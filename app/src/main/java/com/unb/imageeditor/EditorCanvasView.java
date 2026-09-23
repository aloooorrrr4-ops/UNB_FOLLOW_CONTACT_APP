package com.unb.imageeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class EditorCanvasView extends View {

    public interface Listener {
        void onViewportChanged(float zoom, float imageX, float imageY);
        void onTapImage(float imageX, float imageY);
        void onStrokeCompleted(float[] imagePoints);
    }

    private static final Set<String> STROKE_MODES = new HashSet<>(Arrays.asList(
            "brush", "pencil", "eraser", "clone", "heal", "smudge",
            "dodge_burn", "gradient", "select", "free_select", "crop", "color_picker",
            "text_select"
    ));

    private static final Set<String> DETACHED_POINTER_MODES = new HashSet<>(Arrays.asList(
            "brush", "pencil", "eraser", "clone", "heal", "smudge",
            "dodge_burn", "gradient", "select", "free_select", "crop", "color_picker",
            "text_select"
    ));

    private final Paint checkerA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkerB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointerGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textRegionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedTextRegionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix drawMatrix = new Matrix();
    private final Matrix inverse = new Matrix();

    private final ArrayList<Float> strokePoints = new ArrayList<>();
    private final ArrayList<RectF> detectedTextRegions = new ArrayList<>();
    private RectF selectedTextRegion;

    private Bitmap bitmap;
    private float zoom = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;
    private boolean fitted = false;
    private boolean drawingStroke = false;
    private Listener listener;
    private String interactionMode = "move";
    private float previewStrokeSize = 28f;
    private int previewStrokeColor = Color.WHITE;
    private float pointerOffsetPx;
    private float pointerTouchX;
    private float pointerTouchY;
    private float pointerHeadX;
    private float pointerHeadY;
    private boolean pointerVisible = false;
    private boolean pointerActionEnabled = true;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public EditorCanvasView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(20, 22, 26));

        checkerA.setColor(Color.rgb(55, 58, 64));
        checkerB.setColor(Color.rgb(43, 46, 51));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(Color.rgb(126, 132, 143));

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(previewStrokeColor);

        pointerPaint.setStyle(Paint.Style.STROKE);
        pointerPaint.setStrokeWidth(dp(1.6f));
        pointerPaint.setColor(Color.WHITE);

        pointerGuidePaint.setStyle(Paint.Style.STROKE);
        pointerGuidePaint.setStrokeWidth(dp(1));
        pointerGuidePaint.setColor(Color.argb(110, 220, 225, 235));

        textRegionPaint.setStyle(Paint.Style.STROKE);
        textRegionPaint.setStrokeWidth(dp(1.5f));
        textRegionPaint.setColor(Color.rgb(81, 164, 255));

        selectedTextRegionPaint.setStyle(Paint.Style.STROKE);
        selectedTextRegionPaint.setStrokeWidth(dp(2.5f));
        selectedTextRegionPaint.setColor(Color.rgb(255, 196, 64));

        pointerOffsetPx = dp(92);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        cancelStroke();
                        return bitmap != null;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (bitmap == null) return false;
                        float old = zoom;
                        zoom = clamp(zoom * detector.getScaleFactor(), 0.05f, 16f);
                        float factor = zoom / old;

                        float fx = detector.getFocusX();
                        float fy = detector.getFocusY();
                        offsetX = fx - (fx - offsetX) * factor;
                        offsetY = fy - (fy - offsetY) * factor;

                        invalidate();
                        notifyViewport();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        if (bitmap == null || scaleDetector.isInProgress() || isStrokeMode()) {
                            return false;
                        }
                        offsetX -= distanceX;
                        offsetY -= distanceY;
                        invalidate();
                        notifyViewport();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (bitmap == null || isStrokeMode()) return false;
                        if (zoom > 1.05f) fitToView();
                        else zoomAt(e.getX(), e.getY(), 2f);
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (bitmap == null || listener == null || isStrokeMode()) return false;
                        float[] p = screenToImage(e.getX(), e.getY());
                        listener.onTapImage(p[0], p[1]);
                        return true;
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setInteractionMode(String mode) {
        interactionMode = mode == null ? "move" : mode;
        clearStrokePreview();
        if (usesDetachedPointer()) {
            post(this::ensurePointerVisibleAtCenter);
        } else {
            pointerVisible = false;
            invalidate();
        }
    }

    public String getInteractionMode() {
        return interactionMode;
    }

    public void setPointerActionEnabled(boolean enabled) {
        pointerActionEnabled = enabled;
        if (usesDetachedPointer()) {
            ensurePointerVisibleAtCenter();
        }
        invalidate();
    }

    public boolean isPointerActionEnabled() {
        return pointerActionEnabled;
    }

    public void showPointerNow() {
        ensurePointerVisibleAtCenter();
    }

    public float[] getPointerImagePosition() {
        if (!pointerVisible || bitmap == null) return null;
        return screenToImage(pointerHeadX, pointerHeadY);
    }

    private void ensurePointerVisibleAtCenter() {
        if (!usesDetachedPointer() || getWidth() <= 0 || getHeight() <= 0) return;
        if (!pointerVisible) {
            pointerHeadX = getWidth() / 2f;
            pointerHeadY = getHeight() / 2f;
            pointerTouchX = pointerHeadX;
            pointerTouchY = Math.min(getHeight() - dp(10), pointerHeadY + pointerOffsetPx);
            pointerVisible = true;
        }
        invalidate();
    }

    public void setStrokePreview(float imageSizePx, int color) {
        previewStrokeSize = Math.max(1f, imageSizePx);
        previewStrokeColor = color;
        invalidate();
    }

    public void clearStrokePreview() {
        drawingStroke = false;
        strokePoints.clear();
        invalidate();
    }

    public void setPointerOffsetDp(float offsetDp) {
        pointerOffsetPx = dp(Math.max(36f, Math.min(180f, offsetDp)));
        invalidate();
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        fitted = false;
        clearStrokePreview();
        post(() -> {
            fitToView();
            ensurePointerVisibleAtCenter();
        });
    }

    public void setBitmapPreserveViewport(Bitmap bitmap) {
        this.bitmap = bitmap;
        clearStrokePreview();
        invalidate();
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void clearBitmap() {
        bitmap = null;
        fitted = false;
        clearStrokePreview();
        invalidate();
    }

    public void fitToView() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
        float pad = dp(28);
        float sx = Math.max(0.01f, (getWidth() - pad * 2f) / bitmap.getWidth());
        float sy = Math.max(0.01f, (getHeight() - pad * 2f) / bitmap.getHeight());
        zoom = Math.min(sx, sy);
        offsetX = (getWidth() - bitmap.getWidth() * zoom) / 2f;
        offsetY = (getHeight() - bitmap.getHeight() * zoom) / 2f;
        fitted = true;
        invalidate();
        notifyViewport();
    }

    public void setZoom(float requestedZoom) {
        if (bitmap == null) return;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float old = zoom;
        zoom = clamp(requestedZoom, 0.05f, 16f);
        float factor = zoom / old;
        offsetX = centerX - (centerX - offsetX) * factor;
        offsetY = centerY - (centerY - offsetY) * factor;
        invalidate();
        notifyViewport();
    }

    public float getZoom() {
        return zoom;
    }

    public float[] screenToImage(float sx, float sy) {
        rebuildMatrix();
        if (!drawMatrix.invert(inverse)) return new float[]{0f, 0f};
        float[] p = new float[]{sx, sy};
        inverse.mapPoints(p);
        if (bitmap != null) {
            p[0] = clamp(p[0], 0f, bitmap.getWidth());
            p[1] = clamp(p[1], 0f, bitmap.getHeight());
        }
        return p;
    }

    private void zoomAt(float x, float y, float multiplier) {
        float old = zoom;
        zoom = clamp(zoom * multiplier, 0.05f, 16f);
        float factor = zoom / old;
        offsetX = x - (x - offsetX) * factor;
        offsetY = y - (y - offsetY) * factor;
        invalidate();
        notifyViewport();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (bitmap != null && (!fitted || oldw == 0 || oldh == 0)) {
            post(this::fitToView);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawCheckerboard(canvas);

        if (bitmap == null) {
            drawEmptyState(canvas);
            return;
        }

        rebuildMatrix();
        canvas.drawBitmap(bitmap, drawMatrix, imagePaint);

        RectF r = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        drawMatrix.mapRect(r);
        canvas.drawRect(r, borderPaint);

        drawTextRegions(canvas);
        drawStrokePreview(canvas);
        drawDetachedPointer(canvas);
    }

    public void setDetectedTextRegions(List<RectF> regions) {
        detectedTextRegions.clear();
        if (regions != null) {
            for (RectF r : regions) {
                if (r != null) detectedTextRegions.add(new RectF(r));
            }
        }
        selectedTextRegion = null;
        invalidate();
    }

    public void setSelectedTextRegion(RectF region) {
        selectedTextRegion = region == null ? null : new RectF(region);
        invalidate();
    }

    public void clearDetectedTextRegions() {
        detectedTextRegions.clear();
        selectedTextRegion = null;
        invalidate();
    }

    private void drawTextRegions(Canvas canvas) {
        if (detectedTextRegions.isEmpty()) return;

        for (RectF region : detectedTextRegions) {
            RectF screen = new RectF(region);
            drawMatrix.mapRect(screen);
            canvas.drawRect(screen, textRegionPaint);
        }

        if (selectedTextRegion != null) {
            RectF selected = new RectF(selectedTextRegion);
            drawMatrix.mapRect(selected);
            canvas.drawRect(selected, selectedTextRegionPaint);
        }
    }

    private void drawDetachedPointer(Canvas canvas) {
        if (!pointerVisible || !usesDetachedPointer()) return;

        canvas.drawLine(pointerTouchX, pointerTouchY, pointerHeadX, pointerHeadY,
                pointerGuidePaint);

        boolean paintTool = "brush".equals(interactionMode) ||
                "pencil".equals(interactionMode) ||
                "eraser".equals(interactionMode) ||
                "clone".equals(interactionMode) ||
                "heal".equals(interactionMode) ||
                "smudge".equals(interactionMode) ||
                "dodge_burn".equals(interactionMode);

        if (paintTool) {
            float radius = Math.max(dp(3), (previewStrokeSize * zoom) / 2f);
            pointerPaint.setColor("eraser".equals(interactionMode)
                    ? Color.rgb(255, 105, 105)
                    : previewStrokeColor);
            pointerPaint.setStrokeWidth(dp(1.6f));
            canvas.drawCircle(pointerHeadX, pointerHeadY, radius, pointerPaint);
            canvas.drawLine(pointerHeadX - dp(4), pointerHeadY,
                    pointerHeadX + dp(4), pointerHeadY, pointerPaint);
            canvas.drawLine(pointerHeadX, pointerHeadY - dp(4),
                    pointerHeadX, pointerHeadY + dp(4), pointerPaint);
        } else {
            pointerPaint.setColor(Color.WHITE);
            pointerPaint.setStrokeWidth(dp(1.5f));
            float r = dp(10);
            canvas.drawCircle(pointerHeadX, pointerHeadY, r, pointerPaint);
            canvas.drawLine(pointerHeadX - r - dp(4), pointerHeadY,
                    pointerHeadX + r + dp(4), pointerHeadY, pointerPaint);
            canvas.drawLine(pointerHeadX, pointerHeadY - r - dp(4),
                    pointerHeadX, pointerHeadY + r + dp(4), pointerPaint);
        }

        Paint touch = pointerGuidePaint;
        canvas.drawCircle(pointerTouchX, pointerTouchY, dp(5), touch);
    }

    private void updateDetachedPointer(float touchX, float touchY) {
        pointerTouchX = touchX;
        pointerTouchY = touchY;
        pointerHeadX = touchX;
        pointerHeadY = touchY - pointerOffsetPx;
        pointerVisible = usesDetachedPointer();
    }

    private boolean usesDetachedPointer() {
        return DETACHED_POINTER_MODES.contains(interactionMode);
    }

    private void drawStrokePreview(Canvas canvas) {
        if (strokePoints.size() < 2) return;

        strokePaint.setColor("eraser".equals(interactionMode)
                ? Color.argb(170, 235, 80, 80)
                : previewStrokeColor);
        strokePaint.setStrokeWidth(Math.max(dp(1), previewStrokeSize * zoom));

        float[] first = imageToScreen(strokePoints.get(0), strokePoints.get(1));

        if ("gradient".equals(interactionMode) || "select".equals(interactionMode) || "crop".equals(interactionMode)) {
            if (strokePoints.size() < 4) return;
            float[] last = imageToScreen(
                    strokePoints.get(strokePoints.size() - 2),
                    strokePoints.get(strokePoints.size() - 1));

            if ("select".equals(interactionMode) || "crop".equals(interactionMode)) {
                strokePaint.setStrokeWidth(dp(1.5f));
                strokePaint.setColor(Color.argb(230, 255, 255, 255));
                float left = Math.min(first[0], last[0]);
                float top = Math.min(first[1], last[1]);
                float right = Math.max(first[0], last[0]);
                float bottom = Math.max(first[1], last[1]);
                canvas.drawRect(left, top, right, bottom, strokePaint);
            } else {
                strokePaint.setStrokeWidth(dp(3));
                canvas.drawLine(first[0], first[1], last[0], last[1], strokePaint);
            }
            return;
        }

        Path path = new Path();
        path.moveTo(first[0], first[1]);
        for (int i = 2; i + 1 < strokePoints.size(); i += 2) {
            float[] p = imageToScreen(strokePoints.get(i), strokePoints.get(i + 1));
            path.lineTo(p[0], p[1]);
        }
        canvas.drawPath(path, strokePaint);
    }

    private float[] imageToScreen(float x, float y) {
        rebuildMatrix();
        float[] p = new float[]{x, y};
        drawMatrix.mapPoints(p);
        return p;
    }

    private void drawCheckerboard(Canvas canvas) {
        int size = dp(18);
        int cols = getWidth() / size + 1;
        int rows = getHeight() / size + 1;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                Paint p = ((x + y) & 1) == 0 ? checkerA : checkerB;
                canvas.drawRect(x * size, y * size, (x + 1) * size, (y + 1) * size, p);
            }
        }
    }

    private void drawEmptyState(Canvas canvas) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(194, 199, 207));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(18));
        canvas.drawText("افتح صورة لبدء التحرير", getWidth() / 2f, getHeight() / 2f, p);

        p.setColor(Color.rgb(135, 141, 151));
        p.setTextSize(dp(13));
        canvas.drawText("يمكنك استخدام اللمس، القلم، الماوس ولوحة المفاتيح",
                getWidth() / 2f, getHeight() / 2f + dp(28), p);
    }

    private void rebuildMatrix() {
        drawMatrix.reset();
        drawMatrix.postScale(zoom, zoom);
        drawMatrix.postTranslate(offsetX, offsetY);
    }

    private void notifyViewport() {
        if (listener == null) return;
        float[] center = screenToImage(getWidth() / 2f, getHeight() / 2f);
        listener.onViewportChanged(zoom, center[0], center[1]);
    }

    private boolean isStrokeMode() {
        return STROKE_MODES.contains(interactionMode);
    }

    private void addStrokePoint(float screenX, float screenY) {
        float workingY = usesDetachedPointer() ? screenY - pointerOffsetPx : screenY;
        float[] p = screenToImage(screenX, workingY);
        if (strokePoints.size() >= 2) {
            float px = strokePoints.get(strokePoints.size() - 2);
            float py = strokePoints.get(strokePoints.size() - 1);
            float dx = p[0] - px;
            float dy = p[1] - py;
            float minStep = Math.max(0.5f, 1.5f / Math.max(zoom, 0.1f));
            if ((dx * dx + dy * dy) < minStep * minStep) return;
        }
        strokePoints.add(p[0]);
        strokePoints.add(p[1]);
    }

    private void finishStroke() {
        if (!drawingStroke) return;
        drawingStroke = false;
        if (listener == null || strokePoints.size() < 2) {
            clearStrokePreview();
            return;
        }

        float[] points = new float[strokePoints.size()];
        for (int i = 0; i < strokePoints.size(); i++) points[i] = strokePoints.get(i);
        listener.onStrokeCompleted(points);
        invalidate();
    }

    private void cancelStroke() {
        drawingStroke = false;
        strokePoints.clear();
        if (usesDetachedPointer()) {
            ensurePointerVisibleAtCenter();
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return true;

        scaleDetector.onTouchEvent(event);

        if (event.getPointerCount() >= 2 || scaleDetector.isInProgress()) {
            cancelStroke();
            gestureDetector.onTouchEvent(event);
            return true;
        }

        if (!isStrokeMode()) {
            gestureDetector.onTouchEvent(event);
            return true;
        }

        if (usesDetachedPointer() && !pointerActionEnabled) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    updateDetachedPointer(event.getX(), event.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                strokePoints.clear();
                drawingStroke = true;
                updateDetachedPointer(event.getX(), event.getY());
                addStrokePoint(event.getX(), event.getY());
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!drawingStroke) return true;
                updateDetachedPointer(event.getX(), event.getY());
                for (int i = 0; i < event.getHistorySize(); i++) {
                    addStrokePoint(event.getHistoricalX(i), event.getHistoricalY(i));
                }
                addStrokePoint(event.getX(), event.getY());
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (drawingStroke) {
                    updateDetachedPointer(event.getX(), event.getY());
                    addStrokePoint(event.getX(), event.getY());
                    finishStroke();
                }
                pointerVisible = usesDetachedPointer();
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelStroke();
                return true;

            default:
                return true;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
