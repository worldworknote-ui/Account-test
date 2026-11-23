import com.smart.hawala.DayClose;
import com.smart.hawala.Model;
import com.smart.hawala.Sanity;
import java.util.List;

/** 8.0: تنبيهات المعقولية قبل الحفظ (ت2). */
public class SanityTest {
    static int fails = 0, passes = 0;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Model.Tx tx(long amount, String cur, String person, long at, String status) {
        Model.Tx t = new Model.Tx();
        t.amount = amount;
        t.currency = cur;
        t.person = person;
        t.createdAt = at;
        t.status = status;
        return t;
    }

    static Sanity.Input in(long amount) {
        Sanity.Input i = new Sanity.Input();
        i.amount = amount;
        i.currency = "USD";
        i.dec = 2;
        i.when = 1_800_000_000_000L;
        i.debit = true;
        return i;
    }

    public static void main(String[] a) {
        long now = 1_800_000_000_000L, h = 3600000L;
        Sanity.Input i = in(500000);
        for (int k = 0; k < 6; k++) i.history.add(tx(100000, "USD", "س" + k, now - (k + 1) * h, "ACTIVE"));
        List<String> w = Sanity.check(i);
        check(w.size() == 1 && w.get(0).contains("غير معتاد") && w.get(0).contains("5,000") && w.get(0).contains("(\u20661,000\u2069)"), "5000 مقابل متوسط 1000: تنبيه " + w);
        i.amount = 300000;
        check(Sanity.check(i).isEmpty(), "ثلاثة أضعاف تمامًا: لا تنبيه");
        Sanity.Input few = in(900000);
        for (int k = 0; k < 4; k++) few.history.add(tx(100000, "USD", "", now - (k + 1) * h, "ACTIVE"));
        check(Sanity.check(few).isEmpty(), "أقل من 5 حركات سابقة: لا يُحكم بالمتوسط");
        Sanity.Input mixed = in(500000);
        for (int k = 0; k < 6; k++) mixed.history.add(tx(100000, "EUR", "", now - (k + 1) * h, "ACTIVE"));
        for (int k = 0; k < 6; k++) mixed.history.add(tx(100000, "USD", "", now - (k + 1) * h, "CANCELLED"));
        check(Sanity.check(mixed).isEmpty(), "عملة أخرى وملغاة لا تدخل المتوسط");

        System.out.println("== التكرار");
        Sanity.Input d = in(250000);
        d.person = "  أحمد   محمود ";
        d.history.add(tx(250000, "USD", "أحمد محمود", now - 4 * 60000L, "ACTIVE"));
        List<String> dw = Sanity.check(d);
        check(dw.size() == 1 && dw.get(0).contains("تكرار") && dw.get(0).contains("4 دقيقة"), "الشخص والمبلغ نفسه قبل 4 دقائق " + dw);
        d.history.set(0, tx(250000, "USD", "أحمد محمود", now - 11 * 60000L, "ACTIVE"));
        check(Sanity.check(d).isEmpty(), "بعد 11 دقيقة: لا تنبيه");
        d.history.set(0, tx(250000, "USD", "أحمد محمود", now - 2 * 60000L, "CANCELLED"));
        check(Sanity.check(d).isEmpty(), "المكرر ملغى: لا تنبيه");
        d.history.set(0, tx(250000, "USD", "سامر", now - 2 * 60000L, "ACTIVE"));
        check(Sanity.check(d).isEmpty(), "شخص آخر: لا تنبيه");
        d.history.set(0, tx(250001, "USD", "أحمد محمود", now - 2 * 60000L, "ACTIVE"));
        check(Sanity.check(d).isEmpty(), "مبلغ آخر: لا تنبيه");

        System.out.println("== حد المكتب");
        Sanity.Input l = in(200000);
        l.balanceBefore = 900000;
        l.receivableLimit = 1000000L;
        List<String> lw = Sanity.check(l);
        check(lw.size() == 1 && lw.get(0).contains("لنا") && lw.get(0).contains("11,000"), "تجاوز حد «لنا» " + lw);
        l.debit = false;
        check(Sanity.check(l).isEmpty(), "الحركة المعاكسة تقرّب من الصفر: لا تنبيه");
        Sanity.Input pl = in(300000);
        pl.debit = false;
        pl.balanceBefore = -100000;
        pl.payableLimit = 350000L;
        List<String> pw = Sanity.check(pl);
        check(pw.size() == 1 && pw.get(0).contains("علينا") && pw.get(0).contains("4,000"), "تجاوز حد «علينا» " + pw);
        pl.balanceBefore = -400000;
        check(Sanity.check(pl).isEmpty(), "كان متجاوزًا قبلها: التنبيه بعد الحفظ يكفي (لا تكرار)");

        System.out.println("== بصمة اليوم (ت5)");
        byte[] key = new byte[32], key2 = new byte[32];
        for (int k = 0; k < 32; k++) { key[k] = (byte) k; key2[k] = (byte) (k + 1); }
        java.util.List<Model.Tx> day = new java.util.ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            Model.Tx t = tx(100000L * k, "USD", "", now + k, "ACTIVE");
            t.id = k; t.uuid = "u" + k; t.type = Model.TYPE_DEBIT; t.feeType = Model.FEE_NONE; t.feeCurrency = "USD";
            day.add(t);
        }
        String c1 = DayClose.code(key, DayClose.canonical("2026/10/14", day));
        check(c1.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}") && !c1.matches(".*[01IO].*"), "رمز من 12 حرفًا بلا حروف ملتبسة: " + c1);
        java.util.List<Model.Tx> rev = new java.util.ArrayList<>(day);
        java.util.Collections.reverse(rev);
        check(c1.equals(DayClose.code(key, DayClose.canonical("2026/10/14", rev))), "ترتيب القراءة لا يغيّر البصمة");
        day.get(1).amount = 200001;
        check(!c1.equals(DayClose.code(key, DayClose.canonical("2026/10/14", day))), "تغيير مبلغ بسنت واحد يغيّر البصمة");
        day.get(1).amount = 200000;
        day.get(2).status = "CANCELLED";
        check(!c1.equals(DayClose.code(key, DayClose.canonical("2026/10/14", day))), "إلغاء حركة لاحقًا يغيّر بصمة يومها");
        day.get(2).status = "ACTIVE";
        check(c1.equals(DayClose.code(key, DayClose.canonical("2026/10/14", day))), "الحالة الأصلية تعيد البصمة نفسها");
        check(!c1.equals(DayClose.code(key2, DayClose.canonical("2026/10/14", day))), "سرّ مكتب آخر = بصمة أخرى (لا تُزوَّر بلا السرّ)");
        check(!c1.equals(DayClose.code(key, DayClose.canonical("2026/10/15", day))), "التاريخ جزء من البصمة");
        check(DayClose.normalize(" " + c1.toLowerCase().replace("-", " ") + " ").equals(c1), "يُقبل الرمز بأحرف صغيرة وبلا شرطات");

        System.out.println(fails == 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
        if (fails > 0) System.exit(1);
    }
}
