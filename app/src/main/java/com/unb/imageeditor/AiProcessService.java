package com.unb.imageeditor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class AiProcessService extends Service {
    public static final String ACTION_DONE = "com.unb.imageeditor.STAGE_DONE";
    public static final String ACTION_FAILED = "com.unb.imageeditor.STAGE_FAILED";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_MESSAGE = "message";

    public static final String EXTRA_X = "x";
    public static final String EXTRA_Y = "y";
    public static final String EXTRA_W = "w";
    public static final String EXTRA_H = "h";
    public static final String EXTRA_NEW_TEXT = "new_text";
    public static final String EXTRA_FONT_SIZE = "font_size";
    public static final String EXTRA_TEXT_COLOR = "text_color";
    public static final String EXTRA_DIRECTION = "direction";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String EXTRA_FONT_WEIGHT = "font_weight";

    public static final int OCR_DETECT = 5;
    public static final int OCR_REPLACE = 6;

    public static final String FIRST = "first_input.img";
    public static final String SECOND = "second_input.img";
    public static final String STAGE1 = "stage1.png";
    public static final String STAGE2 = "stage2.jpg";
    public static final String STAGE3 = "stage3.png";
    public static final String STAGE4 = "stage4.jpg";

    public static final String OCR_INPUT = "ocr_input.img";
    public static final String OCR_BLOCKS = "ocr_blocks.json";
    public static final String OCR_RESULT = "ocr_result.png";

    private static final String CHANNEL_ID = "unb_ai_processing";
    private static final int NOTIFICATION_ID = 4201;
    private volatile boolean working = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || working) {
            return START_NOT_STICKY;
        }

        final int stage = intent.getIntExtra(EXTRA_STAGE, 0);
        final String server = intent.getStringExtra(EXTRA_SERVER);

        if (stage < 1 || stage > OCR_REPLACE || server == null || server.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        working = true;
        startForeground(
                NOTIFICATION_ID,
                buildNotification(notificationText(stage))
        );

        new Thread(() -> {
            try {
                runStage(stage, normalizeServer(server), intent);
                sendResult(ACTION_DONE, stage, doneText(stage));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "خطأ غير معروف" : e.getMessage();
                sendResult(ACTION_FAILED, stage, msg);
            } finally {
                working = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        }, "UNB-AI-Stage-" + stage).start();

        return START_NOT_STICKY;
    }

    private void runStage(int stage, String server, Intent intent) throws Exception {
        File dir = getFilesDir();

        if (stage == 1) {
            byte[] first = readFile(required(dir, FIRST));
            byte[] result = postMultipart(
                    server + "/api/stage1/cut-first",
                    Part.file("file", "first.jpg", "image/jpeg", first)
            );
            writeFile(new File(dir, STAGE1), result);
            return;
        }

        if (stage == 2) {
            byte[] first = readFile(required(dir, FIRST));
            byte[] cutout = readFile(required(dir, STAGE1));
            byte[] result = postMultipart(
                    server + "/api/stage2/restore-background",
                    Part.file("file", "first.jpg", "image/jpeg", first),
                    Part.file("cutout", "stage1.png", "image/png", cutout)
            );
            writeFile(new File(dir, STAGE2), result);
            return;
        }

        if (stage == 3) {
            byte[] second = readFile(required(dir, SECOND));
            byte[] result = postMultipart(
                    server + "/api/stage3/cut-second",
                    Part.file("file", "second.jpg", "image/jpeg", second)
            );
            writeFile(new File(dir, STAGE3), result);
            return;
        }

        if (stage == 4) {
            byte[] first = readFile(required(dir, FIRST));
            byte[] background = readFile(required(dir, STAGE2));
            byte[] subject = readFile(required(dir, STAGE3));
            byte[] targetCutout = readFile(required(dir, STAGE1));

            byte[] result = postMultipart(
                    server + "/api/stage4/composite",
                    Part.file("target", "first.jpg", "image/jpeg", first),
                    Part.file("background", "background.jpg", "image/jpeg", background),
                    Part.file("subject", "subject.png", "image/png", subject),
                    Part.file("target_cutout", "stage1.png", "image/png", targetCutout)
            );
            writeFile(new File(dir, STAGE4), result);
            return;
        }

        if (stage == OCR_DETECT) {
            File source = new File(dir, OCR_RESULT);
            if (!source.exists() || source.length() == 0) {
                source = required(dir, OCR_INPUT);
            }

            byte[] image = readFile(source);
            byte[] result = postMultipart(
                    server + "/api/ocr/detect",
                    Part.file("image", "ocr.jpg", "image/jpeg", image)
            );
            writeFile(new File(dir, OCR_BLOCKS), result);
            return;
        }

        File source = new File(dir, OCR_RESULT);
        if (!source.exists() || source.length() == 0) {
            source = required(dir, OCR_INPUT);
        }

        byte[] image = readFile(source);

        int x = intent.getIntExtra(EXTRA_X, 0);
        int y = intent.getIntExtra(EXTRA_Y, 0);
        int w = intent.getIntExtra(EXTRA_W, 0);
        int h = intent.getIntExtra(EXTRA_H, 0);
        int fontSize = intent.getIntExtra(EXTRA_FONT_SIZE, 24);
        String newText = safe(intent.getStringExtra(EXTRA_NEW_TEXT));
        String textColor = defaultString(intent.getStringExtra(EXTRA_TEXT_COLOR), "#000000");
        String direction = defaultString(intent.getStringExtra(EXTRA_DIRECTION), "auto");
        String language = defaultString(intent.getStringExtra(EXTRA_LANGUAGE), "unknown");
        String fontWeight = defaultString(intent.getStringExtra(EXTRA_FONT_WEIGHT), "normal");

        byte[] result = postMultipart(
                server + "/api/ocr/replace",
                Part.file("image", "ocr.png", "image/png", image),
                Part.text("x", String.valueOf(x)),
                Part.text("y", String.valueOf(y)),
                Part.text("w", String.valueOf(w)),
                Part.text("h", String.valueOf(h)),
                Part.text("new_text", newText),
                Part.text("font_size", String.valueOf(fontSize)),
                Part.text("text_color", textColor),
                Part.text("direction", direction),
                Part.text("language", language),
                Part.text("font_weight", fontWeight)
        );
        writeFile(new File(dir, OCR_RESULT), result);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String notificationText(int stage) {
        if (stage == OCR_DETECT) return "جاري اكتشاف النصوص العربية والإنجليزية";
        if (stage == OCR_REPLACE) return "جاري استبدال النص داخل الصورة";
        return "جاري تنفيذ المرحلة " + stage + " بالذكاء الاصطناعي";
    }

    private String doneText(int stage) {
        if (stage == OCR_DETECT) return "تم اكتشاف النصوص";
        if (stage == OCR_REPLACE) return "تم استبدال النص";
        return "تمت المرحلة " + stage + " بنجاح";
    }

    private File required(File dir, String name) throws Exception {
        File f = new File(dir, name);
        if (!f.exists() || f.length() == 0) {
            throw new Exception("ملف المرحلة المطلوبة غير موجود: " + name);
        }
        return f;
    }

    private String normalizeServer(String s) {
        String value = s.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void sendResult(String action, int stage, String message) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STAGE, stage);
        i.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(i);
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
                    StringBuilder head = new StringBuilder();
                    head.append("--").append(boundary).append("\r\n");
                    head.append("Content-Disposition: form-data; name=\"")
                            .append(p.name).append("\"");

                    if (p.filename != null) {
                        head.append("; filename=\"").append(p.filename).append("\"");
                    }

                    head.append("\r\n");

                    if (p.mime != null) {
                        head.append("Content-Type: ").append(p.mime).append("\r\n");
                    }

                    head.append("\r\n");

                    out.write(head.toString().getBytes(StandardCharsets.UTF_8));
                    out.write(p.bytes);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }

                out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();

            if (code < 200 || code >= 300) {
                InputStream err = c.getErrorStream();
                String body = "";
                if (err != null) {
                    body = new String(readAll(err), StandardCharsets.UTF_8);
                }
                throw new Exception("HTTP " + code + (body.isEmpty() ? "" : " — " + body));
            }

            return readAll(c.getInputStream());

        } finally {
            c.disconnect();
        }
    }

    private byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private void writeFile(File f, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(bytes);
            out.flush();
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
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

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "معالجة صور UNB",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("يبقي معالجة الذكاء الاصطناعي مستمرة عند الخروج من التطبيق");

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle("UNB Image Editor")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class Part {
        final String name;
        final String filename;
        final String mime;
        final byte[] bytes;

        private Part(String name, String filename, String mime, byte[] bytes) {
            this.name = name;
            this.filename = filename;
            this.mime = mime;
            this.bytes = bytes;
        }

        static Part file(String name, String filename, String mime, byte[] bytes) {
            return new Part(name, filename, mime, bytes);
        }

        static Part text(String name, String value) {
            return new Part(
                    name,
                    null,
                    "text/plain; charset=UTF-8",
                    value.getBytes(StandardCharsets.UTF_8)
            );
        }
    }
}
