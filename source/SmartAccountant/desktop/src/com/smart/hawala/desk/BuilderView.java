package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * منشئ التقارير: الفترة، المكاتب، العملات، الاتجاه، نوع الحركة (حوالات/أجور/راجع/تبديل)، الحالة، البحث، الخدمة، الوجهة، المستخدم،
 * حدود المبلغ، التجميع (16 طريقة) والجدول المتقاطع والمقارنة بالفترة السابقة والترتيب وأعلى N، والكشف التفصيلي برصيد متراكم.
 * قوالب جاهزة وقوالب يحفظها المستخدم (تنتقل مع النسخة الاحتياطية). الحساب في ReportBuilder المشترك مع الهاتف.
 */
final class BuilderView extends View {
    final Ui.Field title = Ui.field("عنوان التقرير (اختياري)");
    final JComboBox<String> period = Ui.combo(Ui.PERIODS);
    final Ui.DateField from = new Ui.DateField(0), to = new Ui.DateField(0);
    final JComboBox<String> dir = Ui.combo(ReportBuilder.DIR_NAMES), kind = Ui.combo(ReportBuilder.KIND_NAMES), status = Ui.combo(ReportBuilder.STATUS_NAMES);
    final JComboBox<String> group = Ui.combo(ReportBuilder.GROUP_NAMES), group2 = Ui.combo(withNone(ReportBuilder.GROUP_NAMES)), measure = Ui.combo(ReportBuilder.MEASURE_NAMES),
            sort = Ui.combo(ReportBuilder.SORT_NAMES);
    final Ui.Field text = Ui.field("كلمات في المستفيد أو البيان"), service = Ui.field("الخدمة"), dest = Ui.field("الوجهة"), user = Ui.field("المستخدم");
    final Ui.Field min = Ui.numeric("من"), max = Ui.numeric("إلى"), top = Ui.numeric("الكل");
    final JCheckBox compare = Ui.check("قارن بالفترة السابقة", false), details = Ui.check("أضف الكشف التفصيلي", false), running = Ui.check("رصيد متراكم (مكتب واحد)", true);
    final DefaultListModel<Model.Office> officeModel = new DefaultListModel<>();
    final JList<Model.Office> offices = new JList<>(officeModel);
    final JPanel curBox = Ui.flow();
    final Map<String, JCheckBox> curChecks = new LinkedHashMap<>();
    final JComboBox<String> presets = Ui.combo(new String[0]);
    final JPanel out = Ui.column();
    final JPanel customDates;
    Report last;

    static String[] withNone(String[] a) {
        String[] r = a.clone();
        r[0] = "بلا جدول متقاطع";
        return r;
    }

    BuilderView(App app) {
        super(app, "منشئ التقارير");
        customDates = Ui.row(Ui.label("من", 13, Ui.muted, true), from, Ui.label("إلى", 13, Ui.muted, true), to);
    }

    @Override void build() {
        offices.setFont(Ui.font(13.5f, false));
        offices.setComponentOrientation(Ui.RTL);
        offices.setVisibleRowCount(7);
        offices.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, ((Model.Office) v).name, i, s, f);
                c.setHorizontalAlignment(SwingConstants.RIGHT);
                c.setText((s ? "✓  " : "    ") + ((Model.Office) v).name);
                return c;
            }
        });
        offices.setSelectionModel(new DefaultListSelectionModel() {
            @Override public void setSelectionInterval(int a, int b) {
                if (isSelectedIndex(a)) super.removeSelectionInterval(a, b);
                else super.addSelectionInterval(a, b);
            }
        });
        JScrollPane os = new JScrollPane(offices);
        os.setBorder(new Ui.RoundBorder(Ui.line, 10));
        os.setPreferredSize(new Dimension(240, 170));
        os.setComponentOrientation(Ui.RTL);
        period.setSelectedIndex(3);
        group.setSelectedIndex(ReportBuilder.G_OFFICE);
        measure.setSelectedIndex(ReportBuilder.M_NET);

        Ui.Card c = Ui.card();
        JPanel p1 = new JPanel(new GridLayout(0, 4, 12, 10));
        p1.setOpaque(false);
        p1.setAlignmentX(Component.RIGHT_ALIGNMENT);
        p1.add(Ui.labeled("القوالب", presets));
        p1.add(Ui.labeled("العنوان", title));
        p1.add(Ui.labeled("الفترة", period));
        p1.add(Ui.labeled("فترة مخصصة", customDates));
        p1.add(Ui.labeled("الاتجاه", dir));
        p1.add(Ui.labeled("نوع الحركة", kind));
        p1.add(Ui.labeled("الحالة", status));
        p1.add(Ui.labeled("بحث", text));
        p1.add(Ui.labeled("الخدمة", service));
        p1.add(Ui.labeled("الوجهة", dest));
        p1.add(Ui.labeled("المستخدم", user));
        JPanel mm = new JPanel(new GridLayout(1, 2, 6, 0));
        mm.setOpaque(false);
        mm.add(min);
        mm.add(max);
        p1.add(Ui.labeled("المبلغ (من — إلى)", mm));
        p1.add(Ui.labeled("جمّع حسب", group));
        p1.add(Ui.labeled("جدول متقاطع: الأعمدة حسب", group2));
        p1.add(Ui.labeled("قيمة الجدول المتقاطع", measure));
        p1.add(Ui.labeled("الترتيب", sort));
        JPanel row2 = new JPanel(new BorderLayout(14, 0));
        row2.setOpaque(false);
        row2.setAlignmentX(Component.RIGHT_ALIGNMENT);
        JPanel offBox = Ui.column();
        offBox.add(Ui.label("المكاتب (بلا تحديد = كلها)", 13, Ui.muted, true));
        offBox.add(os);
        offBox.add(Ui.flow(Ui.ghost("الكل", e -> offices.clearSelection())));
        row2.add(offBox, BorderLayout.LINE_START);
        JPanel rest = Ui.column();
        rest.add(Ui.label("العملات", 13, Ui.muted, true));
        rest.add(curBox);
        rest.add(Ui.gap(6));
        rest.add(Ui.row(Ui.labeled("أعلى (عدد)", top), Ui.flow(compare, details, running)));
        top.setMaximumSize(new Dimension(120, 40));
        rest.add(Ui.gap(10));
        rest.add(Ui.flow(Ui.primary("عرض التقرير", e -> run()), Ui.ghost("تصدير / طباعة…", e -> export()), Ui.ghost("حفظ كقالب…", e -> savePreset()),
                Ui.ghost("حذف القالب", e -> deletePreset()), Ui.ghost("مسح الخيارات", e -> apply(new ReportBuilder.Spec()))));
        row2.add(rest, BorderLayout.CENTER);
        c.add(p1);
        c.add(Ui.gap(12));
        c.add(row2);
        body.add(c);
        body.add(Ui.gap(14));
        body.add(out);
        scroll = Ui.scroll(body);
        add(scroll, BorderLayout.CENTER);
        period.addActionListener(e -> customDates.setVisible(period.getSelectedIndex() == Ui.PERIODS.length - 1));
        customDates.setVisible(false);
        presets.addActionListener(e -> {
            if (presets.getSelectedIndex() > 0) loadPreset((String) presets.getSelectedItem());
        });
    }

    @Override void refresh() {
        actions.removeAll();
        subtitle.setText("اختر ما تريد ثم «عرض التقرير»؛ الناتج يُصدَّر PDF أو Excel أو CSV.");
        List<Long> sel = new ArrayList<>();
        for (Model.Office o : offices.getSelectedValuesList()) sel.add(o.id);
        officeModel.clear();
        for (Model.Office o : db.offices()) officeModel.addElement(o);
        for (int i = 0; i < officeModel.size(); i++) if (sel.contains(officeModel.get(i).id)) offices.addSelectionInterval(i, i);
        Set<String> curSel = new HashSet<>();
        for (Map.Entry<String, JCheckBox> e : curChecks.entrySet()) if (e.getValue().isSelected()) curSel.add(e.getKey());
        curBox.removeAll();
        curChecks.clear();
        for (Model.Currency cur : db.currencies(true)) {
            JCheckBox cb = Ui.check(cur.code, curSel.contains(cur.code));
            curChecks.put(cur.code, cb);
            curBox.add(cb);
        }
        loadPresets();
        if (out.getComponentCount() == 0) out.add(Ui.para("أمثلة: «الراجع لكل مكتب هذا الشهر»، «مقارنة شهرية بالمكتب»، «ساعات الذروة»، «أكبر 20 حوالة» — من قائمة القوالب.", 14, Ui.muted, textWidth()));
        done();
    }

    // ===================================================================================
    // المواصفات
    // ===================================================================================

    ReportBuilder.Spec spec() {
        ReportBuilder.Spec s = new ReportBuilder.Spec();
        s.title = title.val();
        int p = period.getSelectedIndex();
        if (p == Ui.PERIODS.length - 1) {
            s.from = from.start();
            s.to = to.endExclusive();
        } else {
            long[] r = Ui.period(p);
            s.from = r[0];
            s.to = r[1];
        }
        for (Model.Office o : offices.getSelectedValuesList()) s.offices.add(o.id);
        for (Map.Entry<String, JCheckBox> e : curChecks.entrySet()) if (e.getValue().isSelected()) s.currencies.add(e.getKey());
        s.direction = dir.getSelectedIndex();
        s.kind = kind.getSelectedIndex();
        s.status = status.getSelectedIndex();
        s.text = text.val();
        s.service = service.val();
        s.dest = dest.val();
        s.user = user.val();
        s.minAmount = min.val().isEmpty() ? null : new BigDecimal(Db.normalizeNumber(min.val()));
        s.maxAmount = max.val().isEmpty() ? null : new BigDecimal(Db.normalizeNumber(max.val()));
        s.group = group.getSelectedIndex();
        s.group2 = group2.getSelectedIndex();
        s.measure = measure.getSelectedIndex();
        s.sort = sort.getSelectedIndex();
        try {
            s.top = top.val().isEmpty() ? 0 : Integer.parseInt(Ui.digits(top.val()));
        } catch (Exception e) {
            s.top = 0;
        }
        s.compare = compare.isSelected();
        s.details = details.isSelected();
        s.runningBalance = running.isSelected();
        return s;
    }

    /** يعرض مواصفات في الحقول. الفترة: تُحفظ كرقم فترة سريعة في القالب (p) ليبقى «هذا الشهر» هذا الشهر دائمًا. */
    void apply(ReportBuilder.Spec s) {
        title.setText(s.title);
        dir.setSelectedIndex(s.direction);
        kind.setSelectedIndex(s.kind);
        status.setSelectedIndex(s.status);
        text.setText(s.text);
        service.setText(s.service);
        dest.setText(s.dest);
        user.setText(s.user);
        min.setText(s.minAmount == null ? "" : s.minAmount.toPlainString());
        max.setText(s.maxAmount == null ? "" : s.maxAmount.toPlainString());
        group.setSelectedIndex(s.group);
        group2.setSelectedIndex(s.group2);
        measure.setSelectedIndex(s.measure);
        sort.setSelectedIndex(s.sort);
        top.setText(s.top > 0 ? "" + s.top : "");
        compare.setSelected(s.compare);
        details.setSelected(s.details);
        running.setSelected(s.runningBalance);
        offices.clearSelection();
        for (int i = 0; i < officeModel.size(); i++) if (s.offices.contains(officeModel.get(i).id)) offices.addSelectionInterval(i, i);
        for (Map.Entry<String, JCheckBox> e : curChecks.entrySet()) e.getValue().setSelected(s.currencies.contains(e.getKey()));
        if (s.from > 0 || s.to > 0) {
            period.setSelectedIndex(Ui.PERIODS.length - 1);
            from.set(s.from);
            to.set(s.to > 0 ? s.to - 1 : 0);
        }
    }

    // ===================================================================================
    // القوالب
    // ===================================================================================

    static final String KEY = "report_presets";
    static final String READY = "جاهز: ";

    /** قوالب جاهزة: [الاسم، الفترة السريعة، المواصفات]. */
    static List<Object[]> builtIn() {
        List<Object[]> l = new ArrayList<>();
        ReportBuilder.Spec s;
        s = new ReportBuilder.Spec();
        s.title = "الراجع لكل مكتب";
        s.kind = ReportBuilder.K_RAJE;
        s.group = ReportBuilder.G_OFFICE;
        s.sort = ReportBuilder.SORT_FEES;
        l.add(new Object[]{"الراجع لكل مكتب — هذا الشهر", 3, s});
        s = new ReportBuilder.Spec();
        s.title = "صافي الأجور والراجع شهريًا";
        s.kind = ReportBuilder.K_ALL;
        s.group = ReportBuilder.G_MONTH;
        s.group2 = ReportBuilder.G_CURRENCY;
        s.measure = ReportBuilder.M_FEES_NET;
        l.add(new Object[]{"صافي الأجور والراجع شهريًا — 12 شهرًا", 6, s});
        s = new ReportBuilder.Spec();
        s.title = "مقارنة المكاتب بالشهر السابق";
        s.group = ReportBuilder.G_OFFICE;
        s.compare = true;
        s.sort = ReportBuilder.SORT_VOLUME;
        l.add(new Object[]{"مقارنة المكاتب بالشهر الماضي", 4, s});
        s = new ReportBuilder.Spec();
        s.title = "عدد العمليات: المكتب × الشهر";
        s.group = ReportBuilder.G_OFFICE;
        s.group2 = ReportBuilder.G_MONTH;
        s.measure = ReportBuilder.M_COUNT;
        s.sort = ReportBuilder.SORT_COUNT;
        s.top = 25;
        l.add(new Object[]{"جدول متقاطع: المكتب × الشهر — 12 شهرًا", 6, s});
        s = new ReportBuilder.Spec();
        s.title = "ساعات الذروة";
        s.group = ReportBuilder.G_HOUR;
        s.group2 = ReportBuilder.G_WEEKDAY;
        s.measure = ReportBuilder.M_COUNT;
        l.add(new Object[]{"ساعات الذروة وأيام الأسبوع — 3 أشهر", 5, s});
        s = new ReportBuilder.Spec();
        s.title = "أكبر الحوالات";
        s.group = ReportBuilder.G_NONE;
        s.kind = ReportBuilder.K_TRANSFERS;
        s.minAmount = new BigDecimal("10000");
        l.add(new Object[]{"الحوالات من 10,000 فأكثر — هذا الشهر", 3, s});
        s = new ReportBuilder.Spec();
        s.title = "الخدمات والوجهات";
        s.group = ReportBuilder.G_SERVICE;
        s.group2 = ReportBuilder.G_DEST;
        s.measure = ReportBuilder.M_COUNT;
        l.add(new Object[]{"الخدمات × الوجهات — 3 أشهر", 5, s});
        s = new ReportBuilder.Spec();
        s.title = "حركات كل مستخدم";
        s.group = ReportBuilder.G_USER;
        s.details = true;
        l.add(new Object[]{"حركات اليوم لكل مستخدم", 0, s});
        s = new ReportBuilder.Spec();
        s.title = "شرائح المبالغ";
        s.group = ReportBuilder.G_SIZE;
        s.kind = ReportBuilder.K_TRANSFERS;
        l.add(new Object[]{"توزيع الحوالات حسب المبلغ — 12 شهرًا", 6, s});
        s = new ReportBuilder.Spec();
        s.title = "الملغاة والمعدّلة";
        s.status = ReportBuilder.S_EDITED;
        s.group = ReportBuilder.G_NONE;
        l.add(new Object[]{"المعدّلة وتصحيحاتها — هذا الشهر", 3, s});
        s = new ReportBuilder.Spec();
        s.title = "أكثر المستفيدين";
        s.group = ReportBuilder.G_PERSON;
        s.sort = ReportBuilder.SORT_COUNT;
        s.top = 30;
        l.add(new Object[]{"أكثر 30 مستفيدًا — 12 شهرًا", 6, s});
        s = new ReportBuilder.Spec();
        s.title = "أسبوعيًا";
        s.group = ReportBuilder.G_WEEK;
        s.compare = true;
        l.add(new Object[]{"ملخص أسبوعي — 3 أشهر", 5, s});
        // 10.12: قص العملات والحركات بين مكتبين (القوالب نفسها في الهاتف)
        for (ReportBuilder.Preset p : ReportBuilder.presets1012()) l.add(new Object[]{p.name + " — " + ReportBuilder.PERIODS[p.period], p.period, p.spec});
        return l;
    }

    JSONObject stored() {
        try {
            String s = db.setting(KEY, "");
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    void loadPresets() {
        DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
        m.addElement("— اختر قالبًا —");
        for (Object[] b : builtIn()) m.addElement(READY + b[0]);
        JSONObject st = stored();
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = st.keys(); it.hasNext(); ) names.add(it.next());
        Collections.sort(names);
        for (String n : names) m.addElement(n);
        presets.setModel(m);
    }

    void loadPreset(String name) {
        if (name.startsWith(READY)) {
            for (Object[] b : builtIn()) if (name.equals(READY + b[0])) {
                apply((ReportBuilder.Spec) b[2]);
                period.setSelectedIndex((Integer) b[1]);
                run();
                return;
            }
        }
        JSONObject j = stored().optJSONObject(name);
        if (j == null) return;
        ReportBuilder.Spec s = ReportBuilder.Spec.fromJson(j);
        int p = j.optInt("p", -1);
        if (p >= 0 && p < Ui.PERIODS.length - 1) {
            s.from = 0;
            s.to = 0;
        }
        apply(s);
        if (p >= 0 && p < Ui.PERIODS.length - 1) period.setSelectedIndex(p);
        run();
    }

    void savePreset() {
        try {
            ReportBuilder.Spec s = spec();
            String name = Ui.input(this, "حفظ كقالب", "اسم القالب", s.title.isEmpty() ? "" : s.title);
            if (name == null || name.trim().isEmpty()) return;
            JSONObject j = s.toJson();
            int p = period.getSelectedIndex();
            j.put("p", p);
            if (p != Ui.PERIODS.length - 1) {
                j.put("from", 0);
                j.put("to", 0);
            }
            JSONObject st = stored();
            st.put(name.trim(), j);
            db.setSetting(KEY, st.toString());
            loadPresets();
            presets.setSelectedItem(name.trim());
            Ui.toast("حُفظ القالب «" + name.trim() + "»");
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void deletePreset() {
        String n = (String) presets.getSelectedItem();
        if (n == null || presets.getSelectedIndex() == 0 || n.startsWith(READY)) {
            Ui.toast("اختر قالبًا محفوظًا (القوالب الجاهزة لا تُحذف)");
            return;
        }
        if (!Ui.confirm(this, "حذف القالب", "حذف القالب «" + n + "»؟", "حذف")) return;
        JSONObject st = stored();
        st.remove(n);
        db.setSetting(KEY, st.toString());
        loadPresets();
    }

    // ===================================================================================
    // التشغيل
    // ===================================================================================

    Report build(ReportBuilder.Spec s) {
        return ReportBuilder.build(s, db.builderData(), db.setting("office_name", ""));
    }

    void run() {
        final ReportBuilder.Spec s;
        try {
            s = spec();
        } catch (Exception e) {
            Ui.error(this, e);
            return;
        }
        Ui.busy(this, "جارٍ بناء التقرير…", p -> build(s), r -> {
            last = r;
            out.removeAll();
            out.add(Ui.label(r.title, 19, Ui.primaryInk, true));
            out.add(Ui.gap(6));
            out.add(ReportPanel.render(r, textWidth()));
            Ui.rtl(out);
            out.revalidate();
            out.repaint();
            SwingUtilities.invokeLater(() -> {
                Rectangle b = out.getBounds();
                body.scrollRectToVisible(new Rectangle(b.x, b.y, 10, 10));
            });
        });
    }

    void export() {
        final ReportBuilder.Spec s;
        try {
            s = spec();
        } catch (Exception e) {
            Ui.error(this, e);
            return;
        }
        Export.dialog(this, app, s.title.isEmpty() ? "تقرير مخصص" : s.title, () -> build(s));
    }
}
