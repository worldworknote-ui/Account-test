package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * تنبيهات معقولية قبل حفظ حركة جديدة (منطق بحت): لا تمنع شيئًا، تُعرض في نافذة التأكيد فقط.
 * 1. مبلغ يفوق ثلاثة أضعاف متوسط حركات المكتب بالعملة نفسها (صفر زائد؟).
 * 2. تكرار محتمل: الشخص نفسه والمبلغ نفسه على المكتب نفسه خلال 10 دقائق.
 * 3. الرصيد بعد الحركة يتجاوز حد المكتب لهذه العملة.
 */
public final class Sanity {
    private Sanity() {}

    /** أقل عدد حركات سابقة ليُعتدّ بالمتوسط. */
    public static final int MIN_HISTORY = 5;
    public static final long DUP_WINDOW_MS = 10 * 60000L;

    public static final class Input {
        public long amount, when, balanceBefore;
        public boolean debit;
        public String currency = "", person = "";
        public int dec = 2;
        public Long receivableLimit, payableLimit;
        /** آخر الحركات النشطة لهذا المكتب (أي عملة)، الأحدث أولًا. */
        public List<Model.Tx> history = new ArrayList<>();
    }

    /**
     * 8.0 (ت4): هل تحتاج حركة الموظف اعتماد المحاسب؟ threshold بالسنت الأمريكي (0 = كل حركاته).
     * عملة بلا سعر صرف معروف لا يمكن تقويمها، فتُرسل للاعتماد احتياطًا.
     */
    /** 10.1: الاعتماد يُطلب ممن لا يملك صلاحية «اعتماد الحركات». */
    public static boolean needsApproval(boolean on, boolean canApprove, long amountMinor, int dec, java.math.BigDecimal usdPerUnit, long thresholdUsdCents) {
        if (!on || canApprove) return false;
        if (thresholdUsdCents <= 0) return true;
        if (usdPerUnit == null || usdPerUnit.signum() <= 0) return true;
        java.math.BigDecimal cents = java.math.BigDecimal.valueOf(amountMinor, dec).multiply(usdPerUnit).movePointRight(2);
        return cents.compareTo(java.math.BigDecimal.valueOf(thresholdUsdCents)) >= 0;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static List<String> check(Input in) {
        List<String> out = new ArrayList<>();
        // 1) غير معتاد
        long sum = 0;
        int n = 0;
        for (Model.Tx t : in.history) {
            if (!t.isActive() || !in.currency.equals(t.currency)) continue;
            sum += t.amount;
            if (++n >= 50) break;
        }
        if (n >= MIN_HISTORY) {
            long avg = sum / n;
            if (avg > 0 && in.amount > 3 * avg) {
                out.add("مبلغ غير معتاد: \u2066" + Model.fmt(in.amount, in.dec) + " " + in.currency + "\u2069 أكبر من ثلاثة أضعاف متوسط آخر " + n
                        + " حركة لهذا المكتب (\u2066" + Model.fmt(avg, in.dec) + "\u2069). تأكد من الأصفار.");
            }
        }
        // 2) تكرار محتمل
        String p = norm(in.person);
        for (Model.Tx t : in.history) {
            if (!t.isActive() || t.amount != in.amount || !in.currency.equals(t.currency)) continue;
            if (!p.equals(norm(t.person))) continue;
            long gap = Math.abs(in.when - t.createdAt);
            if (gap <= DUP_WINDOW_MS) {
                long min = Math.max(1, gap / 60000L);
                out.add("تكرار محتمل: حركة بالمبلغ نفسه" + (p.isEmpty() ? "" : " للشخص نفسه") + " على هذا المكتب قبل " + (gap < 60000L ? "أقل من دقيقة" : min + " دقيقة") + ".");
                break;
            }
        }
        // 3) حد المكتب
        long after = in.balanceBefore + (in.debit ? in.amount : -in.amount);
        if (after > 0 && in.receivableLimit != null && after > in.receivableLimit && in.balanceBefore <= in.receivableLimit) {
            out.add("بعد هذه الحركة يصير لنا على المكتب \u2066" + Model.fmt(after, in.dec) + " " + in.currency + "\u2069 وحدّه \u2066" + Model.fmt(in.receivableLimit, in.dec) + "\u2069.");
        }
        if (after < 0 && in.payableLimit != null && -after > in.payableLimit && -in.balanceBefore <= in.payableLimit) {
            out.add("بعد هذه الحركة يصير علينا للمكتب \u2066" + Model.fmt(-after, in.dec) + " " + in.currency + "\u2069 وحدّه \u2066" + Model.fmt(in.payableLimit, in.dec) + "\u2069.");
        }
        return out;
    }
}
