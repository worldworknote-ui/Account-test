package android.graphics;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.*;

/**
 * خطوط أندرويد على الكمبيوتر: خطوط التطبيق نفسها (plex/cairo/kufi) من موارد البرنامج، والخط الافتراضي يتبع اختيار المستخدم
 * (يضبطه Activity عند تطبيق السمة). الرموز التي لا يحويها الخط تُرسم بخط بديل من النظام (FontKit).
 */
public class Typeface {
    public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;

    final String family;
    final int style;

    private Typeface(String family, int style) { this.family = family; this.style = style; }

    /** الخط الافتراضي حسب السمة الحالية (plex افتراضيًا). */
    public static volatile String themeFamily = "plex";

    public static final Typeface DEFAULT = new Typeface(null, NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface(null, BOLD);
    public static final Typeface SANS_SERIF = new Typeface("sans-serif", NORMAL);
    public static final Typeface SERIF = new Typeface("serif", NORMAL);
    public static final Typeface MONOSPACE = new Typeface("monospace", NORMAL);

    public static Typeface create(String family, int style) { return new Typeface(family, style); }
    public static Typeface create(Typeface t, int style) { return new Typeface(t == null ? null : t.family, style); }
    public static Typeface create(Typeface t, int weight, boolean italic) { return new Typeface(t == null ? null : t.family, weight >= 600 ? BOLD : NORMAL); }
    public static Typeface defaultFromStyle(int style) { return new Typeface(null, style); }
    public static Typeface family(String f) { return new Typeface(f, NORMAL); }

    public int getStyle() { return style; }
    public boolean isBold() { return (style & BOLD) != 0; }
    public boolean isItalic() { return (style & ITALIC) != 0; }
    public String familyName() { return family == null ? themeFamily : family; }

    @Override public boolean equals(Object o) {
        return o instanceof Typeface && Objects.equals(((Typeface) o).family, family) && ((Typeface) o).style == style;
    }
    @Override public int hashCode() { return Objects.hashCode(family) * 7 + style; }

    // ------------------------------------------------------------------ تحميل الخطوط
    private static final Map<String, Font> loaded = new HashMap<>();

    /** الخط الأساسي بحجم 1 لهذه العائلة والنمط. */
    public Font base() { return base(familyName(), isBold()); }

    public static synchronized Font base(String fam, boolean bold) {
        String key = fam + (bold ? ":b" : ":r");
        Font f = loaded.get(key);
        if (f != null) return f;
        String file = null;
        switch (fam) {
            case "plex": case "cairo": case "kufi": file = fam + (bold ? "_bold" : "_regular") + ".ttf"; break;
            default: break;
        }
        if (file != null) f = load("/res/" + file);
        if (f == null && ("monospace".equals(fam))) f = new Font(Font.MONOSPACED, bold ? Font.BOLD : Font.PLAIN, 1);
        if (f == null && ("sans-serif".equals(fam) || "system".equals(fam) || "serif".equals(fam))) f = systemFont(bold);
        if (f == null) {
            Font p = load("/res/plex" + (bold ? "_bold" : "_regular") + ".ttf");
            f = p != null ? p : new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 1);
        }
        loaded.put(key, f);
        return f;
    }

    private static Font load(String res) {
        try (InputStream in = Typeface.class.getResourceAsStream(res)) {
            if (in == null) return null;
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            try { GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f); } catch (Throwable ignored) { }
            return f.deriveFont(1f);
        } catch (Exception e) {
            return null;
        }
    }

    /** «خط النظام»: خط عربي من النظام إن وُجد (الماك: SF Arabic/Geeza، ويندوز: Segoe UI)، وإلا plex. */
    private static Font systemFont(boolean bold) {
        for (String n : new String[]{"SF Arabic", "Geeza Pro", "Segoe UI", "Tahoma", "Noto Sans Arabic", "Arial"}) {
            if (FontKit.has(n)) {
                Font f = new Font(n, bold ? Font.BOLD : Font.PLAIN, 1);
                if (f.canDisplay('ا')) return f;
            }
        }
        return null;
    }
}
