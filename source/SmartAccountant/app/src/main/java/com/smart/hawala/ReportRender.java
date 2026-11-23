package com.smart.hawala;

import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 9.0: عرض أي تقرير (Report) على الهاتف: كل قسم في بطاقة، الخلاصة في الأعلى، وكل سطر مضغوط
 * (العمود الأول عنوانًا والباقي «العمود: القيمة»)، مع «عرض المزيد» للأقسام الطويلة. الأرقام نفسها التي تُصدَّر PDF و Excel.
 */
final class ReportRender {
    private ReportRender() {}

    static void render(MainActivity a, Report r) {
        for (Report.Section s : r.sections) section(a, a.body, s);
    }

    static void section(final MainActivity a, LinearLayout into, final Report.Section s) {
        final LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        c.setBackground(a.round(a.surface, a.line, 16));
        into.addView(c, a.lp(-1, -2, 5));
        c.addView(a.text(s.heading, 16, a.primaryInk, true));
        for (Report.Kv kv : s.summary) c.addView(a.text(kv.label + ": " + Report.text(kv.value), 13, kv.emphasis ? a.red : a.ink, kv.emphasis));
        final LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        c.addView(rows);
        final int[] shown = {0};
        final Runnable more = () -> {
            int until = Math.min(s.rows.size(), shown[0] + 15);
            for (int i = shown[0]; i < until; i++) rows.addView(row(a, s, s.rows.get(i)));
            shown[0] = until;
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

    static View row(MainActivity a, Report.Section s, Report.Row r) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6));
        if (s.cols.isEmpty() || r.cells.length == 0) return box;
        int first = s.cols.size() > 1 && "#".equals(s.cols.get(0).title) ? 1 : 0;
        String title = Report.text(r.cells[first]);
        if (first == 1) title = Report.text(r.cells[0]) + ". " + title;
        boolean strong = r.style == Report.BOLD || r.style == Report.TOTAL;
        box.addView(a.text(title, 14, r.style == Report.MUTED ? a.muted : a.ink, strong));
        List<String> parts = new ArrayList<>();
        for (int i = first + 1; i < s.cols.size() && i < r.cells.length; i++) {
            Object cell = r.cells[i];
            String v = Report.text(cell);
            if (v.isEmpty() || "0".equals(v) || "—".equals(v)) continue;
            boolean num = cell instanceof Report.Money || cell instanceof Long || cell instanceof Integer;
            parts.add(s.cols.get(i).title + ": " + (num ? "\u2066" + v + "\u2069" : v));
        }
        if (!parts.isEmpty()) box.addView(a.text(Model.join(parts, "   •   "), 12, r.style == Report.MUTED ? a.muted : a.dark, false));
        if (r.style == Report.TOTAL) box.setBackground(a.round(a.goldSoft, a.goldSoft, 10));
        else if (r.style == Report.SHADED) box.setBackground(a.round(a.primarySoft, a.primarySoft, 10));
        else box.setBackground(a.round(Color.TRANSPARENT, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, a.dp(2), 0, a.dp(2));
        box.setLayoutParams(lp);
        return box;
    }
}
