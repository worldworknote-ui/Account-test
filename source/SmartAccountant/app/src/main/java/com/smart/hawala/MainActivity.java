package com.smart.hawala;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Ui {

    static final int PICK_IMAGES = 20, TAKE_PHOTO = 21, CREATE_BACKUP = 30, OPEN_BACKUP = 31, AUTO_TREE = 32, DRIVE_FILE = 33, PICK_LOGO = 40, SAVE_EXPORT = 60, LICENSE_FILE = 61, LICENSE_QR = 62;
    static final List<String> HOME_KEYS = Arrays.asList("tx", "log", "statement", "totals", "daily", "search", "office", "currency", "backup", "cash", "reports", "mine", "settings");
    static boolean reminderShownThisSession;

    final TxScreen txScreen = new TxScreen(this);
    final StatementScreen statementScreen = new StatementScreen(this);
    final BalancesScreen balancesScreen = new BalancesScreen(this);
    final LogScreen logScreen = new LogScreen(this);
    final DailyScreen dailyScreen = new DailyScreen(this);
    final ExportUi exportUi = new ExportUi(this);
    final BackupScreen backupScreen = new BackupScreen(this);
    final ResetScreen resetScreen = new ResetScreen(this);
    final DesignScreen designScreen = new DesignScreen(this);
    final OfficesScreen officesScreen = new OfficesScreen(this);
    final SettingsScreen settingsScreen = new SettingsScreen(this);
    final UsersScreen usersScreen = new UsersScreen(this);
    final ReconScreen reconScreen = new ReconScreen(this);
    final WaQueue waQueue = new WaQueue(this);
    /** 12.1: نقل الرسائل (الحافظة بكل صيغها، القص، التجميع، إطار الالتقاط). */
    final WaMove waMove = new WaMove(this);
    final HomeScreen homeScreen = new HomeScreen(this);
    final OfficeHub officeHub = new OfficeHub(this);
    final MoreScreen moreScreen = new MoreScreen(this);
    final LicenseScreen licenseScreen = new LicenseScreen(this);
    final FxScreen fxScreen = new FxScreen(this);
    final MineScreen mineScreen = new MineScreen(this);
    final TeamScreen teamScreen = new TeamScreen(this);
    final OwnerScreen ownerScreen = new OwnerScreen(this);
    final ReportsScreen reportsScreen = new ReportsScreen(this);
    final CashScreen cashScreen = new CashScreen(this);
    final SyncScreen syncScreen = new SyncScreen(this);
    final TabsScreen tabsScreen = new TabsScreen(this);
    final AppLock appLock = new AppLock(this);
    final NotifScreen notifScreen = new NotifScreen(this);
    final UsageScreen usageScreen = new UsageScreen(this);

    /** إعادة إنشاء الشاشة بعد تغيير الخط أو الوضع الليلي: لا تُطلب البصمة مجددًا، ونعود للشاشة نفسها. */
    static boolean trustedRecreate;
    static String recreateTarget;
    static final String ACTION_NEW_TX = "com.smart.hawala.action.NEW_TX";
    static final String ACTION_PASTE = "com.smart.hawala.action.PASTE";
    static final String ACTION_OFFICES = "com.smart.hawala.action.OFFICES";
    static final String ACTION_SEARCH = "com.smart.hawala.action.SEARCH";
    static final String ACTION_PENDING = "com.smart.hawala.action.PENDING";
    static final String ACTION_DUPS = "com.smart.hawala.action.DUPS";
    static final String ACTION_LOG = "com.smart.hawala.action.LOG";
    /** 10.16: يفتح شاشة المزامنة (من إشعار تعارض أو دخول جهاز). */
    static final String ACTION_SYNC = "com.smart.hawala.action.SYNC";
    /** اختصار من أيقونة التطبيق (ضغطة مطولة) ينتظر فتح القفل. */
    String pendingShortcut;

    final List<byte[]> pendingImages = new ArrayList<>();
    String cameraFile;
    File restoreInput;
    boolean unlocked;
    /** 8.0: مفتاح التشفير مفقود (فشل الفحص الذاتي): لا إدخال ولا نسخ، والاستعادة فقط. */
    static boolean keyMissing;
    long lastAuthAt;
    long pausedAt;

    // ===================================================================================
    // دورة الحياة
    // ===================================================================================

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        // 10.1: كل عملية مسجّلة تجدول مزامنة قريبة (على الخيط الرئيسي)
        final android.os.Handler mh = new android.os.Handler(android.os.Looper.getMainLooper());
        Db.onLocalChange = () -> mh.post(syncScreen::syncSoon);
        try {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignored) {
            // غير مهم
        }
        if (b != null) {
            cameraFile = b.getString("cameraFile");
            String ri = b.getString("restoreInput");
            if (ri != null) restoreInput = new File(ri);
        }
        // 12.7: أول تشفير لقاعدة قديمة: في الخلفية بشاشة واضحة، ثم يكمل الإقلاع
        if (SmartApp.db == null && Db.needsEncryption(this)) {
            encryptingScreen(b);
            return;
        }
        startWithDb(b);
    }

    private void encryptingScreen(final Bundle b) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(28), dp(28), dp(28), dp(28));
        l.addView(new android.widget.ProgressBar(this));
        TextView t = new TextView(this);
        t.setText("جارٍ تشفير بيانات المحاسب لأول مرة…\n\nيحدث مرة واحدة فقط وقد يستغرق دقيقة مع البيانات الكبيرة. بياناتك تبقى كما هي، "
                + "وإن أُغلق التطبيق الآن يُعاد التشفير بأمان في الفتح التالي.");
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, 0);
        l.addView(t);
        setContentView(l);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Throwable err = null;
            try {
                if (SmartApp.db == null) {
                    SmartApp.db = new Db(app);
                    SmartApp.afterDb(app);
                }
            } catch (Throwable x) {
                err = x;
            }
            final Throwable fe = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (fe != null) showFatal(fe);
                else startWithDb(b);
            });
        }, "first-encrypt").start();
    }

    private void startWithDb(Bundle b) {
        try {
            if (SmartApp.db == null) SmartApp.db = new Db(getApplicationContext());
            db = SmartApp.db;
            applySecureScreen();
            keyMissing = !Crypto.healthy(this) || Db.lockedAside != null;   // 12.6: أو قاعدة مشفّرة لم تُفتح بالمفتاح
            applyTheme();
            // مشاركة من واتساب (مرة واحدة: لا تُعاد عند استرجاع الشاشة بعد إغلاق النظام لها)
            if (b == null) {
                waQueue.pending = waQueue.fromIntent(getIntent());
                pendingShortcut = shortcutOf(getIntent());
            }
            applyColors();
            cleanShared();
            if (trustedRecreate) {
                trustedRecreate = false;
                unlocked = true;
                lastAuthAt = SystemClock.elapsedRealtime();
                String target = recreateTarget;
                recreateTarget = null;
                if ("design".equals(target) && !(db.usersEnabled() && SmartApp.currentUser == null)) designScreen.show();
                else afterUnlock();
            } else if (biometricOn()) {
                appLock.lockScreen();
            } else {
                unlocked = true;
                afterUnlock();
            }
            AutoBackup.runIfDueAsync(this);
            Notifier.schedule(this);
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    /** إعادة الإنشاء بعد تغيير السمة أو الخط مع الحفاظ على حالة الدخول. */
    void restyle(String target) {
        txScreen.saveDraft();
        trustedRecreate = true;
        recreateTarget = target;
        recreate();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        // الوضع «تلقائي»: يتبع الوضع الليلي للهاتف عند تغيّره
        if (db != null && unlocked && wantsNight(db, cfg) != night) restyle(null);
    }

    static String shortcutOf(Intent i) {
        String act = i == null ? null : i.getAction();
        if (ACTION_NEW_TX.equals(act) || ACTION_PASTE.equals(act) || ACTION_OFFICES.equals(act) || ACTION_SEARCH.equals(act)
                || ACTION_PENDING.equals(act) || ACTION_DUPS.equals(act) || ACTION_LOG.equals(act) || ACTION_SYNC.equals(act)) return act;
        // 11.5: رمز QR من البائع (smartacc://activate?k=…) مُسح بكاميرا الهاتف: يُفعَّل بعد فتح التطبيق
        if (Intent.ACTION_VIEW.equals(act) && i.getData() != null && "smartacc".equals(i.getData().getScheme())) return "license:" + i.getData().toString();
        return null;
    }

    /** ينفّذ اختصار أيقونة التطبيق بعد الدخول. */
    boolean openShortcut() {
        String s = pendingShortcut;
        pendingShortcut = null;
        if (s == null) return false;
        switch (s) {
            case ACTION_NEW_TX: txScreen.showNew(); return true;
            case ACTION_PASTE: homeScreen.pasteFromClipboard(); return true;
            case ACTION_OFFICES: officeHub.showList(); return true;
            case ACTION_SEARCH: logScreen.searchFor(""); return true;
            case ACTION_PENDING: if (can(Auth.Perm.APPROVE)) logScreen.showPending(); else showHome(); return true;
            case ACTION_DUPS: syncScreen.showDuplicates(); return true;
            case ACTION_SYNC: syncScreen.show(); return true;
            case ACTION_LOG: if (can(Auth.Perm.VIEW)) logScreen.show(false); else showHome(); return true;
            default:
                if (s.startsWith("license:")) {
                    licenseScreen.show();
                    licenseScreen.activateText(s.substring(8));
                    return true;
                }
                return false;
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String sc = shortcutOf(i);
        if (sc != null && db != null) {
            pendingShortcut = sc;
            if (unlocked && !(db.usersEnabled() && SmartApp.currentUser == null)) {
                beforeScreenChange();
                openShortcut();
            }
            return;
        }
        java.util.List<WaText.Msg> shared = waQueue.fromIntent(i);
        if (shared == null || db == null) return;
        waQueue.pending = shared;
        if (unlocked && !(db.usersEnabled() && SmartApp.currentUser == null)) {
            beforeScreenChange();
            waQueue.openPending();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 12.1: وضع التجميع على الهاتف: ما نُسخ في واتساب يُقرأ عند العودة للتطبيق (أندرويد يسمح بالقراءة والنافذة في المقدمة فقط)
        if (hasFocus && db != null && unlocked && waMove.collecting && !WaMove.desktop()) {
            try {
                waMove.collectNow();
            } catch (Throwable ignored) {
                // غير مهم
            }
        }
        // أندرويد يسمح بقراءة وصف الحافظة فقط والنافذة في المقدمة: نحدّث بطاقة «نسخت نصًا قبل قليل»
        if (hasFocus && onHome && db != null && unlocked) {
            try {
                homeScreen.refreshClipCard();
            } catch (Throwable ignored) {
                // غير مهم
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraFile != null) out.putString("cameraFile", cameraFile);
        if (restoreInput != null) out.putString("restoreInput", restoreInput.getAbsolutePath());
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = SystemClock.elapsedRealtime();
        // 11.5: كانت العلامة تبقى إن لم يتوقف التطبيق بعدها (كالمشاركة على الكمبيوتر)، فيُعفى أول ترك حقيقي للتطبيق من القفل
        ownPause = ownActivity && pausedAt - ownAt < 15000;
        ownActivity = false;
        sessionEnd();
        txScreen.saveDraft();
        syncScreen.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ownPause) {
            ownPause = false;
            return;
        }
        if (db != null && unlocked) cashScreen.onResume(pausedAt);
        if (db != null && unlocked) syncScreen.onResume();
        if (db == null || !unlocked || pausedAt == 0 || pausedAt <= lastAuthAt) return;
        boolean locks = biometricOn() || db.usersEnabled();
        long timeout = db.settingLong("lock_timeout", -1);
        if (!locks || timeout < 0) return;
        if (SystemClock.elapsedRealtime() - pausedAt >= timeout * 60000L) {
            txScreen.saveDraft();
            relock();
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        sessionStart();
    }

    // ===================================================================================
    // 11.5: سجل الاستخدام: كل جلسة (من الفتح إلى الترك أو القفل) سطر في سجل العمليات باسم صاحبها،
    // ينتقل بالمزامنة فيرى المدير استخدام كل محاسب على كل الأجهزة
    // ===================================================================================

    private long sessionAt;
    private String sessionUser;

    // 12.7: الخمول (بلا لمس ولا كتابة) يُنهي الجلسة عند آخر نشاط، وتُحفظ الجلسة الجارية كل دقيقة فلا تضيع إن أُغلق التطبيق غصبًا
    static final String OPEN_KEY = "sync_usage_open";
    private volatile long lastInputClock = Clock.now();
    private boolean idle;
    private final android.os.Handler usageHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable usageTick = new Runnable() {
        @Override public void run() {
            usageCheck();
            usageHandler.postDelayed(this, 30_000);
        }
    };

    /** دقائق الخمول التي تُنهي الجلسة (يغيّرها المدير؛ 0 = بلا حد). */
    long idleMinutes() { return db == null ? 5 : Math.max(0, db.settingLong("usage_idle_min", 5)); }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        lastInputClock = Clock.now();
        if (idle) {
            idle = false;
            sessionStart();
        }
    }

    /** 12.7 (الكمبيوتر): النافذة صارت خلف برامج أخرى: تنتهي الجلسة الآن. */
    public void usageAway() {
        sessionEnd();
        idle = true;
    }

    /** 12.7 (الكمبيوتر): عادت النافذة إلى المقدمة. */
    public void usageBack() { onUserInteraction(); }

    private void usageCheck() {
        if (sessionAt <= 0 || db == null) return;
        long now = Clock.now(), limit = idleMinutes() * 60_000L;
        if (limit > 0 && now - lastInputClock >= limit) {
            sessionEnd(Math.max(sessionAt, lastInputClock));
            idle = true;
            return;
        }
        // حفظ مؤقت: بداية الجلسة وآخر نشاط واسم صاحبها (إعداد خاص بهذا الجهاز لا يتزامن)
        try {
            db.setSettingQuiet(OPEN_KEY, sessionAt + "|" + Math.min(now, Math.max(sessionAt, lastInputClock)) + "|" + Crypto.enc(Model.nz(sessionUser)));
        } catch (Throwable ignored) {
            // غير حرج
        }
    }

    /** 12.7: جلسة بقيت محفوظة من تشغيل أُغلق غصبًا: تُسجَّل حتى آخر نشاط محفوظ. */
    void recoverOpenSession() {
        if (db == null) return;
        try {
            String v = db.setting(OPEN_KEY, "");
            if (v.isEmpty()) return;
            db.setSettingQuiet(OPEN_KEY, "");
            String[] p = v.split("\\|", 3);
            long start = Long.parseLong(p[0]), end = Long.parseLong(p[1]);
            String user = p.length > 2 ? Crypto.dec(p[2]) : "";
            writeSession(user, start, end);
        } catch (Throwable ignored) {
            // سطر تالف: يُترك
        }
    }

    private void writeSession(String user, long start, long end) {
        long sec = (end - start) / 1000;
        if (sec < 10) return;
        String keep = Db.currentUserName;
        try {
            Db.currentUserName = user;
            db.audit(UsageScreen.ACTION, "usage", sec, UsageScreen.describe(start, end));
        } finally {
            Db.currentUserName = keep;
        }
    }

    void sessionStart() {
        if (db == null || !unlocked || keyMissing) return;
        if (db.usersEnabled() && SmartApp.currentUser == null) return;
        String u = Model.nz(Db.currentUserName);
        if (sessionAt > 0 && u.equals(sessionUser)) return;
        if (sessionAt > 0) sessionEnd();
        recoverOpenSession();
        sessionAt = Clock.now();
        sessionUser = u;
        lastInputClock = sessionAt;
        idle = false;
        usageHandler.removeCallbacks(usageTick);
        usageHandler.postDelayed(usageTick, 30_000);
    }

    void sessionEnd() { sessionEnd(Clock.now()); }

    void sessionEnd(long end) {
        usageHandler.removeCallbacks(usageTick);
        if (sessionAt <= 0 || db == null) return;
        long start = sessionAt;
        sessionAt = 0;
        try {
            db.setSettingQuiet(OPEN_KEY, "");
        } catch (Throwable ignored) {
            // غير حرج
        }
        writeSession(sessionUser, start, Math.max(start, end));
    }

    void relock() {
        unlocked = false;
        if (db.usersEnabled()) SmartApp.currentUser = null;
        if (biometricOn()) {
            appLock.lockScreen();
        } else {
            unlocked = true;
            afterUnlock();
        }
    }

    @Override
    public void onBackPressed() {
        if (!unlocked || (db != null && db.usersEnabled() && SmartApp.currentUser == null)) {
            super.onBackPressed();
            return;
        }
        if (closeFan()) return;
        // 11.5: الصفحة السابقة فعلًا (السجل)، ثم الرجوع المحدد للصفحة، ثم الرئيسية
        if (historyBack()) return;
        if (back != null) {
            Runnable r = back;
            back = null;
            returning = true;   // 10.5 (§6): الشاشة التي نرجع إليها تعود إلى موضع تمريرها (يُصفَّر بعد رسمها)
            runAsBack(r);
        } else if (!onHome) showHome();
        else super.onBackPressed();
    }

    @Override void beforeScreenChange() {
        txScreen.saveDraft();
        txScreen.detach();
    }

    @Override boolean showHomeButton() {
        return unlocked && !(db != null && db.usersEnabled() && SmartApp.currentUser == null);
    }

    @Override void goHome() { showHome(); }

    @Override void themeLeft(String owner) {
        if ("cash".equals(owner)) cashScreen.leave();
    }

    // ===================================================================================
    // 9.0.1: إخفاء المبالغ (العين) — أرصدة المكاتب والصفحة الرئيسية والصندوق
    // ===================================================================================

    boolean amountsHidden() { return db != null && "1".equals(db.setting("hide_amounts", "0")); }

    /** مبلغ منسّق أو «••••». */
    String mf(long v, int d) { return m(Model.fmt(v, d)); }

    /** رصيد منسّق («لنا»/«علينا») أو «••••». */
    String mb(long v, int d) { return m(Model.bal(v, d)); }

    /** النص كما هو، أو «••••» حين تكون المبالغ مخفية. */
    String m(String s) { return amountsHidden() ? "••••" : s; }

    /** زر العين في الشريط العلوي: يخفي المبالغ أو يظهرها ثم يعيد رسم الشاشة. */
    void eyeAction(final Runnable refresh) {
        final boolean h = amountsHidden();
        headerAction(h ? R.drawable.ic_eye_off : R.drawable.ic_eye, h ? "إظهار المبالغ" : "إخفاء المبالغ", v -> {
            db.setSettingQuiet("hide_amounts", h ? "0" : "1");
            toast(h ? "المبالغ ظاهرة" : "المبالغ مخفية — اضغط العين لإظهارها");
            refresh.run();
        });
    }

    // ===================================================================================
    // 10.1: المزامنة السريعة
    // ===================================================================================

    @Override void globalActions() {
        try {
            if (db == null || !unlocked || (db.usersEnabled() && SmartApp.currentUser == null)) return;
            if (!SyncEngine.enabled(db) || !License.current.sync()) return;
            headerAction(R.drawable.ic_sync, "مزامنة الآن", v -> syncScreen.now(null));
        } catch (Throwable ignored) {
            // الشريط فقط
        }
    }

    /** بعد وصول تغييرات من جهاز آخر: الدخول إن صار مطلوبًا، أو إعادة رسم الشاشة الجذرية الحالية. */
    void afterSync(SyncEngine.Result res) {
        if (db == null || !unlocked) return;
        if (db.usersEnabled()) {
            Model.User u = SmartApp.currentUser;
            Model.User now = u == null ? null : db.user(u.id);
            if (now == null || !now.active) {
                if (u != null) toast("أُوقف حسابك أو تغيّر من الجهاز الرئيسي. سجّل الدخول من جديد.");
                SmartApp.currentUser = null;
                usersScreen.showLogin();
                return;
            }
            SmartApp.currentUser = now;
        }
        if (onHome) showHome();
        else if (rootTab != null && !rootTab.equals("tx")) openTab(rootTab);
    }

    /** 10.3: قفل التطبيق على هذا الجهاز (بصمة أو كلمة سر التطبيق). */
    boolean biometricOn() { return db != null && appLock.on(); }

    boolean can(Auth.Perm p) {
        if (db == null) return true;
        return db.allowed(p);
    }

    boolean require(Auth.Perm p) {
        if (can(p)) return true;
        toast("ليست لديك صلاحية لهذه العملية. اطلب من المدير.");
        return false;
    }

    // ===================================================================================
    // القفل والدخول
    // ===================================================================================

    void afterUnlock() {
        try {
            if (!keyMissing) Reliability.healthIfDue(db);
            if (!keyMissing) Reliability.searchIndexAsync(db);
            LicenseGuard.refreshAsync(getApplicationContext(), db);
        } catch (Throwable ignored) {
            // غير حرج
        }
        // 12.7: انضمام بدأ ولم يكتمل تحميله: شاشة التحميل تكمله أولًا
        if (SyncEngine.joinPending(db)) {
            syncScreen.joinScreen();
            return;
        }
        if (db.usersEnabled() && SmartApp.currentUser == null && !usersScreen.autoLogin()) usersScreen.showLogin();
        else showHome();
    }

    void showLock() {
        returning = true;   // 11.5: شاشة القفل تظهر كاملة فورًا بلا حركة دخول (قد تُبنى والنافذة مصغّرة)
        base("التطبيق مقفل", null);
        ImageView logo = new ImageView(this);
        Bitmap bm = homeLogo();
        if (bm != null) logo.setImageBitmap(bm);
        else logo.setImageResource(R.drawable.ic_logo);
        body.addView(logo, lp(-1, 110, 20));
        TextView t = text("تحقق من هويتك بالبصمة أو قفل الشاشة للمتابعة.", 17, dark, false);
        t.setGravity(Gravity.CENTER);
        add(t);
        button("فتح التطبيق", primary, v -> authenticate());
        if (appLock.hasPin()) button("الفتح بكلمة سر التطبيق", accent, v -> appLock.pinScreen(null));
    }

    void authenticate() {
        try {
            android.hardware.biometrics.BiometricPrompt.Builder b = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle("فتح المحاسب الذكي").setSubtitle("تحقق من هويتك للمتابعة");
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            else b.setNegativeButton("إلغاء", getMainExecutor(), (d, w) -> showLock());
            b.build().authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            unlocked = true;
                            lastAuthAt = SystemClock.elapsedRealtime();
                            afterUnlock();
                        }

                        @Override
                        public void onAuthenticationError(int c, CharSequence m) {
                            // 8.0: القفل لا يُلغى بصمت. 11 و12 و14 = لا بصمة ولا قفل شاشة مهيّأ في الهاتف أصلًا:
                            // يُفتح مع تنبيه دائم دون كتابة biometric=0. أي خطأ آخر (منها 1: المستشعر مشغول) يبقي الشاشة مقفلة.
                            if (c == 11 || c == 12 || c == 14) {
                                // 10.3: الهاتف بلا بصمة ولا قفل شاشة: كلمة سر التطبيق بدل الفتح دون حماية
                                appLock.noBiometric();
                            } else {
                                if (m != null && c != 5 && c != 10 && c != 13) toast(m.toString());
                                showLock();
                            }
                        }
                    });
        } catch (Throwable e) {
            showLock();
            toast("تعذر فتح شاشة التحقق؛ اضغط «فتح التطبيق» للمحاولة من جديد.");
        }
    }

    /**
     * 8.0: يظهر إن فشل الفحص الذاتي للتشفير (ضياع مفتاح Keystore). يمنع الإدخال والنسخ حتى لا تُكتب نسخ تالفة
     * فوق السليمة، ويعرض الاستعادة من نسخة احتياطية فقط.
     */
    void showKeyMissing() {
        base("مفتاح التشفير غير متاح", null);
        onHome = true;
        add(text("تعذر فك البيانات المشفّرة على هذا الهاتف (أسماء المكاتب والبيانات النصية). يحدث هذا إن مُسح مفتاح الأمان من الهاتف: إعادة ضبط، أو نقل بيانات التطبيق بأداة نسخ، أو عطل.", 15, dark, false));
        add(text("لحماية نسخك الاحتياطية السليمة أوقف التطبيق الإدخال والنسخ التلقائي. الحل: استعد آخر نسخة احتياطية سليمة.", 15, red, true));
        button("إعادة المحاولة", primary, v -> {
            Crypto.init(this);
            // 12.6: عاد المفتاح ← تُعاد القاعدة المشفّرة التي نُقلت جانبًا
            if (Db.lockedAside != null && Crypto.healthy(this)) {
                Db back = Db.restoreLockedAside(this, SmartApp.db);
                if (back != null) {
                    SmartApp.db = back;
                    db = back;
                }
            }
            if (Crypto.healthy(this) && Db.lockedAside == null) {
                keyMissing = false;
                toast("عاد المفتاح ✓");
                showHome();
            } else toast("ما زال المفتاح غير متاح.");
        });
        if (can(Auth.Perm.RESTORE)) {
            button("استعادة من نسخة احتياطية", accent, v -> confirm("استعادة من نسخة احتياطية",
                    "سيُنشأ مفتاح أمان جديد، ثم تختار ملف النسخة وتُستبدل البيانات بمحتواها بعد فحصها ومعاينتها. البيانات النصية الحالية غير مقروءة أصلًا.",
                    "متابعة", () -> {
                        Crypto.resetForRestore(this);
                        // 12.6: القاعدة المشفّرة بالمفتاح القديم تُنقل جانبًا (لا تُحذف) وتُفتح قاعدة جديدة بالمفتاح الجديد
                        SmartApp.db = Db.reopenAfterKeyReset(this, SmartApp.db);
                        db = SmartApp.db;
                        keyMissing = false;
                        backupScreen.pickRestoreFile();
                    }));
        } else add(text("الاستعادة للمدير: اطلب منه الدخول.", 14, muted, false));
    }

    // ===================================================================================
    // الأخطاء الحرجة
    // ===================================================================================

    void showFatal(final Throwable t) {
        try {
            final String trace = CrashGuard.header() + CrashGuard.stack(t);
            CrashGuard.save(this, CrashGuard.stack(t));
            applyColors();
            base("تعذر تشغيل البيانات", null);
            add(text("حدث خطأ أثناء فتح التطبيق. اضغط «مشاركة تفاصيل الخطأ» وأرسلها ليتم إصلاح المشكلة.", 16, ink, false));
            button("إعادة المحاولة", primary, v -> recreate());
            button("مشاركة تفاصيل الخطأ", accent, v -> shareText(trace));
            TextView tv = text(trace, 12, muted, false);
            tv.setTextIsSelectable(true);
            tv.setGravity(Gravity.LEFT);
            tv.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            add(tv);
        } catch (Throwable x) {
            try {
                Toast.makeText(this, "خطأ: " + t, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
                // تجاهل
            }
        }
    }

    @Override void openLicense() { licenseScreen.show(); }

    void shareText(String txt) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, txt);
        startOwn(Intent.createChooser(i, "مشاركة"));
    }

    boolean checkPreviousCrash() {
        final String rep = CrashGuard.read(this);
        if (rep == null) return false;
        new AlertDialog.Builder(this).setTitle("أُغلق التطبيق سابقًا بسبب خطأ")
                .setMessage("يمكنك مشاركة تفاصيل الخطأ ليتم إصلاحه. اضغط «مشاركة» ثم اختر واتساب أو البريد.")
                .setPositiveButton("مشاركة", (d, w) -> {
                    shareText(rep);
                    CrashGuard.clear(this);
                })
                .setNegativeButton("تجاهل", (d, w) -> CrashGuard.clear(this)).show();
        return true;
    }

    /** 8.0: ملفات التصدير المؤقتة تُحذف بعد 10 دقائق (عند الفتح وعند مغادرة التطبيق)، لا بعد يومين. */
    void cleanShared() {
        File[] fs = sharedDir().listFiles();
        if (fs == null) return;
        long old = Clock.now() - 10L * 60000L;
        for (File f : fs) if (f.lastModified() < old) f.delete();
    }

    /** 8.0: منع لقطات الشاشة ومعاينة «التطبيقات الأخيرة» (مفعّل افتراضيًا، ويستطيع المدير إيقافه). */
    void applySecureScreen() {
        if (db == null || !"0".equals(db.setting("secure_screen", "1"))) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            cleanShared();
        } catch (Throwable ignored) {
            // غير مهم
        }
    }

    // ===================================================================================
    // الصفحة الرئيسية
    // ===================================================================================

    static Map<String, String> homeNames() {
        Map<String, String> n = new LinkedHashMap<>();
        n.put("tx", "تسجيل حركة");
        n.put("log", "سجل الحركات");
        n.put("statement", "كشف مكتب");
        n.put("totals", "أرصدة كل المكاتب");
        n.put("daily", "التقرير اليومي");
        n.put("search", "البحث المتقدم");
        n.put("office", "إضافة / تعديل مكتب");
        n.put("currency", "العملات وأسعار الصرف");
        n.put("backup", "نسخة احتياطية الآن");
        n.put("cash", "صندوق المكتب");
        n.put("reports", "مركز التقارير");
        n.put("mine", "مكاتبي");
        n.put("settings", "الإعدادات والتصميم");
        return n;
    }

    static List<String> parseOrder(String raw) {
        List<String> out = new ArrayList<>();
        for (String k : (raw == null ? "" : raw).split(",")) {
            k = k.trim();
            if (HOME_KEYS.contains(k) && !out.contains(k)) out.add(k);
        }
        for (String k : HOME_KEYS) {
            if (out.contains(k)) continue;
            int at = out.indexOf("settings");
            if (k.equals("settings") || at < 0) out.add(k);
            else out.add(at, k);
        }
        return out;
    }

    static Set<String> parseSet(String raw) {
        Set<String> s = new HashSet<>();
        for (String k : (raw == null ? "" : raw).split(",")) if (!k.trim().isEmpty()) s.add(k.trim());
        s.remove("settings");
        return s;
    }

    static Map<String, String> parseColors(String raw) {
        Map<String, String> m = new HashMap<>();
        for (String part : (raw == null ? "" : raw).split(";")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        return m;
    }

    static String joinColors(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static String welcomeText(String template, String office) {
        String t = template == null ? "" : template;
        String o = office == null ? "" : office.trim();
        return t.replace("{المكتب}", o).replace("{office}", o).trim();
    }

    Auth.Perm permFor(String key) {
        switch (key) {
            case "tx": return Auth.Perm.TX_ADD;
            case "office": return Auth.Perm.OFFICES;
            case "currency": return Auth.Perm.CURRENCIES;
            case "backup": return Auth.Perm.BACKUP;
            case "cash": return Auth.Perm.VIEW;
            case "settings": return null;
            default: return Auth.Perm.VIEW;
        }
    }

    void openHome(String k) {
        switch (k) {
            case "tx": txScreen.showNew(); break;
            case "log": logScreen.show(false); break;
            case "statement": officePicker(statementScreen::show); break;
            case "totals": balancesScreen.show(); break;
            case "daily": dailyScreen.show(Clock.now()); break;
            case "search": logScreen.show(true); break;
            case "office": officeHub.showList(); break;
            case "currency": officesScreen.showCurrencies(); break;
            case "backup": backupScreen.backupDialog(); break;
            case "cash": cashScreen.show(); break;
            case "reports": reportsScreen.show(); break;
            case "mine": mineScreen.show(); break;
            default: settingsScreen.show(); break;
        }
    }

    @Override void openTab(String key) {
        switch (key) {
            case "home": showHome(); break;
            case "offices": officeHub.showList(); break;
            case "tx": txScreen.showNew(); break;
            case "log": logScreen.show(false); break;
            case "cash": cashScreen.show(); break;
            case "clients": cashScreen.clients.list(); break;
            case "mine": mineScreen.show(); break;
            case "reports": reportsScreen.show(); break;
            case "balances": balancesScreen.show(); break;
            case "daily": dailyScreen.show(Clock.now()); break;
            default: moreScreen.show(); break;
        }
    }

    /** 10.6: خيارات زر «تسجيل» المتاحة لهذا المستخدم. */
    @Override java.util.List<RecordMenu.Item> recordItems() { return RecordMenu.allowed(this::can); }

    @Override void openRecord(String key, Model.Office office) {
        if (office != null && office.active) db.setSettingQuiet("last_office", "" + office.id);
        if (RecordMenu.FX.equals(key)) fxScreen.show(null);
        else if (RecordMenu.XFER.equals(key)) fxScreen.transfer(null, office);
        else txScreen.showNew();
    }

    // ===================================================================================
    // 10.15: قائمة «+» المروحية فوق زر «تسجيل» في الشريط الزجاجي (من «أموالي»)
    // ===================================================================================

    private FanMenu fan;

    /** هل يملك المستخدم ما يفعله زر القائمة؟ (القواعد نفسها تُفرض مرة أخرى في طبقة البيانات عند الحفظ) */
    boolean fanAllowed(String k) {
        switch (k) {
            case "TX": case "XFER": case "WA": return can(Auth.Perm.TX_ADD);
            case "FX": return can(Auth.Perm.TX_ADD) && can(Auth.Perm.TX_EDIT);
            case "DAILY": return can(Auth.Perm.VIEW);
            default: return can(Auth.Perm.CASH);
        }
    }

    static int fanIcon(String k) {
        switch (k) {
            case "TX": return R.drawable.ic_plus;
            case "FX": case "SELL": case "BUY": return R.drawable.ic_coins;
            case "XFER": return R.drawable.ic_transfer;
            case "WA": return R.drawable.ic_paste;
            case "CASH_IN": return R.drawable.ic_download;
            case "CASH_OUT": return R.drawable.ic_upload;
            case "EXP": return R.drawable.ic_tag;
            case "COUNT": return R.drawable.ic_check2;
            default: return R.drawable.ic_calendar;
        }
    }

    /** أزرار الصندوق: الصندوق غير مهيأ ← شاشة تهيئته؛ مقفل ← يُفتح أولًا. */
    private void cashDo(Runnable r) {
        if (!require(Auth.Perm.CASH)) return;
        if (!db.cashConfig().on) cashScreen.show();
        else cashScreen.guard(r);
    }

    void fanAction(String k) {
        switch (k) {
            case "TX": openRecord(RecordMenu.TX, null); break;
            case "FX": openRecord(RecordMenu.FX, null); break;
            case "XFER": openRecord(RecordMenu.XFER, null); break;
            case "WA": homeScreen.pasteFromClipboard(); break;
            case "CASH_IN": cashScreen.form(CashBox.IN, null); break;
            case "CASH_OUT": cashScreen.form(CashBox.OUT, null); break;
            case "SELL": cashDo(() -> cashScreen.trade(CashBox.SELL)); break;
            case "BUY": cashDo(() -> cashScreen.trade(CashBox.BUY)); break;
            case "EXP": cashDo(cashScreen::expense); break;
            case "COUNT": cashDo(cashScreen::count); break;
            default: dailyScreen.show(Clock.now());
        }
    }

    /** أزرار القائمة المتاحة لهذا المستخدم بالترتيب الذي اختاره. */
    java.util.List<String> fanKeys() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : nav().fan) if (fanAllowed(k)) out.add(k);
        return out;
    }

    @Override void plusPressed(final NotchBar nb, final int center, final int activeIdx) {
        if (fan != null && (fan.getParent() == null || fan.isClosing())) fan = null;
        if (fan != null) {
            fan.close(null);
            return;
        }
        final java.util.List<String> keys = fanKeys();
        // القائمة مطفأة، أو لا يبقى فيها زران للمستخدم: صفحة التسجيل مباشرة كما كانت
        if (!nav().plusMenu || keys.size() < 2 || frame == null) {
            if (keys.size() == 1 && nav().plusMenu) fanAction(keys.get(0));
            else openTab("tx");
            return;
        }
        nb.slideTo(center);
        final boolean[] chosen = {false};
        java.util.List<FanMenu.Item> items = new java.util.ArrayList<>();
        for (final String k : keys) {
            android.graphics.drawable.Drawable ic = getDrawable(fanIcon(k));
            items.add(new FanMenu.Item(NavPrefs.itemName(k), ic, nav().itemColor(k), true, () -> {
                chosen[0] = true;
                fanAction(k);
            }));
        }
        final FanMenu f = new FanMenu(this, items, () -> {
            float[] cc = nb.circleCenter(center);
            return new float[]{cc[0] + nb.getX(), cc[1] + nb.getY()};
        }, text("ا", 10, ink, true).getTypeface(), nav(), () -> {
            fan = null;
            if (!chosen[0]) nb.slideTo(activeIdx);
        });
        fan = f;
        // المروحة تحت الشريط في الترتيب حتى يبقى الزر ظاهرًا فوق العتمة
        int at = frame.indexOfChild(nb);
        frame.addView(f, at < 0 ? frame.getChildCount() : at, new android.widget.FrameLayout.LayoutParams(-1, -1));
        f.open();
    }

    /** زر الرجوع يغلق القائمة المفتوحة أولًا. */
    boolean closeFan() {
        if (fan != null && (fan.getParent() == null || fan.isClosing())) fan = null;
        if (fan == null) return false;
        fan.close(null);
        return true;
    }

    /** 10.2: هل يملك المستخدم الحالي ما يفتحه هذا التبويب؟ (ما لا يملكه لا يظهر له أصلًا) */
    boolean tabAllowed(String k) {
        switch (k) {
            case "tx": return can(Auth.Perm.TX_ADD);
            case "cash": case "clients": return can(Auth.Perm.CASH);
            case "mine": return mineScreen.available();
            case "log": return can(Auth.Perm.VIEW) || can(Auth.Perm.TX_ADD);
            case "offices": case "reports": case "balances": case "daily": return can(Auth.Perm.VIEW);
            default: return true;
        }
    }

    /** الترتيب المحفوظ للشريط السفلي (على هذا الجهاز)، أو الافتراضي. */
    java.util.List<String> savedTabs() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String s = db == null ? "" : db.setting("tabs", "");
        if (!s.isEmpty()) for (String k : s.split(",")) if (!k.isEmpty() && !out.contains(k)) out.add(k);
        if (out.isEmpty()) {
            out.addAll(java.util.Arrays.asList(TAB_KEYS));
            // 10.7: «مكاتبي» في الشريط افتراضيًا للمحاسب (غير المدير) الذي خُصصت له مكاتب أو يتحقق من الحركات
            Model.User u = SmartApp.currentUser;
            if (u != null && !Auth.isAdmin(db.user(u.id)) && mineScreen.available()) out.add(out.indexOf("more"), "mine");
        }
        if (!out.contains("more")) out.add("more");
        return out;
    }

    @Override java.util.List<String> tabKeys() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : savedTabs()) if (tabAllowed(k)) out.add(k);
        // 11.5: الحد بحسب عرض الشاشة (6 في الهاتف، وحتى 10 في الشاشات العريضة والكمبيوتر)؛ «المزيد» يبقى دائمًا
        int max = maxTabs();
        for (int i = out.size() - 1; out.size() > max && i >= 0; i--) if (!"more".equals(out.get(i))) out.remove(i);
        return out;
    }

    /** 11.5: أقصى عدد لعناصر الشريط السفلي على هذه الشاشة (نحو 60 نقطة لكل عنصر). */
    int maxTabs() {
        int w = getResources().getConfiguration().screenWidthDp;
        return Math.max(TabsScreen.MIN_MAX, Math.min(10, w / 60));
    }

    void showHome() {
        if (db.usersEnabled() && SmartApp.currentUser == null) {
            usersScreen.showLogin();
            return;
        }
        if (keyMissing) {
            showKeyMissing();
            return;
        }
        // 10.12: لكل مستخدم وضعه وخطه — بعد الدخول (أو تبديل المستخدم) تُعاد الشاشة بسمته إن اختلفت
        if (!appliedTheme.equals(themeSig())) {
            restyle("home");
            return;
        }
        sessionStart();
        waMove.startWatch();
        if (waQueue.openPending()) return;
        if (openShortcut()) return;
        applyColors();
        if (!"classic".equals(db.setting("home_style", "modern"))) {
            homeScreen.show();
            return;
        }
        showClassicHome();
    }

    /** الصفحة الرئيسية الكلاسيكية (أزرار كبيرة كما في الإصدارات السابقة) لمن يفضّلها. */
    void showClassicHome() {
        String title = db.setting("app_title", "المحاسب الذكي لمكاتب الحوالات");
        base(title, null);
        onHome = true;
        int queued = waQueue.remaining();
        if (queued > 0 && can(Auth.Perm.TX_ADD)) {
            LinearLayout qc = card();
            qc.setBackground(round(Color.rgb(232, 244, 238), primary, 16));
            qc.addView(text("دفعة رسائل واتساب لم تكتمل: بقيت " + queued + " رسالة", 15, primary, true));
            LinearLayout qr = row();
            qr.addView(smallButton("متابعة التسجيل", primary, v -> waQueue.showCurrent()), weight(44, 1.3f, 2));
            qr.addView(smallButton("إنهاء الدفعة", Color.rgb(120, 120, 120), v -> waQueue.stop()), weight(44, 1, 2));
            qc.addView(qr);
        }
        Bitmap logo = homeLogo();
        if (logo != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logo);
            iv.setAdjustViewBounds(true);
            String size = db.setting("home_logo_size", "medium");
            int h = "small".equals(size) ? 70 : "large".equals(size) ? 150 : 100;
            body.addView(iv, lp(-1, h, 4));
        }
        String office = db.setting("office_name", "").trim();
        if (!office.isEmpty()) {
            TextView on = text(office, 22, primary, true);
            on.setGravity(Gravity.CENTER);
            add(on);
        }
        String welcome = welcomeText(db.setting("welcome", ""), office);
        if (!welcome.isEmpty()) {
            TextView w = text(welcome, 17, dark, false);
            w.setGravity(Gravity.CENTER);
            add(w);
        }
        Model.User u = SmartApp.currentUser;
        if (u != null) {
            LinearLayout ur = row();
            TextView un = text("المستخدم: " + u.name + " (" + Auth.shortLabel(u.role) + ")", 14, muted, false);
            ur.addView(un, new LinearLayout.LayoutParams(0, -2, 1));
            ur.addView(smallButton("خروج", Color.rgb(120, 120, 120), v -> {
                SmartApp.currentUser = null;
                Db.currentUserName = "المستخدم المحلي";
                usersScreen.showLogin();
            }), new LinearLayout.LayoutParams(-2, dp(40)));
            body.addView(ur);
            if (Auth.ADMIN.equals(u.role) && !db.hasRecoveryCode()) {
                LinearLayout rc = card();
                rc.setBackground(round(Color.rgb(252, 232, 232), Color.rgb(220, 120, 120), 16));
                rc.addView(text("⚠ لا يوجد رمز استرداد للمدير. إن نُسي رمز المدير لن يمكن استعادة الدخول.", 14, red, true));
                rc.addView(smallButton("إنشاء رمز الاسترداد الآن", red, v -> usersScreen.createRecoveryFromHome()), lp(-1, 44, 4));
            }
        }
        String reminder = backupReminderText();
        if (reminder != null && can(Auth.Perm.BACKUP)) {
            LinearLayout c = card();
            c.setBackground(round(Color.rgb(253, 240, 228), Color.rgb(230, 160, 110), 16));
            c.addView(text("⚠ " + reminder, 15, Color.rgb(140, 60, 20), true));
            c.addView(smallButton("إنشاء نسخة احتياطية الآن", Color.rgb(200, 90, 30), v -> backupScreen.backupDialog()), lp(-1, 46, 4));
        }
        String autoErr = db.setting("auto_backup_error", "");
        if (!autoErr.isEmpty() && can(Auth.Perm.BACKUP)) {
            LinearLayout c = card();
            c.setBackground(round(Color.rgb(252, 232, 232), Color.rgb(220, 120, 120), 16));
            c.addView(text("فشل النسخ التلقائي الأخير — اضغط للتفاصيل", 14, red, true));
            c.setOnClickListener(v -> backupScreen.show());
        }
        String driveErr = db.setting("drive_backup_error", "");
        if (!driveErr.isEmpty() && can(Auth.Perm.BACKUP)) {
            LinearLayout c = card();
            c.setBackground(round(Color.rgb(252, 232, 232), Color.rgb(220, 120, 120), 16));
            c.addView(text("فشل حفظ النسخة التلقائية في Google Drive — اضغط للتفاصيل", 14, red, true));
            c.setOnClickListener(v -> backupScreen.show());
        }
        Map<String, String> names = homeNames();
        Set<String> hidden = parseSet(db.setting("home_hidden", ""));
        Map<String, String> colors = parseColors(db.setting("btn_colors", ""));
        boolean grid = "grid".equals(db.setting("home_layout", "list"));
        boolean big = "big".equals(db.setting("btn_size", "normal"));
        int h = big ? 68 : 54;
        LinearLayout pending = null;
        for (String k : parseOrder(db.setting("home_order", Db.DEFAULT_ORDER))) {
            if (hidden.contains(k)) continue;
            Auth.Perm p = permFor(k);
            if (p != null && !can(p)) continue;
            if (k.equals("mine") && !mineScreen.available()) continue;
            final String key = k;
            int col = parseColor(colors.get(k), primary);
            Button b = makeButton(names.get(k), col, v -> openHome(key));
            if (big) b.setTextSize(18);
            if (!grid) body.addView(b, lp(-1, h, 4));
            else if (pending == null) {
                pending = row();
                body.addView(pending, lp(-1, -2, 0));
                pending.addView(b, weight(h + 14, 1, 4));
            } else {
                pending.addView(b, weight(h + 14, 1, 4));
                pending = null;
            }
        }
        if (pending != null) pending.addView(new View(this), weight(h + 14, 1, 4));
        TextView dev = text(db.setting("developer", "برمجة: Ai creative solutions"), 14, muted, true);
        dev.setGravity(Gravity.CENTER);
        body.addView(dev, lp(-1, -2, 12));
        TextView ver = text("الإصدار " + versionName(), 12, muted, false);
        ver.setGravity(Gravity.CENTER);
        add(ver);
        TextView modern = text("جرّب الواجهة الحديثة: المزيد ← المظهر ← شكل الصفحة الرئيسية", 12, muted, false);
        modern.setGravity(Gravity.CENTER);
        add(modern);
        if (!checkPreviousCrash() && can(Auth.Perm.BACKUP)) maybeReminderDialog(reminder);
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ===================================================================================
    // تذكير النسخ الاحتياطي
    // ===================================================================================

    long txSinceBackup() {
        long now = db.txCount();
        long at = db.settingLong("last_backup_tx", 0);
        return now >= at ? now - at : now;
    }

    /** 11.5: وقت دقيق مع قربه: «اليوم 14:32 (منذ 5 دقائق)»، «أمس 09:10»، أو التاريخ والساعة. */
    static String agoAt(long t) {
        if (t <= 0) return "لا يوجد";
        long now = Clock.now(), min = Math.max(0, (now - t) / 60000);
        String hm = UsageScreen.hm(t);
        if (Model.day(t).equals(Model.day(now))) return "اليوم " + hm + (min < 1 ? " (الآن)" : min < 60 ? " (منذ " + min + " دقيقة)" : "");
        if (Model.day(t).equals(Model.day(now - 86_400_000L))) return "أمس " + hm;
        return Model.date(t) + " (" + ago(t) + ")";
    }

    static String ago(long t) {
        if (t <= 0) return "لا يوجد";
        long days = (Clock.now() - t) / 86400000L;
        if (days <= 0) return "اليوم";
        if (days == 1) return "منذ يوم";
        if (days == 2) return "منذ يومين";
        if (days <= 10) return "منذ " + days + " أيام";
        return "منذ " + days + " يومًا";
    }

    String backupReminderText() {
        long txNow = db.txCount();
        if (txNow == 0 && db.officeCount() == 0) return null;
        long last = db.settingLong("last_backup", 0);
        long days = db.settingLong("reminder_days", 7);
        long txLimit = db.settingLong("reminder_tx", 20);
        if (days <= 0 && txLimit <= 0) return null;
        if (last == 0) return "لم تُنشأ أي نسخة احتياطية لبياناتك حتى الآن. احفظ نسخة خارج الهاتف.";
        if (days > 0 && Clock.now() - last > days * 86400000L) return "آخر نسخة احتياطية كانت " + ago(last) + ".";
        long n = txSinceBackup();
        if (txLimit > 0 && n >= txLimit) return "سجّلت " + n + " حركة منذ آخر نسخة احتياطية.";
        return null;
    }

    void maybeReminderDialog(String reminder) {
        if (reminder == null || reminderShownThisSession) return;
        String today = day(Clock.now());
        if (today.equals(db.setting("reminder_dialog_day", ""))) return;
        reminderShownThisSession = true;
        db.setSettingQuiet("reminder_dialog_day", today);
        new AlertDialog.Builder(this).setTitle("تذكير: النسخة الاحتياطية")
                .setMessage(reminder + "\n\nالنسخة المحفوظة خارج الهاتف هي الطريقة الوحيدة لاسترجاع بياناتك إذا ضاع الهاتف أو تعطّل.")
                .setPositiveButton("إنشاء الآن", (d, w) -> backupScreen.backupDialog()).setNegativeButton("لاحقًا", null).show();
    }

    void markBackupDone(String status) {
        db.setSettingQuiet("last_backup", "" + Clock.now());
        db.setSettingQuiet("last_backup_tx", "" + db.txCount());
        db.setSettingQuiet("last_backup_status", status);
        Reliability.healthAsync(db);
    }

    // ===================================================================================
    // اختيار المكتب (المثبّتة أولًا)
    // ===================================================================================

    interface OfficeCb { void on(Model.Office o); }

    void officePicker(final OfficeCb cb) { officePicker(cb, false); }

    /** activeOnly: لتسجيل الحركات، تُخفى المكاتب الموقوفة. */
    void officePicker(final OfficeCb cb, final boolean activeOnly) {
        final List<Model.Office> all = activeOffices(activeOnly);
        if (all.isEmpty()) {
            boolean anyStopped = activeOnly && !db.offices().isEmpty();
            new AlertDialog.Builder(this).setMessage(anyStopped ? "كل المكاتب موقوفة. فعّل مكتبًا من قائمة المكاتب أولًا." : "لا توجد مكاتب بعد. أضف مكتبًا أولًا.")
                    .setPositiveButton(anyStopped ? "قائمة المكاتب" : "إضافة مكتب", (d, w) -> {
                        if (anyStopped) officesScreen.showManager();
                        else officesScreen.form(null);
                    }).setNegativeButton("إلغاء", null).show();
            return;
        }
        // 11.5: البحث في الأعلى دائمًا، و«آخر المكاتب» و«مكاتبي» قسمان يُطويان بسهم ويُحفظ آخر وضع لهما،
        // والقائمة تأخذ ما بقي من الارتفاع (كانت صغيرة في الأسفل). عند الكتابة يختفي القسمان وتظهر النتائج تحت البحث مباشرة.
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(8), dp(12), dp(8));
        final android.widget.EditText q = new android.widget.EditText(this);
        q.setHint("ابحث باسم المكتب أو اختصاره");
        q.setSingleLine(true);
        l.addView(q, new LinearLayout.LayoutParams(-1, dp(54)));
        final AlertDialog[] holder = new AlertDialog[1];
        final LinearLayout secs = new LinearLayout(this);
        secs.setOrientation(LinearLayout.VERTICAL);
        l.addView(secs, new LinearLayout.LayoutParams(-1, -2));
        final android.widget.ListView list = new android.widget.ListView(this);
        final Runnable[] sizeList = {null};
        List<Model.Office> rec = new ArrayList<>();
        for (Model.Office o : officeHub.recentOffices(8)) if (!activeOnly || o.active) rec.add(o);
        if (rec.size() > 6) rec = rec.subList(0, 6);
        if (!rec.isEmpty() && all.size() > 4)
            pickerSection(secs, "آخر المكاتب", "picker_open_recent", rec, false, holder, cb, sizeList);
        // 10.7: «مكاتبي» اختصار للمحاسب في أعلى الاختيار
        List<Model.Office> mine = new ArrayList<>();
        for (Model.Office o : db.myOffices()) if (!activeOnly || o.active) mine.add(o);
        if (!mine.isEmpty()) pickerSection(secs, "مكاتبي", "picker_open_mine", mine, true, holder, cb, sizeList);
        final TextView allHead = text("كل المكاتب (" + all.size() + ") — اضغط مطولًا للتثبيت في الأعلى", 12, muted, true);
        l.addView(allHead);
        l.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        final TextView none = text("لا يوجد مكتب بهذا الاسم.", 14, muted, false);
        none.setVisibility(View.GONE);
        l.addView(none);
        sizeList[0] = () -> {
            // ارتفاع القائمة = ما بقي من الشاشة بعد البحث والقسمين المفتوحين
            float d = getResources().getDisplayMetrics().density;
            int screen = Math.round(getResources().getDisplayMetrics().heightPixels / d);
            int used = 54 + 24 + 150;
            if (secs.getVisibility() == View.VISIBLE) for (int i = 0; i < secs.getChildCount(); i++) {
                View c = secs.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                used += c instanceof Flow ? 46 * Math.max(1, (int) Math.ceil(((Flow) c).getChildCount() / 3.0)) : 34;
            }
            int h = Math.max(200, Math.min(720, screen - used));
            android.view.ViewGroup.LayoutParams lpL = list.getLayoutParams();
            if (lpL.height != dp(h)) {
                lpL.height = dp(h);
                list.setLayoutParams(lpL);
            }
        };
        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        final List<Model.Office> shown = new ArrayList<>();
        list.setAdapter(ad);
        final Runnable fill = () -> {
            ad.clear();
            shown.clear();
            String f = Model.nz(q.getText().toString()).trim().toLowerCase(java.util.Locale.ROOT);
            for (Model.Office o : all) {
                if (f.isEmpty() || officeMatches(o, f)) {
                    shown.add(o);
                    ad.add((o.pinned ? "📌 " : "") + o.name + (o.active ? "" : "  (موقوف)"));
                }
            }
            secs.setVisibility(f.isEmpty() ? View.VISIBLE : View.GONE);
            allHead.setText(f.isEmpty() ? "كل المكاتب (" + all.size() + ") — اضغط مطولًا للتثبيت في الأعلى" : "نتائج البحث: " + shown.size());
            none.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            sizeList[0].run();
        };
        fill.run();
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("اختر المكتب").setView(l).setNegativeButton("إلغاء", null).create();
        holder[0] = dlg;
        q.addTextChangedListener(watcher(fill));
        // Enter في البحث: إن بقي مكتب واحد يُختار مباشرة
        q.setOnEditorActionListener((v, id, ev) -> {
            if (shown.size() == 1) {
                dlg.dismiss();
                cb.on(shown.get(0));
                return true;
            }
            return false;
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < shown.size()) {
                dlg.dismiss();
                cb.on(shown.get(pos));
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return true;
            Model.Office o = shown.get(pos);
            db.setPinned(o.id, !o.pinned);
            toast(o.pinned ? "أُلغي تثبيت المكتب" : "تم تثبيت المكتب في أعلى القائمة");
            List<Model.Office> fresh = activeOffices(activeOnly);
            all.clear();
            all.addAll(fresh);
            fill.run();
            return true;
        });
        dlg.show();
    }

    /** 11.5: بحث المكتب بالاسم أو الاختصار أو الهاتف، دون اعتبار الهمزات والتاء المربوطة والألف المقصورة. */
    static boolean officeMatches(Model.Office o, String f) {
        String k = foldAr(f);
        return foldAr(Model.nz(o.name)).contains(k) || foldAr(Model.nz(o.shortName)).contains(k) || Model.nz(o.phone).replace(" ", "").contains(f.replace(" ", ""));
    }

    static String foldAr(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') c = 'ا';
            else if (c == 'ة') c = 'ه';
            else if (c == 'ى') c = 'ي';
            else if (c == 'ؤ') c = 'و';
            else if (c == 'ئ') c = 'ي';
            else if (c == 'ـ' || (c >= '\u064B' && c <= '\u0652')) continue;
            b.append(c);
        }
        return b.toString();
    }

    /** قسم يُطوى بسهم في نافذة اختيار المكتب، ويُحفظ آخر وضع له لهذا المستخدم. */
    private void pickerSection(LinearLayout secs, String title, final String key, List<Model.Office> offices, boolean strong,
                               final AlertDialog[] holder, final OfficeCb cb, final Runnable[] sizeList) {
        final boolean[] open = {!"0".equals(db.setting(key, "1"))};
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(6), 0, dp(4));
        final TextView arrow = text(open[0] ? "▾" : "◂", 16, primaryInk, true);
        head.addView(arrow, new LinearLayout.LayoutParams(dp(22), -2));
        head.addView(text(title + " (" + offices.size() + ")", 13, strong ? primaryInk : muted, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.setContentDescription(title);
        secs.addView(head, new LinearLayout.LayoutParams(-1, dp(34)));
        final Flow fl = flow();
        for (final Model.Office o : offices) fl.addView(pill(o.name, strong, v -> {
            if (holder[0] != null) holder[0].dismiss();
            cb.on(o);
        }));
        fl.setVisibility(open[0] ? View.VISIBLE : View.GONE);
        secs.addView(fl, new LinearLayout.LayoutParams(-1, -2));
        head.setOnClickListener(v -> {
            open[0] = !open[0];
            fl.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            arrow.setText(open[0] ? "▾" : "◂");
            try {
                db.setSettingQuiet(key, open[0] ? "1" : "0");
            } catch (Exception ignored) {
                // تفضيل عرض فقط
            }
            if (sizeList[0] != null) sizeList[0].run();
        });
    }

    List<Model.Office> activeOffices(boolean activeOnly) {
        List<Model.Office> all = db.offices();
        if (!activeOnly) return all;
        List<Model.Office> out = new ArrayList<>();
        for (Model.Office o : all) if (o.active) out.add(o);
        return out;
    }

    // ===================================================================================
    // نتائج الشاشات الأخرى
    // ===================================================================================

    @Override
    protected void onActivityResult(int r, int c, Intent data) {
        super.onActivityResult(r, c, data);
        if (db == null) return;
        try {
            switch (r) {
                case PICK_IMAGES:
                case TAKE_PHOTO:
                    txScreen.onImageResult(r, c, data);
                    break;
                case CREATE_BACKUP:
                case OPEN_BACKUP:
                case AUTO_TREE:
                case DRIVE_FILE:
                    backupScreen.onResult(r, c, data);
                    break;
                case PICK_LOGO:
                    designScreen.onLogoResult(c, data);
                    break;
                case SAVE_EXPORT:
                    exportUi.onSaveResult(c, data);
                    break;
                case LICENSE_FILE:
                    if (c == RESULT_OK && data != null) licenseScreen.onKeyFile(data.getData());
                    break;
                case LICENSE_QR:
                    if (c == RESULT_OK && data != null) licenseScreen.onQrImage(data.getData());
                    break;
                case SyncScreen.PICK_TREE:
                    syncScreen.onResult(c, data);
                    break;
                case DriveAuth.REQ_PICK:
                    DriveAuth.onPicked(this, c, data);
                    break;
                case DriveAuth.REQ:
                    DriveAuth.onResult(this, c, data);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            error(e);
        }
    }
}
