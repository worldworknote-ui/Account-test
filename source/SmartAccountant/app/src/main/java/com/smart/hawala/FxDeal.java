package com.smart.hawala;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 10.5 (§8): حساب تبديل العملة مع المكتب — منطق بحت مشترك بين الهاتف والكمبيوتر.
 * 10.8: «قص العملات» مقاصة أرصدة لا بيع نقدي: العملة المباعة تُخصم من رصيدنا لدى المكتب، والمشتراة تُضاف إليه؛ يُسمح بعبور الصفر
 * (فينقلب «لنا» إلى «علينا») مع تنبيه غير مانع، ولا حركة صندوق ولا بنك.
 * <ul>
 *   <li>«نبيع» عملة للمكتب (تصير «لنا» عليه) و«نشتري» منه أخرى (تصير «علينا» له).</li>
 *   <li>السعر يُكتب بأي اتجاه: «1 بيع = ؟ شراء» أو «1 شراء = ؟ بيع». الرقم المكتوب يُحفظ كما هو؛ عكسه يُعرض للشرح فقط
 *       (1 ÷ 1.12 = 0.892857…، وكتابة 0.9 سعر جديد مختلف وليس تقريبًا له).</li>
 *   <li>قراءة السعر بـ {@link CashBox#rateDecimal}: النقطة عشرية دائمًا ولا تُعامل فاصل آلاف.</li>
 * </ul>
 */
public final class FxDeal {
    /** عملة البيع («لنا») وعملة الشراء («علينا»). */
    public String sell = "", buy = "";
    public int sellDec = 2, buyDec = 2;
    /** مبلغ البيع بالوحدة الصغرى. */
    public long sellAmount;
    /** السعر كما كتبه المستخدم. */
    public BigDecimal rate;
    /** true: «1 بيع = rate شراء»؛ false: «1 شراء = rate بيع». */
    public boolean buyPerSell = true;

    /** يقرأ السعر (null إن لم يكن رقمًا موجبًا). */
    public static BigDecimal parseRate(String text) {
        try {
            BigDecimal r = CashBox.rateDecimal(text);
            return r != null && r.signum() > 0 ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** كم من عملة الشراء لكل 1 من عملة البيع (بدقة كافية للحساب). */
    public BigDecimal buyPerSellRate() {
        if (rate == null || rate.signum() <= 0) return null;
        return buyPerSell ? rate : BigDecimal.ONE.divide(rate, 12, RoundingMode.HALF_UP);
    }

    /** مبلغ الشراء المقابل بالوحدة الصغرى (تقريب نصف للأعلى إلى منازل عملة الشراء)، أو null. */
    public Long buyAmount() {
        if (rate == null || rate.signum() <= 0 || sellAmount <= 0) return null;
        BigDecimal s = BigDecimal.valueOf(sellAmount, sellDec);
        BigDecimal b = buyPerSell ? s.multiply(rate) : s.divide(rate, buyDec + 8, RoundingMode.HALF_UP);
        return b.setScale(buyDec, RoundingMode.HALF_UP).movePointRight(buyDec).longValueExact();
    }

    /**
     * 10.13: الحساب من الطرف الآخر: المبلغ المباع من المبلغ المشترى والسعر (تقريب نصف للأعلى إلى منازل عملة البيع)، أو null.
     * المحاسب يكتب ما سيأخذه والسعر، فيُحسب ما يبيعه.
     */
    public Long sellAmountFor(long buyMinor) {
        if (rate == null || rate.signum() <= 0 || buyMinor <= 0) return null;
        BigDecimal b = BigDecimal.valueOf(buyMinor, buyDec);
        BigDecimal s = buyPerSell ? b.divide(rate, sellDec + 8, RoundingMode.HALF_UP) : b.multiply(rate);
        long v = s.setScale(sellDec, RoundingMode.HALF_UP).movePointRight(sellDec).longValueExact();
        return v > 0 ? v : null;
    }

    /** عكس السعر للعرض: 1 ÷ r بست منازل (1.12 ← 0.892857). */
    public static String invert(BigDecimal r) {
        if (r == null || r.signum() <= 0) return "";
        return BigDecimal.ONE.divide(r, 6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** معنى السعر بالكلمات، باتجاهيه. */
    public String meaning() {
        if (rate == null || rate.signum() <= 0 || sell.isEmpty() || buy.isEmpty()) return "";
        String r = rate.stripTrailingZeros().toPlainString();
        if (buyPerSell) return "1 " + sell + " = " + r + " " + buy + "   (أي 1 " + buy + " ≈ " + invert(rate) + " " + sell + ")";
        return "1 " + buy + " = " + r + " " + sell + "   (أي 1 " + sell + " ≈ " + invert(rate) + " " + buy + ")";
    }

    /** يقلب العملية: ما كان شراءً يصير بيعًا. المبلغ الجديد = مبلغ الشراء الحالي، والسعر المكتوب نفسه بمعناه نفسه. */
    public FxDeal reversed() {
        FxDeal x = new FxDeal();
        x.sell = buy;
        x.buy = sell;
        x.sellDec = buyDec;
        x.buyDec = sellDec;
        Long b = buyAmount();
        x.sellAmount = b == null ? 0 : b;
        x.rate = rate;
        // «1 USD = 0.92 EUR» يبقى صحيحًا بعد القلب، لكن اتجاهه بالنسبة للبيع والشراء ينعكس
        x.buyPerSell = !buyPerSell;
        return x;
    }

    /** السعر الفعلي من المبلغين بعد التقريب (يُحفظ مع العملية: كم من عملة «علينا» لكل 1 من عملة «لنا»). */
    public static String effectiveRate(long sellMinor, int sellDec, long buyMinor, int buyDec) {
        return BigDecimal.valueOf(buyMinor, buyDec).divide(BigDecimal.valueOf(sellMinor, sellDec), 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ===================================================================================
    // 10.8: المقاصة — الرصيد بعد العملية، وتنبيه تجاوز الرصيد، وسعر البرنامج للاستئناس
    // ===================================================================================

    /** الرصيد الموقّع بالكلمات: موجب «لنا»، سالب «علينا»، صفر «متوازن». */
    public static String side(long v, int dec) {
        if (v > 0) return "لنا " + Model.fmt(v, dec);
        if (v < 0) return "علينا " + Model.fmt(-v, dec);
        return "متوازن";
    }

    /** سطر جدول المعاينة: العملة | الرصيد قبل | أثر العملية (خصم/إضافة) | الرصيد بعد. */
    public static String[] row(String cur, long before, long delta, int dec) {
        return new String[]{cur, side(before, dec), (delta < 0 ? "خصم " : "إضافة ") + Model.fmt(Math.abs(delta), dec), side(before + delta, dec)};
    }

    /**
     * تنبيه غير مانع حين يتجاوز المبلغ المباع الرصيد المتاح «لنا» في العملة المباعة (أو كان الرصيد صفرًا أو «علينا» أصلًا).
     * null = لا تجاوز.
     */
    public static String overdraft(String cur, long balance, long sold, int dec) {
        if (sold <= 0 || sold <= balance) return null;
        long after = balance - sold;
        String a = Model.fmt(sold, dec) + " " + cur;
        if (balance > 0)
            return "تنبيه: المبلغ المراد بيعه " + a + " يتجاوز رصيدك المتاح البالغ " + Model.fmt(balance, dec) + " " + cur + " بمقدار " + Model.fmt(sold - balance, dec) + " " + cur
                    + ". إذا أكملت العملية، سيتحول رصيد " + cur + " من «لنا " + Model.fmt(balance, dec) + " " + cur + "» إلى «علينا " + Model.fmt(-after, dec) + " " + cur + "».";
        if (balance == 0)
            return "تنبيه: لا رصيد لنا في " + cur + " لدى هذا المكتب (متوازن). بيع " + a + " يجعل الرصيد «علينا " + Model.fmt(-after, dec) + " " + cur + "».";
        return "تنبيه: رصيد " + cur + " أصلًا «علينا " + Model.fmt(-balance, dec) + " " + cur + "». بيع " + a + " يزيد ما علينا بمقدار " + a
                + " فيصير «علينا " + Model.fmt(-after, dec) + " " + cur + "».";
    }

    /** توضيح ثابت يظهر في الشاشة والتأكيد. */
    public static final String NETTING_NOTE = "هذه العملية تحوّل الأرصدة بين العملات في حساب المكتب، ولا تسجل حركة نقدية في الصندوق أو البنك.";

    /** وصف افتراضي للساقين إن لم يُكتب بيان. */
    public static String describe(String sell, String sellText, String buy, String buyText) {
        return "قص عملات: بيع " + sellText + " " + sell + " مقابل " + buyText + " " + buy;
    }

    /**
     * سعر البرنامج (أسعار الصرف المحفوظة: يدوي أو أونلاين) بين عملتين: كم من عملة الشراء لكل 1 من عملة البيع، أو null إن نقص سعر.
     * usd: قيمة 1 من كل عملة بالدولار.
     */
    public static BigDecimal reference(java.util.Map<String, BigDecimal> usd, String sell, String buy) {
        if (usd == null || sell == null || buy == null || sell.isEmpty() || buy.isEmpty() || sell.equals(buy)) return null;
        BigDecimal s = usd.get(sell), b = usd.get(buy);
        if (s == null || b == null || s.signum() <= 0 || b.signum() <= 0) return null;
        BigDecimal r = s.divide(b, 10, RoundingMode.HALF_UP);
        int scale = r.compareTo(BigDecimal.TEN) >= 0 ? 2 : r.compareTo(BigDecimal.ONE) >= 0 ? 4 : 6;
        return r.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** سعر البرنامج بالاتجاه الذي اختاره المحاسب (buyPerSell أو عكسه). */
    public static BigDecimal referenceIn(BigDecimal buyPerSellRef, boolean buyPerSell) {
        if (buyPerSellRef == null) return null;
        if (buyPerSell) return buyPerSellRef;
        BigDecimal r = BigDecimal.ONE.divide(buyPerSellRef, 10, RoundingMode.HALF_UP);
        int scale = r.compareTo(BigDecimal.TEN) >= 0 ? 2 : r.compareTo(BigDecimal.ONE) >= 0 ? 4 : 6;
        return r.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
