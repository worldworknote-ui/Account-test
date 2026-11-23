package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/** شاشة الدخول (عند تفعيل المستخدمين) وأدوات الرمز السري المشتركة: المحاولات الخاطئة، رمز الاسترداد، تأكيد المدير. */
final class LoginView extends View {
    LoginView(App app) {
        super(app, "تسجيل الدخول");
    }

    @Override void refresh() {
        clear();
        JPanel center = Ui.column();
        Ui.Card c = Ui.card();
        c.setMaximumSize(new Dimension(520, 2000));
        JLabel logo = new JLabel(new ImageIcon(Main.appIcon(84)));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.add(logo);
        String office = db.setting("office_name", "").trim();
        JLabel on = Ui.label(office.isEmpty() ? app.shortTitle() : office, 22, Ui.primaryInk, true);
        on.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.add(on);
        c.add(Ui.gap(4));
        JLabel hint = Ui.label("اكتب اسم المستخدم ورمزك السري", 14, Ui.muted, false);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.add(hint);
        c.add(Ui.gap(14));
        // 10.2: لا تُعرض أسماء المستخدمين؛ كل شخص يكتب اسمه
        Model.User remembered = db.rememberedUser();
        Ui.Field name = Ui.field("اسم المستخدم");
        name.setText(remembered != null ? remembered.name : db.setting("last_login_name", ""));
        name.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        JPasswordField pin = Ui.password();
        pin.setFont(Ui.font(20, true));
        pin.setHorizontalAlignment(JTextField.CENTER);
        pin.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        JCheckBox remember = Ui.check("تذكرني على هذا الجهاز (يُفتح البرنامج بحسابي مباشرة)", remembered != null || "1".equals(db.setting("remember_default", "0")));
        c.add(Ui.labeled("اسم المستخدم", name));
        c.add(Ui.gap(6));
        c.add(Ui.labeled("الرمز السري", pin));
        c.add(Ui.gap(6));
        c.add(Ui.row(remember, Box.createHorizontalGlue()));
        c.add(Ui.gap(10));
        Runnable go = () -> login(name.val(), new String(pin.getPassword()), remember.isSelected(), pin);
        pin.addActionListener(e -> go.run());
        name.addActionListener(e -> pin.requestFocusInWindow());
        Ui.Btn enter = Ui.button("دخول", Ui.primary, e -> go.run());
        enter.setAlignmentX(Component.CENTER_ALIGNMENT);
        enter.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        c.add(enter);
        c.add(Ui.gap(8));
        SwingUtilities.invokeLater(() -> (name.val().isEmpty() ? name : pin).requestFocusInWindow());
        Ui.Btn forgot = Ui.ghost("نسيت الرمز؟", e -> forgot());
        forgot.setAlignmentX(Component.CENTER_ALIGNMENT);
        c.add(forgot);
        if (SyncEngine.enabled(db)) {
            c.add(Ui.gap(10));
            JLabel sh = Ui.label("الحسابات من الجهاز الرئيسي في المزامنة. لا تجد اسمك؟ اطلب من المدير إنشاءه ثم حدّث.", 12.5f, Ui.muted, false);
            sh.setAlignmentX(Component.CENTER_ALIGNMENT);
            c.add(sh);
            Ui.Btn up = Ui.ghost("تحديث الحسابات (مزامنة الآن)", e -> {
                SyncView.now(app);
                new Timer(6000, x -> {
                    ((Timer) x.getSource()).stop();
                    if (app.locked) refresh();
                }).start();
            });
            up.setAlignmentX(Component.CENTER_ALIGNMENT);
            c.add(up);
        }
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);
        wrap.add(c);
        center.add(Ui.gap(40));
        center.add(wrap);
        add(center);
        done();
    }

    // ===================================================================================
    // المحاولات الخاطئة (محفوظة؛ الانتظار يتضاعف)
    // ===================================================================================

    static long remaining(Db db) {
        long until = db.settingLong("login_locked_until", 0);
        long now = Clock.now();
        if (until > now + 2 * 3600000L) until = now + 3600000L;   // ساعة مقدَّمة لا تمد الانتظار بلا حد
        return Math.max(0, until - now);
    }

    static int recordFail(Db db, String who, long userId) {
        int f = (int) db.settingLong("login_fails", 0) + 1;
        db.setSettingQuiet("login_fails", "" + f);
        long ms = Pin.lockoutMillis(f);
        if (ms > 0) db.setSettingQuiet("login_locked_until", "" + (Clock.now() + ms));
        String prev = Db.currentUserName;
        Db.currentUserName = who;
        db.audit("LOGIN_FAILED", "user", userId, "محاولة " + f);
        Db.currentUserName = prev;
        return f;
    }

    static void resetFails(Db db) {
        db.setSettingQuiet("login_fails", "0");
        db.deleteSetting("login_locked_until");
    }

    static String waitText(long ms) {
        long s = (ms + 999) / 1000;
        if (s < 60) return s + " ثانية";
        return (s + 59) / 60 + " دقيقة";
    }

    static boolean canTry(Db db) {
        long w = remaining(db);
        if (w <= 0) return true;
        Ui.toast("محاولات خاطئة كثيرة. انتظر " + waitText(w) + " ثم أعد المحاولة.");
        return false;
    }

    /** نافذة رمز سري؛ null عند الإلغاء. */
    static String askPin(Component parent, String title, String msg) {
        JPanel c = Ui.column();
        if (msg != null && !msg.isEmpty()) {
            c.add(Ui.para(msg, 14, Ui.ink, 320));
            c.add(Ui.gap(8));
        }
        JPasswordField p = Ui.password();
        p.setFont(Ui.font(22, true));
        p.setHorizontalAlignment(JTextField.CENTER);
        c.add(p);
        c.setPreferredSize(new Dimension(340, c.getPreferredSize().height));
        SwingUtilities.invokeLater(p::requestFocusInWindow);
        p.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override public void ancestorAdded(javax.swing.event.AncestorEvent e) { p.requestFocusInWindow(); }

            @Override public void ancestorRemoved(javax.swing.event.AncestorEvent e) {}

            @Override public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
        });
        if (!Ui.form(parent, title, Ui.rtl(c), "متابعة")) return null;
        return new String(p.getPassword());
    }

    void login(String n, String pin, boolean remember, JPasswordField field) {
        if (!canTry(db)) return;
        field.setText("");
        Model.User u = db.userByName(n);
        if (u != null && db.verifyPin(u.id, pin)) {
            resetFails(db);
            db.setSettingQuiet("last_login_name", u.name);
            db.setSettingQuiet("remember_default", remember ? "1" : "0");
            if (remember) db.rememberUser(u.id);
            else db.forgetUser();
            Db.currentUserName = u.name;
            db.audit("LOGIN", "user", u.id, "desk");
            UsersView.reauthAt = Clock.now();
            app.loggedIn(u);
        } else {
            int f = recordFail(db, n.isEmpty() ? "?" : n, u == null ? 0 : u.id);
            long w = Pin.lockoutMillis(f);
            Ui.message(this, "تعذر الدخول", "الاسم أو الرمز غير صحيح." + (w > 0 ? "\nانتظر " + waitText(w) + " قبل المحاولة التالية." : ""), JOptionPane.WARNING_MESSAGE);
        }
    }

    void pin(Model.User u) {
        if (!canTry(db)) return;
        String pin = askPin(this, u.name, "الرمز السري");
        if (pin == null) return;
        if (remaining(db) > 0) {
            canTry(db);
            return;
        }
        if (db.verifyPin(u.id, pin)) {
            resetFails(db);
            Db.currentUserName = u.name;
            db.audit("LOGIN", "user", u.id, "mac");
            UsersView.reauthAt = Clock.now();
            app.loggedIn(u);
        } else {
            int f = recordFail(db, u.name, u.id);
            long w = Pin.lockoutMillis(f);
            Ui.message(this, "رمز خاطئ", w > 0 ? "انتظر " + waitText(w) + " قبل المحاولة التالية." : "بقيت " + Math.max(0, 5 - f) + " محاولة قبل الانتظار.",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    void forgot() {
        String msg = "• الموظف: اطلب من المدير تغيير رمزك من «المستخدمون».\n\n"
                + "• المدير: أدخل رمز الاسترداد الذي حفظته عند تفعيل المستخدمين (مثل ABCD-EFGH-JKMN-PQRS).";
        if (!db.hasRecoveryCode()) {
            Ui.info(this, "نسيت الرمز", msg + "\n\n! لم يُنشأ رمز استرداد لهذه البيانات. يستطيع مدير آخر (إن وُجد) تغيير رمزك.");
            return;
        }
        if (Ui.choose(this, "نسيت الرمز", msg, new String[]{"عندي رمز الاسترداد", "إغلاق"}) != 0) return;
        if (!canTry(db)) return;
        String code = Ui.input(this, "رمز استرداد المدير", "اكتب رمز الاسترداد", "");
        if (code == null) return;
        if (remaining(db) > 0) {
            canTry(db);
            return;
        }
        if (!db.verifyRecoveryCode(code)) {
            int f = recordFail(db, "استرداد المدير", 0);
            long w = Pin.lockoutMillis(f);
            Ui.message(this, "رمز غير صحيح", w > 0 ? "انتظر " + waitText(w) + "." : "رمز الاسترداد غير صحيح.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        resetFails(db);
        db.audit("RECOVERY_USED", "user", 0, "mac");
        List<Model.User> admins = new ArrayList<>();
        for (Model.User u : db.users(true)) if (Auth.ADMIN.equals(u.role)) admins.add(u);
        if (admins.isEmpty()) {
            Ui.error(this, new IllegalStateException("لا يوجد مدير نشط."));
            return;
        }
        Model.User admin = admins.get(0);
        if (admins.size() > 1) {
            String[] names = new String[admins.size()];
            for (int i = 0; i < names.length; i++) names[i] = admins.get(i).name;
            int k = Ui.choose(this, "أي مدير؟", "اختر حساب المدير", names);
            if (k < 0) return;
            admin = admins.get(k);
        }
        DeskBridge.recoveryGrant(true);
        try {
            String p1 = askPin(this, "رمز جديد للمدير: " + admin.name, "رمز المدير من 6 إلى 8 أرقام");
            if (p1 == null) return;
            String p2 = askPin(this, "تأكيد الرمز", "أعد كتابة الرمز");
            if (p2 == null) return;
            if (!Pin.validAdmin(p1)) throw new IllegalArgumentException("رمز المدير من 6 إلى 8 أرقام");
            if (!p1.equals(p2)) throw new IllegalArgumentException("الرمزان غير متطابقين");
            db.setPin(admin.id, p1);
            String fresh = db.newRecoveryCode();
            UsersView.showRecoveryCode(this, fresh, "استُخدم رمز الاسترداد القديم ولم يعد صالحًا. هذا رمز الاسترداد الجديد:");
            Db.currentUserName = admin.name;
            UsersView.reauthAt = Clock.now();
            app.loggedIn(admin);
        } catch (Exception e) {
            Ui.error(this, e);
        } finally {
            DeskBridge.recoveryGrant(false);
        }
    }
}
