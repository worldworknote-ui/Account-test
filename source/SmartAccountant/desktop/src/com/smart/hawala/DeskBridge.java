package com.smart.hawala;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** جسر لواجهة الماك إلى ما هو محصور بالحزمة في الملفات المشتركة (دون تغيير صلاحياتها في تطبيق الهاتف). */
public final class DeskBridge {
    private DeskBridge() {}

    /** رمز استرداد صحيح: يُسمح بتغيير رمز المدير مرة واحدة قبل الدخول. */
    public static void recoveryGrant(boolean on) { Db.recoveryGrant = on; }

    public static byte[] readAll(File f) throws IOException { return Db.readAll(f); }

    public static void copyFile(File from, File to) throws IOException { Db.copyFile(from, to); }

    public static void sortOffices(List<Model.Office> l) { Db.sortOffices(l); }

    /** الإعدادات التي تخص هذا الجهاز ولا تنتقل مع النسخة. */
    public static Set<String> deviceSettings() { return Db.DEVICE_SETTINGS; }

    public static String requestPrefix() { return License.REQ_PREFIX; }
}
