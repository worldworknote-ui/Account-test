package android.os;
public class Environment {
    public static final String DIRECTORY_DOWNLOADS = "Downloads", DIRECTORY_DOCUMENTS = "Documents", DIRECTORY_PICTURES = "Pictures", MEDIA_MOUNTED = "mounted";
    public static java.io.File getExternalStorageDirectory() { return new java.io.File(System.getProperty("user.home")); }
    public static java.io.File getExternalStoragePublicDirectory(String t) { return new java.io.File(System.getProperty("user.home"), t); }
    public static String getExternalStorageState() { return MEDIA_MOUNTED; }
    public static java.io.File getDataDirectory() { return android.content.Context.defaultHome(); }
}
