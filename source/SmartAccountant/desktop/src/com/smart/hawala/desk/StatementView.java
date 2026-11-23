package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * كشف حساب مكتب: الفترة (أو «بعد آخر مطابقة»)، العملات، والرصيد المتراكم — بالدالة نفسها التي يصدّر بها الهاتف (Reports.statement)،
 * فالكشف على الشاشة = PDF = Excel. مع خيارات التصميم (إظهار المستفيد، البيان، الملغاة…).
 */
final class StatementView extends View {
    /** كشف ما بعد مطابقة. */
    static final class Since {
        final long office, recon;

        Since(long office, long recon) {
            this.office = office;
            this.recon = recon;
        }
    }

    final JComboBox<Model.Office> office = Ui.pickCombo();
    final JComboBox<String> period = Ui.combo(Ui.PERIODS);
    final Ui.DateField from = new Ui.DateField(0), to = new Ui.DateField(0);
    final JPanel curBox = Ui.flow();
    final Map<String, JCheckBox> curChecks = new LinkedHashMap<>();
    final JCheckBox hideCancelled = Ui.check("إخفاء الملغاة", true), images = Ui.check("مع صور الوصل", false), newest = Ui.check("الأحدث أولًا", false);
    final JCheckBox showPerson = Ui.check("المستفيد", true), showDesc = Ui.check("البيان", true), showNotes = Ui.check("الملاحظات", false);
    final JPanel out = Ui.column();
    JPanel custom;
    Long sinceRecon;
    boolean loading;

    StatementView(App app) {
        super(app, "كشف حساب");
    }

    @Override void build() {
        office.setFont(Ui.font(14, true));
        office.setComponentOrientation(Ui.RTL);
        office.setMaximumSize(new Dimension(300, 38));
        office.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v instanceof Model.Office ? ((Model.Office) v).name : "— اختر المكتب —", i, s, f);
                c.setHorizontalAlignment(SwingConstants.RIGHT);
                return c;
            }
        });
        period.setSelectedIndex(8);
        period.setMaximumSize(new Dimension(170, 38));
        custom = Ui.row(Ui.label("من", 13, Ui.muted, true), from, Ui.label("إلى", 13, Ui.muted, true), to);
        custom.setMaximumSize(new Dimension(520, 40));
        custom.setVisible(false);
        Ui.Card opts = Ui.card();
        opts.add(Ui.row(office, period, custom));
        opts.add(Ui.gap(8));
        opts.add(curBox);
        opts.add(Ui.flow(hideCancelled, newest, images, Ui.label("  |  يظهر:", 13, Ui.muted, true), showPerson, showDesc, showNotes));
        body.add(opts);
        body.add(Ui.gap(14));
        body.add(out);
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);
        Runnable re = () -> {
            if (!loading) showReport();
        };
        office.addActionListener(e -> {
            if (loading) return;
            sinceRecon = null;
            loadCurrencies();
            showReport();
        });
        period.addActionListener(e -> {
            custom.setVisible(period.getSelectedIndex() == Ui.PERIODS.length - 1);
            sinceRecon = null;
            re.run();
        });
        from.onChange = re;
        to.onChange = re;
        for (JCheckBox c : new JCheckBox[]{hideCancelled, images, newest, showPerson, showDesc, showNotes}) c.addActionListener(e -> re.run());
    }

    @Override void open(Object arg) {
        loading = true;
        loadOffices();
        long id = arg instanceof Since ? ((Since) arg).office : arg instanceof Long ? (Long) arg : -1;
        for (int i = 0; i < office.getItemCount(); i++) if (office.getItemAt(i) != null && office.getItemAt(i).id == id) office.setSelectedIndex(i);
        sinceRecon = arg instanceof Since ? ((Since) arg).recon : null;
        loading = false;
        loadCurrencies();
        refreshActions();
        showReport();
        done();
    }

    @Override void refresh() {
        loading = true;
        Model.Office cur = (Model.Office) office.getSelectedItem();
        loadOffices();
        if (cur != null) for (int i = 0; i < office.getItemCount(); i++) if (office.getItemAt(i) != null && office.getItemAt(i).id == cur.id) office.setSelectedIndex(i);
        loading = false;
        loadCurrencies();
        refreshActions();
        showReport();
        done();
    }

    void refreshActions() {
        actions.removeAll();
        action(Ui.primary("تصدير / طباعة…", e -> export()));
        action(Ui.ghost("مطابقة الرصيد", e -> {
            Model.Office o = (Model.Office) office.getSelectedItem();
            if (o != null) app.go("office", o.id);
        }));
    }

    void loadOffices() {
        DefaultComboBoxModel<Model.Office> m = new DefaultComboBoxModel<>();
        m.addElement(null);
        for (Model.Office o : db.offices()) m.addElement(o);
        office.setModel(m);
    }

    void loadCurrencies() {
        curBox.removeAll();
        curChecks.clear();
        Model.Office o = (Model.Office) office.getSelectedItem();
        if (o == null) return;
        Set<String> codes = new TreeSet<>(db.officeCurrencies(o.id));
        codes.addAll(db.balances(o.id).keySet());
        curBox.add(Ui.label("العملات:", 13, Ui.muted, true));
        for (String c : codes) {
            JCheckBox cb = Ui.check(c, true);
            cb.addActionListener(e -> showReport());
            curChecks.put(c, cb);
            curBox.add(cb);
        }
        curBox.revalidate();
    }

    ExportStyle style() {
        ExportStyle st = Export.style(db);
        st.hideCancelled = hideCancelled.isSelected();
        st.newestFirst = newest.isSelected();
        st.showPerson = showPerson.isSelected();
        st.showDesc = showDesc.isSelected();
        st.showNotes = showNotes.isSelected();
        return st;
    }

    Report build(boolean forExport) {
        Model.Office o = (Model.Office) office.getSelectedItem();
        if (o == null) throw new IllegalArgumentException("اختر المكتب");
        ExportStyle st = style();
        Reports.StatementInput in = new Reports.StatementInput();
        in.officeName = o.name;
        in.issuer = db.setting("office_name", "");
        in.currencyNames = db.currencyNames();
        in.decimals = db.decimalsMap();
        in.fullBalance = db.balances(o.id);
        Set<String> only = new LinkedHashSet<>();
        for (Map.Entry<String, JCheckBox> e : curChecks.entrySet()) if (e.getValue().isSelected()) only.add(e.getKey());
        in.onlyCurrencies = only.size() == curChecks.size() ? null : only;
        List<Model.Line> lines;
        if (sinceRecon != null) {
            Model.Recon since = db.recon(sinceRecon);
            in.periodLabel = "بعد مطابقة " + Model.date(since.createdAt) + " (" + since.statusLabel() + ")";
            in.periodHasStart = true;
            in.opening = db.balanceUpTo(o.id, since);
            in.openingLabel = "رصيد المطابقة بتاريخ " + Model.date(since.createdAt);
            in.openingKv = "رصيد آخر مطابقة";
            in.openingCol = "رصيد المطابقة";
            List<Model.Line> after = db.statementLinesAfter(o.id, since);
            Set<Long> originalsAfter = new HashSet<>();
            for (Model.Line l : after) if (l.txId != null && !"REVERSAL".equals(l.kind)) originalsAfter.add(l.txId);
            lines = new ArrayList<>();
            for (Model.Line l : after) {
                if (st.hideCancelled && l.cancelledTx() && l.txId != null && originalsAfter.contains(l.txId)) continue;
                lines.add(l);
            }
        } else {
            long[] r = range();
            in.periodLabel = (String) period.getSelectedItem();
            if (period.getSelectedIndex() == Ui.PERIODS.length - 1) in.periodLabel = (r[0] > 0 ? Model.day(r[0]) : "البداية") + " ← " + (r[1] < Long.MAX_VALUE ? Model.day(r[1] - 1) : "الآن");
            in.periodHasStart = r[0] > 0;
            if (r[0] > 0) in.opening = db.balanceBefore(o.id, r[0], st.hideCancelled);
            lines = db.statementLines(o.id, r[0], r[1]);
        }
        in.lines = lines;
        if (forExport && images.isSelected()) {
            Map<Long, List<Long>> m = new HashMap<>();
            for (Model.Line l : lines) if (l.txId != null && "PRINCIPAL".equals(l.kind) && !m.containsKey(l.txId)) {
                List<Long> ids = db.attachmentIds(l.txId);
                if (!ids.isEmpty()) m.put(l.txId, ids);
            }
            in.attachments = m;
        }
        return Reports.statement(in, st);
    }

    long[] range() {
        int p = period.getSelectedIndex();
        if (p == Ui.PERIODS.length - 1) {
            long f = 0, t = 0;
            try {
                f = from.start();
                t = to.endExclusive();
            } catch (Exception ignored) {
                // بلا حد
            }
            return new long[]{f, t == 0 ? Long.MAX_VALUE : t};
        }
        long[] r = Ui.period(p);
        return new long[]{r[0], r[1] == 0 ? Long.MAX_VALUE : r[1]};
    }

    void showReport() {
        out.removeAll();
        Model.Office o = (Model.Office) office.getSelectedItem();
        if (o == null) {
            out.add(Ui.label("اختر مكتبًا لعرض كشفه.", 15, Ui.muted, false));
        } else {
            try {
                Report r = build(false);
                titleLabel.setText("كشف حساب: " + o.name);
                subtitle.setText(sinceRecon != null ? "ما بعد آخر مطابقة" : "");
                out.add(ReportPanel.render(r, textWidth()));
            } catch (Exception e) {
                out.add(Ui.label(Ui.friendly(e), 14, Ui.red, false));
            }
        }
        Ui.rtl(out);
        out.revalidate();
        out.repaint();
    }

    void export() {
        Model.Office o = (Model.Office) office.getSelectedItem();
        if (o == null) {
            Ui.toast("اختر المكتب أولًا");
            return;
        }
        Export.dialog(this, app, "كشف حساب " + o.name, () -> build(true));
    }
}
