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
    private static final int PICK_FIRST = 1001;
    private static final int PICK_SECOND = 1002;
    private static final String SERVER_BASE = "http://91.98.126.167:18083";

    private Uri firstUri;
    private Uri secondUri;

    private byte[] stage1Png;
    private byte[] stage2Jpg;
    private byte[] stage3Png;
    private byte[] stage4Jpg;

    private ImageView firstPreview;
    private ImageView secondPreview;
    private ImageView stage1Preview;
    private ImageView stage2Preview;
    private ImageView stage3Preview;
    private ImageView stage4Preview;

    private Button stage1Button;
    private Button stage2Button;
    private Button save2Button;
    private Button stage3Button;
    private Button save3Button;
    private Button stage4Button;
    private Button save4Button;

    private ProgressBar progress;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(root);

        TextView title = title("UNB Image Editor", 26);
        root.addView(title);

        TextView subtitle = title("قص الشخص + استعادة الخلفية + تركيب شخص جديد", 16);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        root.addView(sectionTitle("الصورة الأولى"));
        firstPreview = imageBox(260);
        root.addView(firstPreview);

        Button pickFirst = new Button(this);
        pickFirst.setText("اختيار الصورة الأولى");
        root.addView(pickFirst);

        stage1Button = new Button(this);
        stage1Button.setText("1) قص الشخص من الصورة الأولى");
        stage1Button.setEnabled(false);
        root.addView(stage1Button);

        root.addView(sectionTitle("معاينة المرحلة 1"));
        stage1Preview = imageBox(220);
        root.addView(stage1Preview);

        stage2Button = new Button(this);
        stage2Button.setText("2) تعبئة نفس الخلفية الأصلية");
        stage2Button.setEnabled(false);
        root.addView(stage2Button);

        root.addView(sectionTitle("نتيجة المرحلة 2 — الخلفية المستعادة"));
        stage2Preview = imageBox(250);
        root.addView(stage2Preview);

        save2Button = new Button(this);
        save2Button.setText("تحميل المرحلة 2");
        save2Button.setEnabled(false);
        root.addView(save2Button);

        root.addView(sectionTitle("الصورة الثانية"));
        secondPreview = imageBox(240);
        root.addView(secondPreview);

        Button pickSecond = new Button(this);
        pickSecond.setText("اختيار الصورة الثانية");
        root.addView(pickSecond);

        stage3Button = new Button(this);
        stage3Button.setText("3) قص الشخص من الصورة الثانية");
        stage3Button.setEnabled(false);
        root.addView(stage3Button);

        root.addView(sectionTitle("نتيجة المرحلة 3 — القصاصة"));
        stage3Preview = imageBox(240);
        root.addView(stage3Preview);

        save3Button = new Button(this);
        save3Button.setText("تحميل المرحلة 3");
        save3Button.setEnabled(false);
        root.addView(save3Button);

        stage4Button = new Button(this);
        stage4Button.setText("4) تركيب الشخص الثاني على الخلفية");
        stage4Button.setEnabled(false);
        root.addView(stage4Button);

        root.addView(sectionTitle("النتيجة النهائية"));
        stage4Preview = imageBox(290);
        root.addView(stage4Preview);

        save4Button = new Button(this);
        save4Button.setText("تحميل المرحلة 4");
        save4Button.setEnabled(false);
        root.addView(save4Button);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(48), dp(48));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, pp);

        status = title("جاهز", 15);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        pickFirst.setOnClickListener(v -> chooseImage(PICK_FIRST));
        pickSecond.setOnClickListener(v -> chooseImage(PICK_SECOND));

        stage1Button.setOnClickListener(v -> runStage1());
        stage2Button.setOnClickListener(v -> runStage2());
        stage3Button.setOnClickListener(v -> runStage3());
        stage4Button.setOnClickListener(v -> runStage4());

        save2Button.setOnClickListener(v ->
                saveBytes(stage2Jpg, "UNB_stage2_background_", "image/jpeg", ".jpg"));

        save3Button.setOnClickListener(v ->
                saveBytes(stage3Png, "UNB_stage3_subject_", "image/png", ".png"));

        save4Button.setOnClickListener(v ->
                saveBytes(stage4Jpg, "UNB_stage4_final_", "image/jpeg", ".jpg"));

        setContentView(scroll);
    }

    private TextView title(String text, int size) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private TextView sectionTitle(String text) {
        TextView v = title(text, 17);
        v.setPadding(0, dp(14), 0, dp(6));
        return v;
    }

    private ImageView imageBox(int heightDp) {
        ImageView v = new ImageView(this);
        v.setAdjustViewBounds(true);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(0xFFF1F1F1);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        ));
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

        if (requestCode == PICK_FIRST) {
            firstUri = uri;
            firstPreview.setImageURI(uri);

            stage1Png = null;
            stage2Jpg = null;
            stage4Jpg = null;

            stage1Preview.setImageDrawable(null);
            stage2Preview.setImageDrawable(null);
            stage4Preview.setImageDrawable(null);

            stage1Button.setEnabled(true);
            stage2Button.setEnabled(false);
            save2Button.setEnabled(false);
            save4Button.setEnabled(false);

            status.setText("الصورة الأولى جاهزة");
        }

        if (requestCode == PICK_SECOND) {
            secondUri = uri;
            secondPreview.setImageURI(uri);

            stage3Png = null;
            stage4Jpg = null;

            stage3Preview.setImageDrawable(null);
            stage4Preview.setImageDrawable(null);

            stage3Button.setEnabled(true);
            save3Button.setEnabled(false);
            save4Button.setEnabled(false);

            status.setText("الصورة الثانية جاهزة");
        }

        refreshStage4();
    }

    private void setBusy(boolean busy, String text) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setText(text);

        if (busy) {
            stage1Button.setEnabled(false);
            stage2Button.setEnabled(false);
            stage3Button.setEnabled(false);
            stage4Button.setEnabled(false);
        } else {
            stage1Button.setEnabled(firstUri != null);
            stage2Button.setEnabled(firstUri != null && stage1Png != null);
            stage3Button.setEnabled(secondUri != null);
            refreshStage4();
        }
    }

    private void refreshStage4() {
        stage4Button.setEnabled(stage2Jpg != null && stage3Png != null && firstUri != null);
    }

    private void runStage1() {
        if (firstUri == null) return;

        setBusy(true, "المرحلة 1: جاري قص الشخص الأول...");

        new Thread(() -> {
            try {
                byte[] first = readAll(getContentResolver().openInputStream(firstUri));
                byte[] result = postMultipart(
                        SERVER_BASE + "/api/stage1/cut-first",
                        new Part("file", "first.jpg", "image/jpeg", first)
                );

                Bitmap bmp = decodeBitmap(result);
                stage1Png = result;

                runOnUiThread(() -> {
                    stage1Preview.setImageBitmap(bmp);
                    stage2Button.setEnabled(true);
                    setBusy(false, "تمت المرحلة 1 — الشخص الأول مقصوص");
                });
            } catch (Exception e) {
                fail("المرحلة 1", e);
            }
        }).start();
    }

    private void runStage2() {
        if (firstUri == null || stage1Png == null) return;

        setBusy(true, "المرحلة 2: الذكاء الاصطناعي يعيد بناء نفس الخلفية...");

        new Thread(() -> {
            try {
                byte[] first = readAll(getContentResolver().openInputStream(firstUri));
                byte[] result = postMultipart(
                        SERVER_BASE + "/api/stage2/restore-background",
                        new Part("file", "first.jpg", "image/jpeg", first)
                );

                Bitmap bmp = decodeBitmap(result);
                stage2Jpg = result;

                runOnUiThread(() -> {
                    stage2Preview.setImageBitmap(bmp);
                    save2Button.setEnabled(true);
                    refreshStage4();
                    setBusy(false, "تمت المرحلة 2 — الخلفية الأصلية مستعادة");
                });
            } catch (Exception e) {
                fail("المرحلة 2", e);
            }
        }).start();
    }

    private void runStage3() {
        if (secondUri == null) return;

        setBusy(true, "المرحلة 3: جاري قص الشخص من الصورة الثانية...");

        new Thread(() -> {
            try {
                byte[] second = readAll(getContentResolver().openInputStream(secondUri));
                byte[] result = postMultipart(
                        SERVER_BASE + "/api/stage3/cut-second",
                        new Part("file", "second.jpg", "image/jpeg", second)
                );

                Bitmap bmp = decodeBitmap(result);
                stage3Png = result;

                runOnUiThread(() -> {
                    stage3Preview.setImageBitmap(bmp);
                    save3Button.setEnabled(true);
                    refreshStage4();
                    setBusy(false, "تمت المرحلة 3 — الشخص الثاني مقصوص");
                });
            } catch (Exception e) {
                fail("المرحلة 3", e);
            }
        }).start();
    }

    private void runStage4() {
        if (firstUri == null || stage2Jpg == null || stage3Png == null) return;

        setBusy(true, "المرحلة 4: جاري تركيب الشخص الثاني في مكان الأول...");

        new Thread(() -> {
            try {
                byte[] first = readAll(getContentResolver().openInputStream(firstUri));

                byte[] result = postMultipart(
                        SERVER_BASE + "/api/stage4/composite",
                        new Part("target", "first.jpg", "image/jpeg", first),
                        new Part("background", "background.jpg", "image/jpeg", stage2Jpg),
                        new Part("subject", "subject.png", "image/png", stage3Png)
                );

                Bitmap bmp = decodeBitmap(result);
                stage4Jpg = result;

                runOnUiThread(() -> {
                    stage4Preview.setImageBitmap(bmp);
                    save4Button.setEnabled(true);
                    setBusy(false, "تمت المرحلة 4 — النتيجة جاهزة");
                });
            } catch (Exception e) {
                fail("المرحلة 4", e);
            }
        }).start();
    }

    private void fail(String stage, Exception e) {
        String message = e.getMessage() == null ? "خطأ غير معروف" : e.getMessage();
        runOnUiThread(() -> {
            setBusy(false, stage + " فشلت: " + message);
            Toast.makeText(this, stage + " فشلت", Toast.LENGTH_LONG).show();
        });
    }

    private Bitmap decodeBitmap(byte[] bytes) throws Exception {
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bmp == null) throw new Exception("الخادم لم يرجع صورة صالحة");
        return bmp;
    }

    private byte[] postMultipart(String endpoint, Part... parts) throws Exception {
        String boundary = "----UNB" + UUID.randomUUID();
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();

        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(360000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setRequestProperty("Connection", "close");

            try (OutputStream out = c.getOutputStream()) {
                for (Part p : parts) {
                    String head =
                            "--" + boundary + "\r\n" +
                            "Content-Disposition: form-data; name=\"" + p.name +
                            "\"; filename=\"" + p.filename + "\"\r\n" +
                            "Content-Type: " + p.mime + "\r\n\r\n";

                    out.write(head.getBytes("UTF-8"));
                    out.write(p.bytes);
                    out.write("\r\n".getBytes("UTF-8"));
                }

                out.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
            }

            int code = c.getResponseCode();

            if (code < 200 || code >= 300) {
                InputStream err = c.getErrorStream();
                String body = "";
                if (err != null) {
                    body = new String(readAll(err), "UTF-8");
                }
                throw new Exception("HTTP " + code + (body.isEmpty() ? "" : " — " + body));
            }

            return readAll(c.getInputStream());

        } finally {
            c.disconnect();
        }
    }

    private void saveBytes(byte[] bytes, String prefix, String mime, String ext) {
        if (bytes == null) return;

        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    prefix + System.currentTimeMillis() + ext
            );
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/UNB Image Editor"
            );

            Uri uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );

            if (uri == null) throw new Exception("تعذر إنشاء الملف");

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("تعذر فتح الملف");
                out.write(bytes);
            }

            Toast.makeText(this, "تم الحفظ في الاستوديو", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "فشل الحفظ: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        if (input == null) throw new Exception("تعذر قراءة الصورة");

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

    private static class Part {
        final String name;
        final String filename;
        final String mime;
        final byte[] bytes;

        Part(String name, String filename, String mime, byte[] bytes) {
            this.name = name;
            this.filename = filename;
            this.mime = mime;
            this.bytes = bytes;
        }
    }
}
