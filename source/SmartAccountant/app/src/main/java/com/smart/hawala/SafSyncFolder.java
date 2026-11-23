package com.smart.hawala;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 10.0: مجلد المزامنة على الهاتف (إطار التخزين في أندرويد). يُختار مرة واحدة بصلاحية دائمة.
 * الملف الجديد يُكتب باسم مؤقت ثم يُعاد تسميته فلا يرى جهاز آخر ملفًا نصف مكتوب.
 */
final class SafSyncFolder implements SyncFolder {
    private final ContentResolver cr;
    private final Uri tree;
    private final String treeDoc;
    private final Map<String, String> ids = new HashMap<>();
    /** 10.4: [الحجم، وقت التعديل] لكل ملف من آخر قراءة للقائمة. */
    private final Map<String, long[]> info = new HashMap<>();
    /** 10.4: قُرئت القائمة مرة على الأقل في هذه الجولة (الكتابة لا تعيد قراءتها لكل ملف جديد). */
    private boolean listedOnce;

    SafSyncFolder(Context ctx, Uri tree) {
        this.cr = ctx.getContentResolver();
        this.tree = tree;
        this.treeDoc = DocumentsContract.getTreeDocumentId(tree);
    }

    /** أول قراءة للمجلد في هذه الجولة تنتظر المزوّد حتى يُكمل تحديث القائمة من السحابة (Google Drive مثلًا). */
    private boolean waited;
    /** للتشخيص: عدد الملفات الظاهرة، وهل بقي المزوّد يحدّث القائمة بعد انتهاء الانتظار. */
    int visible;
    boolean stillLoading;

    private void refresh() throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc);
        boolean wait = !waited && android.os.Looper.myLooper() != android.os.Looper.getMainLooper();
        waited = true;
        if (wait) {
            try {
                // يطلب من المزوّد جلب أحدث قائمة من السحابة (يتجاهله من لا يدعمه)
                cr.refresh(children, null, null);
            } catch (Throwable ignored) {
                // غير مدعوم
            }
        }
        for (int round = 0; ; round++) {
            Map<String, String[]> found = new HashMap<>();
            boolean loading;
            final java.util.concurrent.CountDownLatch changed = new java.util.concurrent.CountDownLatch(1);
            android.database.ContentObserver obs = null;
            Uri notify = null;
            try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (c == null) throw new IOException("مجلد المزامنة غير متاح. اختره من جديد من «المزامنة».");
                while (c.moveToNext()) {
                    String n = c.getString(1);
                    if (n == null) continue;
                    long m = c.isNull(2) ? 0 : c.getLong(2);
                    String[] prev = found.get(n);
                    // أسماء مكررة (Google Drive يسمح بها): الأحدث يفوز
                    long sz = c.isNull(3) ? 0 : c.getLong(3);
                    if (prev == null || Long.parseLong(prev[1]) < m) found.put(n, new String[]{c.getString(0), "" + m, "" + sz});
                }
                android.os.Bundle ex = c.getExtras();
                loading = ex != null && ex.getBoolean(DocumentsContract.EXTRA_LOADING, false);
                if (loading && wait && round < 4) notify = c.getNotificationUri();
            } catch (SecurityException e) {
                throw new IOException("انتهت صلاحية الوصول إلى مجلد المزامنة. اختره من جديد من «المزامنة».");
            }
            ids.clear();
            info.clear();
            for (Map.Entry<String, String[]> e : found.entrySet()) {
                ids.put(e.getKey(), e.getValue()[0]);
                info.put(e.getKey(), new long[]{Long.parseLong(e.getValue()[2]), Long.parseLong(e.getValue()[1])});
            }
            listedOnce = true;
            visible = ids.size();
            stillLoading = loading;
            if (!loading || !wait || round >= 4) return;
            // المزوّد ما زال يجلب القائمة: ننتظر إشعاره (أو 6 ثوانٍ) ثم نقرأ من جديد
            try {
                if (notify != null) {
                    obs = new android.database.ContentObserver(null) {
                        @Override public void onChange(boolean self) { changed.countDown(); }
                    };
                    cr.registerContentObserver(notify, true, obs);
                }
                changed.await(6, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
                // نكتفي بالانتظار
            } finally {
                if (obs != null) cr.unregisterContentObserver(obs);
            }
        }
    }

    private Uri doc(String name) throws IOException {
        String id = ids.get(name);
        if (id == null) {
            refresh();
            id = ids.get(name);
            if (id == null && !name.startsWith(".")) id = recover(name);
        }
        return id == null ? null : DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    @Override public List<String> list(String prefix) throws IOException {
        refresh();
        List<String> out = new ArrayList<>();
        for (String n : ids.keySet()) if (n.startsWith(prefix)) out.add(n);
        return out;
    }

    @Override public boolean exists(String name) throws IOException { return doc(name) != null; }

    @Override public byte[] read(String name) throws IOException {
        Uri u = doc(name);
        if (u == null) return null;
        try (InputStream in = cr.openInputStream(u); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } catch (java.io.FileNotFoundException e) {
            ids.remove(name);
            return null;
        }
    }

    @Override public void write(String name, byte[] data) throws IOException {
        // 10.4: ملف جديد (ليس في القائمة) يُنشأ مباشرة دون إعادة قراءة المجلد كله
        Uri existing;
        if (!listedOnce) existing = doc(name);
        else {
            String id = ids.get(name);
            existing = id == null ? null : DocumentsContract.buildDocumentUriUsingTree(tree, id);
        }
        if (existing != null) {
            replaceSafely(existing, name, data);
            return;
        }
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc);
        String tmp = ".tmp-" + System.nanoTime() + "-" + name;
        Uri u = DocumentsContract.createDocument(cr, parent, "application/octet-stream", tmp);
        if (u == null) throw new IOException("تعذر إنشاء ملف في مجلد المزامنة");
        try (OutputStream o = cr.openOutputStream(u, "w")) {
            if (o == null) throw new IOException("تعذر الكتابة في مجلد المزامنة");
            o.write(data);
        }
        Uri renamed = null;
        try {
            renamed = DocumentsContract.renameDocument(cr, u, name);
        } catch (Exception ignored) {
            // بعض المزوّدين لا يدعمون إعادة التسمية: نكتب بالاسم النهائي مباشرة
        }
        if (renamed == null) {
            DocumentsContract.deleteDocument(cr, u);
            Uri direct = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name);
            if (direct == null) throw new IOException("تعذر إنشاء ملف في مجلد المزامنة");
            try (OutputStream o = cr.openOutputStream(direct, "w")) {
                if (o == null) throw new IOException("تعذر الكتابة في مجلد المزامنة");
                o.write(data);
            }
            renamed = direct;
        }
        // 10.4: يُضاف الملف إلى القائمة المعروفة دون إعادة قراءة المجلد كله بعد كل ملف
        try {
            ids.put(name, DocumentsContract.getDocumentId(renamed));
            info.put(name, new long[]{data.length, Clock.now()});
        } catch (Exception e) {
            refresh();
        }
    }

    /**
     * 11.5: استبدال آمن لملف موجود: النسخة الجديدة تُكتب كاملة في ملف مؤقت وتُقرأ للتحقق، ثم يُنحّى القديم باسم «.old-…»
     * ويأخذ الجديد اسمه، ثم يُحذف القديم. (كان يُكتب فوق القديم مباشرة، فانقطاع الكتابة يترك ملفًا تالفًا بلا نسخة سليمة.)
     * انقطاع بين الخطوتين يترك «.old-…» سليمًا تستعيده القراءة التالية. مزوّد لا يدعم إعادة التسمية: الكتابة فوقه بعد التحقق.
     */
    private void replaceSafely(Uri existing, String name, byte[] data) throws IOException {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc);
        long t = System.nanoTime();
        Uri tmp = DocumentsContract.createDocument(cr, parent, "application/octet-stream", ".tmp-" + t + "-" + name);
        if (tmp == null) throw new IOException("تعذر إنشاء ملف في مجلد المزامنة");
        try (OutputStream o = cr.openOutputStream(tmp, "w")) {
            if (o == null) throw new IOException("تعذر الكتابة في مجلد المزامنة");
            o.write(data);
        }
        if (!sameLength(tmp, data.length)) {
            try { DocumentsContract.deleteDocument(cr, tmp); } catch (Exception ignored) { /* يبقى مؤقتًا */ }
            throw new IOException("لم تكتمل كتابة " + name + " في مجلد المزامنة (بقيت النسخة السابقة كما هي)");
        }
        Uri aside = null;
        try {
            aside = DocumentsContract.renameDocument(cr, existing, ".old-" + t + "-" + name);
        } catch (Exception ignored) {
            // لا يدعم إعادة التسمية
        }
        if (aside != null) {
            Uri done = null;
            try {
                done = DocumentsContract.renameDocument(cr, tmp, name);
            } catch (Exception ignored) {
                // نعيد القديم
            }
            if (done != null) {
                try { DocumentsContract.deleteDocument(cr, aside); } catch (Exception ignored) { /* يُنظَّف لاحقًا */ }
                ids.put(name, DocumentsContract.getDocumentId(done));
                info.put(name, new long[]{data.length, Clock.now()});
                return;
            }
            try { DocumentsContract.renameDocument(cr, aside, name); } catch (Exception ignored) { /* تستعيده القراءة */ }
        }
        // مزوّد بلا إعادة تسمية: الكتابة فوق القديم (المحتوى الجديد تُحقق من اكتماله أعلاه)
        try { DocumentsContract.deleteDocument(cr, tmp); } catch (Exception ignored) { /* مؤقت */ }
        try (OutputStream o = cr.openOutputStream(existing, "wt")) {
            if (o == null) throw new IOException("تعذر الكتابة في مجلد المزامنة");
            o.write(data);
        }
    }

    private boolean sameLength(Uri u, int n) {
        try (InputStream in = cr.openInputStream(u)) {
            if (in == null) return false;
            long total = 0;
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) total += r;
            return total == n;
        } catch (Exception e) {
            return false;
        }
    }

    /** 11.5: ملف غاب اسمه بين خطوتي الاستبدال: تُستعاد نسخته السابقة السليمة «.old-…-الاسم». */
    private String recover(String name) {
        String best = null;
        for (String k : ids.keySet()) if (k.startsWith(".old-") && k.endsWith("-" + name) && (best == null || k.compareTo(best) > 0)) best = k;
        if (best == null) return null;
        try {
            Uri r = DocumentsContract.renameDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, ids.get(best)), name);
            if (r == null) return null;
            String id = DocumentsContract.getDocumentId(r);
            ids.remove(best);
            ids.put(name, id);
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public boolean canDelete() { return true; }

    @Override public void delete(String name) throws IOException {
        if (!listedOnce) refresh();
        String id = ids.get(name);
        if (id == null) return;
        try {
            DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id));
        } catch (java.io.FileNotFoundException e) {
            // حُذف من قبل
        } catch (Exception e) {
            throw new IOException("تعذر حذف ملف من مجلد المزامنة: " + e.getMessage());
        }
        ids.remove(name);
        info.remove(name);
    }

    @Override public Map<String, long[]> stat(String prefix) throws IOException {
        refresh();
        Map<String, long[]> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : info.entrySet()) if (e.getKey().startsWith(prefix)) out.put(e.getKey(), e.getValue());
        return out;
    }

    @Override public String describe() {
        String name = null;
        try (Cursor c = cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc), new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Throwable ignored) {
            // يبقى الاسم من الرابط
        }
        if (name == null) {
            String d = Uri.decode(tree.getLastPathSegment() == null ? tree.toString() : tree.getLastPathSegment());
            return d.replace("primary:", "ذاكرة الهاتف/");
        }
        return provider() + " / " + name;
    }

    /** اسم مزوّد المجلد للمستخدم. */
    String provider() {
        String a = tree.getAuthority() == null ? "" : tree.getAuthority();
        if (a.contains("google.android.apps.docs")) return "Google Drive";
        if (a.contains("externalstorage")) return "ذاكرة الهاتف";
        if (a.contains("onedrive")) return "OneDrive";
        if (a.contains("dropbox")) return "Dropbox";
        return a;
    }

    boolean cloudDrive() { return tree.getAuthority() != null && tree.getAuthority().contains("google.android.apps.docs"); }

    @Override public String note() {
        if (stillLoading) return provider() + " ما زال يحدّث قائمة ملفات المجلد؛ قد تصل بعض التغييرات في الجولة التالية.";
        return "";
    }
}
