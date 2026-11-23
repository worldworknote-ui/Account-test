package com.smart.hawala;

import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 10.0: محرك المزامنة بين الأجهزة (مشترك: الهاتف والماك والويندوز) عبر مجلد مشترك لا يرى إلا ملفات مشفّرة.
 *
 * <p>الملفات في المجلد:
 * <ul>
 *   <li>رأس الخزنة (نص مكشوف بلا أي بيانات): معرّفها، والملح، ومفتاح الخزنة مغلّفًا بكلمة السر وبرمز الاسترداد.</li>
 *   <li>m-… لكل جهاز ملف عضوية (مشفّر): اسمه ومنصته وبادئة أرقامه وآخر حزمة له وما طبّقه من غيره وبصمة دفتره.</li>
 *   <li>p-… حزم كل جهاز بالتسلسل، كل حزمة تحمل بصمة السابقة (سلسلة): حذف حزمة أو تبديل ترتيبها أو تغيير بايت = يُكتشف.</li>
 *   <li>s-… لقطة كاملة مشفّرة يبدأ منها الجهاز المنضم، مع المؤشرات التي تمثلها.</li>
 * </ul>
 *
 * <p>كل جهاز يكتب ملفاته فقط (لا يكتب جهازان في ملف واحد أبدًا). الحزمة تُطبَّق كاملة أو لا تُطبَّق، ولا تُطبَّق قبل ما تعتمد عليه
 * من حزم الأجهزة الأخرى (ترتيب سببي)، وتطبيقها مرتين مستحيل (المؤشر يتقدم في المعاملة نفسها).
 */
public final class SyncEngine {
    public static final String HEADER = "smart-accountant-sync-vault.json";
    /**
     * صيغة الخزنة. 2 (10.4): الصور ملفات مستقلة (a-…) لا تتكرر، واللقطة بلا صور، والملفات القديمة تُحذف تلقائيًا بعد أن
     * تصل إلى كل الأجهزة. الأجهزة بإصدار أقدم تُطلب منها الترقية (لا تقرأ خزنة صيغتها أحدث).
     * 3 (10.5): الإزالة لا تحذف حزمًا لم تصل (حد upTo يحترمه الجميع)، ودور الجهاز الرئيسي ينتقل، وجداول التحقق والمكاتب والعلامة.
     * جهاز 10.4 كان سيحذف حزم جهاز يزيله ولا يعرف الجداول الجديدة، فيتوقف عن المزامنة ويُطلب منه التحديث؛ بياناته وتغييراته
     * غير المرسلة تبقى عليه وتُرسل بعد التحديث.
     */
    /**
     * 4 (10.16): جدول سجل العمليات المشترك (audit_feed). جهاز 10.15 لا يعرفه فكان سيتجاوز أسطره؛ يُطلب منه التحديث.
     */
    /** 5 (10.17): جدول رسائل المدير admin_msgs وعمود users.nick. */
    /* 6 (10.18): جدول الأمانات office_holds. */
    public static final int FORMAT = 6;
    /** لقطة جديدة بعد هذا العدد من حزم هذا الجهاز. */
    static final int SNAPSHOT_EVERY = 150;
    /** 10.4: جهاز لم يظهر منذ هذه المدة يُنبَّه عنه (يمنع حذف ما لم يستلمه). */
    public static final long STALE_MS = 30L * 24 * 3600 * 1000;
    /** 10.4: صورة بلا مرجع تُحذف بعد هذه المدة (هامش أمان لحزم في الطريق). */
    static final long BLOB_GRACE_MS = 7L * 24 * 3600 * 1000;
    /** 10.4: حزمة صورتها لم تصل بعد هذه المدة تُطبَّق دونها (مع تنبيه) كي لا تتوقف المزامنة. */
    static final long BLOB_WAIT_MS = 3L * 24 * 3600 * 1000;

    /**
     * 10.16: قفل واحد لكل عمليات الخزنة على هذا الجهاز (الجولة، النقل، الحذف، كلمة السر، الإزالة…). كل استدعاء ينشئ كائنًا
     * جديدًا، فقفل الكائن لم يكن يمنع جولتين معًا (الزر اليدوي مع التلقائية). العملية الثانية تنتظر الأولى ثم تعمل.
     */
    static final java.util.concurrent.locks.ReentrantLock VAULT = new java.util.concurrent.locks.ReentrantLock();

    /** هل تعمل عملية على الخزنة الآن (للواجهة: «المزامنة جارية»). */
    public static boolean busy() { return VAULT.isLocked(); }

    final Db db;
    final SyncFolder folder;

    public SyncEngine(Db db, SyncFolder folder) {
        this.db = db;
        this.folder = folder;
    }

    // ===================================================================================
    // الحالة
    // ===================================================================================

    public static boolean enabled(Db db) { return SyncStore.meta(db.getReadableDatabase(), "vault_id") != null; }

    public static final class Status {
        public boolean enabled;
        public String vaultId = "", device = "", deviceName = "", folder = "", lastError = "", digestAlert = "", leftReason = "", lastErrorWith = "";
        public long lastErrorAt;
        public long lastSync, mySeq, pending, unsent;
        public int conflicts;
        /** 10.16: المفتوح للمراجعة غير الحركات المكررة (تعارضات وتنبيهات)، وتحذير الساعة إن وُجد. */
        public int review;
        public String clockWarn = "";
        public final List<JSONObject> members = new ArrayList<>();
        public final Map<String, JSONObject> peers = new java.util.HashMap<>();
        public String note = "";
        /** 10.14: نُقلت المزامنة من المكان الحالي إلى آخر (نص للمستخدم)، وهل هي مزامنة جديدة (يلزم الانضمام) أم الخزنة نفسها (يكفي الدخول بالمكان الجديد). */
        public String movedTo = "", movedTarget = "";
        public boolean movedFresh;
        public boolean founder;
        public String founderDev = "";
        /** 10.4: أجهزة لم تظهر منذ 30 يومًا (تمنع حذف الملفات القديمة حتى تعود أو تُزال). */
        public final List<JSONObject> stale = new ArrayList<>();
        /** 10.4: آخر تنظيف. */
        public String lastClean = "";
        public boolean paused;

        /** اسم الجهاز الرئيسي للواجهة. */
        public String founderName() {
            if (founder) return deviceName;
            for (JSONObject m : members) if (m.optString("dev").equals(founderDev)) return m.optString("name");
            return "الجهاز الرئيسي";
        }
    }

    public static Status status(Db db) {
        SQLiteDatabase d = db.getReadableDatabase();
        Status s = new Status();
        s.enabled = SyncStore.meta(d, "vault_id") != null;
        s.leftReason = nz(SyncStore.meta(d, "left_reason"));
        if (!s.enabled) return s;
        s.vaultId = nz(SyncStore.meta(d, "vault_id"));
        s.device = nz(SyncStore.meta(d, "device"));
        s.deviceName = nz(SyncStore.meta(d, "device_name"));
        s.folder = nz(SyncStore.meta(d, "folder"));
        s.lastError = nz(SyncStore.meta(d, "last_error"));
        s.lastErrorWith = nz(SyncStore.meta(d, "last_error_with"));
        s.lastErrorAt = lng(SyncStore.meta(d, "last_error_at"));
        s.digestAlert = nz(SyncStore.meta(d, "digest_alert"));
        s.movedTo = nz(SyncStore.meta(d, "moved_to"));
        s.movedFresh = "1".equals(SyncStore.meta(d, "moved_fresh"));
        s.movedTarget = nz(SyncStore.meta(d, "moved_target"));
        s.lastSync = lng(SyncStore.meta(d, "last_sync"));
        s.mySeq = lng(SyncStore.meta(d, "my_seq"));
        try (Cursor c = d.rawQuery("SELECT tbl,rid FROM sync_chg", null)) {
            while (c.moveToNext()) if (!("settings".equals(c.getString(0)) && SyncStore.localSetting(c.getString(1)))) s.pending++;
        }
        try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM sync_outbox WHERE uploaded=0", null)) {
            if (c.moveToFirst()) s.unsent = c.getLong(0);
        }
        try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM sync_conflicts WHERE resolved=0", null)) {
            if (c.moveToFirst()) s.conflicts = (int) c.getLong(0);
        }
        try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM sync_conflicts WHERE resolved=0 AND kind<>'DUPLICATE'", null)) {
            if (c.moveToFirst()) s.review = (int) c.getLong(0);
        }
        s.clockWarn = nz(SyncStore.meta(d, "clock_warn"));
        try {
            JSONArray m = new JSONArray(nz(SyncStore.meta(d, "members_cache")).isEmpty() ? "[]" : SyncStore.meta(d, "members_cache"));
            for (int i = 0; i < m.length(); i++) s.members.add(m.getJSONObject(i));
        } catch (Exception ignored) {
            // لا قائمة بعد
        }
        try {
            String pj = SyncStore.meta(d, "peers");
            JSONArray pa = new JSONArray(pj == null ? "[]" : pj);
            for (int i = 0; i < pa.length(); i++) s.peers.put(pa.getJSONObject(i).optString("dev"), pa.getJSONObject(i));
        } catch (Exception ignored) {
            // لا تشخيص بعد
        }
        s.note = nz(SyncStore.meta(d, "folder_note"));
        s.founder = SyncStore.founder(d);
        s.founderDev = nz(SyncStore.meta(d, "founder"));
        long now = Clock.now();
        for (JSONObject m : s.members) if (!m.optString("dev").equals(s.device) && m.optLong("at") > 0 && now - m.optLong("at") > STALE_MS) s.stale.add(m);
        s.lastClean = nz(SyncStore.meta(d, "gc_note"));
        s.paused = paused(db);
        return s;
    }

    static String nz(String s) { return s == null ? "" : s; }

    /** 10.4: المزامنة التلقائية موقوفة مؤقتًا على هذا الجهاز (اليدوية تعمل). */
    public static boolean paused(Db db) { return "1".equals(db.setting("sync_paused", "0")); }

    /** 10.4: كل كم ثانية تجري المزامنة التلقائية والتطبيق مفتوح. */
    public static int everySeconds(Db db) {
        try {
            int v = Integer.parseInt(db.setting("sync_every", "90"));
            return Math.max(20, Math.min(3600, v));
        } catch (Exception e) {
            return 90;
        }
    }

    static long lng(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    public static List<String[]> conflicts(Db db) { return SyncStore.openConflicts(db.getReadableDatabase()); }

    /** 10.18: سجل تنبيهات المزامنة كله (لا يُحذف عند «تمت»): {id، الوقت، النوع، الجدول، الصف، التفاصيل، الجهاز، تمت؟}. */
    public static List<String[]> noticeHistory(Db db, int limit) { return SyncStore.allConflicts(db.getReadableDatabase(), limit); }

    /** 10.16: «تمت» لكل المفتوح للمراجعة دفعة واحدة (غير الحركات المكررة، فلها قرارها). */
    public static void resolveAll(Db db) {
        db.getWritableDatabase().execSQL("UPDATE sync_conflicts SET resolved=1 WHERE resolved=0 AND kind<>'DUPLICATE'");
    }

    /**
     * 10.16: ما يجب أن يراه المستخدم في الصفحة الرئيسية عن المزامنة (كان لا يظهر إلا في شاشة المزامنة): {النص، خطير؟ "1"/"0"}.
     * تعارضات وتنبيهات مفتوحة، مزامنة متعثرة، تغييرات لم تُرسل منذ يوم، ساعة غير مضبوطة، بصمة دفتر مختلفة.
     */
    public static List<String[]> homeAlerts(Db db) {
        List<String[]> out = new ArrayList<>();
        Status s = status(db);
        if (!s.enabled) {
            if (!s.leftReason.isEmpty()) out.add(new String[]{"خرج هذا الجهاز من المزامنة: " + s.leftReason, "1"});
            return out;
        }
        // 10.18: ساعة هذا الجهاز بعيدة عن الوقت الصحيح (البرنامج يصحّح، لكن الأفضل ضبطها)
        if (Db.clockOff()) out.add(new String[]{db.clockLines().get(1) + " يُستحسن ضبط ساعة الجهاز على «تلقائي».", "0"});
        if (s.review > 0) {
            List<String[]> cs = conflicts(db);
            String first = cs.isEmpty() ? "" : ": " + SyncStore.kindName(cs.get(0)[2]) + (cs.size() > 1 ? " و" + (cs.size() - 1) + " غيره" : "");
            boolean onlyNotices = true;
            for (String[] c : cs) if (!SyncStore.isNotice(c[2])) onlyNotices = false;
            out.add(new String[]{(onlyNotices ? s.review + " تنبيه من المزامنة" : s.review + " تعارض/تنبيه من المزامنة بحاجة لمراجعتك") + first, onlyNotices ? "0" : "1"});
        }
        if (!s.digestAlert.isEmpty()) out.add(new String[]{s.digestAlert, "1"});
        long now = Clock.now();
        boolean waiting = s.pending + s.unsent > 0;
        if (!s.movedTo.isEmpty()) out.add(new String[]{"نُقلت المزامنة إلى مكان آخر: " + s.movedTo, "1"});
        else if (!s.lastError.isEmpty()) out.add(new String[]{"آخر مزامنة فشلت" + (waiting ? " وعلى هذا الجهاز " + (s.pending + s.unsent) + " تغييرًا لم يصل للأجهزة الأخرى" : "")
                + ": " + (s.lastError.length() > 140 ? s.lastError.substring(0, 140) + "…" : s.lastError), "1"});
        else if (waiting && s.lastSync > 0 && now - s.lastSync > UNSENT_WARN_MS) out.add(new String[]{"على هذا الجهاز " + (s.pending + s.unsent)
                + " تغييرًا لم يُرسل للأجهزة الأخرى منذ " + Math.max(1, (now - s.lastSync) / 3600000L) + " ساعة. افتح «المزامنة» واضغط «مزامنة الآن».", "1"});
        if (!s.clockWarn.isEmpty()) out.add(new String[]{s.clockWarn, "0"});
        return out;
    }

    /** تغييرات لم تُرسل منذ هذه المدة تُنبَّه في الرئيسية. */
    static final long UNSENT_WARN_MS = 24L * 3600 * 1000;

    /** 10.2: اسم الجهاز الذي أنشأ الصف (من بادئة رقمه)، أو فارغ. */
    public static String deviceOf(Db db, long id) {
        long p = id >> 32;
        if (p == 0) return "";
        for (JSONObject m : status(db).members) if (m.optLong("prefix") == p) return m.optString("name");
        return "";
    }

    /** 10.2: حركتان متشابهتان من جهازين (تكرار محتمل) بانتظار قرار. */
    public static final class Dup {
        public long conflictId;
        public Model.Tx a, b;
    }

    /** التكرارات المحتملة المفتوحة؛ ما حُسم (أُلغيت إحداهما أو قيل «ليستا مكررتين» من أي جهاز) يُغلق تلقائيًا. */
    public static List<Dup> duplicates(Db db) {
        List<Dup> out = new ArrayList<>();
        SQLiteDatabase d = db.getWritableDatabase();
        List<Object[]> rows = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT id,rid FROM sync_conflicts WHERE kind='DUPLICATE' AND resolved=0 ORDER BY created_at DESC LIMIT 200", null)) {
            while (c.moveToNext()) rows.add(new Object[]{c.getLong(0), c.getString(1)});
        }
        for (Object[] r : rows) {
            String[] p = ((String) r[1]).split("\\|");
            Dup x = new Dup();
            x.conflictId = (Long) r[0];
            try {
                x.a = db.tx(Long.parseLong(p[0]));
                x.b = db.tx(Long.parseLong(p[1]));
            } catch (Exception e) {
                x.a = null;
            }
            boolean open = x.a != null && x.b != null && (x.a.isActive() || x.a.isPending()) && (x.b.isActive() || x.b.isPending())
                    && !SyncStore.dupOk(d, Math.min(x.a.id, x.b.id), Math.max(x.a.id, x.b.id));
            if (!open) d.execSQL("UPDATE sync_conflicts SET resolved=1 WHERE id=" + x.conflictId);
            else out.add(x);
        }
        return out;
    }

    /** «ليستا مكررتين»: قرار يصل للأجهزة الأخرى فلا تسأل عنه. */
    public static void keepBoth(Db db, Dup x) {
        db.setSetting("dupok_" + Math.min(x.a.id, x.b.id) + "_" + Math.max(x.a.id, x.b.id), "1");
        db.audit("DUPLICATE_KEEP", "tx", x.a.id, "أُبقيت الحركتان " + x.a.id + " و" + x.b.id);
        resolveConflict(db, x.conflictId);
    }

    /** يلغي إحدى الحركتين (قيد عكسي، أو رفض إن كانت معلّقة) ويغلق المراجعة. */
    public static void dropDuplicate(Db db, Dup x, Model.Tx drop) {
        String why = "حركة مكررة (سُجّلت من جهازين): بقيت " + (drop.id == x.a.id ? x.b.id : x.a.id);
        if (drop.isPending()) db.rejectTx(drop.id, why);
        else db.cancelTx(drop.id, why);
        resolveConflict(db, x.conflictId);
    }

    public static void resolveConflict(Db db, long id) {
        db.getWritableDatabase().execSQL("UPDATE sync_conflicts SET resolved=1 WHERE id=" + id);
    }

    // ===================================================================================
    // المفاتيح
    // ===================================================================================

    JSONObject header() throws IOException {
        byte[] b = folder.read(HEADER);
        if (b == null) return null;
        try {
            JSONObject h = new JSONObject(new String(b, StandardCharsets.UTF_8));
            if (!"smart-accountant-sync".equals(h.optString("format"))) throw new IOException("المجلد فيه ملف ليس خزنة مزامنة لهذا التطبيق");
            if (h.optInt("v") > FORMAT) throw new IOException("خزنة المزامنة من إصدار أحدث من التطبيق. حدّث التطبيق أولًا.");
            return h;
        } catch (org.json.JSONException e) {
            throw new IOException("رأس خزنة المزامنة تالف");
        }
    }

    SyncCrypto.Keys keys() throws IOException {
        SQLiteDatabase d = db.getReadableDatabase();
        String vid = SyncStore.meta(d, "vault_id"), k = SyncStore.meta(d, "vault_key");
        if (vid == null || k == null) throw new IOException("المزامنة غير مفعّلة على هذا الجهاز");
        SyncCrypto.Keys ks = new SyncCrypto.Keys(vid, SyncCrypto.unhex(Crypto.dec(k)));
        folder.unlock(ks);
        return ks;
    }

    static String platform() {
        String os = System.getProperty("os.name", "");
        if (System.getProperty("java.vendor", "").toLowerCase().contains("android") || System.getProperty("java.vm.name", "").toLowerCase().contains("dalvik")) return "أندرويد";
        if (os.toLowerCase().contains("mac")) return "ماك";
        if (os.toLowerCase().contains("win")) return "ويندوز";
        return os;
    }

    // ===================================================================================
    // إنشاء الخزنة (الجهاز المرجعي) والانضمام إليها
    // ===================================================================================

    /** هل في المجلد خزنة؟ */
    public boolean vaultExists() throws IOException {
        JSONObject h = header();
        return h != null && !tombstone(h);
    }

    // ===================================================================================
    // 10.14: «شاهد» في رأس الخزنة: حُذفت الخزنة من هذا المكان، أو نُقلت إلى مكان/حساب آخر.
    // الرأس يبقى (بلا أي مفتاح أو بيانات) حتى لا يعيد جهازٌ منضم رفعها بـ«إصلاح»، ويعرف كل جهاز ما حدث في أول مزامنة.
    // ===================================================================================

    public static boolean tombstone(JSONObject h) { return h != null && (h.has("deleted") || h.has("moved")); }

    /** نص الشاهد للمستخدم. */
    public static String tombText(JSONObject h) {
        if (h == null) return "";
        JSONObject x = h.optJSONObject(h.has("deleted") ? "deleted" : "moved");
        String who = x == null ? "" : x.optString("by"), when = x == null || x.optLong("at") <= 0 ? "" : Model.date(x.optLong("at"));
        if (h.has("deleted")) return "حذف المدير" + (who.isEmpty() ? "" : " «" + who + "»") + " ملفات المزامنة من هذا المكان" + (when.isEmpty() ? "" : " في " + when) + ".";
        String to = x == null ? "" : x.optString("to");
        boolean fresh = x != null && x.optBoolean("fresh");
        return (fresh ? "أُنشئت مزامنة جديدة" : "نُقلت المزامنة") + (to.isEmpty() ? " إلى مكان آخر" : " إلى «" + to + "»")
                + (who.isEmpty() ? "" : " (بواسطة «" + who + "»)") + (when.isEmpty() ? "" : " في " + when) + ".";
    }

    private JSONObject tombHeader(String vault, String kind, JSONObject info, byte[] vaultKey) throws Exception {
        JSONObject t = new JSONObject();
        t.put("format", "smart-accountant-sync");
        t.put("v", FORMAT);
        t.put("vault", vault);
        info.put("at", Clock.now());
        info.put("by", Db.currentUserName);
        String dev = SyncStore.meta(db.getReadableDatabase(), "device");
        if (dev != null) info.put("dev", dev);
        t.put(kind, info);
        // 11.5: الأمر موقّع بمفتاح الخزنة (لا يملكه إلا جهاز منضم أو من يعرف كلمة السر)؛ أمر بلا توقيع صحيح لا يُنفَّذ
        if (vaultKey != null) t.put("auth", tombTag(vaultKey, t));
        return t;
    }

    /** 11.5: توقيع أمر الحذف/النقل: HMAC بمفتاح مشتق من مفتاح الخزنة على الخزنة والنوع وكل حقول الأمر (مرتبة). */
    static String tombTag(byte[] vaultKey, JSONObject t) {
        String kind = t.has("deleted") ? "deleted" : "moved";
        JSONObject x = t.optJSONObject(kind);
        StringBuilder b = new StringBuilder("SA11-tomb|").append(t.optString("vault")).append('|').append(kind);
        if (x != null) {
            java.util.List<String> ks = new java.util.ArrayList<>();
            java.util.Iterator<String> it = x.keys();
            while (it.hasNext()) ks.add(it.next());
            java.util.Collections.sort(ks);
            for (String k : ks) b.append('|').append(k).append('=').append(String.valueOf(x.opt(k)));
        }
        byte[] key = SyncCrypto.hkdf(vaultKey, null, "SA11-tombstone");
        return SyncCrypto.hex(SyncCrypto.hmac(key, b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** 11.5: أمر الحذف/النقل في الرأس صادر فعلًا من جهاز يملك مفتاح هذه الخزنة؟ */
    static boolean tombVerified(JSONObject h, byte[] vaultKey) {
        if (h == null || vaultKey == null || !tombstone(h)) return false;
        String tag = h.optString("auth", "");
        return !tag.isEmpty() && java.security.MessageDigest.isEqual(tag.getBytes(StandardCharsets.UTF_8), tombTag(vaultKey, h).getBytes(StandardCharsets.UTF_8));
    }

    static final String TOMB_UNVERIFIED = "في مكان المزامنة أمر «حذف» أو «نقل» غير موقّع بمفتاح هذه الخزنة (من إصدار أقدم، أو تعديل من خارج التطبيق). "
            + "لم يُنفَّذ: هذا الجهاز باقٍ في المزامنة وحزمه غير المرسلة محفوظة. من شاشة المزامنة اضغط «إصلاح» لإعادة رأس الخزنة، "
            + "أو إن كان المدير قد حذف المزامنة فعلًا بإصدار أقدم فأوقفها هنا يدويًا.";

    /** يحذف كل ملفات هذا المكان ما عدا رأس الخزنة. يرجع عدد المحذوف. */
    private int deleteFiles() throws IOException {
        int n = 0;
        for (String name : folder.list("")) {
            if (HEADER.equals(name)) continue;
            folder.delete(name);
            n++;
        }
        return n;
    }

    private void requireAdmin(String what) {
        Model.User me = db.actor();
        if (db.usersEnabled() && (me == null || !Auth.isAdmin(me))) throw new SecurityException(what + " للمدير وحده");
    }

    /**
     * 10.14: حذف ملفات المزامنة من هذا المكان (مثلًا قبل تغيير حساب Google، أو للبدء من جديد). للمدير وحده، وبكلمة سر المزامنة
     * أو رمز الاسترداد (إثبات ملكية الخزنة)، وبعد نسخة أمان محلية من بيانات هذا الجهاز. يبقى في المكان «شاهد حذف» بلا مفاتيح:
     * كل جهاز منضم يخرج من المزامنة في جولته التالية برسالة واضحة (بياناته تبقى)، و«الإصلاح» لا يعيد رفعها.
     * يرجع عدد الملفات المحذوفة.
     */
    public int wipe(android.content.Context ctx, char[] secret, boolean recovery) throws Exception {
        VAULT.lock();
        try {
            return wipeLocked(ctx, secret, recovery);
        } finally {
            VAULT.unlock();
        }
    }

    private int wipeLocked(android.content.Context ctx, char[] secret, boolean recovery) throws Exception {
        requireAdmin("حذف ملفات المزامنة");
        JSONObject h = header();
        if (h == null) throw new IllegalStateException("لا توجد خزنة مزامنة في هذا المكان");
        if (tombstone(h)) throw new IllegalStateException(tombText(h) + " لا شيء آخر يُحذف هنا.");
        byte[] vk;
        try {
            vk = unlock(h, secret, recovery);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(recovery ? "رمز الاسترداد غير صحيح" : "كلمة سر المزامنة غير صحيحة");
        }
        // نسخة أمان من بيانات هذا الجهاز قبل أي حذف (يمكن التراجع إليها من «النسخ الاحتياطي ← نسخ الأمان»)
        if (ctx != null) Snapshot.take(ctx, db, "قبل حذف ملفات المزامنة");
        String vault = h.optString("vault");
        int n = deleteFiles();
        folder.write(HEADER, tombHeader(vault, "deleted", new JSONObject(), vk).toString(1).getBytes(StandardCharsets.UTF_8));
        if (enabled(db) && vault.equals(SyncStore.meta(db.getReadableDatabase(), "vault_id"))) leave("حذفتَ ملفات المزامنة من " + folder.describe());
        db.audit("SYNC_WIPE", "sync", 0, "حُذفت ملفات المزامنة (" + n + " ملفًا) من " + folder.describe());
        return n;
    }

    /**
     * 10.14: بعد نقل الخزنة إلى مكان/حساب آخر (أو إنشاء مزامنة جديدة هناك): يُترك في هذا المكان القديم شاهد «نُقلت إلى …»
     * تراه الأجهزة الأخرى فتتوقف عن الإرسال إليه وتطلب الانتقال. deleteFiles = يُحذف معه كل ما عدا الشاهد.
     */
    public int retire(String to, boolean fresh, boolean deleteFiles) throws Exception {
        VAULT.lock();
        try {
            return retireLocked(to, fresh, deleteFiles);
        } finally {
            VAULT.unlock();
        }
    }

    private int retireLocked(String to, boolean fresh, boolean deleteFiles) throws Exception {
        requireAdmin("ترك المكان القديم");
        JSONObject h = header();
        if (h == null) return 0;
        String vault = h.optString("vault");
        int n = deleteFiles ? deleteFiles() : 0;
        JSONObject info = new JSONObject();
        info.put("to", to == null ? "" : to);
        if (fresh) info.put("fresh", true);
        byte[] vk = null;
        try {
            SyncCrypto.Keys ks = keys();
            if (ks.vaultId.equals(vault)) vk = ks.vault;
        } catch (Exception ignored) {
            // جهاز غير منضم: أمر غير موقّع (الأجهزة الأحدث لا تنفّذه تلقائيًا)
        }
        folder.write(HEADER, tombHeader(vault, "moved", info, vk).toString(1).getBytes(StandardCharsets.UTF_8));
        db.audit("SYNC_RETIRE", "sync", 0, (fresh ? "مزامنة جديدة في " : "نُقلت إلى ") + to + (deleteFiles ? " وحُذفت ملفات القديم (" + n + ")" : ""));
        return n;
    }

    /**
     * 10.14: هل المكانان هما المكان نفسه فعلًا (مثلًا اختير الحساب القديم نفسه «حسابًا جديدًا»)؟ يُكتب ملف فحص صغير في b ويُبحث عنه في a ثم يُحذف.
     * يمنع ترك شاهد «نُقلت» فوق الخزنة الوحيدة.
     */
    public static boolean sameFolder(SyncFolder a, SyncFolder b) throws IOException {
        String probe = "probe-" + Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE) + ".json";
        b.write(probe, new byte[]{1});
        try {
            return a.list("probe-").contains(probe);
        } finally {
            try {
                b.delete(probe);
            } catch (IOException ignored) {
                // ملف فحص من بايت واحد؛ يُحذف مع ملفات الخزنة لاحقًا
            }
        }
    }

    /** 10.14: حذف ما بقي من ملفات الخزنة في مكان قديم نُقلت منه (يبقى الشاهد وحده). */
    public int purgeRetired() throws Exception {
        VAULT.lock();
        try {
            return purgeRetiredLocked();
        } finally {
            VAULT.unlock();
        }
    }

    private int purgeRetiredLocked() throws Exception {
        requireAdmin("حذف الخزنة من المكان القديم");
        JSONObject h = header();
        if (h == null || !h.has("moved")) throw new IllegalStateException("هذا المكان ليس مكانًا قديمًا نُقلت منه المزامنة");
        int n = deleteFiles();
        db.audit("SYNC_PURGE_OLD", "sync", 0, "حُذفت ملفات الخزنة من المكان القديم (" + n + ")");
        return n;
    }

    /**
     * 10.14: نسيت كلمة سر المزامنة وهذا الجهاز ما زال منضمًا: يعيّن المدير كلمة جديدة دون القديمة (الجهاز يحمل مفتاح الخزنة أصلًا،
     * فلا يُضعف هذا الأمان). الأجهزة المنضمة لا تتأثر؛ رمز الاسترداد يبقى كما هو. التأكد من هوية المدير (رمزه) في الواجهة قبلها.
     */
    public void resetPassword(char[] newPw) throws Exception {
        VAULT.lock();
        try {
            resetPasswordLocked(newPw);
        } finally {
            VAULT.unlock();
        }
    }

    private void resetPasswordLocked(char[] newPw) throws Exception {
        requireAdmin("تعيين كلمة سر المزامنة");
        if (newPw == null || newPw.length < 8) throw new IllegalArgumentException("كلمة سر المزامنة 8 محارف على الأقل");
        SyncCrypto.Keys keys = keys();
        JSONObject h = header();
        if (h == null) throw new IllegalStateException("رأس الخزنة غير موجود في المكان الحالي");
        if (tombstone(h)) throw new IllegalStateException(tombText(h));
        if (!keys.vaultId.equals(h.optString("vault"))) throw new IllegalStateException("الخزنة في هذا المكان ليست خزنة هذا الجهاز");
        byte[] salt = SyncCrypto.unhex(h.getString("salt"));
        SyncCrypto.checkKdf(h.optInt("iter", SyncCrypto.KDF_ITER), salt);
        h.put("pw", SyncCrypto.wrap(SyncCrypto.pbkdf2(newPw, salt, h.optInt("iter", SyncCrypto.KDF_ITER)), keys.vault, keys.vaultId));
        folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
        SyncStore.setMeta(db.getWritableDatabase(), "header_copy", h.toString());
        db.audit("SYNC_PASSWORD", "sync", 0, "عُيّنت كلمة سر مزامنة جديدة من جهاز منضم (دون القديمة)");
    }

    /**
     * ينشئ خزنة مزامنة من بيانات هذا الجهاز (الجهاز المرجعي): مفتاح عشوائي، ولقطة أولى (Genesis) تبدأ منها الأجهزة الأخرى.
     * يرجع رمز الاسترداد (يُعرض مرة واحدة).
     */
    public String create(char[] password, String deviceName) throws Exception {
        VAULT.lock();
        try {
            return createLocked(password, deviceName);
        } finally {
            VAULT.unlock();
        }
    }

    private String createLocked(char[] password, String deviceName) throws Exception {
        if (!License.current.sync()) throw new Entitlements.LimitReached("المزامنة بين الأجهزة ميزة في النسخة المدفوعة.");
        if (password == null || password.length < 8) throw new IllegalArgumentException("كلمة سر المزامنة 8 محارف على الأقل");
        if (vaultExists()) throw new IllegalStateException("في هذا المجلد خزنة مزامنة من قبل. اختر «الانضمام» أو مجلدًا آخر.");
        if (enabled(db)) throw new IllegalStateException("هذا الجهاز في مزامنة من قبل");
        // 10.14: مكان فيه شاهد حذف/نقل من خزنة سابقة: تُحذف بقاياها أولًا حتى لا تختلط ملفاتها بالجديدة
        if (tombstone(header())) deleteFiles();
        String vid = SyncCrypto.hex(SyncCrypto.random(12));
        byte[] vk = SyncCrypto.random(32), salt = SyncCrypto.random(16);
        String rc = SyncCrypto.newRecoveryCode();
        JSONObject h = new JSONObject();
        h.put("format", "smart-accountant-sync");
        h.put("v", FORMAT);
        h.put("vault", vid);
        h.put("salt", SyncCrypto.hex(salt));
        h.put("iter", SyncCrypto.KDF_ITER);
        h.put("pw", SyncCrypto.wrap(SyncCrypto.pbkdf2(password, salt, SyncCrypto.KDF_ITER), vk, vid));
        h.put("rc", SyncCrypto.wrap(SyncCrypto.pbkdf2(SyncCrypto.normalizeRecovery(rc).toCharArray(), salt, SyncCrypto.KDF_ITER), vk, vid));
        h.put("created", Clock.now());
        folder.unlock(new SyncCrypto.Keys(vid, vk));
        // أسرار الرموز وبصمة اليوم تُنشأ هنا لتنتقل مع اللقطة: رموز المستخدمين واحدة على كل الأجهزة
        db.pinPepper();
        db.dayCloseKey();
        LicenseGuard.officeUid(db);   // 12.7: هوية المكتب تنتقل مع اللقطة إلى كل جهاز ينضم
        register(vid, vk, deviceName, new JSONObject(), new JSONObject());
        String dev = SyncStore.meta(db.getReadableDatabase(), "device");
        h.put("founder", dev);
        SyncStore.setMeta(db.getWritableDatabase(), "founder", dev);
        folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
        SyncStore.setMeta(db.getWritableDatabase(), "header_copy", h.toString());
        writeSnapshot();
        writeMember();
        return rc;
    }

    /** يفك مفتاح الخزنة بكلمة السر أو برمز الاسترداد. */
    byte[] unlock(JSONObject h, char[] secret, boolean recovery) throws GeneralSecurityException {
        byte[] salt = SyncCrypto.unhex(h.optString("salt"));
        int iter = h.optInt("iter", SyncCrypto.KDF_ITER);
        // 11.5: حدود معقولة لحقول الرأس قبل استعمالها (رقم ضخم يعطّل الفتح، وصغير يُضعف الاشتقاق)
        SyncCrypto.checkKdf(iter, salt);
        char[] s = recovery ? SyncCrypto.normalizeRecovery(new String(secret)).toCharArray() : secret;
        return SyncCrypto.unwrap(SyncCrypto.pbkdf2(s, salt, iter), h.optString(recovery ? "rc" : "pw"), h.optString("vault"));
    }

    /**
     * ينضم هذا الجهاز إلى خزنة موجودة: بياناته الحالية تُستبدل بآخر لقطة في الخزنة (احفظ نسخة احتياطية قبلها)،
     * ثم تُطبَّق كل الحزم التي بعدها.
     */
    // ===================================================================================
    // 12.7: الانضمام على مرحلتين: (1) فتح الخزنة بكلمة السر — لحظات — ثم يُعدّ الجهاز منضمًّا؛
    // (2) تحميل البيانات بشريط تقدم، يكمل في الخلفية ويُستأنف من تلقاء نفسه إن انقطع (ترك التطبيق، إغلاقه، انقطاع الشبكة)
    // ===================================================================================

    /** شريط التقدم: نسبة 0..100 ورسالة. */
    public interface JoinListener {
        void on(int percent, String message);
    }

    static final String PENDING = "sync-join-pending.json";

    static File pendingFile(Db db) { return new File(db.evidenceDir().getParentFile(), PENDING); }

    /** 12.7: هل على هذا الجهاز انضمام بدأ ولم يكتمل تحميله؟ */
    public static boolean joinPending(Db db) {
        try {
            return pendingFile(db).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** اسم الجهاز في الانضمام المعلّق (للعرض). */
    public static String pendingName(Db db) {
        try {
            return new JSONObject(new String(Db.readAll(pendingFile(db)), StandardCharsets.UTF_8)).optString("name");
        } catch (Exception e) {
            return "";
        }
    }

    /** يلغي انضمامًا معلّقًا لم يبدأ استبدال البيانات بعد (أو انتهى). */
    public static void cancelPending(Db db) { pendingFile(db).delete(); }

    public void join(char[] secret, boolean recovery, String deviceName) throws Exception {
        joinPrepare(secret, recovery, deviceName);
        joinFinish(null);
    }

    /** المرحلة 1: يتحقق من كلمة السر ووجود لقطة، ويحفظ الانضمام معلّقًا (المفتاح مشفّرًا بمفتاح الجهاز). لا يغيّر البيانات. */
    public void joinPrepare(char[] secret, boolean recovery, String deviceName) throws Exception {
        VAULT.lock();
        try {
            if (!License.current.sync()) throw new Entitlements.LimitReached("المزامنة بين الأجهزة ميزة في النسخة المدفوعة.");
            if (enabled(db)) throw new IllegalStateException("هذا الجهاز في مزامنة من قبل");
            JSONObject h = header();
            if (h == null) throw new IllegalStateException("لا توجد خزنة مزامنة في هذا المجلد. أنشئها من الجهاز الذي فيه بياناتك الكاملة.");
            if (tombstone(h)) throw new IllegalStateException(tombText(h) + (h.has("moved") ? " سجّل الدخول بالمكان أو الحساب الجديد ثم انضم هناك." : " أنشئ مزامنة جديدة أو انضم إلى مكانها الجديد."));
            byte[] vk;
            try {
                vk = unlock(h, secret, recovery);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException(recovery ? "رمز الاسترداد غير صحيح" : "كلمة سر المزامنة غير صحيحة");
            }
            String vid = h.getString("vault");
            SyncCrypto.Keys keys = new SyncCrypto.Keys(vid, vk);
            folder.unlock(keys);
            if (bestSnapshot(readMembers(keys)) == null) throw new IllegalStateException("لم تصل لقطة الخزنة إلى هذا الجهاز بعد. انتظر حتى تكتمل مزامنة المجلد ثم أعد المحاولة.");
            JSONObject p = new JSONObject();
            p.put("vid", vid);
            p.put("vk", Crypto.enc(SyncCrypto.hex(vk)));
            p.put("name", deviceName == null ? "" : deviceName);
            p.put("at", Clock.now());
            File f = pendingFile(db), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(p.toString().getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new IOException("تعذر حفظ حالة الانضمام");
            db.audit("SYNC_JOIN_START", "sync", 0, p.optString("name"));
        } finally {
            VAULT.unlock();
        }
    }

    /** أحدث لقطة بين الأعضاء — 10.5: من اللقطات التي تشمل ما حُذف من حزم الأجهزة المُزالة فقط. */
    private JSONObject bestSnapshot(Map<String, JSONObject> members) throws Exception {
        Map<String, JSONObject> rem = removedMap();
        JSONObject best = null;
        for (JSONObject m : members.values()) {
            JSONObject s = m.optJSONObject("snap");
            if (s != null && covers(s, rem) && (best == null || s.optLong("at") > best.optLong("at"))) best = s;
        }
        return best;
    }

    /** المرحلة 2: يحمّل البيانات ويستبدلها ويسجّل الجهاز. آمنة للإعادة: ما لم يُسجَّل الجهاز تُعاد من أولها. */
    public void joinFinish(JoinListener l) throws Exception {
        VAULT.lock();
        try {
            joinFinishLocked(l == null ? (pc, m) -> { } : l);
        } finally {
            VAULT.unlock();
        }
    }

    private void joinFinishLocked(JoinListener l) throws Exception {
        File pf = pendingFile(db);
        if (!pf.isFile()) {
            if (enabled(db)) return;
            throw new IllegalStateException("لا يوجد انضمام جارٍ. ابدأ الانضمام من جديد.");
        }
        JSONObject p = new JSONObject(new String(Db.readAll(pf), StandardCharsets.UTF_8));
        if (enabled(db)) {
            // سُجّل الجهاز ثم انقطع قبل حذف الملف: يكمل الجولة الأولى فقط
            pf.delete();
            l.on(92, "أول مزامنة…");
            firstRound();
            l.on(100, "اكتمل");
            return;
        }
        if (!License.current.sync()) throw new Entitlements.LimitReached("المزامنة بين الأجهزة ميزة في النسخة المدفوعة.");
        l.on(3, "فتح الخزنة…");
        String vid = p.getString("vid");
        byte[] vk = SyncCrypto.unhex(Crypto.decStrict(p.getString("vk")));
        JSONObject h = header();
        if (h == null) throw new IllegalStateException("لا تصل خزنة المزامنة الآن. تأكد من الاتصال ثم اضغط «إعادة المحاولة».");
        if (!vid.equals(h.optString("vault")) || tombstone(h))
            throw new IllegalStateException("تغيّرت خزنة المزامنة منذ بدأ الانضمام. اضغط «إلغاء الانضمام» ثم انضم من جديد.");
        SyncCrypto.Keys keys = new SyncCrypto.Keys(vid, vk);
        folder.unlock(keys);
        Map<String, JSONObject> members = readMembers(keys);
        JSONObject best = bestSnapshot(members);
        if (best == null) throw new IllegalStateException("لم تصل لقطة الخزنة إلى هذا الجهاز بعد. انتظر حتى تكتمل مزامنة المجلد ثم أعد المحاولة.");
        l.on(8, "تنزيل البيانات…");
        // 10.18: اللقطة تُستبدل دوريًا على جهازها؛ تعثّر قراءتها لحظة الانضمام كان يُفشل الانضمام: تُعاد المحاولة
        byte[] sealed = null;
        for (int attempt = 0; attempt < 3 && sealed == null; attempt++) {
            try {
                sealed = folder.read(best.getString("name"));
            } catch (IOException e) {
                if (attempt == 2) throw e;
            }
            if (sealed == null && attempt < 2) Thread.sleep(700L * (attempt + 1));
        }
        if (sealed == null) throw new IllegalStateException("ملف اللقطة لم يصل بعد إلى هذا الجهاز. انتظر حتى تكتمل مزامنة المجلد.");
        JSONObject snap = new JSONObject(new String(keys.openFile("s", best.getString("name"), sealed), StandardCharsets.UTF_8));
        l.on(25, "تنزيل الصور…");
        // 10.4: صور اللقطة ملفات مستقلة: تُنزَّل كلها قبل أن تُستبدل بيانات هذا الجهاز (إن نقص شيء لا يتغير شيء)
        List<Object[]> blobs = fetchSnapshotBlobs(keys, snap.optJSONArray("atts"), (i, n) -> l.on(25 + (n == 0 ? 25 : 25 * i / n), "تنزيل الصور " + i + " من " + n + "…"));
        l.on(52, "حفظ البيانات على هذا الجهاز…");
        restore(Base64.getDecoder().decode(snap.getString("zip")));
        l.on(82, "ترتيب الصور…");
        insertBlobs(blobs);
        register(vid, vk, p.optString("name"), snap.getJSONObject("cursors"), snap.getJSONObject("hashes"));
        SyncStore.setMeta(db.getWritableDatabase(), "header_copy", h.toString());
        if (!h.optString("founder").isEmpty()) SyncStore.setMeta(db.getWritableDatabase(), "founder", h.optString("founder"));
        else SyncStore.setMeta(db.getWritableDatabase(), "founder", snap.optString("dev"));
        pf.delete();
        db.audit("SYNC_JOIN_DONE", "sync", 0, p.optString("name"));
        l.on(90, "أول مزامنة…");
        firstRound();
        l.on(100, "اكتمل");
    }

    /**
     * 10.18: الانضمام تمّ عند هذه النقطة (البيانات استُبدلت والجهاز سُجّل). تعثّر الشبكة في كتابة البطاقة أو في أول جولة
     * كان يُظهر «خطأ» مع أن الجهاز انضم، فيحاول المستخدم ثانية ويُرفض «في مزامنة من قبل». الآن: ينجح الانضمام وتُكمل الجولة التالية ما بقي
     */
    private void firstRound() {
        try {
            writeMember();
            syncLocked();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            SyncStore.setMeta(db.getWritableDatabase(), "last_error", "انضم الجهاز، وتعثّرت أول مزامنة: " + msg + " — ستُعاد تلقائيًا، أو اضغط «مزامنة الآن».");
            db.audit("SYNC_JOIN_PARTIAL", "sync", 0, msg);
        }
    }

    /** يستعيد لقطة الخزنة فوق بيانات هذا الجهاز (بالمسار نفسه للاستعادة العادية وفحوصها). */
    void restore(byte[] zip) throws Exception {
        final File staging = new File(db.evidenceDir().getParentFile(), "sync-staging");
        if (!staging.exists() && !staging.mkdirs()) throw new IOException("تعذر تجهيز مجلد مؤقت");
        BackupFormat.AttachmentStore store = new BackupFormat.AttachmentStore() {
            @Override public Object store(long id, InputStream data) throws Exception {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = data.read(buf)) > 0) o.write(buf, 0, n);
                File f = new File(staging, UUID.randomUUID() + ".evd");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(Crypto.encBytes(o.toByteArray()));
                }
                return f;
            }

            @Override public void discard(Object handle) {
                if (handle instanceof File) ((File) handle).delete();
            }
        };
        BackupFormat.Parsed p = BackupFormat.readV5(new ByteArrayInputStream(zip), store);
        BackupFormat.Report r = BackupFormat.validate(p);
        if (!r.errors.isEmpty()) throw new IOException("لقطة الخزنة لم تجتز الفحص: " + r.errors.get(0));
        boolean sys = db.isSystem();
        db.setSystem(true);
        db.restoringForSync = true;
        try {
            db.importBackup(p, null);
        } finally {
            db.restoringForSync = false;
            db.setSystem(sys);
        }
    }

    /** 10.4: اسم ملف الصورة في الخزنة (مشتق من بصمة محتواها؛ الصورة نفسها = الملف نفسه مهما تكررت). */
    static String blobName(SyncCrypto.Keys keys, String sha) { return keys.fileName("a-", "att:" + sha); }

    /** 10.4: ينزّل صور اللقطة إلى مجلد مؤقت مشفّرة كما تُحفظ الصور محليًا: [id, tx_id, mime, created_at, sha, ملف]. */
    interface Count {
        void at(int i, int n);
    }

    List<Object[]> fetchSnapshotBlobs(SyncCrypto.Keys keys, JSONArray atts) throws Exception { return fetchSnapshotBlobs(keys, atts, (i, n) -> { }); }

    List<Object[]> fetchSnapshotBlobs(SyncCrypto.Keys keys, JSONArray atts, Count count) throws Exception {
        List<Object[]> out = new ArrayList<>();
        if (atts == null || atts.length() == 0) return out;
        File staging = new File(db.evidenceDir().getParentFile(), "sync-blobs");
        if (!staging.exists() && !staging.mkdirs()) throw new IOException("تعذر تجهيز مجلد مؤقت للصور");
        int missing = 0;
        try {
            for (int i = 0; i < atts.length(); i++) {
                if (i % 5 == 0) count.at(i, atts.length());
                JSONObject x = atts.getJSONObject(i);
                String ref = x.getString("ref"), sha = x.getString("sha");
                byte[] sealed = folder.read(ref);
                if (sealed == null) {
                    missing++;
                    continue;
                }
                byte[] plain = keys.openFile("a", ref, sealed);
                if (!sha.equals(SyncCrypto.hex(SyncCrypto.sha256(plain)))) throw new IOException("صورة تالفة في الخزنة (رقم " + x.optLong("id") + ")");
                File f = new File(staging, UUID.randomUUID() + ".evd");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(Crypto.encBytes(plain));
                }
                out.add(new Object[]{x.getLong("id"), x.getLong("tx"), x.optString("mime", "image/jpeg"), x.optLong("at"), sha, f});
            }
            if (missing > 0)
                throw new IllegalStateException("لم تصل " + missing + " من " + atts.length() + " صورة من صور الخزنة إلى هذا الجهاز بعد. انتظر حتى تكتمل مزامنة المكان ثم أعد المحاولة؛ بياناتك لم تتغير.");
            return out;
        } catch (Exception e) {
            for (Object[] b : out) ((File) b[5]).delete();
            throw e;
        }
    }

    /** 10.4: بعد استعادة لقطة بلا صور: تُنقل الصور المنزّلة إلى مكانها وتُسجَّل (قبل تفعيل الالتقاط، فلا تُرسل من جديد). */
    void insertBlobs(List<Object[]> blobs) throws IOException {
        if (blobs.isEmpty()) return;
        File dir = db.evidenceDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد الصور");
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            for (Object[] b : blobs) {
                File src = (File) b[5], dst = new File(dir, src.getName());
                if (!src.renameTo(dst)) {
                    java.nio.file.Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    src.delete();
                }
                d.execSQL("INSERT OR REPLACE INTO attachments(id,tx_id,path,mime,created_at) VALUES(?,?,?,?,?)",
                        new Object[]{b[0], b[1], dst.getAbsolutePath(), b[2], b[3]});
                d.execSQL("INSERT OR REPLACE INTO sync_blobs(att_id,sha) VALUES(?,?)", new Object[]{b[0], b[4]});
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** يسجّل هذا الجهاز في الخزنة: معرّف جديد، وبادئة أرقام لا يستخدمها غيره، والمؤشرات التي بدأ منها. */
    void register(String vid, byte[] vk, String deviceName, JSONObject cursors, JSONObject hashes) throws Exception {
        SyncCrypto.Keys keys = new SyncCrypto.Keys(vid, vk);
        Set<Long> used = new HashSet<>();
        // 10.4: بادئات الأجهزة المُزالة محجوزة أيضًا (صفوفها باقية في الدفتر)
        for (JSONObject m : readMembersRaw(keys).values()) {
            used.add(m.optLong("prefix"));
            JSONArray r = m.optJSONArray("removed");
            if (r != null) for (int i = 0; i < r.length(); i++) used.add(r.optJSONObject(i) == null ? 0 : r.optJSONObject(i).optLong("prefix"));
        }
        SecureRandom rnd = new SecureRandom();
        long prefix;
        do {
            prefix = 1 + rnd.nextInt((1 << 20) - 2);
        } while (used.contains(prefix));
        String dev = SyncCrypto.hex(SyncCrypto.random(8));
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            for (String t : new String[]{"sync_meta", "sync_chg", "sync_clock", "sync_ids", "sync_outbox", "sync_cursor"}) d.execSQL("DELETE FROM " + t);
            SyncStore.setMeta(d, "vault_id", vid);
            SyncStore.setMeta(d, "joined_fmt", "" + FORMAT);
            SyncStore.setMeta(d, "vault_key", Crypto.enc(SyncCrypto.hex(vk)));
            SyncStore.setMeta(d, "device", dev);
            SyncStore.setMeta(d, "device_name", deviceName == null || deviceName.trim().isEmpty() ? platform() : deviceName.trim());
            SyncStore.setMeta(d, "prefix", "" + prefix);
            SyncStore.setMeta(d, "my_seq", "0");
            SyncStore.setMeta(d, "my_hash", "");
            SyncStore.setMeta(d, "folder", folder.describe());
            SyncStore.setMeta(d, "joined_at", "" + Clock.now());
            Iterator<String> it = cursors.keys();
            while (it.hasNext()) {
                String x = it.next();
                d.execSQL("INSERT OR REPLACE INTO sync_cursor(dev,seq,hash) VALUES(?,?,?)", new Object[]{x, cursors.getLong(x), hashes.optString(x, "")});
            }
            SyncStore.capture(d, true);
            db.audit("SYNC_JOIN", "sync", 0, "جهاز " + dev + " — بادئة " + prefix);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        db.syncPrefix = prefix;
    }

    /** يخرج هذا الجهاز من المزامنة (بياناته تبقى). */
    public void leave() {
        leave("أُوقفت المزامنة على هذا الجهاز");
    }

    /** 10.14: الخروج مع سبب يظهر للمستخدم (مثل: حذف المدير ملفات المزامنة). */
    public void leave(String reason) {
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            SyncStore.leave(d, reason);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        db.syncPrefix = 0;
    }

    /**
     * 10.2: نقل الخزنة إلى ناقل آخر (من المجلد المشترك إلى Google Drive المباشر أو خادمك، أو العكس): تُنسخ ملفاتها المشفّرة
     * كما هي (الرأس والأعضاء واللقطات والحزم) ما لم تكن هناك. كل جهاز يفعل الشيء نفسه فيكمل كل منهم ما رآه الآخر.
     * يرجع عدد الملفات المنسوخة.
     */
    public int moveTo(SyncFolder dst) throws Exception {
        VAULT.lock();
        try {
            return moveToLocked(dst);
        } finally {
            VAULT.unlock();
        }
    }

    private int moveToLocked(SyncFolder dst) throws Exception {
        SyncCrypto.Keys keys = keys();
        dst.unlock(keys);
        byte[] h = folder.read(HEADER);
        if (h == null) throw new IOException("رأس الخزنة غير موجود في الناقل الحالي");
        // 10.14: المكان الحالي قديم نُقلت منه الخزنة (فيه شاهد بلا مفاتيح): لا يُنسخ الشاهد إلى الوجهة، بل رأس الخزنة المحفوظ على هذا الجهاز
        if (tombstone(new JSONObject(new String(h, StandardCharsets.UTF_8)))) {
            String copy = SyncStore.meta(db.getReadableDatabase(), "header_copy");
            if (copy == null || tombstone(new JSONObject(copy)) || !keys.vaultId.equals(new JSONObject(copy).optString("vault")))
                throw new IllegalStateException("رأس الخزنة غير محفوظ على هذا الجهاز. سجّل الدخول بالمكان الجديد مباشرة.");
            h = copy.getBytes(StandardCharsets.UTF_8);
        }
        byte[] there = dst.read(HEADER);
        if (there != null) {
            JSONObject t = new JSONObject(new String(there, StandardCharsets.UTF_8));
            if (tombstone(t)) dst.write(HEADER, h);
            else if (!keys.vaultId.equals(t.optString("vault"))) throw new IllegalStateException("في الوجهة خزنة مزامنة أخرى. اختر وجهة فارغة أو الخزنة نفسها.");
        } else dst.write(HEADER, h);
        int n = 0;
        java.util.Set<String> have = new HashSet<>();
        have.addAll(dst.list(""));
        // 10.4: الصور قبل اللقطات والحزم (ما يشير إليها يجدها)
        for (String p : new String[]{"a-", "s-", "p-", "m-"}) {
            for (String name : folder.list(p)) {
                if (have.contains(name)) continue;
                byte[] b = folder.read(name);
                if (b == null) continue;
                dst.write(name, b);
                n++;
            }
        }
        SyncStore.setMeta(db.getWritableDatabase(), "folder", dst.describe());
        return n;
    }

    /** 10.3: هل كانت آخر جولة بلا ملف خزنة في المكان المختار؟ */
    public static boolean headerMissing(Db db) { return "1".equals(SyncStore.meta(db.getReadableDatabase(), "header_missing")); }

    /** هل يحتاج الإصلاح كلمة السر؟ (لا نسخة محلية من رأس الخزنة: أجهزة 10.2 وما قبله) */
    public static boolean repairNeedsPassword(Db db) { return SyncStore.meta(db.getReadableDatabase(), "header_copy") == null; }

    /**
     * 10.3: إصلاح مكان مزامنة بلا ملفات (نُقل المجلد أو فُرّغ): يكتب رأس الخزنة (من النسخة المحلية، أو جديدًا بكلمة السر)،
     * ولقطة كاملة لهذا الجهاز، وما عنده من حزم، وبطاقته. لا يغيّر أي بيانات محاسبية. يرجع رمز استرداد جديدًا إن أُنشئ رأس جديد، وإلا "".
     */
    public String repair(char[] password) throws Exception {
        VAULT.lock();
        try {
            return repairLocked(password);
        } finally {
            VAULT.unlock();
        }
    }

    private String repairLocked(char[] password) throws Exception {
        if (!enabled(db)) throw new IllegalStateException("المزامنة غير مفعّلة على هذا الجهاز");
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        JSONObject there = header();
        String rc = "";
        // 11.5: أمر حذف/نقل غير موقّع لا يمنع الإصلاح (يُعاد الرأس الأصلي من نسخة هذا الجهاز)
        if (there != null && tombstone(there) && keys.vaultId.equals(there.optString("vault")) && !tombVerified(there, keys.vault)) there = null;
        if (there != null && tombstone(there)) throw new IllegalStateException(tombText(there) + " لا يُعاد رفع الخزنة إلى هنا. انتقل إلى مكانها الجديد أو أنشئ مزامنة جديدة.");
        if (there != null) {
            if (!keys.vaultId.equals(there.optString("vault"))) throw new IllegalStateException("في هذا المكان خزنة مزامنة أخرى. اختر مكانًا آخر.");
        } else {
            String copy = SyncStore.meta(d, "header_copy");
            JSONObject h;
            if (copy != null) h = new JSONObject(copy);
            else {
                if (password == null || password.length < 8) throw new IllegalArgumentException("اكتب كلمة سر المزامنة (8 محارف على الأقل)");
                byte[] salt = SyncCrypto.random(16);
                rc = SyncCrypto.newRecoveryCode();
                h = new JSONObject();
                h.put("format", "smart-accountant-sync");
                h.put("v", FORMAT);
                h.put("vault", keys.vaultId);
                h.put("salt", SyncCrypto.hex(salt));
                h.put("iter", SyncCrypto.KDF_ITER);
                h.put("pw", SyncCrypto.wrap(SyncCrypto.pbkdf2(password, salt, SyncCrypto.KDF_ITER), keys.vault, keys.vaultId));
                h.put("rc", SyncCrypto.wrap(SyncCrypto.pbkdf2(SyncCrypto.normalizeRecovery(rc).toCharArray(), salt, SyncCrypto.KDF_ITER), keys.vault, keys.vaultId));
                h.put("created", Clock.now());
                String f = SyncStore.meta(d, "founder");
                if (f != null) h.put("founder", f);
            }
            folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
            SyncStore.setMeta(d, "header_copy", h.toString());
        }
        // لقطة كاملة (يبدأ منها أي جهاز ينضم هنا) ثم حزم هذا الجهاز وبطاقته
        writeSnapshot();
        d.execSQL("UPDATE sync_outbox SET uploaded=0");
        upload();
        writeMember();
        SyncStore.setMeta(d, "header_missing", null);
        SyncStore.setMeta(d, "folder", folder.describe());
        db.audit("SYNC_REPAIR", "sync", 0, folder.describe());
        return rc;
    }

    /** تغيير كلمة سر المزامنة: يُعاد تغليف المفتاح فقط (يلزم أن تكون الكلمة القديمة صحيحة). */
    public void changePassword(char[] oldPw, char[] newPw) throws Exception {
        VAULT.lock();
        try {
            changePasswordLocked(oldPw, newPw);
        } finally {
            VAULT.unlock();
        }
    }

    private void changePasswordLocked(char[] oldPw, char[] newPw) throws Exception {
        if (newPw == null || newPw.length < 8) throw new IllegalArgumentException("كلمة سر المزامنة 8 محارف على الأقل");
        JSONObject h = header();
        if (h == null) throw new IllegalStateException("رأس الخزنة غير موجود في المجلد");
        byte[] vk;
        try {
            vk = unlock(h, oldPw, false);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("كلمة السر الحالية غير صحيحة");
        }
        folder.unlock(new SyncCrypto.Keys(h.getString("vault"), vk));
        byte[] salt = SyncCrypto.unhex(h.getString("salt"));
        h.put("pw", SyncCrypto.wrap(SyncCrypto.pbkdf2(newPw, salt, h.optInt("iter", SyncCrypto.KDF_ITER)), vk, h.getString("vault")));
        folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
        // 10.4: النسخة المحلية من الرأس تتبع التغيير (وإلا أعاد «إصلاح» كلمة السر القديمة)
        SyncStore.setMeta(db.getWritableDatabase(), "header_copy", h.toString());
        db.audit("SYNC_PASSWORD", "sync", 0, "غُيّرت كلمة سر المزامنة");
    }

    /** 10.4: رمز استرداد جديد (يلزم كلمة السر الحالية). القديم يتوقف عن العمل. يرجع الرمز الجديد (يُعرض مرة واحدة). */
    public String newRecoveryCode(char[] password) throws Exception {
        VAULT.lock();
        try {
            return newRecoveryCodeLocked(password);
        } finally {
            VAULT.unlock();
        }
    }

    private String newRecoveryCodeLocked(char[] password) throws Exception {
        JSONObject h = header();
        if (h == null) throw new IllegalStateException("رأس الخزنة غير موجود في المكان الحالي");
        byte[] vk;
        try {
            vk = unlock(h, password, false);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("كلمة سر المزامنة غير صحيحة");
        }
        if (!Arrays.equals(vk, keys().vault)) throw new IllegalStateException("الخزنة في هذا المكان ليست خزنة هذا الجهاز");
        String rc = SyncCrypto.newRecoveryCode();
        byte[] salt = SyncCrypto.unhex(h.getString("salt"));
        h.put("rc", SyncCrypto.wrap(SyncCrypto.pbkdf2(SyncCrypto.normalizeRecovery(rc).toCharArray(), salt, h.optInt("iter", SyncCrypto.KDF_ITER)), vk, h.getString("vault")));
        folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
        SyncStore.setMeta(db.getWritableDatabase(), "header_copy", h.toString());
        db.audit("SYNC_RECOVERY", "sync", 0, "أُصدر رمز استرداد جديد");
        return rc;
    }

    // ===================================================================================
    // 10.5 (AUDIT-002): دور الجهاز الرئيسي — نقله، واستلامه عند ضياع الجهاز الرئيسي
    // ===================================================================================
    //
    // التصميم:
    //  • مصدر الحقيقة الوحيد هو حقل «founder» في رأس الخزنة؛ كل جهاز يقرؤه في بداية كل جولة ويتبعه، فلا يبقى جهازان رئيسيان
    //    بعد جولة واحدة. أعمال الرئيسي (حذف ملفات المُزالين والصور، إرسال الأسرار مرة) آمنة حتى لو تزامنت للحظة.
    //  • النقل من الرئيسي نفسه: بحساب مدير، إلى جهاز نشط زامن قريبًا واستلم كل ما أرسله الرئيسي (فلا ينقصه شيء من الأسرار).
    //  • الاستلام عند الضياع: بحساب مدير + رمز استرداد الخزنة (ليس كلمة السر ولا رابط الانضمام اللذين يعرفهما كل جهاز منضم)،
    //    وبشرط أن يغيب الرئيسي FOUNDER_ABSENT_MS أو أن يكون قد خرج. إن عاد الرئيسي القديم يقرأ الرأس فيصبح جهازًا عاديًا.
    //  • كل تغيير يرفع «founder_epoch» ويُسجَّل في سجل العمليات وفي الرأس (من، إلى، متى، وكيف).

    /** أقل غياب للجهاز الرئيسي قبل السماح باستلام دوره برمز الاسترداد. */
    public static final long FOUNDER_ABSENT_MS = 7L * 24 * 3600 * 1000;
    /** الجهاز المستلم بالنقل يجب أن يكون قد زامن خلال هذه المدة. */
    static final long FOUNDER_TARGET_FRESH_MS = 3L * 24 * 3600 * 1000;

    /** ينقل دور الجهاز الرئيسي من هذا الجهاز إلى جهاز آخر نشط. */
    public void transferFounder(String toDev) throws Exception {
        VAULT.lock();
        try {
            transferFounderLocked(toDev);
        } finally {
            VAULT.unlock();
        }
    }

    private void transferFounderLocked(String toDev) throws Exception {
        db.requireAdmin("نقل دور الجهاز الرئيسي للمدير وحده.");
        if (!enabled(db)) throw new IllegalStateException("المزامنة غير مفعّلة على هذا الجهاز");
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        JSONObject h = header();
        if (h == null) throw new IllegalStateException("رأس الخزنة غير موجود في المكان الحالي");
        String me = SyncStore.meta(d, "device");
        if (!me.equals(h.optString("founder"))) throw new IllegalStateException("هذا الجهاز ليس الجهاز الرئيسي الآن");
        if (toDev == null || toDev.equals(me)) throw new IllegalArgumentException("اختر جهازًا آخر");
        roundMembers = null;
        JSONObject m = readMembers(keys).get(toDev);
        if (m == null) throw new IllegalArgumentException("الجهاز غير موجود في المزامنة أو أُزيل منها");
        if (Clock.now() - m.optLong("at") > FOUNDER_TARGET_FRESH_MS)
            throw new IllegalStateException("الجهاز «" + m.optString("name") + "» لم يزامن منذ " + Model.date(m.optLong("at")) + ". زامنه أولًا ثم انقل الدور.");
        if (!atLeast(m.optString("app"), "10.5")) throw new IllegalStateException("حدّث التطبيق على «" + m.optString("name") + "» إلى 10.5 أو أحدث أولًا.");
        JSONObject c = m.optJSONObject("cursors");
        long mySeq = lng(SyncStore.meta(d, "my_seq"));
        if (c == null || c.optLong(me, 0) < mySeq)
            throw new IllegalStateException("«" + m.optString("name") + "» لم يستلم بعد كل ما أرسله هذا الجهاز (" + (c == null ? 0 : c.optLong(me, 0)) + " من " + mySeq
                    + "). زامن الجهازين ثم أعد المحاولة.");
        setFounder(h, toDev, me, "نقل من الجهاز الرئيسي");
        db.audit("SYNC_FOUNDER", "sync", 0, "نُقل دور الجهاز الرئيسي إلى «" + m.optString("name") + "»");
        writeMember();
        cacheMembers();
    }

    /** يستلم هذا الجهاز دور الجهاز الرئيسي بعد ضياعه (رمز الاسترداد + مدير + غياب الرئيسي). */
    public void claimFounder(char[] recoveryCode) throws Exception {
        VAULT.lock();
        try {
            claimFounderLocked(recoveryCode);
        } finally {
            VAULT.unlock();
        }
    }

    private void claimFounderLocked(char[] recoveryCode) throws Exception {
        db.requireAdmin("استلام دور الجهاز الرئيسي للمدير وحده.");
        if (!enabled(db)) throw new IllegalStateException("المزامنة غير مفعّلة على هذا الجهاز");
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        JSONObject h = header();
        if (h == null) throw new IllegalStateException("رأس الخزنة غير موجود في المكان الحالي");
        byte[] vk;
        try {
            vk = unlock(h, recoveryCode, true);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("رمز الاسترداد غير صحيح");
        }
        if (!Arrays.equals(vk, keys.vault)) throw new IllegalStateException("الخزنة في هذا المكان ليست خزنة هذا الجهاز");
        String me = SyncStore.meta(d, "device"), old = h.optString("founder");
        if (me.equals(old)) throw new IllegalStateException("هذا الجهاز هو الجهاز الرئيسي من قبل");
        String why = founderClaimBlock(old);
        if (why != null) throw new IllegalStateException(why);
        setFounder(h, me, old, "استلام برمز الاسترداد بعد غياب الرئيسي");
        db.audit("SYNC_FOUNDER", "sync", 0, "استلم هذا الجهاز دور الجهاز الرئيسي برمز الاسترداد (السابق: " + nameOf(keys, old) + ")");
        writeMember();
        cacheMembers();
    }

    /** null إن جاز استلام الدور الآن، وإلا السبب (للواجهة قبل طلب الرمز). */
    public String founderClaimBlock(String founderDev) throws Exception {
        if (founderDev == null || founderDev.isEmpty()) return null;
        roundMembers = null;
        readMembers(keys());   // يدمج سجلات الخروج الأحدث من بطاقات الجميع
        JSONObject x = removedMap().get(founderDev);
        if (x != null) return null;   // خرج بإعادة التعيين
        JSONObject fm = readMembersRaw(keys()).get(founderDev);
        if (fm == null) return null;  // لا بطاقة له في المكان
        long gone = Clock.now() - fm.optLong("at");
        if (gone >= FOUNDER_ABSENT_MS) return null;
        long days = Math.max(1, (FOUNDER_ABSENT_MS - gone + 86_399_999L) / 86_400_000L);
        return "الجهاز الرئيسي «" + fm.optString("name") + "» زامن في " + Model.date(fm.optLong("at")) + ". إن كان بين يديك فانقل الدور منه هو؛ "
                + "وإن ضاع فيُسمح باستلام الدور بعد " + days + " يوم من غيابه (حماية من وجود جهازين رئيسيين).";
    }

    private String nameOf(SyncCrypto.Keys keys, String dev) {
        try {
            JSONObject m = readMembersRaw(keys).get(dev);
            if (m != null) return m.optString("name");
        } catch (Exception ignored) {
            // الاسم للعرض فقط
        }
        JSONObject x = removedMap().get(dev);
        return x != null ? x.optString("name") : dev;
    }

    private void setFounder(JSONObject h, String to, String from, String how) throws Exception {
        h.put("founder", to);
        h.put("founder_epoch", h.optLong("founder_epoch", 0) + 1);
        h.put("founder_at", Clock.now());
        h.put("founder_from", from == null ? "" : from);
        h.put("founder_how", how);
        folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
        SQLiteDatabase d = db.getWritableDatabase();
        SyncStore.setMeta(d, "header_copy", h.toString());
        SyncStore.setMeta(d, "founder", to);
        // الأسرار وصلت إلى كل جهاز مع لقطته: لا تُعاد من الرئيسي الجديد (لا تتغير رموز المستخدمين)
        if (to.equals(SyncStore.meta(d, "device"))) SyncStore.setMeta(d, "secrets_sent", "1");
    }

    /** «10.5» ≥ «10.4»… (أرقام مفصولة بنقاط). */
    static boolean atLeast(String v, String min) {
        String[] a = (v == null ? "" : v).split("\\."), b = min.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? lng(a[i].replaceAll("[^0-9]", "")) : 0, y = i < b.length ? lng(b[i]) : 0;
            if (x != y) return x > y;
        }
        return true;
    }

    /** 10.4: اسم جديد لهذا الجهاز (يظهر لبقية الأجهزة بعد الجولة التالية). */
    public void rename(String name) throws Exception {
        VAULT.lock();
        try {
            renameLocked(name);
        } finally {
            VAULT.unlock();
        }
    }

    private void renameLocked(String name) throws Exception {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("اكتب اسمًا للجهاز");
        if (n.length() > 40) n = n.substring(0, 40);
        SyncStore.setMeta(db.getWritableDatabase(), "device_name", n);
        // تسمية الجهاز لنفسه أحدث من أي تسمية سابقة أعطاها له المدير
        Map<String, JSONObject> rn = renamesMap();
        JSONObject x = new JSONObject();
        x.put("name", n);
        x.put("at", Clock.now());
        rn.put(nz(SyncStore.meta(db.getReadableDatabase(), "device")), x);
        saveRenames(rn);
        writeMember();
        cacheMembers();
    }

    // ---- 10.17: المدير يعيد تسمية أي جهاز منضم ----

    Map<String, JSONObject> renamesMap() {
        Map<String, JSONObject> out = new TreeMap<>();
        try {
            String s = SyncStore.meta(db.getReadableDatabase(), "renames");
            JSONObject o = new JSONObject(s == null || s.isEmpty() ? "{}" : s);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                if (o.optJSONObject(k) != null) out.put(k, o.getJSONObject(k));
            }
        } catch (Exception ignored) {
            // قائمة تالفة = بلا تسميات
        }
        return out;
    }

    void saveRenames(Map<String, JSONObject> m) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, JSONObject> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        SyncStore.setMeta(db.getWritableDatabase(), "renames", o.toString());
    }

    /**
     * يسمّي المدير جهازًا منضمًّا (من جهازه). التسمية تنتقل في بطاقات الأجهزة (الأحدث يفوز)، فتراها كل الأجهزة بعد جولتها
     * التالية، والجهاز نفسه يعتمدها اسمًا له.
     */
    public void renameDevice(String dev, String name) throws Exception {
        VAULT.lock();
        try {
            db.requireAdmin("إعادة تسمية أجهزة المزامنة للمدير وحده.");
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) throw new IllegalArgumentException("اكتب اسمًا للجهاز");
            if (n.length() > 40) n = n.substring(0, 40);
            if (dev == null || dev.isEmpty()) throw new IllegalArgumentException("الجهاز غير معروف");
            if (dev.equals(SyncStore.meta(db.getReadableDatabase(), "device"))) {
                renameLocked(n);
                return;
            }
            Map<String, JSONObject> rn = renamesMap();
            JSONObject x = new JSONObject();
            x.put("name", n);
            x.put("at", Clock.now());
            x.put("by", nz(SyncStore.meta(db.getReadableDatabase(), "device_name")));
            rn.put(dev, x);
            saveRenames(rn);
            writeMember();
            cacheMembers();
            db.audit("SYNC_DEVICE", "sync", 0, "سمّى المدير الجهاز: " + n);
        } finally {
            VAULT.unlock();
        }
    }

    // ===================================================================================
    // الأعضاء واللقطات
    // ===================================================================================

    /** 10.4: الأعضاء النشطون فقط (بلا الأجهزة التي أزالها المدير). يحدّث قائمة المُزالين المحلية من بطاقات الجميع. */
    Map<String, JSONObject> readMembers(SyncCrypto.Keys keys) throws IOException {
        Map<String, JSONObject> all = readMembersRaw(keys);
        Map<String, JSONObject> removed = removedMap();
        boolean grew = false;
        for (JSONObject m : all.values()) {
            JSONArray r = m.optJSONArray("removed");
            if (r == null) continue;
            for (int i = 0; i < r.length(); i++) {
                JSONObject x = r.optJSONObject(i);
                if (x == null || x.optString("dev").isEmpty()) continue;
                JSONObject have = removed.get(x.optString("dev"));
                if (have == null) {
                    removed.put(x.optString("dev"), x);
                    grew = true;
                    continue;
                }
                // 10.5: سجلان للجهاز نفسه (أزاله جهازان، أو خرج بنفسه وأزيل): يُؤخذ الأبعد upTo فلا يُقطع ما أرسله
                try {
                    if (x.has("upTo") && (!have.has("upTo") && purgedTo(have) < 0 || x.optLong("upTo") > have.optLong("upTo", 0))) {
                        have.put("upTo", x.optLong("upTo"));
                        have.put("snaps", Math.max(have.optLong("snaps", 0), x.optLong("snaps", 0)));
                        grew = true;
                    }
                    if (purgedTo(x) > purgedTo(have)) {
                        have.put("purgedTo", purgedTo(x));
                        grew = true;
                    }
                } catch (org.json.JSONException ignored) {
                    // سجل غير مكتمل: يبقى المحلي
                }
            }
        }
        if (grew) saveRemoved(removed);
        for (String dev : removed.keySet()) all.remove(dev);
        // 10.17: تسميات الأجهزة (من المدير أو من الجهاز نفسه): تُدمج من كل البطاقات، والأحدث يفوز
        try {
            Map<String, JSONObject> rn = renamesMap();
            boolean newer = false;
            for (JSONObject m : all.values()) {
                JSONObject r = m.optJSONObject("renames");
                if (r == null) continue;
                for (java.util.Iterator<String> it = r.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject x = r.optJSONObject(k), have = rn.get(k);
                    if (x == null || x.optString("name").trim().isEmpty()) continue;
                    if (have == null || x.optLong("at") > have.optLong("at")) {
                        rn.put(k, x);
                        newer = true;
                    }
                }
            }
            if (newer) saveRenames(rn);
            String me = nz(SyncStore.meta(db.getReadableDatabase(), "device"));
            for (Map.Entry<String, JSONObject> e : rn.entrySet()) {
                String nm = e.getValue().optString("name").trim();
                if (nm.isEmpty()) continue;
                JSONObject card = all.get(e.getKey());
                if (card != null) card.put("name", nm);
                // هذا الجهاز سمّاه المدير: يعتمد الاسم (وتحمله بطاقته من الجولة التالية)
                if (e.getKey().equals(me) && !nm.equals(SyncStore.meta(db.getReadableDatabase(), "device_name"))) SyncStore.setMeta(db.getWritableDatabase(), "device_name", nm);
            }
        } catch (Exception ignored) {
            // التسمية للعرض فقط
        }
        return all;
    }

    Map<String, JSONObject> removedMap() {
        Map<String, JSONObject> out = new TreeMap<>();
        try {
            String s = SyncStore.meta(db.getReadableDatabase(), "removed");
            JSONArray a = new JSONArray(s == null ? "[]" : s);
            for (int i = 0; i < a.length(); i++) out.put(a.getJSONObject(i).optString("dev"), a.getJSONObject(i));
        } catch (Exception ignored) {
            // فارغة
        }
        return out;
    }

    void saveRemoved(Map<String, JSONObject> removed) {
        JSONArray a = new JSONArray();
        for (JSONObject x : removed.values()) a.put(x);
        SyncStore.setMeta(db.getWritableDatabase(), "removed", a.toString());
    }

    /** 10.4: بطاقات الأعضاء تُقرأ مرة واحدة في الجولة (طلبات أقل إلى Drive والخادم والمجلد). */
    private Map<String, JSONObject> roundMembers;

    Map<String, JSONObject> readMembersRaw(SyncCrypto.Keys keys) throws IOException {
        if (roundMembers != null) return new TreeMap<>(roundMembers);
        Map<String, JSONObject> out = new TreeMap<>();
        membersIncomplete = false;
        for (String n : folder.list("m-")) {
            byte[] b = null;
            // 10.18: بطاقة لم تُقرأ (تُستبدل الآن على جهاز صاحبها، أو تعثّر التنزيل) تُعاد محاولتها؛ وإن بقيت ناقصة فالقائمة «غير مكتملة»:
            // لا يُعلن خروج جهاز ولا يُحذف شيء من الخزنة اعتمادًا عليها
            for (int attempt = 0; attempt < 3 && b == null; attempt++) {
                try {
                    b = folder.read(n);
                } catch (IOException e) {
                    b = null;
                }
                if (b == null && attempt < 2) try {
                    Thread.sleep(400L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (b == null) {
                membersIncomplete = true;
                continue;
            }
            try {
                JSONObject m = new JSONObject(new String(keys.openFile("m", n, b), StandardCharsets.UTF_8));
                if (!keys.vaultId.equals(m.optString("vault"))) continue;
                if (!n.equals(keys.fileName("m-", "member:" + m.optString("dev")))) continue;
                out.put(m.getString("dev"), m);
            } catch (Exception e) {
                // ملف عضو لم يكتمل وصوله أو من خزنة أخرى: يُتجاهل، والقائمة تُعد غير مكتملة
                membersIncomplete = true;
            }
        }
        return out;
    }

    /** 10.18: آخر قراءة لبطاقات الأجهزة لم تكتمل (بطاقة لم تُقرأ أو لم تُفك). */
    private boolean membersIncomplete;

    void writeMember() throws Exception {
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getReadableDatabase();
        String dev = SyncStore.meta(d, "device");
        JSONObject m = new JSONObject();
        m.put("vault", keys.vaultId);
        m.put("dev", dev);
        m.put("name", nz(SyncStore.meta(d, "device_name")));
        m.put("platform", platform());
        m.put("app", BuildInfo.VERSION);
        m.put("prefix", lng(SyncStore.meta(d, "prefix")));
        m.put("seq", lng(SyncStore.meta(d, "my_seq")));
        m.put("hash", nz(SyncStore.meta(d, "my_hash")));
        m.put("cursors", new JSONObject(SyncStore.cursors(d)));
        m.put("digest", SyncStore.digest(d));
        m.put("at", Clock.now());
        JSONObject rnj = new JSONObject();
        for (Map.Entry<String, JSONObject> e : renamesMap().entrySet()) rnj.put(e.getKey(), e.getValue());
        m.put("renames", rnj);
        m.put("fmt", FORMAT);
        String snap = SyncStore.meta(d, "snap");
        if (snap != null) m.put("snap", new JSONObject(snap));
        // 10.4: الأجهزة المُزالة تنتقل من بطاقة إلى بطاقة فلا تُنسى
        Map<String, JSONObject> removed = removedMap();
        if (!removed.isEmpty()) {
            JSONArray ra = new JSONArray();
            for (JSONObject x : removed.values()) ra.put(x);
            m.put("removed", ra);
        }
        String name = keys.fileName("m-", "member:" + dev);
        folder.write(name, keys.sealFile("m", name, m.toString().getBytes(StandardCharsets.UTF_8)));
        if (roundMembers != null) roundMembers.put(dev, m);
    }

    /** لقطة كاملة لحالة هذا الجهاز مع مؤشراته (يبدأ منها المنضم). تُختم الحزم المعلّقة قبلها فتكون اللقطة = ما حتى my_seq. */
    void writeSnapshot() throws Exception {
        sealPending();
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        JSONObject cursors = new JSONObject(), hashes = new JSONObject();
        List<Object[]> atts = new ArrayList<>();
        byte[] zip, zipInside = null;
        File copy = null;
        String dev;
        long mySeq;
        d.beginTransaction();
        try {
            // داخل معاملة كتابة: لا يتغير شيء بين قراءة المؤشرات وكتابة اللقطة
            dev = SyncStore.meta(d, "device");
            mySeq = lng(SyncStore.meta(d, "my_seq"));
            try (Cursor c = d.rawQuery("SELECT dev,seq,hash FROM sync_cursor", null)) {
                while (c.moveToNext()) {
                    cursors.put(c.getString(0), c.getLong(1));
                    hashes.put(c.getString(0), c.getString(2));
                }
            }
            cursors.put(dev, mySeq);
            hashes.put(dev, nz(SyncStore.meta(d, "my_hash")));
            // 10.4: اللقطة بلا صور (الصور ملفات مستقلة تشير إليها اللقطة ببصمتها)
            try (Cursor c = d.rawQuery("SELECT id,tx_id,path,mime,created_at FROM attachments ORDER BY id", null)) {
                while (c.moveToNext()) atts.add(new Object[]{c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.isNull(4) ? 0L : c.getLong(4)});
            }
            // 12.7: نسخة من الملف داخل المعاملة (لحظات)، وبناء اللقطة منها خارجها: لا تنتظر الشاشة ولا الحركات الجديدة
            copy = db.copyForRead("snap");
            if (copy == null) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                BackupFormat.write(withoutAttachments(db.backupSource()), o, BuildInfo.VERSION, Db.VER);
                zipInside = o.toByteArray();
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        if (zipInside != null) zip = zipInside;
        else {
            try {
                SQLiteDatabase src = db.openReadCopy(copy);
                try {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    BackupFormat.write(withoutAttachments(db.backupSource(src)), o, BuildInfo.VERSION, Db.VER);
                    zip = o.toByteArray();
                } finally {
                    src.close();
                }
            } finally {
                DbCipher.deleteDb(copy);
            }
        }
        JSONArray attRefs = uploadBlobs(keys, atts);
        long n = lng(SyncStore.meta(d, "snap_no")) + 1;
        String name = keys.fileName("s-", dev + ":" + n);
        JSONObject s = new JSONObject();
        s.put("vault", keys.vaultId);
        s.put("dev", dev);
        s.put("at", Clock.now());
        s.put("cursors", cursors);
        s.put("hashes", hashes);
        s.put("zip", Base64.getEncoder().encodeToString(zip));
        s.put("fmt", FORMAT);
        s.put("atts", attRefs);
        folder.write(name, keys.sealFile("s", name, s.toString().getBytes(StandardCharsets.UTF_8)));
        JSONObject ptr = new JSONObject();
        ptr.put("name", name);
        ptr.put("at", s.getLong("at"));
        ptr.put("seq", mySeq);
        // 10.4: ما تشمله اللقطة من حزم كل جهاز (يحدد ما يمكن حذفه بأمان)
        ptr.put("cur", cursors);
        SQLiteDatabase w = db.getWritableDatabase();
        SyncStore.setMeta(w, "snap", ptr.toString());
        SyncStore.setMeta(w, "snap_no", "" + n);
        SyncStore.setMeta(w, "snap_seq", "" + mySeq);
        SyncStore.setMeta(w, "snap_fmt", "" + FORMAT);
        SyncStore.setMeta(w, "snap_sum", "" + progress(w));
        SyncStore.setMeta(w, "snap_at", "" + s.getLong("at"));
    }

    /** 10.4: مجموع ما أرسله هذا الجهاز وما استلمه من الكل (يقيس تقدم الدفتر منذ آخر لقطة). */
    static long progress(SQLiteDatabase d) {
        long n = lng(SyncStore.meta(d, "my_seq"));
        for (Long v : SyncStore.cursors(d).values()) n += v;
        return n;
    }

    /** 10.4: مصدر النسخة نفسه بلا صور (لقطة المزامنة). */
    static BackupFormat.Source withoutAttachments(final BackupFormat.Source src) {
        return new BackupFormat.Source() {
            @Override public Map<String, Long> counts() throws Exception {
                Map<String, Long> m = new LinkedHashMap<>(src.counts());
                m.put("attachments", 0L);
                return m;
            }

            @Override public void rows(String table, BackupFormat.RowSink sink) throws Exception { src.rows(table, sink); }

            @Override public List<BackupFormat.AttachmentRef> attachments() { return new ArrayList<>(); }

            @Override public byte[] attachmentData(BackupFormat.AttachmentRef ref) { return null; }

            @Override public List<String> warnings() { return src.warnings(); }

            @Override public int unreadable() { return src.unreadable(); }
        };
    }

    /**
     * 10.4: يرفع ما ينقص الخزنة من صور (كل صورة مرة واحدة، باسم مشتق من بصمتها) ويرجع مراجعها للقطة:
     * [{id, tx, mime, at, sha, ref}]. صورة تالفة أو مفقودة على هذا الجهاز لا تدخل اللقطة.
     */
    JSONArray uploadBlobs(SyncCrypto.Keys keys, List<Object[]> atts) throws Exception {
        JSONArray out = new JSONArray();
        if (atts.isEmpty()) return out;
        Set<String> there = new HashSet<>(folder.list("a-"));
        SQLiteDatabase d = db.getWritableDatabase();
        for (Object[] a : atts) {
            long id = (Long) a[0];
            File f = a[2] == null ? null : db.attachmentFile((String) a[2]);
            if (f == null || !f.isFile()) continue;
            String sha = SyncStore.blobSha(d, id, f);
            if (sha == null) continue;
            String ref = blobName(keys, sha);
            if (!there.contains(ref)) {
                byte[] plain = Crypto.decBytes(java.nio.file.Files.readAllBytes(f.toPath()));
                folder.write(ref, keys.sealFile("a", ref, plain));
                there.add(ref);
            }
            JSONObject x = new JSONObject();
            x.put("id", id);
            x.put("tx", a[1]);
            x.put("mime", a[3] == null ? "image/jpeg" : a[3]);
            x.put("at", a[4]);
            x.put("sha", sha);
            x.put("ref", ref);
            out.put(x);
        }
        return out;
    }

    // ===================================================================================
    // الإرسال
    // ===================================================================================

    /** يختم التغييرات المكتملة في حزمة جديدة محفوظة محليًا (تُرفع بعدها). يرجع رقمها أو 0. */
    long sealPending() throws Exception {
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            Hlc clock = clock(d);
            String h = clock.now(Clock.now());
            JSONArray ops = SyncStore.seal(db, d, h);
            if (ops == null) {
                d.setTransactionSuccessful();
                return 0;
            }
            SyncStore.setMeta(d, "hlc", h);
            String dev = SyncStore.meta(d, "device");
            long seq = lng(SyncStore.meta(d, "my_seq")) + 1;
            JSONObject p = new JSONObject();
            p.put("v", FORMAT);
            p.put("vault", keys.vaultId);
            p.put("dev", dev);
            p.put("seq", seq);
            p.put("prev", nz(SyncStore.meta(d, "my_hash")));
            p.put("hlc", h);
            p.put("at", Clock.now());
            p.put("schema", Db.VER);
            p.put("app", BuildInfo.VERSION);
            p.put("deps", new JSONObject(SyncStore.cursors(d)));
            // 10.4: كل صورة في الحزمة مرجع لملفها المستقل (يُرفع قبل الحزمة)
            JSONArray blobs = new JSONArray();
            for (int i = 0; i < ops.length(); i++) {
                JSONObject op = ops.getJSONObject(i);
                JSONObject r = op.optJSONObject("r");
                if (!"attachments".equals(op.optString("t")) || r == null || !r.has("_sha")) continue;
                String ref = blobName(keys, r.getString("_sha"));
                r.put("_ref", ref);
                blobs.put(new JSONArray().put(ref).put(r.optLong("id")));
            }
            p.put("ops", ops);
            String name = keys.fileName("p-", dev + ":" + seq);
            byte[] sealed = keys.sealFile("p", name, p.toString().getBytes(StandardCharsets.UTF_8));
            String hash = SyncCrypto.hex(SyncCrypto.sha256(sealed));
            d.execSQL("INSERT INTO sync_outbox(seq,name,data,hash,uploaded,created_at,blobs) VALUES(?,?,?,?,0,?,?)",
                    new Object[]{seq, name, sealed, hash, Clock.now(), blobs.length() == 0 ? null : blobs.toString()});
            SyncStore.setMeta(d, "my_seq", "" + seq);
            SyncStore.setMeta(d, "my_hash", hash);
            d.setTransactionSuccessful();
            return seq;
        } finally {
            d.endTransaction();
        }
    }

    static Hlc clock(SQLiteDatabase d) {
        Hlc c = new Hlc(SyncStore.meta(d, "device"));
        c.restore(SyncStore.meta(d, "hlc"));
        return c;
    }

    /**
     * يرفع الحزم المحفوظة (الرفع قبل العلامة: التكرار آمن لأن المحتوى نفسه بالاسم نفسه).
     * 10.4: قائمة المكان تُقرأ مرة واحدة (لا قراءة لكل ملف قبل كتابته)، وصور الحزمة تُرفع قبلها.
     */
    int upload() throws Exception {
        SQLiteDatabase d = db.getWritableDatabase();
        List<Object[]> rows = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT seq,name,data,blobs FROM sync_outbox WHERE uploaded=0 ORDER BY seq", null)) {
            while (c.moveToNext()) rows.add(new Object[]{c.getLong(0), c.getString(1), c.getBlob(2), c.getString(3)});
        }
        if (rows.isEmpty()) return 0;
        SyncCrypto.Keys keys = keys();
        Set<String> present = new HashSet<>(folder.list(""));
        int n = 0;
        for (Object[] r : rows) {
            String name = (String) r[1];
            byte[] data = (byte[]) r[2];
            if (r[3] != null) {
                JSONArray bl = new JSONArray((String) r[3]);
                for (int i = 0; i < bl.length(); i++) {
                    String ref = bl.getJSONArray(i).getString(0);
                    if (present.contains(ref)) continue;
                    byte[] plain = attachmentBytes(bl.getJSONArray(i).getLong(1));
                    if (plain == null) continue;   // حُذفت الصورة هنا قبل رفعها: المستلم يطبّق الحركة دونها بعد مهلة
                    folder.write(ref, keys.sealFile("a", ref, plain));
                    present.add(ref);
                }
            }
            byte[] there = present.contains(name) ? folder.read(name) : null;
            if (there != null && !Arrays.equals(there, data)) {
                throw new IllegalStateException("في المجلد حزمة بالرقم نفسه من هذا الجهاز بمحتوى مختلف: يبدو أن بيانات هذا الجهاز استُعيدت من نسخة قديمة. "
                        + "أوقف المزامنة على هذا الجهاز ثم انضم من جديد.");
            }
            if (there == null) folder.write(name, data);
            present.add(name);
            d.execSQL("UPDATE sync_outbox SET uploaded=1 WHERE seq=" + r[0]);
            n++;
        }
        // تبقى آخر 50 حزمة مرفوعة محليًا فقط
        d.execSQL("DELETE FROM sync_outbox WHERE uploaded=1 AND seq<(SELECT MAX(seq)-50 FROM sync_outbox)");
        return n;
    }

    /** 10.4: محتوى صورة محلية مكشوفًا، أو null إن لم تعد موجودة. */
    byte[] attachmentBytes(long attId) {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT path FROM attachments WHERE id=?", new String[]{"" + attId})) {
            if (!c.moveToFirst() || c.getString(0) == null) return null;
            File f = db.attachmentFile(c.getString(0));
            if (!f.isFile()) return null;
            return Crypto.decBytes(java.nio.file.Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 10.4: يجهّز صور الحزمة قبل تطبيقها: كل مرجع صورة غير موجودة هنا يُنزَّل ويُتحقق من بصمته ويُضمَّن في العملية.
     * يرجع false إن لم تصل صورة بعد (تنتظر الحزمة الجولة التالية). صورة غابت أكثر من 3 أيام تُطبَّق الحركة دونها مع تنبيه.
     */
    boolean prepareBlobs(SyncCrypto.Keys keys, JSONObject p, String fromDev, Result res) throws Exception {
        JSONArray ops = p.getJSONArray("ops");
        SQLiteDatabase d = db.getReadableDatabase();
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.getJSONObject(i);
            JSONObject r = op.optJSONObject("r");
            if (!"attachments".equals(op.optString("t")) || !"U".equals(op.optString("o")) || r == null || !r.has("_ref") || r.has("_file")) continue;
            boolean have = false;
            try (Cursor c = d.rawQuery("SELECT path FROM attachments WHERE id=?", new String[]{"" + r.optLong("id")})) {
                if (c.moveToFirst() && c.getString(0) != null && db.attachmentFile(c.getString(0)).isFile()) have = true;
            }
            if (have) continue;
            String ref = r.getString("_ref");
            byte[] sealed = folder.read(ref);
            if (sealed == null) {
                if (Clock.now() - p.optLong("at") < BLOB_WAIT_MS) return false;
                SyncStore.conflict(db.getWritableDatabase(), "IMAGE_MISSING", "attachments", "" + r.optLong("id"),
                        "صورة الحركة " + r.optLong("tx_id") + " لم تصل من الجهاز الآخر؛ سُجّلت الحركة دونها", fromDev);
                res.errors.add("صورة الحركة " + r.optLong("tx_id") + " لم تصل؛ سُجّلت الحركة دونها.");
                continue;
            }
            byte[] plain = keys.openFile("a", ref, sealed);
            if (!r.optString("_sha").equals(SyncCrypto.hex(SyncCrypto.sha256(plain)))) throw new IOException("صورة تالفة في الخزنة للحركة " + r.optLong("tx_id"));
            r.put("_file", Base64.getEncoder().encodeToString(plain));
        }
        return true;
    }

    // ===================================================================================
    // الاستلام
    // ===================================================================================

    /** نتيجة جولة مزامنة. */
    public static final class Result {
        public int sent, received, rows, duplicates, conflicts, waiting, dupTx;
        /** 10.16: تنبيهات للمراجعة (تعديل لم يُطبَّق، تغيّر صلاحيات)، وأخبار الأجهزة (دخل/خرج) نصوصًا جاهزة للإشعار. */
        public int notices;
        public final List<String> news = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public String digestAlert = "";
        public JSONArray peers = new JSONArray();
        /** 10.2: أرقام الحركات الجديدة التي وصلت في هذه الجولة. */
        public final List<Long> newTx = new ArrayList<>();
        public String note = "";
        /** 10.4: ما حذفه التنظيف في هذه الجولة (أو سبب تعذره)، أو فارغ. */
        public String cleaned = "";
        /** 11.5: لكل جهاز: كم حزمة وصلت منه في هذه الجولة، وأول خطأ معه؛ واسم الجهاز صاحب أول خطأ. */
        public final Map<String, Integer> gotFrom = new LinkedHashMap<>();
        public final Map<String, String> errFrom = new LinkedHashMap<>();
        public String firstErrWith = "";

        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append("أُرسلت ").append(sent).append(" حزمة، ووصلت ").append(received);
            if (rows > 0) b.append(" (").append(rows).append(" تغييرًا)");
            if (duplicates > 0) b.append("، وتُجوهل ").append(duplicates).append(" إلغاءً مكررًا");
            if (dupTx > 0) b.append("، و").append(dupTx).append(" حركة قد تكون مكررة (راجعها)");
            if (conflicts - dupTx > 0) b.append("، و").append(conflicts - dupTx).append(" تعارضًا للمراجعة");
            if (notices > 0) b.append("، و").append(notices).append(" تنبيهًا للمراجعة");
            if (waiting > 0) b.append("، و").append(waiting).append(" حزمة تنتظر حزمًا من جهاز آخر");
            for (String n : news) b.append("\n• ").append(n);
            return b.toString();
        }

        /** 10.16: هل في الجولة ما يستحق إشعارًا (وصل شيء، أو تنبيه، أو خبر عن جهاز)؟ */
        public boolean notable() { return received > 0 || notices > 0 || !news.isEmpty(); }

        /** تعارضات وتنبيهات للمراجعة غير الحركات المكررة. */
        public int review() { return Math.max(0, conflicts - dupTx) + notices; }
    }

    int pull(Result res) throws Exception {
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        String me = SyncStore.meta(d, "device");
        long myPrefix = db.syncPrefix;
        Map<String, JSONObject> members = readMembers(keys);
        // 10.4: جهاز خرج بإعادة التعيين: تُستلم حزمه حتى آخرها (upTo) ثم يتوقف
        // 10.5 (AUDIT-001): وكذلك الجهاز الذي أزاله المدير، ولو حُذفت بطاقته؛ ولا يُقبل منه شيء بعد upTo
        Map<String, JSONObject> raw = null;
        // إن كان هذا الجهاز قد استلم من المُزال أبعد مما رآه من أزاله (تأخر وصول الملفات إلى ذلك الجهاز) يرفع upTo إلى ما استلمه،
        // فيصل الجميع إلى الحزم نفسها (السجلات تُدمج بالأبعد)
        Map<String, JSONObject> remNow = removedMap();
        boolean raised = false;
        Map<String, Long> curNow = SyncStore.cursors(d);
        for (JSONObject x : remNow.values()) {
            Long have = curNow.get(x.optString("dev"));
            if (x.has("upTo") && have != null && have > x.optLong("upTo")) {
                x.put("upTo", have);
                raised = true;
            }
        }
        if (raised) saveRemoved(remNow);
        Map<String, Long> caps = removedCaps();
        for (JSONObject x : removedMap().values()) {
            long upTo = x.optLong("upTo", 0);
            if (upTo <= 0) continue;
            Long have = SyncStore.cursors(d).get(x.optString("dev"));
            if ((have == null ? 0 : have) >= upTo) continue;
            if (raw == null) raw = readMembersRaw(keys);
            JSONObject m = raw.get(x.optString("dev"));
            if (m == null) {
                m = new JSONObject();
                m.put("dev", x.optString("dev"));
                m.put("name", x.optString("name"));
                m.put("seq", upTo);
            }
            members.put(x.optString("dev"), m);
        }
        int applied = 0;
        boolean progress = true;
        Set<String> blocked = new HashSet<>();
        Map<String, String> why = new java.util.HashMap<>();
        while (progress) {
            progress = false;
            for (String dev : members.keySet()) {
                if (dev.equals(me) || blocked.contains(dev)) continue;
                while (true) {
                    long cur = 0;
                    String curHash = "";
                    try (Cursor c = d.rawQuery("SELECT seq,hash FROM sync_cursor WHERE dev=?", new String[]{dev})) {
                        if (c.moveToFirst()) {
                            cur = c.getLong(0);
                            curHash = c.getString(1);
                        }
                    }
                    long next = cur + 1;
                    if (caps.containsKey(dev) && next > caps.get(dev)) break;   // أُزيل: لا شيء بعد آخر حزمة له
                    String name = keys.fileName("p-", dev + ":" + next);
                    byte[] sealed = folder.read(name);
                    if (sealed == null) {
                        why.put(dev, "missing");
                        break;
                    }
                    JSONObject p;
                    try {
                        p = new JSONObject(new String(keys.openFile("p", name, sealed), StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        devError(res, dev, "حزمة تالفة أو معدَّلة من جهاز «" + members.get(dev).optString("name") + "» (رقم " + next + "): لم تُطبَّق. "
                                + "قد يكون وصولها لم يكتمل بعد.");
                        why.put(dev, "bad");
                        blocked.add(dev);
                        break;
                    }
                    if (!dev.equals(p.optString("dev")) || p.optLong("seq") != next || !keys.vaultId.equals(p.optString("vault"))) {
                        devError(res, dev, "حزمة في غير مكانها من جهاز «" + members.get(dev).optString("name") + "»: لم تُطبَّق.");
                        why.put(dev, "bad");
                        blocked.add(dev);
                        break;
                    }
                    if (!curHash.equals(p.optString("prev"))) {
                        devError(res, dev, "سلسلة حزم جهاز «" + members.get(dev).optString("name") + "» منقطعة عند الحزمة " + next
                                + " (حُذفت حزمة أو عُدّلت): توقفت مزامنة هذا الجهاز للحماية.");
                        why.put(dev, "chain");
                        blocked.add(dev);
                        break;
                    }
                    if (p.optInt("schema") > Db.VER) {
                        devError(res, dev, "جهاز «" + members.get(dev).optString("name") + "» يعمل بإصدار أحدث (" + p.optString("app") + "). حدّث التطبيق هنا لتصل حزمه.");
                        why.put(dev, "newer");
                        blocked.add(dev);
                        break;
                    }
                    if (!depsMet(d, p.optJSONObject("deps"), me)) {
                        why.put(dev, "waiting");
                        res.waiting++;
                        break;
                    }
                    try {
                        if (!prepareBlobs(keys, p, dev, res)) {
                            why.put(dev, "image");
                            break;
                        }
                    } catch (Exception e) {
                        devError(res, dev, "صورة في حزمة من جهاز «" + members.get(dev).optString("name") + "» لم تُقرأ: " + e.getMessage());
                        why.put(dev, "bad");
                        blocked.add(dev);
                        break;
                    }
                    SyncStore.Applied a;
                    d.beginTransaction();
                    try {
                        SyncStore.capture(d, false);
                        Hlc clock = clock(d);
                        clock.observe(p.getString("hlc"), Clock.now());
                        SyncStore.setMeta(d, "hlc", Hlc.format(clock.wall, clock.counter, me));
                        a = SyncStore.apply(db, d, p.getJSONArray("ops"), p.getString("hlc"), dev, myPrefix);
                        // 10.4: بصمات الصور التي وصلت (لا تُحسب من جديد عند اللقطة)
                        JSONArray ops = p.getJSONArray("ops");
                        for (int i = 0; i < ops.length(); i++) {
                            JSONObject r = ops.getJSONObject(i).optJSONObject("r");
                            if ("attachments".equals(ops.getJSONObject(i).optString("t")) && r != null && r.has("_sha") && r.has("_file"))
                                d.execSQL("INSERT OR REPLACE INTO sync_blobs(att_id,sha) VALUES(?,?)", new Object[]{r.optLong("id"), r.getString("_sha")});
                        }
                        d.execSQL("INSERT OR REPLACE INTO sync_cursor(dev,seq,hash) VALUES(?,?,?)", new Object[]{dev, next, SyncCrypto.hex(SyncCrypto.sha256(sealed))});
                        SyncStore.capture(d, true);
                        d.setTransactionSuccessful();
                    } finally {
                        d.endTransaction();
                    }
                    why.remove(dev);
                    res.received++;
                    res.gotFrom.merge(dev, 1, Integer::sum);
                    res.rows += a.rows;
                    res.duplicates += a.duplicates;
                    res.conflicts += a.conflicts;
                    res.dupTx += a.dupTx;
                    res.notices += a.notices;
                    res.newTx.addAll(a.newTx);
                    applied++;
                    progress = true;
                    // تعارض تعديل خسره هذا الجهاز: يلغي حركته بقيد عكسي عادي يصل للجميع
                    for (long loser : a.loseMine) {
                        boolean sys = db.isSystem();
                        db.setSystem(true);
                        try {
                            Model.Tx t = db.tx(loser);
                            if (t != null && Model.STATUS_ACTIVE.equals(t.status)) db.cancelTx(loser, "تعارض مزامنة: عُدّلت الحركة الأصلية من جهاز آخر أولًا");
                            else if (t != null && Model.STATUS_PENDING.equals(t.status)) db.rejectTx(loser, "تعارض مزامنة");
                        } catch (Exception e) {
                            res.errors.add("تعذر إلغاء الحركة المتعارضة " + loser + ": " + e.getMessage());
                        } finally {
                            db.setSystem(sys);
                        }
                    }
                }
            }
        }
        // تشخيص لكل جهاز: كم حزمة أعلنها، وكم وصل منها هنا، ولماذا توقف الباقي
        JSONArray peers = new JSONArray();
        Map<String, Long> cur = SyncStore.cursors(d);
        Map<String, JSONObject> removedNow = removedMap();
        for (Map.Entry<String, JSONObject> e : members.entrySet()) {
            if (e.getKey().equals(me) || removedNow.containsKey(e.getKey())) continue;
            JSONObject x = new JSONObject();
            long have = cur.containsKey(e.getKey()) ? cur.get(e.getKey()) : 0, announced = e.getValue().optLong("seq");
            x.put("dev", e.getKey());
            x.put("announced", announced);
            x.put("have", have);
            String w = why.get(e.getKey());
            x.put("state", have >= announced ? (w == null || "missing".equals(w) ? "ok" : w) : (w == null ? "missing" : w));
            peers.put(x);
        }
        res.peers = peers;
        return applied;
    }

    /** نص حالة جهاز آخر للواجهة (من تشخيص آخر جولة). */
    public static String peerText(JSONObject p, String provider) {
        if (p == null) return "";
        long a = p.optLong("announced"), h = p.optLong("have");
        String base = "وصل هنا " + h + " من " + a + " حزمة";
        switch (p.optString("state")) {
            case "ok": return h >= a ? "✓ كل حزمه وصلت (" + h + ")" : base;
            case "missing": return base + " — الحزمة " + (h + 1) + " لم تظهر في المجلد على هذا الجهاز بعد"
                    + (provider.isEmpty() ? "" : " (" + provider + " لم يُنزّلها)");
            case "waiting": return base + " — تنتظر حزمًا من جهاز آخر أولًا";
            case "image": return base + " — تنتظر وصول صورة مرفقة إلى هذا الجهاز";
            case "bad": return base + " — الحزمة التالية لم يكتمل وصولها أو تالفة؛ يُعاد المحاولة";
            case "chain": return base + " — سلسلة الحزم منقطعة (حُذفت حزمة)؛ توقفت للحماية";
            case "newer": return base + " — من إصدار أحدث؛ حدّث التطبيق هنا";
            default: return base;
        }
    }

    static boolean depsMet(SQLiteDatabase d, JSONObject deps, String me) {
        if (deps == null) return true;
        Map<String, Long> cur = SyncStore.cursors(d);
        Iterator<String> it = deps.keys();
        while (it.hasNext()) {
            String x = it.next();
            if (x.equals(me)) continue;
            Long have = cur.get(x);
            if ((have == null ? 0 : have) < deps.optLong(x)) return false;
        }
        return true;
    }

    // ===================================================================================
    // جولة كاملة
    // ===================================================================================

    /** جولة مزامنة: ختم ← رفع ← استلام ← (ختم ما نتج عن حل التعارض ورفعه) ← بطاقة العضو ← مقارنة البصمات. */
    public Result sync() throws Exception {
        VAULT.lock();
        try {
            return syncLocked();
        } finally {
            VAULT.unlock();
        }
    }

    private Result syncLocked() throws Exception {
        Result res = new Result();
        SQLiteDatabase d = db.getWritableDatabase();
        try {
            roundMembers = null;
            if (!License.current.sync()) throw new Entitlements.LimitReached("المزامنة بين الأجهزة ميزة في النسخة المدفوعة. بياناتك على هذا الجهاز تبقى كما هي.");
            JSONObject h = header();
            if (h == null) {
                SyncStore.setMeta(d, "header_missing", "1");
                throw new IOException("ملف الخزنة غير موجود في " + folder.describe() + ". إن نقلت المزامنة إلى مكان جديد فاضغط «إصلاح» في شاشة المزامنة، "
                        + "أو اختر المكان الصحيح.");
            }
            SyncStore.setMeta(d, "header_missing", null);
            // 11.5: أمر حذف/نقل لا يُنفَّذ إلا إن كان موقّعًا بمفتاح هذه الخزنة (لا خروج ولا مسح لحزم لم تُرسل بسبب ملف معدَّل)
            if (tombstone(h) && h.optString("vault").equals(SyncStore.meta(d, "vault_id")) && !tombVerified(h, keys().vault)) {
                SyncStore.setMeta(d, "tomb_unverified", "1");
                throw new IllegalStateException(TOMB_UNVERIFIED);
            }
            SyncStore.setMeta(d, "tomb_unverified", null);
            // 10.14: حُذفت الخزنة من هنا: يخرج هذا الجهاز من المزامنة (بياناته تبقى)؛ نُقلت: لا يُرسل إلى المكان القديم ويطلب الانتقال
            if (h.has("deleted") && h.optString("vault").equals(SyncStore.meta(d, "vault_id"))) {
                leave(tombText(h));
                throw new IllegalStateException(tombText(h) + " خرج هذا الجهاز من المزامنة وبياناته باقية كما هي. خذ نسخة احتياطية، ثم أنشئ مزامنة جديدة أو انضم إليها.");
            }
            if (h.has("moved")) {
                boolean fresh = h.optJSONObject("moved") != null && h.optJSONObject("moved").optBoolean("fresh");
                SyncStore.setMeta(d, "moved_to", tombText(h));
                SyncStore.setMeta(d, "moved_fresh", fresh ? "1" : null);
                SyncStore.setMeta(d, "moved_target", h.optJSONObject("moved") == null ? null : h.optJSONObject("moved").optString("to", null));
                throw new IllegalStateException(tombText(h) + (fresh
                        ? " أوقف المزامنة هنا ثم انضم إلى المزامنة الجديدة (تُستبدل بيانات هذا الجهاز ببياناتها؛ خذ نسخة احتياطية قبلها)."
                        : " لا يُرسل هذا الجهاز شيئًا إلى المكان القديم. من شاشة «المزامنة» سجّل الدخول بالحساب أو المكان الجديد."));
            }
            SyncStore.setMeta(d, "moved_to", null);
            SyncStore.setMeta(d, "moved_fresh", null);
            SyncStore.setMeta(d, "moved_target", null);
            if (!h.toString().equals(SyncStore.meta(d, "header_copy"))) SyncStore.setMeta(d, "header_copy", h.toString());
            if (!h.optString("vault").equals(SyncStore.meta(d, "vault_id"))) throw new IllegalStateException("المجلد فيه خزنة أخرى غير خزنة هذا الجهاز");
            // 10.4/10.5: ترقية الخزنة إلى الصيغة الحالية (الأجهزة بإصدار أقدم يُطلب منها التحديث)
            if (h.optInt("v") < FORMAT) {
                h.put("v", FORMAT);
                folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
                SyncStore.setMeta(d, "header_copy", h.toString());
                db.audit("SYNC_UPGRADE", "sync", 0, "رُقّيت خزنة المزامنة إلى الصيغة " + FORMAT);
            }
            // 10.4: جهاز أزاله المدير من المزامنة يخرج منها (بياناته تبقى)
            roundMembers = readMembersRaw(keys());
            readMembers(keys());
            JSONObject meRemoved = removedMap().get(SyncStore.meta(d, "device"));
            if (meRemoved != null) {
                // 10.5 (AUDIT-001): ما أرسله قبل الإزالة (حتى upTo) يصل للجميع؛ ما بعده لا يُرسل. يُذكر عدده صراحة ولا يُحذف من هنا.
                long mySeq = lng(SyncStore.meta(d, "my_seq")), upTo = meRemoved.optLong("upTo", mySeq);
                long unsealed = 0;
                try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM sync_chg", null)) {
                    if (c.moveToFirst()) unsealed = c.getLong(0);
                }
                long lost = Math.max(0, mySeq - upTo);
                String extra = lost + unsealed == 0 ? ""
                        : " لم يصل إلى الأجهزة الأخرى " + (lost > 0 ? lost + " حزمة" : "") + (lost > 0 && unsealed > 0 ? " و" : "")
                        + (unsealed > 0 ? unsealed + " تغييرًا لم يُختم" : "") + " من هذا الجهاز: هي باقية هنا فقط. خذ نسخة احتياطية قبل أي انضمام جديد"
                        + " (الانضمام يستبدل بيانات هذا الجهاز ببيانات الخزنة) وسجّل تلك الحركات من جديد على جهاز في المزامنة.";
                d.beginTransaction();
                try {
                    SyncStore.leave(d, "أزال المدير هذا الجهاز من المزامنة. لإعادته: «الانضمام» من جديد." + extra);
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
                db.syncPrefix = 0;
                db.audit("SYNC_REMOVED_HERE", "sync", 0, "أُخرج هذا الجهاز من المزامنة لأن المدير أزاله." + extra);
                throw new IllegalStateException("أزال المدير هذا الجهاز من المزامنة. بياناته باقية عليه؛ لإعادته انضم من جديد." + extra);
            }
            // 10.1: الجهاز الرئيسي معروف للجميع (خزنات 10.0: يكتبه منشئها في الرأس عند أول مزامنة)
            if (h.optString("founder").isEmpty()) {
                if (SyncStore.meta(d, "founder") == null && SyncStore.founder(d)) {
                    h.put("founder", SyncStore.meta(d, "device"));
                    folder.write(HEADER, h.toString(1).getBytes(StandardCharsets.UTF_8));
                    SyncStore.setMeta(d, "founder", h.getString("founder"));
                }
            } else if (!h.optString("founder").equals(SyncStore.meta(d, "founder"))) {
                // 10.5 (AUDIT-002): انتقل الدور (نقلًا أو استلامًا): الرأس هو المرجع، فلا يبقى جهازان رئيسيان
                String was = SyncStore.meta(d, "founder"), now = h.optString("founder"), me = SyncStore.meta(d, "device");
                SyncStore.setMeta(d, "founder", now);
                if (now.equals(me)) SyncStore.setMeta(d, "secrets_sent", "1");
                if (was != null) {
                    String txt = me.equals(was) ? "لم يعد هذا الجهاز هو الجهاز الرئيسي (" + h.optString("founder_how") + ")"
                            : now.equals(me) ? "أصبح هذا الجهاز هو الجهاز الرئيسي (" + h.optString("founder_how") + ")"
                            : "تغيّر الجهاز الرئيسي (" + h.optString("founder_how") + ")";
                    db.audit("SYNC_FOUNDER", "sync", 0, txt);
                    log(d, txt, "");
                }
            }
            // 12.7: خزنة أقدم من 12.7: الجهاز الرئيسي يُنشئ «هوية المكتب» فتصل لكل الأجهزة
            if (SyncStore.founder(d) && LicenseGuard.currentUid(db).isEmpty()) LicenseGuard.officeUid(db);
            if (SyncStore.founder(d) && !"1".equals(SyncStore.meta(d, "secrets_sent"))) {
                // مرة واحدة: سرّ الرموز وسرّ بصمة اليوم من الجهاز الرئيسي إلى الأجهزة المنضمة (ولو أُنشئا بعد اللقطة)
                for (String k : Db.ENCRYPTED_SETTINGS) d.execSQL("INSERT INTO sync_chg(tbl,rid,op) SELECT 'settings',k,'U' FROM settings WHERE k=?", new Object[]{k});
                SyncStore.setMeta(d, "secrets_sent", "1");
            }
            if (sealPending() > 0) { /* حزمة جديدة */ }
            res.sent += upload();
            pull(res);
            if (sealPending() > 0) res.sent += upload();
            long mySeq = lng(SyncStore.meta(d, "my_seq")), snapSeq = lng(SyncStore.meta(d, "snap_seq"));
            // 10.4: لقطة بالصيغة الجديدة عند أول جولة بعد التحديث (بلا صور، وتسمح بحذف القديم)
            // وكذلك كلما استلم الجهاز 150 حزمة من غيره: لقطة كل جهاز تتقدم فلا تمنع حذف حزم الآخرين
            // 12.7: بقيت القاعدة كما هي (كل 150 حزمة): حذف الحزم القديمة يحتاج لقطة حديثة من كل جهاز، وتقليلها كان يكبّر الخزنة.
            // ما تغيّر: اللقطة تُبنى من نسخة من الملف خارج المعاملة، فلا تنتظر الشاشة ولا الحركات الجديدة.
            if (mySeq - snapSeq >= SNAPSHOT_EVERY || progress(d) - lng(SyncStore.meta(d, "snap_sum")) >= SNAPSHOT_EVERY
                    || !String.valueOf(FORMAT).equals(SyncStore.meta(d, "snap_fmt"))) writeSnapshot();
            writeMember();
            try {
                String gc = clean(false);
                if (!gc.isEmpty()) res.cleaned = gc;
            } catch (Exception e) {
                res.cleaned = "تعذر التنظيف: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            res.digestAlert = compareDigests();
            SyncStore.setMeta(d, "digest_alert", res.digestAlert.isEmpty() ? null : res.digestAlert);
            SyncStore.setMeta(d, "last_sync", "" + Clock.now());
            SyncStore.setMeta(d, "last_error", res.errors.isEmpty() ? null : res.errors.get(0));
            if (!res.errors.isEmpty()) {
                SyncStore.setMeta(d, "last_error_at", "" + Clock.now());
                SyncStore.setMeta(d, "last_error_with", res.firstErrWith.isEmpty() ? "هذا الجهاز" : res.firstErrWith);
            }
            // 11.5: سجل كل جهاز: ما وصل منه وأخطاؤه، وجولة هذا الجهاز
            java.util.Set<String> touched = new java.util.LinkedHashSet<>(res.gotFrom.keySet());
            touched.addAll(res.errFrom.keySet());
            for (String dv : touched) {
                Integer n = res.gotFrom.get(dv);
                devLog(d, dv, n == null ? "لم يصل شيء منه" : "وصلت منه " + n + " حزمة", res.errFrom.get(dv));
            }
            if (res.sent > 0 || res.received > 0 || !res.errors.isEmpty())
                devLog(d, SyncStore.meta(d, "device"), "أرسل " + res.sent + " حزمة واستلم " + res.received, res.errFrom.isEmpty() && !res.errors.isEmpty() ? res.errors.get(0) : "");
            res.note = folder.note();
            SyncStore.setMeta(d, "peers", res.peers.toString());
            SyncStore.setMeta(d, "folder_note", res.note.isEmpty() ? null : res.note);
            log(d, res.summary() + (res.note.isEmpty() ? "" : " • " + res.note), res.errors.isEmpty() ? "" : res.errors.get(0));
            db.reloadClock();   // 10.18: توقيت المكتب قد يصل من جهاز آخر
            res.news.addAll(cacheMembers());
            if (res.received > 0 || res.sent > 0) db.audit("SYNC", "sync", 0, res.summary());
            for (String n : res.news) db.audit("SYNC_DEVICE", "sync", 0, n);
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            SyncStore.setMeta(d, "last_error", m);
            SyncStore.setMeta(d, "last_error_at", "" + Clock.now());
            SyncStore.setMeta(d, "last_error_with", placeName(folder) + " — من هذا الجهاز");
            log(d, "فشلت الجولة", m);
            devLog(d, SyncStore.meta(d, "device"), "فشلت الجولة", m);
            throw e;
        } finally {
            roundMembers = null;
        }
        return res;
    }

    // ===================================================================================
    // 10.4: التنظيف التلقائي، وإزالة جهاز، وحجم المكان
    // ===================================================================================

    /** أقل وقت بين تنظيفين تلقائيين. */
    static final long CLEAN_EVERY_MS = 6L * 3600 * 1000;
    /** أكثر عدد ملفات يُحذف في جولة واحدة (الباقي في الجولات التالية). */
    static final int CLEAN_MAX = 3000;

    /**
     * يحذف هذا الجهاز من ملفاته فقط ما لم يعد يحتاجه أحد:
     * <ul>
     *   <li>حزمه التي استلمتها كل الأجهزة النشطة وتشملها لقطة كل جهاز (فلا يحتاجها جهاز ينضم من أي لقطة).</li>
     *   <li>لقطاته القديمة (تبقى آخر اثنتين).</li>
     *   <li>الجهاز الرئيسي وحده: الصور التي لا تشير إليها أي حركة ولا أي لقطة منذ أسبوع على الأقل.</li>
     * </ul>
     * جهاز غائب يمنع حذف ما لم يستلمه: يظهر تنبيه عنه بعد 30 يومًا، ويستطيع المدير إزالته.
     * يرجع وصفًا لما حُذف، أو فارغًا.
     */
    public String clean(boolean force) throws Exception {
        VAULT.lock();
        try {
            return cleanLocked(force);
        } finally {
            VAULT.unlock();
        }
    }

    private String cleanLocked(boolean force) throws Exception {
        if (!folder.canDelete()) return "";
        SQLiteDatabase d = db.getWritableDatabase();
        long now = Clock.now();
        if (!force && now - lng(SyncStore.meta(d, "gc_at")) < CLEAN_EVERY_MS) return "";
        SyncCrypto.Keys keys = keys();
        String me = SyncStore.meta(d, "device");
        Map<String, JSONObject> members = readMembers(keys);
        // 10.18: لا حذف من الخزنة وبطاقات الأجهزة غير مكتملة، ولا وجهاز معروف غائبة بطاقته: قد يُحذف ما لم يستلمه بعد
        boolean missing = membersIncomplete;
        try {
            JSONArray pa = new JSONArray(nz(SyncStore.meta(d, "members_cache")).isEmpty() ? "[]" : SyncStore.meta(d, "members_cache"));
            Map<String, JSONObject> gone = removedMap();
            for (int i = 0; i < pa.length(); i++) {
                String dv = pa.getJSONObject(i).optString("dev");
                if (!dv.isEmpty() && !dv.equals(me) && !members.containsKey(dv) && !gone.containsKey(dv)) missing = true;
            }
        } catch (Exception ignored) {
            // بلا قائمة سابقة
        }
        if (missing) {
            SyncStore.setMeta(d, "gc_note", Model.date(now) + ": أُجّل الحذف — بطاقة أحد الأجهزة لم تُقرأ في هذه الجولة");
            return "";
        }
        int packets = 0, snaps = 0, blobs = 0;
        // 1) الحزم: الحد = أقل ما استلمته الأجهزة النشطة منها، وأقل ما تشمله لقطاتها، ولقطتي أنا
        long limit = lng(SyncStore.meta(d, "snap_seq"));
        String why = "";
        for (JSONObject m : members.values()) {
            if (m.optString("dev").equals(me)) continue;
            JSONObject c = m.optJSONObject("cursors");
            long got = c == null ? 0 : c.optLong(me, 0);
            if (got < limit) {
                limit = got;
                why = m.optString("name");
            }
            JSONObject sp = m.optJSONObject("snap");
            if (sp != null) {
                JSONObject cur = sp.optJSONObject("cur");
                long inSnap = cur == null ? 0 : cur.optLong(me, 0);
                if (inSnap < limit) {
                    limit = inSnap;
                    why = m.optString("name");
                }
            }
        }
        long from = lng(SyncStore.meta(d, "gc_seq"));
        long upTo = Math.min(limit, from + CLEAN_MAX);
        try {
            for (long q = from + 1; q <= upTo; q++) {
                folder.delete(keys.fileName("p-", me + ":" + q));
                packets++;
                if (q % 100 == 0) SyncStore.setMeta(d, "gc_seq", "" + q);
            }
            if (upTo > from) SyncStore.setMeta(d, "gc_seq", "" + upTo);
            // 2) لقطاتي القديمة (تبقى آخر اثنتين)
            long snapNo = lng(SyncStore.meta(d, "snap_no")), sg = lng(SyncStore.meta(d, "snap_gc"));
            for (long n = sg + 1; n <= snapNo - 2; n++) {
                folder.delete(keys.fileName("s-", me + ":" + n));
                snaps++;
            }
            if (snapNo - 2 > sg) SyncStore.setMeta(d, "snap_gc", "" + (snapNo - 2));
            // 3) ملفات الأجهزة التي خرجت بإعادة التعيين بعد أن تصل حزمها للجميع (الجهاز الرئيسي فقط)
            if (SyncStore.founder(d)) packets += purgeRetired(keys, members, me);
            // 4) الصور بلا مرجع (الجهاز الرئيسي فقط، مرة في اليوم، وحين يكون قد استلم كل شيء)
            if (SyncStore.founder(d) && (force || now - lng(SyncStore.meta(d, "gc_blobs_at")) > 24L * 3600 * 1000)) blobs = cleanBlobs(keys, members, me);
        } catch (HttpSyncFolder.OldServer e) {
            SyncStore.setMeta(d, "gc_at", "" + now);
            SyncStore.setMeta(d, "gc_note", e.getMessage());
            return e.getMessage();
        }
        SyncStore.setMeta(d, "gc_at", "" + now);
        String out = packets + snaps + blobs == 0 ? "" : "حُذف " + packets + " حزمة قديمة و" + snaps + " لقطة قديمة و" + blobs + " صورة بلا مرجع";
        String held = limit < lng(SyncStore.meta(d, "my_seq")) - SNAPSHOT_EVERY && !why.isEmpty() ? "ينتظر بعض الحذف استلام «" + why + "»" : "";
        String note = Model.date(now) + ": " + (out.isEmpty() ? "لا شيء يُحذف" : out) + (held.isEmpty() ? "" : " • " + held);
        SyncStore.setMeta(d, "gc_note", note);
        if (!out.isEmpty()) db.audit("SYNC_CLEAN", "sync", 0, out);
        return out;
    }

    /**
     * 10.4: يحذف ملفات جهاز خرج (بإعادة التعيين أو أزاله المدير) حين تكون كل الأجهزة النشطة قد استلمت حزمه حتى آخرها (upTo).
     * 10.5 (AUDIT-001): وبشرط أن تشملها لقطة الجهاز الرئيسي (يكتب لقطة جديدة إن لزم)؛ والمنضم الجديد يختار لقطة تشمل ما حُذف
     * (انظر {@link #covers}) فلا يبدأ من لقطة أقدم تحتاج حزمًا لم تعد موجودة.
     */
    int purgeRetired(SyncCrypto.Keys keys, Map<String, JSONObject> members, String me) throws Exception {
        Map<String, JSONObject> removed = removedMap();
        int n = 0;
        boolean changed = false, snapped = false;
        Map<String, Long> mine = SyncStore.cursors(db.getReadableDatabase());
        for (JSONObject x : removed.values()) {
            if (!x.has("upTo")) continue;   // إزالة 10.4: حُذفت ملفاته يومها
            long upTo = x.optLong("upTo", 0), done = purgedTo(x);
            if (done >= upTo) continue;
            String dev = x.optString("dev");
            boolean all = (mine.containsKey(dev) ? mine.get(dev) : 0) >= upTo;
            for (JSONObject m : members.values()) {
                if (m.optString("dev").equals(me)) continue;
                JSONObject c = m.optJSONObject("cursors");
                if (c == null || c.optLong(dev, 0) < upTo) all = false;
            }
            if (!all) continue;
            if (snapCur(SyncStore.meta(db.getReadableDatabase(), "snap"), dev) < upTo) {
                if (snapped) continue;
                writeSnapshot();   // لقطة الرئيسي تشمل حزمه الآن: منها يبدأ أي منضم جديد
                snapped = true;
                if (snapCur(SyncStore.meta(db.getReadableDatabase(), "snap"), dev) < upTo) continue;
            }
            for (long q = Math.max(1, done + 1); q <= upTo; q++) folder.delete(keys.fileName("p-", dev + ":" + q));
            if (done < 0) {
                for (long q = 1; q <= x.optLong("snaps", 0) + 1; q++) folder.delete(keys.fileName("s-", dev + ":" + q));
                if (!x.optString("snapName").isEmpty()) folder.delete(x.optString("snapName"));
                folder.delete(keys.fileName("m-", "member:" + dev));
            }
            n += (int) (upTo - Math.max(0, done));
            x.put("purgedTo", upTo);
            x.put("purged", true);
            changed = true;
        }
        if (changed) {
            saveRemoved(removed);
            writeMember();
        }
        return n;
    }

    /** يحذف الصور التي لا يشير إليها شيء منذ أسبوع. يرجع عدد المحذوف. */
    int cleanBlobs(SyncCrypto.Keys keys, Map<String, JSONObject> members, String me) throws Exception {
        SQLiteDatabase d = db.getWritableDatabase();
        Map<String, Long> cur = SyncStore.cursors(d);
        for (JSONObject m : members.values()) {
            String dev = m.optString("dev");
            if (dev.equals(me)) continue;
            Long have = cur.get(dev);
            if ((have == null ? 0 : have) < m.optLong("seq")) return 0;   // لم يستلم كل شيء بعد
        }
        Map<String, long[]> st = folder.stat("a-");
        if (st == null || st.isEmpty()) return 0;
        Set<String> used = new HashSet<>();
        try (Cursor c = d.rawQuery("SELECT id,path FROM attachments", null)) {
            while (c.moveToNext()) {
                String sha = c.getString(1) == null ? null : SyncStore.blobSha(d, c.getLong(0), db.attachmentFile(c.getString(1)));
                if (sha != null) used.add(blobName(keys, sha));
            }
        }
        // صور تشير إليها لقطات الأجهزة الأخرى (قد ينضم جهاز من إحداها)
        for (JSONObject m : members.values()) {
            JSONObject sp = m.optJSONObject("snap");
            if (sp == null) continue;
            if (m.optString("dev").equals(me)) continue;
            byte[] sealed = folder.read(sp.optString("name"));
            if (sealed == null) return 0;   // لم تصل: لا نحذف شيئًا هذه المرة
            JSONObject snap = new JSONObject(new String(keys.openFile("s", sp.optString("name"), sealed), StandardCharsets.UTF_8));
            JSONArray at = snap.optJSONArray("atts");
            if (at != null) for (int i = 0; i < at.length(); i++) used.add(at.getJSONObject(i).optString("ref"));
        }
        long now = Clock.now();
        int n = 0;
        for (Map.Entry<String, long[]> e : st.entrySet()) {
            if (used.contains(e.getKey())) continue;
            long mt = e.getValue()[1];
            if (mt <= 0 || now - mt < BLOB_GRACE_MS) continue;
            folder.delete(e.getKey());
            if (++n >= CLEAN_MAX) break;
        }
        SyncStore.setMeta(d, "gc_blobs_at", "" + now);
        return n;
    }

    /**
     * يزيل جهازًا من المزامنة (ضاع أو لم يعد يُستخدم): لا يعود يمنع التنظيف، وإن عاد يخرج من المزامنة تلقائيًا
     * (بياناته تبقى عليه، ويمكنه الانضمام من جديد). لا يُزال الجهاز الرئيسي ولا هذا الجهاز.
     * <p>10.5 (AUDIT-001): لا يُحذف شيء لحظة الإزالة. يُسجَّل «آخر حزمة» له الموجودة في المكان (upTo): كل جهاز يكمل استلام حزمه
     * حتى upTo بالضبط ثم يتوقف (فلا يضيع ما أرسله قبل إزالته، ولا يُقبل منه شيء بعدها)، ولا يُحذف مؤشره فتبقى مقارنة البصمات
     * صحيحة. حزمه وبطاقته ولقطاته يحذفها الجهاز الرئيسي في التنظيف بعد أن تصل حزمه إلى كل الأجهزة وتشملها لقطاتها.
     * يرجع upTo.
     */
    public long removeDevice(String dev) throws Exception {
        VAULT.lock();
        try {
            return removeDeviceLocked(dev);
        } finally {
            VAULT.unlock();
        }
    }

    private long removeDeviceLocked(String dev) throws Exception {
        db.require(Auth.Perm.SYNC);
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        String me = SyncStore.meta(d, "device");
        if (dev.equals(me)) throw new IllegalArgumentException("لإخراج هذا الجهاز استعمل «إيقاف المزامنة على هذا الجهاز»");
        if (dev.equals(SyncStore.meta(d, "founder"))) throw new IllegalArgumentException("لا يُزال الجهاز الرئيسي من المزامنة. انقل دور الجهاز الرئيسي إلى جهاز آخر أولًا.");
        Map<String, JSONObject> all = readMembersRaw(keys);
        JSONObject m = all.get(dev);
        if (m == null) throw new IllegalArgumentException("الجهاز غير موجود في المزامنة");
        // آخر حزمة: ما أعلنته بطاقته، وما رفعه بعدها قبل أن يكتب بطاقته (سلسلة متصلة في المكان)
        long upTo = m.optLong("seq");
        Set<String> present = new HashSet<>(folder.list("p-"));
        while (present.contains(keys.fileName("p-", dev + ":" + (upTo + 1)))) upTo++;
        Map<String, JSONObject> removed = removedMap();
        JSONObject x = new JSONObject();
        x.put("dev", dev);
        x.put("prefix", m.optLong("prefix"));
        x.put("name", m.optString("name"));
        x.put("at", Clock.now());
        x.put("by", nz(SyncStore.meta(d, "device_name")));
        x.put("upTo", upTo);
        x.put("snaps", m.optLong("seq") / SNAPSHOT_EVERY + 5);
        JSONObject sp = m.optJSONObject("snap");
        if (sp != null) x.put("snapName", sp.optString("name"));
        removed.put(dev, x);
        saveRemoved(removed);
        writeMember();
        cacheMembers();
        db.audit("SYNC_REMOVE", "sync", 0, "أُزيل الجهاز «" + m.optString("name") + "» من المزامنة؛ تُستلم حزمه حتى الحزمة " + upTo + " ثم تتوقف");
        return upTo;
    }

    /** 10.5: ما تشمله لقطة (مؤشرها كما في البطاقة) من حزم جهاز. */
    static long snapCur(String snapPtr, String dev) {
        if (snapPtr == null) return 0;
        try {
            JSONObject c = new JSONObject(snapPtr).optJSONObject("cur");
            return c == null ? 0 : c.optLong(dev, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 10.5: هل تشمل اللقطة كل ما حُذف من حزم الأجهزة المُزالة؟ (وإلا احتاج المنضم منها حزمًا لم تعد موجودة) */
    static boolean covers(JSONObject snapPtr, Map<String, JSONObject> removed) {
        for (JSONObject x : removed.values()) {
            long done = purgedTo(x);
            if (!x.has("upTo") || done <= 0) continue;
            JSONObject c = snapPtr.optJSONObject("cur");
            if (c == null || c.optLong(x.optString("dev"), 0) < done) return false;
        }
        return true;
    }

    /** 10.5: أين وصل حذف ملفات جهاز مُزال (-1 لم يبدأ). سجلات 10.4: «purged» = حُذف كل شيء. */
    static long purgedTo(JSONObject x) {
        if (x.has("purgedTo")) return x.optLong("purgedTo", -1);
        return x.optBoolean("purged") ? x.optLong("upTo", 0) : -1;
    }

    /** 10.5: آخر حزمة تُقبل من كل جهاز مُزال يُكمَل استلامه (المُزال بلا upTo لا يُستلم منه شيء). */
    Map<String, Long> removedCaps() {
        Map<String, Long> out = new java.util.HashMap<>();
        for (JSONObject x : removedMap().values()) out.put(x.optString("dev"), Math.max(0, x.optLong("upTo", 0)));
        return out;
    }

    /**
     * 10.4: قبل إعادة تعيين البرنامج: جولة أخيرة (ترسل ما لم يُرسل)، ثم يعلن هذا الجهاز خروجه في بطاقته فلا ينتظره أحد.
     * يذكر آخر حزمة له (upTo): يكمل الآخرون استلام حزمه حتى آخرها ثم يتوقفون، ويحذفها الجهاز الرئيسي بعد أن تصل للجميع.
     * أفضل جهد: إن تعذر الوصول يظهر الجهاز للآخرين غائبًا فيُزال لاحقًا.
     */
    public void retire() throws Exception {
        VAULT.lock();
        try {
            retireLocked();
        } finally {
            VAULT.unlock();
        }
    }

    private void retireLocked() throws Exception {
        if (!enabled(db)) return;
        try {
            sync();
        } catch (Exception ignored) {
            // نكمل: الأهم ألا ينتظره الآخرون
        }
        if (!enabled(db)) return;
        SyncCrypto.Keys keys = keys();
        SQLiteDatabase d = db.getWritableDatabase();
        String me = SyncStore.meta(d, "device");
        Map<String, JSONObject> removed = removedMap();
        JSONObject x = new JSONObject();
        x.put("dev", me);
        x.put("prefix", lng(SyncStore.meta(d, "prefix")));
        x.put("name", nz(SyncStore.meta(d, "device_name")));
        x.put("at", Clock.now());
        x.put("by", "إعادة تعيين البرنامج على الجهاز نفسه");
        // ما أرسله ولم تُطبّقه الأجهزة الأخرى بعد لا يضيع: تُكمل استلامه حتى هذه الحزمة
        x.put("upTo", lng(SyncStore.meta(d, "my_seq")));
        x.put("snaps", lng(SyncStore.meta(d, "snap_no")));
        removed.put(me, x);
        saveRemoved(removed);
        // لقطته لا تصلح بداية لمنضم جديد بعد خروجه
        SyncStore.setMeta(d, "snap", null);
        writeMember();
    }

    /** حجم مكان المزامنة: عدد الملفات بأنواعها وحجمها (إن عرفه الناقل). */
    public static final class Usage {
        public int members, packets, snapshots, images, other;
        public long bytes = -1, imageBytes = -1, snapBytes = -1;

        public int total() { return members + packets + snapshots + images + other; }
    }

    public Usage usage() throws IOException {
        Usage u = new Usage();
        Map<String, long[]> st = folder.stat("");
        java.util.Collection<String> names = st != null ? st.keySet() : folder.list("");
        if (st != null) {
            u.bytes = 0;
            u.imageBytes = 0;
            u.snapBytes = 0;
        }
        for (String n : names) {
            long sz = st == null ? 0 : st.get(n)[0];
            if (n.startsWith("m-")) u.members++;
            else if (n.startsWith("p-")) u.packets++;
            else if (n.startsWith("s-")) {
                u.snapshots++;
                if (st != null) u.snapBytes += sz;
            } else if (n.startsWith("a-")) {
                u.images++;
                if (st != null) u.imageBytes += sz;
            } else u.other++;
            if (st != null) u.bytes += sz;
        }
        return u;
    }

    public static String size(long b) {
        if (b < 0) return "غير معروف";
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " ك.ب";
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f م.ب", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f غ.ب", b / 1073741824.0);
    }

    /** سجل آخر 15 جولة (للشاشة). */
    /** 11.5: خطأ في حزم جهاز بعينه: يُعرف «مع من» حصل. */
    private void devError(Result res, String dev, String msg) {
        res.errors.add(msg);
        res.errFrom.putIfAbsent(dev, msg);
        if (res.firstErrWith.isEmpty()) {
            int a = msg.indexOf('«'), b = msg.indexOf('»', a + 1);
            res.firstErrWith = a >= 0 && b > a ? "جهاز «" + msg.substring(a + 1, b) + "»" : "جهاز آخر";
        }
    }

    /** 11.5: اسم مكان المزامنة للعرض. */
    static String placeName(SyncFolder f) {
        if (f instanceof DriveSyncFolder) return "Google Drive";
        if (f instanceof HttpSyncFolder) return "خادم المزامنة";
        return "مجلد المزامنة";
    }

    static final int DEVLOG_MAX = 40;

    /** 11.5: سجل لكل جهاز (على هذا الجهاز): ما وصل منه وأخطاؤه، ولهذا الجهاز: جولاته. */
    static void devLog(SQLiteDatabase d, String dev, String text, String err) {
        if (dev == null || dev.isEmpty()) return;
        try {
            String old = SyncStore.meta(d, "devlog");
            JSONObject all = new JSONObject(old == null ? "{}" : old);
            JSONArray a = all.optJSONArray(dev), out = new JSONArray();
            JSONObject x = new JSONObject();
            x.put("at", Clock.now());
            x.put("t", text);
            if (err != null && !err.isEmpty()) x.put("e", err);
            out.put(x);
            for (int i = 0; a != null && i < a.length() && out.length() < DEVLOG_MAX; i++) out.put(a.get(i));
            all.put(dev, out);
            SyncStore.setMeta(d, "devlog", all.toString());
        } catch (Exception ignored) {
            // السجل للعرض فقط
        }
    }

    public static List<JSONObject> devHistory(Db db, String dev) {
        List<JSONObject> out = new ArrayList<>();
        try {
            String s = SyncStore.meta(db.getReadableDatabase(), "devlog");
            JSONArray a = new JSONObject(s == null ? "{}" : s).optJSONArray(dev);
            for (int i = 0; a != null && i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {
            // فارغ
        }
        return out;
    }

    /** 11.5: مسح سجل جهاز واحد (أو كل السجلات بما فيها «آخر الجولات» إن كان dev فارغًا) على هذا الجهاز فقط. */
    public static void clearDevLog(Db db, String dev) {
        SQLiteDatabase d = db.getWritableDatabase();
        try {
            if (dev == null || dev.isEmpty()) {
                SyncStore.setMeta(d, "devlog", null);
                SyncStore.setMeta(d, "log", null);
                return;
            }
            String s = SyncStore.meta(d, "devlog");
            JSONObject all = new JSONObject(s == null ? "{}" : s);
            all.remove(dev);
            SyncStore.setMeta(d, "devlog", all.toString());
        } catch (Exception ignored) {
            // للعرض فقط
        }
    }

    static void log(SQLiteDatabase d, String text, String err) {
        try {
            String old = SyncStore.meta(d, "log");
            JSONArray a = new JSONArray(old == null ? "[]" : old), out = new JSONArray();
            JSONObject x = new JSONObject();
            x.put("at", Clock.now());
            x.put("t", text);
            if (err != null && !err.isEmpty()) x.put("e", err);
            out.put(x);
            for (int i = 0; i < a.length() && out.length() < 15; i++) out.put(a.get(i));
            SyncStore.setMeta(d, "log", out.toString());
        } catch (Exception ignored) {
            // السجل للعرض فقط
        }
    }

    public static List<JSONObject> history(Db db) {
        List<JSONObject> out = new ArrayList<>();
        try {
            String s = SyncStore.meta(db.getReadableDatabase(), "log");
            JSONArray a = new JSONArray(s == null ? "[]" : s);
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {
            // فارغ
        }
        return out;
    }

    /** 10.16: ساعة جهاز تسبق ساعتنا بأكثر من هذا = تحذير (ترتيب «الأحدث يفوز» يعتمد على الساعات). */
    static final long CLOCK_SKEW_MS = 10L * 60 * 1000;

    /**
     * يحفظ قائمة الأعضاء للواجهة. 10.16: ويقارنها بالقائمة السابقة: جهاز جديد دخل، أو جهاز خرج/أُزيل = تنبيه يُحفظ للمراجعة
     * (يظهر في الرئيسية وشاشة المزامنة) ونص يرجع للإشعار. أول قائمة بعد الانضمام لا تُعد أخبارًا.
     */
    List<String> cacheMembers() throws Exception {
        List<String> news = new ArrayList<>();
        SQLiteDatabase d = db.getWritableDatabase();
        String me = nz(SyncStore.meta(d, "device")), prevJson = SyncStore.meta(d, "members_cache");
        Map<String, String> prev = new TreeMap<>();
        try {
            JSONArray pa = new JSONArray(prevJson == null ? "[]" : prevJson);
            for (int i = 0; i < pa.length(); i++) prev.put(pa.getJSONObject(i).optString("dev"), pa.getJSONObject(i).optString("name"));
        } catch (Exception ignored) {
            // قائمة تالفة = بلا مقارنة
        }
        JSONArray a = new JSONArray();
        Map<String, String> cur = new TreeMap<>();
        long now = Clock.now();
        String ahead = "";
        Map<String, JSONObject> live = readMembers(keys());
        // 10.18: الجهاز لا يخرج من المزامنة إلا بإزالة مسجَّلة (المدير أزاله أو أعاد تعيين برنامجه). بطاقة غابت عن هذه الجولة
        // (تُستبدل الآن، أو تأخر وصولها) لا تعني خروجه: يبقى في القائمة كما كان ولا يُنبَّه أحد بخروجه
        Map<String, JSONObject> gone = removedMap();
        try {
            JSONArray pa = new JSONArray(prevJson == null ? "[]" : prevJson);
            for (int i = 0; i < pa.length(); i++) {
                JSONObject x = pa.getJSONObject(i);
                String dv = x.optString("dev");
                if (dv.isEmpty() || dv.equals(me) || live.containsKey(dv) || gone.containsKey(dv)) continue;
                a.put(x);
                cur.put(dv, x.optString("name"));
            }
        } catch (Exception ignored) {
            // قائمة تالفة = بلا ترحيل
        }
        for (JSONObject m : live.values()) {
            JSONObject x = new JSONObject();
            x.put("dev", m.optString("dev"));
            x.put("name", m.optString("name"));
            x.put("platform", m.optString("platform"));
            x.put("app", m.optString("app"));
            x.put("seq", m.optLong("seq"));
            x.put("at", m.optLong("at"));
            x.put("prefix", m.optLong("prefix"));
            a.put(x);
            cur.put(m.optString("dev"), m.optString("name"));
            if (!m.optString("dev").equals(me) && m.optLong("at") - now > CLOCK_SKEW_MS) ahead = m.optString("name");
        }
        SyncStore.setMeta(d, "members_cache", a.toString());
        // ساعة جهاز آخر تسبق ساعتنا كثيرًا: إحدى الساعتين خطأ، والتعديل «الأحدث» قد يُحسم خطأ
        String warn = ahead.isEmpty() ? null : "ساعة الجهاز «" + ahead + "» تسبق ساعة هذا الجهاز بأكثر من 10 دقائق. اضبط الوقت والتاريخ على الجهازين "
                + "(تلقائي من الشبكة)؛ ترتيب التعديلات بين الأجهزة يعتمد على الساعة.";
        if (warn != null && !warn.equals(SyncStore.meta(d, "clock_warn"))) {
            SyncStore.conflict(d, "CLOCK", "sync", ahead + "@" + (now / 86_400_000L), warn, "");
            news.add(warn);
        }
        SyncStore.setMeta(d, "clock_warn", warn);
        if (prevJson == null || prev.isEmpty()) return news;
        Map<String, JSONObject> removed = removedMap();
        for (Map.Entry<String, String> e : cur.entrySet()) {
            if (prev.containsKey(e.getKey()) || e.getKey().equals(me)) continue;
            String t = "دخل الجهاز «" + e.getValue() + "» إلى المزامنة. إن لم تكن تعرفه فأزله من «المزامنة ← الأجهزة» وغيّر كلمة سر المزامنة.";
            SyncStore.conflict(d, "DEVICE_JOINED", "sync", e.getKey() + "@" + now, t, e.getKey());
            news.add(t);
        }
        for (Map.Entry<String, String> e : prev.entrySet()) {
            if (cur.containsKey(e.getKey()) || e.getKey().equals(me)) continue;
            JSONObject x = removed.get(e.getKey());
            String by = x == null ? "" : x.optString("by");
            String t = "خرج الجهاز «" + e.getValue() + "» من المزامنة" + (by.isEmpty() ? "." : " (" + (by.startsWith("إعادة") ? by : "أزاله: " + by) + ").");
            SyncStore.conflict(d, "DEVICE_GONE", "sync", e.getKey() + "@" + now, t, e.getKey());
            news.add(t);
        }
        return news;
    }

    /**
     * الأجهزة التي استلمت الحزم نفسها تمامًا يجب أن تتطابق بصمة دفترها؛ إن اختلفت فشيء غير متوقع حدث: تنبيه ظاهر
     * (لا تُخفى المشكلة). يرجع نص التنبيه أو فارغًا.
     */
    String compareDigests() throws Exception {
        SQLiteDatabase d = db.getReadableDatabase();
        String me = SyncStore.meta(d, "device");
        Map<String, Long> mine = SyncStore.cursors(d);
        mine.put(me, lng(SyncStore.meta(d, "my_seq")));
        String myDigest = SyncStore.digest(d);
        for (JSONObject m : readMembers(keys()).values()) {
            String dev = m.optString("dev");
            if (dev.equals(me)) continue;
            Map<String, Long> theirs = new TreeMap<>();
            JSONObject c = m.optJSONObject("cursors");
            if (c != null) {
                Iterator<String> it = c.keys();
                while (it.hasNext()) {
                    String x = it.next();
                    theirs.put(x, c.optLong(x));
                }
            }
            theirs.put(dev, m.optLong("seq"));
            if (!same(mine, theirs)) continue;
            if (!myDigest.equals(m.optString("digest")))
                return "دفتر هذا الجهاز لا يطابق دفتر «" + m.optString("name") + "» رغم استلامهما الحزم نفسها. لا تسجّل حركات كبيرة قبل مراجعة «فحص الصحة» على الجهازين.";
        }
        return "";
    }

    static boolean same(Map<String, Long> a, Map<String, Long> b) {
        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            long x = a.containsKey(k) ? a.get(k) : 0, y = b.containsKey(k) ? b.get(k) : 0;
            if (x != y) return false;
        }
        return true;
    }

    /** للواجهة: عدد التغييرات المحلية التي لم تُرسل بعد. */
    public static long pending(Db db) {
        Status s = status(db);
        return s.pending + s.unsent;
    }

    static LinkedHashMap<String, String> describe(JSONObject m) {
        LinkedHashMap<String, String> x = new LinkedHashMap<>();
        x.put("name", m.optString("name"));
        return x;
    }
}
