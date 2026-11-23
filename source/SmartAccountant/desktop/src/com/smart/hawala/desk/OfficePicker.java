package com.smart.hawala.desk;

import com.smart.hawala.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 10.18: نافذة «اختر المكتب» كالهاتف: خانة تصفية في الأعلى، «آخر المكاتب»، ثم «مكاتبي» كلها شرائح تتصفى أثناء الكتابة.
 * Enter يختار أول نتيجة، Esc يغلق، والزر الأيمن على مكتب يثبّته في أعلى القائمة أو يلغي تثبيته.
 */
final class OfficePicker {
    private OfficePicker() {}

    /** يرجع المكتب المختار أو null. */
    static Model.Office pick(Component parent, Db db, String title) {
        Window w = parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent);
        final JDialog d = new JDialog(w, title == null ? "اختر المكتب" : title, Dialog.ModalityType.APPLICATION_MODAL);
        final Model.Office[] out = {null};
        final Ui.Field q = Ui.field("اكتب جزءًا من اسم المكتب للتصفية");
        q.setFont(Ui.font(16, false));
        q.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        final Wrap body = new Wrap();
        final List<Model.Office> shown = new ArrayList<>();
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            body.removeAll();
            shown.clear();
            String s = OwnerReports.norm(q.getText());
            List<Model.Office> all = new ArrayList<>();
            for (Model.Office o : RecordView.myFirst(db, db.offices())) if (o.active) all.add(o);
            if (s.isEmpty()) {
                List<Model.Office> recent = new ArrayList<>();
                for (String x : db.setting("recent_offices", "").split(","))
                    for (Model.Office o : all) if (x.trim().equals("" + o.id) && recent.size() < 6) recent.add(o);
                if (!recent.isEmpty()) {
                    body.section("آخر المكاتب");
                    for (Model.Office o : recent) body.chip(o, false, () -> { out[0] = o; d.dispose(); }, () -> pin(db, o, fill[0]));
                }
            }
            body.section(s.isEmpty() ? "مكاتبي" : "النتائج");
            for (Model.Office o : all) {
                if (!s.isEmpty() && !OwnerReports.norm(o.name + " " + Model.nz(o.shortName) + " " + Model.nz(o.phone)).contains(s)) continue;
                shown.add(o);
                body.chip(o, true, () -> { out[0] = o; d.dispose(); }, () -> pin(db, o, fill[0]));
            }
            if (shown.isEmpty()) body.note("لا مكتب يطابق «" + q.getText().trim() + "»");
            body.note("انقر بالزر الأيمن على مكتب لتثبيته في أعلى القائمة أو إلغاء تثبيته.");
            body.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            body.revalidate();
            body.repaint();
        };
        q.getDocument().addDocumentListener(FxView.now(fill[0]));
        q.addActionListener(e -> {
            if (!shown.isEmpty()) {
                out[0] = shown.get(0);
                d.dispose();
            }
        });
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Ui.surface);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));
        root.add(q, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.getViewport().setBackground(Ui.surface);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        root.add(sp, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bottom.setOpaque(false);
        bottom.add(Ui.ghost("إلغاء", e -> d.dispose()));
        root.add(bottom, BorderLayout.SOUTH);
        root.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        d.setContentPane(root);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        fill[0].run();
        d.setSize(560, 640);
        d.setLocationRelativeTo(w);
        SwingUtilities.invokeLater(q::requestFocusInWindow);
        d.setVisible(true);
        return out[0];
    }

    private static void pin(Db db, Model.Office o, Runnable refill) {
        try {
            db.setPinned(o.id, !o.pinned);
            o.pinned = !o.pinned;
            Ui.toast(o.pinned ? "ثُبّت «" + o.name + "» في أعلى القائمة" : "أُلغي تثبيت «" + o.name + "»");
            refill.run();
        } catch (Exception ex) {
            Ui.toast(ex.getMessage());
        }
    }

    /** لوحة تلتف شرائحها على عدة أسطر بعرض النافذة (من اليمين). */
    static final class Wrap extends JPanel implements Scrollable {
        Wrap() {
            setLayout(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            setOpaque(false);
        }

        void section(String t) {
            JLabel l = Ui.label(t, 13.5f, Ui.muted, true);
            l.setBorder(new EmptyBorder(8, 4, 0, 4));
            add(full(l));
        }

        void note(String t) {
            JLabel l = Ui.label(t, 12.5f, Ui.muted, false);
            l.setBorder(new EmptyBorder(6, 4, 0, 4));
            add(full(l));
        }

        /** عنصر يأخذ سطرًا كاملًا. */
        JComponent full(JComponent c) {
            JPanel p = new JPanel(new BorderLayout()) {
                @Override public Dimension getPreferredSize() {
                    Container par = getParent();
                    int w = par == null ? 400 : Math.max(200, par.getWidth() - 20);
                    return new Dimension(w, super.getPreferredSize().height);
                }
            };
            p.setOpaque(false);
            p.add(c, BorderLayout.LINE_START);
            return p;
        }

        void chip(Model.Office o, boolean filled, Runnable pick, Runnable pin) {
            final boolean f = filled;
            JButton b = new JButton((o.pinned ? "• " : "") + o.name) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int h = getHeight() - 1;
                    boolean over = getModel().isRollover();
                    g2.setColor(f ? (over ? Ui.darker(Ui.primary, 0.9f) : Ui.primary) : (over ? Ui.mix(Ui.surface, Ui.primary, 0.08f) : Ui.surface));
                    g2.fill(new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth() - 1, h, h, h));
                    if (!f) {
                        g2.setColor(Ui.line);
                        g2.draw(new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, h - 1, h, h));
                    }
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            b.setForeground(filled ? Color.WHITE : Ui.ink);
            b.setFont(Ui.font(15.5f, filled));
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setRolloverEnabled(true);
            b.setBorder(new EmptyBorder(10, 20, 11, 20));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> pick.run());
            b.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { pop(e); }

                @Override public void mouseReleased(MouseEvent e) { pop(e); }

                void pop(MouseEvent e) {
                    if (e.isPopupTrigger()) pin.run();
                }
            });
            add(b);
        }

        @Override public Dimension getPreferredSize() {
            // ارتفاع الأسطر الملتفة بعرض النافذة
            Container par = getParent();
            int width = par == null ? 500 : par.getWidth();
            if (width <= 0) width = 500;
            FlowLayout fl = (FlowLayout) getLayout();
            int x = fl.getHgap(), y = fl.getVgap(), rowH = 0;
            for (Component c : getComponents()) {
                Dimension d = c.getPreferredSize();
                if (x + d.width + fl.getHgap() > width && x > fl.getHgap()) {
                    x = fl.getHgap();
                    y += rowH + fl.getVgap();
                    rowH = 0;
                }
                x += d.width + fl.getHgap();
                rowH = Math.max(rowH, d.height);
            }
            return new Dimension(width, y + rowH + fl.getVgap());
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }

        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
