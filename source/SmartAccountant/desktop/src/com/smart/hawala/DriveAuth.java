package com.smart.hawala;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.smart.hawala.desk.DeskDriveAuth;
import java.io.IOException;

/**
 * 11.0 (الكمبيوتر): الواجهة نفسها التي في الهاتف (اختيار الحساب ثم الدخول)، والدخول نفسه عبر المتصفح (OAuth للكمبيوتر بصلاحية
 * مجلد التطبيق المخفي). رمز التجديد لكل حساب في سلسلة المفاتيح؛ الحساب الحالي (قبل 11.0) في خانته القديمة.
 */
final class DriveAuth implements DriveSyncFolder.Tokens {
    static final int REQ = 71, REQ_PICK = 73;
    private static final String PENDING = "drive_refresh_pending";
    private static String pendingEmail;
    private final DeskDriveAuth inner;

    DriveAuth(Context ctx) { this(ctx, ""); }

    DriveAuth(Context ctx, String account) { inner = new DeskDriveAuth(SmartApp.db, keyFor(account)); }

    /** خانة رمز الحساب: الحساب الحالي في الخانة القديمة إن لم تكن له خانة باسمه. */
    static String keyFor(String account) {
        Db db = SmartApp.db;
        String cur = db == null ? "" : db.setting("drive_account", "");
        if (account == null || account.isEmpty()) account = cur;
        if (account.isEmpty()) return DeskDriveAuth.REFRESH;
        String k = "drive_refresh:" + account.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DeskDriveAuth.has(k) && account.equalsIgnoreCase(cur) && DeskDriveAuth.has(DeskDriveAuth.REFRESH)) return DeskDriveAuth.REFRESH;
        return k;
    }

    @Override public String token(boolean refresh) throws IOException { return inner.token(refresh); }

    interface Picked { void on(String email); }

    interface Done {
        void ok();

        void fail(Exception e);
    }

    /** على الكمبيوتر: اختيار الحساب = الدخول في المتصفح (يظهر اختيار حساب Google هناك)، ثم يُعرف البريد. */
    static void pickAccount(Activity a, String current, Picked done) {
        if (!clientReady(a, () -> pickAccount(a, current, done))) return;
        waitLogin(a, () -> DeskDriveAuth.login(SmartApp.db, PENDING, current == null ? "" : current), r -> {
            String email = String.valueOf(r);
            if (email.isEmpty()) {
                DeskDriveAuth.drop(PENDING);
                ((Ui) a).error(new IOException("تم الدخول لكن تعذّرت معرفة بريد الحساب. أعد المحاولة."));
                return;
            }
            pendingEmail = email;
            done.on(email);
        }, e -> ((Ui) a).error(e));
    }

    interface Login { String run() throws Exception; }

    /**
     * 11.2: انتظار الدخول في المتصفح بنافذة فيها «إلغاء» (كانت نافذة انتظار بلا إلغاء حتى 5 دقائق فيبدو البرنامج معلّقًا)،
     * و«فتح صفحة الدخول مرة أخرى». الإلغاء يوقف الانتظار فورًا؛ وبعد 3 دقائق دون دخول يتوقف وحده.
     */
    static void waitLogin(Activity a, Login w, java.util.function.Consumer<String> ok, java.util.function.Consumer<Exception> fail) {
        Ui u = (Ui) a;
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        l.setPadding(u.dp(20), u.dp(20), u.dp(20), u.dp(8));
        l.addView(new android.widget.ProgressBar(a));
        android.widget.TextView tv = u.text("أكمل الدخول بحساب Google في المتصفح الذي فُتح، ثم ارجع إلى البرنامج.", 15, u.dark, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(u.dp(14));
        l.addView(tv, lp);
        final boolean[] over = {false};
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("الدخول إلى Google").setView(l).setCancelable(false)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("فتح الصفحة مرة أخرى", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            over[0] = true;
            DeskDriveAuth.cancelLogin();
            dlg.dismiss();
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> DeskDriveAuth.reopenLogin());
        new Thread(() -> {
            String r = null;
            Exception err = null;
            try {
                r = w.run();
            } catch (Exception e) {
                err = e;
            }
            final String fr = r;
            final Exception fe = err;
            a.runOnUiThread(() -> {
                boolean cancelled = over[0];
                over[0] = true;
                try { dlg.dismiss(); } catch (Throwable ignored) { /* أُغلقت */ }
                if (cancelled) return;
                if (fe != null) fail.accept(fe);
                else ok.accept(fr == null ? "" : fr);
            });
        }, "google-login").start();
    }

    static void onPicked(Activity a, int code, Intent data) {}

    static void onResult(Activity a, int code, Intent data) {}

    static void connect(final Activity a, final Done done) { connect(a, "", done); }

    /** الدخول بحساب محدد: إن دخل للتو (اختيار الحساب) يُعتمد رمزه، وإلا يُفتح المتصفح. */
    static void connect(final Activity a, String account, final Done done) {
        final String acct = account == null ? "" : account;
        if (!acct.isEmpty() && acct.equalsIgnoreCase(pendingEmail) && DeskDriveAuth.has(PENDING)) {
            try {
                DeskDriveAuth.promote(PENDING, keyFor(acct));
                pendingEmail = null;
                done.ok();
            } catch (Exception e) {
                done.fail(e);
            }
            return;
        }
        if (!clientReady(a, () -> connect(a, acct, done))) return;
        final String key = keyFor(acct);
        // 11.3: رمز محفوظ يعمل فعلًا؟ (كان وجوده يكفي فلا تفعل «إعادة الدخول» شيئًا مع رمز منتهٍ)
        if (DeskDriveAuth.has(key)) {
            ((Ui) a).busy("جارٍ التحقق من دخول Google…", p -> {
                try {
                    new DeskDriveAuth(SmartApp.db, key).token(true);
                    return Boolean.TRUE;
                } catch (Exception e) {
                    return Boolean.FALSE;
                }
            }, r -> {
                if (Boolean.TRUE.equals(r)) done.ok();
                else browserLogin(a, key, acct, done);
            });
            return;
        }
        browserLogin(a, key, acct, done);
    }

    /** الدخول في المتصفح؛ ويُرفض حساب غير المطلوب (يبقى الرمز القديم كما هو). */
    private static void browserLogin(Activity a, String key, String acct, Done done) {
        final String tmp = "drive_refresh_relogin";
        waitLogin(a, () -> DeskDriveAuth.login(SmartApp.db, acct.isEmpty() ? key : tmp, acct), email -> {
            try {
                if (!acct.isEmpty()) {
                    if (!email.isEmpty() && !email.equalsIgnoreCase(acct)) {
                        DeskDriveAuth.drop(tmp);
                        done.fail(new IOException("دخلتَ بحساب " + email + "، والمزامنة على هذا الجهاز بحساب " + acct + ". أعد المحاولة واختر " + acct + "."));
                        return;
                    }
                    DeskDriveAuth.promote(tmp, key);
                }
                done.ok();
            } catch (Exception e) {
                done.fail(e);
            }
        }, done::fail);
    }

    /** معرّف عميل Google للكمبيوتر: من البرنامج نفسه، أو يُلصق مرة واحدة (كما في نسخة الكمبيوتر السابقة). */
    private static boolean clientReady(Activity a, Runnable again) {
        if (DeskDriveAuth.client(SmartApp.db) != null) return true;
        Ui u = (Ui) a;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(u.dp(20), u.dp(4), u.dp(20), 0);
        box.addView(u.text("ربط Google Drive على الكمبيوتر يحتاج «معرّف عميل» من نوع تطبيق كمبيوتر (Desktop app) من مشروع Google Cloud. "
                + "الصقه مرة واحدة، أو استعمل «خادمك» أو «مجلد مشترك».", 14, u.muted, false));
        final EditText id = u.field("Client ID"), sec = u.field("Client secret");
        id.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
        sec.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
        box.addView(id, u.lp(-1, 52, 4));
        box.addView(sec, u.lp(-1, 52, 4));
        new AlertDialog.Builder(a).setTitle("معرّف عميل Google").setView(box).setPositiveButton("حفظ ومتابعة", (d, w) -> {
            String i = id.getText().toString().trim(), s = sec.getText().toString().trim();
            if (i.isEmpty()) return;
            SmartApp.db.setSetting("drive_client_id", i);
            SmartApp.db.setSetting("drive_client_secret", s);
            again.run();
        }).setNegativeButton("إلغاء", null).show();
        return false;
    }

    static String friendly(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
