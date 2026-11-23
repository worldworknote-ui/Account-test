package com.smart.hawala;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * تشفير الحقول الحساسة والمرفقات داخل الهاتف بمفتاح AES-GCM محفوظ في Android Keystore.
 * المفتاح لا يغادر الهاتف، لذلك تُفك البيانات عند إنشاء النسخة الاحتياطية (وتُحمى بكلمة مرور النسخة)،
 * ثم تُشفَّر من جديد بمفتاح الهاتف الجديد عند الاستعادة؛ وبهذا تصلح الاستعادة على هاتف آخر.
 */
public final class Crypto {
    /** النص الذي يظهر مكان أي حقل تعذّر فك تشفيره. */
    public static final String PLACEHOLDER = "[بيانات مشفرة غير قابلة للقراءة]";
    private static final String ALIAS = "smart_accountant_local_v1";
    private static SecretKey cachedKey;

    /** ذاكرة مؤقتة للحقول المفكوكة لتسريع الشاشات (فك التشفير عبر Keystore بطيء نسبيًا). */
    private static final int CACHE_MAX = 8000;
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > CACHE_MAX; }
    };

    private Crypto() {}

    /**
     * مفتاح البيانات للحقول النصية (انظر FieldCipher): يُقرأ مرة عند تشغيل التطبيق ويُفك بمفتاح Keystore.
     * null = غير متاح، فتُستخدم الطريقة القديمة (Keystore لكل حقل) كما كانت.
     */
    private static volatile SecretKey dek;
    static final String DEK_PREFS = "field_key", DEK_NAME = "dek_v2";
    /**
     * 8.0: إن كانت قاعدة البيانات أو مفتاح البيانات المغلَّف موجودين فمفتاح Keystore يجب أن يكون موجودًا.
     * قبلها كان ضياعه (إعادة ضبط، نقل بيانات التطبيق بأداة، عطل) يولّد مفتاحًا جديدًا بصمت، فتُكتب نسخ احتياطية
     * حقولها نصوص بديلة وتحل تدريجيًا محل آخر نسخة سليمة.
     */
    private static volatile boolean keyMustExist;

    /** يُستدعى مرة عند تشغيل التطبيق قبل فتح قاعدة البيانات. */
    public static synchronized void init(Context ctx) {
        if (dek != null) return;
        keyMustExist = ctx.getDatabasePath(Db.NAME).exists()
                || ctx.getSharedPreferences(DEK_PREFS, Context.MODE_PRIVATE).contains(DEK_NAME);
        try {
            SharedPreferences sp = ctx.getSharedPreferences(DEK_PREFS, Context.MODE_PRIVATE);
            String wrapped = sp.getString(DEK_NAME, null);
            if (wrapped != null) {
                // مفتاح موجود: لا يُستبدل أبدًا حتى لو تعذر فكه، وإلا ضاعت البيانات المشفرة به. نعيد المحاولة إن كان Keystore مشغولًا.
                Exception last = null;
                for (int i = 0; i < 3; i++) {
                    try {
                        dek = FieldCipher.key(Base64.decode(decLegacy(wrapped), Base64.NO_WRAP));
                        return;
                    } catch (Exception e) {
                        last = e;
                        Thread.sleep(150);
                    }
                }
                throw last;
            }
            SecretKey k = FieldCipher.newKey();
            String w = encLegacy(Base64.encodeToString(k.getEncoded(), Base64.NO_WRAP));
            if (!sp.edit().putString(DEK_NAME, w).commit()) return;
            // تحقق من الحفظ والفك قبل الاعتماد عليه
            String back = sp.getString(DEK_NAME, null);
            if (back == null || !back.equals(w)) return;
            byte[] check = Base64.decode(decLegacy(back), Base64.NO_WRAP);
            if (!java.util.Arrays.equals(check, k.getEncoded())) return;
            dek = k;
        } catch (Throwable t) {
            dek = null;
        }
    }

    /** 12.6: مفتاح قاعدة البيانات (SQLCipher) المشتق من مفتاح البيانات، أو null إن لم يتوفر. */
    public static String dbKey() { return FieldCipher.dbKey(dek); }

    /** هل الحقول الجديدة تُشفَّر بمفتاح البيانات السريع؟ */
    public static boolean fast() { return dek != null; }

    /** هل هذا النص المشفّر بالطريقة القديمة (Keystore لكل حقل)؟ */
    public static boolean isLegacy(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            byte[] in = Base64.decode(s, Base64.NO_WRAP);
            return in.length > 0 && !FieldCipher.isV2(in);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static synchronized SecretKey key() throws Exception {
        if (cachedKey != null) return cachedKey;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            if (keyMustExist) throw new IllegalStateException("مفتاح Keystore مفقود والبيانات المشفرة موجودة؛ لن يُولَّد مفتاح جديد");
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        cachedKey = ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        return cachedKey;
    }

    public static String enc(String s) {
        if (s == null) return null;
        SecretKey k = dek;
        if (k != null) {
            try {
                String r = FieldCipher.encrypt(k, s);
                synchronized (CACHE) { CACHE.put(r, s); }
                return r;
            } catch (Exception e) {
                // نادر جدًا: نعود للطريقة القديمة
            }
        }
        return encLegacy(s);
    }

    /** الطريقة القديمة: مفتاح Keystore مباشرة لكل حقل (تُستخدم أيضًا لحفظ مفتاح البيانات نفسه). */
    private static String encLegacy(String s) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] b = c.doFinal(s.getBytes(StandardCharsets.UTF_8));
            byte[] iv = c.getIV();
            byte[] out = new byte[1 + iv.length + b.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(b, 0, out, 1 + iv.length, b.length);
            String r = Base64.encodeToString(out, Base64.NO_WRAP);
            synchronized (CACHE) { CACHE.put(r, s); }
            return r;
        } catch (Exception e) {
            throw new IllegalStateException("تعذر تشفير البيانات على هذا الهاتف", e);
        }
    }

    /** يفك التشفير ويرمي استثناءً عند الفشل. */
    public static String decStrict(String s) throws Exception {
        if (s == null) return null;
        if (s.isEmpty()) return "";
        synchronized (CACHE) {
            String hit = CACHE.get(s);
            if (hit != null) return hit;
        }
        byte[] in = Base64.decode(s, Base64.NO_WRAP);
        String out;
        if (FieldCipher.isV2(in)) {
            SecretKey k = dek;
            if (k == null) throw new IllegalStateException("مفتاح البيانات غير متاح");
            out = FieldCipher.decrypt(k, in);
        } else out = legacyDecrypt(in);
        synchronized (CACHE) { CACHE.put(s, out); }
        return out;
    }

    private static String decLegacy(String s) throws Exception {
        return legacyDecrypt(Base64.decode(s, Base64.NO_WRAP));
    }

    private static String legacyDecrypt(byte[] in) throws Exception {
        int n = in[0] & 255;
        byte[] iv = new byte[n];
        System.arraycopy(in, 1, iv, 0, n);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(in, 1 + n, in.length - 1 - n), StandardCharsets.UTF_8);
    }

    /** للعرض: عند الفشل يرجع نصًا بديلًا بدل أن تنهار الشاشة. */
    public static String dec(String s) {
        if (s == null) return null;
        try {
            return decStrict(s);
        } catch (Exception e) {
            return PLACEHOLDER;
        }
    }

    public static byte[] encBytes(byte[] raw) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] b = c.doFinal(raw);
            byte[] iv = c.getIV();
            byte[] o = new byte[1 + iv.length + b.length];
            o[0] = (byte) iv.length;
            System.arraycopy(iv, 0, o, 1, iv.length);
            System.arraycopy(b, 0, o, 1 + iv.length, b.length);
            return o;
        } catch (Exception e) {
            throw new IllegalStateException("تعذر تشفير الصورة على هذا الهاتف", e);
        }
    }

    public static byte[] decBytes(byte[] in) {
        try {
            int n = in[0] & 255;
            byte[] iv = new byte[n];
            System.arraycopy(in, 1, iv, 0, n);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return c.doFinal(in, 1 + n, in.length - 1 - n);
        } catch (Exception e) {
            throw new IllegalStateException("تعذر فك تشفير الصورة", e);
        }
    }

    /**
     * فحص ذاتي عند كل تشغيل: نص معروف مشفّر عند أول تشغيل يُفك الآن. يكشف ضياع المفتاح فورًا
     * بدل اكتشافه من نصوص بديلة في الشاشات أو في نسخة احتياطية تالفة.
     */
    public static boolean healthy(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(DEK_PREFS, Context.MODE_PRIVATE);
        try {
            String canary = sp.getString("canary", null);
            if (canary == null) {
                String c = enc("ok");
                if (!"ok".equals(decStrict(c))) return false;
                sp.edit().putString("canary", c).commit();
                return true;
            }
            return "ok".equals(decStrict(canary));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * بعد ضياع المفتاح فقط، وبطلب صريح من المستخدم قبل استعادة نسخة احتياطية: يسمح بمفاتيح جديدة.
     * الحقول المشفرة القديمة غير مقروءة أصلًا؛ الاستعادة تكتب كل شيء من جديد بالمفتاح الجديد.
     */
    public static synchronized void resetForRestore(Context ctx) {
        ctx.getSharedPreferences(DEK_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        keyMustExist = false;
        dek = null;
        cachedKey = null;
        clearCache();
        init(ctx);
        keyMustExist = true;
    }

    public static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); }
    }
}
