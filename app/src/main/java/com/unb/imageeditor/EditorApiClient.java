package com.unb.imageeditor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditorApiClient {

    public interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    public static class Project {
        public final String id;
        public final Bitmap preview;
        public Project(String id, Bitmap preview) {
            this.id = id;
            this.preview = preview;
        }
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private String serverBase;
    private String apiKey = "";

    public EditorApiClient(String serverBase) {
        this.serverBase = trimSlash(serverBase);
    }

    public void setServerBase(String serverBase) {
        this.serverBase = trimSlash(serverBase);
    }

    public String getServerBase() {
        return serverBase;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void close() {
        executor.shutdownNow();
    }

    public void health(Callback<JSONObject> cb) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open("/health", "GET");
                ensureOk(conn);
                cb.onSuccess(new JSONObject(readText(conn.getInputStream())));
            } catch (Exception e) {
                cb.onError(clean(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void createProject(byte[] image, String fileName, Callback<Project> cb) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String boundary = "----UNB" + UUID.randomUUID().toString().replace("-", "");
                conn = open("/api/editor/projects", "POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                try (OutputStream out = conn.getOutputStream()) {
                    writeFilePart(out, boundary, "image",
                            fileName == null ? "image.png" : fileName,
                            "application/octet-stream", image);
                    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }

                ensureOk(conn);
                JSONObject json = new JSONObject(readText(conn.getInputStream()));
                String id = json.optString("project_id", "");
                String previewPath = json.optString("preview_url", "");
                if (id.isEmpty()) throw new Exception("الخادم لم يرجع رقم المشروع");

                Bitmap preview = previewPath.isEmpty()
                        ? BitmapFactory.decodeByteArray(image, 0, image.length)
                        : downloadBitmap(previewPath);

                cb.onSuccess(new Project(id, preview));
            } catch (Exception e) {
                cb.onError(clean(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void applyOperation(String projectId, String operation,
                               JSONObject params, Callback<Bitmap> cb) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open("/api/editor/projects/" + encode(projectId) + "/operations", "POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JSONObject body = new JSONObject();
                body.put("operation", operation);
                body.put("params", params == null ? new JSONObject() : params);

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                ensureOk(conn);
                JSONObject json = new JSONObject(readText(conn.getInputStream()));
                String previewPath = json.optString("preview_url", "");
                if (previewPath.isEmpty()) throw new Exception("لا توجد معاينة من الخادم");
                cb.onSuccess(downloadBitmap(previewPath));
            } catch (Exception e) {
                cb.onError(clean(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void history(String projectId, String action, Callback<Bitmap> cb) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open("/api/editor/projects/" + encode(projectId) + "/" + action, "POST");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
                conn.getOutputStream().close();
                ensureOk(conn);
                JSONObject json = new JSONObject(readText(conn.getInputStream()));
                cb.onSuccess(downloadBitmap(json.getString("preview_url")));
            } catch (Exception e) {
                cb.onError(clean(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void getJson(String path, Callback<JSONObject> cb) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(path, "GET");
                ensureOk(conn);
                cb.onSuccess(new JSONObject(readText(conn.getInputStream())));
            } catch (Exception e) {
                cb.onError(clean(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void getProjectSection(String projectId, String section, Callback<JSONObject> cb) {
        getJson("/api/editor/projects/" + encode(projectId) + "/" + encode(section), cb);
    }

    public void getResources(String kind, Callback<JSONObject> cb) {
        getJson("/api/editor/resources/" + encode(kind), cb);
    }

    public void closeProject(String projectId, Callback<JSONObject> cb) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open("/api/editor/projects/" + encode(projectId), "DELETE");
                ensureOk(conn);
                cb.onSuccess(new JSONObject(readText(conn.getInputStream())));
            } catch (Exception e) {
                cb.onError(clean(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void exportProject(String projectId, String format, Callback<byte[]> cb) {
        executor.execute(() -> {
            try {
                String safeFormat = format == null ? "png" : format.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (safeFormat.isEmpty()) safeFormat = "png";
                byte[] data = downloadBytes("/api/editor/projects/" + encode(projectId) +
                        "/export?format=" + safeFormat);
                cb.onSuccess(data);
            } catch (Exception e) {
                cb.onError(clean(e));
            }
        });
    }

    public byte[] downloadBytes(String relativeOrAbsolute) throws Exception {
        String target = absoluteUrl(relativeOrAbsolute);
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setUseCaches(false);
        if (!apiKey.isEmpty()) {
            conn.setRequestProperty("X-UNB-Editor-Key", apiKey);
        }
        try {
            ensureOk(conn);
            return readBytes(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private Bitmap downloadBitmap(String path) throws Exception {
        byte[] bytes = downloadBytes(path);
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (b == null) throw new Exception("الخادم رجّع صورة غير صالحة");
        return b;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(absoluteUrl(path)).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod(method);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json, image/*");
        if (!apiKey.isEmpty()) {
            conn.setRequestProperty("X-UNB-Editor-Key", apiKey);
        }
        return conn;
    }

    private String absoluteUrl(String path) {
        if (path == null) return serverBase;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return serverBase + (path.startsWith("/") ? path : "/" + path);
    }

    private static void ensureOk(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) return;
        InputStream in = conn.getErrorStream();
        String body = in == null ? "" : readText(in);
        throw new Exception("HTTP " + code + (body.isEmpty() ? "" : ": " + body));
    }

    private static void writeFilePart(OutputStream out, String boundary,
                                      String field, String fileName,
                                      String contentType, byte[] data) throws Exception {
        String head = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + field +
                "\"; filename=\"" + fileName.replace("\"", "") + "\"\r\n" +
                "Content-Type: " + contentType + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(InputStream in) throws Exception {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String trimSlash(String v) {
        if (v == null) return "";
        String s = v.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String encode(String value) {
        return value == null ? "" : value.replace("/", "").replace("..", "");
    }

    private static String clean(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
