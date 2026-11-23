package com.smart.hawala;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 9.0: «مركز التقارير»: الفترة أولًا (اليوم، أمس، 7 أيام، هذا الشهر، الشهر الماضي، 3 أشهر، 12 شهرًا، هذه السنة، الكل، أو من–إلى)،
 * ثم التقارير مرتبة في مجموعات: الأساسية، والجاهزة للفترة، والصندوق، والتقرير المخصص بخياراته كلها، والقوالب المحفوظة.
 * الحساب في ReportBuilder وCashBox المشتركين مع الماك، والتصدير PDF و Excel و CSV.
 */
final class ReportsScreen {
    private final MainActivity a;
    /** الفترة المختارة (فهرس في ReportBuilder.PERIODS) وحدّا الفترة المخصصة. */
    private int period = ReportBuilder.P_MONTH;
    private long customFrom, customTo;
    private ReportBuilder.Spec draft;

    ReportsScreen(MainActivity a) { this.a = a; }

    static final String KEY = "report_presets";

    long[] range() {
        if (period == ReportBuilder.P_CUSTOM) return new long[]{customFrom, customTo};
        return ReportBuilder.period(period, Clock.now());
    }

    String rangeText() {
        long[] r = range();
        if (r[0] <= 0 && r[1] <= 0) return "كل الفترات";
        return "من " + (r[0] > 0 ? "\u2066" + Model.day(r[0]) + "\u2069" : "البداية") + " إلى " + (r[1] > 0 ? "\u2066" + Model.day(r[1] - 1) + "\u2069" : "الآن");
    }

    // ===================================================================================
    // الشاشة الرئيسية
    // ===================================================================================

    private boolean allPresets;

    void show() {
        if (!a.require(Auth.Perm.VIEW) || !a.require(Auth.Perm.REPORTS)) return;   // 12.0
        a.here(this::show);
        a.tabbedScreen("مركز التقارير", a.moreScreen::show, "more");
        periodCard(this::show);
        final long[] r = range();
        final StatementScreen.Period sp = new StatementScreen.Period(ReportBuilder.PERIODS[period], r[0], r[1] > 0 ? r[1] : Long.MAX_VALUE);

        a.section("الأساسية", null, null);
        LinearLayout g = a.group();
        int ic = a.primaryInk;
        a.groupRow(g, R.drawable.ic_scale, ic, "أرصدة كل المكاتب", "الآن: لنا وعلينا لكل عملة، مع التقويم بالدولار", null, v -> a.balancesScreen.show());
        a.groupRow(g, R.drawable.ic_doc, ic, "كشف مكتب للفترة", "اختر المكتب: حركاته ورصيده المتراكم للفترة المختارة", null,
                v -> a.officePicker(o -> a.statementScreen.show(o, sp, false)));
        a.groupRow(g, R.drawable.ic_calendar, ic, "التقرير اليومي", "حركات يوم وأجوره ومجاميعه", null,
                v -> a.dailyScreen.show(r[0] > 0 ? r[0] : Clock.now()));
        a.groupRow(g, R.drawable.ic_filter, ic, "البحث المتقدم في الحركات", "حسب المكتب والنوع والعملة والفترة والمبلغ", null, v -> a.logScreen.show(true));
        if (a.can(Auth.Perm.OWNER)) a.groupRow(g, R.drawable.ic_pulse, ic, "تقارير صاحب المكتب", "النمو، التركّز، عمر الديون، الخاملة، الاتجاهات، الأجور، الخدمات", null, v -> a.ownerScreen.show());
        if (a.db.cashConfig().on)
            a.groupRow(g, R.drawable.ic_cash, ic, "تقرير صندوق المكتب للفترة", "الداخل والخارج والمصاريف حسب البند والجرد، وكشف برصيد متراكم", null,
                    v -> a.cashScreen.reportFor(r[0], r[1], this::show));

        // 10.12: التحقق بين المحاسبين (مع نظام المستخدمين)
        if (a.db.usersEnabled())
            a.groupRow(g, R.drawable.ic_check2, ic, "التسجيل والتحقق بين المحاسبين", "لكل مكتب من يسجّل ومن يتحقق، وما تحقق منه وما ينتظر، والمطابقات", null,
                    v -> teamReport(r[0], r[1]));

        a.section("جاهزة للفترة المختارة", null, null);
        LinearLayout pg = a.group();
        // 10.16: الأكثر استعمالًا أولًا، والبقية تظهر عند الطلب (كانت قائمة طويلة تزحم الشاشة)
        final List<ReportBuilder.Preset> presets = ReportBuilder.presets();
        int shownPresets = 0;
        for (final ReportBuilder.Preset p : presets) {
            if (!allPresets && shownPresets >= 5) break;
            shownPresets++;
            a.groupRow(pg, R.drawable.ic_report, ic, p.name, p.hint, null, v -> {
                ReportBuilder.Spec s = ReportBuilder.Spec.fromJson(p.spec.toJson());
                s.from = r[0];
                s.to = r[1];
                run(s, this::show);
            });
        }
        if (presets.size() > 5) a.groupRow(pg, R.drawable.ic_sliders, a.muted, allPresets ? "إخفاء التقارير الإضافية" : "عرض بقية التقارير الجاهزة (" + (presets.size() - 5) + ")",
                allPresets ? null : "قص العملات، الحركات بين مكتبين، ساعات الذروة، الحوالات الكبيرة…", null, v -> {
            allPresets = !allPresets;
            show();
        });

        a.section("تقرير مخصص", null, null);
        LinearLayout cg = a.group();
        a.groupRow(cg, R.drawable.ic_sliders, ic, "صمّم تقريرك", "المكاتب والعملات والاتجاه والنوع والحالة والمبلغ، والتجميع والجدول المتقاطع والمقارنة", null, v -> builder(null));
        JSONObject st = stored();
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = st.keys(); it.hasNext(); ) names.add(it.next());
        Collections.sort(names);
        for (final String n : names) {
            a.groupRow(cg, R.drawable.ic_tag, ic, n, "قالب محفوظ — اضغط للتشغيل، ومطولًا للتعديل أو الحذف", null, v -> runSaved(n)).setOnLongClickListener(v -> {
                savedMenu(n);
                return true;
            });
        }
        a.hint("كل تقرير يُصدَّر PDF أو Excel أو يُشارَك. القوالب المحفوظة تنتقل مع النسخة الاحتياطية وتعمل على الماك أيضًا.");
    }

    /** 11.5: اختيار مكاتب التقرير مع بحث (كانت قائمة طويلة بلا بحث). بلا تحديد = كل المكاتب. */
    private void chooseOffices(final List<Model.Office> offs, final java.util.Set<Long> chosen, final Runnable after) {
        final java.util.Set<Long> pick = new java.util.TreeSet<>(chosen);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(12), a.dp(6), a.dp(12), 0);
        final EditText q = new EditText(a);
        q.setHint("ابحث باسم المكتب");
        q.setSingleLine(true);
        l.addView(q, new LinearLayout.LayoutParams(-1, a.dp(52)));
        final TextView count = a.text("", 12, a.muted, true);
        l.addView(count);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);
        float d = a.getResources().getDisplayMetrics().density;
        int h = Math.max(220, Math.min(620, Math.round(a.getResources().getDisplayMetrics().heightPixels / d) - 300));
        l.addView(sv, new LinearLayout.LayoutParams(-1, a.dp(h)));
        final List<CheckBox> boxes = new ArrayList<>();
        final Runnable counter = () -> count.setText(pick.isEmpty() ? "لا تحديد = كل المكاتب" : "المختارة: " + pick.size());
        for (final Model.Office o : offs) {
            CheckBox cb = new CheckBox(a);
            cb.setText(o.name + (o.active ? "" : "  (موقوف)"));
            cb.setChecked(pick.contains(o.id));
            cb.setOnCheckedChangeListener((b, on) -> {
                if (on) pick.add(o.id);
                else pick.remove(o.id);
                counter.run();
            });
            cb.setTag(o);
            boxes.add(cb);
            box.addView(cb, new LinearLayout.LayoutParams(-1, a.dp(44)));
        }
        counter.run();
        q.addTextChangedListener(Ui.watcher(() -> {
            String f = q.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (CheckBox cb : boxes) cb.setVisibility(f.isEmpty() || MainActivity.officeMatches((Model.Office) cb.getTag(), f) ? View.VISIBLE : View.GONE);
        }));
        LinearLayout acts = a.row();
        acts.addView(a.smallButton("تحديد الظاهرة", a.accent, v -> {
            for (CheckBox cb : boxes) if (cb.getVisibility() == View.VISIBLE) cb.setChecked(true);
        }), a.weight(40, 1, 2));
        acts.addView(a.smallButton("إلغاء التحديد", Color.rgb(140, 140, 140), v -> {
            for (CheckBox cb : boxes) cb.setChecked(false);
        }), a.weight(40, 1, 2));
        l.addView(acts);
        new AlertDialog.Builder(a).setTitle("مكاتب التقرير").setView(l)
                .setPositiveButton("تم", (dd, w) -> {
                    chosen.clear();
                    chosen.addAll(pick);
                    after.run();
                }).setNegativeButton("إلغاء", null).show();
    }

    /** بطاقة الفترة: أزرار الفترات السريعة، ومن–إلى للمخصصة. */
    void periodCard(final Runnable redraw) {
        LinearLayout c = a.card();
        c.addView(a.text("الفترة", 15, a.ink, true));
        LinearLayout pills = a.row();
        for (int i = 0; i < ReportBuilder.PERIODS.length; i++) {
            final int k = i;
            pills.addView(a.pill(ReportBuilder.PERIODS[i], i == period, v -> {
                if (k == ReportBuilder.P_CUSTOM) pickCustom(redraw);
                else {
                    period = k;
                    redraw.run();
                }
            }), a.gap(-2, -2, 3));
        }
        c.addView(a.strip(pills), a.lp(-1, -2, 2));
        TextView rt = a.text(rangeText(), 14, a.primaryInk, true);
        c.addView(rt);
    }

    private void pickCustom(final Runnable redraw) {
        final Calendar c = Model.cal();
        if (customFrom > 0) c.setTimeInMillis(customFrom);
        DatePickerDialog first = new DatePickerDialog(a, (v1, y1, m1, d1) -> {
            Calendar f = Model.cal();
            f.set(y1, m1, d1, 0, 0, 0);
            f.set(Calendar.MILLISECOND, 0);
            final long from = f.getTimeInMillis();
            Calendar t0 = Model.cal();
            if (customTo > 0) t0.setTimeInMillis(customTo - 1);
            DatePickerDialog second = new DatePickerDialog(a, (v2, y2, m2, d2) -> {
                Calendar t = Model.cal();
                t.set(y2, m2, d2, 0, 0, 0);
                t.set(Calendar.MILLISECOND, 0);
                long to = ReportBuilder.addDays(t.getTimeInMillis(), 1);
                if (to <= from) {
                    a.toast("تاريخ النهاية قبل البداية");
                    return;
                }
                customFrom = from;
                customTo = to;
                period = ReportBuilder.P_CUSTOM;
                redraw.run();
            }, t0.get(Calendar.YEAR), t0.get(Calendar.MONTH), t0.get(Calendar.DAY_OF_MONTH));
            second.setTitle("إلى تاريخ");
            second.show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        first.setTitle("من تاريخ");
        first.show();
    }

    // ===================================================================================
    // التشغيل والعرض
    // ===================================================================================

    Report build(ReportBuilder.Spec s) {
        Report r = ReportBuilder.build(s, a.db.builderData(), a.db.setting("office_name", ""));
        Entitlements e = License.current;
        r.stamp(e.watermark(), e.lid);
        return r;
    }

    void run(final ReportBuilder.Spec s, final Runnable back) {
        a.busy("جارٍ بناء التقرير…", p -> build(s), res -> showReport((Report) res, s, back));
    }

    private void showReport(final Report r, final ReportBuilder.Spec s, final Runnable back) {
        a.here(() -> run(s, back));
        a.tabbedScreen(r.title, back, "more");
        LinearLayout head = a.card();
        head.addView(a.text(r.title, 17, a.primaryInk, true));
        for (String h : r.headerLines) head.addView(a.text(h, 12, a.muted, false));
        LinearLayout bar = a.row();
        bar.addView(a.smallButton("تصدير PDF / Excel", a.primary, v -> a.exportUi.reportDialog(r.title, () -> showReport(r, s, back),
                (images, st) -> Collections.singletonList(build(s)))), a.weight(44, 1.3f, 2));
        bar.addView(a.smallButton("CSV", Color.rgb(120, 120, 120), v -> shareCsv(r)), a.weight(44, 0.6f, 2));
        bar.addView(a.smallButton("تعديل", a.accent, v -> builder(s)), a.weight(44, 0.7f, 2));
        a.body.addView(bar, a.lp(-1, -2, 4));
        if (r.dataRows() == 0) a.notice(R.drawable.ic_info, "لا توجد حركات مطابقة لهذه الشروط في الفترة المختارة.", false, null, null);
        ReportRender.render(a, r);
    }

    /** 10.12: تقرير التسجيل والتحقق للفترة المختارة. */
    void teamReport(final long from, final long to) {
        final String label = rangeText();
        final java.util.function.Supplier<Report> make = () -> {
            Report r = a.db.teamCheckReport(from, to, label);
            Entitlements e = License.current;
            r.stamp(e.watermark(), e.lid);
            return r;
        };
        a.busy("جارٍ بناء التقرير…", p -> make.get(), res -> showPlain((Report) res, make, this::show));
    }

    /** عرض تقرير جاهز (بلا منشئ): تصدير PDF / Excel و CSV. */
    void showPlain(final Report r, final java.util.function.Supplier<Report> make, final Runnable back) {
        a.here(() -> showPlain(make == null ? r : make.get(), make, back));
        a.tabbedScreen(r.title, back, "more");
        LinearLayout head = a.card();
        head.addView(a.text(r.title, 17, a.primaryInk, true));
        for (String h : r.headerLines) head.addView(a.text(h, 12, a.muted, false));
        LinearLayout bar = a.row();
        bar.addView(a.smallButton("تصدير PDF / Excel", a.primary, v -> a.exportUi.reportDialog(r.title, () -> showPlain(r, make, back),
                (images, st) -> Collections.singletonList(make.get()))), a.weight(44, 1.3f, 2));
        bar.addView(a.smallButton("CSV", Color.rgb(120, 120, 120), v -> shareCsv(r)), a.weight(44, 0.6f, 2));
        a.body.addView(bar, a.lp(-1, -2, 4));
        if (r.dataRows() == 0) a.notice(R.drawable.ic_info, "لا توجد بيانات في الفترة المختارة.", false, null, null);
        ReportRender.render(a, r);
    }

    private void shareCsv(Report r) {
        try {
            File f = new File(a.sharedDir(), Ui.safeName(r.title) + ".csv");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(ReportBuilder.csv(r).getBytes(StandardCharsets.UTF_8));
            }
            a.shareFiles(Collections.singletonList(f), "text/csv", r.title);
        } catch (Exception e) {
            a.error(e);
        }
    }

    // ===================================================================================
    // المنشئ
    // ===================================================================================

    void builder(ReportBuilder.Spec given) {
        if (!a.require(Auth.Perm.VIEW)) return;
        final ReportBuilder.Spec s = given != null ? ReportBuilder.Spec.fromJson(given.toJson()) : draft != null ? draft : new ReportBuilder.Spec();
        draft = s;
        a.here(() -> builder(given));
        a.tabbedScreen("تقرير مخصص", this::show, "more");
        if (given != null && (given.from > 0 || given.to > 0)) {
            period = ReportBuilder.P_CUSTOM;
            customFrom = given.from;
            customTo = given.to;
        }
        periodCard(() -> builder(null));

        a.title("العنوان (اختياري)");
        final EditText title = a.input("مثل: أجور مكاتب حلب");
        title.setText(s.title);

        a.title("المكاتب والعملات");
        final List<Model.Office> offs = a.db.offices();
        final TextView offT = a.add(a.text("", 14, a.ink, true));
        final Runnable offText = () -> offT.setText(s.offices.isEmpty() ? "المكاتب: الكل" : "المكاتب: " + s.offices.size() + " مختارة");
        offText.run();
        a.body.addView(a.smallButton("اختيار المكاتب", a.accent, v -> chooseOffices(offs, s.offices, offText)), a.lp(-1, 44, 2));
        Ui.Flow curs = a.flow();
        for (final Model.Currency c : a.db.currencies(false)) {
            final CheckBox cb = new CheckBox(a);
            cb.setText(c.code);
            cb.setChecked(s.currencies.contains(c.code));
            cb.setOnCheckedChangeListener((b, on) -> {
                if (on) s.currencies.add(c.code);
                else s.currencies.remove(c.code);
            });
            curs.addView(cb);
        }
        a.body.addView(curs);
        a.hint("بلا تحديد عملة = كل العملات.");

        a.title("ماذا يدخل التقرير");
        a.seg(ReportBuilder.DIR_NAMES, s.direction, i -> s.direction = i);
        a.add(a.label("نوع الحركة"));
        // 11.6: لا خيار «الراجع» منفصلًا (هو من الأجور): القائمة بلا البند 3، والمحفوظ عليه يظهر «حركات الأجور»
        final String[] kinds = new String[ReportBuilder.KIND_NAMES.length - 1];
        for (int i = 0, j = 0; i < ReportBuilder.KIND_NAMES.length; i++) if (i != ReportBuilder.K_RAJE) kinds[j++] = ReportBuilder.KIND_NAMES[i];
        final Spinner kind = a.spinner(kinds);
        int kk = s.kind == ReportBuilder.K_RAJE ? ReportBuilder.K_FEE_ROWS : s.kind;
        kind.setSelection(kk > ReportBuilder.K_RAJE ? kk - 1 : kk);
        a.body.addView(kind, a.lp(-1, 48, 2));
        a.add(a.label("الحالة"));
        final Spinner status = a.spinner(ReportBuilder.STATUS_NAMES);
        status.setSelection(s.status);
        a.body.addView(status, a.lp(-1, 48, 2));
        final EditText text = a.input("بحث في المستفيد والبيان");
        text.setText(s.text);
        LinearLayout mm = a.row();
        final EditText min = a.field("المبلغ من");
        final EditText max = a.field("إلى");
        Ui.numeric(min);
        Ui.numeric(max);
        if (s.minAmount != null) min.setText(s.minAmount.toPlainString());
        if (s.maxAmount != null) max.setText(s.maxAmount.toPlainString());
        mm.addView(min, a.weight(50, 1, 2));
        mm.addView(max, a.weight(50, 1, 2));
        a.body.addView(mm);
        final EditText service = a.input("الخدمة (اختياري)");
        service.setText(s.service);
        final EditText dest = a.input("الوجهة (اختياري)");
        dest.setText(s.dest);
        final EditText user = a.input("المستخدم (اختياري)");
        user.setText(s.user);

        a.title("الشكل");
        a.add(a.label("جمّع حسب"));
        final Spinner group = a.spinner(ReportBuilder.GROUP_NAMES);
        group.setSelection(s.group);
        a.body.addView(group, a.lp(-1, 48, 2));
        a.add(a.label("جدول متقاطع: الأعمدة حسب"));
        String[] g2 = ReportBuilder.GROUP_NAMES.clone();
        g2[0] = "بلا جدول متقاطع";
        final Spinner group2 = a.spinner(g2);
        group2.setSelection(s.group2);
        a.body.addView(group2, a.lp(-1, 48, 2));
        a.add(a.label("قيمة الجدول المتقاطع"));
        final Spinner measure = a.spinner(ReportBuilder.MEASURE_NAMES);
        measure.setSelection(s.measure);
        a.body.addView(measure, a.lp(-1, 48, 2));
        a.add(a.label("الترتيب"));
        final Spinner sort = a.spinner(ReportBuilder.SORT_NAMES);
        sort.setSelection(s.sort);
        a.body.addView(sort, a.lp(-1, 48, 2));
        final EditText top = a.input("أعلى عدد (فارغ = الكل)");
        top.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (s.top > 0) top.setText("" + s.top);
        final CheckBox compare = check("قارن بالفترة السابقة (بالطول نفسه)", s.compare);
        final CheckBox details = check("أضف الكشف التفصيلي تحت المجاميع", s.details);
        final CheckBox running = check("رصيد متراكم (عند اختيار مكتب واحد)", s.runningBalance);

        final Runnable collect = () -> {
            s.title = title.getText().toString().trim();
            long[] r = range();
            s.from = r[0];
            s.to = r[1];
            int kp = kind.getSelectedItemPosition();
            s.kind = kp >= ReportBuilder.K_RAJE ? kp + 1 : kp;
            s.status = status.getSelectedItemPosition();
            s.text = text.getText().toString().trim();
            s.service = service.getText().toString().trim();
            s.dest = dest.getText().toString().trim();
            s.user = user.getText().toString().trim();
            s.minAmount = num(min);
            s.maxAmount = num(max);
            s.group = group.getSelectedItemPosition();
            s.group2 = group2.getSelectedItemPosition();
            s.measure = measure.getSelectedItemPosition();
            s.sort = sort.getSelectedItemPosition();
            try {
                s.top = top.getText().toString().trim().isEmpty() ? 0 : Integer.parseInt(Db.normalizeNumber(top.getText().toString().trim()));
            } catch (Exception e) {
                s.top = 0;
            }
            s.compare = compare.isChecked();
            s.details = details.isChecked();
            s.runningBalance = running.isChecked();
        };
        LinearLayout foot = a.row();
        foot.addView(a.makeButton("عرض التقرير", a.primary, v -> {
            collect.run();
            run(ReportBuilder.Spec.fromJson(s.toJson()), () -> builder(null));
        }), new LinearLayout.LayoutParams(0, a.dp(50), 1.4f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, a.dp(50), 1);
        sp.setMargins(a.dp(8), 0, 0, 0);
        foot.addView(a.makeButton("حفظ كقالب", Color.rgb(130, 130, 130), v -> {
            collect.run();
            save(s);
        }), sp);
        a.footer(foot);
        LinearLayout r2 = a.row();
        r2.addView(a.smallButton("مسح الخيارات", Color.rgb(130, 130, 130), v -> {
            draft = new ReportBuilder.Spec();
            builder(null);
        }), a.weight(42, 1, 2));
        a.body.addView(r2, a.lp(-1, -2, 6));
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setChecked(on);
        a.body.addView(c);
        return c;
    }

    private static BigDecimal num(EditText e) {
        String t = e.getText().toString().trim();
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(Db.normalizeNumber(t));
        } catch (Exception ex) {
            return null;
        }
    }

    // ===================================================================================
    // القوالب المحفوظة (الصيغة نفسها على الماك)
    // ===================================================================================

    JSONObject stored() {
        try {
            String s = a.db.setting(KEY, "");
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void save(final ReportBuilder.Spec s) {
        final EditText name = new EditText(a);
        name.setText(s.title);
        name.setHint("اسم القالب");
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle("حفظ كقالب").setMessage("يُحفظ القالب بفترته السريعة («هذا الشهر» يبقى هذا الشهر دائمًا).").setView(l)
                .setPositiveButton("حفظ", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        a.toast("اكتب اسمًا للقالب");
                        return;
                    }
                    try {
                        JSONObject j = s.toJson();
                        j.put("p", period);
                        if (period != ReportBuilder.P_CUSTOM) {
                            j.put("from", 0);
                            j.put("to", 0);
                        }
                        JSONObject st = stored();
                        st.put(n, j);
                        a.db.setSetting(KEY, st.toString());
                        a.toast("حُفظ القالب «" + n + "»");
                    } catch (Exception e) {
                        a.error(e);
                    }
                }).setNegativeButton("إلغاء", null).show();
    }

    private ReportBuilder.Spec loadSaved(String n) {
        JSONObject j = stored().optJSONObject(n);
        if (j == null) return null;
        ReportBuilder.Spec s = ReportBuilder.Spec.fromJson(j);
        int p = j.optInt("p", -1);
        if (p >= 0 && p < ReportBuilder.P_CUSTOM) {
            period = p;
            long[] r = ReportBuilder.period(p, Clock.now());
            s.from = r[0];
            s.to = r[1];
        }
        return s;
    }

    private void runSaved(String n) {
        ReportBuilder.Spec s = loadSaved(n);
        if (s != null) run(s, this::show);
    }

    private void savedMenu(final String n) {
        new AlertDialog.Builder(a).setTitle(n).setItems(new String[]{"تشغيل", "تعديل", "حذف"}, (d, w) -> {
            if (w == 0) runSaved(n);
            else if (w == 1) {
                ReportBuilder.Spec s = loadSaved(n);
                if (s != null) {
                    draft = null;
                    builder(s);
                }
            } else a.confirm("حذف القالب", "حذف القالب «" + n + "»؟", "حذف", () -> {
                JSONObject st = stored();
                st.remove(n);
                a.db.setSetting(KEY, st.toString());
                show();
            });
        }).show();
    }
}
