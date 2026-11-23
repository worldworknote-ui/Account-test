package com.smart.hawala;

import java.security.SecureRandom;
import javax.crypto.spec.SecretKeySpec;

/**
 * 10.14: «رمز استرداد النسخ الاحتياطية». يُنشئه المدير مرة واحدة ويُعرض مرة واحدة (24 حرفًا). بعده تُكتب كل نسخة من هذا الجهاز
 * (التلقائية واليدوية) بالإصدار 2 من التشفير: تُفتح بكلمة مرورها أو بهذا الرمز، فنسيان كلمة مرور النسخ لا يُضيع النسخ.
 *
 * <p>لا يُحفظ الرمز نفسه أبدًا: يُحفظ المفتاح المشتق منه (وملحه) مشفّرًا بمفتاح هذا الجهاز، في إعدادات خاصة بالجهاز
 * (لا تُنقل بالمزامنة ولا داخل النسخ). من يملك الرمز يفتح النسخ؛ ومن لا يملكه ولا كلمة المرور لا يفتحها.
 * إيقافه أو إصداره من جديد لا يغيّر النسخ القديمة: تبقى تُفتح بالرمز الذي كُتبت به.
 */
public final class BackupRecovery {
    private BackupRecovery() {}

    static final String K_KEY = "backup_rc_key", K_SALT = "backup_rc_salt", K_ITER = "backup_rc_iter", K_AT = "backup_rc_at";
    public static final int ITERATIONS = 310000;

    public static boolean on(Db db) { return !db.setting(K_KEY, "").isEmpty() && !db.setting(K_SALT, "").isEmpty(); }

    /** تاريخ إنشاء الرمز الحالي (0 = لا رمز). */
    public static long createdAt(Db db) { return db.settingLong(K_AT, 0); }

    /** مفتاح الرمز لكتابة النسخ، أو null إن لم يُفعَّل (أو تعذّر فكّه على هذا الجهاز: تُكتب النسخة بكلمة المرور وحدها). */
    public static SecureStreams.RecoveryKey key(Db db) {
        if (!on(db)) return null;
        try {
            byte[] k = SyncCrypto.unhex(Crypto.dec(db.setting(K_KEY, "")));
            byte[] salt = SyncCrypto.unhex(db.setting(K_SALT, ""));
            int iter = (int) db.settingLong(K_ITER, ITERATIONS);
            if (k.length != 32 || salt.length != 16) return null;
            return new SecureStreams.RecoveryKey(salt, iter, new SecretKeySpec(k, "AES"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void requireAdmin(Db db) {
        Model.User me = db.actor();
        if (db.usersEnabled() && (me == null || !Auth.isAdmin(me))) throw new SecurityException("رمز استرداد النسخ للمدير وحده");
    }

    /** ينشئ رمزًا جديدًا (يحل محل السابق للنسخ القادمة) ويرجعه ليُعرض مرة واحدة. */
    public static String create(Db db) throws Exception {
        requireAdmin(db);
        String code = SyncCrypto.newRecoveryCode();
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        SecureStreams.RecoveryKey rk = SecureStreams.RecoveryKey.derive(code, salt, ITERATIONS);
        db.setSettingQuiet(K_KEY, Crypto.enc(SyncCrypto.hex(rk.key.getEncoded())));
        db.setSettingQuiet(K_SALT, SyncCrypto.hex(salt));
        db.setSettingQuiet(K_ITER, "" + ITERATIONS);
        db.setSettingQuiet(K_AT, "" + Clock.now());
        db.audit("BACKUP_RECOVERY", "backup", 0, "أُنشئ رمز استرداد للنسخ الاحتياطية");
        return code;
    }

    /** يوقف الرمز للنسخ القادمة (النسخ السابقة تبقى تُفتح به). */
    public static void disable(Db db) {
        requireAdmin(db);
        for (String k : new String[]{K_KEY, K_SALT, K_ITER, K_AT}) db.deleteSetting(k);
        db.audit("BACKUP_RECOVERY", "backup", 0, "أُوقف رمز استرداد النسخ الاحتياطية");
    }
}
