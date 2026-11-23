package com.smart.hawala;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * الترخيص على الهاتف: مفتاح جهاز داخل Android Keystore (في StrongBox إن وُجد) لا يغادر العتاد،
 * وطلب تفعيل يحمل سلسلة تصديق المفتاح، ورمز موقّع من البائع مربوط ببصمة هذا المفتاح.
 *
 * الفشل يعني المجاني لا الإغلاق: لا شيء هنا يحجز البيانات أو يغلق التطبيق، ولا تُبدَّل رخصة مدفوعة بالمجانية بصمت
 * (السبب يظهر في شاشة الترخيص). الاتصال بخادم الترخيص اختياري ومعطّل حتى يُكتب عنوانه.
 */
public final class License {
    private License() {}

    static final String DEV = "smart_license_device_v1";
    static final String MAC = "quota_mac_v1";
    private static final String FILE = "license.txt";
    static final String REQ_PREFIX = "SA8-REQ:", REL_PREFIX = "SA8-REL:";

    /** ما يحق لهذه النسخة الآن. يُبنى عند الإقلاع ويُعاد بعد كل تفعيل أو تحرير. */
    public static volatile Entitlements current = Entitlements.free("");
    /** إشارات البيئة (للعرض وللتقدير، لا للإغلاق). */
    public static volatile String signals = "";
    public static volatile boolean signatureOk = true;
    /** 11.5: تعذّر فحص التوقيع (لا «مطابق» ولا «غير مطابق»): لا يُعدّ إثبات أصالة، ولا يحرم المشتري من رخصته. */
    public static volatile boolean signatureUnknown;

    // ===================================================================================
    // الإقلاع
    // ===================================================================================

    /** يُستدعى عند الإقلاع بعد فتح قاعدة البيانات. لا يرمي أبدًا. */
    public static synchronized void load(Context ctx, Db db) {
        try {
            int sig = signatureState(ctx);
            signatureOk = sig != SIG_MISMATCH;
            signatureUnknown = sig == SIG_UNKNOWN;
            if (signatureUnknown) {
                try { db.audit("LICENSE_SIG_UNKNOWN", "license", 0, "تعذّر التحقق من توقيع التطبيق عند الإقلاع"); } catch (Throwable ignored) { /* سجل */ }
            }
            signals = environmentSignals(ctx);
            String token = storedToken(ctx, db);
            if (token.isEmpty()) {
                current = Entitlements.free("");
                return;
            }
            byte[] dk = deviceKeyX509();
            LicenseToken.Grant g = LicenseToken.verify(token, dk);
            if (g == null) {
                current = Entitlements.free(dk == null
                        ? "رمز الترخيص المحفوظ يخص هاتفًا آخر أو مفتاح الجهاز غير موجود هنا (مثلًا بعد نقل البيانات إلى هاتف جديد). اطلب نقل الرخصة."
                        : "رمز الترخيص المحفوظ لا يطابق مفتاح هذا الهاتف. اطلب رمزًا جديدًا من البائع.");
                return;
            }
            if (revoked(db, g.lid())) {
                current = Entitlements.free("أُلغيت الرخصة " + g.lid() + " (مسرّبة أو منقولة). تواصل مع البائع.");
                return;
            }
            if (!signatureOk) {
                current = Entitlements.free("هذه نسخة من التطبيق ليست موقّعة من مطوّره (معدَّلة أو من مصدر مجهول)؛ الرخصة لا تعمل عليها. "
                        + "ثبّت النسخة الأصلية؛ بياناتك تنتقل بنسخة احتياطية.");
                return;
            }
            current = LicenseGuard.decide(ctx, db, g);   // 12.7: المكتب والوقت الموثوق
        } catch (Throwable t) {
            current = Entitlements.free("تعذر التحقق من الترخيص الآن (" + t.getClass().getSimpleName() + "). أعد فتح التطبيق؛ الإضافة متوقفة عند حدود المجاني فقط.");
        }
    }

    /** الرمز في موضعين (إعدادات الهاتف + ملف داخلي): يُقارَنان ويُرمَّم أحدهما من الآخر إن تلف. */
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

    /** بايتات X.509 لمفتاح هذا الهاتف، أو null قبل أول طلب تفعيل. */
    static byte[] deviceKeyX509() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Certificate c = ks.getCertificate(DEV);
        return c == null ? null : c.getPublicKey().getEncoded();
    }

    /** يُنشأ مرة واحدة عند أول طلب؛ التحدّي = رقم الطلب، فيرتبط المفتاح المصدَّق ببيعة واحدة. */
    private static void createDeviceKey(byte[] challenge) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        for (boolean strong : new boolean[]{true, false}) {
            try {
                KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(DEV, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAttestationChallenge(challenge);
                if (strong) b.setIsStrongBoxBacked(true);
                g.initialize(b.build());
                g.generateKeyPair();
                return;
            } catch (StrongBoxUnavailableException e) {
                // لا StrongBox في هذا الهاتف: العتاد الآمن العادي (TEE)
            }
        }
        throw new IllegalStateException("تعذر إنشاء مفتاح الجهاز");
    }

    /**
     * نص طلب التفعيل الذي يرسله العميل للبائع (واتساب أو بريد): رقم الطلب، اسم الحزمة، بصمة مفتاح الهاتف ومفتاحه العام،
     * وسلسلة شهادات التصديق التي يفحصها البائع في أداته (لا على الهاتف).
     */
    public static String requestText(Context ctx, Db db, String order) throws Exception {
        order = order == null ? "" : order.trim();
        if (order.isEmpty() || order.length() > 64) throw new IllegalArgumentException("اكتب رقم الطلب الذي أعطاك إياه البائع (حتى 64 حرفًا).");
        if (deviceKeyX509() == null) createDeviceKey(("SA8|" + order).getBytes(StandardCharsets.UTF_8));
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Certificate[] chain = ks.getCertificateChain(DEV);
        byte[] pub = deviceKeyX509();
        JSONObject j = new JSONObject();
        j.put("t", "sa-activate");
        j.put("v", 1);
        j.put("order", order);
        j.put("pkg", ctx.getPackageName());
        j.put("ver", BuildInfo.VERSION);
        j.put("build", BuildInfo.BUILD_DATE);
        j.put("dk", LicenseToken.deviceFingerprint(pub));
        j.put("pub", Base64.getEncoder().encodeToString(pub));
        JSONArray c = new JSONArray();
        if (chain != null) for (Certificate x : chain) c.put(Base64.getEncoder().encodeToString(x.getEncoded()));
        j.put("chain", c);
        j.put("model", Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        j.put("at", Clock.now());
        db.audit("LICENSE_REQUEST", "license", 0, order);
        // 11.5: مضغوطًا (أقصر بنحو الثلث)؛ أداة البائع تقرأ الصيغتين
        LicenseGuard.addToRequest(ctx, db, j);   // 12.7: هوية المكتب وسجل محاولات الاستخدام لمكتب آخر
        return LicenseToken.packRequest(j.toString());
    }

    /** يتحقق من الرمز لهذا الهاتف ثم يحفظه في موضعين. يرمي برسالة واضحة إن لم يصلح. */
    public static Entitlements activate(Context ctx, Db db, String token) throws Exception {
        token = LicenseToken.extract(token);   // 11.5: من ملف المفتاح أو رابط QR أو رسائل واتساب مقسّمة
        byte[] dk = deviceKeyX509();
        if (dk == null) throw new IllegalStateException("أنشئ طلب التفعيل أولًا من هذا الهاتف ثم أرسله للبائع.");
        LicenseToken.Grant g = LicenseToken.verify(token, dk);
        if (g == null) {
            String lid = LicenseToken.peek(token).get("lid");
            throw new IllegalArgumentException("الرمز غير صالح لهذا الهاتف" + (lid == null ? "" : " (" + lid + ")")
                    + ": تأكد أنك نسخته كاملًا وأنه صادر لطلب هذا الهاتف نفسه.");
        }
        save(ctx, db, token.replaceAll("\\s+", ""));
        db.audit("LICENSE_ACTIVATE", "license", 0, g.lid());
        load(ctx, db);
        return current;
    }

    /**
     * «إلغاء التفعيل» قبل نقل الرخصة: يوقّع إيصال تحرير (رقم الرخصة + بصمة المفتاح + تحدٍّ) بمفتاح الجهاز
     * ثم يحذف الرمز والمفتاح. لا يستطيع إنتاج الإيصال إلا حامل المفتاح الأصلي.
     */
    public static String release(Context ctx, Db db) throws Exception {
        Entitlements e = current;
        String token = storedToken(ctx, db);
        byte[] dk = deviceKeyX509();
        LicenseToken.Grant g = LicenseToken.verify(token, dk);
        if (g == null) throw new IllegalStateException("لا توجد رخصة مفعّلة على هذا الهاتف.");
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        String body = "v=1;t=release;lid=" + g.lid() + ";dk=" + LicenseToken.deviceFingerprint(dk) + ";n="
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + ";at=" + Clock.now();
        String sig = sign(body.getBytes(StandardCharsets.UTF_8));
        save(ctx, db, "");
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(DEV);
        db.audit("LICENSE_RELEASE", "license", 0, g.lid());
        load(ctx, db);
        return REL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "." + sig;
    }

    /** 12.7: توقيع بمفتاح الجهاز (سجل المحاولات). */
    static String signData(byte[] data) throws Exception { return sign(data); }

    private static String sign(byte[] data) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        PrivateKey k = (PrivateKey) ks.getKey(DEV, null);
        java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
        s.initSign(k);
        s.update(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }

    // ===================================================================================
    // بصمة عدّادات المجاني (HMAC بمفتاح Keystore)
    // ===================================================================================

    /** بصمة hex، أو «» إن تعذّر Keystore (فتُعامل العدّادات كغير موثوقة وتحكم أرضية البيانات الحية). */
    static String mac(String data) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(MAC)) {
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
                kg.init(new KeyGenParameterSpec.Builder(MAC, KeyProperties.PURPOSE_SIGN).build());
                kg.generateKey();
            }
            Mac m = Mac.getInstance("HmacSHA256");
            m.init((SecretKey) ks.getKey(MAC, null));
            return BackupFormat.hex(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    // ===================================================================================
    // سلامة الحزمة والبيئة
    // ===================================================================================

    static final int SIG_MATCH = 0, SIG_MISMATCH = 1, SIG_UNKNOWN = 2;

    /** 11.5: ثلاث نتائج: مطابق، غير مطابق، تعذّر التحقق (كان التعذّر يُعدّ مطابقًا). */
    static int signatureState(Context ctx) {
        try {
            return signatureMatchesOrThrow(ctx) ? SIG_MATCH : SIG_MISMATCH;
        } catch (Throwable t) {
            return SIG_UNKNOWN;
        }
    }

    /** للتوافق: «غير مطابق» فقط يعيد false. */
    static boolean signatureMatches(Context ctx) { return signatureState(ctx) != SIG_MISMATCH; }

    /** بصمة شهادة التوقيع تطابق شهادة المطوّر؟ نسخة معاد توقيعها تختلف. يرمي إن تعذّر الفحص. */
    static boolean signatureMatchesOrThrow(Context ctx) throws Exception {
        {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] sigs = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            if (sigs == null || sigs.length == 0) return false;
            for (Signature s : sigs) {
                String h = BackupFormat.hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())).toUpperCase(Locale.US);
                if (h.equals(BuildInfo.SIGNING_CERT_SHA256)) return true;
            }
            return false;
        }
    }

    /** إشارات للعرض فقط: لا حذف بيانات ولا إغلاق للتطبيق أبدًا؛ صاحب هاتف مروّت صادق لا يخسر دفاتره. */
    static String environmentSignals(Context ctx) {
        StringBuilder b = new StringBuilder();
        try {
            if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) b.append("نسخة تطوير قابلة للتصحيح؛ ");
            String fp = (Build.FINGERPRINT + " " + Build.MODEL + " " + Build.HARDWARE).toLowerCase(Locale.US);
            if (fp.contains("generic") || fp.contains("emulator") || fp.contains("goldfish") || fp.contains("ranchu") || fp.contains("sdk_gphone")) b.append("محاكٍ؛ ");
            for (String p : new String[]{"/system/xbin/su", "/system/bin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/adb/magisk"}) {
                if (new File(p).exists()) {
                    b.append("صلاحية الجذر (root)؛ ");
                    break;
                }
            }
            String maps = new String(Db.readAll(new java.io.FileInputStream("/proc/self/maps")), StandardCharsets.UTF_8);
            if (maps.contains("frida") || maps.contains("xposed") || maps.contains("lsposed") || maps.contains("substrate")) b.append("أدوات تعديل التطبيقات أثناء التشغيل؛ ");
        } catch (Throwable ignored) {
            // غير مهم
        }
        return b.toString().trim();
    }

    /** أدوات حقن أثناء التشغيل (لا مجرد root): تكفي لوضع علامة «نسخة مجانية» على التصدير حتى تحقق عبر الخادم. */
    static boolean highRisk() {
        return signals.contains("أدوات تعديل");
    }

    // ===================================================================================
    // الخادم الاختياري (مرحلة لاحقة): فحص دوري وقائمة إلغاء. معطّل حتى يُكتب عنوانه.
    // ===================================================================================

    /** هل حان موعد الفحص (كل 7 أيام)؟ لا يتعطل شيء إن فشل الاتصال أسابيع. */
    static boolean checkDue(Db db) {
        if (db.setting("license_server", "").trim().isEmpty() || !current.pro) return false;
        return Clock.now() - db.settingLong("license_last_check", 0) > 7L * 86400000L;
    }

    /**
     * يرسل رقم الرخصة وتحدّيًا عشوائيًا موقّعًا بمفتاح الجهاز (يثبت أنه الهاتف المسجَّل)، ويستقبل قائمة الإلغاء.
     * أي فشل في الاتصال يُتجاهل.
     */
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
