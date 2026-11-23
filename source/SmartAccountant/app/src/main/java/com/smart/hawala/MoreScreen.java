package com.smart.hawala;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.InputStream;

/**
 * تبويب «المزيد»: كل ما ليس عملًا يوميًا مجمّعًا في مجموعات واضحة
 * (التقارير، البيانات، تسجيل الحركات، الإدارة والأمان، المظهر). يحل محل شاشة الإعدادات الطويلة.
 */
final class MoreScreen {
    private final MainActivity a;

    MoreScreen(MainActivity a) { this.a = a; }

    /**
     * 10.2: «المزيد» مرتّب بمجموعات لا تتكرر: حسابي، التقارير، الصندوق والعملاء، العملات، البيانات والمزامنة، تسجيل الحركات،
     * الإشعارات، الأمان والإدارة، المظهر والتخصيص، حول التطبيق. ما لا يملك المستخدم صلاحيته لا يظهر له.
     */
    void show() {
        a.here(this::show);
        a.rootScreen("المزيد", "more");
        Model.User u = SmartApp.currentUser;
        int ic = a.primaryInk;

        // 10.3: لكل مستخدم ما يخصه وجهازه، دون أي صلاحية إضافية
        a.section(u != null ? "حسابي وجهازي" : "هذا الجهاز", null, null);
        LinearLayout me = a.group();
        if (u != null) {
            a.groupRow(me, R.drawable.ic_user, ic, u.name, Auth.describe(a.db.user(u.id)), null, null);
            a.groupRow(me, R.drawable.ic_key, ic, "تغيير رمزي السري", null, null, v -> a.usersScreen.changeOwnPin());
            // 10.5 (§7): مكاتب المحاسب و«بانتظار تحققي»
            if (a.mineScreen.available()) {
                int waiting = a.db.awaitingMyCheck(60, 200).size();
                a.groupRow(me, R.drawable.ic_check2, ic, "مكاتبي والتحقق", "مكاتبك، وآخر رسالة نُقلت لكل منها، والحركات بانتظار تحققك",
                        waiting > 0 ? waiting + " بانتظارك" : null, v -> a.mineScreen.show());
            }
        }
        a.groupRow(me, R.drawable.ic_lock, a.appLock.on() ? ic : a.red, "قفل التطبيق", "بصمة أو كلمة سر للتطبيق، ومتى يُطلب", AppLock.label(a), v -> a.appLock.settings());
        a.groupRow(me, R.drawable.ic_bell, ic, "الإشعارات", "حركات تحتاج اعتمادك، النسخ التلقائي، تذكيرات المكاتب", Notifier.on(a.db) ? "مفعّلة" : "متوقفة",
                v -> a.notifScreen.show());
        a.groupRow(me, R.drawable.ic_more, ic, "الشريط السفلي", "النمط والألوان والأزرار وقائمة «+»", null, v -> a.tabsScreen.show());
        if (u != null) a.groupRow(me, R.drawable.ic_logout, a.red, "تسجيل الخروج", a.db.rememberedUser() != null ? "ويُنسى «تذكرني» على هذا الجهاز" : null, null, v -> a.usersScreen.logout());

        if (a.can(Auth.Perm.VIEW)) {
            a.section("التقارير", null, null);
            LinearLayout g = a.group();
            if (a.can(Auth.Perm.REPORTS)) a.groupRow(g, R.drawable.ic_report, ic, "مركز التقارير", "جاهزة ومخصصة بالفترة التي تختارها، مع التصدير PDF و Excel", null, v -> a.reportsScreen.show());
            a.groupRow(g, R.drawable.ic_scale, ic, "أرصدة كل المكاتب", "لنا وعلينا لكل عملة، مع التقويم بالدولار", null, v -> a.balancesScreen.show());
            if (a.can(Auth.Perm.REPORTS)) a.groupRow(g, R.drawable.ic_calendar, ic, "التقرير اليومي", "حركات يوم وأجوره ومجاميعه", null, v -> a.dailyScreen.show(Clock.now()));
        }

        if (a.can(Auth.Perm.CASH)) {
            a.section("الصندوق والعملاء", null, null);
            LinearLayout cg = a.group();
            CashBox.Config cc = a.db.cashConfig();
            a.groupRow(cg, R.drawable.ic_cash, a.goldInk, cc.on ? cc.name : "صندوق المكتب", cc.on ? "النقد بكل عملة: استلام وتسليم وبيع وشراء ومصاريف وجرد"
                    : "اضبط النقد الموجود لديك بعيدًا عن أرصدة المكاتب", cc.on ? null : "إعداد", v -> a.cashScreen.show());
            if (cc.on) a.groupRow(cg, R.drawable.ic_users, ic, "حسابات العملاء", "رصيد كل عميل بكل عملة وكشفه", null, v -> a.cashScreen.clients.list());
        }

        if (a.can(Auth.Perm.VIEW) || a.can(Auth.Perm.CURRENCIES) || a.can(Auth.Perm.METALS)) {
            a.section("العملات والمعادن والأسعار", null, null);
            LinearLayout cu = a.group();
            if (a.can(Auth.Perm.CURRENCIES) || a.can(Auth.Perm.METALS))
                a.groupRow(cu, R.drawable.ic_coins, ic, "العملات والمعادن", "إضافة عملة أو معدن (ذهب، فضة…) وتفعيلها", null, v -> a.officesScreen.showCurrencies());
            a.groupRow(cu, R.drawable.ic_rates, ic, "أسعار الصرف", "للتقويم التقريبي بالدولار فقط — لا تغيّر الأرصدة", null, v -> a.officesScreen.showRates(this::show));
            if (a.can(Auth.Perm.SETTINGS)) a.groupRow(cu, R.drawable.ic_tag, ic, "كلمات العملات", "مثل: يوز = USDT", null, v -> CurrencyWords.screen(a, this::show));
        }

        boolean sync = a.can(Auth.Perm.SYNC) || SyncEngine.enabled(a.db);
        if (sync || a.can(Auth.Perm.BACKUP) || a.can(Auth.Perm.RESTORE)) {
            a.section("البيانات والمزامنة", null, null);
            LinearLayout d = a.group();
            if (sync) {
                SyncEngine.Status ss = SyncEngine.status(a.db);
                a.groupRow(d, R.drawable.ic_sync, ic, "المزامنة بين الأجهزة", ss.enabled ? "مفعّلة (" + SyncScreen.kindName(a.db) + ") — آخر مزامنة " + MainActivity.ago(ss.lastSync)
                        : "بياناتك واحدة على الهاتف والماك والويندوز", ss.enabled ? null : (License.current.sync() ? "تفعيل" : "مدفوعة"), v -> a.syncScreen.show());
            }
            if (a.can(Auth.Perm.BACKUP)) {
                long last = a.db.settingLong("last_backup", 0);
                a.groupRow(d, R.drawable.ic_shield, ic, "النسخ الاحتياطي والاستعادة", "آخر نسخة: " + MainActivity.ago(last), null, v -> a.backupScreen.show());
            }
            if (a.can(Auth.Perm.RESTORE)) a.groupRow(d, R.drawable.ic_migrate, ic, "مساعد النقل من برنامج آخر", "انقل مكاتبك وأرصدتك من Excel أو برنامج الميزان بيست ريت", null, v -> migration());
        }

        if (a.can(Auth.Perm.SETTINGS)) {
            a.section("تسجيل الحركات", null, null);
            LinearLayout t = a.group();
            toggle(t, R.drawable.ic_chat, "استخراج معلومات رسائل واتساب", "البيان أول النموذج مع زر «إضافة المعلومات للحقول»", "wa_parse", "1",
                    on -> a.toast(on ? "مفعّلة: البيان أول النموذج، وزر «إضافة المعلومات للحقول»." : "متوقفة: نموذج التسجيل بالترتيب العادي والبيان حقل اختياري."));
            // 12.1: نقل الرسائل من واتساب على الكمبيوتر
            if (WaMove.desktop()) toggle(t, R.drawable.ic_paste, "مراقبة الحافظة", "حين تنسخ رسائل من واتساب تظهر بطاقة «نقل N رسائل» في الرئيسية", "clip_watch", "1",
                    on -> { if (on) a.waMove.startWatch(); else a.waMove.stopWatch(); });
            // 12.4: على أندرويد زرا التجميع والقص اختياريان (الافتراضي بدونهما)
            if (!WaMove.desktop()) {
                toggle(t, R.drawable.ic_collect, "زر «وضع التجميع»", "في الرئيسية: انسخ الرسائل واحدة تلو الأخرى ثم «نقل»", "wa_btn_collect", "0", null);
                toggle(t, R.drawable.ic_cut, "زر «قص الرسائل»", "في الرئيسية ونموذج التسجيل: قطع نص فيه عدة رسائل ملزوقة", "wa_btn_cut", "0", null);
            }
            a.groupRow(t, R.drawable.ic_search, ic, "فحص الحافظة", "شكل ما نسخته دون محتواه، لإرساله للدعم إن لم تنفصل الرسائل", null, v -> a.waMove.inspect());
            toggle(t, R.drawable.ic_check, "ملخص للتأكيد قبل حفظ كل حركة", "نافذة تعرض ما سيُسجَّل قبل الحفظ", "confirm_tx", "1", null);
            a.groupRow(t, R.drawable.ic_sparkle, ic, AiExtract.NAME, "ذكاء اصطناعي عبر الإنترنت بمفتاحك (اختياري)", !License.current.ai() ? "النسخة الكاملة" : AiSettings.on(a.db) ? "مفعّل" : "متوقف", v -> AiSettings.screen(a, this::show));
        }

        if (a.can(Auth.Perm.USERS) || a.can(Auth.Perm.SETTINGS) || a.can(Auth.Perm.AUDIT)) {
            a.section("إدارة المكتب", null, null);
            LinearLayout s = a.group();
            if (a.can(Auth.Perm.USERS)) a.groupRow(s, R.drawable.ic_users, ic, "المستخدمون والصلاحيات", null, null, v -> a.usersScreen.showManager());
            if (a.can(Auth.Perm.AUDIT)) a.groupRow(s, R.drawable.ic_pulse, ic, "سجل العمليات", "من فعل ماذا ومتى", null, v -> a.settingsScreen.showAudit());
            if (a.can(Auth.Perm.SETTINGS)) {
                a.groupRow(s, R.drawable.ic_lock, ic, "أمان المكتب وإقفال الفترة", "لقطات الشاشة، وتوقيت المكتب، وإقفال الحركات حتى تاريخ", null, v -> a.settingsScreen.showSecurity());
                int lv = Reliability.overall(Reliability.items(a));
                a.groupRow(s, R.drawable.ic_health, Reliability.color(a, lv), "اعتمادية التطبيق وفحص البيانات", "النسخ، الفحص، المفتاح، القفل، المساحة، تمرين الاستعادة",
                        "● " + Reliability.word(lv), v -> Reliability.screen(a));
            }
        }

        // 10.12: مظهر كل مستخدم له وحده، فيخصّصه كل مستخدم؛ ما يمسّ الجميع (الشعار، الكشوفات، القوالب) لصاحب صلاحية التصميم
        {
            a.section("المظهر والتصميم", null, null);
            LinearLayout m = a.group();
            boolean shared = a.can(Auth.Perm.DESIGN);
            a.groupRow(m, R.drawable.ic_palette, ic, "الخط والألوان والوضع الليلي والاهتزاز", shared ? "والصفحة الرئيسية والشعار" : "والصفحة الرئيسية — لك وحدك", null, v -> a.designScreen.show());
            if (shared) {
                a.groupRow(m, R.drawable.ic_doc, ic, "تصميم الكشوفات المصدّرة", null, null, v -> a.exportUi.showStyle(this::show));
                a.groupRow(m, R.drawable.ic_check2, ic, "قالب رسالة مطابقة الرصيد", null, null, v -> a.reconScreen.templateScreen(this::show));
            }
        }

        a.section("حول التطبيق", null, null);
        LinearLayout ab = a.group();
        Entitlements ent = License.current;
        if (a.can(Auth.Perm.LICENSE)) a.groupRow(ab, R.drawable.ic_key, ic, "الترخيص", ent.pro ? ent.edition() + " — " + ent.lid : "النسخة المجانية — اعرف ما تضيفه النسخة الكاملة",
                ent.note.isEmpty() ? null : "تنبيه", v -> a.licenseScreen.show());
        a.groupRow(ab, R.drawable.ic_info, ic, "معلومات عن التطبيق", "الإصدار " + a.versionName() + " — ما يقدمه لك باختصار", null, v -> About.appInfo(a, this::show));
        a.groupRow(ab, R.drawable.ic_users, ic, "من نحن", About.COMPANY, null, v -> About.aboutUs(a, this::show));
        a.groupRow(ab, R.drawable.ic_phone, ic, "اتصل بنا", About.PHONE_SHOWN, null, v -> About.contact(a, this::show));

        // 10.4: إعادة التعيين (للمدير، أو لصاحب الهاتف بلا نظام مستخدمين)
        if (ResetScreen.allowed(a.db)) {
            a.section("إعادة التعيين", null, null);
            LinearLayout rs = a.group();
            a.groupRow(rs, R.drawable.ic_trash, a.red, "إعادة تعيين البرنامج", "يصبح التطبيق على هذا الهاتف كأنه مثبت للتو: حذف البيانات (مع إبقاء الرخصة أو بدونها)",
                    null, v -> a.resetScreen.start());
        }
    }

    interface OnToggle { void changed(boolean on); }

    /** سطر بمفتاح تشغيل/إيقاف لإعداد بسيط. */
    private void toggle(LinearLayout g, int icon, String title, String sub, final String key, String def, final OnToggle after) {
        LinearLayout r = a.groupRow(g, icon, a.primaryInk, title, sub, null, null);
        final Switch sw = new Switch(a);
        sw.setChecked("1".equals(a.db.setting(key, def)));
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{a.night ? a.green : a.primary, a.night ? Color.rgb(150, 160, 156) : Color.rgb(250, 250, 250)}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{Ui.mix(a.surface, a.primary, 0.5f), Ui.mix(a.surface, a.muted, 0.45f)}));
        sw.setOnCheckedChangeListener((b, c) -> {
            a.db.setSetting(key, c ? "1" : "0");
            if (after != null) after.changed(c);
        });
        r.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(v -> sw.toggle());
        r.setBackground(a.ripple(null, 0));
    }

    // ===================================================================================
    // مساعد النقل من برنامج آخر
    // ===================================================================================

    void migration() {
        if (!a.require(Auth.Perm.RESTORE)) return;
        a.here(this::migration);
        a.tabbedScreen("مساعد النقل", this::show, "more");
        LinearLayout c = a.card();
        c.addView(a.text("انقل بياناتك من برنامجك الحالي في ثلاث خطوات", 16, a.ink, true));
        c.addView(a.text("يعمل المساعد على الكمبيوتر بدون إنترنت: يقرأ ملف Excel الذي يصدّره برنامج الميزان بيست ريت (أو جدول Excel الذي تعمل عليه)، "
                + "ويحوّله إلى ملف نسخة احتياطية مشفّر بكلمة سر تختارها، ثم تستورده هنا. أداتان: «أداة النقل» للكشوفات وملفات برنامج الميزان بيست ريت، "
                + "و«الاستيراد الذكي» لأي ملف Excel تحفظ فيه حساباتك.", 14, a.muted, false));
        LinearLayout g = a.group();
        a.groupRow(g, R.drawable.ic_share, a.primaryInk, "1. أرسل أداة النقل إلى الكمبيوتر", "للكشوفات وملفات برنامج الميزان بيست ريت — عبر واتساب أو البريد، وافتحها بكروم أو إيدج", null,
                v -> shareTool("migration-tool.html", "أداة-النقل-المحاسب-الذكي.html", "أداة النقل — المحاسب الذكي"));
        a.groupRow(g, R.drawable.ic_share, a.primaryInk, "أو أرسل «الاستيراد الذكي» إلى الكمبيوتر", "لأي ملف Excel: يفهم الأوراق والأعمدة وحده، ويتحقق من مجاميع ملفك ومعادلاته قبل النقل", null,
                v -> shareTool("smart-import.html", "الاستيراد-الذكي-المحاسب-الذكي.html", "الاستيراد الذكي — المحاسب الذكي"));
        a.groupRow(g, R.drawable.ic_migrate, a.primaryInk, "2. حوّل ملفاتك على الكمبيوتر", "ملف Excel واحد، أو كشوفات حساب (ملف لكل مكتب وعملة) تختارها كلها معًا؛ راجع المطابقة ثم احفظ ملف \u200E.sba", null, null);
        a.groupRow(g, R.drawable.ic_download, a.primaryInk, "3. استورد الملف هنا", "انقل ملف \u200E.sba للهاتف ثم اضغط هنا", null, v -> importFile());
        a.notice(R.drawable.ic_shield, "عند الاستيراد تختار: «إضافة إلى بياناتي» (لا يُحذف شيء؛ تُضاف الأرصدة والحركات، والمكتب الموجود بالاسم نفسه تُضاف إليه) "
                + "أو «استبدال الكل». في الحالتين تُعرض معاينة الأرصدة قبل أي تغيير، وتُحفظ نسخة أمان يمكنك التراجع إليها.", false, null, null);
        a.hint("بعد الاستيراد قارن أرصدة المكاتب هنا بأرصدة برنامج الميزان بيست ريت: الأداة تعطيك تقريرًا بالأرصدة المتوقعة لكل مكتب وعملة.");
    }

    private void shareTool(String asset, String fileName, String title) {
        try {
            java.io.File f = new java.io.File(a.sharedDir(), fileName);
            try (InputStream in = a.getAssets().open(asset); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            a.shareFiles(java.util.Collections.singletonList(f), "text/html", title);
        } catch (Exception e) {
            a.error(new Exception("تعذر تجهيز " + title + ": " + Ui.friendly(e)));
        }
    }

    private void importFile() {
        new AlertDialog.Builder(a).setTitle("استيراد ملف النقل")
                .setMessage("اختر ملف \u200E.sba الذي أنشأته أداة النقل أو الاستيراد الذكي. سيُفحص الملف ويُعرض ملخصه قبل أي تغيير، وتُحفظ نسخة أمان من بياناتك الحالية.")
                .setPositiveButton("اختيار الملف", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    a.startForResult(i, MainActivity.OPEN_BACKUP);
                }).setNegativeButton("إلغاء", null).show();
    }
}
