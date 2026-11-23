package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import javax.swing.*;

/** أرصدة كل المكاتب (Reports.balances كالهاتف): لكل مكتب وعملة مجموع لنا وعلينا والرصيد، مع الإجماليات. */
final class BalancesView extends View {
    final JCheckBox zero = Ui.check("إظهار المكاتب المسدَّدة (رصيد صفر)", false);
    final JPanel out = Ui.column();

    BalancesView(App app) {
        super(app, "أرصدة كل المكاتب");
    }

    @Override void build() {
        body.add(Ui.flow(zero));
        body.add(Ui.gap(10));
        body.add(out);
        zero.addActionListener(e -> refresh());
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);
    }

    Report report() {
        return db.withHolds(Reports.balances(db.officeTotals(null), db.decimalsMap(), db.currencyNames(), zero.isSelected(), db.setting("office_name", ""), Clock.now()));
    }

    @Override void refresh() {
        actions.removeAll();
        action(Ui.primary("تصدير / طباعة…", e -> Export.dialog(this, app, "أرصدة المكاتب", this::report)));
        out.removeAll();
        out.add(ReportPanel.render(report(), textWidth()));
        Ui.rtl(out);
        out.revalidate();
        done();
    }
}
