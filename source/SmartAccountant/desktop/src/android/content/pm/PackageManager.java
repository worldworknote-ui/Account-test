package android.content.pm;
public class PackageManager {
    public static final PackageManager INSTANCE = new PackageManager();
    public static final int PERMISSION_GRANTED = 0, PERMISSION_DENIED = -1, GET_SIGNATURES = 64, GET_SIGNING_CERTIFICATES = 0x08000000, MATCH_DEFAULT_ONLY = 0x10000,
            COMPONENT_ENABLED_STATE_ENABLED = 1, COMPONENT_ENABLED_STATE_DISABLED = 2, DONT_KILL_APP = 1;
    public static final String FEATURE_CAMERA_ANY = "android.hardware.camera.any", FEATURE_FINGERPRINT = "android.hardware.fingerprint";
    public static class NameNotFoundException extends Exception { public NameNotFoundException() {} public NameNotFoundException(String m) { super(m); } }
    public PackageInfo getPackageInfo(String pkg, int flags) throws NameNotFoundException {
        PackageInfo p = new PackageInfo();
        p.packageName = pkg;
        p.versionName = com.smart.hawala.BuildInfo.VERSION;
        p.versionCode = 0;
        p.firstInstallTime = System.currentTimeMillis();
        p.lastUpdateTime = System.currentTimeMillis();
        return p;
    }
    public ApplicationInfo getApplicationInfo(String pkg, int flags) { return new ApplicationInfo(); }
    public boolean hasSystemFeature(String f) { return false; }
    public java.util.List<ResolveInfo> queryIntentActivities(android.content.Intent i, int flags) { return new java.util.ArrayList<>(); }
    public ResolveInfo resolveActivity(android.content.Intent i, int flags) { return new ResolveInfo(); }
    public void setComponentEnabledSetting(android.content.ComponentName c, int s, int f) {}
    public CharSequence getApplicationLabel(ApplicationInfo i) { return "المحاسب الذكي"; }
    public String getInstallerPackageName(String p) { return null; }
}
