package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import javax.swing.*;

/**
 * اعتمادية البرنامج (كالهاتف): ضوء أخضر/أصفر/أحمر لآخر نسخة، النسخ التلقائي، فحص البيانات (سلامة الملف، الروابط، توازن القيود،
 * رصيد كل مكتب = حركاته، تسلسل سجل العمليات)، مفتاح التشفير، القفل، المساحة، وتمرين الاستعادة.
 */
final class HealthView extends View {
    static final long DAY = 86400000L;
    final JPanel results = Ui.column();

    HealthView(App app) {
        super(app, "اعتمادية البرنامج");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        action(Ui.primary("افحص البيانات الآن", e -> runNow()));
        long now = Clock.now();
        long last = db.settingLong("last_backup", 0);
        boolean verified = "verified".equals(db.setting("last_backup_status", ""));
        int lb = last == 0 ? 2 : now - last > 10 * DAY ? 2 : now - last > 3 * DAY || !verified ? 1 : 0;
        item(lb, "آخر نسخة احتياطية" + (verified ? " متحقَّق منها" : ""), last == 0 ? "لا توجد نسخة بعد. الجهاز وحده لا يكفي." : Model.date(last), "نسخة الآن", () -> {
            app.go("backup");
            ((BackupView) app.views.get("backup")).create();
        });
        String err = db.setting("auto_backup_error", "");
        if (!DeskBackup.enabled(db)) item(1, "النسخ التلقائي متوقف", "فعّله إلى مجلد (مثل iCloud Drive) حتى لا تعتمد على التذكّر.", "الإعداد", () -> app.go("backup"));
        else item(err.isEmpty() ? 0 : 2, err.isEmpty() ? "النسخ التلقائي يعمل" : "فشل النسخ التلقائي الأخير",
                err.isEmpty() ? "كل " + DeskBackup.intervalHours(db) + " ساعة" + (DeskBackup.everyTx(db) > 0 ? " أو بعد " + DeskBackup.everyTx(db) + " حركة" : "") : err.trim(), "التفاصيل", () -> app.go("backup"));
        String hs = db.setting("health_status", "");
        long hl = db.settingLong("health_last", 0);
        if (hs.isEmpty()) item(1, "فحص سلامة البيانات", "لم يُفحص بعد.", "افحص الآن", this::runNow);
        else if ("ok".equals(hs)) item(now - hl > 3 * DAY ? 1 : 0, "البيانات سليمة", "القيود متوازنة، الأرصدة تطابق الحركات، الملف والسجل سليمان — " + Model.date(hl), "افحص الآن", this::runNow);
        else {
            int bar = hs.indexOf('|');
            item(2, "فحص البيانات وجد " + (bar > 0 ? hs.substring(0, bar) : "") + " ملاحظة", bar > 0 ? hs.substring(bar + 1) : hs, "التفاصيل", this::runNow);
        }
        boolean key = Crypto.healthy(app.ctx);
        item(key ? 0 : 2, key ? "مفتاح التشفير سليم" : "مفتاح التشفير غير متاح", key ? "المفتاح في «سلسلة المفاتيح» لهذا المستخدم، والحقول المشفّرة تُقرأ." : "لا تُنشأ نسخ جديدة حتى لا تحل محل السليمة. استعد آخر نسخة.", null, null);
        boolean locked = db.usersEnabled();
        item(locked ? 0 : 1, locked ? "البرنامج مقفل بالمستخدمين" : "البرنامج بلا قفل", locked ? "يُطلب الدخول عند الفتح وبعد الخمول." : "أي شخص يفتح الكمبيوتر يرى الحسابات. فعّل المستخدمين لقفل البرنامج.", "المستخدمون", () -> app.go("users"));
        File home = app.ctx.home();
        long free = home.getUsableSpace();
        item(free > 500L * 1024 * 1024 ? 0 : free > 100L * 1024 * 1024 ? 1 : 2, "المساحة الحرة", (free / (1024 * 1024)) + " ميغابايت", null, null);
        String ds = db.setting("restore_drill_status", "");
        long da = db.settingLong("restore_drill_at", 0);
        int dl = ds.startsWith("fail") ? 2 : ds.startsWith("ok") && now - da <= 31 * DAY ? 0 : 1;
        item(dl, "تمرين الاستعادة", ds.isEmpty() ? "لم تُجرَّب استعادة أي نسخة بعد. النسخة التي لم تُجرَّب لا تُعدّ نسخة." : ds.substring(ds.indexOf('|') + 1) + " — " + Model.date(da),
                "جرّب الآن", () -> {
                    app.go("backup");
                    ((BackupView) app.views.get("backup")).drill();
                });
        space(10);
        add(results);
        add(Ui.para("مجلد البيانات: " + home.getAbsolutePath(), 12, Ui.muted, w));
        done();
    }

    void item(int level, String title, String detail, String btn, Runnable r) {
        Color c = level == 0 ? Ui.green : level == 1 ? new Color(196, 140, 20) : Ui.red;
        Ui.Card k = Ui.card();
        k.setLayout(new BorderLayout(12, 0));
        JLabel dot = new JLabel(new Icon() {
            @Override public void paintIcon(Component cc, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c);
                g2.fillOval(x, y, 16, 16);
                g2.dispose();
            }

            @Override public int getIconWidth() { return 16; }

            @Override public int getIconHeight() { return 16; }
        });
        k.add(dot, BorderLayout.LINE_START);
        JPanel t = Ui.column();
        t.add(Ui.label(title, 15, Ui.ink, true));
        t.add(Ui.para(detail, 13, Ui.muted, textWidth() - 220));
        k.add(t, BorderLayout.CENTER);
        if (btn != null) k.add(Ui.ghost(btn, e -> r.run()), BorderLayout.LINE_END);
        add(k);
        space(8);
    }

    void runNow() {
        Ui.busy(this, "جارٍ فحص البيانات كاملة…", p -> {
            List<Db.Check> l = db.healthCheck(true);
            db.runHealthAuto();
            return l;
        }, l -> {
            refresh();
            results.removeAll();
            Ui.Card c = Ui.card();
            c.add(Ui.heading("نتيجة الفحص"));
            for (Db.Check ch : l) {
                c.add(Ui.label((ch.ok ? "✓ " : "× ") + ch.title, 14, ch.ok ? Ui.green : Ui.red, true));
                if (ch.detail != null && !ch.detail.isEmpty()) c.add(Ui.para(ch.detail, 12.5f, Ui.muted, textWidth() - 60));
            }
            results.add(c);
            results.add(Ui.gap(10));
            Ui.rtl(results);
            results.revalidate();
            app.updateFoot();
        });
    }
}
