package com.smart.hawala;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 10.2: ناقل المزامنة عبر خادم خاص بك (sync-server/server.js): نفس ملفات المجلد المشترك المشفّرة، لكن عبر HTTPS مباشرة،
 * فتصل التغييرات خلال ثوانٍ. الخادم لا يرى إلا ملفات مشفّرة بأسماء معتمة، ولا يكتب في خزنة إلا من يملك رمزًا مشتقًا
 * من مفتاحها (يحفظ الخادم بصمة الرمز فقط).
 *
 * <p>رابط الانضمام: {@code https://الخادم/v1/vault/<رمز الخزنة>} — يحمل العنوان ورمز الخزنة معًا.
 */
public final class HttpSyncFolder implements SyncFolder {
    public static final String VAULT_PATH = "/v1/vault/";
    private final String base;
    private final String serverKey;
    private String vault;
    private String token;
    int timeoutMs = 30000;

    public HttpSyncFolder(String base, String vault, String serverKey) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.vault = vault == null || vault.isEmpty() ? null : vault;
        this.serverKey = serverKey == null ? "" : serverKey.trim();
    }

    /** يفك رابط الانضمام إلى [العنوان، رمز الخزنة]، أو [العنوان، null] لعنوان خادم بلا خزنة. */
    public static String[] parseLink(String link) {
        String s = link == null ? "" : link.trim();
        int i = s.indexOf(VAULT_PATH);
        if (i < 0) return new String[]{s, null};
        String v = s.substring(i + VAULT_PATH.length()).replaceAll("[/?#].*$", "");
        return new String[]{s.substring(0, i), v.isEmpty() ? null : v};
    }

    public static String link(String base, String vault) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + VAULT_PATH + vault;
    }

    /** للعرض والمشاركة: رابط الانضمام لهذه الخزنة. */
    public String joinLink() { return vault == null ? base : link(base, vault); }

    public static void checkUrl(String base) {
        String b = base == null ? "" : base.trim().toLowerCase(java.util.Locale.ROOT);
        if (!b.startsWith("https://") && !(b.startsWith("http://") && Model.localUrl(b))) throw new IllegalArgumentException("عنوان الخادم يجب أن يبدأ بـ https://");
    }

    @Override public void unlock(SyncCrypto.Keys keys) {
        if (vault == null) vault = keys.vaultId;
        token = keys.serverToken();
    }

    @Override public String kind() { return "server"; }

    private String fileUrl(String name) throws IOException {
        if (vault == null) throw new IOException("رمز الخزنة غير معروف");
        return base + VAULT_PATH + vault + "/file/" + URLEncoder.encode(name, "UTF-8");
    }

    private HttpURLConnection open(String url, String method, boolean auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs * 4);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "SmartAccountant/" + BuildInfo.VERSION);
        if (!serverKey.isEmpty()) c.setRequestProperty("X-Server-Key", serverKey);
        if (auth && token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream i = in; ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[16384];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    private IOException fail(HttpURLConnection c, int code) {
        String msg = "";
        try {
            msg = new String(readAll(c.getErrorStream()), StandardCharsets.UTF_8);
            JSONObject j = new JSONObject(msg);
            msg = j.optString("error", msg);
        } catch (Exception ignored) {
            // النص كما هو
        }
        if (code == 401 || code == 403) return new IOException("الخادم رفض الوصول (" + code + "): " + (msg.isEmpty() ? "رمز الخادم أو الخزنة غير صحيح" : msg));
        if (code == 413) return new IOException("الملف أكبر مما يقبله الخادم");
        return new IOException("خطأ من خادم المزامنة (" + code + ")" + (msg.isEmpty() ? "" : ": " + msg));
    }

    @Override public List<String> list(String prefix) throws IOException {
        List<String> out = new ArrayList<>();
        if (vault == null) return out;
        HttpURLConnection c = open(base + VAULT_PATH + vault + "/list?prefix=" + URLEncoder.encode(prefix, "UTF-8"), "GET", true);
        try {
            int code = c.getResponseCode();
            if (code == 404) return out;
            if (code != 200) throw fail(c, code);
            JSONArray a = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)).getJSONArray("files");
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("n"));
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("رد غير مفهوم من خادم المزامنة");
        } finally {
            c.disconnect();
        }
    }

    @Override public boolean exists(String name) throws IOException { return read(name) != null; }

    @Override public byte[] read(String name) throws IOException {
        if (vault == null) return null;
        HttpURLConnection c = open(fileUrl(name), "GET", !SyncEngine.HEADER.equals(name));
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw fail(c, code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    @Override public void write(String name, byte[] data) throws IOException {
        if (token == null) throw new IOException("الخزنة مقفلة: لا كتابة على الخادم قبل فتحها");
        HttpURLConnection c = open(fileUrl(name), "PUT", true);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(data);
            }
            int code = c.getResponseCode();
            if (code != 200 && code != 201 && code != 204) throw fail(c, code);
            readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    @Override public boolean canDelete() { return true; }

    /** 10.4: حذف ملف (يحتاج خادم 10.4؛ الخادم الأقدم يرد 405 فيُبلَّغ المستخدم بتحديثه). */
    @Override public void delete(String name) throws IOException {
        if (token == null) throw new IOException("الخزنة مقفلة");
        HttpURLConnection c = open(fileUrl(name), "DELETE", true);
        try {
            int code = c.getResponseCode();
            if (code == 405) throw new OldServer();
            if (code != 200 && code != 204 && code != 404) throw fail(c, code);
        } finally {
            c.disconnect();
        }
    }

    /** 10.4: خادم من إصدار أقدم لا يدعم الحذف. */
    public static final class OldServer extends IOException {
        OldServer() { super("خادم المزامنة من إصدار أقدم لا يدعم التنظيف: حدّث server.js على الخادم إلى 10.4"); }
    }

    @Override public java.util.Map<String, long[]> stat(String prefix) throws IOException {
        java.util.Map<String, long[]> out = new java.util.HashMap<>();
        if (vault == null) return out;
        HttpURLConnection c = open(base + VAULT_PATH + vault + "/list?prefix=" + URLEncoder.encode(prefix, "UTF-8"), "GET", true);
        try {
            int code = c.getResponseCode();
            if (code == 404) return out;
            if (code != 200) throw fail(c, code);
            JSONArray a = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)).getJSONArray("files");
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = a.getJSONObject(i);
                out.put(f.getString("n"), new long[]{f.optLong("s"), f.optLong("m")});
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("رد غير مفهوم من خادم المزامنة");
        } finally {
            c.disconnect();
        }
    }

    /** فحص الخادم قبل الإعداد: يعمل؟ ويقبل رمز الخادم؟ يرجع وصفًا أو يرمي. */
    public String ping() throws IOException {
        HttpURLConnection c = open(base + "/v1/health", "GET", false);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw fail(c, code);
            JSONObject j = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
            if (!"smart-accountant-sync".equals(j.optString("service"))) throw new IOException("العنوان لا يخص خادم مزامنة «المحاسب الذكي»");
            if (j.optBoolean("keyRequired") && serverKey.isEmpty()) throw new IOException("هذا الخادم يطلب «رمز الخادم»");
            if (j.optBoolean("keyRequired") && !j.optBoolean("keyOk")) throw new IOException("«رمز الخادم» غير صحيح");
            return "الخادم يعمل (" + j.optString("version") + ")";
        } catch (org.json.JSONException e) {
            throw new IOException("العنوان لا يخص خادم مزامنة «المحاسب الذكي»");
        } finally {
            c.disconnect();
        }
    }

    @Override public String describe() {
        try {
            return "خادمك: " + new URL(base).getHost();
        } catch (Exception e) {
            return "خادمك: " + base;
        }
    }
}
