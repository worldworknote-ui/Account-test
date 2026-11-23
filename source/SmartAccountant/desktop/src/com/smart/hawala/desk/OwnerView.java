package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import javax.swing.*;

/** تقارير صاحب المكتب (OwnerReports كالهاتف): النمو، التركّز، عمر الديون، الخامل، الصاعد والمتراجع، الأجور والراجع، المرتجعات، الخدمات، الحجم، الجودة. */
final class OwnerView extends View {
    final JCheckBox allTime = Ui.check("التركّز على كل الفترة (بدل آخر 12 شهرًا)", false);
    final JPanel out = Ui.column();
    Report last;

    OwnerView(App app) {
        super(app, "تقارير صاحب المكتب");
    }

    @Override void build() {
        body.add(Ui.flow(allTime));
        body.add(Ui.gap(8));
        body.add(out);
        allTime.addActionListener(e -> refresh());
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);
    }

    OwnerReports.Input input() {
        OwnerReports.Input in = new OwnerReports.Input();
        in.now = Clock.now();
        in.txs = db.reportTxs();
        in.openings = db.reportOpenings();
        in.names = db.officeNames();
        in.decimals = db.decimalsMap();
        in.usdRates = db.usdRates();
        return in;
    }

    Report report() { return OwnerReports.build(input(), db.setting("office_name", ""), allTime.isSelected()); }

    @Override void refresh() {
        actions.removeAll();
        action(Ui.primary("تصدير / طباعة…", e -> Export.dialog(this, app, "تقارير صاحب المكتب", this::report)));
        out.removeAll();
        out.add(Ui.label("جارٍ تحليل الحركات…", 14, Ui.muted, false));
        out.revalidate();
        done();
        new SwingWorker<Report, Void>() {
            @Override protected Report doInBackground() { return report(); }

            @Override protected void done() {
                out.removeAll();
                try {
                    Report r = get();
                    last = r;
                    Ui.Card head = Ui.tinted(Ui.primarySoft, null);
                    head.add(Ui.label("الخلاصة", 17, Ui.primaryInk, true));
                    int n = 0;
                    for (String h : r.headerLines) {
                        if (h.startsWith("صادر عن") || h.startsWith("حتى:")) continue;
                        head.add(Ui.para("• " + Pdf.clean(h), 14, Ui.ink, textWidth() - 40));
                        n++;
                    }
                    if (n == 0) head.add(Ui.label("لا توجد حركات كافية بعد.", 14, Ui.muted, false));
                    head.add(Ui.gap(6));
                    head.add(Ui.para("من الحركات النشطة فقط. خصّص ساعة أول كل شهر لأربعة أرقام: النمو، الديون الأقدم من 90 يومًا، المكاتب الصاعدة والمتراجعة، وصافي الأجور (ومنها «الراجع»).",
                            12.5f, Ui.muted, textWidth() - 40));
                    out.add(head);
                    out.add(Ui.gap(14));
                    for (Report.Section s : r.sections) {
                        out.add(ReportPanel.section(s, textWidth()));
                        out.add(Ui.gap(12));
                    }
                } catch (Exception e) {
                    out.add(Ui.label(Ui.friendly(e), 14, Ui.red, false));
                }
                Ui.rtl(out);
                out.revalidate();
                out.repaint();
            }
        }.execute();
    }
}
