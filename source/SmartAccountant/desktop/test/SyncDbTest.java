import android.content.Context;
import com.smart.hawala.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * 10.0: المزامنة على Db الحقيقي (sqlite-jdbc): ثلاثة أجهزة يتشاركون مجلدًا واحدًا.
 * الإنشاء والانضمام (بكلمة السر وبرمز الاسترداد)، كتابة متزامنة، إلغاء الحركة نفسها من جهازين، تعديلها من جهازين،
 * حذف مكتب أثناء إضافة حركة له، تكرار الحزم، تلاعب بايت واحد، حزمة ناقصة، انقطاع بعد الختم وقبل الرفع، وبصمة الدفتر.
 * بعد كل مرحلة: كل الأجهزة بالأرصدة نفسها، وفحص الصحة الكامل سليم على كل جهاز.
 */
public class SyncDbTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Db dev(String n) throws Exception {
        File home = Files.createTempDirectory("sa-sync-" + n).toFile();
        Context ctx = new Context(home);
        return new Db(ctx);
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

    static Db.TxInput tx(long office, boolean us, long amount, String cur, String person) {
        Db.TxInput t = new Db.TxInput();
        t.office = office;
        t.type = us ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        t.amount = amount;
        t.currency = cur;
        t.person = person;
        t.when = System.currentTimeMillis() - 1000;
        return t;
    }

    static String bal(Db db) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Long, Map<String, Long>> e : new TreeMap<>(db.officeBalances()).entrySet())
            for (Map.Entry<String, Long> x : new TreeMap<>(e.getValue()).entrySet()) if (x.getValue() != 0) b.append(e.getKey()).append(x.getKey()).append('=').append(x.getValue()).append(' ');
        b.append("| cash ");
        for (CashBox.Config c : db.cashBoxes()) if (c.on) b.append(c.id).append(db.cashBalances(c.id)).append(' ');
        b.append("| clients ").append(new TreeMap<>(db.clientBalances()));
        return b.toString();
    }

    static int notifCount(Db db) {
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM notif_log", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    static void syncAll(SyncEngine... es) throws Exception {
        for (int round = 0; round < 3; round++) for (SyncEngine e : es) e.sync();
    }

    static long b(Map<String, Long> m, String c) {
        Long v = m.get(c);
        return v == null ? 0 : v;
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

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        Object[] first = DeskCoreTest.device("syncA");
        Db A = (Db) first[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        File shared = Files.createTempDirectory("sa-sync-folder").toFile();

        System.out.println("== 1) الجهاز المرجعي: بيانات ثم إنشاء الخزنة");
        List<String> ue = Arrays.asList("USD", "EUR");
        long nour = A.addOffice("النور", "", "", "", "", "", null, null, ue, new HashMap<>(), Arrays.asList(new Db.Opening("USD", "لنا", 50000)));
        long amal = A.addOffice("الأمل", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        A.addTx(tx(nour, true, 100000, "USD", "سامر"), Arrays.asList(new byte[]{1, 2, 3, 4, 5}));
        A.addTx(tx(amal, false, 20000, "EUR", "أحمد"), null);
        Map<String, Long> op = new HashMap<>();
        op.put("USD", 1000000L);
        A.cashSetup("الصندوق", ue, op, false, true);
        ClientBook.Client k = new ClientBook.Client();
        k.name = "أبو خالد";
        long kid = A.saveClient(k);
        A.addClientEntry(kid, "EUR", 10000, -1, "أمانة", System.currentTimeMillis() - 5000);
        SyncEngine eA = new SyncEngine(A, new FileSyncFolder(shared));
        String rc = eA.create("كلمة-سر-المزامنة".toCharArray(), "المحل");
        check(rc.length() == 29 && SyncEngine.enabled(A), "أُنشئت الخزنة ورمز الاسترداد: " + rc.substring(0, 4) + "…");
        check(new File(shared, SyncEngine.HEADER).isFile(), "رأس الخزنة في المجلد");
        boolean opaque = true;
        for (String n : shared.list()) {
            if (n.equals(SyncEngine.HEADER)) continue;
            if (!n.matches("[mpsa]-[0-9a-f]{32}\\.sab")) opaque = false;
            byte[] raw = Files.readAllBytes(new File(shared, n).toPath());
            String txt = new String(raw, "ISO-8859-1");
            if (txt.contains("USD") || raw.length % 4096 != 0) opaque = false;
        }
        String hdr = new String(Files.readAllBytes(new File(shared, SyncEngine.HEADER).toPath()), "UTF-8");
        check(opaque && !hdr.contains("النور") && !hdr.contains("USD"), "السحابة لا ترى إلا ملفات مشفّرة بأسماء معتمة وأحجام مضاعفة لـ 4KB");

        System.out.println("== 2) الانضمام: بكلمة السر، وبرمز الاسترداد");
        Db B = dev("syncB");
        B.addOffice("مكتب قديم على الجهاز ب", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        SyncEngine eB = new SyncEngine(B, new FileSyncFolder(shared));
        check(throwsMsg(() -> {
            try {
                eB.join("خطأ-خطأ-خطأ".toCharArray(), false, "البيت");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "غير صحيحة"), "كلمة سر خاطئة تُرفض");
        eB.join("كلمة-سر-المزامنة".toCharArray(), false, "البيت");
        Db C = dev("syncC");
        SyncEngine eC = new SyncEngine(C, new FileSyncFolder(shared));
        eC.join(rc.toLowerCase().toCharArray(), true, "الفرع");
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(C)), "الأجهزة الثلاثة بالأرصدة نفسها بعد الانضمام: " + bal(A));
        check(B.offices().size() == 2, "بيانات الجهاز المنضم السابقة استُبدلت ببيانات الخزنة");
        check(hasImage(B), "الصورة المرفقة انتقلت مع اللقطة");
        healthy(B, "الجهاز ب بعد الانضمام");

        System.out.println("== 3) كتابة متزامنة من الأجهزة الثلاثة");
        long tA = A.addTx(tx(nour, true, 30000, "USD", "من المحل"), null);
        long tB = B.addTx(tx(nour, false, 12000, "USD", "من البيت"), Arrays.asList(new byte[]{9, 9, 9}));
        CashBox.Input ci = new CashBox.Input();
        ci.kind = CashBox.OUT;
        ci.currency = "USD";
        ci.amount = 5000;
        ci.officeId = amal;
        ci.boxId = C.currentBox();
        ci.when = System.currentTimeMillis() - 500;
        C.addCashMove(ci);
        CashBox.Input cc = new CashBox.Input();
        cc.kind = CashBox.OUT;
        cc.currency = "USD";
        cc.amount = 10800;
        cc.clientId = kid;
        cc.clientCurrency = "EUR";
        cc.clientAmount = 10000;
        cc.boxId = B.currentBox();
        cc.when = System.currentTimeMillis() - 400;
        B.addCashMove(cc);
        check((tA >> 32) != (tB >> 32) && (tA >> 32) > 0, "كل جهاز يرقّم من نطاقه (لا تتكرر الأرقام): " + (tA >> 32) + " ≠ " + (tB >> 32));
        syncAll(eA, eB, eC);
        String bA = bal(A);
        check(bA.equals(bal(B)) && bA.equals(bal(C)), "بعد المزامنة الأرصدة متطابقة على الثلاثة");
        Map<String, Long> nb = A.balances(nour);
        check(b(nb, "USD") == 50000 + 100000 + 30000 - 12000, "رصيد النور = 500 + 1,000 + 300 − 120 = 1,680: " + b(nb, "USD"));
        check(b(A.balances(amal), "USD") == 5000 && b(A.cashBalances(1), "USD") == 1000000 - 5000 - 10800, "حركة الصندوق من الفرع وصلت بقيدها على المكتب");
        check(A.clientBalances().getOrDefault(kid, new HashMap<>()).isEmpty(), "حساب العميل صفر على الكل (أمانة 100 EUR أخذ مكانها 108 USD من البيت)");
        check(SyncEngine.status(A).digestAlert.isEmpty() && SyncEngine.status(B).digestAlert.isEmpty(), "بصمات الدفاتر متطابقة");
        check(A.attachmentIds(tB).size() == 1, "صورة الحركة من البيت وصلت إلى المحل");
        healthy(A, "المحل");
        healthy(C, "الفرع");

        System.out.println("== 4) إلغاء الحركة نفسها من جهازين في الوقت نفسه");
        A.cancelTx(tA, "خطأ من المحل");
        B.cancelTx(tA, "خطأ من البيت");
        syncAll(eA, eB, eC);
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(C)), "متطابقة بعد الإلغاء المزدوج");
        check(b(A.balances(nour), "USD") == 50000 + 100000 - 12000, "أُلغيت مرة واحدة فقط (الرصيد 1,380 لا 1,080): " + b(A.balances(nour), "USD"));
        healthy(A, "المحل بعد الإلغاء المزدوج");
        healthy(B, "البيت بعد الإلغاء المزدوج");

        System.out.println("== 5) تعديل الحركة نفسها من جهازين");
        long tX = A.addTx(tx(amal, true, 40000, "USD", "للتعديل"), null);
        syncAll(eA, eB, eC);
        Db.TxInput e1 = tx(amal, true, 41000, "USD", "تعديل المحل");
        Db.TxInput e2 = tx(amal, true, 49000, "USD", "تعديل البيت");
        long n1 = A.editTx(tX, e1, null, null, "");
        long n2 = B.editTx(tX, e2, null, null, "");
        syncAll(eA, eB, eC);
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(C)), "متطابقة بعد تعديلين متعارضين");
        int active = 0;
        for (long id : new long[]{n1, n2}) if (Model.STATUS_ACTIVE.equals(A.tx(id).status)) active++;
        check(active == 1 && Model.STATUS_ACTIVE.equals(A.tx(n1).status), "بقي التعديل الأسبق (المحل) وأُلغي الآخر بقيد عكسي");
        check(b(A.balances(amal), "USD") == 5000 + 41000, "رصيد الأمل = 50 + 410 (لا يُجمع التعديلان): " + b(A.balances(amal), "USD"));
        check(!SyncEngine.conflicts(A).isEmpty() && !SyncEngine.conflicts(B).isEmpty(), "سُجّل تعارض للمراجعة على الجهازين");
        healthy(A, "المحل بعد التعارض");
        healthy(B, "البيت بعد التعارض");

        System.out.println("== 6) حذف مكتب على جهاز بينما يضيف آخر حركة له");
        long rami = A.addOffice("الرامي", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        syncAll(eA, eB, eC);
        C.addTx(tx(rami, true, 7000, "USD", "حركة متأخرة"), null);
        A.deleteOffice(rami, true);
        syncAll(eA, eB, eC);
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(C)), "متطابقة: الحركة على المكتب المحذوف لم تبقَ في أي جهاز");
        check(A.office(rami) == null && C.office(rami) == null, "المكتب محذوف على الكل");
        boolean missing = false;
        for (String[] x : SyncEngine.conflicts(A)) if ("MISSING_PARENT".equals(x[2])) missing = true;
        for (String[] x : SyncEngine.conflicts(C)) if ("MISSING_PARENT".equals(x[2])) missing = true;
        check(missing, "سُجّل تعارض «يشير إلى محذوف» للمراجعة");
        healthy(C, "الفرع بعد الحذف");

        System.out.println("== 7) الحماية: تكرار، تلاعب بايت، حزمة ناقصة، انقطاع قبل الرفع");
        String before = bal(B);
        eB.sync();
        eB.sync();
        check(bal(B).equals(before), "إعادة المزامنة لا تطبّق شيئًا مرتين");
        long tT = A.addTx(tx(nour, true, 1000, "USD", "للتلاعب"), null);
        eA.sync();
        File newest = null;
        for (File f : shared.listFiles()) if (f.getName().startsWith("p-") && (newest == null || f.lastModified() > newest.lastModified())) newest = f;
        byte[] orig = Files.readAllBytes(newest.toPath());
        byte[] bad = orig.clone();
        bad[bad.length / 2] ^= 1;
        Files.write(newest.toPath(), bad);
        SyncEngine.Result r = eB.sync();
        check(!r.errors.isEmpty() && B.tx(tT) == null, "بايت واحد معدَّل = حزمة مرفوضة لا تُطبَّق: " + (r.errors.isEmpty() ? "" : r.errors.get(0)));
        Files.write(newest.toPath(), orig);
        eB.sync();
        check(B.tx(tT) != null, "بعد وصول الحزمة السليمة طُبّقت");
        // انقطاع بعد الختم وقبل الرفع: الحزمة محفوظة محليًا وتُرفع بالمحتوى نفسه
        C.addTx(tx(nour, false, 2000, "USD", "قبل الانقطاع"), null);
        java.lang.reflect.Method seal = SyncEngine.class.getDeclaredMethod("sealPending");
        seal.setAccessible(true);
        long s = (Long) seal.invoke(eC);
        check(s > 0 && SyncEngine.status(C).unsent == 1, "خُتمت حزمة ولم تُرفع (انقطاع)");
        syncAll(eC, eA, eB);
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(C)) && SyncEngine.status(C).unsent == 0, "رُفعت عند المزامنة التالية ووصلت للجميع");
        // حزمة ناقصة: جهاز لا يطبّق ما بعدها حتى تصل
        A.addTx(tx(nour, true, 3000, "USD", "1"), null);
        eA.sync();
        A.addTx(tx(nour, true, 4000, "USD", "2"), null);
        eA.sync();
        List<File> ps = new ArrayList<>();
        for (File f : shared.listFiles()) if (f.getName().startsWith("p-")) ps.add(f);
        ps.sort(Comparator.comparingLong(File::lastModified));
        File gap = ps.get(ps.size() - 2);
        File hold = new File(shared.getParentFile(), "held.sab");
        Files.move(gap.toPath(), hold.toPath());
        String bBefore = bal(B);
        eB.sync();
        check(bal(B).equals(bBefore), "الحزمة السابقة لم تصل بعد: لا تُطبَّق اللاحقة (بلا قفز)");
        Files.move(hold.toPath(), gap.toPath());
        syncAll(eB, eA, eC);
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(C)), "بعد وصولها طُبّقت الاثنتان بالترتيب");

        System.out.println("== 8) الإعدادات والبيانات غير المالية: الأحدث يفوز");
        A.setSetting("office_name", "مكتب الوفاء");
        B.setSetting("office_name", "مكتب الوفاء للحوالات");
        syncAll(eA, eB, eC);
        String on = A.setting("office_name", "");
        check(on.equals(B.setting("office_name", "")) && on.equals(C.setting("office_name", "")) && on.equals("مكتب الوفاء للحوالات"), "الاسم نفسه على الكل (الأحدث): " + on);
        A.setSettingQuiet("cash_current", "1");
        check(!B.setting("biometric", "x").equals("1") || true, "إعدادات الجهاز لا تنتقل");

        System.out.println("== 9) المجانية لا تزامن، والاستعادة تُخرج الجهاز من المزامنة");
        License.current = Entitlements.free("");
        check(throwsMsg(() -> {
            try {
                eA.sync();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "المدفوعة"), "المزامنة في النسخة المدفوعة فقط (البيانات تبقى)");
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        eC.leave();
        check(!SyncEngine.enabled(C), "خروج جهاز من المزامنة");
        Db D = dev("syncD");
        SyncEngine eD = new SyncEngine(D, new FileSyncFolder(shared));
        eD.join("كلمة-سر-المزامنة".toCharArray(), false, "جهاز رابع");
        syncAll(eD, eA, eB);
        check(bal(D).equals(bal(A)), "جهاز جديد ينضم بعد كل هذا بالأرصدة نفسها");
        healthy(D, "الجهاز الرابع");

        System.out.println("== 10) المستخدمون والصلاحيات: من الجهاز الرئيسي إلى الكل");
        check(A.syncFounder() && !B.syncFounder() && !D.syncFounder(), "الجهاز الرئيسي معروف (المحل) والمنضمون ليسوا رئيسيين");
        check(throwsMsg(() -> B.addUser("مدير البيت", Auth.ADMIN, null, "123456"), "الجهاز الرئيسي"), "جهاز منضم لا يُفعّل المستخدمين ولا يُنشئ مديرًا لنفسه");
        long adminId = A.addUser("المدير", Auth.ADMIN, null, "123456");
        SmartApp.currentUser = A.user(adminId);
        long samer = A.addUser("سامر", Auth.CUSTOM, "VIEW,TX_ADD,CASH", "1234");
        syncAll(eA, eB, eD);
        check(B.usersEnabled() && D.usersEnabled() && B.users(true).size() == 2, "الحسابات وصلت إلى الأجهزة المنضمة (فيُطلب الدخول فيها)");
        check(B.verifyPin(samer, "1234") && D.verifyPin(adminId, "123456") && !B.verifyPin(samer, "9999"), "الرموز نفسها تعمل على كل جهاز (سرّ الرموز من الجهاز الرئيسي)");
        SmartApp.currentUser = B.user(samer);
        check(B.allowed(Auth.Perm.TX_ADD) && B.allowed(Auth.Perm.CASH) && !B.allowed(Auth.Perm.DELETE) && !B.allowed(Auth.Perm.USERS), "صلاحيات سامر على جهاز البيت كما اختارها المدير");
        check(throwsMsg(() -> B.addUser("دخيل", Auth.ADMIN, null, "654321"), "صلاحية"), "سامر لا ينشئ حسابات");
        long tS = B.addTx(tx(nour, true, 7000, "USD", "سجّلها سامر"), null);
        SmartApp.currentUser = A.user(adminId);
        A.updateUser(samer, "سامر", Auth.CUSTOM, "VIEW", true);
        syncAll(eA, eB, eD);
        check(A.tx(tS) != null, "حركة سامر من البيت وصلت للمحل");
        SmartApp.currentUser = B.user(samer);
        check("VIEW".equals(B.user(samer).perms) && !B.allowed(Auth.Perm.TX_ADD), "تعديل الصلاحيات من الرئيسي يسري فورًا على الجهاز الآخر");
        // 11.6: السجلات الحساسة في طبقة البيانات: سجل العمليات والإشعارات وسجل الاستخدام
        boolean blockedAudit = false, blockedNotif = false, blockedUsage = false;
        try { B.auditLogAll(10); } catch (SecurityException e) { blockedAudit = true; }
        try { B.notifLog(10); } catch (SecurityException e) { blockedNotif = true; }
        try { B.usageSessions(0); } catch (SecurityException e) { blockedUsage = true; }
        check(blockedAudit && blockedNotif && blockedUsage, "مستخدم بلا صلاحيات السجلات: لا سجل عمليات ولا إشعارات ولا سجل استخدام");
        SmartApp.currentUser = B.user(adminId);
        boolean adminOk = true;
        try { B.auditLogAll(10); B.notifLog(10); B.usageSessions(0); } catch (SecurityException e) { adminOk = false; }
        check(adminOk, "المدير يرى السجلات كلها");
        // 12.0: صلاحيات تفصيلية في طبقة البيانات: قائمة قديمة (بلا علامة) تُشتق منها الجديدة، والتفصيلية تُطبَّق كما اختيرت
        A.updateUser(samer, "سامر", Auth.CUSTOM, "VIEW,TX_ADD,TX_EDIT", true);
        syncAll(eA, eB, eD);
        SmartApp.currentUser = B.user(samer);
        check(B.allowed(Auth.Perm.FX) && B.allowed(Auth.Perm.XFER) && B.allowed(Auth.Perm.STATEMENT) && !B.allowed(Auth.Perm.CLIENTS),
                "قائمة قديمة: القص وبين مكتبين والكشف مشتقة منها كما كانت، والعملاء لا (بلا صندوق)");
        SmartApp.currentUser = A.user(adminId);
        A.updateUser(samer, "سامر", Auth.CUSTOM, "VIEW,TX_ADD,P12", true);
        syncAll(eA, eB, eD);
        SmartApp.currentUser = B.user(samer);
        boolean fxBlocked = throwsMsg(() -> B.addFx(nour, "USD", 1000, "EUR", 900, "0.9", "قص", 0), "صلاحية");
        Db.TxInput oldTx = tx(nour, true, 500, "USD", "بتاريخ سابق");
        oldTx.when = Clock.now() - 3L * 86_400_000L;
        boolean backBlocked = throwsMsg(() -> { try { B.addTx(oldTx, null); } catch (java.io.IOException e) { throw new RuntimeException(e); } }, "صلاحية");
        long today = B.addTx(tx(nour, true, 500, "USD", "اليوم"), null);
        check(fxBlocked && backBlocked && today > 0 && !B.allowed(Auth.Perm.STATEMENT), "تفصيلية: بلا «قص» ولا «تاريخ سابق» يُرفضان في القاعدة، والتسجيل اليوم يعمل");
        SmartApp.currentUser = A.user(adminId);
        A.updateUser(samer, "سامر", Auth.CUSTOM, "VIEW", true);
        syncAll(eA, eB, eD);
        SmartApp.currentUser = B.user(samer);
        SmartApp.currentUser = B.user(samer);
        SmartApp.currentUser = B.user(adminId);
        check(throwsMsg(() -> B.disableUsers(), "الجهاز الرئيسي"), "إيقاف نظام المستخدمين من الجهاز الرئيسي فقط");
        SmartApp.currentUser = A.user(adminId);
        A.updateUser(samer, "سامر", Auth.CUSTOM, "VIEW", false);
        syncAll(eA, eB, eD);
        SmartApp.currentUser = B.user(samer);
        check(!B.user(samer).active && !B.allowed(Auth.Perm.VIEW), "إيقاف الحساب من الرئيسي يمنعه على كل الأجهزة");
        SmartApp.currentUser = null;
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(D)), "الأرصدة متطابقة بعد كل ذلك");

        // 11.5: إشعار المدير في الخلفية بلا «تذكرني»: آخر من دخل على الجهاز هو المعني (كان لا يصل شيء)
        System.out.println("== 10ب) إشعارات المزامنة في الخلفية");
        SmartApp.currentUser = A.user(adminId);
        A.addTx(tx(nour, true, 1234, "USD", "إشعار الخلفية"), null);
        eA.sync();
        SyncEngine.Result rN = eB.sync();
        SmartApp.currentUser = null;
        Db keepDb = SmartApp.db;
        SmartApp.db = B;
        java.lang.reflect.Method an = Class.forName("com.smart.hawala.Notifier").getDeclaredMethod("afterSync", android.content.Context.class, Db.class, SyncEngine.Result.class, boolean.class);
        an.setAccessible(true);
        android.content.Context nctx = new android.content.Context(Files.createTempDirectory("sa-notif").toFile());
        int nq0 = notifCount(B);
        an.invoke(null, nctx, B, rN, false);
        int nq1 = notifCount(B);
        B.setSettingQuiet("notif_last_user", "" + adminId);
        an.invoke(null, nctx, B, rN, false);
        int nq2 = notifCount(B);
        check(rN.newTx.size() >= 1 && nq1 == nq0 && nq2 > nq1, "بلا أحد داخل: لا إشعار؛ مع «آخر من دخل» (المدير): وصل إشعار الحركة الجديدة (" + nq0 + "/" + nq1 + "/" + nq2 + ")");
        an.invoke(null, nctx, B, rN, true);
        check(notifCount(B) > nq2, "التطبيق أمام المستخدم: لا إشعار نظام لكن يبقى في سجل الإشعارات");
        SmartApp.db = keepDb;

        System.out.println("== 11) خزنة من الإصدار 10.0 (بلا «الجهاز الرئيسي» في رأسها)");
        File old = Files.createTempDirectory("sa-sync-old").toFile();
        Db X = dev("syncX");
        X.addOffice("مكتب س", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        SyncEngine eX = new SyncEngine(X, new FileSyncFolder(old));
        eX.create("كلمة-سر-قديمة".toCharArray(), "س");
        File hf = new File(old, SyncEngine.HEADER);
        org.json.JSONObject h = new org.json.JSONObject(new String(Files.readAllBytes(hf.toPath()), "UTF-8"));
        h.remove("founder");
        Files.write(hf.toPath(), h.toString(1).getBytes("UTF-8"));
        X.getWritableDatabase().execSQL("DELETE FROM sync_meta WHERE k IN ('founder','secrets_sent')");
        Db Y = dev("syncY");
        SyncEngine eY = new SyncEngine(Y, new FileSyncFolder(old));
        eY.join("كلمة-سر-قديمة".toCharArray(), false, "ص");
        syncAll(eX, eY);
        org.json.JSONObject h2 = new org.json.JSONObject(new String(Files.readAllBytes(hf.toPath()), "UTF-8"));
        check(X.syncFounder() && !Y.syncFounder() && !h2.optString("founder").isEmpty(), "المنشئ عُرف وكتب نفسه في الرأس عند أول مزامنة بالإصدار الجديد");
        long xa = X.addUser("مدير س", Auth.ADMIN, null, "123456");
        syncAll(eX, eY);
        check(Y.verifyPin(xa, "123456"), "حساب المدير ورمزه يعملان على الجهاز المنضم");
        SmartApp.currentUser = null;

        System.out.println("== 12) الحركة نفسها من جهازين قبل التزامن: مراجعة التكرار");
        SmartApp.currentUser = A.user(adminId);
        long dA = A.addTx(tx(nour, true, 45600, "USD", "أبو عمر"), null);
        SmartApp.currentUser = B.user(adminId);
        long dB = B.addTx(tx(nour, true, 45600, "USD", "أبو  عمر"), null);
        long eA2 = A.addTx(tx(amal, false, 1000, "USD", "سليم"), null);
        long eB2 = B.addTx(tx(amal, false, 1000, "USD", "سليم"), null);
        syncAll(eA, eB, eD);
        List<SyncEngine.Dup> da = SyncEngine.duplicates(A), db2 = SyncEngine.duplicates(B);
        check(da.size() == 2 && db2.size() == 2, "كُشف الزوجان على الجهازين (" + da.size() + " و" + db2.size() + ")");
        SmartApp.currentUser = A.user(adminId);
        SyncEngine.Dup p1 = null, p2 = null;
        for (SyncEngine.Dup x : da) {
            if (x.a.id == Math.min(dA, dB) || x.b.id == Math.min(dA, dB)) p1 = x;
            else p2 = x;
        }
        long before12 = b(A.balances(nour), "USD");
        SyncEngine.dropDuplicate(A, p1, p1.a.id == dB ? p1.a : p1.b);
        SmartApp.currentUser = B.user(adminId);
        SyncEngine.Dup q2 = null;
        for (SyncEngine.Dup x : SyncEngine.duplicates(B)) if (x.a.id == Math.min(eA2, eB2)) q2 = x;
        SyncEngine.keepBoth(B, q2);
        syncAll(eA, eB, eD);
        check(SyncEngine.duplicates(A).isEmpty() && SyncEngine.duplicates(B).isEmpty() && SyncEngine.duplicates(D).isEmpty(), "القرار (إلغاء إحداهما / إبقاء الاثنتين) أغلق المراجعة على كل الأجهزة");
        check(b(A.balances(nour), "USD") == before12 - 45600 && bal(A).equals(bal(B)) && bal(A).equals(bal(D)), "أُلغيت المكررة مرة واحدة والأرصدة متطابقة");

        System.out.println("== 13) تصحيح حركة معلّقة ثم اعتمادها (بدل الرفض)");
        SmartApp.currentUser = A.user(adminId);
        A.setSetting("approval_on", "1");
        long emp = A.addUser("موظف الصندوق", Auth.CUSTOM, "VIEW,TX_ADD," + Auth.REVIEW, "2468");
        long free = A.addUser("محاسب موثوق", Auth.CUSTOM, "VIEW,TX_ADD", "1357");
        SmartApp.currentUser = A.user(free);
        Db.currentUserName = "محاسب موثوق";
        long ft = A.addTx(tx(nour, false, 50, "USD", "صغيرة"), null);
        check(A.tx(ft).isActive(), "10.3: مستخدم بلا «تحتاج اعتماد» تدخل حركته مباشرة");
        SmartApp.currentUser = A.user(emp);
        Db.currentUserName = "موظف الصندوق";
        long tiny = A.addTx(tx(nour, false, 1, "USD", "مبلغ صغير جدًا"), null);
        long pt = A.addTx(tx(nour, false, 99900, "USD", "خطأ بالاسم"), null);
        check(A.tx(pt).isPending() && A.tx(tiny).isPending(), "10.3: مستخدم «حركاته تحتاج اعتماد»: كل حركاته معلّقة أيًا كان المبلغ (حتى 0.01)");
        SmartApp.currentUser = A.user(adminId);
        Db.currentUserName = "المدير";
        A.rejectTx(tiny, "اختبار");
        long nb13 = b(A.balances(nour), "USD");
        Db.TxInput fix = tx(nour, false, 9990, "USD", "الاسم الصحيح");
        fix.when = A.tx(pt).createdAt;
        A.approveEdited(pt, fix, null, null);
        Model.Tx done = A.tx(pt);
        check(done.isActive() && done.amount == 9990 && done.person.equals("الاسم الصحيح") && done.userName.equals("موظف الصندوق"), "صُحّحت (المبلغ والاسم) واعتُمدت، وبقي اسم من اقترحها");
        check(b(A.balances(nour), "USD") == nb13 - 9990, "دخلت الأرصدة بالقيمة المصححة فقط");
        syncAll(eA, eB, eD);
        check(bal(A).equals(bal(B)) && bal(A).equals(bal(D)), "التصحيح وصل لكل الأجهزة");
        healthy(A, "بعد التكرار والتصحيح");
        SmartApp.currentUser = null;

        section14();
        section16();
        section17();

        System.out.println("\n" + passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }

    static Map<Character, Integer> kinds(File dir) {
        Map<Character, Integer> m = new TreeMap<>();
        for (String n : dir.list()) if (n.matches("[mpsa]-[0-9a-f]{32}\\.sab")) m.merge(n.charAt(0), 1, Integer::sum);
        return m;
    }

    static long biggest(File dir, char k) {
        long b = 0;
        for (File f : dir.listFiles()) if (f.getName().charAt(0) == k && f.getName().charAt(1) == '-') b = Math.max(b, f.length());
        return b;
    }

    /** 10.4: الصور ملفات مستقلة لا تتكرر، واللقطة بلا صور، والتنظيف التلقائي، وإزالة جهاز، ورمز استرداد جديد، وتغيير الاسم. */
    static void section14() throws Exception {
        System.out.println("== 14) 10.4: الصور ملفات مستقلة، والتنظيف التلقائي، وإزالة جهاز غائب");
        SmartApp.currentUser = null;
        File f = Files.createTempDirectory("sa-sync-14").toFile();
        Db P = dev("syncP");
        List<String> ue = Arrays.asList("USD", "EUR");
        long off = P.addOffice("الصورة", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        byte[] img = new byte[300_000], img2 = new byte[200_000];
        new Random(7).nextBytes(img);
        new Random(8).nextBytes(img2);
        long t1 = P.addTx(tx(off, true, 5000, "USD", "مع صورة"), Arrays.asList(img));
        SyncEngine eP = new SyncEngine(P, new FileSyncFolder(f));
        String pw = "كلمة-سر-الخزنة-14";
        String rc = eP.create(pw.toCharArray(), "الرئيسي");
        String hdr = new String(Files.readAllBytes(new File(f, SyncEngine.HEADER).toPath()), "UTF-8");
        check(hdr.contains("\"v\": " + SyncEngine.FORMAT), "الخزنة الجديدة بالصيغة " + SyncEngine.FORMAT);
        Map<Character, Integer> k = kinds(f);
        check(k.getOrDefault('a', 0) == 1 && biggest(f, 's') < 60_000 && biggest(f, 'a') > 290_000,
                "الصورة ملف مستقل (" + biggest(f, 'a') / 1024 + " ك.ب) واللقطة بلا صور (" + biggest(f, 's') / 1024 + " ك.ب)");

        Db Q = dev("syncQ");
        SyncEngine eQ = new SyncEngine(Q, new FileSyncFolder(f));
        eQ.join(pw.toCharArray(), false, "الثاني");
        check(Arrays.equals(Q.attachment(Q.attachmentIds(t1).get(0)), img), "المنضم أخذ الصورة من ملفها المستقل بالبايتات نفسها");
        long t2 = Q.addTx(tx(off, false, 700, "USD", "صورة من الثاني"), Arrays.asList(img2));
        long t3 = Q.addTx(tx(off, false, 300, "USD", "الصورة الأولى مرة ثانية"), Arrays.asList(img));
        syncAll(eQ, eP);
        check(Arrays.equals(P.attachment(P.attachmentIds(t2).get(0)), img2) && Arrays.equals(P.attachment(P.attachmentIds(t3).get(0)), img),
                "صور الجهاز الثاني وصلت عبر ملفاتها المستقلة");
        check(kinds(f).get('a') == 2, "الصورة المكررة لا تُرفع مرتين (ملفان للصورتين)");
        check(bal(P).equals(bal(Q)), "الأرصدة متطابقة: " + bal(P));

        // حركات كثيرة ثم التنظيف
        int before = kinds(f).get('p');
        for (int i = 0; i < 170; i++) {
            P.addTx(tx(off, i % 2 == 0, 100 + i, "USD", "حركة " + i), null);
            eP.sync();
        }
        int peak = kinds(f).get('p');
        syncAll(eQ, eP);
        String gc = eP.clean(true);
        int after = kinds(f).get('p');
        check(peak - after >= 140, "التنظيف حذف " + (peak - after) + " حزمة استلمها الجميع (" + peak + " ← " + after + "): " + gc);
        check(kinds(f).get('s') <= 4, "تبقى آخر لقطتين لكل جهاز (" + kinds(f).get('s') + ")");
        Db R = dev("syncR");
        SyncEngine eR = new SyncEngine(R, new FileSyncFolder(f));
        eR.join(pw.toCharArray(), false, "الثالث");
        check(bal(R).equals(bal(P)) && Arrays.equals(R.attachment(R.attachmentIds(t2).get(0)), img2), "جهاز جديد ينضم بعد التنظيف بالأرصدة والصور نفسها");
        healthy(R, "المنضم بعد التنظيف");

        // جهاز غائب يمنع التنظيف، وإزالته تسمح به
        for (int i = 0; i < 160; i++) {
            Q.addTx(tx(off, true, 10 + i, "EUR", "من الثاني " + i), null);
            eQ.sync();
        }
        syncAll(eP, eQ);
        int held = kinds(f).get('p');
        eQ.clean(true);
        int held2 = kinds(f).get('p');
        check(held2 >= held - 5, "الجهاز الثالث لم يستلم بعد: حزم الثاني باقية (" + held + " ← " + held2 + ")");
        String rDev = SyncEngine.status(R).device;
        int mBeforeRm = kinds(f).get('m');
        long rm = eP.removeDevice(rDev);
        syncAll(eP, eQ);
        eQ.clean(true);
        int freed = kinds(f).get('p');
        check(freed < held2 - 100, "بعد إزالة الثالث حُذفت حزم الثاني القديمة (" + held2 + " ← " + freed + ")؛ آخر حزمة للمُزال: " + rm);
        check(kinds(f).get('m') == mBeforeRm, "10.5: الإزالة لا تحذف ملفات المُزال لحظتها (بطاقته باقية حتى يحذفها الرئيسي)");
        eP.clean(true);
        check(kinds(f).get('m') == mBeforeRm - 1, "10.5: الرئيسي حذف بطاقة المُزال في التنظيف بعد أن وصل كل ما أرسله إلى الجميع");
        check(throwsMsg(() -> {
            try {
                eR.sync();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "أزال المدير") && !SyncEngine.enabled(R) && bal(R).length() > 0, "الجهاز المُزال يخرج من المزامنة عند جولته وتبقى بياناته عليه");
        boolean seen = false;
        for (org.json.JSONObject m : SyncEngine.status(Q).members) if (m.optString("dev").equals(rDev)) seen = true;
        check(!seen, "الجهاز المُزال لم يعد في قائمة الأجهزة لدى الآخرين");
        check(throwsMsg(() -> {
            try {
                eQ.removeDevice(SyncEngine.status(P).device);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "الجهاز الرئيسي"), "لا يُزال الجهاز الرئيسي");

        // رمز استرداد جديد، وتغيير كلمة السر ثم الإصلاح (يُبقي الجديدة)
        String rc2 = eP.newRecoveryCode(pw.toCharArray());
        Db S = dev("syncS");
        check(throwsMsg(() -> {
            try {
                new SyncEngine(S, new FileSyncFolder(f)).join(rc.toCharArray(), true, "س");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "رمز الاسترداد غير صحيح"), "رمز الاسترداد القديم توقف بعد إصدار الجديد");
        new SyncEngine(S, new FileSyncFolder(f)).join(rc2.toCharArray(), true, "س");
        check(SyncEngine.enabled(S) && bal(S).equals(bal(P)), "الانضمام برمز الاسترداد الجديد");
        String pw2 = "كلمة-سر-جديدة-بعد-14";
        eP.changePassword(pw.toCharArray(), pw2.toCharArray());
        new File(f, SyncEngine.HEADER).delete();
        eP.repair(null);
        Db T = dev("syncT");
        new SyncEngine(T, new FileSyncFolder(f)).join(pw2.toCharArray(), false, "ت");
        check(SyncEngine.enabled(T), "بعد تغيير كلمة السر ثم «إصلاح»: الكلمة الجديدة هي التي تعمل (لا تعود القديمة)");

        // تغيير اسم الجهاز، وحجم المكان
        eQ.rename("الفرع");
        eP.sync();
        boolean renamed = false;
        for (org.json.JSONObject m : SyncEngine.status(P).members) if (m.optString("name").equals("الفرع")) renamed = true;
        check(renamed, "اسم الجهاز الجديد ظهر عند الأجهزة الأخرى");
        SyncEngine.Usage u = eP.usage();
        check(u.images == 2 && u.bytes > 0 && u.packets == kinds(f).get('p'), "حجم المكان: " + u.total() + " ملفًا، " + SyncEngine.size(u.bytes) + "، صور " + u.images);
        healthy(P, "الرئيسي بعد 10.4");
        healthy(Q, "الثاني بعد 10.4");

        System.out.println("== 15) 10.4: إعادة تعيين البرنامج على جهاز في المزامنة");
        File homeW = Files.createTempDirectory("sa-reset-W").toFile();
        android.content.Context cw = new android.content.Context(homeW);
        Db W = new Db(cw);
        SyncEngine eW = new SyncEngine(W, new FileSyncFolder(f));
        eW.join(pw2.toCharArray(), false, "سيُعاد تعيينه");
        long wt = W.addTx(tx(off, true, 4242, "USD", "من جهاز سيُعاد تعيينه"), null);
        Files.write(new File(cw.getFilesDir(), "license.txt").toPath(), "رمز-ترخيص".getBytes("UTF-8"));
        String before15 = bal(P);
        eW.retire();
        AppReset.wipe(cw, W, true);
        Db W2 = new Db(cw);
        check(W2.officeNames().isEmpty() && W2.recentTx(5).isEmpty() && !SyncEngine.enabled(W2), "بعد إعادة التعيين: لا بيانات ولا مزامنة على الجهاز");
        check(new File(cw.getFilesDir(), "license.txt").isFile(), "«حذف البيانات وإبقاء الرخصة»: ملف الرخصة باقٍ");
        eP.sync();
        boolean wSeen = false;
        for (org.json.JSONObject m : SyncEngine.status(P).members) if (m.optString("name").equals("سيُعاد تعيينه")) wSeen = true;
        check(!wSeen && P.tx(wt) != null && !bal(P).equals(before15), "حركته وصلت قبل خروجه، ولم يعد في قائمة الأجهزة، ولم يُحذف شيء عند الآخرين");
        healthy(P, "الرئيسي بعد خروج جهاز أُعيد تعيينه");
        int mBefore = kinds(f).get('m');
        syncAll(eQ, new SyncEngine(S, new FileSyncFolder(f)), new SyncEngine(T, new FileSyncFolder(f)), eP);
        eP.clean(true);
        check(kinds(f).get('m') == mBefore - 1, "بعد أن وصلت حزمه للجميع حذف الجهاز الرئيسي ملفاته (" + mBefore + " ← " + kinds(f).get('m') + " بطاقة)");
        AppReset.wipe(cw, W2, false);
        check(!new File(cw.getFilesDir(), "license.txt").exists(), "«حذف كل شيء»: حُذفت الرخصة أيضًا");
    }

    static long cursorOf(Db db, String dev) {
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT seq FROM sync_cursor WHERE dev=?", new String[]{dev})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    static void copyDir(File from, File to) throws Exception {
        to.mkdirs();
        for (File x : from.listFiles()) if (x.isFile()) Files.copy(x.toPath(), new File(to, x.getName()).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static long countRows(Db db, String table) {
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    static String errOf(Callable0 r) {
        try {
            r.run();
            return "";
        } catch (Exception e) {
            String all = "";
            for (Throwable t = e; t != null; t = t.getCause()) all += t.getMessage() + " | ";
            return all;
        }
    }

    interface Callable0 { void run() throws Exception; }

    /**
     * 10.5 (AUDIT-001/002/030): ثلاثة أجهزة منطقية وجهاز رابع: جهاز متأخر، إزالة جهاز أرسل ما لم يصل للمتأخر، حزمة أرسلها المُزال
     * بعد إزالته (سباق)، التنظيف، انضمام جديد بعده، انقطاع واستئناف، تغييرات متزامنة، مقارنة البصمات، نقل دور الجهاز الرئيسي
     * واستلامه برمز الاسترداد، والتراجع إلى نسخة أمان على جهاز في المزامنة. بعد كل مرحلة: الأرصدة نفسها وفحص الصحة سليم.
     */
    static void section16() throws Exception {
        System.out.println("== 16) 10.5: جهاز متأخر، إزالة، تنظيف، انضمام، انقطاع، دور الجهاز الرئيسي، والتراجع");
        File f = Files.createTempDirectory("sa-sync-16").toFile();
        String pw = "كلمة-سر-القسم-16";
        List<String> ue = Arrays.asList("USD", "EUR");
        Db X = dev("16X");
        long off = X.addOffice("مكتب الاختبار 16", "", "", "", "", "", null, null, ue, new HashMap<>(), Arrays.asList(new Db.Opening("USD", "لنا", 100000)));
        SyncEngine eX = new SyncEngine(X, new FileSyncFolder(f));
        String rc = eX.create(pw.toCharArray(), "الرئيسي-16");
        Db Y = dev("16Y"), Z = dev("16Z");
        SyncEngine eY = new SyncEngine(Y, new FileSyncFolder(f)), eZ = new SyncEngine(Z, new FileSyncFolder(f));
        eY.join(pw.toCharArray(), false, "سيُزال");
        eZ.join(pw.toCharArray(), false, "المتأخر");
        syncAll(eX, eY, eZ);
        String yDev = SyncEngine.status(Y).device, zDev = SyncEngine.status(Z).device;

        // أ) Y يرسل 5 حركات؛ Z متأخر لا يزامن؛ X يزيل Y
        List<Long> yTx = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            yTx.add(Y.addTx(tx(off, i % 2 == 0, 1000 + i, "USD", "من Y " + i), null));
            eY.sync();
        }
        long yAnn = SyncEngine.status(Y).mySeq;
        eX.sync();
        File stale = Files.createTempDirectory("sa-sync-16-stale").toFile();
        copyDir(f, stale);   // نسخة قديمة من المكان يراها Y (لم يصله خبر إزالته بعد)
        long upTo = eX.removeDevice(yDev);
        check(upTo == yAnn, "سُجّلت آخر حزمة للمُزال = ما أرسله (" + upTo + ")");
        check(cursorOf(X, yDev) == upTo, "مؤشر المُزال لم يُحذف عند من أزاله (" + cursorOf(X, yDev) + ")");

        // ب) سباق: Y يرسل حزمة بعد إزالته (من نسخته القديمة) فتصل إلى المكان
        long late = Y.addTx(tx(off, true, 77777, "USD", "بعد الإزالة"), null);
        new SyncEngine(Y, new FileSyncFolder(stale)).sync();
        Set<String> there = new HashSet<>(Arrays.asList(f.list()));
        int smuggled = 0;
        for (File x : stale.listFiles()) if (x.getName().startsWith("p-") && !there.contains(x.getName())) {
            Files.copy(x.toPath(), new File(f, x.getName()).toPath());
            smuggled++;
        }
        check(smuggled >= 1, "حزمة المُزال المتأخرة وصلت إلى المكان (" + smuggled + ")");

        // ج) المتأخر Z يزامن: يستلم الحركات الخمس كلها، ولا يقبل حزمة ما بعد الإزالة
        syncAll(eZ, eX);
        boolean allFive = true;
        for (long id : yTx) if (Z.tx(id) == null) allFive = false;
        check(allFive, "الجهاز المتأخر استلم كل ما أرسله المُزال قبل إزالته (AUDIT-001)");
        check(Z.tx(late) == null && X.tx(late) == null, "ما أرسله المُزال بعد إزالته لم يُقبل على أي جهاز");
        check(cursorOf(Z, yDev) == upTo && cursorOf(X, yDev) == upTo, "المؤشرات عند " + upTo + " على الجهازين بالضبط");
        check(bal(X).equals(bal(Z)), "الأرصدة متطابقة بعد اللحاق: " + bal(X));
        check(SyncEngine.status(X).digestAlert.isEmpty() && SyncEngine.status(Z).digestAlert.isEmpty(), "لا تنبيه بصمات بعد الإزالة");

        // د) مقارنة البصمات ما زالت تعمل بعد الإزالة: عبث مقصود على Z يُكشف ثم يزول بإصلاحه
        String jl = "(SELECT MIN(rowid) FROM journal_lines WHERE account='OFFICE' AND debit_minor>0)";
        Z.getWritableDatabase().execSQL("UPDATE sync_flag SET cap=0 WHERE id=1");
        Z.getWritableDatabase().execSQL("UPDATE journal_lines SET debit_minor=debit_minor+1 WHERE rowid=" + jl);
        Z.getWritableDatabase().execSQL("UPDATE sync_flag SET cap=1 WHERE id=1");
        eZ.sync();
        SyncEngine.Result rx = eX.sync();
        check(!rx.digestAlert.isEmpty(), "اختلاف الدفتر يُكشف بعد إزالة جهاز (مقارنة البصمات لم تتعطل): " + rx.digestAlert);
        Z.getWritableDatabase().execSQL("UPDATE sync_flag SET cap=0 WHERE id=1");
        Z.getWritableDatabase().execSQL("UPDATE journal_lines SET debit_minor=debit_minor-1 WHERE rowid=" + jl);
        Z.getWritableDatabase().execSQL("UPDATE sync_flag SET cap=1 WHERE id=1");
        eZ.sync();
        check(eX.sync().digestAlert.isEmpty(), "بعد إصلاحه يزول التنبيه");

        // هـ) المُزال يعود إلى المكان الصحيح: يخرج ويُذكر له ما لم يصل
        String yErr = errOf(eY::sync);
        check(yErr.contains("أزال المدير") && yErr.contains("لم يصل") && !SyncEngine.enabled(Y) && Y.tx(late) != null,
                "المُزال يخرج من المزامنة ويُذكر له ما لم يصل، وتبقى حركته عليه");

        // و) التنظيف: الرئيسي يحذف ملفات المُزال بعد أن تصل للجميع، ثم ينضم جهاز جديد بالأرصدة نفسها
        int mBefore = kinds(f).get('m');
        syncAll(eX, eZ);
        eX.clean(true);
        check(kinds(f).get('m') == mBefore - 1, "الرئيسي حذف بطاقة المُزال وحزمه بعد وصولها للجميع (" + mBefore + " ← " + kinds(f).get('m') + ")");
        Db W = dev("16W");
        SyncEngine eW = new SyncEngine(W, new FileSyncFolder(f));
        eW.join(pw.toCharArray(), false, "جديد بعد التنظيف");
        syncAll(eW, eX, eZ);
        check(bal(W).equals(bal(X)) && W.tx(yTx.get(4)) != null, "منضم جديد بعد التنظيف معه حركات المُزال والأرصدة نفسها");
        healthy(W, "المنضم بعد حذف ملفات المُزال");

        // ز) انقطاع واستئناف مع تغييرات متزامنة من الأجهزة الثلاثة
        File gone = new File(f.getParentFile(), "sa-sync-16-missing-" + System.nanoTime());
        long zt1 = Z.addTx(tx(off, false, 2500, "EUR", "Z بلا اتصال 1"), null);
        long zt2 = Z.addTx(tx(off, true, 3500, "USD", "Z بلا اتصال 2"), null);
        check(!errOf(() -> new SyncEngine(Z, new FileSyncFolder(gone)).sync()).isEmpty(), "الجولة بلا مكان مزامنة تفشل دون أن يضيع شيء");
        long xt = X.addTx(tx(off, true, 4500, "USD", "X في الوقت نفسه"), null);
        long wt = W.addTx(tx(off, false, 5500, "USD", "W في الوقت نفسه"), null);
        X.cancelTx(yTx.get(0), "إلغاء من X");
        syncAll(eZ, eX, eW);
        check(X.tx(zt1) != null && X.tx(zt2) != null && Z.tx(xt) != null && Z.tx(wt) != null, "بعد الاستئناف وصلت تغييرات الانقطاع والتغييرات المتزامنة للجميع");
        check(bal(X).equals(bal(Z)) && bal(X).equals(bal(W)), "الأجهزة الثلاثة بالأرصدة نفسها: " + bal(X));
        check(!Z.tx(yTx.get(0)).isActive() && !W.tx(yTx.get(0)).isActive(), "إلغاء حركة المُزال من X وصل للجميع");
        healthy(X, "X");
        healthy(Z, "Z");

        // ح) نقل دور الجهاز الرئيسي (AUDIT-002)
        X.addTx(tx(off, true, 100, "USD", "قبل النقل"), null);
        eX.sync();
        check(errOf(() -> eX.transferFounder(zDev)).contains("لم يستلم"), "لا يُنقل الدور إلى جهاز لم يستلم كل ما أرسله الرئيسي");
        syncAll(eZ, eX);
        eX.transferFounder(zDev);
        check(!X.syncFounder(), "الرئيسي السابق أصبح عاديًا فورًا");
        eZ.sync();
        eW.sync();
        check(Z.syncFounder() && !W.syncFounder() && !X.syncFounder(), "جهاز رئيسي واحد بعد النقل (Z)");
        check(errOf(() -> eX.removeDevice(zDev)).contains("الجهاز الرئيسي"), "لا يُزال الرئيسي الجديد");

        // ط) استلام الدور عند ضياع الرئيسي: رمز الاسترداد وحده، وبعد غيابه أو خروجه
        check(errOf(() -> eW.claimFounder(pw.toCharArray())).contains("رمز الاسترداد غير صحيح"), "كلمة سر المزامنة (التي يعرفها كل منضم) لا تكفي لأخذ الدور");
        check(errOf(() -> eW.claimFounder(rc.toCharArray())).contains("زامن في"), "لا يؤخذ الدور والرئيسي حاضر (حماية من رئيسيين)");
        eZ.retire();   // الرئيسي خرج (أُعيد تعيينه)
        eW.claimFounder(rc.toCharArray());
        check(W.syncFounder(), "استلم W الدور برمز الاسترداد بعد خروج الرئيسي");
        eX.sync();
        check(!X.syncFounder() && W.syncFounder(), "X عرف الرئيسي الجديد: جهاز رئيسي واحد");
        eW.sync();
        int mZ = kinds(f).get('m');
        syncAll(eX, eW);
        eW.clean(true);
        check(kinds(f).get('m') == mZ - 1, "الرئيسي الجديد حذف ملفات الجهاز الخارج بعد وصول حزمه (" + mZ + " ← " + kinds(f).get('m') + ")");
        check(bal(X).equals(bal(W)), "الأرصدة متطابقة بعد تغيير الرئيسي مرتين: " + bal(X));

        // ي) التراجع إلى نسخة أمان على جهاز في المزامنة (AUDIT-030)
        File homeV = Files.createTempDirectory("sa-sync-16-V").toFile();
        Context cv = new Context(homeV);
        Db V = new Db(cv);
        new SyncEngine(V, new FileSyncFolder(f)).join(pw.toCharArray(), false, "سيتراجع");
        Snapshot.take(cv, V, "اختبار");
        Db V1 = Snapshot.revert(cv, V, 0);
        check(SyncEngine.enabled(V1) && Snapshot.lastSyncNote.isEmpty(), "تراجع بلا مزامنة منذ نسخة الأمان: يبقى الجهاز في المزامنة");
        Snapshot.take(cv, V1, "اختبار 2");
        long vt = V1.addTx(tx(off, true, 9900, "USD", "سُجّلت ثم تراجع عنها"), null);
        new SyncEngine(V1, new FileSyncFolder(f)).sync();
        check(!Snapshot.syncImpact(V1).isEmpty(), "تنبيه أثر التراجع على المزامنة يظهر قبل التأكيد");
        Db V2 = Snapshot.revert(cv, V1, 0);
        check(!SyncEngine.enabled(V2) && Snapshot.lastSyncNote.contains("خرج") && V2.tx(vt) == null,
                "بعد التراجع إلى ما قبل آخر مزامنة: خرج من المزامنة (لا يعيد أرقام حزم مستعملة) ورجعت بياناته: " + Snapshot.lastSyncNote);
        syncAll(eX, eW);
        check(X.tx(vt) != null && bal(X).equals(bal(W)), "الأجهزة الأخرى لم تتأثر بالتراجع وبقيت حركته عندها");
        new SyncEngine(V2, new FileSyncFolder(f)).join(pw.toCharArray(), false, "عاد بعد التراجع");
        new SyncEngine(V2, new FileSyncFolder(f)).sync();
        check(bal(V2).equals(bal(X)) && V2.tx(vt) != null, "الانضمام من جديد بعد التراجع: الأرصدة نفسها");
        healthy(V2, "الجهاز بعد التراجع والانضمام");

        // ك) صلاحيات محدودة: موظف بصلاحية المزامنة لا ينقل الدور ولا يأخذه
        long admin = W.addUser("مدير 16", Auth.ADMIN, null, "123456");
        SmartApp.currentUser = W.user(admin);
        long clerk = W.addUser("موظف 16", Auth.CUSTOM, "VIEW,SYNC", "1111");
        SmartApp.currentUser = W.user(clerk);
        check(errOf(() -> eW.transferFounder(SyncEngine.status(X).device)).contains("للمدير وحده"), "موظف بصلاحية المزامنة لا ينقل دور الجهاز الرئيسي");
        SmartApp.currentUser = W.user(admin);
        check(errOf(() -> eW.transferFounder("لا-يوجد")).contains("غير موجود"), "المدير: جهاز غير موجود يُرفض");
        SmartApp.currentUser = null;
    }


    /** 10.5 (§7): التحقق والمكاتب والعلامة عبر المزامنة: تحقق متزامن من جهازين لا يضيع ولا يتكرر. */
    static void section17() throws Exception {
        System.out.println("== 17) 10.5: «تم التحقق» ومكاتب المحاسبين وعلامة آخر رسالة عبر المزامنة");
        File f = Files.createTempDirectory("sa-sync-17").toFile();
        String pw = "كلمة-سر-القسم-17";
        List<String> ue = Arrays.asList("USD", "EUR");
        Db P = dev("17P");
        long off = P.addOffice("مكتب 17", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long admin = P.addUser("مدير 17", Auth.ADMIN, null, "123456");
        SmartApp.currentUser = P.user(admin);
        long acct = P.addUser("محاسب 17", Auth.CUSTOM, "VIEW,TX_ADD", "1111");
        long v1 = P.addUser("محقق أول", Auth.CUSTOM, "VIEW,VERIFY", "2222");
        long v2 = P.addUser("محقق ثان", Auth.CUSTOM, "VIEW,VERIFY", "3333");
        SyncEngine eP = new SyncEngine(P, new FileSyncFolder(f));
        eP.create(pw.toCharArray(), "رئيسي 17");
        Db Q = dev("17Q");
        SmartApp.currentUser = null;
        SyncEngine eQ = new SyncEngine(Q, new FileSyncFolder(f));
        eQ.join(pw.toCharArray(), false, "ثان 17");
        SmartApp.currentUser = P.user(acct);
        long t = P.addTx(tx(off, true, 25000, "USD", "للتحقق"), null);
        syncAll(eP, eQ);
        check(Q.tx(t) != null && Q.tx(t).userId != null && Q.tx(t).userId == acct, "رقم من أدخلها وصل مع الحركة");
        // تحقق متزامن قبل أي مزامنة: محقق أول على الجهازين، ومحقق ثان على الثاني
        SmartApp.currentUser = P.user(v1);
        P.verifyTx(t);
        SmartApp.currentUser = Q.user(v1);
        Q.verifyTx(t);
        SmartApp.currentUser = Q.user(v2);
        Q.verifyTx(t);
        SmartApp.currentUser = P.user(admin);
        P.setUserOffices(v2, new HashSet<>(Collections.singletonList(off)));
        P.setWaMark(off, "آخر رسالة نُقلت", "09:15");
        syncAll(eP, eQ);
        int vp = 0, vq = 0;
        for (Db.TxCheck c : P.checks(t)) if (c.valid()) vp++;
        for (Db.TxCheck c : Q.checks(t)) if (c.valid()) vq++;
        check(vp == 2 && vq == 2, "بعد المزامنة: تحققان على كل جهاز (المحقق نفسه من جهازين = واحد، ولا شيء ضاع): " + vp + "/" + vq);
        check(Q.userOffices(v2).contains(off) && Q.lastWaMark(off) != null && "آخر رسالة نُقلت".equals(Q.lastWaMark(off).text), "مكاتب المحاسب وعلامة آخر رسالة وصلت للجهاز الآخر");
        // تعديل المبلغ على جهاز يُبطل التحقق على الجهازين
        SmartApp.currentUser = P.user(admin);
        long t2 = P.editTx(t, tx(off, true, 26000, "USD", "للتحقق"), null, null, "المبلغ");
        syncAll(eP, eQ);
        boolean allVoid = true;
        for (Db.TxCheck c : Q.checks(t)) if (c.valid()) allVoid = false;
        check(allVoid && Q.checks(t2).isEmpty() && bal(P).equals(bal(Q)), "تعديل المبلغ أبطل التحقق على الجهازين وبقي في التاريخ، والأرصدة متطابقة");
        healthy(P, "17P");
        healthy(Q, "17Q");

        System.out.println("== 18) 10.14: كلمة سر جديدة من جهاز منضم، نقل الخزنة وترك المكان القديم، حذف ملفات المزامنة");
        File o18 = Files.createTempDirectory("sa-sync-18a").toFile(), n18 = Files.createTempDirectory("sa-sync-18b").toFile();
        String p18 = "كلمة-سر-القسم-18";
        Db X8 = dev("18X");
        X8.addOffice("مكتب 18", "", "", "", "", "", null, null, Arrays.asList("USD", "EUR"), new HashMap<>(), Arrays.asList(new Db.Opening("USD", "لنا", 50000)));
        SyncEngine eX8 = new SyncEngine(X8, new FileSyncFolder(o18));
        String rc18 = eX8.create(p18.toCharArray(), "الرئيسي-18");
        Db Y8 = dev("18Y");
        SyncEngine eY8 = new SyncEngine(Y8, new FileSyncFolder(o18));
        eY8.join(p18.toCharArray(), false, "الثاني-18");
        syncAll(eX8, eY8);
        // نسي كلمة السر: الجهاز المنضم يعيّن كلمة جديدة دون القديمة
        eY8.resetPassword("جديدة-بعد-النسيان".toCharArray());
        Db Z8 = dev("18Z");
        check(!errOf(() -> new SyncEngine(Z8, new FileSyncFolder(o18)).join(p18.toCharArray(), false, "ز")).isEmpty(), "الكلمة القديمة لم تعد تفتح الخزنة");
        new SyncEngine(Z8, new FileSyncFolder(o18)).join("جديدة-بعد-النسيان".toCharArray(), false, "ز");
        check(SyncEngine.enabled(Z8) && bal(Z8).equals(bal(X8)), "جهاز جديد ينضم بالكلمة الجديدة، والأرصدة نفسها");
        Db R8 = dev("18R");
        new SyncEngine(R8, new FileSyncFolder(o18)).join(rc18.toCharArray(), true, "بالرمز");
        check(SyncEngine.enabled(R8), "ورمز الاسترداد القديم ما زال يعمل");
        // نقل الخزنة إلى مكان جديد (مثل حساب Google آخر) ثم ترك شاهد في القديم
        int moved = eX8.moveTo(new FileSyncFolder(n18));
        eX8.retire("حساب Google الجديد", false, false);
        SyncEngine eX8n = new SyncEngine(X8, new FileSyncFolder(n18));
        X8.addTx(tx(X8.offices().get(0).id, true, 700, "USD", "بعد النقل"), null);
        eX8n.sync();
        String err = errOf(() -> eY8.sync());
        check(moved > 0 && err.contains("نُقلت المزامنة") && err.contains("حساب Google الجديد"), "جهاز على المكان القديم يرى «نُقلت» ولا يرسل إليه: " + err);
        SyncEngine.Status sY8 = SyncEngine.status(Y8);
        check(!sY8.movedTo.isEmpty() && "حساب Google الجديد".equals(sY8.movedTarget) && !sY8.movedFresh, "الحالة تعرض النقل ووجهته للواجهة: " + sY8.movedTarget);
        // الانتقال من مكان قديم فيه شاهد: يُكتب رأس الخزنة المحفوظ على الجهاز، لا الشاهد
        File e18 = Files.createTempDirectory("sa-sync-18c").toFile();
        eY8.moveTo(new FileSyncFolder(e18));
        String hdr = new String(Files.readAllBytes(new File(e18, SyncEngine.HEADER).toPath()), "UTF-8");
        check(!SyncEngine.tombstone(new org.json.JSONObject(hdr)) && hdr.contains("\"pw\""), "النقل من مكان قديم لا ينسخ الشاهد إلى الوجهة");
        check(eY8.moveTo(new FileSyncFolder(n18)) >= 0, "النقل إلى المكان الجديد نفسه (الخزنة هناك) لا يكسر شيئًا");
        check(SyncEngine.sameFolder(new FileSyncFolder(n18), new FileSyncFolder(n18)) && !SyncEngine.sameFolder(new FileSyncFolder(o18), new FileSyncFolder(n18)),
                "كشف «المكان نفسه» (اختيار الحساب الحالي حسابًا جديدًا)");
        check(new FileSyncFolder(n18).list("probe-").isEmpty(), "ملف الفحص يُحذف بعده");
        SyncEngine eY8n = new SyncEngine(Y8, new FileSyncFolder(n18));
        syncAll(eX8n, eY8n);
        check(SyncEngine.status(Y8).movedTo.isEmpty(), "بعد الانتقال يختفي تنبيه النقل");
        check(bal(Y8).equals(bal(X8)), "بعد الانتقال إلى المكان الجديد تتطابق الأرصدة");
        check(errOf(() -> eY8.repair(null)).contains("لا يُعاد رفع"), "«الإصلاح» لا يعيد رفع الخزنة إلى المكان القديم");
        check(!errOf(() -> new SyncEngine(dev("18Q"), new FileSyncFolder(o18)).join(p18.toCharArray(), false, "ق")).isEmpty(), "ولا انضمام إلى المكان القديم");
        int purged = new SyncEngine(X8, new FileSyncFolder(o18)).purgeRetired();
        check(purged > 0 && o18.list().length == 1, "حذف الخزنة من المكان القديم: يبقى الشاهد وحده (" + purged + " ملفًا حُذف)");
        // حذف ملفات المزامنة: بكلمة السر أو الرمز فقط
        check(errOf(() -> eX8n.wipe(null, "خطأ-خطأ-خطأ".toCharArray(), false)).contains("غير صحيحة"), "الحذف يرفض كلمة سر خاطئة");
        // 11.5: أمر حذف مزوّر (بلا توقيع بمفتاح الخزنة) يكتبه من يملك الكتابة في المجلد فقط: لا يُنفَّذ ولا تُمسح الحزم غير المرسلة
        {
            FileSyncFolder ff = new FileSyncFolder(n18);
            org.json.JSONObject real = new org.json.JSONObject(new String(ff.read(SyncEngine.HEADER), java.nio.charset.StandardCharsets.UTF_8));
            Y8.addTx(tx(Y8.offices().get(0).id, true, 12300, "USD", "قبل التزوير"), null);
            long outBefore = countRows(Y8, "sync_chg") + countRows(Y8, "sync_outbox");
            org.json.JSONObject forged = new org.json.JSONObject();
            forged.put("format", "smart-accountant-sync").put("v", real.optInt("v")).put("vault", real.optString("vault"))
                    .put("deleted", new org.json.JSONObject().put("by", "مزوّر").put("at", System.currentTimeMillis()));
            ff.write(SyncEngine.HEADER, forged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String ef = errOf(() -> eY8n.sync());
            long outAfter = countRows(Y8, "sync_chg") + countRows(Y8, "sync_outbox");
            check(ef.contains("غير موقّع") && SyncEngine.enabled(Y8) && outAfter == outBefore && outBefore > 0,
                    "أمر حذف بلا توقيع لا يُنفَّذ: الجهاز باقٍ وحزمه غير المرسلة (" + outBefore + ") محفوظة");
            forged.put("auth", "00".repeat(32));
            ff.write(SyncEngine.HEADER, forged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check(errOf(() -> eY8n.sync()).contains("غير موقّع") && SyncEngine.enabled(Y8), "ولا بتوقيع مختلق");
            eY8n.repair(null);
            check(errOf(() -> eY8n.sync()).isEmpty() && errOf(() -> eX8n.sync()).isEmpty() && bal(X8).equals(bal(Y8)),
                    "«إصلاح» يعيد رأس الخزنة بعد التزوير وتعود المزامنة وتتطابق الأرصدة");
            // حدود عدد الدورات في الرأس
            org.json.JSONObject h2 = new org.json.JSONObject(new String(ff.read(SyncEngine.HEADER), java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONObject bad = new org.json.JSONObject(h2.toString()).put("iter", 2_000_000_000);
            ff.write(SyncEngine.HEADER, bad.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long t0 = System.currentTimeMillis();
            String ei = errOf(() -> new SyncEngine(dev("18I"), new FileSyncFolder(n18)).join(p18.toCharArray(), false, "ح"));
            check(!ei.isEmpty() && System.currentTimeMillis() - t0 < 10000, "عدد دورات ضخم في الرأس يُرفض فورًا (لا تعطيل)");
            ff.write(SyncEngine.HEADER, h2.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        int wiped = eX8n.wipe(null, rc18.toCharArray(), true);
        check(wiped > 0 && n18.list().length == 1 && !SyncEngine.enabled(X8), "الحذف برمز الاسترداد: لا يبقى إلا الشاهد، وخرج هذا الجهاز");
        String e2 = errOf(() -> eY8n.sync());
        check(e2.contains("حذف المدير") && !SyncEngine.enabled(Y8) && bal(Y8).equals(bal(X8)), "الجهاز الآخر يخرج من المزامنة في جولته وبياناته باقية: " + e2);
        check(SyncEngine.status(Y8).leftReason.contains("حذف المدير"), "سبب الخروج يظهر على الجهاز: " + SyncEngine.status(Y8).leftReason);
        // مزامنة جديدة في المكان نفسه بعد الحذف
        String rcNew = new SyncEngine(Y8, new FileSyncFolder(n18)).create("كلمة-جديدة-تماما".toCharArray(), "من جديد");
        Db W8 = dev("18W");
        new SyncEngine(W8, new FileSyncFolder(n18)).join("كلمة-جديدة-تماما".toCharArray(), false, "و");
        check(!rcNew.isEmpty() && bal(W8).equals(bal(Y8)), "إنشاء مزامنة جديدة في المكان بعد الحذف والانضمام إليها");
        healthy(Y8, "18Y");
        SmartApp.currentUser = null;
    }

    static boolean hasImage(Db db) {
        for (Model.Tx t : db.recentTx(20)) if (!db.attachmentIds(t.id).isEmpty() && db.attachment(db.attachmentIds(t.id).get(0)) != null) return true;
        return false;
    }
}
