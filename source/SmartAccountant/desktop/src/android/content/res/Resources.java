package android.content.res;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.util.DisplayMetrics;
import java.util.HashMap;
import java.util.Map;

/** موارد التطبيق: المقاسات (كثافة العرض) والأيقونات والخطوط والنصوص بأسمائها من R المولَّد. */
public class Resources {
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String m) { super(m); }
        public NotFoundException() { super(); }
    }

    public class Theme {
        public void applyStyle(int style, boolean force) { Resources.this.applyStyle(style); }
        public Resources getResources() { return Resources.this; }
    }

    private static final Resources SYS = new Resources();
    public static Resources getSystem() { return SYS; }

    public final DisplayMetrics metrics = new DisplayMetrics();
    public final Configuration config = new Configuration();

    public DisplayMetrics getDisplayMetrics() { return metrics; }
    public Configuration getConfiguration() { return config; }

    // ------------------------------------------------------------------ أسماء المعرّفات
    private static Map<Integer, String[]> names;

    static synchronized Map<Integer, String[]> names() {
        if (names != null) return names;
        names = new HashMap<>();
        for (String cls : new String[]{"com.smart.hawala.R", "android.R"}) {
            try {
                for (Class<?> k : Class.forName(cls).getDeclaredClasses()) {
                    for (java.lang.reflect.Field f : k.getFields()) {
                        if (f.getType() == int.class) names.put(f.getInt(null), new String[]{k.getSimpleName(), f.getName()});
                    }
                }
            } catch (Throwable ignored) { }
        }
        return names;
    }

    public String getResourceEntryName(int id) { String[] n = names().get(id); if (n == null) throw new NotFoundException("#" + Integer.toHexString(id)); return n[1]; }
    public String getResourceTypeName(int id) { String[] n = names().get(id); if (n == null) throw new NotFoundException(); return n[0]; }
    public String getResourceName(int id) { String[] n = names().get(id); if (n == null) throw new NotFoundException(); return "com.smart.hawala:" + n[0] + "/" + n[1]; }
    public int getIdentifier(String name, String type, String pkg) {
        for (Map.Entry<Integer, String[]> e : names().entrySet()) if (e.getValue()[0].equals(type) && e.getValue()[1].equals(name)) return e.getKey();
        return 0;
    }

    // ------------------------------------------------------------------ القراءة
    public Drawable getDrawable(int id) { return getDrawable(id, null); }
    public Drawable getDrawable(int id, Theme t) {
        String[] n = names().get(id);
        if (n == null) return null;
        if ("mipmap".equals(n[0])) return VectorDrawable.load("ic_logo");
        if ("drawable".equals(n[0])) {
            Drawable d = VectorDrawable.load(n[1]);
            if (d == null && "android.R".equals(null)) return null;
            return d != null ? d : VectorDrawable.load(androidFallback(n[1]));
        }
        return null;
    }

    private static String androidFallback(String name) {
        if (name.contains("share")) return "ic_share";
        if (name.contains("alert")) return "ic_warn";
        if (name.contains("search")) return "ic_search";
        if (name.contains("add")) return "ic_plus";
        if (name.contains("delete")) return "ic_trash";
        if (name.contains("sync")) return "ic_sync";
        if (name.contains("close")) return "ic_close";
        return "ic_info";
    }

    public String getString(int id) {
        try {
            for (String[] s : (String[][]) Class.forName("com.smart.hawala.R").getField("STRINGS").get(null)) {
                if (s[0].equals(getResourceEntryName(id))) return s[1];
            }
        } catch (Throwable ignored) { }
        if (id == android.R.string.ok) return "حسنًا";
        if (id == android.R.string.cancel) return "إلغاء";
        if (id == android.R.string.yes) return "نعم";
        if (id == android.R.string.no) return "لا";
        return "";
    }
    public String getString(int id, Object... args) { return String.format(getString(id), args); }
    public CharSequence getText(int id) { return getString(id); }
    public int getColor(int id) { return getColor(id, null); }
    public int getColor(int id, Theme t) {
        if (id == android.R.color.white) return 0xFFFFFFFF;
        if (id == android.R.color.black) return 0xFF000000;
        return 0xFF0B6B53;
    }
    public ColorStateList getColorStateList(int id) { return ColorStateList.valueOf(getColor(id)); }
    public float getDimension(int id) { return 0; }
    public int getDimensionPixelSize(int id) { return 0; }
    public int getDimensionPixelOffset(int id) { return 0; }
    public int getInteger(int id) { return 0; }
    public boolean getBoolean(int id) { return false; }
    public Typeface getFont(int id) {
        String[] n = names().get(id);
        return n == null ? Typeface.DEFAULT : Typeface.family(n[1]);
    }
    public java.io.InputStream openRawResource(int id) { throw new NotFoundException(); }
    public android.content.res.AssetManager getAssets() { return new AssetManager(); }
    public Theme newTheme() { return new Theme(); }

    /** طبقات السمة: الخط (FontPlex...) والوضع الليلي. */
    public void applyStyle(int style) {
        String[] n = names().get(style);
        if (n == null) return;
        switch (n[1]) {
            case "FontPlex": Typeface.themeFamily = "plex"; break;
            case "FontCairo": Typeface.themeFamily = "cairo"; break;
            case "FontKufi": Typeface.themeFamily = "kufi"; break;
            case "FontSystem": Typeface.themeFamily = "system"; break;
            default: break;
        }
    }
}
