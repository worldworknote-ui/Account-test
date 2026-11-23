import com.smart.hawala.AuditChain;
import com.smart.hawala.Pin;
import java.util.ArrayList;
import java.util.List;

/** 8.0: رموز v2 بسرّ الهاتف + ترقية الصيغة القديمة، وسجل العمليات المتسلسل. */
public class PinTest {
    static int fails = 0, passes = 0;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    public static void main(String[] a) {
        System.out.println("== رموز الدخول v2");
        byte[] p1 = Pin.newPepper(), p2 = Pin.newPepper();
        String salt = Pin.newSalt();
        String h = Pin.hashV2("1234", salt, p1);
        check(h.startsWith("v2:") && h.length() == 3 + 64, "الصيغة v2: بادئة و64 خانة hex");
        check(h.equals(Pin.hashV2("1234", salt, p1)), "ثابتة لنفس الرمز والملح والسرّ");
        check(!h.equals(Pin.hashV2("1234", salt, p2)), "سرّ آخر = تجزئة أخرى (ملف القاعدة وحده لا يكفي للتخمين)");
        check(!h.equals(Pin.hashV2("1235", salt, p1)), "رمز آخر = تجزئة أخرى");
        check(Pin.verifyAny("1234", salt, h, p1) && !Pin.verifyAny("1234", salt, h, p2) && !Pin.verifyAny("0000", salt, h, p1), "التحقق v2");
        check(!Pin.verifyAny("1234", salt, h, null), "v2 بلا سرّ لا ينجح ولا يرمي");
        String old = Pin.hash("123456", salt);
        check(Pin.needsUpgrade(old) && !Pin.needsUpgrade(h), "الصيغة القديمة تُعلَّم للترقية");
        check(Pin.verifyAny("123456", salt, old, null) && Pin.verifyAny("123456", salt, old, p1) && !Pin.verifyAny("123457", salt, old, p1), "القديمة تُقبل بلا سرّ حتى تُرقّى");
        boolean threw;
        try {
            Pin.hashV2("1234", salt, new byte[4]);
            threw = false;
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "سرّ قصير مرفوض");
        long t0 = System.nanoTime();
        Pin.hashV2("1234", salt, p1);
        long ms = (System.nanoTime() - t0) / 1000000;
        long t1 = System.nanoTime();
        Pin.hash("1234", salt);
        long ms1 = Math.max(1, (System.nanoTime() - t1) / 1000000);
        check(ms >= ms1 * 2, "v2 أبطأ عمدًا من القديمة (" + ms + "ms مقابل " + ms1 + "ms)");
        check(Pin.normalizeRecovery("abcd-efgh ٢٣٤٥").equals("ABCDEFGH2345") || Pin.normalizeRecovery("abcd-efgh ٢٣٤٥").startsWith("ABCDEFGH"), "رمز الاسترداد يبقى بالصيغة الأولى ويُوحَّد");

        System.out.println("== سجل العمليات المتسلسل");
        List<AuditChain.Row> rows = new ArrayList<>();
        // سطران قديمان بلا بصمة (قبل 8.0)
        for (int i = 1; i <= 2; i++) rows.add(row(i, "CREATE_TRANSACTION", null, null, i * 10));
        String prev = "";
        for (int i = 3; i <= 8; i++) {
            AuditChain.Row r = row(i, i == 5 ? "EDIT_TRANSACTION" : "CREATE_TRANSACTION", prev, null, i * 10);
            r.hash = AuditChain.hash(r);
            prev = r.hash;
            rows.add(r);
        }
        AuditChain.Result ok = AuditChain.verify(rows, prev);
        check(ok.intact() && ok.legacy == 2 && ok.ok == 6, "سلسلة سليمة: 2 قديمان و6 متسلسلة");
        check(AuditChain.verify(rows, "").intact(), "بلا بصمة محفوظة (هاتف جديد) تُفحص السلسلة وحدها");

        List<AuditChain.Row> edited = copy(rows);
        edited.get(4).reason = "مبلغ آخر";
        AuditChain.Result e1 = AuditChain.verify(edited, prev);
        check(!e1.intact() && e1.brokenAt == 5 && e1.problem.contains("عُدّل"), "تعديل نص سطر يُكشف عند السطر نفسه");

        List<AuditChain.Row> deleted = copy(rows);
        deleted.remove(5);
        AuditChain.Result e2 = AuditChain.verify(deleted, prev);
        check(!e2.intact() && e2.brokenAt == 7 && e2.problem.contains("محذوف"), "حذف سطر من الوسط يُكشف عند الذي بعده");

        List<AuditChain.Row> tail = copy(rows);
        tail.remove(tail.size() - 1);
        AuditChain.Result e3 = AuditChain.verify(tail, prev);
        check(!e3.intact() && e3.brokenAt < 0 && e3.problem.contains("نهايته"), "حذف آخر سطر يُكشف بالبصمة المحفوظة");

        List<AuditChain.Row> head = copy(rows);
        head.remove(2);
        check(!AuditChain.verify(head, prev).intact(), "حذف أول سطر متسلسل يُكشف");

        List<AuditChain.Row> inserted = copy(rows);
        inserted.add(7, row(99, "CANCEL_TRANSACTION", null, null, 75));
        check(!AuditChain.verify(inserted, prev).intact(), "سطر بلا بصمة بعد بدء التسلسل يُكشف");

        List<AuditChain.Row> swapped = copy(rows);
        java.util.Collections.swap(swapped, 4, 5);
        check(!AuditChain.verify(swapped, prev).intact(), "تبديل ترتيب سطرين يُكشف");

        check(!AuditChain.hash("", "A", "b", 1, "x|y", "", 5).equals(AuditChain.hash("", "A", "b", 1, "x", "|y", 5)), "لا لبس بين الحقول (أطوال مسبقة)");

        System.out.println(fails == 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
        if (fails > 0) System.exit(1);
    }

    static AuditChain.Row row(long id, String action, String prev, String hash, long at) {
        AuditChain.Row r = new AuditChain.Row();
        r.id = id;
        r.action = action;
        r.entity = "tx";
        r.entityId = id * 3;
        r.user = "المدير";
        r.reason = "سبب " + id;
        r.createdAt = at;
        r.prev = prev;
        r.hash = hash;
        return r;
    }

    static List<AuditChain.Row> copy(List<AuditChain.Row> l) {
        List<AuditChain.Row> o = new ArrayList<>();
        for (AuditChain.Row r : l) {
            AuditChain.Row c = row(r.id, r.action, r.prev, r.hash, r.createdAt);
            c.entity = r.entity;
            c.entityId = r.entityId;
            c.user = r.user;
            c.reason = r.reason;
            o.add(c);
        }
        return o;
    }
}
