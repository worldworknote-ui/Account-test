package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 9.0.1: «حسابات العملاء» تحت تبويب الصندوق: لكل عميل رصيد بكل عملة («لنا» / «علينا») مستقل عن حسابات المكاتب.
 * الاستلام منه والتسليم له يمرّان بالصندوق (حركة نقد + حركة على حسابه في المعاملة نفسها)، ويمكن أن يُقيَّد على حسابه
 * بعملة غير عملة النقد (له 100 يورو فيأخذ دولارًا). وقيد بلا نقد، وتبديل عملة الرصيد، وكشف بالرصيد المتراكم يُصدَّر.
 */
final class ClientScreen {
    private final CashScreen c;
    private final MainActivity a;
    private int filter = 0;
    private String query = "";
    static final String[] FILTERS = {"الكل", "لنا عليهم", "علينا لهم", "رصيد صفر", "المؤرشفون"};

    ClientScreen(CashScreen c) {
        this.c = c;
        this.a = c.a;
    }

    private String bal(long v, String cur) { return a.m(c.sideText(v, cur)); }

    // ===================================================================================
    // في الصفحة الرئيسية للصندوق
    // ===================================================================================

    /** 12.0: «حسابات العملاء» صلاحية مستقلة. */
    boolean allowed() { return a.can(Auth.Perm.CLIENTS); }

    void homeSummary() {
        if (!allowed()) return;
        List<ClientBook.Client> all = a.db.clients();
        a.section("حسابات العملاء", all.isEmpty() ? null : "الكل (" + all.size() + ")", all.isEmpty() ? null : v -> list());
        LinearLayout card = a.card();
        if (all.isEmpty()) {
            card.addView(a.text("للعملاء الذين يستلمون منك أو يسلّمونك مباشرة ولهم حساب لديك (غير المكاتب). سجّل لكل عميل ما له وما عليه بكل عملة.", 13, a.muted, false));
            if (a.can(Auth.Perm.CASH)) card.addView(a.makeButton("+ إضافة عميل", a.primary, v -> edit(null, k -> page(k.id))), a.lp(-1, 46, 4));
            return;
        }
        Map<String, long[]> t = ClientBook.totals(a.db.clientBalances());
        if (t.isEmpty()) card.addView(a.text("كل حسابات العملاء مسدّدة (صفر).", 13, a.muted, false));
        for (Map.Entry<String, long[]> e : t.entrySet()) {
            long[] v = e.getValue();
            int d = a.db.decimals(e.getKey());
            LinearLayout r = a.row();
            r.addView(a.text(e.getKey(), 14, a.ink, true), new LinearLayout.LayoutParams(a.dp(52), -2));
            r.addView(a.text(v[0] == 0 ? "" : "لنا " + a.m(CashScreen.iso(Model.fmt(v[0], d))) + " (" + v[2] + ")", 13, a.green, true), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(a.text(v[1] == 0 ? "" : "علينا " + a.m(CashScreen.iso(Model.fmt(v[1], d))) + " (" + v[3] + ")", 13, a.red, true), new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(r);
        }
        card.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
        card.setOnClickListener(v -> list());
    }

    // ===================================================================================
    // قائمة العملاء
    // ===================================================================================

    void list() {
        if (!a.require(Auth.Perm.CLIENTS)) return;
        c.guard(this::listImpl);
    }

    private void listImpl() {
        c.tabbed("حسابات العملاء", c::home);
        a.eyeAction(this::listImpl);
        if (a.can(Auth.Perm.CASH)) a.headerAction(R.drawable.ic_plus, "عميل جديد", v -> edit(null, k -> page(k.id)));
        final List<ClientBook.Client> all = a.db.clients();
        final Map<Long, Map<String, Long>> bals = a.db.clientBalances();
        if (all.isEmpty()) {
            LinearLayout k = a.card();
            k.addView(a.text("لا يوجد عملاء بعد", 17, a.ink, true));
            k.addView(a.text("أضف العميل مرة واحدة، ثم سجّل ما تستلمه منه وما تسلّمه له من الصندوق، فيبقى حسابه بكل عملة مضبوطًا مع كشف مفصّل.", 14, a.muted, false));
            if (a.can(Auth.Perm.CASH)) k.addView(a.makeButton("+ إضافة عميل", a.primary, v -> edit(null, x -> page(x.id))), a.lp(-1, 50, 6));
            return;
        }
        // الإجمالي
        Map<String, long[]> t = ClientBook.totals(bals);
        if (!t.isEmpty()) {
            LinearLayout card = a.card();
            card.addView(a.text("الإجمالي", 14, a.primaryInk, true));
            for (Map.Entry<String, long[]> e : t.entrySet()) {
                long[] v = e.getValue();
                int d = a.db.decimals(e.getKey());
                card.addView(a.text(CashScreen.iso(e.getKey()) + "   لنا عليهم " + a.m(CashScreen.iso(Model.fmt(v[0], d))) + "   •   علينا لهم " + a.m(CashScreen.iso(Model.fmt(v[1], d))), 13, a.dark, false));
            }
        }
        LinearLayout sr = a.officeHub.searchBox("ابحث باسم العميل أو هاتفه");
        final EditText q = (EditText) sr.getTag();
        q.setText(query);
        a.body.addView(sr, a.lp(-1, 50, 4));
        final LinearLayout chips = a.row();
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(a.strip(chips), a.lp(-1, -2, 2));
        a.body.addView(box);
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
            query = q.getText().toString();
            String f = query.trim().toLowerCase(Locale.ROOT);
            box.removeAllViews();
            int n = 0;
            for (ClientBook.Client k : all) {
                if (!f.isEmpty() && !(k.name + " " + k.phone).toLowerCase(Locale.ROOT).contains(f)) continue;
                Map<String, Long> b = bals.containsKey(k.id) ? bals.get(k.id) : new HashMap<>();
                if (!passes(k, b)) continue;
                box.addView(card(k, b), a.lp(-1, -2, 3));
                n++;
            }
            if (n == 0) box.addView(a.text("لا يوجد عملاء مطابقون.", 14, a.muted, false));
        };
        q.addTextChangedListener(Ui.watcher(fill[0]));
        fill[0].run();
    }

    private boolean passes(ClientBook.Client k, Map<String, Long> b) {
        switch (filter) {
            case 1:
                if (!k.active) return false;
                for (long v : b.values()) if (v > 0) return true;
                return false;
            case 2:
                if (!k.active) return false;
                for (long v : b.values()) if (v < 0) return true;
                return false;
            case 3: return k.active && b.isEmpty();
            case 4: return !k.active;
            default: return k.active;
        }
    }

    private View card(final ClientBook.Client k, Map<String, Long> b) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
        r.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
        TextView av = a.text(k.name.isEmpty() ? "؟" : k.name.substring(0, k.name.offsetByCodePoints(0, 1)), 17, a.primaryInk, true);
        av.setGravity(Gravity.CENTER);
        av.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(a.primarySoft);
        av.setBackground(g);
        r.addView(av, new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(10), 0, 0, 0);
        TextView nm = a.text(k.name + (k.phone.isEmpty() ? "" : "   " + CashScreen.iso(k.phone)), 16, k.active ? a.ink : a.muted, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm);
        Ui.Flow f = a.flow();
        for (Map.Entry<String, Long> e : b.entrySet()) {
            long v = e.getValue();
            TextView t = a.chip(a.amountsHidden() ? "•••• " + e.getKey() : CashScreen.iso(Model.fmt(Math.abs(v), a.db.decimals(e.getKey())) + " " + e.getKey()) + " " + ClientBook.side(v),
                    v > 0 ? a.green : a.red, v > 0 ? a.creditBg : a.debitBg);
            t.setTextSize(12);
            f.addView(t);
        }
        if (b.isEmpty()) col.addView(a.text(k.active ? "الحساب مسدَّد (صفر)" : "مؤرشف", 12, a.muted, false));
        else col.addView(f);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.setOnClickListener(v -> page(k.id));
        return r;
    }

    // ===================================================================================
    // اختيار عميل (من نموذج الاستلام والتسليم)
    // ===================================================================================

    void pick(final Consumer<ClientBook.Client> done) {
        final List<ClientBook.Client> all = new ArrayList<>();
        for (ClientBook.Client k : a.db.clients()) if (k.active) all.add(k);
        final Map<Long, Map<String, Long>> bals = a.db.clientBalances();
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(16), a.dp(8), a.dp(16), 0);
        final EditText q = a.field("ابحث بالاسم أو الهاتف");
        l.addView(q, a.lp(-1, 48, 2));
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        l.addView(sv, new LinearLayout.LayoutParams(-1, a.dp(320)));
        final AlertDialog d = new AlertDialog.Builder(a).setTitle("اختر العميل").setView(l)
                .setPositiveButton("+ عميل جديد", (x, w) -> edit(null, done)).setNegativeButton("إلغاء", null).create();
        final Runnable fill = () -> {
            box.removeAllViews();
            String f = q.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (final ClientBook.Client k : all) {
                if (!f.isEmpty() && !(k.name + " " + k.phone).toLowerCase(Locale.ROOT).contains(f)) continue;
                Map<String, Long> b = bals.containsKey(k.id) ? bals.get(k.id) : new HashMap<>();
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Long> e : b.entrySet()) parts.add(bal(e.getValue(), e.getKey()));
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
                row.setBackground(a.ripple(a.round(a.surface, a.line, 12), 12));
                row.addView(a.text(k.name, 15, a.ink, true));
                row.addView(a.text(parts.isEmpty() ? "رصيده صفر" : Model.join(parts, " • "), 12, a.muted, false));
                row.setOnClickListener(v -> {
                    d.dismiss();
                    done.accept(k);
                });
                box.addView(row, a.lp(-1, -2, 2));
            }
            if (box.getChildCount() == 0) box.addView(a.text(all.isEmpty() ? "لا يوجد عملاء بعد. أضف عميلًا." : "لا نتائج.", 13, a.muted, false));
        };
        q.addTextChangedListener(Ui.watcher(fill));
        fill.run();
        d.show();
    }

    /** إضافة عميل أو تعديل بياناته. */
    void edit(final ClientBook.Client k, final Consumer<ClientBook.Client> after) {
        if (!a.require(Auth.Perm.CASH)) return;
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final EditText name = a.field("اسم العميل"), phone = a.field("الهاتف (اختياري)"), note = a.field("ملاحظة (اختياري)");
        phone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        if (k != null) {
            name.setText(k.name);
            phone.setText(k.phone);
            note.setText(k.note);
        }
        l.addView(name, a.lp(-1, 52, 2));
        l.addView(phone, a.lp(-1, 52, 2));
        l.addView(note, a.lp(-1, 52, 2));
        final AlertDialog d = new AlertDialog.Builder(a).setTitle(k == null ? "عميل جديد" : "بيانات العميل").setView(l)
                .setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                ClientBook.Client n = new ClientBook.Client();
                n.id = k == null ? 0 : k.id;
                n.name = name.getText().toString().trim();
                n.phone = phone.getText().toString().trim();
                n.note = note.getText().toString().trim();
                long id = a.db.saveClient(n);
                d.dismiss();
                a.toast(k == null ? "أُضيف العميل ✓" : "حُفظت البيانات");
                after.accept(a.db.client(id));
            } catch (Exception e) {
                a.error(e);
            }
        }));
        d.show();
    }

    // ===================================================================================
    // صفحة العميل
    // ===================================================================================

    void page(final long id) {
        if (!a.require(Auth.Perm.CLIENTS)) return;
        c.guard(() -> pageImpl(id));
    }

    private void pageImpl(final long id) {
        final ClientBook.Client k = a.db.client(id);
        if (k == null) {
            listImpl();
            return;
        }
        c.tabbed(k.name, this::listImpl);
        a.eyeAction(() -> pageImpl(id));
        if (a.can(Auth.Perm.CASH)) a.headerAction(R.drawable.ic_edit, "بيانات العميل", v -> options(k));
        a.headerAction(R.drawable.ic_report, "كشف الحساب", v -> statement(k, a.reportsScreen.range()[0], a.reportsScreen.range()[1]));
        final List<ClientBook.Move> moves = a.db.clientMoves(k.id);
        Map<String, Long> b = ClientBook.balances(moves);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(14));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, a.night ? new int[]{Color.rgb(28, 44, 72), Color.rgb(14, 22, 40)} : new int[]{CashScreen.NAVY, CashScreen.NAVY2});
        g.setCornerRadius(a.dp(20));
        card.setBackground(g);
        card.addView(c.plain("رصيد العميل" + (k.phone.isEmpty() ? "" : "   •   " + CashScreen.iso(k.phone)), 13, Color.rgb(196, 210, 232), false));
        if (b.isEmpty()) card.addView(c.plain("الحساب مسدَّد بالكامل (صفر)", 18, Color.WHITE, true));
        for (Map.Entry<String, Long> e : b.entrySet()) {
            long v = e.getValue();
            LinearLayout r = a.row();
            r.setPadding(0, a.dp(4), 0, a.dp(4));
            r.addView(c.plain(e.getKey(), 15, Color.rgb(214, 224, 240), false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView amt = c.plain(a.amountsHidden() ? "••••" : Model.fmt(Math.abs(v), a.db.decimals(e.getKey())), 20, Color.WHITE, true);
            amt.setTextDirection(View.TEXT_DIRECTION_LTR);
            r.addView(amt, new LinearLayout.LayoutParams(-2, -2));
            TextView chip = c.plain(ClientBook.side(v), 12, v > 0 ? Color.rgb(20, 92, 53) : Color.rgb(142, 31, 39), true);
            chip.setPadding(a.dp(10), a.dp(1), a.dp(10), a.dp(2));
            GradientDrawable cg = new GradientDrawable();
            cg.setColor(v > 0 ? Color.rgb(227, 241, 232) : Color.rgb(248, 227, 228));
            cg.setCornerRadius(a.dp(50));
            chip.setBackground(cg);
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-2, -2);
            chp.setMargins(a.dp(8), 0, 0, 0);
            r.addView(chip, chp);
            card.addView(r);
        }
        card.addView(c.plain("«لنا»: العميل مدين لنا • «علينا»: له عندنا", 11, Color.rgb(170, 186, 212), false));
        if (!k.note.isEmpty()) card.addView(c.plain(k.note, 12, Color.rgb(196, 210, 232), false));
        a.body.addView(card, a.lp(-1, -2, 6));
        if (!k.active) a.notice(R.drawable.ic_info, "الحساب مؤرشف: لا تُسجَّل عليه حركات جديدة.", false, "إعادته", v -> {
            try {
                a.db.setClientActive(k.id, true);
                pageImpl(id);
            } catch (Exception e) {
                a.error(e);
            }
        });

        if (k.active && a.can(Auth.Perm.CASH)) {
            LinearLayout r1 = a.row(), r2 = a.row();
            r1.addView(c.tile(R.drawable.ic_download, "استلام منه", "يدخل الصندوق", c.green(), v -> c.form(CashBox.IN, null, k)), a.weight(-2, 1, 4));
            r1.addView(c.tile(R.drawable.ic_upload, "تسليم له", "يخرج من الصندوق", c.red(), v -> c.form(CashBox.OUT, null, k)), a.weight(-2, 1, 4));
            r2.addView(c.tile(R.drawable.ic_edit, "قيد بلا نقد", "رصيد سابق أو تسوية", c.gray(), v -> entry(k)), a.weight(-2, 1, 4));
            r2.addView(c.tile(R.drawable.ic_coins, "تبديل عملة", "رصيده بعملة أخرى", c.teal(), v -> swap(k)), a.weight(-2, 1, 4));
            a.body.addView(r1, a.lp(-1, -2, 2));
            a.body.addView(r2, a.lp(-1, -2, 2));
        }

        a.section("الحركات", "كشف الحساب", v -> statement(k, a.reportsScreen.range()[0], a.reportsScreen.range()[1]));
        if (moves.isEmpty()) {
            a.hint("لا حركات بعد. «تسليم له» أو «استلام منه» يسجّل النقد في الصندوق وعلى حسابه معًا.");
            return;
        }
        // الرصيد المتراكم لكل عملة بعد كل حركة
        Map<String, Long> run = new HashMap<>();
        long[] after = new long[moves.size()];
        for (int i = 0; i < moves.size(); i++) {
            ClientBook.Move m = moves.get(i);
            long nb = (run.containsKey(m.currency) ? run.get(m.currency) : 0) + m.effect();
            run.put(m.currency, nb);
            after[i] = nb;
        }
        int shown = 0;
        for (int i = moves.size() - 1; i >= 0 && shown < 200; i--, shown++) a.body.addView(moveRow(moves.get(i), after[i], k), a.lp(-1, -2, 2));
        if (moves.size() > 200) a.hint("تظهر آخر 200 حركة؛ كلها في «كشف الحساب».");
    }

    private View moveRow(final ClientBook.Move m, long after, final ClientBook.Client k) {
        Map<String, Integer> dec = a.db.decimalsMap();
        LinearLayout r = a.row();
        r.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        r.setBackground(a.ripple(a.round(a.surface, a.line, 14), 14));
        int col = !m.counts() ? a.muted : m.dir > 0 ? a.green : a.red;
        LinearLayout ic = new LinearLayout(a);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(Ui.mix(a.surface, col, a.night ? 0.3f : 0.12f));
        cg.setCornerRadius(a.dp(12));
        ic.setBackground(cg);
        int icon = ClientBook.CASH.equals(m.kind) ? (m.cashDir > 0 ? R.drawable.ic_download : R.drawable.ic_upload)
                : ClientBook.SWAP.equals(m.kind) ? R.drawable.ic_coins : R.drawable.ic_edit;
        ic.addView(a.icon(icon, col, 20), new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
        r.addView(ic, new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
        LinearLayout cc = new LinearLayout(a);
        cc.setOrientation(LinearLayout.VERTICAL);
        cc.setPadding(a.dp(10), 0, a.dp(6), 0);
        cc.addView(a.text(ClientBook.text(m, dec, true), 14, m.counts() ? a.ink : a.muted, true));
        String sub = (m.description.isEmpty() ? "" : m.description + "\n") + Model.date(m.createdAt) + (m.cancelled() ? "   •   ملغاة" : "")
                + (m.counts() ? "\nالرصيد بعدها: " + bal(after, m.currency) : "");
        TextView s = a.text(sub, 12, a.muted, false);
        s.setMaxLines(4);
        cc.addView(s);
        r.addView(cc, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout amt = new LinearLayout(a);
        amt.setOrientation(LinearLayout.VERTICAL);
        amt.setGravity(Gravity.LEFT);
        TextView av = a.text(a.amountsHidden() ? "••••" : (m.dir > 0 ? "+" : "−") + Model.fmt(m.amount, CashBox.dec(dec, m.currency)), 16, col, true);
        av.setTextDirection(View.TEXT_DIRECTION_LTR);
        amt.addView(av);
        amt.addView(a.text(m.currency + "  " + (m.dir > 0 ? "لنا" : "علينا"), 11, a.muted, false));
        r.addView(amt, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(v -> details(m, k));
        return r;
    }

    private void details(final ClientBook.Move m, final ClientBook.Client k) {
        Map<String, Integer> dec = a.db.decimalsMap();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"العميل", k.name});
        rows.add(new String[]{"الحركة", ClientBook.text(m, dec, true)});
        rows.add(new String[]{"على حسابه", (m.dir > 0 ? "لنا عليه " : "علينا له ") + c.money(m.amount, m.currency)});
        if (m.cashCurrency != null) rows.add(new String[]{m.cashDir > 0 ? "دخل الصندوق" : "خرج من الصندوق", c.money(m.cashAmount, m.cashCurrency)});
        if (!Model.nz(m.rate).isEmpty() && m.cashCurrency != null && !m.cashCurrency.equals(m.currency))
            rows.add(new String[]{"السعر", CashScreen.iso("1 " + m.currency + " = " + m.rate + " " + m.cashCurrency)});
        if (!m.description.isEmpty()) rows.add(new String[]{"البيان", m.description});
        rows.add(new String[]{"التاريخ", Model.date(m.createdAt)});
        rows.add(new String[]{"بواسطة", m.userName});
        if (m.cancelled()) rows.add(new String[]{"الحالة", "ملغاة" + (m.cancelReason.isEmpty() ? "" : " — " + m.cancelReason)});
        AlertDialog.Builder b = new AlertDialog.Builder(a).setView(c.detailView("حركة على حساب العميل", rows, null)).setPositiveButton("إغلاق", null);
        if (m.counts() && a.can(Auth.Perm.TX_CANCEL)) b.setNegativeButton("إلغاء الحركة", (x, w) -> {
            final EditText reason = a.field("سبب الإلغاء");
            LinearLayout l = new LinearLayout(a);
            l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
            l.addView(reason, new LinearLayout.LayoutParams(-1, a.dp(50)));
            new AlertDialog.Builder(a).setTitle("إلغاء الحركة").setView(l)
                    .setMessage(m.cashUuid != null ? "تُلغى معها حركة الصندوق (يعود النقد كما كان)." : ClientBook.SWAP.equals(m.kind) ? "يُلغى التبديل بسطريه." : null)
                    .setPositiveButton("إلغاء الحركة", (d2, w2) -> {
                        try {
                            a.db.cancelClientMove(m.id, reason.getText().toString().trim());
                            a.toast("أُلغيت الحركة");
                            pageImpl(k.id);
                        } catch (Exception e) {
                            a.error(e);
                        }
                    }).setNegativeButton("رجوع", null).show();
        });
        b.show();
    }

    private void options(final ClientBook.Client k) {
        final List<String> acts = new ArrayList<>();
        acts.add("تعديل البيانات");
        acts.add(k.active ? "أرشفة الحساب (رصيده صفر)" : "إعادة الحساب");
        new AlertDialog.Builder(a).setTitle(k.name).setItems(acts.toArray(new String[0]), (d, w) -> {
            try {
                if (w == 0) edit(k, x -> pageImpl(x.id));
                else {
                    a.db.setClientActive(k.id, !k.active);
                    a.toast(k.active ? "أُرشف الحساب" : "أُعيد الحساب");
                    pageImpl(k.id);
                }
            } catch (Exception e) {
                a.error(e);
            }
        }).show();
    }

    // ===================================================================================
    // قيد بلا نقد
    // ===================================================================================

    private void entry(final ClientBook.Client k) {
        c.screen("قيد على حساب " + k.name, () -> pageImpl(k.id));
        a.hint("يسجّل على حساب العميل دون أن يمس الصندوق: رصيد قديم قبل استخدام التطبيق، أو تسوية متفق عليها.");
        a.add(a.label("الجهة"));
        final Ui.Seg side = a.new Seg(new String[]{"لنا عليه (مدين لنا)", "علينا له (له عندنا)"}, new int[]{a.green, a.red}, -1, false);
        a.body.addView(side.view, a.lp(-1, -2, 2));
        a.add(a.label("العملة"));
        final List<String> curs = new ArrayList<>();
        for (Model.Currency x : a.db.currencies(false)) curs.add(x.code);
        final Ui.Seg cur = a.new Seg(curs.toArray(new String[0]), null, -1, curs.size() > 4);
        a.body.addView(cur.view, a.lp(-1, -2, 2));
        final EditText amount = a.amountInput("المبلغ");
        amount.setTextSize(20);
        final EditText desc = a.input("البيان (مثل: رصيد سابق)");
        c.dateButton(null);
        a.footer(a.makeButton("مراجعة القيد", a.primary, v -> {
            try {
                if (side.none()) throw new Exception("حدّد: لنا عليه أم علينا له");
                if (cur.none()) throw new Exception("اختر العملة");
                final String cu = cur.label();
                final long am = Db.minor(amount.getText().toString(), a.db.decimals(cu));
                if (am <= 0) throw new Exception("المبلغ يجب أن يكون أكبر من صفر");
                final int dir = side.selected == 0 ? 1 : -1;
                final String ds = desc.getText().toString().trim();
                final long when = c.now();
                Map<String, Long> b = ClientBook.balances(a.db.clientMoves(k.id));
                long before = b.containsKey(cu) ? b.get(cu) : 0;
                List<String[]> rows = new ArrayList<>();
                rows.add(new String[]{"العميل", k.name});
                rows.add(new String[]{"القيد", (dir > 0 ? "لنا عليه " : "علينا له ") + c.money(am, cu)});
                rows.add(new String[]{"رصيده " + cu, c.sideText(before, cu) + " ← " + c.sideText(before + dir * am, cu)});
                rows.add(new String[]{"الصندوق", "لا يتغير (بلا نقد)"});
                if (!ds.isEmpty()) rows.add(new String[]{"البيان", ds});
                rows.add(new String[]{"التاريخ", Model.date(when)});
                c.confirmDialog("راجع قبل التسجيل", rows, null, "تسجيل", () -> a.busy("جارٍ التسجيل…", p -> {
                    a.db.addClientEntry(k.id, cu, am, dir, ds, when);
                    return null;
                }, res -> {
                    a.toast("سُجّل القيد ✓");
                    pageImpl(k.id);
                }));
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    // ===================================================================================
    // تبديل عملة الرصيد (بلا نقد)
    // ===================================================================================

    private void swap(final ClientBook.Client k) {
        final Map<String, Long> b = ClientBook.balances(a.db.clientMoves(k.id));
        if (b.isEmpty()) {
            a.toast("رصيد العميل صفر: لا شيء لتبديله");
            return;
        }
        c.screen("تبديل عملة رصيد " + k.name, () -> pageImpl(k.id));
        final Map<String, Integer> dec = a.db.decimalsMap();
        a.hint("ينقل جزءًا من رصيد العميل من عملة إلى أخرى بسعر تتفقان عليه، بالجهة نفسها (ما له يبقى له، وما عليه يبقى عليه). لا يمس الصندوق. "
                + "إن كان سيأخذ نقدًا بعملة أخرى فاستخدم «تسليم له» واختر «عملة أخرى» مباشرة.");
        a.add(a.label("من رصيده بـ"));
        final List<String> have = new ArrayList<>(b.keySet());
        final Ui.Seg from = a.new Seg(have.toArray(new String[0]), null, have.size() == 1 ? 0 : -1, have.size() > 4);
        a.body.addView(from.view, a.lp(-1, -2, 2));
        final TextView fromBal = a.add(a.text("", 13, a.primaryInk, true));
        final EditText amount = a.amountInput("المبلغ المبدَّل");
        a.add(a.label("إلى"));
        final List<String> curs = new ArrayList<>();
        for (Model.Currency x : a.db.currencies(false)) curs.add(x.code);
        final Ui.Seg to = a.new Seg(curs.toArray(new String[0]), null, -1, curs.size() > 4);
        a.body.addView(to.view, a.lp(-1, -2, 2));
        final EditText rate = a.field("السعر: 1 من الأولى = كم من الثانية");
        Ui.numeric(rate);
        a.body.addView(rate, a.lp(-1, 50, 2));
        final TextView market = a.add(a.text("", 12, a.primary, true));
        final EditText toAmt = a.amountInput("المبلغ بالعملة الجديدة (يُحسب تلقائيًا)");
        final EditText desc = a.input("البيان (اختياري)");
        c.dateButton(null);
        final Runnable calc = () -> {
            String f = from.label(), t = to.label();
            if (f != null) {
                long v = b.get(f);
                fromBal.setText("رصيده: " + c.sideText(v, f));
                if (amount.getText().toString().trim().isEmpty() && !amount.hasFocus())
                    amount.setText(BigDecimal.valueOf(Math.abs(v), CashBox.dec(dec, f)).stripTrailingZeros().toPlainString());
            }
            if (f == null || t == null || f.equals(t)) {
                market.setText("");
                return;
            }
            BigDecimal mk = CashBox.crossRate(f, t, a.db.usdRates());
            market.setText(mk == null ? "لا يوجد سعر سوق لهما" : "سعر السوق: " + CashScreen.iso("1 " + f + " = " + mk.toPlainString() + " " + t) + "   (اضغط لاستخدامه)");
            market.setOnClickListener(mk == null ? null : v -> rate.setText(mk.toPlainString()));
            try {
                long am = Db.minor(amount.getText().toString(), CashBox.dec(dec, f));
                Long ta = CashBox.counterAmount(am, CashBox.dec(dec, f), rate.getText().toString(), CashBox.dec(dec, t));
                if (ta != null) toAmt.setText(BigDecimal.valueOf(ta, CashBox.dec(dec, t)).stripTrailingZeros().toPlainString());
            } catch (Exception ignored) {
                // ناقص
            }
        };
        from.listener = i -> {
            amount.setText("");
            calc.run();
        };
        to.listener = i -> calc.run();
        amount.addTextChangedListener(Ui.watcher(calc));
        rate.addTextChangedListener(Ui.watcher(calc));
        calc.run();
        a.footer(a.makeButton("مراجعة التبديل", c.teal(), v -> {
            try {
                if (from.none() || to.none()) throw new Exception("اختر العملتين");
                final String f = from.label(), t = to.label();
                final long am = Db.minor(amount.getText().toString(), CashBox.dec(dec, f));
                final long ta = Db.minor(toAmt.getText().toString(), CashBox.dec(dec, t));
                String err = ClientBook.validateSwap(b, f, am, t, ta);
                if (err != null) throw new Exception(err);
                final String ds = desc.getText().toString().trim();
                final long when = c.now();
                long bf = b.get(f), sideNow = bf > 0 ? 1 : -1;
                long bt = b.containsKey(t) ? b.get(t) : 0;
                List<String[]> rows = new ArrayList<>();
                rows.add(new String[]{"العميل", k.name});
                rows.add(new String[]{"يُطفأ من رصيده", c.money(am, f)});
                rows.add(new String[]{"ويُضاف مقابله", c.money(ta, t)});
                rows.add(new String[]{"السعر", CashScreen.iso("1 " + f + " = " + ClientBook.rateOf(am, CashBox.dec(dec, f), ta, CashBox.dec(dec, t)) + " " + t)});
                rows.add(new String[]{"رصيده " + f, c.sideText(bf, f) + " ← " + c.sideText(bf - sideNow * am, f)});
                rows.add(new String[]{"رصيده " + t, c.sideText(bt, t) + " ← " + c.sideText(bt + sideNow * ta, t)});
                rows.add(new String[]{"الصندوق", "لا يتغير (بلا نقد)"});
                if (!ds.isEmpty()) rows.add(new String[]{"البيان", ds});
                c.confirmDialog("راجع قبل التسجيل", rows, null, "تسجيل", () -> a.busy("جارٍ التسجيل…", p -> {
                    a.db.addClientSwap(k.id, f, am, t, ta, ds, when);
                    return null;
                }, res -> {
                    a.toast("سُجّل التبديل ✓");
                    pageImpl(k.id);
                }));
            } catch (Exception e) {
                a.error(e);
            }
        }));
    }

    // ===================================================================================
    // كشف الحساب (شاشة + PDF / Excel)
    // ===================================================================================

    void statement(final ClientBook.Client k, final long from, final long to) {
        a.busy("جارٍ بناء الكشف…", p -> build(k, from, to), res -> {
            final Report r = (Report) res;
            c.screen(r.title, () -> pageImpl(k.id));
            a.reportsScreen.periodCard(() -> statement(k, a.reportsScreen.range()[0], a.reportsScreen.range()[1]));
            LinearLayout bar = a.row();
            bar.addView(a.smallButton("تصدير PDF / Excel", a.primary, v -> a.exportUi.reportDialog(r.title, () -> statement(k, from, to),
                    (images, st) -> Collections.singletonList(build(k, from, to)))), a.weight(44, 1, 2));
            a.body.addView(bar, a.lp(-1, -2, 4));
            ReportRender.render(a, r);
        });
    }

    private Report build(ClientBook.Client k, long from, long to) {
        Report r = ClientBook.statement(k, a.db.clientMoves(k.id), from, to, a.db.decimalsMap(), a.db.setting("office_name", ""));
        Entitlements e = License.current;
        r.stamp(e.watermark(), e.lid);
        return r;
    }

    static String round2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString(); }
}
