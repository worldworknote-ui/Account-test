package com.smart.hawala.desk;

import com.smart.hawala.Auth;
import com.smart.hawala.Db;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** شاشة في النافذة الرئيسية: رأس بعنوان وأزرار، ومحتوى قابل للتمرير يُعاد بناؤه في refresh(). */
abstract class View extends JPanel {
    final App app;
    final Db db;
    final JPanel body = Ui.column();
    final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    final JLabel titleLabel = Ui.label("", 24, Ui.ink, true);
    final JLabel subtitle = Ui.label("", 13, Ui.muted, false);
    JScrollPane scroll;
    boolean scrollable = true;

    View(App app, String title) {
        super(new BorderLayout());
        this.app = app;
        this.db = app.db;
        setBackground(Ui.bg);
        titleLabel.setText(title);
        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(Ui.bg);
        // 10.4: رأس أوسع بعنوان أكبر وخط فاصل خفيف
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.line), new EmptyBorder(20, 28, 14, 28)));
        JPanel t = Ui.column();
        t.add(titleLabel);
        t.add(subtitle);
        // 10.18: زر رجوع ظاهر في كل شاشة (يظهر حين توجد شاشة سابقة)
        backBtn = Ui.ghost("→ رجوع", e -> app.back());
        backBtn.setToolTipText("رجوع إلى الشاشة السابقة (Ctrl+Z أو Esc أو Alt+←)");
        backBtn.setVisible(false);
        JPanel lead = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        lead.setOpaque(false);
        lead.applyComponentOrientation(Ui.RTL);
        lead.add(backBtn);
        lead.add(t);
        head.add(lead, BorderLayout.LINE_START);
        actions.setOpaque(false);
        head.add(actions, BorderLayout.LINE_END);
        add(head, BorderLayout.NORTH);
    }

    Ui.Btn backBtn;

    void updateBack() {
        if (backBtn != null) backBtn.setVisible(!app.history.isEmpty() && !"home".equals(app.current));
    }

    /** يُستدعى عند الإنشاء الأول للشاشة. الافتراضي: محتوى يتمرر. */
    void build() {
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);
    }

    /** يُعيد ملء المحتوى من قاعدة البيانات (عند فتح الشاشة وبعد أي تغيير). */
    abstract void refresh();

    /** عند فتح الشاشة بوسيط (مثل مكتب محدد أو حركة للتعديل). */
    void open(Object arg) { refresh(); }

    /** 10.5 (§6): موضع تمرير الصفحة قبل إعادة بنائها؛ يُستعاد إن بقيت الصفحة نفسها (العنوان نفسه). */
    private int keptScroll = -1;
    private String keptTitle;

    void clear() {
        if (scroll != null) {
            keptScroll = scroll.getVerticalScrollBar().getValue();
            keptTitle = titleLabel.getText();
        }
        body.removeAll();
        actions.removeAll();
    }

    void done() {
        if (scroll != null && keptScroll > 0 && titleLabel.getText().equals(keptTitle)) {
            final int y = keptScroll;
            final JScrollPane sp = scroll;
            SwingUtilities.invokeLater(() -> sp.getVerticalScrollBar().setValue(y));
        }
        keptScroll = -1;
        Ui.rtl(this);
        body.revalidate();
        body.repaint();
        actions.revalidate();
        actions.repaint();
    }

    void action(JComponent c) { actions.add(c); }

    /** 10.10: دخول الشاشة بظهور وصعود خفيفين (0 → 1). */
    double intro = 1;
    private Timer introT;

    void enter() {
        if (introT != null) introT.stop();
        if (!Ui.motion()) {
            intro = 1;
            repaint();
            return;
        }
        intro = 0;
        repaint();
        introT = Ui.tween(520, x -> {
            intro = x;
            repaint();
        }, () -> {
            intro = 1;
            repaint();
        });
    }

    @Override public void paint(Graphics g) {
        if (intro >= 1) {
            super.paint(g);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, intro))));
        g2.translate(0, (1 - intro) * 22);
        super.paint(g2);
        g2.dispose();
    }

    void add(JComponent c) {
        c.setAlignmentX(Component.RIGHT_ALIGNMENT);
        body.add(c);
    }

    void space(int h) { body.add(Ui.gap(h)); }

    boolean can(Auth.Perm p) { return app.can(p); }

    boolean require(Auth.Perm p) { return app.require(p); }

    /** عرض محتوى الصفحة لحساب عرض النصوص الملتفة. */
    int textWidth() {
        int w = scroll != null ? scroll.getViewport().getWidth() : getWidth();
        if (w <= 0 && App.frame != null) w = App.frame.getWidth() - 260;
        return Math.max(420, w - 120);
    }
}
