package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 10.5 (§11): محتوى نافذة تأكيد الحركة — مشترك بين الهاتف والكمبيوتر. الأرصدة من {@link Db#preview} (قواعد الترحيل نفسها)،
 * فلا تحسب الواجهة رصيدًا بنفسها. يشمل: الحركة العادية، والتعديل (الفرق الصحيح)، والتبديل (عملتان)، والتحويل (مكتبان)،
 * والحركة التي تحتاج اعتمادًا («الرصيد المتوقع بعد اعتماد الحركة»).
 */
public final class TxConfirm {
    private TxConfirm() {}

    /** نوع السطر: المكتب يُعرض بالأحمر مع كلمة «المكتب:» (لا يعتمد على اللون وحده). */
    public static final int OFFICE = 1, NORMAL = 0, BALANCE = 2, AFTER = 3, NOTE = 4;

    public static final class Row {
        public final int kind;
        public final String label, value;

        Row(int kind, String label, String value) {
            this.kind = kind;
            this.label = label;
            this.value = value;
        }

        @Override public String toString() { return label.isEmpty() ? value : label + ": " + value; }
    }

    /** ما يُعرض، وبصمة الأرصدة التي بُني عليها (للتحقق قبل الحفظ أنها لم تتغير). */
    public static final class View {
        public final List<Row> rows = new ArrayList<>();
        public String fingerprint = "";
        public boolean pending;

        public String text() {
            StringBuilder b = new StringBuilder();
            for (Row r : rows) {
                if (b.length() > 0) b.append('\n');
                b.append(r.kind == OFFICE ? "■ " : r.kind == BALANCE || r.kind == AFTER ? "   " : "").append(r);
            }
            return b.toString();
        }
    }

    /**
     * يبني التأكيد لساق أو أكثر. replacing: رقم الحركة التي تُستبدل عند التعديل (أو null). rateText: سعر التبديل للعرض (أو null).
     */
    public static View build(Db db, List<Db.TxInput> legs, Long replacing, String rateText) {
        View v = new View();
        List<Db.Preview> pv = db.preview(legs, replacing);
        StringBuilder fp = new StringBuilder();
        boolean transfer = legs.size() == 2 && legs.get(0).office != legs.get(1).office;
        boolean fx = legs.size() == 2 && !transfer;
        if (transfer) v.rows.add(new Row(NORMAL, "العملية", "تحويل بين مكتبين — ساقان تُسجَّلان وتُلغيان وتُعتمدان معًا"));
        else if (fx) v.rows.add(new Row(NORMAL, "العملية", "تبديل عملة مع المكتب — ساقان مرتبطتان"));
        else if (replacing != null) v.rows.add(new Row(NORMAL, "العملية", "تعديل: يُلغى الأصل بقيد عكسي ويُسجَّل البديل"));
        for (Db.Preview p : pv) {
            v.pending |= p.pending;
            v.rows.add(new Row(OFFICE, "المكتب", "«" + p.officeName + "»"));
            for (Db.TxInput x : legs) {
                if (x.office != p.office) continue;
                boolean us = Model.TYPE_DEBIT.equals(x.type);
                int d = db.decimals(x.currency);
                v.rows.add(new Row(NORMAL, "النوع", us ? "لنا (مدين)" : "علينا (دائن)"));
                // 12.6: المعدن بوزنه ووحدته («1.500 غ ذهب عيار 21») لا برمزه
                if (Metals.isMetal(x.currency)) v.rows.add(new Row(NORMAL, "الوزن", Model.fmt(x.amount, d) + " " + Metals.unitText(x.currency)));
                else v.rows.add(new Row(NORMAL, "المبلغ", "\u2066" + Model.fmt(x.amount, d) + " " + x.currency + "\u2069"));
                if (!Model.FEE_NONE.equals(x.feeType) && x.fee > 0)
                    v.rows.add(new Row(NORMAL, x.feeType, Metals.isMetal(x.feeCurrency) ? Model.fmt(x.fee, db.decimals(x.feeCurrency)) + " " + Metals.unitText(x.feeCurrency)
                            : "\u2066" + Model.fmt(x.fee, db.decimals(x.feeCurrency)) + " " + x.feeCurrency + "\u2069"));
                else v.rows.add(new Row(NORMAL, "الأجرة", "بدون أجرة"));
                if (!Model.nz(x.person).isEmpty()) v.rows.add(new Row(NORMAL, "المستفيد", x.person));
            }
            for (Map.Entry<String, Long> e : p.before.entrySet()) {
                String cur = e.getKey();
                int d = db.decimals(cur);
                long b = e.getValue(), a = p.after.get(cur);
                v.rows.add(new Row(BALANCE, "الرصيد الحالي (" + cur + ")", Model.bal(b, d)));
                v.rows.add(new Row(AFTER, (p.pending ? "الرصيد المتوقع بعد اعتماد الحركة" : "الرصيد بعد هذه الحركة سيصبح") + " (" + cur + ")", Model.bal(a, d)));
                if (replacing != null) v.rows.add(new Row(NOTE, "فرق التعديل (" + cur + ")", (a - b >= 0 ? "+" : "−") + Model.fmt(Math.abs(a - b), d)));
                fp.append(p.office).append(':').append(cur).append('=').append(b).append(';');
            }
        }
        if (rateText != null && !rateText.isEmpty()) v.rows.add(new Row(NORMAL, "السعر", rateText));
        if (v.pending) v.rows.add(new Row(NOTE, "", "تحتاج هذه الحركة اعتماد المحاسب: لا يتغير الرصيد قبل اعتمادها."));
        Db.TxInput first = legs.get(0);
        if (first.when > 0 && Model.day(first.when).compareTo(Model.day(Clock.now())) != 0)
            v.rows.add(new Row(NOTE, "التاريخ", Model.day(first.when)));
        v.fingerprint = fp.toString();
        return v;
    }

    public static View build(Db db, Db.TxInput in, Long replacing) {
        List<Db.TxInput> l = new ArrayList<>();
        l.add(in);
        return build(db, l, replacing, null);
    }

    /** هل تغيّرت الأرصدة منذ بُني التأكيد؟ (حركة وصلت بالمزامنة أو من شاشة أخرى) */
    public static boolean changed(Db db, View shown, List<Db.TxInput> legs, Long replacing) {
        return !build(db, legs, replacing, null).fingerprint.equals(shown.fingerprint);
    }
}
