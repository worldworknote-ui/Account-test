import android.content.Context;
import android.database.Cursor;
import com.smart.hawala.*;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

/**
 * 12.6 على Db الحقيقي وبيانات اصطناعية: تشفير القاعدة كاملة (وترحيل قاعدة 10.4 غير مشفّرة)، ضياع المفتاح دون حذف شيء،
 * الصلاحيات المفصلة (المعادن، الأسعار أونلاين، الصندوق) واشتقاقها للحسابات القديمة، سقوف المجانية الجديدة (الصناديق والعملاء
 * والمستخدمون)، رخصة مخصصة بالأرقام، وصرامة حسابات المعادن (عيار واحد لكل حساب).
 * الاستعمال: java Release126Test tests/data/db-10.4
 */
public class Release126Test {
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

    static boolean plain(File f) throws Exception {
        byte[] h = Files.readAllBytes(f.toPath());
        return h.length >= 15 && new String(h, 0, 15, java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3");
    }

    static boolean opensWithoutKey(File f) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getPath())) {
            c.createStatement().executeQuery("SELECT count(*) FROM tx").next();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Model.User user(Db db, String name, String perms) {
        for (Model.User u : db.users(false)) if (u.name.equals(name)) return u;
        return null;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        File src = new File(a[a.length - 1]);

        System.out.println("== 1) قاعدة 10.4 غير مشفّرة تُشفَّر عند أول فتح، وكل شيء كما هو");
        File home = Files.createTempDirectory("sa-126-cipher").toFile();
        MigrationTest.copy(src, home);
        File dbf = new File(home, "databases/" + Db.NAME);
        check(plain(dbf), "قبل: الملف عادي (رأس SQLite ظاهر)");
        DeskCoreTest.resetCrypto();
        Db M = MigrationTest.open(home);
        Map<Long, Map<String, Long>> bal = new TreeMap<>(M.officeBalances());
        check(!plain(dbf) && M.encrypted(), "بعد: الملف كله مشفّر");
        check(Db.cipherNote.isEmpty() && Db.lockedAside == null, "بلا ملاحظات ولا نقل جانبي");
        check(bal.toString().equals("{1={EUR=-10000, USD=136700}, 2={EUR=-25000}}"), "أرصدة المكاتب كما في قاعدة 10.4: " + bal);
        check(M.users(false).size() == 2 && M.cashBalances().get("USD") == 500000, "المستخدمان والصندوق كما هم");
        String[] left = new File(home, "databases").list();
        boolean clean = true;
        for (String n : left) if (n.contains("plain") || n.contains("pre-encrypt") || n.contains("enc-tmp")) clean = false;
        check(clean, "لا نسخة غير مشفّرة متروكة بجانب القاعدة: " + Arrays.toString(left));
        check(!opensWithoutKey(dbf), "الملف لا يُقرأ بلا المفتاح (برنامج SQLite عادي يرفضه)");
        String name1 = M.office(1).name;
        M.close();
        DeskCoreTest.resetCrypto();
        Db M2 = MigrationTest.open(home);
        check(M2.office(1).name.equals(name1) && new TreeMap<>(M2.officeBalances()).equals(bal), "يُفتح مرة ثانية بالمفتاح نفسه، والأسماء المشفّرة تُقرأ");
        try (Cursor c = M2.getReadableDatabase().rawQuery("PRAGMA user_version", null)) {
            c.moveToFirst();
            check(c.getInt(0) == Db.VER, "رقم المخطط " + Db.VER);
        }
        M2.close();

        System.out.println("== 2) ضياع مفتاح الجهاز: القاعدة تُنقل جانبًا ولا تُحذف، وتعود مع المفتاح");
        File keys = new File(home, "keys");
        File keysAway = new File(home, "keys-away");
        keys.renameTo(keysAway);
        keys.mkdirs();
        DeskCoreTest.resetCrypto();
        SecretStore.fallback(keys);
        Context ctx = new Context(home);
        Crypto.init(ctx);   // لا مفتاح والقاعدة «موجودة»؟ — نقلناها؟ لا: Crypto يرى القاعدة فلا يولّد مفتاحًا
        Db L = new Db(ctx);
        check(Db.lockedAside != null && Db.lockedAside.exists(), "القاعدة المشفّرة نُقلت جانبًا كما هي: " + (Db.lockedAside == null ? "" : Db.lockedAside.getName()));
        check(!Crypto.healthy(ctx) || Db.lockedAside != null, "الشاشة تعرض «مفتاح التشفير غير متاح» (الإدخال والنسخ التلقائي موقوفان)");
        // عاد المفتاح
        deleteTree(keys);
        keysAway.renameTo(keys);
        DeskCoreTest.resetCrypto();
        SecretStore.fallback(keys);
        Crypto.init(ctx);
        Db back = Db.restoreLockedAside(ctx, L);
        check(back != null && Db.lockedAside == null && new TreeMap<>(back.officeBalances()).equals(bal), "بعد عودة المفتاح: «إعادة المحاولة» تعيد القاعدة كاملة بأرصدتها");
        back.close();

        System.out.println("== 3) مفتاح جديد قبل الاستعادة: القديمة تبقى على القرص، والجديدة تعمل");
        DeskCoreTest.resetCrypto();
        SecretStore.fallback(keys);
        Crypto.init(ctx);
        Db cur = new Db(ctx);
        Crypto.resetForRestore(ctx);
        Db fresh = Db.reopenAfterKeyReset(ctx, cur);
        int lockedFiles = 0;
        for (String n : new File(home, "databases").list()) if (n.startsWith("smart_accountant.locked-") && n.endsWith(".db")) lockedFiles++;
        check(lockedFiles >= 1 && Db.lockedAside == null, "القديمة محفوظة باسم locked-… (لا تُحذف) ولا تمنع العمل");
        check(fresh.offices().isEmpty() && fresh.encrypted(), "قاعدة جديدة فارغة مشفّرة بالمفتاح الجديد، جاهزة للاستعادة");
        fresh.close();

        System.out.println("== 4) الصلاحيات المفصلة واشتقاقها للحسابات القديمة");
        Set<String> old = Auth.expand(Auth.parse("VIEW,CURRENCIES,CASH"));
        check(old.contains("METALS") && old.contains("RATES") && old.contains("RATES_ONLINE"), "حساب قديم بـ«العملات»: المعادن والأسعار اليدوية والأونلاين كما كانت");
        check(old.contains("CASH_EXPENSE") && old.contains("CASH_FX") && old.contains("CASH_COUNT") && old.contains("CLIENTS"), "حساب قديم بـ«الصندوق»: المصاريف والبيع والشراء والجرد والعملاء");
        Set<String> d12 = Auth.expand(Auth.parse("VIEW,CURRENCIES,RATES,CASH,P12"));
        check(d12.contains("METALS") && d12.contains("RATES_ONLINE") && d12.contains("CASH_FX"), "قائمة 12.0–12.5 (P12 بلا P126): تُشتق الجديدة فلا ينقص شيء");
        Set<String> d126 = Auth.expand(Auth.parse("VIEW,CURRENCIES,CASH,P12,P126"));
        check(!d126.contains("METALS") && !d126.contains("RATES_ONLINE") && !d126.contains("CASH_FX"), "قائمة 12.6: كما اختارها المدير تمامًا");
        check(Auth.csv(Auth.parse("CURRENCIES,P12,P126")).contains("P126"), "العلامة تُحفظ");

        Object[] dv = DeskCoreTest.device("r126p");
        Db P = (Db) dv[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        Model.User admin = P.user(P.addUser("المدير", Auth.ADMIN, null, "112233"));
        SmartApp.currentUser = admin;
        Db.currentUserName = admin.name;
        final Model.User am = P.user(P.addUser("أمين", Auth.CUSTOM, "VIEW,CURRENCIES,RATES,CASH,P12,P126", "4455"));
        SmartApp.currentUser = am;
        Db.currentUserName = am.name;
        check(throwsMsg(() -> P.saveMetal("XAU21", "ذهب 21", Metals.GOLD, 875, "g", false, true), "صلاحية"), "بلا «المعادن»: إضافة معدن مرفوضة في طبقة البيانات");
        P.saveCurrency("TRY", "ليرة تركية", 2, true);
        check(P.currencies(true).stream().anyMatch(c -> c.code.equals("TRY")), "و«العملات» وحدها تكفي لإضافة عملة");
        Map<String, BigDecimal> on = new HashMap<>();
        on.put("TRY", new BigDecimal("0.03"));
        check(throwsMsg(() -> P.saveOnlineRates(on, Clock.now()), "صلاحية"), "بلا «الأسعار أونلاين»: التحديث من الإنترنت مرفوض");
        check(throwsMsg(() -> P.setRatesUrl("https://x.example"), "صلاحية"), "وتغيير مصدر الأسعار مرفوض");
        P.setManualRate("TRY", new BigDecimal("0.031"));
        check(P.rates().get("TRY").manual != null, "والسعر اليدوي يعمل بـ«الأسعار اليدوية»");
        SmartApp.currentUser = admin;
        Db.currentUserName = admin.name;
        P.cashSetup("", Arrays.asList("USD", "TRY"), Collections.singletonMap("USD", 100000L), false, true);
        SmartApp.currentUser = am;
        Db.currentUserName = am.name;
        CashBox.Input ex = new CashBox.Input();
        ex.kind = CashBox.EXPENSE;
        ex.currency = "USD";
        ex.amount = 1000;
        ex.category = "إيجار";
        ex.boxId = 1;
        ex.when = Clock.now();
        check(throwsMsg(io(() -> P.addCashMove(ex)), "صلاحية"), "بلا «مصاريف الصندوق»: المصروف مرفوض في طبقة البيانات");
        CashBox.Input in = new CashBox.Input();
        in.kind = CashBox.IN;
        in.currency = "USD";
        in.amount = 500;
        in.category = "استلام من شخص";
        in.boxId = 1;
        in.when = Clock.now();
        P.addCashMove(in);
        check(P.cashBalances().get("USD") == 100500, "والاستلام يعمل بـ«الصندوق» وحده");
        check(throwsMsg(() -> P.cashCount(1, Collections.singletonMap("USD", 100500L), "", new ArrayList<>()), "صلاحية"), "بلا «الجرد»: الجرد مرفوض");
        SmartApp.currentUser = null;

        System.out.println("== 5) سقوف المجانية الجديدة ورخصة مخصصة");
        Object[] dv2 = DeskCoreTest.device("r126f");
        Db F = (Db) dv2[1];
        License.current = Entitlements.of(null, BuildInfo.BUILD_DATE);
        check(Entitlements.FREE_OFFICES == 15 && Entitlements.FREE_TX_PER_OFFICE == 1500, "المجانية: 15 مكتبًا و1500 حركة لكل مكتب");
        for (int i = 1; i <= 25; i++) {
            ClientBook.Client k = new ClientBook.Client();
            k.name = "عميل " + i;
            F.saveClient(k);
        }
        ClientBook.Client k26 = new ClientBook.Client();
        k26.name = "عميل 26";
        check(throwsMsg(() -> F.saveClient(k26), "25"), "العميل 26 مرفوض في المجانية");
        long firstId = F.clients().get(0).id;
        F.setClientActive(firstId, false);
        F.saveClient(k26);
        check(F.clients().stream().filter(c -> c.active).count() == 25, "أرشفة عميل تعيد مكانه");
        check(throwsMsg(() -> F.setClientActive(firstId, true), "25"), "وإعادة المؤرشف فوق الحد مرفوضة");
        F.cashSetup("", Arrays.asList("USD"), new HashMap<>(), false, true);
        check(throwsMsg(() -> F.addCashBox("خزنة 2", Arrays.asList("USD"), null), "صندوق واحد"), "صندوق ثانٍ مرفوض في المجانية");
        Model.User owner = F.user(F.addUser("المالك", Auth.ADMIN, null, "778899"));
        SmartApp.currentUser = owner;
        Db.currentUserName = owner.name;
        check(throwsMsg(() -> F.addUser("موظف", Auth.CUSTOM, "VIEW", "1231"), "مستخدم واحد"), "المجانية لا تضيف مستخدمين");
        Map<String, String> g = new HashMap<>();
        g.put("ft", "users,boxes,clients,export");
        g.put("lid", "TEST-CUSTOM");
        g.put("uu", "29991231");
        g.put("mu", "2");
        g.put("mb", "2");
        g.put("mc", "30");
        java.lang.reflect.Constructor<LicenseToken.Grant> gc = LicenseToken.Grant.class.getDeclaredConstructor(Map.class);
        gc.setAccessible(true);
        License.current = Entitlements.of(gc.newInstance(g), BuildInfo.BUILD_DATE);
        F.addUser("موظف", Auth.CUSTOM, "VIEW", "1231");
        check(throwsMsg(() -> F.addUser("موظف2", Auth.CUSTOM, "VIEW", "3213"), "2"), "رخصة بمستخدمَين: الثالث مرفوض برسالة الرقم");
        long b2 = F.addCashBox("خزنة 2", Arrays.asList("USD"), null);
        check(b2 > 0 && throwsMsg(() -> F.addCashBox("خزنة 3", Arrays.asList("USD"), null), "2"), "رخصة بصندوقين: الثاني يُضاف والثالث مرفوض");
        License.current = Entitlements.of(null, BuildInfo.BUILD_DATE);
        check(F.cashBoxes().size() == 2 && F.users(false).size() == 2, "بعد العودة للمجانية: الصناديق والمستخدمون الموجودون باقون كما هم");
        SmartApp.currentUser = null;

        System.out.println("== 6) المعادن: كل عيار حساب واحد، ولا عيار غامض");
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        SmartApp.currentUser = owner;
        Db.currentUserName = owner.name;
        F.saveMetal("XAU21", "ذهب 21", Metals.GOLD, 875, "g", false, true);
        check(throwsMsg(() -> F.saveMetal("XAU21B", "ذهب 21 مكرر", Metals.GOLD, 875, "g", false, true), "كل عيار حساب واحد"), "العيار نفسه بالوحدة نفسها مرفوض باسم آخر");
        check(throwsMsg(() -> F.saveMetal("XAUX", "ذهب بلا عيار", Metals.GOLD, 0, "g", false, true), "اختر العيار"), "ذهب بلا عيار بجانب ذهب آخر مرفوض (يلتبس)");
        F.saveMetal("XAU21K", "ذهب 21 كيلو", Metals.GOLD, 875, "kg", false, true);
        F.saveMetal("XAU18", "ذهب 18", Metals.GOLD, 750, "g", false, true);
        check(F.metals().size() == 3, "عيار آخر أو وحدة أخرى حساب مستقل");
        F.saveMetal("XAU21", "ذهب عيار 21", Metals.GOLD, 875, "g", false, false);
        check(!F.currencies(true).stream().filter(c -> c.code.equals("XAU21")).findFirst().get().active, "تعديل الاسم والتفعيل لحساب قائم يعمل");
        List<String> taken = new ArrayList<>();
        for (Model.Currency c : F.currencies(true)) taken.add(c.code);
        check(Metals.uniqueCode(Metals.GOLD, 875, "g", taken).equals("XAU21G") && Metals.uniqueCode(Metals.GOLD, 916, "g", taken).equals("XAU22"),
                "الرمز المقترح لا يتكرر (XAU21 موجود ← XAU21G)، وعيار جديد ← XAU22");
        check(Metals.show("1.500", "XAU18").equals("1.500 غ ذهب عيار 18") && Metals.qtyWord("XAU18").equals("الوزن") && Metals.qtyWord("USD").equals("المبلغ"),
                "المعدن يُعرض بوزنه ووحدته: «1.500 غ ذهب عيار 18»");

        SmartApp.currentUser = null;
        System.out.println("== 7) الاشتراك بتاريخ انتهاء لا يمتد بإرجاع الساعة");
        Object[] dv3 = DeskCoreTest.device("r126d");
        Db D = (Db) dv3[1];
        D.setSettingQuiet("license_day", "20300101");
        check(D.licenseDay() == 20300101, "أكبر يوم رآه التطبيق يبقى ولو رجعت ساعة الجهاز");

        System.out.println();
        System.out.println(fails == 0 ? "نجحت اختبارات 12.6 كلها (" + passes + ")" : "فشل " + fails + " من " + (passes + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    interface IoRun { void run() throws Exception; }

    static Runnable io(IoRun r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }
}
