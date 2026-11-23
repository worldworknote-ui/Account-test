package com.smart.hawala;

/** نسخة الماك: القاعدة المفتوحة والمستخدم الداخل (تقرؤهما Db عند كتابة سجل العمليات والصلاحيات). */
public final class SmartApp {
    private SmartApp() {}

    public static volatile Db db;
    public static volatile Model.User currentUser;
    public static volatile android.content.Context ctx;

    /** 12.7: على الكمبيوتر تُفتح القاعدة في Main قبل النافذة (التشفير الأول بنافذة تقدم هناك): لا شيء هنا. */
    static void afterDb(android.content.Context c) { }
}
