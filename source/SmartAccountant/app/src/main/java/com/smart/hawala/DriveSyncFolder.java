package com.smart.hawala;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 10.2: ناقل المزامنة عبر Google Drive مباشرة (واجهة Drive v3)، في «مجلد بيانات التطبيق» المخفي (appDataFolder) لحساب Google
 * الذي تسجّل به: لا يظهر في Drive ولا يلمسه غير هذا التطبيق، ولا يحتاج تطبيق Drive على الهاتف، فتصل التغييرات خلال ثوانٍ.
 * الملفات هي نفسها المشفّرة بأسماء معتمة. كل الأجهزة يجب أن تسجّل بحساب Google نفسه.
 *
 * <p>الرمز (access token) يأتي من {@link Tokens}: على أندرويد من خدمات Google، وعلى الكمبيوتر من تسجيل دخول في المتصفح.
 */
public final class DriveSyncFolder implements SyncFolder {
    public static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";

    /** مصدر رمز الدخول. refresh = الرمز السابق رُفض (انتهى): اطلب جديدًا. */
    public interface Tokens {
        String token(boolean refresh) throws IOException;
    }

    /** للاختبار: عنوان واجهة بديلة (خادم محاكٍ). */
    static String api = "https://www.googleapis.com";

    private final Tokens tokens;
    private String token;
    private final Map<String, String[]> ids = new HashMap<>();
    private boolean listed;
    int timeoutMs = 30000;

    public DriveSyncFolder(Tokens tokens) { this.tokens = tokens; }

    @Override public String kind() { return "drive"; }

    @Override public String describe() { return "Google Drive (مجلد التطبيق المخفي)"; }

    private interface Call<T> {
        T run(String token) throws IOException;
    }

    /** ينفّذ الطلب، وإن رُفض الرمز (401) يطلب رمزًا جديدًا مرة واحدة. */
    private <T> T authed(Call<T> c) throws IOException {
        if (token == null) token = tokens.token(false);
        try {
            return c.run(token);
        } catch (Unauthorized e) {
            token = tokens.token(true);
            return c.run(token);
        }
    }

    static final class Unauthorized extends IOException {
        Unauthorized() { super("انتهت صلاحية الدخول إلى Google"); }
    }

    private HttpURLConnection open(String url, String method, String tok) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs * 4);
        c.setUseCaches(false);
        c.setRequestProperty("Authorization", "Bearer " + tok);
        return c;
    }

    /** 10.18: وقت خادم Google من ترويسة الرد يصحّح «الساعة الموحّدة» (من الخادم الحقيقي فقط، لا من محاكٍ أو خادم بديل). */
    private void learnClock(HttpURLConnection c) {
        try {
            if (!"www.googleapis.com".equals(c.getURL().getHost())) return;
            long d = c.getHeaderFieldDate("Date", 0);
            if (d > 0) Clock.learn(d);
        } catch (Throwable ignored) {
            // التصحيح اختياري
        }
    }

    private IOException fail(HttpURLConnection c, int code) {
        if (code == 401) return new Unauthorized();
        String msg = "";
        try {
            JSONObject j = new JSONObject(new String(HttpSyncFolder.readAll(c.getErrorStream()), StandardCharsets.UTF_8));
            msg = j.getJSONObject("error").optString("message");
        } catch (Exception ignored) {
            // بلا تفاصيل
        }
        String lm = msg.toLowerCase();
        if (code == 403 && (lm.contains("not been used") || lm.contains("disabled")))
            return new IOException("واجهة Google Drive غير مفعّلة في مشروع Google Cloud الخاص بالتطبيق (فعّل Google Drive API). " + msg);
        if (code == 403 && lm.contains("insufficient")) return new IOException("لم يُمنح التطبيق صلاحية Drive. سجّل الدخول من جديد من «المزامنة».");
        if (code == 403 || code == 429) return new IOException("Google Drive طلب التمهّل (" + code + "). أعد المحاولة بعد قليل.");
        return new IOException("خطأ من Google Drive (" + code + ")" + (msg.isEmpty() ? "" : ": " + msg));
    }

    private void refresh() throws IOException {
        ids.clear();
        String pageToken = null;
        do {
            final String pt = pageToken;
            JSONObject j = authed(tok -> {
                String url = api + "/drive/v3/files?spaces=appDataFolder&pageSize=1000&fields=" + URLEncoder.encode("nextPageToken,files(id,name,modifiedTime,size)", "UTF-8")
                        + "&q=" + URLEncoder.encode("trashed=false", "UTF-8") + (pt == null ? "" : "&pageToken=" + URLEncoder.encode(pt, "UTF-8"));
                HttpURLConnection c = open(url, "GET", tok);
                try {
                    int code = c.getResponseCode();
                learnClock(c);
                    if (code != 200) throw fail(c, code);
                    return new JSONObject(new String(HttpSyncFolder.readAll(c.getInputStream()), StandardCharsets.UTF_8));
                } catch (org.json.JSONException e) {
                    throw new IOException("رد غير مفهوم من Google Drive");
                } finally {
                    c.disconnect();
                }
            });
            JSONArray a = j.optJSONArray("files");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject f = a.optJSONObject(i);
                if (f == null) continue;
                String n = f.optString("name"), id = f.optString("id"), m = f.optString("modifiedTime");
                String[] prev = ids.get(n);
                // أسماء مكررة ممكنة في Drive: الأحدث يفوز
                if (prev == null || prev[1].compareTo(m) < 0) ids.put(n, new String[]{id, m, f.optString("size", "0")});
            }
            pageToken = j.optString("nextPageToken", null);
            if (pageToken != null && pageToken.isEmpty()) pageToken = null;
        } while (pageToken != null);
        listed = true;
    }

    private String id(String name) throws IOException {
        if (!listed) refresh();
        String[] x = ids.get(name);
        if (x == null) {
            refresh();
            x = ids.get(name);
        }
        return x == null ? null : x[0];
    }

    @Override public List<String> list(String prefix) throws IOException {
        refresh();
        List<String> out = new ArrayList<>();
        for (String n : ids.keySet()) if (n.startsWith(prefix)) out.add(n);
        return out;
    }

    @Override public boolean exists(String name) throws IOException { return id(name) != null; }

    @Override public byte[] read(String name) throws IOException {
        final String fid = id(name);
        if (fid == null) return null;
        byte[] b = readId(fid);
        if (b != null) return b;
        // 10.18: الملف استُبدل بين قراءة القائمة وقراءته (الاستبدال = ملف جديد ثم حذف القديم، وبطاقة كل جهاز تُستبدل في كل مزامنة):
        // كان يرجع «غير موجود» فيبدو الجهاز الآخر كأنه خرج من المزامنة. تُحدَّث القائمة ويُقرأ الملف الجديد.
        refresh();
        String[] x = ids.get(name);
        return x == null || x[0].equals(fid) ? null : readId(x[0]);
    }

    private byte[] readId(final String fid) throws IOException {
        return authed(tok -> {
            HttpURLConnection c = open(api + "/drive/v3/files/" + URLEncoder.encode(fid, "UTF-8") + "?alt=media", "GET", tok);
            try {
                int code = c.getResponseCode();
                learnClock(c);
                if (code == 404) return null;
                if (code != 200) throw fail(c, code);
                return HttpSyncFolder.readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
        });
    }

    /** الملفات الكبيرة (اللقطة) تُرفع بالرفع المستأنف؛ الصغيرة بطلب واحد. */
    static final int SIMPLE_MAX = 4 * 1024 * 1024;

    @Override public void write(final String name, final byte[] data) throws IOException {
        // 10.4: ملف جديد لا يُعيد قراءة القائمة كلها (القائمة قُرئت في أول الجولة)
        if (!listed) refresh();
        final String old = ids.containsKey(name) ? ids.get(name)[0] : null;
        // استبدال ملف = ملف جديد بالاسم نفسه ثم حذف القديم (القارئ يأخذ الأحدث إن رأى الاثنين لحظة)
        authed(tok -> {
            JSONObject r = data.length > SIMPLE_MAX ? resumable(tok, null, name, data) : simple(tok, null, name, data);
            ids.put(name, new String[]{r.optString("id"), r.optString("modifiedTime", "9999"), "" + data.length});
            return null;
        });
        if (old != null) {
            try {
                authed(tok -> {
                    HttpURLConnection c = open(api + "/drive/v3/files/" + URLEncoder.encode(old, "UTF-8"), "DELETE", tok);
                    try {
                        int code = c.getResponseCode();
                learnClock(c);
                        if (code == 401) throw new Unauthorized();
                        return null;
                    } finally {
                        c.disconnect();
                    }
                });
            } catch (IOException ignored) {
                // يبقى القديم؛ الأحدث يفوز عند القراءة
            }
        }
    }

    @Override public boolean canDelete() { return true; }

    @Override public void delete(String name) throws IOException {
        if (!listed) refresh();
        String[] x = ids.get(name);
        if (x == null) return;
        final String fid = x[0];
        authed(tok -> {
            HttpURLConnection c = open(api + "/drive/v3/files/" + URLEncoder.encode(fid, "UTF-8"), "DELETE", tok);
            try {
                int code = c.getResponseCode();
                learnClock(c);
                if (code == 401) throw new Unauthorized();
                if (code != 204 && code != 200 && code != 404) throw fail(c, code);
                return null;
            } finally {
                c.disconnect();
            }
        });
        ids.remove(name);
    }

    @Override public Map<String, long[]> stat(String prefix) throws IOException {
        refresh();
        Map<String, long[]> out = new HashMap<>();
        for (Map.Entry<String, String[]> e : ids.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            long size = 0, at = 0;
            try {
                size = Long.parseLong(e.getValue().length > 2 ? e.getValue()[2] : "0");
            } catch (Exception ignored) {
                // بلا حجم
            }
            at = rfc3339(e.getValue()[1]);
            out.put(e.getKey(), new long[]{size, at});
        }
        return out;
    }

    /** وقت Drive (2026-10-23T10:15:30.123Z) بالميلي ثانية، أو 0. */
    static long rfc3339(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s.length() >= 19 ? s.substring(0, 19) : s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private JSONObject result(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
                learnClock(c);
            if (code != 200 && code != 201) throw fail(c, code);
            return new JSONObject(new String(HttpSyncFolder.readAll(c.getInputStream()), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject meta(String name, boolean create) throws IOException {
        JSONObject meta = new JSONObject();
        try {
            if (create) {
                meta.put("name", name);
                meta.put("parents", new JSONArray().put("appDataFolder"));
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        return meta;
    }

    /** طلب PATCH عبر POST (HttpURLConnection في جافا لا يدعم PATCH؛ واجهات Google تقبل الترويسة البديلة). */
    private HttpURLConnection openWrite(String url, String tok, boolean update) throws IOException {
        HttpURLConnection c = open(url, "POST", tok);
        if (update) c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        c.setDoOutput(true);
        return c;
    }

    private JSONObject simple(String tok, String fid, String name, byte[] data) throws IOException {
        String boundary = "sa" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream b = new ByteArrayOutputStream(data.length + 512);
        b.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta(name, fid == null) + "\r\n--" + boundary
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(data);
        b.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] body = b.toByteArray();
        String url = api + "/upload/drive/v3/files" + (fid == null ? "" : "/" + URLEncoder.encode(fid, "UTF-8")) + "?uploadType=multipart&fields=id,name,modifiedTime";
        HttpURLConnection c = openWrite(url, tok, false);
        c.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(body);
        }
        return result(c);
    }

    private JSONObject resumable(String tok, String fid, String name, byte[] data) throws IOException {
        String url = api + "/upload/drive/v3/files" + (fid == null ? "" : "/" + URLEncoder.encode(fid, "UTF-8")) + "?uploadType=resumable&fields=id,name,modifiedTime";
        HttpURLConnection c = openWrite(url, tok, false);
        byte[] m = meta(name, fid == null).toString().getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("X-Upload-Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Upload-Content-Length", "" + data.length);
        c.setFixedLengthStreamingMode(m.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(m);
        }
        String session;
        try {
            int code = c.getResponseCode();
                learnClock(c);
            if (code != 200) throw fail(c, code);
            session = c.getHeaderField("Location");
            HttpSyncFolder.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
        if (session == null) throw new IOException("لم يبدأ الرفع إلى Google Drive");
        HttpURLConnection u = open(session, "PUT", tok);
        u.setDoOutput(true);
        u.setRequestProperty("Content-Type", "application/octet-stream");
        u.setFixedLengthStreamingMode(data.length);
        try (OutputStream o = u.getOutputStream()) {
            o.write(data);
        }
        return result(u);
    }
}
