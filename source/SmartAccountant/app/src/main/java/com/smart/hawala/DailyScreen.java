package com.smart.hawala;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.widget.LinearLayout;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** التقرير اليومي: مجاميع اليوم لكل عملة (لنا، علينا، الأجور، الصافي، العدد) وحركات اليوم، مع التصدير. */
final class DailyScreen {
    private final MainActivity a;

    DailyScreen(MainActivity a) { this.a = a; }

    static long dayStart(long t) {
        Calendar c = Model.cal();
        c.setTimeInMillis(t);
        return StatementScreen.startOfDay(c);
    }

    static long nextDay(long start) {
        Calendar c = Model.cal();
        c.setTimeInMillis(start);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return StatementScreen.startOfDay(c);
    }

    static long prevDay(long start) {
        Calendar c = Model.cal();
        c.setTimeInMillis(start);
        c.add(Calendar.DAY_OF_MONTH, -1);
        return StatementScreen.startOfDay(c);
    }

    void show(final long anyTime) {
        if (!a.require(Auth.Perm.VIEW) || !a.require(Auth.Perm.REPORTS)) return;   // 12.0
        final long from = dayStart(anyTime);
        final long to = nextDay(from);
        a.here(() -> show(anyTime));
        a.tabbedScreen("التقرير اليومي", a::showHome, "home");
        LinearLayout nav = a.row();
        a.body.addView(nav, a.lp(-1, -2, 4));
        nav.addView(a.smallButton("اليوم السابق", Color.rgb(120, 120, 120), v -> show(prevDay(from))), a.weight(46, 1, 2));
        nav.addView(a.smallButton(Model.day(from), a.accent, v -> {
            Calendar c = Model.cal();
            c.setTimeInMillis(from);
            DatePickerDialog dp = new DatePickerDialog(a, (x, y, m, d) -> {
                Calendar s = Model.cal();
                s.set(y, m, d);
                show(s.getTimeInMillis());
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(Clock.now());
            dp.show();
        }), a.weight(46, 1.2f, 2));
        boolean isToday = to > Clock.now();
        nav.addView(a.smallButton("اليوم التالي", isToday ? Color.LTGRAY : Color.rgb(120, 120, 120), v -> {
            if (!isToday) show(to);
        }), a.weight(46, 1, 2));

        Map<String, long[]> stats = a.db.dailyStats(from, to);
        LinearLayout c = a.card();
        c.addView(a.text("مجاميع " + (isToday ? "اليوم" : Model.day(from)), 18, a.primary, true));
        if (stats.isEmpty()) c.addView(a.text("لا توجد حركات في هذا اليوم.", 15, a.muted, false));
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            int d = a.db.decimals(e.getKey());
            long[] v = e.getValue();
            StringBuilder sb = new StringBuilder(e.getKey() + "   (" + v[4] + " حركة)");
            sb.append("\nحركات لنا: ").append(Model.fmt(v[0], d)).append("   •   حركات علينا: ").append(Model.fmt(v[1], d));
            if (v[2] > 0 || v[3] > 0) sb.append("\nأجور لنا: ").append(Model.fmt(v[2], d)).append("   •   أجور علينا: ").append(Model.fmt(v[3], d));
            sb.append("\nصافي اليوم: ").append(Model.bal(v[0] - v[1] + v[2] - v[3], d));
            c.addView(a.text(sb.toString(), 15, a.dark, false));
        }
        c.addView(a.text("الحركات الملغاة غير محسوبة.", 12, a.muted, false));
        try {
            // 8.0 (ت5): بصمة اليوم تُطبع على التقرير المصدَّر؛ أي تعديل في الملف بعد خروجه يُكشف هنا
            c.addView(a.text("بصمة اليوم: \u2066" + a.db.dayCode(from, to) + "\u2069", 14, a.primaryInk, true));
        } catch (Exception e) {
            c.addView(a.text("تعذر حساب بصمة اليوم: " + Ui.friendly(e), 12, a.red, false));
        }
        LinearLayout vr = a.row();
        vr.addView(a.smallButton("التحقق من بصمة تقرير", a.accent, v -> verifyDialog(from)), a.weight(42, 1, 2));
        a.body.addView(vr, a.lp(-1, -2, 2));

        Db.TxFilter f = new Db.TxFilter();
        f.from = from;
        f.to = to;
        f.includeCancelled = true;
        final List<Model.Tx> txs = a.db.txLog(f);
        if (!txs.isEmpty()) {
            a.button("تصدير التقرير اليومي (PDF / صور / Excel)", a.primary, v -> a.exportUi.dailyDialog(from, to));
        }
        Map<Long, String> names = a.db.officeNames();
        Map<Long, Integer> att = a.db.attachmentCounts(null);
        for (final Model.Tx t : txs) {
            LinearLayout x = a.card();
            x.addView(a.logScreen.txRow(t, names.get(t.officeId), att.get(t.id)));
            x.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
            x.setOnClickListener(v -> a.logScreen.details(t, () -> show(from)));
        }
    }

    Report report(long from, long to) {
        Db.TxFilter f = new Db.TxFilter();
        f.from = from;
        f.to = to;
        f.includeCancelled = true;
        Report r = Reports.daily(Model.day(from), a.db.dailyStats(from, to), a.db.txLog(f), a.db.officeNames(), a.db.decimalsMap(),
                a.db.setting("office_name", ""), Clock.now());
        try {
            r.headerLines.add("بصمة اليوم: " + a.db.dayCode(from, to) + " — للتحقق: التقرير اليومي ← «التحقق من بصمة تقرير»");
        } catch (Exception ignored) {
            // التقرير يُصدَّر بلا بصمة
        }
        return r;
    }

    /** يكتب المستخدم تاريخ التقرير وبصمته المطبوعة؛ يعيد التطبيق الحساب من الحركات ويقارن. */
    private void verifyDialog(long day) {
        final android.widget.EditText code = new android.widget.EditText(a);
        code.setHint("XXXX-XXXX-XXXX");
        code.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(code, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(a).setTitle("التحقق من بصمة تقرير يوم " + Model.day(day))
                .setMessage("اكتب البصمة المطبوعة في أعلى التقرير اليومي ليوم " + Model.day(day) + ". (لتقرير يوم آخر اختر اليوم أولًا من الأعلى.)")
                .setView(l).setPositiveButton("تحقق", (d, w) -> {
                    String want = a.db.dayCode(day, nextDay(day));
                    boolean ok = want.equals(DayClose.normalize(code.getText().toString()));
                    a.db.audit("DAY_CODE_VERIFY", "daily", day, ok ? "ok" : "mismatch");
                    new android.app.AlertDialog.Builder(a).setTitle(ok ? "مطابقة ✓" : "غير مطابقة")
                            .setMessage(ok ? "التقرير يطابق حركات ذلك اليوم كما هي في التطبيق: لم يُعدَّل بعد إصداره."
                                    : "البصمة لا تطابق. إما أن الملف عُدّل بعد خروجه من التطبيق، أو أن حركة من ذلك اليوم أُلغيت أو عُدّلت بعد إصدار التقرير (راجع سجل العمليات)، أو أن الرمز كُتب خطأً.")
                            .setPositiveButton("حسنًا", null).show();
                }).setNegativeButton("إلغاء", null).show();
    }
}
