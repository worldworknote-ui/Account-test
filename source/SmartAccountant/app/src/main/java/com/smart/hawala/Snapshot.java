package com.smart.hawala;

import android.content.Context;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * نسخ أمان محلية تُؤخذ تلقائيًا قبل كل عملية كبيرة (استعادة، إضافة ملف نقل، حذف مكتب): ملف قاعدة البيانات + الصور.
 * 8.0: ثلاث خانات تدور (الأحدث أولًا)، فيمكن التراجع عن آخر ثلاث عمليات. الجديدة تُكتب كاملة أولًا ثم تُدار الخانات
 * بإعادة تسمية، فإن فشلت خطوة أُعيدت الخانات كما كانت ولا يبقى المستخدم بلا نسخة أمان.
 * الحقول فيها مشفّرة بمفتاح هذا الهاتف، فهي تصلح على هذا الهاتف فقط.
 */
public final class Snapshot {
    private Snapshot() {}

    public static final int SLOTS = 3;

    /** الخانة i (0 = الأحدث): pre_restore، pre_restore_2، pre_restore_3. */
    static File slot(Context c, int i) { return new File(c.getFilesDir(), i == 0 ? "pre_restore" : "pre_restore_" + (i + 1)); }

    public static File dir(Context c) { return slot(c, 0); }

    public static final class Info {
        public int slot;
        public long time;
        public String label = "";
    }

    static boolean valid(File d) { return new File(d, Db.NAME).exists() && new File(d, "info.txt").exists(); }

    /** نسخ الأمان الموجودة، الأحدث أولًا. */
    public static List<Info> list(Context c) {
        List<Info> l = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            File d = slot(c, i);
            if (!valid(d)) continue;
            Info x = new Info();
            x.slot = i;
            x.time = new File(d, "info.txt").lastModified();
            x.label = label(d);
            l.add(x);
        }
        return l;
    }

    public static boolean exists(Context c) { return valid(slot(c, 0)); }

    public static long time(Context c) {
        File f = new File(slot(c, 0), "info.txt");
        return f.exists() ? f.lastModified() : 0;
    }

    /** وصف نسخة الأمان: «استعادة» أو «حذف مكتب …». */
    public static String label(Context c) { return label(slot(c, 0)); }

    private static String label(File d) {
        try {
            String t = new String(Db.readAll(new File(d, "info.txt")), StandardCharsets.UTF_8);
            int i = t.indexOf('\n');
            return i < 0 ? "" : t.substring(i + 1).trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static void take(Context ctx, Db db) throws IOException { take(ctx, db, ""); }

    public static void take(Context ctx, Db db, String label) throws IOException {
        Db.stopMaintenance = true;
        synchronized (Db.MAINT_LOCK) {
            // تنتظر انتهاء أي دفعة تحويل تشفير جارية قبل نسخ قاعدة البيانات
        }
        File tmp = new File(ctx.getFilesDir(), "pre_restore_tmp");
        deleteTree(tmp);
        File ev = new File(tmp, "evidence");
        if (!ev.mkdirs()) throw new IOException("تعذر إنشاء مجلد نسخة الأمان");
        db.checkpoint();
        File dbFile = ctx.getDatabasePath(Db.NAME);
        Db.copyFile(dbFile, new File(tmp, Db.NAME));
        File[] imgs = db.evidenceDir().listFiles();
        if (imgs != null) for (File f : imgs) if (f.isFile()) BackupManager.linkOrCopy(f, new File(ev, f.getName()));
        try (FileOutputStream o = new FileOutputStream(new File(tmp, "info.txt"))) {
            o.write(("snapshot " + Clock.now() + "\n" + (label == null ? "" : label)).getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        rotateIn(ctx, tmp);
    }

    /**
     * تدوير آمن: الأقدم يُنقل جانبًا (لا يُحذف بعد)، ثم تُزاح الخانات، ثم تدخل الجديدة. فشل أي خطوة يعيد كل شيء.
     * الأقدم يُحذف في النهاية فقط بعد نجاح الإدخال.
     */
    static void rotateIn(Context ctx, File fresh) throws IOException {
        File drop = new File(ctx.getFilesDir(), "pre_restore_drop");
        deleteTree(drop);
        List<File[]> done = new ArrayList<>();   // {من، إلى} لكل نقل نجح، للتراجع بالعكس
        try {
            File last = slot(ctx, SLOTS - 1);
            if (last.exists()) move(last, drop, done);
            for (int i = SLOTS - 2; i >= 0; i--) {
                File s = slot(ctx, i);
                if (s.exists()) move(s, slot(ctx, i + 1), done);
            }
            move(fresh, slot(ctx, 0), done);
        } catch (IOException e) {
            for (int i = done.size() - 1; i >= 0; i--) done.get(i)[1].renameTo(done.get(i)[0]);
            throw e;
        }
        deleteTree(drop);
    }

    private static void move(File from, File to, List<File[]> done) throws IOException {
        deleteTree(to);
        if (!from.renameTo(to)) throw new IOException("تعذر حفظ نسخة الأمان (نقل " + from.getName() + ")");
        done.add(new File[]{from, to});
    }

    /** يعيد البيانات كما كانت قبل آخر عملية كبيرة. */
    public static Db revert(Context ctx, Db current) throws IOException { return revert(ctx, current, 0); }

    /**
     * يعيد البيانات كما كانت في نسخة الأمان رقم slot. يغلق قاعدة البيانات ويستبدل ملفها ثم يعيد فتحها.
     * 8.0: الإقفال المحاسبي لا يُفك بالتراجع (يبقى الأحدث)، وسجل العمليات الحالي يُضاف إلى أرشيف السجل لا يضيع.
     * @return قاعدة البيانات الجديدة المفتوحة
     */
    public static Db revert(Context ctx, Db current, int slot) throws IOException {
        File dir = slot(ctx, slot);
        File snapDb = new File(dir, Db.NAME);
        if (!valid(dir)) throw new IOException("لا توجد نسخة أمان سابقة للاستعادة");
        String lockNow = current.setting("lock_before", "0");
        // نحفظ الوضع الحالي أولًا، حتى يمكن الرجوع إليه إن فشل التراجع (ومنه يُنقل سجل العمليات إلى الأرشيف)
        File backupNow = new File(ctx.getFilesDir(), "pre_revert");
        deleteTree(backupNow);
        backupNow.mkdirs();
        current.checkpoint();
        File dbFile = ctx.getDatabasePath(Db.NAME);
        File nowCopy = new File(backupNow, Db.NAME);
        Db.copyFile(dbFile, nowCopy);

        current.close();
        new File(dbFile.getPath() + "-wal").delete();
        new File(dbFile.getPath() + "-shm").delete();
        new File(dbFile.getPath() + "-journal").delete();
        try {
            Db.copyFile(snapDb, dbFile);
        } catch (IOException e) {
            Db.copyFile(nowCopy, dbFile);
            throw e;
        }
        File evidence = new File(ctx.getFilesDir(), "evidence");
        evidence.mkdirs();
        File[] snapImgs = new File(dir, "evidence").listFiles();
        if (snapImgs != null) {
            for (File f : snapImgs) {
                File to = new File(evidence, f.getName());
                if (!to.exists()) BackupManager.linkOrCopy(f, to);
            }
        }
        Crypto.clearCache();
        Db fresh = new Db(ctx.getApplicationContext());
        SQLiteDatabase d = fresh.getWritableDatabase();
        try {
            d.execSQL("ATTACH DATABASE ? AS cur", new Object[]{nowCopy.getAbsolutePath()});
            try {
                d.execSQL("INSERT INTO audit_archive(orig_id,action,entity,entity_id,user_name,reason,created_at,prev_hash,row_hash,archived_at) "
                        + "SELECT id,action,entity,entity_id,user_name,reason,created_at,prev_hash,row_hash," + Clock.now() + " FROM cur.audit_logs");
            } finally {
                d.execSQL("DETACH DATABASE cur");
            }
        } catch (Exception ignored) {
            // الأرشفة تحسين؛ التراجع نفسه تم
        }
        // 10.5 (AUDIT-030): التراجع على جهاز في المزامنة لا يعيد هويته ومؤشراته إلى الوراء
        lastSyncNote = "";
        try {
            d.execSQL("ATTACH DATABASE ? AS cur", new Object[]{nowCopy.getAbsolutePath()});
            try {
                lastSyncNote = syncAfterRevert(d);
            } finally {
                d.execSQL("DETACH DATABASE cur");
            }
        } catch (Exception e) {
            // لا نعرف حالة المزامنة قبل التراجع: الأسلم الخروج منها (البيانات تبقى)
            if (SyncStore.meta(d, "vault_id") != null) {
                SyncStore.leave(d, "تراجعت البيانات إلى نسخة أمان؛ تعذر التحقق من حالة المزامنة فخرج هذا الجهاز منها. لإعادته: «الانضمام» من جديد");
                lastSyncNote = "خرج هذا الجهاز من المزامنة بعد التراجع.";
            }
        }
        if (SyncStore.meta(d, "vault_id") == null) fresh.syncPrefix = 0;
        try {
            if (Long.parseLong(lockNow) > Long.parseLong(fresh.setting("lock_before", "0"))) fresh.setSettingQuiet("lock_before", lockNow);
        } catch (NumberFormatException ignored) {
            // قيمة غير رقمية: تبقى قيمة النسخة
        }
        fresh.cleanupEvidence();
        fresh.audit("REVERT_RESTORE", "backup", 0, label(dir) + " / lock=" + fresh.setting("lock_before", "0") + (lastSyncNote.isEmpty() ? "" : " / " + lastSyncNote));
        deleteTree(backupNow);
        return fresh;
    }

    /** 10.5: ما حدث للمزامنة في آخر تراجع (للواجهة)، أو فارغ. */
    public static volatile String lastSyncNote = "";

    /**
     * 10.5 (AUDIT-030): بعد استبدال قاعدة البيانات بنسخة الأمان (والحالة قبل التراجع مرفقة باسم cur):
     * إن كانت نسخة الأمان في مزامنة وحالتها أقدم مما وصلت إليه المزامنة قبل التراجع (رقم آخر حزمة أُرسلت أو ما استُلم من
     * غيره، أو خزنة/هوية أخرى، أو كان الجهاز قد خرج منها) يخرج الجهاز من المزامنة: لو بقي لأعاد استعمال أرقام حزم مستعملة
     * بمحتوى مختلف، ولبقيت الأجهزة الأخرى بتغييراته التي أُلغيت هنا دون أن يعرف أحد. البيانات تبقى كما في نسخة الأمان.
     */
    static String syncAfterRevert(SQLiteDatabase d) {
        String freshVault = SyncStore.meta(d, "vault_id");
        if (freshVault == null) return "";
        String curVault = one(d, "SELECT v FROM cur.sync_meta WHERE k='vault_id'"), curDev = one(d, "SELECT v FROM cur.sync_meta WHERE k='device'");
        boolean regressed = curVault == null || !curVault.equals(freshVault) || curDev == null || !curDev.equals(SyncStore.meta(d, "device"));
        if (!regressed) {
            long curSeq = num(one(d, "SELECT v FROM cur.sync_meta WHERE k='my_seq'")), freshSeq = num(SyncStore.meta(d, "my_seq"));
            if (freshSeq < curSeq) regressed = true;
            java.util.Map<String, Long> fc = SyncStore.cursors(d);
            try (android.database.Cursor c = d.rawQuery("SELECT dev,seq FROM cur.sync_cursor", null)) {
                while (c.moveToNext()) {
                    Long f = fc.get(c.getString(0));
                    if ((f == null ? 0 : f) < c.getLong(1)) regressed = true;
                }
            }
        }
        if (!regressed) return "";
        String why = curVault == null ? "كان هذا الجهاز قد خرج من المزامنة قبل التراجع"
                : "نسخة الأمان أقدم من آخر مزامنة لهذا الجهاز";
        SyncStore.leave(d, "تراجعت البيانات إلى نسخة أمان (" + why + ")، فخرج هذا الجهاز من المزامنة كي لا يتعارض مع الأجهزة الأخرى. "
                + "ما أرسله قبل التراجع باقٍ عندها. لإعادته: «الانضمام» من جديد (تُستبدل بياناته ببيانات الخزنة؛ خذ نسخة احتياطية أولًا إن احتجت ما فيها).");
        return "خرج هذا الجهاز من المزامنة بعد التراجع (" + why + ").";
    }

    /**
     * 10.5: للواجهة قبل التراجع: أثره على المزامنة. فارغ إن لم يكن الجهاز في مزامنة.
     * يذكر عدد التغييرات التي لم تُرسل بعد (تضيع من المزامنة بالتراجع) ليزامن أولًا.
     */
    public static String syncImpact(Db current) {
        SQLiteDatabase d = current.getReadableDatabase();
        if (SyncStore.meta(d, "vault_id") == null) return "";
        long unsent = num(one(d, "SELECT COUNT(*) FROM sync_chg")) + num(one(d, "SELECT COUNT(*) FROM sync_outbox WHERE uploaded=0"));
        return "هذا الجهاز في مزامنة: إن كانت نسخة الأمان أقدم من آخر مزامنة يخرج من المزامنة بعد التراجع (ولا تتغير بيانات الأجهزة الأخرى)، ويمكنه الانضمام من جديد."
                + (unsent > 0 ? " تنبيه: " + unsent + " تغييرًا هنا لم يُرسل بعد؛ زامن أولًا إن أردت أن يصل." : "");
    }

    private static String one(SQLiteDatabase d, String sql) {
        try (android.database.Cursor c = d.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static long num(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
