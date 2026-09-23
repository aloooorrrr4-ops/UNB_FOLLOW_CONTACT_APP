package com.unb.imageeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

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
        File baseDir = new File(appContext.getFilesDir(), "tesseract");
        File tessdata = new File(baseDir, "tessdata");
        if (!tessdata.exists() && !tessdata.mkdirs()) {
            throw new IllegalStateException("تعذر تجهيز ملفات OCR");
        }

        copyAssetIfNeeded("tessdata/ara.traineddata",
                new File(tessdata, "ara.traineddata"));
        copyAssetIfNeeded("tessdata/eng.traineddata",
                new File(tessdata, "eng.traineddata"));

        TessBaseAPI api = new TessBaseAPI();
        try {
            boolean ready = api.init(baseDir.getAbsolutePath(), "ara+eng");
            if (!ready) throw new IllegalStateException("فشل تشغيل محرك التعرف على النص");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            api.setImage(input);
            String text = api.getUTF8Text();
            int confidence = api.meanConfidence();
            return new Result(text, confidence);
        } finally {
            api.recycle();
            if (input != source && !input.isRecycled()) input.recycle();
        }
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
