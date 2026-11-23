package com.smart.hawala;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * 10.0: «المزامنة بين الأجهزة» (النسخة المدفوعة، يفعّلها المستخدم أو يتركها): اختيار المجلد المشترك، وإنشاء الخزنة من
 * هذا الجهاز أو الانضمام إليها، والحالة والأجهزة والتعارضات، ومزامنة تلقائية في الخلفية.
 */
final class SyncScreen {
    static final int PICK_TREE = 70;
    private final MainActivity a;
    private Runnable afterPick;

    SyncScreen(MainActivity a) { this.a = a; }

    // ===================================================================================
    // التشغيل في الخلفية
    // ===================================================================================

    private static volatile boolean running;
    private static long lastRun;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            auto(false);
            handler.postDelayed(this, a.db == null ? 90_000 : SyncEngine.everySeconds(a.db) * 1000L);
        }
    };

    /** 10.2: الناقل المختار: «drive» (Google Drive مباشرة)، «server» (خادمك)، أو «folder» (مجلد مشترك). */
    static String transport(Db db) { return db.setting("sync_transport", "folder"); }

    static SyncFolder folder(Context ctx, Db db) { return folder(ctx, db, transport(db)); }

    static SyncFolder folder(Context ctx, Db db, String kind) {
        if ("drive".equals(kind)) return new DriveSyncFolder(new DriveAuth(ctx, db.setting("drive_account", "")));
        if ("server".equals(kind)) {
            String url = db.setting("sync_server", "");
            if (url.isEmpty()) return null;
            String v = db.setting("sync_server_vault", "");
            if (v.isEmpty()) v = SyncEngine.status(db).vaultId;
            return new HttpSyncFolder(url, v, db.setting("sync_server_key", ""));
        }
        String t = db.setting("sync_tree", "");
        return t.isEmpty() ? null : new SafSyncFolder(ctx, Uri.parse(t));
    }

    static String kindName(Db db) {
        switch (transport(db)) {
            case "drive": return "\u200FGoogle Drive مباشرة";
            case "server": return "خادم المزامنة الخاص بك";
            default: return "مجلد مشترك";
        }
    }

    /** مزامنة في الخلفية دون واجهة (من مهمة النظام الدورية). */
    static void background(Context ctx, Db db) {
        if (running || !SyncEngine.enabled(db) || !License.current.sync() || SyncEngine.paused(db) || !networkOk(ctx, db)) return;
        SyncFolder f = folder(ctx, db);
        if (f == null) return;
        running = true;
        try {
            Notifier.afterSync(ctx, db, syncWithRetry(db, f));
        } catch (Throwable ignored) {
            // الخطأ محفوظ في حالة المزامنة ويظهر في شاشتها
        } finally {
            running = false;
            lastRun = SystemClock.elapsedRealtime();
        }
    }

    /** 10.4: «عبر الواي فاي فقط»: لا مزامنة تلقائية على بيانات الجوال (اليدوية تعمل دائمًا). */
    static boolean networkOk(Context ctx, Db db) {
        if (!"1".equals(db.setting("sync_wifi", "0"))) return true;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && !cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return true;
        }
    }

    void onResume() {
        handler.removeCallbacks(tick);
        inBackground = false;
        retryIfPending();
        if (a.db != null) SyncJob.schedule(a, SyncEngine.enabled(a.db) && !SyncEngine.paused(a.db), "1".equals(a.db.setting("sync_wifi", "0")));
        if (a.db != null && SyncEngine.enabled(a.db)) handler.postDelayed(tick, 3000);
    }

    void onPause() {
        handler.removeCallbacks(tick);
        // 12.7: ترك التطبيق أثناء مزامنة: مهمة نظام قصيرة تكملها إن أُوقف التطبيق، والخطأ لا يظهر عند العودة بل تُعاد الجولة
        pauseSerial++;
        inBackground = true;
        if ((running || joinRunning()) && a.db != null) SyncJob.runOnce(a, "1".equals(a.db.setting("sync_wifi", "0")));
    }

    private void retryIfPending() {
        if (!retryOnResume || a.db == null || !SyncEngine.enabled(a.db)) return;
        retryOnResume = false;
        handler.postDelayed(() -> {
            if (running) return;
            a.toast("تكملة المزامنة التي توقفت حين تركت التطبيق…");
            now(null);
        }, 1500);
    }

    /** 12.7: يزداد مع كل ترك للتطبيق؛ جولة بدأت قبله وفشلت تُعاد بصمت عند العودة. */
    static volatile int pauseSerial;
    static volatile boolean inBackground;
    private static volatile boolean retryOnResume;

    /** 12.7: خطأ عابر (شبكة، مهلة، انقطاع) يستحق إعادة المحاولة بدل رسالة خطأ. */
    static boolean transientError(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof java.io.InterruptedIOException || x instanceof java.net.SocketException || x instanceof java.net.UnknownHostException
                    || x instanceof javax.net.ssl.SSLException) return true;
            String m = x.getMessage() == null ? "" : x.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (m.contains("timeout") || m.contains("timed out") || m.contains("connection reset") || m.contains("broken pipe") || m.contains("unable to resolve")) return true;
            if (x.getCause() == x) break;
        }
        return false;
    }

    /** 12.7: جولة مع إعادة محاولة الأخطاء العابرة (5 ثم 15 ثانية). */
    static SyncEngine.Result syncWithRetry(Db db, SyncFolder f) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new SyncEngine(db, f).sync();
            } catch (Exception e) {
                last = e;
                if (!transientError(e) || attempt == 2) throw e;
                Thread.sleep(attempt == 0 ? 5000 : 15000);
            }
        }
        throw last;
    }

    /** جولة تلقائية (لا تزعج المستخدم إلا إن وصلت تغييرات). */
    void auto(boolean force) {
        if (running || a.db == null || !SyncEngine.enabled(a.db) || !License.current.sync() || SyncEngine.paused(a.db) || !networkOk(a, a.db)) return;
        if (!force && SystemClock.elapsedRealtime() - lastRun < 60_000 && SyncEngine.pending(a.db) == 0) return;
        final SyncFolder f = folder(a, a.db);
        if (f == null) return;
        running = true;
        new Thread(() -> {
            SyncEngine.Result r = null;
            try {
                r = syncWithRetry(a.db, f);
            } catch (Throwable ignored) {
                // يظهر في شاشة المزامنة
            } finally {
                running = false;
                lastRun = SystemClock.elapsedRealtime();
            }
            final SyncEngine.Result res = r;
            // التطبيق أمام المستخدم: الإشعار لما يحتاج إجراء فقط (الاعتماد والتكرار)، والباقي رسالة قصيرة.
            // 11.5: «أمامه» = النافذة في المقدمة فعلًا (كان الكمبيوتر يُعدّ في الواجهة دائمًا فلا تصل إشعارات الحركات للمدير)
            boolean looking;
            try {
                looking = a.hasWindowFocus();
            } catch (Throwable t) {
                looking = false;
            }
            if (res != null) Notifier.afterSync(a, a.db, res, looking);
            if (res != null && res.received > 0) a.runOnUiThread(() -> {
                a.toast("وصلت تحديثات من الأجهزة الأخرى (" + res.rows + " تغييرًا)");
                a.afterSync(res);
            });
        }, "sync").start();
    }

    /** زر «مزامنة الآن» السريع (الشريط العلوي أو شاشة الدخول): جولة فورية ثم نتيجتها في سطر واحد. */
    void now(final Runnable after) {
        if (a.db == null || !SyncEngine.enabled(a.db)) {
            a.toast("المزامنة غير مفعّلة على هذا الجهاز");
            return;
        }
        if (!License.current.sync()) {
            a.toast("المزامنة ميزة في النسخة المدفوعة");
            return;
        }
        if (running) {
            a.toast("المزامنة جارية الآن…");
            return;
        }
        final SyncFolder f = folder(a, a.db);
        if (f == null) {
            a.toast("اختر مجلد المزامنة من «المزيد ← المزامنة»");
            return;
        }
        running = true;
        a.toast("جارٍ المزامنة…");
        final int serial = pauseSerial;
        new Thread(() -> {
            SyncEngine.Result r = null;
            Throwable err = null;
            try {
                r = syncWithRetry(a.db, f);
            } catch (Throwable t) {
                err = t;
            } finally {
                running = false;
                lastRun = SystemClock.elapsedRealtime();
            }
            final SyncEngine.Result res = r;
            final Throwable e = err;
            final boolean left = serial != pauseSerial || inBackground;
            a.runOnUiThread(() -> {
                if (e != null) {
                    // 12.7: تُرك التطبيق أثناء الجولة: لا رسالة خطأ عند العودة؛ تُعاد الجولة بصمت
                    if (left) {
                        retryOnResume = true;
                        if (!inBackground) retryIfPending();
                        return;
                    }
                    a.toast("تعذرت المزامنة: " + Ui.friendly(e));
                    return;
                }
                String extra = res.errors.isEmpty() ? (res.note.isEmpty() ? "" : "\n" + res.note) : "\n" + res.errors.get(0);
                a.toast("✓ " + res.summary() + extra);
                if (after != null) after.run();
                else if (res.received > 0) a.afterSync(res);
            });
        }, "sync-now").start();
    }

    /** 10.1: بعد كل تغيير محلي: مزامنة خلال ثوانٍ (تُجمع التغييرات المتتالية في جولة واحدة). */
    private final Runnable soon = () -> auto(true);

    void syncSoon() {
        if (a.db == null || !SyncEngine.enabled(a.db) || !"1".equals(a.db.setting("sync_after_change", "1"))) return;
        handler.removeCallbacks(soon);
        handler.postDelayed(soon, 4000);
    }

    // ===================================================================================
    // الشاشة
    // ===================================================================================

    void show() {
        a.here(this::show);
        a.tabbedScreen("المزامنة بين الأجهزة", a.moreScreen::show, "more");
        SyncEngine.Status st = SyncEngine.status(a.db);
        if (!License.current.sync()) {
            LinearLayout c = a.card();
            c.addView(a.text("ميزة في النسخة المدفوعة", 17, a.primaryInk, true));
            c.addView(a.text("المزامنة تجعل بياناتك واحدة على كل أجهزتك: الهاتف والماك والويندوز. كل جهاز يسجّل ويعدّل، وتصل التغييرات للبقية "
                    + "مشفّرة بالكامل عبر مجلد سحابي تختاره. بياناتك على هذا الجهاز تعمل كما هي دون مزامنة.", 14, a.ink, false));
            c.addView(a.makeButton("الترخيص", a.primary, v -> a.openLicense()), a.lp(-1, 48, 6));
            if (!st.enabled) return;
        }
        if (!st.enabled) {
            intro(st);
            return;
        }
        status(st);
    }

    private void intro(SyncEngine.Status st) {
        if (!st.leftReason.isEmpty()) a.notice(R.drawable.ic_info, "المزامنة متوقفة على هذا الجهاز: " + st.leftReason + ". انضم من جديد للمتابعة.", false, null, null);
        LinearLayout c = a.card();
        c.addView(a.text("كيف تعمل؟", 17, a.primaryInk, true));
        c.addView(a.text("• بياناتك تبقى على كل جهاز وتعمل دون إنترنت.\n"
                + "• كل جهاز يرسل تغييراته كملفات مشفّرة ويستلم تغييرات الأجهزة الأخرى. تنتقل الحركات الجديدة فقط.\n"
                + "• الناقل (Google أو خادمك أو المجلد) لا يرى شيئًا: لا أسماء ولا مبالغ. المفتاح من كلمة سر المزامنة التي تختارها.\n"
                + "• القيود لا تُحذف ولا تتكرر: الإلغاء نفسه من جهازين يُحسب مرة واحدة، وتعديل الحركة نفسها من جهازين يُبقي الأسبق ويعرض الآخر للمراجعة.", 14, a.ink, false));
        if (!a.can(Auth.Perm.SYNC)) {
            a.hint("إعداد المزامنة لمن يملك صلاحية «إدارة المزامنة».");
            return;
        }
        a.section("اختر طريقة المزامنة (الطريقة نفسها على كل الأجهزة)", null, null);
        final String kind = transport(a.db);
        LinearLayout g = a.group();
        a.groupRow(g, R.drawable.ic_upload, "drive".equals(kind) ? a.green : a.primaryInk, "\u200FGoogle Drive مباشرة — الأسهل",
                "التطبيق يتصل بحساب Google نفسه على كل جهاز، في مجلد مخفي خاص به. تصل التغييرات خلال ثوانٍ دون فتح تطبيق Drive.",
                "drive".equals(kind) ? "✓" : "اختيار", v -> choose("drive"));
        a.groupRow(g, R.drawable.ic_db, "server".equals(kind) ? a.green : a.primaryInk, "خادم المزامنة الخاص بك — الأسرع",
                "خادم تملكه (مرفق مع التطبيق). تحكم كامل وسرعة فورية، ويحتاج استضافة.", "server".equals(kind) ? "✓" : "اختيار", v -> choose("server"));
        a.groupRow(g, R.drawable.ic_doc, "folder".equals(kind) ? a.green : a.primaryInk, "مجلد مشترك",
                "مجلد في Drive أو تزامنه FolderSync أو Syncthing. قد يتأخر وصول التغييرات حتى يحدّث تطبيق المزامنة المجلد.",
                "folder".equals(kind) ? "✓" : "اختيار", v -> choose("folder"));
        a.button("شرح مفصّل لطريقة «" + ("drive".equals(kind) ? "Google Drive" : "server".equals(kind) ? "خادمك" : "المجلد المشترك") + "»", Color.rgb(120, 120, 120),
                v -> help(kind, this::show));
        if (!License.current.sync()) return;
        if ("drive".equals(kind)) introDrive();
        else if ("server".equals(kind)) introServer();
        else introFolder();
    }

    private void choose(String kind) {
        a.db.setSettingQuiet("sync_transport", kind);
        show();
    }

    /** بعد اختيار الناقل وتجهيزه: إنشاء الخزنة أو الانضمام إليها. */
    private void createOrJoin(final String what) {
        a.busy("جارٍ الاتصال بـ" + what + "…", p -> new SyncEngine(a.db, folder(a, a.db)).vaultExists(), res -> {
            boolean exists = (Boolean) res;
            if (exists) {
                a.notice(R.drawable.ic_check, "في " + what + " خزنة مزامنة: انضم إليها بكلمة سرها.", false, null, null);
                a.button("الانضمام إلى المزامنة", a.primary, v -> joinDialog());
            } else {
                a.notice(R.drawable.ic_info, "لا خزنة بعد في " + what + ": أنشئ المزامنة من الجهاز الذي فيه بياناتك الكاملة (مرة واحدة)، ثم انضم من الأجهزة الأخرى.", false, null, null);
                a.button("إنشاء المزامنة من بيانات هذا الجهاز", a.primary, v -> createDialog());
            }
        });
    }

    private void introDrive() {
        LinearLayout h = a.card();
        h.addView(a.text("\u200FGoogle Drive مباشرة", 16, a.primaryInk, true));
        h.addView(a.text("سجّل الدخول بحساب Google نفسه على كل جهاز (الهاتف والماك والويندوز). يطلب التطبيق صلاحية «مجلد التطبيق» فقط: "
                + "لا يرى ملفاتك في Drive ولا تظهر ملفاته لك. الملفات مشفّرة قبل أن تغادر الجهاز.", 13, a.dark, false));
        final boolean ok = "1".equals(a.db.setting("sync_drive_ok", ""));
        final String acct = a.db.setting("drive_account", "");
        // 10.14: اختيار الحساب صراحة من حسابات الهاتف، فيتغيّر فعلًا ويظهر أي حساب متصل
        a.button(ok ? "✓ متصل" + (acct.isEmpty() ? "" : " بـ " + acct) + " — تغيير الحساب" : "1) اختيار حساب Google وتسجيل الدخول", ok ? a.accent : a.primary,
                v -> signIn(acct, this::show));
        if (ok) createOrJoin("Google Drive");
    }

    /** 10.14: اختيار حساب Google من الهاتف ثم الموافقة على صلاحية «مجلد التطبيق» له، وحفظه لهذا الجهاز. */
    private void signIn(String current, final Runnable after) {
        DriveAuth.pickAccount(a, current, email -> DriveAuth.connect(a, email, new DriveAuth.Done() {
            @Override public void ok() {
                a.db.setSettingQuiet("drive_account", email);
                a.db.setSettingQuiet("sync_drive_ok", "1");
                a.toast("تم الاتصال بـ " + email + " ✓");
                after.run();
            }

            @Override public void fail(Exception e) { a.error(e); }
        }));
    }

    /** 11.3: هل الخطأ انتهاء دخول Google (لا شبكة ولا غيره)؟ */
    static boolean authError(String m) {
        if (m == null) return false;
        String l = m.toLowerCase(java.util.Locale.ROOT);
        return m.contains("سجّل الدخول") || m.contains("رمز Google") || m.contains("صلاحية الدخول") || l.contains("invalid_grant")
                || l.contains("unauthorized") || l.contains("401") || l.contains("userrecoverableauth") || l.contains("need permission")
                || l.contains("token has been expired") || l.contains("revoked");
    }

    /** 11.3: إعادة الدخول بالحساب نفسه (لأي مستخدم). */
    void reLogin(final String acct) {
        DriveAuth.connect(a, acct, new DriveAuth.Done() {
            @Override public void ok() {
                a.db.setSettingQuiet("sync_drive_ok", "1");
                a.toast("تم الاتصال بـ Google Drive ✓");
                now(SyncScreen.this::show);
            }

            @Override public void fail(Exception e) { a.error(e); }
        });
    }

    private void introServer() {
        LinearLayout h = a.card();
        h.addView(a.text("خادم المزامنة الخاص بك", 16, a.primaryInk, true));
        h.addView(a.text("على الجهاز الرئيسي: اكتب عنوان خادمك (https://…) ورمز الخادم إن وُجد، ثم أنشئ المزامنة؛ يظهر بعدها «رابط الانضمام».\n"
                + "على الأجهزة الأخرى: الصق «رابط الانضمام» نفسه ورمز الخادم ثم انضم.", 13, a.dark, false));
        final EditText url = a.field("عنوان الخادم أو رابط الانضمام");
        String cur = a.db.setting("sync_server", "");
        String v0 = a.db.setting("sync_server_vault", "");
        url.setText(cur.isEmpty() ? "" : v0.isEmpty() ? cur : HttpSyncFolder.link(cur, v0));
        url.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        a.body.addView(url, a.lp(-1, 52, 4));
        final EditText key = a.field("رمز الخادم (إن طلبه خادمك)");
        key.setText(a.db.setting("sync_server_key", ""));
        Ui.secret(key, false);
        a.body.addView(key, a.lp(-1, 52, 4));
        a.button("1) حفظ وفحص الاتصال", a.accent, v -> {
            final String[] pv = HttpSyncFolder.parseLink(url.getText().toString());
            try {
                HttpSyncFolder.checkUrl(pv[0]);
            } catch (Exception e) {
                a.error(e);
                return;
            }
            final String k = key.getText().toString().trim();
            a.busy("جارٍ فحص الخادم…", p -> new HttpSyncFolder(pv[0], pv[1], k).ping(), res -> {
                a.db.setSettingQuiet("sync_server", pv[0]);
                a.db.setSettingQuiet("sync_server_vault", pv[1] == null ? "" : pv[1]);
                a.db.setSettingQuiet("sync_server_key", k);
                a.toast(String.valueOf(res));
                show();
            });
        });
        if (!cur.isEmpty()) createOrJoin("الخادم");
    }

    private void introFolder() {
        LinearLayout h = a.card();
        h.addView(a.text("المجلد المشترك", 16, a.primaryInk, true));
        h.addView(a.text("اختر المجلد نفسه على كل جهاز:\n"
                + "• مجلد في Google Drive: قد يتأخر ظهور تغييرات الأجهزة الأخرى حتى يحدّث تطبيق Drive المجلد على الهاتف.\n"
                + "• \u200FFolderSync أو Syncthing: مجلد في ذاكرة الهاتف تزامنه هذه التطبيقات — أسرع وأثبت.\n"
                + "• على الماك والويندوز: مجلد داخل Google Drive أو OneDrive أو Dropbox بعد تثبيت تطبيقها.", 13, a.dark, false));
        a.button("1) اختيار مجلد المزامنة", a.accent, v -> pick(this::show));
        String tree = a.db.setting("sync_tree", "");
        if (tree.isEmpty()) return;
        a.hint("المجلد: " + new SafSyncFolder(a, Uri.parse(tree)).describe());
        createOrJoin("المجلد");
    }

    void pick(Runnable after) {
        afterPick = after;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        a.toast("اختر المجلد المشترك (أو أنشئ واحدًا) ثم اضغط «استخدام هذا المجلد».");
        a.startForResult(i, PICK_TREE);
    }

    void onResult(int code, Intent data) {
        if (code != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            a.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            a.error(new IllegalStateException("لم يمنح هذا المجلد صلاحية دائمة. اختر مجلدًا في ذاكرة الهاتف."));
            return;
        }
        a.db.setSettingQuiet("sync_tree", tree.toString());
        if (afterPick != null) afterPick.run();
    }

    private EditText secret(LinearLayout l, String hint) {
        EditText e = a.field(hint);
        Ui.secret(e, false);
        l.addView(e, a.lp(-1, 50, 3));
        return e;
    }

    private void createDialog() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText name = a.field("اسم هذا الجهاز (مثل: هاتف المحل)");
        l.addView(name, a.lp(-1, 50, 3));
        final EditText p1 = secret(l, "كلمة سر المزامنة (8 محارف على الأقل)"), p2 = secret(l, "أعد كتابتها");
        TextView n = a.text("تُطلب كلمة السر على كل جهاز ينضم. لا تُسترد إن نسيتها، إلا برمز الاسترداد الذي يظهر بعد الإنشاء.", 12, a.muted, false);
        l.addView(n);
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("إنشاء المزامنة").setView(l).setPositiveButton("إنشاء", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String s1 = p1.getText().toString();
            if (s1.length() < 8) {
                p1.setError("8 محارف على الأقل");
                return;
            }
            if (!s1.equals(p2.getText().toString())) {
                p2.setError("غير متطابقة");
                return;
            }
            d.dismiss();
            final String dn = name.getText().toString();
            a.busy("جارٍ إنشاء الخزنة المشفّرة ورفع لقطة البيانات…", p -> new SyncEngine(a.db, folder(a, a.db)).create(s1.toCharArray(), dn), res -> {
                if ("server".equals(transport(a.db))) a.db.setSettingQuiet("sync_server_vault", SyncEngine.status(a.db).vaultId);
                recoveryDialog((String) res);
            });
        }));
        d.show();
    }

    private void recoveryDialog(final String code) {
        new AlertDialog.Builder(a).setTitle("رمز الاسترداد — احفظه الآن")
                .setMessage("أُنشئت المزامنة ✓\n\nإن نُسيت كلمة السر فهذا الرمز وحده يفتح الخزنة. يظهر مرة واحدة فقط: انسخه واحفظه في مكان آمن خارج هذا الهاتف.\n\n" + code)
                .setCancelable(false)
                .setPositiveButton("نسخ الرمز", (x, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("رمز استرداد المزامنة", code));
                    a.toast("نُسخ الرمز");
                    show();
                }).setNegativeButton("حفظته", (x, w) -> show()).show();
    }

    private void joinDialog() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText name = a.field("اسم هذا الجهاز (مثل: هاتف البيت)");
        l.addView(name, a.lp(-1, 50, 3));
        final Ui.Seg how = a.new Seg(new String[]{"كلمة السر", "رمز الاسترداد"}, null, 0, false);
        l.addView(how.view, a.lp(-1, -2, 2));
        final EditText pw = secret(l, "كلمة سر المزامنة أو رمز الاسترداد");
        TextView warn = a.text("⚠ تُستبدل بيانات هذا الجهاز ببيانات المزامنة. إن كان فيه بيانات تحتاجها فاحفظ نسخة احتياطية أولًا.", 13, a.red, true);
        l.addView(warn);
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("الانضمام إلى المزامنة").setView(l).setPositiveButton("انضمام", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String s = pw.getText().toString();
            if (s.trim().isEmpty()) {
                pw.setError("مطلوبة");
                return;
            }
            d.dismiss();
            final boolean rc = how.selected == 1;
            final String dn = name.getText().toString();
            // 12.7: كلمة السر تُفحص هنا فقط (لحظات)، ثم ينتقل الجهاز إلى شاشة التحميل بشريط تقدم
            a.busy("جارٍ فتح الخزنة…", p -> {
                new SyncEngine(a.db, folder(a, a.db)).joinPrepare(s.toCharArray(), rc, dn);
                return null;
            }, res -> joinScreen());
        }));
        d.show();
    }

    // ===================================================================================
    // 12.7: شاشة تحميل الانضمام: شريط تقدم، تكمل في الخلفية، وتُستأنف من تلقاء نفسها
    // ===================================================================================

    private static volatile Thread joinThread;

    static boolean joinRunning() {
        Thread t = joinThread;
        return t != null && t.isAlive();
    }
    private static volatile int joinPct;
    private static volatile String joinMsg = "", joinErr;
    private static volatile boolean joinDone;
    private final Runnable joinTick = new Runnable() {
        @Override public void run() { refreshJoin(); }
    };
    private ProgressBar joinBar;
    private TextView joinLine, joinErrView;
    private LinearLayout joinErrBox;
    private boolean joinShown;

    /** يبدأ التحميل (أو يكمله) ويعرض الشاشة. */
    void joinScreen() {
        joinShown = true;
        a.base("الانضمام إلى المزامنة", null);
        String name = SyncEngine.pendingName(a.db);
        TextView t = a.text("انضم هذا الجهاز" + (name.isEmpty() ? "" : " («" + name + "»)") + " ✓\nجارٍ تحميل بيانات المكتب إليه.", 17, a.primaryInk, true);
        t.setGravity(Gravity.CENTER);
        a.add(t);
        joinBar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        joinBar.setMax(100);
        a.body.addView(joinBar, a.lp(-1, 18, 14));
        joinLine = a.text("", 15, a.ink, false);
        joinLine.setGravity(Gravity.CENTER);
        a.add(joinLine);
        joinErrBox = new LinearLayout(a);
        joinErrBox.setOrientation(LinearLayout.VERTICAL);
        joinErrView = a.text("", 14, a.red, true);
        joinErrBox.addView(joinErrView);
        joinErrBox.addView(a.smallButton("إعادة المحاولة", a.primary, v -> startJoin()), a.lp(-1, 46, 6));
        joinErrBox.addView(a.smallButton("إلغاء الانضمام", Color.rgb(130, 130, 130), v -> cancelJoin()), a.lp(-1, 42, 4));
        joinErrBox.setVisibility(android.view.View.GONE);
        a.body.addView(joinErrBox);
        a.hint("يمكنك ترك التطبيق أثناء التحميل: يكمل في الخلفية، وإن انقطع (إغلاق أو انقطاع شبكة) يكمل من تلقاء نفسه عند فتح التطبيق. "
                + "بيانات هذا الجهاز لا تُستبدل إلا بعد اكتمال تنزيل بيانات المكتب وصوره.");
        startJoin();
    }

    private void startJoin() {
        if (joinThread != null && joinThread.isAlive()) {
            refreshJoin();
            return;
        }
        joinErr = null;
        joinDone = false;
        joinPct = 0;
        joinMsg = "جارٍ البدء…";
        final SyncFolder f = folder(a, a.db);
        final Db db = a.db;
        Thread th = new Thread(() -> {
            Exception last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                try {
                    new SyncEngine(db, f).joinFinish((pc, m) -> {
                        joinPct = pc;
                        joinMsg = m;
                    });
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    if (!transientError(e) || attempt == 3) break;
                    joinMsg = "انقطع الاتصال، إعادة المحاولة…";
                    try {
                        Thread.sleep(attempt == 0 ? 5000 : 15000);
                    } catch (InterruptedException x) {
                        break;
                    }
                }
            }
            if (last != null) joinErr = Ui.friendly(last);
            else joinDone = true;
        }, "sync-join");
        joinThread = th;
        th.start();
        refreshJoin();
    }

    private void refreshJoin() {
        handler.removeCallbacks(joinTick);
        if (!joinShown) return;
        if (joinBar != null) joinBar.setProgress(joinPct);
        if (joinLine != null) joinLine.setText(joinPct + "% — " + joinMsg);
        if (joinErr != null) {
            joinErrView.setText("تعذّر إكمال التحميل: " + joinErr);
            joinErrBox.setVisibility(android.view.View.VISIBLE);
            return;
        }
        joinErrBox.setVisibility(android.view.View.GONE);
        if (joinDone) {
            joinShown = false;
            joinDone = false;
            Reliability.defer(15 * 60_000L);
            a.toast("اكتمل تحميل بيانات المكتب ✓");
            // 10.1: البيانات صارت بيانات المزامنة: إن كان فيها مستخدمون يدخل كل شخص بحسابه
            SmartApp.currentUser = null;
            Db.currentUserName = "المستخدم المحلي";
            a.afterUnlock();
            return;
        }
        handler.postDelayed(joinTick, 400);
    }

    private void cancelJoin() {
        a.confirm("إلغاء الانضمام", "يتوقف الانضمام ويبقى هذا الجهاز خارج المزامنة. يمكنك الانضمام لاحقًا بكلمة السر.", "إلغاء الانضمام", () -> {
            SyncEngine.cancelPending(a.db);
            joinShown = false;
            joinErr = null;
            a.afterUnlock();
        });
    }

    /** خرجت شاشة أخرى فوق شاشة التحميل (أو عاد التطبيق): يتوقف التحديث الدوري فقط؛ العمل نفسه مستمر. */
    void leaveJoinScreen() {
        joinShown = false;
        handler.removeCallbacks(joinTick);
    }

    // ===================================================================================
    // 10.3: الشرح المفصّل لكل طريقة، والإصلاح
    // ===================================================================================

    void help(final String kind, final Runnable back) {
        a.here(() -> help(kind, back));
        a.tabbedScreen("شرح المزامنة", back, "more");
        SyncHelp.Guide g = "drive".equals(kind) ? SyncHelp.drive(true) : "server".equals(kind) ? SyncHelp.server() : SyncHelp.folder(true);
        LinearLayout c = a.card();
        c.addView(rtl(a.text("\u200F" + g.title, 18, a.primaryInk, true)));
        c.addView(rtl(a.text(g.intro, 14, a.ink, false)));
        a.section("الخطوات", null, null);
        LinearLayout s = a.card();
        for (int i = 0; i < g.steps.length; i++) s.addView(rtl(a.text((i + 1) + ") " + g.steps[i], 15, a.ink, false)));
        a.section("إن واجهتك مشكلة", null, null);
        LinearLayout n = a.card();
        for (String x : g.notes) n.addView(rtl(a.text("• " + x, 14, a.dark, false)));
        a.section("قبل أي طريقة", null, null);
        LinearLayout b = a.card();
        for (String x : SyncHelp.BASICS) b.addView(rtl(a.text("• " + x, 14, a.dark, false)));
    }

    private TextView rtl(TextView t) {
        t.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
        t.setPadding(0, a.dp(3), 0, a.dp(3));
        return t;
    }

    private void repairDialog() {
        if (!a.require(Auth.Perm.SYNC)) return;
        final boolean pw = SyncEngine.repairNeedsPassword(a.db);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(a.text("يكتب هذا الجهاز ملفات المزامنة المشفّرة في المكان المختار الآن (" + SyncEngine.status(a.db).folder + "): رأس الخزنة ولقطة كاملة وحزمه. "
                + "بياناتك لا تتغير. بعدها اختر المكان نفسه على الأجهزة الأخرى (أو «الطريقة: تغيير» عليها).", 14, a.ink, false));
        final EditText p = pw ? secret(l, "كلمة سر المزامنة (8 محارف على الأقل)") : null;
        if (pw) l.addView(a.text("يُكتب رأس جديد بكلمة السر هذه، ويظهر رمز استرداد جديد (القديم لا يعمل هنا).", 12, a.muted, false));
        new AlertDialog.Builder(a).setTitle("إصلاح مكان المزامنة").setView(l).setPositiveButton("إصلاح", (d, w) -> {
            final char[] s = p == null ? null : p.getText().toString().toCharArray();
            a.busy("جارٍ كتابة ملفات المزامنة…", x -> new SyncEngine(a.db, folder(a, a.db)).repair(s), res -> {
                String rc = (String) res;
                if (!rc.isEmpty()) recoveryDialog(rc);
                else {
                    a.toast("أُصلح مكان المزامنة ✓");
                    now(this::show);
                }
            });
        }).setNegativeButton("إلغاء", null).show();
    }

    /** 11.6: رؤية الأجهزة وسجلاتها وأخطائها: صلاحية يختارها المدير («إدارة المزامنة» تشملها). */
    boolean seesDevices() { return a.can(Auth.Perm.DEVICES) || a.can(Auth.Perm.SYNC); }

    private void status(SyncEngine.Status st) {
        final boolean devs = seesDevices();
        if (SyncEngine.headerMissing(a.db)) {
            a.notice(R.drawable.ic_warn, "ملفات المزامنة غير موجودة في المكان المختار (" + st.folder + "). إن نقلت المزامنة إلى هنا فاضغط «إصلاح»، أو اختر المكان الصحيح من «الطريقة: تغيير».",
                    true, "إصلاح", v -> repairDialog());
        }
        if (!st.digestAlert.isEmpty()) a.notice(R.drawable.ic_warn, st.digestAlert, true, null, null);
        if (!st.movedTo.isEmpty()) movedNotice(st);
        else if (!st.lastError.isEmpty()) {
            // 11.3: خطأ دخول Google: زر «دخول» في الرسالة نفسها لأي مستخدم
            boolean auth = "drive".equals(transport(a.db)) && authError(st.lastError);
            String when = st.lastErrorAt > 0 ? MainActivity.agoAt(st.lastErrorAt) : "";
            String with = st.lastErrorWith.isEmpty() || !devs ? "" : "مع " + st.lastErrorWith;
            String head = when.isEmpty() && with.isEmpty() ? "آخر خطأ: " : "آخر خطأ (" + when + (when.isEmpty() || with.isEmpty() ? "" : " • ") + with + "): ";
            a.notice(R.drawable.ic_warn, head + Ui.netFriendly(st.lastError), true, auth ? "دخول" : null,
                    auth ? v -> reLogin(a.db.setting("drive_account", "")) : null);
        }
        if (st.paused) a.notice(R.drawable.ic_info, "المزامنة التلقائية موقوفة مؤقتًا على هذا الجهاز. تغييراتك محفوظة وتُرسل عند الاستئناف أو بزر «مزامنة الآن».", false, "استئناف", v -> {
            a.db.setSettingQuiet("sync_paused", "0");
            SyncJob.schedule(a, true, "1".equals(a.db.setting("sync_wifi", "0")));
            show();
        });
        for (final JSONObject m : devs ? st.stale : new java.util.ArrayList<JSONObject>()) {
            long days = (Clock.now() - m.optLong("at")) / 86_400_000L;
            boolean canRemove = a.can(Auth.Perm.SYNC) && !m.optString("dev").equals(st.founderDev);
            a.notice(R.drawable.ic_warn, "«" + m.optString("name") + "» لم يتزامن منذ " + days + " يومًا. ما لم يستلمه من الملفات لا يُحذف حتى يعود."
                    + (canRemove ? " إن ضاع أو لم يعد يُستخدم فأزله من المزامنة." : ""), true, canRemove ? "إزالة" : null, canRemove ? v -> removeDevice(m) : null);
        }
        LinearLayout g = a.group();
        a.groupRow(g, R.drawable.ic_check2, a.green, "المزامنة مفعّلة", "هذا الجهاز: " + st.deviceName + (st.founder ? " (الجهاز الرئيسي)" : " • الرئيسي: " + st.founderName()),
                "تغيير الاسم", v -> renameDialog(st.deviceName));
        if (!st.founder && a.can(Auth.Perm.SYNC))
            a.groupRow(g, R.drawable.ic_key, a.primaryInk, "الجهاز الرئيسي: " + st.founderName(), "إن ضاع أو تعطل يمكن لهذا الجهاز استلام دوره برمز الاسترداد",
                    "ضاع؟", v -> claimFounder(st));
        a.groupRow(g, R.drawable.ic_clock, a.primaryInk, "آخر مزامنة", st.lastSync > 0 ? MainActivity.ago(st.lastSync) : "لم تتم بعد", null, null);
        a.groupRow(g, R.drawable.ic_upload, a.primaryInk, "بانتظار الإرسال", st.pending + st.unsent == 0 ? "لا شيء — كل تغييراتك أُرسلت" : (st.pending + st.unsent) + " تغييرًا/حزمة", null, null);
        String k = transport(a.db);
        a.groupRow(g, R.drawable.ic_db, a.primaryInk, "الطريقة: " + kindName(a.db), st.folder, a.can(Auth.Perm.SYNC) ? "تغيير" : null,
                a.can(Auth.Perm.SYNC) ? v -> changeTransport() : null);
        if ("server".equals(k)) {
            final String link = HttpSyncFolder.link(a.db.setting("sync_server", ""), st.vaultId);
            a.groupRow(g, R.drawable.ic_key, a.primaryInk, "رابط الانضمام للأجهزة الأخرى", link, "نسخ", v -> {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("رابط الانضمام", link));
                a.toast("نُسخ رابط الانضمام. أرسله لجهازك الآخر مع كلمة سر المزامنة.");
            });
        }
        // 12.7: الجولة نفسها التي يستخدمها زر الشريط: تكمل إن تُرك التطبيق، ولا تُظهر خطأ عابرًا عند العودة
        a.button("مزامنة الآن", a.primary, v -> now(this::show));

        a.button("شرح مفصّل لهذه الطريقة", Color.rgb(120, 120, 120), v -> help(transport(a.db), this::show));

        if (!devs) a.hint("قائمة أجهزة المزامنة وسجلاتها تظهر لمن أعطاه المدير صلاحية «رؤية أجهزة المزامنة» (المستخدمون ← الصلاحيات).");
        else a.section("الأجهزة", null, null);
        LinearLayout dg = a.group();
        String prov = "";
        SyncFolder fo = folder(a, a.db);
        if (fo instanceof SafSyncFolder) prov = ((SafSyncFolder) fo).provider();
        for (JSONObject m : devs ? st.members : new java.util.ArrayList<JSONObject>()) {
            String dev = m.optString("dev");
            boolean me = dev.equals(st.device);
            String role = dev.equals(st.founderDev) || (me && st.founder) ? " • الرئيسي" : "";
            String line = m.optString("platform") + " • الإصدار " + m.optString("app") + role + " • آخر ظهور " + MainActivity.agoAt(m.optLong("at"));
            if (me) line += "\nأرسل " + st.mySeq + " حزمة" + (st.unsent > 0 ? " (" + st.unsent + " لم تُرفع بعد)" : " — كلها في المجلد");
            else if (st.peers.containsKey(dev)) line += "\n" + SyncEngine.peerText(st.peers.get(dev), prov);
            JSONObject pr = st.peers.get(dev);
            boolean late = pr != null && pr.optLong("have") < pr.optLong("announced");
            final JSONObject mm = m;
            boolean removable = !me && a.can(Auth.Perm.SYNC) && !dev.equals(st.founderDev);
            // 10.5: الجهاز الرئيسي ينقل دوره من هنا («إجراءات»: اجعله الرئيسي / إزالة)
            boolean actions = removable && st.founder;
            // 10.17: المدير يعيد تسمية أي جهاز (من غير الجهاز الرئيسي أيضًا)
            boolean isAdmin = !a.db.usersEnabled() || (SmartApp.currentUser != null && Auth.isAdmin(SmartApp.currentUser));
            final boolean renameOnly = !me && !actions && isAdmin, canRm = removable;
            // 11.5: كل جهاز له صفحته: سجله (ما وصل منه وأخطاؤه) وآخر ظهور بالساعة، وإجراءاته
            final String fLine = line;
            final boolean fActions = actions, fRename = renameOnly, fRm = canRm, fMe = me;
            a.groupRow(dg, R.drawable.ic_phone, me ? a.green : late ? a.red : a.primaryInk, m.optString("name") + (me ? "  (هذا الجهاز)" : ""), line,
                    "السجل", v -> deviceScreen(mm, fLine, fMe, fActions, fRename, fRm));
        }
        if (!st.note.isEmpty()) a.hint(st.note);
        boolean anyLate = false;
        for (JSONObject p : st.peers.values()) if (p.optLong("have") < p.optLong("announced") && "missing".equals(p.optString("state"))) anyLate = true;
        if (anyLate && !prov.isEmpty() && !prov.equals("ذاكرة الهاتف")) {
            a.notice(R.drawable.ic_info, "بعض الحزم موجودة في " + prov + " لكن لم تظهر على هذا الهاتف بعد. تطبيق " + prov
                    + " يحدّث المجلد على الهاتف كل فترة: افتح تطبيقه لحظة ثم ارجع واضغط «مزامنة الآن». إن تكرر التأخير فاستخدم مجلدًا تزامنه FolderSync أو Syncthing (أسرع).", false, null, null);
        }
        if (st.members.isEmpty()) a.groupRow(dg, R.drawable.ic_info, a.muted, "اضغط «مزامنة الآن» لتظهر الأجهزة", null, null, null);

        int dups = SyncEngine.duplicates(a.db).size();
        if (dups > 0) a.notice(R.drawable.ic_warn, dups + " زوج حركات قد يكون مكررًا (سُجّل من جهازين)", true, "مراجعة", v -> showDuplicates());
        // 11.6: «للمراجعة» وسجل التنبيهات لمن يملك «سجل الإشعارات»
        final List<String[]> cs = a.can(Auth.Perm.NOTIFS) ? SyncEngine.conflicts(a.db) : new java.util.ArrayList<String[]>();
        if (a.can(Auth.Perm.NOTIFS)) a.section("للمراجعة (" + cs.size() + ")", cs.size() > 1 ? "تمت للكل" : null, cs.size() > 1 ? v -> a.confirm("تمت للكل", "تُعلَّم كل التنبيهات الـ" + cs.size()
                + " كمُراجَعة وتختفي من هنا ومن الصفحة الرئيسية. (الحركات المكررة لها مراجعتها الخاصة.)", "تمت", () -> {
            SyncEngine.resolveAll(a.db);
            show();
        }) : null);
        // 10.18: ما عُلّم «تمت» لا يضيع: يبقى في سجل الإشعارات
        if (a.can(Auth.Perm.NOTIFS)) a.body.addView(a.smallButton("سجل تنبيهات المزامنة والإشعارات", a.accent, v -> a.notifScreen.showLog(1)), a.lp(-2, 40, 2));
        if (cs.isEmpty() && a.can(Auth.Perm.NOTIFS)) a.hint("لا تعارضات. التعارض يحدث نادرًا: تعديل الحركة نفسها من جهازين، أو حركة لمكتب حذفه جهاز آخر، أو حركة بتاريخ داخل فترة مقفلة.");
        LinearLayout cg = a.group();
        for (final String[] c : cs) {
            a.groupRow(cg, SyncStore.isNotice(c[2]) ? R.drawable.ic_info : R.drawable.ic_warn, SyncStore.isNotice(c[2]) ? a.primaryInk : a.red, SyncStore.kindName(c[2]), c[5] + "\n" + Model.date(Long.parseLong(c[1])), "تمت", v -> {
                SyncEngine.resolveConflict(a.db, Long.parseLong(c[0]));
                show();
            });
        }

        List<JSONObject> hist = SyncEngine.history(a.db);
        if (!hist.isEmpty() && devs) {
            a.section("آخر الجولات", "مسح السجلات", v -> a.confirm("مسح سجلات المزامنة", "يُمسح «آخر الجولات» وسجل كل جهاز على هذا الجهاز فقط. لا يمس البيانات ولا ملفات المزامنة.",
                    "مسح", () -> {
                        SyncEngine.clearDevLog(a.db, "");
                        show();
                    }));
            LinearLayout hg = a.group();
            int n = 0;
            for (JSONObject x : hist) {
                if (n++ >= 6) break;
                boolean bad = x.has("e");
                a.groupRow(hg, bad ? R.drawable.ic_warn : R.drawable.ic_check2, bad ? a.red : a.green, MainActivity.agoAt(x.optLong("at")),
                        x.optString("t") + (bad ? "\n" + x.optString("e") : ""), null, null);
            }
        }

        a.section("التلقائي على هذا الجهاز", null, null);
        LinearLayout ag = a.group();
        final boolean paused = SyncEngine.paused(a.db);
        a.groupRow(ag, R.drawable.ic_sync, paused ? a.red : a.green, paused ? "المزامنة التلقائية موقوفة مؤقتًا" : "المزامنة التلقائية تعمل",
                paused ? "لا ترسل ولا تستلم إلا بزر «مزامنة الآن». التغييرات تُحفظ وتُرسل عند الاستئناف." : "تعمل في الخلفية؛ أوقفها مؤقتًا إن كنت على اتصال محدود",
                paused ? "استئناف" : "إيقاف مؤقت", v -> {
                    a.db.setSettingQuiet("sync_paused", paused ? "0" : "1");
                    SyncJob.schedule(a, paused, "1".equals(a.db.setting("sync_wifi", "0")));
                    show();
                });
        final boolean on = "1".equals(a.db.setting("sync_after_change", "1"));
        a.groupRow(ag, R.drawable.ic_upload, a.primaryInk, "مزامنة بعد كل حركة", on ? "مفعّلة: تُرسل التغييرات خلال ثوانٍ من حفظها" : "متوقفة: على الفترة المحددة وعند الفتح فقط",
                on ? "إيقاف" : "تفعيل", v -> {
                    a.db.setSettingQuiet("sync_after_change", on ? "0" : "1");
                    show();
                });
        final int every = SyncEngine.everySeconds(a.db);
        a.groupRow(ag, R.drawable.ic_clock, a.primaryInk, "كل كم والتطبيق مفتوح", everyName(every) + " • في الخلفية كل 15 دقيقة تقريبًا (أقل ما يسمح به أندرويد)", "تغيير", v -> {
            final int[] secs = {30, 90, 300, 900};
            String[] names = new String[secs.length];
            for (int i = 0; i < secs.length; i++) names[i] = everyName(secs[i]);
            new AlertDialog.Builder(a).setTitle("المزامنة التلقائية كل").setItems(names, (d, w) -> {
                a.db.setSettingQuiet("sync_every", "" + secs[w]);
                onResume();
                show();
            }).show();
        });
        final boolean wifi = "1".equals(a.db.setting("sync_wifi", "0"));
        a.groupRow(ag, R.drawable.ic_phone, a.primaryInk, "عبر الواي فاي فقط", wifi ? "مفعّل: لا مزامنة تلقائية على بيانات الجوال (زر «مزامنة الآن» يعمل دائمًا)"
                : "متوقف: تعمل على الواي فاي وبيانات الجوال", wifi ? "إيقاف" : "تفعيل", v -> {
                    a.db.setSettingQuiet("sync_wifi", wifi ? "0" : "1");
                    SyncJob.schedule(a, !SyncEngine.paused(a.db), !wifi);
                    show();
                });

        a.section("مكان المزامنة وحجمه", null, null);
        LinearLayout pg = a.group();
        a.groupRow(pg, R.drawable.ic_db, a.primaryInk, "الحجم وعدد الملفات", "اضغط «عرض» لقراءة المكان الآن", "عرض", v -> showUsage());
        final SyncFolder cur = folder(a, a.db);
        boolean canClean = cur != null && cur.canDelete();
        a.groupRow(pg, R.drawable.ic_trash, a.primaryInk, "التنظيف التلقائي",
                (canClean ? "يحذف كل جهاز حزمه القديمة بعد أن تصل إلى كل الأجهزة، ولقطاته القديمة، والصور التي لم يعد يشير إليها شيء. لا يمس بياناتك."
                        : "هذه الطريقة لا تسمح بحذف الملفات.") + (st.lastClean.isEmpty() ? "" : "\nآخر تنظيف: " + st.lastClean),
                canClean && a.can(Auth.Perm.SYNC) ? "تنظيف الآن" : null, canClean && a.can(Auth.Perm.SYNC) ? v -> a.busy("جارٍ التنظيف…", p -> new SyncEngine(a.db, folder(a, a.db)).clean(true), res -> {
                    String r = (String) res;
                    a.toast(r.isEmpty() ? "لا شيء قديم يُحذف الآن" : r);
                    show();
                }) : null);
        // 11.3: إعادة الدخول بالحساب نفسه لكل مستخدم (لا تغيّر الحساب ولا أي إعداد): كانت للمدير وحده، فإن انتهت صلاحية دخول
        // جهاز موظف توقفت مزامنته ولا يصل إليه حتى ترقيته إلى مدير (لأن الترقية نفسها تصل بالمزامنة).
        if ("drive".equals(k)) {
            final String acct0 = a.db.setting("drive_account", "");
            a.groupRow(pg, R.drawable.ic_sync, a.primaryInk, "إعادة تسجيل الدخول بالحساب نفسه",
                    "إن انتهت صلاحية الدخول" + (acct0.isEmpty() ? "" : " (" + acct0 + ")"), "دخول", v -> reLogin(acct0));
        }
        if (a.can(Auth.Perm.SYNC)) {
            if ("server".equals(k)) a.groupRow(pg, R.drawable.ic_edit, a.primaryInk, "تعديل عنوان الخادم أو رمزه", "للخادم نفسه (مثل تغيير رمز الخادم). للانتقال إلى خادم آخر استعمل «الطريقة: تغيير»", "تعديل", v -> editServer());
            else if ("drive".equals(k)) {
                // 10.14: الحساب المتصل ظاهر، وتغييره نقلٌ منظّم أو بداية جديدة؛ والحساب القديم تُحذف الخزنة منه بعد انتقال الأجهزة
                final String acct = a.db.setting("drive_account", "");
                a.groupRow(pg, R.drawable.ic_user, a.primaryInk, "حساب Google: " + (acct.isEmpty() ? "غير محدد (الحساب الذي وافقت عليه أول مرة)" : acct),
                        "استعمل الحساب نفسه على كل الأجهزة", "تغيير", v -> changeAccount(acct));
                final String old = a.db.setting("drive_account_old", "");
                if (!old.isEmpty())
                    a.groupRow(pg, R.drawable.ic_trash, a.red, "الحساب القديم: " + old, "نُقلت المزامنة منه. بعد أن تنتقل كل الأجهزة احذف الخزنة منه", "حذف", v -> purgeOld(old));
            }
            else a.groupRow(pg, R.drawable.ic_doc, a.primaryInk, "إعادة اختيار المجلد نفسه", "إن ظهرت «انتهت صلاحية الوصول». لنقل المزامنة إلى مجلد آخر استعمل «الطريقة: تغيير»", "اختيار", v -> pick(() -> now(this::show)));
        }

        a.section("الأمان", null, null);
        LinearLayout sg = a.group();
        if (!a.can(Auth.Perm.SYNC)) {
            a.hint("كلمة السر ورمز الاسترداد وإزالة الأجهزة وإيقاف المزامنة لمن يملك صلاحية «إدارة المزامنة».");
            return;
        }
        a.groupRow(sg, R.drawable.ic_key, a.primaryInk, "تغيير كلمة سر المزامنة", "للأجهزة التي تنضم لاحقًا؛ المنضمة تبقى كما هي", null, v -> changePassword());
        a.groupRow(sg, R.drawable.ic_shield, a.primaryInk, "رمز استرداد جديد", "إن ضاع الرمز القديم أو اطّلع عليه غيرك: يتوقف القديم ويظهر جديد مرة واحدة", null, v -> newRecovery());
        a.groupRow(sg, R.drawable.ic_key, a.primaryInk, "نسيت كلمة السر؟ تعيين كلمة جديدة", "من هذا الجهاز المنضم دون الكلمة القديمة (للمدير بعد تأكيد رمزه)", null, v -> resetPassword());
        a.groupRow(sg, R.drawable.ic_trash, a.red, "حذف ملفات المزامنة من هذا المكان", "للمدير، بكلمة السر أو رمز الاسترداد، وبعد نسخة أمان. كل الأجهزة تخرج من المزامنة", null, v -> wipeDialog());
        a.groupRow(sg, R.drawable.ic_logout, a.red, "إيقاف المزامنة على هذا الجهاز", "بياناته تبقى؛ ويمكن الانضمام من جديد", null,
                v -> a.confirm("إيقاف المزامنة", "يتوقف هذا الجهاز عن الإرسال والاستلام، وتبقى بياناته كما هي. للعودة: «الانضمام» (تُستبدل بياناته ببيانات المزامنة).",
                        "إيقاف", () -> {
                            new SyncEngine(a.db, folder(a, a.db)).leave();
                            show();
                        }));
        a.hint("الخزنة: " + st.vaultId.substring(0, Math.min(8, st.vaultId.length())) + "… • تشفير AES-256-GCM بمفتاح من كلمة سر المزامنة • الحزم مسلسلة ومرتبطة ببصمات (أي حذف أو تعديل يُكتشف).");
    }

    static String everyName(int secs) {
        if (secs < 60) return secs + " ثانية";
        if (secs == 90) return "دقيقة ونصف";
        return (secs / 60) + " دقائق";
    }

    private void showUsage() {
        a.busy("جارٍ قراءة مكان المزامنة…", p -> new SyncEngine(a.db, folder(a, a.db)).usage(), res -> {
            SyncEngine.Usage u = (SyncEngine.Usage) res;
            new AlertDialog.Builder(a).setTitle("مكان المزامنة")
                    .setMessage(u.total() + " ملفًا" + (u.bytes >= 0 ? " • " + SyncEngine.size(u.bytes) : "") + "\n\n"
                            + "• حزم التغييرات: " + u.packets + "\n"
                            + "• اللقطات الكاملة: " + u.snapshots + (u.snapBytes >= 0 ? " (" + SyncEngine.size(u.snapBytes) + ")" : "") + "\n"
                            + "• الصور: " + u.images + (u.imageBytes >= 0 ? " (" + SyncEngine.size(u.imageBytes) + ")" : "") + "\n"
                            + "• بطاقات الأجهزة: " + u.members + "\n\n"
                            + "كل صورة ملف واحد مهما تكررت، والحزم القديمة تُحذف تلقائيًا بعد وصولها لكل الأجهزة.")
                    .setPositiveButton("حسنًا", null).show();
        });
    }

    /** 10.17: المدير يسمّي جهازًا منضمًّا آخر. */
    private void renameOther(final JSONObject m) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText n = a.field("اسم الجهاز");
        n.setText(m.optString("name"));
        l.addView(n, a.lp(-1, 50, 3));
        new AlertDialog.Builder(a).setTitle("إعادة تسمية «" + m.optString("name") + "»").setView(l).setPositiveButton("حفظ", (d, w) -> {
            final String v = n.getText().toString();
            a.busy("جارٍ الحفظ…", p -> {
                new SyncEngine(a.db, folder(a, a.db)).renameDevice(m.optString("dev"), v);
                return null;
            }, res -> {
                a.toast("غُيّر اسم الجهاز ✓ (يظهر على الأجهزة الأخرى بعد مزامنتها)");
                show();
            });
        }).setNegativeButton("إلغاء", null).show();
    }

    private void renameDialog(String current) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText n = a.field("اسم هذا الجهاز");
        n.setText(current);
        l.addView(n, a.lp(-1, 50, 3));
        new AlertDialog.Builder(a).setTitle("اسم هذا الجهاز").setView(l).setPositiveButton("حفظ", (d, w) -> {
            final String v = n.getText().toString();
            a.busy("جارٍ الحفظ…", p -> {
                new SyncEngine(a.db, folder(a, a.db)).rename(v);
                return null;
            }, res -> {
                a.toast("غُيّر اسم الجهاز ✓ (يظهر للأجهزة الأخرى بعد مزامنتها)");
                show();
            });
        }).setNegativeButton("إلغاء", null).show();
    }

    private void removeDevice(final JSONObject m) {
        if (!a.require(Auth.Perm.SYNC)) return;
        a.confirm("إزالة «" + m.optString("name") + "» من المزامنة",
                SyncHelp.REMOVE_TEXT,
                "إزالة", () -> a.busy("جارٍ إزالة الجهاز…", p -> new SyncEngine(a.db, folder(a, a.db)).removeDevice(m.optString("dev")), res -> {
                    a.toast("أُزيل الجهاز ✓ تصل حزمه حتى رقم " + res + " إلى كل الأجهزة ثم تتوقف");
                    show();
                }));
    }

    /** 10.5: من الجهاز الرئيسي: نقل الدور إلى جهاز آخر أو إزالته. */
    private void deviceActions(final JSONObject m) {
        new AlertDialog.Builder(a).setTitle(m.optString("name"))
                .setItems(new String[]{"اجعله الجهاز الرئيسي", "إزالة من المزامنة", "إعادة تسمية الجهاز"}, (d, w) -> {
                    if (w == 0) transferFounder(m);
                    else if (w == 1) removeDevice(m);
                    else renameOther(m);
                }).setNegativeButton("إلغاء", null).show();
    }

    /** 10.5 (AUDIT-002): نقل دور الجهاز الرئيسي. */
    private void transferFounder(final JSONObject m) {
        if (!a.require(Auth.Perm.SYNC)) return;
        a.confirm("جعل «" + m.optString("name") + "» الجهاز الرئيسي",
                "الجهاز الرئيسي يحذف ملفات الأجهزة المُزالة والصور القديمة من مكان المزامنة، ومنه يُفعَّل نظام المستخدمين أو يُوقف.\n"
                        + "• يجب أن يكون «" + m.optString("name") + "» قد زامن قريبًا واستلم كل ما أرسله هذا الهاتف.\n"
                        + "• يصبح هذا الهاتف جهازًا عاديًا فورًا، ويعرف الجهاز الآخر دوره في جولته التالية.\n• لا تتغير أي بيانات ولا رموز المستخدمين.",
                "نقل الدور", () -> a.busy("جارٍ نقل الدور…", p -> {
                    SyncEngine e = new SyncEngine(a.db, folder(a, a.db));
                    e.sync();
                    e.transferFounder(m.optString("dev"));
                    return true;
                }, res -> {
                    a.toast("نُقل دور الجهاز الرئيسي إلى «" + m.optString("name") + "» ✓");
                    show();
                }));
    }

    /** 10.5 (AUDIT-002): استلام دور الجهاز الرئيسي بعد ضياعه: مدير + رمز الاسترداد + غياب الرئيسي. */
    private void claimFounder(final SyncEngine.Status st) {
        a.busy("جارٍ الفحص…", p -> {
            String b = new SyncEngine(a.db, folder(a, a.db)).founderClaimBlock(st.founderDev);
            return b == null ? "" : b;
        }, res -> {
            if (!((String) res).isEmpty()) {
                new AlertDialog.Builder(a).setTitle("استلام دور الجهاز الرئيسي").setMessage((String) res).setPositiveButton("حسنًا", null).show();
                return;
            }
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
            l.addView(a.text("استعمله فقط إن ضاع الجهاز الرئيسي «" + st.founderName() + "» أو تعطل. يلزم رمز استرداد المزامنة (الورقة التي حفظتها عند الإنشاء)؛ "
                    + "كلمة السر ورابط الانضمام لا يكفيان. إن عاد الجهاز القديم يصبح جهازًا عاديًا تلقائيًا.", 13, a.ink, false));
            final EditText rc = secret(l, "رمز الاسترداد");
            new AlertDialog.Builder(a).setTitle("استلام دور الجهاز الرئيسي").setView(l).setPositiveButton("استلام الدور", (d, w) -> {
                final char[] s = rc.getText().toString().toCharArray();
                a.busy("جارٍ استلام الدور…", p -> {
                    new SyncEngine(a.db, folder(a, a.db)).claimFounder(s);
                    return true;
                }, r2 -> {
                    a.toast("أصبح هذا الهاتف هو الجهاز الرئيسي ✓");
                    show();
                });
            }).setNegativeButton("إلغاء", null).show();
        });
    }

    private void newRecovery() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(a.text("يتوقف رمز الاسترداد القديم فورًا، ويظهر رمز جديد مرة واحدة. احفظه خارج هذا الهاتف.", 13, a.ink, false));
        final EditText pw = secret(l, "كلمة سر المزامنة الحالية");
        new AlertDialog.Builder(a).setTitle("رمز استرداد جديد").setView(l).setPositiveButton("إصدار", (d, w) -> {
            final char[] s = pw.getText().toString().toCharArray();
            a.busy("جارٍ إصدار الرمز…", p -> new SyncEngine(a.db, folder(a, a.db)).newRecoveryCode(s), res -> recoveryDialog((String) res));
        }).setNegativeButton("إلغاء", null).show();
    }

    private void editServer() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText url = a.field("https://عنوان-خادمك");
        url.setText(a.db.setting("sync_server", ""));
        url.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        l.addView(url, a.lp(-1, 50, 3));
        final EditText key = secret(l, "رمز الخادم (إن وُجد)");
        key.setText(a.db.setting("sync_server_key", ""));
        l.addView(a.text("يُفحص الاتصال قبل الحفظ، والخزنة نفسها تبقى. إن كان العنوان لخادم آخر فاستعمل «الطريقة: تغيير» لتُنسخ الملفات إليه.", 12, a.muted, false));
        new AlertDialog.Builder(a).setTitle("خادم المزامنة").setView(l).setPositiveButton("فحص وحفظ", (d, w) -> {
            final String base = HttpSyncFolder.parseLink(url.getText().toString())[0];
            final String k = key.getText().toString().trim();
            try {
                HttpSyncFolder.checkUrl(base);
            } catch (Exception e) {
                a.error(e);
                return;
            }
            final String vault = SyncEngine.status(a.db).vaultId;
            a.busy("جارٍ فحص الخادم…", p -> {
                HttpSyncFolder h = new HttpSyncFolder(base, vault, k);
                h.ping();
                if (new SyncEngine(a.db, h).vaultExists()) return "ok";
                return "empty";
            }, res -> {
                if ("empty".equals(res)) {
                    a.error(new IllegalStateException("الخادم يعمل لكن خزنتك ليست عليه. للانتقال إليه استعمل «الطريقة: تغيير ← خادم المزامنة»."));
                    return;
                }
                a.db.setSettingQuiet("sync_server", base);
                a.db.setSettingQuiet("sync_server_key", k);
                a.toast("حُفظت إعدادات الخادم ✓");
                now(this::show);
            });
        }).setNegativeButton("إلغاء", null).show();
    }

    /** 10.2: نقل الخزنة إلى طريقة أخرى (تُنسخ الملفات المشفّرة كما هي؛ كرّرها على كل جهاز بالطريقة نفسها). */
    // ===================================================================================
    // 10.14: تغيير حساب Google، تعيين كلمة سر جديدة، حذف ملفات المزامنة
    // ===================================================================================

    private void changeAccount(final String cur) {
        if (!a.require(Auth.Perm.SYNC)) return;
        String[] items = {"نقل المزامنة إلى الحساب الجديد (موصى به)", "بداية جديدة في الحساب الجديد"};
        new AlertDialog.Builder(a).setTitle("تغيير حساب Google").setItems(items, (d, w) -> {
            if (w == 0) a.confirm("نقل المزامنة إلى حساب آخر",
                    "تُنسخ الخزنة المشفّرة كما هي إلى الحساب الجديد، ويُترك في الحساب القديم علامة «نُقلت»: كل جهاز آخر يتوقف عن الإرسال إليه "
                            + "ويطلب منك الدخول بالحساب الجديد على ذلك الجهاز (بياناته لا تتغير). بعد أن تنتقل كل الأجهزة احذف الخزنة من الحساب القديم.",
                    "اختيار الحساب الجديد", () -> pickNew(cur, false));
            else a.confirm("بداية جديدة في حساب آخر",
                    "تُنشأ مزامنة جديدة في الحساب الجديد من بيانات هذا الجهاز، بكلمة سر ورمز استرداد جديدين. كل جهاز آخر يُطلب منه أن ينضم إليها، "
                            + "فتُستبدل بياناته ببيانات هذا الجهاز: ما سُجّل عليه ولم يصل إلى هنا يضيع. استعمل هذا فقط إن كانت كل الأجهزة متزامنة الآن.",
                    "اختيار الحساب الجديد", () -> pickNew(cur, true));
        }).setNegativeButton("إلغاء", null).show();
    }

    private void pickNew(final String cur, final boolean fresh) {
        DriveAuth.pickAccount(a, cur, email -> {
            if (email.equalsIgnoreCase(cur)) {
                a.toast("هذا هو الحساب الحالي. اختر حسابًا آخر.");
                return;
            }
            DriveAuth.connect(a, email, new DriveAuth.Done() {
                @Override public void ok() {
                    a.usersScreen.confirmAdmin(fresh ? "بداية جديدة في حساب آخر" : "نقل المزامنة إلى حساب آخر", () -> {
                        if (fresh) freshIn(cur, email);
                        else moveTo(cur, email);
                    });
                }

                @Override public void fail(Exception e) { a.error(e); }
            });
        });
    }

    private void moveTo(final String cur, final String email) {
        a.busy("جارٍ نقل الخزنة المشفّرة إلى " + email + "…", p -> {
            SyncFolder src = new DriveSyncFolder(new DriveAuth(a, cur)), dst = new DriveSyncFolder(new DriveAuth(a, email));
            if (SyncEngine.sameFolder(src, dst)) throw new IllegalStateException(email + " هو الحساب المتصل حاليًا. اختر حسابًا آخر.");
            int n = new SyncEngine(a.db, src).moveTo(dst);
            new SyncEngine(a.db, src).retire("Google Drive: " + email, false, false);
            return n;
        }, res -> {
            a.db.setSettingQuiet("drive_account", email);
            if (!cur.isEmpty()) a.db.setSettingQuiet("drive_account_old", cur);
            a.db.setSettingQuiet("sync_drive_ok", "1");
            new AlertDialog.Builder(a).setTitle("نُقلت المزامنة ✓")
                    .setMessage("نُسخ " + res + " ملفًا إلى " + email + ".\n\nعلى كل جهاز آخر: افتح «المزامنة» فيظهر أن المزامنة نُقلت؛ اختر «حساب Google: تغيير ← نقل» "
                            + "أو «تسجيل الدخول» بـ " + email + ". بعد أن تنتقل كل الأجهزة احذف الخزنة من الحساب القديم من هنا.")
                    .setPositiveButton("حسنًا", (d, w) -> now(this::show)).show();
        });
    }

    private void freshIn(final String cur, final String email) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText p1 = secret(l, "كلمة سر المزامنة الجديدة (8 محارف على الأقل)"), p2 = secret(l, "أعد كتابتها");
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("مزامنة جديدة في " + email).setView(l).setPositiveButton("إنشاء", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String s1 = p1.getText().toString();
            if (s1.length() < 8) {
                p1.setError("8 محارف على الأقل");
                return;
            }
            if (!s1.equals(p2.getText().toString())) {
                p2.setError("غير متطابقة");
                return;
            }
            d.dismiss();
            final String dn = SyncEngine.status(a.db).deviceName;
            a.busy("جارٍ إنشاء المزامنة الجديدة في " + email + "…", p -> {
                SyncFolder old = new DriveSyncFolder(new DriveAuth(a, cur)), dst = new DriveSyncFolder(new DriveAuth(a, email));
                if (SyncEngine.sameFolder(old, dst)) throw new IllegalStateException(email + " هو الحساب المتصل حاليًا. اختر حسابًا آخر.");
                new SyncEngine(a.db, old).retire("Google Drive: " + email, true, false);
                new SyncEngine(a.db, old).leave();
                a.db.setSettingQuiet("drive_account", email);
                return new SyncEngine(a.db, dst).create(s1.toCharArray(), dn);
            }, res -> {
                if (!cur.isEmpty()) a.db.setSettingQuiet("drive_account_old", cur);
                recoveryDialog((String) res);
            });
        }));
        d.show();
    }

    /** 10.14: البريد من وصف الوجهة «Google Drive: x@y» (فارغ إن لم تكن Google). */
    static String driveEmail(String target) {
        String p = "Google Drive: ";
        return target != null && target.startsWith(p) ? target.substring(p.length()).trim() : "";
    }

    /** 10.14: على الأجهزة الأخرى بعد نقل المزامنة أو بدايتها الجديدة في حساب آخر: تنبيه واضح وزر الانتقال. */
    private void movedNotice(final SyncEngine.Status st) {
        final String email = driveEmail(st.movedTarget);
        final boolean drive = "drive".equals(transport(a.db));
        if (!a.can(Auth.Perm.SYNC)) {
            a.notice(R.drawable.ic_warn, st.movedTo + " اطلب من المدير نقل هذا الجهاز.", true, null, null);
            return;
        }
        if (st.movedFresh) {
            a.notice(R.drawable.ic_warn, st.movedTo + " للمتابعة: انضم إلى المزامنة الجديدة. تُستبدل بيانات هذا الجهاز ببياناتها، وتُحفظ نسخة أمان قبلها.", true, "انضمام", v ->
                    a.confirm("الانضمام إلى المزامنة الجديدة", "يخرج هذا الجهاز من المزامنة القديمة (بياناته باقية)، ثم "
                            + (drive && !email.isEmpty() ? "تختار الحساب " + email + " و" : "") + "تنضم بكلمة سر المزامنة الجديدة.", "متابعة", () -> {
                        new SyncEngine(a.db, null).leave(st.movedTo);
                        if (drive && !email.isEmpty()) signIn(email, this::show);
                        else show();
                    }));
            return;
        }
        if (drive && !email.isEmpty())
            a.notice(R.drawable.ic_warn, st.movedTo + " سجّل الدخول بـ " + email + " على هذا الجهاز: الخزنة نفسها وبياناتك كما هي.", true, "انتقال", v ->
                    DriveAuth.pickAccount(a, email, picked -> {
                        if (!picked.equalsIgnoreCase(email)) {
                            a.toast("اختر الحساب الذي نُقلت إليه المزامنة: " + email);
                            return;
                        }
                        DriveAuth.connect(a, picked, new DriveAuth.Done() {
                            @Override public void ok() {
                                final String cur = a.db.setting("drive_account", "");
                                a.busy("جارٍ الانتقال إلى " + picked + "…", p -> new SyncEngine(a.db, new DriveSyncFolder(new DriveAuth(a, cur)))
                                        .moveTo(new DriveSyncFolder(new DriveAuth(a, picked))), res -> {
                                    a.db.setSettingQuiet("drive_account", picked);
                                    a.db.setSettingQuiet("sync_drive_ok", "1");
                                    a.toast("انتقل هذا الجهاز إلى " + picked + " ✓");
                                    now(SyncScreen.this::show);
                                });
                            }

                            @Override public void fail(Exception e) { a.error(e); }
                        });
                    }));
        else a.notice(R.drawable.ic_warn, st.movedTo + " اختر المكان الجديد من «الطريقة: تغيير».", true, "تغيير", v -> changeTransport());
    }

    private void purgeOld(final String old) {
        a.confirm("حذف الخزنة من الحساب القديم", "تُحذف ملفات المزامنة المشفّرة من " + old + " وتبقى فيه علامة «نُقلت» فقط. "
                + "تأكد أولًا أن كل أجهزتك انتقلت إلى الحساب الجديد (قائمة الأجهزة هنا). بياناتك في الحساب الجديد وعلى الأجهزة لا تتأثر.", "حذف", () ->
                a.usersScreen.confirmAdmin("حذف الخزنة من الحساب القديم", () -> a.busy("جارٍ الحذف من " + old + "…",
                        p -> new SyncEngine(a.db, new DriveSyncFolder(new DriveAuth(a, old))).purgeRetired(), res -> {
                            a.db.deleteSetting("drive_account_old");
                            a.toast("حُذف " + res + " ملفًا من الحساب القديم ✓");
                            show();
                        })));
    }

    private void resetPassword() {
        a.usersScreen.confirmAdmin("تعيين كلمة سر المزامنة", () -> {
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
            l.addView(a.text("هذا الجهاز منضم ويحمل مفتاح الخزنة، فتُعيَّن الكلمة الجديدة دون القديمة. الأجهزة المنضمة لا تتأثر، ورمز الاسترداد يبقى كما هو.", 12, a.muted, false));
            final EditText p1 = secret(l, "كلمة السر الجديدة (8 محارف على الأقل)"), p2 = secret(l, "أعد كتابتها");
            final AlertDialog d = new AlertDialog.Builder(a).setTitle("كلمة سر مزامنة جديدة").setView(l).setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create();
            d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final String s1 = p1.getText().toString();
                if (s1.length() < 8) {
                    p1.setError("8 محارف على الأقل");
                    return;
                }
                if (!s1.equals(p2.getText().toString())) {
                    p2.setError("غير متطابقة");
                    return;
                }
                d.dismiss();
                a.busy("جارٍ حفظ كلمة السر الجديدة…", p -> {
                    new SyncEngine(a.db, folder(a, a.db)).resetPassword(s1.toCharArray());
                    return null;
                }, res -> a.toast("عُيّنت كلمة سر المزامنة الجديدة ✓"));
            }));
            d.show();
        });
    }

    private void wipeDialog() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(a.text("تُحذف كل ملفات المزامنة المشفّرة من «" + kindName(a.db) + "». قبل الحذف تُحفظ نسخة أمان من بيانات هذا الجهاز. "
                + "كل الأجهزة (وهذا الجهاز) تخرج من المزامنة وتبقى بياناتها كما هي، ثم يمكنك إنشاء مزامنة جديدة.", 13, a.red, true));
        final Ui.Seg how = a.new Seg(new String[]{"كلمة السر", "رمز الاسترداد"}, null, 0, false);
        l.addView(how.view, a.lp(-1, -2, 2));
        final EditText pw = secret(l, "كلمة سر المزامنة أو رمز الاسترداد");
        final EditText word = a.field("اكتب: حذف");
        l.addView(word, a.lp(-1, 50, 3));
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("حذف ملفات المزامنة").setView(l).setPositiveButton("حذف", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!"حذف".equals(word.getText().toString().trim())) {
                word.setError("اكتب كلمة «حذف» للتأكيد");
                return;
            }
            final String sec = pw.getText().toString();
            if (sec.trim().isEmpty()) {
                pw.setError("مطلوبة");
                return;
            }
            d.dismiss();
            final boolean rc = how.selected == 1;
            a.usersScreen.confirmAdmin("حذف ملفات المزامنة", () -> a.busy("جارٍ حفظ نسخة أمان وحذف ملفات المزامنة…",
                    p -> new SyncEngine(a.db, folder(a, a.db)).wipe(a, sec.toCharArray(), rc), res -> new AlertDialog.Builder(a).setTitle("حُذفت ملفات المزامنة ✓")
                            .setMessage("حُذف " + res + " ملفًا. خرج هذا الجهاز من المزامنة وبياناته كما هي، وستخرج الأجهزة الأخرى في جولتها التالية. "
                                    + "حُفظت نسخة أمان من بيانات هذا الجهاز قبل الحذف. يمكنك الآن إنشاء مزامنة جديدة (في هذا المكان أو بحساب آخر).")
                            .setPositiveButton("حسنًا", (dd, w) -> show()).show()));
        }));
        d.show();
    }

    private void changeTransport() {
        final String cur = transport(a.db);
        final String[] keys = {"drive", "server", "folder"};
        final String[] names = {"\u200FGoogle Drive مباشرة", "خادم المزامنة الخاص بك", "مجلد مشترك"};
        new AlertDialog.Builder(a).setTitle("نقل المزامنة إلى طريقة أخرى").setItems(names, (d, w) -> {
            final String to = keys[w];
            if (to.equals(cur) && !"folder".equals(to)) {
                a.toast("هذه هي الطريقة الحالية");
                return;
            }
            a.confirm("نقل المزامنة إلى «" + names[w] + "»", "تُنسخ ملفات الخزنة المشفّرة إلى الطريقة الجديدة ثم يستخدمها هذا الجهاز. "
                    + "افعل الشيء نفسه على كل جهاز (يكمل كل جهاز ما ينقص). "
                    + ("drive".equals(to) ? "يُطلب الدخول بحساب Google نفسه على كل الأجهزة." : "server".equals(to) ? "اكتب عنوان الخادم أولًا في الخطوة التالية." : "اختر المجلد في الخطوة التالية."),
                    "متابعة", () -> prepareTarget(to));
        }).show();
    }

    /** 10.3: إعدادات الناقل قبل النقل (تُعاد كما كانت إن فشل النقل، فلا يبقى الجهاز على مكان فارغ). */
    private String[] savedTransport;
    private static final String[] TRANSPORT_KEYS = {"sync_tree", "sync_server", "sync_server_key", "sync_server_vault", "sync_transport"};

    private void prepareTarget(final String to) {
        savedTransport = new String[TRANSPORT_KEYS.length];
        for (int i = 0; i < TRANSPORT_KEYS.length; i++) savedTransport[i] = a.db.setting(TRANSPORT_KEYS[i], "");
        final SyncFolder src = folder(a, a.db);
        if (src == null) {
            a.toast("الطريقة الحالية غير متاحة على هذا الجهاز");
            return;
        }
        if ("drive".equals(to)) {
            DriveAuth.connect(a, new DriveAuth.Done() {
                @Override public void ok() { doMove(to, src); }

                @Override public void fail(Exception e) { a.error(e); }
            });
        } else if ("server".equals(to)) {
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
            final EditText url = a.field("https://عنوان-خادمك");
            url.setText(a.db.setting("sync_server", ""));
            url.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
            l.addView(url, a.lp(-1, 50, 3));
            final EditText key = secret(l, "رمز الخادم (إن وُجد)");
            key.setText(a.db.setting("sync_server_key", ""));
            new AlertDialog.Builder(a).setTitle("خادم المزامنة").setView(l).setPositiveButton("متابعة", (d, w) -> {
                String base = HttpSyncFolder.parseLink(url.getText().toString())[0];
                try {
                    HttpSyncFolder.checkUrl(base);
                } catch (Exception e) {
                    a.error(e);
                    return;
                }
                a.db.setSettingQuiet("sync_server", base);
                a.db.setSettingQuiet("sync_server_key", key.getText().toString().trim());
                a.db.setSettingQuiet("sync_server_vault", SyncEngine.status(a.db).vaultId);
                doMove(to, src);
            }).setNegativeButton("إلغاء", null).show();
        } else pick(() -> doMove(to, src));
    }

    private void doMove(final String to, final SyncFolder src) {
        final SyncFolder dst = folder(a, a.db, to);
        if (dst == null) {
            a.toast("الطريقة غير مهيأة بعد");
            return;
        }
        final String[] saved = savedTransport;
        a.busy("جارٍ نقل ملفات الخزنة المشفّرة…", p -> {
            try {
                return new SyncEngine(a.db, src).moveTo(dst);
            } catch (Exception e) {
                // فشل النقل: يعود الجهاز إلى مكانه السابق كما كان
                if (saved != null) for (int i = 0; i < TRANSPORT_KEYS.length; i++) a.db.setSettingQuiet(TRANSPORT_KEYS[i], saved[i]);
                throw new IllegalStateException("لم يكتمل النقل، وبقيت المزامنة على مكانها السابق: " + Ui.friendly(e));
            }
        }, res -> {
            a.db.setSettingQuiet("sync_transport", to);
            if ("drive".equals(to)) a.db.setSettingQuiet("sync_drive_ok", "1");
            a.toast("نُقلت المزامنة ✓ (" + res + " ملفًا). افعل الشيء نفسه على أجهزتك الأخرى.");
            now(this::show);
        });
    }

    // ===================================================================================
    // 10.2: حركات قد تكون مكررة (سُجّلت من جهازين قبل أن يتزامنا)
    // ===================================================================================

    /** 11.5: صفحة جهاز: تفاصيله وآخر ظهوره بالساعة، وسجله على هذا الجهاز (ما وصل منه، وأخطاؤه)، وإجراءاته. */
    private void deviceScreen(final JSONObject m, final String line, final boolean me, final boolean actions, final boolean renameOnly, final boolean canRm) {
        final String dev = m.optString("dev");
        a.here(() -> deviceScreen(m, line, me, actions, renameOnly, canRm));
        a.tabbedScreen(m.optString("name") + (me ? " (هذا الجهاز)" : ""), this::show, "more");
        LinearLayout g = a.group();
        a.groupRow(g, R.drawable.ic_phone, a.primaryInk, "آخر ظهور: " + MainActivity.agoAt(m.optLong("at")), line, null, null);
        if (actions) a.button("إجراءات الجهاز", a.accent, v -> deviceActions(m));
        else if (renameOnly) {
            a.button("إعادة تسمية الجهاز", a.accent, v -> renameOther(m));
            if (canRm) a.button("إزالة من المزامنة", a.red, v -> removeDevice(m));
        } else if (canRm) a.button("إزالة من المزامنة", a.red, v -> removeDevice(m));
        List<JSONObject> hist = SyncEngine.devHistory(a.db, dev);
        a.section("السجل (" + hist.size() + ")", hist.isEmpty() ? null : "مسح", hist.isEmpty() ? null : v -> a.confirm("مسح سجل الجهاز",
                "يُمسح سجل «" + m.optString("name") + "» على هذا الجهاز فقط.", "مسح", () -> {
                    SyncEngine.clearDevLog(a.db, dev);
                    deviceScreen(m, line, me, actions, renameOnly, canRm);
                }));
        if (hist.isEmpty()) a.hint(me ? "يظهر هنا ما أرسله هذا الجهاز واستلمه في كل جولة، وأخطاؤه." : "يظهر هنا ما وصل من هذا الجهاز في كل جولة، وأي خطأ في حزمه.");
        LinearLayout hg = a.group();
        for (JSONObject x : hist) {
            boolean bad = x.has("e");
            a.groupRow(hg, bad ? R.drawable.ic_warn : R.drawable.ic_check2, bad ? a.red : a.green, MainActivity.agoAt(x.optLong("at")),
                    x.optString("t") + (bad ? "\n" + Ui.netFriendly(x.optString("e")) : ""), null, null);
        }
    }

    void showDuplicates() {
        a.here(this::showDuplicates);
        a.tabbedScreen("حركات قد تكون مكررة", a::showHome, "home");
        final List<SyncEngine.Dup> l = SyncEngine.duplicates(a.db);
        a.hint("حركتان بالمكتب والمبلغ والعملة والاسم نفسها سُجّلتا من جهازين قبل أن يتزامنا. قرّر لكل زوج: هل هما حوالة واحدة سُجّلت مرتين، أم حوالتان فعلًا؟ "
                + "قرارك يصل إلى كل الأجهزة.");
        if (l.isEmpty()) {
            a.add(a.text("لا حركات مكررة محتملة ✓", 15, a.muted, false));
            return;
        }
        Map<Long, String> names = a.db.officeNames();
        for (final SyncEngine.Dup x : l) {
            LinearLayout c = a.card();
            c.addView(a.text(Model.nz(names.get(x.a.officeId)) + " — " + (Model.TYPE_DEBIT.equals(x.a.type) ? "لنا " : "علينا ")
                    + "\u2066" + Model.fmt(x.a.amount, a.db.decimals(x.a.currency)) + " " + x.a.currency + "\u2069", 16, a.primaryInk, true));
            c.addView(a.text(dupLine("الأولى", x.a), 13, a.ink, false));
            c.addView(a.text(dupLine("الثانية", x.b), 13, a.ink, false));
            LinearLayout r = a.row();
            r.addView(a.smallButton("أبقِ الاثنتين", a.accent, v -> {
                SyncEngine.keepBoth(a.db, x);
                a.toast("أُبقيت الحركتان (ليستا مكررتين)");
                showDuplicates();
            }), a.weight(42, 1.2f, 2));
            r.addView(a.smallButton("ألغِ الأولى", a.red, v -> dropDup(x, x.a)), a.weight(42, 1, 2));
            r.addView(a.smallButton("ألغِ الثانية", a.red, v -> dropDup(x, x.b)), a.weight(42, 1, 2));
            c.addView(r);
        }
    }

    private String dupLine(String which, Model.Tx t) {
        String dev = SyncEngine.deviceOf(a.db, t.id);
        return "• " + which + ": " + Model.date(t.createdAt) + (t.person.isEmpty() ? "" : " • " + t.person) + (t.description.isEmpty() ? "" : " • " + t.description)
                + (t.userName.isEmpty() ? "" : " • سجّلها " + t.userName) + (dev.isEmpty() ? "" : " • من «" + dev + "»") + (t.isPending() ? " • بانتظار الاعتماد" : "");
    }

    private void dropDup(final SyncEngine.Dup x, final Model.Tx t) {
        if (!a.require(t.isPending() ? Auth.Perm.APPROVE : Auth.Perm.TX_CANCEL)) return;
        a.confirm("إلغاء الحركة المكررة", dupLine("الحركة", t) + "\n\n" + (t.isPending() ? "تُرفض (لم تدخل الأرصدة)." : "تُلغى بقيد عكسي ظاهر في السجل، وتبقى الأخرى."),
                "إلغاء هذه", () -> {
                    try {
                        SyncEngine.dropDuplicate(a.db, x, t);
                        a.toast("أُلغيت الحركة المكررة");
                    } catch (Exception e) {
                        a.error(e);
                    }
                    showDuplicates();
                });
    }

    private void changePassword() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText o = secret(l, "كلمة السر الحالية"), n1 = secret(l, "الجديدة (8 محارف على الأقل)"), n2 = secret(l, "أعد كتابة الجديدة");
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("تغيير كلمة سر المزامنة").setView(l).setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (n1.getText().length() < 8) {
                n1.setError("8 محارف على الأقل");
                return;
            }
            if (!n1.getText().toString().equals(n2.getText().toString())) {
                n2.setError("غير متطابقة");
                return;
            }
            d.dismiss();
            final String os = o.getText().toString(), ns = n1.getText().toString();
            a.busy("جارٍ إعادة تغليف المفتاح…", p -> {
                new SyncEngine(a.db, folder(a, a.db)).changePassword(os.toCharArray(), ns.toCharArray());
                return null;
            }, res -> a.toast("غُيّرت كلمة سر المزامنة ✓"));
        }));
        d.show();
    }
}
