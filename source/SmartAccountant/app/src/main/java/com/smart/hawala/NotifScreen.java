package com.smart.hawala;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.LinearLayout;
import android.widget.Switch;
import java.util.List;

/** 9.0: إعدادات الإشعارات: الأنواع الثلاثة، وأيام المطابقة، وساعات الإشعار، وخيارات كل مكتب. */
final class NotifScreen {
    private final MainActivity a;

    NotifScreen(MainActivity a) { this.a = a; }

    boolean allowedNow() { return allowed(); }

    private boolean allowed() {
        return Build.VERSION.SDK_INT < 33 || a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    void askPermission() {
        if (allowed()) return;
        try {
            a.markOwn();
            a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        } catch (Exception ignored) {
            a.ownActivity = false;
        }
    }

    private Switch toggle(LinearLayout g, int icon, String title, String sub, final String key, final Runnable after) {
        LinearLayout r = a.groupRow(g, icon, a.primaryInk, title, sub, null, null);
        final Switch sw = new Switch(a);
        sw.setChecked(!"0".equals(a.db.setting(key, "1")));
        sw.setOnCheckedChangeListener((b, c) -> {
            a.db.setSettingQuiet(key, c ? "1" : "0");
            if (c) askPermission();
            if (after != null) after.run();
        });
        r.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        r.setOnClickListener(v -> sw.toggle());
        return sw;
    }

    void show() {
        a.here(this::show);
        a.tabbedScreen("الإشعارات والتنبيهات", a.moreScreen::show, "more");
        // 10.18: كل إشعار ظهر يبقى في سجل (لا يضيع بالضغط عليه)
        if (a.can(Auth.Perm.NOTIFS)) a.button("سجل الإشعارات", a.accent, v -> showLog(0));
        if (!allowed()) a.notice(R.drawable.ic_bell, "الإشعارات غير مسموحة لهذا التطبيق في إعدادات الهاتف.", true, "السماح", v -> askPermission());
        LinearLayout g = a.group();
        toggle(g, R.drawable.ic_bell, "الإشعارات", "مفتاح عام لكل إشعارات التطبيق على هذا الهاتف", "notif_on", this::show);
        if (!Notifier.on(a.db)) {
            a.hint("الإشعارات متوقفة. أخطاء النسخ الاحتياطي تظهر داخل التطبيق دائمًا.");
            return;
        }
        a.section("متى أُشعرك؟", null, null);
        LinearLayout t = a.group();
        if (SyncEngine.enabled(a.db)) {
            toggle(t, R.drawable.ic_check2, "حركة من جهاز آخر بانتظار اعتمادي", "لمن يملك صلاحية الاعتماد: تصلك فور المزامنة", "notif_sync_pending", null);
            toggle(t, R.drawable.ic_sync, "حركات جديدة سجّلها الآخرون", "للمدير ومن يعتمد: ملخص الحركات الجديدة من الأجهزة الأخرى", "notif_sync_tx", null);
            toggle(t, R.drawable.ic_warn, "تعارضات المزامنة وتنبيهاتها", "تعديل من جهازين، تعديل لم يُطبَّق، تغيّر صلاحيات مستخدم… تظهر أيضًا في الصفحة الرئيسية", "notif_sync_review", null);
            toggle(t, R.drawable.ic_db, "دخول جهاز إلى المزامنة أو خروجه", "للمدير: كلما انضم جهاز جديد أو خرج جهاز أو أُزيل", "notif_sync_devices", null);
        }
        toggle(t, R.drawable.ic_shield, "عند إنشاء نسخة احتياطية تلقائية", "بعد كل نسخة تلقائية ناجحة (الفشل يُشعَر به دائمًا)", "notif_backup", null);
        toggle(t, R.drawable.ic_clock, "مكتب لم تُسجَّل عليه حركات منذ يوم كامل", "للمكاتب النشطة التي تعاملت معها في آخر 30 يومًا", "notif_idle", null);
        toggle(t, R.drawable.ic_check2, "مكتب لم تُرسل له مطابقة رصيد", "للمكاتب التي رصيدها ليس صفرًا", "notif_recon", null);
        final int days = Notifier.reconDays(a.db);
        a.groupRow(t, R.drawable.ic_calendar, a.primaryInk, "المطابقة متأخرة بعد", "اضغط للتغيير", days + " أيام", v -> {
            final String[] opts = {"يوم واحد", "3 أيام", "7 أيام", "14 يومًا", "30 يومًا"};
            final int[] vals = {1, 3, 7, 14, 30};
            int cur = 2;
            for (int i = 0; i < vals.length; i++) if (vals[i] == days) cur = i;
            new AlertDialog.Builder(a).setTitle("تنبيه المطابقة بعد").setSingleChoiceItems(opts, cur, (d, w) -> {
                d.dismiss();
                a.db.setSettingQuiet("notif_recon_days", "" + vals[w]);
                show();
            }).setNegativeButton("إلغاء", null).show();
        });
        final int hf = Notifier.hourFrom(a.db), ht = Notifier.hourTo(a.db);
        a.groupRow(t, R.drawable.ic_clock, a.primaryInk, "ساعات التذكير", "لا تظهر تذكيرات المكاتب خارجها", "من " + hf + " إلى " + ht, v -> hours());

        a.section("لكل مكتب", null, null);
        a.hint("أوقف التذكير لمكتب لا تتعامل معه يوميًا. الخيار نفسه في صفحة المكتب ← «البيانات».");
        LinearLayout og = a.group();
        List<Model.Office> offs = a.db.offices();
        int n = 0;
        for (final Model.Office o : offs) {
            if (!o.active) continue;
            boolean idle = a.db.officeNotif("notif_idle_off", o.id), rec = a.db.officeNotif("notif_recon_off", o.id);
            String state = idle && rec ? "الاثنان" : idle ? "الحركات فقط" : rec ? "المطابقة فقط" : "متوقف";
            a.groupRow(og, R.drawable.ic_offices, a.primaryInk, o.name, "بلا حركات منذ يوم • مطابقة لم تُرسل", state, v -> officeDialog(o));
            n++;
        }
        if (n == 0) a.groupRow(og, R.drawable.ic_offices, a.muted, "لا توجد مكاتب نشطة", null, null, null);
        a.button("إرسال إشعار تجريبي", a.accent, v -> {
            askPermission();
            Notifier.backupDone(a, a.db, "تجربة — هكذا يظهر الإشعار", null, true);
            a.toast(allowed() ? "أُرسل إشعار تجريبي" : "اسمح بالإشعارات أولًا");
        });
    }

    private void officeDialog(final Model.Office o) {
        final boolean[] st = {a.db.officeNotif("notif_idle_off", o.id), a.db.officeNotif("notif_recon_off", o.id)};
        new AlertDialog.Builder(a).setTitle(o.name)
                .setMultiChoiceItems(new String[]{"لم تُسجَّل حركات منذ يوم كامل", "لم تُرسل مطابقة رصيد"}, st, (d, w, c) -> st[w] = c)
                .setPositiveButton("حفظ", (d, w) -> {
                    a.db.setOfficeNotif("notif_idle_off", o.id, st[0]);
                    a.db.setOfficeNotif("notif_recon_off", o.id, st[1]);
                    show();
                }).setNegativeButton("إلغاء", null).show();
    }

    private void hours() {
        final String[] hs = new String[24];
        for (int i = 0; i < 24; i++) hs[i] = String.format(java.util.Locale.US, "%02d:00", i);
        new AlertDialog.Builder(a).setTitle("تبدأ التذكيرات من").setSingleChoiceItems(hs, Notifier.hourFrom(a.db), (d, from) -> {
            d.dismiss();
            final String[] ts = new String[24];
            for (int i = 0; i < 24; i++) ts[i] = String.format(java.util.Locale.US, "%02d:00", i + 1 == 24 ? 0 : i + 1);
            new AlertDialog.Builder(a).setTitle("وتنتهي عند").setSingleChoiceItems(ts, Notifier.hourTo(a.db) - 1, (d2, to) -> {
                d2.dismiss();
                a.db.setSettingQuiet("notif_hour_from", "" + from);
                a.db.setSettingQuiet("notif_hour_to", "" + (to + 1));
                show();
            }).setNegativeButton("إلغاء", null).show();
        }).setNegativeButton("إلغاء", null).show();
    }

    /** 10.18: سجل الإشعارات على هذا الجهاز: كل ما ظهر من إشعارات، وكل تنبيهات المزامنة (المفتوح منها وما تمّ). */
    void showLog(final int tab) {
        // 11.6: تنبيهات حساسة: لمن أعطاه المدير صلاحية «سجل الإشعارات»
        if (!a.require(Auth.Perm.NOTIFS)) return;
        a.here(() -> showLog(tab));
        a.base("سجل الإشعارات", this::show);
        final Ui.Seg seg = a.new Seg(new String[]{"كل الإشعارات", "تنبيهات المزامنة"}, null, tab, false);
        seg.listener = i -> showLog(i);
        a.body.addView(seg.view, a.lp(-1, -2, 4));
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        if (tab == 0) rows = a.db.notifLog(500);
        else for (String[] c : SyncEngine.noticeHistory(a.db, 500))
            rows.add(new String[]{Model.date(Long.parseLong(c[1])), SyncStore.kindName(c[2]) + ("1".equals(c[7]) ? " — تمت" : " — مفتوح"), c[5]});
        if (rows.isEmpty()) a.hint("لا شيء بعد.");
        for (String[] r : rows) {
            LinearLayout c = a.card();
            c.addView(a.text(r[1], 15, a.primaryInk, true));
            if (!r[2].isEmpty()) {
                android.widget.TextView t = a.text(r[2], 14, a.ink, false);
                t.setTextIsSelectable(true);
                c.addView(t);
            }
            c.addView(a.text(r[0], 12, a.muted, false));
        }
        if (tab == 0 && !rows.isEmpty()) a.button("مسح سجل الإشعارات", a.red, v -> a.confirm("مسح سجل الإشعارات", "يُمسح سجل الإشعارات على هذا الهاتف. تنبيهات المزامنة تبقى.", "مسح", () -> {
            a.db.clearNotifLog();
            showLog(0);
        }));
    }
}
