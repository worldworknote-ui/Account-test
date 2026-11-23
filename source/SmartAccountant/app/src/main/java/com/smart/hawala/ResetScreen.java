package com.smart.hawala;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.File;

/**
 * 10.4: «إعادة تعيين البرنامج»: يصبح التطبيق على هذا الهاتف كأنه مثبت للتو. خياران: حذف البيانات مع إبقاء الرخصة، أو حذف كل شيء.
 * للمدير فقط (أو لصاحب الهاتف إن لم يكن نظام المستخدمين مفعّلًا)، برمزه وبكتابة كلمة «حذف»، مع عرض النسخة الاحتياطية قبله.
 * الأجهزة الأخرى في المزامنة لا تتأثر: يُعلن هذا الجهاز خروجه منها ولا يُرسل أي حذف.
 */
final class ResetScreen {
    private final MainActivity a;

    ResetScreen(MainActivity a) { this.a = a; }

    static boolean allowed(Db db) { return !db.usersEnabled() || Auth.isAdmin(SmartApp.currentUser); }

    void start() {
        if (!allowed(a.db)) {
            a.error(new IllegalStateException("إعادة تعيين البرنامج لمدير المكتب فقط."));
            return;
        }
        final String[] opts = {"حذف البيانات وإبقاء الرخصة", "حذف كل شيء بما فيه الرخصة"};
        new AlertDialog.Builder(a).setTitle("إعادة تعيين البرنامج").setItems(opts, (d, w) -> confirm(w == 0)).setNegativeButton("إلغاء", null).show();
    }

    private void confirm(final boolean keepLicense) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        boolean sync = SyncEngine.enabled(a.db);
        l.addView(a.text("يُحذف من هذا الهاتف نهائيًا:\n• كل الحركات والمكاتب والأرصدة والصناديق والعملاء والصور\n• المستخدمون والإعدادات والتصميم\n• النسخ الاحتياطية الداخلية"
                + (keepLicense ? "" : "\n• الرخصة (تحتاج إدخال رمز التفعيل من جديد)")
                + (sync ? "\n\nالمزامنة: يرسل الهاتف ما لم يُرسل ثم يخرج منها. بيانات الأجهزة الأخرى لا تتأثر." : "")
                + "\n\nالنسخ الاحتياطية التي حفظتها خارج التطبيق (في مجلد أو سحابة) تبقى.", 14, a.ink, false));
        final EditText pin = a.db.usersEnabled() ? a.field("رمز المدير") : null;
        if (pin != null) {
            Ui.secret(pin, true);
            l.addView(pin, a.lp(-1, 50, 6));
        }
        final EditText word = a.field("اكتب: " + AppReset.CONFIRM_WORD);
        l.addView(word, a.lp(-1, 50, 4));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle(keepLicense ? "حذف البيانات (تبقى الرخصة)" : "حذف كل شيء").setView(l)
                .setPositiveButton("حذف نهائي", null)
                .setNeutralButton("نسخة احتياطية أولًا", (x, w) -> a.backupScreen.backupDialog())
                .setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(x -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(a.red);
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!AppReset.CONFIRM_WORD.equals(word.getText().toString().trim())) {
                    word.setError("اكتب «" + AppReset.CONFIRM_WORD + "» للتأكيد");
                    return;
                }
                if (pin != null && !a.db.verifyPin(SmartApp.currentUser.id, pin.getText().toString())) {
                    pin.setError("الرمز غير صحيح");
                    return;
                }
                dlg.dismiss();
                run(keepLicense);
            });
        });
        dlg.show();
    }

    private void run(final boolean keepLicense) {
        a.db.audit("APP_RESET", "app", 0, keepLicense ? "إعادة تعيين: حذف البيانات وإبقاء الرخصة" : "إعادة تعيين: حذف كل شيء");
        a.busy("جارٍ إعادة تعيين البرنامج…", p -> {
            if (SyncEngine.enabled(a.db)) {
                SyncFolder f = SyncScreen.folder(a, a.db);
                if (f != null) {
                    try {
                        new SyncEngine(a.db, f).retire();
                    } catch (Throwable ignored) {
                        // يظهر للأجهزة الأخرى غائبًا فيُزال من هناك
                    }
                }
            }
            return null;
        }, res -> {
            SyncJob.schedule(a, false);
            wipe(a, a.db, keepLicense);
            a.toast("أُعيد تعيين البرنامج");
            Intent i = Intent.makeRestartActivityTask(new android.content.ComponentName(a, MainActivity.class));
            a.startActivity(i);
            Runtime.getRuntime().exit(0);
        });
    }

    /** الحذف نفسه (قابل للاختبار): قاعدة البيانات والملفات، والتفضيلات الخاصة بالجهاز عدا مفتاح التشفير. */
    static void wipe(Context ctx, Db db, boolean keepLicense) {
        AppReset.wipe(ctx, db, keepLicense);
        File prefs = new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
        File[] ps = prefs.listFiles();
        if (ps != null) for (File f : ps) {
            String n = f.getName().replace(".xml", "");
            if (n.equals(Crypto.DEK_PREFS)) continue;
            ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit();
        }
        SmartApp.db = null;
        SmartApp.currentUser = null;
    }
}
