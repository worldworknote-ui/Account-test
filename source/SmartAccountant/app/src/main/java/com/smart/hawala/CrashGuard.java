package com.smart.hawala;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * يحفظ تفاصيل أي انهيار مفاجئ في ملف داخلي، ويعرضها التطبيق عند الفتح التالي
 * مع زر مشاركة، ليسهل إصلاح المشكلة بدون كمبيوتر.
 */
public final class CrashGuard {
    private static final String FILE = "last_crash.txt";
    public static volatile String version = "";

    private CrashGuard() {}

    public static void install(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(app, stack(error));
            } catch (Throwable ignored) {
                // لا نريد أن يفشل تسجيل الخطأ نفسه
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    public static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        PrintWriter p = new PrintWriter(w);
        t.printStackTrace(p);
        p.flush();
        return w.toString();
    }

    public static String header() {
        return "المحاسب الذكي لمكاتب الحوالات " + version + "\n"
                + "الوقت: " + Model.sdf(new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)).format(new Date()) + "\n"
                + "الجهاز: " + Build.MANUFACTURER + " " + Build.MODEL
                + " | أندرويد " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n\n";
    }

    public static void save(Context ctx, String trace) {
        try (FileOutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), FILE))) {
            o.write((header() + trace).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // تجاهل
        }
    }

    /** يرجع نص آخر انهيار محفوظ، أو null إن لم يوجد. */
    public static String read(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE);
            if (!f.exists() || f.length() == 0) return null;
            return new String(Db.readAll(f), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clear(Context ctx) {
        try {
            new File(ctx.getFilesDir(), FILE).delete();
        } catch (Throwable ignored) {
            // تجاهل
        }
    }
}
