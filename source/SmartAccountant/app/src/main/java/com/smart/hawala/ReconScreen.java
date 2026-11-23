package com.smart.hawala;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * مطابقة الأرصدة مع المكاتب:
 * - إرسال الرصيد فقط (نص لواتساب أو صورة أو الاثنين) بقالب قابل للتعديل.
 * - تسجيل كل إرسال كمطابقة «بانتظار التأكيد»، ثم «مطابِقة ✓» أو «غير مطابِقة ✗» حسب رد المكتب.
 * - كشف بالحركات التي سُجّلت بعد آخر مطابقة (لكل العملات أو لعملات محددة) من نافذة التصدير.
 */
final class ReconScreen {
    static final int TEXT = 0, IMAGE = 1, BOTH = 2;

    private final MainActivity a;

    ReconScreen(MainActivity a) { this.a = a; }

    Reconcile.Template template() { return Reconcile.Template.from(a.db.settings(Reconcile.Template.KEYS)); }

    private int accent() {
        String c = a.db.setting("st_color", "");
        return Ui.parseColor(c, a.primary);
    }

    /** أرصدة المكتب الحالية لكل عملة مفعّلة (والأصفار). */
    private Map<String, Long> currentBalances(Model.Office o) {
        Map<String, Long> m = new java.util.TreeMap<>();
        for (String code : a.db.officeCurrencies(o.id)) m.put(code, 0L);
        m.putAll(a.db.balances(o.id));
        return m;
    }

    static String statusText(Model.Recon r) {
        String s = r.statusLabel();
        if (r.confirmedAt > 0 && !Model.RECON_SENT.equals(r.status)) s += " (" + Model.date(r.confirmedAt) + ")";
        return s;
    }

    int statusColor(Model.Recon r) {
        return Model.RECON_CONFIRMED.equals(r.status) ? a.green : Model.RECON_DISPUTED.equals(r.status) ? a.red : Color.rgb(176, 110, 30);
    }

    // ===================================================================================
    // إرسال الرصيد / إنشاء مطابقة
    // ===================================================================================

    void sendDialog(final Model.Office o, final Runnable after) {
        if (!a.require(Auth.Perm.EXPORT) || !a.require(Auth.Perm.RECON)) return;   // 12.0
        final Map<String, Long> bal = currentBalances(o);
        final Map<String, String> names = a.db.currencyNames();
        final Reconcile.Template t = template();
        ScrollView sv = new ScrollView(a);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        l.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));
        sv.addView(l);
        l.addView(a.text("يُرسل الرصيد فقط، بدون تفاصيل الحركات.", 13, a.muted, false));
        l.addView(a.label("العملات"));
        final Map<String, CheckBox> checks = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : bal.entrySet()) {
            CheckBox ck = new CheckBox(a);
            int d = a.db.decimals(e.getKey());
            String nm = names.get(e.getKey());
            ck.setText((nm == null ? e.getKey() : nm) + ":  " + (e.getValue() == 0 ? "صفر" : Model.fmt(Math.abs(e.getValue()), d) + " " + (e.getValue() > 0 ? t.plusWord : t.minusWord)));
            ck.setChecked(e.getValue() != 0 || t.showZero);
            l.addView(ck);
            checks.put(e.getKey(), ck);
        }
        l.addView(a.label("الشكل"));
        final Ui.Seg fmt = a.new Seg(new String[]{"نص", "صورة", "صورة + نص"}, null, (int) Math.max(0, Math.min(2, a.db.settingLong("rc_format", BOTH))), false);
        l.addView(fmt.view);
        final CheckBox record = new CheckBox(a);
        record.setText("تسجيل كمطابقة بانتظار تأكيد المكتب");
        record.setChecked(a.can(Auth.Perm.TX_ADD));
        record.setEnabled(a.can(Auth.Perm.TX_ADD));
        l.addView(record);
        l.addView(a.text("عند تأكيد المكتب أن رصيده مطابق، اضغط «أكّد المكتب» في سجل المطابقات. بعدها تستطيع تصدير الحركات التي جاءت بعد هذه المطابقة فقط.", 12, a.muted, false));

        // 11.3: الأمانات والمبالغ المجمّدة (لا تدخل في الرصيد): اختيارية في كل إرسال، ويُحفظ آخر اختيار
        final List<String> holdLines = holdLines(o, null);
        final CheckBox holds = new CheckBox(a);
        if (!holdLines.isEmpty()) {
            holds.setText("إظهار الأمانات والمبالغ المجمّدة (" + holdLines.size() + ") تحت الرصيد");
            holds.setChecked("1".equals(a.db.setting("rc_holds", "0")));
            l.addView(holds);
        }

        // 11.6: «الميزان»: مجموع الأرصدة المختارة مقوّمًا بالدولار، اختياري ويُحفظ آخر اختيار
        final CheckBox scale = new CheckBox(a);
        scale.setText("إضافة الميزان (مجموع الأرصدة بالدولار)");
        scale.setChecked("1".equals(a.db.setting("rc_scale", "0")));
        l.addView(scale);

        final AlertDialog[] dlg = new AlertDialog[1];
        final java.util.function.IntConsumer act = mode -> {
            Set<String> only = new LinkedHashSet<>();
            for (Map.Entry<String, CheckBox> e : checks.entrySet()) if (e.getValue().isChecked()) only.add(e.getKey());
            if (only.isEmpty()) {
                a.toast("اختر عملة واحدة على الأقل");
                return;
            }
            a.db.setSettingQuiet("rc_format", "" + fmt.selected);
            if (!holdLines.isEmpty()) a.db.setSettingQuiet("rc_holds", holds.isChecked() ? "1" : "0");
            a.db.setSettingQuiet("rc_scale", scale.isChecked() ? "1" : "0");
            final Reconcile.Template use = withScale(withSpecials(withHolds(template(), holds.isChecked() ? holdLines(o, only) : null), specialLines(o, only)), scale.isChecked() ? scaleLine(bal, only) : null);
            long at = Clock.now();
            if (record.isChecked()) {
                try {
                    a.db.createRecon(o.id);
                } catch (Exception e) {
                    a.error(e);
                    return;
                }
            }
            if (dlg[0] != null) dlg[0].dismiss();
            List<Reconcile.Row> rows = Reconcile.rows(bal, names, a.db.decimalsMap(), only, true);
            if (mode < 0) copyText(use, o, at, rows);
            else deliver(use, o, at, rows, fmt.selected);
            if (record.isChecked()) a.toast("سُجّلت المطابقة بانتظار تأكيد المكتب.");
            if (after != null && record.isChecked()) after.run();
        };
        l.addView(a.makeButton("إرسال / مشاركة", a.primary, v -> act.accept(0)), a.lp(-1, 50, 4));
        l.addView(a.makeButton("نسخ النص فقط", a.accent, v -> act.accept(-1)), a.lp(-1, 46, 4));
        LinearLayout r2 = a.row();
        r2.addView(a.smallButton("معاينة", Color.rgb(120, 120, 120), v -> {
            Set<String> only = new LinkedHashSet<>();
            for (Map.Entry<String, CheckBox> e : checks.entrySet()) if (e.getValue().isChecked()) only.add(e.getKey());
            preview(withScale(withSpecials(withHolds(template(), holds.isChecked() ? holdLines(o, only) : null), specialLines(o, only)), scale.isChecked() ? scaleLine(bal, only) : null), o.name, Clock.now(),
                    Reconcile.rows(bal, names, a.db.decimalsMap(), only, true), fmt.selected);
        }), a.weight(42, 1, 2));
        if (a.can(Auth.Perm.DESIGN)) {
            r2.addView(a.smallButton("تعديل القالب", Color.rgb(120, 120, 120), v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                templateScreen(() -> {
                    a.statementScreen.show(o);
                    sendDialog(o, after);
                });
            }), a.weight(42, 1, 2));
        }
        l.addView(r2);
        dlg[0] = new AlertDialog.Builder(a).setTitle("مطابقة رصيد: " + o.name).setView(sv).setNegativeButton("إغلاق", null).create();
        dlg[0].show();
    }

    private String issuer() { return a.db.setting("office_name", ""); }

    /**
     * 11.3: سطور الأمانات والمجمّد المفتوحة للمكتب (مجموعة حسب النوع والعملة)، مثل «أمانة لنا عند المكتب: 500 دولار أمريكي».
     * only: العملات المختارة (أو null للكل).
     */
    List<String> holdLines(Model.Office o, Set<String> only) {
        Map<String, Long> sum = new LinkedHashMap<>();
        for (Db.Hold h : a.db.holds(o.id, true)) {
            if (only != null && !only.contains(h.currency)) continue;
            String k = h.label() + "|" + h.currency;
            sum.put(k, (sum.containsKey(k) ? sum.get(k) : 0L) + h.amount);
        }
        Map<String, String> names = a.db.currencyNames();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : sum.entrySet()) {
            String[] p = e.getKey().split("\\|", 2);
            String nm = names.get(p[1]);
            out.add(p[0] + ": " + Model.fmt(e.getValue(), a.db.decimals(p[1])) + " " + (nm == null ? p[1] : nm));
        }
        return out;
    }

    /** 11.6: سطر الميزان للعملات المختارة بكلمتي القالب («لنا/لكم» كما يقرؤها المكتب)، أو null إن كان كل شيء صفرًا. */
    String scaleLine(Map<String, Long> bal, Set<String> only) {
        Map<String, Long> sub = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : bal.entrySet()) if (only == null || only.contains(e.getKey())) sub.put(e.getKey(), e.getValue());
        boolean any = false;
        for (long v : sub.values()) if (v != 0) any = true;
        if (!any) return null;
        Ledger.Valuation val = Ledger.valueOffice(sub, a.db.usdRates(), a.db.decimalsMap());
        java.math.BigDecimal net = val.netUsd();
        long whole = net.abs().setScale(0, java.math.RoundingMode.HALF_EVEN).longValue();
        Reconcile.Template t = template();
        String s = "⚖️ الميزان ≈ " + (whole == 0 ? "صفر" : Model.fmt(whole, 0)) + " USD" + (whole == 0 ? "" : " " + (net.signum() > 0 ? t.plusWord : t.minusWord));
        if (!val.missingRates.isEmpty()) s += " (بلا سعر صرف: " + Model.join(new ArrayList<>(val.missingRates), "، ") + ")";
        return s;
    }

    /** 11.6: يضع سطر الميزان تحت الأرصدة (في النص والصورة) لهذا الإرسال فقط. */
    static Reconcile.Template withScale(Reconcile.Template t, String line) {
        if (line == null || line.isEmpty()) return t;
        String f = Model.nz(t.footer).trim();
        t.footer = line + (f.isEmpty() ? "" : "\n\n" + f);
        return t;
    }

    /** 12.4: الحركات الخاصة المحسوبة في الرصيد (معلّقة) تُذكر تحت الأرصدة: داخلة فيها، وتنتظر الإكمال. */
    List<String> specialLines(Model.Office o, java.util.Set<String> only) {
        List<String> out = new java.util.ArrayList<>();
        for (Model.Tx t : a.db.specials(o.id)) {
            if (!t.isActive() || (only != null && !only.isEmpty() && !only.contains(t.currency))) continue;
            out.add(t.special + ": " + (Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ") + Model.fmt(t.amount, a.db.decimals(t.currency)) + " " + t.currency
                    + (Model.nz(t.person).isEmpty() ? "" : " — " + t.person));
        }
        return out;
    }

    static Reconcile.Template withSpecials(Reconcile.Template t, List<String> lines) {
        if (lines == null || lines.isEmpty()) return t;
        StringBuilder b = new StringBuilder("حركات معلّقة داخلة في الرصيد:");
        for (String s : lines) b.append("\n• ").append(s);
        String f = Model.nz(t.footer).trim();
        t.footer = b + (f.isEmpty() ? "" : "\n\n" + f);
        return t;
    }

    /** يضع سطور الأمانات تحت الأرصدة (في النص والصورة) لهذا الإرسال فقط. */
    static Reconcile.Template withHolds(Reconcile.Template t, List<String> lines) {
        if (lines == null || lines.isEmpty()) return t;
        StringBuilder b = new StringBuilder("الأمانات والمبالغ المجمّدة (لا تدخل في الرصيد):");
        for (String s : lines) b.append("\n• ").append(s);
        String f = Model.nz(t.footer).trim();
        t.footer = b + (f.isEmpty() ? "" : "\n\n" + f);
        return t;
    }

    private void copyText(Reconcile.Template t, Model.Office o, long at, List<Reconcile.Row> rows) {
        String txt = Reconcile.text(t, o.name, issuer(), at, rows);
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("مطابقة رصيد", txt));
        a.toast("نُسخ النص؛ الصقه في محادثة المكتب.");
    }

    private File imageFile(Reconcile.Template t, String office, long at, List<Reconcile.Row> rows) throws Exception {
        Bitmap bm = BalanceCard.render(t, office, issuer(), at, rows, accent(), a.homeLogo());
        File f = new File(a.sharedDir(), Ui.safeName("مطابقة-" + office) + "-" + Model.sdf(new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)).format(new Date(at)) + ".png");
        try (OutputStream os = new FileOutputStream(f)) {
            bm.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            bm.recycle();
        }
        return f;
    }

    /** يرسل الرسالة: نص عبر قائمة المشاركة، أو صورة، أو صورة مع النص كتعليق. */
    void deliver(final Reconcile.Template t, final Model.Office o, final long at, final List<Reconcile.Row> rows, final int mode) {
        final String txt = Reconcile.text(t, o.name, issuer(), at, rows);
        a.db.audit("EXPORT", "balance", o.id, mode == TEXT ? "text" : mode == IMAGE ? "image" : "image+text");
        if (mode == TEXT) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, txt);
            if (!a.startOwn(Intent.createChooser(i, "إرسال الرصيد"))) a.error(new Exception("لا يوجد تطبيق للمشاركة."));
            return;
        }
        a.busy("جارٍ تجهيز الصورة…", p -> imageFile(t, o.name, at, rows), res -> {
            File f = (File) res;
            Intent i = a.shareIntent(Collections.singletonList(f), "image/png", "مطابقة رصيد — " + o.name);
            if (mode == BOTH) i.putExtra(Intent.EXTRA_TEXT, txt);
            if (!a.startOwn(Intent.createChooser(i, "إرسال الرصيد"))) a.error(new Exception("لا يوجد تطبيق للمشاركة."));
        });
    }

    private void preview(Reconcile.Template t, String office, long at, List<Reconcile.Row> rows, int mode) {
        ScrollView sv = new ScrollView(a);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        sv.addView(l);
        if (mode != TEXT) {
            Bitmap bm = BalanceCard.render(t, office, issuer(), at, rows, accent(), a.homeLogo());
            ImageView iv = new ImageView(a);
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(bm);
            l.addView(iv, new LinearLayout.LayoutParams(-1, -2));
        }
        if (mode != IMAGE) {
            TextView tv = a.text(Reconcile.text(t, office, issuer(), at, rows), 14, a.dark, false);
            tv.setTextIsSelectable(true);
            tv.setBackground(a.round(Color.rgb(231, 246, 227), Color.rgb(200, 225, 195), 10));
            tv.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            l.addView(tv, a.lp(-1, -2, 6));
        }
        new AlertDialog.Builder(a).setTitle("معاينة").setView(sv).setPositiveButton("إغلاق", null).show();
    }

    // ===================================================================================
    // سجل المطابقات للمكتب
    // ===================================================================================

    void showOffice(final Model.Office o) {
        if (!a.require(Auth.Perm.VIEW) || !a.require(Auth.Perm.RECON)) return;   // 12.0
        a.here(() -> showOffice(o));
        a.tabbedScreen("مطابقات: " + o.name, () -> a.officeHub.showOffice(o), "offices");
        LinearLayout top = a.card();
        top.addView(a.text("الرصيد الحالي", 16, a.primary, true));
        Reconcile.Template t = template();
        List<Reconcile.Row> now = Reconcile.rows(currentBalances(o), a.db.currencyNames(), a.db.decimalsMap(), null, false);
        if (now.isEmpty()) top.addView(a.text("لا توجد أرصدة قائمة.", 14, a.muted, false));
        for (Reconcile.Row r : now) {
            top.addView(a.text(r.label(false) + ": " + t.amount(r) + " " + Reconcile.word(t, r), 15, r.sign() > 0 ? a.green : a.red, true));
        }
        a.button("مطابقة جديدة: إرسال الرصيد", a.primary, v -> sendDialog(o, () -> showOffice(o)));
        a.button("كشف الحركات بعد آخر مطابقة", a.accent, v -> a.exportUi.statementDialog(o, StatementScreen.Period.all(), null, ExportUi.SCOPE_SINCE, null));
        a.hint("«بعد المطابقة» تعني كل ما سُجّل بعدها، حتى الحركة المسجّلة بتاريخ أقدم، وأي إلغاء لحركة قديمة؛ فيبدأ الكشف برصيد المطابقة وينتهي بالرصيد الحالي.");

        List<Model.Recon> list = a.db.recons(o.id, 100);
        a.title("سجل المطابقات (" + list.size() + ")");
        if (list.isEmpty()) a.hint("لم تُنشأ مطابقة بعد.");
        Map<String, String> names = a.db.currencyNames();
        Map<String, Integer> dec = a.db.decimalsMap();
        for (final Model.Recon r : list) {
            LinearLayout c = a.card();
            c.addView(a.text(Model.date(r.createdAt) + "   •   " + statusText(r), 15, statusColor(r), true));
            List<Reconcile.Row> rows = Reconcile.rows(r.balances, names, dec, null, false);
            StringBuilder sb = new StringBuilder();
            for (Reconcile.Row x : rows) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(x.label(false)).append(": ").append(t.amount(x)).append(" ").append(Reconcile.word(t, x));
            }
            if (rows.isEmpty()) sb.append("كل الأرصدة صفر");
            c.addView(a.text(sb.toString(), 14, a.dark, false));
            if (!r.note.isEmpty()) c.addView(a.text("ملاحظة: " + r.note, 13, a.muted, false));
            // 10.8: من أنشأها ومن تحقق منها
            c.addView(a.text(a.db.reconPeople(r), 12, r.checkedBy != null ? a.green : a.muted, false));
            if (r.checkedBy == null && a.can(Auth.Perm.VERIFY)) c.addView(a.smallButton("تم التحقق من المطابقة ✓", a.primary, v -> {
                try {
                    a.db.verifyRecon(r.id);
                    a.toast("سُجّل تحققك من المطابقة ✓");
                    showOffice(o);
                } catch (Exception e) {
                    a.error(e);
                }
            }), a.lp(-1, 40, 2));
            LinearLayout b1 = a.row();
            if (Model.RECON_SENT.equals(r.status)) {
                b1.addView(a.smallButton("✓ أكّد المكتب", a.green, v -> statusDialog(r, Model.RECON_CONFIRMED, () -> showOffice(o))), a.weight(42, 1, 2));
                b1.addView(a.smallButton("✗ غير مطابق", a.red, v -> statusDialog(r, Model.RECON_DISPUTED, () -> showOffice(o))), a.weight(42, 1, 2));
            } else {
                b1.addView(a.smallButton("تغيير الحالة", Color.rgb(120, 120, 120), v -> statusDialog(r, Model.RECON_SENT, () -> showOffice(o))), a.weight(42, 1, 2));
            }
            c.addView(b1);
            LinearLayout b2 = a.row();
            b2.addView(a.smallButton("كشف بعدها", a.accent, v -> a.exportUi.statementDialog(o, StatementScreen.Period.all(), null, ExportUi.SCOPE_SINCE, r)), a.weight(42, 1, 2));
            b2.addView(a.smallButton("إعادة الإرسال", a.primary, v -> resend(o, r)), a.weight(42, 1, 2));
            if (a.can(Auth.Perm.TX_CANCEL)) {
                b2.addView(a.smallButton("حذف", Color.rgb(140, 140, 140), v -> a.confirm("حذف المطابقة", "تُحذف هذه المطابقة من السجل فقط؛ الحركات والأرصدة لا تتغير.", "حذف", () -> {
                    a.db.deleteRecon(r.id);
                    showOffice(o);
                })), a.weight(42, 0.7f, 2));
            }
            c.addView(b2);
        }
    }

    void statusDialog(final Model.Recon r, final String status, final Runnable after) {
        if (!a.require(Auth.Perm.TX_ADD)) return;
        if (Model.RECON_SENT.equals(status) && !Model.RECON_SENT.equals(r.status)) {
            new AlertDialog.Builder(a).setTitle("تغيير الحالة").setItems(new String[]{"مطابِقة ✓", "غير مطابِقة ✗", "بانتظار التأكيد"}, (d, w) -> {
                String s = w == 0 ? Model.RECON_CONFIRMED : w == 1 ? Model.RECON_DISPUTED : Model.RECON_SENT;
                if (Model.RECON_SENT.equals(s)) {
                    a.db.setReconStatus(r.id, s, r.note);
                    if (after != null) after.run();
                } else statusDialog(r, s, after);
            }).show();
            return;
        }
        boolean ok = Model.RECON_CONFIRMED.equals(status);
        final EditText note = new EditText(a);
        note.setHint(ok ? "ملاحظة (اختياري): من أكّد ومتى" : "ما الفرق؟ مثلًا: رصيد الدولار عندهم 7,800 لكم");
        note.setText(r.note);
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(4));
        l.addView(note, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle(ok ? "أكّد المكتب أن الرصيد مطابق" : "الرصيد غير مطابق عند المكتب")
                .setMessage("مطابقة " + Model.date(r.createdAt))
                .setView(l).setPositiveButton("حفظ", (d, w) -> {
                    a.db.setReconStatus(r.id, status, note.getText().toString().trim());
                    a.toast(ok ? "سُجّلت المطابقة مؤكدة ✓" : "سُجّلت المطابقة غير مطابقة ✗");
                    if (after != null) after.run();
                }).setNegativeButton("إلغاء", null).show();
    }

    private void resend(final Model.Office o, final Model.Recon r) {
        final Reconcile.Template t = template();
        final List<Reconcile.Row> rows = Reconcile.rows(r.balances, a.db.currencyNames(), a.db.decimalsMap(), null, t.showZero);
        new AlertDialog.Builder(a).setTitle("إعادة إرسال مطابقة " + Model.date(r.createdAt))
                .setItems(new String[]{"نص", "صورة", "صورة + نص", "نسخ النص"}, (d, w) -> {
                    if (w == 3) copyText(t, o, r.createdAt, rows);
                    else deliver(t, o, r.createdAt, rows, w);
                }).show();
    }

    // ===================================================================================
    // قالب الرسالة
    // ===================================================================================

    void templateScreen(final Runnable back) {
        if (!a.require(Auth.Perm.DESIGN)) return;
        final Reconcile.Template t = template();
        a.here(() -> templateScreen(back));
        a.tabbedScreen("قالب رسالة مطابقة الرصيد", back == null ? a.moreScreen::show : back, "more");
        a.hint("يظهر في النص المرسل لواتساب وفي الصورة. اترك أي حقل فارغًا لإخفائه.");
        final EditText greeting = a.input("الترحيب (مثل: السلام عليكم ورحمة الله وبركاته)");
        greeting.setText(t.greeting);
        final EditText title = a.input("العنوان (مثل: مطابقة رصيد)");
        title.setText(t.title);
        final EditText orn = a.input("زخرفة حول العنوان (مثل 🌹)");
        orn.setText(t.ornament);
        LinearLayout sr = a.row();
        final EditText sal = a.field("قبل اسم المكتب: السادة:");
        sal.setText(t.salutation);
        final EditText hon = a.field("بعده: المحترمين");
        hon.setText(t.honorific);
        sr.addView(sal, a.weight(50, 1, 2));
        sr.addView(hon, a.weight(50, 1, 2));
        a.body.addView(sr);
        a.add(a.label("كلمة الرصيد كما يقرؤها المكتب"));
        LinearLayout wr = a.row();
        final EditText plus = a.field("المكتب مدين لنا: لنا");
        plus.setText(t.plusWord);
        final EditText minus = a.field("نحن مدينون له: لكم");
        minus.setText(t.minusWord);
        wr.addView(plus, a.weight(50, 1, 2));
        wr.addView(minus, a.weight(50, 1, 2));
        a.body.addView(wr);
        final EditText footer = a.input("التذييل");
        footer.setSingleLine(false);
        footer.setMinLines(2);
        footer.setLayoutParams(a.lp(-1, -2, 4));
        footer.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
        footer.setText(t.footer);
        final CheckBox bold = new CheckBox(a);
        bold.setText("خط عريض للمبالغ والعنوان في واتساب");
        bold.setChecked(t.bold);
        final CheckBox issuer = new CheckBox(a);
        issuer.setText("إظهار اسم مكتبك");
        issuer.setChecked(t.showIssuer);
        final CheckBox code = new CheckBox(a);
        code.setText("إظهار رمز العملة بجانب اسمها (USD…)");
        code.setChecked(t.showCode);
        final CheckBox zero = new CheckBox(a);
        zero.setText("تحديد العملات ذات الرصيد الصفري افتراضيًا");
        zero.setChecked(t.showZero);
        for (CheckBox cb : new CheckBox[]{bold, issuer, code, zero}) a.body.addView(cb);

        // ---- 10.16: تصميم أسطر العملات والأجزاء الظاهرة ----
        a.title("شكل سطر العملة");
        a.hint(Reconcile.LINE_HELP);
        final EditText line = a.input("شكل السطر");
        line.setText(t.lineFormat);
        a.button("اختيار شكل جاهز", Color.rgb(120, 120, 120), v -> {
            String[] names = new String[Reconcile.LINE_PRESETS.length];
            Reconcile.Template ex = new Reconcile.Template();
            for (int i = 0; i < names.length; i++) {
                ex.lineFormat = Reconcile.LINE_PRESETS[i][1];
                ex.bold = false;
                names[i] = Reconcile.LINE_PRESETS[i][0] + "\n" + Reconcile.line(ex, Reconcile.sampleRows().get(1));
            }
            new AlertDialog.Builder(a).setTitle("شكل سطر العملة").setItems(names, (dg, w) -> line.setText(Reconcile.LINE_PRESETS[w][1])).show();
        });
        // موضع «لنا/لكم» من الرقم، ولونهما
        a.add(a.label("موضع «لنا / لكم» من الرقم"));
        final Ui.Seg pos = a.new Seg(Reconcile.WORD_POS, null, Reconcile.wordPos(t.lineFormat), false);
        a.body.addView(pos.view, a.lp(-1, -2, 2));
        pos.listener = i -> line.setText(Reconcile.withWordPos(line.getText().toString(), i));
        final CheckBox colored = new CheckBox(a);
        colored.setText("تلوين «لنا» و«لكم» (في الصورة، وبالدائرة الملوّنة في نص واتساب)");
        colored.setChecked(t.colored);
        a.body.addView(colored);
        final String[] cPlus = {t.colorPlus}, cMinus = {t.colorMinus};
        final Runnable[] afterColor = new Runnable[1];
        LinearLayout colr = a.row();
        final android.widget.Button bPlus = a.smallButton("لون «لنا»", Reconcile.rgb(cPlus[0], a.green), null);
        final android.widget.Button bMinus = a.smallButton("لون «لكم»", Reconcile.rgb(cMinus[0], a.red), null);
        colr.addView(bPlus, a.weight(44, 1, 2));
        colr.addView(bMinus, a.weight(44, 1, 2));
        a.body.addView(colr);
        final String[] colorNames = new String[Reconcile.COLORS.length];
        for (int i = 0; i < colorNames.length; i++) colorNames[i] = Reconcile.COLORS[i][2] + "  " + Reconcile.COLORS[i][0];
        final CheckBox trim = new CheckBox(a);
        trim.setText("حذف الأصفار العشرية (59,450 بدل 59,450.00)");
        trim.setChecked(t.trimZeros);
        final CheckBox gap = new CheckBox(a);
        gap.setText("سطر فارغ بين كل عملة وأخرى");
        gap.setChecked(t.gap);
        final CheckBox boldName = new CheckBox(a);
        boldName.setText("خط عريض لاسم العملة أيضًا");
        boldName.setChecked(t.boldName);
        final CheckBox showDate = new CheckBox(a);
        showDate.setText("إظهار سطر التاريخ");
        showDate.setChecked(t.showDate);
        final CheckBox showOffice = new CheckBox(a);
        showOffice.setText("إظهار سطر اسم المكتب");
        showOffice.setChecked(t.showOffice);
        final CheckBox showSep = new CheckBox(a);
        showSep.setText("إظهار الخط الفاصل قبل الأرصدة وبعدها");
        showSep.setChecked(t.showSeparator);
        final CheckBox[] more = {trim, gap, boldName, showDate, showOffice, showSep};
        for (CheckBox cb : more) a.body.addView(cb);
        a.add(a.label("العلامات قبل كل سطر: لنا • لكم • صفر (اتركها فارغة لحذفها)"));
        LinearLayout mr = a.row();
        final EditText mPlus = a.field("🟢"), mMinus = a.field("🔴"), mZero = a.field("⚪");
        mPlus.setText(t.markPlus);
        mMinus.setText(t.markMinus);
        mZero.setText(t.markZero);
        mr.addView(mPlus, a.weight(50, 1, 2));
        mr.addView(mMinus, a.weight(50, 1, 2));
        mr.addView(mZero, a.weight(50, 1, 2));
        a.body.addView(mr);
        LinearLayout lr = a.row();
        final EditText dateLabel = a.field("قبل التاريخ: 📅 التاريخ:");
        dateLabel.setText(t.dateLabel);
        final EditText officeMark = a.field("قبل المكتب: 🏢");
        officeMark.setText(t.officeMark);
        lr.addView(dateLabel, a.weight(50, 1.4f, 2));
        lr.addView(officeMark, a.weight(50, 1, 2));
        a.body.addView(lr);
        final EditText sepText = a.input("الخط الفاصل (أي رموز تريدها)");
        sepText.setText(t.separator);
        LinearLayout zr = a.row();
        final EditText zeroWord = a.field("كلمة الرصيد الصفري: صفر");
        zeroWord.setText(t.zeroWord);
        zr.addView(zeroWord, a.weight(50, 1, 2));
        a.body.addView(zr);
        final EditText emptyText = a.input("النص عند عدم وجود أرصدة");
        emptyText.setText(t.emptyText);
        // اختيار اللون يضبط الدائرة المطابقة له في نص واتساب (ويبقى تعديلها باليد ممكنًا)
        bPlus.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle("لون «لنا»").setItems(colorNames, (dg, w) -> {
            cPlus[0] = Reconcile.COLORS[w][1];
            mPlus.setText(Reconcile.COLORS[w][2]);
            bPlus.setBackground(a.round(Reconcile.rgb(cPlus[0], a.green), Reconcile.rgb(cPlus[0], a.green), 12));
            if (afterColor[0] != null) afterColor[0].run();
        }).show());
        bMinus.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle("لون «لكم»").setItems(colorNames, (dg, w) -> {
            cMinus[0] = Reconcile.COLORS[w][1];
            mMinus.setText(Reconcile.COLORS[w][2]);
            bMinus.setBackground(a.round(Reconcile.rgb(cMinus[0], a.red), Reconcile.rgb(cMinus[0], a.red), 12));
            if (afterColor[0] != null) afterColor[0].run();
        }).show());

        a.title("المعاينة");
        final TextView txt = a.add(a.text("", 14, a.dark, false));
        txt.setBackground(a.round(Color.rgb(231, 246, 227), Color.rgb(200, 225, 195), 10));
        txt.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        final ImageView img = new ImageView(a);
        img.setAdjustViewBounds(true);
        a.body.addView(img, a.lp(-1, -2, 6));
        final Runnable read = () -> {
            t.greeting = greeting.getText().toString();
            t.title = title.getText().toString().trim().isEmpty() ? "مطابقة رصيد" : title.getText().toString();
            t.ornament = orn.getText().toString();
            t.salutation = sal.getText().toString();
            t.honorific = hon.getText().toString();
            t.plusWord = plus.getText().toString().trim().isEmpty() ? "لنا" : plus.getText().toString().trim();
            t.minusWord = minus.getText().toString().trim().isEmpty() ? "لكم" : minus.getText().toString().trim();
            t.footer = footer.getText().toString();
            t.bold = bold.isChecked();
            t.showIssuer = issuer.isChecked();
            t.showCode = code.isChecked();
            t.showZero = zero.isChecked();
            t.lineFormat = Reconcile.validLine(line.getText().toString());
            t.colored = colored.isChecked();
            t.colorPlus = cPlus[0];
            t.colorMinus = cMinus[0];
            t.trimZeros = trim.isChecked();
            t.gap = gap.isChecked();
            t.boldName = boldName.isChecked();
            t.showDate = showDate.isChecked();
            t.showOffice = showOffice.isChecked();
            t.showSeparator = showSep.isChecked();
            t.markPlus = mPlus.getText().toString();
            t.markMinus = mMinus.getText().toString();
            t.markZero = mZero.getText().toString();
            t.dateLabel = dateLabel.getText().toString();
            t.officeMark = officeMark.getText().toString();
            t.separator = sepText.getText().toString();
            t.zeroWord = zeroWord.getText().toString();
            t.emptyText = emptyText.getText().toString().trim().isEmpty() ? new Reconcile.Template().emptyText : emptyText.getText().toString();
        };
        final Runnable refreshText = () -> {
            read.run();
            txt.setText(Reconcile.text(t, "كاش شام", issuer(), Clock.now(), Reconcile.sampleRows()));
        };
        final Runnable refreshImage = () -> {
            read.run();
            try {
                img.setImageBitmap(BalanceCard.render(t, "كاش شام", issuer(), Clock.now(), Reconcile.sampleRows(), accent(), a.homeLogo()));
            } catch (Throwable e) {
                img.setImageDrawable(null);
            }
        };
        for (EditText e : new EditText[]{greeting, title, orn, sal, hon, plus, minus, footer, line, mPlus, mMinus, mZero, dateLabel, officeMark, sepText, zeroWord, emptyText})
            e.addTextChangedListener(Ui.watcher(refreshText));
        for (CheckBox cb : more) cb.setOnCheckedChangeListener((b, x) -> {
            refreshText.run();
            refreshImage.run();
        });
        colored.setOnCheckedChangeListener((b, x) -> refreshImage.run());
        afterColor[0] = () -> {
            refreshText.run();
            refreshImage.run();
        };
        for (CheckBox cb : new CheckBox[]{bold, issuer, code, zero}) cb.setOnCheckedChangeListener((b, x) -> {
            refreshText.run();
            refreshImage.run();
        });
        refreshText.run();
        refreshImage.run();
        a.button("تحديث صورة المعاينة", Color.rgb(120, 120, 120), v -> refreshImage.run());
        a.button("حفظ القالب", a.primary, v -> {
            read.run();
            if (!line.getText().toString().contains("{مبلغ}")) {
                a.toast("شكل السطر يجب أن يحوي الخانة {مبلغ}. أُعيد الشكل الافتراضي.");
                line.setText(Reconcile.LINE_AMOUNT_FIRST);
                return;
            }
            for (Map.Entry<String, String> e : t.toMap().entrySet()) a.db.setSettingQuiet(e.getKey(), e.getValue());
            a.db.audit("SETTING", "settings", 0, "recon_template");
            a.toast("تم حفظ القالب");
            if (back != null) back.run();
            else a.settingsScreen.show();
        });
        a.button("القالب الافتراضي", Color.rgb(140, 140, 140), v -> a.confirm("القالب الافتراضي", "يعود نص الرسالة إلى الصيغة الافتراضية.", "استعادة", () -> {
            for (Map.Entry<String, String> e : new Reconcile.Template().toMap().entrySet()) a.db.setSettingQuiet(e.getKey(), e.getValue());
            templateScreen(back);
        }));
        a.hint("ملاحظة: الكلمة «لكم» تعني أن الرصيد لصالح المكتب (أي «علينا» في دفترك).");
    }
}
