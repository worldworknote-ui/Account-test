package android.provider;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.io.File;

/** مستندات SAF على الكمبيوتر: الشجرة مجلد، والمستند ملف بعنوانه file://، ومعرّف المستند مساره الكامل. */
public final class DocumentsContract {
    public static final String EXTRA_LOADING = "loading", EXTRA_INFO = "info", EXTRA_ERROR = "error";
    public static final String EXTRA_INITIAL_URI = "android.provider.extra.INITIAL_URI", EXTRA_PROMPT = "android.provider.extra.PROMPT";
    public static final class Document {
        public static final String COLUMN_DOCUMENT_ID = "document_id", COLUMN_DISPLAY_NAME = "_display_name", COLUMN_MIME_TYPE = "mime_type",
                COLUMN_LAST_MODIFIED = "last_modified", COLUMN_SIZE = "_size", COLUMN_FLAGS = "flags", MIME_TYPE_DIR = "vnd.android.document/directory";
        public static final int FLAG_SUPPORTS_DELETE = 4, FLAG_SUPPORTS_WRITE = 2, FLAG_DIR_SUPPORTS_CREATE = 8, FLAG_SUPPORTS_RENAME = 64;
    }
    private static final String CHILDREN = "sa_children=1";

    public static String getTreeDocumentId(Uri tree) { return tree.file().getAbsolutePath(); }
    public static String getDocumentId(Uri doc) { return doc.file().getAbsolutePath(); }
    public static Uri buildDocumentUriUsingTree(Uri tree, String docId) { return Uri.fromFile(new File(docId)); }
    public static Uri buildDocumentUri(String authority, String docId) { return Uri.fromFile(new File(docId)); }
    public static Uri buildTreeDocumentUri(String authority, String docId) { return Uri.fromFile(new File(docId)); }
    public static Uri buildChildDocumentsUriUsingTree(Uri tree, String parentId) { return Uri.parse(Uri.fromFile(new File(parentId)) + "?" + CHILDREN); }
    public static boolean isDocumentUri(android.content.Context c, Uri u) { return u != null && u.isFile(); }
    public static boolean isTreeUri(Uri u) { return u != null && u.isFile() && u.file().isDirectory(); }

    public static Uri createDocument(ContentResolver r, Uri parent, String mime, String name) throws java.io.FileNotFoundException {
        File dir = parent.file();
        dir.mkdirs();
        File f = new File(dir, name);
        if (Document.MIME_TYPE_DIR.equals(mime)) { f.mkdirs(); return Uri.fromFile(f); }
        try {
            if (!f.exists() && !f.createNewFile()) throw new java.io.FileNotFoundException(f.getPath());
        } catch (java.io.IOException e) {
            throw new java.io.FileNotFoundException(e.getMessage());
        }
        return Uri.fromFile(f);
    }
    public static boolean deleteDocument(ContentResolver r, Uri doc) throws java.io.FileNotFoundException {
        File f = doc.file();
        if (!f.exists()) throw new java.io.FileNotFoundException(f.getPath());
        return f.delete();
    }
    public static Uri renameDocument(ContentResolver r, Uri doc, String name) throws java.io.FileNotFoundException {
        File f = doc.file(), t = new File(f.getParentFile(), name);
        if (!f.renameTo(t)) throw new java.io.FileNotFoundException(f.getPath());
        return Uri.fromFile(t);
    }

    /** للاستعلام من ContentResolver. */
    public static Cursor query(Uri u, String[] proj) {
        String[] cols = proj != null ? proj : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        File f = u.file();
        if (u.toString().endsWith(CHILDREN)) {
            File[] l = f.listFiles();
            if (l != null) for (File x : l) if (!x.getName().startsWith(".")) c.addRow(row(cols, x));
        } else if (f.exists()) c.addRow(row(cols, f));
        return c;
    }
    private static Object[] row(String[] cols, File f) {
        Object[] r = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            switch (cols[i]) {
                case Document.COLUMN_DOCUMENT_ID: r[i] = f.getAbsolutePath(); break;
                case Document.COLUMN_DISPLAY_NAME: r[i] = f.getName(); break;
                case Document.COLUMN_MIME_TYPE: r[i] = f.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                case Document.COLUMN_LAST_MODIFIED: r[i] = f.lastModified(); break;
                case Document.COLUMN_SIZE: r[i] = f.length(); break;
                case Document.COLUMN_FLAGS: r[i] = 0x4F; break;
                default: r[i] = null;
            }
        }
        return r;
    }
}
