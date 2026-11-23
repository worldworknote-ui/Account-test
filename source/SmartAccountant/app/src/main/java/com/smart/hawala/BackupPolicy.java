package com.smart.hawala;

/**
 * متى يحين النسخ التلقائي (منطق بحت يُختبر على الكمبيوتر).
 * المجانية: يوميًا على الأكثر. الكاملة: حتى كل 6 ساعات، وأيضًا بعد عدد من الحركات منذ آخر نسخة.
 */
public final class BackupPolicy {
    private BackupPolicy() {}

    /** خيارات التكرار بالساعات كما تظهر في شاشة النسخ (الأقل من حد النسخة لا يظهر). */
    public static final long[] HOURS = {6, 24, 72, 168};

    /** الفاصل بالساعات: الإعداد بالساعات، أو الأيام القديمة × 24، مرفوعًا إلى الحد الأدنى المسموح. */
    public static long intervalHours(long hoursSetting, long daysSetting, int minHours) {
        long h = hoursSetting > 0 ? hoursSetting : Math.max(1, daysSetting) * 24;
        return Math.max(Math.max(1, minHours), h);
    }

    /**
     * عدد الحركات الذي يستدعي نسخة: 0 = لا (المجانية دائمًا). الإعداد السالب/غير المحدد = الافتراضي المسموح،
     * و0 = أوقفه المستخدم، وما دون الحد المسموح يُرفع إليه حتى لا يتكرر النسخ مع كل حركة.
     */
    public static int everyTx(long setting, int allowed) {
        if (allowed <= 0 || setting == 0) return 0;
        if (setting < 0) return allowed;
        return (int) Math.min(100000, Math.max(allowed, setting));
    }

    /**
     * هل حان النسخ؟ بالوقت (مع هامش نصف ساعة حتى لا يفوت الموعد بفارق دقائق جدولة النظام)، أو بعدد الحركات
     * (مع 10 دقائق على الأقل بين نسختين). ساعة هاتف رجعت إلى الوراء أكثر من ساعة تعني أن الموعد غير معروف: يُنسخ.
     */
    public static boolean due(long now, long last, long intervalHours, long txSince, int everyTx) {
        if (last <= 0) return true;
        if (now < last - 3600000L) return true;
        if (now - last >= intervalHours * 3600000L - 30 * 60000L) return true;
        return everyTx > 0 && txSince >= everyTx && now - last >= 10 * 60000L;
    }
}
