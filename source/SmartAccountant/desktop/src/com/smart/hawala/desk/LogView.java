package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** سجل الحركات: تصفية بالفترة والمكتب والعملة والاتجاه والحالة والبحث، وتفاصيل كل حركة (تعديل، إلغاء، صور، اعتماد). */
final class LogView extends View {
    final JComboBox<String> period = Ui.combo(Ui.PERIODS);
    final Ui.DateField from = new Ui.DateField(0), to = new Ui.DateField(0);
    final JComboBox<Object> office = Ui.pickCombo();
    final JComboBox<String> currency = Ui.combo(new String[0]);
    final JComboBox<String> dir = Ui.combo(new String[]{"لنا وعلينا", "لنا فقط", "علينا فقط"});
    final JComboBox<String> status = Ui.combo(new String[]{"النشطة", "مع الملغاة", "بانتظار الاعتماد"});
    final Ui.Field search = Ui.field("بحث: مستفيد، بيان، مكتب، مبلغ، رقم الحركة، محاسب…");
    /** 10.18: تصفية أوسع: نوع الحركة، من سجّلها، ومدى المبلغ. */
    final JComboBox<String> kind = Ui.combo(new String[]{"كل الأنواع", "عادية", "قص عملات", "بين مكتبين", "مع أجرة", "بمرفقات", "معدّلة (مصحّحة)"});
    final JComboBox<String> who = Ui.combo(new String[]{"كل المحاسبين"});
    final Ui.Field minAmt = Ui.numeric("المبلغ من"), maxAmt = Ui.numeric("إلى");
    final JLabel count = Ui.label("", 13, Ui.muted, false);
    JTable table;
    List<Model.Tx> rows = new ArrayList<>();
    JPanel custom;
    boolean loading;

    LogView(App app) {
        super(app, "سجل الحركات");
    }

    @Override void build() {
        office.setFont(Ui.font(14, false));
        office.setComponentOrientation(Ui.RTL);
        office.setMaximumSize(new Dimension(240, 38));
        office.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v instanceof Model.Office ? ((Model.Office) v).name : "كل المكاتب", i, s, f);
                c.setHorizontalAlignment(SwingConstants.RIGHT);
                return c;
            }
        });
        period.setSelectedIndex(3);
        for (JComboBox<?> c : new JComboBox<?>[]{period, currency, dir, status}) c.setMaximumSize(new Dimension(170, 38));
        custom = Ui.row(Ui.label("من", 13, Ui.muted, true), from, Ui.label("إلى", 13, Ui.muted, true), to);
        custom.setMaximumSize(new Dimension(520, 40));
        JPanel f1 = Ui.row(period, custom, office, currency, dir, status);
        for (JComboBox<?> c : new JComboBox<?>[]{kind, who}) c.setMaximumSize(new Dimension(170, 38));
        minAmt.setMaximumSize(new Dimension(120, 38));
        maxAmt.setMaximumSize(new Dimension(120, 38));
        JPanel f2 = Ui.row(search, kind, who, minAmt, maxAmt, Ui.ghost("مسح الفلاتر", e -> clearFilters()));
        JPanel f3 = Ui.row(count);
        JPanel top = Ui.column();
        top.add(f1);
        top.add(Ui.gap(8));
        top.add(f2);
        top.add(Ui.gap(6));
        top.add(f3);
        top.setBorder(new javax.swing.border.EmptyBorder(4, 24, 10, 24));
        table = Ui.table(new DefaultTableModel());
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Ui.bg);
        holder.setBorder(new javax.swing.border.EmptyBorder(0, 24, 20, 24));
        holder.add(Ui.tableBox(table));
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Ui.bg);
        center.add(top, BorderLayout.NORTH);
        center.add(holder, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        Runnable re = () -> {
            if (!loading) load();
        };
        period.addActionListener(e -> {
            custom.setVisible(period.getSelectedIndex() == Ui.PERIODS.length - 1);
            re.run();
        });
        from.onChange = re;
        to.onChange = re;
        office.addActionListener(e -> re.run());
        currency.addActionListener(e -> re.run());
        dir.addActionListener(e -> re.run());
        status.addActionListener(e -> re.run());
        kind.addActionListener(e -> re.run());
        who.addActionListener(e -> re.run());
        minAmt.getDocument().addDocumentListener(TxView.doc(re));
        maxAmt.getDocument().addDocumentListener(TxView.doc(re));
        search.getDocument().addDocumentListener(TxView.doc(re));
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) details(LogView.this, app, rows.get(table.convertRowIndexToModel(table.getSelectedRow())).id);
            }
        });
        custom.setVisible(false);
    }

    void clearFilters() {
        loading = true;
        period.setSelectedIndex(3);
        custom.setVisible(false);
        office.setSelectedIndex(0);
        if (currency.getItemCount() > 0) currency.setSelectedIndex(0);
        dir.setSelectedIndex(0);
        status.setSelectedIndex(0);
        kind.setSelectedIndex(0);
        if (who.getItemCount() > 0) who.setSelectedIndex(0);
        minAmt.setText("");
        maxAmt.setText("");
        search.setText("");
        loading = false;
        load();
    }

    @Override void open(Object arg) {
        if ("pending".equals(arg)) {
            loading = true;
            status.setSelectedIndex(2);
            period.setSelectedIndex(8);
            loading = false;
        }
        refresh();
    }

    @Override void refresh() {
        actions.removeAll();
        action(Ui.ghost("تصدير…", e -> export()));
        if (can(Auth.Perm.TX_ADD)) action(Ui.primary("+ تسجيل حركة", e -> app.go("tx")));
        loading = true;
        Object sel = office.getSelectedItem();
        DefaultComboBoxModel<Object> om = new DefaultComboBoxModel<>();
        om.addElement("الكل");
        for (Model.Office o : db.offices()) om.addElement(o);
        office.setModel(om);
        if (sel instanceof Model.Office) for (int i = 1; i < om.getSize(); i++) if (((Model.Office) om.getElementAt(i)).id == ((Model.Office) sel).id) office.setSelectedIndex(i);
        Object cs = currency.getSelectedItem();
        DefaultComboBoxModel<String> cm = new DefaultComboBoxModel<>();
        cm.addElement("كل العملات");
        for (Model.Currency c : db.currencies(true)) cm.addElement(c.code);
        currency.setModel(cm);
        if (cs != null) currency.setSelectedItem(cs);
        Object ws = who.getSelectedItem();
        DefaultComboBoxModel<String> wm = new DefaultComboBoxModel<>();
        wm.addElement("كل المحاسبين");
        if (db.usersEnabled() && app.can(Auth.Perm.VIEW)) for (Model.User u : db.users(false)) wm.addElement(Db.label(u));
        who.setModel(wm);
        who.setVisible(wm.getSize() > 1);
        if (ws != null) who.setSelectedItem(ws);
        loading = false;
        load();
        done();
    }

    long[] range() {
        int p = period.getSelectedIndex();
        if (p == Ui.PERIODS.length - 1) {
            long f = 0, t = 0;
            try {
                f = from.start();
                t = to.endExclusive();
            } catch (Exception ignored) {
                // تاريخ غير صالح: بلا حد
            }
            return new long[]{f, t == 0 ? Long.MAX_VALUE : t};
        }
        long[] r = Ui.period(p);
        return new long[]{r[0], r[1] == 0 ? Long.MAX_VALUE : r[1]};
    }

    void load() {
        if (table == null) return;
        long[] r = range();
        List<Model.Tx> list;
        if (status.getSelectedIndex() == 2) {
            list = db.pendingTx();
        } else {
            Db.TxFilter f = new Db.TxFilter();
            f.from = r[0];
            f.to = r[1];
            Object o = office.getSelectedItem();
            if (o instanceof Model.Office) f.office = ((Model.Office) o).id;
            if (currency.getSelectedIndex() > 0) f.currency = (String) currency.getSelectedItem();
            if (dir.getSelectedIndex() == 1) f.type = Model.TYPE_DEBIT;
            if (dir.getSelectedIndex() == 2) f.type = Model.TYPE_CREDIT;
            f.includeCancelled = status.getSelectedIndex() == 1;
            list = db.txLog(f);
        }
        String q = OwnerReports.norm(search.getText());
        Ui.Keep keep = Ui.Keep.of(table, rows, t -> t.id);   // 10.5 (§6)
        rows = new ArrayList<>();
        // 10.3: من لا يملك «العرض» يرى حركاته هو فقط
        boolean mineOnly = !app.can(Auth.Perm.VIEW);
        Model.User meU = SmartApp.currentUser;
        String me = meU == null ? Db.currentUserName : meU.name;
        // 10.18: فلاتر النوع والمحاسب ومدى المبلغ، والبحث يشمل اسم المكتب والمبلغ واسم المحاسب
        int kd = kind.getSelectedIndex();
        String whoSel = who.isVisible() && who.getSelectedIndex() > 0 ? String.valueOf(who.getSelectedItem()) : null;
        java.math.BigDecimal lo = amountOf(minAmt.getText()), hi = amountOf(maxAmt.getText());
        Map<Long, Integer> att = kd == 5 ? db.attachmentCounts(null) : null;
        Map<Long, String> onames = db.officeNames();
        Map<String, Integer> decs = db.decimalsMap();
        for (Model.Tx t : list) {
            // 10.5 (§7): «حركاته هو» برقمه الثابت (والقديمة بالاسم)
            if (mineOnly && !(meU != null ? Db.enteredBy(t, meU) : t.realUser().equals(me))) continue;
            if (kd == 1 && t.isLinked()) continue;
            if (kd == 2 && !t.isFx()) continue;
            if (kd == 3 && !t.isTransfer()) continue;
            if (kd == 4 && (Model.FEE_NONE.equals(t.feeType) || t.fee == 0)) continue;
            if (kd == 5 && (att.get(t.id) == null || att.get(t.id) == 0)) continue;
            if (kd == 6 && t.replacesId == null) continue;
            if (whoSel != null && !whoSel.equals(Model.nz(t.userName))) continue;
            if (lo != null || hi != null) {
                java.math.BigDecimal v = java.math.BigDecimal.valueOf(t.amount, decs.getOrDefault(t.currency, 2));
                if (lo != null && v.compareTo(lo) < 0) continue;
                if (hi != null && v.compareTo(hi) > 0) continue;
            }
            if (!q.isEmpty()) {
                String hay = OwnerReports.norm(Model.nz(t.person) + " " + Model.nz(t.description) + " " + Model.nz(t.notes) + " " + Model.nz(t.dest) + " " + Model.nz(t.service)
                        + " " + Model.shortRef(t.uuid) + " " + Model.nz(onames.get(t.officeId)) + " " + Model.nz(t.userName) + " " + t.currency + " "
                        + Model.plain(t.amount, decs.getOrDefault(t.currency, 2)) + " " + Model.fmt(t.amount, decs.getOrDefault(t.currency, 2)));
                boolean all = true;
                for (String w : q.split(" ")) all &= w.isEmpty() || hay.contains(w);
                if (!all) continue;
            }
            rows.add(t);
        }
        // 10.18: مكتب واحد وعملة واحدة ⇒ عمود «الرصيد بعدها»
        Object selO = office.getSelectedItem();
        Map<Long, Long> after = selO instanceof Model.Office && currency.getSelectedIndex() > 0 ? db.balancesAfter(((Model.Office) selO).id, (String) currency.getSelectedItem()) : null;
        table.setModel(model(app, rows, true, after, after == null ? 2 : db.decimals((String) currency.getSelectedItem())));
        styleTxTable(table, true);
        keep.restore(table, rows, t -> t.id);
        Map<String, long[]> tot = new TreeMap<>();
        for (Model.Tx t : rows) if (t.isActive()) {
            long[] x = tot.computeIfAbsent(t.currency, k -> new long[2]);
            if (Model.TYPE_DEBIT.equals(t.type)) x[0] += t.amount;
            else x[1] += t.amount;
        }
        StringBuilder sb = new StringBuilder(rows.size() + " حركة");
        for (Map.Entry<String, long[]> e : tot.entrySet()) {
            int d = db.decimals(e.getKey());
            sb.append("   •   ").append(e.getKey()).append(": لنا ").append(Model.fmt(e.getValue()[0], d)).append(" / علينا ").append(Model.fmt(e.getValue()[1], d));
        }
        count.setText(sb.toString());
    }

    /** مبلغ مكتوب في خانة الفلتر (يقبل الفواصل والأرقام العربية)، أو null. */
    static java.math.BigDecimal amountOf(String s) {
        try {
            StringBuilder b = new StringBuilder();
            for (char ch : Model.nz(s).toCharArray()) {
                if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + ch - '٠'));
                else if (ch >= '0' && ch <= '9' || ch == '.') b.append(ch);
                else if (ch == '٫') b.append('.');
            }
            String v = b.toString();
            return v.isEmpty() ? null : new java.math.BigDecimal(v);
        } catch (Exception e) {
            return null;
        }
    }

    void export() {
        List<Model.Tx> snap = new ArrayList<>(rows);
        List<String> filters = new ArrayList<>();
        filters.add("الفترة: " + period.getSelectedItem());
        if (office.getSelectedItem() instanceof Model.Office) filters.add("المكتب: " + ((Model.Office) office.getSelectedItem()).name);
        if (currency.getSelectedIndex() > 0) filters.add("العملة: " + currency.getSelectedItem());
        if (!search.val().isEmpty()) filters.add("بحث: " + search.val());
        if (kind.getSelectedIndex() > 0) filters.add("النوع: " + kind.getSelectedItem());
        if (who.isVisible() && who.getSelectedIndex() > 0) filters.add("المحاسب: " + who.getSelectedItem());
        if (!minAmt.val().isEmpty() || !maxAmt.val().isEmpty()) filters.add("المبلغ: " + (minAmt.val().isEmpty() ? "…" : minAmt.val()) + " – " + (maxAmt.val().isEmpty() ? "…" : maxAmt.val()));
        Export.dialog(this, app, "سجل الحركات", () -> Reports.txList("سجل الحركات", filters, snap, db.officeNames(), db.decimalsMap(), db.setting("office_name", ""), Clock.now()));
    }

    // ===================================================================================
    // جدول الحركات (مشترك)
    // ===================================================================================

    static DefaultTableModel model(App app, List<Model.Tx> txs, boolean withOffice) { return model(app, txs, withOffice, null, 2); }

    /** 10.18: after != null يضيف عمود «الرصيد بعدها» (رصيد المكتب بالعملة المختارة بعد كل حركة) بعد عمود الأجرة. */
    static DefaultTableModel model(App app, List<Model.Tx> txs, boolean withOffice, Map<Long, Long> after, int afterDec) {
        Db db = app.db;
        Map<Long, String> names = db.officeNames();
        Map<String, Integer> dec = db.decimalsMap();
        List<String> cols = new ArrayList<>(Arrays.asList("التاريخ"));
        if (withOffice) cols.add("المكتب");
        cols.addAll(Arrays.asList("لنا", "علينا", "العملة", "الأجرة"));
        if (after != null) cols.add("الرصيد بعدها");
        cols.addAll(Arrays.asList("المستفيد", "البيان", "الحالة"));
        DefaultTableModel m = new DefaultTableModel(cols.toArray(), 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }

            @Override public Class<?> getColumnClass(int c) {
                String n = getColumnName(c);
                return "لنا".equals(n) || "علينا".equals(n) ? Ui.Money.class : String.class;
            }
        };
        for (Model.Tx t : txs) {
            int d = dec.getOrDefault(t.currency, 2);
            boolean us = Model.TYPE_DEBIT.equals(t.type);
            List<Object> r = new ArrayList<>();
            r.add(Model.date(t.createdAt));
            if (withOffice) r.add(names.getOrDefault(t.officeId, ""));
            r.add(new Ui.Money(us ? t.amount : 0, d, false));
            r.add(new Ui.Money(us ? 0 : t.amount, d, false));
            r.add(t.currency);
            r.add(t.hasFee() ? (Model.FEE_US.equals(t.feeType) ? "لنا " : "علينا ") + Model.fmt(t.fee, dec.getOrDefault(t.feeCurrency, 2)) + " " + t.feeCurrency : "");
            if (after != null) {
                Long b = after.get(t.id);
                // afterDec < 0: كل العملات معًا ⇒ رصيد عملة الحركة نفسها مع رمزها
                r.add(b == null ? "" : app.hidden() ? "••••" : afterDec < 0 ? (b > 0 ? "لنا " : b < 0 ? "علينا " : "") + Ui.ltr(Model.fmt(Math.abs(b), d) + " " + t.currency) : Model.bal(b, afterDec));
            }
            r.add(Model.nz(t.person));
            r.add(Ui.cut(MessageParser.compact(Model.nz(t.description), t.person), 90));
            r.add(statusName(t));
            m.addRow(r.toArray());
        }
        return m;
    }

    static String statusName(Model.Tx t) {
        if (t.isActive()) return t.isFx() ? "تبديل عملة" : t.isTransfer() ? "تحويل بين مكتبين" : t.replacesId != null ? "مُصحَّحة" : "نشطة";
        if (t.isPending()) return "بانتظار الاعتماد";
        if (t.isRejected()) return "مرفوضة";
        if ("CANCELLED".equals(t.status)) return "ملغاة";
        return Model.nz(t.status);
    }

    static void styleTxTable(JTable t, boolean withOffice) {
        t.setDefaultRenderer(Ui.Money.class, new Ui.Cell(SwingConstants.CENTER) {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
                String n = tb.getColumnName(col);
                if (v instanceof Ui.Money && ((Ui.Money) v).v != 0) c.setForeground("لنا".equals(n) ? Ui.green : Ui.red);
                return c;
            }
        });
        boolean bal = false;
        for (int i = 0; i < t.getColumnCount(); i++) if ("الرصيد بعدها".equals(t.getColumnName(i))) bal = true;
        if (bal) {
            if (withOffice) Ui.widths(t, 152, 140, 95, 95, 55, 115, 135, 140, 280, 85);
            else Ui.widths(t, 152, 95, 95, 55, 115, 135, 140, 300, 85);
            // الرصيد بلونه: لنا أخضر، علينا أحمر
            t.getColumn("الرصيد بعدها").setCellRenderer(new Ui.Cell(SwingConstants.CENTER) {
                @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
                    Component c = super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
                    String x = String.valueOf(v);
                    c.setForeground(x.contains("لنا") ? Ui.green : x.contains("علينا") ? Ui.red : Ui.muted);
                    c.setFont(Ui.font(13.5f, true));
                    return c;
                }
            });
        } else if (withOffice) Ui.widths(t, 152, 150, 100, 100, 55, 120, 150, 330, 85);
        else Ui.widths(t, 152, 100, 100, 55, 120, 150, 380, 85);
        t.applyComponentOrientation(Ui.RTL);
    }

    /** 10.5 (§6): الحركات المحددة في جدول كل شاشة (بمفتاح الشاشة)، تُعاد بعد تحديث الجدول أو الرجوع إليه. */
    static final java.util.Map<String, java.util.Set<Long>> SELECTED = new java.util.HashMap<>();

    static JTable txTable(App app, List<Model.Tx> txs, boolean withOffice) { return txTable(app, txs, withOffice, null, 2); }

    static JTable txTable(App app, List<Model.Tx> txs, boolean withOffice, Map<Long, Long> after, int afterDec) {
        JTable t = Ui.table(model(app, txs, withOffice, after, afterDec));
        styleTxTable(t, withOffice);
        if (after != null) {
            // «الرصيد بعدها» بعرض يتسع للمبلغ ورمز العملة
            for (int i = 0; i < t.getColumnCount(); i++) if ("الرصيد بعدها".equals(t.getColumnName(i))) {
                t.getColumnModel().getColumn(i).setPreferredWidth(185);
                t.getColumnModel().getColumn(i).setMinWidth(150);
            }
            // والتاريخ كاملًا (لا «05:…»)
            t.getColumnModel().getColumn(0).setMinWidth(150);
        }
        final String key = String.valueOf(app.current);
        java.util.Set<Long> keep = SELECTED.get(key);
        if (keep != null && !keep.isEmpty()) {
            for (int i = 0; i < txs.size(); i++) {
                if (!keep.contains(txs.get(i).id)) continue;
                int v = t.convertRowIndexToView(i);
                if (v >= 0) t.addRowSelectionInterval(v, v);
            }
        }
        t.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            java.util.Set<Long> sel = new java.util.HashSet<>();
            for (int r : t.getSelectedRows()) {
                int m = t.convertRowIndexToModel(r);
                if (m >= 0 && m < txs.size()) sel.add(txs.get(m).id);
            }
            SELECTED.put(key, sel);
        });
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && t.getSelectedRow() >= 0) details(t, app, txs.get(t.convertRowIndexToModel(t.getSelectedRow())).id);
            }
        });
        return t;
    }

    // ===================================================================================
    // تفاصيل حركة
    // ===================================================================================

    static void details(Component parent, App app, long id) {
        Db db = app.db;
        Model.Tx t = db.tx(id);
        if (t == null) return;
        int d = db.decimals(t.currency);
        Model.Office o = db.office(t.officeId);
        StringBuilder b = new StringBuilder();
        b.append(Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ").append(Model.fmt(t.amount, d)).append(" ").append(t.currency).append("\n");
        b.append("المكتب: ").append(o == null ? "" : o.name).append("\n");
        b.append("التاريخ: ").append(Model.date(t.createdAt)).append("\n");
        if (t.hasFee()) b.append(t.feeType).append(": ").append(Model.fmt(t.fee, db.decimals(t.feeCurrency))).append(" ").append(t.feeCurrency).append("\n");
        if (!Model.nz(t.person).isEmpty()) b.append("المستفيد: ").append(t.person).append("\n");
        if (!Model.nz(t.service).isEmpty() || !Model.nz(t.dest).isEmpty()) b.append("الخدمة/الوجهة: ").append(Model.nz(t.service)).append(" ").append(Model.nz(t.dest)).append("\n");
        if (t.isFx()) b.append("تبديل عملة بسعر ").append(t.fxRate).append("\n");
        else if (t.isTransfer()) b.append("ساق من تحويل بين مكتبين").append(Model.nz(t.dest).isEmpty() ? "" : " (الطرف الآخر: «" + t.dest + "»)").append(" — تُلغى وتُعتمد الساقان معًا\n");
        b.append("الحالة: ").append(statusName(t)).append("\n");
        // 10.5 (§7): من أدخلها ومن اعتمدها ومن تحقق منها ومتى (بأرقام المستخدمين الثابتة)
        for (String line : db.txPeople(t)) b.append(line).append("\n");
        b.append("المرجع: ").append(Model.shortRef(t.uuid)).append("\n");
        if (!Model.nz(t.notes).isEmpty()) b.append("ملاحظات: ").append(t.notes).append("\n");
        if (!Model.nz(t.description).isEmpty()) b.append("\nالبيان:\n").append(t.description);
        Long repl = db.replacementOf(t.id);
        if (repl != null) b.append("\n\nصُحّحت بالحركة ").append(Model.shortRef(db.tx(repl) == null ? "" : db.tx(repl).uuid));
        List<Long> imgs = db.attachmentIds(t.id);
        List<String> opts = new ArrayList<>();
        if (t.isPending() && app.can(Auth.Perm.APPROVE)) {
            opts.add("اعتماد");
            opts.add("تصحيح واعتماد");
            opts.add("رفض");
            if (!db.similarTx(t).isEmpty()) b.append("\n\n! توجد حركة مشابهة (المكتب والمبلغ والاسم نفسها): تأكد أنها ليست مكررة قبل الاعتماد.");
        }
        if (t.isActive() && app.can(Auth.Perm.VERIFY) && db.usersEnabled()) opts.add("تم التحقق ✓");
        if (t.isActive() && !t.isLinked() && app.can(Auth.Perm.TX_EDIT)) opts.add("تعديل");
        // 10.18: قص العملات يُعدَّل بساقيه معًا من شاشة القص
        if (t.isActive() && t.isFx() && app.can(Auth.Perm.TX_EDIT) && app.can(Auth.Perm.TX_ADD)) opts.add("تعديل القص");
        if (t.isActive() && app.can(Auth.Perm.TX_CANCEL)) opts.add("إلغاء الحركة");
        if (!imgs.isEmpty()) opts.add("الصور (" + imgs.size() + ")");
        opts.add("نسخ");
        opts.add("إغلاق");
        JTextArea a = Ui.area(14);
        a.setText(b.toString());
        a.setEditable(false);
        a.setCaretPosition(0);
        JScrollPane sp = Ui.areaBox(a);
        sp.setPreferredSize(new Dimension(520, 320));
        JOptionPane p = new JOptionPane(sp, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, opts.toArray(), "إغلاق");
        Ui.show(parent, p, "تفاصيل الحركة");
        Object v = p.getValue();
        if (v == null) return;
        String c = v.toString();
        try {
            if ("اعتماد".equals(c)) {
                db.approveTx(t.id);
                app.changed();
                Ui.toast("اعتُمدت الحركة ودخلت الأرصدة");
                refreshCurrent(app);
            } else if ("رفض".equals(c)) {
                String why = Ui.input(parent, "رفض الحركة", (db.cashLinked(t.id) ? "! " + Db.REJECT_CASH_NOTE + "\n\n" : "") + "سبب الرفض", "");
                if (why == null) return;
                db.rejectTx(t.id, why);
                refreshCurrent(app);
            } else if (c.startsWith("تم التحقق")) {
                db.verifyTx(t.id);
                Ui.toast("سُجّل تحققك ✓ (بلا قيد ولا أثر في الأرصدة)");
                refreshCurrent(app);
            } else if ("تصحيح واعتماد".equals(c)) {
                app.go("tx", new TxView.Edit(t.id, true));
            } else if ("تعديل القص".equals(c)) {
                app.go("fx", new FxView.Edit(t.id));
            } else if ("تعديل".equals(c)) {
                app.go("tx", new TxView.Edit(t.id));
            } else if ("إلغاء الحركة".equals(c)) {
                String why = Ui.input(parent, "إلغاء الحركة", "تُلغى الحركة بقيد عكسي (لا يُحذف شيء) وتبقى ظاهرة كملغاة." + (t.isFx() ? "\nتُلغى ساقا التبديل معًا." : t.isTransfer() ? "\nتُلغى ساقا التحويل بين المكتبين معًا." : "") + "\n\nسبب الإلغاء:", "");
                if (why == null) return;
                if (why.trim().isEmpty()) {
                    Ui.toast("سبب الإلغاء مطلوب");
                    return;
                }
                db.cancelTx(t.id, why);
                app.changed();
                Ui.toast("أُلغيت الحركة بقيد عكسي");
                refreshCurrent(app);
            } else if (c.startsWith("الصور")) {
                showImages(parent, db, imgs);
            } else if ("نسخ".equals(c)) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(b.toString()), null);
                Ui.toast("نُسخت التفاصيل");
            }
        } catch (Exception e) {
            Ui.error(parent, e);
        }
    }

    static void refreshCurrent(App app) {
        View v = app.views.get(app.current);
        if (v instanceof OfficeView) v.refresh();
        else if (v != null) v.refresh();
    }

    static void showImages(Component parent, Db db, List<Long> ids) {
        JPanel c = Ui.column();
        for (Long id : ids) {
            try {
                BufferedImage im = ImageIO.read(new java.io.ByteArrayInputStream(db.attachment(id)));
                if (im == null) continue;
                double s = Math.min(1, 640.0 / im.getWidth());
                Image sc = im.getScaledInstance((int) (im.getWidth() * s), (int) (im.getHeight() * s), Image.SCALE_SMOOTH);
                JLabel l = new JLabel(new ImageIcon(sc));
                l.setAlignmentX(Component.RIGHT_ALIGNMENT);
                c.add(l);
                c.add(Ui.gap(10));
            } catch (Exception ignored) {
                c.add(Ui.label("(صورة غير قابلة للعرض)", 13, Ui.muted, false));
            }
        }
        JScrollPane sp = new JScrollPane(c);
        sp.setPreferredSize(new Dimension(700, 560));
        Ui.form(parent, "صور الوصل", sp, "إغلاق");
    }
}
