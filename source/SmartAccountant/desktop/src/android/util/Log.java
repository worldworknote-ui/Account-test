package android.util;
public final class Log {
    public static boolean on = Boolean.getBoolean("sa.log");
    public static int d(String t, String m) { if (on) System.err.println("D/" + t + ": " + m); return 0; }
    public static int i(String t, String m) { if (on) System.err.println("I/" + t + ": " + m); return 0; }
    public static int v(String t, String m) { return 0; }
    public static int w(String t, String m) { if (on) System.err.println("W/" + t + ": " + m); return 0; }
    public static int w(String t, String m, Throwable e) { return w(t, m + " " + e); }
    public static int w(String t, Throwable e) { return w(t, String.valueOf(e)); }
    public static int e(String t, String m) { System.err.println("E/" + t + ": " + m); return 0; }
    public static int e(String t, String m, Throwable e) { System.err.println("E/" + t + ": " + m + " " + e); return 0; }
    public static String getStackTraceString(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }
}
