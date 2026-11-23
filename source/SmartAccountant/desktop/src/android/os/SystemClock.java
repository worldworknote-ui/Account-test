package android.os;
public final class SystemClock {
    private static final long T0 = System.nanoTime();
    public static long elapsedRealtime() { return (System.nanoTime() - T0) / 1_000_000L + 10_000L; }
    public static long elapsedRealtimeNanos() { return System.nanoTime() - T0 + 10_000_000_000L; }
    public static long uptimeMillis() { return elapsedRealtime(); }
    public static long currentThreadTimeMillis() { return elapsedRealtime(); }
    public static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
}
