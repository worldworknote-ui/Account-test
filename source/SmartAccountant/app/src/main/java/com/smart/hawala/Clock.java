package com.smart.hawala;

/**
 * 10.18: «الساعة الموحّدة». كل الأوقات التي يسجّلها البرنامج (الحركات، القيود، السجل، ترتيب المزامنة) تؤخذ من هنا لا من
 * ساعة الجهاز مباشرة: ساعة الجهاز + فرق تصحيح يُتعلَّم من وقت خادم Google عند كل اتصال بمكان المزامنة (ترويسة Date)،
 * ويُحفظ على الجهاز فيعمل بلا إنترنت بآخر تصحيح. جهاز ساعته خاطئة (حتى 12 ساعة) تُسجَّل حركاته بالوقت الصحيح.
 * بلا مصدر وقت (لا مزامنة عبر Google) الفرق صفر والسلوك كما كان.
 */
public final class Clock {
    private Clock() {}

    /** فروق أصغر من هذا ضجيج شبكة (دقة الترويسة ثانية) فلا تُصحَّح. */
    static final long NOISE_MS = 3000;
    private static volatile long offset;
    private static volatile long learnedAt;

    public interface Saver {
        void save(long offsetMs);
    }

    public static volatile Saver saver;

    /** الوقت الموحّد الآن (ميلي ثانية منذ 1970، عالمي). */
    public static long now() { return System.currentTimeMillis() + offset; }

    /** كم تنحرف ساعة الجهاز عن الوقت الصحيح (موجب = الجهاز متأخر). */
    public static long offset() { return offset; }

    /** متى عُرف الفرق آخر مرة في هذه الجلسة (0 = من المحفوظ فقط). */
    public static long learnedAt() { return learnedAt; }

    /** عند فتح القاعدة: آخر فرق محفوظ على هذا الجهاز. */
    public static void load(long stored) { offset = Math.abs(stored) < NOISE_MS ? 0 : stored; }

    /** وقت الخادم كما ورد الآن في رد HTTPS. قيم غير معقولة (قبل 2024 أو فارغة) تُتجاهل. */
    public static void learn(long serverMillis) {
        if (serverMillis < 1_700_000_000_000L) return;
        long o = serverMillis - System.currentTimeMillis();
        if (Math.abs(o) < NOISE_MS) o = 0;
        boolean changed = Math.abs(o - offset) >= 1000;
        offset = o;
        learnedAt = System.currentTimeMillis();
        Saver s = saver;
        if (changed && s != null) {
            try {
                s.save(o);
            } catch (Throwable ignored) {
                // الحفظ للجلسة التالية فقط
            }
        }
    }

    /** للاختبارات. */
    public static void reset() {
        offset = 0;
        learnedAt = 0;
    }
}
