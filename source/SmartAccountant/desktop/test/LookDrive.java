package com.smart.hawala.desk;

import android.app.WindowStack;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.widget.TextView;
import com.smart.hawala.SmartApp;
import java.awt.*;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * 11.0: تجربة الشكل على شاشة افتراضية (Xvfb) بالبرنامج الحقيقي ومجلد بيانات مؤقت فارغ:
 * معالج أول تشغيل، تغيير حجم النافذة (المقياس يتبعها)، خيارات المعالج، التبديل إلى الشكل القديم والعودة، والكتابة مع التكبير.
 * الاستعمال: java -Dsa.home=<مجلد مؤقت> ... com.smart.hawala.desk.LookDrive <مجلد اللقطات>
 */
public final class LookDrive {
    static File out;
    static final List<String> fails = new ArrayList<>();
    static Robot robot;

    public static void main(String[] a) throws Exception {
        out = new File(a[0]);
        out.mkdirs();
        String home = System.getProperty("sa.home");
        if (home == null || !home.contains("tmp")) throw new IllegalStateException("sa.home يجب أن يكون مجلدًا مؤقتًا");
        robot = new Robot();
        Main.main(new String[0]);
        sleep(6000);
        check(PhoneWindow.frame != null && PhoneWindow.frame.isShowing(), "phone window shown");
        check(LookWizard.dlg != null && LookWizard.dlg.isShowing(), "wizard shown on first run");
        shot("01-first-run");

        // سحب طرف النافذة: المقياس يتبع الحجم
        float s0 = ViewRootImpl.scale();
        resize(760, 1000);
        float s1 = ViewRootImpl.scale();
        shot("02-bigger");
        resize(380, 640);
        float s2 = ViewRootImpl.scale();
        shot("03-smaller");
        System.out.println("scale: start " + s0 + " big " + s1 + " small " + s2);
        // 11.6: يصغر مع النافذة الصغيرة ولا يكبر فوق الحجم الطبيعي (×الحجم المختار)
        check(s1 > s2 + 0.15f, "scale follows window size (smaller window → smaller)");
        resize(1600, 1100);
        float s3 = ViewRootImpl.scale();
        check(s3 <= s1 + 0.01f && s3 <= 1.0f * PhoneWindow.zoom(SmartApp.db) + 0.01f, "big window: no growth above natural size (" + s3 + ")");
        resize(760, 1000);
        resize(520, 900);

        click("كامل العرض");
        sleep(800);
        check("full".equals(SmartApp.db.setting("desk_column", "")), "column full saved");
        resize(1100, 800);
        shot("04-full-width");
        click("تلقائي مع النافذة");
        resize(520, 900);

        SwingUtilities.invokeAndWait(() -> {
            JSlider s = find(LookWizard.dlg, JSlider.class);
            s.setValue(130);
        });
        sleep(800);
        float sz = ViewRootImpl.scale();
        System.out.println("scale at 130%: " + sz);
        shot("05-zoom-130");
        SwingUtilities.invokeAndWait(() -> find(LookWizard.dlg, JSlider.class).setValue(100));

        click("ليلي");
        sleep(2500);
        shot("06-night");
        check("dark".equals(SmartApp.db.setting("theme_mode", "")), "night saved");
        click("فاتح");
        sleep(2500);

        click("الشكل القديم");
        sleep(4000);
        check(App.frame != null && App.frame.isShowing() && PhoneWindow.frame == null, "switched to classic");
        shot("07-classic");
        click("مثل الهاتف (جديد)");
        sleep(4000);
        check(PhoneWindow.frame != null && PhoneWindow.frame.isShowing() && App.frame == null, "back to phone");
        shot("08-phone-again");

        // الكتابة في خانة البحث مع تكبير 130٪
        SwingUtilities.invokeAndWait(() -> find(LookWizard.dlg, JSlider.class).setValue(130));
        sleep(1000);
        click("ابدأ العمل");
        sleep(800);
        check(LookWizard.dlg == null, "wizard closed");
        check("done".equals(SmartApp.db.setting("desk_wizard", "")), "wizard marked done");
        Point p = hintPoint("ابحث");
        check(p != null, "search field found");
        if (p != null) {
            robot.mouseMove(p.x, p.y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            sleep(700);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection("الأمل"), null);
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
            robot.keyPress(java.awt.event.KeyEvent.VK_V);
            robot.keyRelease(java.awt.event.KeyEvent.VK_V);
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            sleep(1200);
            shot("09-typing-scaled");
            // الخانة الحقيقية فوق مكانها بالضبط
            JTextField f = find(PhoneWindow.frame, JTextField.class);
            check(f != null && f.isShowing() && "الأمل".equals(f.getText()), "typed into scaled field");
            if (f != null && f.isShowing()) {
                Point fp = f.getLocationOnScreen();
                System.out.println("field at " + fp + " size " + f.getSize() + " click " + p + " font " + f.getFont().getSize2D());
                check(fp.y <= p.y && fp.y + f.getHeight() >= p.y, "field covers the box");
            }
        }

        // إعادة التشغيل: المعالج لا يظهر مرة أخرى (يُفحص بالإعداد)
        System.out.println(fails.isEmpty() ? "LOOK OK" : "LOOK FAILURES: " + fails.size());
        for (String f : fails) System.out.println("  " + f);
        System.exit(fails.isEmpty() ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails.add(what);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static void resize(int w, int h) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PhoneWindow.frame.setExtendedState(Frame.NORMAL);
            PhoneWindow.frame.setSize(w, h);
        });
        sleep(1200);
    }

    static void shot(String name) throws Exception {
        Rectangle r = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        ImageIO.write(robot.createScreenCapture(r), "png", new File(out, name + ".png"));
    }

    static void click(String label) throws Exception {
        final AbstractButton[] b = {null};
        SwingUtilities.invokeAndWait(() -> {
            if (LookWizard.dlg == null) return;
            for (AbstractButton x : all(LookWizard.dlg.getContentPane(), AbstractButton.class)) if (label.equals(x.getText())) b[0] = x;
        });
        if (b[0] == null) { check(false, "button " + label); return; }
        Point p = b[0].getLocationOnScreen();
        robot.mouseMove(p.x + b[0].getWidth() / 2, p.y + b[0].getHeight() / 2);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        sleep(900);
    }

    static <T> T find(Container c, Class<T> k) {
        List<T> l = all(c, k);
        return l.isEmpty() ? null : l.get(0);
    }

    static <T> List<T> all(Component c, Class<T> k) {
        List<T> o = new ArrayList<>();
        if (k.isInstance(c)) o.add(k.cast(c));
        if (c instanceof Container) for (Component x : ((Container) c).getComponents()) o.addAll(all(x, k));
        if (c instanceof Window) for (Window w : ((Window) c).getOwnedWindows()) o.addAll(all(w, k));
        return o;
    }

    /** مركز خانة كتابة تبدأ عبارتها الإرشادية بالنص المعطى، بإحداثيات الشاشة. */
    static Point hintPoint(String hint) throws Exception {
        final Point[] r = {null};
        SwingUtilities.invokeAndWait(() -> {
            ViewRootImpl root = null;
            View v = null;
            for (ViewRootImpl x : WindowStack.roots()) {
                View f = findHint(x.getView(), hint);
                if (f != null) { root = x; v = f; }
            }
            if (v == null) { System.out.println("roots: " + WindowStack.roots().size()); return; }
            float[] o = {v.getWidth() / 2f, v.getHeight() / 2f};
            v.mapToRoot(o);
            Point s = root.pane.getLocationOnScreen();
            float sc = ViewRootImpl.scale();
            r[0] = new Point(s.x + Math.round(o[0] * sc), s.y + Math.round(o[1] * sc));
        });
        return r[0];
    }

    static View findHint(View v, String hint) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView && ((TextView) v).getHint() != null && ((TextView) v).getHint().toString().startsWith(hint)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findHint(g.getChildAt(i), hint);
                if (f != null) return f;
            }
        }
        return null;
    }
}
