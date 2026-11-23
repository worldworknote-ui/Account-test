package com.smart.hawala;

import android.content.Context;
import android.database.Cursor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

/**
 * 12.6: تشفير ملف قاعدة البيانات كاملًا (SQLCipher 4، AES-256) على أندرويد. للكمبيوتر نسخة بالواجهة نفسها
 * (desktop/src/com/smart/hawala/DbCipher.java) فوق SQLite3MultipleCiphers.
 * <p>
 * القاعدة القديمة غير المشفّرة تُشفَّر مرة واحدة بأمان: تُنسخ إلى ملف جديد مشفّر (sqlcipher_export)، ويُتحقق أن كل جدول
 * فيه العدد نفسه من الصفوف، ثم يحل الجديد محل القديم. أي فشل يترك القديم كما هو ويُعاد المحاولة في التشغيل التالي.
 */
final class DbCipher {
    private DbCipher() {}

    private static volatile boolean loaded;

    static void load() {
        if (loaded) return;
        System.loadLibrary("sqlcipher");
        loaded = true;
    }

    /** رأس ملف SQLite العادي غير المشفّر. */
    static boolean isPlain(File f) {
        byte[] h = new byte[16];
        try (FileInputStream in = new FileInputStream(f)) {
            if (in.read(h) != 16) return false;
        } catch (IOException e) {
            return false;
        }
        return new String(h, 0, 15, java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3");
    }

    /** هل يُفتح الملف المشفّر بهذا المفتاح؟ */
    static boolean canOpen(File f, String key) {
        load();
        SQLiteDatabase d = null;
        try {
            d = SQLiteDatabase.openDatabase(f.getPath(), key, null, SQLiteDatabase.OPEN_READONLY, null, null);
            try (Cursor c = d.rawQuery("SELECT count(*) FROM sqlite_master", (String[]) null)) {
                return c.moveToFirst();
            }
        } catch (Throwable t) {
            return false;
        } finally {
            if (d != null) d.close();
        }
    }

    /** 12.7: على أندرويد تقرأ خيوط الخلفية من اتصالات القراءة تلقائيًا (WAL): لا شيء هنا. */
    static void preferReader(boolean on) { }

    /** 12.7: يفتح ملف قاعدة (نسخة للقراءة) بالمفتاح ("" = غير مشفّر). */
    static SQLiteDatabase openFile(File f, String key) {
        load();
        return SQLiteDatabase.openDatabase(f.getPath(), key == null ? "" : key, null, SQLiteDatabase.OPEN_READWRITE, null, null);
    }

    /** عدد صفوف كل جدول (للتحقق بعد التشفير). */
    private static Map<String, Long> counts(SQLiteDatabase d) {
        Map<String, Long> m = new LinkedHashMap<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", (String[]) null)) {
            while (c.moveToNext()) names.add(c.getString(0));
        }
        for (String n : names) {
            try (Cursor c = d.rawQuery("SELECT count(*) FROM \"" + n.replace("\"", "\"\"") + "\"", (String[]) null)) {
                m.put(n, c.moveToFirst() ? c.getLong(0) : -1);
            }
        }
        return m;
    }

    /** يشفّر قاعدة غير مشفّرة في مكانها. يرمي ويترك الأصل سليمًا إن فشل أي شيء. */
    static void encrypt(Context ctx, File f, String key) throws Exception {
        load();
        File tmp = new File(f.getPath() + ".enc-tmp");
        deleteDb(tmp);
        Map<String, Long> before;
        int version;
        SQLiteDatabase p = SQLiteDatabase.openDatabase(f.getPath(), "", null, SQLiteDatabase.OPEN_READWRITE, null, null);
        try {
            try (Cursor c = p.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", (String[]) null)) { c.moveToFirst(); }
            before = counts(p);
            version = p.getVersion();
            p.execSQL("ATTACH DATABASE '" + tmp.getPath().replace("'", "''") + "' AS enc KEY \"" + key + "\"");
            try (Cursor c = p.rawQuery("SELECT sqlcipher_export('enc')", (String[]) null)) { c.moveToFirst(); }
            p.execSQL("PRAGMA enc.user_version = " + version);
            p.execSQL("DETACH DATABASE enc");
        } finally {
            p.close();
        }
        SQLiteDatabase e = SQLiteDatabase.openDatabase(tmp.getPath(), key, null, SQLiteDatabase.OPEN_READWRITE, null, null);
        try {
            Map<String, Long> after = counts(e);
            if (!after.equals(before)) throw new IOException("التحقق بعد التشفير فشل: الجداول أو أعداد الصفوف لا تطابق");
            if (e.getVersion() != version) throw new IOException("التحقق بعد التشفير فشل: رقم المخطط");
            try (Cursor c = e.rawQuery("PRAGMA integrity_check", (String[]) null)) {
                if (!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0))) throw new IOException("فحص سلامة القاعدة المشفّرة فشل");
            }
        } finally {
            e.close();
        }
        File old = new File(f.getPath() + ".plain-old");
        deleteDb(old);
        if (!f.renameTo(old)) {
            deleteDb(tmp);
            throw new IOException("تعذر استبدال القاعدة بنسختها المشفّرة");
        }
        new File(f.getPath() + "-wal").delete();
        new File(f.getPath() + "-shm").delete();
        new File(f.getPath() + "-journal").delete();
        if (!tmp.renameTo(f)) {
            old.renameTo(f);   // رجوع للأصل
            throw new IOException("تعذر وضع القاعدة المشفّرة مكان القديمة");
        }
        deleteDb(old);
    }

    static void deleteDb(File f) {
        f.delete();
        new File(f.getPath() + "-wal").delete();
        new File(f.getPath() + "-shm").delete();
        new File(f.getPath() + "-journal").delete();
    }
}
