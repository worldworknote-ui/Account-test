package com.smart.hawala;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 9.0: «صندوق المكتب» — قسم بشكل وألوان خاصة (كأنه برنامج مستقل) لضبط النقد الموجود لدى صاحب المكتب بكل عملة.
 * 9.0.1: تبويب خاص في الشريط السفلي، وقفل بالبصمة أو بكلمة سر، وعدة صناديق والتحويل بينها، وبيع وشراء العملات بالسعر
 * والمقابل المحسوب، وتفاصيل كاملة قبل كل تسجيل، وسجل للجرد يقارن زيادة عملة بعجز أخرى، وحسابات العملاء (ClientScreen).
 * الحساب كله في CashBox وClientBook (مشتركة)، والكتابة في Db التي تسجّل الحركات المرتبطة في المعاملة نفسها.
 */
final class CashScreen {
    final MainActivity a;
    final ClientScreen clients;
    private int filter = 0;
    static final String[] FILTERS = {"الكل", "اليوم", "استلام", "تسليم", "مصاريف", "بيع وشراء", "عملاء", "لحساب مكاتب", "تحويل", "الملغاة"};

    CashScreen(MainActivity a) {
        this.a = a;
        this.clients = new ClientScreen(this);
    }

    // ===================================================================================
    // الألوان الخاصة بالصندوق
    // ===================================================================================

    static final int NAVY = Color.rgb(22, 42, 74), NAVY2 = Color.rgb(12, 27, 52), GOLD = Color.rgb(201, 162, 39);

    void enter() {
        a.applyColors();
        if (!a.night) {
            a.primary = Color.rgb(30, 64, 110);
            a.bg = Color.rgb(237, 241, 247);
            a.surface = Color.WHITE;
            a.line = Color.rgb(214, 222, 234);
            a.primarySoft = Color.rgb(226, 234, 246);
            a.primaryInk = Color.rgb(22, 50, 92);
            a.accent = GOLD;
            a.goldSoft = Color.rgb(250, 241, 210);
            a.goldInk = Color.rgb(110, 82, 6);
        } else {
            a.primary = Color.rgb(74, 118, 178);
            a.bg = Color.rgb(13, 19, 31);
            a.surface = Color.rgb(22, 31, 48);
            a.line = Color.rgb(38, 51, 74);
            a.primarySoft = Color.rgb(28, 42, 66);
            a.primaryInk = Color.rgb(150, 186, 236);
            a.accent = Color.rgb(214, 178, 72);
            a.goldSoft = Color.rgb(56, 46, 18);
            a.goldInk = Color.rgb(236, 205, 120);
        }
        a.header = a.night ? Color.rgb(9, 15, 27) : NAVY;
        a.headerInk = Color.WHITE;
        a.flatHeader = false;
        a.themeOwner = "cash";
        a.themeKeep = true;
    }

    void screen(String title, Runnable back) {
        enter();
        a.base(title, back);
    }

    /** شاشة رئيسية في تبويب الصندوق (مع الشريط السفلي). */
    void tabbed(String title, Runnable back) {
        enter();
        a.tabbedScreen(title, back, "cash");
    }

    static int dec(Map<String, Integer> m, String c) {
        Integer v = m.get(c);
        return v == null ? 2 : v;
    }

    /** مبلغ معزول الاتجاه داخل النص العربي: «1,250.00 USD». */
    String money(long v, String cur) { return "\u2066" + Model.fmt(v, a.db.decimals(cur)) + " " + cur + "\u2069"; }

    static String iso(String s) { return "\u2066" + s + "\u2069"; }

    // ===================================================================================
    // 9.0.1: قفل الصندوق (بصمة أو قفل الشاشة، أو كلمة سر خاصة بالصندوق)
    // ===================================================================================

    static final String LOCK_OFF = "0", LOCK_BIO = "bio", LOCK_PIN = "pin";
    /** فُتح الصندوق في هذه الزيارة؛ يُقفل عند مغادرة شاشاته أو بعد خروج من التطبيق أطول من المهلة. */
    private boolean open;
    private long authAt;
    static final long GRACE_MS = 30000;

    String lockMode() {
        String m = a.db.setting("cash_lock", LOCK_OFF);
        if (LOCK_PIN.equals(m) && a.db.setting("cash_pin_hash", "").isEmpty()) return LOCK_OFF;
        return m;
    }

    void leave() { open = false; }

    void onResume(long pausedAt) {
        if (!open || LOCK_OFF.equals(lockMode()) || pausedAt == 0 || pausedAt <= authAt) return;
        if (SystemClock.elapsedRealtime() - pausedAt < GRACE_MS) return;
        open = false;
        if ("cash".equals(a.themeOwner)) show();
    }

    /** يشغّل r إن كان الصندوق مفتوحًا أو بلا قفل، وإلا يطلب البصمة أو كلمة السر أولًا. */
    void guard(final Runnable r) {
        if (open || LOCK_OFF.equals(lockMode())) {
            open = true;
            r.run();
            return;
        }
        tabbed("الصندوق مقفل", null);
        LinearLayout c = a.card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(a.icon(R.drawable.ic_lock, a.primaryInk, 48), new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
        TextView t = a.text(LOCK_BIO.equals(lockMode()) ? "افتح الصندوق بالبصمة أو بقفل الشاشة." : "اكتب كلمة سر الصندوق لفتحه.", 16, a.ink, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        TextView s = a.text("الصندوق محمي بقفل خاص لأن فيه النقد وحسابات العملاء.", 13, a.muted, false);
        s.setGravity(Gravity.CENTER);
        c.addView(s);
        final Runnable ask = () -> unlock(() -> {
            open = true;
            authAt = SystemClock.elapsedRealtime();
            r.run();
        });
        c.addView(a.makeButton("فتح الصندوق", a.primary, v -> ask.run()), a.lp(-1, 52, 8));
        ask.run();
    }

    private void unlock(final Runnable ok) {
        if (LOCK_BIO.equals(lockMode())) bio("فتح الصندوق", ok);
        else pinDialog(ok);
    }

    /** البصمة أو قفل الشاشة. لا قفل مهيّأ في الهاتف أصلًا (11/12/14): يُفتح مع تنبيه، كقفل التطبيق. */
    void bio(String title, final Runnable ok) {
        try {
            android.hardware.biometrics.BiometricPrompt.Builder b = new android.hardware.biometrics.BiometricPrompt.Builder(a)
                    .setTitle(title).setSubtitle("بالبصمة أو بقفل الشاشة");
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            else b.setNegativeButton("إلغاء", a.getMainExecutor(), (d, w) -> { });
            // شاشة رمز القفل تُوقف النشاط مؤقتًا: لا يُعدّ ذلك خروجًا من التطبيق (فلا يُقفل التطبيق نفسه بعدها)
            a.markOwn();
            final Runnable clear = () -> a.getWindow().getDecorView().postDelayed(() -> a.ownActivity = false, 1500);
            b.build().authenticate(new android.os.CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            clear.run();
                            ok.run();
                        }

                        @Override
                        public void onAuthenticationError(int c, CharSequence m) {
                            clear.run();
                            if (c == 11 || c == 12 || c == 14) {
                                a.toast("لا توجد بصمة ولا قفل شاشة على هذا الهاتف: الصندوق غير محمي. اضبط قفل الشاشة أو اختر كلمة سر للصندوق.");
                                ok.run();
                            } else if (m != null && c != 5 && c != 10 && c != 13) a.toast(m.toString());
                        }
                    });
        } catch (Throwable e) {
            a.ownActivity = false;
            a.toast("تعذر فتح شاشة التحقق؛ اضغط «فتح الصندوق» للمحاولة من جديد.");
        }
    }

    private void pinDialog(final Runnable ok) {
        long until = a.db.settingLong("cash_locked_until", 0);
        if (until > Clock.now()) {
            a.toast("محاولات خاطئة كثيرة. حاول بعد " + Math.max(1, (until - Clock.now()) / 1000) + " ثانية.");
            return;
        }
        final EditText e = a.field("كلمة سر الصندوق");
        Ui.secret(e, false);
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(e, new LinearLayout.LayoutParams(-1, a.dp(52)));
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("فتح الصندوق").setView(l).setPositiveButton("فتح", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (Pin.verify(e.getText().toString(), a.db.setting("cash_pin_salt", ""), a.db.setting("cash_pin_hash", ""))) {
                a.db.setSettingQuiet("cash_fails", "0");
                d.dismiss();
                ok.run();
            } else {
                int fails = (int) a.db.settingLong("cash_fails", 0) + 1;
                a.db.setSettingQuiet("cash_fails", "" + fails);
                long wait = Pin.lockoutMillis(fails);
                if (wait > 0) {
                    a.db.setSettingQuiet("cash_locked_until", "" + (Clock.now() + wait));
                    d.dismiss();
                    a.toast("محاولات خاطئة كثيرة. انتظر " + wait / 1000 + " ثانية.");
                } else {
                    e.setText("");
                    e.setError("كلمة السر غير صحيحة");
                }
            }
        }));
        d.show();
    }

    /** ضبط كلمة سر جديدة للصندوق (4 محارف على الأقل، تُكتب مرتين). */
    private void newPin(final Runnable after) {
        final EditText p1 = a.field("كلمة السر (4 محارف على الأقل)"), p2 = a.field("أعد كتابتها");
        Ui.secret(p1, false);
        Ui.secret(p2, false);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(p1, a.lp(-1, 52, 2));
        l.addView(p2, a.lp(-1, 52, 2));
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("كلمة سر الصندوق").setView(l).setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create();
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
            a.db.setSettingQuiet("cash_pin_salt", salt);
            a.db.setSettingQuiet("cash_pin_hash", Pin.hash(s1, salt));
            a.db.setSettingQuiet("cash_lock", LOCK_PIN);
            a.db.setSettingQuiet("cash_fails", "0");
            d.dismiss();
            a.toast("قُفل الصندوق بكلمة سر ✓");
            after.run();
        }));
        d.show();
    }

    // ===================================================================================
    // الصفحة الرئيسية للصندوق
    // ===================================================================================

    void show() {
        if (!a.require(Auth.Perm.CASH)) return;
        CashBox.Config cfg = a.db.cashConfig();
        if (!cfg.on) {
            setup(true);
            return;
        }
        guard(this::home);
    }

    private List<CashBox.Config> activeBoxes() {
        List<CashBox.Config> out = new ArrayList<>();
        for (CashBox.Config c : a.db.cashBoxes()) if (c.active) out.add(c);
        return out;
    }

    void home() {
        final CashBox.Config cfg = a.db.cashConfig();
        tabbed(cfg.name, null);
        a.eyeAction(this::home);
        if (a.can(Auth.Perm.SETTINGS)) a.headerAction(R.drawable.ic_sliders, "إعدادات الصندوق", v -> setup(false));
        a.headerAction(R.drawable.ic_report, "تقرير الصندوق", v -> reportFor(a.reportsScreen.range()[0], a.reportsScreen.range()[1], this::home));

        // الصناديق: التنقل بينها وإضافة صندوق
        final List<CashBox.Config> boxes = activeBoxes();
        if (boxes.size() > 1 || a.can(Auth.Perm.SETTINGS)) {
            LinearLayout chips = a.row();
            for (final CashBox.Config b : boxes)
                chips.addView(a.pill(b.name, b.id == cfg.id, v -> {
                    a.db.setCurrentBox(b.id);
                    home();
                }), a.gap(-2, -2, 3));
            if (a.can(Auth.Perm.SETTINGS)) chips.addView(a.pill("+ صندوق", false, v -> boxForm(null)), a.gap(-2, -2, 3));
            a.body.addView(a.strip(chips), a.lp(-1, -2, 2));
        }

        final List<CashBox.Move> moves = a.db.cashMoves(cfg.id);
        Map<String, Long> bal = CashBox.balances(moves, cfg.currencies);
        Map<String, Integer> dec = a.db.decimalsMap();
        hero(cfg, bal, dec);

        Map<String, long[]> today = CashBox.todayFlow(moves, Clock.now());
        if (!today.isEmpty()) {
            LinearLayout t = a.card();
            t.addView(a.text("اليوم", 14, a.ink, true));
            for (Map.Entry<String, long[]> e : today.entrySet()) {
                int d = dec(dec, e.getKey());
                t.addView(a.text(iso(e.getKey()) + "   داخل " + a.m(iso(Model.fmt(e.getValue()[0], d))) + "   •   خارج " + a.m(iso(Model.fmt(e.getValue()[1], d))), 13, a.dark, false));
            }
        }
        if (cfg.strict) a.notice(R.drawable.ic_shield, "هذا الصندوق مربوط بالمكاتب: الاستلام والتسليم لحساب مكتب أو عميل فقط" + (cfg.allowInternal ? " (المصاريف والبيع والشراء مسموحة)." : "."), false, null, null);
        actions(cfg, boxes.size() > 1);
        clients.homeSummary();
        list(moves, dec);
    }

    private void hero(CashBox.Config cfg, Map<String, Long> bal, Map<String, Integer> dec) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(16));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, a.night ? new int[]{Color.rgb(28, 44, 72), Color.rgb(14, 22, 40)} : new int[]{NAVY, NAVY2});
        g.setCornerRadius(a.dp(22));
        card.setBackground(g);
        LinearLayout top = a.row();
        top.addView(plain("النقد في الصندوق", 13, Color.rgb(196, 210, 232), false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = plain(cfg.strict ? "مربوط بالمكاتب" : "حر", 11, Color.rgb(42, 30, 0), true);
        badge.setPadding(a.dp(10), a.dp(2), a.dp(10), a.dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GOLD);
        bg.setCornerRadius(a.dp(50));
        badge.setBackground(bg);
        top.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        card.addView(top);
        Ledger.Valuation v = Ledger.valueOffice(bal, a.db.usdRates(), dec);
        BigDecimal net = v.netUsd();
        TextView total = plain("≈ " + a.m(iso(Model.fmt(net.setScale(0, RoundingMode.HALF_EVEN).longValue(), 0) + " USD")), 26, Color.WHITE, true);
        total.setPadding(0, a.dp(4), 0, a.dp(2));
        card.addView(total);
        card.addView(plain("مجموع الصندوق مقوّمًا بالدولار حسب أسعار الصرف" + (v.missingRates.isEmpty() ? "" : " (بلا سعر: " + Model.join(new ArrayList<>(v.missingRates), "، ") + ")"),
                11, Color.rgb(170, 186, 212), false));
        View div = new View(a);
        div.setBackgroundColor(Color.argb(50, 255, 255, 255));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(1)));
        dp.setMargins(0, a.dp(10), 0, a.dp(6));
        card.addView(div, dp);
        Map<String, String> names = a.db.currencyNames();
        for (Map.Entry<String, Long> e : bal.entrySet()) {
            LinearLayout r = a.row();
            r.setPadding(0, a.dp(3), 0, a.dp(3));
            String nm = names.get(e.getKey());
            r.addView(plain(nm == null || nm.isEmpty() ? e.getKey() : nm + " · " + e.getKey(), 14, Color.rgb(214, 224, 240), false), new LinearLayout.LayoutParams(0, -2, 1));
            long b = e.getValue();
            TextView amt = plain(a.amountsHidden() ? "••••" : (b < 0 ? "عجز " : "") + Model.fmt(Math.abs(b), dec(dec, e.getKey())), 20, b < 0 ? Color.rgb(255, 170, 170) : Color.WHITE, true);
            amt.setTextDirection(View.TEXT_DIRECTION_LTR);
            r.addView(amt, new LinearLayout.LayoutParams(-2, -2));
            card.addView(r);
        }
        List<CashBox.Count> counts = a.db.cashCounts(cfg.id);
        long lc = counts.isEmpty() ? 0 : counts.get(0).createdAt;
        card.addView(plain(lc > 0 ? "آخر جرد: " + MainActivity.ago(lc) : "لم يُجرد الصندوق بعد — الجرد يطابق المسجّل بالمعدود فعلًا", 11, Color.rgb(170, 186, 212), false));
        a.body.addView(card, a.lp(-1, -2, 6));
    }

    TextView plain(String s, int size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (bold) Ui.bold(t);
        return t;
    }

    View tile(int icon, String label, String sub, int color, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(a.dp(6), a.dp(14), a.dp(6), a.dp(12));
        b.setBackground(a.ripple(a.round(a.surface, Ui.mix(a.line, color, 0.45f), 18), 18));
        LinearLayout ic = new LinearLayout(a);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Ui.mix(a.surface, color, a.night ? 0.35f : 0.14f));
        cg.setCornerRadius(a.dp(14));
        ic.setBackground(cg);
        ic.addView(a.icon(icon, color, 24), new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
        b.addView(ic, new LinearLayout.LayoutParams(a.dp(46), a.dp(46)));
        TextView t = a.text(label, 14, a.ink, true);
        t.setGravity(Gravity.CENTER);
        b.addView(t, new LinearLayout.LayoutParams(-2, -2));
        if (sub != null) {
            TextView s = a.text(sub, 11, a.muted, false);
            s.setGravity(Gravity.CENTER);
            b.addView(s, new LinearLayout.LayoutParams(-2, -2));
        }
        b.setOnClickListener(l);
        return b;
    }

    int green() { return a.night ? Color.rgb(92, 203, 163) : Color.rgb(27, 120, 70); }

    int red() { return a.night ? Color.rgb(240, 138, 143) : Color.rgb(173, 46, 54); }

    int amber() { return a.night ? Color.rgb(233, 196, 120) : Color.rgb(176, 120, 10); }

    int gray() { return a.night ? Color.rgb(160, 170, 185) : Color.rgb(90, 100, 115); }

    int teal() { return a.night ? Color.rgb(110, 200, 210) : Color.rgb(18, 110, 120); }

    private void actions(CashBox.Config cfg, boolean manyBoxes) {
        boolean can = a.can(Auth.Perm.CASH);
        boolean internal = !cfg.strict || cfg.allowInternal;
        List<View> tiles = new ArrayList<>();
        if (can) {
            tiles.add(tile(R.drawable.ic_download, "استلام", "يدخل الصندوق", green(), v -> form(CashBox.IN, null, null)));
            tiles.add(tile(R.drawable.ic_upload, "تسليم", "يخرج من الصندوق", red(), v -> form(CashBox.OUT, null, null)));
            if (internal && a.can(Auth.Perm.CASH_EXPENSE)) tiles.add(tile(R.drawable.ic_tag, "مصروف", "رواتب، إيجار…", amber(), v -> expense()));
            if (internal && a.can(Auth.Perm.CASH_FX)) tiles.add(tile(R.drawable.ic_coins, "بيع", "عملة تخرج ومقابلها يدخل", a.primary, v -> trade(CashBox.SELL)));
            if (internal && a.can(Auth.Perm.CASH_FX)) tiles.add(tile(R.drawable.ic_coins, "شراء", "عملة تدخل ومقابلها يخرج", teal(), v -> trade(CashBox.BUY)));
            if (a.can(Auth.Perm.CASH_COUNT)) tiles.add(tile(R.drawable.ic_check2, "جرد", "طابق المعدود", gray(), v -> count()));
        }
        if (clients.allowed()) tiles.add(tile(R.drawable.ic_users, "العملاء", "حساباتهم بالتفصيل", a.primary, v -> clients.list()));
        if (can && manyBoxes) tiles.add(tile(R.drawable.ic_transfer, "تحويل", "بين الصناديق", teal(), v -> transfer()));
        tiles.add(tile(R.drawable.ic_list, "سجل الجرد", "العجز والزيادة", gray(), v -> countLog()));
        tiles.add(tile(R.drawable.ic_report, "التقرير", "للفترة", a.primary, v -> reportFor(a.reportsScreen.range()[0], a.reportsScreen.range()[1], this::home)));
        for (int i = 0; i < tiles.size(); i += 3) {
            LinearLayout r = a.row();
            for (int j = i; j < i + 3; j++) {
                if (j < tiles.size()) r.addView(tiles.get(j), a.weight(-2, 1, 4));
                else r.addView(new View(a), a.weight(1, 1, 4));
            }
            a.body.addView(r, a.lp(-1, -2, 2));
        }
    }

    // ===================================================================================
    // قائمة الحركات
    // ===================================================================================

    private boolean passes(CashBox.Move m, long dayStart) {
        switch (filter) {
            case 1: return m.createdAt >= dayStart;
            case 2: return CashBox.IN.equals(m.kind) && !m.cancelled();
            case 3: return CashBox.OUT.equals(m.kind) && !m.cancelled();
            case 4: return CashBox.EXPENSE.equals(m.kind) && !m.cancelled();
            case 5: return CashBox.EXCHANGE.equals(m.kind) && !m.cancelled();
            case 6: return m.clientId != null;
            case 7: return m.officeId != null;
            case 8: return CashBox.TRANSFER.equals(m.kind);
            case 9: return m.cancelled();
            default: return true;
        }
    }

    private void list(final List<CashBox.Move> moves, final Map<String, Integer> dec) {
        a.section("الحركات", null, null);
        final LinearLayout chips = a.row();
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final Map<Long, String> names = a.db.officeNames();
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            chips.removeAllViews();
            for (int i = 0; i < FILTERS.length; i++) {
                final int k = i;
                chips.addView(a.pill(FILTERS[i], i == filter, v -> {
                    filter = k;
                    fill[0].run();
                }), a.gap(-2, -2, 3));
            }
            box.removeAllViews();
            long day = CashBox.startOfDay(Clock.now());
            int n = 0;
            for (int i = moves.size() - 1; i >= 0 && n < 150; i--) {
                CashBox.Move m = moves.get(i);
                if (!passes(m, day)) continue;
                box.addView(moveRow(m, names, dec), a.lp(-1, -2, 2));
                n++;
            }
            if (n == 0) box.addView(a.text("لا توجد حركات هنا.", 14, a.muted, false));
            else if (n >= 150) box.addView(a.text("تظهر آخر 150 حركة؛ كلها في «التقرير».", 12, a.muted, false));
        };
        a.body.addView(a.strip(chips), a.lp(-1, -2, 2));
        a.body.addView(box);
        fill[0].run();
    }

    String title(CashBox.Move m, Map<Long, String> names) {
        if (m.officeId != null) {
            String n = names.get(m.officeId);
            return (m.dir > 0 ? "استلام لحساب " : "تسليم لحساب ") + (n == null ? "مكتب" : n);
        }
        if (m.clientId != null) return (m.dir > 0 ? "استلام من العميل " : "تسليم للعميل ") + m.party;
        if (CashBox.EXCHANGE.equals(m.kind)) {
            if (CashBox.CAT_SELL.equals(m.category) || CashBox.CAT_BUY.equals(m.category))
                return m.mainLeg() ? m.category : (CashBox.CAT_SELL.equals(m.category) ? "مقابل البيع" : "مقابل الشراء");
            return m.dir > 0 ? "صرف عملة — داخل" : "صرف عملة — خارج";
        }
        if (CashBox.TRANSFER.equals(m.kind)) return m.dir > 0 ? "تحويل وارد" : "تحويل إلى صندوق آخر";
        if (CashBox.OPENING.equals(m.kind)) return "رصيد افتتاحي";
        if (CashBox.ADJUST.equals(m.kind)) return m.dir > 0 ? "جرد — زيادة" : "جرد — عجز";
        return CashBox.kindName(m.kind) + (m.category.isEmpty() ? "" : " — " + m.category);
    }

    private View moveRow(final CashBox.Move m, final Map<Long, String> names, Map<String, Integer> dec) {
        LinearLayout r = a.row();
        r.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        r.setBackground(a.ripple(a.round(a.surface, a.line, 14), 14));
        boolean in = m.dir > 0;
        int col = !m.counts() ? a.muted : in ? a.green : a.red;
        int icon = CashBox.EXPENSE.equals(m.kind) ? R.drawable.ic_tag : CashBox.EXCHANGE.equals(m.kind) ? R.drawable.ic_coins
                : CashBox.TRANSFER.equals(m.kind) ? R.drawable.ic_transfer : m.clientId != null ? R.drawable.ic_user
                : CashBox.ADJUST.equals(m.kind) ? R.drawable.ic_check2 : m.officeId != null ? R.drawable.ic_offices : in ? R.drawable.ic_download : R.drawable.ic_upload;
        LinearLayout ic = new LinearLayout(a);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Ui.mix(a.surface, col, a.night ? 0.3f : 0.12f));
        cg.setCornerRadius(a.dp(12));
        ic.setBackground(cg);
        ic.addView(a.icon(icon, col, 20), new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
        r.addView(ic, new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(10), 0, a.dp(6), 0);
        c.addView(a.text(title(m, names), 14, m.counts() ? a.ink : a.muted, true));
        String sub = Model.date(m.createdAt);
        String who = m.clientId != null ? "" : Model.nz(m.party);
        if (!Model.nz(m.description).isEmpty()) who = who.isEmpty() ? m.description : who + " — " + m.description;
        if (!Model.nz(m.rate).isEmpty() && (CashBox.EXCHANGE.equals(m.kind) || m.clientId != null)) who = (who.isEmpty() ? "" : who + " — ") + "السعر " + m.rate;
        if (!who.isEmpty()) sub = who + "\n" + sub;
        String st = m.stateText();
        if (!st.isEmpty()) sub += "   •   " + st;
        TextView s = a.text(sub, 12, a.muted, false);
        s.setMaxLines(3);
        c.addView(s);
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout amt = new LinearLayout(a);
        amt.setOrientation(LinearLayout.VERTICAL);
        amt.setGravity(Gravity.LEFT);
        int d = dec(dec, m.currency);
        TextView av = a.text(a.amountsHidden() ? "••••" : (in ? "+" : "−") + Model.fmt(m.amount, d), 16, col, true);
        av.setTextDirection(View.TEXT_DIRECTION_LTR);
        amt.addView(av);
        long fc = m.feeCash();
        TextView cur = a.text(m.currency + (fc != 0 && !a.amountsHidden() ? (fc > 0 ? "  أجور +" : "  أجور −") + Model.fmt(Math.abs(fc), d) : ""), 11, a.muted, false);
        cur.setTextDirection(View.TEXT_DIRECTION_LTR);
        amt.addView(cur);
        r.addView(amt, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(v -> details(m, names));
        return r;
    }

    private void details(final CashBox.Move m, Map<Long, String> names) {
        int d = a.db.decimals(m.currency);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"الحركة", title(m, names)});
        rows.add(new String[]{m.dir > 0 ? "دخل الصندوق" : "خرج من الصندوق", money(m.amount, m.currency)});
        if (m.fee > 0) rows.add(new String[]{"الأجور", iso(Model.fmt(m.fee, d)) + " " + (CashBox.FEE_US.equals(m.feeSide) ? "لنا" : "علينا")
                + (m.feeInCash ? " — نقدًا في الصندوق" : " — على حساب المكتب")});
        if (!Model.nz(m.rate).isEmpty()) rows.add(new String[]{"السعر", m.rate});
        if (m.pairUuid != null && CashBox.EXCHANGE.equals(m.kind)) {
            for (CashBox.Move o : a.db.cashMoves(m.boxId))
                if (m.pairUuid.equals(o.pairUuid) && o.id != m.id) rows.add(new String[]{o.dir > 0 ? "ودخل مقابلها" : "وخرج مقابلها", money(o.amount, o.currency)});
        }
        if (!m.party.isEmpty()) rows.add(new String[]{m.clientId != null ? "العميل" : "الشخص", m.party});
        if (!m.description.isEmpty()) rows.add(new String[]{"البيان", m.description});
        if (m.officeId != null) rows.add(new String[]{"على المكتب", (m.dir > 0 ? "علينا" : "لنا") + " — حركة رقم " + m.txId});
        rows.add(new String[]{"التاريخ", Model.date(m.createdAt)});
        rows.add(new String[]{"بواسطة", m.userName});
        if (!m.stateText().isEmpty()) rows.add(new String[]{"الحالة", m.stateText()});
        if (m.cancelled() && !m.cancelReason.isEmpty()) rows.add(new String[]{"سبب الإلغاء", m.cancelReason});
        AlertDialog.Builder db = new AlertDialog.Builder(a).setView(detailView("حركة صندوق", rows, null)).setPositiveButton("إغلاق", null);
        if (!m.cancelled() && a.can(Auth.Perm.TX_CANCEL)) db.setNegativeButton("إلغاء الحركة", (x, w) -> cancel(m));
        db.show();
    }

    private void cancel(final CashBox.Move m) {
        final EditText reason = new EditText(a);
        reason.setHint("سبب الإلغاء");
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(reason, new LinearLayout.LayoutParams(-1, -2));
        String msg = m.officeId != null ? "تُلغى معها حركة المكتب المرتبطة بقيد عكسي، فيعود رصيد المكتب كما كان."
                : m.clientId != null ? "تُلغى معها الحركة على حساب العميل، فيعود رصيده كما كان."
                : CashBox.EXCHANGE.equals(m.kind) ? "تُلغى ساقا البيع أو الشراء معًا (العملة ومقابلها)."
                : CashBox.TRANSFER.equals(m.kind) ? "يُلغى التحويل في الصندوقين معًا." : null;
        new AlertDialog.Builder(a).setTitle("إلغاء حركة الصندوق").setView(l).setMessage(msg)
                .setPositiveButton("إلغاء الحركة", (d, w) -> {
                    try {
                        a.db.cancelCashMove(m.id, reason.getText().toString().trim());
                        a.toast("أُلغيت الحركة");
                        home();
                    } catch (Exception e) {
                        a.error(e);
                    }
                }).setNegativeButton("رجوع", null).show();
    }

    // ===================================================================================
    // 9.0.1: التفاصيل قبل التسجيل (كل حركة)
    // ===================================================================================

    /** جدول «البند: القيمة» داخل نافذة، مع تنبيه أحمر اختياري في أعلاه. */
    View detailView(List<String[]> rows, String warn) { return detailView(null, rows, warn); }

    View detailView(String title, List<String[]> rows, String warn) {
        ScrollView sv = new ScrollView(a);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(6));
        if (title != null) {
            TextView t = a.text(title, 19, a.primaryInk, true);
            t.setPadding(a.dp(4), 0, a.dp(4), a.dp(8));
            l.addView(t);
        }
        if (warn != null) {
            TextView w = a.text("⚠ " + warn, 14, a.red, true);
            w.setBackground(a.round(a.debitBg, a.debitBg, 10));
            w.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
            l.addView(w, a.lp(-1, -2, 4));
        }
        for (String[] r : rows) {
            if (r[0] == null) {
                TextView h = a.text(r[1], 13, a.primaryInk, true);
                h.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(2));
                l.addView(h);
                continue;
            }
            LinearLayout row = a.row();
            row.setPadding(0, a.dp(3), 0, a.dp(3));
            TextView k = a.text(r[0], 13, a.muted, false);
            row.addView(k, new LinearLayout.LayoutParams(0, -2, 0.9f));
            TextView v = a.text(r[1], 14, a.ink, true);
            v.setTextDirection(View.TEXT_DIRECTION_RTL);
            row.addView(v, new LinearLayout.LayoutParams(0, -2, 1.6f));
            l.addView(row);
            View div = new View(a);
            div.setBackgroundColor(a.line);
            l.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(1) / 2)));
        }
        sv.addView(l);
        return sv;
    }

    void confirmDialog(String title, List<String[]> rows, String warn, String okLabel, final Runnable go) {
        new AlertDialog.Builder(a).setView(detailView(title, rows, warn))
                .setPositiveButton(warn != null ? okLabel + " رغم ذلك" : okLabel, (d, w) -> go.run()).setNegativeButton("تعديل", null).show();
    }

    String opName(CashBox.Input x) {
        switch (x.kind) {
            case CashBox.IN: return x.officeId != null ? "استلام لحساب مكتب" : x.clientId != null ? "استلام من عميل" : "استلام إلى الصندوق";
            case CashBox.OUT: return x.officeId != null ? "تسليم لحساب مكتب" : x.clientId != null ? "تسليم لعميل" : "تسليم من الصندوق";
            case CashBox.EXPENSE: return "مصروف";
            case CashBox.EXCHANGE: return CashBox.BUY.equals(x.side) ? "شراء عملة" : "بيع عملة";
            case CashBox.TRANSFER: return "تحويل بين الصناديق";
            default: return CashBox.kindName(x.kind);
        }
    }

    /** يبني التفاصيل الكاملة لحركة قبل حفظها، ثم يحفظها بعد الموافقة. after: الشاشة بعد الحفظ. */
    void preview(final CashBox.Input x, final Runnable after) {
        CashBox.Config cfg = a.db.cashBox(x.boxId);
        Map<String, Integer> dec = a.db.decimalsMap();
        Map<String, Long> bal = a.db.cashBalances(x.boxId);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"العملية", opName(x)});
        rows.add(new String[]{"الصندوق", cfg.name});
        if (CashBox.EXCHANGE.equals(x.kind)) {
            boolean buy = CashBox.BUY.equals(x.side);
            rows.add(new String[]{buy ? "تشتري" : "تبيع", money(x.amount, x.currency)});
            if (!Model.nz(x.rate).isEmpty()) rows.add(new String[]{"السعر", iso("1 " + x.currency + " = " + x.rate + " " + x.toCurrency)});
            rows.add(new String[]{buy ? "وتدفع" : "وتستلم", money(x.toAmount, x.toCurrency)});
            String mk = marketLine(x.currency, x.toCurrency, x.amount, x.toAmount, buy);
            if (mk != null) rows.add(new String[]{"مقارنة بالسوق", mk});
        } else {
            rows.add(new String[]{x.dir() > 0 ? "يدخل الصندوق" : "يخرج من الصندوق", money(x.amount, x.currency)});
        }
        if (!Model.nz(x.category).isEmpty() && x.officeId == null && x.clientId == null) rows.add(new String[]{"البند", x.category});
        if (x.officeId != null) {
            Model.Office o = a.db.office(x.officeId);
            rows.add(new String[]{"المكتب", o == null ? "#" + x.officeId : o.name});
            rows.add(new String[]{"على المكتب", (CashBox.IN.equals(x.kind) ? "علينا " : "لنا ") + money(x.amount, x.currency)});
        }
        if (x.clientId != null) {
            ClientBook.Client c = a.db.client(x.clientId);
            rows.add(new String[]{"العميل", c == null ? "#" + x.clientId : c.name});
            rows.add(new String[]{"على حسابه", (CashBox.IN.equals(x.kind) ? "علينا له " : "لنا عليه ") + money(x.clientAmount, x.clientCurrency)});
            if (!x.clientCurrency.equals(x.currency))
                rows.add(new String[]{"السعر", iso("1 " + x.clientCurrency + " = " + ClientBook.rateOf(x.clientAmount, dec(dec, x.clientCurrency), x.amount, dec(dec, x.currency)) + " " + x.currency)});
            Map<String, Long> cb = ClientBook.balances(a.db.clientMoves(x.clientId));
            long before = cb.containsKey(x.clientCurrency) ? cb.get(x.clientCurrency) : 0;
            long after2 = before + (CashBox.IN.equals(x.kind) ? -x.clientAmount : x.clientAmount);
            rows.add(new String[]{"رصيده " + x.clientCurrency, sideText(before, x.clientCurrency) + " ← " + sideText(after2, x.clientCurrency)});
        }
        if (x.fee > 0) rows.add(new String[]{"الأجور", money(x.fee, x.currency) + " " + (CashBox.FEE_US.equals(x.feeSide) ? "لنا" : "علينا")
                + (x.officeId == null || x.feeInCash ? " — نقدًا في الصندوق" : " — على حساب المكتب")});
        if (!Model.nz(x.party).isEmpty() && x.clientId == null) rows.add(new String[]{"الشخص", x.party});
        if (!Model.nz(x.description).isEmpty()) rows.add(new String[]{"البيان", x.description});
        rows.add(new String[]{"التاريخ", Model.date(x.when)});
        rows.add(new String[]{null, "رصيد الصندوق قبل ← بعد"});
        for (Map.Entry<String, Long> e : CashBox.changes(x).entrySet()) {
            long b = bal.containsKey(e.getKey()) ? bal.get(e.getKey()) : 0;
            rows.add(new String[]{e.getKey(), iso(Model.fmt(b, dec(dec, e.getKey()))) + " ← " + iso(Model.fmt(b + e.getValue(), dec(dec, e.getKey())))
                    + "  (" + iso((e.getValue() >= 0 ? "+" : "−") + Model.fmt(Math.abs(e.getValue()), dec(dec, e.getKey()))) + ")"});
        }
        if (CashBox.TRANSFER.equals(x.kind) && x.toBox != null) {
            CashBox.Config to = a.db.cashBox(x.toBox);
            Map<String, Long> tb = a.db.cashBalances(x.toBox);
            long b = tb.containsKey(x.currency) ? tb.get(x.currency) : 0;
            rows.add(new String[]{null, "صندوق «" + to.name + "»"});
            rows.add(new String[]{x.currency, iso(Model.fmt(b, dec(dec, x.currency))) + " ← " + iso(Model.fmt(b + x.amount, dec(dec, x.currency)))});
        }
        String warn = CashBox.negativeAfter(x, bal, dec);
        confirmDialog("راجع قبل التسجيل", rows, warn, "تسجيل", () -> a.busy("جارٍ التسجيل…", p -> a.db.addCashMove(x), res -> {
            a.toast("سُجّلت الحركة ✓");
            after.run();
        }));
    }

    String sideText(long v, String cur) {
        return v == 0 ? "صفر" : ClientBook.side(v) + " " + iso(Model.fmt(Math.abs(v), a.db.decimals(cur)) + " " + cur);
    }

    /** سطر مقارنة سعرك بسعر السوق (أسعار الإنترنت أو اليدوية)، أو null بلا سعر. */
    String marketLine(String cur, String counter, long amount, long counterAmount, boolean buy) {
        BigDecimal mk = CashBox.crossRate(cur, counter, a.db.usdRates());
        if (mk == null || amount <= 0 || counterAmount <= 0) return null;
        int d1 = a.db.decimals(cur), d2 = a.db.decimals(counter);
        BigDecimal mine = BigDecimal.valueOf(counterAmount, d2).divide(BigDecimal.valueOf(amount, d1), 10, RoundingMode.HALF_EVEN);
        BigDecimal atMarket = BigDecimal.valueOf(amount, d1).multiply(mk);
        BigDecimal diff = BigDecimal.valueOf(counterAmount, d2).subtract(atMarket);
        if (buy) diff = diff.negate();
        BigDecimal pct = mine.subtract(mk).multiply(BigDecimal.valueOf(100)).divide(mk, 2, RoundingMode.HALF_EVEN);
        String sign = diff.signum() >= 0 ? "لصالحك ≈ " : "عليك ≈ ";
        return "السوق " + iso("1 " + cur + " = " + mk.toPlainString() + " " + counter) + "\n" + sign
                + iso(diff.abs().setScale(Math.min(d2, 2), RoundingMode.HALF_EVEN).toPlainString() + " " + counter) + " (" + iso(pct.abs().toPlainString() + "%") + ")";
    }

    // ===================================================================================
    // التاريخ
    // ===================================================================================

    private long when;

    Button dateButton(LinearLayout into) {
        when = 0;
        final Button b = a.smallButton("التاريخ: الآن", Color.rgb(130, 130, 130), null);
        b.setOnClickListener(v -> {
            final Calendar c = Model.cal();
            new DatePickerDialog(a, (x, y, mo, d) -> new TimePickerDialog(a, (tp, h, mi) -> {
                Calendar t = Model.cal();
                t.set(y, mo, d, h, mi, 0);
                t.set(Calendar.MILLISECOND, 0);
                if (t.getTimeInMillis() > Clock.now()) {
                    a.toast("لا يمكن اختيار وقت في المستقبل");
                    return;
                }
                when = t.getTimeInMillis();
                b.setText("التاريخ: " + Model.date(when));
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show(), c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
        });
        (into == null ? a.body : into).addView(b, a.lp(-1, 42, 4));
        return b;
    }

    long now() { return when > 0 ? when : Clock.now(); }

    // ===================================================================================
    // الاستلام والتسليم (بدون حساب، لحساب مكتب، أو لحساب عميل)
    // ===================================================================================

    /** office أو client: محدد مسبقًا (من صفحة المكتب أو العميل) أو null. */
    void form(final String kind, final Model.Office presetOffice, final ClientBook.Client presetClient) {
        if (!a.require(Auth.Perm.CASH)) return;
        if (!a.db.cashConfig().on) {
            show();
            return;
        }
        guard(() -> formImpl(kind, presetOffice, presetClient));
    }

    void form(final String kind, final Model.Office preset) { form(kind, preset, null); }

    private void formImpl(final String kind, final Model.Office preset, final ClientBook.Client presetClient) {
        final CashBox.Config cfg = a.db.cashConfig();
        final boolean in = CashBox.IN.equals(kind);
        final Runnable back = presetClient != null ? () -> clients.page(presetClient.id) : this::home;
        screen(in ? "استلام إلى الصندوق" : "تسليم من الصندوق", back);
        final Model.Office[] office = {preset};
        final ClientBook.Client[] client = {presetClient};
        final Map<String, Integer> dec = a.db.decimalsMap();
        a.hint("الصندوق: " + cfg.name);

        a.add(a.label("الحساب"));
        final String[] links = cfg.strict ? new String[]{"لحساب مكتب", "لحساب عميل"} : new String[]{"لحساب مكتب", "لحساب عميل", "بدون حساب"};
        final Ui.Seg link = a.new Seg(links, null, preset != null ? 0 : presetClient != null ? 1 : cfg.strict ? 0 : 2, false);
        a.body.addView(link.view, a.lp(-1, -2, 2));
        final LinearLayout officeBox = new LinearLayout(a);
        officeBox.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(officeBox);
        final Button officeBtn = a.makeButton("اختر المكتب", a.accent, null);
        officeBox.addView(officeBtn, a.lp(-1, 52, 2));
        final TextView officeHint = a.text("", 12, a.muted, false);
        officeBox.addView(officeHint);
        final LinearLayout clientBox = new LinearLayout(a);
        clientBox.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(clientBox);
        final Button clientBtn = a.makeButton("اختر العميل", a.accent, null);
        clientBox.addView(clientBtn, a.lp(-1, 52, 2));
        final TextView clientHint = a.text("", 12, a.muted, false);
        clientBox.addView(clientHint);
        final LinearLayout catBox = new LinearLayout(a);
        catBox.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(catBox);
        final List<String> cats = in ? cfg.inCats : cfg.outCats;
        catBox.addView(a.label("البند"));
        final Spinner cat = a.spinner(cats.toArray(new String[0]));
        catBox.addView(cat, a.lp(-1, 48, 2));
        catBox.addView(a.smallButton("+ بند جديد", Color.rgb(130, 130, 130), v -> newCategory(kind, () -> formImpl(kind, office[0], client[0]))), a.lp(-2, 38, 2));

        a.add(a.label("عملة النقد"));
        final LinearLayout curHolder = new LinearLayout(a);
        a.body.addView(curHolder, new LinearLayout.LayoutParams(-1, -2));
        final Ui.Seg[] cur = {null};
        final Runnable[] refreshRef = new Runnable[1];
        final Runnable drawCurs = () -> {
            String prev = cur[0] == null ? null : cur[0].label();
            List<String> show = new ArrayList<>(cfg.currencies);
            if (link.selected == 0 && office[0] != null) show.retainAll(a.db.officeCurrencies(office[0].id));
            curHolder.removeAllViews();
            if (show.isEmpty()) {
                curHolder.addView(a.text("لا عملة مشتركة بين الصندوق وهذا المكتب.", 13, a.red, true));
                cur[0] = null;
                return;
            }
            cur[0] = a.new Seg(show.toArray(new String[0]), null, show.size() == 1 ? 0 : Math.max(-1, prev == null ? -1 : show.indexOf(prev)), show.size() > 4);
            cur[0].listener = i -> {
                if (refreshRef[0] != null) refreshRef[0].run();
            };
            curHolder.addView(cur[0].view, new LinearLayout.LayoutParams(-1, -2));
        };

        a.add(a.label("المبلغ"));
        final EditText amount = a.amountInput("0");
        amount.setTextSize(22);
        Ui.bold(amount);

        // حساب العميل بعملة أخرى (له 100 يورو فيأخذ دولارًا)
        final LinearLayout crossBox = new LinearLayout(a);
        crossBox.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(crossBox);
        crossBox.addView(a.label(in ? "يُضاف إلى حسابه بـ" : "يُخصم من حسابه بـ"));
        final Ui.Seg same = a.new Seg(new String[]{"عملة النقد نفسها", "عملة أخرى"}, null, 0, false);
        crossBox.addView(same.view, a.lp(-1, -2, 2));
        final LinearLayout crossIn = new LinearLayout(a);
        crossIn.setOrientation(LinearLayout.VERTICAL);
        crossBox.addView(crossIn);
        final List<String> allCurs = new ArrayList<>();
        for (Model.Currency c : a.db.currencies(false)) allCurs.add(c.code);
        final Ui.Seg ccur = a.new Seg(allCurs.toArray(new String[0]), null, -1, allCurs.size() > 4);
        crossIn.addView(ccur.view, a.lp(-1, -2, 2));
        final EditText crate = a.field("السعر: 1 من عملة الحساب = كم من عملة النقد");
        Ui.numeric(crate);
        crossIn.addView(crate, a.lp(-1, 50, 2));
        final EditText camt = a.field("المبلغ على حسابه");
        Ui.numeric(camt);
        crossIn.addView(camt, a.lp(-1, 50, 2));
        final TextView cmarket = a.text("", 12, a.muted, false);
        crossIn.addView(cmarket);

        final EditText party = a.input(in ? "ممن استلمت؟ (الاسم)" : "لمن سلّمت؟ (المستفيد)");
        final EditText desc = a.input("البيان (اختياري)");

        a.add(a.label("الأجور"));
        final Ui.Seg fee = a.seg(new String[]{"بدون أجور", "أجور لنا", "أجور علينا"}, 0, null);
        final LinearLayout feeBox = new LinearLayout(a);
        feeBox.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(feeBox);
        final EditText feeAmt = a.field("مبلغ الأجور (بعملة النقد)");
        Ui.numeric(feeAmt);
        feeBox.addView(feeAmt, a.lp(-1, 50, 2));
        final Ui.Seg feeWhere = a.new Seg(new String[]{"على حساب المكتب", "نقدًا في الصندوق"}, null, 0, false);
        feeBox.addView(feeWhere.view, a.lp(-1, -2, 2));
        dateButton(null);
        final TextView summary = a.add(a.text("", 14, a.primaryInk, true));
        final boolean[] lock = {false};

        final Runnable recalcCross = () -> {
            // السعر ← المبلغ على الحساب = المبلغ ÷ السعر
            if (lock[0] || cur[0] == null || ccur.none()) return;
            try {
                long amt = Db.minor(amount.getText().toString(), dec(dec, cur[0].label()));
                BigDecimal r = CashBox.rateDecimal(crate.getText().toString());
                if (r == null || r.signum() <= 0 || amt <= 0) return;
                int dc = dec(dec, ccur.label());
                lock[0] = true;
                camt.setText(BigDecimal.valueOf(amt, dec(dec, cur[0].label())).divide(r, dc, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString());
            } catch (Exception ignored) {
                // ناقص
            } finally {
                lock[0] = false;
            }
        };

        final Runnable refresh = () -> {
            int ls = link.selected;
            boolean withOffice = ls == 0, withClient = ls == 1;
            officeBox.setVisibility(withOffice ? View.VISIBLE : View.GONE);
            clientBox.setVisibility(withClient ? View.VISIBLE : View.GONE);
            crossBox.setVisibility(withClient ? View.VISIBLE : View.GONE);
            crossIn.setVisibility(withClient && same.selected == 1 ? View.VISIBLE : View.GONE);
            catBox.setVisibility(!withOffice && !withClient ? View.VISIBLE : View.GONE);
            party.setVisibility(withClient ? View.GONE : View.VISIBLE);
            feeBox.setVisibility(fee.selected > 0 ? View.VISIBLE : View.GONE);
            feeWhere.view.setVisibility(withOffice ? View.VISIBLE : View.GONE);
            if (office[0] != null) {
                officeBtn.setText("المكتب: " + office[0].name);
                officeHint.setText(in ? "يُسجَّل على المكتب «علينا» بالمبلغ نفسه (استلمنا لحسابه)." : "يُسجَّل على المكتب «لنا» بالمبلغ نفسه (سلّمنا لحسابه).");
            }
            if (client[0] != null) {
                clientBtn.setText("العميل: " + client[0].name);
                Map<String, Long> cb = ClientBook.balances(a.db.clientMoves(client[0].id));
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Long> e : cb.entrySet()) parts.add(sideText(e.getValue(), e.getKey()));
                clientHint.setText("رصيده الآن: " + (parts.isEmpty() ? "صفر" : Model.join(parts, " • ")));
            }
            String c = cur[0] == null ? null : cur[0].label();
            if (c != null && !ccur.none()) {
                BigDecimal mk = CashBox.crossRate(ccur.label(), c, a.db.usdRates());
                cmarket.setText(mk == null ? "لا يوجد سعر سوق لهذه العملة" : "سعر السوق: " + iso("1 " + ccur.label() + " = " + mk.toPlainString() + " " + c) + " — اضغط هنا لاستخدامه");
                cmarket.setOnClickListener(mk == null ? null : v -> {
                    crate.setText(mk.toPlainString());
                    recalcCross.run();
                });
            }
            long amt = 0;
            try {
                if (c != null && !amount.getText().toString().trim().isEmpty()) amt = Db.minor(amount.getText().toString(), dec(dec, c));
            } catch (Exception ignored) {
                // يظهر عند الحفظ
            }
            if (c == null || amt <= 0) {
                summary.setText("");
                return;
            }
            int d = dec(dec, c);
            long f = 0;
            try {
                if (fee.selected > 0 && !feeAmt.getText().toString().trim().isEmpty()) f = Db.minor(feeAmt.getText().toString(), d);
            } catch (Exception ignored) {
                // يظهر عند الحفظ
            }
            boolean cashFee = !withOffice || feeWhere.selected == 1;
            long cashEffect = (in ? amt : -amt) + (f > 0 && cashFee ? (fee.selected == 1 ? f : -f) : 0);
            StringBuilder sb = new StringBuilder();
            sb.append(cashEffect >= 0 ? "يدخل الصندوق: " : "يخرج من الصندوق: ").append(iso(Model.fmt(Math.abs(cashEffect), d) + " " + c));
            if (withOffice && office[0] != null) {
                sb.append("\nوعلى مكتب «").append(office[0].name).append("»: ").append(in ? "علينا " : "لنا ").append(iso(Model.fmt(amt, d) + " " + c));
                if (f > 0 && !cashFee) sb.append(" وأجرة ").append(fee.selected == 1 ? "لنا " : "علينا ").append(iso(Model.fmt(f, d)));
            }
            if (withClient && client[0] != null) {
                String cc = same.selected == 1 && !ccur.none() ? ccur.label() : c;
                String ca = same.selected == 1 ? camt.getText().toString().trim() : Model.fmt(amt, d);
                sb.append("\nوعلى حساب «").append(client[0].name).append("»: ").append(in ? "علينا له " : "لنا عليه ").append(iso(ca + " " + cc));
            }
            summary.setText(sb.toString());
        };
        officeBtn.setOnClickListener(v -> a.officePicker(o -> {
            office[0] = o;
            drawCurs.run();
            refresh.run();
        }, true));
        clientBtn.setOnClickListener(v -> clients.pick(k -> {
            client[0] = k;
            refresh.run();
        }));
        link.listener = i -> {
            drawCurs.run();
            refresh.run();
        };
        same.listener = i -> refresh.run();
        ccur.listener = i -> {
            refresh.run();
            recalcCross.run();
        };
        fee.listener = i -> refresh.run();
        feeWhere.listener = i -> refresh.run();
        amount.addTextChangedListener(Ui.watcher(() -> {
            recalcCross.run();
            refresh.run();
        }));
        crate.addTextChangedListener(Ui.watcher(() -> {
            recalcCross.run();
            refresh.run();
        }));
        camt.addTextChangedListener(Ui.watcher(refresh));
        feeAmt.addTextChangedListener(Ui.watcher(refresh));
        refreshRef[0] = refresh;
        drawCurs.run();
        refresh.run();

        a.footer(a.makeButton(in ? "مراجعة الاستلام" : "مراجعة التسليم", in ? a.green : a.red, v -> {
            try {
                CashBox.Input x = new CashBox.Input();
                x.kind = kind;
                x.boxId = cfg.id;
                int ls = link.selected;
                if (ls == 0) {
                    if (office[0] == null) throw new Exception("اختر المكتب");
                    x.officeId = office[0].id;
                } else if (ls == 1) {
                    if (client[0] == null) throw new Exception("اختر العميل أو أضفه");
                    x.clientId = client[0].id;
                }
                if (cur[0] == null || cur[0].none()) throw new Exception("اختر العملة");
                x.currency = cur[0].label();
                int d = dec(dec, x.currency);
                x.amount = Db.minor(amount.getText().toString(), d);
                if (x.clientId != null) {
                    if (same.selected == 1) {
                        if (ccur.none()) throw new Exception("اختر عملة حساب العميل");
                        x.clientCurrency = ccur.label();
                        if (x.clientCurrency.equals(x.currency)) throw new Exception("اختر عملة غير عملة النقد، أو «عملة النقد نفسها»");
                        x.clientAmount = Db.minor(camt.getText().toString(), dec(dec, x.clientCurrency));
                    } else {
                        x.clientCurrency = x.currency;
                        x.clientAmount = x.amount;
                    }
                } else x.party = party.getText().toString().trim();
                x.description = desc.getText().toString().trim();
                if (ls == 2) x.category = cats.isEmpty() ? "" : String.valueOf(cat.getSelectedItem());
                if (fee.selected > 0) {
                    x.fee = Db.minor(feeAmt.getText().toString(), d);
                    if (x.fee <= 0) throw new Exception("اكتب مبلغ الأجور أو اختر «بدون أجور»");
                    x.feeSide = fee.selected == 1 ? CashBox.FEE_US : CashBox.FEE_THEM;
                    x.feeInCash = ls != 0 || feeWhere.selected == 1;
                }
                x.when = now();
                String err = CashBox.validate(x, cfg, Clock.now());
                if (err != null) throw new Exception(err);
                preview(x, presetClient != null ? () -> clients.page(presetClient.id) : this::home);
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    private void newCategory(final String kind, final Runnable after) {
        final EditText e = new EditText(a);
        e.setHint("اسم البند");
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle("بند جديد").setView(l).setPositiveButton("إضافة", (d, w) -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty()) return;
            a.db.addCashCategory(kind, n);
            after.run();
        }).setNegativeButton("إلغاء", null).show();
    }

    // ===================================================================================
    // المصروف
    // ===================================================================================

    void expense() {
        if (!a.require(Auth.Perm.CASH) || !a.require(Auth.Perm.CASH_EXPENSE)) return;
        final CashBox.Config cfg = a.db.cashConfig();
        screen("مصروف من الصندوق", this::home);
        final Map<String, Integer> dec = a.db.decimalsMap();
        a.add(a.label("البند"));
        final Ui.Flow chips = a.flow();
        final String[] chosen = {null};
        final List<TextView> pills = new ArrayList<>();
        for (final String c : cfg.expenseCats) {
            final TextView[] me = new TextView[1];
            me[0] = a.pill(c, false, v -> {
                chosen[0] = c;
                for (TextView t : pills) {
                    boolean on = t == me[0];
                    t.setBackground(a.round(on ? a.accent : a.surface, on ? a.accent : a.line, 50));
                    t.setTextColor(on ? Color.rgb(42, 30, 0) : a.ink);
                }
            });
            pills.add(me[0]);
            chips.addView(me[0]);
        }
        a.body.addView(chips);
        a.body.addView(a.smallButton("+ بند جديد", Color.rgb(130, 130, 130), v -> newCategory(CashBox.EXPENSE, this::expense)), a.lp(-2, 38, 2));
        a.add(a.label("العملة"));
        final Ui.Seg cur = a.new Seg(cfg.currencies.toArray(new String[0]), null, cfg.currencies.size() == 1 ? 0 : -1, cfg.currencies.size() > 4);
        a.body.addView(cur.view, a.lp(-1, -2, 2));
        a.add(a.label("المبلغ"));
        final EditText amount = a.amountInput("0");
        amount.setTextSize(22);
        final EditText party = a.input("لمن؟ (اختياري: اسم العامل، صاحب المحل…)");
        final EditText desc = a.input("البيان (مثل: راتب شهر 10)");
        dateButton(null);
        a.footer(a.makeButton("مراجعة المصروف", a.accent, v -> {
            try {
                if (chosen[0] == null) throw new Exception("اختر بند المصروف");
                if (cur.none()) throw new Exception("اختر العملة");
                CashBox.Input x = new CashBox.Input();
                x.kind = CashBox.EXPENSE;
                x.boxId = cfg.id;
                x.category = chosen[0];
                x.currency = cur.label();
                x.amount = Db.minor(amount.getText().toString(), dec(dec, x.currency));
                x.party = party.getText().toString().trim();
                x.description = desc.getText().toString().trim();
                x.when = now();
                String err = CashBox.validate(x, cfg, Clock.now());
                if (err != null) throw new Exception(err);
                preview(x, this::home);
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    // ===================================================================================
    // 9.0.1: البيع والشراء
    // ===================================================================================

    void trade(final String side) {
        if (!a.require(Auth.Perm.CASH) || !a.require(Auth.Perm.CASH_FX)) return;
        final CashBox.Config cfg = a.db.cashConfig();
        if (cfg.currencies.size() < 2) {
            a.toast("أضف عملة ثانية للصندوق من إعداداته أولًا");
            return;
        }
        final boolean buy = CashBox.BUY.equals(side);
        screen(buy ? "شراء عملة" : "بيع عملة", this::home);
        final Map<String, Integer> dec = a.db.decimalsMap();
        final String[] cs = cfg.currencies.toArray(new String[0]);
        final Ui.Seg sideSeg = a.new Seg(new String[]{"بيع", "شراء"}, new int[]{a.primary, teal()}, buy ? 1 : 0, false);
        sideSeg.listener = i -> trade(i == 1 ? CashBox.BUY : CashBox.SELL);
        a.body.addView(sideSeg.view, a.lp(-1, -2, 2));
        a.hint(buy ? "تشتري عملة: تدخل الصندوق، وتدفع مقابلها من عملة أخرى تخرج من الصندوق."
                : "تبيع عملة: تخرج من الصندوق، وتستلم مقابلها عملة أخرى تدخل الصندوق.");
        a.add(a.label(buy ? "1) العملة التي تشتريها" : "1) العملة التي تبيعها"));
        final Ui.Seg cur = a.new Seg(cs, null, -1, cs.length > 4);
        a.body.addView(cur.view, a.lp(-1, -2, 2));
        final EditText amount = a.amountInput("الكمية");
        amount.setTextSize(20);
        Ui.bold(amount);
        a.add(a.label(buy ? "2) تدفع مقابلها بـ" : "2) تستلم مقابلها بـ"));
        final Ui.Seg counter = a.new Seg(cs, null, -1, cs.length > 4);
        a.body.addView(counter.view, a.lp(-1, -2, 2));
        a.add(a.label("3) السعر والمقابل"));
        final TextView rateLbl = a.add(a.text("السعر: كم من العملة المقابلة لكل 1", 12, a.muted, false));
        final EditText rate = a.field("السعر");
        Ui.numeric(rate);
        a.body.addView(rate, a.lp(-1, 50, 2));
        final TextView market = a.add(a.text("", 12, a.primary, true));
        final EditText counterAmt = a.amountInput(buy ? "تدفع (يُحسب تلقائيًا)" : "تستلم (يُحسب تلقائيًا)");
        final TextView live = a.add(a.text("", 14, a.primaryInk, true));
        final EditText party = a.input("مع من؟ (اختياري)");
        final EditText desc = a.input("البيان (اختياري)");
        dateButton(null);
        LinearLayout rr = a.row();
        if (a.can(Auth.Perm.RATES_ONLINE)) rr.addView(a.smallButton("تحديث الأسعار من الإنترنت", Color.rgb(130, 130, 130), v -> a.busy("جارٍ تحديث الأسعار…", p -> ExchangeRates.refresh(a.db), res -> {
            a.toast("حُدّثت أسعار " + res + " عملة");
            trade(side);
        })), new LinearLayout.LayoutParams(-2, a.dp(38)));
        a.body.addView(rr, a.lp(-1, -2, 2));

        final boolean[] lock = {false};
        final Runnable info = () -> {
            String c1 = cur.label(), c2 = counter.label();
            if (c1 == null || c2 == null || c1.equals(c2)) {
                rateLbl.setText(c1 != null && c1.equals(c2) ? "اختر عملتين مختلفتين" : "السعر: كم من العملة المقابلة لكل 1");
                market.setText("");
                live.setText("");
                return;
            }
            rateLbl.setText("السعر: " + iso("1 " + c1 + " = ؟ " + c2));
            BigDecimal mk = CashBox.crossRate(c1, c2, a.db.usdRates());
            market.setText(mk == null ? "لا يوجد سعر سوق لهما — حدّث الأسعار أو اكتب السعر" : "سعر السوق: " + iso("1 " + c1 + " = " + mk.toPlainString() + " " + c2) + "   (اضغط لاستخدامه)");
            market.setOnClickListener(mk == null ? null : v -> rate.setText(mk.toPlainString()));
            try {
                long amt = Db.minor(amount.getText().toString(), dec(dec, c1));
                long ca = Db.minor(counterAmt.getText().toString(), dec(dec, c2));
                String t = (buy ? "تشتري " : "تبيع ") + iso(Model.fmt(amt, dec(dec, c1)) + " " + c1) + (buy ? " وتدفع " : " وتستلم ") + iso(Model.fmt(ca, dec(dec, c2)) + " " + c2);
                String ml = marketLine(c1, c2, amt, ca, buy);
                live.setText(ml == null ? t : t + "\n" + ml);
            } catch (Exception e) {
                live.setText("");
            }
        };
        final Runnable fromRate = () -> {
            if (lock[0]) return;
            String c1 = cur.label(), c2 = counter.label();
            if (c1 == null || c2 == null) return;
            try {
                long amt = Db.minor(amount.getText().toString(), dec(dec, c1));
                Long ca = CashBox.counterAmount(amt, dec(dec, c1), rate.getText().toString(), dec(dec, c2));
                if (ca == null) return;
                lock[0] = true;
                counterAmt.setText(BigDecimal.valueOf(ca, dec(dec, c2)).stripTrailingZeros().toPlainString());
            } catch (Exception ignored) {
                // ناقص
            } finally {
                lock[0] = false;
            }
        };
        final Runnable fromCounter = () -> {
            // كتب المقابل بيده: السعر = المقابل ÷ الكمية
            if (lock[0] || !counterAmt.hasFocus()) return;
            String c1 = cur.label(), c2 = counter.label();
            if (c1 == null || c2 == null) return;
            try {
                long amt = Db.minor(amount.getText().toString(), dec(dec, c1));
                long ca = Db.minor(counterAmt.getText().toString(), dec(dec, c2));
                lock[0] = true;
                rate.setText(ClientBook.rateOf(amt, dec(dec, c1), ca, dec(dec, c2)));
            } catch (Exception ignored) {
                // ناقص
            } finally {
                lock[0] = false;
            }
        };
        cur.listener = i -> {
            fromRate.run();
            info.run();
        };
        counter.listener = i -> {
            fromRate.run();
            info.run();
        };
        amount.addTextChangedListener(Ui.watcher(() -> {
            fromRate.run();
            info.run();
        }));
        rate.addTextChangedListener(Ui.watcher(() -> {
            fromRate.run();
            info.run();
        }));
        counterAmt.addTextChangedListener(Ui.watcher(() -> {
            fromCounter.run();
            info.run();
        }));
        info.run();
        a.footer(a.makeButton(buy ? "مراجعة الشراء" : "مراجعة البيع", buy ? teal() : a.primary, v -> {
            try {
                if (cur.none()) throw new Exception(buy ? "اختر العملة التي تشتريها" : "اختر العملة التي تبيعها");
                if (counter.none()) throw new Exception("اختر العملة المقابلة");
                CashBox.Input x = new CashBox.Input();
                x.kind = CashBox.EXCHANGE;
                x.side = side;
                x.boxId = cfg.id;
                x.currency = cur.label();
                x.toCurrency = counter.label();
                x.amount = Db.minor(amount.getText().toString(), dec(dec, x.currency));
                String ct = counterAmt.getText().toString().trim();
                if (ct.isEmpty()) {
                    Long ca = CashBox.counterAmount(x.amount, dec(dec, x.currency), rate.getText().toString(), dec(dec, x.toCurrency));
                    if (ca == null) throw new Exception("اكتب السعر أو المبلغ المقابل");
                    x.toAmount = ca;
                } else x.toAmount = Db.minor(ct, dec(dec, x.toCurrency));
                String rt = rate.getText().toString().trim();
                BigDecimal rv = CashBox.rateDecimal(rt);
                if (!rt.isEmpty() && rv == null) throw new Exception("السعر غير مفهوم: اكتبه مثل 0.92 أو 13500");
                x.rate = rv == null ? ClientBook.rateOf(x.amount, dec(dec, x.currency), x.toAmount, dec(dec, x.toCurrency)) : rv.toPlainString();
                x.party = party.getText().toString().trim();
                x.description = desc.getText().toString().trim();
                x.when = now();
                String err = CashBox.validate(x, cfg, Clock.now());
                if (err != null) throw new Exception(err);
                preview(x, this::home);
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    /** متوافق مع 9.0: «صرف عملة» صار بيعًا. */
    void exchange() { trade(CashBox.SELL); }

    // ===================================================================================
    // 9.0.1: التحويل بين الصناديق
    // ===================================================================================

    void transfer() {
        if (!a.require(Auth.Perm.CASH)) return;
        final CashBox.Config cfg = a.db.cashConfig();
        final List<CashBox.Config> others = new ArrayList<>();
        for (CashBox.Config c : activeBoxes()) if (c.id != cfg.id) others.add(c);
        if (others.isEmpty()) {
            a.toast("لا يوجد صندوق آخر. أضفه من «+ صندوق».");
            return;
        }
        screen("تحويل من «" + cfg.name + "»", this::home);
        final Map<String, Integer> dec = a.db.decimalsMap();
        a.hint("ينقل نقدًا من هذا الصندوق إلى صندوق آخر لك (مثل: من صندوق المحل إلى خزنة البيت). مجموع النقد لا يتغير.");
        a.add(a.label("إلى الصندوق"));
        String[] names = new String[others.size()];
        for (int i = 0; i < names.length; i++) names[i] = others.get(i).name;
        final Ui.Seg to = a.new Seg(names, null, names.length == 1 ? 0 : -1, names.length > 3);
        a.body.addView(to.view, a.lp(-1, -2, 2));
        a.add(a.label("العملة"));
        final Ui.Seg cur = a.new Seg(cfg.currencies.toArray(new String[0]), null, cfg.currencies.size() == 1 ? 0 : -1, cfg.currencies.size() > 4);
        a.body.addView(cur.view, a.lp(-1, -2, 2));
        final EditText amount = a.amountInput("المبلغ");
        amount.setTextSize(20);
        final EditText desc = a.input("البيان (اختياري)");
        dateButton(null);
        a.footer(a.makeButton("مراجعة التحويل", teal(), v -> {
            try {
                if (to.none()) throw new Exception("اختر الصندوق");
                if (cur.none()) throw new Exception("اختر العملة");
                CashBox.Input x = new CashBox.Input();
                x.kind = CashBox.TRANSFER;
                x.boxId = cfg.id;
                x.toBox = others.get(to.selected).id;
                x.currency = cur.label();
                x.amount = Db.minor(amount.getText().toString(), dec(dec, x.currency));
                x.description = desc.getText().toString().trim();
                x.when = now();
                String err = CashBox.validate(x, cfg, Clock.now());
                if (err != null) throw new Exception(err);
                if (!others.get(to.selected).currencies.contains(x.currency))
                    throw new Exception("العملة " + x.currency + " غير مفعّلة في «" + others.get(to.selected).name + "». أضفها من إعداداته.");
                preview(x, this::home);
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    // ===================================================================================
    // الجرد، ومراجعته (زيادة عملة مقابل عجز أخرى)، وسجله
    // ===================================================================================

    void count() {
        if (!a.require(Auth.Perm.CASH) || !a.require(Auth.Perm.CASH_COUNT)) return;
        final CashBox.Config cfg = a.db.cashConfig();
        screen("جرد «" + cfg.name + "»", this::home);
        final Map<String, Integer> dec = a.db.decimalsMap();
        // 10.5 (AUDIT-028): المسجّل = الرصيد + نقد حركات معلّقة بانتظار الاعتماد (موجود في الصندوق ولم يُحسب بعد)
        final Map<String, Long> bal = a.db.cashExpected(cfg.id);
        final Map<String, Long> pend = a.db.cashPending(cfg.id);
        a.hint("اعدد النقد الموجود فعلًا واكتبه لكل عملة، ثم «مراجعة الجرد»: يظهر العجز والزيادة، ويُقارن زيادة عملة بعجز أخرى (هل هي عملية بيع لم تُسجَّل؟) قبل الحفظ. اترك الحقل فارغًا لعملة لم تعدّها.");
        if (!pend.isEmpty()) a.notice(R.drawable.ic_info, "في الصندوق نقد لحركات مكاتب بانتظار الاعتماد، وهو داخل في «المسجّل» أدناه لأنه موجود فعلًا. "
                + "لا تُسجَّل له تسوية، ولا يُحسب مرة ثانية عند اعتماده. إن رُفضت الحركة فأعد النقد لصاحبه أو سجّله من جديد.", false, null, null);
        final Map<String, EditText> fields = new LinkedHashMap<>();
        for (final String c : cfg.currencies) {
            LinearLayout card = a.card();
            final int d = dec(dec, c);
            final long b = bal.containsKey(c) ? bal.get(c) : 0;
            card.addView(a.text(c + " — المسجّل: " + iso(Model.fmt(b, d)), 15, a.ink, true));
            if (pend.containsKey(c)) card.addView(a.text("منه معلّق بانتظار الاعتماد: " + iso(Model.fmt(pend.get(c), d)), 12, a.goldInk, false));
            final EditText f = a.field("المعدود فعلًا");
            Ui.numeric(f);
            card.addView(f, a.lp(-1, 50, 2));
            final TextView df = a.text("", 13, a.muted, true);
            card.addView(df);
            f.addTextChangedListener(Ui.watcher(() -> {
                try {
                    long v = Db.minor(f.getText().toString(), d);
                    long diff = v - b;
                    df.setText(diff == 0 ? "مطابق ✓" : (diff > 0 ? "زيادة " : "عجز ") + iso(Model.fmt(Math.abs(diff), d) + " " + c));
                    df.setTextColor(diff == 0 ? a.green : diff > 0 ? a.primaryInk : a.red);
                } catch (Exception e) {
                    df.setText("");
                }
            }));
            fields.put(c, f);
        }
        final EditText note = a.input("ملاحظة (اختياري)");
        a.footer(a.makeButton("مراجعة الجرد", a.primary, v -> {
            try {
                final Map<String, Long> counted = new LinkedHashMap<>();
                for (Map.Entry<String, EditText> e : fields.entrySet()) {
                    String t = e.getValue().getText().toString().trim();
                    if (t.isEmpty()) continue;
                    counted.put(e.getKey(), Db.minor(t, dec(dec, e.getKey())));
                }
                if (counted.isEmpty()) throw new Exception("اكتب المعدود لعملة واحدة على الأقل");
                countReview(cfg, counted, note.getText().toString().trim());
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    private void countReview(final CashBox.Config cfg, final Map<String, Long> counted, final String note) {
        screen("مراجعة الجرد", this::count);
        final Map<String, Integer> dec = a.db.decimalsMap();
        Map<String, Long> bal = a.db.cashExpected(cfg.id);
        Map<String, BigDecimal> rates = a.db.usdRates();
        final List<CashBox.CountLine> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : counted.entrySet()) lines.add(new CashBox.CountLine(e.getKey(), bal.containsKey(e.getKey()) ? bal.get(e.getKey()) : 0, e.getValue()));
        LinearLayout g = a.group();
        BigDecimal up = BigDecimal.ZERO, down = BigDecimal.ZERO;
        for (CashBox.CountLine l : lines) {
            int d = dec(dec, l.currency);
            long df = l.diff();
            BigDecimal u = CashBox.usd(Math.abs(df), l.currency, rates, dec);
            if (u != null && df > 0) up = up.add(u);
            if (u != null && df < 0) down = down.add(u);
            a.groupRow(g, df == 0 ? R.drawable.ic_check : df > 0 ? R.drawable.ic_download : R.drawable.ic_upload, df == 0 ? a.green : df > 0 ? a.primaryInk : a.red,
                    l.currency + (df == 0 ? " — مطابق" : df > 0 ? " — زيادة" : " — عجز"),
                    "المسجّل " + iso(Model.fmt(l.recorded, d)) + " • المعدود " + iso(Model.fmt(l.counted, d)) + (u != null && df != 0 && !"USD".equals(l.currency) ? " • ≈ " + iso(u.setScale(2, RoundingMode.HALF_EVEN) + " $") : ""),
                    df == 0 ? "✓" : iso((df > 0 ? "+" : "−") + Model.fmt(Math.abs(df), d)), null);
        }
        List<String> missing = new ArrayList<>();
        BigDecimal net = CashBox.netUsd(lines, rates, dec, missing);
        LinearLayout c = a.card();
        c.addView(a.text("المقارنة بالدولار", 15, a.primaryInk, true));
        c.addView(a.text("مجموع الزيادة ≈ " + iso(up.setScale(2, RoundingMode.HALF_EVEN) + " $") + "   •   مجموع العجز ≈ " + iso(down.setScale(2, RoundingMode.HALF_EVEN) + " $"), 13, a.ink, false));
        c.addView(a.text(net.signum() == 0 ? "الصافي: متساويان تقريبًا" : (net.signum() > 0 ? "الصافي: زيادة ≈ " : "الصافي: عجز ≈ ") + iso(net.abs().setScale(2, RoundingMode.HALF_EVEN) + " $"),
                14, net.signum() < 0 ? a.red : a.green, true));
        if (!missing.isEmpty()) c.addView(a.text("بلا سعر صرف (لم تدخل المقارنة): " + Model.join(missing, "، "), 12, a.muted, false));

        final List<CashBox.Suggestion> sugg = CashBox.analyze(lines, rates, dec);
        final List<CheckBox> picks = new ArrayList<>();
        boolean internal = !cfg.strict || cfg.allowInternal;
        if (!sugg.isEmpty()) {
            a.section("هل هي عملية بيع لم تُسجَّل؟", null, null);
            for (CashBox.Suggestion s : sugg) {
                LinearLayout k = a.card();
                k.setBackground(a.round(a.goldSoft, a.accent, 16));
                k.addView(a.text("زيادة " + iso(Model.fmt(s.got, dec(dec, s.gotCur)) + " " + s.gotCur) + " (≈ " + iso(s.gotUsd + " $") + ") وعجز "
                        + iso(Model.fmt(s.sold, dec(dec, s.soldCur)) + " " + s.soldCur) + " (≈ " + iso(s.soldUsd + " $") + ")", 14, a.goldInk, true));
                k.addView(a.text("قيمتاهما متقاربتان (الفرق ≈ " + iso(s.gapUsd().abs().setScale(2, RoundingMode.HALF_EVEN) + " $") + "): الأرجح أنك بعت "
                        + iso(Model.fmt(s.sold, dec(dec, s.soldCur)) + " " + s.soldCur) + " بـ " + iso(Model.fmt(s.got, dec(dec, s.gotCur)) + " " + s.gotCur)
                        + " بسعر " + iso("1 " + s.soldCur + " = " + s.rate.toPlainString() + " " + s.gotCur) + " ولم تسجّل العملية.", 13, a.ink, false));
                CheckBox cb = new CheckBox(a);
                cb.setText("سجّلها عملية بيع (بدل تسويتي عجز وزيادة)");
                cb.setEnabled(internal);
                picks.add(cb);
                k.addView(cb);
            }
            if (!internal) a.hint("الربط الإلزامي لا يسمح بالبيع والشراء داخل الصندوق؛ تُسجَّل الفروق تسويات جرد.");
        } else if (up.signum() > 0 && down.signum() > 0) {
            a.notice(R.drawable.ic_info, "في الجرد زيادة وعجز بعملات مختلفة لكن قيمتيهما غير متقاربتين، فلا تبدو عملية بيع واحدة. راجع الحركات قبل الحفظ.", false, null, null);
        }
        a.hint("عند الحفظ: يُحفظ الجرد في «سجل الجرد» كما هو (المسجّل والمعدود لكل عملة)، وما بقي من فروق يُسجَّل «تسوية جرد» فيطابق الصندوق المعدود.");
        a.footer(a.makeButton("حفظ الجرد", a.primary, v -> {
            final List<CashBox.Suggestion> chosen = new ArrayList<>();
            for (int i = 0; i < sugg.size(); i++) if (picks.get(i).isChecked()) chosen.add(sugg.get(i));
            a.busy("جارٍ حفظ الجرد…", p -> a.db.cashCount(cfg.id, counted, note, chosen), res -> {
                CashBox.Count r = (CashBox.Count) res;
                a.toast(r.adjusted == 0 && r.sales.isEmpty() ? "الصندوق مطابق للمعدود ✓"
                        : (r.sales.isEmpty() ? "" : "سُجّل البيع، ") + (r.adjusted > 0 ? r.adjusted + " تسوية جرد، " : "") + "والصندوق الآن مطابق للمعدود");
                countLog();
            });
        }));
    }

    void countLog() {
        final CashBox.Config cfg = a.db.cashConfig();
        screen("سجل الجرد — " + cfg.name, this::home);
        if (a.can(Auth.Perm.CASH) && a.can(Auth.Perm.CASH_COUNT)) a.headerAction(R.drawable.ic_plus, "جرد جديد", v -> count());
        final Map<String, Integer> dec = a.db.decimalsMap();
        List<CashBox.Count> log = a.db.cashCounts(cfg.id);
        if (log.isEmpty()) {
            LinearLayout c = a.card();
            c.addView(a.text("لم يُسجَّل أي جرد بعد", 16, a.ink, true));
            c.addView(a.text("كل جرد تحفظه يظهر هنا بالمسجّل والمعدود والعجز والزيادة لكل عملة.", 13, a.muted, false));
            if (a.can(Auth.Perm.CASH) && a.can(Auth.Perm.CASH_COUNT)) c.addView(a.makeButton("جرد الآن", a.primary, v -> count()), a.lp(-1, 48, 6));
            return;
        }
        for (final CashBox.Count k : log) {
            LinearLayout c = a.card();
            c.addView(a.text(Model.date(k.createdAt) + (k.userName.isEmpty() ? "" : "  •  " + k.userName), 14, a.ink, true));
            Ui.Flow f = a.flow();
            for (CashBox.CountLine l : k.lines) {
                long df = l.diff();
                int d = dec(dec, l.currency);
                f.addView(df == 0 ? a.chip(l.currency + " مطابق", a.green, a.creditBg)
                        : a.chip(iso((df > 0 ? "زيادة " : "عجز ") + Model.fmt(Math.abs(df), d) + " " + l.currency), df > 0 ? a.primaryInk : a.red, df > 0 ? a.primarySoft : a.debitBg));
            }
            c.addView(f);
            String net = Model.nz(k.netUsd);
            if (!net.isEmpty()) {
                BigDecimal n = new BigDecimal(net);
                c.addView(a.text(n.signum() == 0 ? "الصافي بالدولار: صفر" : "الصافي بالدولار: " + (n.signum() > 0 ? "زيادة ≈ " : "عجز ≈ ") + iso(n.abs().setScale(2, RoundingMode.HALF_EVEN) + " $"),
                        12, n.signum() < 0 ? a.red : a.muted, true));
            }
            if (!k.sales.isEmpty()) c.addView(a.text("سُجّل بيعًا: " + k.sales, 12, a.goldInk, false));
            if (!k.note.isEmpty()) c.addView(a.text("ملاحظة: " + k.note, 12, a.muted, false));
            c.setOnClickListener(v -> {
                List<String[]> rows = new ArrayList<>();
                rows.add(new String[]{"التاريخ", Model.date(k.createdAt)});
                rows.add(new String[]{"بواسطة", k.userName});
                for (CashBox.CountLine l : k.lines) {
                    int d = dec(dec, l.currency);
                    rows.add(new String[]{null, l.currency});
                    rows.add(new String[]{"المسجّل", iso(Model.fmt(l.recorded, d))});
                    rows.add(new String[]{"المعدود", iso(Model.fmt(l.counted, d))});
                    rows.add(new String[]{"الفرق", l.diff() == 0 ? "مطابق" : (l.diff() > 0 ? "زيادة " : "عجز ") + iso(Model.fmt(Math.abs(l.diff()), d))});
                }
                if (!k.sales.isEmpty()) rows.add(new String[]{"سُجّل بيعًا", k.sales});
                rows.add(new String[]{"تسويات الجرد", "" + k.adjusted});
                if (!k.note.isEmpty()) rows.add(new String[]{"ملاحظة", k.note});
                new AlertDialog.Builder(a).setView(detailView("جرد", rows, null)).setPositiveButton("إغلاق", null).show();
            });
        }
    }

    // ===================================================================================
    // الإعداد: العملات (كلها، مع أسعار الإنترنت)، الصناديق، القفل، الربط
    // ===================================================================================

    /** مسودة الإعداد تبقى عند إعادة رسم الشاشة (تحديث الأسعار أو إضافة عملة). */
    private Set<String> draftChecks;
    private Map<String, String> draftOpen;
    private String draftName;

    void setup(final boolean first) {
        if (first && !a.can(Auth.Perm.SETTINGS)) {
            tabbed("صندوق المكتب", null);
            a.notice(R.drawable.ic_info, "الصندوق لم يُعَد بعد. يعدّه المدير من هذه الشاشة.", false, null, null);
            return;
        }
        if (!a.require(Auth.Perm.SETTINGS)) return;
        final CashBox.Config cfg = a.db.cashConfig();
        if (first) tabbed("إعداد صندوق المكتب", null);
        else screen("إعدادات «" + cfg.name + "»", () -> {
            draftChecks = null;
            home();
        });
        if (first) {
            LinearLayout intro = a.card();
            intro.addView(a.text("صندوقك: النقد الموجود لديك فعلًا", 17, a.primaryInk, true));
            intro.addView(a.text("حسابات الصندوق مستقلة عن أرصدة المكاتب ولا تؤثر عليها. تسجّل فيه ما يدخل ويخرج من النقد بكل عملة، "
                    + "مع خانة للأجور. وحين تسلّم أو تستلم لحساب مكتب أو عميل يُسجَّل على حسابه في الوقت نفسه. "
                    + "وتبيع وتشتري العملات، وتضيف المصاريف، وتجرد الصندوق متى شئت.", 14, a.ink, false));
        }
        a.title("اسم الصندوق");
        final EditText name = a.input(CashBox.DEFAULT_NAME);
        name.setText(draftName != null ? draftName : cfg.on ? cfg.name : "");
        final Map<String, CheckBox> checks = new LinkedHashMap<>();
        final Map<String, EditText> opens = new LinkedHashMap<>();
        currencyPicker(cfg.on ? new TreeSet<>(cfg.currencies) : null, cfg.on ? a.db.cashBalances(cfg.id) : new HashMap<>(), checks, opens, first,
                () -> {
                    draftName = name.getText().toString();
                    setup(first);
                });

        final Switch[] link = linkSection(cfg.strict, cfg.allowInternal);
        final Switch strict = link[0], internal = link[1];
        if (!first) {
            lockSection();
            boxesSection();
            a.title("بنود المصاريف");
            a.hint(Model.join(cfg.expenseCats, " • "));
            a.body.addView(a.smallButton("+ بند مصروف جديد", Color.rgb(130, 130, 130), v -> newCategory(CashBox.EXPENSE, () -> setup(false))), a.lp(-2, 38, 2));
        }
        a.footer(a.makeButton(first ? "إنشاء الصندوق" : "حفظ", a.primary, v -> {
            try {
                List<String> codes = new ArrayList<>();
                Map<String, Long> openings = openingsOf(checks, opens, codes);
                if (codes.isEmpty()) throw new Exception("اختر عملة واحدة على الأقل");
                a.db.cashSetup(cfg.id, name.getText().toString(), codes, openings, strict.isChecked(), internal.isChecked());
                draftChecks = null;
                draftOpen = null;
                draftName = null;
                a.toast(first ? "أُنشئ الصندوق ✓" : "حُفظت الإعدادات");
                open = true;
                home();
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    private Map<String, Long> openingsOf(Map<String, CheckBox> checks, Map<String, EditText> opens, List<String> codes) {
        Map<String, Long> openings = new LinkedHashMap<>();
        for (Map.Entry<String, CheckBox> e : checks.entrySet()) {
            if (!e.getValue().isChecked()) continue;
            codes.add(e.getKey());
            EditText op = opens.get(e.getKey());
            if (op != null && !op.getText().toString().trim().isEmpty()) openings.put(e.getKey(), Db.minor(op.getText().toString(), a.db.decimals(e.getKey())));
        }
        return openings;
    }

    /** نص سعر العملة: «1 EUR = 1.08 USD» أو «1 USD = 13,000 SYP» للعملات الصغيرة. */
    String rateText(String code, Db.Rate r) {
        if ("USD".equals(code)) return "العملة الأساس";
        if (r == null || r.usd == null) return "بلا سعر صرف";
        String src = r.at > 0 ? " — " + r.source + " " + MainActivity.ago(r.at) : " — " + r.source;
        if (r.usd.compareTo(BigDecimal.ONE) >= 0) return iso("1 " + code + " = " + r.usd.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + " USD") + src;
        BigDecimal inv = BigDecimal.ONE.divide(r.usd, 4, RoundingMode.HALF_EVEN);
        return iso("1 USD = " + Model.fmt(inv.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValue(), 2).replaceAll("\\.00$", "") + " " + code) + src;
    }

    /**
     * 9.0.1: كل عملات التطبيق (المفعّلة وغير المفعّلة) مع سعر صرفها، وتحديث الأسعار من الإنترنت، وإضافة أي عملة أخرى من قائمة الأسعار.
     * selected: العملات المختارة حاليًا (null = إعداد أول)؛ withOpen: حقول الرصيد الافتتاحي للعملات الجديدة.
     */
    void currencyPicker(Set<String> selected, Map<String, Long> bal, Map<String, CheckBox> checks, Map<String, EditText> opens, boolean first, final Runnable redraw) {
        a.title("العملات والرصيد الموجود الآن");
        a.hint(selected == null ? "اختر عملات الصندوق، واكتب النقد الموجود لديك الآن بكل عملة (الرصيد الافتتاحي)."
                : "يمكن إضافة عملة مع رصيدها الافتتاحي. لا تُزال عملة رصيدها ليس صفرًا.");
        Map<String, Db.Rate> rates = a.db.rates();
        for (Model.Currency c : a.db.currencies(true)) {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4));
            box.setBackground(a.round(a.surface, a.line, 12));
            LinearLayout r = a.row();
            CheckBox cb = new CheckBox(a);
            cb.setText(c.code + " — " + c.name + (c.active ? "" : " (غير مفعّلة في المكاتب)"));
            boolean had = selected != null && selected.contains(c.code);
            cb.setChecked(draftChecks != null ? draftChecks.contains(c.code) : had);
            r.addView(cb, new LinearLayout.LayoutParams(0, -2, 1.3f));
            if (!had) {
                EditText op = a.field("الموجود الآن");
                Ui.numeric(op);
                op.setTextSize(14);
                if (draftOpen != null && draftOpen.containsKey(c.code)) op.setText(draftOpen.get(c.code));
                r.addView(op, a.weight(46, 1, 2));
                opens.put(c.code, op);
            } else {
                Long b = bal.get(c.code);
                r.addView(a.text("الرصيد " + iso(Model.fmt(b == null ? 0 : b, c.decimals)), 13, a.muted, false), new LinearLayout.LayoutParams(0, -2, 1));
            }
            box.addView(r);
            box.addView(a.text(rateText(c.code, rates.get(c.code)), 11, a.muted, false));
            a.body.addView(box, a.lp(-1, -2, 2));
            checks.put(c.code, cb);
        }
        final Runnable keep = () -> {
            draftChecks = new TreeSet<>();
            draftOpen = new HashMap<>();
            for (Map.Entry<String, CheckBox> e : checks.entrySet()) if (e.getValue().isChecked()) draftChecks.add(e.getKey());
            for (Map.Entry<String, EditText> e : opens.entrySet()) draftOpen.put(e.getKey(), e.getValue().getText().toString());
        };
        LinearLayout btns = a.row();
        if (a.can(Auth.Perm.RATES_ONLINE)) btns.addView(a.smallButton("تحديث الأسعار من الإنترنت", Color.rgb(130, 130, 130), v -> {
            keep.run();
            a.busy("جارٍ تحديث الأسعار…", p -> ExchangeRates.refresh(a.db), res -> {
                a.toast("حُدّثت أسعار " + res + " عملة");
                redraw.run();
            });
        }), a.weight(40, 1, 2));
        if (a.can(Auth.Perm.CURRENCIES)) btns.addView(a.smallButton("+ عملة أخرى", Color.rgb(130, 130, 130), v -> {
            keep.run();
            addWorldCurrency(redraw);
        }), a.weight(40, 1, 2));
        a.body.addView(btns, a.lp(-1, -2, 4));
    }

    /** يضيف للتطبيق عملة من قائمة أسعار الإنترنت (أو من القائمة المعروفة) باسمها العربي ومنازلها. */
    private void addWorldCurrency(final Runnable after) {
        Set<String> have = new TreeSet<>();
        for (Model.Currency c : a.db.currencies(true)) have.add(c.code);
        Set<String> codes = new TreeSet<>(a.db.rates().keySet());
        codes.addAll(WorldCurrencies.ALL.keySet());
        codes.removeAll(have);
        final List<String> known = new ArrayList<>(), rest = new ArrayList<>();
        for (String c : codes) {
            if (!c.matches("[A-Z]{3}")) continue;
            if (WorldCurrencies.ALL.containsKey(c)) known.add(c);
            else rest.add(c);
        }
        final List<String> all = new ArrayList<>(known);
        all.addAll(rest);
        if (all.isEmpty()) {
            a.toast("كل العملات المعروفة مضافة. حدّث الأسعار من الإنترنت لتظهر عملات أكثر.");
            return;
        }
        final String[] labels = new String[all.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = all.get(i) + " — " + WorldCurrencies.name(all.get(i));
        new AlertDialog.Builder(a).setTitle("إضافة عملة (" + all.size() + ")").setItems(labels, (d, w) -> {
            String code = all.get(w);
            try {
                a.db.saveCurrency(code, WorldCurrencies.name(code), WorldCurrencies.decimals(code), true);
                if (draftChecks != null) draftChecks.add(code);
                a.toast("أُضيفت " + code + " — " + WorldCurrencies.name(code) + " (" + WorldCurrencies.decimals(code) + " منازل)");
                after.run();
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    private void lockSection() {
        a.title("قفل الصندوق");
        final String mode = lockMode();
        final Ui.Seg s = a.seg(new String[]{"بدون", "البصمة", "كلمة سر"}, LOCK_BIO.equals(mode) ? 1 : LOCK_PIN.equals(mode) ? 2 : 0, null);
        s.listener = i -> {
            if (i == 0) {
                a.db.setSettingQuiet("cash_lock", LOCK_OFF);
                a.toast("أُلغي قفل الصندوق");
            } else if (i == 1) {
                bio("تفعيل قفل الصندوق", () -> {
                    a.db.setSettingQuiet("cash_lock", LOCK_BIO);
                    authAt = SystemClock.elapsedRealtime();
                    a.toast("يُفتح الصندوق بالبصمة أو بقفل الشاشة ✓");
                    setup(false);
                });
                s.set(LOCK_BIO.equals(mode) ? 1 : LOCK_PIN.equals(mode) ? 2 : 0, false);
            } else {
                newPin(() -> setup(false));
                s.set(LOCK_BIO.equals(mode) ? 1 : LOCK_PIN.equals(mode) ? 2 : 0, false);
            }
        };
        a.hint("«البصمة» تقبل أيضًا قفل الشاشة (الرمز أو النمط). يُطلب القفل عند فتح الصندوق من أي مكان، وبعد الخروج من التطبيق أكثر من 30 ثانية. القفل خاص بهذا الهاتف.");
    }

    private void boxesSection() {
        a.title("الصناديق");
        LinearLayout g = a.group();
        long cur = a.db.currentBox();
        Map<String, Integer> dec = a.db.decimalsMap();
        Map<String, BigDecimal> rates = a.db.usdRates();
        for (final CashBox.Config b : a.db.cashBoxes()) {
            Ledger.Valuation v = Ledger.valueOffice(a.db.cashBalances(b.id), rates, dec);
            String sub = Model.join(b.currencies, " • ") + (b.strict ? " — مربوط بالمكاتب" : " — حر") + (b.active ? "" : " — معطّل");
            a.groupRow(g, R.drawable.ic_cash, b.active ? a.primaryInk : a.muted, b.name + (b.id == cur ? "  (المفتوح)" : ""), sub,
                    a.m("≈ " + Model.fmt(v.netUsd().setScale(0, RoundingMode.HALF_EVEN).longValue(), 0) + " $"), x -> boxMenu(b));
        }
        a.body.addView(a.smallButton("+ صندوق جديد", Color.rgb(130, 130, 130), v -> boxForm(null)), a.lp(-2, 38, 2));
    }

    /** 10.0: ربط هذا الصندوق بالمكاتب (لكل صندوق ربطه). يرجع مفتاحي الربط والسماح. */
    private Switch[] linkSection(boolean strictNow, boolean internalNow) {
        a.title("ربط هذا الصندوق بالمكاتب");
        final Switch strict = new Switch(a);
        strict.setText("مربوط: لا يدخل هذا الصندوق ولا يخرج منه إلا لحساب مكتب أو عميل");
        strict.setChecked(strictNow);
        a.body.addView(strict, a.lp(-1, -2, 4));
        final Switch internal = new Switch(a);
        internal.setText("مع الربط: السماح بالمصاريف والبيع والشراء داخل الصندوق");
        internal.setChecked(internalNow);
        a.body.addView(internal, a.lp(-1, -2, 4));
        internal.setVisibility(strictNow ? View.VISIBLE : View.GONE);
        strict.setOnCheckedChangeListener((x, on) -> internal.setVisibility(on ? View.VISIBLE : View.GONE));
        a.hint("حر (غير مربوط): تسجّل فيه الاستلام والتسليم لحساب مكتب أو عميل أو بدون حساب (مثل إيداع من صاحب المكتب أو سحب له). "
                + "الجرد مسموح دائمًا. لكل صندوق ربطه: مثلًا صندوق المحل مربوط بالمكاتب، وخزنة البيت حرة.");
        return new Switch[]{strict, internal};
    }

    private void boxMenu(final CashBox.Config b) {
        final List<String> acts = new ArrayList<>();
        if (b.active) acts.add("فتحه");
        if (b.active) acts.add("إعداداته (الاسم والعملات والربط)");
        acts.add(b.active ? "تعطيله (رصيده صفر)" : "تفعيله");
        acts.add("حذفه نهائيًا");
        new AlertDialog.Builder(a).setTitle(b.name).setItems(acts.toArray(new String[0]), (d, w) -> {
            String s = acts.get(w);
            try {
                if (s.startsWith("فتح")) {
                    a.db.setCurrentBox(b.id);
                    home();
                } else if (s.startsWith("إعداد")) {
                    a.db.setCurrentBox(b.id);
                    setup(false);
                } else if (s.startsWith("تعطيل")) {
                    a.db.setCashBoxActive(b.id, false);
                    a.toast("عُطّل «" + b.name + "» — يبقى تاريخه في التقارير");
                    setup(false);
                } else if (s.startsWith("حذف")) {
                    String why = a.db.cashBoxDeleteBlock(b.id);
                    if (why != null) {
                        new AlertDialog.Builder(a).setTitle("لا يمكن حذف «" + b.name + "»").setMessage(why).setPositiveButton("حسنًا", null).show();
                        return;
                    }
                    a.confirm("حذف «" + b.name + "» نهائيًا", "يُحذف الصندوق بكل حركاته وسجل جرده، ولا يمكن التراجع (إلا من نسخة احتياطية). رصيده صفر ولا ترتبط حركاته بمكاتب أو عملاء.",
                            "حذف نهائيًا", () -> {
                                try {
                                    a.db.deleteCashBox(b.id);
                                    a.toast("حُذف «" + b.name + "»");
                                    setup(false);
                                } catch (Exception e) {
                                    a.error(e);
                                }
                            });
                } else {
                    a.db.setCashBoxActive(b.id, true);
                    setup(false);
                }
            } catch (Exception e) {
                a.error(e);
            }
        }).show();
    }

    /** صندوق جديد: الاسم والعملات والرصيد الموجود فيه الآن. */
    void boxForm(final Runnable unused) {
        if (!a.require(Auth.Perm.SETTINGS)) return;
        screen("صندوق جديد", () -> {
            draftChecks = null;
            home();
        });
        a.hint("صندوق آخر لك بنقده المستقل (مثل: صندوق الفرع، خزنة البيت). تنقل النقد بينه وبين الصناديق الأخرى بـ«تحويل».");
        a.title("اسم الصندوق");
        final EditText name = a.input("مثل: خزنة البيت");
        if (draftName != null) name.setText(draftName);
        final Map<String, CheckBox> checks = new LinkedHashMap<>();
        final Map<String, EditText> opens = new LinkedHashMap<>();
        currencyPicker(null, new HashMap<>(), checks, opens, true, () -> {
            draftName = name.getText().toString();
            boxForm(null);
        });
        final Switch[] link = linkSection(false, true);
        a.footer(a.makeButton("إنشاء الصندوق", a.primary, v -> {
            try {
                List<String> codes = new ArrayList<>();
                Map<String, Long> openings = openingsOf(checks, opens, codes);
                if (codes.isEmpty()) throw new Exception("اختر عملة واحدة على الأقل");
                long id = a.db.addCashBox(name.getText().toString(), codes, openings, link[0].isChecked(), link[1].isChecked());
                a.db.setCurrentBox(id);
                draftChecks = null;
                draftOpen = null;
                draftName = null;
                a.toast("أُنشئ الصندوق ✓");
                home();
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    // ===================================================================================
    // التقرير
    // ===================================================================================

    void reportFor(final long from, final long to, final Runnable back) {
        guard(() -> {
            final CashBox.ReportSpec sp = new CashBox.ReportSpec();
            sp.from = from;
            sp.to = to;
            a.busy("جارٍ بناء تقرير الصندوق…", p -> build(sp), res -> {
                final Report r = (Report) res;
                screen(r.title, back);
                a.reportsScreen.periodCard(() -> reportFor(a.reportsScreen.range()[0], a.reportsScreen.range()[1], back));
                LinearLayout bar = a.row();
                bar.addView(a.smallButton("تصدير PDF / Excel", a.primary, v -> a.exportUi.reportDialog(r.title, () -> reportFor(from, to, back),
                        (images, st) -> Collections.singletonList(build(sp)))), a.weight(44, 1, 2));
                a.body.addView(bar, a.lp(-1, -2, 4));
                ReportRender.render(a, r);
            });
        });
    }

    Report build(CashBox.ReportSpec sp) {
        CashBox.Config cfg = a.db.cashConfig();
        Report r = CashBox.report(a.db.cashMoves(cfg.id), sp, a.db.decimalsMap(), a.db.officeNames(), cfg.name, a.db.setting("office_name", ""));
        Entitlements e = License.current;
        r.stamp(e.watermark(), e.lid);
        return r;
    }

    // ===================================================================================
    // بطاقة الصفحة الرئيسية
    // ===================================================================================

    void homeCard() {
        CashBox.Config cfg = a.db.cashConfig();
        if (!cfg.on || !a.can(Auth.Perm.CASH)) return;
        boolean locked = !LOCK_OFF.equals(lockMode());
        Map<String, Long> bal = a.db.cashBalances(cfg.id);
        Map<String, Integer> dec = a.db.decimalsMap();
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, a.night ? new int[]{Color.rgb(28, 44, 72), Color.rgb(14, 22, 40)} : new int[]{NAVY, NAVY2});
        g.setCornerRadius(a.dp(18));
        c.setBackground(a.ripple(g, 18));
        LinearLayout h = a.row();
        h.addView(a.icon(R.drawable.ic_cash, GOLD, 20), new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
        int nBoxes = activeBoxes().size();
        h.addView(plain("  " + cfg.name + (nBoxes > 1 ? "  (" + nBoxes + " صناديق)" : ""), 15, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (locked) h.addView(a.icon(R.drawable.ic_lock, GOLD, 18), new LinearLayout.LayoutParams(a.dp(18), a.dp(18)));
        c.addView(h);
        Ui.Flow f = a.flow();
        // الصندوق المقفل لا يكشف أرصدته في الصفحة الرئيسية
        boolean hide = locked || a.amountsHidden();
        for (Map.Entry<String, Long> e : bal.entrySet()) {
            TextView t = plain(hide ? "•••• " + e.getKey() : iso(Model.fmt(e.getValue(), dec(dec, e.getKey())) + " " + e.getKey()), 15,
                    e.getValue() < 0 && !hide ? Color.rgb(255, 170, 170) : Color.WHITE, true);
            t.setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4));
            GradientDrawable cg = new GradientDrawable();
            cg.setColor(Color.argb(40, 255, 255, 255));
            cg.setCornerRadius(a.dp(50));
            t.setBackground(cg);
            f.addView(t);
        }
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(0, a.dp(6), 0, 0);
        c.addView(f, fp);
        c.setOnClickListener(v -> show());
        a.body.addView(c, a.lp(-1, -2, 5));
    }
}
