package android.text;
public class Html {
    public static final int FROM_HTML_MODE_LEGACY = 0, FROM_HTML_MODE_COMPACT = 63;
    public static Spanned fromHtml(String s) { return fromHtml(s, 0); }
    public static Spanned fromHtml(String s, int flags) {
        String t = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n").replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        return new SpannableString(t);
    }
    public static String escapeHtml(CharSequence s) { return TextUtils.htmlEncode(s.toString()); }
}
