package com.unb.imageeditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class OcrOverlayImageView extends ImageView {
    public interface OnBlockClickListener {
        void onBlockClick(JSONObject block);
    }

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<JSONObject> blocks = new ArrayList<>();
    private int selectedIndex = -1;
    private OnBlockClickListener listener;

    public OcrOverlayImageView(Context context) {
        super(context);
        init();
    }

    public OcrOverlayImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setScaleType(ScaleType.FIT_CENTER);

        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(2));
        boxPaint.setColor(Color.rgb(0, 122, 255));

        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(dp(4));
        selectedPaint.setColor(Color.rgb(255, 64, 64));
    }

    public void setOnBlockClickListener(OnBlockClickListener listener) {
        this.listener = listener;
    }

    public void setBlocks(JSONArray array) {
        blocks.clear();
        selectedIndex = -1;

        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject block = array.optJSONObject(i);
                if (block != null) blocks.add(block);
            }
        }

        invalidate();
    }

    public void selectBlock(String id) {
        selectedIndex = -1;

        // Keep the preview clean. Show only the text currently selected from
        // the list instead of covering the whole image with OCR rectangles.
        if (selectedIndex < 0 || selectedIndex >= blocks.size()) return;
        for (int i = selectedIndex; i <= selectedIndex; i++) {
            if (id.equals(blocks.get(i).optString("id"))) {
                selectedIndex = i;
                break;
            }
        }

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (getDrawable() == null || blocks.isEmpty()) return;

        Matrix matrix = getImageMatrix();

        for (int i = 0; i < blocks.size(); i++) {
            JSONObject bbox = blocks.get(i).optJSONObject("bbox");
            if (bbox == null) continue;

            RectF rect = new RectF(
                    bbox.optInt("x"),
                    bbox.optInt("y"),
                    bbox.optInt("x") + bbox.optInt("w"),
                    bbox.optInt("y") + bbox.optInt("h")
            );

            matrix.mapRect(rect);
            canvas.drawRoundRect(rect, dp(7), dp(7), selectedPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || getDrawable() == null) {
            return true;
        }

        Matrix inverse = new Matrix();
        if (!getImageMatrix().invert(inverse)) return true;

        float[] point = new float[]{event.getX(), event.getY()};
        inverse.mapPoints(point);

        for (int i = blocks.size() - 1; i >= 0; i--) {
            JSONObject bbox = blocks.get(i).optJSONObject("bbox");
            if (bbox == null) continue;

            float x = bbox.optInt("x");
            float y = bbox.optInt("y");
            float w = bbox.optInt("w");
            float h = bbox.optInt("h");

            if (point[0] >= x && point[0] <= x + w &&
                    point[1] >= y && point[1] <= y + h) {

                selectedIndex = i;
                invalidate();

                if (listener != null) {
                    listener.onBlockClick(blocks.get(i));
                }
                return true;
            }
        }

        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
