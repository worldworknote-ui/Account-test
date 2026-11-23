package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import javax.swing.*;

/** المستخدمون والصلاحيات (كالهاتف): مدير برمز 6–8 أرقام ورمز استرداد يُعرض مرة، وأدوار، وتأكيد المدير قبل التغييرات. */
final class UsersView extends View {
    static long reauthAt;
    static final long REAUTH_MS = 120000L;

    UsersView(App app) {
        super(app, "المستخدمون والصلاحيات");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        if (!db.usersEnabled()) {
            Ui.Card c = Ui.card();
            c.add(Ui.heading("نظام المستخدمين غير مفعّل"));
            c.add(Ui.gap(6));
            c.add(Ui.para("حاليًا يعمل البرنامج بصلاحيات كاملة لأي شخص يفتحه على هذا الكمبيوتر.\n\nعند التفعيل: لكل شخص اسم ورمز سري، ويختار المدير صلاحياته واحدة واحدة، "
                    + "وتظهر شاشة دخول، ويُقفل البرنامج بعد الخمول، وتُسجَّل كل حركة باسم من سجّلها.", 14, Ui.ink, w));
            c.add(Ui.gap(10));
            String block = db.founderBlock();
            if (block != null) c.add(Ui.para(block, 13.5f, Ui.goldInk, w));
            else {
                if (SyncEngine.enabled(db)) c.add(Ui.para("هذا هو الجهاز الرئيسي في المزامنة: الحسابات التي تنشئها هنا تصل إلى كل الأجهزة المنضمة، ويُطلب فيها الدخول.", 13, Ui.muted, w));
                c.add(Ui.flow(Ui.primary("تفعيل: إنشاء حساب المدير", e -> userDialog(null, true))));
            }
            add(c);
            done();
            return;
        }
        boolean has = db.hasRecoveryCode();
        Ui.Card rc = Ui.tinted(has ? Ui.creditBg : Ui.debitBg, null);
        rc.add(Ui.label(has ? "✓ رمز استرداد المدير محفوظ" : "! لا يوجد رمز استرداد للمدير", 16, has ? Ui.green : Ui.red, true));
        rc.add(Ui.para(has ? "إن فُقد الرمز المكتوب أنشئ رمزًا جديدًا؛ القديم يتوقف فورًا." : "بدونه لا يمكن استعادة دخول المدير إن نُسي رمزه. أنشئه الآن واكتبه على ورقة.", 13, Ui.ink, w));
        rc.add(Ui.gap(6));
        rc.add(Ui.flow(Ui.button(has ? "إنشاء رمز استرداد جديد" : "إنشاء رمز الاسترداد", has ? Ui.accent : Ui.red,
                e -> confirmAdmin("رمز استرداد جديد", () -> showRecoveryCode(this, db.newRecoveryCode(), "رمز استرداد المدير الجديد (أي رمز سابق توقف):")))));
        add(rc);
        space(10);
        Ui.Card lock = Ui.card();
        lock.add(Ui.label("القفل بعد الخمول", 15, Ui.primaryInk, true));
        JComboBox<String> mins = Ui.combo(new String[]{"دقيقتان", "5 دقائق", "10 دقائق", "30 دقيقة", "ساعة", "لا يُقفل تلقائيًا"});
        long[] vals = {2, 5, 10, 30, 60, 0};
        long cur = db.settingLong("desk_lock_min", 5);
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) mins.setSelectedIndex(i);
        mins.addActionListener(e -> db.setSettingQuiet("desk_lock_min", "" + vals[mins.getSelectedIndex()]));
        lock.add(mins);
        add(lock);
        space(10);
        action(Ui.primary("إضافة مستخدم", e -> confirmAdmin("إضافة مستخدم", () -> userDialog(null, false))));
        // 10.8: من يسجّل كل مكتب ومن يتحقق منه
        action(Ui.ghost("فرز المكاتب بين المحاسبين", e -> confirmAdmin("فرز المكاتب", () -> app.go("team"))));
        // 10.17: رسالة يكتبها المدير وتظهر إشعارًا عند المحاسبين (الكل أو واحد)
        if (SmartApp.currentUser != null && Auth.isAdmin(SmartApp.currentUser)) action(Ui.ghost("رسالة إلى المحاسبين…", e -> adminMessages(this, app)));
        Model.User me = SmartApp.currentUser;
        final boolean meAdmin = me == null || Auth.isAdmin(db.user(me.id));
        for (final Model.User u : db.users(false)) {
            Ui.Card x = Ui.card();
            boolean self = me != null && me.id == u.id;
            x.add(Ui.label(u.name + (self ? " (أنت)" : "") + (u.active ? "" : " — موقوف"), 17, u.active ? Ui.primaryInk : Ui.muted, true));
            x.add(Ui.para(Auth.describe(u), 13, Ui.ink, w));
            // 10.5 (§7): مكاتب المحاسب
            String roles = db.rolesLine(u.id);
            if (!roles.isEmpty()) x.add(Ui.para(roles, 13, Ui.goldInk, w));
            x.add(Ui.gap(6));
            // 10.5 (AUDIT-023): حساب المدير يعدّله ويغيّر رمزه المدير وحده
            if (!Auth.isAdmin(u) || meAdmin) x.add(Ui.flow(Ui.ghost("الصلاحيات والتعديل", e -> confirmAdmin("تعديل " + u.name, () -> userDialog(u, false))),
                    Ui.ghost("تغيير الرمز", e -> confirmAdmin("تغيير رمز " + u.name, () -> newPin(u))),
                    Ui.ghost("المكاتب…", e -> confirmAdmin("مكاتب " + u.name, () -> officesDialog(u))),
                    // 10.8: حذف الحساب دون المساس بما سجّله
                    self ? Box.createHorizontalStrut(1) : Ui.danger("حذف الحساب…", e -> confirmAdmin("حذف حساب " + u.name, () -> deleteUser(u)))));
            else x.add(Ui.para("حساب المدير يعدّله المدير وحده", 12, Ui.muted, w));
            add(x);
            space(8);
        }
        space(6);
        if (SyncEngine.enabled(db)) add(Ui.para("الحسابات والصلاحيات متزامنة: أي تعديل هنا يصل إلى كل الأجهزة. أرسل لكل شخص اسمه ورمزه ليدخل من جهازه.", 13, Ui.muted, w));
        space(10);
        if (db.founderBlock() == null) add(Ui.flow(Ui.danger("إيقاف نظام المستخدمين", e -> confirmAdmin("إيقاف نظام المستخدمين", () -> {
            if (!Ui.confirm(this, "إيقاف نظام المستخدمين", "ستُوقف كل الحسابات ويعود البرنامج للعمل بدون دخول وبصلاحيات كاملة. يمكنك إعادة التفعيل لاحقًا.", "إيقاف")) return;
            try {
                db.disableUsers();
            } catch (Exception ex) {
                Ui.error(this, ex);
                return;
            }
            SmartApp.currentUser = null;
            Db.currentUserName = "المستخدم المحلي";
            app.updateFoot();
            Ui.toast("تم إيقاف نظام المستخدمين");
            refresh();
        }))));
        done();
    }

    /** 10.8: حذف حساب: يُغلق ويُزال من المكاتب، وكل ما سجّله يبقى كما هو باسمه «(حساب محذوف)». */
    void deleteUser(Model.User u) {
        String typed = Ui.input(this, "حذف حساب " + u.name, "يُحذف حساب «" + u.name + "» نهائيًا: لا يستطيع الدخول بعد الآن من أي جهاز، ويُزال من فرز المكاتب.\n\n"
                + "لا يُحذف شيء مما سجّله: حركاته وتحققاته ومطابقاته تبقى كما هي، والأرصدة لا تتغير، ويظهر اسمه عليها «(حساب محذوف)».\n\nاكتب اسمه للتأكيد:", "");
        if (typed == null) return;
        if (!typed.trim().equals(u.name.trim())) {
            Ui.toast("الاسم غير مطابق؛ لم يُحذف شيء");
            return;
        }
        try {
            db.deleteUser(u.id);
            Ui.toast("حُذف حساب " + u.name + " — بقيت حركاته كما هي");
            refresh();
        } catch (Exception ex) {
            Ui.error(this, ex);
        }
    }

    /**
     * 10.5 (§7): مكاتب المحاسب. 10.7: المختارة أولًا، وبحث، وتنبيه للمكتب المشترك.
     * 10.8: لكل مكتب دور: «يسجّل» حركاته ومطابقاته، أو «يتحقق» منها.
     */
    void officesDialog(Model.User u) {
        java.util.Map<Long, String> roles = new java.util.LinkedHashMap<>(db.officeRoles(u.id));
        java.util.Map<Long, java.util.List<String>> others = db.officeHolders(u.id);
        java.util.List<Model.Office> all = db.offices();
        boolean canCheck = Auth.can(u, Auth.Perm.VERIFY);
        JPanel c = Ui.column();
        c.add(Ui.para("«يسجّل»: يُدخل حركات المكتب ومطابقاته ويراه في «مكاتبي». «يتحقق»: يراجع ما سجّله غيره ويؤكد سلامته. لا يجمع الدورين في مكتب واحد.", 13, Ui.muted, 460));
        c.add(Ui.gap(6));
        Ui.Field q = Ui.field("ابحث عن مكتب");
        c.add(q);
        JLabel count = Ui.label(" ", 13, Ui.primaryInk, true);
        c.add(count);
        JPanel list = Ui.column();
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAll();
            String f = OwnerReports.norm(q.getText());
            // المختارة أولًا (آخر اختيار في الأعلى)، ثم الباقي
            java.util.List<Model.Office> order = new java.util.ArrayList<>();
            for (Long id : roles.keySet()) for (Model.Office o : all) if (o.id == id) order.add(o);
            for (Model.Office o : all) if (!roles.containsKey(o.id)) order.add(o);
            int own = 0, chk = 0;
            for (String r : roles.values()) if (Db.ROLE_CHECK.equals(r)) chk++;
            else own++;
            for (Model.Office o : order) {
                if (!f.isEmpty() && !OwnerReports.norm(o.name).contains(f)) continue;
                String role = roles.get(o.id);
                JComboBox<String> cb = Ui.combo(canCheck ? new String[]{"—", "يسجّل", "يتحقق"} : new String[]{"—", "يسجّل"});
                cb.setSelectedIndex(role == null ? 0 : Db.ROLE_CHECK.equals(role) && canCheck ? 2 : 1);
                cb.setMaximumSize(new Dimension(130, 34));
                cb.addActionListener(e -> {
                    int i = cb.getSelectedIndex();
                    if (i == 0) roles.remove(o.id);
                    else {
                        java.util.Map<Long, String> n = new java.util.LinkedHashMap<>();
                        n.put(o.id, i == 2 ? Db.ROLE_CHECK : Db.ROLE_OWN);
                        for (java.util.Map.Entry<Long, String> x : roles.entrySet()) if (x.getKey() != o.id) n.put(x.getKey(), x.getValue());
                        roles.clear();
                        roles.putAll(n);
                        java.util.List<String> h = others.get(o.id);
                        if (i == 1 && h != null && !h.isEmpty()) Ui.toast("تنبيه: «" + o.name + "» يسجّله " + Model.join(h, "، ") + " أيضًا");
                    }
                    SwingUtilities.invokeLater(render[0]);
                });
                list.add(Ui.row(Ui.label(o.name + (o.active ? "" : " (موقوف)"), 14, Ui.ink, role != null), Box.createHorizontalGlue(), cb));
                java.util.List<String> h = others.get(o.id);
                if (h != null && !h.isEmpty())
                    list.add(Ui.label("    ! يسجّله أيضًا: " + Model.join(h, "، "), 12, Db.ROLE_OWN.equals(role) ? Ui.red : Ui.warnInk, Db.ROLE_OWN.equals(role)));
                list.add(Ui.gap(3));
            }
            count.setText("يسجّل " + own + " مكتب • يتحقق من " + chk);
            Ui.rtl(list);
            list.revalidate();
            list.repaint();
        };
        q.getDocument().addDocumentListener(TxView.doc(render[0]));
        render[0].run();
        JScrollPane sp = Ui.scroll(list);
        sp.setPreferredSize(new Dimension(460, Math.min(400, 40 * Math.max(3, all.size()) + 10)));
        c.add(sp);
        if (!Ui.form(this, "مكاتب " + u.name, Ui.rtl(c), "حفظ")) return;
        java.util.List<String> shared = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Long, String> e : roles.entrySet()) {
            if (!Db.ROLE_OWN.equals(e.getValue())) continue;
            java.util.List<String> h = others.get(e.getKey());
            Model.Office o = db.office(e.getKey());
            if (h != null && !h.isEmpty() && o != null) shared.add("• «" + o.name + "» يسجّله أيضًا: " + Model.join(h, "، "));
        }
        if (!shared.isEmpty() && !Ui.confirm(this, "مكاتب يسجّلها محاسب آخر أيضًا", Model.join(shared, "\n")
                + "\n\nيجوز ذلك، لكن الأوضح أن يسجّل كل مكتبَ محاسبٌ واحد ويتحقق منه غيره. تأكد أن هذا مقصود.", "حفظ رغم ذلك")) return;
        try {
            db.setUserOffices(u.id, roles);
            Ui.toast("حُفظت مكاتب " + u.name);
            refresh();
        } catch (Exception ex) {
            Ui.error(this, ex);
        }
    }

    void confirmAdmin(String why, Runnable action) { confirmAdmin(this, db, why, action); }

    /** 10.14: تأكيد رمز المستخدم الحالي (المدير) قبل عملية حساسة، من أي شاشة. */
    static void confirmAdmin(Component parent, Db db, String why, Runnable action) {
        Model.User me = SmartApp.currentUser;
        if (!db.usersEnabled() || me == null) {
            action.run();
            return;
        }
        if (reauthAt > 0 && Clock.now() - reauthAt < REAUTH_MS) {
            action.run();
            return;
        }
        if (!LoginView.canTry(db)) return;
        String p = LoginView.askPin(parent, "تأكيد: " + why, "أدخل رمزك للتأكيد.");
        if (p == null) return;
        if (db.verifyPin(me.id, p)) {
            LoginView.resetFails(db);
            reauthAt = Clock.now();
            action.run();
        } else {
            int f = LoginView.recordFail(db, me.name, me.id);
            long w = Pin.lockoutMillis(f);
            Ui.message(parent, "رمز خاطئ", w > 0 ? "انتظر " + LoginView.waitText(w) + "." : "الرمز غير صحيح.", JOptionPane.WARNING_MESSAGE);
        }
    }

    static boolean pinOk(String role, String pin) { return Auth.ADMIN.equals(role) ? Pin.validAdmin(pin) : Pin.valid(pin); }

    static String pinRule(String role) { return Auth.ADMIN.equals(role) ? "رمز المدير من 6 إلى 8 أرقام" : "الرمز من 4 إلى 8 أرقام"; }

    void userDialog(Model.User old, boolean firstAdmin) {
        JPanel c = Ui.column();
        Ui.Field name = Ui.field("اسم المستخدم");
        if (old != null) name.setText(old.name);
        // 10.17: الاسم الظاهر لبقية المحاسبين بدل اسم الدخول (اختياري)
        Ui.Field nick = Ui.field("اختياري");
        if (old != null) nick.setText(old.nick);
        c.add(Ui.labeled("الاسم", name));
        c.add(Ui.gap(6));
        c.add(Ui.labeled("الاسم الظاهر للآخرين (يراه المحاسبون بدل اسم الدخول؛ المدير يرى اسم الدخول)", nick));
        c.add(Ui.gap(8));
        JCheckBox admin = Ui.check("مدير — كل الصلاحيات", firstAdmin || Auth.isAdmin(old));
        boolean iAmAdmin = SmartApp.currentUser == null || Auth.isAdmin(db.user(SmartApp.currentUser.id));
        admin.setEnabled(!firstAdmin && iAmAdmin);
        c.add(admin);
        JPanel permBox = Ui.column();
        permBox.add(Ui.label("الصلاحيات (اختر ما يناسب هذا الشخص):", 13.5f, Ui.primaryInk, true));
        java.util.Map<Auth.Perm, JCheckBox> boxes = new java.util.LinkedHashMap<>();
        java.util.Set<String> cur = old == null ? Auth.parse("VIEW,TX_ADD") : Auth.perms(old);
        JCheckBox review = Ui.check("     حركاته تحتاج اعتمادًا قبل أن تدخل الأرصدة (أيًا كان المبلغ)", cur.contains(Auth.REVIEW));
        for (Object[] grp : Auth.GROUPS) {
            permBox.add(Ui.label((String) grp[0], 13.5f, Ui.goldInk, true));
            for (Auth.Perm p : (Auth.Perm[]) grp[1]) {
                String h = Auth.hint(p);
                JCheckBox cb = Ui.check(Auth.title(p) + (h.isEmpty() ? "" : "  — " + h), cur.contains(p.name()));
                permBox.add(cb);
                boxes.put(p, cb);
                if (p == Auth.Perm.TX_ADD) permBox.add(review);
            }
        }
        permBox.setVisible(!admin.isSelected());
        admin.addActionListener(e -> {
            permBox.setVisible(!admin.isSelected());
            SwingUtilities.getWindowAncestor(c).pack();
        });
        c.add(permBox);
        JPasswordField p1 = Ui.password(), p2 = Ui.password();
        JCheckBox active = Ui.check("الحساب نشط", old == null || old.active);
        if (old == null) {
            c.add(Ui.gap(8));
            c.add(Ui.labeled(firstAdmin ? "رمز المدير (6 إلى 8 أرقام)" : "الرمز السري (4 إلى 8 أرقام، والمدير 6)", p1));
            c.add(Ui.gap(6));
            c.add(Ui.labeled("أعد كتابة الرمز", p2));
        } else {
            c.add(Ui.gap(8));
            c.add(active);
            c.add(Ui.para("لتغيير رمز هذا المستخدم استخدم زر «تغيير الرمز».", 12, Ui.muted, 360));
        }
        if (firstAdmin) {
            c.add(Ui.gap(8));
            c.add(Ui.para("بعد الحفظ يظهر «رمز استرداد المدير» مرة واحدة: اكتبه على ورقة. هو الطريقة الوحيدة لاستعادة الدخول إن نُسي رمز المدير.", 12, Ui.muted, 360));
        }
        while (Ui.form(this, old == null ? (firstAdmin ? "حساب المدير" : "مستخدم جديد") : "تعديل «" + old.name + "»", Ui.rtl(c), "حفظ")) {
            try {
                String n = name.val();
                if (n.isEmpty()) throw new IllegalArgumentException("اكتب اسم المستخدم");
                for (Model.User u : db.users(false)) if ((old == null || u.id != old.id) && u.name.trim().equalsIgnoreCase(n)) throw new IllegalArgumentException("يوجد مستخدم بنفس الاسم");
                String r = admin.isSelected() ? Auth.ADMIN : Auth.CUSTOM;
                java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
                for (java.util.Map.Entry<Auth.Perm, JCheckBox> e : boxes.entrySet()) if (e.getValue().isSelected()) chosen.add(e.getKey().name());
                if (review.isSelected() && boxes.get(Auth.Perm.TX_ADD).isSelected()) chosen.add(Auth.REVIEW);
                if (!admin.isSelected() && chosen.isEmpty()) throw new IllegalArgumentException("اختر صلاحية واحدة على الأقل");
                String perms = Auth.csv(chosen);
                if (old == null) {
                    String pin = new String(p1.getPassword());
                    if (!pinOk(r, pin)) throw new IllegalArgumentException(pinRule(r));
                    if (!pin.equals(new String(p2.getPassword()))) throw new IllegalArgumentException("الرمزان غير متطابقين");
                    long id = db.addUser(n, r, perms, pin);
                    if (!nick.val().isEmpty()) db.setNick(id, nick.val());
                    if (firstAdmin) {
                        Model.User me = null;
                        for (Model.User u : db.users(true)) if (u.id == id) me = u;
                        if (me != null) {
                            SmartApp.currentUser = me;
                            Db.currentUserName = me.name;
                            reauthAt = Clock.now();
                        }
                        showRecoveryCode(this, db.newRecoveryCode(), "تم التفعيل. هذا «رمز استرداد المدير»:");
                    }
                } else {
                    db.updateUser(old.id, n, r, perms, active.isSelected());
                    if (!nick.val().equals(Model.nz(old.nick).trim())) db.setNick(old.id, nick.val());
                    Model.User me = SmartApp.currentUser;
                    if (me != null && me.id == old.id) SmartApp.currentUser = db.user(old.id);
                }
                app.updateFoot();
                refresh();
                return;
            } catch (Exception e) {
                Ui.error(this, e);
            }
        }
    }

    void newPin(Model.User u) {
        String p1 = LoginView.askPin(this, "رمز جديد لـ " + u.name, pinRule(u.role));
        if (p1 == null) return;
        String p2 = LoginView.askPin(this, "تأكيد الرمز", "أعد كتابة الرمز");
        if (p2 == null) return;
        try {
            if (!pinOk(u.role, p1)) throw new IllegalArgumentException(pinRule(u.role));
            if (!p1.equals(p2)) throw new IllegalArgumentException("الرمزان غير متطابقين");
            db.setPin(u.id, p1);
            Ui.toast("تم تغيير الرمز");
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    /** 10.17: المستخدم يختار اسمه الظاهر لبقية المحاسبين. */
    static void ownNick(Component parent, Db db) {
        Model.User me = SmartApp.currentUser;
        if (me == null) {
            Ui.toast("الاسم الظاهر يعمل مع نظام المستخدمين");
            return;
        }
        Model.User now = db.user(me.id);
        Ui.Field n = Ui.field("اتركه فارغًا لاسم الدخول");
        n.setText(now == null ? "" : now.nick);
        JPanel c = Ui.column();
        c.add(Ui.para("يراه بقية المحاسبين بجانب حركاتك بدل اسم دخولك «" + me.name + "». المدير يرى اسم الدخول دائمًا.", 13.5f, Ui.ink, 400));
        c.add(Ui.gap(8));
        c.add(Ui.labeled("اسمي الظاهر", n));
        if (!Ui.form(parent, "اسمي الظاهر للآخرين", Ui.rtl(c), "حفظ")) return;
        try {
            db.setNick(me.id, n.val());
            Ui.toast(n.val().isEmpty() ? "يظهر اسم دخولك" : "حُفظ الاسم الظاهر ✓");
        } catch (Exception e) {
            Ui.error(parent, e);
        }
    }

    /** 10.17: المدير يكتب رسالة للمحاسبين (الكل أو واحد)، ويرى ما أرسله ويسحبه. */
    static void adminMessages(Component parent, App app) {
        Db db = app.db;
        java.util.List<Model.User> to = new java.util.ArrayList<>();
        for (Model.User u : db.users(true)) if (SmartApp.currentUser == null || u.id != SmartApp.currentUser.id) to.add(u);
        if (to.isEmpty()) {
            Ui.toast("لا مستخدمين آخرين بعد");
            return;
        }
        String[] names = new String[to.size() + 1];
        names[0] = "كل المحاسبين";
        for (int i = 0; i < to.size(); i++) names[i + 1] = to.get(i).name;
        JComboBox<String> who = Ui.combo(names);
        JTextArea body = Ui.area(4);
        JPanel c = Ui.column();
        c.add(Ui.labeled("إلى", who));
        c.add(Ui.gap(6));
        c.add(Ui.labeled("نص الرسالة (حتى 500 حرف)", Ui.areaBox(body)));
        c.add(Ui.para("تظهر إشعارًا عند المحاسب وفي صفحته الرئيسية حتى يضغط «قرأتها»" + (SyncEngine.enabled(db) ? " (تصل لأجهزتهم مع المزامنة التالية)." : "."), 12.5f, Ui.muted, 460));
        java.util.List<Db.AdminMsg> sent = db.adminMsgsSent(8);
        if (!sent.isEmpty()) {
            c.add(Ui.gap(8));
            c.add(Ui.label("آخر ما أرسلته", 13, Ui.primaryInk, true));
            for (Db.AdminMsg m : sent) {
                c.add(Ui.row(Ui.label(Ui.cut((m.toUser == null ? "للكل" : "إلى " + m.toName) + " — " + Model.date(m.at) + ": " + m.body.replace('\n', ' '), 90), 13, Ui.ink, false),
                        Box.createHorizontalGlue(), Ui.ghost("سحب", e -> {
                            db.deleteAdminMsg(m.uuid);
                            Ui.toast("سُحبت الرسالة (تختفي عند من لم يقرأها)");
                            ((JComponent) e.getSource()).setEnabled(false);
                        })));
            }
        }
        c.setPreferredSize(new Dimension(520, c.getPreferredSize().height));
        if (!Ui.form(parent, "رسالة إلى المحاسبين", Ui.rtl(c), "إرسال")) return;
        try {
            db.sendAdminMsg(who.getSelectedIndex() == 0 ? null : to.get(who.getSelectedIndex() - 1).id, body.getText());
            Ui.toast("أُرسلت الرسالة ✓");
            SyncView.now(app);
        } catch (Exception e) {
            Ui.error(parent, e);
        }
    }

    /** يعرض رمز الاسترداد مرة واحدة ولا يُغلق قبل تأكيد حفظه. */
    static void showRecoveryCode(Component parent, String code, String intro) {
        JPanel c = Ui.column();
        c.add(Ui.para(intro, 14, Ui.ink, 380));
        c.add(Ui.gap(10));
        JTextField f = new JTextField(code);
        f.setEditable(false);
        f.setFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
        f.setHorizontalAlignment(JTextField.CENTER);
        f.setBackground(new Color(240, 247, 243));
        f.setBorder(new Ui.RoundBorder(Ui.primary, 10));
        f.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        c.add(f);
        c.add(Ui.gap(8));
        c.add(Ui.para("اكتبه على ورقة واحفظه في مكان آمن بعيدًا عن الجهاز. هو الطريقة الوحيدة لاستعادة دخول المدير إن نُسي الرمز، ولن يظهر مرة أخرى.", 13, Ui.red, 380));
        c.add(Ui.gap(6));
        c.add(Ui.flow(Ui.ghost("نسخ الرمز (لدقيقة)", e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
            Timer t = new Timer(60000, x -> {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(""), null);
                } catch (Exception ignored) {
                    // غير مهم
                }
            });
            t.setRepeats(false);
            t.start();
            Ui.toast("نُسخ الرمز لدقيقة واحدة. الصقه في مكان آمن الآن.");
        })));
        JCheckBox saved = Ui.check("كتبت الرمز وحفظته", false);
        c.add(saved);
        c.setPreferredSize(new Dimension(440, c.getPreferredSize().height));
        while (true) {
            Ui.form(parent, "رمز استرداد المدير", Ui.rtl(c), "تم");
            if (saved.isSelected()) return;
            Ui.toast("ضع علامة «كتبت الرمز وحفظته» أولًا");
        }
    }
}
