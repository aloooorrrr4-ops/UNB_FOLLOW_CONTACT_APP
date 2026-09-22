package com.unb.imageeditor;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AppRemoteConfig {

    public static final String CONFIG_URL =
            "https://raw.githubusercontent.com/aloooorrrr4-ops/UNB_FOLLOW_CONTACT_APP/main/control/app-config.json";

    public String appName = "محرر الصور الذكي";
    public String subtitle = "ارفع صورة، ثم اكتب التعديل كأنك تتحدث معي";
    public String serverBase = "http://91.98.126.167:18083";
    public String chatEditPath = "/api/chat/edit";
    public boolean maintenance = false;
    public String maintenanceMessage = "الخدمة تحت الصيانة حالياً";
    public String welcomeMessage = "أرسل صورة ثم اكتب التعديل المطلوب.";
    public int maxUploadMb = 15;

    public static AppRemoteConfig fetch() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(CONFIG_URL).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("Remote config HTTP " + code);
        }

        byte[] body;
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            body = out.toByteArray();
        } finally {
            conn.disconnect();
        }

        JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
        AppRemoteConfig c = new AppRemoteConfig();

        c.appName = o.optString("app_name", c.appName);
        c.subtitle = o.optString("subtitle", c.subtitle);
        c.serverBase = trimSlash(o.optString("server_base", c.serverBase));
        c.chatEditPath = normalizePath(o.optString("chat_edit_path", c.chatEditPath));
        c.maintenance = o.optBoolean("maintenance", c.maintenance);
        c.maintenanceMessage = o.optString("maintenance_message", c.maintenanceMessage);
        c.welcomeMessage = o.optString("welcome_message", c.welcomeMessage);
        c.maxUploadMb = Math.max(1, o.optInt("max_upload_mb", c.maxUploadMb));

        return c;
    }

    private static String trimSlash(String value) {
        if (value == null) return "";
        String v = value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String normalizePath(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "/api/chat/edit";
        return v.startsWith("/") ? v : "/" + v;
    }
}
