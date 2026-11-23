package com.smart.hawala;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 10.15: إعدادات الشريط السفلي «الزجاجي بتجويف» وقائمة «+» (منقولة من تطبيق «أموالي» 1.6 ومكيّفة للمكاتب):
 * شكل الشريط وحجمه وألوانه وشفافيته ولون الدائرة والأيقونات وظهور الأسماء، ومحتوى قائمة «+» وترتيبها ولون كل زر
 * وشكلها وحجمها، وماذا يفعل زر «+». ترتيب أزرار الشريط نفسه باقٍ في إعداد «tabs» كما كان.
 * تُحفظ نصًّا JSON في الإعداد الشخصي nav_prefs (لكل مستخدم وحده). منطق بحت بلا أندرويد (الألوان أعداد ARGB) ليُختبر في tests/.
 */
public final class NavPrefs {
    /** لون «تلقائي»: الشريط بلون الشريط العلوي للتطبيق، والدائرة بلون التبويب المفتوح، والأيقونات حسب لون الشريط. */
    public static final int AUTO = 0;

    /** كل ما يمكن وضعه في قائمة «+»: المفتاح ← [الاسم، اللون الافتراضي]. ما لا يملك المستخدم صلاحيته لا يظهر له. */
    public static final Map<String, Object[]> ITEMS = new LinkedHashMap<>();

    static {
        ITEMS.put("TX", new Object[]{"حركة عادية", rgb(11, 107, 83)});
        ITEMS.put("FX", new Object[]{"قص عملات", rgb(30, 64, 110)});
        ITEMS.put("XFER", new Object[]{"بين مكتبين", rgb(14, 116, 144)});
        ITEMS.put("WA", new Object[]{"من واتساب", rgb(31, 143, 90)});
        ITEMS.put("CASH_IN", new Object[]{"استلام", rgb(22, 128, 82)});
        ITEMS.put("CASH_OUT", new Object[]{"تسليم", rgb(201, 63, 63)});
        ITEMS.put("SELL", new Object[]{"بيع عملة", rgb(79, 70, 229)});
        ITEMS.put("BUY", new Object[]{"شراء عملة", rgb(13, 128, 128)});
        ITEMS.put("EXP", new Object[]{"مصروف", rgb(201, 162, 39)});
        ITEMS.put("COUNT", new Object[]{"جرد", rgb(100, 116, 139)});
        ITEMS.put("DAILY", new Object[]{"اليومي", rgb(217, 119, 6)});
    }

    public static final List<String> DEFAULT_FAN = Arrays.asList("TX", "FX", "XFER", "WA");
    public static final int MIN_FAN = 2, MAX_FAN = 6;

    /** ألوان جاهزة للشريط (الأول «تلقائي» = لون الشريط العلوي). */
    public static final int[] BAR_COLORS = {AUTO, rgb(58, 47, 43), rgb(22, 50, 92), rgb(24, 24, 27), rgb(250, 250, 252), rgb(31, 120, 90),
            rgb(176, 124, 16), rgb(185, 50, 50), rgb(91, 70, 190), rgb(100, 116, 139)};
    /** ألوان الدائرة (الأول «تلقائي» = لون التبويب المفتوح). */
    public static final int[] CIRCLE_COLORS = {AUTO, rgb(44, 112, 88), rgb(30, 64, 110), rgb(176, 124, 16), rgb(185, 50, 50), rgb(91, 70, 190),
            rgb(13, 128, 128), rgb(190, 24, 93), rgb(24, 24, 27)};
    /** ألوان الأيقونات (الأول «تلقائي» = أبيض على الداكن وكحلي على الفاتح). */
    public static final int[] ICON_COLORS = {AUTO, 0xFFFFFFFF, rgb(242, 196, 109), rgb(126, 226, 176), rgb(22, 50, 92), rgb(24, 24, 27)};
    public static final int[] ALPHA = {110, 175, 240};
    public static final String[] ALPHA_NAMES = {"شفاف", "وسط", "معتم"};
    public static final String[] FAN_STYLES = {"فاتح", "كحلي", "داكن"};
    public static final String[] SIZES = {"صغير", "وسط", "كبير"};
    public static final String[] BAR_SHAPES = {"موصول بالحواف", "جزيرة عائمة"};

    /** أنماط الشريط (الإعداد tab_style): الزجاجي بتجويف (الجديد)، والجزيرة، والدائرة التي تتمدد. */
    public static final String STYLE_NOTCH = "notch", STYLE_ISLAND = "island", STYLE_BUBBLE = "bubble";
    public static final String[] STYLES = {STYLE_NOTCH, STYLE_ISLAND, STYLE_BUBBLE};
    public static final String[] STYLE_NAMES = {"زجاجي بتجويف", "جزيرة", "دائرة تتمدد"};

    public List<String> fan = new ArrayList<>(DEFAULT_FAN);
    public Map<String, Integer> itemColors = new LinkedHashMap<>();
    public int barColor = rgb(58, 47, 43);
    public int barAlpha = 2;
    public int circleColor = AUTO;
    public int iconColor = AUTO;
    public int fanStyle = 0;
    public int fanSize = 1;
    /** حجم الشريط (صغير/وسط/كبير). */
    public int barSize = 1;
    /** true = جزيرة عائمة مستديرة، false = موصول بطرفي الشاشة. */
    public boolean barFloat = false;
    /** أسماء التبويبات تحت الأيقونات. */
    public boolean labels = true;
    /** زر «+» يفتح القائمة السريعة (true) أو صفحة التسجيل مباشرة (false). */
    public boolean plusMenu = true;

    public static int rgb(int r, int g, int b) { return 0xFF000000 | (r << 16) | (g << 8) | b; }

    static int r(int c) { return (c >> 16) & 0xFF; }

    static int g(int c) { return (c >> 8) & 0xFF; }

    static int b(int c) { return c & 0xFF; }

    /** خلط لونين (f = نسبة الثاني). */
    public static int mix(int a, int b, float f) {
        return rgb((int) (r(a) + (r(b) - r(a)) * f), (int) (g(a) + (g(b) - g(a)) * f), (int) (b(a) + (b(b) - b(a)) * f));
    }

    public static boolean light(int c) { return (0.299 * r(c) + 0.587 * g(c) + 0.114 * b(c)) > 165; }

    /** لون الشريط الفعلي: المختار، أو لون الشريط العلوي للتطبيق. */
    public int bar(int header) { return barColor != AUTO ? barColor : header | 0xFF000000; }

    /** لون الدائرة الفعلي: المختار، أو لون التبويب. */
    public int circle(int tabColor) { return circleColor != AUTO ? circleColor : tabColor | 0xFF000000; }

    /** لون أيقونات الشريط الفعلي: المختار، أو تلقائيًا أبيض على الشريط الداكن وكحلي على الفاتح. */
    public int icons(int header) { return iconColor != AUTO ? iconColor : light(bar(header)) ? rgb(22, 50, 92) : 0xFFFFFFFF; }

    public int itemColor(String k) {
        Integer c = itemColors.get(k);
        if (c != null) return c;
        Object[] o = ITEMS.get(k);
        return o == null ? rgb(100, 116, 139) : (Integer) o[1];
    }

    public static String itemName(String k) {
        Object[] o = ITEMS.get(k);
        return o == null ? k : (String) o[0];
    }

    public static String styleName(String style) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return STYLE_NAMES[i];
        return STYLE_NAMES[0];
    }

    /** النمط المحفوظ بعد التحقق (قيمة غير معروفة = الزجاجي). */
    public static String style(String saved) {
        for (String s : STYLES) if (s.equals(saved)) return s;
        return STYLE_NOTCH;
    }

    // ===================================================================================
    // تعديل (يرجع رسالة خطأ للمستخدم أو null)
    // ===================================================================================

    public static void move(List<String> l, int i, int d) {
        int j = i + d;
        if (i < 0 || j < 0 || i >= l.size() || j >= l.size()) return;
        String x = l.get(i);
        l.set(i, l.get(j));
        l.set(j, x);
    }

    public String toggleFan(String k) {
        if (fan.contains(k)) {
            if (fan.size() <= MIN_FAN) return "يجب أن تبقى في القائمة " + MIN_FAN + " أزرار على الأقل";
            fan.remove(k);
        } else {
            if (fan.size() >= MAX_FAN) return "الحد الأقصى " + MAX_FAN + " أزرار حتى تبقى القائمة مريحة";
            if (!ITEMS.containsKey(k)) return "زر غير معروف";
            fan.add(k);
        }
        return null;
    }

    // ===================================================================================
    // الحفظ والقراءة
    // ===================================================================================

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("fan", new JSONArray(fan));
            JSONObject ic = new JSONObject();
            for (Map.Entry<String, Integer> e : itemColors.entrySet()) ic.put(e.getKey(), e.getValue());
            o.put("itemColors", ic);
            o.put("barColor", barColor);
            o.put("barAlpha", barAlpha);
            o.put("circleColor", circleColor);
            o.put("iconColor", iconColor);
            o.put("fanStyle", fanStyle);
            o.put("fanSize", fanSize);
            o.put("barSize", barSize);
            o.put("barFloat", barFloat);
            o.put("labels", labels);
            o.put("plusMenu", plusMenu);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static int color(JSONObject o, String k, int def) {
        if (!o.has(k)) return def;
        int c = o.optInt(k, def);
        return c == AUTO ? AUTO : c | 0xFF000000;
    }

    /** يقرأ الإعدادات؛ أي قيمة ناقصة أو تالفة تعود لافتراضيها (والترتيب يُصلَح: كل زر مرة واحدة). */
    public static NavPrefs from(String json) {
        NavPrefs p = new NavPrefs();
        if (json == null || json.trim().isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray f = o.optJSONArray("fan");
            if (f != null) {
                List<String> l = new ArrayList<>();
                for (int i = 0; i < f.length() && l.size() < MAX_FAN; i++) {
                    String k = f.optString(i);
                    if (ITEMS.containsKey(k) && !l.contains(k)) l.add(k);
                }
                if (l.size() >= MIN_FAN) p.fan = l;
            }
            JSONObject ic = o.optJSONObject("itemColors");
            if (ic != null) {
                java.util.Iterator<String> it = ic.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (ITEMS.containsKey(k)) p.itemColors.put(k, ic.optInt(k) | 0xFF000000);
                }
            }
            p.barColor = color(o, "barColor", p.barColor);
            p.barAlpha = clamp(o.optInt("barAlpha", p.barAlpha), 0, ALPHA.length - 1);
            p.circleColor = color(o, "circleColor", p.circleColor);
            p.iconColor = color(o, "iconColor", p.iconColor);
            p.fanStyle = clamp(o.optInt("fanStyle", p.fanStyle), 0, FAN_STYLES.length - 1);
            p.fanSize = clamp(o.optInt("fanSize", p.fanSize), 0, SIZES.length - 1);
            p.barSize = clamp(o.optInt("barSize", p.barSize), 0, SIZES.length - 1);
            p.barFloat = o.optBoolean("barFloat", p.barFloat);
            p.labels = o.optBoolean("labels", p.labels);
            p.plusMenu = o.optBoolean("plusMenu", p.plusMenu);
        } catch (Exception e) {
            return new NavPrefs();
        }
        return p;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
