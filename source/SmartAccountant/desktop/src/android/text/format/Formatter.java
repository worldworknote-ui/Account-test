package android.text.format;
public class Formatter {
    public static String formatFileSize(android.content.Context c, long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }
    public static String formatShortFileSize(android.content.Context c, long b) { return formatFileSize(c, b); }
}
