package com.unb.imageeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

public class EditorCanvasView extends View {

    public interface Listener {
        void onViewportChanged(float zoom, float imageX, float imageY);
        void onTapImage(float imageX, float imageY);
    }

    private final Paint checkerA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkerB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix drawMatrix = new Matrix();
    private final Matrix inverse = new Matrix();

    private Bitmap bitmap;
    private float zoom = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;
    private boolean fitted = false;
    private Listener listener;

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

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
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
                        if (bitmap == null || scaleDetector.isInProgress()) return false;
                        offsetX -= distanceX;
                        offsetY -= distanceY;
                        invalidate();
                        notifyViewport();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (bitmap == null) return false;
                        if (zoom > 1.05f) fitToView();
                        else zoomAt(e.getX(), e.getY(), 2f);
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (bitmap == null || listener == null) return false;
                        float[] p = screenToImage(e.getX(), e.getY());
                        listener.onTapImage(p[0], p[1]);
                        return true;
                    }
                });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        fitted = false;
        post(this::fitToView);
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void clearBitmap() {
        bitmap = null;
        fitted = false;
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean a = scaleDetector.onTouchEvent(event);
        boolean b = gestureDetector.onTouchEvent(event);
        return a || b || true;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
