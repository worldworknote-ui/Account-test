package android.content;
public class ClipDescription {
    public static final String MIMETYPE_TEXT_PLAIN = "text/plain", MIMETYPE_TEXT_HTML = "text/html", MIMETYPE_TEXT_URILIST = "text/uri-list", EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private final CharSequence label; private final String[] mime; private android.os.PersistableBundle extras;
    public ClipDescription(CharSequence label, String[] mime) { this.label = label; this.mime = mime; }
    private final long ts = java.lang.System.currentTimeMillis();
    public long getTimestamp() { return ts; }
    public boolean hasMimeType(String m) { for (String x : mime) if (x.equals(m) || ("text/*".equals(m) && x.startsWith("text/"))) return true; return false; }
    public CharSequence getLabel() { return label; }
    public int getMimeTypeCount() { return mime.length; }
    public String getMimeType(int i) { return mime[i]; }
    public void setExtras(android.os.PersistableBundle b) { extras = b; }
    public android.os.PersistableBundle getExtras() { return extras; }
}
