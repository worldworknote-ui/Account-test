package android.util;
public class Base64 {
    public static final int DEFAULT = 0, NO_PADDING = 1, NO_WRAP = 2, CRLF = 4, URL_SAFE = 8, NO_CLOSE = 16;
    public static String encodeToString(byte[] b, int flags) { return new String(encode(b, flags), java.nio.charset.StandardCharsets.US_ASCII); }
    public static String encodeToString(byte[] b, int off, int len, int flags) { return encodeToString(java.util.Arrays.copyOfRange(b, off, off + len), flags); }
    public static byte[] encode(byte[] b, int flags) {
        java.util.Base64.Encoder e = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) e = e.withoutPadding();
        byte[] r = e.encode(b);
        if ((flags & NO_WRAP) != 0) return r;
        // أندرويد يلف كل 76 حرفًا ويضيف سطرًا في الآخر
        StringBuilder sb = new StringBuilder();
        String s = new String(r, java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < s.length(); i += 76) sb.append(s, i, Math.min(s.length(), i + 76)).append((flags & CRLF) != 0 ? "\r\n" : "\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    public static byte[] decode(String s, int flags) {
        String c = s.replaceAll("\\s", "");
        if ((flags & URL_SAFE) != 0 || c.indexOf('-') >= 0 || c.indexOf('_') >= 0) return java.util.Base64.getUrlDecoder().decode(pad(c));
        return java.util.Base64.getDecoder().decode(pad(c));
    }
    public static byte[] decode(byte[] b, int flags) { return decode(new String(b, java.nio.charset.StandardCharsets.US_ASCII), flags); }
    private static String pad(String s) { int m = s.length() % 4; return m == 0 || s.endsWith("=") ? s : s + "====".substring(m); }
}
