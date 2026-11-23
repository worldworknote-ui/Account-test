package android.content;

import android.database.Cursor;
import android.net.Uri;
import java.io.*;

/** المحتوى على الكمبيوتر = ملفات (file://). القوائم والأسماء والأحجام من نظام الملفات. */
public class ContentResolver {
    static final ContentResolver INSTANCE = new ContentResolver();
    public static final String SCHEME_FILE = "file", SCHEME_CONTENT = "content", SCHEME_ANDROID_RESOURCE = "android.resource";

    public InputStream openInputStream(Uri u) throws FileNotFoundException {
        if (u == null) throw new FileNotFoundException("null");
        return new FileInputStream(u.file());
    }
    public OutputStream openOutputStream(Uri u) throws FileNotFoundException { return openOutputStream(u, "w"); }
    public OutputStream openOutputStream(Uri u, String mode) throws FileNotFoundException {
        File f = u.file();
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        return new FileOutputStream(f, mode != null && mode.contains("a"));
    }
    public android.os.ParcelFileDescriptor openFileDescriptor(Uri u, String mode) throws FileNotFoundException {
        return android.os.ParcelFileDescriptor.open(u.file(), mode != null && mode.contains("w") ? android.os.ParcelFileDescriptor.MODE_READ_WRITE : android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public String getType(Uri u) {
        String n = u.file().getName().toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (u.file().isDirectory()) return android.provider.DocumentsContract.Document.MIME_TYPE_DIR;
        return "application/octet-stream";
    }
    public void takePersistableUriPermission(Uri u, int flags) {}
    public void releasePersistableUriPermission(Uri u, int flags) {}
    public java.util.List<UriPermission> getPersistedUriPermissions() { return new java.util.ArrayList<>(); }
    public void registerContentObserver(Uri u, boolean d, android.database.ContentObserver o) {}
    public void unregisterContentObserver(android.database.ContentObserver o) {}
    public void notifyChange(Uri u, android.database.ContentObserver o) {}
    public boolean refresh(Uri u, android.os.Bundle b, android.os.CancellationSignal s) { return true; }
    public int delete(Uri u, String w, String[] a) { return u.file().delete() ? 1 : 0; }

    /** أسماء الملفات وأحجامها، ومحتوى المجلدات (لمستندات SAF). */
    public Cursor query(Uri u, String[] proj, String sel, String[] args, String order) {
        return android.provider.DocumentsContract.query(u, proj);
    }
    public Cursor query(Uri u, String[] proj, android.os.Bundle q, android.os.CancellationSignal s) { return query(u, proj, null, null, null); }

    public static class UriPermission { public Uri getUri() { return null; } public boolean isReadPermission() { return true; } public boolean isWritePermission() { return true; } }
}
