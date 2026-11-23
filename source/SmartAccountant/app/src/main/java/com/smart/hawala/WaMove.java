package com.smart.hawala;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 12.1: نقل الرسائل من واتساب (أو أي برنامج) إلى التسجيل بأسهل طريقة على كل جهاز:
 * <ul>
 * <li>الحافظة بكل صيغها (رؤوس واتساب، فقاعات HTML، أسطر الوقت) — {@link WaClip}.</li>
 * <li>«قص هنا» يدوي حين يبقى النص ملزوقًا.</li>
 * <li>«وضع التجميع»: كل نسخ رسالة تصير بندًا مستقلًا، ثم «نقل N رسائل».</li>
 * <li>مراقبة الحافظة على الكمبيوتر: بطاقة «في الحافظة N رسائل من واتساب».</li>
 * <li>«إطار الالتقاط» على ويندوز وماك ({@link Capture}): يرقّم الفقاعات تحت الإطار، فتضغط الرقم فينتقل نصها.</li>
 * <li>«فحص الحافظة»: شكل ما نُسخ دون محتواه، للدعم.</li>
 * </ul>
 * لا يُسجَّل شيء تلقائيًا: كل رسالة تمر بنموذج التسجيل وتأكيد المحاسب، وصلاحية «التسجيل من واتساب» (TX_WA) شرط في كل طريق.
 */
final class WaMove {
    private final MainActivity a;

    WaMove(MainActivity a) { this.a = a; }

    /** على الكمبيوتر (طبقة التوافق تضع Build.MANUFACTURER = "Desktop"). */
    static boolean desktop() { return "Desktop".equals(Build.MANUFACTURER); }

    // ===================================================================================
    // جسر الكمبيوتر: إطار الالتقاط (تضعه نسخة الكمبيوتر عند التشغيل؛ على الهاتف يبقى null فلا يظهر الزر)
    // ===================================================================================

    public interface Capture {
        /** فارغ إن كان الإطار متاحًا، وإلا سبب عدم توفره. */
        String unavailable();

        /**
         * يفتح الإطار. whole = يلتقط نافذة واتساب كلها تلقائيًا بدل إطار يحرّكه المستخدم.
         * prefs: إعدادات هذا الجهاز (مكان الإطار cap_bounds، القراءة التلقائية cap_auto).
         */
        void open(boolean whole, Picked cb, Prefs prefs);
    }

    /** 12.2: إعدادات الجهاز المحلية (لا تُزامَن) لنوافذ الكمبيوتر. */
    public interface Prefs {
        String get(String k, String def);

        void put(String k, String v);
    }

    /** 12.2: نافذة التجميع الصغيرة على الكمبيوتر (فوق كل النوافذ): 1، 2، 3… مع جزء من كل رسالة وزر «نقل». */
    public interface CollectView {
        void show(List<WaText.Msg> collected, Runnable move, Runnable stop);

        void close();
    }

    static volatile CollectView collectView;

    Prefs prefs() {
        return new Prefs() {
            @Override public String get(String k, String def) { return a.db == null ? def : a.db.setting(k, def); }

            @Override public void put(String k, String v) { if (a.db != null) a.db.setSettingQuiet(k, v); }
        };
    }

    public interface Picked {
        /** فقاعة أو أكثر اختارها المستخدم بأرقامها، واسم المحادثة إن عُرف. */
        void picked(List<WaText.Msg> msgs, String chat);
    }

    static volatile Capture capture;

    boolean canCapture() { return capture != null && a.can(Auth.Perm.TX_WA); }

    /**
     * 12.6: في المجانية الالتقاط والتجميع والقص ضمن الرسائل اليومية المسموحة (20): بعدها لا تبدأ، ويبقى الإدخال اليدوي.
     * يعيد عدد المتبقي اليوم (Long.MAX_VALUE في الكاملة)، أو 0 مع رسالة.
     */
    long waRemaining(boolean tell) {
        long[] u = a.db.waUsage();
        if (u[1] == Entitlements.UNLIMITED) return Long.MAX_VALUE;
        long left = Math.max(0, u[1] - u[0]);
        if (left == 0 && tell) a.error(new Entitlements.LimitReached(Entitlements.waLimitMessage(u[1])));
        return left;
    }

    void openCapture(boolean whole) {
        if (!a.require(Auth.Perm.TX_WA)) return;
        if (waRemaining(true) == 0) return;
        Capture c = capture;
        if (c == null) {
            a.toast("إطار الالتقاط متاح على ويندوز وماك فقط. على الهاتف: حدّد الرسائل في واتساب ← مشاركة ← المحاسب الذكي.");
            return;
        }
        String why = c.unavailable();
        if (why != null && !why.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("إطار الالتقاط غير متاح").setMessage(why).setPositiveButton("حسنًا", null).show();
            return;
        }
        c.open(whole, this::fromCapture, prefs());
    }

    /** فقاعات من الإطار: إلى خانة النموذج المفتوح إن وُجد، وإلا إلى نموذج جديد أو دفعة. */
    void fromCapture(List<WaText.Msg> msgs, String chat) {
        if (msgs == null || msgs.isEmpty()) return;
        if (!a.require(Auth.Perm.TX_WA)) return;
        long left = waRemaining(true);   // 12.6: المجانية ضمن المتبقي اليوم
        if (left == 0) return;
        if (msgs.size() > left) {
            msgs = new ArrayList<>(msgs.subList(0, (int) left));
            a.toast("النسخة المجانية: بقي لك اليوم " + left + " رسالة للاستخراج، فنُقل هذا العدد فقط.");
        }
        Model.Office sug = officeFromChat(chat);
        if (msgs.size() == 1 && a.txScreen.formOpen()) {
            a.txScreen.captured(msgs.get(0), sug);
            return;
        }
        a.beforeScreenChange();
        if (msgs.size() == 1) a.txScreen.showCaptured(msgs.get(0), sug);
        else {
            a.waQueue.suggested = sug;
            a.waQueue.suggestedFrom = chat;
            a.waQueue.overview(msgs);
        }
    }

    Model.Office officeFromChat(String chat) {
        if (chat == null || chat.trim().isEmpty()) return null;
        List<Model.Office> offices = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Model.Office o : a.db.offices()) {
            if (!o.active) continue;
            offices.add(o);
            names.add(o.name);
        }
        int i = Bubbles.matchOffice(chat, names);
        return i < 0 ? null : offices.get(i);
    }

    // ===================================================================================
    // من الحافظة إلى التسجيل
    // ===================================================================================

    /** يقرأ الحافظة ويوجّه الرسائل. يعيد false إن كانت فارغة. */
    boolean pasteClipboard() {
        WaClip.Read r = WaClip.read(a);
        if (r.empty()) return false;
        route(WaClip.messages(r.text, r.html), r.text);
        return true;
    }

    /** رسالة واحدة ← نموذج (مع عرض «القص» إن بدا النص ملزوقًا)، عدة رسائل ← دفعة. */
    void route(List<WaText.Msg> msgs, String raw) {
        if (msgs.isEmpty()) {
            a.txScreen.showNew();
            return;
        }
        if (msgs.size() > 1) {
            if (!a.can(Auth.Perm.TX_WA)) {
                a.txScreen.showShared(new WaText.Msg("", "", raw.trim()));
                return;
            }
            a.waQueue.overview(msgs);
            return;
        }
        if (a.can(Auth.Perm.TX_WA) && WaClip.maybeGlued(msgs.get(0).body)) {
            final WaText.Msg one = msgs.get(0);
            new AlertDialog.Builder(a).setTitle("هل هذا النص أكثر من رسالة؟")
                    .setMessage("يبدو أن النص فيه أكثر من رسالة ملزوقة (يحصل عند النسخ من واتساب على ويندوز). اقطعه بنفسك عند حدود الرسائل، أو سجّله كما هو.")
                    .setPositiveButton("✂ قص الرسائل", (d, w) -> cutScreen(one.body))
                    .setNeutralButton("رسالة واحدة", (d, w) -> a.txScreen.showShared(one))
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        a.txScreen.showShared(msgs.get(0));
    }

    // ===================================================================================
    // «قص هنا»: المستخدم يحدد حدود الرسائل في نص ملزوق
    // ===================================================================================

    void cutScreen(final String text) {
        if (!a.require(Auth.Perm.TX_WA)) return;
        if (waRemaining(true) == 0) return;
        final List<String> lines = WaCuts.lines(text);
        final TreeSet<Integer> cuts = WaCuts.suggest(lines);
        a.here(() -> cutScreen(text));
        a.base("قص الرسائل", a::showHome);
        a.hint("النص أدناه فيه عدة رسائل ملزوقة. بين كل سطرين خط منقّط «✂»: اضغطه فيصير أحمر «رسالة جديدة» ويبدأ من هناك رسالة أخرى، واضغطه مرة أخرى لإلغائه. "
                + "ما اقترحه البرنامج مُعلَّم مسبقًا. ثم اضغط «متابعة» في الأسفل.");
        final Button go = a.makeButton("", a.primary, null);
        final Runnable count = () -> {
            int n = WaCuts.apply(lines, cuts).size();
            go.setText(n <= 1 ? "متابعة كرسالة واحدة" : "متابعة (" + n + " رسائل)");
        };
        LinearLayout box = a.card();
        box.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                final int at = i;
                final TextView sc = a.text("", 13, a.muted, true);
                sc.setGravity(Gravity.CENTER);
                sc.setPadding(0, a.dp(4), 0, a.dp(4));
                final Runnable paint = () -> {
                    boolean on = cuts.contains(at);
                    // 12.3: مكان القص ظاهر كزر صغير «✂ قص هنا»، والمقطوع أحمر «رسالة جديدة»
                    sc.setText(on ? "✂ ─────── رسالة جديدة ───────" : "✂  قص هنا");
                    sc.setTextColor(on ? a.red : a.primaryInk);
                    sc.setBackground(on ? a.round(a.debitBg, a.debitBg, 8) : a.round(a.primarySoft, Ui.mix(a.primarySoft, a.primaryInk, 0.2f), 8));
                };
                paint.run();
                sc.setContentDescription("قص هنا");
                sc.setOnClickListener(v -> {
                    if (!cuts.remove(at)) cuts.add(at);
                    paint.run();
                    count.run();
                });
                box.addView(sc, new LinearLayout.LayoutParams(-1, -2));
            }
            String ln = lines.get(i);
            TextView t = a.text(ln.trim().isEmpty() ? " " : ln, 14, WaCuts.timeLine(ln) ? a.muted : a.dark, false);
            t.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
            box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout foot = a.row();
        go.setOnClickListener(v -> {
            List<WaText.Msg> parts = WaCuts.apply(lines, cuts);
            if (parts.size() <= 1) a.txScreen.showShared(new WaText.Msg("", "", text.trim()));
            else a.waQueue.overview(parts);
        });
        foot.addView(go, new LinearLayout.LayoutParams(0, a.dp(52), 1));
        Button cancel = a.makeButton("إلغاء", Color.rgb(130, 130, 130), v -> a.showHome());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, a.dp(52));
        cp.setMargins(a.dp(8), 0, 0, 0);
        foot.addView(cancel, cp);
        a.footer(foot);
        count.run();
    }

    // ===================================================================================
    // وضع التجميع: كل نسخة = رسالة
    // ===================================================================================

    final List<WaText.Msg> collected = new ArrayList<>();
    boolean collecting;
    private String lastCollected;

    void startCollect() {
        if (!a.require(Auth.Perm.TX_WA)) return;
        if (waRemaining(true) == 0) return;
        collecting = true;
        collected.clear();
        lastCollected = WaClip.read(a).text;
        // 12.2: على الكمبيوتر تُراقب الحافظة أثناء التجميع حتى لو كانت «مراقبة الحافظة» مطفأة، وتظهر نافذة التجميع الصغيرة
        startWatch(true);
        CollectView cv = collectView;
        if (cv != null) {
            cv.show(new ArrayList<>(collected), this::moveCollected, this::stopCollect);
            if (a.onHome) a.showHome();
            return;
        }
        a.toast(desktop()
                ? "وضع التجميع: انسخ الرسائل من واتساب واحدة تلو الأخرى (Ctrl+C أو ⌘C)، ثم ارجع واضغط «نقل»."
                : "وضع التجميع: انسخ رسالة في واتساب ثم ارجع إلى التطبيق، وكرّر لكل رسالة، ثم اضغط «نقل».");
        if (a.onHome) a.showHome();
    }

    /** 12.4: على أندرويد زرا «وضع التجميع» و«قص الرسائل» اختياريان من الإعدادات (الافتراضي مخفيان)؛ على الكمبيوتر ظاهران دائمًا. */
    boolean showCollect() { return desktop() || (a.db != null && "1".equals(a.db.setting("wa_btn_collect", "0"))); }

    boolean showCut() { return desktop() || (a.db != null && "1".equals(a.db.setting("wa_btn_cut", "0"))); }

    /** 12.2: زر «تجميع» في نموذج التسجيل: يبدأ التجميع، أو يعيد إظهار نافذته إن كان جاريًا. */
    void startOrShowCollect() {
        CollectView cv = collectView;
        if (collecting && cv != null) cv.show(new ArrayList<>(collected), this::moveCollected, this::stopCollect);
        else if (!collecting) startCollect();
    }

    void stopCollect() {
        collecting = false;
        collected.clear();
        lastCollected = null;
        closeCollectView();
        if (a.onHome) a.showHome();
    }

    void moveCollected() {
        List<WaText.Msg> l = new ArrayList<>(collected);
        collecting = false;
        collected.clear();
        lastCollected = null;
        closeCollectView();
        if (l.isEmpty()) {
            a.toast("لم تُجمع أي رسالة بعد.");
            if (a.onHome) a.showHome();
            return;
        }
        // 12.2: رسالة واحدة ونموذج التسجيل مفتوح ← إلى خانته مباشرة (كالالتقاط)
        if (l.size() == 1 && a.txScreen.formOpen()) {
            a.txScreen.captured(l.get(0), null);
            return;
        }
        a.beforeScreenChange();
        if (l.size() == 1) a.txScreen.showShared(l.get(0));
        else a.waQueue.overview(l);
    }

    /** نسخة جديدة في الحافظة أثناء التجميع. يعيد true إن أُضيف شيء. */
    boolean collectNow() {
        if (!collecting) return false;
        WaClip.Read r = WaClip.read(a);
        if (r.text.trim().isEmpty() || r.text.equals(lastCollected)) return false;
        lastCollected = r.text;
        List<WaText.Msg> msgs = WaClip.messages(r.text, r.html);
        int added = 0;
        long left = waRemaining(false);   // 12.6: المجانية لا تجمع أكثر من المتبقي اليوم
        boolean capped = false;
        for (WaText.Msg m : msgs) {
            if (collected.size() >= left) {
                capped = true;
                break;
            }
            String fp = WaText.fingerprint(m.body);
            boolean dup = false;
            for (WaText.Msg c : collected) if (WaText.fingerprint(c.body).equals(fp)) { dup = true; break; }
            if (dup) continue;
            collected.add(m);
            added++;
        }
        if (capped) a.toast("النسخة المجانية: بقي لك اليوم " + left + " رسالة للاستخراج، وجُمع العدد كله. انقل ما جُمع، والباقي بالإدخال اليدوي.");
        if (added > 0) {
            CollectView cv = collectView;
            if (cv != null) cv.show(new ArrayList<>(collected), this::moveCollected, this::stopCollect);
            else a.toast("جُمعت " + collected.size() + (collected.size() == 1 ? " رسالة" : " رسائل"));
            if (a.onHome) a.homeScreen.refreshClipCard();
        }
        return added > 0;
    }

    // ===================================================================================
    // مراقبة الحافظة (الكمبيوتر؛ على الهاتف يُقرأ عند العودة للتطبيق في وضع التجميع فقط)
    // ===================================================================================

    private ClipboardManager.OnPrimaryClipChangedListener watcher;

    void startWatch() { startWatch(false); }

    /** force: أثناء التجميع تُراقب الحافظة مهما كان الإعداد. */
    void startWatch(boolean force) {
        if (!desktop() || watcher != null || a.db == null) return;
        if (!force && "0".equals(a.db.setting("clip_watch", "1"))) return;
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            watcher = this::clipChanged;
            cm.addPrimaryClipChangedListener(watcher);
        } catch (Exception ignored) {
            watcher = null;
        }
    }

    private void closeCollectView() {
        CollectView cv = collectView;
        if (cv != null) cv.close();
        // مراقبة فُتحت للتجميع فقط تُغلق معه
        if (a.db != null && "0".equals(a.db.setting("clip_watch", "1"))) stopWatch();
    }

    void stopWatch() {
        if (watcher == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.removePrimaryClipChangedListener(watcher);
        } catch (Exception ignored) {
            // لا شيء
        }
        watcher = null;
    }

    private void clipChanged() {
        if (a.db == null || !a.unlocked || !a.can(Auth.Perm.TX_WA)) return;
        if (collectNow()) return;
        if (a.onHome) a.homeScreen.refreshClipCard();
    }

    /** على الكمبيوتر: عدد رسائل واتساب في الحافظة الآن (0 إن لم تكن رسائل). */
    int clipCount() {
        WaClip.Read r = WaClip.read(a);
        if (r.text.trim().isEmpty()) return 0;
        List<WaText.Msg> l = WaClip.messages(r.text, r.html);
        if (l.size() > 1) return l.size();
        if (l.size() == 1) {
            List<MessageParser.Cur> curs = new ArrayList<>();
            for (Model.Currency c : a.db.currencies(false)) curs.add(new MessageParser.Cur(c.code, c.name, c.decimals));
            if (!MessageParser.parse(l.get(0).body, curs, CurrencyWords.load(a.db)).amount.isEmpty()) return 1;
        }
        return 0;
    }

    // ===================================================================================
    // فحص الحافظة
    // ===================================================================================

    void inspect() {
        WaClip.Read r = WaClip.read(a);
        StringBuilder rep = new StringBuilder();
        rep.append("فحص الحافظة — المحاسب الذكي ").append(BuildInfo.VERSION).append('\n');
        rep.append("الجهاز: ").append(Build.MODEL).append('\n');
        rep.append("الصيغ: ").append(r.mimes.isEmpty() ? "—" : String.join("، ", r.mimes)).append('\n');
        List<WaText.Msg> plain = WaText.split(r.text);
        List<WaText.Msg> html = WaHtml.messages(r.html);
        List<WaText.Msg> best = WaClip.messages(r.text, r.html);
        rep.append("رسائل بالرؤوس: ").append(plain.size()).append(" • من HTML: ").append(html == null ? "لا يوجد" : String.valueOf(html.size()))
                .append(" • أسطر الوقت: ").append(WaCuts.auto(r.text).size()).append(" • النتيجة: ").append(best.size()).append('\n');
        rep.append("\n— النص العادي (").append(r.text.length()).append(" حرفًا، المحتوى مخفي) —\n").append(ClipMask.text(r.text)).append('\n');
        rep.append("\n— HTML (").append(r.html == null ? "غير موجود" : r.html.length() + " حرفًا، المحتوى مخفي").append(") —\n");
        if (r.html != null) rep.append(ClipMask.html(r.html)).append('\n');
        final String report = rep.toString();
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));
        TextView top = a.text("الحروف العربية صارت «ع»، واللاتينية «x»، والأرقام «#». يبقى شكل النسخ فقط، فيمكن إرساله للدعم بأمان.", 12, a.muted, false);
        l.addView(top);
        TextView body = a.text(report, 12, a.dark, false);
        body.setTextIsSelectable(true);
        body.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        l.addView(sv, new LinearLayout.LayoutParams(-1, a.dp(360)));
        new AlertDialog.Builder(a).setTitle("فحص الحافظة").setView(l)
                .setPositiveButton("نسخ التقرير", (d, w) -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("فحص الحافظة", report));
                        a.toast("نُسخ التقرير (بلا محتوى الرسائل). الصقه في رسالة للدعم.");
                    } catch (Exception e) {
                        a.toast("تعذّر النسخ");
                    }
                })
                .setNegativeButton("إغلاق", null).show();
    }
}
