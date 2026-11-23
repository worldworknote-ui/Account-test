package com.smart.hawala;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * تبويب «المكاتب»: قائمة المكاتب بأرصدتها ومرشحات سريعة، وصفحة لكل مكتب تجمع كل ما يخصه:
 * الرصيد، حركة جديدة، الكشف، مطابقة الرصيد، الحركات، المطابقات، والبيانات.
 * لا تحسب هذه الشاشة أي رصيد بنفسها: كل الأرصدة من دفتر القيود (Db.officeBalances / Db.balances).
 */
final class OfficeHub {
    private final MainActivity a;
    private String query = "";
    private int filter = 0;
    private int innerTab = 0;
    static final String[] FILTERS = {"الكل", "المثبّتة", "لنا", "علينا", "الموقوفة"};

    OfficeHub(MainActivity a) { this.a = a; }

    // ===================================================================================
    // آخر المكاتب المفتوحة (للصفحة الرئيسية واختيار المكتب)
    // ===================================================================================

    static List<Long> recentIds(Db db) {
        List<Long> out = new ArrayList<>();
        for (String s : db.setting("recent_offices", "").split(",")) {
            try {
                long id = Long.parseLong(s.trim());
                if (!out.contains(id)) out.add(id);
            } catch (NumberFormatException ignored) {
                // تجاهل
            }
        }
        return out;
    }

    static void touch(Db db, long officeId) {
        try {
            db.touchOffice(officeId);
        } catch (Exception ignored) {
            // ترتيب «آخر ما فتحته» غير حرج
        }
        List<Long> ids = recentIds(db);
        ids.remove(officeId);
        ids.add(0, officeId);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size() && i < 8; i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        db.setSettingQuiet("recent_offices", sb.toString());
    }

    List<Model.Office> recentOffices(int max) {
        List<Model.Office> out = new ArrayList<>();
        for (long id : recentIds(a.db)) {
            Model.Office o = a.db.office(id);
            if (o != null) out.add(o);
            if (out.size() >= max) break;
        }
        return out;
    }

    // ===================================================================================
    // قائمة المكاتب
    // ===================================================================================

    void showList() {
        if (!a.require(Auth.Perm.VIEW)) return;
        a.here(this::showList);
        a.rootScreen("المكاتب", "offices");
        a.eyeAction(this::showList);
        if (a.can(Auth.Perm.OFFICES)) a.headerAction(R.drawable.ic_plus, "إضافة مكتب", v -> a.officesScreen.form(null));
        final List<Model.Office> all = a.db.offices();
        final Map<Long, Map<String, Long>> balAll = a.db.officeBalances();
        final int sort = OfficeOrder.clamp((int) a.db.settingLong("office_sort", OfficeOrder.ALPHA));
        OfficeOrder.sort(all, sort, a.db.officeOrderInputs(balAll));
        final Map<String, java.math.BigDecimal> usdRates = a.db.usdRates();
        if (all.isEmpty()) {
            LinearLayout c = a.card();
            c.addView(a.text("لا توجد مكاتب بعد", 17, a.ink, true));
            c.addView(a.text("أضف المكاتب التي تتعامل معها، ثم سجّل الحركات عليها. لكل مكتب صفحة فيها رصيده وكشفه ومطابقاته.", 14, a.muted, false));
            if (a.can(Auth.Perm.OFFICES)) c.addView(a.makeButton("إضافة مكتب", a.primary, v -> a.officesScreen.form(null)), a.lp(-1, 50, 6));
            return;
        }
        final Map<Long, Map<String, Long>> bal = balAll;
        final Map<String, Integer> dec = a.db.decimalsMap();

        LinearLayout sr = searchBox("ابحث باسم المكتب أو الهاتف");
        final EditText q = (EditText) sr.getTag();
        q.setText(query);
        a.body.addView(sr, a.lp(-1, 50, 4));
        // 9.0: الترتيب: أبجديًا، الأكبر رصيدًا، الأصغر رصيدًا، الأكثر استخدامًا، آخر ما فتحته (المثبّتة أولًا دائمًا)
        LinearLayout so = a.row();
        so.addView(a.icon(R.drawable.ic_sliders, a.muted, 18), new LinearLayout.LayoutParams(a.dp(18), a.dp(18)));
        so.addView(a.text("الترتيب:", 13, a.muted, false));
        TextView pick = a.pill(OfficeOrder.NAMES[sort] + "  ▾", true, v -> new android.app.AlertDialog.Builder(a).setTitle("ترتيب المكاتب")
                .setSingleChoiceItems(OfficeOrder.NAMES, sort, (d, w) -> {
                    d.dismiss();
                    a.db.setSettingQuiet("office_sort", "" + w);
                    showList();
                }).setNegativeButton("إلغاء", null).show());
        so.addView(pick, new LinearLayout.LayoutParams(-2, -2));
        if (sort == OfficeOrder.BIGGEST || sort == OfficeOrder.SMALLEST)
            so.addView(a.text("(الميزان بالدولار)", 12, a.muted, false));
        a.body.addView(so, a.lp(-1, -2, 2));

        final LinearLayout chips = a.row();
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] fill = new Runnable[1];
        final Runnable drawChips = () -> {
            chips.removeAllViews();
            for (int i = 0; i < FILTERS.length; i++) {
                final int k = i;
                chips.addView(a.pill(FILTERS[i], i == filter, v -> {
                    filter = k;
                    fill[0].run();
                }), a.gap(-2, -2, 3));
            }
        };
        a.body.addView(a.strip(chips), a.lp(-1, -2, 4));
        a.body.addView(list);
        fill[0] = () -> {
            drawChips.run();
            query = q.getText().toString();
            list.removeAllViews();
            String f = query.trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (final Model.Office o : all) {
                String hay = (Model.nz(o.name) + " " + Model.nz(o.shortName) + " " + Model.nz(o.phone) + " " + Model.nz(o.location)).toLowerCase(Locale.ROOT);
                if (!f.isEmpty() && !hay.contains(f)) continue;
                Map<String, Long> b = bal.get(o.id);
                if (b == null) b = new HashMap<>();
                if (!passes(o, b)) continue;
                shown++;
                list.addView(officeCard(o, b, dec, usdRates, this::showList), a.lp(-1, -2, 4));
            }
            if (shown == 0) list.addView(a.text("لا توجد مكاتب مطابقة.", 14, a.muted, false));
        };
        q.addTextChangedListener(Ui.watcher(fill[0]));
        fill[0].run();
        a.hint("ضغطة مطوّلة على أي مكتب: تثبيت، حركة جديدة، مطابقة رصيد، كشف.");
    }

    private boolean passes(Model.Office o, Map<String, Long> b) {
        switch (filter) {
            case 1: return o.pinned;
            case 2:
                for (long v : b.values()) if (v > 0) return true;
                return false;
            case 3:
                for (long v : b.values()) if (v < 0) return true;
                return false;
            case 4: return !o.active;
            default: return true;
        }
    }

    /** حقل بحث بأيقونة؛ الحقل نفسه محفوظ في tag. */
    LinearLayout searchBox(String hint) {
        LinearLayout r = a.row();
        r.setPadding(a.dp(12), 0, a.dp(6), 0);
        r.setBackground(a.round(a.surface, a.line, 14));
        r.addView(a.icon(R.drawable.ic_search, a.muted, 20), new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setTextColor(a.ink);
        e.setHintTextColor(a.muted);
        e.setBackground(null);
        e.setPadding(a.dp(8), 0, a.dp(8), 0);
        e.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        r.addView(e, new LinearLayout.LayoutParams(0, -1, 1));
        r.setTag(e);
        return r;
    }

    View officeCard(final Model.Office o, Map<String, Long> b, Map<String, Integer> dec, final Runnable refresh) {
        return officeCard(o, b, dec, null, refresh);
    }

    /** 9.0: سطر «الميزان» — مجموع أرصدة المكتب بكل العملات مقوّمًا بالدولار (لنا − علينا). */
    String usdLine(Map<String, Long> b, Map<String, Integer> dec, Map<String, java.math.BigDecimal> rates) {
        boolean any = false;
        for (long v : b.values()) if (v != 0) any = true;
        if (!any) return null;
        Ledger.Valuation val = Ledger.valueOffice(b, rates, dec);
        java.math.BigDecimal net = val.netUsd();
        long whole = net.abs().setScale(0, java.math.RoundingMode.HALF_EVEN).longValue();
        if (a.amountsHidden()) return "الميزان ≈ •••• USD";
        String s = whole == 0 ? "الميزان ≈ صفر USD" : "الميزان ≈ \u2066" + Model.fmt(whole, 0) + " USD\u2069 " + (net.signum() > 0 ? "لنا" : "علينا");
        if (!val.missingRates.isEmpty()) s += " (بلا سعر: " + Model.join(new ArrayList<>(val.missingRates), "، ") + ")";
        if (!val.weightOnly.isEmpty()) s += " (بالوزن فقط: " + Model.join(new ArrayList<>(val.weightOnly), "، ") + ")";
        return s;
    }

    View officeCard(final Model.Office o, Map<String, Long> b, Map<String, Integer> dec, Map<String, java.math.BigDecimal> usd, final Runnable refresh) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
        c.setBackground(a.ripple(a.round(a.surface, o.pinned ? Ui.mix(a.line, a.primary, 0.45f) : a.line, 16), 16));
        c.addView(avatar(o), new LinearLayout.LayoutParams(a.dp(42), a.dp(42)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(a.dp(10), 0, 0, 0);
        LinearLayout head = a.row();
        TextView name = a.text(o.name, 16, o.active ? a.ink : a.muted, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (o.pinned) head.addView(a.icon(R.drawable.ic_pin, a.accent, 16), new LinearLayout.LayoutParams(a.dp(16), a.dp(16)));
        if (!o.active) head.addView(a.chip("موقوف", a.red, a.debitBg));
        col.addView(head);
        Ui.Flow chips = a.flow();
        int nonZero = 0;
        for (Map.Entry<String, Long> e : b.entrySet()) {
            long v = e.getValue();
            if (v == 0) continue;
            nonZero++;
            int d = dec.containsKey(e.getKey()) ? dec.get(e.getKey()) : 2;
            chips.addView(balChip(v, d, e.getKey()));
        }
        if (nonZero > 0) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(a.dp(4), a.dp(4), 0, 0);
            col.addView(chips, cp);
            String ul = usd == null ? null : usdLine(b, dec, usd);
            if (ul != null) {
                java.math.BigDecimal n = Ledger.valueOffice(b, usd, dec).netUsd();
                col.addView(a.text(ul, 12, n.signum() > 0 ? a.green : n.signum() < 0 ? a.red : a.muted, true));
            }
        } else {
            col.addView(a.text(o.active ? "الحساب مسدَّد بالكامل" : "لا تُقبل عليه حركات جديدة، وكشفه باقٍ", 12, a.muted, false));
        }
        c.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        c.setOnClickListener(v -> showOffice(o));
        c.setOnLongClickListener(v -> {
            quickMenu(o, refresh);
            return true;
        });
        return c;
    }

    View avatar(Model.Office o) {
        String n = Model.nz(o.shortName).trim().isEmpty() ? Model.nz(o.name).trim() : o.shortName.trim();
        // 9.0: «الأمل» و«النور» لا يظهر لهما «ا» معًا: الحرف بعد «ال»
        if (n.startsWith("مكتب ") && n.length() > 6) n = n.substring(5).trim();
        if (n.startsWith("ال") && n.length() > 3) n = n.substring(2);
        TextView t = a.text(n.isEmpty() ? "؟" : n.substring(0, n.offsetByCodePoints(0, 1)), 17, a.primaryInk, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 0);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(a.primarySoft);
        t.setBackground(g);
        return t;
    }

    /** رقاقة رصيد: «59,450.00 SYP لنا» بالأخضر أو بالأحمر. */
    TextView balChip(long v, int d, String code) {
        boolean us = v > 0;
        TextView t = a.chip((a.amountsHidden() ? "•••• " + code : "\u2066" + Model.fmt(Math.abs(v), d) + " " + code + "\u2069") + " " + (us ? "لنا" : "علينا"), us ? a.green : a.red, us ? a.creditBg : a.debitBg);
        t.setTextSize(12);
        return t;
    }

    /** قائمة الضغطة المطولة على مكتب. */
    void quickMenu(final Model.Office o, final Runnable refresh) {
        final List<String> acts = new ArrayList<>();
        if (o.active && a.can(Auth.Perm.TX_ADD)) acts.add("حركة جديدة لهذا المكتب");
        acts.add("فتح صفحة المكتب");
        acts.add("كشف المكتب");
        if (a.can(Auth.Perm.EXPORT)) acts.add("مطابقة رصيد");
        if (o.active && a.can(Auth.Perm.CASH) && a.db.cashConfig().on) {
            acts.add("تسليم من الصندوق لحسابه");
            acts.add("استلام إلى الصندوق لحسابه");
        }
        acts.add(o.pinned ? "إلغاء التثبيت" : "تثبيت في أعلى القوائم");
        if (a.can(Auth.Perm.OFFICES)) acts.add("تعديل بيانات المكتب");
        new AlertDialog.Builder(a).setTitle(o.name).setItems(acts.toArray(new String[0]), (d, w) -> {
            String s = acts.get(w);
            if (s.startsWith("حركة")) a.txScreen.showForOffice(o);
            else if (s.startsWith("فتح")) showOffice(o);
            else if (s.startsWith("كشف")) a.statementScreen.show(o);
            else if (s.startsWith("مطابقة")) a.reconScreen.sendDialog(o, refresh);
            else if (s.startsWith("تسليم من الصندوق")) a.cashScreen.form(CashBox.OUT, o);
            else if (s.startsWith("استلام إلى الصندوق")) a.cashScreen.form(CashBox.IN, o);
            else if (s.contains("التثبيت") || s.startsWith("تثبيت")) {
                a.db.setPinned(o.id, !o.pinned);
                a.toast(o.pinned ? "أُلغي تثبيت المكتب" : "تم تثبيت المكتب في أعلى القوائم");
                refresh.run();
            } else a.officesScreen.form(o);
        }).show();
    }

    // ===================================================================================
    // صفحة المكتب
    // ===================================================================================

    void showOffice(Model.Office office) {
        if (!a.require(Auth.Perm.VIEW)) return;
        final Model.Office o = a.db.office(office.id) == null ? office : a.db.office(office.id);
        touch(a.db, o.id);
        final Runnable refresh = () -> showOffice(o);
        a.here(() -> showOffice(o));
        a.tabbedScreen(o.name, this::showList, "offices");
        a.headerAction(R.drawable.ic_pin, o.pinned ? "إلغاء التثبيت" : "تثبيت", v -> {
            a.db.setPinned(o.id, !o.pinned);
            a.toast(o.pinned ? "أُلغي تثبيت المكتب" : "تم تثبيت المكتب في أعلى القوائم");
            showOffice(o);
        });
        if (o.pinned) {
            View pin = a.barActions.getChildAt(a.barActions.getChildCount() - 1);
            if (pin instanceof FrameLayout && ((FrameLayout) pin).getChildAt(0) instanceof android.widget.ImageView) {
                ((android.widget.ImageView) ((FrameLayout) pin).getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(a.accent));
            }
        }
        if (a.can(Auth.Perm.OFFICES)) a.headerAction(R.drawable.ic_dots, "خيارات المكتب", v -> optionsMenu(o));
        a.eyeAction(refresh);

        // بطاقة الرصيد: من دفتر القيود مباشرة
        Map<String, Long> bal = new java.util.TreeMap<>();
        for (String code : a.db.officeCurrencies(o.id)) bal.put(code, 0L);
        bal.putAll(a.db.balances(o.id));
        Map<String, String> names = a.db.currencyNames();
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a.primary, Ui.darker(a.primary, 0.78f)});
        g.setCornerRadius(a.dp(20));
        card.setBackground(g);
        int soft = Ui.mix(Color.WHITE, a.primary, 0.22f);
        card.addView(plain("الرصيد الحالي", 13, soft, false));
        int shown = 0;
        for (Map.Entry<String, Long> e : bal.entrySet()) {
            int d = a.db.decimals(e.getKey());
            long v = e.getValue();
            LinearLayout r = a.row();
            r.setPadding(0, a.dp(4), 0, a.dp(4));
            String nm = names.get(e.getKey());
            r.addView(plain(nm == null || nm.isEmpty() ? e.getKey() : nm + " · " + e.getKey(), 14, Color.WHITE, false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView amt = plain(a.mf(Math.abs(v), d), 19, Color.WHITE, true);
            amt.setTextDirection(View.TEXT_DIRECTION_LTR);
            r.addView(amt, new LinearLayout.LayoutParams(-2, -2));
            TextView chip = plain(v > 0 ? "لنا" : v < 0 ? "علينا" : "صفر", 12, v > 0 ? Color.rgb(20, 92, 53) : v < 0 ? Color.rgb(142, 31, 39) : Color.rgb(70, 80, 76), true);
            chip.setPadding(a.dp(10), a.dp(1), a.dp(10), a.dp(2));
            GradientDrawable cg = new GradientDrawable();
            cg.setColor(v > 0 ? Color.rgb(227, 241, 232) : v < 0 ? Color.rgb(248, 227, 228) : Color.rgb(235, 235, 230));
            cg.setCornerRadius(a.dp(50));
            chip.setBackground(cg);
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-2, -2);
            chp.setMargins(a.dp(8), 0, 0, 0);
            r.addView(chip, chp);
            card.addView(r);
            shown++;
        }
        if (shown == 0) card.addView(plain("لا توجد عملات مفعّلة لهذا المكتب", 14, Color.WHITE, false));
        // 10.18: الأمانات والمبالغ المجمّدة تحت الرصيد
        for (String hl : a.db.holdLines(o.id)) card.addView(plain("🔒 " + a.m(hl), 13, Color.WHITE, false));
        // 12.4: الحركات الخاصة القائمة (المحسوبة داخلة في الرصيد أعلاه، وغير المحسوبة خارجه)
        int spIn = 0, spOut = 0;
        for (Model.Tx t : a.db.specials(o.id)) if (t.isUnposted()) spOut++; else if (t.isActive()) spIn++;
        if (spIn + spOut > 0) card.addView(plain("⏳ حركات خاصة: " + (spIn > 0 ? spIn + " داخلة في الرصيد" : "") + (spIn > 0 && spOut > 0 ? "، " : "")
                + (spOut > 0 ? spOut + " خارجه" : "") + " — في «أمانات وخاصة»", 13, Color.WHITE, false));
        String ul = usdLine(bal, a.db.decimalsMap(), a.db.usdRates());
        if (ul != null) {
            TextView ut = plain(ul, 15, Color.WHITE, true);
            ut.setPadding(0, a.dp(6), 0, 0);
            card.addView(ut);
        }
        View div = new View(a);
        div.setBackgroundColor(Color.argb(46, 255, 255, 255));
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(1)));
        dvp.setMargins(0, a.dp(8), 0, a.dp(6));
        card.addView(div, dvp);
        final Model.Recon last = a.db.lastRecon(o.id, false);
        card.addView(plain(last == null ? "لم تُرسل مطابقة رصيد لهذا المكتب بعد" : "آخر مطابقة: " + Model.date(last.createdAt) + " — " + ReconScreen.statusText(last), 12, soft, false));
        a.body.addView(card, a.lp(-1, -2, 4));

        if (!o.active) a.notice(R.drawable.ic_warn, "هذا المكتب موقوف: لا يظهر في تسجيل الحركات ولا تُقبل عليه حركات جديدة.", true,
                a.can(Auth.Perm.OFFICES) ? "تفعيل" : null, a.can(Auth.Perm.OFFICES) ? v -> {
                    a.db.setOfficeActive(o.id, true);
                    a.toast("تم تفعيل المكتب «" + o.name + "»");
                    showOffice(o);
                } : null);
        List<String> alerts = a.officesScreen.limitAlerts(o);
        if (!alerts.isEmpty()) a.notice(R.drawable.ic_warn, "تجاوز الحد:\n" + Model.join(alerts, "\n"), true, null, null);
        if (last != null && Model.RECON_SENT.equals(last.status) && a.can(Auth.Perm.TX_ADD)) {
            LinearLayout rc = a.row();
            rc.addView(a.smallButton("✓ أكّد المكتب المطابقة", a.green, v -> a.reconScreen.statusDialog(last, Model.RECON_CONFIRMED, refresh)), a.weight(42, 1.2f, 2));
            rc.addView(a.smallButton("✗ غير مطابق", a.red, v -> a.reconScreen.statusDialog(last, Model.RECON_DISPUTED, refresh)), a.weight(42, 1, 2));
            a.body.addView(rc);
        }

        // الإجراءات السريعة
        LinearLayout acts = a.row();
        acts.setPadding(0, a.dp(6), 0, a.dp(2));
        if (o.active && a.can(Auth.Perm.TX_ADD)) acts.addView(action(R.drawable.ic_plus, "حركة جديدة", true, v -> a.txScreen.showForOffice(o)), a.weight(-2, 1, 4));
        if (a.can(Auth.Perm.STATEMENT)) acts.addView(action(R.drawable.ic_doc, "كشف", false, v -> a.statementScreen.show(o)), a.weight(-2, 1, 4));
        if (a.can(Auth.Perm.EXPORT)) {
            if (a.can(Auth.Perm.RECON)) acts.addView(action(R.drawable.ic_check2, "مطابقة رصيد", false, v -> a.reconScreen.sendDialog(o, refresh)), a.weight(-2, 1, 4));
            acts.addView(action(R.drawable.ic_share, "مشاركة", false, v -> a.exportUi.statementDialog(o, StatementScreen.Period.all(), null)), a.weight(-2, 1, 4));
        }
        if (o.active && a.can(Auth.Perm.CASH) && a.db.cashConfig().on) {
            acts.addView(action(R.drawable.ic_cash, "الصندوق", false, v -> new android.app.AlertDialog.Builder(a).setTitle("عبر صندوق المكتب — " + o.name)
                    .setItems(new String[]{"تسليم من الصندوق لحسابه (لنا على المكتب)", "استلام إلى الصندوق لحسابه (علينا للمكتب)"},
                            (d, w) -> a.cashScreen.form(w == 0 ? CashBox.OUT : CashBox.IN, o)).show()), a.weight(-2, 1, 4));
        }
        a.body.addView(acts, new LinearLayout.LayoutParams(-1, -2));

        // التبويبات الداخلية
        final String[] tabs = {"الحركات", "المطابقات", "أمانات وخاصة", "البيانات"};
        LinearLayout seg = a.row();
        seg.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
        int track = a.night ? Ui.mix(a.surface, Color.BLACK, 0.25f) : Color.rgb(236, 231, 220);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(track);
        tg.setCornerRadius(a.dp(14));
        seg.setBackground(tg);
        final LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        final List<TextView> tabViews = new ArrayList<>();
        final Runnable[] render = new Runnable[1];
        for (int i = 0; i < tabs.length; i++) {
            final int k = i;
            TextView t = a.text(tabs[i], 13, a.muted, true);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                innerTab = k;
                render[0].run();
            });
            tabViews.add(t);
            seg.addView(t, new LinearLayout.LayoutParams(0, a.dp(38), 1));
        }
        LinearLayout.LayoutParams sp = a.lp(-1, -2, 10);
        a.body.addView(seg, sp);
        a.body.addView(content);
        render[0] = () -> {
            for (int i = 0; i < tabViews.size(); i++) {
                TextView t = tabViews.get(i);
                boolean on = i == innerTab;
                t.setTextColor(on ? a.primaryInk : a.muted);
                if (on) {
                    GradientDrawable sg = new GradientDrawable();
                    sg.setColor(a.surface);
                    sg.setCornerRadius(a.dp(11));
                    t.setBackground(sg);
                } else t.setBackground(null);
            }
            content.removeAllViews();
            if (innerTab == 0) txTab(o, content, refresh);
            else if (innerTab == 1) reconTab(o, content, refresh);
            else if (innerTab == 2) holdsTab(o, content, refresh);
            else infoTab(o, content);
        };
        render[0].run();
    }

    private TextView plain(String s, int size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (bold) Ui.bold(t);
        return t;
    }

    private View action(int icon, String label, boolean main, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(a.dp(4), a.dp(12), a.dp(4), a.dp(10));
        int fill = main ? a.accent : a.surface;
        int fg = main ? Color.rgb(42, 28, 0) : a.ink;
        b.setBackground(a.ripple(a.round(fill, main ? fill : a.line, 16), 16));
        b.addView(a.icon(icon, fg, 22), new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
        TextView t = plain(label, 12, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, a.dp(5), 0, 0);
        b.addView(t, new LinearLayout.LayoutParams(-2, -2));
        b.setOnClickListener(l);
        return b;
    }

    /** 10.18: العملة المختارة لتصفية حركات المكتب (null = الكل) — معها يظهر «الرصيد بعدها» تحت كل حركة. */
    private String txCur;
    private long txCurOffice;

    private void txTab(final Model.Office o, LinearLayout content, final Runnable refresh) {
        if (txCurOffice != o.id) {
            txCur = null;
            txCurOffice = o.id;
        }
        final List<String> curs = new ArrayList<>(a.db.officeCurrencies(o.id));
        for (String k : a.db.balances(o.id).keySet()) if (!curs.contains(k)) curs.add(k);
        if (curs.size() > 0) {
            // 12.5: صفّ يلتف إلى سطر ثانٍ بدل التمرير الأفقي، فلا تُقصّ أزرار العملات على الأطراف (أندرويد والكمبيوتر)
            Ui.Flow chips = a.flow();
            List<String> opts = new ArrayList<>();
            opts.add(null);
            opts.addAll(curs);
            for (final String code : opts) {
                boolean on = code == null ? txCur == null : code.equals(txCur);
                TextView ch = a.chip(code == null ? "كل العملات" : code, on ? Color.WHITE : a.primaryInk, on ? a.primary : a.surface);
                ch.setTextSize(13);
                ch.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(7));
                ch.setOnClickListener(v -> {
                    txCur = code;
                    refresh.run();
                });
                chips.addView(ch);
            }
            content.addView(chips, a.lp(-1, -2, 2));
        }
        // أحدث الحركات فقط من قاعدة البيانات (لا تُقرأ كل حركات المكتب)؛ مع عملة محددة: 200 ومعها الرصيد بعد كل حركة
        Db.TxFilter tf = new Db.TxFilter();
        tf.office = o.id;
        tf.currency = txCur;
        tf.limit = txCur == null ? 30 : 200;
        List<Model.Tx> txs = a.db.txLog(tf);
        long total = txs.size() < tf.limit ? txs.size() : a.db.txCount(tf);
        Map<Long, Integer> att = a.db.attachmentCounts(o.id);
        // 10.18: «الرصيد بعدها» تحت كل حركة دائمًا، بعملة الحركة نفسها (ومع عملة مختارة: رصيدها وحدها)
        final Map<Long, Long> after = new java.util.HashMap<>();
        if (txCur != null) after.putAll(a.db.balancesAfter(o.id, txCur));
        else for (String c : curs) after.putAll(a.db.balancesAfter(o.id, c));
        content.addView(a.text("تحت كل حركة: رصيد المكتب بعملتها بعد تسجيلها (للمتابعة والتدقيق).", 12, a.muted, false));
        for (final Model.Tx t : txs) {
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            c.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
            c.addView(a.logScreen.txRow(t, null, att.get(t.id)));
            if (after.get(t.id) != null) {
                long b = after.get(t.id);
                TextView bt = a.text("الرصيد بعدها: " + (b > 0 ? "لنا " : b < 0 ? "علينا " : "") + "\u202A" + a.m(Model.fmt(Math.abs(b), a.db.decimals(t.currency))) + " " + t.currency + "\u202C", 13, b > 0 ? a.green : b < 0 ? a.red : a.muted, true);
                bt.setPadding(0, a.dp(4), 0, 0);
                c.addView(bt);
            }
            c.setOnClickListener(v -> a.logScreen.details(t, refresh));
            content.addView(c, a.lp(-1, -2, 4));
        }
        if (txs.isEmpty()) content.addView(a.text("لا توجد حركات على هذا المكتب بعد.", 14, a.muted, false));
        TextView all = a.text(total > 30 ? "كل حركات المكتب (" + total + ") في الكشف" : "الكشف الكامل والتصدير", 14, a.primaryInk, true);
        all.setGravity(Gravity.CENTER);
        all.setPadding(a.dp(8), a.dp(12), a.dp(8), a.dp(12));
        all.setBackground(a.ripple(null, 12));
        all.setOnClickListener(v -> a.statementScreen.show(o));
        content.addView(all, new LinearLayout.LayoutParams(-1, -2));
    }

    private void reconTab(final Model.Office o, LinearLayout content, final Runnable refresh) {
        List<Model.Recon> list = a.db.recons(o.id, 20);
        if (list.isEmpty()) content.addView(a.text("لم تُرسل مطابقة رصيد لهذا المكتب بعد. المطابقة ترسل للمكتب رصيده الحالي ليؤكده.", 14, a.muted, false));
        Map<String, String> names = a.db.currencyNames();
        Map<String, Integer> dec = a.db.decimalsMap();
        Reconcile.Template t = a.reconScreen.template();
        for (final Model.Recon r : list) {
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            c.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
            LinearLayout h = a.row();
            h.addView(a.text(Model.date(r.createdAt), 14, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
            boolean ok = Model.RECON_CONFIRMED.equals(r.status), bad = Model.RECON_DISPUTED.equals(r.status);
            h.addView(a.chip(r.statusLabel(), ok ? a.green : bad ? a.red : a.warnInk, ok ? a.creditBg : bad ? a.debitBg : a.warnBg));
            c.addView(h);
            StringBuilder sb = new StringBuilder();
            for (Reconcile.Row x : Reconcile.rows(r.balances, names, dec, null, false)) {
                if (sb.length() > 0) sb.append("  •  ");
                sb.append(x.label(false)).append(": ").append(x.amount()).append(" ").append(Reconcile.word(t, x));
            }
            c.addView(a.text(sb.length() == 0 ? "كل الأرصدة صفر" : sb.toString(), 13, a.muted, false));
            if (!r.note.isEmpty()) c.addView(a.text("ملاحظة: " + r.note, 12, a.muted, false));
            c.setOnClickListener(v -> {
                if (Model.RECON_SENT.equals(r.status) && a.can(Auth.Perm.TX_ADD)) {
                    new AlertDialog.Builder(a).setTitle("مطابقة " + Model.date(r.createdAt))
                            .setItems(new String[]{"✓ أكّد المكتب أن الرصيد مطابق", "✗ الرصيد غير مطابق عند المكتب", "سجل المطابقات الكامل"}, (d, w) -> {
                                if (w == 0) a.reconScreen.statusDialog(r, Model.RECON_CONFIRMED, refresh);
                                else if (w == 1) a.reconScreen.statusDialog(r, Model.RECON_DISPUTED, refresh);
                                else a.reconScreen.showOffice(o);
                            }).show();
                } else a.reconScreen.showOffice(o);
            });
            content.addView(c, a.lp(-1, -2, 4));
        }
        LinearLayout r = a.row();
        if (a.can(Auth.Perm.EXPORT)) r.addView(a.smallButton("مطابقة جديدة", a.primary, v -> a.reconScreen.sendDialog(o, refresh)), a.weight(44, 1, 2));
        r.addView(a.smallButton("السجل الكامل", Color.rgb(120, 120, 120), v -> a.reconScreen.showOffice(o)), a.weight(44, 1, 2));
        content.addView(r);
    }

    private void notifRow(LinearLayout g, final Model.Office o, final String key, String title) {
        LinearLayout r = a.groupRow(g, R.drawable.ic_bell, a.primaryInk, title, Notifier.on(a.db) ? "لهذا المكتب" : "الإشعارات متوقفة من «المزيد ← الإشعارات»", null, null);
        final android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(a.db.officeNotif(key, o.id));
        sw.setOnCheckedChangeListener((b, c) -> {
            a.db.setOfficeNotif(key, o.id, c);
            a.toast(c ? "مفعّل لهذا المكتب" : "متوقف لهذا المكتب");
        });
        r.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(v -> sw.toggle());
    }

    /**
     * 10.18: أمانات المكتب والمبالغ المجمّدة: المفتوح، الإضافة، الفك، والسجل.
     * 12.4: ومعها «الحركات الخاصة» (ناقصة أجور، تحتاج مراجعة، بانتظار التنفيذ…)، وتحويل الخاصة والأمانة والمجمّد لحركات عادية.
     */
    private void holdsTab(final Model.Office o, LinearLayout content, final Runnable refresh) {
        // ---------------- الحركات الخاصة
        final boolean sp = a.can(Auth.Perm.SPECIAL);
        List<Model.Tx> specials = a.db.specials(o.id);
        content.addView(a.text("الحركات الخاصة", 16, a.ink, true), a.lp(-1, -2, 2));
        content.addView(a.text("حركات ناقصة أجور، أو تحتاج مراجعة، أو بانتظار التنفيذ… المحسوبة في الرصيد تظهر في الكشف «معلّقة». "
                + "حوّلها لحركة عادية حين تكتمل.", 12, a.muted, false));
        if (o.active && sp && a.can(Auth.Perm.TX_ADD)) content.addView(a.smallButton("+ حركة خاصة", a.accent, v -> a.txScreen.showNewSpecial(o)), a.lp(-1, 44, 6));
        if (specials.isEmpty()) content.addView(a.text("لا حركات خاصة قائمة لهذا المكتب.", 14, a.muted, false), a.lp(-1, -2, 4));
        for (final Model.Tx t : specials) {
            LinearLayout c = card();
            LinearLayout hd = a.row();
            hd.setGravity(Gravity.CENTER_VERTICAL);
            hd.addView(a.chip("⏳ " + t.special, a.warnInk, a.warnBg));
            View fill = new View(a);
            hd.addView(fill, new LinearLayout.LayoutParams(0, 1, 1));
            hd.addView(t.isPending() ? a.chip("بانتظار الاعتماد", a.muted, a.surface)
                    : t.isUnposted() ? a.chip("لا تُحسب في الرصيد", a.muted, a.surface) : a.chip("تُحسب في الرصيد", a.green, a.creditBg));
            c.addView(hd);
            boolean debit = Model.TYPE_DEBIT.equals(t.type);
            c.addView(a.text((debit ? "لنا " : "علينا ") + a.mf(t.amount, a.db.decimals(t.currency)) + " " + t.currency
                    + (t.hasFee() ? "  •  أجرة " + a.mf(t.fee, a.db.decimals(t.feeCurrency)) + " " + t.feeCurrency : "  •  بلا أجرة"), 16, debit ? a.green : a.red, true));
            String desc = Model.nz(t.person).isEmpty() ? MessageParser.compact(t.description, null) : t.person;
            c.addView(a.text(Model.date(t.createdAt) + (t.userName.isEmpty() ? "" : " • " + t.userName) + (Model.nz(desc).isEmpty() ? "" : "\n" + desc), 12, a.muted, false));
            c.setOnClickListener(v -> specialActions(o, t, refresh));
            content.addView(c, a.lp(-1, -2, 4));
        }

        // ---------------- الأمانات والمجمّد
        content.addView(a.text("الأمانات والمبالغ المجمّدة", 16, a.ink, true), a.lp(-1, -2, 14));
        content.addView(a.text("الأمانة المنفصلة لا تدخل في الرصيد ولا في القيود. التجميد يُبقي الرصيد كما هو ويعلّم جزءًا منه «مجمّدًا» فيظهر المتاح. "
                + "اضغط على أي منها لتحويله لحركة عادية أو فكّه.", 12, a.muted, false));
        if (o.active && a.can(Auth.Perm.HOLDS)) {   // 12.0
            android.widget.Button add = a.smallButton("+ أمانة / تجميد", a.primary, v -> addHold(o, refresh));
            content.addView(add, a.lp(-1, 44, 6));
        }
        List<Db.Hold> all = a.db.holds(o.id, false);
        if (all.isEmpty()) content.addView(a.text("لا أمانات ولا مبالغ مجمّدة لهذا المكتب.", 14, a.muted, false));
        for (final Db.Hold h : all) {
            LinearLayout c = card();
            LinearLayout hd = a.row();
            hd.addView(a.text(h.label(), 14, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
            hd.addView(a.chip(h.open() ? "قائم" : h.toTx.isEmpty() ? "فُكّ" : "حُوّل لحركة", h.open() ? a.warnInk : a.muted, h.open() ? a.warnBg : a.surface));
            c.addView(hd);
            c.addView(a.text(a.mf(h.amount, a.db.decimals(h.currency)) + " " + h.currency, 16, a.primaryInk, true));
            c.addView(a.text(Model.date(h.at) + (h.by.isEmpty() ? "" : " • " + h.by) + (h.open() ? "" : "\n" + (h.toTx.isEmpty() ? "فُكّ " : "حُوّل لحركة ") + Model.date(h.releasedAt)
                    + (h.releasedBy.isEmpty() ? "" : " • " + h.releasedBy)) + (h.note.isEmpty() ? "" : "\n" + h.note), 12, a.muted, false));
            if (h.open() && a.can(Auth.Perm.HOLDS)) c.setOnClickListener(v -> holdActions(o, h, refresh));
            content.addView(c, a.lp(-1, -2, 4));
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        c.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
        return c;
    }

    /** 12.4: بأي تاريخ تنزل الحركة بالكشف؟ (الأصلي أو اليوم) ثم ينفّذ go(keepDate). */
    private void whichDate(long original, final java.util.function.Consumer<Boolean> go) {
        boolean today = Model.day(original).equals(Model.day(Clock.now()));
        if (today) {
            go.accept(true);
            return;
        }
        new AlertDialog.Builder(a).setTitle("بأي تاريخ تنزل بالكشف والمطابقة؟")
                .setItems(new String[]{"بتاريخها الأصلي: " + Model.day(original), "بتاريخ اليوم: " + Model.day(Clock.now())}, (d, w) -> go.accept(w == 0))
                .setNegativeButton("إلغاء", null).show();
    }

    private void specialActions(final Model.Office o, final Model.Tx t, final Runnable refresh) {
        final List<String> acts = new ArrayList<>();
        final boolean sp = a.can(Auth.Perm.SPECIAL);
        if (sp && !t.isPending()) {
            acts.add("تحويل لحركة عادية");
            acts.add("تعديل (النوع، الأجور، الحساب في الرصيد…)");
            if (t.isUnposted()) acts.add("إلغاء الحركة الخاصة");
        }
        if (t.isActive() && a.can(Auth.Perm.TX_CANCEL)) acts.add("إلغاء الحركة بقيد عكسي");
        acts.add("التفاصيل");
        new AlertDialog.Builder(a).setTitle("⏳ " + t.special + " — " + a.mf(t.amount, a.db.decimals(t.currency)) + " " + t.currency)
                .setItems(acts.toArray(new String[0]), (d, w) -> {
                    String act = acts.get(w);
                    if (act.startsWith("تحويل")) whichDate(t.createdAt, keep -> a.txScreen.showConvert(t, keep, () -> showOffice(o)));
                    else if (act.startsWith("تعديل")) a.txScreen.showEdit(t, () -> showOffice(o));
                    else if (act.equals("إلغاء الحركة الخاصة")) a.confirm("إلغاء الحركة الخاصة", "لم تدخل الرصيد بعد، فتُلغى بلا قيد عكسي وتبقى في السجل.", "إلغاء الحركة", () -> {
                        try {
                            a.db.voidSpecial(t.id, "");
                            a.toast("أُلغيت ✓");
                            refresh.run();
                        } catch (Exception e) {
                            a.error(e);
                        }
                    });
                    else if (act.startsWith("إلغاء")) a.logScreen.details(t, refresh);
                    else a.logScreen.details(t, refresh);
                }).show();
    }

    private void holdActions(final Model.Office o, final Db.Hold h, final Runnable refresh) {
        final boolean trust = Db.HOLD_TRUST.equals(h.kind);
        final List<String> acts = new ArrayList<>();
        if (o.active && a.can(Auth.Perm.TX_ADD)) acts.add(trust ? "تحويل لحركة عادية (تنزل بالكشف والمطابقة)" : "تسجيله حركة عادية (انصرف المبلغ فعلًا)");
        acts.add(trust ? "فكّ الأمانة بلا حركة" : "فكّ التجميد (الرصيد كما هو)");
        new AlertDialog.Builder(a).setTitle(h.label() + " — " + a.mf(h.amount, a.db.decimals(h.currency)) + " " + h.currency)
                .setItems(acts.toArray(new String[0]), (d, w) -> {
                    String act = acts.get(w);
                    if (act.startsWith("تحويل") || act.startsWith("تسجيله")) whichDate(h.at, keep -> a.txScreen.showFromHold(h, keep, () -> showOffice(o)));
                    else a.confirm(trust ? "فكّ الأمانة" : "فكّ التجميد", trust ? "تُعلَّم كمفكوكة وتبقى في السجل. لا يُنشئ أي قيد." : "يرجع المبلغ متاحًا. الرصيد لا يتغير.",
                            "فكّ", () -> {
                                try {
                                    a.db.releaseHold(h.uuid);
                                    a.toast("فُكّ ✓");
                                    refresh.run();
                                } catch (Exception e) {
                                    a.error(e);
                                }
                            });
                }).show();
    }

    private void addHold(final Model.Office o, final Runnable refresh) {
        final List<String> curs = new ArrayList<>(a.db.officeCurrencies(o.id));
        if (curs.isEmpty()) {
            a.toast("أضف عملة للمكتب أولًا");
            return;
        }
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(a.label("النوع"));
        final Ui.Seg kind = a.new Seg(new String[]{"أمانة منفصلة", "تجميد من الرصيد"}, null, 0, false);
        l.addView(kind.view, a.lp(-1, -2, 2));
        l.addView(a.label("الاتجاه"));
        final Ui.Seg side = a.new Seg(new String[]{"للمكتب عندنا / من «علينا»", "لنا عنده / من «لنا»"}, null, 0, false);
        l.addView(side.view, a.lp(-1, -2, 2));
        l.addView(a.label("العملة"));
        final Ui.Seg cur = a.new Seg(curs.toArray(new String[0]), null, 0, false);
        l.addView(cur.view, a.lp(-1, -2, 2));
        final EditText amount = a.field("المبلغ");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        l.addView(amount, a.lp(-1, 50, 4));
        final EditText note = a.field("ملاحظة (اختياري)");
        l.addView(note, a.lp(-1, 50, 4));
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(l);
        new AlertDialog.Builder(a).setTitle("أمانة أو تجميد — " + o.name).setView(sv).setPositiveButton("حفظ", (d, w) -> {
            try {
                String code = curs.get(cur.selected);
                a.db.addHold(o.id, code, kind.selected == 0 ? Db.HOLD_TRUST : Db.HOLD_FREEZE, side.selected == 0 ? Db.SIDE_THEIRS : Db.SIDE_OURS,
                        Db.minor(amount.getText().toString(), a.db.decimals(code)), note.getText().toString());
                a.toast("حُفظ ✓");
                refresh.run();
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    private void infoTab(final Model.Office o, LinearLayout content) {
        LinearLayout g = new LinearLayout(a);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(a.round(a.surface, a.line, 16));
        g.setClipToOutline(true);
        content.addView(g, a.lp(-1, -2, 4));
        if (!Model.nz(o.phone).isEmpty()) {
            a.groupRow(g, R.drawable.ic_phone, a.primaryInk, o.phone, "الهاتف — اضغط للاتصال", null, v -> {
                Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + o.phone.replaceAll("[^0-9+]", "")));
                if (!a.startOwn(i)) a.toast("لا يوجد تطبيق اتصال");
            });
        }
        if (!Model.nz(o.shortName).isEmpty()) a.groupRow(g, R.drawable.ic_tag, a.primaryInk, o.shortName, "الاسم المختصر", null, null);
        if (!Model.nz(o.location).isEmpty()) a.groupRow(g, R.drawable.ic_offices, a.primaryInk, o.location, "الموقع", null, null);
        if (!Model.nz(o.contact).isEmpty()) a.groupRow(g, R.drawable.ic_user, a.primaryInk, o.contact, "جهة الاتصال", null, null);
        List<String> curs = a.db.officeCurrencies(o.id);
        a.groupRow(g, R.drawable.ic_coins, a.primaryInk, curs.isEmpty() ? "لا توجد" : Model.join(curs, " • "), "العملات المفعّلة", null, null);
        Map<String, Db.Limit> lim = a.db.officeLimits(o.id);
        List<String> ls = new ArrayList<>();
        for (Map.Entry<String, Db.Limit> e : lim.entrySet()) {
            Db.Limit l = e.getValue();
            int d = a.db.decimals(e.getKey());
            if (l.receivable != null && l.receivable > 0) ls.add(e.getKey() + " لنا ≤ " + Model.fmt(l.receivable, d));
            if (l.payable != null && l.payable > 0) ls.add(e.getKey() + " علينا ≤ " + Model.fmt(l.payable, d));
        }
        if (o.usdLimit != null && o.usdLimit > 0) ls.add("مجموع لنا ≤ " + Model.fmt(o.usdLimit, 2) + " USD");
        if (o.usdOweLimit != null && o.usdOweLimit > 0) ls.add("مجموع علينا ≤ " + Model.fmt(o.usdOweLimit, 2) + " USD");
        if (!ls.isEmpty()) a.groupRow(g, R.drawable.ic_scale, a.primaryInk, Model.join(ls, "\n"), "حدود التنبيه", null, null);
        if (!Model.nz(o.notes).isEmpty()) a.groupRow(g, R.drawable.ic_doc, a.primaryInk, o.notes, "ملاحظات", null, null);
        a.groupRow(g, R.drawable.ic_list, a.primaryInk, String.valueOf(a.db.officeTxCount(o.id)), "عدد الحركات المسجّلة", null, null);
        // 9.0: إشعارات هذا المكتب
        notifRow(g, o, "notif_idle_off", "إشعار إن لم تُسجَّل له حركات منذ يوم كامل");
        notifRow(g, o, "notif_recon_off", "إشعار إن لم تُرسل له مطابقة رصيد منذ " + Notifier.reconDays(a.db) + " أيام");
        if (a.can(Auth.Perm.OFFICES)) {
            LinearLayout r = a.row();
            r.addView(a.smallButton("تعديل البيانات", a.primary, v -> a.officesScreen.form(o)), a.weight(44, 1, 2));
            r.addView(a.smallButton(o.active ? "إيقاف المكتب" : "تفعيل المكتب", Color.rgb(120, 120, 120), v -> toggleActive(o)), a.weight(44, 1, 2));
            content.addView(r, a.lp(-1, -2, 4));
        }
        // 10.5 (§7): علامة «آخر رسالة واتساب نُقلت» لهذا المكتب
        LinearLayout mk = a.card();
        mk.addView(a.text("آخر رسالة واتساب نُقلت", 15, a.primaryInk, true));
        a.mineScreen.markCard(mk, o, () -> showOffice(o));
        // card() يضيفها إلى الصفحة؛ مكانها هنا داخل تبويب المعلومات
        if (mk.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) mk.getParent()).removeView(mk);
        content.addView(mk, a.lp(-1, -2, 5));
        LinearLayout r2 = a.row();
        if (o.active && a.can(Auth.Perm.TX_ADD)) r2.addView(a.smallButton("حركة بين مكتبين", a.accent, v -> a.fxScreen.transfer(o)), a.weight(44, 1, 2));
        if (o.active && a.can(Auth.Perm.TX_ADD) && a.can(Auth.Perm.FX)) r2.addView(a.smallButton("قص عملات", a.accent, v -> a.fxScreen.show(o)), a.weight(44, 1, 2));
        if (a.can(Auth.Perm.DELETE)) r2.addView(a.smallButton("دمج في مكتب آخر", Color.rgb(120, 120, 120), v -> a.fxScreen.mergeDialog(o)), a.weight(44, 1, 2));
        if (r2.getChildCount() > 0) {
            content.addView(r2, a.lp(-1, -2, 4));
            content.addView(a.text("الدمج لاسمين لحساب واحد (مثل «الأمل» و«مكتب الأمل»): تنتقل الحركات كما هي ويُتحقق من الأرصدة.", 12, a.muted, false));
        }
    }

    private void toggleActive(final Model.Office o) {
        if (o.active) a.officesScreen.stopDialog(o, () -> showOffice(o));
        else {
            a.db.setOfficeActive(o.id, true);
            a.toast("تم تفعيل المكتب «" + o.name + "»");
            showOffice(o);
        }
    }

    private void optionsMenu(final Model.Office o) {
        final String[] items = {"تعديل بيانات المكتب", o.active ? "إيقاف المكتب" : "تفعيل المكتب", "قص عملات مع هذا المكتب", "دمج في مكتب آخر…", "حذف المكتب…", "حركة بين مكتبين (هذا المكتب طالبها)"};
        new AlertDialog.Builder(a).setTitle(o.name).setItems(items, (d, w) -> {
            if (w == 0) a.officesScreen.form(o);
            else if (w == 1) toggleActive(o);
            else if (w == 2) a.fxScreen.show(o);
            else if (w == 3) a.fxScreen.mergeDialog(o);
            else if (w == 5) a.fxScreen.transfer(o);
            else a.officesScreen.deleteDialog(o);
        }).show();
    }
}
