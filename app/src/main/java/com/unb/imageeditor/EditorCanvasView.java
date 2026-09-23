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
            "text_select", "fill", "fuzzy_select", "color_select"
    ));

    private static final Set<String> DETACHED_POINTER_MODES = new HashSet<>(Arrays.asList(
            "brush", "pencil", "eraser", "clone", "heal", "smudge",
            "dodge_burn", "gradient", "select", "free_select", "crop", "color_picker",
            "text_select", "fill", "fuzzy_select", "color_select"
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
    private final Paint transformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transformHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint textOverlayBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textOverlayHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix drawMatrix = new Matrix();
    private final Matrix inverse = new Matrix();

    private final ArrayList<Float> strokePoints = new ArrayList<>();
    private final ArrayList<RectF> detectedTextRegions = new ArrayList<>();
    private RectF selectedTextRegion;

    private Bitmap bitmap;
    private Bitmap selectionOverlay;
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
    private float[] transformQuad;
    private int activeTransformHandle = -1;

    // Editable text overlay. It stays separate from the bitmap until the user
    // explicitly commits it, so move/scale/rotate remain non-destructive.
    private String textOverlayText;
    private float textOverlayX;
    private float textOverlayY;
    private float textOverlaySize = 42f;
    private float textOverlayScale = 1f;
    private float textOverlayRotation = 0f;
    private float textOverlayWidthScale = 1f;
    private int textOverlayColor = Color.WHITE;
    private String textOverlayFont = "sans-serif";
    private boolean textOverlayBold = false;
    private boolean textOverlayEditingEnabled = true;
    private int textOverlayGesture = 0; // 1 move, 2 scale, 3 rotate
    private float textOverlayStartTouchX;
    private float textOverlayStartTouchY;
    private float textOverlayStartX;
    private float textOverlayStartY;
    private float textOverlayStartScale;
    private float textOverlayStartRotation;

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

        transformPaint.setStyle(Paint.Style.STROKE);
        transformPaint.setStrokeWidth(dp(2));
        transformPaint.setColor(Color.rgb(255, 196, 64));

        transformHandlePaint.setStyle(Paint.Style.FILL);
        transformHandlePaint.setColor(Color.WHITE);

        textOverlayBoxPaint.setStyle(Paint.Style.STROKE);
        textOverlayBoxPaint.setStrokeWidth(dp(1.5f));
        textOverlayBoxPaint.setColor(Color.rgb(255, 196, 64));
        textOverlayHandlePaint.setStyle(Paint.Style.FILL);
        textOverlayHandlePaint.setColor(Color.WHITE);

        pointerOffsetPx = dp(92);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        cancelStroke();
                        activeTransformHandle = -1;
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
        if ("transform_quad".equals(interactionMode)) {
            pointerVisible = false;
            post(this::resetTransformQuad);
        } else if ("text_place".equals(interactionMode)) {
            pointerVisible = false;
            transformQuad = null;
            activeTransformHandle = -1;
            invalidate();
        } else if (usesDetachedPointer()) {
            post(this::ensurePointerVisibleAtCenter);
        } else {
            pointerVisible = false;
            transformQuad = null;
            activeTransformHandle = -1;
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


    public void startTextOverlay(String text, float x, float y, float size, int color,
                                 String fontFamily, boolean bold, float widthScale) {
        if (!textOverlayEditingEnabled) return;
        if (bitmap == null || text == null || text.trim().isEmpty()) return;
        if (hasTextOverlay()) return;
        textOverlayText = text;
        textOverlayX = clamp(x, 0f, bitmap.getWidth());
        textOverlayY = clamp(y, 0f, bitmap.getHeight());
        textOverlaySize = Math.max(6f, size);
        textOverlayScale = 1f;
        textOverlayRotation = 0f;
        textOverlayColor = color;
        textOverlayFont = fontFamily == null || fontFamily.trim().isEmpty()
                ? "sans-serif" : fontFamily;
        textOverlayBold = bold;
        textOverlayWidthScale = clamp(widthScale, 0.5f, 2.0f);
        setInteractionMode("text_place");
        invalidate();
    }

    public boolean hasTextOverlay() {
        return textOverlayText != null && !textOverlayText.isEmpty();
    }

    public void setTextOverlayEditingEnabled(boolean enabled) {
        textOverlayEditingEnabled = enabled;
        if (!enabled) textOverlayGesture = 0;
    }

    public void clearTextOverlay() {
        textOverlayText = null;
        textOverlayGesture = 0;
        if ("text_place".equals(interactionMode)) {
            interactionMode = "text";
        }
        invalidate();
    }

    public void updateTextOverlayStyle(float size, int color, String fontFamily,
                                       boolean bold, float widthScale) {
        if (!textOverlayEditingEnabled || !hasTextOverlay()) return;
        textOverlaySize = Math.max(6f, size);
        textOverlayColor = color;
        if (fontFamily != null && !fontFamily.trim().isEmpty()) {
            textOverlayFont = fontFamily;
        }
        textOverlayBold = bold;
        textOverlayWidthScale = clamp(widthScale, 0.5f, 2.0f);
        invalidate();
    }

    // text, x, y, baseSize, scale, rotation, widthScale, color are read through
    // dedicated getters to avoid coupling the Activity to View internals.
    public String getTextOverlayText() { return textOverlayText; }
    public float getTextOverlayX() { return textOverlayX; }
    public float getTextOverlayY() { return textOverlayY; }
    public float getTextOverlaySize() { return textOverlaySize; }
    public float getTextOverlayScale() { return textOverlayScale; }
    public float getTextOverlayRotation() { return textOverlayRotation; }
    public float getTextOverlayWidthScale() { return textOverlayWidthScale; }
    public int getTextOverlayColor() { return textOverlayColor; }
    public String getTextOverlayFont() { return textOverlayFont; }
    public boolean isTextOverlayBold() { return textOverlayBold; }

    public void resetTransformQuad() {
        if (bitmap == null) return;
        transformQuad = new float[]{
                0f, 0f,
                bitmap.getWidth(), 0f,
                bitmap.getWidth(), bitmap.getHeight(),
                0f, bitmap.getHeight()
        };
        activeTransformHandle = -1;
        invalidate();
    }

    public float[] getTransformQuad() {
        return transformQuad == null ? null : transformQuad.clone();
    }

    public void clearTransformQuad() {
        transformQuad = null;
        activeTransformHandle = -1;
        invalidate();
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
        clearTextOverlay();
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

    public void setSelectionOverlay(Bitmap overlay) {
        selectionOverlay = overlay;
        invalidate();
    }

    public void clearSelectionOverlay() {
        selectionOverlay = null;
        invalidate();
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void clearBitmap() {
        bitmap = null;
        selectionOverlay = null;
        clearTextOverlay();
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
        if (selectionOverlay != null) {
            canvas.drawBitmap(selectionOverlay, drawMatrix, imagePaint);
        }

        RectF r = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        drawMatrix.mapRect(r);
        canvas.drawRect(r, borderPaint);

        drawTextRegions(canvas);
        drawTextOverlay(canvas);
        drawTransformQuad(canvas);
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


    private void configureTextOverlayPaint() {
        textOverlayPaint.setColor(textOverlayColor);
        textOverlayPaint.setTextSize(textOverlaySize);
        textOverlayPaint.setTextScaleX(textOverlayWidthScale);
        textOverlayPaint.setTextAlign(Paint.Align.CENTER);
        textOverlayPaint.setTypeface(android.graphics.Typeface.create(
                textOverlayFont,
                textOverlayBold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
    }

    private RectF textOverlayLocalBounds() {
        configureTextOverlayPaint();
        String[] lines = textOverlayText == null ? new String[]{""}
                : textOverlayText.split("\\n", -1);
        float maxWidth = 1f;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, textOverlayPaint.measureText(line));
        }
        Paint.FontMetrics fm = textOverlayPaint.getFontMetrics();
        float lineHeight = Math.max(textOverlaySize * 1.2f, fm.descent - fm.ascent);
        float height = Math.max(lineHeight, lineHeight * lines.length);
        float pad = Math.max(4f, textOverlaySize * 0.12f);
        return new RectF(-maxWidth / 2f - pad, -height / 2f - pad,
                maxWidth / 2f + pad, height / 2f + pad);
    }

    private void drawTextOverlay(Canvas canvas) {
        if (!hasTextOverlay()) return;
        configureTextOverlayPaint();
        RectF bounds = textOverlayLocalBounds();
        String[] lines = textOverlayText.split("\\n", -1);
        Paint.FontMetrics fm = textOverlayPaint.getFontMetrics();
        float lineHeight = Math.max(textOverlaySize * 1.2f, fm.descent - fm.ascent);
        float firstBaseline = -((lines.length - 1) * lineHeight) / 2f
                - (fm.ascent + fm.descent) / 2f;

        canvas.save();
        canvas.concat(drawMatrix);
        canvas.translate(textOverlayX, textOverlayY);
        canvas.rotate(textOverlayRotation);
        canvas.scale(textOverlayScale, textOverlayScale);

        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], 0f, firstBaseline + i * lineHeight, textOverlayPaint);
        }
        canvas.drawRect(bounds, textOverlayBoxPaint);

        float handleRadius = Math.max(7f / Math.max(zoom, 0.1f),
                textOverlaySize * 0.09f);
        canvas.drawCircle(bounds.right, bounds.bottom, handleRadius, textOverlayHandlePaint);
        canvas.drawCircle(bounds.right, bounds.top, handleRadius, textOverlayHandlePaint);
        canvas.restore();
    }

    private float[] textOverlayScreenPoint(float localX, float localY) {
        float cos = (float) Math.cos(Math.toRadians(textOverlayRotation));
        float sin = (float) Math.sin(Math.toRadians(textOverlayRotation));
        float sx = localX * textOverlayScale;
        float sy = localY * textOverlayScale;
        float ix = textOverlayX + sx * cos - sy * sin;
        float iy = textOverlayY + sx * sin + sy * cos;
        return imageToScreen(ix, iy);
    }

    private float textOverlayScreenDistance(float sx, float sy, float localX, float localY) {
        float[] p = textOverlayScreenPoint(localX, localY);
        return (float) Math.hypot(sx - p[0], sy - p[1]);
    }

    private boolean isPointInsideTextOverlay(float sx, float sy) {
        float[] p = screenToImage(sx, sy);
        float dx = p[0] - textOverlayX;
        float dy = p[1] - textOverlayY;
        float rad = (float) Math.toRadians(-textOverlayRotation);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float lx = (dx * cos - dy * sin) / Math.max(0.05f, textOverlayScale);
        float ly = (dx * sin + dy * cos) / Math.max(0.05f, textOverlayScale);
        return textOverlayLocalBounds().contains(lx, ly);
    }

    private boolean handleTextOverlayTouch(MotionEvent event) {
        if (!textOverlayEditingEnabled) return true;
        if (!hasTextOverlay()) return true;
        RectF b = textOverlayLocalBounds();
        float sx = event.getX();
        float sy = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float hit = dp(30);
                if (textOverlayScreenDistance(sx, sy, b.right, b.bottom) <= hit) {
                    textOverlayGesture = 2;
                } else if (textOverlayScreenDistance(sx, sy, b.right, b.top) <= hit) {
                    textOverlayGesture = 3;
                } else if (isPointInsideTextOverlay(sx, sy)) {
                    textOverlayGesture = 1;
                } else {
                    textOverlayGesture = 0;
                }

                textOverlayStartTouchX = sx;
                textOverlayStartTouchY = sy;
                textOverlayStartX = textOverlayX;
                textOverlayStartY = textOverlayY;
                textOverlayStartScale = textOverlayScale;
                textOverlayStartRotation = textOverlayRotation;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (textOverlayGesture == 1) {
                    float[] from = screenToImage(textOverlayStartTouchX, textOverlayStartTouchY);
                    float[] to = screenToImage(sx, sy);
                    textOverlayX = clamp(textOverlayStartX + (to[0] - from[0]),
                            0f, bitmap.getWidth());
                    textOverlayY = clamp(textOverlayStartY + (to[1] - from[1]),
                            0f, bitmap.getHeight());
                } else if (textOverlayGesture == 2) {
                    float[] center = imageToScreen(textOverlayStartX, textOverlayStartY);
                    float startDist = Math.max(dp(12),
                            (float) Math.hypot(textOverlayStartTouchX - center[0],
                                    textOverlayStartTouchY - center[1]));
                    float nowDist = (float) Math.hypot(sx - center[0], sy - center[1]);
                    textOverlayScale = clamp(textOverlayStartScale * nowDist / startDist,
                            0.15f, 8f);
                } else if (textOverlayGesture == 3) {
                    float[] center = imageToScreen(textOverlayStartX, textOverlayStartY);
                    float startAngle = (float) Math.toDegrees(Math.atan2(
                            textOverlayStartTouchY - center[1],
                            textOverlayStartTouchX - center[0]));
                    float nowAngle = (float) Math.toDegrees(Math.atan2(
                            sy - center[1], sx - center[0]));
                    textOverlayRotation = textOverlayStartRotation + (nowAngle - startAngle);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                textOverlayGesture = 0;
                invalidate();
                return true;

            default:
                return true;
        }
    }

    private void drawTransformQuad(Canvas canvas) {
        if (transformQuad == null || !"transform_quad".equals(interactionMode)) return;

        float[][] p = new float[4][];
        for (int i = 0; i < 4; i++) {
            p[i] = imageToScreen(transformQuad[i * 2], transformQuad[i * 2 + 1]);
        }

        Path path = new Path();
        path.moveTo(p[0][0], p[0][1]);
        for (int i = 1; i < 4; i++) path.lineTo(p[i][0], p[i][1]);
        path.close();
        canvas.drawPath(path, transformPaint);

        float r = dp(7);
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(p[i][0], p[i][1], r, transformHandlePaint);
            canvas.drawCircle(p[i][0], p[i][1], r, transformPaint);
        }
    }

    private int findTransformHandle(float sx, float sy) {
        if (transformQuad == null) return -1;
        float maxDist = dp(26);
        float best = maxDist * maxDist;
        int index = -1;
        for (int i = 0; i < 4; i++) {
            float[] p = imageToScreen(transformQuad[i * 2], transformQuad[i * 2 + 1]);
            float dx = sx - p[0];
            float dy = sy - p[1];
            float d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                index = i;
            }
        }
        return index;
    }

    private boolean handleTransformTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeTransformHandle = findTransformHandle(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activeTransformHandle >= 0) {
                    float[] p = screenToImage(event.getX(), event.getY());
                    transformQuad[activeTransformHandle * 2] = p[0];
                    transformQuad[activeTransformHandle * 2 + 1] = p[1];
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeTransformHandle = -1;
                invalidate();
                return true;
            default:
                return true;
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
                "dodge_burn".equals(interactionMode) ||
                "fill".equals(interactionMode);

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

        if ("text_place".equals(interactionMode)) {
            return handleTextOverlayTouch(event);
        }

        scaleDetector.onTouchEvent(event);

        if (event.getPointerCount() >= 2 || scaleDetector.isInProgress()) {
            cancelStroke();
            activeTransformHandle = -1;
            gestureDetector.onTouchEvent(event);
            return true;
        }

        if ("transform_quad".equals(interactionMode)) {
            return handleTransformTouch(event);
        }

        if ("text_select".equals(interactionMode)) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    updateDetachedPointer(event.getX(), event.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    updateDetachedPointer(event.getX(), event.getY());
                    if (pointerActionEnabled && listener != null) {
                        float[] p = getPointerImagePosition();
                        if (p != null) listener.onTapImage(p[0], p[1]);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
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
