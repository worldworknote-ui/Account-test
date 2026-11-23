package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 12.1: نسخة HTML من الحافظة. واتساب ديسكتوب على ويندوز وواتساب ويب في المتصفح يضعان مع النص العادي (الملزوق كرسالة واحدة)
 * نسخة HTML فيها كل فقاعة في عنصر مستقل، وغالبًا مع خاصية data-pre-plain-text="[الوقت، التاريخ] الاسم: ".
 * نعيد بناء النص بصيغة النسخ في أندرويد ثم نقسمه بـ {@link WaText#split} نفسها، فيبقى منطق التقسيم واحدًا.
 * منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر. لا نعتمد على شكل واتساب الداخلي إلا كتلميح: إن لم نجد علامات الفقاعات نعيد null.
 */
public final class WaHtml {
    private WaHtml() {}

    private static final Pattern PRE = Pattern.compile("data-pre-plain-text\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    /** بداية فقاعة في واتساب ويب (رسالة واردة أو صادرة). */
    private static final Pattern BUBBLE = Pattern.compile("<[a-z0-9]+[^>]*class\\s*=\\s*[\"'][^\"']*\\bmessage-(?:in|out)\\b[^\"']*[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * يحوّل HTML الحافظة إلى رسائل، أو null إن لم يكن فيه ما يدل على فقاعات (فيُستعمل النص العادي كما كان).
     */
    public static List<WaText.Msg> messages(String html) {
        if (html == null || html.isEmpty()) return null;
        String h = stripHeader(html);
        List<int[]> starts = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        Matcher m = PRE.matcher(h);
        while (m.find()) {
            String v = m.group(2) != null ? m.group(2) : m.group(3);
            int tagStart = h.lastIndexOf('<', m.start());
            int tagEnd = h.indexOf('>', m.end());
            if (tagStart < 0 || tagEnd < 0) continue;
            starts.add(new int[]{tagStart, tagEnd + 1});
            prefixes.add(decode(v));
        }
        if (!starts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < starts.size(); k++) {
                int from = starts.get(k)[1];
                int to = k + 1 < starts.size() ? starts.get(k + 1)[0] : h.length();
                String body = WaCuts.dropMeta(text(h.substring(from, to)));
                if (body.trim().isEmpty()) continue;
                String pre = prefixes.get(k).trim();
                if (sb.length() > 0) sb.append('\n');
                sb.append(pre).append(pre.endsWith(":") ? " " : "").append(body.trim());
            }
            List<WaText.Msg> out = WaText.split(sb.toString());
            return out.isEmpty() ? null : out;
        }
        // بلا data-pre-plain-text: فقاعات واتساب ويب بأسماء صنفها
        Matcher b = BUBBLE.matcher(h);
        List<Integer> pos = new ArrayList<>();
        while (b.find()) pos.add(b.start());
        if (pos.size() < 1) return null;
        List<WaText.Msg> out = new ArrayList<>();
        for (int k = 0; k < pos.size(); k++) {
            int to = k + 1 < pos.size() ? pos.get(k + 1) : h.length();
            String t = text(h.substring(pos.get(k), to));
            String time = WaCuts.lastTime(t);
            String body = WaCuts.dropMeta(t).trim();
            if (time.isEmpty()) {
                String[] tt = WaCuts.trailingTime(body);
                body = tt[0];
                time = tt[1];
            }
            if (body.isEmpty()) continue;
            out.addAll(asMsgs(body, time));
        }
        return out.isEmpty() ? null : out;
    }

    private static List<WaText.Msg> asMsgs(String body, String time) {
        List<WaText.Msg> l = WaText.split(body);
        if (l.size() == 1 && l.get(0).time.isEmpty()) {
            List<WaText.Msg> r = new ArrayList<>();
            r.add(new WaText.Msg(l.get(0).sender, time, l.get(0).body));
            return r;
        }
        return l;
    }

    /** ترويسة CF_HTML في ويندوز («Version:0.9 StartHTML:…») إن بقيت. */
    static String stripHeader(String html) {
        int i = html.indexOf('<');
        if (i > 0 && html.substring(0, i).contains("StartHTML")) return html.substring(i);
        return html;
    }

    private static final Pattern BLOCK = Pattern.compile("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6]|p|div|li|tr)\\b[^>]*>");
    private static final Pattern DROP = Pattern.compile("(?is)<(script|style|head)\\b.*?</\\1\\s*>");

    /** نص HTML: العناصر الكتلية تصبح أسطرًا، والصور الرمزية (الإيموجي) تُستبدل بنصها البديل. */
    public static String text(String html) {
        String s = DROP.matcher(html).replaceAll("");
        s = s.replaceAll("(?i)<img\\b[^>]*\\balt\\s*=\\s*\"([^\"]*)\"[^>]*>", "$1");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = s.replaceAll("<[^>]*>", "");
        s = decode(s);
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").trim();
            if (t.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append(t);
        }
        return out.toString().trim();
    }

    static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String e = s.substring(i + 1, semi);
                    String r = null;
                    switch (e) {
                        case "amp": r = "&"; break;
                        case "lt": r = "<"; break;
                        case "gt": r = ">"; break;
                        case "quot": r = "\""; break;
                        case "apos": r = "'"; break;
                        case "nbsp": r = " "; break;
                        default:
                            try {
                                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                            } catch (Exception ignored) {
                                r = null;
                            }
                    }
                    if (r != null) {
                        b.append(r);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }
}
