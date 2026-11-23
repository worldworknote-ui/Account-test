package com.smart.hawala.desk;

import com.smart.hawala.Db;
import com.smart.hawala.Model;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/**
 * السمة والعناصر المشتركة لواجهة الماك: ألوان الهاتف نفسها (الأخضر 0B6B53 والذهبي)، خط IBM Plex Sans Arabic المضمَّن،
 * واتجاه من اليمين لليسار في كل شيء. كل عمل بطيء يمر عبر busy() في خيط خلفي.
 */
final class Ui {
    private Ui() {}

    static Color primary, accent, bg, surface, line, ink, muted, red, green, creditBg, debitBg, warnBg, warnInk, primarySoft, primaryInk, goldSoft, goldInk, sidebar, sidebarInk;
    /** 10.4: ألوان إضافية للتصميم الحديث: خلفية صفوف الجداول المتناوبة، ورأس الجدول، وظل البطاقات، وخط الشريط الجانبي الخافت. */
    static Color stripe, tableHead, shadow, sidebarMuted, sidebarHover, sidebarActive;
    /** 10.4: الوضع الليلي لنسخة الكمبيوتر. */
    static boolean dark;
    /** 10.10: خلفية الأزرار المجزأة. */
    static Color segTrack;
    static Font base, baseBold;
    static final ComponentOrientation RTL = ComponentOrientation.RIGHT_TO_LEFT;

    static void init(Db db) {
        loadFonts();
        applyColors(db);
        setupLaf();
        UIManager.put("Panel.background", bg);
        UIManager.put("OptionPane.background", surface);
        UIManager.put("OptionPane.messageFont", font(14, false));
        UIManager.put("OptionPane.buttonFont", font(13, true));
        UIManager.put("ToolTip.font", font(12, false));
        for (String k : new String[]{"Label.font", "Button.font", "TextField.font", "TextArea.font", "ComboBox.font", "CheckBox.font", "RadioButton.font",
                "Table.font", "TableHeader.font", "List.font", "Menu.font", "MenuItem.font", "PopupMenu.font", "TabbedPane.font", "Spinner.font",
                "PasswordField.font", "FormattedTextField.font", "TitledBorder.font", "ToggleButton.font", "TextPane.font", "EditorPane.font"}) {
            UIManager.put(k, font(13, k.startsWith("TableHeader")));
        }
        UIManager.put("Table.rowHeight", 34);
    }

    /**
     * 10.4: سمة FlatLaf الحديثة (مكتبة مفتوحة المصدر، رخصة Apache 2.0): حقول وقوائم وجداول وأشرطة تمرير بزوايا مستديرة
     * ومظهر واحد على الماك والويندوز، فاتح أو ليلي، بلون المكتب الأساسي.
     */
    static void setupLaf() {
        java.util.Map<String, String> x = new java.util.HashMap<>();
        x.put("@accentColor", hex(primary));
        x.put("@background", hex(bg));
        x.put("@foreground", hex(ink));
        x.put("Component.arc", "12");
        x.put("Button.arc", "12");
        x.put("TextComponent.arc", "10");
        x.put("CheckBox.arc", "6");
        x.put("ProgressBar.arc", "999");
        x.put("Component.focusWidth", "1");
        x.put("Component.innerFocusWidth", "0");
        x.put("Component.borderColor", hex(line));
        x.put("Component.disabledBorderColor", hex(line));
        x.put("Component.focusedBorderColor", hex(primary));
        x.put("Component.focusColor", hex(mix(surface, primary, 0.35f)));
        x.put("TextField.margin", "6,10,6,10");
        x.put("PasswordField.margin", "6,10,6,10");
        // 10.5: زر «إظهار كلمة السر» (العين) في كل خانة سر
        x.put("PasswordField.showRevealButton", "true");
        x.put("TextArea.margin", "8,10,8,10");
        x.put("ComboBox.padding", "4,8,4,8");
        x.put("TextField.background", hex(surface));
        x.put("PasswordField.background", hex(surface));
        x.put("TextArea.background", hex(surface));
        x.put("ComboBox.background", hex(surface));
        x.put("ComboBox.buttonBackground", hex(surface));
        x.put("List.background", hex(surface));
        x.put("ScrollBar.thumbArc", "999");
        x.put("ScrollBar.thumbInsets", "2,2,2,2");
        x.put("ScrollBar.width", "11");
        x.put("ScrollBar.showButtons", "false");
        x.put("ScrollBar.track", hex(bg));
        x.put("Table.background", hex(surface));
        x.put("Table.selectionBackground", hex(mix(surface, primary, dark ? 0.32f : 0.16f)));
        x.put("Table.selectionForeground", hex(ink));
        x.put("Table.selectionInactiveBackground", hex(mix(surface, primary, dark ? 0.22f : 0.10f)));
        x.put("Table.selectionInactiveForeground", hex(ink));
        x.put("Table.gridColor", hex(line));
        x.put("TableHeader.background", hex(tableHead));
        x.put("TableHeader.foreground", hex(muted));
        x.put("TableHeader.separatorColor", hex(line));
        x.put("TableHeader.bottomSeparatorColor", hex(line));
        x.put("TableHeader.height", "36");
        x.put("PopupMenu.borderCornerRadius", "10");
        x.put("Popup.borderCornerRadius", "10");
        x.put("ToolTip.background", hex(dark ? new Color(40, 52, 48) : new Color(30, 41, 37)));
        x.put("ToolTip.foreground", "#FFFFFF");
        x.put("OptionPane.background", hex(surface));
        x.put("Panel.background", hex(bg));
        x.put("TitlePane.unifiedBackground", "true");
        x.put("TitlePane.background", hex(bg));
        x.put("CheckBox.icon.checkmarkColor", "#FFFFFF");
        x.put("CheckBox.icon.selectedBackground", hex(primary));
        x.put("CheckBox.icon.selectedBorderColor", hex(primary));
        x.put("ProgressBar.foreground", hex(primary));
        x.put("ProgressBar.background", hex(line));
        x.put("Separator.foreground", hex(line));
        try {
            com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(x);
            if (dark) com.formdev.flatlaf.FlatDarkLaf.setup();
            else com.formdev.flatlaf.FlatLightLaf.setup();
            UIManager.put("defaultFont", font(13.5f, false));
        } catch (Throwable t) {
            // بلا المكتبة: المظهر الافتراضي للنظام
        }
    }

    /** 10.4: تبديل الوضع الفاتح/الليلي وإعادة رسم كل النوافذ. */
    static void switchTheme(Db db, boolean toDark) {
        db.setSettingQuiet("desk_theme", toDark ? "dark" : "light");
        init(db);
        for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w);
    }

    static void loadFonts() {
        if (base != null) return;
        try {
            base = load("/res/plex_regular.ttf");
            baseBold = load("/res/plex_bold.ttf");
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(base);
            ge.registerFont(baseBold);
            for (String f : new String[]{"cairo_regular", "cairo_bold", "kufi_regular", "kufi_bold"}) {
                try {
                    ge.registerFont(load("/res/" + f + ".ttf"));
                } catch (Exception ignored) {
                    // اختياري
                }
            }
        } catch (Exception e) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
            baseBold = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        }
    }

    private static Font load(String path) throws Exception {
        try (InputStream in = Ui.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path);
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    static Font font(float size, boolean bold) {
        loadFonts();
        return (bold ? baseBold : base).deriveFont(size);
    }

    static Color parse(String s, Color def) {
        try {
            s = s == null ? "" : s.trim();
            if (s.isEmpty()) return def;
            if (s.startsWith("#")) s = s.substring(1);
            long v = Long.parseLong(s, 16);
            if (s.length() == 8) return new Color((int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255);
            return new Color((int) v);
        } catch (Exception e) {
            return def;
        }
    }

    static Color mix(Color a, Color b, float t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static Color darker(Color c, float f) { return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f)); }

    static String hex(Color c) { return String.format(Locale.ROOT, "#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    /** 10.12: بصمة المظهر المطبّق — لكل مستخدم مظهره، فيُعاد التطبيق عند دخول مستخدم مختلف المظهر. */
    static String appliedSig = "";

    static String lookSig(Db db) {
        if (db == null) return "";
        return db.setting("desk_theme", "light") + "|" + db.setting("primary_color", "") + "|" + db.setting("accent_color", "") + "|" + db.setting("bg_color", "")
                + "|" + db.setting("hold_confirm", "off") + "|" + db.setting("page_motion", "stagger")
                + "|" + db.setting("desk_font", "plex") + "|" + db.setting("desk_side_color", "") + "|" + db.setting("desk_btn_size", "normal") + "|" + db.setting("desk_borders", "on");
    }

    /** 10.13: قاعدة البيانات لقراءة تفضيلات المستخدم الحالي (الضغط المطوّل، حركة الصفحات). */
    static Db store;

    /** حركة الصفحات: «none» بلا حركة، وغيرها الحركة المعتادة. */
    static boolean motion() { return store == null || !"none".equals(store.setting("page_motion", "stagger")); }

    // ---- 10.16: خيارات المظهر التي كانت على الهاتف وحده: الخط، لون الشريط، حجم الأزرار، إطارات البطاقات ----
    static String fontKey = "plex";
    static float btnScale = 1f;
    static boolean cardBorders = true;
    static final String[][] FONTS = {{"plex", "IBM Plex Arabic (الافتراضي)"}, {"cairo", "Cairo"}, {"kufi", "Noto Kufi"}, {"system", "خط النظام"}};

    static boolean hasFont(String k) { return "system".equals(k) || Ui.class.getResource("/res/" + k + "_regular.ttf") != null; }

    static void pickFont(Db db) {
        String k = db == null ? "plex" : db.setting("desk_font", "plex");
        if (k.equals(fontKey) || !hasFont(k)) return;
        try {
            if ("system".equals(k)) {
                base = new Font(Font.DIALOG, Font.PLAIN, 13);
                baseBold = new Font(Font.DIALOG, Font.BOLD, 13);
            } else {
                Font r = load("/res/" + k + "_regular.ttf"), b = load("/res/" + k + "_bold.ttf");
                base = r;
                baseBold = b;
            }
            fontKey = k;
        } catch (Exception ignored) {
            // يبقى الخط الحالي
        }
    }

    static void applyColors(Db db) {
        pickFont(db);
        btnScale = db != null && "big".equals(db.setting("desk_btn_size", "normal")) ? 1.18f : 1f;
        cardBorders = db == null || !"off".equals(db.setting("desk_borders", "on"));
        store = db;
        appliedSig = lookSig(db);
        dark = db != null && "dark".equals(db.setting("desk_theme", "light"));
        Color brand = parse(db == null ? "" : db.setting("primary_color", "#0B6B53"), new Color(11, 107, 83));
        accent = parse(db == null ? "" : db.setting("accent_color", "#C7922F"), new Color(199, 146, 47));
        if (!dark) {
            primary = brand;
            String b = db == null ? "" : db.setting("bg_color", "").trim();
            // الخلفية الحديثة الافتراضية أفتح وأبرد؛ لون اختاره المستخدم بنفسه يبقى
            bg = b.isEmpty() || b.equalsIgnoreCase("#F7F4ED") ? new Color(243, 245, 244) : parse(b, new Color(243, 245, 244));
            surface = Color.WHITE;
            line = new Color(225, 231, 228);
            ink = new Color(23, 36, 31);
            muted = new Color(99, 113, 107);
            red = new Color(176, 42, 52);
            green = new Color(22, 118, 70);
            creditBg = new Color(228, 242, 234);
            debitBg = new Color(251, 231, 232);
            warnBg = new Color(255, 243, 214);
            warnInk = new Color(110, 67, 0);
            primarySoft = mix(Color.WHITE, primary, 0.09f);
            primaryInk = darker(primary, 0.76f);
            goldSoft = mix(Color.WHITE, accent, 0.16f);
            goldInk = darker(accent, 0.58f);
            stripe = new Color(249, 250, 250);
            tableHead = new Color(246, 248, 247);
            shadow = new Color(16, 40, 30, 14);
        } else {
            // 10.10: الوضع الليلي العصري (مثل الهاتف): أخضر داكن جدًا ونعناعي للأخضر و«لنا»
            boolean defBrand = brand.equals(new Color(11, 107, 83));
            primary = defBrand ? new Color(61, 220, 151) : mix(brand, Color.WHITE, 0.12f);
            bg = new Color(11, 20, 17);
            surface = new Color(19, 32, 27);
            line = new Color(36, 55, 47);
            ink = new Color(236, 243, 239);
            muted = new Color(141, 163, 154);
            red = new Color(255, 120, 120);
            green = new Color(61, 220, 151);
            creditBg = new Color(20, 52, 40);
            debitBg = new Color(58, 28, 31);
            warnBg = new Color(59, 46, 18);
            warnInk = new Color(243, 208, 138);
            primarySoft = mix(surface, primary, 0.16f);
            primaryInk = defBrand ? new Color(61, 220, 151) : mix(brand, Color.WHITE, 0.58f);
            goldSoft = mix(surface, accent, 0.22f);
            goldInk = mix(accent, Color.WHITE, 0.35f);
            stripe = new Color(23, 37, 31);
            tableHead = new Color(24, 39, 33);
            shadow = new Color(0, 0, 0, 0);
        }
        segTrack = mix(surface, line, dark ? 0.55f : 0.6f);
        sidebar = dark ? new Color(8, 15, 12) : mix(darker(brand, 0.42f), Color.BLACK, 0.12f);
        String sc = db == null ? "" : db.setting("desk_side_color", "").trim();
        if (!sc.isEmpty()) sidebar = parse(sc, sidebar);
        sidebarInk = new Color(234, 243, 239);
        sidebarMuted = new Color(150, 176, 166);
        sidebarHover = new Color(255, 255, 255, 16);
        sidebarActive = new Color(255, 255, 255, 30);
    }

    static <T extends Component> T rtl(T c) {
        c.applyComponentOrientation(RTL);
        return c;
    }

    // ===================================================================================
    // النصوص
    // ===================================================================================

    static JLabel label(String s, float size, Color c, boolean bold) {
        JLabel l = new JLabel(s);
        l.setFont(font(size, bold));
        l.setForeground(c);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        l.setAlignmentX(Component.RIGHT_ALIGNMENT);
        return l;
    }

    static JLabel title(String s) { return label(s, 22, ink, true); }

    static JLabel heading(String s) { return label(s, 16, primaryInk, true); }

    /** عرض CSS في HTML سوينغ يُكبَّر نحو 1.33؛ هذا يحوّل البكسل الحقيقي إلى قيمة CSS. */
    static int css(int px) { return Math.max(120, (int) (px * 0.74)); }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    /** نص متعدد الأسطر يلتف بعرض ثابت. */
    static JLabel para(String s, float size, Color c, int width) {
        JLabel l = new JLabel("<html><div style='width:" + css(width) + "px;text-align:right'>" + esc(s) + "</div></html>");
        l.setFont(font(size, false));
        l.setForeground(c);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        l.setAlignmentX(Component.RIGHT_ALIGNMENT);
        return l;
    }

    // ===================================================================================
    // الأزرار
    // ===================================================================================

    /** زر مسطح بزوايا مستديرة (يرسم نفسه، فيبدو كما هو على الماك وغيره). */
    static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** «للماك» أو «للويندوز» حسب النظام الذي يعمل عليه البرنامج. */
    static String forOs() { return MAC ? "للماك" : WIN ? "للويندوز" : "للكمبيوتر"; }

    // ===================================================================================
    // 10.10: الحركة — نابض بسيط لكل زر، وزر «اضغط مطوّلًا» يفتح التأكيد نفسه، ودخول الشاشات
    // ===================================================================================

    /** حركة زمنية بسيطة على خيط الواجهة: f(تقدم 0..1 بعد التخفيف)، ثم done. */
    static Timer tween(int ms, java.util.function.DoubleConsumer f, Runnable done) {
        long t0 = com.smart.hawala.Clock.now();
        Timer t = new Timer(15, null);
        t.addActionListener(e -> {
            double x = Math.min(1, (com.smart.hawala.Clock.now() - t0) / (double) ms);
            f.accept(easeOut(x));
            if (x >= 1) {
                t.stop();
                if (done != null) done.run();
            }
        });
        t.start();
        return t;
    }

    static double easeOut(double x) { return 1 - Math.pow(1 - x, 3); }

    /** تخفيف بارتداد خفيف في النهاية. */
    static double backOut(double x) {
        double c1 = 1.4, c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    static final class Btn extends JButton {
        Color fill, ink2;
        boolean outline;
        /** نابض الضغط: 0 حر، 1 مضغوط (مع ارتداد عند الترك). */
        double press, vel;
        private Timer spring;
        /** 10.10: «اضغط مطوّلًا» — التقدم 0..1، ولا يُضغط الزر بالنقرة القصيرة. */
        boolean hold;
        double holdP;
        private Timer holdT;
        private boolean mouseDown, holdFired;
        private long downAt;

        Btn(String text, Color fill, Color ink2, boolean outline) {
            super(text);
            this.fill = fill;
            this.ink2 = ink2;
            this.outline = outline;
            setFont(font(13.5f * btnScale, true));
            setForeground(ink2);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(Math.round(9 * btnScale), Math.round(18 * btnScale), Math.round(9 * btnScale), Math.round(18 * btnScale)));
            setAlignmentX(Component.RIGHT_ALIGNMENT);
            getModel().addChangeListener(e -> kick());
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (!hold || !isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                    mouseDown = true;
                    holdFired = false;
                    downAt = com.smart.hawala.Clock.now();
                    if (holdT != null) holdT.stop();
                    double start = holdP;
                    holdT = tween((int) (850 * (1 - start)), x -> {
                        holdP = start + (1 - start) * x;
                        repaint();
                    }, () -> {
                        if (!mouseDown) return;
                        holdFired = true;
                        Toolkit.getDefaultToolkit().beep();
                        Btn.super.fireActionPerformed(new java.awt.event.ActionEvent(Btn.this, java.awt.event.ActionEvent.ACTION_PERFORMED, getActionCommand()));
                        holdP = 0;
                        repaint();
                    });
                }

                @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (!hold || !mouseDown) return;
                    mouseDown = false;
                    if (!holdFired) {
                        if (holdT != null) holdT.stop();
                        double from = holdP;
                        holdT = tween(220, x -> {
                            holdP = from * (1 - x);
                            repaint();
                        }, null);
                        if (com.smart.hawala.Clock.now() - downAt < 350) toast("اضغط مطوّلًا على الزر حتى يمتلئ، ثم أكّد");
                    }
                }
            });
        }

        /** النقر بالفأرة على زر «اضغط مطوّلًا» لا يضغطه؛ لوحة المفاتيح وقارئ الشاشة يضغطانه مباشرة (إلى التأكيد نفسه). */
        @Override protected void fireActionPerformed(java.awt.event.ActionEvent e) {
            if (hold && (mouseDown || com.smart.hawala.Clock.now() - downAt < 400)) return;
            super.fireActionPerformed(e);
        }

        private void kick() {
            if (spring == null) {
                spring = new Timer(15, e -> {
                    double target = getModel().isPressed() && getModel().isArmed() ? 1 : 0;
                    vel += (target - press) * 0.16;
                    vel *= 0.72;
                    press += vel;
                    repaint();
                    if (Math.abs(target - press) < 0.002 && Math.abs(vel) < 0.002) {
                        press = target;
                        ((Timer) e.getSource()).stop();
                    }
                });
            }
            if (!spring.isRunning()) spring.start();
        }

        void colors(Color f, Color i) {
            fill = f;
            ink2 = i;
            setForeground(i);
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double sc = 1 - 0.05 * press;
            g2.translate(getWidth() / 2.0, getHeight() / 2.0);
            g2.scale(sc, sc);
            g2.translate(-getWidth() / 2.0, -getHeight() / 2.0);
            boolean clear = fill.getAlpha() == 0;
            Color f = fill;
            if (!isEnabled()) f = clear ? fill : mix(fill, surface, 0.55f);
            else if (clear) f = getModel().isRollover() ? new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 16) : fill;
            else if (getModel().isPressed()) f = outline ? primarySoft : darker(fill, 0.85f);
            else if (getModel().isRollover()) f = outline ? primarySoft : (dark ? mix(fill, Color.WHITE, 0.08f) : darker(fill, 0.92f));
            RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 14, 14);
            if (!outline && !clear && isEnabled() && !dark && fill.getAlpha() == 255) {
                // ظل خفيف تحت الأزرار الممتلئة
                g2.setColor(new Color(0, 0, 0, 18));
                g2.fill(new RoundRectangle2D.Float(1f, 2f, getWidth() - 2f, getHeight() - 2f, 14, 14));
            }
            g2.setColor(outline && !getModel().isRollover() && !getModel().isPressed() ? surface : f);
            g2.fill(r);
            if (outline) {
                g2.setColor(getModel().isRollover() ? mix(line, primary, 0.35f) : line);
                g2.draw(r);
            }
            if (hold && holdP > 0) {
                // يمتلئ من اليمين (اتجاه القراءة)
                Shape old = g2.getClip();
                int w = (int) Math.round(getWidth() * holdP);
                g2.clip(new Rectangle(getWidth() - w, 0, w, getHeight()));
                g2.setColor(mix(fill, Color.WHITE, dark ? 0.35f : 0.28f));
                g2.fill(r);
                g2.setClip(old);
            }
            g2.dispose();
            Graphics2D gt = (Graphics2D) g.create();
            gt.translate(getWidth() / 2.0, getHeight() / 2.0);
            gt.scale(sc, sc);
            gt.translate(-getWidth() / 2.0, -getHeight() / 2.0);
            super.paintComponent(gt);
            gt.dispose();
        }
    }

    /** 10.10: يجعل الزر «اضغط مطوّلًا» (يمتلئ ثم يضغط نفسه فيفتح التأكيد). */
    static Btn holdToConfirm(Btn b) {
        // 10.13: خيار لا إجبار (الافتراضي ضغطة عادية ثم نافذة التأكيد)
        if (store == null || !"on".equals(store.setting("hold_confirm", "off"))) return b;
        b.hold = true;
        b.setToolTipText("اضغط مطوّلًا حتى يمتلئ الزر لفتح التأكيد");
        b.getAccessibleContext().setAccessibleDescription("اضغط مطوّلًا حتى يمتلئ الزر لفتح التأكيد");
        return b;
    }

    static Btn button(String text, Color fill, ActionListener a) {
        Btn b = new Btn(text, fill, onColor(fill), false);
        if (a != null) b.addActionListener(a);
        return b;
    }

    static Btn primary(String text, ActionListener a) { return button(text, primary, a); }

    static Btn ghost(String text, ActionListener a) {
        Btn b = new Btn(text, surface, primaryInk, true);
        if (a != null) b.addActionListener(a);
        return b;
    }

    static Btn danger(String text, ActionListener a) { return button(text, red, a); }

    static Color onColor(Color c) {
        double l = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255;
        return l > 0.62 ? new Color(20, 30, 26) : Color.WHITE;
    }

    /**
     * مجموعة خيارات متجاورة (مثل «لنا / علينا»). 10.10: مسار هادئ يتحرك فوقه مؤشر ملوّن بارتداد خفيف إلى الخيار المختار.
     */
    static final class Seg extends JPanel {
        final List<Btn> buttons = new ArrayList<>();
        final Color[] colors;
        int selected = -1;
        Consumer<Integer> listener;
        /** مكان المؤشر الحالي (x، العرض) ولونه، وحركته. */
        private double ix = -1, iw;
        private Color ic;
        private Timer anim;

        Seg(String[] items, Color[] colors, int sel) {
            super(new GridLayout(1, items.length, 4, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(4, 4, 4, 4));
            this.colors = colors;
            for (int i = 0; i < items.length; i++) {
                final int k = i;
                Btn b = new Btn(items[i], new Color(0, 0, 0, 0), ink, false);
                b.addActionListener(e -> {
                    set(k);
                    if (listener != null) listener.accept(k);
                });
                buttons.add(b);
                add(b);
            }
            set(sel);
            setAlignmentX(Component.RIGHT_ALIGNMENT);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) { snap(); }
            });
        }

        private Color colorOf(int k) { return colors == null ? primary : colors[Math.min(k, colors.length - 1)]; }

        private void snap() {
            if (selected < 0 || selected >= buttons.size()) return;
            Btn b = buttons.get(selected);
            ix = b.getX();
            iw = b.getWidth();
            repaint();
        }

        void set(int i) {
            int prev = selected;
            selected = i;
            for (int k = 0; k < buttons.size(); k++) {
                Btn b = buttons.get(k);
                b.outline = false;
                Color c = colorOf(k);
                b.colors(new Color(0, 0, 0, 0), k == i ? onColor(c) : colors == null ? muted : c);
            }
            if (i < 0 || i >= buttons.size()) {
                ix = -1;
                repaint();
                return;
            }
            Btn b = buttons.get(i);
            Color to = colorOf(i);
            if (b.getWidth() == 0 || prev < 0 || ix < 0) {
                ic = to;
                SwingUtilities.invokeLater(this::snap);
                snap();
                return;
            }
            double fx = ix, fw = iw, tx = b.getX(), tw = b.getWidth();
            Color fc = ic == null ? to : ic;
            if (anim != null) anim.stop();
            long t0 = com.smart.hawala.Clock.now();
            anim = new Timer(15, null);
            anim.addActionListener(e -> {
                double x = Math.min(1, (com.smart.hawala.Clock.now() - t0) / 560.0), y = backOut(x);
                ix = fx + (tx - fx) * y;
                iw = fw + (tw - fw) * y;
                ic = mix(fc, to, (float) Math.min(1, x * 1.3));
                repaint();
                if (x >= 1) anim.stop();
            });
            anim.start();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(segTrack);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16));
            if (ix >= 0 && selected >= 0) {
                g2.setColor(ic == null ? colorOf(selected) : ic);
                Btn b = buttons.get(selected);
                g2.fill(new RoundRectangle2D.Double(ix, b.getY(), iw, b.getHeight(), 12, 12));
            }
            g2.dispose();
        }

        boolean none() { return selected < 0; }
    }

    // ===================================================================================
    // الحاويات
    // ===================================================================================

    /** بطاقة بيضاء بزوايا مستديرة؛ المحتوى عمودي. */
    static final class Card extends JPanel {
        Color fill = surface, border = line;

        Card() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setBorder(new EmptyBorder(18, 20, 20, 20));
            setAlignmentX(Component.RIGHT_ALIGNMENT);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 10.4: ظل ناعم (طبقتان) تحت البطاقة في الوضع الفاتح
            if (shadow.getAlpha() > 0 && fill == surface) {
                g2.setColor(shadow);
                g2.fill(new RoundRectangle2D.Float(0, 2, getWidth() - 1, getHeight() - 2, 18, 18));
                g2.setColor(new Color(shadow.getRed(), shadow.getGreen(), shadow.getBlue(), shadow.getAlpha() / 2));
                g2.fill(new RoundRectangle2D.Float(0, 1, getWidth() - 1, getHeight() - 1, 18, 18));
            }
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 3, 18, 18));
            if (border != null && (cardBorders || border != line)) {
                g2.setColor(border);
                g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 3, 18, 18));
            }
            g2.dispose();
            super.paintComponent(g);
        }

        @Override public Dimension getMaximumSize() {
            Dimension p = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, p.height);
        }
    }

    static Card card() { return new Card(); }

    static Card tinted(Color fill, Color border) {
        Card c = new Card();
        c.fill = fill;
        c.border = border;
        return c;
    }

    /** صف أفقي من اليمين لليسار. */
    static JPanel row(Component... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.LINE_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.RIGHT_ALIGNMENT);
        p.setComponentOrientation(RTL);
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(8));
            p.add(cs[i]);
        }
        return p;
    }

    static JPanel flow(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.RIGHT_ALIGNMENT);
        p.setComponentOrientation(RTL);
        for (Component c : cs) p.add(c);
        return p;
    }

    /** عمود بعرض الصفحة. */
    static JPanel column() {
        JPanel p = new JPanel() {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.RIGHT_ALIGNMENT);
        return p;
    }

    static Component gap(int h) { return Box.createVerticalStrut(h); }

    /** محتوى قابل للتمرير بعرض الصفحة. */
    static JScrollPane scroll(JComponent c) {
        JPanel holder = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return d;
            }
        };
        holder.setOpaque(false);
        holder.add(c, BorderLayout.NORTH);
        ScrollableHolder sh = new ScrollableHolder(holder);
        JScrollPane sp = new JScrollPane(sh);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(bg);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setComponentOrientation(RTL);
        return sp;
    }

    static final class ScrollableHolder extends JPanel implements Scrollable {
        ScrollableHolder(JComponent c) {
            super(new BorderLayout());
            setOpaque(true);
            setBackground(bg);
            setBorder(new EmptyBorder(16, 28, 28, 28));
            add(c, BorderLayout.CENTER);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }

        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ===================================================================================
    // الحقول
    // ===================================================================================

    /** حقل نص بتلميح داخلي. */
    static final class Field extends JTextField {
        String hint;

        Field(String hint) {
            setFont(font(14, false));
            setHorizontalAlignment(JTextField.RIGHT);
            setComponentOrientation(RTL);
            setHint(hint);
            setAlignmentX(Component.RIGHT_ALIGNMENT);
        }

        /** 10.4: تلميح داخلي يرسمه FlatLaf (يختفي عند الكتابة). */
        void setHint(String h) {
            hint = h;
            putClientProperty("JTextField.placeholderText", h);
            repaint();
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        String val() { return getText().trim(); }
    }

    static Field field(String hint) { return new Field(hint); }

    static Field numeric(String hint) {
        Field f = new Field(hint);
        f.setFont(font(15, true));
        return f;
    }

    static JPasswordField password() {
        JPasswordField p = new JPasswordField();
        p.setFont(font(14, false));
        p.setHorizontalAlignment(JTextField.RIGHT);
        // 10.5: الخاصية القديمة «JTextField.showRevealButton» لا تعرفها FlatLaf، فلم يكن الزر يظهر أصلًا
        p.putClientProperty("FlatLaf.style", "showRevealButton: true");
        p.getAccessibleContext().setAccessibleDescription("اضغط العين لإظهار ما كتبته أو إخفائه");
        p.setAlignmentX(Component.RIGHT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return p;
    }

    static JTextArea area(int rows) {
        JTextArea a = new JTextArea(rows, 20);
        a.setFont(font(14, false));
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setComponentOrientation(RTL);
        return a;
    }

    static JScrollPane areaBox(JTextArea a) {
        JScrollPane sp = new JScrollPane(a);
        sp.setAlignmentX(Component.RIGHT_ALIGNMENT);
        sp.setComponentOrientation(RTL);
        return sp;
    }

    // ---- 10.18: قوائم الاختيار كالهاتف: القائمة الطويلة تفتح نافذة فيها خانة كتابة تُصفّي الخيارات فورًا ----
    /** من هذا العدد فما فوق تُفتح نافذة الاختيار بالبحث بدل القائمة المنسدلة. */
    static final int PICK_MIN = 8;

    /** قائمة اختيار تدعم الكتابة للتصفية (كل القوائم تُنشأ من هنا). */
    static <T> JComboBox<T> pickCombo() {
        return new JComboBox<T>() {
            @Override public void setPopupVisible(boolean v) {
                if (v && getItemCount() >= PICK_MIN && isShowing()) pick(this, "");
                else super.setPopupVisible(v);
            }

            @Override public void processKeyEvent(java.awt.event.KeyEvent e) {
                // الكتابة مباشرة فوق القائمة تفتح البحث بالحرف المكتوب
                if (e.getID() == java.awt.event.KeyEvent.KEY_TYPED && getItemCount() >= PICK_MIN && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
                        && (Character.isLetterOrDigit(e.getKeyChar()) || e.getKeyChar() == '/')) {
                    pick(this, "" + e.getKeyChar());
                    e.consume();
                    return;
                }
                super.processKeyEvent(e);
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String itemText(JComboBox c, int i) {
        Object v = c.getItemAt(i);
        try {
            Component r = c.getRenderer().getListCellRendererComponent(new JList(), v, i, false, false);
            if (r instanceof JLabel && ((JLabel) r).getText() != null) return ((JLabel) r).getText();
        } catch (Exception ignored) {
            // عارض مخصص لا يقبل قائمة فارغة: نص العنصر نفسه
        }
        return String.valueOf(v);
    }

    private static boolean picking;

    /** نافذة اختيار: خانة كتابة فوق قائمة مُصفّاة. الأسهم وEnter للاختيار، وEsc أو النقر خارجها للإغلاق. */
    @SuppressWarnings("rawtypes")
    static void pick(final JComboBox c, String initial) {
        if (picking || !c.isEnabled()) return;
        picking = true;
        final int n = c.getItemCount();
        final String[] text = new String[n], norm = new String[n];
        for (int i = 0; i < n; i++) {
            text[i] = itemText(c, i);
            norm[i] = com.smart.hawala.OwnerReports.norm(text[i]);
        }
        final JDialog d = new JDialog(win(c));
        d.setUndecorated(true);
        final Field q = field("اكتب للبحث…");
        final DefaultListModel<Integer> m = new DefaultListModel<>();
        final JList<Integer> list = new JList<>(m);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(32);
        list.setBackground(surface);
        list.setCellRenderer((jl, idx, row, sel, foc) -> {
            JLabel l = label(text[idx], 14, ink, sel);
            l.setOpaque(true);
            l.setBackground(sel ? primarySoft : surface);
            l.setBorder(new EmptyBorder(0, 12, 0, 12));
            l.setHorizontalAlignment(SwingConstants.RIGHT);
            return l;
        });
        final Runnable fill = () -> {
            String f = com.smart.hawala.OwnerReports.norm(q.getText());
            m.clear();
            java.util.List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (f.isEmpty() || norm[i].startsWith(f)) m.addElement(i);
                else {
                    boolean all = true;
                    for (String w : f.split(" ")) all &= w.isEmpty() || norm[i].contains(w);
                    if (all) rest.add(i);
                }
            }
            for (int i : rest) m.addElement(i);
            int cur = f.isEmpty() ? c.getSelectedIndex() : -1, at = 0;
            for (int i = 0; i < m.size(); i++) if (m.get(i) == cur) at = i;
            if (!m.isEmpty()) {
                list.setSelectedIndex(at);
                list.ensureIndexIsVisible(at);
            }
        };
        final Runnable close = () -> {
            picking = false;
            d.dispose();
        };
        final Runnable choose = () -> {
            Integer v = list.getSelectedValue();
            close.run();
            if (v != null) c.setSelectedIndex(v);
            c.requestFocusInWindow();
        };
        q.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { fill.run(); }
        });
        q.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                int sz = m.getSize(), i = list.getSelectedIndex(), k = e.getKeyCode();
                if (k == java.awt.event.KeyEvent.VK_DOWN && sz > 0) list.setSelectedIndex(Math.min(sz - 1, i + 1));
                else if (k == java.awt.event.KeyEvent.VK_UP && sz > 0) list.setSelectedIndex(Math.max(0, i - 1));
                else if (k == java.awt.event.KeyEvent.VK_PAGE_DOWN && sz > 0) list.setSelectedIndex(Math.min(sz - 1, i + 9));
                else if (k == java.awt.event.KeyEvent.VK_PAGE_UP && sz > 0) list.setSelectedIndex(Math.max(0, i - 9));
                else if (k == java.awt.event.KeyEvent.VK_ENTER) {
                    choose.run();
                    e.consume();
                    return;
                } else if (k == java.awt.event.KeyEvent.VK_ESCAPE) {
                    close.run();
                    c.requestFocusInWindow();
                    e.consume();
                    return;
                } else return;
                list.ensureIndexIsVisible(list.getSelectedIndex());
                e.consume();
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (list.locationToIndex(e.getPoint()) >= 0) choose.run();
            }
        });
        d.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) { close.run(); }
        });
        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setBackground(surface);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(primary, 2), new EmptyBorder(8, 8, 8, 8)));
        box.add(q, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(line));
        int rows = Math.min(12, Math.max(4, n));
        int wd = Math.max(340, c.getWidth());
        sp.setPreferredSize(new Dimension(wd, rows * 32 + 4));
        box.add(sp, BorderLayout.CENTER);
        box.add(label(n + " خيارًا — اكتب جزءًا من الاسم، ↑ ↓ ثم Enter", 11.5f, muted, false), BorderLayout.SOUTH);
        box.applyComponentOrientation(RTL);
        d.setContentPane(box);
        d.pack();
        try {
            Point at = c.getLocationOnScreen();
            Rectangle scr = c.getGraphicsConfiguration().getBounds();
            int x = Math.max(scr.x + 4, Math.min(at.x + c.getWidth() - d.getWidth(), scr.x + scr.width - d.getWidth() - 4));
            int y = at.y + c.getHeight() + 2;
            if (y + d.getHeight() > scr.y + scr.height - 40) y = Math.max(scr.y + 4, at.y - d.getHeight() - 2);
            d.setLocation(x, y);
        } catch (Exception e) {
            d.setLocationRelativeTo(c);
        }
        q.setText(initial == null ? "" : initial);
        fill.run();
        d.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            q.requestFocusInWindow();
            q.setCaretPosition(q.getText().length());
        });
    }

    static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> c = pickCombo();
        c.setModel(new DefaultComboBoxModel<>(items));
        c.setFont(font(14, false));
        c.setComponentOrientation(RTL);
        c.setAlignmentX(Component.RIGHT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        if (c.getRenderer() instanceof JLabel) ((JLabel) c.getRenderer()).setHorizontalAlignment(SwingConstants.RIGHT);
        return c;
    }

    static JCheckBox check(String text, boolean on) {
        JCheckBox c = new JCheckBox(text, on);
        c.setFont(font(13.5f, false));
        c.setOpaque(false);
        c.setComponentOrientation(RTL);
        c.setHorizontalAlignment(SwingConstants.RIGHT);
        c.setAlignmentX(Component.RIGHT_ALIGNMENT);
        return c;
    }

    /** عنوان صغير فوق حقل. */
    static JComponent labeled(String label, JComponent field) {
        JPanel p = column();
        p.add(label(label, 12.5f, muted, true));
        p.add(gap(5));
        p.add(field);
        return p;
    }

    static final class RoundBorder implements Border {
        final Color c;
        final int r;

        RoundBorder(Color c, int r) {
            this.c = c;
            this.r = r;
        }

        @Override public void paintBorder(Component comp, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(comp.isFocusOwner() ? primary : c);
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, r, r));
            g2.dispose();
        }

        @Override public Insets getBorderInsets(Component c) { return new Insets(2, 2, 2, 2); }

        @Override public boolean isBorderOpaque() { return false; }
    }

    // ===================================================================================
    // الجداول
    // ===================================================================================

    static JTable table(javax.swing.table.TableModel m) {
        JTable t = new JTable(m) {
            @Override public Component prepareRenderer(TableCellRenderer r, int row, int col) {
                Component c = super.prepareRenderer(r, row, col);
                if (!isRowSelected(row)) c.setBackground(row % 2 == 0 ? surface : stripe);
                return c;
            }
        };
        t.setFont(font(13.5f, false));
        t.setRowHeight(34);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setGridColor(dark ? line : new Color(238, 242, 240));
        t.setSelectionBackground(mix(surface, primary, dark ? 0.32f : 0.14f));
        t.setSelectionForeground(ink);
        t.setFillsViewportHeight(true);
        t.setAutoCreateRowSorter(true);
        t.setComponentOrientation(RTL);
        JTableHeader h = t.getTableHeader();
        h.setFont(font(12.5f, true));
        h.setBackground(tableHead);
        h.setForeground(muted);
        h.setReorderingAllowed(false);
        ((DefaultTableCellRenderer) h.getDefaultRenderer()).setHorizontalAlignment(SwingConstants.CENTER);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        right.setBorder(new EmptyBorder(0, 8, 0, 8));
        t.setDefaultRenderer(Object.class, new Cell(SwingConstants.RIGHT));
        t.setDefaultRenderer(String.class, new Cell(SwingConstants.RIGHT));
        t.setDefaultRenderer(Long.class, new Cell(SwingConstants.CENTER));
        t.setDefaultRenderer(Integer.class, new Cell(SwingConstants.CENTER));
        return t;
    }

    /** خلية: نص من اليمين، والأرقام في الوسط؛ «علينا» بالأحمر و«لنا» بالأخضر. */
    static class Cell extends DefaultTableCellRenderer {
        final int align;

        Cell(int align) {
            this.align = align;
            setBorder(new EmptyBorder(0, 8, 0, 8));
        }

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, false, row, col);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            setHorizontalAlignment(v instanceof Money || v instanceof Number ? SwingConstants.CENTER : align);
            setForeground(ink);
            setFont(t.getFont());
            if (v instanceof Money) {
                Money m = (Money) v;
                if (m.balance && m.v < 0) setForeground(red);
                else if (m.balance && m.v > 0) setForeground(green);
                setFont(font(13.5f, true));
            }
            return c;
        }
    }

    /** مبلغ في جدول: يُعرض منسّقًا ويُرتَّب رقميًا. */
    static final class Money implements Comparable<Money> {
        final long v;
        final int dec;
        final boolean balance;

        Money(long v, int dec, boolean balance) {
            this.v = v;
            this.dec = dec;
            this.balance = balance;
        }

        @Override public String toString() { return balance ? Model.bal(v, dec) : v == 0 ? "" : Model.fmt(v, dec); }

        @Override public int compareTo(Money o) { return Long.compare(v, o.v); }
    }

    static void widths(JTable t, int... w) {
        for (int i = 0; i < w.length && i < t.getColumnCount(); i++) {
            TableColumn c = t.getColumnModel().getColumn(i);
            c.setPreferredWidth(w[i]);
        }
    }

    /** 10.18: جدول داخل صفحة تتمرر: عجلة الفأرة فوقه تمرّر الصفحة (لا تتوقف عنده). */
    static void passWheel(final JScrollPane sp) {
        sp.setWheelScrollingEnabled(false);
        sp.addMouseWheelListener(e -> {
            Container p = sp.getParent();
            while (p != null && !(p instanceof JScrollPane)) p = p.getParent();
            if (p != null) p.dispatchEvent(SwingUtilities.convertMouseEvent(sp, e, p));
        });
    }

    static JScrollPane tableBox(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(new RoundBorder(line, 12));
        sp.getViewport().setBackground(surface);
        sp.setComponentOrientation(RTL);
        sp.setAlignmentX(Component.RIGHT_ALIGNMENT);
        return sp;
    }

    // ===================================================================================
    // الحوارات
    // ===================================================================================

    static Window win(Component c) {
        if (c == null) return App.frame;
        return c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
    }

    static void info(Component parent, String title, String msg) { message(parent, title, msg, JOptionPane.INFORMATION_MESSAGE); }

    static void message(Component parent, String title, String msg, int type) {
        JOptionPane p = new JOptionPane(para(msg, 14, ink, 440), type, JOptionPane.DEFAULT_OPTION, null, new Object[]{"حسنًا"});
        show(parent, p, title);
    }

    static String friendly(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && (x.getMessage() == null || x.getMessage().isEmpty() || x instanceof java.util.concurrent.ExecutionException
                || x instanceof java.lang.reflect.InvocationTargetException)) x = x.getCause();
        if (t instanceof com.smart.hawala.BackupManager.BackupException) return ((com.smart.hawala.BackupManager.BackupException) t).userMessage();
        String m = x.getMessage();
        if (m == null || m.trim().isEmpty()) m = x.getClass().getSimpleName();
        if (x instanceof OutOfMemoryError) m = "الذاكرة لا تكفي لهذه العملية.";
        return m;
    }

    static void error(Component parent, Throwable t) {
        if (t instanceof com.smart.hawala.Entitlements.LimitReached) {
            limit(parent, t.getMessage());
            return;
        }
        if (!(t instanceof IllegalArgumentException) && !(t instanceof IllegalStateException)) Main.log(t);
        message(parent, "تعذّرت العملية", friendly(t), JOptionPane.WARNING_MESSAGE);
    }

    static void limit(Component parent, String msg) {
        int r = choose(parent, "حدود النسخة المجانية", msg + "\n\nكل بياناتك محفوظة ومتاحة للعرض والتصدير والنسخ الاحتياطي.", new String[]{"الترخيص", "حسنًا"});
        if (r == 0 && App.instance != null) App.instance.go("license");
    }

    static boolean confirm(Component parent, String title, String msg, String yes) {
        return choose(parent, title, msg, new String[]{yes, "إلغاء"}) == 0;
    }

    static int choose(Component parent, String title, String msg, String[] options) {
        JOptionPane p = new JOptionPane(para(msg, 14, ink, 440), JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[0]);
        show(parent, p, title);
        Object v = p.getValue();
        for (int i = 0; i < options.length; i++) if (options[i].equals(v)) return i;
        return -1;
    }

    /**
     * 10.5 (§6): يحفظ تحديد الجدول (بمفاتيح الصفوف لا بأرقامها) وموضع تمريره قبل إعادة ملئه، ويعيدهما بعده،
     * فلا يرجع المستخدم إلى أول القائمة بعد كل عملية أو رجوع.
     */
    static final class Keep {
        final java.util.Set<Object> keys = new java.util.HashSet<>();
        Point pos;

        static <T> Keep of(JTable t, List<T> rows, java.util.function.Function<T, Object> key) {
            Keep k = new Keep();
            if (t == null) return k;
            for (int v : t.getSelectedRows()) {
                int m = t.convertRowIndexToModel(v);
                if (m >= 0 && m < rows.size()) k.keys.add(key.apply(rows.get(m)));
            }
            if (t.getParent() instanceof JViewport) k.pos = ((JViewport) t.getParent()).getViewPosition();
            return k;
        }

        <T> void restore(JTable t, List<T> rows, java.util.function.Function<T, Object> key) {
            if (t == null) return;
            for (int m = 0; m < rows.size() && m < t.getModel().getRowCount(); m++) {
                if (!keys.contains(key.apply(rows.get(m)))) continue;
                int v = t.convertRowIndexToView(m);
                if (v >= 0) t.addRowSelectionInterval(v, v);
            }
            final Point p = pos;
            if (p != null && t.getParent() instanceof JViewport) {
                final JViewport vp = (JViewport) t.getParent();
                SwingUtilities.invokeLater(() -> {
                    int maxY = Math.max(0, vp.getView().getHeight() - vp.getExtentSize().height);
                    vp.setViewPosition(new Point(p.x, Math.min(p.y, maxY)));
                });
            }
        }
    }

    /** 10.5 (§11): يرسم سطور تأكيد الحركة: المكتب بالأحمر مع كلمة «المكتب:»، والرصيد بعد الحركة بخط عريض. */
    static void fillConfirm(JPanel box, com.smart.hawala.TxConfirm.View v) {
        box.removeAll();
        for (com.smart.hawala.TxConfirm.Row r : v.rows) {
            JLabel l;
            switch (r.kind) {
                case com.smart.hawala.TxConfirm.OFFICE:
                    l = new JLabel("<html><div style='text-align:right'><b>" + esc(r.label) + ":</b> <b><font color='" + hex(red) + "'>" + esc(r.value) + "</font></b></div></html>");
                    l.setFont(font(16, true));
                    box.add(gap(6));
                    break;
                case com.smart.hawala.TxConfirm.AFTER:
                    l = label("   " + r, 14.5f, primaryInk, true);
                    break;
                case com.smart.hawala.TxConfirm.BALANCE:
                    l = label("   " + r, 14, ink, false);
                    break;
                case com.smart.hawala.TxConfirm.NOTE:
                    l = para(r.toString(), 13, goldInk, 440);
                    break;
                default:
                    l = label(r.toString(), 14, ink, false);
            }
            l.setForeground(r.kind == com.smart.hawala.TxConfirm.OFFICE ? ink : l.getForeground());
            l.setHorizontalAlignment(SwingConstants.RIGHT);
            l.setAlignmentX(Component.RIGHT_ALIGNMENT);
            box.add(l);
        }
        box.revalidate();
        box.repaint();
    }

    /**
     * 10.5 (§11): نافذة تأكيد الحركة بالأرصدة من طبقة البيانات. تُحدَّث كل ثانيتين وهي مفتوحة؛ إن تغيّر الرصيد (مزامنة مثلًا)
     * يظهر تنبيه وتُعرض الأرقام الجديدة. يرجع ما وافق عليه المستخدم (لمقارنته قبل الحفظ)، أو null عند الإلغاء.
     */
    static com.smart.hawala.TxConfirm.View confirmTx(Component parent, String title, String prefix, java.util.function.Supplier<com.smart.hawala.TxConfirm.View> build, String yes) {
        final com.smart.hawala.TxConfirm.View[] shown = {build.get()};
        JPanel c = column();
        if (prefix != null && !prefix.isEmpty()) {
            c.add(para(prefix, 14, red, 440));
            c.add(gap(6));
        }
        JLabel changed = label("", 13.5f, red, true);
        c.add(changed);
        JPanel rows = column();
        fillConfirm(rows, shown[0]);
        c.add(rows);
        Timer t = new Timer(2000, e -> {
            try {
                com.smart.hawala.TxConfirm.View now = build.get();
                if (!now.fingerprint.equals(shown[0].fingerprint)) {
                    shown[0] = now;
                    fillConfirm(rows, now);
                    changed.setText("! تغيّر الرصيد أثناء العرض (وصلت حركة أخرى). راجع الأرقام الجديدة.");
                    Window w = SwingUtilities.getWindowAncestor(c);
                    if (w != null) w.pack();
                }
            } catch (Exception ignored) {
                // يبقى العرض السابق
            }
        });
        t.start();
        boolean ok;
        try {
            ok = form(parent, title, rtl(c), yes);
        } finally {
            t.stop();
        }
        return ok ? shown[0] : null;
    }

    /** حوار بمحتوى حر وزرّين؛ يرجع true عند الموافقة. */
    static boolean form(Component parent, String title, JComponent content, String yes) {
        JOptionPane p = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{yes, "إلغاء"}, yes);
        show(parent, p, title);
        return yes.equals(p.getValue());
    }

    static String input(Component parent, String title, String msg, String initial) {
        JPanel c = column();
        c.add(para(msg, 14, ink, 380));
        c.add(gap(8));
        Field f = field("");
        f.setText(initial == null ? "" : initial);
        c.add(f);
        if (!form(parent, title, c, "موافق")) return null;
        return f.getText().trim();
    }

    static void show(Component parent, JOptionPane p, String title) {
        p.applyComponentOrientation(RTL);
        JDialog d = p.createDialog(win(parent), title);
        d.applyComponentOrientation(RTL);
        // 10.18: كل النوافذ قابلة للتكبير، ولا تتجاوز الشاشة (محتواها يتمدد معها)
        d.setResizable(true);
        try {
            Rectangle scr = d.getGraphicsConfiguration().getBounds();
            Dimension sz = d.getSize();
            int mw = (int) (scr.width * 0.94), mh = (int) (scr.height * 0.90);
            if (sz.width > mw || sz.height > mh) {
                d.setSize(Math.min(sz.width, mw), Math.min(sz.height, mh));
                d.setLocationRelativeTo(win(parent));
            }
        } catch (Exception ignored) {
            // الحجم الافتراضي
        }
        d.setVisible(true);
        d.dispose();
    }

    // ===================================================================================
    // العمل في الخلفية
    // ===================================================================================

    interface Progress {
        void update(String msg);
    }

    interface Work<T> {
        T run(Progress p) throws Exception;
    }

    /** ينفذ عملًا بطيئًا في خيط خلفي مع نافذة انتظار، ثم done على خيط الواجهة (أو رسالة خطأ واضحة). */
    static <T> void busy(Component parent, String msg, Work<T> work, Consumer<T> done) {
        Window owner = win(parent);
        final JDialog d = new JDialog(owner, "يرجى الانتظار", Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        Card c = card();
        c.setBorder(new EmptyBorder(22, 28, 22, 28));
        final JLabel l = label(msg, 15, ink, true);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setAlignmentX(Component.RIGHT_ALIGNMENT);
        c.add(l);
        c.add(gap(12));
        c.add(bar);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(surface);
        root.setBorder(BorderFactory.createLineBorder(line));
        root.add(c);
        d.setContentPane(rtl(root));
        d.pack();
        d.setSize(Math.max(360, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(owner);
        SwingWorker<T, String> w = new SwingWorker<T, String>() {
            @Override protected T doInBackground() throws Exception { return work.run(m -> publish(m)); }

            @Override protected void process(List<String> chunks) { l.setText(chunks.get(chunks.size() - 1)); }

            @Override protected void done() {
                d.dispose();
                try {
                    T r = get();
                    if (done != null) done.accept(r);
                } catch (java.util.concurrent.ExecutionException e) {
                    error(parent, e.getCause());
                } catch (Exception e) {
                    error(parent, e);
                }
            }
        };
        w.execute();
        Timer t = new Timer(250, e -> {
            if (!w.isDone()) d.setVisible(true);
        });
        t.setRepeats(false);
        t.start();
    }

    /** إشعار قصير أسفل النافذة يختفي وحده. */
    static void toast(String msg) {
        if (App.frame == null) return;
        JWindow w = new JWindow(App.frame);
        JLabel l = label(msg, 14, Color.WHITE, true);
        l.setBorder(new EmptyBorder(10, 18, 10, 18));
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(dark ? new Color(58, 74, 68, 245) : new Color(23, 36, 31, 238));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16));
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.add(l);
        w.setBackground(new Color(0, 0, 0, 0));
        w.setContentPane(p);
        w.pack();
        Rectangle f = App.frame.getBounds();
        w.setLocation(f.x + (f.width - w.getWidth()) / 2, f.y + f.height - w.getHeight() - 40);
        w.setVisible(true);
        Timer t = new Timer(2800, e -> w.dispose());
        t.setRepeats(false);
        t.start();
    }

    // ===================================================================================
    // التاريخ
    // ===================================================================================

    /** حقل تاريخ yyyy/MM/dd مع زر تقويم. */
    static final class DateField extends JPanel {
        final Field f = new Field("سنة/شهر/يوم");
        Runnable onChange;

        DateField(long t) {
            setLayout(new BorderLayout(6, 0));
            setOpaque(false);
            setAlignmentX(Component.RIGHT_ALIGNMENT);
            set(t);
            Btn b = ghost("", e -> pick());
            b.setIcon(new Icons("daily", 18, primaryInk));
            b.setBorder(new EmptyBorder(4, 10, 4, 10));
            add(f, BorderLayout.CENTER);
            add(b, BorderLayout.LINE_END);
            setComponentOrientation(RTL);
            f.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) {
                    if (onChange != null) onChange.run();
                }
            });
            f.addActionListener(e -> {
                if (onChange != null) onChange.run();
            });
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        void set(long t) { f.setText(t <= 0 ? "" : Model.day(t)); }

        /** بداية اليوم المكتوب، أو 0 إن كان فارغًا؛ يرمي إن كان غير صالح. */
        long start() {
            String s = f.getText().trim().replace('-', '/').replace('.', '/');
            if (s.isEmpty()) return 0;
            String[] p = s.split("/");
            if (p.length != 3) throw new IllegalArgumentException("اكتب التاريخ بالشكل سنة/شهر/يوم، مثل 2026/10/15");
            int y = Integer.parseInt(digits(p[0])), m = Integer.parseInt(digits(p[1])), d = Integer.parseInt(digits(p[2]));
            if (y < 100) y += 2000;
            Calendar c = com.smart.hawala.Model.cal();
            c.setLenient(false);
            c.clear();
            c.set(y, m - 1, d, 0, 0, 0);
            return c.getTimeInMillis();
        }

        long endExclusive() {
            long s = start();
            if (s == 0) return 0;
            Calendar c = com.smart.hawala.Model.cal();
            c.setTimeInMillis(s);
            c.add(Calendar.DAY_OF_MONTH, 1);
            return c.getTimeInMillis();
        }

        void pick() {
            long cur;
            try {
                cur = start();
            } catch (Exception e) {
                cur = 0;
            }
            Calendar c = com.smart.hawala.Model.cal();
            if (cur > 0) c.setTimeInMillis(cur);
            CalendarPopup cp = new CalendarPopup(c, t -> {
                set(t);
                if (onChange != null) onChange.run();
            });
            cp.show(this, 0, getHeight());
        }
    }

    static String digits(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.trim().toCharArray()) {
            if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + ch - '٠'));
            else if (ch >= '0' && ch <= '9') b.append(ch);
        }
        if (b.length() == 0) throw new IllegalArgumentException("تاريخ غير صالح");
        return b.toString();
    }

    static final class CalendarPopup extends JPopupMenu {
        final Calendar shown;
        final Consumer<Long> pick;
        final JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
        final JLabel head = label("", 14, ink, true);
        static final String[] DAYS = {"س", "ح", "ن", "ث", "ر", "خ", "ج"};

        CalendarPopup(Calendar start, Consumer<Long> pick) {
            this.pick = pick;
            shown = (Calendar) start.clone();
            shown.set(Calendar.DAY_OF_MONTH, 1);
            JPanel p = new JPanel(new BorderLayout(4, 6));
            p.setBorder(new EmptyBorder(8, 8, 8, 8));
            p.setBackground(surface);
            Btn prev = ghost("›", e -> move(-1)), next = ghost("‹", e -> move(1));
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(prev, BorderLayout.EAST);
            top.add(next, BorderLayout.WEST);
            head.setHorizontalAlignment(SwingConstants.CENTER);
            top.add(head, BorderLayout.CENTER);
            p.add(top, BorderLayout.NORTH);
            grid.setOpaque(false);
            p.add(grid, BorderLayout.CENTER);
            Btn today = ghost("اليوم", e -> {
                setVisible(false);
                Calendar t = com.smart.hawala.Model.cal();
                t.set(Calendar.HOUR_OF_DAY, 0);
                t.set(Calendar.MINUTE, 0);
                t.set(Calendar.SECOND, 0);
                t.set(Calendar.MILLISECOND, 0);
                pick.accept(t.getTimeInMillis());
            });
            p.add(today, BorderLayout.SOUTH);
            add(rtl(p));
            render();
        }

        void move(int m) {
            shown.add(Calendar.MONTH, m);
            render();
        }

        void render() {
            grid.removeAll();
            head.setText(String.format(Locale.US, "%04d/%02d", shown.get(Calendar.YEAR), shown.get(Calendar.MONTH) + 1));
            for (String d : DAYS) {
                JLabel l = label(d, 12, muted, true);
                l.setHorizontalAlignment(SwingConstants.CENTER);
                grid.add(l);
            }
            Calendar c = (Calendar) shown.clone();
            int lead = (c.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7;
            for (int i = 0; i < lead; i++) grid.add(new JLabel());
            int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
            for (int d = 1; d <= days; d++) {
                final int day = d;
                JButton b = new JButton("" + d);
                b.setFont(font(12.5f, false));
                b.setMargin(new Insets(2, 2, 2, 2));
                b.setFocusPainted(false);
                b.addActionListener(e -> {
                    setVisible(false);
                    Calendar t = (Calendar) shown.clone();
                    t.set(Calendar.DAY_OF_MONTH, day);
                    t.set(Calendar.HOUR_OF_DAY, 0);
                    t.set(Calendar.MINUTE, 0);
                    t.set(Calendar.SECOND, 0);
                    t.set(Calendar.MILLISECOND, 0);
                    pick.accept(t.getTimeInMillis());
                });
                grid.add(b);
            }
            grid.applyComponentOrientation(RTL);
            pack();
            revalidate();
            repaint();
        }
    }

    static long startOfDay(long t) {
        Calendar c = com.smart.hawala.Model.cal();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long addDays(long t, int d) {
        Calendar c = com.smart.hawala.Model.cal();
        c.setTimeInMillis(t);
        c.add(Calendar.DAY_OF_MONTH, d);
        return c.getTimeInMillis();
    }

    static long addMonths(long t, int m) {
        Calendar c = com.smart.hawala.Model.cal();
        c.setTimeInMillis(t);
        c.add(Calendar.MONTH, m);
        return c.getTimeInMillis();
    }

    static long startOfMonth(long t) {
        Calendar c = com.smart.hawala.Model.cal();
        c.setTimeInMillis(startOfDay(t));
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** فترة سريعة: [من، إلى) واسمها. */
    static final String[] PERIODS = {"اليوم", "أمس", "آخر 7 أيام", "هذا الشهر", "الشهر الماضي", "آخر 3 أشهر", "آخر 12 شهرًا", "هذه السنة", "كل الفترات", "مخصصة…"};

    static long[] period(int i) {
        long today = startOfDay(com.smart.hawala.Clock.now()), tomorrow = addDays(today, 1);
        switch (i) {
            case 0: return new long[]{today, tomorrow};
            case 1: return new long[]{addDays(today, -1), today};
            case 2: return new long[]{addDays(today, -6), tomorrow};
            case 3: return new long[]{startOfMonth(today), tomorrow};
            case 4: return new long[]{addMonths(startOfMonth(today), -1), startOfMonth(today)};
            case 5: return new long[]{addMonths(today, -3), tomorrow};
            case 6: return new long[]{addMonths(today, -12), tomorrow};
            case 7: {
                Calendar c = com.smart.hawala.Model.cal();
                c.setTimeInMillis(today);
                c.set(Calendar.DAY_OF_YEAR, 1);
                return new long[]{c.getTimeInMillis(), tomorrow};
            }
            default: return new long[]{0, 0};
        }
    }

    /** حد أقصى لعرض النص. */
    static String cut(String s, int n) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').trim();
        return x.length() > n ? x.substring(0, n) + "…" : x;
    }

    /** مبلغ وعملة من اليسار لليمين داخل نص عربي («1,000 USD» لا «USD 1,000»). */
    static String ltr(String s) { return "\u202A" + s + "\u202C"; }

    static KeyStroke key(int code) { return KeyStroke.getKeyStroke(code, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()); }

    // ===================================================================================
    // 10.18: قطع تصميم الهاتف على الكمبيوتر (بطاقة الرصيد الخضراء، شارات لنا/علينا، أزرار المربعات، حرف المكتب)
    // ===================================================================================

    /** شارة مستديرة: «لنا» خضراء، «علينا» حمراء، «صفر» رمادية — بألوان الهاتف نفسها. */
    static final class Pill extends JLabel {
        Color fill;

        Pill(String text, Color fill, Color ink, float size) {
            super(text);
            this.fill = fill;
            setForeground(ink);
            setFont(font(size, true));
            setBorder(new EmptyBorder(3, 12, 4, 12));
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
            g2.dispose();
            super.paintComponent(g);
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
    }

    static Color usBg() { return dark ? new Color(28, 70, 48) : new Color(227, 241, 232); }

    static Color themBg() { return dark ? new Color(86, 32, 36) : new Color(248, 227, 228); }

    static Color usInk() { return dark ? new Color(150, 222, 178) : new Color(20, 92, 53); }

    static Color themInk() { return dark ? new Color(250, 170, 175) : new Color(142, 31, 39); }

    /** شارة الاتجاه وحدها: لنا / علينا / صفر. */
    static Pill side(long v) {
        return new Pill(v > 0 ? "لنا" : v < 0 ? "علينا" : "صفر", v > 0 ? usBg() : v < 0 ? themBg() : (dark ? new Color(60, 66, 63) : new Color(235, 235, 230)),
                v > 0 ? usInk() : v < 0 ? themInk() : (dark ? new Color(200, 205, 200) : new Color(70, 80, 76)), 12.5f);
    }

    /** شارة رصيد كاملة كبطاقات المكاتب في الهاتف: «لنا 980 USD» بخلفية ملونة. */
    static Pill balance(long v, int dec, String code, boolean hidden) {
        String t = (v > 0 ? "لنا " : v < 0 ? "علينا " : "") + ltr((hidden ? "••••" : Model.fmt(Math.abs(v), dec)) + " " + code);
        return new Pill(t, v > 0 ? usBg() : v < 0 ? themBg() : (dark ? new Color(60, 66, 63) : new Color(235, 235, 230)), v > 0 ? usInk() : v < 0 ? themInk() : muted, 13.5f);
    }

    /** دائرة بالحرف الأول من اسم المكتب. */
    static final class Avatar extends JComponent {
        final String letter;
        final int d;

        Avatar(String name, int d) {
            String n = Model.nz(name).trim();
            // كالهاتف: «الأمل» ← «أ» لا «ا»
            if (n.startsWith("ال") && n.length() > 3) n = n.substring(2);
            this.letter = n.isEmpty() ? "؟" : n.substring(0, n.offsetByCodePoints(0, 1));
            this.d = d;
            setPreferredSize(new Dimension(d, d));
            setMaximumSize(new Dimension(d, d));
            setMinimumSize(new Dimension(d, d));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(primarySoft);
            g2.fill(new java.awt.geom.Ellipse2D.Float(0, 0, d - 1, d - 1));
            g2.setColor(primaryInk);
            g2.setFont(font(d * 0.42f, true));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(letter, (d - fm.stringWidth(letter)) / 2f, (d - fm.getHeight()) / 2f + fm.getAscent());
            g2.dispose();
        }
    }

    /** بطاقة الرصيد الخضراء المتدرجة (كأعلى صفحة المكتب في الهاتف). */
    static final class Hero extends JPanel {
        Hero() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setBorder(new EmptyBorder(18, 24, 18, 24));
            setAlignmentX(Component.RIGHT_ALIGNMENT);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new GradientPaint(getWidth(), 0, primary, 0, getHeight(), darker(primary, 0.78f)));
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 26, 26));
            g2.dispose();
            super.paintComponent(g);
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        /** نص أبيض (أو أبيض خافت) على البطاقة. */
        static JLabel text(String s, float size, boolean bold, boolean soft) { return label(s, size, soft ? mix(Color.WHITE, primary, 0.25f) : Color.WHITE, bold); }
    }

    /** زر مربع بأيقونة فوق النص (كأزرار صفحة المكتب في الهاتف)؛ main = الزر الذهبي «حركة جديدة». */
    static final class Tile extends JButton {
        final boolean main;
        boolean over;

        Tile(String icon, String text, boolean main, java.awt.event.ActionListener a) {
            // كل سطر كاملًا لا يُكسر داخل الكلمة («الصندو ق» على الماك)
            super("<html><center><nobr>" + text.replace("\n", "</nobr><br><nobr>") + "</nobr></center></html>");
            this.main = main;
            Color ink = main ? new Color(60, 40, 10) : primaryInk;
            setIcon(new Icons(icon, 26, ink));
            setForeground(ink);
            setFont(font(13.5f, true));
            setVerticalTextPosition(SwingConstants.BOTTOM);
            setHorizontalTextPosition(SwingConstants.CENTER);
            setIconTextGap(8);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(14, 10, 14, 10));
            setPreferredSize(new Dimension(128, 108));
            setMinimumSize(new Dimension(112, 100));
            if (a != null) addActionListener(a);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                    over = true;
                    repaint();
                }

                @Override public void mouseExited(java.awt.event.MouseEvent e) {
                    over = false;
                    repaint();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color f = main ? new Color(193, 148, 66) : surface;
            if (over) f = main ? darker(f, 0.93f) : mix(surface, primary, 0.06f);
            g2.setColor(f);
            g2.fill(new RoundRectangle2D.Float(1, 1, getWidth() - 3, getHeight() - 3, 26, 26));
            if (!main) {
                g2.setColor(line);
                g2.draw(new RoundRectangle2D.Float(1, 1, getWidth() - 3, getHeight() - 3, 26, 26));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
