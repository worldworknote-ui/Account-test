package android.app.job;
public class JobInfo {
    public static final int NETWORK_TYPE_NONE = 0, NETWORK_TYPE_ANY = 1, NETWORK_TYPE_UNMETERED = 2, BACKOFF_POLICY_LINEAR = 0, BACKOFF_POLICY_EXPONENTIAL = 1;
    final int id; final android.content.ComponentName comp; long period, latency, deadline; android.os.PersistableBundle extras = new android.os.PersistableBundle(); boolean persisted; int net;
    JobInfo(Builder b) { net = b.net; id = b.id; comp = b.comp; period = b.period; latency = b.latency; deadline = b.deadline; extras = b.extras; persisted = b.persisted; }
    public int getId() { return id; }
    public long getIntervalMillis() { return period; }
    public long getMinLatencyMillis() { return latency; }
    public boolean isPeriodic() { return period > 0; }
    public boolean isPersisted() { return persisted; }
    public android.os.PersistableBundle getExtras() { return extras; }
    public int getNetworkType() { return net; }
    public android.content.ComponentName getService() { return comp; }
    public static long getMinPeriodMillis() { return 15 * 60_000L; }
    public static final class Builder {
        final int id; final android.content.ComponentName comp; long period, latency, deadline = -1; android.os.PersistableBundle extras = new android.os.PersistableBundle(); boolean persisted; int net;
        public Builder(int id, android.content.ComponentName c) { this.id = id; comp = c; }
        public Builder setPeriodic(long ms) { period = Math.max(ms, 15 * 60_000L); return this; }
        public Builder setPeriodic(long ms, long flex) { return setPeriodic(ms); }
        public Builder setMinimumLatency(long ms) { latency = ms; return this; }
        public Builder setOverrideDeadline(long ms) { deadline = ms; return this; }
        public Builder setRequiredNetworkType(int t) { net = t; return this; }
        public Builder setRequiresCharging(boolean b) { return this; }
        public Builder setRequiresDeviceIdle(boolean b) { return this; }
        public Builder setRequiresBatteryNotLow(boolean b) { return this; }
        public Builder setRequiresStorageNotLow(boolean b) { return this; }
        public Builder setPersisted(boolean b) { persisted = b; return this; }
        public Builder setExtras(android.os.PersistableBundle b) { extras = b; return this; }
        public Builder setBackoffCriteria(long ms, int policy) { return this; }
        public Builder setImportantWhileForeground(boolean b) { return this; }
        public Builder setPrefetch(boolean b) { return this; }
        public Builder setExpedited(boolean b) { return this; }
        public JobInfo build() { return new JobInfo(this); }
    }
}
