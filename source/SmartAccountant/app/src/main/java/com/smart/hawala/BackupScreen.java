package com.smart.hawala;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** النسخ الاحتياطي اليدوي والتلقائي المجدول، والاستعادة مع المعاينة والتراجع، والتذكير، وسجل النسخ. */
final class BackupScreen {
    private final MainActivity a;

    BackupScreen(MainActivity a) { this.a = a; }

    interface PwCb { void on(char[] pw); }

    // ===================================================================================
    // الشاشة
    // ===================================================================================

    void show() {
        if (!a.require(Auth.Perm.BACKUP)) return;
        a.here(this::show);
        a.tabbedScreen("النسخ الاحتياطي والاستعادة", a.moreScreen::show, "more");
        long last = a.db.settingLong("last_backup", 0);
        String status = a.db.setting("last_backup_status", "");
        LinearLayout c = a.card();
        c.addView(a.text("حالة النسخ الاحتياطي", 18, a.primary, true));
        String st = "verified".equals(status) ? "محفوظة ومُتحقق منها ✓" : "shared".equals(status) ? "أُرسلت بالمشاركة" : "unverified".equals(status) ? "محفوظة دون إعادة قراءة" : "";
        c.addView(a.text("آخر نسخة: " + (last == 0 ? "لا يوجد" : Model.date(last) + " (" + MainActivity.ago(last) + ")" + (st.isEmpty() ? "" : " — " + st)), 15, a.dark, false));
        c.addView(a.text("حركات جديدة منذ آخر نسخة: " + a.txSinceBackup(), 15, a.dark, false));
        String reminder = a.backupReminderText();
        if (reminder != null) c.addView(a.text("⚠ " + reminder, 14, a.red, true));

        a.button("إنشاء نسخة احتياطية جديدة", a.primary, v -> backupDialog());
        if (a.can(Auth.Perm.RESTORE)) {
            a.button("استعادة نسخة من ملف", a.red, v -> new AlertDialog.Builder(a).setTitle("استعادة نسخة")
                    .setMessage("ستُستبدل بيانات التطبيق الحالية بمحتوى النسخة بعد فحصها كاملًا ومعاينتها.\n\nقبل الاستبدال تُحفظ نسخة أمان من بياناتك الحالية تلقائيًا، ويمكنك التراجع عنها من هذه الشاشة.")
                    .setPositiveButton("اختيار الملف", (d, w) -> {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        a.startForResult(i, MainActivity.OPEN_BACKUP);
                    }).setNegativeButton("إلغاء", null).show());
            if (Snapshot.exists(a)) {
                int n = Snapshot.list(a).size();
                a.button("التراجع عن " + snapshotWhat() + " (" + Model.date(Snapshot.time(a)) + ")", Color.rgb(120, 120, 120), v -> revertDialog());
                if (n > 1) a.body.addView(a.smallButton("نسخ أمان أقدم (" + (n - 1) + ")", Color.rgb(120, 120, 120), v -> olderSnapshots()), a.lp(-1, 42, 4));
            }
        }

        autoSection();
        recoverySection();

        a.title("التذكير");
        final String[] dayLabels = {"إيقاف التذكير بالأيام", "يوميًا", "كل 3 أيام", "أسبوعيًا", "كل أسبوعين"};
        final long[] dayValues = {0, 1, 3, 7, 14};
        final Spinner days = a.spinner(dayLabels);
        long dv = a.db.settingLong("reminder_days", 7);
        for (int i = 0; i < dayValues.length; i++) if (dayValues[i] == dv) days.setSelection(i);
        a.body.addView(days, a.lp(-1, 54, 4));
        final String[] txLabels = {"بدون تذكير بعدد الحركات", "بعد كل 10 حركات", "بعد كل 20 حركة", "بعد كل 50 حركة", "بعد كل 100 حركة"};
        final long[] txValues = {0, 10, 20, 50, 100};
        final Spinner txs = a.spinner(txLabels);
        long tv = a.db.settingLong("reminder_tx", 20);
        for (int i = 0; i < txValues.length; i++) if (txValues[i] == tv) txs.setSelection(i);
        a.body.addView(txs, a.lp(-1, 54, 4));
        android.widget.AdapterView.OnItemSelectedListener saver = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                long d = dayValues[days.getSelectedItemPosition()];
                long t = txValues[txs.getSelectedItemPosition()];
                if (d != a.db.settingLong("reminder_days", 7)) a.db.setSetting("reminder_days", "" + d);
                if (t != a.db.settingLong("reminder_tx", 20)) a.db.setSetting("reminder_tx", "" + t);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        };
        days.setOnItemSelectedListener(saver);
        txs.setOnItemSelectedListener(saver);

        List<File> local = BackupManager.localBackups(a);
        a.title("نسخ محفوظة داخل التطبيق (آخر 3)");
        a.hint("هذه نسخ مؤقتة داخل الهاتف للحماية من الأخطاء؛ تضيع إذا حُذف التطبيق أو ضاع الهاتف. احفظ نسخة خارج الهاتف دائمًا.");
        if (local.isEmpty()) a.hint("لا توجد");
        for (final File f : local) {
            LinearLayout x = a.card();
            x.addView(a.text(Model.date(f.lastModified()) + " — " + BackupManager.size(f.length()), 15, a.dark, true));
            LinearLayout r = a.row();
            r.addView(a.smallButton("حفظ في ملف", a.primary, v -> saveBackupTo(f)), a.weight(44, 1, 2));
            r.addView(a.smallButton("مشاركة", a.accent, v -> shareBackup(f)), a.weight(44, 1, 2));
            x.addView(r);
            LinearLayout r2 = a.row();
            if (a.can(Auth.Perm.RESTORE)) r2.addView(a.smallButton("استعادة", a.red, v -> restorePasswordDialog(f, false)), a.weight(44, 1, 2));
            r2.addView(a.smallButton("حذف", Color.rgb(120, 120, 120), v -> new AlertDialog.Builder(a).setMessage("حذف هذه النسخة من داخل التطبيق؟")
                    .setPositiveButton("حذف", (d, w) -> {
                        f.delete();
                        show();
                    }).setNegativeButton("إلغاء", null).show()), a.weight(44, 1, 2));
            x.addView(r2);
        }

        List<Db.BackupRecord> hist = a.db.backupHistory(15);
        a.title("سجل النسخ");
        if (hist.isEmpty()) a.hint("لا يوجد");
        for (Db.BackupRecord r : hist) {
            String kind = "SAVE".equals(r.kind) ? "حفظ في ملف" : "SHARE".equals(r.kind) ? "مشاركة" : "CREATE".equals(r.kind) ? "إنشاء داخل التطبيق"
                    : "RESTORE".equals(r.kind) ? "استعادة" : "AUTO".equals(r.kind) ? "نسخ تلقائي" : r.kind;
            String line = Model.date(r.createdAt) + " • " + kind + " • " + r.status + (r.size > 0 ? " • " + BackupManager.size(r.size) : "")
                    + (r.tx > 0 || r.offices > 0 ? "\nمكاتب " + r.offices + " • حركات " + r.tx + " • صور " + r.att : "")
                    + (r.verified ? " • ✓ متحقق" : "") + (r.details.isEmpty() ? "" : "\n" + r.details);
            a.add(a.text(line, 13, a.dark, false)).setBackground(a.round(Color.WHITE, Color.rgb(228, 223, 214), 10));
        }
    }

    String snapshotWhat() {
        String l = Snapshot.label(a);
        return l.isEmpty() ? "آخر استعادة" : l;
    }

    /** يعيد البيانات كما كانت قبل آخر عملية كبيرة (استعادة نسخة أو حذف مكتب). */
    void revertDialog() {
        if (!a.require(Auth.Perm.RESTORE)) return;
        if (!Snapshot.exists(a)) {
            a.toast("لا توجد نسخة أمان للتراجع.");
            return;
        }
        new AlertDialog.Builder(a).setTitle("التراجع عن " + snapshotWhat())
                .setMessage("ستعود البيانات كما كانت قبل " + snapshotWhat() + " (" + Model.date(Snapshot.time(a)) + "). أي حركات سُجّلت بعد ذلك ستُفقد. هل تريد المتابعة؟"
                        + syncWarn())
                .setPositiveButton("تراجع", (d, w) -> a.busy("جارٍ إرجاع البيانات…", p -> Snapshot.revert(a, a.db), res -> {
                    SmartApp.db = (Db) res;
                    a.db = SmartApp.db;
                    a.toast("تمت إعادة البيانات كما كانت" + (Snapshot.lastSyncNote.isEmpty() ? "" : ". " + Snapshot.lastSyncNote));
                    afterDataReplaced();
                })).setNegativeButton("إلغاء", null).show();
    }

    /** 10.5 (AUDIT-030): أثر التراجع على المزامنة، يُضاف إلى رسالة التأكيد. */
    private String syncWarn() {
        String s = Snapshot.syncImpact(a.db);
        return s.isEmpty() ? "" : "\n\n" + s;
    }

    /** 8.0: التراجع إلى ما قبل عملية أقدم (حتى ثلاث عمليات كبيرة). */
    private void olderSnapshots() {
        if (!a.require(Auth.Perm.RESTORE)) return;
        final List<Snapshot.Info> l = Snapshot.list(a);
        String[] items = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            Snapshot.Info x = l.get(i);
            items[i] = "قبل " + (x.label.isEmpty() ? "استعادة" : x.label) + " — " + Model.date(x.time);
        }
        new AlertDialog.Builder(a).setTitle("نسخ الأمان على هذا الهاتف").setItems(items, (d, w) -> {
            final Snapshot.Info x = l.get(w);
            String what = x.label.isEmpty() ? "الاستعادة" : x.label;
            new AlertDialog.Builder(a).setTitle("التراجع إلى ما قبل " + what)
                    .setMessage("ستعود البيانات كما كانت قبل " + what + " (" + Model.date(x.time) + "). كل ما سُجّل بعدها سيُفقد"
                            + (x.slot > 0 ? "، ومنه عمليات كبيرة أحدث" : "") + ". سجل العمليات الحالي يُحفظ في الأرشيف. متابعة؟" + syncWarn())
                    .setPositiveButton("تراجع", (d2, w2) -> a.busy("جارٍ إرجاع البيانات…", p -> Snapshot.revert(a, a.db, x.slot), res -> {
                        SmartApp.db = (Db) res;
                        a.db = SmartApp.db;
                        a.toast("تمت إعادة البيانات كما كانت" + (Snapshot.lastSyncNote.isEmpty() ? "" : ". " + Snapshot.lastSyncNote));
                        afterDataReplaced();
                    })).setNegativeButton("إلغاء", null).show();
        }).setNegativeButton("إغلاق", null).show();
    }

    /** بعد الاستعادة أو التراجع: قد تتغير قائمة المستخدمين والألوان، فنعيد الدخول. */
    private void afterDataReplaced() {
        Reliability.healthAsync(a.db);
        SmartApp.currentUser = null;
        Db.currentUserName = "المستخدم المحلي";
        a.applyColors();
        if (AutoBackup.enabled(a.db)) AutoBackup.schedule(a);
        a.afterUnlock();
    }

    // ===================================================================================
    // النسخ التلقائي
    // ===================================================================================

    static String folderName(String tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            int i = id.indexOf(':');
            String vol = i > 0 ? id.substring(0, i) : "";
            String path = i >= 0 ? id.substring(i + 1) : id;
            String where = "primary".equalsIgnoreCase(vol) ? "ذاكرة الهاتف" : vol.isEmpty() ? "" : vol;
            return (where.isEmpty() ? "" : where + " / ") + (path.isEmpty() ? "(المجلد الرئيسي)" : path);
        } catch (Exception e) {
            return "مجلد مختار";
        }
    }

    private void autoSection() {
        a.title("النسخ الاحتياطي التلقائي");
        final LinearLayout c = a.card();
        final String tree = a.db.setting("auto_backup_tree", "");
        final String drive = a.db.setting("drive_backup_uri", "");
        boolean hasPw = !a.db.setting("auto_backup_pw", "").isEmpty();
        boolean on = AutoBackup.enabled(a.db);
        if (!AutoBackup.configured(a.db)) {
            c.addView(a.text("غير مفعّل. اختر أين تُحفظ النسخ المشفّرة تلقائيًا كل يوم (يمكنك اختيار المكانين معًا):", 14, a.dark, false));
            c.addView(a.makeButton("📁 مجلد على الهاتف أو بطاقة الذاكرة", a.primary, v -> chooseFolder()), a.lp(-1, 48, 4));
            c.addView(a.makeButton("☁ ملف في Google Drive", a.primary, v -> chooseDriveFile()), a.lp(-1, 48, 4));
            c.addView(a.text("المجلد يحتفظ بعدة نسخ مؤرّخة. أما Google Drive فلا يسمح للتطبيقات باختيار مجلد كامل، لذلك تختار مكان ملف واحد داخل Drive، ويستبدل التطبيق محتواه بآخر نسخة في كل مرة.", 12, a.muted, false));
            if ((!tree.isEmpty() || !drive.isEmpty()) && !hasPw) {
                c.addView(a.makeButton("تحديد كلمة مرور النسخ التلقائي", a.accent, v -> autoPassword(true)), a.lp(-1, 46, 4));
            }
            return;
        }
        long last = a.db.settingLong("last_auto_backup", 0);
        c.addView(a.text((on ? "✓ مفعّل" : "⏸ متوقف")
                + "\nآخر نسخة تلقائية: " + (last == 0 ? "لا يوجد بعد" : Model.date(last) + " (" + MainActivity.ago(last) + ")"), 14, on ? a.green : a.dark, true));
        String err = a.db.setting("auto_backup_error", "");
        if (!err.isEmpty()) c.addView(a.text("⚠ فشل آخر نسخ تلقائي" + (tree.isEmpty() ? "" : " (المجلد)") + ":\n" + err, 13, a.red, true));

        c.addView(a.label("أماكن الحفظ"));
        // المجلد
        c.addView(a.text("📁 " + (tree.isEmpty() ? "مجلد على الهاتف: غير محدد" : "المجلد: " + folderName(tree)), 14, tree.isEmpty() ? a.muted : a.dark, !tree.isEmpty()));
        LinearLayout fr = a.row();
        fr.addView(a.smallButton(tree.isEmpty() ? "اختيار مجلد" : "تغيير المجلد", a.accent, v -> chooseFolder()), a.weight(42, 1, 2));
        if (!tree.isEmpty()) fr.addView(a.smallButton("إزالة", a.muted, v -> removeDestination(false)), a.weight(42, 1, 2));
        c.addView(fr);
        // Google Drive
        long lastDrive = a.db.settingLong("last_drive_backup", 0);
        c.addView(a.text("☁ " + (drive.isEmpty() ? "Google Drive: غير محدد"
                : "Google Drive: " + a.db.setting("drive_backup_name", AutoBackup.driveFileName(a))
                + "\nآخر تحديث: " + (lastDrive == 0 ? "لا يوجد بعد" : Model.date(lastDrive) + " (" + MainActivity.ago(lastDrive) + ")")),
                14, drive.isEmpty() ? a.muted : a.dark, !drive.isEmpty()));
        String dErr = a.db.setting("drive_backup_error", "");
        if (!dErr.isEmpty()) c.addView(a.text("⚠ فشل آخر حفظ في Google Drive:\n" + dErr, 13, a.red, true));
        LinearLayout dr = a.row();
        dr.addView(a.smallButton(drive.isEmpty() ? "اختيار ملف في Drive" : "تغيير ملف Drive", a.accent, v -> chooseDriveFile()), a.weight(42, 1, 2));
        if (!drive.isEmpty()) dr.addView(a.smallButton("إزالة", a.muted, v -> removeDestination(true)), a.weight(42, 1, 2));
        c.addView(dr);
        c.addView(a.text("المجلد يحتفظ بعدة نسخ مؤرّخة؛ ملف Google Drive يُستبدل بآخر نسخة في كل مرة (Drive لا يسمح للتطبيقات باختيار مجلد كامل).", 12, a.muted, false));

        c.addView(a.label("التكرار"));
        long hours = AutoBackup.intervalHours(a.db);
        final boolean six = License.current.autoBackupMinHours() <= 6;
        final long[] hourValues = six ? BackupPolicy.HOURS : new long[]{24, 72, 168};
        String[] hourLabels = six ? new String[]{"كل 6 ساعات", "يوميًا", "كل 3 أيام", "أسبوعيًا"} : new String[]{"يوميًا", "كل 3 أيام", "أسبوعيًا"};
        int hi = 0;
        for (int i = 0; i < hourValues.length; i++) if (hours >= hourValues[i]) hi = i;
        Ui.Seg freq = a.new Seg(hourLabels, null, hi, false);
        freq.listener = i -> {
            a.db.setSetting("auto_backup_hours", "" + hourValues[i]);
            a.db.setSetting("auto_backup_days", "" + Math.max(1, hourValues[i] / 24));
        };
        c.addView(freq.view);
        if (License.current.autoBackupEveryTx() > 0) {
            c.addView(a.label("وأيضًا بعد عدد من الحركات"));
            final long[] txValues = {0, 25, 50};
            int every = AutoBackup.everyTx(a.db);
            Ui.Seg txSeg = a.new Seg(new String[]{"لا", "كل 25 حركة", "كل 50 حركة"}, null, every == 0 ? 0 : every <= 25 ? 1 : 2, false);
            txSeg.listener = i -> a.db.setSetting("auto_backup_every_tx", "" + txValues[i]);
            c.addView(txSeg.view);
        } else {
            c.addView(a.text("النسخة الكاملة: كل 6 ساعات أو بعد كل 25 حركة.", 12, a.muted, false));
        }
        if (!tree.isEmpty()) {
            c.addView(a.label("عدد النسخ المحتفظ بها في المجلد"));
            long keep = a.db.settingLong("auto_backup_keep", 7);
            final long[] keepValues = {3, 7, 14, 30};
            int ki = keep <= 3 ? 0 : keep <= 7 ? 1 : keep <= 14 ? 2 : 3;
            Ui.Seg keepSeg = a.new Seg(new String[]{"3", "7", "14", "30"}, null, ki, false);
            keepSeg.listener = i -> a.db.setSetting("auto_backup_keep", "" + keepValues[i]);
            c.addView(keepSeg.view);
        }

        if (!tree.isEmpty()) {
            final CheckBox readable = new CheckBox(a);
            readable.setText("نسخة مقروءة شهرية (Excel) في المجلد: الأرصدة وحركات الشهر الماضي");
            readable.setChecked("1".equals(a.db.setting("readable_export", "0")));
            readable.setOnCheckedChangeListener((b, yes) -> {
                a.db.setSetting("readable_export", yes ? "1" : "0");
                if (yes) a.db.deleteSetting("readable_export_month");
            });
            c.addView(readable);
            c.addView(a.text("تُفتح بلا التطبيق وبلا كلمة مرور إن ضاع المفتاح أو تعطلت صيغة النسخ؛ لكنها غير مشفّرة، فاختر مجلدًا لا يصل إليه غيرك."
                    + (a.db.setting("readable_export_error", "").isEmpty() ? "" : "\n⚠ " + a.db.setting("readable_export_error", "")), 12, a.muted, false));
        }
        c.addView(a.makeButton("نسخ تلقائي الآن (تجربة)", a.primary, v -> runAutoNow()), a.lp(-1, 46, 4));
        c.addView(a.smallButton("تغيير كلمة مرور النسخ التلقائي", a.accent, v -> autoPassword(false)), a.lp(-1, 44, 4));
        c.addView(a.smallButton("اختبر أنك تتذكر كلمة المرور", a.muted, v -> recallTest()), a.lp(-1, 44, 4));
        if (hasPw) c.addView(a.smallButton("نسيتها؟ عرض كلمة مرور النسخ التلقائي (للمدير)", a.muted, v -> revealAutoPassword()), a.lp(-1, 44, 4));
        c.addView(a.makeButton(on ? "إيقاف النسخ التلقائي" : "تشغيل النسخ التلقائي", on ? a.red : a.primary, v -> {
            if (AutoBackup.enabled(a.db)) {
                a.db.setSetting("auto_backup_on", "0");
                AutoBackup.cancel(a);
                a.toast("أُوقف النسخ التلقائي");
            } else {
                a.db.setSetting("auto_backup_on", "1");
                AutoBackup.schedule(a);
                askNotifications();
                a.toast("تم تشغيل النسخ التلقائي");
            }
            show();
        }), a.lp(-1, 46, 4));
        c.addView(a.text("كلمة مرور النسخ التلقائي محفوظة مشفّرة على هذا الهاتف فقط. احفظها في مكان آمن؛ ستحتاجها لاستعادة أي نسخة تلقائية على هاتف آخر.", 12, a.muted, false));
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        a.toast("اختر أو أنشئ مجلدًا للنسخ ثم اضغط «استخدام هذا المجلد».");
        a.startForResult(i, MainActivity.AUTO_TREE);
    }

    /** Google Drive لا يظهر في اختيار المجلدات؛ نطلب منه إنشاء ملف واحد نملك صلاحية دائمة لتحديثه. */
    private void chooseDriveFile() {
        new AlertDialog.Builder(a).setTitle("النسخ التلقائي إلى Google Drive")
                .setMessage("Google Drive لا يسمح للتطبيقات باختيار مجلد كامل، لذلك ستختار مكان ملف واحد:\n\n"
                        + "1) في الشاشة التالية افتح القائمة ☰ واختر «Drive» أو «Google Drive».\n"
                        + "2) افتح المجلد الذي تريده داخل Drive (أو أنشئ مجلدًا جديدًا).\n"
                        + "3) أبقِ اسم الملف كما هو واضغط «حفظ».\n\n"
                        + "بعدها يستبدل التطبيق محتوى هذا الملف بآخر نسخة مشفّرة في كل نسخ تلقائي، ويتحقق منها بإعادة قراءتها. "
                        + "للاحتفاظ بعدة نسخ قديمة أيضًا، أضف مجلدًا على الهاتف بجانب Drive.\n\n"
                        + "إن لم يظهر Drive في القائمة فثبّت تطبيق Google Drive وسجّل الدخول فيه أولًا.")
                .setPositiveButton("متابعة", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/octet-stream");
                    i.putExtra(Intent.EXTRA_TITLE, AutoBackup.driveFileName(a));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    a.startForResult(i, MainActivity.DRIVE_FILE);
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void releaseUri(String uri) {
        if (uri == null || uri.isEmpty()) return;
        try {
            a.getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // قد تكون الصلاحية أُلغيت مسبقًا
        }
    }

    /** بعد إضافة وجهة: إن كانت الأولى نفعّل النسخ التلقائي، وإن لم توجد كلمة مرور نطلبها. */
    private void afterDestinationAdded(boolean wasConfigured) {
        if (a.db.setting("auto_backup_pw", "").isEmpty()) {
            autoPassword(true);
            return;
        }
        if (!wasConfigured) {
            a.db.setSetting("auto_backup_on", "1");
            AutoBackup.schedule(a);
            askNotifications();
        }
        show();
        runAutoNow();
    }

    private void onTreeChosen(Uri tree) {
        try {
            a.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            a.error(new Exception("لم يمنح الهاتف صلاحية دائمة لهذا المجلد. اختر مجلدًا آخر (مثلًا داخل «Download» أو «Documents»)."));
            return;
        }
        boolean wasConfigured = AutoBackup.configured(a.db);
        String old = a.db.setting("auto_backup_tree", "");
        if (!old.equals(tree.toString())) releaseUri(old);
        a.db.setSetting("auto_backup_tree", tree.toString());
        a.db.deleteSetting("auto_backup_error");
        afterDestinationAdded(wasConfigured);
    }

    private void onDriveChosen(Uri file) {
        try {
            a.getContentResolver().takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            a.error(new Exception("لم يمنح المكان المختار صلاحية دائمة لتحديث الملف، فلا يمكن النسخ إليه تلقائيًا. جرّب مرة أخرى، أو استخدم مجلدًا على الهاتف."));
            return;
        }
        boolean wasConfigured = AutoBackup.configured(a.db);
        String old = a.db.setting("drive_backup_uri", "");
        if (!old.equals(file.toString())) releaseUri(old);
        String name = BackupManager.displayName(a, file);
        a.db.setSetting("drive_backup_uri", file.toString());
        a.db.setSettingQuiet("drive_backup_name", name == null || name.isEmpty() ? AutoBackup.driveFileName(a) : name);
        a.db.deleteSetting("drive_backup_error");
        a.db.deleteSetting("last_drive_backup");
        afterDestinationAdded(wasConfigured);
    }

    private void removeDestination(final boolean drive) {
        new AlertDialog.Builder(a).setTitle(drive ? "إزالة Google Drive" : "إزالة المجلد")
                .setMessage(drive ? "لن يُحدَّث ملف Google Drive بعد الآن. الملف نفسه يبقى في Drive ولا يُحذف."
                        : "لن تُحفظ نسخ تلقائية جديدة في هذا المجلد. النسخ الموجودة فيه تبقى ولا تُحذف.")
                .setPositiveButton("إزالة", (d, w) -> {
                    if (drive) {
                        releaseUri(a.db.setting("drive_backup_uri", ""));
                        a.db.deleteSetting("drive_backup_uri");
                        a.db.deleteSetting("drive_backup_name");
                        a.db.deleteSetting("drive_backup_error");
                        a.db.deleteSetting("last_drive_backup");
                    } else {
                        releaseUri(a.db.setting("auto_backup_tree", ""));
                        a.db.deleteSetting("auto_backup_tree");
                        a.db.deleteSetting("auto_backup_error");
                    }
                    if (!AutoBackup.configured(a.db)) {
                        a.db.setSetting("auto_backup_on", "0");
                        AutoBackup.cancel(a);
                        a.toast("أُوقف النسخ التلقائي لعدم وجود مكان للحفظ");
                    }
                    show();
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void autoPassword(final boolean firstSetup) {
        passwordDialog("كلمة مرور النسخ التلقائي",
                "ستُشفّر بها كل النسخ التلقائية. احفظها في مكان آمن؛ لا يمكن فتح النسخ بدونها.", "حفظ",
                pw -> {
                    try {
                        a.db.setSettingQuiet("auto_backup_pw", Crypto.enc(new String(pw)));
                        a.db.audit("SETTING", "settings", 0, "auto_backup_pw");
                    } finally {
                        Arrays.fill(pw, '\0');
                    }
                    // 8.0 (ت7): كلمة المرور محفوظة داخل الهاتف فقط؛ إن ضاع الهاتف ونُسيت فلا قيمة لأي نسخة
                    new AlertDialog.Builder(a).setTitle("احفظ كلمة المرور خارج الهاتف")
                            .setMessage("اكتب كلمة مرور النسخ التلقائي على ورقة أو في مكان آمن بعيدًا عن هذا الهاتف.\n\n"
                                    + "إن ضاع الهاتف أو تعطّل فهي الطريقة الوحيدة لفتح النسخ على هاتف آخر. "
                                    + "سنُنشئ الآن نسخة تجريبية ونفتحها بها للتأكد.")
                            .setCancelable(false)
                            .setPositiveButton("كتبتها، تابع", (d2, w2) -> {
                                if (firstSetup) {
                                    a.db.setSetting("auto_backup_on", "1");
                                    if (a.db.setting("auto_backup_days", "").isEmpty()) a.db.setSettingQuiet("auto_backup_days", "1");
                                    if (a.db.setting("auto_backup_keep", "").isEmpty()) a.db.setSettingQuiet("auto_backup_keep", "7");
                                    AutoBackup.schedule(a);
                                    askNotifications();
                                } else a.toast("تم تغيير كلمة مرور النسخ التلقائي");
                                show();
                                runAutoNow();
                            }).show();
                });
    }

    // ===================================================================================
    // 10.14: رمز استرداد النسخ، وعرض كلمة مرور النسخ التلقائي للمدير
    // ===================================================================================

    private void recoverySection() {
        a.title("رمز استرداد النسخ");
        final LinearLayout c = a.card();
        final boolean on = BackupRecovery.on(a.db);
        c.addView(a.text(on ? "✓ مفعّل منذ " + Model.date(BackupRecovery.createdAt(a.db)) + ". كل نسخة من هذا الجهاز (التلقائية واليدوية) تُفتح بكلمة مرورها أو بهذا الرمز."
                : "غير مفعّل. إن نسيت كلمة مرور النسخ فلا تُفتح النسخ أبدًا. فعّل رمز الاسترداد واحفظه على ورقة بعيدًا عن الهاتف: يفتح كل نسخة تُؤخذ بعده.", 14, on ? a.green : a.dark, on));
        boolean admin = a.can(Auth.Perm.BACKUP) && (!a.db.usersEnabled() || (SmartApp.currentUser != null && Auth.isAdmin(SmartApp.currentUser)));
        if (!admin) {
            c.addView(a.text("للمدير وحده.", 12, a.muted, false));
            return;
        }
        if (!on) c.addView(a.makeButton("إنشاء رمز استرداد النسخ", a.primary, v -> a.usersScreen.confirmAdmin("رمز استرداد النسخ", this::newBackupCode)), a.lp(-1, 46, 4));
        else {
            c.addView(a.smallButton("رمز جديد (يحل محل الحالي للنسخ القادمة)", a.accent, v -> a.confirm("رمز استرداد جديد",
                    "النسخ القادمة تُفتح بالرمز الجديد. النسخ السابقة تبقى تُفتح بالرمز القديم وبكلمة مرورها، فاحتفظ بالقديم ما دمت تحتاج تلك النسخ.", "متابعة",
                    () -> a.usersScreen.confirmAdmin("رمز استرداد النسخ", this::newBackupCode))), a.lp(-1, 44, 4));
            c.addView(a.smallButton("إيقاف الرمز", a.red, v -> a.confirm("إيقاف رمز استرداد النسخ",
                    "النسخ القادمة تُفتح بكلمة مرورها وحدها. النسخ السابقة تبقى تُفتح بالرمز القديم.", "إيقاف",
                    () -> a.usersScreen.confirmAdmin("إيقاف رمز استرداد النسخ", () -> {
                        BackupRecovery.disable(a.db);
                        a.toast("أُوقف رمز استرداد النسخ");
                        show();
                    }))), a.lp(-1, 44, 4));
        }
        c.addView(a.text("الرمز لا يُحفظ على الهاتف ولا يظهر إلا مرة واحدة. النسخ المأخوذة بإصدار أقدم من 10.14، أو قبل تفعيل الرمز، تُفتح بكلمة مرورها فقط. "
                + "النسخ المأخوذة بعد تفعيله لا تفتحها الإصدارات الأقدم من 10.14.", 12, a.muted, false));
    }

    private void newBackupCode() {
        a.busy("جارٍ إنشاء الرمز…", p -> BackupRecovery.create(a.db), res -> {
            final String code = (String) res;
            new AlertDialog.Builder(a).setTitle("رمز استرداد النسخ — احفظه الآن")
                    .setMessage("يفتح كل نسخة احتياطية تُؤخذ من الآن إن نسيت كلمة مرورها. يظهر مرة واحدة فقط: اكتبه على ورقة واحفظه بعيدًا عن هذا الهاتف.\n\n" + code)
                    .setCancelable(false)
                    .setPositiveButton("نسخ الرمز", (x, w) -> {
                        copySecret("رمز استرداد النسخ", code);
                        show();
                    }).setNegativeButton("حفظته", (x, w) -> show()).show();
        });
    }

    /** نسخ نص سري إلى الحافظة (يُعلَّم «حساسًا» فلا يظهر في معاينة الحافظة على أندرويد 13 وما بعده). */
    private void copySecret(String label, String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        android.content.ClipData clip = android.content.ClipData.newPlainText(label, text);
        if (Build.VERSION.SDK_INT >= 24) {
            android.os.PersistableBundle x = new android.os.PersistableBundle();
            x.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(x);
        }
        cm.setPrimaryClip(clip);
        a.toast("نُسخ. الصقه في مكان آمن ثم امسح الحافظة.");
    }

    /** 10.14: نسيان كلمة مرور النسخ التلقائي: تُعرض للمدير بعد تأكيد رمزه (محفوظة مشفّرة بمفتاح هذا الجهاز)، ويُسجَّل العرض في السجل. */
    private void revealAutoPassword() {
        if (!a.require(Auth.Perm.BACKUP)) return;
        if (a.db.usersEnabled() && (SmartApp.currentUser == null || !Auth.isAdmin(SmartApp.currentUser))) {
            a.toast("عرض كلمة مرور النسخ للمدير وحده");
            return;
        }
        a.usersScreen.confirmAdmin("عرض كلمة مرور النسخ التلقائي", () -> {
            final String pw;
            try {
                pw = Crypto.decStrict(a.db.setting("auto_backup_pw", ""));
            } catch (Exception e) {
                new AlertDialog.Builder(a).setTitle("تعذر عرضها")
                        .setMessage("لا يمكن فك كلمة المرور المحفوظة على هذا الهاتف (ربما نُقلت البيانات من هاتف آخر). عيّن كلمة جديدة؛ النسخ القديمة تُفتح بكلمتها القديمة أو برمز استرداد النسخ إن كان مفعّلًا حينها.")
                        .setPositiveButton("تعيين كلمة جديدة", (x, y) -> autoPassword(false)).setNegativeButton("إغلاق", null).show();
                return;
            }
            a.db.audit("AUTO_PW_REVEAL", "backup", 0, "عُرضت كلمة مرور النسخ التلقائي");
            new AlertDialog.Builder(a).setTitle("كلمة مرور النسخ التلقائي")
                    .setMessage("اكتبها على ورقة واحفظها بعيدًا عن هذا الهاتف:\n\n" + pw)
                    .setPositiveButton("نسخ", (x, y) -> copySecret("كلمة مرور النسخ", pw)).setNegativeButton("إغلاق", null).show();
        });
    }

    /** 8.0 (ت7): يكتبها المستخدم من ذاكرته وتُقارن بالمحفوظة. 10.14: وللمدير «عرضها» بعد تأكيد رمزه. */
    private void recallTest() {
        final EditText p = new EditText(a);
        Ui.secret(p, false);
        p.setHint("كلمة مرور النسخ التلقائي");
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), 0);
        l.addView(p, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle("هل تتذكرها؟").setMessage("اكتبها من ذاكرتك أو من الورقة التي حفظتها فيها.").setView(l)
                .setPositiveButton("تحقق", (d, w) -> {
                    final boolean ok = sameAutoPassword(p.getText().toString());
                    a.db.audit("AUTO_PW_RECALL", "backup", 0, ok ? "ok" : "mismatch");
                    new AlertDialog.Builder(a).setTitle(ok ? "صحيحة ✓" : "غير مطابقة")
                            .setMessage(ok ? "ستستطيع فتح نسخك على هاتف آخر." : "ما كتبته لا يطابق كلمة مرور النسخ التلقائي. غيّرها الآن إلى كلمة تحفظها خارج الهاتف، فالنسخ القديمة لن تُفتح بدونها.")
                            .setPositiveButton(ok ? "حسنًا" : "تغييرها الآن", (x, y) -> { if (!ok) autoPassword(false); })
                            .setNegativeButton(ok ? null : "لاحقًا", null).show();
                }).setNegativeButton("إلغاء", null).show();
    }

    private boolean sameAutoPassword(String typed) {
        try {
            return Crypto.decStrict(a.db.setting("auto_backup_pw", "")).equals(typed);
        } catch (Exception e) {
            return false;
        }
    }

    private void runAutoNow() {
        a.busy("جارٍ إنشاء نسخة تلقائية وحفظها والتحقق منها…", p -> AutoBackup.runNow(a, a.db), res -> {
            String fails = (a.db.setting("auto_backup_error", "").isEmpty() ? "" : "\n\n⚠ المجلد:\n" + a.db.setting("auto_backup_error", ""))
                    + (a.db.setting("drive_backup_error", "").isEmpty() ? "" : "\n\n⚠ Google Drive:\n" + a.db.setting("drive_backup_error", ""));
            if (fails.isEmpty()) a.toast("تم حفظ النسخة:\n" + res);
            else new AlertDialog.Builder(a).setTitle("نجح جزئيًا").setMessage("حُفظت النسخة في:\n" + res + fails).setPositiveButton("حسنًا", null).show();
            show();
        });
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            try {
                a.markOwn();
                a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
            } catch (Exception ignored) {
                a.ownActivity = false;
            }
        }
    }

    // ===================================================================================
    // إنشاء نسخة يدوية
    // ===================================================================================

    /** نافذة كلمة المرور بحقلين للتأكد من حفظها وإدخالها بشكل صحيح. */
    void passwordDialog(String title, String message, String okLabel, final PwCb cb) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(4));
        final EditText p1 = new EditText(a);
        p1.setHint("كلمة المرور (8 محارف على الأقل)");
        final EditText p2 = new EditText(a);
        p2.setHint("أعد كتابة كلمة المرور للتأكيد");
        for (EditText e : new EditText[]{p1, p2}) {
            Ui.secret(e, false);
            l.addView(e);
        }
        final CheckBox showPw = new CheckBox(a);
        showPw.setText("إظهار كلمة المرور");
        showPw.setOnCheckedChangeListener((b, c) -> {
            for (EditText e : new EditText[]{p1, p2}) {
                e.setTransformationMethod(c ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
                e.setSelection(e.getText().length());
            }
        });
        l.addView(showPw);
        final TextView hint = a.text("", 13, a.muted, false);
        l.addView(hint);
        Runnable check = () -> {
            int n1 = p1.getText().length();
            if (n1 == 0) hint.setText("");
            else if (n1 < 8) {
                hint.setText("قصيرة: " + n1 + " من 8 محارف على الأقل");
                hint.setTextColor(a.red);
            } else if (p2.getText().length() > 0 && !p1.getText().toString().equals(p2.getText().toString())) {
                hint.setText("الكلمتان غير متطابقتين");
                hint.setTextColor(a.red);
            } else if (p2.getText().length() > 0) {
                hint.setText("✓ الكلمتان متطابقتان");
                hint.setTextColor(a.primary);
            } else {
                hint.setText("أعد كتابتها في الحقل الثاني");
                hint.setTextColor(a.muted);
            }
        };
        p1.addTextChangedListener(Ui.watcher(check));
        p2.addTextChangedListener(Ui.watcher(check));
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle(title).setMessage(message)
                .setView(l).setPositiveButton(okLabel, null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Editable e1 = p1.getText(), e2 = p2.getText();
            if (e1.length() < 8) {
                hint.setText("كلمة المرور يجب ألا تقل عن 8 محارف");
                hint.setTextColor(a.red);
                return;
            }
            if (!e1.toString().equals(e2.toString())) {
                hint.setText("كلمتا المرور غير متطابقتين. أعد كتابتهما.");
                hint.setTextColor(a.red);
                return;
            }
            final char[] pw = new char[e1.length()];
            e1.getChars(0, e1.length(), pw, 0);
            p1.setText("");
            p2.setText("");
            dlg.dismiss();
            cb.on(pw);
        }));
        dlg.show();
    }

    void backupDialog() {
        if (!a.require(Auth.Perm.BACKUP)) return;
        passwordDialog("نسخة احتياطية مشفرة", "احفظ كلمة المرور في مكان آمن؛ لا يمكن فتح النسخة بدونها، ولا يستطيع أحد استرجاعها لك.",
                "إنشاء النسخة", this::createBackup);
    }

    private void createBackup(final char[] pw) {
        final String version = a.versionName();
        a.busy("جارٍ إنشاء النسخة…", p -> {
            try {
                return BackupManager.createLocal(a, a.db, pw, version, p);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }, res -> {
            final BackupManager.Created c = (BackupManager.Created) res;
            a.db.recordBackup("CREATE", "تم الإنشاء والتحقق", c.file.getName(), c.size, c.counts, true,
                    c.result.skippedAttachments > 0 ? "تم تخطي " + c.result.skippedAttachments + " صورة تالفة" : "");
            StringBuilder sb = new StringBuilder("تم إنشاء النسخة وفكّها بالكامل للتحقق منها ✓\n\n");
            sb.append("الحجم: ").append(BackupManager.size(c.size)).append("\nالمكاتب: ").append(c.counts.get("offices"))
                    .append(" • الحركات: ").append(c.counts.get("tx")).append(" • الصور: ").append(c.result.attachments);
            for (String w : c.result.warnings) sb.append("\n⚠ ").append(w);
            if (c.result.skippedAttachments > 0) sb.append("\n⚠ تم تخطي ").append(c.result.skippedAttachments).append(" صورة تالفة أو مفقودة.");
            sb.append("\n\nالخطوة التالية: احفظها خارج الهاتف (ذاكرة خارجية، Google Drive، أو أرسلها لنفسك).");
            new AlertDialog.Builder(a).setTitle("النسخة جاهزة").setMessage(sb.toString()).setCancelable(false)
                    .setPositiveButton("حفظ في ملف…", (d, w) -> saveBackupTo(c.file))
                    .setNeutralButton("مشاركة / إرسال", (d, w) -> shareBackup(c.file))
                    .setNegativeButton("لاحقًا", (d, w) -> {
                        a.toast("النسخة محفوظة داخل التطبيق؛ يمكنك حفظها لاحقًا من شاشة النسخ الاحتياطي.");
                        if (!a.onHome) show();
                    }).show();
        });
    }

    private void saveBackupTo(File f) {
        a.db.setSettingQuiet("pending_backup_file", f.getAbsolutePath());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, f.getName());
        a.startForResult(i, MainActivity.CREATE_BACKUP);
    }

    private void shareBackup(File f) {
        try {
            File out = new File(a.sharedDir(), f.getName());
            Db.copyFile(f, out);
            a.db.recordBackup("SHARE", "تمت المشاركة", f.getName(), f.length(), a.db.counts(), false, "يرجى التأكد من وصول الملف كاملًا إلى الوجهة");
            a.markBackupDone("shared");
            a.shareFiles(Collections.singletonList(out), "application/octet-stream", "نسخة احتياطية - المحاسب الذكي");
        } catch (Exception e) {
            a.error(e);
        }
    }

    // ===================================================================================
    // الاستعادة
    // ===================================================================================

    private void restorePasswordDialog(final File file, final boolean temporary) {
        if (!a.require(Auth.Perm.RESTORE)) return;
        final EditText p = new EditText(a);
        p.setHint("كلمة مرور النسخة أو رمز استرداد النسخ");
        Ui.secret(p, false);
        LinearLayout l = new LinearLayout(a);
        l.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(4));
        l.addView(p, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(a).setTitle("كلمة مرور النسخة").setMessage("سيتم فك النسخة وفحصها كاملة وعرض محتواها قبل أي تغيير على بياناتك.")
                .setView(l).setPositiveButton("فك وفحص", (d, w) -> {
                    Editable e = p.getText();
                    final char[] pw = new char[e.length()];
                    e.getChars(0, e.length(), pw, 0);
                    p.setText("");
                    a.busy("جارٍ فك النسخة وفحصها…", pr -> {
                        try {
                            return BackupManager.prepareRestore(a, file, pw, pr);
                        } finally {
                            Arrays.fill(pw, '\0');
                        }
                    }, res -> restorePreview(file, temporary, (BackupManager.Prepared) res));
                })
                .setNegativeButton("إلغاء", (d, w) -> {
                    if (temporary) file.delete();
                }).show();
    }

    /** ملف من أداة النقل: الافتراضي إضافته إلى البيانات الحالية؛ الاستبدال خيار صريح. */
    private void restorePreview(final File file, final boolean temporary, final BackupManager.Prepared prep) {
        if (!"migration".equals(prep.parsed.manifest.optString("source"))) {
            replacePreview(file, temporary, prep);
            return;
        }
        BackupFormat.Report r = prep.report;
        new AlertDialog.Builder(a).setTitle("ملف من أداة النقل").setCancelable(false)
                .setMessage("في الملف " + r.offices + " مكتب و " + r.txActive + " حركة.\n\nكيف تريد استخدامه؟\n\n"
                        + "• إضافة إلى بياناتي الحالية (موصى به): تبقى كل مكاتبك وحركاتك كما هي، وتُضاف أرصدة الملف وحركاته. "
                        + "المكتب الموجود بالاسم نفسه تُضاف إليه، والباقي مكاتب جديدة. تظهر المعاينة قبل أي تغيير.\n\n"
                        + "• استبدال كل بياناتي: تُحذف بيانات التطبيق كلها ويحل محلها محتوى الملف فقط.")
                .setPositiveButton("إضافة إلى بياناتي", (d, w) -> mergePlan(file, temporary, prep))
                .setNeutralButton("استبدال الكل", (d, w) -> new AlertDialog.Builder(a).setTitle("⚠ استبدال كل البيانات؟")
                        .setMessage("ستُحذف كل مكاتبك وحركاتك الحالية (" + a.db.officeCount() + " مكتب، " + a.db.txCount() + " حركة) ويبقى محتوى الملف فقط. "
                                + "تُحفظ نسخة أمان يمكن التراجع إليها. هل أنت متأكد؟")
                        .setPositiveButton("نعم، استبدل", (d2, w2) -> replacePreview(file, temporary, prep))
                        .setNegativeButton("رجوع", (d2, w2) -> restorePreview(file, temporary, prep)).show())
                .setNegativeButton("إلغاء", (d, w) -> {
                    BackupManager.clearStaging(a);
                    if (temporary) file.delete();
                    a.restoreInput = null;
                }).show();
    }

    private void mergePlan(final File file, final boolean temporary, final BackupManager.Prepared prep) {
        a.busy("جارٍ مطابقة المكاتب وحساب الأرصدة…", p -> MergePlan.build(prep.parsed.tables, prep.parsed.manifest, a.db.mergeExisting()),
                res -> mergePreview(file, temporary, prep, (MergePlan) res));
    }

    private void mergePreview(final File file, final boolean temporary, final BackupManager.Prepared prep, final MergePlan plan) {
        final Runnable cancel = () -> {
            BackupManager.clearStaging(a);
            if (temporary) file.delete();
            a.restoreInput = null;
            show();
        };
        a.base("إضافة ملف النقل", cancel);
        LinearLayout c = a.card();
        c.addView(a.text("تُضاف أرصدة الملف وحركاته إلى بياناتك الحالية. لا يُحذف شيء.", 15, a.ink, true));
        c.addView(a.text("المكاتب: " + plan.matchedOffices + " موجود تُضاف إليه، و " + plan.newOffices + " جديد"
                + "\nالحركات: " + plan.txs.size() + "   •   الأرصدة السابقة: " + plan.openings.size()
                + (plan.newCurrencies.isEmpty() ? "" : "\nعملات جديدة: " + newCodes(plan)), 14, a.dark, false));
        for (String e : plan.errors) a.notice(R.drawable.ic_warn, e, true, null, null);
        for (String w : plan.warnings) a.notice(R.drawable.ic_warn, w, false, null, null);
        // 9.0: النسخة المجانية: إن تجاوزت المكاتب الجديدة الحد يختار المستخدم ما يُضاف منها
        final long maxOff = License.current.maxOffices();
        final long live = a.db.officeCount();
        final boolean over = maxOff != Entitlements.UNLIMITED && live + plan.newOffices > maxOff;
        if (over) {
            a.notice(R.drawable.ic_warn, "الملف يضيف " + plan.newOffices + " مكتبًا جديدًا وعندك " + live + "، والنسخة المجانية تتسع لـ " + maxOff
                    + ". اختر المكاتب الجديدة التي تُضاف (حتى " + Math.max(0, maxOff - live) + ")، أو فعّل النسخة الكاملة.", true, "اختيار المكاتب",
                    v -> pickNew(file, temporary, prep, plan, (int) Math.max(0, maxOff - live)));
        } else if (plan.droppedOffices > 0) {
            a.notice(R.drawable.ic_info, "استُبعد " + plan.droppedOffices + " مكتبًا جديدًا باختيارك؛ لا تُضاف حركاتها.", false, "إعادة الاختيار",
                    v -> mergePlan(file, temporary, prep));
        }
        // 10.16: اعتماد رصيد الملف كما هو بدل جمعه مع الرصيد الحالي
        if (plan.ok() && plan.adoptable() > 0) {
            final android.widget.CheckBox ad = new android.widget.CheckBox(a);
            ad.setText("اعتماد رصيد الملف كما هو (بدون جمعه مع الرصيد الحالي)");
            ad.setChecked(plan.adopt);
            ad.setOnCheckedChangeListener((b, on) -> {
                plan.setAdopt(on);
                mergePreview(file, temporary, prep, plan);
            });
            LinearLayout ac = a.card();
            ac.addView(ad);
            ac.addView(a.text(plan.adopt ? "للمكاتب الموجودة: يُلغى رصيدها الحالي بقيد تسوية ظاهر في الكشف، ثم يُكتب رصيد الملف، فيصبح الرصيد = رصيد الملف تمامًا. لا تُحذف أي حركة."
                    : plan.adoptable() + " رصيد لمكاتب موجودة سيُجمع مع رصيد الملف. إن كان الملف يحمل الرصيد الأحدث الكامل (لا فرقًا يُضاف) ففعّل هذا الخيار.", 13, a.muted, false));
        }
        if (plan.ok()) {
            a.hint("راجع كل سطر: «الآن» رصيد المكتب في التطبيق، «يُضاف» من الملف، «بعد الإضافة» ما سيصبح. "
                    + "إن ظهر مكتب «جديد» وهو موجود عندك باسم مختلف قليلًا، ألغِ وعدّل اسمه في أداة النقل (الخطوة 4) ليطابق اسمه هنا.");
            for (MergePlan.Row r : plan.rows) {
                LinearLayout x = a.card();
                LinearLayout h = a.row();
                h.addView(a.text(r.office, 15, a.ink, true), new LinearLayout.LayoutParams(0, -2, 1));
                h.addView(a.chip(r.isNew ? "جديد" : r.approximate ? "موجود (اسم مقارب)" : "موجود", r.isNew ? a.primaryInk : a.goldInk,
                        r.isNew ? a.primarySoft : a.goldSoft), new LinearLayout.LayoutParams(-2, -2));
                x.addView(h);
                x.addView(a.text(r.code + (r.txCount > 0 ? "   •   " + r.txCount + " حركة" : ""), 13, a.muted, false));
                boolean rep = plan.adopt && !r.isNew && r.current != 0;
                x.addView(a.text("الآن: " + Model.bal(r.current, r.decimals) + (rep ? " (يُلغى بقيد تسوية)" : "") + "\n" + (rep ? "رصيد الملف: " : "يُضاف: ")
                        + Model.bal(r.added, r.decimals) + "\n" + (rep ? "يُعتمد: " : "بعد الإضافة: ") + Model.bal(r.after, r.decimals), 14, a.dark, false));
            }
        }
        LinearLayout foot = a.row();
        Button go = a.makeButton("إضافة الآن", a.primary, v -> mergeApply(file, temporary, plan));
        go.setEnabled(plan.ok() && !over);
        if (!plan.ok() || over) go.setAlpha(0.45f);
        foot.addView(go, new LinearLayout.LayoutParams(0, a.dp(50), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, a.dp(50));
        cp.setMargins(a.dp(8), 0, 0, 0);
        foot.addView(a.makeButton("إلغاء", Color.rgb(130, 130, 130), v -> cancel.run()), cp);
        a.footer(foot);
    }

    private void pickNew(final File file, final boolean temporary, final BackupManager.Prepared prep, final MergePlan plan, final int allowed) {
        final List<MergePlan.Office> news = plan.newOfficeList();
        if (allowed <= 0) {
            new AlertDialog.Builder(a).setTitle("لا مكان لمكتب جديد").setMessage("عندك الحد الأقصى من المكاتب. احذف مكتبًا لا تحتاجه فيعود مكانه، "
                    + "أو أضف الملف إلى مكاتب بالأسماء نفسها، أو فعّل النسخة الكاملة.").setPositiveButton("حسنًا", null).show();
            return;
        }
        String[] names = new String[news.size()];
        final boolean[] on = new boolean[news.size()];
        for (int i = 0; i < news.size(); i++) {
            names[i] = news.get(i).name;
            on[i] = i < allowed;
        }
        new AlertDialog.Builder(a).setTitle("اختر حتى " + allowed + " من المكاتب الجديدة").setMultiChoiceItems(names, on, (d, w, c) -> on[w] = c)
                .setPositiveButton("تم", (d, w) -> {
                    java.util.Set<Long> keep = new java.util.HashSet<>();
                    for (int i = 0; i < on.length; i++) if (on[i]) keep.add(news.get(i).fileId);
                    if (keep.size() > allowed) {
                        a.toast("اخترت " + keep.size() + "، والمتاح " + allowed + " فقط");
                        pickNew(file, temporary, prep, plan, allowed);
                        return;
                    }
                    plan.keepNew(keep);
                    mergePreview(file, temporary, prep, plan);
                }).setNegativeButton("إلغاء", null).show();
    }

    private static String newCodes(MergePlan plan) {
        StringBuilder b = new StringBuilder();
        for (MergePlan.NewCurrency n : plan.newCurrencies) b.append(b.length() > 0 ? "، " : "").append(n.code);
        return b.toString();
    }

    private void mergeApply(final File file, final boolean temporary, final MergePlan plan) {
        a.busy("جارٍ حفظ نسخة أمان ثم الإضافة والتحقق…", p -> {
            Snapshot.take(a, a.db, "إضافة ملف النقل");
            p.update("جارٍ الإضافة والتحقق من الأرصدة…");
            return a.db.mergeMigration(plan);
        }, res -> {
            BackupManager.clearStaging(a);
            if (temporary) file.delete();
            a.restoreInput = null;
            new AlertDialog.Builder(a).setTitle("تمت الإضافة ✓")
                    .setMessage("أُضيف " + plan.txs.size() + " حركة و " + plan.openings.size() + " رصيد سابق إلى " + plan.offices.size() + " مكتب ("
                            + res + " جديد)" + (plan.adopt ? "، واعتُمد رصيد الملف للمكاتب الموجودة بدل جمعه" : "") + "، وتحقق التطبيق أن رصيد كل مكتب صار كما في المعاينة تمامًا.\n\n"
                            + "إن لم تكن هذه النتيجة المطلوبة: الإعدادات ← النسخ الاحتياطي ← «التراجع عن إضافة ملف النقل».")
                    .setPositiveButton("حسنًا", null).show();
            a.showHome();
        });
    }

    /**
     * 9.0: النسخة المجانية تتسع لـ 9 مكاتب: نسخة فيها أكثر لا تُستعاد كلها، بل يختار المستخدم المكاتب التي يريدها
     * (مقترحة: الأكثر حركات)، فتُستعاد بكل حركاتها وأرصدتها، ويبقى الصندوق والإعدادات كما في النسخة.
     */
    private void pickOffices(final File file, final boolean temporary, final BackupManager.Prepared prep, final long max, final Runnable back) {
        final List<BackupFormat.OfficeInfo> infos = BackupFormat.officeSummaries(prep.parsed);
        Collections.sort(infos, (x, y) -> x.txCount != y.txCount ? Long.compare(y.txCount, x.txCount) : Long.compare(y.lastTx, x.lastTx));
        final java.util.Set<Long> sel = new java.util.LinkedHashSet<>();
        if (prep.keep != null) sel.addAll(prep.keep);
        else for (BackupFormat.OfficeInfo o : infos) if (sel.size() < max) sel.add(o.id);
        final Runnable cancel = () -> {
            BackupManager.clearStaging(a);
            if (temporary) file.delete();
            a.restoreInput = null;
            show();
        };
        a.base("اختر المكاتب (" + max + " كحد أقصى)", cancel);
        LinearLayout c = a.card();
        c.addView(a.text("في النسخة " + infos.size() + " مكتبًا، والنسخة المجانية تتسع لـ " + max + ".", 16, a.ink, true));
        c.addView(a.text("اختر المكاتب التي تريد استعادتها: تُستعاد بكل حركاتها وأرصدتها كما في النسخة، والباقي لا يُستعاد. "
                + "اقترحنا الأكثر حركات؛ غيّر الاختيار كما تريد. لاستعادة الكل فعّل النسخة الكاملة.", 13, a.muted, false));
        final TextView counter = a.add(a.text("", 15, a.primaryInk, true));
        final Map<String, Integer> dec = a.db.decimalsMap();
        final List<CheckBox> boxes = new ArrayList<>();
        final Button[] go = new Button[1];
        final Runnable update = () -> {
            counter.setText("المختار: " + sel.size() + " من " + max);
            counter.setTextColor(sel.size() > max ? a.red : a.primaryInk);
            if (go[0] != null) {
                boolean ok = !sel.isEmpty() && sel.size() <= max;
                go[0].setEnabled(ok);
                go[0].setAlpha(ok ? 1f : 0.45f);
            }
        };
        for (final BackupFormat.OfficeInfo o : infos) {
            LinearLayout card = a.card();
            final CheckBox cb = new CheckBox(a);
            cb.setText(o.name.isEmpty() ? "مكتب #" + o.id : o.name);
            cb.setTextSize(16);
            cb.setChecked(sel.contains(o.id));
            card.addView(cb);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Long> e : o.balances.entrySet()) {
                if (e.getValue() == 0) continue;
                Integer d = dec.get(e.getKey());
                parts.add("\u2066" + Model.fmt(Math.abs(e.getValue()), d == null ? 2 : d) + " " + e.getKey() + "\u2069 " + (e.getValue() > 0 ? "لنا" : "علينا"));
            }
            card.addView(a.text(o.txCount + " حركة" + (o.lastTx > 0 ? " — آخرها " + Model.day(o.lastTx) : "") + (o.active ? "" : " — موقوف")
                    + (parts.isEmpty() ? "\nالرصيد صفر" : "\n" + Model.join(parts, "   •   ")), 13, a.muted, false));
            cb.setOnCheckedChangeListener((b, on) -> {
                if (on) sel.add(o.id);
                else sel.remove(o.id);
                update.run();
            });
            card.setOnClickListener(v -> cb.toggle());
            boxes.add(cb);
        }
        LinearLayout foot = a.row();
        go[0] = a.makeButton("متابعة", a.primary, v -> {
            prep.keep = new java.util.HashSet<>(sel);
            replacePreview(file, temporary, prep);
        });
        foot.addView(go[0], new LinearLayout.LayoutParams(0, a.dp(50), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, a.dp(50));
        cp.setMargins(a.dp(8), 0, 0, 0);
        foot.addView(a.makeButton("إلغاء", Color.rgb(130, 130, 130), v -> cancel.run()), cp);
        a.footer(foot);
        update.run();
    }

    private void replacePreview(final File file, final boolean temporary, final BackupManager.Prepared prep) {
        BackupFormat.Report r = prep.report;
        final long maxOff = License.current.maxOffices();
        if (maxOff != Entitlements.UNLIMITED && r.offices > maxOff && (prep.keep == null || prep.keep.size() > maxOff)) {
            pickOffices(file, temporary, prep, maxOff, null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("تاريخ النسخة: ").append(r.createdAt > 0 ? Model.date(r.createdAt) : "غير معروف")
                .append("\nإصدار التطبيق: ").append(r.appVersion.isEmpty() ? "—" : r.appVersion).append(" (صيغة ").append(r.format).append(")")
                .append("\nالمكاتب: ").append(r.offices).append(prep.keep != null ? " — تُستعاد " + prep.keep.size() + " مختارة فقط" : "")
                .append("\nالحركات: ").append(r.txActive).append(" نشطة + ").append(r.txCancelled).append(" ملغاة")
                .append("\nالصور: ").append(r.attachments)
                .append("\nالعملات: ").append(r.currencies)
                .append(r.cashMoves > 0 ? "\nحركات صندوق المكتب: " + r.cashMoves : "")
                .append("\n\n✓ القيود متوازنة والعلاقات سليمة.");
        for (String w : r.warnings) sb.append("\n⚠ ").append(w);
        sb.append("\n\nبياناتك الحالية: ").append(a.db.officeCount()).append(" مكتب و ").append(a.db.txCount()).append(" حركة؛ ستُحفظ منها نسخة أمان ثم تُستبدل.");
        // 10.13: مع تفضيلات المظهر التي في النسخة أو بدونها (يبقى مظهر كل مستخدم كما هو الآن)
        final android.widget.CheckBox look = new android.widget.CheckBox(a);
        look.setText("استعادة تفضيلات المظهر من النسخة (الألوان، الوضع، الخط، الشريط، الرئيسية)");
        look.setChecked(true);
        look.setTextSize(14);
        android.widget.LinearLayout lv = new android.widget.LinearLayout(a);
        lv.setPadding(a.dp(20), a.dp(4), a.dp(20), 0);
        lv.addView(look);
        new AlertDialog.Builder(a).setTitle("معاينة النسخة قبل الاستعادة").setMessage(sb.toString()).setView(lv).setCancelable(false)
                .setPositiveButton("استعادة الآن", (d, w) -> a.busy("جارٍ الاستعادة…", p -> {
                    prep.keepLook = !look.isChecked();
                    BackupManager.applyRestore(a, a.db, prep, p);
                    return null;
                }, res -> {
                    if (temporary) file.delete();
                    a.restoreInput = null;
                    a.db.recordBackup("RESTORE", "تمت الاستعادة", file.getName(), file.length(), a.db.counts(), true, "");
                    new AlertDialog.Builder(a).setTitle("تمت الاستعادة ✓")
                            .setMessage("تم استبدال البيانات بمحتوى النسخة. إن لم تكن هذه النسخة المطلوبة، يمكنك التراجع من «الإعدادات ← النسخ الاحتياطي ← التراجع عن آخر استعادة».")
                            .setPositiveButton("حسنًا", null).show();
                    afterDataReplaced();
                }))
                .setNeutralButton(prep.keep != null ? "تغيير المكاتب" : null, prep.keep == null ? null : (d, w) -> pickOffices(file, temporary, prep, maxOff, null))
                .setNegativeButton("إلغاء", (d, w) -> {
                    BackupManager.clearStaging(a);
                    if (temporary) file.delete();
                    a.restoreInput = null;
                }).show();
    }

    // ===================================================================================
    // نتائج اختيار الملفات والمجلدات
    // ===================================================================================

    /** يفتح منتقي ملف النسخة للاستعادة مباشرة (من شاشة «مفتاح التشفير غير متاح» مثلًا). */
    void pickRestoreFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        a.startForResult(i, MainActivity.OPEN_BACKUP);
    }

    void onResult(int r, int c, Intent data) {
        if (c != Activity.RESULT_OK) {
            if (r == MainActivity.CREATE_BACKUP) a.toast("لم يتم اختيار مكان؛ النسخة ما زالت محفوظة داخل التطبيق.");
            if (r == MainActivity.AUTO_TREE) a.toast("لم يتم اختيار مجلد.");
            if (r == MainActivity.DRIVE_FILE) a.toast("لم يتم اختيار ملف في Google Drive.");
            return;
        }
        if (r == MainActivity.DRIVE_FILE) {
            Uri file = data == null ? null : data.getData();
            if (file == null) {
                a.error(new Exception("لم يتم اختيار ملف."));
                return;
            }
            onDriveChosen(file);
            return;
        }
        if (r == MainActivity.AUTO_TREE) {
            Uri tree = data == null ? null : data.getData();
            if (tree == null) {
                a.error(new Exception("لم يتم اختيار مجلد."));
                return;
            }
            onTreeChosen(tree);
        } else if (r == MainActivity.CREATE_BACKUP) {
            final Uri target = data == null ? null : data.getData();
            final String path = a.db.setting("pending_backup_file", "");
            final File src = path.isEmpty() ? null : new File(path);
            if (target == null || src == null || !src.exists()) {
                a.error(new Exception("تعذر إكمال الحفظ: ملف النسخة غير موجود. أنشئ نسخة جديدة."));
                return;
            }
            a.busy("جارٍ حفظ النسخة والتحقق منها في المكان المختار…", p -> BackupManager.copyToUri(a, src, target), res -> {
                BackupManager.Delivery d = (BackupManager.Delivery) res;
                String name = BackupManager.displayName(a, target);
                a.db.deleteSetting("pending_backup_file");
                if (d.verified) {
                    a.db.recordBackup("SAVE", "تم الحفظ والتحقق", name, d.size, a.db.counts(), true, "");
                    a.markBackupDone("verified");
                    new AlertDialog.Builder(a).setTitle("تم الحفظ ✓")
                            .setMessage("تم حفظ النسخة باسم:\n" + name + "\n\nوأُعيدت قراءتها من المكان المختار وطابقت الأصل تمامًا.")
                            .setPositiveButton("حسنًا", null).show();
                } else {
                    a.db.recordBackup("SAVE", "حُفظت دون تحقق", name, d.size, a.db.counts(), false, d.note);
                    a.markBackupDone("unverified");
                    new AlertDialog.Builder(a).setTitle("تم الإرسال").setMessage(d.note).setPositiveButton("حسنًا", null).show();
                }
                if (!a.onHome) show();
            });
        } else if (r == MainActivity.OPEN_BACKUP) {
            final Uri src = data == null ? null : data.getData();
            if (src == null) {
                a.error(new Exception("لم يتم اختيار ملف"));
                return;
            }
            a.busy("جارٍ قراءة الملف…", p -> BackupManager.copyIn(a, src), res -> {
                a.restoreInput = (File) res;
                restorePasswordDialog(a.restoreInput, true);
            });
        }
    }
}
