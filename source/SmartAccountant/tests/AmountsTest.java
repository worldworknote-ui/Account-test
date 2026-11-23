import com.smart.hawala.Amounts;
import com.smart.hawala.MessageParser;
import java.math.BigDecimal;

/**
 * 8.0: قراءة واحدة للمبالغ. قبلها كان «1,500» يُقرأ 1.5 في النموذج و1500 في محلل واتساب.
 * كل حالة: النص، منازل العملة، المتوقع بالوحدة الصغرى (أو null = يجب أن يُرفض).
 */
public class AmountsTest {
    static int fails = 0;

    static void check(boolean c, String m) {
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
        if (!c) fails++;
    }

    static void minor(String text, int dec, Long want) {
        Long got;
        try {
            got = Amounts.minor(text, dec);
        } catch (RuntimeException e) {
            got = null;
        }
        check(want == null ? got == null : want.equals(got), "«" + text + "» dec=" + dec + " → " + (want == null ? "مرفوض" : want) + " (got " + got + ")");
    }

    public static void main(String[] a) {
        System.out.println("== thousands vs decimals");
        minor("1,500", 2, 150000L);          // الخطأ القديم: 1.5
        minor("1.500", 2, 150000L);
        minor("1,500", 0, 1500L);
        minor("1,250,000", 0, 1250000L);
        minor("1.250.000", 0, 1250000L);
        minor("1,250.50", 2, 125050L);
        minor("1.250,50", 2, 125050L);
        minor("12,5", 2, 1250L);
        minor("12.50", 2, 1250L);
        minor("0,500", 2, 50L);             // 0.500 = 0.50 بلا تقريب
        minor("0,505", 2, null);            // كسر زائد يحتاج تقريبًا: يُرفض لا يُقرَّب
        minor("0,50", 2, 50L);
        minor(".5", 2, 50L);
        minor("12.", 2, 1200L);
        minor("100", 2, 10000L);
        minor("100", 0, 100L);
        System.out.println("== Arabic and Persian digits and separators");
        minor("١٬٥٠٠", 2, 150000L);          // ٬ فاصل آلاف عربي يُحذف
        minor("١٥٠٠", 2, 150000L);
        minor("١٢٫٥", 2, 1250L);              // ٫ عشرية عربية
        minor("١،٥٠٠", 2, 150000L);          // ، فاصلة عربية = فاصل آلاف هنا
        minor("۱۲۵۰", 2, 125000L);
        minor("2 500", 2, 250000L);
        minor("2 500,75", 2, 250075L);
        System.out.println("== irregular grouping is rejected, not guessed");
        minor("1,50,000", 2, null);
        minor("12,34,567", 2, null);
        minor("1,2,3", 2, null);
        minor("1.250,5,0", 2, null);
        minor("1,250.50.5", 2, null);
        minor("abc", 2, null);
        minor("12a", 2, null);
        minor("", 2, null);
        minor(",", 2, null);
        System.out.println("== three-decimal currencies (KWD, JOD)");
        minor("1,500", 3, null);             // ملتبس: يُرفض بدل التخمين
        minor("1.500", 3, null);
        minor("1500", 3, 1500000L);
        minor("1.5", 3, 1500L);
        minor("1,250.500", 3, 1250500L);
        minor("12.3456", 3, null);           // كسر أكثر من المنازل
        System.out.println("== signs and extremes");
        minor("-1,500", 2, -150000L);
        minor("+75", 2, 7500L);
        minor("999999999999999999", 2, null); // يتجاوز long بعد الضرب
        minor("0", 2, 0L);

        System.out.println("== the form and the WhatsApp parser give the same value");
        String[] same = {"1,500", "1.500", "1,250.50", "1.250,50", "12,5", "2.5", "1,250,000", "5000", "750"};
        for (String s : same) {
            BigDecimal p = MessageParser.numberForTest(s);
            BigDecimal f = new BigDecimal(Amounts.normalize(s, 2));
            check(p != null && p.compareTo(f) == 0, "«" + s + "»: الرسالة " + p + " = النموذج " + f);
        }
        check(MessageParser.numberForTest("+963944") == null, "phone with + is not an amount");

        System.out.println(fails == 0 ? "ALL AMOUNT TESTS PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
