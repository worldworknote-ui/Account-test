package com.smart.hawala;

import android.app.AlertDialog;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * الصفحة الرئيسية الحديثة: عناصر يختارها المستخدم ويرتبها كما يريد (المزيد ← المظهر، أو زر القلم في الرئيسية).
 * كل الأرقام من دفتر القيود مباشرة (Ledger.totals على أرصدة المكاتب، Db.dailyStats…) دون أي حساب موازٍ.
 */
final class HomeScreen {
    private final MainActivity a;
    /** مكان بطاقة «نسخت نصًا قبل قليل»؛ تُملأ عندما تحصل النافذة على التركيز (شرط أندرويد لقراءة الحافظة). */
    private LinearLayout clipSlot;

    HomeScreen(MainActivity a) { this.a = a; }

    // ===================================================================================
    // العناصر المتاحة
    // ===================================================================================

    static final class Widget {
        final String key, title, desc;
        final boolean defOn, hasOptions;

        Widget(String key, String title, String desc, boolean defOn, boolean hasOptions) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.defOn = defOn;
            this.hasOptions = hasOptions;
        }
    }

    static final Widget[] WIDGETS = {
            new Widget("search", "البحث", "شريط البحث في الحركات والمكاتب", true, false),
            new Widget("welcome", "الرسالة الترحيبية", "النص الذي تكتبه في «المظهر»", true, false),
            new Widget("alerts", "التنبيهات", "دفعة واتساب غير مكتملة، النسخ الاحتياطي، رمز الاسترداد", true, false),
            new Widget("cash", "صندوق المكتب", "النقد في الصندوق بكل عملة (يظهر بعد إعداد الصندوق)", true, false),
            new Widget("clipboard", "اقتراح اللصق", "بطاقة تظهر عندما تنسخ نصًا من واتساب قبل قليل", true, false),
            new Widget("net", "صافي الأرصدة لكل عملة", "بطاقة لكل عملة: الصافي ومجموع لنا وعلينا", true, true),
            new Widget("totals", "جدول الإجمالي لكل عملة", "لنا وعلينا والصافي وعدد المكاتب لكل عملة", false, true),
            new Widget("usd", "التقويم التقريبي بالدولار", "مجموع لنا وعلينا والصافي بالدولار حسب أسعار الصرف", false, false),
            new Widget("today", "اليوم", "عدد حركات اليوم، المطابقات المنتظرة، المسودة", true, false),
            new Widget("daily", "ملخص اليوم بالعملات", "لنا وعلينا والأجور لكل عملة اليوم", false, true),
            new Widget("offices", "أرصدة المكاتب", "مكاتب بأرصدتها: المثبّتة، أو الأكبر رصيدًا", false, true),
            new Widget("recent_tx", "آخر الحركات", "آخر الحركات المسجّلة", false, true),
            new Widget("recons", "المطابقات بانتظار التأكيد", "المكاتب التي لم تؤكد رصيدها بعد", false, false),
            new Widget("limits", "تجاوز حدود المكاتب", "المكاتب التي تجاوزت الحد الذي وضعته", false, false),
            new Widget("recent_offices", "آخر المكاتب", "أزرار سريعة لآخر المكاتب التي فتحتها", true, false),
            new Widget("paste", "لصق رسائل واتساب", "زر كبير للصق رسالة أو عدة رسائل منسوخة", true, false),
            new Widget("shortcuts", "الاختصارات", "أزرار الوصول السريع (ترتيبها وألوانها من «المظهر»)", true, false),
            new Widget("rates", "أسعار الصرف", "سعر كل عملة مقابل الدولار", false, true)
    };
    // 12.6: اسم البرنامج وإصداره لم يعودا عنصرًا يُخفى: يظهران دائمًا آخر الصفحة (signature() بعد كل العناصر).

    static Widget widget(String key) {
        for (Widget w : WIDGETS) if (w.key.equals(key)) return w;
        return null;
    }

    /** الترتيب والإظهار: «key» ظاهر، «-key» مخفي. العناصر الجديدة تُضاف في آخر القائمة بحالتها الافتراضية. */
    static List<String[]> layout(Db db) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String part : db.setting("home_widgets", "").split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            boolean on = !p.startsWith("-");
            String k = on ? p : p.substring(1);
            if (widget(k) == null || !seen.add(k)) continue;
            out.add(new String[]{k, on ? "1" : "0"});
        }
        for (Widget w : WIDGETS) if (!seen.contains(w.key)) out.add(new String[]{w.key, w.defOn ? "1" : "0"});
        return out;
    }

    static void saveLayout(Db db, List<String[]> l) {
        StringBuilder sb = new StringBuilder();
        for (String[] x : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append("1".equals(x[1]) ? x[0] : "-" + x[0]);
        }
        db.setSettingQuiet("home_widgets", sb.toString());
    }

    static int iconFor(String key) {
        switch (key) {
            case "tx": return R.drawable.ic_plus;
            case "log": return R.drawable.ic_list;
            case "statement": return R.drawable.ic_doc;
            case "totals": return R.drawable.ic_scale;
            case "daily": return R.drawable.ic_calendar;
            case "search": return R.drawable.ic_search;
            case "office": return R.drawable.ic_offices;
            case "currency": return R.drawable.ic_coins;
            case "backup": return R.drawable.ic_shield;
            case "cash": return R.drawable.ic_cash;
            case "reports": return R.drawable.ic_report;
            case "mine": return R.drawable.ic_check2;
            default: return R.drawable.ic_sliders;
        }
    }

    // ===================================================================================
    // الرسم
    // ===================================================================================

    void show() {
        String office = a.db.setting("office_name", "").trim();
        String title = office.isEmpty() ? a.db.setting("app_title", "المحاسب الذكي لمكاتب الحوالات") : office;
        a.here(this::show);
        a.rootScreen(title, "home");
        a.onHome = true;
        clipSlot = null;
        a.screenTitle.setTextColor(a.flatHeader ? a.primaryInk : a.headerInk);

        // اسم المستخدم الداخل (بلا تحية) فوق العنوان، والشعار في بداية الشريط
        Model.User u = SmartApp.currentUser;
        ViewGroup barRow = (ViewGroup) a.screenTitle.getParent();
        int at = barRow.indexOfChild(a.screenTitle);
        barRow.removeView(a.screenTitle);
        LinearLayout tcol = new LinearLayout(a);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setGravity(Gravity.CENTER_VERTICAL);
        a.screenTitle.setPadding(a.dp(6), 0, a.dp(6), 0);
        if (u != null && u.name != null && !u.name.trim().isEmpty()) {
            TextView g = a.text(u.name, 13, a.flatHeader ? a.muted : a.headerInk, false);
            g.setPadding(a.dp(6), 0, a.dp(6), 0);
            g.setSingleLine(true);
            tcol.addView(g);
        }
        // العنوان (اسم المكتب) كان يحمل مقاسات الشريط الأفقي (عرض 0 بوزن)، فيختفي داخل العمود العمودي
        tcol.addView(a.screenTitle, new LinearLayout.LayoutParams(-1, -2));
        barRow.addView(tcol, at, new LinearLayout.LayoutParams(0, -1, 1));
        Bitmap logo = "none".equals(a.db.setting("home_logo_mode", "default")) ? null : a.homeLogo();
        if (logo != null) {
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(logo);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a.dp(40), a.dp(40));
            lp.setMargins(a.dp(4), 0, a.dp(2), 0);
            barRow.addView(iv, at, lp);
        }
        a.eyeAction(this::show);
        // 10.9: الوضع الليلي والنهاري بضغطة من الرئيسية (الإعداد نفسه في «المظهر»)
        a.headerAction(a.night ? R.drawable.ic_sun : R.drawable.ic_moon, a.night ? "الوضع النهاري" : "الوضع الليلي", v -> {
            a.db.setSettingQuiet("theme_mode", a.night ? "light" : "dark");
            a.restyle("home");
        });
        a.headerAction(R.drawable.ic_edit, "تخصيص الصفحة الرئيسية", v -> editor());
        if (u != null) a.headerAction(R.drawable.ic_user, "المستخدم", v -> userMenu(u));

        int shown = 0;
        // 10.13: حدود العناصر (لتقسيم الشاشة العريضة عمودين دون فصل عنوان عن محتواه)
        List<Integer> bounds = new ArrayList<>();
        for (String[] w : layout(a.db)) {
            if (!"1".equals(w[1])) continue;
            bounds.add(a.body.getChildCount());
            try {
                render(w[0], office);
                shown++;
            } catch (Exception e) {
                // عنصر واحد لا يُسقط الصفحة كلها
                a.hint("تعذر عرض «" + widget(w[0]).title + "»: " + Ui.friendly(e));
            }
        }
        if (shown == 0) {
            LinearLayout c = a.card();
            c.addView(a.text("أخفيت كل عناصر الصفحة الرئيسية", 16, a.ink, true));
            c.addView(a.makeButton("اختيار العناصر", a.primary, v -> editor()), a.lp(-1, 48, 6));
        }
        if (a.wide() && bounds.size() >= 3) {
            int n = a.body.getChildCount(), best = -1;
            for (int b : bounds) if (b > 0 && b < n && (best < 0 || Math.abs(b - n / 2) < Math.abs(best - n / 2))) best = b;
            if (best > 0) a.splitBody(a.body.getChildAt(best));
        }
        signature();
        if (!a.checkPreviousCrash() && a.can(Auth.Perm.BACKUP)) a.maybeReminderDialog(a.backupReminderText());
    }

    private void render(String key, String office) {
        switch (key) {
            case "search": search(); break;
            case "welcome": {
                String welcome = MainActivity.welcomeText(a.db.setting("welcome", ""), office);
                if (!welcome.isEmpty()) a.hint(welcome);
                break;
            }
            case "alerts": attention(); break;
            case "clipboard":
                if (a.can(Auth.Perm.TX_ADD)) {
                    clipSlot = new LinearLayout(a);
                    clipSlot.setOrientation(LinearLayout.VERTICAL);
                    a.body.addView(clipSlot, new LinearLayout.LayoutParams(-1, -2));
                    refreshClipCard();
                }
                break;
            case "net": netBalances(); break;
            case "cash": a.cashScreen.homeCard(); break;
            case "totals": totalsTable(); break;
            case "usd": usdValue(); break;
            case "today": todayGroup(); break;
            case "daily": dailyByCurrency(); break;
            case "offices": officeBalances(); break;
            case "recent_tx": recentTx(); break;
            case "recons": pendingRecons(); break;
            case "limits": limits(); break;
            case "recent_offices": recentOffices(); break;
            case "paste": pasteCard(); break;
            case "shortcuts": shortcuts(); break;
            case "rates": rates(); break;
            default: break;
        }
    }

    /** 12.6: ثابت وغير قابل للإخفاء، وآخر شيء في الصفحة (بعرض الشاشة كله حتى بالعمودين). */
    private void signature() {
        TextView name = a.text(BuildInfo.APP_NAME, 13, a.muted, true);
        name.setGravity(Gravity.CENTER);
        a.body.addView(name, a.lp(-1, -2, 14));
        TextView dev = a.text("الإصدار " + a.versionName() + " • " + a.db.setting("developer", "برمجة: Ai creative solutions"), 12, a.muted, false);
        dev.setGravity(Gravity.CENTER);
        dev.setContentDescription("اسم البرنامج وإصداره");
        a.body.addView(dev, a.lp(-1, -2, 2));
    }

    private void search() {
        if (!a.can(Auth.Perm.VIEW)) return;
        LinearLayout sb = a.officeHub.searchBox("ابحث: مكتب، مستفيد، مبلغ، مرجع");
        final android.widget.EditText q = (android.widget.EditText) sb.getTag();
        q.setFocusable(false);
        q.setOnClickListener(v -> a.logScreen.searchFor(""));
        sb.setOnClickListener(v -> a.logScreen.searchFor(""));
        a.body.addView(sb, a.lp(-1, 50, 6));
    }

    private Set<String> chosenCurrencies(String key) {
        Set<String> s = new java.util.LinkedHashSet<>();
        for (String c : a.db.setting(key, "").split(",")) if (!c.trim().isEmpty()) s.add(c.trim());
        return s;
    }

    private TextView small(String s, int size, int color, boolean bold) {
        TextView t = a.text(s, size, color, bold);
        t.setPadding(a.dp(2), a.dp(1), a.dp(2), a.dp(1));
        return t;
    }

    // ===================================================================================
    // تنبيهات: دفعة واتساب، رمز الاسترداد، النسخ الاحتياطي
    // ===================================================================================

    private void attention() {
        int queued = a.waQueue.remaining();
        if (queued > 0 && a.can(Auth.Perm.TX_ADD)) {
            LinearLayout qc = a.card();
            qc.setBackground(a.round(a.primarySoft, a.primary, 16));
            LinearLayout h = a.row();
            h.addView(a.icon(R.drawable.ic_chat, a.primaryInk, 22), new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
            h.addView(a.text("دفعة رسائل واتساب لم تكتمل: بقيت " + queued + " رسالة", 15, a.primaryInk, true), new LinearLayout.LayoutParams(0, -2, 1));
            qc.addView(h);
            LinearLayout qr = a.row();
            qr.addView(a.smallButton("متابعة التسجيل", a.primary, v -> a.waQueue.showCurrent()), a.weight(44, 1.3f, 2));
            qr.addView(a.smallButton("إنهاء الدفعة", Color.rgb(120, 120, 120), v -> a.waQueue.stop()), a.weight(44, 1, 2));
            qc.addView(qr);
        }
        // 10.17: رسائل المدير غير المقروءة
        for (final Db.AdminMsg am : a.db.unreadAdminMsgs()) a.notice(R.drawable.ic_info, "رسالة من المدير" + (am.from.isEmpty() ? "" : " (" + a.db.shown(am.from) + ")")
                + " — " + Model.date(am.at) + ":\n" + am.body, true, "قرأتها", v -> {
            a.db.markAdminMsgRead(am.uuid);
            show();
        });
        // 10.16: تعارضات المزامنة وتعثرها تظهر هنا (كانت لا تُرى إلا داخل شاشة المزامنة)
        if (a.can(Auth.Perm.NOTIFS)) for (String[] al : SyncEngine.homeAlerts(a.db)) a.notice(R.drawable.ic_warn, al[0], "1".equals(al[1]), "المزامنة", v -> a.syncScreen.show());
        if (SyncEngine.enabled(a.db) && (a.can(Auth.Perm.TX_CANCEL) || a.can(Auth.Perm.APPROVE))) {
            int dups = SyncEngine.duplicates(a.db).size();
            if (dups > 0) a.notice(R.drawable.ic_warn, dups + " زوج حركات قد يكون مكررًا (سُجّل من جهازين)", true, "مراجعة", v -> a.syncScreen.showDuplicates());
        }
        if (a.can(Auth.Perm.APPROVE) && a.db.usersEnabled()) {
            long pend = a.db.pendingCount();
            if (pend > 0) a.notice(R.drawable.ic_check2, pend + " حركة بانتظار اعتمادك", false, "مراجعة", v -> a.logScreen.showPending());
        }
        // 9.0: إذن الإشعارات (أندرويد 13+) لتذكيرات المكاتب والنسخ التلقائي: يُطلب مرة من هنا، وبعدها من «المزيد ← الإشعارات»
        if (Notifier.on(a.db) && !a.notifScreen.allowedNow() && !"1".equals(a.db.setting("notif_asked", "0"))) {
            a.notice(R.drawable.ic_bell, "اسمح بالإشعارات لتصلك تذكيرات المكاتب (لا حركات منذ يوم، مطابقة لم تُرسل) وتأكيد النسخ التلقائي.", false, "السماح", v -> {
                a.db.setSettingQuiet("notif_asked", "1");
                a.notifScreen.askPermission();
            });
        }
        Model.User u = SmartApp.currentUser;
        if (u != null && Auth.ADMIN.equals(u.role) && !a.db.hasRecoveryCode()) {
            a.notice(R.drawable.ic_key, "لا يوجد رمز استرداد للمدير. إن نُسي رمز المدير لن يمكن استعادة الدخول.", true, "إنشاء", v -> a.usersScreen.createRecoveryFromHome());
        }
        if (a.can(Auth.Perm.BACKUP)) {
            String autoErr = a.db.setting("auto_backup_error", "");
            if (!autoErr.isEmpty()) a.notice(R.drawable.ic_warn, "فشل النسخ التلقائي الأخير", true, "التفاصيل", v -> a.backupScreen.show());
            String driveErr = a.db.setting("drive_backup_error", "");
            if (!driveErr.isEmpty()) a.notice(R.drawable.ic_warn, "فشل حفظ النسخة في Google Drive", true, "التفاصيل", v -> a.backupScreen.show());
            String reminder = a.backupReminderText();
            if (reminder != null) a.notice(R.drawable.ic_shield, reminder, false, "نسخة الآن", v -> a.backupScreen.backupDialog());
            // 8.0 (ت14): شارة الاعتمادية: تظهر حين يحتاج شيء انتباهًا (9.0: يمكن إخفاؤها من لوحة الاعتمادية)
            if (Reliability.homeAlerts(a.db)) try {
                List<Reliability.Item> rel = Reliability.items(a);
                int lv = Reliability.overall(rel);
                if (lv != Reliability.GREEN) {
                    String first = "";
                    for (Reliability.Item i : rel) if (i.level == lv) { first = i.title; break; }
                    a.notice(R.drawable.ic_pulse, "اعتمادية التطبيق: " + Reliability.word(lv) + " — " + first, lv == Reliability.RED, "التفاصيل", v -> Reliability.screen(a));
                }
            } catch (Throwable ignored) {
                // لا تمنع الرئيسية
            }
        }
    }

    // ===================================================================================
    // اقتراح اللصق: يُقرأ وصف الحافظة فقط (وقت النسخ)، ولا يُقرأ النص إلا عند ضغط المستخدم
    // ===================================================================================

    void refreshClipCard() {
        final LinearLayout slot = clipSlot;
        if (slot == null || !a.onHome) return;
        slot.removeAllViews();
        // 12.1: وضع التجميع: شريط بعدد ما جُمع وزر «نقل»
        if (a.waMove.collecting) {
            LinearLayout r = a.row();
            r.setPadding(a.dp(12), a.dp(8), a.dp(8), a.dp(8));
            r.setBackground(a.round(a.primarySoft, a.primarySoft, 14));
            int n = a.waMove.collected.size();
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(a.text("وضع التجميع: " + (n == 0 ? "لم تُجمع رسائل بعد" : "جُمعت " + n + (n == 1 ? " رسالة" : " رسائل")), 14, a.primaryInk, true));
            col.addView(a.text(WaMove.desktop() ? "انسخ كل رسالة من واتساب وحدها (Ctrl+C أو ⌘C)، فتُضاف هنا." : "انسخ رسالة في واتساب ثم ارجع هنا، وكرّر لكل رسالة.", 12, a.primaryInk, false));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            Button mv = a.smallButton("نقل", a.primary, v -> a.waMove.moveCollected());
            mv.setEnabled(n > 0);
            r.addView(mv, new LinearLayout.LayoutParams(-2, a.dp(36)));
            r.addView(a.iconButton(R.drawable.ic_close, a.primaryInk, "إيقاف التجميع", v -> a.waMove.stopCollect()), new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
            slot.addView(r, a.lp(-1, -2, 5));
            return;
        }
        // 12.1: على الكمبيوتر تُقرأ الحافظة مباشرة (لا قيود أندرويد): بطاقة بعدد رسائل واتساب المنسوخة
        if (WaMove.desktop()) {
            int n;
            String sig;
            try {
                n = a.waMove.clipCount();
                sig = "d" + WaText.fingerprint(WaClip.read(a).text).hashCode();
            } catch (Throwable t) {
                return;
            }
            if (n <= 0 || sig.equals(a.db.setting("clip_seen", ""))) return;
            final String seen = sig;
            LinearLayout r = a.row();
            r.setPadding(a.dp(12), a.dp(10), a.dp(8), a.dp(10));
            r.setBackground(a.round(a.goldSoft, a.goldSoft, 14));
            r.addView(a.icon(R.drawable.ic_paste, a.goldInk, 22), new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(a.text(n == 1 ? "نسخت رسالة حوالة" : "في الحافظة " + n + " رسائل من واتساب", 14, a.goldInk, true));
            col.addView(a.text(n == 1 ? "اضغط لنقلها إلى نموذج التسجيل." : "اضغط لنقلها إلى البرنامج: تظهر قائمتها فتسجّل كل رسالة وحدها.", 12, a.goldInk, false));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(a.iconButton(R.drawable.ic_close, a.goldInk, "إخفاء", v -> {
                a.db.setSettingQuiet("clip_seen", seen);
                slot.removeAllViews();
            }), new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
            r.setOnClickListener(v -> {
                a.db.setSettingQuiet("clip_seen", seen);
                pasteFromClipboard();
            });
            slot.addView(r, a.lp(-1, -2, 5));
            return;
        }
        long ts = 0;
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipDescription d = cm == null ? null : cm.getPrimaryClipDescription();
            if (d == null || !(d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType("text/*"))) return;
            if (Build.VERSION.SDK_INT >= 26) ts = d.getTimestamp();
        } catch (Exception ignored) {
            return;
        }
        long now = Clock.now();
        if (ts <= 0 || now - ts > 15 * 60000L || now < ts - 60000L) return;
        if (String.valueOf(ts).equals(a.db.setting("clip_seen", ""))) return;
        final String seen = String.valueOf(ts);
        LinearLayout r = a.row();
        r.setPadding(a.dp(12), a.dp(10), a.dp(8), a.dp(10));
        r.setBackground(a.round(a.goldSoft, a.goldSoft, 14));
        r.addView(a.icon(R.drawable.ic_paste, a.goldInk, 22), new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(a.text("نسخت نصًا قبل قليل", 14, a.goldInk, true));
        col.addView(a.text("اضغط لتسجيله. إن كان عدة رسائل من واتساب تُعرض كدفعة لتسجّلها واحدة تلو الأخرى.", 12, a.goldInk, false));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(a.iconButton(R.drawable.ic_close, a.goldInk, "إخفاء", v -> {
            a.db.setSettingQuiet("clip_seen", seen);
            slot.removeAllViews();
        }), new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
        r.setOnClickListener(v -> {
            a.db.setSettingQuiet("clip_seen", seen);
            pasteFromClipboard();
        });
        slot.addView(r, a.lp(-1, -2, 5));
    }

    // ===================================================================================
    // الأرصدة
    // ===================================================================================

    private Map<String, Ledger.CurrencyTotal> totals() {
        return Ledger.totals(a.db.officeBalances());
    }

    private void netBalances() {
        if (!a.can(Auth.Perm.VIEW)) return;
        Map<String, Ledger.CurrencyTotal> totals = totals();
        Map<String, String> names = a.db.currencyNames();
        Map<String, Integer> dec = a.db.decimalsMap();
        Set<String> only = chosenCurrencies("hw_net_curs");
        a.section("صافي الأرصدة", "كل المكاتب", v -> a.balancesScreen.show());
        LinearLayout strip = a.row();
        strip.setGravity(Gravity.TOP);
        int n = 0;
        for (Ledger.CurrencyTotal t : totals.values()) {
            if (t.receivable == 0 && t.payable == 0) continue;
            if (!only.isEmpty() && !only.contains(t.code)) continue;
            n++;
            int d = dec.containsKey(t.code) ? dec.get(t.code) : 2;
            long net = t.net();
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            c.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
            String nm = names.get(t.code);
            c.addView(small((nm == null || nm.isEmpty() ? "" : nm + " · ") + t.code, 12, a.muted, false));
            TextView amt = small(a.mf(Math.abs(net), d), 20, a.ink, true);
            amt.setTextDirection(View.TEXT_DIRECTION_LTR);
            amt.setGravity(Gravity.RIGHT);
            c.addView(amt);
            TextView chip = net > 0 ? a.chip("لنا", a.green, a.creditBg) : net < 0 ? a.chip("علينا", a.red, a.debitBg) : a.chip("متوازن", a.muted, a.line);
            LinearLayout cr = a.row();
            cr.addView(chip, new LinearLayout.LayoutParams(-2, -2));
            c.addView(cr);
            c.addView(small("لنا " + a.mf(t.receivable, d) + " · علينا " + a.mf(t.payable, d), 11, a.muted, false));
            c.setOnClickListener(v -> a.balancesScreen.show());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(a.dp(164), -2);
            p.setMargins(a.dp(4), a.dp(2), a.dp(4), a.dp(2));
            strip.addView(c, p);
        }
        if (n == 0) {
            a.add(a.text("لا توجد أرصدة بعد. سجّل أول حركة لتظهر هنا أرصدة كل عملة.", 14, a.muted, false));
            return;
        }
        a.body.addView(a.strip(strip), a.lp(-1, -2, 2));
    }

    private void totalsTable() {
        if (!a.can(Auth.Perm.VIEW)) return;
        Map<String, Ledger.CurrencyTotal> totals = totals();
        Map<String, String> names = a.db.currencyNames();
        Map<String, Integer> dec = a.db.decimalsMap();
        Set<String> only = chosenCurrencies("hw_net_curs");
        a.section("الإجمالي لكل عملة", "التفاصيل", v -> a.balancesScreen.show());
        LinearLayout g = a.group();
        for (Ledger.CurrencyTotal t : totals.values()) {
            if (t.receivable == 0 && t.payable == 0) continue;
            if (!only.isEmpty() && !only.contains(t.code)) continue;
            int d = dec.containsKey(t.code) ? dec.get(t.code) : 2;
            String nm = names.get(t.code);
            String sub = "لنا " + a.mf(t.receivable, d) + " (" + t.officesReceivable + " مكتب) • علينا " + a.mf(t.payable, d) + " (" + t.officesPayable + " مكتب)";
            a.groupRow(g, R.drawable.ic_coins, a.primaryInk, t.code + (nm == null || nm.isEmpty() ? "" : " — " + nm), sub, a.mb(t.net(), d), v -> a.balancesScreen.show());
        }
        if (g.getChildCount() == 0) a.groupRow(g, R.drawable.ic_info, a.muted, "لا توجد أرصدة بعد", null, null, null);
    }

    private void usdValue() {
        if (!a.can(Auth.Perm.VIEW)) return;
        Map<String, BigDecimal> usd = a.db.usdRates();
        Ledger.Valuation v = Ledger.value(totals(), usd, a.db.decimalsMap());
        a.section("التقويم التقريبي بالدولار", "أسعار الصرف", x -> a.officesScreen.showRates(a::showHome));
        LinearLayout c = a.card();
        LinearLayout r = a.row();
        r.addView(valueCol("لنا", Db.fmtUsd(v.receivableUsd), a.green), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(valueCol("علينا", Db.fmtUsd(v.payableUsd), a.red), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(valueCol("الصافي", Db.fmtUsd(v.netUsd()), v.netUsd().signum() >= 0 ? a.green : a.red), new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(r);
        if (!v.missingRates.isEmpty()) c.addView(a.text("بدون سعر صرف (غير محسوبة): " + v.missingRates, 12, a.warnInk, false));
        c.addView(a.text("تقريبي للمتابعة فقط؛ الأرصدة الحقيقية بعملاتها.", 11, a.muted, false));
    }

    private View valueCol(String label, String value, int color) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(small(label, 12, a.muted, false));
        TextView t = small(a.m("$" + value), 17, color, true);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setGravity(Gravity.RIGHT);
        col.addView(t);
        return col;
    }

    // ===================================================================================
    // اليوم
    // ===================================================================================

    private void todayGroup() {
        if (!a.can(Auth.Perm.VIEW)) return;
        LinearLayout g = a.group();
        long start = DailyScreen.dayStart(Clock.now());
        long count = 0;
        for (long[] v : a.db.dailyStats(start, DailyScreen.nextDay(start)).values()) count += v[4];
        a.groupRow(g, R.drawable.ic_clock, a.primaryInk, "حركات اليوم", count == 0 ? "لا توجد حركات اليوم بعد" : null,
                count == 0 ? null : String.valueOf(count), v -> a.dailyScreen.show(Clock.now()));
        final List<Model.Office> pending = pendingOffices();
        if (!pending.isEmpty()) {
            a.groupRow(g, R.drawable.ic_check2, a.warnInk, "مطابقات بانتظار تأكيد المكتب", null, String.valueOf(pending.size()), v -> {
                if (pending.size() == 1) {
                    a.officeHub.showOffice(pending.get(0));
                    return;
                }
                String[] items = new String[pending.size()];
                for (int i = 0; i < items.length; i++) items[i] = pending.get(i).name;
                new AlertDialog.Builder(a).setTitle("بانتظار التأكيد").setItems(items, (d, w) -> a.officeHub.showOffice(pending.get(w))).show();
            });
        }
        // 10.7: اختصار «مكاتبي» للمحاسب
        if (a.mineScreen.available()) {
            int mine = a.db.myOffices().size();
            int waiting = a.can(Auth.Perm.VERIFY) ? a.db.awaitingMyCheck(60, 200).size() : 0;
            a.groupRow(g, R.drawable.ic_check2, a.primaryInk, "مكاتبي", mine == 0 ? "لم تُخصص لك مكاتب بعد" : mine + " مكتب" + (waiting > 0 ? " • " + waiting + " بانتظار تحققك" : ""),
                    waiting > 0 ? String.valueOf(waiting) : null, v -> a.mineScreen.show());
        }
        if (a.can(Auth.Perm.TX_ADD) && !a.db.setting("draft_tx", "").isEmpty()) {
            a.groupRow(g, R.drawable.ic_edit, a.primaryInk, "حركة لم تُحفظ بعد (مسودة)", "تابع تسجيلها من حيث توقفت", null, v -> a.txScreen.showNew());
        }
    }

    private List<Model.Office> pendingOffices() {
        List<Model.Office> pending = new ArrayList<>();
        for (Map.Entry<Long, Model.Recon> e : a.db.lastReconsByOffice().entrySet()) {
            if (!Model.RECON_SENT.equals(e.getValue().status)) continue;
            Model.Office o = a.db.office(e.getKey());
            if (o != null) pending.add(o);
        }
        return pending;
    }

    private void dailyByCurrency() {
        if (!a.can(Auth.Perm.VIEW)) return;
        long start = DailyScreen.dayStart(Clock.now());
        Map<String, long[]> stats = a.db.dailyStats(start, DailyScreen.nextDay(start));
        Set<String> only = chosenCurrencies("hw_net_curs");
        a.section("ملخص اليوم", "التقرير اليومي", v -> a.dailyScreen.show(Clock.now()));
        LinearLayout g = a.group();
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            if (!only.isEmpty() && !only.contains(e.getKey())) continue;
            long[] v = e.getValue();
            int d = a.db.decimals(e.getKey());
            StringBuilder sub = new StringBuilder("لنا " + Model.fmt(v[0], d) + " • علينا " + Model.fmt(v[1], d));
            if (v[2] > 0) sub.append(" • أجور لنا ").append(Model.fmt(v[2], d));
            if (v[3] > 0) sub.append(" • أجور علينا ").append(Model.fmt(v[3], d));
            a.groupRow(g, R.drawable.ic_calendar, a.primaryInk, e.getKey(), sub.toString(), v[4] + " حركة", x -> a.dailyScreen.show(Clock.now()));
        }
        if (g.getChildCount() == 0) a.groupRow(g, R.drawable.ic_info, a.muted, "لا توجد حركات اليوم بعد", null, null, null);
    }

    // ===================================================================================
    // المكاتب والحركات
    // ===================================================================================

    private void officeBalances() {
        if (!a.can(Auth.Perm.VIEW)) return;
        String mode = a.db.setting("hw_offices_mode", "pinned");
        int n = (int) a.db.settingLong("hw_offices_n", 5);
        final Map<Long, Map<String, Long>> bal = a.db.officeBalances();
        final Map<String, Integer> dec = a.db.decimalsMap();
        final Map<String, BigDecimal> usd = a.db.usdRates();
        List<Model.Office> all = a.db.offices();
        List<Model.Office> list = new ArrayList<>();
        if ("pinned".equals(mode)) {
            for (Model.Office o : all) if (o.pinned) list.add(o);
        } else {
            for (Model.Office o : all) {
                Map<String, Long> b = bal.get(o.id);
                if (b == null) continue;
                boolean has = false;
                for (long v : b.values()) if ("top_us".equals(mode) ? v > 0 : "top_them".equals(mode) ? v < 0 : v != 0) has = true;
                if (has) list.add(o);
            }
            // الترتيب بالقيمة التقريبية بالدولار (لنا أو علينا)
            final boolean us = !"top_them".equals(mode);
            Collections.sort(list, (x, y) -> worth(bal.get(y.id), usd, dec, us).compareTo(worth(bal.get(x.id), usd, dec, us)));
        }
        String title = "pinned".equals(mode) ? "المكاتب المثبّتة" : "top_us".equals(mode) ? "أكبر الأرصدة لنا" : "top_them".equals(mode) ? "أكبر الأرصدة علينا" : "أرصدة المكاتب";
        a.section(title, "كل المكاتب", v -> a.officeHub.showList());
        if (list.isEmpty()) {
            a.add(a.text("pinned".equals(mode) ? "لا توجد مكاتب مثبّتة. ثبّت مكتبًا من صفحته (أيقونة الدبوس)." : "لا توجد أرصدة.", 13, a.muted, false));
            return;
        }
        int k = 0;
        for (Model.Office o : list) {
            if (++k > n) break;
            Map<String, Long> b = bal.get(o.id);
            a.body.addView(a.officeHub.officeCard(o, b == null ? new HashMap<String, Long>() : b, dec, a::showHome), a.lp(-1, -2, 4));
        }
    }

    private static BigDecimal worth(Map<String, Long> b, Map<String, BigDecimal> usd, Map<String, Integer> dec, boolean us) {
        BigDecimal sum = BigDecimal.ZERO;
        if (b == null) return sum;
        for (Map.Entry<String, Long> e : b.entrySet()) {
            long v = e.getValue();
            if (us ? v <= 0 : v >= 0) continue;
            BigDecimal r = usd.get(e.getKey());
            Integer d = dec.get(e.getKey());
            if (r == null) continue;
            sum = sum.add(Ledger.toUsd(Math.abs(v), d == null ? 2 : d, r));
        }
        return sum;
    }

    private void recentTx() {
        if (!a.can(Auth.Perm.VIEW)) return;
        int n = (int) a.db.settingLong("hw_tx_n", 5);
        List<Model.Tx> txs = a.db.recentTx(n);
        a.section("آخر الحركات", "السجل", v -> a.logScreen.show(false));
        if (txs.isEmpty()) {
            a.add(a.text("لا توجد حركات بعد.", 13, a.muted, false));
            return;
        }
        Map<Long, String> names = a.db.officeNames();
        for (final Model.Tx t : txs) {
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
            c.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
            c.addView(a.logScreen.txRow(t, names.get(t.officeId), null));
            c.setOnClickListener(v -> a.logScreen.details(t, a::showHome));
            a.body.addView(c, a.lp(-1, -2, 4));
        }
    }

    private void pendingRecons() {
        if (!a.can(Auth.Perm.VIEW)) return;
        List<Model.Office> pending = pendingOffices();
        a.section("المطابقات بانتظار التأكيد", null, null);
        LinearLayout g = a.group();
        Map<Long, Model.Recon> last = a.db.lastReconsByOffice();
        for (final Model.Office o : pending) {
            Model.Recon r = last.get(o.id);
            a.groupRow(g, R.drawable.ic_check2, a.warnInk, o.name, r == null ? null : "أُرسلت " + Model.date(r.createdAt), null, v -> a.officeHub.showOffice(o));
        }
        if (pending.isEmpty()) a.groupRow(g, R.drawable.ic_check, a.green, "لا توجد مطابقات منتظرة", null, null, null);
    }

    private void limits() {
        if (!a.can(Auth.Perm.VIEW)) return;
        List<String> all = new ArrayList<>();
        for (Model.Office o : a.db.offices()) all.addAll(a.officesScreen.limitAlerts(o));
        a.section("تجاوز حدود المكاتب", null, null);
        if (all.isEmpty()) {
            a.add(a.text("لا يوجد مكتب تجاوز حده.", 13, a.muted, false));
            return;
        }
        a.notice(R.drawable.ic_warn, Model.join(all, "\n"), true, null, v -> a.balancesScreen.show());
    }

    // ===================================================================================
    // آخر المكاتب
    // ===================================================================================

    private void recentOffices() {
        if (!a.can(Auth.Perm.VIEW)) return;
        List<Model.Office> list = a.officeHub.recentOffices(6);
        if (list.size() < 6) {
            for (Model.Office o : a.db.offices()) {
                if (list.size() >= 6) break;
                boolean has = false;
                for (Model.Office x : list) if (x.id == o.id) has = true;
                if (!has && o.pinned) list.add(o);
            }
        }
        if (list.isEmpty() && !a.can(Auth.Perm.OFFICES)) return;
        a.section(list.isEmpty() ? "المكاتب" : "آخر المكاتب", list.isEmpty() ? null : "كل المكاتب", v -> a.officeHub.showList());
        Ui.Flow f = a.flow();
        for (final Model.Office o : list) f.addView(a.pill(o.name, false, v -> a.officeHub.showOffice(o)));
        if (a.can(Auth.Perm.OFFICES)) {
            TextView add = a.pill("+ مكتب جديد", false, v -> a.officesScreen.form(null));
            android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setCornerRadius(a.dp(50));
            dg.setStroke(a.dp(1), Ui.mix(a.muted, a.surface, 0.3f), a.dp(5), a.dp(4));
            add.setBackground(a.ripple(dg, 50));
            add.setTextColor(a.muted);
            f.addView(add);
        }
        a.body.addView(f, a.lp(-1, -2, 4));
    }

    // ===================================================================================
    // لصق رسائل واتساب
    // ===================================================================================

    private void pasteCard() {
        if (!a.can(Auth.Perm.TX_ADD)) return;
        LinearLayout c = a.row();
        c.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{a.primary, Ui.darker(a.primary, 0.8f)});
        g.setCornerRadius(a.dp(18));
        c.setBackground(a.ripple(g, 18));
        FrameLayout ib = new FrameLayout(a);
        android.graphics.drawable.GradientDrawable ig = new android.graphics.drawable.GradientDrawable();
        ig.setColor(Color.argb(36, 255, 255, 255));
        ig.setCornerRadius(a.dp(14));
        ib.setBackground(ig);
        ib.addView(a.icon(R.drawable.ic_paste, Color.WHITE, 24), new FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER));
        c.addView(ib, new LinearLayout.LayoutParams(a.dp(46), a.dp(46)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(a.dp(12), 0, 0, 0);
        TextView t1 = new TextView(a);
        t1.setText("لصق رسائل واتساب");
        t1.setTextSize(16);
        t1.setTextColor(Color.WHITE);
        Ui.bold(t1);
        TextView t2 = new TextView(a);
        t2.setText("في واتساب: حدّد رسالة أو عدة رسائل ← نسخ ← ثم اضغط هنا");
        t2.setTextSize(12);
        t2.setTextColor(Ui.mix(Color.WHITE, a.primary, 0.18f));
        col.addView(t1);
        col.addView(t2);
        c.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        c.setOnClickListener(v -> pasteFromClipboard());
        a.body.addView(c, a.lp(-1, -2, 10));
        // 12.1: طرق أخرى للنقل: التقاط من الشاشة (ويندوز وماك)، وضع التجميع، القص اليدوي
        // 12.3: أزرار بأيقونات، وحُذف «نافذة واتساب» (الالتقاط فيه «حدد النافذة»)، وأُضيف «قص الرسائل» ظاهرًا
        if (a.can(Auth.Perm.TX_WA)) {
            LinearLayout more = a.row();
            more.setGravity(Gravity.CENTER);
            if (a.waMove.canCapture())
                more.addView(a.toolChip(R.drawable.ic_capture, "التقاط من الشاشة", 1, v -> a.waMove.openCapture(false)), moreLp(more));
            if (a.waMove.showCollect() || a.waMove.collecting)
                more.addView(a.toolChip(R.drawable.ic_collect, a.waMove.collecting ? "إيقاف التجميع" : "وضع التجميع", 1,
                        v -> { if (a.waMove.collecting) a.waMove.stopCollect(); else a.waMove.startCollect(); }), moreLp(more));
            if (a.waMove.showCut()) more.addView(a.toolChip(R.drawable.ic_cut, "قص الرسائل", 1, v -> cutFromClipboard()), moreLp(more));
            if (more.getChildCount() > 0) a.body.addView(more, a.lp(-1, -2, 2));
        }
        TextView how = a.text("كيف أنسخ عدة رسائل من واتساب؟", 13, a.primaryInk, true);
        how.setGravity(Gravity.CENTER);
        how.setBackground(a.ripple(null, 10));
        how.setOnClickListener(v -> waHelp());
        a.body.addView(how, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout.LayoutParams moreLp(LinearLayout row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, a.dp(40), 1);
        if (row.getChildCount() > 0) p.setMarginStart(a.dp(6));
        return p;
    }

    /** 12.3: «قص الرسائل» من الرئيسية: النص المنسوخ فيه عدة رسائل ملزوقة يُقطع يدويًا. */
    void cutFromClipboard() {
        WaClip.Read clip = WaClip.read(a);
        String text = clip.text == null ? "" : clip.text.trim();
        if (WaCuts.lines(text).size() < 2) {
            new android.app.AlertDialog.Builder(a).setTitle("قص الرسائل")
                    .setMessage("«قص» لنص فيه عدة رسائل ملزوقة ببعض.\n\n1. حدّد الرسائل في واتساب وانسخها.\n2. اضغط «قص الرسائل».\n"
                            + "3. اضغط الخط المنقّط بين سطرين ليبدأ من هناك رسالة جديدة، ثم «متابعة».")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        a.beforeScreenChange();
        a.waMove.cutScreen(text);
    }

    /** شرح خطوات نسخ رسالة أو عدة رسائل من واتساب ولصقها. */
    void waHelp() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(18), a.dp(6), a.dp(18), a.dp(6));
        String[][] steps = {
                {"1", "في محادثة واتساب اضغط مطولًا على رسالة الحوالة حتى تتلوّن."},
                {"2", "لعدة رسائل: اضغط ضغطة عادية على كل رسالة أخرى تريدها فتُحدَّد كلها."},
                {"3", "اضغط زر «نسخ» في الشريط العلوي لواتساب (مربعان فوق بعض)."},
                {"4", "افتح المحاسب الذكي واضغط «لصق رسائل واتساب» في الرئيسية، أو زر اللصق في نموذج التسجيل."},
                {"5", "رسالة واحدة: يُفتح نموذج التسجيل مع المعلومات المستخرجة. عدة رسائل: تظهر قائمتها، تحدّد ما تريد، ثم تسجّل كل رسالة وتضغط «تسجيل والتالية»."}
        };
        // 12.1: طرق إضافية: الالتقاط من الشاشة على الكمبيوتر، والتجميع على كل الأجهزة
        List<String[]> all = new ArrayList<>(java.util.Arrays.asList(steps));
        if (WaMove.desktop()) {
            all.add(new String[]{"6", "على ويندوز وماك: «التقاط من الشاشة» (Ctrl/⌘+G) ثم «حدد النافذة» واضغط على واتساب: يظهر رقم على كل رسالة، اضغط الرقم لنقلها. مرّر في واتساب والإطار يقرأ وحده."});
            all.add(new String[]{"7", "«وضع التجميع»: انسخ الرسائل من واتساب واحدة تلو الأخرى فتظهر مرقّمة في نافذة صغيرة، ثم «نقل»."});
        } else if (a.waMove.showCollect()) {
            all.add(new String[]{"6", "«وضع التجميع»: انسخ رسالة في واتساب وارجع للتطبيق، وكرّر لكل رسالة، ثم «نقل»."});
        }
        if (a.waMove.showCut())
            all.add(new String[]{String.valueOf(all.size() + 1), "«قص الرسائل»: إن نُسخت عدة رسائل ملزوقة ببعض، اضغطه ثم اضغط الخط المنقّط بين سطرين ليبدأ من هناك رسالة جديدة."});
        for (String[] s : all) {
            LinearLayout r = a.row();
            r.setGravity(Gravity.TOP);
            TextView n = a.text(s[0], 14, Color.WHITE, true);
            n.setGravity(Gravity.CENTER);
            n.setPadding(0, 0, 0, 0);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(a.primary);
            n.setBackground(g);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(a.dp(26), a.dp(26));
            np.setMargins(0, a.dp(6), 0, 0);
            r.addView(n, np);
            r.addView(a.text(s[1], 14, a.ink, false), new LinearLayout.LayoutParams(0, -2, 1));
            l.addView(r);
        }
        l.addView(a.text("ملاحظة: واتساب لا يتيح «مشاركة» الرسائل النصية مع التطبيقات الأخرى (يتيح فقط إعادة توجيهها لمحادثة)، لذلك النسخ ثم اللصق هو الطريق. لا يُسجَّل شيء دون تأكيدك.", 12, a.muted, false));
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(l);
        new AlertDialog.Builder(a).setTitle("تسجيل رسائل واتساب").setView(sv)
                .setPositiveButton("الصق الآن", (d, w) -> pasteFromClipboard()).setNegativeButton("إغلاق", null).show();
    }

    /** يقرأ الحافظة: رسالة واحدة ← نموذج تسجيل، عدة رسائل ← دفعة واتساب. لا يُسجَّل شيء دون تأكيد المحاسب. */
    void pasteFromClipboard() {
        if (!a.require(Auth.Perm.TX_ADD)) return;
        // 12.1: الحافظة بكل صيغها (رؤوس واتساب، فقاعات HTML من ويندوز والمتصفح، أسطر الوقت)، و«القص» إن بقي النص ملزوقًا
        WaClip.Read clip = WaClip.read(a);
        String text = clip.text;
        if (text == null || text.trim().isEmpty()) {
            new AlertDialog.Builder(a).setTitle("الحافظة فارغة")
                    .setMessage("انسخ رسالة الحوالة من واتساب أولًا: اضغط عليها مطولًا ثم زر «نسخ». لعدة رسائل حدّدها كلها ثم «نسخ».")
                    .setPositiveButton("الخطوات بالتفصيل", (d, w) -> waHelp())
                    .setNeutralButton("نموذج فارغ", (d, w) -> a.txScreen.showNew())
                    .setNegativeButton("حسنًا", null).show();
            return;
        }
        a.waMove.route(WaClip.messages(text, clip.html), text);
    }

    // ===================================================================================
    // الاختصارات (ترتيبها وإخفاؤها وألوانها من «المظهر»)
    // ===================================================================================

    private void shortcuts() {
        Map<String, String> names = MainActivity.homeNames();
        Set<String> hidden = MainActivity.parseSet(a.db.setting("home_hidden", ""));
        Map<String, String> colors = MainActivity.parseColors(a.db.setting("btn_colors", ""));
        List<String> keys = new ArrayList<>();
        for (String k : MainActivity.parseOrder(a.db.setting("home_order", Db.DEFAULT_ORDER))) {
            if (hidden.contains(k)) continue;
            Auth.Perm p = a.permFor(k);
            if (p != null && !a.can(p)) continue;
            if (k.equals("mine") && !a.mineScreen.available()) continue;
            keys.add(k);
        }
        if (keys.isEmpty()) return;
        a.section("اختصارات", null, null);
        LinearLayout r = null;
        for (int i = 0; i < keys.size(); i++) {
            if (i % 3 == 0) {
                r = a.row();
                r.setGravity(Gravity.TOP);
                a.body.addView(r, new LinearLayout.LayoutParams(-1, -2));
            }
            final String k = keys.get(i);
            int col = Ui.parseColor(colors.get(k), a.primaryInk);
            LinearLayout t = new LinearLayout(a);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(a.dp(4), a.dp(12), a.dp(4), a.dp(10));
            t.setBackground(a.ripple(a.round(a.surface, a.line, 16), 16));
            FrameLayout ib = new FrameLayout(a);
            ib.setBackground(a.round(Ui.mix(a.surface, col, a.night ? 0.25f : 0.1f), Ui.mix(a.surface, col, a.night ? 0.25f : 0.1f), 12));
            ib.addView(a.icon(iconFor(k), a.readable(col), 22), new FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER));
            t.addView(ib, new LinearLayout.LayoutParams(a.dp(40), a.dp(40)));
            TextView l = a.text(names.get(k), 12, a.ink, true);
            l.setGravity(Gravity.CENTER);
            l.setMaxLines(2);
            t.addView(l, new LinearLayout.LayoutParams(-1, -2));
            t.setOnClickListener(v -> a.openHome(k));
            r.addView(t, a.weight(-2, 1, 4));
        }
        if (keys.size() % 3 != 0) for (int i = keys.size() % 3; i < 3; i++) r.addView(new View(a), a.weight(-2, 1, 4));
    }

    // ===================================================================================
    // أسعار الصرف
    // ===================================================================================

    private void rates() {
        if (!a.can(Auth.Perm.VIEW)) return;
        Map<String, Db.Rate> rates = a.db.rates();
        Set<String> only = chosenCurrencies("hw_rates_curs");
        a.section("أسعار الصرف مقابل الدولار", "تعديل", v -> a.officesScreen.showRates(a::showHome));
        LinearLayout g = a.group();
        for (Map.Entry<String, Db.Rate> e : rates.entrySet()) {
            if ("USD".equals(e.getKey())) continue;
            if (!only.isEmpty() && !only.contains(e.getKey())) continue;
            Db.Rate r = e.getValue();
            a.groupRow(g, R.drawable.ic_rates, a.primaryInk, "1 USD = " + OfficesScreen.unitsPerUsd(r.usd) + " " + e.getKey(),
                    r.source + (r.at > 0 ? " • " + Model.date(r.at) : ""), null, null);
        }
        if (g.getChildCount() == 0) a.groupRow(g, R.drawable.ic_info, a.muted, "لا توجد أسعار بعد", "حدّثها من «أسعار الصرف»", null, v -> a.officesScreen.showRates(a::showHome));
    }

    // ===================================================================================
    // المستخدم الحالي
    // ===================================================================================

    private void userMenu(final Model.User u) {
        new AlertDialog.Builder(a).setTitle(u.name + " — " + Auth.shortLabel(u.role))
                .setItems(new String[]{"تغيير رمزي السري", "اسمي الظاهر للآخرين", "تسجيل الخروج"}, (d, w) -> {
                    if (w == 0) a.usersScreen.changeOwnPin();
                    else if (w == 1) a.usersScreen.changeOwnNick();
                    else {
                        a.usersScreen.logout();
                    }
                }).show();
    }

    // ===================================================================================
    // تخصيص الصفحة الرئيسية
    // ===================================================================================

    void editor() {
        // 10.12: الصفحة الرئيسية لكل مستخدم وحده، فيخصّصها كل مستخدم
        a.here(this::editor);
        a.tabbedScreen("تخصيص الصفحة الرئيسية", a::showHome, "home");
        a.hint("اختر ما يظهر في صفحتك الرئيسية، ورتّبه بسحب «≡» أو بالأسهم. التغييرات تُحفظ فورًا.");
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(box);
        final Runnable[] draw = new Runnable[1];
        final List<List<String[]>> cur = new ArrayList<>();
        // 10.13: الترتيب بالسحب «≡» إلى جانب الأسهم
        final Ui.DragSort ds = a.new DragSort(box, keys -> {
            List<String[]> l = cur.get(0), out = new ArrayList<>();
            for (Object k : keys) for (String[] it : l) if (it[0].equals(k)) out.add(it);
            for (String[] it : l) if (!out.contains(it)) out.add(it);
            saveLayout(a.db, out);
            draw[0].run();
        });
        draw[0] = () -> {
            box.removeAllViews();
            final List<String[]> l = layout(a.db);
            cur.clear();
            cur.add(l);
            for (int i = 0; i < l.size(); i++) {
                final int idx = i;
                final String[] item = l.get(i);
                final Widget w = widget(item[0]);
                boolean on = "1".equals(item[1]);
                LinearLayout r = a.row();
                r.setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
                r.setBackground(a.round(on ? a.surface : Ui.mix(a.surface, a.bg, 0.6f), a.line, 14));
                r.setGravity(Gravity.CENTER_VERTICAL);
                TextView hd = ds.handle();
                r.addView(hd, new LinearLayout.LayoutParams(a.dp(30), a.dp(44)));
                ds.bind(r, hd, item[0]);
                Switch sw = new Switch(a);
                sw.setChecked(on);
                int[][] st = {{android.R.attr.state_checked}, {}};
                sw.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{a.night ? a.green : a.primary, a.night ? Color.rgb(150, 160, 156) : Color.rgb(250, 250, 250)}));
                sw.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{Ui.mix(a.surface, a.primary, 0.5f), Ui.mix(a.surface, a.muted, 0.45f)}));
                sw.setOnCheckedChangeListener((b, c) -> {
                    item[1] = c ? "1" : "0";
                    saveLayout(a.db, l);
                    draw[0].run();
                });
                r.addView(sw, new LinearLayout.LayoutParams(-2, -2));
                LinearLayout col = new LinearLayout(a);
                col.setOrientation(LinearLayout.VERTICAL);
                col.addView(small(w.title, 15, on ? a.ink : a.muted, true));
                col.addView(small(w.desc, 12, a.muted, false));
                r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                if (w.hasOptions) r.addView(a.iconButton(R.drawable.ic_sliders, a.primaryInk, "خيارات", v -> options(w.key, draw[0])), new LinearLayout.LayoutParams(a.dp(40), a.dp(44)));
                TextView up = moveButton("▲", idx > 0, v -> {
                    Collections.swap(l, idx, idx - 1);
                    saveLayout(a.db, l);
                    draw[0].run();
                });
                TextView down = moveButton("▼", idx < l.size() - 1, v -> {
                    Collections.swap(l, idx, idx + 1);
                    saveLayout(a.db, l);
                    draw[0].run();
                });
                r.addView(up, new LinearLayout.LayoutParams(a.dp(36), a.dp(44)));
                r.addView(down, new LinearLayout.LayoutParams(a.dp(36), a.dp(44)));
                box.addView(r, a.lp(-1, -2, 3));
            }
        };
        draw[0].run();
        LinearLayout foot = a.row();
        foot.addView(a.makeButton("عرض الصفحة الرئيسية", a.primary, v -> a.showHome()), new LinearLayout.LayoutParams(0, a.dp(50), 1));
        android.widget.Button reset = a.makeButton("الافتراضي", Color.rgb(120, 120, 120), v -> a.confirm("الترتيب الافتراضي",
                "تعود عناصر الصفحة الرئيسية وترتيبها إلى الشكل الأصلي.", "استعادة", () -> {
                    a.db.deleteSetting("home_widgets");
                    editor();
                }));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, a.dp(50));
        rp.setMargins(a.dp(8), 0, 0, 0);
        foot.addView(reset, rp);
        a.footer(foot);
    }

    private TextView moveButton(String s, boolean enabled, View.OnClickListener l) {
        TextView t = a.text(s, 15, enabled ? a.primaryInk : Ui.mix(a.muted, a.surface, 0.6f), true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 0);
        if (enabled) {
            t.setBackground(a.ripple(null, 10));
            t.setOnClickListener(l);
        }
        return t;
    }

    /** خيارات العنصر: العملات المعروضة، طريقة اختيار المكاتب، العدد. */
    private void options(String key, final Runnable after) {
        switch (key) {
            case "net":
            case "totals":
            case "daily":
                currencyChooser("hw_net_curs", "العملات في الأرصدة وملخص اليوم", after);
                break;
            case "rates":
                currencyChooser("hw_rates_curs", "العملات في أسعار الصرف", after);
                break;
            case "offices": {
                final String[] modes = {"pinned", "top_us", "top_them", "all"};
                final String[] labels = {"المكاتب المثبّتة", "أكبر الأرصدة لنا (بالدولار التقريبي)", "أكبر الأرصدة علينا (بالدولار التقريبي)", "كل المكاتب التي لها رصيد"};
                int cur = java.util.Arrays.asList(modes).indexOf(a.db.setting("hw_offices_mode", "pinned"));
                new AlertDialog.Builder(a).setTitle("أي المكاتب تظهر؟").setSingleChoiceItems(labels, Math.max(0, cur), (d, w) -> {
                    a.db.setSettingQuiet("hw_offices_mode", modes[w]);
                    d.dismiss();
                    countChooser("hw_offices_n", "كم مكتبًا يظهر؟", 5, after);
                }).show();
                break;
            }
            case "recent_tx":
                countChooser("hw_tx_n", "كم حركة تظهر؟", 5, after);
                break;
            default:
                break;
        }
    }

    private void countChooser(final String key, String title, long def, final Runnable after) {
        final int[] counts = {3, 5, 10, 15, 20};
        String[] labels = new String[counts.length];
        long cur = a.db.settingLong(key, def);
        int sel = 1;
        for (int i = 0; i < counts.length; i++) {
            labels[i] = String.valueOf(counts[i]);
            if (counts[i] == cur) sel = i;
        }
        new AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(labels, sel, (d, w) -> {
            a.db.setSettingQuiet(key, String.valueOf(counts[w]));
            d.dismiss();
            if (after != null) after.run();
        }).show();
    }

    private void currencyChooser(final String key, String title, final Runnable after) {
        final List<Model.Currency> cs = a.db.currencies(true);
        final String[] labels = new String[cs.size()];
        final boolean[] checked = new boolean[cs.size()];
        Set<String> chosen = chosenCurrencies(key);
        for (int i = 0; i < cs.size(); i++) {
            labels[i] = cs.get(i).code + " — " + cs.get(i).name;
            checked[i] = chosen.isEmpty() || chosen.contains(cs.get(i).code);
        }
        new AlertDialog.Builder(a).setTitle(title).setMultiChoiceItems(labels, checked, (d, w, c) -> checked[w] = c)
                .setPositiveButton("حفظ", (d, w) -> {
                    List<String> out = new ArrayList<>();
                    boolean all = true;
                    for (int i = 0; i < cs.size(); i++) {
                        if (checked[i]) out.add(cs.get(i).code);
                        else all = false;
                    }
                    a.db.setSettingQuiet(key, all ? "" : BackupManager.join(out, ","));
                    if (after != null) after.run();
                })
                .setNeutralButton("الكل", (d, w) -> {
                    a.db.setSettingQuiet(key, "");
                    if (after != null) after.run();
                })
                .setNegativeButton("إلغاء", null).show();
    }
}
