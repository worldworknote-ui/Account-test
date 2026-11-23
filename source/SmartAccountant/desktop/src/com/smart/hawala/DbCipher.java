package com.smart.hawala;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 12.6: تشفير ملف قاعدة البيانات كاملًا على ويندوز والماك (SQLite3MultipleCiphers بصيغة SQLCipher 4)، بالواجهة نفسها
 * لنسخة أندرويد (app/.../DbCipher.java). القاعدة القديمة تُشفَّر مرة واحدة: نسخة أمان من الملف، ثم «rekey» في مكانه،
 * ثم تحقق من كل الجداول وأعداد صفوفها؛ أي فشل يعيد الملف الأصلي كما كان.
 */
final class DbCipher {
    private DbCipher() {}

    static boolean isPlain(File f) {
        byte[] h = new byte[16];
        try (FileInputStream in = new FileInputStream(f)) {
            if (in.read(h) != 16) return false;
        } catch (IOException e) {
            return false;
        }
        return new String(h, 0, 15, java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3");
    }

    private static Connection open(File f, String key) throws Exception {
        Class.forName("org.sqlite.JDBC");
        if (key == null || key.isEmpty()) return DriverManager.getConnection("jdbc:sqlite:" + f.getPath());
        org.sqlite.mc.SQLiteMCSqlCipherConfig mc = org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults();
        mc.withKey(key);
        return mc.build().createConnection("jdbc:sqlite:" + f.getPath());
    }

    /** على الكمبيوتر المكتبة جزء من sqlite-jdbc وتُحمَّل معه: لا شيء هنا (نظير أندرويد). */
    static void load() { }

    /** 12.7: قراءات هذا الخيط من اتصال القراءة الثاني (للفحص وأعمال الخلفية الطويلة). */
    static void preferReader(boolean on) { net.zetetic.database.sqlcipher.SQLiteDatabase.preferReader(on); }

    /** 12.7: يفتح ملف قاعدة (نسخة للقراءة) بالمفتاح ("" = غير مشفّر). */
    static net.zetetic.database.sqlcipher.SQLiteDatabase openFile(File f, String key) {
        return net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(f.getPath(), key == null ? "" : key);
    }

    static boolean canOpen(File f, String key) {
        try (Connection c = open(f, key); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM sqlite_master")) {
            return r.next();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Map<String, Long> counts(Connection c) throws Exception {
        Map<String, Long> m = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (r.next()) names.add(r.getString(1));
        }
        for (String n : names) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM \"" + n.replace("\"", "\"\"") + "\"")) {
                m.put(n, r.next() ? r.getLong(1) : -1);
            }
        }
        return m;
    }

    private static int version(Connection c) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    static void encrypt(Context ctx, File f, String key) throws Exception {
        Map<String, Long> before;
        int ver;
        // 1) الملف العادي: يُفرغ سجل WAL فيه ويُغلق (حتى تكون النسخة والتشفير على ملف واحد كامل)
        try (Connection c = open(f, "")) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                s.execute("PRAGMA journal_mode=DELETE");
            }
            before = counts(c);
            ver = version(c);
        }
        File safety = new File(f.getPath() + ".pre-encrypt");
        Files.copy(f.toPath(), safety.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            // 2) التشفير في المكان
            org.sqlite.mc.SQLiteMCSqlCipherConfig mc = org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults();
            try (Connection c = mc.build().createConnection("jdbc:sqlite:" + f.getPath()); Statement s = c.createStatement()) {
                s.execute("PRAGMA rekey=\"" + key + "\"");
            }
            if (isPlain(f)) throw new IOException("بقي الملف غير مشفّر بعد rekey");
            // 3) التحقق
            try (Connection c = open(f, key)) {
                if (!counts(c).equals(before)) throw new IOException("التحقق بعد التشفير فشل: الجداول أو أعداد الصفوف لا تطابق");
                if (version(c) != ver) throw new IOException("التحقق بعد التشفير فشل: رقم المخطط");
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
                    if (!r.next() || !"ok".equalsIgnoreCase(r.getString(1))) throw new IOException("فحص سلامة القاعدة المشفّرة فشل");
                }
            }
        } catch (Exception e) {
            // رجوع للأصل كما كان
            new File(f.getPath() + "-wal").delete();
            new File(f.getPath() + "-shm").delete();
            new File(f.getPath() + "-journal").delete();
            Files.copy(safety.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            safety.delete();
            throw e;
        }
        safety.delete();
    }

    static void deleteDb(File f) {
        f.delete();
        new File(f.getPath() + "-wal").delete();
        new File(f.getPath() + "-shm").delete();
        new File(f.getPath() + "-journal").delete();
    }
}
