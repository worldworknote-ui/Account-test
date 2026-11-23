package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 10.16: مصمم رسالة مطابقة الرصيد على الكمبيوتر (كان على الهاتف وحده): كل نصوص القالب، شكل سطر العملة، ما يظهر وما يُخفى،
 * مع معاينة حية. القالب نفسه (مفاتيح rc_*) فيُنقل بين الأجهزة.
 */
final class ReconDesign {
    private ReconDesign() {}

    static void open(Component parent, Db db, Runnable after) {
        final Reconcile.Template t = Reconcile.Template.from(db.settings(Reconcile.Template.KEYS));
        final Ui.Field greet = f(t.greeting), ttl = f(t.title), orn = f(t.ornament), sal = f(t.salutation), hon = f(t.honorific), foot = f(t.footer),
                plus = f(t.plusWord), minus = f(t.minusWord), zeroWord = f(t.zeroWord), line = f(t.lineFormat), mPlus = f(t.markPlus), mMinus = f(t.markMinus),
                mZero = f(t.markZero), dateLabel = f(t.dateLabel), officeMark = f(t.officeMark), sep = f(t.separator), empty = f(t.emptyText);
        final JCheckBox bold = Ui.check("خط عريض للمبالغ والعنوان", t.bold), boldName = Ui.check("خط عريض لاسم العملة أيضًا", t.boldName),
                issuer = Ui.check("إظهار اسم مكتبك في آخر الرسالة", t.showIssuer), code = Ui.check("رمز العملة بجانب اسمها (USD…)", t.showCode),
                zero = Ui.check("تحديد العملات ذات الرصيد الصفري افتراضيًا", t.showZero), trim = Ui.check("حذف الأصفار العشرية (59,450 بدل 59,450.00)", t.trimZeros),
                gap = Ui.check("سطر فارغ بين كل عملة وأخرى", t.gap), showDate = Ui.check("إظهار سطر التاريخ", t.showDate),
                showOffice = Ui.check("إظهار سطر اسم المكتب", t.showOffice), showSep = Ui.check("إظهار الخط الفاصل", t.showSeparator);
        String[] names = new String[Reconcile.LINE_PRESETS.length + 1];
        names[0] = "شكل جاهز…";
        for (int i = 0; i < Reconcile.LINE_PRESETS.length; i++) names[i + 1] = Reconcile.LINE_PRESETS[i][0];
        final JComboBox<String> preset = Ui.combo(names);
        // 10.16: موضع «لنا/لكم» من الرقم، ولونهما
        final JComboBox<String> pos = Ui.combo(Reconcile.WORD_POS);
        pos.setSelectedIndex(Reconcile.wordPos(t.lineFormat));
        String[] cn = new String[Reconcile.COLORS.length];
        for (int i = 0; i < cn.length; i++) cn[i] = Reconcile.COLORS[i][2] + "  " + Reconcile.COLORS[i][0];
        final JComboBox<String> cPlus = Ui.combo(cn), cMinus = Ui.combo(cn);
        cPlus.setSelectedIndex(Math.max(0, Reconcile.colorIndex(t.colorPlus)));
        cMinus.setSelectedIndex(Reconcile.colorIndex(t.colorMinus) < 0 ? 1 : Reconcile.colorIndex(t.colorMinus));
        final JCheckBox colored = Ui.check("تلوين «لنا» و«لكم»", t.colored);

        JPanel g = new JPanel(new GridLayout(0, 2, 12, 6));
        g.setOpaque(false);
        g.add(Ui.labeled("التحية", greet));
        g.add(Ui.labeled("العنوان", ttl));
        g.add(Ui.labeled("زخرفة حول العنوان", orn));
        g.add(Ui.labeled("الخاتمة", foot));
        g.add(Ui.labeled("قبل اسم المكتب", sal));
        g.add(Ui.labeled("بعد اسم المكتب", hon));
        g.add(Ui.labeled("كلمة الرصيد لنا", plus));
        g.add(Ui.labeled("كلمة الرصيد علينا", minus));
        g.add(Ui.labeled("كلمة الرصيد الصفري", zeroWord));
        g.add(Ui.labeled("النص عند عدم وجود أرصدة", empty));
        g.add(Ui.labeled("قبل التاريخ", dateLabel));
        g.add(Ui.labeled("قبل المكتب", officeMark));
        g.add(Ui.labeled("علامة «لنا»", mPlus));
        g.add(Ui.labeled("علامة «لكم»", mMinus));
        g.add(Ui.labeled("علامة الصفر", mZero));
        g.add(Ui.labeled("الخط الفاصل", sep));

        JPanel left = Ui.column();
        left.add(g);
        left.add(Ui.gap(8));
        left.add(Ui.label("شكل سطر العملة", 14, Ui.primaryInk, true));
        left.add(Ui.para(Reconcile.LINE_HELP, 12.5f, Ui.muted, 520));
        left.add(Ui.row(line, preset));
        left.add(Ui.gap(6));
        JPanel pg = new JPanel(new GridLayout(0, 3, 12, 6));
        pg.setOpaque(false);
        pg.add(Ui.labeled("موضع «لنا / لكم»", pos));
        pg.add(Ui.labeled("لون «لنا»", cPlus));
        pg.add(Ui.labeled("لون «لكم»", cMinus));
        left.add(pg);
        left.add(Ui.para("واتساب لا يلوّن النص: اللون فيه هو الدائرة قبل السطر، وفي المعاينة هنا وفي صورة المطابقة على الهاتف يُلوَّن الرقم والكلمة.", 12.5f, Ui.muted, 520));
        left.add(Ui.gap(4));
        JPanel cg = new JPanel(new GridLayout(0, 2, 12, 2));
        cg.setOpaque(false);
        final JCheckBox[] cks = {trim, gap, bold, boldName, showDate, showOffice, showSep, issuer, code, zero, colored};
        for (JCheckBox c : cks) cg.add(c);
        left.add(cg);

        final JTextPane preview = new JTextPane();
        preview.setEditable(false);
        preview.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        preview.setBackground(Ui.surface);
        preview.setForeground(Ui.ink);
        preview.applyComponentOrientation(Ui.RTL);
        JScrollPane ps = new JScrollPane(preview);
        ps.setPreferredSize(new Dimension(330, 430));
        JPanel right = Ui.column();
        right.add(Ui.label("المعاينة", 14, Ui.primaryInk, true));
        right.add(ps);
        right.add(Ui.gap(6));
        final Runnable[] readRef = new Runnable[1];
        right.add(Ui.flow(Ui.ghost("معاينة صورة المطابقة", e -> {
            if (readRef[0] != null) readRef[0].run();
            java.awt.image.BufferedImage im = ReconImage.render(t, "كاش شام", db.setting("office_name", ""), Clock.now(), Reconcile.sampleRows(), Ui.primary);
            Image sc = im.getScaledInstance(480, -1, Image.SCALE_SMOOTH);
            JScrollPane isp = new JScrollPane(new JLabel(new ImageIcon(sc)));
            isp.setPreferredSize(new Dimension(510, Math.min(640, im.getHeight() * 480 / im.getWidth() + 20)));
            JOptionPane.showMessageDialog(Ui.win(parent), isp, "صورة المطابقة", JOptionPane.PLAIN_MESSAGE);
        })));

        final Runnable read = () -> {
            t.greeting = greet.val();
            t.title = ttl.val().isEmpty() ? "مطابقة رصيد" : ttl.val();
            t.ornament = orn.val();
            t.salutation = sal.val();
            t.honorific = hon.val();
            t.footer = foot.val();
            t.plusWord = plus.val().isEmpty() ? "لنا" : plus.val();
            t.minusWord = minus.val().isEmpty() ? "لكم" : minus.val();
            t.zeroWord = zeroWord.val();
            t.lineFormat = Reconcile.validLine(line.getText());
            t.markPlus = mPlus.val();
            t.markMinus = mMinus.val();
            t.markZero = mZero.val();
            t.dateLabel = dateLabel.val();
            t.officeMark = officeMark.val();
            t.separator = sep.val();
            t.emptyText = empty.val().isEmpty() ? new Reconcile.Template().emptyText : empty.val();
            t.bold = bold.isSelected();
            t.boldName = boldName.isSelected();
            t.showIssuer = issuer.isSelected();
            t.showCode = code.isSelected();
            t.showZero = zero.isSelected();
            t.trimZeros = trim.isSelected();
            t.colored = colored.isSelected();
            t.colorPlus = Reconcile.COLORS[cPlus.getSelectedIndex()][1];
            t.colorMinus = Reconcile.COLORS[cMinus.getSelectedIndex()][1];
            t.gap = gap.isSelected();
            t.showDate = showDate.isSelected();
            t.showOffice = showOffice.isSelected();
            t.showSeparator = showSep.isSelected();
        };
        readRef[0] = read;
        final Runnable upd = () -> {
            read.run();
            // المعاينة: النص كما يُرسل، مع تلوين أسطر العملات بلوني «لنا» و«لكم» (للإيضاح؛ واتساب يعرض الدائرة الملوّنة)
            java.util.List<Reconcile.Row> rows = Reconcile.sampleRows();
            String txt = Reconcile.text(t, "كاش شام", db.setting("office_name", ""), Clock.now(), rows);
            preview.setText(txt);
            javax.swing.text.StyledDocument doc = preview.getStyledDocument();
            javax.swing.text.SimpleAttributeSet base = new javax.swing.text.SimpleAttributeSet();
            javax.swing.text.StyleConstants.setForeground(base, Ui.ink);
            javax.swing.text.StyleConstants.setAlignment(base, javax.swing.text.StyleConstants.ALIGN_RIGHT);
            doc.setParagraphAttributes(0, doc.getLength(), base, false);
            doc.setCharacterAttributes(0, doc.getLength(), base, true);
            if (t.colored) for (Reconcile.Row r : rows) {
                String ln = Reconcile.line(t, r);
                int at = txt.indexOf(ln);
                if (at < 0) continue;
                javax.swing.text.SimpleAttributeSet cs = new javax.swing.text.SimpleAttributeSet();
                javax.swing.text.StyleConstants.setForeground(cs, new Color(t.color(r.sign()) & 0xFFFFFF));
                javax.swing.text.StyleConstants.setBold(cs, true);
                doc.setCharacterAttributes(at, ln.length(), cs, false);
            }
            preview.setCaretPosition(0);
        };
        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { upd.run(); }

            @Override public void removeUpdate(DocumentEvent e) { upd.run(); }

            @Override public void changedUpdate(DocumentEvent e) { upd.run(); }
        };
        for (Ui.Field x : new Ui.Field[]{greet, ttl, orn, sal, hon, foot, plus, minus, zeroWord, line, mPlus, mMinus, mZero, dateLabel, officeMark, sep, empty})
            x.getDocument().addDocumentListener(dl);
        for (JCheckBox c : cks) c.addActionListener(e -> upd.run());
        pos.addActionListener(e -> line.setText(Reconcile.withWordPos(line.getText(), pos.getSelectedIndex())));
        cPlus.addActionListener(e -> mPlus.setText(Reconcile.COLORS[cPlus.getSelectedIndex()][2]));
        cMinus.addActionListener(e -> mMinus.setText(Reconcile.COLORS[cMinus.getSelectedIndex()][2]));
        preset.addActionListener(e -> {
            int i = preset.getSelectedIndex();
            if (i > 0) line.setText(Reconcile.LINE_PRESETS[i - 1][1]);
        });
        upd.run();

        JPanel all = new JPanel(new BorderLayout(16, 0));
        all.setOpaque(false);
        all.add(left, BorderLayout.CENTER);
        all.add(right, BorderLayout.LINE_END);
        JOptionPane p = new JOptionPane(Ui.rtl(all), JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"حفظ", "القالب الافتراضي", "إلغاء"}, "حفظ");
        Ui.show(parent, p, "تصميم رسالة مطابقة الرصيد");
        Object v = p.getValue();
        if ("حفظ".equals(v)) {
            read.run();
            if (!line.getText().contains("{مبلغ}")) Ui.info(parent, "شكل السطر", "شكل السطر لا يحوي الخانة {مبلغ}؛ حُفظ بالشكل الافتراضي (المبلغ ثم العملة).");
            save(db, t);
            Ui.toast("حُفظ تصميم الرسالة");
        } else if ("القالب الافتراضي".equals(v)) {
            if (!Ui.confirm(parent, "القالب الافتراضي", "يعود نص الرسالة وشكلها إلى الصيغة الافتراضية.", "استعادة")) return;
            save(db, new Reconcile.Template());
            Ui.toast("عاد القالب الافتراضي");
        } else return;
        if (after != null) after.run();
    }

    private static void save(Db db, Reconcile.Template t) {
        for (Map.Entry<String, String> x : t.toMap().entrySet()) db.setSettingQuiet(x.getKey(), x.getValue());
        db.audit("SETTING", "settings", 0, "recon_template");
    }

    private static Ui.Field f(String v) {
        Ui.Field x = Ui.field("");
        // خط النظام المنطقي: يعرض الرموز التعبيرية (🟢 🌹) التي لا يحويها خط البرنامج
        x.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        x.setText(v);
        return x;
    }
}
