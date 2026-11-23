import com.smart.hawala.*;
import java.math.BigDecimal;
import java.util.*;

public class LedgerTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static Map<String, Long> m(Object... kv) { Map<String, Long> r = new TreeMap<>(); for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], ((Number) kv[i+1]).longValue()); return r; }
  public static void main(String[] a) {
    System.out.println("1) review example: office A owes us 1,000 USD, we owe office B 700 USD");
    Map<Long, Map<String, Long>> ob = new LinkedHashMap<>();
    ob.put(1L, m("USD", 100000));   // minor units (cents)
    ob.put(2L, m("USD", -70000));
    Map<String, Ledger.CurrencyTotal> t = Ledger.totals(ob);
    check(t.get("USD").receivable == 100000, "مجموع لنا = 1,000.00 (got " + t.get("USD").receivable + ")");
    check(t.get("USD").payable == 70000, "مجموع علينا = 700.00 (got " + t.get("USD").payable + ")");
    check(t.get("USD").net() == 30000, "الصافي = 300 shown separately");
    check(t.get("USD").officesReceivable == 1 && t.get("USD").officesPayable == 1, "office counts 1/1");

    System.out.println("2) multi-currency, zero balances ignored");
    ob.put(3L, m("SYP", 13000000, "USD", 0));
    ob.put(4L, m("SYP", -2600000, "EUR", 5000));
    t = Ledger.totals(ob);
    check(t.get("SYP").receivable == 13000000 && t.get("SYP").payable == 2600000, "SYP لنا/علينا separate");
    check(t.get("USD").officesReceivable == 1, "zero balance office not counted");

    System.out.println("3) USD valuation keeps لنا and علينا separate (no abs-sum mixing)");
    Map<String, BigDecimal> rates = new HashMap<>();
    rates.put("SYP", BigDecimal.ONE.divide(new BigDecimal("13000"), 16, java.math.RoundingMode.HALF_EVEN));
    Map<String, Integer> dec = new HashMap<>(); dec.put("USD", 2); dec.put("SYP", 0); dec.put("EUR", 2);
    Ledger.Valuation v = Ledger.value(t, rates, dec);
    // لنا: 1000 USD + 13,000,000 SYP (=1000 USD) = 2000 ; علينا: 700 USD + 2,600,000 SYP (=200 USD) = 900
    check(v.receivableUsd.compareTo(new BigDecimal("2000.00")) == 0, "لنا ≈ 2000.00 USD (got " + v.receivableUsd + ")");
    check(v.payableUsd.compareTo(new BigDecimal("900.00")) == 0, "علينا ≈ 900.00 USD (got " + v.payableUsd + ")");
    check(v.missingRates.contains("EUR"), "EUR without rate reported, not silently used: " + v.missingRates);

    System.out.println("4) per-office valuation (for USD limits)");
    Ledger.Valuation ov = Ledger.valueOffice(m("USD", 50000, "SYP", -1300000), rates, dec);
    check(ov.receivableUsd.compareTo(new BigDecimal("500.00")) == 0 && ov.payableUsd.compareTo(new BigDecimal("100.00")) == 0, "office: لنا 500 / علينا 100 (no netting)");

    System.out.println("5) limit checks per side");
    check(Ledger.check("USD", 120000, 100000L, null) != null, "لنا above لنا-limit -> alert");
    check(Ledger.check("USD", -120000, 100000L, null) == null, "علينا not checked against لنا-limit");
    check(Ledger.check("USD", -120000, null, 100000L) != null, "علينا above علينا-limit -> alert");
    check(Ledger.check("USD", 90000, 100000L, 100000L) == null, "under limit -> no alert");

    System.out.println(fails == 0 ? "ALL LEDGER TESTS PASSED" : (fails + " FAILURE(S)"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
