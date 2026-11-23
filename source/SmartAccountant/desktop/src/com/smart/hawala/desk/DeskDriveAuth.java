package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.Desktop;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.json.JSONObject;

/**
 * 10.2: الدخول إلى Google Drive على الماك والويندوز: تسجيل دخول في المتصفح (OAuth لتطبيقات الكمبيوتر مع PKCE وعنوان محلي)،
 * بصلاحية «مجلد التطبيق المخفي» فقط. رمز التجديد يُحفظ في سلسلة المفاتيح (الماك) أو بحماية ويندوز، ولا يُكتب في قاعدة البيانات.
 *
 * <p>معرّف عميل Google للكمبيوتر: من الملف res/google-oauth.json داخل البرنامج (يضعه البائع عند البناء)، أو يُلصق مرة واحدة
 * في شاشة المزامنة.
 */
public final class DeskDriveAuth implements DriveSyncFolder.Tokens {
    public static final String REFRESH = "drive_refresh";
    /** 10.14: رمز الحساب الجديد أثناء تغيير الحساب (قبل أن يُعتمد)، ورمز الحساب القديم بعد النقل (لحذف الخزنة منه لاحقًا). */
    public static final String NEW = "drive_refresh_new", OLD = "drive_refresh_old";
    private final Db db;
    private final String key;
    private String access;
    private long expires;

    public DeskDriveAuth(Db db) { this(db, REFRESH); }

    public DeskDriveAuth(Db db, String key) {
        this.db = db;
        this.key = key;
    }

    /** [معرّف العميل، السر] أو null. */
    public static String[] client(Db db) {
        String id = db.setting("drive_client_id", "").trim(), sec = db.setting("drive_client_secret", "").trim();
        if (!id.isEmpty()) return new String[]{id, sec};
        try (InputStream in = DeskDriveAuth.class.getResourceAsStream("/res/google-oauth.json")) {
            if (in == null) return null;
            JSONObject j = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JSONObject x = j.has("installed") ? j.getJSONObject("installed") : j;
            String cid = x.optString("client_id");
            return cid.isEmpty() ? null : new String[]{cid, x.optString("client_secret")};
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean connected() { return has(REFRESH); }

    public static boolean has(String key) {
        try {
            String r = SecretStore.get(key);
            return r != null && !r.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static void disconnect() { drop(REFRESH); }

    public static void drop(String key) {
        try {
            SecretStore.delete(key);
        } catch (Exception ignored) {
            // لا شيء
        }
    }

    /** 10.14: ينقل رمزًا محفوظًا من خانة إلى أخرى (الجديد يصبح الحالي، والحالي يصبح القديم). null = تُفرّغ الوجهة. */
    public static void promote(String from, String to) throws Exception {
        String v = SecretStore.get(from);
        if (v == null || v.isEmpty()) SecretStore.delete(to);
        else SecretStore.put(to, v);
        SecretStore.delete(from);
    }

    @Override public synchronized String token(boolean refresh) throws IOException {
        if (!refresh && access != null && Clock.now() < expires - 60000) return access;
        String rt;
        try {
            rt = SecretStore.get(key);
        } catch (Exception e) {
            throw new IOException("تعذر قراءة رمز Google المحفوظ: " + e.getMessage());
        }
        if (rt == null || rt.isEmpty()) throw new IOException("سجّل الدخول بحساب Google من شاشة «المزامنة» أولًا");
        String[] c = client(db);
        if (c == null) throw new IOException("معرّف عميل Google للكمبيوتر غير مضبوط");
        JSONObject r = post("client_id=" + enc(c[0]) + "&client_secret=" + enc(c[1]) + "&refresh_token=" + enc(rt) + "&grant_type=refresh_token");
        if (r.has("error")) {
            if ("invalid_grant".equals(r.optString("error"))) drop(key);
            throw new IOException("انتهى دخول Google (" + r.optString("error") + "). سجّل الدخول من جديد من «المزامنة».");
        }
        access = r.optString("access_token");
        expires = Clock.now() + r.optLong("expires_in", 3000) * 1000;
        return access;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String tokenUrl = "https://oauth2.googleapis.com/token";

    static JSONObject post(String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(tokenUrl).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] b = form.getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) {
            o.write(b);
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        try {
            String s = in == null ? "{}" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new JSONObject(s);
        } catch (org.json.JSONException e) {
            throw new IOException("رد غير مفهوم من Google (" + code + ")");
        } finally {
            c.disconnect();
        }
    }

    /**
     * تسجيل الدخول التفاعلي (في خيط خلفي): يفتح المتصفح، وينتظر عودة Google إلى عنوان محلي على هذا الجهاز، ثم يحفظ رمز التجديد.
     * المهلة 5 دقائق.
     */
    public static String login(Db db) throws Exception { return login(db, REFRESH, ""); }

    /**
     * 10.14: يظهر اختيار الحساب دائمًا (select_account)، فـ«تغيير الحساب» يغيّره فعلًا. يُحفظ رمز التجديد في الخانة key،
     * ويرجع بريد الحساب الذي اختاره المستخدم (من Drive نفسه)، أو "" إن تعذّرت معرفته.
     */
    private static volatile ServerSocket waiting;
    private static volatile String lastUrl;

    /** 11.2: إلغاء انتظار الدخول الجاري (زر «إلغاء»). */
    public static void cancelLogin() {
        ServerSocket s = waiting;
        if (s != null) try { s.close(); } catch (IOException ignored) { /* أُغلق */ }
    }

    /** 11.2: فتح صفحة الدخول مرة أخرى (إن أُغلق المتصفح أو لم يظهر). */
    public static void reopenLogin() {
        String u = lastUrl;
        if (u != null && waiting != null && !waiting.isClosed()) try { Desktop.getDesktop().browse(new URI(u)); } catch (Exception ignored) { /* لا متصفح */ }
    }

    public static String login(Db db, String key, String hint) throws Exception {
        String[] c = client(db);
        if (c == null) throw new IOException("معرّف عميل Google للكمبيوتر غير مضبوط (انظر دليل الإصدار).");
        SecureRandom rnd = new SecureRandom();
        byte[] v = new byte[32];
        rnd.nextBytes(v);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(v);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        byte[] st = new byte[16];
        rnd.nextBytes(st);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(st);
        try (ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            // 11.2: يمكن إلغاؤه من نافذة الانتظار، ويتوقف وحده بعد 3 دقائق
            ss.setSoTimeout(180000);
            waiting = ss;
            String redirect = "http://127.0.0.1:" + ss.getLocalPort();
            String url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + enc(c[0]) + "&redirect_uri=" + enc(redirect)
                    + "&response_type=code&scope=" + enc(DriveSyncFolder.SCOPE) + "&code_challenge=" + challenge + "&code_challenge_method=S256"
                    + "&access_type=offline&prompt=" + enc("select_account consent") + (hint == null || hint.isEmpty() ? "" : "&login_hint=" + enc(hint))
                    + "&state=" + enc(state);
            lastUrl = url;
            Desktop.getDesktop().browse(new URI(url));
            while (true) {
                Socket acc;
                try {
                    acc = ss.accept();
                } catch (java.net.SocketTimeoutException te) {
                    throw new IOException("انتهت مهلة الدخول (3 دقائق) دون إكماله في المتصفح. أعد المحاولة عندما تكون جاهزًا.");
                } catch (java.net.SocketException se) {
                    if (ss.isClosed()) throw new IOException("أُلغي الدخول إلى Google.");
                    throw se;
                }
                try (Socket s = acc) {
                    s.setSoTimeout(10000);
                    String line = readLine(s.getInputStream());
                    // GET /?state=..&code=.. HTTP/1.1
                    String q = line.contains("?") ? line.substring(line.indexOf('?') + 1).split(" ")[0] : "";
                    String code = param(q, "code"), err = param(q, "error"), gotState = param(q, "state");
                    if (code == null && err == null) {
                        reply(s, "…");
                        continue;
                    }
                    if (err != null || !state.equals(gotState)) {
                        reply(s, "لم يكتمل الدخول. ارجع إلى البرنامج وأعد المحاولة.");
                        throw new IOException("لم يكتمل الدخول إلى Google (" + (err == null ? "state" : err) + ")");
                    }
                    JSONObject r = post("code=" + enc(code) + "&client_id=" + enc(c[0]) + "&client_secret=" + enc(c[1]) + "&redirect_uri=" + enc(redirect)
                            + "&grant_type=authorization_code&code_verifier=" + enc(verifier));
                    if (r.has("error") || r.optString("refresh_token").isEmpty()) {
                        reply(s, "تعذر إكمال الدخول. ارجع إلى البرنامج.");
                        throw new IOException("تعذر إكمال الدخول إلى Google: " + r.optString("error_description", r.optString("error", "بلا رمز تجديد")));
                    }
                    SecretStore.put(key, r.getString("refresh_token"));
                    reply(s, "تم ✓ — يمكنك إغلاق هذه الصفحة والرجوع إلى «المحاسب الذكي».");
                    return email(r.optString("access_token"));
                }
            }
        }
    }

    static String aboutUrl = "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)";

    /** 10.14: بريد الحساب المتصل (واجهة Drive «about» بالصلاحية نفسها). "" إن تعذّر. */
    public static String email(String access) {
        if (access == null || access.isEmpty()) return "";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(aboutUrl).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + access);
            try {
                if (c.getResponseCode() >= 400) return "";
                JSONObject j = new JSONObject(new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                return j.optJSONObject("user") == null ? "" : j.getJSONObject("user").optString("emailAddress");
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0 && ch != '\n') b.append((char) ch);
        return b.toString().trim();
    }

    private static String param(String q, String k) {
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(k)) {
                try {
                    return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(i + 1);
                }
            }
        }
        return null;
    }

    private static void reply(Socket s, String msg) throws IOException {
        String html = "<!doctype html><html dir=rtl lang=ar><meta charset=utf-8><title>المحاسب الذكي</title>"
                + "<body style='font-family:sans-serif;text-align:center;padding:60px;font-size:20px'>" + msg + "</body></html>";
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.write(b);
        o.flush();
    }
}
