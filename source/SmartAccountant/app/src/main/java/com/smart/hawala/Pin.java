package com.smart.hawala;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * تجزئة رموز دخول المستخدمين (PBKDF2 + ملح عشوائي). الرمز نفسه لا يُحفظ أبدًا.
 * 8.0 (الصيغة v2): الرمز يمر أولًا بـ HMAC بسرّ («فلفل») محفوظ مشفّرًا بمفتاح الهاتف، ثم 120,000 دورة.
 * فمن ينسخ ملف قاعدة البيانات وحده لا يستطيع تجربة الرموز العشرة آلاف خارج الهاتف. السرّ ينتقل مع النسخة الاحتياطية
 * (المشفّرة بكلمة مرورها) فتعمل الرموز بعد نقل البيانات إلى هاتف جديد. رمز الاسترداد يبقى بالصيغة الأولى عمدًا
 * (عشوائيته نحو 79 بتًا) ليبقى باب الإنقاذ مفتوحًا حتى لو ضاع السرّ.
 */
public final class Pin {
    private static final int ITER = 30000;
    static final int ITER_V2 = 120000;
    static final String V2 = "v2:";

    private Pin() {}

    public static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return BackupFormat.hex(b);
    }

    public static String hash(String pin, String saltHex) {
        try {
            byte[] salt = new byte[saltHex.length() / 2];
            for (int i = 0; i < salt.length; i++) salt[i] = (byte) Integer.parseInt(saltHex.substring(2 * i, 2 * i + 2), 16);
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITER, 256);
            try {
                return BackupFormat.hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String pin, String saltHex, String expectedHex) {
        if (pin == null || saltHex == null || expectedHex == null) return false;
        return MessageDigest.isEqual(hash(pin, saltHex).getBytes(), expectedHex.getBytes());
    }

    /** الصيغة v2: «v2:» + PBKDF2(HMAC(السرّ، الرمز)، الملح، 120000). */
    public static String hashV2(String pin, String saltHex, byte[] pepper) {
        if (pepper == null || pepper.length < 16) throw new IllegalStateException("سرّ الرموز غير متاح");
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(pepper, "HmacSHA256"));
            String peppered = BackupFormat.hex(m.doFinal(pin.getBytes(StandardCharsets.UTF_8)));
            byte[] salt = new byte[saltHex.length() / 2];
            for (int i = 0; i < salt.length; i++) salt[i] = (byte) Integer.parseInt(saltHex.substring(2 * i, 2 * i + 2), 16);
            PBEKeySpec spec = new PBEKeySpec(peppered.toCharArray(), salt, ITER_V2, 256);
            try {
                return V2 + BackupFormat.hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** يتحقق من الصيغتين: v2 بالسرّ، والقديمة بلا سرّ (تُرقّى عند أول دخول ناجح). */
    public static boolean verifyAny(String pin, String saltHex, String expected, byte[] pepper) {
        if (pin == null || saltHex == null || expected == null) return false;
        if (expected.startsWith(V2)) {
            if (pepper == null) return false;
            return MessageDigest.isEqual(hashV2(pin, saltHex, pepper).getBytes(), expected.getBytes());
        }
        return verify(pin, saltHex, expected);
    }

    public static boolean needsUpgrade(String expected) { return expected != null && !expected.startsWith(V2); }

    public static byte[] newPepper() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** رمز استرداد المدير: 16 حرفًا ورقمًا بلا حروف ملتبسة، بصيغة XXXX-XXXX-XXXX-XXXX (نحو 79 بت). */
    public static String newRecoveryCode() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(CODE_CHARS.charAt(r.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** يوحّد رمز الاسترداد كما كتبه المستخدم: أحرف كبيرة، بلا مسافات أو شرطات، والأرقام العربية تُحوَّل. */
    public static String normalizeRecovery(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : code.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (ch >= 0x0660 && ch <= 0x0669) ch = (char) ('0' + ch - 0x0660);
            if (CODE_CHARS.indexOf(ch) >= 0) sb.append(ch);
        }
        return sb.toString();
    }

    /** رمز المدير: من 6 إلى 8 أرقام. */
    public static boolean validAdmin(String pin) {
        return pin != null && pin.matches("[0-9]{6,8}");
    }

    /** مدة الانتظار بعد محاولات خاطئة متتالية: لا شيء حتى 4، ثم 30 ثانية تتضاعف حتى ساعة. */
    public static long lockoutMillis(int fails) {
        if (fails < 5) return 0;
        long ms = 30000L << Math.min(fails - 5, 7);
        return Math.min(ms, 3600000L);
    }

    /** رمز من 4 إلى 8 أرقام. */
    public static boolean valid(String pin) {
        return pin != null && pin.matches("[0-9]{4,8}");
    }
}
