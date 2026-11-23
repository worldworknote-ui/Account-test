package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** التقارير الجاهزة: بطاقات تفتح كل تقرير، وقوالب منشئ التقارير، وسجل العمليات (من فعل ماذا ومتى). */
final class ReportsView extends View {
    /** 10.12: فترة تقرير التحقق (افتراضيًا هذا الشهر). */
    int teamPeriod = ReportBuilder.P_MONTH;

    ReportsView(App app) {
        super(app, "تقارير جاهزة");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        JPanel g = new JPanel(new GridLayout(0, 3, 12, 12));
        g.setOpaque(false);
        g.setAlignmentX(Component.RIGHT_ALIGNMENT);
        g.add(card("كشف حساب مكتب", "حركات مكتب لفترة أو بعد آخر مطابقة، برصيد متراكم لكل عملة.", () -> app.go("statement")));
        g.add(card("أرصدة كل المكاتب", "لنا وعلينا والرصيد لكل مكتب وعملة، مع الإجماليات.", () -> app.go("balances")));
        g.add(card("التقرير اليومي", "حركات يوم واحد ومجاميعه، مع بصمة تكشف أي تعديل لاحق.", () -> app.go("daily")));
        g.add(card("سجل الحركات", "تصفية وبحث في كل الحركات وتصديرها.", () -> app.go("log")));
        g.add(card("تقارير صاحب المكتب", "النمو، التركّز، عمر الديون، المكاتب الصاعدة والمتراجعة، الأجور والراجع.", () -> app.go("owner")));
        g.add(card("منشئ التقارير", "تقرير بخياراتك: 16 طريقة تجميع، جدول متقاطع، مقارنة بفترة سابقة، قوالب.", () -> app.go("builder")));
        add(g);
        space(18);
        add(Ui.heading("قوالب جاهزة في منشئ التقارير"));
        space(8);
        JPanel q = new JPanel(new GridLayout(0, 3, 10, 10));
        q.setOpaque(false);
        q.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (Object[] b : BuilderView.builtIn()) {
            final String name = BuilderView.READY + b[0];
            Ui.Btn bt = Ui.ghost((String) b[0], e -> {
                app.go("builder");
                View v = app.views.get("builder");
                if (v instanceof BuilderView) ((BuilderView) v).presets.setSelectedItem(name);
            });
            q.add(bt);
        }
        add(q);
        // 10.12: التسجيل والتحقق بين المحاسبين (مع نظام المستخدمين) — التقرير نفسه في الهاتف
        if (db.usersEnabled()) {
            space(18);
            add(Ui.heading("التسجيل والتحقق بين المحاسبين"));
            add(Ui.para("لكل مكتب من يسجّله ومن يتحقق منه، وكم حركة تحقق منها محاسب آخر وكم تنتظر؛ ولكل محاسب ما سجّله وما تحقق منه؛ ومطابقات الفترة.", 13, Ui.muted, w));
            space(6);
            String[] ps = java.util.Arrays.copyOf(ReportBuilder.PERIODS, ReportBuilder.P_CUSTOM);
            final JComboBox<String> per = Ui.combo(ps);
            per.setSelectedIndex(teamPeriod);
            final JPanel out = Ui.column();
            final java.util.function.Supplier<Report> make = () -> {
                long[] r = ReportBuilder.period(per.getSelectedIndex(), Clock.now());
                Report rep = db.teamCheckReport(r[0], r[1], (String) per.getSelectedItem());
                rep.stamp(License.current.watermark(), License.current.lid);
                return rep;
            };
            Runnable show = () -> {
                teamPeriod = per.getSelectedIndex();
                out.removeAll();
                try {
                    out.add(ReportPanel.render(make.get(), w));
                } catch (Exception ex) {
                    out.add(Ui.label(Ui.friendly(ex), 14, Ui.red, false));
                }
                Ui.rtl(out);
                out.revalidate();
                out.repaint();
            };
            per.addActionListener(e -> show.run());
            add(Ui.flow(Ui.labeled("الفترة", per), Ui.primary("تصدير / طباعة…", e -> Export.dialog(this, app, "التسجيل والتحقق بين المحاسبين", make))));
            space(6);
            add(out);
            show.run();
        }
        space(18);
        add(Ui.heading("سجل العمليات (آخر 300)"));
        add(Ui.para("كل إضافة وتعديل وإلغاء ودخول ونسخ واستعادة باسم من فعلها. السجل متسلسل بالبصمات: أي حذف أو تعديل فيه يظهر في «الاعتمادية».", 13, Ui.muted, w));
        space(6);
        List<String[]> log = db.auditLogAll(300);
        if (SyncEngine.enabled(db)) add(Ui.para("يشمل ما جرى على كل أجهزة المزامنة (العمود «الجهاز»).", 13, Ui.muted, w));
        DefaultTableModel m = new DefaultTableModel(new Object[]{"الوقت", "العملية", "المستخدم", "الجهاز", "التفاصيل"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String[] r : log) m.addRow(new Object[]{r[0], r[1], r[4], r[6], (r[2].isEmpty() ? "" : r[2] + " " + r[3] + " ") + Ui.cut(r[5], 160)});
        JTable t = Ui.table(m);
        Ui.widths(t, 150, 200, 130, 130, 460);
        JScrollPane sp = Ui.tableBox(t);
        sp.setPreferredSize(new Dimension(800, 420));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));
        add(sp);
        done();
    }

    JComponent card(String title, String desc, Runnable go) {
        Ui.Card c = Ui.card();
        c.add(Ui.label(title, 16, Ui.primaryInk, true));
        c.add(Ui.gap(4));
        c.add(Ui.para(desc, 13, Ui.muted, 260));
        c.add(Ui.gap(8));
        c.add(Ui.flow(Ui.primary("فتح", e -> go.run())));
        return c;
    }
}
