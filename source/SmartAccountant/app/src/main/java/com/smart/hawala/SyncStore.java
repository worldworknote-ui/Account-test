package com.smart.hawala;

import android.content.ContentValues;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 10.0: طبقة المزامنة داخل قاعدة البيانات (مشتركة: الهاتف والماك والويندوز).
 *
 * <p>الفكرة: كل تغيير في جدول مالي يلتقطه مُشغِّل (Trigger) في سجل التغييرات (sync_chg) داخل المعاملة نفسها، فلا يفوت شيء
 * مهما كان مسار الكود. «الختم» يحوّل التغييرات المكتملة إلى حزمة: صورة كل صف كما هو الآن (مفكوك التشفير المحلي)، بساعة HLC.
 * الجهاز الآخر يطبّق الحزمة كاملة في معاملة واحدة أو لا يطبّقها.
 *
 * <p>قواعد الدقة المحاسبية:
 * <ul>
 *   <li>أرقام الصفوف عالمية: كل جهاز يرقّم من نطاقه (بادئته × 2^32)، فالصف نفسه بالرقم نفسه على كل الأجهزة، والأرصدة
 *       تُشتق دائمًا من مجموع سطور القيود (لا أرقام مخزّنة تُدمج).</li>
 *   <li>القيود تُضاف ولا تُعدَّل: الإلغاء قيد عكسي (REVERSAL) يشير إلى الأصل. إلغاء الحركة نفسها من جهازين = قيدان عكسيان
 *       للأصل نفسه؛ الثاني يُتجاهل عند الوصول (لا يُلغى المبلغ مرتين).</li>
 *   <li>تعديل الحركة نفسها من جهازين: الأسبق بالساعة يبقى، والأحدث يُلغيه الجهاز الذي أنشأه بقيد عكسي عادي، ويُسجَّل
 *       تعارضًا للمراجعة (لا دمج تلقائي للمبالغ).</li>
 *   <li>البيانات غير المالية (اسم مكتب، إعداد…) : الأحدث بالساعة يفوز للصف كله.</li>
 *   <li>صف يشير إلى صف حذفه جهاز آخر (مكتب محذوف مثلًا): لا يُطبَّق ويُسجَّل تعارضًا.</li>
 * </ul>
 */
public final class SyncStore {
    private SyncStore() {}

    /** الجداول المتزامنة ومفاتيحها. الترتيب: الأب قبل الابن. */
    static final String[][] TABLES = {
            {"currencies", "code"}, {"users", "id"}, {"offices", "id"}, {"office_currencies", "office_id,code"}, {"tx", "id"}, {"attachments", "id"},
            {"journal_entries", "id"}, {"journal_lines", "id"}, {"opening_balances", "id"}, {"settings", "k"}, {"exchange_rates", "code"},
            {"reconciliations", "id"}, {"reconciliation_lines", "id"}, {"cash_boxes", "id"}, {"cash_moves", "id"}, {"cash_counts", "id"},
            {"cash_clients", "id"}, {"client_moves", "id"},
            // 10.5 (§7): مفاتيح طبيعية (لا تتكرر ولا تضيع بين جهازين)
            {"user_offices", "user_id,office_id"}, {"tx_checks", "uuid"}, {"wa_marks", "uuid"},
            // 10.13: حالة عمل المكتب اليوم (المكتب + اليوم مفتاح طبيعي)
            {"office_work", "office_id,day"},
            // 10.16: سجل العمليات المشترك (إضافة فقط)
            {"audit_feed", "uuid"},
            // 10.17: رسائل المدير إلى المحاسبين
            {"admin_msgs", "uuid"},
            // 10.18: الأمانات والمبالغ المجمّدة
            {"office_holds", "uuid"}};

    static final Set<String> ID_TABLES = new HashSet<>();
    static final Map<String, String[]> KEYS = new HashMap<>();
    static {
        for (String[] t : TABLES) {
            KEYS.put(t[0], t[1].split(","));
            if ("id".equals(t[1])) ID_TABLES.add(t[0]);
        }
    }

    /** إعدادات لا تُزامَن أبدًا (خاصة بالجهاز أو سرية أو داخلية). */
    static boolean localSetting(String k) {
        // 10.12: تفضيل شخصي («u12.tabs») يُعامل كمفتاحه الأصلي: الألوان تنتقل مع المستخدم لأجهزته، والشريط يبقى لكل جهاز
        if (k != null) k = Db.baseKey(k);
        return k == null || k.startsWith("sync") || k.startsWith("mig") || Db.DEVICE_SETTINGS.contains(k)
                || k.equals("crypto_v2_done") || k.startsWith("quota") || k.startsWith("license")
                // 10.5 (AUDIT-004): إعدادات هذا الجهاز وحده: مظهر الكمبيوتر، معرّف عميل Google وسرّه، آخر ما اختاره المستخدم هنا،
                // الإشعارات، المسودات، النسخ التلقائي، الذكاء الاصطناعي، تفضيلات التصدير والصفحة الرئيسية
                || k.startsWith("desk_") || k.startsWith("drive_") || k.startsWith("last_") || k.startsWith("notif_") || k.startsWith("draft_")
                || k.startsWith("auto_backup") || k.startsWith("readable_export") || k.startsWith("ai_") || k.startsWith("scroll_") || k.startsWith("ui_")
                || DEVICE_ONLY.contains(k);
    }

    /** 10.5: مفاتيح محلية إضافية لا تبدأ ببادئة خاصة. */
    static final java.util.Set<String> DEVICE_ONLY = new java.util.HashSet<>(java.util.Arrays.asList(
            "export_format", "export_images", "export_separate", "fee_kind_last", "fee_per_last", "fee_rates_recent", "home_widgets",
            "hw_offices_mode", "recent_offices", "wa_pin_office", "wa_pin_at"));

    private static final String[] DDL = {
            "CREATE TABLE IF NOT EXISTS sync_meta(k TEXT PRIMARY KEY,v TEXT)",
            "CREATE TABLE IF NOT EXISTS sync_flag(id INTEGER PRIMARY KEY,cap INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS sync_chg(seq INTEGER PRIMARY KEY AUTOINCREMENT,tbl TEXT NOT NULL,rid TEXT NOT NULL,op TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sync_clock(tbl TEXT NOT NULL,rid TEXT NOT NULL,hlc TEXT NOT NULL,PRIMARY KEY(tbl,rid))",
            "CREATE TABLE IF NOT EXISTS sync_ids(tbl TEXT PRIMARY KEY,next INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sync_outbox(seq INTEGER PRIMARY KEY,name TEXT NOT NULL,data BLOB NOT NULL,hash TEXT NOT NULL,uploaded INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sync_cursor(dev TEXT PRIMARY KEY,seq INTEGER NOT NULL,hash TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sync_conflicts(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,kind TEXT NOT NULL,tbl TEXT,rid TEXT,"
                    + "detail TEXT,dev TEXT,resolved INTEGER NOT NULL DEFAULT 0,UNIQUE(kind,tbl,rid))",
            // 10.4: بصمة كل صورة (SHA-256 لمحتواها المكشوف) — اسم ملفها المستقل في الخزنة مشتق منها
            "CREATE TABLE IF NOT EXISTS sync_blobs(att_id INTEGER PRIMARY KEY,sha TEXT NOT NULL)"
    };

    static String keyExpr(String alias, String[] key) {
        if (key.length == 1) return alias + "." + key[0];
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < key.length; i++) {
            if (i > 0) b.append("||'|'||");
            b.append(alias).append('.').append(key[i]);
        }
        return b.toString();
    }

    static void ensureSchema(SQLiteDatabase d) {
        for (String s : DDL) d.execSQL(s);
        // 10.4: الصور التي تحتاجها كل حزمة (تُرفع قبلها)
        boolean hasBlobs = false;
        try (Cursor c = d.rawQuery("PRAGMA table_info(sync_outbox)", null)) {
            while (c.moveToNext()) if ("blobs".equals(c.getString(1))) hasBlobs = true;
        }
        if (!hasBlobs) d.execSQL("ALTER TABLE sync_outbox ADD COLUMN blobs TEXT");
        d.execSQL("INSERT OR IGNORE INTO sync_flag(id,cap) VALUES(1,0)");
        String cap = "(SELECT cap FROM sync_flag WHERE id=1)=1";
        for (String[] t : TABLES) {
            String name = t[0];
            String[] key = KEYS.get(name);
            String nw = keyExpr("NEW", key), od = keyExpr("OLD", key);
            if (name.equals("exchange_rates")) {
                // أسعار الإنترنت يجلبها كل جهاز بنفسه؛ يُزامَن السعر اليدوي فقط
                d.execSQL("CREATE TRIGGER IF NOT EXISTS sync1_i_" + name + " AFTER INSERT ON " + name + " WHEN " + cap + " AND NEW.manual_rate IS NOT NULL"
                        + " BEGIN INSERT INTO sync_chg(tbl,rid,op) VALUES('" + name + "'," + nw + ",'U'); END");
                d.execSQL("CREATE TRIGGER IF NOT EXISTS sync1_u_" + name + " AFTER UPDATE OF manual_rate,manual_at ON " + name + " WHEN " + cap
                        + " BEGIN INSERT INTO sync_chg(tbl,rid,op) VALUES('" + name + "'," + nw + ",'U'); END");
                continue;
            }
            String extra = name.equals("settings") ? " AND NEW.k NOT LIKE 'sync%'" : "";
            String extraOld = name.equals("settings") ? " AND OLD.k NOT LIKE 'sync%'" : "";
            d.execSQL("CREATE TRIGGER IF NOT EXISTS sync1_i_" + name + " AFTER INSERT ON " + name + " WHEN " + cap + extra
                    + " BEGIN INSERT INTO sync_chg(tbl,rid,op) VALUES('" + name + "'," + nw + ",'U'); END");
            d.execSQL("CREATE TRIGGER IF NOT EXISTS sync1_u_" + name + " AFTER UPDATE ON " + name + " WHEN " + cap + extra
                    + " BEGIN INSERT INTO sync_chg(tbl,rid,op) SELECT '" + name + "'," + od + ",'D' WHERE " + od + "<>" + nw + ";"
                    + " INSERT INTO sync_chg(tbl,rid,op) VALUES('" + name + "'," + nw + ",'U'); END");
            d.execSQL("CREATE TRIGGER IF NOT EXISTS sync1_d_" + name + " AFTER DELETE ON " + name + " WHEN " + cap + extraOld
                    + " BEGIN INSERT INTO sync_chg(tbl,rid,op) VALUES('" + name + "'," + od + ",'D'); END");
        }
    }

    // ===================================================================================
    // الحالة المحلية
    // ===================================================================================

    static String meta(SQLiteDatabase d, String k) {
        try (Cursor c = d.rawQuery("SELECT v FROM sync_meta WHERE k=?", new String[]{k})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    static void setMeta(SQLiteDatabase d, String k, String v) {
        if (v == null) d.execSQL("DELETE FROM sync_meta WHERE k=?", new Object[]{k});
        else d.execSQL("INSERT OR REPLACE INTO sync_meta(k,v) VALUES(?,?)", new Object[]{k, v});
    }

    static long prefix(SQLiteDatabase d) {
        try {
            String p = meta(d, "prefix");
            return p == null || meta(d, "vault_id") == null ? 0 : Long.parseLong(p);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 10.1: هل هذا الجهاز هو الجهاز الرئيسي (منشئ المزامنة)؟ جهاز خارج المزامنة رئيسي لنفسه.
     * للخزنات المنشأة في 10.0 (بلا «founder»): المنشئ هو صاحب اللقطة الأولى عند الرقم 0.
     */
    static boolean founder(SQLiteDatabase d) {
        if (meta(d, "vault_id") == null) return true;
        String f = meta(d, "founder"), me = meta(d, "device");
        if (f != null) return f.equals(me);
        return "1".equals(meta(d, "snap_no")) && "0".equals(meta(d, "snap_seq"));
    }

    static void capture(SQLiteDatabase d, boolean on) { d.execSQL("UPDATE sync_flag SET cap=" + (on ? 1 : 0) + " WHERE id=1"); }

    /** رقم الصف التالي من نطاق هذا الجهاز. */
    static long nextId(SQLiteDatabase d, String table, long prefix) {
        long next;
        try (Cursor c = d.rawQuery("SELECT next FROM sync_ids WHERE tbl=?", new String[]{table})) {
            if (c.moveToFirst()) next = c.getLong(0);
            else {
                long base = prefix << 32;
                try (Cursor m = d.rawQuery("SELECT MAX(id) FROM " + table + " WHERE id>=" + base + " AND id<" + (base + (1L << 32)), null)) {
                    next = m.moveToFirst() && !m.isNull(0) ? m.getLong(0) + 1 : base + 1;
                }
            }
        }
        if (next >= ((prefix + 1) << 32)) throw new IllegalStateException("نفد نطاق أرقام هذا الجهاز في " + table);
        d.execSQL("INSERT OR REPLACE INTO sync_ids(tbl,next) VALUES(?,?)", new Object[]{table, next + 1});
        return next;
    }

    /** خروج من المزامنة: يتوقف الالتقاط وتُنسى الخزنة والمؤشرات (البيانات تبقى كما هي). */
    static void leave(SQLiteDatabase d, String reason) {
        if (meta(d, "vault_id") == null) return;
        capture(d, false);
        for (String t : new String[]{"sync_meta", "sync_chg", "sync_clock", "sync_ids", "sync_outbox", "sync_cursor", "sync_blobs"}) d.execSQL("DELETE FROM " + t);
        setMeta(d, "left_reason", reason);
    }

    /** «حتى القيد»: آخر رقم في كل نطاق (JSON). */
    static String marks(SQLiteDatabase d) {
        JSONObject o = new JSONObject();
        try (Cursor c = d.rawQuery("SELECT id>>32,MAX(id) FROM journal_entries GROUP BY id>>32", null)) {
            while (c.moveToNext()) o.put("" + c.getLong(0), c.getLong(1));
        } catch (Exception ignored) {
            // يبقى فارغًا
        }
        return o.toString();
    }

    /** شرط SQL: القيد col ضمن ما سبق المطابقة. */
    static String upTo(String col, long cutoff, String marks) {
        if (marks == null || marks.trim().isEmpty() || marks.trim().equals("{}")) return "(" + col + "<=" + cutoff + ")";
        try {
            JSONObject o = new JSONObject(marks);
            StringBuilder b = new StringBuilder("(");
            java.util.Iterator<String> it = o.keys();
            boolean first = true;
            while (it.hasNext()) {
                String g = it.next();
                long base = Long.parseLong(g) << 32, max = o.getLong(g);
                if (!first) b.append(" OR ");
                b.append("(").append(col).append(">=").append(base).append(" AND ").append(col).append("<=").append(max).append(")");
                first = false;
            }
            if (first) return "(" + col + "<=" + cutoff + ")";
            return b.append(")").toString();
        } catch (Exception e) {
            return "(" + col + "<=" + cutoff + ")";
        }
    }

    // ===================================================================================
    // الختم: من سجل التغييرات إلى عمليات
    // ===================================================================================

    static String where(String table, String rid, List<String> args) {
        String[] key = KEYS.get(table);
        if (key.length == 1) {
            args.add(rid);
            return key[0] + "=?";
        }
        String[] parts = rid.split("\\|", -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < key.length; i++) {
            if (i > 0) b.append(" AND ");
            b.append(key[i]).append("=?");
            args.add(i < parts.length ? parts[i] : "");
        }
        return b.toString();
    }

    /** صورة الصف الحالية بنص مكشوف (الحقول الحساسة مفكوكة، والصورة المرفقة مضمّنة)، أو null إن لم يعد موجودًا. */
    static JSONObject image(Db db, SQLiteDatabase d, String table, String rid) throws Exception {
        List<String> args = new ArrayList<>();
        String w = where(table, rid, args);
        List<String> sens = Db.SENSITIVE.get(table);
        try (Cursor c = d.rawQuery("SELECT * FROM " + table + " WHERE " + w, args.toArray(new String[0]))) {
            if (!c.moveToFirst()) return null;
            JSONObject o = new JSONObject();
            for (int i = 0; i < c.getColumnCount(); i++) {
                String col = c.getColumnName(i);
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL: o.put(col, JSONObject.NULL); break;
                    case Cursor.FIELD_TYPE_INTEGER: o.put(col, c.getLong(i)); break;
                    case Cursor.FIELD_TYPE_FLOAT: o.put(col, c.getDouble(i)); break;
                    case Cursor.FIELD_TYPE_BLOB: o.put(col, "b64:" + Base64.getEncoder().encodeToString(c.getBlob(i))); break;
                    default: {
                        String v = c.getString(i);
                        if (sens != null && sens.contains(col)) v = Crypto.dec(v);
                        if (table.equals("settings") && col.equals("v") && Db.ENCRYPTED_SETTINGS.contains(o.optString("k"))) {
                            // سرّ رموز المستخدمين وسرّ بصمة اليوم: يرسلهما الجهاز الرئيسي وحده (فتطابق الرموز على كل الأجهزة)
                            if (!founder(d) || v == null) return null;
                            v = Crypto.decStrict(v);
                        }
                        o.put(col, v);
                    }
                }
            }
            if (table.equals("settings") && localSetting(o.optString("k"))) return null;
            if (table.equals("exchange_rates")) {
                JSONObject m = new JSONObject();
                m.put("code", o.get("code"));
                m.put("manual_rate", o.opt("manual_rate"));
                m.put("manual_at", o.opt("manual_at"));
                return m;
            }
            if (table.equals("attachments")) {
                // 10.4: الصورة لا تُضمَّن في الحزمة؛ تُرفع ملفًا مستقلًا (مرة واحدة) وتحمل الحزمة بصمتها فقط
                File f = db.attachmentFile(o.optString("path"));
                o.remove("path");
                String sha = f.isFile() ? blobSha(d, o.getLong("id"), f) : null;
                if (sha != null) o.put("_sha", sha);
            }
            return o;
        }
    }

    /** 10.4: بصمة محتوى الصورة المكشوف (محفوظة بعد أول حساب)، أو null إن تعذر فكها. */
    static String blobSha(SQLiteDatabase d, long attId, File f) {
        try (Cursor c = d.rawQuery("SELECT sha FROM sync_blobs WHERE att_id=?", new String[]{"" + attId})) {
            if (c.moveToFirst()) return c.getString(0);
        }
        try {
            byte[] plain = Crypto.decBytes(java.nio.file.Files.readAllBytes(f.toPath()));
            String sha = SyncCrypto.hex(SyncCrypto.sha256(plain));
            d.execSQL("INSERT OR REPLACE INTO sync_blobs(att_id,sha) VALUES(?,?)", new Object[]{attId, sha});
            return sha;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * يحوّل التغييرات الملتقطة (معاملات مكتملة فقط: يجري داخل معاملة كتابة) إلى عمليات بساعة واحدة، ويحدّث ساعة كل صف.
     * يرجع null إن لم يكن هناك تغيير.
     */
    static JSONArray seal(Db db, SQLiteDatabase d, String hlc) throws Exception {
        LinkedHashMap<String, String[]> pending = new LinkedHashMap<>();
        long maxSeq = 0;
        try (Cursor c = d.rawQuery("SELECT seq,tbl,rid,op FROM sync_chg ORDER BY seq", null)) {
            while (c.moveToNext()) {
                maxSeq = c.getLong(0);
                String k = c.getString(1) + "\u0000" + c.getString(2);
                String[] cur = pending.get(k);
                if (cur == null) pending.put(k, new String[]{c.getString(1), c.getString(2), c.getString(3)});
                else cur[2] = c.getString(3);
            }
        }
        if (pending.isEmpty()) return null;
        JSONArray ops = new JSONArray();
        for (String[] p : pending.values()) {
            String table = p[0], rid = p[1], op = p[2];
            if (table.equals("settings") && localSetting(rid)) continue;
            JSONObject o = new JSONObject();
            o.put("t", table);
            o.put("k", rid);
            if ("U".equals(op)) {
                JSONObject img = image(db, d, table, rid);
                if (img == null) {
                    // حُذف بعد تعديله في المعاملة نفسها (أو إعداد لا يُزامَن)
                    if (table.equals("settings") || table.equals("exchange_rates")) continue;
                    o.put("o", "D");
                } else {
                    o.put("o", "U");
                    o.put("r", img);
                }
            } else o.put("o", "D");
            ops.put(o);
            d.execSQL("INSERT OR REPLACE INTO sync_clock(tbl,rid,hlc) VALUES(?,?,?)", new Object[]{table, rid, hlc});
        }
        d.execSQL("DELETE FROM sync_chg WHERE seq<=" + maxSeq);
        return ops.length() == 0 ? null : ops;
    }

    // ===================================================================================
    // التطبيق: حزمة من جهاز آخر في معاملة واحدة
    // ===================================================================================

    /** نتيجة تطبيق حزمة. */
    static final class Applied {
        int rows, skippedOlder, duplicates, conflicts, dupTx;
        /** 10.16: تنبيهات للمراجعة ليست تعارض حركات (تعديل لم يُطبَّق، تغيّر صلاحيات…). */
        int notices;
        /** 10.2: حركات جديدة وصلت من جهاز آخر (للإشعارات). */
        final List<Long> newTx = new ArrayList<>();
        /** حركات أنشأها هذا الجهاز وخسرت تعارض تعديل: يلغيها الجهاز نفسه بعد التطبيق. */
        final List<Long> loseMine = new ArrayList<>();
    }

    static Set<String> columns(SQLiteDatabase d, String table, Map<String, Set<String>> cache) {
        Set<String> s = cache.get(table);
        if (s == null) {
            s = Db.columnsOf(d, table);
            cache.put(table, s);
        }
        return s;
    }

    static void conflict(SQLiteDatabase d, String kind, String table, String rid, String detail, String dev) {
        d.execSQL("INSERT OR IGNORE INTO sync_conflicts(created_at,kind,tbl,rid,detail,dev) VALUES(?,?,?,?,?,?)",
                new Object[]{Clock.now(), kind, table, rid, detail, dev});
    }

    /** 10.16: أسماء أنواع التعارضات والتنبيهات (واحدة للهاتف والكمبيوتر). */
    public static String kindName(String k) {
        switch (k == null ? "" : k) {
            case "EDIT_TWICE": return "حركة عُدّلت من جهازين";
            case "MISSING_PARENT": return "حركة لمكتب أو أصل محذوف";
            case "LATE": return "حركة بتاريخ داخل فترة مقفلة";
            case "UNKNOWN_TABLE": return "بيانات من إصدار أحدث";
            case "IMAGE_MISSING": return "صورة لم تصل";
            case "DUPLICATE": return "حركتان قد تكونان مكررتين";
            case "EDIT_LOST": return "تعديل من جهاز آخر لم يُطبَّق";
            case "ROLE_CHANGED": return "تغيّر في المستخدمين والصلاحيات";
            case "DEVICE_JOINED": return "جهاز جديد دخل المزامنة";
            case "DEVICE_GONE": return "جهاز خرج من المزامنة";
            case "CLOCK": return "ساعة جهاز غير مضبوطة";
            default: return k;
        }
    }

    /** 10.16: هل هذا النوع إشعار للعلم (لا يحتاج قرارًا في حركة)؟ */
    public static boolean isNotice(String k) {
        return "ROLE_CHANGED".equals(k) || "DEVICE_JOINED".equals(k) || "DEVICE_GONE".equals(k) || "CLOCK".equals(k);
    }

    /** 10.16: جداول يُنبَّه المستخدم إن خسر فيها تعديلٌ قاعدةَ «الأحدث يفوز» (بيانات يعدّلها الناس يدويًا ولا تُعوَّض بقيد). */
    private static final Map<String, String> EDIT_LOST_TABLES = new HashMap<>();
    static {
        EDIT_LOST_TABLES.put("offices", "المكتب");
        EDIT_LOST_TABLES.put("users", "المستخدم");
        EDIT_LOST_TABLES.put("currencies", "العملة");
        EDIT_LOST_TABLES.put("cash_boxes", "الصندوق");
        EDIT_LOST_TABLES.put("cash_clients", "العميل");
    }

    private static String rowLabel(SQLiteDatabase d, String table, String rid) {
        String what = EDIT_LOST_TABLES.get(table);
        if (table.equals("offices") || table.equals("users") || table.equals("cash_boxes") || table.equals("cash_clients")) {
            try (Cursor c = d.rawQuery("SELECT name FROM " + table + " WHERE id=?", new String[]{rid})) {
                if (c.moveToFirst() && c.getString(0) != null) {
                    List<String> sens = Db.SENSITIVE.get(table);
                    String n = sens != null && sens.contains("name") ? Crypto.dec(c.getString(0)) : c.getString(0);
                    if (n != null && !n.isEmpty()) return what + " «" + n + "»";
                }
            } catch (Exception ignored) {
                // الاسم للعرض فقط
            }
        }
        return what + " " + rid;
    }

    private static String[] userState(SQLiteDatabase d, String rid, Set<String> cols) {
        String perms = cols.contains("perms") ? "COALESCE(perms,'')" : "''";
        try (Cursor c = d.rawQuery("SELECT name,role,active," + perms + " FROM users WHERE id=?", new String[]{rid})) {
            return c.moveToFirst() ? new String[]{c.getString(0), Model.nz(c.getString(1)), "" + c.getInt(2), c.getString(3)} : null;
        }
    }

    /**
     * يطبّق عمليات حزمة (يُستدعى داخل معاملة، والالتقاط متوقف). myPrefix: لمعرفة الحركات التي أنشأها هذا الجهاز.
     */
    static Applied apply(Db db, SQLiteDatabase d, JSONArray ops, String hlc, String fromDev, long myPrefix) throws Exception {
        Applied res = new Applied();
        Map<String, Set<String>> cols = new HashMap<>();
        Map<String, Set<Long>> touched = new HashMap<>();
        Set<Long> insertedTx = new HashSet<>();
        Map<String, String[]> userChanges = new java.util.LinkedHashMap<>();
        Set<String> baseline = fkViolations(d);
        d.execSQL("PRAGMA defer_foreign_keys=ON");
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.getJSONObject(i);
            String table = op.getString("t"), rid = op.getString("k"), kind = op.getString("o");
            if (!KEYS.containsKey(table)) {
                conflict(d, "UNKNOWN_TABLE", table, rid, "جدول من إصدار أحدث", fromDev);
                continue;
            }
            if (table.equals("settings") && localSetting(rid)) continue;
            boolean secret = table.equals("settings") && Db.ENCRYPTED_SETTINGS.contains(rid);
            // الأسرار من الجهاز الرئيسي وحده، وتُطبَّق دائمًا (لا «الأحدث يفوز» فيها)
            if (secret && (!fromDev.equals(meta(d, "founder")) || "D".equals(kind))) continue;
            // الأحدث يفوز: تغيير محلي أحدث من هذا لا يُكتب فوقه
            String local = null;
            try (Cursor c = d.rawQuery("SELECT hlc FROM sync_clock WHERE tbl=? AND rid=?", new String[]{table, rid})) {
                if (c.moveToFirst()) local = c.getString(0);
            }
            if (!secret && local != null && local.compareTo(hlc) > 0) {
                res.skippedOlder++;
                // 10.16: لا يضيع تعديل بصمت: بقي ما على هذا الجهاز لأنه أحدث، ويُعلَم المستخدم ليراجع
                if (EDIT_LOST_TABLES.containsKey(table)) {
                    conflict(d, "EDIT_LOST", table, rid + "@" + hlc, ("D".equals(kind) ? "حذف " : "تعديل على ") + rowLabel(d, table, rid)
                            + " من جهاز آخر لم يُطبَّق لأن عليه تعديلًا أحدث؛ بقي التعديل الأحدث. راجع البيانات وتأكد أنها كما تريد.", fromDev);
                    res.notices++;
                }
                continue;
            }
            List<String> args = new ArrayList<>();
            String w = where(table, rid, args);
            String[] wa = args.toArray(new String[0]);
            if ("D".equals(kind)) {
                if (table.equals("attachments")) {
                    try (Cursor c = d.rawQuery("SELECT path FROM attachments WHERE " + w, wa)) {
                        if (c.moveToFirst() && c.getString(0) != null) db.attachmentFile(c.getString(0)).delete();
                    }
                }
                d.delete(table, w, wa);
            } else {
                JSONObject r = op.getJSONObject("r");
                if (table.equals("journal_entries") && "REVERSAL".equals(r.optString("kind")) && !r.isNull("reverses_id")) {
                    // إلغاء الحركة نفسها من جهازين: قيد عكسي واحد يكفي
                    boolean exists;
                    try (Cursor c = d.rawQuery("SELECT 1 FROM journal_entries WHERE id=?", new String[]{rid})) {
                        exists = c.moveToFirst();
                    }
                    if (!exists) {
                        boolean dup;
                        try (Cursor c = d.rawQuery("SELECT 1 FROM journal_entries WHERE kind='REVERSAL' AND reverses_id=? AND COALESCE(status,'POSTED')='POSTED'",
                                new String[]{"" + r.getLong("reverses_id")})) {
                            dup = c.moveToFirst();
                        }
                        if (dup) {
                            res.duplicates++;
                            d.execSQL("INSERT OR REPLACE INTO sync_clock(tbl,rid,hlc) VALUES(?,?,?)", new Object[]{table, rid, hlc});
                            continue;
                        }
                    }
                }
                if (table.equals("journal_lines")) {
                    // سطور قيد عكسي مكرر تجاهلناه: لا قيد لها هنا
                    boolean parent;
                    try (Cursor c = d.rawQuery("SELECT 1 FROM journal_entries WHERE id=?", new String[]{"" + r.optLong("entry_id")})) {
                        parent = c.moveToFirst();
                    }
                    boolean self;
                    try (Cursor c = d.rawQuery("SELECT 1 FROM journal_lines WHERE id=?", new String[]{rid})) {
                        self = c.moveToFirst();
                    }
                    if (!parent && !self && skippedEntry(ops, r.optLong("entry_id"))) {
                        d.execSQL("INSERT OR REPLACE INTO sync_clock(tbl,rid,hlc) VALUES(?,?,?)", new Object[]{table, rid, hlc});
                        continue;
                    }
                }
                ContentValues v = values(db, d, table, r, cols);
                if (v == null) continue;
                String[] userBefore = table.equals("users") ? userState(d, rid, columns(d, "users", cols)) : null;
                boolean userRow = table.equals("users");
                if (table.equals("exchange_rates")) {
                    if (d.update(table, v, w, wa) == 0) {
                        v.put("usd_rate", "0");
                        v.put("updated_at", 0L);
                        d.insertOrThrow(table, null, v);
                    }
                } else if (d.update(table, v, w, wa) == 0) {
                    d.insertOrThrow(table, null, v);
                    if (table.equals("tx")) insertedTx.add(r.getLong("id"));
                }
                if (userRow && !userChanges.containsKey(rid)) userChanges.put(rid, userBefore);
                if (userRow) db.invalidateNicks();
                if (ID_TABLES.contains(table)) {
                    Set<Long> s = touched.get(table);
                    if (s == null) {
                        s = new HashSet<>();
                        touched.put(table, s);
                    }
                    s.add(Long.parseLong(rid));
                }
            }
            d.execSQL("INSERT OR REPLACE INTO sync_clock(tbl,rid,hlc) VALUES(?,?,?)", new Object[]{table, rid, hlc});
            res.rows++;
        }
        return applyTail(db, d, res, baseline, insertedTx, userChanges, hlc, fromDev, myPrefix);
    }

    private static Applied applyTail(Db db, SQLiteDatabase d, Applied res, Set<String> baseline, Set<Long> insertedTx, Map<String, String[]> userChanges,
            String hlc, String fromDev, long myPrefix) throws Exception {
        // 10.16: تغيّر دور مستخدم أو صلاحياته أو إيقافه/إضافة مدير من جهاز آخر: يُعلَم به (كان يمر بصمت)
        Map<String, Set<String>> ucols = new HashMap<>();
        for (Map.Entry<String, String[]> e : userChanges.entrySet()) {
            String[] was = e.getValue(), now = userState(d, e.getKey(), columns(d, "users", ucols));
            if (now == null) continue;
            String name = Model.nz(now[0]);
            List<String> what = new ArrayList<>();
            if (was == null) {
                if ("ADMIN".equals(now[1])) what.add("أُضيف مدير جديد");
            } else {
                if (!was[1].equals(now[1])) what.add("تغيّر دوره من " + Auth.shortLabel(was[1]) + " إلى " + Auth.shortLabel(now[1]));
                if (!was[2].equals(now[2])) what.add("1".equals(now[2]) ? "أُعيد تفعيله" : "أُوقف");
                if (!Model.nz(was[3]).equals(Model.nz(now[3]))) what.add("تغيّرت صلاحياته");
            }
            if (what.isEmpty()) continue;
            conflict(d, "ROLE_CHANGED", "users", e.getKey() + "@" + hlc, "المستخدم «" + name + "»: " + String.join("، ", what) + " (من جهاز آخر).", fromDev);
            res.notices++;
        }
        // صفوف تشير إلى ما حذفه جهاز آخر (أو وصلت لأصل محذوف هنا): تُرفع مع قيودها ويُسجَّل تعارض؛
        // المخالفات القديمة الموجودة قبل الحزمة لا تُمس
        for (int round = 0; round < 8; round++) {
            List<String[]> bad = new ArrayList<>();
            try (Cursor c = d.rawQuery("PRAGMA foreign_key_check", null)) {
                while (c.moveToNext()) {
                    String key = c.getString(0) + ":" + c.getLong(1);
                    if (!baseline.contains(key)) bad.add(new String[]{c.getString(0), "" + c.getLong(1), c.getString(2)});
                }
            }
            if (bad.isEmpty()) break;
            for (String[] b : bad) {
                if (b[0].equals("tx")) {
                    d.execSQL("DELETE FROM journal_lines WHERE entry_id IN (SELECT id FROM journal_entries WHERE tx_id=" + b[1] + ")");
                    d.execSQL("DELETE FROM journal_entries WHERE tx_id=" + b[1]);
                    d.execSQL("DELETE FROM attachments WHERE tx_id=" + b[1]);
                }
                d.delete(b[0], "rowid=?", new String[]{b[1]});
                conflict(d, "MISSING_PARENT", b[0], b[1], "يشير إلى " + b[2] + " محذوف", fromDev);
                res.conflicts++;
            }
        }
        // تعديل الحركة نفسها من جهازين: الأسبق بالساعة يبقى
        try (Cursor c = d.rawQuery("SELECT replaces_id FROM tx WHERE replaces_id IS NOT NULL AND status IN ('ACTIVE','PENDING') GROUP BY replaces_id HAVING COUNT(*)>1", null)) {
            List<Long> groups = new ArrayList<>();
            while (c.moveToNext()) groups.add(c.getLong(0));
            for (long g : groups) {
                List<Object[]> cand = new ArrayList<>();
                try (Cursor q = d.rawQuery("SELECT t.id,COALESCE(k.hlc,'') FROM tx t LEFT JOIN sync_clock k ON k.tbl='tx' AND k.rid=CAST(t.id AS TEXT)"
                        + " WHERE t.replaces_id=" + g + " AND t.status IN ('ACTIVE','PENDING')", null)) {
                    while (q.moveToNext()) cand.add(new Object[]{q.getLong(0), q.getString(1)});
                }
                cand.sort((x, y) -> {
                    int k = ((String) x[1]).compareTo((String) y[1]);
                    return k != 0 ? k : Long.compare((Long) x[0], (Long) y[0]);
                });
                for (int j = 1; j < cand.size(); j++) {
                    long loser = (Long) cand.get(j)[0];
                    conflict(d, "EDIT_TWICE", "tx", "" + loser, "عُدّلت الحركة " + g + " من جهازين؛ بقي التعديل الأسبق (" + cand.get(0)[0] + ") وأُلغي هذا", fromDev);
                    res.conflicts++;
                    if (myPrefix > 0 && (loser >> 32) == myPrefix) res.loseMine.add(loser);
                }
            }
        }
        res.newTx.addAll(insertedTx);
        // 10.2: حركة وصلت تشبه حركة سجّلها جهاز آخر (المكتب والمبلغ والعملة والاسم نفسها خلال يوم ونصف): تكرار محتمل للمراجعة
        for (long id : insertedTx) {
            Model.Tx t = Db.txOn(d, id);
            if (t == null || !(t.isActive() || t.isPending())) continue;
            for (Model.Tx o : db.similarTx(d, t, 36 * 3600000L)) {
                if ((o.id >> 32) == (id >> 32) && (id >> 32) != 0) continue;
                long x = Math.min(id, o.id), y = Math.max(id, o.id);
                if (dupOk(d, x, y)) continue;
                conflict(d, "DUPLICATE", "tx", x + "|" + y, "حركتان متشابهتان من جهازين: " + t.person + " — " + t.amount + " " + t.currency, fromDev);
                res.conflicts++;
                res.dupTx++;
            }
        }
        // حركات وصلت بتاريخ داخل فترة مقفلة: تُقبل (وقعت فعلًا) وتُعلَّم للمراجعة
        long lock = 0;
        try (Cursor c = d.rawQuery("SELECT v FROM settings WHERE k='lock_before'", null)) {
            if (c.moveToFirst() && c.getString(0) != null) lock = Long.parseLong(c.getString(0).trim());
        } catch (Exception ignored) {
            // بلا إقفال
        }
        if (lock > 0) for (long id : insertedTx) {
            try (Cursor c = d.rawQuery("SELECT created_at FROM tx WHERE id=" + id, null)) {
                if (c.moveToFirst() && c.getLong(0) < lock) {
                    conflict(d, "LATE", "tx", "" + id, "حركة بتاريخ داخل فترة مقفلة وصلت من جهاز آخر", fromDev);
                    res.conflicts++;
                }
            }
        }
        return res;
    }

    /** 10.2: «ليستا مكررتين» قرار يُزامَن (إعداد) فلا يُسأل عنه على الأجهزة الأخرى. */
    static boolean dupOk(SQLiteDatabase d, long x, long y) {
        try (Cursor c = d.rawQuery("SELECT 1 FROM settings WHERE k=?", new String[]{"dupok_" + x + "_" + y})) {
            return c.moveToFirst();
        }
    }

    static Set<String> fkViolations(SQLiteDatabase d) {
        Set<String> s = new HashSet<>();
        try (Cursor c = d.rawQuery("PRAGMA foreign_key_check", null)) {
            while (c.moveToNext()) s.add(c.getString(0) + ":" + c.getLong(1));
        }
        return s;
    }

    private static boolean skippedEntry(JSONArray ops, long entryId) {
        // القيد العكسي المكرر يأتي في الحزمة نفسها قبل سطوره
        for (int i = 0; i < ops.length(); i++) {
            JSONObject o = ops.optJSONObject(i);
            if (o != null && "journal_entries".equals(o.optString("t")) && ("" + entryId).equals(o.optString("k"))) return true;
        }
        return false;
    }

    /** صورة الصف ← قيم محلية (تشفير الحقول الحساسة بمفتاح هذا الجهاز، وكتابة الصورة المرفقة في مجلده). */
    static ContentValues values(Db db, SQLiteDatabase d, String table, JSONObject r, Map<String, Set<String>> cache) throws Exception {
        Set<String> local = columns(d, table, cache);
        List<String> sens = Db.SENSITIVE.get(table);
        ContentValues v = new ContentValues();
        java.util.Iterator<String> it = r.keys();
        while (it.hasNext()) {
            String col = it.next();
            if (col.startsWith("_") || !local.contains(col.toLowerCase(Locale.US))) continue;
            Object val = r.get(col);
            if (val == JSONObject.NULL) v.putNull(col);
            else if (val instanceof Number) {
                if (val instanceof Double || val instanceof Float || val instanceof java.math.BigDecimal) {
                    double dv = ((Number) val).doubleValue();
                    if (dv == Math.rint(dv) && Math.abs(dv) < 9.0E15) v.put(col, (long) dv);
                    else v.put(col, dv);
                } else v.put(col, ((Number) val).longValue());
            } else if (val instanceof Boolean) v.put(col, ((Boolean) val) ? 1L : 0L);
            else {
                String sv = String.valueOf(val);
                if (sv.startsWith("b64:")) v.put(col, Base64.getDecoder().decode(sv.substring(4)));
                else if (table.equals("settings") && col.equals("v") && Db.ENCRYPTED_SETTINGS.contains(r.optString("k"))) v.put(col, Crypto.enc(sv));
                else v.put(col, sens != null && sens.contains(col) ? Crypto.enc(sv) : sv);
            }
        }
        if (table.equals("attachments")) {
            String path = null;
            try (Cursor c = d.rawQuery("SELECT path FROM attachments WHERE id=?", new String[]{"" + r.getLong("id")})) {
                if (c.moveToFirst()) path = c.getString(0);
            }
            if (path != null && !new File(path).isFile() && db.attachmentFile(path).isFile()) path = db.attachmentFile(path).getAbsolutePath();
            if (path == null || !new File(path).isFile()) {
                if (!r.has("_file")) return null;
                File dir = db.evidenceDir();
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد الصور");
                File f = new File(dir, UUID.randomUUID() + ".evd");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(Crypto.encBytes(Base64.getDecoder().decode(r.getString("_file"))));
                    o.getFD().sync();
                }
                path = f.getAbsolutePath();
            }
            v.put("path", path);
        }
        return v;
    }

    // ===================================================================================
    // بصمة الدفتر: تتطابق على كل الأجهزة التي استلمت الحزم نفسها
    // ===================================================================================

    static String digest(SQLiteDatabase d) {
        StringBuilder b = new StringBuilder();
        String[] qs = {
                "SELECT office_id,currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' GROUP BY office_id,currency ORDER BY office_id,currency",
                "SELECT box_id,currency,SUM(CASE WHEN status='ACTIVE' THEN dir*amount_minor ELSE 0 END),COUNT(*) FROM cash_moves GROUP BY box_id,currency ORDER BY box_id,currency",
                "SELECT client_id,currency,SUM(CASE WHEN status='ACTIVE' THEN dir*amount_minor ELSE 0 END),COUNT(*) FROM client_moves GROUP BY client_id,currency ORDER BY client_id,currency",
                "SELECT status,COUNT(*) FROM tx GROUP BY status ORDER BY status",
                "SELECT COUNT(*) FROM offices"};
        for (String q : qs) {
            try (Cursor c = d.rawQuery(q, null)) {
                while (c.moveToNext()) {
                    for (int i = 0; i < c.getColumnCount(); i++) b.append(c.isNull(i) ? "" : c.getString(i)).append(',');
                    b.append(';');
                }
            }
            b.append('|');
        }
        return SyncCrypto.hex(SyncCrypto.sha256(b.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32);
    }

    static Map<String, Long> cursors(SQLiteDatabase d) {
        Map<String, Long> m = new TreeMap<>();
        try (Cursor c = d.rawQuery("SELECT dev,seq FROM sync_cursor", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getLong(1));
        }
        return m;
    }

    static List<String[]> openConflicts(SQLiteDatabase d) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT id,created_at,kind,tbl,rid,detail,dev FROM sync_conflicts WHERE resolved=0 AND kind<>'DUPLICATE' ORDER BY created_at DESC LIMIT 200", null)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)});
        }
        return out;
    }

    /** 10.18: كل التنبيهات بتاريخها (المفتوح وما عُلّم «تمت») لسجل الإشعارات؛ العمود الأخير resolved. */
    static List<String[]> allConflicts(SQLiteDatabase d, int limit) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT id,created_at,kind,tbl,rid,detail,dev,resolved FROM sync_conflicts ORDER BY created_at DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)});
        }
        return out;
    }

    static final List<String> SYNC_TABLE_NAMES = new ArrayList<>();
    static {
        for (String[] t : TABLES) SYNC_TABLE_NAMES.add(t[0]);
    }

    static boolean isSynced(String t) { return SYNC_TABLE_NAMES.contains(t); }

    static String join(List<String> l) { return String.join(",", l); }

    static List<String> list(String s) { return s == null || s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(","))); }
}
