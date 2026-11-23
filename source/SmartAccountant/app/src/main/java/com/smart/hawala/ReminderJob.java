package com.smart.hawala;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** 9.0: مهمة دورية من النظام تفحص تذكيرات المكاتب (مكتب بلا حركات منذ يوم، مطابقة لم تُرسل) وترسل إشعارًا عند الحاجة. */
public class ReminderJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                Db db = SmartApp.db;
                if (db == null) {
                    db = new Db(getApplicationContext());
                    SmartApp.db = db;
                }
                if (Crypto.healthy(getApplicationContext()) && Db.lockedAside == null) {
                    // 10.0: المزامنة في الخلفية (إن كانت مفعّلة)
                    try {
                        if (License.current == null || !License.current.pro) License.load(getApplicationContext(), db);
                    } catch (Throwable ignored) {
                        // تبقى الرخصة كما هي
                    }
                    SyncScreen.background(getApplicationContext(), db);
                    Notifier.check(getApplicationContext(), db);
                }
            } catch (Throwable ignored) {
                // المحاولة التالية بعد ساعات
            } finally {
                jobFinished(params, false);
            }
        }, "reminders-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
