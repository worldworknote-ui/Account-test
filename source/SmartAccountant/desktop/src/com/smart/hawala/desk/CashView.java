package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * «صندوق المكتب» على الماك والويندوز — الحساب نفسه في CashBox وDb المشتركين مع الهاتف.
 * 10.0: عدة صناديق (لكل صندوق ربطه بالمكاتب، ويُعطَّل أو يُحذف)، بيع وشراء العملات بالسعر والمقابل، تحويل بين الصناديق،
 * استلام وتسليم لحساب مكتب أو عميل (ولو بعملة أخرى)، تفاصيل كاملة قبل كل تسجيل، جرد يقارن الزيادة بالعجز وسجله، وإخفاء المبالغ.
 */
final class CashView extends View {
    static final Color NAVY = new Color(22, 42, 74), GOLD = new Color(201, 162, 39);
    final JComboBox<String> period = Ui.combo(ReportBuilder.PERIODS);
    final JComboBox<String> kindFilter = Ui.combo(new String[]{"كل الحركات", "استلام", "تسليم", "المصاريف", "بيع وشراء", "عملاء", "لحساب مكاتب", "تحويل", "الملغاة"});
    final JPanel head = Ui.column();
    JTable table;
    List<CashBox.Move> shown = new ArrayList<>();

    CashView(App app) {
        super(app, "صندوق المكتب");
        period.setSelectedIndex(ReportBuilder.P_MONTH);
        period.removeItemAt(ReportBuilder.P_CUSTOM);
        period.addActionListener(e -> fill());
        kindFilter.addActionListener(e -> fill());
    }

    @Override void build() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Ui.bg);
        head.setBorder(new EmptyBorder(4, 24, 8, 24));
        center.add(head, BorderLayout.NORTH);
        table = Ui.table(new DefaultTableModel());
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Ui.bg);
        holder.setBorder(new EmptyBorder(0, 24, 20, 24));
        holder.add(Ui.tableBox(table));
        center.add(holder, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) details(shown.get(table.convertRowIndexToModel(table.getSelectedRow())));
            }
        });
    }

    static String iso(String s) { return Ui.ltr(s); }

    String money(long v, String cur) { return Ui.ltr(Model.fmt(v, db.decimals(cur)) + " " + cur); }

    String m(String s) { return app.hidden() ? "••••" : s; }

    // ---- 10.16: قفل الصندوق (المفاتيح نفسها التي في الهاتف: cash_lock / cash_pin_salt / cash_pin_hash، خاصة بالجهاز) ----
    static long openUntil;
    static final long OPEN_MS = 10 * 60000L;

    boolean lockOn() { return "pin".equals(db.setting("cash_lock", "0")) && !db.setting("cash_pin_hash", "").isEmpty(); }

    void unlock() {
        long until = db.settingLong("cash_locked_until", 0);
        if (until > Clock.now()) {
            Ui.toast("محاولات خاطئة كثيرة. حاول بعد " + Math.max(1, (until - Clock.now()) / 1000) + " ثانية.");
            return;
        }
        JPasswordField f = Ui.password();
        JPanel q = Ui.column();
        q.add(Ui.label("كلمة سر الصندوق", 14, Ui.ink, true));
        q.add(Ui.gap(8));
        q.add(f);
        if (!Ui.form(this, "فتح الصندوق", q, "فتح")) return;
        if (Pin.verify(new String(f.getPassword()), db.setting("cash_pin_salt", ""), db.setting("cash_pin_hash", ""))) {
            db.setSettingQuiet("cash_fails", "0");
            openUntil = Clock.now() + OPEN_MS;
            refresh();
            return;
        }
        int fails = (int) db.settingLong("cash_fails", 0) + 1;
        db.setSettingQuiet("cash_fails", "" + fails);
        long wait = Pin.lockoutMillis(fails);
        if (wait > 0) {
            db.setSettingQuiet("cash_locked_until", "" + (Clock.now() + wait));
            Ui.toast("محاولات خاطئة كثيرة. انتظر " + wait / 1000 + " ثانية.");
        } else Ui.toast("كلمة السر غير صحيحة");
    }

    void lockSetup() {
        if (lockOn()) {
            if (!Ui.confirm(this, "قفل الصندوق", "الصندوق مقفل بكلمة سر على هذا الجهاز. إلغاء القفل؟ (لتغيير كلمة السر: ألغِ القفل ثم فعّله من جديد)", "إلغاء القفل")) return;
            UsersView.confirmAdmin(this, db, "إلغاء قفل الصندوق", () -> {
                db.setSettingQuiet("cash_lock", "0");
                db.audit("SETTING", "settings", 0, "cash_lock off");
                Ui.toast("أُلغي قفل الصندوق");
                refresh();
            });
            return;
        }
        JPasswordField p1 = Ui.password(), p2 = Ui.password();
        JPanel q = Ui.column();
        q.add(Ui.para("كلمة سر خاصة بشاشة الصندوق على هذا الجهاز (4 أحرف أو أرقام على الأقل). تُطلب عند كل دخول إلى الصندوق.", 14, Ui.ink, 420));
        q.add(Ui.gap(8));
        q.add(Ui.labeled("كلمة السر", p1));
        q.add(Ui.labeled("تأكيدها", p2));
        if (!Ui.form(this, "قفل الصندوق", q, "تفعيل القفل")) return;
        String s1 = new String(p1.getPassword()), s2 = new String(p2.getPassword());
        if (s1.length() < 4) {
            Ui.toast("كلمة السر قصيرة (4 على الأقل)");
            return;
        }
        if (!s1.equals(s2)) {
            Ui.toast("الكلمتان غير متطابقتين");
            return;
        }
        String salt = Pin.newSalt();
        db.setSettingQuiet("cash_pin_salt", salt);
        db.setSettingQuiet("cash_pin_hash", Pin.hash(s1, salt));
        db.setSettingQuiet("cash_lock", "pin");
        db.setSettingQuiet("cash_fails", "0");
        db.audit("SETTING", "settings", 0, "cash_lock on");
        openUntil = Clock.now() + OPEN_MS;
        Ui.toast("فُعّل قفل الصندوق ✓");
        refresh();
    }

    List<CashBox.Config> activeBoxes() {
        List<CashBox.Config> out = new ArrayList<>();
        for (CashBox.Config c : db.cashBoxes()) if (c.on && c.active) out.add(c);
        return out;
    }

    @Override void refresh() {
        actions.removeAll();
        head.removeAll();
        CashBox.Config cfg = db.cashConfig();
        if (!cfg.on) {
            subtitle.setText("النقد الموجود لديك فعلًا بكل عملة — مستقل عن أرصدة المكاتب");
            if (can(Auth.Perm.SETTINGS)) action(Ui.primary("إعداد الصندوق", e -> setup(true)));
            head.add(Ui.para("الصندوق لم يُعَد بعد. حساباته مستقلة عن أرصدة المكاتب ولا تؤثر عليها: تسجّل فيه ما يدخل ويخرج من النقد بكل عملة مع خانة للأجور، "
                    + "وحين تسلّم أو تستلم لحساب مكتب أو عميل يُسجَّل على حسابه مباشرة. وتبيع وتشتري العملات، وتضيف المصاريف، وتجرد الصندوق متى شئت.", 14, Ui.ink, textWidth()));
            if (table != null) table.setModel(new DefaultTableModel());
            done();
            return;
        }
        // 10.16: قفل الصندوق بكلمة سر خاصة (كالهاتف): يُفتح لهذه الزيارة ويُقفل عند مغادرة الشاشة أو بعد 10 دقائق
        if (lockOn() && Clock.now() > openUntil) {
            titleLabel.setText(cfg.name);
            subtitle.setText("الصندوق مقفل بكلمة سر");
            action(Ui.primary("فتح الصندوق", e -> unlock()));
            head.add(Ui.para("هذا الصندوق مقفل بكلمة سر خاصة به على هذا الجهاز. اضغط «فتح الصندوق» واكتبها.", 14, Ui.ink, textWidth()));
            if (table != null) table.setModel(new DefaultTableModel());
            done();
            return;
        }
        if (lockOn()) openUntil = Clock.now() + OPEN_MS;
        titleLabel.setText(cfg.name);
        subtitle.setText(cfg.strict ? "هذا الصندوق مربوط بالمكاتب: الاستلام والتسليم لحساب مكتب أو عميل فقط" : "النقد الموجود لديك بكل عملة — مستقل عن أرصدة المكاتب");
        boolean internal = !cfg.strict || cfg.allowInternal;
        List<CashBox.Config> boxes = activeBoxes();
        if (can(Auth.Perm.CASH)) {
            action(Ui.button("استلام", Ui.green, e -> move(CashBox.IN, null)));
            action(Ui.button("تسليم", Ui.red, e -> move(CashBox.OUT, null)));
            if (internal) {
                action(Ui.ghost("مصروف", e -> expense()));
                action(Ui.button("بيع", NAVY, e -> trade(CashBox.SELL)));
                action(Ui.button("شراء", new Color(18, 110, 120), e -> trade(CashBox.BUY)));
            }
            if (boxes.size() > 1) action(Ui.ghost("تحويل", e -> transfer()));
            action(Ui.ghost("جرد", e -> count()));
        }
        action(Ui.ghost("سجل الجرد", e -> countLog()));
        action(Ui.ghost("التقرير…", e -> report()));
        if (can(Auth.Perm.SETTINGS)) action(Ui.ghost("الإعدادات", e -> setup(false)));
        if (can(Auth.Perm.SETTINGS)) action(Ui.ghost(lockOn() ? "قفل الصندوق ✓" : "قفل الصندوق…", e -> lockSetup()));

        // الصناديق
        JPanel boxRow = Ui.row();
        boxRow.add(Ui.label("الصندوق:", 13, Ui.muted, true));
        for (CashBox.Config b : boxes) {
            JButton bt = b.id == cfg.id ? Ui.primary(b.name, null) : Ui.ghost(b.name, e -> {
                db.setCurrentBox(b.id);
                refresh();
            });
            boxRow.add(Box.createHorizontalStrut(6));
            boxRow.add(bt);
        }
        if (can(Auth.Perm.SETTINGS)) {
            boxRow.add(Box.createHorizontalStrut(6));
            boxRow.add(Ui.ghost("+ صندوق", e -> boxForm()));
            boxRow.add(Box.createHorizontalStrut(6));
            boxRow.add(Ui.ghost("إدارة الصناديق", e -> boxes()));
        }
        boxRow.add(Box.createHorizontalStrut(6));
        boxRow.add(Ui.ghost(app.hidden() ? "إظهار المبالغ" : "إخفاء المبالغ", e -> {
            app.toggleHidden();
            refresh();
        }));
        boxRow.add(Box.createHorizontalGlue());
        head.add(boxRow);
        head.add(Ui.gap(8));

        Map<String, Long> bal = db.cashBalances(cfg.id);
        Map<String, Integer> dec = db.decimalsMap();
        Ui.Card hero = Ui.tinted(NAVY, NAVY);
        JPanel row = Ui.row();
        Ledger.Valuation val = Ledger.valueOffice(bal, db.usdRates(), dec);
        JLabel tot = Ui.label("≈ " + m(Ui.ltr(Model.fmt(val.netUsd().setScale(0, RoundingMode.HALF_EVEN).longValue(), 0) + " USD")), 24, Color.WHITE, true);
        JPanel left = Ui.column();
        left.add(Ui.label("النقد في الصندوق (مقوّمًا بالدولار)", 13, new Color(196, 210, 232), false));
        left.add(tot);
        if (!val.missingRates.isEmpty())
            left.add(Ui.label("بلا سعر صرف: " + String.join("، ", val.missingRates) + " — حدّثها من إعدادات الصندوق", 12, new Color(255, 214, 140), false));
        List<CashBox.Count> counts = db.cashCounts(cfg.id);
        left.add(Ui.label(counts.isEmpty() ? "لم يُجرد الصندوق بعد" : "آخر جرد: " + Model.date(counts.get(0).createdAt), 12, new Color(170, 186, 212), false));
        row.add(left);
        row.add(Box.createHorizontalGlue());
        for (Map.Entry<String, Long> e : bal.entrySet()) {
            JPanel c = Ui.column();
            c.add(Ui.label(e.getKey(), 12, GOLD, true));
            long v = e.getValue();
            c.add(Ui.label(m(Ui.ltr((v < 0 ? "عجز " : "") + Model.fmt(Math.abs(v), dec.getOrDefault(e.getKey(), 2)))), 19, v < 0 ? new Color(255, 170, 170) : Color.WHITE, true));
            c.setBorder(new EmptyBorder(0, 18, 0, 0));
            row.add(c);
        }
        hero.add(row);
        head.add(hero);
        head.add(Ui.gap(10));
        head.add(Ui.row(Ui.label("الفترة", 13, Ui.muted, true), period, Ui.label("النوع", 13, Ui.muted, true), kindFilter, Box.createHorizontalGlue()));
        period.setMaximumSize(new Dimension(200, 38));
        kindFilter.setMaximumSize(new Dimension(200, 38));
        Ui.rtl(head);
        fill();
        done();
    }

    boolean passes(CashBox.Move m) {
        switch (kindFilter.getSelectedIndex()) {
            case 1: return CashBox.IN.equals(m.kind) && !m.cancelled();
            case 2: return CashBox.OUT.equals(m.kind) && !m.cancelled();
            case 3: return CashBox.EXPENSE.equals(m.kind) && !m.cancelled();
            case 4: return CashBox.EXCHANGE.equals(m.kind) && !m.cancelled();
            case 5: return m.clientId != null;
            case 6: return m.officeId != null;
            case 7: return CashBox.TRANSFER.equals(m.kind);
            case 8: return m.cancelled();
            default: return true;
        }
    }

    String title(CashBox.Move m, Map<Long, String> names) {
        if (m.officeId != null) return (m.dir > 0 ? "استلام لحساب " : "تسليم لحساب ") + names.getOrDefault(m.officeId, "مكتب");
        if (m.clientId != null) return (m.dir > 0 ? "استلام من العميل " : "تسليم للعميل ") + m.party;
        if (CashBox.EXCHANGE.equals(m.kind)) {
            if (CashBox.CAT_SELL.equals(m.category) || CashBox.CAT_BUY.equals(m.category))
                return (m.mainLeg() ? m.category : (CashBox.CAT_SELL.equals(m.category) ? "مقابل البيع" : "مقابل الشراء")) + (Model.nz(m.rate).isEmpty() ? "" : " بسعر " + m.rate);
            return m.dir > 0 ? "صرف عملة — داخل" : "صرف عملة — خارج";
        }
        if (CashBox.TRANSFER.equals(m.kind)) return m.dir > 0 ? "تحويل وارد" : "تحويل إلى صندوق آخر";
        if (CashBox.ADJUST.equals(m.kind)) return m.dir > 0 ? "جرد — زيادة" : "جرد — عجز";
        if (CashBox.OPENING.equals(m.kind)) return "رصيد افتتاحي";
        return CashBox.kindName(m.kind) + (m.category.isEmpty() ? "" : " — " + m.category);
    }

    void fill() {
        if (table == null || !db.cashConfig().on) return;
        long[] r = ReportBuilder.period(period.getSelectedIndex(), Clock.now());
        Map<String, Integer> dec = db.decimalsMap();
        Map<Long, String> names = db.officeNames();
        boolean hide = app.hidden();
        DefaultTableModel mdl = new DefaultTableModel(new Object[]{"التاريخ", "الحركة", "الشخص / البيان", "داخل", "خارج", "العملة", "أجور نقدًا", "الحالة"}, 0) {
            @Override public boolean isCellEditable(int a, int b) { return false; }

            @Override public Class<?> getColumnClass(int c) { return !hide && (c == 3 || c == 4) ? Ui.Money.class : String.class; }
        };
        Ui.Keep keep = Ui.Keep.of(table, shown, x -> x.id);   // 10.5 (§6)
        shown = new ArrayList<>();
        List<CashBox.Move> all = db.cashMoves(db.currentBox());
        for (int i = all.size() - 1; i >= 0; i--) {
            CashBox.Move x = all.get(i);
            if (x.createdAt < r[0] || (r[1] > 0 && x.createdAt >= r[1]) || !passes(x)) continue;
            shown.add(x);
            int d = dec.getOrDefault(x.currency, 2);
            String who = x.clientId != null ? "" : x.party;
            if (!x.description.isEmpty()) who = who.isEmpty() ? x.description : who + " — " + x.description;
            long fc = x.feeCash();
            Object in = hide ? (x.dir > 0 ? "••••" : "") : new Ui.Money(x.dir > 0 ? x.amount : 0, d, false);
            Object out = hide ? (x.dir < 0 ? "••••" : "") : new Ui.Money(x.dir < 0 ? x.amount : 0, d, false);
            mdl.addRow(new Object[]{Model.date(x.createdAt), title(x, names), who, in, out, x.currency,
                    fc == 0 || hide ? "" : (fc > 0 ? "لنا " : "علينا ") + Model.fmt(Math.abs(fc), d), x.stateText()});
        }
        table.setModel(mdl);
        table.setDefaultRenderer(Ui.Money.class, new Ui.Cell(SwingConstants.CENTER));
        Ui.widths(table, 130, 240, 240, 110, 110, 60, 100, 110);
        keep.restore(table, shown, x -> x.id);
        table.applyComponentOrientation(Ui.RTL);
    }

    void details(CashBox.Move m) {
        int d = db.decimals(m.currency);
        StringBuilder b = new StringBuilder(title(m, db.officeNames())).append("\n");
        b.append(m.dir > 0 ? "دخل الصندوق: " : "خرج من الصندوق: ").append(money(m.amount, m.currency)).append("\n");
        if (m.fee > 0) b.append("الأجور: ").append(Model.fmt(m.fee, d)).append(CashBox.FEE_US.equals(m.feeSide) ? " لنا" : " علينا")
                .append(m.feeInCash ? " — نقدًا في الصندوق" : " — على حساب المكتب").append("\n");
        if (!Model.nz(m.rate).isEmpty()) b.append("السعر: ").append(m.rate).append("\n");
        if (m.pairUuid != null && CashBox.EXCHANGE.equals(m.kind))
            for (CashBox.Move o : db.cashMoves(m.boxId))
                if (m.pairUuid.equals(o.pairUuid) && o.id != m.id) b.append(o.dir > 0 ? "ودخل مقابلها: " : "وخرج مقابلها: ").append(money(o.amount, o.currency)).append("\n");
        if (!m.party.isEmpty()) b.append(m.clientId != null ? "العميل: " : "الشخص: ").append(m.party).append("\n");
        if (!m.description.isEmpty()) b.append("البيان: ").append(m.description).append("\n");
        if (m.officeId != null) b.append("مسجّلة أيضًا على المكتب (").append(m.dir > 0 ? "علينا" : "لنا").append(") برقم ").append(m.txId).append("\n");
        b.append("التاريخ: ").append(Model.date(m.createdAt)).append("\nبواسطة: ").append(m.userName);
        if (!m.stateText().isEmpty()) b.append("\nالحالة: ").append(m.stateText());
        if (m.cancelled() || !can(Auth.Perm.TX_CANCEL)) {
            Ui.info(this, "حركة صندوق", b.toString());
            return;
        }
        int k = Ui.choose(this, "حركة صندوق", b.toString(), new String[]{"إغلاق", "إلغاء الحركة"});
        if (k != 1) return;
        String msg = m.officeId != null ? "تُلغى معها حركة المكتب المرتبطة بقيد عكسي." : m.clientId != null ? "تُلغى معها الحركة على حساب العميل."
                : CashBox.EXCHANGE.equals(m.kind) ? "تُلغى العملة ومقابلها معًا." : CashBox.TRANSFER.equals(m.kind) ? "يُلغى التحويل في الصندوقين معًا." : "";
        String reason = Ui.input(this, "إلغاء حركة الصندوق", msg + " السبب:", "");
        if (reason == null) return;
        try {
            db.cancelCashMove(m.id, reason);
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    // ===================================================================================
    // التفاصيل قبل التسجيل
    // ===================================================================================

    long when(Ui.DateField f) throws Exception {
        long s = f.start();
        long today = Ui.startOfDay(Clock.now());
        if (s == 0 || s == today) return Clock.now();
        if (s > today) throw new IllegalArgumentException("لا يمكن اختيار تاريخ في المستقبل");
        return s + 12 * 3600000L;
    }

    static String opName(CashBox.Input x) {
        switch (x.kind) {
            case CashBox.IN: return x.officeId != null ? "استلام لحساب مكتب" : x.clientId != null ? "استلام من عميل" : "استلام إلى الصندوق";
            case CashBox.OUT: return x.officeId != null ? "تسليم لحساب مكتب" : x.clientId != null ? "تسليم لعميل" : "تسليم من الصندوق";
            case CashBox.EXPENSE: return "مصروف";
            case CashBox.EXCHANGE: return CashBox.BUY.equals(x.side) ? "شراء عملة" : "بيع عملة";
            case CashBox.TRANSFER: return "تحويل بين الصناديق";
            default: return CashBox.kindName(x.kind);
        }
    }

    String side(long v, String cur) { return v == 0 ? "صفر" : ClientBook.side(v) + " " + money(Math.abs(v), cur); }

    /** نص التفاصيل الكاملة قبل الحفظ، مع رصيد الصندوق قبل ← بعد لكل عملة تتأثر. */
    String preview(CashBox.Input x) {
        CashBox.Config cfg = db.cashBox(x.boxId);
        Map<String, Integer> dec = db.decimalsMap();
        Map<String, Long> bal = db.cashBalances(x.boxId);
        StringBuilder b = new StringBuilder();
        b.append("العملية: ").append(opName(x)).append("\nالصندوق: ").append(cfg.name).append('\n');
        if (CashBox.EXCHANGE.equals(x.kind)) {
            boolean buy = CashBox.BUY.equals(x.side);
            b.append(buy ? "تشتري: " : "تبيع: ").append(money(x.amount, x.currency)).append('\n');
            if (!Model.nz(x.rate).isEmpty()) b.append("السعر: ").append(Ui.ltr("1 " + x.currency + " = " + x.rate + " " + x.toCurrency)).append('\n');
            b.append(buy ? "وتدفع: " : "وتستلم: ").append(money(x.toAmount, x.toCurrency)).append('\n');
            BigDecimal mk = CashBox.crossRate(x.currency, x.toCurrency, db.usdRates());
            if (mk != null) {
                BigDecimal diff = BigDecimal.valueOf(x.toAmount, CashBox.dec(dec, x.toCurrency)).subtract(BigDecimal.valueOf(x.amount, CashBox.dec(dec, x.currency)).multiply(mk));
                if (buy) diff = diff.negate();
                b.append("سعر السوق: ").append(Ui.ltr("1 " + x.currency + " = " + mk.toPlainString() + " " + x.toCurrency)).append(" — ")
                        .append(diff.signum() >= 0 ? "لصالحك ≈ " : "عليك ≈ ").append(Ui.ltr(diff.abs().setScale(2, RoundingMode.HALF_EVEN) + " " + x.toCurrency)).append('\n');
            }
        } else b.append(x.dir() > 0 ? "يدخل الصندوق: " : "يخرج من الصندوق: ").append(money(x.amount, x.currency)).append('\n');
        if (!Model.nz(x.category).isEmpty() && x.officeId == null && x.clientId == null) b.append("البند: ").append(x.category).append('\n');
        if (x.officeId != null) {
            Model.Office o = db.office(x.officeId);
            b.append("المكتب: ").append(o == null ? "#" + x.officeId : o.name).append(" — ").append(CashBox.IN.equals(x.kind) ? "علينا " : "لنا ").append(money(x.amount, x.currency)).append('\n');
        }
        if (x.clientId != null) {
            ClientBook.Client c = db.client(x.clientId);
            b.append("العميل: ").append(c == null ? "#" + x.clientId : c.name).append(" — ").append(CashBox.IN.equals(x.kind) ? "علينا له " : "لنا عليه ")
                    .append(money(x.clientAmount, x.clientCurrency)).append('\n');
            if (!x.clientCurrency.equals(x.currency))
                b.append("السعر: ").append(Ui.ltr("1 " + x.clientCurrency + " = " + ClientBook.rateOf(x.clientAmount, CashBox.dec(dec, x.clientCurrency), x.amount, CashBox.dec(dec, x.currency)) + " " + x.currency)).append('\n');
            Map<String, Long> cb = ClientBook.balances(db.clientMoves(x.clientId));
            long before = cb.getOrDefault(x.clientCurrency, 0L), after = before + (CashBox.IN.equals(x.kind) ? -x.clientAmount : x.clientAmount);
            b.append("رصيده ").append(x.clientCurrency).append(": ").append(side(before, x.clientCurrency)).append(" ← ").append(side(after, x.clientCurrency)).append('\n');
        }
        if (x.fee > 0) b.append("الأجور: ").append(money(x.fee, x.currency)).append(CashBox.FEE_US.equals(x.feeSide) ? " لنا" : " علينا")
                .append(x.officeId == null || x.feeInCash ? " — نقدًا في الصندوق" : " — على حساب المكتب").append('\n');
        if (!Model.nz(x.party).isEmpty() && x.clientId == null) b.append("الشخص: ").append(x.party).append('\n');
        if (!Model.nz(x.description).isEmpty()) b.append("البيان: ").append(x.description).append('\n');
        b.append("التاريخ: ").append(Model.date(x.when)).append("\n\nرصيد الصندوق قبل ← بعد:\n");
        for (Map.Entry<String, Long> e : CashBox.changes(x).entrySet()) {
            long bb = bal.getOrDefault(e.getKey(), 0L);
            int d = CashBox.dec(dec, e.getKey());
            b.append(e.getKey()).append(": ").append(Ui.ltr(Model.fmt(bb, d))).append(" ← ").append(Ui.ltr(Model.fmt(bb + e.getValue(), d)))
                    .append(" (").append(Ui.ltr((e.getValue() >= 0 ? "+" : "−") + Model.fmt(Math.abs(e.getValue()), d))).append(")\n");
        }
        if (CashBox.TRANSFER.equals(x.kind) && x.toBox != null) {
            CashBox.Config to = db.cashBox(x.toBox);
            long tb = db.cashBalances(x.toBox).getOrDefault(x.currency, 0L);
            int d = CashBox.dec(dec, x.currency);
            b.append("«").append(to.name).append("» ").append(x.currency).append(": ").append(Ui.ltr(Model.fmt(tb, d))).append(" ← ").append(Ui.ltr(Model.fmt(tb + x.amount, d))).append('\n');
        }
        return b.toString();
    }

    void save(CashBox.Input x) {
        CashBox.Config cfg = db.cashBox(x.boxId);
        String err = CashBox.validate(x, cfg, Clock.now());
        if (err != null) {
            Ui.error(this, new IllegalArgumentException(err));
            return;
        }
        String neg = CashBox.negativeAfter(x, db.cashBalances(x.boxId), db.decimalsMap());
        String text = (neg == null ? "" : "! " + neg + "\n\n") + preview(x);
        if (!Ui.confirm(this, "راجع قبل التسجيل", text, neg == null ? "تسجيل" : "تسجيل رغم ذلك")) return;
        try {
            db.addCashMove(x);
            app.changed();
            Ui.toast("سُجّلت الحركة ✓");
            refresh();
            View cv = app.views.get("clients");
            if (cv != null) cv.refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    // ===================================================================================
    // الاستلام والتسليم (بدون حساب، لحساب مكتب، أو لحساب عميل ولو بعملة أخرى)
    // ===================================================================================

    /** 10.18: المكتب الذي يُختار مسبقًا في نافذة التسليم/الاستلام (من زر «الصندوق» في صفحة المكتب). */
    Long presetOffice;

    void move(String kind, ClientBook.Client preset) {
        CashBox.Config cfg = db.cashConfig();
        boolean in = CashBox.IN.equals(kind);
        JComboBox<String> link = Ui.combo(cfg.strict ? new String[]{"لحساب مكتب", "لحساب عميل"} : new String[]{"لحساب مكتب", "لحساب عميل", "بدون حساب"});
        List<Model.Office> offs = new ArrayList<>();
        for (Model.Office o : db.offices()) if (o.active) offs.add(o);
        String[] on = new String[offs.size()];
        for (int i = 0; i < on.length; i++) on[i] = offs.get(i).name;
        JComboBox<String> office = Ui.combo(on);
        // 10.18: من صفحة المكتب يُفتح على المكتب نفسه
        if (presetOffice != null) for (int i = 0; i < offs.size(); i++) if (offs.get(i).id == presetOffice) office.setSelectedIndex(i);
        presetOffice = null;
        List<ClientBook.Client> cls = new ArrayList<>();
        for (ClientBook.Client k : db.clients()) if (k.active) cls.add(k);
        String[] cn = new String[cls.size()];
        for (int i = 0; i < cn.length; i++) cn[i] = cls.get(i).name;
        JComboBox<String> client = Ui.combo(cn);
        List<String> cats = in ? cfg.inCats : cfg.outCats;
        JComboBox<String> cat = Ui.combo(cats.toArray(new String[0]));
        JComboBox<String> cur = Ui.combo(cfg.currencies.toArray(new String[0]));
        List<String> all = new ArrayList<>();
        for (Model.Currency c : db.currencies(false)) all.add(c.code);
        JComboBox<String> ccur = Ui.combo(all.toArray(new String[0]));
        JCheckBox other = Ui.check(in ? "يُضاف إلى حسابه بعملة أخرى" : "يُخصم من حسابه بعملة أخرى", false);
        Ui.Field amount = Ui.numeric("المبلغ"), camt = Ui.numeric("المبلغ على حسابه (بعملته)"), party = Ui.field(in ? "ممن استلمت؟" : "لمن سلّمت؟"), desc = Ui.field("البيان (اختياري)");
        JComboBox<String> fee = Ui.combo(new String[]{"بدون أجور", "أجور لنا", "أجور علينا"});
        Ui.Field feeAmt = Ui.numeric("مبلغ الأجور");
        JComboBox<String> feeWhere = Ui.combo(new String[]{"على حساب المكتب", "نقدًا في الصندوق"});
        Ui.DateField date = new Ui.DateField(Clock.now());
        JPanel c = new JPanel(new GridLayout(0, 2, 12, 10));
        c.setOpaque(false);
        c.add(Ui.labeled("الحساب", link));
        c.add(Ui.labeled("المكتب", office));
        c.add(Ui.labeled("العميل", client));
        c.add(Ui.labeled("البند (بدون حساب)", cat));
        c.add(Ui.labeled("عملة النقد", cur));
        c.add(Ui.labeled("المبلغ", amount));
        c.add(Ui.labeled("حساب العميل", other));
        c.add(Ui.labeled("عملة حسابه", ccur));
        c.add(Ui.labeled("المبلغ على حسابه", camt));
        c.add(Ui.labeled(in ? "ممن استلمت" : "لمن سلّمت", party));
        c.add(Ui.labeled("البيان", desc));
        c.add(Ui.labeled("التاريخ", date));
        c.add(Ui.labeled("الأجور", fee));
        c.add(Ui.labeled("مبلغ الأجور", feeAmt));
        c.add(Ui.labeled("الأجور تُقيَّد", feeWhere));
        Runnable sync = () -> {
            int ls = link.getSelectedIndex();
            office.setEnabled(ls == 0);
            feeWhere.setEnabled(ls == 0);
            client.setEnabled(ls == 1);
            other.setEnabled(ls == 1);
            ccur.setEnabled(ls == 1 && other.isSelected());
            camt.setEnabled(ls == 1 && other.isSelected());
            party.setEnabled(ls != 1);
            cat.setEnabled(ls == 2);
            feeAmt.setEnabled(fee.getSelectedIndex() > 0);
        };
        link.addActionListener(e -> sync.run());
        other.addActionListener(e -> sync.run());
        fee.addActionListener(e -> sync.run());
        if (preset != null) {
            link.setSelectedIndex(1);
            for (int i = 0; i < cls.size(); i++) if (cls.get(i).id == preset.id) client.setSelectedIndex(i);
        } else if (!cfg.strict) link.setSelectedIndex(2);
        sync.run();
        JPanel wrap = Ui.column();
        wrap.add(Ui.para((in ? "يدخل الصندوق «" : "يخرج من الصندوق «") + cfg.name + "». لحساب مكتب: يُسجَّل على المكتب " + (in ? "«علينا»" : "«لنا»")
                + ". لحساب عميل: " + (in ? "«علينا له»" : "«لنا عليه»") + "، ولو بعملة غير عملة النقد (له 100 يورو فيأخذ دولارًا مثلًا).", 13, Ui.muted, 600));
        wrap.add(Ui.gap(8));
        wrap.add(c);
        if (!Ui.form(this, in ? "استلام إلى الصندوق" : "تسليم من الصندوق", Ui.rtl(wrap), "مراجعة")) return;
        try {
            CashBox.Input x = new CashBox.Input();
            x.kind = kind;
            x.boxId = cfg.id;
            int ls = link.getSelectedIndex();
            if (ls == 0) {
                if (offs.isEmpty()) throw new IllegalArgumentException("لا توجد مكاتب نشطة");
                x.officeId = offs.get(office.getSelectedIndex()).id;
            } else if (ls == 1) {
                if (cls.isEmpty()) throw new IllegalArgumentException("لا يوجد عملاء: أضفهم من «حسابات العملاء»");
                x.clientId = cls.get(client.getSelectedIndex()).id;
            }
            x.currency = (String) cur.getSelectedItem();
            int d = db.decimals(x.currency);
            x.amount = Db.minor(amount.val(), d);
            if (x.clientId != null) {
                if (other.isSelected()) {
                    x.clientCurrency = (String) ccur.getSelectedItem();
                    if (x.clientCurrency.equals(x.currency)) throw new IllegalArgumentException("اختر عملة غير عملة النقد");
                    x.clientAmount = Db.minor(camt.val(), db.decimals(x.clientCurrency));
                } else {
                    x.clientCurrency = x.currency;
                    x.clientAmount = x.amount;
                }
            } else x.party = party.val();
            x.description = desc.val();
            if (ls == 2) x.category = (String) cat.getSelectedItem();
            if (fee.getSelectedIndex() > 0) {
                x.fee = Db.minor(feeAmt.val(), d);
                x.feeSide = fee.getSelectedIndex() == 1 ? CashBox.FEE_US : CashBox.FEE_THEM;
                x.feeInCash = ls != 0 || feeWhere.getSelectedIndex() == 1;
            }
            x.when = when(date);
            save(x);
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void expense() {
        CashBox.Config cfg = db.cashConfig();
        JComboBox<String> cat = Ui.combo(cfg.expenseCats.toArray(new String[0]));
        JComboBox<String> cur = Ui.combo(cfg.currencies.toArray(new String[0]));
        Ui.Field amount = Ui.numeric("المبلغ"), party = Ui.field("لمن؟ (اختياري)"), desc = Ui.field("البيان (مثل: راتب شهر 10)"), newCat = Ui.field("أو بند جديد");
        Ui.DateField date = new Ui.DateField(Clock.now());
        JPanel c = new JPanel(new GridLayout(0, 2, 12, 10));
        c.setOpaque(false);
        c.add(Ui.labeled("البند", cat));
        c.add(Ui.labeled("بند جديد (اختياري)", newCat));
        c.add(Ui.labeled("العملة", cur));
        c.add(Ui.labeled("المبلغ", amount));
        c.add(Ui.labeled("لمن", party));
        c.add(Ui.labeled("البيان", desc));
        c.add(Ui.labeled("التاريخ", date));
        if (!Ui.form(this, "مصروف من الصندوق", Ui.rtl(c), "مراجعة")) return;
        try {
            CashBox.Input x = new CashBox.Input();
            x.kind = CashBox.EXPENSE;
            x.boxId = cfg.id;
            x.category = newCat.val().isEmpty() ? (String) cat.getSelectedItem() : newCat.val();
            if (!newCat.val().isEmpty()) db.addCashCategory(CashBox.EXPENSE, newCat.val());
            x.currency = (String) cur.getSelectedItem();
            x.amount = Db.minor(amount.val(), db.decimals(x.currency));
            x.party = party.val();
            x.description = desc.val();
            x.when = when(date);
            save(x);
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    // ===================================================================================
    // البيع والشراء
    // ===================================================================================

    void trade(String side) {
        CashBox.Config cfg = db.cashConfig();
        if (cfg.currencies.size() < 2) {
            Ui.info(this, "بيع وشراء", "أضف عملة ثانية للصندوق من إعداداته أولًا.");
            return;
        }
        boolean buy = CashBox.BUY.equals(side);
        String[] cs = cfg.currencies.toArray(new String[0]);
        JComboBox<String> cur = Ui.combo(cs), counter = Ui.combo(cs);
        counter.setSelectedIndex(1);
        Ui.Field amount = Ui.numeric("الكمية"), rate = Ui.numeric("السعر"), toAmt = Ui.numeric("المقابل (فارغ = الكمية × السعر)"), party = Ui.field("مع من؟ (اختياري)"),
                desc = Ui.field("البيان (اختياري)");
        JLabel market = Ui.label("", 12.5f, Ui.primaryInk, true);
        Runnable mk = () -> {
            BigDecimal r = CashBox.crossRate((String) cur.getSelectedItem(), (String) counter.getSelectedItem(), db.usdRates());
            market.setText(r == null ? "لا يوجد سعر سوق لهما" : "سعر السوق: " + Ui.ltr("1 " + cur.getSelectedItem() + " = " + r.toPlainString() + " " + counter.getSelectedItem()));
        };
        cur.addActionListener(e -> mk.run());
        counter.addActionListener(e -> mk.run());
        mk.run();
        Ui.DateField date = new Ui.DateField(Clock.now());
        JPanel c = new JPanel(new GridLayout(0, 2, 12, 10));
        c.setOpaque(false);
        c.add(Ui.labeled(buy ? "1) العملة التي تشتريها (تدخل)" : "1) العملة التي تبيعها (تخرج)", cur));
        c.add(Ui.labeled("الكمية", amount));
        c.add(Ui.labeled(buy ? "2) تدفع مقابلها بـ (تخرج)" : "2) تستلم مقابلها بـ (تدخل)", counter));
        c.add(Ui.labeled("3) السعر: كم من المقابلة لكل 1", rate));
        c.add(Ui.labeled("المقابل", toAmt));
        c.add(Ui.labeled("مع من", party));
        c.add(Ui.labeled("البيان", desc));
        c.add(Ui.labeled("التاريخ", date));
        JPanel wrap = Ui.column();
        wrap.add(market);
        wrap.add(Ui.gap(6));
        wrap.add(c);
        if (!Ui.form(this, buy ? "شراء عملة" : "بيع عملة", Ui.rtl(wrap), "مراجعة")) return;
        try {
            CashBox.Input x = new CashBox.Input();
            x.kind = CashBox.EXCHANGE;
            x.side = side;
            x.boxId = cfg.id;
            x.currency = (String) cur.getSelectedItem();
            x.toCurrency = (String) counter.getSelectedItem();
            int d1 = db.decimals(x.currency), d2 = db.decimals(x.toCurrency);
            x.amount = Db.minor(amount.val(), d1);
            if (toAmt.val().isEmpty()) {
                Long ca = CashBox.counterAmount(x.amount, d1, rate.val(), d2);
                if (ca == null) throw new IllegalArgumentException("اكتب السعر أو المبلغ المقابل");
                x.toAmount = ca;
            } else x.toAmount = Db.minor(toAmt.val(), d2);
            BigDecimal rv = CashBox.rateDecimal(rate.val());
            if (!rate.val().isEmpty() && rv == null) throw new IllegalArgumentException("السعر غير مفهوم: اكتبه مثل 0.92 أو 13500");
            x.rate = rv == null ? ClientBook.rateOf(x.amount, d1, x.toAmount, d2) : rv.toPlainString();
            x.party = party.val();
            x.description = desc.val();
            x.when = when(date);
            save(x);
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void transfer() {
        CashBox.Config cfg = db.cashConfig();
        List<CashBox.Config> others = new ArrayList<>();
        for (CashBox.Config b : activeBoxes()) if (b.id != cfg.id) others.add(b);
        if (others.isEmpty()) return;
        String[] names = new String[others.size()];
        for (int i = 0; i < names.length; i++) names[i] = others.get(i).name;
        JComboBox<String> to = Ui.combo(names), cur = Ui.combo(cfg.currencies.toArray(new String[0]));
        Ui.Field amount = Ui.numeric("المبلغ"), desc = Ui.field("البيان (اختياري)");
        Ui.DateField date = new Ui.DateField(Clock.now());
        JPanel c = new JPanel(new GridLayout(0, 2, 12, 10));
        c.setOpaque(false);
        c.add(Ui.labeled("إلى الصندوق", to));
        c.add(Ui.labeled("العملة", cur));
        c.add(Ui.labeled("المبلغ", amount));
        c.add(Ui.labeled("البيان", desc));
        c.add(Ui.labeled("التاريخ", date));
        if (!Ui.form(this, "تحويل من «" + cfg.name + "»", Ui.rtl(c), "مراجعة")) return;
        try {
            CashBox.Input x = new CashBox.Input();
            x.kind = CashBox.TRANSFER;
            x.boxId = cfg.id;
            x.toBox = others.get(to.getSelectedIndex()).id;
            x.currency = (String) cur.getSelectedItem();
            x.amount = Db.minor(amount.val(), db.decimals(x.currency));
            x.description = desc.val();
            x.when = when(date);
            save(x);
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    // ===================================================================================
    // الجرد ومراجعته وسجله
    // ===================================================================================

    void count() {
        CashBox.Config cfg = db.cashConfig();
        // 10.5 (AUDIT-028): المسجّل = الرصيد + نقد حركات مكاتب بانتظار الاعتماد (موجود فعلًا ولم يُحسب بعد)
        Map<String, Long> bal = db.cashExpected(cfg.id);
        Map<String, Long> pend = db.cashPending(cfg.id);
        Map<String, Ui.Field> fields = new LinkedHashMap<>();
        JPanel c = new JPanel(new GridLayout(0, 2, 12, 10));
        c.setOpaque(false);
        for (String cur : cfg.currencies) {
            Ui.Field f = Ui.numeric("المعدود فعلًا");
            fields.put(cur, f);
            c.add(Ui.labeled(cur + " — المسجّل " + Model.fmt(bal.getOrDefault(cur, 0L), db.decimals(cur))
                    + (pend.containsKey(cur) ? " (منه معلّق " + Model.fmt(pend.get(cur), db.decimals(cur)) + ")" : ""), f));
        }
        Ui.Field note = Ui.field("ملاحظة (اختياري)");
        c.add(Ui.labeled("ملاحظة", note));
        JPanel wrap = Ui.column();
        wrap.add(Ui.para("اعدد النقد الموجود واكتبه لكل عملة، ثم «مراجعة»: يظهر العجز والزيادة، ويُقارن زيادة عملة بعجز أخرى (هل هي عملية بيع لم تُسجَّل؟). اترك العملة التي لم تعدّها فارغة.", 13, Ui.muted, 560));
        if (!pend.isEmpty()) wrap.add(Ui.para("في الصندوق نقد لحركات مكاتب بانتظار الاعتماد، وهو داخل في «المسجّل» لأنه موجود فعلًا: لا تُسجَّل له تسوية، ولا يُحسب مرة ثانية عند اعتماده.", 13, Ui.goldInk, 560));
        wrap.add(Ui.gap(8));
        wrap.add(c);
        if (!Ui.form(this, "جرد «" + cfg.name + "»", Ui.rtl(wrap), "مراجعة الجرد")) return;
        try {
            Map<String, Long> counted = new LinkedHashMap<>();
            for (Map.Entry<String, Ui.Field> e : fields.entrySet()) if (!e.getValue().val().isEmpty()) counted.put(e.getKey(), Db.minor(e.getValue().val(), db.decimals(e.getKey())));
            if (counted.isEmpty()) return;
            review(cfg, counted, note.val());
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void review(CashBox.Config cfg, Map<String, Long> counted, String note) {
        Map<String, Integer> dec = db.decimalsMap();
        Map<String, Long> bal = db.cashExpected(cfg.id);
        Map<String, BigDecimal> rates = db.usdRates();
        List<CashBox.CountLine> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : counted.entrySet()) lines.add(new CashBox.CountLine(e.getKey(), bal.getOrDefault(e.getKey(), 0L), e.getValue()));
        JPanel p = Ui.column();
        BigDecimal up = BigDecimal.ZERO, down = BigDecimal.ZERO;
        for (CashBox.CountLine l : lines) {
            int d = CashBox.dec(dec, l.currency);
            long df = l.diff();
            BigDecimal u = CashBox.usd(Math.abs(df), l.currency, rates, dec);
            if (u != null && df > 0) up = up.add(u);
            if (u != null && df < 0) down = down.add(u);
            p.add(Ui.label(l.currency + ": المسجّل " + Ui.ltr(Model.fmt(l.recorded, d)) + " • المعدود " + Ui.ltr(Model.fmt(l.counted, d)) + " • "
                    + (df == 0 ? "مطابق ✓" : (df > 0 ? "زيادة " : "عجز ") + Ui.ltr(Model.fmt(Math.abs(df), d)) + (u != null && !"USD".equals(l.currency) ? " (≈ " + Ui.ltr(u.setScale(2, RoundingMode.HALF_EVEN) + " $") + ")" : "")),
                    14, df == 0 ? Ui.green : df > 0 ? Ui.primaryInk : Ui.red, true));
        }
        BigDecimal net = CashBox.netUsd(lines, rates, dec, null);
        p.add(Ui.gap(6));
        p.add(Ui.label("مجموع الزيادة ≈ " + Ui.ltr(up.setScale(2, RoundingMode.HALF_EVEN) + " $") + " • مجموع العجز ≈ " + Ui.ltr(down.setScale(2, RoundingMode.HALF_EVEN) + " $")
                + " • الصافي: " + (net.signum() == 0 ? "متساويان" : (net.signum() > 0 ? "زيادة ≈ " : "عجز ≈ ") + Ui.ltr(net.abs().setScale(2, RoundingMode.HALF_EVEN) + " $")), 13.5f, Ui.ink, true));
        List<CashBox.Suggestion> sugg = CashBox.analyze(lines, rates, dec);
        List<JCheckBox> picks = new ArrayList<>();
        boolean internal = !cfg.strict || cfg.allowInternal;
        for (CashBox.Suggestion s : sugg) {
            p.add(Ui.gap(10));
            Ui.Card k = Ui.tinted(new Color(250, 241, 210), GOLD);
            k.add(Ui.para("زيادة " + money(s.got, s.gotCur) + " (≈ " + s.gotUsd + " $) وعجز " + money(s.sold, s.soldCur) + " (≈ " + s.soldUsd + " $): قيمتاهما متقاربتان، "
                    + "الأرجح أنك بعت " + money(s.sold, s.soldCur) + " بـ " + money(s.got, s.gotCur) + " بسعر " + Ui.ltr("1 " + s.soldCur + " = " + s.rate.toPlainString() + " " + s.gotCur)
                    + " ولم تسجّل العملية.", 13.5f, new Color(110, 82, 6), 560));
            JCheckBox cb = Ui.check("سجّلها عملية بيع (بدل تسويتي عجز وزيادة)", false);
            cb.setEnabled(internal);
            picks.add(cb);
            k.add(cb);
            p.add(k);
        }
        if (sugg.isEmpty() && up.signum() > 0 && down.signum() > 0)
            p.add(Ui.para("في الجرد زيادة وعجز بعملات مختلفة لكن قيمتيهما غير متقاربتين، فلا تبدو عملية بيع واحدة. راجع الحركات قبل الحفظ.", 13, Ui.muted, 560));
        p.add(Ui.gap(8));
        p.add(Ui.para("يُحفظ الجرد في «سجل الجرد» كما هو، وما بقي من فروق يُسجَّل «تسوية جرد» فيطابق الصندوق المعدود.", 12.5f, Ui.muted, 560));
        if (!Ui.form(this, "مراجعة الجرد", Ui.rtl(p), "حفظ الجرد")) return;
        List<CashBox.Suggestion> chosen = new ArrayList<>();
        for (int i = 0; i < sugg.size(); i++) if (picks.get(i).isSelected()) chosen.add(sugg.get(i));
        try {
            CashBox.Count r = db.cashCount(cfg.id, counted, note, chosen);
            app.changed();
            Ui.toast(r.adjusted == 0 && r.sales.isEmpty() ? "الصندوق مطابق للمعدود ✓" : "حُفظ الجرد، والصندوق الآن مطابق للمعدود");
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void countLog() {
        CashBox.Config cfg = db.cashConfig();
        Map<String, Integer> dec = db.decimalsMap();
        DefaultTableModel mdl = new DefaultTableModel(new Object[]{"التاريخ", "بواسطة", "العجز والزيادة", "الصافي بالدولار", "سُجّل بيعًا", "ملاحظة"}, 0) {
            @Override public boolean isCellEditable(int a, int b) { return false; }
        };
        for (CashBox.Count k : db.cashCounts(cfg.id)) {
            List<String> parts = new ArrayList<>();
            for (CashBox.CountLine l : k.lines) {
                long df = l.diff();
                parts.add(df == 0 ? l.currency + " مطابق" : (df > 0 ? "زيادة " : "عجز ") + Model.fmt(Math.abs(df), CashBox.dec(dec, l.currency)) + " " + l.currency);
            }
            String net = k.netUsd.isEmpty() ? "" : new BigDecimal(k.netUsd).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
            mdl.addRow(new Object[]{Model.date(k.createdAt), k.userName, String.join(" • ", parts), net, k.sales.replace("\u2066", "").replace("\u2069", ""), k.note});
        }
        JTable t = Ui.table(mdl);
        Ui.widths(t, 130, 110, 320, 100, 260, 160);
        t.applyComponentOrientation(Ui.RTL);
        JScrollPane sp = Ui.tableBox(t);
        sp.setPreferredSize(new Dimension(1000, 420));
        Ui.form(this, "سجل الجرد — " + cfg.name, sp, "إغلاق");
    }

    // ===================================================================================
    // الإعداد: العملات كلها بأسعارها، والربط، والصناديق
    // ===================================================================================

    String rateText(String code, Map<String, Db.Rate> rates) {
        if ("USD".equals(code)) return "العملة الأساس";
        Db.Rate r = rates.get(code);
        if (r == null || r.usd == null) return "بلا سعر صرف";
        if (r.usd.compareTo(BigDecimal.ONE) >= 0) return "1 " + code + " = " + r.usd.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + " USD — " + r.source;
        return "1 USD = " + BigDecimal.ONE.divide(r.usd, 2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + " " + code + " — " + r.source;
    }

    /** لوحة العملات: كل عملات التطبيق مع سعرها، وحقل الرصيد الافتتاحي للعملات الجديدة. */
    JPanel currencyPanel(Set<String> selected, Map<String, Long> bal, Map<String, JCheckBox> checks, Map<String, Ui.Field> opens) {
        JPanel g = new JPanel(new GridLayout(0, 3, 12, 6));
        g.setOpaque(false);
        Map<String, Db.Rate> rates = db.rates();
        for (Model.Currency c : db.currencies(true)) {
            boolean had = selected.contains(c.code);
            JCheckBox cb = Ui.check(c.code + " — " + c.name, had);
            checks.put(c.code, cb);
            g.add(cb);
            if (had) g.add(Ui.label("الرصيد " + Model.fmt(bal.getOrDefault(c.code, 0L), c.decimals), 13, Ui.muted, false));
            else {
                Ui.Field op = Ui.numeric("الموجود الآن (رصيد افتتاحي)");
                opens.put(c.code, op);
                g.add(op);
            }
            g.add(Ui.label(rateText(c.code, rates), 12, Ui.muted, false));
        }
        return g;
    }

    Map<String, Long> openings(Map<String, JCheckBox> checks, Map<String, Ui.Field> opens, List<String> codes) {
        Map<String, Long> openings = new LinkedHashMap<>();
        for (Map.Entry<String, JCheckBox> e : checks.entrySet()) {
            if (!e.getValue().isSelected()) continue;
            codes.add(e.getKey());
            Ui.Field op = opens.get(e.getKey());
            if (op != null && !op.val().isEmpty()) openings.put(e.getKey(), Db.minor(op.val(), db.decimals(e.getKey())));
        }
        return openings;
    }

    void setup(boolean first) {
        CashBox.Config cfg = db.cashConfig();
        Ui.Field name = Ui.field(CashBox.DEFAULT_NAME);
        name.setText(cfg.on ? cfg.name : "");
        Map<String, JCheckBox> checks = new LinkedHashMap<>();
        Map<String, Ui.Field> opens = new LinkedHashMap<>();
        JPanel g = currencyPanel(new HashSet<>(cfg.on ? cfg.currencies : new ArrayList<>()), cfg.on ? db.cashBalances(cfg.id) : new HashMap<>(), checks, opens);
        JCheckBox strict = Ui.check("ربط هذا الصندوق بالمكاتب: لا يدخل ولا يخرج إلا لحساب مكتب أو عميل", cfg.strict);
        JCheckBox internal = Ui.check("مع الربط: السماح بالمصاريف والبيع والشراء داخل الصندوق", cfg.allowInternal);
        JPanel c = Ui.column();
        c.add(Ui.labeled("اسم الصندوق", name));
        c.add(Ui.gap(8));
        c.add(Ui.row(Ui.label("العملات والرصيد الموجود الآن", 13, Ui.muted, true), Box.createHorizontalGlue(),
                Ui.ghost("تحديث الأسعار من الإنترنت", e -> Ui.busy(this, "جارٍ تحديث الأسعار…", p -> ExchangeRates.refresh(db), n -> Ui.toast("حُدّثت أسعار " + n + " عملة — افتح الإعدادات من جديد"))),
                Ui.ghost("+ عملة أخرى", e -> addCurrency())));
        c.add(g);
        c.add(Ui.gap(8));
        c.add(strict);
        c.add(internal);
        c.add(Ui.para("لكل صندوق ربطه: مثلًا صندوق المحل مربوط بالمكاتب، وخزنة البيت حرة. الجرد مسموح دائمًا.", 12.5f, Ui.muted, 700));
        JScrollPane sp = Ui.scroll(Ui.rtl(c));
        sp.setPreferredSize(new Dimension(820, 560));
        if (!Ui.form(this, cfg.on ? "إعدادات «" + cfg.name + "»" : "إعداد صندوق المكتب", sp, cfg.on ? "حفظ" : "إنشاء الصندوق")) return;
        try {
            List<String> codes = new ArrayList<>();
            Map<String, Long> op = openings(checks, opens, codes);
            if (codes.isEmpty()) throw new IllegalArgumentException("اختر عملة واحدة على الأقل");
            db.cashSetup(cfg.id, name.val(), codes, op, strict.isSelected(), internal.isSelected());
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void addCurrency() {
        if (!can(Auth.Perm.CURRENCIES)) return;
        Set<String> have = new TreeSet<>();
        for (Model.Currency c : db.currencies(true)) have.add(c.code);
        Set<String> codes = new TreeSet<>(db.rates().keySet());
        codes.addAll(WorldCurrencies.ALL.keySet());
        codes.removeAll(have);
        List<String> list = new ArrayList<>();
        for (String c : codes) if (c.matches("[A-Z]{3}")) list.add(c);
        if (list.isEmpty()) {
            Ui.info(this, "إضافة عملة", "كل العملات المعروفة مضافة. حدّث الأسعار من الإنترنت لتظهر عملات أكثر.");
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i) + " — " + WorldCurrencies.name(list.get(i));
        JComboBox<String> pick = Ui.combo(labels);
        if (!Ui.form(this, "إضافة عملة للتطبيق", Ui.rtl(Ui.labeled("العملة", pick)), "إضافة")) return;
        String code = list.get(pick.getSelectedIndex());
        try {
            db.saveCurrency(code, WorldCurrencies.name(code), WorldCurrencies.decimals(code), true);
            Ui.toast("أُضيفت " + code + " — افتح الإعدادات من جديد لتظهر");
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void boxForm() {
        Ui.Field name = Ui.field("مثل: خزنة البيت");
        Map<String, JCheckBox> checks = new LinkedHashMap<>();
        Map<String, Ui.Field> opens = new LinkedHashMap<>();
        JPanel g = currencyPanel(new HashSet<>(), new HashMap<>(), checks, opens);
        JCheckBox strict = Ui.check("ربط هذا الصندوق بالمكاتب", false);
        JCheckBox internal = Ui.check("مع الربط: السماح بالمصاريف والبيع والشراء", true);
        JPanel c = Ui.column();
        c.add(Ui.para("صندوق آخر لك بنقده المستقل (مثل: صندوق الفرع، خزنة البيت). تنقل النقد بينه وبين الصناديق الأخرى بـ«تحويل».", 13, Ui.muted, 700));
        c.add(Ui.labeled("اسم الصندوق", name));
        c.add(Ui.gap(8));
        c.add(g);
        c.add(strict);
        c.add(internal);
        JScrollPane sp = Ui.scroll(Ui.rtl(c));
        sp.setPreferredSize(new Dimension(820, 520));
        if (!Ui.form(this, "صندوق جديد", sp, "إنشاء")) return;
        try {
            List<String> codes = new ArrayList<>();
            Map<String, Long> op = openings(checks, opens, codes);
            if (codes.isEmpty()) throw new IllegalArgumentException("اختر عملة واحدة على الأقل");
            long id = db.addCashBox(name.val(), codes, op, strict.isSelected(), internal.isSelected());
            db.setCurrentBox(id);
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void boxes() {
        List<CashBox.Config> all = new ArrayList<>();
        for (CashBox.Config b : db.cashBoxes()) if (b.on) all.add(b);
        String[] labels = new String[all.size()];
        for (int i = 0; i < labels.length; i++) {
            CashBox.Config b = all.get(i);
            labels[i] = b.name + " — " + String.join("، ", b.currencies) + (b.strict ? " — مربوط" : " — حر") + (b.active ? "" : " — معطّل");
        }
        JComboBox<String> pick = Ui.combo(labels);
        int k = Ui.choose(this, "إدارة الصناديق", "اختر الصندوق ثم الإجراء. التعطيل يُبقي تاريخه في التقارير (رصيده صفر). الحذف نهائي ويجوز فقط لصندوق رصيده صفر "
                + "ولا ترتبط حركاته بمكاتب أو عملاء أو بصناديق أخرى.", new String[]{"إغلاق", "فتحه", "تعطيل / تفعيل", "حذف نهائيًا"});
        if (k <= 0) return;
        JPanel p = Ui.column();
        p.add(Ui.labeled("الصندوق", pick));
        if (!Ui.form(this, "إدارة الصناديق", Ui.rtl(p), "متابعة")) return;
        CashBox.Config b = all.get(pick.getSelectedIndex());
        try {
            if (k == 1) {
                if (!b.active) throw new IllegalStateException("الصندوق معطّل؛ فعّله أولًا");
                db.setCurrentBox(b.id);
            } else if (k == 2) {
                db.setCashBoxActive(b.id, !b.active);
                Ui.toast(b.active ? "عُطّل «" + b.name + "»" : "فُعّل «" + b.name + "»");
            } else {
                String why = db.cashBoxDeleteBlock(b.id);
                if (why != null) {
                    Ui.info(this, "لا يمكن حذف «" + b.name + "»", why);
                    return;
                }
                if (!Ui.confirm(this, "حذف «" + b.name + "» نهائيًا", "يُحذف الصندوق بكل حركاته وسجل جرده، ولا يمكن التراجع إلا من نسخة احتياطية.", "حذف نهائيًا")) return;
                db.deleteCashBox(b.id);
            }
            app.changed();
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    Report makeReport() {
        long[] r = ReportBuilder.period(period.getSelectedIndex(), Clock.now());
        CashBox.ReportSpec sp = new CashBox.ReportSpec();
        sp.from = r[0];
        sp.to = r[1];
        CashBox.Config cfg = db.cashConfig();
        Report rep = CashBox.report(db.cashMoves(cfg.id), sp, db.decimalsMap(), db.officeNames(), cfg.name, db.setting("office_name", ""));
        Entitlements e = License.current;
        rep.stamp(e.watermark(), e.lid);
        return rep;
    }

    void report() {
        Report r = makeReport();
        JPanel p = ReportPanel.render(r, 900);
        JScrollPane sp = Ui.scroll(p);
        sp.setPreferredSize(new Dimension(960, 600));
        if (Ui.form(this, r.title + " — " + ReportBuilder.PERIODS[period.getSelectedIndex()], sp, "تصدير / طباعة…")) Export.dialog(this, app, r.title, this::makeReport);
    }
}
