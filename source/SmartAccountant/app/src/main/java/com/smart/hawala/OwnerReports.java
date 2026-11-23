package com.smart.hawala;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 8.0: تقارير صاحب المكتب (منطق بحت يُختبر على الكمبيوتر) من الحركات نفسها، دون تسجيل شيء جديد:
 * حجم العمل الشهري ونموه، وتركّز العمل، وعمر الأرصدة، والحسابات الخاملة، والمكاتب الصاعدة والمتراجعة والجديدة،
 * والأجور لنا وعلينا وفرق العملة، والمرتجعات والتصحيحات، والخدمات والوجهات، وحجم الحوالة، وجودة التسجيل.
 *
 * <p>القواعد: تُحسب الحركات النشطة فقط (الملغاة والمرفوضة وبانتظار الاعتماد خارج الأرقام). عمر الرصيد بطريقة «الأقدم يُسدَّد أولًا»:
 * الرصيد الحالي يُنسب إلى أحدث الحركات التي صنعته. الأجور من خانة الأجرة، ومن الحركات التي بيانها «أجور» (كما في الملفات المنقولة).
 */
public final class OwnerReports {
    private OwnerReports() {}

    public static final long DAY = 86400000L;

    public static final class Opening {
        public long office, signed, when;
        public String currency;

        public Opening(long office, String currency, long signed, long when) {
            this.office = office;
            this.currency = currency;
            this.signed = signed;
            this.when = when;
        }
    }

    public static final class Input {
        public long now;
        public List<Model.Tx> txs = new ArrayList<>();
        public List<Opening> openings = new ArrayList<>();
        public Map<Long, String> names = new HashMap<>();
        public Map<String, Integer> decimals = new HashMap<>();
        /** قيمة وحدة العملة بالدولار (لفرق العملة)؛ اختياري. */
        public Map<String, BigDecimal> usdRates = new HashMap<>();
    }

    // ===================================================================================
    // أدوات
    // ===================================================================================

    static boolean active(Model.Tx t) { return t.isActive(); }

    static int dec(Input in, String c) {
        Integer d = in.decimals.get(c);
        return d == null ? 2 : d;
    }

    static String name(Input in, long office) {
        String n = in.names.get(office);
        return n == null || n.isEmpty() ? "مكتب " + office : n;
    }

    /** للمقارنة فقط: بلا تشكيل، وتوحيد الألف والتاء المربوطة والياء، ومسافات مفردة. */
    public static String norm(String s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "");
        x = x.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي');
        return x.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final java.util.regex.Pattern FEE = java.util.regex.Pattern.compile("^(ال)?(اجور|اجره|اجر(?!\\p{L}))|^عمول");

    /**
     * حركة أجور مستقلة: يبدأ بيانها بـ«أجور» أو «أجرة» أو «اجر5436» أو «عمولة»، أو يذكر «راجع».
     * حوالة بيانها «عمان مع أجور» ليست أجرة: مبلغها مبلغ الحوالة.
     */
    public static boolean feeRow(Model.Tx t) {
        String d = norm(t.description);
        return FEE.matcher(d).find() || RAJE.matcher(d).find();
    }

    /**
     * «راجع» بلغة المكاتب: أجرة على حوالة (يدفعها المكتب لنا حين تكون الحركة «لنا»، وندفعها له حين تكون «علينا»).
     * «راجع154 الف» = الأجرة عن حوالة بـ154 ألفًا، ومبلغ الحركة هو الأجرة نفسها.
     */
    static final java.util.regex.Pattern RAJE = java.util.regex.Pattern.compile("(^|[^\\p{L}])(ال)?راجع(?!\\p{L})");

    public static boolean rajeRow(Model.Tx t) { return RAJE.matcher(norm(t.description)).find(); }

    private static final java.util.regex.Pattern RAJE_BASE = java.util.regex.Pattern.compile(
            "راجع\\s*(\\d+(?:[.,]\\d+)?)\\s*(الف|الاف|اف|الفين)?((?:\\s*\\+\\s*\\d+(?:[.,]\\d+)?\\s*(?:الف|الاف|اف)?)*)");
    private static final java.util.regex.Pattern PLUS_TERM = java.util.regex.Pattern.compile("\\+\\s*(\\d+(?:[.,]\\d+)?)\\s*(الف|الاف|اف)?");

    /**
     * المبلغ المذكور بعد «راجع» (حجم الحوالة التي هذه أجرتها) بوحدات العملة، أو null إن لم يُذكر.
     * «راجع154 الف» = 154,000 · «راجع 5000» = 5,000 · «راجع35 الف+ 50125» = 85,125.
     */
    public static BigDecimal rajeBase(String description) {
        String d = norm(description);
        StringBuilder ascii = new StringBuilder(d.length());
        for (char ch : d.toCharArray()) ascii.append(ch >= '٠' && ch <= '٩' ? (char) ('0' + ch - '٠') : ch);
        java.util.regex.Matcher m = RAJE_BASE.matcher(ascii);
        if (!m.find()) return null;
        BigDecimal v = term(m.group(1), m.group(2));
        java.util.regex.Matcher p = PLUS_TERM.matcher(m.group(3) == null ? "" : m.group(3));
        while (p.find()) v = v.add(term(p.group(1), p.group(2)));
        return v.signum() > 0 ? v : null;
    }

    private static BigDecimal term(String num, String unit) {
        BigDecimal v = new BigDecimal(num.replace(',', '.'));
        return unit != null && !unit.isEmpty() ? v.multiply(BigDecimal.valueOf(1000)) : v;
    }

    /**
     * نسبة الراجع الفعلية (بالمئة) = الأجرة ÷ المبلغ المذكور. إن جاءت أكبر من 10% دون كلمة «ألف»
     * فالأرجح أن المذكور بالآلاف («راجع33» على أجرة 66) فيُضرب بألف؛ وإن بقيت غير معقولة تُهمل.
     */
    public static BigDecimal rajeRate(Model.Tx t, int decimals) {
        BigDecimal base = rajeBase(t.description);
        if (base == null) return null;
        BigDecimal fee = BigDecimal.valueOf(t.amount, decimals);
        BigDecimal r = fee.multiply(BigDecimal.valueOf(100)).divide(base, 4, java.math.RoundingMode.HALF_UP);
        if (r.compareTo(BigDecimal.TEN) > 0) r = r.divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_UP);
        return r.compareTo(BigDecimal.TEN) > 0 || r.signum() <= 0 ? null : r;
    }

    /** حوالة راجعة (أُعيدت): البيان يذكر الإرجاع. «راجع» وحدها أجرة لا إرجاع. */
    public static boolean returnRow(Model.Tx t) {
        String d = norm(t.description);
        return d.contains("مرتجع") || d.contains("راجعه") || d.contains("رجعت") || d.contains("رجوع") || d.contains("ارجاع") || d.contains("مردود");
    }

    static int monthKey(long t) {
        Calendar c = Model.cal();
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1;
    }

    static String monthLabel(int k) { return (k / 100) + "/" + String.format(Locale.US, "%02d", k % 100); }

    static int prevMonth(int k, int n) {
        int y = k / 100, m = k % 100 - n;
        while (m <= 0) {
            m += 12;
            y--;
        }
        return y * 100 + m;
    }

    static String pct(long part, long whole) {
        if (whole == 0) return "—";
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(whole), 1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    static Report.Money money(Input in, String c, long v) { return new Report.Money(v, dec(in, c)); }

    /** العدد مع المعدود بالعربية: مكتب واحد، مكتبان، 3 مكاتب، 11 مكتبًا. */
    public static String count(long n, String one, String two, String plural, String acc) {
        if (n == 0) return "لا شيء";
        if (n == 1) return one + " واحد";
        if (n == 2) return two;
        long r = n % 100;
        return n + " " + (r >= 3 && r <= 10 ? plural : acc);
    }

    static String offices(long n) { return count(n, "مكتب", "مكتبان", "مكاتب", "مكتبًا"); }

    static String fmt(Input in, String c, long v) { return "\u2066" + Model.fmt(v, dec(in, c)) + " " + c + "\u2069"; }

    /** أثر الحركة على رصيد المكتب (لنا موجب): الأصل ثم الأجرة. */
    static void effects(Model.Tx t, List<Object[]> out) {
        out.add(new Object[]{t.currency, Model.TYPE_DEBIT.equals(t.type) ? t.amount : -t.amount});
        if (t.hasFee()) out.add(new Object[]{t.feeCurrency, Model.FEE_US.equals(t.feeType) ? t.fee : -t.fee});
    }

    /** المكتب ← العملة ← الرصيد، من الحركات النشطة والأرصدة السابقة. */
    public static Map<Long, Map<String, Long>> balances(Input in) {
        Map<Long, Map<String, Long>> b = new TreeMap<>();
        List<Object[]> fx = new ArrayList<>();
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            fx.clear();
            effects(t, fx);
            for (Object[] e : fx) b.computeIfAbsent(t.officeId, k -> new TreeMap<>()).merge((String) e[0], (Long) e[1], Long::sum);
        }
        for (Opening o : in.openings) b.computeIfAbsent(o.office, k -> new TreeMap<>()).merge(o.currency, o.signed, Long::sum);
        return b;
    }

    static List<String> currenciesByVolume(Input in, long from) {
        Map<String, Long> v = new HashMap<>();
        for (Model.Tx t : in.txs) if (active(t) && t.createdAt >= from) v.merge(t.currency, 1L, Long::sum);
        List<String> l = new ArrayList<>(v.keySet());
        Collections.sort(l, (a, b) -> Long.compare(v.get(b), v.get(a)));
        return l;
    }

    // ===================================================================================
    // 1) حجم العمل الشهري والنمو
    // ===================================================================================

    /**
     * النمو بأشهر كاملة: آخر 12 شهرًا كاملًا (حتى الشهر الماضي) مقابل الـ12 قبلها، ومتوسط العمليات الشهري لكل منهما،
     * ومجموع المبالغ لكل عملة (دون حركات الأجور المستقلة). الشهر الجاري غير مكتمل فلا يدخل.
     */
    public static final class Growth {
        public int lastMonth, firstMonth, prevFirst;
        public long opsLastMonth, opsYearAgo, ops12, opsPrev12;
        public final Map<String, long[]> volume12 = new TreeMap<>();   // العملة ← {آخر 12 شهرًا، الـ12 قبلها}

        /**
         * 10.16: عدد الأشهر الكاملة التي فيها بيانات فعلًا داخل نافذة الـ12 (من أول حركة في الدفتر). مكتب بدأ قبل 3 أشهر
         * يُقسم على 3 لا على 12، وإلا ظهر متوسطه ربع الحقيقة.
         */
        public int months12 = 12, monthsPrev = 12;
        /** هل في السنة السابقة بيانات تصلح للمقارنة؟ */
        public boolean comparable;

        public long avg12() { return Math.round(ops12 / (double) Math.max(1, months12)); }

        public long avgPrev12() { return Math.round(opsPrev12 / (double) Math.max(1, monthsPrev)); }

        /** «آخر 12 شهرًا» أو «آخر 3 أشهر» حسب عمر البيانات. */
        public String span() { return months12 >= 12 ? "آخر 12 شهرًا" : months12 == 1 ? "الشهر الماضي" : months12 == 2 ? "آخر شهرين" : "آخر " + months12 + " أشهر"; }
    }

    static int monthsBetween(int from, int to) {
        return (to / 100 - from / 100) * 12 + (to % 100 - from % 100);
    }

    public static Growth growth(Input in) {
        Growth g = new Growth();
        g.lastMonth = prevMonth(monthKey(in.now), 1);
        g.firstMonth = prevMonth(g.lastMonth, 11);
        g.prevFirst = prevMonth(g.lastMonth, 23);
        int yearAgo = prevMonth(g.lastMonth, 12);
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            int m = monthKey(t.createdAt);
            if (m == g.lastMonth) g.opsLastMonth++;
            if (m == yearAgo) g.opsYearAgo++;
            if (m > g.lastMonth || m < g.prevFirst) continue;
            long[] v = g.volume12.computeIfAbsent(t.currency, k -> new long[2]);
            boolean recent = m >= g.firstMonth;
            if (recent) g.ops12++;
            else g.opsPrev12++;
            if (!feeRow(t)) v[recent ? 0 : 1] += t.amount;
        }
        int firstData = Integer.MAX_VALUE;
        for (Model.Tx t : in.txs) if (active(t)) firstData = Math.min(firstData, monthKey(t.createdAt));
        if (firstData != Integer.MAX_VALUE) {
            if (firstData > g.firstMonth) g.months12 = Math.max(1, Math.min(12, monthsBetween(firstData, g.lastMonth) + 1));
            int prevLast = prevMonth(g.firstMonth, 1);
            if (firstData > g.prevFirst) g.monthsPrev = Math.max(1, Math.min(12, monthsBetween(firstData, prevLast) + 1));
            // المقارنة بالسنة السابقة صادقة حين تغطي بياناتها ستة أشهر على الأقل
            g.comparable = g.opsPrev12 > 0 && firstData <= prevMonth(prevLast, 5);
        }
        return g;
    }

    static String ratio(long now, long before) {
        if (before == 0) return now == 0 ? "—" : "جديد";
        return "×" + BigDecimal.valueOf(now).divide(BigDecimal.valueOf(before), 1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static void monthly(Report r, Input in, int months) {
        int last = monthKey(in.now);
        List<String> curs = currenciesByVolume(in, in.now - 365 * DAY);
        if (curs.size() > 4) curs = curs.subList(0, 4);
        Map<Integer, long[]> ops = new TreeMap<>();
        Map<Integer, Map<String, Long>> vol = new TreeMap<>();
        int first = prevMonth(last, months - 1);
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            int m = monthKey(t.createdAt);
            if (m < first || m > last) continue;
            ops.computeIfAbsent(m, k -> new long[1])[0]++;
            if (!feeRow(t)) vol.computeIfAbsent(m, k -> new HashMap<>()).merge(t.currency, t.amount, Long::sum);
        }
        Growth g = growth(in);
        Report.Section s = r.section("حجم العمل الشهري", "شهري");
        // 10.16: المتوسط على الأشهر التي فيها بيانات، والمقارنة بالسنة السابقة حين توجد فقط (بلا «مقابل 0 (جديد)»)
        s.kv("متوسط العمليات شهريًا", g.avg12() + " في " + g.span() + (g.comparable ? "، مقابل " + g.avgPrev12() + " في السنة قبلها (" + ratio(g.avg12(), g.avgPrev12()) + ")"
                : " (لا بيانات كافية عن السنة السابقة للمقارنة)"), false);
        s.kv("آخر شهر كامل (" + monthLabel(g.lastMonth) + ")", g.opsLastMonth + " عملية" + (g.opsYearAgo > 0 ? "، مقابل " + g.opsYearAgo + " في الشهر نفسه قبل سنة" : ""), false);
        for (String c : curs) {
            long[] v = g.volume12.get(c);
            if (v != null && (v[0] > 0 || v[1] > 0)) s.kv("المبالغ بـ" + c, fmt(in, c, v[0]) + " في " + g.span()
                    + (g.comparable && v[1] > 0 ? "، مقابل " + fmt(in, c, v[1]) + " في السنة قبلها (" + ratio(v[0], v[1]) + ")" : ""), false);
        }
        s.col("الشهر", 1f, Report.TEXT).col("العمليات", 0.8f, Report.INT);
        for (String c : curs) s.col("المبالغ " + c, 1.3f, Report.MONEY);
        // 10.16: الأشهر السابقة لأول حركة في الدفتر لا تُعرض (كانت صفوف أصفار طويلة)
        int firstShown = first;
        if (!ops.isEmpty()) firstShown = Math.max(first, ((TreeMap<Integer, long[]>) ops).firstKey());
        else firstShown = last;
        for (int m = last; m >= firstShown; m = prevMonth(m, 1)) {
            long[] o = ops.get(m);
            Object[] cells = new Object[2 + curs.size()];
            cells[0] = monthLabel(m);
            cells[1] = o == null ? 0L : o[0];
            Map<String, Long> v = vol.get(m);
            for (int i = 0; i < curs.size(); i++) {
                Long x = v == null ? null : v.get(curs.get(i));
                cells[2 + i] = money(in, curs.get(i), x == null ? 0 : x);
            }
            s.row(m == last ? Report.MUTED : Report.NORMAL, cells);
        }
        s.note = "الشهر الحالي غير مكتمل (بلون باهت). المبالغ دون حركات الأجور المستقلة.";
    }

    // ===================================================================================
    // 2) ترتيب المكاتب وتركّز العمل
    // ===================================================================================

    public static final class Concentration {
        public long total;
        public List<long[]> ranked = new ArrayList<>();   // {office, ops}
        public long top10;
        public final Map<String, Object[]> payTop = new TreeMap<>(), recTop = new TreeMap<>(); // العملة ← {office, amount, total}
    }

    public static Concentration concentration(Input in, long from) {
        Concentration c = new Concentration();
        Map<Long, Long> ops = new HashMap<>();
        for (Model.Tx t : in.txs) if (active(t) && t.createdAt >= from) ops.merge(t.officeId, 1L, Long::sum);
        for (Map.Entry<Long, Long> e : ops.entrySet()) c.ranked.add(new long[]{e.getKey(), e.getValue()});
        Collections.sort(c.ranked, (a, b) -> Long.compare(b[1], a[1]));
        for (int i = 0; i < c.ranked.size(); i++) {
            c.total += c.ranked.get(i)[1];
            if (i < 10) c.top10 += c.ranked.get(i)[1];
        }
        for (Map.Entry<Long, Map<String, Long>> e : balances(in).entrySet()) {
            for (Map.Entry<String, Long> b : e.getValue().entrySet()) {
                long v = b.getValue();
                Map<String, Object[]> m = v > 0 ? c.recTop : c.payTop;
                if (v == 0) continue;
                Object[] x = m.computeIfAbsent(b.getKey(), k -> new Object[]{0L, 0L, 0L});
                x[2] = (Long) x[2] + Math.abs(v);
                if (Math.abs(v) > (Long) x[1]) {
                    x[0] = e.getKey();
                    x[1] = Math.abs(v);
                }
            }
        }
        return c;
    }

    static void concentrationSection(Report r, Input in, long from, String period) {
        Concentration c = concentration(in, from);
        Report.Section s = r.section("ترتيب المكاتب وتركّز العمل", "التركز");
        s.kv("الفترة", period, false);
        s.kv("أكبر " + Math.min(10, c.ranked.size()) + " مكاتب من " + c.ranked.size(), pct(c.top10, c.total) + " من العمليات", true);
        if (!c.ranked.isEmpty()) s.kv("أكبر مكتب: «" + name(in, c.ranked.get(0)[0]) + "»", pct(c.ranked.get(0)[1], c.total) + " وحده", false);
        for (Map.Entry<String, Object[]> e : c.payTop.entrySet()) {
            Object[] x = e.getValue();
            s.kv("أكبر ما عليك بـ" + e.getKey(), "«" + name(in, (Long) x[0]) + "»: " + fmt(in, e.getKey(), (Long) x[1]) + " = " + pct((Long) x[1], (Long) x[2]) + " مما عليك بها", false);
        }
        for (Map.Entry<String, Object[]> e : c.recTop.entrySet()) {
            Object[] x = e.getValue();
            s.kv("أكبر ما لك بـ" + e.getKey(), "«" + name(in, (Long) x[0]) + "»: " + fmt(in, e.getKey(), (Long) x[1]) + " = " + pct((Long) x[1], (Long) x[2]) + " مما لك بها", false);
        }
        s.col("#", 0.4f, Report.INT).col("المكتب", 2f, Report.TEXT).col("العمليات", 0.9f, Report.INT).col("النسبة", 0.8f, Report.TEXT).col("التراكمي", 0.8f, Report.TEXT);
        long cum = 0;
        for (int i = 0; i < c.ranked.size() && i < 30; i++) {
            long[] x = c.ranked.get(i);
            cum += x[1];
            s.row(i < 10 ? Report.BOLD : Report.NORMAL, (long) (i + 1), name(in, x[0]), x[1], pct(x[1], c.total), pct(cum, c.total));
        }
        s.note = "خسارة مكتب كبير واحد تهزّ العمل: ضع حدًا لما يبقى لك أو عليك عند مكتب واحد، وابنِ بدائل على الوجهات نفسها.";
    }

    // ===================================================================================
    // 3) عمر الأرصدة (الأقدم يُسدَّد أولًا)
    // ===================================================================================

    public static final long[] BUCKETS = {30, 90, 180};

    public static final class Aging {
        /** oldestDays: عمر أقدم جزء من الرصيد (الأقدم يُسدَّد أولًا). sinceDays: أيام منذ آخر مرة كان الحساب مسدَّدًا (صفرًا أو بالاتجاه المعاكس). */
        public long office, balance, oldestDays, sinceDays;
        public String currency;
        /** 0-30، 31-90، 91-180، أكثر من 180 يومًا. */
        public final long[] buckets = new long[4];

        public long over90() { return buckets[2] + buckets[3]; }
    }

    /** receivable=true: ما لك (رصيد موجب)؛ false: ما عليك. */
    public static List<Aging> aging(Input in, boolean receivable) {
        Map<String, List<long[]>> moves = new HashMap<>();   // office|currency ← {when, amount} في اتجاه الرصيد
        List<Object[]> fx = new ArrayList<>();
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            fx.clear();
            effects(t, fx);
            for (Object[] e : fx) {
                long v = (Long) e[1];
                if (receivable ? v > 0 : v < 0) moves.computeIfAbsent(t.officeId + "|" + e[0], k -> new ArrayList<>()).add(new long[]{t.createdAt, Math.abs(v)});
            }
        }
        for (Opening o : in.openings) {
            if (receivable ? o.signed > 0 : o.signed < 0) moves.computeIfAbsent(o.office + "|" + o.currency, k -> new ArrayList<>()).add(new long[]{o.when, Math.abs(o.signed)});
        }
        // كل الحركات بترتيبها الزمني لحساب «غير مسدَّد منذ»
        Map<String, List<long[]>> all = new HashMap<>();   // office|currency ← {when, id, signed}
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            fx.clear();
            effects(t, fx);
            for (Object[] e : fx) all.computeIfAbsent(t.officeId + "|" + e[0], k -> new ArrayList<>()).add(new long[]{t.createdAt, t.id, (Long) e[1]});
        }
        for (Opening o : in.openings) all.computeIfAbsent(o.office + "|" + o.currency, k -> new ArrayList<>()).add(new long[]{o.when, -1, o.signed});
        List<Aging> out = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Long>> e : balances(in).entrySet()) {
            for (Map.Entry<String, Long> b : e.getValue().entrySet()) {
                long bal = receivable ? b.getValue() : -b.getValue();
                if (bal <= 0) continue;
                Aging a = new Aging();
                List<long[]> seq = all.getOrDefault(e.getKey() + "|" + b.getKey(), new ArrayList<>());
                Collections.sort(seq, (x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
                long run = 0, start = seq.isEmpty() ? in.now : seq.get(0)[0];
                for (long[] m : seq) {
                    long before = run;
                    run += receivable ? m[2] : -m[2];
                    if (before <= 0 && run > 0) start = m[0];
                }
                a.sinceDays = Math.max(0, (in.now - start) / DAY);
                a.office = e.getKey();
                a.currency = b.getKey();
                a.balance = bal;
                List<long[]> l = moves.getOrDefault(a.office + "|" + a.currency, new ArrayList<>());
                Collections.sort(l, (x, y) -> Long.compare(y[0], x[0]));
                long left = bal;
                for (long[] m : l) {
                    if (left <= 0) break;
                    long take = Math.min(left, m[1]);
                    long days = Math.max(0, (in.now - m[0]) / DAY);
                    a.buckets[days <= BUCKETS[0] ? 0 : days <= BUCKETS[1] ? 1 : days <= BUCKETS[2] ? 2 : 3] += take;
                    a.oldestDays = Math.max(a.oldestDays, days);
                    left -= take;
                }
                if (left > 0) a.buckets[3] += left;   // أقدم من كل حركة مسجّلة
                out.add(a);
            }
        }
        Collections.sort(out, (x, y) -> Long.compare(y.over90(), x.over90()) != 0 ? Long.compare(y.over90(), x.over90()) : Long.compare(y.balance, x.balance));
        return out;
    }

    /** العملة ← {المجموع، لم يُسدَّد بالكامل منذ أكثر من 90 يومًا، الأقدم من 90 يومًا بطريقة الأقدم أولًا}. */
    public static Map<String, long[]> agingTotals(List<Aging> l) {
        Map<String, long[]> tot = new TreeMap<>();
        for (Aging a : l) {
            long[] t = tot.computeIfAbsent(a.currency, k -> new long[3]);
            t[0] += a.balance;
            if (a.sinceDays > 90) t[1] += a.balance;
            t[2] += a.over90();
        }
        return tot;
    }

    static void agingSection(Report r, Input in, boolean receivable) {
        List<Aging> l = aging(in, receivable);
        Report.Section s = r.section(receivable ? "عمر ما لك عند المكاتب" : "عمر ما عليك للمكاتب", receivable ? "عمر لنا" : "عمر علينا");
        for (Map.Entry<String, long[]> e : agingTotals(l).entrySet()) {
            long[] t = e.getValue();
            s.kv((receivable ? "لك بـ" : "عليك بـ") + e.getKey(), fmt(in, e.getKey(), t[0]) + "؛ على حسابات لم تُسدَّد بالكامل منذ أكثر من 90 يومًا " + fmt(in, e.getKey(), t[1])
                    + " (" + pct(t[1], t[0]) + ")؛ والأقدم من 90 يومًا فعلًا " + fmt(in, e.getKey(), t[2]) + " (" + pct(t[2], t[0]) + ")", t[2] > 0);
        }
        s.col("المكتب", 1.8f, Report.TEXT).col("العملة", 0.6f, Report.TEXT).col("الرصيد", 1.1f, Report.MONEY).col("غير مسدَّد منذ (يوم)", 0.9f, Report.INT)
                .col("حتى 30 يومًا", 1f, Report.MONEY).col("31-90", 1f, Report.MONEY).col("91-180", 1f, Report.MONEY).col("أكثر من 180", 1f, Report.MONEY);
        for (Aging a : l) {
            s.row(a.over90() > 0 ? Report.BOLD : Report.NORMAL, name(in, a.office), a.currency, money(in, a.currency, a.balance), a.sinceDays,
                    money(in, a.currency, a.buckets[0]), money(in, a.currency, a.buckets[1]), money(in, a.currency, a.buckets[2]), money(in, a.currency, a.buckets[3]));
        }
        s.note = (receivable ? "«غير مسدَّد منذ»: آخر مرة كان الحساب صفرًا أو لصالح المكتب. والأعمدة تنسب الرصيد إلى أحدث الحركات التي صنعته (الأقدم يُسدَّد أولًا)، "
                + "فتعرف كم منه قديم فعلًا. ابدأ المطالبة بالأقدم، أو خفّض سقف المكتب."
                : "ما بقي عليك قديمًا قد يكون حسابًا انتقل إلى اسم آخر دون قيد تحويل: راجعه أو ادمج الاسمين.");
    }

    // ===================================================================================
    // 4) الحسابات الخاملة التي عليها رصيد
    // ===================================================================================

    public static final class Dormant {
        public long office, days;
        public final Map<String, Long> balances = new TreeMap<>();
    }

    /**
     * رصيد متوقف: رصيد بعملة لم تتحرك (بتلك العملة) منذ أكثر من minDays يومًا، ولو كان المكتب نشطًا بعملة أخرى.
     * days = أيام أقدم رصيد متوقف في المكتب.
     */
    public static List<Dormant> dormant(Input in, int minDays) {
        Map<String, Long> last = new HashMap<>();
        List<Object[]> fx = new ArrayList<>();
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            fx.clear();
            effects(t, fx);
            for (Object[] e : fx) last.merge(t.officeId + "|" + e[0], t.createdAt, Math::max);
        }
        for (Opening o : in.openings) last.merge(o.office + "|" + o.currency, o.when, Math::max);
        List<Dormant> out = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Long>> e : balances(in).entrySet()) {
            Dormant d = new Dormant();
            d.office = e.getKey();
            for (Map.Entry<String, Long> b : e.getValue().entrySet()) {
                Long l = last.get(e.getKey() + "|" + b.getKey());
                if (b.getValue() == 0 || l == null) continue;
                long days = (in.now - l) / DAY;
                if (days <= minDays) continue;
                d.balances.put(b.getKey(), b.getValue());
                d.days = Math.max(d.days, days);
            }
            if (!d.balances.isEmpty()) out.add(d);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.days, a.days));
        return out;
    }

    static void dormantSection(Report r, Input in) {
        List<Dormant> l = dormant(in, 90);
        int n = 0;
        for (Dormant d : l) n += d.balances.size();
        Report.Section s = r.section("حسابات متوقفة عليها رصيد", "خاملة");
        s.kv("أرصدة لم تتحرك بعملتها منذ أكثر من 90 يومًا", count(n, "رصيد", "رصيدان", "أرصدة", "رصيدًا") + " على " + count(l.size(), "حساب", "حسابين", "حسابات", "حسابًا"), n > 0);
        s.col("المكتب", 1.8f, Report.TEXT).col("أقدمها بلا حركة منذ (يوم)", 0.9f, Report.INT).col("الأرصدة المتوقفة", 2.6f, Report.TEXT);
        for (Dormant d : l) {
            List<String> b = new ArrayList<>();
            for (Map.Entry<String, Long> e : d.balances.entrySet()) b.add((e.getValue() > 0 ? "لنا " : "علينا ") + fmt(in, e.getKey(), Math.abs(e.getValue())));
            s.row(Report.NORMAL, name(in, d.office), d.days, Model.join(b, " • "));
        }
        s.note = "مال منسي، أو حساب مؤقت لم يُقفَل، أو اسم مكرر لمكتب واحد (ادمجهما من صفحة المكتب).";
    }

    // ===================================================================================
    // 5) المكاتب الصاعدة والمتراجعة والجديدة (آخر 90 يومًا مقابل الـ90 قبلها)
    // ===================================================================================

    public static final class Trend {
        public long office, last, prev;
        /** rising، declining، new، stopped، back. */
        public String kind;
    }

    public static List<Trend> trends(Input in, int window) {
        long w = window * DAY;
        Map<Long, long[]> m = new HashMap<>();   // {آخر، قبلها، أول حركة}
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            long[] x = m.computeIfAbsent(t.officeId, k -> new long[]{0, 0, Long.MAX_VALUE});
            long age = in.now - t.createdAt;
            if (age >= 0 && age < w) x[0]++;
            else if (age >= w && age < 2 * w) x[1]++;
            x[2] = Math.min(x[2], t.createdAt);
        }
        List<Trend> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : m.entrySet()) {
            long[] x = e.getValue();
            Trend t = new Trend();
            t.office = e.getKey();
            t.last = x[0];
            t.prev = x[1];
            if (x[1] == 0 && x[0] > 0) t.kind = in.now - x[2] < w ? "new" : "back";
            else if (x[1] > 0 && x[0] == 0) t.kind = "stopped";
            else if (x[1] > 0 && x[0] * 3 >= x[1] * 4) t.kind = "rising";
            else if (x[1] > 0 && x[0] * 3 <= x[1] * 2) t.kind = "declining";
            else continue;
            out.add(t);
        }
        Collections.sort(out, (a, b) -> Long.compare(Math.abs(b.last - b.prev), Math.abs(a.last - a.prev)));
        return out;
    }

    static final String[][] TREND_LABELS = {{"declining", "متراجع (نقص أكثر من الثلث)"}, {"stopped", "توقف"}, {"rising", "صاعد (زاد الثلث فأكثر)"},
            {"new", "جديد"}, {"back", "عاد بعد انقطاع"}};

    static void trendSection(Report r, Input in) {
        List<Trend> l = trends(in, 90);
        Report.Section s = r.section("المكاتب الصاعدة والمتراجعة والجديدة", "الاتجاهات");
        Map<String, Integer> cnt = new HashMap<>();
        for (Trend t : l) cnt.merge(t.kind, 1, Integer::sum);
        for (String[] k : TREND_LABELS) if (cnt.containsKey(k[0])) s.kv(k[1], offices(cnt.get(k[0])), "declining".equals(k[0]));
        s.col("المكتب", 1.8f, Report.TEXT).col("الحال", 1.4f, Report.TEXT).col("آخر 90 يومًا", 0.9f, Report.INT).col("الـ90 قبلها", 0.9f, Report.INT).col("التغير", 0.8f, Report.TEXT);
        for (String[] k : TREND_LABELS) {
            for (Trend t : l) {
                if (!k[0].equals(t.kind)) continue;
                String ch = t.prev == 0 ? "—" : (t.last >= t.prev ? "+" : "") + ((t.last - t.prev) * 100 / t.prev) + "%";
                s.row("declining".equals(t.kind) || "stopped".equals(t.kind) ? Report.BOLD : Report.NORMAL, name(in, t.office), k[1], t.last, t.prev, ch);
            }
        }
        s.note = "اتصل بالمتراجع واسأله عن السبب قبل أن تخسره، وأعطِ الجديد سقفًا صغيرًا يكبر مع التزامه.";
    }

    // ===================================================================================
    // 6) ربح الأجور وفرق العملة
    // ===================================================================================

    public static final class Fees {
        /** المكتب|العملة ← {قبضت، دفعت}. */
        public final Map<String, long[]> perOffice = new TreeMap<>();
        public final Map<String, long[]> perCurrency = new TreeMap<>();
        public final Map<Integer, Map<String, long[]>> perMonth = new TreeMap<>();
        public long fromDescription, fromRaje;
        /** «راجع»: المكتب|العملة ← {قبضت، دفعت، عدد، أجرة ما ذُكر مبلغه، المبلغ المذكور (بوحدات العملة الصغرى)}. */
        public final Map<String, long[]> raje = new TreeMap<>();
    }

    public static Fees fees(Input in, long from) {
        Fees f = new Fees();
        for (Model.Tx t : in.txs) {
            if (!active(t) || t.createdAt < from) continue;
            String cur = null;
            long got = 0, paid = 0;
            if (t.hasFee()) {
                cur = t.feeCurrency;
                if (Model.FEE_US.equals(t.feeType)) got = t.fee;
                else paid = t.fee;
            } else if (feeRow(t)) {
                f.fromDescription++;
                cur = t.currency;
                if (Model.TYPE_DEBIT.equals(t.type)) got = t.amount;
                else paid = t.amount;
                if (rajeRow(t)) {
                    f.fromRaje++;
                    long[] x = f.raje.computeIfAbsent(t.officeId + "|" + cur, z -> new long[5]);
                    x[0] += got;
                    x[1] += paid;
                    x[2]++;
                    BigDecimal rate = rajeRate(t, dec(in, cur));
                    if (rate != null) {
                        x[3] += t.amount;
                        x[4] += BigDecimal.valueOf(t.amount).multiply(BigDecimal.valueOf(100)).divide(rate, 0, java.math.RoundingMode.HALF_UP).longValue();
                    }
                }
            }
            if (cur == null) continue;
            add(f.perOffice, t.officeId + "|" + cur, got, paid);
            add(f.perCurrency, cur, got, paid);
            add(f.perMonth.computeIfAbsent(monthKey(t.createdAt), k -> new TreeMap<>()), cur, got, paid);
        }
        return f;
    }

    static void add(Map<String, long[]> m, String k, long got, long paid) {
        long[] x = m.computeIfAbsent(k, z -> new long[2]);
        x[0] += got;
        x[1] += paid;
    }

    static void feesSection(Report r, Input in) {
        Fees f = fees(in, in.now - 365 * DAY);
        Report.Section s = r.section("الأجور لنا وعلينا — آخر 12 شهرًا", "الأجور");
        for (Map.Entry<String, long[]> e : f.perCurrency.entrySet()) {
            long[] x = e.getValue();
            s.kv("بـ" + e.getKey(), "قبضت " + fmt(in, e.getKey(), x[0]) + "، ودفعت " + fmt(in, e.getKey(), x[1]) + " — الصافي " + (x[0] >= x[1] ? "ربح " : "خسارة ")
                    + fmt(in, e.getKey(), Math.abs(x[0] - x[1])), x[0] < x[1]);
        }
        if (f.fromDescription > 0) s.kv("منها حركات أجور مستقلة", f.fromDescription + " حركة — سجّل الأجرة في خانتها مع الحوالة لتعرف ربح كل حوالة", false);
        s.col("المكتب", 1.8f, Report.TEXT).col("العملة", 0.6f, Report.TEXT).col("أجور لنا", 1f, Report.MONEY).col("أجور علينا", 1f, Report.MONEY).col("الصافي", 1f, Report.BAL);
        List<Map.Entry<String, long[]>> l = new ArrayList<>(f.perOffice.entrySet());
        Collections.sort(l, (a, b) -> Long.compare(a.getValue()[0] - a.getValue()[1], b.getValue()[0] - b.getValue()[1]));
        for (Map.Entry<String, long[]> e : l) {
            String[] k = e.getKey().split("\\|", 2);
            long[] x = e.getValue();
            s.row(x[0] < x[1] ? Report.BOLD : Report.NORMAL, name(in, Long.parseLong(k[0])), k[1], money(in, k[1], x[0]), money(in, k[1], x[1]), new Report.Bal(x[0] - x[1], dec(in, k[1])));
        }
        s.note = "المكاتب التي تدفع لها أجورًا أكثر مما تأخذ أولًا: راجع تسعيرها أو غيّر الطريق.";

        // شهريًا
        Report.Section m = r.section("صافي الأجور شهريًا", "الأجور شهريًا");
        List<String> curs = new ArrayList<>(f.perCurrency.keySet());
        m.col("الشهر", 1f, Report.TEXT);
        for (String c : curs) m.col("صافي " + c, 1.2f, Report.BAL);
        List<Integer> months = new ArrayList<>(f.perMonth.keySet());
        Collections.reverse(months);
        for (int mk : months) {
            Object[] cells = new Object[1 + curs.size()];
            cells[0] = monthLabel(mk);
            for (int i = 0; i < curs.size(); i++) {
                long[] x = f.perMonth.get(mk).get(curs.get(i));
                cells[1 + i] = new Report.Bal(x == null ? 0 : x[0] - x[1], dec(in, curs.get(i)));
            }
            m.row(Report.NORMAL, cells);
        }

        // 11.6: لا قسم منفصلًا لـ«الراجع»: أجوره داخل «الأجور لنا وعلينا» أعلاه

        // فرق العملة
        Map<String, List<Model.Tx>> pairs = new LinkedHashMap<>();
        for (Model.Tx t : in.txs) if (active(t) && t.isFx() && t.createdAt >= in.now - 365 * DAY) pairs.computeIfAbsent(t.pairUuid, k -> new ArrayList<>()).add(t);
        if (!pairs.isEmpty()) {
            Report.Section x = r.section("تبديل العملات — آخر 12 شهرًا", "تبديل العملات");
            x.col("التاريخ", 1f, Report.DATE).col("المكتب", 1.6f, Report.TEXT).col("لنا", 1.2f, Report.TEXT).col("علينا", 1.2f, Report.TEXT).col("السعر", 0.8f, Report.TEXT)
                    .col("الفرق بالدولار بسعر اليوم", 1.2f, Report.TEXT);
            BigDecimal total = BigDecimal.ZERO;
            int priced = 0;
            for (List<Model.Tx> legs : pairs.values()) {
                Model.Tx us = null, them = null;
                for (Model.Tx t : legs) {
                    if (Model.TYPE_DEBIT.equals(t.type)) us = t;
                    else them = t;
                }
                if (us == null || them == null) continue;
                BigDecimal ru = in.usdRates.get(us.currency), rt = in.usdRates.get(them.currency);
                String diff = "—";
                if (ru != null && rt != null) {
                    BigDecimal d = BigDecimal.valueOf(us.amount, dec(in, us.currency)).multiply(ru)
                            .subtract(BigDecimal.valueOf(them.amount, dec(in, them.currency)).multiply(rt)).setScale(2, java.math.RoundingMode.HALF_UP);
                    total = total.add(d);
                    priced++;
                    diff = d.toPlainString();
                }
                x.row(Report.NORMAL, new Report.DateV(us.createdAt), name(in, us.officeId), fmt(in, us.currency, us.amount), fmt(in, them.currency, them.amount), us.fxRate, diff);
            }
            x.kv("عمليات التبديل", pairs.size() + "", false);
            if (priced > 0) x.kv("فرق العملة التقديري", (total.signum() >= 0 ? "ربح " : "خسارة ") + total.abs().toPlainString() + " USD بأسعار اليوم المحفوظة", true);
            x.note = "تقدير: يُقوَّم المبلغان بسعر الصرف المحفوظ في التطبيق الآن، لا بسعر يوم التبديل.";
        }
    }

    /** الأجرة ÷ المبلغ (كلاهما بالوحدات الصغرى) نسبةً مئوية بثلاثة منازل على الأكثر. */
    static String rateText(long fee, long base) {
        if (base <= 0) return "—";
        return BigDecimal.valueOf(fee).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(base), 3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    // ===================================================================================
    // 7) المرتجعات والتصحيحات
    // ===================================================================================

    /** المكتب ← {راجعة، تعديلات، إلغاءات} منذ from. */
    public static Map<Long, long[]> returns(Input in, long from) {
        Set<Long> replaced = new HashSet<>();
        for (Model.Tx t : in.txs) if (t.replacesId != null) replaced.add(t.replacesId);
        Map<Long, long[]> m = new HashMap<>();
        for (Model.Tx t : in.txs) {
            if (t.createdAt < from) continue;
            long[] x = m.computeIfAbsent(t.officeId, k -> new long[3]);
            if (active(t) && returnRow(t)) x[0]++;
            if (active(t) && t.replacesId != null) x[1]++;
            if ("CANCELLED".equals(t.status) && !replaced.contains(t.id)) x[2]++;
        }
        return m;
    }

    static void returnsSection(Report r, Input in) {
        Map<Long, long[]> m = returns(in, in.now - 365 * DAY);
        long ret = 0;
        for (long[] x : m.values()) ret += x[0];
        List<Map.Entry<Long, long[]>> l = new ArrayList<>(m.entrySet());
        l.removeIf(e -> e.getValue()[0] + e.getValue()[1] + e.getValue()[2] == 0);
        Collections.sort(l, (a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1] + b.getValue()[2], a.getValue()[0] + a.getValue()[1] + a.getValue()[2]));
        Report.Section s = r.section("المرتجعات والتصحيحات — آخر 12 شهرًا", "المرتجعات");
        s.kv("حوالات أُعيدت (البيان يذكر «مرتجع» أو «رجعت» أو «رجوع»)", ret + " عملية", ret > 0);
        if (!l.isEmpty()) s.kv("أكثرها مع", "«" + name(in, l.get(0).getKey()) + "»", false);
        s.col("المكتب", 1.8f, Report.TEXT).col("مُعادة", 0.8f, Report.INT).col("تعديلات", 0.8f, Report.INT).col("إلغاءات", 0.8f, Report.INT);
        for (Map.Entry<Long, long[]> e : l) s.row(Report.NORMAL, name(in, e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        s.note = "كل حوالة مُعادة وقت وأجور ضائعة: اسأل عن سببها، وكثرة التعديل مع مكتب تعني اتفاقًا غير واضح. («راجع» أجرة وليست إرجاعًا: هي ضمن «الأجور لنا وعلينا».)";
    }

    // ===================================================================================
    // 8) الخدمات والوجهات
    // ===================================================================================

    static void servicesSection(Report r, Input in) {
        long from = in.now - 365 * DAY;
        Map<String, Map<String, Long>> sv = new TreeMap<>(), dv = new TreeMap<>();
        Map<String, Long> sc = new HashMap<>(), dc = new HashMap<>();
        long all = 0, withS = 0, withD = 0;
        for (Model.Tx t : in.txs) {
            if (!active(t) || t.createdAt < from || feeRow(t)) continue;
            all++;
            String s = Model.nz(t.service).trim(), d = Model.nz(t.dest).trim();
            if (!s.isEmpty()) withS++;
            if (!d.isEmpty()) withD++;
            String sk = s.isEmpty() ? "غير محددة" : s, dk = d.isEmpty() ? "غير محددة" : d;
            sc.merge(sk, 1L, Long::sum);
            dc.merge(dk, 1L, Long::sum);
            sv.computeIfAbsent(sk, k -> new TreeMap<>()).merge(t.currency, t.amount, Long::sum);
            dv.computeIfAbsent(dk, k -> new TreeMap<>()).merge(t.currency, t.amount, Long::sum);
        }
        for (int pass = 0; pass < 2; pass++) {
            Map<String, Long> c = pass == 0 ? sc : dc;
            Map<String, Map<String, Long>> v = pass == 0 ? sv : dv;
            Report.Section s = r.section(pass == 0 ? "الخدمات — آخر 12 شهرًا" : "الوجهات — آخر 12 شهرًا", pass == 0 ? "الخدمات" : "الوجهات");
            s.kv(pass == 0 ? "حركات حُدّدت خدمتها" : "حركات حُدّدت وجهتها", pct(pass == 0 ? withS : withD, all) + " من " + all, false);
            s.col(pass == 0 ? "الخدمة" : "الوجهة", 1.6f, Report.TEXT).col("العمليات", 0.8f, Report.INT).col("النسبة", 0.7f, Report.TEXT).col("المبالغ", 2.6f, Report.TEXT);
            List<String> keys = new ArrayList<>(c.keySet());
            Collections.sort(keys, (a, b) -> Long.compare(c.get(b), c.get(a)));
            int n = 0;
            for (String k : keys) {
                if (++n > 40) break;
                List<String> amounts = new ArrayList<>();
                for (Map.Entry<String, Long> e : v.get(k).entrySet()) amounts.add(fmt(in, e.getKey(), e.getValue()));
                s.row("غير محددة".equals(k) ? Report.MUTED : Report.NORMAL, k, c.get(k), pct(c.get(k), all), Model.join(amounts, " • "));
            }
            if (pass == 0) s.note = "سجّل نوع الخدمة والوجهة في خانتيهما عند كل حركة ليصبح هذا التقرير دقيقًا (يقترحهما النموذج من الحركات السابقة).";
        }
    }

    // ===================================================================================
    // 9) حجم الحوالة والعمليات الكبيرة
    // ===================================================================================

    public static final class Size {
        public long count, median, p90, p99, max, volume, top1Volume;
    }

    public static Map<String, Size> sizes(Input in, long from) {
        Map<String, List<Long>> m = new TreeMap<>();
        for (Model.Tx t : in.txs) if (active(t) && t.createdAt >= from && !feeRow(t)) m.computeIfAbsent(t.currency, k -> new ArrayList<>()).add(t.amount);
        Map<String, Size> out = new TreeMap<>();
        for (Map.Entry<String, List<Long>> e : m.entrySet()) {
            List<Long> l = e.getValue();
            Collections.sort(l);
            Size s = new Size();
            s.count = l.size();
            int n = l.size();
            s.median = n % 2 == 1 ? l.get(n / 2) : (l.get(n / 2 - 1) + l.get(n / 2)) / 2;
            s.p90 = l.get(Math.min(n - 1, (int) Math.ceil(n * 0.9) - 1));
            s.p99 = l.get(Math.min(n - 1, (int) Math.ceil(n * 0.99) - 1));
            s.max = l.get(n - 1);
            int top = Math.max(1, (int) Math.ceil(n * 0.01));
            for (int i = 0; i < n; i++) {
                s.volume += l.get(i);
                if (i >= n - top) s.top1Volume += l.get(i);
            }
            out.put(e.getKey(), s);
        }
        return out;
    }

    static void sizeSection(Report r, Input in) {
        long from = in.now - 365 * DAY;
        Map<String, Size> m = sizes(in, from);
        Report.Section s = r.section("حجم الحوالة — آخر 12 شهرًا", "حجم الحوالة");
        s.col("العملة", 0.6f, Report.TEXT).col("العمليات", 0.8f, Report.INT).col("المعتادة (الوسيط)", 1.1f, Report.MONEY).col("أكبر 10% تبدأ من", 1.1f, Report.MONEY)
                .col("أكبر 1% تبدأ من", 1.1f, Report.MONEY).col("الأكبر", 1.1f, Report.MONEY).col("حصة أكبر 1% من المبالغ", 1f, Report.TEXT);
        for (Map.Entry<String, Size> e : m.entrySet()) {
            Size z = e.getValue();
            String c = e.getKey();
            s.row(Report.NORMAL, c, z.count, money(in, c, z.median), money(in, c, z.p90), money(in, c, z.p99), money(in, c, z.max), pct(z.top1Volume, z.volume));
        }
        s.note = "العملية الأكبر من «أكبر 10%» تستحق موافقتك قبل التسليم: فعّل «الاعتماد المزدوج» بهذا الحد من «القفل والبصمة وإقفال الفترة».";
        // أكبر العمليات في آخر 90 يومًا
        List<Model.Tx> big = new ArrayList<>();
        for (Model.Tx t : in.txs) {
            if (!active(t) || t.createdAt < in.now - 90 * DAY || feeRow(t)) continue;
            Size z = m.get(t.currency);
            if (z != null && t.amount >= z.p99) big.add(t);
        }
        Collections.sort(big, Comparator.comparingLong((Model.Tx t) -> t.amount).reversed());
        Report.Section b = r.section("أكبر العمليات — آخر 90 يومًا", "الكبيرة");
        b.col("التاريخ", 1f, Report.DATE).col("المكتب", 1.6f, Report.TEXT).col("المبلغ", 1.2f, Report.TEXT).col("النوع", 0.6f, Report.TEXT).col("البيان", 2f, Report.TEXT);
        for (int i = 0; i < big.size() && i < 50; i++) {
            Model.Tx t = big.get(i);
            b.row(Report.NORMAL, new Report.DateV(t.createdAt), name(in, t.officeId), fmt(in, t.currency, t.amount), Model.TYPE_DEBIT.equals(t.type) ? "لنا" : "علينا",
                    MessageParser.compact(t.description, t.person));
        }
        if (big.isEmpty()) b.note = "لا عمليات ضمن أكبر 1% في آخر 90 يومًا.";
    }

    // ===================================================================================
    // 10) جودة التسجيل
    // ===================================================================================

    public static final class Quality {
        public final List<long[]> bulkDays = new ArrayList<>();   // {بداية اليوم، العمليات}
        public final List<String[]> similarNames = new ArrayList<>();
        public long distinctDescriptions, numericNames;
    }

    public static Quality quality(Input in) {
        Quality q = new Quality();
        Map<String, long[]> perDay = new TreeMap<>();
        Set<String> desc = new HashSet<>();
        for (Model.Tx t : in.txs) {
            if (!active(t)) continue;
            perDay.computeIfAbsent(Model.day(t.createdAt), k -> new long[]{t.createdAt, 0})[1]++;
            String d = norm(t.description);
            if (!d.isEmpty()) desc.add(d);
        }
        q.distinctDescriptions = desc.size();
        List<Long> counts = new ArrayList<>();
        for (long[] v : perDay.values()) counts.add(v[1]);
        Collections.sort(counts);
        long median = counts.isEmpty() ? 0 : counts.get(counts.size() / 2);
        for (long[] v : perDay.values()) if (v[1] >= Math.max(100, median * 10)) q.bulkDays.add(new long[]{v[0], v[1]});
        Collections.sort(q.bulkDays, (a, b) -> Long.compare(b[1], a[1]));
        List<Map.Entry<Long, String>> names = new ArrayList<>(in.names.entrySet());
        for (Map.Entry<Long, String> e : names) if (norm(e.getValue()).matches("[0-9٠-٩ ]+")) q.numericNames++;
        for (int i = 0; i < names.size(); i++) {
            String a = norm(names.get(i).getValue());
            if (a.length() < 3) continue;
            for (int j = 0; j < names.size(); j++) {
                if (i == j) continue;
                String b = norm(names.get(j).getValue());
                if (b.length() > a.length() && (b.startsWith(a + " ") || b.endsWith(" " + a))) q.similarNames.add(new String[]{names.get(i).getValue(), names.get(j).getValue()});
            }
        }
        return q;
    }

    static void qualitySection(Report r, Input in) {
        Quality q = quality(in);
        Report.Section s = r.section("ما يلزم تسجيله ليصبح التحليل أدق", "جودة التسجيل");
        s.kv("أيام سُجّلت فيها عمليات كثيرة دفعة واحدة", q.bulkDays.isEmpty() ? "لا يوجد" : q.bulkDays.size() + " يوم؛ أكبرها " + Model.day(q.bulkDays.get(0)[0]) + " بـ" + q.bulkDays.get(0)[1]
                + " عملية (اختر تاريخ العملية الحقيقي عند التسجيل)", !q.bulkDays.isEmpty());
        s.kv("أسماء مكاتب متشابهة", q.similarNames.isEmpty() ? "لا يوجد" : count(q.similarNames.size(), "زوج", "زوجان", "أزواج", "زوجًا") + " (ادمجهما إن كانا حسابًا واحدًا)", !q.similarNames.isEmpty());
        if (q.numericNames > 0) s.kv("مكاتب أسماؤها أرقام فقط", q.numericNames + " (اكتب الاسم الحقيقي)", true);
        s.kv("بيانات مختلفة", q.distinctDescriptions + " (الخدمة والمدينة في خانتيهما تجعلها قابلة للعدّ)", false);
        s.col("الملاحظة", 1.2f, Report.TEXT).col("التفاصيل", 2.6f, Report.TEXT);
        for (int i = 0; i < q.bulkDays.size() && i < 10; i++) s.row(Report.NORMAL, "تسجيل دفعة واحدة", Model.day(q.bulkDays.get(i)[0]) + ": " + q.bulkDays.get(i)[1] + " عملية");
        for (String[] p : q.similarNames) s.row(Report.NORMAL, "اسمان متشابهان", "«" + p[0] + "» و«" + p[1] + "»");
    }

    // ===================================================================================
    // التقرير الكامل والخلاصة
    // ===================================================================================

    /** الخلاصة: أربعة أسئلة صاحب المكتب — هل يكبر عملي؟ على من أعتمد؟ أين مالي معلّق؟ من يربحني؟ */
    public static List<String> headlines(Input in) {
        List<String> h = new ArrayList<>();
        Growth g = growth(in);
        // 10.16: خلاصة لا تقول إلا ما تسنده البيانات: المتوسط على أشهر البيانات، والمقارنة حين توجد سنة سابقة،
        // والتركّز حين يكون المكاتب أكثر من عشرة (وإلا «أكبر 10 = 100%» بديهية)، والديون القديمة بما هو قديم فعلًا.
        if (g.ops12 > 0) {
            h.add("العمل: متوسط " + g.avg12() + " عملية شهريًا في " + g.span() + (g.comparable ? " مقابل " + g.avgPrev12() + " في السنة قبلها (" + ratio(g.avg12(), g.avgPrev12()) + ")." : "."));
        }
        Concentration c = concentration(in, in.now - 365 * DAY);
        if (c.total > 0 && c.ranked.size() > 10) h.add("التركّز: أكبر 10 مكاتب = " + pct(c.top10, c.total) + " من عمليات آخر 12 شهرًا.");
        else if (c.total > 0 && c.ranked.size() >= 3) h.add("التركّز: أكثر مكتب تعاملًا = " + pct(c.ranked.get(0)[1], c.total) + " من عمليات آخر 12 شهرًا (من " + c.ranked.size() + " مكاتب).");
        Map<String, long[]> tot = agingTotals(aging(in, true));
        String main = null;
        long best = -1;
        for (Map.Entry<String, long[]> e : tot.entrySet()) if (e.getValue()[1] > best) {
            best = e.getValue()[1];
            main = e.getKey();
        }
        if (main != null && best > 0) {
            long[] t = tot.get(main);
            if (t[2] > 0) h.add("ديون قديمة: " + fmt(in, main, t[2]) + " من أصل " + fmt(in, main, t[0]) + " لنا بـ" + main + " (" + pct(t[2], t[0]) + ") عمرها أكثر من 90 يومًا.");
        }
        Fees f = fees(in, in.now - 365 * DAY);
        for (Map.Entry<String, long[]> e : f.perCurrency.entrySet()) {
            long[] x = e.getValue();
            if (x[1] > x[0]) h.add("أجور " + e.getKey() + " خاسرة: دفعت " + fmt(in, e.getKey(), x[1]) + " وقبضت " + fmt(in, e.getKey(), x[0]) + " في 12 شهرًا.");
        }
        int dormant = 0;
        for (Dormant d : dormant(in, 90)) dormant += d.balances.size();
        if (dormant > 0) h.add("أرصدة متوقفة: " + count(dormant, "رصيد", "رصيدان", "أرصدة", "رصيدًا") + " لم تتحرك منذ أكثر من 90 يومًا.");
        return h;
    }

    public static Report build(Input in, String issuer, boolean allTime) {
        Report r = new Report();
        r.title = "تقارير صاحب المكتب";
        if (issuer != null && !issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + issuer.trim());
        r.headerLines.add("حتى: " + Model.date(in.now));
        r.headerLines.addAll(headlines(in));
        r.fileBase = "تقارير-صاحب-المكتب";
        monthly(r, in, 25);
        concentrationSection(r, in, allTime ? 0 : in.now - 365 * DAY, allTime ? "كل الفترة" : "آخر 12 شهرًا");
        agingSection(r, in, true);
        agingSection(r, in, false);
        dormantSection(r, in);
        trendSection(r, in);
        feesSection(r, in);
        returnsSection(r, in);
        servicesSection(r, in);
        sizeSection(r, in);
        qualitySection(r, in);
        return r;
    }
}
