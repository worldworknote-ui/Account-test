package com.smart.hawala;

import android.content.Context;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * نسخة الماك من Crypto (الواجهة نفسها التي تستدعيها Db): مفتاح البيانات AES-256 في سلسلة مفاتيح الماك،
 * والحقول الحساسة بصيغة FieldCipher v2 نفسها التي في الهاتف. المفتاح لا يدخل النسخة الاحتياطية:
 * النسخة تحمل الحقول مفكوكة داخل ملف مشفّر بكلمة مرور، وتُشفَّر من جديد بمفتاح الجهاز عند الاستعادة (هاتفًا كان أو ماكًا).
 */
public final class Crypto {
    /** اسم ملف المفتاح في أندرويد (لا يُستعمل على الكمبيوتر: المفتاح في سلسلة المفاتيح). */
    static final String DEK_PREFS = "field_key";
    public static final String PLACEHOLDER = "[بيانات مشفرة غير قابلة للقراءة]";
    static final String DEK_NAME = "dek_v2", CANARY = "canary";

    private static final int CACHE_MAX = 20000;
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(1024, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > CACHE_MAX; }
    };

    private static volatile SecretKey dek;
    /** آخر سبب لعدم توفر المفتاح (للعرض). */
    public static volatile String problem = "";

    private Crypto() {}

    /**
     * يُستدعى مرة عند التشغيل قبل فتح القاعدة. إن كانت القاعدة موجودة والمفتاح مفقودًا لا يُولَّد مفتاح جديد
     * (وإلا كُتبت نسخ احتياطية بحقول بديلة)؛ تظهر شاشة «مفتاح التشفير غير متاح» وتبقى الاستعادة من نسخة ممكنة.
     */
    public static synchronized void init(Context ctx) {
        if (dek != null) return;
        problem = "";
        boolean dbExists = ctx.getDatabasePath(Db.NAME).exists();
        try {
            String s = SecretStore.get(DEK_NAME);
            if (s != null) {
                dek = FieldCipher.key(Base64.getDecoder().decode(s));
                return;
            }
            if (dbExists) {
                problem = "قاعدة البيانات موجودة لكن مفتاحها غير موجود في سلسلة المفاتيح لهذا المستخدم.";
                return;
            }
            SecretKey k = FieldCipher.newKey();
            String w = Base64.getEncoder().encodeToString(k.getEncoded());
            SecretStore.put(DEK_NAME, w);
            String back = SecretStore.get(DEK_NAME);
            if (!w.equals(back)) {
                problem = "تعذر التحقق من حفظ المفتاح في سلسلة المفاتيح.";
                return;
            }
            dek = k;
        } catch (Throwable t) {
            problem = "تعذر الوصول إلى سلسلة المفاتيح: " + t.getMessage();
            dek = null;
        }
    }

    public static boolean fast() { return dek != null; }

    /** 12.6: مفتاح قاعدة البيانات (SQLCipher) المشتق من مفتاح البيانات، أو null إن لم يتوفر. */
    public static String dbKey() { return FieldCipher.dbKey(dek); }

    /** لا توجد في الماك حقول بالطريقة القديمة. */
    public static boolean isLegacy(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            byte[] in = Base64.getDecoder().decode(s);
            return in.length > 0 && !FieldCipher.isV2(in);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static SecretKey key() {
        SecretKey k = dek;
        if (k == null) throw new IllegalStateException("مفتاح البيانات غير متاح على هذا الجهاز" + (problem.isEmpty() ? "" : ": " + problem));
        return k;
    }

    public static String enc(String s) {
        if (s == null) return null;
        try {
            String r = FieldCipher.encrypt(key(), s);
            synchronized (CACHE) { CACHE.put(r, s); }
            return r;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("تعذر تشفير البيانات على هذا الجهاز", e);
        }
    }

    public static String decStrict(String s) throws Exception {
        if (s == null) return null;
        if (s.isEmpty()) return "";
        synchronized (CACHE) {
            String hit = CACHE.get(s);
            if (hit != null) return hit;
        }
        byte[] in = Base64.getDecoder().decode(s);
        if (!FieldCipher.isV2(in)) throw new IllegalStateException("حقل بصيغة لا تخص هذا الجهاز");
        String out = FieldCipher.decrypt(key(), in);
        synchronized (CACHE) { CACHE.put(s, out); }
        return out;
    }

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
            throw new IllegalStateException("تعذر تشفير الصورة على هذا الجهاز", e);
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

    /** فحص ذاتي: نص معروف شُفّر أول مرة يُفك الآن. */
    public static boolean healthy(Context ctx) {
        try {
            String canary = SecretStore.get(CANARY);
            if (canary == null) {
                String c = enc("ok");
                if (!"ok".equals(decStrict(c))) return false;
                SecretStore.put(CANARY, c);
                return true;
            }
            synchronized (CACHE) { CACHE.remove(canary); }
            return "ok".equals(decStrict(canary));
        } catch (Throwable e) {
            return false;
        }
    }

    /** بعد ضياع المفتاح فقط، وبطلب صريح قبل استعادة نسخة: مفتاح جديد (الحقول القديمة غير مقروءة أصلًا). */
    public static synchronized void resetForRestore(Context ctx) {
        try {
            SecretStore.delete(DEK_NAME);
            SecretStore.delete(CANARY);
        } catch (Exception ignored) {
            // يُكتب فوقه
        }
        dek = null;
        clearCache();
        problem = "";
        try {
            SecretKey k = FieldCipher.newKey();
            SecretStore.put(DEK_NAME, Base64.getEncoder().encodeToString(k.getEncoded()));
            dek = k;
        } catch (Throwable t) {
            problem = "تعذر إنشاء مفتاح جديد: " + t.getMessage();
        }
    }

    public static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); }
    }
}
