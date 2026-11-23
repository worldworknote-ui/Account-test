package com.smart.hawala;

import android.content.Context;
import java.io.File;

/**
 * 10.4: إعادة تعيين البرنامج على هذا الجهاز فقط (مشترك: الهاتف والماك والويندوز).
 * <ul>
 *   <li>«حذف البيانات»: الحركات والمكاتب والصناديق والعملاء والصور والمستخدمون والإعدادات والنسخ الداخلية، وتبقى الرخصة.</li>
 *   <li>«حذف كل شيء»: ما سبق والرخصة أيضًا (يُفعَّل من جديد برمز التفعيل).</li>
 * </ul>
 * لا يُرسل الحذف إلى الأجهزة الأخرى أبدًا: تُحذف قاعدة البيانات نفسها لا صفوفها، فلا تلتقط المزامنة شيئًا.
 * النسخ الاحتياطية المحفوظة خارج البرنامج (في مجلد اخترته) لا تُمس.
 */
public final class AppReset {
    private AppReset() {}

    public static final String CONFIRM_WORD = "حذف";

    /** يحذف البيانات. بعده يجب إعادة تشغيل التطبيق (قاعدة جديدة فارغة). */
    public static void wipe(Context ctx, Db db, boolean keepLicense) {
        try {
            db.close();
        } catch (Throwable ignored) {
            // نكمل
        }
        File dbf = ctx.getDatabasePath(Db.NAME);
        File dir = dbf.getParentFile();
        File[] all = dir == null ? null : dir.listFiles();
        if (all != null) for (File f : all) deleteTree(f);
        String keep = keepLicense ? "license.txt" : null;
        File[] files = ctx.getFilesDir().listFiles();
        if (files != null) for (File f : files) if (keep == null || !f.getName().equals(keep)) deleteTree(f);
        File[] cache = ctx.getCacheDir().listFiles();
        if (cache != null) for (File f : cache) deleteTree(f);
        Crypto.clearCache();
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
