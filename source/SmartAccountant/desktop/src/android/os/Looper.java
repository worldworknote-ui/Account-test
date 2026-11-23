package android.os;

/** الخيط الرئيسي لأندرويد = خيط أحداث Swing. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private Looper() {}
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return javax.swing.SwingUtilities.isEventDispatchThread() ? MAIN : null; }
    public static void prepare() {}
    public static void loop() {}
    public void quit() {}
    public void quitSafely() {}
    public Thread getThread() { return null; }
    public boolean isCurrentThread() { return javax.swing.SwingUtilities.isEventDispatchThread(); }
}
