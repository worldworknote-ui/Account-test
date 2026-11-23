package android.os;
public final class StrictMode {
    public static final class ThreadPolicy {
        public static final ThreadPolicy LAX = new ThreadPolicy();
        public static final class Builder {
            public Builder() {} public Builder(ThreadPolicy p) {}
            public Builder detectAll() { return this; } public Builder penaltyLog() { return this; } public Builder detectDiskReads() { return this; }
            public Builder detectDiskWrites() { return this; } public Builder detectNetwork() { return this; } public Builder penaltyDeath() { return this; }
            public Builder permitAll() { return this; } public Builder permitDiskReads() { return this; } public Builder permitDiskWrites() { return this; }
            public ThreadPolicy build() { return new ThreadPolicy(); }
        }
    }
    public static final class VmPolicy {
        public static final class Builder {
            public Builder() {} public Builder(VmPolicy p) {}
            public Builder detectAll() { return this; } public Builder penaltyLog() { return this; } public Builder detectLeakedClosableObjects() { return this; }
            public Builder detectLeakedSqlLiteObjects() { return this; } public Builder penaltyDeath() { return this; } public Builder detectFileUriExposure() { return this; }
            public Builder detectActivityLeaks() { return this; } public Builder detectNonSdkApiUsage() { return this; } public Builder detectContentUriWithoutPermission() { return this; }
            public Builder detectUnsafeIntentLaunch() { return this; }
            public VmPolicy build() { return new VmPolicy(); }
        }
    }
    public static void setThreadPolicy(ThreadPolicy p) {}
    public static void setVmPolicy(VmPolicy p) {}
    public static ThreadPolicy getThreadPolicy() { return ThreadPolicy.LAX; }
    public static ThreadPolicy allowThreadDiskReads() { return ThreadPolicy.LAX; }
    public static ThreadPolicy allowThreadDiskWrites() { return ThreadPolicy.LAX; }
}
