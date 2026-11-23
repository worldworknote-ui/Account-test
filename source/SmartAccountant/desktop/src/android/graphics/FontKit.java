package android.graphics;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.text.AttributedString;
import java.util.*;

/** الخط البديل للرموز: يقسّم النص إلى مقاطع، ولكل مقطع خط يحوي حروفه. */
public final class FontKit {
    private FontKit() {}

    private static Set<String> names;
    private static List<Font> fallbacks;

    public static synchronized boolean has(String name) {
        if (names == null) {
            names = new HashSet<>();
            try { names.addAll(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT))); }
            catch (Throwable ignored) { }
        }
        return names.contains(name);
    }

    static synchronized List<Font> fallbacks() {
        if (fallbacks != null) return fallbacks;
        List<Font> l = new ArrayList<>();
        for (String fam : new String[]{"plex", "cairo", "kufi"}) l.add(Typeface.base(fam, false));
        for (String n : new String[]{"Apple Symbols", "Segoe UI Symbol", "Segoe UI Emoji", "Arial Unicode MS", "Lucida Grande",
                "DejaVu Sans", "Noto Sans Symbols2", "Noto Sans Symbols", "Noto Sans", "Symbola", "Lucida Sans Unicode", "Segoe UI", "Arial"}) {
            if (has(n)) l.add(new Font(n, Font.PLAIN, 1));
        }
        l.add(new Font(Font.DIALOG, Font.PLAIN, 1));
        fallbacks = l;
        return l;
    }

    private static final Map<Long, Font> pick = new HashMap<>();

    /** خط يرسم المحرف cp (الأساسي إن أمكن). */
    public static Font fontFor(Font primary, int cp) {
        if (primary.canDisplay(cp) || cp < 0x20 || Character.getType(cp) == Character.FORMAT || cp == 0xFE0F) return primary;
        long key = ((long) System.identityHashCode(primary) << 32) | cp;
        synchronized (pick) {
            Font f = pick.get(key);
            if (f != null) return f;
            f = primary;
            for (Font b : fallbacks()) if (b.canDisplay(cp)) { f = b; break; }
            pick.put(key, f);
            return f;
        }
    }

    /** يضيف الخطوط إلى النص (مع البدائل) للمدى [s,e). size بالبكسل، والنمط العريض يُطلب من الخط البديل أيضًا. */
    public static void apply(AttributedString as, String text, int s, int e, Font primary, float size, boolean bold) {
        Font sized = primary.deriveFont(size);
        int i = s;
        while (i < e) {
            int cp = text.codePointAt(i);
            Font f = fontFor(primary, cp);
            int j = i + Character.charCount(cp);
            while (j < e) {
                int c2 = text.codePointAt(j);
                if (fontFor(primary, c2) != f) break;
                j += Character.charCount(c2);
            }
            Font use = f == primary ? sized : f.deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
            as.addAttribute(TextAttribute.FONT, use, i, j);
            i = j;
        }
    }

    /** هل يحتاج النص خطًا بديلًا. */
    public static boolean needsFallback(Font primary, String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (fontFor(primary, cp) != primary) return true;
            i += Character.charCount(cp);
        }
        return false;
    }
}
