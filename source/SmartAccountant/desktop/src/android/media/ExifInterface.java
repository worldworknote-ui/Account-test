package android.media;

/** اتجاه الصورة من بيانات EXIF (JPEG) — قارئ صغير. */
public class ExifInterface {
    public static final String TAG_ORIENTATION = "Orientation";
    public static final int ORIENTATION_NORMAL = 1, ORIENTATION_FLIP_HORIZONTAL = 2, ORIENTATION_ROTATE_180 = 3, ORIENTATION_FLIP_VERTICAL = 4,
            ORIENTATION_TRANSPOSE = 5, ORIENTATION_ROTATE_90 = 6, ORIENTATION_TRANSVERSE = 7, ORIENTATION_ROTATE_270 = 8, ORIENTATION_UNDEFINED = 0;
    private int orientation = ORIENTATION_UNDEFINED;
    public ExifInterface(String path) throws java.io.IOException { this(new java.io.FileInputStream(path)); }
    public ExifInterface(java.io.File f) throws java.io.IOException { this(new java.io.FileInputStream(f)); }
    public ExifInterface(java.io.FileDescriptor fd) throws java.io.IOException { this(new java.io.FileInputStream(fd)); }
    public ExifInterface(java.io.InputStream in) throws java.io.IOException {
        try (java.io.InputStream s = in) { orientation = read(s.readNBytes(256 * 1024)); } catch (Exception e) { orientation = ORIENTATION_UNDEFINED; }
    }
    public int getAttributeInt(String tag, int def) { return TAG_ORIENTATION.equals(tag) && orientation != 0 ? orientation : def; }
    public String getAttribute(String tag) { return TAG_ORIENTATION.equals(tag) ? String.valueOf(orientation) : null; }
    public void setAttribute(String tag, String v) {}
    public void saveAttributes() {}

    private static int read(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 0;
        int i = 2;
        while (i + 4 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) return 0;
            int marker = b[i + 1] & 0xFF, len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (marker == 0xE1 && i + 10 < b.length && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                int t = i + 10;
                boolean le = b[t] == 'I';
                int ifd = u32(b, t + 4, le);
                int p = t + ifd;
                int n = u16(b, p, le);
                for (int k = 0; k < n; k++) {
                    int e = p + 2 + k * 12;
                    if (e + 12 > b.length) break;
                    if (u16(b, e, le) == 0x0112) return u16(b, e + 8, le);
                }
                return 0;
            }
            i += 2 + len;
        }
        return 0;
    }
    private static int u16(byte[] b, int i, boolean le) { return le ? (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) : ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static int u32(byte[] b, int i, boolean le) { return le ? u16(b, i, true) | (u16(b, i + 2, true) << 16) : (u16(b, i, false) << 16) | u16(b, i + 2, false); }
}
