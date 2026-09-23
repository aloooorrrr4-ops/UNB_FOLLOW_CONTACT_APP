package com.unb.imageeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fully local OCR wrapper. Arabic + English trained data are bundled into the APK
 * at build time and copied to the app-private directory on first use.
 */
public final class LocalOcrEngine {

    public static final class Result {
        public final String text;
        public final int confidence;

        Result(String text, int confidence) {
            this.text = text == null ? "" : text.trim();
            this.confidence = confidence;
        }
    }

    private final Context appContext;

    public LocalOcrEngine(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public Result recognize(Bitmap source, RectF region) throws Exception {
        if (source == null) throw new IllegalArgumentException("لا توجد صورة");

        Bitmap input = crop(source, region);
        File baseDir = prepareTessData();

        TessBaseAPI api = new TessBaseAPI();
        try {
            boolean ready = api.init(baseDir.getAbsolutePath(), "ara+eng");
            if (!ready) throw new IllegalStateException("فشل تشغيل محرك التعرف على النص");
            api.setVariable("user_defined_dpi", "300");

            Result best = recognizeMode(api, input, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE);
            if (best.text.isEmpty() || best.confidence < 45) {
                Result word = recognizeMode(api, input, TessBaseAPI.PageSegMode.PSM_SINGLE_WORD);
                if (isBetter(word, best)) best = word;
            }
            if (best.text.isEmpty() || best.confidence < 30) {
                Result sparse = recognizeMode(api, input, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
                if (isBetter(sparse, best)) best = sparse;
            }
            return best;
        } finally {
            api.recycle();
            if (input != source && !input.isRecycled()) input.recycle();
        }
    }

    public List<RectF> detectWordRegions(Bitmap source) throws Exception {
        if (source == null) throw new IllegalArgumentException("لا توجد صورة");

        File baseDir = prepareTessData();
        TessBaseAPI api = new TessBaseAPI();
        List<RectF> boxes = new ArrayList<>();

        try {
            boolean ready = api.init(baseDir.getAbsolutePath(), "ara+eng");
            if (!ready) throw new IllegalStateException("فشل تشغيل محرك التعرف على النص");

            api.setVariable("user_defined_dpi", "300");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
            api.setImage(source);

            // Recognition must run before the ResultIterator contains word data.
            api.getUTF8Text();

            ResultIterator iterator = api.getResultIterator();
            if (iterator == null) return boxes;

            try {
                iterator.begin();
                do {
                    String value = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD);
                    Rect rect = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD);
                    float confidence = iterator.confidence(PageIteratorLevel.RIL_WORD);

                    if (value == null || value.trim().isEmpty() || rect == null) continue;
                    if (confidence < 5f) continue;
                    if (rect.width() < 3 || rect.height() < 3) continue;

                    float padX = Math.max(2f, Math.min(8f, rect.height() * 0.18f));
                    float padY = Math.max(2f, Math.min(6f, rect.height() * 0.12f));
                    boxes.add(new RectF(
                            Math.max(0f, rect.left - padX),
                            Math.max(0f, rect.top - padY),
                            Math.min(source.getWidth(), rect.right + padX),
                            Math.min(source.getHeight(), rect.bottom + padY)
                    ));
                } while (iterator.next(PageIteratorLevel.RIL_WORD));
            } finally {
                iterator.delete();
            }
        } finally {
            api.recycle();
        }

        dedupeWordRegions(boxes);
        boxes.sort(Comparator
                .comparingDouble((RectF r) -> r.top)
                .thenComparingDouble(r -> r.left));
        return boxes;
    }

    private Result recognizeMode(TessBaseAPI api, Bitmap input, int pageSegMode) {
        api.clear();
        api.setPageSegMode(pageSegMode);
        api.setImage(input);
        String text = api.getUTF8Text();
        int confidence = api.meanConfidence();
        return new Result(text, confidence);
    }

    private boolean isBetter(Result candidate, Result current) {
        if (candidate == null) return false;
        if (current == null) return true;
        if (candidate.text.isEmpty() != current.text.isEmpty()) {
            return !candidate.text.isEmpty();
        }
        return candidate.confidence > current.confidence;
    }

    private File prepareTessData() throws Exception {
        File baseDir = new File(appContext.getFilesDir(), "tesseract");
        File tessdata = new File(baseDir, "tessdata");
        if (!tessdata.exists() && !tessdata.mkdirs()) {
            throw new IllegalStateException("تعذر تجهيز ملفات OCR");
        }

        copyAssetIfNeeded("tessdata/ara.traineddata",
                new File(tessdata, "ara.traineddata"));
        copyAssetIfNeeded("tessdata/eng.traineddata",
                new File(tessdata, "eng.traineddata"));
        return baseDir;
    }

    private void dedupeWordRegions(List<RectF> boxes) {
        for (int i = boxes.size() - 1; i >= 0; i--) {
            RectF a = boxes.get(i);
            for (int j = 0; j < i; j++) {
                RectF b = boxes.get(j);
                float intersection = intersectionArea(a, b);
                if (intersection <= 0f) continue;
                float minArea = Math.max(1f, Math.min(a.width() * a.height(),
                        b.width() * b.height()));
                if (intersection / minArea >= 0.82f) {
                    if (a.width() * a.height() >= b.width() * b.height()) {
                        boxes.remove(i);
                    } else {
                        boxes.set(j, a);
                        boxes.remove(i);
                    }
                    break;
                }
            }
        }
    }

    private float intersectionArea(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return 0f;
        return (right - left) * (bottom - top);
    }

    private Bitmap crop(Bitmap source, RectF region) {
        if (region == null) return source;

        int left = clamp(Math.round(region.left), 0, source.getWidth() - 1);
        int top = clamp(Math.round(region.top), 0, source.getHeight() - 1);
        int right = clamp(Math.round(region.right), left + 1, source.getWidth());
        int bottom = clamp(Math.round(region.bottom), top + 1, source.getHeight());

        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }

    private void copyAssetIfNeeded(String assetPath, File target) throws Exception {
        if (target.exists() && target.length() > 1024) return;

        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (InputStream in = appContext.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
