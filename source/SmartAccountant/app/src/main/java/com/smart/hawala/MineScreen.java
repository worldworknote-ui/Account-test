package com.smart.hawala;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Map;

/**
 * 10.5 (§7): «مكاتبي والتحقق» على الهاتف — المكاتب المخصصة للمحاسب بأرصدتها وعلامة آخر رسالة واتساب نُقلت لكل منها،
 * و«بانتظار تحققي»: حركات نافذة أدخلها غيره ولم يتحقق منها. «تم التحقق» لا يُنشئ أي قيد. (مثل شاشة الكمبيوتر نفسها)
 */
final class MineScreen {
    private final MainActivity a;

    MineScreen(MainActivity a) { this.a = a; }

    /** يظهر لمن خُصصت له مكاتب أو له صلاحية التحقق، وللمدير ومدير المستخدمين (حالات كل المكاتب) — مع نظام المستخدمين. */
    boolean available() {
        return a.db.usersEnabled() && (a.can(Auth.Perm.VERIFY) || a.can(Auth.Perm.USERS) || !a.db.myOffices().isEmpty() || !a.db.myCheckOffices().isEmpty());
    }

    // 10.13: صفحات «مكاتبي» من الأعلى
    static final String P_REC = "rec", P_CHK = "chk", P_RECON = "recon", P_TX = "tx", P_ALL = "all";
    static final String SORT_STATUS = "status", SORT_NAME = "name", SORT_MINE = "mine";
    /** تصفية الحالة الحالية (null = الكل). */
    private String filter;

    /** الصفحات المتاحة لهذا المستخدم: [المفتاح، الاسم]. */
    List<String[]> pages() {
        List<String[]> p = new java.util.ArrayList<>();
        if (!a.db.myOffices().isEmpty()) p.add(new String[]{P_REC, "مكاتب للتسجيل"});
        if (!a.db.myCheckOffices().isEmpty()) p.add(new String[]{P_CHK, "مكاتب للتحقق"});
        if (a.can(Auth.Perm.VERIFY)) {
            p.add(new String[]{P_RECON, "مطابقات للتحقق"});
            p.add(new String[]{P_TX, "تسجيلات المحاسبين"});
        }
        if (a.can(Auth.Perm.USERS)) p.add(new String[]{P_ALL, "كل المكاتب"});
        return p;
    }

    void show() {
        a.here(this::show);
        a.tabbedScreen("مكاتبي", a::showHome, a.tabKeys().contains("mine") ? "mine" : "more");
        final List<String[]> pages = pages();
        if (pages.isEmpty()) {
            a.hint("لم يخصص لك المدير مكاتب بعد (المستخدمون ← فرز المكاتب).");
            return;
        }
        String cur = a.db.setting("mine_page", pages.get(0)[0]);
        int sel = 0;
        String[] labels = new String[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            labels[i] = pages.get(i)[1];
            if (pages.get(i)[0].equals(cur)) sel = i;
        }
        final String page = pages.get(sel)[0];
        Ui.Seg seg = a.new Seg(labels, null, sel, true);
        seg.listener = i -> {
            a.db.setSettingQuiet("mine_page", pages.get(i)[0]);
            filter = null;
            show();
        };
        a.body.addView(seg.view, a.lp(-1, -2, 2));
        switch (page) {
            case P_REC: offices(a.db.myOffices(), P_REC); break;
            case P_CHK: offices(a.db.myCheckOffices(), P_CHK); break;
            case P_RECON: recons(); break;
            case P_TX: txs(); break;
            default: {
                List<Model.Office> all = new java.util.ArrayList<>();
                for (Model.Office o : a.db.offices()) if (o.active) all.add(o);
                offices(all, P_ALL);
            }
        }
    }

    /** لون كل حالة. */
    int workColor(String s) {
        if (Db.W_DOING.equals(s)) return a.night ? Color.rgb(251, 191, 36) : Color.rgb(196, 118, 6);
        if (Db.W_DONE.equals(s)) return a.green;
        if (Db.W_RECON.equals(s)) return a.night ? Color.rgb(96, 165, 250) : Color.rgb(37, 99, 235);
        return a.muted;
    }

    /** صفحة مكاتب (للتسجيل، للتحقق، أو كلها للمدير): تصفية بالحالة، وترتيب بالحالة أو الاسم أو ترتيبي بالسحب. */
    private void offices(List<Model.Office> list, final String page) {
        final Map<Long, Db.Work> work = a.db.officeWork();
        final java.util.Map<Long, Db.Team> teams = new java.util.HashMap<>();
        for (Db.Team t : a.db.teams()) teams.put(t.office.id, t);
        Map<Long, Integer> waitMine = new java.util.HashMap<>();
        if (P_CHK.equals(page)) for (Model.Tx t : a.db.awaitingMyCheck(60, 2000)) waitMine.merge(t.officeId, 1, Integer::sum);
        // التصفية بالحالة مع عدد كل حالة
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Model.Office o : list) {
            Db.Work w = work.get(o.id);
            counts.merge(w == null ? Db.W_NONE : w.status, 1, Integer::sum);
        }
        LinearLayout pills = a.row();
        pills.addView(a.pill("الكل " + list.size(), filter == null, v -> {
            filter = null;
            show();
        }), a.gap(-2, -2, 3));
        for (final String k : Db.WORK_KEYS) {
            Integer n = counts.get(k);
            pills.addView(a.pill(Db.workLabel(k) + " " + (n == null ? 0 : n), k.equals(filter), v -> {
                filter = k.equals(filter) ? null : k;
                show();
            }), a.gap(-2, -2, 3));
        }
        a.body.addView(a.strip(pills), a.lp(-1, -2, 2));
        // الترتيب
        final String sort = a.db.setting("mine_sort", SORT_STATUS);
        LinearLayout sr = a.row();
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.addView(a.text("الترتيب:", 13, a.muted, false), new LinearLayout.LayoutParams(-2, -2));
        String[][] sorts = {{SORT_STATUS, "بالحالة"}, {SORT_NAME, "بالاسم"}, {SORT_MINE, "ترتيبي"}};
        for (final String[] so : sorts)
            sr.addView(a.pill(so[1], so[0].equals(sort), v -> {
                a.db.setSettingQuiet("mine_sort", so[0]);
                show();
            }), a.gap(-2, -2, 3));
        a.body.addView(sr, a.lp(-1, -2, 2));
        if (SORT_MINE.equals(sort)) a.hint("اسحب «≡» لترتيب المكاتب كما تحب. الترتيب لك وحدك.");

        List<Model.Office> shown = new java.util.ArrayList<>();
        for (Model.Office o : list) {
            Db.Work w = work.get(o.id);
            if (filter == null || filter.equals(w == null ? Db.W_NONE : w.status)) shown.add(o);
        }
        final java.text.Collator col = java.text.Collator.getInstance(new java.util.Locale("ar"));
        if (SORT_NAME.equals(sort)) shown.sort((x, y) -> col.compare(x.name, y.name));
        else if (SORT_MINE.equals(sort)) {
            final List<String> order = java.util.Arrays.asList(a.db.setting("mine_order", "").split(","));
            shown.sort((x, y) -> {
                int ix = order.indexOf(String.valueOf(x.id)), iy = order.indexOf(String.valueOf(y.id));
                if (ix < 0) ix = Integer.MAX_VALUE;
                if (iy < 0) iy = Integer.MAX_VALUE;
                return ix != iy ? Integer.compare(ix, iy) : col.compare(x.name, y.name);
            });
        } else {
            shown.sort((x, y) -> {
                Db.Work wx = work.get(x.id), wy = work.get(y.id);
                int rx = Db.workRank(wx == null ? Db.W_NONE : wx.status), ry = Db.workRank(wy == null ? Db.W_NONE : wy.status);
                return rx != ry ? Integer.compare(rx, ry) : col.compare(x.name, y.name);
            });
        }
        if (shown.isEmpty()) a.hint(list.isEmpty() ? "لا مكاتب في هذه الصفحة." : "لا مكاتب بهذه الحالة.");
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        a.body.addView(box, a.lp(-1, -2, 0));
        final Ui.DragSort drag = SORT_MINE.equals(sort) ? a.new DragSort(box, keys -> {
            List<String> ids = new java.util.ArrayList<>();
            for (Object k : keys) ids.add(String.valueOf(k));
            // ما لم يظهر (مُصفّى) يبقى بعدها بترتيبه
            for (String old : a.db.setting("mine_order", "").split(",")) if (!old.isEmpty() && !ids.contains(old)) ids.add(old);
            a.db.setSettingQuiet("mine_order", Model.join(ids, ","));
        }) : null;
        Map<String, Integer> dec = a.db.decimalsMap();
        for (final Model.Office o : shown) {
            final Db.Work w = work.get(o.id) != null ? work.get(o.id) : new Db.Work();
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            c.setBackground(a.round(a.surface, a.cardStroke, 16));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(0, a.dp(4), 0, a.dp(4));
            box.addView(c, cp);
            LinearLayout top = a.row();
            top.setGravity(Gravity.CENTER_VERTICAL);
            if (drag != null) {
                TextView h = drag.handle();
                top.addView(h, new LinearLayout.LayoutParams(a.dp(36), a.dp(40)));
                drag.bind(c, h, o.id);
            }
            TextView nm = a.text(o.name, 16, a.primaryInk, true);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            int wc = workColor(w.status);
            TextView st = a.chip(Db.workLabel(w.status) + (w.manual != null ? " ✎" : ""), wc, Ui.mix(a.surface, wc, a.night ? 0.22f : 0.12f));
            st.setTextSize(12);
            st.setContentDescription("الحالة: " + Db.workLabel(w.status) + (w.manual != null ? " (وضعها " + w.byName + ")" : " (تلقائية)"));
            if (a.db.canSetWork(o.id)) st.setOnClickListener(v -> pickStatus(o, w));
            top.addView(st, new LinearLayout.LayoutParams(-2, -2));
            c.addView(top);
            // العلامات: سجّلتُ، أرسلتُ، تحققتُ / بانتظاري
            List<String> marks = new java.util.ArrayList<>();
            if (P_CHK.equals(page)) {
                Integer n = waitMine.get(o.id);
                marks.add(n == null || n == 0 ? "✓ تحققتُ" : "⏳ " + n + " بانتظار تحققي");
                Db.Team t = teams.get(o.id);
                marks.add("يسجّله: " + (t == null || t.owners.isEmpty() ? "—" : TeamScreen.names(t.owners)));
            } else {
                if (w.myTxToday > 0) marks.add("✓ سجّلتُ " + w.myTxToday);
                else if (w.txToday > 0) marks.add("✓ سُجّلت " + w.txToday);
                if (w.reconToday) marks.add(w.myReconToday ? "✓ أرسلتُ مطابقة" : "✓ أُرسلت مطابقة");
                if (w.txToday > 0) marks.add(w.unverifiedToday == 0 ? "✓ تُحقِّق منها" : "⏳ " + w.unverifiedToday + " بلا تحقق");
                if (P_ALL.equals(page)) {
                    Db.Team t = teams.get(o.id);
                    marks.add("يسجّله: " + (t == null || t.owners.isEmpty() ? "—" : TeamScreen.names(t.owners))
                            + " • يتحقق: " + (t == null || t.checkers.isEmpty() ? "—" : TeamScreen.names(t.checkers)));
                }
            }
            if (w.manual != null && !w.byName.isEmpty()) marks.add("✎ " + w.byName);
            if (!marks.isEmpty()) c.addView(a.text(Model.join(marks, "   "), 12, a.muted, false));
            StringBuilder bal = new StringBuilder();
            for (Map.Entry<String, Long> e : a.db.balances(o.id).entrySet()) {
                if (e.getValue() == 0) continue;
                bal.append(bal.length() > 0 ? "  •  " : "").append("\u2066").append(Model.bal(e.getValue(), dec.containsKey(e.getKey()) ? dec.get(e.getKey()) : 2))
                        .append(" ").append(e.getKey()).append("\u2069");
            }
            c.addView(a.text(bal.length() == 0 ? "متوازن" : bal.toString(), 12, a.ink, false));
            LinearLayout r = a.row();
            r.addView(a.smallButton("فتح", a.accent, v -> a.officeHub.showOffice(o)), a.weight(36, 1, 2));
            if (!P_CHK.equals(page) && a.can(Auth.Perm.TX_ADD))
                r.addView(a.smallButton("تسجيل", a.primary, v -> a.openRecord("tx", o)), a.weight(36, 1, 2));
            r.addView(a.smallButton("آخر رسالة", Color.rgb(120, 120, 120), v -> markDialog(o)), a.weight(36, 1, 2));
            c.addView(r, a.lp(-1, -2, 2));
        }
    }

    /** اختيار حالة المكتب اليوم، أو إعادتها تلقائية. */
    private void pickStatus(final Model.Office o, final Db.Work w) {
        String[] items = new String[Db.WORK_KEYS.length + 1];
        for (int i = 0; i < Db.WORK_KEYS.length; i++) items[i] = Db.workLabel(Db.WORK_KEYS[i]) + (Db.WORK_KEYS[i].equals(w.status) ? "  ✓" : "");
        items[Db.WORK_KEYS.length] = "تلقائية (" + Db.workLabel(w.auto) + ")" + (w.manual == null ? "  ✓" : "");
        new AlertDialog.Builder(a).setTitle("حالة «" + o.name + "» اليوم").setItems(items, (d, i) -> {
            try {
                a.db.setOfficeWork(o.id, i < Db.WORK_KEYS.length ? Db.WORK_KEYS[i] : null);
                a.tick(a.body);
                show();
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    /** علامة آخر رسالة نُقلت في نافذة (بدل بطاقة كبيرة في كل مكتب). */
    private void markDialog(final Model.Office o) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        final AlertDialog[] dlg = {null};
        markCard(l, o, () -> {
            if (dlg[0] != null) dlg[0].dismiss();
            show();
        });
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(l);
        dlg[0] = new AlertDialog.Builder(a).setTitle("آخر رسالة نُقلت — " + o.name).setView(sv).setPositiveButton("إغلاق", null).show();
    }

    /** مطابقات للتحقق: بانتظار تحققي، وما تحققتُ منه مؤخرًا. */
    private void recons() {
        Map<String, Integer> dec = a.db.decimalsMap();
        Map<Long, String> on = a.db.officeNames();
        final List<Model.Recon> recs = a.db.awaitingMyRecons(60, 100);
        a.section("بانتظار تحققي (" + recs.size() + ")", null, null);
        if (recs.isEmpty()) a.add(a.text("لا مطابقات بانتظار تحققك ✓", 14, a.green, true));
        for (final Model.Recon r : recs) {
            LinearLayout c = a.card();
            c.addView(a.text("«" + Model.nz(on.get(r.officeId)) + "» — " + Model.date(r.createdAt), 15, a.primaryInk, true));
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Long> e : r.balances.entrySet())
                b.append(b.length() > 0 ? "  •  " : "").append("\u2066").append(Model.bal(e.getValue(), dec.containsKey(e.getKey()) ? dec.get(e.getKey()) : 2)).append(" ")
                        .append(e.getKey()).append("\u2069");
            c.addView(a.text(b.toString(), 13, a.ink, false));
            c.addView(a.text("✓ أُرسلت • " + a.db.reconPeople(r) + " • " + r.statusLabel(), 12, a.muted, false));
            c.addView(a.smallButton("تحققتُ من المطابقة ✓", a.primary, v -> {
                try {
                    a.db.verifyRecon(r.id);
                    a.toast("سُجّل تحققك من المطابقة ✓");
                    show();
                } catch (Exception e) {
                    a.error(e);
                }
            }), a.lp(-1, 40, 3));
        }
        List<Model.Recon> done = a.db.reconsCheckedByMe(14, 50);
        if (!done.isEmpty()) {
            a.section("تحققتُ منها (آخر 14 يومًا)", null, null);
            for (Model.Recon r : done)
                a.add(a.text("✓ «" + Model.nz(on.get(r.officeId)) + "» — " + Model.date(r.createdAt), 13, a.green, false));
        }
    }

    /** تسجيلات المحاسبين للتحقق: حركات أدخلها غيري في مكاتب أتحقق منها، وما تحققتُ منه مؤخرًا. */
    private void txs() {
        final List<Model.Tx> wait = a.db.awaitingMyCheck(60, 200);
        a.section("بانتظار تحققي (" + wait.size() + ")", null, null);
        a.hint("التحقق لا يُنشئ قيدًا ولا يغيّر الأرصدة، وتعديل المبلغ أو العملة أو الاتجاه أو المكتب يُبطله.");
        if (wait.isEmpty()) a.add(a.text("لا حركات بانتظار تحققك ✓", 14, a.green, true));
        Map<Long, String> names = a.db.officeNames();
        // 10.16: تحديد عدة حركات أو الكل والتحقق منها دفعة واحدة
        final java.util.Set<Long> picked = new java.util.LinkedHashSet<>();
        final List<android.widget.CheckBox> boxes = new java.util.ArrayList<>();
        final android.widget.Button[] go = new android.widget.Button[1];
        final Runnable count = () -> {
            if (go[0] != null) go[0].setText(picked.isEmpty() ? "تحققتُ من المحدد" : "تحققتُ من المحدد (" + picked.size() + ")");
        };
        if (wait.size() > 1) {
            LinearLayout br = a.row();
            br.addView(a.smallButton("تحديد الكل", a.accent, v -> {
                boolean all = picked.size() < wait.size();
                for (android.widget.CheckBox cb : boxes) cb.setChecked(all);
            }), a.weight(42, 1, 2));
            go[0] = a.smallButton("تحققتُ من المحدد", a.primary, v -> {
                if (picked.isEmpty()) {
                    a.toast("حدّد حركة واحدة على الأقل (أو «تحديد الكل»)");
                    return;
                }
                a.confirm("التحقق من " + picked.size() + " حركة", "يُسجَّل تحققك من الحركات المحددة كلها. تأكد أنك راجعتها فعلًا: التحقق شهادة باسمك.", "تحققتُ", () -> {
                    try {
                        Object[] r = a.db.verifyTxs(new java.util.ArrayList<>(picked));
                        @SuppressWarnings("unchecked") List<String> bad = (List<String>) r[1];
                        if (bad.isEmpty()) a.toast("سُجّل تحققك من " + r[0] + " حركة ✓");
                        else new AlertDialog.Builder(a).setTitle("سُجّل " + r[0] + " ولم يُسجَّل " + bad.size()).setMessage(Model.join(bad, "\n")).setPositiveButton("حسنًا", null).show();
                        show();
                    } catch (Exception e) {
                        a.error(e);
                    }
                });
            });
            br.addView(go[0], a.weight(42, 1.4f, 2));
            a.body.addView(br);
        }
        for (final Model.Tx t : wait) {
            LinearLayout c = a.card();
            int d = a.db.decimals(t.currency);
            if (wait.size() > 1) {
                final android.widget.CheckBox cb = new android.widget.CheckBox(a);
                cb.setText("تحديد");
                cb.setOnCheckedChangeListener((x, on) -> {
                    if (on) picked.add(t.id);
                    else picked.remove(t.id);
                    count.run();
                });
                boxes.add(cb);
                c.addView(cb);
            }
            c.addView(a.text((Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ") + "\u2066" + Model.fmt(t.amount, d) + " " + t.currency + "\u2069 — " + Model.nz(names.get(t.officeId)),
                    15, Model.TYPE_DEBIT.equals(t.type) ? a.green : a.red, true));
            c.addView(a.text("✓ سجّلها " + Model.join(a.db.txPeople(t), "، ") + (t.person.isEmpty() ? "" : " • " + t.person), 12, a.muted, false));
            LinearLayout r = a.row();
            r.addView(a.smallButton("تحققتُ ✓", a.primary, v -> {
                try {
                    a.db.verifyTx(t.id);
                    a.toast("سُجّل تحققك ✓");
                    show();
                } catch (Exception e) {
                    a.error(e);
                }
            }), a.weight(40, 1, 2));
            r.addView(a.smallButton("التفاصيل", a.accent, v -> a.logScreen.details(t, this::show)), a.weight(40, 1, 2));
            c.addView(r);
        }
        List<Model.Tx> done = a.db.checkedByMe(7, 50);
        if (!done.isEmpty()) {
            a.section("تحققتُ منها (آخر 7 أيام)", null, null);
            for (Model.Tx t : done)
                a.add(a.text("✓ " + (Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ") + "\u2066" + Model.fmt(t.amount, a.db.decimals(t.currency)) + " " + t.currency + "\u2069 — "
                        + Model.nz(names.get(t.officeId)), 13, a.green, false));
        }
    }

    /** علامة «آخر رسالة واتساب نُقلت» للمكتب: النص والتاريخ ومن حفظها، ونسخها وتحديثها وتاريخها. */
    void markCard(LinearLayout c, final Model.Office o, final Runnable after) {
        final Db.WaMark m = a.db.lastWaMark(o.id);
        if (m == null) c.addView(a.text("آخر رسالة نُقلت: لم تُحفظ علامة بعد", 13, a.muted, false));
        else {
            c.addView(a.text("آخر رسالة نُقلت (حفظها " + m.userName + " — " + Model.date(m.at) + (m.msgTime.isEmpty() ? "" : " — وقت الرسالة " + m.msgTime) + "):", 13, a.goldInk, true));
            android.widget.TextView tx = a.text(m.text, 14, a.ink, false);
            tx.setTextIsSelectable(true);
            c.addView(tx);
        }
        c.addView(a.text("العلامة تساعدك لتعرف من أين تكمل؛ حفظها ليس دليلًا على تسجيل أي حركة.", 12, a.muted, false));
        LinearLayout r = a.row();
        if (m != null) r.addView(a.smallButton("نسخ النص", a.accent, v -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("آخر رسالة", m.text));
            a.toast("نُسخ نص آخر رسالة");
        }), a.weight(30, 1, 2));
        if (a.can(Auth.Perm.TX_ADD)) r.addView(a.smallButton("تحديث", a.primary, v -> update(o, after)), a.weight(30, 1, 2));
        r.addView(a.smallButton("التاريخ", android.graphics.Color.rgb(120, 120, 120), v -> history(o)), a.weight(30, 1, 2));
        c.addView(r);
    }

    void update(final Model.Office o, final Runnable after) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(a.text("الصق نص آخر رسالة واتساب نقلتها إلى الدفتر لـ«" + o.name + "». يُحفظ مع اسمك ووقت الحفظ، ويبقى التاريخ السابق.", 13, a.muted, false));
        final EditText text = new EditText(a);
        text.setMinLines(3);
        text.setHint("نص الرسالة");
        l.addView(text, new LinearLayout.LayoutParams(-1, -2));
        final EditText time = new EditText(a);
        time.setHint("وقت الرسالة كما في واتساب (اختياري)");
        l.addView(time, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle("آخر رسالة نُقلت — " + o.name).setView(l).setPositiveButton("حفظ العلامة", (d, w) -> {
            try {
                a.db.setWaMark(o.id, text.getText().toString(), time.getText().toString());
                a.toast("حُفظت العلامة");
                if (after != null) after.run();
            } catch (Exception e) {
                a.error(e);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    void history(Model.Office o) {
        StringBuilder b = new StringBuilder();
        for (Db.WaMark m : a.db.waMarks(o.id, 50))
            b.append(Model.date(m.at)).append(" — ").append(m.userName).append(m.msgTime.isEmpty() ? "" : " — وقت الرسالة " + m.msgTime).append("\n").append(m.text).append("\n\n");
        new AlertDialog.Builder(a).setTitle("تاريخ علامة آخر رسالة — " + o.name).setMessage(b.length() == 0 ? "لا توجد علامات بعد." : b.toString().trim())
                .setPositiveButton("إغلاق", null).show();
    }
}
