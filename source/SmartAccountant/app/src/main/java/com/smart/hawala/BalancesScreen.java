package com.smart.hawala;

import android.graphics.Color;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * أرصدة كل المكاتب في مكان واحد: لكل مكتب ولكل عملة «كل ما لنا» و«كل ما علينا» ثم الرصيد،
 * والإجمالي لكل عملة بدون تقاصّ بين المكاتب، مع التقويم بالدولار والتنبيهات والتصدير.
 */
final class BalancesScreen {
    private final MainActivity a;
    private boolean showZero = false;
    private boolean showGross = false;
    private String query = "";
    private Map<Long, Model.Recon> recons = new HashMap<>();

    BalancesScreen(MainActivity a) { this.a = a; }

    void show() {
        if (!a.require(Auth.Perm.VIEW)) return;
        a.here(this::show);
        a.tabbedScreen("أرصدة كل المكاتب", a::showHome, "home");
        a.eyeAction(this::show);
        showGross = "1".equals(a.db.setting("ui_bal_gross", "0"));
        final List<Model.OfficeTotals> offices = a.db.officeTotals(null);
        recons = a.db.lastReconsByOffice();
        Map<String, Integer> dec = a.db.decimalsMap();

        // الإجمالي لكل عملة
        Map<Long, Map<String, Long>> net = new LinkedHashMap<>();
        for (Model.OfficeTotals o : offices) {
            Map<String, Long> m = new java.util.TreeMap<>();
            for (Map.Entry<String, long[]> e : o.perCurrency.entrySet()) m.put(e.getKey(), e.getValue()[2]);
            net.put(o.officeId, m);
        }
        Map<String, Ledger.CurrencyTotal> totals = Ledger.totals(net);
        // 10.16: جدول مرتب بدل نص متراص: لكل عملة سطر عنوان (الرمز والاسم والصافي) وتحته خانتان «لنا» و«علينا»
        Map<String, String> cnames = a.db.currencyNames();
        LinearLayout tc = a.card();
        tc.addView(a.text("الإجمالي لكل عملة", 18, a.primary, true));
        tc.addView(a.text("ما لنا على المكاتب وما علينا لها، كلٌّ على حدة (دون خصم أحدهما من الآخر)، ثم الصافي.", 12, a.muted, false));
        boolean any = false;
        for (Ledger.CurrencyTotal t : totals.values()) {
            if (t.receivable == 0 && t.payable == 0) continue;
            int d = dec.containsKey(t.code) ? dec.get(t.code) : 2;
            if (any) tc.addView(divider());
            any = true;
            LinearLayout h = a.row();
            h.setPadding(0, a.dp(6), 0, 0);
            String nm = cnames.get(t.code);
            h.addView(a.text(t.code + (nm == null || nm.isEmpty() || nm.equals(t.code) ? "" : "  " + nm), 15, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
            TextView netV = a.text(a.mb(t.net(), d), 16, t.net() > 0 ? a.green : t.net() < 0 ? a.red : a.muted, true);
            h.addView(netV, new LinearLayout.LayoutParams(-2, -2));
            tc.addView(h);
            LinearLayout r = a.row();
            r.addView(cell("لنا", a.mf(t.receivable, d), t.officesReceivable + " مكتب", a.green), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(cell("علينا", a.mf(t.payable, d), t.officesPayable + " مكتب", a.red), new LinearLayout.LayoutParams(0, -2, 1));
            tc.addView(r);
        }
        if (!any) tc.addView(a.text("لا توجد أرصدة بعد.", 15, a.muted, false));

        Map<String, Db.Rate> rates = a.db.rates();
        Map<String, BigDecimal> usd = new HashMap<>();
        for (Map.Entry<String, Db.Rate> e : rates.entrySet()) usd.put(e.getKey(), e.getValue().usd);
        Ledger.Valuation v = Ledger.value(totals, usd, dec);
        if (any) {
            LinearLayout uc = a.card();
            uc.addView(a.text("التقويم التقريبي بالدولار", 15, a.primary, true));
            LinearLayout r = a.row();
            r.addView(cell("لنا", a.m("$" + Db.fmtUsd(v.receivableUsd)), null, a.green), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(cell("علينا", a.m("$" + Db.fmtUsd(v.payableUsd)), null, a.red), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(cell("الصافي", a.m("$" + Db.fmtUsd(v.netUsd())), null, v.netUsd().signum() >= 0 ? a.green : a.red), new LinearLayout.LayoutParams(0, -2, 1));
            uc.addView(r);
            if (!v.missingRates.isEmpty()) uc.addView(a.text("⚠ بدون سعر صرف (غير محسوبة): " + v.missingRates, 12, a.warnInk, false));
            uc.addView(a.text("تقريبي للمتابعة فقط؛ الأرصدة الحقيقية بعملاتها.", 11, a.muted, false));
        }

        LinearLayout tools = a.row();
        a.body.addView(tools, a.lp(-1, -2, 4));
        tools.addView(a.smallButton("تصدير (PDF / صور / Excel)", a.primary, x -> a.exportUi.balancesDialog(showZero)), a.weight(46, 1.4f, 2));
        tools.addView(a.smallButton("أسعار الصرف", a.accent, x -> a.officesScreen.showRates(this::show)), a.weight(46, 1, 2));

        LinearLayout fr = a.row();
        final EditText q = a.field("تصفية بالاسم");
        q.setText(query);
        fr.addView(q, a.weight(48, 1, 2));
        final CheckBox zero = new CheckBox(a);
        zero.setText("إظهار الصفرية");
        zero.setChecked(showZero);
        zero.setOnCheckedChangeListener((b, c) -> {
            showZero = c;
            query = q.getText().toString();
            show();
        });
        fr.addView(zero, new LinearLayout.LayoutParams(-2, -2));
        a.body.addView(fr);
        // 10.16: مجاميع الحركة («كل ما لنا» و«كل ما علينا») أرقام كبيرة لا تلزم كل مرة: تظهر عند الطلب، والرصيد دائمًا
        final CheckBox gross = new CheckBox(a);
        gross.setText("إظهار مجاميع الحركة لكل مكتب (كل ما لنا وكل ما علينا)");
        gross.setChecked(showGross);
        gross.setOnCheckedChangeListener((b, c) -> {
            showGross = c;
            a.db.setSettingQuiet("ui_bal_gross", c ? "1" : "0");
            query = q.getText().toString();
            show();
        });
        a.body.addView(gross);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(list);
        final Runnable fill = () -> renderList(list, offices, q.getText().toString());
        q.addTextChangedListener(Ui.watcher(fill));
        fill.run();
    }

    /** خانة رقم مرتبة: عنوان صغير، ثم الرقم بلونه (من اليسار لليمين فلا تنقلب الفواصل)، ثم ملاحظة صغيرة. */
    private android.view.View cell(String label, String value, String note, int color) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, a.dp(2), a.dp(8), a.dp(4));
        col.addView(a.text(label + (note == null ? "" : "  •  " + note), 12, a.muted, false));
        TextView t = a.text("\u2066" + value + "\u2069", 16, color, true);
        col.addView(t);
        return col;
    }

    private android.view.View divider() {
        android.view.View v = new android.view.View(a);
        v.setBackgroundColor(a.line);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, a.dp(1)));
        p.setMargins(0, a.dp(6), 0, 0);
        v.setLayoutParams(p);
        return v;
    }

    private void renderList(LinearLayout list, List<Model.OfficeTotals> offices, String filter) {
        list.removeAllViews();
        String f = filter.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (final Model.OfficeTotals o : offices) {
            if (!f.isEmpty() && !Model.nz(o.name).toLowerCase(Locale.ROOT).contains(f)) continue;
            boolean nonZero = false;
            for (long[] v : o.perCurrency.values()) if (v[0] != 0 || v[1] != 0 || v[2] != 0) nonZero = true;
            if (!nonZero && !showZero) continue;
            shown++;
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            c.setBackground(a.ripple(a.round(a.surface, o.pinned ? a.primary : a.line, 14), 14));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.setMargins(0, a.dp(4), 0, a.dp(4));
            list.addView(c, p);
            LinearLayout head = a.row();
            head.addView(a.text((o.pinned ? "📌 " : "") + o.name + (o.active ? "" : " (موقوف)"), 17, a.primary, true), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(a.smallButton("مطابقة", a.primary, v -> {
                Model.Office office = a.db.office(o.officeId);
                if (office != null) a.reconScreen.sendDialog(office, this::show);
            }), new LinearLayout.LayoutParams(-2, a.dp(38)));
            head.addView(a.smallButton(o.pinned ? "إلغاء التثبيت" : "تثبيت", Color.rgb(130, 130, 130), v -> {
                a.db.setPinned(o.officeId, !o.pinned);
                show();
            }), new LinearLayout.LayoutParams(-2, a.dp(38)));
            c.addView(head);
            Model.Recon rc = recons.get(o.officeId);
            if (rc != null) c.addView(a.text("آخر مطابقة: " + Model.date(rc.createdAt) + " — " + ReconScreen.statusText(rc), 12, a.reconScreen.statusColor(rc), false));
            for (Map.Entry<String, long[]> e : o.perCurrency.entrySet()) {
                long[] v = e.getValue();
                if (!showZero && v[0] == 0 && v[1] == 0 && v[2] == 0) continue;
                int d = a.db.decimals(e.getKey());
                LinearLayout br = a.row();
                br.setPadding(0, a.dp(3), 0, 0);
                br.addView(a.text(e.getKey(), 14, a.muted, true), new LinearLayout.LayoutParams(a.dp(52), -2));
                TextView t = a.text(a.mb(v[2], d), 15, v[2] > 0 ? a.green : v[2] < 0 ? a.red : a.ink, true);
                br.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                c.addView(br);
                if (showGross) c.addView(a.text("كل ما لنا: " + a.mf(v[0], d) + "   •   كل ما علينا: " + a.mf(v[1], d), 12, a.muted, false));
            }
            Model.Office off = a.db.office(o.officeId);
            if (off != null) {
                List<String> alerts = a.officesScreen.limitAlerts(off);
                if (!alerts.isEmpty()) c.addView(a.text("⚠ " + Model.join(alerts, "\n"), 13, a.red, true));
            }
            c.setOnClickListener(v -> {
                Model.Office office = a.db.office(o.officeId);
                if (office != null) a.officeHub.showOffice(office);
            });
        }
        if (shown == 0) list.addView(a.text("لا توجد مكاتب مطابقة.", 14, a.muted, false));
        else list.addView(a.text("اضغط على أي مكتب لفتح صفحته.", 12, a.muted, false));
    }

    Report report(boolean includeZero) {
        return a.db.withHolds(Reports.balances(a.db.officeTotals(null), a.db.decimalsMap(), a.db.currencyNames(), includeZero,
                a.db.setting("office_name", ""), Clock.now()));
    }
}
