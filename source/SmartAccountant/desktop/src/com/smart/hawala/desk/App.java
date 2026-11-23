package com.smart.hawala.desk;

import android.content.Context;
import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * النافذة الرئيسية: شريط جانبي على اليمين بالأقسام، والمحتوى في الوسط. تُبنى الشاشات عند أول فتح.
 * تتولى أيضًا: الدخول والقفل بعد الخمول، والنسخ التلقائي وفحص البيانات في الخلفية، وقائمة الماك العلوية.
 */
final class App {
    static JFrame frame;
    /** 11.0: للتبديل إلى شكل الهاتف دون إغلاق البرنامج: كل ما يعمل في الخلفية يُوقف. */
    final java.util.List<Timer> timers = new java.util.ArrayList<>();
    private KeyEventDispatcher ked;
    private final java.util.List<java.awt.event.AWTEventListener> awt = new java.util.ArrayList<>();
    boolean stopped;
    static App instance;

    final Context ctx;
    final Db db;
    final JPanel content = new JPanel(new CardLayout());
    final JPanel side = new JPanel();
    final Map<String, View> views = new LinkedHashMap<>();
    final Map<String, Function<App, View>> factories = new LinkedHashMap<>();
    final Map<String, NavItem> nav = new LinkedHashMap<>();
    final JLabel who = Ui.label("", 12.5f, Ui.sidebarInk, false);
    final JLabel badge = Ui.label("", 12, Ui.sidebarInk, true);
    String current = "";
    /** عدد الحركات بانتظار الاعتماد (يُحدَّث بعد كل تغيير؛ لا قراءة من القاعدة أثناء الرسم). */
    volatile long pendingCached;
    long lastInput = Clock.now();
    boolean locked;

    /** 10.4: الأقسام في مجموعات بعناوين («-» = عنوان مجموعة). */
    static final String[][] SECTIONS = {
            {"-", "العمل اليومي"}, {"home", "الرئيسية"}, {"tx", "تسجيل حركة"}, {"offices", "المكاتب"}, {"mine", "مكاتبي"}, {"log", "السجل"}, {"statement", "كشف حساب"}, {"balances", "الأرصدة"},
            {"daily", "اليومي"}, {"cash", "صندوق المكتب"}, {"clients", "حسابات العملاء"},
            {"-", "التقارير"}, {"builder", "منشئ التقارير"}, {"owner", "تقارير صاحب المكتب"}, {"reports", "تقارير جاهزة"},
            {"-", "الإدارة"}, {"backup", "النسخ الاحتياطي"}, {"sync", "المزامنة"}, {"health", "الاعتمادية"}, {"users", "المستخدمون"}, {"settings", "الإعدادات"}, {"license", "الترخيص"}, {"about", "حول التطبيق"}};

    App(Context ctx, Db db) {
        this.ctx = ctx;
        this.db = db;
        instance = this;
        factories.put("home", HomeView::new);
        factories.put("tx", TxView::new);
        factories.put("fx", FxView::new);
        factories.put("xfer", TransferView::new);
        factories.put("offices", OfficesView::new);
        factories.put("mine", MineView::new);
        factories.put("team", TeamView::new);
        factories.put("office", OfficeView::new);
        factories.put("log", LogView::new);
        factories.put("statement", StatementView::new);
        factories.put("balances", BalancesView::new);
        factories.put("daily", DailyView::new);
        factories.put("cash", CashView::new);
        factories.put("clients", ClientsView::new);
        factories.put("sync", SyncView::new);
        factories.put("builder", BuilderView::new);
        factories.put("owner", OwnerView::new);
        factories.put("reports", ReportsView::new);
        factories.put("backup", BackupView::new);
        factories.put("health", HealthView::new);
        factories.put("users", UsersView::new);
        factories.put("settings", SettingsView::new);
        factories.put("license", LicenseView::new);
        factories.put("about", AboutView::new);
        factories.put("login", LoginView::new);
    }

    void show() {
        frame = new JFrame(appTitle());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        // 10.18: رجوع بلوحة المفاتيح من أي مكان: Ctrl+Z أو Esc (خارج خانات الكتابة والنوافذ)، وزر الفأرة «رجوع»
        ked = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || locked || frame == null || !frame.isActive()) return false;
            Component fo = e.getComponent();
            boolean typing = fo instanceof javax.swing.text.JTextComponent && ((javax.swing.text.JTextComponent) fo).isEditable();
            // Esc يغلق القائمة المنسدلة المفتوحة أو يلغي تحرير خلية جدول — لا يخرج من الشاشة
            JComboBox<?> cb = fo instanceof JComboBox ? (JComboBox<?>) fo : (JComboBox<?>) SwingUtilities.getAncestorOfClass(JComboBox.class, fo);
            if (cb != null && cb.isPopupVisible()) typing = true;
            JTable tb = fo instanceof JTable ? (JTable) fo : (JTable) SwingUtilities.getAncestorOfClass(JTable.class, fo);
            if (tb != null && tb.isEditing()) typing = true;
            boolean ctrlZ = e.getKeyCode() == KeyEvent.VK_Z && (e.getModifiersEx() & Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()) != 0 && !e.isShiftDown();
            boolean esc = e.getKeyCode() == KeyEvent.VK_ESCAPE && e.getModifiersEx() == 0;
            if ((ctrlZ || esc) && !typing && !history.isEmpty() && MenuSelectionManager.defaultManager().getSelectedPath().length == 0) {
                back();
                return true;
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(ked);
        listen(ev -> {
            if (ev instanceof MouseEvent && ev.getID() == MouseEvent.MOUSE_PRESSED && ((MouseEvent) ev).getButton() == 4 && !locked && frame != null && frame.isActive()) back();
        }, AWTEvent.MOUSE_EVENT_MASK);
        // 10.16: عند العودة إلى البرنامج والرئيسية ظاهرة: تُحدَّث إن تغيّر نص الحافظة (بطاقة «اللصق من واتساب»)
        frame.addWindowListener(new WindowAdapter() {
            String lastClip;

            @Override public void windowActivated(java.awt.event.WindowEvent e) {
                if (locked || !"home".equals(current)) return;
                String c = HomeView.clipText(db);
                if (c != null && !c.equals(lastClip)) {
                    lastClip = c;
                    View v = views.get("home");
                    if (v != null) v.refresh();
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        try {
            frame.setIconImages(java.util.Arrays.asList(Main.appIcon(16), Main.appIcon(32), Main.appIcon(48), Main.appIcon(64), Main.appIcon(256)));
        } catch (Throwable ignored) {
            // غير مهم
        }
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.bg);
        buildSide();
        root.add(side, BorderLayout.LINE_START);
        content.setBackground(Ui.bg);
        root.add(content, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setJMenuBar(menu());
        root.applyComponentOrientation(Ui.RTL);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setSize(Math.min(1360, scr.width - 80), Math.min(880, scr.height - 80));
        // 10.5 (§12): حد أدنى لا يتجاوز الشاشة الصغيرة، ومكان النافذة وحجمها يُستعادان كما تركهما المستخدم (إن بقيا على شاشة موجودة)
        frame.setMinimumSize(new Dimension(Math.min(1024, scr.width - 40), Math.min(680, scr.height - 60)));
        frame.setLocationRelativeTo(null);
        restoreBounds();
        listen(e -> lastInput = Clock.now(), AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        // إعادة لفّ النصوص بعد تغيير حجم النافذة (عدا نموذج الحركة حتى لا يُلمس ما يُكتب)
        Timer relayout = new Timer(450, e -> {
            View v = views.get(current);
            if (v != null && !(v instanceof TxView) && !(v instanceof RecordView) && !(v instanceof BuilderView) && !locked) {
                try {
                    v.refresh();
                } catch (Throwable ignored) {
                    // تُعاد عند الفتح التالي
                }
            }
        });
        relayout.setRepeats(false);
        timers.add(relayout);
        frame.addComponentListener(new ComponentAdapter() {
            int lastW = -1;

            @Override public void componentResized(ComponentEvent e) {
                if (Math.abs(frame.getWidth() - lastW) > 40) {
                    lastW = frame.getWidth();
                    relayout.restart();
                }
                saveBounds.restart();
            }

            @Override public void componentMoved(ComponentEvent e) { saveBounds.restart(); }
        });
        Model.User rem = db.usersEnabled() && SmartApp.currentUser == null ? db.rememberedUser() : null;
        if (rem != null) {
            // 10.2: «تذكرني» على هذا الجهاز: يُفتح بالحساب مباشرة (القفل بعد الخمول يطلب رمزه فقط)
            Db.currentUserName = rem.name;
            loggedIn(rem);
        } else if (db.usersEnabled() && SmartApp.currentUser == null) lock();
        else go("home");
        frame.setVisible(true);
        // فتح ملف .sba بالنقر المزدوج في Finder ← استعادته
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                Desktop.getDesktop().setOpenFileHandler(ev -> SwingUtilities.invokeLater(() -> {
                    for (java.io.File f : ev.getFiles()) {
                        if (!f.getName().toLowerCase().endsWith(".sba") || locked) continue;
                        go("backup");
                        View v = views.get("backup");
                        if (v instanceof BackupView) ((BackupView) v).restoreFile(f);
                        break;
                    }
                }));
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
                Desktop.getDesktop().setQuitHandler((ev, resp) -> {
                    resp.cancelQuit();
                    quit();
                });
        } catch (Throwable ignored) {
            // غير مدعوم على هذا النظام
        }
        startTimers();
        afterUnlock();
    }

    String appTitle() {
        String t = db.setting("app_title", "").trim();
        return t.isEmpty() ? "المحاسب الذكي لمكاتب الحوالات" : t;
    }

    // ===================================================================================
    // الشريط الجانبي
    // ===================================================================================

    final class NavItem extends JComponent {
        final String key, label;
        final Icons icon;
        boolean hover;

        NavItem(String key, String label) {
            this.key = key;
            this.label = label;
            icon = new Icons(key, 19, Ui.sidebarInk);
            setPreferredSize(new Dimension(236, 38));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(Component.RIGHT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { go(key); }

                @Override public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            boolean on = key.equals(navOf(current));
            // 10.10: المحدد داخل إطار ملوّن ينزلق بين العناصر (يرسمه الشريط نفسه)، وكلمته بلونه
            if (hover && !on) {
                g2.setColor(Ui.sidebarHover);
                g2.fill(new RoundRectangle2D.Float(12, 1, getWidth() - 24, getHeight() - 2, 12, 12));
            }
            icon.color = on ? navColor(key) : Ui.sidebarMuted;
            icon.paintIcon(this, g2, getWidth() - 46, (getHeight() - 19) / 2);
            g2.setFont(Ui.font(13.5f, on));
            g2.setColor(on ? navColor(key) : Ui.sidebarInk);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(label, getWidth() - 58 - fm.stringWidth(label), (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            long p = "log".equals(key) ? pendingCached : 0;
            if (p > 0) {
                String n = p > 99 ? "99+" : "" + p;
                g2.setFont(Ui.font(11, true));
                FontMetrics f2 = g2.getFontMetrics();
                int w = Math.max(20, f2.stringWidth(n) + 10), h = 18, x = 22, y = (getHeight() - h) / 2;
                g2.setColor(new Color(214, 64, 69));
                g2.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
                g2.setColor(Color.WHITE);
                g2.drawString(n, x + (w - f2.stringWidth(n)) / 2, y + (h + f2.getAscent() - f2.getDescent()) / 2);
            }
            g2.dispose();
        }
    }

    // ===================================================================================
    // 10.10: إطار القسم المحدد في الشريط الجانبي — لكل قسم لون جذاب
    // ===================================================================================

    JPanel navList;
    double navY = -1;
    Color navColorNow;
    Timer navAnim;

    /** القسم الذي يُعلَّم في الشريط لهذه الشاشة. */
    static String navOf(String cur) {
        if ("office".equals(cur)) return "offices";
        if ("fx".equals(cur) || "xfer".equals(cur)) return "tx";
        if ("team".equals(cur)) return "users";
        return cur;
    }

    static Color navColor(String k) {
        switch (k) {
            case "home": return new Color(96, 165, 250);
            case "tx": return new Color(61, 220, 151);
            case "offices": return new Color(45, 212, 191);
            case "mine": return new Color(134, 239, 172);
            case "log": return new Color(167, 139, 250);
            case "statement": return new Color(125, 211, 252);
            case "balances": return new Color(34, 211, 238);
            case "daily": return new Color(251, 191, 36);
            case "cash": return new Color(233, 196, 106);
            case "clients": return new Color(244, 114, 182);
            case "builder": case "owner": case "reports": return new Color(129, 140, 248);
            case "backup": case "sync": case "health": return new Color(251, 146, 60);
            default: return new Color(203, 213, 225);
        }
    }

    /** يحرك الإطار إلى قسم الشاشة الحالية (من مكانه السابق) ويبدّل لونه تدريجيًا. */
    void slideNav() {
        if (navList == null) return;
        NavItem n = nav.get(navOf(current));
        if (n == null || n.getHeight() == 0) {
            navY = -1;
            navList.repaint();
            if (n != null) SwingUtilities.invokeLater(() -> {
                if (n.getHeight() > 0) slideNav();
            });
            return;
        }
        double to = n.getY();
        Color cTo = navColor(n.key);
        if (navAnim != null) navAnim.stop();
        if (navY < 0 || navColorNow == null) {
            navY = to;
            navColorNow = cTo;
            navList.repaint();
            return;
        }
        double from = navY;
        Color cFrom = navColorNow;
        long t0 = Clock.now();
        navAnim = new Timer(15, null);
        navAnim.addActionListener(e -> {
            double x = Math.min(1, (Clock.now() - t0) / 560.0), y = Ui.backOut(x);
            navY = from + (to - from) * y;
            navColorNow = Ui.mix(cFrom, cTo, (float) Math.min(1, x * 1.2));
            navList.repaint();
            for (NavItem k : nav.values()) k.repaint();
            if (x >= 1) navAnim.stop();
        });
        navAnim.start();
    }

    void buildSide() {
        side.removeAll();
        side.setLayout(new BorderLayout());
        side.setBackground(Ui.sidebar);
        side.setPreferredSize(new Dimension(252, 10));
        side.setBorder(Ui.dark ? BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Ui.line), new EmptyBorder(18, 0, 12, 0))
                : new EmptyBorder(18, 0, 12, 0));
        // الرأس: الشعار واسم المكتب
        JLabel logo = new JLabel(new ImageIcon(Main.appIcon(40)));
        String full = shortTitle();
        JLabel name = Ui.label(Ui.cut(full, 20), 15, Color.WHITE, true);
        name.setToolTipText(full);
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.add(logo, BorderLayout.LINE_START);
        JPanel nm = new JPanel();
        nm.setOpaque(false);
        nm.setLayout(new BoxLayout(nm, BoxLayout.Y_AXIS));
        nm.add(Box.createVerticalStrut(2));
        nm.add(name);
        String office = db.setting("office_name", "").trim();
        JLabel off = Ui.label(Ui.cut(office.isEmpty() ? Ui.forOs() : office, 26), 12, Ui.sidebarMuted, false);
        nm.add(off);
        head.add(nm, BorderLayout.CENTER);
        head.setBorder(new EmptyBorder(0, 18, 10, 18));
        // 10.18: خانة بحث ظاهرة أعلى القائمة: تفتح «الانتقال السريع» (أقسام، مكاتب، إعدادات، إجراءات)
        final Ui.Field sq = Ui.field("بحث…  (" + (Ui.MAC ? "Cmd" : "Ctrl") + "+K)");
        sq.setFont(Ui.font(13, false));
        sq.setToolTipText("اكتب اسم قسم أو مكتب أو إعداد أو إجراء");
        sq.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void go() {
                final String t = sq.getText();
                if (t.isEmpty()) return;
                SwingUtilities.invokeLater(() -> {
                    sq.setText("");
                    QuickJump.open(App.this, t);
                });
            }

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {}

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });
        sq.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { QuickJump.open(App.this, ""); }
        });
        JPanel sqBox = new JPanel(new BorderLayout());
        sqBox.setOpaque(false);
        sqBox.setBorder(new EmptyBorder(0, 14, 10, 14));
        sqBox.add(sq);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(head, BorderLayout.NORTH);
        north.add(sqBox, BorderLayout.SOUTH);
        side.add(north, BorderLayout.NORTH);
        // الأقسام في مجموعات (تتمرر إن صغرت النافذة)
        JPanel list = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // 10.10: إطار العنصر المحدد (ينزلق من السابق إلى الجديد ويتبدل لونه)
                if (navY < 0 || navColorNow == null) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                RoundRectangle2D r = new RoundRectangle2D.Double(10, navY + 1, getWidth() - 20, 36, 14, 14);
                g2.setColor(new Color(navColorNow.getRed(), navColorNow.getGreen(), navColorNow.getBlue(), 34));
                g2.fill(r);
                g2.setStroke(new BasicStroke(1.8f));
                g2.setColor(navColorNow);
                g2.draw(r);
                g2.dispose();
            }
        };
        navList = list;
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        nav.clear();
        String pendingGroup = null;
        for (String[] s : SECTIONS) {
            if ("-".equals(s[0])) {
                pendingGroup = s[1];
                continue;
            }
            if (!navAllowed(s[0])) continue;
            if (pendingGroup != null) {
                JLabel g = Ui.label(pendingGroup, 11.5f, Ui.sidebarMuted, true);
                g.setBorder(new EmptyBorder(list.getComponentCount() == 0 ? 2 : 14, 0, 4, 26));
                g.setAlignmentX(Component.RIGHT_ALIGNMENT);
                g.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
                list.add(g);
                pendingGroup = null;
            }
            NavItem n = new NavItem(s[0], s[1]);
            nav.put(s[0], n);
            list.add(n);
        }
        JScrollPane ls = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        ls.setBorder(BorderFactory.createEmptyBorder());
        ls.setOpaque(false);
        ls.getViewport().setOpaque(false);
        ls.getVerticalScrollBar().setUnitIncrement(16);
        ls.getVerticalScrollBar().setPreferredSize(new Dimension(6, 10));
        ls.getVerticalScrollBar().setOpaque(false);
        side.add(ls, BorderLayout.CENTER);
        navY = -1;
        SwingUtilities.invokeLater(this::slideNav);
        // التذييل: مزامنة سريعة، والوضع الليلي، وبطاقة المستخدم
        JPanel foot = new JPanel();
        foot.setOpaque(false);
        foot.setLayout(new BoxLayout(foot, BoxLayout.Y_AXIS));
        foot.setBorder(new EmptyBorder(10, 16, 0, 16));
        JPanel btns = new JPanel(new GridLayout(1, 0, 8, 0));
        btns.setOpaque(false);
        btns.setAlignmentX(Component.RIGHT_ALIGNMENT);
        btns.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        if (SyncEngine.enabled(db) && License.current.sync() && !locked) btns.add(sideButton("مزامنة", "sync", e -> SyncView.now(this)));
        btns.add(sideButton(Ui.dark ? "نهاري" : "ليلي", Ui.dark ? "sun" : "moon", e -> {
            Ui.switchTheme(db, !Ui.dark);
            reloadAll();
        }));
        // 10.12: ألوان كل مستخدم له وحده (كالوضع الليلي) — متاحة لكل مستخدم
        if (!locked) btns.add(sideButton("مظهري", "settings", e -> myColors()));
        foot.add(btns);
        foot.add(Box.createVerticalStrut(10));
        JPanel userCard = new JPanel(new BorderLayout(10, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 14));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
                g2.dispose();
            }
        };
        userCard.setOpaque(false);
        userCard.setBorder(new EmptyBorder(9, 12, 9, 12));
        userCard.setAlignmentX(Component.RIGHT_ALIGNMENT);
        userCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        Model.User u = SmartApp.currentUser;
        String initial = u != null && !u.name.isEmpty() ? u.name.substring(0, 1) : "م";
        userCard.add(avatar(initial), BorderLayout.LINE_START);
        JPanel txt = new JPanel();
        txt.setOpaque(false);
        txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
        who.setForeground(Color.WHITE);
        who.setFont(Ui.font(13, true));
        badge.setForeground(Ui.sidebarMuted);
        badge.setFont(Ui.font(11.5f, false));
        txt.add(who);
        txt.add(badge);
        userCard.add(txt, BorderLayout.CENTER);
        foot.add(userCard);
        side.add(foot, BorderLayout.SOUTH);
        updateFoot();
        side.applyComponentOrientation(Ui.RTL);
        side.revalidate();
        side.repaint();
    }

    /** زر صغير شفاف في تذييل الشريط الجانبي. */
    /** 10.12: ألوان المظهر لهذا المستخدم وحده (لا تتغيّر عند غيره). */
    void myColors() {
        JPanel c = Ui.column();
        if (db.usersEnabled() && SmartApp.currentUser != null)
            c.add(Ui.para("هذه الألوان والوضع الليلي خاصة بك وحدك: لا تتغيّر عند غيرك من المستخدمين.", 13, Ui.muted, 380));
        String[][] keys = {{"primary_color", "اللون الأساسي"}, {"accent_color", "لون التمييز"}, {"bg_color", "لون الخلفية"}};
        Color[] cur = {Ui.primary, Ui.accent, Ui.bg};
        JDialog[] dlg = {null};
        JPanel row = Ui.flow();
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i][0], label = keys[i][1];
            final Color now = cur[i];
            row.add(Ui.button(label, now, e -> {
                Color picked = JColorChooser.showDialog(frame, label, now);
                if (picked == null) return;
                db.setSettingQuiet(k, Ui.hex(picked));
                if (dlg[0] != null) dlg[0].dispose();
                reloadAll();
            }));
        }
        row.add(Ui.ghost("الألوان الافتراضية", e -> {
            for (String[] k : keys) db.deleteSetting(k[0]);
            if (dlg[0] != null) dlg[0].dispose();
            reloadAll();
        }));
        c.add(row);
        // 10.17: اسمي الظاهر لبقية المحاسبين
        if (db.usersEnabled() && SmartApp.currentUser != null) {
            c.add(Ui.gap(10));
            c.add(Ui.flow(Ui.ghost("اسمي الظاهر للآخرين…", e -> UsersView.ownNick(frame, db))));
        }
        // 10.16: الخط، لون الشريط الجانبي، حجم الأزرار، إطارات البطاقات (إعدادات هذا الجهاز)
        c.add(Ui.gap(10));
        java.util.List<String[]> fonts = new java.util.ArrayList<>();
        for (String[] f : Ui.FONTS) if (Ui.hasFont(f[0])) fonts.add(f);
        String[] fn = new String[fonts.size()];
        int fsel = 0;
        for (int i = 0; i < fn.length; i++) {
            fn[i] = fonts.get(i)[1];
            if (fonts.get(i)[0].equals(db.setting("desk_font", "plex"))) fsel = i;
        }
        JComboBox<String> font = Ui.combo(fn);
        font.setSelectedIndex(fsel);
        font.addActionListener(e -> {
            db.setSettingQuiet("desk_font", fonts.get(font.getSelectedIndex())[0]);
            if (dlg[0] != null) dlg[0].dispose();
            // الخط يدخل في كل عناصر الواجهة: تُبنى النافذة من جديد
            Main.restart(this, db, "تغيّر الخط");
        });
        c.add(Ui.labeled("الخط", font));
        c.add(Ui.gap(6));
        c.add(Ui.flow(Ui.button("لون الشريط الجانبي", Ui.sidebar, e -> {
            Color picked = JColorChooser.showDialog(frame, "لون الشريط الجانبي", Ui.sidebar);
            if (picked == null) return;
            db.setSettingQuiet("desk_side_color", Ui.hex(picked));
            if (dlg[0] != null) dlg[0].dispose();
            reloadAll();
        }), Ui.ghost("لون الشريط الافتراضي", e -> {
            db.deleteSetting("desk_side_color");
            if (dlg[0] != null) dlg[0].dispose();
            reloadAll();
        })));
        c.add(Ui.gap(6));
        Ui.Seg bs = new Ui.Seg(new String[]{"أزرار عادية", "أزرار كبيرة"}, null, "big".equals(db.setting("desk_btn_size", "normal")) ? 1 : 0);
        bs.listener = i -> db.setSettingQuiet("desk_btn_size", i == 1 ? "big" : "normal");
        c.add(Ui.labeled("حجم الأزرار", bs));
        c.add(Ui.gap(6));
        Ui.Seg bo = new Ui.Seg(new String[]{"بإطارات", "بلا إطارات"}, null, "off".equals(db.setting("desk_borders", "on")) ? 1 : 0);
        bo.listener = i -> db.setSettingQuiet("desk_borders", i == 1 ? "off" : "on");
        c.add(Ui.labeled("إطارات البطاقات", bo));
        // 10.13: زر التسجيل وحركة الصفحات (لك وحدك، كالهاتف)
        c.add(Ui.gap(10));
        Ui.Seg hold = new Ui.Seg(new String[]{"ضغطة عادية", "ضغط مطوّل"}, null, "on".equals(db.setting("hold_confirm", "off")) ? 1 : 0);
        hold.listener = i -> db.setSettingQuiet("hold_confirm", i == 1 ? "on" : "off");
        c.add(Ui.labeled("زر التسجيل (التأكيد يظهر في الحالتين)", hold));
        c.add(Ui.gap(6));
        Ui.Seg mo = new Ui.Seg(new String[]{"بحركة", "بلا حركة"}, null, "none".equals(db.setting("page_motion", "stagger")) ? 1 : 0);
        mo.listener = i -> db.setSettingQuiet("page_motion", i == 1 ? "none" : "stagger");
        c.add(Ui.labeled("حركة الصفحات", mo));
        JOptionPane pane = new JOptionPane(c, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"إغلاق"});
        pane.setComponentOrientation(Ui.RTL);
        dlg[0] = pane.createDialog(frame, "مظهري");
        dlg[0].setVisible(true);
        // الإعدادات تُطبَّق على الصفحات بإعادة بنائها
        if (!Ui.lookSig(db).equals(Ui.appliedSig)) reloadAll();
    }

    Ui.Btn sideButton(String text, String icon, java.awt.event.ActionListener a) {
        Ui.Btn b = Ui.button(text, new Color(255, 255, 255, 26), a);
        b.colors(new Color(255, 255, 255, 26), Color.WHITE);
        b.setIcon(new Icons(icon, 16, Color.WHITE));
        b.setIconTextGap(6);
        b.setFont(Ui.font(12.5f, true));
        b.setBorder(new EmptyBorder(7, 10, 7, 10));
        return b;
    }

    static JComponent avatar(String letter) {
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Ui.accent);
                g2.fillOval(0, 0, 34, 34);
                g2.setColor(Color.WHITE);
                g2.setFont(Ui.font(15, true));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(letter, (34 - fm.stringWidth(letter)) / 2, (34 + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        };
        c.setPreferredSize(new Dimension(34, 34));
        return c;
    }

    String shortTitle() {
        String t = db.setting("app_title", "").trim();
        return t.isEmpty() ? "المحاسب الذكي" : t;
    }

    void updateFoot() {
        try {
            pendingCached = can(Auth.Perm.APPROVE) ? db.pendingCount() : 0;
        } catch (Throwable ignored) {
            // يبقى آخر عدد
        }
        Entitlements e = License.current;
        badge.setText(e.pro ? "✓ نسخة كاملة" + (e.lid == null || e.lid.isEmpty() ? "" : " — " + e.lid) : "النسخة المجانية");
        Model.User u = SmartApp.currentUser;
        who.setText(u == null ? (db.usersEnabled() ? "غير مسجّل" : "المدير") : u.name + " · " + Auth.shortLabel(u.role));
        if (u == null && !db.usersEnabled()) badge.setText(badge.getText() + " · " + BuildInfo.VERSION + " " + Ui.forOs());
        for (NavItem n : nav.values()) n.repaint();
    }

    // ===================================================================================
    // التنقل
    // ===================================================================================

    /** 10.5 (§12): يحفظ مكان النافذة وحجمها (إعداد خاص بهذا الجهاز، لا يُزامن). */
    Timer saveBounds;

    void persistBounds() {
        try {
            boolean max = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
            Rectangle r = frame.getBounds();
            String v = max ? (db.setting("desk_bounds", "").replaceFirst(",max$", "") + ",max") : r.x + "," + r.y + "," + r.width + "," + r.height;
            if (!v.startsWith(",")) db.setSettingQuiet("desk_bounds", v);
        } catch (Exception ignored) {
            // ليست حرجة
        }
    }

    void restoreBounds() {
        saveBounds = new Timer(800, e -> persistBounds());
        saveBounds.setRepeats(false);
        timers.add(saveBounds);
        try {
            String[] p = db.setting("desk_bounds", "").split(",");
            if (p.length < 4) return;
            Rectangle r = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            Dimension min = frame.getMinimumSize();
            r.width = Math.max(r.width, min.width);
            r.height = Math.max(r.height, min.height);
            boolean visible = false;
            for (GraphicsDevice g : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle sb = g.getDefaultConfiguration().getBounds();
                Rectangle in = sb.intersection(r);
                if (!in.isEmpty() && in.width >= 200 && in.height >= 120) visible = true;
            }
            if (!visible) return;   // الشاشة التي كانت عليها لم تعد موجودة: تبقى في الوسط
            frame.setBounds(r);
            if (p.length > 4 && "max".equals(p[4])) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        } catch (Exception ignored) {
            // قيمة تالفة: الحجم الافتراضي
        }
    }

    void go(String key) { go(key, null); }

    void go(String key, Object arg) {
        if (locked && !"login".equals(key)) return;
        if (!"cash".equals(key)) CashView.openUntil = 0;   // 10.16: مغادرة الصندوق تقفله (إن كان له قفل)
        if (!allowed(key)) {
            Ui.toast("ليست لديك صلاحية لهذا القسم.");
            return;
        }
        View v = views.get(key);
        if (v == null) {
            Function<App, View> f = factories.get(key);
            if (f == null) return;
            v = f.apply(this);
            v.build();
            views.put(key, v);
            content.add(v, key);
        }
        boolean changed = !key.equals(current);
        // 10.16: سجل تنقل للرجوع بلوحة المفاتيح (Alt+←)
        if ((changed || arg != null && !arg.equals(currentArg)) && current != null && !goingBack && !"login".equals(current)) {
            // 10.18: الرجوع كالهاتف: الذهاب إلى شاشة موجودة في السجل يعيد السجل إلى ما قبلها (فلا يدور الرجوع بين شاشتين،
            // وبعد حفظ حركة والعودة إلى المكتب يرجع «رجوع» إلى ما كان قبل المكتب لا إلى نموذج التسجيل الفارغ)
            Object[] hit = null;
            for (Object[] h : history) if (h[0].equals(key) && java.util.Objects.equals(h[1], arg)) hit = h;
            if (hit != null) {
                while (!history.isEmpty() && history.peek() != hit) history.pop();
                history.pop();
            } else if (!(key.equals(current) && java.util.Objects.equals(arg, currentArg))) {
                // نماذج التسجيل تُحفظ بلا معطاها المؤقت (تعبئة/تعديل): الرجوع إليها يفتحها كما تُركت لا بإعادة التعبئة
                Object keepArg = currentArg instanceof TxView.Prefill || currentArg instanceof FxView.Edit || currentArg instanceof RecordView.Open ? null : currentArg;
                history.push(new Object[]{current, keepArg});
                while (history.size() > 40) history.removeLast();
            }
        }
        currentArg = arg;
        current = key;
        slideNav();
        try {
            if (arg != null) v.open(arg);
            else v.refresh();
        } catch (Throwable t) {
            Ui.error(frame, t);
        }
        ((CardLayout) content.getLayout()).show(content, key);
        if (changed) v.enter();
        for (NavItem n : nav.values()) n.repaint();
        View shownView = views.get(current);
        if (shownView != null) shownView.updateBack();
    }

    final java.util.ArrayDeque<Object[]> history = new java.util.ArrayDeque<>();
    Object currentArg;
    boolean goingBack;

    /** 10.16: رجوع إلى الشاشة السابقة. */
    void back() {
        if (locked || history.isEmpty()) return;
        Object[] h = history.pop();
        goingBack = true;
        try {
            go((String) h[0], h[1]);
        } finally {
            goingBack = false;
        }
    }

    /** 10.16: تحديث الشاشة الحالية (F5). */
    void refreshCurrent() {
        View v = views.get(current);
        if (v == null || locked) return;
        try {
            v.refresh();
            Ui.toast("حُدّثت الشاشة");
        } catch (Throwable t) {
            Ui.error(frame, t);
        }
    }

    boolean allowed(String key) {
        switch (key) {
            case "backup": return can(Auth.Perm.BACKUP);
            case "users": case "team": return can(Auth.Perm.USERS);
            case "settings": return can(Auth.Perm.SETTINGS) || can(Auth.Perm.CURRENCIES);
            case "tx": case "xfer": return can(Auth.Perm.TX_ADD);
            case "fx": return can(Auth.Perm.TX_ADD) && can(Auth.Perm.TX_EDIT);
            default: return true;
        }
    }

    /** بعد أي تغيير في البيانات: تحديث الشارات، وفحص النسخ التلقائي. */
    /** 10.0: إخفاء المبالغ (العين). */
    boolean hidden() { return "1".equals(db.setting("hide_amounts", "0")); }

    /** 10.18: قفل الآن (من البحث السريع والقائمة). */
    void lockNow() {
        if (db.usersEnabled()) lock();
        else if (DeskLock.on(db)) DeskLock.lockNow(this);
        else Ui.toast("عيّن كلمة سر للقفل من «الإعدادات ← قفل البرنامج»، أو فعّل نظام المستخدمين.");
    }

    void toggleHidden() { db.setSettingQuiet("hide_amounts", hidden() ? "0" : "1"); }

    void changed() {
        updateFoot();
        DeskBackup.runIfDueAsync(this);
    }

    /** يعيد بناء الشاشة الحالية (بعد استعادة أو تغيير الإعدادات). */
    void reloadAll() {
        for (View v : views.values()) content.remove(v);
        views.clear();
        Ui.applyColors(db);
        buildSide();
        frame.setTitle(appTitle());
        go(locked ? "login" : "home");
    }

    // ===================================================================================
    // الصلاحيات والدخول
    // ===================================================================================

    /** 10.2: ما لا يملك المستخدم صلاحيته لا يظهر في القائمة أصلًا (وخاصة الصندوق). */
    boolean navAllowed(String k) {
        switch (k) {
            case "tx": return can(Auth.Perm.TX_ADD);
            case "cash": case "clients": return can(Auth.Perm.CASH);
            case "log": return can(Auth.Perm.VIEW) || can(Auth.Perm.TX_ADD);
            case "offices": case "statement": case "balances": case "daily": case "builder": case "owner": case "reports": return can(Auth.Perm.VIEW);
            case "backup": return can(Auth.Perm.BACKUP) || can(Auth.Perm.RESTORE);
            case "sync": return can(Auth.Perm.SYNC) || SyncEngine.enabled(db);
            case "health": case "settings": return can(Auth.Perm.SETTINGS);
            case "users": return can(Auth.Perm.USERS);
            // 10.5 (§7): «مكاتبي والتحقق» لمن خُصصت له مكاتب أو له صلاحية التحقق (مع نظام المستخدمين)
            case "mine": return db.usersEnabled() && (can(Auth.Perm.VERIFY) || can(Auth.Perm.USERS) || !db.myOffices().isEmpty() || !db.myCheckOffices().isEmpty());
            default: return true;
        }
    }

    boolean can(Auth.Perm p) {
        return db.allowed(p);
    }

    boolean require(Auth.Perm p) {
        if (can(p)) return true;
        Ui.toast("ليست لديك صلاحية لهذه العملية. اطلب من المدير.");
        return false;
    }

    void lock() {
        locked = true;
        SmartApp.currentUser = null;
        Db.currentUserName = "المستخدم المحلي";
        buildSide();
        updateFoot();
        go("login");
    }

    void loggedIn(Model.User u) {
        locked = false;
        SmartApp.currentUser = u;
        Db.currentUserName = u.name;
        lastInput = Clock.now();
        // 10.12: مظهر هذا المستخدم (وضعه وألوانه) لا مظهر من قبله
        if (!Ui.lookSig(db).equals(Ui.appliedSig)) {
            Ui.init(db);
            for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w);
            for (View v : views.values()) content.remove(v);
            views.clear();
        }
        buildSide();
        updateFoot();
        go("home");
        afterUnlock();
    }

    void afterUnlock() {
        new Thread(() -> {
            try {
                Thread.sleep(1500);
                db.runHealthAuto();
                SwingUtilities.invokeLater(this::updateFoot);
            } catch (Throwable ignored) {
                // يظهر في شاشة الاعتمادية
            }
            DeskBackup.runIfDueAsync(this);
            com.smart.hawala.LicenseScreenFlags.refresh(ctx, db);   // 12.7: تأكيد الوقت إن لزم (خادم الترخيص مخفي)
        }, "after-unlock").start();
    }

    long lockAfterMs() {
        long m = db.settingLong("desk_lock_min", 5);
        return m <= 0 ? 0 : m * 60000L;
    }

    void startTimers() {
        // القفل بعد الخمول (عند وجود مستخدمين)
        Timer idle = new Timer(15000, e -> {
            long after = lockAfterMs();
            if (!locked && db.usersEnabled() && after > 0 && Clock.now() - lastInput > after) lock();
            else if (!locked && after > 0 && DeskLock.on(db) && Clock.now() - lastInput > after) DeskLock.lockNow(this);
        });
        idle.start();
        timers.add(idle);
        // 10.16: تذكيرات المكاتب والنسخ (كالهاتف): بعد دقيقتين من الفتح ثم كل 30 دقيقة
        Timer remind = new Timer(30 * 60000, e -> DeskNotify.check(this));
        remind.setInitialDelay(120000);
        remind.start();
        timers.add(remind);
        // النسخ التلقائي: فحص كل 10 دقائق
        Timer auto = new Timer(10 * 60000, e -> DeskBackup.runIfDueAsync(this));
        auto.setInitialDelay(60000);
        auto.start();
        timers.add(auto);
        // 10.0: المزامنة التلقائية كل دقيقة (حين تكون مفعّلة)
        // 10.4: الفترة يختارها المستخدم من شاشة المزامنة (30 ثانية إلى 15 دقيقة)
        Timer sync = new Timer(15000, e -> {
            if (!locked && Clock.now() - SyncView.lastAuto >= SyncEngine.everySeconds(db) * 1000L) SyncView.auto(this);
        });
        // 10.1: كل عملية مسجّلة تجدول مزامنة بعد 4 ثوانٍ (المتتالية تُجمع في جولة واحدة)
        final Timer soon = new Timer(4000, e -> {
            if (!locked && "1".equals(db.setting("sync_after_change", "1"))) SyncView.auto(this);
        });
        soon.setRepeats(false);
        Db.onLocalChange = () -> SwingUtilities.invokeLater(soon::restart);
        sync.setInitialDelay(5000);
        sync.start();
        timers.add(sync);
        timers.add(soon);
    }

    private void listen(java.awt.event.AWTEventListener l, long mask) {
        awt.add(l);
        Toolkit.getDefaultToolkit().addAWTEventListener(l, mask);
    }

    /** 11.0: إغلاق هذه الواجهة وحدها (التبديل إلى شكل الهاتف): البيانات تبقى مفتوحة. */
    void stop() {
        if (stopped) return;
        stopped = true;
        persistBounds();
        for (Timer t : timers) t.stop();
        if (navAnim != null) navAnim.stop();
        if (ked != null) KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(ked);
        for (java.awt.event.AWTEventListener l : awt) Toolkit.getDefaultToolkit().removeAWTEventListener(l);
        Db.onLocalChange = null;
        if (frame != null) frame.dispose();
        frame = null;
        if (instance == this) instance = null;
    }

    void quit() {
        persistBounds();
        try {
            db.checkpoint();
        } catch (Throwable ignored) {
            // غير مهم
        }
        try {
            db.close();
        } catch (Throwable ignored) {
            // غير مهم
        }
        System.exit(0);
    }

    // ===================================================================================
    // القائمة العلوية
    // ===================================================================================

    JMenuBar menu() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("ملف");
        file.add(item("تسجيل حركة جديدة", KeyEvent.VK_N, () -> go("tx")));
        file.add(shift(item("تسجيل حركة قص عملات", 0, () -> go("fx")), KeyEvent.VK_N));
        file.add(shift(item("تسجيل حركة بين مكتبين", 0, () -> go("xfer")), KeyEvent.VK_T));
        file.add(item("مزامنة الآن", KeyEvent.VK_R, () -> SyncView.now(this)));
        file.add(item("نسخة احتياطية الآن…", KeyEvent.VK_B, () -> {
            go("backup");
            View v = views.get("backup");
            if (v instanceof BackupView) ((BackupView) v).create();
        }));
        file.add(item("استعادة نسخة من ملف…", 0, () -> {
            go("backup");
            View v = views.get("backup");
            if (v instanceof BackupView) ((BackupView) v).restoreFromFile();
        }));
        file.addSeparator();
        // 10.5 (AUDIT-027): كل مستخدم يغيّر رمزه بنفسه بعد إدخال رمزه الحالي (كالهاتف)
        file.add(item("تغيير رمزي السري…", 0, () -> {
            if (!db.usersEnabled() || SmartApp.currentUser == null) {
                Ui.toast("الرمز الشخصي يعمل بعد تفعيل المستخدمين والدخول.");
                return;
            }
            String old = LoginView.askPin(frame, "تغيير رمزي السري", "الرمز الحالي");
            if (old == null) return;
            String p1 = LoginView.askPin(frame, "الرمز الجديد", UsersView.pinRule(SmartApp.currentUser.role));
            if (p1 == null) return;
            String p2 = LoginView.askPin(frame, "تأكيد الرمز", "أعد كتابة الرمز الجديد");
            if (p2 == null) return;
            try {
                if (!p1.equals(p2)) throw new IllegalArgumentException("الرمزان غير متطابقين");
                if (!LoginView.canTry(db)) return;
                db.changeOwnPin(old, p1);
                LoginView.resetFails(db);
                Ui.toast("تم تغيير رمزك ✓");
            } catch (SecurityException e) {
                LoginView.recordFail(db, SmartApp.currentUser.name, SmartApp.currentUser.id);
                Ui.error(frame, e);
            } catch (Exception e) {
                Ui.error(frame, e);
            }
        }));
        file.add(item("تسجيل الخروج", 0, () -> {
            if (!db.usersEnabled()) return;
            db.forgetUser();
            lock();
        }));
        file.add(item("قفل البرنامج", KeyEvent.VK_L, () -> {
            if (db.usersEnabled()) lock();
            else if (DeskLock.on(db)) DeskLock.lockNow(this);
            else Ui.toast("عيّن كلمة سر للقفل من «الإعدادات ← قفل البرنامج»، أو فعّل نظام المستخدمين.");
        }));
        JMenu viewM = new JMenu("عرض");
        int i = 1;
        for (String[] s : SECTIONS) {
            if ("-".equals(s[0])) continue;
            final String k = s[0];
            viewM.add(item(s[1], i <= 9 ? KeyEvent.VK_0 + i : 0, () -> go(k)));
            i++;
        }
        // 11.0: العودة إلى شكل الهاتف (الجديد) ومعالج الشكل
        viewM.addSeparator();
        viewM.add(item("الشكل الجديد (مثل الهاتف)", 0, () -> Main.switchUi("phone")));
        viewM.add(item("معالج الشكل والحجم…", 0, () -> LookWizard.open(false)));
        // 10.16: قائمة «انتقال» للعمل بلوحة المفاتيح
        JMenu goM = new JMenu("انتقال");
        goM.add(item("الانتقال السريع (قسم أو مكتب)…", KeyEvent.VK_K, () -> QuickJump.open(this)));
        goM.add(item("البحث في سجل الحركات", KeyEvent.VK_F, () -> {
            go("log");
            View v = views.get("log");
            if (v instanceof LogView) SwingUtilities.invokeLater(() -> {
                ((LogView) v).search.requestFocusInWindow();
                ((LogView) v).search.selectAll();
            });
        }));
        JMenuItem backItem = item("رجوع", 0, this::back);
        backItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, java.awt.event.InputEvent.ALT_DOWN_MASK));
        goM.add(backItem);
        JMenuItem f5 = item("تحديث الشاشة", 0, this::refreshCurrent);
        f5.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        goM.add(f5);
        goM.add(item("إخفاء المبالغ / إظهارها", KeyEvent.VK_E, () -> {
            toggleHidden();
            View v = views.get(current);
            if (v != null && !locked) v.refresh();
        }));
        JMenu help = new JMenu("مساعدة");
        JMenuItem f1 = item("اختصارات لوحة المفاتيح", 0, () -> QuickJump.help(frame));
        f1.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
        help.add(f1);
        help.add(item("عن البرنامج", 0, () -> Ui.info(frame, "عن البرنامج", appTitle() + "\nالإصدار " + BuildInfo.VERSION + " " + Ui.forOs() + " (رقم البناء " + BuildInfo.BUILD_DATE + ")\n\n"
                + "الحسابات والنسخ الاحتياطي بالصيغة نفسها التي في تطبيق الهاتف: النسخة من أي منهما تُستعاد في الآخر.\n\nمجلد البيانات: " + ctx.home().getAbsolutePath()
                + "\n" + db.setting("developer", "برمجة: Ai creative solutions"))));
        help.add(item("فتح مجلد البيانات", 0, () -> {
            try {
                Desktop.getDesktop().open(ctx.home());
            } catch (Exception ex) {
                Ui.error(frame, ex);
            }
        }));
        mb.add(file);
        mb.add(goM);
        mb.add(viewM);
        mb.add(help);
        if (!Ui.MAC) mb.applyComponentOrientation(java.awt.ComponentOrientation.RIGHT_TO_LEFT);
        return mb;
    }

    static JMenuItem shift(JMenuItem m, int key) {
        m.setAccelerator(KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
        return m;
    }

    static JMenuItem item(String text, int key, Runnable r) {
        JMenuItem m = new JMenuItem(text);
        m.setFont(Ui.font(13, false));
        if (key != 0) m.setAccelerator(Ui.key(key));
        m.addActionListener(e -> r.run());
        return m;
    }
}
