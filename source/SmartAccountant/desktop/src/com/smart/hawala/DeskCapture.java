package com.smart.hawala;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/**
 * «إطار الالتقاط» على ويندوز وماك: إطار ملوّن فوق محادثة واتساب (أو أي برنامج)، يقرأ ما بداخله ويضع رقمًا على كل
 * رسالة؛ الضغط على الرقم ينقل نص الرسالة إلى البرنامج.
 * <p>
 * 12.2:
 * <ul>
 * <li>الإطار نوافذ منفصلة رفيعة (شريط في الأعلى وثلاث حواف وأرقام)، وداخله فارغ تمامًا: الماوس والتمرير يصلان إلى واتساب
 * مباشرة على كل الأنظمة.</li>
 * <li>الرسالة كلها رقم واحد: نجد شكل كل فقاعة في الصورة ({@link BubbleShapes}) ونجمع فيها أسطرها.</li>
 * <li>«⚡ تلقائي»: حين يتغير ما تحت الإطار (تمرير، رسالة جديدة) ثم يثبت، يُقرأ من جديد وحده.</li>
 * <li>«حدد النافذة»: كبسة على نافذة واتساب تلزق الإطار عليها، أو سحب مستطيل فوق جزء منها.</li>
 * </ul>
 * القراءة بأدوات النظام نفسه بلا مكتبات جديدة ولا اتصال: ويندوز «إمكانية الوصول» ثم قارئ الصور المدمج؛ ماك Vision.
 * لا يلمس البرنامج واتساب ولا يضغط فيه شيئًا.
 */
public final class DeskCapture implements WaMove.Capture {
    static final String TITLE = "SA-CAPTURE";
    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    static final boolean WIN = OS.contains("win"), MAC = OS.contains("mac");

    private DeskCapture() {}

    /** تُستدعى عند تحميل نسخة الكمبيوتر. */
    public static void install() {
        if (WaMove.capture == null) WaMove.capture = new DeskCapture();
        if (WaMove.collectView == null) WaMove.collectView = new DeskCollect();
    }

    /** للتجارب على لينكس: ملف فيه أسطر القارئ بإحداثيات نسبية لداخل الإطار. */
    static String fake() { return System.getProperty("sa.capture.fake"); }

    @Override public String unavailable() {
        if (GraphicsEnvironment.isHeadless()) return "لا توجد شاشة.";
        if (fake() != null) return "";
        if (WIN) return new File(powershell()).exists() ? "" : "لم أجد PowerShell في ويندوز، وهو لازم لقراءة الشاشة.";
        if (MAC) return new File("/usr/bin/osascript").exists() ? "" : "لم أجد osascript في الماك.";
        return "إطار الالتقاط متاح على ويندوز وماك فقط.";
    }

    private static Win open;

    @Override public void open(boolean whole, WaMove.Picked cb, WaMove.Prefs prefs) {
        SwingUtilities.invokeLater(() -> {
            if (open != null && open.isDisplayable()) {
                open.cb = cb;
                open.prefs = prefs;
                open.toFrontAll();
                if (whole) open.fitWindow();
                else open.read();
                return;
            }
            open = new Win(cb, prefs);
            boolean saved = open.restore();
            open.showAll();
            if (whole) open.fitWindow();
            else if (!saved && fake() == null) open.selectArea();
            else open.read();
            if (prefs == null || !"1".equals(prefs.get("cap_intro", ""))) {
                open.help();
                if (prefs != null) prefs.put("cap_intro", "1");
            }
        });
    }

    /** للتجارب. */
    static Win current() { return open; }

    // ===================================================================================
    // الإطار
    // ===================================================================================

    static final Color ACCENT = new Color(0x1F, 0x8A, 0x70), PICKED = new Color(0xD3, 0x2F, 0x2F);

    static final class Win extends JFrame {
        WaMove.Picked cb;
        WaMove.Prefs prefs;
        final JLabel status = new JLabel(" ", SwingConstants.RIGHT);
        final JToggleButton multi = new JToggleButton("عدة رسائل");
        final JToggleButton auto = new JToggleButton("قراءة تلقائية");
        final JButton send = new JButton("نقل المحدد");
        final List<Bubbles.Bubble> bubbles = new ArrayList<>();
        final Set<Integer> chosen = new HashSet<>();
        final List<JWindow> badgeWins = new ArrayList<>();
        final List<JButton> badgeBtns = new ArrayList<>();
        /** المستطيل الملوّن كله (الحواف داخله) بإحداثيات الشاشة؛ الشريط فوقه. */
        Rectangle area = new Rectangle(100, 140, 460, 600);
        final JWindow left, right, bottom;
        String chat = "";
        String idle = " ";
        volatile boolean reading;
        boolean dragging, hovering;
        private final javax.swing.Timer settle, watch, save;
        private final AtomicBoolean sampling = new AtomicBoolean();
        private int[] lastSig;
        private boolean pending;
        private int stable;
        static final int BAR = 66, EDGE = 8, BADGE = 28, MIN_W = 440, MIN_H = 160, BOX_MIN_W = 250, BOX_MIN_H = 70;
        /** 12.5: «مربع النسخ»: مربع صغير يُوضع فوق رسالة، و«نسخ» ينقل كل ما تحته نصًا واحدًا. */
        boolean boxMode;
        private Rectangle beforeBox;
        final JButton copyBtn = new JButton("نسخ ما في المربع");
        final JToggleButton boxBtn = new JToggleButton("مربع نسخ");
        private final List<Component> normalOnly = new ArrayList<>();

        int minW() { return boxMode ? BOX_MIN_W : MIN_W; }

        int minH() { return boxMode ? BOX_MIN_H : MIN_H; }

        Win(WaMove.Picked cb, WaMove.Prefs prefs) {
            super(TITLE);
            this.cb = cb;
            this.prefs = prefs;
            setUndecorated(true);
            setAlwaysOnTop(true);
            setContentPane(bar());
            left = edge(1);
            right = edge(2);
            bottom = edge(4);
            settle = new javax.swing.Timer(650, e -> read());
            settle.setRepeats(false);
            save = new javax.swing.Timer(800, e -> saveBounds());
            save.setRepeats(false);
            watch = new javax.swing.Timer(450, e -> tick());
            auto.setSelected(prefs == null || !"0".equals(prefs.get("cap_auto", "1")));
            autoLabel();
            getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        // ------------------------------- الشريط -------------------------------

        private JComponent bar() {
            JPanel p = new JPanel(new BorderLayout(0, 2)) {
                @Override protected void paintComponent(Graphics g) {
                    g.setColor(ACCENT);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            p.setBorder(BorderFactory.createEmptyBorder(5, 6, EDGE, 6));
            p.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
            btns.setOpaque(false);
            btns.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            JButton pick = small("حدد النافذة", "اضغط على نافذة واتساب لتحديدها كلها، أو اسحب مستطيلًا فوق الرسائل");
            pick.addActionListener(e -> selectArea());
            JButton again = small("اقرأ", "اقرأ ما داخل الإطار الآن");
            again.addActionListener(e -> read());
            style(auto, "قراءة تلقائية: حين تمرّر في واتساب وتتوقف، تُقرأ الرسائل من جديد وحدها");
            auto.addActionListener(e -> {
                if (prefs != null) prefs.put("cap_auto", auto.isSelected() ? "1" : "0");
                lastSig = null;
                pending = false;
                autoLabel();
                if (auto.isSelected() && bubbles.isEmpty()) read();
            });
            autoLabel();
            style(multi, "لعدة رسائل: اضغط «عدة رسائل» ثم الأرقام ثم «نقل المحدد»");
            multi.addActionListener(e -> {
                chosen.clear();
                refreshSend();
                layoutBadges();
            });
            style(send, "انقل الرسائل المحددة دفعة واحدة");
            send.addActionListener(e -> sendChosen());
            send.setVisible(false);
            JButton q = small("؟", "شرح الإطار");
            q.addActionListener(e -> help());
            JButton close = small("×", "إغلاق الإطار (Esc)");
            close.addActionListener(e -> dispose());
            style(copyBtn, "انقل كل النص الذي تحت المربع إلى البرنامج رسالةً واحدة");
            copyBtn.setBackground(new Color(0xFF, 0xF1, 0x76));
            copyBtn.addActionListener(e -> copyBox());
            copyBtn.setVisible(false);
            style(boxBtn, "مربع صغير تضعه فوق رسالة واحدة ثم «نسخ ما في المربع» (بدل الأرقام)");
            boxBtn.addActionListener(e -> setBoxMode(boxBtn.isSelected()));
            btns.add(copyBtn);
            btns.add(pick);
            btns.add(again);
            btns.add(auto);
            btns.add(multi);
            btns.add(boxBtn);
            btns.add(q);
            btns.add(close);
            normalOnly.add(pick);
            normalOnly.add(again);
            normalOnly.add(auto);
            normalOnly.add(multi);
            p.add(btns, BorderLayout.NORTH);
            status.setForeground(Color.WHITE);
            status.setFont(status.getFont().deriveFont(Font.BOLD, 12f));
            status.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            JPanel row2 = new JPanel(new BorderLayout(6, 0));
            row2.setOpaque(false);
            row2.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            row2.add(status, BorderLayout.CENTER);
            row2.add(send, BorderLayout.LINE_END);
            p.add(row2, BorderLayout.CENTER);
            Mover mv = new Mover(8);
            p.addMouseListener(mv);
            p.addMouseMotionListener(mv);
            status.addMouseListener(mv);
            status.addMouseMotionListener(mv);
            row2.addMouseListener(mv);
            row2.addMouseMotionListener(mv);
            btns.addMouseListener(mv);
            btns.addMouseMotionListener(mv);
            return p;
        }

        private void autoLabel() {
            auto.setText(auto.isSelected() ? "قراءة تلقائية: تعمل" : "قراءة تلقائية: متوقفة");
            auto.setBackground(auto.isSelected() ? new Color(0xFF, 0xF1, 0x76) : null);
        }

        private JButton small(String s, String tip) {
            JButton b = new JButton(s);
            style(b, tip);
            return b;
        }

        private void style(AbstractButton b, String tip) {
            b.setToolTipText(tip);
            b.setFocusable(false);
            b.setMargin(new Insets(2, 6, 2, 6));
            b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        }

        private void refreshSend() {
            send.setVisible(multi.isSelected());
            send.setText("نقل المحدد (" + chosen.size() + ")");
            send.setEnabled(!chosen.isEmpty());
        }

        void say(String s) {
            idle = s == null || s.isEmpty() ? " " : s;
            if (!hovering) status.setText(idle);
        }

        // ------------------------------- الحواف -------------------------------

        private JWindow edge(int mode) {
            JWindow w = new JWindow(this);
            w.setAlwaysOnTop(true);
            w.setFocusableWindowState(false);
            JPanel p = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    g.setColor(ACCENT);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    // مقبض الزاوية السفلية لتغيير الحجم
                    g.setColor(Color.WHITE);
                    if (mode == 4) {
                        g.fillRect(getWidth() - 22, 2, 18, getHeight() - 4);
                        g.fillRect(4, 2, 18, getHeight() - 4);
                    }
                }
            };
            p.setToolTipText("اسحب لتغيير الحجم");
            Mover mv = new Mover(mode);
            p.addMouseListener(mv);
            p.addMouseMotionListener(mv);
            w.setContentPane(p);
            return w;
        }

        void setArea(Rectangle r) {
            Rectangle sc = screens();
            r = new Rectangle(r);
            r.width = Math.max(minW(), Math.min(r.width, sc.width));
            r.height = Math.max(minH(), Math.min(r.height, sc.height - BAR));
            // الشريط يبقى ظاهرًا على الشاشة
            if (r.y - BAR < sc.y) r.y = sc.y + BAR;
            if (r.x < sc.x - r.width + 80) r.x = sc.x - r.width + 80;
            if (r.x > sc.x + sc.width - 80) r.x = sc.x + sc.width - 80;
            if (r.y > sc.y + sc.height - 40) r.y = sc.y + sc.height - 40;
            area = r;
            setBounds(r.x, r.y - BAR, r.width, BAR + EDGE);
            left.setBounds(r.x, r.y + EDGE, EDGE, r.height - 2 * EDGE);
            right.setBounds(r.x + r.width - EDGE, r.y + EDGE, EDGE, r.height - 2 * EDGE);
            bottom.setBounds(r.x, r.y + r.height - EDGE, r.width, EDGE);
            validate();
        }

        /** مكان الإطار المحفوظ على هذا الجهاز. */
        boolean restore() {
            Rectangle sc = screens();
            String s = prefs == null ? "" : prefs.get("cap_bounds", "");
            try {
                String[] p = s.split(",");
                if (p.length == 4) {
                    Rectangle r = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
                    if (sc.intersects(r)) {
                        setArea(r);
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // إعداد تالف
            }
            Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int w = Math.min(460, b.width - 40), h = Math.min(600, b.height - BAR - 40);
            setArea(new Rectangle(b.x + b.width - w - 60, b.y + BAR + Math.max(10, (b.height - h - BAR) / 2), w, h));
            return false;
        }

        private void saveBounds() {
            if (prefs != null && isDisplayable()) prefs.put("cap_bounds", area.x + "," + area.y + "," + area.width + "," + area.height);
        }

        void showAll() {
            setVisible(true);
            left.setVisible(true);
            right.setVisible(true);
            bottom.setVisible(true);
            watch.start();
        }

        void toFrontAll() {
            toFront();
            left.toFront();
            right.toFront();
            bottom.toFront();
            for (JWindow b : badgeWins) b.toFront();
        }

        /** داخل الإطار (حيث تُقرأ الرسائل) بإحداثيات الشاشة. */
        Rectangle inner() {
            return new Rectangle(area.x + EDGE, area.y + EDGE, area.width - 2 * EDGE, area.height - 2 * EDGE);
        }

        /** حُرّك الإطار أو تغيّر حجمه: تُمسح الأرقام وتُقرأ الرسائل بعد أن يتوقف. */
        void moved() {
            clearBadges();
            lastSig = null;
            pending = false;
            if (!boxMode) settle.restart();
            save.restart();
        }

        /** 12.5: تشغيل «مربع النسخ» أو الرجوع لوضع الأرقام. */
        void setBoxMode(boolean on) {
            if (on == boxMode) return;
            settle.stop();
            clearBadges();
            bubbles.clear();
            chosen.clear();
            multi.setSelected(false);
            refreshSend();
            boxMode = on;
            boxBtn.setSelected(on);
            boxBtn.setText(on ? "وضع الأرقام" : "مربع نسخ");
            copyBtn.setVisible(on);
            for (Component c : normalOnly) c.setVisible(!on);
            if (on) {
                beforeBox = new Rectangle(area);
                Rectangle in = inner();
                int w = Math.min(380, Math.max(BOX_MIN_W, in.width)), h = 150;
                setArea(new Rectangle(in.x + (in.width - w) / 2, in.y + Math.max(0, (in.height - h) / 2), w, h));
                say("ضع المربع فوق رسالة (اسحبه من شريطه، وغيّر حجمه من حوافه) ثم «نسخ ما في المربع».");
            } else {
                if (beforeBox != null) setArea(beforeBox);
                lastSig = null;
                read();
            }
            validate();
            repaint();
        }

        /** 12.5: كل ما تحت المربع نصًا واحدًا (المرسل والوقت يُفصلان كما في الفقاعة). */
        void copyBox() {
            if (reading || !isShowing()) return;
            reading = true;
            say("جارٍ قراءة ما في المربع…");
            final Rectangle in = inner();
            new Thread(() -> {
                Bubbles.Result r = null;
                String err = null;
                try {
                    r = Reader.readBox(in);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final Bubbles.Result fr = r;
                final String fe = err;
                SwingUtilities.invokeLater(() -> {
                    reading = false;
                    if (fr == null || fr.bubbles.isEmpty()) {
                        say(fe != null ? fe : MAC ? "لم أقرأ نصًا. كبّر المربع قليلًا، أو اسمح بـ«تسجيل الشاشة» للبرنامج."
                                : "لم أقرأ نصًا تحت المربع. كبّره قليلًا حول الرسالة.");
                        return;
                    }
                    List<WaText.Msg> l = new ArrayList<>();
                    l.add(fr.bubbles.get(0).msg());
                    chat = fr.chat == null ? "" : fr.chat;
                    deliver(l);
                    String t = fr.bubbles.get(0).text.replace('\n', ' ');
                    say("نُقل: " + (t.length() > 60 ? t.substring(0, 60) + "…" : t));
                });
            }, "capture-box").start();
        }

        // ------------------------------- الأرقام -------------------------------

        void clearBadges() {
            for (JWindow w : badgeWins) w.dispose();
            badgeWins.clear();
            badgeBtns.clear();
            if (hovering) {
                hovering = false;
                status.setText(idle);
            }
        }

        /** أزرار الأرقام الظاهرة (للتجارب). */
        List<JButton> badges() { return new ArrayList<>(badgeBtns); }

        void layoutBadges() {
            clearBadges();
            if (!isShowing()) return;
            Rectangle in = inner();
            int mid = in.x + in.width / 2;
            for (final Bubbles.Bubble b : bubbles) {
                final JButton badge = new JButton(String.valueOf(b.number));
                badge.setName("badge-" + b.number);
                badge.setFont(badge.getFont().deriveFont(Font.BOLD, 13f));
                badge.setMargin(new Insets(0, 0, 0, 0));
                badge.setFocusable(false);
                badge.setBackground(chosen.contains(b.number) ? PICKED : ACCENT);
                badge.setForeground(Color.WHITE);
                badge.setOpaque(true);
                badge.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
                badge.addActionListener(e -> pick(b));
                badge.addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) {
                        hovering = true;
                        status.setText(b.number + ": " + preview(b.text));
                    }

                    @Override public void mouseExited(MouseEvent e) {
                        hovering = false;
                        status.setText(idle);
                        lastSig = null;
                    }
                });
                // 12.5: بجانب الفقاعة من جهتها الخارجية (حيث صورة المرسل) لا فوق نصها؛ وإن لم يتسع المكان فعلى زاويتها
                boolean incoming = b.x + b.w / 2 < mid;
                int x = incoming ? b.x - BADGE - 4 : b.x + b.w + 4;
                if (x < in.x || x + BADGE > in.x + in.width) x = incoming ? b.x + 2 : b.x + b.w - BADGE - 2;
                x = Math.max(in.x, Math.min(in.x + in.width - BADGE, x));
                int y = Math.max(in.y, Math.min(in.y + in.height - BADGE, b.y + 2));
                JWindow w = new JWindow(this);
                w.setAlwaysOnTop(true);
                w.setFocusableWindowState(false);
                w.setContentPane(badge);
                w.setBounds(x, y, BADGE, BADGE);
                w.setVisible(true);
                badgeWins.add(w);
                badgeBtns.add(badge);
            }
        }

        private static String preview(String t) {
            String s = t.replace('\n', ' ');
            return s.length() > 90 ? s.substring(0, 90) + "…" : s;
        }

        void pick(Bubbles.Bubble b) {
            if (multi.isSelected()) {
                if (!chosen.remove(b.number)) chosen.add(b.number);
                refreshSend();
                for (JButton x : badgeBtns) x.setBackground(chosen.contains(Integer.parseInt(x.getText())) ? PICKED : ACCENT);
                return;
            }
            List<WaText.Msg> l = new ArrayList<>();
            l.add(b.msg());
            deliver(l);
            say("نُقلت الرسالة " + b.number + ". اضغط رقمًا آخر أو أغلق الإطار.");
        }

        void sendChosen() {
            List<WaText.Msg> l = new ArrayList<>();
            for (Bubbles.Bubble b : bubbles) if (chosen.contains(b.number)) l.add(b.msg());
            if (l.isEmpty()) return;
            chosen.clear();
            multi.setSelected(false);
            refreshSend();
            layoutBadges();
            deliver(l);
            say("نُقلت " + l.size() + " رسائل إلى البرنامج.");
        }

        private void deliver(List<WaText.Msg> l) {
            final WaMove.Picked c = cb;
            final String ch = chat;
            if (c == null) return;
            SwingUtilities.invokeLater(() -> c.picked(l, ch));
        }

        // ------------------------------- القراءة -------------------------------

        void read() {
            if (reading || !isShowing()) return;
            reading = true;
            settle.stop();
            boolean had = !badgeWins.isEmpty();
            clearBadges();
            say("جارٍ القراءة…");
            final Rectangle in = inner();
            final double scale = scaleOf(this);
            final Rectangle bar = getBounds();
            new Thread(() -> {
                Bubbles.Result r = null;
                String err = null;
                try {
                    // تختفي الأرقام من الشاشة قبل التصوير
                    if (had) Thread.sleep(150);
                    r = Reader.read(in, scale, bar);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final Bubbles.Result fr = r;
                final String fe = err;
                SwingUtilities.invokeLater(() -> {
                    reading = false;
                    shown(fr, fe);
                    lastSig = null;
                    pending = false;
                });
            }, "capture-read").start();
        }

        void shown(Bubbles.Result r, String err) {
            bubbles.clear();
            chosen.clear();
            refreshSend();
            if (!isDisplayable()) return;
            if (r == null) {
                say(err == null ? "تعذّرت القراءة." : err);
                return;
            }
            bubbles.addAll(r.bubbles);
            chat = r.chat == null ? "" : r.chat;
            if (bubbles.isEmpty()) {
                say(MAC ? "لم أجد رسائل. اسمح بـ«تسجيل الشاشة» للبرنامج من إعدادات الماك ← الخصوصية والأمان، ثم «اقرأ»."
                        : "لم أجد رسائل داخل الإطار. اضغط «حدد النافذة» واختر نافذة واتساب.");
                return;
            }
            say((chat.isEmpty() ? "" : "«" + chat + "» • ") + bubbles.size() + (bubbles.size() == 1 ? " رسالة" : " رسائل") + ": اضغط رقم الرسالة لنقلها");
            layoutBadges();
        }

        /** «نافذة واتساب»: يغطي الإطار نافذة واتساب كلها ثم يقرأ. */
        void fitWindow() {
            if (reading) return;
            reading = true;
            say("أبحث عن نافذة واتساب…");
            final double scale = scaleOf(this);
            final Rectangle bar = getBounds();
            new Thread(() -> {
                Rectangle w = null;
                String err = null;
                try {
                    w = Reader.whatsappWindow(scale, bar);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final Rectangle fw = w;
                final String fe = err;
                SwingUtilities.invokeLater(() -> {
                    reading = false;
                    if (fw == null) {
                        say(fe != null ? fe : "لم أجد نافذة واتساب مفتوحة. اضغط «حدد النافذة» ثم اضغط على النافذة التي فيها الرسائل.");
                        return;
                    }
                    around(fw);
                    read();
                });
            }, "capture-fit").start();
        }

        /** يضع الإطار حول مستطيل (داخله = المستطيل). */
        void around(Rectangle target) {
            clearBadges();
            setArea(new Rectangle(target.x - EDGE, target.y - EDGE, target.width + 2 * EDGE, target.height + 2 * EDGE));
            settle.stop();
            save.restart();
            lastSig = null;
            pending = false;
        }

        /** «حدد النافذة»: كبسة على نافذة أو سحب مستطيل. */
        void selectArea() {
            clearBadges();
            new Picker(this).begin();
        }

        // ------------------------------- القراءة التلقائية -------------------------------

        /** كل 450 م.ث: صورة صغيرة لما تحت الإطار؛ إن تغيّرت ثم ثبتت تُقرأ من جديد. */
        private void tick() {
            if (boxMode || !auto.isSelected() || reading || dragging || hovering || !isShowing() || fake() != null) return;
            if (!sampling.compareAndSet(false, true)) return;
            final Rectangle in = inner();
            final List<Rectangle> mask = new ArrayList<>();
            for (JWindow w : badgeWins) mask.add(w.getBounds());
            new Thread(() -> {
                int[] sig = null;
                try {
                    sig = signature(new Robot().createScreenCapture(in), in, mask);
                } catch (Throwable ignored) {
                    sig = null;
                } finally {
                    sampling.set(false);
                }
                final int[] s = sig;
                SwingUtilities.invokeLater(() -> compare(s));
            }, "capture-watch").start();
        }

        private void compare(int[] sig) {
            if (sig == null || reading || !auto.isSelected()) return;
            if (lastSig == null || lastSig.length != sig.length) {
                lastSig = sig;
                return;
            }
            if (changed(lastSig, sig)) {
                lastSig = sig;
                pending = true;
                stable = 0;
                if (!badgeWins.isEmpty()) {
                    clearBadges();
                    say("…");
                }
                return;
            }
            if (pending && ++stable >= 1) {
                pending = false;
                read();
            }
        }

        @Override public void dispose() {
            settle.stop();
            watch.stop();
            if (save.isRunning()) {
                save.stop();
                saveBounds();
            }
            clearBadges();
            left.dispose();
            right.dispose();
            bottom.dispose();
            super.dispose();
            if (open == this) open = null;
        }

        void saveShot() {
            final Rectangle in = inner();
            new Thread(() -> {
                String res;
                try {
                    BufferedImage img = Reader.grab(in);
                    File dir = new File(System.getProperty("user.home"), "Desktop");
                    if (!dir.isDirectory()) dir = new File(System.getProperty("user.home"));
                    File f = new File(dir, "sa-capture-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".png");
                    javax.imageio.ImageIO.write(img, "png", f);
                    res = "حُفظت الصورة: " + f.getName() + " (على سطح المكتب). أرسلها للمطوّر.";
                } catch (Throwable t) {
                    res = "تعذّر حفظ الصورة: " + t.getMessage();
                }
                final String m = res;
                SwingUtilities.invokeLater(() -> say(m));
            }, "capture-shot").start();
        }

        void help() {
            String msg = "<html><div style='width:330px' dir='rtl'>"
                    + "<b>1.</b> اضغط <b>«حدد النافذة»</b> ثم اضغط على نافذة واتساب، أو اسحب بالماوس مستطيلًا فوق الرسائل.<br><br>"
                    + "<b>2.</b> على كل رسالة يظهر رقم. اضغط الرقم فتنتقل الرسالة كلها إلى البرنامج.<br><br>"
                    + "<b>3.</b> مرّر الرسائل في واتساب كالعادة من داخل الإطار. مع <b>«قراءة تلقائية: تعمل»</b> تُقرأ من جديد وحدها حين تتوقف.<br><br>"
                    + "<b>4.</b> لعدة رسائل: <b>«عدة رسائل»</b> ثم اضغط الأرقام ثم <b>«نقل المحدد»</b>.<br><br>"
                    + "<b>5.</b> حرّك الإطار من شريطه الأخضر، وغيّر حجمه من حوافه.<br><br>"
                    + "<b>6.</b> <b>«مربع نسخ»</b>: مربع صغير تضعه فوق رسالة واحدة، ثم «نسخ ما في المربع» فينتقل كل ما تحته.<br><br>"
                    + "رسائل الصور والملصقات لا تُرقَّم.<br><br>"
                    + (MAC ? "على الماك: اسمح للبرنامج بـ«تسجيل الشاشة» من إعدادات النظام ← الخصوصية والأمان.<br><br>" : "")
                    + "البرنامج يقرأ الشاشة فقط ولا يضغط شيئًا في واتساب، ولا يُسجَّل شيء قبل مراجعتك في نموذج التسجيل.</div></html>";
            JOptionPane pane = new JOptionPane(msg, JOptionPane.INFORMATION_MESSAGE);
            // 12.5: صورة لما تحت الإطار تُحفظ على سطح المكتب لترسلها للمطوّر حين لا تُعرف الرسائل
            JButton shotBtn = new JButton("حفظ صورة ما تحت الإطار (للفحص)");
            shotBtn.addActionListener(e -> saveShot());
            pane.setOptions(new Object[]{"فهمت", shotBtn});
            pane.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            JDialog d = pane.createDialog(this, "كيف أستعمل إطار الالتقاط؟");
            d.setModal(false);
            d.setAlwaysOnTop(true);
            d.setVisible(true);
        }

        /** سحب الإطار من شريطه (8)، وتغيير حجمه من حوافه: 1 يسار، 2 يمين، 4 أسفل. */
        private final class Mover extends MouseAdapter {
            final int base;
            Point start;
            Rectangle startA;
            int mode;

            Mover(int base) { this.base = base; }

            int modeAt(MouseEvent e) {
                if (base != 4) return base;
                int x = e.getX(), w = e.getComponent().getWidth();
                if (x >= w - 24) return 4 | 2;
                if (x <= 24) return 4 | 1;
                return 4;
            }

            @Override public void mouseMoved(MouseEvent e) {
                int m = modeAt(e);
                int c = m == 8 ? Cursor.MOVE_CURSOR : m == 6 ? Cursor.SE_RESIZE_CURSOR : m == 5 ? Cursor.SW_RESIZE_CURSOR
                        : m == 1 ? Cursor.W_RESIZE_CURSOR : m == 2 ? Cursor.E_RESIZE_CURSOR : Cursor.S_RESIZE_CURSOR;
                e.getComponent().setCursor(Cursor.getPredefinedCursor(c));
            }

            @Override public void mousePressed(MouseEvent e) {
                start = e.getLocationOnScreen();
                startA = new Rectangle(area);
                mode = modeAt(e);
                dragging = true;
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (start == null) return;
                int dx = e.getXOnScreen() - start.x, dy = e.getYOnScreen() - start.y;
                Rectangle b = new Rectangle(startA);
                if (mode == 8) b.setLocation(startA.x + dx, startA.y + dy);
                else {
                    if ((mode & 1) != 0) { b.x = startA.x + dx; b.width = startA.width - dx; }
                    if ((mode & 2) != 0) b.width = startA.width + dx;
                    if ((mode & 4) != 0) b.height = startA.height + dy;
                    if (b.width < minW()) { if ((mode & 1) != 0) b.x -= minW() - b.width; b.width = minW(); }
                    if (b.height < minH()) b.height = minH();
                }
                clearBadges();
                setArea(b);
            }

            @Override public void mouseReleased(MouseEvent e) {
                boolean was = start != null && !area.equals(startA);
                start = null;
                dragging = false;
                if (was) moved();
            }
        }
    }

    // ===================================================================================
    // «حدد النافذة»: طبقة شفافة فوق الشاشة كلها
    // ===================================================================================

    static final class Picker extends JWindow {
        final Win win;
        Point from, to;

        Picker(Win win) {
            super(win);
            this.win = win;
            setAlwaysOnTop(true);
            Rectangle sc = screens();
            setBounds(sc);
            boolean translucent = false;
            try {
                GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                if (gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                    setBackground(new Color(0, 0, 0, 0));
                    translucent = true;
                }
            } catch (Throwable ignored) {
                translucent = false;
            }
            if (!translucent) try { setOpacity(0.35f); } catch (Throwable ignored) { /* بلا شفافية */ }
            final boolean see = translucent;
            JPanel p = new JPanel() {
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setComposite(AlphaComposite.Src);
                    g.setColor(see ? new Color(0, 0, 0, 70) : Color.DARK_GRAY);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    if (from != null && to != null) {
                        Rectangle r = rect();
                        r.translate(-getX(), -getY());
                        g.setColor(see ? new Color(255, 255, 255, 40) : Color.GRAY);
                        g.fillRect(r.x, r.y, r.width, r.height);
                        g.setComposite(AlphaComposite.SrcOver);
                        g.setColor(ACCENT);
                        g.setStroke(new BasicStroke(4));
                        g.drawRect(r.x, r.y, r.width, r.height);
                    }
                    g.setComposite(AlphaComposite.SrcOver);
                    String t1 = "اضغط على نافذة واتساب لتحديدها كلها";
                    String t2 = "أو اسحب بالماوس مستطيلًا فوق الرسائل  •  Esc للإلغاء";
                    Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                    g.setFont(getFont().deriveFont(Font.BOLD, 20f));
                    FontMetrics fm = g.getFontMetrics();
                    int w = Math.max(fm.stringWidth(t1), g.getFontMetrics(getFont().deriveFont(15f)).stringWidth(t2)) + 48;
                    int x = b.x - Picker.this.getX() + (b.width - w) / 2, y = b.y - Picker.this.getY() + 40;
                    g.setColor(ACCENT);
                    g.fillRoundRect(x, y, w, 76, 18, 18);
                    g.setColor(Color.WHITE);
                    g.drawString(t1, x + (w - fm.stringWidth(t1)) / 2, y + 32);
                    g.setFont(getFont().deriveFont(15f));
                    FontMetrics f2 = g.getFontMetrics();
                    g.drawString(t2, x + (w - f2.stringWidth(t2)) / 2, y + 60);
                    g.dispose();
                }
            };
            p.setOpaque(false);
            p.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    from = e.getLocationOnScreen();
                    to = from;
                }

                @Override public void mouseDragged(MouseEvent e) {
                    to = e.getLocationOnScreen();
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent e) {
                    to = e.getLocationOnScreen();
                    if (from == null) return;
                    Rectangle r = rect();
                    if (r.width >= 40 && r.height >= 40) done(r);
                    else clicked(from);
                }
            };
            p.addMouseListener(ma);
            p.addMouseMotionListener(ma);
            setContentPane(p);
            getRootPane().registerKeyboardAction(e -> {
                dispose();
                win.say("أُلغي التحديد.");
            }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        Rectangle rect() {
            return new Rectangle(Math.min(from.x, to.x), Math.min(from.y, to.y), Math.abs(from.x - to.x), Math.abs(from.y - to.y));
        }

        void begin() {
            win.say("حدد نافذة واتساب…");
            setVisible(true);
            toFront();
            requestFocus();
        }

        void done(Rectangle r) {
            dispose();
            win.around(r);
            win.read();
        }

        /** كبسة: النافذة التي تحت الماوس (بعد إخفاء الطبقة). */
        void clicked(final Point at) {
            setVisible(false);
            win.say("أبحث عن النافذة…");
            final double scale = scaleOf(win);
            final Rectangle bar = win.getBounds();
            new Thread(() -> {
                Rectangle w = null;
                String err = null;
                try {
                    Thread.sleep(120);
                    w = Reader.windowAt(at, scale, bar);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final Rectangle fw = w;
                final String fe = err;
                SwingUtilities.invokeLater(() -> {
                    dispose();
                    if (fw == null) {
                        win.say(fe != null ? fe : "لم أعرف النافذة هنا. اضغط «حدد النافذة» واسحب مستطيلًا فوق الرسائل.");
                        return;
                    }
                    win.around(fw);
                    win.read();
                });
            }, "capture-pick").start();
        }
    }

    // ===================================================================================
    // أدوات
    // ===================================================================================

    /** كل الشاشات معًا. */
    static Rectangle screens() {
        Rectangle all = null;
        try {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                all = all == null ? new Rectangle(b) : all.union(b);
            }
        } catch (Throwable ignored) {
            all = null;
        }
        return all == null ? new Rectangle(0, 0, 1920, 1080) : all;
    }

    /**
     * بصمة صغيرة لصورة ما تحت الإطار: متوسط الإضاءة في خلايا 8×8 بكسل، والخلايا تحت الأرقام صفر.
     */
    static int[] signature(BufferedImage img, Rectangle in, List<Rectangle> mask) {
        int cell = 8;
        int cw = Math.max(1, img.getWidth() / cell), ch = Math.max(1, img.getHeight() / cell);
        double kx = img.getWidth() / (double) Math.max(1, in.width), ky = img.getHeight() / (double) Math.max(1, in.height);
        int[] sig = new int[cw * ch];
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int x0 = cx * cell, y0 = cy * cell;
                boolean masked = false;
                for (Rectangle m : mask) {
                    int mx = (int) ((m.x - in.x) * kx), my = (int) ((m.y - in.y) * ky), mw = (int) (m.width * kx) + 1, mh = (int) (m.height * ky) + 1;
                    if (x0 + cell > mx && x0 < mx + mw && y0 + cell > my && y0 < my + mh) { masked = true; break; }
                }
                if (masked) continue;
                long s = 0;
                int n = 0;
                for (int y = y0; y < y0 + cell && y < img.getHeight(); y += 2)
                    for (int x = x0; x < x0 + cell && x < img.getWidth(); x += 2) {
                        int p = img.getRGB(x, y);
                        s += ((p >> 16) & 255) * 3 + ((p >> 8) & 255) * 6 + (p & 255);
                        n++;
                    }
                sig[cy * cw + cx] = n == 0 ? 0 : (int) (s / (n * 10));
            }
        }
        return sig;
    }

    /** تغيّر حقيقي (تمرير، رسالة جديدة): خلايا كثيرة تغيّرت، لا وميض المؤشر أو «يكتب…». */
    static boolean changed(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return true;
        int n = 0;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 6) n++;
        return n >= Math.max(6, a.length * 0.02);
    }

    static double scaleOf(Component c) {
        try {
            return c.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        } catch (Throwable t) {
            return 1;
        }
    }

    static String powershell() {
        String root = System.getenv("SystemRoot");
        if (root == null) root = "C:\\Windows";
        return root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    }

    // ===================================================================================
    // القرّاء
    // ===================================================================================

    static final class Reader {
        private Reader() {}

        /** in: داخل الإطار، bar: مكان شريط الإطار (للمعايرة على ويندوز)، كلاهما بإحداثيات جافا. */
        static Bubbles.Result read(Rectangle in, double scale, Rectangle bar) throws Exception {
            String f = fake();
            if (f != null) {
                List<Bubbles.Node> nodes = shift(Bubbles.parse(new String(Files.readAllBytes(new File(f).toPath()), StandardCharsets.UTF_8)), in.x, in.y);
                return Bubbles.build(nodes, new int[]{in.x, in.y, in.width, in.height});
            }
            if (WIN) return windows(in, scale, bar);
            if (MAC) return mac(in);
            throw new Exception("إطار الالتقاط متاح على ويندوز وماك فقط.");
        }

        static List<Bubbles.Node> shift(List<Bubbles.Node> l, int dx, int dy) {
            List<Bubbles.Node> o = new ArrayList<>();
            for (Bubbles.Node n : l) o.add(new Bubbles.Node(n.kind, n.x + dx, n.y + dy, n.w, n.h, n.text));
            return o;
        }

        /** 12.5: «مربع النسخ»: قراءة الصورة كلها ثم جمعها في رسالة واحدة. */
        static Bubbles.Result readBox(Rectangle in) throws Exception {
            List<Bubbles.Node> nodes = new ArrayList<>();
            String f = fake();
            if (f != null) nodes.addAll(shift(Bubbles.parse(new String(Files.readAllBytes(new File(f).toPath()), StandardCharsets.UTF_8)), in.x, in.y));
            else {
                BufferedImage img = grab(in);
                List<Bubbles.Node> l = WIN ? ocrWindows(img, in) : MAC ? visionLines(img, in) : null;
                if (l == null) throw new Exception(WIN ? "تعذّرت قراءة الصورة في ويندوز." : "إطار الالتقاط متاح على ويندوز وماك فقط.");
                nodes.addAll(l);
            }
            List<Bubbles.Node> lines = new ArrayList<>();
            for (Bubbles.Node n : nodes) if (n.kind == 'L' || n.kind == 'T' || n.kind == 'R') lines.add(new Bubbles.Node('L', n.x, n.y, n.w, n.h, n.text));
            lines.add(new Bubbles.Node('B', in.x, in.y, in.width, in.height, ""));
            return Bubbles.build(lines, new int[]{in.x, in.y, in.width, in.height});
        }

        /** 12.2: أشكال الفقاعات في الصورة بإحداثيات الشاشة (نوع B). */
        static List<Bubbles.Node> shapes(BufferedImage img, Rectangle in) {
            List<Bubbles.Node> o = new ArrayList<>();
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            double kx = in.width / (double) w, ky = in.height / (double) h;
            for (BubbleShapes.Box b : BubbleShapes.find(px, w, h, w / (double) Math.max(1, in.width)))
                o.add(new Bubbles.Node(b.media ? 'M' : 'B', in.x + (int) Math.round(b.x * kx), in.y + (int) Math.round(b.y * ky),
                        (int) Math.round(b.w * kx), (int) Math.round(b.h * ky), ""));
            return o;
        }

        // ---------------------------------- ويندوز ----------------------------------

        static String[] frameArgs(Rectangle in, Rectangle bar) {
            return new String[]{"-Ix", String.valueOf(in.x), "-Iy", String.valueOf(in.y), "-Iw", String.valueOf(in.width), "-Ih", String.valueOf(in.height),
                    "-Jx", String.valueOf(bar.x), "-Jy", String.valueOf(bar.y), "-Jw", String.valueOf(bar.width), "-Jh", String.valueOf(bar.height)};
        }

        static Bubbles.Result windows(Rectangle in, double scale, Rectangle bar) throws Exception {
            long pid = ProcessHandle.current().pid();
            List<String> args = new ArrayList<>(List.of("-SelfPid", String.valueOf(pid), "-Whole", "0"));
            args.addAll(List.of(frameArgs(in, bar)));
            String out = run(script("uia.ps1"), 40, args.toArray(new String[0]));
            List<Bubbles.Node> raw = Bubbles.parse(out);
            List<Bubbles.Node> nodes = toJava(raw, bar, scale);
            int[] fr = {in.x, in.y, in.width, in.height};
            Bubbles.Result r = Bubbles.build(nodes, fr);
            if (!r.bubbles.isEmpty()) return r;
            // لا نص من إمكانية الوصول: قراءة الصورة المدمجة في ويندوز، وتجميعها بأشكال الفقاعات
            BufferedImage img = grab(in);
            List<Bubbles.Node> ocr = ocrWindows(img, in);
            if (ocr == null) return r;
            List<Bubbles.Node> all = new ArrayList<>(ocr);
            all.addAll(shapes(img, in));
            for (Bubbles.Node n : nodes) if (n.kind == 'W' || n.kind == 'H') all.add(n);
            Bubbles.Result o = Bubbles.build(all, fr);
            if (o.chat.isEmpty()) o.chat = r.chat;
            return o;
        }

        /**
         * إحداثيات إمكانية الوصول (بكسل حقيقي) إلى إحداثيات جافا (قد تكون مقسومة على تكبير الشاشة):
         * شريط الإطار نفسه (F) يعطي التحويل الدقيق، وإلا نقسم على التكبير.
         */
        static List<Bubbles.Node> toJava(List<Bubbles.Node> raw, Rectangle bar, double scale) {
            Bubbles.Node self = null;
            for (Bubbles.Node n : raw) if (n.kind == 'F') self = n;
            double sx = 1 / Math.max(0.5, scale), sy = sx, ox = 0, oy = 0;
            if (self != null && self.w > 20 && self.h > 10 && bar != null) {
                sx = bar.width / (double) self.w;
                sy = bar.height / (double) self.h;
                ox = bar.x - self.x * sx;
                oy = bar.y - self.y * sy;
            }
            List<Bubbles.Node> o = new ArrayList<>();
            for (Bubbles.Node n : raw) {
                if (n.kind == 'F') continue;
                o.add(new Bubbles.Node(n.kind, (int) Math.round(n.x * sx + ox), (int) Math.round(n.y * sy + oy),
                        (int) Math.round(n.w * sx), (int) Math.round(n.h * sy), n.text));
            }
            return o;
        }

        static List<Bubbles.Node> ocrWindows(BufferedImage img, Rectangle in) {
            try {
                File png = File.createTempFile("sa-cap-", ".png");
                png.deleteOnExit();
                javax.imageio.ImageIO.write(img, "png", png);
                String out;
                try {
                    out = run(script("ocr.ps1"), 40, "-Path", png.getAbsolutePath());
                } finally {
                    png.delete();
                }
                List<Bubbles.Node> l = Bubbles.parse(out);
                double sx = in.width / (double) img.getWidth(), sy = in.height / (double) img.getHeight();
                List<Bubbles.Node> o = new ArrayList<>();
                for (Bubbles.Node n : l)
                    o.add(new Bubbles.Node('L', in.x + (int) (n.x * sx), in.y + (int) (n.y * sy), (int) (n.w * sx), (int) (n.h * sy), n.text));
                return o;
            } catch (Throwable t) {
                return null;
            }
        }

        // ---------------------------------- ماك ----------------------------------

        static Bubbles.Result mac(Rectangle in) throws Exception {
            BufferedImage img = grab(in);
            List<Bubbles.Node> nodes = visionLines(img, in);
            nodes.addAll(shapes(img, in));
            // النافذة = الإطار نفسه: اسم المحادثة يُقرأ إن كان رأسها داخل الإطار في أعلاه
            nodes.add(new Bubbles.Node('W', in.x, in.y, in.width, in.height, ""));
            return Bubbles.build(nodes, new int[]{in.x, in.y, in.width, in.height});
        }

        static List<Bubbles.Node> visionLines(BufferedImage img, Rectangle in) throws Exception {
            File png = File.createTempFile("sa-cap-", ".png");
            png.deleteOnExit();
            javax.imageio.ImageIO.write(img, "png", png);
            String out;
            try {
                out = run(script("vision.js"), 40, png.getAbsolutePath());
            } finally {
                png.delete();
            }
            // سطر لكل نص: x y w h (نسبية 0..1 من أسفل الصورة) ثم النص بـBase64
            List<Bubbles.Node> nodes = new ArrayList<>();
            for (String line : out.split("\n")) {
                String[] p = line.trim().split("\t");
                if (p.length < 5) continue;
                try {
                    double x = Double.parseDouble(p[0]), y = Double.parseDouble(p[1]), w = Double.parseDouble(p[2]), h = Double.parseDouble(p[3]);
                    String t = new String(java.util.Base64.getDecoder().decode(p[4]), StandardCharsets.UTF_8);
                    int jx = in.x + (int) Math.round(x * in.width), jw = (int) Math.round(w * in.width);
                    int jh = (int) Math.round(h * in.height), jy = in.y + (int) Math.round((1 - y - h) * in.height);
                    nodes.add(new Bubbles.Node('L', jx, jy, jw, jh, t));
                } catch (Exception ignored) {
                    // سطر تالف
                }
            }
            return nodes;
        }

        /** صورة ما تحت الإطار بأعلى دقة متاحة (شاشات Retina وتكبير ويندوز). */
        static BufferedImage grab(Rectangle in) throws Exception {
            Robot r = new Robot();
            Image best = null;
            try {
                java.awt.image.MultiResolutionImage mr = r.createMultiResolutionScreenCapture(in);
                for (Image v : mr.getResolutionVariants()) if (best == null || v.getWidth(null) > best.getWidth(null)) best = v;
            } catch (Throwable t) {
                best = null;
            }
            if (best == null) return r.createScreenCapture(in);
            BufferedImage b = new BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            g.drawImage(best, 0, 0, null);
            g.dispose();
            return b;
        }

        /** نافذة ظاهرة على الشاشة بإحداثيات جافا مع اسم برنامجها/عنوانها. */
        static final class Shown {
            final Rectangle r;
            final String name;

            Shown(Rectangle r, String name) {
                this.r = r;
                this.name = name == null ? "" : name;
            }
        }

        /** كل النوافذ الظاهرة من الأمام للخلف (عدا نوافذ البرنامج نفسه). */
        static List<Shown> windowsList(double scale, Rectangle bar) throws Exception {
            List<Shown> l = new ArrayList<>();
            long pid = ProcessHandle.current().pid();
            if (fake() != null) {
                Rectangle sc = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                l.add(new Shown(new Rectangle(sc.x + 40, sc.y + 80, Math.min(900, sc.width - 80), Math.min(640, sc.height - 120)), "WhatsApp"));
                return l;
            }
            if (WIN) {
                List<String> args = new ArrayList<>(List.of("-SelfPid", String.valueOf(pid), "-List", "1"));
                args.addAll(List.of(frameArgs(new Rectangle(0, 0, 0, 0), bar)));
                for (Bubbles.Node n : toJava(Bubbles.parse(run(script("uia.ps1"), 30, args.toArray(new String[0]))), bar, scale))
                    if (n.kind == 'W') l.add(new Shown(new Rectangle(n.x, n.y, n.w, n.h), n.text));
            } else if (MAC) {
                for (String line : run(script("macwins.js"), 15).split("\n")) {
                    String[] p = line.trim().split("\t", -1);
                    if (p.length < 6) continue;
                    try {
                        if (Long.parseLong(p[0].trim()) == pid) continue;
                        Rectangle r = new Rectangle(Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()), Integer.parseInt(p[4].trim()));
                        if (r.width < 80 || r.height < 80) continue;
                        String name = p[5].isEmpty() ? "" : new String(java.util.Base64.getDecoder().decode(p[5].trim()), StandardCharsets.UTF_8);
                        l.add(new Shown(r, name));
                    } catch (Exception ignored) {
                        // سطر تالف
                    }
                }
            }
            return l;
        }

        /** «حدد النافذة» بكبسة: أعلى نافذة تحت النقطة. */
        static Rectangle windowAt(Point p, double scale, Rectangle bar) throws Exception {
            for (Shown s : windowsList(scale, bar)) if (s.r.contains(p)) return s.r;
            return null;
        }

        /** نافذة واتساب (أو أول نافذة فيها WhatsApp في اسمها) بإحداثيات جافا، أو null. */
        static Rectangle whatsappWindow(double scale, Rectangle bar) throws Exception {
            for (Shown s : windowsList(scale, bar))
                if (s.name.matches("(?is).*(whatsapp|واتساب).*")) return s.r;
            return null;
        }

        /** ينسخ سكربت القراءة من داخل البرنامج إلى ملف مؤقت. */
        static File script(String name) throws Exception {
            try (InputStream in = DeskCapture.class.getResourceAsStream("/res/capture/" + name)) {
                if (in == null) throw new Exception("سكربت القراءة غير موجود: " + name);
                String ext = name.substring(name.lastIndexOf('.'));
                File f = File.createTempFile("sa-cap-", ext);
                f.deleteOnExit();
                byte[] b = in.readAllBytes();
                // PowerShell 5 يقرأ الملف بلا BOM كترميز النظام: نضيف BOM ليُقرأ UTF-8
                if (ext.equals(".ps1")) {
                    byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                    byte[] all = new byte[b.length + 3];
                    System.arraycopy(bom, 0, all, 0, 3);
                    System.arraycopy(b, 0, all, 3, b.length);
                    b = all;
                }
                Files.write(f.toPath(), b);
                return f;
            }
        }

        static String run(File script, int seconds, String... args) throws Exception {
            List<String> cmd = new ArrayList<>();
            if (script.getName().endsWith(".ps1")) {
                cmd.add(powershell());
                cmd.add("-NoProfile");
                cmd.add("-NonInteractive");
                cmd.add("-ExecutionPolicy");
                cmd.add("Bypass");
                cmd.add("-File");
                cmd.add(script.getAbsolutePath());
            } else {
                cmd.add("/usr/bin/osascript");
                cmd.add("-l");
                cmd.add("JavaScript");
                cmd.add(script.getAbsolutePath());
            }
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            p.getOutputStream().close();
            final byte[][] buf = new byte[1][];
            Thread t = new Thread(() -> {
                try {
                    buf[0] = p.getInputStream().readAllBytes();
                } catch (Exception e) {
                    buf[0] = new byte[0];
                }
            });
            t.start();
            Thread drain = new Thread(() -> {
                try {
                    p.getErrorStream().readAllBytes();
                } catch (Exception ignored) {
                    // لا شيء
                }
            });
            drain.start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new Exception("القراءة أخذت وقتًا طويلًا. صغّر الإطار وحاول مرة أخرى.");
            }
            t.join(2000);
            try {
                script.delete();
            } catch (Exception ignored) {
                // لا شيء
            }
            return buf[0] == null ? "" : new String(buf[0], StandardCharsets.UTF_8);
        }
    }
}
