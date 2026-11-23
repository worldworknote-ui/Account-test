import android.content.Context;
import com.smart.hawala.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

/**
 * 9.0 على Db الحقيقي (sqlite-jdbc): صندوق المكتب، وحد النسخة المجانية (الحذف يعيد المكان، واختيار 9 مكاتب عند الاستعادة
 * والإضافة)، وعدّاد واتساب اليومي، وترتيب المكاتب. كل رقم يُقارن بقيمة محسوبة باليد، وفحص الصحة الكامل بعد كل مرحلة.
 */
public class CashDbTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Map<String, Long> bal(Db db, long office) { return db.balances(office); }

    static long b(Map<String, Long> m, String c) {
        Long v = m.get(c);
        return v == null ? 0 : v;
    }

    static boolean healthy(Db db, String label) {
        boolean all = true;
        for (Db.Check c : db.healthCheck(true)) {
            if (!c.ok) {
                all = false;
                System.out.println("      ✗ " + c.title + ": " + c.detail);
            }
        }
        check(all, label + ": فحص الصحة الكامل سليم");
        return all;
    }

    static CashBox.Input in(String kind, String cur, long amount) {
        CashBox.Input i = new CashBox.Input();
        i.kind = kind;
        i.currency = cur;
        i.amount = amount;
        i.when = System.currentTimeMillis() - 1000;
        return i;
    }

    static boolean throwsMsg(Runnable r, String part) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            StringBuilder all = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) all.append(t.getMessage()).append(" | ");
            boolean ok = part == null || all.toString().contains(part);
            if (!ok) System.out.println("      رسالة: " + all);
            return ok;
        }
    }

    interface Io { void run() throws Exception; }

    static Runnable io(Io x) {
        return () -> {
            try {
                x.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        File data = new File(a.length > 0 ? a[0] : "tests/data");
        JSONObject expect = new JSONObject(new String(Files.readAllBytes(new File(data, "expect.json").toPath()), StandardCharsets.UTF_8));

        System.out.println("== 1) صندوق المكتب: الإعداد والأرصدة الافتتاحية");
        Object[] dv = DeskCoreTest.device("cash");
        Context ctx = (Context) dv[0];
        Db db = (Db) dv[1];
        List<String> ue = Arrays.asList("USD", "EUR");
        long nour = db.addOffice("النور", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long amal = db.addOffice("الأمل", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        check(throwsMsg(io(() -> db.addCashMove(in(CashBox.IN, "USD", 100))), "غير مُعَد"), "الصندوق غير المعَد يرفض الحركات");
        Map<String, Long> op = new HashMap<>();
        op.put("USD", 1000000L);   // 10,000.00
        op.put("EUR", 200000L);    // 2,000.00
        db.cashSetup("الصندوق الرئيسي", ue, op, false, true);
        Map<String, Long> cb = db.cashBalances();
        check(b(cb, "USD") == 1000000 && b(cb, "EUR") == 200000, "الأرصدة الافتتاحية: 10,000 USD و2,000 EUR " + cb);
        check(db.cashConfig().name.equals("الصندوق الرئيسي") && db.cashConfig().on, "اسم الصندوق وحالته");

        System.out.println("== 2) حركات غير مرتبطة بمكتب: استلام وتسليم بأجور نقدية، ومصروف");
        CashBox.Input i1 = in(CashBox.IN, "USD", 50000);           // +500
        i1.category = "استلام من شخص";
        i1.party = "أحمد";
        i1.fee = 1000;                                               // +10 أجور لنا نقدًا
        i1.feeSide = CashBox.FEE_US;
        db.addCashMove(i1);
        CashBox.Input i2 = in(CashBox.OUT, "USD", 20000);          // −200
        i2.category = "سحب لصاحب المكتب";
        i2.fee = 500;                                                // −5 أجور علينا نقدًا
        i2.feeSide = CashBox.FEE_THEM;
        db.addCashMove(i2);
        CashBox.Input i3 = in(CashBox.EXPENSE, "USD", 30000);      // −300
        i3.category = "إيجار المحل";
        db.addCashMove(i3);
        cb = db.cashBalances();
        check(b(cb, "USD") == 1000000 + 50000 + 1000 - 20000 - 500 - 30000, "USD = 10,000 + 500 + 10 − 200 − 5 − 300 = 10,005: " + b(cb, "USD"));
        check(b(bal(db, nour), "USD") == 0 && b(bal(db, amal), "USD") == 0, "حركات الصندوق العامة لا تمس أرصدة المكاتب");
        CashBox.Input bad = in(CashBox.EXPENSE, "USD", 100);
        check(throwsMsg(io(() -> db.addCashMove(bad)), "بند"), "المصروف بلا بند يُرفض");
        CashBox.Input bad2 = in(CashBox.IN, "TRY", 100);
        bad2.category = "أخرى";
        check(throwsMsg(io(() -> db.addCashMove(bad2)), "غير مفعّلة في الصندوق"), "عملة غير مفعّلة في الصندوق تُرفض");

        System.out.println("== 3) حركات لحساب مكتب: تُسجَّل على المكتب في المعاملة نفسها");
        CashBox.Input o1 = in(CashBox.OUT, "USD", 10000);          // تسليم 100 لحساب النور
        o1.officeId = nour;
        o1.party = "محمد علي";
        o1.fee = 100;                                                // أجرة 1 لنا على حساب المكتب
        o1.feeSide = CashBox.FEE_US;
        o1.feeInCash = false;
        long m1 = db.addCashMove(o1);
        check(b(bal(db, nour), "USD") == 10100, "النور: لنا 100 + أجرة 1 = 101 USD: " + b(bal(db, nour), "USD"));
        long cashUsd = b(db.cashBalances(), "USD");
        check(cashUsd == 1000500 - 10000, "الصندوق ناقص 100 فقط (الأجرة على المكتب لا تمس النقد): " + cashUsd);
        CashBox.Input o2 = in(CashBox.IN, "EUR", 50000);           // استلام 500 EUR لحساب الأمل
        o2.officeId = amal;
        o2.fee = 500;                                                // أجرة 5 لنا نقدًا
        o2.feeSide = CashBox.FEE_US;
        o2.feeInCash = true;
        db.addCashMove(o2);
        check(b(bal(db, amal), "EUR") == -50000, "الأمل: علينا 500 EUR (الأجرة قُبضت نقدًا فلا تظهر على المكتب): " + b(bal(db, amal), "EUR"));
        check(b(db.cashBalances(), "EUR") == 200000 + 50000 + 500, "EUR الصندوق = 2,000 + 500 + 5 = 2,505: " + b(db.cashBalances(), "EUR"));
        CashBox.Move mv = db.cashMove(m1);
        check(mv.txId != null && db.cashLinked(mv.txId), "حركة الصندوق تحمل رقم حركة المكتب");
        Model.Tx tx1 = db.tx(mv.txId);
        check(Model.TYPE_DEBIT.equals(tx1.type) && tx1.amount == 10000 && "USD".equals(tx1.currency) && Model.FEE_US.equals(tx1.feeType) && tx1.fee == 100
                && "محمد علي".equals(tx1.person) && "الصندوق".equals(tx1.service), "حركة المكتب: لنا 100 USD بأجرة 1 لنا، والمستفيد والخدمة «الصندوق»");
        Db.TxInput ed = new Db.TxInput();
        ed.office = nour;
        ed.type = Model.TYPE_DEBIT;
        ed.amount = 20000;
        ed.currency = "USD";
        ed.when = System.currentTimeMillis();
        final long t1 = mv.txId;
        // 10.5 (AUDIT-017): التعديل من جهة المكتب صار يصحح حركة الصندوق معه في المعاملة نفسها (بدل المنع)
        long cashBefore = b(db.cashBalances(), "USD");
        long t2 = db.editTx(t1, ed, null, null, "مبلغ خاطئ");
        check(db.cashMove(m1).txId == t2 && db.cashMove(m1).amount == 20000 && db.cashMove(m1).fee == 0, "تعديل حركة من الصندوق ينقل حركة الصندوق إلى الحركة الجديدة بمبلغها");
        check(b(db.cashBalances(), "USD") == cashBefore - 10000 && b(bal(db, nour), "USD") == 20000, "النقد خرج 200 بدل 100، والنور لنا 200 (بلا الأجرة المحذوفة)");
        Db.TxInput restore1 = new Db.TxInput();
        restore1.office = nour;
        restore1.type = Model.TYPE_DEBIT;
        restore1.amount = 10000;
        restore1.currency = "USD";
        restore1.feeType = Model.FEE_US;
        restore1.fee = 100;
        restore1.feeCurrency = "USD";
        restore1.person = "محمد علي";
        restore1.when = System.currentTimeMillis();
        db.editTx(t2, restore1, null, null, "إرجاع");
        check(b(db.cashBalances(), "USD") == cashBefore && b(bal(db, nour), "USD") == 10100 && db.cashMove(m1).fee == 100 && !db.cashMove(m1).feeInCash,
                "إرجاعها كما كانت يعيد النقد والرصيد والأجرة على حساب المكتب");
        healthy(db, "بعد الحركات المرتبطة");

        System.out.println("== 4) صرف عملة داخل الصندوق");
        CashBox.Input ex = in(CashBox.EXCHANGE, "USD", 100000);    // 1,000 USD ← 920 EUR
        ex.toCurrency = "EUR";
        ex.toAmount = 92000;
        ex.rate = "0.92";
        long exId = db.addCashMove(ex);
        cb = db.cashBalances();
        check(b(cb, "USD") == 990500 - 100000 && b(cb, "EUR") == 250500 + 92000, "الصرف: USD ناقص 1,000 وEUR زائد 920 " + cb);

        System.out.println("== 5) الإلغاء في الاتجاهين");
        db.cancelCashMove(m1, "خطأ");
        check(b(bal(db, nour), "USD") == 0, "إلغاء حركة الصندوق يلغي حركة المكتب بقيد عكسي (رصيد النور صفر)");
        check(b(db.cashBalances(), "USD") == 890500 + 10000, "ويعود النقد للصندوق: " + b(db.cashBalances(), "USD"));
        CashBox.Move m2 = null;
        for (CashBox.Move x : db.cashMoves()) if (x.officeId != null && x.officeId == amal) m2 = x;
        db.cancelTx(m2.txId, "من صفحة المكتب");
        check(db.cashMove(m2.id).cancelled() && b(db.cashBalances(), "EUR") == 342500 - 50500, "إلغاء حركة المكتب من صفحته يلغي حركة الصندوق (والأجرة النقدية معها)");
        db.cancelCashMove(exId, "صرف خاطئ");
        cb = db.cashBalances();
        check(b(cb, "USD") == 1000500 && b(cb, "EUR") == 200000, "إلغاء الصرف يلغي ساقيه معًا " + cb);
        healthy(db, "بعد الإلغاء");

        System.out.println("== 6) الخلاصة والتقرير");
        List<CashBox.Move> all = db.cashMoves();
        Map<String, CashBox.Summary> sums = CashBox.summaries(all, 0, 0, null);
        boolean same = true;
        for (Map.Entry<String, Long> e : db.cashBalances().entrySet()) {
            CashBox.Summary s = sums.get(e.getKey());
            if (s == null ? e.getValue() != 0 : s.closing != e.getValue()) same = false;
        }
        check(same, "الخلاصة: آخر الفترة = أولها + الداخل − الخارج ± الأجور − المصاريف = الرصيد لكل عملة");
        CashBox.Summary su = sums.get("USD");
        check(su.expenses == 30000 && su.byCategory.get("إيجار المحل") == 30000 && su.feesIn == 1000 && su.feesOut == 500, "المصاريف حسب البند والأجور النقدية");
        long mid = System.currentTimeMillis() + 1;
        Map<String, CashBox.Summary> s2 = CashBox.summaries(all, mid, 0, null);
        check(s2.get("USD").opening == su.closing && s2.get("USD").closing == su.closing, "فترة بعد كل الحركات: أولها = آخرها = الرصيد "
                + s2.get("USD").opening + " " + s2.get("USD").closing + " " + su.closing + " in=" + s2.get("USD").in + " out=" + s2.get("USD").out + " cnt=" + s2.get("USD").count);
        CashBox.ReportSpec sp = new CashBox.ReportSpec();
        Report r = CashBox.report(all, sp, db.decimalsMap(), db.officeNames(), "الصندوق الرئيسي", "مكتب الاختبار");
        check(r.sections.size() >= 4 && r.dataRows() > 5, "تقرير الصندوق: " + r.sections.size() + " أقسام");

        System.out.println("== 7) الجرد");
        Map<String, Long> counted = new HashMap<>();
        counted.put("USD", 1000000L);   // العجز 5
        counted.put("EUR", 200300L);    // زيادة 3
        int n = db.cashCount(counted, "جرد المساء");
        cb = db.cashBalances();
        check(n == 2 && b(cb, "USD") == 1000000 && b(cb, "EUR") == 200300, "الجرد يسجّل عجز 5 USD وزيادة 3 EUR فيطابق الصندوق المعدود " + cb);

        System.out.println("== 8) الربط الإلزامي");
        db.cashSetup("الصندوق الرئيسي", ue, null, true, false);
        CashBox.Input s1 = in(CashBox.IN, "USD", 100);
        s1.category = "أخرى";
        check(throwsMsg(io(() -> db.addCashMove(s1)), "الربط الإلزامي"), "الاستلام بلا مكتب يُرفض");
        CashBox.Input s3 = in(CashBox.EXPENSE, "USD", 100);
        s3.category = "ضيافة ونثريات";
        check(throwsMsg(io(() -> db.addCashMove(s3)), "لا يسمح بالمصاريف"), "المصروف يُرفض حين لا يُسمح به");
        db.cashSetup("الصندوق الرئيسي", ue, null, true, true);
        db.addCashMove(s3);
        CashBox.Input s4 = in(CashBox.OUT, "USD", 5000);
        s4.officeId = amal;
        db.addCashMove(s4);
        check(b(db.cashBalances(), "USD") == 1000000 - 100 - 5000 && b(bal(db, amal), "USD") == 5000, "مع الإذن: المصروف مقبول، والتسليم لحساب مكتب يعمل");
        check(throwsMsg(() -> db.cashSetup("x", Arrays.asList("EUR"), null, false, true), "لا يمكن إزالة"), "لا تُزال عملة رصيدها في الصندوق ليس صفرًا");

        System.out.println("== 9) حذف مكتب ودمج مكتبين مع حركات صندوق");
        long rami = db.addOffice("الرامي", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        CashBox.Input d1 = in(CashBox.OUT, "EUR", 7000);
        d1.officeId = rami;
        db.addCashMove(d1);
        long eurBefore = b(db.cashBalances(), "EUR");
        db.deleteOffice(rami, true);
        check(b(db.cashBalances(), "EUR") == eurBefore, "حذف المكتب لا يغيّر الصندوق (النقد خرج فعلًا): تُفك الحركة وتبقى");
        boolean unlinked = false;
        for (CashBox.Move x : db.cashMoves()) if (x.amount == 7000 && x.officeId == null && x.txId == null && x.category.contains("محذوف")) unlinked = true;
        check(unlinked, "حركة الصندوق للمكتب المحذوف بقيت بلا ربط وبند «لحساب مكتب محذوف»");
        long amal2 = db.addOffice("مكتب الأمل", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        db.mergeOffices(amal, amal2);
        boolean moved = true;
        for (CashBox.Move x : db.cashMoves()) if (x.officeId != null && x.officeId == amal) moved = false;
        check(moved && b(bal(db, amal2), "USD") == 5000, "الدمج ينقل ربط حركات الصندوق للمكتب الهدف");
        healthy(db, "بعد الحذف والدمج");

        System.out.println("== 9.1) 9.0.1: البيع والشراء");
        db.cashSetup("الصندوق الرئيسي", ue, null, false, true);
        Map<String, Long> c0 = db.cashBalances();
        CashBox.Input sell = in(CashBox.EXCHANGE, "USD", 10000);   // بيع 100 USD بسعر 0.92 = 92 EUR
        sell.side = CashBox.SELL;
        sell.toCurrency = "EUR";
        sell.rate = "0.92";
        sell.toAmount = CashBox.counterAmount(10000, 2, "0.92", 2);
        check(sell.toAmount == 9200, "المقابل محسوب: 100 × 0.92 = 92.00 EUR");
        db.addCashMove(sell);
        CashBox.Input buy = in(CashBox.EXCHANGE, "EUR", 5000);     // شراء 50 EUR بسعر 1.08 = ندفع 54 USD
        buy.side = CashBox.BUY;
        buy.toCurrency = "USD";
        buy.rate = "1.08";
        buy.toAmount = CashBox.counterAmount(5000, 2, "1.08", 2);
        long buyId = db.addCashMove(buy);
        Map<String, Long> c1 = db.cashBalances();
        check(b(c1, "USD") == b(c0, "USD") - 10000 - 5400 && b(c1, "EUR") == b(c0, "EUR") + 9200 + 5000,
                "البيع: USD −100 وEUR +92؛ الشراء: EUR +50 وUSD −54 " + c1);
        CashBox.Input huge = in(CashBox.EXCHANGE, "EUR", 100000000);
        huge.side = CashBox.BUY;
        huge.toCurrency = "USD";
        huge.toAmount = 108000000;
        String neg = CashBox.negativeAfter(huge, c1, db.decimalsMap());
        check(neg != null && neg.contains("USD"), "الشراء الذي يدفع أكثر مما في الصندوق ينبّه على عملة الدفع (USD)");
        Map<String, Long> ch = CashBox.changes(buy);
        check(ch.get("EUR") == 5000 && ch.get("USD") == -5400, "المعاينة: أثر الشراء +50 EUR و−54 USD");
        Report fx = CashBox.report(db.cashMoves(), new CashBox.ReportSpec(), db.decimalsMap(), db.officeNames(), "x", "");
        boolean fxSec = false;
        for (Report.Section sec : fx.sections) if (sec.heading.contains("بيع وشراء")) fxSec = sec.rows.size() == 2;
        check(fxSec, "التقرير: قسم البيع والشراء بسطرين (بيع USD/EUR وشراء EUR/USD)");
        db.cancelCashMove(buyId, "خطأ");
        Map<String, Long> c2 = db.cashBalances();
        check(b(c2, "USD") == b(c0, "USD") - 10000 && b(c2, "EUR") == b(c0, "EUR") + 9200, "إلغاء الشراء يعيد ساقيه معًا");
        healthy(db, "بعد البيع والشراء");

        System.out.println("== 9.2) 9.0.1: عدة صناديق والتحويل بينها");
        Map<String, Long> op2 = new HashMap<>();
        op2.put("USD", 50000L);
        // 12.6: المجانية صندوق واحد؛ الصناديق المتعددة بالرخصة
        check(throwsMsg(() -> db.addCashBox("خزنة البيت", Arrays.asList("USD"), op2), "صندوق واحد"), "12.6: المجانية لا تضيف صندوقًا ثانيًا");
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        long box2 = db.addCashBox("خزنة البيت", Arrays.asList("USD"), op2);
        check(box2 == 2 && b(db.cashBalances(box2), "USD") == 50000 && db.cashBalances(1).equals(c2), "صندوق ثانٍ برصيد 500 USD والأول لم يتغير");
        check(throwsMsg(() -> db.addCashBox("خزنة البيت", Arrays.asList("USD"), null), "بالاسم نفسه"), "لا يتكرر اسم الصندوق");
        CashBox.Input tr = in(CashBox.TRANSFER, "USD", 10000);
        tr.boxId = 1;
        tr.toBox = box2;
        db.addCashMove(tr);
        check(b(db.cashBalances(1), "USD") == b(c2, "USD") - 10000 && b(db.cashBalances(box2), "USD") == 60000, "التحويل: −100 من الأول و+100 في الثاني");
        CashBox.Input tr2 = in(CashBox.TRANSFER, "EUR", 100);
        tr2.boxId = 1;
        tr2.toBox = box2;
        check(throwsMsg(io(() -> db.addCashMove(tr2)), "غير مفعّلة"), "تحويل عملة غير مفعّلة في صندوق الوجهة يُرفض");
        check(throwsMsg(() -> db.setCashBoxActive(box2, false), "رصيد"), "لا يُؤرشف صندوق فيه رصيد");
        CashBox.Input back = in(CashBox.TRANSFER, "USD", 60000);
        back.boxId = box2;
        back.toBox = 1L;
        db.addCashMove(back);
        db.setCashBoxActive(box2, false);
        CashBox.Input dead = in(CashBox.IN, "USD", 100);
        dead.boxId = box2;
        dead.category = "أخرى";
        check(throwsMsg(io(() -> db.addCashMove(dead)), "معطّل"), "بعد التعطيل (رصيده صفر) لا تُسجَّل عليه حركات");
        // 10.0: لكل صندوق ربطه بالمكاتب، والحذف بشروط
        db.setCashBoxActive(box2, true);
        db.cashSetup(1, "الصندوق الرئيسي", ue, null, true, false);
        CashBox.Input free2 = in(CashBox.IN, "USD", 1000);
        free2.boxId = box2;
        free2.category = "أخرى";
        db.addCashMove(free2);
        CashBox.Input strict1 = in(CashBox.IN, "USD", 1000);
        strict1.boxId = 1;
        strict1.category = "أخرى";
        check(throwsMsg(io(() -> db.addCashMove(strict1)), "مربوط") || throwsMsg(io(() -> db.addCashMove(strict1)), "الربط"),
                "الأول مربوط بالمكاتب يرفض الاستلام بلا حساب، والثاني الحر يقبله");
        check(db.cashBox(1).strict && !db.cashBox(box2).strict, "الربط محفوظ لكل صندوق على حدة");
        db.cashSetup(1, "الصندوق الرئيسي", ue, null, false, true);
        check(db.cashBoxDeleteBlock(box2) != null && db.cashBoxDeleteBlock(box2).contains("رصيد"), "لا يُحذف صندوق فيه رصيد");
        CashBox.Input out2 = in(CashBox.OUT, "USD", 1000);
        out2.boxId = box2;
        out2.category = "أخرى";
        db.addCashMove(out2);
        check(db.cashBoxDeleteBlock(box2) != null && db.cashBoxDeleteBlock(box2).contains("بصناديق أخرى"), "لا يُحذف صندوق فيه تحويلات مع صندوق آخر (يُعطَّل بدلًا منه)");
        Map<String, Long> op3 = new HashMap<>();
        op3.put("USD", 7000L);
        long box3 = db.addCashBox("صندوق مؤقت", Arrays.asList("USD"), op3, true, true);
        check(db.cashBox(box3).strict, "صندوق جديد مربوط من إنشائه");
        CashBox.Input ex3 = in(CashBox.EXPENSE, "USD", 7000);
        ex3.boxId = box3;
        ex3.category = "ضيافة ونثريات";
        db.addCashMove(ex3);
        check(db.cashBoxDeleteBlock(box3) == null, "صندوق رصيده صفر بلا روابط يجوز حذفه");
        Map<String, Long> b1before = db.cashBalances(1);
        db.deleteCashBox(box3);
        check(!db.cashMoves(box3).isEmpty() == false && db.cashBalances(1).equals(b1before), "الحذف يزيل الصندوق وحركاته ولا يمس غيره");
        check(b(db.cashBalances(1), "USD") == b(c2, "USD") + 50000 && db.currentBox() == 1, "عاد كل نقده للأول: +500 (الافتتاحي) والمفتوح هو الأول");
        healthy(db, "بعد الصناديق");

        System.out.println("== 9.3) 9.0.1: الجرد: زيادة عملة يقابلها عجز أخرى");
        db.setManualRate("EUR", new java.math.BigDecimal("1.08"));
        Map<String, Long> c3 = db.cashBalances(1);
        Map<String, Long> cnt = new LinkedHashMap<>();
        cnt.put("USD", b(c3, "USD") - 10000);    // عجز 100 USD
        cnt.put("EUR", b(c3, "EUR") + 9200);     // زيادة 92 EUR ≈ 99.36 USD
        List<CashBox.CountLine> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : cnt.entrySet()) lines.add(new CashBox.CountLine(e.getKey(), b(c3, e.getKey()), e.getValue()));
        List<CashBox.Suggestion> sg = CashBox.analyze(lines, db.usdRates(), db.decimalsMap());
        check(sg.size() == 1 && sg.get(0).soldCur.equals("USD") && sg.get(0).sold == 10000 && sg.get(0).gotCur.equals("EUR") && sg.get(0).got == 9200
                && sg.get(0).rate.compareTo(new java.math.BigDecimal("0.92")) == 0, "الاقتراح: بيع 100 USD بـ 92 EUR (بسعر 0.92) لم يُسجَّل");
        CashBox.Count cc = db.cashCount(1, cnt, "جرد الخميس", sg);
        Map<String, Long> c4 = db.cashBalances(1);
        check(cc.adjusted == 0 && b(c4, "USD") == cnt.get("USD") && b(c4, "EUR") == cnt.get("EUR"), "سُجّل بيعًا: لا تسويات والصندوق مطابق للمعدود " + c4);
        List<CashBox.Count> log = db.cashCounts(1);
        CashBox.Count last = log.get(0);
        check(last.note.equals("جرد الخميس") && last.sales.contains("بيع") && last.lines.size() == 2 && last.lines.get(0).diff() == -10000,
                "سجل الجرد يحفظ المسجّل والمعدود قبل التسوية وما سُجّل بيعًا");
        List<CashBox.CountLine> no = Arrays.asList(new CashBox.CountLine("USD", 10000, 0), new CashBox.CountLine("EUR", 0, 5000));
        check(CashBox.analyze(no, db.usdRates(), db.decimalsMap()).isEmpty(), "زيادة 50 EUR لا تعادل عجز 100 USD: لا اقتراح");
        check(log.size() >= 2, "سجل الجرد فيه الجرد السابق أيضًا: " + log.size());
        healthy(db, "بعد الجرد");

        System.out.println("== 9.4) 9.0.1: حسابات العملاء");
        ClientBook.Client kc = new ClientBook.Client();
        kc.name = "أبو خالد";
        kc.phone = "0999";
        long kid = db.saveClient(kc);
        ClientBook.Client k2 = new ClientBook.Client();
        k2.name = "أبو خالد";
        check(throwsMsg(() -> db.saveClient(k2), "بالاسم نفسه"), "لا يتكرر اسم العميل");
        Map<String, Long> c5 = db.cashBalances(1);
        CashBox.Input ci = in(CashBox.IN, "EUR", 10000);           // استلمنا منه 100 EUR
        ci.clientId = kid;
        ci.clientCurrency = "EUR";
        ci.clientAmount = 10000;
        db.addCashMove(ci);
        check(b(ClientBook.balances(db.clientMoves(kid)), "EUR") == -10000 && b(db.cashBalances(1), "EUR") == b(c5, "EUR") + 10000,
                "استلام منه: EUR الصندوق +100 والعميل: علينا له 100 EUR");
        CashBox.Input co = in(CashBox.OUT, "USD", 10800);          // يأخذ 108 USD بدل الـ 100 EUR
        co.clientId = kid;
        co.clientCurrency = "EUR";
        co.clientAmount = 10000;
        long coId = db.addCashMove(co);
        check(ClientBook.balances(db.clientMoves(kid)).isEmpty() && b(db.cashBalances(1), "USD") == b(c5, "USD") - 10800,
                "أخذ 108 USD مكان الـ 100 EUR: حسابه صفر، وخرج 108 USD من الصندوق");
        ClientBook.Move cash2 = null;
        for (ClientBook.Move x : db.clientMoves(kid)) if (x.cashUuid != null && "USD".equals(x.cashCurrency)) cash2 = x;
        check(cash2 != null && "1.08".equals(cash2.rate) && cash2.cashAmount == 10800 && ClientBook.text(cash2, db.decimalsMap()).contains("1 EUR = 1.08 USD"),
                "السعر محفوظ ويظهر في الكشف: " + (cash2 == null ? "" : ClientBook.text(cash2, db.decimalsMap())));
        CashBox.Input bad3 = in(CashBox.OUT, "USD", 100);
        bad3.clientId = kid;
        bad3.clientCurrency = "USD";
        bad3.clientAmount = 200;
        check(throwsMsg(io(() -> db.addCashMove(bad3)), "المبلغ نفسه"), "بالعملة نفسها يُقيَّد المبلغ نفسه");
        db.addClientEntry(kid, "USD", 5000, 1, "دين قديم", System.currentTimeMillis() - 500);
        check(b(ClientBook.balances(db.clientMoves(kid)), "USD") == 5000, "قيد بلا نقد: لنا عليه 50 USD (الصندوق لم يتغير)");
        check(throwsMsg(() -> db.addClientSwap(kid, "USD", 6000, "EUR", 5000, "", System.currentTimeMillis() - 400), "أكبر من رصيد"), "لا يُبدَّل أكثر من الرصيد");
        db.addClientSwap(kid, "USD", 5000, "EUR", 4600, "اتفاق", System.currentTimeMillis() - 300);
        Map<String, Long> kb = ClientBook.balances(db.clientMoves(kid));
        check(b(kb, "USD") == 0 && b(kb, "EUR") == 4600, "التبديل: دينه 50 USD صار 46 EUR (لنا) " + kb);
        check(throwsMsg(() -> db.setClientActive(kid, false), "ليس صفرًا"), "لا يُؤرشف عميل رصيده ليس صفرًا");
        db.cancelClientMove(cash2.id, "خطأ");
        kb = ClientBook.balances(db.clientMoves(kid));
        check(b(kb, "EUR") == 4600 - 10000 && b(db.cashBalances(1), "USD") == b(c5, "USD") && db.cashMove(coId).cancelled(),
                "إلغاء التسليم من حساب العميل يلغي حركة الصندوق ويعيد له 100 EUR " + kb);
        db.cashSetup("الصندوق الرئيسي", ue, null, true, true);
        CashBox.Input st = in(CashBox.IN, "USD", 1000);
        st.clientId = kid;
        st.clientCurrency = "USD";
        st.clientAmount = 1000;
        db.addCashMove(st);
        check(b(ClientBook.balances(db.clientMoves(kid)), "USD") == -1000, "الربط الإلزامي يقبل الحركة لحساب عميل");
        db.cashSetup("الصندوق الرئيسي", ue, null, false, true);
        Report kr = ClientBook.statement(db.client(kid), db.clientMoves(kid), 0, 0, db.decimalsMap(), "");
        check(kr.sections.size() == 3, "كشف العميل: الرصيد + كشف لكل عملة (EUR وUSD): " + kr.sections.size());
        Map<String, long[]> tot = ClientBook.totals(db.clientBalances());
        check(tot.get("EUR")[1] == 5400 && tot.get("USD")[1] == 1000, "إجمالي العملاء: علينا 54 EUR و10 USD");
        healthy(db, "بعد حسابات العملاء");

        System.out.println("== 10) النسخة الاحتياطية تنقل الصندوق كاملًا");
        File bk = new File(ctx.getFilesDir(), "cash.sba");
        BackupManager.Created created = BackupManager.createLocal(ctx, db, "كلمة-سر-الصندوق".toCharArray(), "9.0", m -> {});
        Files.copy(created.file.toPath(), bk.toPath());
        Map<String, Long> cashA = db.cashBalances();
        int movesA = db.cashMoves().size();
        Object[] dv2 = DeskCoreTest.device("cash2");
        Context ctx2 = (Context) dv2[0];
        Db db2 = (Db) dv2[1];
        DeskCoreTest.restore(ctx2, db2, bk, "كلمة-سر-الصندوق");
        check(db2.cashBalances().equals(cashA) && db2.cashMoves().size() == movesA && db2.cashConfig().strict == db.cashConfig().strict, "على الجهاز الآخر: " + movesA + " حركة صندوق وأرصدته وإعداداته كما هي " + db2.cashBalances());
        boolean names = false;
        for (CashBox.Move x : db2.cashMoves()) if ("أحمد".equals(x.party)) names = true;
        check(names, "الأسماء المشفّرة في الصندوق تُقرأ بمفتاح الجهاز الآخر");
        check(db2.cashBoxes().size() == 2 && db2.cashBalances(1).equals(db.cashBalances(1)) && db2.cashBalances(2).equals(db.cashBalances(2)), "الصناديق وأرصدتها " + db2.cashBoxes().size());
        check(db2.cashCounts(1).size() == db.cashCounts(1).size() && db2.cashCounts(1).get(0).note.equals("جرد الخميس"), "9.0.1: سجل الجرد بملاحظاته");
        check(db2.clientBalances().equals(db.clientBalances()) && db2.client(kid).name.equals("أبو خالد") && db2.clientMoves(kid).size() == db.clientMoves(kid).size(),
                "9.0.1: حسابات العملاء وأسماؤهم المشفّرة وحركاتهم");
        healthy(db2, "الجهاز الآخر");
        db2.close();

        System.out.println("== 11) المجانية: حذف مكتب له حركات يعيد مكانه");
        Object[] dv3 = DeskCoreTest.device("free");
        Context ctx3 = (Context) dv3[0];
        Db db3 = (Db) dv3[1];
        List<Long> ids = new ArrayList<>();
        License.current = Entitlements.of(null, BuildInfo.BUILD_DATE);
        final int F = Entitlements.FREE_OFFICES;   // 12.6: 15 (كانت 9)
        for (int k = 1; k <= F; k++) {
            long id = db3.addOffice("مكتب " + k, "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
            ids.add(id);
            Db.TxInput t = new Db.TxInput();
            t.office = id;
            t.type = k % 2 == 0 ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
            t.amount = 1000L * k;
            t.currency = "USD";
            t.when = System.currentTimeMillis() - 5000;
            db3.addTx(t, null);
        }
        check(throwsMsg(() -> db3.addOffice("العاشر", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>()), String.valueOf(F)), "المكتب بعد الحد يُرفض في المجانية");
        db3.deleteOffice(ids.get(0), true);
        long tenth = db3.addOffice("العاشر", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        check(tenth > 0 && db3.offices().size() == F, "بعد حذف مكتب له حركات يعود مكانه فارغًا ويُضاف مكتب جديد");
        check(Entitlements.free("").maxTxPerOffice() == 1500, "حد الحركات 1500 لكل مكتب (12.6)");

        System.out.println("== 12) المجانية: استعادة نسخة فيها 12 مكتبًا باختيار 9");
        Object[] dv4 = DeskCoreTest.device("many");
        Context ctx4 = (Context) dv4[0];
        Db db4 = (Db) dv4[1];
        License.current = Entitlements.of(null, BuildInfo.BUILD_DATE);
        Map<String, Long> wantBal = new TreeMap<>();
        // لبناء 12 مكتبًا نتجاوز الحد بإضافة المكاتب على دفعات بنسخة احتياطية: نبنيها كمرخّص عبر دمج ملفات A–D مرارًا غير ممكن،
        // لذلك نكتب 12 مكتبًا مباشرة في القاعدة (محاكاة نسخة من الكاملة)، ثم نستعيدها في جهاز مجاني.
        net.zetetic.database.sqlcipher.SQLiteDatabase w = db4.getWritableDatabase();
        final int N = F + 3;   // نسخة فيها مكاتب أكثر من حد المجانية
        for (int k = 1; k <= N; k++) {
            w.execSQL("INSERT INTO offices(name,active,pinned) VALUES(?,1,0)", new Object[]{Crypto.enc("ف" + k)});
            long oid = k;
            w.execSQL("INSERT INTO office_currencies(office_id,code) VALUES(?,?)", new Object[]{oid, "USD"});
        }
        for (int k = 1; k <= N; k++) {
            Db.TxInput t = new Db.TxInput();
            t.office = k;
            t.type = Model.TYPE_DEBIT;
            t.amount = 100L * k;
            t.currency = "USD";
            t.when = System.currentTimeMillis() - 5000;
            db4.addTx(t, null);
            wantBal.put("ف" + k, 100L * k);
        }
        CashBox.Input cx = in(CashBox.OUT, "USD", 300);
        db4.cashSetup("", Arrays.asList("USD"), Collections.singletonMap("USD", 100000L), false, true);
        cx.officeId = 3L;
        db4.addCashMove(cx);   // مكتب «ف3» (لن يُختار): يُفك ربط حركة الصندوق ويبقى النقد
        wantBal.put("ف3", 100L * 3 + 300);
        BackupManager.Created c12 = BackupManager.createLocal(ctx4, db4, "اثنا-عشر".toCharArray(), "9.0", m -> {});
        Object[] dv5 = DeskCoreTest.device("pick");
        Context ctx5 = (Context) dv5[0];
        Db db5 = (Db) dv5[1];
        BackupManager.Prepared prep = BackupManager.prepareRestore(ctx5, c12.file, "اثنا-عشر".toCharArray(), m -> {});
        List<BackupFormat.OfficeInfo> infos = BackupFormat.officeSummaries(prep.parsed);
        check(infos.size() == N && infos.get(4).txCount == 1 && infos.get(4).balances.get("USD") == 500, "ملخص مكاتب النسخة قبل الاستعادة: 12 مكتبًا بحركاتها وأرصدتها");
        check(throwsMsg(io(() -> BackupManager.applyRestore(ctx5, db5, prep, m -> {})), "تتسع"), "استعادة الـ12 كلها تُرفض في المجانية");
        BackupManager.Prepared prep2 = BackupManager.prepareRestore(ctx5, c12.file, "اثنا-عشر".toCharArray(), m -> {});
        Set<Long> keep = new TreeSet<>();
        for (long k = 1; keep.size() < F; k++) if (k != 3) keep.add(k);
        prep2.keep = keep;
        BackupManager.applyRestore(ctx5, db5, prep2, m -> {});
        Map<String, Long> got = new TreeMap<>();
        for (Model.Office o : db5.offices()) got.put(o.name, b(db5.balances(o.id), "USD"));
        Map<String, Long> want9 = new TreeMap<>();
        for (long k : keep) want9.put("ف" + k, 100L * k);
        check(got.equals(want9), "استُعيدت المكاتب التسعة المختارة بأرصدتها كما في النسخة " + got);
        check(db5.cashBalances().get("USD") == 100000 - 300, "الصندوق كامل (حركة المكتب غير المختار فُكّ ربطها وبقي نقدها)");
        healthy(db5, "بعد الاستعادة الانتقائية");

        System.out.println("== 13) المجانية: إضافة ملف نقل يتجاوز الحد باختيار المكاتب الجديدة");
        Object[] dv6 = DeskCoreTest.device("merge");
        Context ctx6 = (Context) dv6[0];
        Db db6 = (Db) dv6[1];
        for (int k = 1; k <= F - 1; k++) db6.addOffice("قائم " + k, "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        String pw = expect.getJSONObject("A").getString("password");
        BackupManager.Prepared pa = BackupManager.prepareRestore(ctx6, new File(data, "A.sba"), pw.toCharArray(), m -> {});
        MergePlan plan = MergePlan.build(pa.parsed.tables, pa.parsed.manifest, db6.mergeExisting());
        check(plan.ok() && plan.newOffices == 3, "ملف A: 3 مكاتب جديدة");
        check(throwsMsg(() -> db6.mergeMigration(plan), "تتسع"), "إضافتها كلها تتجاوز 9 فتُرفض");
        long firstNew = plan.newOfficeList().get(0).fileId;
        String firstName = plan.newOfficeList().get(0).name;
        Map<String, Long> fileExp = new TreeMap<>();
        for (Map.Entry<String, Long> e : plan.expected.entrySet()) if (e.getKey().startsWith(firstNew + "|")) fileExp.put(e.getKey().split("\\|")[1], e.getValue());
        plan.keepNew(new HashSet<>(Collections.singletonList(firstNew)));
        check(plan.newOffices == 1 && plan.droppedOffices == 2, "بقي مكتب جديد واحد باختيار المستخدم");
        db6.mergeMigration(plan);
        long nid = -1;
        for (Model.Office o : db6.offices()) if (o.name.equals(firstName)) nid = o.id;
        Map<String, Long> nb = new TreeMap<>();
        for (Map.Entry<String, Long> e : db6.balances(nid).entrySet()) if (e.getValue() != 0) nb.put(e.getKey(), e.getValue());
        check(db6.offices().size() == F && nb.equals(fileExp), "أُضيف المكتب المختار بأرصدته كما في الملف " + nb + " والمجموع 9 مكاتب");
        healthy(db6, "بعد الإضافة الانتقائية");

        System.out.println("== 14) عدّاد واتساب اليومي");
        boolean okAll = true;
        for (int k = 0; k < 20; k++) okAll &= db6.waAllowed("رسالة رقم " + k) && db6.waUse("رسالة رقم " + k);
        check(okAll, "20 رسالة مختلفة مسموحة اليوم");
        check(!db6.waAllowed("رسالة 21") && !db6.waUse("رسالة 21"), "الرسالة 21 تُرفض (الإدخال اليدوي)");
        check(db6.waAllowed("رسالة رقم 3") && db6.waUse("  رسالة   رقم 3 "), "الرسالة المحتسبة سابقًا اليوم تُعاد بلا احتساب جديد");
        check(db6.waUsage()[0] == 20 && db6.waUsage()[1] == 20, "الاستهلاك 20 / 20");

        System.out.println("== 15) ترتيب المكاتب");
        Map<Long, Map<String, Long>> ob = db.officeBalances();
        OfficeOrder.Inputs oi = db.officeOrderInputs(ob);
        List<Model.Office> list = db.offices();
        OfficeOrder.sort(list, OfficeOrder.BIGGEST, oi);
        check(!list.isEmpty(), "الترتيب بالأكبر رصيدًا يعمل على " + list.size() + " مكاتب");
        db.touchOffice(nour);
        oi = db.officeOrderInputs(ob);
        OfficeOrder.sort(list, OfficeOrder.LAST_OPENED, oi);
        check(list.get(0).id == nour, "«آخر ما فتحته»: النور أولًا بعد فتحه");

        System.out.println("== 16) 10.0: ترقية صناديق 9.x (في الإعدادات) إلى جدولها");
        {
            Object[] dvm = DeskCoreTest.device("migboxes");
            Context cm = (Context) dvm[0];
            Db dm = (Db) dvm[1];
            net.zetetic.database.sqlcipher.SQLiteDatabase wm = dm.getWritableDatabase();
            wm.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('cash_on','1')");
            wm.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('cash_name','صندوق المحل')");
            wm.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('cash_currencies','USD,EUR')");
            wm.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('cash_strict','1')");
            wm.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('cash_boxes',?)", new Object[]{"[{\"id\":2,\"name\":\"البيت\",\"cur\":\"USD\",\"active\":false}]"});
            wm.execSQL("INSERT INTO cash_moves(uuid,box_id,kind,currency,amount_minor,dir,category,status,created_at) VALUES('m1',1,'OPENING','USD',5000,1,'x','ACTIVE',1)");
            wm.execSQL("INSERT INTO cash_moves(uuid,box_id,kind,currency,amount_minor,dir,category,status,created_at) VALUES('m2',2,'OPENING','USD',700,1,'x','ACTIVE',1)");
            wm.execSQL("DELETE FROM cash_boxes");
            dm.close();
            Db dm2 = new Db(cm);
            List<CashBox.Config> bx = dm2.cashBoxes();
            check(bx.size() == 2 && bx.get(0).name.equals("صندوق المحل") && bx.get(0).strict && bx.get(0).currencies.equals(Arrays.asList("USD", "EUR"))
                    && bx.get(1).id == 2 && !bx.get(1).active && b(dm2.cashBalances(1), "USD") == 5000 && b(dm2.cashBalances(2), "USD") == 700,
                    "الصندوقان بأرقامهما وأسمائهما وربطهما وحالتهما، وأرصدتهما كما هي");
            dm2.close();
        }

        db.close();
        System.out.println("\n" + passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }
}
