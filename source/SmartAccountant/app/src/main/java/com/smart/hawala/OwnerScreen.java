package com.smart.hawala;

import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 8.0: «تقارير صاحب المكتب»: الخلاصة في الأعلى (هل يكبر عملي؟ على من أعتمد؟ أين مالي معلّق؟ من يربحني؟)،
 * ثم كل تقرير في بطاقة، مع تصدير الكل PDF أو Excel. الحساب في خيط خلفي.
 */
final class OwnerScreen {
    private final MainActivity a;
    private boolean allTime;

    OwnerScreen(MainActivity a) { this.a = a; }

    OwnerReports.Input input() {
        OwnerReports.Input in = new OwnerReports.Input();
        in.now = Clock.now();
        in.txs = a.db.reportTxs();
        in.openings = a.db.reportOpenings();
        in.names = a.db.officeNames();
        in.decimals = a.db.decimalsMap();
        in.usdRates = a.db.usdRates();
        return in;
    }

    Report build() {
        Report r = OwnerReports.build(input(), a.db.setting("office_name", ""), allTime);
        return r;
    }

    void show() {
        if (!a.require(Auth.Perm.VIEW) || !a.require(Auth.Perm.OWNER)) return;   // 12.0
        a.busy("جارٍ تحليل الحركات…", p -> build(), res -> render((Report) res));
    }

    private void render(final Report r) {
        a.here(this::show);
        a.tabbedScreen("تقارير صاحب المكتب", a.moreScreen::show, "more");
        LinearLayout head = a.card();
        head.addView(a.text("الخلاصة", 17, a.primaryInk, true));
        List<String> hl = new ArrayList<>();
        for (String h : r.headerLines) if (!h.startsWith("صادر عن") && !h.startsWith("حتى:")) hl.add(h);
        if (hl.isEmpty()) head.addView(a.text("لا توجد حركات كافية بعد.", 14, a.muted, false));
        for (String h : hl) head.addView(a.text("• " + h, 14, a.ink, false));
        head.addView(a.text("من الحركات النشطة فقط. خصّص ساعة أول كل شهر لأربعة أرقام: النمو، الديون الأقدم من 90 يومًا، المكاتب الصاعدة والمتراجعة، وصافي الأجور.",
                12, a.muted, false));
        LinearLayout bar = a.row();
        bar.addView(a.smallButton("تصدير PDF / Excel", a.primary, v -> a.exportUi.reportDialog("تقارير صاحب المكتب", this::show,
                (images, st) -> Collections.singletonList(build()))), a.weight(44, 1.3f, 2));
        bar.addView(a.smallButton(allTime ? "التركّز: كل الفترة" : "التركّز: آخر 12 شهرًا", Color.rgb(120, 120, 120), v -> {
            allTime = !allTime;
            show();
        }), a.weight(44, 1, 2));
        a.body.addView(bar, a.lp(-1, -2, 4));

        for (final Report.Section s : r.sections) section(s);
    }

    private void section(final Report.Section s) {
        final LinearLayout c = a.card();
        c.addView(a.text(s.heading, 16, a.primaryInk, true));
        for (Report.Kv kv : s.summary) {
            TextView t = a.text(kv.label + ": " + Report.text(kv.value), 13, kv.emphasis ? a.red : a.ink, kv.emphasis);
            c.addView(t);
        }
        final LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        c.addView(rows);
        final int[] shown = {0};
        final Runnable more = new Runnable() {
            @Override public void run() {
                int until = Math.min(s.rows.size(), shown[0] + 12);
                for (int i = shown[0]; i < until; i++) rows.addView(row(s, s.rows.get(i)));
                shown[0] = until;
            }
        };
        more.run();
        if (s.rows.size() > shown[0]) {
            final TextView all = a.text("عرض المزيد (" + (s.rows.size() - shown[0]) + ")", 13, a.primary, true);
            all.setPadding(0, a.dp(6), 0, a.dp(2));
            all.setOnClickListener(v -> {
                more.run();
                if (shown[0] >= s.rows.size()) all.setVisibility(View.GONE);
                else all.setText("عرض المزيد (" + (s.rows.size() - shown[0]) + ")");
            });
            c.addView(all);
        }
        if (s.note != null && !s.note.isEmpty()) c.addView(a.text(s.note, 12, a.muted, false));
    }

    /** سطر جدول مضغوط للهاتف: العمود الأول عنوانًا والباقي «العمود: القيمة». */
    private View row(Report.Section s, Report.Row r) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, a.dp(6), 0, a.dp(6));
        int first = s.cols.size() > 1 && "#".equals(s.cols.get(0).title) ? 1 : 0;
        String title = Report.text(r.cells[first]);
        if (first == 1) title = Report.text(r.cells[0]) + ". " + title;
        box.addView(a.text(title, 14, r.style == Report.MUTED ? a.muted : a.ink, r.style == Report.BOLD));
        List<String> parts = new ArrayList<>();
        for (int i = first + 1; i < s.cols.size() && i < r.cells.length; i++) {
            Object cell = r.cells[i];
            String v = Report.text(cell);
            if (v.isEmpty() || "0".equals(v)) continue;
            boolean num = cell instanceof Report.Money || cell instanceof Report.Bal || cell instanceof Long || cell instanceof Integer;
            parts.add(s.cols.get(i).title + ": " + (num ? "\u2066" + v + "\u2069" : v));
        }
        if (!parts.isEmpty()) box.addView(a.text(Model.join(parts, "   •   "), 12, a.muted, false));
        box.setBackground(a.round(Color.TRANSPARENT, Color.TRANSPARENT, 0));
        return box;
    }
}
