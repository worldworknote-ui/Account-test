package com.smart.hawala;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** مهمة دورية من النظام (كل 6 ساعات تقريبًا) تنشئ النسخة التلقائية إن حان موعدها. */
public class AutoBackupJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Db db = SmartApp.db;
                    if (db == null) {
                        db = new Db(getApplicationContext());
                        SmartApp.db = db;
                    }
                    if (AutoBackup.due(db)) AutoBackup.runNow(getApplicationContext(), db);
                } catch (Throwable ignored) {
                    // سُجّل الخطأ وأُرسل إشعار
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "auto-backup-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
