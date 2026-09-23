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
        public final boolean hasMask;
        public final String blendMode;
        public final boolean locked;

        LayerInfo(int index, String name, boolean visible, int opacity, boolean active,
                  boolean hasMask, String blendMode, boolean locked) {
            this.index = index;
            this.name = name;
            this.visible = visible;
            this.opacity = opacity;
            this.active = active;
            this.hasMask = hasMask;
            this.blendMode = blendMode;
            this.locked = locked;
        }
    }

    private static final class Layer {
        String name;
        Bitmap bitmap;
        Bitmap mask;
        boolean visible = true;
        boolean locked = false;
        int opacity = 255;
        String blendMode = "normal";

        Layer(String name, Bitmap bitmap) {
            this.name = name;
            this.bitmap = bitmap;
        }
    }

    private Bitmap bitmap;
    private Rect selection;
    private byte[] selectionMask;
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
        selectionMask = null;
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
                    i == activeLayerIndex, layer.mask != null, layer.blendMode, layer.locked));
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

    public synchronized Bitmap selectionPreview() {
        if (selectionMask == null || bitmap == null) return null;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (selectionMask.length != w * h) return null;

        Bitmap overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] px = new int[selectionMask.length];
        for (int i = 0; i < selectionMask.length; i++) {
            int a = selectionMask[i] & 0xFF;
            if (a == 0) {
                px[i] = Color.TRANSPARENT;
            } else {
                int alpha = Math.max(24, Math.round(a * 0.28f));
                px[i] = Color.argb(alpha, 65, 145, 255);
            }
        }
        overlay.setPixels(px, 0, w, 0, 0, w, h);
        return overlay;
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
        selectionMask = null;
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
        selectionMask = null;
        return compositeLayers();
    }

    public synchronized int beginAdjustment(String operation, int requestedValue) {
        requireImage();
        if (activeLayer().locked) {
            throw new IllegalStateException("الطبقة مقفلة — افتح القفل أولاً");
        }
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
        if (activeLayer().locked) {
            throw new IllegalStateException("الطبقة مقفلة — افتح القفل أولاً");
        }
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
            case "toggle_layer_lock":
                return toggleLayerLock(p.optInt("index", activeLayerIndex));
            case "set_layer_opacity":
                return setLayerOpacity(p.optInt("index", activeLayerIndex),
                        p.optInt("opacity", 255));
            case "set_layer_blend_mode":
                return setLayerBlendMode(p.optInt("index", activeLayerIndex),
                        p.optString("mode", "normal"));
            case "rename_layer":
                return renameLayer(p.optInt("index", activeLayerIndex),
                        p.optString("name", "Layer"));
            case "move_layer_up":
                return moveLayer(activeLayerIndex, 1);
            case "move_layer_down":
                return moveLayer(activeLayerIndex, -1);
            case "move_layer_top":
                return moveLayerToEdge(activeLayerIndex, true);
            case "move_layer_bottom":
                return moveLayerToEdge(activeLayerIndex, false);
            case "show_all_layers":
                return showAllLayers();
            case "hide_other_layers":
                return hideOtherLayers(activeLayerIndex);
            case "add_layer_mask":
                return addLayerMask();
            case "invert_layer_mask":
                return invertLayerMask();
            case "apply_layer_mask":
                return applyLayerMask();
            case "remove_layer_mask":
                return removeLayerMask();
            case "merge_down":
                return mergeDown();
            case "merge_visible":
                return mergeVisibleLayers();
            case "flatten":
                return flattenLayers();
        }

        if (selectionOperation(op, p)) return compositeLayers();

        if (activeLayer().locked) {
            throw new IllegalStateException("الطبقة مقفلة — افتح القفل أولاً");
        }

        // Validate user-controlled free-transform geometry before flattening.
        // Otherwise an invalid quad could destroy the original layer model
        // before quadTransform() gets a chance to reject it.
        if ("quad_transform".equals(op)) {
            validateQuadTransform(p);
        }

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
                case "quad_transform":
                    quadTransform(p);
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
                case "pattern_fill":
                    patternFill(p);
                    break;
                case "channel_extract":
                    channelExtract(p.optString("channel", "R"));
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

            if (selectionMask != null && !isGeometryOperation(op)) {
                applySelectionMask(before);
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
                "perspective".equals(op) || "quad_transform".equals(op);
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
        copyLayer.blendMode = source.blendMode;
        copyLayer.locked = false;
        copyLayer.mask = source.mask == null ? null : copy(source.mask);
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

    private Bitmap toggleLayerLock(int index) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        layers.get(index).locked = !layers.get(index).locked;
        return compositeLayers();
    }

    private Bitmap setLayerOpacity(int index, int opacity) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        layers.get(index).opacity = clamp(opacity, 0, 255);
        return compositeLayers();
    }

    private Bitmap renameLayer(int index, String name) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) clean = "Layer " + (index + 1);
        layers.get(index).name = clean;
        return compositeLayers();
    }

    private Bitmap moveLayer(int index, int direction) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        int target = index + direction;
        if (target < 0 || target >= layers.size()) return compositeLayers();

        Layer layer = layers.remove(index);
        layers.add(target, layer);
        activeLayerIndex = target;
        bitmap = layer.bitmap;
        return compositeLayers();
    }

    private Bitmap moveLayerToEdge(int index, boolean top) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        if (layers.size() <= 1) return compositeLayers();

        Layer layer = layers.remove(index);
        int target = top ? layers.size() : 0;
        layers.add(target, layer);
        activeLayerIndex = target;
        bitmap = layer.bitmap;
        return compositeLayers();
    }

    private Bitmap showAllLayers() {
        for (Layer layer : layers) layer.visible = true;
        return compositeLayers();
    }

    private Bitmap hideOtherLayers(int index) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        for (int i = 0; i < layers.size(); i++) {
            layers.get(i).visible = i == index;
        }
        return compositeLayers();
    }

    private Bitmap setLayerBlendMode(int index, String mode) {
        if (index < 0 || index >= layers.size()) {
            throw new IllegalArgumentException("طبقة غير موجودة");
        }
        String normalized = normalizeBlendMode(mode);
        layers.get(index).blendMode = normalized;
        return compositeLayers();
    }

    private String normalizeBlendMode(String mode) {
        String m = mode == null ? "normal" : mode.trim().toLowerCase();
        switch (m) {
            case "multiply":
            case "screen":
            case "add":
            case "darken":
            case "lighten":
                return m;
            default:
                return "normal";
        }
    }

    private Bitmap addLayerMask() {
        Layer layer = activeLayer();
        Bitmap mask = Bitmap.createBitmap(width(), height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mask);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);

        if (selectionMask == null) {
            canvas.drawRect(0, 0, width(), height(), paint);
        } else {
            int[] maskPixels = new int[selectionMask.length];
            for (int i = 0; i < selectionMask.length; i++) {
                int v = selectionMask[i] & 0xFF;
                maskPixels[i] = Color.argb(255, v, v, v);
            }
            mask.setPixels(maskPixels, 0, width(), 0, 0, width(), height());
        }

        layer.mask = mask;
        return compositeLayers();
    }

    private Bitmap invertLayerMask() {
        Layer layer = activeLayer();
        if (layer.mask == null) throw new IllegalStateException("لا يوجد قناع للطبقة");

        int w = layer.mask.getWidth();
        int h = layer.mask.getHeight();
        int[] px = new int[w * h];
        layer.mask.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int v = 255 - Color.red(px[i]);
            px[i] = Color.argb(255, v, v, v);
        }
        layer.mask.setPixels(px, 0, w, 0, 0, w, h);
        return compositeLayers();
    }

    private Bitmap applyLayerMask() {
        Layer layer = activeLayer();
        if (layer.mask == null) throw new IllegalStateException("لا يوجد قناع للطبقة");

        Bitmap masked = maskedLayerBitmap(layer);
        layer.bitmap = masked;
        layer.mask = null;
        bitmap = layer.bitmap;
        return compositeLayers();
    }

    private Bitmap removeLayerMask() {
        Layer layer = activeLayer();
        if (layer.mask == null) throw new IllegalStateException("لا يوجد قناع للطبقة");
        layer.mask = null;
        return compositeLayers();
    }

    private Bitmap maskedLayerBitmap(Layer layer) {
        Bitmap out = Bitmap.createBitmap(width(), height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(layer.bitmap, 0, 0, paint);

        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        Bitmap alphaMask = Bitmap.createBitmap(width(), height(), Bitmap.Config.ARGB_8888);
        int[] src = new int[width() * height()];
        int[] dst = new int[src.length];
        layer.mask.getPixels(src, 0, width(), 0, 0, width(), height());
        for (int i = 0; i < src.length; i++) {
            int a = Color.red(src[i]);
            dst[i] = Color.argb(a, 255, 255, 255);
        }
        alphaMask.setPixels(dst, 0, width(), 0, 0, width(), height());
        canvas.drawBitmap(alphaMask, 0, 0, maskPaint);
        maskPaint.setXfermode(null);
        return out;
    }

    private PorterDuff.Mode porterDuffForBlend(String mode) {
        switch (normalizeBlendMode(mode)) {
            case "multiply": return PorterDuff.Mode.MULTIPLY;
            case "screen": return PorterDuff.Mode.SCREEN;
            case "add": return PorterDuff.Mode.ADD;
            case "darken": return PorterDuff.Mode.DARKEN;
            case "lighten": return PorterDuff.Mode.LIGHTEN;
            default: return null;
        }
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
        if (lower.visible) {
            Bitmap lowerBitmap = lower.mask == null ? lower.bitmap : maskedLayerBitmap(lower);
            canvas.drawBitmap(lowerBitmap, 0, 0, lowerPaint);
        }

        Paint topPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        topPaint.setAlpha(top.opacity);
        PorterDuff.Mode topMode = porterDuffForBlend(top.blendMode);
        if (topMode != null) topPaint.setXfermode(new PorterDuffXfermode(topMode));
        if (top.visible) {
            Bitmap topBitmap = top.mask == null ? top.bitmap : maskedLayerBitmap(top);
            canvas.drawBitmap(topBitmap, 0, 0, topPaint);
        }
        topPaint.setXfermode(null);

        lower.bitmap = merged;
        lower.mask = null;
        lower.blendMode = "normal";
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

    private Bitmap mergeVisibleLayers() {
        int visibleCount = 0;
        int topVisibleIndex = -1;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).visible) {
                visibleCount++;
                topVisibleIndex = i;
            }
        }
        if (visibleCount <= 1) return compositeLayers();

        int w = width();
        int h = height();
        Bitmap mergedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mergedBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean hasBackdrop = false;

        for (Layer layer : layers) {
            if (!layer.visible) continue;
            paint.setAlpha(layer.opacity);
            PorterDuff.Mode mode = hasBackdrop ? porterDuffForBlend(layer.blendMode) : null;
            paint.setXfermode(mode == null ? null : new PorterDuffXfermode(mode));
            Bitmap draw = layer.mask == null ? layer.bitmap : maskedLayerBitmap(layer);
            canvas.drawBitmap(draw, 0, 0, paint);
            hasBackdrop = true;
        }
        paint.setAlpha(255);
        paint.setXfermode(null);

        Layer mergedLayer = new Layer("Merged Visible", mergedBitmap);
        java.util.ArrayList<Layer> rebuilt = new java.util.ArrayList<>();
        int mergedIndex = -1;
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            if (!layer.visible) {
                rebuilt.add(layer);
            } else if (i == topVisibleIndex) {
                mergedIndex = rebuilt.size();
                rebuilt.add(mergedLayer);
            }
        }

        layers.clear();
        layers.addAll(rebuilt);
        activeLayerIndex = Math.max(0, mergedIndex);
        bitmap = layers.get(activeLayerIndex).bitmap;
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

        boolean hasBackdrop = false;
        for (Layer layer : layers) {
            if (!layer.visible) continue;
            paint.setAlpha(layer.opacity);

            // A blend mode requires an existing backdrop. The first visible
            // layer is always composited normally, matching desktop editors.
            PorterDuff.Mode mode = hasBackdrop ? porterDuffForBlend(layer.blendMode) : null;
            if (mode != null) paint.setXfermode(new PorterDuffXfermode(mode));
            else paint.setXfermode(null);

            Bitmap draw = layer.mask == null ? layer.bitmap : maskedLayerBitmap(layer);
            canvas.drawBitmap(draw, 0, 0, paint);
            hasBackdrop = true;
        }
        paint.setAlpha(255);
        paint.setXfermode(null);
        return out;
    }

    private boolean selectionOperation(String op, JSONObject p) {
        switch (op) {
            case "select_all":
                selectionMask = new byte[bitmap.getWidth() * bitmap.getHeight()];
                java.util.Arrays.fill(selectionMask, (byte) 0xFF);
                selection = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                return true;

            case "select_none":
                selectionMask = null;
                selection = null;
                return true;

            case "select_rectangle": {
                int x = clamp(p.optInt("x", 0), 0, bitmap.getWidth() - 1);
                int y = clamp(p.optInt("y", 0), 0, bitmap.getHeight() - 1);
                int w = Math.max(1, p.optInt("width", 1));
                int h = Math.max(1, p.optInt("height", 1));
                Rect r = new Rect(
                        x, y,
                        Math.min(bitmap.getWidth(), x + w),
                        Math.min(bitmap.getHeight(), y + h));
                selectionMask = new byte[bitmap.getWidth() * bitmap.getHeight()];
                fillMaskRect(selectionMask, r, 255);
                selection = r;
                return true;
            }

            case "select_polygon": {
                JSONArray pts = p.optJSONArray("points");
                if (pts == null || pts.length() < 6) return true;
                selectionMask = polygonMask(pts);
                updateSelectionBoundsFromMask();
                return true;
            }

            case "select_color": {
                int target = parseColor(p.optString("color", "#ffffff"));
                selectionMask = colorMask(target, p.optDouble("threshold", 0.15), false, 0, 0);
                updateSelectionBoundsFromMask();
                return true;
            }

            case "select_contiguous": {
                int x = clamp((int) Math.round(p.optDouble("x", 0)), 0, bitmap.getWidth() - 1);
                int y = clamp((int) Math.round(p.optDouble("y", 0)), 0, bitmap.getHeight() - 1);
                int target = bitmap.getPixel(x, y);
                selectionMask = colorMask(target, p.optDouble("threshold", 0.15), true, x, y);
                updateSelectionBoundsFromMask();
                return true;
            }

            case "invert_selection":
                if (selectionMask == null) {
                    selectionMask = new byte[bitmap.getWidth() * bitmap.getHeight()];
                    java.util.Arrays.fill(selectionMask, (byte) 0xFF);
                } else {
                    for (int i = 0; i < selectionMask.length; i++) {
                        selectionMask[i] = (byte) (255 - (selectionMask[i] & 0xFF));
                    }
                }
                updateSelectionBoundsFromMask();
                return true;

            case "grow":
                if (selectionMask != null) {
                    selectionMask = morphMask(selectionMask, Math.max(1, p.optInt("steps", 1)), true);
                    updateSelectionBoundsFromMask();
                }
                return true;

            case "shrink":
                if (selectionMask != null) {
                    selectionMask = morphMask(selectionMask, Math.max(1, p.optInt("steps", 1)), false);
                    updateSelectionBoundsFromMask();
                }
                return true;

            case "feather":
                if (selectionMask != null) {
                    selectionMask = blurMask(selectionMask, Math.max(1, p.optInt("radius", 5)));
                    updateSelectionBoundsFromMask();
                }
                return true;

            case "border":
                if (selectionMask != null) {
                    int radius = Math.max(1, p.optInt("radius", 2));
                    byte[] grown = morphMask(selectionMask, radius, true);
                    byte[] shrunk = morphMask(selectionMask, radius, false);
                    byte[] border = new byte[selectionMask.length];
                    for (int i = 0; i < border.length; i++) {
                        int g = grown[i] & 0xFF;
                        int sh = shrunk[i] & 0xFF;
                        border[i] = (byte) Math.max(0, g - sh);
                    }
                    selectionMask = border;
                    updateSelectionBoundsFromMask();
                }
                return true;

            case "sharpen_selection":
                if (selectionMask != null) {
                    for (int i = 0; i < selectionMask.length; i++) {
                        selectionMask[i] = (byte) ((selectionMask[i] & 0xFF) >= 128 ? 255 : 0);
                    }
                    updateSelectionBoundsFromMask();
                }
                return true;

            default:
                return false;
        }
    }

    private void fillMaskRect(byte[] mask, Rect rect, int value) {
        int w = bitmap.getWidth();
        byte v = (byte) value;
        for (int y = Math.max(0, rect.top); y < Math.min(bitmap.getHeight(), rect.bottom); y++) {
            int row = y * w;
            for (int x = Math.max(0, rect.left); x < Math.min(w, rect.right); x++) {
                mask[row + x] = v;
            }
        }
    }

    private byte[] polygonMask(JSONArray pts) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int n = pts.length() / 2;
        float[] xs = new float[n];
        float[] ys = new float[n];
        float minY = h, maxY = 0;

        for (int i = 0; i < n; i++) {
            xs[i] = (float) pts.optDouble(i * 2);
            ys[i] = (float) pts.optDouble(i * 2 + 1);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }

        byte[] mask = new byte[w * h];
        int y0 = clamp((int) Math.floor(minY), 0, h - 1);
        int y1 = clamp((int) Math.ceil(maxY), 0, h - 1);

        for (int y = y0; y <= y1; y++) {
            for (int x = 0; x < w; x++) {
                boolean inside = false;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    boolean intersect = ((ys[i] > y) != (ys[j] > y)) &&
                            (x < (xs[j] - xs[i]) * (y - ys[i]) /
                                    Math.max(0.0001f, ys[j] - ys[i]) + xs[i]);
                    if (intersect) inside = !inside;
                }
                if (inside) mask[y * w + x] = (byte) 0xFF;
            }
        }
        return mask;
    }

    private byte[] colorMask(int target, double threshold, boolean contiguous, int startX, int startY) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] px = pixels();
        int tr = Color.red(target), tg = Color.green(target), tb = Color.blue(target);
        double limit = Math.max(0.01, Math.min(1.0, threshold)) * 441.67295593;
        double limitSq = limit * limit;
        byte[] mask = new byte[w * h];

        if (!contiguous) {
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int dr = Color.red(c) - tr;
                int dg = Color.green(c) - tg;
                int db = Color.blue(c) - tb;
                if (dr * dr + dg * dg + db * db <= limitSq) mask[i] = (byte) 0xFF;
            }
            return mask;
        }

        boolean[] seen = new boolean[px.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int start = startY * w + startX;
        queue.add(start);
        seen[start] = true;

        while (!queue.isEmpty()) {
            int idx = queue.removeFirst();
            int c = px[idx];
            int dr = Color.red(c) - tr;
            int dg = Color.green(c) - tg;
            int db = Color.blue(c) - tb;
            if (dr * dr + dg * dg + db * db > limitSq) continue;

            mask[idx] = (byte) 0xFF;
            int x = idx % w;
            int y = idx / w;

            if (x > 0) enqueue(idx - 1, seen, queue);
            if (x + 1 < w) enqueue(idx + 1, seen, queue);
            if (y > 0) enqueue(idx - w, seen, queue);
            if (y + 1 < h) enqueue(idx + w, seen, queue);
        }
        return mask;
    }

    private void enqueue(int index, boolean[] seen, ArrayDeque<Integer> queue) {
        if (index < 0 || index >= seen.length || seen[index]) return;
        seen[index] = true;
        queue.addLast(index);
    }

    private byte[] morphMask(byte[] source, int radius, boolean grow) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        byte[] current = source.clone();

        for (int step = 0; step < radius; step++) {
            byte[] next = current.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    int center = current[idx] & 0xFF;

                    if (grow && center >= 128) continue;
                    if (!grow && center < 128) continue;

                    boolean hit = false;
                    for (int dy = -1; dy <= 1 && !hit; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            int v = current[yy * w + xx] & 0xFF;
                            if (grow ? v >= 128 : v < 128) {
                                hit = true;
                                break;
                            }
                        }
                    }

                    if (hit) next[idx] = (byte) (grow ? 255 : 0);
                }
            }
            current = next;
        }
        return current;
    }

    private byte[] blurMask(byte[] source, int radius) {
        radius = Math.max(1, Math.min(30, radius));
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] temp = new int[source.length];
        byte[] out = new byte[source.length];

        for (int y = 0; y < h; y++) {
            int sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += source[y * w + clamp(x, 0, w - 1)] & 0xFF;
            }
            int window = radius * 2 + 1;
            for (int x = 0; x < w; x++) {
                temp[y * w + x] = sum / window;
                int removeX = clamp(x - radius, 0, w - 1);
                int addX = clamp(x + radius + 1, 0, w - 1);
                sum += (source[y * w + addX] & 0xFF) -
                        (source[y * w + removeX] & 0xFF);
            }
        }

        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += temp[clamp(y, 0, h - 1) * w + x];
            }
            int window = radius * 2 + 1;
            for (int y = 0; y < h; y++) {
                out[y * w + x] = (byte) clamp(sum / window, 0, 255);
                int removeY = clamp(y - radius, 0, h - 1);
                int addY = clamp(y + radius + 1, 0, h - 1);
                sum += temp[addY * w + x] - temp[removeY * w + x];
            }
        }
        return out;
    }

    private void updateSelectionBoundsFromMask() {
        if (selectionMask == null) {
            selection = null;
            return;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int i = 0; i < selectionMask.length; i++) {
            if ((selectionMask[i] & 0xFF) == 0) continue;
            int x = i % w;
            int y = i / w;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        selection = maxX < 0 ? null :
                new Rect(minX, minY, Math.min(w, maxX + 1), Math.min(h, maxY + 1));
    }

    private void applySelectionMask(Bitmap before) {
        if (selectionMask == null || before == null) return;
        if (before.getWidth() != bitmap.getWidth() || before.getHeight() != bitmap.getHeight()) {
            selectionMask = null;
            selection = null;
            return;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] oldPx = new int[w * h];
        int[] newPx = new int[w * h];
        before.getPixels(oldPx, 0, w, 0, 0, w, h);
        bitmap.getPixels(newPx, 0, w, 0, 0, w, h);

        for (int i = 0; i < newPx.length; i++) {
            int a = selectionMask[i] & 0xFF;
            if (a <= 0) newPx[i] = oldPx[i];
            else if (a < 255) newPx[i] = blend(oldPx[i], newPx[i], a / 255f);
        }

        bitmap.setPixels(newPx, 0, w, 0, 0, w, h);
    }

    private void rotate(int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), m, true)
                .copy(Bitmap.Config.ARGB_8888, true);
        selection = null;
        selectionMask = null;
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
        selectionMask = null;
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
        selectionMask = null;
    }

    private void resize(JSONObject p) {
        int w = p.optInt("width", 0);
        int h = p.optInt("height", 0);
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("أبعاد غير صحيحة");
        bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
                .copy(Bitmap.Config.ARGB_8888, true);
        selection = null;
        selectionMask = null;
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
        selectionMask = null;
    }

    private void validateQuadTransform(JSONObject p) {
        JSONArray quad = p.optJSONArray("quad");
        if (quad == null || quad.length() < 8) {
            throw new IllegalArgumentException("مقابض التحويل غير مكتملة");
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float[] src = new float[]{
                0f, 0f,
                w, 0f,
                w, h,
                0f, h
        };
        float[] dst = new float[8];
        for (int i = 0; i < 8; i++) {
            dst[i] = (float) quad.optDouble(i, src[i]);
            if (!Float.isFinite(dst[i])) {
                throw new IllegalArgumentException("إحداثيات التحويل غير صالحة");
            }
        }

        Matrix probe = new Matrix();
        if (!probe.setPolyToPoly(src, 0, dst, 0, 4)) {
            throw new IllegalStateException("تعذر حساب التحويل الحر");
        }
    }

    private void quadTransform(JSONObject p) {
        JSONArray quad = p.optJSONArray("quad");
        if (quad == null || quad.length() < 8) {
            throw new IllegalArgumentException("مقابض التحويل غير مكتملة");
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float[] src = new float[]{
                0f, 0f,
                w, 0f,
                w, h,
                0f, h
        };
        float[] dst = new float[8];
        for (int i = 0; i < 8; i++) {
            dst[i] = (float) quad.optDouble(i, src[i]);
        }

        Matrix matrix = new Matrix();
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            throw new IllegalStateException("تعذر حساب التحويل الحر");
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bitmap, matrix, paint);
        bitmap = out;
        selection = null;
        selectionMask = null;
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

    private void patternFill(JSONObject p) {
        String style = p.optString("style", "checker").toLowerCase();
        int fg = parseColor(p.optString("foreground", "#ffffff"));
        int bg = parseColor(p.optString("background", "#000000"));
        int size = Math.max(4, p.optInt("size", 24));

        int left = selection == null ? 0 : selection.left;
        int top = selection == null ? 0 : selection.top;
        int right = selection == null ? bitmap.getWidth() : selection.right;
        int bottom = selection == null ? bitmap.getHeight() : selection.bottom;

        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setColor(bg);
        canvas.drawRect(left, top, right, bottom, paint);

        paint.setColor(fg);
        if ("stripes".equals(style)) {
            int stripe = Math.max(2, size / 2);
            for (int x = left; x < right; x += size) {
                canvas.drawRect(x, top, Math.min(right, x + stripe), bottom, paint);
            }
        } else if ("diagonal".equals(style)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * 0.16f));
            int span = (right - left) + (bottom - top);
            for (int offset = -span; offset < span; offset += size) {
                canvas.drawLine(left + offset, bottom, left + offset + (bottom - top), top, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else if ("cross".equals(style)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * 0.14f));
            for (int x = left; x <= right; x += size) {
                canvas.drawLine(x, top, x, bottom, paint);
            }
            for (int y = top; y <= bottom; y += size) {
                canvas.drawLine(left, y, right, y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else if ("diamond".equals(style)) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * 0.12f));
            float half = size / 2f;
            for (float cy = top; cy <= bottom + size; cy += size) {
                for (float cx = left; cx <= right + size; cx += size) {
                    android.graphics.Path d = new android.graphics.Path();
                    d.moveTo(cx, cy - half);
                    d.lineTo(cx + half, cy);
                    d.lineTo(cx, cy + half);
                    d.lineTo(cx - half, cy);
                    d.close();
                    canvas.drawPath(d, paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
        } else if ("dots".equals(style)) {
            float radius = Math.max(1f, size * 0.22f);
            for (int y = top + size / 2; y < bottom; y += size) {
                for (int x = left + size / 2; x < right; x += size) {
                    canvas.drawCircle(x, y, radius, paint);
                }
            }
        } else {
            for (int y = top; y < bottom; y += size) {
                for (int x = left; x < right; x += size) {
                    if ((((x - left) / size) + ((y - top) / size)) % 2 == 0) {
                        canvas.drawRect(x, y,
                                Math.min(right, x + size),
                                Math.min(bottom, y + size), paint);
                    }
                }
            }
        }
    }

    private void channelExtract(String channel) {
        String ch = channel == null ? "R" : channel.toUpperCase();
        int[] px = pixels();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int v;
            if ("G".equals(ch)) v = Color.green(c);
            else if ("B".equals(ch)) v = Color.blue(c);
            else if ("A".equals(ch) || "ALPHA".equals(ch)) v = Color.alpha(c);
            else v = Color.red(c);
            px[i] = Color.argb(255, v, v, v);
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
        int foreground = parseColor(p.optString("color", "#000000"));

        // Preserve the original artwork/background. The old implementation
        // painted one flat rectangle over the whole OCR box, which destroyed
        // gradients, logos and patterns behind the text. Instead, detect
        // character-like high-contrast pixels and inpaint only those pixels.
        eraseRasterTextPreserveBackground(x, y, w, h);

        if (text.trim().isEmpty()) return;

        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(foreground);
        String fontFamily = p.optString("font", "sans-serif");
        boolean bold = p.optBoolean("bold", false);
        paint.setTypeface(Typeface.create(fontFamily,
                bold ? Typeface.BOLD : Typeface.NORMAL));

        float requested = Math.max(6f, (float) p.optDouble("size", h * 0.72f));
        String[] lines = text.split("\\n", -1);
        float maxWidth = Math.max(1f, w - 4f);
        float targetWidth = Math.max(1f, maxWidth * 0.92f);

        // Preserve the original text-region height first. The previous logic
        // reduced the font size whenever the replacement string was wider,
        // which made Arabic replacements such as "اليمن نت" visibly tiny.
        paint.setTextSize(requested);
        Paint.FontMetrics initialFm = paint.getFontMetrics();
        float glyphHeight = Math.max(1f, initialFm.descent - initialFm.ascent);
        float targetLineHeight = Math.max(6f,
                (h * 0.78f) / Math.max(1, lines.length));
        float heightScale = targetLineHeight / glyphHeight;
        float fitted = Math.max(6f, requested * heightScale);
        paint.setTextSize(fitted);

        float baseWidthScale = Math.max(0.5f, Math.min(2.0f,
                (float) p.optDouble("width_scale", 1.0)));
        paint.setTextScaleX(baseWidthScale);

        float widest = 1f;
        for (String line : lines) {
            widest = Math.max(widest, paint.measureText(line));
        }

        // Match the width of the old selected region mostly by horizontal
        // scaling, not by shrinking font height. Only fall back to reducing
        // font size if the required horizontal compression would be extreme.
        float widthFactor = targetWidth / widest;
        float adjustedScaleX = baseWidthScale * widthFactor;
        if (adjustedScaleX < 0.62f) {
            float shrink = adjustedScaleX / 0.62f;
            fitted = Math.max(6f, fitted * shrink);
            paint.setTextSize(fitted);
            adjustedScaleX = 0.62f;
        }
        adjustedScaleX = Math.max(0.62f, Math.min(1.45f, adjustedScaleX));
        paint.setTextScaleX(adjustedScaleX);

        // Replacement text is centered in the original text box so Arabic
        // words remain in the same visual slot even when character counts differ.
        paint.setTextAlign(Paint.Align.CENTER);
        float tx = x + w / 2f;

        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = Math.max(fitted * 1.08f, fm.descent - fm.ascent);
        float totalHeight = lineHeight * lines.length;
        float baseline = y + (h - totalHeight) / 2f - fm.ascent;

        for (String line : lines) {
            if (baseline > y + h + Math.abs(fm.ascent)) break;
            canvas.drawText(line, tx, baseline, paint);
            baseline += lineHeight;
        }
    }

    private void eraseRasterTextPreserveBackground(int left, int top, int width, int height) {
        if (width < 2 || height < 2) return;

        int count = width * height;
        int[] src = new int[count];
        bitmap.getPixels(src, 0, width, left, top, width, height);

        int borderColor = estimateRegionBorderColor(src, width, height);
        int radius = clamp(Math.round(height * 0.12f), 3, 12);

        int[] localDiff = new int[count];
        int[] borderDiff = new int[count];
        double sum = 0.0;
        double sumSq = 0.0;

        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                int i = yy * width + xx;
                int local = ringAverageColor(src, width, height, xx, yy, radius);
                int dLocal = colorDistance(src[i], local);
                int dBorder = colorDistance(src[i], borderColor);
                localDiff[i] = dLocal;
                borderDiff[i] = dBorder;
                sum += dLocal;
                sumSq += (double) dLocal * dLocal;
            }
        }

        double mean = sum / Math.max(1, count);
        double variance = Math.max(0.0, sumSq / Math.max(1, count) - mean * mean);
        double std = Math.sqrt(variance);
        int threshold = clamp((int) Math.round(mean + std * 1.15), 28, 78);

        boolean[] mask = new boolean[count];
        int masked = 0;
        for (int i = 0; i < count; i++) {
            if (localDiff[i] >= threshold && borderDiff[i] >= 24) {
                mask[i] = true;
                masked++;
            }
        }

        // Tight boxes sometimes make the border estimate less distinct. Use a
        // slightly more permissive pass only when the first pass found almost
        // nothing, while still requiring clear separation from the border tone.
        int minimumUsefulMask = Math.max(8, count / 1200);
        if (masked < minimumUsefulMask) {
            int relaxed = Math.max(22, threshold - 12);
            for (int i = 0; i < count; i++) {
                if (!mask[i] && localDiff[i] >= relaxed && borderDiff[i] >= 34) {
                    mask[i] = true;
                    masked++;
                }
            }
        }

        if (masked == 0) return;

        // Reject obvious non-glyph components before inpainting. This keeps
        // long rules, borders and other high-contrast artwork from being
        // mistaken for text merely because they contrast with the background.
        mask = filterLikelyGlyphComponents(mask, width, height);
        masked = 0;
        for (boolean value : mask) if (value) masked++;
        if (masked == 0) return;

        // Include antialiased glyph edges without expanding far into artwork.
        boolean[] expanded = mask.clone();
        for (int yy = 1; yy < height - 1; yy++) {
            for (int xx = 1; xx < width - 1; xx++) {
                int i = yy * width + xx;
                if (!mask[i]) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        expanded[(yy + dy) * width + (xx + dx)] = true;
                    }
                }
            }
        }
        mask = expanded;

        int[] out = src.clone();
        boolean[] known = new boolean[count];
        for (int i = 0; i < count; i++) known[i] = !mask[i];

        // Wavefront inpainting copies only local surrounding artwork into the
        // removed glyphs, so gradients and decorative backgrounds stay intact.
        int maxPasses = Math.min(32, Math.max(width, height));
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean changed = false;
            int[] next = out.clone();
            boolean[] nextKnown = known.clone();

            for (int yy = 0; yy < height; yy++) {
                for (int xx = 0; xx < width; xx++) {
                    int i = yy * width + xx;
                    if (known[i]) continue;

                    long aa = 0, rr = 0, gg = 0, bb = 0;
                    int samples = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = yy + dy;
                        if (ny < 0 || ny >= height) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = xx + dx;
                            if (nx < 0 || nx >= width) continue;
                            int ni = ny * width + nx;
                            if (!known[ni]) continue;
                            int c = out[ni];
                            aa += Color.alpha(c);
                            rr += Color.red(c);
                            gg += Color.green(c);
                            bb += Color.blue(c);
                            samples++;
                        }
                    }

                    if (samples >= 2) {
                        next[i] = Color.argb(
                                (int) (aa / samples),
                                (int) (rr / samples),
                                (int) (gg / samples),
                                (int) (bb / samples));
                        nextKnown[i] = true;
                        changed = true;
                    }
                }
            }

            out = next;
            known = nextKnown;
            if (!changed) break;
        }

        // Extremely thick isolated glyph centers are rare; if any remain,
        // fill only those still-masked pixels with the region border estimate.
        for (int i = 0; i < count; i++) {
            if (!known[i]) out[i] = borderColor;
        }

        bitmap.setPixels(out, 0, width, left, top, width, height);
    }

    private int estimateRegionBorderColor(int[] px, int width, int height) {
        long aa = 0, rr = 0, gg = 0, bb = 0;
        int samples = 0;
        int band = Math.max(1, Math.min(3, Math.min(width, height) / 8));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x >= band && x < width - band &&
                        y >= band && y < height - band) {
                    continue;
                }
                int c = px[y * width + x];
                aa += Color.alpha(c);
                rr += Color.red(c);
                gg += Color.green(c);
                bb += Color.blue(c);
                samples++;
            }
        }

        if (samples == 0) return Color.TRANSPARENT;
        return Color.argb(
                (int) (aa / samples),
                (int) (rr / samples),
                (int) (gg / samples),
                (int) (bb / samples));
    }

    private boolean[] filterLikelyGlyphComponents(boolean[] candidate,
                                                  int width, int height) {
        int count = width * height;
        boolean[] accepted = new boolean[count];

        // Reuse one queue whose capacity follows the largest actual candidate
        // component rather than the full selected region. The candidate array
        // itself doubles as the visited map by clearing entries when enqueued.
        int initialCapacity = Math.max(32, Math.min(256, count));
        int[] queue = new int[initialCapacity];

        int maxReasonableArea = Math.max(24, (int) (count * 0.42f));
        int longHorizontal = Math.max(12, Math.round(width * 0.55f));
        int longVertical = Math.max(12, Math.round(height * 0.55f));
        int thinHorizontalHeight = Math.max(2, Math.round(height * 0.10f));
        int thinVerticalWidth = Math.max(2, Math.round(width * 0.10f));

        for (int startIndex = 0; startIndex < count; startIndex++) {
            if (!candidate[startIndex]) continue;

            int head = 0;
            int tail = 0;
            if (tail == queue.length) {
                queue = java.util.Arrays.copyOf(queue, queue.length * 2);
            }
            queue[tail++] = startIndex;
            candidate[startIndex] = false;

            int minX = width, minY = height, maxX = -1, maxY = -1;
            int area = 0;

            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                area++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) continue;
                        int ni = ny * width + nx;
                        if (!candidate[ni]) continue;

                        if (tail == queue.length) {
                            int nextCapacity = Math.min(count,
                                    Math.max(queue.length + 1, queue.length * 2));
                            queue = java.util.Arrays.copyOf(queue, nextCapacity);
                        }
                        queue[tail++] = ni;
                        candidate[ni] = false;
                    }
                }
            }

            int boxW = Math.max(1, maxX - minX + 1);
            int boxH = Math.max(1, maxY - minY + 1);
            boolean longThinHorizontal =
                    boxW >= longHorizontal && boxH <= thinHorizontalHeight;
            boolean longThinVertical =
                    boxH >= longVertical && boxW <= thinVerticalWidth;
            boolean hugeArtwork = area > maxReasonableArea;

            // Tight manual selections are allowed: Arabic words or individual
            // glyphs can legitimately touch opposite edges. Reject only
            // components whose geometry is clearly line-like or implausibly
            // large for text inside the selected region.
            boolean keep = area >= 2 &&
                    !longThinHorizontal &&
                    !longThinVertical &&
                    !hugeArtwork;

            if (keep) {
                for (int i = 0; i < tail; i++) {
                    accepted[queue[i]] = true;
                }
            }
        }

        return accepted;
    }

    private int ringAverageColor(int[] px, int width, int height,
                                 int x, int y, int radius) {
        int x0 = clamp(x - radius, 0, width - 1);
        int x1 = clamp(x + radius, 0, width - 1);
        int y0 = clamp(y - radius, 0, height - 1);
        int y1 = clamp(y + radius, 0, height - 1);

        int c1 = px[y * width + x0];
        int c2 = px[y * width + x1];
        int c3 = px[y0 * width + x];
        int c4 = px[y1 * width + x];
        int c5 = px[y0 * width + x0];
        int c6 = px[y0 * width + x1];
        int c7 = px[y1 * width + x0];
        int c8 = px[y1 * width + x1];

        int alpha = (Color.alpha(c1) + Color.alpha(c2) + Color.alpha(c3) + Color.alpha(c4) +
                Color.alpha(c5) + Color.alpha(c6) + Color.alpha(c7) + Color.alpha(c8)) / 8;
        int red = (Color.red(c1) + Color.red(c2) + Color.red(c3) + Color.red(c4) +
                Color.red(c5) + Color.red(c6) + Color.red(c7) + Color.red(c8)) / 8;
        int green = (Color.green(c1) + Color.green(c2) + Color.green(c3) + Color.green(c4) +
                Color.green(c5) + Color.green(c6) + Color.green(c7) + Color.green(c8)) / 8;
        int blue = (Color.blue(c1) + Color.blue(c2) + Color.blue(c3) + Color.blue(c4) +
                Color.blue(c5) + Color.blue(c6) + Color.blue(c7) + Color.blue(c8)) / 8;

        return Color.argb(alpha, red, green, blue);
    }

    private int colorDistance(int a, int b) {
        int da = Math.abs(Color.alpha(a) - Color.alpha(b));
        int dr = Math.abs(Color.red(a) - Color.red(b));
        int dg = Math.abs(Color.green(a) - Color.green(b));
        int db = Math.abs(Color.blue(a) - Color.blue(b));

        // Preserve the exact RGB sensitivity used before alpha support was
        // added, while still detecting alpha-only contrast on transparent art.
        int rgbDistance = (dr + dg + db) / 3;
        return Math.max(rgbDistance, da);
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
        if (text.trim().isEmpty()) throw new IllegalArgumentException("النص فارغ");

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(parseColor(p.optString("color", "#ffffff")));
        float size = Math.max(4f, (float) p.optDouble("size", 42));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(
                p.optString("font", "sans-serif"),
                p.optBoolean("bold", false) ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextScaleX(Math.max(0.5f, Math.min(2.0f,
                (float) p.optDouble("width_scale", 1.0))));

        float x = (float) p.optDouble("x", bitmap.getWidth() / 2f);
        float y = (float) p.optDouble("y", bitmap.getHeight() / 2f);
        float scale = Math.max(0.15f, Math.min(8f,
                (float) p.optDouble("scale", 1.0)));
        float rotation = (float) p.optDouble("rotation", 0);

        Canvas canvas = new Canvas(bitmap);
        String[] lines = text.split("\\n", -1);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = Math.max(size * 1.2f, fm.descent - fm.ascent);
        float firstBaseline = -((lines.length - 1) * lineHeight) / 2f
                - (fm.ascent + fm.descent) / 2f;

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);
        canvas.scale(scale, scale);
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], 0f, firstBaseline + i * lineHeight, paint);
        }
        canvas.restore();
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

            int deltaR = 0, deltaG = 0, deltaB = 0;
            if (heal) {
                // Keep color matching bounded even with the 300 px brush.
                // The paint loop still uses the full brush radius; only the
                // average-color probes are capped because larger disks add
                // little visual value while becoming quadratic per point.
                int sampleRadius = Math.max(1, Math.min(radius / 2, 24));
                int targetAvg = neighborhoodAverage(original, w, h, cx, cy, sampleRadius);
                int sourceAvg = neighborhoodAverage(original, w, h,
                        cx + offsetX, cy + offsetY, sampleRadius);
                deltaR = Color.red(targetAvg) - Color.red(sourceAvg);
                deltaG = Color.green(targetAvg) - Color.green(sourceAvg);
                deltaB = Color.blue(targetAvg) - Color.blue(sourceAvg);
            }

            for (int dy = -radius; dy <= radius; dy++) {
                int yy = cy + dy;
                int sy = yy + offsetY;
                if (yy < 0 || yy >= h || sy < 0 || sy >= h) continue;

                for (int dx = -radius; dx <= radius; dx++) {
                    int distSq = dx * dx + dy * dy;
                    if (distSq > radius * radius) continue;

                    int xx = cx + dx;
                    int sx = xx + offsetX;
                    if (xx < 0 || xx >= w || sx < 0 || sx >= w) continue;

                    int di = yy * w + xx;
                    int si = sy * w + sx;
                    int source = original[si];
                    int dest = out[di];

                    float edge = 1f - (float) Math.sqrt(distSq) / Math.max(1f, radius);
                    float local = opacity * Math.max(0.12f, edge);

                    if (heal) {
                        int corrected = Color.argb(
                                Color.alpha(source),
                                clamp(Color.red(source) + deltaR, 0, 255),
                                clamp(Color.green(source) + deltaG, 0, 255),
                                clamp(Color.blue(source) + deltaB, 0, 255));
                        out[di] = blend(dest, corrected, local * 0.82f);
                    } else {
                        out[di] = blend(dest, source, local);
                    }
                }
            }
        }
        setPixels(out);
    }

    private int neighborhoodAverage(int[] px, int w, int h,
                                    int cx, int cy, int radius) {
        long aa = 0, rr = 0, gg = 0, bb = 0, count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int y = cy + dy;
            if (y < 0 || y >= h) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int x = cx + dx;
                if (x < 0 || x >= w) continue;
                if (dx * dx + dy * dy > radius * radius) continue;
                int c = px[y * w + x];
                aa += Color.alpha(c);
                rr += Color.red(c);
                gg += Color.green(c);
                bb += Color.blue(c);
                count++;
            }
        }
        if (count == 0) return Color.TRANSPARENT;
        return Color.argb((int) (aa / count), (int) (rr / count),
                (int) (gg / count), (int) (bb / count));
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
