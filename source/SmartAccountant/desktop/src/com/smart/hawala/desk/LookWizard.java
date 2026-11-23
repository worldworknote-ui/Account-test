package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * 11.0: معالج الشكل على الماك والويندوز: يظهر عند أول تشغيل (ومن «المظهر» أو قائمة «عرض» متى شئت).
 * نافذة صغيرة بجانب البرنامج؛ كل اختيار يُطبَّق فورًا على نافذة البرنامج نفسها فتراه كما سيبقى (معاينة حية):
 * الشكل (مثل الهاتف أو القديم)، والحجم في النافذة، والتكبير (70٪…170٪)، والوضع فاتح/ليلي.
 */
public final class LookWizard {
    private LookWizard() {}

    static JDialog dlg;
    private static boolean first;
    private static JPanel body;

    /** يفتح المعالج (أو يقدّمه إن كان مفتوحًا). */
    public static void open(boolean firstRun) {
        if (dlg != null) {
            dlg.toFront();
            return;
        }
        Db db = SmartApp.db;
        if (db == null) return;
        dlg = new JDialog((Frame) null, "تخصيص الشكل", false);
        dlg.setType(Window.Type.UTILITY);
        dlg.setAlwaysOnTop(true);
        dlg.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { finish(); }
        });
        try { dlg.setIconImages(java.util.Arrays.asList(Main.appIcon(16), Main.appIcon(32), Main.appIcon(64))); } catch (Throwable ignored) { /* بلا أيقونة */ }
        body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(18, 20, 18, 20));
        first = firstRun;
        build(firstRun);
        dlg.setContentPane(body);
        dlg.pack();
        dlg.setSize(Math.max(400, dlg.getWidth()), dlg.getHeight());
        place(firstRun);
        dlg.setVisible(true);
    }

    /** يضع المعالج بجانب نافذة البرنامج (وعند أول تشغيل يزيح النافذة قليلًا ليظهر الاثنان معًا). */
    static void place(boolean firstRun) {
        Window main = current();
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int w = dlg.getWidth(), h = Math.min(dlg.getHeight(), scr.height - 20);
        dlg.setSize(w, h);
        if (main == null) { dlg.setLocationRelativeTo(null); return; }
        Rectangle m = main.getBounds();
        int gap = 16;
        if (firstRun && m.width + w + gap <= scr.width && (main instanceof Frame) && (((Frame) main).getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            int total = m.width + gap + w;
            int x0 = scr.x + (scr.width - total) / 2;
            // المعالج يسار النافذة (الكتابة من اليمين: البرنامج في مكانه المعتاد يمينًا)
            dlg.setLocation(x0, Math.max(scr.y, m.y + (m.height - h) / 2));
            main.setLocation(x0 + w + gap, m.y);
        } else if (m.x - scr.x >= w + gap) {
            dlg.setLocation(m.x - w - gap, Math.max(scr.y, m.y + (m.height - h) / 2));
        } else if (scr.x + scr.width - (m.x + m.width) >= w + gap) {
            dlg.setLocation(m.x + m.width + gap, Math.max(scr.y, m.y + (m.height - h) / 2));
        } else {
            dlg.setLocation(Math.max(scr.x, m.x + 24), Math.max(scr.y, m.y + m.height - h - 24));
        }
    }

    static Window current() {
        if (PhoneWindow.frame != null) return PhoneWindow.frame;
        return App.frame;
    }

    static boolean phone() { return !"classic".equals(SmartApp.db.setting("desk_ui", "phone")); }

    private static void rebuild() {
        if (dlg == null) return;
        body.removeAll();
        build(first);
        body.applyComponentOrientation(Ui.RTL);
        body.revalidate();
        body.repaint();
        Dimension p = body.getPreferredSize();
        dlg.setSize(Math.max(dlg.getWidth(), p.width + 4), Math.min(p.height + dlg.getInsets().top + dlg.getInsets().bottom + 4,
                GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().height - 20));
    }

    private static void build(boolean firstRun) {
        Db db = SmartApp.db;
        boolean phone = phone();
        body.setBackground(Ui.surface);
        JLabel t = new JLabel(firstRun ? "أهلًا بك! اختر شكل البرنامج" : "تخصيص الشكل والحجم");
        t.setFont(Ui.font(20f, true));
        t.setForeground(Ui.primaryInk);
        add(t);
        add(note("كل اختيار يظهر فورًا في نافذة البرنامج بجانبك. تغيّره لاحقًا من «المزيد ← المظهر»"
                + " (أو «عرض ← معالج الشكل» في الشكل القديم)."));

        // ---------------------------------------------------------------- الشكل
        add(head("الشكل"));
        add(choices(new String[]{"مثل الهاتف (جديد)", "الشكل القديم"}, phone ? 0 : 1, i -> {
            String want = i == 0 ? "phone" : "classic";
            if (want.equals(db.setting("desk_ui", "phone"))) return;
            Main.switchUi(want);
            SwingUtilities.invokeLater(() -> {
                rebuild();
                if (dlg != null) dlg.toFront();
            });
        }));
        add(note(phone ? "شاشات الهاتف نفسها بكل ميزاتها وأزرارها وبحثها."
                : "واجهة الكمبيوتر السابقة: قائمة جانبية وجداول عريضة."));

        // ---------------------------------------------------------------- الحجم (شكل الهاتف)
        if (phone) {
            add(head("الحجم في النافذة"));
            String col = PhoneWindow.column(db);
            String[] keys = DeskLook.COLS;
            int ci = DeskLook.indexOf(keys, col);
            add(choices(new String[]{"تلقائي مع النافذة", "عمود 480", "عمود 600", "كامل العرض"}, ci < 0 ? 0 : ci, i -> {
                db.setSettingQuiet("desk_column", keys[i]);
                PhoneWindow.refresh();
            }));
            add(note("«تلقائي»: اسحب طرف النافذة أو زاويتها فيكبر كل شيء أو يصغر معها تلقائيًا."));

            add(head("التكبير"));
            int z;
            try { z = Integer.parseInt(db.setting("desk_zoom", "100")); } catch (Exception e) { z = 100; }
            JSlider s = new JSlider(70, 170, Math.max(70, Math.min(170, z)));
            s.setMajorTickSpacing(25);
            s.setMinorTickSpacing(5);
            s.setSnapToTicks(true);
            s.setPaintTicks(true);
            s.setOpaque(false);
            JLabel v = new JLabel(s.getValue() + "٪");
            v.setFont(Ui.font(15f, true));
            v.setForeground(Ui.primaryInk);
            s.addChangeListener(e -> {
                v.setText(s.getValue() + "٪");
                db.setSettingQuiet("desk_zoom", String.valueOf(s.getValue()));
                PhoneWindow.refresh();
            });
            JPanel zr = new JPanel(new BorderLayout(10, 0));
            zr.setOpaque(false);
            zr.add(s, BorderLayout.CENTER);
            zr.add(v, BorderLayout.LINE_END);
            add(zr);
            add(note("حجم النصوص والأزرار وكل شيء، من 70٪ إلى 170٪."));
        }

        // ---------------------------------------------------------------- الوضع
        add(head("الوضع"));
        boolean night = phone ? "dark".equals(db.setting("theme_mode", "light")) : "dark".equals(db.setting("desk_theme", "light"));
        add(choices(new String[]{"فاتح", "ليلي"}, night ? 1 : 0, i -> {
            String m = i == 1 ? "dark" : "light";
            db.setSettingQuiet("theme_mode", m);
            Ui.switchTheme(db, i == 1);
            if (PhoneWindow.activity != null) PhoneWindow.activity.restyleAll();
            if (App.instance != null && App.frame != null) App.instance.reloadAll();
            SwingUtilities.invokeLater(LookWizard::rebuild);
        }));

        // ---------------------------------------------------------------- إنهاء
        body.add(Box.createVerticalStrut(18));
        JButton done = new JButton(firstRun ? "ابدأ العمل" : "تم");
        done.setFont(Ui.font(16f, true));
        done.setBackground(Ui.primary);
        done.setForeground(Ui.onColor(Ui.primary));
        done.putClientProperty("JButton.buttonType", "roundRect");
        done.setAlignmentX(Component.RIGHT_ALIGNMENT);
        done.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        done.setPreferredSize(new Dimension(320, 46));
        done.addActionListener(e -> finish());
        body.add(done);
        body.applyComponentOrientation(Ui.RTL);
    }

    static void finish() {
        try { SmartApp.db.setSettingQuiet("desk_wizard", "done"); } catch (Throwable ignored) { /* يظهر مرة أخرى فقط */ }
        if (dlg != null) dlg.dispose();
        dlg = null;
        Window w = current();
        if (w != null) w.toFront();
    }

    // ------------------------------------------------------------------ عناصر
    interface Pick { void picked(int i); }

    private static void add(JComponent c) {
        c.setAlignmentX(Component.RIGHT_ALIGNMENT);
        body.add(c);
    }

    private static JLabel head(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Ui.font(15f, true));
        l.setForeground(Ui.ink);
        l.setBorder(new EmptyBorder(16, 0, 6, 0));
        return l;
    }

    private static JComponent note(String s) {
        JTextArea a = new JTextArea(s);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setFocusable(false);
        a.setOpaque(false);
        a.setFont(Ui.font(12.5f, false));
        a.setForeground(Ui.muted);
        a.setBorder(new EmptyBorder(4, 0, 0, 0));
        a.setColumns(30);
        a.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return a;
    }

    /** أزرار اختيار متجاورة (واحد فقط مختار)، كالأزرار المقسّمة في الهاتف. */
    private static JComponent choices(String[] labels, int sel, Pick p) {
        JPanel r = new JPanel(new GridLayout(labels.length > 2 ? 2 : 1, labels.length > 2 ? 2 : labels.length, 8, 8));
        r.setOpaque(false);
        ButtonGroup g = new ButtonGroup();
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            JToggleButton b = new JToggleButton(labels[i], i == sel);
            b.setFont(Ui.font(13.5f, i == sel));
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.setFocusable(false);
            b.setPreferredSize(new Dimension(150, 40));
            style(b);
            b.addItemListener(e -> {
                b.setFont(Ui.font(13.5f, b.isSelected()));
                style(b);
            });
            b.addActionListener(e -> p.picked(k));
            g.add(b);
            r.add(b);
        }
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
        return r;
    }

    private static void style(JToggleButton b) {
        if (b.isSelected()) {
            b.setBackground(Ui.primary);
            b.setForeground(Ui.onColor(Ui.primary));
            b.putClientProperty("JToggleButton.selectedBackground", Ui.primary);
        } else {
            b.setBackground(Ui.mix(Ui.surface, Ui.line, 0.5f));
            b.setForeground(Ui.ink);
        }
    }
}
