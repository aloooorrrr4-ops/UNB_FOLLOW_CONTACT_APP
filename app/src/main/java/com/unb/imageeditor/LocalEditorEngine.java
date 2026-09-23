package com.unb.imageeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * First offline engine for UNB Pro Editor.
 *
 * The UI talks only to this class. Later phases can move heavy filters to
 * C++/NDK/GPU without changing the existing editor interface.
 */
public final class LocalEditorEngine {

    private static final int MAX_HISTORY = 12;

    public static final class LayerInfo {
        public final int index;
        public final String name;
        public final boolean visible;
        public final int opacity;
        public final boolean active;

        LayerInfo(int index, String name, boolean visible, int opacity, boolean active) {
            this.index = index;
            this.name = name;
            this.visible = visible;
            this.opacity = opacity;
            this.active = active;
        }
    }

    private static final class Layer {
        String name;
        Bitmap bitmap;
        boolean visible = true;
        int opacity = 255;

        Layer(String name, Bitmap bitmap) {
            this.name = name;
            this.bitmap = bitmap;
        }
    }

    private Bitmap bitmap;
    private Rect selection;
    private final Deque<Bitmap> undo = new ArrayDeque<>();
    private final Deque<Bitmap> redo = new ArrayDeque<>();
    private final ArrayList<Layer> layers = new ArrayList<>();
    private int activeLayerIndex = 0;

    private Bitmap adjustmentBase;
    private Bitmap adjustmentSessionStart;
    private String adjustmentOperation;
    private int adjustmentValue;
    private int brightnessAdjustment;
    private int contrastAdjustment;
    private int saturationAdjustment;

    public synchronized void open(Bitmap source) {
        if (source == null) throw new IllegalArgumentException("الصورة غير صالحة");
        undo.clear();
        redo.clear();
        layers.clear();
        bitmap = mutable(source);
        layers.add(new Layer("Background", bitmap));
        activeLayerIndex = 0;
        selection = null;
        clearAdjustmentPipeline();
    }

    public synchronized boolean hasImage() {
        return bitmap != null;
    }

    public synchronized Bitmap current() {
        return compositeLayers();
    }

    public synchronized List<LayerInfo> layerInfo() {
        ArrayList<LayerInfo> result = new ArrayList<>();
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            result.add(new LayerInfo(i, layer.name, layer.visible, layer.opacity,
                    i == activeLayerIndex));
        }
        return result;
    }

    public synchronized int width() {
        return layers.isEmpty() ? (bitmap == null ? 0 : bitmap.getWidth())
                : layers.get(0).bitmap.getWidth();
    }

    public synchronized int height() {
        return layers.isEmpty() ? (bitmap == null ? 0 : bitmap.getHeight())
                : layers.get(0).bitmap.getHeight();
    }

    public synchronized int undoDepth() {
        return undo.size();
    }

    public synchronized int redoDepth() {
        return redo.size();
    }

    public synchronized Bitmap undo() {
        requireImage();
        clearAdjustmentPipeline();
        if (undo.isEmpty()) throw new IllegalStateException("لا توجد خطوة أقدم");
        redo.addLast(copy(bitmap));
        trim(redo);
        bitmap = undo.removeLast();
        syncActiveLayer();
        selection = null;
        return compositeLayers();
    }

    public synchronized Bitmap redo() {
        requireImage();
        clearAdjustmentPipeline();
        if (redo.isEmpty()) throw new IllegalStateException("لا توجد خطوة لإعادتها");
        undo.addLast(copy(bitmap));
        trim(undo);
        bitmap = redo.removeLast();
        syncActiveLayer();
        selection = null;
        return compositeLayers();
    }

    public synchronized int beginAdjustment(String operation, int requestedValue) {
        requireImage();
        String op = normalizeAdjustment(operation);

        if (adjustmentBase == null) {
            adjustmentBase = copy(bitmap);
        }

        adjustmentSessionStart = copy(bitmap);
        adjustmentOperation = op;
        adjustmentValue = committedAdjustment(op);
        return adjustmentValue;
    }

    public synchronized Bitmap previewAdjustment(String operation, int value) {
        requireImage();
        String op = normalizeAdjustment(operation);
        if (adjustmentOperation == null || !op.equals(adjustmentOperation)) {
            beginAdjustment(op, committedAdjustment(op));
        }

        adjustmentValue = Math.max(-100, Math.min(100, value));
        bitmap = copy(adjustmentBase);

        int b = "brightness".equals(op) ? adjustmentValue : brightnessAdjustment;
        int c = "contrast".equals(op) ? adjustmentValue : contrastAdjustment;
        int s = "saturation".equals(op) ? adjustmentValue : saturationAdjustment;

        if (b != 0) brightness(b);
        if (c != 0) contrast(c);
        if (s != 0) saturation(s);
        syncActiveLayer();
        return compositeLayers();
    }

    public synchronized Bitmap commitAdjustment() {
        requireImage();
        if (adjustmentOperation == null) return compositeLayers();

        int previous = committedAdjustment(adjustmentOperation);
        if (adjustmentValue != previous) {
            if (adjustmentSessionStart != null) {
                undo.addLast(adjustmentSessionStart);
                trim(undo);
            }
            redo.clear();
            setCommittedAdjustment(adjustmentOperation, adjustmentValue);
        } else if (adjustmentSessionStart != null) {
            bitmap = adjustmentSessionStart;
        }

        adjustmentSessionStart = null;
        adjustmentOperation = null;
        adjustmentValue = 0;
        syncActiveLayer();
        return compositeLayers();
    }

    public synchronized Bitmap cancelAdjustment() {
        if (adjustmentSessionStart != null) {
            bitmap = adjustmentSessionStart;
        }
        adjustmentSessionStart = null;
        adjustmentOperation = null;
        adjustmentValue = 0;
        syncActiveLayer();
        return compositeLayers();
    }

    public synchronized boolean hasAdjustmentSession() {
        return adjustmentOperation != null;
    }

    private String normalizeAdjustment(String operation) {
        String op = operation == null ? "" : operation.trim().toLowerCase();
        if (!"brightness".equals(op) && !"contrast".equals(op) && !"saturation".equals(op)) {
            throw new IllegalArgumentException("تعديل غير مدعوم");
        }
        return op;
    }

    private int committedAdjustment(String operation) {
        if ("brightness".equals(operation)) return brightnessAdjustment;
        if ("contrast".equals(operation)) return contrastAdjustment;
        if ("saturation".equals(operation)) return saturationAdjustment;
        return 0;
    }

    private void setCommittedAdjustment(String operation, int value) {
        if ("brightness".equals(operation)) brightnessAdjustment = value;
        else if ("contrast".equals(operation)) contrastAdjustment = value;
        else if ("saturation".equals(operation)) saturationAdjustment = value;
    }

    private void clearAdjustmentPipeline() {
        adjustmentBase = null;
        adjustmentSessionStart = null;
        adjustmentOperation = null;
        adjustmentValue = 0;
        brightnessAdjustment = 0;
        contrastAdjustment = 0;
        saturationAdjustment = 0;
    }

    public synchronized Bitmap apply(String operation, JSONObject params) {
        requireImage();
        clearAdjustmentPipeline();
        String op = operation == null ? "" : operation.trim().toLowerCase();
        JSONObject p = params == null ? new JSONObject() : params;

        switch (op) {
            case "add_layer":
                return addLayer(p.optString("name", "Layer " + (layers.size() + 1)));
            case "duplicate_layer":
                return duplicateLayer();
            case "delete_layer":
                return deleteLayer();
            case "set_active_layer":
                return setActiveLayer(p.optInt("index", activeLayerIndex));
            case "toggle_layer_visibility":
                return toggleLayerVisibility(p.optInt("index", activeLayerIndex));
            case "set_layer_opacity":
                return setLayerOpacity(p.optInt("index", activeLayerIndex),
                        p.optInt("opacity", 255));
            case "merge_down":
                return mergeDown();
            case "flatten":
            case "merge_visible":
                return flattenLayers();
        }

        if (selectionOperation(op, p)) return compositeLayers();

        if (isGeometryOperation(op) && layers.size() > 1) {
            flattenLayersInternal();
        }

        Bitmap original = bitmap;
        Bitmap before = copy(original);
        // Work on a separate mutable frame. The Canvas can keep drawing the
        // previous frame while a heavy local operation runs on the worker.
        bitmap = copy(original);
        try {
            switch (op) {
                case "rotate":
                    rotate(p.optInt("degrees", 90));
                    break;
                case "flip_horizontal":
                    flip(true);
                    break;
                case "flip_vertical":
                    flip(false);
                    break;
                case "crop":
                    crop(p);
                    break;
                case "resize":
                case "scale":
                    resize(p);
                    break;
                case "perspective":
                    perspective(p);
                    break;
                case "brightness":
                    brightness((float) p.optDouble("value", 0));
                    break;
                case "contrast":
                    contrast((float) p.optDouble("value", 0));
                    break;
                case "saturation":
                case "hue_saturation":
                    saturation((float) p.optDouble("value", p.optDouble("saturation", 0)));
                    break;
                case "desaturate":
                    saturation(-100f);
                    break;
                case "invert":
                    invert();
                    break;
                case "threshold":
                    threshold(p.optDouble("low", 50));
                    break;
                case "posterize":
                    posterize(p.optInt("levels", 4));
                    break;
                case "levels":
                    levels(p);
                    break;
                case "color_balance":
                    colorBalance(p);
                    break;
                case "curves":
                    curves((float) p.optDouble("strength", 28));
                    break;
                case "equalize":
                    equalize();
                    break;
                case "clone":
                    cloneHealStroke(p, false);
                    break;
                case "heal":
                    cloneHealStroke(p, true);
                    break;
                case "smudge":
                    smudgeStroke(p);
                    break;
                case "dodge_burn":
                    dodgeBurnStroke(p);
                    break;
                case "gaussian_blur":
                    boxBlur(Math.max(1, p.optInt("radius", 4)));
                    break;
                case "noise_reduction":
                    boxBlur(Math.max(1, p.optInt("radius", 1)));
                    break;
                case "unsharp_mask":
                    unsharp((float) p.optDouble("amount", 1.15),
                            Math.max(1, p.optInt("radius", 2)));
                    break;
                case "bloom":
                    bloom(Math.max(1, p.optInt("radius", 5)),
                            (float) p.optDouble("strength", 0.35));
                    break;
                case "emboss":
                    emboss();
                    break;
                case "edge":
                    edgeDetect();
                    break;
                case "oilify":
                    oilify();
                    break;
                case "pixelize":
                case "mosaic":
                    pixelize(Math.max(2, p.optInt("size", 12)));
                    break;
                case "motion_blur":
                    motionBlur(Math.max(2, p.optInt("radius", 9)));
                    break;
                case "color_temperature":
                    colorTemperature(p.optInt("value", 20));
                    break;
                case "fill":
                    fill(parseColor(p.optString("color", "#000000")));
                    break;
                case "paintbrush":
                case "brush":
                    stroke(p, false, false);
                    break;
                case "pencil":
                    stroke(p, true, false);
                    break;
                case "eraser":
                    stroke(p, false, true);
                    break;
                case "fill_stroke":
                    stroke(p, false, false);
                    break;
                case "gradient":
                    gradient(p);
                    break;
                case "add_text":
                    addText(p);
                    break;
                case "replace_text_region":
                    replaceTextRegion(p);
                    break;

                default:
                    throw new UnsupportedOperationException(
                            "الأداة " + op + " ستُنقل للمحرك المحلي في المرحلة التالية");
            }

            undo.addLast(before);
            trim(undo);
            redo.clear();
            syncActiveLayer();
            return compositeLayers();
        } catch (RuntimeException e) {
            bitmap = original;
            syncActiveLayer();
            throw e;
        }
    }

    public synchronized byte[] export(String format, int quality) {
        requireImage();
        String fmt = format == null ? "png" : format.toLowerCase();
        Bitmap.CompressFormat cf;
        int q = Math.max(1, Math.min(100, quality));

        switch (fmt) {
            case "jpg":
            case "jpeg":
                cf = Bitmap.CompressFormat.JPEG;
                break;
            case "webp":
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    cf = Bitmap.CompressFormat.WEBP_LOSSLESS;
                    q = 100;
                } else {
                    cf = Bitmap.CompressFormat.WEBP;
                }
                break;
            case "png":
                cf = Bitmap.CompressFormat.PNG;
                q = 100;
                break;
            default:
                throw new UnsupportedOperationException(
                        "النسخة المحلية الحالية تحفظ PNG / JPG / WEBP");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap exportBitmap = compositeLayers();
        if (!exportBitmap.compress(cf, q, out)) {
            throw new IllegalStateException("فشل ضغط الصورة");
        }
        return out.toByteArray();
    }

    private boolean isGeometryOperation(String op) {
        return "rotate".equals(op) || "flip_horizontal".equals(op) ||
                "flip_vertical".equals(op) || "crop".equals(op) ||
                "resize".equals(op) || "scale".equals(op) ||
                "perspective".equals(op);
    }

    private Bitmap addLayer(String name) {
        Bitmap layerBitmap = Bitmap.createBitmap(width(), height(), Bitmap.Config.ARGB_8888);
        Layer layer = new Layer(name == null || name.trim().isEmpty()
                ? "Layer " + (layers.size() + 1) : name.trim(), layerBitmap);
        layers.add(layer);
        activeLayerIndex = layers.size() - 1;
        bitmap = layer.bitmap;
        undo.clear();
        redo.clear();
        clearAdjustmentPipeline();
        return compositeLayers();
    }

    private Bitmap duplicateLayer() {
        Layer source = activeLayer();
        Layer copyLayer = new Layer(source.name + " copy", copy(source.bitmap));
        copyLayer.visible = source.visible;
        copyLayer.opacity = source.opacity;
        layers.add(activeLayerIndex + 1, copyLayer);
        activeLayerIndex++;
        bitmap = copyLayer.bitmap;
        undo.clear();
        redo.clear();
        clearAdjustmentPipeline();
        return compositeLayers();
    }

    private Bitmap deleteLayer() {
        if (layers.size() <= 1) {
            throw new IllegalStateException("لا يمكن حذف آخر طبقة");
        }
        layers.remove(activeLayerIndex);
        activeLayerIndex = Math.max(0, Math.min(activeLayerIndex, layers.size() - 1));
        bitmap = layers.get(activeLayerIndex).bitmap;
        undo.clear();
        redo.clear();
        clearAdjustmentPipeline();
        return compositeLayers();
    }

    private Bitmap setActiveLayer(int index) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        syncActiveLayer();
        activeLayerIndex = index;
        bitmap = layers.get(activeLayerIndex).bitmap;
        undo.clear();
        redo.clear();
        clearAdjustmentPipeline();
        return compositeLayers();
    }

    private Bitmap toggleLayerVisibility(int index) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        layers.get(index).visible = !layers.get(index).visible;
        return compositeLayers();
    }

    private Bitmap setLayerOpacity(int index, int opacity) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        layers.get(index).opacity = clamp(opacity, 0, 255);
        return compositeLayers();
    }

    private Bitmap mergeDown() {
        if (activeLayerIndex <= 0 || layers.size() <= 1) {
            throw new IllegalStateException("لا توجد طبقة أسفلها للدمج");
        }

        Layer top = layers.get(activeLayerIndex);
        Layer lower = layers.get(activeLayerIndex - 1);
        Bitmap merged = Bitmap.createBitmap(width(), height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(merged);

        Paint lowerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        lowerPaint.setAlpha(lower.opacity);
        if (lower.visible) canvas.drawBitmap(lower.bitmap, 0, 0, lowerPaint);

        Paint topPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        topPaint.setAlpha(top.opacity);
        if (top.visible) canvas.drawBitmap(top.bitmap, 0, 0, topPaint);

        lower.bitmap = merged;
        lower.opacity = 255;
        lower.visible = true;
        lower.name = lower.name + " + " + top.name;
        layers.remove(activeLayerIndex);
        activeLayerIndex--;
        bitmap = lower.bitmap;
        undo.clear();
        redo.clear();
        clearAdjustmentPipeline();
        return compositeLayers();
    }

    private Bitmap flattenLayers() {
        flattenLayersInternal();
        undo.clear();
        redo.clear();
        clearAdjustmentPipeline();
        return bitmap;
    }

    private void flattenLayersInternal() {
        if (layers.size() <= 1) {
            if (!layers.isEmpty()) bitmap = layers.get(0).bitmap;
            return;
        }

        Bitmap merged = compositeLayers();
        layers.clear();
        Layer flat = new Layer("Background", merged);
        layers.add(flat);
        activeLayerIndex = 0;
        bitmap = merged;
    }

    private Layer activeLayer() {
        if (layers.isEmpty()) throw new IllegalStateException("لا توجد طبقات");
        activeLayerIndex = Math.max(0, Math.min(activeLayerIndex, layers.size() - 1));
        return layers.get(activeLayerIndex);
    }

    private void syncActiveLayer() {
        if (!layers.isEmpty() && bitmap != null) {
            activeLayer().bitmap = bitmap;
        }
    }

    private Bitmap compositeLayers() {
        if (bitmap == null) return null;
        if (layers.size() <= 1) {
            if (!layers.isEmpty()) {
                Layer only = layers.get(0);
                if (only.visible && only.opacity >= 255) return only.bitmap;
            } else {
                return bitmap;
            }
        }

        int w = width();
        int h = height();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        for (Layer layer : layers) {
            if (!layer.visible) continue;
            paint.setAlpha(layer.opacity);
            canvas.drawBitmap(layer.bitmap, 0, 0, paint);
        }
        paint.setAlpha(255);
        return out;
    }

    private boolean selectionOperation(String op, JSONObject p) {
        switch (op) {
            case "select_all":
                selection = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                return true;
            case "select_none":
                selection = null;
                return true;
            case "select_rectangle": {
                int x = clamp(p.optInt("x", 0), 0, bitmap.getWidth() - 1);
                int y = clamp(p.optInt("y", 0), 0, bitmap.getHeight() - 1);
                int w = Math.max(1, p.optInt("width", 1));
                int h = Math.max(1, p.optInt("height", 1));
                selection = new Rect(
                        x, y,
                        Math.min(bitmap.getWidth(), x + w),
                        Math.min(bitmap.getHeight(), y + h));
                return true;
            }
            case "grow":
                if (selection != null) {
                    int s = Math.max(0, p.optInt("steps", 1));
                    selection.left = Math.max(0, selection.left - s);
                    selection.top = Math.max(0, selection.top - s);
                    selection.right = Math.min(bitmap.getWidth(), selection.right + s);
                    selection.bottom = Math.min(bitmap.getHeight(), selection.bottom + s);
                }
                return true;
            case "shrink":
                if (selection != null) {
                    int s = Math.max(0, p.optInt("steps", 1));
                    if (selection.width() > s * 2 && selection.height() > s * 2) {
                        selection.inset(s, s);
                    }
                }
                return true;
            case "feather":
            case "border":
            case "select_polygon": {
                JSONArray pts = p.optJSONArray("points");
                if (pts == null || pts.length() < 6) return true;
                float minX = bitmap.getWidth(), minY = bitmap.getHeight();
                float maxX = 0, maxY = 0;
                for (int i = 0; i + 1 < pts.length(); i += 2) {
                    float x = (float) pts.optDouble(i);
                    float y = (float) pts.optDouble(i + 1);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
                selection = new Rect(
                        clamp(Math.round(minX), 0, bitmap.getWidth() - 1),
                        clamp(Math.round(minY), 0, bitmap.getHeight() - 1),
                        clamp(Math.round(maxX), 1, bitmap.getWidth()),
                        clamp(Math.round(maxY), 1, bitmap.getHeight()));
                return true;
            }
            case "select_color": {
                int target = parseColor(p.optString("color", "#ffffff"));
                selectColorBounds(target, p.optDouble("threshold", 0.15));
                return true;
            }
            case "select_contiguous": {
                int x = clamp((int) Math.round(p.optDouble("x", 0)), 0, bitmap.getWidth() - 1);
                int y = clamp((int) Math.round(p.optDouble("y", 0)), 0, bitmap.getHeight() - 1);
                int target = bitmap.getPixel(x, y);
                selectColorBounds(target, p.optDouble("threshold", 0.15));
                return true;
            }
            case "invert_selection":
                selection = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                return true;
            case "sharpen_selection":
                return true;
            default:
                return false;
        }
    }

    private void rotate(int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), m, true)
                .copy(Bitmap.Config.ARGB_8888, true);
        selection = null;
    }

    private void flip(boolean horizontal) {
        Matrix m = new Matrix();
        m.setScale(horizontal ? -1f : 1f, horizontal ? 1f : -1f);
        m.postTranslate(horizontal ? bitmap.getWidth() : 0,
                horizontal ? 0 : bitmap.getHeight());
        Bitmap out = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(bitmap, m, null);
        bitmap = out;
        selection = null;
    }

    private void crop(JSONObject p) {
        int x = clamp(p.optInt("x", 0), 0, bitmap.getWidth() - 1);
        int y = clamp(p.optInt("y", 0), 0, bitmap.getHeight() - 1);
        int w = Math.max(1, Math.min(
                p.optInt("width", bitmap.getWidth() - x), bitmap.getWidth() - x));
        int h = Math.max(1, Math.min(
                p.optInt("height", bitmap.getHeight() - y), bitmap.getHeight() - y));
        bitmap = Bitmap.createBitmap(bitmap, x, y, w, h)
                .copy(Bitmap.Config.ARGB_8888, true);
        selection = null;
    }

    private void resize(JSONObject p) {
        int w = p.optInt("width", 0);
        int h = p.optInt("height", 0);
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("أبعاد غير صحيحة");
        bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
                .copy(Bitmap.Config.ARGB_8888, true);
        selection = null;
    }

    private void perspective(JSONObject p) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        float topInset = Math.max(0f, Math.min(w * 0.45f,
                (float) p.optDouble("top_inset", w * 0.08f)));
        float bottomInset = Math.max(0f, Math.min(w * 0.45f,
                (float) p.optDouble("bottom_inset", 0f)));
        float verticalShift = Math.max(-h * 0.35f, Math.min(h * 0.35f,
                (float) p.optDouble("vertical_shift", 0f)));

        float[] src = {
                0f, 0f,
                w, 0f,
                w, h,
                0f, h
        };
        float[] dst = {
                topInset, verticalShift,
                w - topInset, -verticalShift,
                w - bottomInset, h,
                bottomInset, h
        };

        Matrix matrix = new Matrix();
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            throw new IllegalStateException("تعذر تطبيق المنظور");
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bitmap, matrix, paint);
        bitmap = out;
        selection = null;
    }

    private void brightness(float value) {
        float offset = Math.max(-100f, Math.min(100f, value)) * 2.55f;
        matrix(new ColorMatrix(new float[]{
                1,0,0,0,offset,
                0,1,0,0,offset,
                0,0,1,0,offset,
                0,0,0,1,0
        }));
    }

    private void contrast(float value) {
        float v = Math.max(-100f, Math.min(100f, value));
        float f = (v + 100f) / 100f;
        f *= f;
        float t = 128f * (1f - f);
        matrix(new ColorMatrix(new float[]{
                f,0,0,0,t,
                0,f,0,0,t,
                0,0,f,0,t,
                0,0,0,1,0
        }));
    }

    private void saturation(float value) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(Math.max(0f, Math.min(2f, 1f + value / 100f)));
        matrix(cm);
    }

    private void invert() {
        matrix(new ColorMatrix(new float[]{
                -1,0,0,0,255,
                0,-1,0,0,255,
                0,0,-1,0,255,
                0,0,0,1,0
        }));
    }

    private void matrix(ColorMatrix cm) {
        Bitmap out = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        bitmap = out;
    }

    private void threshold(double low) {
        int threshold = clamp((int) Math.round(low <= 1.0 ? low * 255.0 : low * 2.55), 0, 255);
        int[] px = pixels();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int y = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            int v = y >= threshold ? 255 : 0;
            px[i] = Color.argb(Color.alpha(c), v, v, v);
        }
        setPixels(px);
    }

    private void posterize(int levels) {
        int n = clamp(levels, 2, 256);
        int[] px = pixels();
        float step = 255f / (n - 1);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = Color.argb(Color.alpha(c),
                    quant(Color.red(c), step),
                    quant(Color.green(c), step),
                    quant(Color.blue(c), step));
        }
        setPixels(px);
    }

    private int quant(int value, float step) {
        return clamp(Math.round(Math.round(value / step) * step), 0, 255);
    }

    private void levels(JSONObject p) {
        float low = (float) p.optDouble("low_input", 0.0);
        float high = (float) p.optDouble("high_input", 1.0);
        float gamma = (float) p.optDouble("gamma", 1.0);
        low = Math.max(0f, Math.min(1f, low));
        high = Math.max(low + 0.001f, Math.min(1f, high));
        gamma = Math.max(0.1f, Math.min(10f, gamma));

        int[] px = pixels();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = Color.argb(Color.alpha(c),
                    level(Color.red(c), low, high, gamma),
                    level(Color.green(c), low, high, gamma),
                    level(Color.blue(c), low, high, gamma));
        }
        setPixels(px);
    }

    private int level(int value, float low, float high, float gamma) {
        float n = value / 255f;
        n = Math.max(0f, Math.min(1f, (n - low) / (high - low)));
        n = (float) Math.pow(n, 1.0 / gamma);
        return clamp(Math.round(n * 255f), 0, 255);
    }

    private void colorBalance(JSONObject p) {
        int dr = Math.round((float) p.optDouble("cyan_red", 0));
        int dg = Math.round((float) p.optDouble("magenta_green", 0));
        int db = Math.round((float) p.optDouble("yellow_blue", 0));
        int[] px = pixels();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = Color.argb(Color.alpha(c),
                    clamp(Color.red(c) + dr, 0, 255),
                    clamp(Color.green(c) + dg, 0, 255),
                    clamp(Color.blue(c) + db, 0, 255));
        }
        setPixels(px);
    }

    private void equalize() {
        int[] px = pixels();
        int[][] hist = new int[3][256];
        for (int c : px) {
            hist[0][Color.red(c)]++;
            hist[1][Color.green(c)]++;
            hist[2][Color.blue(c)]++;
        }

        int[][] map = new int[3][256];
        for (int ch = 0; ch < 3; ch++) {
            int sum = 0;
            for (int v = 0; v < 256; v++) {
                sum += hist[ch][v];
                map[ch][v] = Math.round(sum * 255f / Math.max(1, px.length));
            }
        }

        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = Color.argb(Color.alpha(c),
                    map[0][Color.red(c)], map[1][Color.green(c)], map[2][Color.blue(c)]);
        }
        setPixels(px);
    }

    private void fill(int color) {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(color);
        Rect target = selection == null
                ? new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
                : new Rect(selection);
        canvas.drawRect(target, paint);
    }

    private void stroke(JSONObject p, boolean pencil, boolean erase) {
        JSONArray pts = p.optJSONArray("points");
        if (pts == null || pts.length() < 2) throw new IllegalArgumentException("مسار الرسم فارغ");

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(1f, (float) p.optDouble("size", 20)));
        paint.setAlpha(clamp((int) Math.round(p.optDouble("opacity", 100) * 2.55), 0, 255));
        paint.setColor(parseColor(p.optString("color", "#ffffff")));

        if (pencil) {
            paint.setAntiAlias(false);
            paint.setStrokeCap(Paint.Cap.SQUARE);
        }

        if (erase && p.optBoolean("transparent", true)) {
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        Canvas canvas = new Canvas(bitmap);
        float x0 = (float) pts.optDouble(0);
        float y0 = (float) pts.optDouble(1);

        if (pts.length() == 2) {
            canvas.drawPoint(x0, y0, paint);
            return;
        }

        for (int i = 2; i + 1 < pts.length(); i += 2) {
            float x1 = (float) pts.optDouble(i);
            float y1 = (float) pts.optDouble(i + 1);
            canvas.drawLine(x0, y0, x1, y1, paint);
            x0 = x1;
            y0 = y1;
        }
    }

    private void gradient(JSONObject p) {
        float x1 = (float) p.optDouble("x1", 0);
        float y1 = (float) p.optDouble("y1", 0);
        float x2 = (float) p.optDouble("x2", bitmap.getWidth());
        float y2 = (float) p.optDouble("y2", bitmap.getHeight());
        int fg = parseColor(p.optString("foreground", "#ffffff"));
        int bg = parseColor(p.optString("background", "#000000"));

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if ("radial".equalsIgnoreCase(p.optString("type", "linear"))) {
            float radius = Math.max(1f,
                    (float) Math.hypot(x2 - x1, y2 - y1));
            paint.setShader(new RadialGradient(
                    x1, y1, radius, fg, bg, Shader.TileMode.CLAMP));
        } else {
            paint.setShader(new LinearGradient(
                    x1, y1, x2, y2, fg, bg, Shader.TileMode.CLAMP));
        }
        Canvas canvas = new Canvas(bitmap);
        Rect target = selection == null
                ? new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
                : new Rect(selection);
        canvas.drawRect(target, paint);
    }

    private void replaceTextRegion(JSONObject p) {
        int x = clamp(p.optInt("x", 0), 0, bitmap.getWidth() - 1);
        int y = clamp(p.optInt("y", 0), 0, bitmap.getHeight() - 1);
        int w = Math.max(1, Math.min(p.optInt("width", 1), bitmap.getWidth() - x));
        int h = Math.max(1, Math.min(p.optInt("height", 1), bitmap.getHeight() - y));

        String text = p.optString("text", "");
        int background = parseColor(p.optString("background", "#ffffff"));
        int foreground = parseColor(p.optString("color", "#000000"));

        Canvas canvas = new Canvas(bitmap);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(background);
        canvas.drawRect(x, y, x + w, y + h, bg);

        if (text.trim().isEmpty()) return;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(foreground);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        float requested = Math.max(6f, (float) p.optDouble("size", h * 0.72f));
        String[] lines = text.split("\\n", -1);
        float maxWidth = Math.max(1f, w - 4f);

        float fitted = requested;
        for (String line : lines) {
            paint.setTextSize(fitted);
            float measured = paint.measureText(line);
            if (measured > maxWidth && measured > 0f) {
                fitted = Math.max(6f, fitted * (maxWidth / measured));
            }
        }
        paint.setTextSize(fitted);

        boolean rtl = containsRtl(text);
        paint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        float tx = rtl ? x + w - 2f : x + 2f;

        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = Math.max(fitted * 1.15f, fm.descent - fm.ascent);
        float baseline = y + Math.max(-fm.ascent, (h - lineHeight * lines.length) / 2f - fm.ascent);

        for (String line : lines) {
            if (baseline > y + h) break;
            canvas.drawText(line, tx, baseline, paint);
            baseline += lineHeight;
        }
    }

    private boolean containsRtl(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            byte d = Character.getDirectionality(text.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
        }
        return false;
    }

    private void addText(JSONObject p) {
        String text = p.optString("text", "");
        if (text.isEmpty()) throw new IllegalArgumentException("النص فارغ");

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(parseColor(p.optString("color", "#ffffff")));
        paint.setTextSize(Math.max(4f, (float) p.optDouble("size", 42)));
        paint.setTextAlign(Paint.Align.LEFT);

        float x = (float) p.optDouble("x", 0);
        float y = (float) p.optDouble("y", paint.getTextSize());

        Canvas canvas = new Canvas(bitmap);
        String[] lines = text.split("\\n", -1);
        float line = paint.getTextSize() * 1.2f;
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], x, y + i * line, paint);
        }
    }

    private void selectColorBounds(int target, double threshold) {
        int[] px = pixels();
        int tr = Color.red(target), tg = Color.green(target), tb = Color.blue(target);
        double limit = Math.max(0.01, Math.min(1.0, threshold)) * 441.67295593;
        double limitSq = limit * limit;

        int minX = bitmap.getWidth(), minY = bitmap.getHeight();
        int maxX = -1, maxY = -1;
        int w = bitmap.getWidth();

        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int dr = Color.red(c) - tr;
            int dg = Color.green(c) - tg;
            int db = Color.blue(c) - tb;
            if (dr * dr + dg * dg + db * db > limitSq) continue;
            int x = i % w;
            int y = i / w;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        selection = maxX < 0 ? null :
                new Rect(minX, minY, Math.min(w, maxX + 1),
                        Math.min(bitmap.getHeight(), maxY + 1));
    }

    private void curves(float strength) {
        float amount = Math.max(-100f, Math.min(100f, strength)) / 100f;
        int[] px = pixels();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = Color.argb(Color.alpha(c),
                    curveValue(Color.red(c), amount),
                    curveValue(Color.green(c), amount),
                    curveValue(Color.blue(c), amount));
        }
        setPixels(px);
    }

    private int curveValue(int value, float amount) {
        float n = value / 255f;
        float smooth = n * n * (3f - 2f * n);
        float out = amount >= 0
                ? n + (smooth - n) * amount
                : n + (n - smooth) * (-amount);
        return clamp(Math.round(out * 255f), 0, 255);
    }

    private void cloneHealStroke(JSONObject p, boolean heal) {
        JSONArray pts = p.optJSONArray("points");
        if (pts == null || pts.length() < 2) return;

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int radius = Math.max(1, Math.round((float) p.optDouble("size", 24) / 2f));
        float opacity = Math.max(0f, Math.min(1f,
                (float) p.optDouble("opacity", 100) / 100f));

        float firstX = (float) pts.optDouble(0);
        float firstY = (float) pts.optDouble(1);
        float sourceX = (float) p.optDouble("source_x", firstX);
        float sourceY = (float) p.optDouble("source_y", firstY);
        int offsetX = Math.round(sourceX - firstX);
        int offsetY = Math.round(sourceY - firstY);

        int[] original = pixels();
        int[] out = original.clone();

        for (int i = 0; i + 1 < pts.length(); i += 2) {
            int cx = Math.round((float) pts.optDouble(i));
            int cy = Math.round((float) pts.optDouble(i + 1));

            for (int dy = -radius; dy <= radius; dy++) {
                int yy = cy + dy;
                int sy = yy + offsetY;
                if (yy < 0 || yy >= h || sy < 0 || sy >= h) continue;
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx * dx + dy * dy > radius * radius) continue;
                    int xx = cx + dx;
                    int sx = xx + offsetX;
                    if (xx < 0 || xx >= w || sx < 0 || sx >= w) continue;

                    int di = yy * w + xx;
                    int si = sy * w + sx;
                    int source = original[si];
                    int dest = out[di];
                    float local = opacity;
                    if (heal) local *= 0.65f;
                    out[di] = blend(dest, source, local);
                }
            }
        }
        setPixels(out);
    }

    private void smudgeStroke(JSONObject p) {
        JSONArray pts = p.optJSONArray("points");
        if (pts == null || pts.length() < 2) return;

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int radius = Math.max(1, Math.round((float) p.optDouble("size", 24) / 2f));
        float strength = Math.max(0.05f, Math.min(0.9f,
                (float) p.optDouble("pressure", 50) / 100f));

        int[] px = pixels();
        for (int i = 0; i + 1 < pts.length(); i += 2) {
            int cx = clamp(Math.round((float) pts.optDouble(i)), 0, w - 1);
            int cy = clamp(Math.round((float) pts.optDouble(i + 1)), 0, h - 1);

            long ar = 0, ag = 0, ab = 0, aa = 0, count = 0;
            int sampleRadius = Math.max(1, radius / 2);
            for (int dy = -sampleRadius; dy <= sampleRadius; dy++) {
                int yy = cy + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -sampleRadius; dx <= sampleRadius; dx++) {
                    int xx = cx + dx;
                    if (xx < 0 || xx >= w || dx * dx + dy * dy > sampleRadius * sampleRadius) continue;
                    int cc = px[yy * w + xx];
                    aa += Color.alpha(cc);
                    ar += Color.red(cc);
                    ag += Color.green(cc);
                    ab += Color.blue(cc);
                    count++;
                }
            }
            if (count == 0) continue;
            int avg = Color.argb((int) (aa / count), (int) (ar / count),
                    (int) (ag / count), (int) (ab / count));

            for (int dy = -radius; dy <= radius; dy++) {
                int yy = cy + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx * dx + dy * dy > radius * radius) continue;
                    int xx = cx + dx;
                    if (xx < 0 || xx >= w) continue;
                    int index = yy * w + xx;
                    px[index] = blend(px[index], avg, strength);
                }
            }
        }
        setPixels(px);
    }

    private void dodgeBurnStroke(JSONObject p) {
        JSONArray pts = p.optJSONArray("points");
        if (pts == null || pts.length() < 2) return;

        boolean burn = "burn".equalsIgnoreCase(p.optString("type", "dodge"));
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int radius = Math.max(1, Math.round((float) p.optDouble("size", 24) / 2f));
        float exposure = Math.max(0.01f, Math.min(1f,
                (float) p.optDouble("exposure", 35) / 100f));

        int[] px = pixels();
        for (int i = 0; i + 1 < pts.length(); i += 2) {
            int cx = Math.round((float) pts.optDouble(i));
            int cy = Math.round((float) pts.optDouble(i + 1));
            for (int dy = -radius; dy <= radius; dy++) {
                int yy = cy + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx * dx + dy * dy > radius * radius) continue;
                    int xx = cx + dx;
                    if (xx < 0 || xx >= w) continue;
                    int index = yy * w + xx;
                    int cc = px[index];
                    int r = Color.red(cc), g = Color.green(cc), b = Color.blue(cc);
                    if (burn) {
                        r = Math.round(r * (1f - exposure * 0.35f));
                        g = Math.round(g * (1f - exposure * 0.35f));
                        b = Math.round(b * (1f - exposure * 0.35f));
                    } else {
                        r = Math.round(r + (255 - r) * exposure * 0.35f);
                        g = Math.round(g + (255 - g) * exposure * 0.35f);
                        b = Math.round(b + (255 - b) * exposure * 0.35f);
                    }
                    px[index] = Color.argb(Color.alpha(cc),
                            clamp(r, 0, 255), clamp(g, 0, 255), clamp(b, 0, 255));
                }
            }
        }
        setPixels(px);
    }

    private void boxBlur(int radius) {
        radius = Math.max(1, Math.min(30, radius));
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] src = pixels();
        int[] temp = new int[src.length];
        int[] out = new int[src.length];

        for (int y = 0; y < h; y++) {
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int x = -radius; x <= radius; x++) {
                int xx = clamp(x, 0, w - 1);
                int c = src[y * w + xx];
                sa += Color.alpha(c); sr += Color.red(c);
                sg += Color.green(c); sb += Color.blue(c);
            }
            int window = radius * 2 + 1;
            for (int x = 0; x < w; x++) {
                temp[y * w + x] = Color.argb((int)(sa/window), (int)(sr/window),
                        (int)(sg/window), (int)(sb/window));
                int removeX = clamp(x - radius, 0, w - 1);
                int addX = clamp(x + radius + 1, 0, w - 1);
                int rem = src[y * w + removeX];
                int add = src[y * w + addX];
                sa += Color.alpha(add) - Color.alpha(rem);
                sr += Color.red(add) - Color.red(rem);
                sg += Color.green(add) - Color.green(rem);
                sb += Color.blue(add) - Color.blue(rem);
            }
        }

        for (int x = 0; x < w; x++) {
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int y = -radius; y <= radius; y++) {
                int yy = clamp(y, 0, h - 1);
                int c = temp[yy * w + x];
                sa += Color.alpha(c); sr += Color.red(c);
                sg += Color.green(c); sb += Color.blue(c);
            }
            int window = radius * 2 + 1;
            for (int y = 0; y < h; y++) {
                out[y * w + x] = Color.argb((int)(sa/window), (int)(sr/window),
                        (int)(sg/window), (int)(sb/window));
                int removeY = clamp(y - radius, 0, h - 1);
                int addY = clamp(y + radius + 1, 0, h - 1);
                int rem = temp[removeY * w + x];
                int add = temp[addY * w + x];
                sa += Color.alpha(add) - Color.alpha(rem);
                sr += Color.red(add) - Color.red(rem);
                sg += Color.green(add) - Color.green(rem);
                sb += Color.blue(add) - Color.blue(rem);
            }
        }
        setPixels(out);
    }

    private void unsharp(float amount, int radius) {
        int[] original = pixels();
        Bitmap saved = bitmap;
        boxBlur(radius);
        int[] blurred = pixels();
        bitmap = saved;
        int[] out = original.clone();
        float a = Math.max(0f, Math.min(3f, amount));

        for (int i = 0; i < out.length; i++) {
            int o = original[i], b = blurred[i];
            out[i] = Color.argb(Color.alpha(o),
                    clamp(Math.round(Color.red(o) + (Color.red(o)-Color.red(b))*a),0,255),
                    clamp(Math.round(Color.green(o) + (Color.green(o)-Color.green(b))*a),0,255),
                    clamp(Math.round(Color.blue(o) + (Color.blue(o)-Color.blue(b))*a),0,255));
        }
        setPixels(out);
    }

    private void bloom(int radius, float strength) {
        int[] original = pixels();
        Bitmap saved = bitmap;
        boxBlur(radius);
        int[] blurred = pixels();
        bitmap = saved;
        int[] out = original.clone();
        float s = Math.max(0f, Math.min(1f, strength));
        for (int i = 0; i < out.length; i++) {
            int o = original[i], b = blurred[i];
            int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
            if ((br + bg + bb) / 3 < 150) continue;
            int glow = Color.rgb(br, bg, bb);
            out[i] = blend(o, screen(o, glow), s);
        }
        setPixels(out);
    }

    private int screen(int a, int b) {
        return Color.argb(Color.alpha(a),
                255 - ((255-Color.red(a))*(255-Color.red(b))/255),
                255 - ((255-Color.green(a))*(255-Color.green(b))/255),
                255 - ((255-Color.blue(a))*(255-Color.blue(b))/255));
    }

    private void emboss() {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] src = pixels(), out = src.clone();
        for (int y = 1; y < h; y++) {
            for (int x = 1; x < w; x++) {
                int c = src[y*w+x], p = src[(y-1)*w+x-1];
                out[y*w+x] = Color.argb(Color.alpha(c),
                        clamp(128 + Color.red(c)-Color.red(p),0,255),
                        clamp(128 + Color.green(c)-Color.green(p),0,255),
                        clamp(128 + Color.blue(c)-Color.blue(p),0,255));
            }
        }
        setPixels(out);
    }

    private void edgeDetect() {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] src = pixels(), out = new int[src.length];
        for (int y = 1; y < h-1; y++) {
            for (int x = 1; x < w-1; x++) {
                int gx = gray(src[(y-1)*w+x+1]) + 2*gray(src[y*w+x+1]) + gray(src[(y+1)*w+x+1])
                        - gray(src[(y-1)*w+x-1]) - 2*gray(src[y*w+x-1]) - gray(src[(y+1)*w+x-1]);
                int gy = gray(src[(y+1)*w+x-1]) + 2*gray(src[(y+1)*w+x]) + gray(src[(y+1)*w+x+1])
                        - gray(src[(y-1)*w+x-1]) - 2*gray(src[(y-1)*w+x]) - gray(src[(y-1)*w+x+1]);
                int v = clamp((int)Math.sqrt(gx*gx + gy*gy),0,255);
                out[y*w+x] = Color.argb(Color.alpha(src[y*w+x]), v,v,v);
            }
        }
        setPixels(out);
    }

    private int gray(int c) {
        return (Color.red(c)*299 + Color.green(c)*587 + Color.blue(c)*114)/1000;
    }

    private void oilify() {
        posterize(12);
        boxBlur(1);
    }

    private void pixelize(int size) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] px = pixels();
        for (int by = 0; by < h; by += size) {
            for (int bx = 0; bx < w; bx += size) {
                long aa=0, rr=0, gg=0, bb=0, count=0;
                int yEnd = Math.min(h, by+size), xEnd = Math.min(w, bx+size);
                for(int y=by;y<yEnd;y++) for(int x=bx;x<xEnd;x++){
                    int c=px[y*w+x]; aa+=Color.alpha(c); rr+=Color.red(c);
                    gg+=Color.green(c); bb+=Color.blue(c); count++;
                }
                int avg=Color.argb((int)(aa/count),(int)(rr/count),(int)(gg/count),(int)(bb/count));
                for(int y=by;y<yEnd;y++) for(int x=bx;x<xEnd;x++) px[y*w+x]=avg;
            }
        }
        setPixels(px);
    }

    private void motionBlur(int radius) {
        int w=bitmap.getWidth(), h=bitmap.getHeight();
        int[] src=pixels(), out=src.clone();
        for(int y=0;y<h;y++){
            long aa=0,rr=0,gg=0,bb=0;
            int window=radius*2+1;
            for(int x=-radius;x<=radius;x++){
                int c=src[y*w+clamp(x,0,w-1)];
                aa+=Color.alpha(c); rr+=Color.red(c); gg+=Color.green(c); bb+=Color.blue(c);
            }
            for(int x=0;x<w;x++){
                out[y*w+x]=Color.argb((int)(aa/window),(int)(rr/window),(int)(gg/window),(int)(bb/window));
                int rem=src[y*w+clamp(x-radius,0,w-1)];
                int add=src[y*w+clamp(x+radius+1,0,w-1)];
                aa+=Color.alpha(add)-Color.alpha(rem); rr+=Color.red(add)-Color.red(rem);
                gg+=Color.green(add)-Color.green(rem); bb+=Color.blue(add)-Color.blue(rem);
            }
        }
        setPixels(out);
    }

    private void colorTemperature(int value) {
        int shift=clamp(value,-100,100);
        int[] px=pixels();
        for(int i=0;i<px.length;i++){
            int c=px[i];
            px[i]=Color.argb(Color.alpha(c),
                    clamp(Color.red(c)+shift,0,255),
                    Color.green(c),
                    clamp(Color.blue(c)-shift,0,255));
        }
        setPixels(px);
    }

    private int blend(int base, int over, float amount) {
        float a=Math.max(0f,Math.min(1f,amount));
        return Color.argb(
                clamp(Math.round(Color.alpha(base)*(1f-a)+Color.alpha(over)*a),0,255),
                clamp(Math.round(Color.red(base)*(1f-a)+Color.red(over)*a),0,255),
                clamp(Math.round(Color.green(base)*(1f-a)+Color.green(over)*a),0,255),
                clamp(Math.round(Color.blue(base)*(1f-a)+Color.blue(over)*a),0,255));
    }

    private int[] pixels() {
        int[] px = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(px, 0, bitmap.getWidth(), 0, 0,
                bitmap.getWidth(), bitmap.getHeight());
        return px;
    }

    private void setPixels(int[] px) {
        bitmap.setPixels(px, 0, bitmap.getWidth(), 0, 0,
                bitmap.getWidth(), bitmap.getHeight());
    }

    private void trim(Deque<Bitmap> stack) {
        while (stack.size() > MAX_HISTORY) stack.removeFirst();
    }

    private void requireImage() {
        if (bitmap == null) throw new IllegalStateException("افتح صورة أولاً");
    }

    private static Bitmap mutable(Bitmap source) {
        if (source.isMutable() && source.getConfig() == Bitmap.Config.ARGB_8888) {
            return source.copy(Bitmap.Config.ARGB_8888, true);
        }
        return source.copy(Bitmap.Config.ARGB_8888, true);
    }

    private static Bitmap copy(Bitmap source) {
        return source.copy(Bitmap.Config.ARGB_8888, true);
    }

    private static int parseColor(String value) {
        try {
            return Color.parseColor(value);
        } catch (Exception ignored) {
            return Color.WHITE;
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
