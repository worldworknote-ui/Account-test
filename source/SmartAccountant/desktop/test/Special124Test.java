import android.content.Context;
import android.database.Cursor;
import com.smart.hawala.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * 12.4 على Db الحقيقي (sqlite-jdbc) وبيانات اصطناعية: الحركات الخاصة (محسوبة وغير محسوبة)، ظهورها في الكشف «معلّقة»،
 * تحويلها لعادية بتاريخها أو بتاريخ اليوم، تعليم العادية خاصة، الإلغاء، الصلاحيات، الاعتماد، تحويل الأمانة والمجمّد لحركة،
 * المزامنة بين جهازين، وترحيل قاعدة 10.4 إلى المخطط 12.
 * الاستعمال: java Special124Test tests/data/db-10.4
 */
public class Special124Test {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Db.TxInput tx(long office, boolean us, long amount, String cur, String person, long when) {
        Db.TxInput t = new Db.TxInput();
        t.office = office;
        t.type = us ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        t.amount = amount;
        t.currency = cur;
        t.person = person;
        t.when = when;
        return t;
    }

    static Db.TxInput sp(Db.TxInput t, String type, boolean bal) {
        t.special = type;
        t.specialBal = bal;
        return t;
    }

    static Db.TxInput copy(Model.Tx t) {
        Db.TxInput in = tx(t.officeId, Model.TYPE_DEBIT.equals(t.type), t.amount, t.currency, t.person, t.createdAt);
        in.feeType = t.feeType;
        in.fee = t.fee;
        in.feeCurrency = t.feeCurrency;
        in.description = t.description;
        in.notes = t.notes;
        in.service = t.service;
        in.dest = t.dest;
        return in;
    }

    static boolean throwsMsg(Runnable r, String part) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            String all = "";
            for (Throwable t = e; t != null; t = t.getCause()) all += t.getMessage() + " | ";
            if (part != null && !all.contains(part)) System.out.println("      رسالة: " + all);
            return part == null || all.contains(part);
        }
    }

    interface Io { void run() throws Exception; }

    static Runnable io(Io r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        };
    }

    static void as(Db db, long uid) {
        SmartApp.currentUser = uid == 0 ? null : db.user(uid);
        Db.currentUserName = SmartApp.currentUser == null ? "" : SmartApp.currentUser.name;
    }

    static int count(Db db, String sql) {
        try (Cursor c = db.getReadableDatabase().rawQuery(sql, null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    static long bal(Db db, long office, String cur) { return db.balances(office).getOrDefault(cur, 0L); }

    static void syncAll(SyncEngine... es) throws Exception {
        for (int round = 0; round < 3; round++) for (SyncEngine e : es) e.sync();
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        Object[] first = DeskCoreTest.device("s124A");
        Db A = (Db) first[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        List<String> ue = Arrays.asList("USD", "EUR", "TRY");
        long off = A.addOffice("تجربة", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long other = A.addOffice("الأمل", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long now = Clock.now();
        long t3 = now - 3 * 86_400_000L;

        System.out.println("== 1) خاصة لا تُحسب في الرصيد");
        long base = A.addTx(tx(off, true, 100000, "USD", "عادية", t3), null);
        long sNo = A.addTx(sp(tx(off, true, 50000, "USD", "أبو خالد", t3), "ناقصة أجور", false), null);
        Model.Tx s1 = A.tx(sNo);
        check(Model.STATUS_SPECIAL.equals(s1.status) && s1.isSpecial() && s1.isUnposted() && "ناقصة أجور".equals(s1.special), "محفوظة «خاصة» بنوعها، بلا قيود");
        check(bal(A, off, "USD") == 100000, "الرصيد لا يتغير: " + bal(A, off, "USD"));
        check(count(A, "SELECT COUNT(*) FROM journal_entries WHERE tx_id=" + sNo) == 0, "لا قيد لها");
        boolean inStmt = false;
        for (Model.Line l : A.statementLines(off, 0, Long.MAX_VALUE)) if (l.txId != null && l.txId == sNo) inStmt = true;
        check(!inStmt, "ولا تظهر في الكشف");
        check(A.specials(off).size() == 1 && A.specials(null).size() == 1, "تظهر في قائمة الخاصة للمكتب");

        System.out.println("== 2) خاصة تُحسب في الرصيد (معلّقة في الكشف والمطابقة)");
        long sYes = A.addTx(sp(tx(off, false, 20000, "USD", "سامي", t3), "تحتاج مراجعة", true), null);
        Model.Tx s2 = A.tx(sYes);
        check(s2.isActive() && s2.isSpecial() && s2.specialBal, "نشطة ومعلَّمة");
        check(bal(A, off, "USD") == 80000, "تدخل الرصيد: " + bal(A, off, "USD"));
        String mark = null;
        for (Model.Line l : A.statementLines(off, 0, Long.MAX_VALUE)) if (l.txId != null && l.txId == sYes) mark = l.special;
        check("تحتاج مراجعة".equals(mark), "سطر الكشف يحمل نوعها");
        Reports.StatementInput si = new Reports.StatementInput();
        si.officeName = "تجربة";
        si.lines = A.statementLines(off, 0, Long.MAX_VALUE);
        si.fullBalance = A.balances(off);
        si.opening = new HashMap<>();
        si.decimals = A.decimalsMap();
        si.periodLabel = "الكل";
        Report rep = Reports.statement(si, ExportStyle.from(new HashMap<>()));
        boolean marked = false;
        for (Report.Section sec : rep.sections) for (Report.Row row : sec.rows) for (Object c : row.cells) if (String.valueOf(c).contains("معلّقة: تحتاج مراجعة")) marked = true;
        check(marked, "الكشف المصدَّر: «معلّقة: تحتاج مراجعة»");
        check(A.specials(off).size() == 2, "قائمة الخاصة: اثنتان");

        System.out.println("== 3) تحويل غير المحسوبة لعادية مع الأجور، بتاريخ اليوم");
        Db.TxInput c1 = copy(s1);
        c1.feeType = Model.FEE_US;
        c1.fee = 1000;
        c1.feeCurrency = "USD";
        c1.when = Clock.now();
        long n1 = A.editTx(sNo, c1, null, null, "");
        check(Model.STATUS_CONVERTED.equals(A.tx(sNo).status) && A.tx(n1).isActive() && !A.tx(n1).isSpecial() && A.tx(n1).replacesId == sNo, "القديمة «حُوّلت» والجديدة عادية مرتبطة بها");
        check(bal(A, off, "USD") == 80000 + 50000 + 1000, "الرصيد = المبلغ + الأجرة: " + bal(A, off, "USD"));
        check(count(A, "SELECT COUNT(*) FROM journal_entries WHERE tx_id=" + sNo + " AND kind='REVERSAL'") == 0, "لا قيد عكسي لما لم يُرحَّل");
        check(Model.day(A.tx(n1).createdAt).equals(Model.day(Clock.now())), "بتاريخ اليوم");
        check(throwsMsg(io(() -> A.editTx(sNo, copy(s1), null, null, "")), null), "المحوّلة لا تُحوَّل مرة ثانية");

        System.out.println("== 4) تحويل المحسوبة كما هي (بتاريخها): العلامة وحدها تُرفع");
        long entriesBefore = count(A, "SELECT COUNT(*) FROM journal_entries");
        long n2 = A.editTx(sYes, copy(s2), null, null, "");
        check(n2 == sYes && !A.tx(sYes).isSpecial() && A.tx(sYes).isActive(), "الحركة نفسها صارت عادية");
        check(count(A, "SELECT COUNT(*) FROM journal_entries") == entriesBefore && bal(A, off, "USD") == 131000, "لا قيود جديدة والرصيد كما هو: " + bal(A, off, "USD"));

        System.out.println("== 5) محسوبة ثم إضافة أجرتها: قيد عكسي وحركة عادية");
        long s3 = A.addTx(sp(tx(off, true, 30000, "EUR", "نور", t3), "بانتظار التنفيذ", true), null);
        Db.TxInput c3 = copy(A.tx(s3));
        c3.feeType = Model.FEE_THEM;
        c3.fee = 500;
        c3.feeCurrency = "EUR";
        long n3 = A.editTx(s3, c3, null, null, "");
        check(n3 != s3 && "CANCELLED".equals(A.tx(s3).status) && !A.tx(n3).isSpecial() && A.tx(n3).createdAt == t3, "القديمة أُلغيت بقيد عكسي والجديدة عادية بتاريخها الأصلي");
        check(bal(A, off, "EUR") == 30000 - 500, "الرصيد = المبلغ − أجرتهم: " + bal(A, off, "EUR"));

        System.out.println("== 6) تعليم حركة عادية كخاصة، وتغيير حسابها في الرصيد");
        Db.TxInput m = sp(copy(A.tx(base)), "عليها مشاكل", true);
        long nb = A.editTx(base, m, null, null, "");
        check(nb == base && "عليها مشاكل".equals(A.tx(base).special) && bal(A, off, "USD") == 131000, "العلامة وحدها (الحركة والرصيد كما هما)");
        Db.TxInput m2 = sp(copy(A.tx(base)), "عليها مشاكل", false);
        long nb2 = A.editTx(base, m2, null, null, "");
        check(A.tx(nb2).isUnposted() && "CANCELLED".equals(A.tx(base).status) && bal(A, off, "USD") == 31000, "«لا تُحسب»: تخرج من الرصيد بقيد عكسي: " + bal(A, off, "USD"));

        System.out.println("== 7) الإلغاء");
        A.cancelTx(nb2, "تجربة");
        check(Model.STATUS_VOIDED.equals(A.tx(nb2).status) && bal(A, off, "USD") == 31000, "إلغاء غير المحسوبة: VOIDED بلا قيد، والرصيد كما هو");
        check(throwsMsg(() -> A.voidSpecial(nb2, ""), "ليست"), "لا تُلغى مرتين");
        long s7 = A.addTx(sp(tx(off, false, 700, "USD", "", now), "ناقصة أجور", true), null);
        A.cancelTx(s7, "");
        check("CANCELLED".equals(A.tx(s7).status) && bal(A, off, "USD") == 31000, "المحسوبة تُلغى بقيد عكسي كأي حركة");

        System.out.println("== 8) أنواع الحركات الخاصة");
        check(A.specialTypes().equals(Arrays.asList(Db.SPECIAL_DEFAULTS)), "الأنواع الافتراضية الأربعة");
        A.editSpecialType("بانتظار الإيصال", false);
        check(A.specialTypes().contains("بانتظار الإيصال") && A.specialTypes().size() == 5, "إضافة نوع جديد");
        check(throwsMsg(() -> A.editSpecialType("بانتظار الإيصال", false), "موجود"), "لا يتكرر");
        A.editSpecialType("عليها مشاكل", true);
        check(!A.specialTypes().contains("عليها مشاكل"), "حذف نوع");
        check(throwsMsg(io(() -> A.addTx(sp(tx(off, true, 1, "USD", "", now), "اسم طويل جدًا جدًا جدًا جدًا جدًا جدًا جدًا جدًا", true), null)), "طويل"), "اسم نوع طويل مرفوض");

        System.out.println("== 9) الأمانة والمجمّد إلى حركة عادية");
        String h1 = A.addHold(off, "TRY", Db.HOLD_TRUST, Db.SIDE_OURS, 90000, "أمانة تجربة");
        long egp0 = bal(A, off, "TRY");
        long hx = A.addTx(tx(off, true, 90000, "TRY", "", now), null, h1);
        Db.Hold hh = null;
        for (Db.Hold h : A.holds(off, false)) if (h.uuid.equals(h1)) hh = h;
        check(hh != null && !hh.open() && hh.toTx.equals(A.tx(hx).uuid), "الأمانة فُكّت مربوطة بالحركة");
        check(bal(A, off, "TRY") == egp0 + 90000, "والحركة في الرصيد والكشف");
        check(throwsMsg(io(() -> A.addTx(tx(off, true, 90000, "TRY", "", now), null, h1)), "فُكّت"), "لا تُحوَّل الأمانة مرتين");
        int txBefore = count(A, "SELECT COUNT(*) FROM tx");
        String h2 = A.addHold(off, "TRY", Db.HOLD_TRUST, Db.SIDE_THEIRS, 6000, "");
        check(throwsMsg(io(() -> A.addTx(tx(other, false, 6000, "TRY", "", now), null, h2)), "مكتب"), "حركة لمكتب آخر مرفوضة");
        check(count(A, "SELECT COUNT(*) FROM tx") == txBefore && A.holds(off, true).size() == 1, "ولا يبقى منها شيء (المعاملة كاملة أو لا شيء)");
        check(throwsMsg(io(() -> A.addTx(sp(tx(off, false, 6000, "TRY", "", now), "ناقصة أجور", true), null, h2)), "عادية"), "الأمانة تُحوَّل لحركة عادية فقط");
        String f1 = A.addHold(off, "TRY", Db.HOLD_FREEZE, Db.SIDE_OURS, 30000, "");
        A.releaseHold(f1);
        check(bal(A, off, "TRY") == egp0 + 90000, "فكّ التجميد لا يغيّر الرصيد");
        String f2 = A.addHold(off, "TRY", Db.HOLD_FREEZE, Db.SIDE_OURS, 30000, "");
        A.addTx(tx(off, false, 30000, "TRY", "", now), null, f2);
        check(bal(A, off, "TRY") == egp0 + 60000 && A.holds(off, true).size() == 1, "تسجيل المجمّد حركة: يتغير الرصيد ويُفكّ التجميد");

        System.out.println("== 10) الصلاحيات والاعتماد");
        long admin = A.addUser("المدير", Auth.ADMIN, null, "123456");
        as(A, admin);
        long u1 = A.addUser("سامر", Auth.CUSTOM, "VIEW,TX_ADD," + Auth.DETAILED, "1111");
        long u2 = A.addUser("رنا", Auth.CUSTOM, "VIEW,TX_ADD,SPECIAL," + Auth.DETAILED, "2222");
        long u3 = A.addUser("قديم", Auth.CUSTOM, "VIEW,TX_ADD", "3333");
        as(A, u1);
        check(throwsMsg(io(() -> A.addTx(sp(tx(off, true, 1000, "USD", "", Clock.now()), "ناقصة أجور", false), null)), null), "بلا صلاحية «خاصة»: لا تسجيل خاصة (في طبقة البيانات)");
        check(throwsMsg(() -> A.editSpecialType("س", false), null), "ولا إضافة نوع");
        as(A, u2);
        long s10 = A.addTx(sp(tx(off, true, 1000, "USD", "", Clock.now()), "ناقصة أجور", false), null);
        check(A.tx(s10).isUnposted(), "بصلاحية «خاصة»: تُسجَّل");
        check(throwsMsg(io(() -> A.addTx(tx(off, true, 10, "TRY", "", Clock.now()), null, A.addHold(off, "TRY", Db.HOLD_TRUST, Db.SIDE_OURS, 10, ""))), null), "تحويل أمانة يحتاج صلاحية «الأمانات»");
        as(A, u3);
        check(Auth.perms(A.user(u3)).contains("SPECIAL"), "قائمة قديمة بلا علامة تفصيلية: التسجيل يجلب «خاصة» كما يجلب الأمانات");
        as(A, admin);
        long u4 = A.addUser("مراجَع", Auth.CUSTOM, "VIEW,TX_ADD,SPECIAL," + Auth.REVIEW + "," + Auth.DETAILED, "4444");
        as(A, u4);
        long pend = A.addTx(sp(tx(off, true, 500000, "USD", "", Clock.now()), "بانتظار التنفيذ", false), null);
        check(A.tx(pend).isPending(), "مستخدم تنتظر حركاته الاعتماد: الخاصة بانتظار الاعتماد أولًا");
        as(A, admin);
        long b0 = bal(A, off, "USD");
        A.approveTx(pend);
        check(A.tx(pend).isUnposted() && bal(A, off, "USD") == b0, "الاعتماد يجعلها «خاصة» غير محسوبة (بلا قيود)");
        as(A, 0);

        System.out.println("== 11) المزامنة");
        File shared = Files.createTempDirectory("sa-124-folder").toFile();
        SyncEngine eA = new SyncEngine(A, new FileSyncFolder(shared));
        eA.create("كلمة-سر-المزامنة".toCharArray(), "المحل");
        Db B = new Db(new Context(Files.createTempDirectory("sa-124-B").toFile()));
        SyncEngine eB = new SyncEngine(B, new FileSyncFolder(shared));
        eB.join("كلمة-سر-المزامنة".toCharArray(), false, "البيت");
        syncAll(eA, eB);
        check(B.specials(off).size() == A.specials(off).size() && !B.specials(off).isEmpty(), "الخاصة وصلت الجهاز الثاني: " + B.specials(off).size());
        check(B.balances(off).equals(A.balances(off)), "والأرصدة متطابقة");
        check(B.specialTypes().equals(A.specialTypes()), "وأنواعها متزامنة");
        as(B, admin);
        Model.Tx onB = B.tx(s10);
        long conv = B.editTx(s10, copy(onB), null, null, "");
        as(B, 0);
        syncAll(eA, eB);
        check(Model.STATUS_CONVERTED.equals(A.tx(s10).status) && A.tx(conv) != null && A.tx(conv).isActive(), "التحويل على جهاز يصل الآخر");
        check(B.balances(off).equals(A.balances(off)), "والأرصدة متطابقة بعده");

        System.out.println("== 12) ترحيل قاعدة 10.4 إلى المخطط " + Db.VER);
        File src = new File(a[a.length - 1]);
        if (!src.isDirectory()) check(false, "مجلد قاعدة 10.4 غير موجود: " + src);
        else {
            File home = Files.createTempDirectory("sa-124-mig").toFile();
            MigrationTest.copy(src, home);
            DeskCoreTest.resetCrypto();
            Db M = MigrationTest.open(home);
            Map<Long, Map<String, Long>> balM = new TreeMap<>(M.officeBalances());
            int ver;
            try (Cursor c = M.getReadableDatabase().rawQuery("PRAGMA user_version", null)) {
                c.moveToFirst();
                ver = c.getInt(0);
            }
            check(ver == Db.VER && Db.VER >= 12, "رقم المخطط بعد الترحيل " + ver);
            check(count(M, "SELECT COUNT(*) FROM tx WHERE special IS NOT NULL") == 0, "الحركات القديمة كلها عادية");
            for (Model.User u : M.users(false)) if (Auth.isAdmin(u)) as(M, u.id);
            Model.Office mo = M.offices().get(0);
            String cur = M.officeCurrencies(mo.id).get(0);
            M.addTx(sp(tx(mo.id, true, 100, cur, "", Clock.now()), "ناقصة أجور", false), null);
            check(new TreeMap<>(M.officeBalances()).equals(balM) && M.specials(mo.id).size() == 1, "الأرصدة كما هي، والخاصة تعمل على القاعدة القديمة");
        }

        System.out.println();
        System.out.println(fails == 0 ? "نجحت اختبارات 12.4 كلها (" + passes + ")" : "فشل " + fails + " من " + (passes + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
