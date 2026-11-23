package android.text;

public class TextUtils {
    public enum TruncateAt { START, MIDDLE, END, MARQUEE, END_SMALL }
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
    public static boolean equals(CharSequence a, CharSequence b) { if (a == b) return true; if (a == null || b == null) return false; return a.toString().equals(b.toString()); }
    public static String join(CharSequence d, Iterable<?> items) {
        StringBuilder b = new StringBuilder();
        for (Object o : items) { if (b.length() > 0) b.append(d); b.append(o); }
        return b.toString();
    }
    public static String join(CharSequence d, Object[] items) { return join(d, java.util.Arrays.asList(items)); }
    public static String[] split(String s, String re) { return s.isEmpty() ? new String[0] : s.split(re, -1); }
    public static boolean isDigitsOnly(CharSequence s) { for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false; return true; }
    public static boolean isGraphic(CharSequence s) { for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) return true; return false; }
    public static String htmlEncode(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    public static CharSequence concat(CharSequence... t) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (CharSequence c : t) b.append(c);
        return b;
    }
    public static int getLayoutDirectionFromLocale(java.util.Locale l) { return l != null && ("ar".equals(l.getLanguage()) || "fa".equals(l.getLanguage()) || "he".equals(l.getLanguage()) || "ur".equals(l.getLanguage())) ? 1 : 0; }
    public static CharSequence ellipsize(CharSequence t, TextPaint p, float avail, TruncateAt where) {
        String s = t.toString();
        if (p.measureText(s) <= avail) return t;
        String e = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + e) <= avail) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + e;
    }
    public static String substring(CharSequence s, int a, int b) { return s.toString().substring(a, b); }
    public static int indexOf(CharSequence s, char c) { return s.toString().indexOf(c); }
    public static CharSequence stringOrSpannedString(CharSequence s) { return s; }
}
