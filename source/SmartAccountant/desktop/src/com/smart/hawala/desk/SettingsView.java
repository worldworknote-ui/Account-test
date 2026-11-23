package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * الإعدادات (المحفوظة في البيانات نفسها، فتنتقل مع النسخة الاحتياطية بين الهاتف والماك): اسم المكتب والترحيب والألوان،
 * العملات ومنازلها، أسعار الصرف، كلمات العملات العامية، تأكيد الحركة، الاعتماد المزدوج، إقفال الفترة، رسالة المطابقة،
 * رمز الدولة لواتساب، والفهم بالذكاء الاصطناعي (النسخة الكاملة).
 */
final class SettingsView extends View {
    SettingsView(App app) {
        super(app, "الإعدادات");
    }

    /** 10.16: إيقاف تذكير الخمول أو تذكير المطابقة لمكاتب بعينها (كالهاتف: notif_idle_off / notif_recon_off). */
    void officeNotifs() {
        java.util.List<Model.Office> offs = new java.util.ArrayList<>();
        for (Model.Office o : db.offices()) if (o.active) offs.add(o);
        if (offs.isEmpty()) {
            Ui.toast("لا مكاتب بعد");
            return;
        }
        JPanel g = new JPanel(new GridLayout(0, 3, 10, 2));
        g.setOpaque(false);
        g.add(Ui.label("المكتب", 13, Ui.muted, true));
        g.add(Ui.label("تذكير الخمول", 13, Ui.muted, true));
        g.add(Ui.label("تذكير المطابقة", 13, Ui.muted, true));
        final java.util.List<JCheckBox[]> boxes = new java.util.ArrayList<>();
        for (Model.Office o : offs) {
            JCheckBox i = Ui.check("", db.officeNotif("notif_idle_off", o.id)), r = Ui.check("", db.officeNotif("notif_recon_off", o.id));
            boxes.add(new JCheckBox[]{i, r});
            g.add(Ui.label(o.name, 14, Ui.ink, false));
            g.add(i);
            g.add(r);
        }
        JScrollPane sp = new JScrollPane(Ui.rtl(g));
        sp.setBorder(null);
        sp.setPreferredSize(new Dimension(520, Math.min(480, 40 + offs.size() * 30)));
        if (!Ui.form(this, "تذكيرات المكاتب (أزل العلامة لإيقاف التذكير لمكتب)", sp, "حفظ")) return;
        for (int k = 0; k < offs.size(); k++) {
            db.setOfficeNotif("notif_idle_off", offs.get(k).id, boxes.get(k)[0].isSelected());
            db.setOfficeNotif("notif_recon_off", offs.get(k).id, boxes.get(k)[1].isSelected());
        }
        Ui.toast("حُفظت تذكيرات المكاتب");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();

        // المكتب والمظهر
        if (can(Auth.Perm.SETTINGS) || can(Auth.Perm.DESIGN)) {
            Ui.Card c = Ui.card();
            c.add(Ui.heading("المكتب والمظهر"));
            Ui.Field office = Ui.field("اسم مكتبك (يظهر في التقارير والكشوف)"), title = Ui.field("اسم البرنامج في الأعلى"), welcome = Ui.field("رسالة الترحيب في الرئيسية");
            Ui.Field issuer = Ui.field("عنوان/هاتف تحت اسم مكتبك في التقارير"), footer = Ui.field("تذييل التقارير");
            office.setText(db.setting("office_name", ""));
            title.setText(db.setting("app_title", ""));
            welcome.setText(db.setting("welcome", ""));
            issuer.setText(db.setting("st_issuer_info", ""));
            footer.setText(db.setting("st_footer", ""));
            JPanel g = new JPanel(new GridLayout(0, 2, 12, 8));
            g.setOpaque(false);
            g.setAlignmentX(Component.RIGHT_ALIGNMENT);
            g.add(Ui.labeled("اسم المكتب", office));
            g.add(Ui.labeled("اسم البرنامج", title));
            g.add(Ui.labeled("رسالة الترحيب", welcome));
            g.add(Ui.labeled("معلومات المكتب في التقارير", issuer));
            g.add(Ui.labeled("تذييل التقارير", footer));
            c.add(g);
            c.add(Ui.gap(8));
            JPanel colors = Ui.flow(colorBtn("اللون الأساسي", "primary_color", Ui.primary), colorBtn("لون التمييز", "accent_color", Ui.accent),
                    colorBtn("لون الخلفية", "bg_color", Ui.bg), Ui.ghost("الألوان الافتراضية", e -> {
                        for (String k : new String[]{"primary_color", "accent_color", "bg_color"}) db.deleteSetting(k);
                        app.reloadAll();
                    }));
            c.add(colors);
            c.add(Ui.gap(8));
            c.add(Ui.flow(Ui.primary("حفظ", e -> {
                db.setSetting("office_name", office.val());
                db.setSetting("app_title", title.val());
                db.setSetting("welcome", welcome.val());
                db.setSetting("st_issuer_info", issuer.val());
                db.setSetting("st_footer", footer.val());
                Ui.toast("حُفظت الإعدادات");
                app.reloadAll();
                app.go("settings");
            })));
            add(c);
            space(12);
        }

        // العملات
        Ui.Card cc = Ui.card();
        cc.add(Ui.heading("العملات"));
        DefaultTableModel m = new DefaultTableModel(new Object[]{"الرمز", "الاسم", "المنازل العشرية", "مفعّلة", "سعر الدولار للوحدة"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        Map<String, Db.Rate> rates = db.rates();
        List<Model.Currency> curs = db.currencies(true);
        for (Model.Currency c : curs) {
            Db.Rate r = rates.get(c.code);
            m.addRow(new Object[]{c.code, c.name, c.decimals, c.active ? "نعم" : "لا", r == null || r.usd == null ? "—" : r.usd.stripTrailingZeros().toPlainString() + (r.manual != null ? " (يدوي)" : "")});
        }
        JTable t = Ui.table(m);
        Ui.widths(t, 80, 220, 110, 70, 180);
        JScrollPane sp = Ui.tableBox(t);
        sp.setPreferredSize(new Dimension(700, Math.min(10, curs.size()) * 32 + 36));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp.getPreferredSize().height));
        cc.add(sp);
        cc.add(Ui.gap(8));
        cc.add(Ui.flow(Ui.primary("عملة جديدة…", e -> currency(null)), Ui.ghost("تعديل المحددة…", e -> {
            int r = t.getSelectedRow();
            if (r >= 0) currency(curs.get(t.convertRowIndexToModel(r)));
            else Ui.toast("اختر عملة من الجدول");
        }), Ui.ghost("سعر الصرف للمحددة…", e -> {
            int r = t.getSelectedRow();
            if (r >= 0) rate(curs.get(t.convertRowIndexToModel(r)).code);
            else Ui.toast("اختر عملة من الجدول");
        })));
        cc.add(Ui.para("أسعار الصرف تُستعمل لتقويم الحدود بالدولار وفرق العملة التقديري والاعتماد المزدوج فقط؛ لا تغيّر أي رصيد.", 12.5f, Ui.muted, w));
        add(cc);
        space(12);

        // كلمات العملات
        Ui.Card aw = Ui.card();
        aw.add(Ui.heading("كلمات العملات العامية (لفهم الرسائل)"));
        Map<String, List<String>> aliases = MessageParser.aliasesFrom(db.setting("cur_aliases", ""));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : aliases.entrySet()) sb.append(e.getKey()).append(": ").append(Model.join(e.getValue(), "، ")).append("\n");
        aw.add(Ui.para(sb.length() == 0 ? "لا توجد كلمات مضافة. مثال: «يوز» = USDT." : sb.toString().trim(), 13.5f, Ui.ink, w));
        aw.add(Ui.flow(Ui.ghost("إضافة كلمة…", e -> alias(aliases)), Ui.ghost("حذف كلمة…", e -> removeAlias(aliases))));
        add(aw);
        space(12);

        // 10.18: الوقت الموحّد — توقيت المكتب لكل الأجهزة، وتصحيح ساعة الجهاز تلقائيًا
        {
            Ui.Card ck = Ui.card();
            ck.add(Ui.heading("الوقت الموحّد"));
            for (String line : db.clockLines()) ck.add(Ui.para(line, 13.5f, Ui.ink, w));
            ck.add(Ui.para("كل الأجهزة تعرض التواريخ وتحسب «اليوم» بتوقيت المكتب، ولو كان المحاسب في بلد آخر. وساعة كل جهاز تُصحَّح من وقت Google عند كل مزامنة "
                    + "(ويبقى التصحيح محفوظًا عند العمل بلا إنترنت)، فلا تُسجَّل حركة بوقت خاطئ بسبب ساعة جهاز غير مضبوطة.", 12.5f, Ui.muted, w));
            if (can(Auth.Perm.SETTINGS)) ck.add(Ui.flow(Ui.ghost("تغيير توقيت المكتب…", e -> pickZone())));
            add(ck);
            space(12);
        }

        // الحركات
        if (can(Auth.Perm.SETTINGS)) {
            Ui.Card tx = Ui.card();
            tx.add(Ui.heading("الحركات"));
            JCheckBox confirm = Ui.check("نافذة تأكيد قبل تسجيل كل حركة", "1".equals(db.setting("confirm_tx", "1")));
            confirm.addActionListener(e -> db.setSetting("confirm_tx", confirm.isSelected() ? "1" : "0"));
            tx.add(confirm);
            tx.add(Ui.para("اعتماد الحركات صار لكل مستخدم: «المستخدمون ← الصلاحيات والتعديل ← حركاته تحتاج اعتمادًا».", 13, Ui.muted, 520));
            tx.add(Ui.gap(10));
            long lockBefore = db.settingLong("lock_before", 0);
            tx.add(Ui.label(lockBefore > 0 ? "الحركات قبل " + Model.day(lockBefore) + " مقفلة: لا تُلغى ولا تُعدَّل ولا تُسجَّل حركة بتاريخ قبلها." : "لا توجد فترة محاسبية مقفلة.", 14, Ui.ink, false));
            Ui.DateField lockDate = new Ui.DateField(lockBefore > 0 ? lockBefore - 1 : 0);
            lockDate.setMaximumSize(new Dimension(260, 40));
            tx.add(Ui.row(Ui.label("إقفال حتى نهاية يوم", 13, Ui.muted, true), lockDate, Ui.ghost("إقفال", e -> {
                try {
                    long until = lockDate.endExclusive();
                    if (until <= 0) throw new IllegalArgumentException("اختر التاريخ");
                    if (until > Clock.now() + 86400000L) throw new IllegalArgumentException("لا يمكن إقفال تاريخ في المستقبل");
                    if (!Ui.confirm(this, "إقفال الفترة", "لن يمكن إلغاء أو تعديل أي حركة قبل " + Model.day(until) + "، ولا تسجيل حركة بتاريخ قبله. متابعة؟", "إقفال")) return;
                    db.setLockBefore(until);
                    refresh();
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            }), Ui.ghost("فتح الإقفال", e -> {
                if (Ui.confirm(this, "فتح الإقفال", "تُفتح كل الفترات للتعديل. متابعة؟", "فتح")) {
                    db.setLockBefore(0);
                    refresh();
                }
            })));
            tx.add(Ui.gap(10));
            // 10.16: مفتاح استخراج معلومات رسائل واتساب (كان على الهاتف وحده)
            JCheckBox waParse = Ui.check("استخراج معلومات رسائل واتساب عند لصقها في تسجيل الحركة (المبلغ والعملة والاسم…)", !"0".equals(db.setting("wa_parse", "1")));
            waParse.addActionListener(e -> db.setSetting("wa_parse", waParse.isSelected() ? "1" : "0"));
            tx.add(waParse);
            JCheckBox clip = Ui.check("عرض الرسالة المنسوخة في الصفحة الرئيسية عند العودة إلى البرنامج", !"0".equals(db.setting("desk_clip_peek", "1")));
            clip.addActionListener(e -> db.setSettingQuiet("desk_clip_peek", clip.isSelected() ? "1" : "0"));
            tx.add(clip);
            tx.add(Ui.gap(10));
            Ui.Field cc2 = Ui.field("963");
            cc2.setText(db.setting("wa_country", "963"));
            cc2.setMaximumSize(new Dimension(120, 40));
            tx.add(Ui.row(Ui.label("رمز الدولة لأرقام المكاتب المحلية (واتساب)", 13, Ui.muted, true), cc2, Ui.ghost("حفظ", e -> {
                db.setSetting("wa_country", cc2.val().replaceAll("[^0-9]", ""));
                Ui.toast("حُفظ");
            })));
            add(tx);
            space(12);
        }

        // 10.16: قفل البرنامج بكلمة سر (بلا نظام مستخدمين) ومهلة القفل بعد الخمول
        if (can(Auth.Perm.SETTINGS)) {
            Ui.Card lc = Ui.card();
            lc.add(Ui.heading("قفل البرنامج"));
            boolean users = db.usersEnabled();
            lc.add(Ui.para(users ? "نظام المستخدمين مفعّل: الدخول برمز كل مستخدم. المهلة أدناه تقفل البرنامج بعد الخمول."
                    : DeskLock.on(db) ? "البرنامج مقفل بكلمة سر على هذا الجهاز: تُطلب عند الفتح وبعد الخمول." : "البرنامج يُفتح بلا قفل على هذا الجهاز. يمكنك تعيين كلمة سر تُطلب عند الفتح وبعد الخمول.",
                    13.5f, Ui.muted, w));
            final long[] vals = {0, 1, 2, 5, 10, 15, 30, 60};
            String[] names = {"لا يقفل تلقائيًا", "دقيقة", "دقيقتان", "5 دقائق", "10 دقائق", "15 دقيقة", "30 دقيقة", "ساعة"};
            JComboBox<String> mins = Ui.combo(names);
            long cur = db.settingLong("desk_lock_min", 5);
            for (int i = 0; i < vals.length; i++) if (vals[i] == cur) mins.setSelectedIndex(i);
            mins.addActionListener(e -> db.setSettingQuiet("desk_lock_min", "" + vals[mins.getSelectedIndex()]));
            mins.setMaximumSize(new Dimension(220, 40));
            lc.add(Ui.row(Ui.label("القفل بعد الخمول", 13, Ui.muted, true), mins, users ? Box.createHorizontalStrut(1)
                    : Ui.ghost(DeskLock.on(db) ? "تغيير كلمة السر أو إلغاء القفل…" : "تعيين كلمة سر…", e -> DeskLock.setup(this, db, this::refresh))));
            add(lc);
            space(12);
        }

        // 10.16: الإشعارات والتذكيرات (كانت على الهاتف وحده)
        {
            Ui.Card nc = Ui.card();
            nc.add(Ui.heading("الإشعارات والتذكيرات"));
            nc.add(Ui.para("إشعار النظام (ويندوز/ماك) مع رسالة داخل البرنامج. التذكيرات مرة في اليوم داخل ساعات الإشعار. إعدادات هذا الجهاز وحده.", 13.5f, Ui.muted, w));
            String[][] tg = {{"notif_on", "تشغيل الإشعارات على هذا الجهاز"}, {"notif_sync_pending", "حركة من جهاز آخر بانتظار اعتمادي"},
                    {"notif_sync_review", "تعارضات المزامنة وتنبيهاتها"}, {"notif_sync_devices", "دخول جهاز إلى المزامنة أو خروجه (للمدير)"},
                    {"notif_idle", "مكتب لم تُسجَّل عليه حركات منذ يوم كامل"}, {"notif_recon", "مكتب لم تُرسل له مطابقة رصيد"},
                    {"notif_backup", "عند إنشاء نسخة احتياطية تلقائية"}};
            JPanel ng = new JPanel(new GridLayout(0, 2, 12, 2));
            ng.setOpaque(false);
            ng.setAlignmentX(Component.RIGHT_ALIGNMENT);
            for (String[] tog : tg) {
                JCheckBox cb = Ui.check(tog[1], !"0".equals(db.setting(tog[0], "1")));
                cb.addActionListener(e -> db.setSettingQuiet(tog[0], cb.isSelected() ? "1" : "0"));
                ng.add(cb);
            }
            nc.add(ng);
            Ui.Field rd = Ui.numeric("7"), hf = Ui.numeric("9"), ht = Ui.numeric("21"), br = Ui.numeric("3"), bt = Ui.numeric("0");
            bt.setText("" + db.settingLong("desk_backup_remind_tx", 0));
            rd.setText("" + db.settingLong("notif_recon_days", 7));
            hf.setText("" + db.settingLong("notif_hour_from", 9));
            ht.setText("" + db.settingLong("notif_hour_to", 21));
            br.setText("" + db.settingLong("desk_backup_remind_days", 3));
            JPanel nf = new JPanel(new GridLayout(0, 4, 12, 8));
            nf.setOpaque(false);
            nf.setAlignmentX(Component.RIGHT_ALIGNMENT);
            nf.add(Ui.labeled("تذكير المطابقة بعد (أيام)", rd));
            nf.add(Ui.labeled("ساعات الإشعار: من", hf));
            nf.add(Ui.labeled("إلى", ht));
            nf.add(Ui.labeled("ذكّرني بالنسخ كل (أيام، 0 = لا)", br));
            nf.add(Ui.labeled("وبعد كل (حركة جديدة، 0 = لا)", bt));
            nc.add(nf);
            nc.add(Ui.flow(Ui.primary("حفظ", e -> {
                try {
                    db.setSettingQuiet("notif_recon_days", "" + Math.max(1, Math.min(60, Long.parseLong(rd.val()))));
                    db.setSettingQuiet("notif_hour_from", "" + Math.max(0, Math.min(23, Long.parseLong(hf.val()))));
                    db.setSettingQuiet("notif_hour_to", "" + Math.max(1, Math.min(24, Long.parseLong(ht.val()))));
                    db.setSettingQuiet("desk_backup_remind_days", "" + Math.max(0, Math.min(60, Long.parseLong(br.val()))));
                    db.setSettingQuiet("desk_backup_remind_tx", "" + Math.max(0, Math.min(5000, Long.parseLong(bt.val().isEmpty() ? "0" : bt.val()))));
                    Ui.toast("حُفظت إعدادات الإشعارات");
                } catch (NumberFormatException ex) {
                    Ui.toast("اكتب أرقامًا صحيحة");
                }
            }), Ui.ghost("إشعار تجريبي", e -> DeskNotify.show("المحاسب الذكي", "هكذا تظهر الإشعارات على هذا الجهاز.")),
                    Ui.ghost("استثناء مكاتب من التذكير…", e -> officeNotifs()), Ui.ghost("سجل الإشعارات…", e -> NotifLog.open(this, db))));
            add(nc);
            space(12);
        }

        // 10.16: مصدر أسعار الصرف من الإنترنت (كان على الهاتف وحده)
        if (can(Auth.Perm.SETTINGS)) {
            Ui.Card xc = Ui.card();
            xc.add(Ui.heading("مصدر أسعار الصرف"));
            xc.add(Ui.para("رابط خدمة الأسعار (اتركه فارغًا للمصدر الافتراضي). الأسعار للتقويم التقريبي بالدولار فقط ولا تغيّر أي رصيد.", 13.5f, Ui.muted, w));
            Ui.Field ru = Ui.field("https://…");
            ru.setText(db.setting("rates_url", ""));
            xc.add(Ui.row(ru, Ui.ghost("حفظ", e -> {
                String url = ru.val();
                if (!url.isEmpty() && !url.startsWith("https://")) {
                    Ui.toast("الرابط يجب أن يبدأ بـ https://");
                    return;
                }
                if (url.isEmpty()) db.deleteSetting("rates_url");
                else db.setSetting("rates_url", url);
                Ui.toast("حُفظ مصدر الأسعار");
            }), Ui.ghost("تحديث الأسعار الآن", e -> Ui.busy(this, "جارٍ تحديث الأسعار…", p -> ExchangeRates.refresh(db), r -> Ui.toast("حُدّثت الأسعار ✓")))));
            add(xc);
            space(12);
        }

        // رسالة المطابقة
        if (can(Auth.Perm.DESIGN) || can(Auth.Perm.SETTINGS)) {
            Ui.Card rc = Ui.card();
            rc.add(Ui.heading("رسالة مطابقة الرصيد (واتساب)"));
            Reconcile.Template tp = Reconcile.Template.from(db.settings(Reconcile.Template.KEYS));
            rc.add(Ui.para("النص الذي يُرسل للمكتب في واتساب: التحية والعنوان والخاتمة، شكل سطر كل عملة (المبلغ قبل العملة أو بعدها…)، "
                    + "الأصفار العشرية، السطر الفارغ بين العملات، العلامات والخطوط الفاصلة.", 13.5f, Ui.muted, w));
            JTextArea pv = Ui.area(8);
            pv.setEditable(false);
            pv.setText(Reconcile.text(tp, "كاش شام", db.setting("office_name", ""), Clock.now(), Reconcile.sampleRows()));
            pv.setCaretPosition(0);
            JScrollPane pvs = Ui.areaBox(pv);
            pvs.setAlignmentX(Component.RIGHT_ALIGNMENT);
            pvs.setMaximumSize(new Dimension(460, 230));
            rc.add(pvs);
            rc.add(Ui.flow(Ui.primary("تصميم الرسالة…", e -> ReconDesign.open(this, db, this::refresh))));
            add(rc);
            space(12);
        }

        // الذكاء الاصطناعي
        if (can(Auth.Perm.SETTINGS)) {
            Ui.Card ai = Ui.card();
            ai.add(Ui.heading(AiExtract.NAME + " (اختياري)"));
            ai.add(Ui.para(AiExtract.EXPLAIN, 13, Ui.muted, w));
            if (!License.current.ai()) {
                ai.add(Ui.para("من ميزات النسخة المدفوعة. المحرك المدمج لاستخراج معلومات رسائل واتساب (ومنها «راجع») مجاني ويعمل دائمًا دون إنترنت.", 13.5f, Ui.muted, w));
            } else {
                JCheckBox on = Ui.check("تفعيل زر «" + AiExtract.BUTTON + "» في تسجيل الحركة", "1".equals(db.setting("ai_on", "0")));
                JComboBox<String> prov = Ui.combo(AiExtract.PROVIDER_NAMES);
                prov.setSelectedIndex(AiExtract.providerIndex(db.setting("ai_provider", AiExtract.CLAUDE)));
                JPasswordField key = Ui.password();
                Ui.Field model = Ui.field("اسم النموذج (اختياري لـ Claude)"), url = Ui.field("عنوان الخدمة (لـ OpenAI وGemini اختياري، ولـ «مخصّص» مطلوب)");
                model.setText(db.setting("ai_model", ""));
                url.setText(db.setting("ai_url", ""));
                ai.add(on);
                JPanel g = new JPanel(new GridLayout(0, 2, 12, 8));
                g.setOpaque(false);
                g.setAlignmentX(Component.RIGHT_ALIGNMENT);
                g.add(Ui.labeled("الخدمة", prov));
                g.add(Ui.labeled("مفتاح الخدمة (يُحفظ مشفّرًا؛ فارغ = يبقى كما هو)", key));
                g.add(Ui.labeled("النموذج", model));
                g.add(Ui.labeled("العنوان", url));
                ai.add(g);
                ai.add(Ui.flow(Ui.primary("حفظ", e -> {
                    db.setSetting("ai_on", on.isSelected() ? "1" : "0");
                    db.setSetting("ai_provider", AiExtract.PROVIDERS[Math.max(0, prov.getSelectedIndex())]);
                    String k = new String(key.getPassword()).trim();
                    if (!k.isEmpty()) db.setSettingQuiet("ai_key", Crypto.enc(k));
                    db.setSetting("ai_model", model.val());
                    db.setSetting("ai_url", url.val());
                    Ui.toast("حُفظ");
                })));
            }
            add(ai);
        }
        // 10.4: إعادة التعيين
        if (DeskReset.allowed(db)) {
            space(14);
            Ui.Card rc = Ui.card();
            rc.add(Ui.heading("إعادة تعيين البرنامج"));
            rc.add(Ui.para("يصبح البرنامج على هذا الجهاز كأنه مثبت للتو: تُحذف البيانات مع إبقاء الرخصة أو بدونها. الأجهزة الأخرى في المزامنة لا تتأثر.", 13, Ui.muted, textWidth() - 40));
            rc.add(Ui.flow(Ui.danger("إعادة تعيين البرنامج…", e -> DeskReset.start(app, this))));
            add(rc);
        }
        done();
    }

    Ui.Btn colorBtn(String label, String key, Color cur) {
        Ui.Btn b = Ui.button(label, cur, null);
        b.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, label, cur);
            if (c == null) return;
            db.setSetting(key, Ui.hex(c));
            app.reloadAll();
            app.go("settings");
        });
        return b;
    }

    void currency(Model.Currency old) {
        if (!require(Auth.Perm.CURRENCIES)) return;
        JPanel c = Ui.column();
        Ui.Field code = Ui.field("مثل USD أو USDT"), name = Ui.field("الاسم بالعربية");
        JComboBox<Integer> dec = Ui.combo(new Integer[]{0, 1, 2, 3, 4, 6, 8});
        JCheckBox active = Ui.check("مفعّلة", old == null || old.active);
        if (old != null) {
            code.setText(old.code);
            code.setEditable(false);
            name.setText(old.name);
            dec.setSelectedItem(old.decimals);
        } else dec.setSelectedItem(2);
        c.add(Ui.labeled("الرمز", code));
        c.add(Ui.gap(6));
        c.add(Ui.labeled("الاسم", name));
        c.add(Ui.gap(6));
        c.add(Ui.labeled("المنازل العشرية", dec));
        c.add(active);
        if (old != null && db.currencyUsed(old.code)) c.add(Ui.para("العملة مستعملة في حركات أو في الصندوق؛ لا تتغير منازلها العشرية (المبالغ محفوظة بأصغر وحدة، فتغييرها يغيّر قيمتها).", 12, Ui.muted, 340));
        c.setPreferredSize(new Dimension(380, c.getPreferredSize().height));
        while (Ui.form(this, old == null ? "عملة جديدة" : "تعديل " + old.code, Ui.rtl(c), "حفظ")) {
            try {
                String k = code.val().toUpperCase(Locale.US);
                if (!k.matches("[A-Z0-9]{2,10}")) throw new IllegalArgumentException("الرمز من 2 إلى 10 أحرف لاتينية أو أرقام");
                if (name.val().isEmpty()) throw new IllegalArgumentException("اكتب اسم العملة");
                if (old != null && db.currencyUsed(old.code) && (Integer) dec.getSelectedItem() != old.decimals)
                    throw new IllegalArgumentException("لا يمكن تغيير المنازل العشرية لعملة عليها حركات (تتغير قيمة المبالغ).");
                db.saveCurrency(k, name.val(), (Integer) dec.getSelectedItem(), active.isSelected());
                refresh();
                return;
            } catch (Exception e) {
                Ui.error(this, e);
            }
        }
    }

    void rate(String code) {
        String v = Ui.input(this, "سعر الصرف: " + code, "كم دولارًا تساوي 1 " + code + "؟ (مثال لليورو: 1.08) — فارغ = حذف السعر اليدوي", "");
        if (v == null) return;
        try {
            // 10.5 (AUDIT-021): «1.085» لليورو = واحد وخمسة وثمانون من ألف، لا 1085
            BigDecimal r = v.trim().isEmpty() ? null : CashBox.rateDecimal(v);
            if (!v.trim().isEmpty() && r == null) throw new IllegalArgumentException("السعر غير مفهوم: اكتبه مثل 1.085");
            db.setManualRate(code, r);
            refresh();
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }

    void alias(Map<String, List<String>> aliases) {
        String w = Ui.input(this, "كلمة عملة", "الكلمة كما تُكتب في الرسائل (مثل: يوز)", "");
        if (w == null || w.trim().isEmpty()) return;
        List<String> codes = new ArrayList<>();
        for (Model.Currency c : db.currencies(true)) codes.add(c.code);
        int k = Ui.choose(this, "تعني أي عملة؟", "«" + w.trim() + "» تعني:", codes.toArray(new String[0]));
        if (k < 0) return;
        aliases.computeIfAbsent(codes.get(k), z -> new ArrayList<>()).add(w.trim());
        db.setSetting("cur_aliases", MessageParser.aliasesTo(aliases));
        refresh();
    }

    void removeAlias(Map<String, List<String>> aliases) {
        String w = Ui.input(this, "حذف كلمة", "اكتب الكلمة التي تريد حذفها", "");
        if (w == null) return;
        for (List<String> l : aliases.values()) l.remove(w.trim());
        db.setSetting("cur_aliases", MessageParser.aliasesTo(aliases));
        refresh();
    }

    /** 10.18: اختيار توقيت المكتب من قائمة قابلة للبحث (المناطق الشائعة أولًا ثم كل مناطق العالم). */
    void pickZone() {
        List<String> ids = new ArrayList<>();
        ids.add("");
        for (String[] z : Model.ZONES) ids.add(z[0]);
        for (String id : java.util.TimeZone.getAvailableIDs()) if (id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/") && !ids.contains(id)) ids.add(id);
        String[] labels = new String[ids.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Model.zoneLabel(ids.get(i));
        JComboBox<String> box = Ui.combo(labels);
        box.setSelectedIndex(Math.max(0, ids.indexOf(Model.zoneId())));
        JPanel col = Ui.column();
        col.add(Ui.para("اختر البلد الذي يعمل المكتب بتوقيته. يسري على كل الأجهزة بعد المزامنة: التواريخ المعروضة، وبداية اليوم ونهايته في التقارير والإقفال والصندوق. "
                + "لا يغيّر لحظة تسجيل أي حركة محفوظة.", 13, Ui.muted, 460));
        col.add(Ui.gap(8));
        col.add(box);
        JOptionPane p = new JOptionPane(col, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{"حفظ", "إلغاء"}, "حفظ");
        Ui.show(this, p, "توقيت المكتب");
        if (!"حفظ".equals(p.getValue())) return;
        try {
            db.setOfficeZone(ids.get(box.getSelectedIndex()));
            app.changed();
            Ui.toast("توقيت المكتب: " + Model.zoneLabel(Model.zoneId()));
            refresh();
        } catch (Exception ex) {
            Ui.error(this, ex);
        }
    }
}
