import com.smart.hawala.*;
import java.io.*;
import java.util.*;
public class ListTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static Model.Tx tx(long id, String type, long amt, String cur, String status) {
    Model.Tx t = new Model.Tx(); t.id = id; t.officeId = 1; t.type = type; t.amount = amt; t.currency = cur; t.status = status;
    t.person = "شخص " + id; t.description = "بيان"; t.notes = ""; t.uuid = "abcd-" + id; t.feeType = Model.FEE_NONE; t.feeCurrency = cur; t.createdAt = 1760000000000L + id * 1000; t.userName = "";
    return t; }
  public static void main(String[] a) throws Exception {
    List<Model.Tx> txs = Arrays.asList(tx(1, Model.TYPE_DEBIT, 1000, "USD", "ACTIVE"), tx(2, Model.TYPE_CREDIT, 500, "USD", "CANCELLED"));
    Map<Long,String> names = new HashMap<>(); names.put(1L, "مكتب");
    Map<String,Integer> dec = new HashMap<>(); dec.put("USD", 2);
    Map<Long,List<Long>> att = new HashMap<>(); att.put(1L, Arrays.asList(7L, 8L));
    Report r = Reports.txList("نتائج البحث", Arrays.asList("بحث: x"), txs, names, dec, "مكتبي", 1760000000000L, att);
    check(r.dataRows() == 2, "txList has 2 rows");
    Report.Section s = r.sections.get(0);
    check(s.rows.get(0).images != null && s.rows.get(0).images.size() == 2, "images attached to tx row 1");
    check(s.rows.get(1).images == null, "no images for row 2");
    Report r2 = Reports.txList("سجل", new ArrayList<String>(), txs, names, dec, "", 1760000000000L);
    check(r2.sections.get(0).rows.get(0).images == null, "old overload: no images");
    Map<String,long[]> stats = new TreeMap<>(); stats.put("USD", new long[]{1000, 0, 0, 0, 1});
    Report d = Reports.daily("2025/10/09", stats, txs, names, dec, "مكتبي", 1760000000000L, att);
    check(d.dataRows() == 3, "daily has stats + 2 tx rows: " + d.dataRows());
    try (OutputStream o = new FileOutputStream(a[0] + "/list.xlsx")) { XlsxWriter.write(r, o, "FF0B6B53", TimeZone.getTimeZone("Asia/Damascus")); }
    try (OutputStream o = new FileOutputStream(a[0] + "/daily.xlsx")) { XlsxWriter.write(d, o, "FF0B6B53", TimeZone.getTimeZone("Asia/Damascus")); }
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
