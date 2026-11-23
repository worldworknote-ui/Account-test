package com.smart.hawala;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 12.5: المعادن الثمينة (ذهب، فضة، بلاتين، بلاديوم…) حسابات بالوزن كالعملات تمامًا.
 * <p>
 * المنطق الصارم:
 * <ul>
 * <li>كل معدن بعياره حساب مستقل (ذهب 21 غير ذهب 24): لا تحويل خفي بينها؛ التحويل بـ«قص» بنسبة ظاهرة.</li>
 * <li>الوحدة (غرام، كيلو، أونصة، مثقال…) ومنازلها تُختار عند الإنشاء ولا تتغير بعد أول حركة (تغييرها يغيّر معنى كل وزن مسجّل).</li>
 * <li>الوزن في الرسالة بوحدة أخرى («2 كيلو») يُحوَّل إلى وحدة الحساب بحساب دقيق، ويُنبَّه المحاسب إن احتاج تقريبًا.</li>
 * <li>العيار غير الواضح حين يكون للمعدن أكثر من حساب: لا يُخمَّن (يختاره المحاسب).</li>
 * <li>التقويم بالدولار اختياري لكل معدن (يختاره المحاسب)، بسعر يدخله لوحدة واحدة، وسعر أونلاين للاستئناس إن توفر.</li>
 * </ul>
 * منطق بحت بلا أندرويد حتى يُختبر.
 */
public final class Metals {
    private Metals() {}

    // ===================================================================================
    // المعادن والوحدات
    // ===================================================================================

    public static final String GOLD = "XAU", SILVER = "XAG", PLATINUM = "XPT", PALLADIUM = "XPD", OTHER = "XMT";
    public static final String[] BASES = {GOLD, SILVER, PLATINUM, PALLADIUM, OTHER};

    public static String baseName(String b) {
        switch (b == null ? "" : b) {
            case GOLD: return "ذهب";
            case SILVER: return "فضة";
            case PLATINUM: return "بلاتين";
            case PALLADIUM: return "بلاديوم";
            default: return "معدن";
        }
    }

    /** وحدة وزن: رمزها، اسمها، غراماتها، ومنازلها العشرية في الحساب. */
    public static final class Unit {
        public final String id, name, shortName;
        public final BigDecimal grams;
        public final int decimals;

        Unit(String id, String name, String shortName, String grams, int decimals) {
            this.id = id;
            this.name = name;
            this.shortName = shortName;
            this.grams = new BigDecimal(grams);
            this.decimals = decimals;
        }
    }

    public static final Map<String, Unit> UNITS;

    static {
        Map<String, Unit> m = new LinkedHashMap<>();
        m.put("g", new Unit("g", "غرام", "غ", "1", 3));
        m.put("kg", new Unit("kg", "كيلوغرام", "كغ", "1000", 6));
        m.put("oz", new Unit("oz", "أونصة (تروي)", "أونصة", "31.1034768", 4));
        m.put("mithqal", new Unit("mithqal", "مثقال", "مثقال", "4.25", 3));
        m.put("tola", new Unit("tola", "تولة", "تولة", "11.6638038", 3));
        m.put("ct", new Unit("ct", "قيراط وزن", "قيراط", "0.2", 2));
        UNITS = Collections.unmodifiableMap(m);
    }

    public static Unit unit(String id) {
        Unit u = UNITS.get(id == null ? "" : id);
        return u == null ? UNITS.get("g") : u;
    }

    /** العيار بالألف من القيراط الذهبي (24 = 999، 21 = 875…)، أو ‰ نفسه إن كتبه هكذا. */
    public static int finenessOfKarat(int k) {
        switch (k) {
            case 24: return 999;
            case 23: return 958;
            case 22: return 916;
            case 21: return 875;
            case 20: return 833;
            case 18: return 750;
            case 16: return 666;
            case 14: return 585;
            case 12: return 500;
            case 10: return 417;
            case 9: return 375;
            case 8: return 333;
            default: return 0;
        }
    }

    public static int karatOf(int fineness) {
        for (int k = 24; k >= 8; k--) if (finenessOfKarat(k) == fineness) return k;
        return 0;
    }

    /** وصف العيار للعرض: «عيار 21» للذهب، «925» لغيره. */
    public static String purityLabel(String base, int fineness) {
        if (fineness <= 0) return "";
        int k = karatOf(fineness);
        return GOLD.equals(base) && k > 0 ? "عيار " + k : String.valueOf(fineness);
    }

    /** رمز مقترح: XAU21، XAG925، XPT950… */
    public static String suggestCode(String base, int fineness) {
        if (fineness <= 0) return base;
        int k = karatOf(fineness);
        return base + (GOLD.equals(base) && k > 0 ? String.valueOf(k) : String.valueOf(fineness));
    }

    /** 12.6: العيارات الشائعة لكل معدن (أزرار سريعة في نافذة المعدن). */
    public static String[] commonPurities(String base) {
        switch (base == null ? "" : base) {
            case GOLD: return new String[]{"24", "22", "21", "18", "14"};
            case SILVER: return new String[]{"999", "925", "900", "800"};
            case PLATINUM: return new String[]{"999", "950", "900"};
            case PALLADIUM: return new String[]{"999", "950", "500"};
            default: return new String[0];
        }
    }

    /** 12.6: لاحقة الوحدة في الرمز حين يتكرر المعدن والعيار بوحدة أخرى. */
    static String unitSuffix(String unitId) {
        switch (unitId == null ? "" : unitId) {
            case "kg": return "K";
            case "oz": return "OZ";
            case "mithqal": return "M";
            case "tola": return "T";
            case "ct": return "C";
            default: return "G";
        }
    }

    /**
     * 12.6: رمز مقترح لا يتكرر مع الموجود: XAU21، ثم XAU21K لوحدة أخرى، ثم XAU21K2… حتى لا يحتاج المحاسب أن يفكر بالرمز
     * ليضيف عيارًا ثانيًا.
     */
    public static String uniqueCode(String base, int fineness, String unitId, java.util.Collection<String> taken) {
        java.util.Set<String> t = new java.util.HashSet<>();
        for (String x : taken) t.add(x.toUpperCase(Locale.US));
        String c = suggestCode(base, fineness);
        if (!t.contains(c)) return c;
        String c2 = c + unitSuffix(unitId);
        if (!t.contains(c2) && c2.length() <= 10) return c2;
        for (int i = 2; i < 100; i++) {
            String c3 = (c2.length() <= 8 ? c2 : c2.substring(0, 8)) + i;
            if (!t.contains(c3)) return c3;
        }
        return c2;
    }

    public static String suggestName(String base, int fineness, String unitId) {
        String p = purityLabel(base, fineness);
        return baseName(base) + (p.isEmpty() ? "" : " " + p) + " (" + unit(unitId).name + ")";
    }

    /** 12.6: «الوزن» للمعدن و«المبلغ» للعملة (في النماذج والمراجعة والتفاصيل). */
    public static String qtyWord(String code) { return isMetal(code) ? "الوزن" : "المبلغ"; }

    /** 12.6: وحدة المعدن ووصفه بدل الرمز: «غ ذهب عيار 21»؛ للعملة رمزها كما هو. */
    public static String unitText(String code) {
        Metal m = of(code);
        if (m == null) return code == null ? "" : code;
        String p = purityLabel(m.base, m.fineness);
        return m.u().shortName + " " + baseName(m.base) + (p.isEmpty() ? "" : " " + p);
    }

    /** 12.6: مبلغ منسّق مع وحدته: «500 غ ذهب عيار 21» أو «500 USD». */
    public static String show(String amountText, String code) { return amountText + " " + unitText(code); }

    /** حساب معدن واحد. */
    public static final class Metal {
        public final String code, base, unit;
        public final int fineness;
        public final boolean valued;

        public Metal(String code, String base, String unit, int fineness, boolean valued) {
            this.code = code;
            this.base = base == null || base.isEmpty() ? OTHER : base;
            this.unit = UNITS.containsKey(unit) ? unit : "g";
            this.fineness = Math.max(0, fineness);
            this.valued = valued;
        }

        public Unit u() { return unit(unit); }
    }

    // ===================================================================================
    // السجل (يملؤه Db من جدول العملات، ويقرؤه المحلّل والتقويم)
    // ===================================================================================

    private static final Map<String, Metal> REG = new ConcurrentHashMap<>();

    public static void set(List<Metal> all) {
        Map<String, Metal> m = new LinkedHashMap<>();
        for (Metal x : all) m.put(x.code, x);
        REG.keySet().retainAll(m.keySet());
        REG.putAll(m);
    }

    public static Metal of(String code) { return code == null ? null : REG.get(code); }

    public static boolean isMetal(String code) { return of(code) != null; }

    /** معدن لا يدخل التقويم بالدولار (بالوزن فقط، باختيار المحاسب). */
    public static boolean notValued(String code) {
        Metal m = of(code);
        return m != null && !m.valued;
    }

    /** وصف وحدة الحساب بعد المبلغ («غ»…)، أو فارغ لغير المعادن. */
    public static String unitShort(String code) {
        Metal m = of(code);
        return m == null ? "" : m.u().shortName;
    }

    // ===================================================================================
    // التحويل
    // ===================================================================================

    /** قيمة بوحدة إلى وحدة أخرى بالضبط، ثم إلى منازل الهدف. exact[0] = لم يحتج تقريبًا. */
    public static BigDecimal convert(BigDecimal v, String from, String to, boolean[] exact) {
        Unit a = unit(from), b = unit(to);
        BigDecimal grams = v.multiply(a.grams);
        BigDecimal out = grams.divide(b.grams, 12, RoundingMode.HALF_EVEN).stripTrailingZeros();
        BigDecimal r = out.setScale(b.decimals, RoundingMode.HALF_EVEN);
        if (exact != null && exact.length > 0) exact[0] = r.compareTo(out) == 0;
        return r;
    }

    /** سعر الأونصة الصافية بالدولار ← سعر وحدة الحساب (بعياره). */
    public static BigDecimal usdPerUnitFromOunce(BigDecimal usdPerOunce, Metal m) {
        if (usdPerOunce == null || m == null) return null;
        BigDecimal perGram = usdPerOunce.divide(UNITS.get("oz").grams, 16, RoundingMode.HALF_EVEN);
        BigDecimal f = m.fineness > 0 ? BigDecimal.valueOf(m.fineness).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_EVEN) : BigDecimal.ONE;
        return perGram.multiply(m.u().grams).multiply(f).setScale(10, RoundingMode.HALF_EVEN);
    }

    // ===================================================================================
    // في الرسائل
    // ===================================================================================

    private static final Map<String, String> BASE_WORDS = new LinkedHashMap<>();

    static {
        for (String w : new String[]{"ذهب", "الذهب", "دهب", "الدهب", "ذهبي", "كسر ذهب", "gold", "altın", "altin", "aur"}) BASE_WORDS.put(w, GOLD);
        for (String w : new String[]{"فضة", "فضه", "الفضة", "الفضه", "silver", "gümüş", "gumus", "argint"}) BASE_WORDS.put(w, SILVER);
        for (String w : new String[]{"بلاتين", "البلاتين", "platinum", "platin"}) BASE_WORDS.put(w, PLATINUM);
        for (String w : new String[]{"بلاديوم", "البلاديوم", "palladium", "paladyum"}) BASE_WORDS.put(w, PALLADIUM);
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");

    /** المعدن المذكور في النص، أو null. */
    public static String baseIn(String text) {
        String s = norm(text);
        Matcher m = WORD.matcher(s);
        while (m.find()) {
            String b = BASE_WORDS.get(m.group());
            if (b != null) return b;
        }
        return null;
    }

    private static final Pattern KARAT = Pattern.compile("(?iu)(?:عيار|العيار)\\s*(\\d{1,2})(?!\\d)|(?<![\\d.,])(\\d{1,2})\\s*(?:قيراط|عيار|k|kt|karat|ayar|ayarı)(?![\\p{L}])");
    private static final Pattern MILLE = Pattern.compile("(?<![\\d.,])(999|9999|995|990|958|950|925|916|900|875|835|800|750|585|500|375)(?![\\d.,])");

    /** العيار بالألف المذكور في النص (من «عيار 21» أو «18k» أو «925»)، أو 0. */
    public static int finenessIn(String text) {
        String s = norm(text);
        Matcher m = KARAT.matcher(s);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            int f = finenessOfKarat(Integer.parseInt(g));
            if (f > 0) return f;
        }
        m = MILLE.matcher(s);
        if (m.find()) return "9999".equals(m.group(1)) ? 999 : Integer.parseInt(m.group(1));
        return 0;
    }

    private static final Map<String, String> UNIT_WORDS = new LinkedHashMap<>();

    static {
        for (String w : new String[]{"غرام", "غرامات", "غراما", "غم", "غ", "جرام", "جرامات", "جم", "جرام", "gram", "grams", "gr", "g", "gramm", "gram"}) UNIT_WORDS.put(w, "g");
        for (String w : new String[]{"كيلو", "كيلوغرام", "كيلوجرام", "كيلوات", "كغ", "كجم", "kg", "kilo", "kilos", "kilogram"}) UNIT_WORDS.put(w, "kg");
        for (String w : new String[]{"أونصة", "اونصة", "أونصه", "اونصه", "اونصات", "أونصات", "اونس", "أونس", "oz", "ounce", "ounces", "ons"}) UNIT_WORDS.put(w, "oz");
        for (String w : new String[]{"مثقال", "مثاقيل", "مثقالا", "mithqal", "miskal", "miskal"}) UNIT_WORDS.put(w, "mithqal");
        for (String w : new String[]{"تولة", "توله", "تولات", "tola", "tolas"}) UNIT_WORDS.put(w, "tola");
    }

    private static final Pattern NUM_UNIT;

    static {
        StringBuilder alt = new StringBuilder();
        List<String> ws = new ArrayList<>(UNIT_WORDS.keySet());
        ws.sort((p, q) -> q.length() - p.length());
        for (String w : ws) {
            if (alt.length() > 0) alt.append('|');
            alt.append(Pattern.quote(w));
        }
        NUM_UNIT = Pattern.compile("(?iu)(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s*(" + alt + ")(?![\\p{L}])|(?<![\\p{L}])(" + alt + ")\\s*(\\d+(?:[.,]\\d+)?)(?![\\d.,])");
    }

    /** الوزن ووحدته المذكوران في النص («2.5 كيلو»، «غرام 50»)، أو null. */
    public static String[] weightIn(String text) {
        Matcher m = NUM_UNIT.matcher(norm(text));
        while (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(4);
            String w = m.group(2) != null ? m.group(2) : m.group(3);
            String u = UNIT_WORDS.get(w.toLowerCase(Locale.ROOT));
            if (u == null || num == null) continue;
            // رقم العيار ليس وزنًا («21 غ» بعد «عيار» نادر؛ «عيار 21 غرام 50»: الوزن 50)
            int at = m.start();
            String before = norm(text).substring(Math.max(0, at - 7), at);
            if (before.matches("(?s).*(?:عيار|العيار)\\s*$") && m.group(1) != null) continue;
            return new String[]{num.replace(',', '.'), u};
        }
        return null;
    }

    /**
     * يكمل نتيجة المحلّل لرسالة عن معدن: يختار حساب المعدن بعياره (أو يتركه للمحاسب إن لم يُعرف)،
     * ويحوّل الوزن إلى وحدة الحساب.
     */
    public static void apply(MessageParser.Result r, String text, List<MessageParser.Cur> curs) {
        if (r == null || text == null || curs == null) return;
        List<Metal> mine = new ArrayList<>();
        for (MessageParser.Cur c : curs) {
            Metal m = of(c.code);
            if (m != null) mine.add(m);
        }
        if (mine.isEmpty()) return;
        String base = baseIn(text);
        Metal picked = r.currency == null ? null : of(r.currency);
        if (base == null && picked == null) return;
        if (base == null) base = picked.base;
        int fin = finenessIn(text);
        List<Metal> same = new ArrayList<>();
        for (Metal m : mine) if (m.base.equals(base)) same.add(m);
        Metal chosen = null;
        if (fin > 0) {
            for (Metal m : same) if (m.fineness == fin) chosen = m;
        } else if (same.size() == 1) chosen = same.get(0);
        String[] w = weightIn(text);
        if (chosen == null) {
            if (!same.isEmpty()) {
                // صرامة: لا نخمّن العيار
                if (picked != null && picked.base.equals(base)) r.currency = null;
                if (r.currency != null && of(r.currency) != null) r.currency = null;
                r.amountNote = (r.amountNote.isEmpty() ? "" : r.amountNote + " — ") + (fin > 0
                        ? "لا يوجد حساب " + baseName(base) + " " + purityLabel(base, fin) + " لهذا المكتب: اختر الحساب بيدك"
                        : "لم يُذكر عيار " + baseName(base) + ": اختر الحساب بيدك");
                r.currencyWord = baseName(base);
            }
            if (w != null && r.amount.isEmpty()) r.amount = w[0];
            return;
        }
        r.currency = chosen.code;
        r.currencyWord = baseName(base);
        if (w != null) {
            boolean[] ex = {true};
            BigDecimal v = convert(new BigDecimal(w[0]), w[1], chosen.unit, ex);
            String plain = v.stripTrailingZeros().toPlainString();
            r.amount = plain;
            r.amountText = w[0] + " " + unit(w[1]).shortName;
            if (!w[1].equals(chosen.unit))
                r.amountNote = (r.amountNote.isEmpty() ? "" : r.amountNote + " — ") + "حُوّل " + w[0] + " " + unit(w[1]).shortName + " = " + plain + " "
                        + chosen.u().shortName + (ex[0] ? "" : " (بتقريب إلى " + chosen.u().decimals + " منازل: راجعه)");
        } else if (fin > 0 && r.amount.equals(String.valueOf(karatOf(fin)))) {
            // الرقم الذي التقطه المحلّل هو العيار لا الوزن
            r.amount = "";
        }
    }

    static String norm(String t) {
        return MessageParser.digits(t == null ? "" : t).toLowerCase(Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace("اونصة", "اونصة").replace('ـ', ' ');
    }
}
