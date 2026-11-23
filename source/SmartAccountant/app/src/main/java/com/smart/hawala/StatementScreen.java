package com.smart.hawala;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** كشف المكتب: الأرصدة المفصلة لكل عملة، الفترة، الحركات (تحديد/تفاصيل/تعديل/إلغاء)، والتصدير. */
final class StatementScreen {
    private final MainActivity a;

    StatementScreen(MainActivity a) { this.a = a; }

    static final class Period {
        final String label;
        final long from, to;

        Period(String label, long from, long to) {
            this.label = label;
            this.from = from;
            this.to = to;
        }

        static Period all() { return new Period("كل الفترات", 0, Long.MAX_VALUE); }

        boolean hasStart() { return from > 0; }
    }

    interface OnPeriod { void on(Period p); }

    static long startOfDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** اختيار فترة: جاهزة أو من تاريخ إلى تاريخ. */
    void choosePeriod(final OnPeriod cb) {
        String[] items = {"كل الفترات", "اليوم", "هذا الشهر", "الشهر الماضي", "آخر 30 يومًا", "هذه السنة", "من تاريخ إلى تاريخ…"};
        new AlertDialog.Builder(a).setTitle("اختر الفترة").setItems(items, (d, w) -> {
            Calendar c = Model.cal();
            long end = Long.MAX_VALUE;
            switch (w) {
                case 0: cb.on(Period.all()); break;
                case 1: cb.on(new Period("اليوم", startOfDay(c), end)); break;
                case 2:
                    c.set(Calendar.DAY_OF_MONTH, 1);
                    cb.on(new Period("هذا الشهر", startOfDay(c), end));
                    break;
                case 3: {
                    c.set(Calendar.DAY_OF_MONTH, 1);
                    long to = startOfDay(c);
                    c.add(Calendar.MONTH, -1);
                    cb.on(new Period("الشهر الماضي (" + Model.sdf(new SimpleDateFormat("yyyy/MM", Locale.US)).format(new Date(c.getTimeInMillis())) + ")", startOfDay(c), to));
                    break;
                }
                case 4:
                    c.add(Calendar.DAY_OF_MONTH, -30);
                    cb.on(new Period("آخر 30 يومًا", startOfDay(c), end));
                    break;
                case 5:
                    c.set(Calendar.DAY_OF_YEAR, 1);
                    cb.on(new Period("هذه السنة", startOfDay(c), end));
                    break;
                default:
                    customPeriod(cb);
            }
        }).show();
    }

    void customPeriod(final OnPeriod cb) {
        final Calendar c = Model.cal();
        DatePickerDialog first = new DatePickerDialog(a, (v1, y1, m1, d1) -> {
            Calendar from = Model.cal();
            from.set(y1, m1, d1);
            final long f = startOfDay(from);
            DatePickerDialog second = new DatePickerDialog(a, (v2, y2, m2, d2) -> {
                Calendar to = Model.cal();
                to.set(y2, m2, d2);
                to.add(Calendar.DAY_OF_MONTH, 1);
                long t = startOfDay(to);
                if (t <= f) {
                    a.error(new Exception("تاريخ النهاية يجب أن يكون بعد تاريخ البداية"));
                    return;
                }
                cb.on(new Period("من " + Model.day(f) + " إلى " + Model.day(t - 1), f, t));
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            second.setTitle("إلى تاريخ");
            second.show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), 1);
        first.setTitle("من تاريخ");
        first.show();
    }

    void show(Model.Office o) { show(o, Period.all(), false); }

    void show(final Model.Office office, final Period period, final boolean showCancelled) {
        if (!a.require(Auth.Perm.VIEW) || !a.require(Auth.Perm.STATEMENT)) return;   // 12.0
        final Model.Office o = a.db.office(office.id) == null ? office : a.db.office(office.id);
        a.here(() -> show(o, period, showCancelled));
        a.tabbedScreen("كشف — " + o.name, () -> a.officeHub.showOffice(o), "offices");
        OfficeHub.touch(a.db, o.id);
        final Runnable refresh = () -> show(o, period, showCancelled);

        // الأرصدة المفصلة: كل ما لنا، كل ما علينا، الرصيد
        List<Model.OfficeTotals> tl = a.db.officeTotals(o.id);
        LinearLayout c = a.card();
        LinearLayout head = a.row();
        head.addView(a.text("أرصدة المكتب (كل الفترات)", 17, a.primary, true), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(a.smallButton(o.pinned ? "إلغاء التثبيت" : "📌 تثبيت", Color.rgb(120, 120, 120), v -> {
            a.db.setPinned(o.id, !o.pinned);
            show(a.db.office(o.id), period, showCancelled);
        }), new LinearLayout.LayoutParams(-2, a.dp(40)));
        c.addView(head);
        if (!o.active) c.addView(a.text("⏸ هذا المكتب موقوف: لا يظهر في تسجيل الحركات ولا تُقبل عليه حركات جديدة.", 13, a.red, true));
        if (!tl.isEmpty() && !tl.get(0).perCurrency.isEmpty()) {
            for (Map.Entry<String, long[]> e : tl.get(0).perCurrency.entrySet()) {
                int dec = a.db.decimals(e.getKey());
                long[] v = e.getValue();
                TextView t = a.text(e.getKey() + "   الرصيد: " + Model.bal(v[2], dec)
                        + "\nكل ما لنا: " + Model.fmt(v[0], dec) + "   •   كل ما علينا: " + Model.fmt(v[1], dec), 15, a.dark, false);
                c.addView(t);
            }
        } else c.addView(a.text("لا توجد عملات مفعّلة لهذا المكتب", 14, a.muted, false));
        Map<String, Long> net = a.db.balances(o.id);
        Ledger.Valuation val = Ledger.valueOffice(net, a.db.usdRates(), a.db.decimalsMap());
        if (val.receivableUsd.signum() != 0 || val.payableUsd.signum() != 0) {
            c.addView(a.text("تقويم تقريبي بالدولار: لنا ≈ " + Db.fmtUsd(val.receivableUsd) + " • علينا ≈ " + Db.fmtUsd(val.payableUsd)
                    + (val.missingRates.isEmpty() ? "" : "\nبدون سعر: " + val.missingRates), 12, a.muted, false));
        }
        List<String> alerts = a.officesScreen.limitAlerts(o);
        if (!alerts.isEmpty()) c.addView(a.text("⚠ تجاوز الحد:\n" + Model.join(alerts, "\n"), 14, a.red, true));

        // المطابقة مع المكتب
        LinearLayout rc = a.card();
        final Model.Recon last = a.db.lastRecon(o.id, false);
        rc.addView(a.text("المطابقة مع المكتب", 16, a.primary, true));
        if (last == null) rc.addView(a.text("لم تُرسل مطابقة رصيد لهذا المكتب بعد.", 13, a.muted, false));
        else rc.addView(a.text("آخر مطابقة: " + Model.date(last.createdAt) + " — " + ReconScreen.statusText(last), 14, a.reconScreen.statusColor(last), true));
        LinearLayout rb = a.row();
        rb.addView(a.smallButton("مطابقة رصيد", a.primary, v -> a.reconScreen.sendDialog(o, refresh)), a.weight(44, 1.1f, 2));
        rb.addView(a.smallButton("بعد آخر مطابقة", a.accent, v -> a.exportUi.statementDialog(o, period, null, ExportUi.SCOPE_SINCE, null)), a.weight(44, 1.1f, 2));
        rb.addView(a.smallButton("السجل", Color.rgb(120, 120, 120), v -> a.reconScreen.showOffice(o)), a.weight(44, 0.7f, 2));
        rc.addView(rb);
        if (last != null && Model.RECON_SENT.equals(last.status)) {
            LinearLayout qb = a.row();
            qb.addView(a.smallButton("✓ أكّد المكتب", a.green, v -> a.reconScreen.statusDialog(last, Model.RECON_CONFIRMED, refresh)), a.weight(42, 1, 2));
            qb.addView(a.smallButton("✗ غير مطابق", a.red, v -> a.reconScreen.statusDialog(last, Model.RECON_DISPUTED, refresh)), a.weight(42, 1, 2));
            rc.addView(qb);
        }

        LinearLayout pr = a.row();
        a.body.addView(pr, a.lp(-1, -2, 4));
        pr.addView(a.smallButton("الفترة: " + period.label, a.accent, v -> choosePeriod(p -> show(o, p, showCancelled))), a.weight(46, 1, 2));
        final CheckBox cancelled = new CheckBox(a);
        cancelled.setText("إظهار الملغاة");
        cancelled.setChecked(showCancelled);
        cancelled.setOnCheckedChangeListener((x, ch) -> show(o, period, ch));
        pr.addView(cancelled, new LinearLayout.LayoutParams(-2, -2));

        if (period.hasStart()) {
            Map<String, Long> before = a.db.balanceBefore(o.id, period.from, true);
            Map<String, long[]> sums = new java.util.TreeMap<>();
            for (String code : net.keySet()) sums.put(code, new long[]{0, 0});
            for (Model.Line l : a.db.statementLines(o.id, period.from, period.to)) {
                if (l.cancelledTx()) continue;
                long[] s = sums.get(l.currency);
                if (s == null) {
                    s = new long[]{0, 0};
                    sums.put(l.currency, s);
                }
                s[0] += l.debit;
                s[1] += l.credit;
            }
            LinearLayout pc = a.card();
            pc.addView(a.text("ملخص الفترة: " + period.label, 16, a.primary, true));
            for (Map.Entry<String, long[]> e : sums.entrySet()) {
                int dec = a.db.decimals(e.getKey());
                long open = before.containsKey(e.getKey()) ? before.get(e.getKey()) : 0;
                long close = open + e.getValue()[0] - e.getValue()[1];
                pc.addView(a.text(e.getKey() + ": بداية الفترة " + Model.bal(open, dec) + "\n+ لنا " + Model.fmt(e.getValue()[0], dec)
                        + "   − علينا " + Model.fmt(e.getValue()[1], dec) + "\n= نهاية الفترة " + Model.bal(close, dec), 14, a.dark, false));
            }
        }

        final TextView selInfo = a.hint("اضغط على حركة لتحديدها، واضغط مطولًا للتفاصيل والتعديل والإلغاء.");
        // أحدث 400 حركة من قاعدة البيانات مباشرة؛ التصدير يقرأ كل حركات الفترة في الخلفية
        Db.TxFilter tf = new Db.TxFilter();
        tf.office = o.id;
        tf.includeCancelled = showCancelled;
        tf.from = period.from;
        tf.to = period.to;
        tf.limit = 400;
        List<Model.Tx> txs = a.db.txLog(tf);
        final long total = txs.size() < 400 ? txs.size() : a.db.txCount(tf);
        final Set<Long> selected = new HashSet<>();
        final Map<Long, Integer> attCounts = a.db.attachmentCounts(o.id);
        Button exportBtn = a.button("تصدير الكشف (PDF / صور / Excel)", a.primary, null);
        exportBtn.setOnClickListener(v -> a.exportUi.statementDialog(o, period, new HashSet<>(selected)));
        if (total > txs.size()) a.hint("يُعرض أحدث " + txs.size() + " حركة من " + total + ". اختر فترة لعرض الأقدم؛ التصدير يشمل كل حركات الفترة.");
        for (final Model.Tx t : txs) {
            final LinearLayout x = a.card();
            x.addView(a.logScreen.txRow(t, null, attCounts.get(t.id)));
            x.setOnClickListener(v -> {
                if (selected.contains(t.id)) {
                    selected.remove(t.id);
                    x.setBackground(a.round(a.surface, a.line, 16));
                } else {
                    selected.add(t.id);
                    x.setBackground(a.round(a.primarySoft, a.primary, 16));
                }
                selInfo.setText(selected.isEmpty() ? "لا يوجد تحديد: سيُصدَّر كل حركات الفترة." : "محدد للتصدير: " + selected.size() + " حركة — اضغط «تصدير الكشف».");
            });
            x.setOnLongClickListener(v -> {
                a.logScreen.details(t, refresh);
                return true;
            });
        }
        if (txs.isEmpty()) a.add(a.text("لا توجد حركات في هذه الفترة.", 15, a.muted, false));
    }

    // ===================================================================================
    // بيانات الكشف للتصدير
    // ===================================================================================

    Reports.StatementInput input(Model.Office o, Period p, Set<Long> selected, boolean images, ExportStyle st) {
        return input(o, p, selected, images, st, null, null);
    }

    /**
     * بيانات الكشف. since != null: كل ما سُجّل بعد المطابقة (بترتيب القيود لا بالتاريخ)، والرصيد الافتتاحي = رصيد المطابقة.
     * عند إخفاء الملغاة: تُخفى الحركة الملغاة فقط إن كانت هي وإلغاؤها كلاهما بعد المطابقة (صافيها صفر)،
     * أما إلغاء حركة قديمة فيبقى ظاهرًا لأنه يغيّر الرصيد عن رصيد المطابقة.
     */
    Reports.StatementInput input(Model.Office o, Period p, Set<Long> selected, boolean images, ExportStyle st, Model.Recon since, Set<String> only) {
        boolean sel = since == null && selected != null && !selected.isEmpty();
        Reports.StatementInput in = new Reports.StatementInput();
        in.officeName = o.name;
        in.issuer = a.db.setting("office_name", "");
        in.periodLabel = p.label;
        in.periodHasStart = p.hasStart();
        in.selectedOnly = sel;
        in.currencyNames = a.db.currencyNames();
        in.decimals = a.db.decimalsMap();
        in.fullBalance = a.db.balances(o.id);
        in.onlyCurrencies = only;
        List<Model.Line> lines;
        if (since != null) {
            in.periodLabel = "بعد مطابقة " + Model.date(since.createdAt) + " (" + since.statusLabel() + ")";
            in.periodHasStart = true;
            in.opening = a.db.balanceUpTo(o.id, since);
            in.openingLabel = "رصيد المطابقة بتاريخ " + Model.date(since.createdAt);
            in.openingKv = "رصيد آخر مطابقة";
            in.openingCol = "رصيد المطابقة";
            List<Model.Line> after = a.db.statementLinesAfter(o.id, since);
            Set<Long> originalsAfter = new HashSet<>();
            for (Model.Line l : after) if (l.txId != null && !"REVERSAL".equals(l.kind)) originalsAfter.add(l.txId);
            lines = new ArrayList<>();
            for (Model.Line l : after) {
                if (st.hideCancelled && l.cancelledTx() && l.txId != null && originalsAfter.contains(l.txId)) continue;
                lines.add(l);
            }
        } else {
            if (!sel && p.hasStart()) in.opening = a.db.balanceBefore(o.id, p.from, st.hideCancelled);
            lines = a.db.statementLines(o.id, sel ? 0 : p.from, sel ? Long.MAX_VALUE : p.to);
            if (sel) {
                List<Model.Line> keep = new ArrayList<>();
                for (Model.Line l : lines) if (l.txId != null && selected.contains(l.txId)) keep.add(l);
                lines = keep;
            }
        }
        in.lines = lines;
        if (images) {
            Map<Long, List<Long>> m = new HashMap<>();
            for (Model.Line l : lines) {
                if (l.txId != null && "PRINCIPAL".equals(l.kind) && !m.containsKey(l.txId)) {
                    List<Long> ids = a.db.attachmentIds(l.txId);
                    if (!ids.isEmpty()) m.put(l.txId, ids);
                }
            }
            in.attachments = m;
        }
        return in;
    }
}
