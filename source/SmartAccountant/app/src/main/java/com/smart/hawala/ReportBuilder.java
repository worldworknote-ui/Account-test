package com.smart.hawala;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 8.1: «منشئ التقارير» — منطق بحت مشترك بين الهاتف والماك. يختار المستخدم الفترة والمكاتب والعملات والاتجاه ونوع الحركة
 * والحالة والبحث في النصوص والخدمة والوجهة والمستخدم وحدود المبلغ، ثم التجميع (بالمكتب، باليوم/الأسبوع/الشهر/الربع/السنة،
 * بيوم الأسبوع، بالساعة، بالخدمة، بالوجهة، بالمستخدم، بالمستفيد، بالاتجاه، بنوع الحركة، بشريحة المبلغ) وجدولًا متقاطعًا
 * اختياريًا ومقارنة بالفترة السابقة، فيخرج Report يُصدَّر PDF أو Excel أو CSV كباقي التقارير.
 *
 * <p>القاعدة المحاسبية: كل حركة نشطة إما حوالة (مبلغها في «لنا/علينا» وأجرتها من خانة الأجرة في «الأجور»)
 * أو حركة أجور/راجع مستقلة (مبلغها كله في «الأجور»)، فلا يُحسب مبلغ مرتين، و«الأثر على الرصيد» = صافي الحوالات + صافي الأجور
 * يساوي حركة رصيد المكاتب في دفتر القيود للفترة نفسها (يتحقق منه الاختبار على ملف حقيقي).
 */
public final class ReportBuilder {
    private ReportBuilder() {}

    public static final long DAY = 86400000L;

    // الاتجاه
    public static final int DIR_ALL = 0, DIR_US = 1, DIR_THEM = 2;
    // نوع الحركة
    public static final int K_ALL = 0, K_TRANSFERS = 1, K_FEE_ROWS = 2, K_RAJE = 3, K_WITH_FEE = 4, K_NO_FEE = 5, K_FX = 6, K_XFER = 7;
    // الحالة
    public static final int S_ACTIVE = 0, S_ALL = 1, S_CANCELLED = 2, S_EDITED = 3, S_PENDING = 4;
    // التجميع
    public static final int G_NONE = 0, G_OFFICE = 1, G_CURRENCY = 2, G_DAY = 3, G_WEEK = 4, G_MONTH = 5, G_QUARTER = 6, G_YEAR = 7, G_WEEKDAY = 8,
            G_HOUR = 9, G_SERVICE = 10, G_DEST = 11, G_USER = 12, G_PERSON = 13, G_DIRECTION = 14, G_KIND = 15, G_SIZE = 16;
    // المقياس في الجدول المتقاطع
    public static final int M_COUNT = 0, M_US = 1, M_THEM = 2, M_NET = 3, M_VOLUME = 4, M_FEES_NET = 5, M_IMPACT = 6;
    // الترتيب
    public static final int SORT_KEY = 0, SORT_COUNT = 1, SORT_VOLUME = 2, SORT_NET = 3, SORT_FEES = 4;

    public static final String[] DIR_NAMES = {"الكل", "لنا فقط", "علينا فقط"};
    public static final String[] KIND_NAMES = {"كل الحركات", "الحوالات فقط (دون حركات الأجور)", "حركات الأجور فقط", "حركات الأجور فقط",
            "حوالات بأجرة", "حوالات بلا أجرة", "قص العملات فقط", "الحركات بين مكتبين فقط"};
    public static final String[] STATUS_NAMES = {"النشطة فقط", "كل الحالات", "الملغاة فقط", "المعدّلة وتصحيحاتها", "بانتظار الاعتماد"};
    public static final String[] GROUP_NAMES = {"بلا تجميع (كشف تفصيلي)", "المكتب", "العملة", "اليوم", "الأسبوع", "الشهر", "الربع", "السنة", "يوم الأسبوع",
            "ساعة اليوم", "الخدمة", "الوجهة", "المستخدم", "المستفيد", "الاتجاه (لنا/علينا)", "نوع الحركة", "شريحة المبلغ"};
    public static final String[] MEASURE_NAMES = {"عدد العمليات", "مجموع لنا", "مجموع علينا", "الصافي (لنا − علينا)", "الحجم (لنا + علينا)", "صافي الأجور",
            "الأثر على الرصيد"};
    public static final String[] SORT_NAMES = {"حسب المجموعة (الزمن أولًا)", "الأكثر عمليات", "الأكبر حجمًا", "الأكبر صافيًا", "الأكثر أجورًا"};
    static final String[] WEEKDAYS = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
    static final long[] SIZE_EDGES = {500, 2000, 10000, 50000};

    /** مواصفات التقرير (تُحفظ كقالب باسم). */
    public static final class Spec {
        public String title = "";
        public long from = 0, to = 0;   // to = 0: حتى الآن (حصريًا)
        public Set<Long> offices = new TreeSet<>();
        public Set<String> currencies = new TreeSet<>();
        public int direction = DIR_ALL, kind = K_ALL, status = S_ACTIVE;
        public String text = "", service = "", dest = "", user = "";
        public BigDecimal minAmount, maxAmount;
        public int group = G_OFFICE, group2 = G_NONE, measure = M_NET, sort = SORT_KEY, top = 0;
        public boolean compare, details, runningBalance = true;

        public JSONObject toJson() {
            JSONObject j = new JSONObject();
            try {
                j.put("title", title).put("from", from).put("to", to).put("direction", direction).put("kind", kind).put("status", status)
                        .put("text", text).put("service", service).put("dest", dest).put("user", user).put("group", group).put("group2", group2)
                        .put("measure", measure).put("sort", sort).put("top", top).put("compare", compare).put("details", details).put("running", runningBalance);
                if (minAmount != null) j.put("min", minAmount.toPlainString());
                if (maxAmount != null) j.put("max", maxAmount.toPlainString());
                JSONArray o = new JSONArray();
                for (long x : offices) o.put(x);
                j.put("offices", o);
                JSONArray c = new JSONArray();
                for (String x : currencies) c.put(x);
                j.put("currencies", c);
            } catch (Exception ignored) {
                // لا يحدث مع هذه الأنواع
            }
            return j;
        }

        public static Spec fromJson(JSONObject j) {
            Spec s = new Spec();
            s.title = j.optString("title", "");
            s.from = j.optLong("from", 0);
            s.to = j.optLong("to", 0);
            s.direction = clamp(j.optInt("direction", 0), DIR_NAMES.length);
            s.kind = clamp(j.optInt("kind", 0), KIND_NAMES.length);
            s.status = clamp(j.optInt("status", 0), STATUS_NAMES.length);
            s.text = j.optString("text", "");
            s.service = j.optString("service", "");
            s.dest = j.optString("dest", "");
            s.user = j.optString("user", "");
            s.group = clamp(j.optInt("group", G_OFFICE), GROUP_NAMES.length);
            s.group2 = clamp(j.optInt("group2", 0), GROUP_NAMES.length);
            s.measure = clamp(j.optInt("measure", M_NET), MEASURE_NAMES.length);
            s.sort = clamp(j.optInt("sort", 0), SORT_NAMES.length);
            s.top = Math.max(0, j.optInt("top", 0));
            s.compare = j.optBoolean("compare", false);
            s.details = j.optBoolean("details", false);
            s.runningBalance = j.optBoolean("running", true);
            try {
                if (j.has("min")) s.minAmount = new BigDecimal(j.getString("min"));
                if (j.has("max")) s.maxAmount = new BigDecimal(j.getString("max"));
            } catch (Exception ignored) {
                // حد غير صالح يُهمل
            }
            JSONArray o = j.optJSONArray("offices");
            for (int i = 0; o != null && i < o.length(); i++) s.offices.add(o.optLong(i));
            JSONArray c = j.optJSONArray("currencies");
            for (int i = 0; c != null && i < c.length(); i++) s.currencies.add(c.optString(i));
            return s;
        }

        static int clamp(int v, int n) { return v < 0 || v >= n ? 0 : v; }
    }

    /** البيانات: كل الحركات (كل الحالات) والأرصدة السابقة والأسماء ومنازل العملات. */
    public static final class Data {
        public final List<Model.Tx> txs = new ArrayList<>();
        public final List<OwnerReports.Opening> openings = new ArrayList<>();
        public final Map<Long, String> names = new HashMap<>();
        public final Map<String, Integer> decimals = new HashMap<>();
        public long now = Clock.now();
    }

    /** مجاميع مجموعة واحدة بعملة واحدة. */
    public static final class Agg {
        public long count, us, them, feeUs, feeThem, max;

        public long net() { return us - them; }

        public long volume() { return us + them; }

        public long feesNet() { return feeUs - feeThem; }

        /** أثر هذه الحركات على أرصدة المكاتب: صافي الحوالات + صافي الأجور. */
        public long impact() { return net() + feesNet(); }

        void add(Agg o) {
            count += o.count;
            us += o.us;
            them += o.them;
            feeUs += o.feeUs;
            feeThem += o.feeThem;
            max = Math.max(max, o.max);
        }

        public long measure(int m) {
            switch (m) {
                case M_COUNT: return count;
                case M_US: return us;
                case M_THEM: return them;
                case M_VOLUME: return volume();
                case M_FEES_NET: return feesNet();
                case M_IMPACT: return impact();
                default: return net();
            }
        }
    }

    // ===================================================================================
    // التصفية والتصنيف
    // ===================================================================================

    static boolean isDebit(Model.Tx t) { return Model.TYPE_DEBIT.equals(t.type); }

    public static boolean matches(Spec s, Data d, Model.Tx t, long from, long to) {
        if (t.createdAt < from || (to > 0 && t.createdAt >= to)) return false;
        if (!s.offices.isEmpty() && !s.offices.contains(t.officeId)) return false;
        if (!s.currencies.isEmpty() && !s.currencies.contains(t.currency) && !(t.hasFee() && s.currencies.contains(t.feeCurrency))) return false;
        if (s.direction == DIR_US && !isDebit(t)) return false;
        if (s.direction == DIR_THEM && isDebit(t)) return false;
        boolean feeRow = OwnerReports.feeRow(t);
        switch (s.kind) {
            case K_TRANSFERS: if (feeRow) return false; break;
            case K_FEE_ROWS: if (!feeRow) return false; break;
            case K_RAJE: if (!OwnerReports.rajeRow(t)) return false; break;
            case K_WITH_FEE: if (feeRow || !t.hasFee()) return false; break;
            case K_NO_FEE: if (feeRow || t.hasFee()) return false; break;
            case K_FX: if (!t.isFx()) return false; break;
            case K_XFER: if (!t.isTransfer()) return false; break;
            default: break;
        }
        switch (s.status) {
            case S_ALL: break;
            case S_CANCELLED: if (!"CANCELLED".equals(t.status)) return false; break;
            case S_EDITED: if (t.replacesId == null && !replaced(d, t.id)) return false; break;
            case S_PENDING: if (!t.isPending()) return false; break;
            default: if (!t.isActive()) return false;
        }
        if (!s.text.trim().isEmpty()) {
            String q = OwnerReports.norm(s.text);
            String hay = OwnerReports.norm(Model.nz(t.person) + " " + Model.nz(t.description) + " " + Model.nz(t.notes) + " " + Model.nz(t.dest) + " " + Model.nz(t.service));
            for (String w : q.split(" ")) if (!w.isEmpty() && !hay.contains(w)) return false;
        }
        if (!s.service.trim().isEmpty() && !OwnerReports.norm(t.service).contains(OwnerReports.norm(s.service))) return false;
        if (!s.dest.trim().isEmpty() && !OwnerReports.norm(t.dest).contains(OwnerReports.norm(s.dest))) return false;
        if (!s.user.trim().isEmpty() && !OwnerReports.norm(t.userName).contains(OwnerReports.norm(s.user))) return false;
        int dec = dec(d, t.currency);
        if (s.minAmount != null && BigDecimal.valueOf(t.amount, dec).compareTo(s.minAmount) < 0) return false;
        if (s.maxAmount != null && BigDecimal.valueOf(t.amount, dec).compareTo(s.maxAmount) > 0) return false;
        return true;
    }

    private static final Map<Data, Set<Long>> REPLACED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static boolean replaced(Data d, long id) {
        Set<Long> r = REPLACED.get(d);
        if (r == null) {
            r = new HashSet<>();
            for (Model.Tx t : d.txs) if (t.replacesId != null) r.add(t.replacesId);
            REPLACED.put(d, r);
        }
        return r.contains(id);
    }

    static int dec(Data d, String c) {
        Integer x = d.decimals.get(c);
        return x == null ? 2 : x;
    }

    /** يضيف أثر الحركة إلى مجاميع عملاتها (الأصل في عملته، والأجرة في عملتها). */
    static void accumulate(Map<String, Agg> m, Model.Tx t, Set<String> onlyCurrencies) {
        boolean feeRow = OwnerReports.feeRow(t);
        if (onlyCurrencies.isEmpty() || onlyCurrencies.contains(t.currency)) {
            Agg a = m.computeIfAbsent(t.currency, k -> new Agg());
            a.count++;
            if (feeRow) {
                if (isDebit(t)) a.feeUs += t.amount;
                else a.feeThem += t.amount;
            } else {
                if (isDebit(t)) a.us += t.amount;
                else a.them += t.amount;
                a.max = Math.max(a.max, t.amount);
            }
        }
        if (!feeRow && t.hasFee() && (onlyCurrencies.isEmpty() || onlyCurrencies.contains(t.feeCurrency))) {
            Agg f = m.computeIfAbsent(t.feeCurrency, k -> new Agg());
            if (Model.FEE_US.equals(t.feeType)) f.feeUs += t.fee;
            else f.feeThem += t.fee;
        }
    }

    // ===================================================================================
    // مفاتيح التجميع
    // ===================================================================================

    /** مفتاح ترتيب + نص عرض. المفتاح الزمني رقم يرتّب زمنيًا. */
    public static String[] key(int g, Data d, Model.Tx t) {
        Calendar c = Model.cal();
        c.setTimeInMillis(t.createdAt);
        int y = c.get(Calendar.YEAR), mo = c.get(Calendar.MONTH) + 1, da = c.get(Calendar.DAY_OF_MONTH);
        switch (g) {
            case G_OFFICE: return new String[]{"" + t.officeId, name(d, t.officeId)};
            case G_CURRENCY: return new String[]{t.currency, t.currency};
            case G_DAY: return new String[]{String.format(Locale.US, "%04d%02d%02d", y, mo, da), String.format(Locale.US, "%04d/%02d/%02d", y, mo, da)};
            case G_WEEK: {
                // الأسبوع يبدأ السبت
                int back = (c.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7;
                c.add(Calendar.DAY_OF_MONTH, -back);
                String k = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
                return new String[]{k, "أسبوع " + String.format(Locale.US, "%04d/%02d/%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))};
            }
            case G_MONTH: return new String[]{String.format(Locale.US, "%04d%02d", y, mo), String.format(Locale.US, "%04d/%02d", y, mo)};
            case G_QUARTER: return new String[]{y + "Q" + ((mo - 1) / 3 + 1), y + " — الربع " + ((mo - 1) / 3 + 1)};
            case G_YEAR: return new String[]{"" + y, "" + y};
            case G_WEEKDAY: {
                int w = c.get(Calendar.DAY_OF_WEEK);   // 1=الأحد
                int order = (w - Calendar.SATURDAY + 7) % 7;   // السبت أولًا
                return new String[]{"" + order, WEEKDAYS[w - 1]};
            }
            case G_HOUR: {
                int h = c.get(Calendar.HOUR_OF_DAY);
                return new String[]{String.format(Locale.US, "%02d", h), String.format(Locale.US, "%02d:00–%02d:00", h, (h + 1) % 24)};
            }
            case G_SERVICE: return txt(t.service, "بلا خدمة");
            case G_DEST: return txt(t.dest, "بلا وجهة");
            case G_USER: return txt(t.userName, "غير معروف");
            case G_PERSON: return txt(t.person, "بلا اسم");
            case G_DIRECTION: return isDebit(t) ? new String[]{"1", "لنا"} : new String[]{"2", "علينا"};
            case G_KIND: {
                if (t.isFx()) return new String[]{"4", "قص عملات"};
                if (t.isTransfer()) return new String[]{"5", "بين مكتبين"};
                if (OwnerReports.rajeRow(t)) return new String[]{"2", "أجور"};
                if (OwnerReports.feeRow(t)) return new String[]{"2", "أجور"};
                return new String[]{"1", t.hasFee() ? "حوالة بأجرة" : "حوالة"};
            }
            case G_SIZE: {
                BigDecimal v = BigDecimal.valueOf(t.amount, dec(d, t.currency));
                int i = 0;
                while (i < SIZE_EDGES.length && v.compareTo(BigDecimal.valueOf(SIZE_EDGES[i])) >= 0) i++;
                String label = i == 0 ? "أقل من " + Model.fmt(SIZE_EDGES[0], 0)
                        : i == SIZE_EDGES.length ? Model.fmt(SIZE_EDGES[i - 1], 0) + " فأكثر"
                        : Model.fmt(SIZE_EDGES[i - 1], 0) + " – " + Model.fmt(SIZE_EDGES[i], 0);
                return new String[]{"" + i, label};
            }
            default: return new String[]{"", "الكل"};
        }
    }

    static String[] txt(String v, String empty) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) return new String[]{"￿", empty};
        return new String[]{OwnerReports.norm(s), s};
    }

    static String name(Data d, long office) {
        String n = d.names.get(office);
        return n == null || n.isEmpty() ? "مكتب " + office : n;
    }

    static boolean timeGroup(int g) { return g >= G_DAY && g <= G_HOUR; }

    // ===================================================================================
    // الحساب
    // ===================================================================================

    public static final class Result {
        /** المجموعة ← العملة ← المجاميع. */
        public final Map<String, Map<String, Agg>> groups = new LinkedHashMap<>();
        public final Map<String, String> labels = new HashMap<>();
        public final Map<String, Agg> totals = new TreeMap<>();
        public final Map<String, Map<String, Agg>> previous = new HashMap<>();
        public final Map<String, Agg> previousTotals = new TreeMap<>();
        public final List<Model.Tx> rows = new ArrayList<>();
        /** الجدول المتقاطع: المجموعة ← العمود ← العملة ← المجاميع. */
        public final Map<String, Map<String, Map<String, Agg>>> pivot = new HashMap<>();
        public final Map<String, String> pivotLabels = new TreeMap<>();
        public long from, to, prevFrom, prevTo;
    }

    public static Result compute(Spec s, Data d) {
        Result r = new Result();
        r.from = s.from;
        r.to = s.to > 0 ? s.to : Long.MAX_VALUE;
        Map<String, Map<String, Agg>> groups = new HashMap<>();
        for (Model.Tx t : d.txs) {
            if (!matches(s, d, t, r.from, r.to)) continue;
            r.rows.add(t);
            String[] k = key(s.group, d, t);
            r.labels.put(k[0], k[1]);
            accumulate(groups.computeIfAbsent(k[0], z -> new TreeMap<>()), t, s.currencies);
            accumulate(r.totals, t, s.currencies);
            if (s.group2 != G_NONE) {
                String[] k2 = key(s.group2, d, t);
                r.pivotLabels.put(k2[0], k2[1]);
                accumulate(r.pivot.computeIfAbsent(k[0], z -> new HashMap<>()).computeIfAbsent(k2[0], z -> new TreeMap<>()), t, s.currencies);
            }
        }
        if (s.compare && s.from > 0) {
            long len = r.to == Long.MAX_VALUE ? d.now - s.from : r.to - s.from;
            r.prevFrom = s.from - len;
            r.prevTo = s.from;
            for (Model.Tx t : d.txs) {
                if (!matches(s, d, t, r.prevFrom, r.prevTo)) continue;
                String[] k = key(s.group, d, t);
                if (!r.labels.containsKey(k[0])) r.labels.put(k[0], k[1]);
                accumulate(r.previous.computeIfAbsent(k[0], z -> new TreeMap<>()), t, s.currencies);
                accumulate(r.previousTotals, t, s.currencies);
            }
            for (String k : r.previous.keySet()) groups.computeIfAbsent(k, z -> new TreeMap<>());
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        final Map<String, Map<String, Agg>> g = groups;
        Comparator<String> byKey = timeGroup(s.group) || s.group == G_DIRECTION || s.group == G_KIND || s.group == G_SIZE || s.group == G_YEAR
                ? Comparator.naturalOrder() : (a, b) -> r.labels.get(a).compareTo(r.labels.get(b));
        Comparator<String> cmp;
        switch (s.sort) {
            case SORT_COUNT: cmp = Comparator.comparingLong((String k) -> -sum(g.get(k), M_COUNT)); break;
            case SORT_VOLUME: cmp = Comparator.comparingLong((String k) -> -sum(g.get(k), M_VOLUME)); break;
            case SORT_NET: cmp = Comparator.comparingLong((String k) -> -Math.abs(sum(g.get(k), M_NET))); break;
            case SORT_FEES: cmp = Comparator.comparingLong((String k) -> -sum(g.get(k), M_FEES_NET)); break;
            default: cmp = byKey;
        }
        Collections.sort(keys, cmp.thenComparing(byKey));
        if (s.top > 0 && keys.size() > s.top) keys = keys.subList(0, s.top);
        for (String k : keys) r.groups.put(k, groups.get(k));
        return r;
    }

    /**
     * مجموع مقياس عبر العملات. للعدد يُجمع مباشرة؛ للمبالغ يُجمع بلا تحويل (للترتيب فقط، ضمن عملة واحدة في الغالب).
     */
    static long sum(Map<String, Agg> m, int measure) {
        long s = 0;
        if (m == null) return 0;
        for (Agg a : m.values()) s += a.measure(measure);
        return s;
    }

    /** رصيد كل مكتب|عملة قبل لحظة (حركات نشطة + أرصدة سابقة)، للرصيد المتراكم في الكشف التفصيلي. */
    static Map<String, Long> balanceBefore(Data d, long before) {
        Map<String, Long> b = new HashMap<>();
        for (Model.Tx t : d.txs) {
            if (!t.isActive() || t.createdAt >= before) continue;
            b.merge(t.officeId + "|" + t.currency, isDebit(t) ? t.amount : -t.amount, Long::sum);
            if (t.hasFee()) b.merge(t.officeId + "|" + t.feeCurrency, Model.FEE_US.equals(t.feeType) ? t.fee : -t.fee, Long::sum);
        }
        for (OwnerReports.Opening o : d.openings) if (o.when < before) b.merge(o.office + "|" + o.currency, o.signed, Long::sum);
        return b;
    }

    // ===================================================================================
    // بناء التقرير
    // ===================================================================================

    public static String describe(Spec s, Data d) {
        List<String> p = new ArrayList<>();
        p.add("الفترة: " + (s.from > 0 ? Model.day(s.from) : "البداية") + " ← " + (s.to > 0 ? Model.day(s.to - 1) : "الآن"));
        if (!s.offices.isEmpty()) {
            List<String> n = new ArrayList<>();
            for (long o : s.offices) n.add(name(d, o));
            p.add("المكاتب: " + (n.size() > 6 ? n.subList(0, 6) + " و" + (n.size() - 6) + " غيرها" : Model.join(n, "، ")));
        }
        if (!s.currencies.isEmpty()) p.add("العملات: " + Model.join(new ArrayList<>(s.currencies), "، "));
        if (s.direction != DIR_ALL) p.add("الاتجاه: " + DIR_NAMES[s.direction]);
        if (s.kind != K_ALL) p.add("النوع: " + KIND_NAMES[s.kind]);
        if (s.status != S_ACTIVE) p.add("الحالة: " + STATUS_NAMES[s.status]);
        if (!s.text.trim().isEmpty()) p.add("بحث: «" + s.text.trim() + "»");
        if (!s.service.trim().isEmpty()) p.add("الخدمة: " + s.service.trim());
        if (!s.dest.trim().isEmpty()) p.add("الوجهة: " + s.dest.trim());
        if (!s.user.trim().isEmpty()) p.add("المستخدم: " + s.user.trim());
        if (s.minAmount != null || s.maxAmount != null)
            p.add("المبلغ: " + (s.minAmount == null ? "" : "من " + s.minAmount.toPlainString() + " ") + (s.maxAmount == null ? "" : "إلى " + s.maxAmount.toPlainString()));
        return Model.join(p, " · ");
    }

    public static Report build(Spec s, Data d, String issuer) {
        Result res = compute(s, d);
        Report r = new Report();
        r.title = s.title == null || s.title.trim().isEmpty() ? (s.group == G_NONE ? "كشف حركات مخصص" : "تقرير حسب " + GROUP_NAMES[s.group]) : s.title.trim();
        r.fileBase = "تقرير-مخصص";
        if (issuer != null && !issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + issuer.trim());
        r.headerLines.add(describe(s, d));

        // الخلاصة لكل عملة
        Report.Section sum = r.section("الخلاصة", "الخلاصة");
        sum.kv("عدد الحركات المطابقة", "" + res.rows.size(), false);
        for (Map.Entry<String, Agg> e : res.totals.entrySet()) {
            String c = e.getKey();
            Agg a = e.getValue();
            sum.kv("بـ" + c, "لنا " + fmt(d, c, a.us) + " · علينا " + fmt(d, c, a.them) + " · الصافي " + Model.bal(a.net(), dec(d, c))
                    + " · الأجور لنا " + fmt(d, c, a.feeUs) + " وعلينا " + fmt(d, c, a.feeThem) + " · الأثر على الرصيد " + Model.bal(a.impact(), dec(d, c)) + " " + c, false);
            Agg p = res.previousTotals.get(c);
            if (s.compare && p != null)
                sum.kv("مقارنة بالفترة السابقة (" + c + ")", "العمليات " + change(a.count, p.count) + " · الحجم " + change(a.volume(), p.volume())
                        + " · صافي الأجور " + change(a.feesNet(), p.feesNet()), false);
        }
        sum.col("العملة", 0.7f, Report.TEXT).col("العمليات", 0.7f, Report.INT).col("لنا", 1.1f, Report.MONEY).col("علينا", 1.1f, Report.MONEY)
                .col("الصافي", 1.1f, Report.BAL).col("أجور لنا", 1f, Report.MONEY).col("أجور علينا", 1f, Report.MONEY).col("الأثر على الرصيد", 1.1f, Report.BAL);
        for (Map.Entry<String, Agg> e : res.totals.entrySet()) {
            String c = e.getKey();
            Agg a = e.getValue();
            int dc = dec(d, c);
            sum.row(Report.TOTAL, c, a.count, new Report.Money(a.us, dc), new Report.Money(a.them, dc), new Report.Bal(a.net(), dc),
                    new Report.Money(a.feeUs, dc), new Report.Money(a.feeThem, dc), new Report.Bal(a.impact(), dc));
        }
        sum.note = "«الصافي» = الحوالات لنا − علينا. حركات الأجور المستقلة تُحسب في الأجور لا في الحوالات، فلا يتكرر مبلغ. "
                + "«الأثر على الرصيد» = الصافي + صافي الأجور، وهو ما تغيّر في أرصدة المكاتب بهذه الحركات.";

        if (s.group != G_NONE) groupedSection(r, s, d, res);
        if (s.group2 != G_NONE && s.group != G_NONE) pivotSections(r, s, d, res);
        if (s.group == G_NONE || s.details) detailSection(r, s, d, res);
        return r;
    }

    static void groupedSection(Report r, Spec s, Data d, Result res) {
        Report.Section g = r.section("حسب " + GROUP_NAMES[s.group], GROUP_NAMES[s.group]);
        boolean multi = res.totals.size() > 1;
        g.col(GROUP_NAMES[s.group], 1.6f, Report.TEXT);
        if (multi) g.col("العملة", 0.6f, Report.TEXT);
        g.col("العمليات", 0.7f, Report.INT).col("لنا", 1f, Report.MONEY).col("علينا", 1f, Report.MONEY).col("الصافي", 1f, Report.BAL)
                .col("صافي الأجور", 0.9f, Report.BAL).col("المتوسط", 0.9f, Report.MONEY).col("أكبر حوالة", 0.9f, Report.MONEY);
        if (s.compare) g.col("عمليات سابقًا", 0.7f, Report.INT).col("تغيّر العمليات", 0.7f, Report.TEXT).col("تغيّر الحجم", 0.7f, Report.TEXT);
        for (Map.Entry<String, Map<String, Agg>> e : res.groups.entrySet()) {
            String label = res.labels.get(e.getKey());
            Map<String, Agg> prev = res.previous.get(e.getKey());
            Set<String> curs = new TreeSet<>(e.getValue().keySet());
            if (prev != null) curs.addAll(prev.keySet());
            boolean first = true;
            for (String c : curs) {
                Agg a = e.getValue().getOrDefault(c, new Agg());
                Agg p = prev == null ? null : prev.get(c);
                int dc = dec(d, c);
                long transfers = a.count;
                long avg = a.us + a.them == 0 || transfers == 0 ? 0 : (a.us + a.them) / Math.max(1, transfers);
                List<Object> cells = new ArrayList<>();
                cells.add(label);
                if (multi) cells.add(c);
                cells.add(a.count);
                cells.add(new Report.Money(a.us, dc));
                cells.add(new Report.Money(a.them, dc));
                cells.add(new Report.Bal(a.net(), dc));
                cells.add(new Report.Bal(a.feesNet(), dc));
                cells.add(new Report.Money(avg, dc));
                cells.add(new Report.Money(a.max, dc));
                if (s.compare) {
                    cells.add(p == null ? 0L : p.count);
                    cells.add(change(a.count, p == null ? 0 : p.count));
                    cells.add(change(a.volume(), p == null ? 0 : p.volume()));
                }
                g.row(Report.NORMAL, cells.toArray());
                first = false;
            }
        }
        if (s.top > 0) g.note = "أعلى " + s.top + " فقط حسب الترتيب المختار.";
    }

    static void pivotSections(Report r, Spec s, Data d, Result res) {
        List<String> cols = new ArrayList<>(res.pivotLabels.keySet());
        int maxCols = 24;
        boolean more = cols.size() > maxCols;
        if (more) {
            // أكبر الأعمدة حسب العدد
            final Map<String, Long> weight = new HashMap<>();
            for (Map<String, Map<String, Agg>> row : res.pivot.values())
                for (Map.Entry<String, Map<String, Agg>> c : row.entrySet()) weight.merge(c.getKey(), sum(c.getValue(), M_COUNT), Long::sum);
            cols.sort((a, b) -> Long.compare(weight.getOrDefault(b, 0L), weight.getOrDefault(a, 0L)));
            cols = new ArrayList<>(cols.subList(0, maxCols));
            Collections.sort(cols);
        }
        boolean count = s.measure == M_COUNT;
        Set<String> curs = count ? Collections.singleton("") : res.totals.keySet();
        for (String c : curs) {
            String head = MEASURE_NAMES[s.measure] + (count ? "" : " بـ" + c) + " — " + GROUP_NAMES[s.group] + " × " + GROUP_NAMES[s.group2];
            Report.Section p = r.section(head, "جدول " + (count ? "العدد" : c));
            p.col(GROUP_NAMES[s.group], 1.4f, Report.TEXT);
            int type = count ? Report.INT : (s.measure == M_US || s.measure == M_THEM || s.measure == M_VOLUME ? Report.MONEY : Report.BAL);
            for (String k : cols) p.col(res.pivotLabels.get(k), 0.9f, type);
            if (more) p.col("أخرى", 0.9f, type);
            p.col("المجموع", 1f, type);
            int dc = count ? 0 : dec(d, c);
            long[] colTotals = new long[cols.size() + 2];
            for (String g : res.groups.keySet()) {
                Map<String, Map<String, Agg>> row = res.pivot.getOrDefault(g, new HashMap<>());
                Object[] cells = new Object[cols.size() + 2 + (more ? 1 : 0)];
                cells[0] = res.labels.get(g);
                long total = 0, other = 0;
                for (Map.Entry<String, Map<String, Agg>> e : row.entrySet()) {
                    long v = count ? sum(e.getValue(), M_COUNT) : e.getValue().getOrDefault(c, new Agg()).measure(s.measure);
                    total += v;
                    int i = cols.indexOf(e.getKey());
                    if (i < 0) other += v;
                }
                for (int i = 0; i < cols.size(); i++) {
                    Map<String, Agg> m = row.get(cols.get(i));
                    long v = m == null ? 0 : count ? sum(m, M_COUNT) : m.getOrDefault(c, new Agg()).measure(s.measure);
                    cells[1 + i] = cell(type, v, dc);
                    colTotals[i] += v;
                }
                int at = 1 + cols.size();
                if (more) {
                    cells[at++] = cell(type, other, dc);
                    colTotals[cols.size()] += other;
                }
                cells[at] = cell(type, total, dc);
                colTotals[cols.size() + 1] += total;
                p.row(Report.NORMAL, cells);
            }
            Object[] tot = new Object[cols.size() + 2 + (more ? 1 : 0)];
            tot[0] = "المجموع";
            for (int i = 0; i < cols.size(); i++) tot[1 + i] = cell(type, colTotals[i], dc);
            int at = 1 + cols.size();
            if (more) tot[at++] = cell(type, colTotals[cols.size()], dc);
            tot[at] = cell(type, colTotals[cols.size() + 1], dc);
            p.row(Report.TOTAL, tot);
            if (more) p.note = "أكبر " + maxCols + " عمودًا بعدد العمليات، والباقي في «أخرى».";
        }
    }

    static Object cell(int type, long v, int dec) {
        if (type == Report.INT) return v;
        if (type == Report.MONEY) return new Report.Money(v, dec);
        return new Report.Bal(v, dec);
    }

    static void detailSection(Report r, Spec s, Data d, Result res) {
        Report.Section t = r.section(s.group == G_NONE ? "الحركات" : "تفاصيل الحركات", "الحركات");
        boolean oneOffice = s.offices.size() == 1;
        boolean running = s.runningBalance && oneOffice && s.status == S_ACTIVE && s.kind == K_ALL && s.direction == DIR_ALL
                && s.text.trim().isEmpty() && s.service.trim().isEmpty() && s.dest.trim().isEmpty() && s.user.trim().isEmpty()
                && s.minAmount == null && s.maxAmount == null;
        t.col("التاريخ", 0.9f, Report.DATE);
        if (!oneOffice) t.col("المكتب", 1.3f, Report.TEXT);
        t.col("لنا", 1f, Report.MONEY).col("علينا", 1f, Report.MONEY).col("العملة", 0.5f, Report.TEXT).col("الأجرة", 1f, Report.TEXT)
                .col("المستفيد", 1.2f, Report.TEXT).col("البيان", 1.6f, Report.TEXT).col("الخدمة/الوجهة", 1f, Report.TEXT);
        if (s.status != S_ACTIVE) t.col("الحالة", 0.7f, Report.TEXT);
        if (running) t.col("الرصيد", 1.1f, Report.BAL);
        Map<String, Long> bal = running ? balanceBefore(d, res.from) : new HashMap<>();
        if (running) {
            long o = s.offices.iterator().next();
            for (Map.Entry<String, Long> e : bal.entrySet()) {
                String[] k = e.getKey().split("\\|", 2);
                if (Long.parseLong(k[0]) != o || e.getValue() == 0 || (!s.currencies.isEmpty() && !s.currencies.contains(k[1]))) continue;
                List<Object> cells = new ArrayList<>();
                cells.add(new Report.DateV(res.from));
                cells.add("");
                cells.add("");
                cells.add(k[1]);
                cells.add("");
                cells.add("");
                cells.add("الرصيد قبل الفترة");
                cells.add("");
                cells.add(new Report.Bal(e.getValue(), dec(d, k[1])));
                t.row(Report.SHADED, cells.toArray());
            }
        }
        // الأرصدة السابقة المسجّلة داخل الفترة تظهر في موضعها الزمني
        List<OwnerReports.Opening> ops = new ArrayList<>();
        if (running) {
            long o = s.offices.iterator().next();
            for (OwnerReports.Opening op : d.openings)
                if (op.office == o && op.when >= res.from && op.when < res.to && (s.currencies.isEmpty() || s.currencies.contains(op.currency))) ops.add(op);
            ops.sort(Comparator.comparingLong(op -> op.when));
        }
        int oi = 0;
        int n = 0;
        for (Model.Tx x : res.rows) {
            while (oi < ops.size() && ops.get(oi).when <= x.createdAt) openingRow(t, d, ops.get(oi++), bal, s.status != S_ACTIVE);
            int dc = dec(d, x.currency);
            List<Object> cells = new ArrayList<>();
            cells.add(new Report.DateV(x.createdAt));
            if (!oneOffice) cells.add(name(d, x.officeId));
            cells.add(isDebit(x) ? new Report.Money(x.amount, dc) : "");
            cells.add(isDebit(x) ? "" : new Report.Money(x.amount, dc));
            cells.add(x.currency);
            cells.add(x.hasFee() ? (Model.FEE_US.equals(x.feeType) ? "لنا " : "علينا ") + Model.fmt(x.fee, dec(d, x.feeCurrency)) + " " + x.feeCurrency : "");
            cells.add(Model.nz(x.person));
            cells.add(Model.nz(x.description));
            String sd = Model.nz(x.service) + (Model.nz(x.service).isEmpty() || Model.nz(x.dest).isEmpty() ? "" : " · ") + Model.nz(x.dest);
            cells.add(sd);
            if (s.status != S_ACTIVE) cells.add(statusName(x));
            if (running) {
                String k = x.officeId + "|" + x.currency;
                if (x.isActive()) {
                    bal.merge(k, isDebit(x) ? x.amount : -x.amount, Long::sum);
                    if (x.hasFee()) {
                        String fk = x.officeId + "|" + x.feeCurrency;
                        bal.merge(fk, Model.FEE_US.equals(x.feeType) ? x.fee : -x.fee, Long::sum);
                    }
                }
                cells.add(new Report.Bal(bal.getOrDefault(k, 0L), dc));
            }
            t.row(x.isActive() ? (n++ % 2 == 1 ? Report.SHADED : Report.NORMAL) : Report.MUTED, cells.toArray());
        }
        while (oi < ops.size()) openingRow(t, d, ops.get(oi++), bal, s.status != S_ACTIVE);
        if (res.rows.isEmpty()) t.note = "لا توجد حركات تطابق الاختيارات.";
        else if (running) t.note = "الرصيد المتراكم بعملة كل سطر، ويشمل أجرة الحوالة إن كانت بالعملة نفسها.";
    }

    static void openingRow(Report.Section t, Data d, OwnerReports.Opening op, Map<String, Long> bal, boolean statusCol) {
        int dc = dec(d, op.currency);
        String k = op.office + "|" + op.currency;
        bal.merge(k, op.signed, Long::sum);
        List<Object> cells = new ArrayList<>();
        cells.add(new Report.DateV(op.when));
        cells.add(op.signed > 0 ? new Report.Money(op.signed, dc) : "");
        cells.add(op.signed > 0 ? "" : new Report.Money(-op.signed, dc));
        cells.add(op.currency);
        cells.add("");
        cells.add("");
        cells.add("رصيد سابق");
        cells.add("");
        if (statusCol) cells.add("");
        cells.add(new Report.Bal(bal.getOrDefault(k, 0L), dc));
        t.row(Report.SHADED, cells.toArray());
    }

    static String statusName(Model.Tx t) {
        if (t.isActive()) return "نشطة";
        if (t.isPending()) return "بانتظار الاعتماد";
        if (t.isRejected()) return "مرفوضة";
        if ("CANCELLED".equals(t.status)) return "ملغاة";
        return Model.nz(t.status);
    }

    static String fmt(Data d, String c, long v) { return Model.fmt(v, dec(d, c)); }

    /** نسبة التغيّر بين فترتين: «+25%»، «−10%»، «جديد»، «—». */
    public static String change(long now, long before) {
        if (before == 0) return now == 0 ? "—" : "جديد";
        BigDecimal p = BigDecimal.valueOf(now - before).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(Math.abs(before)), 0, RoundingMode.HALF_UP);
        return (p.signum() > 0 ? "+" : p.signum() < 0 ? "−" : "") + p.abs().toPlainString() + "%";
    }

    /** CSV (UTF-8 مع BOM ليفتحه Excel بالعربية): كل قسم جدول، بقيم رقمية خام. */
    public static String csv(Report r) {
        StringBuilder b = new StringBuilder("\uFEFF");
        for (Report.Section s : r.sections) {
            if (s.cols.isEmpty()) continue;
            b.append(q(s.heading)).append("\r\n");
            List<String> h = new ArrayList<>();
            for (Report.Col c : s.cols) h.add(q(c.title));
            b.append(Model.join(h, ",")).append("\r\n");
            for (Report.Row row : s.rows) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < s.cols.size(); i++) {
                    Object v = i < row.cells.length ? row.cells[i] : "";
                    if (v instanceof Report.Money) cells.add(BigDecimal.valueOf(((Report.Money) v).v, ((Report.Money) v).dec).toPlainString());
                    else if (v instanceof Report.Bal) cells.add(BigDecimal.valueOf(((Report.Bal) v).v, ((Report.Bal) v).dec).toPlainString());
                    else if (v instanceof Report.DateV) cells.add(Model.day(((Report.DateV) v).t));
                    else cells.add(q(Report.text(v)));
                }
                b.append(Model.join(cells, ",")).append("\r\n");
            }
            b.append("\r\n");
        }
        return b.toString();
    }

    static String q(String s) {
        String v = s == null ? "" : s.replace("\u2066", "").replace("\u2069", "");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@"))
            v = "\"" + (v.startsWith("=") || v.startsWith("+") || v.startsWith("@") ? "'" : "") + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    // ===================================================================================
    // 9.0: الفترات السريعة والقوالب الجاهزة (مشتركة بين الهاتف والماك)
    // ===================================================================================

    public static final String[] PERIODS = {"اليوم", "أمس", "آخر 7 أيام", "هذا الشهر", "الشهر الماضي", "آخر 3 أشهر", "آخر 12 شهرًا", "هذه السنة", "كل الفترات", "مخصصة…"};
    public static final int P_TODAY = 0, P_YESTERDAY = 1, P_WEEK = 2, P_MONTH = 3, P_LAST_MONTH = 4, P_3M = 5, P_12M = 6, P_YEAR = 7, P_ALL = 8, P_CUSTOM = 9;

    public static long startOfDay(long t) {
        Calendar c = Model.cal();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long addDays(long t, int d) {
        Calendar c = Model.cal();
        c.setTimeInMillis(t);
        c.add(Calendar.DAY_OF_MONTH, d);
        return c.getTimeInMillis();
    }

    public static long addMonths(long t, int m) {
        Calendar c = Model.cal();
        c.setTimeInMillis(t);
        c.add(Calendar.MONTH, m);
        return c.getTimeInMillis();
    }

    public static long startOfMonth(long t) {
        Calendar c = Model.cal();
        c.setTimeInMillis(startOfDay(t));
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** فترة سريعة [من، إلى)؛ «كل الفترات» و«مخصصة» = {0، 0}. */
    public static long[] period(int i, long now) {
        long today = startOfDay(now), tomorrow = addDays(today, 1);
        switch (i) {
            case P_TODAY: return new long[]{today, tomorrow};
            case P_YESTERDAY: return new long[]{addDays(today, -1), today};
            case P_WEEK: return new long[]{addDays(today, -6), tomorrow};
            case P_MONTH: return new long[]{startOfMonth(today), tomorrow};
            case P_LAST_MONTH: return new long[]{addMonths(startOfMonth(today), -1), startOfMonth(today)};
            case P_3M: return new long[]{addMonths(today, -3), tomorrow};
            case P_12M: return new long[]{addMonths(today, -12), tomorrow};
            case P_YEAR: {
                Calendar c = Model.cal();
                c.setTimeInMillis(today);
                c.set(Calendar.DAY_OF_YEAR, 1);
                return new long[]{c.getTimeInMillis(), tomorrow};
            }
            default: return new long[]{0, 0};
        }
    }

    /** قالب جاهز: الاسم، والفترة السريعة المقترحة، والمواصفات. */
    public static final class Preset {
        public final String name, hint;
        public final int period;
        public final Spec spec;

        Preset(String name, String hint, int period, Spec spec) {
            this.name = name;
            this.hint = hint;
            this.period = period;
            this.spec = spec;
        }
    }

    public static List<Preset> presets() {
        List<Preset> l = new ArrayList<>();
        Spec s;
        s = new Spec();
        s.title = "ملخص المكاتب";
        s.group = G_OFFICE;
        s.sort = SORT_VOLUME;
        l.add(new Preset("ملخص المكاتب", "لنا وعلينا والصافي والأجور لكل مكتب", P_MONTH, s));
        s = new Spec();
        s.title = "الأجور لنا وعلينا لكل مكتب";
        s.group = G_OFFICE;
        s.sort = SORT_FEES;
        l.add(new Preset("الأجور لنا وعلينا لكل مكتب", "ما قبضناه من أجور وما دفعناه، لكل مكتب", P_MONTH, s));
        s = new Spec();
        s.title = "صافي الأجور شهريًا";
        s.group = G_MONTH;
        s.group2 = G_CURRENCY;
        s.measure = M_FEES_NET;
        l.add(new Preset("الأجور لنا وعلينا شهريًا", "صافي الأجور لكل شهر وعملة", P_12M, s));
        s = new Spec();
        s.title = "مقارنة المكاتب بالفترة السابقة";
        s.group = G_OFFICE;
        s.compare = true;
        s.sort = SORT_VOLUME;
        l.add(new Preset("مقارنة المكاتب بالفترة السابقة", "من نما ومن تراجع", P_LAST_MONTH, s));
        s = new Spec();
        s.title = "عدد العمليات: المكتب × الشهر";
        s.group = G_OFFICE;
        s.group2 = G_MONTH;
        s.measure = M_COUNT;
        s.sort = SORT_COUNT;
        s.top = 25;
        l.add(new Preset("المكتب × الشهر", "جدول متقاطع بعدد العمليات", P_12M, s));
        s = new Spec();
        s.title = "ساعات الذروة";
        s.group = G_HOUR;
        s.group2 = G_WEEKDAY;
        s.measure = M_COUNT;
        l.add(new Preset("ساعات الذروة وأيام الأسبوع", "متى يكثر العمل", P_3M, s));
        s = new Spec();
        s.title = "أكبر الحوالات";
        s.group = G_NONE;
        s.kind = K_TRANSFERS;
        s.minAmount = new BigDecimal("10000");
        l.add(new Preset("الحوالات من 10,000 فأكثر", "كشف تفصيلي بالحوالات الكبيرة", P_MONTH, s));
        s = new Spec();
        s.title = "الخدمات والوجهات";
        s.group = G_SERVICE;
        s.group2 = G_DEST;
        s.measure = M_COUNT;
        l.add(new Preset("الخدمات × الوجهات", "أي خدمة إلى أي وجهة", P_3M, s));
        s = new Spec();
        s.title = "حركات كل مستخدم";
        s.group = G_USER;
        s.details = true;
        l.add(new Preset("حركات كل مستخدم", "من سجّل ماذا", P_TODAY, s));
        s = new Spec();
        s.title = "شرائح المبالغ";
        s.group = G_SIZE;
        s.kind = K_TRANSFERS;
        l.add(new Preset("توزيع الحوالات حسب المبلغ", "صغيرة ومتوسطة وكبيرة", P_12M, s));
        s = new Spec();
        s.title = "المعدّلة وتصحيحاتها";
        s.status = S_EDITED;
        s.group = G_NONE;
        l.add(new Preset("المعدّلة وتصحيحاتها", "كل حركة عُدّلت مع تصحيحها", P_MONTH, s));
        s = new Spec();
        s.title = "أكثر المستفيدين";
        s.group = G_PERSON;
        s.sort = SORT_COUNT;
        s.top = 30;
        l.add(new Preset("أكثر 30 مستفيدًا", "الأسماء الأكثر تكرارًا", P_12M, s));
        s = new Spec();
        s.title = "ملخص أسبوعي";
        s.group = G_WEEK;
        s.compare = true;
        l.add(new Preset("ملخص أسبوعي", "كل أسبوع مع المقارنة", P_3M, s));
        s = new Spec();
        s.title = "ملخص يومي";
        s.group = G_DAY;
        s.group2 = G_CURRENCY;
        s.measure = M_IMPACT;
        l.add(new Preset("ملخص يومي بالعملات", "أثر كل يوم على الأرصدة", P_MONTH, s));
        l.addAll(presets1012());
        return l;
    }

    /**
     * 10.12: تقارير جاهزة لما أُضيف منذ 10.5: قص العملات (المقاصة) والحركات بين مكتبين.
     * في قص العملات: العملة المشتراة تُسجَّل «لنا» والمباعة «علينا»، فمجموع «لنا» لعملة = ما اشتريناه منها، و«علينا» = ما بعناه.
     * في الحركات بين مكتبين: الطالب «لنا» والمنفّذ «علينا»، فلكل حركة ساقان متساويتان والصافي على كل المكاتب صفر.
     */
    public static List<Preset> presets1012() {
        List<Preset> l = new ArrayList<>();
        Spec s;
        s = new Spec();
        s.title = "عمليات قص العملات";
        s.kind = K_FX;
        s.group = G_NONE;
        l.add(new Preset("عمليات قص العملات", "كل عملية بعملتيها وسعرها وطرفيها", P_3M, s));
        s = new Spec();
        s.title = "قص العملات لكل عملة (المشترى لنا والمباع علينا)";
        s.kind = K_FX;
        s.group = G_CURRENCY;
        s.sort = SORT_VOLUME;
        l.add(new Preset("قص العملات لكل عملة", "كم اشترينا (لنا) وكم بعنا (علينا) من كل عملة، والصافي", P_3M, s));
        s = new Spec();
        s.title = "قص العملات: المكتب × العملة";
        s.kind = K_FX;
        s.group = G_OFFICE;
        s.group2 = G_CURRENCY;
        s.measure = M_NET;
        s.sort = SORT_VOLUME;
        l.add(new Preset("قص العملات: المكتب × العملة", "صافي ما تغيّر في رصيد كل مكتب بكل عملة بسبب القص", P_3M, s));
        s = new Spec();
        s.title = "الحركات بين مكتبين";
        s.kind = K_XFER;
        s.group = G_NONE;
        l.add(new Preset("الحركات بين مكتبين", "كشف تفصيلي: الطالب (لنا) والمنفّذ (علينا) والأجرة", P_MONTH, s));
        s = new Spec();
        s.title = "الحركات بين مكتبين لكل مكتب";
        s.kind = K_XFER;
        s.group = G_OFFICE;
        s.group2 = G_CURRENCY;
        s.measure = M_NET;
        s.sort = SORT_VOLUME;
        l.add(new Preset("الحركات بين مكتبين لكل مكتب", "من يطلب أكثر ومن ينفّذ أكثر، بكل عملة", P_MONTH, s));
        s = new Spec();
        s.title = "أنواع الحركات شهريًا";
        s.group = G_MONTH;
        s.group2 = G_KIND;
        s.measure = M_COUNT;
        l.add(new Preset("أنواع الحركات شهريًا", "الحوالات والأجور وقص العملات والحركات بين مكتبين", P_12M, s));
        return l;
    }
}
