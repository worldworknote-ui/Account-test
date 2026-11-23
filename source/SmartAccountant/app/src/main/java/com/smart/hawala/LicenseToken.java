package com.smart.hawala;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * التحقق من رمز الترخيص: منطق بحت بلا Android حتى يُختبر على الكمبيوتر.
 *
 * الرمز = Base64url(payload) + "." + Base64url(توقيع ECDSA P-256 بترميز DER).
 * payload = حقول مفتاح=قيمة بينها «;»: v=1، lid رقم الرخصة، ed الإصدار التجاري، ft الميزات، uu آخر تاريخ بناء
 * مشمول بالتحديثات (yyyymmdd)، dk بصمة SHA-256 لمفتاح الهاتف العام (Base64url)، kid معرّف مفتاح التوقيع.
 * المفتاح الخاص عند البائع وحده (أداة الإصدار على جهاز غير متصل)، والتطبيق يحمل المفتاحين العامين فقط:
 * يستطيع التأكد أن الرمز صادر من البائع ولا يستطيع أحد إصدار رمز جديد دونه ولو قرأ الشيفرة كلها.
 */
public final class LicenseToken {
    private LicenseToken() {}

    /** المفاتيح العامة المضمّنة: kid ← X.509 بترميز Base64. k2 احتياطي إن تسرّب k1 (تُعاد الرخص بتوقيعه ثم يُسحب k1). */
    public static final Map<String, String> TRUSTED;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("k1", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8zlFFLS9w6SXiVoxS62RhtEy2q32XDWj96nAFfvRleporJR5ugSQC4a1tjHVNTt7mFDSOPUcYLb+jYS6AR10hg==");
        m.put("k2", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEj9ACjhyMfDIK0u2pkPs517eT7AyTC+5TswYFs2mECyytnLgkRQV6TzTEs9Zy3icPNhKqCjjLZj9Ga9xAjghi6Q==");
        TRUSTED = java.util.Collections.unmodifiableMap(m);
    }

    public static final class Grant {
        private final Map<String, String> f;

        Grant(Map<String, String> f) { this.f = f; }

        public String lid() { return f.getOrDefault("lid", ""); }

        public String get(String key) { return f.getOrDefault(key, ""); }

        /** «all» = كل الميزات المدفوعة. */
        public boolean has(String feature) {
            String ft = "," + f.getOrDefault("ft", "") + ",";
            return ft.contains(",all,") || ft.contains("," + feature + ",");
        }

        public long num(String key) {
            try {
                return Long.parseLong(f.getOrDefault(key, "0"));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** هل هذا البناء ضمن فترة التحديثات؟ buildDate مثل 20261014 يُضمَّن وقت البناء، لا من ساعة الهاتف. */
        public boolean coversBuild(int buildDate) { return num("uu") >= buildDate; }
    }

    /** بصمة مفتاح الهاتف كما تُكتب في الرمز (dk). */
    public static String deviceFingerprint(byte[] deviceKeyX509) throws Exception {
        return new String(Base64.getUrlEncoder().withoutPadding().encode(MessageDigest.getInstance("SHA-256").digest(deviceKeyX509)), StandardCharsets.US_ASCII);
    }

    /** يرجع null إن لم يصحّ الرمز لهذا الهاتف (توقيع، مفتاح موقّع مجهول، هاتف آخر، رمز مبتور أو فاسد). */
    public static Grant verify(String token, byte[] deviceKeyX509) {
        return verify(token, deviceKeyX509, TRUSTED);
    }

    /** بمفاتيح موثوقة محددة (الاختبارات تمرّر مفتاحًا مؤقتًا؛ التطبيق يستعمل المضمّنة فقط). */
    public static Grant verify(String token, byte[] deviceKeyX509, Map<String, String> trusted) {
        try {
            if (token == null || deviceKeyX509 == null) return null;
            token = token.replaceAll("\\s+", "");
            int dot = token.indexOf('.');
            if (dot < 1 || dot != token.lastIndexOf('.') || dot == token.length() - 1) return null;
            Base64.Decoder u = Base64.getUrlDecoder();
            byte[] payload = u.decode(token.substring(0, dot));
            byte[] sig = u.decode(token.substring(dot + 1));
            Map<String, String> f = parse(new String(payload, StandardCharsets.UTF_8));
            if (f == null) return null;
            String pub = f.containsKey("kid") ? trusted.get(f.get("kid")) : null;
            if (pub == null || !"1".equals(f.get("v"))) return null;
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pub))));
            s.update(payload);
            if (!s.verify(sig)) return null;
            byte[] want = deviceFingerprint(deviceKeyX509).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(want, f.getOrDefault("dk", "").getBytes(StandardCharsets.US_ASCII))) return null;
            if (f.getOrDefault("lid", "").isEmpty()) return null;
            return new Grant(f);
        } catch (Exception e) {
            return null;
        }
    }

    /** قراءة الحقول دون تحقق (للعرض في رسالة خطأ فقط، لا للتفعيل). */
    public static Map<String, String> peek(String token) {
        try {
            String t = token.replaceAll("\\s+", "");
            int dot = t.indexOf('.');
            Map<String, String> m = parse(new String(Base64.getUrlDecoder().decode(dot > 0 ? t.substring(0, dot) : t), StandardCharsets.UTF_8));
            return m == null ? new HashMap<>() : m;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * 11.5: طلب التفعيل مضغوطًا (SA8-REQZ:): JSON مضغوط deflate ثم Base64url — أقصر بنحو الثلث من SA8-REQ: فلا ينقسم بسهولة.
     * أداة البائع تقرأ الصيغتين.
     */
    public static String packRequest(String json) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true);
        d.setInput(json.getBytes(StandardCharsets.UTF_8));
        d.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return "SA8-REQZ:" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /**
     * 11.5: يستخرج رمز الترخيص من أي نص: ملف المفتاح (.sakey)، رابط smartacc://activate?k=…، رسالة البائع كاملة،
     * أو أجزاء منسوخة من عدة رسائل واتساب (تُحذف بادئات «[التاريخ، الوقت] الاسم:» وتُجمع الأجزاء).
     */
    public static String extract(String raw) {
        if (raw == null) return "";
        String s = raw.replace("SMARTACC-LICENSE", " ");
        int k = s.indexOf("activate?k=");
        if (k >= 0) {
            s = s.substring(k + 11);
            try {
                s = java.net.URLDecoder.decode(s, "UTF-8");
            } catch (Exception ignored) {
                // كما هو
            }
        }
        // بادئات واتساب في أول كل سطر
        s = s.replaceAll("(?m)^\\s*\\[?\\d{1,4}[/.\\-]\\d{1,2}(?:[/.\\-]\\d{1,4})?,?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?[APap]\\.?[Mm]\\.?)?\\]?\\s*(?:-\\s*)?[^:\\n]{1,40}:\\s", "");
        String joined = s.replaceAll("\\s+", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{20,}").matcher(joined);
        String best = null;
        while (m.find()) if (best == null || m.group().length() > best.length()) best = m.group();
        return best != null ? best : raw.replaceAll("\\s+", "");
    }

    /** null إن تكرر حقل (لا لبس بين ما تعرضه أداة البائع وما يقرؤه التطبيق). */
    static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i > 0 && m.put(kv.substring(0, i), kv.substring(i + 1)) != null) return null;
        }
        return m;
    }
}
