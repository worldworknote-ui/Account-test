package com.smart.hawala;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * رسائل واتساب المشارَكة إلى التطبيق: رسالة واحدة تفتح «تسجيل حركة» مباشرة،
 * وعدة رسائل تصبح «دفعة» تُراجَع وتُسجَّل واحدة تلو الأخرى.
 * لا يُسجَّل شيء تلقائيًا: كل حركة تمر بنموذج التسجيل نفسه وتأكيد المحاسب، فيبقى القيد المزدوج كما هو.
 * الدفعة محفوظة في إعداد خاص بالهاتف (wa_queue) فلا تضيع إن أُغلق التطبيق.
 */
final class WaQueue {
    private final MainActivity a;

    WaQueue(MainActivity a) { this.a = a; }

    static final String KEY = "wa_queue";
    static final String SENDERS = "wa_sender_office";

    /** رسائل وصلت قبل فتح القفل أو الدخول، تُعرض بعده. */
    List<WaText.Msg> pending;

    static final class State {
        final List<WaText.Msg> items = new ArrayList<>();
        int index, saved, skipped;
        long office;

        boolean done() { return index >= items.size(); }

        WaText.Msg current() { return done() ? null : items.get(index); }

        /** رسائل من أكثر من مرسل في واتساب (مكاتب مختلفة غالبًا). */
        boolean mixedSenders() {
            Set<String> k = new HashSet<>();
            for (WaText.Msg m : items) if (!m.sender.isEmpty()) k.add(senderKey(m.sender));
            return k.size() > 1;
        }
    }

    // ===================================================================================
    // استقبال المشاركة
    // ===================================================================================

    /** يقرأ النص من نية المشاركة (رسالة أو عدة رسائل). يعيد null إن لم تكن مشاركة نصية. */
    List<WaText.Msg> fromIntent(Intent i) {
        if (i == null) return null;
        String act = i.getAction();
        if (!Intent.ACTION_SEND.equals(act) && !Intent.ACTION_SEND_MULTIPLE.equals(act)) return null;
        List<String> texts = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(act)) {
            CharSequence cs = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (cs != null) texts.add(cs.toString());
            else {
                Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
                String s = readText(u);
                if (s != null) texts.add(s);
            }
        } else {
            ArrayList<CharSequence> l = i.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT);
            if (l != null) for (CharSequence cs : l) if (cs != null) texts.add(cs.toString());
            if (texts.isEmpty()) {
                ArrayList<Uri> us = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (us != null) for (Uri u : us) {
                    String s = readText(u);
                    if (s != null) texts.add(s);
                }
            }
        }
        List<WaText.Msg> out = new ArrayList<>();
        for (String t : texts) out.addAll(WaText.split(t));
        return out;
    }

    private String readText(Uri u) {
        if (u == null) return null;
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            byte[] raw = Db.readAll(in);
            if (raw.length > 2_000_000) return null;
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** يُستدعى بعد فتح القفل والدخول: يعرض ما وصل من مشاركة. */
    boolean openPending() {
        if (pending == null) return false;
        List<WaText.Msg> l = pending;
        pending = null;
        if (l.isEmpty()) {
            a.toast("لم أجد نصًا في المشاركة.");
            return false;
        }
        if (!a.can(Auth.Perm.TX_ADD)) {
            a.toast("ليست لديك صلاحية تسجيل الحركات.");
            return false;
        }
        if (l.size() == 1) {
            a.txScreen.showShared(l.get(0));
            return true;
        }
        overview(l);
        return true;
    }

    // ===================================================================================
    // حفظ الدفعة
    // ===================================================================================

    State load() {
        String raw = a.db.secretSetting(KEY);
        if (raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            State s = new State();
            s.index = o.optInt("i");
            s.saved = o.optInt("saved");
            s.skipped = o.optInt("skipped");
            s.office = o.optLong("office", -1);
            JSONArray arr = o.getJSONArray("items");
            for (int k = 0; k < arr.length(); k++) {
                JSONObject m = arr.getJSONObject(k);
                s.items.add(new WaText.Msg(m.optString("s"), m.optString("t"), m.optString("b")));
            }
            return s.items.isEmpty() ? null : s;
        } catch (Exception e) {
            a.db.deleteSetting(KEY);
            return null;
        }
    }

    void store(State s) {
        try {
            if (s == null || s.done()) {
                a.db.deleteSetting(KEY);
                return;
            }
            JSONObject o = new JSONObject();
            o.put("i", s.index);
            o.put("saved", s.saved);
            o.put("skipped", s.skipped);
            o.put("office", s.office);
            JSONArray arr = new JSONArray();
            for (WaText.Msg m : s.items) arr.put(new JSONObject().put("s", m.sender).put("t", m.time).put("b", m.body));
            o.put("items", arr);
            a.db.setSecretSetting(KEY, o.toString());
        } catch (Exception ignored) {
            // الدفعة ليست حرجة: الرسائل باقية في واتساب
        }
    }

    boolean active() { return load() != null; }

    /** عدد الرسائل الباقية في دفعة لم تكتمل (للتنبيه في الرئيسية). */
    int remaining() {
        State s = load();
        return s == null ? 0 : s.items.size() - s.index;
    }

    // ===================================================================================
    // المكتب المقترح لكل مرسل
    // ===================================================================================

    private Map<String, Long> senders() {
        Map<String, Long> m = new HashMap<>();
        for (String line : a.db.setting(SENDERS, "").split("\n")) {
            int t = line.lastIndexOf('\t');
            if (t <= 0) continue;
            try {
                m.put(line.substring(0, t), Long.parseLong(line.substring(t + 1)));
            } catch (NumberFormatException ignored) {
                // سطر تالف
            }
        }
        return m;
    }

    static String senderKey(String sender) { return MessageParser.norm(WaText.stripMarks(sender)).trim(); }

    void rememberSender(String sender, long office) {
        String k = senderKey(sender);
        if (k.isEmpty() || k.contains("\n") || k.contains("\t")) return;
        Map<String, Long> m = senders();
        Long old = m.get(k);
        if (old != null && old == office) return;
        m.put(k, office);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (n++ > 300) break;
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        a.db.setSettingQuiet(SENDERS, sb.toString());
    }

    /** المكتب الذي سُجّلت له آخر رسالة من هذا المرسل، إن كان نشطًا. */
    Model.Office officeFor(String sender) {
        Long id = senders().get(senderKey(sender));
        if (id == null) return null;
        Model.Office o = a.db.office(id);
        return o != null && o.active ? o : null;
    }

    // ===================================================================================
    // شاشة الدفعة
    // ===================================================================================

    private Model.Office batchOffice;
    /** 12.1: مكتب مقترح من اسم المحادثة في «إطار الالتقاط» (لا يتجاوز المكتب المثبّت، ويُؤكَّد عند البدء كغيره). */
    Model.Office suggested;
    String suggestedFrom;

    void overview(final List<WaText.Msg> msgs) { overview(msgs, null); }

    /** 12.7: from = مكتب النموذج الذي لُصقت فيه الرسائل (دخل منه المحاسب): يُثبَّت لهذه الدفعة بدل آخر مكتب. */
    void overview(final List<WaText.Msg> msgs, final Model.Office from) {
        if (!a.require(Auth.Perm.TX_WA)) return;   // 12.0
        a.here(() -> overview(msgs, from));
        a.base("رسائل من واتساب", a::showHome);
        // 10.13: سطر واحد بدل بطاقة الشرح
        a.hint(msgs.size() + " رسائل: حدّد ما فيه حوالات، واختر المكتب، ثم «ابدأ».");
        // 10.5 (§5): المكتب يثبّته المحاسب صراحة لهذه الدفعة؛ المقترح (المثبّت في الجلسة، أو مكتب المرسل) يحتاج تأكيده عند البدء
        Set<String> senderSet = new HashSet<>();
        for (WaText.Msg m : msgs) senderSet.add(senderKey(m.sender));
        batchOffice = from != null && from.active ? from : WaPin.current(a.db);
        Model.Office sug = suggested;
        String sugFrom = suggestedFrom;
        suggested = null;
        suggestedFrom = null;
        if (batchOffice == null && sug != null && sug.active) {
            batchOffice = sug;
            a.hint("المكتب مقترح من اسم المحادثة «" + sugFrom + "»: تأكّد منه قبل البدء.");
        }
        if (batchOffice == null && senderSet.size() == 1) batchOffice = officeFor(msgs.get(0).sender);
        if (batchOffice == null) {
            Model.Office last = a.db.office(a.db.settingLong("last_office", -1));
            if (last != null && last.active) batchOffice = last;
        }
        if (senderSet.size() > 1) a.hint("من أكثر من مرسل: كلها للمكتب المثبّت، وغيّره في نموذج الرسالة إن لزم.");
        final Button officeBtn = a.button(batchOffice == null ? "📌 اختر المكتب" : "📌 المكتب: " + batchOffice.name, a.red, null);
        officeBtn.setContentDescription("المكتب المثبّت لهذه الدفعة (لا يتغير تلقائيًا)");
        officeBtn.setOnClickListener(v -> a.officePicker(o -> {
            batchOffice = o;
            officeBtn.setText("المكتب: " + o.name);
        }, true));

        List<MessageParser.Cur> curs = new ArrayList<>();
        for (Model.Currency c : a.db.currencies(false)) curs.add(new MessageParser.Cur(c.code, c.name, c.decimals));
        Map<String, List<String>> aliases = CurrencyWords.load(a.db);
        Map<String, Model.Tx> recent = a.txScreen.recentPrints();
        Map<Long, String> officeNames = new HashMap<>();
        for (Model.Office o : a.db.offices()) officeNames.put(o.id, o.name);
        final List<CheckBox> boxes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        final Button start = a.makeButton("", a.primary, null);
        final Runnable count = () -> {
            int n = 0;
            for (CheckBox b : boxes) if (b.isChecked()) n++;
            start.setText(n == 0 ? "حدّد رسالة واحدة على الأقل" : "ابدأ تسجيل المحدد (" + n + ")");
            start.setEnabled(n > 0);
        };
        for (int k = 0; k < msgs.size(); k++) {
            WaText.Msg m = msgs.get(k);
            LinearLayout c = a.card();
            c.setPadding(a.dp(10), a.dp(4), a.dp(12), a.dp(6));
            CheckBox cb = new CheckBox(a);
            String head = (k + 1) + ". " + (m.sender.isEmpty() ? "رسالة" : m.sender) + (m.time.isEmpty() ? "" : "  •  " + m.time);
            cb.setText(head);
            cb.setTextSize(13);
            cb.setTextColor(a.muted);
            c.addView(cb);
            String preview = MessageParser.compact(m.body, null);
            TextView pv = a.text(preview.isEmpty() ? m.body : preview, 13, a.dark, false);
            pv.setMaxLines(2);
            pv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            c.addView(pv);
            MessageParser.Result r = MessageParser.parse(m.body, curs, aliases);
            String fp = WaText.fingerprint(m.body);
            Model.Tx dup = fp.length() >= 12 ? recent.get(fp) : null;
            boolean dupInBatch = fp.length() >= 12 && !seen.add(fp);
            boolean ok = !r.amount.isEmpty();
            if (ok) {
                String cur = r.currency != null ? r.currency : r.currencyWord;
                c.addView(a.text("✓ " + r.amount + (cur.isEmpty() ? "" : " " + cur) + (r.person.isEmpty() ? "" : " — " + r.person), 13, a.green, true));
            } else {
                c.addView(a.text("✗ لا يوجد مبلغ واضح؛ قد لا تكون حوالة", 13, a.red, true));
            }
            if (dup != null) {
                String on = officeNames.get(dup.officeId);
                c.addView(a.text("⚠ مسجّلة سابقًا: " + Model.day(dup.createdAt) + (on == null ? "" : " — " + on) + " — "
                        + Model.fmt(dup.amount, a.db.decimals(dup.currency)) + " " + dup.currency, 13, a.red, true));
            }
            if (dupInBatch) c.addView(a.text("⚠ مكررة في هذه الدفعة", 13, a.red, true));
            cb.setChecked(ok && dup == null && !dupInBatch);
            cb.setOnCheckedChangeListener((b, x) -> count.run());
            boxes.add(cb);
        }
        LinearLayout foot = a.row();
        foot.addView(start, new LinearLayout.LayoutParams(0, a.dp(52), 1));
        android.widget.Button cancel = a.makeButton("إلغاء", Color.rgb(130, 130, 130), v -> a.showHome());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, a.dp(52));
        cp.setMargins(a.dp(8), 0, 0, 0);
        foot.addView(cancel, cp);
        a.footer(foot);
        start.setOnClickListener(v -> {
            final State s = new State();
            for (int k = 0; k < msgs.size(); k++) if (boxes.get(k).isChecked()) s.items.add(msgs.get(k));
            if (s.items.isEmpty()) return;
            if (batchOffice == null) {
                a.toast("اختر المكتب لهذه الدفعة أولًا");
                return;
            }
            final Model.Office chosen = batchOffice;
            Model.Office was = WaPin.current(a.db);
            String msg = was != null && was.id != chosen.id ? WaPin.changeWarning(was.name, chosen.name)
                    : was != null ? "ستُسجَّل الرسائل المحددة (" + s.items.size() + ") للمكتب المثبّت «" + chosen.name + "»."
                    : WaPin.sessionQuestion(chosen.name);
            new AlertDialog.Builder(a).setTitle("المكتب: «" + chosen.name + "»").setMessage(msg)
                    .setPositiveButton("نعم، ابدأ", (d, w) -> {
                        WaPin.pin(a.db, chosen.id);
                        s.office = chosen.id;
                        store(s);
                        showCurrent();
                    }).setNegativeButton("رجوع", null).show();
        });
        count.run();
    }

    /** يفتح نموذج التسجيل للرسالة الحالية في الدفعة. */
    void showCurrent() {
        State s = load();
        if (s == null || s.done()) {
            finish(s);
            return;
        }
        WaText.Msg m = s.current();
        // 10.5 (§5): المكتب المثبّت للدفعة وحده — لا يتغير تلقائيًا بالمرسل. تغييره من النموذج يمر بتحذير صريح.
        Model.Office o = null;
        if (s.office > 0) {
            o = a.db.office(s.office);
            if (o != null && !o.active) o = null;
        }
        a.txScreen.showQueued(m, s.index, s.items.size(), o);
    }

    /** 10.5 (§5): غيّر المحاسب المكتب (بعد التحذير): الرسائل التالية في الدفعة لهذا المكتب. */
    void repin(long office) {
        State s = load();
        if (s == null) return;
        s.office = office;
        store(s);
    }

    /** بعد تسجيل الرسالة الحالية: يتذكر المكتب ويتابع للتالية. */
    void savedCurrent(long officeId) {
        State s = load();
        if (s == null) return;
        WaText.Msg m = s.current();
        if (m != null && !m.sender.isEmpty()) rememberSender(m.sender, officeId);
        s.saved++;
        s.office = officeId;
        s.index++;
        store(s);
        if (s.done()) finish(s);
        else showCurrent();
    }

    /** تراجع عن حركة سُجّلت من الدفعة: تُعاد رسالتها إلى آخر الدفعة لتسجيلها من جديد. */
    void undone() {
        State s = load();
        if (s == null || s.index <= 0) return;
        if (s.saved > 0) s.saved--;
        s.items.add(s.items.get(s.index - 1));
        store(s);
        a.toast("أُعيدت الرسالة إلى آخر الدفعة لتسجيلها من جديد.");
    }

    void skipCurrent() {
        State s = load();
        if (s == null) return;
        s.skipped++;
        s.index++;
        store(s);
        if (s.done()) finish(s);
        else showCurrent();
    }

    void stop() {
        final State s = load();
        if (s == null) {
            a.showHome();
            return;
        }
        int left = s.items.size() - s.index;
        new AlertDialog.Builder(a).setTitle("إنهاء الدفعة")
                .setMessage("بقيت " + left + " رسالة لم تُسجَّل. ستبقى في واتساب ويمكنك نسخها ولصقها لاحقًا.")
                .setPositiveButton("إنهاء", (d, w) -> {
                    a.db.deleteSetting(KEY);
                    a.toast("انتهت الدفعة: سُجّلت " + s.saved + "، وتُخطّيت " + (s.skipped + left) + ".");
                    a.showHome();
                })
                .setNegativeButton("متابعة الدفعة", null).show();
    }

    private void finish(State s) {
        a.db.deleteSetting(KEY);
        a.showHome();
        if (s != null) {
            new AlertDialog.Builder(a).setTitle("انتهت الدفعة")
                    .setMessage("سُجّلت " + s.saved + " حركة" + (s.skipped > 0 ? "، وتُخطّيت " + s.skipped + " رسالة." : "."))
                    .setPositiveButton("حسنًا", null).show();
        }
    }

    /** شريط أعلى نموذج التسجيل أثناء الدفعة: الرسالة n من N مع شريط تقدّم. */
    LinearLayout strip(WaText.Msg m, int index, int total) {
        // 10.13: شريط مضغوط: «الرسالة 2 من 5» وزرّا تخطٍّ وإنهاء صغيران في السطر نفسه، وتحتها خط التقدم
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(12), a.dp(6), a.dp(8), a.dp(6));
        c.setBackground(a.round(a.primarySoft, a.primarySoft, 14));
        c.setLayoutParams(a.lp(-1, -2, 3));
        LinearLayout h = a.row();
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(a.text("دفعة واتساب: " + (index + 1) + " من " + total, 14, a.primaryInk, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button skip = a.smallButton("تخطٍّ", Color.rgb(120, 120, 120), v -> skipCurrent());
        skip.setContentDescription("تخطّي هذه الرسالة");
        h.addView(skip, new LinearLayout.LayoutParams(-2, a.dp(34)));
        Button end = a.smallButton("إنهاء", a.red, v -> stop());
        end.setContentDescription("إنهاء الدفعة");
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, a.dp(34));
        ep.setMargins(a.dp(6), 0, 0, 0);
        h.addView(end, ep);
        c.addView(h);
        android.widget.FrameLayout track = new android.widget.FrameLayout(a);
        track.setBackground(a.round(Ui.mix(a.primarySoft, a.primary, 0.18f), Ui.mix(a.primarySoft, a.primary, 0.18f), 3));
        android.view.View fill = new android.view.View(a);
        fill.setBackground(a.round(a.primary, a.primary, 3));
        track.addView(fill, new android.widget.FrameLayout.LayoutParams(0, -1));
        track.post(() -> {
            android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = (int) (track.getWidth() * (index + 1) / (float) Math.max(1, total));
            fill.setLayoutParams(lp);
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, a.dp(6));
        tp.setMargins(0, a.dp(5), a.dp(4), 0);
        c.addView(track, tp);
        // 10.5 (§5): المكتب المثبّت ظاهر طوال الدفعة، ومكتب المرسل المعروف تنبيه فقط إن اختلف (لا يُغيَّر تلقائيًا)
        Model.Office pin = WaPin.current(a.db);
        if (pin != null) {
            Model.Office known = officeFor(m.sender);
            if (known != null && known.id != pin.id)
                c.addView(a.text("⚠ رسائله السابقة لـ«" + known.name + "» والمثبّت «" + pin.name + "»", 12, a.red, true));
        }
        return c;
    }
}
