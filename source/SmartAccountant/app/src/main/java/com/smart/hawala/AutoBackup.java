package com.smart.hawala;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * نسخ احتياطي تلقائي مجدول إلى مجلد يختاره المستخدم مرة واحدة (ذاكرة الهاتف، بطاقة الذاكرة...)
 * و/أو إلى ملف واحد في Google Drive يُحدَّث بآخر نسخة.
 * كلمة مرور النسخ التلقائي محفوظة مشفّرة بمفتاح الهاتف (Keystore). يعمل في الخلفية عبر JobScheduler،
 * وعند فتح التطبيق إن تأخر موعده. عند الفشل يظهر إشعار وسبب الفشل في شاشة النسخ.
 */
public final class AutoBackup {
    public static final int JOB_ID = 5101;
    private static final String CHANNEL = "backup";
    /**
     * بادئة ملفات النسخ التلقائي. الإصدار 5 يبقى «smart-accountant-auto-»، وأي حزمة أخرى (مثل v6 المثبتة بجانبه)
     * تضيف اسمها، حتى لا يحذف أحدهما نسخ الآخر عند تنظيف المجلد نفسه.
     */
    static String prefix(Context c) {
        String p = c.getPackageName();
        String tail = p.substring(p.lastIndexOf('.') + 1);
        return "v5".equals(tail) ? "smart-accountant-auto-" : "smart-accountant-" + tail + "-auto-";
    }

    private AutoBackup() {}

    /** مجلد على الهاتف/بطاقة الذاكرة/أي مزوّد يدعم المجلدات: يحتفظ بعدة نسخ مؤرّخة. */
    public static boolean hasFolder(Db db) {
        return !db.setting("auto_backup_tree", "").isEmpty();
    }

    /**
     * ملف واحد في Google Drive (أو أي خدمة سحابية): Google Drive لا يسمح للتطبيقات باختيار مجلد كامل،
     * فيختار المستخدم مكان ملف واحد ويُستبدل محتواه بآخر نسخة في كل مرة (ويحتفظ Drive بإصداراته السابقة).
     */
    public static boolean hasDrive(Db db) {
        return !db.setting("drive_backup_uri", "").isEmpty();
    }

    public static boolean configured(Db db) {
        return (hasFolder(db) || hasDrive(db)) && !db.setting("auto_backup_pw", "").isEmpty();
    }

    public static boolean enabled(Db db) {
        return "1".equals(db.setting("auto_backup_on", "0")) && configured(db);
    }

    /** الاسم المقترح لملف Drive؛ يختلف عن بادئة النسخ المؤرخة حتى لا يحذفه تنظيف المجلد. */
    static String driveFileName(Context c) {
        return prefix(c).replace("-auto-", "-drive-") + "latest.sba";
    }

    /** الفاصل الفعلي بالساعات حسب الإعداد وحد النسخة (المجانية يوميًا على الأكثر، الكاملة حتى كل 6 ساعات). */
    public static long intervalHours(Db db) {
        return BackupPolicy.intervalHours(db.settingLong("auto_backup_hours", 0), db.settingLong("auto_backup_days", 1), License.current.autoBackupMinHours());
    }

    /** عدد الحركات منذ آخر نسخة الذي يستدعي نسخة جديدة (0 = لا). */
    public static int everyTx(Db db) {
        return BackupPolicy.everyTx(db.settingLong("auto_backup_every_tx", -1), License.current.autoBackupEveryTx());
    }

    public static boolean due(Db db) {
        if (!enabled(db)) return false;
        long last = db.settingLong("last_auto_backup", 0);
        int every = everyTx(db);
        long since = every > 0 ? db.txCount() - db.settingLong("last_backup_tx", 0) : 0;
        return BackupPolicy.due(Clock.now(), last, intervalHours(db), since, every);
    }

    public static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, AutoBackupJob.class))
                .setPeriodic(6L * 3600000L)
                .setPersisted(true)
                .setRequiresStorageNotLow(true)
                .build();
        js.schedule(job);
    }

    public static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    /** يشغّل النسخة إن حان موعدها، في خيط خلفي. */
    public static void runIfDueAsync(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Db db = SmartApp.db;
                if (db == null) return;
                try {
                    if (!due(db)) return;
                    // محاولة واحدة كل نصف ساعة من هذا المسار (بعد الحركات أو عند الفتح)، فلا تتكرر إشعارات الفشل مع كل حركة
                    long now = Clock.now();
                    long tried = db.settingLong("auto_backup_try", 0);
                    if (now - tried >= 0 && now - tried < 30 * 60000L) return;
                    db.setSettingQuiet("auto_backup_try", "" + now);
                    runNow(app, db);
                } catch (Throwable ignored) {
                    // runNow يسجّل الخطأ ويرسل إشعارًا
                }
            }
        }, "auto-backup").start();
    }

    /** خطأ نهائي بعد تسجيل كل وجهة على حدة؛ لا يُسجَّل مرة ثانية. */
    private static final class AllFailed extends Exception {
        AllFailed(String m) { super(m); }
    }

    /**
     * ينشئ نسخة مشفّرة ويتحقق منها داخل التطبيق، ثم يرسلها إلى كل وجهة مفعّلة (المجلد و/أو ملف Google Drive)
     * ويعيد قراءتها من هناك للمطابقة. فشل وجهة لا يمنع الأخرى، ولكل وجهة سجل خطئها الخاص.
     */
    public static synchronized String runNow(Context ctx, Db db) throws Exception {
        char[] pw = null;
        try {
            // 8.0: لا نسخ بمفتاح مفقود: النسخة ستحمل نصوصًا بديلة وتحل محل السليمة
            if (!Crypto.healthy(ctx) || Db.lockedAside != null) throw new IllegalStateException("مفتاح التشفير غير متاح على هذا الهاتف؛ أُوقف النسخ التلقائي حتى لا تُستبدل النسخ السليمة. استعد آخر نسخة احتياطية.");
            String enc = db.setting("auto_backup_pw", "");
            if (enc.isEmpty()) throw new IllegalStateException("لم تُحدَّد كلمة مرور للنسخ التلقائي");
            if (!hasFolder(db) && !hasDrive(db)) throw new IllegalStateException("لم تُحدَّد وجهة للنسخ التلقائي (مجلد أو ملف Google Drive).");
            pw = Crypto.decStrict(enc).toCharArray();
            String version = "";
            try {
                version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
                // غير مهم
            }
            BackupManager.Created created;
            try {
                created = BackupManager.createLocal(ctx, db, pw, version, new BackupManager.Progress() {
                    @Override public void update(String message) {}
                });
            } catch (BackupManager.BackupException first) {
                // قد تتغير البيانات أثناء النسخ في الخلفية فيفشل التحقق؛ نعيد المحاولة مرة واحدة
                Thread.sleep(4000);
                created = BackupManager.createLocal(ctx, db, pw, version, new BackupManager.Progress() {
                    @Override public void update(String message) {}
                });
            }
            Arrays.fill(pw, '\0');
            pw = null;

            ContentResolver cr = ctx.getContentResolver();
            List<String> saved = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            boolean allVerified = true;
            long now = Clock.now();

            if (hasFolder(db)) {
                try {
                    Uri tree = Uri.parse(db.setting("auto_backup_tree", ""));
                    String treeDoc = DocumentsContract.getTreeDocumentId(tree);
                    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc);
                    String name = prefix(ctx) + created.file.getName().replace("smart-accountant-", "");
                    Uri doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name);
                    if (doc == null) throw new IllegalStateException("تعذر إنشاء ملف في مجلد النسخ التلقائي. أعد اختيار المجلد من شاشة النسخ الاحتياطي.");
                    BackupManager.Delivery d = BackupManager.copyToUri(ctx, created.file, doc);
                    db.recordBackup("AUTO", d.verified ? "نسخ تلقائي متحقق منه" : "نسخ تلقائي دون إعادة قراءة", name, d.size, created.counts, d.verified, d.note);
                    allVerified &= d.verified;
                    db.deleteSetting("auto_backup_error");
                    // 8.0: نسخة فيها حقول تالفة لا تحذف الأقدم السليمة (والحد الأدنى للاحتفاظ 3)
                    int keep = (int) Math.max(3, db.settingLong("auto_backup_keep", 7));
                    if (created.result.unreadable == 0) prune(cr, tree, treeDoc, prefix(ctx), keep);
                    else db.setSettingQuiet("auto_backup_error", Model.date(now) + "\nالنسخة الأخيرة فيها " + created.result.unreadable
                            + " حقلًا لم يمكن فكه؛ لم تُحذف النسخ الأقدم. افتح «الإعدادات ← فحص البيانات».");
                    saved.add(name);
                    try {
                        monthlyReadable(ctx, db, cr, tree, treeDoc);
                    } catch (Exception e) {
                        db.setSettingQuiet("readable_export_error", Model.date(now) + "\n" + message(e));
                    }
                } catch (Exception e) {
                    String msg = message(e);
                    db.setSettingQuiet("auto_backup_error", Model.date(now) + "\n" + msg);
                    failed.add("المجلد: " + msg);
                }
            }

            if (hasDrive(db)) {
                try {
                    // 8.0: ملف Drive يُستبدل كل مرة، فلا يُكتب فوقه بنسخة فيها حقول تالفة
                    if (created.result.unreadable > 0) throw new IllegalStateException("النسخة الأخيرة فيها " + created.result.unreadable
                            + " حقلًا لم يمكن فكه؛ أُبقي ملف Google Drive السابق كما هو.");
                    Uri file = Uri.parse(db.setting("drive_backup_uri", ""));
                    String name = db.setting("drive_backup_name", driveFileName(ctx));
                    BackupManager.Delivery d = BackupManager.copyToUri(ctx, created.file, file, false);
                    db.recordBackup("AUTO", d.verified ? "نسخ تلقائي إلى Google Drive متحقق منه" : "نسخ تلقائي إلى Google Drive دون إعادة قراءة",
                            name, d.size, created.counts, d.verified, d.note);
                    allVerified &= d.verified;
                    db.setSettingQuiet("last_drive_backup", "" + now);
                    db.deleteSetting("drive_backup_error");
                    saved.add("Google Drive: " + name);
                } catch (Exception e) {
                    String msg = message(e);
                    if (e instanceof SecurityException || (e.getCause() instanceof SecurityException)
                            || (e instanceof BackupManager.BackupException && e.getCause() instanceof java.io.FileNotFoundException)) {
                        msg += "\nربما حُذف الملف من Google Drive أو أُلغيت صلاحية الوصول إليه. اختر ملف Drive من جديد من شاشة النسخ الاحتياطي.";
                    }
                    db.setSettingQuiet("drive_backup_error", Model.date(now) + "\n" + msg);
                    failed.add("Google Drive: " + msg);
                }
            }

            if (saved.isEmpty()) {
                String all = join(failed);
                notifyFailure(ctx, all);
                throw new AllFailed(all);
            }
            if (!hasFolder(db)) db.deleteSetting("auto_backup_error");
            db.setSettingQuiet("last_auto_backup", "" + now);
            db.setSettingQuiet("last_backup", "" + now);
            db.setSettingQuiet("last_backup_tx", "" + db.txCount());
            db.setSettingQuiet("last_backup_status", allVerified ? "verified" : "unverified");
            if (!failed.isEmpty()) notifyFailure(ctx, "حُفظت النسخة في: " + join(saved) + "\nوفشل الحفظ في:\n" + join(failed));
            else Notifier.backupDone(ctx, db, join(saved), created.counts, allVerified);
            Reliability.healthAsync(db);
            return join(saved);
        } catch (AllFailed e) {
            throw new Exception(e.getMessage());
        } catch (Exception e) {
            String msg = message(e);
            db.setSettingQuiet("auto_backup_error", Model.date(Clock.now()) + "\n" + msg);
            notifyFailure(ctx, msg);
            throw e;
        } finally {
            if (pw != null) Arrays.fill(pw, '\0');
        }
    }

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * 8.0 (ت9): نسخة مقروءة شهرية (اختيارية) في مجلد النسخ: أرصدة كل المكاتب الآن، وحركات الشهر الماضي، بصيغة Excel.
     * تُفتح بلا التطبيق وبلا كلمة مرور، فتقي من فشل صيغة النسخ أو ضياع المفتاح. غير مشفّرة: لذلك هي اختيارية.
     */
    static void monthlyReadable(Context ctx, Db db, ContentResolver cr, Uri tree, String treeDoc) throws Exception {
        if (!"1".equals(db.setting("readable_export", "0"))) return;
        java.util.Calendar c = Model.cal();
        String month = String.format(java.util.Locale.US, "%04d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1);
        if (month.equals(db.setting("readable_export_month", ""))) return;
        long now = Clock.now();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc);
        String issuer = db.setting("office_name", "");
        Entitlements ent = License.current;
        Report bal = Reports.balances(db.officeTotals(null), db.decimalsMap(), db.currencyNames(), false, issuer, now);
        bal.stamp(ent.watermark(), ent.lid);
        writeXlsx(cr, parent, prefix(ctx).replace("-auto-", "-readable-") + month + "-balances.xlsx", bal);
        c.set(java.util.Calendar.DAY_OF_MONTH, 1);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long thisMonth = c.getTimeInMillis();
        c.add(java.util.Calendar.MONTH, -1);
        Db.TxFilter f = new Db.TxFilter();
        f.from = c.getTimeInMillis();
        f.to = thisMonth;
        f.includeCancelled = true;
        String prev = String.format(java.util.Locale.US, "%04d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1);
        Report txs = Reports.txList("حركات شهر " + prev, new ArrayList<String>(), db.txLog(f), db.officeNames(), db.decimalsMap(), issuer, now);
        txs.stamp(ent.watermark(), ent.lid);
        writeXlsx(cr, parent, prefix(ctx).replace("-auto-", "-readable-") + prev + "-transactions.xlsx", txs);
        db.setSettingQuiet("readable_export_month", month);
        db.setSettingQuiet("readable_export_at", "" + now);
        db.deleteSetting("readable_export_error");
    }

    private static void writeXlsx(ContentResolver cr, Uri parent, String name, Report r) throws Exception {
        Uri doc = DocumentsContract.createDocument(cr, parent, XLSX, name);
        if (doc == null) throw new IllegalStateException("تعذر إنشاء " + name);
        try (java.io.OutputStream o = cr.openOutputStream(doc)) {
            if (o == null) throw new IllegalStateException("تعذر الكتابة في " + name);
            XlsxWriter.write(r, o, null, Model.zone());
        }
    }

    private static String message(Exception e) {
        return e instanceof BackupManager.BackupException ? ((BackupManager.BackupException) e).userMessage() : String.valueOf(e.getMessage());
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append('\n');
            b.append(p);
        }
        return b.toString();
    }

    private static void prune(ContentResolver cr, Uri tree, String treeDoc, String prefix, int keep) {
        List<String[]> found = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc);
        try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n != null && n.startsWith(prefix) && n.endsWith(".sba")) found.add(new String[]{n, c.getString(0)});
            }
        } catch (Exception e) {
            return;
        }
        Collections.sort(found, (a, b) -> b[0].compareTo(a[0]));
        for (int i = keep; i < found.size(); i++) {
            try {
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, found.get(i)[1]));
            } catch (Exception ignored) {
                // نتركه
            }
        }
    }

    static void notifyFailure(Context ctx, String msg) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "النسخ الاحتياطي", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("فشل النسخ الاحتياطي التلقائي")
                    .setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(JOB_ID, n);
        } catch (Throwable ignored) {
            // بدون صلاحية إشعارات لا يظهر شيء، والخطأ مسجل في شاشة النسخ
        }
    }
}
