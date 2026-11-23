package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import javax.swing.*;

/**
 * 10.4: «إعادة تعيين البرنامج» على الماك والويندوز: يصبح البرنامج على هذا الجهاز كأنه مثبت للتو.
 * خياران: حذف البيانات مع إبقاء الرخصة، أو حذف كل شيء. للمدير (أو صاحب الجهاز بلا نظام مستخدمين)، برمزه وبكتابة «حذف».
 * الأجهزة الأخرى في المزامنة لا تتأثر: يرسل هذا الجهاز ما لم يُرسل ثم يعلن خروجه.
 */
final class DeskReset {
    private DeskReset() {}

    static boolean allowed(Db db) { return !db.usersEnabled() || Auth.isAdmin(SmartApp.currentUser); }

    static void start(App app, Component parent) {
        Db db = app.db;
        if (!allowed(db)) {
            Ui.info(parent, "إعادة تعيين البرنامج", "لمدير المكتب فقط.");
            return;
        }
        int k = Ui.choose(parent, "إعادة تعيين البرنامج", "ماذا تريد أن تحذف من هذا الجهاز؟",
                new String[]{"حذف البيانات وإبقاء الرخصة", "حذف كل شيء بما فيه الرخصة", "إلغاء"});
        if (k != 0 && k != 1) return;
        boolean keep = k == 0;
        boolean sync = SyncEngine.enabled(db);
        JPanel c = Ui.column();
        c.add(Ui.para("يُحذف من هذا الجهاز نهائيًا:\n• كل الحركات والمكاتب والأرصدة والصناديق والعملاء والصور\n• المستخدمون والإعدادات والتصميم\n• النسخ الاحتياطية الداخلية"
                + (keep ? "" : "\n• الرخصة (تحتاج إدخال رمز التفعيل من جديد)")
                + (sync ? "\n\nالمزامنة: يرسل الجهاز ما لم يُرسل ثم يخرج منها. بيانات الأجهزة الأخرى لا تتأثر." : "")
                + "\n\nالنسخ الاحتياطية التي حفظتها في مجلد من اختيارك تبقى.", 14, Ui.ink, 460));
        c.add(Ui.gap(8));
        c.add(Ui.flow(Ui.ghost("نسخة احتياطية أولًا…", e -> app.go("backup"))));
        JPasswordField pin = Ui.password();
        if (db.usersEnabled()) c.add(Ui.labeled("رمز المدير", pin));
        Ui.Field word = Ui.field(AppReset.CONFIRM_WORD);
        c.add(Ui.labeled("للتأكيد اكتب: " + AppReset.CONFIRM_WORD, word));
        if (!Ui.form(parent, keep ? "حذف البيانات (تبقى الرخصة)" : "حذف كل شيء", Ui.rtl(c), "حذف نهائي")) return;
        if (!AppReset.CONFIRM_WORD.equals(word.val().trim())) {
            Ui.info(parent, "إعادة تعيين البرنامج", "لم تُكتب كلمة «" + AppReset.CONFIRM_WORD + "»؛ لم يُحذف شيء.");
            return;
        }
        if (db.usersEnabled() && !db.verifyPin(SmartApp.currentUser.id, new String(pin.getPassword()))) {
            Ui.info(parent, "إعادة تعيين البرنامج", "رمز المدير غير صحيح؛ لم يُحذف شيء.");
            return;
        }
        db.audit("APP_RESET", "app", 0, keep ? "إعادة تعيين: حذف البيانات وإبقاء الرخصة" : "إعادة تعيين: حذف كل شيء");
        Ui.busy(parent, "جارٍ إعادة تعيين البرنامج…", p -> {
            if (SyncEngine.enabled(db)) {
                SyncFolder f = SyncView.folder(db);
                if (f != null) {
                    try {
                        new SyncEngine(db, f).retire();
                    } catch (Throwable ignored) {
                        // يظهر للأجهزة الأخرى غائبًا فيُزال من هناك
                    }
                }
            }
            AppReset.wipe(app.ctx, db, keep);
            if (!keep) {
                try {
                    SecretStore.delete(DeskDriveAuth.REFRESH);
                } catch (Throwable ignored) {
                    // لا شيء محفوظ
                }
            }
            Db fresh = new Db(app.ctx);
            fresh.getWritableDatabase();
            return fresh;
        }, fresh -> {
            Main.restart(app, fresh, "أُعيد تعيين البرنامج: يبدأ كأنه مثبت للتو");
        });
    }
}
