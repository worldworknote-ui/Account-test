package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 8.0 (ت14): لوحة «اعتمادية التطبيق» بضوء أخضر/أصفر/أحمر: تحوّل الأعطال الصامتة إلى تنبيه ظاهر.
 * ومعها الفحص التلقائي (ت1) وتمرين الاستعادة (ت6).
 */
final class Reliability {
    private Reliability() {}

    static final int GREEN = 0, YELLOW = 1, RED = 2;
    static final long DAY = 86400000L;

    static final class Item {
        int level;
        String title, detail;
        int icon;
        String action;
        Runnable run;

        Item(int level, int icon, String title, String detail) {
            this.level = level;
            this.icon = icon;
            this.title = title;
            this.detail = detail;
        }

        Item act(String label, Runnable r) {
            action = label;
            run = r;
            return this;
        }
    }

    // ===================================================================================
    // الفحص التلقائي (ت1)
    // ===================================================================================

    /** أول فتح في اليوم: فحص سريع في الخلفية. */
    /** 12.7: بعد الانضمام أو الاستعادة يؤجَّل الفحص التلقائي ربع ساعة (لا فحص فوق التحميل). */
    static volatile long deferUntil;

    static void defer(long ms) { deferUntil = android.os.SystemClock.elapsedRealtime() + ms; }

    static void healthIfDue(final Db db) {
        if (android.os.SystemClock.elapsedRealtime() < deferUntil) return;
        long last = db.settingLong("health_last", 0);
        if (Model.day(last).equals(Model.day(Clock.now()))) return;
        healthAsync(db);
    }

    private static volatile boolean searchStarted;

    /** 12.7: يكمل نص البحث المحلي في الخلفية (دفعات قصيرة) فيكون البحث سريعًا من أول مرة. */
    static void searchIndexAsync(final Db db) {
        if (searchStarted) return;
        searchStarted = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(8000);
                db.fillSearchIndex();
            } catch (Throwable ignored) {
                // يُكمَل عند أول بحث
            } finally {
                searchStarted = false;
            }
        }, "search-index");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** بعد كل نسخة، وبعد الاستعادة. */
    static void healthAsync(final Db db) {
        Thread t = new Thread(() -> {
            try {
                db.runHealthAuto();
            } catch (Throwable ignored) {
                // تُعاد في المرة التالية
            }
        }, "health");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ===================================================================================
    // البنود
    // ===================================================================================

    static List<Item> items(final MainActivity a) {
        Db db = a.db;
        long now = Clock.now();
        List<Item> l = new ArrayList<>();

        long last = db.settingLong("last_backup", 0);
        boolean verified = "verified".equals(db.setting("last_backup_status", ""));
        int lb = last == 0 ? RED : now - last > 10 * DAY ? RED : now - last > 3 * DAY || !verified ? YELLOW : GREEN;
        l.add(new Item(lb, R.drawable.ic_shield, "آخر نسخة احتياطية" + (verified ? " متحقَّق منها" : ""),
                last == 0 ? "لا توجد نسخة بعد. الهاتف وحده لا يكفي." : MainActivity.ago(last) + (verified ? "" : " — لم تُعَد قراءتها من مكان الحفظ"))
                .act("نسخة الآن", () -> a.backupScreen.backupDialog()));

        String autoErr = db.setting("auto_backup_error", "") + db.setting("drive_backup_error", "");
        long lastAuto = db.settingLong("last_auto_backup", 0);
        if (!AutoBackup.enabled(db)) {
            l.add(new Item(YELLOW, R.drawable.ic_clock, "النسخ التلقائي متوقف", "فعّله إلى مجلد أو Google Drive حتى لا تعتمد على التذكّر.")
                    .act("الإعداد", () -> a.backupScreen.show()));
        } else {
            l.add(new Item(autoErr.isEmpty() ? GREEN : RED, R.drawable.ic_clock, autoErr.isEmpty() ? "النسخ التلقائي يعمل" : "فشل النسخ التلقائي الأخير",
                    autoErr.isEmpty() ? "كل " + AutoBackup.intervalHours(db) + " ساعة" + (AutoBackup.everyTx(db) > 0 ? " أو بعد " + AutoBackup.everyTx(db) + " حركة" : "")
                            + (lastAuto > 0 ? " — آخرها " + MainActivity.ago(lastAuto) : "") : autoErr.trim())
                    .act("التفاصيل", () -> a.backupScreen.show()));
        }

        String hs = db.setting("health_status", "");
        long hl = db.settingLong("health_last", 0);
        if (hs.isEmpty()) {
            l.add(new Item(YELLOW, R.drawable.ic_health, "فحص سلامة البيانات", "لم يُفحص بعد.").act("افحص الآن", () -> runHealthNow(a)));
        } else if ("ok".equals(hs)) {
            l.add(new Item(now - hl > 3 * DAY ? YELLOW : GREEN, R.drawable.ic_health, "البيانات سليمة",
                    "القيود متوازنة، الأرصدة تطابق الحركات، الملف والصور والسجل سليمة — " + MainActivity.ago(hl)).act("افحص الآن", () -> runHealthNow(a)));
        } else {
            int bar = hs.indexOf('|');
            l.add(new Item(RED, R.drawable.ic_health, "فحص البيانات وجد " + (bar > 0 ? hs.substring(0, bar) : "") + " ملاحظة",
                    bar > 0 ? hs.substring(bar + 1) : hs).act("التفاصيل", () -> a.settingsScreen.showHealth()));
        }

        boolean key = Crypto.healthy(a);
        l.add(new Item(key ? GREEN : RED, R.drawable.ic_key, key ? "مفتاح التشفير سليم" : "مفتاح التشفير غير متاح",
                key ? "الحقول المشفّرة تُقرأ على هذا الهاتف." : "لا تُنشأ نسخ جديدة حتى لا تحل محل السليمة. استعد آخر نسخة."));

        boolean locked = a.biometricOn() || db.usersEnabled();
        boolean secure = !"0".equals(db.setting("secure_screen", "1"));
        // 10.2: السماح بلقطات الشاشة اختيار المدير الصريح: يُذكر في الوصف ولا يُعدّ تحذيرًا دائمًا
        l.add(new Item(locked ? GREEN : YELLOW, R.drawable.ic_lock, locked ? "التطبيق مقفل" : "التطبيق بلا قفل",
                (locked ? "يُطلب القفل عند الفتح" : "أي شخص يفتح الهاتف يرى الحسابات") + (secure ? "، ولقطات الشاشة ممنوعة." : "، ولقطات الشاشة مسموحة."))
                .act("الإعداد", () -> a.settingsScreen.showSecurity()));

        long free = a.getFilesDir().getUsableSpace();
        l.add(new Item(free > 500L * 1024 * 1024 ? GREEN : free > 100L * 1024 * 1024 ? YELLOW : RED, R.drawable.ic_db, "المساحة الحرة في الهاتف",
                (free / (1024 * 1024)) + " ميغابايت" + (free > 100L * 1024 * 1024 ? "" : " — قليلة: قد تفشل النسخ والصور.")));

        String ds = db.setting("restore_drill_status", "");
        long da = db.settingLong("restore_drill_at", 0);
        int dl = ds.startsWith("fail") ? RED : ds.startsWith("ok") && now - da <= 31 * DAY ? GREEN : YELLOW;
        l.add(new Item(dl, R.drawable.ic_undo, "تمرين الاستعادة",
                ds.isEmpty() ? "لم تُجرَّب استعادة أي نسخة بعد. النسخة التي لم تُجرَّب لا تُعدّ نسخة." :
                        ds.substring(ds.indexOf('|') + 1) + " — " + MainActivity.ago(da) + (dl == YELLOW ? " (يُعاد شهريًا)" : ""))
                .act("جرّب الآن", () -> drill(a)));
        return l;
    }

    static int overall(List<Item> items) {
        int w = GREEN;
        for (Item i : items) w = Math.max(w, i.level);
        return w;
    }

    static int color(MainActivity a, int level) {
        return level == GREEN ? a.green : level == YELLOW ? Color.rgb(214, 150, 20) : a.red;
    }

    static String word(int level) {
        return level == GREEN ? "جيدة" : level == YELLOW ? "تحتاج انتباهًا" : "في خطر";
    }

    // ===================================================================================
    // الشاشة
    // ===================================================================================

    /** هل تظهر تنبيهات الاعتمادية في الصفحة الرئيسية؟ (الافتراضي نعم) */
    static boolean homeAlerts(Db db) { return !"0".equals(db.setting("rel_home", "1")); }

    static void screen(final MainActivity a) {
        a.here(() -> screen(a));
        a.tabbedScreen("اعتمادية التطبيق", a.moreScreen::show, "more");
        List<Item> items = items(a);
        int o = overall(items);
        LinearLayout head = a.card();
        head.addView(a.text("● الاعتمادية: " + word(o), 18, color(a, o), true));
        head.addView(a.text("أخضر = لا شيء مطلوب. أصفر = يُنصح بإجراء. أحمر = افعل الآن قبل أن تحتاج بياناتك.", 13, a.muted, false));
        // 9.0: إظهار تنبيه الاعتمادية في الصفحة الرئيسية أو إخفاؤه (اللوحة هنا تبقى كما هي)
        LinearLayout tr = a.row();
        tr.addView(a.text("إظهار تنبيهات الاعتمادية في الصفحة الرئيسية", 14, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        final android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(homeAlerts(a.db));
        sw.setOnCheckedChangeListener((b, c) -> {
            a.db.setSettingQuiet("rel_home", c ? "1" : "0");
            a.toast(c ? "تظهر تنبيهات الاعتمادية في الرئيسية عند الحاجة" : "أُخفيت تنبيهات الاعتمادية من الرئيسية؛ تجدها هنا دائمًا");
        });
        tr.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        tr.setOnClickListener(v -> sw.toggle());
        head.addView(tr, a.lp(-1, -2, 4));
        for (final Item i : items) {
            LinearLayout c = a.card();
            LinearLayout r = a.row();
            r.addView(a.icon(i.icon, color(a, i.level), 22), new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
            r.addView(a.text("  " + i.title, 15, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(a.text("●", 18, color(a, i.level), true));
            c.addView(r);
            c.addView(a.text(i.detail, 13, a.muted, false));
            if (i.run != null) c.addView(a.smallButton(i.action, i.level == RED ? a.red : a.primary, v -> i.run.run()), a.lp(-2, 40, 4));
        }
    }

    static void runHealthNow(final MainActivity a) {
        a.busy("جارٍ فحص البيانات…", p -> a.db.runHealthAuto(), res -> {
            a.toast("ok".equals(res) ? "البيانات سليمة ✓" : "وُجدت ملاحظات: افتح «فحص صحة البيانات».");
            screen(a);
        });
    }

    // ===================================================================================
    // تمرين الاستعادة (ت6)
    // ===================================================================================

    /** يفك أحدث نسخة محلية كاملة ويفحصها دون أن يلمس البيانات. يجرّب كلمة مرور النسخ التلقائي أولًا. */
    static void drill(final MainActivity a) {
        if (!a.require(Auth.Perm.BACKUP)) return;
        List<File> files = BackupManager.localBackups(a);
        if (files.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("تمرين الاستعادة").setMessage("لا توجد نسخة على هذا الهاتف بعد. أنشئ نسخة أولًا ثم جرّب استعادتها.")
                    .setPositiveButton("نسخة الآن", (d, w) -> a.backupScreen.backupDialog()).setNegativeButton("إغلاق", null).show();
            return;
        }
        final File f = files.get(0);
        String enc = a.db.setting("auto_backup_pw", "");
        char[] auto = null;
        if (!enc.isEmpty()) {
            try {
                auto = Crypto.decStrict(enc).toCharArray();
            } catch (Exception ignored) {
                // نطلبها من المستخدم
            }
        }
        if (auto != null) {
            final char[] pw = auto;
            runDrill(a, f, pw, true);
        } else askAndDrill(a, f);
    }

    private static void askAndDrill(final MainActivity a, final File f) {
        final EditText p = new EditText(a);
        Ui.secret(p, false);
        p.setHint("كلمة مرور هذه النسخة");
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(p, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle("تمرين الاستعادة")
                .setMessage("أحدث نسخة: " + f.getName() + " (" + Model.date(f.lastModified()) + "). اكتب كلمة مرورها: يُفك الملف كاملًا ويُفحص دون تغيير بياناتك.")
                .setView(l).setPositiveButton("ابدأ", (d, w) -> runDrill(a, f, p.getText().toString().toCharArray(), false))
                .setNegativeButton("إلغاء", null).show();
    }

    private static void runDrill(final MainActivity a, final File f, final char[] pw, final boolean triedAuto) {
        a.busy("جارٍ فك النسخة وفحصها (تمرين، لا يتغير شيء)…", p -> {
            try {
                return BackupManager.drill(a, f, pw, p);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }, res -> {
            BackupManager.DrillResult r = (BackupManager.DrillResult) res;
            if (r.wrongPassword && triedAuto) {
                askAndDrill(a, f);
                return;
            }
            String status = r.ok ? "ok|نسخة " + Model.date(f.lastModified()) + " تُفتح وتجتاز الفحص: " + r.offices + " مكتب، " + r.tx + " حركة، " + r.attachments + " صورة"
                    : "fail|" + r.message;
            a.db.setSettingQuiet("restore_drill_status", status);
            a.db.setSettingQuiet("restore_drill_at", "" + Clock.now());
            a.db.audit("RESTORE_DRILL", "backup", 0, r.ok ? "ok" : r.message);
            new AlertDialog.Builder(a).setTitle(r.ok ? "التمرين نجح ✓" : "التمرين فشل")
                    .setMessage(r.ok ? "النسخة " + f.getName() + " فُكّت كاملة واجتازت الفحص: " + r.offices + " مكتب، " + r.tx + " حركة، القيود متوازنة، و"
                            + r.attachments + " صورة سليمة. بياناتك لم تتغير." + (r.newerTx > 0 ? "\n\nسُجّلت بعدها " + r.newerTx + " حركة: أنشئ نسخة جديدة." : "")
                            : r.message)
                    .setPositiveButton("حسنًا", (d, w) -> screen(a)).show();
        });
    }
}
