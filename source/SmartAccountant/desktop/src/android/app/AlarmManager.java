package android.app;
public class AlarmManager {
    public static final int RTC_WAKEUP = 0, RTC = 1, ELAPSED_REALTIME_WAKEUP = 2, ELAPSED_REALTIME = 3;
    public static final long INTERVAL_DAY = 86400000L, INTERVAL_HOUR = 3600000L, INTERVAL_FIFTEEN_MINUTES = 900000L;
    public void set(int t, long at, PendingIntent p) {}
    public void setExact(int t, long at, PendingIntent p) {}
    public void setExactAndAllowWhileIdle(int t, long at, PendingIntent p) {}
    public void setAndAllowWhileIdle(int t, long at, PendingIntent p) {}
    public void setInexactRepeating(int t, long at, long every, PendingIntent p) {}
    public void setRepeating(int t, long at, long every, PendingIntent p) {}
    public void cancel(PendingIntent p) {}
    public boolean canScheduleExactAlarms() { return true; }
}
