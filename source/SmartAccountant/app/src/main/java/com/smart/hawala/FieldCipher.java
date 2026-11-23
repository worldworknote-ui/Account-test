package com.smart.hawala;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * تشفير الحقول النصية بمفتاح بيانات (AES-256-GCM) يعمل داخل التطبيق، والمفتاح نفسه محفوظ مشفّرًا بمفتاح Android Keystore.
 * السبب: كل عملية عبر Keystore تمر بخدمة النظام وتستغرق أجزاء من الثانية، فقراءة آلاف الحركات كانت تجمّد الشاشة؛
 * بهذا المفتاح تصبح العملية بالميكروثانية مع بقاء المفتاح محميًا بالعتاد وهو مخزن.
 * الصيغة: البايت الأول 0xCC (القديمة تبدأ بطول IV = 12) ثم IV من 12 بايت ثم النص المشفّر مع وسم 16 بايت، بترميز Base64.
 * منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر.
 */
public final class FieldCipher {
    public static final int V2 = 0xCC;
    private static final int IV = 12;
    private static final SecureRandom RNG = new SecureRandom();

    private FieldCipher() {}

    /**
     * 12.6: مفتاح تشفير قاعدة البيانات كاملة (SQLCipher) مشتق من مفتاح البيانات نفسه (HMAC-SHA256)، فلا يُحفظ مفتاح ثانٍ
     * ولا تنشأ طريقة جديدة لضياعه: القاعدة تُفتح حيث تُفك الحقول. الصيغة «x'…'» مفتاح خام 256 بت (بلا PBKDF2 بطيء).
     */
    public static String dbKey(SecretKey dek) {
        if (dek == null) return null;
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(dek.getEncoded(), "HmacSHA256"));
            byte[] k = m.doFinal("smart-accountant/sqlcipher/v1".getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("x'");
            for (byte b : k) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return sb.append('\'').toString();
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    public static SecretKey newKey() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }

    public static SecretKey key(byte[] raw) {
        if (raw == null || raw.length != 32) throw new IllegalArgumentException("مفتاح غير صالح");
        return new SecretKeySpec(raw, "AES");
    }

    public static String encrypt(SecretKey k, String plain) throws GeneralSecurityException {
        byte[] iv = new byte[IV];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[1 + IV + ct.length];
        out[0] = (byte) V2;
        System.arraycopy(iv, 0, out, 1, IV);
        System.arraycopy(ct, 0, out, 1 + IV, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    /** هل هذه البايتات (بعد فك Base64) بالصيغة الجديدة؟ */
    public static boolean isV2(byte[] in) {
        return in != null && in.length >= 1 + IV + 16 && (in[0] & 255) == V2;
    }

    public static String decrypt(SecretKey k, byte[] in) throws GeneralSecurityException {
        if (!isV2(in)) throw new GeneralSecurityException("ليست بالصيغة الجديدة");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, in, 1, IV));
        return new String(c.doFinal(in, 1 + IV, in.length - 1 - IV), StandardCharsets.UTF_8);
    }

    public static boolean isV2(String base64) {
        if (base64 == null || base64.isEmpty()) return false;
        try {
            return isV2(Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
