package android.app.job;
public class JobParameters {
    final int id; final android.os.PersistableBundle extras;
    JobParameters(int id, android.os.PersistableBundle e) { this.id = id; extras = e; }
    public int getJobId() { return id; }
    public android.os.PersistableBundle getExtras() { return extras; }
    public boolean isOverrideDeadlineExpired() { return false; }
    public int getStopReason() { return 0; }
}
