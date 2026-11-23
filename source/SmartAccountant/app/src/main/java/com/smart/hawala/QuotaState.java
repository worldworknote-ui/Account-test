package com.smart.hawala;

import java.util.Map;
import java.util.TreeMap;

/**
 * عدّادات النسخة المجانية (منطق بحت). عدّادان لا ينقصان: المكاتب المنشأة، والحركات الجديدة لكل مكتب.
 * يُحفظان في إعدادات الهاتف (لا تنتقل مع النسخة ولا تُستبدل بالاستعادة) مع بصمة HMAC بمفتاح Keystore.
 * يُؤخذ دائمًا الأكبر بين العدّاد وأرضية البيانات الحية، وإعادة التثبيت تبدأ من البيانات الموجودة.
 * 9.0: حذف مكتب يعيد مكانه فارغًا (يُحذف معه كل ما عليه). التصحيحات (تعديل حركة) لا تُحتسب.
 */
public final class QuotaState {
    /** المكاتب المنشأة منذ التثبيت. */
    public long offices;
    /** الحركات الجديدة لكل مكتب (رقم المكتب ← العدد). */
    public final Map<Long, Long> tx = new TreeMap<>();

    public static QuotaState parse(String s) {
        QuotaState q = new QuotaState();
        if (s == null || s.isEmpty()) return q;
        for (String part : s.split(";")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            String k = part.substring(0, i);
            try {
                long v = Long.parseLong(part.substring(i + 1));
                if (v < 0) continue;
                if (k.equals("o")) q.offices = v;
                else if (k.startsWith("t")) q.tx.put(Long.parseLong(k.substring(1)), v);
            } catch (NumberFormatException ignored) {
                // جزء فاسد يُتجاهل (تحكم الأرضية)
            }
        }
        return q;
    }

    /** نص ثابت الترتيب (تُحسب عليه البصمة). */
    public String serialize() {
        StringBuilder b = new StringBuilder("o=").append(offices);
        for (Map.Entry<Long, Long> e : tx.entrySet()) if (e.getValue() > 0) b.append(";t").append(e.getKey()).append('=').append(e.getValue());
        return b.toString();
    }

    public long txOf(long office) {
        Long v = tx.get(office);
        return v == null ? 0 : v;
    }

    /** يرفع العدّاد إلى الأرضية إن كانت أكبر (لا ينقص أبدًا). */
    public void raiseTo(long liveOffices, Map<Long, Long> liveTx) {
        offices = Math.max(offices, liveOffices);
        for (Map.Entry<Long, Long> e : liveTx.entrySet()) tx.put(e.getKey(), Math.max(txOf(e.getKey()), e.getValue()));
    }

    /**
     * 9.0: حذف مكتب (بحركاته أو بدونها) يعيد مكانه فارغًا: المكتب وكل حركاته تُحذف معه، فلا بيانات تُصفَّر
     * والعدّاد يعود إلى عدد المكاتب الموجودة فعلًا.
     */
    public void officeDeleted(long office) {
        if (offices > 0) offices--;
        tx.remove(office);
    }

    /**
     * دمج مكتبين (اسمان لحساب واحد): حركات المصدر تنتقل إلى الهدف وتُحتسب عليه، فيعود مكان المصدر
     * (لا بيانات تُحذف ولا عدّاد يُصفَّر: الحركات كلها باقية ومحسوبة).
     */
    public void officesMerged(long from, long into) {
        tx.put(into, txOf(into) + txOf(from));
        tx.remove(from);
        if (offices > 0) offices--;
    }

    /** null = يُسمح بمكتب جديد، وإلا نص الرفض. */
    public String officeRefusal(Entitlements e) {
        long max = e.maxOffices();
        return offices >= max ? Entitlements.officeLimitMessage(max) : null;
    }

    /** null = يُسمح بحركة جديدة على هذا المكتب، وإلا نص الرفض. التعديل والإلغاء لا يمرّان من هنا أبدًا. */
    public String txRefusal(Entitlements e, long office, String officeName) {
        long max = e.maxTxPerOffice();
        return txOf(office) >= max ? Entitlements.txLimitMessage(officeName, max) : null;
    }

    /** المتبقي قبل السقف (للعرض: «بقي 37 حركة»)، أو -1 إن لم يكن هناك سقف. */
    public static long remaining(long used, long max) {
        return max == Entitlements.UNLIMITED ? -1 : Math.max(0, max - used);
    }
}
