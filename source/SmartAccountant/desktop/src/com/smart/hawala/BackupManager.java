package com.smart.hawala;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * إنشاء النسخ الاحتياطية واستعادتها — نسخة الماك من BackupManager (الصيغة والتحقق نفسهما؛ ملفات بدل Uri أندرويد).
 * مولَّد من ملف الهاتف: أي تعديل منطقي يُجرى هناك أولًا.
 *
 * <p>طريقة العمل الجديدة (تحل مشكلة «تعذّر إنشاء النسخة»):
 * <ol>
 *   <li>تُنشأ النسخة أولًا داخل التطبيق (مجلد backups) ثم تُفك بالكامل للتحقق منها بنسخة مستقلة من كلمة المرور.</li>
 *   <li>بعد نجاح التحقق فقط يختار المستخدم مكان الحفظ؛ يُنسخ الملف الجاهز إليه (لا حاجة لكلمة المرور في هذه المرحلة،
 *       فلا تضيع العملية إذا أعاد النظام تشغيل التطبيق أثناء اختيار المكان).</li>
 *   <li>يُعاد قراءة الملف من مكان الحفظ ومقارنة بصمته SHA-256 بالأصل؛ النجاح لا يُسجّل إلا بعد التطابق.</li>
 * </ol>
 */
public final class BackupManager {

    private BackupManager() {}

    /** مراحل العملية، لتوضيح مكان الفشل للمستخدم دون كشف أي بيانات حساسة. */
    public enum Stage {
        PREPARE("تجهيز البيانات"),
        ENCRYPT("التشفير والكتابة داخل التطبيق"),
        VERIFY("التحقق من النسخة"),
        WRITE("الكتابة في المكان المختار"),
        REREAD("إعادة قراءة الملف من المكان المختار"),
        READ("قراءة ملف النسخة"),
        DECRYPT("فك التشفير"),
        VALIDATE("فحص محتوى النسخة"),
        SNAPSHOT("حفظ نسخة أمان من البيانات الحالية"),
        IMPORT("استبدال البيانات");

        public final String label;

        Stage(String label) { this.label = label; }
    }

    public static final class BackupException extends Exception {
        public final Stage stage;

        public BackupException(Stage stage, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
        }

        public String userMessage() {
            String m = getMessage();
            return "المرحلة: " + stage.label + "\n\n" + (m == null ? "خطأ غير معروف" : m);
        }
    }

    public interface Progress {
        void update(String message);
    }

    static BackupException wrap(Stage stage, Throwable t) {
        if (t instanceof BackupException) return (BackupException) t;
        String m;
        if (t instanceof SecureStreams.WrongPasswordException || t instanceof SecureStreams.CorruptBackupException) m = t.getMessage();
        else if (t instanceof OutOfMemoryError) m = "الذاكرة لا تكفي. أغلق التطبيقات الأخرى ثم أعد المحاولة.";
        else if (t instanceof IOException && t.getMessage() != null && t.getMessage().toLowerCase(Locale.US).contains("space")) m = "لا توجد مساحة كافية في التخزين.";
        else if (t.getMessage() != null && !t.getMessage().trim().isEmpty()) m = t.getMessage();
        else m = t.getClass().getSimpleName();
        return new BackupException(stage, m, t);
    }

    // ===================================================================================
    // الإنشاء والتحقق داخل التطبيق
    // ===================================================================================

    public static final class Created {
        public File file;
        public long size;
        public String sha256;
        public Map<String, Long> counts;
        public BackupFormat.WriteResult result;
    }

    public static File backupsDir(Context c) {
        File d = new File(c.getFilesDir(), "backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String newName() {
        return "smart-accountant-" + Model.sdf(new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)).format(new Date()) + ".sba";
    }

    /**
     * ينشئ نسخة مشفّرة داخل التطبيق ويتحقق منها بالكامل. لا يعدّل مصفوفة كلمة المرور الممرّرة؛
     * يعمل على نسخ مستقلة ويمسحها في النهاية. المستدعي مسؤول عن مسح مصفوفته.
     */
    public static Created createLocal(Context ctx, Db db, char[] password, String appVersion, Progress p) throws BackupException {
        if (password == null || password.length < 8) throw new BackupException(Stage.PREPARE, "كلمة المرور يجب ألا تقل عن 8 محارف", null);
        File dir = backupsDir(ctx);
        long free = dir.getUsableSpace();
        if (free > 0 && free < 5L * 1024 * 1024) throw new BackupException(Stage.PREPARE, "مساحة التخزين المتبقية في الهاتف قليلة جدًا. احذف بعض الملفات ثم أعد المحاولة.", null);
        String name = newName();
        File tmp = new File(dir, name + ".tmp");
        File out = new File(dir, name);
        char[] pwWrite = password.clone();
        char[] pwVerify = password.clone();
        Created c = new Created();
        try {
            p.update("جارٍ تجهيز البيانات…");
            c.counts = db.counts();
            BackupFormat.Source src = db.backupSource();

            p.update("جارٍ التشفير والكتابة…");
            SecureStreams.EncryptingOutputStream enc = null;
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                BufferedOutputStream bos = new BufferedOutputStream(fos, 65536);
                // 10.14: إن فُعّل «رمز استرداد النسخ» تُفتح النسخة بكلمة مرورها أو بالرمز
                enc = new SecureStreams.EncryptingOutputStream(bos, pwWrite, BackupRecovery.key(db));
                c.result = BackupFormat.write(src, enc, appVersion, Db.VER);
                enc.close();
                bos.flush();
                fos.getFD().sync();
            } catch (Throwable t) {
                if (enc != null) enc.abort();
                tmp.delete();
                throw wrap(Stage.ENCRYPT, t);
            }

            p.update("جارٍ التحقق من النسخة (فك كامل)…");
            try {
                verifyFile(tmp, pwVerify, c.result);
            } catch (Throwable t) {
                tmp.delete();
                throw wrap(Stage.VERIFY, t);
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                throw new BackupException(Stage.ENCRYPT, "تعذر حفظ ملف النسخة داخل التطبيق", null);
            }
            c.file = out;
            c.size = out.length();
            try {
                c.sha256 = sha256(out);
            } catch (Exception e) {
                throw wrap(Stage.VERIFY, e);
            }
            pruneLocal(ctx, 3);
            return c;
        } finally {
            Arrays.fill(pwWrite, '\0');
            Arrays.fill(pwVerify, '\0');
        }
    }

    /** يفك الملف بالكامل دون حفظ شيء، ويطابق الأعداد مع ما كُتب. */
    static void verifyFile(File f, char[] password, BackupFormat.WriteResult expected) throws Exception {
        final long[] bytes = {0};
        BackupFormat.AttachmentStore counting = new BackupFormat.AttachmentStore() {
            @Override public Object store(long id, InputStream data) throws IOException {
                byte[] b = new byte[16384];
                int n;
                while ((n = data.read(b)) > 0) bytes[0] += n;
                return id;
            }

            @Override public void discard(Object handle) { /* لا شيء محفوظ */ }
        };
        File spill = new File(f.getParentFile(), ".verify-spill");
        BackupFormat.Parsed parsed = null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 65536)) {
            SecureStreams.DecryptingInputStream dec = new SecureStreams.DecryptingInputStream(in, password);
            parsed = BackupFormat.readV5(dec, counting, spill);
            if (expected != null) {
                for (Map.Entry<String, Long> e : expected.rows.entrySet()) {
                    if (parsed.table(e.getKey()).size() != e.getValue()) {
                        throw new SecureStreams.CorruptBackupException("عدد صفوف " + e.getKey() + " بعد الفك لا يطابق الأصل");
                    }
                }
                if (parsed.attachments.size() != expected.attachments) {
                    throw new SecureStreams.CorruptBackupException("عدد الصور بعد الفك لا يطابق الأصل");
                }
            }
            BackupFormat.Report r = BackupFormat.validate(parsed);
            if (!r.ok()) throw new SecureStreams.CorruptBackupException("فشل فحص محتوى النسخة: " + r.errors.get(0));
        } finally {
            if (parsed != null) BackupFormat.discardSpill(parsed);
            Snapshot.deleteTree(spill);
        }
    }

    /** نتيجة تمرين الاستعادة (ت6). */
    public static final class DrillResult {
        public boolean ok, wrongPassword;
        public String message = "";
        public long offices, tx, attachments, newerTx;
    }

    /**
     * 8.0 (ت6): يفك النسخة كاملة (الصور تُفك وتُعد دون حفظ، والجداول الكبيرة في ملف مؤقت مشفّر) ويشغّل فحص الاستعادة
     * نفسه، دون أي تغيير في البيانات. «النسخة التي لم تُجرَّب استعادتها لا تُعدّ نسخة».
     */
    public static DrillResult drill(Context ctx, File f, char[] password, Progress p) {
        DrillResult r = new DrillResult();
        File spill = new File(ctx.getCacheDir(), "drill-spill");
        BackupFormat.Parsed parsed = null;
        final long[] att = {0};
        BackupFormat.AttachmentStore counting = new BackupFormat.AttachmentStore() {
            @Override public Object store(long id, InputStream data) throws IOException {
                byte[] b = new byte[16384];
                while (data.read(b) > 0) { /* الصورة تُقرأ كاملة ويُتحقق من بصمتها */ }
                att[0]++;
                return id;
            }

            @Override public void discard(Object handle) { /* لا شيء محفوظ */ }
        };
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 65536)) {
            p.update("جارٍ فك التشفير وقراءة كل الجداول والصور…");
            parsed = BackupFormat.readV5(new SecureStreams.DecryptingInputStream(in, password), counting, spill);
            p.update("جارٍ فحص القيود والعلاقات…");
            BackupFormat.Report rep = BackupFormat.validate(parsed);
            if (!rep.ok()) {
                r.message = "النسخة تُفك لكنها لا تجتاز الفحص: " + rep.errors.get(0);
                return r;
            }
            r.offices = rep.offices;
            r.tx = rep.txActive + rep.txCancelled;
            r.attachments = att[0];
            Db db = SmartApp.db;
            if (db != null) r.newerTx = Math.max(0, db.txCount() - r.tx);
            r.ok = true;
        } catch (SecureStreams.WrongPasswordException e) {
            r.wrongPassword = true;
            r.message = "كلمة المرور لا تفتح هذه النسخة. إن نسيتها فلن تستطيع استعادتها: أنشئ نسخة جديدة بكلمة مرور تحفظها.";
        } catch (Throwable t) {
            r.message = "تعذر فك النسخة: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        } finally {
            if (parsed != null) BackupFormat.discardSpill(parsed);
            Snapshot.deleteTree(spill);
        }
        return r;
    }

    public static List<File> localBackups(Context c) {
        File[] fs = backupsDir(c).listFiles();
        List<File> l = new ArrayList<>();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().endsWith(".sba")) l.add(f);
        java.util.Collections.sort(l, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        return l;
    }

    static void pruneLocal(Context c, int keep) {
        List<File> l = localBackups(c);
        for (int i = keep; i < l.size(); i++) l.get(i).delete();
        File[] fs = backupsDir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".tmp")) f.delete();
    }

    // ===================================================================================
    // النسخ إلى المكان الذي يختاره المستخدم، مع إعادة القراءة والمطابقة
    // ===================================================================================

    public static final class Delivery {
        public boolean verified;
        public String note = "";
        public long size;
    }

    /**
     * الماك: ينسخ النسخة الجاهزة إلى ملف يختاره المستخدم (مجلد، قرص خارجي، iCloud Drive…) ثم يعيد قراءته ويطابق بصمته.
     */
    /** 11.0: واجهة الهاتف تختار المكان كعنوان (file:// على الكمبيوتر). */
    public static Delivery copyToUri(android.content.Context ctx, File src, android.net.Uri target) throws BackupException { return copyToFile(src, target.file()); }

    public static Delivery copyToUri(android.content.Context ctx, File src, android.net.Uri target, boolean deleteBad) throws BackupException { return copyToFile(src, target.file()); }

    public static String displayName(android.content.Context ctx, android.net.Uri u) { return u.file().getName(); }

    /** ينسخ الملف المختار إلى مجلد مؤقت داخل البرنامج (كما في الهاتف). */
    public static File copyIn(android.content.Context ctx, android.net.Uri src) throws BackupException {
        File f = new File(ctx.getCacheDir(), "restore-input.sba");
        try (InputStream in = ctx.getContentResolver().openInputStream(src); OutputStream o = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            if (f.length() == 0) throw new IOException("الملف المختار فارغ");
        } catch (Throwable t) {
            f.delete();
            throw wrap(Stage.READ, t);
        }
        return f;
    }

    public static Delivery copyToFile(File src, File target) throws BackupException {
        Delivery d = new Delivery();
        d.size = src.length();
        String srcSha;
        try {
            srcSha = sha256(src);
        } catch (Exception e) {
            throw wrap(Stage.READ, e);
        }
        File tmp = new File(target.getParentFile(), "." + target.getName() + ".part");
        try (InputStream in = new FileInputStream(src); FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.flush();
            o.getFD().sync();
        } catch (Throwable t) {
            tmp.delete();
            throw wrap(Stage.WRITE, t);
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            tmp.delete();
            throw wrap(Stage.WRITE, t);
        }
        String gotSha;
        try {
            gotSha = sha256(target);
        } catch (Throwable t) {
            d.note = "حُفظ الملف لكن تعذرت إعادة قراءته للتحقق. النسخة محفوظة أيضًا داخل البرنامج.";
            return d;
        }
        if (target.length() == d.size && srcSha.equalsIgnoreCase(gotSha)) {
            d.verified = true;
            return d;
        }
        target.delete();
        throw new BackupException(Stage.REREAD, "الملف الذي حُفظ غير مطابق للأصل. ربما امتلأت المساحة أو انقطع القرص. لم يُعتمد؛ جرّب مكانًا آخر.", null);
    }

    // ===================================================================================
    // الاستعادة: نسخ الملف ← فك وفحص كامل ← معاينة ← نسخة أمان ← استبدال
    // ===================================================================================

    public static File stagingDir(Context ctx) {
        return new File(ctx.getFilesDir(), "restore_staging");
    }

    public static void clearStaging(Context ctx) {
        File d = stagingDir(ctx);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    public static final class Prepared {
        public BackupFormat.Parsed parsed;
        public BackupFormat.Report report;
        /** 9.0: المكاتب التي تُستعاد (null = كلها)؛ تختارها الواجهة حين تتجاوز النسخة حد النسخة المجانية. */
        public java.util.Set<Long> keep;
        /** 10.13: تبقى تفضيلات المظهر الحالية (لا تُستعاد تفضيلات النسخة). */
        public boolean keepLook;
    }

    /** يفك النسخة ويجهّز صورها (مشفّرة بمفتاح هذا الهاتف) ويفحصها كاملة، دون لمس البيانات الحالية. */
    public static Prepared prepareRestore(Context ctx, File file, char[] password, Progress p) throws BackupException {
        char[] pw = password.clone();
        clearStaging(ctx);
        final File dir = stagingDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) throw new BackupException(Stage.READ, "تعذر تجهيز مجلد مؤقت", null);
        BackupFormat.AttachmentStore store = new BackupFormat.AttachmentStore() {
            @Override public Object store(long id, InputStream data) throws Exception {
                byte[] raw = readLimited(data);
                File f = new File(dir, UUID.randomUUID() + ".evd");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(Crypto.encBytes(raw));
                    o.getFD().sync();
                }
                Arrays.fill(raw, (byte) 0);
                return f;
            }

            @Override public void discard(Object handle) {
                if (handle instanceof File) ((File) handle).delete();
            }
        };
        Prepared out = new Prepared();
        try {
            byte[] head = new byte[8];
            try (InputStream in = new FileInputStream(file)) {
                int n = 0;
                while (n < 8) {
                    int r = in.read(head, n, 8 - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n < 8) throw new SecureStreams.CorruptBackupException("الملف صغير جدًا وليس نسخة احتياطية");
            } catch (Throwable t) {
                throw wrap(Stage.READ, t);
            }
            p.update("جارٍ فك التشفير وقراءة النسخة…");
            try {
                if (SecureStreams.startsWith(head, SecureStreams.MAGIC)) {
                    try (InputStream in = new BufferedInputStream(new FileInputStream(file), 65536)) {
                        out.parsed = BackupFormat.readV5(new SecureStreams.DecryptingInputStream(in, pw), store, dir);
                    }
                } else if (SecureStreams.startsWith(head, BackupFormat.MAGIC_V4)) {
                    if (file.length() > 300L * 1024 * 1024) throw new SecureStreams.CorruptBackupException("ملف الإصدار القديم كبير جدًا");
                    out.parsed = BackupFormat.readV4(Db.readAll(file), pw, store);
                } else {
                    throw new SecureStreams.CorruptBackupException("هذا الملف ليس نسخة احتياطية من المحاسب الذكي (امتداد النسخ: .sba)");
                }
            } catch (Throwable t) {
                clearStaging(ctx);
                throw wrap(Stage.DECRYPT, t);
            }
            p.update("جارٍ فحص القيود والعلاقات والصور…");
            out.report = BackupFormat.validate(out.parsed);
            if (!out.report.ok()) {
                clearStaging(ctx);
                throw new BackupException(Stage.VALIDATE, "النسخة لا تجتاز الفحص، ولن تُستعاد حتى لا تفسد بياناتك:\n• "
                        + join(out.report.errors, "\n• "), null);
            }
            return out;
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    /** نسخة أمان من البيانات الحالية ثم الاستبدال. */
    public static void applyRestore(Context ctx, Db db, Prepared prep, Progress p) throws BackupException {
        p.update("جارٍ حفظ نسخة أمان من بياناتك الحالية…");
        try {
            Snapshot.take(ctx, db);
        } catch (Throwable t) {
            throw wrap(Stage.SNAPSHOT, t);
        }
        p.update("جارٍ استبدال البيانات…");
        try {
            db.restoreKeepLook = prep.keepLook;
            db.importBackup(prep.parsed, prep.keep);
        } catch (Throwable t) {
            for (BackupFormat.StagedAttachment s : prep.parsed.attachments) {
                if (s.handle instanceof File) ((File) s.handle).delete();
            }
            throw wrap(Stage.IMPORT, t);
        } finally {
            db.restoreKeepLook = false;
            clearStaging(ctx);
        }
    }

    // ===================================================================================
    // أدوات
    // ===================================================================================

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return BackupFormat.hex(md.digest());
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " بايت";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f كيلوبايت", bytes / 1024.0);
        return String.format(Locale.US, "%.1f ميغابايت", bytes / (1024.0 * 1024));
    }

    /** ربط ملف بدل نسخه لتوفير المساحة؛ عند عدم الدعم يُنسخ. */
    static void linkOrCopy(File from, File to) throws IOException {
        try {
            java.nio.file.Files.createLink(to.toPath(), from.toPath());
        } catch (Throwable t) {
            Db.copyFile(from, to);
        }
    }
}
