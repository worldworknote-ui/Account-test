import android.content.Context;
import android.database.Cursor;
import com.smart.hawala.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * 10.16: إصلاحات تقرير المراجعة وملاحظات التجربة، على Db الحقيقي (sqlite-jdbc) وبيانات اصطناعية:
 * سجل العمليات المشترك بين الأجهزة، تنبيهات المزامنة (تعديل لم يُطبَّق، صلاحيات، دخول جهاز وخروجه) وظهورها للرئيسية،
 * التحقق من عدة حركات دفعة واحدة، حد محاولات الرمز في طبقة البيانات، حماية الإعدادات الحساسة، رفض نسخة من مخطط أحدث،
 * ترحيل قاعدة 10.4 إلى المخطط 9، و«اعتماد رصيد الملف» عند إضافة ملف النقل.
 * الاستعمال: java Fixes1016Test tests/data
 */
public class Fixes1016Test {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Db dev(String n) throws Exception {
        File home = Files.createTempDirectory("sa-1016-" + n).toFile();
        return new Db(new Context(home));
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

    static List<String> kinds(Db db) {
        List<String> k = new ArrayList<>();
        for (String[] c : SyncEngine.conflicts(db)) k.add(c[2]);
        return k;
    }

    static String detail(Db db, String kind) {
        for (String[] c : SyncEngine.conflicts(db)) if (c[2].equals(kind)) return c[5];
        return "";
    }

    static boolean feedHas(Db db, String action, String devName) {
        for (String[] r : db.auditLogAll(500)) if (r[1].equals(action) && r[6].contains(devName)) return true;
        return false;
    }

    static void as(Db db, long uid) {
        SmartApp.currentUser = uid == 0 ? null : db.user(uid);
        Db.currentUserName = SmartApp.currentUser == null ? "" : SmartApp.currentUser.name;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        File data = new File(a[a.length - 1]);
        Object[] first = DeskCoreTest.device("f1016A");
        Db A = (Db) first[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        File shared = Files.createTempDirectory("sa-1016-folder").toFile();
        List<String> ue = Arrays.asList("USD", "EUR");

        System.out.println("== 1) سجل العمليات المشترك بين الأجهزة");
        long nour = A.addOffice("النور", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        check(A.auditLogAll(50).size() == A.auditLog(50).size() && A.auditLogAll(50).get(0)[6].isEmpty(), "بلا مزامنة: السجل كما كان وعمود الجهاز فارغ");
        int feed0;
        try (Cursor c = A.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM audit_feed", null)) {
            c.moveToFirst();
            feed0 = c.getInt(0);
        }
        check(feed0 == 0, "بلا مزامنة لا يُكتب شيء في السجل المشترك");
        SyncEngine eA = new SyncEngine(A, new FileSyncFolder(shared));
        eA.create("كلمة-سر-المزامنة".toCharArray(), "المحل");
        Db B = dev("B");
        SyncEngine eB = new SyncEngine(B, new FileSyncFolder(shared));
        eB.join("كلمة-سر-المزامنة".toCharArray(), false, "البيت");
        syncAll(eA, eB);
        check(kinds(A).isEmpty() || !kinds(A).contains("DEVICE_GONE"), "لا خبر خروج كاذب بعد الانضمام");
        long tA = A.addTx(tx(nour, true, 30000, "USD", "من المحل"), null);
        long tB = B.addTx(tx(nour, false, 12000, "USD", "من البيت"), null);
        B.cancelTx(tB, "خطأ إدخال");
        syncAll(eA, eB);
        check(feedHas(A, "CANCEL_TX", "البيت") || feedHas(A, "CANCEL", "البيت") || feedHasPrefix(A, "CANCEL", "البيت"), "المدير على جهاز «المحل» يرى إلغاءً جرى على «البيت»");
        check(feedHasPrefix(B, "ADD", "المحل") || feedHasPrefix(B, "TX", "المحل") || feedHasAny(B, "المحل"), "وجهاز «البيت» يرى ما جرى على «المحل»");
        boolean mineOnce = true;
        Map<String, Integer> seen = new HashMap<>();
        for (String[] r : A.auditLogAll(500)) if (r[6].contains("هذا الجهاز")) seen.merge(r[0] + r[1] + r[3] + r[5], 1, Integer::sum);
        for (int n : seen.values()) if (n > 2) mineOnce = false;
        int own = 0, total = A.auditLog(500).size();
        for (String[] r : A.auditLogAll(500)) if (r[6].contains("هذا الجهاز")) own++;
        check(own == total && mineOnce, "أسطر هذا الجهاز لا تتكرر (من سجله المتسلسل فقط): " + own + "/" + total);
        boolean noSyncNoise = true;
        try (Cursor c = A.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM audit_feed WHERE action IN ('SYNC','SYNC_UPGRADE')", null)) {
            c.moveToFirst();
            noSyncNoise = c.getInt(0) == 0;
        }
        check(noSyncNoise, "جولات المزامنة نفسها لا تُنسخ في السجل المشترك (لا حلقة مزامنة)");
        SyncEngine.Result quiet1 = eA.sync(), quiet2 = eB.sync(), quiet3 = eA.sync();
        check(quiet3.sent == 0 && quiet3.received == 0, "بعد الهدوء: لا حزم تتولد من المزامنة نفسها (" + quiet3.sent + "/" + quiet3.received + ")");
        boolean chain = true;
        for (Db.Check c : A.healthCheck(true)) if (!c.ok) {
            chain = false;
            System.out.println("      ✗ " + c.title + ": " + c.detail);
        }
        check(chain, "فحص الصحة (ومنه سلسلة بصمات السجل) سليم مع السجل المشترك");

        System.out.println("== 2) دخول جهاز إلى المزامنة وخروجه");
        Db C = dev("C");
        SyncEngine eC = new SyncEngine(C, new FileSyncFolder(shared));
        eC.join("كلمة-سر-المزامنة".toCharArray(), false, "الفرع");
        SyncEngine.Result ra = eA.sync();
        check(ra.news.size() == 1 && ra.news.get(0).contains("الفرع") && ra.news.get(0).contains("دخل"), "خبر دخول «الفرع» يصل في نتيجة الجولة (للإشعار): " + ra.news);
        check(kinds(A).contains("DEVICE_JOINED") && detail(A, "DEVICE_JOINED").contains("الفرع"), "ويُحفظ تنبيهًا للمراجعة");
        check(eA.sync().news.isEmpty(), "ولا يتكرر الخبر في الجولة التالية");
        boolean home = false;
        for (String[] al : SyncEngine.homeAlerts(A)) if (al[0].contains("تنبيه")) home = true;
        check(home, "الصفحة الرئيسية تعرض تنبيهات المزامنة المفتوحة: " + SyncEngine.homeAlerts(A).get(0)[0]);
        check(ra.notable() && ra.summary().contains("الفرع"), "الجولة تُعد جديرة بالإشعار وملخصها يذكر الجهاز");
        syncAll(eA, eB, eC);
        String devC = SyncEngine.status(C).device;
        eA.removeDevice(devC);
        SyncEngine.Result rb = eB.sync();
        check(rb.news.size() == 1 && rb.news.get(0).contains("خرج") && rb.news.get(0).contains("الفرع") && rb.news.get(0).contains("المحل"), "خبر خروج «الفرع» ومن أزاله يصل للأجهزة الأخرى: " + rb.news);
        check(kinds(B).contains("DEVICE_GONE"), "ويُحفظ تنبيهًا للمراجعة على الجهاز الآخر");
        SyncEngine.resolveAll(A);
        SyncEngine.resolveAll(B);
        check(SyncEngine.status(A).review == 0 && SyncEngine.homeAlerts(A).isEmpty(), "«تمت للكل» تُخلي المراجعة والرئيسية");

        System.out.println("== 3) تعديل من جهازين: الأحدث يفوز ولا يضيع الآخر بصمت");
        Model.Office oA = A.office(nour);
        B.updateOffice(nour, "النور — تعديل البيت", "", "111", "", "", "", true, null, null, ue, new HashMap<>());
        Thread.sleep(30);
        A.updateOffice(nour, "النور — تعديل المحل", "", "222", "", "", "", true, null, null, ue, new HashMap<>());
        SyncEngine.Result r3 = eA.sync();
        eB.sync();
        SyncEngine.Result r3a = eA.sync();
        eB.sync();
        check(A.office(nour).name.equals(B.office(nour).name) && A.office(nour).name.startsWith("النور — تعديل"), "الجهازان اتفقا على تعديل واحد: " + A.office(nour).name);
        Db W = kinds(A).contains("EDIT_LOST") ? A : B;
        check(kinds(W).contains("EDIT_LOST") && detail(W, "EDIT_LOST").contains("المكتب «النور"), "الجهاز الذي بقي تعديله يُعلَم أن تعديلًا من جهاز آخر لم يُطبَّق: " + detail(W, "EDIT_LOST"));
        check(kinds(A).contains("EDIT_LOST") != kinds(B).contains("EDIT_LOST"), "تنبيه واحد لا اثنان (على الجهاز الذي حُسم عنده)");
        boolean red = false;
        for (String[] al : SyncEngine.homeAlerts(W)) if ("1".equals(al[1]) && al[0].contains("مراجعت")) red = true;
        check(red, "ويظهر في الرئيسية كأمر يحتاج مراجعة");

        System.out.println("== 4) تغيّر الصلاحيات من جهاز آخر");
        long admin = A.addUser("المدير", Auth.ADMIN, null, "123456");
        as(A, admin);
        long clerk = A.addUser("سامر", Auth.CUSTOM, "VIEW,TX_ADD", "1111");
        long acct = A.addUser("رنا", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY", "2222");
        syncAll(eA, eB);
        SyncEngine.resolveAll(B);
        A.updateUser(clerk, "سامر", Auth.CUSTOM, "VIEW,TX_ADD,TX_CANCEL,APPROVE", true);
        A.updateUser(acct, "رنا", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY", false);
        eA.sync();
        SyncEngine.Result r4 = eB.sync();
        List<String> rc = new ArrayList<>();
        for (String[] c : SyncEngine.conflicts(B)) if (c[2].equals("ROLE_CHANGED")) rc.add(c[5]);
        check(rc.size() == 2 && rc.toString().contains("سامر") && rc.toString().contains("تغيّرت صلاحياته") && rc.toString().contains("أُوقف"), "الجهاز الآخر يُعلَم بتغيّر الصلاحيات والإيقاف: " + rc);
        check(r4.notices == 2 && r4.review() >= 2, "وتُعدّ للإشعار");
        A.updateUser(acct, "رنا", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY", true);
        syncAll(eA, eB);

        System.out.println("== 5) التحقق من عدة حركات دفعة واحدة");
        Map<Long, String> roles = new HashMap<>();
        roles.put(nour, "CHECK");
        A.setUserOffices(acct, roles);
        as(A, clerk);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) ids.add(A.addTx(tx(nour, true, 1000 + i, "USD", "ح" + i), null));
        as(A, acct);
        long mine = A.addTx(tx(nour, true, 777, "USD", "حركتي"), null);
        int waiting = A.awaitingMyCheck(60, 200).size();
        List<Long> all = new ArrayList<>(ids);
        all.add(mine);
        Object[] vr = A.verifyTxs(all);
        @SuppressWarnings("unchecked") List<String> refused = (List<String>) vr[1];
        check((Integer) vr[0] == 4 && refused.size() == 1 && refused.get(0).startsWith("#" + mine), "سُجّل التحقق من 4 ورُفضت حركتي أنا بسببها: " + refused);
        check(waiting >= 4 && A.awaitingMyCheck(60, 200).size() == waiting - 4, "لم يبقَ منها شيء بانتظار تحققي: كان " + waiting + " وصار " + A.awaitingMyCheck(60, 200).size());
        as(A, clerk);
        check(throwsMsg(() -> A.verifyTxs(ids), null), "من لا يملك صلاحية التحقق يُرفض في طبقة البيانات");

        System.out.println("== 6) حد محاولات الرمز في طبقة البيانات");
        boolean wrongs = true;
        for (int i = 0; i < 5; i++) wrongs &= !A.verifyPin(clerk, "0000");
        check(wrongs, "5 محاولات خاطئة تُرفض كلٌّ بوصفها خطأ");
        check(throwsMsg(() -> A.verifyPin(clerk, "1111"), "انتظر"), "بعدها حتى الرمز الصحيح يُرفض مؤقتًا (لا تخمين سريع من أي واجهة)");
        check(A.verifyPin(acct, "2222"), "ولا يتأثر مستخدم آخر");

        System.out.println("== 7) الإعدادات الحساسة بصلاحيتها في طبقة البيانات");
        as(A, clerk);
        check(throwsMsg(() -> A.setSettingQuiet("lock_before", "0"), null), "موظف لا يفك الإقفال المحاسبي بكتابة الإعداد مباشرة");
        check(throwsMsg(() -> A.setSetting("approval_on", "0"), null) && throwsMsg(() -> A.deleteSetting("approval_usd"), null), "ولا يعطّل الاعتماد");
        A.setSettingQuiet("rc_title", "كشف");
        check(A.setting("rc_title", "").equals("كشف"), "والإعدادات العادية تُكتب كما كانت");
        as(A, admin);
        A.setSetting("approval_on", "0");
        check(A.setting("approval_on", "").equals("0"), "والمدير يكتبها");

        // ============ 10.17 ============
        System.out.println("== 11) الاسم الظاهر: المحاسبون يرون الاسم المختار والمدير يرى اسم الدخول");
        as(A, admin);
        A.setNick(clerk, "أبو سامي");
        as(A, clerk);
        long nt = A.addTx(tx(nour, true, 4321, "USD", "حركة سامر"), null);
        check(A.tx(nt).userName.equals("سامر") && A.tx(nt).realUser().equals("سامر"), "صاحب الحركة يرى اسمه");
        as(A, acct);
        Model.Tx seen11 = A.tx(nt);
        check(seen11.userName.equals("أبو سامي") && seen11.realUser().equals("سامر"), "محاسب آخر يرى الاسم الظاهر، والمنطق يعرف الحقيقي: " + seen11.userName);
        check(!Db.enteredBy(seen11, A.user(acct)) && Db.enteredBy(seen11, A.user(clerk)), "«حركتي؟» لا يتأثر بالاسم الظاهر");
        boolean leak = false;
        for (String ln : A.txPeople(seen11)) if (ln.contains("سامر")) leak = true;
        check(!leak, "تفاصيل الحركة (من أدخلها) بلا اسم الدخول لغير المدير");
        as(A, admin);
        check(A.tx(nt).userName.equals("سامر"), "المدير يرى اسم الدخول");
        as(A, acct);
        A.setNick(acct, "المحاسبة");
        check(A.user(acct).nick.equals("المحاسبة"), "المحاسب يختار اسمه الظاهر بنفسه");
        check(throwsMsg(() -> A.setNick(clerk, "شيء"), null), "ولا يغيّر اسم غيره بلا صلاحية المستخدمين");
        check(throwsMsg(() -> A.setNick(acct, "المدير"), "مستخدم آخر"), "ولا ينتحل اسم مستخدم آخر");
        as(A, admin);
        syncAll(eA, eB);
        as(B, acct);
        boolean onB = false;
        for (Model.Tx t : B.recentTx(500)) if (t.id == nt) onB = t.userName.equals("أبو سامي");
        check(onB, "الاسم الظاهر يصل بالمزامنة ويُطبَّق على الجهاز الآخر");
        boolean noRoleNoise = true;
        for (String[] c : SyncEngine.conflicts(B)) if (c[2].equals("ROLE_CHANGED") && c[5].contains("أبو سامي")) noRoleNoise = false;
        check(noRoleNoise, "تغيير الاسم الظاهر لا يُعدّ تغيّر صلاحيات");

        System.out.println("== 12) المدير يعيد تسمية جهاز منضم");
        as(A, admin);
        as(B, admin);
        String devB = SyncEngine.status(B).device;
        SmartApp.currentUser = A.user(admin);
        eA.renameDevice(devB, "هاتف رنا");
        boolean inA = false;
        for (org.json.JSONObject m : SyncEngine.status(A).members) if (m.optString("dev").equals(devB)) inA = m.optString("name").equals("هاتف رنا");
        check(inA, "الاسم الجديد يظهر عند المدير فورًا");
        eB.sync();
        check("هاتف رنا".equals(SyncEngine.status(B).deviceName), "والجهاز نفسه يعتمده بعد مزامنته: " + SyncEngine.status(B).deviceName);
        eB.sync();
        eA.sync();
        inA = false;
        for (org.json.JSONObject m : SyncEngine.status(A).members) if (m.optString("dev").equals(devB)) inA = m.optString("name").equals("هاتف رنا");
        check(inA, "ويبقى بعد جولات المزامنة");
        boolean noNews = true;
        for (String[] c : SyncEngine.conflicts(A)) if ((c[2].equals("DEVICE_GONE") || c[2].equals("DEVICE_JOINED")) && c[5].contains("رنا")) noNews = false;
        check(noNews, "التسمية لا تُحسب خروج جهاز أو دخوله");
        as(A, clerk);
        check(throwsMsg(() -> {
            try {
                eA.renameDevice(devB, "اسم آخر");
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, null), "غير المدير لا يسمّي الأجهزة");

        System.out.println("== 13) رسائل المدير إلى المحاسبين");
        as(A, clerk);
        check(throwsMsg(() -> A.sendAdminMsg(null, "مرحبا"), null), "غير المدير لا يرسل");
        as(A, admin);
        A.sendAdminMsg(null, "اجتماع الساعة 5 للجميع");
        String only = A.sendAdminMsg(acct, "راجعي حساب النور اليوم");
        check(A.unreadAdminMsgs().isEmpty(), "المرسل لا تظهر له رسائله");
        as(A, clerk);
        check(A.unreadAdminMsgs().size() == 1 && A.unreadAdminMsgs().get(0).body.contains("اجتماع"), "محاسب يرى رسالة «الكل» فقط");
        as(A, acct);
        check(A.unreadAdminMsgs().size() == 2, "والمحاسب المقصود يرى رسالته ورسالة الكل");
        check(A.adminMsgsToNotify().size() == 2 && A.adminMsgsToNotify().isEmpty(), "الإشعار مرة واحدة لكل رسالة");
        A.markAdminMsgRead(only);
        check(A.unreadAdminMsgs().size() == 1, "«قرأتها» تخفيها عنه وحده");
        as(A, admin);
        SmartApp.currentUser = A.user(admin);
        syncAll(eA, eB);
        as(B, acct);
        check(B.unreadAdminMsgs().size() == 2 && B.adminMsgsToNotify().size() == 2, "الرسائل تصل بالمزامنة إلى جهاز المحاسب وتُشعره");
        as(A, admin);
        A.deleteAdminMsg(only);
        syncAll(eA, eB);
        as(B, acct);
        check(B.unreadAdminMsgs().size() == 1, "سحب المدير للرسالة يخفيها عند من لم يقرأها");
        as(A, admin);
        check(A.adminMsgsSent(10).size() == 1, "وقائمة المرسل تعرض الباقي");
        boolean h17 = true;
        for (Db.Check c : A.healthCheck(true)) if (!c.ok) {
            h17 = false;
            System.out.println("      ✗ " + c.title + ": " + c.detail);
        }
        check(h17, "فحص الصحة سليم");
        as(A, 0);
        as(B, 0);

        System.out.println("== 8) نسخة احتياطية من مخطط أحدث لا تُستعاد");
        Object[] dv = DeskCoreTest.device("f1016R");
        Context ctxR = (Context) dv[0];
        Db R = (Db) dv[1];
        long off = R.addOffice("مكتب", "", "", "", "", "", null, null, ue, new HashMap<>(), Arrays.asList(new Db.Opening("USD", "لنا", 5000)));
        BackupManager.Created cr = BackupManager.createLocal(ctxR, R, "كلمة-النسخة".toCharArray(), "10.16", m -> {});
        BackupManager.Prepared prep = BackupManager.prepareRestore(ctxR, cr.file, "كلمة-النسخة".toCharArray(), m -> {});
        check(prep.parsed.schema() == Db.VER && prep.parsed.tables.containsKey("audit_feed"), "النسخة تحمل المخطط " + Db.VER + " وجدول السجل المشترك");
        prep.parsed.manifest.put("schema", Db.VER + 1);
        String before = String.valueOf(R.balances(off));
        boolean refused8 = false;
        try {
            BackupManager.applyRestore(ctxR, R, prep, m -> {});
        } catch (Exception e) {
            String msgs = "";
            for (Throwable t = e; t != null; t = t.getCause()) msgs += t.getMessage() + " | ";
            refused8 = msgs.contains("أحدث");
            if (!refused8) System.out.println("      رسالة: " + msgs);
        }
        check(refused8 && String.valueOf(R.balances(off)).equals(before), "رُفضت برسالة واضحة والبيانات لم تُمس");
        BackupManager.Prepared ok = BackupManager.prepareRestore(ctxR, cr.file, "كلمة-النسخة".toCharArray(), m -> {});
        BackupManager.applyRestore(ctxR, R, ok, m -> {});
        check(String.valueOf(R.balances(off)).equals(before), "والنسخة بالمخطط الحالي تُستعاد كما هي");

        System.out.println("== 9) ترحيل قاعدة 10.4 إلى المخطط " + Db.VER);
        File src = new File(data, "db-10.4"), homeM = Files.createTempDirectory("sa-1016-mig").toFile();
        MigrationTest.copy(src, homeM);
        Db M = MigrationTest.open(homeM);
        org.json.JSONObject want = new org.json.JSONObject(new String(Files.readAllBytes(new File(homeM, "expect.json").toPath()), "UTF-8"));
        check(want.getString("bal").equals(String.valueOf(new TreeMap<>(M.officeBalances()))), "الأرصدة كما هي بعد الترحيل");
        int ver;
        try (Cursor c = M.getReadableDatabase().rawQuery("PRAGMA user_version", null)) {
            c.moveToFirst();
            ver = c.getInt(0);
        }
        boolean feedTable;
        try (Cursor c = M.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM audit_feed", null)) {
            feedTable = c.moveToFirst() && c.getInt(0) == 0;
        }
        check(ver == Db.VER && feedTable, "المخطط صار " + ver + " وجدول السجل المشترك أُضيف فارغًا");
        // 11.6: سجل العمليات يحتاج صلاحية «سجل العمليات» في طبقة البيانات: يُقرأ هنا بصفة المدير
        for (Model.User mu : M.users(false)) if (Auth.isAdmin(mu)) as(M, mu.id);
        check(!M.auditLogAll(20).isEmpty() && M.auditLogAll(20).size() == M.auditLog(20).size(), "وسجل العمليات القديم يُقرأ كما كان");
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);

        System.out.println("== 10) إضافة ملف النقل: اعتماد رصيد الملف بدل الجمع");
        org.json.JSONObject expect = new org.json.JSONObject(new String(Files.readAllBytes(new File(data, "expect.json").toPath()), "UTF-8"));
        String pw = expect.getJSONObject("A").getString("password");
        Object[] d1 = DeskCoreTest.device("f1016M1");
        Context ctx1 = (Context) d1[0];
        Db M1 = (Db) d1[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        BackupManager.Prepared p1 = BackupManager.prepareRestore(ctx1, new File(data, "A.sba"), pw.toCharArray(), m -> {});
        MergePlan first1 = MergePlan.build(p1.parsed.tables, p1.parsed.manifest, M1.mergeExisting());
        check(first1.ok() && first1.adoptable() == 0, "أول إضافة: كل المكاتب جديدة فلا خيار اعتماد");
        M1.mergeMigration(first1);
        Map<String, Long> fileBal = new TreeMap<>();
        for (Model.Office o : M1.offices()) for (Map.Entry<String, Long> e : M1.balances(o.id).entrySet()) if (e.getValue() != 0) fileBal.put(o.name + "|" + e.getKey(), e.getValue());
        // رصيد إضافي على مكتب موجود، ثم «الملف نفسه» بهوية أخرى (كما لو صُدّر من المحاسب القديم لاحقًا)
        Model.Office any = M1.offices().get(0);
        String anyCur = M1.balances(any.id).keySet().iterator().next();
        M1.addTx(tx(any.id, true, 12345, anyCur, "حركة بعد الإضافة"), null);
        M1.getWritableDatabase().execSQL("DELETE FROM settings WHERE k='merged_files'");
        Map<String, List<org.json.JSONObject>> t2 = new HashMap<>();
        for (Map.Entry<String, List<org.json.JSONObject>> e : p1.parsed.tables.entrySet()) {
            List<org.json.JSONObject> l = new ArrayList<>();
            for (org.json.JSONObject o : e.getValue()) {
                org.json.JSONObject x = new org.json.JSONObject(o.toString());
                if (e.getKey().equals("tx")) x.put("uuid", "again-" + x.optString("uuid"));
                l.add(x);
            }
            t2.put(e.getKey(), l);
        }
        MergePlan sum = MergePlan.build(t2, p1.parsed.manifest, M1.mergeExisting());
        if (!sum.ok()) System.out.println("      خطة: " + sum.errors);
        check(sum.ok() && sum.adoptable() > 0 && sum.newOffices == 0, "الملف مرة ثانية: كل مكاتبه موجودة وعليها أرصدة (" + sum.adoptable() + ")");
        MergePlan.Row row = null;
        for (MergePlan.Row r : sum.rows) if (r.office.equals(any.name) && r.code.equals(anyCur)) row = r;
        check(row != null && row.after == row.current + row.added, "الافتراضي كما كان: الرصيد الحالي + رصيد الملف");
        sum.setAdopt(true);
        check(row.after == row.added, "مع «اعتماد رصيد الملف»: المعاينة = رصيد الملف وحده");
        int txBefore = M1.recentTx(100000).size();
        M1.mergeMigration(sum);
        Map<String, Long> got = new TreeMap<>();
        for (Model.Office o : M1.offices()) for (Map.Entry<String, Long> e : M1.balances(o.id).entrySet()) if (e.getValue() != 0) got.put(o.name + "|" + e.getKey(), e.getValue());
        check(got.equals(fileBal), "بعد الإضافة: رصيد كل مكتب = رصيد الملف تمامًا (لا قديم + جديد)");
        check(M1.recentTx(100000).size() >= txBefore, "لم تُحذف أي حركة من الدفتر (الرصيد السابق أُلغي بقيد تسوية)");
        boolean h = true;
        for (Db.Check c : M1.healthCheck(true)) if (!c.ok) {
            h = false;
            System.out.println("      ✗ " + c.title + ": " + c.detail);
        }
        check(h, "فحص الصحة الكامل سليم بعد الاعتماد");

        System.out.println("\n" + passes + " نجح، " + fails + " فشل");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean feedHasPrefix(Db db, String prefix, String devName) {
        for (String[] r : db.auditLogAll(500)) if (r[1].contains(prefix) && r[6].contains(devName)) return true;
        return false;
    }

    static boolean feedHasAny(Db db, String devName) {
        for (String[] r : db.auditLogAll(500)) if (r[6].contains(devName) && !r[6].contains("هذا الجهاز")) return true;
        return false;
    }
}
