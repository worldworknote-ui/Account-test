package com.smart.hawala;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * رسالة «مطابقة رصيد» التي تُرسل للمكتب: الأرصدة فقط، بنص جاهز لواتساب أو كصورة.
 * منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر. القالب يُحفظ في الإعدادات بمفاتيح rc_* ويُنقل مع النسخة.
 */
public final class Reconcile {
    private Reconcile() {}

    public static final String LINE = "━━━━━━━━━━━━━━━━━━";

    public static final class Template {
        public String greeting = "السلام عليكم ورحمة الله وبركاته";
        public String title = "مطابقة رصيد";
        public String ornament = "🌹";
        public String salutation = "السادة:";
        public String honorific = "المحترمين";
        public String footer = "يرجى المطابقة على الأرصدة مع المحاسب بشكل دائم 🔍";
        /** كلمة الرصيد الموجب (المكتب مدين لنا) والسالب (نحن مدينون له)، كما يقرؤها المكتب. */
        public String plusWord = "لنا";
        public String minusWord = "لكم";
        public boolean bold = true;
        public boolean showIssuer = true;
        public boolean showCode = false;
        public boolean showZero = false;

        // ---- 10.16: حرية أكبر في التصميم ----
        /**
         * شكل سطر كل عملة. يكتبه المستخدم بهذه الخانات: {علامة} {مبلغ} {عملة} {رمز} {كلمة}، وكل ما عداها يبقى كما كُتب
         * (ويجوز سطر جديد داخله). الافتراضي: المبلغ قبل اسم العملة.
         */
        public String lineFormat = LINE_AMOUNT_FIRST;
        /** حذف الأصفار العشرية: 59,450.00 → 59,450 و12.50 → 12.5 (الكسور الحقيقية تبقى). */
        public boolean trimZeros = true;
        /** سطر فارغ بين كل عملة وأخرى. */
        public boolean gap = true;
        public boolean showDate = true, showOffice = true, showSeparator = true;
        /** خط عريض لاسم العملة أيضًا. */
        public boolean boldName = false;
        public String dateLabel = "📅 التاريخ:";
        public String officeMark = "🏢";
        public String separator = LINE;
        public String markPlus = "🟢", markMinus = "🔴", markZero = "⚪";
        public String zeroWord = "صفر";
        public String emptyText = "لا توجد أرصدة قائمة؛ الحساب مسدَّد بالكامل ✅";
        /**
         * لون «لنا» ولون «لكم» (#RRGGBB) في صورة المطابقة وفي المعاينة. واتساب لا يلوّن النص، فاللون فيه هو الدائرة الملوّنة
         * قبل السطر (تتبع اللون المختار). colored=false: بلا تلوين (لون واحد داكن).
         */
        public String colorPlus = COLORS[0][1], colorMinus = COLORS[1][1];
        public boolean colored = true;

        /** لون سطر حسب إشارته (ARGB)، أو الداكن المحايد بلا تلوين/للصفر. */
        public int color(int sign) {
            if (!colored || sign == 0) return sign == 0 && colored ? 0xFF787878 : 0xFF282828;
            return rgb(sign > 0 ? colorPlus : colorMinus, sign > 0 ? 0xFF1B7846 : 0xFFAD2E36);
        }

        /** هل «لنا/لكم» قبل الرقم في شكل السطر؟ */
        public boolean wordBefore() { return wordPos(lineFormat) == 0; }

        private static String s(Map<String, String> m, String k, String def) {
            String v = m.get(k);
            return v == null ? def : v;
        }

        private static boolean b(Map<String, String> m, String k, boolean def) {
            String v = m.get(k);
            return v == null || v.isEmpty() ? def : "1".equals(v);
        }

        public static Template from(Map<String, String> m) {
            Template t = new Template();
            t.greeting = s(m, "rc_greeting", t.greeting);
            t.title = s(m, "rc_title", t.title);
            if (t.title.trim().isEmpty()) t.title = "مطابقة رصيد";
            t.ornament = s(m, "rc_ornament", t.ornament);
            t.salutation = s(m, "rc_salutation", t.salutation);
            t.honorific = s(m, "rc_honorific", t.honorific);
            t.footer = s(m, "rc_footer", t.footer);
            t.plusWord = s(m, "rc_plus", t.plusWord);
            if (t.plusWord.trim().isEmpty()) t.plusWord = "لنا";
            t.minusWord = s(m, "rc_minus", t.minusWord);
            if (t.minusWord.trim().isEmpty()) t.minusWord = "لكم";
            t.bold = b(m, "rc_bold", true);
            t.showIssuer = b(m, "rc_issuer", true);
            t.showCode = b(m, "rc_code", false);
            t.showZero = b(m, "rc_zero", false);
            t.lineFormat = validLine(s(m, "rc_line", t.lineFormat));
            t.trimZeros = b(m, "rc_trim", true);
            t.gap = b(m, "rc_gap", true);
            t.showDate = b(m, "rc_date", true);
            t.showOffice = b(m, "rc_office", true);
            t.showSeparator = b(m, "rc_sep", true);
            t.boldName = b(m, "rc_bold_name", false);
            t.dateLabel = s(m, "rc_date_label", t.dateLabel);
            t.officeMark = s(m, "rc_office_mark", t.officeMark);
            t.separator = s(m, "rc_sep_text", t.separator);
            t.markPlus = s(m, "rc_mark_plus", t.markPlus);
            t.markMinus = s(m, "rc_mark_minus", t.markMinus);
            t.markZero = s(m, "rc_mark_zero", t.markZero);
            t.zeroWord = s(m, "rc_zero_word", t.zeroWord);
            t.emptyText = s(m, "rc_empty", t.emptyText);
            if (t.emptyText.trim().isEmpty()) t.emptyText = new Template().emptyText;
            t.colorPlus = s(m, "rc_color_plus", t.colorPlus);
            t.colorMinus = s(m, "rc_color_minus", t.colorMinus);
            t.colored = b(m, "rc_colored", true);
            return t;
        }

        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("rc_greeting", greeting);
            m.put("rc_title", title);
            m.put("rc_ornament", ornament);
            m.put("rc_salutation", salutation);
            m.put("rc_honorific", honorific);
            m.put("rc_footer", footer);
            m.put("rc_plus", plusWord);
            m.put("rc_minus", minusWord);
            m.put("rc_bold", bold ? "1" : "0");
            m.put("rc_issuer", showIssuer ? "1" : "0");
            m.put("rc_code", showCode ? "1" : "0");
            m.put("rc_zero", showZero ? "1" : "0");
            m.put("rc_line", validLine(lineFormat));
            m.put("rc_trim", trimZeros ? "1" : "0");
            m.put("rc_gap", gap ? "1" : "0");
            m.put("rc_date", showDate ? "1" : "0");
            m.put("rc_office", showOffice ? "1" : "0");
            m.put("rc_sep", showSeparator ? "1" : "0");
            m.put("rc_bold_name", boldName ? "1" : "0");
            m.put("rc_date_label", dateLabel);
            m.put("rc_office_mark", officeMark);
            m.put("rc_sep_text", separator);
            m.put("rc_mark_plus", markPlus);
            m.put("rc_mark_minus", markMinus);
            m.put("rc_mark_zero", markZero);
            m.put("rc_zero_word", zeroWord);
            m.put("rc_empty", emptyText);
            m.put("rc_color_plus", colorPlus);
            m.put("rc_color_minus", colorMinus);
            m.put("rc_colored", colored ? "1" : "0");
            return m;
        }

        public static final String[] KEYS = {"rc_greeting", "rc_title", "rc_ornament", "rc_salutation", "rc_honorific", "rc_footer",
                "rc_plus", "rc_minus", "rc_bold", "rc_issuer", "rc_code", "rc_zero",
                "rc_line", "rc_trim", "rc_gap", "rc_date", "rc_office", "rc_sep", "rc_bold_name", "rc_date_label", "rc_office_mark", "rc_sep_text",
                "rc_mark_plus", "rc_mark_minus", "rc_mark_zero", "rc_zero_word", "rc_empty", "rc_color_plus", "rc_color_minus", "rc_colored"};

        /** المبلغ كما يظهر في الرسالة والصورة (بحذف الأصفار العشرية إن اختير). */
        public String amount(Row r) { return trimZeros ? trimZeros(r.amount()) : r.amount(); }
    }

    // ---- 10.16: أشكال جاهزة لسطر العملة (يختار المستخدم أحدها أو يكتب شكله) ----
    public static final String LINE_AMOUNT_FIRST = "{علامة} {مبلغ} {عملة} {كلمة}";
    public static final String LINE_NAME_FIRST = "{علامة} {عملة}: {مبلغ} {كلمة}";
    public static final String LINE_WORD_FIRST = "{علامة} {كلمة} {مبلغ} {عملة}";
    public static final String LINE_TWO_LINES = "{علامة} {عملة}\n{مبلغ} {كلمة}";
    public static final String LINE_COMPACT = "{مبلغ} {رمز} {كلمة}";
    /** {الاسم للواجهة، الشكل}. */
    public static final String[][] LINE_PRESETS = {
            {"المبلغ ثم العملة", LINE_AMOUNT_FIRST}, {"العملة ثم المبلغ", LINE_NAME_FIRST}, {"لنا/لكم أولًا", LINE_WORD_FIRST},
            {"سطران لكل عملة", LINE_TWO_LINES}, {"مختصر بالرمز", LINE_COMPACT}};
    public static final String LINE_HELP = "الخانات: {علامة} الدائرة الملوّنة، {مبلغ} الرقم، {عملة} اسم العملة، {رمز} رمزها (USD)، {كلمة} لنا/لكم. "
            + "رتّبها كما تشاء وأضف بينها أي نص أو رمز؛ واكتب \\n لسطر جديد.";

    /** ألوان جاهزة لـ«لنا» و«لكم»: {الاسم، اللون، الدائرة المطابقة له في نص واتساب}. */
    public static final String[][] COLORS = {
            {"أخضر", "#1B7846", "🟢"}, {"أحمر", "#AD2E36", "🔴"}, {"أزرق", "#1F5FBF", "🔵"}, {"برتقالي", "#D9730D", "🟠"},
            {"بنفسجي", "#7B3FB5", "🟣"}, {"بني", "#7A4B2A", "🟤"}, {"ذهبي", "#B8860B", "🟡"}, {"أسود", "#222222", "⚫"}};

    public static int colorIndex(String hex) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i][1].equalsIgnoreCase(hex)) return i;
        return -1;
    }

    /** #RRGGBB ← ARGB معتم؛ نص غير صالح = الافتراضي. */
    public static int rgb(String hex, int def) {
        try {
            String h = hex == null ? "" : hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() != 6) return def;
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (Exception e) {
            return def;
        }
    }

    public static final String[] WORD_POS = {"قبل الرقم", "بعد الرقم", "آخر السطر"};

    /** موضع «لنا/لكم» في شكل السطر: 0 قبل الرقم، 1 بعده مباشرة، 2 في آخر السطر (أو موضع حر آخر). */
    public static int wordPos(String format) {
        String f = validLine(format);
        int w = f.indexOf("{كلمة}"), a = f.indexOf("{مبلغ}");
        if (w < 0) return 2;
        if (w < a) return 0;
        return f.substring(a + "{مبلغ}".length(), w).trim().isEmpty() ? 1 : 2;
    }

    /** ينقل خانة «لنا/لكم» إلى الموضع المطلوب ويترك باقي الشكل كما كتبه المستخدم. */
    public static String withWordPos(String format, int pos) {
        String f = validLine(format).replace("{كلمة}", "").replaceAll(" {2,}", " ").trim();
        if (pos == 0) return f.replace("{مبلغ}", "{كلمة} {مبلغ}");
        if (pos == 1) return f.replace("{مبلغ}", "{مبلغ} {كلمة}");
        return f + " {كلمة}";
    }

    /** شكل بلا خانة المبلغ ليس رسالة مطابقة: يعود للافتراضي (لا تُرسل رسالة بلا أرقام). */
    public static String validLine(String f) {
        return f == null || !f.contains("{مبلغ}") ? LINE_AMOUNT_FIRST : f;
    }

    /** 59,450.00 → 59,450 و12.50 → 12.5 و0.00 → 0؛ ما بلا كسور يبقى كما هو. */
    public static String trimZeros(String a) {
        if (a == null || a.indexOf('.') < 0) return a;
        int e = a.length();
        while (e > 0 && a.charAt(e - 1) == '0') e--;
        if (e > 0 && a.charAt(e - 1) == '.') e--;
        return a.substring(0, e);
    }

    /** سطر عملة واحدة حسب شكل القالب (قد يكون أكثر من سطر). */
    public static String line(Template t, Row r) {
        String mark = r.sign() > 0 ? t.markPlus : r.sign() < 0 ? t.markMinus : t.markZero;
        String name = r.label(t.showCode);
        String out = validLine(t.lineFormat).replace("\\n", "\n")
                .replace("{علامة}", Model.nz(mark).trim())
                .replace("{مبلغ}", b(t, t.amount(r)))
                .replace("{عملة}", t.boldName ? b(t, name) : name)
                .replace("{رمز}", r.code)
                .replace("{كلمة}", word(t, r));
        // خانة فارغة (علامة محذوفة مثلًا) لا تترك فراغين ولا فراغًا أول السطر
        StringBuilder sb = new StringBuilder();
        for (String ln : out.split("\n", -1)) {
            String x = ln.replaceAll(" {2,}", " ").trim();
            if (x.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(x);
        }
        return sb.toString();
    }



    /** سطر عملة في الرسالة. */
    public static final class Row {
        public final String code, name;
        public final long minor;
        public final int decimals;

        public Row(String code, String name, long minor, int decimals) {
            this.code = code;
            this.name = name;
            this.minor = minor;
            this.decimals = decimals;
        }

        /** 1 لنا، -1 لكم، 0 صفر. */
        public int sign() { return Long.signum(minor); }

        public String amount() { return Model.fmtFixed(Math.abs(minor), decimals); }

        public String label(boolean showCode) {
            String n = name == null || name.trim().isEmpty() ? code : name.trim();
            return showCode && !n.equals(code) ? n + " (" + code + ")" : n;
        }
    }

    /** يحوّل الأرصدة إلى أسطر بترتيب العملات، مع تصفية العملات المختارة والأصفار. */
    public static List<Row> rows(Map<String, Long> balances, Map<String, String> names, Map<String, Integer> decimals,
                                 Set<String> only, boolean showZero) {
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : balances.entrySet()) {
            String code = e.getKey();
            if (only != null && !only.contains(code)) continue;
            long v = e.getValue() == null ? 0 : e.getValue();
            if (v == 0 && !showZero) continue;
            Integer d = decimals.get(code);
            out.add(new Row(code, names.get(code), v, d == null ? 2 : d));
        }
        return out;
    }

    public static String word(Template t, Row r) {
        return r.sign() > 0 ? t.plusWord : r.sign() < 0 ? t.minusWord : (t.zeroWord.trim().isEmpty() ? "صفر" : t.zeroWord.trim());
    }

    private static String b(Template t, String s) {
        return t.bold && !s.trim().isEmpty() ? "*" + s.trim() + "*" : s;
    }

    /** نص الرسالة الجاهز للصق في واتساب (النجوم = خط عريض في واتساب). */
    public static String text(Template t, String officeName, String issuer, long at, List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        if (!t.greeting.trim().isEmpty()) sb.append(t.greeting.trim()).append("\n\n");
        String orn = t.ornament == null ? "" : t.ornament.trim();
        sb.append(orn.isEmpty() ? "" : orn + " ").append(b(t, t.title)).append(orn.isEmpty() ? "" : " " + orn).append("\n\n");
        if (t.showDate) sb.append(t.dateLabel.trim().isEmpty() ? "" : t.dateLabel.trim() + " ").append(Model.date(at)).append("\n");
        if (t.showOffice) sb.append(t.officeMark.trim().isEmpty() ? "" : t.officeMark.trim() + " ").append(t.salutation.trim().isEmpty() ? "" : t.salutation.trim() + " ")
                .append(b(t, Model.nz(officeName))).append(t.honorific.trim().isEmpty() ? "" : " " + t.honorific.trim()).append("\n");
        String sep = t.showSeparator ? (t.separator.trim().isEmpty() ? LINE : t.separator.trim()) : "";
        sb.append(sep).append("\n");
        if (rows.isEmpty()) sb.append(t.emptyText.trim()).append("\n");
        boolean first = true;
        for (Row r : rows) {
            if (!first && t.gap) sb.append("\n");
            first = false;
            sb.append(line(t, r)).append("\n");
        }
        sb.append(sep).append("\n");
        if (!t.footer.trim().isEmpty()) sb.append(t.footer.trim()).append("\n");
        if (t.showIssuer && issuer != null && !issuer.trim().isEmpty()) sb.append("\n").append(issuer.trim());
        return sb.toString().trim();
    }

    /** أمثلة للمعاينة في شاشة القالب. */
    public static List<Row> sampleRows() {
        List<Row> l = new ArrayList<>();
        l.add(new Row("SYP", "ليرة سورية (جديد)", 5945000L, 2));
        l.add(new Row("USD", "دولار أمريكي", -781900L, 2));
        l.add(new Row("EUR", "يورو أوروبي", 875000L, 2));
        return l;
    }
}
