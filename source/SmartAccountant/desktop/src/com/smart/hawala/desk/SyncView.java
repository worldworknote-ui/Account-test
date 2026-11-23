package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import javax.swing.*;
import org.json.JSONObject;

/**
 * 10.0: «المزامنة بين الأجهزة» على الماك والويندوز (المحرك نفسه في SyncEngine): اختيار المجلد المشترك (داخل Google Drive أو
 * OneDrive أو Dropbox أو Syncthing)، وإنشاء الخزنة أو الانضمام إليها، والحالة والأجهزة والتعارضات. المزامنة التلقائية كل دقيقة.
 */
final class SyncView extends View {
    SyncView(App app) { super(app, "المزامنة بين الأجهزة"); }

    /** 10.2: الناقل المختار: «drive» أو «server» أو «folder». */
    static String transport(Db db) { return db.setting("sync_transport", "folder"); }

    static SyncFolder folder(Db db) { return folder(db, transport(db)); }

    static SyncFolder folder(Db db, String kind) {
        if ("drive".equals(kind)) return new DriveSyncFolder(new DeskDriveAuth(db));
        if ("server".equals(kind)) {
            String url = db.setting("sync_server", "");
            if (url.isEmpty()) return null;
            String v = db.setting("sync_server_vault", "");
            if (v.isEmpty()) v = SyncEngine.status(db).vaultId;
            return new HttpSyncFolder(url, v, db.setting("sync_server_key", ""));
        }
        String d = db.setting("sync_dir", "");
        return d.isEmpty() ? null : new FileSyncFolder(new File(d));
    }

    static String kindName(String k) {
        switch (k) {
            case "drive": return "Google Drive مباشرة";
            case "server": return "خادم المزامنة الخاص بك";
            default: return "مجلد مشترك";
        }
    }

    @Override void refresh() {
        clear();
        subtitle.setText("بياناتك واحدة على الهاتف والماك والويندوز — مشفّرة بالكامل (النسخة المدفوعة)");
        SyncEngine.Status st = SyncEngine.status(db);
        int w = textWidth();
        if (!License.current.sync()) {
            Ui.Card c = Ui.card();
            c.add(Ui.heading("ميزة في النسخة المدفوعة"));
            c.add(Ui.para("المزامنة تجعل بياناتك واحدة على كل أجهزتك. كل جهاز يسجّل ويعدّل، وتصل التغييرات للبقية مشفّرة عبر مجلد سحابي تختاره. "
                    + "بياناتك على هذا الجهاز تعمل كما هي دون مزامنة.", 14, Ui.ink, w));
            add(c);
            action(Ui.primary("الترخيص", e -> app.go("license")));
            if (!st.enabled) {
                done();
                return;
            }
        }
        if (!st.enabled) intro(st, w);
        else status(st, w);
        done();
    }

    void intro(SyncEngine.Status st, int w) {
        if (!st.leftReason.isEmpty()) add(Ui.para("المزامنة متوقفة على هذا الجهاز: " + st.leftReason + ". انضم من جديد للمتابعة.", 14, Ui.red, w));
        Ui.Card c = Ui.card();
        c.add(Ui.heading("كيف تعمل؟"));
        c.add(Ui.para("• بياناتك تبقى على كل جهاز وتعمل دون إنترنت.\n• كل جهاز يرسل تغييراته كملفات مشفّرة ويستلم تغييرات الأجهزة الأخرى. تنتقل الحركات الجديدة فقط.\n"
                + "• الناقل (Google أو خادمك أو المجلد) لا يرى شيئًا: لا أسماء ولا مبالغ.\n"
                + "• الإلغاء نفسه من جهازين يُحسب مرة واحدة، وتعديل الحركة نفسها من جهازين يُبقي الأسبق ويعرض الآخر للمراجعة.", 14, Ui.ink, w));
        add(c);
        space(10);
        if (!app.can(Auth.Perm.SYNC)) {
            add(Ui.para("إعداد المزامنة لمن يملك صلاحية «إدارة المزامنة».", 13.5f, Ui.muted, w));
            return;
        }
        add(Ui.row(Ui.ghost("شرح مفصّل لكل طريقة…", e -> help(transport(db))), Box.createHorizontalGlue()));
        space(6);
        add(Ui.heading("اختر طريقة المزامنة (الطريقة نفسها على كل الأجهزة)"));
        String kind = transport(db);
        ButtonGroup bg = new ButtonGroup();
        String[][] opts = {{"drive", "Google Drive مباشرة — الأسهل: تسجّل بحساب Google نفسه على كل جهاز، وتصل التغييرات خلال ثوانٍ."},
                {"server", "خادم المزامنة الخاص بك — الأسرع: خادم تملكه (مرفق مع التطبيق)."},
                {"folder", "مجلد مشترك — داخل Google Drive أو OneDrive أو Dropbox أو Syncthing على هذا الجهاز."}};
        for (String[] o : opts) {
            JRadioButton rb = new JRadioButton(o[1], o[0].equals(kind));
            rb.setOpaque(false);
            rb.setFont(Ui.font(14, o[0].equals(kind)));
            rb.addActionListener(e -> {
                db.setSettingQuiet("sync_transport", o[0]);
                refresh();
            });
            bg.add(rb);
            add(Ui.row(rb, Box.createHorizontalGlue()));
        }
        space(10);
        if (!License.current.sync()) return;
        if ("drive".equals(kind)) {
            String[] cl = DeskDriveAuth.client(db);
            if (cl == null) {
                add(Ui.para("لربط Google Drive على الكمبيوتر يلزم «معرّف عميل Google للكمبيوتر» مرة واحدة (من مشروع Google Cloud الخاص بالتطبيق — انظر دليل الإصدار).", 13.5f, Ui.goldInk, w));
                add(Ui.row(Ui.ghost("لصق معرّف العميل…", e -> clientDialog()), Box.createHorizontalGlue()));
                return;
            }
            boolean ok = DeskDriveAuth.connected();
            String acct = db.setting("drive_account", "");
            add(Ui.row(Ui.primary(ok ? "✓ متصل" + (acct.isEmpty() ? "" : " بـ " + acct) + " — تغيير الحساب…" : "1) اختيار حساب Google وتسجيل الدخول…", e -> driveLogin(this::refresh)), Ui.ghost("تعديل معرّف العميل…", e -> clientDialog()), Box.createHorizontalGlue()));
            if (!ok) return;
        } else if ("server".equals(kind)) {
            String cur = db.setting("sync_server", ""), v0 = db.setting("sync_server_vault", "");
            Ui.Field url = Ui.field("عنوان الخادم أو رابط الانضمام");
            url.setText(cur.isEmpty() ? "" : v0.isEmpty() ? cur : HttpSyncFolder.link(cur, v0));
            url.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            JPasswordField key = Ui.password();
            key.setText(db.setting("sync_server_key", ""));
            add(Ui.labeled("عنوان الخادم (على الجهاز الرئيسي) أو رابط الانضمام (على بقية الأجهزة)", url));
            add(Ui.labeled("رمز الخادم (إن طلبه خادمك)", key));
            space(6);
            add(Ui.row(Ui.primary("1) حفظ وفحص الاتصال", e -> {
                String[] pv = HttpSyncFolder.parseLink(url.val());
                String k = new String(key.getPassword()).trim();
                try {
                    HttpSyncFolder.checkUrl(pv[0]);
                } catch (Exception ex) {
                    Ui.error(this, ex);
                    return;
                }
                Ui.busy(this, "جارٍ فحص الخادم…", p -> new HttpSyncFolder(pv[0], pv[1], k).ping(), msg -> {
                    db.setSettingQuiet("sync_server", pv[0]);
                    db.setSettingQuiet("sync_server_vault", pv[1] == null ? "" : pv[1]);
                    db.setSettingQuiet("sync_server_key", k);
                    Ui.toast(msg);
                    refresh();
                });
            }), Box.createHorizontalGlue()));
            if (cur.isEmpty()) return;
        } else {
            String dir = db.setting("sync_dir", "");
            add(Ui.row(Ui.primary("1) اختيار مجلد المزامنة…", e -> pick()), Box.createHorizontalGlue()));
            if (dir.isEmpty()) return;
            space(6);
            add(Ui.label("المجلد: " + dir, 13, Ui.muted, false));
        }
        space(10);
        boolean exists;
        try {
            exists = new SyncEngine(db, folder(db)).vaultExists();
        } catch (Exception e) {
            add(Ui.para("تعذر الاتصال: " + Ui.friendly(e), 14, Ui.red, w));
            return;
        }
        if (exists) {
            add(Ui.para("وُجدت خزنة مزامنة: انضم إليها بكلمة سرها (تُستبدل بيانات هذا الجهاز ببيانات المزامنة؛ احفظ نسخة احتياطية أولًا إن كان فيه ما تحتاجه).", 14, Ui.ink, w));
            add(Ui.row(Ui.primary("2) الانضمام إلى المزامنة…", e -> join()), Box.createHorizontalGlue()));
        } else {
            add(Ui.para("لا خزنة بعد: أنشئ المزامنة من الجهاز الذي فيه بياناتك الكاملة (مرة واحدة)، ثم انضم من الأجهزة الأخرى.", 14, Ui.ink, w));
            add(Ui.row(Ui.primary("2) إنشاء المزامنة من بيانات هذا الجهاز…", e -> create()), Box.createHorizontalGlue()));
        }
    }

    void clientDialog() {
        Ui.Field id = Ui.field("…apps.googleusercontent.com");
        id.setText(db.setting("drive_client_id", ""));
        JPasswordField sec = Ui.password();
        sec.setText(db.setting("drive_client_secret", ""));
        JPanel c = Ui.column();
        c.add(Ui.labeled("معرّف العميل (Client ID)", id));
        c.add(Ui.labeled("سر العميل (Client secret)", sec));
        c.add(Ui.para("من Google Cloud ← APIs & Services ← Credentials ← عميل OAuth من نوع Desktop app.", 12.5f, Ui.muted, 420));
        if (!Ui.form(this, "عميل Google للكمبيوتر", Ui.rtl(c), "حفظ")) return;
        db.setSettingQuiet("drive_client_id", id.val().trim());
        db.setSettingQuiet("drive_client_secret", new String(sec.getPassword()).trim());
        refresh();
    }

    void driveLogin(Runnable after) {
        Ui.toast("سيُفتح المتصفح: اختر حساب Google ووافق، ثم ارجع إلى البرنامج.");
        Ui.busy(this, "بانتظار الدخول في المتصفح…", p -> DeskDriveAuth.login(db, DeskDriveAuth.REFRESH, db.setting("drive_account", "")), email -> {
            db.setSettingQuiet("sync_drive_ok", "1");
            if (!email.isEmpty()) db.setSettingQuiet("drive_account", email);
            Ui.toast("تم الاتصال بـ " + (email.isEmpty() ? "Google Drive" : email) + " ✓");
            after.run();
        });
    }

    void pick() {
        JFileChooser ch = new JFileChooser(db.setting("sync_dir", System.getProperty("user.home")));
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setDialogTitle("اختر المجلد المشترك للمزامنة");
        ch.applyComponentOrientation(Ui.RTL);
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = ch.getSelectedFile();
        if (f == null || !f.isDirectory() || !f.canWrite()) {
            Ui.info(this, "المزامنة", "لا يمكن الكتابة في هذا المجلد. اختر مجلدًا آخر.");
            return;
        }
        db.setSettingQuiet("sync_dir", f.getAbsolutePath());
        refresh();
    }

    void create() {
        Ui.Field name = Ui.field("اسم هذا الجهاز (مثل: ماك المحل)");
        JPasswordField p1 = Ui.password(), p2 = Ui.password();
        JPanel c = Ui.column();
        c.add(Ui.labeled("اسم الجهاز", name));
        c.add(Ui.labeled("كلمة سر المزامنة (8 محارف على الأقل)", p1));
        c.add(Ui.labeled("أعد كتابتها", p2));
        c.add(Ui.para("تُطلب كلمة السر على كل جهاز ينضم. لا تُسترد إن نسيتها، إلا برمز الاسترداد الذي يظهر بعد الإنشاء.", 12.5f, Ui.muted, 420));
        if (!Ui.form(this, "إنشاء المزامنة", Ui.rtl(c), "إنشاء")) return;
        char[] a = p1.getPassword();
        if (a.length < 8 || !new String(a).equals(new String(p2.getPassword()))) {
            Ui.info(this, "إنشاء المزامنة", a.length < 8 ? "كلمة السر 8 محارف على الأقل" : "كلمتا السر غير متطابقتين");
            return;
        }
        Ui.busy(this, "جارٍ إنشاء الخزنة المشفّرة ورفع لقطة البيانات…", p -> new SyncEngine(db, folder(db)).create(a, name.val()), rc -> {
            if ("server".equals(transport(db))) db.setSettingQuiet("sync_server_vault", SyncEngine.status(db).vaultId);
            JTextField code = new JTextField(rc);
            code.setEditable(false);
            code.setFont(Ui.font(18, true));
            JPanel q = Ui.column();
            q.add(Ui.para("أُنشئت المزامنة ✓\n\nإن نُسيت كلمة السر فهذا الرمز وحده يفتح الخزنة. يظهر مرة واحدة فقط: انسخه واحفظه في مكان آمن.", 14, Ui.ink, 460));
            q.add(Ui.gap(8));
            q.add(code);
            Ui.form(this, "رمز الاسترداد — احفظه الآن", Ui.rtl(q), "حفظته");
            app.changed();
            refresh();
        });
    }

    void join() {
        Ui.Field name = Ui.field("اسم هذا الجهاز (مثل: ماك البيت)");
        JComboBox<String> how = Ui.combo(new String[]{"كلمة سر المزامنة", "رمز الاسترداد"});
        JPasswordField pw = Ui.password();
        JPanel c = Ui.column();
        c.add(Ui.labeled("اسم الجهاز", name));
        c.add(Ui.labeled("بماذا تفتح الخزنة", how));
        c.add(Ui.labeled("كلمة السر أو الرمز", pw));
        c.add(Ui.para("! تُستبدل بيانات هذا الجهاز ببيانات المزامنة.", 13, Ui.red, 420));
        if (!Ui.form(this, "الانضمام إلى المزامنة", Ui.rtl(c), "انضمام")) return;
        char[] s = pw.getPassword();
        boolean rc = how.getSelectedIndex() == 1;
        Ui.busy(this, "جارٍ فتح الخزنة وتنزيل البيانات…", p -> {
            new SyncEngine(db, folder(db)).join(s, rc, name.val());
            return true;
        }, ok -> {
            Ui.toast("انضم هذا الجهاز إلى المزامنة ✓");
            app.reloadAll();
            // 10.1: إن كان في بيانات المزامنة مستخدمون يدخل كل شخص بحسابه
            if (db.usersEnabled()) app.lock();
            else app.go("sync");
        });
    }

    void status(SyncEngine.Status st, int w) {
        String tr = transport(db);
        boolean admin = app.can(Auth.Perm.SYNC);
        // ——— تنبيهات ———
        if (SyncEngine.headerMissing(db)) {
            add(Ui.para("! ملفات المزامنة غير موجودة في المكان المختار (" + st.folder + "). إن نقلت المزامنة إلى هنا فاضغط «إصلاح»، أو اختر المكان الصحيح من «نقل المزامنة».", 14, Ui.red, w));
            add(Ui.row(Ui.primary("إصلاح: كتابة ملفات المزامنة هنا…", e -> repair()), Box.createHorizontalGlue()));
            space(8);
        }
        if (!st.digestAlert.isEmpty()) add(Ui.para("! " + st.digestAlert, 14, Ui.red, w));
        if (!st.movedTo.isEmpty()) movedNotice(st, w);
        else if (!st.lastError.isEmpty()) add(Ui.para("آخر خطأ: " + st.lastError, 14, Ui.red, w));
        if (st.paused) {
            add(Ui.row(Ui.label(" المزامنة التلقائية موقوفة مؤقتًا على هذا الجهاز؛ تغييراتك محفوظة وتُرسل عند الاستئناف.", 14, Ui.goldInk, true), Box.createHorizontalGlue(),
                    Ui.ghost("استئناف", e -> {
                        db.setSettingQuiet("sync_paused", "0");
                        refresh();
                    })));
            space(6);
        }
        for (JSONObject m : st.stale) {
            long days = (Clock.now() - m.optLong("at")) / 86_400_000L;
            boolean canRemove = admin && !m.optString("dev").equals(st.founderDev);
            JPanel r = Ui.row(Ui.label("! «" + m.optString("name") + "» لم يتزامن منذ " + days + " يومًا: ما لم يستلمه من الملفات لا يُحذف حتى يعود أو يُزال.", 13.5f, Ui.red, false),
                    Box.createHorizontalGlue());
            if (canRemove) r.add(Ui.danger("إزالة…", e -> removeDevice(m)));
            add(r);
            space(4);
        }
        action(Ui.primary("مزامنة الآن", e -> Ui.busy(this, "جارٍ المزامنة…", p -> {
            // 10.16: جولة تلقائية تعمل الآن؟ القفل العام يجعل هذه تنتظرها ثم تجري (لا جولتان معًا على الخزنة)
            return new SyncEngine(db, folder(db)).sync();
        }, r -> {
            Ui.toast(r.summary());
            if (r.received > 0) app.reloadAll();
            if (!afterSync(app)) app.go("sync");
        })));

        action(Ui.ghost("سجل الإشعارات", e -> NotifLog.open(this, db)));   // 10.18
        // 10.16: الحجم وعدد الملفات في مكان المزامنة (كان على الهاتف وحده)
        action(Ui.ghost("الحجم وعدد الملفات", e -> Ui.busy(this, "جارٍ قراءة مكان المزامنة…", p -> new SyncEngine(db, folder(db)).usage(), u -> Ui.info(this, "مكان المزامنة",
                u.total() + " ملفًا" + (u.bytes >= 0 ? " — " + SyncEngine.size(u.bytes) : "") + "\n\nبطاقات الأجهزة: " + u.members + "\nحزم التغييرات: " + u.packets
                        + "\nاللقطات: " + u.snapshots + (u.snapBytes >= 0 ? " (" + SyncEngine.size(u.snapBytes) + ")" : "") + "\nالصور: " + u.images
                        + (u.imageBytes >= 0 ? " (" + SyncEngine.size(u.imageBytes) + ")" : "") + (u.other > 0 ? "\nملفات أخرى: " + u.other : "")
                        + "\n\nالملفات القديمة التي استلمتها كل الأجهزة تُحذف تلقائيًا."))));

        // ——— هذا الجهاز ———
        Ui.Card c = Ui.card();
        c.add(Ui.row(Ui.heading("✓ المزامنة مفعّلة — " + st.deviceName + (st.founder ? " (الجهاز الرئيسي)" : "")), Box.createHorizontalGlue(),
                Ui.ghost("تغيير الاسم…", e -> renameDialog(st.deviceName))));
        if (!st.founder) {
            JPanel fr = Ui.row(Ui.label("الجهاز الرئيسي: " + st.founderName(), 13, Ui.muted, false), Box.createHorizontalGlue());
            if (admin) fr.add(Ui.ghost("ضاع الجهاز الرئيسي؟ استلم دوره…", e -> claimFounder(st)));
            c.add(fr);
        }
        c.add(Ui.gap(4));
        c.add(Ui.label("آخر مزامنة: " + (st.lastSync > 0 ? Model.date(st.lastSync) : "لم تتم بعد") + "   •   بانتظار الإرسال: "
                + (st.pending + st.unsent == 0 ? "لا شيء" : (st.pending + st.unsent) + ""), 13.5f, Ui.ink, false));
        c.add(Ui.label("الطريقة: " + kindName(tr) + " — " + st.folder, 13, Ui.muted, false));
        if ("server".equals(tr)) {
            String link = HttpSyncFolder.link(db.setting("sync_server", ""), st.vaultId);
            c.add(Ui.gap(4));
            c.add(Ui.row(Ui.label("رابط الانضمام: " + link, 12.5f, Ui.ink, false), Box.createHorizontalGlue(), Ui.ghost("نسخ", e -> {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(link), null);
                Ui.toast("نُسخ رابط الانضمام. أرسله لجهازك الآخر مع كلمة سر المزامنة.");
            })));
        }
        c.add(Ui.gap(6));
        c.add(Ui.flow(Ui.ghost("شرح مفصّل…", e -> help(tr))));
        add(c);
        space(12);

        // ——— الأجهزة ———
        Ui.Card dc0 = Ui.card();
        dc0.add(Ui.heading("الأجهزة (" + st.members.size() + ")"));
        if (st.members.isEmpty()) dc0.add(Ui.label("اضغط «مزامنة الآن» لتظهر الأجهزة", 13, Ui.muted, false));
        for (JSONObject m : st.members) {
            String dev = m.optString("dev");
            boolean me = dev.equals(st.device);
            JPanel r = Ui.row(Ui.label("• " + m.optString("name") + (me ? " (هذا الجهاز)" : "") + (dev.equals(st.founderDev) ? " — الرئيسي" : "") + " — " + m.optString("platform")
                    + " • الإصدار " + m.optString("app") + " • آخر ظهور " + Model.date(m.optLong("at")), 13.5f, me ? Ui.green : Ui.ink, me), Box.createHorizontalGlue());
            if (!me && admin) r.add(Ui.ghost("تسمية…", e -> renameOther(m)));   // 10.17: المدير يسمّي أي جهاز
            if (!me && admin && st.founder) r.add(Ui.ghost("اجعله الرئيسي…", e -> transferFounder(m)));
            if (!me && admin && !dev.equals(st.founderDev)) r.add(Ui.ghost("إزالة…", e -> removeDevice(m)));
            dc0.add(r);
            JSONObject pr = st.peers.get(dev);
            String line = me ? "أرسل " + st.mySeq + " حزمة" + (st.unsent > 0 ? " (" + st.unsent + " لم تُرفع بعد)" : " — كلها في المكان") : SyncEngine.peerText(pr, "");
            boolean late = pr != null && pr.optLong("have") < pr.optLong("announced");
            if (!line.isEmpty()) dc0.add(Ui.para("   " + line, 12.5f, late ? Ui.red : Ui.muted, w - 40));
        }
        if (!st.note.isEmpty()) dc0.add(Ui.para(st.note, 12.5f, Ui.goldInk, w - 40));
        add(dc0);
        space(12);

        // ——— التكرار والتعارضات ———
        List<SyncEngine.Dup> dups = SyncEngine.duplicates(db);
        if (!dups.isEmpty()) {
            add(Ui.heading("حركات قد تكون مكررة (" + dups.size() + ")"));
            add(Ui.para("حركتان بالمكتب والمبلغ والعملة والاسم نفسها سُجّلتا من جهازين قبل أن يتزامنا. قرّر لكل زوج؛ قرارك يصل إلى كل الأجهزة.", 13, Ui.muted, w));
            java.util.Map<Long, String> names = db.officeNames();
            for (SyncEngine.Dup x : dups) {
                Ui.Card dc = Ui.card();
                dc.add(Ui.label(Model.nz(names.get(x.a.officeId)) + " — " + (Model.TYPE_DEBIT.equals(x.a.type) ? "لنا " : "علينا ") + Ui.ltr(Model.fmt(x.a.amount, db.decimals(x.a.currency)) + " " + x.a.currency), 15, Ui.primaryInk, true));
                dc.add(Ui.para(dupLine("الأولى", x.a) + "\n" + dupLine("الثانية", x.b), 13, Ui.ink, w - 40));
                dc.add(Ui.flow(Ui.ghost("أبقِ الاثنتين", e -> {
                    SyncEngine.keepBoth(db, x);
                    Ui.toast("أُبقيت الحركتان");
                    refresh();
                }), Ui.danger("ألغِ الأولى", e -> dropDup(x, x.a)), Ui.danger("ألغِ الثانية", e -> dropDup(x, x.b))));
                add(dc);
                space(8);
            }
            space(8);
        }
        List<String[]> cs = SyncEngine.conflicts(db);
        if (!cs.isEmpty()) {
            Ui.Card cc = Ui.card();
            cc.add(Ui.row(Ui.heading("للمراجعة (" + cs.size() + ")"), Box.createHorizontalGlue(), cs.size() > 1 ? Ui.ghost("تمت للكل", e -> {
                if (!Ui.confirm(this, "تمت للكل", "تُعلَّم كل التنبيهات كمُراجَعة وتختفي من هنا ومن الصفحة الرئيسية.", "تمت")) return;
                SyncEngine.resolveAll(db);
                refresh();
            }) : Box.createHorizontalStrut(1)));
            for (String[] x : cs) {
                cc.add(Ui.row(Ui.label(Ui.cut(kind(x[2]) + ": " + x[5], 170) + " — " + Model.date(Long.parseLong(x[1])), 13.5f, SyncStore.isNotice(x[2]) ? Ui.ink : Ui.red, false), Box.createHorizontalGlue(),
                        Ui.ghost("تمت المراجعة", e -> {
                            SyncEngine.resolveConflict(db, Long.parseLong(x[0]));
                            refresh();
                        })));
            }
            add(cc);
            space(12);
        }

        // ——— التلقائي ———
        Ui.Card ac0 = Ui.card();
        ac0.add(Ui.heading("التلقائي على هذا الجهاز"));
        JCheckBox pause = Ui.check("إيقاف المزامنة التلقائية مؤقتًا (يعمل زر «مزامنة الآن» دائمًا)", st.paused);
        pause.addActionListener(e -> {
            db.setSettingQuiet("sync_paused", pause.isSelected() ? "1" : "0");
            refresh();
        });
        ac0.add(Ui.row(pause, Box.createHorizontalGlue()));
        boolean on = "1".equals(db.setting("sync_after_change", "1"));
        JCheckBox ac = Ui.check("مزامنة بعد كل حركة (خلال ثوانٍ من الحفظ)", on);
        ac.addActionListener(e -> db.setSettingQuiet("sync_after_change", ac.isSelected() ? "1" : "0"));
        ac0.add(Ui.row(ac, Box.createHorizontalGlue()));
        int[] secs = {30, 90, 300, 900};
        String[] names = new String[secs.length];
        int sel = 1, every = SyncEngine.everySeconds(db);
        for (int i = 0; i < secs.length; i++) {
            names[i] = everyName(secs[i]);
            if (secs[i] == every) sel = i;
        }
        JComboBox<String> ev = Ui.combo(names);
        ev.setSelectedIndex(sel);
        ev.setMaximumSize(new Dimension(220, 40));
        ev.addActionListener(e -> db.setSettingQuiet("sync_every", "" + secs[ev.getSelectedIndex()]));
        ac0.add(Ui.row(Ui.label("المزامنة التلقائية كل:", 13.5f, Ui.ink, false), ev, Box.createHorizontalGlue()));
        add(ac0);
        space(12);

        // ——— مكان المزامنة ———
        Ui.Card pc = Ui.card();
        pc.add(Ui.heading("مكان المزامنة وحجمه"));
        SyncFolder fo = folder(db);
        boolean canClean = fo != null && fo.canDelete();
        pc.add(Ui.para(canClean ? "التنظيف التلقائي: يحذف كل جهاز حزمه القديمة بعد أن تصل إلى كل الأجهزة، ولقطاته القديمة، والصور التي لم يعد يشير إليها شيء. لا يمس بياناتك."
                : "هذه الطريقة لا تسمح بحذف الملفات.", 13, Ui.muted, w - 40));
        if (!st.lastClean.isEmpty()) pc.add(Ui.label("آخر تنظيف: " + st.lastClean, 12.5f, Ui.ink, false));
        JLabel usage = Ui.label("", 13, Ui.ink, false);
        pc.add(Ui.gap(4));
        JPanel pr = Ui.flow(Ui.ghost("عرض الحجم", e -> Ui.busy(this, "جارٍ قراءة مكان المزامنة…", p -> new SyncEngine(db, folder(db)).usage(), u -> {
            usage.setText(u.total() + " ملفًا" + (u.bytes >= 0 ? " • " + SyncEngine.size(u.bytes) : "") + "  —  حزم " + u.packets + " • لقطات " + u.snapshots
                    + " • صور " + u.images + (u.imageBytes >= 0 ? " (" + SyncEngine.size(u.imageBytes) + ")" : "") + " • أجهزة " + u.members);
            revalidate();
        })));
        if (canClean && admin) pr.add(Ui.ghost("تنظيف الآن", e -> Ui.busy(this, "جارٍ التنظيف…", p -> new SyncEngine(db, folder(db)).clean(true), r -> {
            Ui.toast(r.isEmpty() ? "لا شيء قديم يُحذف الآن" : r);
            refresh();
        })));
        pc.add(pr);
        pc.add(usage);
        if (admin) {
            pc.add(Ui.gap(8));
            JPanel tp = Ui.flow(Ui.ghost("نقل المزامنة إلى طريقة أخرى…", e -> changeTransport()));
            if ("server".equals(tr)) tp.add(Ui.ghost("تعديل عنوان الخادم أو رمزه…", e -> editServer()));
            else if ("drive".equals(tr)) {
                // 10.14: الحساب المتصل ظاهر، وتغييره نقلٌ منظّم أو بداية جديدة
                String acct = db.setting("drive_account", ""), old = db.setting("drive_account_old", "");
                pc.add(Ui.row(Ui.label("حساب Google: " + (acct.isEmpty() ? "غير معروف (سجّل الدخول من جديد ليظهر)" : acct), 13.5f, Ui.ink, true), Box.createHorizontalGlue(),
                        Ui.ghost("تغيير الحساب…", e -> changeAccount(acct))));
                if (!old.isEmpty() || DeskDriveAuth.has(DeskDriveAuth.OLD))
                    pc.add(Ui.row(Ui.label("الحساب القديم: " + (old.isEmpty() ? "—" : old) + " (نُقلت المزامنة منه)", 13, Ui.muted, false), Box.createHorizontalGlue(),
                            Ui.danger("حذف الخزنة منه…", e -> purgeOld(old))));
                tp.add(Ui.ghost("إعادة تسجيل الدخول بالحساب نفسه…", e -> driveLogin(() -> {
                    now(app);
                    refresh();
                })));
                tp.add(Ui.ghost("تعديل معرّف العميل…", e -> clientDialog()));
            } else tp.add(Ui.ghost("المجلد نفسه في مكان جديد…", e -> repointFolder()));
            pc.add(tp);
        }
        add(pc);
        space(12);

        // ——— الأمان ———
        Ui.Card sc = Ui.card();
        sc.add(Ui.heading("الأمان"));
        if (!admin) sc.add(Ui.para("كلمة السر ورمز الاسترداد وإزالة الأجهزة وإيقاف المزامنة لمن يملك صلاحية «إدارة المزامنة».", 13, Ui.muted, w - 40));
        else sc.add(Ui.flow(Ui.ghost("تغيير كلمة سر المزامنة…", e -> changePw()), Ui.ghost("رمز استرداد جديد…", e -> newRecovery()),
                Ui.ghost("نسيت كلمة السر؟ تعيين كلمة جديدة…", e -> resetPw()), Ui.danger("حذف ملفات المزامنة من هذا المكان…", e -> wipe()),
                Ui.danger("إيقاف المزامنة على هذا الجهاز", e -> {
                    if (!Ui.confirm(this, "إيقاف المزامنة", "يتوقف هذا الجهاز عن الإرسال والاستلام وتبقى بياناته. للعودة: «الانضمام» (تُستبدل بياناته ببيانات المزامنة).", "إيقاف")) return;
                    new SyncEngine(db, folder(db)).leave();
                    refresh();
                })));
        sc.add(Ui.gap(6));
        sc.add(Ui.label("تشفير AES-256-GCM بمفتاح من كلمة سر المزامنة • الحزم مسلسلة ومرتبطة ببصمات (أي حذف أو تعديل يُكتشف).", 12, Ui.muted, false));
        add(sc);
        space(12);

        java.util.List<JSONObject> hist = SyncEngine.history(db);
        if (!hist.isEmpty()) {
            Ui.Card hc = Ui.card();
            hc.add(Ui.heading("آخر الجولات"));
            int n = 0;
            for (JSONObject x : hist) {
                if (n++ >= 6) break;
                hc.add(Ui.para("• " + Model.date(x.optLong("at")) + " — " + x.optString("t") + (x.has("e") ? " — " + x.optString("e") : ""), 12.5f, x.has("e") ? Ui.red : Ui.ink, w - 40));
            }
            add(hc);
        }
    }

    static String everyName(int secs) {
        if (secs < 60) return secs + " ثانية";
        if (secs == 90) return "دقيقة ونصف";
        return (secs / 60) + " دقائق";
    }

    void renameOther(JSONObject m) {
        Ui.Field n = Ui.field("اسم الجهاز");
        n.setText(m.optString("name"));
        JPanel c = Ui.column();
        c.add(Ui.labeled("الاسم الجديد للجهاز «" + m.optString("name") + "»", n));
        if (!Ui.form(this, "إعادة تسمية جهاز", Ui.rtl(c), "حفظ")) return;
        Ui.busy(this, "جارٍ الحفظ…", p -> {
            new SyncEngine(db, folder(db)).renameDevice(m.optString("dev"), n.val());
            return true;
        }, ok -> {
            Ui.toast("غُيّر اسم الجهاز ✓ (يظهر على الأجهزة الأخرى بعد مزامنتها)");
            refresh();
        });
    }

    void renameDialog(String current) {
        Ui.Field n = Ui.field("اسم هذا الجهاز");
        n.setText(current);
        JPanel c = Ui.column();
        c.add(Ui.labeled("اسم هذا الجهاز", n));
        if (!Ui.form(this, "اسم الجهاز", Ui.rtl(c), "حفظ")) return;
        Ui.busy(this, "جارٍ الحفظ…", p -> {
            new SyncEngine(db, folder(db)).rename(n.val());
            return true;
        }, ok -> {
            Ui.toast("غُيّر اسم الجهاز ✓");
            refresh();
        });
    }

    void removeDevice(JSONObject m) {
        if (!app.require(Auth.Perm.SYNC)) return;
        if (!Ui.confirm(this, "إزالة «" + m.optString("name") + "» من المزامنة",
                SyncHelp.REMOVE_TEXT, "إزالة")) return;
        Ui.busy(this, "جارٍ إزالة الجهاز…", p -> new SyncEngine(db, folder(db)).removeDevice(m.optString("dev")), n -> {
            Ui.toast("أُزيل الجهاز ✓ تصل حزمه حتى رقم " + n + " إلى كل الأجهزة ثم تتوقف");
            refresh();
        });
    }

    /** 10.5 (AUDIT-002): نقل دور الجهاز الرئيسي من هذا الجهاز إلى جهاز آخر. */
    void transferFounder(JSONObject m) {
        if (!Ui.confirm(this, "جعل «" + m.optString("name") + "» الجهاز الرئيسي",
                "الجهاز الرئيسي هو الذي يحذف ملفات الأجهزة المُزالة والصور القديمة من مكان المزامنة، ومنه يُفعَّل نظام المستخدمين أو يُوقف.\n"
                        + "• يجب أن يكون «" + m.optString("name") + "» قد زامن قريبًا واستلم كل ما أرسله هذا الجهاز.\n"
                        + "• يصبح هذا الجهاز جهازًا عاديًا فورًا، ويعرف الجهاز الآخر دوره في جولته التالية.\n• لا تتغير أي بيانات محاسبية ولا رموز المستخدمين.",
                "نقل الدور")) return;
        Ui.busy(this, "جارٍ نقل الدور…", p -> {
            SyncEngine e = new SyncEngine(db, folder(db));
            e.sync();
            e.transferFounder(m.optString("dev"));
            return true;
        }, ok -> {
            Ui.toast("نُقل دور الجهاز الرئيسي إلى «" + m.optString("name") + "» ✓");
            refresh();
        });
    }

    /** 10.5 (AUDIT-002): استلام دور الجهاز الرئيسي بعد ضياعه: مدير + رمز الاسترداد + غياب الرئيسي. */
    void claimFounder(SyncEngine.Status st) {
        String block;
        try {
            block = new SyncEngine(db, folder(db)).founderClaimBlock(st.founderDev);
        } catch (Exception ex) {
            block = ex.getMessage();
        }
        if (block != null) {
            Ui.message(this, "استلام دور الجهاز الرئيسي", block, JOptionPane.WARNING_MESSAGE);
            return;
        }
        JPasswordField rc = Ui.password();
        JPanel c = Ui.column();
        c.add(Ui.para("استعمله فقط إن ضاع الجهاز الرئيسي «" + st.founderName() + "» أو تعطل. يلزم رمز استرداد المزامنة (الورقة التي حفظتها عند الإنشاء)؛ "
                + "كلمة السر ورابط الانضمام لا يكفيان. إن عاد الجهاز القديم يصبح جهازًا عاديًا تلقائيًا.", 13.5f, Ui.ink, 460));
        c.add(Ui.labeled("رمز الاسترداد", rc));
        if (!Ui.form(this, "استلام دور الجهاز الرئيسي", Ui.rtl(c), "استلام الدور")) return;
        Ui.busy(this, "جارٍ استلام الدور…", p -> {
            new SyncEngine(db, folder(db)).claimFounder(rc.getPassword());
            return true;
        }, ok -> {
            Ui.toast("أصبح هذا الجهاز هو الجهاز الرئيسي ✓");
            refresh();
        });
    }

    void newRecovery() {
        JPasswordField pw = Ui.password();
        JPanel c = Ui.column();
        c.add(Ui.para("يتوقف رمز الاسترداد القديم فورًا، ويظهر رمز جديد مرة واحدة. احفظه في مكان آمن.", 13.5f, Ui.ink, 420));
        c.add(Ui.labeled("كلمة سر المزامنة الحالية", pw));
        if (!Ui.form(this, "رمز استرداد جديد", Ui.rtl(c), "إصدار")) return;
        Ui.busy(this, "جارٍ إصدار الرمز…", p -> new SyncEngine(db, folder(db)).newRecoveryCode(pw.getPassword()), rc -> {
            JTextField code = new JTextField(rc);
            code.setEditable(false);
            code.setFont(Ui.font(18, true));
            JPanel q = Ui.column();
            q.add(Ui.para("الرمز الجديد (يظهر مرة واحدة). القديم لم يعد يعمل.", 14, Ui.ink, 460));
            q.add(Ui.gap(8));
            q.add(code);
            Ui.form(this, "رمز الاسترداد الجديد — احفظه الآن", Ui.rtl(q), "حفظته");
        });
    }

    void editServer() {
        Ui.Field url = Ui.field("https://عنوان-خادمك");
        url.setText(db.setting("sync_server", ""));
        url.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        JPasswordField key = Ui.password();
        key.setText(db.setting("sync_server_key", ""));
        JPanel c = Ui.column();
        c.add(Ui.labeled("عنوان الخادم", url));
        c.add(Ui.labeled("رمز الخادم (إن وُجد)", key));
        c.add(Ui.para("يُفحص الاتصال قبل الحفظ، والخزنة نفسها تبقى. للانتقال إلى خادم آخر استعمل «نقل المزامنة».", 12.5f, Ui.muted, 420));
        if (!Ui.form(this, "خادم المزامنة", Ui.rtl(c), "فحص وحفظ")) return;
        String base = HttpSyncFolder.parseLink(url.val())[0], k = new String(key.getPassword()).trim(), vault = SyncEngine.status(db).vaultId;
        try {
            HttpSyncFolder.checkUrl(base);
        } catch (Exception e) {
            Ui.error(this, e);
            return;
        }
        Ui.busy(this, "جارٍ فحص الخادم…", p -> {
            HttpSyncFolder h = new HttpSyncFolder(base, vault, k);
            h.ping();
            return new SyncEngine(db, h).vaultExists();
        }, has -> {
            if (!has) {
                Ui.info(this, "خادم المزامنة", "الخادم يعمل لكن خزنتك ليست عليه. للانتقال إليه استعمل «نقل المزامنة إلى طريقة أخرى ← خادم المزامنة».");
                return;
            }
            db.setSettingQuiet("sync_server", base);
            db.setSettingQuiet("sync_server_key", k);
            Ui.toast("حُفظت إعدادات الخادم ✓");
            now(app);
            refresh();
        });
    }

    /** المجلد نفسه صار في مسار آخر (نُقل أو تغيّر اسم المستخدم): يُختار دون نسخ، ويُفحص أنه يحوي الخزنة نفسها. */
    void repointFolder() {
        JFileChooser ch = new JFileChooser(db.setting("sync_dir", System.getProperty("user.home")));
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setDialogTitle("اختر مجلد المزامنة نفسه في مكانه الجديد");
        ch.applyComponentOrientation(Ui.RTL);
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || ch.getSelectedFile() == null) return;
        File f = ch.getSelectedFile();
        try {
            FileSyncFolder ff = new FileSyncFolder(f);
            byte[] h = ff.read(SyncEngine.HEADER);
            if (h == null || !new String(h, java.nio.charset.StandardCharsets.UTF_8).contains(SyncEngine.status(db).vaultId)) {
                Ui.info(this, "المزامنة", "هذا المجلد لا يحوي خزنة المزامنة نفسها. لنقل المزامنة إلى مجلد جديد استعمل «نقل المزامنة إلى طريقة أخرى».");
                return;
            }
        } catch (Exception e) {
            Ui.error(this, e);
            return;
        }
        db.setSettingQuiet("sync_dir", f.getAbsolutePath());
        Ui.toast("اختير المجلد ✓");
        now(app);
        refresh();
    }

    String dupLine(String which, Model.Tx t) {
        String dev = SyncEngine.deviceOf(db, t.id);
        return "• " + which + ": " + Model.date(t.createdAt) + (t.person.isEmpty() ? "" : " • " + t.person) + (t.description.isEmpty() ? "" : " • " + t.description)
                + (t.userName.isEmpty() ? "" : " • سجّلها " + t.userName) + (dev.isEmpty() ? "" : " • من «" + dev + "»") + (t.isPending() ? " • بانتظار الاعتماد" : "");
    }

    void dropDup(SyncEngine.Dup x, Model.Tx t) {
        if (!app.require(t.isPending() ? Auth.Perm.APPROVE : Auth.Perm.TX_CANCEL)) return;
        if (!Ui.confirm(this, "إلغاء الحركة المكررة", dupLine("الحركة", t) + "\n\n" + (t.isPending() ? "تُرفض (لم تدخل الأرصدة)." : "تُلغى بقيد عكسي ظاهر في السجل، وتبقى الأخرى."), "إلغاء هذه")) return;
        try {
            SyncEngine.dropDuplicate(db, x, t);
            app.changed();
            Ui.toast("أُلغيت الحركة المكررة");
        } catch (Exception e) {
            Ui.error(this, e);
        }
        refresh();
    }

    /** 10.2: نقل الخزنة إلى طريقة أخرى (تُنسخ الملفات المشفّرة كما هي؛ كرّرها على كل جهاز). */
    void changeTransport() {
        if (!app.require(Auth.Perm.SYNC)) return;
        String[] keys = {"drive", "server", "folder"};
        String[] names = {kindName("drive"), kindName("server"), kindName("folder")};
        JComboBox<String> to = Ui.combo(names);
        JPanel c = Ui.column();
        c.add(Ui.labeled("الطريقة الجديدة", to));
        c.add(Ui.para("تُنسخ ملفات الخزنة المشفّرة إلى الطريقة الجديدة ثم يستخدمها هذا الجهاز. افعل الشيء نفسه على كل جهاز (يكمل كل جهاز ما ينقص).", 12.5f, Ui.muted, 420));
        if (!Ui.form(this, "نقل المزامنة", Ui.rtl(c), "متابعة")) return;
        String k = keys[to.getSelectedIndex()];
        saved = new String[TKEYS.length];
        for (int i = 0; i < TKEYS.length; i++) saved[i] = db.setting(TKEYS[i], "");
        SyncFolder src = folder(db);
        if (src == null) return;
        if ("drive".equals(k)) {
            if (DeskDriveAuth.client(db) == null) {
                clientDialog();
                if (DeskDriveAuth.client(db) == null) return;
            }
            if (!DeskDriveAuth.connected()) {
                driveLogin(() -> move(k, src));
                return;
            }
        } else if ("server".equals(k)) {
            Ui.Field url = Ui.field("https://عنوان-خادمك");
            url.setText(db.setting("sync_server", ""));
            JPasswordField key = Ui.password();
            key.setText(db.setting("sync_server_key", ""));
            JPanel q = Ui.column();
            q.add(Ui.labeled("عنوان الخادم", url));
            q.add(Ui.labeled("رمز الخادم (إن وُجد)", key));
            if (!Ui.form(this, "خادم المزامنة", Ui.rtl(q), "متابعة")) return;
            String base = HttpSyncFolder.parseLink(url.val())[0];
            try {
                HttpSyncFolder.checkUrl(base);
            } catch (Exception e) {
                Ui.error(this, e);
                return;
            }
            db.setSettingQuiet("sync_server", base);
            db.setSettingQuiet("sync_server_key", new String(key.getPassword()).trim());
            db.setSettingQuiet("sync_server_vault", SyncEngine.status(db).vaultId);
        } else {
            JFileChooser ch = new JFileChooser(db.setting("sync_dir", System.getProperty("user.home")));
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            ch.applyComponentOrientation(Ui.RTL);
            if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || ch.getSelectedFile() == null) return;
            db.setSettingQuiet("sync_dir", ch.getSelectedFile().getAbsolutePath());
        }
        move(k, src);
    }

    /** 10.3: إعدادات الناقل قبل النقل (تُعاد إن فشل النقل). */
    static final String[] TKEYS = {"sync_dir", "sync_server", "sync_server_key", "sync_server_vault", "sync_transport"};
    String[] saved;

    void move(String k, SyncFolder src) {
        SyncFolder dst = folder(db, k);
        if (dst == null) return;
        String[] keep = saved;
        Ui.busy(this, "جارٍ نقل ملفات الخزنة المشفّرة…", p -> {
            try {
                return new SyncEngine(db, src).moveTo(dst);
            } catch (Exception e) {
                if (keep != null) for (int i = 0; i < TKEYS.length; i++) db.setSettingQuiet(TKEYS[i], keep[i]);
                throw new IllegalStateException("لم يكتمل النقل، وبقيت المزامنة على مكانها السابق: " + Ui.friendly(e));
            }
        }, n -> {
            db.setSettingQuiet("sync_transport", k);
            Ui.toast("نُقلت المزامنة ✓ (" + n + " ملفًا). افعل الشيء نفسه على أجهزتك الأخرى.");
            now(app);
            refresh();
        });
    }

    void repair() {
        if (!app.require(Auth.Perm.SYNC)) return;
        boolean pw = SyncEngine.repairNeedsPassword(db);
        JPanel c = Ui.column();
        c.add(Ui.para("يكتب هذا الجهاز ملفات المزامنة المشفّرة في المكان المختار الآن: رأس الخزنة ولقطة كاملة وحزمه. بياناتك لا تتغير. "
                + "بعدها اختر المكان نفسه على الأجهزة الأخرى.", 13.5f, Ui.ink, 440));
        JPasswordField p = Ui.password();
        if (pw) {
            c.add(Ui.labeled("كلمة سر المزامنة (8 محارف على الأقل)", p));
            c.add(Ui.para("يُكتب رأس جديد بكلمة السر هذه، ويظهر رمز استرداد جديد.", 12, Ui.muted, 440));
        }
        if (!Ui.form(this, "إصلاح مكان المزامنة", Ui.rtl(c), "إصلاح")) return;
        char[] s = pw ? p.getPassword() : null;
        Ui.busy(this, "جارٍ كتابة ملفات المزامنة…", x -> new SyncEngine(db, folder(db)).repair(s), rc -> {
            if (!rc.isEmpty()) Ui.info(this, "رمز الاسترداد الجديد — احفظه", rc);
            Ui.toast("أُصلح مكان المزامنة ✓");
            now(app);
            refresh();
        });
    }

    void help(String kind) {
        String[] ks = {"drive", "server", "folder"};
        StringBuilder b = new StringBuilder();
        b.append("قبل أي طريقة:\n");
        for (String x : SyncHelp.BASICS) b.append("• ").append(x).append("\n");
        for (String k : ks) {
            SyncHelp.Guide g = "drive".equals(k) ? SyncHelp.drive(false) : "server".equals(k) ? SyncHelp.server() : SyncHelp.folder(false);
            b.append("\n———————— ").append(g.title).append(k.equals(kind) ? "  (الطريقة الحالية)" : "").append(" ————————\n").append(g.intro).append("\n\nالخطوات:\n");
            for (int i = 0; i < g.steps.length; i++) b.append(i + 1).append(") ").append(g.steps[i]).append("\n");
            b.append("\nإن واجهتك مشكلة:\n");
            for (String x : g.notes) b.append("• ").append(x).append("\n");
        }
        JTextArea a = Ui.area(14);
        a.setText(b.toString());
        a.setEditable(false);
        a.setCaretPosition(0);
        a.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        JScrollPane sp = Ui.areaBox(a);
        sp.setPreferredSize(new Dimension(640, 520));
        Ui.form(this, "شرح تفعيل المزامنة", sp, "إغلاق");
    }

    static String kind(String k) { return SyncStore.kindName(k); }

    void changePw() {
        JPasswordField o = Ui.password(), n1 = Ui.password(), n2 = Ui.password();
        JPanel c = Ui.column();
        c.add(Ui.labeled("الحالية", o));
        c.add(Ui.labeled("الجديدة (8 محارف على الأقل)", n1));
        c.add(Ui.labeled("أعد كتابة الجديدة", n2));
        if (!Ui.form(this, "تغيير كلمة سر المزامنة", Ui.rtl(c), "حفظ")) return;
        if (!new String(n1.getPassword()).equals(new String(n2.getPassword()))) {
            Ui.info(this, "تغيير كلمة السر", "غير متطابقتين");
            return;
        }
        Ui.busy(this, "جارٍ إعادة تغليف المفتاح…", p -> {
            new SyncEngine(db, folder(db)).changePassword(o.getPassword(), n1.getPassword());
            return true;
        }, ok -> Ui.toast("غُيّرت كلمة سر المزامنة ✓"));
    }

    // ===================================================================================
    // 10.14: تغيير حساب Google، تعيين كلمة سر جديدة، حذف ملفات المزامنة
    // ===================================================================================

    static String driveEmail(String target) {
        String p = "Google Drive: ";
        return target != null && target.startsWith(p) ? target.substring(p.length()).trim() : "";
    }

    void changeAccount(String cur) {
        if (!app.require(Auth.Perm.SYNC)) return;
        JComboBox<String> how = Ui.combo(new String[]{"نقل المزامنة إلى الحساب الجديد (موصى به)", "بداية جديدة في الحساب الجديد"});
        JPanel c = Ui.column();
        c.add(Ui.labeled("الطريقة", how));
        c.add(Ui.para("• النقل: تُنسخ الخزنة المشفّرة كما هي إلى الحساب الجديد، ويُترك في القديم علامة «نُقلت»: كل جهاز آخر يتوقف عن الإرسال إليه ويطلب الدخول بالحساب الجديد (بياناته لا تتغير). "
                + "بعد أن تنتقل كل الأجهزة احذف الخزنة من الحساب القديم.\n"
                + "• البداية الجديدة: مزامنة جديدة من بيانات هذا الجهاز بكلمة سر ورمز جديدين؛ كل جهاز آخر ينضم إليها فتُستبدل بياناته. استعملها فقط إن كانت كل الأجهزة متزامنة الآن.", 12.5f, Ui.muted, 460));
        if (!Ui.form(this, "تغيير حساب Google", Ui.rtl(c), "اختيار الحساب الجديد…")) return;
        boolean fresh = how.getSelectedIndex() == 1;
        char[][] pw = {null};
        if (fresh) {
            JPasswordField p1 = Ui.password(), p2 = Ui.password();
            JPanel q = Ui.column();
            q.add(Ui.labeled("كلمة سر المزامنة الجديدة (8 محارف على الأقل)", p1));
            q.add(Ui.labeled("أعد كتابتها", p2));
            if (!Ui.form(this, "مزامنة جديدة", Ui.rtl(q), "متابعة")) return;
            if (p1.getPassword().length < 8 || !new String(p1.getPassword()).equals(new String(p2.getPassword()))) {
                Ui.info(this, "مزامنة جديدة", p1.getPassword().length < 8 ? "كلمة السر 8 محارف على الأقل" : "كلمتا السر غير متطابقتين");
                return;
            }
            pw[0] = p1.getPassword();
        }
        UsersView.confirmAdmin(this, db, fresh ? "بداية جديدة في حساب آخر" : "نقل المزامنة إلى حساب آخر", () -> {
            Ui.toast("سيُفتح المتصفح: اختر الحساب الجديد ووافق، ثم ارجع إلى البرنامج.");
            Ui.busy(this, "بانتظار الدخول بالحساب الجديد في المتصفح…", p -> DeskDriveAuth.login(db, DeskDriveAuth.NEW, ""), email -> {
                if (!cur.isEmpty() && email.equalsIgnoreCase(cur)) {
                    DeskDriveAuth.drop(DeskDriveAuth.NEW);
                    Ui.info(this, "تغيير الحساب", "هذا هو الحساب الحالي. اختر حسابًا آخر.");
                    return;
                }
                String to = email.isEmpty() ? "حساب Google آخر" : email;
                String dn = SyncEngine.status(db).deviceName;
                Ui.busy(this, fresh ? "جارٍ إنشاء المزامنة الجديدة…" : "جارٍ نقل الخزنة المشفّرة إلى " + to + "…", p -> {
                    SyncFolder src = new DriveSyncFolder(new DeskDriveAuth(db)), dst = new DriveSyncFolder(new DeskDriveAuth(db, DeskDriveAuth.NEW));
                    if (SyncEngine.sameFolder(src, dst)) throw new IllegalStateException("الحساب المختار هو الحساب المتصل حاليًا. اختر حسابًا آخر.");
                    if (fresh) {
                        new SyncEngine(db, src).retire("Google Drive: " + email, true, false);
                        new SyncEngine(db, src).leave();
                        return new SyncEngine(db, dst).create(pw[0], dn);
                    }
                    int n = new SyncEngine(db, src).moveTo(dst);
                    new SyncEngine(db, src).retire("Google Drive: " + email, false, false);
                    return "" + n;
                }, res -> {
                    try {
                        DeskDriveAuth.promote(DeskDriveAuth.REFRESH, DeskDriveAuth.OLD);
                        DeskDriveAuth.promote(DeskDriveAuth.NEW, DeskDriveAuth.REFRESH);
                    } catch (Exception ex) {
                        Ui.error(this, ex);
                    }
                    if (!cur.isEmpty()) db.setSettingQuiet("drive_account_old", cur);
                    db.setSettingQuiet("drive_account", email);
                    db.setSettingQuiet("sync_drive_ok", "1");
                    if (fresh) {
                        JTextField code = new JTextField(res);
                        code.setEditable(false);
                        code.setFont(Ui.font(18, true));
                        JPanel q = Ui.column();
                        q.add(Ui.para("أُنشئت المزامنة الجديدة في " + to + " ✓\n\nرمز الاسترداد الجديد يظهر مرة واحدة: احفظه في مكان آمن. على كل جهاز آخر: «انضمام إلى المزامنة الجديدة».", 14, Ui.ink, 460));
                        q.add(Ui.gap(8));
                        q.add(code);
                        Ui.form(this, "رمز الاسترداد — احفظه الآن", Ui.rtl(q), "حفظته");
                        app.changed();
                    } else Ui.info(this, "نُقلت المزامنة ✓", "نُسخ " + res + " ملفًا إلى " + to + ".\n\nعلى كل جهاز آخر: افتح «المزامنة» فيظهر أن المزامنة نُقلت، واضغط «الدخول بالحساب الجديد». "
                            + "بعد أن تنتقل كل الأجهزة احذف الخزنة من الحساب القديم من هنا.");
                    now(app);
                    refresh();
                });
            });
        });
    }

    void purgeOld(String old) {
        if (!Ui.confirm(this, "حذف الخزنة من الحساب القديم", "تُحذف ملفات المزامنة المشفّرة من " + (old.isEmpty() ? "الحساب القديم" : old)
                + " وتبقى فيه علامة «نُقلت» فقط. تأكد أولًا أن كل أجهزتك انتقلت إلى الحساب الجديد. بياناتك في الحساب الجديد وعلى الأجهزة لا تتأثر.", "حذف")) return;
        if (!DeskDriveAuth.has(DeskDriveAuth.OLD)) {
            Ui.info(this, "الحساب القديم", "دخول الحساب القديم غير محفوظ على هذا الجهاز. احذف الخزنة منه من الجهاز الذي نقل المزامنة، أو أزل صلاحية التطبيق من إعدادات حساب Google القديم.");
            db.setSettingQuiet("drive_account_old", "");
            refresh();
            return;
        }
        UsersView.confirmAdmin(this, db, "حذف الخزنة من الحساب القديم", () -> Ui.busy(this, "جارٍ الحذف من الحساب القديم…",
                p -> new SyncEngine(db, new DriveSyncFolder(new DeskDriveAuth(db, DeskDriveAuth.OLD))).purgeRetired(), n -> {
                    DeskDriveAuth.drop(DeskDriveAuth.OLD);
                    db.setSettingQuiet("drive_account_old", "");
                    Ui.toast("حُذف " + n + " ملفًا من الحساب القديم ✓");
                    refresh();
                }));
    }

    /** على الأجهزة الأخرى بعد نقل المزامنة أو بدايتها الجديدة في حساب آخر. */
    void movedNotice(SyncEngine.Status st, int w) {
        String email = driveEmail(st.movedTarget);
        boolean drive = "drive".equals(transport(db));
        boolean admin = app.can(Auth.Perm.SYNC);
        if (st.movedFresh) {
            add(Ui.para("! " + st.movedTo + " للمتابعة: انضم إلى المزامنة الجديدة (تُستبدل بيانات هذا الجهاز ببياناتها؛ خذ نسخة احتياطية قبلها).", 14, Ui.red, w));
            if (admin) add(Ui.row(Ui.primary("انضمام إلى المزامنة الجديدة…", e -> {
                if (!Ui.confirm(this, "الانضمام إلى المزامنة الجديدة", "يخرج هذا الجهاز من المزامنة القديمة (بياناته باقية)، ثم "
                        + (drive && !email.isEmpty() ? "تسجّل الدخول بـ " + email + " و" : "") + "تنضم بكلمة سر المزامنة الجديدة.", "متابعة")) return;
                new SyncEngine(db, null).leave(st.movedTo);
                if (drive && !email.isEmpty()) Ui.busy(this, "بانتظار الدخول في المتصفح…", p -> DeskDriveAuth.login(db, DeskDriveAuth.REFRESH, email), got -> {
                    db.setSettingQuiet("drive_account", got.isEmpty() ? email : got);
                    db.setSettingQuiet("sync_drive_ok", "1");
                    refresh();
                });
                else refresh();
            }), Box.createHorizontalGlue()));
        } else if (drive && !email.isEmpty()) {
            add(Ui.para("! " + st.movedTo + " سجّل الدخول بالحساب الجديد على هذا الجهاز: الخزنة نفسها وبياناتك كما هي.", 14, Ui.red, w));
            if (admin) add(Ui.row(Ui.primary("الدخول بـ " + email + "…", e -> Ui.busy(this, "بانتظار الدخول في المتصفح…", p -> {
                String got = DeskDriveAuth.login(db, DeskDriveAuth.NEW, email);
                if (!got.isEmpty() && !got.equalsIgnoreCase(email)) {
                    DeskDriveAuth.drop(DeskDriveAuth.NEW);
                    throw new IllegalStateException("اخترتَ " + got + ". اختر الحساب الذي نُقلت إليه المزامنة: " + email);
                }
                new SyncEngine(db, new DriveSyncFolder(new DeskDriveAuth(db))).moveTo(new DriveSyncFolder(new DeskDriveAuth(db, DeskDriveAuth.NEW)));
                DeskDriveAuth.promote(DeskDriveAuth.NEW, DeskDriveAuth.REFRESH);
                return email;
            }, got -> {
                db.setSettingQuiet("drive_account", got);
                db.setSettingQuiet("sync_drive_ok", "1");
                Ui.toast("انتقل هذا الجهاز إلى " + got + " ✓");
                now(app);
                refresh();
            })), Box.createHorizontalGlue()));
        } else {
            add(Ui.para("! " + st.movedTo + " اختر المكان الجديد من «نقل المزامنة إلى طريقة أخرى».", 14, Ui.red, w));
        }
        if (!admin) add(Ui.para("اطلب من المدير نقل هذا الجهاز.", 13, Ui.muted, w));
        space(8);
    }

    void resetPw() {
        UsersView.confirmAdmin(this, db, "تعيين كلمة سر المزامنة", () -> {
            JPasswordField n1 = Ui.password(), n2 = Ui.password();
            JPanel c = Ui.column();
            c.add(Ui.para("هذا الجهاز منضم ويحمل مفتاح الخزنة، فتُعيَّن الكلمة الجديدة دون القديمة. الأجهزة المنضمة لا تتأثر، ورمز الاسترداد يبقى كما هو.", 13, Ui.ink, 440));
            c.add(Ui.labeled("كلمة السر الجديدة (8 محارف على الأقل)", n1));
            c.add(Ui.labeled("أعد كتابتها", n2));
            if (!Ui.form(this, "كلمة سر مزامنة جديدة", Ui.rtl(c), "حفظ")) return;
            if (n1.getPassword().length < 8 || !new String(n1.getPassword()).equals(new String(n2.getPassword()))) {
                Ui.info(this, "كلمة سر جديدة", n1.getPassword().length < 8 ? "كلمة السر 8 محارف على الأقل" : "غير متطابقتين");
                return;
            }
            Ui.busy(this, "جارٍ حفظ كلمة السر الجديدة…", p -> {
                new SyncEngine(db, folder(db)).resetPassword(n1.getPassword());
                return true;
            }, ok -> Ui.toast("عُيّنت كلمة سر المزامنة الجديدة ✓"));
        });
    }

    void wipe() {
        JComboBox<String> how = Ui.combo(new String[]{"كلمة سر المزامنة", "رمز الاسترداد"});
        JPasswordField pw = Ui.password();
        Ui.Field word = Ui.field("حذف");
        JPanel c = Ui.column();
        c.add(Ui.para("تُحذف كل ملفات المزامنة المشفّرة من «" + kindName(transport(db)) + "». قبل الحذف تُحفظ نسخة أمان من بيانات هذا الجهاز. "
                + "كل الأجهزة (وهذا الجهاز) تخرج من المزامنة وتبقى بياناتها كما هي، ثم يمكنك إنشاء مزامنة جديدة.", 13.5f, Ui.red, 440));
        c.add(Ui.labeled("بماذا تثبت ملكية الخزنة", how));
        c.add(Ui.labeled("كلمة السر أو الرمز", pw));
        c.add(Ui.labeled("اكتب كلمة «حذف» للتأكيد", word));
        if (!Ui.form(this, "حذف ملفات المزامنة", Ui.rtl(c), "حذف")) return;
        if (!"حذف".equals(word.val().trim())) {
            Ui.info(this, "حذف ملفات المزامنة", "لم يُحذف شيء: اكتب كلمة «حذف» للتأكيد.");
            return;
        }
        char[] sec = pw.getPassword();
        boolean rc = how.getSelectedIndex() == 1;
        UsersView.confirmAdmin(this, db, "حذف ملفات المزامنة", () -> Ui.busy(this, "جارٍ حفظ نسخة أمان وحذف ملفات المزامنة…", p -> new SyncEngine(db, folder(db)).wipe(app.ctx, sec, rc), n -> {
            Ui.info(this, "حُذفت ملفات المزامنة ✓", "حُذف " + n + " ملفًا. خرج هذا الجهاز من المزامنة وبياناته كما هي، وستخرج الأجهزة الأخرى في جولتها التالية. "
                    + "حُفظت نسخة أمان من بيانات هذا الجهاز قبل الحذف (النسخ الاحتياطي ← نسخ الأمان).");
            refresh();
        }));
    }

    /** المزامنة التلقائية في الخلفية (من مؤقت النافذة). */
    static volatile boolean running;
    static volatile long lastAuto;

    static void auto(App app) {
        if (running || !SyncEngine.enabled(app.db) || !License.current.sync() || SyncEngine.paused(app.db)) return;
        SyncFolder f = folder(app.db);
        if (f == null) return;
        running = true;
        lastAuto = Clock.now();
        new SwingWorker<SyncEngine.Result, Void>() {
            @Override protected SyncEngine.Result doInBackground() throws Exception { return new SyncEngine(app.db, f).sync(); }

            @Override protected void done() {
                running = false;
                try {
                    SyncEngine.Result r = get();
                    if (!app.locked) DeskNotify.afterSync(app, r);
                    if (r.received > 0 && !app.locked) {
                        Ui.toast("وصلت تحديثات من الأجهزة الأخرى (" + r.rows + " تغييرًا)");
                        if (afterSync(app)) return;
                        View v = app.views.get(app.current);
                        if (v != null && !(v instanceof TxView)) v.refresh();
                    }
                } catch (Exception ignored) {
                    // يظهر في شاشة المزامنة
                }
            }
        }.execute();
    }

    /** بعد وصول تغييرات: إن صار الدخول مطلوبًا أو أُوقف الحساب الحالي يُطلب الدخول (true). */
    static boolean afterSync(App app) {
        Db db = app.db;
        if (!db.usersEnabled() || app.locked) return false;
        Model.User u = SmartApp.currentUser;
        Model.User now = u == null ? null : db.user(u.id);
        if (now != null && now.active) {
            SmartApp.currentUser = now;
            app.updateFoot();
            return false;
        }
        if (u != null) Ui.toast("أُوقف حسابك أو تغيّر من الجهاز الرئيسي. سجّل الدخول من جديد.");
        app.lock();
        return true;
    }

    /** زر «مزامنة الآن» السريع (الشريط الجانبي أو القائمة). */
    static void now(App app) {
        if (!SyncEngine.enabled(app.db)) {
            Ui.toast("المزامنة غير مفعّلة على هذا الجهاز");
            return;
        }
        if (!License.current.sync()) {
            Ui.toast("المزامنة ميزة في النسخة المدفوعة");
            return;
        }
        if (running) {
            Ui.toast("المزامنة جارية الآن…");
            return;
        }
        SyncFolder f = folder(app.db);
        if (f == null) {
            Ui.toast("اختر مجلد المزامنة من «المزامنة»");
            return;
        }
        running = true;
        Ui.toast("جارٍ المزامنة…");
        new SwingWorker<SyncEngine.Result, Void>() {
            @Override protected SyncEngine.Result doInBackground() throws Exception { return new SyncEngine(app.db, f).sync(); }

            @Override protected void done() {
                running = false;
                try {
                    SyncEngine.Result r = get();
                    Ui.toast("✓ " + r.summary() + (r.errors.isEmpty() ? "" : " — " + r.errors.get(0)));
                    if (r.received > 0) {
                        if (afterSync(app)) return;
                        View v = app.views.get(app.current);
                        if (v != null && !(v instanceof TxView)) v.refresh();
                    } else if ("sync".equals(app.current)) app.go("sync");
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    Ui.toast("تعذرت المزامنة: " + Ui.friendly(c));
                }
            }
        }.execute();
    }

    static Color tint() { return Ui.primary; }
}
