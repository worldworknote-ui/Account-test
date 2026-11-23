import com.smart.hawala.BackupFormat;
import com.smart.hawala.SecureStreams;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * يتحقق أن ملفات أداة النقل (المكتوبة بـ JavaScript في المتصفح) تُفك وتُقرأ وتُفحص بالكود نفسه الذي يستخدمه التطبيق
 * عند الاستعادة، وأن الأرصدة المحسوبة من أسطر القيود تطابق القيم المتوقعة المحسوبة يدويًا.
 * الاستخدام: java MigrationTest <مجلد الملفات>
 */
public class MigrationTest {
    static int fails = 0;

    static void check(String name, boolean ok, Object extra) {
        if (ok) System.out.println("  PASS " + name);
        else {
            fails++;
            System.out.println("  FAIL " + name + (extra == null ? "" : " — " + extra));
        }
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        JSONObject expect = new JSONObject(new String(Files.readAllBytes(Paths.get(dir, "expect.json")), StandardCharsets.UTF_8));
        for (String name : new java.util.TreeSet<>(expect.keySet())) {
            System.out.println("== " + name + ".sba");
            JSONObject e = expect.getJSONObject(name);
            // كلمة مرور خاطئة يجب أن تُرفض برسالة كلمة المرور
            try (InputStream in = new FileInputStream(dir + "/" + name + ".sba")) {
                new SecureStreams.DecryptingInputStream(in, "wrong-password".toCharArray());
                check("wrong password rejected", false, null);
            } catch (SecureStreams.WrongPasswordException ok) {
                check("wrong password rejected", true, null);
            }
            BackupFormat.Parsed p;
            try (InputStream in = new FileInputStream(dir + "/" + name + ".sba")) {
                SecureStreams.DecryptingInputStream dec = new SecureStreams.DecryptingInputStream(in, e.getString("password").toCharArray());
                p = BackupFormat.readV5(dec, new BackupFormat.AttachmentStore() {
                    @Override public Object store(long id, InputStream data) throws Exception {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = data.read(b)) > 0) o.write(b, 0, n);
                        return o.toByteArray();
                    }

                    @Override public void discard(Object handle) {}
                });
                check("stream fully verified (last chunk signed)", dec.isComplete() || readRest(dec), null);
            }
            BackupFormat.Report r = BackupFormat.validate(p);
            check("validate ok", r.ok(), r.errors);
            check("offices " + e.getInt("offices"), r.offices == e.getInt("offices"), r.offices);
            check("active tx " + e.getInt("tx"), r.txActive == e.getInt("tx") && r.txCancelled == 0, r.txActive);
            check("entries " + e.getInt("entries"), r.entries == e.getInt("entries"), r.entries);
            check("schema 6", p.schema() == 6, p.schema());
            check("no users table (keeps device users)", !p.tables.containsKey("users"), null);
            // الأرصدة كما يحسبها التطبيق: SUM(debit-credit) لحساب OFFICE حسب المكتب والعملة
            Map<Long, String> names = new HashMap<>();
            for (JSONObject o : p.table("offices")) names.put(o.getLong("id"), o.getString("name"));
            Map<String, Long> bal = new HashMap<>();
            for (JSONObject l : p.table("journal_lines")) {
                if (!"OFFICE".equals(l.getString("account"))) continue;
                String k = names.get(l.getLong("office_id")) + "|" + l.getString("currency");
                bal.merge(k, l.getLong("debit_minor") - l.getLong("credit_minor"), Long::sum);
            }
            JSONArray exp = e.getJSONArray("balances");
            for (int i = 0; i < exp.length(); i++) {
                JSONArray x = exp.getJSONArray(i);
                String k = x.getString(0) + "|" + x.getString(1);
                Long got = bal.get(k);
                check("balance " + k + " = " + x.getLong(2), got != null && got == x.getLong(2), got);
            }
            // كل حركة لها قيد أصل، والأجرة قيد مستقل، وكل قيد سطران
            Map<Long, Integer> perEntry = new HashMap<>();
            for (JSONObject l : p.table("journal_lines")) perEntry.merge(l.getLong("entry_id"), 1, Integer::sum);
            boolean two = true;
            for (int n : perEntry.values()) if (n != 2) two = false;
            check("every entry has exactly 2 lines", two, perEntry);
            for (JSONObject t : p.table("tx")) {
                int principal = 0;
                for (JSONObject en : p.table("journal_entries")) {
                    if (!en.isNull("tx_id") && en.getLong("tx_id") == t.getLong("id") && "PRINCIPAL".equals(en.getString("kind"))) principal++;
                }
                if (principal != 1) {
                    check("tx " + t.getLong("id") + " has one PRINCIPAL entry", false, principal);
                }
            }
            // تفريغ الجداول لاختبار SQLite
            JSONObject dump = new JSONObject();
            for (Map.Entry<String, java.util.List<JSONObject>> en : p.tables.entrySet()) dump.put(en.getKey(), new JSONArray(en.getValue()));
            try (FileOutputStream f = new FileOutputStream(dir + "/" + name + ".tables.json")) {
                f.write(dump.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        System.out.println(fails == 0 ? "ALL MIGRATION FILE TESTS PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean readRest(InputStream in) throws Exception {
        byte[] b = new byte[4096];
        while (in.read(b) > 0) { /* حتى النهاية */ }
        return ((SecureStreams.DecryptingInputStream) in).isComplete();
    }
}
