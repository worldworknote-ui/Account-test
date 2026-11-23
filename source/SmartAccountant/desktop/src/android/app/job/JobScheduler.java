package android.app.job;

import java.util.*;

/** المهام الدورية (النسخ التلقائي، التذكير، المزامنة) تعمل داخل البرنامج ما دام مفتوحًا. */
public class JobScheduler {
    public static final int RESULT_SUCCESS = 1, RESULT_FAILURE = 0;
    private static final Map<Integer, JobInfo> jobs = new HashMap<>();
    private static final Map<Integer, javax.swing.Timer> timers = new HashMap<>();
    public JobScheduler(android.content.Context c) {}

    public synchronized int schedule(JobInfo j) {
        cancel(j.id);
        jobs.put(j.id, j);
        long first = j.latency > 0 ? j.latency : j.period > 0 ? j.period : Math.max(1000, j.deadline);
        javax.swing.Timer t = new javax.swing.Timer((int) Math.min(Integer.MAX_VALUE, Math.max(1000, first)), e -> run(j));
        t.setRepeats(j.period > 0);
        if (j.period > 0) t.setDelay((int) Math.min(Integer.MAX_VALUE, j.period));
        t.setInitialDelay((int) Math.min(Integer.MAX_VALUE, Math.max(1000, first)));
        timers.put(j.id, t);
        t.start();
        return RESULT_SUCCESS;
    }
    private void run(JobInfo j) {
        try {
            Class<?> k = Class.forName(j.comp.getClassName());
            JobService s = (JobService) k.getDeclaredConstructor().newInstance();
            s.onStartJob(new JobParameters(j.id, j.extras));
        } catch (Throwable e) {
            android.util.Log.w("JobScheduler", "job " + j.id + " failed", e);
        }
        if (j.period <= 0) synchronized (this) { jobs.remove(j.id); timers.remove(j.id); }
    }
    public synchronized void cancel(int id) {
        javax.swing.Timer t = timers.remove(id);
        if (t != null) t.stop();
        jobs.remove(id);
    }
    public synchronized void cancelAll() { for (Integer id : new ArrayList<>(jobs.keySet())) cancel(id); }
    public synchronized JobInfo getPendingJob(int id) { return jobs.get(id); }
    public synchronized List<JobInfo> getAllPendingJobs() { return new ArrayList<>(jobs.values()); }
}
