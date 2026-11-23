import com.smart.hawala.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
/**
 * 7.2: إضافة ملف النقل إلى البيانات الحالية (بدل الاستبدال). يقرأ جداول D (كشوفات حساب) التي يكتبها MigrationTest،
 * ويكتب خطة سيناريو «مكتب موجود» إلى ملف JSON يطبّقه اختبار SQLite بالاستعلامات نفسها في Db.mergeMigration.
 */
public class MergePlanTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static Map<String, List<JSONObject>> tables(JSONObject dump) {
    Map<String, List<JSONObject>> t = new LinkedHashMap<>();
    for (String k : dump.keySet()) {
      List<JSONObject> l = new ArrayList<>();
      JSONArray a = dump.getJSONArray(k);
      for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
      t.put(k, l);
    }
    return t;
  }
  static Map<String, List<JSONObject>> copy(Map<String, List<JSONObject>> t) {
    Map<String, List<JSONObject>> c = new LinkedHashMap<>();
    for (Map.Entry<String, List<JSONObject>> e : t.entrySet()) {
      List<JSONObject> l = new ArrayList<>();
      for (JSONObject o : e.getValue()) l.add(new JSONObject(o.toString()));
      c.put(e.getKey(), l);
    }
    return c;
  }
  static MergePlan.Row row(MergePlan p, String office, String code) {
    for (MergePlan.Row r : p.rows) if (r.office.equals(office) && r.code.equals(code)) return r;
    return null;
  }
  static MergePlan.Existing base() {
    MergePlan.Existing ex = new MergePlan.Existing();
    ex.decimals.put("USD", 2); ex.decimals.put("EUR", 2); ex.decimals.put("TRY", 2); ex.decimals.put("SYP", 0);
    return ex;
  }
  public static void main(String[] a) throws Exception {
    String dir = a[0];
    JSONObject dump = new JSONObject(new String(Files.readAllBytes(Paths.get(dir, "D.tables.json")), StandardCharsets.UTF_8));
    Map<String, List<JSONObject>> T = tables(dump);
    JSONObject man = new JSONObject().put("source", "migration").put("createdAt", 1760000000000L)
        .put("counts", new JSONObject().put("offices", 3).put("tx", 8));

    System.out.println("== empty app: everything new");
    MergePlan p0 = MergePlan.build(T, man, base());
    check(p0.ok(), "plan ok " + p0.errors);
    check(p0.newOffices == 3 && p0.matchedOffices == 0, "3 new offices");
    check(p0.txs.size() == 8 && p0.openings.size() == 2, "8 tx + 2 openings");
    check(row(p0, "الخال", "USD").after == 49500 && row(p0, "الخال", "EUR").after == -10000 && row(p0, "الأمل", "TRY").after == -101000
        && row(p0, "النور", "USD").after == 35000, "after = file balances");

    System.out.println("== existing «الخال» with لنا 100.00 USD, «الامل» spelled differently, and another office");
    MergePlan.Existing ex = base();
    ex.offices.put(7L, "الخال"); ex.offices.put(9L, "الامل"); ex.offices.put(11L, "مكتب آخر");
    ex.balances.put("7|USD", 10000L); ex.balances.put("9|TRY", 500L); ex.balances.put("11|USD", -77700L);
    ex.uuids.add("aaaa-bbbb");
    MergePlan p1 = MergePlan.build(T, man, ex);
    check(p1.ok(), "plan ok " + p1.errors);
    check(p1.matchedOffices == 2 && p1.newOffices == 1, "2 matched, 1 new (النور)");
    MergePlan.Row khal = row(p1, "الخال", "USD");
    check(khal != null && !khal.isNew && khal.current == 10000 && khal.added == 49500 && khal.after == 59500, "الخال USD: 100 + 495 = لنا 595");
    check(row(p1, "الخال", "EUR").current == 0 && row(p1, "الخال", "EUR").after == -10000, "الخال EUR added from zero");
    MergePlan.Row amal = row(p1, "الامل", "TRY");
    check(amal != null && amal.approximate && amal.after == 500 - 101000, "الأمل → existing «الامل» (approximate), 5.00 − 1,010.00");
    check(row(p1, "النور", "USD").isNew, "النور is new");
    check(p1.rows.stream().noneMatch(r -> r.office.equals("مكتب آخر")), "untouched office not in preview");
    Files.write(Paths.get(dir, "merge-plan.json"), planJson(p1, ex).toString(1).getBytes(StandardCharsets.UTF_8));

    System.out.println("== refusals");
    MergePlan.Existing dupName = base();
    dupName.offices.put(1L, "النور"); dupName.offices.put(2L, "النور");
    check(!MergePlan.build(T, man, dupName).ok(), "two existing offices with the same name → refused");
    MergePlan.Existing again = base();
    again.mergedFiles.add(p0.fingerprint);
    check(!MergePlan.build(T, man, again).ok(), "same file merged before → refused");
    MergePlan.Existing hasTx = base();
    hasTx.uuids.add(T.get("tx").get(0).getString("uuid"));
    check(!MergePlan.build(T, man, hasTx).ok(), "a tx uuid already present → refused");
    MergePlan.Existing locked = base();
    locked.lockBefore = 1893456000000L;
    check(!MergePlan.build(T, man, locked).ok(), "dates before accounting lock → refused");
    check(!MergePlan.build(T, new JSONObject().put("app", "x"), base()).ok(), "normal backup (no source=migration) → no merge");
    Map<String, List<JSONObject>> bad = copy(T);
    bad.get("opening_balances").get(0).put("amount_minor", bad.get("opening_balances").get(0).getLong("amount_minor") + 1);
    MergePlan pb = MergePlan.build(bad, man, base());
    check(!pb.ok() && pb.errors.get(0).contains("غير متسق"), "opening not matching its journal → refused");
    Map<String, List<JSONObject>> cancelled = copy(T);
    cancelled.get("tx").get(0).put("status", "CANCELLED");
    check(!MergePlan.build(cancelled, man, base()).ok(), "cancelled tx in file → refused");

    System.out.println("== decimals");
    MergePlan.Existing d3 = base();
    d3.decimals.put("USD", 3);
    MergePlan p3 = MergePlan.build(T, man, d3);
    check(p3.ok() && row(p3, "الخال", "USD").after == 495000 && !p3.warnings.isEmpty(), "USD with 3 decimals in app: ×10 exactly");
    MergePlan.Existing d0 = base();
    d0.decimals.put("TRY", 0);
    MergePlan p4 = MergePlan.build(T, man, d0);
    check(!p4.ok(), "TRY 1,000.50 into 0 decimals → refused (no rounding)");
    MergePlan.Existing nocur = base();
    nocur.decimals.remove("TRY");
    MergePlan p5 = MergePlan.build(T, man, nocur);
    check(p5.ok() && p5.newCurrencies.size() == 1 && p5.newCurrencies.get(0).code.equals("TRY"), "missing currency added");
    System.out.println(fails == 0 ? "ALL MERGE PLAN TESTS PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }

  /** الخطة كما ستطبّقها Db.mergeMigration، لاختبار SQLite. */
  static JSONObject planJson(MergePlan p, MergePlan.Existing ex) {
    JSONObject o = new JSONObject();
    JSONArray offs = new JSONArray();
    for (MergePlan.Office of : p.offices.values()) offs.put(new JSONObject().put("fileId", of.fileId).put("name", of.name).put("target", of.targetId == null ? JSONObject.NULL : of.targetId)
        .put("codes", new JSONArray(of.codes)));
    o.put("offices", offs);
    JSONArray ops = new JSONArray();
    for (MergePlan.Opening op : p.openings) ops.put(new JSONObject().put("office", op.fileOffice).put("code", op.code).put("signed", op.signed).put("when", op.when));
    o.put("openings", ops);
    JSONArray txs = new JSONArray();
    for (MergePlan.Tx x : p.txs) txs.put(new JSONObject().put("uuid", x.uuid).put("office", x.fileOffice).put("type", x.type).put("amount", x.amount).put("currency", x.currency)
        .put("feeType", x.feeType).put("fee", x.fee).put("feeCurrency", x.feeCurrency).put("when", x.when));
    o.put("txs", txs);
    JSONObject exp = new JSONObject();
    for (Map.Entry<String, Long> e : p.expected.entrySet()) exp.put(e.getKey(), e.getValue());
    o.put("expected", exp);
    JSONArray rows = new JSONArray();
    for (MergePlan.Row r : p.rows) rows.put(new JSONObject().put("office", r.office).put("code", r.code).put("after", r.after).put("isNew", r.isNew));
    o.put("rows", rows);
    JSONObject exo = new JSONObject();
    for (Map.Entry<Long, String> e : ex.offices.entrySet()) exo.put(String.valueOf(e.getKey()), e.getValue());
    o.put("existingOffices", exo);
    JSONObject exb = new JSONObject();
    for (Map.Entry<String, Long> e : ex.balances.entrySet()) exb.put(e.getKey(), e.getValue());
    o.put("existingBalances", exb);
    o.put("fingerprint", p.fingerprint);
    return o;
  }
}
