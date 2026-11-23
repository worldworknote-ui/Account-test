package com.smart.hawala.desk;

import android.app.Activity;
import android.app.Platform;
import android.app.WindowStack;
import android.content.Context;
import android.view.ViewRootImpl;
import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/**
 * 11.0: نافذة البرنامج على الماك والويندوز = شاشات الهاتف نفسها.
 * <p>
 * الحجم (desk_column):
 * <ul>
 * <li>auto (الافتراضي): الشاشة تملأ النافذة؛ الحجم يصغر مع النافذة الصغيرة ولا يكبر فوق الحجم الطبيعي (11.6)،
 * والأزرار والخانات بعرض أقصى في الشاشة العريضة.</li>
 * <li>480 / 600: عمود ثابت وسط النافذة؛ full: بعرض النافذة كلها. في هذه الثلاثة الحجم ثابت.</li>
 * </ul>
 * desk_zoom (70…170٪) يكبّر أو يصغّر فوق ذلك. التكبير مقياس رسم عام (لا إعادة بناء للشاشات) فيبقى السحب سلسًا والنص حادًا.
 */
public final class PhoneWindow {
    private PhoneWindow() {}

    static JFrame frame;
    static DeskActivity activity;
    private static JPanel shell;
    /** مرجع الحجم التلقائي: شاشة هاتف منطقية بهذا العرض والارتفاع تملأ النافذة. */
    static final float REF_W = 430f, REF_H = 860f;
    /** أعرض ما تصل إليه الشاشة (بوحدات الهاتف) في الوضع التلقائي قبل أن تتوسط النافذة. */
    static final float MAX_W = 900f;
    /** 11.2: أقل ارتفاع منطقي ظاهر في الوضع التلقائي (التكبير لا يتجاوزه). */
    static final float FIT_H = 800f;

    public static boolean isOpen() { return frame != null; }

    public static void open(Context ctx, Db db) {
        applyMetrics();
        android.hardware.biometrics.SystemAuth.warmUp();
        frame = new JFrame("المحاسب الذكي");
        frame.setIconImages(java.util.Arrays.asList(Main.appIcon(16), Main.appIcon(32), Main.appIcon(64), Main.appIcon(128), Main.appIcon(256)));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Platform.frame = frame;
        Platform.onFinish = () -> { };
        Platform.onRecreate = a -> { if (a instanceof DeskActivity) activity = (DeskActivity) a; if (shell != null) shell.repaint(); };
        Activity.onBarsChanged = () -> { if (shell != null) shell.repaint(); };
        DeskLook.install();
        // 11.5: السحب بإصبعين وزر الرجوع في الفأرة = رجوع للصفحة السابقة
        ViewRootImpl.swipeBack = () -> { if (activity != null) activity.onBackPressed(); };
        android.app.NotificationManager.onOpen = i -> { if (activity != null) activity.deliverNewIntent(i); };
        shell = new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                int c = activity == null ? 0xFFF3F5F4 : activity.bgColor();
                g.setColor(new Color(c));
                g.fillRect(0, 0, getWidth(), getHeight());
                Rectangle b = WindowStack.host().getBounds();
                if (b.x > 0) {
                    // حد خفيف حول الشاشة حين تتوسط النافذة
                    boolean night = activity != null && activity.isNight();
                    g.setColor(night ? new Color(255, 255, 255, 18) : new Color(0, 0, 0, 16));
                    g.drawLine(b.x - 1, 0, b.x - 1, getHeight());
                    g.drawLine(b.x + b.width, 0, b.x + b.width, getHeight());
                }
            }
            @Override public void doLayout() { layout(); }
        };
        // سحب طرف النافذة: المقياس والتخطيط يتبعان الحجم فورًا
        shell.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { layout(); }
        });
        shell.add(WindowStack.host());
        frame.setContentPane(shell);
        frame.setMinimumSize(new Dimension(340, 480));
        if (!restoreBounds(db)) {
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
            String col = column(db);
            int h = Math.min(scr.height - 60, 1000);
            int w;
            if ("full".equals(col)) w = Math.min(scr.width - 80, 1200);
            else if ("auto".equals(col)) w = Math.min(scr.width - 80, Math.round(h * REF_W / REF_H) + 40);
            else w = Math.min(scr.width - 80, Math.round(Integer.parseInt(col) * zoom(db)) + 160);
            frame.setSize(w, h);
            frame.setLocationRelativeTo(null);
        }
        hookUsage();
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
            @Override public void windowIconified(WindowEvent e) { if (activity != null) activity.pauseFromWindow(); }
            @Override public void windowDeiconified(WindowEvent e) {
                if (activity != null) activity.resumeFromWindow();
                wake();
            }
            @Override public void windowActivated(WindowEvent e) { wake(); }
        });
        Timer save = new Timer(700, e -> persistBounds());
        save.setRepeats(false);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { save.restart(); }
            @Override public void componentMoved(ComponentEvent e) { save.restart(); }
        });
        frame.setVisible(true);
        layout();
        activity = Activity.launch(DeskActivity.class, null);
    }

    /** للتجارب: حدود النافذة على الشاشة. */
    public static Rectangle frameBounds() { return frame.getBounds(); }
    public static JFrame frame() { return frame; }
    public static DeskActivity activityForTests() { return activity; }

    // 12.7: وقت العمل: أي فأرة أو لوحة مفاتيح = نشاط؛ وحين لا تكون أي نافذة للبرنامج في المقدمة تتوقف الجلسة
    private static boolean usageHooked, away;
    private static final javax.swing.Timer focusCheck = new javax.swing.Timer(400, e -> checkFocus());

    private static void hookUsage() {
        if (usageHooked) return;
        usageHooked = true;
        focusCheck.setRepeats(false);
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            int id = ev.getID();
            if (id == WindowEvent.WINDOW_ACTIVATED || id == WindowEvent.WINDOW_DEACTIVATED || id == WindowEvent.WINDOW_LOST_FOCUS || id == WindowEvent.WINDOW_GAINED_FOCUS) {
                focusCheck.restart();
                return;
            }
            if (id == java.awt.event.MouseEvent.MOUSE_PRESSED || id == java.awt.event.MouseEvent.MOUSE_WHEEL || id == java.awt.event.KeyEvent.KEY_PRESSED
                    || id == java.awt.event.MouseEvent.MOUSE_MOVED) {
                if (activity != null) activity.onUserInteraction();
            }
        }, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    private static void checkFocus() {
        if (activity == null) return;
        boolean front = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null;
        if (!front && !away) {
            away = true;
            activity.usageAway();
        } else if (front && away) {
            away = false;
            activity.usageBack();
        }
    }

    static String column(Db db) {
        String c = db == null ? "auto" : db.setting("desk_column", "auto");
        return "480".equals(c) || "600".equals(c) || "full".equals(c) ? c : "auto";
    }

    public static float zoom(Db db) {
        try { return Math.max(0.7f, Math.min(1.7f, Integer.parseInt(db.setting("desk_zoom", "100")) / 100f)); } catch (Exception e) { return 1f; }
    }

    /** الكثافة ثابتة 1 (وحدات الهاتف)؛ التكبير كله من مقياس الرسم العام. */
    static void applyMetrics() {
        android.util.DisplayMetrics m = Context.RES.getDisplayMetrics();
        m.density = 1f;
        m.scaledDensity = 1f;
        m.densityDpi = 160;
        Context.RES.getConfiguration().densityDpi = 160;
        android.graphics.drawable.VectorDrawable.density = 1f;
    }

    /** بعد تغيير الحجم أو التكبير من الإعدادات أو المعالج. */
    public static void refresh() {
        if (shell == null) return;
        SwingUtilities.invokeLater(PhoneWindow::layout);
    }

    /** المقياس وعرض الشاشة (بنقاط النافذة) لحجم نافذة معيّن. */
    static float[] metrics(Db db, int W, int H) {
        String col = column(db);
        float z = zoom(db);
        float s;
        int cw;
        if ("auto".equals(col)) {
            // الشاشة تملأ النافذة كلها (كما كانت). 11.6: المقياس لا يكبر فوق حجم الهاتف الطبيعي (كانت الأزرار والخانات
            // تكبر حتى 4 أضعاف مع النافذة)؛ والأزرار والخانات نفسها بعرض أقصى في الشاشة العريضة (Ui.capWide).
            float area = (float) Math.sqrt((W / REF_W) * (H / REF_H));
            s = Math.max(0.6f, Math.min(1.7f, Math.max(0.75f, Math.min(1f, Math.min(area, H / FIT_H))) * z));
            cw = W;
        } else {
            s = z;
            cw = "full".equals(col) ? W : Math.min(W, Math.round(Integer.parseInt(col) * s));
        }
        return new float[]{s, cw};
    }

    /**
     * 11.5: بعد العودة إلى النافذة (من التصغير أو من برنامج آخر) يُعاد قياس الشاشة ورسمها كاملة مرتين:
     * في ويندوز قد تصل العودة قبل أن تستعيد النافذة حجمها، فتبقى الشاشة (مثل شاشة القفل) بعنوانها فقط.
     */
    static void wake() {
        Runnable r = () -> {
            layout();
            for (android.view.ViewRootImpl root : WindowStack.roots()) {
                android.view.View v = root.getView();
                if (v == null) continue;
                v.requestLayout();
                v.invalidate();
            }
            if (shell != null) shell.repaint();
        };
        SwingUtilities.invokeLater(r);
        Timer again = new Timer(350, e -> r.run());
        again.setRepeats(false);
        again.start();
    }

    static void layout() {
        if (shell == null) return;
        int W = shell.getWidth(), H = shell.getHeight();
        if (W <= 0 || H <= 0) return;
        float[] m = metrics(SmartApp.db, W, H);
        int cw = (int) m[1];
        WindowStack.rescale(m[0]);
        Rectangle want = new Rectangle((W - cw) / 2, 0, cw, H);
        JLayeredPane host = WindowStack.host();
        if (!host.getBounds().equals(want)) host.setBounds(want);
        shell.repaint();
    }

    // ------------------------------------------------------------------ مكان النافذة وحجمها
    static void persistBounds() {
        Db db = SmartApp.db;
        if (frame == null || db == null || (frame.getExtendedState() & Frame.ICONIFIED) != 0) return;
        try {
            boolean max = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
            Rectangle b = frame.getBounds();
            db.setSettingQuiet("desk_phone_bounds", b.x + "," + b.y + "," + b.width + "," + b.height + "," + (max ? 1 : 0));
        } catch (Throwable ignored) {
            // غير مهم
        }
    }

    static boolean restoreBounds(Db db) {
        try {
            String[] p = db.setting("desk_phone_bounds", "").split(",");
            if (p.length < 4) return false;
            Rectangle b = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            // يجب أن يقع جزء معقول منها على شاشة موجودة
            boolean onScreen = false;
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle s = d.getDefaultConfiguration().getBounds();
                Rectangle i = s.intersection(b);
                if (i.width >= 200 && i.height >= 200) onScreen = true;
            }
            if (!onScreen || b.width < 340 || b.height < 480) return false;
            frame.setBounds(b);
            if (p.length > 4 && "1".equals(p[4])) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 11.0: إغلاق شكل الهاتف وحده (التبديل إلى الشكل القديم): البيانات تبقى مفتوحة. */
    static void close() {
        persistBounds();
        try {
            if (activity != null) activity.destroyFromWindow();
        } catch (Throwable t) {
            Main.log(t);
        }
        try { new android.app.job.JobScheduler(null).cancelAll(); } catch (Throwable ignored) { /* لا مهام */ }
        // أي نافذة حوار أو إشعار بقي معلّقًا
        for (android.view.ViewRootImpl r : WindowStack.roots()) {
            try { WindowStack.remove(r); } catch (Throwable ignored) { /* تُترك */ }
        }
        activity = null;
        if (frame != null) {
            frame.getContentPane().remove(WindowStack.host());
            frame.dispose();
        }
        frame = null;
        shell = null;
        Platform.frame = null;
    }

    static void quit() {
        persistBounds();
        try {
            if (activity != null) activity.destroyFromWindow();
        } catch (Throwable t) {
            Main.log(t);
        }
        try {
            if (SmartApp.db != null) SmartApp.db.close();
        } catch (Throwable ignored) {
            // الخروج على أي حال
        }
        System.exit(0);
    }
}
