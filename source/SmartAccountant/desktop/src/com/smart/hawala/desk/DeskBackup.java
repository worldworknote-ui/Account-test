package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.*;
import javax.swing.SwingUtilities;

/**
 * النسخ التلقائي على الماك: إلى مجلد يختاره المستخدم (ومنه iCloud Drive أو Dropbox أو قرص خارجي، فتصير نسخة سحابية تلقائيًا).
 * بالقواعد نفسها التي في الهاتف (BackupPolicy): المجانية يوميًا على الأكثر، والكاملة حتى كل 6 ساعات أو بعد كل 25 حركة.
 * كل نسخة تُنشأ وتُفك كاملة للتحقق داخل البرنامج، ثم تُنسخ للمجلد ويُعاد قراءتها وتطابق بصمتها؛ ولا نسخ بمفتاح مفقود.
 */
final class DeskBackup {
    private DeskBackup() {}

    static final String PREFIX = "smart-accountant-mac-auto-";
    private static volatile boolean running;

    static boolean configured(Db db) {
        String dir = db.setting("desk_backup_dir", "");
        return !dir.isEmpty() && !db.setting("auto_backup_pw", "").isEmpty();
    }

    static boolean enabled(Db db) { return "1".equals(db.setting("auto_backup_on", "0")) && configured(db); }

    static long intervalHours(Db db) {
        return BackupPolicy.intervalHours(db.settingLong("auto_backup_hours", 0), db.settingLong("auto_backup_days", 1), License.current.autoBackupMinHours());
    }

    static int everyTx(Db db) { return BackupPolicy.everyTx(db.settingLong("auto_backup_every_tx", -1), License.current.autoBackupEveryTx()); }

    static boolean due(Db db) {
        if (!enabled(db)) return false;
        long last = db.settingLong("last_auto_backup", 0);
        int every = everyTx(db);
        long since = every > 0 ? db.txCount() - db.settingLong("last_backup_tx", 0) : 0;
        return BackupPolicy.due(Clock.now(), last, intervalHours(db), since, every);
    }

    static void runIfDueAsync(App app) {
        if (running) return;
        new Thread(() -> {
            Db db = app.db;
            try {
                if (!due(db)) return;
                long now = Clock.now();
                long tried = db.settingLong("auto_backup_try", 0);
                if (now - tried >= 0 && now - tried < 30 * 60000L) return;
                db.setSettingQuiet("auto_backup_try", "" + now);
                String where = runNow(app);
                // 10.16: إشعار نجاح النسخ التلقائي (كالهاتف) إن كان مفعّلًا، وإلا رسالة داخل البرنامج فقط
                SwingUtilities.invokeLater(() -> {
                    if (!"0".equals(db.setting("notif_backup", "1"))) DeskNotify.show("تم إنشاء نسخة احتياطية تلقائية ✓", "حُفظت في: " + where);
                    else Ui.toast("نسخة تلقائية ✓ " + where);
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    Ui.toast("فشل النسخ التلقائي: " + Ui.cut(Ui.friendly(t), 90));
                    app.updateFoot();
                });
            }
        }, "auto-backup").start();
    }

    static synchronized String runNow(App app) throws Exception {
        running = true;
        Db db = app.db;
        char[] pw = null;
        try {
            if (!Crypto.healthy(app.ctx) || Db.lockedAside != null) throw new IllegalStateException("مفتاح التشفير غير متاح على هذا الجهاز؛ أُوقف النسخ التلقائي حتى لا تُستبدل النسخ السليمة. استعد آخر نسخة احتياطية.");
            String enc = db.setting("auto_backup_pw", "");
            if (enc.isEmpty()) throw new IllegalStateException("لم تُحدَّد كلمة مرور للنسخ التلقائي");
            File dir = new File(db.setting("desk_backup_dir", ""));
            if (!dir.isDirectory()) throw new IllegalStateException("مجلد النسخ التلقائي غير موجود: " + dir.getAbsolutePath() + " (هل القرص الخارجي مفصول؟)");
            pw = Crypto.decStrict(enc).toCharArray();
            BackupManager.Created created;
            try {
                created = BackupManager.createLocal(app.ctx, db, pw, BuildInfo.VERSION + "-mac", m -> {});
            } catch (BackupManager.BackupException first) {
                Thread.sleep(4000);
                created = BackupManager.createLocal(app.ctx, db, pw, BuildInfo.VERSION + "-mac", m -> {});
            }
            Arrays.fill(pw, '\0');
            pw = null;
            long now = Clock.now();
            String name = PREFIX + created.file.getName().replace("smart-accountant-", "");
            BackupManager.Delivery d = BackupManager.copyToFile(created.file, new File(dir, name));
            db.recordBackup("AUTO", d.verified ? "نسخ تلقائي متحقق منه (ماك)" : "نسخ تلقائي دون إعادة قراءة (ماك)", new File(dir, name).getAbsolutePath(), d.size, created.counts, d.verified, d.note);
            db.deleteSetting("auto_backup_error");
            int keep = (int) Math.max(3, db.settingLong("auto_backup_keep", 7));
            if (created.result.unreadable == 0) prune(dir, keep);
            else db.setSettingQuiet("auto_backup_error", Model.date(now) + "\nالنسخة الأخيرة فيها " + created.result.unreadable + " حقلًا لم يمكن فكه؛ لم تُحذف النسخ الأقدم.");
            try {
                monthlyReadable(db, dir);
            } catch (Exception e) {
                db.setSettingQuiet("readable_export_error", Model.date(now) + "\n" + Ui.friendly(e));
            }
            db.setSettingQuiet("last_auto_backup", "" + now);
            db.setSettingQuiet("last_backup", "" + now);
            db.setSettingQuiet("last_backup_tx", "" + db.txCount());
            db.setSettingQuiet("last_backup_status", d.verified ? "verified" : "unverified");
            return name;
        } catch (Exception e) {
            db.setSettingQuiet("auto_backup_error", Model.date(Clock.now()) + "\n" + Ui.friendly(e));
            throw e;
        } finally {
            if (pw != null) Arrays.fill(pw, '\0');
            running = false;
        }
    }

    static void prune(File dir, int keep) {
        File[] fs = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(".sba"));
        if (fs == null) return;
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = keep; i < fs.length; i++) fs[i].delete();
    }

    /** نسخة مقروءة شهرية (اختيارية): Excel بالأرصدة وحركات الشهر الماضي، تُفتح بلا البرنامج وبلا كلمة مرور. */
    static void monthlyReadable(Db db, File dir) throws Exception {
        if (!"1".equals(db.setting("readable_export", "0"))) return;
        Calendar c = Model.cal();
        String month = String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
        if (month.equals(db.setting("readable_export_month", ""))) return;
        long now = Clock.now();
        String issuer = db.setting("office_name", "");
        Entitlements ent = License.current;
        Report bal = Reports.balances(db.officeTotals(null), db.decimalsMap(), db.currencyNames(), false, issuer, now);
        bal.stamp(ent.watermark(), ent.lid);
        xlsx(new File(dir, "smart-accountant-mac-readable-" + month + "-balances.xlsx"), bal);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long thisMonth = c.getTimeInMillis();
        c.add(Calendar.MONTH, -1);
        Db.TxFilter f = new Db.TxFilter();
        f.from = c.getTimeInMillis();
        f.to = thisMonth;
        f.includeCancelled = true;
        String prev = String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
        Report txs = Reports.txList("حركات شهر " + prev, new ArrayList<>(), db.txLog(f), db.officeNames(), db.decimalsMap(), issuer, now);
        txs.stamp(ent.watermark(), ent.lid);
        xlsx(new File(dir, "smart-accountant-mac-readable-" + prev + "-transactions.xlsx"), txs);
        db.setSettingQuiet("readable_export_month", month);
        db.setSettingQuiet("readable_export_at", "" + now);
        db.deleteSetting("readable_export_error");
    }

    static void xlsx(File f, Report r) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) {
            XlsxWriter.write(r, o, null, Model.zone());
        }
    }
}
