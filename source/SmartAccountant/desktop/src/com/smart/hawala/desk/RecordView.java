package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * 10.6: أساس شاشتي «تسجيل حركة قص عملات» و«تسجيل حركة بين مكتبين» بأسلوب «تسجيل حركة»: النموذج في بطاقة يمنى،
 * والملخص الحي وزر التسجيل في بطاقة يسرى. المال والصلاحيات تُفرض في Db عند الحفظ (هنا العرض والمعاينة فقط).
 */
abstract class RecordView extends View {
    /** فتح الشاشة على مكتب محدد (من صفحة المكتب). */
    static final class Open {
        final long office;

        Open(long office) { this.office = office; }
    }

    final JLabel summary = Ui.label("", 14, Ui.ink, false);
    final JLabel footLine = Ui.label(" ", 13.5f, Ui.red, true);
    Ui.Btn save;
    boolean building, saving;

    RecordView(App app, String title) { super(app, title); }

    /** اختيار مكتب بالبحث والقائمة (كما في «تسجيل حركة»). */
    final class OfficeBox extends JPanel {
        final Ui.Field search = Ui.field("ابحث…");
        /** 10.18: الضغط على القائمة يفتح نافذة «اختر المكتب» كالهاتف (تصفية، آخر المكاتب، مكاتبي) بدل القائمة المنسدلة. */
        final JComboBox<Model.Office> combo = new JComboBox<Model.Office>() {
            @Override public void setPopupVisible(boolean v) {
                if (!v || !isEnabled()) {
                    super.setPopupVisible(false);
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    Model.Office o = OfficePicker.pick(this, db, null);
                    if (o != null) load(o.id, true);
                });
            }
        };
        final JLabel balance = Ui.label(" ", 12.5f, Ui.muted, false);
        Runnable onChange;
        boolean quiet;

        OfficeBox(String empty) {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(Component.RIGHT_ALIGNMENT);
            combo.setFont(Ui.font(15, true));
            combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            combo.setComponentOrientation(Ui.RTL);
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v instanceof Model.Office ? (((Model.Office) v).pinned ? " " : "") + ((Model.Office) v).name : "— " + empty + " —", i, s, f);
                    c.setHorizontalAlignment(SwingConstants.RIGHT);
                    c.setFont(Ui.font(14, v != null));
                    return c;
                }
            });
            combo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            combo.setToolTipText("انقر لاختيار المكتب بالبحث");
            add(combo);
            add(Ui.gap(4));
            add(balance);
            search.getDocument().addDocumentListener(TxView.doc(() -> load(get() == null ? -1 : get().id, true)));
            combo.addActionListener(e -> {
                if (!quiet) changed();
            });
        }

        Model.Office get() { return (Model.Office) combo.getSelectedItem(); }

        void load(long select, boolean fire) {
            quiet = true;
            String q = OwnerReports.norm(search.getText());
            DefaultComboBoxModel<Model.Office> m = new DefaultComboBoxModel<>();
            m.addElement(null);
            for (Model.Office o : myFirst(db, db.offices()))
                if (o.active && (q.isEmpty() || OwnerReports.norm(o.name + " " + Model.nz(o.shortName)).contains(q))) m.addElement(o);
            combo.setModel(m);
            for (int i = 0; i < m.getSize(); i++) if (m.getElementAt(i) != null && m.getElementAt(i).id == select) combo.setSelectedIndex(i);
            if (combo.getSelectedItem() == null && !q.isEmpty() && m.getSize() == 2) combo.setSelectedIndex(1);
            quiet = false;
            if (fire) changed();
            else showBalance();
        }

        void showBalance() {
            Model.Office o = get();
            if (o == null) {
                balance.setText(" ");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Long> e : db.balances(o.id).entrySet()) {
                if (sb.length() > 0) sb.append("   •   ");
                sb.append(e.getKey()).append(": ").append(Model.bal(e.getValue(), db.decimals(e.getKey())));
            }
            balance.setText(sb.length() == 0 ? "لا رصيد بعد" : "الرصيد الحالي: " + sb);
        }

        void changed() {
            showBalance();
            if (onChange != null) onChange.run();
        }
    }

    /**
     * 10.7: نوع الحركة أعلى صفحة «تسجيل حركة» (عادية، قص عملات، بين مكتبين) — بدل القائمة نصف الدائرية.
     * المكتب المختار يصير «آخر مكتب» فيظهر مختارًا في النوع الآخر؛ وكل نوع يحتفظ بما كُتب فيه.
     */
    static Ui.Seg kinds(App app, String current, java.util.function.Supplier<Model.Office> office) {
        List<RecordMenu.Item> items = RecordMenu.allowed(app::can);
        String[] labels = new String[items.size()];
        int sel = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = items.get(i).label;
            if (items.get(i).key.equals(current)) sel = i;
        }
        Ui.Seg s = new Ui.Seg(labels, null, sel);
        final int own = sel;
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        s.setVisible(items.size() > 1);
        s.listener = i -> {
            String k = items.get(i).key;
            if (k.equals(current)) return;
            s.set(own);
            Model.Office o = office == null ? null : office.get();
            if (o != null) app.db.setSettingQuiet("last_office", "" + o.id);
            app.go(k);
        };
        return s;
    }

    /** 10.7: «مكاتبي» أولًا في قوائم اختيار المكتب (اختصار للمحاسب)، ثم الباقي بترتيبه. */
    static List<Model.Office> myFirst(Db db, List<Model.Office> all) {
        java.util.Set<Long> mine = new java.util.HashSet<>();
        for (Model.Office o : db.myOffices()) mine.add(o.id);
        if (mine.isEmpty()) return all;
        List<Model.Office> out = new ArrayList<>();
        for (Model.Office o : all) if (mine.contains(o.id)) out.add(o);
        for (Model.Office o : all) if (!mine.contains(o.id)) out.add(o);
        return out;
    }

    /** بطاقة ملوّنة بعنوان ونقطة بلونها (طرف «لنا» بالأخضر و«علينا» بالأحمر). */
    static Ui.Card side(String title, String sub, Color color, Color fill) {
        Ui.Card c = Ui.tinted(fill, Ui.mix(fill, color, 0.45f));
        c.setBorder(new EmptyBorder(14, 16, 16, 16));
        JLabel t = Ui.label(title, 15.5f, color, true);
        t.setIcon(new Icon() {
            @Override public void paintIcon(Component cc, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x, y + 1, 10, 10);
                g2.dispose();
            }

            @Override public int getIconWidth() { return 10; }

            @Override public int getIconHeight() { return 12; }
        });
        t.setIconTextGap(8);
        c.add(t);
        if (sub != null) c.add(Ui.label(sub, 12, Ui.muted, false));
        c.add(Ui.gap(8));
        return c;
    }

    /** أزرار عملات (لا عملة محددة مسبقًا) داخل حامل. */
    static Ui.Seg chips(JPanel holder, List<String> codes, Color color, java.util.function.Consumer<Integer> l, String empty) {
        holder.removeAll();
        if (codes.isEmpty()) {
            holder.add(Ui.label(empty, 13, Ui.red, false));
            holder.revalidate();
            return null;
        }
        Ui.Seg s = new Ui.Seg(codes.toArray(new String[0]), new Color[]{color}, -1);
        s.listener = l;
        s.setMaximumSize(new Dimension(Math.min(Integer.MAX_VALUE, 96 * codes.size()), 40));
        holder.add(s);
        holder.revalidate();
        holder.repaint();
        return s;
    }

    static String label(Ui.Seg s) { return s == null || s.none() ? null : s.buttons.get(s.selected).getText(); }

    static JPanel holder() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.RIGHT_ALIGNMENT);
        p.setComponentOrientation(Ui.RTL);
        return p;
    }

    /** بطاقة الملخص اليسرى (الملخص + سطر الحالة + زر التسجيل). */
    JPanel summaryColumn(String saveLabel, Runnable onSave) {
        JPanel left = Ui.column();
        Ui.Card sc = Ui.tinted(Ui.primarySoft, null);
        sc.add(Ui.label("الملخص", 15, Ui.primaryInk, true));
        sc.add(Ui.gap(6));
        sc.add(summary);
        sc.add(Ui.gap(10));
        sc.add(footLine);
        sc.add(Ui.gap(8));
        save = Ui.holdToConfirm(Ui.primary(saveLabel, e -> onSave.run()));
        save.setFont(Ui.font(15, true));
        sc.add(Ui.flow(save, Ui.ghost("تفريغ النموذج", e -> clearForm())));
        left.add(sc);
        return left;
    }

    /** النموذج يمينًا والملخص يسارًا (كتسجيل حركة). */
    void columns(JPanel form, JPanel left) {
        Ui.Card fc = Ui.card();
        fc.add(form);
        JPanel right = Ui.column();
        right.add(fc);
        JPanel cols = new JPanel(new GridLayout(1, 2, 18, 0));
        cols.setOpaque(false);
        cols.add(right);
        JPanel lw = new JPanel(new BorderLayout());
        lw.setOpaque(false);
        lw.add(left, BorderLayout.NORTH);
        cols.add(lw);
        cols.setAlignmentX(Component.RIGHT_ALIGNMENT);
        body.add(cols);
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);
    }

    abstract void clearForm();

    static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    /** يرسم الملخص (الناقص بالأحمر مع ✗) وسطر الحالة فوق زر التسجيل. */
    void render(List<String> lines, String okLine) {
        StringBuilder h = new StringBuilder("<html><div style='text-align:right;width:" + Ui.css(400) + "px'>");
        List<String> missing = new ArrayList<>();
        boolean inTable = false;
        for (String l : lines) {
            // 10.8: جدول المقاصة «▤عملة|قبل|الأثر|بعد»
            if (l.startsWith("▤")) {
                if (!inTable) {
                    h.append("<table cellpadding='3' style='margin:4px 0'><tr style='color:").append(hex(Ui.muted)).append("'><td align='right'>بعد</td><td align='right'>أثر العملية</td>")
                            .append("<td align='right'>قبل</td><td align='right'>العملة</td></tr>");
                    inTable = true;
                }
                String[] c = l.substring(1).split("\\|", 4);
                h.append("<tr><td align='right'><b>").append(Ui.esc(c[3])).append("</b></td><td align='right' style='color:").append(hex(c[2].startsWith("خصم") ? Ui.red : Ui.green))
                        .append("'>").append(Ui.esc(c[2])).append("</td><td align='right'>").append(Ui.esc(c[1])).append("</td><td align='right'><b>").append(Ui.esc(c[0])).append("</b></td></tr>");
                continue;
            }
            if (inTable) {
                h.append("</table>");
                inTable = false;
            }
            if (l.startsWith("!")) {
                // تنبيه غير مانع: سطر كامل بالأحمر، لا يُعد ناقصًا
                h.append("<div style='margin:6px 0;color:").append(hex(Ui.red)).append("'><b>").append(Ui.esc(l)).append("</b></div>");
                continue;
            }
            boolean bad = l.startsWith("×");
            String b = bad ? l.substring(1).trim() : l;
            int c = b.indexOf(": ");
            String label = c > 0 ? b.substring(0, c) : b, value = c > 0 ? b.substring(c + 2) : "";
            if (bad) missing.add(label);
            h.append("<div style='margin:3px 0'><span style='color:").append(hex(bad ? Ui.red : Ui.muted)).append("'>").append(bad ? "× " : "").append(Ui.esc(label))
                    .append(":</span>&nbsp; <b style='color:").append(hex(bad ? Ui.red : Ui.ink)).append("'>").append(Ui.esc(value)).append("</b></div>");
        }
        if (inTable) h.append("</table>");
        summary.setText(h.append("</div></html>").toString());
        if (missing.isEmpty()) {
            footLine.setText("✓ " + okLine);
            footLine.setForeground(Ui.primaryInk);
        } else {
            footLine.setText("ناقص: " + Model.join(missing, "، "));
            footLine.setForeground(Ui.red);
        }
    }

    String amountText(Ui.Field f, String cur) {
        try {
            int d = db.decimals(cur);
            long v = Db.minor(f.getText(), d);
            return v > 0 ? "\u2066" + Model.fmt(v, d) + " " + cur + "\u2069" : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** زر صغير في منتصف النموذج (قلب العملية / تبديل الطرفين). */
    /** زر «قلب/تبديل» بأيقونة السهمين (الرمز ⇅ لا يوجد في كل الخطوط). */
    static Ui.Btn swapButton(String text, java.awt.event.ActionListener a) {
        Ui.Btn b = Ui.ghost(text, a);
        b.setIcon(new Icons("xfer", 16, Ui.primaryInk));
        b.setIconTextGap(6);
        return b;
    }

    static JComponent centered(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.RIGHT_ALIGNMENT);
        p.add(c);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        return p;
    }

    static void big(Ui.Field f) {
        f.setFont(Ui.font(19, true));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
    }
}
