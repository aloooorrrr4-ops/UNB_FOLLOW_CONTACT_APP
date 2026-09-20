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
    private static final int PICK_TARGET = 1001;
    private static final int PICK_SOURCE = 1002;
    private static final String SERVER_BASE = "http://91.98.126.167:18082";

    private ImageView targetPreview;
    private ImageView sourcePreview;
    private ImageView resultPreview;
    private TextView status;
    private ProgressBar progress;
    private Button swapButton;
    private Button saveButton;

    private Uri targetUri;
    private Uri sourceUri;
    private byte[] resultJpeg;

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
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("دمج الوجه");
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        TextView t1 = new TextView(this);
        t1.setText("الصورة الأساسية");
        t1.setTextSize(16);
        t1.setGravity(Gravity.CENTER);
        root.addView(t1);

        targetPreview = imageBox();
        root.addView(targetPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250)));

        Button pickTarget = new Button(this);
        pickTarget.setText("اختيار الصورة الأساسية");
        root.addView(pickTarget);

        TextView t2 = new TextView(this);
        t2.setText("صورة الوجه");
        t2.setTextSize(16);
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, dp(12), 0, 0);
        root.addView(t2);

        sourcePreview = imageBox();
        root.addView(sourcePreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        Button pickSource = new Button(this);
        pickSource.setText("اختيار صورة الوجه");
        root.addView(pickSource);

        swapButton = new Button(this);
        swapButton.setText("دمج الوجه");
        swapButton.setEnabled(false);
        root.addView(swapButton);

        TextView t3 = new TextView(this);
        t3.setText("النتيجة");
        t3.setTextSize(16);
        t3.setGravity(Gravity.CENTER);
        t3.setPadding(0, dp(12), 0, 0);
        root.addView(t3);

        resultPreview = imageBox();
        root.addView(resultPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)));

        saveButton = new Button(this);
        saveButton.setText("حفظ النتيجة");
        saveButton.setEnabled(false);
        root.addView(saveButton);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, progressParams);

        status = new TextView(this);
        status.setText("اختر الصورة الأساسية وصورة الوجه");
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, dp(12));
        root.addView(status);

        pickTarget.setOnClickListener(v -> chooseImage(PICK_TARGET));
        pickSource.setOnClickListener(v -> chooseImage(PICK_SOURCE));
        swapButton.setOnClickListener(v -> processFaceSwap());
        saveButton.setOnClickListener(v -> saveResult());

        setContentView(scroll);
    }

    private ImageView imageBox() {
        ImageView v = new ImageView(this);
        v.setAdjustViewBounds(true);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(0xFFF1F1F1);
        return v;
    }

    private void chooseImage(int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        if (requestCode == PICK_TARGET) {
            targetUri = uri;
            targetPreview.setImageURI(uri);
            status.setText("تم اختيار الصورة الأساسية");
        } else if (requestCode == PICK_SOURCE) {
            sourceUri = uri;
            sourcePreview.setImageURI(uri);
            status.setText("تم اختيار صورة الوجه");
        }

        resultJpeg = null;
        resultPreview.setImageDrawable(null);
        saveButton.setEnabled(false);
        swapButton.setEnabled(targetUri != null && sourceUri != null);

        if (targetUri != null && sourceUri != null) {
            status.setText("الصورتان جاهزتان — اضغط دمج الوجه");
        }
    }

    private void processFaceSwap() {
        if (targetUri == null || sourceUri == null) {
            Toast.makeText(this, "اختر الصورتين أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        final String endpoint = SERVER_BASE + "/api/faceswap";

        progress.setVisibility(View.VISIBLE);
        status.setText("جاري اكتشاف الوجه ودمجه...");
        swapButton.setEnabled(false);
        saveButton.setEnabled(false);

        new Thread(() -> {
            HttpURLConnection c = null;

            try {
                byte[] target = readAll(getContentResolver().openInputStream(targetUri));
                byte[] source = readAll(getContentResolver().openInputStream(sourceUri));
                String boundary = "----UNB" + UUID.randomUUID();

                c = (HttpURLConnection) new URL(endpoint).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(180000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                c.setRequestProperty("Connection", "close");

                try (OutputStream out = c.getOutputStream()) {
                    writePart(out, boundary, "target", "target.jpg", target);
                    writePart(out, boundary, "source", "source.jpg", source);
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                }

                int code = c.getResponseCode();

                if (code < 200 || code >= 300) {
                    InputStream err = c.getErrorStream();
                    String body = "";
                    if (err != null) {
                        body = new String(readAll(err), "UTF-8");
                    }
                    throw new Exception("HTTP " + code + (body.isEmpty() ? "" : " - " + body));
                }

                byte[] result = readAll(c.getInputStream());
                Bitmap bmp = BitmapFactory.decodeByteArray(result, 0, result.length);

                if (bmp == null) {
                    throw new Exception("النتيجة ليست صورة صالحة");
                }

                resultJpeg = result;

                runOnUiThread(() -> {
                    resultPreview.setImageBitmap(bmp);
                    progress.setVisibility(View.GONE);
                    status.setText("تم دمج الوجه بنجاح");
                    swapButton.setEnabled(true);
                    saveButton.setEnabled(true);
                });

            } catch (Exception e) {
                final String message =
                        e.getMessage() == null ? "تعذر الاتصال بالخادم" : e.getMessage();

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    swapButton.setEnabled(true);
                    status.setText("فشل: " + message);
                    Toast.makeText(this, "تعذر دمج الوجه", Toast.LENGTH_LONG).show();
                });

            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private void writePart(OutputStream out, String boundary, String name,
                           String filename, byte[] bytes) throws Exception {
        String head =
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name +
                "\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: image/jpeg\r\n\r\n";

        out.write(head.getBytes("UTF-8"));
        out.write(bytes);
        out.write("\r\n".getBytes("UTF-8"));
    }

    private void saveResult() {
        if (resultJpeg == null) return;

        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "UNB_faceswap_" + System.currentTimeMillis() + ".jpg"
            );
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/UNB Image Editor"
            );

            Uri uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );

            if (uri == null) {
                throw new Exception("لم يتم إنشاء الملف");
            }

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new Exception("تعذر فتح الملف");
                }
                out.write(resultJpeg);
            }

            Toast.makeText(
                    this,
                    "تم حفظ النتيجة في الاستوديو",
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "فشل الحفظ: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        if (input == null) {
            throw new Exception("تعذر قراءة الصورة");
        }

        try (InputStream in = input;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int n;

            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }

            return out.toByteArray();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
