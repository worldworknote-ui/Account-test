package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * تسجيل حركة (أو تعديلها): الصق رسالة واتساب (أو عدة رسائل) فتُستخرج المعلومات (المستفيد، المبلغ، العملة، المدينة، «راجع»)
 * وتُضاف للحقول بزر، ثم اختيار «لنا/علينا» إلزامي، والأجرة بمبلغ أو نسبة، وملخص حي، وتنبيهات قبل الحفظ (مبلغ غير معتاد،
 * تكرار، تجاوز حد، رسالة مسجّلة سابقًا)، واعتماد المحاسب لحركات الموظف فوق الحد. الحساب والقيود في Db.addTx كالهاتف تمامًا.
 */
final class TxView extends View {
    /** فتح النموذج على مكتب محدد. */
    static final class Prefill {
        final long office;

        Prefill(long office) { this.office = office; }
    }

    /** فتح النموذج لتعديل حركة. */
    static final class Edit {
        final long txId;
        /** 10.2: تصحيح حركة معلّقة ثم اعتمادها. */
        final boolean approve;

        Edit(long txId) { this(txId, false); }

        Edit(long txId, boolean approve) {
            this.txId = txId;
            this.approve = approve;
        }
    }

    boolean approving;
    /** 10.10: «تفاصيل إضافية» (الخدمة، الوجهة، ملاحظات، التاريخ، الصور). */
    final JPanel more = Ui.column();
    Ui.Btn moreToggle;

    void showMore(boolean open) {
        if (more.isVisible() != open) {
            more.setVisible(open);
            body.revalidate();
        }
        List<String> used = new ArrayList<>();
        if (!service.getEditor().getItem().toString().trim().isEmpty()) used.add("الخدمة");
        if (!dest.val().isEmpty()) used.add("الوجهة");
        if (!notes.val().isEmpty()) used.add("ملاحظات");
        try {
            long d0 = date.start();
            if (d0 > 0 && !Model.day(d0).equals(Model.day(Clock.now()))) used.add("التاريخ");
        } catch (Exception ignored) {
            // تاريخ غير مكتمل
        }
        if (!imageData.isEmpty()) used.add("الصور");
        if (moreToggle != null) moreToggle.setText((open ? "-  " : "+  ") + "تفاصيل إضافية" + (used.isEmpty() ? ": الخدمة، الوجهة، ملاحظات، التاريخ، الصور" : " — " + String.join("، ", used)));
    }
    /** 10.7: أزرار نوع الحركة (تُخفى عند التعديل والتصحيح). */
    Ui.Seg kinds;

    // الحقول
    final Ui.Field officeSearch = Ui.field("ابحث عن المكتب…");
    final JPanel recentOffices = Ui.flow(), rateChips = Ui.flow();

    /** 10.16: أزرار آخر المكاتب التي سُجّلت لها حركات على هذا الجهاز (حتى 6). */
    void fillRecentOffices() {
        recentOffices.removeAll();
        int n = 0;
        for (String x : db.setting("recent_offices", "").split(",")) {
            if (x.trim().isEmpty() || n >= 6) continue;
            try {
                final Model.Office o = db.office(Long.parseLong(x.trim()));
                if (o == null || !o.active) continue;
                Ui.Btn b = Ui.ghost(o.name, e -> selectOffice(o.id));
                b.setFont(Ui.font(12.5f, false));
                recentOffices.add(b);
                n++;
            } catch (NumberFormatException ignored) {
                // قيمة قديمة غير رقمية
            }
        }
        recentOffices.setVisible(n > 0 && editing == null);
        recentOffices.revalidate();
    }

    void rememberOffice(long id) {
        List<String> l = new ArrayList<>();
        l.add("" + id);
        for (String x : db.setting("recent_offices", "").split(",")) if (!x.trim().isEmpty() && !x.trim().equals("" + id) && l.size() < 8) l.add(x.trim());
        db.setSettingQuiet("recent_offices", String.join(",", l));
    }

    /** 10.16: آخر النسب المستعملة («1|100,1.5|1000» كالهاتف) أزرارًا تملأ النسبة ونوعها. */
    void fillRateChips() {
        rateChips.removeAll();
        for (String item : db.setting("fee_rates_recent", "").split(",")) {
            String[] p = item.split("\\|");
            if (p.length != 2) continue;
            final java.math.BigDecimal r = Model.parseRate(p[0]);
            if (r == null) continue;
            final int per = "1000".equals(p[1]) ? Model.PER_MILLE : Model.PER_CENT;
            Ui.Btn b = Ui.ghost(Model.rateLabel(r, per), e -> {
                ratePer.set(per == Model.PER_MILLE ? 1 : 0);
                rate.setText(r.stripTrailingZeros().toPlainString());
                recalcFee();
            });
            b.setFont(Ui.font(12.5f, false));
            rateChips.add(b);
        }
        rateChips.setVisible(rateChips.getComponentCount() > 0);
        rateChips.revalidate();
    }

    void rememberRate() {
        if (feeKind.selected != 1) return;
        java.math.BigDecimal r = Model.parseRate(rate.getText());
        if (r == null) return;
        String key = r.stripTrailingZeros().toPlainString() + "|" + per();
        List<String> l = new ArrayList<>();
        l.add(key);
        for (String item : db.setting("fee_rates_recent", "").split(",")) if (!item.isEmpty() && !item.equals(key) && l.size() < 4) l.add(item);
        db.setSettingQuiet("fee_rates_recent", String.join(",", l));
    }
    final JComboBox<Model.Office> office = Ui.pickCombo();
    Ui.Seg type;
    final Ui.Field amount = Ui.numeric("المبلغ");
    final JComboBox<String> currency = Ui.combo(new String[0]);
    Ui.Seg feeMode, feeSide, feeKind, ratePer;
    final Ui.Field fee = Ui.numeric("مبلغ الأجرة"), rate = Ui.numeric("النسبة");
    final JComboBox<String> feeCur = Ui.combo(new String[0]);
    final JLabel rateResult = Ui.label("", 13, Ui.muted, false);
    final Ui.Field person = Ui.field("اسم المستفيد");
    final JTextArea desc = Ui.area(6);
    final JComboBox<String> service = Ui.combo(new String[0]);
    final Ui.Field dest = Ui.field("المدينة أو البلد");
    final Ui.Field notes = Ui.field("ملاحظات داخلية (لا تظهر في الكشف إلا إن اخترت)");
    final Ui.DateField date = new Ui.DateField(0);
    final JPanel feeBox = Ui.column(), rateBox = Ui.column(), images = Ui.flow();
    final List<byte[]> imageData = new ArrayList<>();
    final JPanel parseBox = Ui.column();
    final JLabel summary = Ui.label("", 14.5f, Ui.ink, true);
    final JLabel queueLabel = Ui.label("", 13, Ui.goldInk, true);
    /** 10.5 (§5): المكتب المثبّت لرسائل واتساب (بالأحمر مع كلمة «المكتب»). */
    final JLabel pinLabel = new JLabel();
    Ui.Btn save;

    // الحالة
    Model.Tx editing;
    MessageParser.Result parsed;
    boolean filled;
    Model.Tx duplicate;
    List<WaText.Msg> queue = new ArrayList<>();
    int queueAt;
    javax.swing.Timer parseTimer;
    Map<String, Model.Tx> prints;
    long printsAt;
    boolean building;

    TxView(App app) {
        super(app, "تسجيل حركة");
    }

    @Override void build() {
        type = new Ui.Seg(new String[]{"لنا (مدين)", "علينا (دائن)"}, new Color[]{Ui.green, Ui.red}, -1);
        feeMode = new Ui.Seg(new String[]{"بدون أجرة", "مع أجرة"}, null, 0);
        feeSide = new Ui.Seg(new String[]{"الأجرة لنا", "الأجرة علينا"}, new Color[]{Ui.green, Ui.red}, 0);
        feeKind = new Ui.Seg(new String[]{"مبلغ محدد", "نسبة من المبلغ"}, null, 0);
        ratePer = new Ui.Seg(new String[]{"٪ بالمئة", "‰ بالألف"}, null, "1000".equals(db.setting("fee_per_last", "100")) ? 1 : 0);
        office.setFont(Ui.font(14, false));
        office.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        office.setAlignmentX(Component.RIGHT_ALIGNMENT);
        office.setComponentOrientation(Ui.RTL);
        office.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v instanceof Model.Office ? ((Model.Office) v).name : "— اختر المكتب —", i, s, f);
                c.setHorizontalAlignment(SwingConstants.RIGHT);
                c.setFont(Ui.font(14, false));
                return c;
            }
        });
        service.setEditable(true);

        JPanel form = Ui.column();
        // 10.7: نوع الحركة فوق المكتب (عادية، قص عملات، بين مكتبين)
        kinds = RecordView.kinds(app, RecordMenu.TX, () -> (Model.Office) office.getSelectedItem());
        form.add(kinds);
        form.add(Ui.gap(12));
        form.add(Ui.labeled("المكتب", Ui.row(office, officeSearch)));
        form.add(recentOffices);   // 10.16: آخر المكاتب المستعملة للاختيار السريع
        officeSearch.setMaximumSize(new Dimension(220, 40));
        form.add(Ui.gap(12));
        form.add(Ui.labeled("الاتجاه (إلزامي)", type));
        form.add(Ui.gap(12));
        JPanel amt = Ui.row(amount, currency);
        currency.setMaximumSize(new Dimension(170, 40));
        form.add(Ui.labeled("المبلغ والعملة", amt));
        form.add(Ui.gap(12));
        form.add(Ui.labeled("الأجرة", feeMode));
        feeBox.add(Ui.gap(8));
        feeBox.add(feeSide);
        feeBox.add(Ui.gap(8));
        feeBox.add(feeKind);
        feeBox.add(Ui.gap(8));
        rateBox.add(Ui.row(rate, ratePer));
        rateBox.add(rateChips);   // 10.16: آخر النسب المستعملة
        rate.setMaximumSize(new Dimension(140, 40));
        rateBox.add(rateResult);
        feeBox.add(rateBox);
        feeBox.add(Ui.gap(6));
        feeBox.add(Ui.row(fee, feeCur));
        feeCur.setMaximumSize(new Dimension(170, 40));
        form.add(feeBox);
        form.add(Ui.gap(12));
        form.add(Ui.labeled("المستفيد", person));
        form.add(Ui.gap(12));
        // 10.10: الحقول الاختيارية في «تفاصيل إضافية» (كل الميزات باقية، والنموذج أبسط). تُفتح تلقائيًا إن كان فيها شيء
        JPanel sd = new JPanel(new GridLayout(1, 2, 10, 0));
        sd.setOpaque(false);
        sd.setAlignmentX(Component.RIGHT_ALIGNMENT);
        sd.add(Ui.labeled("الخدمة (اعتماد، سويفت، USDT…)", service));
        sd.add(Ui.labeled("الوجهة", dest));
        more.add(sd);
        more.add(Ui.gap(12));
        more.add(Ui.labeled("ملاحظات", notes));
        more.add(Ui.gap(12));
        more.add(Ui.labeled("التاريخ", date));
        more.add(Ui.gap(12));
        more.add(Ui.labeled("صور الوصل (اختياري)", Ui.row(Ui.ghost("إرفاق صورة…", e -> attach()), images)));
        moreToggle = Ui.ghost("", e -> showMore(!more.isVisible()));
        form.add(Ui.flow(moreToggle));
        form.add(Ui.gap(6));
        form.add(more);
        showMore(false);

        JPanel right = Ui.column();
        Ui.Card fc = Ui.card();
        fc.add(form);
        right.add(fc);

        JPanel left = Ui.column();
        Ui.Card mc = Ui.card();
        mc.add(Ui.label("الرسالة / البيان", 15, Ui.primaryInk, true));
        mc.add(Ui.label("الصق رسالة واتساب (أو عدة رسائل منسوخة معًا) فتُستخرج المعلومات.", 12.5f, Ui.muted, false));
        mc.add(Ui.gap(6));
        JScrollPane ds = Ui.areaBox(desc);
        ds.setPreferredSize(new Dimension(380, 170));
        ds.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        mc.add(ds);
        mc.add(Ui.gap(6));
        mc.add(Ui.flow(Ui.ghost("لصق من الحافظة", e -> pasteClipboard()), Ui.ghost("مسح", e -> {
            desc.setText("");
            queue.clear();
            updateQueue();
        })));
        mc.add(queueLabel);
        mc.add(pinLabel);
        mc.add(parseBox);
        left.add(mc);
        left.add(Ui.gap(12));
        Ui.Card sc = Ui.tinted(Ui.primarySoft, null);
        sc.add(Ui.label("الملخص", 15, Ui.primaryInk, true));
        sc.add(Ui.gap(4));
        sc.add(summary);
        sc.add(Ui.gap(10));
        save = Ui.holdToConfirm(Ui.primary("تسجيل الحركة", e -> save()));
        save.setFont(Ui.font(15, true));
        sc.add(Ui.flow(save, Ui.ghost("تفريغ النموذج", e -> reset(true))));
        left.add(sc);

        JPanel cols = new JPanel(new GridLayout(1, 2, 18, 0));
        cols.setOpaque(false);
        cols.add(right);
        JPanel lw = new JPanel(new BorderLayout());
        lw.setOpaque(false);
        lw.add(left, BorderLayout.NORTH);
        cols.add(lw);
        cols.setAlignmentX(Component.RIGHT_ALIGNMENT);
        body.add(cols);
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);

        // الأحداث
        officeSearch.getDocument().addDocumentListener(doc(this::filterOffices));
        office.addActionListener(e -> {
            if (!building) officeChanged();
        });
        type.listener = i -> updateSummary();
        currency.addActionListener(e -> {
            if (building) return;
            if (feeCur.getItemCount() > 0 && currency.getSelectedIndex() >= 0) feeCur.setSelectedItem(currency.getSelectedItem());
            recalcFee();
        });
        amount.getDocument().addDocumentListener(doc(this::recalcFee));
        fee.getDocument().addDocumentListener(doc(this::updateSummary));
        rate.getDocument().addDocumentListener(doc(this::recalcFee));
        feeMode.listener = i -> applyFee();
        feeSide.listener = i -> updateSummary();
        feeKind.listener = i -> applyFee();
        ratePer.listener = i -> recalcFee();
        feeCur.addActionListener(e -> updateSummary());
        person.getDocument().addDocumentListener(doc(this::updateSummary));
        desc.getDocument().addDocumentListener(doc(this::scheduleParse));
        date.onChange = this::updateSummary;
        parseTimer = new javax.swing.Timer(450, e -> previewParse());
        parseTimer.setRepeats(false);
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(Ui.key(KeyEvent.VK_S), "save");
        getActionMap().put("save", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing()) save();
            }
        });
        applyFee();
    }

    static DocumentListener doc(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }

            @Override public void removeUpdate(DocumentEvent e) { SwingUtilities.invokeLater(r); }

            @Override public void changedUpdate(DocumentEvent e) {}
        };
    }

    @Override void refresh() {
        if (editing != null) {
            done();
            return;
        }
        titleLabel.setText("تسجيل حركة");
        subtitle.setText(quotaText());
        building = true;
        loadOffices(db.settingLong("last_office", 0));
        loadServices();
        building = false;
        officeChanged();
        if (db.setting("draft_tx_mac", "").length() > 0 && desc.getText().isEmpty()) restoreDraft();
        done();
    }

    @Override void open(Object arg) {
        if (arg instanceof Prefill) {
            editing = null;
            reset(false);
            building = true;
            loadOffices(((Prefill) arg).office);
            loadServices();
            building = false;
            officeChanged();
            titleLabel.setText("تسجيل حركة");
            subtitle.setText(quotaText());
        } else if (arg instanceof Edit) {
            Model.Tx t = db.tx(((Edit) arg).txId);
            approving = ((Edit) arg).approve;
            if (approving && t != null && t.isPending()) {
                fillEdit(t);
                titleLabel.setText("تصحيح ثم اعتماد");
                subtitle.setText("اقترحها: " + (t.userName.isEmpty() ? "موظف" : t.userName) + " — تدخل الأرصدة بالقيم المصححة، ويُحفظ ما قبل التصحيح في السجل");
                done();
                return;
            }
            if (t == null || !t.isActive()) {
                Ui.toast("الحركة غير موجودة أو ملغاة");
                return;
            }
            if (t.isFx()) {
                // 10.18: القص يُعدَّل بساقيه من شاشته
                app.go("fx", new FxView.Edit(t.id));
                return;
            }
            if (t.isLinked()) {
                Ui.toast(t.isTransfer() ? "ساقا التحويل بين مكتبين لا تُعدَّلان منفردتين؛ ألغِ التحويل وسجّله من جديد."
                        : "ساقا تبديل العملة لا تُعدَّلان منفردتين؛ ألغِ التبديل وسجّله من جديد.");
                return;
            }
            fillEdit(t);
        } else if (arg instanceof String) {
            refresh();
            desc.setText((String) arg);
        } else refresh();
        done();
    }

    String quotaText() {
        Entitlements e = License.current;
        if (e.pro) return "";
        Model.Office o = (Model.Office) office.getSelectedItem();
        if (o == null) return "النسخة المجانية: " + e.maxTxPerOffice() + " حركة جديدة لكل مكتب";
        QuotaState q = db.quotaNow();
        return "النسخة المجانية: بقي " + QuotaState.remaining(q.txOf(o.id), e.maxTxPerOffice()) + " حركة جديدة لـ«" + o.name + "»";
    }

    void loadOffices(long select) {
        List<Model.Office> l = new ArrayList<>();
        for (Model.Office o : RecordView.myFirst(db, db.offices())) if (o.active) l.add(o);
        String q = OwnerReports.norm(officeSearch.getText());
        DefaultComboBoxModel<Model.Office> m = new DefaultComboBoxModel<>();
        m.addElement(null);
        for (Model.Office o : l) if (q.isEmpty() || OwnerReports.norm(o.name + " " + Model.nz(o.shortName)).contains(q)) m.addElement(o);
        office.setModel(m);
        for (int i = 0; i < m.getSize(); i++) if (m.getElementAt(i) != null && m.getElementAt(i).id == select) office.setSelectedIndex(i);
    }

    /** يختار مكتبًا بعينه في القائمة (يمسح البحث إن أخفاه). */
    void selectOffice(long id) {
        officeSearch.setText("");
        building = true;
        loadOffices(id);
        building = false;
        officeChanged();
    }

    void filterOffices() {
        Model.Office cur = (Model.Office) office.getSelectedItem();
        building = true;
        loadOffices(cur == null ? -1 : cur.id);
        if (office.getSelectedItem() == null && office.getItemCount() == 2) office.setSelectedIndex(1);
        building = false;
        officeChanged();
    }

    void loadServices() {
        Object cur = service.getEditor().getItem();
        DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
        m.addElement("");
        for (String s : db.usedServices(30)) m.addElement(s);
        for (String s : new String[]{"اعتماد", "سويفت", "USDT", "تسليم باليد", "إيداع بنكي"}) if (m.getIndexOf(s) < 0) m.addElement(s);
        service.setModel(m);
        service.getEditor().setItem(cur == null ? "" : cur);
    }

    void officeChanged() {
        fillRecentOffices();
        fillRateChips();
        Model.Office o = (Model.Office) office.getSelectedItem();
        Object curSel = currency.getSelectedItem();
        building = true;
        DefaultComboBoxModel<String> cm = new DefaultComboBoxModel<>(), fm = new DefaultComboBoxModel<>();
        if (o != null) for (String c : db.officeCurrencies(o.id)) {
            cm.addElement(c);
            fm.addElement(c);
        }
        currency.setModel(cm);
        feeCur.setModel(fm);
        currency.setSelectedIndex(-1);
        if (curSel != null && cm.getIndexOf(curSel) >= 0) currency.setSelectedItem(curSel);
        else if (cm.getSize() == 1) currency.setSelectedIndex(0);
        if (currency.getSelectedItem() != null) feeCur.setSelectedItem(currency.getSelectedItem());
        building = false;
        subtitle.setText(editing != null ? subtitle.getText() : quotaText());
        if (parsed != null && !filled) renderParse();
        recalcFee();
    }

    // ===================================================================================
    // الأجرة
    // ===================================================================================

    void applyFee() {
        boolean on = feeMode.selected == 1;
        feeBox.setVisible(on);
        boolean byRate = feeKind.selected == 1;
        rateBox.setVisible(byRate);
        fee.setEditable(!byRate);
        fee.setHint(byRate ? "الأجرة المحسوبة تظهر هنا" : "مبلغ الأجرة");
        feeCur.setEnabled(!byRate);
        if (byRate && currency.getSelectedItem() != null) feeCur.setSelectedItem(currency.getSelectedItem());
        recalcFee();
        body.revalidate();
    }

    int per() { return ratePer.selected == 1 ? Model.PER_MILLE : Model.PER_CENT; }

    void recalcFee() {
        if (feeMode == null) return;
        if (feeMode.selected == 1 && feeKind.selected == 1) {
            String c = (String) currency.getSelectedItem();
            String msg;
            try {
                BigDecimal r = Model.parseRate(rate.getText());
                if (c == null) throw new IllegalArgumentException("اختر العملة");
                long a = Db.minor(amount.getText(), db.decimals(c));
                long f = Model.feeFromRate(a, r, per());
                String v = Model.plain(f, db.decimals(c));
                if (!fee.getText().equals(v)) fee.setText(v);
                msg = Model.fmt(a, db.decimals(c)) + " × " + Model.rateLabel(r, per()) + " = " + Model.fmt(f, db.decimals(c)) + " " + c
                        + (Model.feeRounded(a, r, per()) ? " (مقرّبة)" : "");
            } catch (Exception e) {
                if (!fee.getText().isEmpty()) fee.setText("");
                msg = rate.getText().trim().isEmpty() ? (per() == Model.PER_MILLE ? "اكتب النسبة بالألف، مثل 5" : "اكتب النسبة بالمئة، مثل 1") : "";
            }
            rateResult.setText(msg);
        }
        updateSummary();
    }

    // ===================================================================================
    // الرسالة: الاستخراج والطابور
    // ===================================================================================

    void pasteClipboard() {
        try {
            String s = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (s == null || s.trim().isEmpty()) {
                Ui.toast("الحافظة فارغة");
                return;
            }
            List<WaText.Msg> msgs = WaText.split(s);
            if (msgs.size() > 1) {
                queue = msgs;
                queueAt = 0;
                desc.setText(msgs.get(0).body);
                updateQueue();
                Ui.toast("وُجدت " + msgs.size() + " رسائل؛ تُسجَّل واحدة بعد الأخرى.");
            } else desc.setText(s.trim());
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    /** 10.5 (§5): يعرض المكتب المثبّت لرسائل واتساب، أو أنه سيُطلب تأكيده قبل أول حفظ. */
    void updatePin() {
        Model.Office p = WaPin.current(db);
        boolean wa = fromWa();
        pinLabel.setVisible(wa);
        if (!wa) return;
        pinLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        pinLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        pinLabel.setFont(Ui.font(14, true));
        pinLabel.setText(p == null ? "<html><div style='text-align:right'><font color='" + Ui.hex(Ui.red) + "'> لم يُثبَّت مكتب لرسائل واتساب في هذه الجلسة: سيُطلب تأكيده قبل أول حفظ.</font></div></html>"
                : "<html><div style='text-align:right'> المكتب المثبّت لرسائل واتساب: <b><font color='" + Ui.hex(Ui.red) + "'>«" + Ui.esc(p.name) + "»</font></b> — لا يتغير تلقائيًا</div></html>");
    }

    /** النموذج من رسالة واتساب (دفعة، أو نص فيه حوالة مستخرجة). */
    boolean fromWa() { return editing == null && (queue.size() > 1 || parsed != null && !desc.getText().trim().isEmpty()); }

    void updateQueue() {
        updatePin();
        if (queue.size() > 1) {
            queueLabel.setText("الرسالة " + (queueAt + 1) + " من " + queue.size() + (queue.get(queueAt).sender == null || queue.get(queueAt).sender.isEmpty() ? "" : " — من " + queue.get(queueAt).sender));
            queueLabel.setVisible(true);
        } else {
            queueLabel.setText("");
            queueLabel.setVisible(false);
        }
    }

    void scheduleParse() {
        filled = false;
        parseTimer.restart();
        updateSummary();
    }

    List<MessageParser.Cur> parserCurrencies() {
        List<MessageParser.Cur> out = new ArrayList<>();
        Map<String, String> names = db.currencyNames();
        Model.Office o = (Model.Office) office.getSelectedItem();
        if (o != null) for (String c : db.officeCurrencies(o.id)) out.add(new MessageParser.Cur(c, names.get(c), db.decimals(c)));
        if (out.isEmpty()) for (Model.Currency c : db.currencies(false)) out.add(new MessageParser.Cur(c.code, c.name, c.decimals));
        return out;
    }

    void previewParse() {
        String text = desc.getText();
        if (text.trim().length() < 3 || editing != null) {
            parsed = null;
            duplicate = null;
            parseBox.removeAll();
            parseBox.revalidate();
            return;
        }
        // 10.16: مفتاح «استخراج معلومات رسائل واتساب» (كالهاتف): عند إيقافه إدخال يدوي عادي
        if ("0".equals(db.setting("wa_parse", "1"))) {
            parsed = null;
            duplicate = null;
            parseBox.removeAll();
            parseBox.revalidate();
            parseBox.repaint();
            saveDraft();
            return;
        }
        duplicate = duplicateOf(text);
        // 9.0: المجانية: استخراج معلومات 20 رسالة يوميًا، ثم الإدخال اليدوي العادي
        if (!db.waAllowed(text)) {
            parsed = null;
            parseBox.removeAll();
            parseBox.add(Ui.gap(10));
            parseBox.add(Ui.label("الإدخال اليدوي", 14, Ui.ink, true));
            parseBox.add(Ui.para(Entitlements.waLimitMessage(db.waUsage()[1]), 13, Ui.muted, 520));
            Ui.rtl(parseBox);
            parseBox.revalidate();
            parseBox.repaint();
            saveDraft();
            return;
        }
        parsed = MessageParser.parse(text, parserCurrencies(), MessageParser.aliasesFrom(db.setting("cur_aliases", "")));
        updatePin();
        renderParse();
        saveDraft();
    }

    void renderParse() {
        parseBox.removeAll();
        MessageParser.Result r = parsed;
        if (r == null) return;
        parseBox.add(Ui.gap(10));
        parseBox.add(Ui.label("المعلومات المستخرجة", 14, Ui.primaryInk, true));
        long[] wu = db.waUsage();
        if (wu[1] != Entitlements.UNLIMITED) parseBox.add(Ui.label("النسخة المجانية: استُخدم " + wu[0] + " من " + wu[1] + " رسالة اليوم (تُحتسب عند الإضافة للحقول)", 12, Ui.muted, false));
        if (duplicate != null) line(false, "! هذه الرسالة مسجّلة سابقًا: " + dupText(duplicate) + " — تأكد قبل التسجيل حتى لا تُسجَّل مرتين");
        line(!r.person.isEmpty(), r.person.isEmpty() ? "المستفيد: لم يُستخرج — اكتبه بيدك" : "المستفيد: " + r.person);
        line(!r.amount.isEmpty(), r.amount.isEmpty() ? "المبلغ: لم يُستخرج — اكتبه بيدك" : "المبلغ: " + r.amount
                + (r.amountText.isEmpty() || r.amountText.equals(r.amount) ? "" : "   (في الرسالة: «" + r.amountText + "»)"));
        // 10.5: تفسير يحتاج مراجعة («50000 الف»، أو رقم ملتبس بعملة بثلاث منازل)
        if (!r.amountNote.isEmpty()) line(false, "راجع المبلغ: " + r.amountNote);
        if (r.currency != null) line(true, "العملة: " + r.currency);
        else if (!r.currencyWord.isEmpty()) line(false, "العملة «" + r.currencyWord + "» غير مفعّلة لهذا المكتب — اخترها بيدك");
        else if (!r.unknownWord.isEmpty()) {
            // 10.18 (كالهاتف): كلمة عملة غير معروفة تُربط بعملتها من هنا مباشرة، فيفهمها البرنامج بعدها دائمًا
            line(false, "العملة: لم أتعرّف على «" + r.unknownWord + "»");
            final String word = r.unknownWord;
            parseBox.add(Ui.flow(Ui.ghost("اربط «" + word + "» بعملة ليفهمها البرنامج دائمًا", e -> {
                try {
                    List<String> codes = new ArrayList<>();
                    for (Model.Currency c : db.currencies(false)) codes.add(c.code);
                    int k = Ui.choose(this, "تعني أي عملة؟", "«" + word + "» تعني:", codes.toArray(new String[0]));
                    if (k < 0) return;
                    Map<String, List<String>> al = MessageParser.aliasesFrom(db.setting("cur_aliases", ""));
                    al.computeIfAbsent(codes.get(k), z -> new ArrayList<>()).add(word);
                    db.setSetting("cur_aliases", MessageParser.aliasesTo(al));
                    Ui.toast("«" + word + "» = " + codes.get(k));
                    previewParse();
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            })));
        } else line(false, "العملة: لم تُذكر — اخترها بيدك");
        if (office.getSelectedItem() == null) line(false, "المكتب: لم يُختر بعد — اختره من النموذج");
        if (r.net) line(true, "صافي (بدون خصم أجرة)");
        if (!r.phone.isEmpty()) line(true, "الهاتف: " + r.phone);
        if (!r.city.isEmpty()) line(true, "المدينة: " + r.city);
        if (!r.sender.isEmpty()) line(true, "المرسل في نص الحوالة: " + r.sender);
        if (!r.rajeRate.isEmpty()) line(true, "راجع (أجرة لنا): " + r.rajeRate + (r.rajePer == Model.PER_MILLE ? " بالألف" : "٪") + " من المبلغ");
        else if (!r.rajeAmount.isEmpty()) line(true, "راجع (أجرة لنا): " + r.rajeAmount);
        else if (r.raje) line(false, "ذُكر «راجع»" + (r.rajeHint.isEmpty() ? "" : " " + r.rajeHint) + " دون «بالمية/بالألف» أو عملة — حدّد الأجرة لنا بيدك");
        if (!r.fee.isEmpty()) line(true, "ذُكرت أجرة: " + r.fee + " — لا تُضاف تلقائيًا، أضفها من «مع أجرة» إن لزم");
        List<Component> bs = new ArrayList<>();
        if (r.any()) bs.add(Ui.button(filled ? "✓ أُضيفت للحقول — إعادة الإضافة" : "↓  إضافة المعلومات للحقول", filled ? Ui.green : Ui.primary, e -> fillFromParse()));
        if (License.current.ai() && "1".equals(db.setting("ai_on", "0"))) bs.add(Ui.ghost(AiExtract.BUTTON, e -> askAi()));
        parseBox.add(Ui.gap(6));
        parseBox.add(Ui.flow(bs.toArray(new Component[0])));
        if (!r.any()) parseBox.add(Ui.label("لم أجد في الرسالة معلومات يمكن إضافتها؛ اكتب الحقول بيدك.", 13, Ui.red, true));
        Ui.rtl(parseBox);
        parseBox.revalidate();
        parseBox.repaint();
    }

    void line(boolean ok, String s) {
        JLabel l = Ui.label((ok ? "✓ " : "× ") + s, 13.5f, ok ? Ui.green : Ui.red, false);
        parseBox.add(l);
    }

    void fillFromParse() {
        MessageParser.Result r = parsed;
        if (r == null) return;
        if (!db.waUse(desc.getText())) {
            previewParse();
            return;
        }
        List<String> doneL = new ArrayList<>();
        if (!r.person.isEmpty()) {
            person.setText(r.person);
            doneL.add("المستفيد");
        }
        if (!r.amount.isEmpty()) {
            amount.setText(r.amount);
            doneL.add("المبلغ");
        }
        if (!r.city.isEmpty() && dest.val().isEmpty()) {
            dest.setText(r.city);
            doneL.add("الوجهة");
        }
        if (r.currency != null && ((DefaultComboBoxModel<String>) currency.getModel()).getIndexOf(r.currency) >= 0) {
            currency.setSelectedItem(r.currency);
            doneL.add("العملة");
        }
        if (!r.rajeRate.isEmpty() || !r.rajeAmount.isEmpty()) {
            // «راجع» = أجرة يدفعها المكتب لنا
            feeMode.set(1);
            feeSide.set(0);
            if (!r.rajeRate.isEmpty()) {
                feeKind.set(1);
                ratePer.set(r.rajePer == Model.PER_MILLE ? 1 : 0);
                rate.setText(r.rajeRate);
            } else {
                feeKind.set(0);
                if (currency.getSelectedItem() != null) feeCur.setSelectedItem(currency.getSelectedItem());
                fee.setText(r.rajeAmount);
            }
            applyFee();
            doneL.add("الراجع (أجرة لنا)");
        }
        filled = true;
        renderParse();
        if (type.none()) type.setBorder(BorderFactory.createLineBorder(Ui.red));
        updateSummary();
        Ui.toast(doneL.isEmpty() ? "لا توجد معلومات يمكن إضافتها" : "أُضيف: " + Model.join(doneL, "، ") + ". اختر «لنا» أو «علينا».");
    }

    void askAi() {
        AiExtract.Config c = AiPrefs.config(db);
        String problem = c.problem();
        if (problem != null) {
            Ui.toast(problem);
            return;
        }
        String text = desc.getText();
        List<MessageParser.Cur> curs = parserCurrencies();
        Ui.busy(this, "جارٍ فهم الرسالة بالذكاء الاصطناعي…", p -> AiExtract.call(c, text, curs, MessageParser.aliasesFrom(db.setting("cur_aliases", ""))), res -> {
            parsed = res;
            filled = false;
            renderParse();
        });
    }

    Map<String, Model.Tx> recentPrints() {
        long now = Clock.now();
        if (prints != null && now - printsAt < 300000L) return prints;
        Map<String, Model.Tx> m = new HashMap<>();
        try {
            for (Model.Tx t : db.descriptionsSince(now - 120L * 86400000L)) {
                String fp = WaText.fingerprint(t.description);
                if (fp.length() >= 12 && !m.containsKey(fp)) m.put(fp, t);
            }
        } catch (Exception ignored) {
            // مساعد فقط
        }
        prints = m;
        printsAt = now;
        return m;
    }

    Model.Tx duplicateOf(String text) {
        String fp = WaText.fingerprint(text);
        if (fp.length() < 12) return null;
        Model.Tx t = recentPrints().get(fp);
        if (t == null || (editing != null && t.id == editing.id)) return null;
        Model.Tx full = db.tx(t.id);
        return full != null && full.isActive() ? full : null;
    }

    String dupText(Model.Tx t) {
        return Model.date(t.createdAt) + " — " + db.officeNames().getOrDefault(t.officeId, "") + " — " + Model.fmt(t.amount, db.decimals(t.currency)) + " " + t.currency;
    }

    // ===================================================================================
    // الصور
    // ===================================================================================

    void attach() {
        File f = Export.open(this, db, "اختر صورة الوصل", ".jpg", ".jpeg", ".png", ".heic", ".gif", ".bmp");
        if (f == null) return;
        try {
            BufferedImage im = ImageIO.read(f);
            if (im == null) throw new IllegalArgumentException("الصيغة غير مدعومة؛ اختر JPG أو PNG (صور HEIC: صدّرها JPG من «الصور»).");
            int max = 2000;
            double s = Math.min(1, (double) max / Math.max(im.getWidth(), im.getHeight()));
            int w = (int) (im.getWidth() * s), h = (int) (im.getHeight() * s);
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(im, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", o);
            imageData.add(o.toByteArray());
            renderImages();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void renderImages() {
        images.removeAll();
        for (int i = 0; i < imageData.size(); i++) {
            final int k = i;
            images.add(Ui.ghost("صورة " + (i + 1) + " ×", e -> {
                imageData.remove(k);
                renderImages();
            }));
        }
        images.revalidate();
        images.repaint();
    }

    // ===================================================================================
    // الملخص والحفظ
    // ===================================================================================

    Db.TxInput input(boolean strict) throws Exception {
        Model.Office o = (Model.Office) office.getSelectedItem();
        if (o == null) throw new IllegalArgumentException("اختر المكتب");
        if (type.none()) throw new IllegalArgumentException("اختر «لنا» أو «علينا»");
        String c = (String) currency.getSelectedItem();
        if (c == null) throw new IllegalArgumentException("اختر العملة");
        Db.TxInput in = new Db.TxInput();
        in.office = o.id;
        in.type = type.selected == 0 ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        in.currency = c;
        if (amount.val().isEmpty()) throw new IllegalArgumentException("اكتب المبلغ");
        in.amount = Db.minor(amount.getText(), db.decimals(c));
        if (in.amount <= 0) throw new IllegalArgumentException("المبلغ يجب أن يكون أكبر من صفر");
        in.person = person.val();
        in.description = desc.getText().trim();
        in.notes = notes.val();
        Object sv = service.getEditor().getItem();
        in.service = sv == null ? "" : sv.toString().trim();
        in.dest = dest.val();
        long day = date.start();
        long now = Clock.now();
        if (day == 0 || day == Ui.startOfDay(now)) in.when = editing != null && Ui.startOfDay(editing.createdAt) == Ui.startOfDay(now) ? editing.createdAt : now;
        else if (editing != null && Ui.startOfDay(editing.createdAt) == day) in.when = editing.createdAt;
        else {
            Calendar n = Model.cal();
            Calendar x = Model.cal();
            x.setTimeInMillis(day);
            x.set(Calendar.HOUR_OF_DAY, n.get(Calendar.HOUR_OF_DAY));
            x.set(Calendar.MINUTE, n.get(Calendar.MINUTE));
            x.set(Calendar.SECOND, n.get(Calendar.SECOND));
            in.when = Math.min(x.getTimeInMillis(), now);
        }
        if (feeMode.selected == 1) {
            in.feeType = feeSide.selected == 0 ? Model.FEE_US : Model.FEE_THEM;
            if (feeKind.selected == 1) {
                BigDecimal r = Model.parseRate(rate.getText());
                in.feeCurrency = c;
                in.fee = Model.feeFromRate(in.amount, r, per());
                if (in.fee <= 0) throw new IllegalArgumentException("الأجرة المحسوبة أصغر من أصغر وحدة في " + c + ". اكتبها كمبلغ محدد أو اختر «بدون أجرة».");
            } else {
                String fc = (String) feeCur.getSelectedItem();
                in.feeCurrency = fc == null ? c : fc;
                if (fee.val().isEmpty()) throw new IllegalArgumentException("اكتب مبلغ الأجرة أو اختر «بدون أجرة»");
                in.fee = Db.minor(fee.getText(), db.decimals(in.feeCurrency));
                if (in.fee <= 0) throw new IllegalArgumentException("مبلغ الأجرة يجب أن يكون أكبر من صفر");
            }
        } else {
            in.feeType = Model.FEE_NONE;
            in.fee = 0;
            in.feeCurrency = c;
        }
        return in;
    }

    String summaryText(Db.TxInput in) {
        Model.Office o = db.office(in.office);
        int d = db.decimals(in.currency);
        boolean us = Model.TYPE_DEBIT.equals(in.type);
        StringBuilder b = new StringBuilder();
        b.append(us ? "لنا " : "علينا ").append(Ui.ltr(Model.fmt(in.amount, d) + " " + in.currency)).append(" — «").append(o == null ? "" : o.name).append("»");
        if (!in.person.isEmpty()) b.append("\nالمستفيد: ").append(in.person);
        if (!Model.FEE_NONE.equals(in.feeType)) b.append("\n").append(in.feeType).append(": ").append(Ui.ltr(Model.fmt(in.fee, db.decimals(in.feeCurrency)) + " " + in.feeCurrency));
        // 10.5 (§11): الرصيد من طبقة البيانات بقواعد الترحيل نفسها (الأجرة بأي عملة، والتعديل بين مكتبين أو عملتين)
        for (Db.Preview p : db.preview(in, editing == null ? null : editing.id))
            for (String cur : p.before.keySet())
                b.append("\nرصيد «").append(p.officeName).append("» بـ").append(cur).append(": ").append(Model.bal(p.before.get(cur), db.decimals(cur))).append("  ←  ")
                        .append(Model.bal(p.after.get(cur), db.decimals(cur))).append(p.pending ? " بعد الاعتماد" : " بعد التسجيل");
        if (in.when > 0 && Ui.startOfDay(in.when) != Ui.startOfDay(Clock.now())) b.append("\nبتاريخ ").append(Model.day(in.when));
        return b.toString();
    }

    void updateSummary() {
        if (summary == null || building) return;
        if (moreToggle != null) {
            boolean filled = !dest.val().isEmpty() || !notes.val().isEmpty() || !imageData.isEmpty() || !service.getEditor().getItem().toString().trim().isEmpty();
            showMore(more.isVisible() || filled);
        }
        try {
            Db.TxInput in = input(false);
            summary.setText("<html><div style='text-align:right'>" + Ui.esc(summaryText(in)) + "</div></html>");
            summary.setForeground(Model.TYPE_DEBIT.equals(in.type) ? Ui.green : Ui.red);
            if (type.getBorder() != null) type.setBorder(null);
        } catch (Exception e) {
            summary.setText("<html><div style='text-align:right'>" + Ui.esc(Ui.friendly(e)) + "</div></html>");
            summary.setForeground(Ui.muted);
        }
    }

    List<String> sanity(Db.TxInput in) {
        try {
            Sanity.Input s = new Sanity.Input();
            s.amount = in.amount;
            s.when = in.when;
            s.debit = Model.TYPE_DEBIT.equals(in.type);
            s.currency = in.currency;
            s.dec = db.decimals(in.currency);
            s.person = in.person;
            Long bal = db.balances(in.office).get(in.currency);
            s.balanceBefore = bal == null ? 0 : bal;
            Db.Limit lim = db.officeLimits(in.office).get(in.currency);
            if (lim != null) {
                s.receivableLimit = lim.receivable;
                s.payableLimit = lim.payable;
            }
            Db.TxFilter f = new Db.TxFilter();
            f.office = in.office;
            f.limit = 60;
            s.history = db.txLog(f);
            return Sanity.check(s);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 10.5: يمنع الحفظ المزدوج (نقرتان أو Enter مرتين أثناء الحفظ). */
    boolean saving;

    void save() {
        if (saving) return;
        if (!require(editing == null ? Auth.Perm.TX_ADD : Auth.Perm.TX_EDIT)) return;
        // 10.5 (§5): رسالة واتساب: جلسة جديدة تؤكد المكتب أولًا، وتغيير المكتب المثبّت يمر بالتحذير المطلوب
        Model.Office chosen = (Model.Office) office.getSelectedItem();
        if (fromWa() && chosen != null) {
            WaPin.Need need = WaPin.before(db, chosen.id);
            if (need == WaPin.Need.CONFIRM_SESSION) {
                if (!Ui.confirm(this, "تأكيد المكتب: «" + chosen.name + "»", WaPin.sessionQuestion(chosen.name), "نعم، ثبّته")) return;
                WaPin.pin(db, chosen.id);
            } else if (need == WaPin.Need.CONFIRM_CHANGE) {
                Model.Office was = WaPin.current(db);
                int r = Ui.choose(this, "تغيير المكتب", WaPin.changeWarning(was.name, chosen.name),
                        new String[]{"تغيير إلى «" + chosen.name + "»", "إبقاء «" + was.name + "»"});
                if (r != 0) {
                    if (r == 1) selectOffice(was.id);
                    return;
                }
                WaPin.pin(db, chosen.id);
            }
            updatePin();
        }
        final Db.TxInput in;
        try {
            in = input(true);
        } catch (Exception e) {
            Ui.error(this, e);
            return;
        }
        Model.Tx dup = editing == null ? duplicateOf(in.description) : null;
        List<String> warns = sanity(in);
        String warnText = warns.isEmpty() ? "" : "! " + Model.join(warns, "\n! ") + "\n\n";
        // 10.5 (§11): نافذة تأكيد بالأرصدة من طبقة البيانات (المكتب بالأحمر، الرصيد الحالي وبعد الحركة)، تُحدَّث وهي مفتوحة
        final Long replacing = editing == null ? null : editing.id;
        java.util.function.Supplier<TxConfirm.View> view = () -> TxConfirm.build(db, in, replacing);
        boolean ask = dup != null || !warns.isEmpty() || "1".equals(db.setting("confirm_tx", "1")) || editing != null;
        if (ask) {
            String title, yes, prefix = warnText.trim();
            if (dup != null) {
                title = "! هذه الرسالة مسجّلة سابقًا";
                yes = "نعم، سجّلها";
                prefix = "يوجد حركة بالبيان نفسه: " + dupText(dup) + ". هل هي حوالة جديدة فعلًا؟" + (prefix.isEmpty() ? "" : "\n" + prefix);
            } else if (!warns.isEmpty()) {
                title = "راجع قبل التسجيل";
                yes = "تسجيل رغم ذلك";
            } else if (approving && editing != null) {
                title = "تأكيد التصحيح والاعتماد";
                yes = "اعتماد";
            } else {
                title = editing == null ? "تأكيد تسجيل الحركة" : "تأكيد التعديل";
                yes = editing == null ? "تسجيل" : "حفظ";
            }
            while (true) {
                TxConfirm.View ok = Ui.confirmTx(this, title, prefix, view, yes);
                if (ok == null) return;
                if (!TxConfirm.changed(db, ok, java.util.Collections.singletonList(in), replacing)) break;
                prefix = "! تغيّر الرصيد لحظة الضغط. راجع الأرقام الجديدة ثم أكّد." + (warnText.isEmpty() ? "" : "\n" + warnText.trim());
            }
        }
        if (saving) return;
        String reason = "";
        if (editing != null && !approving) {
            reason = Ui.input(this, "سبب التعديل", "اكتب سبب التعديل (يظهر في سجل العمليات)", "");
            if (reason == null) return;
            if (reason.trim().isEmpty()) {
                Ui.toast("سبب التعديل مطلوب");
                return;
            }
        }
        final String why = reason;
        final Model.Tx old = editing;
        final List<byte[]> imgs = new ArrayList<>(imageData);
        final boolean approve = approving && old != null;
        saving = true;
        final boolean fromWaSave = fromWa();
        Ui.busy(this, "جارٍ الحفظ…", p -> {
            try {
                if (old == null) return db.addTx(in, imgs);
                if (approve) {
                    db.approveEdited(old.id, in, imgs, new HashSet<>());
                    return old.id;
                }
                return db.editTx(old.id, in, imgs, new HashSet<>(), why);
            } finally {
                saving = false;
            }
        }, id -> {
            Model.Tx t = db.tx(id);
            if (fromWaSave) WaPin.touch(db);
            db.setSettingQuiet("last_office", "" + in.office);
            rememberOffice(in.office);
            rememberRate();
            db.setSettingQuiet("fee_per_last", "" + per());
            db.deleteSetting("draft_tx_mac");
            prints = null;
            app.changed();
            boolean pending = t != null && t.isPending();
            if (old != null) {
                editing = null;
                approving = false;
                Ui.toast(approve ? "صُحّحت الحركة واعتُمدت ✓" : "حُفظ التعديل: أُلغي الأصل بقيد عكسي وسُجّل البديل");
                reset(false);
                app.go("log");
                return;
            }
            Ui.toast(pending ? "سُجّلت بانتظار اعتماد المحاسب (لا تدخل الأرصدة قبل الاعتماد)" : "✓ سُجّلت الحركة");
            if (queue.size() > 1 && queueAt + 1 < queue.size()) {
                queueAt++;
                reset(false);
                desc.setText(queue.get(queueAt).body);
                updateQueue();
            } else {
                queue.clear();
                reset(false);
                updateQueue();
            }
            subtitle.setText(quotaText());
        });
    }

    void reset(boolean full) {
        building = true;
        type.set(-1);
        type.setBorder(null);
        amount.setText("");
        feeMode.set(0);
        feeSide.set(0);
        feeKind.set(0);
        fee.setText("");
        rate.setText("");
        person.setText("");
        desc.setText("");
        notes.setText("");
        dest.setText("");
        service.getEditor().setItem("");
        date.set(0);
        imageData.clear();
        renderImages();
        parsed = null;
        filled = false;
        duplicate = null;
        parseBox.removeAll();
        if (full) {
            queue.clear();
            updateQueue();
            db.deleteSetting("draft_tx_mac");
        }
        building = false;
        applyFee();
        updateSummary();
        parseBox.revalidate();
        parseBox.repaint();
    }

    void fillEdit(Model.Tx t) {
        editing = t;
        building = true;
        titleLabel.setText("تعديل حركة");
        subtitle.setText("التعديل يُلغي الأصل بقيد عكسي ويسجّل البديل؛ يبقى الأثر في سجل العمليات.");
        loadOffices(t.officeId);
        Model.Office o = db.office(t.officeId);
        if (office.getSelectedItem() == null && o != null) {
            ((DefaultComboBoxModel<Model.Office>) office.getModel()).addElement(o);
            office.setSelectedItem(o);
        }
        loadServices();
        building = false;
        officeChanged();
        building = true;
        type.set(Model.TYPE_DEBIT.equals(t.type) ? 0 : 1);
        currency.setSelectedItem(t.currency);
        amount.setText(Model.plain(t.amount, db.decimals(t.currency)));
        if (t.hasFee()) {
            feeMode.set(1);
            feeSide.set(Model.FEE_US.equals(t.feeType) ? 0 : 1);
            feeKind.set(0);
            feeCur.setSelectedItem(t.feeCurrency);
            fee.setText(Model.plain(t.fee, db.decimals(t.feeCurrency)));
        } else feeMode.set(0);
        person.setText(Model.nz(t.person));
        desc.setText(Model.nz(t.description));
        notes.setText(Model.nz(t.notes));
        service.getEditor().setItem(Model.nz(t.service));
        dest.setText(Model.nz(t.dest));
        date.set(t.createdAt);
        imageData.clear();
        renderImages();
        building = false;
        applyFee();
        parseBox.removeAll();
        save.setText("حفظ التعديل");
        updateSummary();
    }

    void saveDraft() {
        if (editing != null) return;
        String t = desc.getText();
        if (t.length() > 8000) return;
        db.setSecretSetting("draft_tx_mac", t);
    }

    void restoreDraft() {
        String t = db.secretSetting("draft_tx_mac");
        if (!t.isEmpty()) {
            desc.setText(t);
            Ui.toast("استُعيدت مسودة الرسالة الأخيرة");
        }
    }

    @Override void done() {
        if (editing == null && save != null) save.setText("تسجيل الحركة");
        if (kinds != null) kinds.setVisible(editing == null && kinds.buttons.size() > 1);
        super.done();
    }
}
