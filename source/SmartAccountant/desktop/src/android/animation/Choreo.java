package android.animation;

import java.util.ArrayList;
import java.util.List;

/** ساعة الحركات: إطار كل 16ms على خيط الواجهة ما دامت هناك حركة جارية. */
public final class Choreo {
    private Choreo() {}
    private static final List<ValueAnimator> active = new ArrayList<>();
    private static javax.swing.Timer timer;
    /** يُستدعى بعد كل إطار (لإعادة رسم النوافذ). */
    public static Runnable afterFrame;
    /** لتجارب اللقطات: تنهي الحركات فورًا. */
    public static volatile boolean instant = Boolean.getBoolean("sa.noanim");

    static void add(ValueAnimator a) {
        if (!active.contains(a)) active.add(a);
        if (timer == null) {
            timer = new javax.swing.Timer(15, e -> frame());
            timer.setCoalesce(true);
        }
        if (!timer.isRunning()) timer.start();
    }
    static void remove(ValueAnimator a) { active.remove(a); }
    public static boolean busy() { return !active.isEmpty(); }

    static void frame() {
        long now = System.nanoTime();
        for (ValueAnimator a : new ArrayList<>(active)) {
            try { a.tick(now); } catch (Throwable t) { active.remove(a); Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t); }
        }
        if (afterFrame != null) afterFrame.run();
        if (active.isEmpty() && timer != null) timer.stop();
    }
}
