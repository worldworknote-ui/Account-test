package android.net;

import java.io.File;

/** عنوان ملف أو رابط. على الكمبيوتر الملفات بعناوين file:// والمجلدات (بدل أشجار SAF) كذلك. */
public final class Uri implements android.os.Parcelable, Comparable<Uri> {
    public static final Uri EMPTY = new Uri("");
    private final String s;
    private Uri(String s) { this.s = s; }
    public static Uri parse(String s) { return new Uri(s == null ? "" : s); }
    public static Uri fromFile(File f) { return new Uri(f.toURI().toString().replace("file:/", "file:///").replace("file:////", "file:///")); }
    public static Uri withAppendedPath(Uri base, String seg) { return new Uri(base.s.endsWith("/") ? base.s + encode(seg) : base.s + "/" + encode(seg)); }
    public static String encode(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; } }
    public static String encode(String s, String allow) { return encode(s); }
    public static String decode(String s) { try { return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; } }
    public String getScheme() { int i = s.indexOf(':'); return i < 0 ? null : s.substring(0, i); }
    public boolean isRelative() { return getScheme() == null; }
    public boolean isAbsolute() { return !isRelative(); }
    public String getPath() {
        try {
            if (s.startsWith("file:")) return file().getPath();
            java.net.URI u = new java.net.URI(s);
            return u.getPath();
        } catch (Exception e) {
            return s;
        }
    }
    public String getEncodedPath() { return getPath(); }
    public String getHost() { try { return new java.net.URI(s).getHost(); } catch (Exception e) { return null; } }
    public String getAuthority() { try { return new java.net.URI(s).getAuthority(); } catch (Exception e) { return null; } }
    public int getPort() { try { return new java.net.URI(s).getPort(); } catch (Exception e) { return -1; } }
    public String getQuery() { int i = s.indexOf('?'); return i < 0 ? null : s.substring(i + 1); }
    public String getFragment() { int i = s.indexOf('#'); return i < 0 ? null : s.substring(i + 1); }
    public String getQueryParameter(String k) {
        String q = getQuery();
        if (q == null) return null;
        for (String p : q.split("&")) { int e = p.indexOf('='); String n = e < 0 ? p : p.substring(0, e); if (n.equals(k)) return e < 0 ? "" : decode(p.substring(e + 1)); }
        return null;
    }
    public java.util.List<String> getPathSegments() {
        java.util.List<String> l = new java.util.ArrayList<>();
        String p = getPath();
        if (p != null) for (String x : p.split("/")) if (!x.isEmpty()) l.add(x);
        return l;
    }
    public String getLastPathSegment() { java.util.List<String> l = getPathSegments(); return l.isEmpty() ? null : l.get(l.size() - 1); }
    public Builder buildUpon() { return new Builder(s); }
    /** الملف المحلي لهذا العنوان (file://). */
    public File file() {
        // 11.2: ملفات المشاركة الداخلية (content://<الحزمة>.files/<الاسم>) = مجلد cache/shared للتطبيق
        if (s.startsWith("content://")) {
            String rest = s.substring("content://".length());
            int sl = rest.indexOf('/');
            String auth = sl < 0 ? rest : rest.substring(0, sl);
            String name = sl < 0 ? "" : decode(rest.substring(sl + 1));
            android.content.Context c = android.content.Context.app;
            if (auth.endsWith(".files") && c != null && !name.isEmpty() && !name.contains("/") && !name.contains("..")) return new File(new File(c.getCacheDir(), "shared"), name);
        }
        String x = s;
        int q = x.indexOf('?');
        if (q >= 0) x = x.substring(0, q);
        try { return new File(new java.net.URI(x)); } catch (Exception e) { return new File(decode(x.replaceFirst("^file:/+", "/"))); }
    }
    public boolean isFile() { return s.startsWith("file:") || (s.startsWith("content://") && file() != null && s.substring(10).split("/")[0].endsWith(".files")); }
    @Override public String toString() { return s; }
    @Override public boolean equals(Object o) { return o instanceof Uri && ((Uri) o).s.equals(s); }
    @Override public int hashCode() { return s.hashCode(); }
    @Override public int compareTo(Uri o) { return s.compareTo(o.s); }

    public static final class Builder {
        private String s;
        public Builder() { s = ""; }
        Builder(String s) { this.s = s; }
        public Builder scheme(String sc) { s = sc + ":" + s; return this; }
        public Builder authority(String a) { s = s + "//" + a; return this; }
        public Builder path(String p) { s = s + p; return this; }
        public Builder appendPath(String p) { s = s + (s.endsWith("/") ? "" : "/") + encode(p); return this; }
        public Builder appendQueryParameter(String k, String v) { s = s + (s.contains("?") ? "&" : "?") + encode(k) + "=" + encode(v); return this; }
        public Builder encodedPath(String p) { s = s + p; return this; }
        public Builder fragment(String f) { s = s + "#" + f; return this; }
        public Builder clearQuery() { int i = s.indexOf('?'); if (i >= 0) s = s.substring(0, i); return this; }
        public Uri build() { return new Uri(s); }
    }
}
