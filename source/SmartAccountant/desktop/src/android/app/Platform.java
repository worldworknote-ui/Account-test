package android.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ما يفعله الهاتف بتطبيقات أخرى يفعله الكمبيوتر بنوافذ النظام: اختيار ملف أو مجلد، حفظ ملف، المشاركة (حفظ نسخة أو فتحها
 * أو إرسال النص عبر واتساب)، وفتح الروابط.
 */
public final class Platform {
    private Platform() {}

    public interface Result { void result(int code, int resultCode, Intent data); }

    /** نافذة البرنامج (لنوافذ الملفات). */
    public static Frame frame;
    /** «الرجوع» من الشاشة الرئيسية: تصغير النافذة. */
    public static Runnable onFinish;
    /** بعد إعادة إنشاء الشاشة. */
    public static java.util.function.Consumer<Activity> onRecreate;
    /** آخر مجلد استُعمل. */
    private static File lastDir;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    public static void handle(Context ctx, Intent i, int code, Result cb) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                run(ctx, i, code, cb);
            } catch (Throwable t) {
                android.widget.Toast.makeText(ctx, "تعذر إكمال العملية: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                if (cb != null) cb.result(code, Activity.RESULT_CANCELED, null);
            }
        });
    }

    private static void run(Context ctx, Intent i, int code, Result cb) throws Exception {
        String a = i.getAction() == null ? "" : i.getAction();
        if (Intent.ACTION_CHOOSER.equals(a)) {
            Intent inner = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (inner != null) run(ctx, inner, code, cb);
            return;
        }
        switch (a) {
            case Intent.ACTION_CREATE_DOCUMENT: {
                String name = i.getStringExtra(Intent.EXTRA_TITLE);
                File f = saveDialog(name == null ? "file" : name);
                done(cb, code, f == null ? null : new Intent().setData(Uri.fromFile(f)));
                return;
            }
            case Intent.ACTION_OPEN_DOCUMENT:
            case Intent.ACTION_GET_CONTENT:
            case Intent.ACTION_PICK: {
                boolean multi = i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                String type = i.getType();
                String[] mimes = i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
                List<File> files = openDialog(multi, type, mimes);
                if (files.isEmpty()) { done(cb, code, null); return; }
                Intent r = new Intent().setData(Uri.fromFile(files.get(0)));
                if (files.size() > 1) {
                    ClipData c = ClipData.newRawUri("files", Uri.fromFile(files.get(0)));
                    for (int k = 1; k < files.size(); k++) c.addItem(new ClipData.Item(Uri.fromFile(files.get(k))));
                    r.setClipData(c);
                }
                done(cb, code, r);
                return;
            }
            case Intent.ACTION_OPEN_DOCUMENT_TREE: {
                File d = dirDialog();
                done(cb, code, d == null ? null : new Intent().setData(Uri.fromFile(d)));
                return;
            }
            case android.provider.MediaStore.ACTION_IMAGE_CAPTURE: {
                // لا كاميرا: صورة من الملفات تُنسخ إلى المكان المطلوب
                List<File> f = openDialog(false, "image/*", null);
                Uri out = i.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT);
                if (f.isEmpty() || out == null) { done(cb, code, null); return; }
                java.nio.file.Files.copy(f.get(0).toPath(), out.file().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                done(cb, code, new Intent().setData(out));
                return;
            }
            case Intent.ACTION_SEND:
            case Intent.ACTION_SEND_MULTIPLE: {
                share(ctx, i);
                done(cb, code, new Intent());
                return;
            }
            case Intent.ACTION_VIEW:
            case Intent.ACTION_DIAL:
            case Intent.ACTION_SENDTO: {
                Uri u = i.getData();
                if (u != null) view(u);
                done(cb, code, new Intent());
                return;
            }
            default:
                if (cb != null) cb.result(code, Activity.RESULT_CANCELED, null);
        }
    }

    private static void done(Result cb, int code, Intent data) {
        if (cb != null) cb.result(code, data == null ? Activity.RESULT_CANCELED : Activity.RESULT_OK, data);
    }

    /** فتح رابط أو ملف بالبرنامج المناسب في النظام. */
    public static void view(Uri u) throws Exception {
        if (u.isFile() || u.toString().startsWith("content://")) Desktop.getDesktop().open(u.file());
        else Desktop.getDesktop().browse(new java.net.URI(u.toString()));
    }

    // ------------------------------------------------------------------ نوافذ الملفات
    public static File saveDialog(String name) {
        if (MAC || !headless()) {
            FileDialog fd = new FileDialog(frame, "حفظ", FileDialog.SAVE);
            if (lastDir != null) fd.setDirectory(lastDir.getPath());
            fd.setFile(name);
            fd.setVisible(true);
            if (fd.getFile() == null) return null;
            File f = new File(fd.getDirectory(), fd.getFile());
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            if (!ext.isEmpty() && !f.getName().toLowerCase().endsWith(ext.toLowerCase())) f = new File(f.getParentFile(), f.getName() + ext);
            lastDir = f.getParentFile();
            return f;
        }
        return null;
    }

    public static List<File> openDialog(boolean multi, String type, String[] mimes) {
        List<File> out = new ArrayList<>();
        FileDialog fd = new FileDialog(frame, "اختيار ملف", FileDialog.LOAD);
        fd.setMultipleMode(multi);
        if (lastDir != null) fd.setDirectory(lastDir.getPath());
        final List<String> exts = exts(type, mimes);
        if (!exts.isEmpty()) {
            fd.setFilenameFilter((dir, n) -> { String l = n.toLowerCase(); for (String e : exts) if (l.endsWith(e)) return true; return false; });
            if (!MAC) fd.setFile("*" + String.join(";*", exts));
        }
        fd.setVisible(true);
        for (File f : fd.getFiles()) out.add(f);
        if (!out.isEmpty()) lastDir = out.get(0).getParentFile();
        return out;
    }

    private static List<String> exts(String type, String[] mimes) {
        List<String> l = new ArrayList<>();
        List<String> all = new ArrayList<>();
        if (type != null) all.add(type);
        if (mimes != null) for (String m : mimes) all.add(m);
        for (String t : all) {
            if (t == null || t.equals("*/*")) return new ArrayList<>();
            if (t.startsWith("image/")) { l.add(".png"); l.add(".jpg"); l.add(".jpeg"); l.add(".webp"); l.add(".heic"); l.add(".gif"); }
            else if (t.contains("pdf")) l.add(".pdf");
            else if (t.contains("json")) l.add(".json");
            else if (t.contains("csv") || t.equals("text/comma-separated-values")) l.add(".csv");
            else if (t.equals("text/plain")) l.add(".txt");
            else if (t.contains("spreadsheet") || t.contains("excel")) { l.add(".xlsx"); l.add(".xls"); }
            else if (t.contains("zip")) l.add(".zip");
            else if (t.startsWith("text/")) { l.add(".txt"); l.add(".csv"); }
            else return new ArrayList<>();
        }
        return l;
    }

    public static File dirDialog() {
        if (MAC) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true");
            try {
                FileDialog fd = new FileDialog(frame, "اختيار مجلد", FileDialog.LOAD);
                if (lastDir != null) fd.setDirectory(lastDir.getPath());
                fd.setVisible(true);
                if (fd.getFile() == null) return null;
                File d = new File(fd.getDirectory(), fd.getFile());
                lastDir = d;
                return d;
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false");
            }
        }
        javax.swing.JFileChooser ch = new javax.swing.JFileChooser(lastDir);
        ch.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        ch.setDialogTitle("اختيار مجلد");
        if (ch.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return null;
        lastDir = ch.getSelectedFile();
        return ch.getSelectedFile();
    }

    private static boolean headless() { return GraphicsEnvironment.isHeadless(); }

    // ------------------------------------------------------------------ المشاركة
    /** المشاركة على الكمبيوتر: الملفات تُحفظ نسخة منها أو تُفتح؛ النص يُنسخ أو يُرسل عبر واتساب. */
    static void share(Context ctx, Intent i) throws Exception {
        List<Uri> files = new ArrayList<>();
        Object s = i.getExtras() == null ? null : i.getExtras().get(Intent.EXTRA_STREAM);
        if (s instanceof Uri) files.add((Uri) s);
        else if (s instanceof List) for (Object o : (List<?>) s) if (o instanceof Uri) files.add((Uri) o);
        final CharSequence text = i.getCharSequenceExtra(Intent.EXTRA_TEXT) != null ? i.getCharSequenceExtra(Intent.EXTRA_TEXT) : i.getStringExtra(Intent.EXTRA_TEXT);
        List<File> fs = new ArrayList<>();
        for (Uri u : files) {
            File f = u.file();
            if (f == null || !f.isFile()) throw new java.io.FileNotFoundException("الملف غير موجود: " + (f == null ? u : f.getName()));
            fs.add(f);
        }
        if (fs.isEmpty() && text == null) return;
        ShareSheet.share(ctx, fs, text, i.getStringExtra(Intent.EXTRA_SUBJECT));
    }

    /** يفتح رابط ويب في المتصفح؛ false إن تعذّر. */
    public static boolean browseQuiet(String url) {
        try {
            Desktop.getDesktop().browse(new java.net.URI(url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void reveal(File f) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) new ProcessBuilder("open", "-R", f.getAbsolutePath()).start();
        else if (os.contains("win")) new ProcessBuilder("explorer.exe", "/select,", f.getAbsolutePath()).start();
        else Desktop.getDesktop().open(f.getParentFile());
    }
}
