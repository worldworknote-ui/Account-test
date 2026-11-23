package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/**
 * 10.6: «تسجيل حركة قص عملات» بأسلوب «تسجيل حركة»: نبيع للمكتب عملة («لنا» عليه) ونشتري منه أخرى («علينا» له).
 * العملتان بأزرار، والمبلغ المقابل محسوب من السعر (ويمكن تعديله فيُحفظ السعر الفعلي)، وزر قلب العملية، والملخص الحي مع أثرها
 * على الرصيدين قبل الحفظ، ثم نافذة التأكيد بالأرصدة. الساقان مرتبطتان في Db.addFx: تُعتمدان وتُلغيان معًا.
 */
final class FxView extends RecordView {
    OfficeBox office;
    final JPanel sellHolder = holder(), buyHolder = holder();
    Ui.Seg sell, buy, dir;
    final Ui.Field amount = Ui.numeric("0"), counter = Ui.numeric("0"), rate = Ui.numeric("مثال: 0.92 أو 35.4"), desc = Ui.field("البيان (اختياري)");
    final JLabel meaning = Ui.label(" ", 13, Ui.primaryInk, true);
    final List<String> curs = new ArrayList<>();
    boolean manual, quiet;
    /** 10.13: آخر ما كتبه المحاسب المبلغ المشترى، فيُحسب المباع من السعر (الحساب من الطرفين). */
    boolean fromBuy;
    /** 10.8: الخانة فيها سعر البرنامج المقترح (لم يكتب المحاسب سعره). */
    boolean rateAuto = true;
    final JLabel refLine = Ui.label(" ", 12.5f, Ui.goldInk, false);
    Ui.Btn useRef;

    /** 10.18: فتح عملية قص مسجّلة للتعديل (من تفاصيل الحركة). */
    static final class Edit {
        final long tx;

        Edit(long tx) { this.tx = tx; }
    }

    /** ساقا العملية التي تُعدَّل {المباعة، المشتراة}، أو null عند تسجيل جديد. */
    Model.Tx[] editing;
    final JLabel editNote = Ui.label(" ", 13, Ui.warnInk, true);

    FxView(App app) { super(app, "تسجيل حركة"); }

    @Override void build() {
        office = new OfficeBox("اختر المكتب");
        dir = new Ui.Seg(new String[]{"1 المباعة = ؟ المشتراة", "1 المشتراة = ؟ المباعة"}, null, 0);
        big(amount);
        big(counter);
        big(rate);
        amount.getAccessibleContext().setAccessibleName("المبلغ المباع");
        counter.getAccessibleContext().setAccessibleName("المبلغ المقابل");
        rate.getAccessibleContext().setAccessibleName("السعر");
        JPanel form = Ui.column();
        form.add(kinds(app, RecordMenu.FX, () -> office.get()));
        form.add(Ui.gap(12));
        form.add(Ui.para("قص عملات مع مكتب: نبيع من رصيدنا لديه عملة (تُخصم من رصيدنا) ونأخذ مقابلها عملة أخرى (تُضاف إلى رصيدنا). " + FxDeal.NETTING_NOTE
                + " تُسجَّل الساقان معًا وتُعتمدان وتُلغيان معًا.", 12.5f, Ui.muted, 460));
        form.add(Ui.gap(10));
        editNote.setVisible(false);
        form.add(editNote);
        form.add(Ui.labeled("المكتب", office));
        form.add(Ui.gap(12));
        Ui.Card sc = side("العملة المباعة من رصيدنا", "تُخصم من رصيدنا لدى المكتب (يجوز أن تتجاوزه)", Ui.red, Ui.debitBg);
        sc.add(sellHolder);
        sc.add(Ui.gap(8));
        sc.add(Ui.labeled("المبلغ المباع", amount));
        form.add(sc);
        form.add(Ui.gap(6));
        form.add(centered(swapButton("قلب العملية", e -> swap())));
        form.add(Ui.gap(6));
        Ui.Card bc = side("العملة المشتراة مقابلها", "تُضاف إلى رصيدنا لدى المكتب", Ui.green, Ui.creditBg);
        bc.add(buyHolder);
        bc.add(Ui.gap(8));
        bc.add(Ui.labeled("المبلغ المقابل (اكتب أحد المبلغين والسعر فيُحسب الآخر)", counter));
        form.add(bc);
        form.add(Ui.gap(14));
        form.add(Ui.labeled("سعر الصرف", dir));
        form.add(Ui.gap(6));
        form.add(rate);
        form.add(Ui.gap(4));
        form.add(meaning);
        // 10.8: سعر البرنامج يُقترح، ويبقى ظاهرًا للاستئناس وإن كتب المحاسب سعره
        form.add(refLine);
        useRef = Ui.ghost("استعمل سعر البرنامج", e -> {
            rateAuto = true;
            manual = false;
            reference();
            calc();
        });
        useRef.setVisible(false);
        form.add(Ui.flow(useRef));
        form.add(Ui.gap(12));
        form.add(Ui.labeled("البيان", desc));
        columns(form, summaryColumn("تسجيل القص", this::save));

        office.onChange = () -> {
            if (!building) officeChanged();
        };
        dir.listener = i -> {
            manual = false;
            // السعر المكتوب يُقلب مع الاتجاه حتى لا يتغير معناه
            java.math.BigDecimal r = FxDeal.parseRate(rate.getText());
            if (r != null && !rateAuto) {
                quiet = true;
                rate.setText(FxDeal.referenceIn(r, false).toPlainString());
                quiet = false;
            }
            reference();
            calc();
        };
        amount.getDocument().addDocumentListener(now(() -> {
            if (!quiet) {
                fromBuy = false;
                manual = false;
            }
            calc();
        }));
        rate.getDocument().addDocumentListener(now(() -> {
            if (!quiet) {
                manual = false;
                rateAuto = rate.getText().trim().isEmpty();
                useRef.setVisible(!rateAuto && refLine.getText().trim().length() > 0 && !refLine.getText().startsWith("لا يوجد"));
            }
            calc();
        }));
        counter.getDocument().addDocumentListener(now(() -> {
            if (quiet) return;
            if (fromBuy || amount.getText().trim().isEmpty()) {
                fromBuy = true;
                calc();
            } else update();
        }));
        counter.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyTyped(java.awt.event.KeyEvent e) { manual = true; }
        });
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(Ui.key(KeyEvent.VK_S), "save-fx");
        getActionMap().put("save-fx", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing()) save();
            }
        });
    }

    @Override void refresh() {
        // يحافظ على ما كُتب: يعيد قراءة المكاتب والأرصدة فقط
        building = true;
        Model.Office cur = office.get();
        office.load(cur != null ? cur.id : db.settingLong("last_office", -1), false);
        building = false;
        officeChanged();
        done();
    }

    @Override void open(Object arg) {
        if (arg instanceof Edit) {
            loadEdit(((Edit) arg).tx);
            done();
        } else if (arg instanceof Open) {
            setEditing(null);
            clearInputs();
            building = true;
            office.search.setText("");
            office.load(((Open) arg).office, false);
            building = false;
            officeChanged();
            done();
        } else refresh();
    }

    void setEditing(Model.Tx[] legs) {
        editing = legs;
        office.combo.setEnabled(legs == null);
        office.search.setEnabled(legs == null);
        editNote.setVisible(legs != null);
        if (save != null) save.setText(legs == null ? "تسجيل القص" : "حفظ التعديل");
        subtitle.setText(legs == null ? "" : "تعديل قص عملات مسجّل");
    }

    /** يملأ الشاشة بعملية قص مسجّلة: العملتان والمبلغان كما هما، والسعر الفعلي المحفوظ. */
    void loadEdit(long txId) {
        Model.Tx[] legs = db.fxLegsOf(txId);
        if (legs == null || !legs[0].isActive() || !legs[1].isActive()) {
            Ui.toast("هذه العملية ليست قص عملات نشطًا يمكن تعديله");
            setEditing(null);
            refresh();
            return;
        }
        clearInputs();
        building = true;
        office.search.setText("");
        office.load(legs[0].officeId, false);
        building = false;
        setEditing(legs);
        editNote.setText("تعديل عملية " + Model.shortRef(legs[0].uuid) + " بتاريخ " + Model.date(legs[0].createdAt) + " — تُعكس القديمة وتُسجَّل المصحّحة مكانها بالتاريخ نفسه.");
        officeChanged();
        // العملة المحفوظة قد تكون أُوقفت للمكتب بعد التسجيل: تبقى ظاهرة ليُرى ما يُعدَّل
        for (Model.Tx x : legs) if (!curs.contains(x.currency)) curs.add(x.currency);
        sell = chips(sellHolder, curs, Ui.red, i -> picked(true, i), "");
        buy = chips(buyHolder, curs, Ui.green, i -> picked(false, i), "");
        Ui.rtl(sellHolder);
        Ui.rtl(buyHolder);
        quiet = true;
        sell.set(curs.indexOf(legs[0].currency));
        buy.set(curs.indexOf(legs[1].currency));
        dir.set(0);
        amount.setText(Model.plain(legs[0].amount, db.decimals(legs[0].currency)));
        counter.setText(Model.plain(legs[1].amount, db.decimals(legs[1].currency)));
        rate.setText(Model.nz(legs[0].fxRate));
        desc.setText(Model.nz(legs[0].description));
        quiet = false;
        // المبلغان كما سُجّلا: لا يُعاد حساب المقابل حتى يغيّر المحاسب شيئًا
        rateAuto = false;
        manual = true;
        fromBuy = false;
        labels();
        reference();
        calc();
    }

    void clearInputs() {
        quiet = true;
        amount.setText("");
        counter.setText("");
        rate.setText("");
        desc.setText("");
        quiet = false;
        manual = false;
        if (sell != null) sell.set(-1);
        if (buy != null) buy.set(-1);
    }

    @Override void clearForm() {
        setEditing(null);
        clearInputs();
        calc();
    }

    void officeChanged() {
        String ps = label(sell), pb = label(buy);
        curs.clear();
        Model.Office o = office.get();
        if (o != null) curs.addAll(db.officeCurrencies(o.id));
        List<String> show = curs.size() < 2 ? new ArrayList<>() : curs;
        String empty = o == null ? "اختر المكتب أولًا لتظهر عملاته" : "فعّل عملتين على الأقل لهذا المكتب (تعديل بيانات المكتب)";
        sell = chips(sellHolder, show, Ui.red, i -> picked(true, i), empty);
        buy = chips(buyHolder, show, Ui.green, i -> picked(false, i), empty);
        if (sell != null && ps != null && show.contains(ps)) sell.set(show.indexOf(ps));
        if (buy != null && pb != null && show.contains(pb) && !pb.equals(ps)) buy.set(show.indexOf(pb));
        Ui.rtl(sellHolder);
        Ui.rtl(buyHolder);
        labels();
        reference();
        calc();
    }

    /** عملة في طرف تمنع العملة نفسها في الطرف الآخر؛ ومع عملتين فقط يُختار الطرف الآخر تلقائيًا. */
    void picked(boolean isSell, int i) {
        Ui.Seg other = isSell ? buy : sell;
        if (other != null) {
            if (other.selected == i) other.set(-1);
            if (other.none() && curs.size() == 2) other.set(1 - i);
        }
        manual = false;
        labels();
        reference();
        calc();
    }

    /** سعر البرنامج بالاتجاه المختار: يُقترح في الخانة ما دام المحاسب لم يكتب سعره، ويبقى ظاهرًا دائمًا. */
    void reference() {
        if (dir == null) return;
        FxDeal x = deal();
        java.math.BigDecimal ref = FxDeal.referenceIn(FxDeal.reference(db.usdRates(), x.sell, x.buy), x.buyPerSell);
        if (ref == null) {
            refLine.setText(x.sell.isEmpty() || x.buy.isEmpty() ? " " : "لا يوجد سعر محفوظ في البرنامج لإحدى العملتين (الإعدادات ← العملات وأسعار الصرف).");
            useRef.setVisible(false);
            return;
        }
        java.util.Map<String, Db.Rate> rates = db.rates();
        String from = x.buyPerSell ? x.sell : x.buy, to = x.buyPerSell ? x.buy : x.sell;
        StringBuilder src = new StringBuilder();
        long at = 0;
        for (String c : new String[]{x.sell, x.buy}) {
            Db.Rate r = rates.get(c);
            if (r == null || "ثابت".equals(r.source)) continue;
            if (src.indexOf(r.source) < 0) src.append(src.length() > 0 ? "/" : "").append(r.source);
            if (r.at > 0 && (at == 0 || r.at < at)) at = r.at;
        }
        refLine.setText("سعر البرنامج للاستئناس: 1 " + from + " = " + ref.toPlainString() + " " + to + (src.length() > 0 ? " (" + src + (at > 0 ? "، " + Model.day(at) : "") + ")" : ""));
        useRef.setVisible(!rateAuto);
        if (rateAuto && !ref.toPlainString().equals(rate.getText().trim())) {
            quiet = true;
            rate.setText(ref.toPlainString());
            quiet = false;
            manual = false;
        }
    }

    FxDeal deal() {
        FxDeal x = new FxDeal();
        x.sell = Model.nz(label(sell));
        x.buy = Model.nz(label(buy));
        x.sellDec = x.sell.isEmpty() ? 2 : db.decimals(x.sell);
        x.buyDec = x.buy.isEmpty() ? 2 : db.decimals(x.buy);
        try {
            x.sellAmount = x.sell.isEmpty() ? 0 : Db.minor(amount.getText(), x.sellDec);
        } catch (Exception e) {
            x.sellAmount = 0;
        }
        x.rate = FxDeal.parseRate(rate.getText());
        x.buyPerSell = dir.selected != 1;
        return x;
    }

    void labels() {
        FxDeal x = deal();
        String u = x.sell.isEmpty() ? "المباعة" : x.sell, t = x.buy.isEmpty() ? "المشتراة" : x.buy;
        dir.buttons.get(0).setText("1 " + u + " = ؟ " + t);
        dir.buttons.get(1).setText("1 " + t + " = ؟ " + u);
    }

    void calc() {
        if (quiet || building || dir == null) return;
        FxDeal x = deal();
        meaning.setText(x.meaning().isEmpty() ? " " : "المعنى: " + x.meaning());
        if (fromBuy && !x.sell.isEmpty()) {
            Long sv = null;
            try {
                sv = x.sellAmountFor(Db.minor(counter.getText(), x.buyDec));
            } catch (Exception ignored) {
                // مبلغ غير مكتمل
            }
            String v = sv == null ? "" : Model.plain(sv, x.sellDec);
            if (!amount.getText().equals(v)) {
                quiet = true;
                amount.setText(v);
                quiet = false;
            }
            update();
            return;
        }
        if (!manual && !x.buy.isEmpty()) {
            Long b = x.buyAmount();
            String v = b == null ? "" : Model.plain(b, x.buyDec);
            if (!counter.getText().equals(v)) {
                quiet = true;
                counter.setText(v);
                quiet = false;
            }
        }
        update();
    }

    void update() {
        FxDeal x = deal();
        Model.Office o = office.get();
        List<String> l = new ArrayList<>();
        l.add(o == null ? "× المكتب: لم يُختر" : "المكتب: " + o.name);
        String sa = x.sell.isEmpty() ? null : amountText(amount, x.sell);
        l.add(x.sell.isEmpty() ? "× نبيع: اختر العملة المباعة" : sa == null ? "× نبيع: اكتب المبلغ" : "نبيع من رصيدنا: " + sa);
        String ba = x.buy.isEmpty() ? null : amountText(counter, x.buy);
        l.add(x.buy.isEmpty() ? "× نشتري: اختر العملة المشتراة" : ba == null ? "× نشتري: اكتب السعر أو المبلغ المقابل" : "نأخذ مقابلها: " + ba);
        if (sa != null && ba != null) {
            try {
                long ua = Db.minor(amount.getText(), x.sellDec), ta = Db.minor(counter.getText(), x.buyDec);
                String eff = FxDeal.effectiveRate(ua, x.sellDec, ta, x.buyDec);
                l.add("السعر الفعلي: 1 " + x.sell + " = " + eff + " " + x.buy);
                java.math.BigDecimal ref = FxDeal.reference(db.usdRates(), x.sell, x.buy);
                if (ref != null) {
                    java.math.BigDecimal pct = new java.math.BigDecimal(eff).subtract(ref).multiply(java.math.BigDecimal.valueOf(100)).divide(ref, 2, java.math.RoundingMode.HALF_UP);
                    l.add("مقارنة بسعر البرنامج: " + (pct.signum() == 0 ? "مطابق" : (pct.signum() > 0 ? "أعلى بـ " : "أقل بـ ") + pct.abs().toPlainString() + "٪") + " (1 " + x.sell + " = "
                            + ref.toPlainString() + " " + x.buy + ")");
                }
                if (o != null && editing != null) {
                    // تعديل: «قبل» هو الرصيد بعد عكس العملية القديمة
                    java.util.Map<String, long[]> pv = db.fxEditPreview(editing, x.sell, ua, x.buy, ta);
                    for (java.util.Map.Entry<String, long[]> e : pv.entrySet()) {
                        String[] r = FxDeal.row(e.getKey(), e.getValue()[0], e.getValue()[1] - e.getValue()[0], db.decimals(e.getKey()));
                        l.add("▤" + r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3]);
                    }
                    String warn = FxDeal.overdraft(x.sell, pv.get(x.sell)[0], ua, x.sellDec);
                    if (warn != null) l.add("! " + warn);
                } else if (o != null) {
                    // جدول المقاصة: الرصيد قبل ← أثر العملية ← الرصيد بعد (لنا/علينا من إشارة الرصيد النهائي)
                    for (Db.Preview p : db.preview(java.util.Arrays.asList(db.fxNetLegs(o.id, x.sell, ua, x.buy, ta, "", "", Clock.now())), null))
                        for (String c : p.before.keySet()) {
                            long bf = p.before.get(c), af = p.after.get(c);
                            String[] r = FxDeal.row(c, bf, af - bf, db.decimals(c));
                            l.add("▤" + r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3] + (p.pending ? " (بعد الاعتماد)" : ""));
                        }
                    String warn = FxDeal.overdraft(x.sell, db.balances(o.id).getOrDefault(x.sell, 0L), ua, x.sellDec);
                    if (warn != null) l.add("! " + warn);
                }
            } catch (Exception ignored) {
                // الملخص مساعد فقط
            }
        }
        l.add(editing != null ? "التاريخ: " + Model.date(editing[0].createdAt) + " (تاريخ العملية الأصلي)" : "التاريخ: اليوم " + Model.day(Clock.now()));
        render(l, (o == null ? "" : o.name + " • ") + "نبيع " + sa + " • نأخذ " + ba);
    }

    void swap() {
        fromBuy = false;
        FxDeal r = deal().reversed();
        if (r.sell.isEmpty() || r.buy.isEmpty()) {
            Ui.toast("اختر العملتين أولًا");
            return;
        }
        quiet = true;
        sell.set(curs.indexOf(r.sell));
        buy.set(curs.indexOf(r.buy));
        amount.setText(r.sellAmount > 0 ? Model.plain(r.sellAmount, r.sellDec) : "");
        dir.set(r.buyPerSell ? 0 : 1);
        quiet = false;
        manual = false;
        labels();
        reference();
        calc();
    }

    void save() {
        if (saving) return;
        if (!require(Auth.Perm.TX_ADD) || !require(Auth.Perm.TX_EDIT)) return;
        saving = true;
        try {
            Model.Office o = office.get();
            if (o == null) throw new IllegalArgumentException("اختر المكتب");
            FxDeal x = deal();
            if (x.sell.isEmpty() || x.buy.isEmpty()) throw new IllegalArgumentException("اختر العملة المباعة والعملة المشتراة");
            if (x.sell.equals(x.buy)) throw new IllegalArgumentException("اختر عملتين مختلفتين");
            long ua = x.sellAmount, ta = Db.minor(counter.getText(), x.buyDec);
            if (ua <= 0 || ta <= 0) throw new IllegalArgumentException("اكتب المبلغ والسعر (أو المبلغ المقابل)");
            String r = FxDeal.effectiveRate(ua, x.sellDec, ta, x.buyDec);
            if (editing != null) {
                saveEdit(o, x, ua, ta, r);
                return;
            }
            long when = Clock.now();
            String d = desc.val().isEmpty() ? FxDeal.describe(x.sell, Model.fmt(ua, x.sellDec), x.buy, Model.fmt(ta, x.buyDec)) : desc.val();
            List<Db.TxInput> legs = java.util.Arrays.asList(db.fxNetLegs(o.id, x.sell, ua, x.buy, ta, r, d, when));
            String rateText = "1 " + x.sell + " = " + r + " " + x.buy + " (الفعلي بعد التقريب)";
            // 10.8: تجاوز الرصيد مسموح بعد تأكيد صريح (لا يُحفظ قبله)
            String warn = FxDeal.overdraft(x.sell, db.balances(o.id).getOrDefault(x.sell, 0L), ua, x.sellDec);
            if (warn != null) {
                JOptionPane p = new JOptionPane(Ui.para(warn, 14, Ui.red, 440), JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                        new Object[]{"تأكيد المتابعة", "تعديل العملية"}, "تعديل العملية");
                Ui.show(this, p, "المبلغ يتجاوز رصيد " + x.sell);
                if (!"تأكيد المتابعة".equals(p.getValue())) return;
            }
            while (true) {
                TxConfirm.View ok = Ui.confirmTx(this, "تأكيد قص العملات", warn == null ? "" : warn, () -> TxConfirm.build(db, legs, null, rateText), "تسجيل");
                if (ok == null) return;
                if (TxConfirm.changed(db, ok, legs, null)) {
                    Ui.toast("تغيّر الرصيد لحظة الضغط؛ راجع التأكيد من جديد");
                    continue;
                }
                break;
            }
            db.addFxNet(o.id, x.sell, ua, x.buy, ta, r, d, when);
            db.setSettingQuiet("last_office", "" + o.id);
            app.changed();
            Ui.toast("✓ سُجّل قص العملات (مقاصة بين العملتين)");
            clearInputs();
            app.go("office", o.id);
        } catch (Exception e) {
            Ui.error(this, e);
        } finally {
            saving = false;
        }
    }

    /** 10.18: حفظ تعديل القص: تنبيه تجاوز الرصيد (محسوبًا بعد عكس القديمة)، ثم سبب التعديل، ثم Db.editFxNet في معاملة واحدة. */
    void saveEdit(Model.Office o, FxDeal x, long ua, long ta, String r) {
        Model.Tx[] old = editing;
        java.util.Map<String, long[]> pv = db.fxEditPreview(old, x.sell, ua, x.buy, ta);
        String warn = FxDeal.overdraft(x.sell, pv.get(x.sell)[0], ua, x.sellDec);
        StringBuilder msg = new StringBuilder();
        msg.append("كانت: نبيع ").append(Model.fmt(old[0].amount, db.decimals(old[0].currency))).append(" ").append(old[0].currency).append(" ونأخذ ")
                .append(Model.fmt(old[1].amount, db.decimals(old[1].currency))).append(" ").append(old[1].currency).append("\n");
        msg.append("تصير: نبيع ").append(Model.fmt(ua, x.sellDec)).append(" ").append(x.sell).append(" ونأخذ ").append(Model.fmt(ta, x.buyDec)).append(" ").append(x.buy)
                .append("\nالسعر الفعلي: 1 ").append(x.sell).append(" = ").append(r).append(" ").append(x.buy).append("\n");
        for (java.util.Map.Entry<String, long[]> e : pv.entrySet())
            msg.append("رصيد ").append(e.getKey()).append(" بعد التعديل: ").append(Model.bal(e.getValue()[1], db.decimals(e.getKey()))).append("\n");
        if (warn != null) msg.append("\n! ").append(warn).append("\n");
        msg.append("\nسبب التعديل (مطلوب)");
        String why = Ui.input(this, "تأكيد تعديل قص العملات", msg.toString(), "");
        if (why == null) return;
        if (why.trim().isEmpty()) {
            Ui.toast("سبب التعديل مطلوب");
            return;
        }
        String d = desc.val().isEmpty() ? FxDeal.describe(x.sell, Model.fmt(ua, x.sellDec), x.buy, Model.fmt(ta, x.buyDec)) : desc.val();
        db.editFxNet(old[0].id, x.sell, ua, x.buy, ta, r, d, old[0].createdAt, why);
        app.changed();
        Ui.toast("✓ عُدّل قص العملات (عُكست القديمة وسُجّلت المصحّحة)");
        setEditing(null);
        clearInputs();
        app.go("office", o.id);
    }

    /**
     * 10.18: مستمع فوري للحقول الثلاثة. TxView.doc يؤجل التنفيذ (invokeLater) فيُقرأ «quiet» بعد أن يعود false، فكان ما يكتبه البرنامج
     * يُعامَل كأن المحاسب كتبه: كتابة المبلغ المشترى تحسب المباع ثم يُعاد حساب المشترى فوق ما يُكتب. الآن يُقرأ «quiet» لحظة التغيير.
     */
    static javax.swing.event.DocumentListener now(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        };
    }
}
