package com.smart.hawala;

import java.util.Locale;

/**
 * 10.0: ساعة منطقية هجينة (Hybrid Logical Clock) للمزامنة: الوقت الفعلي بالمللي ثانية + عدّاد + رقم الجهاز.
 * تُكتب نصًا يُرتَّب أبجديًا كما يُرتَّب زمنيًا: «0001734567890123-0000-<device>». لا تتراجع أبدًا حتى لو رجعت ساعة
 * الجهاز، وتتقدم فوق أي ساعة وصلت من جهاز آخر، فيكون ترتيب الأحداث واحدًا على كل الأجهزة.
 */
public final class Hlc {
    public long wall;
    public int counter;
    public final String device;

    public Hlc(String device) { this.device = device; }

    public static String format(long wall, int counter, String device) {
        return String.format(Locale.US, "%016d-%04x-%s", wall, counter & 0xffff, device);
    }

    public String now(long physical) {
        if (physical > wall) {
            wall = physical;
            counter = 0;
        } else counter++;
        if (counter > 0xffff) {
            wall++;
            counter = 0;
        }
        return format(wall, counter, device);
    }

    /** يتقدم فوق ساعة وصلت من جهاز آخر. */
    public void observe(String remote, long physical) {
        long rw;
        int rc;
        try {
            rw = Long.parseLong(remote.substring(0, 16));
            rc = Integer.parseInt(remote.substring(17, 21), 16);
        } catch (Exception e) {
            return;
        }
        long m = Math.max(Math.max(wall, rw), physical);
        if (m == wall && m == rw) counter = Math.max(counter, rc) + 1;
        else if (m == wall) counter++;
        else if (m == rw) counter = rc + 1;
        else counter = 0;
        wall = m;
    }

    public static String load(String saved) { return saved == null ? "" : saved; }

    public void restore(String saved) {
        if (saved == null || saved.length() < 21) return;
        try {
            wall = Long.parseLong(saved.substring(0, 16));
            counter = Integer.parseInt(saved.substring(17, 21), 16);
        } catch (Exception ignored) {
            // يبدأ من الصفر
        }
    }

    public static long wallOf(String h) {
        try {
            return Long.parseLong(h.substring(0, 16));
        } catch (Exception e) {
            return 0;
        }
    }
}
