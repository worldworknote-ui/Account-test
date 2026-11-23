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
 * هذا الجزء يُبنى ويُشغَّل بكود 10.4 (قبل التعديل).
 */
public class MigrationMake {
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

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        File home = new File(a[1]);
        if ("make".equals(a[0])) {
            home.mkdirs();
            Db db = open(home);
            List<String> ue = Arrays.asList("USD", "EUR");
            long A = db.addOffice("القديم أ", "", "", "", "", "", null, null, ue, new HashMap<>(), Arrays.asList(new Db.Opening("USD", "لنا", 30000)));
            long B = db.addOffice("القديم ب", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
            Map<String, Long> op = new HashMap<>();
            op.put("USD", 500000L);
            db.cashSetup("الصندوق", ue, op, false, true);
            long admin = db.addUser("المدير القديم", Auth.ADMIN, null, "123456");
            SmartApp.currentUser = db.user(admin);
            Db.currentUserName = SmartApp.currentUser.name;
            long clerk = db.addUser("موظف قديم", Auth.CUSTOM, "VIEW,TX_ADD,REVIEW", "1111");
            db.addTx(tx(A, true, 100000, "USD", "سامر"), Arrays.asList(new byte[]{1, 2, 3}));
            db.addTx(tx(B, false, 25000, "EUR", "أحمد"), null);
            long t3 = db.addTx(tx(A, false, 4000, "USD", "للتعديل"), null);
            db.editTx(t3, tx(A, false, 4500, "USD", "للتعديل"), null, null, "تصحيح");
            db.addFx(A, "USD", 11200, "EUR", 10000, "0.89285714", "تبديل قديم", System.currentTimeMillis() - 30_000);
            SmartApp.currentUser = db.user(clerk);
            Db.currentUserName = SmartApp.currentUser.name;
            db.addTx(tx(B, true, 7000, "USD", "معلّقة"), null);
            SmartApp.currentUser = null;
            Files.write(new File(home, "expect.json").toPath(), state(db).toString(1).getBytes(StandardCharsets.UTF_8));
            db.close();
            System.out.println("MADE with app " + BuildInfo.VERSION);
            return;
        }
    }
}
