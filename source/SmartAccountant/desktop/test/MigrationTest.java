import android.content.Context;
import com.smart.hawala.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

/**
 * 10.5: ترحيل قاعدة بيانات حقيقية البنية من الإصدار 10.4 (لا من نسخة احتياطية): يُنشئها كود 10.4 نفسه («make»)،
 * ثم يفتحها كود 10.5 («check»): الأرصدة والحركات والمستخدمون والصندوق كما هي بالضبط، والأعمدة والجداول الجديدة أُضيفت،
 * والميزات الجديدة تعمل على البيانات القديمة (التحقق من حركة قديمة بلا رقم مستخدم، والتحويل، والعلامة)، وفحص الصحة سليم.
 * الاستعمال: java MigrationTest tests/data/db-10.4
 */
public class MigrationTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Db open(File home) throws Exception {
        SecretStore.fallback(new File(home, "keys"));
        Context ctx = new Context(home);
        Crypto.init(ctx);
        Db db = new Db(ctx);
        SmartApp.db = db;
        SmartApp.ctx = ctx;
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        return db;
    }

    static Db.TxInput tx(long office, boolean us, long amount, String cur, String person) {
        Db.TxInput t = new Db.TxInput();
        t.office = office;
        t.type = us ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        t.amount = amount;
        t.currency = cur;
        t.person = person;
        t.when = System.currentTimeMillis() - 60_000;
        return t;
    }

    static JSONObject state(Db db) throws Exception {
        JSONObject o = new JSONObject();
        o.put("bal", String.valueOf(new TreeMap<>(db.officeBalances())));
        o.put("cash", String.valueOf(new TreeMap<>(db.cashBalances())));
        o.put("tx", db.recentTx(100).size());
        o.put("pending", db.pendingTx().size());
        o.put("users", db.users(false).size());
        o.put("offices", db.offices().size());
        return o;
    }

    static void copy(File from, File to) throws Exception {
        to.mkdirs();
        for (File f : from.listFiles()) {
            File t = new File(to, f.getName());
            if (f.isDirectory()) copy(f, t);
            else Files.copy(f.toPath(), t.toPath());
        }
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        // نسخة من القاعدة الاصطناعية (لا تُعدَّل الأصلية)
        File src = new File(a[a.length - 1]);
        File home = Files.createTempDirectory("sa-mig").toFile();
        copy(src, home);
        System.out.println("== ترحيل قاعدة 10.4 إلى " + BuildInfo.VERSION);
        JSONObject want = new JSONObject(new String(Files.readAllBytes(new File(home, "expect.json").toPath()), StandardCharsets.UTF_8));
        Db db = open(home);
        JSONObject got = state(db);
        check(want.getString("bal").equals(got.getString("bal")), "أرصدة المكاتب كما هي: " + got.getString("bal"));
        check(want.getString("cash").equals(got.getString("cash")), "أرصدة الصندوق كما هي: " + got.getString("cash"));
        check(want.getInt("tx") == got.getInt("tx") && want.getInt("pending") == got.getInt("pending") && want.getInt("users") == got.getInt("users")
                && want.getInt("offices") == got.getInt("offices"), "عدد الحركات والمعلّقة والمستخدمين والمكاتب كما هو");
        Set<String> cols = new HashSet<>();
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("PRAGMA table_info(tx)", null)) {
            while (c.moveToNext()) cols.add(c.getString(1));
        }
        boolean tables = true;
        for (String t : new String[]{"tx_checks", "wa_marks", "user_offices"})
            try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + t, null)) {
                c.moveToFirst();
            } catch (Exception e) {
                tables = false;
            }
        check(cols.contains("user_id") && cols.contains("approved_by") && cols.contains("approved_at") && tables, "الأعمدة والجداول الجديدة أُضيفت دون مسّ القديم");
        // الميزات الجديدة على البيانات القديمة
        Model.User admin = null, clerk = null;
        for (Model.User u : db.users(false)) {
            if (Auth.isAdmin(u)) admin = u;
            else clerk = u;
        }
        SmartApp.currentUser = admin;
        Db.currentUserName = admin.name;
        long v = db.addUser("محقق جديد", Auth.CUSTOM, "VIEW,VERIFY", "4545");
        Model.Tx oldTx = null, clerkTx = null;
        for (Model.Tx t : db.recentTx(100)) {
            if (t.userId == null && "المدير القديم".equals(t.userName) && !t.isLinked()) oldTx = t;
        }
        for (Model.Tx t : db.pendingTx()) clerkTx = t;
        check(oldTx != null && db.txPeople(oldTx).get(0).contains("المدير القديم"), "الحركة القديمة بلا رقم مستخدم: يُعرض من أدخلها باسمه");
        final Model.Tx ot = oldTx;
        SmartApp.currentUser = db.user(v);
        db.verifyTx(ot.id);
        check(db.verified(db.tx(ot.id)), "التحقق من حركة قديمة يعمل");
        SmartApp.currentUser = admin;
        db.approveTx(clerkTx.id);
        Model.Tx ap = db.tx(clerkTx.id);
        check(ap.isActive() && ap.approvedBy != null && ap.approvedBy == admin.id, "اعتماد حركة معلّقة من 10.4 يسجّل من اعتمدها برقمه");
        List<Model.Office> offs = db.offices();
        Db.TransferInput tr = new Db.TransferInput();
        tr.from = offs.get(0).id;
        tr.to = offs.get(1).id;
        tr.currency = "USD";
        tr.amount = 1000;
        tr.when = System.currentTimeMillis();
        db.addTransfer(tr);
        db.setWaMark(offs.get(0).id, "آخر رسالة", "");
        boolean healthy = true;
        for (Db.Check c : db.healthCheck(true)) {
            if (!c.ok) {
                healthy = false;
                System.out.println("      ✗ " + c.title + ": " + c.detail);
            }
        }
        check(healthy, "فحص الصحة الكامل سليم بعد الترحيل والعمليات الجديدة");
        SmartApp.currentUser = null;
        System.out.println();
        System.out.println(passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }
}
