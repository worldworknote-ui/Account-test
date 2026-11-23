import android.content.Context;
import com.smart.hawala.*;
import java.util.*;

/**
 * 10.5: إصلاحات المال والصلاحيات على Db الحقيقي (sqlite-jdbc)، وكل رقم يُقارن بقيمة محسوبة باليد:
 * AUDIT-017 (تصحيح حركة مرتبطة بالصندوق)، 028 (الجرد مع المعلّق)، 029 (حذف مكتب)، 018-أ (اعتماد التبديل)،
 * 023 (صلاحية المستخدمين وحساب المدير)، 027 (تغيير الرمز الشخصي)، 003 (إذن المزامنة خاص بخيطها).
 */
public class FixesTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static long b(Map<String, Long> m, String c) {
        Long v = m.get(c);
        return v == null ? 0 : v;
    }

    interface Io { void run() throws Exception; }

    static boolean throwsMsg(Io r, String part) {
        try {
            r.run();
            System.out.println("      (لم يُرفض)");
            return false;
        } catch (Exception e) {
            StringBuilder all = new StringBuilder();
            for (Throwable t = e; t != null; t = t.getCause()) all.append(t.getMessage()).append(" | ");
            boolean ok = part == null || all.toString().contains(part);
            if (!ok) System.out.println("      رسالة: " + all);
            return ok;
        }
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

    static CashBox.Input cashIn(long office, String cur, long amount, boolean in) {
        CashBox.Input i = new CashBox.Input();
        i.kind = in ? CashBox.IN : CashBox.OUT;
        i.currency = cur;
        i.amount = amount;
        i.officeId = office;
        i.party = "زبون";
        i.when = System.currentTimeMillis() - 5000;
        return i;
    }

    static Db.TxInput txIn(long office, String type, long amount, String cur) {
        Db.TxInput t = new Db.TxInput();
        t.office = office;
        t.type = type;
        t.amount = amount;
        t.currency = cur;
        t.person = "زبون";
        t.when = System.currentTimeMillis() - 4000;
        return t;
    }

    static CashBox.Move moveOf(Db db, long txId) {
        for (CashBox.Move m : db.cashMoves()) if (m.txId != null && m.txId == txId) return m;
        return null;
    }

    static void as(Db db, long uid) { SmartApp.currentUser = db.user(uid); Db.currentUserName = SmartApp.currentUser.name; }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        Object[] dv = DeskCoreTest.device("fixes");
        Db db = (Db) dv[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        List<String> ue = Arrays.asList("USD", "EUR");
        long A = db.addOffice("البلسم", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long B = db.addOffice("النور", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long C = db.addOffice("الأمل", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        Map<String, Long> op = new HashMap<>();
        op.put("USD", 1000000L);
        op.put("EUR", 100000L);
        db.cashSetup("الصندوق", ue, op, false, true);
        long admin = db.addUser("المدير", Auth.ADMIN, null, "123456");
        as(db, admin);
        long clerk = db.addUser("سامر", Auth.CUSTOM, "VIEW,TX_ADD,TX_EDIT,CASH,REVIEW", "1111");
        long acct = db.addUser("رنا", Auth.CUSTOM, "VIEW,TX_ADD,TX_EDIT,TX_CANCEL,APPROVE,CASH", "2222");
        long noCash = db.addUser("خالد", Auth.CUSTOM, "VIEW,APPROVE", "3333");
        long mgr = db.addUser("مدير الموظفين", Auth.CUSTOM, "VIEW,USERS", "4444");

        System.out.println("== AUDIT-017: تصحيح واعتماد حركة مسجّلة من الصندوق");
        as(db, clerk);
        db.addCashMove(cashIn(A, "USD", 100000, true));    // استلام 1,000 لحساب البلسم (معلّق)
        Model.Tx p1 = db.pendingTx().get(0);
        check(p1.isPending() && b(db.balances(A), "USD") == 0, "حركة الموظف معلّقة ولا أثر لها في رصيد المكتب");
        check(b(db.cashBalances(), "USD") == 1000000 && b(db.cashPending(1), "USD") == 100000, "النقد المعلّق ظاهر منفصلًا ولا يدخل الرصيد: " + db.cashPending(1));
        as(db, noCash);
        Db.TxInput fix = txIn(B, Model.TYPE_CREDIT, 90000, "USD");
        check(throwsMsg(() -> db.approveEdited(p1.id, fix, null, null), "الصندوق"), "معتمد بلا صلاحية الصندوق لا يصحح حركة صندوق (يعتمد أو يرفض فقط)");
        check(db.tx(p1.id).isPending() && b(db.balances(B), "USD") == 0, "ولم يتغير شيء بعد الرفض (العملية ذرية)");
        as(db, acct);
        db.approveEdited(p1.id, fix, null, null);
        CashBox.Move m1 = moveOf(db, p1.id);
        check(b(db.balances(A), "USD") == 0 && b(db.balances(B), "USD") == -90000, "المكتب صُحّح: البلسم 0، النور علينا 900");
        check(m1 != null && m1.amount == 90000 && m1.officeId == B && m1.dir == 1 && m1.counts(), "حركة الصندوق صُحّحت معها: 900 لحساب النور وداخلة ومحسوبة");
        check(b(db.cashBalances(), "USD") == 1000000 + 90000, "النقد = 10,000 + 900 (لا 1,000 القديمة)");
        healthy(db, "بعد تصحيح المبلغ والمكتب");
        // قلب النوع: استلام صار تسليمًا
        as(db, clerk);
        db.addCashMove(cashIn(A, "USD", 50000, true));
        Model.Tx p2 = db.pendingTx().get(0);
        as(db, acct);
        db.approveEdited(p2.id, txIn(A, Model.TYPE_DEBIT, 50000, "USD"), null, null);
        CashBox.Move m2 = moveOf(db, p2.id);
        check(m2.dir == -1 && CashBox.OUT.equals(m2.kind) && b(db.balances(A), "USD") == 50000, "قلب «علينا» إلى «لنا»: النقد صار خارجًا والمكتب لنا 500");
        check(b(db.cashBalances(), "USD") == 1000000 + 90000 - 50000, "النقد = 10,900 − 500");
        // عملة غير مفعّلة في الصندوق: يُرفض ولا يتغير شيء
        as(db, admin);
        db.saveCurrency("TRY", "ليرة تركية", 2, true);
        db.updateOffice(A, "البلسم", "", "", "", "", "", true, null, null, Arrays.asList("USD", "EUR", "TRY"), new HashMap<>());
        as(db, clerk);
        db.addCashMove(cashIn(A, "USD", 20000, true));
        Model.Tx p3 = db.pendingTx().get(0);
        as(db, acct);
        check(throwsMsg(() -> db.approveEdited(p3.id, txIn(A, Model.TYPE_CREDIT, 20000, "TRY"), null, null), "غير مفعّلة"), "تصحيح إلى عملة ليست في الصندوق يُرفض برسالة تشرح البديل");
        check(db.tx(p3.id).isPending() && "USD".equals(moveOf(db, p3.id).currency), "ولم يُكتب شيء (الحركة معلّقة وحركة الصندوق كما هي)");
        db.approveTx(p3.id);
        // التعديل العادي لحركة نافذة مسجّلة من الصندوق
        long nid = db.editTx(p3.id, txIn(A, Model.TYPE_CREDIT, 25000, "EUR"), null, null, "عملة خاطئة");
        CashBox.Move m3 = moveOf(db, nid);
        check(m3 != null && m3.amount == 25000 && "EUR".equals(m3.currency) && moveOf(db, p3.id) == null, "التعديل ينقل حركة الصندوق إلى الحركة الجديدة بالمبلغ والعملة الجديدين");
        check(b(db.cashBalances(), "EUR") == 100000 + 25000 && b(db.cashBalances(), "USD") == 1000000 + 90000 - 50000, "النقد: EUR +250 وUSD بلا أثر للحركة القديمة");
        check(b(db.balances(A), "EUR") == -25000 && b(db.balances(A), "USD") == 50000, "المكتب: علينا 250 EUR ولنا 500 USD");
        healthy(db, "بعد التعديل");

        System.out.println("== AUDIT-028: الجرد قبل اعتماد حركة صندوق معلّقة");
        as(db, clerk);
        db.addCashMove(cashIn(A, "USD", 30000, true));
        Model.Tx p4 = db.pendingTx().get(0);
        Map<String, Long> expected = db.cashExpected(1);
        check(b(expected, "USD") == b(db.cashBalances(), "USD") + 30000, "المتوقع في الصندوق = الرصيد + 300 معلّقة");
        Map<String, Long> counted = new HashMap<>();
        counted.put("USD", b(expected, "USD"));
        CashBox.Count cnt = db.cashCount(1, counted, "جرد", null);
        check(cnt.adjusted == 0 && cnt.lines.get(0).pending == 30000, "المعدود يطابق المتوقع: لا تسوية، والمعلّق محفوظ في سجل الجرد");
        as(db, acct);
        db.approveTx(p4.id);
        check(b(db.cashBalances(), "USD") == b(counted, "USD"), "بعد الاعتماد الصندوق = المعدود (لم يُحسب مرتين)");

        System.out.println("== AUDIT-029: حذف مكتب له حركات صندوق معلّقة ومرفوضة");
        as(db, clerk);
        db.addCashMove(cashIn(C, "USD", 40000, true));
        db.addCashMove(cashIn(C, "USD", 15000, true));
        List<Model.Tx> pc = db.pendingTx();
        as(db, acct);
        db.rejectTx(pc.get(0).id, "خطأ");
        long before = b(db.cashBalances(), "USD");
        as(db, admin);
        db.deleteOffice(C, true);
        check(b(db.cashBalances(), "USD") == before, "حذف المكتب لا يجعل المعلّقة والمرفوضة محسوبة: " + before + " = " + b(db.cashBalances(), "USD"));
        int cancelled = 0;
        int byReject = 0;
        for (CashBox.Move m : db.cashMoves()) {
            if (m.cancelled() && m.cancelReason.contains("حُذف المكتب")) cancelled++;
            if (m.cancelled() && m.cancelReason.contains("رُفضت حركة المكتب")) byReject++;
        }
        check(cancelled == 1 && byReject == 1, "وأُلغيت حركتا الصندوق بسبب واضح (المرفوضة عند رفضها، والمعلّقة عند حذف المكتب)");
        healthy(db, "بعد حذف المكتب");

        System.out.println("== AUDIT-018 (أ): تبديل العملة يخضع للاعتماد");
        as(db, clerk);
        long usdA = b(db.balances(A), "USD"), eurA = b(db.balances(A), "EUR");
        String[] legs = db.addFx(A, "USD", 11200, "EUR", 10000, "1.12", "تبديل", System.currentTimeMillis() - 1000);
        check(db.tx(Long.parseLong(legs[0])).isPending() && db.tx(Long.parseLong(legs[1])).isPending(), "ساقا تبديل الموظف معلّقتان معًا");
        check(b(db.balances(A), "USD") == usdA && b(db.balances(A), "EUR") == eurA, "ولا أثر لهما في الأرصدة");
        as(db, clerk);
        check(throwsMsg(() -> db.approveTx(Long.parseLong(legs[0])), null), "صاحب العملية لا يعتمدها");
        as(db, acct);
        check(throwsMsg(() -> db.approveEdited(Long.parseLong(legs[0]), txIn(A, Model.TYPE_DEBIT, 1, "USD"), null, null), "مترابطة"), "لا تُصحَّح ساق وحدها");
        db.approveTx(Long.parseLong(legs[1]));
        check(db.tx(Long.parseLong(legs[0])).isActive() && db.tx(Long.parseLong(legs[1])).isActive(), "اعتماد ساق يعتمد الأخرى معها");
        check(b(db.balances(A), "USD") == usdA + 11200 && b(db.balances(A), "EUR") == eurA - 10000, "الأثر: لنا 112 USD وعلينا 100 EUR");
        as(db, clerk);
        String[] legs2 = db.addFx(A, "USD", 5000, "EUR", 4000, "1.25", "", System.currentTimeMillis() - 1000);
        as(db, acct);
        db.rejectTx(Long.parseLong(legs2[0]), "سعر خاطئ");
        check(db.tx(Long.parseLong(legs2[1])).isRejected(), "رفض ساق يرفض الأخرى");
        healthy(db, "بعد التبديل");

        System.out.println("== AUDIT-023: صلاحية «المستخدمون» لا تمس حساب المدير");
        as(db, mgr);
        check(throwsMsg(() -> db.addUser("دخيل", Auth.ADMIN, null, "654321"), "للمدير"), "لا ينشئ مديرًا");
        check(throwsMsg(() -> db.setPin(admin, "999999"), "رمز المدير"), "لا يغيّر رمز المدير");
        check(db.verifyPin(admin, "123456"), "ورمز المدير كما هو");
        check(throwsMsg(() -> db.updateUser(admin, "المدير", Auth.CUSTOM, "VIEW", true), "المدير"), "لا يعدّل حساب المدير");
        check(throwsMsg(() -> db.updateUser(mgr, "مدير الموظفين", Auth.ADMIN, null, true), "للمدير"), "لا يرقّي نفسه مديرًا");
        check(throwsMsg(() -> db.updateUser(mgr, "مدير الموظفين", Auth.CUSTOM, "VIEW,USERS,APPROVE", true), "بنفسك"), "لا يعدّل صلاحياته بنفسه");
        check(throwsMsg(() -> db.updateUser(clerk, "سامر", Auth.CUSTOM, "VIEW,TX_ADD,TX_EDIT,CASH,REVIEW,APPROVE", true), "لا تملكها"), "لا يمنح صلاحية لا يملكها (الاعتماد)");
        check(throwsMsg(() -> db.updateUser(clerk, "سامر", Auth.CUSTOM, "VIEW,TX_ADD,TX_EDIT,CASH", true), "اعتماد"), "لا يرفع قيد «حركاته تحتاج اعتمادًا» دون صلاحية الاعتماد");
        db.updateUser(clerk, "سامر الحلبي", Auth.CUSTOM, "VIEW,TX_ADD,TX_EDIT,CASH,REVIEW", true);
        check("سامر الحلبي".equals(db.user(clerk).name) && Auth.needsReview(db.user(clerk)), "ويعدّل بيانات موظف ضمن ما يملك");
        db.updateUser(clerk, "سامر", Auth.CUSTOM, "VIEW,TX_ADD,TX_EDIT,CASH,REVIEW", true);
        // 10.11 (AUDIT-001): «سامر» يملك صلاحيات لا يملكها مدير الموظفين (التسجيل والتعديل والصندوق)؛ لو غيّر رمزه لدخل بها
        check(throwsMsg(() -> db.setPin(clerk, "5555"), "لا تملكها") && db.verifyPin(clerk, "1111"), "لا يغيّر رمز موظف فيه صلاحية لا يملكها (10.11)");
        as(db, admin);
        db.setPin(clerk, "5555");
        as(db, mgr);
        check(db.verifyPin(clerk, "5555"), "والمدير يغيّره");
        check(throwsMsg(() -> db.disableUsers(), "للمدير"), "لا يوقف نظام المستخدمين");
        check(throwsMsg(() -> db.newRecoveryCode(), "المدير"), "لا ينشئ رمز استرداد المدير");

        System.out.println("== AUDIT-027: الموظف يغيّر رمزه بنفسه");
        as(db, clerk);
        check(throwsMsg(() -> db.changeOwnPin("0000", "7777"), "غير صحيح"), "برمز حالي خاطئ يُرفض");
        db.changeOwnPin("5555", "7777");
        check(db.verifyPin(clerk, "7777") && !db.verifyPin(clerk, "5555"), "برمزه الحالي يتغير دون صلاحية «المستخدمون»");
        check(throwsMsg(() -> db.setPin(acct, "8888"), "صلاحية"), "ولا يغيّر رمز غيره");

        System.out.println("== AUDIT-003: إذن المزامنة خاص بخيطها");
        java.lang.reflect.Method set = Db.class.getDeclaredMethod("setSystem", boolean.class);
        set.setAccessible(true);
        final boolean[] other = {false};
        Thread t = new Thread(() -> {
            try {
                set.invoke(db, true);
                Thread.sleep(400);
                set.invoke(db, false);
            } catch (Exception e) {
                other[0] = true;
            }
        });
        t.start();
        Thread.sleep(150);
        as(db, clerk);
        check(throwsMsg(() -> db.addUser("س", Auth.CUSTOM, "VIEW", "1212"), "صلاحية"), "أثناء مزامنة في خيط آخر تبقى صلاحيات المستخدم مفحوصة هنا");
        t.join();
        check(!other[0], "خيط المزامنة عمل بإذنه دون خطأ");

        System.out.println("== §9 (صُحّح في 10.7): الحركة بين مكتبين — طالب الحركة «لنا» عليه والمنفّذ «علينا» له");
        as(db, acct);
        long aU = b(db.balances(A), "USD"), bU = b(db.balances(B), "USD"), cashU = b(db.cashBalances(), "USD");
        Db.TransferInput tr = new Db.TransferInput();
        tr.from = A;
        tr.to = B;
        tr.currency = "USD";
        tr.amount = 50000;
        tr.fromFeeType = Model.FEE_US;
        tr.fromFee = 700;
        tr.toFeeType = Model.FEE_THEM;
        tr.toFee = 1000;
        tr.person = "مستفيد";
        tr.when = System.currentTimeMillis() - 2000;
        List<Db.Preview> pv = db.preview(Arrays.asList(db.transferLegs(tr)), null);
        long[] ids = db.addTransfer(tr);
        check(b(db.balances(A), "USD") == aU + 50000 + 700 && b(db.balances(B), "USD") == bU - 50000 - 1000,
                "طالب الحركة مدين لنا بـ 500 وأجرتنا 7، والمنفّذ دائن لنا بـ 500 وأجرته 10 (فرق الأجرة علينا 3)");
        Model.Tx req = db.tx(ids[0]), exe = db.tx(ids[1]);
        check(req.officeId == A && Model.TYPE_DEBIT.equals(req.type) && exe.officeId == B && Model.TYPE_CREDIT.equals(exe.type)
                && req.description.contains("نفّذها") && exe.description.contains("لطلب"), "الساق الأولى لطالب الحركة «لنا»، والثانية للمنفّذ «علينا»، بوصف واضح");
        check(pv.size() == 2 && pv.get(0).after.get("USD") == b(db.balances(A), "USD") && pv.get(1).after.get("USD") == b(db.balances(B), "USD"),
                "المعاينة قبل الحفظ = الرصيد الفعلي بعده للمكتبين");
        check(b(db.cashBalances(), "USD") == cashU, "التحويل لا يحرّك النقد في الصندوق");
        Model.Tx l0 = db.tx(ids[0]), l1 = db.tx(ids[1]);
        check(l0.isTransfer() && !l0.isFx() && l0.pairUuid.equals(l1.pairUuid) && db.legs(l0).size() == 2, "ساقان برقم عملية واحد، وليستا تبديل عملة");
        check(throwsMsg(() -> db.editTx(ids[1], txIn(B, Model.TYPE_DEBIT, 1, "USD"), null, null, "x"), "تحويل بين مكتبين"), "لا تُعدَّل ساق وحدها");
        Db.TransferInput same = new Db.TransferInput();
        same.from = A;
        same.to = A;
        same.currency = "USD";
        same.amount = 100;
        same.when = System.currentTimeMillis();
        check(throwsMsg(() -> db.addTransfer(same), "مختلفين"), "المكتب نفسه في الطرفين مرفوض");
        db.cancelTx(ids[1], "خطأ");
        check(!db.tx(ids[0]).isActive() && !db.tx(ids[1]).isActive() && b(db.balances(A), "USD") == aU && b(db.balances(B), "USD") == bU, "إلغاء ساق يلغي العملية كلها وتعود الأرصدة");
        as(db, clerk);
        long[] pids = db.addTransfer(tr);
        check(db.tx(pids[0]).isPending() && db.tx(pids[1]).isPending() && b(db.balances(A), "USD") == aU, "تحويل الموظف معلّق بساقيه ولا أثر له قبل الاعتماد");
        TxConfirm.View cv = TxConfirm.build(db, Arrays.asList(db.transferLegs(tr)), null, null);
        boolean expected2 = false, officeRows = false;
        for (TxConfirm.Row r : cv.rows) {
            if (r.label.startsWith("الرصيد المتوقع بعد اعتماد الحركة")) expected2 = true;
            if (r.kind == TxConfirm.OFFICE && r.label.equals("المكتب")) officeRows = true;
        }
        check(cv.pending && expected2 && officeRows, "التأكيد: «الرصيد المتوقع بعد اعتماد الحركة» واسم المكتب مع كلمة «المكتب»");
        as(db, acct);
        db.approveTx(pids[0]);
        check(db.tx(pids[1]).isActive() && b(db.balances(B), "USD") == bU - 51000 && b(db.balances(A), "USD") == aU + 50700, "اعتماد ساق يعتمد العملية كلها");
        healthy(db, "بعد التحويلات");
        // 10.7: حركة سُجّلت بالاتجاه القديم المعكوس (10.5–10.6) يكشفها فحص الصحة، ولا يعدّلها شيء تلقائيًا
        db.getWritableDatabase().execSQL("UPDATE tx SET type=CASE WHEN type=? THEN ? ELSE ? END WHERE pair_uuid=?",
                new Object[]{Model.TYPE_DEBIT, Model.TYPE_CREDIT, Model.TYPE_DEBIT, db.tx(pids[0]).pairUuid});
        boolean flagged = false;
        for (Db.Check hc : db.healthCheck(true)) if (hc.title.startsWith("الحركات بين مكتبين باتجاهها الصحيح") && !hc.ok && hc.detail.contains("" + pids[0])) flagged = true;
        check(flagged, "فحص الصحة يكشف حركة بين مكتبين سُجّلت بالاتجاه القديم");
        db.getWritableDatabase().execSQL("UPDATE tx SET type=CASE WHEN type=? THEN ? ELSE ? END WHERE pair_uuid=?",
                new Object[]{Model.TYPE_DEBIT, Model.TYPE_CREDIT, Model.TYPE_DEBIT, db.tx(pids[0]).pairUuid});
        healthy(db, "بعد إعادة الاتجاه");

        System.out.println("== §11: معاينة الرصيد بقواعد الترحيل نفسها");
        long t1 = db.addTx(txIn(A, Model.TYPE_DEBIT, 70000, "USD"), null);
        Db.TxInput moved = txIn(B, Model.TYPE_CREDIT, 30000, "EUR");
        moved.feeType = Model.FEE_US;
        moved.fee = 500;
        moved.feeCurrency = "USD";
        List<Db.Preview> ep = db.preview(moved, t1);
        Map<Long, Db.Preview> byOffice = new HashMap<>();
        for (Db.Preview p : ep) byOffice.put(p.office, p);
        db.editTx(t1, moved, null, null, "مكتب خاطئ");
        check(byOffice.get(A).after.get("USD") == b(db.balances(A), "USD") && byOffice.get(B).after.get("EUR") == b(db.balances(B), "EUR")
                        && byOffice.get(B).after.get("USD") == b(db.balances(B), "USD"),
                "تعديل نقل الحركة إلى مكتب وعملة أخرى وأجرة بعملة ثالثة: المعاينة = الفعلي للمكتبين والعملات كلها");
        TxConfirm.View ev = TxConfirm.build(db, txIn(A, Model.TYPE_CREDIT, 100, "USD"), null);
        check(TxConfirm.changed(db, ev, Collections.singletonList(txIn(A, Model.TYPE_CREDIT, 100, "USD")), null) == false, "البصمة ثابتة ما لم يتغير الرصيد");
        db.addTx(txIn(A, Model.TYPE_DEBIT, 1, "USD"), null);
        check(TxConfirm.changed(db, ev, Collections.singletonList(txIn(A, Model.TYPE_CREDIT, 100, "USD")), null), "حركة جديدة أثناء التأكيد تُكتشف قبل الحفظ");

        System.out.println("== §8: حساب تبديل العملة");
        FxDeal fd = new FxDeal();
        fd.sell = "USD";
        fd.buy = "EUR";
        fd.sellAmount = 100000;
        fd.rate = FxDeal.parseRate("1.12");
        check(fd.buyAmount() == 112000 && "0.892857".equals(FxDeal.invert(fd.rate)), "1,000 USD × 1.12 = 1,120 EUR، وعكس 1.12 = 0.892857");
        fd.rate = FxDeal.parseRate("0.9");
        fd.buyPerSell = false;
        check(fd.buyAmount() == 111111, "«1 EUR = 0.9 USD» سعر جديد: 1,000 ÷ 0.9 = 1,111.11 EUR (ليس 1/1.12)");
        FxDeal rv = fd.reversed();
        check("EUR".equals(rv.sell) && rv.sellAmount == 111111 && rv.buyAmount() == 100000, "قلب العملية يعيد المبلغ الأصلي (1,111.11 EUR ← 1,000 USD)");
        check(FxDeal.parseRate("1.120").compareTo(new java.math.BigDecimal("1.12")) == 0 && FxDeal.parseRate("١٫١٢").compareTo(new java.math.BigDecimal("1.12")) == 0,
                "السعر 1.120 = 1.12 (النقطة عشرية لا فاصل آلاف) والأرقام العربية مقبولة");
        check(fd.meaning().contains("1 EUR = 0.9 USD"), "المعنى مكتوب: " + fd.meaning());

        System.out.println("== §7: المحاسبون — من أدخل ومن اعتمد ومن تحقق، والمكاتب، و«بانتظار تحققي»");
        as(db, admin);
        long v1 = db.addUser("وسيم", Auth.CUSTOM, "VIEW,VERIFY", "4545");
        long v2 = db.addUser("هالة", Auth.CUSTOM, "VIEW,VERIFY,TX_ADD", "4646");
        as(db, acct);
        long vt = db.addTx(txIn(B, Model.TYPE_DEBIT, 12300, "USD"), null);
        Model.Tx vtx = db.tx(vt);
        check(vtx.userId != null && vtx.userId == acct && db.txPeople(vtx).get(0).startsWith("أدخلها: رنا"), "الحركة تحمل رقم من أدخلها الثابت: " + db.txPeople(vtx).get(0));
        as(db, v2);
        long own = db.addTx(txIn(B, Model.TYPE_CREDIT, 500, "USD"), null);
        check(throwsMsg(() -> db.verifyTx(own), "أدخلتها أنت"), "التحقق من حركته هو لا يُحتسب (يُرفض حتى مع صلاحية التحقق)");
        as(db, mgr);
        check(throwsMsg(() -> db.verifyTx(vt), "صلاحية"), "بلا صلاحية التحقق يُرفض");
        as(db, v1);
        db.verifyTx(vt);
        db.verifyTx(vt);
        check(db.checks(vt).size() == 1 && db.checks(vt).get(0).valid() && db.verified(db.tx(vt)), "تحقق وسيم سُجّل مرة واحدة (التكرار لا يضاعفه)");
        long jBefore;
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM journal_entries", null)) {
            c.moveToFirst();
            jBefore = c.getLong(0);
        }
        as(db, v2);
        db.verifyTx(vt);
        long jAfter;
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM journal_entries", null)) {
            c.moveToFirst();
            jAfter = c.getLong(0);
        }
        check(jAfter == jBefore && db.checks(vt).size() == 2, "«تم التحقق» لا يُنشئ أي قيد، وتحقق مستخدمين اثنين صفّان");
        as(db, clerk);
        long pend = db.addTx(txIn(B, Model.TYPE_CREDIT, 4000, "USD"), null);
        as(db, v1);
        check(throwsMsg(() -> db.verifyTx(pend), "بانتظار الاعتماد"), "المعلّقة لا يُتحقق منها قبل اعتمادها");
        as(db, acct);
        db.approveTx(pend);
        Model.Tx ap = db.tx(pend);
        check(ap.approvedBy != null && ap.approvedBy == acct && ap.approvedAt > 0 && db.txPeople(ap).get(1).startsWith("اعتمدها: رنا"), "من اعتمدها ومتى: " + db.txPeople(ap).get(1));
        // المكاتب
        as(db, mgr);
        db.setUserOffices(v1, new HashSet<>(Collections.singletonList(B)));
        check(db.userOffices(v1).equals(new HashSet<>(Collections.singletonList(B))), "مدير الموظفين خصص «النور» لوسيم");
        // 10.7: المكتب نفسه يجوز أن يكون عند محاسبَين، والواجهة تعرف عند من هو لتنبّه
        db.setUserOffices(v2, new HashSet<>(Arrays.asList(A, B)));
        Map<Long, List<String>> holders = db.officeHolders(v1);
        check(holders.containsKey(B) && holders.get(B).size() == 1 && !holders.get(B).contains(db.user(v1).name) && holders.containsKey(A),
                "مكتب عند محاسبَين: officeHolders يعطي الآخرين (عدا المستخدم نفسه): " + holders);
        db.setUserOffices(v2, new HashSet<>());
        check(throwsMsg(() -> db.setUserOffices(mgr, new HashSet<>(Collections.singletonList(A))), "بنفسك"), "لا يخصص مكاتبه لنفسه");
        check(throwsMsg(() -> db.setUserOffices(admin, new HashSet<>(Collections.singletonList(A))), "المدير"), "ولا مكاتب المدير");
        as(db, acct);
        long onA = db.addTx(txIn(A, Model.TYPE_DEBIT, 777, "USD"), null);
        as(db, v1);
        check(throwsMsg(() -> db.verifyTx(onA), "ليس من المكاتب التي تتحقق منها"), "وسيم لا يتحقق من مكتب ليس من مكاتبه");
        List<Model.Tx> mine = db.awaitingMyCheck(30, 500);
        boolean onlyB = !mine.isEmpty();
        for (Model.Tx x : mine) if (x.officeId != B || x.id == vt) onlyB = false;
        check(onlyB && db.myOffices().size() == 1, "«بانتظار تحققي» لوسيم: حركات «النور» التي لم يتحقق منها فقط (" + mine.size() + ")");
        // التعديل
        as(db, acct);
        Db.TxInput sameKey = txIn(B, Model.TYPE_DEBIT, 12300, "USD");
        sameKey.person = "اسم مصحح";
        long vt2 = db.editTx(vt, sameKey, null, null, "الاسم");
        check(db.checks(vt2).size() == 2 && db.checks(vt2).get(0).valid(), "تعديل الاسم وحده: التحقق انتقل إلى الحركة المصححة");
        Db.TxInput moreMoney = txIn(B, Model.TYPE_DEBIT, 13300, "USD");
        long vt3 = db.editTx(vt2, moreMoney, null, null, "المبلغ");
        boolean voided = true;
        for (Db.TxCheck c : db.checks(vt2)) if (c.valid() || !c.voidReason.contains("المبلغ")) voided = false;
        check(db.checks(vt3).isEmpty() && voided && db.txPeople(db.tx(vt2)).toString().contains("تحقق أُبطل"), "تعديل المبلغ يُبطل التحقق ويبقيه في التاريخ بسببه");
        // العملية المترابطة
        Db.TransferInput tr2 = new Db.TransferInput();
        tr2.from = B;
        tr2.to = A;
        tr2.currency = "USD";
        tr2.amount = 1500;
        tr2.when = System.currentTimeMillis() - 1000;
        long[] tl = db.addTransfer(tr2);
        as(db, v2);
        db.verifyTx(tl[0]);
        check(db.checks(tl[0]).size() == 1 && db.checks(tl[1]).size() == 1, "التحقق من ساق يشمل العملية كلها");
        // علامة آخر رسالة
        db.setWaMark(A, "حوالة 500 دولار لأحمد", "10:31");
        Thread.sleep(5);
        db.setWaMark(A, "حوالة 900 يورو لسامي", "11:02");
        List<Db.WaMark> marks = db.waMarks(A, 10);
        check(marks.size() == 2 && marks.get(0).text.equals("حوالة 900 يورو لسامي") && "هالة".equals(marks.get(0).userName) && db.lastWaMark(A).msgTime.equals("11:02"),
                "علامة آخر رسالة: الأحدث أولًا مع من حفظها، والتاريخ محفوظ");
        healthy(db, "بعد التحقق والمكاتب والعلامات");

        System.out.println("== AUDIT-015: الاعتماد يعيد فحص نشاط المكتب");
        as(db, clerk);
        long pz = db.addTx(txIn(A, Model.TYPE_DEBIT, 300, "USD"), null);
        as(db, admin);
        db.setOfficeActive(A, false);
        as(db, acct);
        check(throwsMsg(() -> db.approveTx(pz), "موقوف") && db.tx(pz).isPending(), "حركة معلّقة لمكتب أُوقف بعدها لا تُعتمد (تبقى معلّقة)");
        as(db, admin);
        db.setOfficeActive(A, true);
        as(db, acct);
        db.approveTx(pz);
        check(db.tx(pz).isActive(), "وبعد تفعيله تُعتمد");

        System.out.println("== §5: المكتب المثبّت لرسائل واتساب");
        WaPin.clear(db);
        check(WaPin.before(db, A) == WaPin.Need.CONFIRM_SESSION, "جلسة جديدة: يُطلب تأكيد المكتب قبل أول حفظ");
        WaPin.pin(db, A);
        check(WaPin.before(db, A) == WaPin.Need.NOTHING && WaPin.current(db).id == A, "بعد التأكيد: المكتب مثبّت ولا يُسأل عنه في كل رسالة");
        check(WaPin.before(db, B) == WaPin.Need.CONFIRM_CHANGE, "اختيار مكتب آخر يحتاج تأكيد التغيير");
        check(WaPin.changeWarning("البلسم", "النور").equals("سيتم تغيير المكتب من «البلسم» إلى «النور». الرسائل التالية ستُسجّل للمكتب الجديد"), "نص التحذير كما طُلب");
        db.setSettingQuiet(WaPin.AT, "" + (System.currentTimeMillis() - WaPin.SESSION_MS - 1000));
        check(WaPin.current(db) == null && WaPin.before(db, A) == WaPin.Need.CONFIRM_SESSION, "بعد انتهاء الجلسة يُطلب التأكيد من جديد");
        WaPin.clear(db);

        System.out.println("== 10.8: قص العملات مقاصة أرصدة (المثال الكامل: EUR لنا 100، RON علينا 500، نبيع 200 EUR بسعر 5)");
        as(db, admin);
        db.saveCurrency("RON", "ليو روماني", 2, true);
        long D = db.addOffice("المقاصة", "", "", "", "", "", null, null, Arrays.asList("EUR", "RON"), new HashMap<>(),
                Arrays.asList(new Db.Opening("EUR", "لنا", 10000), new Db.Opening("RON", "علينا", 50000)));
        as(db, acct);
        check(b(db.balances(D), "EUR") == 10000 && b(db.balances(D), "RON") == -50000, "قبل العملية: EUR لنا 100، RON علينا 500");
        FxDeal fd8 = new FxDeal();
        fd8.sell = "EUR";
        fd8.buy = "RON";
        fd8.sellAmount = 20000;
        fd8.rate = FxDeal.parseRate("5");
        fd8.buyPerSell = true;
        long ron = fd8.buyAmount();
        FxDeal inv = new FxDeal();
        inv.sell = "EUR";
        inv.buy = "RON";
        inv.sellAmount = 20000;
        inv.rate = FxDeal.parseRate("0.2");
        inv.buyPerSell = false;
        check(ron == 100000 && inv.buyAmount() == 100000, "صيغتا السعر (1 EUR = 5 RON و1 RON = 0.2 EUR) تعطيان 1,000 RON");
        String warn = FxDeal.overdraft("EUR", b(db.balances(D), "EUR"), 20000, 2);
        check(warn != null && warn.contains("200") && warn.contains("100") && warn.contains("«لنا 100 EUR»") && warn.contains("«علينا 100 EUR»"),
                "تنبيه التجاوز بالنص المطلوب: " + warn);
        check(FxDeal.overdraft("EUR", 10000, 10000, 2) == null && FxDeal.overdraft("EUR", 10000, 5000, 2) == null, "بيع الرصيد كله أو أقل منه: لا تنبيه");
        check(FxDeal.overdraft("EUR", 0, 5000, 2).contains("متوازن") && FxDeal.overdraft("EUR", -3000, 5000, 2).contains("«علينا 80 EUR»"),
                "الرصيد صفر أو «علينا» أصلًا: تنبيه بمقدار زيادة المديونية والرصيد النهائي");
        List<Db.Preview> fp = db.preview(Arrays.asList(db.fxNetLegs(D, "EUR", 20000, "RON", ron, "5", "", System.currentTimeMillis())), null);
        long cashEur = b(db.cashBalances(), "EUR"), cashRon = b(db.cashBalances(), "RON");
        String[] fx = db.addFxNet(D, "EUR", 20000, "RON", ron, "5", "", System.currentTimeMillis() - 500);
        check(b(db.balances(D), "EUR") == -10000 && b(db.balances(D), "RON") == 50000, "بعد العملية: EUR علينا 100 (+100−200)، RON لنا 500 (−500+1000)");
        check(FxDeal.side(b(db.balances(D), "EUR"), 2).equals("علينا 100") && FxDeal.side(b(db.balances(D), "RON"), 2).equals("لنا 500") && FxDeal.side(0, 2).equals("متوازن"),
                "لنا/علينا من إشارة الرصيد النهائي، والصفر «متوازن»");
        String[] row = FxDeal.row("EUR", 10000, -20000, 2);
        check(fp.size() == 1 && fp.get(0).after.get("EUR") == -10000 && fp.get(0).after.get("RON") == 50000
                        && row[1].equals("لنا 100") && row[2].equals("خصم 200") && row[3].equals("علينا 100"),
                "المعاينة قبل الحفظ = الرصيد الفعلي بعده، والجدول: لنا 100 | خصم 200 | علينا 100");
        check(b(db.cashBalances(), "EUR") == cashEur && b(db.cashBalances(), "RON") == cashRon, "لا حركة صندوق ولا نقد");
        Model.Tx fl = null;
        for (Model.Tx tt : db.recentTx(20)) if (tt.isFx() && tt.officeId == D && "EUR".equals(tt.currency)) fl = tt;
        check(fl != null && Model.TYPE_CREDIT.equals(fl.type) && db.legs(fl).size() == 2, "ساق العملة المباعة «علينا» (تُخصم)، والساقان بمعرّف عملية واحد");
        db.cancelTx(fl.id, "تجربة الإلغاء");
        check(b(db.balances(D), "EUR") == 10000 && b(db.balances(D), "RON") == -50000 && !db.tx(fl.id).isActive() && db.tx(fl.id).createdAt > 0,
                "الإلغاء يعكس الطرفين معًا ويبقى تاريخ العملية");
        // عكس الاتجاه: نبيع RON ونأخذ EUR يعبر الصفر في العملتين
        db.addFxNet(D, "RON", 150000, "EUR", 30000, "0.2", "", System.currentTimeMillis() - 400);
        check(b(db.balances(D), "RON") == -200000 && b(db.balances(D), "EUR") == 40000, "البيع من رصيد «علينا» يزيده (RON علينا 2,000) والمشتراة تُضاف (EUR لنا 400)");
        java.util.Map<String, java.math.BigDecimal> usd = new HashMap<>();
        usd.put("EUR", new java.math.BigDecimal("1.08"));
        usd.put("RON", new java.math.BigDecimal("0.216"));
        check(FxDeal.reference(usd, "EUR", "RON").toPlainString().equals("5") && FxDeal.referenceIn(FxDeal.reference(usd, "EUR", "RON"), false).toPlainString().equals("0.2"),
                "سعر البرنامج بين عملتين من أسعار الدولار المحفوظة، وبالاتجاه العكسي");
        healthy(db, "بعد المقاصات");

        System.out.println("== 10.8: فرز المكاتب بين المحاسبين — من يسجّل ومن يتحقق");
        as(db, admin);
        long r1 = db.addUser("محاسب أول", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY,EXPORT", "5151");
        long r2 = db.addUser("محاسب ثان", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY,EXPORT", "5252");
        long r3 = db.addUser("محاسب ثالث", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY", "5353");
        // تنظيف تخصيصات الأقسام السابقة حتى يبدأ الفرز من الصفر
        for (Model.User u : db.users(false)) if (!Auth.isAdmin(u)) db.setUserOffices(u.id, new HashSet<>());
        Map<Long, String> q1 = new HashMap<>(), q2 = new HashMap<>(), q3 = new HashMap<>();
        q1.put(A, Db.ROLE_OWN);
        q2.put(B, Db.ROLE_OWN);
        q3.put(D, Db.ROLE_OWN);
        db.setUserOffices(r1, q1);
        db.setUserOffices(r2, q2);
        db.setUserOffices(r3, q3);
        check(!db.teamMode(), "قبل تحديد من يتحقق: لا فرز (سلوك 10.5)");
        int auto = db.autoCheckers();
        Map<Long, Db.Team> tm = new HashMap<>();
        for (Db.Team tq : db.teams()) tm.put(tq.office.id, tq);
        Set<Long> chA = new HashSet<>();
        for (Model.User u : tm.get(A).checkers) chA.add(u.id);
        boolean threeWay = auto >= 3 && tm.get(A).owners.size() == 1 && tm.get(A).owners.get(0).id == r1 && chA.contains(r2) && chA.contains(r3) && !chA.contains(r1);
        check(db.teamMode() && threeWay, "ثلاثة محاسبين: كل اثنين يتحققان من مكتب الثالث");
        check(throwsMsg(() -> db.setOfficeTeam(A, new HashSet<>(Arrays.asList(r1)), new HashSet<>(Arrays.asList(r1, r2))), "لا يسجّل ويتحقق"), "لا يجمع محاسب الدورين في مكتب واحد");
        as(db, r1);
        long own1 = db.addTx(txIn(A, Model.TYPE_DEBIT, 777, "USD"), null);
        check(throwsMsg(() -> db.verifyTx(own1), "أدخلتها أنت"), "المسجّل لا يتحقق مما أدخله");
        as(db, r2);
        long otherTx = db.addTx(txIn(A, Model.TYPE_CREDIT, 555, "USD"), null);
        as(db, r1);
        check(throwsMsg(() -> db.verifyTx(otherTx), "أنت من يسجّل حركات هذا المكتب"), "من يسجّل المكتب لا يتحقق من حركاته ولو أدخلها غيره: " );
        as(db, r2);
        boolean seen = false;
        for (Model.Tx w8 : db.awaitingMyCheck(60, 500)) {
            if (w8.id == own1) seen = true;
            check(w8.officeId != B, "«بانتظار تحققي» لا يعرض مكتبي الذي أسجّله");
        }
        db.verifyTx(own1);
        check(seen && db.verified(db.tx(own1)), "المحاسب الثاني يرى حركة الأول في «بانتظار تحققي» ويتحقق منها");
        check(throwsMsg(() -> db.verifyTx(db.addTx(txIn(B, Model.TYPE_DEBIT, 10, "USD"), null)), "أدخلتها أنت"), "ويسجّل في مكتبه هو");
        check(db.myOffices().size() == 1 && db.myOffices().get(0).id == B && db.myCheckOffices().size() == 2, "«مكاتبي»: يسجّل مكتبًا ويتحقق من مكتبين");
        // المطابقة: ينشئها المسجّل ويتحقق منها غيره
        as(db, r1);
        long rc = db.createRecon(A);
        check(throwsMsg(() -> db.verifyRecon(rc), "أنشأتها أنت"), "من أنشأ المطابقة لا يتحقق منها");
        as(db, r2);
        boolean rseen = false;
        for (Model.Recon r : db.awaitingMyRecons(60, 50)) if (r.id == rc) rseen = true;
        db.verifyRecon(rc);
        Model.Recon rv8 = db.recon(rc);
        check(rseen && rv8.checkedBy != null && rv8.checkedBy == r2 && db.reconPeople(rv8).contains("محاسب ثان") && db.reconPeople(rv8).contains("محاسب أول"),
                "المطابقة تظهر في «بانتظار تحققي» ويُسجَّل من تحقق منها: " + db.reconPeople(rv8));
        healthy(db, "بعد الفرز والتحقق");

        System.out.println("== 10.8: حذف حساب محاسب دون المساس بما سجّله");
        as(db, admin);
        Map<String, Long> balA = new java.util.TreeMap<>(db.balances(A));
        int txBefore = db.recentTx(5000).size();
        db.deleteUser(r1);
        Model.User gone = db.user(r1);
        boolean listed = false;
        for (Model.User u : db.users(false)) if (u.id == r1) listed = true;
        check(gone != null && gone.deleted && !gone.active && !listed && db.userOffices(r1).isEmpty(), "الحساب محذوف: لا يظهر في القوائم ولا في المكاتب");
        check(balA.equals(new java.util.TreeMap<>(db.balances(A))) && db.recentTx(5000).size() == txBefore && db.tx(own1).isActive(), "الأرصدة والحركات كما هي تمامًا");
        check(db.txPeople(db.tx(own1)).get(0).contains("محاسب أول (حساب محذوف)") && db.verified(db.tx(own1)), "اسمه باقٍ على ما أدخله «(حساب محذوف)»، والتحقق باقٍ");
        check(db.userByName("محاسب أول") == null && !db.verifyPin(r1, "5151"), "لا دخول بعد الحذف (لا اسم ولا رمز)");
        check(throwsMsg(() -> db.deleteUser(admin), "داخل به"), "لا يحذف المستخدم حسابه وهو داخل به");
        as(db, mgr);
        check(throwsMsg(() -> db.deleteUser(admin), "المدير"), "حساب المدير لا يحذفه إلا مدير");
        as(db, admin);
        healthy(db, "بعد حذف الحساب");

        System.out.println("== 10.12: تفضيلات المظهر لكل مستخدم وحده");
        as(db, admin);
        String officeName0 = db.setting("office_name", "");
        String globalTabs = db.globalSetting("tabs", "X");
        db.setSettingQuiet("tabs", "home,offices,tx");
        db.setSettingQuiet("theme_mode", "dark");
        db.setSettingQuiet("primary_color", "#AA0000");
        check("home,offices,tx".equals(db.setting("tabs", "")) && "dark".equals(db.setting("theme_mode", "light")), "المدير يرى شريطه ووضعه");
        check(globalTabs.equals(db.globalSetting("tabs", "X")), "القيمة العامة لم تتغير بتخصيص المدير");
        as(db, r2);
        check(globalTabs.equals(db.setting("tabs", "X")) && !"dark".equals(db.setting("theme_mode", "light")) && !"#AA0000".equals(db.setting("primary_color", "")),
                "المحاسب لا يتأثر بشريط المدير ولا وضعه ولا ألوانه (يرى القيمة السابقة)");
        db.setSettingQuiet("tabs", "home,log");
        db.setSettingQuiet("haptics", "off");
        as(db, admin);
        check("home,offices,tx".equals(db.setting("tabs", "")) && "on".equals(db.setting("haptics", "on")), "وتخصيص المحاسب لا يغيّر شريط المدير ولا اهتزازه");
        db.deleteSetting("primary_color");
        check("#0B6B53".equals(db.setting("primary_color", "#0B6B53")), "«الافتراضي» لمستخدم = القيمة الافتراضية لا العامة");
        as(db, r2);
        check("home,log".equals(db.setting("tabs", "")), "ويبقى للمحاسب شريطه");
        db.setSettingQuiet("office_name", "مكتب الاختبار المشترك");
        as(db, admin);
        check("مكتب الاختبار المشترك".equals(db.setting("office_name", "")), "اسم المكتب مشترك بين الجميع (ليس تفضيلًا شخصيًا)");
        db.setSettingQuiet("office_name", officeName0);
        Model.User keep = SmartApp.currentUser;
        SmartApp.currentUser = null;
        check(globalTabs.equals(db.setting("tabs", "X")), "بلا مستخدم داخل: القيمة العامة كما كانت");
        SmartApp.currentUser = keep;
        try {
            java.lang.reflect.Method loc = Class.forName("com.smart.hawala.SyncStore").getDeclaredMethod("localSetting", String.class);
            loc.setAccessible(true);
            check((Boolean) loc.invoke(null, "u" + r2 + ".tabs") && !(Boolean) loc.invoke(null, "u" + r2 + ".primary_color")
                    && Db.baseKey("u12.tabs").equals("tabs") && Db.baseKey("usd_rate").equals("usd_rate"),
                    "المزامنة: الشريط يبقى لكل جهاز، والألوان تنتقل مع المستخدم إلى أجهزته");
        } catch (Exception e) {
            check(false, "localSetting: " + e);
        }

        System.out.println("== 10.12: تقارير جديدة");
        as(db, admin);
        ReportBuilder.Data bd = db.builderData();
        int xfer = 0, xferHit = 0, fxHit = 0, fxBoth = 0;
        ReportBuilder.Spec sx = new ReportBuilder.Spec(), sf = new ReportBuilder.Spec();
        sx.kind = ReportBuilder.K_XFER;
        sf.kind = ReportBuilder.K_FX;
        for (Model.Tx tq : db.recentTx(5000)) {
            if (tq.isTransfer() && tq.isActive()) xfer++;
            boolean hx = ReportBuilder.matches(sx, bd, tq, 0, 0), hf = ReportBuilder.matches(sf, bd, tq, 0, 0);
            if (hx) xferHit++;
            if (hf) fxHit++;
            if (hx && hf) fxBoth++;
        }
        check(xfer > 0 && xferHit == xfer && fxBoth == 0 && fxHit > 0, "«الحركات بين مكتبين فقط» تطابق ساقي كل حركة بين مكتبين (" + xfer + ")، ولا تختلط بقص العملات");
        int ran = 0;
        for (ReportBuilder.Preset p : ReportBuilder.presets1012()) {
            ReportBuilder.Spec ps = ReportBuilder.Spec.fromJson(p.spec.toJson());
            Report rp = ReportBuilder.build(ps, bd, "");
            if (rp != null && !rp.sections.isEmpty()) ran++;
        }
        check(ran == ReportBuilder.presets1012().size(), "التقارير الجاهزة الجديدة (" + ran + ") تُبنى");
        Report team = db.teamCheckReport(0, 0, "كل الفترات");
        StringBuilder tt = new StringBuilder();
        for (Report.Section sec : team.sections) for (Report.Row rw : sec.rows) tt.append(java.util.Arrays.toString(rw.cells)).append('\n');
        check(team.sections.size() == 3 && tt.indexOf("محاسب ثان") >= 0 && tt.indexOf("المجموع") >= 0, "تقرير التسجيل والتحقق: المكاتب والمحاسبون والمطابقات");
        long verifiedTotal = 0;
        for (Report.Row rw : team.sections.get(0).rows) if (rw.style == Report.TOTAL) verifiedTotal = (Long) rw.cells[4];
        check(verifiedTotal >= 1, "ويحسب ما تحقق منه محاسب آخر (" + verifiedTotal + ")");

        System.out.println("== 10.13: حالة عمل المكاتب اليوم («مكاتبي»)");
        as(db, admin);
        Map<Long, Db.Work> wk = db.officeWork();
        check(wk.get(A) != null && Db.W_RECON.equals(wk.get(A).auto) && Db.W_RECON.equals(wk.get(A).status) && wk.get(A).txToday > 0,
                "تلقائيًا: مكتب فيه حركات اليوم ومطابقة اليوم = «أُرسلت مطابقة»");
        db.setOfficeWork(A, Db.W_DONE);
        Db.Work wa = db.officeWork().get(A);
        check(Db.W_DONE.equals(wa.status) && Db.W_DONE.equals(wa.manual) && !wa.byName.isEmpty(), "يدويًا: «تم التسجيل» ومن وضعها (" + wa.byName + ")");
        db.setOfficeWork(A, null);
        check(db.officeWork().get(A).manual == null && Db.W_RECON.equals(db.officeWork().get(A).status), "إعادتها تلقائية");
        check(throwsMsg(() -> db.setOfficeWork(A, "BOGUS"), "غير معروفة"), "حالة غير معروفة مرفوضة");
        // مكتب جديد لا يسجّله المحاسب الثاني ولا يتحقق منه
        final long nm = db.addOffice("مكتب خارج الفرز", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        as(db, r2);
        check(nm > 0 && throwsMsg(() -> db.setOfficeWork(nm, Db.W_DONE), "لا تغيّر"), "محاسب لا يغيّر حالة مكتب لا يسجّله ولا يتحقق منه (طبقة البيانات)");
        long own2 = db.myOffices().isEmpty() ? -1 : db.myOffices().get(0).id;
        db.setOfficeWork(own2, Db.W_DOING);
        as(db, admin);
        check(Db.W_DOING.equals(db.officeWork().get(own2).status), "المدير يرى الحالة التي وضعها المحاسب");
        check(java.util.Arrays.asList(BackupFormat.TABLES).contains("office_work"), "حالات المكاتب تدخل النسخة الاحتياطية");

        System.out.println("== 10.13: قص العملات من الطرفين");
        FxDeal fd2 = new FxDeal();
        fd2.sell = "EUR";
        fd2.buy = "USD";
        fd2.sellDec = 2;
        fd2.buyDec = 2;
        fd2.rate = new java.math.BigDecimal("0.92");
        fd2.buyPerSell = true;
        check(Long.valueOf(100000).equals(fd2.sellAmountFor(92000)), "المشترى 920 USD بسعر 0.92 ← المباع 1,000 EUR");
        fd2.buyPerSell = false;
        fd2.rate = new java.math.BigDecimal("1.087");
        check(Long.valueOf(100004).equals(fd2.sellAmountFor(92000)), "بالاتجاه الآخر (1 USD = 1.087 EUR): 920 USD ← 1,000.04 EUR");
        fd2.sellAmount = fd2.sellAmountFor(92000);
        check(Long.valueOf(92000).equals(fd2.buyAmount()), "والعكس يعيد المبلغ نفسه");

        SmartApp.currentUser = null;
        System.out.println();
        System.out.println(passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }
}
