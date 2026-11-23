package com.smart.hawala;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * حسابات الإجماليات والتقويم بالدولار. منطق حسابي بحت (بدون أندرويد) حتى يُختبر على الكمبيوتر.
 *
 * <p>القاعدة الأساسية: لا تقاصّ بين المكاتب. إذا كان لنا على المكتب (أ) 1000 وعلينا للمكتب (ب) 700،
 * فالنتيجة «مجموع لنا 1000» و«مجموع علينا 700»، والصافي 300 يُعرض منفصلًا وبوضوح.
 */
public final class Ledger {
    private Ledger() {}

    /** إجمالي عملة واحدة عبر كل المكاتب. القيم بالوحدة الصغرى (سنت، قرش...). */
    public static final class CurrencyTotal {
        public final String code;
        public long receivable; // مجموع «لنا»
        public long payable;    // مجموع «علينا» (قيمة موجبة)
        public int officesReceivable;
        public int officesPayable;

        CurrencyTotal(String code) { this.code = code; }

        public long net() { return receivable - payable; }
    }

    /**
     * @param officeBalances لكل مكتب: العملة ← الرصيد (موجب = لنا، سالب = علينا)
     */
    public static Map<String, CurrencyTotal> totals(Map<Long, Map<String, Long>> officeBalances) {
        Map<String, CurrencyTotal> out = new TreeMap<>();
        for (Map<String, Long> perOffice : officeBalances.values()) {
            for (Map.Entry<String, Long> e : perOffice.entrySet()) {
                long v = e.getValue() == null ? 0 : e.getValue();
                CurrencyTotal t = out.get(e.getKey());
                if (t == null) {
                    t = new CurrencyTotal(e.getKey());
                    out.put(e.getKey(), t);
                }
                if (v > 0) {
                    t.receivable = Math.addExact(t.receivable, v);
                    t.officesReceivable++;
                } else if (v < 0) {
                    t.payable = Math.addExact(t.payable, -v);
                    t.officesPayable++;
                }
            }
        }
        return out;
    }

    /** نتيجة تقويم بالدولار: «لنا» و«علينا» منفصلان، مع العملات التي لا يوجد لها سعر. */
    public static final class Valuation {
        public BigDecimal receivableUsd = BigDecimal.ZERO;
        public BigDecimal payableUsd = BigDecimal.ZERO;
        public final Set<String> missingRates = new LinkedHashSet<>();
        /** 12.5: معادن «بالوزن فقط» (اختيار المحاسب): خارج التقويم بالدولار، وليست «بلا سعر». */
        public final Set<String> weightOnly = new LinkedHashSet<>();

        public BigDecimal netUsd() { return receivableUsd.subtract(payableUsd); }
    }

    /**
     * يقوّم مبلغًا (بالوحدة الصغرى) بالدولار.
     * @param usdPerUnit قيمة وحدة واحدة من العملة بالدولار
     */
    public static BigDecimal toUsd(long minor, int decimals, BigDecimal usdPerUnit) {
        return BigDecimal.valueOf(minor, decimals).multiply(usdPerUnit);
    }

    /** تقويم إجماليات كل العملات. */
    public static Valuation value(Map<String, CurrencyTotal> totals, Map<String, BigDecimal> usdRates, Map<String, Integer> decimals) {
        Valuation v = new Valuation();
        for (CurrencyTotal t : totals.values()) {
            if (t.receivable == 0 && t.payable == 0) continue;
            if (Metals.notValued(t.code)) {
                v.weightOnly.add(t.code);
                continue;
            }
            BigDecimal rate = rateFor(t.code, usdRates);
            if (rate == null) {
                v.missingRates.add(t.code);
                continue;
            }
            int dec = decimalsFor(t.code, decimals);
            v.receivableUsd = v.receivableUsd.add(toUsd(t.receivable, dec, rate));
            v.payableUsd = v.payableUsd.add(toUsd(t.payable, dec, rate));
        }
        v.receivableUsd = v.receivableUsd.setScale(2, RoundingMode.HALF_EVEN);
        v.payableUsd = v.payableUsd.setScale(2, RoundingMode.HALF_EVEN);
        return v;
    }

    /** تقويم أرصدة مكتب واحد: العملات الموجبة في «لنا» والسالبة في «علينا» دون تقاصّ بينها. */
    public static Valuation valueOffice(Map<String, Long> balances, Map<String, BigDecimal> usdRates, Map<String, Integer> decimals) {
        Valuation v = new Valuation();
        for (Map.Entry<String, Long> e : balances.entrySet()) {
            long b = e.getValue() == null ? 0 : e.getValue();
            if (b == 0) continue;
            if (Metals.notValued(e.getKey())) {
                v.weightOnly.add(e.getKey());
                continue;
            }
            BigDecimal rate = rateFor(e.getKey(), usdRates);
            if (rate == null) {
                v.missingRates.add(e.getKey());
                continue;
            }
            BigDecimal usd = toUsd(Math.abs(b), decimalsFor(e.getKey(), decimals), rate);
            if (b > 0) v.receivableUsd = v.receivableUsd.add(usd);
            else v.payableUsd = v.payableUsd.add(usd);
        }
        v.receivableUsd = v.receivableUsd.setScale(2, RoundingMode.HALF_EVEN);
        v.payableUsd = v.payableUsd.setScale(2, RoundingMode.HALF_EVEN);
        return v;
    }

    private static BigDecimal rateFor(String code, Map<String, BigDecimal> rates) {
        if ("USD".equals(code)) return BigDecimal.ONE;
        BigDecimal r = rates.get(code);
        return (r == null || r.signum() <= 0) ? null : r;
    }

    private static int decimalsFor(String code, Map<String, Integer> decimals) {
        Integer d = decimals.get(code);
        return d == null ? 2 : d;
    }

    /** تحذير حد: النوع («لنا»/«علينا») والقيمة والحد. */
    public static final class LimitBreach {
        public final String currency; // "USD≈" للتقويم الإجمالي
        public final boolean receivable;
        public final long amount;
        public final long limit;

        public LimitBreach(String currency, boolean receivable, long amount, long limit) {
            this.currency = currency;
            this.receivable = receivable;
            this.amount = amount;
            this.limit = limit;
        }
    }

    /** يفحص رصيد عملة مقابل حد «لنا» وحد «علينا» (null = بلا حد). */
    public static LimitBreach check(String currency, long balance, Long limitReceivable, Long limitPayable) {
        if (balance > 0 && limitReceivable != null && limitReceivable > 0 && balance > limitReceivable) {
            return new LimitBreach(currency, true, balance, limitReceivable);
        }
        if (balance < 0 && limitPayable != null && limitPayable > 0 && -balance > limitPayable) {
            return new LimitBreach(currency, false, -balance, limitPayable);
        }
        return null;
    }
}
