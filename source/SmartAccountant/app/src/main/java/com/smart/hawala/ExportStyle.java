package com.smart.hawala;

import java.util.LinkedHashMap;
import java.util.Map;

/** خيارات تصميم الكشوفات المصدّرة. تُحفظ في الإعدادات بمفاتيح تبدأ بـ st_ وتُنقل مع النسخة الاحتياطية. */
public final class ExportStyle {
    public String title = "كشف حساب";
    public String color = "";          // فارغ = لون التطبيق الرئيسي
    public boolean logo = true;
    public String issuerInfo = "";     // عنوان/هاتف يظهر تحت اسم مكتبك
    public boolean showPerson = true;
    public boolean showDesc = true;
    public boolean showRef = true;
    public boolean showNotes = false;
    public boolean showRunning = true;
    public boolean showSummary = true;
    public boolean hideCancelled = true;
    public boolean newestFirst = false;
    public String font = "normal";     // small / normal / large
    public boolean landscape = false;
    public String imageSize = "medium"; // small / medium / large
    public String footer = "";
    public boolean signature = false;
    /** لون مختلف لكل عملة في الكشوفات. */
    public boolean curColors = true;
    /** ألوان اختارها المستخدم: «USD=#1565C0;EUR=#2E7D32». غير المذكورة تأخذ لونًا افتراضيًا ثابتًا. */
    public String curColorMap = "";

    /** ألوان افتراضية ثابتة لأشهر العملات، ثم دورة ألوان واضحة لغيرها. */
    static final String[][] DEFAULT_CUR_COLORS = {
            {"USD", "#1565C0"}, {"EUR", "#2E7D32"}, {"TRY", "#C62828"}, {"SYP", "#6A1B9A"}, {"USDT", "#00838F"},
            {"SAR", "#8D6E63"}, {"AED", "#E65100"}, {"GBP", "#283593"}, {"IQD", "#AD1457"}, {"JOD", "#455A64"}, {"EGP", "#9E7C0C"}
    };
    static final String[] CYCLE = {"#0277BD", "#558B2F", "#D84315", "#4527A0", "#00695C", "#6D4C41", "#C2185B", "#37474F"};

    static int hex(String h) {
        String x = h.trim().replace("#", "");
        if (x.length() == 6) x = "FF" + x;
        return (int) Long.parseLong(x, 16);
    }

    public Map<String, String> colorMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : curColorMap.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        return m;
    }

    public static String joinMap(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** لون العملة (ARGB). */
    public int colorFor(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        String chosen = colorMap().get(c);
        try {
            if (chosen != null && !chosen.isEmpty()) return hex(chosen);
        } catch (Exception ignored) {
            // لون غير صالح: نرجع للافتراضي
        }
        for (String[] d : DEFAULT_CUR_COLORS) if (d[0].equals(c)) return hex(d[1]);
        int h = 0;
        for (char ch : c.toCharArray()) h = h * 31 + ch;
        return hex(CYCLE[Math.abs(h % CYCLE.length)]);
    }

    public String colorHexFor(String code) {
        int v = colorFor(code);
        return String.format(java.util.Locale.US, "#%06X", v & 0xFFFFFF);
    }

    public float fontScale() {
        return "small".equals(font) ? 0.88f : "large".equals(font) ? 1.18f : 1f;
    }

    public float imageWidth() {
        return "small".equals(imageSize) ? 150f : "large".equals(imageSize) ? 360f : 240f;
    }

    private static boolean b(Map<String, String> m, String k, boolean def) {
        String v = m.get(k);
        return v == null || v.isEmpty() ? def : "1".equals(v);
    }

    private static String s(Map<String, String> m, String k, String def) {
        String v = m.get(k);
        return v == null ? def : v;
    }

    public static ExportStyle from(Map<String, String> m) {
        ExportStyle st = new ExportStyle();
        st.title = s(m, "st_title", st.title);
        if (st.title.trim().isEmpty()) st.title = "كشف حساب";
        st.color = s(m, "st_color", "");
        st.logo = b(m, "st_logo", true);
        st.issuerInfo = s(m, "st_issuer_info", "");
        st.showPerson = b(m, "st_person", true);
        st.showDesc = b(m, "st_desc", true);
        st.showRef = b(m, "st_ref", true);
        st.showNotes = b(m, "st_notes", false);
        st.showRunning = b(m, "st_running", true);
        st.showSummary = b(m, "st_summary", true);
        st.hideCancelled = b(m, "st_hide_cancelled", true);
        st.newestFirst = b(m, "st_newest_first", false);
        st.font = s(m, "st_font", "normal");
        st.landscape = b(m, "st_landscape", false);
        st.imageSize = s(m, "st_image_size", "medium");
        st.footer = s(m, "st_footer", "");
        st.signature = b(m, "st_signature", false);
        st.curColors = b(m, "st_cur_colors", true);
        st.curColorMap = s(m, "st_cur_color_map", "");
        return st;
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("st_title", title);
        m.put("st_color", color);
        m.put("st_logo", logo ? "1" : "0");
        m.put("st_issuer_info", issuerInfo);
        m.put("st_person", showPerson ? "1" : "0");
        m.put("st_desc", showDesc ? "1" : "0");
        m.put("st_ref", showRef ? "1" : "0");
        m.put("st_notes", showNotes ? "1" : "0");
        m.put("st_running", showRunning ? "1" : "0");
        m.put("st_summary", showSummary ? "1" : "0");
        m.put("st_hide_cancelled", hideCancelled ? "1" : "0");
        m.put("st_newest_first", newestFirst ? "1" : "0");
        m.put("st_font", font);
        m.put("st_landscape", landscape ? "1" : "0");
        m.put("st_image_size", imageSize);
        m.put("st_footer", footer);
        m.put("st_signature", signature ? "1" : "0");
        m.put("st_cur_colors", curColors ? "1" : "0");
        m.put("st_cur_color_map", curColorMap);
        return m;
    }

    public static final String[] KEYS = {
            "st_title", "st_color", "st_logo", "st_issuer_info", "st_person", "st_desc", "st_ref", "st_notes", "st_running",
            "st_summary", "st_hide_cancelled", "st_newest_first", "st_font", "st_landscape", "st_image_size", "st_footer", "st_signature",
            "st_cur_colors", "st_cur_color_map"
    };
}
