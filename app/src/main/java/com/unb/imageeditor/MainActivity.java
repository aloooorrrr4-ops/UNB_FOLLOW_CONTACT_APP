package com.unb.imageeditor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1001;
    private static final String SERVER_BASE = "http://91.98.126.167:18081";

    private ImageView preview;
    private TextView status;
    private ProgressBar progress;
    private Button removeButton;
    private Button saveButton;
    private Uri selectedUri;
    private byte[] resultPng;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("UNB Image Editor");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("اكتشاف الخلفية وإزالتها");
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMinimumHeight(dp(280));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFFF1F1F1);
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));

        Button pick = new Button(this);
        pick.setText("اختيار صورة");
        root.addView(pick);

        removeButton = new Button(this);
        removeButton.setText("اكتشاف وإزالة الخلفية");
        root.addView(removeButton);

        saveButton = new Button(this);
        saveButton.setText("حفظ PNG");
        saveButton.setEnabled(false);
        root.addView(saveButton);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, p);

        status = new TextView(this);
        status.setText("جاهز — اختر صورة للبدء");
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        pick.setOnClickListener(v -> chooseImage());
        removeButton.setOnClickListener(v -> {
            if (selectedUri == null) {
                Toast.makeText(this, "اختر صورة أولاً", Toast.LENGTH_SHORT).show();
                return;
            }
            processImage();
        });
        saveButton.setOnClickListener(v -> saveResult());

        setContentView(scroll);
    }

    private void chooseImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            selectedUri = data.getData();
            resultPng = null;
            saveButton.setEnabled(false);
            preview.setImageURI(selectedUri);
            status.setText("الصورة جاهزة");
        }
    }

    private void processImage() {
        final String endpoint = SERVER_BASE + "/api/background/cutout";

        progress.setVisibility(View.VISIBLE);
        status.setText("جاري اكتشاف الخلفية...");
        removeButton.setEnabled(false);
        saveButton.setEnabled(false);

        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                byte[] image = readAll(getContentResolver().openInputStream(selectedUri));
                String boundary = "----UNB" + UUID.randomUUID();

                c = (HttpURLConnection) new URL(endpoint).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(120000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                c.setRequestProperty("Connection", "close");

                try (OutputStream out = c.getOutputStream()) {
                    String head = "--" + boundary + "\r\n" +
                            "Content-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\n" +
                            "Content-Type: image/jpeg\r\n\r\n";
                    out.write(head.getBytes("UTF-8"));
                    out.write(image);
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                }

                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("خطأ من الخادم: HTTP " + code);
                }

                byte[] result = readAll(c.getInputStream());
                Bitmap bmp = BitmapFactory.decodeByteArray(result, 0, result.length);
                if (bmp == null) throw new Exception("النتيجة ليست صورة صالحة");

                resultPng = result;
                runOnUiThread(() -> {
                    preview.setImageBitmap(bmp);
                    progress.setVisibility(View.GONE);
                    status.setText("تمت إزالة الخلفية بنجاح");
                    removeButton.setEnabled(true);
                    saveButton.setEnabled(true);
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "تعذر الاتصال بالخادم" : e.getMessage();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    removeButton.setEnabled(true);
                    status.setText("فشل: " + message);
                    Toast.makeText(this, "تعذر معالجة الصورة", Toast.LENGTH_LONG).show();
                });
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private void saveResult() {
        if (resultPng == null) return;
        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "UNB_cutout_" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UNB Image Editor");

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("لم يتم إنشاء الملف");

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("تعذر فتح الملف");
                out.write(resultPng);
            }

            Toast.makeText(this, "تم حفظ الصورة في الاستوديو", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل الحفظ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        if (input == null) throw new Exception("تعذر قراءة الصورة");
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
