package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import javax.swing.*;

/** التقرير اليومي مع «بصمة اليوم» (رمز HMAC يكشف أي تعديل في التقرير بعد خروجه)، والتحقق من بصمة تقرير مطبوع. */
final class DailyView extends View {
    final Ui.DateField day = new Ui.DateField(Clock.now());
    final JPanel out = Ui.column();

    DailyView(App app) {
        super(app, "التقرير اليومي");
    }

    @Override void build() {
        Ui.Card c = Ui.card();
        c.add(Ui.row(Ui.label("اليوم", 14, Ui.muted, true), day, Ui.ghost("‹ السابق", e -> shift(-1)), Ui.ghost("التالي ›", e -> shift(1)),
                Ui.ghost("اليوم", e -> {
                    day.set(Clock.now());
                    refresh();
                })));
        body.add(c);
        body.add(Ui.gap(14));
        body.add(out);
        day.onChange = this::refresh;
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);
    }

    void shift(int d) {
        try {
            long s = day.start();
            day.set(Ui.addDays(s == 0 ? Ui.startOfDay(Clock.now()) : s, d));
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    long from() {
        try {
            long s = day.start();
            return s == 0 ? Ui.startOfDay(Clock.now()) : s;
        } catch (Exception e) {
            return Ui.startOfDay(Clock.now());
        }
    }

    Report report(long from, long to) {
        Db.TxFilter f = new Db.TxFilter();
        f.from = from;
        f.to = to;
        f.includeCancelled = true;
        Report r = Reports.daily(Model.day(from), db.dailyStats(from, to), db.txLog(f), db.officeNames(), db.decimalsMap(), db.setting("office_name", ""), Clock.now());
        try {
            r.headerLines.add("بصمة اليوم: " + db.dayCode(from, to) + " — للتحقق: التقرير اليومي ← «التحقق من بصمة تقرير»");
        } catch (Exception ignored) {
            // بلا بصمة
        }
        return r;
    }

    @Override void refresh() {
        actions.removeAll();
        long from = from(), to = Ui.addDays(from, 1);
        action(Ui.primary("تصدير / طباعة…", e -> Export.dialog(this, app, "التقرير اليومي " + Model.day(from), () -> report(from, to))));
        action(Ui.ghost("التحقق من بصمة تقرير", e -> verify(from)));
        out.removeAll();
        try {
            subtitle.setText("بصمة اليوم: " + db.dayCode(from, to));
            out.add(ReportPanel.render(report(from, to), textWidth()));
        } catch (Exception e) {
            out.add(Ui.label(Ui.friendly(e), 14, Ui.red, false));
        }
        Ui.rtl(out);
        out.revalidate();
        out.repaint();
        done();
    }

    void verify(long day) {
        String code = Ui.input(this, "التحقق من بصمة تقرير يوم " + Model.day(day),
                "اكتب البصمة المطبوعة في أعلى التقرير اليومي ليوم " + Model.day(day) + ". (لتقرير يوم آخر اختر اليوم أولًا.)", "");
        if (code == null) return;
        String want = db.dayCode(day, Ui.addDays(day, 1));
        boolean ok = want.equals(DayClose.normalize(code));
        db.audit("DAY_CODE_VERIFY", "daily", day, ok ? "ok" : "mismatch");
        Ui.message(this, ok ? "مطابقة ✓" : "غير مطابقة", ok ? "التقرير يطابق حركات ذلك اليوم كما هي في البرنامج: لم يُعدَّل بعد إصداره."
                : "البصمة لا تطابق. إما أن الملف عُدّل بعد خروجه من البرنامج، أو أن حركة من ذلك اليوم أُلغيت أو عُدّلت بعد إصدار التقرير (راجع سجل العمليات)، أو أن الرمز كُتب خطأً.",
                ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }
}
