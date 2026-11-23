package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 10.8: «فرز المكاتب بين المحاسبين» — لكل مكتب محاسب «يسجّل» حركاته ومطابقاته، ومحاسب آخر (أو أكثر) «يتحقق» منها ويؤكد سلامتها.
 * محاسبان: كل منهما يتحقق من مكاتب الآخر. ثلاثة: كل اثنين يتحققان من مكاتب الثالث. القواعد في Db (setOfficeTeam، autoCheckers، verifyTx).
 */
final class TeamScreen {
    private final MainActivity a;

    TeamScreen(MainActivity a) { this.a = a; }

    static final String CONCEPT = "لكل مكتب محاسب يسجّل حركاته ومطابقاته، ومحاسب آخر يتحقق منها ويؤكد سلامتها. "
            + "محاسبان: كل منهما يتحقق من مكاتب الآخر. ثلاثة محاسبين: كل اثنين يتحققان من مكاتب الثالث. "
            + "التحقق لا يغيّر رصيدًا؛ يثبت أن محاسبًا غير المسجّل راجع الحركة. ولا يتحقق أحد مما أدخله هو.";

    void show() {
        if (!a.require(Auth.Perm.USERS)) return;
        a.here(this::show);
        a.tabbedScreen("فرز المكاتب بين المحاسبين", a.usersScreen::showManager, "more");
        LinearLayout c = a.card();
        c.addView(a.text("الفكرة", 16, a.primaryInk, true));
        c.addView(a.text(CONCEPT, 13, a.dark, false));
        List<Model.User> acc = a.db.accountants();
        List<Db.Team> teams = a.db.teams();
        int noOwner = 0, noCheck = 0;
        for (Db.Team t : teams) {
            if (t.owners.isEmpty()) noOwner++;
            else if (t.checkers.isEmpty()) noCheck++;
        }
        if (acc.size() < 2) a.notice(R.drawable.ic_info, "يلزم محاسبان على الأقل (غير المدير) ليتحقق كل منهما من الآخر. أضفهما من «المستخدمون».", false, null, null);
        if (noCheck > 0) a.notice(R.drawable.ic_warn, noCheck + " مكتب له من يسجّله ولا أحد يتحقق منه.", true, null, null);
        if (noOwner > 0) a.hint(noOwner + " مكتب بلا محاسب يسجّله بعد. اضغط «تعديل» عند المكتب، أو حدّد مكاتب كل محاسب من «المستخدمون ← المكاتب».");
        if (acc.size() >= 2) a.button("توزيع تلقائي…", a.primary, v -> autoPlan());
        for (final Db.Team t : teams) {
            LinearLayout x = a.card();
            x.addView(a.text(t.office.name, 17, a.primaryInk, true));
            x.addView(a.text("يسجّل: " + (t.owners.isEmpty() ? "— لم يُحدد" : names(t.owners)), 14, t.owners.isEmpty() ? a.muted : a.green, true));
            boolean warn = !t.owners.isEmpty() && t.checkers.isEmpty();
            x.addView(a.text("يتحقق: " + (t.checkers.isEmpty() ? (warn ? "⚠ لا أحد" : "—") : names(t.checkers)), 14, warn ? a.red : t.checkers.isEmpty() ? a.muted : a.primaryInk, true));
            if (t.owners.size() > 1) x.addView(a.text("⚠ أكثر من محاسب يسجّل هذا المكتب", 12, a.warnInk, false));
            int open = a.db.unverifiedCount(t.office.id, 60);
            if (open > 0) x.addView(a.text(open + " حركة خلال 60 يومًا لم يتحقق منها أحد", 12, a.muted, false));
            x.addView(a.smallButton("تعديل", a.accent, v -> edit(t)), a.lp(-1, 42, 3));
        }
    }

    static String names(List<Model.User> l) { return Db.names(l); }

    /** فريق مكتب واحد: لكل محاسب «—» أو «يسجّل» أو «يتحقق». */
    void edit(final Db.Team t) {
        final List<Model.User> acc = a.db.accountants();
        if (acc.isEmpty()) {
            a.toast("لا محاسبين بعد: أضفهم من «المستخدمون»");
            return;
        }
        final Set<Long> own = new HashSet<>(), chk = new HashSet<>();
        for (Model.User u : t.owners) own.add(u.id);
        for (Model.User u : t.checkers) chk.add(u.id);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(16), a.dp(6), a.dp(16), 0);
        box.addView(a.text("لكل محاسب دور واحد في هذا المكتب. من يسجّل لا يتحقق من المكتب نفسه.", 13, a.muted, false));
        int gray = Color.rgb(120, 120, 120);
        for (final Model.User u : acc) {
            box.addView(a.text(u.name, 15, a.ink, true));
            final boolean canCheck = Auth.can(u, Auth.Perm.VERIFY);
            final Ui.Seg s = a.new Seg(new String[]{"—", "يسجّل", canCheck ? "يتحقق" : "يتحقق (بلا صلاحية)"}, new int[]{gray, a.green, a.primary},
                    own.contains(u.id) ? 1 : chk.contains(u.id) ? 2 : 0, false);
            s.listener = i -> {
                own.remove(u.id);
                chk.remove(u.id);
                if (i == 1) own.add(u.id);
                if (i == 2) {
                    if (!canCheck) {
                        a.toast("«" + u.name + "» ليست لديه صلاحية «التحقق من الحركات»: أضفها من «الصلاحيات والتعديل»");
                        s.set(0, false);
                        return;
                    }
                    chk.add(u.id);
                }
            };
            box.addView(s.view, a.lp(-1, -2, 2));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new AlertDialog.Builder(a).setTitle("فريق «" + t.office.name + "»").setView(sv).setPositiveButton("حفظ", (d, w) -> {
            final Runnable save = () -> {
                try {
                    a.db.setOfficeTeam(t.office.id, own, chk);
                    a.toast("حُفظ فريق «" + t.office.name + "»");
                    show();
                } catch (Exception e) {
                    a.error(e);
                }
            };
            List<String> warn = new ArrayList<>();
            if (own.size() > 1) warn.add("أكثر من محاسب يسجّل هذا المكتب (" + own.size() + "): يجوز، لكن كل منهم يراه في «مكاتبي».");
            if (!own.isEmpty() && chk.isEmpty()) warn.add("لا أحد يتحقق من هذا المكتب: حركاته تبقى بلا تحقق.");
            if (warn.isEmpty()) save.run();
            else new AlertDialog.Builder(a).setTitle("تنبيه").setMessage(Model.join(warn, "\n")).setPositiveButton("حفظ رغم ذلك", (x, y) -> save.run())
                    .setNegativeButton("رجوع", null).show();
        }).setNegativeButton("إلغاء", null).show();
    }

    static String rolesLine(Db db, long userId) { return db.rolesLine(userId); }

    /** وصف قصير لمن يتحقق من مكتب (لـ«مكاتبي»). */
    static TextView checkersLine(MainActivity a, Db.Team t) {
        return a.text(t == null || t.checkers.isEmpty() ? "⚠ لا أحد يتحقق من هذا المكتب بعد" : "يتحقق منه: " + names(t.checkers), 13,
                t == null || t.checkers.isEmpty() ? a.red : a.primaryInk, false);
    }

    /**
     * 10.18: التوزيع التلقائي على مرحلتين: (1) المكاتب غير الموزّعة تُوزَّع بالتساوي بعد معاينة واحدة؛
     * (2) المكاتب الموزّعة من قبل يُسأل عنها واحدًا واحدًا: اعتماد المقترح، أو إبقاؤها، أو إيقاف.
     */
    void autoPlan() {
        try {
            final List<Db.TeamPlan> fresh = new ArrayList<>(), old = new ArrayList<>();
            for (Db.TeamPlan p : a.db.autoTeamPlan()) {
                if (p.owners.isEmpty() || p.same()) continue;
                (p.assigned ? old : fresh).add(p);
            }
            if (fresh.isEmpty() && old.isEmpty()) {
                a.toast("لا شيء يتغير: كل المكاتب موزّعة والتحقق موزّع");
                return;
            }
            final int[] done = {0};
            if (fresh.isEmpty()) {
                askOld(old, 0, done);
                return;
            }
            StringBuilder b = new StringBuilder(fresh.size() + " مكتب غير موزّع — تُوزَّع بالتساوي هكذا:\n\n");
            java.util.Map<String, List<String>> by = new java.util.LinkedHashMap<>();
            for (Db.TeamPlan p : fresh) {
                String k = names(p.owners);
                if (!by.containsKey(k)) by.put(k, new ArrayList<String>());
                by.get(k).add(p.office.name);
            }
            for (java.util.Map.Entry<String, List<String>> en : by.entrySet())
                b.append("• ").append(en.getKey()).append(" يسجّل (").append(en.getValue().size()).append("): ").append(Model.join(en.getValue(), "، ")).append("\n");
            b.append("\nويتحقق من كل مكتب المحاسبون الآخرون الذين لهم صلاحية «التحقق من الحركات».\nتنبيه: من يُخصَّص له مكاتب يرى مكاتبه فقط.");
            new AlertDialog.Builder(a).setTitle("توزيع المكاتب غير الموزّعة").setMessage(b.toString()).setPositiveButton("توزيع", (d, w) -> {
                try {
                    for (Db.TeamPlan p : fresh) {
                        a.db.applyTeamPlan(p);
                        done[0]++;
                    }
                } catch (Exception e) {
                    a.error(e);
                }
                askOld(old, 0, done);
            }).setNeutralButton("تخطّي غير الموزّعة", (d, w) -> askOld(old, 0, done)).setNegativeButton("إلغاء", null).show();
        } catch (Exception e) {
            a.error(e);
        }
    }

    private void askOld(final List<Db.TeamPlan> old, final int i, final int[] done) {
        if (i >= old.size()) {
            a.toast(done[0] == 0 ? "لم يتغير شيء" : "وُزّع " + done[0] + " مكتب");
            show();
            return;
        }
        final Db.TeamPlan p = old.get(i);
        String msg = "الحالي:\n  يسجّل: " + names(p.curOwners) + "\n  يتحقق: " + (p.curCheckers.isEmpty() ? "لا أحد" : names(p.curCheckers))
                + "\n\nالمقترح:\n  يسجّل: " + names(p.owners) + " (كما هو)\n  يتحقق: " + (p.checkers.isEmpty() ? "لا أحد" : names(p.checkers));
        new AlertDialog.Builder(a).setTitle(p.office.name + " (" + (i + 1) + " من " + old.size() + ")").setMessage(msg).setCancelable(false)
                .setPositiveButton("اعتماد المقترح", (d, w) -> {
                    try {
                        a.db.applyTeamPlan(p);
                        done[0]++;
                    } catch (Exception e) {
                        a.error(e);
                    }
                    askOld(old, i + 1, done);
                }).setNeutralButton("إبقاء كما هو", (d, w) -> askOld(old, i + 1, done)).setNegativeButton("إيقاف", (d, w) -> askOld(old, old.size(), done)).show();
    }
}
