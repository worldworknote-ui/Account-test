package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/**
 * 10.6: «تسجيل حركة بين مكتبين» بأسلوب «تسجيل حركة». 10.7 (الاتجاه الصحيح): المكتب طالب الحركة يطلب منا تسليم مبلغ فيصير «لنا» عليه،
 * والمكتب المنفّذ يسلّمه نيابة عنا فيصير «علينا» له — بالمبلغ والعملة نفسيهما، في عملية واحدة (Db.addTransfer).
 * الأجرة: أجرتنا على الطالب («لنا») وأجرة المنفّذ («علينا»)، والفرق ربحنا. لا حركة صندوق. المكتب نفسه في الطرفين مرفوض.
 */
final class TransferView extends RecordView {
    static final String[] FEE_KINDS = {Model.FEE_NONE, Model.FEE_US, Model.FEE_THEM};

    OfficeBox from, to;
    final JPanel curHolder = holder();
    Ui.Seg cur, feeMode, fromFee, toFee;
    final Ui.Field amount = Ui.numeric("0"), fromFeeAmt = Ui.numeric("مبلغ أجرة الطالب"), toFeeAmt = Ui.numeric("مبلغ أجرة المنفّذ");
    final Ui.Field person = Ui.field("اسم المستفيد (اختياري)"), desc = Ui.field("البيان (اختياري)");
    final JPanel feeBox = Ui.column();
    final List<String> common = new ArrayList<>();

    TransferView(App app) { super(app, "تسجيل حركة"); }

    @Override void build() {
        from = new OfficeBox("اختر المكتب طالب الحركة");
        to = new OfficeBox("اختر المكتب المنفّذ");
        Color gray = new Color(120, 120, 120);
        feeMode = new Ui.Seg(new String[]{"بدون أجرة", "مع أجرة"}, null, 0);
        fromFee = new Ui.Seg(FEE_KINDS, new Color[]{gray, Ui.green, Ui.red}, 0);
        toFee = new Ui.Seg(FEE_KINDS, new Color[]{gray, Ui.green, Ui.red}, 0);
        big(amount);
        amount.getAccessibleContext().setAccessibleName("المبلغ");
        JPanel form = Ui.column();
        form.add(kinds(app, RecordMenu.XFER, () -> from.get()));
        form.add(Ui.gap(12));
        form.add(Ui.para("مكتب يطلب منا تسليم مبلغ، وينفّذه مكتب آخر نيابة عنا: طالب الحركة يصير «لنا» عليه، والمنفّذ يصير «علينا» له بالمبلغ نفسه. لا يتحرك النقد في الصندوق. تُسجَّل الساقان وتُعتمدان وتُلغيان معًا.",
                12.5f, Ui.muted, 460));
        form.add(Ui.gap(10));
        Ui.Card fc = side("المكتب طالب الحركة", "طلب منا التسليم ← يصير «لنا» عليه", Ui.green, Ui.creditBg);
        fc.add(from);
        form.add(fc);
        form.add(Ui.gap(6));
        form.add(centered(swapButton("تبديل الطرفين", e -> swap())));
        form.add(Ui.gap(6));
        Ui.Card tc = side("المكتب المنفّذ", "سلّم المبلغ عنا ← يصير «علينا» له", Ui.red, Ui.debitBg);
        tc.add(to);
        form.add(tc);
        form.add(Ui.gap(14));
        form.add(Ui.labeled("المبلغ", amount));
        form.add(Ui.gap(10));
        form.add(Ui.labeled("العملة (المشتركة بين المكتبين)", curHolder));
        form.add(Ui.gap(12));
        form.add(Ui.labeled("المستفيد", person));
        form.add(Ui.gap(10));
        form.add(Ui.labeled("البيان", desc));
        form.add(Ui.gap(12));
        form.add(Ui.labeled("الأجرة", feeMode));
        feeBox.add(Ui.gap(8));
        feeBox.add(Ui.labeled("أجرتنا على طالب الحركة (عادةً «الأجرة لنا»)", fromFee));
        feeBox.add(Ui.gap(4));
        feeBox.add(fromFeeAmt);
        feeBox.add(Ui.gap(8));
        feeBox.add(Ui.labeled("أجرة المنفّذ (عادةً «الأجرة علينا»)", toFee));
        feeBox.add(Ui.gap(4));
        feeBox.add(toFeeAmt);
        feeBox.add(Ui.label("الأجرة بعملة الحركة نفسها. الفرق بين أجرتنا وأجرة المنفّذ ربحنا.", 12, Ui.muted, false));
        form.add(feeBox);
        columns(form, summaryColumn("تسجيل الحركة", this::save));

        from.onChange = () -> picked(from, to);
        to.onChange = () -> picked(to, from);
        // تفعيل الأجرة يقترح الحالة المعتادة: أجرتنا على الطالب «لنا»، وأجرة المنفّذ «علينا»
        feeMode.listener = i -> {
            if (i == 1 && fromFee.selected <= 0 && toFee.selected <= 0) {
                fromFee.set(1);
                toFee.set(2);
            }
            update();
        };
        fromFee.listener = i -> update();
        toFee.listener = i -> update();
        for (Ui.Field f : new Ui.Field[]{amount, fromFeeAmt, toFeeAmt, person}) f.getDocument().addDocumentListener(TxView.doc(this::update));
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(Ui.key(KeyEvent.VK_S), "save-xfer");
        getActionMap().put("save-xfer", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing()) save();
            }
        });
    }

    /** المكتب نفسه لا يكون في الطرفين: اختياره في الطرف الآخر يُرفض (يبقى هذا الطرف فارغًا) مع تنبيه. */
    void picked(OfficeBox a, OfficeBox b) {
        if (building) return;
        if (a.get() != null && b.get() != null && a.get().id == b.get().id) {
            Ui.toast("«" + a.get().name + "» مختار في الطرف الآخر؛ اختر مكتبًا آخر أو استعمل «تبديل الطرفين»");
            building = true;
            a.search.setText("");
            a.load(-1, false);
            building = false;
        }
        currencies();
    }

    @Override void refresh() {
        building = true;
        Model.Office f = from.get(), t = to.get();
        from.load(f == null ? db.settingLong("last_office", -1) : f.id, false);
        if (t != null && from.get() != null && t.id == from.get().id) t = null;
        to.load(t == null ? -1 : t.id, false);
        building = false;
        currencies();
        done();
    }

    @Override void open(Object arg) {
        if (arg instanceof Open) {
            clearInputs();
            building = true;
            from.search.setText("");
            to.search.setText("");
            from.load(((Open) arg).office, false);
            to.load(-1, false);
            building = false;
            currencies();
            done();
        } else refresh();
    }

    void clearInputs() {
        for (Ui.Field f : new Ui.Field[]{amount, fromFeeAmt, toFeeAmt, person, desc}) f.setText("");
        feeMode.set(0);
        fromFee.set(0);
        toFee.set(0);
    }

    @Override void clearForm() {
        clearInputs();
        update();
    }

    void swap() {
        Model.Office f = from.get(), t = to.get();
        building = true;
        from.search.setText("");
        to.search.setText("");
        from.load(t == null ? -1 : t.id, false);
        to.load(f == null ? -1 : f.id, false);
        building = false;
        currencies();
    }

    void currencies() {
        String keep = label(cur);
        common.clear();
        Model.Office f = from.get(), t = to.get();
        if (f != null && t != null) {
            List<String> y = db.officeCurrencies(t.id);
            for (String c : db.officeCurrencies(f.id)) if (y.contains(c)) common.add(c);
        }
        cur = chips(curHolder, common, Ui.primary, i -> update(), f == null || t == null ? "اختر المكتبين لتظهر عملاتهما المشتركة" : "لا عملة مشتركة مفعّلة للمكتبين");
        if (cur != null) {
            if (keep != null && common.contains(keep)) cur.set(common.indexOf(keep));
            else if (common.size() == 1) cur.set(0);
        }
        Ui.rtl(curHolder);
        from.showBalance();
        to.showBalance();
        update();
    }

    Db.TransferInput input() {
        Db.TransferInput in = new Db.TransferInput();
        if (from.get() == null || to.get() == null) throw new IllegalArgumentException("اختر المكتبين");
        in.from = from.get().id;
        in.to = to.get().id;
        String c = label(cur);
        if (c == null) throw new IllegalArgumentException(common.isEmpty() ? "لا عملة مشتركة مفعّلة للمكتبين" : "اختر العملة");
        in.currency = c;
        int d = db.decimals(c);
        in.amount = Db.minor(amount.getText(), d);
        in.person = person.val();
        in.description = desc.val();
        in.when = Clock.now();
        boolean fees = feeMode.selected == 1;
        in.fromFeeType = fees ? FEE_KINDS[Math.max(0, fromFee.selected)] : Model.FEE_NONE;
        in.toFeeType = fees ? FEE_KINDS[Math.max(0, toFee.selected)] : Model.FEE_NONE;
        if (!Model.FEE_NONE.equals(in.fromFeeType)) in.fromFee = Db.minor(fromFeeAmt.getText(), d);
        if (!Model.FEE_NONE.equals(in.toFeeType)) in.toFee = Db.minor(toFeeAmt.getText(), d);
        return in;
    }

    void update() {
        if (building || feeMode == null) return;
        boolean fees = feeMode.selected == 1;
        feeBox.setVisible(fees);
        fromFeeAmt.setVisible(fromFee.selected > 0);
        toFeeAmt.setVisible(toFee.selected > 0);
        Model.Office f = from.get(), t = to.get();
        String c = label(cur);
        String at = c == null ? null : amountText(amount, c);
        List<String> l = new ArrayList<>();
        l.add(f == null ? "× طالب الحركة: لم يُختر" : "طالب الحركة: " + f.name + " — «لنا» عليه");
        l.add(t == null ? "× المنفّذ: لم يُختر" : "المنفّذ: " + t.name + " — «علينا» له");
        if (f != null && t != null && common.isEmpty()) l.add("× العملة: لا عملة مشتركة مفعّلة للمكتبين");
        else if (c == null) l.add("× العملة: لم تُختر");
        l.add(amount.getText().trim().isEmpty() ? "× المبلغ: لم يُكتب" : at == null ? "× المبلغ غير صحيح: اكتب أرقامًا فقط" : "المبلغ: " + at);
        if (fees) {
            for (int k = 0; k < 2; k++) {
                Ui.Seg s = k == 0 ? fromFee : toFee;
                Ui.Field e = k == 0 ? fromFeeAmt : toFeeAmt;
                String who = k == 0 ? "أجرة الطالب" : "أجرة المنفّذ";
                if (s.selected <= 0) l.add(who + ": بدون");
                else {
                    String fa = c == null ? null : amountText(e, c);
                    l.add(fa == null ? "× " + who + ": اكتب المبلغ" : who + ": " + FEE_KINDS[s.selected].replace("الأجرة ", "") + " " + fa);
                }
            }
            // ربحنا = ما لنا من أجرة ناقص ما علينا منها
            try {
                long net = 0;
                int d = db.decimals(c);
                for (int k = 0; k < 2; k++) {
                    Ui.Seg s = k == 0 ? fromFee : toFee;
                    if (s.selected <= 0) continue;
                    long v = Db.minor((k == 0 ? fromFeeAmt : toFeeAmt).getText(), d);
                    net += Model.FEE_US.equals(FEE_KINDS[s.selected]) ? v : -v;
                }
                l.add("صافي الأجرة: " + (net >= 0 ? "ربح لنا " : "خسارة علينا ") + "\u2066" + Model.fmt(Math.abs(net), d) + " " + c + "\u2069");
            } catch (Exception ignored) {
                // الناقص ظاهر بالأحمر
            }
        } else l.add("الأجرة: بدون");
        String p = person.val();
        l.add("المستفيد: " + (p.isEmpty() ? "—" : p));
        if (at != null && f != null && t != null && f.id != t.id) {
            try {
                for (Db.Preview pv : db.preview(java.util.Arrays.asList(db.transferLegs(input())), null))
                    for (String k : pv.before.keySet())
                        l.add("«" + pv.officeName + "» " + k + ": " + Model.bal(pv.before.get(k), db.decimals(k)) + "  ←  " + Model.bal(pv.after.get(k), db.decimals(k))
                                + (pv.pending ? " (بعد الاعتماد)" : ""));
            } catch (Exception ignored) {
                // الناقص ظاهر بالأحمر
            }
        }
        l.add("التاريخ: اليوم " + Model.day(Clock.now()));
        render(l, (f == null ? "" : f.name) + " ← " + (t == null ? "" : t.name) + " • " + at);
        body.revalidate();
    }

    void save() {
        if (saving) return;
        if (!require(Auth.Perm.TX_ADD)) return;
        saving = true;
        try {
            Db.TransferInput in = input();
            if (in.amount <= 0) throw new IllegalArgumentException("اكتب المبلغ");
            List<Db.TxInput> legs = java.util.Arrays.asList(db.transferLegs(in));
            while (true) {
                TxConfirm.View ok = Ui.confirmTx(this, "تأكيد الحركة بين مكتبين", "", () -> TxConfirm.build(db, legs, null, null), "تسجيل الحركة");
                if (ok == null) return;
                if (TxConfirm.changed(db, ok, legs, null)) {
                    Ui.toast("تغيّر الرصيد لحظة الضغط؛ راجع التأكيد من جديد");
                    continue;
                }
                break;
            }
            long[] ids = db.addTransfer(in);
            app.changed();
            Model.Tx t = db.tx(ids[0]);
            Ui.toast(t != null && t.isPending() ? "سُجّلت الحركة بانتظار الاعتماد (الساقان معًا)" : "✓ سُجّلت الحركة بين المكتبين");
            clearInputs();
            app.go("office", in.from);
        } catch (Exception e) {
            Ui.error(this, e);
        } finally {
            saving = false;
        }
    }
}
