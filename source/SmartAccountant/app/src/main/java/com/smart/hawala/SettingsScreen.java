package com.smart.hawala;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** الأمان وإقفال الفترة، سجل العمليات، وفحص البيانات. باقي الإعدادات في تبويب «المزيد» (MoreScreen). */
final class SettingsScreen {
    private final MainActivity a;

    SettingsScreen(MainActivity a) { this.a = a; }

    /** الإعدادات صارت مجمّعة في تبويب «المزيد». */
    void show() { a.moreScreen.show(); }

    /** القفل والبصمة وإقفال الفترة المحاسبية. */
    void showSecurity() {
        if (!a.require(Auth.Perm.SETTINGS)) return;
        a.here(this::showSecurity);
        a.tabbedScreen("أمان المكتب وإقفال الفترة", a.moreScreen::show, "more");
        a.hint("قفل التطبيق بالبصمة أو بكلمة سر صار لكل مستخدم على جهازه: «المزيد ← حسابي وجهازي ← قفل التطبيق». "
                + "واعتماد الحركات صار لكل مستخدم: «المستخدمون والصلاحيات ← حركاته تحتاج اعتماد».");
        a.title("لقطات الشاشة");
        final CheckBox secure = new CheckBox(a);
        secure.setText("منع لقطات الشاشة وإخفاء التطبيق في قائمة التطبيقات الأخيرة");
        secure.setTextColor(a.ink);
        secure.setChecked(!"0".equals(a.db.setting("secure_screen", "1")));
        secure.setOnCheckedChangeListener((b, c) -> {
            a.db.setSetting("secure_screen", c ? "1" : "0");
            a.applySecureScreen();
        });
        a.body.addView(secure);
        a.hint("بطاقات الأرصدة والكشوف تُشارك من أزرار المشاركة داخل التطبيق، فلا حاجة إلى لقطات الشاشة.");

        // 10.18: الوقت الموحّد — توقيت المكتب لكل الأجهزة، وتصحيح ساعة الجهاز تلقائيًا
        a.title("الوقت الموحّد");
        for (String line : a.db.clockLines()) a.add(a.text(line, 14, a.ink, false));
        a.hint("كل الأجهزة تعرض التواريخ وتحسب «اليوم» بتوقيت المكتب، ولو كان المحاسب في بلد آخر. وساعة كل جهاز تُصحَّح من وقت Google عند كل مزامنة (ويبقى التصحيح محفوظًا بلا إنترنت).");
        if (a.can(Auth.Perm.SETTINGS)) a.button("تغيير توقيت المكتب…", a.accent, v -> {
            final java.util.List<String> ids = new java.util.ArrayList<>();
            ids.add("");
            for (String[] z : Model.ZONES) ids.add(z[0]);
            for (String id : java.util.TimeZone.getAvailableIDs()) if (id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/") && !ids.contains(id)) ids.add(id);
            final java.util.List<String> labels = new java.util.ArrayList<>();
            for (String id : ids) labels.add(Model.zoneLabel(id));
            // قائمة مع خانة بحث: اكتب اسم المدينة فتظهر
            final android.widget.EditText q = new android.widget.EditText(a);
            q.setHint("ابحث: دمشق، Istanbul…");
            final android.widget.ListView lv = new android.widget.ListView(a);
            final java.util.List<Integer> shown = new java.util.ArrayList<>();
            final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(a, android.R.layout.simple_list_item_1, new java.util.ArrayList<String>());
            lv.setAdapter(ad);
            final Runnable fill = () -> {
                String f = q.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                shown.clear();
                ad.clear();
                for (int i = 0; i < ids.size(); i++) if (f.isEmpty() || labels.get(i).toLowerCase(java.util.Locale.ROOT).contains(f)) {
                    shown.add(i);
                    ad.add((ids.get(i).equals(Model.zoneId()) ? "✓ " : "") + labels.get(i));
                }
                ad.notifyDataSetChanged();
            };
            q.addTextChangedListener(Ui.watcher(fill));
            fill.run();
            android.widget.LinearLayout box = new android.widget.LinearLayout(a);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setPadding(a.dp(16), a.dp(6), a.dp(16), 0);
            box.addView(q);
            box.addView(lv, new android.widget.LinearLayout.LayoutParams(-1, a.dp(380)));
            final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("توقيت المكتب").setView(box).setNegativeButton("إلغاء", null).create();
            lv.setOnItemClickListener((p, vw, pos, idd) -> {
                dlg.dismiss();
                try {
                    a.db.setOfficeZone(ids.get(shown.get(pos)));
                    a.toast("توقيت المكتب: " + Model.zoneLabel(Model.zoneId()));
                    showSecurity();
                } catch (Exception e) {
                    a.error(e);
                }
            });
            dlg.show();
        });

        a.title("إقفال الفترة المحاسبية");
        long lockBefore = a.db.settingLong("lock_before", 0);
        a.add(a.text(lockBefore > 0 ? "الحركات المسجّلة قبل " + Model.day(lockBefore) + " مقفلة: لا يمكن إلغاؤها أو تعديلها أو تسجيل حركة بتاريخ قبلها." : "لا توجد فترة مقفلة.", 15, a.dark, false));
        a.button("إقفال الحركات حتى تاريخ…", a.accent, v -> {
            Calendar c = Model.cal();
            DatePickerDialog dp = new DatePickerDialog(a, (x, y, m, d) -> {
                Calendar k = Model.cal();
                k.set(y, m, d);
                k.add(Calendar.DAY_OF_MONTH, 1);
                final long until = StatementScreen.startOfDay(k);
                new AlertDialog.Builder(a).setTitle("تأكيد الإقفال")
                        .setMessage("ستُقفل كل الحركات حتى نهاية يوم " + Model.day(until - 1) + ". لن يمكن إلغاء أو تعديل أي حركة منها إلا بعد فك الإقفال.")
                        .setPositiveButton("إقفال", (q, w) -> {
                            a.db.setLockBefore(until);
                            showSecurity();
                        }).setNegativeButton("إلغاء", null).show();
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            dp.getDatePicker().setMaxDate(Clock.now());
            dp.show();
        });
        if (lockBefore > 0) {
            a.button("فك إقفال الفترة", a.red, v -> new AlertDialog.Builder(a).setTitle("فك الإقفال")
                    .setMessage("سيُسجَّل فك الإقفال في سجل العمليات. هل تريد المتابعة؟")
                    .setPositiveButton("فك الإقفال", (q, w) -> {
                        a.db.setLockBefore(0);
                        showSecurity();
                    }).setNegativeButton("إلغاء", null).show());
        }
    }

    // ===================================================================================
    // سجل العمليات
    // ===================================================================================

    static String actionName(String code) {
        Map<String, String> m = new HashMap<>();
        m.put("CREATE_TRANSACTION", "تسجيل حركة");
        m.put(UsageScreen.ACTION, "جلسة استخدام");
        m.put("EDIT_TRANSACTION", "تعديل حركة");
        m.put("CANCEL_TRANSACTION", "إلغاء حركة");
        m.put("CREATE_OFFICE", "إضافة مكتب");
        m.put("UPDATE_OFFICE", "تعديل مكتب");
        m.put("SAVE_CURRENCY", "حفظ عملة");
        m.put("MANUAL_RATE", "سعر صرف يدوي");
        m.put("SETTING", "تغيير إعداد");
        m.put("EXPORT", "تصدير");
        m.put("RESTORE", "استعادة نسخة");
        m.put("REVERT_RESTORE", "تراجع عن استعادة");
        m.put("ADD_USER", "إضافة مستخدم");
        m.put("UPDATE_USER", "تعديل مستخدم");
        m.put("RESET_PIN", "تغيير رمز");
        m.put("DISABLE_USERS", "إيقاف نظام المستخدمين");
        m.put("LOGIN", "دخول");
        m.put("LOGIN_FAILED", "محاولة دخول فاشلة");
        m.put("OPERATION_FAILED", "عملية فشلت");
        m.put("MIGRATION", "نقل بيانات من برنامج سابق");
        String v = m.get(code);
        return v == null ? code : v;
    }

    void showAudit() {
        if (!a.require(Auth.Perm.AUDIT)) return;   // 11.6: صلاحية مستقلة يختارها المدير
        a.here(this::showAudit);
        a.tabbedScreen("سجل العمليات", a.moreScreen::show, "more");
        a.hint(SyncEngine.enabled(a.db) ? "آخر 300 عملية على كل أجهزة المزامنة: من فعل ماذا ومتى وعلى أي جهاز. للقراءة فقط."
                : "آخر 300 عملية: من فعل ماذا ومتى. للقراءة فقط.");
        final boolean usageOk = UsageScreen.allowed(a);
        if (usageOk) a.body.addView(a.smallButton("سجل الاستخدام: متى وكم استخدم كل مستخدم التطبيق", a.accent, v -> a.usageScreen.show()), a.lp(-1, 42, 3));
        List<String[]> rows = a.db.auditLogAll(300);
        if (rows.isEmpty()) a.hint("لا يوجد.");
        for (String[] r : rows) {
            StringBuilder sb = new StringBuilder();
            if (UsageScreen.ACTION.equals(r[1])) {
                if (!usageOk) continue;   // 11.6: جلسات الاستخدام للمدير وحده
                sb.append(r[0]).append("  •  جلسة استخدام");
                if (!r[4].isEmpty()) sb.append("\nالمستخدم: ").append(r[4]);
                if (!r[6].isEmpty()) sb.append(r[4].isEmpty() ? "\n" : "  •  ").append("الجهاز: ").append(r[6]);
                if (!r[5].isEmpty()) sb.append("\n").append(r[5]);
                TextView ut = a.add(a.text(sb.toString(), 12, a.muted, false));
                ut.setBackground(a.round(a.surface, a.line, 10));
                continue;
            }
            sb.append(r[0]).append("  •  ").append(actionName(r[1]));
            if (!r[3].isEmpty() && !"0".equals(r[3])) sb.append(" #").append(r[3]);
            if (!r[4].isEmpty()) sb.append("\nالمستخدم: ").append(r[4]);
            if (!r[6].isEmpty()) sb.append(r[4].isEmpty() ? "\n" : "  •  ").append("الجهاز: ").append(r[6]);
            if (!r[5].isEmpty()) sb.append("\n").append(r[5]);
            TextView t = a.add(a.text(sb.toString(), 13, r[1].contains("FAIL") || r[1].startsWith("CANCEL") ? a.red : a.dark, false));
            t.setBackground(a.round(a.surface, a.line, 10));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.setMargins(0, a.dp(2), 0, a.dp(2));
            t.setLayoutParams(p);
        }
    }

    // ===================================================================================
    // فحص صحة البيانات
    // ===================================================================================

    void showHealth() {
        a.busy("جارٍ فحص البيانات…", p -> a.db.healthCheck(), res -> {
            @SuppressWarnings("unchecked") List<Db.Check> checks = (List<Db.Check>) res;
            a.here(this::showHealth);
            a.tabbedScreen("فحص صحة البيانات", a.moreScreen::show, "more");
            a.hint("هذا الفحص للقراءة فقط ولا يغيّر أي بيانات.");
            int bad = 0;
            for (Db.Check c : checks) {
                if (!c.ok) bad++;
                LinearLayout x = a.card();
                x.addView(a.text((c.ok ? "✓ " : "✗ ") + c.title, 16, c.ok ? a.primary : a.red, true));
                if (!c.detail.isEmpty()) x.addView(a.text(c.detail, 13, a.dark, false));
            }
            TextView sum = a.text(bad == 0 ? "النتيجة: البيانات سليمة ✓" : "النتيجة: توجد " + bad + " ملاحظة. احتفظ بنسخة احتياطية وأرسل لقطة من هذه الشاشة للمطوّر.", 16, bad == 0 ? a.primary : a.red, true);
            a.body.addView(sum, 1);
        });
    }
}
