package android.graphics;

/** ألوان أندرويد (int بصيغة ARGB) — نسخة الكمبيوتر. */
public class Color {
    public static final int BLACK = 0xFF000000, WHITE = 0xFFFFFFFF, TRANSPARENT = 0, RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF,
            GRAY = 0xFF888888, DKGRAY = 0xFF444444, LTGRAY = 0xFFCCCCCC, YELLOW = 0xFFFFFF00, CYAN = 0xFF00FFFF, MAGENTA = 0xFFFF00FF;

    public static int alpha(int c) { return c >>> 24; }
    public static int red(int c) { return (c >> 16) & 0xFF; }
    public static int green(int c) { return (c >> 8) & 0xFF; }
    public static int blue(int c) { return c & 0xFF; }
    public static int rgb(int r, int g, int b) { return 0xFF000000 | (cl(r) << 16) | (cl(g) << 8) | cl(b); }
    public static int argb(int a, int r, int g, int b) { return (cl(a) << 24) | (cl(r) << 16) | (cl(g) << 8) | cl(b); }
    public static int rgb(float r, float g, float b) { return rgb((int) (r * 255 + .5f), (int) (g * 255 + .5f), (int) (b * 255 + .5f)); }
    public static int argb(float a, float r, float g, float b) { return argb((int) (a * 255 + .5f), (int) (r * 255 + .5f), (int) (g * 255 + .5f), (int) (b * 255 + .5f)); }
    private static int cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    public static int parseColor(String s) {
        if (s == null) throw new IllegalArgumentException("Unknown color");
        s = s.trim();
        if (s.startsWith("#")) {
            String h = s.substring(1);
            long v = Long.parseLong(h, 16);
            if (h.length() == 6) return (int) (v | 0xFF000000L);
            if (h.length() == 8) return (int) v;
            throw new IllegalArgumentException("Unknown color");
        }
        switch (s.toLowerCase()) {
            case "black": return BLACK;
            case "white": return WHITE;
            case "red": return RED;
            case "green": return GREEN;
            case "blue": return BLUE;
            case "gray": case "grey": return GRAY;
            case "yellow": return YELLOW;
            case "transparent": return TRANSPARENT;
            default: throw new IllegalArgumentException("Unknown color");
        }
    }

    public static void colorToHSV(int c, float[] hsv) {
        float[] r = java.awt.Color.RGBtoHSB(red(c), green(c), blue(c), null);
        hsv[0] = r[0] * 360f;
        hsv[1] = r[1];
        hsv[2] = r[2];
    }

    public static int HSVToColor(float[] hsv) { return HSVToColor(255, hsv); }

    public static int HSVToColor(int a, float[] hsv) {
        int rgb = java.awt.Color.HSBtoRGB(hsv[0] / 360f, hsv[1], hsv[2]);
        return (cl(a) << 24) | (rgb & 0xFFFFFF);
    }

    public static float luminance(int c) {
        double r = lin(red(c) / 255.0), g = lin(green(c) / 255.0), b = lin(blue(c) / 255.0);
        return (float) (0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    private static double lin(double v) { return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }

    /** للرسم بـ Java2D. */
    public static java.awt.Color awt(int c) { return new java.awt.Color(c, true); }
}
