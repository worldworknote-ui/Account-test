package com.smart.hawala.desk;

import com.smart.hawala.Auth;
import com.smart.hawala.Db;
import com.smart.hawala.Model;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/**
 * 10.5 (§7): «مكاتبي والتحقق» — المكاتب المخصصة للمحاسب بأرصدتها وعلامة آخر رسالة واتساب نُقلت لكل منها، وقائمة
 * «بانتظار تحققي»: حركات نافذة أدخلها غيره ولم يتحقق منها. «تم التحقق» لا يُنشئ أي قيد.
 */
final class MineView extends View {
    MineView(App app) { super(app, "مكاتبي"); }

    // 10.13: صفحات من الأعلى (كالهاتف): مكاتب للتسجيل، مكاتب للتحقق، مطابقات للتحقق، تسجيلات المحاسبين، وكل المكاتب للمدير
    static final String P_REC = "rec", P_CHK = "chk", P_RECON = "recon", P_TX = "tx", P_ALL = "all";
    String filter;

    List<String[]> pages() {
        List<String[]> p = new ArrayList<>();
        if (!db.myOffices().isEmpty()) p.add(new String[]{P_REC, "مكاتب للتسجيل"});
        if (!db.myCheckOffices().isEmpty()) p.add(new String[]{P_CHK, "مكاتب للتحقق"});
        if (can(Auth.Perm.VERIFY)) {
            p.add(new String[]{P_RECON, "مطابقات للتحقق"});
            p.add(new String[]{P_TX, "تسجيلات المحاسبين"});
        }
        if (can(Auth.Perm.USERS)) p.add(new String[]{P_ALL, "كل المكاتب"});
        return p;
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        subtitle.setText("أسجّل " + db.myOffices().size() + " مكتب • أتحقق من " + db.myCheckOffices().size());
        if (can(Auth.Perm.USERS)) action(Ui.ghost("فرز المكاتب بين المحاسبين", e -> app.go("team")));
        List<String[]> pages = pages();
        if (pages.isEmpty()) {
            add(Ui.para("لم يخصص لك المدير مكاتب بعد (المستخدمون ← فرز المكاتب).", 13.5f, Ui.muted, w));
            done();
            return;
        }
        String cur = db.setting("mine_page", pages.get(0)[0]);
        int sel = 0;
        String[] labels = new String[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            labels[i] = pages.get(i)[1];
            if (pages.get(i)[0].equals(cur)) sel = i;
        }
        Ui.Seg seg = new Ui.Seg(labels, null, sel);
        seg.listener = i -> {
            db.setSettingQuiet("mine_page", pages.get(i)[0]);
            filter = null;
            refresh();
        };
        seg.setMaximumSize(new Dimension(Math.min(w, 190 * labels.length), 46));
        add(seg);
        space(10);
        String page = pages.get(sel)[0];
        switch (page) {
            case P_REC: offices(db.myOffices(), page, w); break;
            case P_CHK: offices(db.myCheckOffices(), page, w); break;
            case P_RECON: recons(w); break;
            case P_TX: txs(w); break;
            default: {
                List<Model.Office> all = new ArrayList<>();
                for (Model.Office o : db.offices()) if (o.active) all.add(o);
                offices(all, page, w);
            }
        }
        done();
    }

    static Color workColor(String s) {
        if (Db.W_DOING.equals(s)) return Ui.dark ? new Color(251, 191, 36) : new Color(196, 118, 6);
        if (Db.W_DONE.equals(s)) return Ui.green;
        if (Db.W_RECON.equals(s)) return Ui.dark ? new Color(96, 165, 250) : new Color(37, 99, 235);
        return Ui.muted;
    }

    void offices(List<Model.Office> list, String page, int w) {
        Map<Long, Db.Work> work = db.officeWork();
        Map<Long, Db.Team> teams = new java.util.HashMap<>();
        for (Db.Team t : db.teams()) teams.put(t.office.id, t);
        Map<Long, Integer> waitMine = new java.util.HashMap<>();
        if (P_CHK.equals(page)) for (Model.Tx t : db.awaitingMyCheck(60, 2000)) waitMine.merge(t.officeId, 1, Integer::sum);
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Model.Office o : list) {
            Db.Work x = work.get(o.id);
            counts.merge(x == null ? Db.W_NONE : x.status, 1, Integer::sum);
        }
        List<Component> fl = new ArrayList<>();
        fl.add(filterBtn("الكل " + list.size(), null));
        for (String k : Db.WORK_KEYS) fl.add(filterBtn(Db.workLabel(k) + " " + counts.getOrDefault(k, 0), k));
        add(Ui.flow(fl.toArray(new Component[0])));
        space(4);
        String sort = db.setting("mine_sort", "status");
        String[] sk = {"status", "name", "mine"};
        Ui.Seg so = new Ui.Seg(new String[]{"بالحالة", "بالاسم", "ترتيبي"}, null, java.util.Arrays.asList(sk).indexOf(sort) < 0 ? 0 : java.util.Arrays.asList(sk).indexOf(sort));
        so.listener = i -> {
            db.setSettingQuiet("mine_sort", sk[i]);
            refresh();
        };
        so.setMaximumSize(new Dimension(330, 40));
        add(Ui.row(Ui.label("الترتيب:", 13, Ui.muted, false), so));
        if ("mine".equals(sort)) add(Ui.para("رتّب بالسهمين ↑↓ في كل مكتب. الترتيب لك وحدك (ويتبعه الهاتف).", 12.5f, Ui.muted, w));
        space(6);
        List<Model.Office> shown = new ArrayList<>();
        for (Model.Office o : list) {
            Db.Work x = work.get(o.id);
            if (filter == null || filter.equals(x == null ? Db.W_NONE : x.status)) shown.add(o);
        }
        java.text.Collator col = java.text.Collator.getInstance(new java.util.Locale("ar"));
        List<String> order = new ArrayList<>(java.util.Arrays.asList(db.setting("mine_order", "").split(",")));
        order.remove("");
        if ("name".equals(sort)) shown.sort((x, y) -> col.compare(x.name, y.name));
        else if ("mine".equals(sort)) shown.sort((x, y) -> {
            int ix = order.indexOf(String.valueOf(x.id)), iy = order.indexOf(String.valueOf(y.id));
            if (ix < 0) ix = Integer.MAX_VALUE;
            if (iy < 0) iy = Integer.MAX_VALUE;
            return ix != iy ? Integer.compare(ix, iy) : col.compare(x.name, y.name);
        });
        else shown.sort((x, y) -> {
            Db.Work wx = work.get(x.id), wy = work.get(y.id);
            int rx = Db.workRank(wx == null ? Db.W_NONE : wx.status), ry = Db.workRank(wy == null ? Db.W_NONE : wy.status);
            return rx != ry ? Integer.compare(rx, ry) : col.compare(x.name, y.name);
        });
        if (shown.isEmpty()) add(Ui.para(list.isEmpty() ? "لا مكاتب في هذه الصفحة." : "لا مكاتب بهذه الحالة.", 13.5f, Ui.muted, w));
        Map<String, Integer> dec = db.decimalsMap();
        JPanel grid = new JPanel(new GridLayout(0, Math.max(1, Math.min(3, w / 330)), 10, 10));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (int idx = 0; idx < shown.size(); idx++) {
            Model.Office o = shown.get(idx);
            Db.Work x = work.get(o.id) != null ? work.get(o.id) : new Db.Work();
            Ui.Card c = Ui.card();
            Color wc = workColor(x.status);
            Ui.Btn st = Ui.button(Db.workLabel(x.status) + (x.manual != null ? " " : ""), Ui.mix(Ui.surface, wc, Ui.dark ? 0.25f : 0.14f), null);
            st.colors(Ui.mix(Ui.surface, wc, Ui.dark ? 0.25f : 0.14f), wc);
            st.setFont(Ui.font(12.5f, true));
            st.setToolTipText(x.manual != null ? "وضعها " + x.byName + " — انقر للتغيير" : "تلقائية — انقر للتغيير");
            if (db.canSetWork(o.id)) st.addActionListener(e -> pickStatus(st, o, x));
            else st.setEnabled(false);
            c.add(Ui.row(Ui.label(o.name, 15.5f, Ui.primaryInk, true), Box.createHorizontalGlue(), st));
            List<String> marks = new ArrayList<>();
            if (P_CHK.equals(page)) {
                Integer n = waitMine.get(o.id);
                marks.add(n == null || n == 0 ? "✓ تحققتُ" : " " + n + " بانتظار تحققي");
                Db.Team t = teams.get(o.id);
                marks.add("يسجّله: " + (t == null || t.owners.isEmpty() ? "—" : Db.names(t.owners)));
            } else {
                if (x.myTxToday > 0) marks.add("✓ سجّلتُ " + x.myTxToday);
                else if (x.txToday > 0) marks.add("✓ سُجّلت " + x.txToday);
                if (x.reconToday) marks.add(x.myReconToday ? "✓ أرسلتُ مطابقة" : "✓ أُرسلت مطابقة");
                if (x.txToday > 0) marks.add(x.unverifiedToday == 0 ? "✓ تُحقِّق منها" : " " + x.unverifiedToday + " بلا تحقق");
                if (P_ALL.equals(page)) {
                    Db.Team t = teams.get(o.id);
                    marks.add("يسجّله: " + (t == null || t.owners.isEmpty() ? "—" : Db.names(t.owners)) + " • يتحقق: " + (t == null || t.checkers.isEmpty() ? "—" : Db.names(t.checkers)));
                }
            }
            if (!marks.isEmpty()) c.add(Ui.para(String.join("   ", marks), 12.5f, Ui.muted, 300));
            StringBuilder bal = new StringBuilder();
            for (Map.Entry<String, Long> e : db.balances(o.id).entrySet()) {
                if (e.getValue() == 0) continue;
                bal.append(bal.length() > 0 ? "  •  " : "").append(e.getKey()).append(" ").append(app.hidden() ? "••••" : Model.bal(e.getValue(), dec.getOrDefault(e.getKey(), 2)));
            }
            c.add(Ui.para(bal.length() == 0 ? "متوازن" : bal.toString(), 12.5f, Ui.ink, 300));
            List<Component> bs = new ArrayList<>();
            bs.add(Ui.ghost("فتح", e -> app.go("office", o.id)));
            bs.add(Ui.ghost("آخر رسالة", e -> markDialog(o)));
            if ("mine".equals(db.setting("mine_sort", "status"))) {
                final int at = idx;
                bs.add(Ui.ghost("↑", e -> move(shown, at, -1)));
                bs.add(Ui.ghost("↓", e -> move(shown, at, 1)));
            }
            c.add(Ui.flow(bs.toArray(new Component[0])));
            grid.add(c);
        }
        add(grid);
    }

    Ui.Btn filterBtn(String label, String key) {
        boolean on = key == null ? filter == null : key.equals(filter);
        Ui.Btn b = on ? Ui.primary(label, null) : Ui.ghost(label, null);
        b.addActionListener(e -> {
            filter = key == null || key.equals(filter) ? null : key;
            refresh();
        });
        return b;
    }

    /** يحرّك مكتبًا في «ترتيبي» خطوة (يحفظ ترتيب كل الظاهر، وما لم يظهر بعده). */
    void move(List<Model.Office> shown, int at, int dir) {
        int to = at + dir;
        if (to < 0 || to >= shown.size()) return;
        List<String> ids = new ArrayList<>();
        for (Model.Office o : shown) ids.add(String.valueOf(o.id));
        java.util.Collections.swap(ids, at, to);
        for (String old : db.setting("mine_order", "").split(",")) if (!old.isEmpty() && !ids.contains(old)) ids.add(old);
        db.setSettingQuiet("mine_order", String.join(",", ids));
        refresh();
    }

    void pickStatus(Component at, Model.Office o, Db.Work x) {
        JPopupMenu m = new JPopupMenu();
        m.setComponentOrientation(Ui.RTL);
        for (String k : Db.WORK_KEYS) {
            JMenuItem it = new JMenuItem(Db.workLabel(k) + (k.equals(x.status) ? "  ✓" : ""));
            it.setFont(Ui.font(13.5f, false));
            it.addActionListener(e -> setWork(o, k));
            m.add(it);
        }
        m.addSeparator();
        JMenuItem auto = new JMenuItem("تلقائية (" + Db.workLabel(x.auto) + ")" + (x.manual == null ? "  ✓" : ""));
        auto.setFont(Ui.font(13.5f, false));
        auto.addActionListener(e -> setWork(o, null));
        m.add(auto);
        m.show(at, 0, at.getHeight());
    }

    void setWork(Model.Office o, String k) {
        try {
            db.setOfficeWork(o.id, k);
            refresh();
        } catch (Exception ex) {
            Ui.error(this, ex);
        }
    }

    void markDialog(Model.Office o) {
        JPanel c = Ui.column();
        markCard(c, o, 460);
        JOptionPane.showMessageDialog(this, Ui.rtl(c), "آخر رسالة نُقلت — " + o.name, JOptionPane.PLAIN_MESSAGE);
    }

    void txs(int w) {
        List<Model.Tx> wait = db.awaitingMyCheck(60, 300);
        add(Ui.heading("بانتظار تحققي (" + wait.size() + ")"));
        add(Ui.para("حركات أدخلها غيرك في مكاتب تتحقق منها خلال آخر 60 يومًا. انقر مرتين لعرض الحركة. التحقق لا يُنشئ قيدًا، وتعديل المبلغ أو العملة أو الاتجاه أو المكتب يُبطله.",
                13, Ui.muted, w));
        space(6);
        if (wait.isEmpty()) add(Ui.label("لا حركات بانتظار تحققك ✓", 14, Ui.green, true));
        else {
            JTable t = LogView.txTable(app, wait, true);
            JScrollPane sp = Ui.tableBox(t);
            sp.setPreferredSize(new Dimension(800, Math.min(16, Math.max(3, wait.size())) * 32 + 36));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp.getPreferredSize().height));
            add(sp);
            space(6);
            // 10.16: «تحديد الكل»، والتحقق دفعة واحدة في طبقة البيانات (ما يُرفض يُذكر سببه والباقي يُسجَّل)
            add(Ui.flow(Ui.ghost("تحديد الكل", e -> {
                if (t.getSelectedRowCount() == t.getRowCount()) t.clearSelection();
                else t.selectAll();
            }), Ui.primary("تحققتُ من المحدد", e -> {
                int[] rows = t.getSelectedRows();
                if (rows.length == 0) {
                    Ui.toast("حدّد حركة أو أكثر في الجدول، أو اضغط «تحديد الكل»");
                    return;
                }
                List<Long> ids = new ArrayList<>();
                for (int r : rows) ids.add(wait.get(t.convertRowIndexToModel(r)).id);
                if (!Ui.confirm(this, "تم التحقق", "تسجيل تحققك من " + ids.size() + " حركة؟ لا يُنشئ أي قيد. تأكد أنك راجعتها فعلًا: التحقق شهادة باسمك.", "تم التحقق")) return;
                try {
                    Object[] r = db.verifyTxs(ids);
                    @SuppressWarnings("unchecked") List<String> bad = (List<String>) r[1];
                    if (bad.isEmpty()) Ui.toast("سُجّل تحققك من " + r[0] + " حركة ✓");
                    else Ui.info(this, "سُجّل " + r[0] + " ولم يُسجَّل " + bad.size(), String.join("\n", bad));
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
                refresh();
            })));
        }
        List<Model.Tx> done = db.checkedByMe(7, 50);
        if (!done.isEmpty()) {
            space(12);
            add(Ui.heading("تحققتُ منها (آخر 7 أيام)"));
            Map<Long, String> names = db.officeNames();
            for (Model.Tx t : done)
                add(Ui.label("✓ " + (Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ") + Ui.ltr(Model.fmt(t.amount, db.decimals(t.currency)) + " " + t.currency) + " — "
                        + Model.nz(names.get(t.officeId)), 13, Ui.green, false));
        }
    }

    void recons(int w) {
        Map<String, Integer> dec = db.decimalsMap();
        List<Model.Recon> recs = db.awaitingMyRecons(60, 100);
        add(Ui.heading("بانتظار تحققي (" + recs.size() + ")"));
        space(6);
        if (recs.isEmpty()) add(Ui.label("لا مطابقات بانتظار تحققك ✓", 14, Ui.green, true));
        Map<Long, String> on = db.officeNames();
        for (Model.Recon r : recs) {
            Ui.Card c = Ui.card();
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Long> e : r.balances.entrySet())
                b.append(b.length() > 0 ? "   •   " : "").append(e.getKey()).append(" ").append(app.hidden() ? "••••" : Model.bal(e.getValue(), dec.getOrDefault(e.getKey(), 2)));
            c.add(Ui.label("«" + Model.nz(on.get(r.officeId)) + "» — " + Model.date(r.createdAt) + " — " + r.statusLabel(), 14, Ui.primaryInk, true));
            c.add(Ui.label(b.toString(), 13, Ui.ink, false));
            c.add(Ui.label("✓ أُرسلت • " + db.reconPeople(r), 12, Ui.muted, false));
            c.add(Ui.flow(Ui.primary("تحققتُ من المطابقة ✓", e -> {
                try {
                    db.verifyRecon(r.id);
                    Ui.toast("سُجّل تحققك من المطابقة ✓");
                    refresh();
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            })));
            add(c);
            space(6);
        }
        List<Model.Recon> done = db.reconsCheckedByMe(14, 50);
        if (!done.isEmpty()) {
            space(12);
            add(Ui.heading("تحققتُ منها (آخر 14 يومًا)"));
            for (Model.Recon r : done) add(Ui.label("✓ «" + Model.nz(on.get(r.officeId)) + "» — " + Model.date(r.createdAt), 13, Ui.green, false));
        }
    }

    /** علامة «آخر رسالة واتساب نُقلت» للمكتب: النص والتاريخ ومن حفظها، ونسخها وتحديثها وتاريخها. */
    void markCard(JPanel c, Model.Office o, int w) {
        Db.WaMark m = db.lastWaMark(o.id);
        c.add(Ui.gap(6));
        if (m == null) c.add(Ui.label("آخر رسالة نُقلت: لم تُحفظ علامة بعد", 13, Ui.muted, false));
        else {
            c.add(Ui.label("آخر رسالة نُقلت (حفظها " + m.userName + " — " + Model.date(m.at) + (m.msgTime.isEmpty() ? "" : " — وقت الرسالة " + m.msgTime) + "):", 13, Ui.goldInk, true));
            c.add(Ui.para(m.text, 13.5f, Ui.ink, w - 60));
        }
        c.add(Ui.para("العلامة تساعدك لتعرف من أين تكمل؛ حفظها ليس دليلًا على تسجيل أي حركة.", 12, Ui.muted, w - 60));
        List<Component> bs = new ArrayList<>();
        if (m != null) bs.add(Ui.ghost("نسخ النص", e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(m.text), null);
            Ui.toast("نُسخ نص آخر رسالة");
        }));
        if (can(Auth.Perm.TX_ADD)) bs.add(Ui.ghost("تحديث العلامة…", e -> updateMark(this, db, o, this::refresh)));
        bs.add(Ui.ghost("التاريخ", e -> history(this, db, o)));
        c.add(Ui.flow(bs.toArray(new Component[0])));
    }

    static void updateMark(Component parent, Db db, Model.Office o, Runnable after) {
        JTextArea text = Ui.area(5);
        Ui.Field time = Ui.field("وقت الرسالة كما في واتساب (اختياري)");
        JPanel c = Ui.column();
        c.add(Ui.para("الصق نص آخر رسالة واتساب نقلتها إلى الدفتر لـ«" + o.name + "». يُحفظ مع اسمك ووقت الحفظ، ويبقى التاريخ السابق.", 13, Ui.muted, 440));
        c.add(Ui.gap(6));
        JScrollPane sp = Ui.areaBox(text);
        sp.setPreferredSize(new Dimension(440, 120));
        c.add(sp);
        c.add(Ui.gap(6));
        c.add(Ui.labeled("وقت الرسالة", time));
        if (!Ui.form(parent, "آخر رسالة نُقلت — " + o.name, Ui.rtl(c), "حفظ العلامة")) return;
        try {
            db.setWaMark(o.id, text.getText(), time.val());
            Ui.toast("حُفظت العلامة");
            if (after != null) after.run();
        } catch (Exception e) {
            Ui.error(parent, e);
        }
    }

    static void history(Component parent, Db db, Model.Office o) {
        StringBuilder b = new StringBuilder();
        for (Db.WaMark m : db.waMarks(o.id, 50))
            b.append(Model.date(m.at)).append(" — ").append(m.userName).append(m.msgTime.isEmpty() ? "" : " — وقت الرسالة " + m.msgTime).append("\n").append(m.text).append("\n\n");
        Ui.info(parent, "تاريخ علامة آخر رسالة — " + o.name, b.length() == 0 ? "لا توجد علامات بعد." : b.toString().trim());
    }
}
