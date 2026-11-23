package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** إدارة المكاتب (مع التثبيت في أعلى القوائم وحدود التنبيه)، والعملات، وأسعار الصرف. */
final class OfficesScreen {
    private final MainActivity a;

    OfficesScreen(MainActivity a) { this.a = a; }

    // ===================================================================================
    // قائمة المكاتب
    // ===================================================================================

    /** قائمة المكاتب صارت تبويب «المكاتب» (OfficeHub). */
    void showManager() { a.officeHub.showList(); }

    // ===================================================================================
    // نموذج المكتب
    // ===================================================================================

    void form(final Model.Office old) {
        if (!a.require(Auth.Perm.OFFICES)) return;
        a.tabbedScreen(old == null ? "إضافة مكتب" : "تعديل مكتب", old == null ? this::showManager : () -> a.officeHub.showOffice(old), "offices");
        final EditText name = a.input("اسم المكتب");
        final EditText sh = a.input("الاسم المختصر");
        final EditText phone = a.input("الهاتف");
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        final EditText loc = a.input("المدينة / البلد");
        final EditText contact = a.input("جهة الاتصال");
        final EditText notes = a.input("ملاحظات");
        if (old != null) {
            name.setText(old.name);
            sh.setText(old.shortName);
            phone.setText(old.phone);
            loc.setText(old.location);
            contact.setText(old.contact);
            notes.setText(old.notes);
        }
        final CheckBox active = new CheckBox(a);
        active.setText("المكتب نشط");
        active.setChecked(old == null || old.active);
        a.body.addView(active);
        final CheckBox pinned = new CheckBox(a);
        pinned.setText("📌 تثبيت المكتب في أعلى القوائم");
        pinned.setChecked(old != null && old.pinned);
        a.body.addView(pinned);

        a.title("العملات وحدود التنبيه");
        a.hint("حد «لنا»: ننبّهك إذا تجاوز ما لنا على المكتب هذا المبلغ. حد «علينا»: إذا تجاوز ما علينا للمكتب هذا المبلغ. اتركه فارغًا لعدم التنبيه.");
        final Map<String, CheckBox> checks = new LinkedHashMap<>();
        final Map<String, EditText[]> limits = new LinkedHashMap<>();
        List<String> assigned = old == null ? new ArrayList<String>() : a.db.officeCurrencies(old.id);
        Map<String, Db.Limit> oldLimits = old == null ? new HashMap<String, Db.Limit>() : a.db.officeLimits(old.id);
        final List<Model.Currency> currencies = a.db.currencies(true);
        for (Model.Currency c : currencies) {
            if (!c.active && !assigned.contains(c.code)) continue;
            LinearLayout r = a.row();
            CheckBox ck = new CheckBox(a);
            ck.setText(c.code + (c.active ? "" : " (معطلة)"));
            ck.setChecked(assigned.contains(c.code) || (old == null && currencies.size() == 1));
            EditText us = a.field("حد لنا");
            EditText them = a.field("حد علينا");
            Ui.numeric(us);
            Ui.numeric(them);
            us.setTextSize(14);
            them.setTextSize(14);
            Db.Limit l = oldLimits.get(c.code);
            if (l != null && l.receivable != null) us.setText(Db.money(l.receivable, c.decimals));
            if (l != null && l.payable != null) them.setText(Db.money(l.payable, c.decimals));
            r.addView(ck, a.weight(50, 1.1f, 2));
            r.addView(us, a.weight(48, 1, 2));
            r.addView(them, a.weight(48, 1, 2));
            a.body.addView(r);
            checks.put(c.code, ck);
            limits.put(c.code, new EditText[]{us, them});
        }
        a.title("حدود مجموع الأرصدة مقوّمة بالدولار (اختياري)");
        LinearLayout ur = a.row();
        final EditText usdUs = a.field("حد لنا USD");
        final EditText usdThem = a.field("حد علينا USD");
        Ui.numeric(usdUs);
        Ui.numeric(usdThem);
        ur.addView(usdUs, a.weight(52, 1, 2));
        ur.addView(usdThem, a.weight(52, 1, 2));
        a.body.addView(ur);
        if (old != null && old.usdLimit != null) usdUs.setText(Db.money(old.usdLimit, 2));
        if (old != null && old.usdOweLimit != null) usdThem.setText(Db.money(old.usdOweLimit, 2));
        final List<Db.Opening> openings = new ArrayList<>();
        final TextView openList;
        if (old == null) {
            a.title("الأرصدة السابقة (اختياري)");
            openList = a.add(a.text("لا توجد أرصدة سابقة مضافة", 13, a.muted, false));
            a.button("إضافة رصيد سابق", a.accent, v -> openingDialog(openings, openList));
        } else openList = null;
        a.button(old == null ? "حفظ المكتب" : "حفظ التعديلات", a.primary, v -> {
            try {
                String nm = name.getText().toString().trim();
                if (nm.isEmpty()) throw new Exception("اكتب اسم المكتب");
                boolean renamed = old == null || !nm.equalsIgnoreCase(Model.nz(old.name).trim());
                for (Model.Office x : a.db.offices()) {
                    if (renamed && (old == null || x.id != old.id) && x.name != null && x.name.trim().equalsIgnoreCase(nm)) {
                        throw new Exception("يوجد مكتب آخر بنفس الاسم. اختر اسمًا مميزًا حتى لا تختلط الكشوفات.");
                    }
                }
                List<String> codes = new ArrayList<>();
                Map<String, Db.Limit> lims = new HashMap<>();
                for (Model.Currency c : currencies) {
                    CheckBox ck = checks.get(c.code);
                    if (ck == null || !ck.isChecked()) continue;
                    codes.add(c.code);
                    EditText[] f = limits.get(c.code);
                    Long x = f[0].getText().toString().trim().isEmpty() ? null : Db.minor(f[0].getText().toString(), c.decimals);
                    Long y = f[1].getText().toString().trim().isEmpty() ? null : Db.minor(f[1].getText().toString(), c.decimals);
                    lims.put(c.code, new Db.Limit(x, y));
                }
                if (codes.isEmpty()) throw new Exception("فعّل عملة واحدة على الأقل لهذا المكتب");
                if (old != null) {
                    Map<String, Long> bal = a.db.balances(old.id);
                    for (String c : a.db.officeCurrencies(old.id)) {
                        Long b = bal.get(c);
                        if (!codes.contains(c) && b != null && b != 0) throw new Exception("لا يمكن إزالة العملة " + c + " لأن رصيد المكتب بها ليس صفرًا");
                    }
                }
                Long uu = usdUs.getText().toString().trim().isEmpty() ? null : Db.minor(usdUs.getText().toString(), 2);
                Long ut = usdThem.getText().toString().trim().isEmpty() ? null : Db.minor(usdThem.getText().toString(), 2);
                long id;
                if (old == null) {
                    id = a.db.addOffice(nm, sh.getText().toString(), phone.getText().toString(), loc.getText().toString(),
                            contact.getText().toString(), notes.getText().toString(), uu, ut, codes, lims, openings);
                } else {
                    id = old.id;
                    a.db.updateOffice(old.id, nm, sh.getText().toString(), phone.getText().toString(), loc.getText().toString(),
                            contact.getText().toString(), notes.getText().toString(), active.isChecked(), uu, ut, codes, lims);
                }
                if (pinned.isChecked() != (old != null && old.pinned)) a.db.setPinned(id, pinned.isChecked());
                a.toast("تم الحفظ");
                Model.Office saved = a.db.office(id);
                if (saved != null) a.officeHub.showOffice(saved);
                else showManager();
            } catch (Exception e) {
                a.error(e);
            }
        });
    }

    // ===================================================================================
    // إيقاف المكتب وحذفه
    // ===================================================================================

    private String balanceText(Model.Office o) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : a.db.balances(o.id).entrySet()) {
            if (e.getValue() == 0) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append("• ").append(e.getKey()).append(": ").append(Model.bal(e.getValue(), a.db.decimals(e.getKey())));
        }
        return sb.toString();
    }

    void stopDialog(final Model.Office o, final Runnable after) {
        if (!a.require(Auth.Perm.OFFICES)) return;
        String bal = balanceText(o);
        new AlertDialog.Builder(a).setTitle("إيقاف المكتب «" + o.name + "»")
                .setMessage("لن يظهر المكتب عند تسجيل الحركات ولن تُقبل عليه حركات جديدة. كشفه وحركاته ورصيده تبقى محفوظة، ويمكنك تفعيله في أي وقت."
                        + (bal.isEmpty() ? "" : "\n\n⚠ رصيده الحالي ليس صفرًا:\n" + bal))
                .setPositiveButton("إيقاف", (d, w) -> {
                    a.db.setOfficeActive(o.id, false);
                    a.toast("تم إيقاف المكتب «" + o.name + "»");
                    if (after != null) after.run();
                }).setNegativeButton("إلغاء", null).show();
    }

    void deleteDialog(final Model.Office o) {
        if (!a.require(Auth.Perm.OFFICES)) return;
        final long n = a.db.officeTxCount(o.id);
        if (n == 0) {
            new AlertDialog.Builder(a).setTitle("حذف المكتب «" + o.name + "»")
                    .setMessage("لا توجد حركات على هذا المكتب. سيُحذف نهائيًا مع أي رصيد سابق أُدخل عند إنشائه.")
                    .setPositiveButton("حذف", (d, w) -> {
                        try {
                            a.db.deleteOffice(o.id, false);
                            a.toast("تم حذف المكتب");
                            showManager();
                        } catch (Exception e) {
                            a.error(e);
                        }
                    }).setNegativeButton("إلغاء", null).show();
            return;
        }
        String bal = balanceText(o);
        new AlertDialog.Builder(a).setTitle("حذف المكتب «" + o.name + "»")
                .setMessage("على هذا المكتب " + n + " حركة" + (bal.isEmpty() ? "." : "، ورصيده:\n" + bal)
                        + "\n\nالحذف النهائي يمسح المكتب وكل حركاته وصورها من الكشوفات والأرصدة والتقارير."
                        + "\n\nالأنسب غالبًا: إيقاف المكتب، فيختفي من تسجيل الحركات ويبقى سجله.")
                .setPositiveButton("إيقاف بدلًا من الحذف", (d, w) -> stopDialog(o, this::showManager))
                .setNeutralButton("حذف نهائي…", (d, w) -> confirmHardDelete(o, n))
                .setNegativeButton("إلغاء", null).show();
    }

    private void confirmHardDelete(final Model.Office o, final long n) {
        if (!a.can(Auth.Perm.DELETE)) {
            a.error(new Exception("حذف مكتب له حركات متاح للمدير فقط."));
            return;
        }
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(4));
        l.addView(a.text("للتأكيد اكتب اسم المكتب كما هو:\n" + o.name, 14, a.dark, true));
        final EditText name = new EditText(a);
        name.setSingleLine(true);
        l.addView(name);
        l.addView(a.text("قبل الحذف تُحفظ نسخة أمان على الهاتف، ويمكنك التراجع من «النسخ الاحتياطي ← التراجع عن حذف المكتب» (تُحفظ نسخ أمان آخر ثلاث عمليات كبيرة).", 12, a.muted, false));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("حذف نهائي: " + n + " حركة").setView(l)
                .setPositiveButton("حذف نهائي", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!name.getText().toString().trim().equals(Model.nz(o.name).trim())) {
                name.setError("الاسم غير مطابق");
                return;
            }
            dlg.dismiss();
            final String label = "حذف المكتب «" + o.name + "»";
            a.busy("جارٍ حفظ نسخة أمان ثم الحذف…", p -> {
                Snapshot.take(a, a.db, label);
                a.db.deleteOffice(o.id, true);
                return null;
            }, res -> {
                showManager();
                new AlertDialog.Builder(a).setTitle("تم حذف المكتب")
                        .setMessage("حُذف المكتب «" + o.name + "» و" + n + " حركة. إن كان الحذف خطأً يمكنك التراجع الآن أو لاحقًا من شاشة النسخ الاحتياطي.")
                        .setPositiveButton("حسنًا", null)
                        .setNeutralButton("تراجع الآن", (x, y) -> a.backupScreen.revertDialog()).show();
            });
        }));
        dlg.show();
    }

    private void openingDialog(final List<Db.Opening> ops, final TextView list) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        List<String> codes = new ArrayList<>();
        for (Model.Currency c : a.db.currencies(false)) codes.add(c.code);
        final Spinner c = new Spinner(a);
        a.setItems(c, codes);
        final Spinner t = a.spinner(new String[]{"لنا", "علينا"});
        final EditText am = new EditText(a);
        am.setHint("المبلغ");
        Ui.numeric(am);
        l.addView(c);
        l.addView(t);
        l.addView(am);
        new AlertDialog.Builder(a).setTitle("رصيد سابق").setMessage("فعّل العملة نفسها في قائمة عملات المكتب قبل الحفظ.").setView(l)
                .setPositiveButton("إضافة", (d, w) -> {
                    try {
                        String code = String.valueOf(c.getSelectedItem());
                        long amount = Db.minor(am.getText().toString(), a.db.decimals(code));
                        if (amount <= 0) throw new Exception("المبلغ يجب أن يكون أكبر من صفر");
                        ops.add(new Db.Opening(code, String.valueOf(t.getSelectedItem()), amount));
                        StringBuilder sb = new StringBuilder();
                        for (Db.Opening o : ops) sb.append("• ").append(o.code).append(": ").append(o.type).append(' ').append(Db.fmt(o.amount, a.db.decimals(o.code))).append('\n');
                        list.setText(sb.toString().trim());
                        list.setTextColor(a.dark);
                    } catch (Exception e) {
                        a.error(e);
                    }
                }).setNegativeButton("إلغاء", null).show();
    }

    // ===================================================================================
    // تنبيهات الحدود
    // ===================================================================================

    List<String> limitAlerts(Model.Office o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        Map<String, Long> b = a.db.balances(o.id);
        Map<String, Db.Limit> limits = a.db.officeLimits(o.id);
        for (Map.Entry<String, Long> e : b.entrySet()) {
            Db.Limit l = limits.get(e.getKey());
            if (l == null) continue;
            Ledger.LimitBreach br = Ledger.check(e.getKey(), e.getValue(), l.receivable, l.payable);
            if (br != null) {
                int dec = a.db.decimals(e.getKey());
                out.add("• " + o.name + " — " + e.getKey() + ": " + (br.receivable ? "لنا " : "علينا ") + Db.fmt(br.amount, dec) + " (الحد " + Db.fmt(br.limit, dec) + ")");
            }
        }
        if (o.usdLimit != null || o.usdOweLimit != null) {
            Ledger.Valuation v = Ledger.valueOffice(b, a.db.usdRates(), a.db.decimalsMap());
            long us = v.receivableUsd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
            long them = v.payableUsd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
            if (o.usdLimit != null && o.usdLimit > 0 && us > o.usdLimit) {
                out.add("• " + o.name + " — مجموع «لنا» مقوّمًا ≈ " + Db.fmt(us, 2) + " USD (الحد " + Db.fmt(o.usdLimit, 2) + ")");
            }
            if (o.usdOweLimit != null && o.usdOweLimit > 0 && them > o.usdOweLimit) {
                out.add("• " + o.name + " — مجموع «علينا» مقوّمًا ≈ " + Db.fmt(them, 2) + " USD (الحد " + Db.fmt(o.usdOweLimit, 2) + ")");
            }
            if (!v.missingRates.isEmpty() && !out.isEmpty()) out.add("  (لا يوجد سعر لـ " + v.missingRates + " فلم تدخل في التقويم)");
        }
        return out;
    }

    // ===================================================================================
    // العملات
    // ===================================================================================

    void showCurrencies() {
        // 12.6: الشاشة لمن يملك العملات أو المعادن، وكل قسم بصلاحيته
        final boolean cur = a.can(Auth.Perm.CURRENCIES), met = a.can(Auth.Perm.METALS);
        if (!cur && !met && !a.require(Auth.Perm.CURRENCIES)) return;
        a.here(this::showCurrencies);
        a.tabbedScreen("العملات والمعادن", a.moreScreen::show, "more");
        a.button("أسعار الصرف (أونلاين ويدوي)", a.primary, v -> showRates(this::showCurrencies));
        List<Model.Currency> all = a.db.currencies(true);
        for (final Model.Currency c : all) {
            if (Metals.isMetal(c.code) || !cur) continue;
            LinearLayout x = a.card();
            x.addView(a.text(c.toString() + " — " + c.decimals + " منازل" + (c.active ? "" : " — معطلة"), 17, c.active ? a.dark : a.muted, true));
            LinearLayout r = a.row();
            r.addView(a.smallButton(c.active ? "تعطيل" : "تفعيل", c.active ? a.red : a.primary, v -> {
                try {
                    a.db.saveCurrency(c.code, c.name, c.decimals, !c.active);
                    showCurrencies();
                } catch (Exception e) {
                    a.error(e);
                }
            }), a.weight(46, 1, 3));
            r.addView(a.smallButton("تعديل", a.accent, v -> currencyDialog(c)), a.weight(46, 1, 3));
            x.addView(r);
        }
        if (cur) a.button("إضافة عملة", a.primary, v -> currencyDialog(null));
        if (!met) return;

        // 12.5: المعادن الثمينة بالوزن
        a.body.addView(a.text("المعادن الثمينة (بالوزن)", 18, a.ink, true), a.lp(-1, -2, 16));
        a.hint("ذهب، فضة، بلاتين… كل معدن بعياره حساب مستقل بالوزن، وله كل ما للعملات: الحركات والكشوفات والمطابقات والقص. "
                + "فعّله لأي مكتب كالعملة. التحويل بين العيارات بـ«قص» بنسبة ظاهرة.");
        for (final Model.Currency c : all) {
            final Metals.Metal m = Metals.of(c.code);
            if (m == null) continue;
            LinearLayout x = a.card();
            x.addView(a.text(c.name + " · " + c.code + (c.active ? "" : " — معطّل"), 17, c.active ? a.dark : a.muted, true));
            x.addView(a.text(Metals.baseName(m.base) + (m.fineness > 0 ? " • " + Metals.purityLabel(m.base, m.fineness) + " (" + m.fineness + "‰)" : "")
                    + " • الوحدة: " + m.u().name + " (" + m.u().decimals + " منازل)"
                    + " • " + (m.valued ? "يدخل الميزان بالدولار" : "بالوزن فقط (خارج الميزان)"), 13, a.muted, false));
            LinearLayout r = a.row();
            r.addView(a.smallButton(c.active ? "تعطيل" : "تفعيل", c.active ? a.red : a.primary, v -> {
                try {
                    a.db.saveMetal(c.code, c.name, m.base, m.fineness, m.unit, m.valued, !c.active);
                    showCurrencies();
                } catch (Exception e) {
                    a.error(e);
                }
            }), a.weight(46, 1, 3));
            r.addView(a.smallButton("تعديل", a.accent, v -> metalDialog(c, m)), a.weight(46, 1, 3));
            x.addView(r);
        }
        a.button("إضافة معدن (ذهب، فضة…)", a.accent, v -> metalDialog(null, null));
    }

    /** 12.5: معدن جديد أو تعديله: النوع، العيار، الوحدة (ثابتة بعد أول حركة)، الرمز والاسم، والتقويم بالدولار. */
    private void metalDialog(final Model.Currency old, final Metals.Metal om) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        final boolean used = old != null && a.db.currencyUsed(old.code);
        final String[] baseIds = Metals.BASES;
        final String[] baseNames = new String[baseIds.length];
        for (int i = 0; i < baseIds.length; i++) baseNames[i] = Metals.baseName(baseIds[i]);
        l.addView(a.label("المعدن"));
        final Ui.Seg base = a.new Seg(baseNames, null, om == null ? 0 : java.util.Arrays.asList(baseIds).indexOf(om.base), false);
        l.addView(base.view);
        l.addView(a.label("العيار"));
        final EditText fin = a.field("الذهب: 24 أو 21 أو 18… الفضة: 999 أو 925 (اتركه فارغًا إن لم يكن)");
        Ui.numeric(fin);
        if (om != null && om.fineness > 0) {
            int k = Metals.karatOf(om.fineness);
            fin.setText(Metals.GOLD.equals(om.base) && k > 0 ? String.valueOf(k) : String.valueOf(om.fineness));
        }
        l.addView(fin);
        // 12.6: أزرار العيارات الشائعة (كل عيار حساب مستقل)
        final Ui.Flow purChips = a.flow();
        l.addView(purChips, a.lp(-1, -2, 2));
        final Runnable fillChips = () -> {
            purChips.removeAllViews();
            if (used) return;
            for (final String p : Metals.commonPurities(baseIds[Math.max(0, base.selected)]))
                purChips.addView(a.pill(p, p.equals(fin.getText().toString().trim()), v -> fin.setText(p)));
        };
        l.addView(a.label("وحدة الوزن"));
        final List<String> unitIds = new ArrayList<>(Metals.UNITS.keySet());
        String[] unitNames = new String[unitIds.size()];
        for (int i = 0; i < unitIds.size(); i++) unitNames[i] = Metals.UNITS.get(unitIds.get(i)).shortName;
        final Ui.Seg unit = a.new Seg(unitNames, null, om == null ? 0 : unitIds.indexOf(om.unit), false);
        l.addView(unit.view);
        if (used) {
            l.addView(a.text("عليه حركات: المعدن والعيار والوحدة لا تتغير (تغييرها يغيّر معنى كل وزن مسجّل).", 12, a.red, false));
            base.view.setEnabled(false);
            fin.setEnabled(false);
            unit.view.setEnabled(false);
        }
        final EditText code = a.field("الرمز مثل XAU21");
        final EditText name = a.field("الاسم مثل ذهب عيار 21 (غرام)");
        if (old != null) {
            code.setText(old.code);
            code.setEnabled(false);
            name.setText(old.name);
        }
        l.addView(a.label("الرمز والاسم"));
        l.addView(code);
        l.addView(name);
        final android.widget.CheckBox valued = new android.widget.CheckBox(a);
        valued.setText("يدخل الميزان والتقويم بالدولار (بسعر وحدة تدخله في «أسعار الصرف»)");
        valued.setChecked(om != null && om.valued);
        l.addView(valued);
        Runnable suggest = () -> {
            if (old != null) return;
            try {
                String b = baseIds[Math.max(0, base.selected)];
                int f = fineness(b, fin.getText().toString());
                String u = unitIds.get(Math.max(0, unit.selected));
                java.util.List<String> taken = new java.util.ArrayList<>();
                for (Model.Currency x : a.db.currencies(true)) taken.add(x.code);
                code.setText(Metals.uniqueCode(b, f, u, taken));
                name.setText(Metals.suggestName(b, f, u));
            } catch (Exception ignored) {
                // عيار غير مفهوم بعد
            }
        };
        base.listener = i -> {
            fillChips.run();
            suggest.run();
        };
        unit.listener = i -> suggest.run();
        fin.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override public void afterTextChanged(android.text.Editable e) {
                suggest.run();
                fillChips.run();
            }
        });
        fillChips.run();
        suggest.run();
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(l);
        new AlertDialog.Builder(a).setTitle(old == null ? "معدن جديد" : "تعديل " + old.code).setView(sv).setPositiveButton("حفظ", (d, w) -> {
            try {
                String b = baseIds[Math.max(0, base.selected)];
                int f = fineness(b, fin.getText().toString());
                String u = unitIds.get(Math.max(0, unit.selected));
                String cc = code.getText().toString().trim().toUpperCase(Locale.US);
                if (old == null) for (Model.Currency x : a.db.currencies(true)) if (x.code.equals(cc)) throw new Exception("الرمز " + cc + " موجود");
                // 12.6: المعدن نفسه بالعيار نفسه والوحدة نفسها حساب واحد فقط؛ وعيار غير محدد مع وجود حسابات أخرى لنفس المعدن ممنوع (يلتبس)
                if (old == null || om == null || !om.base.equals(b) || om.fineness != f || !om.unit.equals(u)) for (Metals.Metal x : a.db.metals()) {
                    if (old != null && x.code.equals(old.code)) continue;
                    if (!x.base.equals(b)) continue;
                    if (x.fineness == f && x.unit.equals(u))
                        throw new Exception("عندك حساب " + Metals.baseName(b) + (f > 0 ? " " + Metals.purityLabel(b, f) : "") + " بالـ" + Metals.unit(u).name + " باسم «" + x.code + "». كل عيار حساب واحد.");
                    if (f == 0 && !Metals.OTHER.equals(b)) throw new Exception("اختر العيار: عندك حساب " + Metals.baseName(b) + " آخر، والعيار يميّز بينهما");
                }
                a.db.saveMetal(cc, name.getText().toString().trim(), b, f, u, valued.isChecked(), old == null || old.active);
                showCurrencies();
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    /** «21» للذهب = 875‰؛ «925» = 925‰؛ فارغ = 0. */
    static int fineness(String base, String typed) throws Exception {
        String t = Db.normalizeNumber(typed == null ? "" : typed.trim());
        if (t.isEmpty()) return 0;
        int n = Integer.parseInt(t);
        if (n >= 1 && n <= 24 && Metals.finenessOfKarat(n) > 0) return Metals.finenessOfKarat(n);
        if (n >= 100 && n <= 1000) return n;
        throw new Exception("العيار غير مفهوم: اكتب قيراط الذهب (24، 22، 21، 18…) أو العيار بالألف (999، 925، 875…)");
    }

    private void currencyDialog(final Model.Currency old) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        final EditText code = new EditText(a);
        code.setHint("الرمز مثل BRL");
        final EditText name = new EditText(a);
        name.setHint("اسم العملة");
        final EditText dec = new EditText(a);
        dec.setHint("المنازل العشرية (0 إلى 6)");
        dec.setInputType(InputType.TYPE_CLASS_NUMBER);
        final boolean used = old != null && a.db.currencyUsed(old.code);
        if (old != null) {
            code.setText(old.code);
            code.setEnabled(false);
            name.setText(old.name);
            dec.setText("" + old.decimals);
            dec.setEnabled(!used);
        }
        l.addView(code);
        l.addView(name);
        l.addView(dec);
        if (used) l.addView(a.text("لا يمكن تغيير المنازل العشرية لعملة مستخدمة في حركات؛ تغييرها يغيّر قيم المبالغ المسجلة.", 12, a.muted, false));
        new AlertDialog.Builder(a).setTitle(old == null ? "عملة جديدة" : "تعديل عملة").setView(l).setPositiveButton("حفظ", (d, w) -> {
            try {
                String cc = code.getText().toString().trim().toUpperCase(Locale.US);
                if (cc.isEmpty() || name.getText().toString().trim().isEmpty()) throw new Exception("اكتب رمز العملة واسمها");
                if (!cc.matches("[A-Z0-9]{2,8}")) throw new Exception("رمز العملة يجب أن يكون أحرفًا إنجليزية أو أرقامًا (2 إلى 8)");
                int dd = Integer.parseInt(Db.normalizeNumber(dec.getText().toString()));
                if (dd < 0 || dd > 6) throw new Exception("المنازل العشرية يجب أن تكون بين 0 و 6");
                if (old == null) {
                    for (Model.Currency x : a.db.currencies(true)) if (x.code.equals(cc)) throw new Exception("هذه العملة موجودة مسبقًا");
                }
                a.db.saveCurrency(cc, name.getText().toString().trim(), dd, old == null || old.active);
                showCurrencies();
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    // ===================================================================================
    // أسعار الصرف
    // ===================================================================================

    static String usd(BigDecimal v) {
        BigDecimal u = v.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros();
        return u.scale() < 0 ? u.setScale(0).toPlainString() : u.toPlainString();
    }

    static String unitsPerUsd(BigDecimal usdPerUnit) {
        if (usdPerUnit == null || usdPerUnit.signum() <= 0) return "—";
        BigDecimal u = BigDecimal.ONE.divide(usdPerUnit, 6, RoundingMode.HALF_EVEN).stripTrailingZeros();
        return u.scale() < 0 ? u.setScale(0).toPlainString() : u.toPlainString();
    }

    void showRates(final Runnable backTo) {
        if (!a.require(Auth.Perm.VIEW)) return;
        final boolean edit = a.can(Auth.Perm.RATES);   // 12.0: «أسعار الصرف» صلاحية مستقلة
        final boolean online = a.can(Auth.Perm.RATES_ONLINE);   // 12.6: التحديث أونلاين صلاحية مستقلة
        a.here(() -> showRates(backTo));
        a.tabbedScreen("أسعار الصرف", backTo, "more");
        a.hint("يُستخدم السعر لتقويم الأرصدة بالدولار فقط، ولا يغيّر أي مبلغ مسجّل. السعر اليدوي يتقدّم على السعر الأونلاين.");
        if (online) {
            a.button("تحديث الأسعار من الإنترنت الآن", a.primary, v -> a.busy("جارٍ جلب الأسعار…", p -> ExchangeRates.refresh(a.db), res -> {
                a.toast("تم تحديث " + res + " سعرًا");
                showRates(backTo);
            }));
            a.hint("المصدر: " + ExchangeRates.source(a.db));
            a.body.addView(a.smallButton("تغيير مصدر الأسعار", Color.rgb(120, 120, 120), v -> {
                final android.widget.EditText u = a.field(ExchangeRates.DEFAULT_URL);
                u.setText(a.db.setting("rates_url", ""));
                u.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
                android.widget.LinearLayout box = new android.widget.LinearLayout(a);
                box.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
                box.addView(u, new android.widget.LinearLayout.LayoutParams(-1, a.dp(52)));
                new android.app.AlertDialog.Builder(a).setTitle("مصدر أسعار الصرف")
                        .setMessage("اتركه فارغًا للمصدر العالمي الافتراضي، أو اكتب عنوان خادمك (https) بالصيغة نفسها لأسعار السوق المحلية.")
                        .setView(box).setPositiveButton("حفظ", (d, w) -> {
                            String t = u.getText().toString().trim();
                            if (!t.isEmpty() && !t.startsWith("https://")) {
                                a.toast("العنوان يجب أن يبدأ بـ https://");
                                return;
                            }
                            try {
                                a.db.setRatesUrl(t);
                            } catch (Exception e) {
                                a.error(e);
                                return;
                            }
                            showRates(backTo);
                        }).setNegativeButton("إلغاء", null).show();
            }), a.lp(-2, 40, 4));
        }
        Map<String, Db.Rate> rates = a.db.rates();
        for (final Model.Currency c : a.db.currencies(false)) {
            if (c.code.equals("USD")) continue;
            LinearLayout x = a.card();
            x.addView(a.text(c.toString(), 17, a.primary, true));
            Db.Rate r = rates.get(c.code);
            StringBuilder sb = new StringBuilder();
            final Metals.Metal mt = Metals.of(c.code);
            if (mt != null) {
                // 12.5: المعدن بسعر الوحدة بالدولار
                String un = mt.u().shortName;
                if (r != null && r.online != null) sb.append("أونلاين (للاستئناس): 1 ").append(un).append(" = ").append(usd(r.online)).append(" USD (").append(Model.date(r.onlineAt)).append(")\n");
                else sb.append("أونلاين: لا يوجد (مصدر الأسعار لا يعطي سعر المعادن)\n");
                if (r != null && r.manual != null) sb.append("يدوي: 1 ").append(un).append(" = ").append(usd(r.manual)).append(" USD (").append(Model.date(r.manualAt)).append(")\n");
                sb.append(mt.valued ? "يدخل الميزان بالدولار" + (r == null ? " (لا سعر بعد)" : " — المستخدم: " + r.source) : "بالوزن فقط: خارج الميزان (غيّره من «العملات ← تعديل»)");
            } else {
                if (r != null && r.online != null) sb.append("أونلاين: 1 USD = ").append(unitsPerUsd(r.online)).append(' ').append(c.code).append(" (").append(Model.date(r.onlineAt)).append(")\n");
                else sb.append("أونلاين: لا يوجد\n");
                if (r != null && r.manual != null) sb.append("يدوي: 1 USD = ").append(unitsPerUsd(r.manual)).append(' ').append(c.code).append(" (").append(Model.date(r.manualAt)).append(")\n");
                sb.append("المستخدم في التقويم: ").append(r == null ? "لا يوجد سعر" : r.source);
            }
            x.addView(a.text(sb.toString(), 14, a.dark, false));
            if (!edit) continue;
            final EditText in = a.field(mt != null ? "سعر 1 " + mt.u().shortName + " بالدولار؟" : "1 دولار = كم " + c.code + "؟");
            Ui.numeric(in);
            x.addView(in, a.lp(-1, 50, 4));
            LinearLayout rr = a.row();
            rr.addView(a.smallButton("حفظ السعر اليدوي", a.primary, v -> {
                try {
                    // 10.5 (AUDIT-021): «34.250» سعر = أربعة وثلاثون وربع، لا 34250
                    BigDecimal units = CashBox.rateDecimal(in.getText().toString());
                    if (units == null) throw new Exception("السعر غير مفهوم: اكتبه مثل 34.25 أو 13500");
                    if (units.signum() <= 0) throw new Exception("السعر يجب أن يكون أكبر من صفر");
                    a.db.setManualRate(c.code, mt != null ? units : BigDecimal.ONE.divide(units, 16, RoundingMode.HALF_EVEN));
                    a.toast("تم الحفظ");
                    showRates(backTo);
                } catch (Exception e) {
                    a.error(e instanceof NumberFormatException ? new NumberFormatException() : e);
                }
            }), a.weight(46, 1, 3));
            if (r != null && r.manual != null) {
                rr.addView(a.smallButton("إلغاء اليدوي", a.red, v -> {
                    a.db.setManualRate(c.code, null);
                    showRates(backTo);
                }), a.weight(46, 1, 3));
            }
            x.addView(rr);
        }
    }
}
