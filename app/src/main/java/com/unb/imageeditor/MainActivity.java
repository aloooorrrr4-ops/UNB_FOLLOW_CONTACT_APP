package com.unb.imageeditor;

import android.app.Activity;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.File;
import android.os.Build;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.Manifest;
import android.content.ContentValues;
import org.json.JSONObject;
import org.json.JSONArray;
import android.content.ClipboardManager;
import android.content.ClipData;
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
import android.view.WindowInsets;
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
    private String serverBase = "http://91.98.126.167:18083";
    private String chatEditPath = "/api/chat/edit";
    private boolean maintenance = false;
    private String maintenanceMessage = "الخدمة تحت الصيانة حالياً";
    private int maxUploadMb = 15;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout messages;
    private ScrollView scroll;
    private EditText promptInput;
    private Button sendButton;
    private ImageButton attachButton;
    private ProgressBar progress;
    private TextView titleView;
    private TextView subtitleView;

    private byte[] currentImage;
    private String currentFileName = "image.png";
    private boolean busy = false;
    private boolean receiverRegistered = false;
    private long shownResultModified = 0L;
    private long shownTextModified = 0L;
    private int pendingStage = 0;

    private final Runnable resultWatcher = new Runnable() {
        @Override
        public void run() {
            if (!busy || pendingStage == 0) return;

            if (pendingStage == AiProcessService.CHAT_EXTRACT) {
                loadFinishedTextResult(true);
            } else if (pendingStage == AiProcessService.CHAT_EDIT) {
                loadFinishedChatResult(true);
            }

            if (busy && pendingStage != 0) {
                main.postDelayed(this, 1000);
            }
        }
    };

    private final BroadcastReceiver editReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int stage = intent.getIntExtra(AiProcessService.EXTRA_STAGE, 0);

            if (stage != AiProcessService.CHAT_EDIT &&
                    stage != AiProcessService.CHAT_EXTRACT) {
                return;
            }

            if (AiProcessService.ACTION_DONE.equals(intent.getAction())) {
                if (stage == AiProcessService.CHAT_EXTRACT) {
                    loadFinishedTextResult(true);
                } else {
                    loadFinishedChatResult(true);
                }
            } else if (AiProcessService.ACTION_FAILED.equals(intent.getAction())) {
                String msg = intent.getStringExtra(AiProcessService.EXTRA_MESSAGE);
                removeLastAssistantWaiting();
                addAssistantText("تعذر تنفيذ الطلب:\n" + (msg == null ? "خطأ غير معروف" : cleanError(msg)));
                setBusy(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(11, 13, 16));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 13, 16));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Math.max(insets.getSystemWindowInsetBottom(), dp(8));
            int top = Math.max(insets.getSystemWindowInsetTop(), 0);
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(18), dp(18), dp(12));

        titleView = text("محرر الصور الذكي", 23, Color.WHITE);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.RIGHT);
        header.addView(titleView);

        subtitleView = text("ارفع صورة، ثم اكتب التعديل كأنك تتحدث معي", 14, Color.rgb(164, 171, 181));
        subtitleView.setGravity(Gravity.RIGHT);
        subtitleView.setPadding(0, dp(4), 0, 0);
        header.addView(subtitleView);

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

        addAssistantText("أرسل صورة ثم قل: «استخرج النصوص».\nبعدها أرسل التعديلات كلمة مقابل كلمة، مثل:\nعامل = مهندس");

        LinearLayout composerWrap = new LinearLayout(this);
        composerWrap.setOrientation(LinearLayout.VERTICAL);
        composerWrap.setPadding(dp(10), dp(8), dp(10), dp(10));
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
        sendButton.setGravity(Gravity.CENTER);
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

        loadRemoteConfig();
        registerEditReceiver();
        requestNotificationPermission();
        restoreWorkingImage();
        loadFinishedChatResult(false);
        loadFinishedTextResult(false);

        promptInput.setOnEditorActionListener((v, actionId, event) -> {
            if (promptInput.getText().toString().trim().length() > 0) {
                sendEdit();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreWorkingImage();
        loadFinishedChatResult(false);
        loadFinishedTextResult(false);
    }

    private void registerEditReceiver() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(AiProcessService.ACTION_DONE);
        filter.addAction(AiProcessService.ACTION_FAILED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(editReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(editReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    2001
            );
        }
    }

    private void restoreWorkingImage() {
        if (currentImage != null && currentImage.length > 0) return;

        File result = new File(getFilesDir(), AiProcessService.CHAT_RESULT);
        File input = new File(getFilesDir(), AiProcessService.CHAT_INPUT);
        File source = result.exists() && result.length() > 0 ? result : input;

        if (!source.exists() || source.length() == 0) return;

        try {
            currentImage = readFileBytes(source);
            currentFileName = source.getName();
        } catch (Exception ignored) {
        }
    }

    private void loadFinishedChatResult(boolean fromBroadcast) {
        File result = new File(getFilesDir(), AiProcessService.CHAT_RESULT);

        if (!result.exists() || result.length() == 0) return;
        if (result.lastModified() == shownResultModified) return;

        try {
            byte[] bytes = readFileBytes(result);
            Bitmap test = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (test == null) {
                if (fromBroadcast || busy) {
                    removeLastAssistantWaiting();
                    addAssistantText("تمت المعالجة لكن الخادم لم يرجع صورة صالحة.");
                    setBusy(false);
                }
                return;
            }

            currentImage = bytes;
            currentFileName = "edited_" + System.currentTimeMillis() + ".png";
            shownResultModified = result.lastModified();

            if (fromBroadcast) {
                removeLastAssistantWaiting();
            }

            addAssistantImage(bytes, "تم تنفيذ التعديل");
            setBusy(false);
            scrollBottom();

        } catch (Exception e) {
            if (fromBroadcast) {
                removeLastAssistantWaiting();
                addAssistantText("تمت المعالجة لكن تعذر فتح النتيجة:\n" + e.getMessage());
                setBusy(false);
            }
        }
    }

    private byte[] readFileBytes(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in);
        }
    }

    private void loadFinishedTextResult(boolean fromBroadcast) {
        File result = new File(getFilesDir(), AiProcessService.CHAT_TEXT_RESULT);

        if (!result.exists() || result.length() == 0) return;
        if (result.lastModified() == shownTextModified) return;

        try {
            String json = new String(readFileBytes(result), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");

            if (fromBroadcast) {
                removeLastAssistantWaiting();
            }

            if (items == null || items.length() == 0) {
                addAssistantText("لم أجد نصوصاً واضحة داخل الصورة.");
                shownTextModified = result.lastModified();
                setBusy(false);
                return;
            }

            StringBuilder display = new StringBuilder();
            StringBuilder template = new StringBuilder();

            display.append("النصوص المستخرجة — اضغط مطولاً للنسخ:\n\n");

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;

                String original = item.optString("original_text", "").trim();
                if (original.isEmpty()) continue;

                display.append(i + 1)
                        .append(") الأصلي: ")
                        .append(original)
                        .append("\n   البديل: ")
                        .append("\n\n");

                template.append(original).append(" = ").append("\n");
            }

            display.append("أرسل التعديل بهذا الشكل:\n")
                    .append(template);

            addExtractedTextsCard(display.toString(), template.toString());
            shownTextModified = result.lastModified();
            setBusy(false);
            scrollBottom();

        } catch (Exception e) {
            if (fromBroadcast) {
                removeLastAssistantWaiting();
                addAssistantText("تعذر قراءة النصوص المستخرجة:\n" + e.getMessage());
                setBusy(false);
            }
        }
    }

    private void addExtractedTextsCard(String displayText, String template) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.LEFT);
        row.setPadding(0, dp(5), 0, dp(5));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(bubble(Color.rgb(35, 39, 45), 18));

        TextView tv = text(displayText, 15, Color.WHITE);
        tv.setGravity(Gravity.RIGHT);
        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(4), dp(4), dp(4), dp(8));
        card.addView(tv);

        Button copy = new Button(this);
        copy.setText("نسخ قالب التعديل");
        copy.setTextColor(Color.WHITE);
        copy.setAllCaps(false);
        copy.setBackground(bubble(Color.rgb(60, 66, 75), 14));
        card.addView(copy, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ));

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("UNB OCR", template));
                Toast.makeText(this, "تم نسخ النصوص. اكتب البديل بعد علامة =", Toast.LENGTH_LONG).show();
            }
        });

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.88f),
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        row.addView(card, rp);
        messages.addView(row);
    }

    private boolean isExtractPrompt(String prompt) {
        if (prompt == null) return false;
        String p = prompt.trim();
        return p.contains("استخرج النصوص") ||
                p.contains("استخراج النصوص") ||
                p.contains("استخرج الكلام") ||
                p.contains("هات النصوص") ||
                p.contains("اعرض النصوص") ||
                p.contains("طلع النصوص") ||
                p.contains("النصوص القابلة للتعديل") ||
                p.contains("النصوص القابله للتعديل");
    }

    private void loadRemoteConfig() {
        executor.execute(() -> {
            try {
                AppRemoteConfig cfg = AppRemoteConfig.fetch();

                serverBase = cfg.serverBase;
                chatEditPath = cfg.chatEditPath;
                maintenance = cfg.maintenance;
                maintenanceMessage = cfg.maintenanceMessage;
                maxUploadMb = cfg.maxUploadMb;

                main.post(() -> {
                    titleView.setText(cfg.appName);
                    subtitleView.setText(cfg.subtitle);

                    if (cfg.maintenance) {
                        addAssistantText(cfg.maintenanceMessage);
                        sendButton.setEnabled(false);
                    } else {
                        sendButton.setEnabled(true);
                    }
                });
            } catch (Exception ignored) {
                // Keep built-in defaults when remote control config is unavailable.
            }
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

        if (maintenance) {
            Toast.makeText(this, maintenanceMessage, Toast.LENGTH_LONG).show();
            addAssistantText(maintenanceMessage);
            return;
        }

        String prompt = promptInput.getText().toString().trim();

        if (currentImage == null || currentImage.length == 0) {
            Toast.makeText(this, "ارفع صورة أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        if (prompt.isEmpty()) {
            Toast.makeText(this, "اكتب التعديل المطلوب", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentImage.length > maxUploadMb * 1024L * 1024L) {
            Toast.makeText(this, "حجم الصورة أكبر من الحد المسموح: " + maxUploadMb + " MB", Toast.LENGTH_LONG).show();
            return;
        }

        boolean extractMode = isExtractPrompt(prompt);

        promptInput.setText("");
        hideKeyboard();
        addUserText(prompt);
        setBusy(true);
        addAssistantText(
                extractMode
                        ? "جاري استخراج النصوص..."
                        : "جاري تنفيذ التعديل... يمكنك الخروج من التطبيق وسيستمر العمل."
        );

        try {
            File input = new File(getFilesDir(), AiProcessService.CHAT_INPUT);
            File imageResult = new File(getFilesDir(), AiProcessService.CHAT_RESULT);
            File textResult = new File(getFilesDir(), AiProcessService.CHAT_TEXT_RESULT);

            try (FileOutputStream out = new FileOutputStream(input, false)) {
                out.write(currentImage);
                out.flush();
            }

            if (extractMode) {
                if (textResult.exists()) textResult.delete();
                shownTextModified = 0L;
            } else {
                if (imageResult.exists()) imageResult.delete();
                shownResultModified = 0L;
            }

            Intent service = new Intent(this, AiProcessService.class);
            pendingStage = extractMode ? AiProcessService.CHAT_EXTRACT : AiProcessService.CHAT_EDIT;
            service.putExtra(
                    AiProcessService.EXTRA_STAGE,
                    pendingStage
            );
            service.putExtra(AiProcessService.EXTRA_SERVER, serverBase);

            if (!extractMode) {
                service.putExtra(AiProcessService.EXTRA_CHAT_PATH, chatEditPath);
                service.putExtra(AiProcessService.EXTRA_PROMPT, prompt);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }

            main.removeCallbacks(resultWatcher);
            main.postDelayed(resultWatcher, 1000);

            Toast.makeText(
                    this,
                    extractMode
                            ? "بدأ استخراج النصوص"
                            : "بدأ التعديل — يمكنك الخروج من التطبيق وسيستمر التنفيذ",
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            removeLastAssistantWaiting();
            addAssistantText("تعذر بدء المعالجة:\n" + e.getMessage());
            setBusy(false);
        }
    }

    private HttpResult postChatEdit(byte[] image, String fileName, String prompt) throws Exception {
        String boundary = "----UNB" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(serverBase + chatEditPath).openConnection();

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
        if (!value) {
            pendingStage = 0;
            main.removeCallbacks(resultWatcher);
        }
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
        if (receiverRegistered) {
            unregisterReceiver(editReceiver);
            receiverRegistered = false;
        }
        main.removeCallbacks(resultWatcher);
        executor.shutdownNow();
        super.onDestroy();
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
