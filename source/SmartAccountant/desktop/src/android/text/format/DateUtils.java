package android.text.format;
public class DateUtils {
    public static final long SECOND_IN_MILLIS = 1000, MINUTE_IN_MILLIS = 60000, HOUR_IN_MILLIS = 3600000, DAY_IN_MILLIS = 86400000, WEEK_IN_MILLIS = 604800000;
    public static final int FORMAT_SHOW_TIME = 1, FORMAT_SHOW_DATE = 16, FORMAT_NUMERIC_DATE = 0x20000, FORMAT_SHOW_YEAR = 4, FORMAT_ABBREV_ALL = 0x80000, FORMAT_ABBREV_RELATIVE = 0x40000;
    public static boolean isToday(long t) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(t);
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }
    public static CharSequence getRelativeTimeSpanString(long t) { return formatDateTime(null, t, 0); }
    public static CharSequence getRelativeTimeSpanString(long t, long now, long min) { return formatDateTime(null, t, 0); }
    public static String formatDateTime(android.content.Context c, long t, int flags) { return new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(new java.util.Date(t)); }
    public static String formatElapsedTime(long s) { return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60); }
}
