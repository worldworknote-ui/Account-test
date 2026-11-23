package android.text.format;
public class DateFormat {
    public static boolean is24HourFormat(android.content.Context c) { return true; }
    public static CharSequence format(CharSequence fmt, long t) { return new java.text.SimpleDateFormat(fmt.toString(), java.util.Locale.US).format(new java.util.Date(t)); }
    public static CharSequence format(CharSequence fmt, java.util.Date d) { return format(fmt, d.getTime()); }
    public static java.text.DateFormat getDateFormat(android.content.Context c) { return new java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US); }
    public static java.text.DateFormat getTimeFormat(android.content.Context c) { return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US); }
}
