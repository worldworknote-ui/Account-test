package com.smart.hawala;

import android.content.Context;

/** 12.7: جسر للشكل القديم على الكمبيوتر (حزمة desk) إلى حماية الرخصة المشتركة. */
public final class LicenseScreenFlags {
    private LicenseScreenFlags() {}

    /** خادم الترخيص مخفي؛ الكود باقٍ. */
    public static final boolean SHOW_SERVER = false;

    public static long lastTrusted(Db db) { return LicenseGuard.lastTrusted(db); }

    public static String officeUid(Db db) { return LicenseGuard.officeUid(db); }

    public static String note() { return LicenseGuard.timeNote; }

    public static Boolean fetch(Context ctx, Db db) {
        boolean ok = LicenseGuard.fetchTime(db) > 0;
        License.load(ctx, db);
        return ok;
    }

    public static void refresh(Context ctx, Db db) { LicenseGuard.refreshAsync(ctx, db); }
}
