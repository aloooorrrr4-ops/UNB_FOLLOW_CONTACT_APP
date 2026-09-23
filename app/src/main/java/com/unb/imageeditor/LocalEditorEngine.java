package com.unb.imageeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * First offline engine for UNB Pro Editor.
 *
 * The UI talks only to this class. Later phases can move heavy filters to
 * C++/NDK/GPU without changing the existing editor interface.
 */
public final class LocalEditorEngine {

    private static final int MAX_HISTORY = 12;

    private Bitmap bitmap;
    private Rect selection;
    private final Deque<Bitmap> undo = new ArrayDeque<>();
    private final Deque<Bitmap> redo = new ArrayDeque<>();

    private Bitmap adjustmentBase;
    private String adjustmentOperation;
    private int adjustmentValue;

    private Bitmap reusableAdjustmentBase;
    private String reusableAdjustmentOperation;
    private int reusableAdjustmentValue;

    public synchronized void open(Bitmap source) {
        if (source == null) throw new IllegalArgumentException("الصورة غير صالحة");
        undo.clear();
        redo.clear();
        bitmap = mutable(source);
        selection = null;
        clearAdjustmentSession();
        clearReusableAdjustment();
    }

    public synchronized boolean hasImage() {
        return bitmap != null;
    }

    public synchronized Bitmap current() {
        return bitmap;
    }

    public synchronized int width() {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    public synchronized int height() {
        return bitmap == null ? 0 : bitmap.getHeight();
    }

    public synchronized int undoDepth() {
        return undo.size();
    }

    public synchronized int redoDepth() {
        return redo.size();
    }

    public synchronized Bitmap undo() {
        requireImage();
        clearAdjustmentSession();
        clearReusableAdjustment();
        if (undo.isEmpty()) throw new IllegalStateException("لا توجد خطوة أقدم");
        redo.addLast(copy(bitmap));
        trim(redo);
        bitmap = undo.removeLast();
        selection = null;
        return bitmap;
    }

    public synchronized Bitmap redo() {
        requireImage();
        clearAdjustmentSession();
        clearReusableAdjustment();
        if (redo.isEmpty()) throw new IllegalStateException("لا توجد خطوة لإعادتها");
        undo.addLast(copy(bitmap));
        trim(undo);
        bitmap = redo.removeLast();
        selection = null;
        return bitmap;
    }

    public synchronized int beginAdjustment(String operation, int requestedValue) {
        requireImage();
        String op = normalizeAdjustment(operation);
        clearAdjustmentSession();

        if (reusableAdjustmentBase != null &&
                op.equals(reusableAdjustmentOperation) &&
                requestedValue == reusableAdjustmentValue) {
            adjustmentBase = copy(reusableAdjustmentBase);
            adjustmentOperation = op;
            adjustmentValue = reusableAdjustmentValue;
            return reusableAdjustmentValue;
        }

        adjustmentBase = copy(bitmap);
        adjustmentOperation = op;
        adjustmentValue = 0;
        return 0;
    }

    public synchronized Bitmap previewAdjustment(String operation, int value) {
        requireImage();
        String op = normalizeAdjustment(operation);
        if (adjustmentBase == null || !op.equals(adjustmentOperation)) {
            beginAdjustment(op, 0);
        }

        adjustmentValue = Math.max(-100, Math.min(100, value));
        bitmap = copy(adjustmentBase);

        if ("brightness".equals(op)) {
            brightness(adjustmentValue);
        } else if ("contrast".equals(op)) {
            contrast(adjustmentValue);
        } else if ("saturation".equals(op)) {
            saturation(adjustmentValue);
        }
        return bitmap;
    }

    public synchronized Bitmap commitAdjustment() {
        requireImage();
        if (adjustmentBase == null) return bitmap;

        if (adjustmentValue == 0) {
            bitmap = adjustmentBase;
            clearReusableAdjustment();
        } else {
            undo.addLast(adjustmentBase);
            trim(undo);
            redo.clear();

            reusableAdjustmentBase = copy(adjustmentBase);
            reusableAdjustmentOperation = adjustmentOperation;
            reusableAdjustmentValue = adjustmentValue;
        }

        adjustmentBase = null;
        adjustmentOperation = null;
        adjustmentValue = 0;
        return bitmap;
    }

    public synchronized Bitmap cancelAdjustment() {
        if (adjustmentBase != null) {
            bitmap = adjustmentBase;
        }
        clearAdjustmentSession();
        return bitmap;
    }

    public synchronized boolean hasAdjustmentSession() {
        return adjustmentBase != null;
    }

    private String normalizeAdjustment(String operation) {
        String op = operation == null ? "" : operation.trim().toLowerCase();
        if (!"brightness".equals(op) && !"contrast".equals(op) && !"saturation".equals(op)) {
            throw new IllegalArgumentException("تعديل غير مدعوم");
        }
        return op;
    }

    private void clearAdjustmentSession() {
        adjustmentBase = null;
        adjustmentOperation = null;
        adjustmentValue = 0;
    }

    private void clearReusableAdjustment() {
        reusableAdjustmentBase = null;
        reusableAdjustmentOperation = null;
        reusableAdjustmentValue = 0;
    }

    public synchronized Bitmap apply(String operation, JSONObject params) {
        requireImage();
        clearAdjustmentSession();
        clearReusableAdjustment();
        String op = operation == null ? "" : operation.trim().toLowerCase();
        JSONObject p = params == null ? new JSONObject() : params;

        if (selectionOperation(op, p)) return bitmap;

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
                case "equalize":
                    equalize();
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
                case "gradient":
                    gradient(p);
                    break;
                case "add_text":
                    addText(p);
                    break;
                case "flatten":
                case "merge_visible":
                    // Phase 1 has one raster layer; these are already flattened.
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "الأداة " + op + " ستُنقل للمحرك المحلي في المرحلة التالية");
            }

            undo.addLast(before);
            trim(undo);
            redo.clear();
            return bitmap;
        } catch (RuntimeException e) {
            bitmap = original;
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
        if (!bitmap.compress(cf, q, out)) {
            throw new IllegalStateException("فشل ضغط الصورة");
        }
        return out.toByteArray();
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

        if (erase) {
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
        paint.setShader(new LinearGradient(x1, y1, x2, y2, fg, bg, Shader.TileMode.CLAMP));
        Canvas canvas = new Canvas(bitmap);
        Rect target = selection == null
                ? new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
                : new Rect(selection);
        canvas.drawRect(target, paint);
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
