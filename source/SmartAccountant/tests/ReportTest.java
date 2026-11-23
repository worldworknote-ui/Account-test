import com.smart.hawala.*;
import java.io.*;
import java.util.*;

public class ReportTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static Model.Line line(String kind, String cur, long t, long d, long c, long tx, String status, String person) {
    Model.Line l = new Model.Line(); l.kind = kind; l.currency = cur; l.createdAt = t; l.debit = d; l.credit = c; l.txId = tx; l.status = status;
    l.person = person; l.description = "بيان " + tx; l.txUuid = "uuid-" + tx + "-xxxx"; l.txType = d > 0 ? Model.TYPE_DEBIT : Model.TYPE_CREDIT; l.feeType = Model.FEE_US; return l; }
  static Report.Section sec(Report r, String sheet) { for (Report.Section s : r.sections) if (sheet.equals(s.sheetName)) return s; return null; }
  public static void main(String[] a) throws Exception {
    String out = a[0];
    long t0 = 1760000000000L;
    Reports.StatementInput in = new Reports.StatementInput();
    in.officeName = "مكتب الأمانة"; in.issuer = "مكتب أبو عمر"; in.currencyNames.put("USD", "دولار"); in.currencyNames.put("SYP", "ليرة");
    in.decimals.put("USD", 2); in.decimals.put("SYP", 0);
    in.lines.add(line("PRINCIPAL","USD", t0, 100000, 0, 1, "ACTIVE", "أحمد"));
    in.lines.add(line("FEE","USD", t0, 500, 0, 1, "ACTIVE", "أحمد"));
    in.lines.add(line("PRINCIPAL","USD", t0+1000, 0, 70000, 2, "ACTIVE", "سامر"));
    in.lines.add(line("PRINCIPAL","USD", t0+2000, 30000, 0, 3, "CANCELLED", "ملغى"));
    in.lines.add(line("REVERSAL","USD", t0+3000, 0, 30000, 3, "CANCELLED", "ملغى"));
    in.lines.add(line("PRINCIPAL","SYP", t0+4000, 0, 2600000, 4, "ACTIVE", "خالد"));
    in.fullBalance.put("USD", 30500L); in.fullBalance.put("SYP", -2600000L);
    in.attachments = new HashMap<>(); in.attachments.put(1L, Arrays.asList(11L, 12L));

    System.out.println("1) all-periods statement (the case that exported empty in 5.0)");
    ExportStyle st = new ExportStyle();
    Report r = Reports.statement(in, st);
    Report.Section usd = sec(r, "USD");
    check(usd != null && sec(r, "SYP") != null && sec(r, "ملخص") != null, "sheets: ملخص + USD + SYP");
    check(usd.rows.size() == 4, "USD rows = 3 active lines + totals (cancelled hidden): " + usd.rows.size());
    Report.Row last = usd.rows.get(usd.rows.size()-1);
    check(((Report.Money) last.cells[2]).v == 100500 && ((Report.Money) last.cells[3]).v == 70000, "totals: لنا 1,005.00 / علينا 700.00");
    check(((Report.Bal) last.cells[4]).v == 30500, "closing balance لنا 305.00 = full balance");
    check(Report.text(usd.rows.get(2).cells[4]).equals("لنا 305"), "running balance shown as 'لنا 305': " + Report.text(usd.rows.get(2).cells[4]));
    check(usd.rows.get(0).images != null && usd.rows.get(0).images.size() == 2, "images attached to principal row");
    check(usd.summary.size() >= 4, "detailed per-currency summary present (" + usd.summary.size() + " lines)");

    System.out.println("2) show cancelled -> both original and reversal appear, net unchanged");
    st.hideCancelled = false;
    Report r2 = Reports.statement(in, st);
    Report.Section usd2 = sec(r2, "USD");
    check(usd2.rows.size() == 6, "rows incl. cancelled pair + totals: " + usd2.rows.size());
    check(((Report.Bal) usd2.rows.get(usd2.rows.size()-1).cells[4]).v == 30500, "closing still 305.00");

    System.out.println("3) period with opening balance");
    st = new ExportStyle();
    Reports.StatementInput p = new Reports.StatementInput();
    p.officeName = "مكتب"; p.decimals.put("USD", 2); p.periodHasStart = true; p.periodLabel = "هذا الشهر";
    p.opening.put("USD", 50000L); p.fullBalance.put("USD", 80000L);
    p.lines.add(line("PRINCIPAL","USD", t0, 30000, 0, 9, "ACTIVE", "x"));
    Report r3 = Reports.statement(p, st);
    Report.Section u3 = sec(r3, "USD");
    check(Report.text(u3.rows.get(0).cells[1]).contains("الرصيد السابق"), "opening row first");
    check(((Report.Bal) u3.rows.get(u3.rows.size()-1).cells[4]).v == 80000, "closing = opening 500 + 300 = 800");

    System.out.println("4) selected-only export");
    Reports.StatementInput s = new Reports.StatementInput();
    s.officeName = "مكتب"; s.decimals.put("USD", 2); s.selectedOnly = true; s.fullBalance.put("USD", 999900L);
    s.lines.add(line("PRINCIPAL","USD", t0, 12300, 0, 5, "ACTIVE", "y"));
    Report r4 = Reports.statement(s, st);
    Report.Section u4 = sec(r4, "USD");
    check(u4.cols.size() == 4, "no running-balance column for a selection");
    check(r4.headerLines.toString().contains("المحددة"), "header says selected only");
    check(u4.rows.size() == 2, "1 selected row + totals");

    System.out.println("5) newest-first ordering keeps correct running balances");
    st.newestFirst = true;
    Report r5 = Reports.statement(in, st);
    Report.Section u5 = sec(r5, "USD");
    check(((Report.Bal) u5.rows.get(0).cells[4]).v == 30500, "top row shows latest balance 305.00");

    System.out.println("6) balances report: every لنا, every علينا, then balance per office");
    List<Model.OfficeTotals> offs = new ArrayList<>();
    Model.OfficeTotals o1 = new Model.OfficeTotals(); o1.officeId = 1; o1.name = "مكتب أ"; o1.active = true; o1.perCurrency.put("USD", new long[]{500000, 400000, 100000});
    Model.OfficeTotals o2 = new Model.OfficeTotals(); o2.officeId = 2; o2.name = "مكتب ب"; o2.active = true; o2.perCurrency.put("USD", new long[]{100000, 170000, -70000});
    offs.add(o1); offs.add(o2);
    Map<String,Integer> dec = new HashMap<>(); dec.put("USD", 2);
    Report rb = Reports.balances(offs, dec, new HashMap<String,String>(), false, "مكتبي", t0);
    Report.Section g = sec(rb, "الإجمالي");
    check(((Report.Money) g.rows.get(0).cells[1]).v == 100000 && ((Report.Money) g.rows.get(0).cells[2]).v == 70000, "grand totals لنا 1000 / علينا 700 (no netting)");
    Report.Section ofs = sec(rb, "المكاتب");
    check(ofs.rows.size() == 2 && ((Report.Money) ofs.rows.get(0).cells[2]).v == 500000 && ((Report.Money) ofs.rows.get(0).cells[3]).v == 400000, "office A: كل لنا 5000 / كل علينا 4000");
    check(Report.text(ofs.rows.get(1).cells[4]).equals("علينا 700"), "office B balance 'علينا 700'");

    System.out.println("7) WhatsApp number normalisation");
    check("963944123456".equals(Model.whatsappNumber("0944 123 456", "963")), "local 09.. -> 9639..");
    check("963944123456".equals(Model.whatsappNumber("+963 944-123-456", "963")), "+963 kept");
    check("905321112233".equals(Model.whatsappNumber("00905321112233", "963")), "00 prefix stripped");
    check(Model.whatsappNumber("123", "963") == null, "too short rejected");

    System.out.println("8) write Excel files for external validation");
    for (Object[] x : new Object[][]{{"statement.xlsx", r}, {"balances.xlsx", rb}, {"selected.xlsx", r4}}) {
      try (OutputStream o = new FileOutputStream(new File(out, (String) x[0]))) { XlsxWriter.write((Report) x[1], o, "FF0B6B53", TimeZone.getTimeZone("Asia/Damascus")); }
    }
    Report empty = Reports.statement(new Reports.StatementInput(), new ExportStyle());
    try (OutputStream o = new FileOutputStream(new File(out, "empty.xlsx"))) { XlsxWriter.write(empty, o, "FF0B6B53", TimeZone.getDefault()); }
    check(new File(out, "statement.xlsx").length() > 1000, "xlsx files written");
    System.out.println(fails == 0 ? "ALL REPORT TESTS PASSED" : (fails + " FAILURE(S)"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
