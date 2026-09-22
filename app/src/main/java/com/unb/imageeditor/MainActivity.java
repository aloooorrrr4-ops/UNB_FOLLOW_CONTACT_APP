package com.unb.imageeditor;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PICK_IMAGE = 1001;
    private static final String SERVER = "http://91.98.126.167:18083";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout messages;
    private ScrollView scroll;
    private EditText promptInput;
    private Button sendButton;
    private ImageButton attachButton;
    private ProgressBar progress;

    private byte[] currentImage;
    private String currentFileName = "image.png";
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(11, 13, 16));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 13, 16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(18), dp(18), dp(12));

        TextView title = text("محرر الصور الذكي", 23, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.RIGHT);
        header.addView(title);

        TextView subtitle = text("ارفع صورة، ثم اكتب التعديل كأنك تتحدث معي", 14, Color.rgb(164, 171, 181));
        subtitle.setGravity(Gravity.RIGHT);
        subtitle.setPadding(0, dp(4), 0, 0);
        header.addView(subtitle);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(35, 39, 45));
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(12), dp(14), dp(12), dp(16));
        scroll.addView(messages);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        );
        root.addView(scroll, scrollParams);

        addAssistantText("أرسل صورة ثم اكتب ما تريد تعديله. مثال:\n«بدل اختبار جيمب إلى محمد فاضل»");

        LinearLayout composerWrap = new LinearLayout(this);
        composerWrap.setOrientation(LinearLayout.VERTICAL);
        composerWrap.setPadding(dp(10), dp(8), dp(10), dp(12));
        composerWrap.setBackgroundColor(Color.rgb(16, 19, 23));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.bottomMargin = dp(6);
        composerWrap.addView(progress, progressParams);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);

        attachButton = new ImageButton(this);
        attachButton.setImageResource(android.R.drawable.ic_menu_gallery);
        attachButton.setColorFilter(Color.WHITE);
        attachButton.setBackground(bubble(Color.rgb(38, 43, 50), 18));
        attachButton.setContentDescription("اختيار صورة");
        composer.addView(attachButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        promptInput = new EditText(this);
        promptInput.setHint("اكتب التعديل...");
        promptInput.setHintTextColor(Color.rgb(130, 138, 148));
        promptInput.setTextColor(Color.WHITE);
        promptInput.setTextSize(16);
        promptInput.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        promptInput.setTextDirection(View.TEXT_DIRECTION_RTL);
        promptInput.setSingleLine(false);
        promptInput.setMaxLines(4);
        promptInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        promptInput.setBackground(bubble(Color.rgb(30, 34, 40), 20));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputParams.leftMargin = dp(8);
        inputParams.rightMargin = dp(8);
        composer.addView(promptInput, inputParams);

        sendButton = new Button(this);
        sendButton.setText("إرسال");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(14);
        sendButton.setAllCaps(false);
        sendButton.setBackground(bubble(Color.rgb(16, 163, 127), 18));
        composer.addView(sendButton, new LinearLayout.LayoutParams(dp(76), dp(48)));

        composerWrap.addView(composer);
        root.addView(composerWrap);

        setContentView(root);

        attachButton.setOnClickListener(v -> chooseImage());
        sendButton.setOnClickListener(v -> sendEdit());

        promptInput.setOnEditorActionListener((v, actionId, event) -> {
            if (promptInput.getText().toString().trim().length() > 0) {
                sendEdit();
                return true;
            }
            return false;
        });
    }

    private void chooseImage() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        executor.execute(() -> {
            try {
                byte[] bytes = readAll(uri);
                if (bytes.length == 0) throw new Exception("الصورة فارغة");

                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) throw new Exception("صيغة الصورة غير مدعومة");

                currentImage = bytes;
                currentFileName = "upload_" + System.currentTimeMillis() + ".png";

                main.post(() -> {
                    addUserImage(bytes, "تم رفع الصورة");
                    scrollBottom();
                    Toast.makeText(this, "الصورة جاهزة للتعديل", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "تعذر فتح الصورة: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sendEdit() {
        if (busy) return;

        String prompt = promptInput.getText().toString().trim();

        if (currentImage == null || currentImage.length == 0) {
            Toast.makeText(this, "ارفع صورة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        if (prompt.isEmpty()) {
            Toast.makeText(this, "اكتب التعديل المطلوب", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] source = currentImage.clone();

        promptInput.setText("");
        hideKeyboard();
        addUserText(prompt);
        setBusy(true);
        addAssistantText("جاري تنفيذ التعديل...");

        executor.execute(() -> {
            try {
                HttpResult result = postChatEdit(source, currentFileName, prompt);

                if (result.code >= 200 && result.code < 300 && result.body.length > 0) {
                    Bitmap test = BitmapFactory.decodeByteArray(result.body, 0, result.body.length);
                    if (test == null) throw new Exception("الخادم لم يُرجع صورة صالحة");

                    currentImage = result.body;
                    currentFileName = "edited_" + System.currentTimeMillis() + ".png";

                    main.post(() -> {
                        removeLastAssistantWaiting();
                        addAssistantImage(result.body, "تم تنفيذ التعديل");
                        setBusy(false);
                        scrollBottom();
                    });
                } else {
                    String error = new String(result.body, StandardCharsets.UTF_8);
                    main.post(() -> {
                        removeLastAssistantWaiting();
                        addAssistantText("تعذر تنفيذ الطلب:\n" + cleanError(error));
                        setBusy(false);
                        scrollBottom();
                    });
                }
            } catch (Exception e) {
                main.post(() -> {
                    removeLastAssistantWaiting();
                    addAssistantText("حدث خطأ في الاتصال بالخادم:\n" + e.getMessage());
                    setBusy(false);
                    scrollBottom();
                });
            }
        });
    }

    private HttpResult postChatEdit(byte[] image, String fileName, String prompt) throws Exception {
        String boundary = "----UNB" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(SERVER + "/api/chat/edit").openConnection();

        conn.setConnectTimeout(30000);
        conn.setReadTimeout(240000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "image/png, application/json");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = conn.getOutputStream()) {
            writeTextPart(out, boundary, "prompt", prompt);

            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"image\"; filename=\"" + safeFileName(fileName) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(image);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        byte[] body = stream == null ? new byte[0] : readStream(stream);
        conn.disconnect();
        return new HttpResult(code, body);
    }

    private void writeTextPart(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void addUserText(String value) {
        messages.addView(textBubble(value, true));
        scrollBottom();
    }

    private void addAssistantText(String value) {
        messages.addView(textBubble(value, false));
        scrollBottom();
    }

    private void addUserImage(byte[] bytes, String caption) {
        messages.addView(imageBubble(bytes, caption, true, false));
    }

    private void addAssistantImage(byte[] bytes, String caption) {
        messages.addView(imageBubble(bytes, caption, false, true));
    }

    private View textBubble(String value, boolean user) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView tv = text(value, 16, Color.WHITE);
        tv.setGravity(Gravity.RIGHT);
        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        tv.setPadding(dp(14), dp(11), dp(14), dp(11));
        tv.setBackground(bubble(
                user ? Color.rgb(16, 119, 96) : Color.rgb(35, 39, 45),
                18
        ));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        row.addView(tv, p);
        return row;
    }

    private View imageBubble(byte[] bytes, String caption, boolean user, boolean withSave) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.RIGHT : Gravity.LEFT);
        row.setPadding(0, dp(5), 0, dp(5));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(bubble(
                user ? Color.rgb(20, 94, 79) : Color.rgb(35, 39, 45),
                18
        ));

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        image.setImageBitmap(bmp);

        int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        card.addView(image, new LinearLayout.LayoutParams(maxWidth, dp(290)));

        TextView cap = text(caption, 14, Color.rgb(218, 222, 227));
        cap.setGravity(Gravity.RIGHT);
        cap.setPadding(dp(4), dp(8), dp(4), dp(4));
        card.addView(cap);

        if (withSave) {
            Button save = new Button(this);
            save.setText("حفظ الصورة");
            save.setTextColor(Color.WHITE);
            save.setAllCaps(false);
            save.setTextSize(14);
            save.setBackground(bubble(Color.rgb(60, 66, 75), 14));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            );
            sp.topMargin = dp(6);
            card.addView(save, sp);
            save.setOnClickListener(v -> saveImage(bytes));
        }

        row.addView(card);
        return row;
    }

    private void saveImage(byte[] bytes) {
        executor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "UNB_AI_" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UNB AI");
                }

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("تعذر إنشاء الملف");

                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("تعذر فتح الملف");
                    out.write(bytes);
                    out.flush();
                }

                main.post(() -> Toast.makeText(this, "تم حفظ الصورة في الاستوديو", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "فشل الحفظ: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
        attachButton.setEnabled(!value);
        promptInput.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        sendButton.setAlpha(value ? 0.5f : 1f);
        attachButton.setAlpha(value ? 0.5f : 1f);
    }

    private void removeLastAssistantWaiting() {
        int count = messages.getChildCount();
        if (count > 0) {
            View last = messages.getChildAt(count - 1);
            messages.removeView(last);
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("تعذر قراءة الملف");
            return readStream(in);
        }
    }

    private byte[] readStream(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String cleanError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "خطأ غير معروف";
        String s = raw.trim();
        int p = s.indexOf("\"detail\":");
        if (p >= 0) {
            int q1 = s.indexOf('"', p + 9);
            int q2 = q1 >= 0 ? s.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) return s.substring(q1 + 1, q2);
        }
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    private String safeFileName(String name) {
        return name == null ? "image.png" : name.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void scrollBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView text(String value, int size, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(size);
        tv.setTextColor(color);
        return tv;
    }

    private GradientDrawable bubble(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private static class HttpResult {
        final int code;
        final byte[] body;

        HttpResult(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }
}
