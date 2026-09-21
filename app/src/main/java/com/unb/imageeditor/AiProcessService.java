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
import java.util.UUID;

public class AiProcessService extends Service {
    public static final String ACTION_DONE = "com.unb.imageeditor.STAGE_DONE";
    public static final String ACTION_FAILED = "com.unb.imageeditor.STAGE_FAILED";
    public static final String EXTRA_STAGE = "stage";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_MESSAGE = "message";

    public static final String FIRST = "first_input.img";
    public static final String SECOND = "second_input.img";
    public static final String STAGE1 = "stage1.png";
    public static final String STAGE2 = "stage2.jpg";
    public static final String STAGE3 = "stage3.png";
    public static final String STAGE4 = "stage4.jpg";

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

        if (stage < 1 || stage > 4 || server == null || server.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        working = true;
        startForeground(
                NOTIFICATION_ID,
                buildNotification("جاري تنفيذ المرحلة " + stage + " بالذكاء الاصطناعي")
        );

        new Thread(() -> {
            try {
                runStage(stage, normalizeServer(server));
                sendResult(ACTION_DONE, stage, "تمت المرحلة " + stage + " بنجاح");
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

    private void runStage(int stage, String server) throws Exception {
        File dir = getFilesDir();

        if (stage == 1) {
            byte[] first = readFile(required(dir, FIRST));
            byte[] result = postMultipart(
                    server + "/api/stage1/cut-first",
                    new Part("file", "first.jpg", "image/jpeg", first)
            );
            writeFile(new File(dir, STAGE1), result);
            return;
        }

        if (stage == 2) {
            byte[] first = readFile(required(dir, FIRST));
            byte[] cutout = readFile(required(dir, STAGE1));
            byte[] result = postMultipart(
                    server + "/api/stage2/restore-background",
                    new Part("file", "first.jpg", "image/jpeg", first),
                    new Part("cutout", "stage1.png", "image/png", cutout)
            );
            writeFile(new File(dir, STAGE2), result);
            return;
        }

        if (stage == 3) {
            byte[] second = readFile(required(dir, SECOND));
            byte[] result = postMultipart(
                    server + "/api/stage3/cut-second",
                    new Part("file", "second.jpg", "image/jpeg", second)
            );
            writeFile(new File(dir, STAGE3), result);
            return;
        }

        byte[] first = readFile(required(dir, FIRST));
        byte[] background = readFile(required(dir, STAGE2));
        byte[] subject = readFile(required(dir, STAGE3));
        byte[] targetCutout = readFile(required(dir, STAGE1));

        byte[] result = postMultipart(
                server + "/api/stage4/composite",
                new Part("target", "first.jpg", "image/jpeg", first),
                new Part("background", "background.jpg", "image/jpeg", background),
                new Part("subject", "subject.png", "image/png", subject),
                new Part("target_cutout", "stage1.png", "image/png", targetCutout)
        );
        writeFile(new File(dir, STAGE4), result);
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
                if (err != null) body = new String(readAll(err), "UTF-8");
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
            if (nm != null) nm.createNotificationChannel(channel);
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

        Part(String name, String filename, String mime, byte[] bytes) {
            this.name = name;
            this.filename = filename;
            this.mime = mime;
            this.bytes = bytes;
        }
    }
}
