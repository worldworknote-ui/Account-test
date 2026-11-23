package com.smart.hawala;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 9.0: «صندوق المكتب» — منطق بحت مشترك بين الهاتف والماك (والمنصات القادمة).
 *
 * <p>الصندوق يضبط النقد الموجود فعلًا لدى صاحب المكتب بكل عملة، مستقلًا عن أرصدة المكاتب: حساباته في جدول خاص
 * (cash_moves) ولا تدخل دفتر قيود المكاتب. الربط الوحيد بالمكاتب حركة «استلام» أو «تسليم» لحساب مكتب:
 * تُسجَّل حركة عادية على المكتب (بقيودها) في المعاملة نفسها، ويُحفظ رقمها مع حركة الصندوق، فتتبع حالتها
 * (بانتظار الاعتماد أو مرفوضة = لا تُحسب في الصندوق، وإلغاؤها يلغي الأخرى).
 *
 * <p>القاعدة المحاسبية: رصيد الصندوق بعملة = مجموع «أثر» حركاته النشطة، والأثر = اتجاه × المبلغ + أجور نقدية
 * (لنا تدخل، علينا تخرج). أجور الحركة المرتبطة بمكتب تُقيَّد افتراضيًا على حساب المكتب (لا تمس النقد)
 * إلا إذا قُبضت أو دُفعت نقدًا. خلاصة أي فترة تحقق: آخر الفترة = أولها + الداخل − الخارج (يتحقق منه الاختبار).
 */
public final class CashBox {
    private CashBox() {}

    // أنواع الحركات
    public static final String OPENING = "OPENING", IN = "IN", OUT = "OUT", EXPENSE = "EXPENSE", EXCHANGE = "EXCHANGE", ADJUST = "ADJUST", TRANSFER = "TRANSFER";
    /** 9.0.1: بيع عملة (تخرج من الصندوق ويدخل مقابلها) أو شراؤها (تدخل ويخرج مقابلها). */
    public static final String SELL = "SELL", BUY = "BUY";
    public static final String ACTIVE = "ACTIVE", CANCELLED = "CANCELLED";
    /** جهة الأجرة: لنا (نقبضها) أو علينا (ندفعها). */
    public static final String FEE_US = "US", FEE_THEM = "THEM";

    public static final String DEFAULT_NAME = "صندوق المكتب";

    public static final String[] EXPENSE_CATS = {"رواتب وأجور العمال", "إيجار المحل", "كهرباء وماء", "إنترنت واتصالات", "ضيافة ونثريات", "مواصلات",
            "صيانة ومستلزمات", "رسوم وضرائب", "قرطاسية وطباعة", "أخرى"};
    public static final String[] IN_CATS = {"استلام من شخص", "إيداع من صاحب المكتب", "أجور وعمولات نقدية", "سحب من البنك", "أخرى"};
    public static final String[] OUT_CATS = {"تسليم لشخص", "سحب لصاحب المكتب", "إيداع في البنك", "سلفة", "أخرى"};
    public static final String CAT_OFFICE_IN = "استلام لحساب مكتب", CAT_OFFICE_OUT = "تسليم لحساب مكتب", CAT_COUNT = "فرق جرد", CAT_OPENING = "رصيد افتتاحي",
            CAT_EXCHANGE = "صرف عملة", CAT_SELL = "بيع عملة", CAT_BUY = "شراء عملة", CAT_TRANSFER = "تحويل بين الصناديق",
            CAT_CLIENT_IN = "استلام من عميل", CAT_CLIENT_OUT = "تسليم لعميل";

    public static String kindName(String kind) {
        switch (kind == null ? "" : kind) {
            case OPENING: return "رصيد افتتاحي";
            case IN: return "استلام";
            case OUT: return "تسليم";
            case EXPENSE: return "مصروف";
            case EXCHANGE: return "بيع وشراء عملة";
            case TRANSFER: return "تحويل بين الصناديق";
            case ADJUST: return "تسوية جرد";
            default: return kind;
        }
    }

    // ===================================================================================
    // النموذج
    // ===================================================================================

    /** حركة صندوق كما تُقرأ من القاعدة (txStatus: حالة حركة المكتب المرتبطة، أو null). */
    public static final class Move {
        public long id;
        public String uuid = "", kind = IN, currency = "";
        public long amount;
        /** +1 داخل الصندوق، −1 خارج منه. */
        public int dir = 1;
        public long fee;
        public String feeSide = "";
        public boolean feeInCash;
        public String category = "", party = "", description = "", userName = "", cancelReason = "";
        public Long officeId, txId;
        public String txStatus;
        public String pairUuid, rate;
        public String status = ACTIVE;
        public long createdAt;
        /** 9.0.1: الصندوق الذي تخصه الحركة. */
        public long boxId = 1;
        /** 9.0.1: حساب العميل المرتبط (أو null). */
        public Long clientId;

        /** ساق البيع/الشراء الرئيسية (العملة المتداولة، وفيها السعر). */
        public boolean mainLeg() { return CAT_SELL.equals(category) ? dir < 0 : CAT_BUY.equals(category) && dir > 0; }

        public boolean linked() { return txId != null; }

        public boolean cancelled() { return CANCELLED.equals(status); }

        /** تُحسب في الصندوق: نشطة، وحركة المكتب المرتبطة (إن وُجدت) معتمدة نشطة. */
        public boolean counts() { return ACTIVE.equals(status) && (txId == null || Model.STATUS_ACTIVE.equals(txStatus)); }

        public boolean pending() { return ACTIVE.equals(status) && txId != null && Model.STATUS_PENDING.equals(txStatus); }

        /** الأجرة النقدية بإشارتها (لنا +، علينا −)، وصفر إن كانت على حساب المكتب. */
        public long feeCash() {
            if (!feeInCash || fee <= 0) return 0;
            return FEE_US.equals(feeSide) ? fee : FEE_THEM.equals(feeSide) ? -fee : 0;
        }

        /** أثر الحركة على نقد الصندوق بعملتها. */
        public long effect() { return counts() ? dir * amount + feeCash() : 0; }

        public String stateText() {
            if (cancelled()) return "ملغاة";
            if (txId != null && Model.STATUS_PENDING.equals(txStatus)) return "بانتظار الاعتماد";
            if (txId != null && Model.STATUS_REJECTED.equals(txStatus)) return "مرفوضة";
            if (txId != null && "CANCELLED".equals(txStatus)) return "ملغاة";
            return "";
        }
    }

    /** إعدادات الصندوق. */
    public static final class Config {
        /** 9.0.1: رقم الصندوق (عدة صناديق). */
        public long id = 1;
        public boolean active = true;
        public boolean on;
        public String name = DEFAULT_NAME;
        public final List<String> currencies = new ArrayList<>();
        /** الربط الإلزامي: الاستلام والتسليم لحساب مكتب فقط. */
        public boolean strict;
        /** مع الربط الإلزامي: هل تُسمح المصاريف وصرف العملات (لا تخص مكتبًا)؟ */
        public boolean allowInternal = true;
        public final List<String> expenseCats = new ArrayList<>(Arrays.asList(EXPENSE_CATS));
        public final List<String> inCats = new ArrayList<>(Arrays.asList(IN_CATS));
        public final List<String> outCats = new ArrayList<>(Arrays.asList(OUT_CATS));

        public static List<String> list(String s) {
            List<String> out = new ArrayList<>();
            if (s == null) return out;
            for (String p : s.split(",")) if (!p.trim().isEmpty() && !out.contains(p.trim())) out.add(p.trim());
            return out;
        }

        public static String join(List<String> l) {
            StringBuilder b = new StringBuilder();
            for (String x : l) {
                if (b.length() > 0) b.append(',');
                b.append(x.replace(",", "،").trim());
            }
            return b.toString();
        }

        /** البنود الإضافية التي يكتبها المستخدم تُضاف قبل «أخرى». */
        static void addExtra(List<String> base, String extra) {
            for (String x : list(extra)) {
                if (base.contains(x)) continue;
                int at = base.indexOf("أخرى");
                if (at < 0) base.add(x);
                else base.add(at, x);
            }
        }

        public static Config from(Map<String, String> settings) {
            Config c = new Config();
            c.on = "1".equals(settings.get("cash_on"));
            String n = settings.get("cash_name");
            if (n != null && !n.trim().isEmpty()) c.name = n.trim();
            c.currencies.addAll(list(settings.get("cash_currencies")));
            c.strict = "1".equals(settings.get("cash_strict"));
            c.allowInternal = !"0".equals(settings.get("cash_allow_internal"));
            addExtra(c.expenseCats, settings.get("cash_cats_exp"));
            addExtra(c.inCats, settings.get("cash_cats_in"));
            addExtra(c.outCats, settings.get("cash_cats_out"));
            return c;
        }

        /**
         * 9.0.1: كل الصناديق. الأول (رقم 1) من الإعدادات القديمة كما هو (متوافق مع 9.0 والماك)، والإضافية في «cash_boxes»
         * (JSON: id, name, cur, active)، وترث الربط الإلزامي والبنود من الأول (سياسة المكتب واحدة).
         */
        public static List<Config> boxes(Map<String, String> settings) {
            List<Config> out = new ArrayList<>();
            Config main = from(settings);
            out.add(main);
            if (!main.on) return out;
            try {
                org.json.JSONArray a = new org.json.JSONArray(Model.nz(settings.get("cash_boxes")).isEmpty() ? "[]" : settings.get("cash_boxes"));
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.getJSONObject(i);
                    long id = o.optLong("id", 0);
                    if (id <= 1) continue;
                    Config c = from(settings);
                    c.id = id;
                    c.name = o.optString("name", "صندوق " + id).trim();
                    if (c.name.isEmpty()) c.name = "صندوق " + id;
                    c.currencies.clear();
                    c.currencies.addAll(list(o.optString("cur", "")));
                    c.active = o.optBoolean("active", true);
                    out.add(c);
                }
            } catch (Exception ignored) {
                // قائمة تالفة: الصندوق الرئيسي وحده
            }
            return out;
        }

        /** قائمة الصناديق الإضافية بصيغة الحفظ. */
        public static String boxesJson(List<Config> boxes) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Config c : boxes) {
                if (c.id <= 1) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                try {
                    o.put("id", c.id).put("name", c.name).put("cur", join(c.currencies)).put("active", c.active);
                } catch (Exception ignored) {
                    // لا يحدث
                }
                a.put(o);
            }
            return a.toString();
        }

        public static Config find(List<Config> boxes, long id) {
            for (Config c : boxes) if (c.id == id) return c;
            return null;
        }
    }

    /** إدخال حركة صندوق جديدة (الواجهة تملؤه، وDb يتحقق به ثم يكتب). */
    public static final class Input {
        public String kind = IN, currency;
        public long amount;
        /** لـ ADJUST فقط: +1 زيادة، −1 عجز. */
        public int adjustDir = 1;
        public long fee;
        public String feeSide = "";
        /** الأجرة نقدًا في الصندوق؛ بدون مكتب تكون نقدًا دائمًا. */
        public boolean feeInCash = true;
        public String category = "", party = "", description = "";
        public Long officeId;
        public long when;
        /** صرف العملة: العملة المستلمة ومبلغها وسعر الصرف المكتوب. */
        public String toCurrency;
        public long toAmount;
        public String rate = "";
        /** 9.0.1: الصندوق (عدة صناديق)، وصندوق الوجهة للتحويل، وجهة العملية (بيع/شراء). */
        public long boxId = 1;
        public Long toBox;
        public String side = SELL;
        /** 9.0.1: حساب العميل، والعملة والمبلغ اللذان يُقيَّدان على حسابه (قد تختلف عن عملة النقد). */
        public Long clientId;
        public String clientCurrency;
        public long clientAmount;

        public int dir() {
            switch (kind) {
                case OPENING: case IN: return 1;
                case ADJUST: return adjustDir >= 0 ? 1 : -1;
                case EXCHANGE: return BUY.equals(side) ? 1 : -1;
                default: return -1;
            }
        }

        /** العملة والمبلغ الخارجان من الصندوق (للتنبيه من العجز)، أو null إن لم يخرج شيء. */
        public Object[] outgoing() {
            if (EXCHANGE.equals(kind)) return BUY.equals(side) ? new Object[]{toCurrency, toAmount} : new Object[]{currency, amount};
            long fee = this.fee > 0 && (officeId == null || feeInCash) ? (FEE_US.equals(feeSide) ? this.fee : -this.fee) : 0;
            long change = dir() * amount + fee;
            return change < 0 ? new Object[]{currency, -change} : null;
        }
    }

    /**
     * 9.0.1: أثر الإدخال على نقد الصندوق لكل عملة (قبل التسجيل، للمعاينة): الداخل موجب والخارج سالب.
     * التحويل: أثره على الصندوق المصدر فقط (الوجهة تستلم المبلغ نفسه).
     */
    public static Map<String, Long> changes(Input in) {
        Map<String, Long> m = new LinkedHashMap<>();
        if (in == null || in.currency == null) return m;
        if (EXCHANGE.equals(in.kind)) {
            int d = in.dir();
            m.put(in.currency, d * in.amount);
            if (in.toCurrency != null) m.put(in.toCurrency, -d * in.toAmount);
            return m;
        }
        if (TRANSFER.equals(in.kind)) {
            m.put(in.currency, -in.amount);
            return m;
        }
        long fee = in.fee > 0 && (in.officeId == null || in.feeInCash) ? (FEE_US.equals(in.feeSide) ? in.fee : -in.fee) : 0;
        m.put(in.currency, in.dir() * in.amount + fee);
        return m;
    }

    /** نص عملية البيع أو الشراء: «بيع 1,000 USD بسعر 0.92 = 920 EUR». */
    public static String exchangeText(Input in, Map<String, Integer> decimals) { return exchangeText(in, decimals, false); }

    /** iso: عزل الأرقام باتجاهها (للعرض على الشاشة؛ لا للتقارير المطبوعة). */
    public static String exchangeText(Input in, Map<String, Integer> decimals, boolean iso) {
        String l = iso ? "\u2066" : "", r = iso ? "\u2069" : "";
        String t = (BUY.equals(in.side) ? "شراء " : "بيع ") + l + Model.fmt(in.amount, dec(decimals, in.currency)) + " " + in.currency + r;
        if (!Model.nz(in.rate).isEmpty()) t += " بسعر " + l + in.rate + " " + in.toCurrency + r;
        return t + (BUY.equals(in.side) ? "، تدفع " : "، تستلم ") + l + Model.fmt(in.toAmount, dec(decimals, in.toCurrency)) + " " + in.toCurrency + r;
    }

    /**
     * 9.0.1: قراءة سعر صرف كما يكتبه الناس: النقطة الوحيدة عشرية دائمًا (1.085 = واحد وخمسة وثمانون جزءًا من ألف، لا 1085)،
     * والفاصلة الوحيدة قبل 3 أرقام فاصلة آلاف (13,500) وإلا فعشرية (0,92)، والأرقام العربية مقبولة. null للفارغ أو غير الصالح.
     */
    public static BigDecimal rateDecimal(String text) {
        if (text == null) return null;
        StringBuilder b = new StringBuilder();
        for (char ch : text.trim().toCharArray()) {
            if (ch >= 0x0660 && ch <= 0x0669) b.append((char) ('0' + (ch - 0x0660)));
            else if (ch >= 0x06F0 && ch <= 0x06F9) b.append((char) ('0' + (ch - 0x06F0)));
            else if (ch == 0x066B) b.append('.');
            else if (ch == 0x066C || ch == 0x060C) b.append(',');
            else if (ch == ' ' || ch == 0x00A0 || ch == 0x200F || ch == 0x200E || ch == '\'') continue;
            else b.append(ch);
        }
        String s = b.toString();
        if (s.isEmpty()) return null;
        int dots = s.length() - s.replace(".", "").length(), commas = s.length() - s.replace(",", "").length();
        if (dots > 0 && commas > 0) {
            boolean dotLast = s.lastIndexOf('.') > s.lastIndexOf(',');
            s = dotLast ? s.replace(",", "") : s.replace(".", "").replace(',', '.');
        } else if (dots > 1) s = s.replace(".", "");
        else if (commas > 1) s = s.replace(",", "");
        else if (commas == 1) {
            String after = s.substring(s.indexOf(',') + 1);
            s = after.length() == 3 && s.indexOf(',') > 0 ? s.replace(",", "") : s.replace(',', '.');
        }
        if (!s.matches("[0-9]*\\.?[0-9]+|[0-9]+\\.")) return null;
        try {
            BigDecimal r = new BigDecimal(s);
            return r.signum() > 0 ? r.stripTrailingZeros() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * المبلغ المقابل = المبلغ × السعر (السعر: كم من العملة المقابلة لكل وحدة واحدة)، مقرّبًا لمنازل العملة المقابلة.
     * null إن كان السعر غير صالح.
     */
    public static Long counterAmount(long amountMinor, int dec, String rate, int counterDec) {
        try {
            BigDecimal r = rateDecimal(rate);
            if (r == null) return null;
            return BigDecimal.valueOf(amountMinor, dec).multiply(r).setScale(counterDec, RoundingMode.HALF_EVEN).movePointRight(counterDec).longValueExact();
        } catch (Exception e) {
            return null;
        }
    }

    /** سعر مقترح من أسعار الصرف (الدولار): كم من cur2 لكل وحدة من cur1. */
    public static BigDecimal crossRate(String cur1, String cur2, Map<String, BigDecimal> usdRates) {
        BigDecimal a = "USD".equals(cur1) ? BigDecimal.ONE : usdRates.get(cur1), b = "USD".equals(cur2) ? BigDecimal.ONE : usdRates.get(cur2);
        if (a == null || b == null || a.signum() <= 0 || b.signum() <= 0) return null;
        BigDecimal r = a.divide(b, 10, RoundingMode.HALF_EVEN);
        int digits = r.compareTo(BigDecimal.TEN) >= 0 ? 2 : r.compareTo(BigDecimal.ONE) >= 0 ? 4 : 6;
        return r.setScale(digits, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    /** null = الإدخال صالح، وإلا سبب الرفض بلغة المستخدم. */
    public static String validate(Input in, Config c, long now) {
        if (in == null) return "لا يوجد إدخال";
        if (!c.on && !OPENING.equals(in.kind)) return "الصندوق غير مُعَد بعد. اضبطه أولًا من «صندوق المكتب».";
        List<String> kinds = Arrays.asList(OPENING, IN, OUT, EXPENSE, EXCHANGE, ADJUST, TRANSFER);
        if (!kinds.contains(in.kind)) return "نوع حركة الصندوق غير صالح";
        if (in.amount <= 0) return "المبلغ يجب أن يكون أكبر من صفر";
        if (in.currency == null || !c.currencies.contains(in.currency)) return "العملة " + Model.nz(in.currency) + " غير مفعّلة في الصندوق. أضفها من إعدادات الصندوق.";
        if (in.when <= 0) return "تاريخ الحركة غير صالح";
        if (in.when > now + 10 * 60000L) return "لا يمكن تسجيل حركة صندوق بتاريخ في المستقبل";
        boolean office = in.officeId != null, client = in.clientId != null;
        if (office && client) return "الحركة لحساب مكتب أو لحساب عميل، لا الاثنين";
        if (client && !IN.equals(in.kind) && !OUT.equals(in.kind)) return kindName(in.kind) + " لا يُربط بعميل";
        switch (in.kind) {
            case IN:
            case OUT:
                if (c.strict && !office && !client) return "الربط الإلزامي مفعّل في هذا الصندوق: لا يدخل الصندوق ولا يخرج منه إلا لحساب مكتب أو عميل. اختر الحساب.";
                if (!office && !client && Model.nz(in.category).trim().isEmpty()) return "اختر البند (مثل: استلام من شخص، سحب لصاحب المكتب)";
                if (client) {
                    String ce = ClientBook.validateLink(in.currency, in.amount, in.clientCurrency, in.clientAmount);
                    if (ce != null) return ce;
                }
                break;
            case EXPENSE:
                if (office) return "المصروف لا يُربط بمكتب";
                if (c.strict && !c.allowInternal) return "الربط الإلزامي مفعّل ولا يسمح بالمصاريف. غيّر ذلك من إعدادات الصندوق.";
                if (Model.nz(in.category).trim().isEmpty()) return "اختر بند المصروف (رواتب، إيجار، كهرباء…)";
                break;
            case EXCHANGE:
                if (office) return "البيع والشراء داخل الصندوق لا يُربط بمكتب";
                if (c.strict && !c.allowInternal) return "الربط الإلزامي مفعّل ولا يسمح بالبيع والشراء داخل الصندوق. غيّر ذلك من إعدادات الصندوق.";
                if (!SELL.equals(in.side) && !BUY.equals(in.side)) return "حدّد: بيع أم شراء";
                if (in.toCurrency == null || !c.currencies.contains(in.toCurrency)) return "اختر العملة المقابلة (مفعّلة في الصندوق)";
                if (in.toCurrency.equals(in.currency)) return "البيع والشراء بين عملتين مختلفتين";
                if (in.toAmount <= 0) return "اكتب السعر أو المبلغ المقابل";
                if (in.fee > 0) return "البيع والشراء بلا أجرة منفصلة: الربح في السعر نفسه";
                break;
            case TRANSFER:
                if (office) return "التحويل بين الصناديق لا يُربط بمكتب";
                if (in.toBox == null || in.toBox == in.boxId) return "اختر الصندوق الآخر";
                if (in.fee > 0) return "التحويل بين الصناديق بلا أجور";
                break;
            case OPENING:
            case ADJUST:
                if (office) return kindName(in.kind) + " لا يُربط بمكتب";
                if (in.fee > 0) return kindName(in.kind) + " بلا أجور";
                break;
            default:
                break;
        }
        if (in.fee < 0) return "الأجرة غير صالحة";
        if (in.fee > 0 && !FEE_US.equals(in.feeSide) && !FEE_THEM.equals(in.feeSide)) return "حدّد الأجور: لنا أم علينا";
        if (in.fee > 0 && EXPENSE.equals(in.kind)) return "المصروف بلا أجور منفصلة؛ اكتب المبلغ كاملًا";
        return null;
    }

    // ===================================================================================
    // الأرصدة والخلاصات
    // ===================================================================================

    /** رصيد الصندوق لكل عملة (المعدودة فقط). */
    public static Map<String, Long> balances(List<Move> moves, List<String> currencies) {
        Map<String, Long> m = new TreeMap<>();
        if (currencies != null) for (String c : currencies) m.put(c, 0L);
        for (Move x : moves) {
            long e = x.effect();
            if (e == 0 && m.containsKey(x.currency)) continue;
            Long b = m.get(x.currency);
            m.put(x.currency, (b == null ? 0 : b) + e);
        }
        return m;
    }

    /**
     * 10.5 (AUDIT-028): النقد الموجود فعلًا في الصندوق لحركات مكاتب ما زالت بانتظار الاعتماد (لكل عملة).
     * النقد دخل أو خرج، لكنه لا يُحسب في الرصيد حتى تُعتمد حركة المكتب؛ فالجرد يقارن المعدود بالرصيد + هذا المعلّق.
     */
    public static Map<String, Long> pendingCash(List<Move> moves) {
        Map<String, Long> m = new TreeMap<>();
        for (Move x : moves) {
            if (!x.pending()) continue;
            long e = x.dir * x.amount + (x.feeInCash && x.fee > 0 ? (FEE_US.equals(x.feeSide) ? x.fee : FEE_THEM.equals(x.feeSide) ? -x.fee : 0) : 0);
            Long b = m.get(x.currency);
            m.put(x.currency, (b == null ? 0 : b) + e);
        }
        m.values().removeIf(v -> v == 0);
        return m;
    }

    /** خلاصة عملة واحدة لفترة [from, to). */
    public static final class Summary {
        public String currency;
        public long opening, in, out, feesIn, feesOut, expenses, adjustUp, adjustDown, exchangeIn, exchangeOut, openingsInPeriod, closing;
        public long officeIn, officeOut, count;
        /** 9.0.1: التحويل بين الصناديق (وارد وصادر). */
        public long transferIn, transferOut;
        public final Map<String, Long> byCategory = new TreeMap<>();

        /** يجب أن يساوي closing دائمًا. */
        public long computedClosing() {
            return opening + openingsInPeriod + in - out + feesIn - feesOut - expenses + adjustUp - adjustDown + exchangeIn - exchangeOut + transferIn - transferOut;
        }
    }

    public static Map<String, Summary> summaries(List<Move> moves, long from, long to, Set<String> only) {
        Map<String, Summary> out = new TreeMap<>();
        for (Move x : moves) {
            if (only != null && !only.isEmpty() && !only.contains(x.currency)) continue;
            Summary s = out.get(x.currency);
            if (s == null) {
                s = new Summary();
                s.currency = x.currency;
                out.put(x.currency, s);
            }
            long e = x.effect();
            if (x.createdAt < from) {
                s.opening += e;
                continue;
            }
            if (to > 0 && x.createdAt >= to) continue;
            if (!x.counts()) continue;
            s.count++;
            long fc = x.feeCash();
            if (fc > 0) s.feesIn += fc;
            else s.feesOut -= fc;
            switch (x.kind) {
                case OPENING: s.openingsInPeriod += x.amount; break;
                case IN:
                    s.in += x.amount;
                    if (x.officeId != null) s.officeIn += x.amount;
                    break;
                case OUT:
                    s.out += x.amount;
                    if (x.officeId != null) s.officeOut += x.amount;
                    break;
                case EXPENSE:
                    s.expenses += x.amount;
                    String cat = Model.nz(x.category).trim().isEmpty() ? "أخرى" : x.category.trim();
                    Long v = s.byCategory.get(cat);
                    s.byCategory.put(cat, (v == null ? 0 : v) + x.amount);
                    break;
                case EXCHANGE:
                    if (x.dir > 0) s.exchangeIn += x.amount;
                    else s.exchangeOut += x.amount;
                    break;
                case ADJUST:
                    if (x.dir > 0) s.adjustUp += x.amount;
                    else s.adjustDown += x.amount;
                    break;
                case TRANSFER:
                    if (x.dir > 0) s.transferIn += x.amount;
                    else s.transferOut += x.amount;
                    break;
                default: break;
            }
        }
        for (Summary s : out.values()) s.closing = s.computedClosing();
        return out;
    }

    /** الرصيد قبل لحظة لكل عملة. */
    public static Map<String, Long> balanceBefore(List<Move> moves, long t) {
        Map<String, Long> m = new TreeMap<>();
        for (Move x : moves) {
            if (x.createdAt >= t) continue;
            Long b = m.get(x.currency);
            m.put(x.currency, (b == null ? 0 : b) + x.effect());
        }
        return m;
    }

    /** ترتيب زمني ثابت (التاريخ ثم الرقم). */
    public static void sort(List<Move> moves) {
        Collections.sort(moves, new Comparator<Move>() {
            @Override public int compare(Move a, Move b) {
                int c = Long.compare(a.createdAt, b.createdAt);
                return c != 0 ? c : Long.compare(a.id, b.id);
            }
        });
    }

    // ===================================================================================
    // التقرير (PDF / Excel / الشاشة)
    // ===================================================================================

    /** ما يُعرض في تقرير الصندوق. */
    public static final class ReportSpec {
        public long from, to;
        public final Set<String> currencies = new TreeSet<>();
        public boolean details = true, byDay = true, byOffice = true;
    }

    public static int dec(Map<String, Integer> d, String c) {
        Integer v = d.get(c);
        return v == null ? 2 : v;
    }

    static String moveText(Move x, Map<Long, String> offices) {
        if (x.clientId != null) return (x.dir > 0 ? "استلام من العميل " : "تسليم للعميل ") + Model.nz(x.party);
        if (EXCHANGE.equals(x.kind) && (CAT_SELL.equals(x.category) || CAT_BUY.equals(x.category)))
            return (x.mainLeg() ? x.category : (CAT_SELL.equals(x.category) ? "مقابل بيع" : "مقابل شراء")) + (Model.nz(x.rate).isEmpty() ? "" : " بسعر " + x.rate);
        if (TRANSFER.equals(x.kind)) return x.dir > 0 ? "تحويل وارد من صندوق آخر" : "تحويل إلى صندوق آخر";
        StringBuilder b = new StringBuilder(kindName(x.kind));
        if (x.officeId != null) {
            String n = offices.get(x.officeId);
            b.append(x.dir > 0 ? " لحساب " : " لحساب ").append(n == null ? "مكتب #" + x.officeId : n);
        } else if (!Model.nz(x.category).isEmpty() && !OPENING.equals(x.kind)) b.append(" — ").append(x.category);
        return b.toString();
    }

    public static Report report(List<Move> all, ReportSpec sp, Map<String, Integer> decimals, Map<Long, String> offices, String boxName, String issuer) {
        List<Move> moves = new ArrayList<>(all);
        sort(moves);
        Report r = new Report();
        r.title = "تقرير " + (boxName == null || boxName.trim().isEmpty() ? DEFAULT_NAME : boxName.trim());
        r.fileBase = "تقرير-الصندوق";
        if (issuer != null && !issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + issuer.trim());
        r.headerLines.add("الفترة: " + (sp.from <= 0 ? "من البداية" : "من " + Model.day(sp.from)) + " " + (sp.to <= 0 ? "حتى الآن" : "إلى " + Model.day(sp.to - 1)));
        Map<String, Summary> sums = summaries(moves, sp.from, sp.to, sp.currencies);

        Report.Section s = r.section("خلاصة الصندوق", "الخلاصة");
        s.col("العملة", 0.6f, Report.TEXT).col("أول الفترة", 1f, Report.MONEY).col("الداخل", 1f, Report.MONEY).col("الخارج", 1f, Report.MONEY)
                .col("أجور لنا", 0.9f, Report.MONEY).col("أجور علينا", 0.9f, Report.MONEY).col("المصاريف", 0.9f, Report.MONEY)
                .col("فروق الجرد", 0.9f, Report.TEXT).col("آخر الفترة", 1f, Report.MONEY);
        for (Summary m : sums.values()) {
            int dc = dec(decimals, m.currency);
            long inAll = m.in + m.exchangeIn + m.openingsInPeriod + m.transferIn, outAll = m.out + m.exchangeOut + m.transferOut;
            long adj = m.adjustUp - m.adjustDown;
            s.row(Report.TOTAL, m.currency, new Report.Money(m.opening, dc), new Report.Money(inAll, dc), new Report.Money(outAll, dc),
                    new Report.Money(m.feesIn, dc), new Report.Money(m.feesOut, dc), new Report.Money(m.expenses, dc),
                    adj == 0 ? "—" : (adj > 0 ? "زيادة " : "عجز ") + Model.fmt(Math.abs(adj), dc), new Report.Money(m.closing, dc));
            s.kv("بـ" + m.currency, "أول الفترة " + Model.fmt(m.opening, dc) + " + الداخل " + Model.fmt(inAll, dc) + " − الخارج " + Model.fmt(outAll, dc)
                    + " + أجور لنا " + Model.fmt(m.feesIn, dc) + " − أجور علينا " + Model.fmt(m.feesOut, dc) + " − المصاريف " + Model.fmt(m.expenses, dc)
                    + (adj == 0 ? "" : (adj > 0 ? " + زيادة جرد " : " − عجز جرد ") + Model.fmt(Math.abs(adj), dc))
                    + " = " + Model.fmt(m.closing, dc) + " " + m.currency, false);
        }
        if (sums.isEmpty()) s.kv("لا حركات", "لا توجد حركات في الصندوق بهذه الشروط", false);
        s.note = "الصندوق مستقل عن أرصدة المكاتب: يضبط النقد الموجود فعلًا. «الداخل» يشمل الاستلام وما دخل من بيع وشراء العملات والتحويل الوارد من صندوق آخر والأرصدة الافتتاحية داخل الفترة؛ "
                + "و«الخارج» التسليم وما خرج في البيع والشراء والتحويل إلى صندوق آخر. أجور الحركات المقيّدة على حساب المكتب لا تظهر هنا لأنها لا تمس النقد.";

        // 9.0.1: البيع والشراء لكل زوج عملات: المجموع ومتوسط السعر
        Map<String, long[]> fx = new TreeMap<>();
        Map<String, Move> byPair = new HashMap<>();
        for (Move x : moves) if (x.pairUuid != null && !x.mainLeg()) byPair.put(x.pairUuid, x);
        for (Move x : moves) {
            if (!x.mainLeg() || !x.counts() || x.createdAt < sp.from || (sp.to > 0 && x.createdAt >= sp.to)) continue;
            Move other = byPair.get(x.pairUuid);
            if (other == null) continue;
            if (!sp.currencies.isEmpty() && !sp.currencies.contains(x.currency) && !sp.currencies.contains(other.currency)) continue;
            String key = (CAT_SELL.equals(x.category) ? "بيع" : "شراء") + "|" + x.currency + "|" + other.currency;
            long[] v = fx.get(key);
            if (v == null) {
                v = new long[3];
                fx.put(key, v);
            }
            v[0] += x.amount;
            v[1] += other.amount;
            v[2]++;
        }
        if (!fx.isEmpty()) {
            Report.Section f = r.section("بيع وشراء العملات", "البيع والشراء");
            f.col("العملية", 0.6f, Report.TEXT).col("العملة", 0.6f, Report.TEXT).col("الكمية", 1f, Report.MONEY).col("المقابل", 1f, Report.TEXT)
                    .col("متوسط السعر", 0.9f, Report.TEXT).col("العمليات", 0.6f, Report.INT);
            for (Map.Entry<String, long[]> e : fx.entrySet()) {
                String[] k = e.getKey().split("\\|", 3);
                long[] v = e.getValue();
                int d1 = dec(decimals, k[1]), d2 = dec(decimals, k[2]);
                BigDecimal avg = BigDecimal.valueOf(v[1], d2).divide(BigDecimal.valueOf(v[0], d1), 6, RoundingMode.HALF_EVEN).stripTrailingZeros();
                f.row(Report.NORMAL, k[0], k[1], new Report.Money(v[0], d1), Model.fmt(v[1], d2) + " " + k[2], avg.toPlainString() + " " + k[2], v[2]);
            }
            f.note = "«بيع»: خرجت العملة من الصندوق ودخل مقابلها؛ «شراء»: دخلت وخرج مقابلها. متوسط السعر = مجموع المقابل ÷ مجموع الكمية.";
        }

        // المصاريف حسب البند
        Map<String, Map<String, Long>> cats = new TreeMap<>();
        for (Summary m : sums.values())
            for (Map.Entry<String, Long> e : m.byCategory.entrySet()) {
                Map<String, Long> per = cats.get(e.getKey());
                if (per == null) {
                    per = new TreeMap<>();
                    cats.put(e.getKey(), per);
                }
                per.put(m.currency, e.getValue());
            }
        if (!cats.isEmpty()) {
            Report.Section ex = r.section("المصاريف حسب البند", "المصاريف");
            ex.col("البند", 1.6f, Report.TEXT).col("العملة", 0.6f, Report.TEXT).col("المبلغ", 1f, Report.MONEY).col("النسبة من مصاريف العملة", 1f, Report.TEXT);
            for (Map.Entry<String, Map<String, Long>> e : cats.entrySet())
                for (Map.Entry<String, Long> c : e.getValue().entrySet()) {
                    long tot = sums.get(c.getKey()).expenses;
                    ex.row(Report.NORMAL, e.getKey(), c.getKey(), new Report.Money(c.getValue(), dec(decimals, c.getKey())), pct(c.getValue(), tot));
                }
            for (Summary m : sums.values()) if (m.expenses > 0) ex.row(Report.TOTAL, "مجموع المصاريف", m.currency, new Report.Money(m.expenses, dec(decimals, m.currency)), "100%");
        }

        // الحركة مع المكاتب
        if (sp.byOffice) {
            Map<String, long[]> per = new TreeMap<>();
            for (Move x : moves) {
                if (x.officeId == null || !x.counts() || x.createdAt < sp.from || (sp.to > 0 && x.createdAt >= sp.to)) continue;
                if (!sp.currencies.isEmpty() && !sp.currencies.contains(x.currency)) continue;
                String n = offices.get(x.officeId);
                String key = (n == null ? "مكتب #" + x.officeId : n) + "|" + x.currency;
                long[] v = per.get(key);
                if (v == null) {
                    v = new long[4];
                    per.put(key, v);
                }
                if (x.dir > 0) {
                    v[0] += x.amount;
                    v[2]++;
                } else {
                    v[1] += x.amount;
                    v[3]++;
                }
            }
            if (!per.isEmpty()) {
                Report.Section o = r.section("الصندوق مع المكاتب", "مع المكاتب");
                o.col("المكتب", 1.5f, Report.TEXT).col("العملة", 0.6f, Report.TEXT).col("استُلم لحسابه", 1f, Report.MONEY).col("سُلّم لحسابه", 1f, Report.MONEY)
                        .col("الصافي على الصندوق", 1f, Report.TEXT).col("العمليات", 0.6f, Report.INT);
                for (Map.Entry<String, long[]> e : per.entrySet()) {
                    String[] k = e.getKey().split("\\|", 2);
                    long[] v = e.getValue();
                    int dc = dec(decimals, k[1]);
                    long net = v[0] - v[1];
                    o.row(Report.NORMAL, k[0], k[1], new Report.Money(v[0], dc), new Report.Money(v[1], dc),
                            net == 0 ? "—" : (net > 0 ? "داخل " : "خارج ") + Model.fmt(Math.abs(net), dc), v[2] + v[3]);
                }
                o.note = "كل حركة هنا مسجّلة أيضًا على المكتب نفسه في كشفه (حركة الصندوق مرتبطة بها). الاستلام لحساب مكتب = «علينا» للمكتب، والتسليم لحسابه = «لنا».";
            }
        }

        // اليومية
        if (sp.byDay) {
            Map<String, long[]> days = new TreeMap<>();
            for (Move x : moves) {
                if (!x.counts() || x.createdAt < sp.from || (sp.to > 0 && x.createdAt >= sp.to)) continue;
                if (!sp.currencies.isEmpty() && !sp.currencies.contains(x.currency)) continue;
                String key = Model.day(x.createdAt) + "|" + x.currency;
                long[] v = days.get(key);
                if (v == null) {
                    v = new long[3];
                    days.put(key, v);
                }
                long e = x.effect();
                if (e > 0) v[0] += e;
                else v[1] -= e;
                v[2]++;
            }
            if (!days.isEmpty()) {
                Report.Section d = r.section("حركة كل يوم", "اليومية");
                d.col("اليوم", 1f, Report.TEXT).col("العملة", 0.6f, Report.TEXT).col("داخل", 1f, Report.MONEY).col("خارج", 1f, Report.MONEY)
                        .col("الصافي", 1f, Report.TEXT).col("العمليات", 0.6f, Report.INT);
                for (Map.Entry<String, long[]> e : days.entrySet()) {
                    String[] k = e.getKey().split("\\|", 2);
                    long[] v = e.getValue();
                    int dc = dec(decimals, k[1]);
                    long net = v[0] - v[1];
                    d.row(Report.NORMAL, k[0], k[1], new Report.Money(v[0], dc), new Report.Money(v[1], dc), (net >= 0 ? "+" : "−") + Model.fmt(Math.abs(net), dc), v[2]);
                }
            }
        }

        // الكشف التفصيلي برصيد متراكم لكل عملة
        if (sp.details) {
            Set<String> curs = new TreeSet<>();
            for (Move x : moves) if (sp.currencies.isEmpty() || sp.currencies.contains(x.currency)) curs.add(x.currency);
            for (String c : curs) {
                int dc = dec(decimals, c);
                long bal = 0;
                for (Move x : moves) if (c.equals(x.currency) && x.createdAt < sp.from) bal += x.effect();
                Report.Section t = r.section("كشف الصندوق — " + c, "كشف " + c);
                t.col("التاريخ", 0.9f, Report.DATE).col("الحركة", 1.6f, Report.TEXT).col("الشخص / البيان", 1.6f, Report.TEXT).col("داخل", 0.9f, Report.MONEY)
                        .col("خارج", 0.9f, Report.MONEY).col("أجور نقدًا", 0.8f, Report.TEXT).col("الرصيد", 1f, Report.MONEY).col("الحالة", 0.7f, Report.TEXT);
                t.row(Report.SHADED, new Report.DateV(sp.from > 0 ? sp.from : (moves.isEmpty() ? 0 : moves.get(0).createdAt)), "الرصيد قبل الفترة", "", "", "", "",
                        new Report.Money(bal, dc), "");
                int n = 0;
                for (Move x : moves) {
                    if (!c.equals(x.currency) || x.createdAt < sp.from || (sp.to > 0 && x.createdAt >= sp.to)) continue;
                    bal += x.effect();
                    long fc = x.feeCash();
                    String who = Model.nz(x.party);
                    if (!Model.nz(x.description).isEmpty()) who = who.isEmpty() ? x.description : who + " — " + x.description;
                    t.row(x.counts() ? Report.NORMAL : Report.MUTED, new Report.DateV(x.createdAt), moveText(x, offices), who,
                            x.dir > 0 ? new Report.Money(x.amount, dc) : "", x.dir < 0 ? new Report.Money(x.amount, dc) : "",
                            fc == 0 ? "" : (fc > 0 ? "لنا " : "علينا ") + Model.fmt(Math.abs(fc), dc), new Report.Money(bal, dc), x.stateText());
                    n++;
                }
                t.row(Report.TOTAL, new Report.DateV(sp.to > 0 ? sp.to - 1 : Clock.now()), "الرصيد آخر الفترة", n + " حركة", "", "", "", new Report.Money(bal, dc), "");
            }
        }
        return r;
    }

    static String pct(long part, long total) {
        if (total <= 0) return "—";
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + "%";
    }

    /** بداية اليوم المحلي. */
    public static long startOfDay(long t) {
        Calendar c = Model.cal();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** نص رصيد الصندوق لعملة: «12,500.00 USD» (الصندوق لا يكون «علينا»: السالب عجز ظاهر). */
    public static String balText(long v, int dec, String code) {
        return (v < 0 ? "عجز " : "") + Model.fmt(Math.abs(v), dec) + " " + code;
    }

    /** كل الحركات التي تخص يومًا (للجرد اليومي ولشاشة اليوم). */
    public static List<Move> ofDay(List<Move> moves, long dayStart) {
        List<Move> out = new ArrayList<>();
        String d = Model.day(dayStart);
        for (Move x : moves) if (Model.day(x.createdAt).equals(d)) out.add(x);
        return out;
    }

    /** لكل عملة: الداخل والخارج اليوم (للبطاقات). */
    public static Map<String, long[]> todayFlow(List<Move> moves, long now) {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (Move x : ofDay(moves, startOfDay(now))) {
            long e = x.effect();
            if (e == 0) continue;
            long[] v = m.get(x.currency);
            if (v == null) {
                v = new long[2];
                m.put(x.currency, v);
            }
            if (e > 0) v[0] += e;
            else v[1] -= e;
        }
        return m;
    }

    /**
     * إن كانت الحركة ستجعل رصيد عملة في الصندوق سالبًا (عجزًا) يرجع نص التنبيه، وإلا null.
     * (النقد لا يكون سالبًا في الواقع: السالب غالبًا خطأ في المبلغ أو العملة أو حركة داخلة لم تُسجَّل.)
     */
    public static String negativeAfter(Input in, Map<String, Long> balances, Map<String, Integer> decimals) {
        Object[] o = in.outgoing();
        if (o == null || o[0] == null) return null;
        String cur = (String) o[0];
        long out = (Long) o[1];
        Long b = balances.get(cur);
        long after = (b == null ? 0 : b) - out;
        if (after >= 0) return null;
        int d = dec(decimals, cur);
        return "في الصندوق الآن " + Model.fmt(b == null ? 0 : b, d) + " " + cur + "، وبعد هذه الحركة يصبح عجزًا بـ " + Model.fmt(-after, d) + " " + cur
                + ". تأكد من المبلغ والعملة، أو سجّل الداخل أولًا.";
    }

    // ===================================================================================
    // 9.0.1: الجرد: سجل لكل جرد، ومقارنة زيادة عملة بعجز أخرى (بيع أو شراء لم يُسجَّل؟)
    // ===================================================================================

    /** سطر جرد: المسجّل والمعدود والفرق (موجب = زيادة، سالب = عجز). */
    public static final class CountLine {
        public String currency;
        /** recorded: المتوقع في الصندوق (الرصيد + المعلّق)، و10.5: pending منه لحركات بانتظار الاعتماد. */
        public long recorded, counted, pending;

        public CountLine(String c, long recorded, long counted) {
            this.currency = c;
            this.recorded = recorded;
            this.counted = counted;
        }

        public CountLine(String c, long recorded, long counted, long pending) {
            this(c, recorded, counted);
            this.pending = pending;
        }

        public long diff() { return counted - recorded; }
    }

    /** اقتراح: زيادة في عملة وعجز في أخرى قيمتاهما متقاربتان بالدولار = غالبًا بيع لم يُسجَّل. */
    public static final class Suggestion {
        public String soldCur, gotCur;
        public long sold, got;
        public BigDecimal soldUsd, gotUsd, rate;

        public BigDecimal gapUsd() { return gotUsd.subtract(soldUsd); }
    }

    /** قيمة مبلغ بالدولار أو null بلا سعر. */
    public static BigDecimal usd(long minor, String cur, Map<String, BigDecimal> rates, Map<String, Integer> decimals) {
        BigDecimal r = "USD".equals(cur) ? BigDecimal.ONE : rates.get(cur);
        if (r == null || r.signum() <= 0) return null;
        return BigDecimal.valueOf(minor, dec(decimals, cur)).multiply(r).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * يقارن كل عملة فيها زيادة بكل عملة فيها عجز: إن كانت قيمتاهما بالدولار متقاربتين (الفرق ≤ 3٪ أو دولار واحد)
     * فالأرجح أنها عملية بيع (خرجت عملة العجز ودخلت عملة الزيادة) لم تُسجَّل. الأقرب أولًا، وكل عملة في اقتراح واحد.
     */
    public static List<Suggestion> analyze(List<CountLine> lines, Map<String, BigDecimal> rates, Map<String, Integer> decimals) {
        List<Suggestion> all = new ArrayList<>();
        for (CountLine up : lines) {
            if (up.diff() <= 0) continue;
            for (CountLine down : lines) {
                if (down.diff() >= 0 || down == up) continue;
                BigDecimal gu = usd(up.diff(), up.currency, rates, decimals), su = usd(-down.diff(), down.currency, rates, decimals);
                if (gu == null || su == null || gu.signum() == 0 || su.signum() == 0) continue;
                BigDecimal gap = gu.subtract(su).abs(), tol = gu.max(su).multiply(new BigDecimal("0.03")).max(BigDecimal.ONE);
                if (gap.compareTo(tol) > 0) continue;
                Suggestion s = new Suggestion();
                s.soldCur = down.currency;
                s.sold = -down.diff();
                s.gotCur = up.currency;
                s.got = up.diff();
                s.soldUsd = su;
                s.gotUsd = gu;
                s.rate = BigDecimal.valueOf(s.got, dec(decimals, s.gotCur)).divide(BigDecimal.valueOf(s.sold, dec(decimals, s.soldCur)), 6, RoundingMode.HALF_EVEN).stripTrailingZeros();
                all.add(s);
            }
        }
        Collections.sort(all, (x, y) -> x.gapUsd().abs().compareTo(y.gapUsd().abs()));
        List<Suggestion> out = new ArrayList<>();
        Set<String> used = new java.util.HashSet<>();
        for (Suggestion s : all) {
            if (used.contains(s.soldCur) || used.contains(s.gotCur)) continue;
            used.add(s.soldCur);
            used.add(s.gotCur);
            out.add(s);
        }
        return out;
    }

    /** صافي فروق الجرد بالدولار (زيادة − عجز) والعملات بلا سعر. */
    public static BigDecimal netUsd(List<CountLine> lines, Map<String, BigDecimal> rates, Map<String, Integer> decimals, List<String> missing) {
        BigDecimal n = BigDecimal.ZERO;
        for (CountLine l : lines) {
            if (l.diff() == 0) continue;
            BigDecimal v = usd(Math.abs(l.diff()), l.currency, rates, decimals);
            if (v == null) {
                if (missing != null) missing.add(l.currency);
                continue;
            }
            n = l.diff() > 0 ? n.add(v) : n.subtract(v);
        }
        return n;
    }

    /** سجل جرد كما يُحفظ. */
    public static final class Count {
        public long id, boxId, createdAt, adjusted;
        public String userName = "", note = "", netUsd = "", sales = "";
        public final List<CountLine> lines = new ArrayList<>();

        public long shortages() {
            long n = 0;
            for (CountLine l : lines) if (l.diff() < 0) n++;
            return n;
        }

        public long surpluses() {
            long n = 0;
            for (CountLine l : lines) if (l.diff() > 0) n++;
            return n;
        }
    }

    public static String linesJson(List<CountLine> lines) {
        org.json.JSONArray a = new org.json.JSONArray();
        for (CountLine l : lines) {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("c", l.currency).put("r", l.recorded).put("n", l.counted);
                if (l.pending != 0) o.put("p", l.pending);
            } catch (Exception ignored) {
                // لا يحدث
            }
            a.put(o);
        }
        return a.toString();
    }

    public static List<CountLine> linesFrom(String json) {
        List<CountLine> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                out.add(new CountLine(o.getString("c"), o.getLong("r"), o.getLong("n"), o.optLong("p", 0)));
            }
        } catch (Exception ignored) {
            // سجل تالف: فارغ
        }
        return out;
    }

    /** ما يُنقل للتقرير: أسماء المكاتب الحالية. */
    public static Map<Long, String> names(List<Model.Office> offices) {
        Map<Long, String> m = new HashMap<>();
        for (Model.Office o : offices) m.put(o.id, o.name);
        return m;
    }
}
