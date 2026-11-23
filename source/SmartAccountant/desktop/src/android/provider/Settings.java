package android.provider;
public final class Settings {
    public static final String ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS", ACTION_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS",
            ACTION_SECURITY_SETTINGS = "android.settings.SECURITY_SETTINGS", ACTION_BIOMETRIC_ENROLL = "android.settings.BIOMETRIC_ENROLL", EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE",
            ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", ACTION_SETTINGS = "android.settings.SETTINGS",
            ACTION_DATE_SETTINGS = "android.settings.DATE_SETTINGS", ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS";
    public static final class Secure {
        public static final String ANDROID_ID = "android_id";
        public static String getString(android.content.ContentResolver r, String k) { return "desktop-" + Integer.toHexString(java.lang.System.getProperty("user.name", "").hashCode()); }
    }
    public static final class Global {
        public static final String AUTO_TIME = "auto_time", ANIMATOR_DURATION_SCALE = "animator_duration_scale";
        public static int getInt(android.content.ContentResolver r, String k, int d) { return d; }
        public static float getFloat(android.content.ContentResolver r, String k, float d) { return d; }
        public static String getString(android.content.ContentResolver r, String k) { return null; }
    }
    public static final class System {
        public static int getInt(android.content.ContentResolver r, String k, int d) { return d; }
        public static String getString(android.content.ContentResolver r, String k) { return null; }
    }
}
