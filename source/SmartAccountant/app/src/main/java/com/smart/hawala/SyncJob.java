package com.smart.hawala;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** 10.0: مزامنة دورية في الخلفية كل ربع ساعة تقريبًا (حين تكون مفعّلة). */
public class SyncJob extends JobService {
    static final int JOB_ID = 7710;

    static void schedule(Context c, boolean on) { schedule(c, on, false); }

    /** 10.4: wifiOnly = لا تعمل المهمة إلا على شبكة غير محسوبة (واي فاي). */
    static void schedule(Context c, boolean on, boolean wifiOnly) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (!on) {
                js.cancel(JOB_ID);
                return;
            }
            int net = wifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
            JobInfo cur = js.getPendingJob(JOB_ID);
            if (cur != null && cur.getNetworkType() == net) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJob.class))
                    .setPeriodic(15L * 60000L)
                    .setRequiredNetworkType(net)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) {
            // تُزامن عند فتح التطبيق
        }
    }

    /** 12.7: مهمة واحدة قريبة تكمل مزامنة بدأت ثم تُرك التطبيق (تعمل ولو أُغلق). */
    static void runOnce(Context c, boolean wifiOnly) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_ID + 1, new ComponentName(c, SyncJob.class))
                    .setRequiredNetworkType(wifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(20_000L)
                    .setOverrideDeadline(10L * 60_000L)
                    .build());
        } catch (Throwable ignored) {
            // تُكمل عند فتح التطبيق
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                Db db = SmartApp.db;
                if (db == null) {
                    db = new Db(getApplicationContext());
                    SmartApp.db = db;
                }
                // 12.7: انضمام تُرك أثناء التحميل: يكمله هنا ولو أُغلق التطبيق
                if (Crypto.healthy(getApplicationContext()) && Db.lockedAside == null && SyncEngine.joinPending(db) && !SyncScreen.joinRunning()) {
                    try {
                        if (License.current == null || !License.current.pro) License.load(getApplicationContext(), db);
                        SyncFolder f = SyncScreen.folder(getApplicationContext(), db);
                        if (f != null) new SyncEngine(db, f).joinFinish(null);
                    } catch (Throwable ignored) {
                        // يكمل عند فتح التطبيق
                    }
                }
                if (Crypto.healthy(getApplicationContext()) && Db.lockedAside == null && SyncEngine.enabled(db)) {
                    try {
                        if (License.current == null || !License.current.pro) License.load(getApplicationContext(), db);
                    } catch (Throwable ignored) {
                        // تبقى الرخصة كما هي
                    }
                    SyncScreen.background(getApplicationContext(), db);
                } else schedule(getApplicationContext(), false);
            } catch (Throwable ignored) {
                // المحاولة التالية
            } finally {
                jobFinished(params, false);
            }
        }, "sync-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
