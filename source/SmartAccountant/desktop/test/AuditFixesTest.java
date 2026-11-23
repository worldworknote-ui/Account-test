import android.content.Context;
import com.smart.hawala.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * 10.11: إصلاحات تقرير التدقيق على Db الحقيقي (sqlite-jdbc). كل فحص هنا كان يفشل في 10.10:
 * AUDIT-001 (تغيير رمز حساب أعلى صلاحية)، 002 (نقل مجلد البيانات)، 004 (منازل عملة الصندوق)، 010 (الدمج والأدوار)،
 * 011 (صلاحيات المطابقة في Db)، 013 (فحص عنوان الخادم)، 016 (تكرار الأسماء).
 */
public class AuditFixesTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    interface Io { void run() throws Exception; }

    static boolean refused(Io r) {
        try {
            r.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    static void as(Db db, long uid) {
        SmartApp.currentUser = db.user(uid);
        Db.currentUserName = SmartApp.currentUser.name;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        Object[] dv = DeskCoreTest.device("audit");
        Context ctx = (Context) dv[0];
        Db db = (Db) dv[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        List<String> ue = Arrays.asList("USD", "EUR");
        long A = db.addOffice("البلسم", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        long B = db.addOffice("النور", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
        Map<String, Long> op = new HashMap<>();
        op.put("USD", 1000000L);
        db.cashSetup("الصندوق", ue, op, false, true);
        long admin = db.addUser("المدير", Auth.ADMIN, null, "123456");
        as(db, admin);
        long mgr = db.addUser("مدير الموظفين", Auth.CUSTOM, "VIEW,USERS", "4444");
        long power = db.addUser("أمين", Auth.CUSTOM, "VIEW,RESTORE,DELETE", "5555");
        long peer = db.addUser("سعاد", Auth.CUSTOM, "VIEW", "7777");
        long acct = db.addUser("رنا", Auth.CUSTOM, "VIEW,TX_ADD,VERIFY", "2222");
        long chk = db.addUser("خالد", Auth.CUSTOM, "VIEW,VERIFY", "3333");
        long viewer = db.addUser("مشاهد", Auth.CUSTOM, "VIEW", "6666");

        System.out.println("== AUDIT-001: تغيير رمز حساب آخر");
        as(db, mgr);
        check(refused(() -> db.setPin(power, "9999")) && db.verifyPin(power, "5555"), "صاحب «المستخدمون» لا يغيّر رمز حساب فيه صلاحية لا يملكها، ورمزه باقٍ");
        db.setPin(peer, "8888");
        check(db.verifyPin(peer, "8888"), "ويغيّر رمز موظف صلاحياته ضمن صلاحياته");
        as(db, admin);
        db.setPin(power, "9999");
        check(db.verifyPin(power, "9999"), "المدير يغيّر أي رمز");

        System.out.println("== AUDIT-004: منازل عملة مستعملة في الصندوق فقط");
        db.saveCurrency("GLD", "ذهب", 2, true);
        check(!db.currencyUsed("GLD"), "عملة جديدة غير مستعملة");
        Map<String, Long> op2 = new HashMap<>();
        op2.put("GLD", 150000L);
        db.cashSetup(db.currentBox(), "الصندوق", Arrays.asList("USD", "EUR", "GLD"), op2, false, true);
        check(db.currencyUsed("GLD"), "رصيد صندوق بها = مستعملة");
        check(refused(() -> db.saveCurrency("GLD", "ذهب", 0, true)) && db.decimals("GLD") == 2, "تغيير منازلها يُرفض في طبقة البيانات");
        db.saveCurrency("GLD", "ذهب خالص", 2, true);
        check("ذهب خالص".equals(db.currencyNames().get("GLD")), "تغيير الاسم بالمنازل نفسها مسموح");

        System.out.println("== AUDIT-010: الدمج يحفظ أدوار المحاسبين");
        db.setOfficeTeam(A, new HashSet<>(Collections.singletonList(acct)), new HashSet<>(Collections.singletonList(chk)));
        db.mergeOffices(A, B);
        check(Db.ROLE_CHECK.equals(db.officeRoles(chk).get(B)) && Db.ROLE_OWN.equals(db.officeRoles(acct).get(B)), "خالد يبقى «يتحقق» ورنا «تسجّل» في المكتب الهدف");

        System.out.println("== AUDIT-011: المطابقة بصلاحياتها في Db");
        as(db, viewer);
        check(refused(() -> db.createRecon(B)), "مستخدم VIEW فقط لا ينشئ مطابقة");
        as(db, admin);
        long rid = db.createRecon(B);
        as(db, viewer);
        check(refused(() -> db.setReconStatus(rid, Model.RECON_CONFIRMED, "")) && refused(() -> db.deleteRecon(rid)), "ولا يؤكدها ولا يحذفها");
        as(db, admin);
        db.setReconStatus(rid, Model.RECON_CONFIRMED, "تم");
        db.deleteRecon(rid);
        check(db.recon(rid) == null, "المدير يؤكد ويحذف");

        System.out.println("== AUDIT-016: تكرار الأسماء");
        check(refused(() -> db.addUser("رنا", Auth.CUSTOM, "VIEW", "1212")), "اسم موجود يُرفض عند الإضافة");
        check(refused(() -> db.addUser(" رنا ", Auth.CUSTOM, "VIEW", "1212")), "ولو بمسافات زائدة");
        check(refused(() -> db.updateUser(viewer, "رنا", Auth.CUSTOM, "VIEW", true)), "وعند تعديل اسم حساب آخر إليه");
        db.updateUser(viewer, "مشاهد", Auth.CUSTOM, "VIEW", true);
        check(true, "حفظ الحساب باسمه نفسه مسموح");

        System.out.println("== AUDIT-013: عنوان خادم المزامنة");
        check(refused(() -> HttpSyncFolder.checkUrl("http://localhost.attacker.example/")) && refused(() -> HttpSyncFolder.checkUrl("http://127.0.0.1.nip.io/")),
                "http لمضيف يبدأ بـ localhost أو 127.0.0.1 فقط يُرفض");
        check(!refused(() -> HttpSyncFolder.checkUrl("http://127.0.0.1:8090")) && !refused(() -> HttpSyncFolder.checkUrl("https://sync.example.com")),
                "http لهذا الجهاز وhttps لأي خادم مقبولان");

        System.out.println("== AUDIT-002: نقل مجلد البيانات");
        Db.TxInput t = new Db.TxInput();
        t.office = B;
        t.type = Model.TYPE_DEBIT;
        t.amount = 1000;
        t.currency = "USD";
        t.person = "زبون";
        t.when = System.currentTimeMillis() - 5000;
        long tid = db.addTx(t, Collections.singletonList(new byte[]{1, 2, 3, 4}));
        long att = db.attachmentIds(tid).get(0);
        db.close();
        File oldHome = ctx.home();
        File newHome = new File(oldHome.getParentFile(), oldHome.getName() + "-moved");
        Files.move(oldHome.toPath(), newHome.toPath());
        SecretStore.fallback(new File(newHome, "keys"));
        Context c2 = new Context(newHome);
        DeskCoreTest.resetCrypto();
        Crypto.init(c2);
        Db db2 = new Db(c2);
        SmartApp.db = db2;
        byte[] img = db2.attachment(att);
        check(img != null && img.length == 4 && img[3] == 4, "الصورة تُقرأ بعد نقل المجلد");
        boolean imagesOk = false;
        for (Db.Check ch : db2.healthCheck(true)) if (ch.title.startsWith("الصور")) imagesOk = ch.ok;
        check(imagesOk, "وفحص الصحة يجدها");

        System.out.println();
        System.out.println(passes + " نجح، " + fails + " فشل");
        System.exit(fails == 0 ? 0 : 1);
    }
}
