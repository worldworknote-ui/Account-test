package android.content;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import java.io.File;

/**
 * Context على الكمبيوتر: مجلد بيانات التطبيق (~/Library/Application Support/SmartAccountant على الماك)، والموارد المشتركة،
 * وخدمات النظام (الحافظة، الإشعارات، المهام الدورية...). الشاشات (Activity) مشتقة منه.
 */
public class Context {
    public static final int MODE_PRIVATE = 0, MODE_APPEND = 0x8000, BIND_AUTO_CREATE = 1, RECEIVER_EXPORTED = 2, RECEIVER_NOT_EXPORTED = 4;
    public static final String CLIPBOARD_SERVICE = "clipboard", INPUT_METHOD_SERVICE = "input_method", NOTIFICATION_SERVICE = "notification",
            JOB_SCHEDULER_SERVICE = "jobscheduler", KEYGUARD_SERVICE = "keyguard", CONNECTIVITY_SERVICE = "connectivity", POWER_SERVICE = "power",
            WINDOW_SERVICE = "window", ALARM_SERVICE = "alarm", VIBRATOR_SERVICE = "vibrator", ACCOUNT_SERVICE = "account", USER_SERVICE = "user",
            LAYOUT_INFLATER_SERVICE = "layout_inflater", AUDIO_SERVICE = "audio", BIOMETRIC_SERVICE = "biometric", DOWNLOAD_SERVICE = "download",
            SHORTCUT_SERVICE = "shortcut", ACTIVITY_SERVICE = "activity", STORAGE_SERVICE = "storage", VIBRATOR_MANAGER_SERVICE = "vibrator_manager";

    /** الموارد المشتركة لكل التطبيق (المقاسات تتبع اختيار المستخدم). */
    public static final Resources RES = new Resources();
    /** سياق التطبيق. */
    public static volatile Context app;

    private File home;

    public Context(File home) {
        this.home = home;
        mk(home);
        // سياق بمجلد صريح هو سياق التطبيق الحالي (نقل مجلد البيانات يُنشئ سياقًا جديدًا فيصبح هو المعتمد)
        app = this;
    }
    /** للمشتقات (Activity): نفس مجلد التطبيق. */
    protected Context() {
        this.home = app != null ? app.home() : defaultHome();
    }

    /**
     * 12.0: وضع الاختبار فقط حين يكون مجلد البيانات (sa.home) مجلدًا مؤقتًا. خيارات الاختبار (فرض نتيجة التحقق، تخزين
     * الأسرار في ملف) لا تعمل خارجه: تشغيل البرنامج على بيانات حقيقية بخيار منها لا يتجاوز القفل ولا يُضعف التخزين.
     */
    public static boolean testMode() {
        String p = System.getProperty("sa.home");
        if (p == null || p.isEmpty()) return false;
        try {
            String home = new File(p).getCanonicalPath(), tmp = new File(System.getProperty("java.io.tmpdir")).getCanonicalPath();
            return home.startsWith(tmp) || home.startsWith("/tmp/");
        } catch (Exception e) {
            return false;
        }
    }

    /** مجلد بيانات التطبيق الافتراضي لهذا النظام. */
    public static File defaultHome() {
        String p = System.getProperty("sa.home");
        if (p != null && !p.isEmpty()) return new File(p);
        String user = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return new File(user, "Library/Application Support/SmartAccountant");
        if (os.contains("win")) {
            String a = System.getenv("APPDATA");
            return new File(a != null ? a : user, "SmartAccountant");
        }
        return new File(user, ".smart-accountant");
    }

    private static File mk(File f) {
        if (!f.exists()) f.mkdirs();
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
        f.setExecutable(false, false);
        f.setExecutable(true, true);
        return f;
    }

    public File home() { return home; }
    public Context getApplicationContext() { return app != null ? app : this; }
    public Context getBaseContext() { return this; }
    public File getFilesDir() { return mk(new File(home, "files")); }
    public File getCacheDir() { return mk(new File(home, "cache")); }
    public File getCodeCacheDir() { return mk(new File(home, "cache/code")); }
    public File getNoBackupFilesDir() { return mk(new File(home, "nobackup")); }
    public File getExternalFilesDir(String type) { return mk(new File(home, "external" + (type == null ? "" : "/" + type))); }
    public File[] getExternalFilesDirs(String type) { return new File[]{getExternalFilesDir(type)}; }
    public File getExternalCacheDir() { return mk(new File(home, "cache/external")); }
    public File getDir(String name, int mode) { return mk(new File(home, "app_" + name)); }
    public File getDatabasePath(String name) { return new File(mk(new File(home, "databases")), name); }
    public File getDataDir() { return home; }
    public String[] databaseList() { String[] l = new File(home, "databases").list(); return l == null ? new String[0] : l; }
    public boolean deleteDatabase(String n) { return getDatabasePath(n).delete(); }
    public String getPackageName() { return "com.smart.hawala.mac"; }
    public String getOpPackageName() { return getPackageName(); }
    public java.io.FileInputStream openFileInput(String n) throws java.io.FileNotFoundException { return new java.io.FileInputStream(new File(getFilesDir(), n)); }
    public java.io.FileOutputStream openFileOutput(String n, int mode) throws java.io.FileNotFoundException { return new java.io.FileOutputStream(new File(getFilesDir(), n), (mode & MODE_APPEND) != 0); }
    public boolean deleteFile(String n) { return new File(getFilesDir(), n).delete(); }
    public String[] fileList() { String[] l = getFilesDir().list(); return l == null ? new String[0] : l; }
    public File getFileStreamPath(String n) { return new File(getFilesDir(), n); }

    public Resources getResources() { return RES; }
    public Resources.Theme getTheme() { return RES.newTheme(); }
    public void setTheme(int res) {}
    public android.content.res.AssetManager getAssets() { return RES.getAssets(); }
    public final Drawable getDrawable(int id) { return RES.getDrawable(id); }
    public final int getColor(int id) { return RES.getColor(id); }
    public final android.content.res.ColorStateList getColorStateList(int id) { return RES.getColorStateList(id); }
    public final String getString(int id) { return RES.getString(id); }
    public final String getString(int id, Object... a) { return RES.getString(id, a); }
    public final CharSequence getText(int id) { return RES.getText(id); }
    public java.util.concurrent.Executor getMainExecutor() { return r -> javax.swing.SwingUtilities.invokeLater(r); }
    public android.os.Looper getMainLooper() { return android.os.Looper.getMainLooper(); }
    public ClassLoader getClassLoader() { return Context.class.getClassLoader(); }

    public ContentResolver getContentResolver() { return ContentResolver.INSTANCE; }
    public android.content.pm.PackageManager getPackageManager() { return android.content.pm.PackageManager.INSTANCE; }
    public android.content.pm.ApplicationInfo getApplicationInfo() { return new android.content.pm.ApplicationInfo(); }
    public SharedPreferences getSharedPreferences(String name, int mode) { return Prefs.get(new File(mk(new File(home, "shared_prefs")), name + ".properties")); }

    private static final java.util.Map<String, Object> services = new java.util.HashMap<>();
    public Object getSystemService(String name) {
        synchronized (services) {
            Object o = services.get(name);
            if (o != null) return o;
            switch (name) {
                case CLIPBOARD_SERVICE: o = new ClipboardManager(); break;
                case INPUT_METHOD_SERVICE: o = new android.view.inputmethod.InputMethodManager(); break;
                case NOTIFICATION_SERVICE: o = new android.app.NotificationManager(); break;
                case JOB_SCHEDULER_SERVICE: o = new android.app.job.JobScheduler(this); break;
                case KEYGUARD_SERVICE: o = new android.app.KeyguardManager(); break;
                case CONNECTIVITY_SERVICE: o = new android.net.ConnectivityManager(); break;
                case POWER_SERVICE: o = new android.os.PowerManager(); break;
                case USER_SERVICE: o = new android.os.UserManager(); break;
                case WINDOW_SERVICE: o = new android.view.WindowManagerImpl(); break;
                case ALARM_SERVICE: o = new android.app.AlarmManager(); break;
                case VIBRATOR_SERVICE: o = new android.os.Vibrator(); break;
                default: return null;
            }
            services.put(name, o);
            return o;
        }
    }
    @SuppressWarnings("unchecked")
    public final <T> T getSystemService(Class<T> c) {
        for (String n : new String[]{CLIPBOARD_SERVICE, INPUT_METHOD_SERVICE, NOTIFICATION_SERVICE, JOB_SCHEDULER_SERVICE, KEYGUARD_SERVICE, CONNECTIVITY_SERVICE, POWER_SERVICE, USER_SERVICE, WINDOW_SERVICE, ALARM_SERVICE, VIBRATOR_SERVICE}) {
            Object o = getSystemService(n);
            if (c.isInstance(o)) return (T) o;
        }
        return null;
    }

    public void startActivity(Intent i) { android.app.Platform.handle(this, i, -1, null); }
    public void startActivity(Intent i, android.os.Bundle b) { startActivity(i); }
    public void sendBroadcast(Intent i) {}
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f) { return null; }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return null; }
    public void unregisterReceiver(BroadcastReceiver r) {}
    public ComponentName startService(Intent i) { return null; }
    public ComponentName startForegroundService(Intent i) { return null; }
    public boolean stopService(Intent i) { return false; }
    public int checkSelfPermission(String p) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
    public int checkCallingOrSelfPermission(String p) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
    public void grantUriPermission(String pkg, android.net.Uri u, int flags) {}
    public void revokeUriPermission(android.net.Uri u, int flags) {}
    public boolean isRestricted() { return false; }
    public Context createDeviceProtectedStorageContext() { return this; }
    public Context createConfigurationContext(android.content.res.Configuration c) { return this; }
}
