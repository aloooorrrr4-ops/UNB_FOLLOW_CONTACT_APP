package com.unb.imageeditor;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK_FIRST = 1001;
    private static final int PICK_SECOND = 1002;
    private static final int REQ_NOTIFICATIONS = 2001;

    private static final String DEFAULT_SERVER_BASE = "http://91.98.126.167:18083";
    private static final String PREFS = "unb_image_editor_settings";
    private static final String KEY_SERVER = "server_base";

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
    private EditText serverInput;
    private String serverBase;

    private boolean receiverRegistered = false;

    private final BroadcastReceiver stageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stage = intent.getIntExtra(AiProcessService.EXTRA_STAGE, 0);
            String message = intent.getStringExtra(AiProcessService.EXTRA_MESSAGE);

            progress.setVisibility(View.GONE);
            loadExistingFiles();
            refreshButtons();

            if (AiProcessService.ACTION_DONE.equals(intent.getAction())) {
                status.setText(message == null ? "تمت المعالجة" : message);
                Toast.makeText(MainActivity.this, "تمت المرحلة " + stage, Toast.LENGTH_SHORT).show();
            } else {
                status.setText("فشل المرحلة " + stage + ": " + message);
                Toast.makeText(MainActivity.this, "فشل المرحلة " + stage, Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(root);

        root.addView(title("UNB Image Editor", 26));

        TextView subtitle = title("قص الشخص + استعادة الخلفية + تركيب شخص جديد", 16);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        serverBase = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SERVER, DEFAULT_SERVER_BASE);

        root.addView(sectionTitle("رابط خادم AI"));

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setText(serverBase);
        serverInput.setHint("http://SERVER:PORT");
        root.addView(serverInput);

        Button saveServer = new Button(this);
        saveServer.setText("حفظ رابط الخادم");
        root.addView(saveServer);
        saveServer.setOnClickListener(v -> saveServerAddress());

        Button ocrEditor = new Button(this);
        ocrEditor.setText("محرر النصوص OCR — اكتشاف واستبدال النص");
        root.addView(ocrEditor);
        ocrEditor.setOnClickListener(v -> {
            saveServerAddress();
            startActivity(new Intent(this, OcrEditorActivity.class));
        });

        root.addView(sectionTitle("الصورة الأولى"));
        firstPreview = imageBox(260);
        root.addView(firstPreview);

        Button pickFirst = new Button(this);
        pickFirst.setText("اختيار الصورة الأولى");
        root.addView(pickFirst);

        stage1Button = new Button(this);
        stage1Button.setText("1) قص الشخص من الصورة الأولى");
        root.addView(stage1Button);

        root.addView(sectionTitle("معاينة المرحلة 1"));
        stage1Preview = imageBox(220);
        root.addView(stage1Preview);

        stage2Button = new Button(this);
        stage2Button.setText("2) تعبئة نفس الخلفية الأصلية");
        root.addView(stage2Button);

        root.addView(sectionTitle("نتيجة المرحلة 2 — الخلفية المستعادة"));
        stage2Preview = imageBox(250);
        root.addView(stage2Preview);

        save2Button = new Button(this);
        save2Button.setText("تحميل المرحلة 2");
        root.addView(save2Button);

        root.addView(sectionTitle("الصورة الثانية"));
        secondPreview = imageBox(240);
        root.addView(secondPreview);

        Button pickSecond = new Button(this);
        pickSecond.setText("اختيار الصورة الثانية");
        root.addView(pickSecond);

        stage3Button = new Button(this);
        stage3Button.setText("3) قص الشخص من الصورة الثانية");
        root.addView(stage3Button);

        root.addView(sectionTitle("نتيجة المرحلة 3 — القصاصة"));
        stage3Preview = imageBox(240);
        root.addView(stage3Preview);

        save3Button = new Button(this);
        save3Button.setText("تحميل المرحلة 3");
        root.addView(save3Button);

        stage4Button = new Button(this);
        stage4Button.setText("4) تركيب الشخص الثاني على الخلفية");
        root.addView(stage4Button);

        root.addView(sectionTitle("النتيجة النهائية"));
        stage4Preview = imageBox(290);
        root.addView(stage4Preview);

        save4Button = new Button(this);
        save4Button.setText("تحميل المرحلة 4");
        root.addView(save4Button);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(48), dp(48));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, pp);

        status = title("جاهز — المعالجة تستمر حتى لو خرجت من التطبيق", 15);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        pickFirst.setOnClickListener(v -> chooseImage(PICK_FIRST));
        pickSecond.setOnClickListener(v -> chooseImage(PICK_SECOND));

        stage1Button.setOnClickListener(v -> startStage(1));
        stage2Button.setOnClickListener(v -> startStage(2));
        stage3Button.setOnClickListener(v -> startStage(3));
        stage4Button.setOnClickListener(v -> startStage(4));

        save2Button.setOnClickListener(v ->
                saveFile(new File(getFilesDir(), AiProcessService.STAGE2),
                        "UNB_stage2_background_", "image/jpeg", ".jpg"));

        save3Button.setOnClickListener(v ->
                saveFile(new File(getFilesDir(), AiProcessService.STAGE3),
                        "UNB_stage3_subject_", "image/png", ".png"));

        save4Button.setOnClickListener(v ->
                saveFile(new File(getFilesDir(), AiProcessService.STAGE4),
                        "UNB_stage4_final_", "image/jpeg", ".jpg"));

        setContentView(scroll);

        registerStageReceiver();
        requestNotificationPermission();
        loadExistingFiles();
        refreshButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadExistingFiles();
        refreshButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            unregisterReceiver(stageReceiver);
            receiverRegistered = false;
        }
    }

    private void registerStageReceiver() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(AiProcessService.ACTION_DONE);
        filter.addAction(AiProcessService.ACTION_FAILED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stageReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS
            );
        }
    }

    private void saveServerAddress() {
        String value = serverInput.getText().toString().trim();

        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            Toast.makeText(this, "الرابط يجب أن يبدأ بـ http:// أو https://", Toast.LENGTH_LONG).show();
            return;
        }

        serverBase = value;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER, serverBase)
                .apply();

        status.setText("تم حفظ الخادم: " + serverBase);
        Toast.makeText(this, "تم حفظ رابط الخادم", Toast.LENGTH_SHORT).show();
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

        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();

        try {
            if (requestCode == PICK_FIRST) {
                copyUriToFile(uri, new File(getFilesDir(), AiProcessService.FIRST));
                delete(AiProcessService.STAGE1);
                delete(AiProcessService.STAGE2);
                delete(AiProcessService.STAGE4);
                status.setText("الصورة الأولى جاهزة");
            } else if (requestCode == PICK_SECOND) {
                copyUriToFile(uri, new File(getFilesDir(), AiProcessService.SECOND));
                delete(AiProcessService.STAGE3);
                delete(AiProcessService.STAGE4);
                status.setText("الصورة الثانية جاهزة");
            }

            loadExistingFiles();
            refreshButtons();

        } catch (Exception e) {
            Toast.makeText(this, "تعذر حفظ الصورة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startStage(int stage) {
        if (!requirementsReady(stage)) return;

        saveServerAddress();

        Intent i = new Intent(this, AiProcessService.class);
        i.putExtra(AiProcessService.EXTRA_STAGE, stage);
        i.putExtra(AiProcessService.EXTRA_SERVER, serverBase);

        progress.setVisibility(View.VISIBLE);
        status.setText(stageText(stage));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }

        Toast.makeText(
                this,
                "بدأت المرحلة " + stage + " — يمكنك الخروج من التطبيق وستستمر المعالجة",
                Toast.LENGTH_LONG
        ).show();
    }

    private boolean requirementsReady(int stage) {
        File dir = getFilesDir();

        if (stage == 1 && !exists(AiProcessService.FIRST)) {
            Toast.makeText(this, "اختر الصورة الأولى", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (stage == 2 && (!exists(AiProcessService.FIRST) || !exists(AiProcessService.STAGE1))) {
            Toast.makeText(this, "نفذ المرحلة 1 أولاً", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (stage == 3 && !exists(AiProcessService.SECOND)) {
            Toast.makeText(this, "اختر الصورة الثانية", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (stage == 4 &&
                (!exists(AiProcessService.FIRST) ||
                 !exists(AiProcessService.STAGE1) ||
                 !exists(AiProcessService.STAGE2) ||
                 !exists(AiProcessService.STAGE3))) {
            Toast.makeText(this, "أكمل المراحل 1 و2 و3 أولاً", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private String stageText(int stage) {
        if (stage == 1) return "المرحلة 1: جاري قص الشخص الأول...";
        if (stage == 2) return "المرحلة 2: جاري استعادة نفس الخلفية...";
        if (stage == 3) return "المرحلة 3: جاري قص الشخص الثاني...";
        return "المرحلة 4: جاري تركيب الشخص الثاني...";
    }

    private void refreshButtons() {
        stage1Button.setEnabled(exists(AiProcessService.FIRST));
        stage2Button.setEnabled(exists(AiProcessService.FIRST) && exists(AiProcessService.STAGE1));
        stage3Button.setEnabled(exists(AiProcessService.SECOND));
        stage4Button.setEnabled(
                exists(AiProcessService.FIRST) &&
                exists(AiProcessService.STAGE1) &&
                exists(AiProcessService.STAGE2) &&
                exists(AiProcessService.STAGE3)
        );

        save2Button.setEnabled(exists(AiProcessService.STAGE2));
        save3Button.setEnabled(exists(AiProcessService.STAGE3));
        save4Button.setEnabled(exists(AiProcessService.STAGE4));
    }

    private void loadExistingFiles() {
        setPreview(firstPreview, AiProcessService.FIRST);
        setPreview(secondPreview, AiProcessService.SECOND);
        setPreview(stage1Preview, AiProcessService.STAGE1);
        setPreview(stage2Preview, AiProcessService.STAGE2);
        setPreview(stage3Preview, AiProcessService.STAGE3);
        setPreview(stage4Preview, AiProcessService.STAGE4);
    }

    private void setPreview(ImageView view, String filename) {
        File f = new File(getFilesDir(), filename);
        if (!f.exists() || f.length() == 0) {
            view.setImageDrawable(null);
            return;
        }

        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        view.setImageBitmap(bmp);
    }

    private void copyUriToFile(Uri uri, File outFile) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile, false)) {

            if (in == null) throw new Exception("تعذر قراءة الصورة");

            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    private void saveFile(File source, String prefix, String mime, String ext) {
        if (!source.exists() || source.length() == 0) return;

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

            try (InputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri)) {

                if (out == null) throw new Exception("تعذر فتح الملف");

                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
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

    private boolean exists(String name) {
        File f = new File(getFilesDir(), name);
        return f.exists() && f.length() > 0;
    }

    private void delete(String name) {
        File f = new File(getFilesDir(), name);
        if (f.exists()) f.delete();
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
