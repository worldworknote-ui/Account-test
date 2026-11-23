package com.smart.hawala;

import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 8.0: تبديل عملة مع مكتب (ساقان مرتبطتان بسعر)، ودمج مكتبين.
 * 10.6: «تسجيل حركة قص عملات» و«تسجيل حركة بين مكتبين» شاشتان كاملتان بأسلوب «تسجيل حركة»، تُفتحان من زر «تسجيل» أو من صفحة المكتب.
 * التبديل: «لنا» بعملة و«علينا» بأخرى في عملية واحدة؛ يُحفظ السعر فيظهر ربح فرق العملة في تقارير صاحب المكتب.
 */
final class FxScreen {
    private final MainActivity a;

    FxScreen(MainActivity a) { this.a = a; }

    // ===================================================================================
    // 10.6: عناصر مشتركة بين شاشتي القص والتحويل (بأسلوب شاشة «تسجيل حركة» نفسها)
    // ===================================================================================

    /** بطاقة ملوّنة بعنوان ونقطة بلونها (طرف «لنا» بالأخضر و«علينا» بالأحمر). */
    private LinearLayout sideCard(String title, String sub, int color, int fill) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(12));
        c.setBackground(a.round(fill, Ui.mix(fill, color, 0.45f), 18));
        LinearLayout h = a.row();
        android.view.View dot = new android.view.View(a);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(a.dp(10), a.dp(10));
        dp.setMargins(a.dp(2), 0, a.dp(4), 0);
        h.addView(dot, dp);
        h.addView(a.text(title, 16, color, true), new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(h);
        if (sub != null && !sub.isEmpty()) c.addView(a.text(sub, 12, a.muted, false));
        a.body.addView(c, a.lp(-1, -2, 5));
        return c;
    }

    /** خانة مبلغ كبيرة (كخانة المبلغ في «تسجيل حركة»). */
    private EditText bigAmount(LinearLayout into, String label) {
        into.addView(a.text(label, 13, a.dark, true));
        EditText e = a.field("0");
        e.setContentDescription(label);
        Ui.numeric(e);
        e.setTextSize(22);
        Ui.bold(e);
        into.addView(e, a.lp(-1, 56, 4));
        return e;
    }

    /** أزرار العملات (تمرير أفقي) داخل حامل؛ لا عملة محددة مسبقًا. */
    private Ui.Seg curChips(LinearLayout holder, List<String> codes, int color, Ui.OnPick l, String empty) {
        holder.removeAllViews();
        if (codes.isEmpty()) {
            holder.addView(a.text(empty, 13, a.red, false));
            return null;
        }
        Ui.Seg s = a.new Seg(codes.toArray(new String[0]), new int[]{color}, -1, true);
        s.listener = l;
        holder.addView(s.view, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    /** زر دائري صغير في المنتصف (قلب العملية أو تبديل الطرفين). */
    private Button swapButton(String label, android.view.View.OnClickListener l) {
        Button b = a.smallButton(label, a.accent, l);
        LinearLayout r = a.row();
        r.setGravity(android.view.Gravity.CENTER);
        r.addView(b, new LinearLayout.LayoutParams(-2, a.dp(40)));
        a.body.addView(r, a.lp(-1, -2, 0));
        return b;
    }

    private static String balanceLine(Db db, Model.Office o) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : db.balances(o.id).entrySet()) {
            if (sb.length() > 0) sb.append("   •   ");
            sb.append(e.getKey()).append(": ").append(Model.bal(e.getValue(), db.decimals(e.getKey())));
        }
        return sb.length() == 0 ? "لا رصيد بعد" : "الرصيد الحالي: " + sb;
    }

    /** بطاقة الملخص (العنوان أحمر، والناقص بالأحمر مع ✗) وسطر الشريط الثابت — كملخص «تسجيل حركة». */
    private void renderSummary(LinearLayout box, TextView footer, List<String> lines, String okLine, int okColor) {
        List<String> missing = new java.util.ArrayList<>();
        box.removeAllViews();
        TextView head = a.text(lines.get(0), 14, a.red, true);
        head.setPadding(a.dp(2), 0, a.dp(2), a.dp(4));
        box.addView(head);
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            boolean bad = l.startsWith("✗");
            // 10.8: جدول المقاصة «▤عملة|قبل|الأثر|بعد»
            if (l.startsWith("▤")) {
                String[] c = l.substring(1).split("\\|", 4);
                if (i == 1 || !lines.get(i - 1).startsWith("▤")) box.addView(tableRow(new String[]{"العملة", "قبل", "أثر العملية", "بعد"}, true));
                box.addView(tableRow(c, false));
                continue;
            }
            if (l.startsWith("⚠")) {
                // تنبيه غير مانع: سطر كامل بالأحمر، لا يُعد ناقصًا
                TextView w = a.text(l, 13, a.red, true);
                w.setBackground(a.round(a.debitBg, a.debitBg, 8));
                box.addView(w, a.lp(-1, -2, 2));
                continue;
            }
            String body = bad ? l.substring(1).trim() : l;
            int c = body.indexOf(": ");
            String label = c > 0 ? body.substring(0, c) : body;
            String value = c > 0 ? body.substring(c + 2) : "";
            if (bad) missing.add(label);
            LinearLayout r = a.row();
            r.setPadding(0, a.dp(1), 0, a.dp(1));
            TextView lt = a.text((bad ? "✗ " : "") + label, 13, bad ? a.red : a.muted, bad);
            lt.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
            r.addView(lt, new LinearLayout.LayoutParams(a.dp(104), -2));
            TextView vt = a.text(value, 14, bad ? a.red : a.ink, true);
            vt.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
            r.addView(vt, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(r);
        }
        if (missing.isEmpty()) {
            footer.setText("✓ " + okLine);
            footer.setTextColor(a.readable(okColor));
        } else {
            footer.setText("ناقص: " + Model.join(missing, "، "));
            footer.setTextColor(a.red);
        }
    }

    /** صف من جدول المقاصة (العملة، قبل، أثر العملية، بعد). */
    private LinearLayout tableRow(String[] c, boolean head) {
        LinearLayout r = a.row();
        r.setPadding(0, a.dp(2), 0, a.dp(2));
        if (head) r.setBackground(a.round(Ui.mix(a.surface, a.line, 0.5f), Ui.mix(a.surface, a.line, 0.5f), 6));
        float[] w = {0.7f, 1.2f, 1.2f, 1.3f};
        for (int k = 0; k < 4; k++) {
            int col = head ? a.muted : k == 2 ? (c[2].startsWith("خصم") ? a.red : a.green) : a.ink;
            TextView t = a.text(c[k], head ? 12 : 13, col, !head && (k == 0 || k == 3));
            t.setPadding(a.dp(3), a.dp(2), a.dp(3), a.dp(2));
            r.addView(t, new LinearLayout.LayoutParams(0, -2, w[k]));
        }
        return r;
    }

    /** الملخص والشريط الثابت أسفل الشاشة (ملخص مختصر + زر التسجيل ظاهران دائمًا). */
    /** 10.13: أول عنصر في العمود الثاني على الشاشة العريضة (بطاقة الطرف الثاني). */
    private android.view.View splitMark;

    private Object[] summaryAndFooter(String saveLabel, android.view.View.OnClickListener save) {
        LinearLayout summary = new LinearLayout(a);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        summary.setBackground(a.round(Ui.mix(a.surface, a.red, a.night ? 0.12f : 0.045f), Ui.mix(a.line, a.red, 0.55f), 16));
        a.body.addView(summary, a.lp(-1, -2, 8));
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.VERTICAL);
        TextView line = a.text("", 13, a.red, true);
        line.setSingleLine(true);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.setPadding(a.dp(4), 0, a.dp(4), a.dp(2));
        foot.addView(line, new LinearLayout.LayoutParams(-1, -2));
        a.hideWithKeyboard = line;
        Button b = a.makeButton(saveLabel, a.primary, save);
        // 10.9: اضغط مطوّلًا حتى يمتلئ الزر، ثم تظهر نافذة التأكيد نفسها
        a.holdToConfirm(b);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, a.dp(50));
        sbp.topMargin = a.dp(4);
        foot.addView(b, sbp);
        a.footer(foot);
        // 10.13: على التابلت: الطرف الأول في عمود، والطرف الثاني والملخص في العمود المقابل
        a.splitBody(splitMark);
        splitMark = null;
        return new Object[]{summary, line, b};
    }

    private String amountText(EditText e, String cur) {
        try {
            int d = a.db.decimals(cur);
            long v = Db.minor(e.getText().toString(), d);
            return v > 0 ? "\u2066" + Model.fmt(v, d) + " " + cur + "\u2069" : null;
        } catch (Exception ex) {
            return null;
        }
    }

    // ===================================================================================
    // 10.6: تسجيل حركة قص عملات
    // ===================================================================================

    /**
     * 10.8: قص العملات مقاصةُ أرصدة: نبيع من رصيدنا لدى المكتب عملةً (تُخصم) ونأخذ مقابلها عملة أخرى (تُضاف)، بسعر يكتبه المحاسب؛
     * سعر البرنامج يُقترح ويبقى ظاهرًا للاستئناس. يجوز بيع أكثر من الرصيد (ينقلب «لنا» إلى «علينا») بعد تنبيه وتأكيد. لا حركة صندوق.
     * office0: المكتب إن فُتحت من صفحته (null = من زر «تسجيل»، فيُقترح آخر مكتب).
     */
    void show(final Model.Office office0) { show(office0, null); }

    /** 10.18: تعديل قص عملات مسجّل (أي ساق منه): الشاشة نفسها مملوءة بقيمه، والحفظ يعكس القديم ويسجّل المصحّح مكانه. */
    void edit(Model.Tx leg) {
        Model.Tx[] legs = a.db.fxLegsOf(leg.id);
        Model.Office o = legs == null ? null : a.db.office(legs[0].officeId);
        if (legs == null || o == null || !legs[0].isActive() || !legs[1].isActive()) {
            a.toast("هذه العملية ليست قص عملات نشطًا يمكن تعديله");
            return;
        }
        show(o, legs);
    }

    private void show(final Model.Office office0, final Model.Tx[] editing) {
        // 12.0: «قص العملات» صلاحية مستقلة؛ والتعديل يحتاج «تعديل الحركات» أيضًا
        if (!a.require(Auth.Perm.TX_ADD) || !a.require(Auth.Perm.FX) || (editing != null && !a.require(Auth.Perm.TX_EDIT))) return;
        a.base(editing != null ? "تعديل قص عملات" : "تسجيل حركة", office0 == null ? null : () -> a.officeHub.showOffice(office0));
        final Model.Office[] office = {null};
        // 10.7: نوع الحركة فوق المكتب
        if (editing == null) a.recordKinds(RecordMenu.FX, () -> office[0]);
        else a.notice(R.drawable.ic_info, "تعديل عملية " + Model.shortRef(editing[0].uuid) + " بتاريخ " + Model.date(editing[0].createdAt)
                + ": تُعكس القديمة وتُسجَّل المصحّحة مكانها بالتاريخ نفسه. المكتب لا يتغير.", false, null, null);
        a.hint("قص عملات مع مكتب: نبيع من رصيدنا لديه عملة (تُخصم من رصيدنا) ونأخذ مقابلها عملة أخرى (تُضاف إلى رصيدنا). " + FxDeal.NETTING_NOTE
                + " تُسجَّل الساقان معًا وتُعتمدان وتُلغيان معًا.");
        final List<String> curs = new java.util.ArrayList<>();
        final Ui.Seg[] sell = {null}, buy = {null};
        final boolean[] manual = {false}, quiet = {false}, rateAuto = {true};
        final java.util.Map<String, Db.Rate> rates = a.db.rates();
        final java.util.Map<String, java.math.BigDecimal> usd = a.db.usdRates();
        final Button officeBtn = a.button("اختر المكتب", a.accent, null);
        final TextView balance = a.add(a.text("", 13, a.muted, false));

        LinearLayout sc = sideCard("العملة المباعة من رصيدنا", "تُخصم من رصيدنا لدى المكتب (يجوز أن تتجاوزه)", a.red, a.debitBg);
        final LinearLayout sellHolder = new LinearLayout(a);
        sc.addView(sellHolder, a.lp(-1, -2, 2));
        final EditText amt = bigAmount(sc, "المبلغ المباع");
        final Button swap = swapButton("⇅ قلب العملية", null);
        LinearLayout bc = sideCard("العملة المشتراة مقابلها", "تُضاف إلى رصيدنا لدى المكتب", a.green, a.creditBg);
        splitMark = bc;
        final LinearLayout buyHolder = new LinearLayout(a);
        bc.addView(buyHolder, a.lp(-1, -2, 2));
        final EditText counter = bigAmount(bc, "المبلغ المقابل");
        bc.addView(a.text("اكتب أحد المبلغين والسعر فيُحسب الآخر. ويمكنك تعديل الاثنين فيُحفظ السعر الفعلي.", 12, a.muted, false));

        a.add(a.label("سعر الصرف"));
        final Ui.Seg dir = a.new Seg(new String[]{"1 المباعة = ؟ المشتراة", "1 المشتراة = ؟ المباعة"}, null, 0, false);
        a.body.addView(dir.view, a.lp(-1, -2, 2));
        final EditText rate = a.field("مثال: 0.92 أو 35.4");
        rate.setContentDescription("السعر");
        Ui.numeric(rate);
        rate.setTextSize(19);
        Ui.bold(rate);
        a.body.addView(rate, a.lp(-1, 54, 4));
        final TextView meaning = a.add(a.text("", 13, a.primaryInk, true));
        // 10.8: سعر البرنامج (أسعار الصرف المحفوظة) يُقترح، ويبقى ظاهرًا للاستئناس وإن كتب المحاسب سعره
        final TextView refLine = a.add(a.text("", 13, a.goldInk, false));
        final Button useRef = a.smallButton("استعمل سعر البرنامج", a.accent, null);
        a.body.addView(useRef, a.lp(-2, 40, 2));
        useRef.setVisibility(android.view.View.GONE);
        final EditText desc = a.input("البيان (اختياري)");

        final java.util.function.Supplier<FxDeal> deal = () -> {
            FxDeal x = new FxDeal();
            x.sell = sell[0] == null || sell[0].none() ? "" : sell[0].label();
            x.buy = buy[0] == null || buy[0].none() ? "" : buy[0].label();
            x.sellDec = x.sell.isEmpty() ? 2 : a.db.decimals(x.sell);
            x.buyDec = x.buy.isEmpty() ? 2 : a.db.decimals(x.buy);
            try {
                x.sellAmount = x.sell.isEmpty() ? 0 : Db.minor(amt.getText().toString(), x.sellDec);
            } catch (Exception e) {
                x.sellAmount = 0;
            }
            x.rate = FxDeal.parseRate(rate.getText().toString());
            x.buyPerSell = dir.selected != 1;
            return x;
        };
        final Runnable[] calc_holder = new Runnable[1];
        /** 10.13: آخر ما كتبه المحاسب المبلغ المشترى (فالمباع يُحسب منه). */
        final boolean[] fromBuy = {false};
        final Object[] sf = summaryAndFooter(editing != null ? "حفظ التعديل" : "تسجيل القص", null);
        final LinearLayout summary = (LinearLayout) sf[0];
        final TextView footer = (TextView) sf[1];
        final Button save = (Button) sf[2];
        final Runnable update = () -> {
            FxDeal x = deal.get();
            List<String> l = new java.util.ArrayList<>();
            l.add("راجع قبل التسجيل:");
            l.add(office[0] == null ? "✗ المكتب: لم يُختر" : "المكتب: " + office[0].name);
            String sa = x.sell.isEmpty() ? null : amountText(amt, x.sell);
            l.add(x.sell.isEmpty() ? "✗ نبيع: اختر العملة المباعة" : sa == null ? "✗ نبيع: اكتب المبلغ" : "نبيع من رصيدنا: " + sa);
            String ba = x.buy.isEmpty() ? null : amountText(counter, x.buy);
            l.add(x.buy.isEmpty() ? "✗ نشتري: اختر العملة المشتراة" : ba == null ? "✗ نشتري: اكتب السعر أو المبلغ المقابل" : "نأخذ مقابلها: " + ba);
            if (sa != null && ba != null) {
                try {
                    long ua = Db.minor(amt.getText().toString(), x.sellDec), ta = Db.minor(counter.getText().toString(), x.buyDec);
                    String eff = FxDeal.effectiveRate(ua, x.sellDec, ta, x.buyDec);
                    l.add("السعر الفعلي: 1 " + x.sell + " = " + eff + " " + x.buy);
                    java.math.BigDecimal ref = FxDeal.reference(usd, x.sell, x.buy);
                    if (ref != null) {
                        java.math.BigDecimal pct = new java.math.BigDecimal(eff).subtract(ref).multiply(java.math.BigDecimal.valueOf(100)).divide(ref, 2, java.math.RoundingMode.HALF_UP);
                        l.add("مقارنة بسعر البرنامج: " + (pct.signum() == 0 ? "مطابق" : (pct.signum() > 0 ? "أعلى بـ " : "أقل بـ ") + pct.abs().toPlainString() + "٪") + " (1 " + x.sell + " = "
                                + ref.toPlainString() + " " + x.buy + ")");
                    }
                    if (office[0] != null && editing != null) {
                        // تعديل: «قبل» هو الرصيد بعد عكس العملية القديمة
                        java.util.Map<String, long[]> pv = a.db.fxEditPreview(editing, x.sell, ua, x.buy, ta);
                        for (java.util.Map.Entry<String, long[]> e : pv.entrySet()) {
                            String[] r = FxDeal.row(e.getKey(), e.getValue()[0], e.getValue()[1] - e.getValue()[0], a.db.decimals(e.getKey()));
                            l.add("▤" + r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3]);
                        }
                        String warn = FxDeal.overdraft(x.sell, pv.get(x.sell)[0], ua, x.sellDec);
                        if (warn != null) l.add("⚠ " + warn);
                    } else if (office[0] != null) {
                        // جدول المقاصة: الرصيد قبل ← أثر العملية ← الرصيد بعد (لنا/علينا من إشارة الرصيد النهائي)
                        for (Db.Preview p : a.db.preview(java.util.Arrays.asList(a.db.fxNetLegs(office[0].id, x.sell, ua, x.buy, ta, "", "", Clock.now())), null))
                            for (String cur : p.before.keySet()) {
                                int dd = a.db.decimals(cur);
                                long bf = p.before.get(cur), af = p.after.get(cur);
                                String[] r = FxDeal.row(cur, bf, af - bf, dd);
                                l.add("▤" + r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3] + (p.pending ? " (بعد الاعتماد)" : ""));
                            }
                        long bal = a.db.balances(office[0].id).getOrDefault(x.sell, 0L);
                        String warn = FxDeal.overdraft(x.sell, bal, ua, x.sellDec);
                        if (warn != null) l.add("⚠ " + warn);
                    }
                } catch (Exception ignored) {
                    // الملخص مساعد فقط
                }
            }
            l.add(editing != null ? "التاريخ: " + Model.date(editing[0].createdAt) + " (تاريخ العملية الأصلي)" : "التاريخ: اليوم " + Model.day(Clock.now()));
            renderSummary(summary, footer, l, (office[0] == null ? "" : office[0].name + " • ") + "نبيع " + sa + " • نأخذ " + ba, a.primaryInk);
        };
        // سعر البرنامج بالاتجاه المختار: يُقترح في الخانة ما دام المحاسب لم يكتب سعره، ويبقى ظاهرًا دائمًا
        final Runnable reference = () -> {
            FxDeal x = deal.get();
            java.math.BigDecimal ref = FxDeal.referenceIn(FxDeal.reference(usd, x.sell, x.buy), x.buyPerSell);
            if (ref == null) {
                refLine.setText(x.sell.isEmpty() || x.buy.isEmpty() ? "" : "لا يوجد سعر محفوظ في البرنامج لإحدى العملتين (العملات وأسعار الصرف).");
                useRef.setVisibility(android.view.View.GONE);
                return;
            }
            String from = x.buyPerSell ? x.sell : x.buy, to = x.buyPerSell ? x.buy : x.sell;
            StringBuilder src = new StringBuilder();
            long at = 0;
            for (String c : new String[]{x.sell, x.buy}) {
                Db.Rate r = rates.get(c);
                if (r == null || "ثابت".equals(r.source)) continue;
                if (src.indexOf(r.source) < 0) src.append(src.length() > 0 ? "/" : "").append(r.source);
                if (r.at > 0 && (at == 0 || r.at < at)) at = r.at;
            }
            refLine.setText("سعر البرنامج للاستئناس: 1 " + from + " = " + ref.toPlainString() + " " + to
                    + (src.length() > 0 ? " (" + src + (at > 0 ? "، " + Model.day(at) : "") + ")" : ""));
            useRef.setVisibility(rateAuto[0] ? android.view.View.GONE : android.view.View.VISIBLE);
            if (rateAuto[0] && !ref.toPlainString().equals(rate.getText().toString().trim())) {
                quiet[0] = true;
                rate.setText(ref.toPlainString());
                quiet[0] = false;
                manual[0] = false;
            }
        };
        useRef.setOnClickListener(v -> {
            rateAuto[0] = true;
            manual[0] = false;
            reference.run();
            calc_holder[0].run();
        });
        final Runnable labels = () -> {
            FxDeal x = deal.get();
            String u = x.sell.isEmpty() ? "المباعة" : x.sell, t = x.buy.isEmpty() ? "المشتراة" : x.buy;
            dir.buttons.get(0).setText("1 " + u + " = ؟ " + t);
            dir.buttons.get(1).setText("1 " + t + " = ؟ " + u);
        };
        final Runnable calc = () -> {
            if (quiet[0]) return;
            FxDeal x = deal.get();
            meaning.setText(x.meaning().isEmpty() ? "" : "المعنى: " + x.meaning());
            // 10.13: الحساب من الطرفين — إن كان آخر ما كتبه المحاسب المبلغَ المشترى، يُحسب المباع من السعر
            if (fromBuy[0] && !x.sell.isEmpty()) {
                Long sv = null;
                try {
                    sv = x.sellAmountFor(Db.minor(counter.getText().toString(), x.buyDec));
                } catch (Exception ignored) {
                    // مبلغ غير مكتمل
                }
                quiet[0] = true;
                amt.setText(sv == null ? "" : Model.plain(sv, x.sellDec));
                quiet[0] = false;
                update.run();
                return;
            }
            if (!manual[0] && !x.buy.isEmpty()) {
                Long b = x.buyAmount();
                quiet[0] = true;
                counter.setText(b == null ? "" : Model.plain(b, x.buyDec));
                quiet[0] = false;
            }
            update.run();
        };
        calc_holder[0] = calc;
        final Ui.OnPick[] picks = new Ui.OnPick[2];
        final Runnable chips = () -> {
            String ps = sell[0] == null ? null : sell[0].label(), pb = buy[0] == null ? null : buy[0].label();
            curs.clear();
            if (office[0] != null) curs.addAll(a.db.officeCurrencies(office[0].id));
            // عملة العملية المعدَّلة تبقى ظاهرة وإن أُوقفت للمكتب بعد التسجيل
            if (editing != null) for (Model.Tx x : editing) if (!curs.contains(x.currency)) curs.add(x.currency);
            String empty = office[0] == null ? "اختر المكتب أولًا لتظهر عملاته" : "فعّل عملتين على الأقل لهذا المكتب (تعديل بيانات المكتب)";
            List<String> show = curs.size() < 2 ? new java.util.ArrayList<>() : curs;
            sell[0] = curChips(sellHolder, show, a.red, picks[0], empty);
            buy[0] = curChips(buyHolder, show, a.green, picks[1], empty);
            if (sell[0] != null && ps != null && show.contains(ps)) sell[0].set(show.indexOf(ps), false);
            if (buy[0] != null && pb != null && show.contains(pb) && !pb.equals(ps)) buy[0].set(show.indexOf(pb), false);
            labels.run();
            reference.run();
            calc.run();
        };
        // اختيار عملة في طرف يمنع العملة نفسها في الطرف الآخر؛ ومع عملتين فقط يُختار الطرف الآخر تلقائيًا
        picks[0] = i -> {
            if (buy[0] != null) {
                if (buy[0].selected == i) buy[0].set(-1, false);
                if (buy[0].none() && curs.size() == 2) buy[0].set(1 - i, false);
            }
            manual[0] = false;
            labels.run();
            reference.run();
            calc.run();
        };
        picks[1] = i -> {
            if (sell[0] != null) {
                if (sell[0].selected == i) sell[0].set(-1, false);
                if (sell[0].none() && curs.size() == 2) sell[0].set(1 - i, false);
            }
            manual[0] = false;
            labels.run();
            reference.run();
            calc.run();
        };
        final OfficeCbHolder pick = new OfficeCbHolder();
        pick.cb = o -> {
            office[0] = o;
            officeBtn.setText((o.pinned ? "📌 " : "") + "المكتب: " + o.name);
            a.txScreen.markOffice(officeBtn, true);
            balance.setText(balanceLine(a.db, o));
            chips.run();
        };
        officeBtn.setOnClickListener(v -> {
            if (editing != null) a.toast("المكتب لا يتغير عند التعديل؛ لتغييره ألغِ العملية وسجّلها من جديد");
            else a.officePicker(pick.cb, true);
        });
        dir.listener = i -> {
            manual[0] = false;
            // السعر المكتوب يُقلب مع الاتجاه حتى لا يتغير معناه
            java.math.BigDecimal r = FxDeal.parseRate(rate.getText().toString());
            if (r != null && !rateAuto[0]) {
                quiet[0] = true;
                rate.setText(FxDeal.referenceIn(r, false).toPlainString());
                quiet[0] = false;
            }
            reference.run();
            calc.run();
        };
        amt.addTextChangedListener(Ui.watcher(() -> {
            // كتب المبلغ المباع بنفسه: يُحسب المشترى منه
            if (!quiet[0]) {
                fromBuy[0] = false;
                manual[0] = false;
            }
            calc.run();
        }));
        rate.addTextChangedListener(Ui.watcher(() -> {
            if (!quiet[0]) {
                manual[0] = false;
                // المحاسب كتب سعره: يبقى سعر البرنامج ظاهرًا للاستئناس فقط
                rateAuto[0] = rate.getText().toString().trim().isEmpty();
                useRef.setVisibility(rateAuto[0] || refLine.getText().length() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
            }
            calc.run();
        }));
        counter.addTextChangedListener(Ui.watcher(() -> {
            if (quiet[0]) return;
            // 10.13: كتب المبلغ المشترى: إن لم يكتب المباع بنفسه (أو كان محسوبًا) يُحسب المباع من السعر
            if (fromBuy[0] || amt.getText().toString().trim().isEmpty()) {
                fromBuy[0] = true;
                calc.run();
            } else update.run();
        }));
        counter.setOnFocusChangeListener((v, has) -> { if (has) manual[0] = true; });
        swap.setOnClickListener(v -> {
            fromBuy[0] = false;
            FxDeal r = deal.get().reversed();
            if (r.sell.isEmpty() || r.buy.isEmpty() || sell[0] == null || buy[0] == null) {
                a.toast("اختر العملتين أولًا");
                return;
            }
            quiet[0] = true;
            sell[0].set(curs.indexOf(r.sell), false);
            buy[0].set(curs.indexOf(r.buy), false);
            amt.setText(r.sellAmount > 0 ? Model.plain(r.sellAmount, r.sellDec) : "");
            dir.set(r.buyPerSell ? 0 : 1, false);
            quiet[0] = false;
            manual[0] = false;
            labels.run();
            reference.run();
            calc.run();
        });
        save.setOnClickListener(v -> {
            try {
                if (office[0] == null) throw new IllegalArgumentException("اختر المكتب");
                final Model.Office o = office[0];
                final FxDeal x = deal.get();
                if (x.sell.isEmpty() || x.buy.isEmpty()) throw new IllegalArgumentException("اختر العملة المباعة والعملة المشتراة");
                if (x.sell.equals(x.buy)) throw new IllegalArgumentException("اختر عملتين مختلفتين");
                final long ua = x.sellAmount, ta = Db.minor(counter.getText().toString(), x.buyDec);
                if (ua <= 0 || ta <= 0) throw new IllegalArgumentException("اكتب المبلغ والسعر (أو المبلغ المقابل)");
                final String r = FxDeal.effectiveRate(ua, x.sellDec, ta, x.buyDec);
                final String typed = desc.getText().toString().trim();
                final String d = typed.isEmpty() ? FxDeal.describe(x.sell, Model.fmt(ua, x.sellDec), x.buy, Model.fmt(ta, x.buyDec)) : typed;
                if (editing != null) {
                    saveEdit(o, editing, x, ua, ta, r, d);
                    return;
                }
                final long when = Clock.now();
                final List<Db.TxInput> legs = java.util.Arrays.asList(a.db.fxNetLegs(o.id, x.sell, ua, x.buy, ta, r, d, when));
                final String rateText = "1 " + x.sell + " = " + r + " " + x.buy + " (الفعلي بعد التقريب)";
                final String warn = FxDeal.overdraft(x.sell, a.db.balances(o.id).getOrDefault(x.sell, 0L), ua, x.sellDec);
                final Runnable confirm = () -> a.confirmTx("تأكيد قص العملات", warn == null ? "" : warn, () -> TxConfirm.build(a.db, legs, null, rateText), "تسجيل",
                        () -> a.busy("جارٍ التسجيل…", p -> a.db.addFxNet(o.id, x.sell, ua, x.buy, ta, r, d, when), res -> {
                            a.db.setSettingQuiet("last_office", "" + o.id);
                            a.toast("✓ سُجّل قص العملات (مقاصة بين العملتين)");
                            a.officeHub.showOffice(o);
                        }));
                // 10.8: تجاوز الرصيد مسموح بعد تأكيد صريح (لا يُحفظ قبله)
                if (warn == null) confirm.run();
                else new AlertDialog.Builder(a).setTitle("المبلغ يتجاوز رصيد " + x.sell).setMessage(warn)
                        .setPositiveButton("تأكيد المتابعة", (dd, w) -> confirm.run()).setNegativeButton("تعديل العملية", null).show();
            } catch (Exception e) {
                a.error(e);
            }
        });
        Model.Office start = office0;
        if (start == null) {
            Model.Office last = a.db.office(a.db.settingLong("last_office", -1));
            if (last != null && last.active) start = last;
        }
        if (start != null) pick.cb.on(start);
        else chips.run();
        if (editing != null && sell[0] != null && buy[0] != null) {
            // القيم كما سُجّلت: العملتان والمبلغان والسعر الفعلي المحفوظ؛ لا يُعاد حساب المقابل حتى يغيّر المحاسب شيئًا
            quiet[0] = true;
            sell[0].set(curs.indexOf(editing[0].currency), false);
            buy[0].set(curs.indexOf(editing[1].currency), false);
            dir.set(0, false);
            amt.setText(Model.plain(editing[0].amount, a.db.decimals(editing[0].currency)));
            counter.setText(Model.plain(editing[1].amount, a.db.decimals(editing[1].currency)));
            rate.setText(Model.nz(editing[0].fxRate));
            desc.setText(Model.nz(editing[0].description));
            quiet[0] = false;
            rateAuto[0] = false;
            manual[0] = true;
            fromBuy[0] = false;
            labels.run();
            reference.run();
            calc.run();
        }
    }

    /** 10.18: تأكيد تعديل القص (ما كان وما يصير والأرصدة بعده) مع سبب مطلوب، ثم Db.editFxNet في معاملة واحدة. */
    private void saveEdit(final Model.Office o, final Model.Tx[] old, final FxDeal x, final long ua, final long ta, final String r, final String d) {
        java.util.Map<String, long[]> pv = a.db.fxEditPreview(old, x.sell, ua, x.buy, ta);
        String warn = FxDeal.overdraft(x.sell, pv.get(x.sell)[0], ua, x.sellDec);
        StringBuilder msg = new StringBuilder();
        msg.append("كانت: نبيع ").append(Model.fmt(old[0].amount, a.db.decimals(old[0].currency))).append(" ").append(old[0].currency).append(" ونأخذ ")
                .append(Model.fmt(old[1].amount, a.db.decimals(old[1].currency))).append(" ").append(old[1].currency).append("\n");
        msg.append("تصير: نبيع ").append(Model.fmt(ua, x.sellDec)).append(" ").append(x.sell).append(" ونأخذ ").append(Model.fmt(ta, x.buyDec)).append(" ").append(x.buy)
                .append("\nالسعر الفعلي: 1 ").append(x.sell).append(" = ").append(r).append(" ").append(x.buy).append("\n");
        for (java.util.Map.Entry<String, long[]> e : pv.entrySet())
            msg.append("رصيد ").append(e.getKey()).append(" بعد التعديل: ").append(Model.bal(e.getValue()[1], a.db.decimals(e.getKey()))).append("\n");
        if (warn != null) msg.append("\n⚠ ").append(warn).append("\n");
        final EditText why = new EditText(a);
        why.setHint("سبب التعديل (مطلوب)");
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(a.text(a.m(msg.toString()), 14, a.ink, false));
        l.addView(why, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("تأكيد تعديل قص العملات").setView(l).setPositiveButton("حفظ التعديل", null).setNegativeButton("رجوع", null).create();
        dlg.setOnShowListener(di -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String reason = why.getText().toString().trim();
            if (reason.isEmpty()) {
                why.setError("السبب مطلوب");
                return;
            }
            dlg.dismiss();
            a.busy("جارٍ حفظ التعديل…", p -> a.db.editFxNet(old[0].id, x.sell, ua, x.buy, ta, r, d, old[0].createdAt, reason), res -> {
                a.toast("✓ عُدّل قص العملات (عُكست القديمة وسُجّلت المصحّحة)");
                a.officeHub.showOffice(o);
            });
        }));
        dlg.show();
    }

    /** حامل لمستمع اختيار المكتب (يُستدعى من داخله). */
    private static final class OfficeCbHolder { MainActivity.OfficeCb cb; }

    // ===================================================================================
    // 10.6: تسجيل حركة بين مكتبين
    // ===================================================================================

    private static final String[] FEE_KINDS = {Model.FEE_NONE, Model.FEE_US, Model.FEE_THEM};

    void transfer(final Model.Office from0) { transfer(from0, from0); }

    /**
     * 10.7 (الاتجاه الصحيح): المكتب طالب الحركة يطلب منا تسليم مبلغ عن طريق مكتب آخر، فيصير «لنا» عليه؛
     * والمكتب المنفّذ يسلّم المبلغ نيابة عنا، فيصير «علينا» له — بالمبلغ والعملة نفسيهما، في عملية واحدة (Db.addTransfer).
     * الأجرة: أجرتنا على الطالب («لنا») وأجرة المنفّذ («علينا»)؛ الفرق ربحنا. لا حركة صندوق. المكتب نفسه في الطرفين مرفوض.
     * from0: فُتحت من صفحة هذا المكتب (الرجوع إليها). start: المكتب طالب الحركة المقترح.
     */
    void transfer(final Model.Office from0, final Model.Office start) {
        if (!a.require(Auth.Perm.TX_ADD) || !a.require(Auth.Perm.XFER)) return;   // 12.0
        int active = 0;
        for (Model.Office x : a.db.offices()) if (x.active) active++;
        if (active < 2) {
            a.toast("يلزم مكتبان نشطان على الأقل");
            return;
        }
        a.base("تسجيل حركة", from0 == null ? null : () -> a.officeHub.showOffice(from0));
        final Model.Office[] from = {null}, to = {null};
        // 10.7: نوع الحركة فوق المكتب
        a.recordKinds(RecordMenu.XFER, () -> from[0]);
        a.hint("مكتب يطلب منا تسليم مبلغ، وينفّذه مكتب آخر نيابة عنا: طالب الحركة يصير «لنا» عليه، والمنفّذ يصير «علينا» له بالمبلغ نفسه. لا يتحرك النقد في الصندوق. تُسجَّل الساقان وتُعتمدان وتُلغيان معًا.");
        final List<String> common = new java.util.ArrayList<>();
        final Ui.Seg[] cur = {null};

        LinearLayout fc = sideCard("المكتب طالب الحركة", "طلب منا التسليم ← يصير «لنا» عليه", a.green, a.creditBg);
        final Button fromBtn = a.makeButton("اختر المكتب طالب الحركة", a.accent, null);
        fc.addView(fromBtn, a.lp(-1, 52, 4));
        final TextView fromBal = a.text("", 13, a.muted, false);
        fc.addView(fromBal);
        final Button swap = swapButton("⇅ تبديل الطرفين", null);
        LinearLayout tc = sideCard("المكتب المنفّذ", "سلّم المبلغ عنا ← يصير «علينا» له", a.red, a.debitBg);
        splitMark = tc;
        final Button toBtn = a.makeButton("اختر المكتب المنفّذ", a.accent, null);
        tc.addView(toBtn, a.lp(-1, 52, 4));
        final TextView toBal = a.text("", 13, a.muted, false);
        tc.addView(toBal);

        a.add(a.label("المبلغ"));
        final EditText amt = a.amountInput("0");
        amt.setTextSize(22);
        Ui.bold(amt);
        a.add(a.label("العملة (المشتركة بين المكتبين)"));
        final LinearLayout curHolder = new LinearLayout(a);
        a.body.addView(curHolder, a.lp(-1, -2, 0));
        final EditText person = a.input("اسم المستفيد (اختياري)");
        final EditText desc = a.input("البيان (اختياري)");

        a.add(a.label("الأجرة"));
        final Ui.Seg feeMode = a.new Seg(new String[]{"بدون أجرة", "مع أجرة"}, null, 0, false);
        a.body.addView(feeMode.view, a.lp(-1, -2, 2));
        final LinearLayout feeBox = new LinearLayout(a);
        feeBox.setOrientation(LinearLayout.VERTICAL);
        feeBox.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        feeBox.setBackground(a.round(android.graphics.Color.rgb(250, 248, 242), android.graphics.Color.rgb(225, 220, 205), 12));
        int gray = android.graphics.Color.rgb(120, 120, 120);
        feeBox.addView(a.text("أجرتنا على طالب الحركة (عادةً «الأجرة لنا»)", 13, a.dark, true));
        final Ui.Seg fromFee = a.new Seg(FEE_KINDS, new int[]{gray, a.green, a.red}, 0, false);
        feeBox.addView(fromFee.view);
        final EditText fromFeeAmt = a.field("مبلغ أجرة الطالب");
        Ui.numeric(fromFeeAmt);
        feeBox.addView(fromFeeAmt, a.lp(-1, 50, 4));
        feeBox.addView(a.text("أجرة المنفّذ (عادةً «الأجرة علينا»)", 13, a.dark, true));
        final Ui.Seg toFee = a.new Seg(FEE_KINDS, new int[]{gray, a.green, a.red}, 0, false);
        feeBox.addView(toFee.view);
        final EditText toFeeAmt = a.field("مبلغ أجرة المنفّذ");
        Ui.numeric(toFeeAmt);
        feeBox.addView(toFeeAmt, a.lp(-1, 50, 4));
        feeBox.addView(a.text("الأجرة بعملة الحركة نفسها. الفرق بين أجرتنا على الطالب وأجرة المنفّذ هو ربحنا من الحركة.", 12, a.muted, false));
        feeBox.setVisibility(android.view.View.GONE);
        a.body.addView(feeBox, a.lp(-1, -2, 4));

        final java.util.function.Supplier<Db.TransferInput> input = () -> {
            Db.TransferInput in = new Db.TransferInput();
            if (from[0] == null || to[0] == null) throw new IllegalArgumentException("اختر المكتبين");
            in.from = from[0].id;
            in.to = to[0].id;
            if (cur[0] == null || cur[0].none()) throw new IllegalArgumentException(common.isEmpty() ? "لا عملة مشتركة مفعّلة للمكتبين" : "اختر العملة");
            in.currency = cur[0].label();
            int d = a.db.decimals(in.currency);
            in.amount = Db.minor(amt.getText().toString(), d);
            in.person = person.getText().toString().trim();
            in.description = desc.getText().toString().trim();
            in.when = Clock.now();
            boolean fees = feeMode.selected == 1;
            in.fromFeeType = fees ? FEE_KINDS[Math.max(0, fromFee.selected)] : Model.FEE_NONE;
            in.toFeeType = fees ? FEE_KINDS[Math.max(0, toFee.selected)] : Model.FEE_NONE;
            if (!Model.FEE_NONE.equals(in.fromFeeType)) in.fromFee = Db.minor(fromFeeAmt.getText().toString(), d);
            if (!Model.FEE_NONE.equals(in.toFeeType)) in.toFee = Db.minor(toFeeAmt.getText().toString(), d);
            return in;
        };
        final Object[] sf = summaryAndFooter("تسجيل الحركة", null);
        final LinearLayout summary = (LinearLayout) sf[0];
        final TextView footer = (TextView) sf[1];
        final Button save = (Button) sf[2];
        final Runnable update = () -> {
            boolean fees = feeMode.selected == 1;
            feeBox.setVisibility(fees ? android.view.View.VISIBLE : android.view.View.GONE);
            fromFeeAmt.setVisibility(fromFee.selected > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            toFeeAmt.setVisibility(toFee.selected > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            String c = cur[0] == null || cur[0].none() ? null : cur[0].label();
            String at = c == null ? null : amountText(amt, c);
            List<String> l = new java.util.ArrayList<>();
            l.add("راجع قبل التسجيل:");
            l.add(from[0] == null ? "✗ طالب الحركة: لم يُختر" : "طالب الحركة: " + from[0].name + " — «لنا» عليه");
            l.add(to[0] == null ? "✗ المنفّذ: لم يُختر" : "المنفّذ: " + to[0].name + " — «علينا» له");
            if (from[0] != null && to[0] != null && from[0].id == to[0].id) l.add("✗ المكتبان: اختر مكتبين مختلفين");
            if (from[0] != null && to[0] != null && common.isEmpty()) l.add("✗ العملة: لا عملة مشتركة مفعّلة للمكتبين");
            else if (c == null) l.add("✗ العملة: لم تُختر");
            l.add(amt.getText().toString().trim().isEmpty() ? "✗ المبلغ: لم يُكتب" : at == null ? "✗ المبلغ غير صحيح: اكتب أرقامًا فقط" : "المبلغ: " + at);
            if (fees) {
                for (int k = 0; k < 2; k++) {
                    Ui.Seg s = k == 0 ? fromFee : toFee;
                    EditText e = k == 0 ? fromFeeAmt : toFeeAmt;
                    String who = k == 0 ? "أجرة الطالب" : "أجرة المنفّذ";
                    if (s.selected <= 0) l.add(who + ": بدون");
                    else {
                        String fa = c == null ? null : amountText(e, c);
                        l.add(fa == null ? "✗ " + who + ": اكتب المبلغ" : who + ": " + FEE_KINDS[s.selected].replace("الأجرة ", "") + " " + fa);
                    }
                }
                // ربحنا = ما لنا من أجرة ناقص ما علينا منها (بعملة الحركة)
                try {
                    long net = 0;
                    int d = a.db.decimals(c);
                    for (int k = 0; k < 2; k++) {
                        Ui.Seg s = k == 0 ? fromFee : toFee;
                        if (s.selected <= 0) continue;
                        long v = Db.minor((k == 0 ? fromFeeAmt : toFeeAmt).getText().toString(), d);
                        net += Model.FEE_US.equals(FEE_KINDS[s.selected]) ? v : -v;
                    }
                    l.add("صافي الأجرة: " + (net >= 0 ? "ربح لنا " : "خسارة علينا ") + "\u2066" + Model.fmt(Math.abs(net), d) + " " + c + "\u2069");
                } catch (Exception ignored) {
                    // الناقص ظاهر بالأحمر
                }
            } else l.add("الأجرة: بدون");
            String p = person.getText().toString().trim();
            l.add("المستفيد: " + (p.isEmpty() ? "—" : p));
            if (at != null && from[0] != null && to[0] != null && from[0].id != to[0].id) {
                try {
                    for (Db.Preview pv : a.db.preview(java.util.Arrays.asList(a.db.transferLegs(input.get())), null))
                        for (String k : pv.before.keySet())
                            l.add("«" + pv.officeName + "» " + k + ": " + Model.bal(pv.before.get(k), a.db.decimals(k)) + "  ←  " + Model.bal(pv.after.get(k), a.db.decimals(k))
                                    + (pv.pending ? " (بعد الاعتماد)" : ""));
                } catch (Exception ignored) {
                    // الناقص ظاهر بالأحمر أعلاه
                }
            }
            l.add("التاريخ: اليوم " + Model.day(Clock.now()));
            renderSummary(summary, footer, l, (from[0] == null ? "" : from[0].name) + " ← " + (to[0] == null ? "" : to[0].name) + " • " + at, a.primaryInk);
        };
        final Runnable curs = () -> {
            String keep = cur[0] == null ? null : cur[0].label();
            common.clear();
            if (from[0] != null && to[0] != null) {
                List<String> y = a.db.officeCurrencies(to[0].id);
                for (String c : a.db.officeCurrencies(from[0].id)) if (y.contains(c)) common.add(c);
            }
            cur[0] = curChips(curHolder, common, a.primary, i -> update.run(),
                    from[0] == null || to[0] == null ? "اختر المكتبين لتظهر عملاتهما المشتركة" : "لا عملة مشتركة مفعّلة للمكتبين");
            if (cur[0] != null) {
                if (keep != null && common.contains(keep)) cur[0].set(common.indexOf(keep), false);
                else if (common.size() == 1) cur[0].set(0, false);
            }
            fromBal.setText(from[0] == null ? "" : balanceLine(a.db, from[0]));
            toBal.setText(to[0] == null ? "" : balanceLine(a.db, to[0]));
            fromBtn.setText(from[0] == null ? "اختر المكتب طالب الحركة" : (from[0].pinned ? "📌 " : "") + from[0].name);
            toBtn.setText(to[0] == null ? "اختر المكتب المنفّذ" : (to[0].pinned ? "📌 " : "") + to[0].name);
            a.txScreen.markOffice(fromBtn, from[0] != null);
            a.txScreen.markOffice(toBtn, to[0] != null);
            update.run();
        };
        fromBtn.setOnClickListener(v -> a.officePicker(o -> {
            if (to[0] != null && to[0].id == o.id) {
                a.toast("«" + o.name + "» هو المكتب المنفّذ؛ اختر مكتبًا آخر أو اضغط «تبديل الطرفين»");
                return;
            }
            from[0] = o;
            curs.run();
        }, true));
        toBtn.setOnClickListener(v -> a.officePicker(o -> {
            if (from[0] != null && from[0].id == o.id) {
                a.toast("«" + o.name + "» هو طالب الحركة؛ اختر مكتبًا آخر أو اضغط «تبديل الطرفين»");
                return;
            }
            to[0] = o;
            curs.run();
        }, true));
        swap.setOnClickListener(v -> {
            Model.Office t = from[0];
            from[0] = to[0];
            to[0] = t;
            // إعادة رسم الزرين بلا إطار «مختار» إن صار أحدهما فارغًا
            for (Button b : new Button[]{fromBtn, toBtn}) {
                b.setBackground(a.ripple(a.round(a.goldSoft, a.goldSoft, 14), 14));
                b.setTextColor(a.goldInk);
                b.setTextSize(16);
            }
            curs.run();
        });
        // تفعيل الأجرة يقترح الحالة المعتادة: أجرتنا على الطالب «لنا»، وأجرة المنفّذ «علينا»
        feeMode.listener = i -> {
            if (i == 1 && fromFee.selected <= 0 && toFee.selected <= 0) {
                fromFee.set(1, false);
                toFee.set(2, false);
            }
            update.run();
        };
        fromFee.listener = i -> update.run();
        toFee.listener = i -> update.run();
        for (EditText e : new EditText[]{amt, fromFeeAmt, toFeeAmt, person}) e.addTextChangedListener(Ui.watcher(update));
        save.setOnClickListener(v -> {
            try {
                final Db.TransferInput in = input.get();
                if (in.amount <= 0) throw new IllegalArgumentException("اكتب المبلغ");
                final List<Db.TxInput> legs = java.util.Arrays.asList(a.db.transferLegs(in));
                a.confirmTx("تأكيد الحركة بين مكتبين", "", () -> TxConfirm.build(a.db, legs, null, null), "تسجيل الحركة", () -> a.busy("جارٍ التسجيل…",
                        p -> a.db.addTransfer(in), res -> {
                            Model.Tx t = a.db.tx(((long[]) res)[0]);
                            a.toast(t != null && t.isPending() ? "سُجّلت الحركة بانتظار الاعتماد (الساقان معًا)" : "✓ سُجّلت الحركة بين المكتبين");
                            Model.Office back = a.db.office(in.from);
                            if (back != null) a.officeHub.showOffice(back);
                            else a.showHome();
                        }));
            } catch (Exception e) {
                a.error(e);
            }
        });
        if (start != null && start.active) from[0] = start;
        curs.run();
    }

    /** دمج هذا المكتب في مكتب آخر (اسمان لحساب واحد)، بعد نسخة أمان، وبتحقق الأرصدة. */
    void mergeDialog(final Model.Office from) {
        if (!a.require(Auth.Perm.DELETE)) return;
        a.officePicker(into -> {
            if (into.id == from.id) {
                a.toast("اختر مكتبًا آخر");
                return;
            }
            final EditText name = new EditText(a);
            name.setHint("اكتب اسم «" + from.name + "» للتأكيد");
            LinearLayout l = new LinearLayout(a);
            l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
            l.addView(name, new LinearLayout.LayoutParams(-1, -2));
            final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("دمج «" + from.name + "» في «" + into.name + "»")
                    .setMessage("تنتقل كل حركات «" + from.name + "» وأرصدته السابقة إلى «" + into.name + "» كما هي، ويُحذف «" + from.name + "» بعدها فارغًا. "
                            + "رصيد «" + into.name + "» يصير مجموع الرصيدين، ويُتحقق من ذلك لكل عملة قبل الحفظ. تُحفظ نسخة أمان قبل الدمج للتراجع.")
                    .setView(l).setPositiveButton("دمج", null).setNegativeButton("إلغاء", null).create();
            dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!name.getText().toString().trim().equals(Model.nz(from.name).trim())) {
                    name.setError("الاسم غير مطابق");
                    return;
                }
                dlg.dismiss();
                a.busy("جارٍ حفظ نسخة أمان ثم الدمج…", p -> {
                    Snapshot.take(a, a.db, "دمج «" + from.name + "» في «" + into.name + "»");
                    a.db.mergeOffices(from.id, into.id);
                    return null;
                }, res -> {
                    Model.Office fresh = a.db.office(into.id);
                    new AlertDialog.Builder(a).setTitle("تم الدمج ✓").setMessage("صار «" + into.name + "» يحمل حركات المكتبين وأرصدتهما. للتراجع: النسخ الاحتياطي ← التراجع.")
                            .setPositiveButton("حسنًا", null).show();
                    if (fresh != null) a.officeHub.showOffice(fresh);
                });
            }));
            dlg.show();
        }, false);
    }
}
