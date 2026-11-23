import com.smart.hawala.*;
import java.math.BigDecimal;
/** 7.1.2: الأجرة كنسبة من المبلغ الأصلي (بالمئة أو بالألف). */
public class FeeRateTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static boolean throwsIAE(Runnable r) { try { r.run(); return false; } catch (IllegalArgumentException e) { return true; } }
  public static void main(String[] a) {
    BigDecimal one = Model.parseRate("1"), oneHalf = Model.parseRate("1.5");
    check(one != null && one.compareTo(BigDecimal.ONE) == 0, "parse 1");
    check(oneHalf != null && oneHalf.compareTo(new BigDecimal("1.5")) == 0, "parse 1.5");
    check(new BigDecimal("1.5").compareTo(Model.parseRate("١٫٥")) == 0, "Arabic digits and Arabic decimal sign");
    check(new BigDecimal("1.5").compareTo(Model.parseRate("1,5")) == 0, "comma as decimal");
    check(new BigDecimal("1.5").compareTo(Model.parseRate(" 1.5 % ")) == 0, "percent sign and spaces ignored");
    check(new BigDecimal("2").compareTo(Model.parseRate("2‰")) == 0, "per-mille sign ignored");
    check(new BigDecimal("0.25").compareTo(Model.parseRate(".25")) == 0, "leading dot");
    for (String bad : new String[]{"", "0", "0.0", "-1", "abc", "1.2.3", "1.23456", "12345"})
      check(Model.parseRate(bad) == null, "rejects [" + bad + "]");

    // USD بمنزلتين عشريتين: الوحدة الصغرى سنت
    check(Model.feeFromRate(500000, one, Model.PER_CENT) == 5000, "5000.00 × 1% = 50.00");
    check(Model.feeFromRate(200000, oneHalf, Model.PER_MILLE) == 300, "2000.00 × 1.5‰ = 3.00");
    check(Model.feeFromRate(55000, one, Model.PER_CENT) == 550 && !Model.feeRounded(55000, one, Model.PER_CENT), "550.00 × 1% = 5.50 exact");
    // ليرة سورية بلا منازل عشرية
    check(Model.feeFromRate(1000000, oneHalf, Model.PER_MILLE) == 1500, "1,000,000 SYP × 1.5‰ = 1500");
    // تقريب نصف للأعلى لأقرب وحدة صغرى
    check(Model.feeFromRate(5050, one, Model.PER_CENT) == 51 && Model.feeRounded(5050, one, Model.PER_CENT), "50.50 × 1% = 0.505 -> 0.51 (rounded)");
    check(Model.feeFromRate(33333, one, Model.PER_CENT) == 333 && Model.feeRounded(33333, one, Model.PER_CENT), "333.33 × 1% = 3.3333 -> 3.33");
    check(Model.feeFromRate(100, one, Model.PER_MILLE) == 0, "100 SYP × 1‰ = 0.1 -> 0 (caller refuses a zero fee)");
    check(Model.feeFromRate(99999999999L, new BigDecimal("3"), Model.PER_CENT) == 3000000000L, "large amount: no overflow");
    check(Model.feeFromRate(100000, new BigDecimal("100"), Model.PER_CENT) == 100000, "100% = the amount itself");
    check(throwsIAE(() -> Model.feeFromRate(100000, new BigDecimal("101"), Model.PER_CENT)), "rate above 100% refused");
    check(throwsIAE(() -> Model.feeFromRate(100000, new BigDecimal("1001"), Model.PER_MILLE)), "rate above 1000‰ refused");
    check(throwsIAE(() -> Model.feeFromRate(0, one, Model.PER_CENT)), "zero amount refused");
    check(throwsIAE(() -> Model.feeFromRate(100, one, 10)), "unknown base refused");
    check(Model.rateLabel(new BigDecimal("1.50"), Model.PER_MILLE).equals("1.5‰") && Model.rateLabel(one, Model.PER_CENT).equals("1٪"), "labels");
    // الأجرة بالعملة نفسها: نفس الوحدة الصغرى، فالمجموع مع المبلغ دقيق
    long amt = 123456789, fee = Model.feeFromRate(amt, oneHalf, Model.PER_CENT);
    check(fee == 1851852, "1,234,567.89 × 1.5% = 18,518.52 (got " + fee + ")");
    System.out.println(fails == 0 ? "ALL FEE RATE TESTS PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
