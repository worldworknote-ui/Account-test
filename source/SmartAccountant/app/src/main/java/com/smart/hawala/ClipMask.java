package com.smart.hawala;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 12.1: «فحص الحافظة»: يُظهر شكل ما نسخه المستخدم دون محتواه، ليُرسَل للدعم بلا بيانات حقيقية.
 * الحروف العربية تصير «ع»، واللاتينية «x»، والأرقام «#»؛ وتبقى علامات الترقيم والمسافات وأسماء عناصر HTML وخصائصها.
 */
public final class ClipMask {
    private ClipMask() {}

    /** خصائص HTML تبقى كما هي (لا تحمل نص الرسالة). */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList("class", "dir", "role", "data-testid", "data-icon", "tabindex", "lang", "colspan", "rowspan"));
    static final int MAX = 20000;

    public static String text(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX));
        for (int i = 0; i < s.length() && b.length() < MAX; ) {
            int cp = s.codePointAt(i);
            b.append(mask(cp));
            i += Character.charCount(cp);
        }
        if (s.length() > MAX) b.append("\n…");
        return b.toString();
    }

    private static String mask(int cp) {
        if (Character.isDigit(cp)) return "#";
        if (Character.isLetter(cp)) {
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            return sc == Character.UnicodeScript.ARABIC ? "ع" : "x";
        }
        if (Character.getType(cp) == Character.OTHER_SYMBOL) return "☺";
        return new String(Character.toChars(cp));
    }

    /** HTML: أسماء العناصر والخصائص تبقى، ونص العناصر وقيم الخصائص (عدا الآمنة) تُخفى. style يُختصر. */
    public static String html(String h) {
        if (h == null) return "";
        StringBuilder out = new StringBuilder();
        int i = 0, n = h.length();
        while (i < n && out.length() < MAX) {
            char c = h.charAt(i);
            if (c != '<') {
                int j = h.indexOf('<', i);
                if (j < 0) j = n;
                out.append(text(h.substring(i, j)));
                i = j;
                continue;
            }
            int end = h.indexOf('>', i);
            if (end < 0) end = n - 1;
            out.append(tag(h.substring(i, end + 1)));
            i = end + 1;
        }
        if (n > 0 && out.length() >= MAX) out.append("\n…");
        return out.toString();
    }

    private static String tag(String t) {
        if (t.startsWith("<!--")) return "<!--…-->";
        StringBuilder b = new StringBuilder();
        int i = 0, n = t.length();
        // اسم العنصر
        while (i < n && !Character.isWhitespace(t.charAt(i)) && t.charAt(i) != '>') b.append(t.charAt(i++));
        while (i < n) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '>') {
                b.append(c);
                i++;
                continue;
            }
            int s = i;
            while (i < n && t.charAt(i) != '=' && !Character.isWhitespace(t.charAt(i)) && t.charAt(i) != '>') i++;
            String name = t.substring(s, i);
            b.append(name);
            if (i < n && t.charAt(i) == '=') {
                b.append('=');
                i++;
                if (i < n && (t.charAt(i) == '"' || t.charAt(i) == '\'')) {
                    char q = t.charAt(i);
                    int e = t.indexOf(q, i + 1);
                    if (e < 0) e = n - 1;
                    String v = t.substring(i + 1, e);
                    String low = name.toLowerCase(java.util.Locale.ROOT);
                    String mv = KEEP.contains(low) ? v : low.equals("style") ? (v.isEmpty() ? "" : "…") : text(v);
                    b.append(q).append(mv).append(q);
                    i = e + 1;
                } else {
                    int s2 = i;
                    while (i < n && !Character.isWhitespace(t.charAt(i)) && t.charAt(i) != '>') i++;
                    b.append(text(t.substring(s2, i)));
                }
            }
        }
        return b.toString();
    }
}
