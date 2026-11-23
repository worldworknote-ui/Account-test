package com.smart.hawala.desk;

import com.smart.hawala.Report;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * يعرض أي تقرير (Report) على الشاشة كما سيُصدَّر: الأقسام، مربعات الملخص، والجداول بأنماط الصفوف.
 * فالذي تراه هو نفسه ما في PDF وExcel (نموذج واحد للتقرير).
 */
final class ReportPanel {
    private ReportPanel() {}

    static JPanel render(Report r, int width) {
        JPanel p = Ui.column();
        for (String h : r.headerLines) p.add(Ui.para(h, 13, Ui.muted, width));
        if (!r.headerLines.isEmpty()) p.add(Ui.gap(10));
        for (Report.Section s : r.sections) {
            p.add(section(s, width));
            p.add(Ui.gap(14));
        }
        if (r.sections.isEmpty()) p.add(Ui.label("لا توجد بيانات.", 14, Ui.muted, false));
        return p;
    }

    static JComponent section(Report.Section s, int width) {
        Ui.Card c = Ui.card();
        Color accent = s.accent != 0 ? new Color(s.accent, false) : Ui.primary;
        if (s.heading != null && !s.heading.isEmpty()) {
            JLabel h = Ui.label(s.heading, 16, Ui.darker(accent, 0.8f), true);
            c.add(h);
            c.add(Ui.gap(6));
        }
        if (!s.summary.isEmpty()) {
            JPanel g = new JPanel(new GridLayout(0, 1, 0, 3));
            g.setOpaque(false);
            g.setAlignmentX(Component.RIGHT_ALIGNMENT);
            for (Report.Kv kv : s.summary) {
                JLabel l = Ui.label("<html><div style='text-align:right;width:" + Ui.css(Math.max(300, width - 120)) + "px'><b>" + Ui.esc(kv.label) + ":</b> "
                        + Ui.esc(Report.text(kv.value)) + "</div></html>", 13.5f, kv.emphasis ? Ui.red : Ui.ink, false);
                g.add(l);
            }
            c.add(g);
            c.add(Ui.gap(8));
        }
        if (!s.cols.isEmpty() && !s.rows.isEmpty()) {
            String[] cols = new String[s.cols.size()];
            for (int i = 0; i < cols.length; i++) cols[i] = s.cols.get(i).title;
            DefaultTableModel m = new DefaultTableModel(cols, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            for (Report.Row row : s.rows) {
                Object[] cells = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) cells[i] = i < row.cells.length ? row.cells[i] : "";
                m.addRow(cells);
            }
            final List<Report.Row> rows = s.rows;
            final List<Report.Col> types = s.cols;
            JTable t = Ui.table(m);
            t.setAutoCreateRowSorter(false);
            DefaultTableCellRenderer rend = new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int row, int col) {
                    Object shown = v instanceof Report.Money || v instanceof Report.Bal || v instanceof Report.DateV ? v.toString() : Report.text(v);
                    super.getTableCellRendererComponent(tb, Pdf.clean(shown.toString()).replace('\n', ' '), sel, false, row, col);
                    setBorder(new EmptyBorder(0, 8, 0, 8));
                    Report.Row rw = rows.get(row);
                    int type = types.get(col).type;
                    setHorizontalAlignment(type == Report.TEXT ? SwingConstants.RIGHT : SwingConstants.CENTER);
                    setFont(Ui.font(13, rw.style == Report.BOLD || rw.style == Report.TOTAL || v instanceof Report.Bal));
                    Color fg = Ui.ink;
                    if (rw.style == Report.MUTED) fg = Ui.muted;
                    else if (v instanceof Report.Bal) fg = ((Report.Bal) v).v > 0 ? Ui.green : ((Report.Bal) v).v < 0 ? Ui.red : Ui.muted;
                    else if (rw.tint != 0 && (v instanceof Report.Money)) fg = Ui.darker(new Color(rw.tint, false), 0.8f);
                    setForeground(fg);
                    if (!sel) {
                        // 10.18: ألوان الجدول من المظهر الحالي (كانت بيضاء ثابتة فتظهر فاقعة في الوضع الليلي)
                        Color bg = rw.style == Report.TOTAL ? Ui.mix(Ui.surface, accent, 0.18f) : rw.style == Report.SHADED ? Ui.stripe
                                : rw.tint != 0 ? Ui.mix(Ui.surface, new Color(rw.tint, false), 0.07f) : Ui.surface;
                        setBackground(bg);
                    }
                    setToolTipText(shown.toString().length() > 40 ? "<html><div style='width:360px'>" + Ui.esc(Pdf.clean(shown.toString())) + "</div></html>" : null);
                    return this;
                }
            };
            t.setDefaultRenderer(Object.class, rend);
            float sum = 0;
            for (Report.Col col : s.cols) sum += col.weight;
            int tw = Math.max(600, width - 40);
            for (int i = 0; i < s.cols.size(); i++) t.getColumnModel().getColumn(i).setPreferredWidth((int) (tw * s.cols.get(i).weight / sum));
            t.getTableHeader().setBackground(Ui.mix(Ui.surface, accent, Ui.dark ? 0.30f : 0.14f));
            t.getTableHeader().setForeground(Ui.dark ? Ui.ink : Ui.darker(accent, 0.7f));
            JScrollPane sp = Ui.tableBox(t);
            // 10.18: الجدول بكامل صفوفه (كان يُقص: صف الملخص لا يظهر كاملًا، والكشف الطويل في نافذة صغيرة داخلية)؛ الصفحة هي التي تتمرر
            int h = s.rows.size() * t.getRowHeight() + Math.max(36, t.getTableHeader().getPreferredSize().height) + 6;
            sp.setPreferredSize(new Dimension(tw, h));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
            Ui.passWheel(sp);
            t.applyComponentOrientation(Ui.RTL);
            c.add(sp);
        }
        if (s.note != null && !s.note.isEmpty()) {
            c.add(Ui.gap(6));
            c.add(Ui.para(s.note, 12.5f, Ui.muted, Math.max(300, width - 150)));
        }
        return c;
    }
}
