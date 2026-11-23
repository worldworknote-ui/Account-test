package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.Map;

/** 10.2: إشعار نظام (ويندوز/ماك) عند وصول حركات تحتاج إجراء من الأجهزة الأخرى، مع رسالة داخل البرنامج. */
final class DeskNotify {
    private DeskNotify() {}

    private static TrayIcon tray;

    /** 10.16: المفتاح العام للإشعارات على هذا الجهاز (كالهاتف). */
    static boolean on(Db db) { return !"0".equals(db.setting("notif_on", "1")); }

    static boolean workingHour(Db db) {
        int h = Model.cal().get(java.util.Calendar.HOUR_OF_DAY);
        int from = (int) Math.max(0, Math.min(23, db.settingLong("notif_hour_from", 9))), to = (int) Math.max(1, Math.min(24, db.settingLong("notif_hour_to", 21)));
        return from < to ? h >= from && h < to : h >= from || h < to;
    }

    private static String names(java.util.List<Model.Office> l, int max) {
        java.util.List<String> n = new java.util.ArrayList<>();
        for (int i = 0; i < l.size() && i < max; i++) n.add(l.get(i).name);
        return String.join("، ", n) + (l.size() > max ? " و" + (l.size() - max) + " غيرها" : "");
    }

    /**
     * 10.16: تذكيرات الهاتف نفسها على الكمبيوتر (مرة في اليوم لكل نوع، داخل ساعات الإشعار): مكاتب بلا حركات منذ يوم،
     * مكاتب لم تُرسل لها مطابقة منذ المدة المحددة، ونسخة احتياطية تأخرت.
     */
    static void check(App app) {
        Db db = app.db;
        if (app.locked || !on(db) || !workingHour(db)) return;
        try {
            long now = Clock.now();
            String today = Model.sdf(new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)).format(new java.util.Date(now));
            if (app.can(Auth.Perm.VIEW) && !"0".equals(db.setting("notif_idle", "1")) && !today.equals(db.setting("notif_last_idle", ""))) {
                java.util.List<Model.Office> idle = db.idleOffices(now);
                if (!idle.isEmpty()) {
                    show(idle.size() == 1 ? "لم تُسجَّل حركات لمكتب " + idle.get(0).name + " منذ يوم كامل" : "لم تُسجَّل حركات لـ " + idle.size() + " مكاتب منذ يوم كامل", names(idle, 6));
                    db.setSettingQuiet("notif_last_idle", today);
                }
            }
            if (app.can(Auth.Perm.VIEW) && !"0".equals(db.setting("notif_recon", "1")) && !today.equals(db.setting("notif_last_recon", ""))) {
                int days = (int) Math.max(1, Math.min(60, db.settingLong("notif_recon_days", 7)));
                java.util.List<Model.Office> due = db.reconDue(now, days);
                if (!due.isEmpty()) {
                    show(due.size() == 1 ? "لم تُرسل مطابقة رصيد لمكتب " + due.get(0).name + " منذ " + days + " أيام" : due.size() + " مكاتب لم تُرسل لها مطابقة رصيد منذ " + days + " أيام",
                            names(due, 6));
                    db.setSettingQuiet("notif_last_recon", today);
                }
            }
            long remind = db.settingLong("desk_backup_remind_days", 3);
            if (remind > 0 && app.can(Auth.Perm.BACKUP) && !today.equals(db.setting("notif_last_backup_remind", ""))) {
                long last = db.settingLong("last_backup", 0);
                long days = last == 0 ? Long.MAX_VALUE : (now - last) / 86_400_000L;
                // وبعد عدد حركات جديدة منذ آخر نسخة (إن حُدّد)
                long everyTx = db.settingLong("desk_backup_remind_tx", 0), txNow = db.counts().get("tx") == null ? 0 : db.counts().get("tx");
                long since = Math.max(0, txNow - db.settingLong("last_backup_tx", 0));
                if (everyTx > 0 && last > 0 && since >= everyTx && days < remind) {
                    show("حان وقت النسخة الاحتياطية", since + " حركة جديدة منذ آخر نسخة احتياطية. (Ctrl+B)");
                    db.setSettingQuiet("notif_last_backup_remind", today);
                } else if (days >= remind && txNow > 0) {
                    show("حان وقت النسخة الاحتياطية", last == 0 ? "لم تُنشأ نسخة احتياطية على هذا الجهاز بعد. (Ctrl+B)" : "آخر نسخة احتياطية قبل " + days + " يوم. (Ctrl+B)");
                    db.setSettingQuiet("notif_last_backup_remind", today);
                }
            }
        } catch (Throwable ignored) {
            // التذكير للتنبيه فقط
        }
    }

    /** 10.17: إشعار برسائل المدير الجديدة للمستخدم الحالي (مرة لكل رسالة)، وتحديث الرئيسية لتظهر فيها. */
    static void adminMsgs(App app) {
        try {
            if (app.locked) return;
            java.util.List<Db.AdminMsg> l = app.db.adminMsgsToNotify();
            for (Db.AdminMsg m : l) show("رسالة من المدير", m.body);
            if (!l.isEmpty() && "home".equals(app.current)) app.views.get("home").refresh();
        } catch (Throwable ignored) {
            // الإشعار للتنبيه فقط
        }
    }

    static void show(String title, String text) {
        Ui.toast(title + " — " + text.replace('\n', ' '));
        if (App.instance != null) App.instance.db.logNotif(title, text);   // 10.18: يبقى في سجل الإشعارات
        if (App.instance != null && !on(App.instance.db)) return;
        try {
            if (!SystemTray.isSupported()) return;
            if (tray == null) {
                tray = new TrayIcon(Main.appIcon(64), "المحاسب الذكي");
                tray.setImageAutoSize(true);
                SystemTray.getSystemTray().add(tray);
            }
            tray.displayMessage(title, text, TrayIcon.MessageType.INFO);
        } catch (Throwable ignored) {
            // الرسالة داخل البرنامج تكفي
        }
    }

    /** بعد مزامنة: حركات بانتظار الاعتماد (لمن يعتمد)، وتكرار محتمل. */
    static void afterSync(App app, SyncEngine.Result r) {
        adminMsgs(app);
        if (r == null || r.newTx.isEmpty() && r.dupTx == 0 && r.review() == 0 && r.news.isEmpty()) return;
        Db db = app.db;
        boolean approver = !db.usersEnabled() || app.can(Auth.Perm.APPROVE);
        int pend = 0;
        String first = "";
        Map<Long, String> names = db.officeNames();
        for (long id : r.newTx) {
            Model.Tx t = db.tx(id);
            if (t == null || !t.isPending()) continue;
            if (pend++ == 0) first = (t.userName.isEmpty() ? "" : t.userName + ": ") + Model.fmt(t.amount, db.decimals(t.currency)) + " " + t.currency + " — " + Model.nz(names.get(t.officeId));
        }
        if (approver && pend > 0 && !"0".equals(db.setting("notif_sync_pending", "1"))) show(pend == 1 ? "حركة بانتظار اعتمادك" : pend + " حركات بانتظار اعتمادك", first);
        if (r.dupTx > 0 && approver) show("حركات قد تكون مكررة", r.dupTx + " زوج حركات متشابهة من جهازين — راجعها من «المزامنة».");
        // 10.16: تعارضات المزامنة وتنبيهاتها، ودخول جهاز أو خروجه
        boolean watcher = approver || Auth.isAdmin(SmartApp.currentUser);
        if (r.review() > 0 && watcher && !"0".equals(db.setting("notif_sync_review", "1"))) {
            java.util.List<String[]> cs = SyncEngine.conflicts(db);
            show(r.review() == 1 ? "تنبيه من المزامنة بحاجة لمراجعتك" : r.review() + " تنبيهات من المزامنة بحاجة لمراجعتك",
                    cs.isEmpty() ? "راجعها من «المزامنة»." : SyncStore.kindName(cs.get(0)[2]) + ": " + cs.get(0)[5]);
        }
        boolean admin = !db.usersEnabled() || Auth.isAdmin(SmartApp.currentUser);
        if (!r.news.isEmpty() && admin && !"0".equals(db.setting("notif_sync_devices", "1"))) show("أجهزة المزامنة", String.join("\n", r.news));
    }
}
