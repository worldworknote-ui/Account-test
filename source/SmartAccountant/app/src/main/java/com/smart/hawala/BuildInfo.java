package com.smart.hawala;

/** ثوابت البناء (لا تُقرأ من ساعة الهاتف). تُحدَّث مع كل إصدار. */
public final class BuildInfo {
    private BuildInfo() {}

    /** تاريخ هذا البناء: الرخصة تشمله إن كان uu فيها لا يقل عنه. */
    public static final int BUILD_DATE = 20261023;

    public static final String VERSION = "12.7.1";

    /** 12.6: اسم البرنامج الثابت (يظهر دائمًا آخر الصفحة الرئيسية ولا يتغير بتخصيص الاسم). */
    public static final String APP_NAME = "المحاسب الذكي لمكاتب الحوالات";

    /** بصمة SHA-256 لشهادة توقيع الإصدار 8 (المفتاح الجديد). نسخة معاد توقيعها تختلف بصمتها. */
    public static final String SIGNING_CERT_SHA256 = "FD24C2675B88217BECFD42828C39A27CF892393A1770494D969EEE6225BC8686";
}
