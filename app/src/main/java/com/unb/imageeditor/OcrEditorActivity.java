package com.unb.imageeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class OcrEditorActivity extends Activity {
    private static final int PICK_OCR_IMAGE = 3001;
    private static final String PREFS = "unb_image_editor_settings";
    private static final String KEY_SERVER = "server_base";
    private static final String DEFAULT_SERVER = "http://91.98.126.167:18083";

    private String serverBase;

    private OcrOverlayImageView imagePreview;
    private LinearLayout blocksContainer;
    private ProgressBar progress;
    private TextView status;
    private Button analyzeButton;
    private Button saveResultButton;

    private JSONArray blocks = new JSONArray();
    private boolean receiverRegistered = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stage = intent.getIntExtra(AiProcessService.EXTRA_STAGE, 0);
            if (stage != AiProcessService.OCR_DETECT && stage != AiProcessService.OCR_REPLACE) {
                return;
            }

            progress.setVisibility(View.GONE);
            analyzeButton.setEnabled(hasInput());

            String message = intent.getStringExtra(AiProcessService.EXTRA_MESSAGE);

            if (AiProcessService.ACTION_DONE.equals(intent.getAction())) {
                if (stage == AiProcessService.OCR_DETECT) {
                    loadBlocks();
                    status.setText("تم اكتشاف " + blocks.length() + " عنصر نصي");
                } else {
                    loadPreview();
                    saveResultButton.setEnabled(hasResult());
                    status.setText("تم استبدال النص — يمكنك اختيار نص آخر أو إعادة التحليل");
                }

                Toast.makeText(
                        OcrEditorActivity.this,
                        message == null ? "تمت العملية" : message,
                        Toast.LENGTH_SHORT
                ).show();

            } else {
                status.setText("فشل: " + message);
                Toast.makeText(
                        OcrEditorActivity.this,
                        "فشلت العملية: " + message,
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        serverBase = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SERVER, DEFAULT_SERVER);

        ScrollView scroll = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(30));
        scroll.addView(root);

        TextView title = text("محرر النصوص بالذكاء الاصطناعي", 24);
        root.addView(title);

        TextView server = text("الخادم: " + serverBase, 13);
        server.setPadding(0, dp(4), 0, dp(10));
        root.addView(server);

        imagePreview = new OcrOverlayImageView(this);
        imagePreview.setAdjustViewBounds(true);
        imagePreview.setBackgroundColor(0xFFF1F1F1);
        root.addView(
                imagePreview,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(420)
                )
        );

        imagePreview.setOnBlockClickListener(this::showEditDialog);

        Button pick = new Button(this);
        pick.setText("اختيار صورة أو مستند");
        root.addView(pick);

        analyzeButton = new Button(this);
        analyzeButton.setText("اكتشاف النصوص العربية والإنجليزية");
        root.addView(analyzeButton);

        saveResultButton = new Button(this);
        saveResultButton.setText("حفظ الصورة المعدلة");
        root.addView(saveResultButton);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, pp);

        status = text("اختر صورة ثم اضغط اكتشاف النصوص", 15);
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        TextView detectedTitle = text("النصوص المكتشفة", 19);
        detectedTitle.setPadding(0, dp(8), 0, dp(5));
        root.addView(detectedTitle);

        blocksContainer = new LinearLayout(this);
        blocksContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(blocksContainer);

        pick.setOnClickListener(v -> chooseImage());
        analyzeButton.setOnClickListener(v -> startDetect());
        saveResultButton.setOnClickListener(v -> saveResult());

        setContentView(scroll);

        registerStageReceiver();
        loadPreview();
        loadBlocks();
        refreshButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        serverBase = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SERVER, DEFAULT_SERVER);
        loadPreview();
        loadBlocks();
        refreshButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }

    private void registerStageReceiver() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(AiProcessService.ACTION_DONE);
        filter.addAction(AiProcessService.ACTION_FAILED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }

        receiverRegistered = true;
    }

    private void chooseImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, PICK_OCR_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_OCR_IMAGE ||
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }

        try {
            Uri uri = data.getData();

            copyUriToFile(
                    uri,
                    new File(getFilesDir(), AiProcessService.OCR_INPUT)
            );

            delete(AiProcessService.OCR_BLOCKS);
            delete(AiProcessService.OCR_RESULT);

            blocks = new JSONArray();
            blocksContainer.removeAllViews();
            imagePreview.setBlocks(blocks);

            loadPreview();
            refreshButtons();

            status.setText("الصورة جاهزة — اضغط اكتشاف النصوص");

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "تعذر قراءة الصورة: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void startDetect() {
        if (!hasInput()) {
            Toast.makeText(this, "اختر صورة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(this, AiProcessService.class);
        i.putExtra(AiProcessService.EXTRA_STAGE, AiProcessService.OCR_DETECT);
        i.putExtra(AiProcessService.EXTRA_SERVER, serverBase);

        progress.setVisibility(View.VISIBLE);
        status.setText("جاري اكتشاف كل النصوص وتحليل اللغة والحجم واللون...");
        analyzeButton.setEnabled(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void showEditDialog(JSONObject block) {
        JSONObject bbox = block.optJSONObject("bbox");
        if (bbox == null) return;

        imagePreview.selectBlock(block.optString("id"));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), 0);

        TextView info = new TextView(this);
        info.setText(
                "النص الأصلي: " + block.optString("text") +
                "\nاللغة: " + block.optString("language") +
                " | الأرقام: " + block.optString("number_type") +
                "\nالحجم: " + block.optInt("font_size") +
                " | اللون: " + block.optString("text_color") +
                " | السماكة: " + block.optString("font_weight") +
                "\nالثقة: " + Math.round(block.optDouble("confidence") * 100) + "%"
        );
        info.setTextSize(15);
        box.addView(info);

        EditText input = new EditText(this);
        input.setText(block.optString("text"));
        input.setSelectAllOnFocus(true);
        input.setHint("اكتب النص البديل");
        box.addView(input);

        EditText sizeInput = new EditText(this);
        sizeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        sizeInput.setText(String.valueOf(block.optInt("font_size", 24)));
        sizeInput.setHint("حجم الخط");
        box.addView(sizeInput);

        EditText colorInput = new EditText(this);
        colorInput.setSingleLine(true);
        colorInput.setText(block.optString("text_color", "#000000"));
        colorInput.setHint("#000000");
        box.addView(colorInput);

        new AlertDialog.Builder(this)
                .setTitle("استبدال النص")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("استبدال", (dialog, which) -> {
                    int requestedSize = block.optInt("font_size", 24);

                    try {
                        requestedSize = Integer.parseInt(sizeInput.getText().toString().trim());
                    } catch (Exception ignored) {
                    }

                    startReplace(
                            block,
                            input.getText().toString(),
                            requestedSize,
                            colorInput.getText().toString().trim()
                    );
                })
                .show();
    }

    private void startReplace(
            JSONObject block,
            String newText,
            int requestedFontSize,
            String requestedColor
    ) {
        JSONObject bbox = block.optJSONObject("bbox");
        if (bbox == null) return;

        Intent i = new Intent(this, AiProcessService.class);
        i.putExtra(AiProcessService.EXTRA_STAGE, AiProcessService.OCR_REPLACE);
        i.putExtra(AiProcessService.EXTRA_SERVER, serverBase);
        i.putExtra(AiProcessService.EXTRA_X, bbox.optInt("x"));
        i.putExtra(AiProcessService.EXTRA_Y, bbox.optInt("y"));
        i.putExtra(AiProcessService.EXTRA_W, bbox.optInt("w"));
        i.putExtra(AiProcessService.EXTRA_H, bbox.optInt("h"));
        i.putExtra(AiProcessService.EXTRA_NEW_TEXT, newText);
        i.putExtra(
                AiProcessService.EXTRA_FONT_SIZE,
                Math.max(8, requestedFontSize)
        );

        if (requestedColor == null || requestedColor.trim().isEmpty()) {
            requestedColor = block.optString("text_color", "#000000");
        }

        i.putExtra(AiProcessService.EXTRA_TEXT_COLOR, requestedColor);
        i.putExtra(AiProcessService.EXTRA_DIRECTION, block.optString("direction", "auto"));
        i.putExtra(AiProcessService.EXTRA_LANGUAGE, block.optString("language", "unknown"));
        i.putExtra(AiProcessService.EXTRA_FONT_WEIGHT, block.optString("font_weight", "normal"));

        progress.setVisibility(View.VISIBLE);
        status.setText("جاري إزالة النص القديم وكتابة النص الجديد...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void loadBlocks() {
        File file = new File(getFilesDir(), AiProcessService.OCR_BLOCKS);

        if (!file.exists() || file.length() == 0) {
            blocks = new JSONArray();
            blocksContainer.removeAllViews();
            imagePreview.setBlocks(blocks);
            return;
        }

        try {
            String json = new String(readFile(file), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            blocks = root.optJSONArray("blocks");

            if (blocks == null) {
                blocks = new JSONArray();
            }

            renderBlocks();
            imagePreview.setBlocks(blocks);

        } catch (Exception e) {
            status.setText("تعذر قراءة نتائج OCR: " + e.getMessage());
        }
    }

    private void renderBlocks() {
        blocksContainer.removeAllViews();

        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;

            Button item = new Button(this);

            String language = block.optString("language");
            String languageName;
            if ("ar".equals(language)) {
                languageName = "عربي";
            } else if ("en".equals(language)) {
                languageName = "English";
            } else if ("mixed".equals(language)) {
                languageName = "مختلط";
            } else {
                languageName = "غير محدد";
            }

            String numberType = block.optString("number_type");
            String numberName;
            if ("arabic_digits".equals(numberType)) {
                numberName = "أرقام عربية";
            } else if ("english_digits".equals(numberType)) {
                numberName = "أرقام إنجليزية";
            } else if ("mixed_digits".equals(numberType)) {
                numberName = "أرقام مختلطة";
            } else {
                numberName = "بدون أرقام";
            }

            item.setText(
                    (i + 1) + ") " + block.optString("text") +
                    "\n" + languageName + " | " + numberName +
                    " | حجم " + block.optInt("font_size") +
                    " | " + block.optString("text_color")
            );
            item.setAllCaps(false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

            JSONObject captured = block;
            item.setOnClickListener(v -> showEditDialog(captured));

            blocksContainer.addView(item);
        }

        if (blocks.length() == 0 && hasInput()) {
            TextView empty = text("لم يتم اكتشاف نصوص حتى الآن.", 15);
            blocksContainer.addView(empty);
        }
    }

    private void loadPreview() {
        File result = new File(getFilesDir(), AiProcessService.OCR_RESULT);
        File input = new File(getFilesDir(), AiProcessService.OCR_INPUT);

        File source = result.exists() && result.length() > 0 ? result : input;

        if (!source.exists() || source.length() == 0) {
            imagePreview.setImageDrawable(null);
            return;
        }

        try {
            imagePreview.setImageBitmap(loadBitmapOriented(source));
        } catch (Exception e) {
            Bitmap bmp = BitmapFactory.decodeFile(source.getAbsolutePath());
            imagePreview.setImageBitmap(bmp);
        }
    }

    private Bitmap loadBitmapOriented(File file) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) throw new Exception("الصورة غير صالحة");

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        int orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
        );

        int degrees = 0;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;

        if (degrees == 0) return bitmap;

        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);

        return Bitmap.createBitmap(
                bitmap,
                0, 0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );
    }

    private void refreshButtons() {
        analyzeButton.setEnabled(hasInput());
        saveResultButton.setEnabled(hasResult());
    }

    private boolean hasInput() {
        File f = new File(getFilesDir(), AiProcessService.OCR_INPUT);
        return f.exists() && f.length() > 0;
    }

    private boolean hasResult() {
        File f = new File(getFilesDir(), AiProcessService.OCR_RESULT);
        return f.exists() && f.length() > 0;
    }

    private void saveResult() {
        File source = new File(getFilesDir(), AiProcessService.OCR_RESULT);

        if (!source.exists() || source.length() == 0) {
            Toast.makeText(this, "لا توجد صورة معدلة للحفظ", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "UNB_text_edit_" + System.currentTimeMillis() + ".png"
            );
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
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

            Toast.makeText(this, "تم حفظ الصورة المعدلة", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "فشل الحفظ: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
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

    private byte[] readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int n;

            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }

            return out.toByteArray();
        }
    }

    private void delete(String name) {
        File f = new File(getFilesDir(), name);
        if (f.exists()) f.delete();
    }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
