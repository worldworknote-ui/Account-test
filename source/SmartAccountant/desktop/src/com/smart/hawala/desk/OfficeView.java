package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** صفحة مكتب: الأرصدة، آخر الحركات، المطابقات، وأوامر المكتب (حركة، كشف، تعديل، تبديل عملة، دمج، مطابقة، إيقاف، حذف). */
final class OfficeView extends View {
    long officeId;
    /** 10.18: العملة المختارة لتصفية حركات المكتب (null = الكل)، ونص البحث فيها. */
    String txCur, txFind = "";

    OfficeView(App app) {
        super(app, "المكتب");
    }

    @Override void open(Object arg) {
        if (!Long.valueOf(officeId).equals(arg)) {
            txCur = null;
            txFind = "";
        }
        officeId = (Long) arg;
        try {
            db.touchOffice(officeId);
        } catch (Exception ignored) {
            // ترتيب «آخر ما فتحته» غير حرج
        }
        refresh();
    }

    @Override void refresh() {
        clear();
        Model.Office o = db.office(officeId);
        if (o == null) {
            add(Ui.label("المكتب غير موجود (ربما حُذف أو دُمج).", 15, Ui.muted, false));
            done();
            return;
        }
        int w = textWidth();
        titleLabel.setText(o.name);
        List<String> sub = new ArrayList<>();
        if (!Model.nz(o.phone).isEmpty()) sub.add("هاتف: " + o.phone);
        if (!Model.nz(o.location).isEmpty()) sub.add(o.location);
        if (!Model.nz(o.contact).isEmpty()) sub.add(o.contact);
        if (!o.active) sub.add("موقوف");
        subtitle.setText(Model.join(sub, " · "));
        // 10.18: تصميم صفحة المكتب كالهاتف: بطاقة الرصيد الخضراء، تأكيد المطابقة، أزرار مربعة، ثم تبويبات
        JPopupMenu more = new JPopupMenu();
        more.add(App.item("تعديل بيانات المكتب", 0, () -> OfficesView.edit(this, app, o, this::refresh)));
        more.add(App.item(o.pinned ? "إلغاء التثبيت" : "تثبيت المكتب", 0, () -> {
            db.setPinned(o.id, !o.pinned);
            refresh();
        }));
        more.add(App.item(app.hidden() ? "إظهار المبالغ" : "إخفاء المبالغ", 0, () -> {
            app.toggleHidden();
            refresh();
        }));
        more.addSeparator();
        more.add(App.item("دمج هذا المكتب في مكتب آخر…", 0, () -> merge(o)));
        more.add(App.item(o.active ? "إيقاف المكتب" : "تفعيل المكتب", 0, () -> {
            try {
                db.setOfficeActive(o.id, !o.active);
                refresh();
            } catch (Exception ex) {
                Ui.error(this, ex);
            }
        }));
        more.add(App.item("حذف المكتب…", 0, () -> delete(o)));
        Ui.rtl(more);
        Ui.Btn mb = Ui.ghost("خيارات المكتب…", null);
        mb.addActionListener(e -> more.show(mb, 0, mb.getHeight()));
        action(mb);

        Map<String, Integer> dec = db.decimalsMap();
        Map<String, Long> bal = new TreeMap<>();
        for (String c : db.officeCurrencies(o.id)) bal.put(c, 0L);
        bal.putAll(db.balances(o.id));
        Map<String, String> names = db.currencyNames();
        Map<String, Db.Limit> limits = db.officeLimits(o.id);
        Ui.Hero hero = new Ui.Hero();
        hero.add(Ui.Hero.text("الرصيد الحالي", 13.5f, false, true));
        hero.add(Box.createVerticalStrut(4));
        for (Map.Entry<String, Long> e : bal.entrySet()) {
            int d = dec.getOrDefault(e.getKey(), 2);
            long v = e.getValue();
            String nm = names.get(e.getKey());
            JLabel amt = Ui.Hero.text(app.hidden() ? "••••" : Model.fmt(Math.abs(v), d), 26, true, false);
            JPanel r = Ui.row(Ui.Hero.text(nm == null || nm.isEmpty() ? e.getKey() : nm + " · " + e.getKey(), 17, false, false), Box.createHorizontalGlue(), amt,
                    Box.createHorizontalStrut(10), Ui.side(v));
            r.setBorder(new javax.swing.border.EmptyBorder(5, 0, 5, 0));
            hero.add(r);
            Db.Limit l = limits.get(e.getKey());
            if (l != null) {
                String lt = (l.receivable != null ? "حد لنا " + Model.fmt(l.receivable, d) : "") + (l.payable != null ? (l.receivable != null ? " · " : "") + "حد علينا " + Model.fmt(l.payable, d) : "");
                boolean over = (l.receivable != null && v > l.receivable) || (l.payable != null && -v > l.payable);
                hero.add(Ui.Hero.text(lt + (over ? "  ! تجاوز الحد" : ""), 12.5f, over, !over));
            }
        }
        if (bal.isEmpty()) hero.add(Ui.Hero.text("لا توجد عملات مفعّلة لهذا المكتب", 14, false, false));
        for (String hl : db.holdLines(o.id)) hero.add(Ui.Hero.text("• " + (app.hidden() ? hl.replaceAll("[0-9٠-٩][0-9٠-٩,.]*", "••••") : hl), 13.5f, false, false));
        boolean anyBal = false;
        for (long v : bal.values()) anyBal |= v != 0;
        if (anyBal) {
            Ledger.Valuation val = Ledger.valueOffice(db.balances(o.id), db.usdRates(), dec);
            BigDecimal net = val.netUsd();
            long whole = net.abs().setScale(0, RoundingMode.HALF_EVEN).longValue();
            String s = app.hidden() ? "الميزان ≈ •••• USD" : whole == 0 ? "الميزان ≈ صفر USD" : "الميزان ≈ " + Ui.ltr(Model.fmt(whole, 0) + " USD") + " " + (net.signum() > 0 ? "لنا" : "علينا");
            if (!val.missingRates.isEmpty()) s += " (بلا سعر: " + Model.join(new ArrayList<>(val.missingRates), "، ") + ")";
            hero.add(Box.createVerticalStrut(6));
            hero.add(Ui.Hero.text(s, 19, true, false));
        }
        Model.Recon last = db.lastRecon(o.id, false);
        if (last != null) {
            JSeparator sep = new JSeparator();
            sep.setForeground(Ui.mix(Color.WHITE, Ui.primary, 0.6f));
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
            hero.add(Box.createVerticalStrut(10));
            hero.add(sep);
            hero.add(Box.createVerticalStrut(8));
            hero.add(Ui.Hero.text("آخر مطابقة: " + Model.date(last.createdAt) + " — " + last.statusLabel(), 14, false, true));
        }
        add(hero);
        space(12);
        if (last != null && Model.RECON_SENT.equals(last.status) && can(Auth.Perm.TX_ADD)) {
            JPanel two = new JPanel(new GridLayout(1, 2, 12, 0));
            two.setOpaque(false);
            two.setAlignmentX(Component.RIGHT_ALIGNMENT);
            Ui.Btn ok = Ui.button("✓ أكّد المكتب المطابقة", Ui.green, e -> status(last, Model.RECON_CONFIRMED));
            Ui.Btn no = Ui.button("× غير مطابق", Ui.red, e -> status(last, Model.RECON_DISPUTED));
            ok.setPreferredSize(new Dimension(200, 52));
            two.add(ok);
            two.add(no);
            two.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
            Ui.rtl(two);
            add(two);
            space(12);
        }
        // الأزرار المربعة
        List<Ui.Tile> tiles = new ArrayList<>();
        if (can(Auth.Perm.TX_ADD) && o.active) tiles.add(new Ui.Tile("plus", "حركة\nجديدة", true, e -> app.go("tx", new TxView.Prefill(officeId))));
        tiles.add(new Ui.Tile("statement", "كشف", false, e -> app.go("statement", officeId)));
        tiles.add(new Ui.Tile("recon", "مطابقة\nرصيد", false, e -> sendRecon(o)));
        if (can(Auth.Perm.TX_EDIT) && can(Auth.Perm.TX_ADD) && o.active) tiles.add(new Ui.Tile("fx", "قص\nعملات", false, e -> app.go("fx", new RecordView.Open(o.id))));
        if (can(Auth.Perm.TX_ADD) && o.active) tiles.add(new Ui.Tile("xfer", "بين\nمكتبين", false, e -> app.go("xfer", new RecordView.Open(o.id))));
        if (can(Auth.Perm.EXPORT)) tiles.add(new Ui.Tile("share", "مشاركة", false, e -> {
            app.go("statement", officeId);
            View sv = app.views.get("statement");
            if (sv instanceof StatementView) ((StatementView) sv).export();
        }));
        if (o.active && can(Auth.Perm.CASH) && db.cashConfig().on) {
            final Ui.Tile[] ct = new Ui.Tile[1];
            ct[0] = new Ui.Tile("cash", "الصندوق", false, e -> {
                JPopupMenu cm = new JPopupMenu();
                cm.add(App.item("تسليم من الصندوق لحسابه (لنا على المكتب)", 0, () -> cash(o, CashBox.OUT)));
                cm.add(App.item("استلام إلى الصندوق لحسابه (علينا للمكتب)", 0, () -> cash(o, CashBox.IN)));
                Ui.rtl(cm);
                cm.show(ct[0], 0, ct[0].getHeight());
            });
            tiles.add(ct[0]);
        }
        JPanel tr = new JPanel(new GridLayout(1, Math.max(1, tiles.size()), 12, 0));
        tr.setOpaque(false);
        tr.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (Ui.Tile t : tiles) tr.add(t);
        tr.setMaximumSize(new Dimension(Math.min(Integer.MAX_VALUE, 150 * tiles.size()), 108));
        Ui.rtl(tr);
        JPanel trh = Ui.row(tr, Box.createHorizontalGlue());
        add(trh);
        space(16);

        // التبويبات (كالهاتف)
        Ui.Seg tabs = new Ui.Seg(new String[]{"الحركات", "المطابقات", "الأمانات", "البيانات"}, null, tab);
        tabs.listener = i -> {
            tab = i;
            refresh();
        };
        tabs.setMaximumSize(new Dimension(760, 48));
        tabs.setPreferredSize(new Dimension(760, 48));
        add(Ui.row(tabs, Box.createHorizontalGlue()));
        space(12);
        if (tab == 0) txTab(o, w, dec);
        else if (tab == 1) reconTab(o, w, dec);
        else if (tab == 2) holdsCard(o, w);
        else infoTab(o, w, dec, limits);
        done();
    }

    /** التبويب الحالي في صفحة المكتب (0 الحركات، 1 المطابقات، 2 الأمانات، 3 البيانات). */
    int tab;

    void cash(Model.Office o, String kind) {
        app.go("cash");
        View v = app.views.get("cash");
        if (v instanceof CashView) {
            ((CashView) v).presetOffice = o.id;
            ((CashView) v).move(kind, null);
        }
    }

    /** الحركات: شرائح العملات + بحث، والجدول مع «الرصيد بعدها» لكل حركة دائمًا (بعملة الحركة نفسها). */
    void txTab(Model.Office o, int w, Map<String, Integer> dec) {
        List<String> curs = new ArrayList<>(db.officeCurrencies(o.id));
        for (String k : db.balances(o.id).keySet()) if (!curs.contains(k)) curs.add(k);
        if (txCur != null && !curs.contains(txCur)) txCur = null;
        String[] labels = new String[curs.size() + 1];
        labels[0] = "كل العملات";
        for (int i = 0; i < curs.size(); i++) labels[i + 1] = curs.get(i);
        Ui.Seg pick = new Ui.Seg(labels, null, txCur == null ? 0 : curs.indexOf(txCur) + 1);
        pick.listener = i -> {
            txCur = i == 0 ? null : curs.get(i - 1);
            refresh();
        };
        pick.setMaximumSize(new Dimension(Math.min(900, 130 * labels.length), 42));
        Ui.Field find = Ui.field("بحث في حركات المكتب…");
        find.setText(txFind);
        find.setMaximumSize(new Dimension(280, 40));
        add(Ui.row(pick, Box.createHorizontalGlue(), find));
        space(8);
        Db.TxFilter f = new Db.TxFilter();
        f.office = o.id;
        f.includeCancelled = true;
        f.currency = txCur;
        f.limit = txCur == null ? 300 : 2000;
        List<Model.Tx> all = db.txLog(f), txs = new ArrayList<>();
        String q = OwnerReports.norm(txFind);
        for (Model.Tx x : all) {
            if (!q.isEmpty()) {
                String hay = OwnerReports.norm(Model.nz(x.person) + " " + Model.nz(x.description) + " " + Model.nz(x.notes) + " " + Model.nz(x.dest) + " " + Model.fmt(x.amount, db.decimals(x.currency)));
                boolean ok = true;
                for (String wq : q.split(" ")) ok &= wq.isEmpty() || hay.contains(wq);
                if (!ok) continue;
            }
            txs.add(x);
        }
        Map<Long, Long> after = new HashMap<>();
        if (txCur != null) after.putAll(db.balancesAfter(o.id, txCur));
        else for (String c : curs) after.putAll(db.balancesAfter(o.id, c));
        JTable t = LogView.txTable(app, txs, false, after, txCur == null ? -1 : db.decimals(txCur));
        JScrollPane sp = Ui.tableBox(t);
        // الجدول بكامل صفوفه والصفحة هي التي تتمرر
        int th = Math.max(3, txs.size()) * t.getRowHeight() + 42;
        sp.setPreferredSize(new Dimension(800, th));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, th));
        sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        Ui.passWheel(sp);
        add(sp);
        find.addActionListener(e -> {
            txFind = find.val();
            refresh();
        });
        space(4);
        add(Ui.label("«الرصيد بعدها» = رصيد المكتب بعملة الحركة بعد تسجيلها (مع أجرتها بالعملة نفسها). الحركات الملغاة بلا رصيد."
                + (txCur == null && all.size() >= 300 ? " تظهر آخر 300 حركة؛ اختر عملة لترى كل حركاتها." : ""), 12, Ui.muted, false));
    }

    /** المطابقات: سجل المطابقات مع أوامرها. */
    void reconTab(Model.Office o, int w, Map<String, Integer> dec) {
        List<Model.Recon> rs = db.recons(o.id, 50);
        if (rs.isEmpty()) {
            add(Ui.label("لا مطابقات بعد. اضغط «مطابقة رصيد» لإرسال أول مطابقة.", 14, Ui.muted, false));
            return;
        }
        for (Model.Recon r : rs) {
            Ui.Card c = Ui.card();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : r.balances.entrySet())
                sb.append(sb.length() > 0 ? " · " : "").append(e.getKey()).append(" ").append(Model.bal(e.getValue(), dec.getOrDefault(e.getKey(), 2)));
            c.add(Ui.label(Model.date(r.createdAt) + " — " + r.statusLabel(), 14, Model.RECON_CONFIRMED.equals(r.status) ? Ui.green : Model.RECON_DISPUTED.equals(r.status) ? Ui.red : Ui.warnInk, true));
            c.add(Ui.para(sb.toString() + (Model.nz(r.note).isEmpty() ? "" : "\nملاحظة: " + r.note), 13, Ui.ink, w));
            // 10.8: من أنشأها ومن تحقق منها
            c.add(Ui.label(db.reconPeople(r), 12.5f, r.checkedBy != null ? Ui.green : Ui.muted, false));
            List<Component> bs = new ArrayList<>();
            if (r.checkedBy == null && can(Auth.Perm.VERIFY)) bs.add(Ui.ghost("تم التحقق ✓", e -> {
                try {
                    db.verifyRecon(r.id);
                    Ui.toast("سُجّل تحققك من المطابقة ✓");
                    refresh();
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            }));
            if (can(Auth.Perm.TX_ADD)) {
                if (Model.RECON_SENT.equals(r.status)) {
                    bs.add(Ui.ghost("أكّد المكتب ✓", e -> status(r, Model.RECON_CONFIRMED)));
                    bs.add(Ui.ghost("غير مطابق ×", e -> status(r, Model.RECON_DISPUTED)));
                } else bs.add(Ui.ghost("تغيير الحالة…", e -> changeStatus(r)));
            }
            // 10.18: إعادة إرسال المطابقة نفسها (بأرصدتها وتاريخها) نصًا أو صورة
            if (can(Auth.Perm.EXPORT)) bs.add(Ui.ghost("إعادة الإرسال…", e -> resend(o, r)));
            bs.add(Ui.ghost("كشف ما بعدها", e -> app.go("statement", new StatementView.Since(o.id, r.id))));
            if (can(Auth.Perm.DELETE)) bs.add(Ui.ghost("حذف", e -> {
                if (Ui.confirm(this, "حذف المطابقة", "تُحذف المطابقة من السجل فقط؛ لا تتغير أي حركة.", "حذف")) {
                    db.deleteRecon(r.id);
                    refresh();
                }
            }));
            c.add(Ui.flow(bs.toArray(new Component[0])));
            add(c);
            space(6);
        }
    }

    /** البيانات: معلومات المكتب، العملات والحدود، وآخر رسالة واتساب نُقلت. */
    void infoTab(Model.Office o, int w, Map<String, Integer> dec, Map<String, Db.Limit> limits) {
        Ui.Card ic = Ui.card();
        ic.add(Ui.heading("بيانات المكتب"));
        String[][] rows = {{"الاسم", o.name}, {"اسم مختصر", Model.nz(o.shortName)}, {"الهاتف", Model.nz(o.phone)}, {"المدينة / العنوان", Model.nz(o.location)},
                {"المسؤول", Model.nz(o.contact)}, {"ملاحظات", Model.nz(o.notes)}, {"الحالة", o.active ? "نشط" : "موقوف"},
                {"العملات", Model.join(db.officeCurrencies(o.id), "، ")}};
        for (String[] r : rows) {
            if (r[1].isEmpty()) continue;
            ic.add(Ui.row(Ui.label(r[0], 13.5f, Ui.muted, true), Box.createHorizontalStrut(12), Ui.label(r[1], 14.5f, Ui.ink, false), Box.createHorizontalGlue()));
            ic.add(Ui.gap(4));
        }
        for (Map.Entry<String, Db.Limit> e : limits.entrySet()) {
            int d = dec.getOrDefault(e.getKey(), 2);
            Db.Limit l = e.getValue();
            ic.add(Ui.label("حد " + e.getKey() + ": " + (l.receivable != null ? "لنا " + Model.fmt(l.receivable, d) : "") + (l.payable != null ? (l.receivable != null ? " · " : "") + "علينا " + Model.fmt(l.payable, d) : ""), 13, Ui.ink, false));
        }
        if (can(Auth.Perm.OFFICES)) ic.add(Ui.flow(Ui.ghost("تعديل البيانات…", e -> OfficesView.edit(this, app, o, this::refresh))));
        add(ic);
        space(10);
        // 10.5 (§7): علامة «آخر رسالة واتساب نُقلت» لهذا المكتب
        Ui.Card mk = Ui.card();
        mk.add(Ui.label("آخر رسالة واتساب نُقلت", 14, Ui.primaryInk, true));
        Db.WaMark wm = db.lastWaMark(o.id);
        if (wm == null) mk.add(Ui.label("لم تُحفظ علامة بعد", 13, Ui.muted, false));
        else {
            mk.add(Ui.label("حفظها " + wm.userName + " — " + Model.date(wm.at) + (wm.msgTime.isEmpty() ? "" : " — وقت الرسالة " + wm.msgTime), 12.5f, Ui.goldInk, true));
            mk.add(Ui.para(wm.text, 13.5f, Ui.ink, w - 60));
        }
        mk.add(Ui.para("حفظ العلامة ليس دليلًا على تسجيل أي حركة.", 12, Ui.muted, w - 60));
        List<Component> mbs = new ArrayList<>();
        if (wm != null) mbs.add(Ui.ghost("نسخ النص", e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(wm.text), null);
            Ui.toast("نُسخ نص آخر رسالة");
        }));
        if (can(Auth.Perm.TX_ADD)) mbs.add(Ui.ghost("تحديث العلامة…", e -> MineView.updateMark(this, db, o, this::refresh)));
        mbs.add(Ui.ghost("التاريخ", e -> MineView.history(this, db, o)));
        mk.add(Ui.flow(mbs.toArray(new Component[0])));
        add(mk);
    }

    /** 10.18: تغيير حالة مطابقة مؤكَّدة أو مختلَف عليها (كالهاتف): إلى أي حالة أخرى، ومنها الرجوع إلى «بانتظار تأكيد المكتب». */
    void changeStatus(Model.Recon r) {
        List<String> names = new ArrayList<>(), keys = new ArrayList<>();
        for (String[] k : new String[][]{{Model.RECON_SENT, "بانتظار تأكيد المكتب"}, {Model.RECON_CONFIRMED, "أكّد المكتب ✓"}, {Model.RECON_DISPUTED, "غير مطابق ×"}})
            if (!k[0].equals(r.status)) {
                keys.add(k[0]);
                names.add(k[1]);
            }
        names.add("إلغاء");
        JOptionPane p = new JOptionPane(Ui.para("الحالة الآن: " + r.statusLabel(), 14, Ui.ink, 380), JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, names.toArray(), "إلغاء");
        Ui.show(this, p, "تغيير حالة المطابقة");
        int i = names.indexOf(String.valueOf(p.getValue()));
        if (i < 0 || i >= keys.size()) return;
        try {
            if (Model.RECON_SENT.equals(keys.get(i))) {
                db.setReconStatus(r.id, Model.RECON_SENT, r.note);
                refresh();
            } else status(r, keys.get(i));
        } catch (Exception ex) {
            Ui.error(this, ex);
        }
    }

    /** 10.18: إعادة إرسال مطابقة محفوظة بأرصدتها وتاريخها كما كانت (لا تُسجَّل مطابقة جديدة). */
    void resend(Model.Office o, Model.Recon r) {
        Reconcile.Template t = Reconcile.Template.from(db.settings(Reconcile.Template.KEYS));
        List<Reconcile.Row> rows = Reconcile.rows(r.balances, db.currencyNames(), db.decimalsMap(), null, t.showZero);
        String txt = Reconcile.text(t, o.name, db.setting("office_name", ""), r.createdAt, rows);
        JTextArea preview = Ui.area(9);
        preview.setEditable(false);
        preview.setText(txt);
        preview.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        JScrollPane ps = Ui.areaBox(preview);
        ps.setPreferredSize(new Dimension(420, 240));
        JOptionPane p = new JOptionPane(ps, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"فتح واتساب", "نسخ النص", "نسخ الصورة", "حفظ الصورة…", "إلغاء"}, "فتح واتساب");
        Ui.show(this, p, "إعادة إرسال مطابقة " + Model.date(r.createdAt));
        Object v = p.getValue();
        try {
            if ("نسخ الصورة".equals(v) || "حفظ الصورة…".equals(v)) {
                java.awt.image.BufferedImage im = ReconImage.render(t, o.name, db.setting("office_name", ""), r.createdAt, rows, Ui.primary);
                if ("نسخ الصورة".equals(v)) {
                    ReconImage.copy(im);
                    Ui.toast("نُسخت صورة المطابقة؛ الصقها في واتساب (Ctrl+V).");
                } else {
                    java.io.File f = Export.save(this, db, "مطابقة " + o.name, ".png");
                    if (f != null) {
                        javax.imageio.ImageIO.write(im, "png", f);
                        Ui.toast("حُفظت الصورة: " + f.getName());
                    }
                }
            } else if ("فتح واتساب".equals(v) || "نسخ النص".equals(v)) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txt), null);
                if ("فتح واتساب".equals(v)) openWhatsApp(this, db, o.phone, txt);
                else Ui.toast("نُسخ النص؛ الصقه في واتساب.");
            }
        } catch (Exception ex) {
            Ui.error(this, ex);
        }
    }

    void status(Model.Recon r, String s) {
        String note = Ui.input(this, "ملاحظة (اختيارية)", Model.RECON_DISPUTED.equals(s) ? "ما الفرق الذي ذكره المكتب؟" : "ملاحظة", r.note);
        if (note == null) return;
        db.setReconStatus(r.id, s, note);
        refresh();
    }

    // ===================================================================================
    // مطابقة الرصيد (رسالة واتساب)
    // ===================================================================================

    void sendRecon(Model.Office o) {
        if (!require(Auth.Perm.EXPORT)) return;
        Reconcile.Template t = Reconcile.Template.from(db.settings(Reconcile.Template.KEYS));
        Map<String, Long> bal = new TreeMap<>();
        for (String c : db.officeCurrencies(o.id)) bal.put(c, 0L);
        bal.putAll(db.balances(o.id));
        Map<String, String> names = db.currencyNames();
        JPanel c = Ui.column();
        c.add(Ui.para("يُرسل الرصيد فقط، بدون تفاصيل الحركات.", 13, Ui.muted, 420));
        Map<String, JCheckBox> checks = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : bal.entrySet()) {
            int d = db.decimals(e.getKey());
            String nm = names.get(e.getKey());
            JCheckBox ck = Ui.check((nm == null ? e.getKey() : nm) + ":  " + (e.getValue() == 0 ? "صفر" : Model.fmt(Math.abs(e.getValue()), d) + " " + (e.getValue() > 0 ? t.plusWord : t.minusWord)),
                    e.getValue() != 0 || t.showZero);
            checks.put(e.getKey(), ck);
            c.add(ck);
        }
        JCheckBox record = Ui.check("تسجيل كمطابقة بانتظار تأكيد المكتب", can(Auth.Perm.TX_ADD));
        record.setEnabled(can(Auth.Perm.TX_ADD));
        c.add(Ui.gap(6));
        c.add(record);
        JTextArea preview = Ui.area(9);
        preview.setEditable(false);
        Runnable upd = () -> {
            Set<String> only = new LinkedHashSet<>();
            for (Map.Entry<String, JCheckBox> e : checks.entrySet()) if (e.getValue().isSelected()) only.add(e.getKey());
            preview.setText(Reconcile.text(t, o.name, db.setting("office_name", ""), Clock.now(), Reconcile.rows(bal, names, db.decimalsMap(), only, true)));
        };
        for (JCheckBox ck : checks.values()) ck.addActionListener(e -> upd.run());
        upd.run();
        c.add(Ui.gap(6));
        preview.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        JScrollPane ps = Ui.areaBox(preview);
        ps.setPreferredSize(new Dimension(420, 220));
        c.add(ps);
        JOptionPane p = new JOptionPane(Ui.rtl(c), JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                new Object[]{"فتح واتساب", "نسخ النص", "نسخ الصورة", "حفظ الصورة…", "إلغاء"}, "فتح واتساب");
        Ui.show(this, p, "مطابقة الرصيد مع «" + o.name + "»");
        Object v = p.getValue();
        if (!"فتح واتساب".equals(v) && !"نسخ النص".equals(v) && !"نسخ الصورة".equals(v) && !"حفظ الصورة…".equals(v)) return;
        if (checks.values().stream().noneMatch(AbstractButton::isSelected)) {
            Ui.toast("اختر عملة واحدة على الأقل");
            return;
        }
        if (record.isSelected()) {
            try {
                db.createRecon(o.id);
            } catch (Exception e) {
                Ui.error(this, e);
                return;
            }
        }
        String txt = preview.getText();
        // 10.16: المطابقة صورةً (كالهاتف): تُنسخ للحافظة لتُلصق في واتساب، أو تُحفظ ملف PNG
        if ("نسخ الصورة".equals(v) || "حفظ الصورة…".equals(v)) {
            try {
                Set<String> only = new LinkedHashSet<>();
                for (Map.Entry<String, JCheckBox> e : checks.entrySet()) if (e.getValue().isSelected()) only.add(e.getKey());
                java.awt.image.BufferedImage im = ReconImage.render(t, o.name, db.setting("office_name", ""), Clock.now(),
                        Reconcile.rows(bal, names, db.decimalsMap(), only, true), Ui.primary);
                if ("نسخ الصورة".equals(v)) {
                    ReconImage.copy(im);
                    Ui.toast("نُسخت صورة المطابقة؛ الصقها في واتساب (Ctrl+V).");
                } else {
                    java.io.File f = Export.save(this, db, "مطابقة " + o.name, ".png");
                    if (f != null) {
                        javax.imageio.ImageIO.write(im, "png", f);
                        Ui.toast("حُفظت الصورة: " + f.getName());
                    }
                }
            } catch (Exception e) {
                Ui.error(this, e);
            }
            refresh();
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txt), null);
        if ("فتح واتساب".equals(v)) openWhatsApp(this, db, o.phone, txt);
        else Ui.toast("نُسخ النص؛ الصقه في واتساب.");
        refresh();
    }

    /** 10.18: أمانات المكتب والمبالغ المجمّدة: المفتوح منها، وزر الإضافة والفك والسجل. */
    void holdsCard(Model.Office o, int w) {
        List<Db.Hold> open = db.holds(o.id, true);
        Ui.Card c = Ui.card();
        List<Component> hb = new ArrayList<>();
        hb.add(Ui.label("أمانات ومبالغ مجمّدة" + (open.isEmpty() ? "" : " (" + open.size() + ")"), 14, Ui.primaryInk, true));
        hb.add(Box.createHorizontalGlue());
        if (can(Auth.Perm.TX_ADD) && o.active) hb.add(Ui.ghost("+ أمانة / تجميد…", e -> addHold(o)));
        hb.add(Ui.ghost("السجل", e -> holdHistory(o)));
        c.add(Ui.row(hb.toArray(new Component[0])));
        if (open.isEmpty()) c.add(Ui.label("لا أمانات ولا مبالغ مجمّدة لهذا المكتب.", 13, Ui.muted, false));
        for (String ln : db.holdLines(o.id)) c.add(Ui.label("• " + (app.hidden() ? ln.replaceAll("[0-9][0-9,.]*", "••••") : ln), 13.5f, Ui.goldInk, true));
        for (final Db.Hold h : open) {
            String t = h.label() + " — " + (app.hidden() ? "••••" : Model.fmt(h.amount, db.decimals(h.currency))) + " " + h.currency + " — " + Model.day(h.at)
                    + (h.by.isEmpty() ? "" : " — " + h.by) + (h.note.isEmpty() ? "" : " — " + h.note);
            JPanel r = Ui.row(Ui.label(Ui.cut(t, 130), 13, Ui.ink, false), Box.createHorizontalGlue());
            if (can(Auth.Perm.TX_ADD)) r.add(Ui.ghost("فكّ", e -> {
                if (!Ui.confirm(this, "فكّ " + h.label(), "يُعلَّم «" + h.label() + " " + Model.fmt(h.amount, db.decimals(h.currency)) + " " + h.currency
                        + "» كمفكوك ويبقى في السجل. لا يُنشئ أي قيد: إن سُلّم المبلغ فسجّل حركته بنفسك.", "فكّ")) return;
                try {
                    db.releaseHold(h.uuid);
                    Ui.toast("فُكّ ✓");
                    refresh();
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            }));
            c.add(r);
        }
        add(c);
        space(10);
    }

    void addHold(Model.Office o) {
        List<String> curs = new ArrayList<>(db.officeCurrencies(o.id));
        if (curs.isEmpty()) {
            Ui.toast("أضف عملة للمكتب أولًا");
            return;
        }
        JComboBox<String> cur = Ui.combo(curs.toArray(new String[0]));
        Ui.Seg kind = new Ui.Seg(new String[]{"أمانة منفصلة عن الرصيد", "تجميد جزء من الرصيد"}, null, 0);
        Ui.Seg side = new Ui.Seg(new String[]{"للمكتب عندنا / من «علينا»", "لنا عند المكتب / من «لنا»"}, null, 0);
        Ui.Field amount = Ui.numeric("المبلغ"), note = Ui.field("ملاحظة (اختياري): لمن، ولماذا، حتى متى…");
        JPanel c = Ui.column();
        c.add(Ui.para("الأمانة المنفصلة مبلغ محفوظ لا يدخل في رصيد المكتب ولا في القيود. التجميد يُبقي الرصيد كما هو ويعلّم جزءًا منه «مجمّدًا» فيظهر المتاح. "
                + "كلاهما يظهر في صفحة المكتب ويمكن إضافته لرسالة المطابقة، ويُفك بضغطة.", 13.5f, Ui.muted, 460));
        c.add(Ui.gap(8));
        c.add(Ui.labeled("النوع", kind));
        c.add(Ui.gap(6));
        c.add(Ui.labeled("الاتجاه", side));
        c.add(Ui.gap(6));
        JPanel g = new JPanel(new GridLayout(1, 2, 12, 0));
        g.setOpaque(false);
        g.setAlignmentX(Component.RIGHT_ALIGNMENT);
        g.add(Ui.labeled("العملة", cur));
        g.add(Ui.labeled("المبلغ", amount));
        c.add(g);
        c.add(Ui.gap(6));
        c.add(Ui.labeled("ملاحظة", note));
        while (Ui.form(this, "أمانة أو تجميد — " + o.name, Ui.rtl(c), "حفظ")) {
            try {
                String code = (String) cur.getSelectedItem();
                db.addHold(o.id, code, kind.selected == 0 ? Db.HOLD_TRUST : Db.HOLD_FREEZE, side.selected == 0 ? Db.SIDE_THEIRS : Db.SIDE_OURS,
                        Db.minor(amount.val(), db.decimals(code)), note.val());
                Ui.toast("حُفظ ✓");
                refresh();
                return;
            } catch (Exception ex) {
                Ui.error(this, ex);
            }
        }
    }

    void holdHistory(Model.Office o) {
        List<Db.Hold> all = db.holds(o.id, false);
        DefaultTableModel m = new DefaultTableModel(new Object[]{"التاريخ", "النوع", "المبلغ", "العملة", "أضافه", "الحالة", "ملاحظة"}, 0) {
            @Override public boolean isCellEditable(int r, int cc) { return false; }
        };
        for (Db.Hold h : all) m.addRow(new Object[]{Model.date(h.at), h.label(), Model.fmt(h.amount, db.decimals(h.currency)), h.currency, h.by,
                h.open() ? "قائم" : "فُكّ " + Model.day(h.releasedAt) + (h.releasedBy.isEmpty() ? "" : " — " + h.releasedBy), h.note});
        JTable t = Ui.table(m);
        Ui.widths(t, 130, 170, 100, 60, 110, 190, 260);
        JScrollPane sp = Ui.tableBox(t);
        sp.setPreferredSize(new Dimension(1000, 420));
        JOptionPane.showMessageDialog(Ui.win(this), sp, "سجل الأمانات والمبالغ المجمّدة — " + o.name, JOptionPane.PLAIN_MESSAGE);
    }

    static void openWhatsApp(Component parent, Db db, String phone, String text) {
        try {
            String n = Model.whatsappNumber(phone, db.setting("wa_country", "963"));
            String url = "https://wa.me/" + (n == null ? "" : n) + "?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20");
            Desktop.getDesktop().browse(new URI(url));
            Ui.toast("نُسخ النص أيضًا؛ إن لم يظهر في واتساب الصقه.");
        } catch (Exception e) {
            Ui.error(parent, e);
        }
    }

    // 10.6: «قص عملات» و«حركة بين مكتبين» صارتا شاشتين كاملتين (FxView وTransferView) بأسلوب «تسجيل حركة».

    // ===================================================================================
    // الدمج والحذف
    // ===================================================================================

    void merge(Model.Office from) {
        if (!require(Auth.Perm.DELETE)) return;
        List<Model.Office> others = new ArrayList<>();
        for (Model.Office x : db.offices()) if (x.id != from.id) others.add(x);
        if (others.isEmpty()) {
            Ui.toast("لا يوجد مكتب آخر");
            return;
        }
        String[] names = new String[others.size()];
        for (int i = 0; i < names.length; i++) names[i] = others.get(i).name;
        JPanel c = Ui.column();
        JComboBox<String> into = Ui.combo(names);
        c.add(Ui.labeled("ادمج «" + from.name + "» في:", into));
        c.add(Ui.gap(8));
        c.add(Ui.para("تنتقل كل حركات «" + from.name + "» وأرصدته السابقة إلى المكتب المختار كما هي، ويُحذف «" + from.name + "» بعدها فارغًا. "
                + "الرصيد يصير مجموع الرصيدين، ويُتحقق من ذلك لكل عملة قبل الحفظ. تُحفظ نسخة أمان قبل الدمج للتراجع.", 13, Ui.ink, 420));
        c.add(Ui.gap(8));
        Ui.Field confirm = Ui.field("اكتب اسم «" + from.name + "» للتأكيد");
        c.add(confirm);
        c.setPreferredSize(new Dimension(460, c.getPreferredSize().height));
        if (!Ui.form(this, "دمج مكتبين", Ui.rtl(c), "دمج")) return;
        if (!confirm.val().equals(Model.nz(from.name).trim())) {
            Ui.toast("الاسم غير مطابق؛ لم يُدمج شيء.");
            return;
        }
        Model.Office target = others.get(into.getSelectedIndex());
        Ui.busy(this, "جارٍ حفظ نسخة أمان ثم الدمج…", p -> {
            Snapshot.take(app.ctx, db, "دمج «" + from.name + "» في «" + target.name + "»");
            db.mergeOffices(from.id, target.id);
            return null;
        }, r -> {
            app.changed();
            Ui.info(this, "تم الدمج ✓", "صار «" + target.name + "» يحمل حركات المكتبين وأرصدتهما. للتراجع: النسخ الاحتياطي ← نسخ الأمان.");
            app.go("office", target.id);
        });
    }

    void delete(Model.Office o) {
        if (!require(Auth.Perm.DELETE)) return;
        long n = db.officeTxCount(o.id);
        if (n == 0) {
            if (!Ui.confirm(this, "حذف المكتب", "حذف «" + o.name + "» نهائيًا؟ ليس له حركات.", "حذف")) return;
            try {
                db.deleteOffice(o.id, false);
                app.changed();
                app.go("offices");
            } catch (Exception e) {
                Ui.error(this, e);
            }
            return;
        }
        int k = Ui.choose(this, "حذف المكتب", "لـ«" + o.name + "» " + n + " حركة. الأفضل إيقافه: يبقى كشفه ورصيده ولا تُسجَّل عليه حركات جديدة.\n\n"
                + "الحذف النهائي يمسح حركاته وقيودها وصورها (تُحفظ نسخة أمان قبله).", new String[]{"إيقافه فقط", "حذف نهائي مع الحركات", "إلغاء"});
        if (k == 0) {
            db.setOfficeActive(o.id, false);
            refresh();
        } else if (k == 1) {
            String typed = Ui.input(this, "تأكيد الحذف النهائي", "اكتب اسم المكتب «" + o.name + "» للتأكيد", "");
            if (typed == null || !typed.equals(Model.nz(o.name).trim())) {
                Ui.toast("الاسم غير مطابق؛ لم يُحذف شيء.");
                return;
            }
            Ui.busy(this, "جارٍ حفظ نسخة أمان ثم الحذف…", p -> {
                Snapshot.take(app.ctx, db, "حذف «" + o.name + "»");
                db.deleteOffice(o.id, true);
                return null;
            }, r -> {
                app.changed();
                app.go("offices");
            });
        }
    }
}
