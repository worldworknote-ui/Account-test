package com.smart.hawala;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.View;

/**
 * 10.3: قفل التطبيق على هذا الجهاز — لكل مستخدم، دون صلاحية «الإعدادات» (لا يمنح أي صلاحية أخرى):
 * <ul>
 *   <li>«بصمة»: البصمة أو قفل شاشة الهاتف.</li>
 *   <li>«رمز»: كلمة سر خاصة بالتطبيق — للهاتف الذي لا بصمة فيه ولا قفل شاشة، أو لمن لا يريد البصمة.</li>
 * </ul>
 * إن اختير قفل البصمة والهاتف بلا بصمة ولا قفل شاشة، يطلب التطبيق كلمة سر له بدل أن يُفتح دون حماية.
 * الإعدادات خاصة بهذا الجهاز (لا تُزامَن ولا تنتقل مع النسخة).
 */
final class AppLock {
    static final String OFF = "off", BIO = "bio", PIN = "pin";
    private final MainActivity a;

    AppLock(MainActivity a) { this.a = a; }

    String mode() {
        String m = a.db.setting("app_lock", "");
        if (m.isEmpty()) m = "1".equals(a.db.setting("biometric", "0")) ? BIO : OFF;   // ترحيل إعداد 10.2 وما قبله
        if (PIN.equals(m) && !hasPin()) return OFF;
        return m;
    }

    boolean on() { return !OFF.equals(mode()); }

    boolean hasPin() { return !a.db.setting("app_pin_hash", "").isEmpty(); }

    /** هل في الهاتف بصمة أو قفل شاشة (رمز/نمط/كلمة سر)؟ */
    boolean deviceSecure() {
        try {
            KeyguardManager k = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
            return k != null && k.isDeviceSecure();
        } catch (Throwable t) {
            return true;
        }
    }

    void setMode(String m) {
        a.db.setSettingQuiet("app_lock", m);
        a.db.setSettingQuiet("biometric", BIO.equals(m) ? "1" : "0");
    }

    // ===================================================================================
    // شاشة القفل
    // ===================================================================================

    /** يقفل التطبيق ويطلب الفتح بحسب الطريقة. */
    void lockScreen() {
        a.unlocked = false;
        String m = mode();
        if (BIO.equals(m) && !deviceSecure()) {
            // الهاتف بلا بصمة ولا قفل شاشة: لا يُفتح دون حماية
            if (hasPin()) pinScreen("هذا الهاتف بلا بصمة ولا قفل شاشة الآن، فيُفتح التطبيق بكلمة سره.");
            else needPin();
            return;
        }
        if (PIN.equals(m)) {
            pinScreen(null);
            return;
        }
        a.showLock();
        a.authenticate();
    }

    /** بعد فشل البصمة لأن الهاتف لا يملكها (11/12/14). */
    void noBiometric() {
        if (hasPin()) pinScreen("لا توجد بصمة ولا قفل شاشة على هذا الهاتف: افتح بكلمة سر التطبيق.");
        else needPin();
    }

    /**
     * 10.11 (AUDIT-015): مهلة المحاولات الخاطئة بزمن تشغيل الهاتف (كدخول المستخدمين) لا بساعته، فتقديم الساعة لا ينهيها.
     * إن أُعيد تشغيل الهاتف تبدأ المهلة كاملة من جديد.
     */
    private long lockLeft() {
        long ms = a.db.settingLong("app_lock_ms", 0);
        if (ms <= 0) return 0;
        long now = SystemClock.elapsedRealtime(), start = a.db.settingLong("app_lock_rt", 0);
        if (now < start) {
            start = now;
            a.db.setSettingQuiet("app_lock_rt", "" + start);
        }
        return Math.max(0, ms - (now - start));
    }

    private void opened() {
        a.db.setSettingQuiet("app_fails", "0");
        a.db.deleteSetting("app_lock_ms");
        a.unlocked = true;
        a.lastAuthAt = SystemClock.elapsedRealtime();
        a.afterUnlock();
    }

    private void header(String msg) {
        a.returning = true;   // 11.5: بلا حركة دخول
        a.base("التطبيق مقفل", null);
        Bitmap bm = a.homeLogo();
        ImageView logo = new ImageView(a);
        if (bm != null) logo.setImageBitmap(bm);
        else logo.setImageResource(R.drawable.ic_logo);
        a.body.addView(logo, a.lp(-1, 100, 16));
        TextView t = a.text(msg, 16, a.dark, false);
        t.setGravity(Gravity.CENTER);
        a.add(t);
    }

    void pinScreen(String note) {
        header(note != null ? note : "اكتب كلمة سر التطبيق للمتابعة.");
        final EditText p = a.field("كلمة سر التطبيق");
        Ui.secret(p, false);
        p.setGravity(Gravity.CENTER);
        a.body.addView(p, a.lp(-1, 56, 10));
        a.button("فتح التطبيق", a.primary, v -> {
            long left = lockLeft();
            if (left > 0) {
                a.toast("محاولات خاطئة كثيرة. انتظر " + UsersScreen.waitText(left) + ".");
                return;
            }
            String s = p.getText().toString();
            p.setText("");
            if (Pin.verify(s, a.db.setting("app_pin_salt", ""), a.db.setting("app_pin_hash", ""))) opened();
            else {
                int f = (int) a.db.settingLong("app_fails", 0) + 1;
                a.db.setSettingQuiet("app_fails", "" + f);
                long w = Pin.lockoutMillis(f);
                if (w > 0) {
                    a.db.setSettingQuiet("app_lock_rt", "" + SystemClock.elapsedRealtime());
                    a.db.setSettingQuiet("app_lock_ms", "" + w);
                    a.toast("كلمة السر غير صحيحة. انتظر " + UsersScreen.waitText(w) + ".");
                } else p.setError("كلمة السر غير صحيحة");
            }
        });
        if (BIO.equals(mode()) && deviceSecure()) a.button("الفتح بالبصمة", a.accent, v -> a.authenticate());
        TextView h = a.text("نسيت كلمة سر التطبيق؟ احذف بيانات التطبيق وأعد الاستعادة من نسخة احتياطية، أو استعمل المزامنة للانضمام من جديد.", 12, a.muted, false);
        h.setGravity(Gravity.CENTER);
        a.add(h);
    }

    /** القفل مطلوب والهاتف بلا بصمة ولا كلمة سر للتطبيق: يُطلب وضع كلمة سر الآن. */
    private void needPin() {
        header("هذا الهاتف بلا بصمة ولا قفل شاشة، والتطبيق مضبوط على القفل. ضع كلمة سر للتطبيق ليبقى محميًا.");
        a.button("وضع كلمة سر للتطبيق", a.primary, v -> newPin(() -> {
            setMode(PIN);
            opened();
        }));
        a.button("فتح دون قفل (إيقاف القفل على هذا الهاتف)", a.red, v -> a.confirm("فتح دون قفل",
                "سيعمل التطبيق على هذا الهاتف دون أي قفل، ويستطيع من يحمل الهاتف رؤية الحسابات. يمكنك وضع كلمة سر لاحقًا من «المزيد ← قفل التطبيق».",
                "فتح دون قفل", () -> {
                    setMode(OFF);
                    opened();
                }));
    }

    /** كلمة سر جديدة للتطبيق (4 محارف على الأقل، مرتين). */
    void newPin(final Runnable after) {
        final EditText p1 = a.field("كلمة السر (4 محارف على الأقل)"), p2 = a.field("أعد كتابتها");
        Ui.secret(p1, false);
        Ui.secret(p2, false);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(p1, a.lp(-1, 52, 2));
        l.addView(p2, a.lp(-1, 52, 2));
        l.addView(a.text("تُطلب عند فتح التطبيق على هذا الجهاز. لا تُرسل لأي مكان.", 12, a.muted, false));
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("كلمة سر التطبيق").setView(l).setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String s1 = p1.getText().toString();
            if (s1.length() < 4) {
                p1.setError("4 محارف على الأقل");
                return;
            }
            if (!s1.equals(p2.getText().toString())) {
                p2.setError("غير متطابقة");
                return;
            }
            String salt = Pin.newSalt();
            a.db.setSettingQuiet("app_pin_salt", salt);
            a.db.setSettingQuiet("app_pin_hash", Pin.hash(s1, salt));
            a.db.setSettingQuiet("app_fails", "0");
            d.dismiss();
            after.run();
        }));
        d.show();
    }

    // ===================================================================================
    // الإعداد (لكل مستخدم، بلا صلاحية الإعدادات)
    // ===================================================================================

    void settings() {
        a.here(this::settings);
        a.tabbedScreen("قفل التطبيق على هذا الجهاز", a.moreScreen::show, "more");
        final String m = mode();
        final boolean secure = deviceSecure();
        a.hint("يحمي التطبيق إن وصل هاتفك إلى يد غيرك. خاص بهذا الجهاز، ولا يغيّر أي صلاحية.");
        LinearLayout g = a.group();
        a.groupRow(g, R.drawable.ic_lock, BIO.equals(m) ? a.green : a.primaryInk, "البصمة أو قفل شاشة الهاتف",
                secure ? "الأسرع: يُفتح ببصمتك أو برمز قفل الهاتف" : "هذا الهاتف بلا بصمة ولا قفل شاشة: اضبطهما من إعدادات الهاتف، أو اختر كلمة سر للتطبيق",
                BIO.equals(m) ? "✓" : secure ? "اختيار" : null, secure ? v -> {
                    setMode(BIO);
                    a.unlocked = true;
                    a.toast("القفل بالبصمة ✓");
                    settings();
                } : null);
        a.groupRow(g, R.drawable.ic_key, PIN.equals(m) ? a.green : a.primaryInk, "كلمة سر خاصة بالتطبيق",
                hasPin() ? "مضبوطة — تُطلب عند الفتح" : "لمن لا يريد البصمة أو هاتفه بلا بصمة", PIN.equals(m) ? "✓" : "اختيار", v -> newPin(() -> {
                    setMode(PIN);
                    a.toast("قُفل التطبيق بكلمة سر ✓");
                    settings();
                }));
        if (hasPin() && !PIN.equals(m)) a.groupRow(g, R.drawable.ic_key, a.primaryInk, "كلمة السر احتياط للبصمة", "تُطلب إن تعذرت البصمة على هذا الهاتف", null, null);
        a.groupRow(g, R.drawable.ic_close, OFF.equals(m) ? a.red : a.muted, "بلا قفل", "أي شخص يفتح الهاتف يرى الحسابات", OFF.equals(m) ? "✓" : "اختيار", v -> a.confirm("بلا قفل",
                "يعمل التطبيق على هذا الجهاز دون قفل. متأكد؟", "بلا قفل", () -> {
                    setMode(OFF);
                    settings();
                }));

        a.section("متى يُطلب القفل؟", null, null);
        final String[] lockLabels = {"عند فتح التطبيق فقط", "فورًا عند كل عودة", "بعد دقيقة", "بعد 5 دقائق", "بعد 15 دقيقة"};
        final long[] lockValues = {-1, 0, 1, 5, 15};
        final Spinner lock = a.spinner(lockLabels);
        long cur = a.db.settingLong("lock_timeout", -1);
        for (int i = 0; i < lockValues.length; i++) if (lockValues[i] == cur) lock.setSelection(i);
        lock.setBackground(a.round(a.surface, a.line, 12));
        a.body.addView(lock, a.lp(-1, 54, 4));
        lock.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (lockValues[pos] != a.db.settingLong("lock_timeout", -1)) a.db.setSettingQuiet("lock_timeout", "" + lockValues[pos]);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        if (a.db.usersEnabled()) {
            a.hint(a.db.rememberedUser() != null
                    ? "حسابك محفوظ على هذا الجهاز («تذكرني»): القفل هنا هو ما يمنع غيرك من الدخول بحسابك."
                    : "نظام المستخدمين مفعّل: يُطلب اسمك ورمزك عند الفتح أيضًا (أو فعّل «تذكرني» عند الدخول واعتمد على هذا القفل).");
        }
    }

    static String label(MainActivity a) {
        String m = a.appLock.mode();
        return BIO.equals(m) ? "بالبصمة" : PIN.equals(m) ? "بكلمة سر التطبيق" : "بلا قفل";
    }
}
