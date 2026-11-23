import android.content.Context;
import android.database.Cursor;
import com.smart.hawala.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * 10.18 على Db الحقيقي (sqlite-jdbc) وبيانات اصطناعية: الساعة الموحّدة وتوقيت المكتب، عدم تكرار المطابقة، «الرصيد بعدها»،
 * الأمانات والتجميد (ومزامنتها)، تعديل قص العملات (ومزامنته)، خطة التوزيع التلقائي، سجل الإشعارات، وترحيل قاعدة 10.4 إلى المخطط 11.
 * الاستعمال: java Fixes1018Test tests/data/db-10.4
 */
public class Fixes1018Test {
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

    static void syncAll(SyncEngine... es) throws Exception {
        for (int round = 0; round < 3; round++) for (SyncEngine e : es) e.sync();
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

    static void as(Db db, long uid) {
        SmartApp.currentUser = uid == 0 ? null : db.user(uid);
        Db.currentUserName = SmartApp.currentUser == null ? "" : SmartApp.currentUser.name;
    }

    static String nzs(String x) { return x == null ? "" : x; }

    static int count(Db db, String sql) {
        try (Cursor c = db.getReadableDatabase().rawQuery(sql, null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        Object[] first = DeskCoreTest.device("f1018A");
        Db A = (Db) first[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        List<String> ue = Arrays.asList("USD", "EUR", "TRY");
        long nour = A.addOffice("النور", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long amal = A.addOffice("الأمل", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());

        System.out.println("== 1) الساعة الموحّدة وتوقيت المكتب");
        Clock.reset();
        long real = System.currentTimeMillis();
        Clock.learn(real + 1500);
        check(Clock.offset() == 0, "فرق أقل من 3 ثوانٍ يُهمل (ضجيج الشبكة)");
        Clock.learn(real + 2 * 3600_000L);
        check(Math.abs(Clock.offset() - 2 * 3600_000L) < 2000, "ساعة الجهاز متأخرة ساعتين: يُتعلَّم الفرق");
        check(Math.abs(Clock.now() - (System.currentTimeMillis() + 2 * 3600_000L)) < 2000, "Clock.now يعطي الوقت المصحَّح");
        check(Math.abs(A.settingLong("clock_offset_ms", 0) - 2 * 3600_000L) < 2000, "الفرق يُحفظ في إعدادات الجهاز (يبقى بلا إنترنت)");
        check(Db.clockOff() && A.clockLines().get(1).contains("متأخرة"), "تنبيه «ساعة الجهاز متأخرة» يظهر");
        Clock.reset();
        A.reloadClock();
        check(Math.abs(Clock.offset() - 2 * 3600_000L) < 2000, "بعد إعادة التشغيل يُحمَّل الفرق المحفوظ");
        long tid = A.addTx(tx(nour, true, 10000, "USD", "بساعة خاطئة", Clock.now()), null);
        check(Math.abs(A.tx(tid).createdAt - (System.currentTimeMillis() + 2 * 3600_000L)) < 5000, "الحركة تُسجَّل بالوقت المصحَّح لا بساعة الجهاز");
        A.cancelTx(tid, "اختبار");
        Clock.learn(System.currentTimeMillis());
        check(Clock.offset() == 0, "ضُبطت الساعة: يعود الفرق صفرًا");
        long noon = 1767258000000L;   // 2026-01-01 09:00 UTC
        A.setOfficeZone("Asia/Damascus");
        String dam = Model.date(noon);
        A.setOfficeZone("America/New_York");
        String ny = Model.date(noon);
        check(dam.endsWith("12:00") && ny.endsWith("04:00"), "اللحظة نفسها تُعرض بتوقيت المكتب: دمشق " + dam + " / نيويورك " + ny);
        java.util.Calendar c1 = Model.cal();
        check(c1.getTimeZone().getID().equals("America/New_York"), "بداية اليوم ونهايته تُحسبان بتوقيت المكتب");
        check(throwsMsg(() -> A.setOfficeZone("Mars/Base"), "غير معروفة"), "منطقة غير معروفة تُرفض");
        A.setOfficeZone("Asia/Damascus");
        check("Asia/Damascus".equals(A.setting("office_tz", "")) && Model.zoneLabel("Asia/Damascus").contains("دمشق"), "توقيت المكتب محفوظ: " + Model.zoneLabel(Model.zoneId()));

        System.out.println("== 2) المطابقة لا تتكرر (صورة ثم نص)");
        long t0 = Clock.now() - 3600_000L;
        A.addTx(tx(nour, true, 50000, "USD", "أ", t0), null);
        long r1 = A.createRecon(nour), r2 = A.createRecon(nour);
        check(r1 == r2 && count(A, "SELECT COUNT(*) FROM reconciliations WHERE office_id=" + nour) == 1, "إرسال المطابقة مرتين بالرصيد نفسه = مطابقة واحدة");
        A.addTx(tx(nour, false, 20000, "USD", "ب", t0 + 1000), null);
        long r3 = A.createRecon(nour);
        check(r3 != r1 && count(A, "SELECT COUNT(*) FROM reconciliations WHERE office_id=" + nour) == 2, "بعد حركة جديدة تُسجَّل مطابقة جديدة");
        A.setReconStatus(r3, Model.RECON_CONFIRMED, "");
        long r4 = A.createRecon(nour);
        check(r4 != r3, "بعد تأكيد المكتب: إرسال جديد = مطابقة جديدة");

        System.out.println("== 3) الرصيد بعد كل حركة");
        long x3 = A.addTx(tx(nour, true, 7000, "USD", "ج", t0 + 2000), null);
        long x4 = A.addTx(tx(nour, false, 100000, "USD", "د", t0 + 3000), null);
        A.addTx(tx(nour, true, 999, "EUR", "يورو", t0 + 3500), null);
        Map<Long, Long> af = A.balancesAfter(nour, "USD");
        check(af.get(x3) == 37000L && af.get(x4) == -63000L, "الرصيد التراكمي بعد كل حركة صحيح (37000 ثم −63000)");
        long last = 0;
        Db.TxFilter f = new Db.TxFilter();
        f.office = nour;
        f.currency = "USD";
        List<Model.Tx> log = A.txLog(f);
        check(af.get(log.get(0).id).equals(A.balances(nour).get("USD")), "رصيد آخر حركة = رصيد المكتب الحالي");
        A.cancelTx(x3, "خطأ");
        af = A.balancesAfter(nour, "USD");
        check(af.get(x4) == -70000L && A.balances(nour).get("USD") == -70000L, "إلغاء حركة يُخرجها من التراكم");
        check(!A.balancesAfter(nour, "EUR").containsKey(x4), "عملة أخرى لها تراكمها وحدها");

        System.out.println("== 4) الأمانات والتجميد");
        String h1 = A.addHold(amal, "USD", Db.HOLD_TRUST, Db.SIDE_THEIRS, 500000, "أمانة أبو خالد");
        check(A.balances(amal).getOrDefault("USD", 0L) == 0 && A.holdTotals(amal).get("USD")[1] == 500000, "الأمانة المنفصلة لا تدخل الرصيد وتظهر في مجاميع الأمانات");
        check(throwsMsg(() -> A.addHold(amal, "USD", Db.HOLD_FREEZE, Db.SIDE_OURS, 100, ""), null), "تجميد من «لنا» بلا رصيد لنا مرفوض");
        A.addTx(tx(amal, true, 300000, "USD", "هـ", t0), null);
        String h2 = A.addHold(amal, "USD", Db.HOLD_FREEZE, Db.SIDE_OURS, 200000, "برسم التحصيل");
        check(throwsMsg(() -> A.addHold(amal, "USD", Db.HOLD_FREEZE, Db.SIDE_OURS, 150000, ""), null), "تجميد يتجاوز الرصيد بعد المجمّد سابقًا مرفوض");
        check(A.balances(amal).get("USD") == 300000 && A.holdTotals(amal).get("USD")[2] == 200000 && !A.holdLines(amal).isEmpty(), "التجميد لا يغيّر الرصيد ويظهر سطره: " + A.holdLines(amal));
        check(throwsMsg(() -> A.addHold(amal, "USD", Db.HOLD_TRUST, Db.SIDE_OURS, 0, ""), null), "مبلغ صفر مرفوض");
        A.releaseHold(h2);
        check(A.holds(amal, true).size() == 1 && A.holds(amal, false).size() == 2, "الفك يُبقي السجل ويُخرج المبلغ من القائم");
        check(throwsMsg(() -> A.releaseHold(h2), null), "فك المفكوك مرفوض");
        check(A.withHolds(new Report()).sections.size() == 1, "تقرير الأرصدة يضم قسم الأمانات القائمة");

        System.out.println("== 5) تعديل قص العملات");
        A.addTx(tx(amal, true, 700000, "EUR", "رصيد يورو", t0), null);
        String[] fx = A.addFxNet(amal, "EUR", 100000, "USD", 108000, "1.08", "قص أول", t0 + 5000);
        Map<String, Long> before = new TreeMap<>(A.balances(amal));
        check(before.get("EUR") == 600000 && before.get("USD") == 408000, "القص الأصلي: يورو −1000 ودولار +1080");
        Model.Tx[] legs = A.fxLegsOf(Long.parseLong(fx[0]));
        check(legs != null && "EUR".equals(legs[0].currency) && "USD".equals(legs[1].currency), "ساقا القص تُقرآن: المباعة EUR والمشتراة USD");
        Map<String, long[]> pv = A.fxEditPreview(legs, "EUR", 200000, "TRY", 9000000);
        check(pv.get("EUR")[0] == 700000 && pv.get("EUR")[1] == 500000 && pv.get("USD")[1] == 300000 && pv.get("TRY")[1] == 9000000, "معاينة التعديل تعكس القديم أولًا");
        String[] nw = A.editFxNet(legs[1].id, "EUR", 200000, "TRY", 9000000, "45", "قص مصحّح", legs[0].createdAt, "السعر خطأ");
        Map<String, Long> bal = A.balances(amal);
        check(bal.get("EUR") == 500000 && bal.get("USD") == 300000 && bal.get("TRY") == 9000000, "بعد التعديل: الأرصدة كأن القص سُجّل بالقيم الجديدة");
        Model.Tx o0 = A.tx(legs[0].id), n0 = A.tx(Long.parseLong(nw[0])), n1 = A.tx(Long.parseLong(nw[1]));
        check(!o0.isActive() && !A.tx(legs[1].id).isActive() && n0.isActive() && n1.isActive(), "الساقان القديمتان ملغاتان والجديدتان نشطتان");
        check(n0.replacesId != null && n1.replacesId != null && n0.pairUuid.equals(n1.pairUuid) && !n0.pairUuid.equals(o0.pairUuid) && n0.isFx(), "الجديدتان مرتبطتان معًا وكل منهما تحل محل نظيرتها");
        check(n0.createdAt == legs[0].createdAt, "التاريخ الأصلي محفوظ");
        check(throwsMsg(() -> A.editFxNet(legs[0].id, "EUR", 1, "USD", 1, "1", "", t0, ""), "ملغاة"), "تعديل عملية عُدّلت من قبل مرفوض");
        check(throwsMsg(() -> A.editFxNet(Long.parseLong(nw[0]), "EUR", 5, "EUR", 5, "1", "", t0, ""), null), "العملة نفسها في الطرفين مرفوضة ولا يتغير شيء");
        check(A.balances(amal).get("EUR") == 500000 && A.tx(Long.parseLong(nw[0])).isActive(), "فشل التعديل لا يترك نصف عملية (المعاملة تُلغى كاملة)");
        A.cancelTx(Long.parseLong(nw[0]), "إلغاء");
        check(A.balances(amal).get("EUR") == 700000 && A.balances(amal).getOrDefault("TRY", 0L) == 0, "إلغاء القص المصحّح يعيد الأرصدة");
        long plain = A.addTx(tx(amal, true, 100, "USD", "عادية", t0), null);
        check(A.fxLegsOf(plain) == null && throwsMsg(() -> A.editFxNet(plain, "EUR", 5, "USD", 5, "1", "", t0, ""), "ليست حركة قص"), "حركة عادية لا تُفتح كقص");

        System.out.println("== 6) المزامنة: الأمانات وتعديل القص وتوقيت المكتب");
        File shared = Files.createTempDirectory("sa-1018-folder").toFile();
        SyncEngine eA = new SyncEngine(A, new FileSyncFolder(shared));
        eA.create("كلمة-سر-المزامنة".toCharArray(), "المحل");
        Db B = new Db(new Context(Files.createTempDirectory("sa-1018-B").toFile()));
        SyncEngine eB = new SyncEngine(B, new FileSyncFolder(shared));
        eB.join("كلمة-سر-المزامنة".toCharArray(), false, "البيت");
        syncAll(eA, eB);
        check(B.holds(amal, true).size() == 1 && B.holds(amal, false).size() == 2, "الأمانات وصلت الجهاز الثاني (القائم والمفكوك)");
        check("Asia/Damascus".equals(B.setting("office_tz", "")), "توقيت المكتب وصل الجهاز الثاني");
        check(B.setting("clock_offset_ms", "").isEmpty() || B.settingLong("clock_offset_ms", 0) == 0, "فرق ساعة الجهاز لا ينتقل بين الأجهزة");
        String[] fx2 = A.addFxNet(amal, "EUR", 100000, "USD", 110000, "1.1", "قص للمزامنة", Clock.now() - 5000);
        syncAll(eA, eB);
        check(new TreeMap<>(B.balances(amal)).equals(new TreeMap<>(A.balances(amal))), "قبل التعديل: الأرصدة متطابقة على الجهازين");
        B.editFxNet(Long.parseLong(fx2[0]), "EUR", 150000, "USD", 165000, "1.1", "معدّل من البيت", Clock.now() - 5000, "المبلغ");
        B.releaseHold(h1);
        String h3 = B.addHold(nour, "EUR", Db.HOLD_TRUST, Db.SIDE_OURS, 4200, "من البيت");
        syncAll(eA, eB);
        check(new TreeMap<>(B.balances(amal)).equals(new TreeMap<>(A.balances(amal))) && A.balances(amal).get("EUR") == 550000, "تعديل القص على جهاز يصل الآخر والأرصدة متطابقة");
        check(!A.tx(Long.parseLong(fx2[0])).isActive() && !A.tx(Long.parseLong(fx2[1])).isActive(), "الساقان القديمتان ملغاتان على الجهاز الآخر");
        check(A.holds(amal, true).isEmpty() && A.holds(nour, true).size() == 1 && A.holds(nour, true).get(0).uuid.equals(h3), "فكّ أمانة وإضافة أخرى على جهاز يصلان الآخر");
        boolean dupNotice = false;
        for (String[] c : SyncEngine.conflicts(A)) if (c[2].equals("EDIT_LOST") || c[2].equals("EDIT")) dupNotice = true;
        check(!dupNotice, "لا تعارض كاذب من تعديل القص أو الأمانات");
        check(SyncEngine.noticeHistory(A, 100).size() >= SyncEngine.conflicts(A).size(), "سجل تنبيهات المزامنة يضم المفتوح وما تمّ");
        SyncEngine.resolveAll(A);
        check(SyncEngine.conflicts(A).isEmpty() == true && SyncEngine.noticeHistory(A, 100).size() >= 0, "«تمت للكل» لا تمسح السجل");

        System.out.println("== 6ب) جهاز «يخرج وحده» وانضمام «يعطي خطأ» (تعثّر القراءة من مكان المزامنة)");
        SyncEngine.resolveAll(A);
        // بطاقات الأجهزة لا تُقرأ في هذه الجولة (كما يحدث حين تُستبدل البطاقة على Google Drive لحظة القراءة)
        final boolean[] hideCards = {true};
        final boolean[] failAll = {false};
        final SyncFolder realF = new FileSyncFolder(shared);
        SyncFolder flaky = (SyncFolder) java.lang.reflect.Proxy.newProxyInstance(SyncFolder.class.getClassLoader(), new Class<?>[]{SyncFolder.class}, (px, m, args) -> {
            try {
                if (failAll[0] && (m.getName().equals("write") || m.getName().equals("list"))) throw new java.io.IOException("انقطع الاتصال");
                if (hideCards[0] && m.getName().equals("read") && String.valueOf(args[0]).startsWith("m-")) return null;
                return m.invoke(realF, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        SyncEngine eAflaky = new SyncEngine(A, flaky);
        int membersBefore = SyncEngine.status(A).members.size();
        try {
            eAflaky.sync();
        } catch (Exception e) {
            System.out.println("      جولة متعثرة: " + e.getMessage());
        }
        boolean goneNotice = false;
        for (String[] c : SyncEngine.noticeHistory(A, 200)) if (c[2].equals("DEVICE_GONE") && Long.parseLong(c[1]) > Clock.now() - 60_000 && c[5].contains("البيت")) goneNotice = true;
        check(!goneNotice, "بطاقة جهاز لم تُقرأ في جولة: لا تنبيه «خرج الجهاز من المزامنة»");
        check(SyncEngine.status(A).members.size() == membersBefore && SyncEngine.enabled(A) && SyncEngine.enabled(B), "والجهاز يبقى في قائمة الأجهزة، ولا يخرج أحد من المزامنة");
        check(nzs(eAflaky.clean(true)).isEmpty(), "ولا يُحذف شيء من الخزنة والبطاقات غير مكتملة");
        hideCards[0] = false;
        syncAll(eA, eB);
        boolean joinedAgain = false;
        for (String[] c : SyncEngine.conflicts(A)) if (c[2].equals("DEVICE_JOINED")) joinedAgain = true;
        check(!joinedAgain && SyncEngine.status(A).members.size() == membersBefore, "بعد عودة القراءة: لا «دخل جهاز» كاذب والقائمة كما هي");
        // انضمام يتعثر اتصاله بعد أن استُبدلت البيانات وسُجّل الجهاز: كان يظهر خطأ ثم يُرفض الانضمام ثانية «في مزامنة من قبل»
        Db C = new Db(new Context(Files.createTempDirectory("sa-1018-C").toFile()));
        final boolean[] armed = {false};
        SyncFolder cut = (SyncFolder) java.lang.reflect.Proxy.newProxyInstance(SyncFolder.class.getClassLoader(), new Class<?>[]{SyncFolder.class}, (px, m, args) -> {
            try {
                if (armed[0] && m.getName().equals("write")) throw new java.io.IOException("انقطع الاتصال");
                Object r = m.invoke(realF, args);
                // بعد قراءة اللقطة (s-) ينقطع الاتصال: التسجيل المحلي يتم، وكتابة البطاقة وأول جولة تفشلان
                if (m.getName().equals("read") && String.valueOf(args[0]).startsWith("s-")) armed[0] = true;
                return r;
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        boolean joinThrew = false;
        try {
            new SyncEngine(C, cut).join("كلمة-سر-المزامنة".toCharArray(), false, "المكتب الثاني");
        } catch (Exception e) {
            joinThrew = true;
            System.out.println("      خطأ الانضمام: " + e.getMessage());
        }
        check(!joinThrew && SyncEngine.enabled(C), "انقطاع الاتصال بعد تسجيل الجهاز: الانضمام ينجح ولا يظهر خطأ");
        check(SyncEngine.status(C).lastError.contains("انضم الجهاز"), "ويُذكر أن أول مزامنة تعثرت وستُعاد: " + SyncEngine.status(C).lastError);
        check(!C.offices().isEmpty(), "وبيانات لقطة الخزنة وصلت الجهاز المنضم");
        SyncEngine eC = new SyncEngine(C, new FileSyncFolder(shared));
        syncAll(eA, eB, eC);
        check(SyncEngine.status(C).lastError.isEmpty() && SyncEngine.status(A).members.size() == membersBefore + 1, "الجولة التالية تكمل: الجهاز الجديد يظهر عند الآخرين وبلا خطأ");
        check(new TreeMap<>(C.balances(amal)).equals(new TreeMap<>(A.balances(amal))), "وبعدها أرصدته مطابقة للجهاز الرئيسي");
        long tc = C.addTx(tx(nour, true, 4400, "USD", "من المكتب الثاني", Clock.now() - 1000), null);
        syncAll(eA, eB, eC);
        check(A.tx(tc) != null && new TreeMap<>(A.balances(nour)).equals(new TreeMap<>(C.balances(nour))), "وحركاته تصل الجميع");
        SyncEngine.resolveAll(A);

        System.out.println("== 7) سجل الإشعارات");
        A.clearNotifLog();
        A.logNotif("مزامنة", "وصلت 3 حركات من البيت");
        A.logNotif("رسالة المدير", "أرسلوا المطابقات اليوم");
        List<String[]> nl = A.notifLog(50);
        check(nl.size() == 2 && nl.get(0)[1].equals("رسالة المدير") && nl.get(1)[2].contains("3 حركات"), "كل إشعار يُحفظ (الأحدث أولًا) بنصه");
        syncAll(eA, eB);
        check(B.notifLog(50).isEmpty() || !B.notifLog(50).get(0)[1].equals("رسالة المدير"), "سجل الإشعارات محلي لكل جهاز");
        A.clearNotifLog();
        check(A.notifLog(50).isEmpty(), "المسح يفرغ السجل");

        System.out.println("== 8) التوزيع التلقائي للمكاتب");
        long o3 = A.addOffice("الشام", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long o4 = A.addOffice("حلب", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long admin = A.addUser("المدير", Auth.ADMIN, null, "123456");
        as(A, admin);
        long u1 = A.addUser("سامر", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY", "1111");
        long u2 = A.addUser("رنا", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY", "2222");
        A.setOfficeTeam(nour, new HashSet<>(Arrays.asList(u1)), new HashSet<>());
        List<Db.TeamPlan> plan = A.autoTeamPlan();
        int fresh = 0, s1 = 0, s2 = 0;
        Db.TeamPlan pn = null;
        for (Db.TeamPlan p : plan) {
            if (p.office.id == nour) pn = p;
            if (!p.assigned) fresh++;
            for (Model.User u : p.owners) {
                if (u.id == u1) s1++;
                if (u.id == u2) s2++;
            }
        }
        check(plan.size() == 4 && fresh == 3 && s1 == 2 && s2 == 2, "غير الموزّع يُوزَّع بالتساوي مع احتساب ما لدى كل محاسب (2 و2)");
        check(pn != null && pn.assigned && pn.owners.get(0).id == u1 && pn.checkers.size() == 1 && pn.checkers.get(0).id == u2 && !pn.same(), "الموزّع يبقى من يسجّله ويُقترح له من يتحقق");
        check(A.teams().stream().filter(t -> !t.owners.isEmpty()).count() == 1, "الخطة لا تحفظ شيئًا قبل الاعتماد");
        for (Db.TeamPlan p : plan) if (!p.assigned) A.applyTeamPlan(p);
        check(A.teams().stream().filter(t -> !t.owners.isEmpty() && (t.office.id == nour || !t.checkers.isEmpty())).count() == 4, "اعتماد غير الموزّع يحفظه بمن يسجّل ومن يتحقق");
        boolean nourUntouched = true;
        for (Db.Team t : A.teams()) if (t.office.id == nour) nourUntouched = t.checkers.isEmpty();
        check(nourUntouched, "المكتب الموزّع لا يتغير حتى يوافق المدير عليه");
        A.applyTeamPlan(pn);
        boolean allSame = true;
        for (Db.TeamPlan p : A.autoTeamPlan()) allSame &= p.same();
        check(allSame, "بعد الاعتماد: لا شيء يتغير عند إعادة التوزيع");
        as(A, u1);
        check(throwsMsg(() -> A.autoTeamPlan(), null), "المحاسب لا يوزّع المكاتب");
        check(throwsMsg(() -> A.setOfficeZone("Europe/Berlin"), null), "المحاسب لا يغيّر توقيت المكتب");
        as(A, 0);

        System.out.println("== 9) ترحيل قاعدة 10.4 إلى المخطط " + Db.VER);
        File src = new File(a[a.length - 1]);
        if (!src.isDirectory()) check(false, "مجلد قاعدة 10.4 غير موجود: " + src);
        else {
            File home = Files.createTempDirectory("sa-1018-mig").toFile();
            MigrationTest.copy(src, home);
            DeskCoreTest.resetCrypto();
            Db M = MigrationTest.open(home);
            Map<Long, Map<String, Long>> balM = new TreeMap<>(M.officeBalances());
            check(count(M, "SELECT COUNT(*) FROM office_holds") == 0 && count(M, "SELECT COUNT(*) FROM notif_log") == 0, "الجداول الجديدة أُنشئت فارغة");
            int ver;
            try (Cursor c = M.getReadableDatabase().rawQuery("PRAGMA user_version", null)) {
                c.moveToFirst();
                ver = c.getInt(0);
            }
            check(ver == Db.VER && Db.VER >= 11, "رقم المخطط بعد الترحيل " + ver);
            for (Model.User u : M.users(false)) if (Auth.isAdmin(u)) as(M, u.id);
            Model.Office mo = M.offices().get(0);
            String cur = M.officeCurrencies(mo.id).get(0);
            M.addHold(mo.id, cur, Db.HOLD_TRUST, Db.SIDE_THEIRS, 12345, "بعد الترحيل");
            check(new TreeMap<>(M.officeBalances()).equals(balM) && M.holds(mo.id, true).size() == 1, "الأرصدة كما هي بعد الترحيل، والأمانات تعمل على القاعدة القديمة");
            check(!M.balancesAfter(mo.id, cur).isEmpty() || M.balances(mo.id).getOrDefault(cur, 0L) == 0, "«الرصيد بعدها» يعمل على حركات القاعدة القديمة");
        }

        System.out.println();
        System.out.println(fails == 0 ? "نجحت اختبارات 10.18 كلها (" + passes + ")" : "فشل " + fails + " من " + (passes + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
