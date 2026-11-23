package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * 10.0: «حسابات العملاء» على الماك والويندوز (المنطق نفسه في ClientBook وDb): قائمة العملاء بأرصدتهم، وصفحة العميل بحركاته
 * والرصيد بعد كل حركة، واستلام منه وتسليم له عبر الصندوق (ولو بعملة أخرى)، وقيد بلا نقد، وتبديل عملة الرصيد، وكشف يُصدَّر.
 */
final class ClientsView extends View {
    final Ui.Field search = Ui.field("ابحث باسم العميل أو هاتفه");
    final JComboBox<String> filter = Ui.combo(new String[]{"النشطون", "لنا عليهم", "علينا لهم", "رصيد صفر", "المؤرشفون"});
    final JPanel head = Ui.column();
    JTable table, moves;
    List<ClientBook.Client> shown = new ArrayList<>();
    List<ClientBook.Move> shownMoves = new ArrayList<>();
    ClientBook.Client current;
    final JPanel detail = Ui.column();

    ClientsView(App app) {
        super(app, "حسابات العملاء");
        filter.addActionListener(e -> fill());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { fill(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { fill(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { fill(); }
        });
    }

    String m(String s) { return app.hidden() ? "••••" : s; }

    String bal(long v, String cur) { return v == 0 ? "صفر" : ClientBook.side(v) + " " + m(Ui.ltr(Model.fmt(Math.abs(v), db.decimals(cur)) + " " + cur)); }

    @Override void build() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Ui.bg);
        head.setBorder(new EmptyBorder(4, 24, 8, 24));
        center.add(head, BorderLayout.NORTH);
        table = Ui.table(new DefaultTableModel());
        moves = Ui.table(new DefaultTableModel());
        JPanel lists = new JPanel(new GridLayout(1, 2, 16, 0));
        lists.setOpaque(false);
        lists.setBorder(new EmptyBorder(0, 24, 20, 24));
        JPanel r = new JPanel(new BorderLayout());
        r.setOpaque(false);
        r.add(Ui.tableBox(table));
        JPanel l = new JPanel(new BorderLayout());
        l.setOpaque(false);
        l.add(detail, BorderLayout.NORTH);
        l.add(Ui.tableBox(moves));
        lists.add(r);
        lists.add(l);
        lists.applyComponentOrientation(Ui.RTL);
        center.add(lists, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || table.getSelectedRow() < 0) return;
            current = shown.get(table.convertRowIndexToModel(table.getSelectedRow()));
            showClient();
        });
        moves.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && moves.getSelectedRow() >= 0) moveDetails(shownMoves.get(moves.convertRowIndexToModel(moves.getSelectedRow())));
            }
        });
    }

    @Override void refresh() {
        actions.removeAll();
        head.removeAll();
        subtitle.setText("لكل عميل حساب بكل عملة («لنا» / «علينا») مستقل عن حسابات المكاتب");
        if (can(Auth.Perm.CASH)) {
            action(Ui.primary("+ عميل", e -> edit(null)));
            action(Ui.button("استلام منه", Ui.green, e -> cashMove(CashBox.IN)));
            action(Ui.button("تسليم له", Ui.red, e -> cashMove(CashBox.OUT)));
            action(Ui.ghost("قيد بلا نقد", e -> entry()));
            action(Ui.ghost("تبديل عملة", e -> swap()));
        }
        action(Ui.ghost("كشف الحساب…", e -> statement()));
        action(Ui.ghost(app.hidden() ? "إظهار المبالغ" : "إخفاء المبالغ", e -> {
            app.toggleHidden();
            refresh();
        }));
        Map<String, long[]> t = ClientBook.totals(db.clientBalances());
        if (!t.isEmpty()) {
            JPanel row = Ui.row(Ui.label("الإجمالي:", 13, Ui.muted, true));
            for (Map.Entry<String, long[]> e : t.entrySet()) {
                int d = db.decimals(e.getKey());
                row.add(Box.createHorizontalStrut(14));
                row.add(Ui.label(e.getKey() + "  لنا عليهم " + m(Ui.ltr(Model.fmt(e.getValue()[0], d))) + " • علينا لهم " + m(Ui.ltr(Model.fmt(e.getValue()[1], d))), 13, Ui.ink, true));
            }
            row.add(Box.createHorizontalGlue());
            head.add(row);
            head.add(Ui.gap(8));
        }
        search.setMaximumSize(new Dimension(360, 38));
        filter.setMaximumSize(new Dimension(180, 38));
        head.add(Ui.row(search, filter, Box.createHorizontalGlue()));
        Ui.rtl(head);
        fill();
        showClient();
        done();
    }

    boolean passes(ClientBook.Client k, Map<String, Long> b) {
        switch (filter.getSelectedIndex()) {
            case 1:
                for (long v : b.values()) if (v > 0) return k.active;
                return false;
            case 2:
                for (long v : b.values()) if (v < 0) return k.active;
                return false;
            case 3: return k.active && b.isEmpty();
            case 4: return !k.active;
            default: return k.active;
        }
    }

    void fill() {
        if (table == null) return;
        Map<Long, Map<String, Long>> bals = db.clientBalances();
        String q = search.val().toLowerCase(Locale.ROOT);
        DefaultTableModel mdl = new DefaultTableModel(new Object[]{"العميل", "الهاتف", "الرصيد"}, 0) {
            @Override public boolean isCellEditable(int a, int b) { return false; }
        };
        Ui.Keep keep = Ui.Keep.of(table, shown, k -> k.id);   // 10.5 (§6)
        shown = new ArrayList<>();
        for (ClientBook.Client k : db.clients()) {
            Map<String, Long> b = bals.getOrDefault(k.id, new HashMap<>());
            if (!q.isEmpty() && !(k.name + " " + k.phone).toLowerCase(Locale.ROOT).contains(q)) continue;
            if (!passes(k, b)) continue;
            shown.add(k);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Long> e : b.entrySet()) parts.add(bal(e.getValue(), e.getKey()));
            mdl.addRow(new Object[]{k.name, k.phone, parts.isEmpty() ? "صفر" : String.join(" • ", parts)});
        }
        table.setModel(mdl);
        keep.restore(table, shown, k -> k.id);
        Ui.widths(table, 180, 120, 300);
        table.applyComponentOrientation(Ui.RTL);
    }

    void showClient() {
        detail.removeAll();
        if (moves == null) return;
        if (current != null) current = db.client(current.id);
        if (current == null) {
            detail.add(Ui.para("اختر عميلًا من القائمة لتظهر حركاته ورصيده بعد كل حركة.", 13, Ui.muted, 420));
            moves.setModel(new DefaultTableModel());
            detail.revalidate();
            return;
        }
        List<ClientBook.Move> list = db.clientMoves(current.id);
        Map<String, Long> b = ClientBook.balances(list);
        Ui.Card c = Ui.tinted(CashView.NAVY, CashView.NAVY);
        c.add(Ui.label(current.name + (current.phone.isEmpty() ? "" : "   " + Ui.ltr(current.phone)) + (current.active ? "" : " — مؤرشف"), 17, Color.WHITE, true));
        if (b.isEmpty()) c.add(Ui.label("الحساب مسدَّد بالكامل (صفر)", 14, new Color(214, 224, 240), false));
        for (Map.Entry<String, Long> e : b.entrySet()) c.add(Ui.label(bal(e.getValue(), e.getKey()), 15, e.getValue() > 0 ? new Color(160, 230, 190) : new Color(255, 180, 180), true));
        JPanel row = Ui.row(Ui.ghost("تعديل البيانات", e -> edit(current)), Ui.ghost(current.active ? "أرشفة" : "إعادة", e -> archive()), Box.createHorizontalGlue());
        detail.add(c);
        detail.add(Ui.gap(6));
        detail.add(row);
        detail.add(Ui.gap(6));
        Ui.rtl(detail);
        Map<String, Integer> dec = db.decimalsMap();
        DefaultTableModel mdl = new DefaultTableModel(new Object[]{"التاريخ", "الحركة", "البيان", "لنا (+)", "علينا (−)", "الرصيد بعدها", "الحالة"}, 0) {
            @Override public boolean isCellEditable(int a, int b) { return false; }
        };
        Map<String, Long> run = new HashMap<>();
        String[] after = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ClientBook.Move x = list.get(i);
            long nb = run.getOrDefault(x.currency, 0L) + x.effect();
            run.put(x.currency, nb);
            after[i] = bal(nb, x.currency);
        }
        shownMoves = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            ClientBook.Move x = list.get(i);
            shownMoves.add(x);
            String amt = m(Ui.ltr(Model.fmt(x.amount, CashBox.dec(dec, x.currency)) + " " + x.currency));
            mdl.addRow(new Object[]{Model.date(x.createdAt), ClientBook.text(x, dec), x.description, x.dir > 0 ? amt : "", x.dir < 0 ? amt : "", x.counts() ? after[i] : "",
                    x.cancelled() ? "ملغاة" : ""});
        }
        moves.setModel(mdl);
        Ui.widths(moves, 120, 240, 160, 110, 110, 150, 70);
        moves.applyComponentOrientation(Ui.RTL);
        detail.revalidate();
        detail.repaint();
    }

    void moveDetails(ClientBook.Move x) {
        String t = ClientBook.text(x, db.decimalsMap()) + "\nعلى حسابه: " + (x.dir > 0 ? "لنا عليه " : "علينا له ") + Model.fmt(x.amount, db.decimals(x.currency)) + " " + x.currency
                + (x.cashCurrency != null ? "\n" + (x.cashDir > 0 ? "دخل الصندوق: " : "خرج من الصندوق: ") + Model.fmt(x.cashAmount, db.decimals(x.cashCurrency)) + " " + x.cashCurrency : "")
                + (x.description.isEmpty() ? "" : "\nالبيان: " + x.description) + "\nالتاريخ: " + Model.date(x.createdAt) + "\nبواسطة: " + x.userName
                + (x.cancelled() ? "\nملغاة" + (x.cancelReason.isEmpty() ? "" : ": " + x.cancelReason) : "");
        if (!x.counts() || !can(Auth.Perm.TX_CANCEL)) {
            Ui.info(this, "حركة على حساب العميل", t);
            return;
        }
        if (Ui.choose(this, "حركة على حساب العميل", t, new String[]{"إغلاق", "إلغاء الحركة"}) != 1) return;
        String reason = Ui.input(this, "إلغاء الحركة", x.cashUuid != null ? "تُلغى معها حركة الصندوق. السبب:" : "السبب:", "");
        if (reason == null) return;
        try {
            db.cancelClientMove(x.id, reason);
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    boolean need() {
        if (current == null) {
            Ui.info(this, "حسابات العملاء", "اختر العميل من القائمة أولًا.");
            return false;
        }
        if (!current.active) {
            Ui.info(this, "حسابات العملاء", "حساب العميل مؤرشف. أعده أولًا.");
            return false;
        }
        return true;
    }

    void edit(ClientBook.Client k) {
        Ui.Field name = Ui.field("اسم العميل"), phone = Ui.field("الهاتف (اختياري)"), note = Ui.field("ملاحظة (اختياري)");
        if (k != null) {
            name.setText(k.name);
            phone.setText(k.phone);
            note.setText(k.note);
        }
        JPanel c = Ui.column();
        c.add(Ui.labeled("الاسم", name));
        c.add(Ui.labeled("الهاتف", phone));
        c.add(Ui.labeled("ملاحظة", note));
        if (!Ui.form(this, k == null ? "عميل جديد" : "بيانات العميل", Ui.rtl(c), "حفظ")) return;
        try {
            ClientBook.Client n = new ClientBook.Client();
            n.id = k == null ? 0 : k.id;
            n.name = name.val();
            n.phone = phone.val();
            n.note = note.val();
            long id = db.saveClient(n);
            current = db.client(id);
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void archive() {
        try {
            db.setClientActive(current.id, !current.active);
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void cashMove(String kind) {
        if (!need()) return;
        if (!db.cashConfig().on) {
            Ui.info(this, "الصندوق", "أعدّ صندوق المكتب أولًا.");
            return;
        }
        app.go("cash");
        View v = app.views.get("cash");
        if (v instanceof CashView) ((CashView) v).move(kind, current);
        refresh();
    }

    void entry() {
        if (!need()) return;
        JComboBox<String> side = Ui.combo(new String[]{"لنا عليه (مدين لنا)", "علينا له (له عندنا)"});
        List<String> curs = new ArrayList<>();
        for (Model.Currency c : db.currencies(false)) curs.add(c.code);
        JComboBox<String> cur = Ui.combo(curs.toArray(new String[0]));
        Ui.Field amount = Ui.numeric("المبلغ"), desc = Ui.field("البيان (مثل: رصيد سابق)");
        JPanel c = new JPanel(new GridLayout(0, 2, 12, 10));
        c.setOpaque(false);
        c.add(Ui.labeled("الجهة", side));
        c.add(Ui.labeled("العملة", cur));
        c.add(Ui.labeled("المبلغ", amount));
        c.add(Ui.labeled("البيان", desc));
        JPanel w = Ui.column();
        w.add(Ui.para("يسجّل على حساب «" + current.name + "» دون أن يمس الصندوق: رصيد قديم، أو تسوية متفق عليها.", 13, Ui.muted, 520));
        w.add(c);
        if (!Ui.form(this, "قيد بلا نقد", Ui.rtl(w), "مراجعة")) return;
        try {
            String cu = (String) cur.getSelectedItem();
            long am = Db.minor(amount.val(), db.decimals(cu));
            int dir = side.getSelectedIndex() == 0 ? 1 : -1;
            long before = ClientBook.balances(db.clientMoves(current.id)).getOrDefault(cu, 0L);
            String text = "العميل: " + current.name + "\nالقيد: " + (dir > 0 ? "لنا عليه " : "علينا له ") + Ui.ltr(Model.fmt(am, db.decimals(cu)) + " " + cu)
                    + "\nرصيده " + cu + ": " + bal(before, cu) + " ← " + bal(before + dir * am, cu) + "\nالصندوق لا يتغير (بلا نقد)";
            if (!Ui.confirm(this, "راجع قبل التسجيل", text, "تسجيل")) return;
            db.addClientEntry(current.id, cu, am, dir, desc.val(), Clock.now());
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void swap() {
        if (!need()) return;
        Map<String, Long> b = ClientBook.balances(db.clientMoves(current.id));
        if (b.isEmpty()) {
            Ui.info(this, "تبديل عملة", "رصيد العميل صفر: لا شيء لتبديله.");
            return;
        }
        List<String> have = new ArrayList<>(b.keySet());
        JComboBox<String> from = Ui.combo(have.toArray(new String[0]));
        List<String> curs = new ArrayList<>();
        for (Model.Currency c : db.currencies(false)) curs.add(c.code);
        JComboBox<String> to = Ui.combo(curs.toArray(new String[0]));
        Ui.Field amount = Ui.numeric("المبلغ المبدَّل (فارغ = كل الرصيد)"), rate = Ui.numeric("السعر: 1 من الأولى = كم من الثانية"), toAmt = Ui.numeric("المبلغ بالجديدة (فارغ = × السعر)"),
                desc = Ui.field("البيان (اختياري)");
        JPanel c = new JPanel(new GridLayout(0, 2, 12, 10));
        c.setOpaque(false);
        c.add(Ui.labeled("من رصيده بـ", from));
        c.add(Ui.labeled("المبلغ", amount));
        c.add(Ui.labeled("إلى", to));
        c.add(Ui.labeled("السعر", rate));
        c.add(Ui.labeled("المبلغ الجديد", toAmt));
        c.add(Ui.labeled("البيان", desc));
        JPanel w = Ui.column();
        w.add(Ui.para("ينقل جزءًا من رصيد العميل من عملة إلى أخرى بسعر متفق عليه، بالجهة نفسها. لا يمس الصندوق. إن كان سيأخذ نقدًا بعملة أخرى فاستخدم «تسليم له» واختر «بعملة أخرى».", 13, Ui.muted, 560));
        w.add(c);
        if (!Ui.form(this, "تبديل عملة رصيد " + current.name, Ui.rtl(w), "مراجعة")) return;
        try {
            String f = (String) from.getSelectedItem(), t = (String) to.getSelectedItem();
            int d1 = db.decimals(f), d2 = db.decimals(t);
            long am = amount.val().isEmpty() ? Math.abs(b.get(f)) : Db.minor(amount.val(), d1);
            long ta;
            if (toAmt.val().isEmpty()) {
                Long x = CashBox.counterAmount(am, d1, rate.val(), d2);
                if (x == null) throw new IllegalArgumentException("اكتب السعر أو المبلغ الجديد");
                ta = x;
            } else ta = Db.minor(toAmt.val(), d2);
            String err = ClientBook.validateSwap(b, f, am, t, ta);
            if (err != null) throw new IllegalArgumentException(err);
            long bf = b.get(f), sideNow = bf > 0 ? 1 : -1, bt = b.getOrDefault(t, 0L);
            String text = "العميل: " + current.name + "\nيُطفأ من رصيده: " + Ui.ltr(Model.fmt(am, d1) + " " + f) + "\nويُضاف مقابله: " + Ui.ltr(Model.fmt(ta, d2) + " " + t)
                    + "\nالسعر: " + Ui.ltr("1 " + f + " = " + ClientBook.rateOf(am, d1, ta, d2) + " " + t)
                    + "\nرصيده " + f + ": " + bal(bf, f) + " ← " + bal(bf - sideNow * am, f) + "\nرصيده " + t + ": " + bal(bt, t) + " ← " + bal(bt + sideNow * ta, t);
            if (!Ui.confirm(this, "راجع قبل التسجيل", text, "تسجيل")) return;
            db.addClientSwap(current.id, f, am, t, ta, desc.val(), Clock.now());
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    Report makeReport() {
        Report r = ClientBook.statement(current, db.clientMoves(current.id), 0, 0, db.decimalsMap(), db.setting("office_name", ""));
        Entitlements e = License.current;
        r.stamp(e.watermark(), e.lid);
        return r;
    }

    void statement() {
        if (current == null) {
            Ui.info(this, "كشف الحساب", "اختر العميل من القائمة أولًا.");
            return;
        }
        Report r = makeReport();
        JPanel p = ReportPanel.render(r, 900);
        JScrollPane sp = Ui.scroll(p);
        sp.setPreferredSize(new Dimension(960, 600));
        if (Ui.form(this, r.title, sp, "تصدير / طباعة…")) Export.dialog(this, app, r.title, this::makeReport);
    }

    static BigDecimal zero() { return BigDecimal.ZERO; }
}
