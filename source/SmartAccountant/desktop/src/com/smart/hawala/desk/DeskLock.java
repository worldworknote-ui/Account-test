package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import javax.swing.*;

/**
 * 10.16: قفل البرنامج بكلمة سر على الكمبيوتر حين لا يكون نظام المستخدمين مفعّلًا (في الهاتف: قفل التطبيق).
 * تُطلب عند الفتح، وبعد مدة الخمول، وعند «قفل البرنامج». تُحفظ بصمتها فقط (مملّحة) في إعدادات هذا الجهاز.
 * مع نظام المستخدمين يبقى الدخول برمز المستخدم كما هو.
 */
final class DeskLock {
    private DeskLock() {}

    static boolean on(Db db) { return !db.usersEnabled() && !db.setting("desk_lock_hash", "").isEmpty(); }

    /** يطلب كلمة السر حتى تصح. false = اختار المستخدم إغلاق البرنامج. */
    static boolean ask(Component parent, Db db) {
        while (true) {
            long until = db.settingLong("desk_lock_until", 0);
            long wait = until - Clock.now();
            JPasswordField f = Ui.password();
            JPanel q = Ui.column();
            q.add(Ui.label("البرنامج مقفل. اكتب كلمة سر القفل.", 14, Ui.ink, true));
            if (wait > 0) q.add(Ui.label("محاولات خاطئة كثيرة: انتظر " + Math.max(1, wait / 1000) + " ثانية ثم حاول.", 13, Ui.red, false));
            q.add(Ui.gap(8));
            q.add(f);
            SwingUtilities.invokeLater(f::requestFocusInWindow);
            JOptionPane p = new JOptionPane(Ui.rtl(q), JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"فتح", "إغلاق البرنامج"}, "فتح");
            Ui.show(parent, p, "المحاسب الذكي — مقفل");
            if ("إغلاق البرنامج".equals(p.getValue())) return false;
            if (db.settingLong("desk_lock_until", 0) > Clock.now()) continue;
            if (Pin.verify(new String(f.getPassword()), db.setting("desk_lock_salt", ""), db.setting("desk_lock_hash", ""))) {
                db.setSettingQuiet("desk_lock_fails", "0");
                return true;
            }
            int fails = (int) db.settingLong("desk_lock_fails", 0) + 1;
            db.setSettingQuiet("desk_lock_fails", "" + fails);
            long w = Pin.lockoutMillis(fails);
            if (w > 0) db.setSettingQuiet("desk_lock_until", "" + (Clock.now() + w));
        }
    }

    private static boolean showing;

    /** يحجب النافذة ثم يطلب كلمة السر (من الخمول أو من «قفل البرنامج»). */
    static void lockNow(App app) {
        if (showing || !on(app.db) || App.frame == null) return;
        showing = true;
        Component old = App.frame.getGlassPane();
        JPanel cover = new JPanel(new GridBagLayout());
        cover.setBackground(Ui.bg);
        cover.add(Ui.label(" البرنامج مقفل", 22, Ui.muted, true));
        cover.addMouseListener(new java.awt.event.MouseAdapter() {});
        App.frame.setGlassPane(cover);
        cover.setVisible(true);
        app.locked = true;
        try {
            if (!ask(App.frame, app.db)) app.quit();
        } finally {
            app.locked = false;
            cover.setVisible(false);
            App.frame.setGlassPane(old);
            app.lastInput = Clock.now();
            showing = false;
        }
    }

    /** من الإعدادات: تعيين كلمة السر أو تغييرها أو إلغاء القفل. */
    static void setup(Component parent, Db db, Runnable after) {
        if (db.usersEnabled()) {
            Ui.info(parent, "قفل البرنامج", "نظام المستخدمين مفعّل: الدخول برمز كل مستخدم، والقفل بعد الخمول من الخانة نفسها هنا.");
            return;
        }
        boolean has = !db.setting("desk_lock_hash", "").isEmpty();
        JPasswordField old = Ui.password(), p1 = Ui.password(), p2 = Ui.password();
        JPanel q = Ui.column();
        q.add(Ui.para("كلمة سر تُطلب عند فتح البرنامج وبعد مدة الخمول على هذا الجهاز (4 أحرف أو أرقام على الأقل). "
                + "احفظها جيدًا: نسيانها يمنع فتح البرنامج على هذا الجهاز." + (has ? " لإلغاء القفل اترك الخانتين الجديدتين فارغتين." : ""), 13.5f, Ui.ink, 430));
        q.add(Ui.gap(8));
        if (has) q.add(Ui.labeled("كلمة السر الحالية", old));
        q.add(Ui.labeled(has ? "كلمة السر الجديدة" : "كلمة السر", p1));
        q.add(Ui.labeled("تأكيدها", p2));
        if (!Ui.form(parent, "قفل البرنامج بكلمة سر", q, "حفظ")) return;
        if (has && !Pin.verify(new String(old.getPassword()), db.setting("desk_lock_salt", ""), db.setting("desk_lock_hash", ""))) {
            Ui.toast("كلمة السر الحالية غير صحيحة");
            return;
        }
        String s1 = new String(p1.getPassword()), s2 = new String(p2.getPassword());
        if (has && s1.isEmpty() && s2.isEmpty()) {
            db.deleteSetting("desk_lock_hash");
            db.deleteSetting("desk_lock_salt");
            db.audit("SETTING", "settings", 0, "desk_lock off");
            Ui.toast("أُلغي قفل البرنامج");
            if (after != null) after.run();
            return;
        }
        if (s1.length() < 4) {
            Ui.toast("كلمة السر قصيرة (4 على الأقل)");
            return;
        }
        if (!s1.equals(s2)) {
            Ui.toast("الكلمتان غير متطابقتين");
            return;
        }
        String salt = Pin.newSalt();
        db.setSettingQuiet("desk_lock_salt", salt);
        db.setSettingQuiet("desk_lock_hash", Pin.hash(s1, salt));
        db.setSettingQuiet("desk_lock_fails", "0");
        db.audit("SETTING", "settings", 0, "desk_lock on");
        Ui.toast("فُعّل قفل البرنامج ✓");
        if (after != null) after.run();
    }
}
