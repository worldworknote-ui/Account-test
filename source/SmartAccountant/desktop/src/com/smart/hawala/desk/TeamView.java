package com.smart.hawala.desk;

import com.smart.hawala.Auth;
import com.smart.hawala.Db;
import com.smart.hawala.Model;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.*;

/**
 * 10.8: «فرز المكاتب بين المحاسبين» — لكل مكتب من «يسجّل» حركاته ومطابقاته ومن «يتحقق» منها (مثل شاشة الهاتف).
 * محاسبان: كل منهما يتحقق من مكاتب الآخر. ثلاثة: كل اثنين يتحققان من مكاتب الثالث. القواعد في Db.
 */
final class TeamView extends View {
    static final String CONCEPT = "لكل مكتب محاسب يسجّل حركاته ومطابقاته، ومحاسب آخر يتحقق منها ويؤكد سلامتها. "
            + "محاسبان: كل منهما يتحقق من مكاتب الآخر. ثلاثة محاسبين: كل اثنين يتحققان من مكاتب الثالث. "
            + "التحقق لا يغيّر رصيدًا؛ يثبت أن محاسبًا غير المسجّل راجع الحركة. ولا يتحقق أحد مما أدخله هو.";

    TeamView(App app) { super(app, "فرز المكاتب بين المحاسبين"); }

    @Override void refresh() {
        clear();
        int w = textWidth();
        List<Model.User> acc = db.accountants();
        List<Db.Team> teams = db.teams();
        int noOwner = 0, noCheck = 0;
        for (Db.Team t : teams) {
            if (t.owners.isEmpty()) noOwner++;
            else if (t.checkers.isEmpty()) noCheck++;
        }
        subtitle.setText(acc.size() + " محاسب • " + teams.size() + " مكتب" + (noCheck > 0 ? " • " + noCheck + " بلا تحقق" : ""));
        action(Ui.ghost("رجوع إلى المستخدمين", e -> app.go("users")));
        if (acc.size() >= 2 && can(Auth.Perm.USERS)) action(Ui.primary("توزيع تلقائي…", e -> autoPlan()));
        Ui.Card c = Ui.tinted(Ui.primarySoft, null);
        c.add(Ui.label("الفكرة", 15, Ui.primaryInk, true));
        c.add(Ui.para(CONCEPT, 13.5f, Ui.ink, w));
        add(c);
        space(8);
        if (acc.size() < 2) add(Ui.para("يلزم محاسبان على الأقل (غير المدير) ليتحقق كل منهما من الآخر. أضفهما من «المستخدمون».", 13.5f, Ui.warnInk, w));
        if (noCheck > 0) add(Ui.para("! " + noCheck + " مكتب له من يسجّله ولا أحد يتحقق منه.", 14, Ui.red, w));
        if (noOwner > 0) add(Ui.para(noOwner + " مكتب بلا محاسب يسجّله بعد: اضغط «تعديل…» عند المكتب.", 13, Ui.muted, w));
        space(6);
        JPanel grid = new JPanel(new GridLayout(0, Math.max(1, Math.min(3, w / 330)), 10, 10));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (Db.Team t : teams) {
            Ui.Card x = Ui.card();
            x.add(Ui.label(t.office.name, 16, Ui.primaryInk, true));
            x.add(Ui.gap(4));
            x.add(Ui.label("يسجّل: " + (t.owners.isEmpty() ? "— لم يُحدد" : Db.names(t.owners)), 13.5f, t.owners.isEmpty() ? Ui.muted : Ui.green, true));
            boolean warn = !t.owners.isEmpty() && t.checkers.isEmpty();
            x.add(Ui.label("يتحقق: " + (t.checkers.isEmpty() ? (warn ? "! لا أحد" : "—") : Db.names(t.checkers)), 13.5f, warn ? Ui.red : t.checkers.isEmpty() ? Ui.muted : Ui.primaryInk, true));
            if (t.owners.size() > 1) x.add(Ui.label("! أكثر من محاسب يسجّله", 12, Ui.warnInk, false));
            int open = db.unverifiedCount(t.office.id, 60);
            x.add(Ui.label(open == 0 ? "✓ لا حركات بلا تحقق (60 يومًا)" : open + " حركة بلا تحقق (60 يومًا)", 12, open == 0 ? Ui.green : Ui.muted, false));
            x.add(Ui.gap(6));
            if (can(Auth.Perm.USERS)) x.add(Ui.flow(Ui.ghost("تعديل…", e -> edit(this, db, t, this::refresh))));
            grid.add(x);
        }
        add(grid);
        done();
    }

    /** فريق مكتب واحد: لكل محاسب «—» أو «يسجّل» أو «يتحقق». */
    static void edit(Component parent, Db db, Db.Team t, Runnable after) {
        List<Model.User> acc = db.accountants();
        if (acc.isEmpty()) {
            Ui.toast("لا محاسبين بعد: أضفهم من «المستخدمون»");
            return;
        }
        JPanel c = Ui.column();
        c.add(Ui.para("لكل محاسب دور واحد في «" + t.office.name + "». من يسجّل لا يتحقق من المكتب نفسه.", 13, Ui.muted, 420));
        c.add(Ui.gap(6));
        Set<Long> own0 = new HashSet<>(), chk0 = new HashSet<>();
        for (Model.User u : t.owners) own0.add(u.id);
        for (Model.User u : t.checkers) chk0.add(u.id);
        Map<Long, JComboBox<String>> roles = new LinkedHashMap<>();
        for (Model.User u : acc) {
            boolean canCheck = Auth.can(u, Auth.Perm.VERIFY);
            JComboBox<String> cb = Ui.combo(canCheck ? new String[]{"—", "يسجّل", "يتحقق"} : new String[]{"—", "يسجّل"});
            cb.setSelectedIndex(own0.contains(u.id) ? 1 : chk0.contains(u.id) && canCheck ? 2 : 0);
            cb.setMaximumSize(new Dimension(150, 36));
            roles.put(u.id, cb);
            c.add(Ui.row(Ui.label(u.name + (canCheck ? "" : " (بلا صلاحية التحقق)"), 14, Ui.ink, true), Box.createHorizontalGlue(), cb));
            c.add(Ui.gap(4));
        }
        if (!Ui.form(parent, "فريق «" + t.office.name + "»", Ui.rtl(c), "حفظ")) return;
        Set<Long> own = new HashSet<>(), chk = new HashSet<>();
        for (Map.Entry<Long, JComboBox<String>> e : roles.entrySet()) {
            int i = e.getValue().getSelectedIndex();
            if (i == 1) own.add(e.getKey());
            if (i == 2) chk.add(e.getKey());
        }
        List<String> warn = new ArrayList<>();
        if (own.size() > 1) warn.add("أكثر من محاسب يسجّل هذا المكتب (" + own.size() + "): يجوز، لكن كل منهم يراه في «مكاتبي».");
        if (!own.isEmpty() && chk.isEmpty()) warn.add("لا أحد يتحقق من هذا المكتب: حركاته تبقى بلا تحقق.");
        if (!warn.isEmpty() && !Ui.confirm(parent, "تنبيه", String.join("\n", warn), "حفظ رغم ذلك")) return;
        try {
            db.setOfficeTeam(t.office.id, own, chk);
            Ui.toast("حُفظ فريق «" + t.office.name + "»");
            if (after != null) after.run();
        } catch (Exception ex) {
            Ui.error(parent, ex);
        }
    }

    /**
     * 10.18: التوزيع التلقائي على مرحلتين: (1) المكاتب غير الموزّعة تُوزَّع بالتساوي بعد معاينة واحدة؛
     * (2) المكاتب الموزّعة من قبل يُسأل عنها واحدًا واحدًا: اعتماد المقترح، أو إبقاؤها، أو تعديلها يدويًا.
     */
    void autoPlan() {
        try {
            List<Db.TeamPlan> plan = db.autoTeamPlan();
            List<Db.TeamPlan> fresh = new ArrayList<>(), old = new ArrayList<>();
            for (Db.TeamPlan p : plan) {
                if (p.owners.isEmpty() || p.same()) continue;
                (p.assigned ? old : fresh).add(p);
            }
            if (fresh.isEmpty() && old.isEmpty()) {
                Ui.toast("لا شيء يتغير: كل المكاتب موزّعة والتحقق موزّع");
                return;
            }
            int done = 0;
            if (!fresh.isEmpty()) {
                StringBuilder b = new StringBuilder(fresh.size() + " مكتب غير موزّع — تُوزَّع بالتساوي هكذا:\n\n");
                Map<String, List<String>> by = new LinkedHashMap<>();
                for (Db.TeamPlan p : fresh) by.computeIfAbsent(Db.names(p.owners), x -> new ArrayList<>()).add(p.office.name);
                for (Map.Entry<String, List<String>> en : by.entrySet())
                    b.append("• ").append(en.getKey()).append(" يسجّل (").append(en.getValue().size()).append("): ").append(String.join("، ", en.getValue())).append("\n");
                b.append("\nويتحقق من كل مكتب المحاسبون الآخرون الذين لهم صلاحية «التحقق من الحركات».\nتنبيه: من يُخصَّص له مكاتب يرى مكاتبه فقط.");
                JTextArea ta = Ui.area(14);
                ta.setText(b.toString());
                ta.setEditable(false);
                ta.setCaretPosition(0);
                JScrollPane sp = Ui.areaBox(ta);
                sp.setPreferredSize(new Dimension(560, 300));
                JOptionPane op = new JOptionPane(sp, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"توزيع", "تخطّي غير الموزّعة", "إلغاء"}, "توزيع");
                Ui.show(this, op, "توزيع المكاتب غير الموزّعة");
                Object v = op.getValue();
                if (v == null || "إلغاء".equals(v)) return;
                if ("توزيع".equals(v)) for (Db.TeamPlan p : fresh) {
                    db.applyTeamPlan(p);
                    done++;
                }
            }
            int n = 0;
            for (Db.TeamPlan p : old) {
                n++;
                String msg = "المكتب: " + p.office.name + "\n\nالحالي:\n  يسجّل: " + Db.names(p.curOwners) + "\n  يتحقق: " + (p.curCheckers.isEmpty() ? "لا أحد" : Db.names(p.curCheckers))
                        + "\n\nالمقترح:\n  يسجّل: " + Db.names(p.owners) + " (كما هو)\n  يتحقق: " + (p.checkers.isEmpty() ? "لا أحد" : Db.names(p.checkers));
                JOptionPane op = new JOptionPane(Ui.para(msg, 14, Ui.ink, 440), JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                        new Object[]{"اعتماد المقترح", "إبقاء كما هو", "تعديل يدوي…", "إيقاف"}, "اعتماد المقترح");
                Ui.show(this, op, "مكتب موزّع (" + n + " من " + old.size() + ")");
                Object v = op.getValue();
                if (v == null || "إيقاف".equals(v)) break;
                if ("اعتماد المقترح".equals(v)) {
                    db.applyTeamPlan(p);
                    done++;
                } else if ("تعديل يدوي…".equals(v)) {
                    for (Db.Team t : db.teams()) if (t.office.id == p.office.id) edit(this, db, t, () -> { });
                }
            }
            app.changed();
            Ui.toast(done == 0 ? "لم يتغير شيء" : "وُزّع " + done + " مكتب");
            refresh();
        } catch (Exception ex) {
            Ui.error(this, ex);
        }
    }
}
