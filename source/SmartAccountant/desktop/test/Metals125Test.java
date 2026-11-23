import android.content.Context;
import android.database.Cursor;
import com.smart.hawala.*;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.util.*;

/**
 * 12.5 على Db الحقيقي وبيانات اصطناعية: المعادن الثمينة بالوزن (كل عيار حساب)، ثبات الوحدة والعيار بعد أول حركة،
 * التقويم بالدولار باختيار المحاسب، السعر الأونلاين للاستئناس، قراءة الوزن والعيار من الرسائل، المزامنة، وترحيل قاعدة 10.4.
 * الاستعمال: java Metals125Test tests/data/db-10.4
 */
public class Metals125Test {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
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

    static Db.TxInput tx(long office, boolean us, long amount, String cur) {
        Db.TxInput t = new Db.TxInput();
        t.office = office;
        t.type = us ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        t.amount = amount;
        t.currency = cur;
        t.when = Clock.now();
        return t;
    }

    static List<MessageParser.Cur> curs(Db db, String... codes) {
        List<MessageParser.Cur> l = new ArrayList<>();
        for (String c : codes) for (Model.Currency x : db.currencies(true)) if (x.code.equals(c)) l.add(new MessageParser.Cur(x.code, x.name, x.decimals));
        return l;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        Object[] first = DeskCoreTest.device("m125A");
        Db A = (Db) first[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);

        System.out.println("== 1) حسابات المعادن");
        A.saveMetal("XAU21", "ذهب عيار 21 (غرام)", Metals.GOLD, 875, "g", false, true);
        A.saveMetal("XAU24", "ذهب عيار 24 (غرام)", Metals.GOLD, 999, "g", true, true);
        A.saveMetal("XAGKG", "فضة 999 (كيلو)", Metals.SILVER, 999, "kg", true, true);
        check(A.decimals("XAU21") == 3 && A.decimals("XAGKG") == 6, "منازل الوحدة: غرام 3، كيلو 6");
        check(A.metals().size() == 3 && Metals.of("XAU21").fineness == 875 && !Metals.of("XAU21").valued, "السجل يعرف المعادن وعيارها وتقويمها");
        check(throwsMsg(() -> A.saveMetal("USD", "x", Metals.GOLD, 999, "g", false, true), "لعملة موجودة"), "رمز عملة موجودة مرفوض");
        check(throwsMsg(() -> A.saveMetal("XAU9", "x", Metals.GOLD, 1200, "g", false, true), "العيار"), "عيار غير صالح مرفوض");
        check(throwsMsg(() -> A.saveMetal("XAU9", "x", Metals.GOLD, 375, "lb", false, true), "وحدة"), "وحدة غير معروفة مرفوضة");

        System.out.println("== 2) مكتب بعملة ومعدن");
        List<String> ue = Arrays.asList("USD", "XAU21", "XAU24");
        long off = A.addOffice("صاغة الشام", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        A.addTx(tx(off, true, 12500, "XAU21"), null);
        A.addTx(tx(off, false, 2000, "XAU24"), null);
        A.addTx(tx(off, true, 150000, "USD"), null);
        Map<String, Long> b = A.balances(off);
        check(b.get("XAU21") == 12500 && b.get("XAU24") == -2000 && b.get("USD") == 150000, "الأرصدة منفصلة لكل عيار وعملة: " + b);

        System.out.println("== 3) الثبات بعد أول حركة");
        check(throwsMsg(() -> A.saveMetal("XAU21", "ذهب", Metals.GOLD, 875, "kg", false, true), "لا يمكن تغيير"), "الوحدة لا تتغير بعد الحركات");
        check(throwsMsg(() -> A.saveMetal("XAU21", "ذهب", Metals.GOLD, 750, "g", false, true), "لا يمكن تغيير"), "العيار لا يتغير بعد الحركات");
        A.saveMetal("XAU21", "ذهب 21 كسر", Metals.GOLD, 875, "g", false, true);
        A.saveCurrency("XAU21", "ذهب عيار 21", 2, true);
        check(A.decimals("XAU21") == 3 && Metals.of("XAU21").unit.equals("g"), "تعديل الاسم يبقي الوحدة والمنازل (حتى من شاشة العملات)");
        check(A.balances(off).get("XAU21") == 12500, "والرصيد كما هو");

        System.out.println("== 4) التقويم بالدولار باختيار المحاسب");
        A.setManualRate("XAU24", new BigDecimal("80"));
        Ledger.Valuation v = Ledger.valueOffice(A.balances(off), A.usdRates(), A.decimalsMap());
        check(v.weightOnly.contains("XAU21") && !v.missingRates.contains("XAU21"), "ذهب 21 «بالوزن فقط»: خارج الميزان وليس «بلا سعر»");
        check(v.payableUsd.compareTo(new BigDecimal("160.00")) == 0 && v.receivableUsd.compareTo(new BigDecimal("1500.00")) == 0,
                "ذهب 24 بسعره: 2 غ × 80 = 160 علينا، والدولار 1500 لنا: " + v.receivableUsd + " / " + v.payableUsd);
        A.saveMetal("XAU21", "ذهب 21 كسر", Metals.GOLD, 875, "g", true, true);
        v = Ledger.valueOffice(A.balances(off), A.usdRates(), A.decimalsMap());
        check(v.missingRates.contains("XAU21") && v.weightOnly.isEmpty(), "حين يختار «يدخل الميزان» بلا سعر: يظهر «بلا سعر»");

        System.out.println("== 5) السعر الأونلاين للاستئناس");
        Map<String, BigDecimal> on = new HashMap<>();
        on.put("XAU", new BigDecimal("2488"));
        on.put("XAG", new BigDecimal("31.1034768"));
        A.saveOnlineRates(on, Clock.now());
        BigDecimal g24 = A.rates().get("XAU24").online, g21 = A.rates().get("XAU21").online, agkg = A.rates().get("XAGKG").online;
        check(g24 != null && g24.setScale(4, RoundingMode.HALF_EVEN).compareTo(new BigDecimal("79.9111")) == 0, "غرام 24 = 2488 ÷ 31.1034768 × 0.999 = " + g24);
        check(g21 != null && g21.setScale(4, RoundingMode.HALF_EVEN).compareTo(new BigDecimal("69.9922")) == 0, "غرام 21 = × 0.875 = " + g21);
        check(agkg != null && agkg.setScale(2, RoundingMode.HALF_EVEN).compareTo(new BigDecimal("999.00")) == 0, "كيلو فضة 999 = 1000 غ × 1 × 0.999 = " + agkg);
        check(A.rates().get("XAU24").manual.compareTo(new BigDecimal("80")) == 0 && A.usdRates().get("XAU24").compareTo(new BigDecimal("80")) == 0, "اليدوي يتقدّم على الأونلاين");

        System.out.println("== 6) الرسائل: العيار والوزن");
        List<MessageParser.Cur> c3 = curs(A, "USD", "XAU21", "XAU24");
        MessageParser.Result r = MessageParser.parse("تسليم 50 غرام ذهب عيار 21 لأبو خالد", c3);
        check("XAU21".equals(r.currency) && "50".equals(r.amount), "«50 غرام ذهب عيار 21» ← XAU21 50: " + r.currency + " " + r.amount + " [" + r.person + "]");
        r = MessageParser.parse("استلام 2 كيلو ذهب 24 قيراط", c3);
        check("XAU24".equals(r.currency) && "2000".equals(r.amount) && r.amountNote.contains("حُوّل"), "«2 كيلو ذهب 24» ← 2000 غ مع تنبيه التحويل: " + r.amount + " — " + r.amountNote);
        r = MessageParser.parse("1 اونصة ذهب 24k", c3);
        check("XAU24".equals(r.currency) && "31.103".equals(r.amount) && r.amountNote.contains("تقريب"), "«1 أونصة» ← 31.103 غ وتنبيه التقريب: " + r.amount + " — " + r.amountNote);
        r = MessageParser.parse("100 غرام ذهب لسامر", c3);
        check(r.currency == null && "100".equals(r.amount) && r.amountNote.contains("عيار"), "ذهب بلا عيار وللمكتب عياران: لا تخمين: " + r.currency + " — " + r.amountNote);
        r = MessageParser.parse("100 غرام ذهب لسامر", curs(A, "USD", "XAU21"));
        check("XAU21".equals(r.currency) && "100".equals(r.amount), "عيار واحد للمكتب: يُختار: " + r.currency);
        r = MessageParser.parse("ذهب عيار 18 وزن 20 غرام", c3);
        check(r.currency == null && r.amountNote.contains("لا يوجد حساب"), "عيار غير موجود للمكتب: لا يُسجَّل على عيار آخر: " + r.amountNote);
        r = MessageParser.parse("حوالة 500 دولار لأبو علي", c3);
        check("USD".equals(r.currency) && "500".equals(r.amount), "رسالة العملة كما هي: " + r.currency + " " + r.amount);
        check(Metals.finenessIn("فضة 925") == 925 && Metals.finenessIn("18k") == 750 && Metals.finenessIn("عيار 21") == 875, "قراءة العيار: 925، 18k، عيار 21");
        boolean[] ex = {true};
        check(Metals.convert(new BigDecimal("2.5"), "mithqal", "g", ex).compareTo(new BigDecimal("10.625")) == 0 && ex[0], "2.5 مثقال = 10.625 غ بالضبط");

        System.out.println("== 7) المزامنة");
        File shared = Files.createTempDirectory("sa-125-folder").toFile();
        SyncEngine eA = new SyncEngine(A, new FileSyncFolder(shared));
        eA.create("كلمة-سر-المزامنة".toCharArray(), "المحل");
        Db B = new Db(new Context(Files.createTempDirectory("sa-125-B").toFile()));
        SyncEngine eB = new SyncEngine(B, new FileSyncFolder(shared));
        eB.join("كلمة-سر-المزامنة".toCharArray(), false, "البيت");
        for (int i = 0; i < 3; i++) {
            eA.sync();
            eB.sync();
        }
        List<Metals.Metal> mb = B.metals();
        Metals.Metal x = null;
        for (Metals.Metal m : mb) if (m.code.equals("XAGKG")) x = m;
        check(mb.size() == 3 && x != null && x.unit.equals("kg") && x.fineness == 999 && B.decimals("XAGKG") == 6, "المعادن بإعداداتها وصلت الجهاز الثاني");
        check(B.balances(off).equals(A.balances(off)), "والأرصدة بالوزن متطابقة");

        System.out.println("== 8) ترحيل قاعدة 10.4 إلى المخطط " + Db.VER);
        File src = new File(a[a.length - 1]);
        if (!src.isDirectory()) check(false, "مجلد قاعدة 10.4 غير موجود: " + src);
        else {
            File home = Files.createTempDirectory("sa-125-mig").toFile();
            MigrationTest.copy(src, home);
            DeskCoreTest.resetCrypto();
            Db M = MigrationTest.open(home);
            Map<Long, Map<String, Long>> before = new TreeMap<>(M.officeBalances());
            int ver;
            try (Cursor c = M.getReadableDatabase().rawQuery("PRAGMA user_version", null)) {
                c.moveToFirst();
                ver = c.getInt(0);
            }
            check(ver == 13 && Db.VER == 13, "رقم المخطط بعد الترحيل " + ver);
            check(M.metals().isEmpty(), "لا معادن في القاعدة القديمة، والعملات كلها عملات");
            for (Model.User u : M.users(false)) if (Auth.isAdmin(u)) {
                SmartApp.currentUser = u;
                Db.currentUserName = u.name;
            }
            M.saveMetal("XAU22", "ذهب 22", Metals.GOLD, 916, "g", false, true);
            check(new TreeMap<>(M.officeBalances()).equals(before) && M.metals().size() == 1, "الأرصدة كما هي، وإضافة معدن تعمل على القاعدة القديمة");
            SmartApp.currentUser = null;
        }

        System.out.println();
        System.out.println(fails == 0 ? "نجحت اختبارات 12.5 كلها (" + passes + ")" : "فشل " + fails + " من " + (passes + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
