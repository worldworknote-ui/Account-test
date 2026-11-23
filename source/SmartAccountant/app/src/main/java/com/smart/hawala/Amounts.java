package com.smart.hawala;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * قراءة واحدة للمبالغ في كل التطبيق (نموذج الإدخال، رسائل واتساب، المرشّحات، الحدود).
 * قبل 8.0 كان «1,500» يُقرأ 1.5 في نموذج الإدخال و1500 في محلل الرسائل: خطأ بألف ضعف في حركة مالية.
 * القاعدة الآن تعتمد منازل العملة:
 *  - فاصل واحد يليه ثلاثة أرقام بالضبط = فاصل آلاف (1,500 = ألف وخمسمئة)، إلا في عملة بثلاث منازل فيُرفض بدل التخمين؛
 *  - فاصل متكرر = آلاف حصرًا (1,250,000)؛ فاصلان مختلفان = الأخير عشري (1.250,50 و1,250.50)؛
 *  - غير ذلك عشري (12,5 و12.50 و0,500).
 * منطق بحت بلا Android حتى يُختبر على الكمبيوتر.
 */
public final class Amounts {
    private Amounts() {}

    private static boolean digits(String p) {
        if (p.isEmpty()) return false;
        for (int i = 0; i < p.length(); i++) if (p.charAt(i) < '0' || p.charAt(i) > '9') return false;
        return true;
    }

    /** آلاف صحيحة: مجموعة أولى من 1-3 أرقام ثم مجموعات كل منها 3 أرقام يفصل بينها sep. */
    private static boolean grouped(String s, char sep) {
        String[] g = s.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
        if (!digits(g[0]) || g[0].length() > 3) return false;
        for (int i = 1; i < g.length; i++) if (!digits(g[i]) || g[i].length() != 3) return false;
        return true;
    }

    /** رقم قياسي (نقطة عشرية وبلا فواصل آلاف)، ويرفض الملتبس بدل أن يخمّن. «» = فارغ. */
    public static String normalize(String t, int dec) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : t.trim().toCharArray()) {
            if (ch >= 0x0660 && ch <= 0x0669) sb.append((char) ('0' + (ch - 0x0660)));
            else if (ch >= 0x06F0 && ch <= 0x06F9) sb.append((char) ('0' + (ch - 0x06F0)));
            else if (ch == 0x066B) sb.append('.');   // ٫ عشرية عربية
            else if (ch == 0x060C) sb.append(',');   // ، فاصلة عربية
            else if (ch == 0x066C || ch == ' ' || ch == 0x00A0 || ch == 0x202F || ch == '\'' || ch == 0x2019 || ch == 0x200F || ch == 0x200E) continue;
            else sb.append(ch);
        }
        String s = sb.toString(), sign = "";
        if (s.startsWith("-") || s.startsWith("+")) {
            sign = s.startsWith("-") ? "-" : "";
            s = s.substring(1);
        }
        if (s.isEmpty()) return "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && c != '.' && c != ',') throw new NumberFormatException("المبلغ يحتوي محارف غير مسموحة");
        }
        if (s.indexOf('.') < 0 && s.indexOf(',') < 0) return sign + s;

        int last = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
        char sep = s.charAt(last), other = sep == '.' ? ',' : '.';
        String head = s.substring(0, last), frac = s.substring(last + 1);
        if (head.isEmpty() && frac.isEmpty()) throw new NumberFormatException("المبلغ غير مفهوم");
        boolean hasOther = head.indexOf(other) >= 0, repeated = head.indexOf(sep) >= 0;

        boolean decimal;
        if (hasOther) decimal = true;              // 1.250,50 أو 1,250.50: الأخير عشري والآخر آلاف
        else if (repeated) decimal = false;        // 1,250,000: آلاف حصرًا
        else if (frac.length() == 3 && !head.isEmpty() && !head.equals("0")) {
            if (dec == 3) throw new NumberFormatException("المبلغ ملتبس: اكتبه بلا فواصل أو حدّد الكسور صراحة");
            decimal = false;                       // 1,500 بعملة منزلتيها 2 أو 0 = ألف وخمسمئة
        } else decimal = true;                     // 12,5 أو 12.50 أو 0,500

        if (decimal) {
            if (!frac.isEmpty() && !digits(frac)) throw new NumberFormatException("تنسيق المبلغ غير مفهوم");
            if (head.isEmpty()) head = "0";
            if (!(hasOther ? grouped(head, other) : digits(head))) throw new NumberFormatException("تنسيق المبلغ غير مفهوم");
            return sign + head.replace(String.valueOf(other), "") + (frac.isEmpty() ? "" : "." + frac);
        }
        if (!grouped(s, sep)) throw new NumberFormatException("تنسيق الآلاف غير مفهوم");
        return sign + s.replace(String.valueOf(sep), "");
    }

    /** المبلغ بالوحدة الصغرى؛ كسور أكثر من منازل العملة تُرفض (لا تقريب أبدًا). */
    public static long minor(String text, int dec) {
        String n = normalize(text, dec);
        if (n.isEmpty()) throw new NumberFormatException("empty");
        return new BigDecimal(n).movePointRight(dec).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** رقم عشري عام (سعر صرف، نسبة) بقواعد منزلتين: يرجع null للفارغ. */
    public static BigDecimal decimal(String text) {
        String n = normalize(text, 2);
        return n.isEmpty() ? null : new BigDecimal(n);
    }
}
