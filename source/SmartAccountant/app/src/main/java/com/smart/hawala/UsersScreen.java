package com.smart.hawala;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * المستخدمون والصلاحيات: لكل موظف اسم ودور ورمز سري (PIN). بدون مستخدمين يعمل التطبيق كما كان بصلاحيات كاملة.
 *
 * الأمان (5.2):
 * - لا دخول كمدير ببصمة الهاتف أو قفله: البصمة تثبت أن صاحب الهاتف يفتحه، لا أنه المدير.
 * - استرداد المدير برمز استرداد يُعرض مرة واحدة عند التفعيل ويُحفظ مجزّأً، ويُستبدل بعد كل استخدام.
 * - المحاولات الخاطئة تُحفظ على الهاتف (لا تُصفَّر بإغلاق التطبيق)، والانتظار يتضاعف من 30 ثانية حتى ساعة.
 * - تغيير الأدوار والرموز وإيقاف النظام يتطلب إعادة إدخال رمز المدير.
 * - رمز المدير من 6 إلى 8 أرقام.
 */
final class UsersScreen {
    private static final long REAUTH_MS = 120000L;
    private static final int WARN_AT = 5;

    private final MainActivity a;
    private long reauthAt;

    UsersScreen(MainActivity a) { this.a = a; }

    // ===================================================================================
    // المحاولات الخاطئة (محفوظة على الهاتف)
    // ===================================================================================

    /**
     * 8.0: مهلة الانتظار تُقاس بزمن تشغيل الهاتف (elapsedRealtime) لا بساعته، فتقديم الساعة لا ينهيها.
     * إن أُعيد تشغيل الهاتف (الزمن الحالي أصغر من وقت البدء) تبدأ المهلة كاملة من جديد.
     */
    static void startLock(Db db, long ms) {
        db.setSettingQuiet("lock_start_rt", "" + SystemClock.elapsedRealtime());
        db.setSettingQuiet("lock_ms", "" + ms);
    }

    static long remaining(Db db) {
        long ms = db.settingLong("lock_ms", 0);
        if (ms <= 0) return 0;
        long now = SystemClock.elapsedRealtime(), start = db.settingLong("lock_start_rt", 0);
        if (now < start) {
            start = now;
            db.setSettingQuiet("lock_start_rt", "" + start);
        }
        return Math.max(0, ms - (now - start));
    }

    private long lockRemaining() {
        return remaining(a.db);
    }

    /** يسجّل محاولة خاطئة ويعيد عدد المحاولات المتتالية. */
    private int recordFail(String who, long userId) {
        int f = (int) a.db.settingLong("login_fails", 0) + 1;
        a.db.setSettingQuiet("login_fails", "" + f);
        long ms = Pin.lockoutMillis(f);
        if (ms > 0) startLock(a.db, ms);
        String prev = Db.currentUserName;
        Db.currentUserName = who;
        a.db.audit("LOGIN_FAILED", "user", userId, "محاولة " + f);
        Db.currentUserName = prev;
        return f;
    }

    private void resetFails() {
        a.db.setSettingQuiet("login_fails", "0");
        a.db.deleteSetting("login_locked_until");
        a.db.deleteSetting("lock_ms");
    }

    static String waitText(long ms) {
        long s = (ms + 999) / 1000;
        if (s < 60) return s + " ثانية";
        long m = (s + 59) / 60;
        return m + " دقيقة";
    }

    /** يعيد true إن كان مسموحًا بالمحاولة الآن، وإلا يعرض مدة الانتظار. */
    private boolean canTry() {
        long w = lockRemaining();
        if (w <= 0) return true;
        a.toast("محاولات خاطئة كثيرة. انتظر " + waitText(w) + " ثم أعد المحاولة.");
        return false;
    }

    private void failMessage(EditText field, int f) {
        long w = Pin.lockoutMillis(f);
        if (w > 0) a.toast("رمز خاطئ. انتظر " + waitText(w) + " قبل المحاولة التالية.");
        else field.setError("رمز خاطئ. بقيت " + (WARN_AT - f) + " محاولة قبل الانتظار.");
    }

    // ===================================================================================
    // الدخول
    // ===================================================================================

    /** 10.2: الخروج من الحساب (ويُنسى «تذكرني» على هذا الجهاز). */
    void logout() {
        a.sessionEnd();
        a.db.forgetUser();
        SmartApp.currentUser = null;
        Db.currentUserName = "المستخدم المحلي";
        showLogin();
    }

    /** 10.2: إن كان على هذا الجهاز حساب محفوظ («تذكرني») يُدخل به مباشرة. */
    boolean autoLogin() {
        Model.User u = a.db.rememberedUser();
        if (u == null) return false;
        SmartApp.currentUser = u;
        Db.currentUserName = u.name;
        a.lastAuthAt = SystemClock.elapsedRealtime();
        return true;
    }

    /**
     * 10.2: شاشة الدخول لا تعرض أسماء المستخدمين (لا يعرف أحد حسابات غيره): يكتب كل شخص اسمه ورمزه.
     * «تذكرني على هذا الجهاز» يفتح التطبيق بعدها بحسابه مباشرة.
     */
    void showLogin() {
        Db.recoveryGrant = false;
        a.base("تسجيل الدخول", null);
        Bitmap bm = a.homeLogo();
        if (bm != null) {
            ImageView logo = new ImageView(a);
            logo.setImageBitmap(bm);
            logo.setAdjustViewBounds(true);
            a.body.addView(logo, a.lp(-1, 90, 10));
        }
        String office = a.db.setting("office_name", "").trim();
        if (!office.isEmpty()) {
            TextView on = a.text(office, 20, a.primary, true);
            on.setGravity(Gravity.CENTER);
            a.add(on);
        }
        TextView t = a.text("اكتب اسم المستخدم ورمزك السري", 16, a.dark, false);
        t.setGravity(Gravity.CENTER);
        a.add(t);
        final EditText name = a.field("اسم المستخدم");
        name.setText(a.db.setting("last_login_name", ""));
        a.body.addView(name, a.lp(-1, 54, 6));
        final EditText pin = a.field("الرمز السري");
        Ui.secret(pin, true);
        pin.setGravity(Gravity.CENTER);
        a.body.addView(pin, a.lp(-1, 54, 6));
        final CheckBox remember = new CheckBox(a);
        remember.setText("تذكرني على هذا الجهاز (الدخول مباشرة بعد ذلك)");
        remember.setTextColor(a.ink);
        remember.setChecked("1".equals(a.db.setting("remember_default", "0")));
        a.add(remember);
        a.button("دخول", a.primary, v -> {
            if (!canTry()) return;
            String n = name.getText().toString().trim(), p = pin.getText().toString();
            pin.setText("");
            Model.User u = a.db.userByName(n);
            if (u != null && a.db.verifyPin(u.id, p)) {
                a.db.setSettingQuiet("last_login_name", u.name);
                a.db.setSettingQuiet("remember_default", remember.isChecked() ? "1" : "0");
                if (remember.isChecked()) a.db.rememberUser(u.id);
                else a.db.forgetUser();
                login(u);
            } else {
                // رسالة واحدة للاسم والرمز: لا تكشف إن كان الاسم موجودًا
                int f = recordFail(n.isEmpty() ? "?" : n, u == null ? 0 : u.id);
                long w = Pin.lockoutMillis(f);
                if (w > 0) a.toast("الاسم أو الرمز غير صحيح. انتظر " + waitText(w) + " قبل المحاولة التالية.");
                else pin.setError("الاسم أو الرمز غير صحيح");
            }
        });
        if (SyncEngine.enabled(a.db)) {
            TextView h = a.text("الحسابات يُنشئها المدير من الجهاز الرئيسي. لا يقبل اسمك؟ اطلب من المدير إنشاء حسابك ثم اضغط «تحديث».", 13, a.muted, false);
            h.setGravity(Gravity.CENTER);
            a.add(h);
            a.button("تحديث الحسابات (مزامنة الآن)", a.accent, v -> a.syncScreen.now(this::showLogin));
        }
        a.button("نسيت الرمز؟", Color.rgb(130, 130, 130), v -> forgotDialog());
    }

    private EditText pinField(String hint) {
        EditText p = new EditText(a);
        p.setHint(hint);
        Ui.secret(p, true);
        p.setGravity(Gravity.CENTER);
        p.setTextSize(22);
        return p;
    }

    private LinearLayout padded(android.view.View... views) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(22), a.dp(8), a.dp(22), a.dp(4));
        for (android.view.View v : views) l.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    private void pinDialog(final Model.User u) {
        if (!canTry()) return;
        final EditText p = pinField("الرمز السري");
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle(u.name).setView(padded(p))
                .setPositiveButton("دخول", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = p.getText().toString();
            p.setText("");
            if (lockRemaining() > 0) {
                dlg.dismiss();
                canTry();
                return;
            }
            if (a.db.verifyPin(u.id, pin)) {
                dlg.dismiss();
                login(u);
            } else {
                int f = recordFail(u.name, u.id);
                if (Pin.lockoutMillis(f) > 0) dlg.dismiss();
                failMessage(p, f);
            }
        }));
        dlg.show();
    }

    private void login(Model.User u) {
        Db.recoveryGrant = false;
        resetFails();
        SmartApp.currentUser = u;
        Db.currentUserName = u.name;
        a.lastAuthAt = SystemClock.elapsedRealtime();
        reauthAt = SystemClock.elapsedRealtime();
        a.db.audit("LOGIN", "user", u.id, "");
        a.db.setSettingQuiet("notif_last_user", "" + u.id);
        a.showHome();
    }

    // ===================================================================================
    // نسيان الرمز: رمز الاسترداد فقط (لا بصمة الهاتف)
    // ===================================================================================

    private void forgotDialog() {
        String msg = "• الموظف: اطلب من المدير تغيير رمزك من «الإعدادات ← المستخدمون».\n\n"
                + "• المدير: أدخل رمز الاسترداد الذي حفظته عند تفعيل المستخدمين (مثل ABCD-EFGH-JKMN-PQRS).\n\n"
                + "بصمة الهاتف وقفله لا تُستخدم للدخول كمدير، حتى لا يستطيع حامل الهاتف السيطرة على الحسابات.";
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("نسيت الرمز").setMessage(msg).setNegativeButton("إغلاق", null);
        if (a.db.hasRecoveryCode()) b.setPositiveButton("عندي رمز الاسترداد", (d, w) -> recoveryDialog());
        else b.setMessage(msg + "\n\n⚠ لم يُنشأ رمز استرداد لهذه البيانات. يستطيع مدير آخر (إن وُجد) تغيير رمزك.");
        b.show();
    }

    private void recoveryDialog() {
        if (!canTry()) return;
        final EditText code = new EditText(a);
        code.setHint("رمز الاسترداد");
        code.setSingleLine(true);
        code.setGravity(Gravity.CENTER);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setTypeface(Typeface.MONOSPACE);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("رمز استرداد المدير").setView(padded(code))
                .setPositiveButton("تحقق", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (lockRemaining() > 0) {
                dlg.dismiss();
                canTry();
                return;
            }
            if (!a.db.verifyRecoveryCode(code.getText().toString())) {
                int f = recordFail("استرداد المدير", 0);
                if (Pin.lockoutMillis(f) > 0) dlg.dismiss();
                failMessage(code, f);
                return;
            }
            dlg.dismiss();
            resetFails();
            a.db.audit("RECOVERY_USED", "user", 0, "");
            List<Model.User> admins = new ArrayList<>();
            for (Model.User u : a.db.users(true)) if (Auth.ADMIN.equals(u.role)) admins.add(u);
            if (admins.isEmpty()) {
                a.error(new Exception("لا يوجد مدير نشط."));
                return;
            }
            if (admins.size() == 1) recoverAdmin(admins.get(0));
            else {
                String[] names = new String[admins.size()];
                for (int i = 0; i < names.length; i++) names[i] = admins.get(i).name;
                new AlertDialog.Builder(a).setTitle("أي مدير؟").setCancelable(false)
                        .setItems(names, (x, i) -> recoverAdmin(admins.get(i))).show();
            }
        }));
        dlg.show();
    }

    /** بعد رمز استرداد صحيح: رمز جديد للمدير، ثم رمز استرداد جديد (القديم صار مستخدمًا). */
    private void recoverAdmin(final Model.User admin) {
        Db.recoveryGrant = true;   // رمز استرداد صحيح: يُسمح بتغيير رمز المدير مرة واحدة قبل الدخول
        newPinDialog(admin, "رمز جديد للمدير: " + admin.name, false, () -> {
            // 10.5: يدخل المدير أولًا ثم يُنشأ رمز الاسترداد الجديد بصلاحيته (لا بإذن الاسترداد الذي يُغلق مع النافذة)
            SmartApp.currentUser = admin;
            Db.currentUserName = admin.name;
            String fresh = a.db.newRecoveryCode();
            Db.recoveryGrant = false;
            a.lastAuthAt = SystemClock.elapsedRealtime();
            reauthAt = SystemClock.elapsedRealtime();
            showRecoveryCode(fresh, "استُخدم رمز الاسترداد القديم ولم يعد صالحًا. هذا رمز الاسترداد الجديد:", a::showHome);
        });
    }

    /** يعرض رمز الاسترداد مرة واحدة، ولا يُغلق قبل تأكيد حفظه. */
    void showRecoveryCode(final String code, String intro, final Runnable after) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(4));
        l.addView(a.text(intro, 14, a.dark, false));
        TextView c = a.text(code, 22, a.primary, true);
        c.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        c.setGravity(Gravity.CENTER);
        c.setTextIsSelectable(true);
        c.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
        c.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        c.setPadding(a.dp(6), a.dp(14), a.dp(6), a.dp(14));
        c.setBackground(a.round(Color.rgb(240, 247, 243), a.primary, 10));
        l.addView(c, new LinearLayout.LayoutParams(-1, -2));
        l.addView(a.text("اكتبه على ورقة واحفظه في مكان آمن بعيدًا عن الهاتف. هو الطريقة الوحيدة لاستعادة دخول المدير إن نُسي الرمز، ولن يظهر مرة أخرى.", 13, a.red, true));
        Button copy = a.smallButton("نسخ الرمز", a.accent, v -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                // 8.0: يُعلَّم حساسًا (تُخفى معاينته من Android 13) ويُمسح من الحافظة بعد دقيقة
                ClipData clip = ClipData.newPlainText("رمز الاسترداد", code);
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(extras);
                cm.setPrimaryClip(clip);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        cm.clearPrimaryClip();
                    } catch (Throwable ignored) {
                        // غير مهم
                    }
                }, 60000);
            }
            a.toast("نُسخ الرمز لدقيقة واحدة. الصقه في مكان آمن الآن.");
        });
        l.addView(copy, a.lp(-1, 42, 4));
        final CheckBox saved = new CheckBox(a);
        saved.setText("كتبت الرمز وحفظته");
        l.addView(saved);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("رمز استرداد المدير").setView(l).setCancelable(false)
                .setPositiveButton("تم", null).create();
        dlg.setOnShowListener(d -> {
            final Button ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setEnabled(false);
            saved.setOnCheckedChangeListener((b, x) -> ok.setEnabled(x));
            ok.setOnClickListener(v -> {
                dlg.dismiss();
                if (after != null) after.run();
            });
        });
        dlg.show();
    }

    // ===================================================================================
    // تأكيد هوية المدير قبل العمليات الحساسة
    // ===================================================================================

    void confirmAdmin(final String why, final Runnable action) {
        final Model.User me = SmartApp.currentUser;
        if (!a.db.usersEnabled() || me == null) {
            action.run();
            return;
        }
        if (reauthAt > 0 && SystemClock.elapsedRealtime() - reauthAt < REAUTH_MS) {
            action.run();
            return;
        }
        if (!canTry()) return;
        final EditText p = pinField("رمزك السري");
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("تأكيد: " + why)
                .setMessage("أدخل رمزك للتأكيد.").setView(padded(p))
                .setPositiveButton("تأكيد", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (lockRemaining() > 0) {
                dlg.dismiss();
                canTry();
                return;
            }
            if (a.db.verifyPin(me.id, p.getText().toString())) {
                resetFails();
                reauthAt = SystemClock.elapsedRealtime();
                dlg.dismiss();
                action.run();
            } else {
                p.setText("");
                int f = recordFail(me.name, me.id);
                if (Pin.lockoutMillis(f) > 0) dlg.dismiss();
                failMessage(p, f);
            }
        }));
        dlg.show();
    }

    // ===================================================================================
    // إدارة المستخدمين
    // ===================================================================================

    void showManager() {
        if (!a.require(Auth.Perm.USERS)) return;
        a.here(this::showManager);
        a.tabbedScreen("المستخدمون والصلاحيات", a.moreScreen::show, "more");
        final String block = a.db.founderBlock();
        if (!a.db.usersEnabled()) {
            LinearLayout c = a.card();
            c.addView(a.text("نظام المستخدمين غير مفعّل", 18, a.primary, true));
            c.addView(a.text("حاليًا يعمل التطبيق بصلاحيات كاملة لأي شخص يفتحه.\n\nعند التفعيل: لكل شخص اسم ورمز سري، ويختار المدير صلاحياته واحدة واحدة، "
                    + "وتظهر شاشة دخول، وتُسجَّل كل حركة باسم من سجّلها.", 14, a.dark, false));
            if (block != null) {
                a.notice(R.drawable.ic_info, block, false, null, null);
                return;
            }
            if (SyncEngine.enabled(a.db)) a.hint("هذا هو الجهاز الرئيسي في المزامنة: الحسابات التي تنشئها هنا تصل إلى كل الأجهزة المنضمة، ويُطلب فيها الدخول.");
            a.button("تفعيل: إنشاء حساب المدير", a.primary, v -> userDialog(null, true));
            return;
        }
        if (UsageScreen.allowed(a)) a.body.addView(a.smallButton("سجل الاستخدام: متى وكم استخدم كل محاسب التطبيق", a.accent, v -> a.usageScreen.show()), a.lp(-1, 42, 3));
        LinearLayout rc = a.card();
        boolean has = a.db.hasRecoveryCode();
        rc.addView(a.text(has ? "✓ رمز استرداد المدير محفوظ" : "⚠ لا يوجد رمز استرداد للمدير", 16, has ? a.green : a.red, true));
        rc.addView(a.text(has ? "إن فُقد الرمز المكتوب أنشئ رمزًا جديدًا؛ القديم يتوقف فورًا."
                : "بدونه لا يمكن استعادة دخول المدير إن نُسي رمزه. أنشئه الآن واكتبه على ورقة.", 13, a.dark, false));
        rc.addView(a.smallButton(has ? "إنشاء رمز استرداد جديد" : "إنشاء رمز الاسترداد", has ? a.accent : a.red,
                v -> confirmAdmin("رمز استرداد جديد", this::createRecovery)), a.lp(-1, 44, 4));

        if (SyncEngine.enabled(a.db)) a.hint("الحسابات والصلاحيات متزامنة: أي تعديل هنا يصل إلى كل الأجهزة. أرسل لكل شخص اسمه ورمزه ليدخل من جهازه.");
        a.button("إضافة مستخدم", a.primary, v -> confirmAdmin("إضافة مستخدم", () -> userDialog(null, false)));
        // 10.8: من يسجّل كل مكتب ومن يتحقق منه
        a.button("فرز المكاتب بين المحاسبين", a.accent, v -> confirmAdmin("فرز المكاتب", a.teamScreen::show));
        // 10.17: رسالة يكتبها المدير وتظهر إشعارًا عند المحاسبين (الكل أو واحد)
        if (SmartApp.currentUser != null && Auth.isAdmin(SmartApp.currentUser)) a.button("رسالة إلى المحاسبين", a.accent, v -> adminMessages());
        Model.User me = SmartApp.currentUser;
        final boolean meAdmin = me == null || Auth.isAdmin(a.db.user(me.id));
        for (final Model.User u : a.db.users(false)) {
            LinearLayout x = a.card();
            boolean self = me != null && me.id == u.id;
            x.addView(a.text(u.name + (self ? " (أنت)" : "") + (u.active ? "" : " — موقوف"), 17, u.active ? a.primary : a.muted, true));
            // 12.6: الصلاحيات والمكاتب تحت سهم حتى لا تملأ الشاشة (المدير سطر واحد قصير)
            String roles = TeamScreen.rolesLine(a.db, u.id);
            if (Auth.isAdmin(u) && roles.isEmpty()) x.addView(a.text(Auth.describe(u), 13, a.dark, false));
            else x.addView(foldDetails(u, Auth.describe(u), roles));
            LinearLayout r = a.row();
            // 10.5 (AUDIT-023): حساب المدير يعدّله ويغيّر رمزه المدير وحده
            boolean mayTouch = !Auth.isAdmin(u) || meAdmin;
            if (mayTouch) {
                r.addView(a.smallButton("الصلاحيات والتعديل", a.accent, v -> confirmAdmin("تعديل " + u.name, () -> userDialog(u, false))), a.weight(42, 1, 2));
                r.addView(a.smallButton("تغيير الرمز", Color.rgb(120, 120, 120),
                        v -> confirmAdmin("تغيير رمز " + u.name, () -> newPinDialog(u, "رمز جديد لـ " + u.name, false, this::showManager))), a.weight(42, 1, 2));
            } else r.addView(a.text("حساب المدير يعدّله المدير وحده", 12, a.muted, false));
            x.addView(r);
            if (mayTouch) {
                LinearLayout r2 = a.row();
                r2.addView(a.smallButton("المكاتب", a.accent, v -> confirmAdmin("مكاتب " + u.name, () -> officesDialog(u))), a.weight(42, 1, 2));
                // 10.8: حذف الحساب دون المساس بما سجّله
                if (!self) r2.addView(a.smallButton("حذف الحساب", a.red, v -> confirmAdmin("حذف حساب " + u.name, () -> deleteDialog(u))), a.weight(42, 1, 2));
                x.addView(r2);
            }
        }
        if (block == null) {
            a.button("إيقاف نظام المستخدمين", a.red, v -> confirmAdmin("إيقاف نظام المستخدمين", () -> a.confirm("إيقاف نظام المستخدمين",
                    "ستُوقف كل الحسابات ويعود التطبيق للعمل بدون دخول وبصلاحيات كاملة" + (SyncEngine.enabled(a.db) ? " على كل الأجهزة المتزامنة" : "")
                            + ". يمكنك إعادة التفعيل لاحقًا.", "إيقاف", () -> {
                        try {
                            a.db.disableUsers();
                        } catch (Exception e) {
                            a.error(e);
                            return;
                        }
                        SmartApp.currentUser = null;
                        Db.currentUserName = "المستخدم المحلي";
                        a.toast("تم إيقاف نظام المستخدمين");
                        a.showHome();
                    })));
        }
    }

    /** 12.6: «الصلاحيات والمكاتب ◂» مطوية، يفتحها السهم. */
    private android.view.View foldDetails(Model.User u, String perms, String roles) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = a.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6));
        final TextView arrow = a.text("◂", 15, a.primaryInk, true);
        head.addView(arrow, new LinearLayout.LayoutParams(a.dp(20), -2));
        int offices = roles.isEmpty() ? 0 : a.db.officeRoles(u.id).size();
        String title = Auth.isAdmin(u) ? "المكاتب" : "الصلاحيات والمكاتب";
        head.addView(a.text(title + (offices > 0 ? " (" + offices + " مكتب)" : ""), 14, a.primaryInk, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.setContentDescription("تفاصيل " + u.name);
        final LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        if (!Auth.isAdmin(u) || roles.isEmpty()) body.addView(a.text(perms, 13, a.dark, false));
        if (!roles.isEmpty()) body.addView(a.text(roles, 13, a.goldInk, false));
        body.setVisibility(android.view.View.GONE);
        head.setOnClickListener(v -> {
            boolean o = body.getVisibility() != android.view.View.VISIBLE;
            body.setVisibility(o ? android.view.View.VISIBLE : android.view.View.GONE);
            arrow.setText(o ? "▾" : "◂");
        });
        wrap.addView(head);
        wrap.addView(body);
        if (Auth.isAdmin(u)) wrap.addView(a.text(perms, 13, a.dark, false), 0);
        return wrap;
    }

    private void createRecovery() {
        String code = a.db.newRecoveryCode();
        showRecoveryCode(code, "رمز استرداد المدير الجديد (أي رمز سابق توقف):", () -> {
            if (!a.onHome) showManager();
            else a.showHome();
        });
    }

    /** يُستدعى من بطاقة التنبيه في الصفحة الرئيسية. */
    void createRecoveryFromHome() { confirmAdmin("رمز استرداد المدير", this::createRecovery); }

    // ===================================================================================
    // 11.6: قوالب الصلاحيات المحفوظة (الاسم ← الصلاحيات)، في الإعدادات فتنتقل بالمزامنة
    // ===================================================================================

    static final String PRESETS = "perm_presets";

    java.util.Map<String, String> presets() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        try {
            org.json.JSONObject j = new org.json.JSONObject(a.db.setting(PRESETS, "{}"));
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, j.optString(k));
            }
        } catch (Exception ignored) {
            // فارغ
        }
        return m;
    }

    private void storePresets(java.util.Map<String, String> m) {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            for (java.util.Map.Entry<String, String> e : m.entrySet()) j.put(e.getKey(), e.getValue());
            a.db.setSetting(PRESETS, j.toString());
        } catch (Exception e) {
            a.error(e);
        }
    }

    private static java.util.Set<String> chosen(java.util.Map<Auth.Perm, CheckBox> boxes, CheckBox review) {
        java.util.Set<String> c = new java.util.LinkedHashSet<>();
        for (java.util.Map.Entry<Auth.Perm, CheckBox> e : boxes.entrySet()) if (e.getValue().isChecked()) c.add(e.getKey().name());
        if (review.isChecked() && boxes.get(Auth.Perm.TX_ADD).isChecked()) c.add(Auth.REVIEW);
        if (!c.isEmpty()) {
            c.add(Auth.DETAILED);
            c.add(Auth.DETAILED126);
        }
        return c;
    }

    private void savePreset(final java.util.Map<Auth.Perm, CheckBox> boxes, final CheckBox review) {
        final java.util.Set<String> c = chosen(boxes, review);
        if (c.isEmpty()) {
            a.toast("اختر صلاحية واحدة على الأقل");
            return;
        }
        final EditText name = a.field("اسم القالب، مثل: محاسب فرع");
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(name, a.lp(-1, 50, 4));
        l.addView(a.text((c.size() - 1) + " صلاحية. إن وُجد قالب بالاسم نفسه يُستبدل.", 12, a.muted, false));
        new AlertDialog.Builder(a).setTitle("حفظ قالب صلاحيات").setView(l).setPositiveButton("حفظ", (d, w) -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                a.toast("اكتب اسمًا للقالب");
                return;
            }
            java.util.Map<String, String> m = presets();
            m.put(n, Auth.csv(c));
            storePresets(m);
            a.toast("حُفظ القالب «" + n + "»");
        }).setNegativeButton("إلغاء", null).show();
    }

    private void applyPreset(final java.util.Map<Auth.Perm, CheckBox> boxes, final CheckBox review) {
        final java.util.Map<String, String> m = presets();
        if (m.isEmpty()) {
            a.toast("لا قوالب محفوظة بعد: اختر الصلاحيات ثم «حفظ كقالب…»");
            return;
        }
        final String[] names = m.keySet().toArray(new String[0]);
        new AlertDialog.Builder(a).setTitle("تطبيق قالب صلاحيات").setItems(names, (d, w) -> {
            java.util.Set<String> p = Auth.expand(Auth.parse(m.get(names[w])));
            for (java.util.Map.Entry<Auth.Perm, CheckBox> e : boxes.entrySet()) e.getValue().setChecked(p.contains(e.getKey().name()));
            review.setChecked(p.contains(Auth.REVIEW));
            a.toast("طُبّق القالب «" + names[w] + "». راجع ثم احفظ.");
        }).setNeutralButton("حذف قالب…", (d, w) -> new AlertDialog.Builder(a).setTitle("حذف قالب").setItems(names, (d2, w2) -> {
            m.remove(names[w2]);
            storePresets(m);
            a.toast("حُذف القالب «" + names[w2] + "»");
        }).setNegativeButton("إلغاء", null).show()).setNegativeButton("إلغاء", null).show();
    }

    private static boolean pinOk(String role, String pin) {
        return Auth.ADMIN.equals(role) ? Pin.validAdmin(pin) : Pin.valid(pin);
    }

    private static String pinRule(String role) {
        return Auth.ADMIN.equals(role) ? "رمز المدير من 6 إلى 8 أرقام" : "الرمز من 4 إلى 8 أرقام";
    }

    private void userDialog(final Model.User old, final boolean firstAdmin) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(4));
        l.addView(a.text(old == null ? (firstAdmin ? "حساب المدير" : "مستخدم جديد") : "تعديل «" + old.name + "»", 19, a.primaryInk, true));
        final EditText name = a.field("اسم المستخدم");
        if (old != null) name.setText(old.name);
        l.addView(name, a.lp(-1, 50, 4));
        // 10.17: الاسم الظاهر لبقية المحاسبين بدل اسم الدخول (اختياري)
        final EditText nick = a.field("الاسم الظاهر للآخرين (اختياري)");
        if (old != null) nick.setText(old.nick);
        l.addView(nick, a.lp(-1, 50, 4));
        l.addView(a.text("يراه بقية المحاسبين في السجل والتفاصيل بدل اسم الدخول. المدير يرى اسم الدخول دائمًا.", 12, a.muted, false));
        final CheckBox admin = new CheckBox(a);
        admin.setText("مدير — كل الصلاحيات");
        admin.setTextSize(16);
        admin.setChecked(firstAdmin || Auth.isAdmin(old));
        // 10.5 (AUDIT-023): صفة «مدير» يمنحها أو يسحبها المدير وحده
        boolean iAmAdmin = SmartApp.currentUser == null || Auth.isAdmin(a.db.user(SmartApp.currentUser.id));
        admin.setEnabled(!firstAdmin && iAmAdmin);
        l.addView(admin);
        final LinearLayout permBox = new LinearLayout(a);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.addView(a.text("الصلاحيات (اختر ما يناسب هذا الشخص):", 14, a.primaryInk, true));
        final java.util.Map<Auth.Perm, CheckBox> boxes = new java.util.LinkedHashMap<>();
        java.util.Set<String> cur = old == null ? Auth.parse("VIEW,TX_ADD") : Auth.perms(old);
        final CheckBox review = new CheckBox(a);
        // 11.6: قوالب صلاحيات محفوظة بأسماء يختارها المدير (تُطبَّق على مستخدم جديد أو قائم)
        LinearLayout pr = a.row();
        pr.addView(a.smallButton("تطبيق قالب محفوظ…", a.accent, v -> applyPreset(boxes, review)), a.weight(40, 1, 2));
        pr.addView(a.smallButton("حفظ كقالب…", Color.rgb(120, 120, 120), v -> savePreset(boxes, review)), a.weight(40, 1, 2));
        permBox.addView(pr);
        // 12.0: الصلاحيات حسب أقسام التطبيق، كل قسم يُفتح بسهم ومعه عدد المختار و«الكل/لا شيء»
        for (int si = 0; si < Auth.SECTIONS.length; si++) {
            final Object[] sec = Auth.SECTIONS[si];
            final Auth.Perm[] ps = (Auth.Perm[]) sec[1];
            int on = 0;
            for (Auth.Perm p : ps) if (cur.contains(p.name())) on++;
            final LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(a.dp(4), 0, a.dp(4), a.dp(6));
            LinearLayout head = a.row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            head.setBackground(a.ripple(a.round(a.surface, a.cardStroke, 14), 14));
            boolean open = si == 0;
            final TextView arrow = a.text(open ? "▾" : "◂", 16, a.primaryInk, true);
            head.addView(arrow, new LinearLayout.LayoutParams(a.dp(22), -2));
            head.addView(a.text((String) sec[0], 15, a.primaryInk, true), new LinearLayout.LayoutParams(0, -2, 1));
            final TextView count = a.text(on + " من " + ps.length, 13, on > 0 ? a.green : a.muted, true);
            head.addView(count, new LinearLayout.LayoutParams(-2, -2));
            head.setContentDescription((String) sec[0]);
            LinearLayout.LayoutParams hp = a.lp(-1, -2, 4);
            permBox.addView(head, hp);
            permBox.addView(box);
            box.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
            head.setOnClickListener(v -> {
                boolean o = box.getVisibility() != android.view.View.VISIBLE;
                box.setVisibility(o ? android.view.View.VISIBLE : android.view.View.GONE);
                arrow.setText(o ? "▾" : "◂");
            });
            final List<CheckBox> mine = new ArrayList<>();
            final Runnable recount = () -> {
                int n = 0;
                for (CheckBox x : mine) if (x.isChecked()) n++;
                count.setText(n + " من " + mine.size());
                count.setTextColor(n > 0 ? a.green : a.muted);
            };
            LinearLayout allRow = a.row();
            allRow.addView(a.smallButton("الكل", a.accent, v -> { for (CheckBox x : mine) x.setChecked(true); }), a.weight(34, 1, 2));
            allRow.addView(a.smallButton("لا شيء", Color.rgb(130, 130, 130), v -> { for (CheckBox x : mine) x.setChecked(false); }), a.weight(34, 1, 2));
            box.addView(allRow);
            for (Auth.Perm p : ps) {
                CheckBox cb = new CheckBox(a);
                cb.setText(Auth.title(p));
                cb.setChecked(cur.contains(p.name()));
                cb.setOnCheckedChangeListener((bb, x) -> recount.run());
                mine.add(cb);
                box.addView(cb);
                String h = Auth.hint(p);
                if (!h.isEmpty()) {
                    TextView ht = a.text(h, 11, a.muted, false);
                    ht.setPadding(a.dp(32), 0, 0, a.dp(2));
                    box.addView(ht);
                }
                boxes.put(p, cb);
                if (p == Auth.Perm.TX_ADD) {
                    // 10.3: الاعتماد لكل مستخدم: كل حركاته (أي مبلغ) تنتظر اعتماد من يملك «الاعتماد»
                    review.setText("حركاته تحتاج اعتمادًا قبل أن تدخل الأرصدة (أيًا كان المبلغ)");
                    review.setChecked(cur.contains(Auth.REVIEW));
                    review.setPadding(a.dp(24), 0, 0, 0);
                    box.addView(review);
                    TextView rh = a.text("يعتمدها أو يصححها أو يرفضها المدير أو من يملك «اعتماد الحركات»، ويصله إشعار.", 11, a.muted, false);
                    rh.setPadding(a.dp(56), 0, 0, a.dp(2));
                    box.addView(rh);
                }
            }
        }
        l.addView(permBox);
        permBox.setVisibility(admin.isChecked() ? android.view.View.GONE : android.view.View.VISIBLE);
        admin.setOnCheckedChangeListener((b, x) -> permBox.setVisibility(x ? android.view.View.GONE : android.view.View.VISIBLE));
        final EditText p1 = pinField(firstAdmin ? "رمز المدير (6 إلى 8 أرقام)" : "الرمز السري (4 إلى 8 أرقام، والمدير 6)");
        final EditText p2 = pinField("أعد كتابة الرمز");
        final CheckBox active = new CheckBox(a);
        if (old == null) {
            l.addView(p1);
            l.addView(p2);
        } else {
            active.setText("الحساب نشط");
            active.setChecked(old.active);
            l.addView(active);
            l.addView(a.text("لتغيير رمز هذا المستخدم استخدم زر «تغيير الرمز».", 12, a.muted, false));
        }
        if (firstAdmin) l.addView(a.text("بعد الحفظ يظهر «رمز استرداد المدير» مرة واحدة: اكتبه على ورقة. هو الطريقة الوحيدة لاستعادة الدخول إن نُسي رمز المدير.", 12, a.muted, false));
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(l);
        final AlertDialog dlg = new AlertDialog.Builder(a).setView(sv)
                .setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) throw new Exception("اكتب اسم المستخدم");
                for (Model.User u : a.db.users(false)) {
                    if ((old == null || u.id != old.id) && u.name.trim().equalsIgnoreCase(n)) throw new Exception("يوجد مستخدم بنفس الاسم");
                }
                String r = admin.isChecked() ? Auth.ADMIN : Auth.CUSTOM;
                java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
                for (java.util.Map.Entry<Auth.Perm, CheckBox> e : boxes.entrySet()) if (e.getValue().isChecked()) chosen.add(e.getKey().name());
                if (review.isChecked() && boxes.get(Auth.Perm.TX_ADD).isChecked()) chosen.add(Auth.REVIEW);
                if (!admin.isChecked() && chosen.isEmpty()) throw new Exception("اختر صلاحية واحدة على الأقل");
                chosen.add(Auth.DETAILED);   // 12.0: اختيرت تفصيليًا
                chosen.add(Auth.DETAILED126);
                String perms = Auth.csv(chosen);
                if (old == null) {
                    String pin = p1.getText().toString();
                    if (!pinOk(r, pin)) throw new Exception(pinRule(r));
                    if (!pin.equals(p2.getText().toString())) throw new Exception("الرمزان غير متطابقين");
                    long id = a.db.addUser(n, r, perms, pin);
                    if (!nick.getText().toString().trim().isEmpty()) a.db.setNick(id, nick.getText().toString());
                    dlg.dismiss();
                    if (firstAdmin) {
                        Model.User u = a.db.user(id);
                        SmartApp.currentUser = u;
                        Db.currentUserName = n;
                        a.lastAuthAt = SystemClock.elapsedRealtime();
                        reauthAt = SystemClock.elapsedRealtime();
                        resetFails();
                        if (a.db.settingLong("lock_timeout", -1) < 0) a.db.setSetting("lock_timeout", "5");
                        String code = a.db.newRecoveryCode();
                        // 12.7: مسار واضح بعد التفعيل: رمز الاسترداد، ثم رسالة «ادخل باسمك»، ثم شاشة الدخول واسمك مكتوب فيها
                        a.db.setSettingQuiet("last_login_name", n);
                        showRecoveryCode(code, "تم تفعيل المستخدمين ✓. هذا رمز استرداد المدير:", () -> new AlertDialog.Builder(a).setTitle("تم تفعيل نظام المستخدمين ✓")
                                .setMessage("من الآن يدخل كل شخص باسمه ورمزه، وتُسجَّل كل حركة باسم من سجّلها.\n\n"
                                        + "الخطوة التالية: ادخل الآن باسمك «" + n + "» ورمزك الذي اخترته، ثم أضف حسابات الموظفين من «المزيد ← المستخدمون والصلاحيات». "
                                        + (SyncEngine.enabled(a.db) ? "تصل الحسابات إلى الأجهزة المتزامنة، ويدخل كل شخص بحسابه من جهازه. " : "")
                                        + "يُطلب الدخول من جديد بعد 5 دقائق خارج التطبيق (يمكن تغييرها من الإعدادات ← الأمان).")
                                .setCancelable(false)
                                .setPositiveButton("الدخول باسمي", (dd, w) -> {
                                    a.sessionEnd();
                                    SmartApp.currentUser = null;
                                    Db.currentUserName = "المستخدم المحلي";
                                    showLogin();
                                }).show());
                        return;
                    }
                    a.toast("تمت إضافة «" + n + "». أرسل له اسمه ورمزه ليدخل.");
                } else {
                    a.db.updateUser(old.id, n, r, perms, active.isChecked());
                    if (!nick.getText().toString().trim().equals(Model.nz(old.nick).trim())) a.db.setNick(old.id, nick.getText().toString());
                    dlg.dismiss();
                    Model.User me = SmartApp.currentUser;
                    if (me != null && me.id == old.id) {
                        if (!active.isChecked()) {
                            SmartApp.currentUser = null;
                            Db.currentUserName = "المستخدم المحلي";
                            a.afterUnlock();
                            return;
                        }
                        SmartApp.currentUser = a.db.user(old.id);
                        Db.currentUserName = n;
                        if (!a.can(Auth.Perm.USERS)) {
                            a.showHome();
                            return;
                        }
                    }
                    if (Auth.ADMIN.equals(r) && !Auth.ADMIN.equals(old.role)) {
                        a.toast("صار «" + n + "» مديرًا. غيّر رمزه إلى 6 أرقام على الأقل من «تغيير الرمز».");
                    } else a.toast("تم الحفظ");
                }
                showManager();
            } catch (Exception e) {
                a.error(e);
            }
        }));
        dlg.show();
    }

    private void newPinDialog(final Model.User u, String title, final boolean cancelable, final Runnable after) {
        newPinDialog(u, title, cancelable, after, null);
    }

    /** ownOld != null: المستخدم يغيّر رمزه بنفسه (يُتحقق من رمزه الحالي في Db). */
    private void newPinDialog(final Model.User u, String title, final boolean cancelable, final Runnable after, final String ownOld) {
        final EditText p1 = pinField(Auth.ADMIN.equals(u.role) ? "الرمز الجديد (6 إلى 8 أرقام)" : "الرمز الجديد (4 إلى 8 أرقام)");
        final EditText p2 = pinField("أعد كتابة الرمز");
        LinearLayout l = padded(p1, p2);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(title).setView(l).setPositiveButton("حفظ", null);
        b.setNegativeButton("إلغاء", null);
        final AlertDialog dlg = b.create();
        // 10.5 (AUDIT-035): إذن الاسترداد لا يبقى مفتوحًا إن أُغلقت النافذة دون حفظ
        dlg.setOnDismissListener(x -> Db.recoveryGrant = false);
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = p1.getText().toString();
            if (!pinOk(u.role, pin)) {
                p1.setError(pinRule(u.role));
                return;
            }
            if (!pin.equals(p2.getText().toString())) {
                p2.setError("غير متطابق");
                return;
            }
            try {
                if (ownOld != null) a.db.changeOwnPin(ownOld, pin);
                else a.db.setPin(u.id, pin);
                dlg.dismiss();
                a.toast("تم تغيير الرمز");
                if (after != null) after.run();
            } catch (Exception e) {
                a.error(e);
            }
        }));
        dlg.show();
    }

    /** يغيّر المستخدم الحالي رمزه بعد إدخال الرمز القديم. */
    /** 10.17: المستخدم يختار اسمه الظاهر لبقية المحاسبين (المدير يرى اسم الدخول دائمًا). */
    void changeOwnNick() {
        final Model.User me = SmartApp.currentUser;
        if (me == null) return;
        Model.User now = a.db.user(me.id);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText n = a.field("الاسم الظاهر (اتركه فارغًا لاسم الدخول)");
        n.setText(now == null ? "" : now.nick);
        l.addView(n, a.lp(-1, 50, 3));
        l.addView(a.text("يراه بقية المحاسبين بجانب حركاتك بدل اسم دخولك «" + me.name + "». المدير يرى اسم الدخول دائمًا.", 12, a.muted, false));
        new AlertDialog.Builder(a).setTitle("اسمي الظاهر").setView(l).setPositiveButton("حفظ", (d, w) -> {
            try {
                a.db.setNick(me.id, n.getText().toString());
                a.toast(n.getText().toString().trim().isEmpty() ? "يظهر اسم دخولك" : "حُفظ الاسم الظاهر ✓");
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    /** 10.17: المدير يكتب رسالة للمحاسبين (الكل أو واحد)، ويرى ما أرسله ويسحبه. */
    void adminMessages() {
        final List<Model.User> to = new java.util.ArrayList<>();
        for (Model.User u : a.db.users(true)) if (SmartApp.currentUser == null || u.id != SmartApp.currentUser.id) to.add(u);
        if (to.isEmpty()) {
            a.toast("لا مستخدمين آخرين بعد");
            return;
        }
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final String[] names = new String[to.size() + 1];
        names[0] = "كل المحاسبين";
        for (int i = 0; i < to.size(); i++) names[i + 1] = to.get(i).name;
        final int[] sel = {0};
        final android.widget.Button who = a.smallButton("إلى: كل المحاسبين", a.accent, null);
        who.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle("إلى مَن؟").setItems(names, (d, w) -> {
            sel[0] = w;
            who.setText("إلى: " + names[w]);
        }).show());
        l.addView(who);
        final EditText body = a.field("نص الرسالة (حتى 500 حرف)");
        body.setSingleLine(false);
        body.setMinLines(3);
        l.addView(body, a.lp(-1, -2, 4));
        l.addView(a.text("تظهر إشعارًا عند المحاسب وفي صفحته الرئيسية حتى يضغط «قرأتها»" + (SyncEngine.enabled(a.db) ? " (تصل لأجهزتهم مع المزامنة التالية)." : "."), 12, a.muted, false));
        final List<Db.AdminMsg> sent = a.db.adminMsgsSent(10);
        if (!sent.isEmpty()) {
            l.addView(a.text("آخر ما أرسلته (اضغط رسالة لسحبها):", 13, a.primaryInk, true));
            for (final Db.AdminMsg m : sent) {
                TextView t = a.text("• " + (m.toUser == null ? "للكل" : "إلى " + m.toName) + " — " + Model.date(m.at) + "\n" + m.body, 13, a.ink, false);
                t.setOnClickListener(v -> a.confirm("سحب الرسالة", "تختفي الرسالة عند من لم يقرأها بعد.", "سحب", () -> {
                    a.db.deleteAdminMsg(m.uuid);
                    a.toast("سُحبت الرسالة");
                    a.syncScreen.syncSoon();
                }));
                l.addView(t);
            }
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(l);
        new AlertDialog.Builder(a).setTitle("رسالة إلى المحاسبين").setView(sv).setPositiveButton("إرسال", (d, w) -> {
            try {
                a.db.sendAdminMsg(sel[0] == 0 ? null : to.get(sel[0] - 1).id, body.getText().toString());
                a.toast("أُرسلت الرسالة ✓");
                a.syncScreen.syncSoon();
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إغلاق", null).show();
    }

    void changeOwnPin() {
        final Model.User me = SmartApp.currentUser;
        if (me == null || !canTry()) return;
        final EditText old = pinField("الرمز الحالي");
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("تغيير رمزي السري").setView(padded(old))
                .setPositiveButton("متابعة", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (lockRemaining() > 0) {
                dlg.dismiss();
                canTry();
                return;
            }
            if (!a.db.verifyPin(me.id, old.getText().toString())) {
                old.setText("");
                int f = recordFail(me.name, me.id);
                if (Pin.lockoutMillis(f) > 0) dlg.dismiss();
                failMessage(old, f);
                return;
            }
            resetFails();
            dlg.dismiss();
            // 10.5 (AUDIT-027): يُحفظ عبر changeOwnPin الذي يتحقق من الرمز الحالي في طبقة البيانات، بلا صلاحية «المستخدمون»
            newPinDialog(me, "الرمز الجديد", true, null, old.getText().toString());
        }));
        dlg.show();
    }

    /** 10.8: حذف حساب مستخدم: يُغلق ويُزال من المكاتب، وكل ما سجّله يبقى كما هو باسمه «(حساب محذوف)». */
    private void deleteDialog(final Model.User u) {
        final EditText name = a.field("اكتب «" + u.name + "» للتأكيد");
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(6), a.dp(18), 0);
        l.addView(a.text("يُحذف حساب «" + u.name + "» نهائيًا: لا يستطيع الدخول بعد الآن من أي جهاز، ويُزال من فرز المكاتب.\n\n"
                + "لا يُحذف شيء مما سجّله: حركاته وتحققاته ومطابقاته تبقى كما هي، والأرصدة لا تتغير، ويظهر اسمه عليها «(حساب محذوف)».", 14, a.ink, false));
        l.addView(name, a.lp(-1, 50, 6));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("حذف حساب " + u.name).setView(l).setPositiveButton("حذف الحساب", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!name.getText().toString().trim().equals(u.name.trim())) {
                name.setError("الاسم غير مطابق");
                return;
            }
            try {
                a.db.deleteUser(u.id);
                dlg.dismiss();
                a.toast("حُذف حساب " + u.name + " — بقيت حركاته كما هي");
                showManager();
            } catch (Exception e) {
                a.error(e);
            }
        }));
        dlg.show();
    }

    /**
     * 10.5 (§7): مكاتب المحاسب. 10.7: المختارة أولًا، وبحث، وتنبيه للمكتب المشترك.
     * 10.8: لكل مكتب دور: «يسجّل» حركاته ومطابقاته، أو «يتحقق» منها.
     */
    private void officesDialog(final Model.User u) {
        final List<Model.Office> all = a.db.offices();
        final java.util.Map<Long, String> roles = new java.util.LinkedHashMap<>(a.db.officeRoles(u.id));
        final java.util.Map<Long, List<String>> others = a.db.officeHolders(u.id);
        final boolean canCheck = Auth.can(u, Auth.Perm.VERIFY);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(16), a.dp(6), a.dp(16), 0);
        box.addView(a.text("«يسجّل»: يُدخل حركات المكتب ومطابقاته ويراه في «مكاتبي». «يتحقق»: يراجع ما سجّله غيره ويؤكد سلامته. لا يجمع الدورين في مكتب واحد.", 13, a.muted, false));
        final EditText q = a.field("ابحث عن مكتب");
        box.addView(q, a.lp(-1, 48, 4));
        final TextView count = a.text("", 13, a.primaryInk, true);
        box.addView(count);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(-1, a.dp(400)));
        final int gray = Color.rgb(120, 120, 120);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            String f = OwnerReports.norm(q.getText().toString());
            // المختارة أولًا (آخر اختيار في الأعلى)، ثم الباقي
            List<Model.Office> order = new ArrayList<>();
            for (Long id : roles.keySet()) for (Model.Office o : all) if (o.id == id) order.add(o);
            for (Model.Office o : all) if (!roles.containsKey(o.id)) order.add(o);
            int own = 0, chk = 0;
            for (String r : roles.values()) if (Db.ROLE_CHECK.equals(r)) chk++;
            else own++;
            for (final Model.Office o : order) {
                if (!f.isEmpty() && !OwnerReports.norm(o.name).contains(f)) continue;
                String role = roles.get(o.id);
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4));
                if (role != null) row.setBackground(a.round(a.primarySoft, a.primarySoft, 10));
                row.addView(a.text(o.name + (o.active ? "" : " (موقوف)"), 15, a.ink, role != null));
                final Ui.Seg s = a.new Seg(new String[]{"—", "يسجّل", "يتحقق"}, new int[]{gray, a.green, a.primary},
                        role == null ? 0 : Db.ROLE_CHECK.equals(role) ? 2 : 1, false);
                for (android.widget.Button b : s.buttons) b.setTextSize(13);
                s.listener = i -> {
                    if (i == 2 && !canCheck) {
                        a.toast("«" + u.name + "» ليست لديه صلاحية «التحقق من الحركات»");
                        list.post(render[0]);
                        return;
                    }
                    if (i == 0) roles.remove(o.id);
                    else {
                        // المختار يرتفع إلى أول القائمة
                        java.util.Map<Long, String> n = new java.util.LinkedHashMap<>();
                        n.put(o.id, i == 2 ? Db.ROLE_CHECK : Db.ROLE_OWN);
                        for (java.util.Map.Entry<Long, String> e : roles.entrySet()) if (e.getKey() != o.id) n.put(e.getKey(), e.getValue());
                        roles.clear();
                        roles.putAll(n);
                        List<String> h = others.get(o.id);
                        if (i == 1 && h != null && !h.isEmpty()) a.toast("تنبيه: «" + o.name + "» يسجّله " + Model.join(h, "، ") + " أيضًا");
                    }
                    list.post(render[0]);
                };
                row.addView(s.view);
                List<String> h = others.get(o.id);
                if (h != null && !h.isEmpty())
                    row.addView(a.text("⚠ يسجّله أيضًا: " + Model.join(h, "، "), 12, Db.ROLE_OWN.equals(role) ? a.red : a.warnInk, Db.ROLE_OWN.equals(role)));
                list.addView(row, a.lp(-1, -2, 2));
            }
            count.setText("يسجّل " + own + " مكتب • يتحقق من " + chk);
        };
        q.addTextChangedListener(Ui.watcher(render[0]));
        render[0].run();
        new AlertDialog.Builder(a).setTitle("مكاتب " + u.name).setView(box).setPositiveButton("حفظ", (d, w) -> {
            List<String> shared = new ArrayList<>();
            for (java.util.Map.Entry<Long, String> e : roles.entrySet()) {
                if (!Db.ROLE_OWN.equals(e.getValue())) continue;
                List<String> h = others.get(e.getKey());
                Model.Office o = a.db.office(e.getKey());
                if (h != null && !h.isEmpty() && o != null) shared.add("• «" + o.name + "» يسجّله أيضًا: " + Model.join(h, "، "));
            }
            final Runnable save = () -> {
                try {
                    a.db.setUserOffices(u.id, new java.util.LinkedHashMap<>(roles));
                    a.toast("حُفظت مكاتب " + u.name);
                    showManager();
                } catch (Exception e) {
                    a.error(e);
                }
            };
            if (shared.isEmpty()) save.run();
            else new AlertDialog.Builder(a).setTitle("مكاتب يسجّلها محاسب آخر أيضًا")
                    .setMessage(Model.join(shared, "\n") + "\n\nيجوز ذلك، لكن الأوضح أن يسجّل كل مكتبَ محاسبٌ واحد ويتحقق منه غيره. تأكد أن هذا مقصود.")
                    .setPositiveButton("حفظ رغم ذلك", (x, y) -> save.run()).setNegativeButton("رجوع", null).show();
        }).setNegativeButton("إلغاء", null).show();
    }
}
