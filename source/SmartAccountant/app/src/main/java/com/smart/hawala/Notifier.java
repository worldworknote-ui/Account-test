package com.smart.hawala;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 9.0: الإشعارات: عند إنشاء نسخة تلقائية، وعند مكتب لم تُسجَّل عليه حركات منذ يوم كامل، وعند مكتب لم تُرسل له مطابقة رصيد.
 * لكل نوع مفتاح تشغيل، ولكل مكتب خيار (صفحة المكتب ← البيانات). تُفحص في الخلفية كل بضع ساعات، ولا يتكرر النوع نفسه في اليوم
 * أكثر من مرة، ولا تظهر خارج ساعات العمل التي يختارها المستخدم. لا تُرسل أي بيانات خارج الهاتف.
 */
final class Notifier {
    private Notifier() {}

    static final String CH_BACKUP = "backup", CH_REMIND = "reminders";
    static final int ID_BACKUP_OK = 5110, ID_IDLE = 5111, ID_RECON = 5112, JOB_ID = 5103;

    static boolean on(Db db) { return !"0".equals(db.setting("notif_on", "1")); }

    static boolean backupOn(Db db) { return on(db) && !"0".equals(db.setting("notif_backup", "1")); }

    static boolean idleOn(Db db) { return on(db) && !"0".equals(db.setting("notif_idle", "1")); }

    static boolean reconOn(Db db) { return on(db) && !"0".equals(db.setting("notif_recon", "1")); }

    static int reconDays(Db db) { return (int) Math.max(1, Math.min(60, db.settingLong("notif_recon_days", 7))); }

    static int hourFrom(Db db) { return (int) Math.max(0, Math.min(23, db.settingLong("notif_hour_from", 9))); }

    static int hourTo(Db db) { return (int) Math.max(1, Math.min(24, db.settingLong("notif_hour_to", 21))); }

    static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH_BACKUP, "النسخ الاحتياطي", NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel r = new NotificationChannel(CH_REMIND, "تذكيرات المكاتب", NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription("مكتب بلا حركات منذ يوم كامل، ومطابقة رصيد لم تُرسل");
        nm.createNotificationChannel(r);
        NotificationChannel x = new NotificationChannel(CH_ACTION, "حركات تحتاج إجراء", NotificationManager.IMPORTANCE_HIGH);
        x.setDescription("حركات من الأجهزة الأخرى: بانتظار اعتمادك، أو جديدة، أو قد تكون مكررة");
        nm.createNotificationChannel(x);
    }

    static final String CH_ACTION = "actions";

    /**
     * 10.2: بعد مزامنة وصلت فيها حركات من أجهزة أخرى: حركات بانتظار الاعتماد (لمن يعتمد)، وحركات جديدة (للمدير ومن يعتمد)،
     * وتكرار محتمل. المستخدم المعني هو الحالي على هذا الجهاز أو المحفوظ عليه («تذكرني»)؛ بلا مستخدمين: صاحب الجهاز.
     */
    /** 10.17: إشعار برسائل المدير الجديدة للمستخدم الحالي (مرة واحدة لكل رسالة على هذا الجهاز). */
    static void adminMsgs(Context c, Db db) {
        try {
            if (!on(db)) return;
            int i = 0;
            for (Db.AdminMsg m : db.adminMsgsToNotify(target(db))) post(c, CH_ACTION, 3200 + (i++ % 20), "رسالة من المدير", m.body, null);
        } catch (Exception ignored) {
            // الإشعار للتنبيه فقط
        }
    }

    static void afterSync(Context c, Db db, SyncEngine.Result r) { afterSync(c, db, r, false); }

    /**
     * 11.5: لمن الإشعارات على هذا الجهاز: الداخل الآن، أو المحفوظ («تذكرني»)، أو آخر من دخل عليه.
     * (في الخلفية بلا «تذكرني» كان لا أحد معنيًّا فلا يصل شيء للمدير ولا للمحاسبين.)
     */
    static Model.User target(Db db) {
        Model.User who = SmartApp.currentUser != null ? db.user(SmartApp.currentUser.id) : db.rememberedUser();
        if (who == null && db.usersEnabled()) {
            long last = db.settingLong("notif_last_user", 0);
            if (last > 0) who = db.user(last);
        }
        return who;
    }

    static void afterSync(Context c, Db db, SyncEngine.Result r, boolean foreground) {
        if (r == null || !on(db)) return;
        adminMsgs(c, db);
        if (r.newTx.isEmpty() && r.dupTx == 0 && r.review() == 0 && r.news.isEmpty()) return;
        try {
            Model.User who = target(db);
            boolean users = db.usersEnabled();
            boolean approver = !users || (who != null && who.active && Auth.can(who, Auth.Perm.APPROVE));
            boolean watcher = !users || (who != null && who.active && (Auth.isAdmin(who) || Auth.can(who, Auth.Perm.APPROVE)));
            Map<Long, String> names = db.officeNames();
            List<String> pend = new ArrayList<>(), fresh = new ArrayList<>(), mineNew = new ArrayList<>();
            // 11.5: المحاسب يُشعَر بحركات مكاتبه («مكاتبي») التي سجّلها غيره (كان لا يصله شيء)
            java.util.Set<Long> myOffices = new java.util.HashSet<>();
            if (users && who != null && who.active && !watcher) myOffices.addAll(db.officeRoles(who.id).keySet());
            for (long id : r.newTx) {
                Model.Tx t = db.tx(id);
                if (t == null) continue;
                String line = (t.userName.isEmpty() ? "" : t.userName + ": ") + (Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ")
                        + Model.fmt(t.amount, db.decimals(t.currency)) + " " + t.currency + " — " + Model.nz(names.get(t.officeId)) + (t.person.isEmpty() ? "" : " (" + t.person + ")");
                if (t.isPending()) pend.add(line);
                else if (t.isActive()) fresh.add(line);
                if (t.isActive() && myOffices.contains(t.officeId) && (who == null || !who.name.equals(t.userName))) mineNew.add(line);
            }
            if (approver && !pend.isEmpty() && !"0".equals(db.setting("notif_sync_pending", "1"))) {
                post(c, CH_ACTION, nextId(3130), pend.size() == 1 ? "حركة بانتظار اعتمادك" : pend.size() + " حركات بانتظار اعتمادك",
                        join(pend, 4) + "\nاعتمدها أو صحّحها أو ارفضها من التطبيق.", MainActivity.ACTION_PENDING);
            }
            if (watcher && !fresh.isEmpty() && !"0".equals(db.setting("notif_sync_tx", "1"))) {
                String title = fresh.size() == 1 ? "حركة جديدة من جهاز آخر" : fresh.size() + " حركات جديدة من الأجهزة الأخرى";
                if (foreground) logOnly(title, join(fresh, 4));   // أمامه الآن: رسالة قصيرة داخل التطبيق، ويبقى في سجل الإشعارات
                else post(c, CH_ACTION, nextId(3110), title, join(fresh, 4), MainActivity.ACTION_LOG);
            }
            if (!mineNew.isEmpty() && !"0".equals(db.setting("notif_sync_tx", "1"))) {
                String title = mineNew.size() == 1 ? "حركة جديدة على أحد مكاتبك" : mineNew.size() + " حركات جديدة على مكاتبك";
                if (foreground) logOnly(title, join(mineNew, 4));
                else post(c, CH_ACTION, nextId(3110), title, join(mineNew, 4), MainActivity.ACTION_LOG);
            }
            if (r.dupTx > 0 && (approver || watcher)) {
                post(c, CH_ACTION, 3103, "حركات قد تكون مكررة", r.dupTx + " زوج حركات متشابهة سُجّل من جهازين. راجعها: أبقِ الاثنتين أو ألغِ المكررة.", MainActivity.ACTION_DUPS);
            }
            // 10.16: تعارضات المزامنة وتنبيهاتها، ودخول جهاز أو خروجه (كانت لا تظهر إلا لمن يفتح شاشة المزامنة)
            boolean admin = !users || (who != null && who.active && Auth.isAdmin(who));
            // 11.6: تنبيهات المزامنة حساسة: لمن يملك «سجل الإشعارات»
            boolean seesNotifs = !users || (who != null && who.active && Auth.can(who, Auth.Perm.NOTIFS));
            if (r.review() > 0 && seesNotifs && !"0".equals(db.setting("notif_sync_review", "1"))) {
                List<String[]> cs = SyncEngine.conflicts(db);
                List<String> lines = new ArrayList<>();
                for (String[] x : cs) lines.add(SyncStore.kindName(x[2]) + ": " + x[5]);
                post(c, CH_ACTION, 3104, r.review() == 1 ? "تنبيه من المزامنة بحاجة لمراجعتك" : r.review() + " تنبيهات من المزامنة بحاجة لمراجعتك",
                        lines.isEmpty() ? "افتح «المزامنة» وراجع قسم «للمراجعة»." : join(lines, 3), MainActivity.ACTION_SYNC);
            }
            if (!r.news.isEmpty() && admin && !"0".equals(db.setting("notif_sync_devices", "1"))) {
                post(c, CH_ACTION, 3105, "أجهزة المزامنة", join(r.news, 3), MainActivity.ACTION_SYNC);
            }
        } catch (Throwable ignored) {
            // الإشعار للتنبيه فقط
        }
    }

    /** 11.5: رقم جديد لكل إشعار من النوع نفسه (كان الرقم ثابتًا فيحلّ الإشعار الجديد محل القديم قبل أن يُرى). */
    private static int seq;

    static synchronized int nextId(int base) {
        seq = (seq + 1) % 15;
        return base + seq;
    }

    /** إشعار لم يُعرض لأن المستخدم أمام التطبيق: يبقى في سجل الإشعارات. */
    private static void logOnly(String title, String text) {
        try {
            Db ldb = SmartApp.db;
            if (ldb != null) ldb.logNotif(title, text);
        } catch (Throwable ignored) {
            // للمراجعة فقط
        }
    }

    private static String join(List<String> l, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) b.append(i == 0 ? "" : "\n").append(l.get(i));
        if (l.size() > max) b.append("\nو").append(l.size() - max).append(" أخرى");
        return b.toString();
    }

    private static PendingIntent open(Context c, String action, int code) {
        Intent i = new Intent(c, MainActivity.class);
        if (action != null) i.setAction(action);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void post(Context c, String channel, int id, String title, String text, String action) {
        // 10.18: كل إشعار يُحفظ في سجل الإشعارات فلا يضيع بالضغط عليه
        try {
            Db ldb = SmartApp.db;
            if (ldb != null) ldb.logNotif(title, text);
        } catch (Throwable ignored) {
            // السجل للمراجعة فقط
        }
        try {
            channels(c);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification n = new Notification.Builder(c, channel)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open(c, action, id))
                    .setAutoCancel(true)
                    .build();
            nm.notify(id, n);
        } catch (Throwable ignored) {
            // بلا صلاحية إشعارات لا يظهر شيء؛ الحالة ظاهرة داخل التطبيق
        }
    }

    /** بعد نسخة تلقائية ناجحة. */
    static void backupDone(Context c, Db db, String where, Map<String, Long> counts, boolean verified) {
        if (!backupOn(db)) return;
        String t = "حُفظت في: " + where + (verified ? " — وتحقق التطبيق منها" : "");
        if (counts != null && counts.get("tx") != null) t += "\n" + counts.get("tx") + " حركة، " + (counts.get("offices") == null ? 0 : counts.get("offices")) + " مكتب.";
        post(c, CH_BACKUP, ID_BACKUP_OK, "تم إنشاء نسخة احتياطية تلقائية ✓", t, null);
    }

    static String names(List<Model.Office> l, int max) {
        List<String> n = new ArrayList<>();
        for (int i = 0; i < l.size() && i < max; i++) n.add(l.get(i).name);
        String s = Model.join(n, "، ");
        return l.size() > max ? s + " و" + (l.size() - max) + " غيرها" : s;
    }

    /** داخل ساعات العمل المختارة؟ */
    static boolean workingHour(Db db, long now) {
        Calendar c = Model.cal();
        c.setTimeInMillis(now);
        int h = c.get(Calendar.HOUR_OF_DAY);
        int from = hourFrom(db), to = hourTo(db);
        return from < to ? h >= from && h < to : h >= from || h < to;
    }

    /** فحص التذكيرات (من المهمة الدورية، ومن التطبيق عند فتحه). */
    static void check(Context c, Db db) {
        if (!on(db)) return;
        long now = Clock.now();
        if (!workingHour(db, now)) return;
        String today = Db.today();
        if (idleOn(db) && !today.equals(db.setting("notif_last_idle", ""))) {
            List<Model.Office> idle = db.idleOffices(now);
            if (!idle.isEmpty()) {
                post(c, CH_REMIND, ID_IDLE, idle.size() == 1 ? "لم تُسجَّل حركات لمكتب " + idle.get(0).name + " منذ يوم كامل"
                                : "لم تُسجَّل حركات لـ " + idle.size() + " مكاتب منذ يوم كامل",
                        names(idle, 6) + "\nإن كان عندك حركات لم تُسجَّل فسجّلها الآن. (يمكن إيقاف هذا التنبيه لكل مكتب من صفحته)", MainActivity.ACTION_OFFICES);
                db.setSettingQuiet("notif_last_idle", today);
            }
        }
        if (reconOn(db) && !today.equals(db.setting("notif_last_recon", ""))) {
            int days = reconDays(db);
            List<Model.Office> due = db.reconDue(now, days);
            if (!due.isEmpty()) {
                post(c, CH_REMIND, ID_RECON, due.size() == 1 ? "لم تُرسل مطابقة رصيد لمكتب " + due.get(0).name + " منذ " + days + " أيام"
                                : due.size() + " مكاتب لم تُرسل لها مطابقة رصيد منذ " + days + " أيام",
                        names(due, 6) + "\nأرسل المطابقة من صفحة المكتب ← «مطابقة رصيد».", MainActivity.ACTION_OFFICES);
                db.setSettingQuiet("notif_last_recon", today);
            }
        }
    }

    /** المهمة الدورية (كل 3 ساعات تقريبًا). */
    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, ReminderJob.class))
                    .setPeriodic(3L * 3600000L)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) {
            // بلا جدولة: يُفحص عند فتح التطبيق
        }
    }
}
