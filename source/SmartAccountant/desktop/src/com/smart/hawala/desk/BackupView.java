package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import javax.swing.*;

/**
 * النسخ الاحتياطي على الماك — الصيغة نفسها (.sba) التي في الهاتف في الاتجاهين:
 * نسخة الهاتف تُستعاد هنا، ونسخة الماك تُستعاد في الهاتف (استعادة نسخة من ملف). كل نسخة تُفك كاملة للتحقق قبل اعتمادها.
 */
final class BackupView extends View {
    BackupView(App app) {
        super(app, "النسخ الاحتياطي");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        action(Ui.primary("نسخة احتياطية الآن", e -> create()));
        action(Ui.ghost("استعادة من ملف…", e -> restoreFromFile()));
        if (can(Auth.Perm.RESTORE)) action(Ui.ghost("مساعد النقل من برنامج آخر…", e -> migrationWizard()));

        Ui.Card info = Ui.tinted(Ui.primarySoft, null);
        info.add(Ui.label("نسخة واحدة تعمل على الهاتف والكمبيوتر", 16, Ui.primaryInk, true));
        info.add(Ui.para("الملف \u200E.sba الذي تنشئه هنا يُستعاد في تطبيق الهاتف (النسخ الاحتياطي ← استعادة نسخة من ملف)، ونسخة الهاتف تُستعاد هنا. "
                + "الحقول الحساسة تُفك داخل الملف المشفّر بكلمة مرورك، وتُشفَّر من جديد بمفتاح الجهاز الذي يستعيدها. احفظ كلمة المرور خارج الجهاز.", 13.5f, Ui.ink, w));
        add(info);
        space(12);

        long last = db.settingLong("last_backup", 0);
        Ui.Card st = Ui.card();
        st.add(Ui.label("آخر نسخة: " + (last == 0 ? "لا توجد بعد" : Model.date(last)) + ("verified".equals(db.setting("last_backup_status", "")) ? " — متحقَّق منها ✓" : ""), 15, last == 0 ? Ui.red : Ui.ink, true));
        List<Db.BackupRecord> hist = db.backupHistory(8);
        for (Db.BackupRecord r : hist)
            st.add(Ui.label(Model.date(r.createdAt) + " · " + r.status + " · " + BackupManager.size(r.size) + (r.tx > 0 ? " · " + r.tx + " حركة" : "") + (r.path == null || r.path.isEmpty() ? "" : " · " + new File(r.path).getName()),
                    12.5f, Ui.muted, false));
        add(st);
        space(12);

        // النسخ التلقائي
        Ui.Card ab = Ui.card();
        ab.add(Ui.heading("النسخ التلقائي إلى مجلد"));
        ab.add(Ui.para("اختر مجلدًا في iCloud Drive أو Dropbox أو قرصًا خارجيًا: تصير كل نسخة تلقائية نسخة سحابية أيضًا. "
                + (License.current.pro ? "النسخة الكاملة: حتى كل 6 ساعات أو بعد كل 25 حركة." : "النسخة المجانية: مرة يوميًا على الأكثر."), 13, Ui.muted, w));
        ab.add(Ui.gap(6));
        String dir = db.setting("desk_backup_dir", "");
        JCheckBox on = Ui.check("تشغيل النسخ التلقائي", "1".equals(db.setting("auto_backup_on", "0")));
        on.addActionListener(e -> {
            if (on.isSelected() && !DeskBackup.configured(db)) {
                on.setSelected(false);
                Ui.toast("اختر المجلد وكلمة المرور أولًا");
                return;
            }
            db.setSetting("auto_backup_on", on.isSelected() ? "1" : "0");
            refresh();
        });
        ab.add(on);
        ab.add(Ui.label("المجلد: " + (dir.isEmpty() ? "لم يُختر" : dir), 13.5f, dir.isEmpty() ? Ui.red : Ui.ink, false));
        ab.add(Ui.label("كلمة المرور: " + (db.setting("auto_backup_pw", "").isEmpty() ? "لم تُحدَّد" : "محفوظة (مشفّرة بمفتاح هذا الجهاز)"), 13.5f, Ui.ink, false));
        JComboBox<String> every = Ui.combo(new String[]{"كل 6 ساعات", "يوميًا", "كل 3 أيام", "أسبوعيًا"});
        long hours = DeskBackup.intervalHours(db);
        for (int i = 0; i < BackupPolicy.HOURS.length; i++) if (BackupPolicy.HOURS[i] == hours) every.setSelectedIndex(i);
        every.addActionListener(e -> {
            long h = BackupPolicy.HOURS[every.getSelectedIndex()];
            if (h < License.current.autoBackupMinHours()) {
                Ui.toast("كل 6 ساعات في النسخة الكاملة؛ المجانية يوميًا على الأكثر");
                h = License.current.autoBackupMinHours();
            }
            db.setSettingQuiet("auto_backup_hours", "" + h);
            refresh();
        });
        every.setMaximumSize(new Dimension(220, 38));
        JCheckBox perTx = Ui.check("ونسخة بعد كل 25 حركة (النسخة الكاملة)", DeskBackup.everyTx(db) > 0);
        perTx.setEnabled(License.current.autoBackupEveryTx() > 0);
        perTx.addActionListener(e -> db.setSettingQuiet("auto_backup_every_tx", perTx.isSelected() ? "25" : "0"));
        JCheckBox readable = Ui.check("نسخة مقروءة شهرية (Excel غير مشفّر بالأرصدة وحركات الشهر الماضي)", "1".equals(db.setting("readable_export", "0")));
        readable.addActionListener(e -> db.setSettingQuiet("readable_export", readable.isSelected() ? "1" : "0"));
        ab.add(Ui.row(Ui.label("التكرار", 13, Ui.muted, true), every));
        ab.add(perTx);
        ab.add(readable);
        String err = db.setting("auto_backup_error", "");
        if (!err.isEmpty()) ab.add(Ui.para("! " + err, 13, Ui.red, w));
        long la = db.settingLong("last_auto_backup", 0);
        if (la > 0) ab.add(Ui.label("آخر نسخة تلقائية: " + Model.date(la), 13, Ui.muted, false));
        ab.add(Ui.gap(6));
        ab.add(Ui.flow(Ui.ghost("اختيار المجلد…", e -> pickDir()), Ui.ghost("تعيين كلمة المرور…", e -> setAutoPw()),
                Ui.ghost("اختبار تذكّرها…", e -> testAutoPw()),
                Ui.ghost("نسيتها؟ عرضها (للمدير)…", e -> revealAutoPw()),
                Ui.ghost("نسخة تلقائية الآن", e -> Ui.busy(this, "جارٍ النسخ والتحقق…", p -> DeskBackup.runNow(app), r -> {
                    Ui.toast("✓ " + r);
                    refresh();
                })), Ui.ghost("فتح المجلد", e -> {
                    try {
                        if (!dir.isEmpty()) Desktop.getDesktop().open(new File(dir));
                    } catch (Exception ex) {
                        Ui.error(this, ex);
                    }
                })));
        add(ab);
        space(12);

        // 10.14: رمز استرداد النسخ
        Ui.Card rc = Ui.card();
        rc.add(Ui.heading("رمز استرداد النسخ"));
        boolean rcOn = BackupRecovery.on(db);
        rc.add(Ui.para(rcOn ? "✓ مفعّل منذ " + Model.date(BackupRecovery.createdAt(db)) + ". كل نسخة من هذا الجهاز (التلقائية واليدوية) تُفتح بكلمة مرورها أو بهذا الرمز."
                : "غير مفعّل. إن نسيت كلمة مرور النسخ فلا تُفتح النسخ أبدًا. فعّل رمز الاسترداد واحفظه على ورقة بعيدًا عن الجهاز: يفتح كل نسخة تُؤخذ بعده.", 13.5f, rcOn ? Ui.green : Ui.ink, w));
        if (isAdmin()) {
            if (!rcOn) rc.add(Ui.flow(Ui.primary("إنشاء رمز استرداد النسخ…", e -> UsersView.confirmAdmin(this, db, "رمز استرداد النسخ", this::newBackupCode))));
            else rc.add(Ui.flow(Ui.ghost("رمز جديد…", e -> {
                if (Ui.confirm(this, "رمز استرداد جديد", "النسخ القادمة تُفتح بالرمز الجديد. النسخ السابقة تبقى تُفتح بالرمز القديم وبكلمة مرورها، فاحتفظ بالقديم ما دمت تحتاج تلك النسخ.", "متابعة"))
                    UsersView.confirmAdmin(this, db, "رمز استرداد النسخ", this::newBackupCode);
            }), Ui.danger("إيقاف الرمز…", e -> {
                if (Ui.confirm(this, "إيقاف رمز استرداد النسخ", "النسخ القادمة تُفتح بكلمة مرورها وحدها. النسخ السابقة تبقى تُفتح بالرمز القديم.", "إيقاف"))
                    UsersView.confirmAdmin(this, db, "إيقاف رمز استرداد النسخ", () -> {
                        BackupRecovery.disable(db);
                        Ui.toast("أُوقف رمز استرداد النسخ");
                        refresh();
                    });
            })));
        } else rc.add(Ui.label("للمدير وحده.", 12.5f, Ui.muted, false));
        rc.add(Ui.para("الرمز لا يُحفظ على الجهاز ولا يظهر إلا مرة واحدة. النسخ المأخوذة بإصدار أقدم من 10.14، أو قبل تفعيل الرمز، تُفتح بكلمة مرورها فقط. "
                + "النسخ المأخوذة بعد تفعيله لا تفتحها الإصدارات الأقدم من 10.14.", 12.5f, Ui.muted, w));
        add(rc);
        space(12);

        // تمرين الاستعادة
        Ui.Card dr = Ui.card();
        dr.add(Ui.heading("تمرين الاستعادة"));
        String ds = db.setting("restore_drill_status", "");
        dr.add(Ui.para(ds.isEmpty() ? "لم تُجرَّب استعادة أي نسخة بعد. النسخة التي لم تُجرَّب لا تُعدّ نسخة: اختر ملف نسخة لفكه وفحصه كاملًا دون تغيير بياناتك."
                : ds.substring(ds.indexOf('|') + 1) + " — " + Model.date(db.settingLong("restore_drill_at", 0)), 13.5f, Ui.ink, w));
        dr.add(Ui.flow(Ui.ghost("جرّب استعادة نسخة (دون تغيير شيء)…", e -> drill())));
        add(dr);
        space(12);

        // نسخ الأمان
        Ui.Card sn = Ui.card();
        sn.add(Ui.heading("نسخ الأمان (التراجع)"));
        sn.add(Ui.para("تُحفظ تلقائيًا قبل كل استعادة أو دمج أو حذف مكتب أو إضافة ملف نقل (آخر 3).", 13, Ui.muted, w));
        List<Snapshot.Info> snaps = Snapshot.list(app.ctx);
        if (snaps.isEmpty()) sn.add(Ui.label("لا توجد نسخ أمان بعد.", 13.5f, Ui.muted, false));
        for (Snapshot.Info x : snaps) {
            JPanel r = Ui.row(Ui.label(Model.date(x.time) + " — قبل " + (x.label.isEmpty() ? "آخر استعادة" : x.label), 14, Ui.ink, false), Box.createHorizontalGlue(),
                    Ui.ghost("تراجع إليها", e -> revert(x)));
            sn.add(r);
        }
        add(sn);
        done();
    }

    // ===================================================================================
    // إنشاء نسخة يدوية
    // ===================================================================================

    static char[][] askNewPassword(Component parent, String title) {
        JPanel c = Ui.column();
        JPasswordField p1 = Ui.password(), p2 = Ui.password();
        c.add(Ui.labeled("كلمة المرور (8 محارف على الأقل)", p1));
        c.add(Ui.gap(8));
        c.add(Ui.labeled("أعد كتابتها", p2));
        c.add(Ui.gap(8));
        c.add(Ui.para("احفظها خارج الجهاز (ورقة أو مدير كلمات مرور). بدونها لا تُستعاد النسخة على أي جهاز.", 12.5f, Ui.red, 360));
        c.setPreferredSize(new Dimension(400, c.getPreferredSize().height));
        while (Ui.form(parent, title, Ui.rtl(c), "متابعة")) {
            char[] a = p1.getPassword(), b = p2.getPassword();
            if (a.length < 8) {
                Ui.toast("كلمة المرور يجب ألا تقل عن 8 محارف");
                continue;
            }
            if (!Arrays.equals(a, b)) {
                Ui.toast("كلمتا المرور غير متطابقتين");
                continue;
            }
            Arrays.fill(b, '\0');
            return new char[][]{a};
        }
        return null;
    }

    void create() {
        if (!require(Auth.Perm.BACKUP)) return;
        if (!Crypto.healthy(app.ctx)) {
            Ui.message(this, "مفتاح التشفير غير متاح", "لا تُنشأ نسخة جديدة حتى لا تحل محل السليمة. استعد آخر نسخة احتياطية.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        char[][] pw = askNewPassword(this, "نسخة احتياطية جديدة");
        if (pw == null) return;
        File target = Export.save(this, db, "smart-accountant", ".sba");
        if (target == null) {
            Arrays.fill(pw[0], '\0');
            return;
        }
        Ui.busy(this, "جارٍ التجهيز…", p -> {
            try {
                BackupManager.Created c = BackupManager.createLocal(app.ctx, db, pw[0], BuildInfo.VERSION + "-mac", p::update);
                p.update("جارٍ الحفظ وإعادة القراءة للتحقق…");
                BackupManager.Delivery d = BackupManager.copyToFile(c.file, target);
                db.recordBackup("SAVE", d.verified ? "تم الحفظ والتحقق (ماك)" : "حُفظت دون تحقق (ماك)", target.getAbsolutePath(), d.size, c.counts, d.verified, d.note);
                long now = Clock.now();
                db.setSettingQuiet("last_backup", "" + now);
                db.setSettingQuiet("last_backup_tx", "" + db.txCount());
                db.setSettingQuiet("last_backup_status", d.verified ? "verified" : "unverified");
                return c;
            } finally {
                Arrays.fill(pw[0], '\0');
            }
        }, c -> {
            Ui.info(this, "تمت النسخة ✓", "حُفظت النسخة وتحقق البرنامج منها بفكها كاملة وإعادة قراءتها من مكان الحفظ.\n\n" + target.getName() + "\n" + BackupManager.size(c.size)
                    + "\n\nتُستعاد على الكمبيوتر أو الهاتف بكلمة المرور نفسها.");
            refresh();
            app.updateFoot();
        });
    }

    // ===================================================================================
    // الاستعادة (استبدال أو إضافة ملف نقل)
    // ===================================================================================

    void restoreFromFile() {
        if (!require(Auth.Perm.RESTORE)) return;
        File f = Export.open(this, db, "اختر ملف النسخة (.sba)", ".sba");
        if (f == null) return;
        restoreFile(f);
    }

    /** استعادة ملف محدد (من نافذة الاختيار أو بالنقر المزدوج على ملف .sba في Finder). */
    void restoreFile(File f) {
        if (!require(Auth.Perm.RESTORE)) return;
        JPasswordField p = Ui.password();
        JPanel c = Ui.column();
        c.add(Ui.para("سيتم فك النسخة وفحصها كاملة وعرض محتواها قبل أي تغيير على بياناتك.", 13.5f, Ui.ink, 360));
        c.add(Ui.gap(8));
        c.add(Ui.labeled("كلمة مرور النسخة أو رمز استرداد النسخ", p));
        if (!Ui.form(this, "كلمة مرور النسخة", Ui.rtl(c), "فك وفحص")) return;
        char[] pw = p.getPassword();
        Ui.busy(this, "جارٍ فك النسخة وفحصها…", pr -> {
            try {
                return BackupManager.prepareRestore(app.ctx, f, pw, pr::update);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }, prep -> preview(f, prep));
    }

    void preview(File f, BackupManager.Prepared prep) {
        BackupFormat.Report r = prep.report;
        if ("migration".equals(prep.parsed.manifest.optString("source"))) {
            int k = Ui.choose(this, "ملف من أداة النقل", "في الملف " + r.offices + " مكتب و " + r.txActive + " حركة.\n\nكيف تريد استخدامه؟\n\n"
                    + "• إضافة إلى بياناتي الحالية (موصى به): تبقى مكاتبك وحركاتك كما هي، وتُضاف أرصدة الملف وحركاته.\n\n"
                    + "• استبدال كل بياناتي: يحل محتوى الملف محل كل البيانات.", new String[]{"إضافة إلى بياناتي", "استبدال الكل", "إلغاء"});
            if (k == 0) {
                merge(prep);
                return;
            }
            if (k != 1) {
                BackupManager.clearStaging(app.ctx);
                return;
            }
        }
        // 9.0: النسخة المجانية تتسع لـ 9 مكاتب: يختار المستخدم المكاتب التي تُستعاد من نسخة فيها أكثر
        long maxOff = License.current.maxOffices();
        if (maxOff != Entitlements.UNLIMITED && r.offices > maxOff) {
            List<BackupFormat.OfficeInfo> infos = BackupFormat.officeSummaries(prep.parsed);
            infos.sort((x, y) -> x.txCount != y.txCount ? Long.compare(y.txCount, x.txCount) : Long.compare(y.lastTx, x.lastTx));
            List<Long> ids = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            Map<String, Integer> dec = db.decimalsMap();
            for (BackupFormat.OfficeInfo o : infos) {
                ids.add(o.id);
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Long> e : o.balances.entrySet())
                    if (e.getValue() != 0) parts.add(Model.bal(e.getValue(), dec.getOrDefault(e.getKey(), 2)) + " " + e.getKey());
                labels.add((o.name.isEmpty() ? "مكتب #" + o.id : o.name) + " — " + o.txCount + " حركة" + (parts.isEmpty() ? "" : " — " + String.join("، ", parts)));
            }
            Set<Long> keep = pick("اختر المكاتب (" + maxOff + " كحد أقصى)", "في النسخة " + infos.size() + " مكتبًا، والنسخة المجانية تتسع لـ " + maxOff
                    + ". اختر المكاتب التي تُستعاد بكل حركاتها وأرصدتها (اقترحنا الأكثر حركات). لاستعادة الكل فعّل النسخة الكاملة.", ids, labels, (int) maxOff);
            if (keep == null) {
                BackupManager.clearStaging(app.ctx);
                return;
            }
            prep.keep = keep;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("تاريخ النسخة: ").append(r.createdAt > 0 ? Model.date(r.createdAt) : "غير معروف")
                .append("\nإصدار البرنامج: ").append(r.appVersion.isEmpty() ? "—" : r.appVersion).append(" (صيغة ").append(r.format).append(")")
                .append("\nالمكاتب: ").append(r.offices).append(prep.keep != null ? " — تُستعاد " + prep.keep.size() + " مختارة فقط" : "")
                .append("\nالحركات: ").append(r.txActive).append(" نشطة + ").append(r.txCancelled).append(" ملغاة")
                .append(r.cashMoves > 0 ? "\nحركات صندوق المكتب: " + r.cashMoves : "")
                .append("\nالصور: ").append(r.attachments)
                .append("\nالعملات: ").append(r.currencies)
                .append("\n\n✓ القيود متوازنة والعلاقات سليمة.");
        for (String wn : r.warnings) sb.append("\n! ").append(wn);
        sb.append("\n\nبياناتك الحالية: ").append(db.officeCount()).append(" مكتب و ").append(db.txCount()).append(" حركة؛ ستُحفظ منها نسخة أمان ثم تُستبدل.");
        if (!Ui.confirm(this, "معاينة النسخة قبل الاستعادة", sb.toString(), "استعادة الآن")) {
            BackupManager.clearStaging(app.ctx);
            return;
        }
        // 10.13: مع تفضيلات المظهر التي في النسخة أو بدونها
        int lk = JOptionPane.showOptionDialog(this, Ui.rtl(Ui.para("تُستعاد البيانات كاملة في الحالتين. هل تُستعاد معها تفضيلات المظهر التي في النسخة (الألوان، الوضع الليلي، الرئيسية…)؟",
                14, Ui.ink, 440)), "تفضيلات المظهر", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"مع تفضيلات المظهر", "بدونها (أبقِ مظهري)"}, "مع تفضيلات المظهر");
        if (lk < 0) {
            BackupManager.clearStaging(app.ctx);
            return;
        }
        prep.keepLook = lk == 1;
        Ui.busy(this, "جارٍ الاستعادة…", p -> {
            BackupManager.applyRestore(app.ctx, db, prep, p::update);
            return null;
        }, x -> {
            db.recordBackup("RESTORE", "تمت الاستعادة (ماك)", f.getName(), f.length(), db.counts(), true, "");
            afterDataReplaced();
            Ui.info(App.frame, "تمت الاستعادة ✓", "تم استبدال البيانات بمحتوى النسخة. إن لم تكن النسخة المطلوبة: النسخ الاحتياطي ← نسخ الأمان ← «تراجع».");
        });
    }

    void merge(BackupManager.Prepared prep) {
        Ui.busy(this, "جارٍ مطابقة المكاتب وحساب الأرصدة…", p -> MergePlan.build(prep.parsed.tables, prep.parsed.manifest, db.mergeExisting()), plan -> {
            long maxOff = License.current.maxOffices();
            long live = db.officeCount();
            if (plan.ok() && maxOff != Entitlements.UNLIMITED && live + plan.newOffices > maxOff) {
                int allowed = (int) Math.max(0, maxOff - live);
                if (allowed == 0) {
                    Ui.info(this, "لا مكان لمكتب جديد", "عندك الحد الأقصى من المكاتب في النسخة المجانية. احذف مكتبًا لا تحتاجه فيعود مكانه، أو فعّل النسخة الكاملة.");
                    BackupManager.clearStaging(app.ctx);
                    return;
                }
                List<Long> ids = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (MergePlan.Office o : plan.newOfficeList()) {
                    ids.add(o.fileId);
                    labels.add(o.name);
                }
                Set<Long> keep = pick("اختر المكاتب الجديدة (حتى " + allowed + ")", "الملف يضيف " + plan.newOffices + " مكتبًا جديدًا وعندك " + live
                        + "، والنسخة المجانية تتسع لـ " + maxOff + ". المكاتب الموجودة عندك تُضاف إليها الأرصدة دائمًا.", ids, labels, allowed);
                if (keep == null) {
                    BackupManager.clearStaging(app.ctx);
                    return;
                }
                plan.keepNew(keep);
            }
            JTextArea a = Ui.area(16);
            a.setEditable(false);
            // 10.16: اعتماد رصيد الملف كما هو بدل جمعه مع الرصيد الحالي
            final JCheckBox adopt = Ui.check("اعتماد رصيد الملف كما هو (بدون جمعه مع الرصيد الحالي)", false);
            Runnable fill = () -> {
                StringBuilder sb = new StringBuilder(plan.adopt
                        ? "للمكاتب الموجودة: يُلغى رصيدها الحالي بقيد تسوية ظاهر في الكشف ثم يُكتب رصيد الملف، فيصبح الرصيد = رصيد الملف تمامًا. لا تُحذف أي حركة.\n\n"
                        : "تُضاف أرصدة الملف وحركاته إلى بياناتك الحالية. لا يُحذف شيء.\n\n");
                sb.append("المكاتب: ").append(plan.matchedOffices).append(" موجود، و ").append(plan.newOffices).append(" جديد");
                sb.append("\nالحركات: ").append(plan.txs.size()).append("   •   الأرصدة السابقة: ").append(plan.openings.size());
                for (String e : plan.errors) sb.append("\n× ").append(e);
                for (String w : plan.warnings) sb.append("\n! ").append(w);
                if (plan.ok()) {
                    sb.append("\n");
                    for (MergePlan.Row r : plan.rows) {
                        boolean rep = plan.adopt && !r.isNew && r.current != 0;
                        sb.append("\n• ").append(r.office).append(" (").append(r.isNew ? "جديد" : r.approximate ? "موجود — اسم مقارب" : "موجود").append(") ").append(r.code);
                        if (rep) sb.append(": الآن ").append(Model.bal(r.current, r.decimals)).append(" (يُلغى) ← يُعتمد رصيد الملف ").append(Model.bal(r.after, r.decimals));
                        else sb.append(": الآن ").append(Model.bal(r.current, r.decimals)).append(" + ").append(Model.bal(r.added, r.decimals)).append(" = ").append(Model.bal(r.after, r.decimals));
                    }
                }
                a.setText(sb.toString());
                a.setCaretPosition(0);
            };
            fill.run();
            adopt.addActionListener(e -> {
                plan.setAdopt(adopt.isSelected());
                fill.run();
            });
            JScrollPane sp0 = Ui.areaBox(a);
            sp0.setPreferredSize(new Dimension(620, 420));
            JPanel sp = Ui.column();
            if (plan.ok() && plan.adoptable() > 0) {
                sp.add(adopt);
                sp.add(Ui.gap(6));
            }
            sp.add(sp0);
            Ui.rtl(sp);
            if (!plan.ok() || !Ui.form(this, "إضافة ملف النقل", sp, "إضافة الآن")) {
                if (!plan.ok()) Ui.form(this, "لا يمكن الإضافة", sp, "إغلاق");
                BackupManager.clearStaging(app.ctx);
                return;
            }
            Ui.busy(this, "جارٍ حفظ نسخة أمان ثم الإضافة والتحقق…", p -> {
                Snapshot.take(app.ctx, db, "إضافة ملف النقل");
                return db.mergeMigration(plan);
            }, res -> {
                BackupManager.clearStaging(app.ctx);
                app.changed();
                Ui.info(this, "تمت الإضافة ✓", "أُضيف " + plan.txs.size() + " حركة و " + plan.openings.size() + " رصيد سابق إلى " + plan.offices.size() + " مكتب ("
                        + res + " جديد)" + (plan.adopt ? "، واعتُمد رصيد الملف للمكاتب الموجودة بدل جمعه" : "") + "، وتحقق البرنامج أن رصيد كل مكتب صار كما في المعاينة تمامًا.");
                app.go("home");
            });
        });
    }

    /** نافذة اختيار حتى max عنصرًا (المقترح: أول max). null = إلغاء. */
    Set<Long> pick(String title, String msg, List<Long> ids, List<String> labels, int max) {
        JPanel c = Ui.column();
        c.add(Ui.para(msg, 13.5f, Ui.ink, 560));
        c.add(Ui.gap(8));
        JLabel counter = Ui.label("", 14, Ui.primaryInk, true);
        c.add(counter);
        JPanel list = Ui.column();
        List<JCheckBox> boxes = new ArrayList<>();
        Runnable upd = () -> {
            int n = 0;
            for (JCheckBox b : boxes) if (b.isSelected()) n++;
            counter.setText("المختار: " + n + " من " + max);
            counter.setForeground(n > max ? Ui.red : Ui.primaryInk);
        };
        for (int i = 0; i < ids.size(); i++) {
            JCheckBox b = Ui.check(labels.get(i), i < max);
            b.addActionListener(e -> upd.run());
            boxes.add(b);
            list.add(b);
        }
        JScrollPane sp = Ui.scroll(list);
        sp.setPreferredSize(new Dimension(600, 340));
        c.add(sp);
        upd.run();
        while (true) {
            if (!Ui.form(this, title, Ui.rtl(c), "متابعة")) return null;
            Set<Long> out = new LinkedHashSet<>();
            for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) out.add(ids.get(i));
            if (!out.isEmpty() && out.size() <= max) return out;
            Ui.info(this, title, out.isEmpty() ? "اختر مكتبًا واحدًا على الأقل." : "اخترت " + out.size() + "، والحد " + max + ".");
        }
    }

    void afterDataReplaced() {
        SmartApp.currentUser = null;
        Db.currentUserName = "المستخدم المحلي";
        License.load(app.ctx, db);
        app.locked = db.usersEnabled();
        app.reloadAll();
        app.afterUnlock();
    }

    // ===================================================================================
    // النسخ التلقائي: المجلد وكلمة المرور
    // ===================================================================================

    void pickDir() {
        JFileChooser ch = new JFileChooser(db.setting("desk_backup_dir", System.getProperty("user.home")));
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setDialogTitle("اختر مجلد النسخ التلقائي");
        ch.applyComponentOrientation(Ui.RTL);
        File icloud = new File(System.getProperty("user.home"), "Library/Mobile Documents/com~apple~CloudDocs");
        if (icloud.isDirectory() && db.setting("desk_backup_dir", "").isEmpty()) ch.setCurrentDirectory(icloud);
        if (ch.showDialog(this, "اختيار") != JFileChooser.APPROVE_OPTION) return;
        File d = ch.getSelectedFile();
        if (!d.isDirectory() || !d.canWrite()) {
            Ui.toast("لا يمكن الكتابة في هذا المجلد");
            return;
        }
        db.setSetting("desk_backup_dir", d.getAbsolutePath());
        refresh();
    }

    void setAutoPw() {
        char[][] pw = askNewPassword(this, "كلمة مرور النسخ التلقائي");
        if (pw == null) return;
        try {
            db.setSettingQuiet("auto_backup_pw", Crypto.enc(new String(pw[0])));
            Ui.toast("حُفظت كلمة المرور مشفّرة بمفتاح هذا الجهاز");
        } finally {
            Arrays.fill(pw[0], '\0');
        }
        refresh();
    }

    boolean isAdmin() {
        return app.can(Auth.Perm.BACKUP) && (!db.usersEnabled() || (SmartApp.currentUser != null && Auth.isAdmin(SmartApp.currentUser)));
    }

    void newBackupCode() {
        Ui.busy(this, "جارٍ إنشاء الرمز…", p -> BackupRecovery.create(db), code -> {
            JTextField f = new JTextField(code);
            f.setEditable(false);
            f.setFont(Ui.font(18, true));
            JPanel q = Ui.column();
            q.add(Ui.para("يفتح كل نسخة احتياطية تُؤخذ من الآن إن نسيت كلمة مرورها. يظهر مرة واحدة فقط: اكتبه على ورقة واحفظه بعيدًا عن هذا الجهاز.", 14, Ui.ink, 460));
            q.add(Ui.gap(8));
            q.add(f);
            Ui.form(this, "رمز استرداد النسخ — احفظه الآن", Ui.rtl(q), "حفظته");
            refresh();
        });
    }

    /** 10.16: مساعد النقل من برنامج آخر (كان على الهاتف وحده): الأداتان تُفتحان في المتصفح من هذا الجهاز، ثم يُستورد الملف الناتج. */
    void migrationWizard() {
        if (!app.require(Auth.Perm.RESTORE)) return;
        JPanel c = Ui.column();
        c.add(Ui.label("انقل بياناتك من برنامجك الحالي في ثلاث خطوات", 16, Ui.ink, true));
        c.add(Ui.para("الأداتان تعملان في المتصفح على هذا الجهاز بلا إنترنت: تقرآن ملف Excel الذي يصدّره برنامجك (أو جدول Excel تعمل عليه)، "
                + "وتحوّلانه إلى ملف نسخة مشفّر (‎.sba) بكلمة سر تختارها، ثم تستورده هنا.", 13.5f, Ui.muted, 520));
        c.add(Ui.gap(10));
        c.add(Ui.label("1. افتح الأداة المناسبة", 14, Ui.primaryInk, true));
        c.add(Ui.flow(Ui.ghost("أداة النقل (كشوفات وملفات الميزان بيست ريت)", e -> openTool("migration-tool.html", "أداة-النقل-المحاسب-الذكي.html")),
                Ui.ghost("الاستيراد الذكي (أي ملف Excel)", e -> openTool("smart-import.html", "الاستيراد-الذكي-المحاسب-الذكي.html"))));
        c.add(Ui.gap(6));
        c.add(Ui.label("2. حوّل ملفاتك في المتصفح", 14, Ui.primaryInk, true));
        c.add(Ui.para("اختر ملف Excel أو كشوفات الحساب، راجع مطابقة أسماء المكاتب والأرصدة المتوقعة، ثم احفظ ملف ‎.sba.", 13.5f, Ui.ink, 520));
        c.add(Ui.gap(6));
        c.add(Ui.label("3. استورد الملف الناتج هنا", 14, Ui.primaryInk, true));
        c.add(Ui.para("تختار عند الاستيراد «إضافة إلى بياناتي» (ومعها خيار اعتماد رصيد الملف بدل الجمع) أو «استبدال الكل». تُعرض معاينة الأرصدة قبل أي تغيير وتُحفظ نسخة أمان.",
                13.5f, Ui.ink, 520));
        if (Ui.form(this, "مساعد النقل من برنامج آخر", Ui.rtl(c), "استيراد ملف ‎.sba الآن…")) restoreFromFile();
    }

    void openTool(String res, String name) {
        try (java.io.InputStream in = BackupView.class.getResourceAsStream("/res/" + res)) {
            if (in == null) throw new IllegalStateException("الأداة غير مضمّنة في هذه النسخة");
            File dir = new File(app.ctx.home(), "tools");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("تعذر إنشاء مجلد الأدوات");
            File f = new File(dir, name);
            java.nio.file.Files.copy(in, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Desktop.getDesktop().browse(f.toURI());
            Ui.toast("فُتحت الأداة في المتصفح");
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    /** 10.16: «اختبر أنك تتذكر كلمة المرور» (كالهاتف): تكتبها وتُقارن بالمحفوظة دون عرضها. */
    void testAutoPw() {
        if (db.setting("auto_backup_pw", "").isEmpty()) {
            Ui.info(this, "اختبار كلمة المرور", "لم تُحدَّد كلمة مرور للنسخ التلقائي بعد.");
            return;
        }
        JPasswordField f = Ui.password();
        JPanel q = Ui.column();
        q.add(Ui.para("اكتب كلمة مرور النسخ التلقائي كما تتذكرها. بدونها لا تُفتح النسخ على جهاز آخر.", 14, Ui.ink, 400));
        q.add(Ui.gap(8));
        q.add(f);
        if (!Ui.form(this, "اختبار تذكّر كلمة المرور", q, "اختبار")) return;
        try {
            boolean ok = Crypto.decStrict(db.setting("auto_backup_pw", "")).equals(new String(f.getPassword()));
            if (ok) Ui.info(this, "صحيحة ✓", "كلمة المرور التي كتبتها تطابق المحفوظة.");
            else Ui.info(this, "غير مطابقة", "ما كتبته لا يطابق كلمة المرور المحفوظة. إن نسيتها فاعرضها (للمدير) أو عيّن كلمة جديدة واحفظها في مكان آمن.");
        } catch (Exception e) {
            Ui.info(this, "تعذر الاختبار", "لا يمكن فك كلمة المرور المحفوظة على هذا الجهاز. عيّن كلمة جديدة.");
        }
    }

    /** 10.14: نسيان كلمة مرور النسخ التلقائي: تُعرض للمدير بعد تأكيد رمزه، ويُسجَّل العرض. */
    void revealAutoPw() {
        if (db.setting("auto_backup_pw", "").isEmpty()) {
            Ui.info(this, "كلمة مرور النسخ التلقائي", "لم تُحدَّد كلمة مرور بعد.");
            return;
        }
        if (!isAdmin()) {
            Ui.info(this, "كلمة مرور النسخ التلقائي", "عرضها للمدير وحده.");
            return;
        }
        UsersView.confirmAdmin(this, db, "عرض كلمة مرور النسخ التلقائي", () -> {
            String pw;
            try {
                pw = Crypto.decStrict(db.setting("auto_backup_pw", ""));
            } catch (Exception e) {
                Ui.info(this, "تعذر عرضها", "لا يمكن فك كلمة المرور المحفوظة على هذا الجهاز. عيّن كلمة جديدة؛ النسخ القديمة تُفتح بكلمتها القديمة أو برمز استرداد النسخ إن كان مفعّلًا حينها.");
                return;
            }
            db.audit("AUTO_PW_REVEAL", "backup", 0, "عُرضت كلمة مرور النسخ التلقائي");
            JTextField f = new JTextField(pw);
            f.setEditable(false);
            f.setFont(Ui.font(16, true));
            JPanel q = Ui.column();
            q.add(Ui.para("اكتبها على ورقة واحفظها بعيدًا عن هذا الجهاز:", 14, Ui.ink, 420));
            q.add(Ui.gap(8));
            q.add(f);
            Ui.form(this, "كلمة مرور النسخ التلقائي", Ui.rtl(q), "إغلاق");
        });
    }

    // ===================================================================================
    // التمرين ونسخ الأمان
    // ===================================================================================

    void drill() {
        File f = Export.open(this, db, "اختر نسخة لتجربة استعادتها", ".sba");
        if (f == null) return;
        JPasswordField p = Ui.password();
        if (!Ui.form(this, "كلمة مرور النسخة", Ui.rtl(Ui.labeled("كلمة المرور أو رمز استرداد النسخ", p)), "تجربة")) return;
        char[] pw = p.getPassword();
        Ui.busy(this, "جارٍ فك النسخة كاملة وفحصها…", pr -> {
            try {
                return BackupManager.drill(app.ctx, f, pw, pr::update);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }, r -> {
            String msg = r.ok ? "النسخة سليمة وتُستعاد: " + r.offices + " مكتب، " + r.tx + " حركة، " + r.attachments + " صورة." + (r.newerTx > 0 ? " (بعدها " + r.newerTx + " حركة أحدث على هذا الجهاز)" : "")
                    : r.message;
            db.setSettingQuiet("restore_drill_status", (r.ok ? "ok" : "fail") + "|" + msg);
            db.setSettingQuiet("restore_drill_at", "" + Clock.now());
            Ui.message(this, r.ok ? "تمرين الاستعادة ✓" : "التمرين لم ينجح", msg, r.ok ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            refresh();
        });
    }

    void revert(Snapshot.Info x) {
        if (!require(Auth.Perm.RESTORE)) return;
        if (!Ui.confirm(this, "التراجع", "ستعود البيانات كما كانت قبل " + (x.label.isEmpty() ? "آخر استعادة" : x.label) + " (" + Model.date(x.time) + "). أي حركات سُجّلت بعد ذلك ستُفقد. هل تريد المتابعة؟"
                + (Snapshot.syncImpact(db).isEmpty() ? "" : "\n\n" + Snapshot.syncImpact(db)), "تراجع"))
            return;
        Ui.busy(this, "جارٍ إرجاع البيانات…", p -> Snapshot.revert(app.ctx, db, x.slot), fresh -> {
            if (!Snapshot.lastSyncNote.isEmpty()) Ui.info(this, "التراجع", Snapshot.lastSyncNote);
            SmartApp.db = fresh;
            Main.restart(app, fresh);
        });
    }
}
