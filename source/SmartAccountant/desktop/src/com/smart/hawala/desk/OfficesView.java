package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** المكاتب: جدول بكل المكاتب وأرصدتها لكل عملة، بحث، إضافة وتعديل، وفتح صفحة المكتب بنقرتين. */
final class OfficesView extends View {
    final Ui.Field search = Ui.field("بحث باسم المكتب أو الهاتف…");
    /** 10.18: شرائح التصفية كالهاتف: الكل (النشطة)، المثبّتة، لنا، علينا، الموقوفة. */
    final Ui.Seg which = new Ui.Seg(new String[]{"الكل", "المثبّتة", "لنا", "علينا", "الموقوفة"}, null, 0);
    /** 10.18: عرض المكاتب بطاقات (كالهاتف، الافتراضي) أو جدولًا. */
    final Ui.Seg viewMode = new Ui.Seg(new String[]{"بطاقات", "جدول"}, null, 0);
    final JPanel grid = new JPanel(new GridLayout(0, 3, 14, 14));
    final JPanel body = new JPanel(new CardLayout());
    JScrollPane cardsScroll;
    /** 9.0: الترتيب: أبجديًا، الأكبر رصيدًا، الأصغر رصيدًا، الأكثر استخدامًا، آخر ما فتحته. */
    final JComboBox<String> order = Ui.combo(OfficeOrder.NAMES);
    JTable table;
    List<Model.OfficeTotals> shown = new ArrayList<>();

    OfficesView(App app) {
        super(app, "المكاتب");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fill(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fill(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { fill(); }
        });
        which.listener = i -> fill();
        viewMode.listener = i -> {
            db.setSettingQuiet("desk_offices_view", i == 1 ? "table" : "cards");
            ((CardLayout) body.getLayout()).show(body, i == 1 ? "table" : "cards");
            fill();
        };
        order.addActionListener(e -> {
            db.setSettingQuiet("office_sort", "" + order.getSelectedIndex());
            fill();
        });
    }

    @Override void build() {
        JPanel top = Ui.row(search, Box.createHorizontalStrut(8), Ui.label("الترتيب", 13, Ui.muted, true), order, Box.createHorizontalStrut(8), viewMode);
        order.setMaximumSize(new Dimension(190, 38));
        viewMode.setMaximumSize(new Dimension(180, 40));
        viewMode.setPreferredSize(new Dimension(180, 40));
        which.setMaximumSize(new Dimension(560, 42));
        which.setPreferredSize(new Dimension(560, 42));
        JPanel top2 = new JPanel();
        top2.setLayout(new BoxLayout(top2, BoxLayout.Y_AXIS));
        top2.setOpaque(false);
        top2.add(top);
        top2.add(Box.createVerticalStrut(8));
        top2.add(Ui.row(which, Box.createHorizontalGlue()));
        order.setSelectedIndex(OfficeOrder.clamp((int) db.settingLong("office_sort", 0)));
        top2.setBorder(new javax.swing.border.EmptyBorder(6, 24, 10, 24));
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Ui.bg);
        center.add(top2, BorderLayout.NORTH);
        table = Ui.table(new DefaultTableModel());
        JScrollPane sp = Ui.tableBox(table);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Ui.bg);
        holder.setBorder(new javax.swing.border.EmptyBorder(0, 24, 20, 24));
        holder.add(sp);
        // البطاقات: شبكة تتكيف أعمدتها مع العرض، والصفحة تتمرر
        grid.setOpaque(false);
        JPanel gridTop = new JPanel(new BorderLayout());
        gridTop.setOpaque(false);
        gridTop.add(grid, BorderLayout.NORTH);
        gridTop.setBorder(new javax.swing.border.EmptyBorder(4, 24, 24, 24));
        cardsScroll = new JScrollPane(gridTop);
        cardsScroll.setBorder(null);
        cardsScroll.getViewport().setBackground(Ui.bg);
        cardsScroll.getVerticalScrollBar().setUnitIncrement(24);
        cardsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        cardsScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { columns(); }
        });
        body.setOpaque(false);
        body.add(cardsScroll, "cards");
        body.add(holder, "table");
        boolean tableMode = "table".equals(db.setting("desk_offices_view", "cards"));
        viewMode.set(tableMode ? 1 : 0);
        ((CardLayout) body.getLayout()).show(body, tableMode ? "table" : "cards");
        center.add(body, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) openSelected();
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "open");
        table.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openSelected(); }
        });
    }

    void openSelected() {
        int r = table.getSelectedRow();
        if (r < 0) return;
        app.go("office", shown.get(table.convertRowIndexToModel(r)).officeId);
    }

    @Override void refresh() {
        actions.removeAll();
        if (can(Auth.Perm.OFFICES)) action(Ui.primary("+ مكتب جديد", e -> edit(this, app, null, () -> refresh())));
        action(Ui.ghost("تصدير الأرصدة", e -> app.go("balances")));
        action(Ui.ghost(app.hidden() ? "إظهار المبالغ" : "إخفاء المبالغ", e -> {
            app.toggleHidden();
            refresh();
        }));
        fill();
        Entitlements en = License.current;
        if (!en.pro) {
            QuotaState q = db.quotaNow();
            subtitle.setText("النسخة المجانية: " + q.offices + " من " + en.maxOffices() + " مكاتب");
        } else subtitle.setText(db.officeCount() + " مكتبًا");
        done();
    }

    void fill() {
        if (table == null) return;
        Map<String, Integer> dec = db.decimalsMap();
        List<Model.OfficeTotals> all = db.officeTotals(null);
        // الترتيب المختار (المثبّتة أولًا دائمًا)
        Map<Long, Map<String, Long>> balAll = db.officeBalances();
        List<Model.Office> ordered = db.offices();
        OfficeOrder.sort(ordered, order.getSelectedIndex(), db.officeOrderInputs(balAll));
        final Map<Long, Integer> pos = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) pos.put(ordered.get(i).id, i);
        all.sort(Comparator.comparingInt(o -> pos.getOrDefault(o.officeId, Integer.MAX_VALUE)));
        Map<String, java.math.BigDecimal> rates = db.usdRates();
        Set<String> curs = new TreeSet<>();
        for (Model.OfficeTotals o : all) for (Map.Entry<String, long[]> e : o.perCurrency.entrySet()) if (e.getValue()[2] != 0 || e.getValue()[0] != 0 || e.getValue()[1] != 0) curs.add(e.getKey());
        List<String> cols = new ArrayList<>(Arrays.asList("المكتب", "الحالة"));
        for (String c : curs) cols.add("رصيد " + c);
        cols.add("الميزان ≈ USD");
        DefaultTableModel m = new DefaultTableModel(cols.toArray(), 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }

            @Override public Class<?> getColumnClass(int c) { return c >= 2 && c < getColumnCount() - 1 ? Ui.Money.class : Object.class; }
        };
        String q = OwnerReports.norm(search.getText());
        grid.removeAll();
        Map<Long, Model.Office> offices = new HashMap<>();
        for (Model.Office o : db.offices()) offices.put(o.id, o);
        Ui.Keep keep = Ui.Keep.of(table, shown, o -> o.officeId);   // 10.5 (§6)
        shown = new ArrayList<>();
        int w = Math.max(0, which.selected);
        for (Model.OfficeTotals o : all) {
            Model.Office of = offices.get(o.officeId);
            boolean hasUs = false, neg = false;
            for (long[] v : o.perCurrency.values()) {
                hasUs |= v[2] > 0;
                neg |= v[2] < 0;
            }
            if (w != 4 && !o.active) continue;
            if (w == 4 && o.active) continue;
            if (w == 1 && !o.pinned) continue;
            if (w == 2 && !hasUs) continue;
            if (w == 3 && !neg) continue;
            if (!q.isEmpty()) {
                String hay = OwnerReports.norm(o.name + " " + (of == null ? "" : Model.nz(of.phone) + " " + Model.nz(of.shortName)));
                if (!hay.contains(q)) continue;
            }
            shown.add(o);
            Object[] row = new Object[cols.size()];
            row[0] = (o.pinned ? "مثبّت · " : "") + o.name;
            row[1] = o.active ? "نشط" : "موقوف";
            int i = 2;
            Map<String, Long> net = new TreeMap<>();
            for (String c : curs) {
                long[] v = o.perCurrency.get(c);
                row[i++] = app.hidden() ? ((v == null || v[2] == 0) ? "" : "••••") : new Ui.Money(v == null ? 0 : v[2], dec.getOrDefault(c, 2), true);
                if (v != null) net.put(c, v[2]);
            }
            Ledger.Valuation val = Ledger.valueOffice(net, rates, dec);
            long whole = val.netUsd().setScale(0, java.math.RoundingMode.HALF_EVEN).longValue();
            row[i] = app.hidden() ? "••••" : !val.missingRates.isEmpty() ? (whole == 0 ? "" : Model.bal(whole, 0) + " ") + "(بلا سعر: " + String.join("، ", val.missingRates) + ")"
                    : new Ui.Money(whole, 0, true);
            m.addRow(row);
            grid.add(card(o, net, val, whole, dec));
        }
        if (shown.isEmpty()) {
            JLabel none = Ui.label(q.isEmpty() ? "لا مكاتب في هذا التصنيف." : "لا مكتب يطابق البحث.", 14, Ui.muted, false);
            grid.add(none);
        }
        grid.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        columns();
        grid.revalidate();
        grid.repaint();
        table.setModel(m);
        table.setDefaultRenderer(Ui.Money.class, new Ui.Cell(SwingConstants.CENTER));
        Ui.widths(table, 260, 80);
        keep.restore(table, shown, o -> o.officeId);
        table.applyComponentOrientation(Ui.RTL);
    }

    /** عدد أعمدة البطاقات حسب عرض الشاشة (بطاقة لكل ~360 بكسل). */
    void columns() {
        if (cardsScroll == null) return;
        int wd = cardsScroll.getViewport().getWidth() - 48;
        int cols = Math.max(1, Math.min(4, wd / 360));
        GridLayout gl = (GridLayout) grid.getLayout();
        if (gl.getColumns() != cols) {
            gl.setColumns(cols);
            grid.revalidate();
        }
    }

    /** 10.18: بطاقة مكتب كالهاتف: حرف المكتب، الاسم والتثبيت، شارات الأرصدة الملونة، والميزان بالدولار. */
    JComponent card(Model.OfficeTotals o, Map<String, Long> net, Ledger.Valuation val, long whole, Map<String, Integer> dec) {
        // بطاقة واحدة واضحة: إطار مستدير وظل خفيف، الحرف يمينًا والمحتوى بجانبه (اتجاه عربي صريح لكل بطاقة)
        final boolean pin = o.pinned;
        JPanel c = new JPanel(new BorderLayout(14, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth() - 1, h = getHeight() - 1;
                g2.setColor(new Color(0, 0, 0, Ui.dark ? 60 : 18));
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(1, 3, w - 1, h - 2, 22, 22));
                g2.setColor(Ui.surface);
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h - 2, 22, 22));
                g2.setColor(pin ? Ui.mix(Ui.primary, Ui.surface, 0.35f) : Ui.line);
                g2.setStroke(new BasicStroke(pin ? 2f : 1.2f));
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 3, 22, 22));
                g2.dispose();
            }
        };
        c.setOpaque(false);
        c.setBorder(new javax.swing.border.EmptyBorder(16, 18, 18, 18));
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel name = Ui.label(o.name, 18, Ui.ink, true);
        List<Component> head = new ArrayList<>(Arrays.asList(name));
        if (!o.active) head.add(new Ui.Pill("موقوف", Ui.warnBg, Ui.warnInk, 12));
        head.add(Box.createHorizontalGlue());
        if (o.pinned) head.add(new JLabel(new Icons("pin", 18, Ui.goldInk)));
        col.add(Ui.row(head.toArray(new Component[0])));
        col.add(Box.createVerticalStrut(8));
        JPanel pills = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        pills.setOpaque(false);
        pills.setAlignmentX(Component.RIGHT_ALIGNMENT);
        boolean any = false;
        for (Map.Entry<String, Long> e : net.entrySet()) {
            if (e.getValue() == 0) continue;
            any = true;
            pills.add(Ui.balance(e.getValue(), dec.getOrDefault(e.getKey(), 2), e.getKey(), app.hidden()));
        }
        if (!any) pills.add(Ui.label("لا رصيد", 13, Ui.muted, false));
        col.add(pills);
        if (any) {
            String u = app.hidden() ? "الميزان ≈ •••• USD" : !val.missingRates.isEmpty() ? "الميزان: بلا سعر لـ " + String.join("، ", val.missingRates)
                    : whole == 0 ? "الميزان ≈ صفر USD" : "الميزان ≈ " + Ui.ltr(Model.fmt(Math.abs(whole), 0) + " USD") + " " + (whole > 0 ? "لنا" : "علينا");
            col.add(Box.createVerticalStrut(4));
            col.add(Ui.label(u, 14, !val.missingRates.isEmpty() ? Ui.muted : whole > 0 ? Ui.green : whole < 0 ? Ui.red : Ui.muted, true));
        }
        JPanel av = new JPanel(new BorderLayout());
        av.setOpaque(false);
        av.add(new Ui.Avatar(o.name, 54), BorderLayout.NORTH);
        c.add(av, BorderLayout.LINE_START);
        c.add(col, BorderLayout.CENTER);
        c.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.setToolTipText("انقر لفتح المكتب — زر الفأرة الأيمن: تثبيت، حركة جديدة، مطابقة رصيد، كشف");
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) app.go("office", o.officeId);
            }

            @Override public void mousePressed(MouseEvent e) { pop(e); }

            @Override public void mouseReleased(MouseEvent e) { pop(e); }

            void pop(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                JPopupMenu m = new JPopupMenu();
                m.add(App.item(o.pinned ? "إلغاء التثبيت" : "تثبيت", 0, () -> {
                    db.setPinned(o.officeId, !o.pinned);
                    fill();
                }));
                if (o.active && can(Auth.Perm.TX_ADD)) m.add(App.item("حركة جديدة", 0, () -> app.go("tx", new TxView.Prefill(o.officeId))));
                m.add(App.item("مطابقة رصيد", 0, () -> {
                    app.go("office", o.officeId);
                    View v = app.views.get("office");
                    Model.Office of = db.office(o.officeId);
                    if (v instanceof OfficeView && of != null) ((OfficeView) v).sendRecon(of);
                }));
                m.add(App.item("كشف حساب", 0, () -> app.go("statement", o.officeId)));
                Ui.rtl(m);
                m.show(e.getComponent(), e.getX(), e.getY());
            }
        });
        return c;
    }

    /** بطاقة مكتب مختصرة (للرئيسية). */
    static JComponent officeCard(App app, Model.OfficeTotals o, Map<String, Integer> dec) {
        Ui.Card c = Ui.card();
        c.add(Ui.label(o.name, 16, Ui.primaryInk, true));
        boolean any = false;
        for (Map.Entry<String, long[]> e : o.perCurrency.entrySet()) {
            long b = e.getValue()[2];
            if (b == 0) continue;
            any = true;
            c.add(Ui.label(e.getKey() + ": " + (app.hidden() ? "••••" : Model.bal(b, dec.getOrDefault(e.getKey(), 2))), 14, b > 0 ? Ui.green : Ui.red, true));
        }
        if (!any) c.add(Ui.label("لا رصيد", 13, Ui.muted, false));
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { app.go("office", o.officeId); }
        });
        return c;
    }

    // ===================================================================================
    // إضافة وتعديل
    // ===================================================================================

    static void edit(Component parent, App app, Model.Office old, Runnable after) {
        Db db = app.db;
        if (!app.require(Auth.Perm.OFFICES)) return;
        if (old == null) {
            Entitlements en = License.current;
            String no = db.quotaNow().officeRefusal(en);
            if (no != null) {
                Ui.limit(parent, no);
                return;
            }
        }
        JPanel c = Ui.column();
        Ui.Field name = Ui.field("اسم المكتب"), sh = Ui.field("اسم مختصر (اختياري)"), phone = Ui.field("الهاتف"), loc = Ui.field("المدينة / العنوان"),
                contact = Ui.field("الشخص المسؤول"), notes = Ui.field("ملاحظات");
        if (old != null) {
            name.setText(Model.nz(old.name));
            sh.setText(Model.nz(old.shortName));
            phone.setText(Model.nz(old.phone));
            loc.setText(Model.nz(old.location));
            contact.setText(Model.nz(old.contact));
            notes.setText(Model.nz(old.notes));
        }
        JPanel grid = new JPanel(new GridLayout(0, 2, 10, 8));
        grid.setOpaque(false);
        grid.add(Ui.labeled("الاسم *", name));
        grid.add(Ui.labeled("اسم مختصر", sh));
        grid.add(Ui.labeled("الهاتف", phone));
        grid.add(Ui.labeled("المدينة", loc));
        grid.add(Ui.labeled("المسؤول", contact));
        grid.add(Ui.labeled("ملاحظات", notes));
        grid.setAlignmentX(Component.RIGHT_ALIGNMENT);
        c.add(grid);
        c.add(Ui.gap(12));
        c.add(Ui.label("العملات المتعامل بها، وحد التنبيه لكل عملة (اختياري)", 13, Ui.muted, true));
        List<Model.Currency> currencies = db.currencies(false);
        List<String> current = old == null ? new ArrayList<>() : db.officeCurrencies(old.id);
        Map<String, Db.Limit> limits = old == null ? new HashMap<>() : db.officeLimits(old.id);
        Map<String, JCheckBox> on = new LinkedHashMap<>();
        Map<String, Ui.Field[]> lim = new LinkedHashMap<>();
        Map<String, Ui.Field> open = new LinkedHashMap<>();
        Map<String, JComboBox<String>> openSide = new LinkedHashMap<>();
        JPanel cg = new JPanel(new GridLayout(0, old == null ? 5 : 3, 8, 6));
        cg.setOpaque(false);
        cg.setAlignmentX(Component.RIGHT_ALIGNMENT);
        cg.add(Ui.label("العملة", 12, Ui.muted, true));
        cg.add(Ui.label("حد ما لنا", 12, Ui.muted, true));
        cg.add(Ui.label("حد ما علينا", 12, Ui.muted, true));
        if (old == null) {
            cg.add(Ui.label("رصيد سابق", 12, Ui.muted, true));
            cg.add(Ui.label("اتجاهه", 12, Ui.muted, true));
        }
        for (Model.Currency cur : currencies) {
            int d = cur.decimals;
            JCheckBox cb = Ui.check(cur.code + " — " + cur.name, old == null ? currencies.indexOf(cur) < 2 : current.contains(cur.code));
            Db.Limit l = limits.get(cur.code);
            Ui.Field a = Ui.numeric(""), b = Ui.numeric("");
            if (l != null && l.receivable != null) a.setText(Model.plain(l.receivable, d));
            if (l != null && l.payable != null) b.setText(Model.plain(l.payable, d));
            on.put(cur.code, cb);
            lim.put(cur.code, new Ui.Field[]{a, b});
            cg.add(cb);
            cg.add(a);
            cg.add(b);
            if (old == null) {
                Ui.Field ob = Ui.numeric("0");
                JComboBox<String> side = Ui.combo(new String[]{"لنا", "علينا"});
                open.put(cur.code, ob);
                openSide.put(cur.code, side);
                cg.add(ob);
                cg.add(side);
            }
        }
        c.add(cg);
        if (old == null) {
            c.add(Ui.gap(6));
            c.add(Ui.para("«رصيد سابق»: ما كان بينك وبين المكتب قبل البدء بالبرنامج، ويُسجَّل قيدًا مستقلًا يظهر في الكشف.", 12, Ui.muted, 560));
        }
        // 10.16: حدود مجموع الأرصدة مقوّمة بالدولار (كانت تُحفظ ولا تُرى على الكمبيوتر)
        final Ui.Field usdUs = Ui.numeric("بلا حد"), usdThem = Ui.numeric("بلا حد");
        if (old != null && old.usdLimit != null) usdUs.setText(Db.money(old.usdLimit, 2));
        if (old != null && old.usdOweLimit != null) usdThem.setText(Db.money(old.usdOweLimit, 2));
        c.add(Ui.gap(8));
        c.add(Ui.label("حدود مجموع الأرصدة مقوّمة بالدولار (اختياري)", 13, Ui.muted, true));
        JPanel ug = new JPanel(new GridLayout(0, 2, 12, 6));
        ug.setOpaque(false);
        ug.setAlignmentX(Component.RIGHT_ALIGNMENT);
        ug.add(Ui.labeled("حد ما لنا (USD)", usdUs));
        ug.add(Ui.labeled("حد ما علينا (USD)", usdThem));
        c.add(ug);
        c.setPreferredSize(new Dimension(old == null ? 760 : 600, Math.min(620, c.getPreferredSize().height + 20)));
        JScrollPane sp = new JScrollPane(Ui.rtl(c));
        sp.setBorder(null);
        sp.setPreferredSize(new Dimension(old == null ? 790 : 630, Math.min(640, c.getPreferredSize().height + 10)));
        while (Ui.form(parent, old == null ? "مكتب جديد" : "تعديل «" + old.name + "»", sp, "حفظ")) {
            try {
                String n = name.val();
                if (n.isEmpty()) throw new IllegalArgumentException("اكتب اسم المكتب");
                for (Model.Office o : db.offices()) if ((old == null || o.id != old.id) && OwnerReports.norm(o.name).equals(OwnerReports.norm(n)))
                    throw new IllegalArgumentException("يوجد مكتب بالاسم نفسه: «" + o.name + "»");
                List<String> codes = new ArrayList<>();
                Map<String, Db.Limit> lm = new HashMap<>();
                List<Db.Opening> openings = new ArrayList<>();
                for (Model.Currency cur : currencies) {
                    if (!on.get(cur.code).isSelected()) continue;
                    codes.add(cur.code);
                    Ui.Field[] f = lim.get(cur.code);
                    Long ra = f[0].val().isEmpty() ? null : Db.minor(f[0].val(), cur.decimals), pa = f[1].val().isEmpty() ? null : Db.minor(f[1].val(), cur.decimals);
                    if (ra != null || pa != null) lm.put(cur.code, new Db.Limit(ra, pa));
                    if (old == null) {
                        String ov = open.get(cur.code).val();
                        if (!ov.isEmpty()) {
                            long amt = Db.minor(ov, cur.decimals);
                            if (amt > 0) openings.add(new Db.Opening(cur.code, openSide.get(cur.code).getSelectedIndex() == 0 ? "لنا" : "علينا", amt));
                        }
                    }
                }
                if (codes.isEmpty()) throw new IllegalArgumentException("اختر عملة واحدة على الأقل");
                Long uu = usdUs.val().isEmpty() ? null : Long.valueOf(Db.minor(usdUs.val(), 2)), ut = usdThem.val().isEmpty() ? null : Long.valueOf(Db.minor(usdThem.val(), 2));
                if (old == null) db.addOffice(n, sh.val(), phone.val(), loc.val(), contact.val(), notes.val(), uu, ut, codes, lm, openings);
                else db.updateOffice(old.id, n, sh.val(), phone.val(), loc.val(), contact.val(), notes.val(), old.active, uu, ut, codes, lm);
                app.changed();
                if (after != null) after.run();
                Ui.toast(old == null ? "أُضيف المكتب «" + n + "»" : "حُفظ التعديل");
                return;
            } catch (Exception e) {
                Ui.error(parent, e);
            }
        }
    }
}
