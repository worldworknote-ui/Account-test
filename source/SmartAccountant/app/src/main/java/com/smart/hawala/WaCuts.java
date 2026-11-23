package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 12.1: تقسيم نص ملزوق بلا رؤوس واتساب (كما يخرج من واتساب ديسكتوب على ويندوز أو من المتصفح).
 * سطر فيه الوقت وحده («10:32» أو «10:32 م» أو «تم التعديل 10:32 PM») هو آخر الفقاعة في واتساب: قطع مؤكد.
 * السطر الفارغ قطع مقترح فقط، يظهر في شاشة «القص» اليدوية ليختار المستخدم.
 * منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر.
 */
public final class WaCuts {
    private WaCuts() {}

    private static final String T = "\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[AaPp]\\.?\\s?[Mm]\\.?|ص|م)?";
    /** سطر الوقت في أسفل الفقاعة، مع علامات القراءة أو «تم التعديل» إن وُجدت. */
    private static final Pattern TIME_LINE = Pattern.compile(
            "^\\s*(?:(?:تم التعديل|معدّلة|معدلة|Edited)\\s*)?(" + T + ")\\s*(?:[✓✔√]{1,2}|msg-(?:dblcheck|check|time))?\\s*$", Pattern.CASE_INSENSITIVE);
    /** أسطر لا تخص نص الرسالة. */
    private static final Pattern META = Pattern.compile("^\\s*(?:تم التعديل|Edited|محوّلة|محولة|Forwarded|تمت إعادة توجيهها|معاد توجيهها)\\s*$", Pattern.CASE_INSENSITIVE);

    public static boolean timeLine(String line) {
        return TIME_LINE.matcher(MessageParser.digits(WaText.stripMarks(line))).matches();
    }

    static String timeOf(String line) {
        java.util.regex.Matcher m = TIME_LINE.matcher(MessageParser.digits(WaText.stripMarks(line)));
        return m.matches() ? m.group(1).trim() : "";
    }

    /** آخر سطر وقت في النص (وقت الفقاعة). */
    static String lastTime(String text) {
        String[] l = text.split("\n");
        for (int i = l.length - 1; i >= 0; i--) {
            if (l[i].trim().isEmpty()) continue;
            return timeOf(l[i]);
        }
        return "";
    }

    /** يحذف أسطر الوقت والعلامات من آخر الفقاعة وأولها. */
    public static String dropMeta(String text) {
        List<String> l = new ArrayList<>();
        for (String s : text.split("\n", -1)) l.add(s);
        while (!l.isEmpty() && (l.get(l.size() - 1).trim().isEmpty() || timeLine(l.get(l.size() - 1)) || META.matcher(l.get(l.size() - 1)).matches()))
            l.remove(l.size() - 1);
        while (!l.isEmpty() && (l.get(0).trim().isEmpty() || META.matcher(l.get(0)).matches())) l.remove(0);
        return String.join("\n", l);
    }

    private static final Pattern GLUED = Pattern.compile("^(.*?)\\s*(" + T + ")\\s*(?:[✓✔√]{1,2})?\\s*$", Pattern.DOTALL);

    /** وقت ملصق بآخر النص بلا سطر جديد («… 100 دولار10:32 م»): يعيد {النص، الوقت}، والوقت فارغ إن لم يوجد. */
    public static String[] trailingTime(String body) {
        String d = MessageParser.digits(body);
        java.util.regex.Matcher m = GLUED.matcher(d);
        if (m.matches() && m.group(1).trim().length() > 0 && !m.group(1).trim().endsWith(":")) return new String[]{body.substring(0, m.group(1).length()).trim(), m.group(2).trim()};
        return new String[]{body, ""};
    }

    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String s : WaText.stripMarks(text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) out.add(s);
        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) out.remove(out.size() - 1);
        while (!out.isEmpty() && out.get(0).trim().isEmpty()) out.remove(0);
        return out;
    }

    /** أماكن القطع المؤكدة: بعد كل سطر وقت (رقم السطر الذي تبدأ عنده رسالة جديدة). */
    public static TreeSet<Integer> sure(List<String> lines) {
        TreeSet<Integer> c = new TreeSet<>();
        for (int i = 0; i < lines.size() - 1; i++) if (timeLine(lines.get(i))) c.add(i + 1);
        return c;
    }

    /** أماكن القطع المقترحة: المؤكدة، وبعد كل سطر فارغ. */
    public static TreeSet<Integer> suggest(List<String> lines) {
        TreeSet<Integer> c = sure(lines);
        // 12.3: كلمة البداية نفسها في أول سطر (حوالة… حوالة…)، أو سطر اسم مرسل في الغروب، تبدأ رسالة مقترحة
        String open = "";
        for (String l : lines) if (!l.trim().isEmpty()) { open = WaWords.firstWord(l); break; }
        boolean opener = WaWords.OPENERS.contains(open);
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (lines.get(i - 1).trim().isEmpty() && !l.trim().isEmpty()) c.add(i);
            else if (opener && !l.trim().isEmpty() && WaWords.firstWord(l).equals(open)) c.add(i);
            else if (WaWords.sender(l) != null && i + 1 < lines.size()) c.add(i);
        }
        return c;
    }

    /** يقسم الأسطر عند القطع المعطاة. سطر الوقت يصير وقت الرسالة ولا يدخل نصها. */
    public static List<WaText.Msg> apply(List<String> lines, java.util.Collection<Integer> cuts) {
        List<WaText.Msg> out = new ArrayList<>();
        TreeSet<Integer> c = new TreeSet<>(cuts);
        c.add(lines.size());
        int from = 0;
        for (int at : c) {
            if (at <= from || at > lines.size()) continue;
            String part = String.join("\n", lines.subList(from, at));
            String time = lastTime(part);
            String body = dropMeta(part).trim();
            if (!body.isEmpty()) out.add(new WaText.Msg("", time, body));
            from = at;
        }
        return out;
    }

    /** تقسيم تلقائي بالقطع المؤكدة فقط؛ رسالة واحدة إن لم يوجد شيء مؤكد. */
    public static List<WaText.Msg> auto(String text) {
        List<String> l = lines(text);
        TreeSet<Integer> s = sure(l);
        return apply(l, s);
    }
}
