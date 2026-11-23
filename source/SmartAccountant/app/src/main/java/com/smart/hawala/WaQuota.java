package com.smart.hawala;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 9.0: عدّاد يومي لاستخراج معلومات رسائل واتساب في النسخة المجانية (منطق بحت).
 * تُحتسب الرسالة مرة واحدة في اليوم مهما أُعيد استخراجها أو إضافتها للحقول (بصمة نصها)، ويبدأ العد من جديد كل يوم.
 * بعد السقف يعمل نموذج التسجيل بالإدخال اليدوي العادي، ولا يتوقف شيء آخر.
 */
public final class WaQuota {
    /** اليوم yyyyMMdd الذي تخصه البصمات. */
    public String day = "";
    public final Set<String> seen = new LinkedHashSet<>();

    public static WaQuota parse(String s) {
        WaQuota q = new WaQuota();
        if (s == null || s.isEmpty()) return q;
        for (String part : s.split(";")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            String k = part.substring(0, i), v = part.substring(i + 1);
            if (k.equals("d")) q.day = v;
            else if (k.equals("h")) for (String h : v.split(",")) if (!h.trim().isEmpty()) q.seen.add(h.trim());
        }
        return q;
    }

    public String serialize() {
        StringBuilder b = new StringBuilder("d=").append(day).append(";h=");
        boolean first = true;
        for (String h : seen) {
            if (!first) b.append(',');
            b.append(h);
            first = false;
        }
        return b.toString();
    }

    /** بصمة قصيرة لنص الرسالة بعد توحيد المسافات (الرسالة نفسها لا تُحفظ). */
    public static String key(String text) {
        String n = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(n.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format(java.util.Locale.US, "%02x", d[i] & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(n.hashCode());
        }
    }

    private void roll(String today) {
        if (!today.equals(day)) {
            day = today;
            seen.clear();
        }
    }

    /** المستخدم اليوم. */
    public long used(String today) { return today.equals(day) ? seen.size() : 0; }

    /** هل يُسمح باستخراج هذه الرسالة اليوم؟ المحتسبة سابقًا اليوم مسموحة دائمًا. */
    public boolean allows(String key, long max, String today) {
        if (max == Entitlements.UNLIMITED) return true;
        if (!today.equals(day)) return max > 0;
        return seen.contains(key) || seen.size() < max;
    }

    /** يحتسب الرسالة (مرة واحدة في اليوم). يرجع false إن رُفضت لبلوغ السقف. */
    public boolean use(String key, long max, String today) {
        roll(today);
        if (seen.contains(key)) return true;
        if (max != Entitlements.UNLIMITED && seen.size() >= max) return false;
        seen.add(key);
        return true;
    }

    public static long remaining(long used, long max) {
        return max == Entitlements.UNLIMITED ? -1 : Math.max(0, max - used);
    }
}
