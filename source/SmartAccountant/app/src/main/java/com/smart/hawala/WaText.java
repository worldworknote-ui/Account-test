package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * يقسم نصًا مشارَكًا أو منسوخًا من واتساب إلى رسائل منفصلة.
 * واتساب يضع لكل رسالة عند نسخ عدة رسائل سطرًا مثل «[13/10/26, 1:48 AM] أحمد: …» أو «[07/10, 20:39] أحمد: …»،
 * وعند تصدير المحادثة «13/10/2026, 01:48 - أحمد: …». رسالة واحدة بلا رأس تبقى رسالة واحدة.
 * منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر.
 */
public final class WaText {
    private WaText() {}

    public static final class Msg {
        /** اسم المرسل كما في واتساب، أو فارغ إن لم يُذكر. */
        public final String sender;
        /** التاريخ والوقت كما كتبهما واتساب (للعرض فقط). */
        public final String time;
        public final String body;

        public Msg(String sender, String time, String body) {
            this.sender = sender == null ? "" : sender.trim();
            this.time = time == null ? "" : time.trim();
            this.body = body == null ? "" : body.trim();
        }
    }

    /** علامات الاتجاه التي يضعها واتساب حول التواريخ والأسماء. */
    static String stripMarks(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch == '\u200e' || ch == '\u200f' || ch == '\u061c' || (ch >= '\u202a' && ch <= '\u202e') || (ch >= '\u2066' && ch <= '\u2069')) continue;
            if (ch == '\u00a0' || ch == '\u202f') ch = ' ';
            sb.append(ch);
        }
        return sb.toString();
    }

    private static final String TIME = "\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[AaPp]\\.?\\s?[Mm]\\.?|ص|م)?";
    /** يوم/شهر/سنة، أو يوم/شهر فقط: واتساب يحذف السنة عند نسخ رسائل من السنة الحالية «[07/10, 20:39]». */
    private static final String DATE = "\\d{1,4}[/.\\-]\\d{1,2}(?:[/.\\-]\\d{1,4})?";
    /** «[13/10/26, 1:48 AM] أحمد: نص» (النسخ في أندرويد وآيفون). */
    private static final Pattern BRACKET = Pattern.compile("^\\s*\\[(" + DATE + "[,،]?\\s*" + TIME + "|" + TIME + "[,،]?\\s*" + DATE + ")\\]\\s*([^:\\n]{1,80}?):\\s?(.*)$");
    /** «13/10/2026, 01:48 - أحمد: نص» (تصدير المحادثة). */
    private static final Pattern DASH = Pattern.compile("^\\s*(" + DATE + "[,،]?\\s+" + TIME + ")\\s+[-–]\\s+([^:\\n]{1,80}?):\\s?(.*)$");
    /** «13/10/2026, 01:48 - رسالة النظام» بلا مرسل: تُتجاهل. */
    private static final Pattern DASH_SYSTEM = Pattern.compile("^\\s*" + DATE + "[,،]?\\s+" + TIME + "\\s+[-–]\\s+[^:]*$");

    /** نصوص لا تحمل حوالة: وسائط محذوفة ورسائل محذوفة. */
    private static boolean junk(String body) {
        String b = body.trim().toLowerCase(java.util.Locale.ROOT);
        if (b.isEmpty()) return true;
        String[] junk = {"<media omitted>", "<الوسائط غير مضمنة>", "<تم استبعاد الوسائط>", "تم حذف هذه الرسالة", "this message was deleted",
                "you deleted this message", "لقد حذفت هذه الرسالة", "null", "image omitted", "video omitted", "audio omitted", "sticker omitted",
                "document omitted", "gif omitted", "\u200Fصورة محذوفة"};
        for (String j : junk) if (b.equals(j)) return true;
        return false;
    }

    /** يحذف علامة «تم تعديل هذه الرسالة» التي يضيفها واتساب لآخر الرسالة. */
    private static String cleanBody(String body) {
        return body.replaceAll("(?i)\\s*<(this message was edited|تم تعديل هذه الرسالة)>\\s*$", "").trim();
    }

    /** يقسم النص إلى رسائل. */
    public static List<Msg> split(String text) {
        List<Msg> out = new ArrayList<>();
        if (text == null) return out;
        String t = MessageParser.digits(stripMarks(text)).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = t.split("\n", -1);
        String sender = null, time = null;
        StringBuilder body = null;
        boolean any = false;
        for (String line : lines) {
            Matcher m = BRACKET.matcher(line);
            Matcher d = m.matches() ? null : DASH.matcher(line);
            if (m.matches() || (d != null && d.matches())) {
                Matcher h = m.matches() ? m : d;
                if (body != null) add(out, sender, time, body.toString());
                any = true;
                time = h.group(1);
                sender = h.group(2);
                body = new StringBuilder(h.group(3));
                continue;
            }
            if (any && DASH_SYSTEM.matcher(line).matches()) {
                if (body != null) add(out, sender, time, body.toString());
                body = null;
                sender = null;
                time = null;
                continue;
            }
            if (body == null) body = new StringBuilder(line);
            else body.append('\n').append(line);
        }
        if (body != null) add(out, sender, time, body.toString());
        if (!any) {
            // بلا رؤوس: رسالة واحدة بنصها كما هو (مع إزالة علامات الاتجاه فقط)
            out.clear();
            String one = cleanBody(stripMarks(text).trim());
            if (!junk(one)) out.add(new Msg("", "", one));
        }
        return out;
    }

    private static void add(List<Msg> out, String sender, String time, String body) {
        String b = cleanBody(body);
        if (junk(b)) return;
        out.add(new Msg(sender, time, b));
    }

    /** بصمة نص الرسالة للمقارنة (بلا مسافات وعلامات ترقيم، وبحروف موحّدة): تكشف الرسالة نفسها إن شوركت مرتين. */
    public static String fingerprint(String body) {
        String n = MessageParser.norm(stripMarks(body == null ? "" : body));
        StringBuilder sb = new StringBuilder(n.length());
        for (char ch : n.toCharArray()) if (Character.isLetterOrDigit(ch)) sb.append(ch);
        return sb.toString();
    }
}
