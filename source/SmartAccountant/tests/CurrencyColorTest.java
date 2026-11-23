import com.smart.hawala.*;
import java.io.FileOutputStream;
import java.util.*;

/** ألوان العملات في الكشوفات: الأقسام والصفوف تأخذ لون عملتها، والأرقام لا تتغير، وملف Excel صالح. */
public class CurrencyColorTest {
    static int fails = 0;
    static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
    public static void main(String[] a) throws Exception {
        ExportStyle st = new ExportStyle();
        check(st.curColors, "currency colors on by default");
        check(st.colorFor("USD") == ExportStyleColors.hex("#1565C0") && st.colorFor("EUR") != st.colorFor("USD"), "default colors differ per currency");
        st.curColorMap = "EUR=#AD1457";
        check(st.colorFor("EUR") == ExportStyleColors.hex("#AD1457"), "user color for EUR");
        check(st.colorFor("XYZ") == st.colorFor("XYZ") && st.colorFor("XYZ") != 0, "stable color for unknown code");
        ExportStyle back = ExportStyle.from(st.toMap());
        check(back.curColors && back.curColorMap.equals("EUR=#AD1457"), "settings round trip");

        Report r = Reports.sample(st, "مكتبي");
        int accents = 0, tinted = 0;
        for (Report.Section s : r.sections) {
            if (s.accent != 0) accents++;
            for (Report.Row row : s.rows) if (row.tint != 0) tinted++;
        }
        check(accents == 2, "two currency sections colored (got " + accents + ")");
        check(tinted == 2, "summary rows tinted per currency (got " + tinted + ")");
        try (FileOutputStream f = new FileOutputStream(a[0] + "/sample.xlsx")) { XlsxWriter.write(r, f, "FF0B6B53", TimeZone.getTimeZone("UTC")); }

        // أرصدة كل المكاتب: كل صف بعملته
        Model.OfficeTotals o = new Model.OfficeTotals();
        o.officeId = 1; o.name = "كاش شام"; o.active = true;
        o.perCurrency.put("USD", new long[]{50000, 20000, 30000});
        o.perCurrency.put("SYP", new long[]{0, 900000, -900000});
        Map<String, Integer> dec = new HashMap<>(); dec.put("USD", 2); dec.put("SYP", 0);
        Report b = Reports.balances(Collections.singletonList(o), dec, new HashMap<>(), false, "مكتبي", System.currentTimeMillis());
        Reports.colorize(b, st);
        Set<Integer> tints = new HashSet<>();
        for (Report.Section s : b.sections) for (Report.Row row : s.rows) if (row.tint != 0) tints.add(row.tint);
        check(tints.size() == 2, "balances rows tinted with 2 colors");
        try (FileOutputStream f = new FileOutputStream(a[0] + "/balances.xlsx")) { XlsxWriter.write(b, f, "FF0B6B53", TimeZone.getTimeZone("UTC")); }

        st.curColors = false;
        Report off = Reports.sample(st, "x");
        boolean none = true;
        for (Report.Section s : off.sections) { if (s.accent != 0) none = false; for (Report.Row row : s.rows) if (row.tint != 0) none = false; }
        check(none, "switch off → no colors");
        System.out.println(fails == 0 ? "ALL CURRENCY COLOR TESTS PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
class ExportStyleColors {
    static int hex(String h) { return (int) Long.parseLong("FF" + h.replace("#", ""), 16); }
}
