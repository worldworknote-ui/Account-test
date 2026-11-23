package com.smart.hawala;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * الترخيص على الماك: الواجهة نفسها التي تستدعيها Db وشاشة الترخيص، والرمز نفسه (LicenseToken، توقيع البائع ECDSA P-256)
 * مربوط ببصمة مفتاح جهاز. الفرق عن الهاتف: لا يوجد «تصديق عتادي» في الماك، فمفتاح الجهاز يُنشأ ويُحفظ في سلسلة المفاتيح،
 * ويحمل الطلب معرّف الجهاز (IOPlatformUUID مجزّأً) ليراه البائع. الطلب يحمل platform=mac فتعرضه أداة البائع بوضوح.
 * الفشل يعني المجاني لا الإغلاق، ولا شيء هنا يحجز البيانات.
 */
public final class License {
    private License() {}

    static final String DEV = "license_device_key", MAC = "quota_mac_key";
    private static final String FILE = "license.txt";
    static final String REQ_PREFIX = "SA8-REQ:", REL_PREFIX = "SA8-REL:";

    public static volatile Entitlements current = Entitlements.free("");
    public static volatile String signals = "";
    public static volatile boolean signatureOk = true;
    /** 11.5: على الكمبيوتر لا فحص لتوقيع الحزمة بهذه الطريقة. */
    public static volatile boolean signatureUnknown;

    public static synchronized void load(Context ctx, Db db) {
        try {
            String token = storedToken(ctx, db);
            if (token.isEmpty()) {
                current = Entitlements.free("");
                return;
            }
            byte[] dk = deviceKeyX509();
            LicenseToken.Grant g = LicenseToken.verify(token, dk);
            if (g == null) {
                current = Entitlements.free(dk == null
                        ? "رمز الترخيص المحفوظ يخص جهازًا آخر أو مفتاح هذا الجهاز غير موجود (مثلًا بعد نقل البيانات إلى ماك جديد). اطلب نقل الرخصة."
                        : "رمز الترخيص المحفوظ لا يطابق مفتاح هذا الجهاز. اطلب رمزًا جديدًا من البائع.");
                return;
            }
            if (revoked(db, g.lid())) {
                current = Entitlements.free("أُلغيت الرخصة " + g.lid() + " (مسرّبة أو منقولة). تواصل مع البائع.");
                return;
            }
            current = LicenseGuard.decide(ctx, db, g);   // 12.7: المكتب والوقت الموثوق   // 12.6: الاشتراك بتاريخ انتهاء
        } catch (Throwable t) {
            current = Entitlements.free("تعذر التحقق من الترخيص الآن (" + t.getClass().getSimpleName() + "). أعد فتح البرنامج؛ الإضافة متوقفة عند حدود المجاني فقط.");
        }
    }

    static String storedToken(Context ctx, Db db) {
        String a = db.setting("license_token", "").trim();
        String b = readFile(ctx).trim();
        if (a.equals(b)) return a;
        byte[] dk = null;
        try {
            dk = deviceKeyX509();
        } catch (Throwable ignored) {
            // يُعامل كغير صالح
        }
        boolean aOk = !a.isEmpty() && LicenseToken.verify(a, dk) != null;
        boolean bOk = !b.isEmpty() && LicenseToken.verify(b, dk) != null;
        String keep = aOk ? a : bOk ? b : (a.isEmpty() ? b : a);
        save(ctx, db, keep);
        return keep;
    }

    private static String readFile(Context ctx) {
        try (InputStream in = new java.io.FileInputStream(new File(ctx.getFilesDir(), FILE))) {
            return new String(Db.readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void save(Context ctx, Db db, String token) {
        db.setSettingQuiet("license_token", token);
        File f = new File(ctx.getFilesDir(), FILE);
        if (token.isEmpty()) {
            f.delete();
            return;
        }
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // يكفي الموضع الأول
        }
    }

    private static boolean revoked(Db db, String lid) {
        for (String x : db.setting("license_revoked", "").split(",")) if (!x.trim().isEmpty() && x.trim().equals(lid)) return true;
        return false;
    }

    // ===================================================================================
    // مفتاح الجهاز وطلب التفعيل
    // ===================================================================================

    /** المفتاح محفوظ «عام|خاص» (Base64) في سلسلة المفاتيح. */
    private static String[] stored() throws Exception {
        String s = SecretStore.get(DEV);
        if (s == null || !s.contains("|")) return null;
        return s.split("\\|", 2);
    }

    static byte[] deviceKeyX509() throws Exception {
        String[] k = stored();
        return k == null ? null : Base64.getDecoder().decode(k[0]);
    }

    private static PrivateKey privateKey() throws Exception {
        String[] k = stored();
        if (k == null) throw new IllegalStateException("لا يوجد مفتاح جهاز");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(k[1])));
    }

    private static void createDeviceKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        SecretStore.put(DEV, Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "|" + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
    }

    /** معرّف عتاد الماك (IOPlatformUUID) مجزّأً: يراه البائع ليعرف أن الطلب من الجهاز نفسه عند النقل. */
    static String hardwareId() {
        String raw = "";
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.US).contains("mac")) {
                SecretStore.Res r = SecretStore.run("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").matcher(r.out);
                if (m.find()) raw = m.group(1);
            } else if (System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win")) {
                SecretStore.Res r = SecretStore.run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("MachineGuid\\s+REG_SZ\\s+(\\S+)").matcher(r.out);
                if (m.find()) raw = "win:" + m.group(1);
            }
        } catch (Throwable ignored) {
            // يبقى فارغًا
        }
        if (raw.isEmpty()) return "";
        try {
            return BackupFormat.hex(MessageDigest.getInstance("SHA-256").digest(("SA8|" + raw).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    static String model() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win"))
            return "Windows PC / " + System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " / " + System.getProperty("os.arch", "");
        String m = "Mac";
        try {
            SecretStore.Res r = SecretStore.run("/usr/sbin/sysctl", "-n", "hw.model");
            if (r.code == 0 && !r.out.trim().isEmpty()) m = r.out.trim();
        } catch (Throwable ignored) {
            // غير مهم
        }
        return m + " / macOS " + System.getProperty("os.version", "") + " / " + System.getProperty("os.arch", "");
    }

    public static String requestText(Context ctx, Db db, String order) throws Exception {
        order = order == null ? "" : order.trim();
        if (order.isEmpty() || order.length() > 64) throw new IllegalArgumentException("اكتب رقم الطلب الذي أعطاك إياه البائع (حتى 64 حرفًا).");
        if (deviceKeyX509() == null) createDeviceKey();
        byte[] pub = deviceKeyX509();
        JSONObject j = new JSONObject();
        j.put("t", "sa-activate");
        j.put("v", 1);
        j.put("platform", "mac");
        j.put("order", order);
        j.put("pkg", ctx.getPackageName());
        j.put("ver", BuildInfo.VERSION);
        j.put("build", BuildInfo.BUILD_DATE);
        j.put("dk", LicenseToken.deviceFingerprint(pub));
        j.put("pub", Base64.getEncoder().encodeToString(pub));
        j.put("chain", new JSONArray());
        j.put("hw", hardwareId());
        j.put("model", model());
        j.put("at", Clock.now());
        // إثبات حيازة المفتاح: توقيع رقم الطلب ببصمة المفتاح نفسه
        String proof = "v=1;t=request;order=" + order + ";dk=" + LicenseToken.deviceFingerprint(pub);
        j.put("proof", proof);
        j.put("proofSig", sign(proof.getBytes(StandardCharsets.UTF_8)));
        db.audit("LICENSE_REQUEST", "license", 0, order);
        // 11.5: مضغوطًا (أقصر بنحو الثلث)؛ أداة البائع تقرأ الصيغتين
        LicenseGuard.addToRequest(ctx, db, j);   // 12.7: هوية المكتب وسجل محاولات الاستخدام لمكتب آخر
        return LicenseToken.packRequest(j.toString());
    }

    public static Entitlements activate(Context ctx, Db db, String token) throws Exception {
        token = LicenseToken.extract(token);   // 11.5: من ملف المفتاح أو رابط QR أو رسائل واتساب مقسّمة
        byte[] dk = deviceKeyX509();
        if (dk == null) throw new IllegalStateException("أنشئ طلب التفعيل أولًا من هذا الجهاز ثم أرسله للبائع.");
        LicenseToken.Grant g = LicenseToken.verify(token, dk);
        if (g == null) {
            String lid = LicenseToken.peek(token).get("lid");
            throw new IllegalArgumentException("الرمز غير صالح لهذا الجهاز" + (lid == null ? "" : " (" + lid + ")")
                    + ": تأكد أنك نسخته كاملًا وأنه صادر لطلب هذا الجهاز نفسه.");
        }
        save(ctx, db, token.replaceAll("\\s+", ""));
        db.audit("LICENSE_ACTIVATE", "license", 0, g.lid());
        load(ctx, db);
        return current;
    }

    public static String release(Context ctx, Db db) throws Exception {
        String token = storedToken(ctx, db);
        byte[] dk = deviceKeyX509();
        LicenseToken.Grant g = LicenseToken.verify(token, dk);
        if (g == null) throw new IllegalStateException("لا توجد رخصة مفعّلة على هذا الجهاز.");
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        String body = "v=1;t=release;lid=" + g.lid() + ";dk=" + LicenseToken.deviceFingerprint(dk) + ";n="
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + ";at=" + Clock.now();
        String sig = sign(body.getBytes(StandardCharsets.UTF_8));
        save(ctx, db, "");
        SecretStore.delete(DEV);
        db.audit("LICENSE_RELEASE", "license", 0, g.lid());
        load(ctx, db);
        return REL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "." + sig;
    }

    /** 12.7: توقيع بمفتاح الجهاز (سجل المحاولات). */
    static String signData(byte[] data) throws Exception { return sign(data); }

    private static String sign(byte[] data) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
        s.initSign(privateKey());
        s.update(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }

    // ===================================================================================
    // بصمة عدّادات المجاني
    // ===================================================================================

    static String mac(String data) {
        try {
            String k = SecretStore.get(MAC);
            if (k == null) {
                byte[] raw = new byte[32];
                new SecureRandom().nextBytes(raw);
                k = Base64.getEncoder().encodeToString(raw);
                SecretStore.put(MAC, k);
            }
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(Base64.getDecoder().decode(k), "HmacSHA256"));
            return BackupFormat.hex(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean highRisk() { return false; }

    // ===================================================================================
    // الخادم الاختياري
    // ===================================================================================

    public static boolean checkDue(Db db) {
        if (db.setting("license_server", "").trim().isEmpty() || !current.pro) return false;
        return Clock.now() - db.settingLong("license_last_check", 0) > 7L * 86400000L;
    }

    public static String checkOnline(Context ctx, Db db) {
        String base = db.setting("license_server", "").trim();
        if (base.isEmpty() || !current.pro) return "لا يوجد خادم ترخيص مضبوط أو لا رخصة مفعّلة.";
        HttpURLConnection c = null;
        try {
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String n = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
            byte[] dk = deviceKeyX509();
            String body = "v=1;t=check;lid=" + current.lid + ";dk=" + LicenseToken.deviceFingerprint(dk) + ";n=" + n + ";at=" + Clock.now();
            JSONObject req = new JSONObject();
            req.put("body", body);
            req.put("sig", sign(body.getBytes(StandardCharsets.UTF_8)));
            req.put("pub", Base64.getEncoder().encodeToString(dk));
            req.put("ver", BuildInfo.VERSION);
            req.put("platform", "mac");
            c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/v1/check").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) return "رد الخادم: " + c.getResponseCode();
            JSONObject res = new JSONObject(new String(Db.readAll(c.getInputStream()), StandardCharsets.UTF_8));
            JSONArray rv = res.optJSONArray("revoked");
            StringBuilder list = new StringBuilder();
            if (rv != null) for (int i = 0; i < rv.length(); i++) list.append(i > 0 ? "," : "").append(rv.optString(i));
            db.setSettingQuiet("license_revoked", list.toString());
            db.setSettingQuiet("license_last_check", "" + Clock.now());
            load(ctx, db);
            return res.optString("message", "تم الفحص ✓");
        } catch (Throwable t) {
            return "تعذر الاتصال بخادم الترخيص (" + t.getClass().getSimpleName() + "). لا يتعطل شيء؛ يُعاد لاحقًا.";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
