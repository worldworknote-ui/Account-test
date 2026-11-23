package com.smart.hawala;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 9.0.1: «حسابات العملاء» داخل الصندوق — منطق بحت مشترك.
 *
 * <p>العميل شخص يتعامل مع الصندوق مباشرة وله حساب لدينا مستقل تمامًا عن حسابات المكاتب ودفتر قيودها.
 * لكل عميل رصيد بكل عملة = مجموع (اتجاه × مبلغ) لحركاته النشطة: الموجب «لنا» (العميل مدين لنا)، والسالب «علينا» (له عندنا).
 *
 * <ul>
 *   <li>استلام نقد منه (يدخل الصندوق) = علينا له (−).</li>
 *   <li>تسليم نقد له (يخرج من الصندوق) = لنا عليه (+).</li>
 *   <li>قد يُقيَّد على حسابه بعملة غير عملة النقد (له 100 يورو فيأخذ دولارًا): النقد بالدولار، وحركة حسابه باليورو، والسعر محفوظ.</li>
 *   <li>قيد بلا نقد (رصيد سابق أو تسوية متفق عليها) بجهة يختارها المستخدم.</li>
 *   <li>تبديل عملة الرصيد بلا نقد: سطران مرتبطان (يُطفأ جزء من رصيد عملة ويُنشأ مقابله بعملة أخرى بالجهة نفسها).</li>
 * </ul>
 */
public final class ClientBook {
    private ClientBook() {}

    public static final String CASH = "CASH", ENTRY = "ENTRY", SWAP = "SWAP";

    public static final class Client {
        public long id;
        public String name = "", phone = "", note = "";
        public boolean active = true;
        public long createdAt;
    }

    /** حركة على حساب عميل. cashUuid: حركة الصندوق المرتبطة (لنوع CASH)، وcashCurrency/cashAmount للعرض فقط. */
    public static final class Move {
        public long id, clientId, createdAt;
        public String uuid = "", kind = ENTRY, currency = "";
        public long amount;
        /** +1 لنا عليه، −1 علينا له. */
        public int dir = 1;
        public String cashUuid, pairUuid, rate;
        public String description = "", userName = "", cancelReason = "";
        public String status = CashBox.ACTIVE;
        public String cashCurrency;
        public long cashAmount;
        public int cashDir;

        public boolean counts() { return CashBox.ACTIVE.equals(status); }

        public long effect() { return counts() ? dir * amount : 0; }

        public boolean cancelled() { return CashBox.CANCELLED.equals(status); }
    }

    /** الرصيد لكل عملة (الموجب لنا والسالب علينا)، بلا الأصفار. */
    public static Map<String, Long> balances(List<Move> moves) {
        Map<String, Long> m = new TreeMap<>();
        for (Move x : moves) {
            long e = x.effect();
            if (e == 0) continue;
            Long b = m.get(x.currency);
            m.put(x.currency, (b == null ? 0 : b) + e);
        }
        m.values().removeIf(v -> v == 0);
        return m;
    }

    /** أرصدة كل العملاء: العميل ← (العملة ← الرصيد). */
    public static Map<Long, Map<String, Long>> allBalances(List<Move> moves) {
        Map<Long, List<Move>> by = new TreeMap<>();
        for (Move x : moves) {
            List<Move> l = by.get(x.clientId);
            if (l == null) {
                l = new ArrayList<>();
                by.put(x.clientId, l);
            }
            l.add(x);
        }
        Map<Long, Map<String, Long>> out = new TreeMap<>();
        for (Map.Entry<Long, List<Move>> e : by.entrySet()) out.put(e.getKey(), balances(e.getValue()));
        return out;
    }

    /** مجموع «لنا» و«علينا» لكل عملة عبر العملاء: [لنا، علينا، عدد عملاء لنا، عدد عملاء علينا]. */
    public static Map<String, long[]> totals(Map<Long, Map<String, Long>> all) {
        Map<String, long[]> t = new TreeMap<>();
        for (Map<String, Long> b : all.values())
            for (Map.Entry<String, Long> e : b.entrySet()) {
                long[] v = t.get(e.getKey());
                if (v == null) {
                    v = new long[4];
                    t.put(e.getKey(), v);
                }
                if (e.getValue() > 0) {
                    v[0] += e.getValue();
                    v[2]++;
                } else if (e.getValue() < 0) {
                    v[1] -= e.getValue();
                    v[3]++;
                }
            }
        return t;
    }

    /** «150.00 EUR لنا» / «علينا» / «صفر». */
    public static String side(long v) { return v > 0 ? "لنا" : v < 0 ? "علينا" : "صفر"; }

    /** وصف الحركة كما يظهر في الكشف. */
    public static String text(Move x, Map<String, Integer> decimals) { return text(x, decimals, false); }

    /** iso: عزل الأرقام باتجاهها (للشاشة). */
    public static String text(Move x, Map<String, Integer> decimals, boolean iso) {
        String l = iso ? "\u2066" : "", r = iso ? "\u2069" : "";
        switch (x.kind) {
            case CASH: {
                boolean got = x.cashDir > 0;
                String t = got ? "استلمنا منه نقدًا" : "سلّمناه نقدًا";
                if (x.cashCurrency != null && !x.cashCurrency.equals(x.currency))
                    t += " " + l + Model.fmt(x.cashAmount, CashBox.dec(decimals, x.cashCurrency)) + " " + x.cashCurrency + r
                            + (Model.nz(x.rate).isEmpty() ? "" : iso ? "\nبسعر " + l + "1 " + x.currency + " = " + x.rate + " " + x.cashCurrency + r
                            : " (1 " + x.currency + " = " + x.rate + " " + x.cashCurrency + ")");
                return t;
            }
            case SWAP: return "تبديل عملة الرصيد";
            default: return x.dir > 0 ? "قيد لنا عليه" : "قيد علينا له";
        }
    }

    /** ترتيب زمني ثابت. */
    public static void sort(List<Move> moves) {
        Collections.sort(moves, (a, b) -> {
            int c = Long.compare(a.createdAt, b.createdAt);
            return c != 0 ? c : Long.compare(a.id, b.id);
        });
    }

    /**
     * التحقق من إدخال الحركة على الحساب (null = صالح).
     * clientAmount بعملة الحساب؛ إن اختلفت عن عملة النقد يجب السعر أو المبلغ المقابل.
     */
    public static String validateLink(String cashCurrency, long cashAmount, String clientCurrency, long clientAmount) {
        if (clientCurrency == null || clientCurrency.isEmpty()) return "اختر عملة حساب العميل";
        if (clientAmount <= 0) return "اكتب المبلغ الذي يُقيَّد على حساب العميل";
        if (clientCurrency.equals(cashCurrency) && clientAmount != cashAmount) return "بالعملة نفسها يُقيَّد على العميل المبلغ نفسه";
        return null;
    }

    /** التبديل: يُسمح بتبديل ما لا يزيد عن رصيد العملة، وبالجهة نفسها. يرجع null أو السبب. */
    public static String validateSwap(Map<String, Long> bal, String from, long amount, String to, long toAmount) {
        if (from == null || to == null) return "اختر العملتين";
        if (from.equals(to)) return "التبديل بين عملتين مختلفتين";
        Long b = bal.get(from);
        if (b == null || b == 0) return "لا رصيد للعميل بـ" + from + " لتبديله";
        if (amount <= 0 || toAmount <= 0) return "اكتب المبلغ والسعر";
        if (amount > Math.abs(b)) return "المبلغ أكبر من رصيد العميل بـ" + from;
        return null;
    }

    /** نص سعر: كم من cur2 لكل 1 من cur1 (6 منازل كحد أقصى). */
    public static String rateOf(long a1, int d1, long a2, int d2) {
        if (a1 <= 0 || a2 <= 0) return "";
        return BigDecimal.valueOf(a2, d2).divide(BigDecimal.valueOf(a1, d1), 6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
    }

    // ===================================================================================
    // الكشف (PDF / Excel / الشاشة)
    // ===================================================================================

    public static Report statement(Client c, List<Move> all, long from, long to, Map<String, Integer> decimals, String issuer) {
        List<Move> moves = new ArrayList<>(all);
        sort(moves);
        Report r = new Report();
        r.title = "كشف حساب العميل " + c.name;
        r.fileBase = "كشف-عميل";
        if (issuer != null && !issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + issuer.trim());
        if (!Model.nz(c.phone).isEmpty()) r.headerLines.add("الهاتف: " + c.phone);
        r.headerLines.add("الفترة: " + (from <= 0 ? "من البداية" : "من " + Model.day(from)) + " " + (to <= 0 ? "حتى الآن" : "إلى " + Model.day(to - 1)));
        Report.Section s = r.section("الرصيد", "الرصيد");
        s.col("العملة", 0.6f, Report.TEXT).col("أول الفترة", 1f, Report.TEXT).col("لنا عليه (+)", 1f, Report.MONEY).col("علينا له (−)", 1f, Report.MONEY)
                .col("آخر الفترة", 1f, Report.TEXT);
        Set<String> curs = new TreeSet<>();
        for (Move x : moves) curs.add(x.currency);
        for (String cur : curs) {
            int d = CashBox.dec(decimals, cur);
            long open = 0, plus = 0, minus = 0;
            for (Move x : moves) {
                if (!cur.equals(x.currency) || !x.counts()) continue;
                if (x.createdAt < from) open += x.effect();
                else if (to <= 0 || x.createdAt < to) {
                    if (x.dir > 0) plus += x.amount;
                    else minus += x.amount;
                }
            }
            long close = open + plus - minus;
            s.row(Report.TOTAL, cur, Model.fmt(Math.abs(open), d) + " " + side(open), new Report.Money(plus, d), new Report.Money(minus, d),
                    Model.fmt(Math.abs(close), d) + " " + side(close));
            s.kv("بـ" + cur, Model.fmt(Math.abs(close), d) + " " + cur + " " + side(close), close != 0);
        }
        if (curs.isEmpty()) s.kv("لا حركات", "لا توجد حركات على هذا الحساب", false);
        s.note = "«لنا»: العميل مدين لنا. «علينا»: له عندنا. حسابات العملاء مستقلة عن حسابات المكاتب.";
        for (String cur : curs) {
            int d = CashBox.dec(decimals, cur);
            long bal = 0;
            for (Move x : moves) if (cur.equals(x.currency) && x.createdAt < from) bal += x.effect();
            Report.Section t = r.section("كشف " + cur, "كشف " + cur);
            t.col("التاريخ", 0.9f, Report.DATE).col("الحركة", 1.8f, Report.TEXT).col("البيان", 1.4f, Report.TEXT).col("لنا (+)", 0.9f, Report.MONEY)
                    .col("علينا (−)", 0.9f, Report.MONEY).col("الرصيد", 1.1f, Report.TEXT).col("الحالة", 0.6f, Report.TEXT);
            t.row(Report.SHADED, new Report.DateV(from > 0 ? from : (moves.isEmpty() ? 0 : moves.get(0).createdAt)), "الرصيد قبل الفترة", "", "", "",
                    Model.fmt(Math.abs(bal), d) + " " + side(bal), "");
            int n = 0;
            for (Move x : moves) {
                if (!cur.equals(x.currency) || x.createdAt < from || (to > 0 && x.createdAt >= to)) continue;
                bal += x.effect();
                t.row(x.counts() ? Report.NORMAL : Report.MUTED, new Report.DateV(x.createdAt), text(x, decimals), Model.nz(x.description),
                        x.dir > 0 ? new Report.Money(x.amount, d) : "", x.dir < 0 ? new Report.Money(x.amount, d) : "",
                        Model.fmt(Math.abs(bal), d) + " " + side(bal), x.cancelled() ? "ملغاة" : "");
                n++;
            }
            t.row(Report.TOTAL, new Report.DateV(to > 0 ? to - 1 : Clock.now()), "الرصيد آخر الفترة", n + " حركة", "", "",
                    Model.fmt(Math.abs(bal), d) + " " + side(bal), "");
        }
        return r;
    }
}
