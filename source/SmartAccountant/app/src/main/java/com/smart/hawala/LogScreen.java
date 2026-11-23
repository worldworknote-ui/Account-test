package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** سجل كل الحركات مع البحث والفلترة (نص، مكتب، نوع، عملة، فترة، مبلغ)، والتفاصيل والتعديل والإلغاء والتصدير. */
final class LogScreen {
    private final MainActivity a;
    private Db.TxFilter filter = new Db.TxFilter();
    private String term = "";
    private String minText = "", maxText = "";
    /** 10.18: نوع الحركة (0 الكل، 1 عادية، 2 قص عملات، 3 بين مكتبين، 4 مع أجرة، 5 بمرفقات، 6 معدّلة) ومن سجّلها. */
    private int kindSel;
    private String whoSel;
    static final String[] KINDS = {"كل الحركات", "عادية", "قص عملات", "بين مكتبين", "مع أجرة", "بمرفقات", "معدّلة (مصحّحة)"};
    private StatementScreen.Period period = StatementScreen.Period.all();
    private int shownLimit = 100;

    LogScreen(MainActivity a) { this.a = a; }

    /**
     * 10.3: من لا يملك «العرض» يرى حركاته التي سجّلها هو فقط (آخر 300)، بحالتها: نشطة، بانتظار الاعتماد، مرفوضة، ملغاة.
     */
    void showMine() {
        if (!a.require(Auth.Perm.TX_ADD)) return;
        a.here(this::showMine);
        a.rootScreen("حركاتي", "log");
        Model.User u = SmartApp.currentUser;
        String me = u == null ? Db.currentUserName : u.name;
        a.hint("الحركات التي سجّلتها أنت. اضغط الحركة لتفاصيلها.");
        Db.TxFilter f = new Db.TxFilter();
        f.limit = 1500;
        f.includeCancelled = true;
        Map<Long, String> names = a.db.officeNames();
        int n = 0;
        for (Model.Tx t : a.db.txLog(f)) {
            if (!(u != null ? Db.enteredBy(t, u) : t.realUser().equals(me))) continue;   // 10.5 (§7): برقمه الثابت
            LinearLayout x = a.card();
            x.addView(txRow(t, names.get(t.officeId), null));
            if (t.isPending()) x.addView(a.text("بانتظار الاعتماد — لا أثر لها في الأرصدة بعد", 12, a.warnInk, true));
            else if (t.isRejected()) x.addView(a.text("مرفوضة", 12, a.red, true));
            else if (t.isUnposted()) x.addView(a.text("⏳ خاصة: " + t.special + " — لا تُحسب في الرصيد", 12, a.warnInk, true));
            else if (Model.STATUS_CONVERTED.equals(t.status)) x.addView(a.text("خاصة حُوّلت إلى حركة عادية", 12, a.muted, true));
            else if (Model.STATUS_VOIDED.equals(t.status)) x.addView(a.text("خاصة ملغاة", 12, a.muted, true));
            else if (!t.isActive()) x.addView(a.text("ملغاة أو معدّلة", 12, a.muted, true));
            else if (t.isSpecial()) x.addView(a.text("⏳ معلّقة: " + t.special + " — تُحسب في الرصيد", 12, a.warnInk, true));
            final Model.Tx ft = t;
            x.setOnClickListener(v -> details(ft, this::showMine));
            if (++n >= 300) break;
        }
        if (n == 0) a.add(a.text("لم تسجّل حركات بعد.", 15, a.muted, false));
    }

    void show(boolean expandFilters) {
        if (!a.can(Auth.Perm.VIEW) && a.can(Auth.Perm.TX_ADD)) {
            showMine();
            return;
        }
        if (!a.require(Auth.Perm.VIEW)) return;
        shownLimit = 100;
        render(expandFilters);
    }

    /** بحث من الصفحة الرئيسية: نص واحد يبحث في المكاتب والحركات. */
    void searchFor(String text) {
        if (!a.require(Auth.Perm.VIEW)) return;
        filter = new Db.TxFilter();
        period = StatementScreen.Period.all();
        minText = "";
        maxText = "";
        term = text == null ? "" : text.trim();
        shownLimit = 100;
        render(false);
        if (term.isEmpty() && searchField != null) {
            searchField.requestFocus();
            searchField.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (im != null && searchField != null) im.showSoftInput(searchField, 0);
            }, 150);
        }
    }

    private EditText searchField;

    private void render(final boolean expanded) {
        a.here(() -> render(expanded));
        // 11.5: البحث المتقدم صفحة فرعية (يُفتح من مركز التقارير): سهم الرجوع يعيد إلى ما قبله لا إلى الرئيسية
        if (expanded) a.tabbedScreen("البحث المتقدم", () -> render(false), "log");
        else a.rootScreen("الحركات", "log");
        final LinearLayout filters = new LinearLayout(a);
        filters.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(filters);
        LinearLayout bar = a.officeHub.searchBox("بحث: شخص، بيان، ملاحظة، مكتب، مبلغ، مرجع");
        final EditText q = (EditText) bar.getTag();
        searchField = q;
        q.setText(term);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setOnEditorActionListener((v, id, ev) -> {
            term = q.getText().toString().trim();
            render(expanded);
            return true;
        });
        bar.addView(a.smallButton("بحث", a.primary, v -> {
            term = q.getText().toString().trim();
            render(expanded);
        }), new LinearLayout.LayoutParams(a.dp(64), a.dp(40)));
        filters.addView(bar, a.lp(-1, 50, 4));
        if (!term.isEmpty()) {
            // مكاتب مطابقة لنص البحث: الانتقال لصفحة المكتب مباشرة
            String tl = term.toLowerCase(Locale.ROOT);
            LinearLayout oc = a.row();
            int found = 0;
            for (final Model.Office o : a.db.offices()) {
                String hay = (Model.nz(o.name) + " " + Model.nz(o.shortName) + " " + Model.nz(o.phone)).toLowerCase(Locale.ROOT);
                if (!hay.contains(tl)) continue;
                found++;
                oc.addView(a.pill(o.name, false, v -> a.officeHub.showOffice(o)), a.gap(-2, -2, 3));
            }
            if (found > 0) {
                filters.addView(a.text("مكاتب مطابقة", 13, a.muted, true));
                filters.addView(a.strip(oc), a.lp(-1, -2, 2));
            }
        }

        final LinearLayout adv = new LinearLayout(a);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(expanded ? View.VISIBLE : View.GONE);
        filters.addView(a.smallButton(expanded ? "إخفاء الفلاتر" : "فلاتر متقدمة: المكتب، النوع، العملة، الفترة، المبلغ", Color.rgb(120, 120, 120),
                v -> render(!expanded)), a.lp(-1, 42, 3));
        filters.addView(adv);

        final List<Model.Office> offices = a.db.offices();
        List<String> on = new ArrayList<>();
        on.add("كل المكاتب");
        int selOffice = 0;
        for (int i = 0; i < offices.size(); i++) {
            on.add((offices.get(i).pinned ? "📌 " : "") + offices.get(i).name);
            if (filter.office != null && offices.get(i).id == filter.office) selOffice = i + 1;
        }
        final Spinner office = new Spinner(a);
        a.setItems(office, on);
        office.setSelection(selOffice);
        adv.addView(office, a.lp(-1, 50, 2));
        final Spinner type = a.spinner(new String[]{"كل الأنواع", "لنا (مدين)", "علينا (دائن)"});
        type.setSelection(filter.type == null ? 0 : Model.TYPE_DEBIT.equals(filter.type) ? 1 : 2);
        adv.addView(type, a.lp(-1, 50, 2));
        List<String> cs = new ArrayList<>();
        cs.add("كل العملات");
        for (Model.Currency c : a.db.currencies(true)) cs.add(c.code);
        final Spinner cur = new Spinner(a);
        a.setItems(cur, cs);
        Ui.select(cur, filter.currency);
        adv.addView(cur, a.lp(-1, 50, 2));
        adv.addView(a.smallButton("الفترة: " + period.label, a.accent, v -> a.statementScreen.choosePeriod(p -> {
            period = p;
            render(true);
        })), a.lp(-1, 44, 3));
        final Spinner kind = a.spinner(KINDS);
        kind.setSelection(kindSel);
        adv.addView(kind, a.lp(-1, 50, 2));
        final List<String> whoNames = new ArrayList<>();
        whoNames.add("كل المحاسبين");
        if (a.db.usersEnabled() && a.can(Auth.Perm.VIEW)) for (Model.User u : a.db.users(false)) whoNames.add(Db.label(u));
        final Spinner who = new Spinner(a);
        a.setItems(who, whoNames);
        who.setSelection(Math.max(0, whoNames.indexOf(whoSel)));
        if (whoNames.size() > 1) adv.addView(who, a.lp(-1, 50, 2));
        LinearLayout amt = a.row();
        final EditText min = a.field("أدنى مبلغ");
        final EditText max = a.field("أعلى مبلغ");
        Ui.numeric(min);
        Ui.numeric(max);
        min.setText(minText);
        max.setText(maxText);
        amt.addView(min, a.weight(48, 1, 2));
        amt.addView(max, a.weight(48, 1, 2));
        adv.addView(amt);
        final CheckBox cancelled = new CheckBox(a);
        cancelled.setText("تضمين الحركات الملغاة والمعدّلة");
        cancelled.setChecked(filter.includeCancelled);
        adv.addView(cancelled);
        adv.addView(a.smallButton("تطبيق الفلاتر", a.primary, v -> {
            term = q.getText().toString().trim();
            int oi = office.getSelectedItemPosition();
            filter.office = oi <= 0 ? null : offices.get(oi - 1).id;
            int ti = type.getSelectedItemPosition();
            filter.type = ti == 1 ? Model.TYPE_DEBIT : ti == 2 ? Model.TYPE_CREDIT : null;
            filter.currency = cur.getSelectedItemPosition() <= 0 ? null : String.valueOf(cur.getSelectedItem());
            filter.includeCancelled = cancelled.isChecked();
            minText = min.getText().toString().trim();
            maxText = max.getText().toString().trim();
            kindSel = kind.getSelectedItemPosition();
            whoSel = who.getSelectedItemPosition() <= 0 ? null : whoNames.get(who.getSelectedItemPosition());
            render(true);
        }), a.lp(-1, 46, 3));
        adv.addView(a.smallButton("مسح كل الفلاتر", Color.rgb(140, 140, 140), v -> {
            filter = new Db.TxFilter();
            term = "";
            minText = "";
            maxText = "";
            kindSel = 0;
            whoSel = null;
            period = StatementScreen.Period.all();
            render(expanded);
        }), a.lp(-1, 42, 3));

        // 8.0: البحث في خيط خلفي (لا تجمّد الشاشة مع آلاف الحركات)، وبلا نص بحث ولا حدود مبلغ يُجلب المعروض فقط
        final int gen = ++generation;
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(box);
        box.addView(a.text("جارٍ التحميل…", 14, a.muted, false));
        final int limit = shownLimit;
        new Thread(() -> {
            Object res;
            try {
                res = query(limit);
            } catch (Throwable t) {
                res = t;
            }
            final Object fr = res;
            a.runOnUiThread(() -> {
                if (gen != generation || a.isFinishing() || box.getParent() == null) return;
                box.removeAllViews();
                if (fr instanceof NumberFormatException) {
                    box.addView(a.text("قيمة المبلغ في الفلتر غير صحيحة.", 14, a.red, true));
                    return;
                }
                if (fr instanceof Throwable) {
                    box.addView(a.text("تعذر عرض الحركات: " + Ui.friendly((Throwable) fr), 14, a.red, true));
                    return;
                }
                showResults(box, (Result) fr, expanded);
            });
        }, "log-query").start();
    }

    /** نتيجة البحث: الحركات المعروضة والعدد الكلي المطابق. */
    private static final class Result {
        List<Model.Tx> shown;
        long total;
        Map<Long, String> names;
        Map<Long, Integer> att;
    }

    private int generation;

    private void showResults(LinearLayout box, final Result r, final boolean expanded) {
        List<String> desc = describeFilters(r.names);
        box.addView(a.text("عدد الحركات: " + r.total + (desc.isEmpty() ? "" : "\n" + Model.join(desc, " • ")), 14, a.primary, true));
        if (r.total > 0) {
            box.addView(a.makeButton("تصدير هذه النتائج (PDF / صور / Excel)", a.primary, v -> a.busy("جارٍ جمع النتائج…", p -> query(0).shown,
                    res -> {
                        @SuppressWarnings("unchecked") List<Model.Tx> all = (List<Model.Tx>) res;
                        a.exportUi.listDialog(expanded ? "نتائج البحث" : "سجل الحركات", desc, all);
                    })), a.lp(-1, 48, 4));
        }
        int n = 0;
        for (final Model.Tx t : r.shown) {
            if (n >= shownLimit) break;
            n++;
            LinearLayout x = a.card();
            a.body.removeView(x);
            box.addView(x, a.lp(-1, -2, 5));
            x.addView(txRow(t, r.names.get(t.officeId), r.att.get(t.id)));
            x.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
            x.setOnClickListener(v -> details(t, () -> render(expanded)));
        }
        if (r.total > shownLimit) {
            box.addView(a.makeButton("عرض المزيد (" + (r.total - shownLimit) + " متبقية)", Color.rgb(120, 120, 120), v -> {
                shownLimit += 200;
                render(expanded);
            }), a.lp(-1, 46, 4));
        }
        if (r.total == 0) box.addView(a.text("لا توجد حركات مطابقة.", 15, a.muted, false));
    }

    /** limit=0: كل المطابق (للتصدير). بلا نص بحث ولا حدود مبلغ: العدد من قاعدة البيانات والمعروض فقط يُفك. */
    private Result query(int limit) {
        Db.TxFilter f = new Db.TxFilter();
        f.office = filter.office;
        f.type = filter.type;
        f.currency = filter.currency;
        f.includeCancelled = filter.includeCancelled;
        f.from = period.from;
        f.to = period.to;
        Result r = new Result();
        r.names = a.db.officeNames();
        r.att = a.db.attachmentCounts(null);
        boolean simple = term.isEmpty() && minText.isEmpty() && maxText.isEmpty() && kindSel == 0 && whoSel == null;
        if (simple && limit > 0) {
            r.total = a.db.txCount(f);
            f.limit = limit;
            r.shown = a.db.txLog(f);
            return r;
        }
        // 12.7: مع نص بحث تُقرأ المرشّحة من نص البحث المحلي فقط (لا تُفك كل حركات الفترة)، والتحقق كما كان
        List<Model.Tx> all = term.isEmpty() ? a.db.txLog(f) : a.db.txSearch(f, term.toLowerCase(Locale.ROOT));
        BigDecimal lo = minText.isEmpty() ? null : new BigDecimal(Db.normalizeNumber(minText));
        BigDecimal hi = maxText.isEmpty() ? null : new BigDecimal(Db.normalizeNumber(maxText));
        String t = term.toLowerCase(Locale.ROOT);
        List<Model.Tx> out = new ArrayList<>();
        for (Model.Tx x : all) {
            if (!t.isEmpty()) {
                int d = a.db.decimals(x.currency);
                String hay = (Model.nz(r.names.get(x.officeId)) + " " + x.person + " " + x.description + " " + x.notes + " " + x.uuid + " " + Model.nz(x.userName) + " " + Model.nz(x.dest) + " " + x.currency
                        + " " + Model.plain(x.amount, d) + " " + Model.fmt(x.amount, d)).toLowerCase(Locale.ROOT);
                if (!hay.contains(t)) continue;
            }
            if (kindSel == 1 && x.isLinked()) continue;
            if (kindSel == 2 && !x.isFx()) continue;
            if (kindSel == 3 && !x.isTransfer()) continue;
            if (kindSel == 4 && (Model.FEE_NONE.equals(x.feeType) || x.fee == 0)) continue;
            if (kindSel == 5 && (r.att.get(x.id) == null || r.att.get(x.id) == 0)) continue;
            if (kindSel == 6 && x.replacesId == null) continue;
            if (whoSel != null && !whoSel.equals(Model.nz(x.userName))) continue;
            BigDecimal val = BigDecimal.valueOf(x.amount, a.db.decimals(x.currency));
            if (lo != null && val.compareTo(lo) < 0) continue;
            if (hi != null && val.compareTo(hi) > 0) continue;
            out.add(x);
        }
        r.total = out.size();
        r.shown = out;
        return r;
    }

    private List<String> describeFilters(Map<Long, String> names) {
        List<String> d = new ArrayList<>();
        if (!term.isEmpty()) d.add("بحث: " + term);
        if (filter.office != null) d.add("المكتب: " + Model.nz(names.get(filter.office)));
        if (filter.type != null) d.add(Model.TYPE_DEBIT.equals(filter.type) ? "لنا فقط" : "علينا فقط");
        if (filter.currency != null) d.add("العملة: " + filter.currency);
        if (period.hasStart() || period.to != Long.MAX_VALUE) d.add("الفترة: " + period.label);
        if (!minText.isEmpty()) d.add("من مبلغ " + minText);
        if (!maxText.isEmpty()) d.add("إلى مبلغ " + maxText);
        if (filter.includeCancelled) d.add("مع الملغاة");
        if (kindSel > 0) d.add("النوع: " + KINDS[kindSel]);
        if (whoSel != null) d.add("المحاسب: " + whoSel);
        return d;
    }

    /**
     * بطاقة حركة تُستخدم في السجل والكشف والتقرير اليومي وصفحة المكتب:
     * المستفيد والمبلغ بلون النوع (لنا أخضر / علينا أحمر)، ثم البيان مختصرًا، ثم التاريخ والتفاصيل.
     */
    LinearLayout txRow(Model.Tx t, String officeName, Integer attachments) {
        int dec = a.db.decimals(t.currency);
        boolean us = Model.TYPE_DEBIT.equals(t.type);
        boolean active = t.isActive();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = a.row();
        String who = !t.person.isEmpty() ? t.person : officeName != null ? officeName : "—";
        TextView name = a.text(who, 15, active ? a.ink : a.muted, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(a.dp(2), 0, a.dp(4), 0);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amt = a.text("\u2066" + Model.fmt(t.amount, dec) + " " + t.currency + "\u2069", 15, active ? (us ? a.green : a.red) : a.muted, true);
        amt.setPadding(a.dp(4), 0, a.dp(4), 0);
        if (!active) amt.setPaintFlags(amt.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        top.addView(amt, new LinearLayout.LayoutParams(-2, -2));
        top.addView(active ? a.chip(us ? "لنا" : "علينا", us ? a.green : a.red, us ? a.creditBg : a.debitBg)
                : t.isPending() ? a.chip("بانتظار الاعتماد", a.warnInk, a.warnBg)
                : a.chip(t.isRejected() ? "مرفوضة" : a.db.replacementOf(t.id) != null ? "معدّلة" : "ملغاة", a.muted, a.night ? a.line : Color.rgb(236, 233, 226)));
        box.addView(top);
        String desc = MessageParser.compact(t.description, t.person);
        if (!desc.isEmpty()) {
            TextView d = a.text(desc, 13, a.muted, false);
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            d.setPadding(a.dp(2), a.dp(2), a.dp(2), 0);
            box.addView(d);
        }
        List<String> meta = new ArrayList<>();
        meta.add(Model.date(t.createdAt));
        if (officeName != null && !t.person.isEmpty()) meta.add(officeName);
        if (t.hasFee()) meta.add(t.feeType + " " + Model.fmt(t.fee, a.db.decimals(t.feeCurrency)) + " " + t.feeCurrency);
        if (attachments != null && attachments > 0) meta.add("الصور: " + attachments);
        if (!t.service.isEmpty()) meta.add(t.service);
        if (!t.dest.isEmpty()) meta.add("إلى " + t.dest);
        if (active && t.replacesId != null) meta.add("تعديل لحركة سابقة");
        TextView m = a.text(Model.join(meta, " • "), 12, Ui.mix(a.muted, a.surface, 0.15f), false);
        m.setPadding(a.dp(2), a.dp(2), a.dp(2), 0);
        box.addView(m);
        return box;
    }

    // ===================================================================================
    // تفاصيل الحركة
    // ===================================================================================

    /** 12.4: تحويل الخاصة لعادية: بتاريخها الأصلي أو بتاريخ اليوم. */
    private void convertDialog(final Model.Tx t, final Runnable refresh) {
        if (Model.day(t.createdAt).equals(Model.day(Clock.now()))) {
            a.txScreen.showConvert(t, true, refresh);
            return;
        }
        new AlertDialog.Builder(a).setTitle("بأي تاريخ تنزل بالكشف والمطابقة؟")
                .setItems(new String[]{"بتاريخها الأصلي: " + Model.day(t.createdAt), "بتاريخ اليوم: " + Model.day(Clock.now())},
                        (d, w) -> a.txScreen.showConvert(t, w == 0, refresh))
                .setNegativeButton("إلغاء", null).show();
    }

    void details(final Model.Tx t0, final Runnable refresh) {
        final Model.Tx t = a.db.tx(t0.id) == null ? t0 : a.db.tx(t0.id);
        Model.Office o = a.db.office(t.officeId);
        int dec = a.db.decimals(t.currency);
        StringBuilder sb = new StringBuilder();
        sb.append("المكتب: ").append(o == null ? "—" : o.name)
                .append("\nالتاريخ: ").append(Model.date(t.createdAt))
                .append("\nالنوع: ").append(t.type)
                .append("\nالشخص: ").append(t.person)
                .append("\n").append(Metals.qtyWord(t.currency)).append(": ").append(Metals.show(Model.fmt(t.amount, dec), t.currency));
        if (t.hasFee()) sb.append("\n").append(t.feeType).append(": ").append(Metals.show(Model.fmt(t.fee, a.db.decimals(t.feeCurrency)), t.feeCurrency));
        if (!t.description.isEmpty()) sb.append("\nالبيان: ").append(t.description);
        if (!t.notes.isEmpty()) sb.append("\nالملاحظات: ").append(t.notes);
        if (!t.service.isEmpty()) sb.append("\nالخدمة: ").append(t.service);
        if (!t.dest.isEmpty()) sb.append("\nالوجهة: ").append(t.dest);
        if (t.isFx()) sb.append("\nساق من عملية تبديل عملة بسعر ").append(t.fxRate).append(" (تُعدَّل الساقان وتُلغيان معًا)");
        else if (t.isTransfer()) sb.append("\nساق من تحويل بين مكتبين").append(Model.nz(t.dest).isEmpty() ? "" : " (الطرف الآخر: «" + t.dest + "»)").append(" — تُلغى وتُعتمد الساقان معًا، ولا تُعدَّل");
        // 10.5 (§7): من أدخلها ومن اعتمدها ومن تحقق منها ومتى (بأرقام المستخدمين الثابتة)
        for (String line : a.db.txPeople(t)) sb.append("\n").append(line);
        sb.append("\nالمرجع: ").append(t.uuid);
        if (t.replacesId != null) sb.append("\nهذه الحركة تعديل لحركة سابقة.");
        final Long rep = t.isActive() ? null : a.db.replacementOf(t.id);
        if (t.isSpecial()) sb.append("\nحركة خاصة: ").append(t.special).append(t.isUnposted() ? " (لا تُحسب في الرصيد)" : t.isPending() ? "" : " (تُحسب في الرصيد، «معلّقة» في الكشف)");
        if (t.isPending()) sb.append("\nالحالة: بانتظار اعتماد المحاسب (لا أثر لها في الأرصدة بعد)");
        else if (t.isUnposted()) sb.append("\nالحالة: خاصة غير محسوبة (لا أثر لها في الأرصدة ولا الكشف بعد)");
        else if (Model.STATUS_CONVERTED.equals(t.status)) sb.append("\nالحالة: حُوّلت إلى حركة عادية");
        else if (Model.STATUS_VOIDED.equals(t.status)) sb.append("\nالحالة: خاصة أُلغيت قبل أن تُحسب");
        else if (t.isRejected()) sb.append("\nالحالة: مرفوضة (لم تدخل الأرصدة)");
        else if (!t.isActive()) sb.append("\nالحالة: ").append(rep != null ? "معدّلة (حلّت محلها حركة جديدة)" : "ملغاة بقيد عكسي");
        else sb.append("\nالحالة: نشطة");
        final List<Long> att = a.db.attachmentIds(t.id);
        List<String> acts = new ArrayList<>();
        if (!att.isEmpty()) acts.add("عرض الصور (" + att.size() + ")");
        if (t.isActive() && a.can(Auth.Perm.VERIFY) && a.db.usersEnabled()) acts.add("تم التحقق ✓");
        if (t.isActive() && !t.isLinked() && a.can(Auth.Perm.TX_EDIT) && !t.isSpecial()) acts.add("تعديل الحركة");
        // 12.4: الحركات الخاصة
        if (a.can(Auth.Perm.SPECIAL) && t.isSpecial() && !t.isPending()) {
            acts.add("تحويل لحركة عادية");
            acts.add("تعديل الحركة الخاصة");
        }
        if (a.can(Auth.Perm.SPECIAL) && t.isActive() && !t.isLinked() && !t.isSpecial()) acts.add("تعليم كحركة خاصة");
        if (a.can(Auth.Perm.SPECIAL) && t.isUnposted()) acts.add("إلغاء الحركة الخاصة");
        // 10.18: قص العملات يُعدَّل بساقيه معًا من شاشة القص
        if (t.isActive() && t.isFx() && a.can(Auth.Perm.TX_EDIT) && a.can(Auth.Perm.TX_ADD)) acts.add("تعديل القص");
        if (t.isActive() && a.can(Auth.Perm.TX_CANCEL)) acts.add("إلغاء الحركة بقيد عكسي");
        if (t.isPending() && a.can(Auth.Perm.APPROVE)) {
            acts.add("اعتماد الحركة");
            acts.add("رفض الحركة");
        }
        if (t.replacesId != null) acts.add("عرض الحركة الأصلية قبل التعديل");
        if (rep != null) acts.add("عرض الحركة البديلة بعد التعديل");
        if (o != null) acts.add("فتح كشف المكتب");
        final List<String> fa = acts;
        final Model.Office office = o;
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(4));
        TextView info = a.text(sb.toString(), 15, a.dark, false);
        info.setTextIsSelectable(true);
        l.addView(info);
        final AlertDialog[] dlg = new AlertDialog[1];
        for (final String act : fa) {
            l.addView(a.smallButton(act, act.startsWith("إلغاء") || act.startsWith("رفض") ? a.red : a.primary, v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                if (act.startsWith("تم التحقق")) {
                    try {
                        a.db.verifyTx(t.id);
                        a.toast("سُجّل تحققك ✓ (بلا قيد ولا أثر في الأرصدة)");
                        if (refresh != null) refresh.run();
                    } catch (Exception e) {
                        a.error(e);
                    }
                } else if (act.startsWith("عرض الصور")) showAttachments(t.id);
                else if (act.equals("تعديل القص")) a.fxScreen.edit(t);
                else if (act.equals("تحويل لحركة عادية")) convertDialog(t, refresh);
                else if (act.equals("تعليم كحركة خاصة")) a.txScreen.showMarkSpecial(t, refresh);
                else if (act.equals("إلغاء الحركة الخاصة")) a.confirm("إلغاء الحركة الخاصة", "لم تدخل الرصيد بعد، فتُلغى بلا قيد عكسي وتبقى في السجل.", "إلغاء الحركة", () -> {
                    try {
                        a.db.voidSpecial(t.id, "");
                        a.toast("أُلغيت ✓");
                        if (refresh != null) refresh.run();
                    } catch (Exception e) {
                        a.error(e);
                    }
                });
                else if (act.startsWith("تعديل")) a.txScreen.showEdit(t, refresh);
                else if (act.startsWith("إلغاء")) cancelDialog(t, refresh);
                else if (act.startsWith("اعتماد")) approve(t, refresh);
                else if (act.startsWith("رفض")) rejectDialog(t, refresh);
                else if (act.contains("الأصلية")) {
                    Model.Tx orig = a.db.tx(t.replacesId);
                    if (orig != null) details(orig, refresh);
                } else if (act.contains("البديلة")) {
                    Model.Tx nt = a.db.tx(rep);
                    if (nt != null) details(nt, refresh);
                } else if (office != null) a.statementScreen.show(office);
            }), a.lp(-1, 44, 3));
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(l);
        dlg[0] = new AlertDialog.Builder(a).setTitle("تفاصيل الحركة").setView(sv).setPositiveButton("إغلاق", null).show();
    }

    // ===================================================================================
    // الاعتماد المزدوج (ت4)
    // ===================================================================================

    void approve(final Model.Tx t, final Runnable refresh) {
        int dec = a.db.decimals(t.currency);
        a.confirm("اعتماد الحركة", "ستدخل الحركة الأرصدة بتاريخها الأصلي (" + Model.date(t.createdAt) + "):\n"
                + (Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ") + "\u2066" + Model.fmt(t.amount, dec) + " " + t.currency + "\u2069 — " + Model.nz(a.db.officeNames().get(t.officeId))
                + (t.userName.isEmpty() ? "" : "\nاقترحها: " + t.userName)
                + (a.db.similarTx(t).isEmpty() ? "" : "\n\n⚠ توجد حركة مشابهة (المكتب والمبلغ والاسم نفسها): تأكد أنها ليست مكررة قبل الاعتماد."), "اعتماد", () -> {
            try {
                a.db.approveTx(t.id);
                a.toast("اعتُمدت الحركة ودخلت الأرصدة");
                if (refresh != null) refresh.run();
            } catch (Exception e) {
                a.error(e);
            }
        });
    }

    void rejectDialog(final Model.Tx t, final Runnable refresh) {
        final EditText r = new EditText(a);
        r.setHint("سبب الرفض (يُحفظ في سجل العمليات)");
        new AlertDialog.Builder(a).setTitle("رفض الحركة").setMessage("لن تدخل الأرصدة، وتبقى ظاهرة في السجل كمرفوضة." + (a.db.cashLinked(t.id) ? "\n\n⚠ " + Db.REJECT_CASH_NOTE : ""))
                .setView(r).setPositiveButton("رفض", (x, z) -> {
                    try {
                        a.db.rejectTx(t.id, r.getText().toString());
                        a.toast("رُفضت الحركة");
                        if (refresh != null) refresh.run();
                    } catch (Exception e) {
                        a.error(e);
                    }
                }).setNegativeButton("رجوع", null).show();
    }

    /** قائمة الحركات بانتظار الاعتماد (للمحاسب والمدير). */
    void showPending() {
        if (!a.require(Auth.Perm.APPROVE)) return;
        a.here(this::showPending);
        a.tabbedScreen("بانتظار الاعتماد", a::showHome, "home");
        List<Model.Tx> l = a.db.pendingTx();
        a.hint("حركات كبيرة سجّلها موظف ولا أثر لها في الأرصدة حتى تُعتمد. «تصحيح واعتماد» يصلح الخطأ بدل الرفض. اضغط الحركة لتفاصيلها. من اقترح الحركة لا يعتمدها.");
        if (l.isEmpty()) a.add(a.text("لا توجد حركات بانتظار الاعتماد ✓", 15, a.muted, false));
        Map<Long, String> names = a.db.officeNames();
        for (final Model.Tx t : l) {
            LinearLayout x = a.card();
            x.addView(txRow(t, names.get(t.officeId), null));
            List<Model.Tx> sim = a.db.similarTx(t);
            if (!sim.isEmpty()) {
                Model.Tx o = sim.get(0);
                x.addView(a.text("⚠ تشبه حركة " + (o.isPending() ? "معلّقة" : "مسجّلة") + " بتاريخ " + Model.date(o.createdAt) + (o.userName.isEmpty() ? "" : " سجّلها " + o.userName)
                        + ": قد تكون مكررة.", 13, a.red, true));
            }
            LinearLayout r = a.row();
            r.addView(a.smallButton("اعتماد", a.primary, v -> approve(t, this::showPending)), a.weight(42, 1, 2));
            r.addView(a.smallButton("تصحيح واعتماد", a.accent, v -> a.txScreen.showApproveEdit(t, this::showPending)), a.weight(42, 1.3f, 2));
            r.addView(a.smallButton("رفض", a.red, v -> rejectDialog(t, this::showPending)), a.weight(42, 1, 2));
            x.addView(r);
            x.setOnClickListener(v -> details(t, this::showPending));
        }
    }

    void cancelDialog(final Model.Tx t, final Runnable refresh) {
        final EditText r = new EditText(a);
        r.setHint("سبب الإلغاء (يُحفظ في سجل التدقيق)");
        new AlertDialog.Builder(a).setTitle("إلغاء الحركة بقيد عكسي")
                .setMessage("سيُسجَّل قيد عكسي يلغي أثر الحركة وأجرتها بالكامل، وتبقى الحركة الأصلية ظاهرة كملغاة."
                        + (t.isFx() ? "\nتُلغى ساقا التبديل معًا." : t.isTransfer() ? "\nتُلغى ساقا التحويل بين المكتبين معًا." : ""))
                .setView(r).setPositiveButton("تنفيذ الإلغاء", (x, z) -> {
                    try {
                        a.db.cancelTx(t.id, r.getText().toString());
                        a.toast("تم إلغاء الحركة");
                        if (refresh != null) refresh.run();
                    } catch (Exception e) {
                        a.error(e);
                    }
                }).setNegativeButton("رجوع", null).show();
    }

    /** 8.0: الصور تُفك وتُصغَّر في خيط خلفي؛ الصورة كاملة تُفك عند الضغط للمشاركة فقط. */
    void showAttachments(long tx) {
        final List<Long> ids = a.db.attachmentIds(tx);
        a.busy("جارٍ فتح الصور…", p -> {
            List<Object[]> out = new ArrayList<>();
            for (Long id : ids) {
                byte[] raw = a.db.attachment(id);
                out.add(new Object[]{id, raw == null ? null : ImageUtil.decodeSampled(raw, 720)});
            }
            return out;
        }, res -> {
            @SuppressWarnings("unchecked") List<Object[]> imgs = (List<Object[]>) res;
            LinearLayout l = new LinearLayout(a);
            l.setOrientation(LinearLayout.VERTICAL);
            ScrollView s = new ScrollView(a);
            s.addView(l);
            int shown = 0;
            for (Object[] x : imgs) {
                final long id = (Long) x[0];
                Bitmap bm = (Bitmap) x[1];
                if (bm == null) {
                    l.addView(a.text("صورة رقم " + id + " تالفة أو مفقودة", 14, a.red, false));
                    continue;
                }
                ImageView im = new ImageView(a);
                im.setImageBitmap(bm);
                im.setAdjustViewBounds(true);
                im.setPadding(4, 8, 4, 8);
                l.addView(im, new LinearLayout.LayoutParams(-1, -2));
                im.setOnClickListener(v -> a.busy("جارٍ تجهيز الصورة…", p -> {
                    byte[] raw = a.db.attachment(id);
                    if (raw == null) throw new Exception("الصورة غير متاحة");
                    File f = new File(a.sharedDir(), "وصل-" + id + ".jpg");
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        o.write(raw);
                    }
                    return f;
                }, f -> a.shareFiles(Collections.singletonList((File) f), "image/jpeg", "صورة وصل")));
                shown++;
            }
            if (shown > 0) l.addView(a.text("اضغط على الصورة لمشاركتها", 13, a.muted, false), 0);
            new AlertDialog.Builder(a).setTitle("صور الحركة").setView(s).setPositiveButton("إغلاق", null).show();
        });
    }
}
