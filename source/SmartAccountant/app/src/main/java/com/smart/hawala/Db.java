package com.smart.hawala;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONObject;

/**
 * قاعدة البيانات: البنية، الحركات والقيود، الأرصدة، الإعدادات، المستخدمون، وتصدير/استيراد النسخة الاحتياطية.
 * كل تعديل مالي داخل معاملة واحدة: إما أن يُحفظ كاملًا أو لا يُحفظ شيء.
 *
 * <p>قاعدة مهمة (سبب خطأ الكشوفات الفارغة في 5.0): أندرويد يمرّر معاملات الاستعلام نصوصًا،
 * ومقارنة تعبير رقمي بنص لا تطابق شيئًا. لذلك الأرقام (تواريخ، معرّفات) تُكتب في الاستعلام كأرقام مباشرة
 * (قيم long من Java، فلا خطر حقن)، والنصوص فقط تُمرَّر كمعاملات.
 */
public class Db extends SQLiteOpenHelper {
    /** 7 = 8.0: خانات الخدمة والوجهة وساقي تبديل العملة. 8 = 9.0: صندوق المكتب. */
    /** 9 (10.16): جدول audit_feed (سجل العمليات المشترك بين الأجهزة). */
    /** 10 (10.17): عمود users.nick (الاسم الظاهر)، وجدولا رسائل المدير admin_msgs وadmin_msg_state. */
    /** 11 (10.18): الأمانات والمبالغ المجمّدة office_holds (متزامن)، وسجل الإشعارات notif_log (محلي). */
    /**
     * 12 (12.4): tx.special وtx.special_bal (الحركات الخاصة)، وoffice_holds.to_tx (الأمانة التي حُوّلت إلى حركة).
     * 13 (12.5): currencies.kind/metal/unit/fineness/valued (المعادن الثمينة بالوزن).
     */
    public static final int VER = 13;
    public static final String NAME = "smart_accountant.db";

    public static final String TYPE_DEBIT = Model.TYPE_DEBIT;
    public static final String TYPE_CREDIT = Model.TYPE_CREDIT;
    public static final String FEE_NONE = Model.FEE_NONE;
    public static final String FEE_US = Model.FEE_US;
    public static final String FEE_THEM = Model.FEE_THEM;

    /** اسم المستخدم الحالي (يُسجَّل مع كل حركة وفي سجل التدقيق). */
    public static volatile String currentUserName = "المستخدم المحلي";

    /**
     * 10.0: عمليات يجريها التطبيق نفسه (حل تعارض المزامنة): بلا فحص صلاحيات المستخدم الحالي.
     * 10.5 (AUDIT-003): الإذن خاص بالخيط الذي يجري المزامنة وحده؛ كان علمًا عامًا فتمر خلاله عمليات المستخدم من الواجهة
     * في الوقت نفسه دون فحص صلاحياته.
     */
    private final ThreadLocal<Boolean> systemTl = new ThreadLocal<>();

    boolean isSystem() { return Boolean.TRUE.equals(systemTl.get()); }

    void setSystem(boolean on) {
        if (on) systemTl.set(Boolean.TRUE);
        else systemTl.remove();
    }

    /** 10.0: استعادة لقطة الخزنة عند الانضمام (لا تُخرج الجهاز من المزامنة). */
    volatile boolean restoringForSync;

    /** 10.0: بادئة أرقام هذا الجهاز في المزامنة (0 = المزامنة غير مفعّلة: ترقيم SQLite العادي). */
    volatile long syncPrefix;

    /** إدراج صف: مع المزامنة يأخذ الصف رقمًا من نطاق هذا الجهاز، فلا يتكرر رقم بين جهازين أبدًا. */
    long ins(SQLiteDatabase d, String table, ContentValues v) {
        if (syncPrefix > 0 && v.get("id") == null && SyncStore.ID_TABLES.contains(table)) v.put("id", SyncStore.nextId(d, table, syncPrefix));
        return d.insertOrThrow(table, null, v);
    }

    /** بعد رمز استرداد صحيح فقط: يُسمح بتغيير رمز المدير قبل الدخول. */
    static volatile boolean recoveryGrant;

    /**
     * 8.0: فحص صلاحية ثانٍ داخل طبقة البيانات، فلا يتجاوز العمليات الخطرة مسار واجهة منسيّ.
     * بلا نظام مستخدمين يعمل التطبيق بصلاحيات كاملة كما كان. النسخ التلقائي المجدول لا يمر بهذه العمليات.
     */
    void require(Auth.Perm p) {
        if (isSystem() || !usersEnabled()) return;
        Model.User u = SmartApp.currentUser;
        // 10.1: الصلاحيات تُقرأ من قاعدة البيانات كل مرة (قد يغيّرها المدير من جهاز آخر عبر المزامنة)
        Model.User now = u == null ? null : user(u.id);
        if (now == null || !now.active) throw new SecurityException("حسابك موقوف أو غير موجود. سجّل الدخول من جديد.");
        if (!Auth.can(now, p)) throw new SecurityException("ليست لديك صلاحية لهذه العملية");
    }

    /**
     * 10.5 (AUDIT-023): عمليات تخص المدير وحده مهما كانت صلاحيات المستخدم: إنشاء مدير أو ترقيته، تعديل حساب مدير
     * أو تغيير رمزه، إيقاف نظام المستخدمين، ورمز الاسترداد. صلاحية «المستخدمون» تدير حسابات الموظفين فقط.
     */
    void requireAdmin(String why) {
        if (isSystem() || !usersEnabled()) return;
        Model.User u = SmartApp.currentUser;
        Model.User now = u == null ? null : user(u.id);
        if (now == null || !now.active) throw new SecurityException("حسابك موقوف أو غير موجود. سجّل الدخول من جديد.");
        if (!Auth.isAdmin(now)) throw new SecurityException(why);
    }

    /** المستخدم الحالي بحالته الآن (null بلا نظام مستخدمين أو بلا دخول). */
    Model.User actor() {
        Model.User u = SmartApp.currentUser;
        return u == null || !usersEnabled() ? null : user(u.id);
    }

    /** هل يملك المستخدم الحالي الصلاحية الآن (بلا مستخدمين: نعم). */
    public boolean allowed(Auth.Perm p) {
        if (!usersEnabled()) return true;
        Model.User u = SmartApp.currentUser;
        Model.User now = u == null ? null : user(u.id);
        return now != null && now.active && Auth.can(now, p);
    }

    static final Map<String, List<String>> SENSITIVE = new HashMap<>();
    static {
        SENSITIVE.put("offices", Arrays.asList("name", "short_name", "phone", "location", "contact", "notes"));
        SENSITIVE.put("tx", Arrays.asList("person", "description", "notes", "user_name", "dest"));
        SENSITIVE.put("audit_logs", Arrays.asList("user_name", "reason"));
        SENSITIVE.put("audit_archive", Arrays.asList("user_name", "reason"));
        SENSITIVE.put("reconciliations", Arrays.asList("note", "user_name"));
        SENSITIVE.put("cash_moves", Arrays.asList("party", "description", "user_name", "cancel_reason"));
        SENSITIVE.put("cash_counts", Arrays.asList("user_name", "note", "sales"));
        SENSITIVE.put("cash_clients", Arrays.asList("name", "phone", "note"));
        SENSITIVE.put("client_moves", Arrays.asList("description", "user_name", "cancel_reason"));
        SENSITIVE.put("tx_checks", Arrays.asList("user_name", "void_reason"));
        SENSITIVE.put("wa_marks", Arrays.asList("text", "msg_time", "user_name"));
        SENSITIVE.put("office_work", Arrays.asList("user_name"));
        SENSITIVE.put("audit_feed", Arrays.asList("user_name", "reason"));
        SENSITIVE.put("admin_msgs", Arrays.asList("from_name", "body"));
        SENSITIVE.put("office_holds", Arrays.asList("note", "user_name", "released_by"));
    }

    /** إعدادات خاصة بهذا الهاتف: لا تُنقل في النسخة ولا تُستبدل عند الاستعادة. */
    static final Set<String> DEVICE_SETTINGS = new HashSet<>(Arrays.asList(
            // 10.14: رمز استرداد النسخ (مفتاحه مشفّر بمفتاح هذا الجهاز) وحساب Google لهذا الجهاز
            "backup_rc_key", "backup_rc_salt", "backup_rc_iter", "backup_rc_at", "drive_account",
            // 11.0: نافذة الكمبيوتر (عرض العمود والتكبير) تخص الجهاز
            "sync_usage_open", "sync_audit_ok",
            "desk_column", "desk_zoom", "desk_ui", "desk_wizard", "desk_phone_bounds",
            "clock_offset_ms",
            // 12.1: مراقبة الحافظة وإطار الالتقاط على هذا الكمبيوتر
            "clip_watch", "cap_bounds",
            // 12.2: القراءة التلقائية في إطار الالتقاط، وشرح أول مرة
            "cap_auto", "cap_intro",
            // 11.5: آخر من دخل على هذا الجهاز (لإشعارات المزامنة في الخلفية حين لا يكون أحد داخلًا)
            "notif_last_user",
            "biometric", "lock_timeout", "last_backup", "last_backup_tx", "last_backup_status",
            "pending_backup_file", "reminder_dialog_day", "draft_tx", "mig5", "mig6", "last_office",
            "auto_backup_on", "auto_backup_tree", "auto_backup_pw", "auto_backup_days", "auto_backup_keep",
            "last_auto_backup", "auto_backup_error", "login_fails", "login_locked_until", "pending_export_file",
            "ai_on", "ai_auto", "ai_provider", "ai_key", "ai_model", "ai_url", "wa_queue", "wa_sender_office", "clip_seen",
            "drive_backup_uri", "drive_backup_name", "last_drive_backup", "drive_backup_error", "crypto_v2_done",
            // 8.0: القفل والمهلة، والترخيص والعدّادات (لا تنتقل مع النسخة ولا تُستبدل بالاستعادة)
            "lock_start_rt", "lock_ms", "lock_warning", "secure_screen", "license_token", "license_state", "quota", "quota_mac",
            "license_server", "license_last_check", "license_revoked", "risk_note", "health_last", "health_status",
            "restore_drill_at", "restore_drill_status", "readable_export_at", "auto_backup_hours", "auto_backup_every_tx", "auto_backup_try", "audit_head",
            "readable_export", "readable_export_month", "readable_export_error",
            // 8.1: نسخة الماك (مجلد النسخ التلقائي، مهلة القفل، آخر مجلد تصدير)
            "desk_backup_dir", "desk_lock_min", "last_export_dir", "draft_tx_mac", "last_export_format",
            // 9.0: عدّاد واتساب اليومي، وترتيب المكاتب وآخر فتح لكل مكتب، والإشعارات (لكل جهاز إشعاراته)
            "wa_quota", "wa_quota_mac", "office_opened", "office_sort", "notif_on", "notif_backup", "notif_idle", "notif_recon", "notif_recon_days",
            "notif_idle_off", "notif_recon_off", "notif_last_idle", "notif_last_recon", "notif_hour_from", "notif_hour_to", "rel_home", "draft_cash", "notif_asked",
            // 9.0.1: الصندوق المفتوح وقفله (بصمة أو كلمة سر خاصة بهذا الجهاز)، وإخفاء المبالغ
            "cash_current", "cash_lock", "cash_pin_salt", "cash_pin_hash", "hide_amounts", "cash_fails", "cash_locked_until",
            // 10.0: مجلد المزامنة على هذا الجهاز
            "sync_tree", "sync_dir", "sync_after_change", "sync_transport", "sync_server", "sync_server_key", "sync_server_vault", "sync_drive_ok", "remember_user", "remember_default", "last_login_name", "tabs", "notif_sync_tx", "notif_sync_pending", "app_lock", "app_pin_salt", "app_pin_hash", "app_fails", "app_locked_until", "app_lock_rt", "app_lock_ms",
            // 10.5 (AUDIT-004): مظهر الكمبيوتر، ومعرّف عميل Google للكمبيوتر وسرّه (لا تنتقل بالنسخة ولا تُستبدل بالاستعادة)
            "desk_theme", "drive_client_id", "drive_client_secret"));

    public static final String DEFAULT_ORDER = "tx,log,statement,totals,daily,search,office,currency,backup,settings";

    private final Context ctx;
    private final Map<String, Integer> decimalsCache = new HashMap<>();

    public Db(Context c) {
        // 12.6: الملف كله مشفّر (SQLCipher) بمفتاح مشتق من مفتاح البيانات؛ القاعدة القديمة تُشفَّر هنا قبل أول فتح
        super(c, NAME, prepareCipher(c), null, VER, 0, null, null, false);
        ctx = c.getApplicationContext();
    }

    /**
     * 12.6: يوم التحقق من انتهاء الاشتراك (yyyymmdd): أكبر يوم رآه التطبيق على هذا الجهاز (إعداد محلي لا يتزامن)،
     * فإرجاع ساعة الجهاز للوراء لا يمدّد اشتراكًا منتهيًا.
     */
    public long licenseDay() {
        long now = Long.parseLong(new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date(Clock.now())));
        long seen = 0;
        try {
            seen = Long.parseLong(setting("license_day", "0"));
        } catch (Exception ignored) {
            // أول مرة
        }
        if (now > seen) {
            try { setSettingQuiet("license_day", String.valueOf(now)); } catch (Throwable ignored) { /* لا يمنع الإقلاع */ }
            return now;
        }
        return seen;
    }

    /** 12.6: سبب بقاء القاعدة غير مشفّرة الآن (فشل التشفير، أو مفتاح البيانات غير متاح)، أو فارغ. */
    public static volatile String cipherNote = "";

    /**
     * 12.6: قاعدة مشفّرة لم تُفتح بالمفتاح الحالي (ضياع مفتاح الجهاز): نُقلت جانبًا كما هي — لا تُحذف أبدًا —
     * وفُتحت قاعدة فارغة حتى تظهر شاشة «مفتاح التشفير غير متاح» وتعمل الاستعادة. null = لا شيء.
     */
    public static volatile File lockedAside;

    /** هل ملف القاعدة الحالي مشفّر؟ (للعرض والاختبار) */
    public boolean encrypted() {
        File f = ctx.getDatabasePath(NAME);
        return f.exists() && !DbCipher.isPlain(f);
    }

    /** 12.7: قاعدة قديمة غير مشفّرة ومفتاحها متاح: أول فتح سيشفّرها (قد يستغرق ثوانيَ مع البيانات الكبيرة). */
    public static boolean needsEncryption(Context c) {
        try {
            Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            File f = app.getDatabasePath(NAME);
            return f.isFile() && f.length() > 0 && DbCipher.isPlain(f) && Crypto.dbKey() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 12.7: مرة واحدة في وقت واحد (أي خيط يفتح القاعدة ينتظر الآخر فلا يُشفَّر الملف مرتين معًا). */
    static synchronized String prepareCipher(Context c) {
        // 12.7.1: مكتبة التشفير تُحمَّل قبل أي فتح للقاعدة. كانت تُحمَّل فقط عند تشفير قاعدة قديمة، فتنهار القاعدة الجديدة
        // (أول تثبيت، أو بعد «إعادة التعيين») بـ UnsatisfiedLinkError: No implementation found for net.zetetic…
        DbCipher.load();
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        File f = app.getDatabasePath(NAME);
        String key = Crypto.dbKey();
        cipherNote = "";
        if (f.exists() && f.length() == 0) DbCipher.deleteDb(f);   // ملف فارغ لم يُكتب فيه شيء
        if (!f.exists()) {
            if (key == null) cipherNote = "مفتاح البيانات غير متاح: القاعدة الجديدة غير مشفّرة حتى يتوفر.";
            return key == null ? "" : key;
        }
        if (DbCipher.isPlain(f)) {
            if (key == null) {
                cipherNote = "مفتاح البيانات غير متاح الآن: تبقى القاعدة كما كانت (غير مشفّرة) وتُشفَّر في تشغيل لاحق.";
                return "";
            }
            try {
                DbCipher.encrypt(app, f, key);
                return key;
            } catch (Throwable t) {
                cipherNote = "تعذر تشفير القاعدة هذه المرة (" + t.getMessage() + "). بياناتك سليمة كما هي، ويُعاد التشفير في التشغيل التالي.";
                return "";
            }
        }
        if (key != null && DbCipher.canOpen(f, key)) return key;
        // مشفّرة ولا تُفتح الآن: تُنقل جانبًا كما هي (قد يعود المفتاح)، ولا تُحذف
        File aside = new File(f.getParentFile(), "smart_accountant.locked-" + System.currentTimeMillis() + ".db");
        if (f.renameTo(aside)) {
            new File(f.getPath() + "-wal").renameTo(new File(aside.getPath() + "-wal"));
            new File(f.getPath() + "-shm").delete();
            lockedAside = aside;
        }
        return key == null ? "" : key;
    }

    /**
     * 12.6: بعد عودة المفتاح («إعادة المحاولة») تُعاد القاعدة المنقولة جانبًا إن فُتحت بالمفتاح؛ يرجع Db جديدة أو null.
     */
    public static Db restoreLockedAside(Context c, Db current) {
        File aside = lockedAside;
        String key = Crypto.dbKey();
        if (aside == null || key == null || !DbCipher.canOpen(aside, key)) return null;
        File f = c.getDatabasePath(NAME);
        current.close();
        File fresh = new File(f.getPath() + ".fresh-" + System.currentTimeMillis());
        f.renameTo(fresh);
        new File(f.getPath() + "-wal").delete();
        new File(f.getPath() + "-shm").delete();
        if (!aside.renameTo(f)) {
            fresh.renameTo(f);
            return null;
        }
        new File(aside.getPath() + "-wal").renameTo(new File(f.getPath() + "-wal"));
        DbCipher.deleteDb(fresh);
        lockedAside = null;
        return new Db(c.getApplicationContext());
    }

    /** 12.6: بعد «مفتاح جديد قبل الاستعادة»: القاعدة الحالية (مشفّرة بالمفتاح القديم) تُنقل جانبًا وتُفتح قاعدة جديدة بالمفتاح الجديد. */
    public static Db reopenAfterKeyReset(Context c, Db current) {
        current.close();
        Db fresh = new Db(c.getApplicationContext());
        lockedAside = null;   // القديمة تبقى على القرص باسم locked-… ولا تمنع العمل بعد الآن
        return fresh;
    }

    // ===================================================================================
    // البنية والترقية الآمنة
    // ===================================================================================

    @Override public void onConfigure(SQLiteDatabase d) {
        d.setForeignKeyConstraintsEnabled(true);
        d.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase d) { ensureSchema(d); }

    @Override public void onUpgrade(SQLiteDatabase d, int o, int n) { ensureSchema(d); }

    /** 10.16: بيانات من إصدار أحدث لا تُفتح بتطبيق أقدم (كان يكمل بصمت فيتجاهل أعمدة وجداول لا يعرفها). */
    @Override public void onDowngrade(SQLiteDatabase d, int o, int n) {
        throw new IllegalStateException("هذه البيانات من إصدار أحدث من هذا التطبيق (مخطط " + o + "، وهذا الإصدار يعرف حتى " + n
                + "). حدّث التطبيق إلى آخر إصدار ثم افتحه؛ بياناتك لم تُمس.");
    }

    @Override public void onOpen(SQLiteDatabase d) {
        // 12.7: ذاكرة صفحات 32 ميغا بدل 2 (القواعد الكبيرة تُقرأ من الذاكرة لا من القرص)
        try {
            d.execSQL("PRAGMA cache_size=-32768");
        } catch (Throwable ignored) {
            // غير حرج
        }
        loadClock(d);
        if (d.isReadOnly()) return;
        d.beginTransaction();
        try {
            ensureSchema(d);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
            try {
                refreshMetals(d);
            } catch (Exception ignored) {
                // قاعدة قديمة جدًا قبل الأعمدة: لا معادن
            }
        }
        syncPrefix = SyncStore.prefix(d);
    }

    private static final String[] DDL = {
            "CREATE TABLE IF NOT EXISTS currencies(code TEXT PRIMARY KEY,name TEXT NOT NULL,decimals INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 1)",
            "CREATE TABLE IF NOT EXISTS offices(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,short_name TEXT,phone TEXT,location TEXT,contact TEXT,notes TEXT,active INTEGER NOT NULL DEFAULT 1,usd_limit_minor INTEGER,usd_owe_limit_minor INTEGER,pinned INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS office_currencies(office_id INTEGER,code TEXT,limit_minor INTEGER,limit_owe_minor INTEGER,PRIMARY KEY(office_id,code),FOREIGN KEY(office_id) REFERENCES offices(id),FOREIGN KEY(code) REFERENCES currencies(code))",
            "CREATE TABLE IF NOT EXISTS tx(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT UNIQUE NOT NULL,office_id INTEGER NOT NULL,type TEXT NOT NULL,person TEXT,amount_minor INTEGER NOT NULL,currency TEXT NOT NULL,fee_type TEXT NOT NULL,fee_minor INTEGER NOT NULL DEFAULT 0,fee_currency TEXT,description TEXT,notes TEXT,created_at INTEGER NOT NULL,user_name TEXT,status TEXT NOT NULL DEFAULT 'ACTIVE',replaces_id INTEGER,FOREIGN KEY(office_id) REFERENCES offices(id))",
            "CREATE TABLE IF NOT EXISTS attachments(id INTEGER PRIMARY KEY AUTOINCREMENT,tx_id INTEGER NOT NULL,path TEXT NOT NULL,mime TEXT,created_at INTEGER,FOREIGN KEY(tx_id) REFERENCES tx(id))",
            "CREATE TABLE IF NOT EXISTS journal_entries(id INTEGER PRIMARY KEY AUTOINCREMENT,tx_id INTEGER,kind TEXT,created_at INTEGER,reverses_id INTEGER,status TEXT DEFAULT 'POSTED')",
            "CREATE TABLE IF NOT EXISTS journal_lines(id INTEGER PRIMARY KEY AUTOINCREMENT,entry_id INTEGER NOT NULL,account TEXT NOT NULL,office_id INTEGER,currency TEXT NOT NULL,debit_minor INTEGER NOT NULL,credit_minor INTEGER NOT NULL,FOREIGN KEY(entry_id) REFERENCES journal_entries(id))",
            "CREATE TABLE IF NOT EXISTS opening_balances(id INTEGER PRIMARY KEY AUTOINCREMENT,office_id INTEGER,currency TEXT,type TEXT,amount_minor INTEGER,entry_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS exchange_rates(code TEXT PRIMARY KEY,usd_rate TEXT NOT NULL,updated_at INTEGER NOT NULL,manual_rate TEXT,manual_at INTEGER)",
            "CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY,v TEXT)",
            "CREATE TABLE IF NOT EXISTS backup_history(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER,status TEXT,path TEXT,details TEXT,size_bytes INTEGER,n_offices INTEGER,n_tx INTEGER,n_att INTEGER,verified INTEGER,kind TEXT)",
            "CREATE TABLE IF NOT EXISTS audit_logs(id INTEGER PRIMARY KEY AUTOINCREMENT,action TEXT,entity TEXT,entity_id INTEGER,user_name TEXT,reason TEXT,created_at INTEGER)",
            "CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,role TEXT NOT NULL,pin_hash TEXT,pin_salt TEXT,active INTEGER NOT NULL DEFAULT 1,created_at INTEGER)",
            "CREATE TABLE IF NOT EXISTS reconciliations(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT,office_id INTEGER NOT NULL,created_at INTEGER NOT NULL,cutoff_entry INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'SENT',confirmed_at INTEGER,note TEXT,user_name TEXT,FOREIGN KEY(office_id) REFERENCES offices(id))",
            "CREATE TABLE IF NOT EXISTS reconciliation_lines(id INTEGER PRIMARY KEY AUTOINCREMENT,recon_id INTEGER NOT NULL,currency TEXT NOT NULL,balance_minor INTEGER NOT NULL,FOREIGN KEY(recon_id) REFERENCES reconciliations(id))",
            // 8.0: سجل العمليات قبل كل «استبدال الكل» يُحفظ هنا بدل حذفه (خاص بهذا الهاتف، لا يُنقل في النسخة)
            "CREATE TABLE IF NOT EXISTS audit_archive(id INTEGER PRIMARY KEY AUTOINCREMENT,orig_id INTEGER,action TEXT,entity TEXT,entity_id INTEGER,user_name TEXT,reason TEXT,created_at INTEGER,prev_hash TEXT,row_hash TEXT,archived_at INTEGER)",
            // 9.0: صندوق المكتب — حسابات النقد مستقلة عن دفتر قيود المكاتب؛ الحركة المرتبطة بمكتب تحمل رقم حركته (tx_id)
            "CREATE TABLE IF NOT EXISTS cash_moves(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT UNIQUE NOT NULL,box_id INTEGER NOT NULL DEFAULT 1,kind TEXT NOT NULL,currency TEXT NOT NULL,amount_minor INTEGER NOT NULL,dir INTEGER NOT NULL,fee_minor INTEGER NOT NULL DEFAULT 0,fee_side TEXT,fee_cash INTEGER NOT NULL DEFAULT 1,category TEXT,party TEXT,description TEXT,office_id INTEGER,tx_id INTEGER,pair_uuid TEXT,rate TEXT,status TEXT NOT NULL DEFAULT 'ACTIVE',created_at INTEGER NOT NULL,user_name TEXT,cancel_reason TEXT,cancelled_at INTEGER)",
            // 9.0.1: سجل الجرد: المسجّل والمعدود لكل عملة قبل أي تسوية، وما سُجّل بيعًا من الفروق
            "CREATE TABLE IF NOT EXISTS cash_counts(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT,box_id INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,user_name TEXT,note TEXT,lines TEXT NOT NULL,net_usd TEXT,adjusted INTEGER NOT NULL DEFAULT 0,sales TEXT)",
            // 9.0.1: حسابات العملاء (مستقلة عن المكاتب): العميل وحركات حسابه؛ الحركة النقدية مرتبطة بحركة الصندوق (cash_uuid)
            // 10.0: الصناديق في جدول (لكل صندوق ربطه بالمكاتب)، بعد أن كانت في الإعدادات
            "CREATE TABLE IF NOT EXISTS cash_boxes(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT,name TEXT NOT NULL,currencies TEXT,strict INTEGER NOT NULL DEFAULT 0,allow_internal INTEGER NOT NULL DEFAULT 1,active INTEGER NOT NULL DEFAULT 1,sort INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS cash_clients(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT,name TEXT NOT NULL,phone TEXT,note TEXT,active INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS client_moves(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT UNIQUE NOT NULL,client_id INTEGER NOT NULL,kind TEXT NOT NULL,currency TEXT NOT NULL,amount_minor INTEGER NOT NULL,dir INTEGER NOT NULL,cash_uuid TEXT,pair_uuid TEXT,rate TEXT,description TEXT,status TEXT NOT NULL DEFAULT 'ACTIVE',created_at INTEGER NOT NULL,user_name TEXT,cancel_reason TEXT,cancelled_at INTEGER)",
            // 10.5 (§7): مكاتب كل محاسب
            "CREATE TABLE IF NOT EXISTS user_offices(user_id INTEGER NOT NULL,office_id INTEGER NOT NULL,PRIMARY KEY(user_id,office_id))",
            // 10.5 (§7): «تم التحقق» — بلا قيد. المفتاح نصي ثابت (الحركة + المستخدم + بصمة قيمها): تحقق المستخدم نفسه من جهازين صف واحد،
            // وتحقق مستخدمين مختلفين صفان؛ تعديل القيم يُبطل القديم ويبقيه في التاريخ
            "CREATE TABLE IF NOT EXISTS tx_checks(uuid TEXT PRIMARY KEY,tx_id INTEGER NOT NULL,user_id INTEGER NOT NULL,user_name TEXT,created_at INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'VALID',void_reason TEXT,void_at INTEGER)",
            // 10.5 (§7): «آخر رسالة واتساب نُقلت» لكل مكتب، مع تاريخ تحديثها (كل تحديث صف)
            "CREATE TABLE IF NOT EXISTS wa_marks(uuid TEXT PRIMARY KEY,office_id INTEGER NOT NULL,text TEXT,msg_time TEXT,user_id INTEGER,user_name TEXT,created_at INTEGER NOT NULL)",
            // 10.13: حالة عمل المكتب اليوم كما وضعها محاسب (تلقائيًا تُحسب من حركات اليوم ومطابقاته، وهذا الصف يغيّرها يدويًا).
            // جدول مستقل لا عمود في offices: المزامنة «الأحدث يفوز للصف كله»، فتغيير الحالة لا يمسّ اسم المكتب أو بياناته
            "CREATE TABLE IF NOT EXISTS office_work(office_id INTEGER NOT NULL,day TEXT NOT NULL,status TEXT NOT NULL,user_id INTEGER,user_name TEXT,at INTEGER NOT NULL,PRIMARY KEY(office_id,day))",
            // 10.16: سجل العمليات المشترك: كل جهاز منضم للمزامنة يكتب فيه نسخة من أسطر سجله (إضافة فقط، بمفتاح uuid)،
            // فيرى المدير ما جرى على كل الأجهزة. سلسلة البصمات تبقى في audit_logs لكل جهاز وحده.
            // 10.18: أمانات ومبالغ مجمّدة لكل مكتب (لا تدخل في القيود)، وسجل الإشعارات على هذا الجهاز
            "CREATE TABLE IF NOT EXISTS office_holds(uuid TEXT PRIMARY KEY,office_id INTEGER NOT NULL,currency TEXT NOT NULL,amount_minor INTEGER NOT NULL,kind TEXT NOT NULL,side TEXT NOT NULL,"
                    + "note TEXT,created_at INTEGER NOT NULL,user_name TEXT,released_at INTEGER,released_by TEXT)",
            "CREATE TABLE IF NOT EXISTS notif_log(id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,title TEXT,body TEXT)",
            // 10.17: رسائل المدير إلى المحاسبين (تنتقل بالمزامنة)، وحالة قراءتها على هذا الجهاز (محلية)
            "CREATE TABLE IF NOT EXISTS admin_msgs(uuid TEXT PRIMARY KEY,created_at INTEGER NOT NULL,from_user INTEGER,from_name TEXT,to_user INTEGER,body TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS admin_msg_state(uuid TEXT NOT NULL,user_id INTEGER NOT NULL,notified INTEGER NOT NULL DEFAULT 0,read_at INTEGER,PRIMARY KEY(uuid,user_id))",
            "CREATE TABLE IF NOT EXISTS audit_feed(uuid TEXT PRIMARY KEY,created_at INTEGER NOT NULL,action TEXT,entity TEXT,entity_id INTEGER,user_name TEXT,reason TEXT,dev TEXT,dev_name TEXT)"
    };

    private static final String[][] COLS = {
            {"currencies", "name", "TEXT"}, {"currencies", "decimals", "INTEGER DEFAULT 2"}, {"currencies", "active", "INTEGER DEFAULT 1"},
            {"offices", "name", "TEXT"}, {"offices", "short_name", "TEXT"}, {"offices", "phone", "TEXT"}, {"offices", "location", "TEXT"},
            {"offices", "contact", "TEXT"}, {"offices", "notes", "TEXT"}, {"offices", "active", "INTEGER DEFAULT 1"},
            {"offices", "usd_limit_minor", "INTEGER"}, {"offices", "usd_owe_limit_minor", "INTEGER"}, {"offices", "pinned", "INTEGER DEFAULT 0"},
            {"office_currencies", "limit_minor", "INTEGER"}, {"office_currencies", "limit_owe_minor", "INTEGER"},
            {"tx", "uuid", "TEXT"}, {"tx", "office_id", "INTEGER"}, {"tx", "type", "TEXT"}, {"tx", "person", "TEXT"},
            {"tx", "amount_minor", "INTEGER DEFAULT 0"}, {"tx", "currency", "TEXT"}, {"tx", "fee_type", "TEXT DEFAULT 'بدون أجرة'"},
            {"tx", "fee_minor", "INTEGER DEFAULT 0"}, {"tx", "fee_currency", "TEXT"}, {"tx", "description", "TEXT"}, {"tx", "notes", "TEXT"},
            {"tx", "created_at", "INTEGER DEFAULT 0"}, {"tx", "user_name", "TEXT"}, {"tx", "status", "TEXT DEFAULT 'ACTIVE'"}, {"tx", "replaces_id", "INTEGER"},
            {"tx", "service", "TEXT"}, {"tx", "dest", "TEXT"}, {"tx", "pair_uuid", "TEXT"}, {"tx", "fx_rate", "TEXT"},
            // 10.5 (§7): من أدخل الحركة ومن اعتمدها بأرقام المستخدمين الثابتة (القديمة تبقى بالاسم وحده)
            {"tx", "user_id", "INTEGER"}, {"tx", "approved_by", "INTEGER"}, {"tx", "approved_at", "INTEGER"},
            {"attachments", "mime", "TEXT"}, {"attachments", "created_at", "INTEGER"},
            {"journal_entries", "kind", "TEXT"}, {"journal_entries", "created_at", "INTEGER"}, {"journal_entries", "reverses_id", "INTEGER"},
            {"journal_entries", "status", "TEXT DEFAULT 'POSTED'"},
            {"journal_lines", "office_id", "INTEGER"},
            {"opening_balances", "office_id", "INTEGER"}, {"opening_balances", "currency", "TEXT"}, {"opening_balances", "type", "TEXT"},
            {"opening_balances", "amount_minor", "INTEGER"}, {"opening_balances", "entry_id", "INTEGER"},
            {"exchange_rates", "manual_rate", "TEXT"}, {"exchange_rates", "manual_at", "INTEGER"},
            {"backup_history", "created_at", "INTEGER"}, {"backup_history", "status", "TEXT"}, {"backup_history", "path", "TEXT"},
            {"backup_history", "details", "TEXT"}, {"backup_history", "size_bytes", "INTEGER"}, {"backup_history", "n_offices", "INTEGER"},
            {"backup_history", "n_tx", "INTEGER"}, {"backup_history", "n_att", "INTEGER"}, {"backup_history", "verified", "INTEGER"},
            {"backup_history", "kind", "TEXT"},
            {"audit_logs", "action", "TEXT"}, {"audit_logs", "entity", "TEXT"}, {"audit_logs", "entity_id", "INTEGER"},
            {"audit_logs", "user_name", "TEXT"}, {"audit_logs", "reason", "TEXT"}, {"audit_logs", "created_at", "INTEGER"},
            {"audit_logs", "prev_hash", "TEXT"}, {"audit_logs", "row_hash", "TEXT"},
            {"cash_moves", "client_id", "INTEGER"}, {"reconciliations", "cutoff_marks", "TEXT"},
            {"users", "pin_hash", "TEXT"}, {"users", "pin_salt", "TEXT"}, {"users", "active", "INTEGER DEFAULT 1"}, {"users", "created_at", "INTEGER"}, {"users", "perms", "TEXT"},
            // 10.8: حذف حساب محاسب دون المساس بما سجّله (يبقى اسمه في التاريخ)، ودور المحاسب في المكتب (يسجّل / يتحقق)،
            // ومن أنشأ المطابقة ومن تحقق منها. أعمدة جديدة فقط: الأجهزة الأقدم تتجاهلها في المزامنة ولا يتغير شيء قديم
            {"users", "deleted_at", "INTEGER"},
            {"users", "nick", "TEXT"},
            {"user_offices", "role", "TEXT DEFAULT 'OWN'"},
            {"reconciliations", "user_id", "INTEGER"}, {"reconciliations", "checked_by", "INTEGER"}, {"reconciliations", "checked_at", "INTEGER"},
            // 12.4: الحركات الخاصة (أعمدة جديدة فقط: الأجهزة الأقدم تتجاهلها، والخاصة غير المحسوبة عندها ليست ACTIVE فلا تدخل أرصدتها)
            {"tx", "special", "TEXT"}, {"tx", "special_bal", "INTEGER"}, {"office_holds", "to_tx", "TEXT"},
            // 12.5: المعادن الثمينة (أعمدة جديدة فقط؛ الجهاز الأقدم يراها عملة بمنازلها فتبقى أرصدته صحيحة)
            {"currencies", "kind", "TEXT"}, {"currencies", "metal", "TEXT"}, {"currencies", "unit", "TEXT"},
            {"currencies", "fineness", "INTEGER"}, {"currencies", "valued", "INTEGER"}
    };

    static Set<String> columnsOf(SQLiteDatabase d, String table) {
        Set<String> out = new HashSet<>();
        try (Cursor c = d.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) out.add(c.getString(1).toLowerCase(Locale.US));
        }
        return out;
    }

    private void ensureSchema(SQLiteDatabase d) {
        for (String sql : DDL) d.execSQL(sql);
        Map<String, Set<String>> have = new HashMap<>();
        for (String[] col : COLS) {
            Set<String> cols = have.get(col[0]);
            if (cols == null) {
                cols = columnsOf(d, col[0]);
                have.put(col[0], cols);
            }
            if (!cols.contains(col[1])) {
                d.execSQL("ALTER TABLE " + col[0] + " ADD COLUMN " + col[1] + " " + col[2]);
                cols.add(col[1]);
                if (col[0].equals("tx") && col[1].equals("uuid")) {
                    d.execSQL("UPDATE tx SET uuid=lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid=''");
                }
            }
        }
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_office_date ON tx(office_id,created_at)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_date ON tx(created_at)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_feed_date ON audit_feed(created_at)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_lines_office_currency ON journal_lines(office_id,currency)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_lines_entry ON journal_lines(entry_id)");
        // 12.7: فهرس مغطٍّ لأرصدة المكاتب (يُقرأ وحده دون الجدول) وفهرس حالة الحركات (للملخص)
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_lines_bal ON journal_lines(account,office_id,currency,debit_minor,credit_minor)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_status ON tx(status)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action,created_at)");
        // 12.7: نص البحث (محلي على هذا الجهاز، داخل الملف المشفّر كاملًا؛ لا يُنسخ ولا يُزامن): البحث بالنص دون فك كل الحركات
        d.execSQL("CREATE TABLE IF NOT EXISTS tx_search(tx_id INTEGER PRIMARY KEY,uuid TEXT,txt TEXT)");
        d.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_upd AFTER UPDATE OF person,description,notes,dest,user_name,amount_minor,currency ON tx BEGIN DELETE FROM tx_search WHERE tx_id=NEW.id; END");
        d.execSQL("CREATE TRIGGER IF NOT EXISTS tx_search_del AFTER DELETE ON tx BEGIN DELETE FROM tx_search WHERE tx_id=OLD.id; END");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_tx ON journal_entries(tx_id)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_att_tx ON attachments(tx_id)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_cash_date ON cash_moves(created_at)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_cash_tx ON cash_moves(tx_id)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_client_moves ON client_moves(client_id,created_at)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_client_cash ON client_moves(cash_uuid)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_checks_tx ON tx_checks(tx_id)");
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_wamarks_office ON wa_marks(office_id,created_at)");
        boolean firstV5 = !hasSetting(d, "mig5");
        if (firstV5) {
            d.execSQL("UPDATE office_currencies SET limit_owe_minor=limit_minor WHERE limit_owe_minor IS NULL AND limit_minor IS NOT NULL");
            d.execSQL("UPDATE offices SET usd_owe_limit_minor=usd_limit_minor WHERE usd_owe_limit_minor IS NULL AND usd_limit_minor IS NOT NULL");
            if ("0".equals(rawSetting(d, "weekly_backup"))) d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('reminder_days','0')");
        }
        boolean firstV6 = !hasSetting(d, "mig6");
        if (firstV6) d.execSQL("UPDATE offices SET pinned=0 WHERE pinned IS NULL");
        seedDefaults(d);
        migrateBoxes(d);
        // 10.1: الحسابات القديمة تأخذ صلاحيات دورها صراحة (يعدّلها المدير بعدها واحدة واحدة)
        for (String role : new String[]{Auth.ACCOUNTANT, Auth.EMPLOYEE, Auth.VIEWER}) {
            d.execSQL("UPDATE users SET perms=? WHERE perms IS NULL AND role=?", new Object[]{Auth.legacyPerms(role), role});
        }
        // 10.3: من كان يخضع لاعتماد المبالغ الكبيرة (بلا صلاحية الاعتماد) صار «حركاته تحتاج اعتماد»
        if (!hasSetting(d, "mig_review")) {
            if ("1".equals(rawSetting(d, "approval_on"))) {
                List<Object[]> us = new ArrayList<>();
                try (Cursor c = d.rawQuery("SELECT id,role,perms FROM users WHERE role<>'ADMIN'", null)) {
                    while (c.moveToNext()) us.add(new Object[]{c.getLong(0), c.getString(1), c.isNull(2) ? null : c.getString(2)});
                }
                for (Object[] x : us) {
                    Model.User u = new Model.User();
                    u.role = (String) x[1];
                    u.perms = (String) x[2];
                    java.util.Set<String> ps = Auth.perms(u);
                    if (ps.contains(Auth.Perm.APPROVE.name()) || ps.contains(Auth.REVIEW)) continue;
                    ps.add(Auth.REVIEW);
                    d.execSQL("UPDATE users SET perms=? WHERE id=?", new Object[]{Auth.csv(ps), x[0]});
                }
            }
            d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('mig_review','1')");
        }
        SyncStore.ensureSchema(d);
        if (firstV5) d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('mig5','1')");
        if (firstV6) d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('mig6','1')");
    }

    private static boolean hasSetting(SQLiteDatabase d, String k) {
        try (Cursor c = d.rawQuery("SELECT 1 FROM settings WHERE k=?", new String[]{k})) {
            return c.moveToFirst();
        }
    }

    private static String rawSetting(SQLiteDatabase d, String k) {
        try (Cursor c = d.rawQuery("SELECT v FROM settings WHERE k=?", new String[]{k})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private void seedDefaults(SQLiteDatabase d) {
        long n = 0;
        try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM currencies", null)) {
            if (c.moveToFirst()) n = c.getLong(0);
        }
        if (n == 0) {
            String[][] a = {{"USD", "دولار أمريكي", "2"}, {"EUR", "يورو", "2"}, {"TRY", "ليرة تركية", "2"}, {"SYP", "ليرة سورية", "0"}};
            for (String[] x : a) d.execSQL("INSERT INTO currencies(code,name,decimals,active) VALUES(?,?,?,1)", x);
        }
        String[][] st = {
                {"app_title", "المحاسب الذكي لمكاتب الحوالات"}, {"office_name", ""},
                {"welcome", "إدارة آمنة وسريعة لحسابات مكاتب الحوالات"},
                {"primary_color", "#0B6B53"}, {"accent_color", "#C7922F"}, {"header_color", ""}, {"bg_color", "#F7F4ED"},
                {"home_order", DEFAULT_ORDER}, {"home_hidden", ""}, {"btn_colors", ""}, {"home_layout", "list"}, {"btn_size", "normal"},
                {"home_logo_mode", "default"}, {"home_logo_size", "medium"},
                {"developer", "برمجة: Ai creative solutions"}, {"weekly_backup", "1"}, {"reminder_days", "7"}, {"reminder_tx", "20"},
                {"lock_timeout", "5"}, {"lock_before", "0"}, {"country_code", "963"}, {"confirm_tx", "1"}
        };
        for (String[] x : st) d.execSQL("INSERT OR IGNORE INTO settings(k,v) VALUES(?,?)", x);
        // التوقيع القديم (حتى الإصدار 7.0) يُستبدل بالتوقيع الجديد، وكذلك بعد استعادة نسخة قديمة
        d.execSQL("UPDATE settings SET v=? WHERE k='developer' AND v=?", new Object[]{"برمجة: Ai creative solutions", "برمجة: أبو عمر الجوبراني"});
    }

    // ===================================================================================
    // أرقام ومبالغ
    // ===================================================================================

    /** المبلغ بالوحدة الصغرى بقراءة واحدة لكل التطبيق (انظر Amounts): «1,500» ألف وخمسمئة لا 1.5. */
    public static long minor(String text, int dec) {
        return Amounts.minor(text, dec);
    }

    /** رقم عام (حدود، أسعار، منازل) بقواعد منزلتين؛ المبالغ تمر بـ minor مع منازل عملتها. */
    public static String normalizeNumber(String t) {
        return Amounts.normalize(t, 2);
    }

    public static String money(long m, int dec) { return Model.plain(m, dec); }

    public static String fmt(long m, int dec) { return Model.fmt(m, dec); }

    public static String fmtUsd(BigDecimal v) {
        java.text.DecimalFormat f = new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(v);
    }

    public synchronized int decimals(String code) {
        if (code == null) return 2;
        Integer c = decimalsCache.get(code);
        if (c != null) return c;
        int v = 2;
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT decimals FROM currencies WHERE code=?", new String[]{code})) {
            if (cur.moveToFirst()) v = cur.getInt(0);
        }
        decimalsCache.put(code, v);
        return v;
    }

    public synchronized Map<String, Integer> decimalsMap() {
        Map<String, Integer> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,decimals FROM currencies", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getInt(1));
        }
        return m;
    }

    public Map<String, String> currencyNames() {
        Map<String, String> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,name FROM currencies", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getString(1));
        }
        return m;
    }

    // ===================================================================================
    // الإعدادات
    // ===================================================================================

    /**
     * 10.12: تفضيلات المظهر لكل مستخدم وحده (الشريط السفلي، الألوان، الوضع الليلي، الخط، ترتيب الرئيسية، الاهتزاز…).
     * تُحفظ باسم «u<رقم المستخدم>.<المفتاح>»، فما يغيّره مستخدم لا يمسّ غيره. مستخدم لم يخصّص شيئًا يرى القيمة العامة السابقة
     * (فلا يتغيّر مظهر أحد بعد التحديث، ولا ترحيل للبيانات). بلا حسابات (مستخدم واحد) تبقى القيمة العامة كما كانت.
     */
    public static final Set<String> PERSONAL = new HashSet<>(Arrays.asList(
            "tabs", "theme_mode", "app_font", "primary_color", "accent_color", "header_color", "bg_color",
            "home_order", "home_hidden", "btn_colors", "home_layout", "btn_size", "home_style", "home_widgets",
            "desk_theme", "haptics", "hide_amounts", "office_sort",
            // 10.13: خيارات المظهر والسلوك الجديدة، وترتيب «مكاتبي» وصفحتها
            "btn_frames", "page_motion", "hold_confirm", "tab_style", "mine_order", "mine_sort", "mine_page",
            // 10.15: إعدادات الشريط الزجاجي وقائمة «+»
            "nav_prefs",
            // 11.3: خلفية الوضع الليلي، ولون الزر المختار في الشريط السفلي، وأنماطي المحفوظة
            "night_bg", "tab_tint", "design_styles",
            // 11.5: فتح أو طي «آخر المكاتب» و«مكاتبي» في نافذة اختيار المكتب
            "picker_open_recent", "picker_open_mine"));
    /** قيمة «محذوفة» لمستخدم: تعني الافتراضي، لا الرجوع إلى القيمة العامة. */
    static final String UNSET = "::default::";

    public static boolean personal(String k) {
        return k != null && (PERSONAL.contains(k) || k.startsWith("hw_"));
    }

    /** مفتاح التفضيل للمستخدم الحالي، أو null إن لم يكن تفضيلًا شخصيًا أو لا مستخدم داخل. */
    static String userKey(String k) {
        Model.User u = SmartApp.currentUser;
        return u == null || !personal(k) ? null : "u" + u.id + "." + k;
    }

    /** المفتاح الأصلي لمفتاح شخصي («u12.tabs» ← «tabs»)، وإلا المفتاح نفسه. */
    public static String baseKey(String k) {
        if (k == null || k.length() < 3 || k.charAt(0) != 'u' || !Character.isDigit(k.charAt(1))) return k;
        int dot = k.indexOf('.');
        if (dot < 0) return k;
        for (int i = 1; i < dot; i++) if (!Character.isDigit(k.charAt(i))) return k;
        return k.substring(dot + 1);
    }

    public String setting(String k, String def) {
        String uk = userKey(k);
        if (uk != null) {
            String v = rawSetting(uk);
            if (v != null) return UNSET.equals(v) ? def : v;
        }
        String v = rawSetting(k);
        return v == null ? def : v;
    }

    private String rawSetting(String k) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM settings WHERE k=?", new String[]{k})) {
            if (!c.moveToFirst()) return null;
            return c.getString(0);
        }
    }

    /** القيمة العامة (لا الشخصية) — للاختبارات والترحيل. */
    public String globalSetting(String k, String def) {
        String v = rawSetting(k);
        return v == null ? def : v;
    }

    public long settingLong(String k, long def) {
        try {
            return Long.parseLong(setting(k, "" + def).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public Map<String, String> settings(String[] keys) {
        Map<String, String> m = new HashMap<>();
        for (String k : keys) {
            String v = setting(k, null);
            if (v != null) m.put(k, v);
        }
        return m;
    }

    public void setSetting(String k, String v) {
        setSettingQuiet(k, v);
        audit("SETTING", "settings", 0, k);
    }

    /**
     * 10.18: يحمّل «توقيت المكتب» (إعداد عام يتزامن: office_tz) وفرق تصحيح الساعة المحفوظ على هذا الجهاز (clock_offset_ms)،
     * ويربط حفظ الفرق عند تعلّمه. يُستدعى عند فتح القاعدة وبعد المزامنة والاستعادة وتغيير الإعداد.
     */
    void loadClock(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery("SELECT k,v FROM settings WHERE k IN ('office_tz','clock_offset_ms')", null)) {
            String tz = "";
            long off = 0;
            while (c.moveToNext()) {
                if ("office_tz".equals(c.getString(0))) tz = Model.nz(c.getString(1));
                else try {
                    off = Long.parseLong(c.getString(1));
                } catch (Exception ignored) {
                    // قيمة تالفة = بلا تصحيح
                }
            }
            Model.setZone(tz);
            if (Clock.learnedAt() == 0) Clock.load(off);
        } catch (Exception ignored) {
            // قاعدة قيد الإنشاء
        }
        Clock.saver = o -> {
            try {
                getWritableDatabase().execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('clock_offset_ms',?)", new Object[]{"" + o});
            } catch (Throwable ignored) {
                // يُحفظ في المرة التالية
            }
        };
    }

    public void reloadClock() { loadClock(getReadableDatabase()); }

    /** يحدد المدير توقيت المكتب الموحّد ("" = توقيت كل جهاز كما كان). */
    /**
     * 10.18: حالة الساعة الموحّدة للعرض في الإعدادات: وقت المكتب الآن ومنطقته، وفرق ساعة هذا الجهاز عن الوقت الصحيح ومتى قيس.
     */
    public List<String> clockLines() {
        List<String> l = new ArrayList<>();
        l.add("وقت المكتب الآن: " + Model.date(Clock.now()) + " — " + Model.zoneLabel(Model.zoneId()));
        long off = Clock.offset();
        if (Clock.learnedAt() <= 0 && off == 0) l.add("ساعة هذا الجهاز: لم تُقَس بعد (تُقاس تلقائيًا عند أول مزامنة عبر Google Drive).");
        else if (off == 0) l.add("ساعة هذا الجهاز: مضبوطة ✓");
        else {
            long sec = Math.abs(off) / 1000;
            String d = sec >= 3600 ? (sec / 3600) + " ساعة و" + (sec % 3600 / 60) + " دقيقة" : sec >= 60 ? (sec / 60) + " دقيقة" : sec + " ثانية";
            l.add("ساعة هذا الجهاز " + (off > 0 ? "متأخرة" : "متقدمة") + " " + d + " عن الوقت الصحيح: يصحّحها البرنامج تلقائيًا في كل ما يسجّله ويعرضه.");
        }
        return l;
    }

    /** فرق ساعة الجهاز كبير (أكثر من دقيقتين): يُنبَّه المستخدم ليضبط ساعة جهازه، مع أن البرنامج يصحّح. */
    public static boolean clockOff() { return Math.abs(Clock.offset()) > 120_000L; }

    public void setOfficeZone(String id) {
        require(Auth.Perm.SETTINGS);
        String v = id == null ? "" : id.trim();
        if (!v.isEmpty()) {
            boolean ok = false;
            for (String x : java.util.TimeZone.getAvailableIDs()) if (x.equals(v)) ok = true;
            if (!ok) throw new IllegalArgumentException("منطقة زمنية غير معروفة: " + v);
        }
        if (v.isEmpty()) deleteSetting("office_tz");
        else setSetting("office_tz", v);
        reloadClock();
    }

    /** 10.16: إعدادات عامة حساسة لا تُكتب إلا بصلاحيتها حتى لو نسيت شاشة الفحص (الباقي كما كان). */
    static final Map<String, Auth.Perm> PROTECTED_SETTINGS = new HashMap<>();
    static {
        PROTECTED_SETTINGS.put("lock_before", Auth.Perm.SETTINGS);
        PROTECTED_SETTINGS.put("approval_on", Auth.Perm.SETTINGS);
        PROTECTED_SETTINGS.put("approval_usd", Auth.Perm.SETTINGS);
        PROTECTED_SETTINGS.put("office_tz", Auth.Perm.SETTINGS);
    }

    public void setSettingQuiet(String k, String v) {
        Auth.Perm need = PROTECTED_SETTINGS.get(k);
        if (need != null) require(need);
        String uk = userKey(k);
        if (uk != null) k = uk;
        ContentValues x = new ContentValues();
        x.put("k", k);
        x.put("v", v);
        getWritableDatabase().insertWithOnConflict("settings", null, x, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** الإقفال المحاسبي: لا تُسجَّل ولا تُعدَّل حركات قبل هذا التاريخ. للمدير وحده. */
    public void setLockBefore(long until) {
        require(Auth.Perm.SETTINGS);
        setSetting("lock_before", "" + Math.max(0, until));
    }

    /**
     * 8.0: إعداد نصه حساس (مسودة الحركة، طابور رسائل واتساب) يُحفظ مشفّرًا بمفتاح الهاتف كحقول الحركات.
     * القيمة القديمة غير المشفّرة (من 7.x) تُقرأ كما هي مرة واحدة ثم تُكتب مشفّرة.
     */
    public String secretSetting(String k) {
        String v = setting(k, "");
        if (v.isEmpty()) return "";
        try {
            return Crypto.decStrict(v);
        } catch (Exception e) {
            String t = v.trim();
            return t.startsWith("{") || t.startsWith("[") ? v : "";
        }
    }

    public void setSecretSetting(String k, String v) {
        if (v == null || v.isEmpty()) deleteSetting(k);
        else setSettingQuiet(k, Crypto.enc(v));
    }

    public void deleteSetting(String k) {
        Auth.Perm need = PROTECTED_SETTINGS.get(k);
        if (need != null) require(need);
        String uk = userKey(k);
        if (uk != null) {
            // للمستخدم: «الافتراضي» لا القيمة العامة (وإعادة الضبط لا تمسّ غيره)
            ContentValues x = new ContentValues();
            x.put("k", uk);
            x.put("v", UNSET);
            getWritableDatabase().insertWithOnConflict("settings", null, x, SQLiteDatabase.CONFLICT_REPLACE);
            return;
        }
        getWritableDatabase().delete("settings", "k=?", new String[]{k});
    }

    // ===================================================================================
    // حدود النسخة المجانية (9.0): 9 مكاتب (الحذف يعيد المكان) و700 حركة جديدة لكل مكتب
    // ===================================================================================

    /** بصمة العدّادات (Keystore على الهاتف). «» = تعذّرت، فتحكم أرضية البيانات الحية. */
    static java.util.function.Function<String, String> quotaMac = License::mac;

    /** أرضية المكاتب: الموجودة الآن (النشطة والموقوفة). */
    private static long liveOffices(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM offices", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /**
     * أرضية الحركات: الأصلية لكل مكتب (الملغاة تُحتسب حتى لا يكون الإلغاء طريقًا لتصفير السقف،
     * والحركة المصحَّحة replaces_id لا تُحتسب لأن التصحيح مجاني دائمًا). office&lt;0: لا شيء، 0: كل المكاتب.
     */
    private static Map<Long, Long> liveTx(SQLiteDatabase d, long office) {
        Map<Long, Long> m = new HashMap<>();
        if (office < 0) return m;
        try (Cursor c = d.rawQuery("SELECT office_id,COUNT(*) FROM tx WHERE replaces_id IS NULL"
                + (office > 0 ? " AND office_id=" + office : "") + " GROUP BY office_id", null)) {
            while (c.moveToNext()) m.put(c.getLong(0), c.getLong(1));
        }
        return m;
    }

    /**
     * العدّادات كما تُحفظ مرفوعة إلى أرضية البيانات الحية. البصمة المختلفة تعني عبثًا بالإعدادات:
     * تُسجَّل ملاحظة، وأرضية المكاتب تؤخذ أيضًا من سجل التدقيق (المكاتب المنشأة ولو حُذفت).
     */
    private QuotaState quota(SQLiteDatabase d, long office) {
        String raw = rawSetting(d, "quota");
        QuotaState q = QuotaState.parse(raw);
        String stored = rawSetting(d, "quota_mac");
        String want = raw == null ? "" : quotaMac.apply(q.serialize());
        if (raw != null && !want.isEmpty() && !want.equals(stored)) {
            d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('risk_note',?)", new Object[]{"عدّادات النسخة المجانية عُدّلت خارج التطبيق؛ أُعيد بناؤها من البيانات."});
        }
        // 9.0: حذف المكتب يعيد مكانه، فعدّاد المكاتب هو الموجود فعلًا دائمًا (حتى لو حمل العدّاد القديم مكاتب محذوفة)
        q.offices = 0;
        q.raiseTo(liveOffices(d), liveTx(d, office));
        return q;
    }

    private static void saveQuota(SQLiteDatabase d, QuotaState q) {
        String s = q.serialize();
        d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('quota',?)", new Object[]{s});
        d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('quota_mac',?)", new Object[]{quotaMac.apply(s)});
    }

    private static boolean unlimited(Entitlements e) {
        return e.maxOffices() == Entitlements.UNLIMITED && e.maxTxPerOffice() == Entitlements.UNLIMITED;
    }

    /** للعرض في شاشة الترخيص: العدّادات الآن (مرفوعة إلى البيانات الحية لكل المكاتب). */
    public QuotaState quotaNow() {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            QuotaState q = quota(d, 0);
            d.setTransactionSuccessful();
            return q;
        } finally {
            d.endTransaction();
        }
    }

    /** بعد الإضافة: العدّادات تُرفع إلى البيانات الحية (المكاتب الجديدة وحركاتها) ولا تنقص. */
    private void rebaseQuotaKeep(SQLiteDatabase d) {
        QuotaState q = quota(d, 0);
        saveQuota(d, q);
    }

    /** بعد «استبدال الكل»: البيانات كلها جديدة فتُبنى العدّادات منها (أرقام المكاتب القديمة لا تعني شيئًا الآن). */
    private static void rebaseQuota(SQLiteDatabase d) {
        QuotaState q = new QuotaState();
        q.raiseTo(liveOffices(d), liveTx(d, 0));
        saveQuota(d, q);
    }

    // ===================================================================================
    // المكاتب والعملات
    // ===================================================================================

    public static class Limit {
        public Long receivable;
        public Long payable;

        public Limit(Long receivable, Long payable) {
            this.receivable = receivable;
            this.payable = payable;
        }
    }

    public long addOffice(String name, String sh, String phone, String loc, String contact, String notes, Long usdUs, Long usdOwe,
                          List<String> codes, Map<String, Limit> limits, List<Opening> openings) {
        require(Auth.Perm.OFFICES);
        Entitlements ent = License.current;
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            QuotaState q = unlimited(ent) ? null : quota(d, -1);
            String no = q == null ? null : q.officeRefusal(ent);
            if (no != null) throw new Entitlements.LimitReached(no);
            ContentValues v = officeValues(name, sh, phone, loc, contact, notes, usdUs, usdOwe);
            long id = ins(d, "offices", v);
            if (q != null) {
                q.offices++;
                saveQuota(d, q);
            }
            saveOfficeCurrencies(d, id, codes, limits);
            long now = Clock.now();
            for (Opening o : openings) {
                if (!codes.contains(o.code)) throw new IllegalArgumentException("الرصيد السابق بعملة " + o.code + " غير مفعّلة لهذا المكتب. فعّل العملة أولًا.");
                long eid = postEntry(d, null, "OPENING", id, o.code, o.type.equals("لنا") ? o.amount : 0, o.type.equals("علينا") ? o.amount : 0, now);
                ContentValues z = new ContentValues();
                z.put("office_id", id);
                z.put("currency", o.code);
                z.put("type", o.type);
                z.put("amount_minor", o.amount);
                z.put("entry_id", eid);
                ins(d, "opening_balances", z);
            }
            audit(d, "CREATE_OFFICE", "office", id, "");
            d.setTransactionSuccessful();
            return id;
        } finally {
            d.endTransaction();
        }
    }

    public void updateOffice(long id, String name, String sh, String phone, String loc, String contact, String notes, boolean active,
                             Long usdUs, Long usdOwe, List<String> codes, Map<String, Limit> limits) {
        require(Auth.Perm.OFFICES);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = officeValues(name, sh, phone, loc, contact, notes, usdUs, usdOwe);
            v.put("active", active ? 1 : 0);
            d.update("offices", v, "id=" + id, null);
            d.delete("office_currencies", "office_id=" + id, null);
            saveOfficeCurrencies(d, id, codes, limits);
            audit(d, "UPDATE_OFFICE", "office", id, "");
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    public void setPinned(long officeId, boolean pinned) {
        ContentValues v = new ContentValues();
        v.put("pinned", pinned ? 1 : 0);
        getWritableDatabase().update("offices", v, "id=" + officeId, null);
    }

    /** عدد حركات المكتب (النشطة والملغاة). */
    public long officeTxCount(long officeId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tx WHERE office_id=" + officeId, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** إيقاف المكتب أو تفعيله: الموقوف لا يظهر في تسجيل الحركات ولا تُقبل عليه حركات جديدة، ويبقى كشفه ورصيده. */
    public void setOfficeActive(long officeId, boolean active) {
        require(Auth.Perm.OFFICES);
        ContentValues v = new ContentValues();
        v.put("active", active ? 1 : 0);
        getWritableDatabase().update("offices", v, "id=" + officeId, null);
        audit(active ? "ACTIVATE_OFFICE" : "STOP_OFFICE", "office", officeId, "");
    }

    /**
     * يحذف المكتب نهائيًا مع أرصدته السابقة وحدوده، ومع حركاته وقيودها وصورها إن سُمح بذلك.
     * يُرفض إن كان للمكتب حركات داخل فترة محاسبية مقفلة.
     */
    public void deleteOffice(long officeId, boolean withTransactions) {
        require(Auth.Perm.DELETE);
        Model.Office o = office(officeId);
        if (o == null) throw new IllegalArgumentException("المكتب غير موجود");
        long txs = officeTxCount(officeId);
        if (txs > 0 && !withTransactions) throw new IllegalStateException("للمكتب حركات مسجّلة؛ أوقفه بدل حذفه أو اختر الحذف النهائي مع الحركات.");
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            long lock = lockBefore(d);
            if (lock > 0) {
                try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM tx WHERE office_id=" + officeId + " AND created_at<" + lock, null)) {
                    if (c.moveToFirst() && c.getLong(0) > 0) {
                        throw new IllegalStateException("للمكتب حركات داخل فترة محاسبية مقفلة؛ لا يمكن حذفه. يمكنك إيقافه.");
                    }
                }
            }
            QuotaState q = quota(d, officeId);
            purgeOffice(d, officeId);
            q.officeDeleted(officeId);
            saveQuota(d, q);
            audit(d, "DELETE_OFFICE", "office", officeId, Model.nz(o.name) + (txs > 0 ? " — مع " + txs + " حركة" : ""));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        try {
            cleanupEvidence();
        } catch (Exception ignored) {
            // الصور اليتيمة تُنظَّف لاحقًا
        }
    }

    /**
     * يحذف المكتب وكل ما يخصه (حركاته وقيودها وصورها وأرصدته السابقة ومطابقاته وعملاته) في المعاملة الحالية.
     * حركات الصندوق المرتبطة به لا تُحذف (النقد خرج أو دخل فعلًا): يُفك ربطها فتبقى في الصندوق كحركات عامة.
     */
    private static void purgeOffice(SQLiteDatabase d, long officeId) {
        d.execSQL("DELETE FROM office_holds WHERE office_id=" + officeId);
        String txIds = "SELECT id FROM tx WHERE office_id=" + officeId;
        String entryIds = "SELECT id FROM journal_entries WHERE tx_id IN (" + txIds + ")"
                + " UNION SELECT entry_id FROM journal_lines WHERE office_id=" + officeId
                + " UNION SELECT entry_id FROM opening_balances WHERE office_id=" + officeId + " AND entry_id IS NOT NULL";
        d.execSQL("CREATE TEMP TABLE IF NOT EXISTS del_entries(id INTEGER PRIMARY KEY)");
        d.execSQL("DELETE FROM del_entries");
        d.execSQL("INSERT OR IGNORE INTO del_entries(id) " + entryIds);
        d.execSQL("DELETE FROM journal_lines WHERE entry_id IN (SELECT id FROM del_entries)");
        d.execSQL("DELETE FROM journal_entries WHERE id IN (SELECT id FROM del_entries)");
        d.execSQL("DELETE FROM del_entries");
        d.execSQL("DELETE FROM attachments WHERE tx_id IN (" + txIds + ")");
        d.execSQL("UPDATE tx SET replaces_id=NULL WHERE replaces_id IN (" + txIds + ") AND office_id<>" + officeId);
        // 10.5 (AUDIT-029): حركة صندوق مرتبطة بحركة مكتب غير نافذة (معلّقة أو مرفوضة أو ملغاة) لا تصير محسوبة بفك ربطها:
        // تُلغى بسببها. والمرتبطة بحركة نافذة يُفك ربطها فتبقى في الصندوق كما كانت محسوبة (النقد خرج أو دخل فعلًا).
        ContentValues cc = new ContentValues();
        cc.put("status", CashBox.CANCELLED);
        cc.put("cancel_reason", Crypto.enc("حُذف المكتب وحركته لم تكن معتمدة نافذة"));
        cc.put("cancelled_at", Clock.now());
        d.update("cash_moves", cc, "status='ACTIVE' AND tx_id IN (SELECT id FROM tx WHERE office_id=" + officeId + " AND status<>'ACTIVE')", null);
        d.execSQL("UPDATE cash_moves SET tx_id=NULL,office_id=NULL,category='لحساب مكتب محذوف' WHERE office_id=" + officeId + " OR tx_id IN (" + txIds + ")");
        // 10.5 (§9): ساق التحويل عند المكتب الآخر تبقى حركة عادية له (ساقها هنا حُذفت مع المكتب)، فلا تبقى عملية ناقصة
        d.execSQL("UPDATE tx SET pair_uuid=NULL,fx_rate=NULL WHERE office_id<>" + officeId + " AND pair_uuid IN (SELECT pair_uuid FROM tx WHERE office_id=" + officeId
                + " AND pair_uuid IS NOT NULL AND pair_uuid<>'')");
        // 10.5 (§7): التحقق من حركاته، ومكاتب المحاسبين، وعلامة آخر رسالة
        d.execSQL("DELETE FROM tx_checks WHERE tx_id IN (" + txIds + ")");
        d.execSQL("DELETE FROM user_offices WHERE office_id=" + officeId);
        d.execSQL("DELETE FROM wa_marks WHERE office_id=" + officeId);
        d.execSQL("DELETE FROM office_work WHERE office_id=" + officeId);
        d.execSQL("DELETE FROM tx WHERE office_id=" + officeId);
        d.execSQL("DELETE FROM opening_balances WHERE office_id=" + officeId);
        d.execSQL("DELETE FROM reconciliation_lines WHERE recon_id IN (SELECT id FROM reconciliations WHERE office_id=" + officeId + ")");
        d.execSQL("DELETE FROM reconciliations WHERE office_id=" + officeId);
        d.execSQL("DELETE FROM office_currencies WHERE office_id=" + officeId);
        d.execSQL("DELETE FROM offices WHERE id=" + officeId);
    }

    public boolean officeActive(long officeId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT active FROM offices WHERE id=" + officeId, null)) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }

    private static ContentValues officeValues(String name, String sh, String phone, String loc, String contact, String notes, Long usdUs, Long usdOwe) {
        ContentValues v = new ContentValues();
        v.put("name", Crypto.enc(name));
        v.put("short_name", Crypto.enc(sh));
        v.put("phone", Crypto.enc(phone));
        v.put("location", Crypto.enc(loc));
        v.put("contact", Crypto.enc(contact));
        v.put("notes", Crypto.enc(notes));
        if (usdUs == null) v.putNull("usd_limit_minor");
        else v.put("usd_limit_minor", usdUs);
        if (usdOwe == null) v.putNull("usd_owe_limit_minor");
        else v.put("usd_owe_limit_minor", usdOwe);
        return v;
    }

    private void saveOfficeCurrencies(SQLiteDatabase d, long id, List<String> codes, Map<String, Limit> limits) {
        for (String code : codes) {
            ContentValues x = new ContentValues();
            x.put("office_id", id);
            x.put("code", code);
            Limit l = limits.get(code);
            if (l != null && l.receivable != null) x.put("limit_minor", l.receivable);
            if (l != null && l.payable != null) x.put("limit_owe_minor", l.payable);
            d.insertOrThrow("office_currencies", null, x);
        }
    }

    private static final String OFFICE_COLS = "id,name,short_name,phone,location,contact,notes,active,usd_limit_minor,usd_owe_limit_minor,pinned";

    /** المكاتب: المثبّتة أولًا، ثم النشطة، ثم أبجديًا. */
    public List<Model.Office> offices() {
        List<Model.Office> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + OFFICE_COLS + " FROM offices", null)) {
            while (c.moveToNext()) a.add(readOffice(c));
        }
        sortOffices(a);
        return a;
    }

    static void sortOffices(List<Model.Office> a) {
        final Collator col = Collator.getInstance(new Locale("ar"));
        Collections.sort(a, new Comparator<Model.Office>() {
            @Override public int compare(Model.Office x, Model.Office y) {
                if (x.pinned != y.pinned) return x.pinned ? -1 : 1;
                if (x.active != y.active) return x.active ? -1 : 1;
                return col.compare(Model.nz(x.name), Model.nz(y.name));
            }
        });
    }

    public Model.Office office(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + OFFICE_COLS + " FROM offices WHERE id=" + id, null)) {
            return c.moveToFirst() ? readOffice(c) : null;
        }
    }

    public Map<Long, String> officeNames() {
        Map<Long, String> m = new HashMap<>();
        for (Model.Office o : offices()) m.put(o.id, o.name);
        return m;
    }

    private static Model.Office readOffice(Cursor c) {
        Model.Office o = new Model.Office();
        o.id = c.getLong(0);
        o.name = Crypto.dec(c.getString(1));
        o.shortName = Crypto.dec(c.getString(2));
        o.phone = Crypto.dec(c.getString(3));
        o.location = Crypto.dec(c.getString(4));
        o.contact = Crypto.dec(c.getString(5));
        o.notes = Crypto.dec(c.getString(6));
        o.active = c.getInt(7) == 1;
        o.usdLimit = c.isNull(8) ? null : c.getLong(8);
        o.usdOweLimit = c.isNull(9) ? null : c.getLong(9);
        o.pinned = !c.isNull(10) && c.getInt(10) == 1;
        return o;
    }

    public List<String> officeCurrencies(long id) {
        List<String> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code FROM office_currencies WHERE office_id=" + id + " ORDER BY code", null)) {
            while (c.moveToNext()) a.add(c.getString(0));
        }
        return a;
    }

    public Map<String, Limit> officeLimits(long id) {
        Map<String, Limit> a = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,limit_minor,limit_owe_minor FROM office_currencies WHERE office_id=" + id, null)) {
            while (c.moveToNext()) a.put(c.getString(0), new Limit(c.isNull(1) ? null : c.getLong(1), c.isNull(2) ? null : c.getLong(2)));
        }
        return a;
    }

    public List<Model.Currency> currencies(boolean all) {
        refreshMetals(getReadableDatabase());
        List<Model.Currency> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,name,decimals,active FROM currencies" + (all ? "" : " WHERE active=1") + " ORDER BY code", null)) {
            while (c.moveToNext()) a.add(new Model.Currency(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3) == 1));
        }
        return a;
    }

    public synchronized void saveCurrency(String code, String name, int dec, boolean active) {
        String cc = code.trim().toUpperCase(Locale.US);
        // 12.5: المعدن يُحفظ بإعداداته كلها (saveMetal)؛ هنا الاسم والتفعيل فقط، ومنازله من وحدته. 12.6: صلاحيته «المعادن»
        Metals.Metal mt = Metals.of(cc);
        if (mt != null) {
            saveMetal(cc, name, mt.base, mt.fineness, mt.unit, mt.valued, active);
            return;
        }
        require(Auth.Perm.CURRENCIES);
        SQLiteDatabase d = getWritableDatabase();
        // 10.11 (AUDIT-004): المبالغ محفوظة بأصغر وحدة، فتغيير منازل عملة مستعملة يغيّر قيمة كل مبالغها
        try (Cursor c = d.rawQuery("SELECT decimals FROM currencies WHERE code=?", new String[]{cc})) {
            if (c.moveToFirst() && c.getInt(0) != dec && currencyUsed(cc))
                throw new IllegalStateException("لا يمكن تغيير المنازل العشرية للعملة " + cc + ": عليها حركات أو أرصدة (في المكاتب أو الصندوق أو حسابات العملاء)، وتغييرها يغيّر قيمة المبالغ المسجّلة.");
        }
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("decimals", dec);
        v.put("active", active ? 1 : 0);
        if (d.update("currencies", v, "code=?", new String[]{cc}) == 0) {
            v.put("code", cc);
            d.insertOrThrow("currencies", null, v);
        }
        decimalsCache.remove(cc);
        audit("SAVE_CURRENCY", "currency", 0, cc);
    }

    // ===================================================================================
    // 12.5: المعادن الثمينة
    // ===================================================================================

    /** يقرأ حسابات المعادن إلى سجل Metals (يقرؤه المحلّل والتقويم). رخيص: يُستدعى مع قراءة العملات والأسعار. */
    void refreshMetals(SQLiteDatabase d) {
        List<Metals.Metal> l = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT code,metal,unit,fineness,valued FROM currencies WHERE kind='METAL'", null)) {
            while (c.moveToNext())
                l.add(new Metals.Metal(c.getString(0), c.getString(1), c.getString(2), c.isNull(3) ? 0 : c.getInt(3), !c.isNull(4) && c.getInt(4) == 1));
        }
        Metals.set(l);
    }

    public List<Metals.Metal> metals() {
        refreshMetals(getReadableDatabase());
        List<Metals.Metal> out = new ArrayList<>();
        for (Model.Currency c : currencies(true)) {
            Metals.Metal m = Metals.of(c.code);
            if (m != null) out.add(m);
        }
        return out;
    }

    /**
     * 12.5: حساب معدن (جديد أو تعديل). المعدن والعيار والوحدة (ومعها المنازل) لا تتغير بعد أول حركة؛
     * الاسم والتفعيل والتقويم بالدولار تتغير. الرمز لا يكون رمز عملة موجودة.
     */
    public synchronized void saveMetal(String code, String name, String base, int fineness, String unit, boolean valued, boolean active) {
        require(Auth.Perm.METALS);   // 12.6
        String cc = Model.nz(code).trim().toUpperCase(Locale.US);
        if (!cc.matches("[A-Z][A-Z0-9]{1,9}")) throw new IllegalArgumentException("الرمز أحرف إنجليزية وأرقام (2 إلى 10) يبدأ بحرف، مثل XAU21");
        if (Model.nz(name).trim().isEmpty()) throw new IllegalArgumentException("اكتب اسم المعدن");
        if (!Metals.UNITS.containsKey(unit)) throw new IllegalArgumentException("وحدة الوزن غير معروفة");
        if (!Arrays.asList(Metals.BASES).contains(base)) throw new IllegalArgumentException("نوع المعدن غير معروف");
        if (fineness < 0 || fineness > 1000) throw new IllegalArgumentException("العيار بالألف بين 1 و1000 (مثل 875 لعيار 21)");
        int dec = Metals.unit(unit).decimals;
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            boolean unchanged = false;   // 12.6: تعديل الاسم أو التفعيل لحساب قائم لا يخضع لفحص التكرار
            try (Cursor c = d.rawQuery("SELECT kind,metal,unit,fineness,decimals FROM currencies WHERE code=?", new String[]{cc})) {
                if (c.moveToFirst()) {
                    if (!"METAL".equals(c.getString(0))) throw new IllegalStateException("الرمز " + cc + " لعملة موجودة: اختر رمزًا آخر");
                    boolean same = Model.nz(c.getString(1)).equals(base) && Model.nz(c.getString(2)).equals(unit) && (c.isNull(3) ? 0 : c.getInt(3)) == fineness
                            && c.getInt(4) == dec;
                    unchanged = same;
                    if (!same && currencyUsed(cc))
                        throw new IllegalStateException("لا يمكن تغيير المعدن أو العيار أو الوحدة لـ" + cc + ": عليه حركات أو أرصدة، وتغييرها يغيّر معنى كل وزن مسجّل. أنشئ حسابًا جديدًا وانقل إليه بـ«قص».");
                }
            }
            // 12.6: المعدن بالعيار والوحدة نفسيهما حساب واحد؛ وعيار غير محدد بجانب حساب آخر لنفس المعدن يلتبس فيُرفض
            if (!unchanged) try (Cursor c = d.rawQuery("SELECT code,unit,fineness FROM currencies WHERE kind='METAL' AND metal=? AND code<>?", new String[]{base, cc})) {
                while (c.moveToNext()) {
                    int f2 = c.isNull(2) ? 0 : c.getInt(2);
                    if (f2 == fineness && Model.nz(c.getString(1)).equals(unit))
                        throw new IllegalStateException("عندك حساب " + Metals.baseName(base) + (fineness > 0 ? " " + Metals.purityLabel(base, fineness) : "") + " بالـ" + Metals.unit(unit).name
                                + " باسم «" + c.getString(0) + "». كل عيار حساب واحد.");
                    if (fineness == 0 && !Metals.OTHER.equals(base))
                        throw new IllegalStateException("اختر العيار: عندك حساب " + Metals.baseName(base) + " آخر («" + c.getString(0) + "»)، والعيار يميّز بينهما");
                }
            }
            ContentValues v = new ContentValues();
            v.put("name", name.trim());
            v.put("decimals", dec);
            v.put("active", active ? 1 : 0);
            v.put("kind", "METAL");
            v.put("metal", base);
            v.put("unit", unit);
            v.put("fineness", fineness);
            v.put("valued", valued ? 1 : 0);
            if (d.update("currencies", v, "code=?", new String[]{cc}) == 0) {
                v.put("code", cc);
                d.insertOrThrow("currencies", null, v);
            }
            audit(d, "SAVE_METAL", "currency", 0, cc + " " + Metals.baseName(base) + " " + Metals.purityLabel(base, fineness) + " " + Metals.unit(unit).name
                    + (valued ? " — يدخل التقويم بالدولار" : " — بالوزن فقط"));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        synchronized (this) { decimalsCache.remove(cc); }
        refreshMetals(d);
    }

    /** 10.11 (AUDIT-004): مستعملة في قيود المكاتب أو الحركات المعلّقة أو الصندوق أو حسابات العملاء. */
    public boolean currencyUsed(String code) {
        String q = "SELECT 1 FROM journal_lines WHERE currency=?1 UNION ALL SELECT 1 FROM tx WHERE currency=?1 OR fee_currency=?1"
                + " UNION ALL SELECT 1 FROM cash_moves WHERE currency=?1 UNION ALL SELECT 1 FROM client_moves WHERE currency=?1 LIMIT 1";
        try (Cursor c = getReadableDatabase().rawQuery(q, new String[]{code})) {
            return c.moveToFirst();
        }
    }

    // ===================================================================================
    // الحركات
    // ===================================================================================

    public static final class TxInput {
        public long office;
        public String type, person = "", currency, feeType = FEE_NONE, feeCurrency, description = "", notes = "", service = "", dest = "";
        /** 12.4: نوع الحركة الخاصة (فارغ = عادية)، وهل تُحسب في الرصيد. */
        public String special = "";
        public boolean specialBal = true;
        /** ساقا تبديل العملة فقط (يملؤهما addFx). */
        String pairUuid, fxRate;
        public long amount, fee;
        public long when;
    }

    private void validate(TxInput in) {
        if (in.amount <= 0) throw new IllegalArgumentException("المبلغ يجب أن يكون أكبر من صفر");
        if (!TYPE_DEBIT.equals(in.type) && !TYPE_CREDIT.equals(in.type)) throw new IllegalArgumentException("نوع الحركة غير صالح");
        boolean hasFee = !FEE_NONE.equals(in.feeType);
        if (hasFee && (in.fee <= 0 || in.feeCurrency == null)) throw new IllegalArgumentException("اكتب مبلغ الأجرة وعملتها أو اختر «بدون أجرة»");
        if (hasFee && !FEE_US.equals(in.feeType) && !FEE_THEM.equals(in.feeType)) throw new IllegalArgumentException("نوع الأجرة غير صالح");
        Model.Office off = office(in.office);
        if (off == null) throw new IllegalArgumentException("المكتب غير موجود");
        if (!off.active) throw new IllegalArgumentException("المكتب «" + Model.nz(off.name) + "» موقوف. فعّله من قائمة المكاتب لتسجيل حركات عليه.");
        List<String> allowed = officeCurrencies(in.office);
        if (!allowed.contains(in.currency)) throw new IllegalArgumentException("العملة " + in.currency + " غير مفعّلة لهذا المكتب");
        if (hasFee && !allowed.contains(in.feeCurrency)) throw new IllegalArgumentException("عملة الأجرة " + in.feeCurrency + " غير مفعّلة لهذا المكتب");
        if (in.when > Clock.now() + 10 * 60000L) throw new IllegalArgumentException("لا يمكن تسجيل حركة بتاريخ في المستقبل");
        if (in.when <= 0) throw new IllegalArgumentException("تاريخ الحركة غير صالح");
        if (Model.nz(in.special).trim().length() > 40) throw new IllegalArgumentException("اسم نوع الحركة الخاصة طويل (40 حرفًا على الأكثر)");
    }

    private static boolean special(TxInput in) { return in != null && !Model.nz(in.special).trim().isEmpty(); }

    private List<File> writeImages(List<byte[]> images) throws IOException {
        List<File> written = new ArrayList<>();
        if (images == null || images.isEmpty()) return written;
        File dir = evidenceDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد الصور");
        try {
            for (byte[] im : images) {
                File f = new File(dir, UUID.randomUUID() + ".evd");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(Crypto.encBytes(im));
                    o.getFD().sync();
                }
                written.add(f);
            }
        } catch (IOException | RuntimeException e) {
            for (File f : written) f.delete();
            throw e;
        }
        return written;
    }

    private long insertTx(SQLiteDatabase d, TxInput in, Long replaces) { return insertTx(d, in, replaces, false); }

    /** pending: صف الحركة وحده بحالة PENDING، والقيود عند الاعتماد (postTx). */
    private long insertTx(SQLiteDatabase d, TxInput in, Long replaces, boolean pending) {
        boolean hasFee = !FEE_NONE.equals(in.feeType);
        ContentValues v = new ContentValues();
        v.put("uuid", UUID.randomUUID().toString());
        v.put("office_id", in.office);
        v.put("type", in.type);
        v.put("person", Crypto.enc(Model.nz(in.person)));
        v.put("amount_minor", in.amount);
        v.put("currency", in.currency);
        v.put("fee_type", in.feeType);
        v.put("fee_minor", hasFee ? in.fee : 0);
        v.put("fee_currency", hasFee ? in.feeCurrency : in.currency);
        v.put("description", Crypto.enc(Model.nz(in.description)));
        v.put("notes", Crypto.enc(Model.nz(in.notes)));
        v.put("created_at", in.when);
        v.put("user_name", Crypto.enc(currentUserName));
        Model.User me = SmartApp.currentUser;
        if (me != null && usersEnabled()) v.put("user_id", me.id);   // 10.5 (§7): رقم ثابت لمن أدخلها
        v.put("service", Model.nz(in.service).trim());
        v.put("dest", Crypto.enc(Model.nz(in.dest).trim()));
        if (in.pairUuid != null) {
            v.put("pair_uuid", in.pairUuid);
            v.put("fx_rate", in.fxRate);
        }
        if (replaces != null) v.put("replaces_id", replaces);
        // 12.4: الحركة الخاصة: نوعها، والمحسوبة في الرصيد تُرحَّل كالعادية، وغير المحسوبة بلا قيود (SPECIAL)
        boolean unposted = special(in) && !in.specialBal;
        if (special(in)) {
            v.put("special", in.special.trim());
            v.put("special_bal", in.specialBal ? 1 : 0);
        }
        if (pending) v.put("status", Model.STATUS_PENDING);
        else if (unposted) v.put("status", Model.STATUS_SPECIAL);
        long id = ins(d, "tx", v);
        if (!pending && !unposted) postTx(d, id, in);
        return id;
    }

    private void postTx(SQLiteDatabase d, long id, TxInput in) {
        boolean hasFee = !FEE_NONE.equals(in.feeType);
        postEntry(d, id, "PRINCIPAL", in.office, in.currency, TYPE_DEBIT.equals(in.type) ? in.amount : 0, TYPE_CREDIT.equals(in.type) ? in.amount : 0, in.when);
        if (hasFee) postEntry(d, id, "FEE", in.office, in.feeCurrency, FEE_US.equals(in.feeType) ? in.fee : 0, FEE_THEM.equals(in.feeType) ? in.fee : 0, in.when);
    }

    // ===================================================================================
    // الاعتماد المزدوج (ت4): الموظف يقترح المبلغ الكبير، والمحاسب أو المدير يعتمد
    // ===================================================================================

    public boolean approvalRequired(TxInput in) {
        Model.User u = SmartApp.currentUser;
        if (u == null || !usersEnabled()) return false;
        BigDecimal rate = usdRates().get(in.currency);
        // 10.3: الاعتماد قرار المدير لكل مستخدم («حركاته تحتاج اعتماد») أيًا كان المبلغ
        Model.User now = user(u.id);
        if (now != null) return Auth.needsReview(now);
        return Sanity.needsApproval(false, true, in.amount, decimals(in.currency), rate, settingLong("approval_usd", 0));
    }

    public List<Model.Tx> pendingTx() {
        List<Model.Tx> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + TX_COLS + " FROM tx WHERE status='" + Model.STATUS_PENDING + "' ORDER BY created_at,id", null)) {
            while (c.moveToNext()) a.add(readTx(c));
        }
        return a;
    }

    public long pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tx WHERE status='" + Model.STATUS_PENDING + "'", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** الاعتماد: تُكتب القيود الآن بتاريخ الحركة الأصلي، وتصير نشطة. من اقترحها لا يعتمدها. */
    public void approveTx(long id) {
        require(Auth.Perm.APPROVE);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            Model.Tx t = null;
            try (Cursor c = d.rawQuery("SELECT " + TX_COLS + " FROM tx WHERE id=" + id, null)) {
                if (c.moveToFirst()) t = readTx(c);
            }
            if (t == null || !t.isPending()) throw new IllegalStateException("الحركة ليست بانتظار الاعتماد");
            Model.User u = SmartApp.currentUser;
            if (u != null && usersEnabled() && enteredBy(t, u)) throw new IllegalStateException("لا يعتمد المستخدم حركة اقترحها بنفسه");
            // 10.5 (AUDIT-018): العملية المترابطة (تبديل عملة أو تحويل بين مكتبين) تُعتمد بساقيها معًا، فلا تنفذ ساق وتبقى الأخرى معلّقة
            List<Model.Tx> legs = new ArrayList<>();
            legs.add(t);
            if (t.isLinked()) {
                try (Cursor c = d.rawQuery("SELECT " + TX_COLS + " FROM tx WHERE pair_uuid=? AND id<>" + id, new String[]{t.pairUuid})) {
                    while (c.moveToNext()) {
                        Model.Tx o = readTx(c);
                        if (!o.isPending()) throw new IllegalStateException("الساق الأخرى من هذه العملية ليست بانتظار الاعتماد؛ لا تُعتمد ساق واحدة.");
                        legs.add(o);
                    }
                }
            }
            for (Model.Tx x : legs) {
                checkLock(d, x.createdAt, "تاريخ الحركة يقع داخل فترة محاسبية مقفلة");
                // 10.5 (AUDIT-015): المكتب ما زال نشطًا والعملة ما زالت مفعّلة له وقت الاعتماد
                validate(inputOf(x));
                // 12.4: الخاصة غير المحسوبة في الرصيد تُعتمد إلى «خاصة» بلا قيود
                boolean unposted = x.special != null && !x.special.isEmpty() && !x.specialBal;
                if (!unposted) postTx(d, x.id, inputOf(x));
                d.execSQL("UPDATE tx SET status='" + (unposted ? Model.STATUS_SPECIAL : "ACTIVE") + "',approved_by=?,approved_at=? WHERE id=" + x.id,
                        new Object[]{u == null || !usersEnabled() ? null : u.id, Clock.now()});
                audit(d, "APPROVE_TRANSACTION", "tx", x.id, t.realUser() + (legs.size() > 1 ? " — عملية مترابطة (" + legs.size() + " ساق)" : ""));
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** مدخلات الحركة كما هي محفوظة (لترحيل قيودها عند الاعتماد). */
    static TxInput inputOf(Model.Tx t) {
        TxInput in = new TxInput();
        in.office = t.officeId;
        in.type = t.type;
        in.amount = t.amount;
        in.currency = t.currency;
        in.feeType = t.feeType;
        in.fee = t.fee;
        in.feeCurrency = t.feeCurrency;
        in.when = t.createdAt;
        in.person = t.person;
        in.description = t.description;
        in.notes = t.notes;
        in.service = t.service;
        in.dest = t.dest;
        in.special = Model.nz(t.special);
        in.specialBal = t.specialBal;
        return in;
    }

    /**
     * 10.5 (AUDIT-017): يجعل حركة الصندوق المرتبطة بحركة المكتب مطابقة لها بعد تصحيحها، في المعاملة نفسها:
     * المبلغ والعملة والمكتب والاتجاه (علينا = استلام يدخل الصندوق، لنا = تسليم يخرج منه) والأجرة على حساب المكتب،
     * وتنتقل إلى رقم الحركة الجديدة (newTxId) عند التعديل. ما لا يمكن تصحيحه بأمان يُرفض برسالة تشرح البديل.
     * يرجع وصف التغيير للسجل، أو null إن لم تكن الحركة مرتبطة بالصندوق.
     */
    private String relinkCash(SQLiteDatabase d, long oldTxId, long newTxId, TxInput in) {
        List<CashBox.Move> ms = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT " + CASH_COLS + " FROM cash_moves m LEFT JOIN tx t ON t.id=m.tx_id WHERE m.tx_id=" + oldTxId + " AND m.status='ACTIVE'", null)) {
            while (c.moveToNext()) ms.add(readCash(c));
        }
        if (ms.isEmpty()) return null;
        if (ms.size() > 1) throw new IllegalStateException("الحركة مرتبطة بأكثر من حركة صندوق؛ ألغِ حركات الصندوق وسجّلها من جديد.");
        if (!allowed(Auth.Perm.CASH)) throw new SecurityException("هذه الحركة مسجّلة من «صندوق المكتب» وتصحيحها يغيّر النقد أيضًا: يحتاج صلاحية «الصندوق». اطلبه من المدير أو ارفض الحركة.");
        CashBox.Move m = ms.get(0);
        CashBox.Config cfg = cashBox(m.boxId);
        if (!cfg.currencies.contains(in.currency))
            throw new IllegalStateException("العملة " + in.currency + " غير مفعّلة في «" + cfg.name + "» الذي مرّ به النقد. فعّلها في الصندوق، أو ألغِ حركة الصندوق وسجّلها من جديد بالعملة الصحيحة.");
        boolean hasFee = !FEE_NONE.equals(in.feeType);
        long fee;
        String side;
        boolean feeCash;
        if (m.feeInCash && m.fee > 0) {
            if (hasFee) throw new IllegalStateException("أجرة هذه الحركة دُفعت أو قُبضت نقدًا في الصندوق، فلا تُضاف معها أجرة على حساب المكتب. اختر «بدون أجرة» في التصحيح، أو ألغِ حركة الصندوق وسجّلها من جديد.");
            if (!in.currency.equals(m.currency)) throw new IllegalStateException("للحركة أجرة نقدية بعملة " + m.currency + "، فلا تتغير عملتها بالتصحيح. ألغِ حركة الصندوق وسجّلها من جديد.");
            fee = m.fee;
            side = m.feeSide;
            feeCash = true;
        } else if (hasFee) {
            if (!in.currency.equals(in.feeCurrency)) throw new IllegalStateException("أجرة حركة الصندوق تكون بعملة المبلغ نفسها (" + in.currency + ").");
            fee = in.fee;
            side = FEE_US.equals(in.feeType) ? CashBox.FEE_US : CashBox.FEE_THEM;
            feeCash = false;
        } else {
            fee = 0;
            side = "";
            feeCash = false;
        }
        boolean incoming = TYPE_CREDIT.equals(in.type);
        ContentValues v = new ContentValues();
        v.put("tx_id", newTxId);
        v.put("kind", incoming ? CashBox.IN : CashBox.OUT);
        v.put("dir", incoming ? 1 : -1);
        v.put("currency", in.currency);
        v.put("amount_minor", in.amount);
        v.put("fee_minor", fee);
        v.put("fee_side", side);
        v.put("fee_cash", feeCash ? 1 : 0);
        v.put("office_id", in.office);
        v.put("category", incoming ? CashBox.CAT_OFFICE_IN : CashBox.CAT_OFFICE_OUT);
        v.put("party", Crypto.enc(Model.nz(in.person)));
        v.put("created_at", in.when);
        d.update("cash_moves", v, "id=" + m.id, null);
        String before = (m.dir > 0 ? "دخل " : "خرج ") + Model.fmt(m.amount, decimals(m.currency)) + " " + m.currency + (m.officeId == null ? "" : " — مكتب " + m.officeId);
        String after = (incoming ? "دخل " : "خرج ") + Model.fmt(in.amount, decimals(in.currency)) + " " + in.currency + " — مكتب " + in.office;
        return "حركة الصندوق " + m.id + ": " + before + " ← " + after;
    }

    /**
     * 10.2: «تصحيح ثم اعتماد»: المحاسب أو المدير يصحح حركة معلّقة (المبلغ أو الاسم أو المكتب…) بدل رفضها، ثم تُعتمد في الخطوة نفسها.
     * الحركة لم تدخل الأرصدة بعد، فالتصحيح على الصف نفسه، ويُحفظ ما قبل التصحيح في سجل العمليات. يبقى اسم من اقترحها.
     */
    public void approveEdited(long id, TxInput in, List<byte[]> newImages, Set<Long> removedAttachments) throws IOException {
        require(Auth.Perm.APPROVE);
        validate(in);
        List<File> written = writeImages(newImages);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            Model.Tx t = txOn(d, id);
            if (t == null || !t.isPending()) throw new IllegalStateException("الحركة ليست بانتظار الاعتماد");
            Model.User u = SmartApp.currentUser;
            if (u != null && usersEnabled() && enteredBy(t, u)) throw new IllegalStateException("لا يعتمد المستخدم حركة اقترحها بنفسه");
            if (t.isLinked()) throw new IllegalStateException("هذه ساق من عملية مترابطة (" + (t.isTransfer() ? "تحويل بين مكتبين" : "تبديل عملة")
                    + "): لا تُصحَّح ساق وحدها. ارفض العملية كلها ثم سجّلها من جديد بالقيم الصحيحة، أو اعتمدها كما هي.");
            checkLock(d, t.createdAt, "تاريخ الحركة الأصلي يقع داخل فترة محاسبية مقفلة");
            checkLock(d, in.when, "تاريخ الحركة يقع داخل فترة محاسبية مقفلة");
            boolean hasFee = !FEE_NONE.equals(in.feeType);
            ContentValues v = new ContentValues();
            v.put("office_id", in.office);
            v.put("type", in.type);
            v.put("person", Crypto.enc(Model.nz(in.person)));
            v.put("amount_minor", in.amount);
            v.put("currency", in.currency);
            v.put("fee_type", in.feeType);
            v.put("fee_minor", hasFee ? in.fee : 0);
            v.put("fee_currency", hasFee ? in.feeCurrency : in.currency);
            v.put("description", Crypto.enc(Model.nz(in.description)));
            v.put("notes", Crypto.enc(Model.nz(in.notes)));
            v.put("created_at", in.when);
            v.put("service", Model.nz(in.service).trim());
            v.put("dest", Crypto.enc(Model.nz(in.dest).trim()));
            v.put("status", "ACTIVE");
            if (u != null && usersEnabled()) v.put("approved_by", u.id);
            v.put("approved_at", Clock.now());
            d.update("tx", v, "id=" + id, null);
            if (keyChanged(t, in)) voidChecks(d, id, "صُحّحت الحركة قبل اعتمادها (" + keyDiff(t, in) + ")");
            if (removedAttachments != null) for (Long x : removedAttachments) d.delete("attachments", "id=? AND tx_id=?", new String[]{"" + x, "" + id});
            linkImages(d, id, written, Clock.now());
            postTx(d, id, in);
            // 10.5 (AUDIT-017): حركة الصندوق المرتبطة تُصحَّح معها في المعاملة نفسها، فلا يبقى النقد على القيمة القديمة
            String cash = relinkCash(d, id, id, in);
            String before = (TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ") + Model.fmt(t.amount, decimals(t.currency)) + " " + t.currency + " — " + t.person
                    + " — مكتب " + t.officeId;
            String after = (TYPE_DEBIT.equals(in.type) ? "لنا " : "علينا ") + Model.fmt(in.amount, decimals(in.currency)) + " " + in.currency + " — " + Model.nz(in.person)
                    + " — مكتب " + in.office;
            audit(d, "APPROVE_EDITED", "tx", id, "اقترحها " + t.realUser() + "؛ قبل التصحيح: " + before + "؛ بعده: " + after + (cash == null ? "" : "؛ " + cash));
            d.setTransactionSuccessful();
            written.clear();
        } finally {
            d.endTransaction();
            for (File f : written) f.delete();
        }
    }

    /** نص التنبيه عند رفض حركة مسجّلة من الصندوق. */
    public static final String REJECT_CASH_NOTE = "هذه الحركة مسجّلة من الصندوق: برفضها تُلغى حركة النقد معها ولا تُحسب في الصندوق. "
            + "إن كان النقد قد دخل أو خرج فعلًا فاختر «تصحيح واعتماد» بدل الرفض، أو سجّله من جديد صحيحًا.";

    public void rejectTx(long id, String reason) {
        require(Auth.Perm.APPROVE);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            Model.Tx t = txOn(d, id);
            if (t == null || !t.isPending()) throw new IllegalStateException("الحركة ليست بانتظار الاعتماد");
            // 10.5: العملية المترابطة تُرفض بساقيها معًا
            if (t.isLinked()) d.execSQL("UPDATE tx SET status='" + Model.STATUS_REJECTED + "' WHERE status='" + Model.STATUS_PENDING + "' AND pair_uuid=?", new Object[]{t.pairUuid});
            d.execSQL("UPDATE tx SET status='" + Model.STATUS_REJECTED + "' WHERE id=" + id);
            // 10.5: حركة الصندوق المسجّلة معها تُلغى صراحة بسبب ظاهر (كانت لا تُحسب أصلًا ما دامت معلّقة)، فلا تبقى معلّقة بلا نهاية
            ContentValues cc = new ContentValues();
            cc.put("status", CashBox.CANCELLED);
            cc.put("cancel_reason", Crypto.enc("رُفضت حركة المكتب: " + Model.nz(reason)));
            cc.put("cancelled_at", Clock.now());
            int cashN = d.update("cash_moves", cc, "status='ACTIVE' AND tx_id IN (SELECT id FROM tx WHERE status='" + Model.STATUS_REJECTED + "' AND (id=" + id
                    + (t.isLinked() ? " OR pair_uuid='" + t.pairUuid.replace("'", "") + "'" : "") + "))", null);
            audit(d, "REJECT_TRANSACTION", "tx", id, (cashN > 0 ? "حركة الصندوق أُلغيت معها؛ " : "") + (reason == null ? "" : reason) + (t.isLinked() ? " — عملية مترابطة" : ""));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    private void linkImages(SQLiteDatabase d, long txId, List<File> files, long now) {
        for (File f : files) {
            ContentValues a = new ContentValues();
            a.put("tx_id", txId);
            a.put("path", f.getAbsolutePath());
            a.put("mime", "image/jpeg");
            a.put("created_at", now);
            ins(d, "attachments", a);
        }
    }

    /** يسجّل حركة كاملة (حركة + قيد الأصل + قيد الأجرة + الصور) في معاملة واحدة. */
    public long addTx(TxInput in, List<byte[]> images) throws IOException { return addTx(in, images, null); }

    /**
     * 12.4: fromHold = أمانة أو مبلغ مجمّد يُحوَّل إلى هذه الحركة العادية: تُسجَّل الحركة وتُفكّ الأمانة مربوطةً بها في المعاملة نفسها،
     * فلا تبقى الأمانة قائمة مع حركتها ولا تُفكّ بلا حركة.
     */
    public long addTx(TxInput in, List<byte[]> images, String fromHold) throws IOException {
        require(Auth.Perm.TX_ADD);
        if (special(in)) require(Auth.Perm.SPECIAL);   // 12.4
        if (fromHold != null) {
            require(Auth.Perm.HOLDS);
            if (special(in)) throw new IllegalArgumentException("الأمانة تُحوَّل إلى حركة عادية (لا خاصة)");
        }
        if (in.when > 0 && in.when < Model.dayStart(Clock.now())) require(Auth.Perm.BACKDATE);   // 12.0: تاريخ سابق
        validate(in);
        Entitlements ent = License.current;
        List<File> written = writeImages(images);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            checkLock(d, in.when, "تاريخ الحركة يقع داخل فترة محاسبية مقفلة");
            QuotaState q = unlimited(ent) ? null : quota(d, in.office);
            if (q != null) {
                Model.Office off = office(in.office);
                String no = q.txRefusal(ent, in.office, off == null ? "" : Model.nz(off.name));
                if (no != null) throw new Entitlements.LimitReached(no);
            }
            boolean pending = approvalRequired(in);
            long id = insertTx(d, in, null, pending);
            if (q != null) {
                q.tx.put(in.office, q.txOf(in.office) + 1);
                saveQuota(d, q);
            }
            linkImages(d, id, written, Clock.now());
            if (fromHold != null) releaseHoldTo(d, fromHold, id, in);
            audit(d, pending ? "PROPOSE_TRANSACTION" : "CREATE_TRANSACTION", "tx", id, (written.isEmpty() ? "" : ("images=" + written.size()))
                    + (special(in) ? " — خاصة: " + in.special.trim() + (in.specialBal ? " (تُحسب في الرصيد)" : " (لا تُحسب في الرصيد)") : ""));
            d.setTransactionSuccessful();
            written.clear();
            return id;
        } finally {
            d.endTransaction();
            for (File f : written) f.delete();
        }
    }

    /**
     * تعديل حركة: قيد عكسي يلغي الحركة القديمة بكامل أثرها، ثم حركة مصححة مرتبطة بها (replaces_id)،
     * وتنتقل الصور التي لم تُحذف إلى الحركة الجديدة. كل ذلك في معاملة واحدة.
     */
    public long editTx(long oldId, TxInput in, List<byte[]> newImages, Set<Long> removedAttachments, String reason) throws IOException {
        Model.Tx before0 = tx(oldId);
        // 12.4: الحركة الخاصة تُعدَّل وتُحوَّل بصلاحية «الحركات الخاصة» (والخاصة غير المحسوبة بلا قيد يُعكس)
        if (before0 != null && (before0.isSpecial() || special(in))) return replaceSpecial(oldId, in, newImages, removedAttachments, reason);
        require(Auth.Perm.TX_EDIT);
        Model.Tx before = before0;
        if (before != null && before.isLinked()) throw new IllegalStateException("هذه ساق من عملية مترابطة (" + (before.isTransfer() ? "تحويل بين مكتبين" : "تبديل عملة")
                + "): ألغِ العملية كلها ثم سجّلها من جديد بالقيم الصحيحة.");
        validate(in);
        List<File> written = writeImages(newImages);
        SQLiteDatabase d = getWritableDatabase();
        long now = Clock.now();
        d.beginTransaction();
        try {
            reverseTx(d, oldId, now);
            checkLock(d, in.when, "التاريخ الجديد يقع داخل فترة محاسبية مقفلة");
            long id = insertTx(d, in, oldId);
            StringBuilder keep = new StringBuilder("UPDATE attachments SET tx_id=" + id + " WHERE tx_id=" + oldId);
            if (removedAttachments != null && !removedAttachments.isEmpty()) {
                keep.append(" AND id NOT IN (");
                boolean first = true;
                for (Long a : removedAttachments) {
                    if (!first) keep.append(',');
                    keep.append(a.longValue());
                    first = false;
                }
                keep.append(')');
            }
            d.execSQL(keep.toString());
            linkImages(d, id, written, now);
            // 10.5 (AUDIT-017): الحركة المسجّلة من الصندوق تُصحَّح مع حركة صندوقها في المعاملة نفسها (بدل منع التصحيح)
            String cash = relinkCash(d, oldId, id, in);
            // 10.5 (§7): تعديل المبلغ أو العملة أو الاتجاه أو المكتب يُبطل التحقق السابق (يبقى في تاريخ الحركة القديمة)؛
            // وتعديل غيرها (الاسم، البيان…) ينقل التحقق إلى الحركة المصححة
            if (before != null && keyChanged(before, in)) voidChecks(d, oldId, "عُدّلت الحركة: " + keyDiff(before, in));
            else carryChecks(d, oldId, id);
            audit(d, "EDIT_TRANSACTION", "tx", oldId, "new=" + id + (reason == null || reason.isEmpty() ? "" : " — " + reason) + (cash == null ? "" : " — " + cash));
            d.setTransactionSuccessful();
            written.clear();
            return id;
        } finally {
            d.endTransaction();
            for (File f : written) f.delete();
        }
    }

    /**
     * 8.0: تبديل عملة مع مكتب: ساقان في معاملة واحدة، «لنا» بعملة و«علينا» بأخرى، مربوطتان برقم واحد وبسعر التبديل،
     * فيظهر في التقارير ربح فرق العملة وصافي المركز. للمحاسب والمدير (يغيّر رصيد عملتين معًا).
     */
    /** 10.5: ساقا التبديل كما تُسجَّلان («لنا» بعملة البيع و«علينا» بعملة الشراء) — للمعاينة والتسجيل بالقواعد نفسها. */
    public TxInput[] fxLegs(long office, String usCur, long usAmount, String themCur, long themAmount, String rate, String description, long when) {
        if (usCur.equals(themCur)) throw new IllegalArgumentException("التبديل بين عملتين مختلفتين");
        TxInput a = new TxInput(), b = new TxInput();
        for (TxInput x : new TxInput[]{a, b}) {
            x.office = office;
            x.description = description == null ? "" : description;
            x.when = when;
            x.service = "تبديل عملة";
            x.fxRate = rate;
        }
        a.type = TYPE_DEBIT;
        a.currency = usCur;
        a.amount = usAmount;
        b.type = TYPE_CREDIT;
        b.currency = themCur;
        b.amount = themAmount;
        return new TxInput[]{a, b};
    }

    /**
     * 10.8: قص العملات مقاصةً: العملة المباعة تُخصم من رصيدنا لدى المكتب (ساق «علينا»)، والمشتراة تُضاف إليه (ساق «لنا»).
     * يُسمح بعبور الصفر؛ لا حركة صندوق. rate = كم من عملة الشراء لكل 1 من عملة البيع (للعرض والتقارير).
     */
    public TxInput[] fxNetLegs(long office, String sellCur, long sellAmount, String buyCur, long buyAmount, String rate, String description, long when) {
        return fxLegs(office, buyCur, buyAmount, sellCur, sellAmount, rate, description, when);
    }

    public String[] addFxNet(long office, String sellCur, long sellAmount, String buyCur, long buyAmount, String rate, String description, long when) {
        return addFx(office, buyCur, buyAmount, sellCur, sellAmount, rate, description, when);
    }

    /**
     * 10.18: تعديل قص عملات: تُعكس الساقان القديمتان معًا وتُسجَّل ساقان مصحّحتان (كل ساق تحل محل نظيرتها: replaces_id) في معاملة واحدة،
     * فلا يبقى نصف عملية في الأرصدة. المكتب لا يتغير. التحقق السابق يُبطَل (تغيّرت القيم). العملية المعلّقة تُرفض وتُسجَّل من جديد.
     * legId: أي ساق من الساقين. يرجع رقمي الساقين الجديدتين.
     */
    public String[] editFxNet(long legId, String sellCur, long sellAmount, String buyCur, long buyAmount, String rate, String description, long when, String reason) {
        require(Auth.Perm.TX_ADD);
        require(Auth.Perm.TX_EDIT);
        SQLiteDatabase d = getWritableDatabase();
        long now = Clock.now();
        d.beginTransaction();
        try {
            Model.Tx t = txOn(d, legId);
            if (t == null || !t.isFx()) throw new IllegalStateException("هذه ليست حركة قص عملات");
            if (t.isPending()) throw new IllegalStateException("العملية بانتظار الاعتماد: ارفضها ثم سجّلها من جديد بالقيم الصحيحة.");
            if (!t.isActive()) throw new IllegalStateException("هذه العملية ملغاة أو معدّلة من قبل");
            List<Model.Tx> old = new ArrayList<>();
            old.add(t);
            try (Cursor c = d.rawQuery("SELECT " + TX_COLS + " FROM tx WHERE pair_uuid=? AND id<>" + t.id, new String[]{t.pairUuid})) {
                while (c.moveToNext()) old.add(readTxRaw(c));
            }
            if (old.size() != 2) throw new IllegalStateException("ساقا العملية غير مكتملتين؛ ألغِها وسجّلها من جديد.");
            for (Model.Tx x : old) if (!x.isActive()) throw new IllegalStateException("إحدى ساقي العملية ليست نشطة؛ ألغِها وسجّلها من جديد.");
            TxInput[] legs = fxNetLegs(t.officeId, sellCur, sellAmount, buyCur, buyAmount, rate, description, when);
            String pair = UUID.randomUUID().toString();
            for (TxInput x : legs) {
                x.pairUuid = pair;
                validate(x);
            }
            checkLock(d, when, "التاريخ الجديد يقع داخل فترة محاسبية مقفلة");
            String[] ids = new String[2];
            for (Model.Tx x : old) reverseTx(d, x.id, now);
            for (int i = 0; i < 2; i++) {
                // كل ساق جديدة تحل محل الساق القديمة بالاتجاه نفسه («لنا» محل «لنا»)
                Model.Tx match = old.get(0).type.equals(legs[i].type) ? old.get(0) : old.get(1);
                long id = insertTx(d, legs[i], match.id);
                d.execSQL("UPDATE attachments SET tx_id=" + id + " WHERE tx_id=" + match.id);
                voidChecks(d, match.id, "عُدّل قص العملات");
                ids[i] = "" + id;
            }
            audit(d, "EDIT_FX", "tx", t.id, "new=" + ids[0] + "," + ids[1] + " rate=" + rate + (reason == null || reason.trim().isEmpty() ? "" : " — " + reason.trim()));
            d.setTransactionSuccessful();
            return ids;
        } finally {
            d.endTransaction();
        }
    }

    /** 10.18: ما يلزم لفتح قص عملات للتعديل: {الساق المباعة («علينا»)، الساق المشتراة («لنا»)}، أو null إن لم تكن عملية قص نشطة بساقين. */
    public Model.Tx[] fxLegsOf(long legId) {
        Model.Tx t = tx(legId);
        if (t == null || !t.isFx()) return null;
        Model.Tx sell = null, buy = null;
        for (Model.Tx x : legs(t)) {
            if (TYPE_CREDIT.equals(x.type)) sell = x;
            else buy = x;
        }
        return sell == null || buy == null ? null : new Model.Tx[]{sell, buy};
    }

    /**
     * 10.18: معاينة تعديل القص: لكل عملة {الرصيد بعد عكس العملية القديمة، الرصيد بعد العملية المصحّحة}.
     * (المعاينة العادية تحسب العملية القديمة ضمن «قبل»، فتُضاعِف أثرها.)
     */
    public Map<String, long[]> fxEditPreview(Model.Tx[] old, String sellCur, long sellAmount, String buyCur, long buyAmount) {
        Map<String, Long> bal = balances(old[0].officeId);
        Map<String, long[]> out = new LinkedHashMap<>();
        for (String c : new String[]{old[0].currency, old[1].currency, sellCur, buyCur}) {
            if (c == null || c.isEmpty() || out.containsKey(c)) continue;
            long base = bal.getOrDefault(c, 0L);
            if (c.equals(old[0].currency)) base += old[0].amount;   // الساق المباعة كانت خصمًا
            if (c.equals(old[1].currency)) base -= old[1].amount;   // والمشتراة كانت إضافة
            long after = base;
            if (c.equals(sellCur)) after -= sellAmount;
            if (c.equals(buyCur)) after += buyAmount;
            out.put(c, new long[]{base, after});
        }
        return out;
    }

    public String[] addFx(long office, String usCur, long usAmount, String themCur, long themAmount, String rate, String description, long when) {
        require(Auth.Perm.TX_ADD);
        require(Auth.Perm.FX);   // 12.0 (كانت تحتاج «التعديل»؛ صارت صلاحية «قص العملات» نفسها)
        TxInput[] legs = fxLegs(office, usCur, usAmount, themCur, themAmount, rate, description, when);
        TxInput a = legs[0], b = legs[1];
        String pair = UUID.randomUUID().toString();
        a.pairUuid = pair;
        b.pairUuid = pair;
        validate(a);
        validate(b);
        Entitlements ent = License.current;
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            checkLock(d, when, "تاريخ العملية يقع داخل فترة محاسبية مقفلة");
            QuotaState q = unlimited(ent) ? null : quota(d, office);
            if (q != null) {
                Model.Office off = office(office);
                String no = q.txRefusal(ent, office, off == null ? "" : Model.nz(off.name));
                if (no == null && q.txOf(office) + 2 > ent.maxTxPerOffice()) no = Entitlements.txLimitMessage(off == null ? "" : Model.nz(off.name), ent.maxTxPerOffice());
                if (no != null) throw new Entitlements.LimitReached(no);
            }
            // 10.5 (AUDIT-018): التبديل يخضع للاعتماد كأي حركة: الساقان معلّقتان معًا ولا أثر لهما في الأرصدة حتى تُعتمدا
            boolean pending = approvalRequired(a);
            long ia = insertTx(d, a, null, pending);
            long ib = insertTx(d, b, null, pending);
            if (q != null) {
                q.tx.put(office, q.txOf(office) + 2);
                saveQuota(d, q);
            }
            audit(d, pending ? "PROPOSE_FX" : "CREATE_FX", "tx", ia, "pair=" + ib + " rate=" + rate);
            d.setTransactionSuccessful();
            return new String[]{"" + ia, "" + ib};
        } finally {
            d.endTransaction();
        }
    }

    // ===================================================================================
    // 10.5 (§9)، صُحّح في 10.7: الحركة بين مكتبين — طالب الحركة «لنا» والمنفّذ «علينا»، ساقان برقم عملية واحد
    // ===================================================================================

    public static final class TransferInput {
        /**
         * 10.7: المكتب طالب الحركة (from) يطلب منا تسليم مبلغ عن طريق مكتب آخر، فيصير مدينًا لنا: «لنا» عليه.
         * المكتب المنفّذ (to) يسلّم المبلغ نيابة عنا، فنصير مدينين له: «علينا» له. (في 10.5–10.6 كان الاتجاه معكوسًا.)
         */
        public long from, to;
        public String currency, person = "", description = "", notes = "";
        public long amount, when;
        /** أجرة كل طرف صريحة (بدون أجرة افتراضيًا)، بعملة التحويل. */
        public String fromFeeType = FEE_NONE, toFeeType = FEE_NONE;
        public long fromFee, toFee;
    }

    /** ساقا التحويل كما تُسجَّلان (للمعاينة والتسجيل بالقواعد نفسها). */
    public TxInput[] transferLegs(TransferInput in) {
        if (in.from == in.to) throw new IllegalArgumentException("المكتب طالب الحركة والمكتب المنفّذ يجب أن يكونا مختلفين");
        TxInput a = new TxInput(), b = new TxInput();
        Model.Office fo = office(in.from), to = office(in.to);
        for (TxInput x : new TxInput[]{a, b}) {
            x.currency = in.currency;
            x.amount = in.amount;
            x.person = Model.nz(in.person);
            x.notes = Model.nz(in.notes);
            x.when = in.when;
            x.service = "تحويل بين مكتبين";
            x.fxRate = Model.TRANSFER_MARK;
        }
        // طالب الحركة: مدين لنا بالمبلغ (وأجرتنا عليه إن وُجدت «الأجرة لنا»)
        a.office = in.from;
        a.type = TYPE_DEBIT;
        a.dest = to == null ? "" : Model.nz(to.name);
        a.description = Model.nz(in.description).isEmpty() ? "حركة نفّذها «" + (to == null ? "" : Model.nz(to.name)) + "»" : in.description;
        a.feeType = in.fromFeeType == null ? FEE_NONE : in.fromFeeType;
        a.fee = FEE_NONE.equals(a.feeType) ? 0 : in.fromFee;
        a.feeCurrency = FEE_NONE.equals(a.feeType) ? null : in.currency;
        // المنفّذ: دائن لنا بالمبلغ الذي سلّمه عنا (وأجرته علينا إن وُجدت «الأجرة علينا»)
        b.office = in.to;
        b.type = TYPE_CREDIT;
        b.dest = fo == null ? "" : Model.nz(fo.name);
        b.description = Model.nz(in.description).isEmpty() ? "تنفيذ حركة لطلب «" + (fo == null ? "" : Model.nz(fo.name)) + "»" : in.description;
        b.feeType = in.toFeeType == null ? FEE_NONE : in.toFeeType;
        b.fee = FEE_NONE.equals(b.feeType) ? 0 : in.toFee;
        b.feeCurrency = FEE_NONE.equals(b.feeType) ? null : in.currency;
        return new TxInput[]{a, b};
    }

    /**
     * يسجّل التحويل: الساقان في معاملة واحدة برقم عملية واحد (لا ساق بلا أخرى)، وتخضعان للاعتماد معًا، ولا تُعدَّل ساق وحدها.
     * لا حركة صندوق: التحويل بين مكتبين لا يحرّك النقد (حركة النقد تُسجَّل صراحة من الصندوق إن حدثت). يرجع [رقم ساق المرسل، رقم ساق المنفّذ].
     */
    public long[] addTransfer(TransferInput in) {
        require(Auth.Perm.TX_ADD);
        require(Auth.Perm.XFER);   // 12.0
        TxInput[] legs = transferLegs(in);
        String pair = UUID.randomUUID().toString();
        for (TxInput x : legs) {
            x.pairUuid = pair;
            validate(x);
        }
        Entitlements ent = License.current;
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            checkLock(d, in.when, "تاريخ العملية يقع داخل فترة محاسبية مقفلة");
            QuotaState q = unlimited(ent) ? null : quota(d, in.from);
            if (q != null) {
                q.raiseTo(0, liveTx(d, in.to));
                for (TxInput x : legs) {
                    Model.Office off = office(x.office);
                    String no = q.txRefusal(ent, x.office, off == null ? "" : Model.nz(off.name));
                    if (no != null) throw new Entitlements.LimitReached(no);
                }
            }
            boolean pending = approvalRequired(legs[0]) || approvalRequired(legs[1]);
            long ia = insertTx(d, legs[0], null, pending);
            long ib = insertTx(d, legs[1], null, pending);
            if (q != null) {
                q.tx.put(in.from, q.txOf(in.from) + 1);
                q.tx.put(in.to, q.txOf(in.to) + 1);
                saveQuota(d, q);
            }
            audit(d, pending ? "PROPOSE_TRANSFER" : "CREATE_TRANSFER", "tx", ia, "pair=" + ib + " " + Model.plain(in.amount, decimals(in.currency)) + " " + in.currency);
            d.setTransactionSuccessful();
            return new long[]{ia, ib};
        } finally {
            d.endTransaction();
        }
    }

    /** 10.5: ساقات عملية مترابطة (تبديل أو تحويل) بالترتيب، أو الحركة وحدها. */
    public List<Model.Tx> legs(Model.Tx t) {
        List<Model.Tx> out = new ArrayList<>();
        if (t == null) return out;
        if (!t.isLinked()) {
            out.add(t);
            return out;
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + TX_COLS + " FROM tx WHERE pair_uuid=? ORDER BY id", new String[]{t.pairUuid})) {
            while (c.moveToNext()) out.add(readTx(c));
        }
        return out;
    }

    // ===================================================================================
    // 10.5 (§7): المحاسبون — مكاتب كل محاسب، و«تم التحقق»، ومن أدخل ومن اعتمد ومن تحقق، وآخر رسالة واتساب نُقلت
    // ===================================================================================

    /** هل أدخل هذا المستخدم الحركة؟ بالرقم الثابت؛ وللحركات القديمة (بلا رقم) بالاسم. */
    public static boolean enteredBy(Model.Tx t, Model.User u) {
        if (t == null || u == null) return false;
        if (t.userId != null) return t.userId == u.id;
        return t.realUser().equals(Model.nz(u.name));
    }

    /** المبلغ أو العملة أو الاتجاه أو المكتب تغيّر (يُبطل التحقق). */
    static boolean keyChanged(Model.Tx t, TxInput in) {
        return t.amount != in.amount || !Model.nz(t.currency).equals(in.currency) || !Model.nz(t.type).equals(in.type) || t.officeId != in.office;
    }

    String keyDiff(Model.Tx t, TxInput in) {
        List<String> l = new ArrayList<>();
        if (t.amount != in.amount || !Model.nz(t.currency).equals(in.currency))
            l.add("المبلغ " + Model.fmt(t.amount, decimals(t.currency)) + " " + t.currency + " ← " + Model.fmt(in.amount, decimals(in.currency)) + " " + in.currency);
        if (!Model.nz(t.type).equals(in.type)) l.add("الاتجاه " + (TYPE_DEBIT.equals(t.type) ? "لنا" : "علينا") + " ← " + (TYPE_DEBIT.equals(in.type) ? "لنا" : "علينا"));
        if (t.officeId != in.office) {
            Model.Office a = office(t.officeId), b = office(in.office);
            l.add("المكتب «" + (a == null ? "" : a.name) + "» ← «" + (b == null ? "" : b.name) + "»");
        }
        return Model.join(l, "، ");
    }

    /** مفتاح التحقق: الحركة + المستخدم + بصمة قيمها الأساسية (ثابت على كل جهاز: لا يتكرر ولا يضيع بالمزامنة). */
    static String checkKey(Model.Tx t, long userId) {
        String sig = t.officeId + "|" + t.type + "|" + t.amount + "|" + t.currency;
        return "c:" + t.id + ":" + userId + ":" + Integer.toHexString(sig.hashCode());
    }

    public static final class TxCheck {
        public String uuid = "", userName = "", status = "VALID", voidReason = "";
        public long txId, userId, at, voidAt;

        public boolean valid() { return "VALID".equals(status); }
    }

    private void voidChecks(SQLiteDatabase d, long txId, String reason) {
        ContentValues v = new ContentValues();
        v.put("status", "VOID");
        v.put("void_reason", Crypto.enc(reason));
        v.put("void_at", Clock.now());
        int n = d.update("tx_checks", v, "tx_id=" + txId + " AND status='VALID'", null);
        if (n > 0) audit(d, "VERIFY_VOID", "tx", txId, n + " — " + reason);
    }

    /** ينقل التحقق الساري إلى الحركة المصححة (لم تتغير قيمها الأساسية)، ويبقى الأصل في تاريخ الحركة القديمة. */
    private void carryChecks(SQLiteDatabase d, long oldId, long newId) {
        Model.Tx nt = txOn(d, newId);
        if (nt == null) return;
        List<TxCheck> l = checksOn(d, oldId);
        for (TxCheck c : l) {
            if (!c.valid()) continue;
            ContentValues v = new ContentValues();
            v.put("uuid", checkKey(nt, c.userId));
            v.put("tx_id", newId);
            v.put("user_id", c.userId);
            v.put("user_name", Crypto.enc(c.userName));
            v.put("created_at", c.at);
            v.put("status", "VALID");
            if (d.update("tx_checks", v, "uuid=?", new String[]{v.getAsString("uuid")}) == 0) d.insertOrThrow("tx_checks", null, v);
        }
    }

    private List<TxCheck> checksOn(SQLiteDatabase d, long txId) {
        List<TxCheck> out = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT uuid,tx_id,user_id,user_name,created_at,status,void_reason,void_at FROM tx_checks WHERE tx_id=" + txId + " ORDER BY created_at", null)) {
            while (c.moveToNext()) {
                TxCheck x = new TxCheck();
                x.uuid = c.getString(0);
                x.txId = c.getLong(1);
                x.userId = c.getLong(2);
                x.userName = shown(Model.nz(Crypto.dec(c.getString(3))));
                x.at = c.getLong(4);
                x.status = c.getString(5);
                x.voidReason = c.isNull(6) ? "" : Model.nz(Crypto.dec(c.getString(6)));
                x.voidAt = c.isNull(7) ? 0 : c.getLong(7);
                out.add(x);
            }
        }
        return out;
    }

    /** كل تحقق على الحركة (الساري والمُبطل)، بالترتيب. */
    public List<TxCheck> checks(long txId) { return checksOn(getReadableDatabase(), txId); }

    /**
     * «تم التحقق»: لا قيد ولا أثر في الأرصدة. للمستخدم بصلاحية التحقق، على حركة نافذة أدخلها غيره، في مكاتبه (إن خُصصت له مكاتب).
     * العملية المترابطة (تبديل أو تحويل) يُتحقق منها كلها معًا. التحقق مرتين من المستخدم نفسه لا يتكرر.
     */
    public void verifyTx(long txId) {
        require(Auth.Perm.VERIFY);
        Model.User me = actor();
        if (me == null) throw new IllegalStateException("التحقق يحتاج نظام المستخدمين: كل تحقق يُسجَّل باسم من قام به.");
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            Model.Tx t = txOn(d, txId);
            if (t == null) throw new IllegalStateException("الحركة غير موجودة");
            if (t.isPending()) throw new IllegalStateException("الحركة بانتظار الاعتماد: يُتحقق منها بعد اعتمادها");
            if (!t.isActive()) throw new IllegalStateException("الحركة ملغاة أو معدّلة: تحقق من الحركة الحالية");
            if (enteredBy(t, me)) throw new IllegalStateException("لا يُحتسب تحققك من حركة أدخلتها أنت؛ يتحقق منها محاسب آخر.");
            // 10.8: مع فرز المكاتب يتحقق من المكتب من كُلّف بالتحقق منه وحده (لا من يسجّله)
            Set<Long> scope = checkScope(d, me);
            if (scope != null && !scope.contains(t.officeId)) throw new SecurityException(checkRefusal(d, me, t.officeId));
            List<Model.Tx> legs = new ArrayList<>();
            legs.add(t);
            if (t.isLinked()) try (Cursor c = d.rawQuery("SELECT " + TX_COLS + " FROM tx WHERE pair_uuid=? AND id<>" + t.id, new String[]{t.pairUuid})) {
                while (c.moveToNext()) legs.add(readTx(c));
            }
            long now = Clock.now();
            int added = 0;
            for (Model.Tx x : legs) {
                String key = checkKey(x, me.id);
                try (Cursor c = d.rawQuery("SELECT status FROM tx_checks WHERE uuid=?", new String[]{key})) {
                    if (c.moveToFirst() && "VALID".equals(c.getString(0))) continue;
                }
                ContentValues v = new ContentValues();
                v.put("tx_id", x.id);
                v.put("user_id", me.id);
                v.put("user_name", Crypto.enc(me.name));
                v.put("created_at", now);
                v.put("status", "VALID");
                v.putNull("void_reason");
                v.putNull("void_at");
                if (d.update("tx_checks", v, "uuid=?", new String[]{key}) == 0) {
                    v.put("uuid", key);
                    d.insertOrThrow("tx_checks", null, v);
                }
                added++;
            }
            if (added > 0) audit(d, "VERIFY_TX", "tx", txId, me.name + (legs.size() > 1 ? " — عملية مترابطة (" + legs.size() + " ساق)" : ""));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /**
     * 10.16: التحقق من عدة حركات دفعة واحدة («تحديد الكل»). كل حركة تمر بالقواعد نفسها في verifyTx (الصلاحية، الفرز، وألا تكون
     * حركتي)؛ ما يُرفض منها يُتجاوز ويُعاد سببه، والباقي يُسجَّل. يرجع {عدد ما سُجّل، أسباب الرفض}.
     */
    public Object[] verifyTxs(java.util.Collection<Long> ids) {
        require(Auth.Perm.VERIFY);
        int ok = 0;
        List<String> refused = new ArrayList<>();
        // كل حركة بمعاملتها (كما في verifyTx): رفض واحدة لا يُلغي ما سُجّل قبلها ولا ما بعدها
        for (Long id : ids) {
            try {
                verifyTx(id);
                ok++;
            } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
                refused.add("#" + id + ": " + e.getMessage());
            }
        }
        return new Object[]{ok, refused};
    }

    /** هل تحقق منها مستخدم آخر غير من أدخلها؟ (تحقق صاحبها لا يُحتسب أصلًا) */
    public boolean verified(Model.Tx t) {
        for (TxCheck c : checks(t.id)) if (c.valid() && (t.userId == null || c.userId != t.userId)) return true;
        return false;
    }

    /**
     * «بانتظار تحققي»: حركات نافذة في المكاتب التي أتحقق منها (10.8: بالفرز؛ وقبله مكاتبي أو كل المكاتب) خلال آخر days يومًا، أدخلها غيري،
     * ولم أتحقق منها بعد. الأحدث أولًا.
     */
    public List<Model.Tx> awaitingMyCheck(int days, int limit) {
        Model.User me = actor();
        List<Model.Tx> out = new ArrayList<>();
        if (me == null || !Auth.can(me, Auth.Perm.VERIFY)) return out;
        SQLiteDatabase d = getReadableDatabase();
        Set<Long> mine = checkScope(d, me);
        if (mine != null && mine.isEmpty()) return out;
        StringBuilder w = new StringBuilder(" WHERE status='ACTIVE' AND created_at>=" + (Clock.now() - days * 86_400_000L)
                + " AND (user_id IS NULL OR user_id<>" + me.id + ")"
                + " AND NOT EXISTS(SELECT 1 FROM tx_checks k WHERE k.tx_id=tx.id AND k.user_id=" + me.id + " AND k.status='VALID')");
        if (mine != null) {
            w.append(" AND office_id IN (");
            boolean first = true;
            for (Long o : mine) {
                if (!first) w.append(',');
                w.append(o.longValue());
                first = false;
            }
            w.append(')');
        }
        try (Cursor c = d.rawQuery("SELECT " + TX_COLS + " FROM tx" + w + " ORDER BY created_at DESC,id DESC LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) {
                Model.Tx t = readTx(c);
                if (!enteredBy(t, me)) out.add(t);
            }
        }
        return out;
    }

    // ===================================================================================
    // 10.8: فرز المكاتب بين المحاسبين — لكل مكتب من «يسجّل» حركاته ومطابقاته، ومن «يتحقق» منها
    // ===================================================================================

    /** دور المحاسب في المكتب: يسجّل حركاته ومطابقاته (المسؤول عنه)، أو يتحقق منها. محاسب واحد لا يجمع الدورين في مكتب واحد. */
    public static final String ROLE_OWN = "OWN", ROLE_CHECK = "CHECK";

    /** كل مكاتب المستخدم بأي دور (فارغة = لم تُخصص له مكاتب). */
    public Set<Long> userOffices(long userId) { return userOffices(getReadableDatabase(), userId); }

    private static Set<Long> userOffices(SQLiteDatabase d, long userId) {
        Set<Long> out = new java.util.LinkedHashSet<>();
        try (Cursor c = d.rawQuery("SELECT office_id FROM user_offices WHERE user_id=" + userId + " ORDER BY office_id", null)) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    /** دور المستخدم في كل مكتب من مكاتبه: ROLE_OWN أو ROLE_CHECK. */
    public Map<Long, String> officeRoles(long userId) { return officeRoles(getReadableDatabase(), userId); }

    private static Map<Long, String> officeRoles(SQLiteDatabase d, long userId) {
        Map<Long, String> out = new LinkedHashMap<>();
        try (Cursor c = d.rawQuery("SELECT office_id,COALESCE(role,'OWN') FROM user_offices WHERE user_id=" + userId + " ORDER BY office_id", null)) {
            while (c.moveToNext()) out.put(c.getLong(0), ROLE_CHECK.equals(c.getString(1)) ? ROLE_CHECK : ROLE_OWN);
        }
        return out;
    }

    private List<Model.Office> myOfficesWith(String role) {
        List<Model.Office> out = new ArrayList<>();
        Model.User me = actor();
        if (me == null) return out;
        for (Map.Entry<Long, String> e : officeRoles(me.id).entrySet()) {
            if (!role.equals(e.getValue())) continue;
            Model.Office o = office(e.getKey());
            if (o != null) out.add(o);
        }
        return out;
    }

    /** «مكاتبي»: المكاتب التي يسجّل المستخدم الحالي حركاتها ومطابقاتها. */
    public List<Model.Office> myOffices() { return myOfficesWith(ROLE_OWN); }

    /** المكاتب التي يتحقق المستخدم الحالي من حركاتها ومطابقاتها. */
    public List<Model.Office> myCheckOffices() { return myOfficesWith(ROLE_CHECK); }

    /** نظام الفرز مفعّل: لمكتب واحد على الأقل محاسب «يتحقق». قبل ذلك يبقى سلوك 10.5 (يتحقق من مكاتبه أو من الكل). */
    private static boolean teamMode(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery("SELECT 1 FROM user_offices uo JOIN users u ON u.id=uo.user_id WHERE uo.role='CHECK' AND u.active=1 LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    public boolean teamMode() { return teamMode(getReadableDatabase()); }

    /** المكاتب التي يتحقق منها المستخدم: null = كل المكاتب (المدير، أو بلا فرز ولا مكاتب). */
    private static Set<Long> checkScope(SQLiteDatabase d, Model.User me) {
        if (Auth.isAdmin(me)) return null;
        if (teamMode(d)) {
            Set<Long> out = new java.util.LinkedHashSet<>();
            for (Map.Entry<Long, String> e : officeRoles(d, me.id).entrySet()) if (ROLE_CHECK.equals(e.getValue())) out.add(e.getKey());
            return out;
        }
        Set<Long> mine = userOffices(d, me.id);
        return mine.isEmpty() ? null : mine;
    }

    /** أسماء من يتحقق من مكتب (للرسائل). */
    private static List<String> checkerNames(SQLiteDatabase d, long office) {
        List<String> out = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT u.name FROM user_offices uo JOIN users u ON u.id=uo.user_id WHERE uo.office_id=" + office
                + " AND uo.role='CHECK' AND u.active=1 ORDER BY u.name", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    private static String checkRefusal(SQLiteDatabase d, Model.User me, long office) {
        List<String> who = checkerNames(d, office);
        String rest = who.isEmpty() ? "لم يُحدَّد له محاسب يتحقق منه بعد (المستخدمون ← فرز المكاتب)." : "يتحقق منه: " + Model.join(who, "، ") + ".";
        if (ROLE_OWN.equals(officeRoles(d, me.id).get(office))) return "أنت من يسجّل حركات هذا المكتب، والتحقق يكون من محاسب آخر. " + rest;
        return "هذا المكتب ليس من المكاتب التي تتحقق منها. " + rest;
    }

    /**
     * 10.7: لكل مكتب أسماء المستخدمين النشطين الذين يسجّلون له (عدا exceptUser). مكتب عند مسجّلَين مسموح، والواجهة تنبّه قبل الحفظ.
     */
    public Map<Long, List<String>> officeHolders(long exceptUser) {
        Map<Long, List<String>> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uo.office_id,u.name FROM user_offices uo JOIN users u ON u.id=uo.user_id WHERE u.active=1 AND COALESCE(uo.role,'OWN')='OWN' AND u.id<>"
                + exceptUser + " ORDER BY u.name", null)) {
            while (c.moveToNext()) out.computeIfAbsent(c.getLong(0), k -> new ArrayList<>()).add(c.getString(1));
        }
        return out;
    }

    private void checkAssign(long userId) {
        require(Auth.Perm.USERS);
        Model.User target = user(userId);
        if (target == null || target.deleted) throw new IllegalArgumentException("المستخدم غير موجود");
        if (Auth.isAdmin(target)) requireAdmin("مكاتب حساب المدير يحددها المدير وحده.");
        Model.User me = actor();
        if (me != null && !Auth.isAdmin(me) && me.id == userId) throw new SecurityException("لا تحدد مكاتبك بنفسك؛ يحددها المدير.");
    }

    /** يخصص مكاتب لمستخدم (الجديدة «يسجّل»، والباقية بدورها). بصلاحية «المستخدمون»؛ حساب المدير للمدير وحده، ولا يخصص المستخدم لنفسه. */
    public void setUserOffices(long userId, Set<Long> offices) {
        Map<Long, String> now = officeRoles(userId), roles = new LinkedHashMap<>();
        for (Long o : offices) roles.put(o, now.containsKey(o) ? now.get(o) : ROLE_OWN);
        setUserOffices(userId, roles);
    }

    /** 10.8: مكاتب المستخدم بأدوارها (يسجّل / يتحقق)؛ ما لم يُذكر يُزال. */
    public void setUserOffices(long userId, Map<Long, String> roles) {
        checkAssign(userId);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            Map<Long, String> now = officeRoles(d, userId);
            for (Long o : now.keySet()) if (!roles.containsKey(o)) d.delete("user_offices", "user_id=" + userId + " AND office_id=" + o, null);
            int own = 0, check = 0;
            for (Map.Entry<Long, String> e : roles.entrySet()) {
                String role = ROLE_CHECK.equals(e.getValue()) ? ROLE_CHECK : ROLE_OWN;
                if (role.equals(ROLE_OWN)) own++;
                else check++;
                if (role.equals(now.get(e.getKey()))) continue;
                if (office(e.getKey()) == null) throw new IllegalArgumentException("مكتب غير موجود");
                ContentValues v = new ContentValues();
                v.put("role", role);
                if (d.update("user_offices", v, "user_id=" + userId + " AND office_id=" + e.getKey(), null) == 0) {
                    v.put("user_id", userId);
                    v.put("office_id", e.getKey());
                    d.insertOrThrow("user_offices", null, v);
                }
            }
            audit(d, "USER_OFFICES", "user", userId, "يسجّل " + own + " مكتب، يتحقق من " + check);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** فريق المكتب: من يسجّل ومن يتحقق. */
    public static final class Team {
        public Model.Office office;
        public final List<Model.User> owners = new ArrayList<>(), checkers = new ArrayList<>();
    }

    /** فرق كل المكاتب النشطة (بترتيب المكاتب). */
    public List<Team> teams() {
        Map<Long, Team> m = new LinkedHashMap<>();
        for (Model.Office o : offices()) {
            if (!o.active) continue;
            Team t = new Team();
            t.office = o;
            m.put(o.id, t);
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uo.office_id,COALESCE(uo.role,'OWN'),u.id FROM user_offices uo JOIN users u ON u.id=uo.user_id"
                + " WHERE u.active=1 AND u.deleted_at IS NULL ORDER BY u.name", null)) {
            while (c.moveToNext()) {
                Team t = m.get(c.getLong(0));
                Model.User u = user(c.getLong(2));
                if (t == null || u == null) continue;
                (ROLE_CHECK.equals(c.getString(1)) ? t.checkers : t.owners).add(u);
            }
        }
        return new ArrayList<>(m.values());
    }

    /**
     * 10.12: تقرير «التسجيل والتحقق بين المحاسبين» للفترة [from, to): لكل مكتب من يسجّله ومن يتحقق منه، وكم حركة سُجّلت فيه
     * وكم منها تحقق منها محاسب غير مُدخلها؛ ولكل محاسب ما سجّله وما تحقق منه وما ينتظر تحققه؛ ومطابقات الفترة وهل تُحقق منها.
     * القراءة فقط (لا يغيّر شيئًا)، ويحتاج صلاحية «عرض التقارير».
     */
    public Report teamCheckReport(long from, long to, String periodLabel) {
        require(Auth.Perm.VIEW);
        if (to <= 0) to = Long.MAX_VALUE;
        SQLiteDatabase d = getReadableDatabase();
        Report r = new Report();
        r.title = "التسجيل والتحقق بين المحاسبين";
        r.fileBase = "team-check";
        if (periodLabel != null && !periodLabel.isEmpty()) r.headerLines.add("الفترة: " + periodLabel);
        r.headerLines.add("«تحقق منها» = تحقق منها محاسب غير الذي سجّلها. الحركات النشطة فقط.");
        String range = " AND created_at>=" + from + " AND created_at<" + to;
        String verified = "EXISTS(SELECT 1 FROM tx_checks k WHERE k.tx_id=tx.id AND k.status='VALID' AND (tx.user_id IS NULL OR k.user_id<>tx.user_id))";
        // ---- المكاتب
        Report.Section so = r.section("المكاتب: من يسجّل ومن يتحقق", "المكاتب");
        so.col("المكتب", 2.2f, Report.TEXT).col("يسجّلها", 2f, Report.TEXT).col("يتحقق منها", 2f, Report.TEXT)
                .col("الحركات", 1f, Report.INT).col("تحقق منها", 1f, Report.INT).col("بلا تحقق", 1f, Report.INT).col("النسبة", 0.9f, Report.TEXT);
        long all = 0, ok = 0;
        Map<Long, long[]> perOffice = new HashMap<>();
        try (Cursor c = d.rawQuery("SELECT office_id,COUNT(*),SUM(CASE WHEN " + verified + " THEN 1 ELSE 0 END) FROM tx WHERE status='ACTIVE'" + range + " GROUP BY office_id", null)) {
            while (c.moveToNext()) perOffice.put(c.getLong(0), new long[]{c.getLong(1), c.getLong(2)});
        }
        for (Team t : teams()) {
            long[] n = perOffice.get(t.office.id);
            long cnt = n == null ? 0 : n[0], v = n == null ? 0 : n[1];
            if (cnt == 0 && t.owners.isEmpty() && t.checkers.isEmpty()) continue;
            all += cnt;
            ok += v;
            Report.Row row = so.row(cnt > v ? Report.NORMAL : Report.MUTED, t.office.name, t.owners.isEmpty() ? "—" : names(t.owners),
                    t.checkers.isEmpty() ? "لا أحد" : names(t.checkers), cnt, v, cnt - v, pct(v, cnt));
            if (t.checkers.isEmpty() && cnt > 0) row.style = Report.SHADED;
        }
        so.row(Report.TOTAL, "المجموع", "", "", all, ok, all - ok, pct(ok, all));
        so.kv("حركات الفترة", all, false).kv("تحقق منها محاسب آخر", ok, false).kv("بلا تحقق", all - ok, true).kv("نسبة التحقق", pct(ok, all), true);
        so.note = "المكاتب المظلّلة لها حركات ولا أحد يتحقق منها: وزّع التحقق من «فرز المكاتب بين المحاسبين».";
        // ---- المحاسبون
        Report.Section su = r.section("المحاسبون: ما سجّله كل منهم وما تحقق منه", "المحاسبون");
        su.col("المحاسب", 2f, Report.TEXT).col("سجّل", 1f, Report.INT).col("تحقق غيره مما سجّله", 1.3f, Report.INT)
                .col("تحقق هو منه", 1.2f, Report.INT).col("ينتظر تحققه", 1.2f, Report.INT).col("أدواره", 3f, Report.TEXT);
        for (Model.User u : users(false)) {
            long rec = 0, recOk = 0, did = 0, wait = 0;
            try (Cursor c = d.rawQuery("SELECT COUNT(*),SUM(CASE WHEN " + verified + " THEN 1 ELSE 0 END) FROM tx WHERE status='ACTIVE' AND user_id=" + u.id + range, null)) {
                if (c.moveToFirst()) {
                    rec = c.getLong(0);
                    recOk = c.getLong(1);
                }
            }
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM tx_checks k JOIN tx ON tx.id=k.tx_id WHERE k.status='VALID' AND k.user_id=" + u.id
                    + " AND (tx.user_id IS NULL OR tx.user_id<>" + u.id + ") AND k.created_at>=" + from + " AND k.created_at<" + to, null)) {
                if (c.moveToFirst()) did = c.getLong(0);
            }
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM tx WHERE status='ACTIVE'" + range + " AND (user_id IS NULL OR user_id<>" + u.id + ")"
                    + " AND office_id IN (SELECT office_id FROM user_offices WHERE user_id=" + u.id + " AND role='" + ROLE_CHECK + "')"
                    + " AND NOT EXISTS(SELECT 1 FROM tx_checks k WHERE k.tx_id=tx.id AND k.user_id=" + u.id + " AND k.status='VALID')", null)) {
                if (c.moveToFirst()) wait = c.getLong(0);
            }
            String roles = rolesLine(u.id);
            if (rec == 0 && did == 0 && wait == 0 && roles.isEmpty()) continue;
            su.row(wait > 0 ? Report.NORMAL : Report.MUTED, u.name, rec, recOk, did, wait, roles.isEmpty() ? "—" : roles);
        }
        // ---- المطابقات
        Report.Section sr = r.section("مطابقات الفترة", "المطابقات");
        sr.col("المكتب", 2f, Report.TEXT).col("المطابقات", 1f, Report.INT).col("تحقق منها محاسب آخر", 1.4f, Report.INT).col("بانتظار التحقق", 1.2f, Report.INT);
        long ra = 0, rv = 0;
        try (Cursor c = d.rawQuery("SELECT o.name,COUNT(*),SUM(CASE WHEN r.checked_by IS NOT NULL THEN 1 ELSE 0 END) FROM reconciliations r JOIN offices o ON o.id=r.office_id"
                + " WHERE r.created_at>=" + from + " AND r.created_at<" + to + " GROUP BY r.office_id ORDER BY o.name", null)) {
            while (c.moveToNext()) {
                long n = c.getLong(1), v = c.getLong(2);
                ra += n;
                rv += v;
                sr.row(n > v ? Report.NORMAL : Report.MUTED, c.getString(0), n, v, n - v);
            }
        }
        if (ra > 0) sr.row(Report.TOTAL, "المجموع", ra, rv, ra - rv);
        else sr.note = "لا مطابقات في هذه الفترة.";
        return r;
    }

    private static String pct(long part, long whole) {
        if (whole <= 0) return "—";
        return Math.round(part * 100.0 / whole) + "%";
    }

    // ===================================================================================
    // 10.13: حالة عمل كل مكتب اليوم («مكاتبي»): تلقائية من حركات اليوم ومطابقاته، ويغيّرها المحاسب يدويًا، وتبدأ كل يوم من جديد
    // ===================================================================================

    public static final String W_NONE = "NONE", W_DOING = "DOING", W_DONE = "DONE", W_RECON = "RECON";
    public static final String[] WORK_KEYS = {W_NONE, W_DOING, W_DONE, W_RECON};

    public static String workLabel(String s) {
        if (W_DOING.equals(s)) return "جاري التسجيل";
        if (W_DONE.equals(s)) return "تم التسجيل";
        if (W_RECON.equals(s)) return "أُرسلت مطابقة";
        return "لم يُسجَّل";
    }

    /** ترتيب الحالة (للفرز: ما يحتاج عملًا أولًا). */
    public static int workRank(String s) {
        if (W_DOING.equals(s)) return 1;
        if (W_DONE.equals(s)) return 2;
        if (W_RECON.equals(s)) return 3;
        return 0;
    }

    /** حالة مكتب اليوم: الفعلية، والتلقائية، واليدوية (إن وُضعت) ومن وضعها، وعلامات اليوم. */
    public static final class Work {
        public long officeId;
        public String status = W_NONE, auto = W_NONE, manual;
        public String byName = "";
        public long at;
        /** حركات اليوم في المكتب (كلها، ومنها ما سجّلتُه أنا)، وما لم يتحقق منه محاسب غير مُدخله، ومطابقة اليوم. */
        public int txToday, myTxToday, unverifiedToday;
        public boolean reconToday, myReconToday;
    }

    static String workDay(long t) { return Model.sdf(new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)).format(new java.util.Date(t)); }

    /** حالات كل المكاتب النشطة اليوم (بالمعرّف). القراءة فقط. */
    public Map<Long, Work> officeWork() {
        SQLiteDatabase d = getReadableDatabase();
        long now = Clock.now(), start = ReportBuilder.startOfDay(now);
        Model.User me = actor();
        Map<Long, Work> out = new LinkedHashMap<>();
        for (Model.Office o : offices()) {
            if (!o.active) continue;
            Work w = new Work();
            w.officeId = o.id;
            out.put(o.id, w);
        }
        try (Cursor c = d.rawQuery("SELECT office_id,COUNT(*),SUM(CASE WHEN user_id=" + (me == null ? -1 : me.id) + " THEN 1 ELSE 0 END),"
                + "SUM(CASE WHEN EXISTS(SELECT 1 FROM tx_checks k WHERE k.tx_id=tx.id AND k.status='VALID' AND (tx.user_id IS NULL OR k.user_id<>tx.user_id)) THEN 0 ELSE 1 END)"
                + " FROM tx WHERE status='ACTIVE' AND created_at>=" + start + " GROUP BY office_id", null)) {
            while (c.moveToNext()) {
                Work w = out.get(c.getLong(0));
                if (w == null) continue;
                w.txToday = c.getInt(1);
                w.myTxToday = c.getInt(2);
                w.unverifiedToday = c.getInt(3);
            }
        }
        try (Cursor c = d.rawQuery("SELECT office_id,MAX(CASE WHEN user_id=" + (me == null ? -1 : me.id) + " THEN 1 ELSE 0 END) FROM reconciliations WHERE created_at>=" + start
                + " GROUP BY office_id", null)) {
            while (c.moveToNext()) {
                Work w = out.get(c.getLong(0));
                if (w == null) continue;
                w.reconToday = true;
                w.myReconToday = c.getInt(1) == 1;
            }
        }
        for (Work w : out.values()) w.auto = w.reconToday ? W_RECON : w.txToday > 0 ? W_DOING : W_NONE;
        try (Cursor c = d.rawQuery("SELECT office_id,status,user_name,at FROM office_work WHERE day=?", new String[]{workDay(now)})) {
            while (c.moveToNext()) {
                Work w = out.get(c.getLong(0));
                if (w == null || !Arrays.asList(WORK_KEYS).contains(c.getString(1))) continue;
                w.manual = c.getString(1);
                w.byName = shown(Model.nz(Crypto.dec(c.getString(2))));
                w.at = c.getLong(3);
            }
        }
        for (Work w : out.values()) w.status = w.manual != null ? w.manual : w.auto;
        return out;
    }

    /** 10.13: حركات تحققتُ منها خلال آخر days يومًا (الأحدث أولًا) — علامة «تحققت» في «مكاتبي». */
    public List<Model.Tx> checkedByMe(int days, int limit) {
        List<Model.Tx> out = new ArrayList<>();
        Model.User me = actor();
        if (me == null) return out;
        List<Long> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT tx_id FROM tx_checks WHERE status='VALID' AND user_id=" + me.id + " AND created_at>="
                + (Clock.now() - days * 86_400_000L) + " ORDER BY created_at DESC LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        for (Long id : ids) {
            Model.Tx t = tx(id);
            if (t != null && t.isActive()) out.add(t);
        }
        return out;
    }

    /** 10.13: مطابقات تحققتُ منها خلال آخر days يومًا (الأحدث أولًا). */
    public List<Model.Recon> reconsCheckedByMe(int days, int limit) {
        List<Model.Recon> out = new ArrayList<>();
        Model.User me = actor();
        if (me == null) return out;
        List<Long> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM reconciliations WHERE checked_by=" + me.id + " AND checked_at>="
                + (Clock.now() - days * 86_400_000L) + " ORDER BY checked_at DESC LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        for (Long id : ids) {
            Model.Recon r = recon(id);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** هل يغيّر المستخدم الحالي حالة هذا المكتب: المدير ومدير المستخدمين، ومن يسجّل المكتب أو يتحقق منه، وكل من يسجّل حين لا فرز. */
    public boolean canSetWork(long office) {
        Model.User me = actor();
        if (me == null) return true;
        if (Auth.isAdmin(me) || Auth.can(me, Auth.Perm.USERS)) return true;
        if (!Auth.can(me, Auth.Perm.TX_ADD) && !Auth.can(me, Auth.Perm.VERIFY)) return false;
        Map<Long, String> roles = officeRoles(me.id);
        if (roles.containsKey(office)) return true;
        return roles.isEmpty() && !teamMode();
    }

    /** يضع حالة المكتب اليوم يدويًا؛ status = null يعيدها تلقائية. لا قيد ولا أثر على الأرصدة. */
    public void setOfficeWork(long office, String status) {
        if (office(office) == null) throw new IllegalArgumentException("المكتب غير موجود");
        if (status != null && !Arrays.asList(WORK_KEYS).contains(status)) throw new IllegalArgumentException("حالة غير معروفة");
        if (!canSetWork(office)) throw new SecurityException("لا تغيّر حالة مكتب لا تسجّله ولا تتحقق منه");
        SQLiteDatabase d = getWritableDatabase();
        String day = workDay(Clock.now());
        if (status == null) {
            d.delete("office_work", "office_id=" + office + " AND day=?", new String[]{day});
        } else {
            Model.User me = actor();
            ContentValues v = new ContentValues();
            v.put("office_id", office);
            v.put("day", day);
            v.put("status", status);
            if (me != null) v.put("user_id", me.id);
            v.put("user_name", Crypto.enc(currentUserName));
            v.put("at", Clock.now());
            d.insertWithOnConflict("office_work", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        audit(d, "OFFICE_WORK", "office", office, status == null ? "تلقائية" : workLabel(status));
    }

    /** المحاسبون الذين يمكن فرز المكاتب عليهم: حسابات نشطة غير المدير، تسجّل حركات أو تتحقق. */
    public List<Model.User> accountants() {
        List<Model.User> out = new ArrayList<>();
        for (Model.User u : users(true))
            if (!Auth.isAdmin(u) && (Auth.can(u, Auth.Perm.TX_ADD) || Auth.can(u, Auth.Perm.VERIFY))) out.add(u);
        return out;
    }

    /** يحدد فريق مكتب: من يسجّل ومن يتحقق (لا يجمع محاسب الدورين في المكتب نفسه). */
    public void setOfficeTeam(long office, Set<Long> owners, Set<Long> checkers) {
        require(Auth.Perm.USERS);
        if (office(office) == null) throw new IllegalArgumentException("مكتب غير موجود");
        for (Long u : owners) if (checkers.contains(u)) throw new IllegalArgumentException("المحاسب نفسه لا يسجّل ويتحقق من المكتب نفسه: يتحقق منه محاسب آخر");
        Set<Long> all = new HashSet<>(owners);
        all.addAll(checkers);
        Set<Long> before = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT user_id FROM user_offices WHERE office_id=" + office, null)) {
            while (c.moveToNext()) before.add(c.getLong(0));
        }
        Set<Long> touched = new HashSet<>(all);
        touched.addAll(before);
        for (Long u : touched) checkAssign(u);
        for (Long u : checkers) {
            Model.User x = user(u);
            if (x == null || !Auth.can(x, Auth.Perm.VERIFY)) throw new IllegalArgumentException("«" + (x == null ? "" : x.name) + "» ليست لديه صلاحية «التحقق من الحركات»");
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            for (Long u : before) if (!all.contains(u)) d.delete("user_offices", "user_id=" + u + " AND office_id=" + office, null);
            for (Long u : all) {
                ContentValues v = new ContentValues();
                v.put("role", owners.contains(u) ? ROLE_OWN : ROLE_CHECK);
                if (d.update("user_offices", v, "user_id=" + u + " AND office_id=" + office, null) == 0) {
                    v.put("user_id", u);
                    v.put("office_id", office);
                    d.insertOrThrow("user_offices", null, v);
                }
            }
            audit(d, "OFFICE_TEAM", "office", office, "يسجّل " + owners.size() + "، يتحقق " + checkers.size());
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /**
     * توزيع التحقق تلقائيًا: لكل مكتب له من يسجّله، يتحقق منه كل محاسب آخر له صلاحية التحقق
     * (محاسبان: كل منهما يتحقق من مكاتب الآخر؛ ثلاثة: كل اثنين يتحققان من مكاتب الثالث). يرجع عدد المكاتب التي وُزعت.
     */
    public int autoCheckers() {
        require(Auth.Perm.USERS);
        List<Model.User> acc = accountants();
        int n = 0;
        for (Team t : teams()) {
            if (t.owners.isEmpty()) continue;
            Set<Long> owners = new HashSet<>(), checkers = new HashSet<>();
            for (Model.User u : t.owners) owners.add(u.id);
            for (Model.User u : acc) if (!owners.contains(u.id) && Auth.can(u, Auth.Perm.VERIFY)) checkers.add(u.id);
            if (checkers.isEmpty()) continue;
            setOfficeTeam(t.office.id, owners, checkers);
            n++;
        }
        return n;
    }

    /** 10.18: اقتراح توزيع لمكتب واحد (لا يُحفظ حتى يُعتمد بـ setOfficeTeam). */
    public static final class TeamPlan {
        public Model.Office office;
        /** للمكتب من يسجّله من قبل (فيُسأل المدير عنه واحدًا واحدًا)؛ false = غير موزّع فيُوزَّع بالتساوي. */
        public boolean assigned;
        public final List<Model.User> curOwners = new ArrayList<>(), curCheckers = new ArrayList<>(), owners = new ArrayList<>(), checkers = new ArrayList<>();

        /** المقترح يطابق الحالي (لا شيء يتغير). */
        public boolean same() { return ids(curOwners).equals(ids(owners)) && ids(curCheckers).equals(ids(checkers)); }

        public static Set<Long> ids(List<Model.User> l) {
            Set<Long> s = new HashSet<>();
            for (Model.User u : l) s.add(u.id);
            return s;
        }
    }

    /**
     * 10.18: خطة «التوزيع التلقائي» دون حفظ: المكاتب غير الموزّعة تُوزَّع بالتساوي على المحاسبين الذين يسجّلون
     * (الأقل مكاتب أولًا، فيتساوى الجميع مع ما لديهم من قبل)، والموزّعة يبقى من يسجّلها كما هو. ويتحقق من كل مكتب كل محاسب آخر له صلاحية التحقق.
     */
    public List<TeamPlan> autoTeamPlan() {
        require(Auth.Perm.USERS);
        List<Model.User> acc = accountants(), writers = new ArrayList<>();
        for (Model.User u : acc) if (Auth.can(u, Auth.Perm.TX_ADD)) writers.add(u);
        final Map<Long, Integer> load = new HashMap<>();
        for (Model.User u : writers) load.put(u.id, 0);
        List<Team> teams = teams();
        for (Team t : teams) for (Model.User u : t.owners) if (load.containsKey(u.id)) load.put(u.id, load.get(u.id) + 1);
        List<TeamPlan> out = new ArrayList<>();
        for (Team t : teams) {
            if (!t.office.active) continue;
            TeamPlan p = new TeamPlan();
            p.office = t.office;
            p.assigned = !t.owners.isEmpty();
            p.curOwners.addAll(t.owners);
            p.curCheckers.addAll(t.checkers);
            if (p.assigned) p.owners.addAll(t.owners);
            else if (!writers.isEmpty()) {
                Model.User best = null;
                for (Model.User u : writers) if (best == null || load.get(u.id) < load.get(best.id)) best = u;
                p.owners.add(best);
                load.put(best.id, load.get(best.id) + 1);
            }
            Set<Long> own = TeamPlan.ids(p.owners);
            if (!own.isEmpty()) for (Model.User u : acc) if (!own.contains(u.id) && Auth.can(u, Auth.Perm.VERIFY)) p.checkers.add(u);
            out.add(p);
        }
        return out;
    }

    /** يعتمد اقتراح مكتب واحد من الخطة. */
    public void applyTeamPlan(TeamPlan p) { setOfficeTeam(p.office.id, TeamPlan.ids(p.owners), TeamPlan.ids(p.checkers)); }

    /** سطر أدوار المستخدم للعرض: «يسجّل: …» و«يتحقق من: …». */
    public String rolesLine(long userId) {
        List<String> own = new ArrayList<>(), chk = new ArrayList<>();
        for (Map.Entry<Long, String> e : officeRoles(userId).entrySet()) {
            Model.Office o = office(e.getKey());
            if (o != null) (ROLE_CHECK.equals(e.getValue()) ? chk : own).add(o.name);
        }
        List<String> l = new ArrayList<>();
        if (!own.isEmpty()) l.add("يسجّل: " + Model.join(own, "، "));
        if (!chk.isEmpty()) l.add("يتحقق من: " + Model.join(chk, "، "));
        return Model.join(l, "\n");
    }

    /** أسماء مستخدمين مفصولة بفواصل. */
    public static String names(List<Model.User> l) {
        List<String> n = new ArrayList<>();
        for (Model.User u : l) n.add(label(u));
        return Model.join(n, "، ");
    }

    /** 10.17: اسم مستخدم كما يراه المستخدم الحالي (المدير وصاحب الاسم: اسم الدخول؛ غيرهما: الاسم الظاهر إن وُجد). */
    public static String label(Model.User u) {
        if (u == null) return "";
        Model.User me = SmartApp.currentUser;
        if (me == null || Auth.isAdmin(me) || me.id == u.id || u.nick == null || u.nick.trim().isEmpty()) return u.name;
        return u.nick.trim();
    }

    /** حركات نافذة في المكتب خلال آخر days يومًا لم يتحقق منها أحد غير مُدخلها. */
    public int unverifiedCount(long office, int days) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tx WHERE office_id=" + office + " AND status='ACTIVE' AND created_at>="
                + (Clock.now() - days * 86_400_000L) + " AND NOT EXISTS(SELECT 1 FROM tx_checks k WHERE k.tx_id=tx.id AND k.status='VALID'"
                + " AND (tx.user_id IS NULL OR k.user_id<>tx.user_id))", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** آخر رسالة واتساب نُقلت إلى الدفتر لمكتب: علامة يضعها المحاسب ليعرف من أين يكمل. ليست دليلًا على تسجيل شيء. */
    public static final class WaMark {
        public String uuid = "", text = "", msgTime = "", userName = "";
        public long officeId, at;
        public Long userId;
    }

    public void setWaMark(long office, String text, String msgTime) {
        require(Auth.Perm.TX_ADD);
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("الصق نص آخر رسالة نُقلت");
        if (office(office) == null) throw new IllegalArgumentException("المكتب غير موجود");
        ContentValues v = new ContentValues();
        v.put("uuid", UUID.randomUUID().toString());
        v.put("office_id", office);
        v.put("text", Crypto.enc(text.trim()));
        v.put("msg_time", Crypto.enc(Model.nz(msgTime).trim()));
        Model.User me = actor();
        if (me != null) v.put("user_id", me.id);
        v.put("user_name", Crypto.enc(currentUserName));
        v.put("created_at", Clock.now());
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.insertOrThrow("wa_marks", null, v);
            audit(d, "WA_MARK", "office", office, "");
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** تاريخ العلامة لمكتب، الأحدث أولًا. */
    public List<WaMark> waMarks(long office, int limit) {
        List<WaMark> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uuid,office_id,text,msg_time,user_id,user_name,created_at FROM wa_marks WHERE office_id=" + office
                + " ORDER BY created_at DESC,uuid LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) {
                WaMark m = new WaMark();
                m.uuid = c.getString(0);
                m.officeId = c.getLong(1);
                m.text = Model.nz(Crypto.dec(c.getString(2)));
                m.msgTime = Model.nz(Crypto.dec(c.getString(3)));
                m.userId = c.isNull(4) ? null : c.getLong(4);
                m.userName = shown(Model.nz(Crypto.dec(c.getString(5))));
                m.at = c.getLong(6);
                out.add(m);
            }
        }
        return out;
    }

    public WaMark lastWaMark(long office) {
        List<WaMark> l = waMarks(office, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    private String userLabel(Long id, String fallback) {
        if (id != null) {
            Model.User u = user(id);
            if (u != null) return shown(u.name) + (u.deleted ? " (حساب محذوف)" : "");
        }
        return Model.nz(fallback).isEmpty() ? "—" : fallback;
    }

    /** سطور «من أدخلها، ومن اعتمدها، ومن تحقق منها، ومتى» للعرض في تفاصيل الحركة (الهاتف والكمبيوتر). */
    public List<String> txPeople(Model.Tx t) {
        List<String> l = new ArrayList<>();
        l.add("أدخلها: " + userLabel(t.userId, t.userName) + " — " + Model.date(t.createdAt));
        if (t.approvedAt > 0) l.add("اعتمدها: " + userLabel(t.approvedBy, "") + " — " + Model.date(t.approvedAt));
        else if (t.isPending()) l.add("بانتظار الاعتماد");
        for (TxCheck c : checks(t.id)) {
            String who = userLabel(c.userId, c.userName);
            if (c.valid()) l.add((t.userId != null && c.userId == t.userId ? "تحقق (لا يُحتسب: أدخلها هو): " : "تحقق منها: ") + who + " — " + Model.date(c.at));
            else l.add("تحقق أُبطل: " + who + " — " + Model.date(c.at) + " (أُبطل " + Model.date(c.voidAt) + ": " + c.voidReason + ")");
        }
        return l;
    }

    // ===================================================================================
    // 10.5 (§11): معاينة الرصيد قبل الحفظ — بقواعد الترحيل نفسها (postTx)
    // ===================================================================================

    /** أثر حركة على رصيد مكتبها لكل عملة (موجب = «لنا»)، كما يكتبه postTx بالضبط. */
    public static Map<String, Long> effect(TxInput in) {
        Map<String, Long> m = new TreeMap<>();
        m.merge(in.currency, TYPE_DEBIT.equals(in.type) ? in.amount : -in.amount, Long::sum);
        if (!FEE_NONE.equals(in.feeType) && in.fee > 0 && in.feeCurrency != null)
            m.merge(in.feeCurrency, FEE_US.equals(in.feeType) ? in.fee : -in.fee, Long::sum);
        return m;
    }

    public static final class Preview {
        public long office;
        public String officeName = "";
        /** الرصيد الآن، والرصيد بعد الحفظ (أو بعد الاعتماد إن كانت تحتاجه)، لكل عملة تتأثر. */
        public final Map<String, Long> before = new TreeMap<>(), after = new TreeMap<>();
        /** الحركة تحتاج اعتمادًا: «after» هو الرصيد المتوقع بعد اعتمادها، والرصيد لا يتغير قبله. */
        public boolean pending;
    }

    /**
     * معاينة أثر عملية (ساق أو أكثر) على أرصدة مكاتبها، مع طرح أثر الحركة التي تُستبدل عند التعديل (replacing).
     * تُحسب من قاعدة البيانات لحظة الطلب، فتُستدعى من جديد عند عرض التأكيد وعند الحفظ.
     */
    public List<Preview> preview(List<TxInput> legs, Long replacing) {
        Map<Long, Preview> out = new LinkedHashMap<>();
        SQLiteDatabase d = getReadableDatabase();
        boolean pending = false;
        for (TxInput x : legs) pending |= approvalRequired(x);
        Map<Long, Map<String, Long>> delta = new LinkedHashMap<>();
        for (TxInput x : legs) for (Map.Entry<String, Long> e : effect(x).entrySet()) delta.computeIfAbsent(x.office, k -> new TreeMap<>()).merge(e.getKey(), e.getValue(), Long::sum);
        if (replacing != null) {
            Model.Tx old = tx(replacing);
            if (old != null && old.isActive())
                for (Map.Entry<String, Long> e : effect(inputOf(old)).entrySet()) delta.computeIfAbsent(old.officeId, k -> new TreeMap<>()).merge(e.getKey(), -e.getValue(), Long::sum);
        }
        for (Map.Entry<Long, Map<String, Long>> e : delta.entrySet()) {
            Preview p = new Preview();
            p.office = e.getKey();
            Model.Office o = office(p.office);
            p.officeName = o == null ? "" : Model.nz(o.name);
            p.pending = pending;
            Map<String, Long> now = officeBalances(d, p.office);
            for (Map.Entry<String, Long> c : e.getValue().entrySet()) {
                long b = now.containsKey(c.getKey()) ? now.get(c.getKey()) : 0;
                p.before.put(c.getKey(), b);
                p.after.put(c.getKey(), b + c.getValue());
            }
            out.put(p.office, p);
        }
        return new ArrayList<>(out.values());
    }

    /** مختصر: معاينة حركة واحدة. */
    public List<Preview> preview(TxInput in, Long replacing) {
        List<TxInput> l = new ArrayList<>();
        l.add(in);
        return preview(l, replacing);
    }

    // ===================================================================================
    // دمج مكتبين (8.0): اسمان لحساب واحد مثل «الأمل» و«مكتب الأمل»
    // ===================================================================================

    /** أوامر الدمج بالترتيب ({from} و{into} أرقام). كل ما للمصدر ينتقل للهدف، ثم يُحذف المصدر فارغًا. */
    static final String[] MERGE_SQL = {
            "INSERT OR IGNORE INTO office_currencies(office_id,code) SELECT {into},code FROM office_currencies WHERE office_id={from}",
            "UPDATE tx SET office_id={into} WHERE office_id={from}",
            "UPDATE office_holds SET office_id={into} WHERE office_id={from}",
            "UPDATE journal_lines SET office_id={into} WHERE office_id={from}",
            "UPDATE opening_balances SET office_id={into} WHERE office_id={from}",
            "UPDATE cash_moves SET office_id={into} WHERE office_id={from}",
            "DELETE FROM reconciliation_lines WHERE recon_id IN (SELECT id FROM reconciliations WHERE office_id={from})",
            "DELETE FROM reconciliations WHERE office_id={from}",
            "DELETE FROM office_currencies WHERE office_id={from}",
            // 10.5 (§7): محاسبو المكتب المدموج يصيرون محاسبي الهدف بأدوارهم (10.11، AUDIT-010: «يتحقق» يبقى «يتحقق»)، وتاريخ علامة آخر رسالة ينتقل معه
            "INSERT OR IGNORE INTO user_offices(user_id,office_id,role) SELECT user_id,{into},COALESCE(role,'OWN') FROM user_offices WHERE office_id={from}",
            "DELETE FROM user_offices WHERE office_id={from}",
            "UPDATE wa_marks SET office_id={into} WHERE office_id={from}",
            // 10.13: حالات العمل: يوم للهدف حالته تبقى، وما ليس له ينتقل
            "INSERT OR IGNORE INTO office_work(office_id,day,status,user_id,user_name,at) SELECT {into},day,status,user_id,user_name,at FROM office_work WHERE office_id={from}",
            "DELETE FROM office_work WHERE office_id={from}",
            "DELETE FROM offices WHERE id={from}"
    };

    private static Map<String, Long> officeBalances(SQLiteDatabase d, long office) {
        Map<String, Long> m = new TreeMap<>();
        try (Cursor c = d.rawQuery("SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=" + office + " GROUP BY currency", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getLong(1));
        }
        return m;
    }

    /**
     * يدمج المكتب from في into: الحركات والقيود والأرصدة السابقة تنتقل كما هي (لا يتغير أي مبلغ)، ثم يتحقق أن رصيد into
     * لكل عملة = مجموع رصيدي المكتبين قبل الدمج، وإلا يُلغى كل شيء. يُرفض إن كان للمصدر قيود داخل فترة مقفلة.
     */
    public void mergeOffices(long from, long into) {
        require(Auth.Perm.DELETE);
        if (from == into) throw new IllegalArgumentException("اختر مكتبين مختلفين");
        Model.Office a = office(from), b = office(into);
        if (a == null || b == null) throw new IllegalArgumentException("المكتب غير موجود");
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            long lock = lockBefore(d);
            if (lock > 0) {
                try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM journal_lines l JOIN journal_entries e ON e.id=l.entry_id WHERE l.office_id=" + from
                        + " AND COALESCE(e.created_at,0)<" + lock, null)) {
                    if (c.moveToFirst() && c.getLong(0) > 0) throw new IllegalStateException("للمكتب «" + Model.nz(a.name) + "» قيود داخل فترة محاسبية مقفلة؛ فك الإقفال أولًا إن كان الدمج ضروريًا.");
                }
            }
            Map<String, Long> want = officeBalances(d, into);
            for (Map.Entry<String, Long> e : officeBalances(d, from).entrySet()) want.put(e.getKey(), want.getOrDefault(e.getKey(), 0L) + e.getValue());
            long recons;
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM reconciliations WHERE office_id=" + from, null)) {
                recons = c.moveToFirst() ? c.getLong(0) : 0;
            }
            long txs = officeTxCount(from);
            QuotaState q = quota(d, 0);
            for (String sql : MERGE_SQL) d.execSQL(sql.replace("{from}", "" + from).replace("{into}", "" + into));
            Map<String, Long> got = officeBalances(d, into);
            for (Map.Entry<String, Long> e : want.entrySet()) {
                long g = got.getOrDefault(e.getKey(), 0L);
                if (g != e.getValue()) throw new IllegalStateException("تحقق الدمج فشل في " + e.getKey() + " (المتوقع " + e.getValue() + " والناتج " + g + "). لم يتغير شيء.");
            }
            q.officesMerged(from, into);
            saveQuota(d, q);
            audit(d, "MERGE_OFFICES", "office", into, Model.nz(a.name) + " ← " + Model.nz(b.name) + " — " + txs + " حركة" + (recons > 0 ? "، حُذفت " + recons + " مطابقة للمصدر" : ""));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    // ===================================================================================
    // بيانات تقارير صاحب المكتب (8.0)
    // ===================================================================================

    /** كل الحركات بالحقول التي تحتاجها التقارير فقط (يُفك البيان والوجهة وحدهما، فتسرع مع عشرات الآلاف). */
    public List<Model.Tx> reportTxs() {
        List<Model.Tx> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,uuid,office_id,type,amount_minor,currency,fee_type,fee_minor,fee_currency,description,created_at,status,"
                + "replaces_id,service,dest,pair_uuid,fx_rate FROM tx ORDER BY created_at,id", null)) {
            while (c.moveToNext()) {
                Model.Tx t = new Model.Tx();
                t.id = c.getLong(0);
                t.uuid = c.getString(1);
                t.officeId = c.getLong(2);
                t.type = c.getString(3);
                t.amount = c.getLong(4);
                t.currency = c.getString(5);
                t.feeType = c.getString(6);
                t.fee = c.getLong(7);
                t.feeCurrency = c.getString(8);
                t.description = Model.nz(Crypto.dec(c.getString(9)));
                t.createdAt = c.getLong(10);
                t.status = c.getString(11);
                t.replacesId = c.isNull(12) ? null : c.getLong(12);
                t.service = Model.nz(c.getString(13));
                t.dest = c.isNull(14) ? "" : Model.nz(Crypto.dec(c.getString(14)));
                t.pairUuid = Model.nz(c.getString(15));
                t.fxRate = Model.nz(c.getString(16));
                t.person = "";
                t.notes = "";
                t.userName = "";
                a.add(t);
            }
        }
        return a;
    }

    /** الأرصدة السابقة بتاريخ قيدها (لنا موجب). */
    /** 8.1: بيانات «منشئ التقارير»: كل الحركات بكل حالاتها مع النصوص مفكوكة، والأرصدة السابقة والأسماء والمنازل. */
    public ReportBuilder.Data builderData() {
        ReportBuilder.Data d = new ReportBuilder.Data();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,uuid,office_id,type,amount_minor,currency,fee_type,fee_minor,fee_currency,description,created_at,status,"
                + "replaces_id,service,dest,pair_uuid,fx_rate,person,notes,user_name FROM tx ORDER BY created_at,id", null)) {
            while (c.moveToNext()) {
                Model.Tx t = new Model.Tx();
                t.id = c.getLong(0);
                t.uuid = c.getString(1);
                t.officeId = c.getLong(2);
                t.type = c.getString(3);
                t.amount = c.getLong(4);
                t.currency = c.getString(5);
                t.feeType = c.getString(6);
                t.fee = c.getLong(7);
                t.feeCurrency = c.getString(8);
                t.description = Model.nz(Crypto.dec(c.getString(9)));
                t.createdAt = c.getLong(10);
                t.status = c.getString(11);
                t.replacesId = c.isNull(12) ? null : c.getLong(12);
                t.service = Model.nz(c.getString(13));
                t.dest = c.isNull(14) ? "" : Model.nz(Crypto.dec(c.getString(14)));
                t.pairUuid = Model.nz(c.getString(15));
                t.fxRate = Model.nz(c.getString(16));
                t.person = Model.nz(Crypto.dec(c.getString(17)));
                t.notes = Model.nz(Crypto.dec(c.getString(18)));
                t.userReal = Model.nz(Crypto.dec(c.getString(19)));
                t.userName = shown(t.userReal);
                d.txs.add(t);
            }
        }
        d.openings.addAll(reportOpenings());
        d.names.putAll(officeNames());
        d.decimals.putAll(decimalsMap());
        return d;
    }

    public List<OwnerReports.Opening> reportOpenings() {
        List<OwnerReports.Opening> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT o.office_id,o.currency,o.type,o.amount_minor,COALESCE(e.created_at,0) FROM opening_balances o "
                + "LEFT JOIN journal_entries e ON e.id=o.entry_id", null)) {
            while (c.moveToNext()) {
                long amt = c.getLong(3);
                l.add(new OwnerReports.Opening(c.getLong(0), c.getString(1), "لنا".equals(c.getString(2)) ? amt : -amt, c.getLong(4)));
            }
        }
        return l;
    }

    /** خدمات استُعملت من قبل (الأكثر أولًا) لاقتراحها في النموذج. */
    public List<String> usedServices(int limit) {
        List<String> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT service,COUNT(*) n FROM tx WHERE service IS NOT NULL AND service<>'' AND status='ACTIVE' GROUP BY service ORDER BY n DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) l.add(c.getString(0));
        }
        return l;
    }

    /** قيد مزدوج: سطر المكتب + سطر الحساب المقابل بقيمة معاكسة. */
    private long postEntry(SQLiteDatabase d, Long tx, String kind, long office, String cur, long officeDebit, long officeCredit, long when) {
        ContentValues e = new ContentValues();
        if (tx != null) e.put("tx_id", tx);
        e.put("kind", kind);
        e.put("created_at", when);
        long eid = ins(d, "journal_entries", e);
        line(d, eid, "OFFICE", office, cur, officeDebit, officeCredit);
        String contra = kind.equals("FEE") ? (officeDebit > 0 ? "FEE_REVENUE" : "FEE_EXPENSE") : (kind.equals("OPENING") ? "OPENING" : "SETTLEMENT");
        line(d, eid, contra, 0, cur, officeCredit, officeDebit);
        return eid;
    }

    private void line(SQLiteDatabase d, long eid, String acc, long office, String cur, long debit, long credit) {
        if (debit < 0 || credit < 0 || debit == credit) throw new IllegalArgumentException("قيد غير صالح");
        ContentValues l = new ContentValues();
        l.put("entry_id", eid);
        l.put("account", acc);
        if (office > 0) l.put("office_id", office);
        l.put("currency", cur);
        l.put("debit_minor", debit);
        l.put("credit_minor", credit);
        ins(d, "journal_lines", l);
    }

    private static void checkLock(SQLiteDatabase d, long when, String message) {
        long lock = lockBefore(d);
        if (lock > 0 && when < lock) throw new IllegalStateException(message + ". فك الإقفال من الإعدادات أولًا إن كان ذلك ضروريًا.");
    }

    /** يعكس كل قيود الحركة (نفس الحساب والعملة والقيمة المعاكسة) ويجعلها ملغاة. */
    private void reverseTx(SQLiteDatabase d, long id, long now) {
        String st = null;
        long created = 0;
        try (Cursor q = d.rawQuery("SELECT status,created_at FROM tx WHERE id=" + id, null)) {
            if (q.moveToFirst()) {
                st = q.getString(0);
                created = q.getLong(1);
            }
        }
        if (st == null) throw new IllegalStateException("الحركة غير موجودة");
        if (Model.STATUS_PENDING.equals(st)) throw new IllegalStateException("الحركة بانتظار الاعتماد: اعتمدها أو ارفضها أولًا");
        if (Model.STATUS_SPECIAL.equals(st)) throw new IllegalStateException("حركة خاصة لا تُحسب في الرصيد: حوّلها إلى حركة عادية أو ألغها من «الأمانات والخاصة»");
        if (!"ACTIVE".equals(st)) throw new IllegalStateException("هذه الحركة ملغاة أو معدّلة أو مرفوضة");
        checkLock(d, created, "الحركة ضمن فترة محاسبية مقفلة");
        List<Long> olds = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT id FROM journal_entries WHERE tx_id=" + id
                + " AND COALESCE(status,'POSTED')='POSTED' AND COALESCE(kind,'')<>'REVERSAL'", null)) {
            while (c.moveToNext()) olds.add(c.getLong(0));
        }
        for (long old : olds) {
            ContentValues e = new ContentValues();
            e.put("tx_id", id);
            e.put("kind", "REVERSAL");
            e.put("created_at", now);
            e.put("reverses_id", old);
            long ne = ins(d, "journal_entries", e);
            // 10.0: سطرًا سطرًا (لا INSERT…SELECT) ليأخذ كل سطر رقمه من نطاق الجهاز
            List<ContentValues> rev = new ArrayList<>();
            try (Cursor lc = d.rawQuery("SELECT account,office_id,currency,credit_minor,debit_minor FROM journal_lines WHERE entry_id=" + old + " ORDER BY id", null)) {
                while (lc.moveToNext()) {
                    ContentValues l = new ContentValues();
                    l.put("entry_id", ne);
                    l.put("account", lc.getString(0));
                    if (!lc.isNull(1)) l.put("office_id", lc.getLong(1));
                    l.put("currency", lc.getString(2));
                    l.put("debit_minor", lc.getLong(3));
                    l.put("credit_minor", lc.getLong(4));
                    rev.add(l);
                }
            }
            for (ContentValues l : rev) ins(d, "journal_lines", l);
        }
        ContentValues v = new ContentValues();
        v.put("status", "CANCELLED");
        d.update("tx", v, "id=" + id, null);
    }

    public void cancelTx(long id, String reason) {
        Model.Tx sp = tx(id);
        if (sp != null && sp.isUnposted()) {   // 12.4: لا قيد لها ليُعكس
            voidSpecial(id, reason);
            return;
        }
        require(Auth.Perm.TX_CANCEL);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            long now = Clock.now();
            reverseTx(d, id, now);
            // ساقا تبديل العملة تُلغيان معًا: لا يبقى نصف عملية في الأرصدة
            String pair = null;
            try (Cursor c = d.rawQuery("SELECT pair_uuid FROM tx WHERE id=" + id, null)) {
                if (c.moveToFirst() && !c.isNull(0) && !c.getString(0).isEmpty()) pair = c.getString(0);
            }
            List<Long> legs = new ArrayList<>();
            if (pair != null) {
                try (Cursor c = d.rawQuery("SELECT id FROM tx WHERE pair_uuid=? AND status='ACTIVE' AND id<>" + id, new String[]{pair})) {
                    while (c.moveToNext()) legs.add(c.getLong(0));
                }
                for (long leg : legs) reverseTx(d, leg, now);
            }
            // 9.0: حركة الصندوق المرتبطة تُلغى معها (لا يبقى نقد محسوب لحركة مكتب ملغاة) — 10.5: ولكل ساقات العملية
            ContentValues cv = new ContentValues();
            cv.put("status", CashBox.CANCELLED);
            cv.put("cancel_reason", Crypto.enc("أُلغيت حركة المكتب: " + Model.nz(reason)));
            cv.put("cancelled_at", now);
            d.update("cash_moves", cv, "tx_id=" + id + " AND status='ACTIVE'", null);
            for (long leg : legs) d.update("cash_moves", cv, "tx_id=" + leg + " AND status='ACTIVE'", null);
            audit(d, "CANCEL_TRANSACTION", "tx", id, reason);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    private static long lockBefore(SQLiteDatabase d) {
        try {
            String v = rawSetting(d, "lock_before");
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private static final String TX_COLS = "id,uuid,office_id,type,person,amount_minor,currency,fee_type,fee_minor,fee_currency,description,notes,created_at,status,replaces_id,user_name,service,dest,pair_uuid,fx_rate,user_id,approved_by,approved_at,special,special_bal";

    private Model.Tx readTx(Cursor c) {
        Model.Tx t = readTxRaw(c);
        t.userName = shown(t.userReal);
        return t;
    }

    /** بلا «الاسم الظاهر» (لعمليات النظام والمزامنة التي لا مستخدم لها). */
    private static Model.Tx readTxRaw(Cursor c) {
        Model.Tx t = new Model.Tx();
        t.id = c.getLong(0);
        t.uuid = c.getString(1);
        t.officeId = c.getLong(2);
        t.type = c.getString(3);
        t.person = Model.nz(Crypto.dec(c.getString(4)));
        t.amount = c.getLong(5);
        t.currency = c.getString(6);
        t.feeType = c.getString(7);
        t.fee = c.getLong(8);
        t.feeCurrency = c.getString(9);
        t.description = Model.nz(Crypto.dec(c.getString(10)));
        t.notes = Model.nz(Crypto.dec(c.getString(11)));
        t.createdAt = c.getLong(12);
        t.status = c.getString(13);
        t.replacesId = c.isNull(14) ? null : c.getLong(14);
        t.userReal = Model.nz(Crypto.dec(c.getString(15)));
        t.userName = t.userReal;
        t.service = Model.nz(c.getString(16));
        t.dest = c.isNull(17) ? "" : Model.nz(Crypto.dec(c.getString(17)));
        t.pairUuid = Model.nz(c.getString(18));
        t.fxRate = Model.nz(c.getString(19));
        t.userId = c.isNull(20) ? null : c.getLong(20);
        t.approvedBy = c.isNull(21) ? null : c.getLong(21);
        t.approvedAt = c.isNull(22) ? 0 : c.getLong(22);
        t.special = c.isNull(23) ? "" : Model.nz(c.getString(23));
        t.specialBal = c.isNull(24) || c.getLong(24) != 0;
        return t;
    }

    /**
     * 10.18: رصيد المكتب بعملة واحدة بعد كل حركة (للتدقيق): يجمع قيود المكتب بهذه العملة بترتيبها الزمني، ويربط كل حركة
     * بالرصيد بعد آخر قيد لها (فيشمل أجرتها إن كانت بالعملة نفسها، والأرصدة السابقة والتسويات التي قبلها).
     * آخر قيمة = الرصيد الحالي. الحركات الملغاة والمعدّلة (قيدها وعكسه) خارج التراكم، فالعمود يطابق الحركات النشطة المعروضة.
     */
    public Map<Long, Long> balancesAfter(long officeId, String currency) {
        Map<Long, Long> out = new HashMap<>();
        long run = 0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT e.tx_id,SUM(l.debit_minor-l.credit_minor) FROM journal_entries e JOIN journal_lines l ON l.entry_id=e.id "
                + "LEFT JOIN tx t ON t.id=e.tx_id WHERE l.account='OFFICE' AND l.office_id=? AND l.currency=? AND (e.tx_id IS NULL OR t.status='ACTIVE') "
                + "GROUP BY e.id ORDER BY e.created_at,e.id", new String[]{"" + officeId, currency})) {
            while (c.moveToNext()) {
                run += c.getLong(1);
                if (!c.isNull(0)) out.put(c.getLong(0), run);
            }
        }
        return out;
    }

    public static final class TxFilter {
        public Long office;
        public String type;
        public String currency;
        public long from = 0;
        public long to = Long.MAX_VALUE;
        public boolean includeCancelled;
        /** حد أقصى لعدد الحركات (الأحدث أولًا)؛ 0 = بلا حد. */
        public int limit;
    }

    private static String txWhere(TxFilter f, List<String> args) { return txWhere(f, args, ""); }

    /** p = بادئة الجدول (مثل "t.") حين يُربط بجدول آخر. */
    private static String txWhere(TxFilter f, List<String> args, String p) {
        StringBuilder w = new StringBuilder(" WHERE " + p + "created_at>=" + f.from + " AND " + p + "created_at<" + f.to);
        if (f.office != null) w.append(" AND ").append(p).append("office_id=").append(f.office.longValue());
        if (!f.includeCancelled) w.append(" AND ").append(p).append("status='ACTIVE'");
        if (f.type != null) {
            w.append(" AND ").append(p).append("type=?");
            args.add(f.type);
        }
        if (f.currency != null) {
            w.append(" AND ").append(p).append("currency=?");
            args.add(f.currency);
        }
        return w.toString();
    }

    /** الحركات حسب الفلتر، الأحدث أولًا. */
    public List<Model.Tx> txLog(TxFilter f) {
        List<String> args = new ArrayList<>();
        String w = txWhere(f, args);
        List<Model.Tx> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + TX_COLS + " FROM tx" + w + " ORDER BY created_at DESC,id DESC" + (f.limit > 0 ? " LIMIT " + f.limit : ""),
                args.isEmpty() ? null : args.toArray(new String[0]))) {
            while (c.moveToNext()) a.add(readTx(c));
        }
        return a;
    }

    /** 12.7: نص البحث لحركة (بالحروف الصغيرة): كل ما يطابقه بحث السجل عدا اسم المكتب والاسم الظاهر. */
    String searchText(Model.Tx x) {
        int d = decimals(x.currency);
        return (x.person + " " + x.description + " " + x.notes + " " + x.uuid + " " + Model.nz(x.userReal) + " " + Model.nz(x.dest) + " " + x.currency
                + " " + Model.plain(x.amount, d) + " " + Model.fmt(x.amount, d)).toLowerCase(Locale.ROOT);
    }

    private static final Object SEARCH_LOCK = new Object();

    /** 12.7: يكمل نص البحث للحركات الجديدة أو المعدّلة (على دفعات قصيرة؛ أول مرة تُفك الحركات القديمة مرة واحدة فقط). */
    public void fillSearchIndex() {
        synchronized (SEARCH_LOCK) {
            while (true) {
                List<Model.Tx> batch = new ArrayList<>();
                try (Cursor c = getReadableDatabase().rawQuery("SELECT t." + TX_COLS.replace(",", ",t.")
                        + " FROM tx t LEFT JOIN tx_search s ON s.tx_id=t.id WHERE s.tx_id IS NULL OR s.uuid IS NOT t.uuid LIMIT 1000", null)) {
                    while (c.moveToNext()) batch.add(readTxRaw(c));
                }
                if (batch.isEmpty()) return;
                SQLiteDatabase w = getWritableDatabase();
                w.beginTransaction();
                try {
                    for (Model.Tx x : batch) w.execSQL("INSERT OR REPLACE INTO tx_search(tx_id,uuid,txt) VALUES(?,?,?)", new Object[]{x.id, x.uuid, searchText(x)});
                    w.setTransactionSuccessful();
                } finally {
                    w.endTransaction();
                }
                if (batch.size() < 1000) return;
            }
        }
    }

    /**
     * 12.7: حركات الفلتر التي قد تطابق النص (مرشّحة من نص البحث، أو من مكتب أو اسم ظاهر يطابقه). مجموعة أوسع من المطابق،
     * والتحقق النهائي في الواجهة كما كان. term بالحروف الصغيرة.
     */
    public List<Model.Tx> txSearch(TxFilter f, String term) {
        fillSearchIndex();
        List<String> args = new ArrayList<>();
        String w = txWhere(f, args, "t.");
        StringBuilder or = new StringBuilder("instr(s.txt,?)>0");
        args.add(term);
        List<Long> offs = new ArrayList<>();
        for (Map.Entry<Long, String> e : officeNames().entrySet()) if (Model.nz(e.getValue()).toLowerCase(Locale.ROOT).contains(term)) offs.add(e.getKey());
        if (!offs.isEmpty()) or.append(" OR t.office_id IN (").append(Model.join(longs(offs), ",")).append(")");
        List<Long> who = new ArrayList<>();
        for (Model.User u : users(true)) if (u.nick != null && u.nick.toLowerCase(Locale.ROOT).contains(term)) who.add(u.id);
        if (!who.isEmpty()) or.append(" OR t.user_id IN (").append(Model.join(longs(who), ",")).append(")");
        List<Model.Tx> a = new ArrayList<>();
        String cols = "t." + TX_COLS.replace(",", ",t.");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + cols + " FROM tx t LEFT JOIN tx_search s ON s.tx_id=t.id" + w + " AND (" + or + ") ORDER BY t.created_at DESC,t.id DESC",
                args.toArray(new String[0]))) {
            while (c.moveToNext()) a.add(readTx(c));
        }
        return a;
    }

    private static List<String> longs(List<Long> l) {
        List<String> o = new ArrayList<>();
        for (Long x : l) o.add(String.valueOf(x));
        return o;
    }

    /** عدد الحركات حسب الفلتر (بلا فك أي حقل). */
    public long txCount(TxFilter f) {
        List<String> args = new ArrayList<>();
        String w = txWhere(f, args);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tx" + w, args.isEmpty() ? null : args.toArray(new String[0]))) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** حركات للمقارنة بالنص فقط (كشف تسجيل الرسالة مرتين): يُفك البيان وحده بدل أربعة حقول. */
    public List<Model.Tx> descriptionsSince(long from) {
        List<Model.Tx> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,office_id,amount_minor,currency,created_at,description,type FROM tx WHERE status='ACTIVE' AND created_at>=" + from
                + " ORDER BY created_at DESC,id DESC", null)) {
            while (c.moveToNext()) {
                Model.Tx t = new Model.Tx();
                t.id = c.getLong(0);
                t.officeId = c.getLong(1);
                t.amount = c.getLong(2);
                t.currency = c.getString(3);
                t.createdAt = c.getLong(4);
                t.description = Model.nz(Crypto.dec(c.getString(5)));
                t.type = c.getString(6);
                t.person = "";
                t.notes = "";
                t.userName = "";
                t.status = "ACTIVE";
                a.add(t);
            }
        }
        return a;
    }

    /** يوقف تحويل التشفير في الخلفية (قبل الاستعادة أو الإضافة أو نسخة الأمان). */
    static volatile boolean stopMaintenance;
    /** تحمله كل دفعة تحويل؛ نسخة الأمان تنتظره حتى لا تُنسخ قاعدة البيانات أثناء كتابة دفعة. */
    static final Object MAINT_LOCK = new Object();

    /**
     * يحوّل الحقول المشفرة بالطريقة القديمة (Keystore لكل حقل، بطيئة) إلى مفتاح البيانات السريع، في الخلفية على دفعات.
     * لا يغيّر أي قيمة: يفك النص ويعيد تشفيره فقط، ويحدّث الصف بشرط أنه لم يتغير منذ قراءته.
     */
    public void reencryptLegacy() {
        if (!Crypto.fast() || "1".equals(setting("crypto_v2_done", "0"))) return;
        for (Map.Entry<String, List<String>> e : SENSITIVE.entrySet()) {
            String table = e.getKey();
            for (String col : e.getValue()) {
                long after = 0;
                while (true) {
                  synchronized (MAINT_LOCK) {
                    if (stopMaintenance) return;
                    List<Object[]> batch = new ArrayList<>();
                    try (Cursor c = getReadableDatabase().rawQuery("SELECT rowid," + col + " FROM " + table + " WHERE rowid>" + after + " ORDER BY rowid LIMIT 100", null)) {
                        while (c.moveToNext()) batch.add(new Object[]{c.getLong(0), c.isNull(1) ? null : c.getString(1)});
                    }
                    if (batch.isEmpty()) break;
                    after = (Long) batch.get(batch.size() - 1)[0];
                    List<Object[]> upd = new ArrayList<>();
                    for (Object[] r : batch) {
                        String v = (String) r[1];
                        if (!Crypto.isLegacy(v)) continue;
                        try {
                            upd.add(new Object[]{Crypto.enc(Crypto.decStrict(v)), r[0], v});
                        } catch (Exception x) {
                            // حقل لا يُفك (تالف أو نص عادي): يبقى كما هو
                        }
                    }
                    if (upd.isEmpty() || stopMaintenance) continue;
                    SQLiteDatabase d = getWritableDatabase();
                    d.beginTransaction();
                    try {
                        // إعادة تشفير محلية بالمحتوى نفسه: لا تُرسل للأجهزة الأخرى
                        SyncStore.capture(d, false);
                        for (Object[] u : upd) d.execSQL("UPDATE " + table + " SET " + col + "=? WHERE rowid=? AND " + col + "=?", u);
                        SyncStore.capture(d, syncPrefix > 0);
                        d.setTransactionSuccessful();
                    } finally {
                        d.endTransaction();
                    }
                  }
                }
            }
        }
        setSettingQuiet("crypto_v2_done", "1");
    }

    /** آخر الحركات النشطة (للصفحة الرئيسية) دون قراءة السجل كله. */
    public List<Model.Tx> recentTx(int limit) {
        List<Model.Tx> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + TX_COLS + " FROM tx WHERE status='ACTIVE' ORDER BY created_at DESC,id DESC LIMIT " + Math.max(1, Math.min(limit, 100)), null)) {
            while (c.moveToNext()) a.add(readTx(c));
        }
        return a;
    }

    public List<Model.Tx> transactions(Long office, boolean cancelled) {
        TxFilter f = new TxFilter();
        f.office = office;
        f.includeCancelled = cancelled;
        return txLog(f);
    }

    public Model.Tx tx(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + TX_COLS + " FROM tx WHERE id=" + id, null)) {
            return c.moveToFirst() ? readTx(c) : null;
        }
    }

    /**
     * 10.2: حركات مشابهة (المكتب والنوع والعملة والمبلغ نفسها، والاسم نفسه، خلال window حول التاريخ) ما زالت نشطة أو معلّقة.
     * تُستخدم لكشف حركة سُجّلت مرتين من جهازين قبل أن يتزامنا، وعند الاعتماد.
     */
    public List<Model.Tx> similarTx(SQLiteDatabase d, Model.Tx t, long window) {
        List<Model.Tx> out = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT " + TX_COLS + " FROM tx WHERE office_id=? AND type=? AND currency=? AND amount_minor=? AND id<>? AND status IN ('ACTIVE','PENDING')"
                + " AND created_at BETWEEN ? AND ?", new String[]{"" + t.officeId, t.type, t.currency, "" + t.amount, "" + t.id, "" + (t.createdAt - window), "" + (t.createdAt + window)})) {
            String p = Sanity.norm(t.person), ds = Sanity.norm(t.description);
            while (c.moveToNext()) {
                Model.Tx o = readTx(c);
                if (!Sanity.norm(o.person).equals(p)) continue;
                if (p.isEmpty() && !Sanity.norm(o.description).equals(ds)) continue;
                out.add(o);
            }
        }
        return out;
    }

    public List<Model.Tx> similarTx(Model.Tx t) { return similarTx(getReadableDatabase(), t, 36 * 3600000L); }

    static Model.Tx txOn(SQLiteDatabase d, long id) {
        try (Cursor c = d.rawQuery("SELECT " + TX_COLS + " FROM tx WHERE id=" + id, null)) {
            return c.moveToFirst() ? readTxRaw(c) : null;
        }
    }

    public Long replacementOf(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM tx WHERE replaces_id=" + id + " ORDER BY id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : null;
        }
    }

    public long txCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tx", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public long officeCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM offices", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** مجاميع فترة: العملة ← {لنا، علينا، أجور لنا، أجور علينا، عدد الحركات}. الحركات الملغاة غير محسوبة. */
    public Map<String, long[]> dailyStats(long from, long to) {
        Map<String, long[]> m = new TreeMap<>();
        String range = " WHERE status='ACTIVE' AND created_at>=" + from + " AND created_at<" + to;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT currency,type,SUM(amount_minor),COUNT(*) FROM tx" + range + " GROUP BY currency,type", null)) {
            while (c.moveToNext()) {
                long[] v = stat(m, c.getString(0));
                if (TYPE_DEBIT.equals(c.getString(1))) v[0] += c.getLong(2);
                else v[1] += c.getLong(2);
                v[4] += c.getLong(3);
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT fee_currency,fee_type,SUM(fee_minor) FROM tx" + range
                + " AND fee_minor>0 AND fee_type IN (?,?) GROUP BY fee_currency,fee_type", new String[]{FEE_US, FEE_THEM})) {
            while (c.moveToNext()) {
                long[] v = stat(m, c.getString(0));
                if (FEE_US.equals(c.getString(1))) v[2] += c.getLong(2);
                else v[3] += c.getLong(2);
            }
        }
        return m;
    }

    private static long[] stat(Map<String, long[]> m, String code) {
        long[] v = m.get(code);
        if (v == null) {
            v = new long[5];
            m.put(code, v);
        }
        return v;
    }

    // ===================================================================================
    // المرفقات
    // ===================================================================================

    public File evidenceDir() { return new File(ctx.getFilesDir(), "evidence"); }

    /**
     * 10.11 (AUDIT-002): ملف الصورة كما حُفظ مساره، وإن لم يوجد هناك (نُقل مجلد البيانات أو تغيّر اسم مستخدم النظام)
     * فباسمه في مجلد الصور الحالي. المسار المحفوظ لا يتغير، فلا تتأثر الأجهزة والإصدارات الأقدم.
     */
    public File attachmentFile(String path) {
        if (path == null) return null;
        File f = new File(path);
        if (f.isFile()) return f;
        return new File(evidenceDir(), f.getName());
    }

    public byte[] attachment(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path FROM attachments WHERE id=" + id, null)) {
            if (!c.moveToFirst()) return null;
            try {
                return Crypto.decBytes(readAll(attachmentFile(c.getString(0))));
            } catch (Exception e) {
                return null;
            }
        }
    }

    public List<Long> attachmentIds(long tx) {
        List<Long> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM attachments WHERE tx_id=" + tx + " ORDER BY id", null)) {
            while (c.moveToNext()) a.add(c.getLong(0));
        }
        return a;
    }

    public Map<Long, Integer> attachmentCounts(Long office) {
        Map<Long, Integer> m = new HashMap<>();
        String q = "SELECT a.tx_id,COUNT(*) FROM attachments a JOIN tx t ON t.id=a.tx_id" + (office == null ? "" : " WHERE t.office_id=" + office.longValue()) + " GROUP BY a.tx_id";
        try (Cursor c = getReadableDatabase().rawQuery(q, null)) {
            while (c.moveToNext()) m.put(c.getLong(0), c.getInt(1));
        }
        return m;
    }

    static byte[] readAll(File f) throws IOException {
        try (FileInputStream i = new FileInputStream(f)) {
            return readAll(i);
        }
    }

    static byte[] readAll(InputStream i) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = i.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    // ===================================================================================
    // الأرصدة والكشوفات
    // ===================================================================================

    /** رصيد مكتب لكل عملة (موجب = لنا، سالب = علينا)، مع عملات المكتب المفعّلة بقيمة صفر. */
    public Map<String, Long> balances(long office) {
        Map<String, Long> m = new TreeMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=" + office + " GROUP BY currency", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getLong(1));
        }
        for (String x : officeCurrencies(office)) if (!m.containsKey(x)) m.put(x, 0L);
        return m;
    }

    public Map<Long, Map<String, Long>> officeBalances() {
        Map<Long, Map<String, Long>> m = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT office_id,currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id IS NOT NULL GROUP BY office_id,currency", null)) {
            while (c.moveToNext()) {
                Map<String, Long> per = m.get(c.getLong(0));
                if (per == null) {
                    per = new TreeMap<>();
                    m.put(c.getLong(0), per);
                }
                per.put(c.getString(1), c.getLong(2));
            }
        }
        return m;
    }

    /**
     * لكل مكتب ولكل عملة: كل ما لنا (مجموع المدين)، كل ما علينا (مجموع الدائن)، والرصيد.
     * الحركات الملغاة وقيودها العكسية مستبعدة من المجاميع (صافيها صفر) حتى لا تُضخّم الأرقام.
     */
    public List<Model.OfficeTotals> officeTotals(Long onlyOffice) {
        Map<Long, Map<String, long[]>> g = new HashMap<>();
        // 12.7: نفس النتيجة بطريقة أسرع بكثير: مجموع كل أسطر المكاتب (من الفهرس وحده) ناقص أسطر قيود الحركات غير النشطة
        // والقيود المرتبطة بحركة غير موجودة. كانت تربط كل سطر بقيده وحركته (ثوانٍ مع مئات آلاف الأسطر).
        String only = onlyOffice == null ? "" : " AND l.office_id=" + onlyOffice.longValue();
        SQLiteDatabase rd = getReadableDatabase();
        try (Cursor c = rd.rawQuery("SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM journal_lines l"
                + " WHERE l.account='OFFICE' AND l.office_id IS NOT NULL" + only + " GROUP BY l.office_id,l.currency", null)) {
            while (c.moveToNext()) {
                Map<String, long[]> per = g.get(c.getLong(0));
                if (per == null) {
                    per = new TreeMap<>();
                    g.put(c.getLong(0), per);
                }
                long d = c.getLong(2), cr = c.getLong(3);
                per.put(c.getString(1), new long[]{d, cr, d - cr});
            }
        }
        String[] minus = {
                "SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM tx t JOIN journal_entries e ON e.tx_id=t.id"
                        + " JOIN journal_lines l ON l.entry_id=e.id WHERE t.status<>'ACTIVE' AND l.account='OFFICE' AND l.office_id IS NOT NULL" + only
                        + " GROUP BY l.office_id,l.currency",
                "SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM journal_entries e JOIN journal_lines l ON l.entry_id=e.id"
                        + " WHERE e.tx_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tx x WHERE x.id=e.tx_id) AND l.account='OFFICE' AND l.office_id IS NOT NULL" + only
                        + " GROUP BY l.office_id,l.currency"};
        for (String q : minus) {
            try (Cursor c = rd.rawQuery(q, null)) {
                while (c.moveToNext()) {
                    Map<String, long[]> per = g.get(c.getLong(0));
                    long[] v = per == null ? null : per.get(c.getString(1));
                    if (v == null) continue;
                    v[0] -= c.getLong(2);
                    v[1] -= c.getLong(3);
                    v[2] = v[0] - v[1];
                }
            }
        }
        Map<Long, List<String>> curs = new HashMap<>();
        try (Cursor c = rd.rawQuery("SELECT office_id,code FROM office_currencies ORDER BY office_id,code", null)) {
            while (c.moveToNext()) curs.computeIfAbsent(c.getLong(0), k -> new ArrayList<>()).add(c.getString(1));
        }
        List<Model.OfficeTotals> out = new ArrayList<>();
        List<Model.Office> offs;
        if (onlyOffice == null) offs = offices();
        else {
            offs = new ArrayList<>();
            Model.Office o = office(onlyOffice);
            if (o != null) offs.add(o);
        }
        for (Model.Office o : offs) {
            Model.OfficeTotals t = new Model.OfficeTotals();
            t.officeId = o.id;
            t.name = o.name;
            t.pinned = o.pinned;
            t.active = o.active;
            Map<String, long[]> per = new TreeMap<>();
            if (g.get(o.id) != null) per.putAll(g.get(o.id));
            List<String> oc = curs.get(o.id);
            if (oc != null) for (String code : oc) if (!per.containsKey(code)) per.put(code, new long[3]);
            t.perCurrency.putAll(per);
            out.add(t);
        }
        return out;
    }

    /** أسطر كشف المكتب من دفتر القيود خلال فترة [from, to). */
    public List<Model.Line> statementLines(long office, long from, long to) {
        return readLines(" AND COALESCE(e.created_at,0)>=" + from + " AND COALESCE(e.created_at,0)<" + to, office);
    }

    /** أسطر المكتب المسجّلة بعد قيد معيّن (بعد آخر مطابقة)، بما فيها حركات بتاريخ سابق وإلغاءات لحركات قديمة. */
    public List<Model.Line> statementLinesAfter(long office, long cutoffEntry) {
        return readLines(" AND l.entry_id>" + cutoffEntry, office);
    }

    /** 10.0: بعد مطابقة (بنطاقات أرقام الأجهزة إن وُجدت). */
    public List<Model.Line> statementLinesAfter(long office, Model.Recon r) {
        return readLines(" AND NOT " + SyncStore.upTo("l.entry_id", r.cutoffEntry, r.cutoffMarks), office);
    }

    public Map<String, Long> balanceUpTo(long office, Model.Recon r) {
        Map<String, Long> m = new TreeMap<>();
        String q = "SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=" + office
                + " AND " + SyncStore.upTo("entry_id", r.cutoffEntry, r.cutoffMarks) + " GROUP BY currency";
        try (Cursor c = getReadableDatabase().rawQuery(q, null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getLong(1));
        }
        return m;
    }

    /** الرصيد لكل عملة حتى قيد معيّن (شاملًا). */
    public Map<String, Long> balanceUpTo(long office, long cutoffEntry) {
        Map<String, Long> m = new TreeMap<>();
        String q = "SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=" + office
                + " AND entry_id<=" + cutoffEntry + " GROUP BY currency";
        try (Cursor c = getReadableDatabase().rawQuery(q, null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getLong(1));
        }
        return m;
    }

    private List<Model.Line> readLines(String extraWhere, long office) {
        List<Model.Line> out = new ArrayList<>();
        String q = "SELECT l.id,l.entry_id,COALESCE(e.kind,''),COALESCE(e.created_at,0),l.currency,l.debit_minor,l.credit_minor,e.tx_id,"
                + "t.uuid,t.type,t.person,t.description,t.status,t.fee_type,t.notes,t.special"
                + " FROM journal_lines l JOIN journal_entries e ON e.id=l.entry_id LEFT JOIN tx t ON t.id=e.tx_id"
                + " WHERE l.account='OFFICE' AND l.office_id=" + office
                + extraWhere
                + " ORDER BY COALESCE(e.created_at,0),l.id";
        try (Cursor c = getReadableDatabase().rawQuery(q, null)) {
            while (c.moveToNext()) {
                Model.Line l = new Model.Line();
                l.lineId = c.getLong(0);
                l.entryId = c.getLong(1);
                l.kind = c.getString(2);
                l.createdAt = c.getLong(3);
                l.currency = c.getString(4);
                l.debit = c.getLong(5);
                l.credit = c.getLong(6);
                l.txId = c.isNull(7) ? null : c.getLong(7);
                l.txUuid = c.getString(8);
                l.txType = c.getString(9);
                l.person = Model.nz(Crypto.dec(c.getString(10)));
                l.description = Model.nz(Crypto.dec(c.getString(11)));
                l.status = c.isNull(7) ? "ACTIVE" : c.getString(12);
                l.feeType = c.getString(13);
                l.notes = Model.nz(Crypto.dec(c.getString(14)));
                l.special = c.isNull(15) ? "" : Model.nz(c.getString(15));
                out.add(l);
            }
        }
        return out;
    }

    /** الرصيد قبل تاريخ لكل عملة. excludeCancelled يستبعد الحركات الملغاة وعكسها (صافيها صفر). */
    public Map<String, Long> balanceBefore(long office, long before, boolean excludeCancelled) {
        Map<String, Long> m = new TreeMap<>();
        String q = "SELECT l.currency,SUM(l.debit_minor-l.credit_minor) FROM journal_lines l JOIN journal_entries e ON e.id=l.entry_id"
                + " LEFT JOIN tx t ON t.id=e.tx_id WHERE l.account='OFFICE' AND l.office_id=" + office
                + " AND COALESCE(e.created_at,0)<" + before
                + (excludeCancelled ? " AND (e.tx_id IS NULL OR t.status='ACTIVE')" : "")
                + " GROUP BY l.currency";
        try (Cursor c = getReadableDatabase().rawQuery(q, null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getLong(1));
        }
        return m;
    }

    // ===================================================================================
    // أسعار الصرف
    // ===================================================================================

    public static class Rate {
        public BigDecimal usd;
        public String source;
        public long at;
        public BigDecimal online;
        public long onlineAt;
        public BigDecimal manual;
        public long manualAt;
    }

    public Map<String, Rate> rates() {
        Map<String, Rate> m = new TreeMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,usd_rate,updated_at,manual_rate,manual_at FROM exchange_rates", null)) {
            while (c.moveToNext()) {
                Rate r = new Rate();
                r.online = parseRate(c.getString(1));
                r.onlineAt = c.isNull(2) ? 0 : c.getLong(2);
                r.manual = parseRate(c.getString(3));
                r.manualAt = c.isNull(4) ? 0 : c.getLong(4);
                if (r.manual != null) {
                    r.usd = r.manual;
                    r.source = "يدوي";
                    r.at = r.manualAt;
                } else if (r.online != null) {
                    r.usd = r.online;
                    r.source = "أونلاين";
                    r.at = r.onlineAt;
                } else continue;
                m.put(c.getString(0), r);
            }
        }
        Rate usd = new Rate();
        usd.usd = BigDecimal.ONE;
        usd.source = "ثابت";
        m.put("USD", usd);
        return m;
    }

    public Map<String, BigDecimal> usdRates() {
        refreshMetals(getReadableDatabase());
        Map<String, BigDecimal> m = new HashMap<>();
        for (Map.Entry<String, Rate> e : rates().entrySet()) {
            // 12.5: معدن «بالوزن فقط» لا يدخل التقويم بالدولار ولا يُعدّ «بلا سعر»
            if (Metals.notValued(e.getKey())) continue;
            m.put(e.getKey(), e.getValue().usd);
        }
        return m;
    }

    private static BigDecimal parseRate(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            BigDecimal b = new BigDecimal(s.trim());
            return b.signum() > 0 ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 12.6: مصدر الأسعار الأونلاين (فارغ = الافتراضي) بصلاحية «تحديث الأسعار من الإنترنت». */
    public void setRatesUrl(String url) {
        require(Auth.Perm.RATES_ONLINE);
        setSetting("rates_url", Model.nz(url).trim());
    }

    public void saveOnlineRates(Map<String, BigDecimal> usdPerUnit0, long at) {
        require(Auth.Perm.RATES_ONLINE);   // 12.6
        // 12.5: سعر الأونصة الصافية (XAU، XAG، XPT، XPD) إن أعطاه المصدر ← سعر وحدة كل حساب معدن بعياره، للاستئناس
        Map<String, BigDecimal> usdPerUnit = new HashMap<>(usdPerUnit0);
        refreshMetals(getReadableDatabase());
        for (Metals.Metal m : metals()) {
            BigDecimal oz = usdPerUnit0.get(m.base);
            if (oz != null && !m.code.equals(m.base)) usdPerUnit.put(m.code, Metals.usdPerUnitFromOunce(oz, m));
            else if (oz != null) usdPerUnit.put(m.code, Metals.usdPerUnitFromOunce(oz, m));
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            for (Map.Entry<String, BigDecimal> e : usdPerUnit.entrySet()) {
                ContentValues v = new ContentValues();
                v.put("usd_rate", e.getValue().toPlainString());
                v.put("updated_at", at);
                if (d.update("exchange_rates", v, "code=?", new String[]{e.getKey()}) == 0) {
                    v.put("code", e.getKey());
                    d.insertOrThrow("exchange_rates", null, v);
                }
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    public void setManualRate(String code, BigDecimal usdPerUnit) {
        require(Auth.Perm.RATES);   // 12.0
        SQLiteDatabase d = getWritableDatabase();
        ContentValues v = new ContentValues();
        if (usdPerUnit == null) {
            v.putNull("manual_rate");
            v.putNull("manual_at");
        } else {
            v.put("manual_rate", usdPerUnit.toPlainString());
            v.put("manual_at", Clock.now());
        }
        if (d.update("exchange_rates", v, "code=?", new String[]{code}) == 0 && usdPerUnit != null) {
            v.put("code", code);
            v.put("usd_rate", "0");
            v.put("updated_at", 0);
            d.insertOrThrow("exchange_rates", null, v);
        }
        audit("MANUAL_RATE", "currency", 0, code + "=" + (usdPerUnit == null ? "—" : usdPerUnit.toPlainString()));
    }

    // ===================================================================================
    // المستخدمون
    // ===================================================================================

    public List<Model.User> users(boolean onlyActive) {
        List<Model.User> a = new ArrayList<>();
        // 10.8: الحسابات المحذوفة لا تظهر في القوائم (وتبقى لأسماء التاريخ عبر user(id))
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,role,active,perms,deleted_at,nick FROM users WHERE deleted_at IS NULL" + (onlyActive ? " AND active=1" : "") + " ORDER BY id", null)) {
            while (c.moveToNext()) a.add(readUser(c));
        }
        return a;
    }

    private static Model.User readUser(Cursor c) {
        Model.User u = new Model.User();
        u.id = c.getLong(0);
        u.name = c.getString(1);
        u.role = c.getString(2);
        u.active = c.getInt(3) == 1;
        u.perms = c.isNull(4) ? null : c.getString(4);
        u.deleted = c.getColumnCount() > 5 && !c.isNull(5);
        u.nick = c.getColumnCount() > 6 && !c.isNull(6) ? c.getString(6) : "";
        return u;
    }

    // ===================================================================================
    // 10.17: «الاسم الظاهر» — المحاسبون يرون الاسم الذي اختاره زميلهم لا اسم دخوله؛ المدير يرى الاسم الحقيقي
    // ===================================================================================

    private volatile Map<String, String> nickCache;

    void invalidateNicks() { nickCache = null; }

    private Map<String, String> nicks() {
        Map<String, String> m = nickCache;
        if (m != null) return m;
        m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT name,nick FROM users WHERE nick IS NOT NULL AND nick<>''", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getString(1));
        } catch (Exception ignored) {
            // قاعدة قبل الترحيل: بلا أسماء ظاهرة
        }
        nickCache = m;
        return m;
    }

    /**
     * اسم مستخدم كما يُعرض للمستخدم الحالي: المدير (وبلا نظام مستخدمين، وعمليات النظام) يرى اسم الدخول الحقيقي، وصاحب
     * الاسم يرى اسمه، وغيرهما يرى «الاسم الظاهر» إن اختير. يُطبَّق عند قراءة الحركات والتحققات والمطابقات وحركات الصندوق،
     * فيشمل كل الشاشات والتقارير والتصدير.
     */
    public String shown(String real) {
        if (real == null || real.isEmpty()) return "";
        Map<String, String> m = nicks();
        if (m.isEmpty() || isSystem()) return real;
        Model.User me = SmartApp.currentUser;
        if (me == null || Auth.isAdmin(me) || real.equals(me.name)) return real;
        String n = m.get(real);
        return n == null || n.isEmpty() ? real : n;
    }

    /** الاسم الظاهر لمستخدم: يضعه صاحبه لنفسه، أو من يملك إدارة المستخدمين (وحساب المدير يعدّله مدير). فارغ = يُلغى. */
    public void setNick(long id, String nick) {
        Model.User target = user(id), me = SmartApp.currentUser;
        if (target == null) throw new IllegalArgumentException("المستخدم غير موجود");
        if (me == null || me.id != id) {
            require(Auth.Perm.USERS);
            if (Auth.isAdmin(target)) requireAdmin("حساب المدير يعدّله مدير فقط.");
        }
        String n = nick == null ? "" : nick.trim();
        if (n.length() > 30) throw new IllegalArgumentException("الاسم الظاهر طويل (30 حرفًا على الأكثر)");
        if (!n.isEmpty()) for (Model.User u : users(false)) {
            if (u.id == id) continue;
            if (Sanity.norm(u.name).equals(Sanity.norm(n)) || Sanity.norm(Model.nz(u.nick)).equals(Sanity.norm(n)))
                throw new IllegalArgumentException("هذا الاسم يستعمله مستخدم آخر. اختر اسمًا ظاهرًا مختلفًا.");
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            if (n.isEmpty()) v.putNull("nick");
            else v.put("nick", n);
            d.update("users", v, "id=?", new String[]{"" + id});
            audit(d, "USER_NICK", "users", id, n.isEmpty() ? "أُلغي الاسم الظاهر" : "الاسم الظاهر: " + n);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        invalidateNicks();
        if (me != null && me.id == id) me.nick = n;
    }

    // ===================================================================================
    // 10.17: رسائل المدير إلى المحاسبين (للكل أو لواحد): تنتقل بالمزامنة وتظهر إشعارًا وفي الرئيسية حتى «قرأتها»
    // ===================================================================================

    public static final class AdminMsg {
        public String uuid, from = "", body = "", toName = "";
        public long at;
        public Long toUser, fromUser;
        public boolean deleted;
    }

    private AdminMsg readMsg(Cursor c) {
        AdminMsg m = new AdminMsg();
        m.uuid = c.getString(0);
        m.at = c.getLong(1);
        m.fromUser = c.isNull(2) ? null : c.getLong(2);
        m.from = Model.nz(Crypto.dec(c.getString(3)));
        m.toUser = c.isNull(4) ? null : c.getLong(4);
        m.body = Model.nz(Crypto.dec(c.getString(5)));
        m.deleted = c.getInt(6) == 1;
        return m;
    }

    /** يرسل المدير رسالة: toUser = null للكل، أو رقم محاسب واحد. */
    public String sendAdminMsg(Long toUser, String body) {
        if (!usersEnabled()) throw new IllegalStateException("الرسائل تعمل مع نظام المستخدمين. فعّله من «المستخدمون» أولًا.");
        requireAdmin("إرسال رسالة للمحاسبين للمدير وحده.");
        String b = body == null ? "" : body.trim();
        if (b.isEmpty()) throw new IllegalArgumentException("اكتب نص الرسالة");
        if (b.length() > 500) throw new IllegalArgumentException("الرسالة طويلة (500 حرف على الأكثر)");
        if (toUser != null) {
            Model.User t = user(toUser);
            if (t == null || t.deleted || !t.active) throw new IllegalArgumentException("المستخدم غير موجود أو موقوف");
        }
        Model.User me = SmartApp.currentUser;
        String uuid = java.util.UUID.randomUUID().toString();
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("uuid", uuid);
            v.put("created_at", Clock.now());
            if (me != null) v.put("from_user", me.id);
            v.put("from_name", Crypto.enc(me == null ? "المدير" : me.name));
            if (toUser != null) v.put("to_user", toUser);
            v.put("body", Crypto.enc(b));
            v.put("deleted", 0);
            d.insertOrThrow("admin_msgs", null, v);
            audit(d, "ADMIN_MSG", "admin_msgs", 0, (toUser == null ? "للكل" : "إلى " + user(toUser).name) + ": " + (b.length() > 80 ? b.substring(0, 80) + "…" : b));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return uuid;
    }

    /** رسائل لم يقرأها المستخدم الحالي بعد (له أو للكل، وليست منه). */
    public List<AdminMsg> unreadAdminMsgs() { return unreadAdminMsgs(SmartApp.currentUser); }

    public List<AdminMsg> unreadAdminMsgs(Model.User me) {
        List<AdminMsg> out = new ArrayList<>();
        if (me == null || !usersEnabled()) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT m.uuid,m.created_at,m.from_user,m.from_name,m.to_user,m.body,m.deleted FROM admin_msgs m "
                + "WHERE m.deleted=0 AND (m.to_user IS NULL OR m.to_user=?) AND (m.from_user IS NULL OR m.from_user<>?) "
                + "AND NOT EXISTS(SELECT 1 FROM admin_msg_state s WHERE s.uuid=m.uuid AND s.user_id=? AND s.read_at IS NOT NULL) ORDER BY m.created_at",
                new String[]{"" + me.id, "" + me.id, "" + me.id})) {
            while (c.moveToNext()) out.add(readMsg(c));
        }
        return out;
    }

    /** غير المقروءة التي لم يُشعَر بها هذا المستخدم على هذا الجهاز بعد؛ تُعلَّم «أُشعر بها» فلا يتكرر الإشعار. */
    public List<AdminMsg> adminMsgsToNotify() { return adminMsgsToNotify(SmartApp.currentUser); }

    /** 11.5: لمستخدم محدد (في الخلفية: آخر من دخل على هذا الجهاز). */
    public List<AdminMsg> adminMsgsToNotify(Model.User me) {
        List<AdminMsg> out = new ArrayList<>();
        if (me == null) return out;
        SQLiteDatabase d = getWritableDatabase();
        for (AdminMsg m : unreadAdminMsgs(me)) {
            try (Cursor c = d.rawQuery("SELECT notified FROM admin_msg_state WHERE uuid=? AND user_id=?", new String[]{m.uuid, "" + me.id})) {
                if (c.moveToFirst() && c.getInt(0) == 1) continue;
            }
            d.execSQL("INSERT OR REPLACE INTO admin_msg_state(uuid,user_id,notified,read_at) VALUES(?,?,1,NULL)", new Object[]{m.uuid, me.id});
            out.add(m);
        }
        return out;
    }

    public void markAdminMsgRead(String uuid) {
        Model.User me = SmartApp.currentUser;
        if (me == null) return;
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO admin_msg_state(uuid,user_id,notified,read_at) VALUES(?,?,1,?)", new Object[]{uuid, me.id, Clock.now()});
    }

    /** ما أرسله المدير (الأحدث أولًا) لشاشة الإرسال. */
    public List<AdminMsg> adminMsgsSent(int limit) {
        requireAdmin("رسائل المدير للمدير وحده.");
        List<AdminMsg> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uuid,created_at,from_user,from_name,to_user,body,deleted FROM admin_msgs WHERE deleted=0 ORDER BY created_at DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                AdminMsg m = readMsg(c);
                if (m.toUser != null) {
                    Model.User u = user(m.toUser);
                    m.toName = u == null ? "مستخدم محذوف" : u.name;
                }
                out.add(m);
            }
        }
        return out;
    }

    /** سحب رسالة: تختفي عند من لم يقرأها بعد (بعد المزامنة). */
    public void deleteAdminMsg(String uuid) {
        requireAdmin("سحب الرسالة للمدير وحده.");
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        getWritableDatabase().update("admin_msgs", v, "uuid=?", new String[]{uuid});
    }

    // ===================================================================================
    // 10.18: الأمانات والمبالغ المجمّدة
    //   TRUST  = أمانة منفصلة عن الرصيد (لا تدخل في القيود): side=OURS «أمانة لنا عند المكتب»، THEIRS «أمانة للمكتب عندنا».
    //   FREEZE = تجميد جزء من الرصيد القائم: الرصيد كما هو، ويظهر «منه مجمّد» و«المتاح». side=OURS من «لنا»، THEIRS من «علينا».
    // ===================================================================================

    public static final String HOLD_TRUST = "TRUST", HOLD_FREEZE = "FREEZE", SIDE_OURS = "OURS", SIDE_THEIRS = "THEIRS";

    public static final class Hold {
        public String uuid, currency, kind, side, note = "", by = "", releasedBy = "";
        /** 12.4: مرجع الحركة التي حُوّلت إليها (فارغ = فُكّت بلا حركة أو قائمة). */
        public String toTx = "";
        public long officeId, amount, at;
        public Long releasedAt;

        public boolean open() { return releasedAt == null; }

        /** وصف قصير: «أمانة للمكتب عندنا» / «مجمّد من رصيدنا»… */
        public String label() {
            boolean ours = SIDE_OURS.equals(side);
            return HOLD_TRUST.equals(kind) ? (ours ? "أمانة لنا عند المكتب" : "أمانة للمكتب عندنا") : (ours ? "مجمّد من «لنا»" : "مجمّد من «علينا»");
        }
    }

    private Hold readHold(Cursor c) {
        Hold h = new Hold();
        h.uuid = c.getString(0);
        h.officeId = c.getLong(1);
        h.currency = c.getString(2);
        h.amount = c.getLong(3);
        h.kind = c.getString(4);
        h.side = c.getString(5);
        h.note = Model.nz(Crypto.dec(c.getString(6)));
        h.at = c.getLong(7);
        h.by = shown(Model.nz(Crypto.dec(c.getString(8))));
        h.releasedAt = c.isNull(9) ? null : c.getLong(9);
        h.releasedBy = shown(Model.nz(Crypto.dec(c.getString(10))));
        h.toTx = c.getColumnCount() > 11 && !c.isNull(11) ? c.getString(11) : "";
        return h;
    }

    /** يسجّل أمانة أو تجميدًا. التجميد لا يتجاوز الرصيد القائم في اتجاهه (مع ما هو مجمّد قبله). */
    public String addHold(long officeId, String currency, String kind, String side, long amountMinor, String note) {
        require(Auth.Perm.HOLDS);   // 12.0
        if (office(officeId) == null) throw new IllegalArgumentException("المكتب غير موجود");
        if (!HOLD_TRUST.equals(kind) && !HOLD_FREEZE.equals(kind)) throw new IllegalArgumentException("نوع غير معروف");
        if (!SIDE_OURS.equals(side) && !SIDE_THEIRS.equals(side)) throw new IllegalArgumentException("اتجاه غير معروف");
        if (amountMinor <= 0) throw new IllegalArgumentException("اكتب مبلغًا أكبر من صفر");
        if (currency == null || currency.trim().isEmpty()) throw new IllegalArgumentException("اختر العملة");
        if (HOLD_FREEZE.equals(kind)) {
            Long bal = balances(officeId).get(currency);
            long b = bal == null ? 0 : bal, have = SIDE_OURS.equals(side) ? Math.max(0, b) : Math.max(0, -b), frozen = 0;
            for (Hold h : holds(officeId, true)) if (HOLD_FREEZE.equals(h.kind) && h.currency.equals(currency) && h.side.equals(side)) frozen += h.amount;
            int dc = decimals(currency);
            if (amountMinor + frozen > have) throw new IllegalStateException("لا يمكن تجميد " + Model.fmt(amountMinor, dc) + " " + currency + ": الرصيد "
                    + (SIDE_OURS.equals(side) ? "«لنا» " : "«علينا» ") + Model.fmt(have, dc) + (frozen > 0 ? " ومنه مجمّد " + Model.fmt(frozen, dc) : "")
                    + ". للمبلغ غير الداخل في الرصيد اختر «أمانة منفصلة».");
        }
        String uuid = UUID.randomUUID().toString();
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("uuid", uuid);
            v.put("office_id", officeId);
            v.put("currency", currency);
            v.put("amount_minor", amountMinor);
            v.put("kind", kind);
            v.put("side", side);
            v.put("note", Crypto.enc(note == null ? "" : note.trim()));
            v.put("created_at", Clock.now());
            v.put("user_name", Crypto.enc(currentUserName));
            d.insertOrThrow("office_holds", null, v);
            audit(d, "HOLD_ADD", "office", officeId, (HOLD_TRUST.equals(kind) ? "أمانة " : "تجميد ") + Model.fmt(amountMinor, decimals(currency)) + " " + currency);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return uuid;
    }

    /** 12.4: يفكّ الأمانة داخل معاملة تسجيل حركتها، مربوطةً بها (to_tx). */
    private void releaseHoldTo(SQLiteDatabase d, String uuid, long txId, TxInput in) {
        long office;
        String cur;
        long amount;
        try (Cursor c = d.rawQuery("SELECT office_id,released_at,currency,amount_minor FROM office_holds WHERE uuid=?", new String[]{uuid})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("الأمانة غير موجودة");
            if (!c.isNull(1)) throw new IllegalStateException("فُكّت هذه الأمانة من قبل");
            office = c.getLong(0);
            cur = c.getString(2);
            amount = c.getLong(3);
        }
        if (office != in.office) throw new IllegalArgumentException("الحركة لمكتب غير مكتب الأمانة");
        String txUuid = null;
        try (Cursor c = d.rawQuery("SELECT uuid FROM tx WHERE id=" + txId, null)) {
            if (c.moveToFirst()) txUuid = c.getString(0);
        }
        ContentValues v = new ContentValues();
        v.put("released_at", Clock.now());
        v.put("released_by", Crypto.enc(currentUserName));
        v.put("to_tx", txUuid);
        d.update("office_holds", v, "uuid=?", new String[]{uuid});
        audit(d, "HOLD_TO_TX", "office", office, "حُوّلت إلى حركة " + txId + ": " + Model.fmt(amount, decimals(cur)) + " " + cur
                + (amount != in.amount || !cur.equals(in.currency) ? " (الحركة " + Model.fmt(in.amount, decimals(in.currency)) + " " + in.currency + ")" : ""));
    }

    // ===================================================================================
    // 12.4: الحركات الخاصة
    // ===================================================================================

    /** الأنواع الافتراضية للحركات الخاصة؛ المحاسب يضيف غيرها (إعداد متزامن special_types). */
    public static final String[] SPECIAL_DEFAULTS = {"ناقصة أجور", "تحتاج مراجعة", "بانتظار التنفيذ", "عليها مشاكل"};

    public List<String> specialTypes() {
        List<String> out = new ArrayList<>();
        String raw = setting("special_types", "");
        if (raw.trim().isEmpty()) out.addAll(Arrays.asList(SPECIAL_DEFAULTS));
        else for (String x : raw.split("\n")) if (!x.trim().isEmpty() && !out.contains(x.trim())) out.add(x.trim());
        return out;
    }

    /** يضيف نوعًا (أو يحذفه remove=true) لقائمة أنواع الحركات الخاصة. */
    public void editSpecialType(String name, boolean remove) {
        require(Auth.Perm.SPECIAL);
        String n = Model.nz(name).trim().replace('\n', ' ');
        if (n.isEmpty()) throw new IllegalArgumentException("اكتب اسم النوع");
        if (n.length() > 40) throw new IllegalArgumentException("الاسم طويل (40 حرفًا على الأكثر)");
        List<String> l = specialTypes();
        if (remove) l.remove(n);
        else if (!l.contains(n)) l.add(n);
        else throw new IllegalArgumentException("النوع «" + n + "» موجود");
        if (l.isEmpty()) throw new IllegalArgumentException("يبقى نوع واحد على الأقل");
        setSettingQuiet("special_types", String.join("\n", l));
        audit("SPECIAL_TYPES", "settings", 0, (remove ? "حذف: " : "إضافة: ") + n);
    }

    /** الحركات الخاصة القائمة لمكتب (null = كل المكاتب): غير المحسوبة، والمحسوبة المعلّقة، والمنتظرة الاعتماد. الأحدث أولًا. */
    public List<Model.Tx> specials(Long officeId) {
        List<Model.Tx> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + TX_COLS + " FROM tx WHERE special IS NOT NULL AND special<>''"
                + " AND status IN ('" + Model.STATUS_SPECIAL + "','ACTIVE','" + Model.STATUS_PENDING + "')"
                + (officeId == null ? "" : " AND office_id=" + officeId.longValue()) + " ORDER BY created_at DESC,id DESC", null)) {
            while (c.moveToNext()) out.add(readTx(c));
        }
        return out;
    }

    /** نقل الصور الباقية من الحركة القديمة إلى الجديدة. */
    private void moveAttachments(SQLiteDatabase d, long oldId, long id, Set<Long> removed) {
        StringBuilder keep = new StringBuilder("UPDATE attachments SET tx_id=" + id + " WHERE tx_id=" + oldId);
        if (removed != null && !removed.isEmpty()) {
            keep.append(" AND id NOT IN (");
            boolean first = true;
            for (Long x : removed) {
                if (!first) keep.append(',');
                keep.append(x.longValue());
                first = false;
            }
            keep.append(')');
        }
        d.execSQL(keep.toString());
    }

    /**
     * 12.4: تعديل حركة خاصة أو تحويلها إلى عادية (in.special فارغ)، أو تعليم حركة عادية كخاصة عند تعديلها.
     * <ul>
     * <li>الخاصة غير المحسوبة (بلا قيود): تصير CONVERTED وتُسجَّل الجديدة مرتبطة بها (replaces_id)، فلا قيد عكسي لشيء لم يُرحَّل.</li>
     * <li>المحسوبة في الرصيد إن لم يتغير فيها إلا رفع العلامة (نفس المبالغ والأجرة والتاريخ): تُرفع العلامة وحدها، فلا يتغير الكشف.</li>
     * <li>غير ذلك: قيد عكسي وحركة مصححة كالتعديل العادي.</li>
     * </ul>
     */
    public long replaceSpecial(long oldId, TxInput in, List<byte[]> newImages, Set<Long> removedAttachments, String reason) throws IOException {
        require(Auth.Perm.SPECIAL);
        Model.Tx before = tx(oldId);
        if (before == null) throw new IllegalArgumentException("الحركة غير موجودة");
        if (before.isLinked()) throw new IllegalStateException("ساق من عملية مترابطة لا تُعلَّم خاصة ولا تُحوَّل وحدها");
        if (before.isPending()) throw new IllegalStateException("الحركة بانتظار الاعتماد: اعتمدها أو ارفضها أولًا");
        if (in.when > 0 && in.when < Model.dayStart(Clock.now()) && in.when != before.createdAt) require(Auth.Perm.BACKDATE);
        validate(in);
        boolean toNormal = !special(in);
        List<File> written = writeImages(newImages);
        SQLiteDatabase d = getWritableDatabase();
        long now = Clock.now();
        d.beginTransaction();
        try {
            long id;
            String how;
            if (before.isUnposted()) {
                checkLock(d, in.when, "تاريخ الحركة يقع داخل فترة محاسبية مقفلة");
                ContentValues st = new ContentValues();
                st.put("status", Model.STATUS_CONVERTED);
                d.update("tx", st, "id=" + oldId + " AND status='" + Model.STATUS_SPECIAL + "'", null);
                id = insertTx(d, in, oldId);
                moveAttachments(d, oldId, id, removedAttachments);
                linkImages(d, id, written, now);
                how = toNormal ? "حُوّلت الخاصة (غير المحسوبة) إلى حركة عادية" : "عُدّلت الخاصة";
            } else if (before.isActive() && (toNormal || in.specialBal) && !keyChanged(before, in) && in.when == before.createdAt && !feeChanged(before, in)
                    && written.isEmpty() && (removedAttachments == null || removedAttachments.isEmpty()) && sameText(before, in)) {
                // المبالغ والتاريخ كما هي والحركة باقية في الرصيد: تتغير العلامة وحدها (لا قيد عكسي ولا حركة جديدة)
                checkLock(d, before.createdAt, "الحركة ضمن فترة محاسبية مقفلة");
                ContentValues st = new ContentValues();
                if (toNormal) {
                    st.putNull("special");
                    st.putNull("special_bal");
                } else {
                    st.put("special", in.special.trim());
                    st.put("special_bal", 1);
                }
                d.update("tx", st, "id=" + oldId, null);
                id = oldId;
                how = toNormal ? "رُفعت علامة «" + before.special + "» (الحركة كما هي في الرصيد والكشف)"
                        : before.isSpecial() ? "تغيّر النوع إلى «" + in.special.trim() + "»" : "عُلّمت حركة خاصة (كما هي في الرصيد والكشف)";
            } else {
                if (!before.isActive()) throw new IllegalStateException("هذه الحركة ملغاة أو معدّلة");
                reverseTx(d, oldId, now);
                checkLock(d, in.when, "التاريخ الجديد يقع داخل فترة محاسبية مقفلة");
                id = insertTx(d, in, oldId);
                moveAttachments(d, oldId, id, removedAttachments);
                linkImages(d, id, written, now);
                String cash = relinkCash(d, oldId, id, in);
                if (keyChanged(before, in)) voidChecks(d, oldId, "عُدّلت الحركة: " + keyDiff(before, in));
                else carryChecks(d, oldId, id);
                how = (toNormal ? "حُوّلت إلى حركة عادية" : before.isSpecial() ? "عُدّلت الخاصة" : "عُلّمت حركة خاصة") + (cash == null ? "" : " — " + cash);
            }
            audit(d, toNormal ? "CONVERT_SPECIAL" : "EDIT_SPECIAL", "tx", oldId, "new=" + id + " — " + how
                    + (toNormal ? "" : " — " + in.special.trim() + (in.specialBal ? " (تُحسب في الرصيد)" : " (لا تُحسب)"))
                    + (reason == null || reason.isEmpty() ? "" : " — " + reason));
            d.setTransactionSuccessful();
            written.clear();
            return id;
        } finally {
            d.endTransaction();
            for (File f : written) f.delete();
        }
    }

    private static boolean feeChanged(Model.Tx t, TxInput in) {
        return !Model.nz(t.feeType).equals(Model.nz(in.feeType)) || t.fee != in.fee || !Model.nz(t.feeCurrency).equals(Model.nz(in.feeCurrency));
    }

    private static boolean sameText(Model.Tx t, TxInput in) {
        return Model.nz(t.person).equals(Model.nz(in.person)) && Model.nz(t.description).equals(Model.nz(in.description))
                && Model.nz(t.notes).equals(Model.nz(in.notes)) && Model.nz(t.service).equals(Model.nz(in.service)) && Model.nz(t.dest).equals(Model.nz(in.dest));
    }

    /** 12.4: إلغاء حركة خاصة غير محسوبة (لا قيد لها ليُعكس). المحسوبة تُلغى كأي حركة (قيد عكسي). */
    public void voidSpecial(long id, String reason) {
        require(Auth.Perm.SPECIAL);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("status", Model.STATUS_VOIDED);
            int n = d.update("tx", v, "id=" + id + " AND status='" + Model.STATUS_SPECIAL + "'", null);
            if (n == 0) throw new IllegalStateException("ليست حركة خاصة غير محسوبة");
            audit(d, "VOID_SPECIAL", "tx", id, Model.nz(reason));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** فكّ الأمانة أو التجميد (يبقى في السجل بتاريخ الفك ومن فكّه). */
    public void releaseHold(String uuid) {
        require(Auth.Perm.HOLDS);   // 12.0
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            long office = 0;
            try (Cursor c = d.rawQuery("SELECT office_id,released_at FROM office_holds WHERE uuid=?", new String[]{uuid})) {
                if (!c.moveToFirst()) throw new IllegalArgumentException("غير موجود");
                if (!c.isNull(1)) throw new IllegalStateException("فُكّ من قبل");
                office = c.getLong(0);
            }
            ContentValues v = new ContentValues();
            v.put("released_at", Clock.now());
            v.put("released_by", Crypto.enc(currentUserName));
            d.update("office_holds", v, "uuid=?", new String[]{uuid});
            audit(d, "HOLD_RELEASE", "office", office, "");
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** أمانات ومجمّدات مكتب (officeId = null لكل المكاتب)، الأحدث أولًا. */
    public List<Hold> holds(Long officeId, boolean openOnly) {
        List<Hold> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uuid,office_id,currency,amount_minor,kind,side,note,created_at,user_name,released_at,released_by,to_tx FROM office_holds WHERE 1=1"
                + (officeId == null ? "" : " AND office_id=" + officeId.longValue()) + (openOnly ? " AND released_at IS NULL" : "") + " ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) out.add(readHold(c));
        }
        return out;
    }

    /** 10.18: يضيف إلى تقرير الأرصدة قسم «الأمانات والمبالغ المجمّدة القائمة» لكل المكاتب (إن وُجدت)، فيظهر على الشاشة وفي التصدير. */
    public Report withHolds(Report r) {
        List<Hold> open = holds(null, true);
        if (open.isEmpty()) return r;
        Map<Long, String> names = new HashMap<>();
        for (Model.Office o : offices()) names.put(o.id, o.name);
        Report.Section s = r.section("الأمانات والمبالغ المجمّدة القائمة", "الأمانات");
        s.note = "الأمانة المنفصلة خارج الرصيد. المجمّد جزء من الرصيد معلَّم فلا يُحسب متاحًا.";
        s.col("المكتب", 2f, Report.TEXT).col("النوع", 1.6f, Report.TEXT).col("المبلغ", 1.3f, Report.MONEY).col("العملة", 0.8f, Report.TEXT).col("منذ", 1.2f, Report.TEXT)
                .col("أضافه", 1f, Report.TEXT).col("ملاحظة", 1.8f, Report.TEXT);
        for (Hold h : open)
            s.row(Report.NORMAL, Model.nz(names.get(h.officeId)), h.label(), new Report.Money(h.amount, decimals(h.currency)), h.currency, Model.day(h.at), h.by, h.note);
        return r;
    }

    /** لكل عملة: {أمانة لنا عندهم، أمانة لهم عندنا، مجمّد من لنا، مجمّد من علينا} للمفتوح فقط. */
    public Map<String, long[]> holdTotals(long officeId) {
        Map<String, long[]> m = new TreeMap<>();
        for (Hold h : holds(officeId, true)) {
            long[] x = m.get(h.currency);
            if (x == null) m.put(h.currency, x = new long[4]);
            x[(HOLD_TRUST.equals(h.kind) ? 0 : 2) + (SIDE_OURS.equals(h.side) ? 0 : 1)] += h.amount;
        }
        return m;
    }

    /** أسطر جاهزة للعرض تحت رصيد المكتب وفي رسالة المطابقة: «USD: منه مجمّد 500 • المتاح 1,500» و«أمانة للمكتب عندنا: 200 USD». */
    public List<String> holdLines(long officeId) {
        List<String> out = new ArrayList<>();
        Map<String, Long> bal = balances(officeId);
        for (Map.Entry<String, long[]> e : holdTotals(officeId).entrySet()) {
            int dc = decimals(e.getKey());
            long[] x = e.getValue();
            long b = bal.get(e.getKey()) == null ? 0 : bal.get(e.getKey());
            if (x[2] > 0) out.add(e.getKey() + ": من «لنا» مجمّد " + Model.fmt(x[2], dc) + " • المتاح " + Model.fmt(Math.max(0, b) - x[2], dc));
            if (x[3] > 0) out.add(e.getKey() + ": من «علينا» مجمّد " + Model.fmt(x[3], dc) + " • المتاح " + Model.fmt(Math.max(0, -b) - x[3], dc));
            if (x[0] > 0) out.add("أمانة لنا عند المكتب: " + Model.fmt(x[0], dc) + " " + e.getKey());
            if (x[1] > 0) out.add("أمانة للمكتب عندنا: " + Model.fmt(x[1], dc) + " " + e.getKey());
        }
        return out;
    }

    // ===================================================================================
    // 10.18: سجل الإشعارات (على هذا الجهاز): كل إشعار يُحفظ هنا فلا يضيع بالضغط عليه
    // ===================================================================================

    public void logNotif(String title, String body) {
        try {
            SQLiteDatabase d = getWritableDatabase();
            d.execSQL("INSERT INTO notif_log(at,title,body) VALUES(?,?,?)", new Object[]{Clock.now(), Model.nz(title), Crypto.enc(Model.nz(body))});
            d.execSQL("DELETE FROM notif_log WHERE id NOT IN (SELECT id FROM notif_log ORDER BY id DESC LIMIT 1000)");
        } catch (Throwable ignored) {
            // السجل للمراجعة فقط
        }
    }

    /** {الوقت، العنوان، النص}، الأحدث أولًا. */
    public List<String[]> notifLog(int limit) {
        require(Auth.Perm.NOTIFS);   // 11.6: تنبيهات حساسة
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at,title,body FROM notif_log ORDER BY id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(new String[]{Model.date(c.getLong(0)), Model.nz(c.getString(1)), Model.nz(Crypto.dec(c.getString(2)))});
        } catch (Exception ignored) {
            // قاعدة قبل الترحيل
        }
        return out;
    }

    public void clearNotifLog() { getWritableDatabase().execSQL("DELETE FROM notif_log"); }

    /** المستخدم بحالته الآن، أو null. */
    public Model.User user(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,role,active,perms,deleted_at,nick FROM users WHERE id=" + id, null)) {
            return c.moveToFirst() ? readUser(c) : null;
        }
    }

    /** 10.2: الدخول بكتابة الاسم (لا تُعرض قائمة الأسماء): المستخدم النشط بهذا الاسم، أو null. */
    public Model.User userByName(String name) {
        String n = Sanity.norm(name);
        if (n.isEmpty()) return null;
        for (Model.User u : users(true)) if (Sanity.norm(u.name).equals(n)) return u;
        return null;
    }

    /** بصمة رمز المستخدم الحالي: تتغير إن غُيّر رمزه (فيُلغى «تذكرني» على كل جهاز). */
    private String userPrint(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pin_salt,pin_hash FROM users WHERE id=" + id, null)) {
            if (!c.moveToFirst()) return "";
            return SyncCrypto.hex(SyncCrypto.sha256((Model.nz(c.getString(0)) + "|" + Model.nz(c.getString(1))).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 24);
        }
    }

    /** 10.2: «تذكرني على هذا الجهاز»: يُفتح التطبيق بهذا الحساب مباشرة دون كتابة الرمز. */
    public void rememberUser(long id) { setSettingQuiet("remember_user", id + "|" + userPrint(id)); }

    public void forgetUser() { deleteSetting("remember_user"); }

    /** الحساب المحفوظ على هذا الجهاز إن بقي نشطًا وبرمزه نفسه، وإلا null (ويُنسى). */
    public Model.User rememberedUser() {
        String r = setting("remember_user", "");
        int i = r.indexOf('|');
        if (i <= 0) return null;
        try {
            long id = Long.parseLong(r.substring(0, i));
            Model.User u = user(id);
            if (u != null && u.active && r.substring(i + 1).equals(userPrint(id))) return u;
        } catch (Exception ignored) {
            // يُنسى
        }
        forgetUser();
        return null;
    }

    /**
     * 10.1: مع المزامنة، نظام المستخدمين يُفعَّل ويُوقف من الجهاز الرئيسي وحده؛ وتصل الحسابات إلى بقية الأجهزة،
     * فلا يُنشئ كل جهاز مديرًا لنفسه.
     */
    public boolean syncFounder() { return SyncStore.founder(getReadableDatabase()); }

    public String founderBlock() {
        if (syncFounder()) return null;
        return "هذا الجهاز منضم إلى مزامنة. المستخدمون يُفعَّلون ويُوقفون من الجهاز الرئيسي فقط، وتصل حساباتهم إلى هنا تلقائيًا. "
                + "بعدها يدخل كل شخص هنا بحسابه الذي أنشأه له المدير.";
    }

    public boolean usersEnabled() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM users WHERE active=1", null)) {
            return c.moveToFirst() && c.getLong(0) > 0;
        }
    }

    public long addUser(String name, String role, String pin) {
        return addUser(name, role, Auth.ADMIN.equals(role) ? null : Auth.legacyPerms(role), pin);
    }

    /** 10.1: role = ADMIN أو CUSTOM مع قائمة الصلاحيات. */
    public long addUser(String name, String role, String perms, String pin) {
        require(Auth.Perm.USERS);
        if (!usersEnabled() && founderBlock() != null) throw new IllegalStateException(founderBlock());
        if (Auth.ADMIN.equals(role)) requireAdmin("إنشاء حساب مدير للمدير وحده.");
        else checkGrant(perms, null);
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("اكتب اسم المستخدم");
        checkNameFree(name, 0);
        if (!Pin.valid(pin)) throw new IllegalArgumentException("الرمز يجب أن يكون من 4 إلى 8 أرقام");
        if (Auth.ADMIN.equals(role) && !Pin.validAdmin(pin)) throw new IllegalArgumentException("رمز المدير يجب أن يكون من 6 إلى 8 أرقام");
        checkUserSlot();
        String salt = Pin.newSalt();
        ContentValues v = new ContentValues();
        v.put("name", name.trim());
        v.put("role", role);
        v.put("pin_salt", salt);
        v.put("pin_hash", Pin.hashV2(pin, salt, pinPepper()));
        v.put("active", 1);
        v.put("created_at", Clock.now());
        if (!Auth.ADMIN.equals(role)) v.put("perms", perms == null ? "" : perms);
        long id = ins(getWritableDatabase(), "users", v);
        audit("ADD_USER", "user", id, name.trim() + " / " + role);
        return id;
    }

    /**
     * 10.11 (AUDIT-016): الدخول بكتابة الاسم، فلا يتكرر اسم بين حسابين غير محذوفين (بعد التوحيد: الهمزات والمسافات…)؛
     * وإلا لا يستطيع صاحب الحساب الثاني الدخول أبدًا.
     */
    private void checkNameFree(String name, long exceptId) {
        String n = Sanity.norm(name);
        for (Model.User u : users(false)) {
            if (u.id != exceptId && Sanity.norm(u.name).equals(n))
                throw new IllegalArgumentException("يوجد مستخدم بالاسم «" + u.name + "». اختر اسمًا مختلفًا (الدخول يكون بالاسم).");
        }
    }

    /** المجاني: مستخدم واحد نشط (المالك بصفة مدير يحمي التطبيق برمزه). المستخدمون الموجودون لا يُوقفون أبدًا. */
    private void checkUserSlot() {
        long max = License.current.maxUsers();
        if (max == Entitlements.UNLIMITED) return;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM users WHERE active=1", null)) {
            if (c.moveToFirst() && c.getLong(0) >= max) {
                throw new Entitlements.LimitReached(Entitlements.userLimitMessage(max));
            }
        }
    }

    /**
     * 10.5 (AUDIT-023): من يملك «المستخدمون» دون أن يكون مديرًا لا يمنح صلاحية لا يملكها، ولا يعدّل صلاحياته هو
     * (ومنها رفع قيد «حركاته تحتاج اعتمادًا» عن نفسه). المدير بلا قيد. الحسابات الحالية لا تتغير صلاحياتها.
     */
    private void checkGrant(String perms, Model.User target) {
        Model.User me = actor();
        if (me == null || Auth.isAdmin(me) || isSystem()) return;
        java.util.Set<String> want = Auth.expand(Auth.parse(perms == null ? "" : perms)), mine = Auth.perms(me);
        if (target != null && target.id == me.id) {
            java.util.Set<String> a1 = new java.util.HashSet<>(want), b1 = new java.util.HashSet<>(Auth.perms(target));
            a1.removeIf(Auth::marker);
            b1.removeIf(Auth::marker);
            if (!a1.equals(b1)) throw new SecurityException("لا تعدّل صلاحياتك بنفسك؛ يعدّلها المدير.");
            return;
        }
        java.util.Set<String> before = target == null ? new java.util.HashSet<String>() : Auth.perms(target);
        // رفع قيد «حركاته تحتاج اعتمادًا» قرار اعتماد: للمدير أو لمن يملك «الاعتماد»
        if (before.contains(Auth.REVIEW) && !want.contains(Auth.REVIEW) && !mine.contains(Auth.Perm.APPROVE.name()))
            throw new SecurityException("رفع قيد «حركاته تحتاج اعتمادًا» للمدير أو لمن يملك «اعتماد الحركات».");
        for (String p : want) {
            if (Auth.marker(p) || before.contains(p) || mine.contains(p)) continue;
            throw new SecurityException("لا تمنح صلاحية لا تملكها أنت («" + permTitle(p) + "»)؛ يمنحها المدير.");
        }
    }

    private static String permTitle(String p) {
        try {
            return Auth.title(Auth.Perm.valueOf(p));
        } catch (Exception e) {
            return p;
        }
    }

    public void updateUser(long id, String name, String role, boolean active) {
        Model.User old = user(id);
        updateUser(id, name, role, Auth.ADMIN.equals(role) ? null : (old != null && role.equals(old.role) && old.perms != null ? old.perms : Auth.legacyPerms(role)), active);
    }

    /** 10.1: تعديل الاسم والصفة (مدير أو صلاحيات مخصّصة) والصلاحيات والحالة. */
    public void updateUser(long id, String name, String role, String perms, boolean active) {
        require(Auth.Perm.USERS);
        Model.User target = user(id);
        if (target == null) throw new IllegalArgumentException("المستخدم غير موجود");
        if (Auth.isAdmin(target)) requireAdmin("حساب المدير يعدّله مدير فقط.");
        if (Auth.ADMIN.equals(role)) requireAdmin("جعل مستخدم مديرًا للمدير وحده.");
        if (!Auth.ADMIN.equals(role)) checkGrant(perms, target);
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("اكتب اسم المستخدم");
        checkNameFree(name, id);
        if (active) {
            boolean was = false;
            try (Cursor c = getReadableDatabase().rawQuery("SELECT active FROM users WHERE id=" + id, null)) {
                if (c.moveToFirst()) was = c.getInt(0) == 1;
            }
            if (!was) checkUserSlot();
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("name", name.trim());
            v.put("role", role);
            if (Auth.ADMIN.equals(role)) v.putNull("perms");
            else v.put("perms", perms == null ? "" : perms);
            v.put("active", active ? 1 : 0);
            d.update("users", v, "id=" + id, null);
            long admins;
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM users WHERE active=1 AND role=?", new String[]{Auth.ADMIN})) {
                admins = c.moveToFirst() ? c.getLong(0) : 0;
            }
            long activeUsers;
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM users WHERE active=1", null)) {
                activeUsers = c.moveToFirst() ? c.getLong(0) : 0;
            }
            if (activeUsers > 0 && admins == 0) throw new IllegalStateException("يجب أن يبقى مدير واحد نشط على الأقل");
            audit(d, "UPDATE_USER", "user", id, name.trim() + " / " + role + (active ? "" : " / موقوف"));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /**
     * 10.8: حذف حساب مستخدم دون المساس بما سجّله: لا تُحذف حركاته ولا تحققاته ولا يتغير رصيد. يُغلق الحساب نهائيًا
     * (لا دخول: يُمسح رمزه ويُوقف)، ويُزال من المكاتب، ويختفي من القوائم؛ ويبقى اسمه في تفاصيل كل ما سجّله «(حساب محذوف)».
     * لا يحذف المستخدم حسابه وهو داخل به، وحساب المدير يحذفه مدير، ويبقى مدير نشط واحد على الأقل.
     */
    public void deleteUser(long id) {
        require(Auth.Perm.USERS);
        Model.User target = user(id);
        if (target == null) throw new IllegalArgumentException("المستخدم غير موجود");
        if (target.deleted) return;
        Model.User me = actor();
        if (me != null && me.id == id) throw new IllegalStateException("لا تحذف حسابك وأنت داخل به؛ يحذفه مستخدم آخر بصلاحية «المستخدمون».");
        if (Auth.isAdmin(target)) requireAdmin("حساب المدير يحذفه مدير فقط.");
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("active", 0);
            v.put("deleted_at", Clock.now());
            v.putNull("pin_hash");
            v.putNull("pin_salt");
            d.update("users", v, "id=" + id, null);
            d.delete("user_offices", "user_id=" + id, null);
            long admins, activeUsers;
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM users WHERE active=1 AND role=?", new String[]{Auth.ADMIN})) {
                admins = c.moveToFirst() ? c.getLong(0) : 0;
            }
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM users WHERE active=1", null)) {
                activeUsers = c.moveToFirst() ? c.getLong(0) : 0;
            }
            if (activeUsers > 0 && admins == 0) throw new IllegalStateException("يجب أن يبقى مدير واحد نشط على الأقل");
            audit(d, "DELETE_USER", "user", id, target.name + " — بقيت حركاته وتحققاته كما هي");
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    public void setPin(long id, String pin) {
        if (!recoveryGrant) {
            require(Auth.Perm.USERS);
            Model.User target = user(id);
            if (target != null && Auth.isAdmin(target)) requireAdmin("رمز المدير يغيّره المدير نفسه («تغيير رمزي السري») أو برمز الاسترداد، لا صلاحية «المستخدمون».");
            // 10.11 (AUDIT-001): من يعرف رمز حساب يملك صلاحياته؛ فلا يغيّر غير المدير رمز حساب فيه صلاحية لا يملكها هو
            Model.User me = actor();
            if (target != null && me != null && !Auth.isAdmin(me) && me.id != id) {
                java.util.Set<String> mine = Auth.perms(me);
                for (String p : Auth.perms(target)) {
                    if (!Auth.REVIEW.equals(p) && !mine.contains(p))
                        throw new SecurityException("لحساب «" + target.name + "» صلاحية لا تملكها («" + permTitle(p) + "»)؛ رمزه يغيّره المدير.");
                }
            }
        }
        storePin(id, pin);
    }

    /**
     * 10.5 (AUDIT-027): المستخدم يغيّر رمزه بنفسه بعد إدخال رمزه الحالي، بلا صلاحية «المستخدمون».
     * الرمز القديم يُتحقق منه هنا في طبقة البيانات (لا في الواجهة وحدها).
     */
    public void changeOwnPin(String oldPin, String newPin) {
        Model.User me = actor();
        if (me == null || !me.active) throw new SecurityException("سجّل الدخول أولًا.");
        if (!verifyPin(me.id, oldPin)) throw new SecurityException("الرمز الحالي غير صحيح.");
        storePin(me.id, newPin);
    }

    private void storePin(long id, String pin) {
        if (!Pin.valid(pin)) throw new IllegalArgumentException("الرمز يجب أن يكون من 4 إلى 8 أرقام");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT role FROM users WHERE id=" + id, null)) {
            if (c.moveToFirst() && Auth.ADMIN.equals(c.getString(0)) && !Pin.validAdmin(pin)) {
                throw new IllegalArgumentException("رمز المدير يجب أن يكون من 6 إلى 8 أرقام");
            }
        }
        String salt = Pin.newSalt();
        ContentValues v = new ContentValues();
        v.put("pin_salt", salt);
        v.put("pin_hash", Pin.hashV2(pin, salt, pinPepper()));
        getWritableDatabase().update("users", v, "id=" + id, null);
        audit("RESET_PIN", "user", id, "");
    }

    /** يتحقق من الرمز؛ وعند نجاح رمز بالصيغة القديمة يُعاد حفظه بالصيغة v2 في الدخول نفسه (بلا أي خطوة من المستخدم). */
    public boolean verifyPin(long id, String pin) {
        // 10.16: حد المحاولات في طبقة البيانات أيضًا (كان في الواجهة وحدها): بعد 5 أخطاء متتالية يُرفض التحقق مدة تتضاعف
        long[] st;
        synchronized (PIN_TRIES) {
            st = PIN_TRIES.get(id);
            if (st == null) PIN_TRIES.put(id, st = new long[2]);
            long wait = st[1] - System.nanoTime();
            if (st[0] >= 5 && wait > 0) throw new SecurityException("محاولات خاطئة كثيرة. انتظر " + Math.max(1, wait / 1_000_000_000L) + " ثانية ثم حاول.");
        }
        boolean ok = verifyPin0(id, pin);
        synchronized (PIN_TRIES) {
            if (ok) st[0] = 0;
            else {
                st[0]++;
                st[1] = System.nanoTime() + Pin.lockoutMillis((int) st[0]) * 1_000_000L;
            }
        }
        return ok;
    }

    /** محاولات الرمز الخاطئة المتتالية لكل مستخدم في هذه الجلسة: {العدد، متى يُسمح بالمحاولة التالية (nanoTime)}. */
    private static final Map<Long, long[]> PIN_TRIES = new HashMap<>();

    /** للاختبارات. */
    static void resetPinTries() {
        synchronized (PIN_TRIES) {
            PIN_TRIES.clear();
        }
    }

    private boolean verifyPin0(long id, String pin) {
        String salt, hash;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pin_salt,pin_hash FROM users WHERE active=1 AND id=" + id, null)) {
            if (!c.moveToFirst()) return false;
            salt = c.getString(0);
            hash = c.getString(1);
        }
        byte[] pepper = Pin.needsUpgrade(hash) ? null : pinPepper();
        if (!Pin.verifyAny(pin, salt, hash, pepper)) return false;
        if (Pin.needsUpgrade(hash)) {
            try {
                String ns = Pin.newSalt();
                ContentValues v = new ContentValues();
                v.put("pin_salt", ns);
                v.put("pin_hash", Pin.hashV2(pin, ns, pinPepper()));
                getWritableDatabase().update("users", v, "id=" + id, null);
            } catch (Exception ignored) {
                // يبقى بالصيغة القديمة ويُعاد في الدخول التالي
            }
        }
        return true;
    }

    /** إعدادات تُحفظ مشفّرة بمفتاح الهاتف، وتُصدَّر في النسخة بنصها (داخل تشفير النسخة) ثم تُشفَّر عند الاستعادة. */
    static final Set<String> ENCRYPTED_SETTINGS = new HashSet<>(Arrays.asList("pin_pepper", "day_close_key"));

    /** 8.0 (ت5): سرّ بصمة اليوم؛ ينتقل مع النسخة فتبقى بصمات التقارير القديمة قابلة للتحقق على هاتف جديد. */
    synchronized byte[] dayCloseKey() {
        String enc = setting("day_close_key", "");
        if (!enc.isEmpty()) {
            try {
                String hex = Crypto.decStrict(enc);
                if (hex.matches("[0-9a-f]{64}")) {
                    byte[] b = new byte[32];
                    for (int i = 0; i < 32; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                    return b;
                }
            } catch (Exception ignored) {
                // يُرفض أدناه
            }
            throw new IllegalStateException("تعذر فك سرّ بصمة اليوم على هذا الهاتف");
        }
        byte[] k = Pin.newPepper();
        setSettingQuiet("day_close_key", Crypto.enc(BackupFormat.hex(k)));
        return k;
    }

    /** بصمة حركات يوم [from، to) بحالتها الآن. */
    public String dayCode(long from, long to) {
        TxFilter f = new TxFilter();
        f.from = from;
        f.to = to;
        f.includeCancelled = true;
        return DayClose.code(dayCloseKey(), DayClose.canonical(Model.day(from), txLog(f)));
    }

    /** سرّ رموز المستخدمين (يُنشأ مرة واحدة). */
    synchronized byte[] pinPepper() {
        String enc = setting("pin_pepper", "");
        if (!enc.isEmpty()) {
            String hex;
            try {
                hex = Crypto.decStrict(enc);
            } catch (Exception e) {
                hex = null;
            }
            if (hex == null || hex.length() < 32 || !hex.matches("[0-9a-fA-F]+")) throw new IllegalStateException("تعذر فك سرّ رموز المستخدمين؛ استعمل رمز الاسترداد.");
            byte[] b = new byte[hex.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            return b;
        }
        byte[] p = Pin.newPepper();
        setSettingQuiet("pin_pepper", Crypto.enc(BackupFormat.hex(p)));
        return p;
    }

    // ===================================================================================
    // مطابقة الأرصدة مع المكاتب
    // ===================================================================================

    /** يسجّل مطابقة: لقطة من رصيد المكتب الآن لكل عملة، ورقم آخر قيد حتى تُعرف الحركات التي جاءت بعدها. */
    public long createRecon(long officeId) {
        // 10.11 (AUDIT-011): كما في الواجهة: المطابقة تُرسل للمكتب فهي تصدير
        require(Auth.Perm.EXPORT);
        require(Auth.Perm.RECON);   // 12.0
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            long cutoff;
            try (Cursor c = d.rawQuery("SELECT COALESCE(MAX(id),0) FROM journal_entries", null)) {
                cutoff = c.moveToFirst() ? c.getLong(0) : 0;
            }
            Map<String, Long> bal = new TreeMap<>();
            for (String code : officeCurrencies(officeId)) bal.put(code, 0L);
            try (Cursor c = d.rawQuery("SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=" + officeId
                    + " GROUP BY currency", null)) {
                while (c.moveToNext()) bal.put(c.getString(0), c.getLong(1));
            }
            // 10.18: إعادة إرسال المطابقة نفسها (صورة ثم نص، أو نسخ ثم واتساب) لا تُنشئ مطابقة ثانية: إن كانت آخر مطابقة
            // لهذا المكتب ما زالت بانتظار التأكيد ولم يُسجَّل بعدها أي قيد والأرصدة هي نفسها، تُعتمد هي.
            try (Cursor c = d.rawQuery("SELECT id,status,cutoff_entry FROM reconciliations WHERE office_id=" + officeId + " ORDER BY created_at DESC,id DESC LIMIT 1", null)) {
                if (c.moveToFirst() && Model.RECON_SENT.equals(c.getString(1)) && c.getLong(2) == cutoff) {
                    long last = c.getLong(0);
                    Map<String, Long> was = new TreeMap<>();
                    try (Cursor l = d.rawQuery("SELECT currency,balance_minor FROM reconciliation_lines WHERE recon_id=" + last, null)) {
                        while (l.moveToNext()) was.put(l.getString(0), l.getLong(1));
                    }
                    if (was.equals(bal)) {
                        d.setTransactionSuccessful();
                        return last;
                    }
                }
            }
            ContentValues v = new ContentValues();
            v.put("uuid", UUID.randomUUID().toString());
            v.put("office_id", officeId);
            v.put("created_at", Clock.now());
            v.put("cutoff_entry", cutoff);
            // 10.0: مع المزامنة لكل جهاز نطاق أرقام: «حتى القيد» = حتى آخر قيد من كل نطاق
            v.put("cutoff_marks", SyncStore.marks(d));
            v.put("status", Model.RECON_SENT);
            v.put("user_name", Crypto.enc(currentUserName));
            Model.User me = actor();
            if (me != null) v.put("user_id", me.id);
            long id = ins(d, "reconciliations", v);
            for (Map.Entry<String, Long> e : bal.entrySet()) {
                ContentValues l = new ContentValues();
                l.put("recon_id", id);
                l.put("currency", e.getKey());
                l.put("balance_minor", e.getValue());
                ins(d, "reconciliation_lines", l);
            }
            audit(d, "CREATE_RECON", "office", officeId, "");
            d.setTransactionSuccessful();
            return id;
        } finally {
            d.endTransaction();
        }
    }

    /** مطابقات المكتب، الأحدث أولًا. */
    public List<Model.Recon> recons(long officeId, int limit) {
        List<Model.Recon> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,uuid,office_id,created_at,cutoff_entry,status,confirmed_at,note,user_name,cutoff_marks,user_id,checked_by,checked_at FROM reconciliations WHERE office_id="
                + officeId + " ORDER BY created_at DESC,id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) out.add(readRecon(c));
        }
        for (Model.Recon r : out) r.balances.putAll(reconLines(r.id));
        return out;
    }

    public Model.Recon recon(long id) {
        Model.Recon r = null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,uuid,office_id,created_at,cutoff_entry,status,confirmed_at,note,user_name,cutoff_marks,user_id,checked_by,checked_at FROM reconciliations WHERE id=" + id, null)) {
            if (c.moveToFirst()) r = readRecon(c);
        }
        if (r != null) r.balances.putAll(reconLines(r.id));
        return r;
    }

    /** آخر مطابقة للمكتب؛ confirmedOnly = آخر مطابقة أكّدها المكتب. */
    public Model.Recon lastRecon(long officeId, boolean confirmedOnly) {
        String q = "SELECT id FROM reconciliations WHERE office_id=" + officeId
                + (confirmedOnly ? " AND status='" + Model.RECON_CONFIRMED + "'" : "") + " ORDER BY created_at DESC,id DESC LIMIT 1";
        try (Cursor c = getReadableDatabase().rawQuery(q, null)) {
            if (!c.moveToFirst()) return null;
            return recon(c.getLong(0));
        }
    }

    private Model.Recon readRecon(Cursor c) {
        Model.Recon r = new Model.Recon();
        r.id = c.getLong(0);
        r.uuid = Model.nz(c.getString(1));
        r.officeId = c.getLong(2);
        r.createdAt = c.getLong(3);
        r.cutoffEntry = c.getLong(4);
        r.status = Model.nz(c.getString(5));
        r.confirmedAt = c.isNull(6) ? 0 : c.getLong(6);
        r.note = Model.nz(Crypto.dec(c.getString(7)));
        r.userReal = Model.nz(Crypto.dec(c.getString(8)));
        r.userName = shown(r.userReal);
        r.cutoffMarks = c.getColumnCount() > 9 && !c.isNull(9) ? c.getString(9) : "";
        if (c.getColumnCount() > 12) {
            r.userId = c.isNull(10) ? null : c.getLong(10);
            r.checkedBy = c.isNull(11) ? null : c.getLong(11);
            r.checkedAt = c.isNull(12) ? 0 : c.getLong(12);
        }
        return r;
    }

    private Map<String, Long> reconLines(long reconId) {
        Map<String, Long> m = new TreeMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT currency,balance_minor FROM reconciliation_lines WHERE recon_id=" + reconId, null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getLong(1));
        }
        return m;
    }

    /** 10.8: هل أنشأ المستخدم هذه المطابقة (برقمه، أو باسمه للمطابقات الأقدم)؟ */
    static boolean reconBy(Model.Recon r, Model.User u) {
        if (u == null) return false;
        if (r.userId != null) return r.userId == u.id;
        String real = r.userReal.isEmpty() ? r.userName : r.userReal;
        return !real.isEmpty() && Sanity.norm(real).equals(Sanity.norm(u.name));
    }

    /**
     * 10.8: «تم التحقق» من مطابقة: محاسب غير من أنشأها، ممن يتحقق من مكتبها، يؤكد أن لقطة الأرصدة سليمة. لا يغيّر رصيدًا ولا حالة المطابقة مع المكتب.
     */
    public void verifyRecon(long reconId) {
        require(Auth.Perm.VERIFY);
        Model.User me = actor();
        if (me == null) throw new IllegalStateException("التحقق يحتاج نظام المستخدمين: كل تحقق يُسجَّل باسم من قام به.");
        Model.Recon r = recon(reconId);
        if (r == null) throw new IllegalStateException("المطابقة غير موجودة");
        if (reconBy(r, me)) throw new IllegalStateException("لا يُحتسب تحققك من مطابقة أنشأتها أنت؛ يتحقق منها محاسب آخر.");
        SQLiteDatabase d = getWritableDatabase();
        Set<Long> scope = checkScope(d, me);
        if (scope != null && !scope.contains(r.officeId)) throw new SecurityException(checkRefusal(d, me, r.officeId));
        ContentValues v = new ContentValues();
        v.put("checked_by", me.id);
        v.put("checked_at", Clock.now());
        d.update("reconciliations", v, "id=" + reconId, null);
        audit(d, "VERIFY_RECON", "recon", reconId, me.name);
    }

    /** 10.8: مطابقات خلال آخر days يومًا في المكاتب التي أتحقق منها، أنشأها غيري ولم يُتحقق منها. */
    public List<Model.Recon> awaitingMyRecons(int days, int limit) {
        Model.User me = actor();
        List<Model.Recon> out = new ArrayList<>();
        if (me == null || !Auth.can(me, Auth.Perm.VERIFY)) return out;
        SQLiteDatabase d = getReadableDatabase();
        Set<Long> scope = checkScope(d, me);
        if (scope != null && scope.isEmpty()) return out;
        List<Long> ids = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT id,office_id FROM reconciliations WHERE checked_by IS NULL AND created_at>=" + (Clock.now() - days * 86_400_000L)
                + " ORDER BY created_at DESC,id DESC", null)) {
            while (c.moveToNext()) if (scope == null || scope.contains(c.getLong(1))) ids.add(c.getLong(0));
        }
        for (Long id : ids) {
            Model.Recon r = recon(id);
            if (r != null && !reconBy(r, me)) out.add(r);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** من أنشأ المطابقة ومن تحقق منها (للعرض). */
    public String reconPeople(Model.Recon r) {
        String s = "أنشأها: " + userLabel(r.userId, r.userName);
        if (r.checkedBy != null && r.checkedAt > 0) s += " — تحقق منها: " + userLabel(r.checkedBy, "") + " (" + Model.date(r.checkedAt) + ")";
        else s += " — لم يُتحقق منها بعد";
        return s;
    }

    public void setReconStatus(long id, String status, String note) {
        require(Auth.Perm.TX_ADD);   // 10.11 (AUDIT-011): كما في الواجهة
        ContentValues v = new ContentValues();
        v.put("status", status);
        if (Model.RECON_SENT.equals(status)) v.putNull("confirmed_at");
        else v.put("confirmed_at", Clock.now());
        v.put("note", Crypto.enc(note == null ? "" : note));
        getWritableDatabase().update("reconciliations", v, "id=" + id, null);
        audit(Model.RECON_CONFIRMED.equals(status) ? "CONFIRM_RECON" : Model.RECON_DISPUTED.equals(status) ? "DISPUTE_RECON" : "REOPEN_RECON", "recon", id, note == null ? "" : note);
    }

    public void deleteRecon(long id) {
        // 10.11 (AUDIT-011): الهاتف يطلب «إلغاء الحركات» والكمبيوتر «حذف المكاتب»: تكفي إحداهما
        if (!isSystem() && !allowed(Auth.Perm.TX_CANCEL) && !allowed(Auth.Perm.DELETE)) throw new SecurityException("ليست لديك صلاحية لهذه العملية");
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("DELETE FROM reconciliation_lines WHERE recon_id=" + id);
            d.execSQL("DELETE FROM reconciliations WHERE id=" + id);
            audit(d, "DELETE_RECON", "recon", id, "");
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** آخر مطابقة لكل مكتب (للعرض في قائمة الأرصدة). */
    public Map<Long, Model.Recon> lastReconsByOffice() {
        Map<Long, Model.Recon> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,uuid,office_id,created_at,cutoff_entry,status,confirmed_at,note,user_name FROM reconciliations"
                + " WHERE id IN (SELECT MAX(id) FROM reconciliations GROUP BY office_id)", null)) {
            while (c.moveToNext()) {
                Model.Recon r = readRecon(c);
                m.put(r.officeId, r);
            }
        }
        return m;
    }

    // ---- رمز استرداد المدير (بدل الدخول ببصمة الهاتف) ----

    public boolean hasRecoveryCode() { return !setting("recovery_hash", "").isEmpty(); }

    /** ينشئ رمز استرداد جديدًا (يُلغي القديم) ويعيده مرة واحدة لعرضه؛ يُحفظ مجزّأً فقط. */
    public String newRecoveryCode() {
        if (!recoveryGrant) {
            require(Auth.Perm.USERS);
            requireAdmin("رمز استرداد المدير يُنشئه المدير وحده.");
        }
        String code = Pin.newRecoveryCode();
        String salt = Pin.newSalt();
        setSettingQuiet("recovery_salt", salt);
        setSettingQuiet("recovery_hash", Pin.hash(Pin.normalizeRecovery(code), salt));
        audit("NEW_RECOVERY_CODE", "user", 0, "");
        return code;
    }

    public boolean verifyRecoveryCode(String code) {
        String n = Pin.normalizeRecovery(code);
        if (n.length() != 16) return false;
        return Pin.verify(n, setting("recovery_salt", ""), setting("recovery_hash", ""));
    }

    public void disableUsers() {
        require(Auth.Perm.USERS);
        requireAdmin("إيقاف نظام المستخدمين للمدير وحده.");
        if (founderBlock() != null) throw new IllegalStateException("نظام المستخدمين يُوقف من الجهاز الرئيسي في المزامنة فقط.");
        getWritableDatabase().execSQL("UPDATE users SET active=0");
        audit("DISABLE_USERS", "user", 0, "");
    }

    // ===================================================================================
    // سجل النسخ والتدقيق
    // ===================================================================================

    public static class BackupRecord {
        public long createdAt, size, offices, tx, att;
        public String status, kind, path, details;
        public boolean verified;
    }

    public void recordBackup(String kind, String status, String path, long size, Map<String, Long> counts, boolean verified, String details) {
        try {
            ContentValues v = new ContentValues();
            v.put("created_at", Clock.now());
            v.put("kind", kind);
            v.put("status", status);
            v.put("path", path);
            v.put("size_bytes", size);
            if (counts != null) {
                v.put("n_offices", counts.get("offices"));
                v.put("n_tx", counts.get("tx"));
                v.put("n_att", counts.get("attachments"));
            }
            v.put("verified", verified ? 1 : 0);
            v.put("details", details);
            getWritableDatabase().insert("backup_history", null, v);
        } catch (Exception ignored) {
            // السجل للمعلومة فقط
        }
    }

    public List<BackupRecord> backupHistory(int limit) {
        List<BackupRecord> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT created_at,status,kind,path,details,size_bytes,n_offices,n_tx,n_att,verified FROM backup_history ORDER BY id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                BackupRecord r = new BackupRecord();
                r.createdAt = c.isNull(0) ? 0 : c.getLong(0);
                r.status = Model.nz(c.getString(1));
                r.kind = Model.nz(c.getString(2));
                r.path = Model.nz(c.getString(3));
                r.details = Model.nz(c.getString(4));
                r.size = c.isNull(5) ? 0 : c.getLong(5);
                r.offices = c.isNull(6) ? 0 : c.getLong(6);
                r.tx = c.isNull(7) ? 0 : c.getLong(7);
                r.att = c.isNull(8) ? 0 : c.getLong(8);
                r.verified = !c.isNull(9) && c.getInt(9) == 1;
                a.add(r);
            }
        }
        return a;
    }

    /** سجل العمليات: {الوقت، العملية، الكيان، الرقم، المستخدم، السبب}. */
    public List<String[]> auditLog(int limit) {
        List<String[]> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT created_at,action,entity,entity_id,user_name,reason FROM audit_logs WHERE action<>'LOGIN_SESSION' ORDER BY id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                a.add(new String[]{Model.date(c.isNull(0) ? 0 : c.getLong(0)), Model.nz(c.getString(1)), Model.nz(c.getString(2)),
                        c.isNull(3) ? "" : "" + c.getLong(3), Model.nz(Crypto.dec(c.getString(4))), Model.nz(Crypto.dec(c.getString(5)))});
            }
        }
        return a;
    }

    /**
     * 10.16: سجل العمليات من كل الأجهزة: {الوقت، العملية، الكيان، الرقم، المستخدم، السبب، الجهاز}. أسطر هذا الجهاز من سجله
     * المتسلسل، وأسطر الأجهزة الأخرى مما وصل بالمزامنة. بلا مزامنة = سجل هذا الجهاز كما كان (الجهاز فارغ).
     */
    public List<String[]> auditLogAll(int limit) {
        require(Auth.Perm.AUDIT);   // 11.6: في طبقة البيانات أيضًا
        SQLiteDatabase d = getReadableDatabase();
        String me = SyncStore.meta(d, "device");
        List<Object[]> rows = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT created_at,action,entity,entity_id,user_name,reason FROM audit_logs WHERE action<>'LOGIN_SESSION' ORDER BY id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) rows.add(new Object[]{c.isNull(0) ? 0L : c.getLong(0), Model.nz(c.getString(1)), Model.nz(c.getString(2)),
                    c.isNull(3) ? "" : "" + c.getLong(3), shown(Model.nz(Crypto.dec(c.getString(4)))), Model.nz(Crypto.dec(c.getString(5))), ""});
        }
        boolean others = false;
        // خارج المزامنة لا يُعرف أيّ أسطر السجل المشترك كانت لهذا الجهاز: يُعرض سجله وحده (فلا تتكرر أسطره)
        if (me != null) try (Cursor c = d.rawQuery("SELECT created_at,action,entity,entity_id,user_name,reason,dev_name FROM audit_feed WHERE dev IS NOT NULL AND dev<>? AND action<>'LOGIN_SESSION' "
                + "ORDER BY created_at DESC LIMIT " + limit, new String[]{me == null ? "" : me})) {
            while (c.moveToNext()) {
                others = true;
                rows.add(new Object[]{c.getLong(0), Model.nz(c.getString(1)), Model.nz(c.getString(2)), c.isNull(3) ? "" : "" + c.getLong(3),
                        shown(Model.nz(Crypto.dec(c.getString(4)))), Model.nz(Crypto.dec(c.getString(5))), Model.nz(c.getString(6)).isEmpty() ? "جهاز آخر" : c.getString(6)});
            }
        }
        if (others) {
            java.util.Collections.sort(rows, (x, y) -> Long.compare((Long) y[0], (Long) x[0]));
            String mine = Model.nz(SyncStore.meta(d, "device_name"));
            for (Object[] r : rows) if (((String) r[6]).isEmpty()) r[6] = mine.isEmpty() ? "هذا الجهاز" : mine + " (هذا الجهاز)";
        }
        List<String[]> a = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < limit; i++) {
            Object[] r = rows.get(i);
            a.add(new String[]{Model.date((Long) r[0]), (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5], (String) r[6]});
        }
        return a;
    }

    /**
     * 11.5: جلسات الاستخدام منذ وقت معيّن من كل الأجهزة: {نهاية الجلسة، مدتها بالثواني، المستخدم، الجهاز}، الأحدث أولًا.
     * أسطر هذا الجهاز من سجله، وأسطر الأجهزة الأخرى مما وصل بالمزامنة (السجل المشترك).
     */
    public List<Object[]> usageSessions(long since) {
        requireAdmin("سجل الاستخدام للمدير وحده");   // 11.6
        SQLiteDatabase d = getReadableDatabase();
        String me = SyncStore.meta(d, "device");
        String mine = Model.nz(SyncStore.meta(d, "device_name"));
        String here = mine.isEmpty() ? "هذا الجهاز" : mine;
        List<Object[]> rows = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT created_at,entity_id,user_name FROM audit_logs WHERE action=? AND created_at>=?", new String[]{UsageScreen.ACTION, "" + since})) {
            while (c.moveToNext()) rows.add(new Object[]{c.getLong(0), c.isNull(1) ? 0L : c.getLong(1), shown(Model.nz(Crypto.dec(c.getString(2)))), here});
        }
        if (me != null) try (Cursor c = d.rawQuery("SELECT created_at,entity_id,user_name,dev_name FROM audit_feed WHERE action=? AND created_at>=? AND dev IS NOT NULL AND dev<>?",
                new String[]{UsageScreen.ACTION, "" + since, me})) {
            while (c.moveToNext()) rows.add(new Object[]{c.getLong(0), c.isNull(1) ? 0L : c.getLong(1), shown(Model.nz(Crypto.dec(c.getString(2)))),
                    Model.nz(c.getString(3)).isEmpty() ? "جهاز آخر" : c.getString(3)});
        }
        java.util.Collections.sort(rows, (x, y) -> Long.compare((Long) y[0], (Long) x[0]));
        return rows;
    }

    /** 12.7: عدد الحركات التي أدخلها كل مستخدم منذ since (للمدير، بجانب وقت عمله). المفتاح اسم المستخدم الحقيقي؛ "" = بلا نظام مستخدمين. */
    public Map<String, Long> txCountsByUser(long since) {
        requireAdmin("سجل الاستخدام للمدير وحده");
        Map<String, Long> m = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (Model.User u : users(true)) names.put(u.id, u.name);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT user_id,user_name,COUNT(*) FROM tx WHERE created_at>=? GROUP BY user_id,user_name", new String[]{"" + since})) {
            while (c.moveToNext()) {
                String n = c.isNull(0) ? null : names.get(c.getLong(0));
                if (n == null) n = c.isNull(1) ? "" : Model.nz(Crypto.dec(c.getString(1)));
                m.merge(n, c.getLong(2), Long::sum);
            }
        }
        return m;
    }

    public Map<String, Long> counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        SQLiteDatabase d = getReadableDatabase();
        String[] q = {"offices", "tx", "attachments", "currencies"};
        for (String x : q) {
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM " + x, null)) {
                m.put(x, c.moveToFirst() ? c.getLong(0) : 0L);
            }
        }
        return m;
    }

    // ===================================================================================
    // النسخة الاحتياطية: مصدر البيانات
    // ===================================================================================

    public BackupFormat.Source backupSource() { return backupSource(null); }

    /**
     * 12.7: نسخة من ملف القاعدة (والـ WAL) للقراءة في الخلفية. تُستدعى داخل معاملة كتابة يملكها الخيط نفسه، فلا تتغير
     * البيانات أثناء النسخ (ثوانٍ قليلة بدل إبقاء المعاملة مفتوحة طوال بناء اللقطة). null إن تعذّر النسخ.
     */
    public File copyForRead(String tag) {
        File src = ctx.getDatabasePath(NAME);
        File dst = new File(ctx.getCacheDir() != null ? ctx.getCacheDir() : src.getParentFile(), "read-" + tag + "-" + System.nanoTime() + ".db");
        try {
            copyFile(src, dst);
            File wal = new File(src.getPath() + "-wal");
            if (wal.isFile() && wal.length() > 0) copyFile(wal, new File(dst.getPath() + "-wal"));
            return dst;
        } catch (Throwable t) {
            DbCipher.deleteDb(dst);
            return null;
        }
    }

    /** 12.7: يفتح نسخة copyForRead بمفتاح القاعدة نفسها. */
    public SQLiteDatabase openReadCopy(File f) {
        File main = ctx.getDatabasePath(NAME);
        String key = DbCipher.isPlain(main) ? "" : Crypto.dbKey();
        return DbCipher.openFile(f, key == null ? "" : key);
    }

    /** 12.7: عدّ الجداول من اتصال محدد (نسخة للقراءة) أو من القاعدة. */
    private Map<String, Long> counts(SQLiteDatabase d) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String x : new String[]{"offices", "tx", "attachments", "currencies"}) {
            try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM " + x, null)) {
                m.put(x, c.moveToFirst() ? c.getLong(0) : 0L);
            }
        }
        return m;
    }

    /** 12.7: مصدر النسخة من اتصال محدد (null = القاعدة نفسها). */
    public BackupFormat.Source backupSource(final SQLiteDatabase from) {
        return new BackupFormat.Source() {
            int unreadable;

            SQLiteDatabase db() { return from != null ? from : getReadableDatabase(); }

            @Override public Map<String, Long> counts() { return from == null ? Db.this.counts() : Db.this.counts(from); }

            @Override public void rows(String table, BackupFormat.RowSink sink) throws Exception {
                List<String> sensitive = SENSITIVE.get(table);
                SQLiteDatabase d = db();
                try (Cursor c = d.rawQuery("SELECT * FROM " + table, null)) {
                    int n = c.getColumnCount();
                    while (c.moveToNext()) {
                        JSONObject r = new JSONObject();
                        for (int i = 0; i < n; i++) {
                            String col = c.getColumnName(i);
                            int type = c.getType(i);
                            if (type == Cursor.FIELD_TYPE_NULL) r.put(col, JSONObject.NULL);
                            else if (type == Cursor.FIELD_TYPE_INTEGER) r.put(col, c.getLong(i));
                            else if (type == Cursor.FIELD_TYPE_FLOAT) r.put(col, c.getDouble(i));
                            else if (type == Cursor.FIELD_TYPE_BLOB) continue;
                            else {
                                String s = c.getString(i);
                                if (sensitive != null && sensitive.contains(col)) {
                                    try {
                                        s = Crypto.decStrict(s);
                                    } catch (Exception e) {
                                        s = Crypto.PLACEHOLDER;
                                        unreadable++;
                                    }
                                }
                                r.put(col, s);
                            }
                        }
                        if (table.equals("settings") && DEVICE_SETTINGS.contains(r.optString("k"))) continue;
                        if (table.equals("settings") && ENCRYPTED_SETTINGS.contains(r.optString("k")) && !r.isNull("v")) {
                            try {
                                r.put("v", Crypto.decStrict(r.getString("v")));
                            } catch (Exception e) {
                                continue;   // لا يُصدَّر سرّ لا يُفك (يُنشأ غيره عند الحاجة)
                            }
                        }
                        sink.row(r);
                    }
                }
            }

            @Override public List<BackupFormat.AttachmentRef> attachments() {
                List<BackupFormat.AttachmentRef> l = new ArrayList<>();
                try (Cursor c = db().rawQuery("SELECT id,tx_id,path,mime,created_at FROM attachments ORDER BY id", null)) {
                    while (c.moveToNext()) {
                        BackupFormat.AttachmentRef r = new BackupFormat.AttachmentRef();
                        r.id = c.getLong(0);
                        r.txId = c.getLong(1);
                        r.handle = c.getString(2);
                        r.mime = c.getString(3);
                        r.createdAt = c.isNull(4) ? 0 : c.getLong(4);
                        l.add(r);
                    }
                }
                return l;
            }

            @Override public byte[] attachmentData(BackupFormat.AttachmentRef ref) {
                try {
                    return Crypto.decBytes(readAll(attachmentFile((String) ref.handle)));
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override public List<String> warnings() {
                List<String> w = new ArrayList<>();
                if (unreadable > 0) w.add(unreadable + " حقل نصي لم يمكن فك تشفيره في الهاتف الأصلي (حُفظ كنص بديل)");
                return w;
            }

            @Override public int unreadable() { return unreadable; }
        };
    }

    // ===================================================================================
    // إضافة ملف النقل إلى البيانات الحالية (بدل استبدالها)
    // ===================================================================================

    /** ما تحتاجه خطة الإضافة من البيانات الحالية. */
    public MergePlan.Existing mergeExisting() {
        MergePlan.Existing ex = new MergePlan.Existing();
        for (Model.Office o : offices()) ex.offices.put(o.id, o.name);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT code,decimals FROM currencies", null)) {
            while (c.moveToNext()) ex.decimals.put(c.getString(0), c.getInt(1));
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT office_id,currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id IS NOT NULL GROUP BY office_id,currency", null)) {
            while (c.moveToNext()) ex.balances.put(c.getLong(0) + "|" + c.getString(1), c.getLong(2));
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uuid FROM tx", null)) {
            while (c.moveToNext()) ex.uuids.add(c.getString(0));
        }
        for (String f : setting("merged_files", "").split("\n")) if (!f.trim().isEmpty()) ex.mergedFiles.add(f.trim());
        ex.lockBefore = settingLong("lock_before", 0);
        return ex;
    }

    private static long officeBalance(SQLiteDatabase d, long office, String code) {
        try (Cursor c = d.rawQuery("SELECT COALESCE(SUM(debit_minor-credit_minor),0) FROM journal_lines WHERE account='OFFICE' AND office_id=? AND currency=?",
                new String[]{String.valueOf(office), code})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /**
     * يضيف محتوى ملف النقل إلى البيانات الحالية في معاملة واحدة: عملات ومكاتب جديدة، وأرصدة سابقة وحركات بالقيود نفسها
     * التي يكتبها التطبيق (postEntry)، ثم يتحقق أن رصيد كل مكتب وعملة تغيّر بمقدار قيود الملف تمامًا، وإلا يُلغى كل شيء.
     * يرجع عدد المكاتب الجديدة.
     */
    public int mergeMigration(MergePlan plan) {
        require(Auth.Perm.RESTORE);
        if (!plan.ok()) throw new IllegalStateException(plan.errors.get(0));
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            Entitlements ent = License.current;
            if (!unlimited(ent) && liveOffices(d) + plan.newOffices > ent.maxOffices()) {
                throw new Entitlements.LimitReached("الملف يضيف " + plan.newOffices + " مكتبًا جديدًا وعندك " + liveOffices(d) + "، والنسخة المجانية تتسع لـ "
                        + ent.maxOffices() + ". اختر المكاتب الجديدة التي تريد إضافتها، أو فعّل النسخة الكاملة.");
            }
            for (MergePlan.NewCurrency c : plan.newCurrencies) {
                d.execSQL("INSERT OR IGNORE INTO currencies(code,name,decimals,active) VALUES(?,?,?,1)", new Object[]{c.code, c.name, c.decimals});
            }
            Map<Long, Long> target = new HashMap<>();
            int created = 0;
            for (MergePlan.Office o : plan.offices.values()) {
                long id;
                if (o.targetId != null) {
                    id = o.targetId;
                    try (Cursor c = d.rawQuery("SELECT 1 FROM offices WHERE id=" + id, null)) {
                        if (!c.moveToFirst()) throw new IllegalStateException("المكتب «" + o.targetName + "» لم يعد موجودًا؛ أعد المحاولة.");
                    }
                } else {
                    ContentValues v = officeValues(o.name, "", o.phone, "", "", o.notes, null, null);
                    v.put("active", 1);
                    v.put("pinned", 0);
                    id = ins(d, "offices", v);
                    created++;
                }
                target.put(o.fileId, id);
                for (String code : o.codes) {
                    d.execSQL("INSERT OR IGNORE INTO office_currencies(office_id,code) VALUES(?,?)", new Object[]{id, code});
                    d.execSQL("UPDATE currencies SET active=1 WHERE code=?", new Object[]{code});
                }
            }
            // الأرصدة قبل الإضافة، والمتوقع بعدها (مكتبان في الملف قد يذهبان للمكتب نفسه)
            Map<String, Long> want = new HashMap<>();
            for (Map.Entry<String, Long> e : plan.expected.entrySet()) {
                String[] k = e.getKey().split("\\|", 2);
                long id = target.get(Long.parseLong(k[0]));
                String key = id + "|" + k[1];
                if (!want.containsKey(key)) want.put(key, officeBalance(d, id, k[1]));
                want.put(key, want.get(key) + e.getValue());
            }
            long lock = lockBefore(d);
            // 10.16: «اعتماد رصيد الملف»: رصيد المكتب الموجود يُصفَّر بقيد تسوية ظاهر (لا حذف)، فيصبح بعد الإضافة = رصيد الملف
            int adopted = 0;
            if (plan.adopt) {
                long nowT = Clock.now();
                Set<String> seen = new HashSet<>();
                for (MergePlan.Office o : plan.offices.values()) {
                    if (o.targetId == null) continue;
                    for (String code : o.codes) {
                        String key = o.targetId + "|" + code;
                        if (!seen.add(key)) continue;
                        long cur = officeBalance(d, o.targetId, code);
                        if (cur == 0) continue;
                        long amt = Math.abs(cur);
                        long eid = postEntry(d, null, "OPENING", o.targetId, code, cur < 0 ? amt : 0, cur > 0 ? amt : 0, nowT);
                        ContentValues z = new ContentValues();
                        z.put("office_id", o.targetId);
                        z.put("currency", code);
                        z.put("type", cur < 0 ? "لنا" : "علينا");
                        z.put("amount_minor", amt);
                        z.put("entry_id", eid);
                        ins(d, "opening_balances", z);
                        want.put(key, (want.containsKey(key) ? want.get(key) : cur) - cur);
                        adopted++;
                    }
                }
            }
            for (MergePlan.Opening o : plan.openings) {
                if (lock > 0 && o.when < lock) throw new IllegalStateException("رصيد سابق قبل تاريخ الإقفال المحاسبي");
                long id = target.get(o.fileOffice);
                long amt = Math.abs(o.signed);
                long eid = postEntry(d, null, "OPENING", id, o.code, o.signed > 0 ? amt : 0, o.signed < 0 ? amt : 0, o.when);
                ContentValues z = new ContentValues();
                z.put("office_id", id);
                z.put("currency", o.code);
                z.put("type", o.signed > 0 ? "لنا" : "علينا");
                z.put("amount_minor", amt);
                z.put("entry_id", eid);
                ins(d, "opening_balances", z);
            }
            for (MergePlan.Tx x : plan.txs) {
                if (lock > 0 && x.when < lock) throw new IllegalStateException("حركة قبل تاريخ الإقفال المحاسبي");
                long office = target.get(x.fileOffice);
                boolean hasFee = !FEE_NONE.equals(x.feeType);
                ContentValues v = new ContentValues();
                v.put("uuid", x.uuid);
                v.put("office_id", office);
                v.put("type", x.type);
                v.put("person", Crypto.enc(Model.nz(x.person)));
                v.put("amount_minor", x.amount);
                v.put("currency", x.currency);
                v.put("fee_type", x.feeType);
                v.put("fee_minor", hasFee ? x.fee : 0);
                v.put("fee_currency", hasFee ? x.feeCurrency : x.currency);
                v.put("description", Crypto.enc(Model.nz(x.description)));
                v.put("notes", Crypto.enc(Model.nz(x.notes)));
                v.put("created_at", x.when);
                v.put("user_name", Crypto.enc(Model.nz(x.userName)));
                long id = ins(d, "tx", v);
                postEntry(d, id, "PRINCIPAL", office, x.currency, TYPE_DEBIT.equals(x.type) ? x.amount : 0, TYPE_CREDIT.equals(x.type) ? x.amount : 0, x.when);
                if (hasFee) postEntry(d, id, "FEE", office, x.feeCurrency, FEE_US.equals(x.feeType) ? x.fee : 0, FEE_THEM.equals(x.feeType) ? x.fee : 0, x.when);
            }
            // التحقق: كل رصيد تغيّر بمقدار قيود الملف تمامًا
            for (Map.Entry<String, Long> e : want.entrySet()) {
                String[] k = e.getKey().split("\\|", 2);
                long got = officeBalance(d, Long.parseLong(k[0]), k[1]);
                if (got != e.getValue()) {
                    throw new IllegalStateException("التحقق بعد الإضافة فشل (" + k[1] + "): الرصيد " + got + " والمتوقع " + e.getValue() + ". لم يُضف شيء.");
                }
            }
            try (Cursor c = d.rawQuery("SELECT entry_id,currency,SUM(debit_minor-credit_minor) s FROM journal_lines GROUP BY entry_id,currency HAVING s<>0 LIMIT 1", null)) {
                if (c.moveToFirst()) throw new IllegalStateException("قيد غير متوازن بعد الإضافة؛ لم يُضف شيء.");
            }
            String prev = rawSetting(d, "merged_files");
            d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('merged_files',?)",
                    new Object[]{(prev == null || prev.isEmpty() ? "" : prev + "\n") + plan.fingerprint});
            rebaseQuotaKeep(d);
            audit(d, "MERGE_MIGRATION", "import", 0, plan.offices.size() + " مكتب (" + created + " جديد)، " + plan.txs.size() + " حركة، "
                    + plan.openings.size() + " رصيد سابق" + (adopted > 0 ? "، واعتُمد رصيد الملف بدل الجمع في " + adopted + " رصيد (قيد تسوية للرصيد السابق)" : "")
                    + (plan.droppedOffices > 0 ? "، واستُبعد " + plan.droppedOffices + " مكتب باختيارك" : ""));
            d.setTransactionSuccessful();
            return created;
        } finally {
            d.endTransaction();
        }
    }

    // ===================================================================================
    // 9.0: عدّاد واتساب اليومي للنسخة المجانية
    // ===================================================================================

    static String today() { return Model.sdf(new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)).format(new java.util.Date()); }

    private WaQuota waQuota(SQLiteDatabase d) {
        String raw = rawSetting(d, "wa_quota");
        WaQuota q = WaQuota.parse(raw);
        String want = raw == null ? "" : quotaMac.apply(q.serialize());
        String stored = rawSetting(d, "wa_quota_mac");
        if (raw != null && !want.isEmpty() && !want.equals(stored)) {
            // عُدّل خارج التطبيق: يُعتبر حد اليوم مستنفدًا (يعود غدًا)
            q = new WaQuota();
            q.day = today();
            for (int i = 0; i < Entitlements.FREE_WA_PER_DAY; i++) q.seen.add("x" + i);
        }
        return q;
    }

    /** هل يُسمح باستخراج معلومات هذه الرسالة الآن؟ (الكاملة: دائمًا) */
    public boolean waAllowed(String text) {
        long max = License.current.waPerDay();
        if (max == Entitlements.UNLIMITED) return true;
        return waQuota(getReadableDatabase()).allows(WaQuota.key(text), max, today());
    }

    /** يحتسب الرسالة اليوم (مرة واحدة لكل نص). false = بلغ الحد. */
    public boolean waUse(String text) {
        long max = License.current.waPerDay();
        if (max == Entitlements.UNLIMITED) return true;
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            WaQuota q = waQuota(d);
            boolean ok = q.use(WaQuota.key(text), max, today());
            String v = q.serialize();
            d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('wa_quota',?)", new Object[]{v});
            d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('wa_quota_mac',?)", new Object[]{quotaMac.apply(v)});
            d.setTransactionSuccessful();
            return ok;
        } finally {
            d.endTransaction();
        }
    }

    /** {المستخدم اليوم، الحد} (الحد UNLIMITED في الكاملة). */
    public long[] waUsage() {
        long max = License.current.waPerDay();
        if (max == Entitlements.UNLIMITED) return new long[]{0, max};
        return new long[]{waQuota(getReadableDatabase()).used(today()), max};
    }

    // ===================================================================================
    // 9.0: ترتيب المكاتب: الاستخدام وآخر فتح والميزان بالدولار
    // ===================================================================================

    /** لكل مكتب: {حركات آخر 90 يومًا، كل الحركات، وقت آخر حركة}. */
    public Map<Long, long[]> officeUsage() {
        Map<Long, long[]> m = new HashMap<>();
        long since = Clock.now() - 90L * 86400000L;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT office_id,SUM(CASE WHEN created_at>=" + since + " THEN 1 ELSE 0 END),COUNT(*),MAX(created_at) FROM tx GROUP BY office_id", null)) {
            while (c.moveToNext()) m.put(c.getLong(0), new long[]{c.getLong(1), c.getLong(2), c.isNull(3) ? 0 : c.getLong(3)});
        }
        return m;
    }

    /** يُسجَّل فتح صفحة المكتب أو التسجيل عليه (لترتيب «آخر ما فتحته»). */
    public void touchOffice(long officeId) {
        setSettingQuiet("office_opened", OfficeOrder.touchOpened(setting("office_opened", ""), officeId, Clock.now()));
    }

    public OfficeOrder.Inputs officeOrderInputs(Map<Long, Map<String, Long>> balances) {
        OfficeOrder.Inputs in = new OfficeOrder.Inputs();
        Map<String, BigDecimal> rates = usdRates();
        Map<String, Integer> dec = decimalsMap();
        for (Map.Entry<Long, Map<String, Long>> e : balances.entrySet()) in.usdNet.put(e.getKey(), Ledger.valueOffice(e.getValue(), rates, dec).netUsd());
        Map<Long, long[]> use = officeUsage();
        Map<Long, Long> opened = OfficeOrder.parseOpened(setting("office_opened", ""));
        for (Map.Entry<Long, long[]> e : use.entrySet()) {
            in.usage.put(e.getKey(), new long[]{e.getValue()[0], e.getValue()[1]});
            Long o = opened.get(e.getKey());
            opened.put(e.getKey(), Math.max(o == null ? 0 : o, e.getValue()[2]));
        }
        in.opened.putAll(opened);
        return in;
    }

    // ===================================================================================
    // 9.0: صندوق المكتب
    // ===================================================================================

    static final String[] CASH_KEYS = {"cash_on", "cash_name", "cash_currencies", "cash_strict", "cash_allow_internal", "cash_cats_exp", "cash_cats_in", "cash_cats_out", "cash_boxes"};

    /**
     * 10.0: الصناديق في جدول خاص (cash_boxes) بأرقام ثابتة: لكل صندوق اسمه وعملاته وربطه بالمكاتب، ويُعطَّل أو يُحذف.
     * بلا صناديق بعد يرجع صندوقًا افتراضيًا غير مُعَد (on=false) ليظهر الإعداد الأول.
     */
    public List<CashBox.Config> cashBoxes() {
        Map<String, String> st = settings(CASH_KEYS);
        List<CashBox.Config> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,currencies,strict,allow_internal,active FROM cash_boxes ORDER BY sort,id", null)) {
            while (c.moveToNext()) {
                CashBox.Config x = CashBox.Config.from(st);
                x.on = true;
                x.id = c.getLong(0);
                x.name = Model.nz(c.getString(1)).trim().isEmpty() ? CashBox.DEFAULT_NAME : c.getString(1).trim();
                x.currencies.clear();
                x.currencies.addAll(CashBox.Config.list(c.getString(2)));
                x.strict = c.getInt(3) == 1;
                x.allowInternal = c.getInt(4) == 1;
                x.active = c.getInt(5) == 1;
                out.add(x);
            }
        }
        if (out.isEmpty()) {
            CashBox.Config x = CashBox.Config.from(st);
            x.on = false;
            x.id = 1;
            out.add(x);
        }
        return out;
    }

    /** 10.0: نقل الصناديق من الإعدادات (9.0 و9.0.1) إلى جدولها بالأرقام نفسها (حركاتها تشير إليها). آمن للتكرار. */
    private static void migrateBoxes(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM cash_boxes", null)) {
            if (c.moveToFirst() && c.getLong(0) > 0) return;
        }
        Map<String, String> st = new HashMap<>();
        for (String k : CASH_KEYS) {
            String v = rawSetting(d, k);
            if (v != null) st.put(k, v);
        }
        if (!"1".equals(st.get("cash_on"))) return;
        long now = Clock.now();
        int sort = 0;
        for (CashBox.Config b : CashBox.Config.boxes(st)) {
            d.execSQL("INSERT INTO cash_boxes(id,uuid,name,currencies,strict,allow_internal,active,sort,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    new Object[]{b.id, UUID.randomUUID().toString(), b.name, CashBox.Config.join(b.currencies), b.strict ? 1 : 0, b.allowInternal ? 1 : 0,
                            b.active ? 1 : 0, sort++, now});
        }
        // حركات صناديق بلا صندوق معروف (لا يُتوقع): صندوق باسمها حتى لا يختفي نقدها
        try (Cursor c = d.rawQuery("SELECT DISTINCT box_id FROM cash_moves WHERE box_id NOT IN (SELECT id FROM cash_boxes)", null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                d.execSQL("INSERT INTO cash_boxes(id,uuid,name,currencies,strict,allow_internal,active,sort,created_at) VALUES(?,?,?,?,0,1,1,?,?)",
                        new Object[]{id, UUID.randomUUID().toString(), "صندوق " + id, Model.nz(st.get("cash_currencies")), sort++, now});
            }
        }
    }

    /** الصندوق المفتوح على هذا الجهاز (إعداد خاص بالجهاز)، وأول صندوق مفعّل إن لم يكن موجودًا أو كان معطّلًا. */
    public long currentBox() {
        long id = settingLong("cash_current", 1);
        List<CashBox.Config> all = cashBoxes();
        CashBox.Config c = CashBox.Config.find(all, id);
        if (c != null && c.active) return id;
        for (CashBox.Config x : all) if (x.active) return x.id;
        return all.get(0).id;
    }

    public void setCurrentBox(long id) { setSettingQuiet("cash_current", "" + id); }

    /** الصندوق برقمه (يرفض غير الموجود: لا تُسجَّل حركة على صندوق آخر خطأً). */
    public CashBox.Config cashBox(long id) {
        CashBox.Config c = CashBox.Config.find(cashBoxes(), id);
        if (c == null) throw new IllegalArgumentException("الصندوق رقم " + id + " غير موجود");
        return c;
    }

    /** إعدادات الصندوق المفتوح. */
    public CashBox.Config cashConfig() { return cashBox(currentBox()); }

    public void cashSetup(String name, List<String> currencies, Map<String, Long> openings, boolean strict, boolean allowInternal) {
        cashSetup(currentBox(), name, currencies, openings, strict, allowInternal);
    }

    private void checkBoxCurrencies(List<String> currencies) {
        if (currencies == null || currencies.isEmpty()) throw new IllegalArgumentException("اختر عملة واحدة على الأقل للصندوق");
        Set<String> known = new HashSet<>();
        for (Model.Currency c : currencies(true)) known.add(c.code);
        for (String c : currencies) if (!known.contains(c)) throw new IllegalArgumentException("العملة " + c + " غير موجودة في التطبيق. أضفها أولًا.");
    }

    /**
     * إعداد الصندوق أول مرة أو تعديل إعداداته: الاسم والعملات، وربطه بالمكاتب (10.0: لكل صندوق ربطه)، وأرصدة افتتاحية للعملات الجديدة.
     * لا تُزال عملة رصيدها في الصندوق ليس صفرًا.
     */
    public void cashSetup(long boxId, String name, List<String> currencies, Map<String, Long> openings, boolean strict, boolean allowInternal) {
        require(Auth.Perm.SETTINGS);
        checkBoxCurrencies(currencies);
        List<CashBox.Config> boxes = cashBoxes();
        CashBox.Config old = CashBox.Config.find(boxes, boxId);
        if (old == null) throw new IllegalArgumentException("الصندوق غير موجود");
        Map<String, Long> bal = old.on ? cashBalances(boxId) : new HashMap<>();
        for (String c : old.currencies) {
            Long b = bal.get(c);
            if (!currencies.contains(c) && b != null && b != 0) throw new IllegalStateException("لا يمكن إزالة " + c + " من الصندوق ورصيدها ليس صفرًا");
        }
        SQLiteDatabase d = getWritableDatabase();
        long now = Clock.now();
        d.beginTransaction();
        try {
            String nm = name == null || name.trim().isEmpty() ? (old.on ? old.name : CashBox.DEFAULT_NAME) : name.trim();
            for (CashBox.Config o : boxes) if (o.on && o.id != boxId && o.name.equals(nm)) throw new IllegalArgumentException("يوجد صندوق بالاسم نفسه");
            boolean first = !old.on;
            if (first) {
                ContentValues v = new ContentValues();
                v.put("uuid", UUID.randomUUID().toString());
                v.put("name", nm);
                v.put("currencies", CashBox.Config.join(currencies));
                v.put("strict", strict ? 1 : 0);
                v.put("allow_internal", allowInternal ? 1 : 0);
                v.put("active", 1);
                v.put("sort", 0);
                v.put("created_at", now);
                boxId = ins(d, "cash_boxes", v);
                d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('cash_on','1')");
            } else {
                d.execSQL("UPDATE cash_boxes SET name=?,currencies=?,strict=?,allow_internal=? WHERE id=" + boxId,
                        new Object[]{nm, CashBox.Config.join(currencies), strict ? 1 : 0, allowInternal ? 1 : 0});
            }
            insertOpenings(d, boxId, currencies, openings, now);
            audit(d, first ? "CASH_SETUP" : "CASH_SETTINGS", "cash", boxId, nm + " — " + CashBox.Config.join(currencies) + (strict ? " — مربوط بالمكاتب" : " — حر"));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        if (first(boxes)) setCurrentBox(boxId);
    }

    private static boolean first(List<CashBox.Config> boxes) { return boxes.size() == 1 && !boxes.get(0).on; }

    private void insertOpenings(SQLiteDatabase d, long boxId, List<String> currencies, Map<String, Long> openings, long now) {
        if (openings == null) return;
        for (Map.Entry<String, Long> e : openings.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            if (!currencies.contains(e.getKey())) throw new IllegalArgumentException("الرصيد الافتتاحي بعملة غير مختارة: " + e.getKey());
            CashBox.Move m = new CashBox.Move();
            m.kind = CashBox.OPENING;
            m.boxId = boxId;
            m.currency = e.getKey();
            m.amount = e.getValue();
            m.dir = 1;
            m.category = CashBox.CAT_OPENING;
            m.createdAt = now;
            insertCash(d, m);
        }
    }

    public long addCashBox(String name, List<String> currencies, Map<String, Long> openings) {
        return addCashBox(name, currencies, openings, false, true);
    }

    /** صندوق جديد (مثل: صندوق الفرع، خزنة البيت) بربطه الخاص بالمكاتب. يرجع رقمه. */
    public long addCashBox(String name, List<String> currencies, Map<String, Long> openings, boolean strict, boolean allowInternal) {
        require(Auth.Perm.SETTINGS);
        List<CashBox.Config> boxes = cashBoxes();
        if (!boxes.get(0).on) throw new IllegalStateException("اضبط الصندوق الأول أولًا");
        checkBoxCurrencies(currencies);
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) throw new IllegalArgumentException("اكتب اسم الصندوق");
        for (CashBox.Config o : boxes) if (o.name.equals(nm)) throw new IllegalArgumentException("يوجد صندوق بالاسم نفسه");
        checkBoxSlot(boxes, -1);   // 12.6
        SQLiteDatabase d = getWritableDatabase();
        long now = Clock.now();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("uuid", UUID.randomUUID().toString());
            v.put("name", nm);
            v.put("currencies", CashBox.Config.join(currencies));
            v.put("strict", strict ? 1 : 0);
            v.put("allow_internal", allowInternal ? 1 : 0);
            v.put("active", 1);
            v.put("sort", boxes.size());
            v.put("created_at", now);
            long id = ins(d, "cash_boxes", v);
            insertOpenings(d, id, currencies, openings, now);
            audit(d, "CASH_BOX_ADD", "cash", id, nm + " — " + CashBox.Config.join(currencies));
            d.setTransactionSuccessful();
            return id;
        } finally {
            d.endTransaction();
        }
    }

    /** تعطيل صندوق (كل أرصدته صفر، ويبقى صندوق مفعّل غيره) أو إعادته. يبقى تاريخه في التقارير. */
    /** 12.6: سقف الصناديق المفعّلة (المجاني واحد). الموجودة لا تُعطَّل أبدًا؛ يتوقف إنشاء صندوق أو إعادة تفعيله فقط. */
    private static void checkBoxSlot(List<CashBox.Config> boxes, long except) {
        long max = License.current.maxCashBoxes();
        if (max == Entitlements.UNLIMITED) return;
        int n = 0;
        for (CashBox.Config o : boxes) if (o.on && o.active && o.id != except) n++;
        if (n >= max) throw new Entitlements.LimitReached(Entitlements.boxLimitMessage(max));
    }

    /** 12.6: سقف العملاء النشطين (المجاني 25). الأرشفة تعيد المكان. */
    private void checkClientSlot() {
        long max = License.current.maxClients();
        if (max == Entitlements.UNLIMITED) return;
        int n = 0;
        for (ClientBook.Client o : clients()) if (o.active) n++;
        if (n >= max) throw new Entitlements.LimitReached(Entitlements.clientLimitMessage(max));
    }

    public void setCashBoxActive(long id, boolean active) {
        require(Auth.Perm.SETTINGS);
        List<CashBox.Config> boxes = cashBoxes();
        CashBox.Config b = CashBox.Config.find(boxes, id);
        if (b == null || !b.on) throw new IllegalArgumentException("الصندوق غير موجود");
        if (active && !b.active) checkBoxSlot(boxes, id);   // 12.6
        if (!active) {
            int others = 0;
            for (CashBox.Config o : boxes) if (o.active && o.id != id) others++;
            if (others == 0) throw new IllegalStateException("هذا آخر صندوق مفعّل. أضف صندوقًا آخر قبل تعطيله.");
            for (Map.Entry<String, Long> e : cashBalances(id).entrySet())
                if (e.getValue() != 0) throw new IllegalStateException("في الصندوق رصيد " + e.getKey() + ". حوّله إلى صندوق آخر أو سلّمه قبل التعطيل.");
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("UPDATE cash_boxes SET active=" + (active ? 1 : 0) + " WHERE id=" + id);
            audit(d, active ? "CASH_BOX_RESTORE" : "CASH_BOX_ARCHIVE", "cash", id, b.name);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        if (!active && settingLong("cash_current", 1) == id) setCurrentBox(currentBox());
    }

    /**
     * سبب منع حذف الصندوق، أو null إن جاز. يُحذف الصندوق بكل حركاته فقط إن كان رصيده صفرًا وليس فيه ما يرتبط بغيره:
     * حركة لحساب مكتب أو عميل أو تحويل مع صندوق آخر (بأي حالة) — حذفها يكسر حسابًا آخر، فالتعطيل هو الحل حينها.
     */
    public String cashBoxDeleteBlock(long id) {
        List<CashBox.Config> boxes = cashBoxes();
        CashBox.Config b = CashBox.Config.find(boxes, id);
        if (b == null || !b.on) return "الصندوق غير موجود";
        int others = 0;
        for (CashBox.Config o : boxes) if (o.active && o.id != id) others++;
        if (others == 0) return "هذا آخر صندوق مفعّل. أضف صندوقًا آخر قبل حذفه.";
        for (Map.Entry<String, Long> e : cashBalances(id).entrySet())
            if (e.getValue() != 0) return "في الصندوق رصيد " + e.getKey() + ". حوّله إلى صندوق آخر أو سلّمه أولًا.";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM cash_moves WHERE box_id=" + id
                + " AND (office_id IS NOT NULL OR tx_id IS NOT NULL OR client_id IS NOT NULL OR kind='TRANSFER')", null)) {
            if (c.moveToFirst() && c.getLong(0) > 0)
                return "في الصندوق حركات مرتبطة بمكاتب أو عملاء أو بصناديق أخرى (" + c.getLong(0) + "). حذفها يغيّر حسابات أخرى؛ عطّل الصندوق بدل حذفه.";
        }
        return null;
    }

    /** حذف صندوق نهائيًا بحركاته وسجل جرده (بشروط cashBoxDeleteBlock). */
    public void deleteCashBox(long id) {
        require(Auth.Perm.SETTINGS);
        String why = cashBoxDeleteBlock(id);
        if (why != null) throw new IllegalStateException(why);
        CashBox.Config b = cashBox(id);
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            int n = d.delete("cash_moves", "box_id=" + id, null);
            d.delete("cash_counts", "box_id=" + id, null);
            d.delete("cash_boxes", "id=" + id, null);
            audit(d, "CASH_BOX_DELETE", "cash", id, b.name + " — " + n + " حركة");
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        if (settingLong("cash_current", 1) == id) setCurrentBox(currentBox());
    }

    /** بند جديد يضيفه المستخدم لقائمة المصاريف أو الاستلام أو التسليم. */
    public void addCashCategory(String kind, String name) {
        String n = name == null ? "" : name.trim().replace(",", "،");
        if (n.isEmpty()) return;
        String key = CashBox.EXPENSE.equals(kind) ? "cash_cats_exp" : CashBox.IN.equals(kind) ? "cash_cats_in" : "cash_cats_out";
        List<String> l = CashBox.Config.list(setting(key, ""));
        if (!l.contains(n)) l.add(n);
        setSetting(key, CashBox.Config.join(l));
    }

    private long insertCash(SQLiteDatabase d, CashBox.Move m) {
        ContentValues v = new ContentValues();
        v.put("uuid", m.uuid == null || m.uuid.isEmpty() ? UUID.randomUUID().toString() : m.uuid);
        v.put("box_id", m.boxId <= 0 ? 1 : m.boxId);
        v.put("kind", m.kind);
        v.put("currency", m.currency);
        v.put("amount_minor", m.amount);
        v.put("dir", m.dir);
        v.put("fee_minor", m.fee);
        v.put("fee_side", Model.nz(m.feeSide));
        v.put("fee_cash", m.feeInCash ? 1 : 0);
        v.put("category", Model.nz(m.category));
        v.put("party", Crypto.enc(Model.nz(m.party)));
        v.put("description", Crypto.enc(Model.nz(m.description)));
        if (m.officeId != null) v.put("office_id", m.officeId);
        if (m.txId != null) v.put("tx_id", m.txId);
        if (m.clientId != null) v.put("client_id", m.clientId);
        if (m.pairUuid != null) v.put("pair_uuid", m.pairUuid);
        if (m.rate != null) v.put("rate", m.rate);
        v.put("status", CashBox.ACTIVE);
        v.put("created_at", m.createdAt);
        v.put("user_name", Crypto.enc(currentUserName));
        return ins(d, "cash_moves", v);
    }

    /**
     * يسجّل حركة صندوق. المرتبطة بمكتب تُسجَّل معها حركة مكتب عادية في المعاملة نفسها (بكل شروطها: الصلاحية والحد والإقفال
     * والاعتماد): الاستلام لحساب مكتب = «علينا» للمكتب، والتسليم لحسابه = «لنا»، وأجرتها على حساب المكتب ما لم تُقبض نقدًا.
     * البيع والشراء سطران مرتبطان (العملة ومقابلها)، والتحويل بين صندوقين سطران مرتبطان. يرجع رقم حركة الصندوق.
     */
    public long addCashMove(CashBox.Input in) throws IOException {
        require(Auth.Perm.CASH);
        // 12.6: المصروف والبيع والشراء والجرد والافتتاحي صلاحيات فوق «الصندوق»
        if (CashBox.EXPENSE.equals(in.kind)) require(Auth.Perm.CASH_EXPENSE);
        else if (CashBox.EXCHANGE.equals(in.kind)) require(Auth.Perm.CASH_FX);
        else if (CashBox.ADJUST.equals(in.kind) || CashBox.OPENING.equals(in.kind)) require(Auth.Perm.CASH_COUNT);
        CashBox.Config cfg = cashBox(in.boxId);
        if (!cfg.active) throw new IllegalStateException("الصندوق «" + cfg.name + "» معطّل");
        String err = CashBox.validate(in, cfg, Clock.now());
        if (err != null) throw new IllegalArgumentException(err);
        ClientBook.Client client = null;
        if (in.clientId != null) {
            client = client(in.clientId);
            if (client == null) throw new IllegalArgumentException("العميل غير موجود");
            if (!client.active) throw new IllegalStateException("حساب العميل «" + client.name + "» مؤرشف");
            if (decimalsMap().get(in.clientCurrency) == null) throw new IllegalArgumentException("عملة حساب العميل غير معرّفة");
        }
        CashBox.Config dest = null;
        if (CashBox.TRANSFER.equals(in.kind)) {
            dest = cashBox(in.toBox);
            if (!dest.active) throw new IllegalStateException("الصندوق «" + dest.name + "» معطّل");
            if (!dest.currencies.contains(in.currency)) throw new IllegalArgumentException("العملة " + in.currency + " غير مفعّلة في «" + dest.name + "». أضفها من إعداداته.");
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            checkLock(d, in.when, "تاريخ الحركة يقع داخل فترة محاسبية مقفلة");
            Long txId = null;
            boolean feeCash = in.officeId == null || in.feeInCash;
            if (in.officeId != null) {
                TxInput t = new TxInput();
                t.office = in.officeId;
                t.type = CashBox.IN.equals(in.kind) ? TYPE_CREDIT : TYPE_DEBIT;
                t.amount = in.amount;
                t.currency = in.currency;
                t.person = Model.nz(in.party);
                t.description = Model.nz(in.description);
                t.service = "الصندوق";
                t.when = in.when;
                if (in.fee > 0 && !feeCash) {
                    t.feeType = CashBox.FEE_US.equals(in.feeSide) ? FEE_US : FEE_THEM;
                    t.fee = in.fee;
                    t.feeCurrency = in.currency;
                }
                txId = addTx(t, null);
            }
            CashBox.Move m = new CashBox.Move();
            m.kind = in.kind;
            m.boxId = in.boxId;
            m.currency = in.currency;
            m.amount = in.amount;
            m.dir = in.dir();
            m.fee = in.fee;
            m.feeSide = in.fee > 0 ? in.feeSide : "";
            m.feeInCash = feeCash;
            boolean buy = CashBox.BUY.equals(in.side);
            m.category = in.officeId != null ? (CashBox.IN.equals(in.kind) ? CashBox.CAT_OFFICE_IN : CashBox.CAT_OFFICE_OUT)
                    : client != null ? (CashBox.IN.equals(in.kind) ? CashBox.CAT_CLIENT_IN : CashBox.CAT_CLIENT_OUT)
                    : CashBox.EXCHANGE.equals(in.kind) ? (buy ? CashBox.CAT_BUY : CashBox.CAT_SELL)
                    : CashBox.TRANSFER.equals(in.kind) ? CashBox.CAT_TRANSFER
                    : CashBox.ADJUST.equals(in.kind) ? CashBox.CAT_COUNT : Model.nz(in.category).trim();
            m.party = in.party;
            m.description = in.description;
            m.officeId = in.officeId;
            m.txId = txId;
            m.createdAt = in.when;
            long id;
            if (client != null) {
                // حساب العميل: الاستلام منه = علينا له، والتسليم له = لنا عليه؛ وقد يُقيَّد بعملة غير عملة النقد بسعر محفوظ
                m.clientId = client.id;
                if (Model.nz(m.party).trim().isEmpty()) m.party = client.name;
                m.uuid = UUID.randomUUID().toString();
                boolean cross = !in.clientCurrency.equals(in.currency);
                if (cross) m.rate = ClientBook.rateOf(in.clientAmount, decimals(in.clientCurrency), in.amount, decimals(in.currency));
                ClientBook.Move cm = new ClientBook.Move();
                cm.clientId = client.id;
                cm.kind = ClientBook.CASH;
                cm.currency = in.clientCurrency;
                cm.amount = in.clientAmount;
                cm.dir = CashBox.IN.equals(in.kind) ? -1 : 1;
                cm.cashUuid = m.uuid;
                cm.rate = cross ? m.rate : null;
                cm.description = in.description;
                cm.createdAt = in.when;
                insertClientMove(d, cm);
            }
            if (CashBox.EXCHANGE.equals(in.kind)) {
                // البيع: العملة تخرج ومقابلها يدخل؛ الشراء: العملة تدخل ومقابلها يخرج
                String pair = UUID.randomUUID().toString();
                m.pairUuid = pair;
                m.rate = Model.nz(in.rate);
                m.dir = buy ? 1 : -1;
                id = insertCash(d, m);
                CashBox.Move b = new CashBox.Move();
                b.kind = CashBox.EXCHANGE;
                b.boxId = in.boxId;
                b.currency = in.toCurrency;
                b.amount = in.toAmount;
                b.dir = -m.dir;
                b.category = m.category;
                b.party = in.party;
                b.description = in.description;
                b.pairUuid = pair;
                b.rate = Model.nz(in.rate);
                b.createdAt = in.when;
                insertCash(d, b);
            } else if (dest != null) {
                String pair = UUID.randomUUID().toString();
                m.pairUuid = pair;
                m.dir = -1;
                m.party = "إلى: " + dest.name;
                id = insertCash(d, m);
                CashBox.Move b = new CashBox.Move();
                b.kind = CashBox.TRANSFER;
                b.boxId = dest.id;
                b.currency = in.currency;
                b.amount = in.amount;
                b.dir = 1;
                b.category = CashBox.CAT_TRANSFER;
                b.party = "من: " + cfg.name;
                b.description = in.description;
                b.pairUuid = pair;
                b.createdAt = in.when;
                insertCash(d, b);
            } else id = insertCash(d, m);
            String what = CashBox.EXCHANGE.equals(in.kind) ? CashBox.exchangeText(in, decimalsMap())
                    : Model.fmt(in.amount, decimals(in.currency)) + " " + in.currency + (dest != null ? " — إلى " + dest.name : "")
                    + (client != null ? " — عميل: " + client.name + (in.clientCurrency.equals(in.currency) ? "" : " (" + Model.fmt(in.clientAmount, decimals(in.clientCurrency)) + " " + in.clientCurrency + ")") : "");
            audit(d, "CASH_" + (CashBox.EXCHANGE.equals(in.kind) ? (buy ? "BUY" : "SELL") : in.kind), "cash", id, what
                    + (txId != null ? " — حركة مكتب " + txId : "") + (in.fee > 0 ? " — أجور " + Model.fmt(in.fee, decimals(in.currency)) : ""));
            d.setTransactionSuccessful();
            return id;
        } finally {
            d.endTransaction();
        }
    }

    /** إلغاء حركة صندوق (وحركة المكتب المرتبطة بها، والساق الأخرى للبيع والشراء أو التحويل). */
    public void cancelCashMove(long id, String reason) {
        require(Auth.Perm.TX_CANCEL);
        CashBox.Move m = cashMove(id);
        if (m == null) throw new IllegalArgumentException("حركة الصندوق غير موجودة");
        if (m.cancelled()) throw new IllegalStateException("هذه الحركة ملغاة أصلًا");
        if (m.txId != null && Model.STATUS_PENDING.equals(m.txStatus))
            throw new IllegalStateException("حركة المكتب المرتبطة بانتظار الاعتماد: ارفضها من «بانتظار الاعتماد» فلا تُحسب في الصندوق.");
        SQLiteDatabase d = getWritableDatabase();
        long now = Clock.now();
        d.beginTransaction();
        try {
            checkLock(d, m.createdAt, "الحركة ضمن فترة محاسبية مقفلة");
            ContentValues v = new ContentValues();
            v.put("status", CashBox.CANCELLED);
            v.put("cancel_reason", Crypto.enc(Model.nz(reason)));
            v.put("cancelled_at", now);
            if (m.pairUuid != null && !m.pairUuid.isEmpty()) d.update("cash_moves", v, "pair_uuid=? AND status='ACTIVE'", new String[]{m.pairUuid});
            else d.update("cash_moves", v, "id=" + id + " AND status='ACTIVE'", null);
            // 9.0.1: حركة حساب العميل المرتبطة تُلغى معها
            d.update("client_moves", v, "cash_uuid=? AND status='ACTIVE'", new String[]{m.uuid});
            // حركة المكتب المرتبطة تُلغى معها بقيد عكسي (لا يبقى على المكتب مبلغ لم يمر بالصندوق)
            if (m.txId != null && Model.STATUS_ACTIVE.equals(m.txStatus)) cancelTx(m.txId, "إلغاء من الصندوق: " + Model.nz(reason));
            audit(d, "CASH_CANCEL", "cash", id, Model.nz(reason));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** جرد الصندوق المفتوح (متوافق مع 9.0): يرجع عدد التسويات. */
    public int cashCount(Map<String, Long> counted, String note) {
        CashBox.Count c = cashCount(currentBox(), counted, note, null);
        return (int) c.adjusted;
    }

    /**
     * 9.0.1: جرد صندوق: المبالغ المعدودة فعلًا لكل عملة. قبل التسويات تُسجَّل عمليات البيع التي أكّد المستخدم أنها لم تُسجَّل
     * (زيادة عملة يقابلها عجز أخرى)، ثم يُسجَّل ما بقي من فرق «تسوية جرد» ليطابق الصندوق الواقع.
     * ويُحفظ الجرد كاملًا في سجل الجرد (المسجّل والمعدود لكل عملة قبل أي تسوية، والصافي بالدولار).
     */
    public CashBox.Count cashCount(long boxId, Map<String, Long> counted, String note, List<CashBox.Suggestion> asSales) {
        require(Auth.Perm.CASH);
        require(Auth.Perm.CASH_COUNT);   // 12.6
        CashBox.Config cfg = cashBox(boxId);
        // 10.5 (AUDIT-028): المتوقع في الصندوق = الرصيد المحسوب + نقد حركات المكاتب المعلّقة (موجود فعلًا ولم يُعتمد بعد)،
        // فلا تُسجَّل له «تسوية جرد» ثم يُحسب مرة ثانية عند اعتماده
        Map<String, Long> bal = cashExpected(boxId);
        Map<String, Long> pend = cashPending(boxId);
        Map<String, Integer> decs = decimalsMap();
        List<CashBox.CountLine> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : counted.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) continue;
            Long b = bal.get(e.getKey()), pp = pend.get(e.getKey());
            lines.add(new CashBox.CountLine(e.getKey(), b == null ? 0 : b, e.getValue(), pp == null ? 0 : pp));
        }
        String net = CashBox.netUsd(lines, usdRates(), decs, null).toPlainString();
        SQLiteDatabase d = getWritableDatabase();
        long now = Clock.now();
        CashBox.Count out = new CashBox.Count();
        StringBuilder sold = new StringBuilder();
        d.beginTransaction();
        try {
            if (asSales != null) for (CashBox.Suggestion s : asSales) {
                CashBox.Input in = new CashBox.Input();
                in.kind = CashBox.EXCHANGE;
                in.side = CashBox.SELL;
                in.boxId = boxId;
                in.currency = s.soldCur;
                in.amount = s.sold;
                in.toCurrency = s.gotCur;
                in.toAmount = s.got;
                in.rate = s.rate.toPlainString();
                in.when = now;
                in.description = "من الجرد: بيع لم يُسجَّل";
                String err = CashBox.validate(in, cfg, now + 1);
                if (err != null) throw new IllegalArgumentException(err);
                try {
                    addCashMove(in);
                } catch (IOException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                if (sold.length() > 0) sold.append("، ");
                sold.append(CashBox.exchangeText(in, decs, true));
            }
            Map<String, Long> after = cashExpected(boxId);
            int n = 0;
            for (CashBox.CountLine l : lines) {
                Long b = after.get(l.currency);
                long rec = b == null ? 0 : b;
                long diff = l.counted - rec;
                if (diff == 0) continue;
                CashBox.Move m = new CashBox.Move();
                m.kind = CashBox.ADJUST;
                m.boxId = boxId;
                m.currency = l.currency;
                m.amount = Math.abs(diff);
                m.dir = diff > 0 ? 1 : -1;
                m.category = CashBox.CAT_COUNT;
                m.description = "جرد: المعدود " + Model.fmt(l.counted, dec(decs, l.currency)) + " والمسجّل " + Model.fmt(rec, dec(decs, l.currency))
                        + (l.pending == 0 ? "" : " (منه معلّق بانتظار الاعتماد " + Model.fmt(l.pending, dec(decs, l.currency)) + ")")
                        + (note == null || note.trim().isEmpty() ? "" : " — " + note.trim());
                m.createdAt = now;
                insertCash(d, m);
                n++;
            }
            ContentValues v = new ContentValues();
            v.put("uuid", UUID.randomUUID().toString());
            v.put("box_id", boxId);
            v.put("created_at", now);
            v.put("user_name", Crypto.enc(currentUserName));
            v.put("note", Crypto.enc(Model.nz(note).trim()));
            v.put("lines", CashBox.linesJson(lines));
            v.put("net_usd", net);
            v.put("adjusted", n);
            v.put("sales", Crypto.enc(sold.toString()));
            out.id = ins(d, "cash_counts", v);
            d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('cash_last_count',?)", new Object[]{"" + now});
            audit(d, "CASH_COUNT", "cash", out.id, cfg.name + " — " + n + " تسوية" + (sold.length() > 0 ? " — بيع: " + sold : ""));
            d.setTransactionSuccessful();
            out.adjusted = n;
        } finally {
            d.endTransaction();
        }
        out.boxId = boxId;
        out.createdAt = now;
        out.note = Model.nz(note).trim();
        out.netUsd = net;
        out.sales = sold.toString();
        out.lines.addAll(lines);
        return out;
    }

    private static int dec(Map<String, Integer> decs, String c) {
        Integer v = decs.get(c);
        return v == null ? 2 : v;
    }

    /** سجل الجرد لصندوق (الأحدث أولًا). */
    public List<CashBox.Count> cashCounts(long boxId) {
        List<CashBox.Count> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,box_id,created_at,user_name,note,lines,net_usd,adjusted,sales FROM cash_counts WHERE box_id=" + boxId
                + " ORDER BY created_at DESC,id DESC", null)) {
            while (c.moveToNext()) {
                CashBox.Count x = new CashBox.Count();
                x.id = c.getLong(0);
                x.boxId = c.getLong(1);
                x.createdAt = c.getLong(2);
                x.userName = shown(Model.nz(Crypto.dec(c.getString(3))));
                x.note = Model.nz(Crypto.dec(c.getString(4)));
                x.lines.addAll(CashBox.linesFrom(c.getString(5)));
                x.netUsd = Model.nz(c.getString(6));
                x.adjusted = c.isNull(7) ? 0 : c.getLong(7);
                x.sales = Model.nz(Crypto.dec(c.getString(8)));
                out.add(x);
            }
        }
        return out;
    }

    // ===================================================================================
    // 9.0.1: حسابات العملاء
    // ===================================================================================

    private void insertClientMove(SQLiteDatabase d, ClientBook.Move m) {
        if (m.amount <= 0) throw new IllegalArgumentException("المبلغ يجب أن يكون أكبر من صفر");
        ContentValues v = new ContentValues();
        v.put("uuid", UUID.randomUUID().toString());
        v.put("client_id", m.clientId);
        v.put("kind", m.kind);
        v.put("currency", m.currency);
        v.put("amount_minor", m.amount);
        v.put("dir", m.dir);
        if (m.cashUuid != null) v.put("cash_uuid", m.cashUuid);
        if (m.pairUuid != null) v.put("pair_uuid", m.pairUuid);
        if (m.rate != null && !m.rate.isEmpty()) v.put("rate", m.rate);
        v.put("description", Crypto.enc(Model.nz(m.description)));
        v.put("status", CashBox.ACTIVE);
        v.put("created_at", m.createdAt);
        v.put("user_name", Crypto.enc(currentUserName));
        ins(d, "client_moves", v);
    }

    private static ClientBook.Client readClient(Cursor c) {
        ClientBook.Client x = new ClientBook.Client();
        x.id = c.getLong(0);
        x.name = Model.nz(Crypto.dec(c.getString(1)));
        x.phone = Model.nz(Crypto.dec(c.getString(2)));
        x.note = Model.nz(Crypto.dec(c.getString(3)));
        x.active = c.getInt(4) == 1;
        x.createdAt = c.getLong(5);
        return x;
    }

    /** كل العملاء (النشطون أولًا ثم أبجديًا). */
    public List<ClientBook.Client> clients() {
        List<ClientBook.Client> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,phone,note,active,created_at FROM cash_clients", null)) {
            while (c.moveToNext()) out.add(readClient(c));
        }
        final java.text.Collator col = java.text.Collator.getInstance(new Locale("ar"));
        Collections.sort(out, (x, y) -> x.active != y.active ? (x.active ? -1 : 1) : col.compare(x.name, y.name));
        return out;
    }

    public ClientBook.Client client(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,phone,note,active,created_at FROM cash_clients WHERE id=" + id, null)) {
            return c.moveToFirst() ? readClient(c) : null;
        }
    }

    /** إضافة عميل أو تعديل بياناته. يرجع رقمه. */
    public long saveClient(ClientBook.Client x) {
        require(Auth.Perm.CLIENTS);   // 12.0
        String n = Model.nz(x.name).trim();
        if (n.isEmpty()) throw new IllegalArgumentException("اكتب اسم العميل");
        for (ClientBook.Client o : clients())
            if (o.id != x.id && o.name.trim().equalsIgnoreCase(n)) throw new IllegalArgumentException("يوجد عميل بالاسم نفسه: «" + o.name + "»");
        if (x.id <= 0) checkClientSlot();   // 12.6
        SQLiteDatabase d = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("name", Crypto.enc(n));
        v.put("phone", Crypto.enc(Model.nz(x.phone).trim()));
        v.put("note", Crypto.enc(Model.nz(x.note).trim()));
        d.beginTransaction();
        try {
            long id = x.id;
            if (id > 0) {
                if (d.update("cash_clients", v, "id=" + id, null) == 0) throw new IllegalArgumentException("العميل غير موجود");
            } else {
                v.put("uuid", UUID.randomUUID().toString());
                v.put("active", 1);
                v.put("created_at", Clock.now());
                id = ins(d, "cash_clients", v);
            }
            audit(d, x.id > 0 ? "CLIENT_EDIT" : "CLIENT_ADD", "client", id, n);
            d.setTransactionSuccessful();
            return id;
        } finally {
            d.endTransaction();
        }
    }

    /** أرشفة حساب عميل (رصيده صفر بكل العملات) أو إعادته. */
    public void setClientActive(long id, boolean active) {
        require(Auth.Perm.CLIENTS);   // 12.0
        ClientBook.Client c = client(id);
        if (c == null) throw new IllegalArgumentException("العميل غير موجود");
        if (active && !c.active) checkClientSlot();   // 12.6
        if (!active && !ClientBook.balances(clientMoves(id)).isEmpty())
            throw new IllegalStateException("رصيد العميل ليس صفرًا. سدّد حسابه أولًا ثم أرشفه.");
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            d.execSQL("UPDATE cash_clients SET active=" + (active ? 1 : 0) + " WHERE id=" + id);
            audit(d, active ? "CLIENT_RESTORE" : "CLIENT_ARCHIVE", "client", id, c.name);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    private static final String CLIENT_COLS = "m.id,m.uuid,m.client_id,m.kind,m.currency,m.amount_minor,m.dir,m.cash_uuid,m.pair_uuid,m.rate,m.description,m.status,"
            + "m.created_at,m.user_name,m.cancel_reason,x.currency,x.amount_minor,x.dir";

    private List<ClientBook.Move> readClientMoves(String where) {
        List<ClientBook.Move> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + CLIENT_COLS + " FROM client_moves m LEFT JOIN cash_moves x ON x.uuid=m.cash_uuid"
                + where + " ORDER BY m.created_at,m.id", null)) {
            while (c.moveToNext()) {
                ClientBook.Move m = new ClientBook.Move();
                m.id = c.getLong(0);
                m.uuid = c.getString(1);
                m.clientId = c.getLong(2);
                m.kind = c.getString(3);
                m.currency = c.getString(4);
                m.amount = c.getLong(5);
                m.dir = c.getInt(6);
                m.cashUuid = c.isNull(7) ? null : c.getString(7);
                m.pairUuid = c.isNull(8) ? null : c.getString(8);
                m.rate = c.isNull(9) ? null : c.getString(9);
                m.description = Model.nz(Crypto.dec(c.getString(10)));
                m.status = c.getString(11);
                m.createdAt = c.getLong(12);
                m.userName = shown(Model.nz(Crypto.dec(c.getString(13))));
                m.cancelReason = Model.nz(Crypto.dec(c.getString(14)));
                m.cashCurrency = c.isNull(15) ? null : c.getString(15);
                m.cashAmount = c.isNull(16) ? 0 : c.getLong(16);
                m.cashDir = c.isNull(17) ? 0 : c.getInt(17);
                out.add(m);
            }
        }
        return out;
    }

    public List<ClientBook.Move> clientMoves(long clientId) { return readClientMoves(" WHERE m.client_id=" + clientId); }

    public List<ClientBook.Move> allClientMoves() { return readClientMoves(""); }

    /** رصيد كل عميل بكل عملة. */
    public Map<Long, Map<String, Long>> clientBalances() { return ClientBook.allBalances(allClientMoves()); }

    /** قيد على حساب العميل بلا نقد (رصيد سابق أو تسوية متفق عليها): dir +1 لنا عليه، −1 علينا له. */
    public void addClientEntry(long clientId, String currency, long amount, int dir, String description, long when) {
        require(Auth.Perm.CLIENTS);   // 12.0
        ClientBook.Client c = client(clientId);
        if (c == null) throw new IllegalArgumentException("العميل غير موجود");
        if (!c.active) throw new IllegalStateException("حساب العميل مؤرشف");
        if (decimalsMap().get(currency) == null) throw new IllegalArgumentException("العملة غير معرّفة");
        if (amount <= 0) throw new IllegalArgumentException("المبلغ يجب أن يكون أكبر من صفر");
        if (dir != 1 && dir != -1) throw new IllegalArgumentException("حدّد: لنا أم علينا");
        if (when <= 0 || when > Clock.now() + 10 * 60000L) throw new IllegalArgumentException("تاريخ غير صالح");
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            checkLock(d, when, "تاريخ الحركة يقع داخل فترة محاسبية مقفلة");
            ClientBook.Move m = new ClientBook.Move();
            m.clientId = clientId;
            m.kind = ClientBook.ENTRY;
            m.currency = currency;
            m.amount = amount;
            m.dir = dir;
            m.description = description;
            m.createdAt = when;
            insertClientMove(d, m);
            audit(d, "CLIENT_ENTRY", "client", clientId, c.name + " — " + ClientBook.side(dir) + " " + Model.fmt(amount, decimals(currency)) + " " + currency);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /**
     * تبديل عملة الرصيد بلا نقد: يُطفأ amount من رصيد العميل بعملة from ويُنشأ مقابله toAmount بعملة to، بالجهة نفسها
     * (إن كان له عندنا يبقى له، وإن كان علينا يبقى علينا).
     */
    public void addClientSwap(long clientId, String from, long amount, String to, long toAmount, String description, long when) {
        require(Auth.Perm.CLIENTS);   // 12.0
        ClientBook.Client c = client(clientId);
        if (c == null) throw new IllegalArgumentException("العميل غير موجود");
        if (!c.active) throw new IllegalStateException("حساب العميل مؤرشف");
        if (decimalsMap().get(to) == null) throw new IllegalArgumentException("العملة غير معرّفة");
        Map<String, Long> bal = ClientBook.balances(clientMoves(clientId));
        String err = ClientBook.validateSwap(bal, from, amount, to, toAmount);
        if (err != null) throw new IllegalArgumentException(err);
        if (when <= 0 || when > Clock.now() + 10 * 60000L) throw new IllegalArgumentException("تاريخ غير صالح");
        int sideNow = bal.get(from) > 0 ? 1 : -1;
        String rate = ClientBook.rateOf(amount, decimals(from), toAmount, decimals(to));
        String auto = "تبديل " + Model.fmt(amount, decimals(from)) + " " + from + " إلى " + Model.fmt(toAmount, decimals(to)) + " " + to + " (1 " + from + " = " + rate + " " + to + ")";
        String desc = Model.nz(description).trim().isEmpty() ? auto : auto + " — " + description.trim();
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            checkLock(d, when, "تاريخ الحركة يقع داخل فترة محاسبية مقفلة");
            String pair = UUID.randomUUID().toString();
            ClientBook.Move a = new ClientBook.Move();
            a.clientId = clientId;
            a.kind = ClientBook.SWAP;
            a.currency = from;
            a.amount = amount;
            a.dir = -sideNow;
            a.pairUuid = pair;
            a.rate = rate;
            a.description = desc;
            a.createdAt = when;
            insertClientMove(d, a);
            ClientBook.Move b = new ClientBook.Move();
            b.clientId = clientId;
            b.kind = ClientBook.SWAP;
            b.currency = to;
            b.amount = toAmount;
            b.dir = sideNow;
            b.pairUuid = pair;
            b.rate = rate;
            b.description = desc;
            b.createdAt = when;
            insertClientMove(d, b);
            audit(d, "CLIENT_SWAP", "client", clientId, c.name + " — " + auto);
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** إلغاء حركة على حساب عميل: النقدية تُلغى مع حركة الصندوق، والتبديل بساقيه. */
    public void cancelClientMove(long id, String reason) {
        require(Auth.Perm.TX_CANCEL);
        ClientBook.Move m = null;
        for (ClientBook.Move x : readClientMoves(" WHERE m.id=" + id)) m = x;
        if (m == null) throw new IllegalArgumentException("الحركة غير موجودة");
        if (m.cancelled()) throw new IllegalStateException("هذه الحركة ملغاة أصلًا");
        if (m.cashUuid != null) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM cash_moves WHERE uuid=?", new String[]{m.cashUuid})) {
                if (!c.moveToFirst()) throw new IllegalStateException("حركة الصندوق المرتبطة غير موجودة");
                cancelCashMove(c.getLong(0), reason);
                return;
            }
        }
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            checkLock(d, m.createdAt, "الحركة ضمن فترة محاسبية مقفلة");
            ContentValues v = new ContentValues();
            v.put("status", CashBox.CANCELLED);
            v.put("cancel_reason", Crypto.enc(Model.nz(reason)));
            v.put("cancelled_at", Clock.now());
            if (m.pairUuid != null && !m.pairUuid.isEmpty()) d.update("client_moves", v, "pair_uuid=? AND status='ACTIVE'", new String[]{m.pairUuid});
            else d.update("client_moves", v, "id=" + id + " AND status='ACTIVE'", null);
            audit(d, "CLIENT_CANCEL", "client", m.clientId, Model.nz(reason));
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    private static final String CASH_COLS = "m.id,m.uuid,m.kind,m.currency,m.amount_minor,m.dir,m.fee_minor,m.fee_side,m.fee_cash,m.category,m.party,m.description,"
            + "m.office_id,m.tx_id,t.status,m.pair_uuid,m.rate,m.status,m.created_at,m.user_name,m.cancel_reason,m.box_id,m.client_id";

    private CashBox.Move readCash(Cursor c) {
        CashBox.Move m = new CashBox.Move();
        m.id = c.getLong(0);
        m.uuid = c.getString(1);
        m.kind = c.getString(2);
        m.currency = c.getString(3);
        m.amount = c.getLong(4);
        m.dir = c.getInt(5);
        m.fee = c.getLong(6);
        m.feeSide = Model.nz(c.getString(7));
        m.feeInCash = c.getInt(8) == 1;
        m.category = Model.nz(c.getString(9));
        m.party = Model.nz(Crypto.dec(c.getString(10)));
        m.description = Model.nz(Crypto.dec(c.getString(11)));
        m.officeId = c.isNull(12) ? null : c.getLong(12);
        m.txId = c.isNull(13) ? null : c.getLong(13);
        m.txStatus = c.isNull(14) ? (m.txId == null ? null : "CANCELLED") : c.getString(14);
        m.pairUuid = c.isNull(15) ? null : c.getString(15);
        m.rate = c.isNull(16) ? null : c.getString(16);
        m.status = c.getString(17);
        m.createdAt = c.getLong(18);
        m.userName = shown(Model.nz(Crypto.dec(c.getString(19))));
        m.cancelReason = Model.nz(Crypto.dec(c.getString(20)));
        m.boxId = c.isNull(21) ? 1 : c.getLong(21);
        m.clientId = c.isNull(22) ? null : c.getLong(22);
        return m;
    }

    /** حركات الصندوق المفتوح بترتيب زمني (مع حالة حركة المكتب المرتبطة). */
    public List<CashBox.Move> cashMoves() { return cashMoves(currentBox()); }

    /** حركات صندوق برقمه. */
    public List<CashBox.Move> cashMoves(long boxId) {
        List<CashBox.Move> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + CASH_COLS + " FROM cash_moves m LEFT JOIN tx t ON t.id=m.tx_id WHERE m.box_id=" + boxId
                + " ORDER BY m.created_at,m.id", null)) {
            while (c.moveToNext()) out.add(readCash(c));
        }
        return out;
    }

    public CashBox.Move cashMove(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + CASH_COLS + " FROM cash_moves m LEFT JOIN tx t ON t.id=m.tx_id WHERE m.id=" + id, null)) {
            return c.moveToFirst() ? readCash(c) : null;
        }
    }

    /** رصيد الصندوق لكل عملة (بالحساب نفسه في CashBox، مع عملات الصندوق بصفر). */
    public Map<String, Long> cashBalances() { return cashBalances(currentBox()); }

    public Map<String, Long> cashBalances(long boxId) {
        CashBox.Config c = CashBox.Config.find(cashBoxes(), boxId);
        return CashBox.balances(cashMoves(boxId), c == null ? null : c.currencies);
    }

    /** 10.5: نقد حركات المكاتب المعلّقة في الصندوق لكل عملة (موجود فعلًا، غير محسوب حتى الاعتماد). */
    public Map<String, Long> cashPending(long boxId) { return CashBox.pendingCash(cashMoves(boxId)); }

    /** 10.5: ما يجب أن يكون في الصندوق فعلًا الآن: الرصيد المحسوب + النقد المعلّق (أساس الجرد). */
    public Map<String, Long> cashExpected(long boxId) {
        Map<String, Long> m = new TreeMap<>(cashBalances(boxId));
        for (Map.Entry<String, Long> e : cashPending(boxId).entrySet()) m.merge(e.getKey(), e.getValue(), Long::sum);
        return m;
    }

    /** هل الحركة مسجّلة من الصندوق (لمنع تعديلها من جهة المكتب)؟ */
    public boolean cashLinked(long txId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM cash_moves WHERE tx_id=" + txId + " AND status='ACTIVE' LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    /** أرقام حركات المكاتب المسجّلة من الصندوق (لإظهار علامة «من الصندوق» في السجل والكشف). */
    public Set<Long> cashLinkedTxIds() {
        Set<Long> s = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT tx_id FROM cash_moves WHERE tx_id IS NOT NULL AND status='ACTIVE'", null)) {
            while (c.moveToNext()) s.add(c.getLong(0));
        }
        return s;
    }

    // ===================================================================================
    // 9.0: الإشعارات: مكاتب بلا حركات منذ يوم كامل، ومكاتب لم تُرسل لها مطابقة
    // ===================================================================================

    /** المكاتب النشطة التي سُجّل عليها في آخر 30 يومًا ولم يُسجّل عليها شيء منذ أكثر من يوم. */
    public List<Model.Office> idleOffices(long now) {
        Set<Long> off = new HashSet<>();
        for (String x : setting("notif_idle_off", "").split(",")) try {
            off.add(Long.parseLong(x.trim()));
        } catch (NumberFormatException ignored) {
            // تجاهل
        }
        Map<Long, long[]> use = officeUsage();
        List<Model.Office> out = new ArrayList<>();
        for (Model.Office o : offices()) {
            if (!o.active || off.contains(o.id)) continue;
            long[] u = use.get(o.id);
            if (u == null || u[2] <= 0) continue;
            if (now - u[2] > 86400000L && now - u[2] < 30L * 86400000L) out.add(o);
        }
        return out;
    }

    /** المكاتب النشطة ذات الرصيد غير الصفري التي لم تُرسل لها مطابقة منذ days يومًا. */
    public List<Model.Office> reconDue(long now, int days) {
        Set<Long> off = new HashSet<>();
        for (String x : setting("notif_recon_off", "").split(",")) try {
            off.add(Long.parseLong(x.trim()));
        } catch (NumberFormatException ignored) {
            // تجاهل
        }
        Map<Long, Model.Recon> last = lastReconsByOffice();
        Map<Long, Map<String, Long>> bal = officeBalances();
        List<Model.Office> out = new ArrayList<>();
        for (Model.Office o : offices()) {
            if (!o.active || off.contains(o.id)) continue;
            Map<String, Long> b = bal.get(o.id);
            boolean nonZero = false;
            if (b != null) for (long v : b.values()) if (v != 0) nonZero = true;
            if (!nonZero) continue;
            Model.Recon r = last.get(o.id);
            long since = r == null ? 0 : r.createdAt;
            if (now - since > days * 86400000L) out.add(o);
        }
        return out;
    }

    /** تشغيل/إيقاف تنبيه نوع معيّن لمكتب واحد (key: notif_idle_off أو notif_recon_off). */
    public void setOfficeNotif(String key, long officeId, boolean on) {
        List<String> l = new ArrayList<>();
        for (String x : setting(key, "").split(",")) if (!x.trim().isEmpty() && !x.trim().equals("" + officeId)) l.add(x.trim());
        if (!on) l.add("" + officeId);
        setSettingQuiet(key, Model.join(l, ","));
    }

    public boolean officeNotif(String key, long officeId) {
        for (String x : setting(key, "").split(",")) if (x.trim().equals("" + officeId)) return false;
        return true;
    }

    // ===================================================================================
    // الاستعادة
    // ===================================================================================

    public void importBackup(BackupFormat.Parsed p) throws Exception { importBackup(p, null); }

    /**
     * 10.13: عند الاستعادة: true = تبقى تفضيلات المظهر الحالية لكل المستخدمين (الألوان، الوضع، الخط، الشريط، الرئيسية…)
     * وتُتجاهل تفضيلات النسخة؛ false (الافتراضي) = تُستعاد مع النسخة. البيانات المالية تُستعاد كاملة في الحالتين.
     */
    public boolean restoreKeepLook;

    /**
     * keep: المكاتب التي تُستعاد من النسخة (null = كلها). 9.0: النسخة المجانية تختار حتى 9 مكاتب من نسخة فيها أكثر:
     * تُستعاد النسخة كاملة داخل المعاملة ثم يُحذف ما لم يُختر بكل ما يخصه، فتبقى القيود متوازنة والأرصدة مطابقة للنسخة.
     */
    public void importBackup(BackupFormat.Parsed p, Set<Long> keep) throws Exception {
        require(Auth.Perm.RESTORE);
        // 10.16: نسخة من إصدار أحدث لا تُستعاد بتطبيق أقدم (كانت أعمدتها الجديدة تُتجاوز بصمت فتضيع بياناتها)
        if (p.schema() > VER) throw new IllegalStateException("هذه النسخة أُخذت بإصدار أحدث من هذا التطبيق (مخطط " + p.schema() + "، وهذا الإصدار يعرف حتى "
                + VER + "). حدّث التطبيق أولًا ثم استعدها؛ لم يُغيَّر شيء.");
        SQLiteDatabase d = getWritableDatabase();
        File dir = evidenceDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("تعذر إنشاء مجلد الصور");
        Map<String, Set<String>> cols = new HashMap<>();
        for (String t : BackupFormat.TABLES) cols.put(t, columnsOf(d, t));
        Map<String, String> keepDevice = new HashMap<>();
        for (String k : DEVICE_SETTINGS) {
            String v = rawSetting(d, k);
            if (v != null) keepDevice.put(k, v);
        }
        final boolean keepLook = restoreKeepLook;
        if (keepLook) try (Cursor c = d.rawQuery("SELECT k,v FROM settings", null)) {
            while (c.moveToNext()) if (personal(baseKey(c.getString(0))) && c.getString(1) != null) keepDevice.put(c.getString(0), c.getString(1));
        }
        boolean hasUsersTable = p.tables.containsKey("users");
        // نسخة بلا مستخدمين (ملف نقل): مستخدمو الهاتف يبقون ومعهم سرّ رموزهم
        if (!hasUsersTable) for (String k : ENCRYPTED_SETTINGS) {
            String v = rawSetting(d, k);
            if (v != null) keepDevice.put(k, v);
        }
        List<File[]> moved = new ArrayList<>();
        d.beginTransaction();
        try {
            // 8.0: الإقفال المحاسبي لا يُفك بالاستعادة (يبقى الأحدث بين الحالي والمستعاد)، وسجل العمليات الحالي يُؤرشف لا يُحذف
            long lockNow = lockBefore(d);
            // 10.0: الاستعادة تستبدل البيانات: يخرج هذا الجهاز من المزامنة (ينضم من جديد من «المزامنة»)
            if (!restoringForSync) {
                SyncStore.leave(d, "استُعيدت نسخة احتياطية على هذا الجهاز");
                syncPrefix = 0;
            }
            d.execSQL("INSERT INTO audit_archive(orig_id,action,entity,entity_id,user_name,reason,created_at,prev_hash,row_hash,archived_at) "
                    + "SELECT id,action,entity,entity_id,user_name,reason,created_at,prev_hash,row_hash," + Clock.now() + " FROM audit_logs");
            String[] del = {"notif_log", "office_holds", "admin_msg_state", "admin_msgs", "audit_feed", "office_work", "tx_checks", "wa_marks", "user_offices", "client_moves", "cash_clients", "cash_counts", "cash_moves", "cash_boxes", "reconciliation_lines", "reconciliations", "journal_lines", "journal_entries", "attachments", "opening_balances", "tx", "office_currencies", "offices",
                    "exchange_rates", "audit_logs", "settings", "currencies"};
            for (String t : del) d.delete(t, null, null);
            if (hasUsersTable) d.delete("users", null, null);
            for (String table : BackupFormat.TABLES) {
                if (table.equals("users") && !hasUsersTable) continue;
                Set<String> allowed = cols.get(table);
                List<String> sensitive = SENSITIVE.get(table);
                for (JSONObject row : p.table(table)) {
                    if (table.equals("settings") && DEVICE_SETTINGS.contains(row.optString("k"))) continue;
                    if (keepLook && table.equals("settings") && personal(baseKey(row.optString("k")))) continue;
                    if (table.equals("settings") && ENCRYPTED_SETTINGS.contains(row.optString("k"))) {
                        // سرّ رموز المستخدمين يتبع جدول المستخدمين: بلا مستخدمين في النسخة يبقى سرّ هذا الهاتف
                        if (hasUsersTable && !row.isNull("v")) d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES(?,?)", new Object[]{row.optString("k"), Crypto.enc(row.optString("v"))});
                        continue;
                    }
                    ContentValues v = new ContentValues();
                    Iterator<String> it = row.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (!allowed.contains(k.toLowerCase(Locale.US))) continue;
                        Object val = row.opt(k);
                        if (val == null || val == JSONObject.NULL) v.putNull(k);
                        else if (val instanceof Boolean) v.put(k, ((Boolean) val) ? 1 : 0);
                        else if (val instanceof Double || val instanceof Float || val instanceof BigDecimal) {
                            double dv = ((Number) val).doubleValue();
                            if (dv == Math.rint(dv) && Math.abs(dv) < 9.0E15) v.put(k, (long) dv);
                            else v.put(k, dv);
                        } else if (val instanceof Number) v.put(k, ((Number) val).longValue());
                        else {
                            String sv = String.valueOf(val);
                            if (sensitive != null && sensitive.contains(k)) sv = Crypto.enc(sv);
                            v.put(k, sv);
                        }
                    }
                    d.insertOrThrow(table, null, v);
                }
            }
            for (Map.Entry<String, String> e : keepDevice.entrySet()) {
                d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES(?,?)", new Object[]{e.getKey(), e.getValue()});
            }
            // 10.0: نسخ 9.x صناديقها في الإعدادات: تُنقل إلى جدولها
            migrateBoxes(d);
            for (BackupFormat.StagedAttachment s : p.attachments) {
                File from = (File) s.handle;
                File to = new File(dir, UUID.randomUUID() + ".evd");
                if (!from.renameTo(to)) {
                    copyFile(from, to);
                    from.delete();
                }
                moved.add(new File[]{from, to});
                ContentValues v = new ContentValues();
                v.put("id", s.id);
                v.put("tx_id", s.txId);
                v.put("path", to.getAbsolutePath());
                v.put("mime", s.mime == null ? "image/jpeg" : s.mime);
                v.put("created_at", s.createdAt);
                ins(d, "attachments", v);
            }
            if (p.schema() < 5) {
                d.execSQL("UPDATE office_currencies SET limit_owe_minor=limit_minor WHERE limit_owe_minor IS NULL AND limit_minor IS NOT NULL");
                d.execSQL("UPDATE offices SET usd_owe_limit_minor=usd_limit_minor WHERE usd_owe_limit_minor IS NULL AND usd_limit_minor IS NOT NULL");
            }
            d.execSQL("UPDATE offices SET pinned=0 WHERE pinned IS NULL");
            int dropped = 0;
            if (keep != null) {
                List<Long> all = new ArrayList<>();
                try (Cursor c = d.rawQuery("SELECT id FROM offices", null)) {
                    while (c.moveToNext()) all.add(c.getLong(0));
                }
                for (long id : all) if (!keep.contains(id)) {
                    purgeOffice(d, id);
                    dropped++;
                }
            }
            Entitlements ent = License.current;
            if (!unlimited(ent) && liveOffices(d) > ent.maxOffices()) {
                throw new Entitlements.LimitReached("في النسخة " + liveOffices(d) + " مكتبًا، والنسخة المجانية تتسع لـ " + ent.maxOffices()
                        + ". اختر المكاتب التي تريد استعادتها، أو فعّل النسخة الكاملة.");
            }
            seedDefaults(d);
            rebaseQuota(d);
            boolean keptLock = lockNow > lockBefore(d);
            if (keptLock) d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('lock_before',?)", new Object[]{"" + lockNow});
            audit(d, "RESTORE", "backup", 0, "format=" + p.format + " tx=" + p.table("tx").size() + " att=" + p.attachments.size()
                    + (keptLock ? " lock_kept=" + lockNow : "") + (dropped > 0 ? " offices_kept=" + keep.size() + " dropped=" + dropped : ""));
            d.setTransactionSuccessful();
        } catch (Exception e) {
            for (File[] m : moved) {
                if (!m[1].renameTo(m[0])) m[1].delete();
            }
            throw e;
        } finally {
            d.endTransaction();
        }
        synchronized (this) { decimalsCache.clear(); }
        Crypto.clearCache();
        cleanupEvidence();
    }

    public int cleanupEvidence() {
        File dir = evidenceDir();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        Set<String> used = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path FROM attachments", null)) {
            while (c.moveToNext()) used.add(new File(c.getString(0)).getName());
        }
        int n = 0;
        for (File f : files) if (f.isFile() && !used.contains(f.getName()) && f.delete()) n++;
        return n;
    }

    static void copyFile(File from, File to) throws IOException {
        try (FileInputStream i = new FileInputStream(from); FileOutputStream o = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
            o.getFD().sync();
        }
    }

    // ===================================================================================
    // فحص صحة البيانات
    // ===================================================================================

    public static class Check {
        public final boolean ok;
        public final String title;
        public final String detail;

        Check(boolean ok, String title, String detail) {
            this.ok = ok;
            this.title = title;
            this.detail = detail;
        }
    }

    /**
     * 8.0 (ت1): رصيد كل مكتب وعملة من القيود = أثر الحركات النشطة + الأرصدة السابقة، محسوبًا من جدول الحركات وحده.
     * أي فرق يعني قيدًا ناقصًا أو زائدًا أو حركة ملغاة لم تُعكس. المعاملات: نوع «لنا»، أجرة «لنا»، أجرة «علينا».
     */
    static final String BALANCE_IDENTITY_SQL = "SELECT o||' '||c||' فرق='||SUM(v) FROM ("
            + "SELECT office_id o,currency c,debit_minor-credit_minor v FROM journal_lines WHERE account='OFFICE' "
            + "UNION ALL SELECT office_id,currency,CASE WHEN type=? THEN -amount_minor ELSE amount_minor END FROM tx WHERE status='ACTIVE' "
            + "UNION ALL SELECT office_id,fee_currency,CASE WHEN fee_type=? THEN -fee_minor WHEN fee_type=? THEN fee_minor ELSE 0 END FROM tx WHERE status='ACTIVE' AND fee_minor>0 "
            + "UNION ALL SELECT office_id,currency,CASE WHEN type='لنا' THEN -amount_minor WHEN type='علينا' THEN amount_minor ELSE 0 END FROM opening_balances"
            + ") GROUP BY o,c HAVING SUM(v)<>0";

    public List<Check> healthCheck() { return healthCheck(true); }

    /**
     * full=false للفحص التلقائي في الخلفية: فحص سريع لملف القاعدة، ووجود الصور مع فك آخر 10 فقط.
     */
    public List<Check> healthCheck(boolean full) {
        List<Check> out = new ArrayList<>();
        SQLiteDatabase d = getReadableDatabase();
        String integrity = "";
        try (Cursor c = d.rawQuery(full ? "PRAGMA integrity_check" : "PRAGMA quick_check", null)) {
            List<String> rows = new ArrayList<>();
            while (c.moveToNext() && rows.size() < 5) rows.add(c.getString(0));
            integrity = rows.size() == 1 && "ok".equalsIgnoreCase(rows.get(0)) ? "" : String.valueOf(rows);
        } catch (Exception e) {
            integrity = "تعذر الفحص: " + e.getMessage();
        }
        out.add(new Check(integrity.isEmpty(), "ملف قاعدة البيانات سليم (" + (full ? "فحص كامل" : "فحص سريع") + ")", integrity));
        out.add(sqlCheck(d, "الروابط بين الجداول سليمة", "PRAGMA foreign_key_check", "روابط مكسورة"));
        out.add(sqlCheck(d, "رصيد كل مكتب = مجموع حركاته النشطة وأرصدته السابقة", BALANCE_IDENTITY_SQL, "فروق",
                new String[]{TYPE_DEBIT, FEE_US, FEE_THEM}));
        out.add(sqlCheck(d, "توازن كل القيود (المدين = الدائن لكل عملة)",
                "SELECT entry_id||' ('||currency||')' FROM journal_lines GROUP BY entry_id,currency HAVING SUM(debit_minor)<>SUM(credit_minor)", "قيود غير متوازنة"));
        out.add(sqlCheck(d, "أسطر القيود مرتبطة بقيود موجودة",
                "SELECT l.id FROM journal_lines l LEFT JOIN journal_entries e ON e.id=l.entry_id WHERE e.id IS NULL", "أسطر يتيمة"));
        out.add(sqlCheck(d, "كل حركة لها قيد أصل",
                "SELECT t.id FROM tx t WHERE t.status IN ('ACTIVE','CANCELLED') AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.tx_id=t.id AND e.kind='PRINCIPAL')", "حركات بلا قيد"));
        out.add(sqlCheck(d, "الحركات الملغاة والمعدّلة صافيها صفر لكل حساب",
                "SELECT t.id||' '||l.account||' '||l.currency FROM tx t JOIN journal_entries e ON e.tx_id=t.id JOIN journal_lines l ON l.entry_id=e.id WHERE t.status='CANCELLED' GROUP BY t.id,l.account,l.currency HAVING SUM(l.debit_minor-l.credit_minor)<>0",
                "حركات ملغاة لم تُعكس بالكامل"));
        out.add(sqlCheck(d, "الحركات مرتبطة بمكاتب موجودة",
                "SELECT t.id FROM tx t LEFT JOIN offices o ON o.id=t.office_id WHERE o.id IS NULL", "حركات بلا مكتب"));
        out.add(sqlCheck(d, "مراجع الحركات غير مكررة", "SELECT uuid FROM tx GROUP BY uuid HAVING COUNT(*)>1", "مراجع مكررة"));
        out.add(sqlCheck(d, "عملات الحركات معرّفة",
                "SELECT DISTINCT l.currency FROM journal_lines l LEFT JOIN currencies c ON c.code=l.currency WHERE c.code IS NULL", "عملات غير معرّفة"));
        // 9.0: صندوق المكتب
        out.add(sqlCheck(d, "حركات الصندوق صالحة (مبالغ موجبة واتجاه صحيح)",
                "SELECT id FROM cash_moves WHERE amount_minor<=0 OR dir NOT IN (1,-1) OR fee_minor<0", "حركات غير صالحة"));
        out.add(sqlCheck(d, "حركات الصندوق المرتبطة تطابق حركات مكاتبها (المكتب والمبلغ والعملة والاتجاه)",
                "SELECT m.id FROM cash_moves m LEFT JOIN tx t ON t.id=m.tx_id WHERE m.tx_id IS NOT NULL AND m.status='ACTIVE' AND (t.id IS NULL OR t.office_id<>m.office_id"
                        + " OR t.amount_minor<>m.amount_minor OR t.currency<>m.currency OR (m.dir=1 AND t.type<>?) OR (m.dir=-1 AND t.type<>?) OR t.status='CANCELLED')",
                "حركات صندوق لا تطابق حركة مكتبها", new String[]{TYPE_CREDIT, TYPE_DEBIT}));
        out.add(sqlCheck(d, "ساقا كل صرف عملة في الصندوق موجودتان وبالحالة نفسها",
                "SELECT pair_uuid FROM cash_moves WHERE pair_uuid IS NOT NULL AND pair_uuid<>'' GROUP BY pair_uuid HAVING COUNT(*)<>2 OR COUNT(DISTINCT status)<>1",
                "صرف غير مكتمل"));
        // 10.5: العملية المترابطة مع المكاتب (تبديل عملة أو تحويل بين مكتبين) لا تبقى ساقًا بلا أخرى
        out.add(sqlCheck(d, "ساقا كل تبديل عملة وكل تحويل بين مكتبين موجودتان وبالحالة نفسها",
                "SELECT pair_uuid FROM tx WHERE pair_uuid IS NOT NULL AND pair_uuid<>'' GROUP BY pair_uuid HAVING COUNT(*)<>2 OR COUNT(DISTINCT status)<>1",
                "عملية مترابطة غير مكتملة"));
        // 10.7: حركات بين مكتبين سُجّلت في 10.5–10.6 بالاتجاه المعكوس (طالب الحركة «علينا»): الساق الأولى (طالب الحركة) دائنة
        out.add(sqlCheck(d, "الحركات بين مكتبين باتجاهها الصحيح (طالب الحركة «لنا» والمنفّذ «علينا»)",
                "SELECT t.id FROM tx t WHERE t.fx_rate=? AND t.status IN ('ACTIVE','PENDING') AND t.type=? AND t.id=(SELECT MIN(x.id) FROM tx x WHERE x.pair_uuid=t.pair_uuid)",
                "سُجّلت بالاتجاه القديم المعكوس؛ ألغِ كلًّا منها وسجّلها من جديد", new String[]{Model.TRANSFER_MARK, TYPE_CREDIT}));
        // 10.5 (§7): التحقق على حركات موجودة، ومكاتب المحاسبين موجودة
        out.add(sqlCheck(d, "كل تحقق «تم التحقق» على حركة موجودة",
                "SELECT k.uuid FROM tx_checks k LEFT JOIN tx t ON t.id=k.tx_id WHERE t.id IS NULL", "تحقق بلا حركة"));
        out.add(sqlCheck(d, "مكاتب المحاسبين موجودة",
                "SELECT u.office_id FROM user_offices u LEFT JOIN offices o ON o.id=u.office_id WHERE o.id IS NULL", "مكاتب محذوفة ما زالت مخصصة"));
        // 10.0: كل حركة صندوق في صندوق موجود
        out.add(sqlCheck(d, "حركات الصندوق في صناديق موجودة",
                "SELECT m.id FROM cash_moves m LEFT JOIN cash_boxes b ON b.id=m.box_id WHERE b.id IS NULL", "حركات بلا صندوق"));
        // 9.0.1: حسابات العملاء
        out.add(sqlCheck(d, "حركات حسابات العملاء صالحة ومرتبطة بعملاء موجودين",
                "SELECT m.id FROM client_moves m LEFT JOIN cash_clients c ON c.id=m.client_id WHERE c.id IS NULL OR m.amount_minor<=0 OR m.dir NOT IN (1,-1)", "حركات عملاء غير صالحة"));
        out.add(sqlCheck(d, "كل حركة نقدية لعميل تطابق حركة صندوقها (الحالة والاتجاه)",
                "SELECT m.id FROM client_moves m LEFT JOIN cash_moves x ON x.uuid=m.cash_uuid WHERE m.kind='CASH' AND (x.id IS NULL OR x.status<>m.status OR x.dir=m.dir OR x.client_id IS NULL OR x.client_id<>m.client_id)",
                "حركات عملاء لا تطابق الصندوق"));
        out.add(sqlCheck(d, "ساقا كل تبديل في حسابات العملاء موجودتان وبالحالة نفسها",
                "SELECT pair_uuid FROM client_moves WHERE pair_uuid IS NOT NULL AND pair_uuid<>'' GROUP BY pair_uuid HAVING COUNT(*)<>2 OR COUNT(DISTINCT status)<>1",
                "تبديل غير مكتمل"));
        int missing = 0, broken = 0, total = 0;
        List<String> samples = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT id,path FROM attachments ORDER BY id DESC", null)) {
            while (c.moveToNext()) {
                total++;
                File f = attachmentFile(c.getString(1));
                if (!f.exists()) {
                    missing++;
                    if (samples.size() < 5) samples.add("" + c.getLong(0));
                    continue;
                }
                if (!full && total > 10) continue;
                try {
                    Crypto.decBytes(readAll(f));
                } catch (Throwable t) {
                    broken++;
                    if (samples.size() < 5) samples.add("" + c.getLong(0));
                }
            }
        }
        out.add(new Check(missing + broken == 0, "الصور موجودة وقابلة للفتح (" + total + ")",
                missing + broken == 0 ? "" : ("مفقودة: " + missing + "، تالفة: " + broken + " — أمثلة: " + samples)));
        int bad = 0;
        try (Cursor c = d.rawQuery("SELECT name FROM offices UNION ALL SELECT person FROM tx UNION ALL SELECT description FROM tx", null)) {
            while (c.moveToNext()) {
                try {
                    Crypto.decStrict(c.getString(0));
                } catch (Exception e) {
                    bad++;
                }
            }
        }
        out.add(new Check(bad == 0, "النصوص المشفّرة قابلة للقراءة", bad == 0 ? "" : (bad + " حقل لا يمكن فكه على هذا الهاتف")));
        try {
            AuditChain.Result ar = full ? verifyAudit() : verifyAuditSince();
            out.add(new Check(ar.intact(), "سجل العمليات متسلسل لم يُعدَّل (" + ar.ok + " سطر" + (ar.legacy > 0 ? "، و" + ar.legacy + " قبل 8.0" : "") + ")",
                    ar.intact() ? "" : ar.problem + (ar.brokenAt > 0 ? " — عند السطر " + ar.brokenAt : "")));
        } catch (Exception e) {
            out.add(new Check(false, "سجل العمليات متسلسل", "تعذر الفحص: " + e.getMessage()));
        }
        return out;
    }

    /**
     * 8.0 (ت1): فحص تلقائي في الخلفية (بعد كل نسخة، وبعد الاستعادة، وأول فتح في اليوم). النتيجة لشارة الرئيسية
     * ولوحة الاعتمادية: health_status = «ok» أو عدد الملاحظات وعناوينها.
     */
    /** 12.7: فحص واحد في وقت واحد (كان «synchronized» على القاعدة كلها فيحجز decimals() عن الشاشة طوال الفحص). */
    private static final java.util.concurrent.atomic.AtomicBoolean HEALTH_RUNNING = new java.util.concurrent.atomic.AtomicBoolean();

    public String runHealthAuto() {
        if (!HEALTH_RUNNING.compareAndSet(false, true)) return setting("health_status", "");
        List<Check> cs;
        DbCipher.preferReader(true);
        try {
            cs = healthCheck(false);
        } finally {
            DbCipher.preferReader(false);
            HEALTH_RUNNING.set(false);
        }
        StringBuilder bad = new StringBuilder();
        int n = 0;
        for (Check c : cs) {
            if (c.ok) continue;
            n++;
            if (bad.length() > 0) bad.append("؛ ");
            bad.append(c.title);
        }
        String st = n == 0 ? "ok" : n + "|" + bad;
        setSettingQuiet("health_status", st);
        setSettingQuiet("health_last", "" + Clock.now());
        if (n > 0) audit("HEALTH_PROBLEM", "health", n, bad.toString());
        return st;
    }

    private static Check sqlCheck(SQLiteDatabase d, String title, String sql, String label) { return sqlCheck(d, title, sql, label, null); }

    private static Check sqlCheck(SQLiteDatabase d, String title, String sql, String label, String[] args) {
        List<String> bad = new ArrayList<>();
        int n = 0;
        try (Cursor c = d.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                n++;
                if (bad.size() < 5) bad.add(c.getString(0));
            }
        } catch (Exception e) {
            return new Check(false, title, "تعذر الفحص: " + e.getMessage());
        }
        return new Check(n == 0, title, n == 0 ? "" : (label + ": " + n + " — أمثلة: " + bad));
    }

    // ===================================================================================
    // سجل التدقيق
    // ===================================================================================

    /** سطر تدقيق خارج العمليات المالية (إعدادات، تصدير…): فشله لا يوقف العمل. */
    public void audit(String ac, String en, long id, String r) {
        try {
            SQLiteDatabase d = getWritableDatabase();
            d.beginTransaction();
            try {
                audit(d, ac, en, id, r);
                d.setTransactionSuccessful();
            } finally {
                d.endTransaction();
            }
        } catch (Exception ignored) {
            // لا يوقف العمل
        }
    }

    /**
     * 8.0: سطر تدقيق داخل معاملة العملية نفسها، متسلسل ببصمة السطر الذي قبله. إن تعذّرت كتابته تفشل العملية كلها
     * (لا حركة مالية بلا أثر في السجل). آخر بصمة تُحفظ في إعدادات الهاتف فيُكشف حذف أسطر من نهاية السجل.
     */
    /** 10.1: يُستدعى بعد كل عملية مسجّلة والمزامنة مفعّلة (الواجهة تجدول مزامنة قريبة). */
    public static volatile Runnable onLocalChange;

    private void audit(SQLiteDatabase d, String ac, String en, long id, String r) {
        Runnable hook = onLocalChange;
        if (hook != null && syncPrefix > 0 && !ac.startsWith("SYNC") && !ac.startsWith("LOGIN")) {
            try {
                hook.run();
            } catch (Throwable ignored) {
                // الواجهة فقط
            }
        }
        String reason = r == null ? "" : r;
        String user = currentUserName == null ? "" : currentUserName;
        long now = Clock.now();
        String prev = "";
        try (Cursor c = d.rawQuery("SELECT row_hash FROM audit_logs ORDER BY id DESC LIMIT 1", null)) {
            if (c.moveToFirst() && !c.isNull(0)) prev = c.getString(0);
        }
        String hash = AuditChain.hash(prev, ac, en, id, user, reason, now);
        ContentValues v = new ContentValues();
        v.put("action", ac);
        v.put("entity", en);
        v.put("entity_id", id);
        v.put("user_name", Crypto.enc(user));
        v.put("reason", Crypto.enc(reason));
        v.put("created_at", now);
        v.put("prev_hash", prev);
        v.put("row_hash", hash);
        d.insertOrThrow("audit_logs", null, v);
        d.execSQL("INSERT OR REPLACE INTO settings(k,v) VALUES('audit_head',?)", new Object[]{hash});
        // 10.16: نسخة في السجل المشترك (تنتقل بالمزامنة). جولة المزامنة نفسها لا تُنسخ، وإلا ولّدت كل جولة جولة بعدها.
        if (syncPrefix > 0 && !ac.equals("SYNC") && !ac.equals("SYNC_UPGRADE")) {
            ContentValues f = new ContentValues();
            f.put("uuid", java.util.UUID.randomUUID().toString());
            f.put("created_at", now);
            f.put("action", ac);
            f.put("entity", en);
            f.put("entity_id", id);
            f.put("user_name", Crypto.enc(user));
            f.put("reason", Crypto.enc(reason));
            f.put("dev", SyncStore.meta(d, "device"));
            f.put("dev_name", SyncStore.meta(d, "device_name"));
            d.insertOrThrow("audit_feed", null, f);
        }
    }

    /** كل أسطر السجل بنصوصها الأصلية لفحص التسلسل. */
    public List<AuditChain.Row> auditChainRows() { return auditChainRows(0); }

    /** 12.7: الأسطر بعد رقم معيّن (للفحص اليومي المتتابع). */
    public List<AuditChain.Row> auditChainRows(long afterId) {
        List<AuditChain.Row> a = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,action,entity,entity_id,user_name,reason,created_at,prev_hash,row_hash FROM audit_logs WHERE id>" + afterId + " ORDER BY id", null)) {
            while (c.moveToNext()) {
                AuditChain.Row r = new AuditChain.Row();
                r.id = c.getLong(0);
                r.action = c.getString(1);
                r.entity = c.getString(2);
                r.entityId = c.isNull(3) ? 0 : c.getLong(3);
                r.user = Model.nz(Crypto.dec(c.getString(4)));
                r.reason = Model.nz(Crypto.dec(c.getString(5)));
                r.createdAt = c.isNull(6) ? 0 : c.getLong(6);
                r.prev = c.getString(7);
                r.hash = c.getString(8);
                a.add(r);
            }
        }
        return a;
    }

    public AuditChain.Result verifyAudit() {
        return AuditChain.verify(auditChainRows(), setting("audit_head", ""));
    }

    static final String AUDIT_OK = "sync_audit_ok";

    /**
     * 12.7: الفحص التلقائي اليومي: من آخر نقطة سليمة فقط (الفحص الكامل من «الفحص» يقرأ السجل كله).
     * النقطة (رقم السطر وبصمته) إعداد خاص بهذا الجهاز؛ إن لم يعد سطرها بالبصمة نفسها يُفحص السجل كله.
     */
    public AuditChain.Result verifyAuditSince() {
        String cp = setting(AUDIT_OK, "");
        long after = 0;
        String prev = null;
        int idx = cp.indexOf('|');
        if (idx > 0) {
            try {
                long id = Long.parseLong(cp.substring(0, idx));
                String h = cp.substring(idx + 1);
                try (Cursor c = getReadableDatabase().rawQuery("SELECT row_hash FROM audit_logs WHERE id=" + id, null)) {
                    if (c.moveToFirst() && h.equals(c.getString(0))) {
                        after = id;
                        prev = h;
                    }
                }
            } catch (Exception ignored) {
                // نقطة تالفة: فحص كامل
            }
        }
        List<AuditChain.Row> rows = auditChainRows(after);
        AuditChain.Result r = AuditChain.verify(rows, setting("audit_head", ""), prev);
        if (r.intact()) {
            AuditChain.Row lastRow = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (lastRow != null && lastRow.hash != null && !lastRow.hash.isEmpty()) setSettingQuiet(AUDIT_OK, lastRow.id + "|" + lastRow.hash);
        }
        return r;
    }

    public void checkpoint() {
        try (Cursor c = getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            c.moveToFirst();
        } catch (Exception ignored) {
            // ليس حرجًا
        }
    }

    public static class Opening {
        public String code, type;
        public long amount;

        public Opening(String c, String t, long a) {
            code = c;
            type = t;
            amount = a;
        }
    }
}
