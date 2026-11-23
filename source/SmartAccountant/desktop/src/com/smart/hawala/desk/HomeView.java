package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** الرئيسية: الترحيب، التنبيهات (اعتماد، نسخ، فحص)، إجمالي ما لك وما عليك لكل عملة، حركات اليوم، آخر الحركات، المكاتب المثبتة. */
final class HomeView extends View {
    HomeView(App app) {
        super(app, "الرئيسية");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        String welcome = db.setting("welcome", "").trim();
        String office = db.setting("office_name", "").trim();
        titleLabel.setText(office.isEmpty() ? "أهلًا بك" : office);
        subtitle.setText((welcome.isEmpty() ? "" : welcome + " — ") + "اليوم " + Model.day(Clock.now()));
        if (can(Auth.Perm.TX_ADD)) action(Ui.primary("+ تسجيل حركة", e -> app.go("tx")));
        action(Ui.ghost("كشف حساب", e -> app.go("statement")));
        action(Ui.ghost("منشئ التقارير", e -> app.go("builder")));
        action(Ui.ghost(app.hidden() ? "إظهار المبالغ" : "إخفاء المبالغ", e -> {
            app.toggleHidden();
            refresh();
        }));

        notices(w);
        action(Ui.ghost("تخصيص الرئيسية…", e -> customize()));

        // 10.16: أقسام الرئيسية كالهاتف، يختارها المستخدم ويرتبها («تخصيص الرئيسية»)
        Map<String, Integer> dec = db.decimalsMap();
        Set<String> hidden = new HashSet<>(Arrays.asList(db.setting("desk_home_hidden", DEFAULT_HIDDEN).split(",")));
        for (String k : order()) {
            if (hidden.contains(k)) continue;
            try {
                switch (k) {
                    case "totals": totals(dec); break;
                    case "usd": usd(dec); break;
                    case "today": today(dec); break;
                    case "limits": limits(w); break;
                    case "recon": reconPending(); break;
                    case "offices": recentOffices(); break;
                    case "shortcuts": shortcuts(); break;
                    case "paste": paste(w); break;
                    case "rates": rates(); break;
                    case "recent": recentTx(dec); break;
                    case "pinned": pinned(dec); break;
                    default: break;
                }
            } catch (Exception ex) {
                add(Ui.label("تعذر عرض قسم: " + ex.getMessage(), 13, Ui.red, false));
            }
        }
        done();
    }

    static final String[][] WIDGETS = {{"totals", "أرصدة كل المكاتب (لكل عملة)"}, {"usd", "التقويم التقريبي بالدولار"}, {"today", "حركات اليوم"},
            {"limits", "تجاوز حدود المكاتب"}, {"recon", "المطابقات بانتظار التأكيد"}, {"offices", "آخر المكاتب"}, {"shortcuts", "اختصارات"},
            {"paste", "اللصق من واتساب"}, {"rates", "أسعار الصرف مقابل الدولار"}, {"recent", "آخر الحركات"}, {"pinned", "المكاتب المثبتة"}};
    static final String DEFAULT_HIDDEN = "rates";

    List<String> order() {
        List<String> out = new ArrayList<>();
        for (String k : db.setting("desk_home_order", "").split(",")) if (title(k) != null && !out.contains(k)) out.add(k);
        for (String[] x : WIDGETS) if (!out.contains(x[0])) out.add(x[0]);
        return out;
    }

    static String title(String k) {
        for (String[] x : WIDGETS) if (x[0].equals(k)) return x[1];
        return null;
    }

    /** اختيار الأقسام الظاهرة وترتيبها (إعداد هذا الجهاز). */
    void customize() {
        final List<String> ord = order();
        final Set<String> hid = new HashSet<>(Arrays.asList(db.setting("desk_home_hidden", DEFAULT_HIDDEN).split(",")));
        final DefaultListModel<String> m = new DefaultListModel<>();
        final JList<String> list = new JList<>(m);
        final Runnable fill = () -> {
            int sel = list.getSelectedIndex();
            m.clear();
            for (String k : ord) m.addElement((hid.contains(k) ? "(مخفي) " : "") + title(k));
            list.setSelectedIndex(Math.max(0, Math.min(sel, ord.size() - 1)));
        };
        fill.run();
        list.setFont(Ui.font(14, false));
        list.setFixedCellHeight(30);
        list.applyComponentOrientation(Ui.RTL);
        Runnable toggle = () -> {
            int i = list.getSelectedIndex();
            if (i < 0) return;
            if (!hid.remove(ord.get(i))) hid.add(ord.get(i));
            fill.run();
        };
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) toggle.run();
            }
        });
        list.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) toggle.run();
            }
        });
        JPanel c = Ui.column();
        c.add(Ui.para("اختر ما يظهر في الصفحة الرئيسية ورتّبه. (مسافة أو نقرتان: إظهار/إخفاء)", 13.5f, Ui.muted, 420));
        c.add(Ui.gap(6));
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(420, 340));
        c.add(sp);
        c.add(Ui.gap(6));
        c.add(Ui.flow(Ui.ghost("إظهار / إخفاء", e -> toggle.run()), Ui.ghost("↑ أعلى", e -> {
            int i = list.getSelectedIndex();
            if (i > 0) {
                Collections.swap(ord, i, i - 1);
                list.setSelectedIndex(i - 1);
                fill.run();
            }
        }), Ui.ghost("↓ أسفل", e -> {
            int i = list.getSelectedIndex();
            if (i >= 0 && i < ord.size() - 1) {
                Collections.swap(ord, i, i + 1);
                list.setSelectedIndex(i + 1);
                fill.run();
            }
        }), Ui.ghost("الافتراضي", e -> {
            ord.clear();
            for (String[] x : WIDGETS) ord.add(x[0]);
            hid.clear();
            hid.addAll(Arrays.asList(DEFAULT_HIDDEN.split(",")));
            fill.run();
        })));
        if (!Ui.form(this, "تخصيص الصفحة الرئيسية", Ui.rtl(c), "حفظ")) return;
        db.setSettingQuiet("desk_home_order", String.join(",", ord));
        db.setSettingQuiet("desk_home_hidden", String.join(",", hid));
        refresh();
    }

    String mny(String s) { return app.hidden() ? "••••" : s; }

    void totals(Map<String, Integer> dec) {
        // إجمالي ما لك وما عليك
        Map<String, long[]> tot = new TreeMap<>();
        for (Map<String, Long> m : db.officeBalances().values())
            for (Map.Entry<String, Long> e : m.entrySet()) {
                long[] x = tot.computeIfAbsent(e.getKey(), k -> new long[2]);
                if (e.getValue() > 0) x[0] += e.getValue();
                else x[1] -= e.getValue();
            }
        JPanel cards = new JPanel(new GridLayout(0, 3, 12, 12));
        cards.setOpaque(false);
        cards.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (Map.Entry<String, long[]> e : tot.entrySet()) {
            if (e.getValue()[0] == 0 && e.getValue()[1] == 0) continue;
            int d = dec.getOrDefault(e.getKey(), 2);
            Ui.Card c = Ui.card();
            c.add(Ui.label(e.getKey(), 15, Ui.goldInk, true));
            c.add(Ui.gap(4));
            c.add(Ui.label("لك: " + (app.hidden() ? "••••" : Model.fmt(e.getValue()[0], d)), 15, Ui.green, true));
            c.add(Ui.label("عليك: " + (app.hidden() ? "••••" : Model.fmt(e.getValue()[1], d)), 15, Ui.red, true));
            long net = e.getValue()[0] - e.getValue()[1];
            c.add(Ui.label("الصافي: " + (app.hidden() ? "••••" : Model.bal(net, d)), 13, Ui.muted, false));
            cards.add(c);
        }
        if (cards.getComponentCount() > 0) {
            add(Ui.heading("أرصدة كل المكاتب"));
            space(8);
            add(cards);
            space(16);
        }

    }

    void usd(Map<String, Integer> dec) {
        if (!can(Auth.Perm.VIEW)) return;
        Ledger.Valuation v = Ledger.value(Ledger.totals(db.officeBalances()), db.usdRates(), dec);
        if (v.receivableUsd.signum() == 0 && v.payableUsd.signum() == 0) return;
        Ui.Card c = Ui.card();
        c.add(Ui.row(Ui.heading("التقويم التقريبي بالدولار"), Box.createHorizontalGlue(), Ui.ghost("أسعار الصرف", e -> app.go("settings"))));
        JPanel g = new JPanel(new GridLayout(1, 3, 12, 0));
        g.setOpaque(false);
        g.setAlignmentX(Component.RIGHT_ALIGNMENT);
        g.add(Ui.labeled("لنا", Ui.label(mny("$" + Db.fmtUsd(v.receivableUsd)), 18, Ui.green, true)));
        g.add(Ui.labeled("علينا", Ui.label(mny("$" + Db.fmtUsd(v.payableUsd)), 18, Ui.red, true)));
        g.add(Ui.labeled("الصافي", Ui.label(mny("$" + Db.fmtUsd(v.netUsd())), 18, v.netUsd().signum() >= 0 ? Ui.green : Ui.red, true)));
        c.add(g);
        if (!v.missingRates.isEmpty()) c.add(Ui.label("بدون سعر صرف (غير محسوبة): " + v.missingRates, 12.5f, Ui.warnInk, false));
        c.add(Ui.label("تقريبي للمتابعة فقط؛ الأرصدة الحقيقية بعملاتها.", 12, Ui.muted, false));
        add(c);
        space(16);
    }

    void today(Map<String, Integer> dec) {
        // اليوم
        long today = Ui.startOfDay(Clock.now());
        Map<String, long[]> st = db.dailyStats(today, Ui.addDays(today, 1));
        Ui.Card day = Ui.card();
        day.add(Ui.heading("حركات اليوم"));
        if (st.isEmpty()) day.add(Ui.label("لا حركات اليوم بعد.", 14, Ui.muted, false));
        for (Map.Entry<String, long[]> e : st.entrySet()) {
            int d = dec.getOrDefault(e.getKey(), 2);
            long[] v = e.getValue();
            day.add(Ui.label("بـ" + e.getKey() + ":  " + v[4] + " حركة · لنا " + Model.fmt(v[0], d) + " · علينا " + Model.fmt(v[1], d)
                    + (v[2] + v[3] > 0 ? " · أجور لنا " + Model.fmt(v[2], d) + " وعلينا " + Model.fmt(v[3], d) : ""), 14, Ui.ink, false));
        }
        add(day);
        space(16);

    }

    /** تنبيهات حدود المكاتب (لكل عملة، ومقوّمة بالدولار) كما في الهاتف. */
    static List<String> limitAlerts(Db db, Model.Office o) {
        List<String> out = new ArrayList<>();
        Map<String, Long> b = db.balances(o.id);
        Map<String, Db.Limit> limits = db.officeLimits(o.id);
        for (Map.Entry<String, Long> e : b.entrySet()) {
            Db.Limit l = limits.get(e.getKey());
            if (l == null) continue;
            Ledger.LimitBreach br = Ledger.check(e.getKey(), e.getValue(), l.receivable, l.payable);
            if (br == null) continue;
            int d = db.decimals(e.getKey());
            out.add(o.name + " — " + e.getKey() + ": " + (br.receivable ? "لنا " : "علينا ") + Db.fmt(br.amount, d) + " (الحد " + Db.fmt(br.limit, d) + ")");
        }
        if (o.usdLimit != null || o.usdOweLimit != null) {
            Ledger.Valuation v = Ledger.valueOffice(b, db.usdRates(), db.decimalsMap());
            long us = v.receivableUsd.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
            long them = v.payableUsd.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
            if (o.usdLimit != null && o.usdLimit > 0 && us > o.usdLimit) out.add(o.name + " — مجموع «لنا» مقوّمًا ≈ " + Db.fmt(us, 2) + " USD (الحد " + Db.fmt(o.usdLimit, 2) + ")");
            if (o.usdOweLimit != null && o.usdOweLimit > 0 && them > o.usdOweLimit)
                out.add(o.name + " — مجموع «علينا» مقوّمًا ≈ " + Db.fmt(them, 2) + " USD (الحد " + Db.fmt(o.usdOweLimit, 2) + ")");
        }
        return out;
    }

    void limits(int w) {
        if (!can(Auth.Perm.VIEW)) return;
        List<String> all = new ArrayList<>();
        for (Model.Office o : db.offices()) all.addAll(limitAlerts(db, o));
        if (all.isEmpty()) return;
        Ui.Card c = Ui.tinted(Ui.debitBg, null);
        c.add(Ui.row(Ui.label("تجاوز حدود المكاتب (" + all.size() + ")", 15, Ui.red, true), Box.createHorizontalGlue(), Ui.ghost("الأرصدة", e -> app.go("balances"))));
        for (int i = 0; i < all.size() && i < 8; i++) c.add(Ui.label("• " + mny(all.get(i)), 13.5f, Ui.red, false));
        if (all.size() > 8) c.add(Ui.label("و" + (all.size() - 8) + " أخرى…", 13, Ui.red, false));
        add(c);
        space(16);
    }

    void reconPending() {
        if (!can(Auth.Perm.VIEW)) return;
        List<Object[]> pend = new ArrayList<>();
        for (Map.Entry<Long, Model.Recon> e : db.lastReconsByOffice().entrySet()) {
            if (!Model.RECON_SENT.equals(e.getValue().status)) continue;
            Model.Office o = db.office(e.getKey());
            if (o != null) pend.add(new Object[]{o, e.getValue()});
        }
        if (pend.isEmpty()) return;
        Ui.Card c = Ui.card();
        c.add(Ui.heading("المطابقات بانتظار التأكيد (" + pend.size() + ")"));
        JPanel f = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        f.setOpaque(false);
        f.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (Object[] x : pend) {
            final Model.Office o = (Model.Office) x[0];
            Ui.Btn b = Ui.ghost(o.name + " — " + Model.day(((Model.Recon) x[1]).createdAt), e -> app.go("office", o.id));
            f.add(b);
        }
        f.applyComponentOrientation(Ui.RTL);
        c.add(f);
        add(c);
        space(16);
    }

    void recentOffices() {
        if (!can(Auth.Perm.VIEW)) return;
        Map<Long, Long> opened = OfficeOrder.parseOpened(db.setting("office_opened", ""));
        List<Long> ids = new ArrayList<>(opened.keySet());
        ids.sort((x, y) -> Long.compare(opened.get(y), opened.get(x)));
        List<Model.Office> list = new ArrayList<>();
        for (long id : ids) {
            Model.Office o = db.office(id);
            if (o != null && o.active && list.size() < 8) list.add(o);
        }
        if (list.isEmpty()) return;
        add(Ui.row(Ui.heading("آخر المكاتب"), Box.createHorizontalGlue(), Ui.ghost("كل المكاتب", e -> app.go("offices"))));
        JPanel f = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        f.setOpaque(false);
        f.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (final Model.Office o : list) f.add(Ui.ghost(o.name, e -> app.go("office", o.id)));
        f.applyComponentOrientation(Ui.RTL);
        add(f);
        space(12);
    }

    void shortcuts() {
        String[][] sc = {{"tx", "تسجيل حركة", "Ctrl+N"}, {"fx", "قص عملات", "Ctrl+Shift+N"}, {"xfer", "بين مكتبين", "Ctrl+Shift+T"}, {"log", "سجل الحركات", "Ctrl+F"},
                {"statement", "كشف حساب", ""}, {"balances", "الأرصدة", ""}, {"daily", "اليومي", ""}, {"cash", "الصندوق", ""}, {"mine", "مكاتبي", ""}, {"backup", "النسخ الاحتياطي", "Ctrl+B"}};
        JPanel g = new JPanel(new GridLayout(0, 5, 10, 10));
        g.setOpaque(false);
        g.setAlignmentX(Component.RIGHT_ALIGNMENT);
        String mod = Ui.MAC ? "Cmd" : "Ctrl";
        for (final String[] x : sc) {
            if (!app.allowed(x[0]) || ("cash".equals(x[0]) || "mine".equals(x[0])) && !app.navAllowed(x[0])) continue;
            Ui.Btn b = Ui.ghost(x[1], e -> app.go(x[0]));
            if (!x[2].isEmpty()) b.setToolTipText(x[2].replace("Ctrl", mod));
            g.add(b);
        }
        if (g.getComponentCount() == 0) return;
        add(Ui.row(Ui.heading("اختصارات"), Box.createHorizontalGlue(), Ui.ghost("انتقال سريع (" + mod + "+K)", e -> QuickJump.open(app))));
        space(8);
        add(g);
        space(16);
    }

    void paste(int w) {
        if (!can(Auth.Perm.TX_ADD)) return;
        Ui.Card c = Ui.card();
        c.add(Ui.row(Ui.heading("اللصق من واتساب"), Box.createHorizontalGlue(), Ui.primary("لصق رسالة وتسجيلها", e -> {
            app.go("tx");
            View v = app.views.get("tx");
            if (v instanceof TxView) ((TxView) v).pasteClipboard();
        })));
        String clip = clipText(db);
        if (clip != null) {
            c.add(Ui.label("في الحافظة الآن:", 13, Ui.goldInk, true));
            c.add(Ui.para("«" + Ui.cut(clip.replace('\n', ' '), 220) + "»", 13.5f, Ui.ink, w));
        } else c.add(Ui.para("انسخ رسالة الحوالة من واتساب ثم اضغط الزر: تُفتح شاشة التسجيل وتُستخرج منها البيانات لتراجعها قبل الحفظ.", 13.5f, Ui.muted, w));
        add(c);
        space(16);
    }

    /** نص الحافظة إن كان يشبه رسالة حوالة (فيه رقم) ومفتاح العرض مفعّل؛ وإلا null. لا يُحفظ ولا يُرسل. */
    static String clipText(Db db) {
        if ("0".equals(db.setting("desk_clip_peek", "1"))) return null;
        try {
            Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            String s = o == null ? "" : o.toString().trim();
            if (s.length() < 6 || s.length() > 4000 || !s.matches("(?s).*[0-9٠-٩].*")) return null;
            return s;
        } catch (Throwable e) {
            return null;
        }
    }

    void rates() {
        if (!can(Auth.Perm.VIEW)) return;
        Map<String, Db.Rate> rates = db.rates();
        Ui.Card c = Ui.card();
        c.add(Ui.heading("أسعار الصرف مقابل الدولار"));
        int n = 0;
        for (Map.Entry<String, Db.Rate> e : rates.entrySet()) {
            if ("USD".equals(e.getKey())) continue;
            Db.Rate r = e.getValue();
            if (r.usd == null || r.usd.signum() <= 0) continue;
            java.math.BigDecimal u = java.math.BigDecimal.ONE.divide(r.usd, 6, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros();
            c.add(Ui.label("1 USD = " + (u.scale() < 0 ? u.setScale(0).toPlainString() : u.toPlainString()) + " " + e.getKey() + "   —   " + r.source
                    + (r.at > 0 ? " • " + Model.date(r.at) : ""), 13.5f, Ui.ink, false));
            n++;
        }
        if (n == 0) c.add(Ui.label("لا توجد أسعار بعد. حدّثها من «الإعدادات ← مصدر أسعار الصرف».", 13.5f, Ui.muted, false));
        add(c);
        space(16);
    }

    void recentTx(Map<String, Integer> dec) {
        // آخر الحركات
        add(Ui.heading("آخر الحركات"));
        space(8);
        Map<Long, String> names = db.officeNames();
        DefaultTableModel m = new DefaultTableModel(new Object[]{"التاريخ", "المكتب", "لنا", "علينا", "العملة", "المستفيد / البيان"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        List<Model.Tx> recent = db.recentTx(12);
        for (Model.Tx t : recent) {
            int d = dec.getOrDefault(t.currency, 2);
            boolean us = Model.TYPE_DEBIT.equals(t.type);
            m.addRow(new Object[]{Model.date(t.createdAt), names.getOrDefault(t.officeId, ""), us ? new Ui.Money(t.amount, d, false) : "",
                    us ? "" : new Ui.Money(t.amount, d, false), t.currency, Ui.cut(Model.nz(t.person).isEmpty() ? t.description : t.person, 60)});
        }
        JTable table = Ui.table(m);
        Ui.widths(table, 130, 160, 110, 110, 60, 360);
        JScrollPane sp = Ui.tableBox(table);
        sp.setPreferredSize(new Dimension(800, Math.min(12, Math.max(3, recent.size())) * 32 + 36));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp.getPreferredSize().height));
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    int r = table.convertRowIndexToModel(table.getSelectedRow());
                    LogView.details(HomeView.this, app, recent.get(r).id);
                }
            }
        });
        add(sp);
        space(16);

    }

    void pinned(Map<String, Integer> dec) {
        // المكاتب المثبتة
        List<Model.OfficeTotals> pinned = new ArrayList<>();
        for (Model.OfficeTotals o : db.officeTotals(null)) if (o.pinned && o.active) pinned.add(o);
        if (!pinned.isEmpty()) {
            add(Ui.heading("المكاتب المثبتة"));
            space(8);
            JPanel g = new JPanel(new GridLayout(0, 3, 12, 12));
            g.setOpaque(false);
            g.setAlignmentX(Component.RIGHT_ALIGNMENT);
            for (Model.OfficeTotals o : pinned) g.add(OfficesView.officeCard(app, o, dec));
            add(g);
        }
    }

    void notices(int w) {
        List<Component> n = new ArrayList<>();
        // 10.17: رسائل المدير غير المقروءة
        for (final Db.AdminMsg am : db.unreadAdminMsgs()) n.add(notice(Ui.warnBg, Ui.warnInk, "رسالة من المدير" + (am.from.isEmpty() ? "" : " (" + db.shown(am.from) + ")")
                + " — " + Model.date(am.at) + ": " + Ui.cut(am.body.replace('\n', ' '), 400), "قرأتها", () -> {
            db.markAdminMsgRead(am.uuid);
            refresh();
        }));
        // 10.16: تعارضات المزامنة وتعثرها تظهر هنا (كانت لا تُرى إلا داخل شاشة المزامنة)
        for (String[] al : SyncEngine.homeAlerts(db)) n.add("1".equals(al[1]) ? notice(Ui.debitBg, Ui.red, Ui.cut(al[0], 220), "المزامنة", () -> app.go("sync"))
                : notice(Ui.warnBg, Ui.warnInk, Ui.cut(al[0], 220), "المزامنة", () -> app.go("sync")));
        if (SyncEngine.enabled(db) && (can(Auth.Perm.TX_CANCEL) || can(Auth.Perm.APPROVE))) {
            int dups = SyncEngine.duplicates(db).size();
            if (dups > 0) n.add(notice(Ui.debitBg, Ui.red, dups + " زوج حركات قد يكون مكررًا (سُجّل من جهازين)", "مراجعة", () -> app.go("sync", "dups")));
        }
        long pend = can(Auth.Perm.APPROVE) ? db.pendingCount() : 0;
        if (pend > 0) n.add(notice(Ui.warnBg, Ui.warnInk, pend + " حركة بانتظار اعتمادك", "مراجعة", () -> app.go("log", "pending")));
        String hs = db.setting("health_status", "");
        if (!hs.isEmpty() && !"ok".equals(hs)) n.add(notice(Ui.debitBg, Ui.red, "فحص البيانات وجد ملاحظات: " + Ui.cut(hs, 120), "الاعتمادية", () -> app.go("health")));
        String abErr = db.setting("auto_backup_error", "");
        if (!abErr.isEmpty()) n.add(notice(Ui.debitBg, Ui.red, "تعذّر النسخ التلقائي: " + Ui.cut(abErr.replace('\n', ' '), 140), "النسخ الاحتياطي", () -> app.go("backup")));
        long last = db.settingLong("last_backup", 0);
        long days = last == 0 ? -1 : (Clock.now() - last) / 86400000L;
        if (db.txCount() > 0 && (last == 0 || days >= 3) && can(Auth.Perm.BACKUP))
            n.add(notice(Ui.warnBg, Ui.warnInk, last == 0 ? "لم تُنشأ نسخة احتياطية على هذا الجهاز بعد." : "آخر نسخة احتياطية قبل " + days + " يوم.", "نسخة الآن", () -> {
                app.go("backup");
                View v = app.views.get("backup");
                if (v instanceof BackupView) ((BackupView) v).create();
            }));
        if (db.usersEnabled() && !db.hasRecoveryCode() && can(Auth.Perm.USERS))
            n.add(notice(Ui.debitBg, Ui.red, "لا يوجد رمز استرداد للمدير.", "المستخدمون", () -> app.go("users")));
        Entitlements e = License.current;
        if (!e.pro) {
            QuotaState q = db.quotaNow();
            long left = QuotaState.remaining(q.offices, e.maxOffices());
            if (left <= 2) n.add(notice(Ui.goldSoft, Ui.goldInk, "النسخة المجانية: بقي " + left + " من " + e.maxOffices() + " مكاتب. البيانات كلها تبقى متاحة.", "الترخيص", () -> app.go("license")));
        }
        if (e.note != null && !e.note.isEmpty()) n.add(notice(Ui.warnBg, Ui.warnInk, e.note, "الترخيص", () -> app.go("license")));
        for (Component c : n) {
            add((JComponent) c);
            space(8);
        }
        if (!n.isEmpty()) space(8);
    }

    Ui.Card notice(Color bg, Color ink, String text, String btn, Runnable r) {
        Ui.Card c = Ui.tinted(bg, null);
        c.setLayout(new BorderLayout(10, 0));
        c.setBorder(new javax.swing.border.EmptyBorder(10, 16, 10, 12));
        JLabel l = Ui.label(text, 14, ink, true);
        c.add(l, BorderLayout.CENTER);
        Ui.Btn b = Ui.ghost(btn, e -> r.run());
        c.add(b, BorderLayout.LINE_END);
        return c;
    }
}
