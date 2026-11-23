package com.smart.hawala;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 10.0: تشفير المزامنة (منطق بحت مشترك: الهاتف والماك والويندوز).
 *
 * <p>القرارات:
 * <ul>
 *   <li>مفتاح الخزنة (Vault Key) عشوائي 32 بايت، لا يخرج من الأجهزة إلا مغلّفًا: بمفتاح مشتق من كلمة سر المزامنة
 *       (PBKDF2-HMAC-SHA256، 600 ألف دورة، ملح 16 بايت) وبرمز استرداد عشوائي. تغيير كلمة السر يعيد التغليف فقط.
 *       (Argon2id غير متاح مدمجًا في Java/Android 9 دون مكتبة خارجية؛ PBKDF2 بهذا العدد هو توصية OWASP البديلة.)</li>
 *   <li>من مفتاح الخزنة تُشتق بـ HKDF-SHA256 مفاتيح فرعية: للتشفير، ولتعمية أسماء الملفات.</li>
 *   <li>كل ملف: AES-256-GCM، ورقم Nonce عشوائي 96 بت لكل ملف (لا عدّاد: استعادة نسخة قديمة للجهاز قد تعيد العدّاد فيتكرر
 *       الرقم مع المفتاح نفسه وهو خطأ قاتل في GCM؛ العشوائي آمن حتى مليارات الملفات). البيانات الإضافية (AAD) تربط
 *       الملف بالخزنة ونوعه واسمه فلا يُنقل ملف مكان آخر.</li>
 *   <li>المحتوى يُضغط ثم يُحشى ليكون حجم الملف مضاعفًا لـ 4KB فلا يكشف الحجم عدد الحركات.</li>
 *   <li>كل الأجهزة أعضاء موثوقون في الخزنة نفسها (أجهزة المكتب الواحد)، فالتوثيق بـ GCM بمفتاح الخزنة؛ وسلسلة التجزئة بين
 *       حزم كل جهاز تكشف الحذف وإعادة الترتيب.</li>
 * </ul>
 */
public final class SyncCrypto {
    private SyncCrypto() {}

    public static final int KDF_ITER = 600_000;
    /** 11.5: حدود عدد الدورات وطول الملح المقبولة من رأس الخزنة. */
    public static final int KDF_MIN = 100_000, KDF_MAX = 5_000_000;

    /** خطأ في الرأس نفسه (لا كلمة سر خاطئة): يُعرض نصه كما هو. */
    public static void checkKdf(int iter, byte[] salt) {
        if (iter < KDF_MIN || iter > KDF_MAX) throw new IllegalStateException("رأس خزنة المزامنة غير صالح (عدد دورات الاشتقاق " + iter + " خارج الحدود). اضغط «إصلاح» من جهاز منضم.");
        if (salt == null || salt.length < 16 || salt.length > 64) throw new IllegalStateException("رأس خزنة المزامنة غير صالح (طول الملح). اضغط «إصلاح» من جهاز منضم.");
    }
    static final int PAD = 4096;
    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static String hex(byte[] b) { return BackupFormat.hex(b); }

    public static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    public static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HKDF-SHA256 (RFC 5869) بطول 32 بايت. */
    public static byte[] hkdf(byte[] ikm, byte[] salt, String info) {
        byte[] prk = hmac(salt == null ? new byte[32] : salt, ikm);
        byte[] in = (info + "\u0001").getBytes(StandardCharsets.UTF_8);
        return hmac(prk, in);
    }

    public static byte[] pbkdf2(char[] password, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iter, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** AES-256-GCM: nonce(12) || ciphertext+tag. */
    public static byte[] seal(byte[] key, byte[] plain, byte[] aad) {
        try {
            byte[] nonce = random(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(12 + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** يفك ويتحقق؛ أي تغيير ولو بايت واحد (أو مفتاح/AAD خاطئ) = استثناء. */
    public static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        if (sealed == null || sealed.length < 28) throw new GeneralSecurityException("ملف قصير أو تالف");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(sealed, 12, sealed.length - 12);
    }

    // ===================================================================================
    // مفاتيح الخزنة
    // ===================================================================================

    /** مفاتيح الخزنة المشتقة. */
    public static final class Keys {
        public final byte[] vault, enc, name;
        public final String vaultId;

        public Keys(String vaultId, byte[] vault) {
            this.vaultId = vaultId;
            this.vault = vault;
            byte[] salt = vaultId.getBytes(StandardCharsets.UTF_8);
            this.enc = hkdf(vault, salt, "sa-sync-enc-v1");
            this.name = hkdf(vault, salt, "sa-sync-name-v1");
        }

        /** 10.2: رمز الكتابة على خادم المزامنة (مشتق من مفتاح الخزنة؛ الخادم يحفظ بصمته فقط ولا يرى المفتاح). */
        public String serverToken() { return hex(hkdf(vault, vaultId.getBytes(StandardCharsets.UTF_8), "sa-sync-server-v1")); }

        /** اسم ملف معتم ثابت: البادئة + أول 32 حرفًا من HMAC. */
        public String fileName(String prefix, String label) {
            return prefix + hex(hmac(name, label.getBytes(StandardCharsets.UTF_8))).substring(0, 32) + ".sab";
        }

        byte[] aad(String kind, String fileName) {
            return ("SA10|" + vaultId + "|" + kind + "|" + fileName).getBytes(StandardCharsets.UTF_8);
        }

        /** ملف مزامنة: ضغط ثم حشو لمضاعفات 4KB ثم AES-GCM مربوط باسمه ونوعه. */
        public byte[] sealFile(String kind, String fileName, byte[] json) {
            return SyncCrypto.seal(enc, pad(gzip(json)), aad(kind, fileName));
        }

        public byte[] openFile(String kind, String fileName, byte[] sealed) throws GeneralSecurityException, IOException {
            return gunzip(unpad(SyncCrypto.open(enc, sealed, aad(kind, fileName))));
        }
    }

    /** تغليف مفتاح الخزنة بمفتاح (من كلمة السر أو رمز الاسترداد). */
    public static String wrap(byte[] kek, byte[] vaultKey, String vaultId) {
        return hex(seal(kek, vaultKey, ("SA10-wrap|" + vaultId).getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] unwrap(byte[] kek, String wrapped, String vaultId) throws GeneralSecurityException {
        return open(kek, unhex(wrapped), ("SA10-wrap|" + vaultId).getBytes(StandardCharsets.UTF_8));
    }

    /** رمز استرداد: 24 حرفًا من أبجدية بلا التباس (O/0، I/1) في 6 مجموعات، ≈ 120 بت. */
    public static String newRecoveryCode() {
        final String abc = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            if (i > 0 && i % 4 == 0) b.append('-');
            b.append(abc.charAt(RNG.nextInt(abc.length())));
        }
        return b.toString();
    }

    public static String normalizeRecovery(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "").replace('O', '0').replace('I', '1');
    }

    // ===================================================================================
    // ضغط وحشو
    // ===================================================================================

    static byte[] gzip(byte[] b) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(o)) {
                z.write(b);
            }
            return o.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] gunzip(byte[] b) throws IOException {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(b)); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = z.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > 512L * 1024 * 1024) throw new IOException("ملف مزامنة أكبر من المسموح");
            }
            return o.toByteArray();
        }
    }

    /** [طول 4 بايت][المحتوى][أصفار] بحيث يصير الملف المشفّر (+28) مضاعفًا لـ 4KB. */
    static byte[] pad(byte[] b) {
        int need = 4 + b.length + 28;
        int total = ((need + PAD - 1) / PAD) * PAD - 28;
        return ByteBuffer.allocate(total).putInt(b.length).put(b).array();
    }

    static byte[] unpad(byte[] b) throws IOException {
        if (b.length < 4) throw new IOException("ملف مزامنة تالف");
        int n = ByteBuffer.wrap(b).getInt();
        if (n < 0 || n > b.length - 4) throw new IOException("ملف مزامنة تالف");
        byte[] out = new byte[n];
        System.arraycopy(b, 4, out, 0, n);
        return out;
    }
}
