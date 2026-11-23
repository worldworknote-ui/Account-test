package com.smart.hawala;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * محتوى النسخة الاحتياطية: كتابة، قراءة، وفحص كامل قبل الاستعادة.
 *
 * <p>داخل الغلاف المشفّر يوجد ملف ZIP:
 * <pre>
 *  manifest.json                 معلومات النسخة والأعداد المتوقعة
 *  tables/&lt;name&gt;.ndjson        كل صف في سطر (نصوص مفكوكة، تُشفَّر من جديد بمفتاح الهاتف عند الاستعادة)
 *  attachments/&lt;id&gt;.bin        صور الوصولات مفكوكة (تصلح للاستعادة على هاتف آخر)
 *  attachments/index.ndjson      وصف الصور مع بصمة SHA-256 لكل صورة
 *  summary.json                  يُكتب في النهاية فقط: وجوده دليل على اكتمال الكتابة
 * </pre>
 *
 * <p>يدعم أيضًا قراءة نسخ الإصدار 4.0 (SMARTBK4).
 * لا يستخدم أي واجهة خاصة بأندرويد حتى يمكن اختباره على الكمبيوتر.
 */
public final class BackupFormat {

    public static final String APP_ID = "smart-accountant-hawala";
    public static final int FORMAT = 5;
    public static final byte[] MAGIC_V4 = "SMARTBK4".getBytes(StandardCharsets.US_ASCII);

    /** ترتيب الجداول يراعي العلاقات بينها (الأب قبل الابن) عند الاستعادة. */
    public static final String[] TABLES = {
            "currencies", "offices", "office_currencies", "tx", "journal_entries", "journal_lines",
            "opening_balances", "exchange_rates", "settings", "audit_logs", "users", "reconciliations", "reconciliation_lines",
            // 9.0: صندوق المكتب (الإصدارات الأقدم تتجاهله برسالة ظاهرة)
            // 10.0: الصناديق قبل حركاتها
            "cash_boxes",
            "cash_moves",
            // 9.0.1: سجل الجرد
            "cash_counts",
            // 9.0.1: حسابات العملاء (العميل قبل حركاته)
            "cash_clients", "client_moves",
            // 10.5: مكاتب المحاسبين، والتحقق من الحركات، وآخر رسالة واتساب نُقلت لكل مكتب
            "user_offices", "tx_checks", "wa_marks",
            // 10.13: حالات عمل المكاتب
            "office_work",
            // 10.16: سجل العمليات المشترك بين الأجهزة
            "audit_feed",
            // 10.17: رسائل المدير
            "admin_msgs",
            // 10.18: الأمانات والمبالغ المجمّدة
            "office_holds"
    };
    public static final String[] REQUIRED = {"currencies", "offices", "tx", "journal_entries", "journal_lines"};
    /** جداول لها عمود id يجب ألا يتكرر. */
    private static final String[] ID_TABLES = {"offices", "tx", "journal_entries", "journal_lines", "opening_balances", "audit_logs", "users",
            "reconciliations", "reconciliation_lines", "cash_moves", "cash_counts", "cash_clients", "client_moves", "cash_boxes"};

    private static final long MAX_ATTACHMENT_BYTES = 40L * 1024 * 1024;
    private static final int MAX_TEXT_ENTRY = 8 * 1024 * 1024;

    private BackupFormat() {}

    // ===================================================================================
    // الواجهات
    // ===================================================================================

    /** مصدر البيانات عند الإنشاء (قاعدة البيانات في التطبيق، أو بيانات وهمية في الاختبارات). */
    public interface Source {
        Map<String, Long> counts() throws Exception;
        void rows(String table, RowSink sink) throws Exception;
        List<AttachmentRef> attachments() throws Exception;
        /** يرجع بايتات الصورة مفكوكة، أو null إن كانت تالفة/مفقودة (تُحسب كمتخطاة). */
        byte[] attachmentData(AttachmentRef ref);
        /** ملاحظات تظهر في ملخص النسخة (مثل حقول تعذر فكها). */
        List<String> warnings();

        /** 8.0: عدد الحقول التي تعذر فكها فكُتب مكانها نص بديل. نسخة فيها حقول تالفة لا تُحذف بعدها نسخ أقدم. */
        default int unreadable() { return 0; }
    }

    public interface RowSink {
        void row(JSONObject row) throws Exception;
    }

    /** مكان حفظ الصور مؤقتًا أثناء قراءة النسخة (مجلد تجهيز في التطبيق، أو ذاكرة في الاختبار). */
    public interface AttachmentStore {
        /** يحفظ الصورة ويرجع مرجعًا لها. يجب قراءة المجرى حتى نهايته. */
        Object store(long id, InputStream data) throws Exception;
        void discard(Object handle);
    }

    public static final class AttachmentRef {
        public long id;
        public long txId;
        public long createdAt;
        public String mime;
        public Object handle;
    }

    public static final class StagedAttachment {
        public long id;
        public long txId;
        public long createdAt;
        public String mime;
        public long size;
        public String sha256;
        public Object handle;
    }

    public static final class WriteResult {
        public final Map<String, Long> rows = new LinkedHashMap<>();
        public int attachments;
        /** حقول نصية تعذر فكها في الهاتف عند الإنشاء (كُتب مكانها نص بديل). */
        public int unreadable;
        public int skippedAttachments;
        public final List<String> warnings = new ArrayList<>();
    }

    /** نتيجة قراءة نسخة: الجداول والصور المجهزة، دون أي تعديل على بيانات التطبيق. */
    public static final class Parsed {
        public int format;
        public JSONObject manifest = new JSONObject();
        public JSONObject summary;
        public final Map<String, List<JSONObject>> tables = new LinkedHashMap<>();
        public final List<StagedAttachment> attachments = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public int schema() {
            return manifest.optInt("schema", format >= 5 ? 5 : 4);
        }

        public List<JSONObject> table(String name) {
            List<JSONObject> t = tables.get(name);
            return t == null ? new ArrayList<JSONObject>() : t;
        }
    }

    /** تقرير الفحص الكامل قبل الاستعادة. */
    public static final class Report {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int offices, currencies, txActive, txCancelled, attachments, entries, cashMoves, clients, clientMoves;
        public long createdAt;
        public String appVersion = "";
        public int format;

        public boolean ok() { return errors.isEmpty(); }
    }

    // ===================================================================================
    // الكتابة
    // ===================================================================================

    /**
     * يكتب محتوى النسخة إلى المجرى (عادة مجرى التشفير). لا يغلق المجرى: يستدعي finish فقط،
     * والمستدعي يغلق مجرى التشفير لكتابة الجزء الأخير.
     */
    public static WriteResult write(Source src, OutputStream out, String appVersion, int schema) throws Exception {
        WriteResult res = new WriteResult();
        ZipOutputStream zip = new ZipOutputStream(out);
        long now = Clock.now();

        JSONObject counts = new JSONObject();
        for (Map.Entry<String, Long> e : src.counts().entrySet()) counts.put(e.getKey(), e.getValue().longValue());
        JSONObject manifest = new JSONObject();
        manifest.put("format", FORMAT);
        manifest.put("app", APP_ID);
        manifest.put("appName", "المحاسب الذكي لمكاتب الحوالات");
        manifest.put("appVersion", appVersion);
        manifest.put("schema", schema);
        manifest.put("createdAt", now);
        manifest.put("counts", counts);
        putText(zip, "manifest.json", manifest.toString(1));

        for (final String table : TABLES) {
            zip.putNextEntry(new ZipEntry("tables/" + table + ".ndjson"));
            final long[] n = {0};
            final OutputStream z = zip;
            src.rows(table, new RowSink() {
                @Override public void row(JSONObject row) throws Exception {
                    z.write(row.toString().getBytes(StandardCharsets.UTF_8));
                    z.write('\n');
                    n[0]++;
                }
            });
            zip.closeEntry();
            res.rows.put(table, n[0]);
        }

        StringBuilder index = new StringBuilder();
        for (AttachmentRef ref : src.attachments()) {
            byte[] data;
            try {
                data = src.attachmentData(ref);
            } catch (Throwable t) {
                data = null;
            }
            if (data == null || data.length == 0) {
                res.skippedAttachments++;
                continue;
            }
            zip.putNextEntry(new ZipEntry("attachments/" + ref.id + ".bin"));
            zip.write(data);
            zip.closeEntry();
            JSONObject meta = new JSONObject();
            meta.put("id", ref.id);
            meta.put("tx_id", ref.txId);
            meta.put("mime", ref.mime == null ? "image/jpeg" : ref.mime);
            meta.put("created_at", ref.createdAt);
            meta.put("size", data.length);
            meta.put("sha256", sha256Hex(data));
            index.append(meta.toString()).append('\n');
            res.attachments++;
            Arrays.fill(data, (byte) 0);
        }
        putText(zip, "attachments/index.ndjson", index.toString());

        res.warnings.addAll(src.warnings());
        res.unreadable = src.unreadable();
        JSONObject summary = new JSONObject();
        JSONObject rows = new JSONObject();
        for (Map.Entry<String, Long> e : res.rows.entrySet()) rows.put(e.getKey(), e.getValue().longValue());
        summary.put("rows", rows);
        summary.put("attachments", res.attachments);
        summary.put("skippedAttachments", res.skippedAttachments);
        summary.put("unreadableFields", res.unreadable);
        summary.put("warnings", new JSONArray(res.warnings));
        summary.put("completedAt", Clock.now());
        putText(zip, "summary.json", summary.toString(1));
        zip.finish();
        zip.flush();
        return res;
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ===================================================================================
    // قراءة الصيغة 5
    // ===================================================================================

    public static Parsed readV5(InputStream decrypted, AttachmentStore store) throws Exception {
        return readV5(decrypted, store, null);
    }

    /**
     * spillDir: مجلد خاص بالتطبيق تُكتب فيه الجداول الكبيرة مشفّرة بمفتاح عابر بدل إبقائها في الذاكرة
     * (null = كلها في الذاكرة، للنسخ الصغيرة والاختبارات). الذاكرة تثبت مهما كبر الدفتر.
     */
    public static Parsed readV5(InputStream decrypted, AttachmentStore store, File spillDir) throws Exception {
        Parsed p = new Parsed();
        p.format = 5;
        ZipInputStream zin = new ZipInputStream(decrypted);
        Map<Long, StagedAttachment> staged = new LinkedHashMap<>();
        List<JSONObject> index = null;
        boolean manifestSeen = false;
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals("manifest.json")) {
                    p.manifest = new JSONObject(readText(zin));
                    manifestSeen = true;
                } else if (name.startsWith("tables/") && name.endsWith(".ndjson")) {
                    String table = name.substring(7, name.length() - 7);
                    if (!Arrays.asList(TABLES).contains(table)) {
                        p.warnings.add("تم تجاهل جدول غير معروف داخل النسخة: " + table);
                        continue;
                    }
                    p.tables.put(table, readLines(zin, spillDir));
                } else if (name.equals("attachments/index.ndjson")) {
                    index = readLines(zin, null);
                } else if (name.startsWith("attachments/") && name.endsWith(".bin")) {
                    long id;
                    try {
                        id = Long.parseLong(name.substring(12, name.length() - 4));
                    } catch (NumberFormatException nfe) {
                        p.warnings.add("تم تجاهل ملف غير معروف: " + name);
                        continue;
                    }
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    CountingDigestStream cs = new CountingDigestStream(zin, md, MAX_ATTACHMENT_BYTES);
                    Object handle = store.store(id, cs);
                    drain(cs);
                    StagedAttachment old = staged.get(id);
                    if (old != null) store.discard(old.handle);
                    StagedAttachment s = new StagedAttachment();
                    s.id = id;
                    s.size = cs.count;
                    s.sha256 = hex(md.digest());
                    s.handle = handle;
                    staged.put(id, s);
                } else if (name.equals("summary.json")) {
                    p.summary = new JSONObject(readText(zin));
                } else if (!e.isDirectory()) {
                    p.warnings.add("تم تجاهل ملف غير معروف داخل النسخة: " + name);
                }
            }
        } catch (Exception ex) {
            for (StagedAttachment s : staged.values()) store.discard(s.handle);
            throw ex;
        }
        if (!manifestSeen) {
            discardAll(staged, store);
            throw new SecureStreams.CorruptBackupException("النسخة لا تحتوي على ملف التعريف؛ ليست نسخة صالحة");
        }
        if (!APP_ID.equals(p.manifest.optString("app"))) {
            discardAll(staged, store);
            throw new SecureStreams.CorruptBackupException("هذه النسخة ليست من تطبيق المحاسب الذكي");
        }
        if (p.summary == null) {
            discardAll(staged, store);
            throw new SecureStreams.CorruptBackupException("النسخة غير مكتملة: لم تُكتب نهايتها (ربما انقطعت عملية الإنشاء)");
        }
        if (index == null) index = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (JSONObject meta : index) {
            long id = meta.optLong("id", -1);
            StagedAttachment s = staged.get(id);
            if (s == null) {
                discardAll(staged, store);
                throw new SecureStreams.CorruptBackupException("صورة مفقودة داخل النسخة (رقم " + id + ")");
            }
            if (meta.optLong("size", -1) != s.size || !meta.optString("sha256").equalsIgnoreCase(s.sha256)) {
                discardAll(staged, store);
                throw new SecureStreams.CorruptBackupException("صورة تالفة داخل النسخة (رقم " + id + ")");
            }
            s.txId = meta.optLong("tx_id", -1);
            s.createdAt = meta.optLong("created_at", 0);
            s.mime = meta.optString("mime", "image/jpeg");
            used.add(id);
            p.attachments.add(s);
        }
        for (StagedAttachment s : staged.values()) {
            if (!used.contains(s.id)) store.discard(s.handle);
        }
        JSONObject rows = p.summary.optJSONObject("rows");
        if (rows != null) {
            Iterator<String> it = rows.keys();
            while (it.hasNext()) {
                String t = it.next();
                long expected = rows.optLong(t, -1);
                long got = p.table(t).size();
                if (expected != got) {
                    for (StagedAttachment s : p.attachments) store.discard(s.handle);
                    throw new SecureStreams.CorruptBackupException("عدد صفوف الجدول " + t + " لا يطابق المسجل في النسخة");
                }
            }
        }
        if (p.summary.optInt("attachments", p.attachments.size()) != p.attachments.size()) {
            for (StagedAttachment s : p.attachments) store.discard(s.handle);
            throw new SecureStreams.CorruptBackupException("عدد الصور لا يطابق المسجل في النسخة");
        }
        JSONArray w = p.summary.optJSONArray("warnings");
        if (w != null) for (int i = 0; i < w.length(); i++) p.warnings.add(w.optString(i));
        int skipped = p.summary.optInt("skippedAttachments", 0);
        if (skipped > 0) p.warnings.add("عند إنشاء هذه النسخة تم تخطي " + skipped + " صورة تالفة أو مفقودة في الهاتف الأصلي");
        return p;
    }

    private static void discardAll(Map<Long, StagedAttachment> staged, AttachmentStore store) {
        for (StagedAttachment s : staged.values()) store.discard(s.handle);
    }

    // ===================================================================================
    // قراءة الصيغة 4 (نسخ الإصدار 4.0 و 4.0.1)
    // ===================================================================================

    public static Parsed readV4(byte[] file, char[] password, AttachmentStore store) throws Exception {
        JSONObject root;
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(file));
            byte[] m = new byte[MAGIC_V4.length];
            d.readFully(m);
            if (!Arrays.equals(m, MAGIC_V4)) throw new SecureStreams.CorruptBackupException("هذا الملف ليس نسخة احتياطية صحيحة");
            int fmt = d.readInt();
            if (fmt != 1) throw new SecureStreams.CorruptBackupException("إصدار النسخة غير مدعوم");
            int iter = d.readInt();
            if (iter < 100000 || iter > 2000000) throw new SecureStreams.CorruptBackupException("معلمات حماية غير صالحة");
            int sl = d.readInt();
            if (sl != 16) throw new SecureStreams.CorruptBackupException("بنية النسخة غير صالحة");
            byte[] salt = new byte[sl];
            d.readFully(salt);
            int il = d.readInt();
            if (il != 12) throw new SecureStreams.CorruptBackupException("بنية النسخة غير صالحة");
            byte[] iv = new byte[il];
            d.readFully(iv);
            int len = d.readInt();
            if (len < 16 || len > file.length) throw new SecureStreams.CorruptBackupException("حجم النسخة غير صالح");
            byte[] enc = new byte[len];
            d.readFully(enc);
            SecretKeySpec key = SecureStreams.deriveKey(password, salt, iter);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(MAGIC_V4);
            byte[] raw;
            try {
                raw = c.doFinal(enc);
            } catch (AEADBadTagException bad) {
                throw new SecureStreams.CorruptBackupException("كلمة المرور غير صحيحة أو أن الملف تالف");
            }
            root = new JSONObject(new String(gunzip(raw), StandardCharsets.UTF_8));
        } catch (EOFException | NegativeArraySizeException e) {
            throw new SecureStreams.CorruptBackupException("الملف ناقص أو تالف");
        } catch (GeneralSecurityException e) {
            throw new SecureStreams.CorruptBackupException("تعذر فك النسخة: " + e.getMessage(), e);
        }
        if (!root.has("tables") || !root.has("schema")) throw new SecureStreams.CorruptBackupException("محتوى النسخة غير مكتمل");

        Parsed p = new Parsed();
        p.format = 4;
        p.manifest.put("format", 4);
        p.manifest.put("app", APP_ID);
        p.manifest.put("appVersion", root.optString("appVersion", "4.0"));
        p.manifest.put("schema", root.optInt("schema", 4));
        p.manifest.put("createdAt", root.optLong("createdAt", 0));
        JSONArray tabs = root.getJSONArray("tables");
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tb = tabs.getJSONObject(i);
            String name = tb.getString("name");
            if (!Arrays.asList(TABLES).contains(name)) continue; // مثل backup_history: خاص بالهاتف القديم
            JSONArray rows = tb.getJSONArray("rows");
            List<JSONObject> list = new ArrayList<>(rows.length());
            for (int j = 0; j < rows.length(); j++) list.add(rows.getJSONObject(j));
            p.tables.put(name, list);
        }
        JSONArray at = root.optJSONArray("attachments");
        try {
            if (at != null) {
                for (int i = 0; i < at.length(); i++) {
                    JSONObject r = at.getJSONObject(i);
                    byte[] data = Base64.getMimeDecoder().decode(r.getString("data"));
                    StagedAttachment s = new StagedAttachment();
                    s.id = r.getLong("id");
                    s.txId = r.getLong("tx_id");
                    s.mime = r.optString("mime", "image/jpeg");
                    s.createdAt = r.optLong("created_at", 0);
                    s.size = data.length;
                    s.sha256 = sha256Hex(data);
                    s.handle = store.store(s.id, new ByteArrayInputStream(data));
                    p.attachments.add(s);
                }
            }
        } catch (Exception e) {
            for (StagedAttachment s : p.attachments) store.discard(s.handle);
            throw e;
        }
        int skipped = root.optInt("skippedAttachments", 0);
        if (skipped > 0) p.warnings.add("عند إنشاء هذه النسخة تم تخطي " + skipped + " صورة تالفة أو مفقودة في الهاتف الأصلي");
        return p;
    }

    // ===================================================================================
    // الفحص الكامل
    // ===================================================================================

    public static Report validate(Parsed p) {
        Report r = new Report();
        r.format = p.format;
        r.createdAt = p.manifest.optLong("createdAt", 0);
        r.appVersion = p.manifest.optString("appVersion", "");
        r.warnings.addAll(p.warnings);

        for (String t : REQUIRED) {
            if (!p.tables.containsKey(t)) r.errors.add("الجدول «" + t + "» مفقود من النسخة");
        }
        if (!r.errors.isEmpty()) return r;

        for (String t : ID_TABLES) {
            Set<Long> seen = new HashSet<>();
            for (JSONObject row : p.table(t)) {
                Long id = lng(row, "id");
                if (id == null) {
                    r.errors.add("صف بلا رقم تعريف في الجدول " + t);
                    break;
                }
                if (!seen.add(id)) {
                    r.errors.add("رقم تعريف مكرر (" + id + ") في الجدول " + t);
                    break;
                }
            }
        }

        Set<String> currencies = new HashSet<>();
        for (JSONObject row : p.table("currencies")) {
            String code = str(row, "code");
            if (code == null || code.trim().isEmpty()) r.errors.add("عملة بلا رمز");
            else if (!currencies.add(code)) r.errors.add("رمز عملة مكرر: " + code);
        }

        Set<Long> offices = ids(p.table("offices"));
        r.offices = offices.size();
        r.currencies = currencies.size();

        Set<String> ocKeys = new HashSet<>();
        for (JSONObject row : p.table("office_currencies")) {
            Long o = lng(row, "office_id");
            String c = str(row, "code");
            if (o == null || !offices.contains(o)) {
                r.errors.add("عملة مكتب تشير إلى مكتب غير موجود (" + o + ")");
                break;
            }
            if (c == null || !currencies.contains(c)) {
                r.errors.add("عملة مكتب غير معرّفة: " + c);
                break;
            }
            if (!ocKeys.add(o + "|" + c)) {
                r.errors.add("تكرار عملة " + c + " للمكتب " + o);
                break;
            }
        }

        Set<Long> txs = new HashSet<>();
        Set<String> uuids = new HashSet<>();
        Set<String> unknownCur = new HashSet<>();
        for (JSONObject row : p.table("tx")) {
            Long id = lng(row, "id");
            Long o = lng(row, "office_id");
            if (id != null) txs.add(id);
            if (o == null || !offices.contains(o)) {
                r.errors.add("حركة رقم " + id + " تشير إلى مكتب غير موجود");
                break;
            }
            String uuid = str(row, "uuid");
            if (uuid == null || uuid.isEmpty()) {
                r.errors.add("حركة رقم " + id + " بلا مرجع");
                break;
            }
            if (!uuids.add(uuid)) {
                r.errors.add("مرجع حركة مكرر: " + uuid);
                break;
            }
            Long amount = lng(row, "amount_minor");
            if (amount == null || amount < 0) {
                r.errors.add("مبلغ غير صالح في الحركة رقم " + id);
                break;
            }
            String cur = str(row, "currency");
            if (cur != null && !currencies.contains(cur)) unknownCur.add(cur);
            if ("CANCELLED".equals(str(row, "status"))) r.txCancelled++;
            else r.txActive++;
        }
        if (!unknownCur.isEmpty()) r.warnings.add("عملات مستخدمة في حركات وغير موجودة في قائمة العملات: " + unknownCur);

        Set<Long> entries = new HashSet<>();
        for (JSONObject row : p.table("journal_entries")) {
            Long id = lng(row, "id");
            entries.add(id);
            Long tx = lng(row, "tx_id");
            if (tx != null && !txs.contains(tx)) {
                r.errors.add("قيد رقم " + id + " يشير إلى حركة غير موجودة");
                break;
            }
        }
        r.entries = entries.size();

        Map<String, Long> balance = new HashMap<>();
        Map<Long, Integer> linesPerEntry = new HashMap<>();
        for (JSONObject row : p.table("journal_lines")) {
            Long e = lng(row, "entry_id");
            if (e == null || !entries.contains(e)) {
                r.errors.add("سطر قيد يشير إلى قيد غير موجود (" + e + ")");
                break;
            }
            Long o = lng(row, "office_id");
            if (o != null && !offices.contains(o)) {
                r.errors.add("سطر قيد يشير إلى مكتب غير موجود (" + o + ")");
                break;
            }
            Long debit = lng(row, "debit_minor");
            Long credit = lng(row, "credit_minor");
            if (debit == null || credit == null || debit < 0 || credit < 0) {
                r.errors.add("قيمة غير صالحة في سطر القيد رقم " + lng(row, "id"));
                break;
            }
            String key = e + "|" + str(row, "currency");
            Long b = balance.get(key);
            balance.put(key, (b == null ? 0 : b) + debit - credit);
            Integer n = linesPerEntry.get(e);
            linesPerEntry.put(e, n == null ? 1 : n + 1);
        }
        for (Map.Entry<String, Long> b : balance.entrySet()) {
            if (b.getValue() != 0) {
                r.errors.add("قيد غير متوازن (رقم القيد|العملة = " + b.getKey() + ")");
                break;
            }
        }
        int emptyEntries = 0;
        for (Long e : entries) if (!linesPerEntry.containsKey(e)) emptyEntries++;
        if (emptyEntries > 0) r.warnings.add(emptyEntries + " قيد بلا أسطر (لا يؤثر على الأرصدة)");

        for (JSONObject row : p.table("opening_balances")) {
            Long o = lng(row, "office_id");
            if (o != null && !offices.contains(o)) {
                r.errors.add("رصيد سابق يشير إلى مكتب غير موجود (" + o + ")");
                break;
            }
        }

        Set<Long> recons = new HashSet<>();
        for (JSONObject row : p.table("reconciliations")) {
            Long o = lng(row, "office_id");
            if (o == null || !offices.contains(o)) {
                r.errors.add("مطابقة رصيد تشير إلى مكتب غير موجود (" + o + ")");
                break;
            }
            Long id = lng(row, "id");
            if (id != null) recons.add(id);
        }
        for (JSONObject row : p.table("reconciliation_lines")) {
            Long rc = lng(row, "recon_id");
            if (rc == null || !recons.contains(rc)) {
                r.errors.add("سطر مطابقة يشير إلى مطابقة غير موجودة (" + rc + ")");
                break;
            }
        }

        // 9.0: صندوق المكتب: كل حركة مرتبطة تشير إلى مكتب وحركة موجودين، والمبالغ موجبة
        Set<String> cashUuids = new HashSet<>();
        for (JSONObject row : p.table("cash_moves")) {
            Long id = lng(row, "id");
            Long amount = lng(row, "amount_minor"), fee = lng(row, "fee_minor"), dir = lng(row, "dir");
            if (amount == null || amount <= 0 || (fee != null && fee < 0) || dir == null || (dir != 1 && dir != -1)) {
                r.errors.add("حركة صندوق غير صالحة (رقم " + id + ")");
                break;
            }
            Long o = lng(row, "office_id");
            if (o != null && !offices.contains(o)) {
                r.errors.add("حركة صندوق تشير إلى مكتب غير موجود (" + o + ")");
                break;
            }
            Long tx = lng(row, "tx_id");
            if (tx != null && !txs.contains(tx)) {
                r.errors.add("حركة صندوق تشير إلى حركة مكتب غير موجودة (" + tx + ")");
                break;
            }
            String u = str(row, "uuid");
            if (u == null || u.isEmpty() || !cashUuids.add(u)) {
                r.errors.add("حركة صندوق بلا مرجع أو بمرجع مكرر (رقم " + id + ")");
                break;
            }
            String cur = str(row, "currency");
            if (cur == null || !currencies.contains(cur)) r.warnings.add("حركة صندوق بعملة غير معرّفة: " + cur);
            r.cashMoves++;
        }

        // 9.0.1: حسابات العملاء: كل حركة لعميل موجود وبمبلغ موجب
        Set<Long> clients = new HashSet<>();
        for (JSONObject row : p.table("cash_clients")) clients.add(lng(row, "id"));
        for (JSONObject row : p.table("client_moves")) {
            Long c = lng(row, "client_id"), amount = lng(row, "amount_minor"), dir = lng(row, "dir");
            if (c == null || !clients.contains(c)) {
                r.errors.add("حركة على حساب عميل غير موجود (" + c + ")");
                break;
            }
            if (amount == null || amount <= 0 || dir == null || (dir != 1 && dir != -1)) {
                r.errors.add("حركة حساب عميل غير صالحة (رقم " + lng(row, "id") + ")");
                break;
            }
            r.clientMoves++;
        }
        r.clients = clients.size();

        Set<Long> attIds = new HashSet<>();
        for (StagedAttachment s : p.attachments) {
            if (!attIds.add(s.id)) {
                r.errors.add("صورة مكررة الرقم: " + s.id);
                break;
            }
            if (!txs.contains(s.txId)) {
                r.errors.add("صورة رقم " + s.id + " تشير إلى حركة غير موجودة");
                break;
            }
            if (s.size <= 0) {
                r.errors.add("صورة فارغة رقم " + s.id);
                break;
            }
        }
        r.attachments = p.attachments.size();
        return r;
    }

    // ===================================================================================
    // 9.0: مكاتب النسخة (لاختيار ما يُستعاد منها في النسخة المجانية)
    // ===================================================================================

    /** مكتب في النسخة: اسمه وعدد حركاته وأرصدته من القيود. */
    public static final class OfficeInfo {
        public long id;
        public String name = "";
        public boolean active = true;
        public long txCount, lastTx;
        public final Map<String, Long> balances = new java.util.TreeMap<>();
    }

    public static List<OfficeInfo> officeSummaries(Parsed p) {
        Map<Long, OfficeInfo> m = new java.util.LinkedHashMap<>();
        for (JSONObject row : p.table("offices")) {
            Long id = lng(row, "id");
            if (id == null) continue;
            OfficeInfo o = new OfficeInfo();
            o.id = id;
            String n = str(row, "name");
            o.name = n == null ? "" : n;
            Long a = lng(row, "active");
            o.active = a == null || a == 1;
            m.put(id, o);
        }
        for (JSONObject row : p.table("tx")) {
            OfficeInfo o = m.get(lng(row, "office_id"));
            if (o == null) continue;
            o.txCount++;
            Long t = lng(row, "created_at");
            if (t != null && t > o.lastTx) o.lastTx = t;
        }
        for (JSONObject row : p.table("journal_lines")) {
            if (!"OFFICE".equals(str(row, "account"))) continue;
            OfficeInfo o = m.get(lng(row, "office_id"));
            if (o == null) continue;
            Long dr = lng(row, "debit_minor"), cr = lng(row, "credit_minor");
            String c = str(row, "currency");
            long v = (dr == null ? 0 : dr) - (cr == null ? 0 : cr);
            Long b = o.balances.get(c);
            o.balances.put(c, (b == null ? 0 : b) + v);
        }
        return new ArrayList<>(m.values());
    }

    // ===================================================================================
    // أدوات
    // ===================================================================================

    public static Long lng(JSONObject row, String k) {
        if (!row.has(k) || row.isNull(k)) return null;
        Object v = row.opt(k);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String str(JSONObject row, String k) {
        if (!row.has(k) || row.isNull(k)) return null;
        return String.valueOf(row.opt(k));
    }

    private static Set<Long> ids(List<JSONObject> rows) {
        Set<Long> s = new HashSet<>();
        for (JSONObject r : rows) {
            Long id = lng(r, "id");
            if (id != null) s.add(id);
        }
        return s;
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > MAX_TEXT_ENTRY) throw new SecureStreams.CorruptBackupException("ملف داخل النسخة أكبر من المتوقع");
        }
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    /** حد الجدول في الذاكرة (بالأحرف) قبل نقله إلى ملف مؤقت مشفّر. */
    public static long spillChars = 4L * 1024 * 1024;

    private static List<JSONObject> readLines(InputStream in, File spillDir) throws IOException, JSONException {
        BufferedReader br = new BufferedReader(new InputStreamReader(new NonClosing(in), StandardCharsets.UTF_8));
        List<String> pending = new ArrayList<>();
        long chars = 0;
        SpilledRows spill = null;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            if (spill != null) {
                new JSONObject(line);   // يُرفض السطر الفاسد هنا كما في الذاكرة
                spill.append(line);
                continue;
            }
            pending.add(line);
            chars += line.length();
            if (spillDir != null && chars > spillChars) {
                spill = new SpilledRows(spillDir);
                for (String l : pending) {
                    new JSONObject(l);
                    spill.append(l);
                }
                pending = null;
            }
        }
        if (spill != null) {
            spill.finish();
            return spill;
        }
        List<JSONObject> rows = new ArrayList<>(pending.size());
        for (String l : pending) rows.add(new JSONObject(l));
        return rows;
    }

    /**
     * صفوف جدول كبير في ملف مؤقت مشفّر (AES-CTR بمفتاح عابر في الذاكرة فقط، فلا يُقرأ الملف بعد انتهاء العملية)،
     * تُقرأ بالتتابع عند كل مرور. للقراءة فقط: get(i) يمر من البداية (للاختبارات)، والتعديل غير مدعوم.
     */
    public static final class SpilledRows extends java.util.AbstractList<JSONObject> {
        private final File file;
        private final javax.crypto.SecretKey key;
        private final byte[] iv = new byte[16];
        private java.io.Writer w;
        private int size;

        SpilledRows(File dir) throws IOException {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد مؤقت للقراءة");
            file = new File(dir, "rows-" + UUID.randomUUID() + ".tmp");
            try {
                javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance("AES");
                kg.init(256);
                key = kg.generateKey();
                new java.security.SecureRandom().nextBytes(iv);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.IvParameterSpec(iv));
                w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new javax.crypto.CipherOutputStream(new java.io.FileOutputStream(file), c), StandardCharsets.UTF_8), 65536);
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        void append(String line) throws IOException {
            w.write(line);
            w.write('\n');
            size++;
        }

        void finish() throws IOException {
            w.close();
            w = null;
        }

        public File file() { return file; }

        @Override public int size() { return size; }

        @Override public JSONObject get(int i) {
            if (i < 0 || i >= size) throw new IndexOutOfBoundsException("" + i);
            int k = 0;
            for (JSONObject o : this) if (k++ == i) return o;
            throw new IndexOutOfBoundsException("" + i);
        }

        @Override public java.util.Iterator<JSONObject> iterator() {
            final BufferedReader r;
            try {
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
                c.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.IvParameterSpec(iv));
                r = new BufferedReader(new InputStreamReader(new javax.crypto.CipherInputStream(new java.io.FileInputStream(file), c), StandardCharsets.UTF_8), 65536);
            } catch (Exception e) {
                throw new IllegalStateException("تعذرت قراءة الملف المؤقت للنسخة: " + e.getMessage(), e);
            }
            return new java.util.Iterator<JSONObject>() {
                int read;
                String next = advance();

                private String advance() {
                    try {
                        String l;
                        while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) return l;
                        r.close();
                        return null;
                    } catch (IOException e) {
                        throw new IllegalStateException("تعذرت قراءة الملف المؤقت للنسخة: " + e.getMessage(), e);
                    }
                }

                @Override public boolean hasNext() { return next != null && read < size; }

                @Override public JSONObject next() {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    String l = next;
                    read++;
                    next = read < size ? advance() : null;
                    try {
                        return new JSONObject(l);
                    } catch (JSONException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        }
    }

    /** يحذف ملفات الجداول المؤقتة (بعد الاستعادة أو الفحص). */
    public static void discardSpill(Parsed p) {
        for (List<JSONObject> t : p.tables.values()) if (t instanceof SpilledRows) ((SpilledRows) t).file().delete();
    }

    private static void drain(InputStream in) throws IOException {
        byte[] b = new byte[8192];
        while (in.read(b) > 0) { /* حتى النهاية */ }
    }

    private static byte[] gunzip(byte[] b) throws IOException {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(b));
             ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] x = new byte[8192];
            int n;
            while ((n = z.read(x)) > 0) o.write(x, 0, n);
            return o.toByteArray();
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    /** يمنع BufferedReader من إغلاق ZipInputStream. */
    private static final class NonClosing extends FilterInputStream {
        NonClosing(InputStream in) { super(in); }
        @Override public void close() { /* لا شيء */ }
    }

    /** يحسب البصمة والحجم أثناء القراءة، ويرفض الملفات الأكبر من الحد. */
    private static final class CountingDigestStream extends FilterInputStream {
        final MessageDigest md;
        final long max;
        long count;

        CountingDigestStream(InputStream in, MessageDigest md, long max) {
            super(in);
            this.md = md;
            this.max = max;
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                md.update((byte) b);
                count++;
                check();
            }
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                md.update(b, off, n);
                count += n;
                check();
            }
            return n;
        }

        private void check() throws IOException {
            if (count > max) throw new SecureStreams.CorruptBackupException("صورة داخل النسخة أكبر من الحد المسموح");
        }

        @Override public void close() { /* لا يغلق ZipInputStream */ }
    }
}
