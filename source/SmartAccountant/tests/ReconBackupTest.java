import com.smart.hawala.*;
import org.json.*;
import java.util.*;
public class ReconBackupTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static BackupFormat.Parsed base() throws Exception {
    BackupFormat.Parsed p = new BackupFormat.Parsed();
    p.tables.put("currencies", new ArrayList<>(Arrays.asList(new JSONObject().put("code","USD").put("name","d").put("decimals",2).put("active",1))));
    p.tables.put("offices", new ArrayList<>(Arrays.asList(new JSONObject().put("id",1).put("name","a").put("active",1))));
    p.tables.put("tx", new ArrayList<JSONObject>()); p.tables.put("journal_entries", new ArrayList<JSONObject>()); p.tables.put("journal_lines", new ArrayList<JSONObject>());
    return p;
  }
  public static void main(String[] a) throws Exception {
    BackupFormat.Parsed p = base();
    p.tables.put("reconciliations", new ArrayList<>(Arrays.asList(new JSONObject().put("id",1).put("office_id",1).put("created_at",5).put("cutoff_entry",0).put("status","SENT"))));
    p.tables.put("reconciliation_lines", new ArrayList<>(Arrays.asList(new JSONObject().put("id",1).put("recon_id",1).put("currency","USD").put("balance_minor",100))));
    check(BackupFormat.validate(p).ok(), "valid reconciliation rows accepted");
    BackupFormat.Parsed q = base();
    q.tables.put("reconciliations", new ArrayList<>(Arrays.asList(new JSONObject().put("id",1).put("office_id",9).put("created_at",5).put("cutoff_entry",0).put("status","SENT"))));
    check(!BackupFormat.validate(q).ok(), "reconciliation for a missing office rejected");
    BackupFormat.Parsed r = base();
    r.tables.put("reconciliation_lines", new ArrayList<>(Arrays.asList(new JSONObject().put("id",1).put("recon_id",4).put("currency","USD").put("balance_minor",1))));
    check(!BackupFormat.validate(r).ok(), "orphan reconciliation line rejected");
    check(BackupFormat.validate(base()).ok(), "older backup without reconciliation tables still valid");
    check(Arrays.asList(BackupFormat.TABLES).indexOf("reconciliations") > Arrays.asList(BackupFormat.TABLES).indexOf("offices")
        && Arrays.asList(BackupFormat.TABLES).indexOf("reconciliation_lines") > Arrays.asList(BackupFormat.TABLES).indexOf("reconciliations"), "restore order: parents first");
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
  }
}
