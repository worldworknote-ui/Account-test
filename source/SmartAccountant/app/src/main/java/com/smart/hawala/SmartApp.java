package com.smart.hawala;

import android.app.Application;

public class SmartApp extends Application {
    public static volatile Db db;
    /** المستخدم الداخل حاليًا (null = لا يوجد نظام مستخدمين أو لم يدخل أحد). */
    public static volatile Model.User currentUser;

    @Override
    public void onCreate() {
        super.onCreate();
        // أول شيء: تسجيل أي انهيار في ملف حتى نعرف سببه بدل أن يُغلق التطبيق بصمت
        CrashGuard.install(this);
        // 8.0 (ت13): في نسخ التطوير فقط: سجلّ لكل قراءة أو كتابة على خيط الواجهة وكل مورد لم يُغلق (لا يغيّر النسخة النهائية)
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build());
            android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build());
        }
        try {
            CrashGuard.version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // غير مهم
        }
        // مفتاح البيانات للحقول المشفرة (سريع)، قبل فتح قاعدة البيانات
        Crypto.init(this);
        // 12.7: قاعدة كبيرة قديمة تُشفَّر لأول مرة في الخلفية بشاشة تقدم (MainActivity)، لا هنا على خيط الواجهة
        if (Db.needsEncryption(this)) return;
        db = new Db(this);
        afterDb(this);
    }

    /** 12.7: ما يلي فتح القاعدة (هنا عند الإقلاع، أو بعد تشفيرها الأول في الخلفية). */
    static void afterDb(android.content.Context ctx) {
        Db db = SmartApp.db;
        Application app = (Application) ctx.getApplicationContext();
        // ما يحق لهذه النسخة (مجانية أو مرخّصة). لا يرمي ولا يحجز شيئًا؛ الفشل = حدود المجاني فقط.
        License.load(app, db);
        if (Crypto.fast()) {
            final Db d0 = db;
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(6000);
                    d0.reencryptLegacy();
                } catch (Throwable ignored) {
                    // يُستكمل في التشغيل التالي
                }
            }, "reencrypt");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        try {
            if (AutoBackup.enabled(db)) AutoBackup.schedule(app);
        } catch (Throwable ignored) {
            // الجدولة تُعاد عند فتح شاشة النسخ
        }
    }
}
