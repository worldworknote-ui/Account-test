package com.smart.hawala;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * يشارك ملفات مؤقتة (كشوفات PDF/PNG/CSV، نسخ احتياطية، وصور الكاميرا) من مجلد cache/shared فقط.
 * يحترم وضع الفتح المطلوب: القراءة لا تفرّغ الملف أبدًا (الإصدار السابق كان يفتح مع MODE_TRUNCATE دائمًا للكتابة،
 * والآن يُستخدم الوضع كما طلبه التطبيق الآخر بالضبط).
 */
public class EvidenceProvider extends ContentProvider {

    public static String authority(android.content.Context c) { return c.getPackageName() + ".files"; }

    public static Uri uriFor(android.content.Context c, File f) {
        return Uri.parse("content://" + authority(c) + "/" + Uri.encode(f.getName()));
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri u) {
        String n = u.getLastPathSegment() == null ? "" : u.getLastPathSegment().toLowerCase(Locale.US);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".csv")) return "text/csv";
        return "application/octet-stream";
    }

    @Override public int delete(Uri u, String s, String[] a) { return 0; }

    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }

    private File fileFor(Uri u) throws FileNotFoundException {
        String name = u.getLastPathSegment();
        if (name == null || name.isEmpty()) throw new FileNotFoundException("اسم ملف غير صالح");
        File dir = new File(getContext().getCacheDir(), "shared");
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException("مسار غير مسموح");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("مسار غير صالح");
        }
        return f;
    }

    @Override
    public Cursor query(Uri u, String[] projection, String s, String[] a, String so) {
        String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        List<String> keep = new ArrayList<>();
        for (String c : cols) if (OpenableColumns.DISPLAY_NAME.equals(c) || OpenableColumns.SIZE.equals(c)) keep.add(c);
        MatrixCursor c = new MatrixCursor(keep.toArray(new String[0]));
        try {
            File f = fileFor(u);
            Object[] row = new Object[keep.size()];
            for (int i = 0; i < keep.size(); i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(keep.get(i)) ? f.getName() : f.length();
            c.addRow(row);
        } catch (FileNotFoundException ignored) {
            // مؤشر فارغ
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        File f = fileFor(u);
        int m = ParcelFileDescriptor.parseMode(mode);
        boolean writes = (m & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 || (m & ParcelFileDescriptor.MODE_READ_WRITE) == ParcelFileDescriptor.MODE_READ_WRITE;
        if (writes) {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
        } else if (!f.exists()) {
            throw new FileNotFoundException("الملف غير موجود");
        }
        return ParcelFileDescriptor.open(f, m);
    }
}
