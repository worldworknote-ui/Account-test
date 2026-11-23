package com.smart.hawala;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.text.InputType;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** شاشة تسجيل حركة وتعديلها: اختيارات بضغطة واحدة، والتفاصيل تظهر فقط عند الحاجة. */
final class TxScreen {
    private final MainActivity a;
    private Form f;
    private Model.Tx editing;
    /** 10.2: تصحيح حركة معلّقة ثم اعتمادها (بدل رفضها). */
    private boolean approving;
    private Runnable afterEdit;
    private final List<Long> existingAtt = new ArrayList<>();
    private final Set<Long> removedAtt = new HashSet<>();

    TxScreen(MainActivity a) { this.a = a; }

    /** 12.4: أمانة أو مجمّد يُحوَّل إلى الحركة التي في النموذج (تُفكّ مربوطةً بها عند الحفظ). */
    private String fromHold;
    /** 12.4: النموذج لتحويل حركة خاصة إلى عادية. */
    private boolean converting;

    private final class Form {
        /** 12.4: الحركة الخاصة: مفعّلة، ونوعها، وهل تُحسب في الرصيد. */
        boolean specialOn;
        String specialType = "";
        Ui.Seg specialBal;
        LinearLayout specialBody;
        android.widget.Switch specialSw;
        Ui.Flow specialTypes;
        /** 12.0: بطاقة الحركة (النوع والمبلغ والعملة) بلون نوعها، كبطاقتي قص العملات. */
        LinearLayout txCard;
        Model.Office office;
        Button officeBtn;
        TextView balance;
        Ui.Seg type;
        EditText amount;
        LinearLayout curHolder;
        /** 10.9: «تفاصيل إضافية» (ملاحظات، الخدمة، الوجهة، التاريخ، الصور) مطوية حتى تُطلب أو تُملأ. */
        LinearLayout more;
        TextView moreToggle;
        Ui.Seg cur;
        List<String> curs = new ArrayList<>();
        EditText person, desc, notes, service, dest;
        Button dateBtn;
        long when; // 0 = الآن
        Ui.Seg feeMode;
        LinearLayout feeBox;
        Ui.Seg feeSide;
        EditText fee;
        LinearLayout feeCurHolder;
        Ui.Seg feeCur;
        boolean feeCurTouched;
        /** طريقة الأجرة: 0 مبلغ محدد، 1 نسبة من المبلغ الأصلي (بالمئة أو بالألف) تُحسب تلقائيًا. */
        Ui.Seg feeKind;
        LinearLayout rateBox;
        Ui.Flow rateChips;
        EditText rate;
        Ui.Seg ratePer;
        TextView rateResult, feeCurLabel;
        /** 12.6: «المبلغ» أو «الوزن (غرام)» حسب العملة المختارة. */
        TextView amountLabel;
        /** مرسل رسالة واتساب ووقتها: للعرض فقط حتى يعرف المحاسب مصدر الحوالة. */
        String waSender = "", waTime = "";
        Ui.Seg imgMode;
        LinearLayout imgBox;
        LinearLayout thumbs;
        /** بطاقة «راجع قبل التسجيل» داخل النموذج: سطر لكل معلومة، والناقص بالأحمر. */
        LinearLayout summary;
        /** سطر الملخص المختصر في الشريط الثابت أسفل الشاشة، وزر التسجيل. */
        TextView footerLine;
        Button saveBtn;
        Map<String, String> names;
        /** ميزة استخراج المعلومات من رسالة واتساب مفعّلة (الإعدادات ← wa_parse). */
        boolean wa;
        /** بطاقة المعلومات المستخرجة (أخضر = استُخرج، أحمر = لم يُستخرج) مع زر «إضافة المعلومات للحقول». */
        LinearLayout parseBox;
        MessageParser.Result parsed;
        String parsedText = "";
        /** ضغط المستخدم «إضافة المعلومات للحقول» لهذا النص. */
        boolean filled;
        /** مصدر النتيجة المعروضة: المحرك المدمج أو الذكاء الاصطناعي. */
        boolean byAi;
        /** نتيجة الذكاء الاصطناعي الخام (لكل العملات) ونصها، حتى لا تضيع عند تغيير المكتب. */
        MessageParser.Result aiRaw;
        String aiText = "", aiAsked = "";
        /** لُصق نص كبير للتو (زر اللصق أو لوحة المفاتيح): للتشغيل التلقائي للذكاء الاصطناعي. */
        boolean pasted;
        /** نموذج فُتح من مشاركة واتساب: لا يقرأ المسودة ولا يكتبها ولا يحذفها. */
        boolean external;
        /** جزء من دفعة رسائل واتساب: بعد التسجيل تُفتح الرسالة التالية. */
        boolean queued;
        /** حركة سابقة نصها مطابق لهذا البيان (احتمال تسجيل الرسالة مرتين). */
        Model.Tx duplicate;
        final Runnable parseLater = () -> previewParse();
    }

    void detach() { f = null; }

    // ===================================================================================
    // الفتح
    // ===================================================================================

    /**
     * 12.7: مكتب اختاره المحاسب صراحة لهذا النموذج (دخل منه «حركة جديدة» أو اختاره بيده): لصق عدة رسائل يذهب إليه
     * لا إلى آخر مكتب مثبّت لرسائل واتساب.
     */
    private Model.Office chosenOffice;

    /** المكتب الذي تُثبَّت له دفعة رسائل لُصقت في هذا النموذج. */
    private Model.Office pasteOffice() {
        return chosenOffice != null && f != null && f.office != null && f.office.id == chosenOffice.id ? f.office : null;
    }

    void showNew() {
        if (!a.require(Auth.Perm.TX_ADD)) return;
        chosenOffice = null;
        fromHold = null;
        converting = false;
        approving = false;
        editing = null;
        afterEdit = null;
        existingAtt.clear();
        removedAtt.clear();
        a.pendingImages.clear();
        build("تسجيل حركة");
        // 10.7: نوع الحركة فوق المكتب (عادية، قص عملات، بين مكتبين)
        a.recordKinds(RecordMenu.TX, () -> f == null ? null : f.office);
        JSONObject d = null;
        String draft = a.db.secretSetting("draft_tx");
        long officeId = a.db.settingLong("last_office", -1);
        if (!draft.isEmpty()) {
            try {
                d = new JSONObject(draft);
                officeId = d.optLong("office", officeId);
            } catch (Exception ignored) {
                d = null;
            }
        }
        if (officeId > 0) {
            Model.Office o = a.db.office(officeId);
            if (o != null && o.active) selectOffice(o);
        }
        if (d != null) {
            f.type.set(d.optInt("type", -1), false);
            f.amount.setText(d.optString("amount"));
            selectCur(f.cur, f.curs, d.optString("currency"));
            if (f.feeCur != null && f.cur != null && !f.cur.none()) f.feeCur.set(f.cur.selected, false);
            f.person.setText(d.optString("person"));
            f.desc.setText(d.optString("desc"));
            f.notes.setText(d.optString("notes"));
            f.service.setText(d.optString("service"));
            f.dest.setText(d.optString("dest"));
            if (d.optInt("feeMode", 0) == 1) {
                // الطريقة أولًا (مسودات ما قبل 7.1.2 بلا طريقة = مبلغ محدد)، ثم القيم، حتى لا يمسح الحساب مبلغًا محفوظًا
                f.feeKind.set(d.has("feeKind") ? d.optInt("feeKind", 0) : 0, false);
                f.ratePer.set(d.optInt("ratePer", 0), false);
                f.rate.setText(d.optString("rate"));
                applyFeeKind();
                f.feeMode.set(1, true);
                f.feeSide.set(d.optInt("feeSide", 0), false);
                if (f.feeKind.selected == 0) f.fee.setText(d.optString("fee"));
                f.feeCurTouched = d.optBoolean("feeCurTouched", false);
                selectCur(f.feeCur, f.curs, d.optString("feeCur"));
                if (feeByRate()) recalcFee();
            }
            long w = d.optLong("when", 0);
            if (w > 0) setWhen(w);
            a.toast("تم استرجاع بيانات حركة لم تُحفظ (الصور لا تُحفظ في المسودة).");
        }
        updateSummary();
    }

    /** رسالة واحدة مشارَكة من واتساب: نموذج جديد والبيان معبّأ، دون المساس بالمسودة. */
    void showShared(WaText.Msg m) {
        openExternal(m, false, "تسجيل حركة من واتساب", null);
    }

    /** 12.1: نموذج تسجيل جديد مفتوح الآن (لا تعديل)، ففقاعة من إطار الالتقاط تنتقل إلى خانته. */
    boolean formOpen() { return f != null && editing == null && f.desc != null && f.wa; }

    /** 12.1: فقاعة من إطار الالتقاط إلى خانة «الصق رسالة الحوالة هنا» في النموذج المفتوح، ثم الاستخراج المعتاد. */
    void captured(WaText.Msg m, Model.Office suggested) {
        if (!formOpen()) {
            showCaptured(m, suggested);
            return;
        }
        if (f.office == null && WaPin.current(a.db) == null && suggested != null && suggested.active) selectOffice(suggested);
        if (!m.time.isEmpty()) f.waTime = m.time;
        putText(m.body);
        a.toast(suggested != null && f.office != null && f.office.id == suggested.id ? "نُقلت الرسالة. المكتب مقترح من اسم المحادثة: تأكّد منه." : "نُقلت الرسالة إلى الخانة.");
    }

    /** 12.1: فقاعة واحدة من إطار الالتقاط بلا نموذج مفتوح. المكتب المثبّت أولًا، ثم المقترح من اسم المحادثة. */
    void showCaptured(WaText.Msg m, Model.Office suggested) {
        Model.Office pinned = WaPin.current(a.db);
        openExternal(m, false, "تسجيل حركة من الشاشة", pinned != null ? null : suggested);
    }

    /** رسالة من دفعة واتساب، مع شريط «الرسالة n من N» في أعلى النموذج. */
    void showQueued(WaText.Msg m, int index, int total, Model.Office office) {
        openExternal(m, true, "تسجيل حركة (" + (index + 1) + " من " + total + ")", office);
        if (f == null) return;
        LinearLayout strip = a.waQueue.strip(m, index, total);
        a.body.removeView(strip);
        a.body.addView(strip, 0);
    }

    /**
     * 10.13: سطر واحد مضغوط أعلى النموذج بدل بطاقتين: مرسل الرسالة ووقتها، وبجانبه شارة صغيرة للمكتب المثبّت لرسائل واتساب
     * (أو تنبيه صغير بالأحمر أنه لم يُثبَّت). اللمس على الشارة يشرح المعنى.
     */
    private View waHead(WaText.Msg m, final Model.Office pinned) {
        LinearLayout r = a.row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(12), a.dp(6), a.dp(8), a.dp(6));
        r.setBackground(a.round(a.goldSoft, a.goldSoft, 14));
        String who = m.sender.isEmpty() ? "رسالة واتساب" : m.sender;
        TextView name = a.text("✉ " + who, 15, a.goldInk, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (!m.time.isEmpty()) {
            TextView tm = a.text("\u2066" + m.time + "\u2069", 12, a.goldInk, false);
            tm.setPadding(a.dp(6), 0, a.dp(6), 0);
            r.addView(tm, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView pin = a.chip(pinned != null ? "📌 " + pinned.name : "⚠ بلا مكتب مثبّت", pinned != null ? a.red : a.red,
                pinned != null ? a.debitBg : a.debitBg);
        pin.setTextSize(12);
        pin.setSingleLine(true);
        pin.setOnClickListener(v -> new android.app.AlertDialog.Builder(a).setTitle("المكتب المثبّت لرسائل واتساب")
                .setMessage(pinned != null ? "رسائل واتساب في هذه الجلسة تُسجَّل لمكتب «" + pinned.name + "». لا يتغير تلقائيًا؛ إن غيّرت المكتب في النموذج يُطلب تأكيد التغيير قبل الحفظ."
                        : "لم يُثبَّت مكتب لرسائل واتساب في هذه الجلسة: سيُطلب تأكيد المكتب قبل أول حفظ.")
                .setPositiveButton("حسنًا", null).show());
        r.addView(pin, new LinearLayout.LayoutParams(-2, -2));
        Model.Office known = m.sender.isEmpty() ? null : a.waQueue.officeFor(m.sender);
        if (known == null || (pinned != null && known.id == pinned.id)) {
            r.setLayoutParams(a.lp(-1, -2, 3));
            return r;
        }
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(r);
        TextView k = a.text("رسائله السابقة سُجّلت لمكتب: " + known.name, 12, a.muted, false);
        k.setPadding(a.dp(12), a.dp(2), a.dp(12), 0);
        c.addView(k);
        c.setLayoutParams(a.lp(-1, -2, 3));
        return c;
    }

    private void openExternal(WaText.Msg m, boolean queued, String title, Model.Office office) {
        if (!a.require(Auth.Perm.TX_ADD)) return;
        fromHold = null;
        converting = false;
        editing = null;
        afterEdit = null;
        existingAtt.clear();
        removedAtt.clear();
        a.pendingImages.clear();
        build(title);
        f.external = true;
        f.queued = queued;
        f.waSender = m.sender;
        f.waTime = m.time;
        if (queued && f.saveBtn != null) f.saveBtn.setText("تسجيل والتالية");
        // 10.5 (§5): المكتب المثبّت صراحة أولًا؛ بغيره يُقترح مكتب ويُطلب تأكيده قبل أول حفظ. لا يتغير تلقائيًا بالمرسل.
        Model.Office pinned = WaPin.current(a.db);
        Model.Office o = office != null ? office : pinned;
        if (o == null && !m.sender.isEmpty()) o = a.waQueue.officeFor(m.sender);
        if (o == null) {
            Model.Office last = a.db.office(a.db.settingLong("last_office", -1));
            if (last != null && last.active) o = last;
        }
        if (o != null && o.active) selectOffice(o);
        a.body.addView(waHead(m, pinned), 0);
        f.pasted = true;
        f.desc.setText(m.body);
        f.desc.removeCallbacks(f.parseLater);
        previewParse();
        updateSummary();
    }

    // ===================================================================================
    // كشف تسجيل الرسالة نفسها مرتين
    // ===================================================================================

    private Map<String, Model.Tx> recent;
    private long recentAt;

    /** بصمات نصوص الحركات النشطة في آخر 120 يومًا (تُحدَّث بعد كل تسجيل أو كل 5 دقائق). */
    Map<String, Model.Tx> recentPrints() {
        long now = Clock.now();
        if (recent != null && now - recentAt < 300000L) return recent;
        Map<String, Model.Tx> m = new java.util.HashMap<>();
        try {
            for (Model.Tx t : a.db.descriptionsSince(now - 120L * 86400000L)) {
                String fp = WaText.fingerprint(t.description);
                if (fp.length() >= 12 && !m.containsKey(fp)) m.put(fp, t);
            }
        } catch (Exception ignored) {
            // الكشف مساعد فقط
        }
        recent = m;
        recentAt = now;
        return m;
    }

    private Model.Tx duplicateOf(String text) {
        String fp = WaText.fingerprint(text);
        if (fp.length() < 12) return null;
        Model.Tx t = recentPrints().get(fp);
        if (t == null || (editing != null && (t.id == editing.id))) return null;
        // البصمات خفيفة (البيان فقط)؛ الحركة المطابقة تُقرأ كاملة للعرض
        Model.Tx full = a.db.tx(t.id);
        return full != null && full.isActive() ? full : null;
    }

    /** 10.2: تصحيح حركة بانتظار الاعتماد ثم اعتمادها. */
    void showApproveEdit(final Model.Tx t, Runnable after) {
        if (!a.require(Auth.Perm.APPROVE)) return;
        if (!t.isPending()) {
            a.toast("الحركة لم تعد بانتظار الاعتماد.");
            return;
        }
        approving = true;
        fill(t, after, "تصحيح ثم اعتماد");
    }

    void showEdit(final Model.Tx t, Runnable after) {
        fromHold = null;
        converting = false;
        // 12.4: الحركة الخاصة تُعدَّل بصلاحية «الحركات الخاصة» (وغير المحسوبة ليست ACTIVE)
        if (t.isSpecial() && !t.isPending()) {
            if (!a.require(Auth.Perm.SPECIAL)) return;
            approving = false;
            fill(t, after, "تعديل حركة خاصة");
            return;
        }
        if (!a.require(Auth.Perm.TX_EDIT)) return;
        if (!t.isActive()) {
            a.toast("لا يمكن تعديل حركة ملغاة أو معدّلة.");
            return;
        }
        approving = false;
        fill(t, after, "تعديل حركة");
    }

    /** 12.4: تعليم حركة عادية كخاصة (نموذج التعديل والخاصة مفعّلة). */
    void showMarkSpecial(final Model.Tx t, Runnable after) {
        if (!a.require(Auth.Perm.SPECIAL)) return;
        if (!t.isActive() || t.isLinked()) {
            a.toast("تُعلَّم الحركة النشطة العادية فقط (لا ساق من عملية مترابطة).");
            return;
        }
        fromHold = null;
        converting = false;
        approving = false;
        fill(t, after, "تعليم كحركة خاصة");
        setSpecial(true);
    }

    /**
     * 12.4: تحويل حركة خاصة إلى عادية: نموذج معبّأ بها (يكمل المحاسب الأجور أو يصحح)، والخاصة مطفأة،
     * وبتاريخها الأصلي أو بتاريخ اليوم كما اختار.
     */
    void showConvert(final Model.Tx t, boolean keepDate, Runnable after) {
        if (!a.require(Auth.Perm.SPECIAL)) return;
        fromHold = null;
        converting = true;
        approving = false;
        fill(t, after, "تحويل لحركة عادية");
        setSpecial(false);
        if (!keepDate) setWhen(0);
        a.body.addView(a.text("الحركة الخاصة «" + t.special + "» تصير حركة عادية تنزل بالكشف والمطابقة "
                + (keepDate ? "بتاريخها " + Model.day(t.createdAt) : "بتاريخ اليوم") + ". أكمل ما ينقص (الأجور مثلًا) ثم «تحويل لحركة عادية».", 13, a.primaryInk, true), 0);
        if (f.saveBtn != null) f.saveBtn.setText("تحويل لحركة عادية");
        updateSummary();
    }

    /** 12.4: أمانة أو مبلغ مجمّد إلى حركة عادية: نموذج جديد معبّأ بمبلغها، والأمانة تُفكّ مربوطةً بالحركة عند الحفظ. */
    void showFromHold(final Db.Hold h, boolean keepDate, Runnable after) {
        if (!a.require(Auth.Perm.TX_ADD) || !a.require(Auth.Perm.HOLDS)) return;
        Model.Office o = a.db.office(h.officeId);
        if (o == null || !o.active) {
            a.toast("المكتب موقوف: فعّله أولًا.");
            return;
        }
        editing = null;
        approving = false;
        converting = false;
        afterEdit = after;
        existingAtt.clear();
        removedAtt.clear();
        a.pendingImages.clear();
        build("تحويل «" + h.label() + "» إلى حركة");
        fromHold = h.uuid;
        f.external = true;
        selectOffice(o);
        // الأمانة لنا عنده = «لنا»؛ للمكتب عندنا = «علينا». المجمّد: تسويته تُنقص الجهة التي جُمّد منها (يغيّرها المحاسب إن لزم)
        boolean ours = Db.SIDE_OURS.equals(h.side);
        boolean debit = Db.HOLD_TRUST.equals(h.kind) ? ours : !ours;
        f.type.set(debit ? 0 : 1, false);
        selectCur(f.cur, f.curs, h.currency);
        f.amount.setText(Model.plain(h.amount, a.db.decimals(h.currency)));
        f.notes.setText((h.note.isEmpty() ? "" : h.note + " — ") + "من " + h.label() + " (" + Model.day(h.at) + ")");
        if (keepDate) setWhen(h.at);
        a.body.addView(a.text("تُسجَّل حركة عادية تنزل بالكشف والمطابقة " + (keepDate ? "بتاريخ " + Model.day(h.at) : "بتاريخ اليوم")
                + "، و«" + h.label() + "» تُفكّ مربوطةً بها. راجع الاتجاه والمبلغ ثم سجّل.", 13, a.primaryInk, true), 0);
        if (f.saveBtn != null) f.saveBtn.setText("تسجيل وفكّ " + (Db.HOLD_TRUST.equals(h.kind) ? "الأمانة" : "التجميد"));
        updateSummary();
    }

    /** 12.4: يفتح نموذج حركة جديدة للمكتب والخاصة مفعّلة. */
    void showNewSpecial(final Model.Office o) {
        if (!a.require(Auth.Perm.SPECIAL)) return;
        showForOffice(o);
        if (f != null) setSpecial(true);
    }

    /** 12.4: تشغيل أو إطفاء قسم «حركة خاصة» في النموذج. */
    void setSpecial(boolean on) {
        if (f == null || f.specialSw == null) return;
        f.specialSw.setChecked(on);
        f.specialOn = on;
        f.specialBody.setVisibility(on ? View.VISIBLE : View.GONE);
        if (f.saveBtn != null && editing == null && fromHold == null) f.saveBtn.setText(on ? "تسجيل كحركة خاصة" : "تسجيل الحركة");
        updateSummary();
    }

    private void fill(final Model.Tx t, Runnable after, String title) {
        editing = t;
        afterEdit = after;
        a.pendingImages.clear();
        existingAtt.clear();
        existingAtt.addAll(a.db.attachmentIds(t.id));
        removedAtt.clear();
        build(title);
        Model.Office o = a.db.office(t.officeId);
        if (o != null) selectOffice(o);
        f.type.set(Model.TYPE_DEBIT.equals(t.type) ? 0 : 1, false);
        f.amount.setText(Model.plain(t.amount, a.db.decimals(t.currency)));
        selectCur(f.cur, f.curs, t.currency);
        f.person.setText(t.person);
        f.desc.setText(t.description);
        // عند التعديل الحقول معبّأة أصلًا: لا تظهر بطاقة الاستخراج إلا إن غيّر المستخدم البيان
        f.desc.removeCallbacks(f.parseLater);
        f.parsedText = Model.nz(t.description);
        if (f.parseBox != null) f.parseBox.setVisibility(View.GONE);
        f.notes.setText(t.notes);
        f.service.setText(t.service);
        f.dest.setText(t.dest);
        setWhen(t.createdAt);
        if (t.hasFee()) {
            // الأجرة المسجّلة مبلغ ثابت (حتى لو حُسبت من نسبة عند التسجيل)
            f.feeKind.set(0, false);
            applyFeeKind();
            f.feeMode.set(1, true);
            f.feeSide.set(Model.FEE_US.equals(t.feeType) ? 0 : 1, false);
            f.fee.setText(Model.plain(t.fee, a.db.decimals(t.feeCurrency)));
            f.feeCurTouched = true;
            selectCur(f.feeCur, f.curs, t.feeCurrency);
        }
        if (!existingAtt.isEmpty()) {
            f.imgMode.set(1, true);
            refreshThumbs();
        }
        if (t.isSpecial() && f.specialSw != null) {
            f.specialType = t.special;
            renderSpecialTypes();
            f.specialBal.set(t.specialBal ? 0 : 1, false);
            setSpecial(true);
        }
        updateSummary();
    }

    // ===================================================================================
    // بناء النموذج
    // ===================================================================================

    private void build(String title) {
        a.base(title, editing == null ? null : () -> {
            if (afterEdit != null) afterEdit.run();
            else a.showHome();
        });
        f = new Form();
        final Form form = f;
        if (editing != null && approving) {
            a.notice(R.drawable.ic_check2, "اقترحها: " + (editing.userName.isEmpty() ? "موظف" : editing.userName) + ". صحّح ما يلزم ثم اضغط «حفظ التصحيح واعتماد»: "
                    + "تدخل الأرصدة بالقيم المصححة، ويُحفظ ما قبل التصحيح في سجل العمليات.", false, null, null);
        } else if (editing != null) {
            a.hint("التعديل يسجّل قيدًا عكسيًا يلغي الحركة الأصلية بالكامل، ثم يسجّل الحركة المصححة مرتبطة بها. الأصل يبقى ظاهرًا في السجل كمعدّل.");
        }
        // 12.0: التسجيل من رسائل واتساب صلاحية مستقلة (بدونها يبقى البيان حقلًا عاديًا)
        form.wa = "1".equals(a.db.setting("wa_parse", "1")) && a.can(Auth.Perm.TX_WA);
        form.names = a.db.currencyNames();
        form.officeBtn = a.button("اختر المكتب", a.accent, v -> a.officePicker(o -> {
            selectOffice(o);
            chosenOffice = o;   // 12.7: اختيار صريح من المحاسب
        }, true));
        form.balance = a.add(a.text("", 13, a.muted, false));

        if (form.wa) {
            // البيان أولًا: تُلصق فيه رسالة واتساب، فتظهر المعلومات المستخرجة، ثم يضيفها المحاسب للحقول بزر
            // 10.13: عنوان قصير وزر «لصق» صغير بجانبه بدل زر عريض، وحقل أصغر يكبر مع النص
            LinearLayout dl = a.row();
            waHead = dl;
            dl.setGravity(Gravity.CENTER_VERTICAL);
            dl.addView(a.label("رسالة واتساب"), new LinearLayout.LayoutParams(0, -2, 1));
            // 12.3: «مسح» في سطر العنوان نفسه (مقابل «رسالة واتساب») فوق شريط الأدوات
            if (editing == null) {
                LinearLayout clr = a.toolChip(R.drawable.ic_trash, "مسح", 2, v -> clearForm());
                clr.setContentDescription("مسح النموذج");
                dl.addView(clr, new LinearLayout.LayoutParams(-2, a.dp(34)));
            }
            a.body.addView(dl, a.lp(-1, -2, 2));
            // 12.3: شريط أدوات الرسالة بأزرار بأيقونات: لصق، التقاط وتجميع (ويندوز وماك)، قص، مسح النموذج
            LinearLayout tools = a.row();
            tools.setGravity(Gravity.CENTER_VERTICAL);
            tools.addView(a.toolChip(R.drawable.ic_paste, "لصق", 0, v -> pasteMessage()), toolLp(tools));
            if (editing == null && a.waMove.canCapture()) {
                // 12.1: إطار يرقّم الرسائل على الشاشة، فتضغط الرقم فينتقل نصها إلى هذه الخانة
                tools.addView(a.toolChip(R.drawable.ic_capture, "التقاط", 1, v -> a.waMove.openCapture(false)), toolLp(tools));
                // 12.2: تنسخ رسائل من واتساب واحدة تلو الأخرى فتظهر مرقمة في نافذة صغيرة، ثم «نقل»
                tools.addView(a.toolChip(R.drawable.ic_collect, "تجميع", 1, v -> a.waMove.startOrShowCollect()), toolLp(tools));
            }
            if (editing == null && a.waMove.showCut()) {
                tools.addView(a.toolChip(R.drawable.ic_cut, "قص", 1, v -> cutFromForm()), toolLp(tools));
            }
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setFillViewport(false);
            hs.addView(tools, new android.widget.FrameLayout.LayoutParams(-2, -2));
            a.body.addView(hs, a.lp(-1, -2, 4));
            form.desc = a.field("الصق رسالة الحوالة هنا");
            form.desc.setSingleLine(false);
            form.desc.setMinLines(2);
            form.desc.setMaxLines(10);
            form.desc.setGravity(Gravity.TOP | Gravity.START);
            form.desc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            form.desc.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
            form.desc.setTextSize(15);
            a.body.addView(form.desc, a.lp(-1, -2, 2));
            form.parseBox = new LinearLayout(a);
            form.parseBox.setOrientation(LinearLayout.VERTICAL);
            form.parseBox.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            form.parseBox.setBackground(a.round(Color.rgb(248, 249, 252), Color.rgb(205, 214, 230), 12));
            form.parseBox.setVisibility(View.GONE);
            a.body.addView(form.parseBox, a.lp(-1, -2, 4));
            form.desc.addTextChangedListener(Ui.watcher(() -> {
                form.desc.removeCallbacks(form.parseLater);
                form.desc.postDelayed(form.parseLater, 450);
            }));
            form.desc.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int after) {}

                @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                    if (count - before >= 15) form.pasted = true;
                    // 11.3: لصق عدة رسائل مباشرة في الخانة (Ctrl+V على الكمبيوتر، أو «لصق» من قائمة الخانة): العرض نفسه كزر «لصق»
                    if (count - before >= 30 && editing == null && !form.queued && !splitOffered) {
                        final String ins = s.subSequence(st, st + count).toString();
                        // 12.1: نسخة HTML من الحافظة إن كان هذا هو نصها (واتساب ويندوز والمتصفح)
                        WaClip.Read cr = WaClip.read(a);
                        final List<WaText.Msg> msgs = WaClip.messages(ins, cr.text.trim().equals(ins.trim()) ? cr.html : null);
                        if (msgs.size() > 1) {
                            splitOffered = true;
                            form.desc.post(() -> offerSplit(msgs, ins, () -> {
                                String cur = form.desc.getText().toString();
                                int at = cur.indexOf(ins);
                                if (at >= 0) form.desc.setText(cur.substring(0, at) + cur.substring(at + ins.length()));
                            }));
                        }
                    }
                }

                @Override public void afterTextChanged(android.text.Editable e) {}
            });
        }

        // لا يُختار النوع مسبقًا: المحاسب يختار «لنا» أو «علينا» في كل حركة
        final android.view.View splitMark = a.add(a.label("نوع الحركة (إلزامي)"));
        form.type = a.new Seg(new String[]{"لنا (مدين)", "علينا (دائن)"}, new int[]{a.green, a.red}, -1, false);
        form.type.listener = i -> {
            form.type.flagMissing(false);
            updateSummary();
        };
        a.body.addView(form.type.view, a.lp(-1, -2, 2));

        form.amountLabel = a.add(a.label("المبلغ"));
        form.amount = a.amountInput("0");
        form.amount.setTextSize(22);
        Ui.bold(form.amount);
        form.amount.addTextChangedListener(Ui.watcher(() -> {
            if (feeByRate()) recalcFee();
            else updateSummary();
        }));

        a.add(a.label(form.wa ? "العملة (اخترها، أو أضفها من البيان)" : "العملة"));
        form.curHolder = new LinearLayout(a);
        a.body.addView(form.curHolder, a.lp(-1, -2, 0));

        form.person = a.input("اسم الشخص أو المستفيد");
        if (!form.wa) {
            // الميزة متوقفة: البيان حقل عادي اختياري كما في الإصدارات السابقة
            form.desc = a.input("البيان (اختياري)");
            form.desc.setSingleLine(false);
            form.desc.setMaxLines(5);
            form.desc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            form.desc.setLayoutParams(a.lp(-1, -2, 4));
        }
        form.notes = a.input("ملاحظات (اختياري)");
        // 8.0: نوع الخدمة والوجهة في خانتين ثابتتين (لتقرير «الخدمات والوجهات»)، اختياريتان
        form.service = a.input("نوع الخدمة (اختياري): اعتماد، سويفت، USDT…");
        final EditText svc = form.service;
        LinearLayout chips = a.row();
        java.util.LinkedHashSet<String> sv = new java.util.LinkedHashSet<>(a.db.usedServices(8));
        for (String x : a.db.setting("services", "اعتماد,سويفت,USDT,تسليم نقدي,تحويل بنكي").split(",")) if (!x.trim().isEmpty()) sv.add(x.trim());
        sv.remove("تبديل عملة");
        for (final String x : sv) chips.addView(a.pill(x, false, v -> svc.setText(x)), a.gap(-2, -2, 3));
        final View svcChips = a.strip(chips);
        a.body.addView(svcChips, a.lp(-1, -2, 2));
        form.dest = a.input("الوجهة: المدينة أو البلد (اختياري)");
        form.person.addTextChangedListener(Ui.watcher(this::updateSummary));

        form.dateBtn = a.smallButton("", Color.rgb(110, 110, 110), v -> pickDate());
        a.body.addView(form.dateBtn, a.lp(-1, 44, 4));
        // 12.0: بلا صلاحية «تاريخ سابق» تُسجَّل الحركة بتاريخ اليوم (الزر يبقى ظاهرًا للعلم لكنه معطّل)
        if (!a.can(Auth.Perm.BACKDATE) && editing == null) form.dateBtn.setEnabled(false);
        setWhen(0);

        final View feeHead = a.add(a.label("الأجرة"));
        form.feeMode = a.new Seg(new String[]{"بدون أجرة", "مع أجرة"}, null, 0, false);
        a.body.addView(form.feeMode.view, a.lp(-1, -2, 2));
        form.feeBox = new LinearLayout(a);
        form.feeBox.setOrientation(LinearLayout.VERTICAL);
        form.feeBox.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
        form.feeBox.setBackground(a.round(Color.rgb(250, 248, 242), Color.rgb(225, 220, 205), 12));
        form.feeSide = a.new Seg(new String[]{"الأجرة لنا", "الأجرة علينا"}, new int[]{a.green, a.red}, 0, false);
        form.feeSide.listener = i -> updateSummary();
        form.feeBox.addView(form.feeSide.view);
        // مبلغ محدد، أو نسبة من المبلغ الأصلي (1٪ أو 1.5‰ مثلًا) تُحسب منها الأجرة بعملة المبلغ نفسها
        int lastKind = "1".equals(a.db.setting("fee_kind_last", "0")) ? 1 : 0;
        form.feeKind = a.new Seg(new String[]{"مبلغ محدد", "نسبة من المبلغ"}, null, lastKind, false);
        form.feeBox.addView(form.feeKind.view, a.lp(-1, -2, 4));
        form.rateBox = new LinearLayout(a);
        form.rateBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rr = a.row();
        rr.setGravity(Gravity.CENTER_VERTICAL);
        form.rate = a.field("النسبة");
        Ui.numeric(form.rate);
        form.rate.setGravity(Gravity.CENTER);
        rr.addView(form.rate, new LinearLayout.LayoutParams(a.dp(96), a.dp(50)));
        form.ratePer = a.new Seg(new String[]{"٪ بالمئة", "‰ بالألف"}, null, "1000".equals(a.db.setting("fee_per_last", "100")) ? 1 : 0, false);
        rr.addView(form.ratePer.view, new LinearLayout.LayoutParams(0, -2, 1));
        form.rateBox.addView(rr, a.lp(-1, -2, 4));
        form.rateChips = a.flow();
        form.rateBox.addView(form.rateChips, a.lp(-1, -2, 2));
        form.rateResult = a.text("", 14, a.ink, true);
        form.rateResult.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(2));
        form.rateBox.addView(form.rateResult);
        form.feeBox.addView(form.rateBox);
        form.fee = a.field("مبلغ الأجرة");
        Ui.numeric(form.fee);
        form.fee.addTextChangedListener(Ui.watcher(this::updateSummary));
        form.feeBox.addView(form.fee, a.lp(-1, 50, 4));
        form.feeCurLabel = a.text("عملة الأجرة (تتبع عملة المبلغ تلقائيًا)", 13, a.dark, true);
        form.feeBox.addView(form.feeCurLabel);
        form.feeCurHolder = new LinearLayout(a);
        form.feeBox.addView(form.feeCurHolder);
        form.feeKind.listener = i -> applyFeeKind();
        form.ratePer.listener = i -> recalcFee();
        form.rate.addTextChangedListener(Ui.watcher(this::recalcFee));
        fillRateChips(form);
        applyFeeKind(form);
        form.feeBox.setVisibility(View.GONE);
        a.body.addView(form.feeBox, a.lp(-1, -2, 4));
        form.feeMode.listener = i -> {
            form.feeBox.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
            if (i == 1 && form.feeCur != null && form.feeCur.none() && form.cur != null && !form.cur.none()) form.feeCur.set(form.cur.selected, false);
            if (i == 1) (form.feeKind.selected == 1 ? form.rate : form.fee).requestFocus();
            if (feeByRate()) recalcFee();
            else updateSummary();
        };

        final TextView imgLabel = a.add(a.label("صور الوصل"));
        form.imgMode = a.new Seg(new String[]{"بدون صور", "مع صور"}, null, 0, false);
        a.body.addView(form.imgMode.view, a.lp(-1, -2, 2));
        form.imgBox = new LinearLayout(a);
        form.imgBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout ib = a.row();
        ib.addView(a.smallButton("من الاستديو", a.accent, v -> pickImages()), a.weight(46, 1, 3));
        ib.addView(a.smallButton("بالكاميرا", a.accent, v -> takePhoto()), a.weight(46, 1, 3));
        form.imgBox.addView(ib);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        form.thumbs = a.row();
        hs.addView(form.thumbs);
        form.imgBox.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        form.imgBox.setVisibility(View.GONE);
        a.body.addView(form.imgBox, a.lp(-1, -2, 2));
        form.imgMode.listener = i -> form.imgBox.setVisibility(i == 1 ? View.VISIBLE : View.GONE);

        // 10.9: الحقول الاختيارية في «تفاصيل إضافية» — كل الميزات باقية، والشاشة أبسط. تُفتح تلقائيًا إن كان فيها شيء
        form.more = new LinearLayout(a);
        form.more.setOrientation(LinearLayout.VERTICAL);
        form.more.setVisibility(View.GONE);
        int at = a.body.indexOfChild(form.notes);
        View[] opt = {form.notes, form.service, svcChips, form.dest, form.dateBtn, imgLabel, form.imgMode.view, form.imgBox};
        for (View v : opt) {
            ViewGroup.LayoutParams lpv = v.getLayoutParams();
            a.body.removeView(v);
            form.more.addView(v, lpv);
        }
        // الأجرة قبلها (مالية وأساسية)، ثم «تفاصيل إضافية»
        int feeAt = a.body.indexOfChild(form.feeBox) + 1;
        form.moreToggle = a.text("", 14, a.primaryInk, true);
        form.moreToggle.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        form.moreToggle.setBackground(a.ripple(a.round(a.surface, a.cardStroke, 14), 14));
        Ui.springy(form.moreToggle, 0.97f);
        form.moreToggle.setOnClickListener(v -> showMore(form, form.more.getVisibility() != View.VISIBLE));
        a.body.addView(form.moreToggle, Math.min(feeAt, a.body.getChildCount()), a.lp(-1, -2, 6));
        a.body.addView(form.more, Math.min(feeAt + 1, a.body.getChildCount()), a.lp(-1, -2, 0));
        showMore(form, false);

        // 12.0: بطاقات بتنسيق «قص العملات»: رسالة واتساب، والحركة (بلون نوعها)، والأجرة
        if (form.wa && waHead != null) wrapCard(waHead, form.parseBox, a.surface, a.cardStroke);
        View lastTx = form.wa ? form.person : form.desc;
        form.txCard = wrapCard(splitMark, a.body.indexOfChild(lastTx) > a.body.indexOfChild(form.person) ? lastTx : form.person, a.surface, a.cardStroke);
        wrapCard(feeHead, form.feeBox, Color.rgb(250, 248, 242), Color.rgb(225, 220, 205));
        tintTxCard();
        waHead = null;

        // 12.4: «حركة خاصة»: ناقصة أجور، تحتاج مراجعة، بانتظار التنفيذ… تظهر تحت «الأمانات والخاصة» في المكتب
        if (a.can(Auth.Perm.SPECIAL) && !approving && !(editing != null && (editing.isLinked() || editing.isPending()))) buildSpecial(form);

        // ملخص ما سيُسجَّل قبل التسجيل: بطاقة مرتبة، عنوانها أحمر، والناقص بالأحمر مع ✗
        form.summary = new LinearLayout(a);
        form.summary.setOrientation(LinearLayout.VERTICAL);
        form.summary.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        form.summary.setBackground(a.round(Ui.mix(a.surface, a.red, a.night ? 0.12f : 0.045f), Ui.mix(a.line, a.red, 0.55f), 16));
        a.body.addView(form.summary, a.lp(-1, -2, 8));

        // الشريط الثابت أسفل الشاشة: ملخص مختصر وزر التسجيل ظاهران دائمًا حتى مع لوحة المفاتيح
        LinearLayout foot = new LinearLayout(a);
        foot.setOrientation(LinearLayout.VERTICAL);
        form.footerLine = a.text("", 13, a.red, true);
        form.footerLine.setSingleLine(true);
        form.footerLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        form.footerLine.setPadding(a.dp(4), 0, a.dp(4), a.dp(2));
        foot.addView(form.footerLine, new LinearLayout.LayoutParams(-1, -2));
        a.hideWithKeyboard = form.footerLine;
        form.saveBtn = a.makeButton(editing == null ? "تسجيل الحركة" : approving ? "حفظ التصحيح واعتماد" : "حفظ التعديل", a.primary, v -> save());
        // 10.9: اضغط مطوّلًا حتى يمتلئ الزر، ثم تظهر نافذة التأكيد نفسها
        a.holdToConfirm(form.saveBtn);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, a.dp(50));
        sbp.topMargin = a.dp(4);
        foot.addView(form.saveBtn, sbp);
        a.footer(foot);
        // 12.3: «مسح النموذج» صار في شريط أدوات الرسالة أعلى النموذج (وفي الأسفل إن لم يكن التسجيل من واتساب متاحًا)
        if (editing == null && !form.wa) {
            Button clear = a.smallButton("مسح النموذج", Color.rgb(130, 130, 130), v -> clearForm());
            clear.setTextSize(13);
            LinearLayout cr = a.row();
            cr.setGravity(Gravity.CENTER);
            cr.addView(clear, new LinearLayout.LayoutParams(-2, a.dp(38)));
            a.body.addView(cr, a.lp(-1, -2, 6));
        }
        // 10.13: على التابلت: الرسالة والمكتب في عمود، والحقول والملخص في العمود المقابل
        if (form.wa) a.splitBody(form.txCard != null ? form.txCard : splitMark);
    }

    private View waHead;

    private void buildSpecial(final Form form) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(10));
        c.setBackground(a.round(a.surface, a.cardStroke, 18));
        LinearLayout hd = a.row();
        hd.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tc = new LinearLayout(a);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.addView(a.text("حركة خاصة", 15, a.ink, true));
        tc.addView(a.text("ناقصة أجور، تحتاج مراجعة، بانتظار التنفيذ… تظهر في «الأمانات والخاصة» بالمكتب حتى تحوّلها لعادية", 12, a.muted, false));
        hd.addView(tc, new LinearLayout.LayoutParams(0, -2, 1));
        form.specialSw = new android.widget.Switch(a);
        hd.addView(form.specialSw, new LinearLayout.LayoutParams(-2, -2));
        c.addView(hd);
        form.specialBody = new LinearLayout(a);
        form.specialBody.setOrientation(LinearLayout.VERTICAL);
        form.specialBody.addView(a.label("النوع"));
        form.specialTypes = a.flow();
        form.specialBody.addView(form.specialTypes, a.lp(-1, -2, 2));
        form.specialBody.addView(a.label("في الرصيد"));
        form.specialBal = a.new Seg(new String[]{"تُحسب في الرصيد", "لا تُحسب في الرصيد"}, null, -1, false);
        form.specialBal.listener = i -> updateSummary();
        form.specialBody.addView(form.specialBal.view, a.lp(-1, -2, 2));
        form.specialBody.addView(a.text("المحسوبة تدخل الرصيد وتظهر في الكشف والمطابقة بعلامة «معلّقة». غير المحسوبة لا تدخل الرصيد ولا الكشف حتى تُحوَّل لحركة عادية.", 12, a.muted, false));
        form.specialBody.setVisibility(View.GONE);
        c.addView(form.specialBody, a.lp(-1, -2, 4));
        form.specialSw.setOnCheckedChangeListener((b, on) -> {
            if (f != form) return;
            form.specialOn = on;
            form.specialBody.setVisibility(on ? View.VISIBLE : View.GONE);
            if (form.saveBtn != null && editing == null && fromHold == null) form.saveBtn.setText(on ? "تسجيل كحركة خاصة" : "تسجيل الحركة");
            updateSummary();
        });
        hd.setOnClickListener(v -> form.specialSw.toggle());
        renderSpecialTypes();
        a.body.addView(c, a.lp(-1, -2, 8));
    }

    private void renderSpecialTypes() {
        final Form form = f;
        if (form == null || form.specialTypes == null) return;
        form.specialTypes.removeAllViews();
        List<String> types = a.db.specialTypes();
        if (!form.specialType.isEmpty() && !types.contains(form.specialType)) types.add(form.specialType);
        for (final String x : types) {
            View p = a.pill(x, x.equals(form.specialType), v -> {
                form.specialType = x;
                renderSpecialTypes();
                updateSummary();
            });
            p.setOnLongClickListener(v -> {
                a.confirm("حذف النوع «" + x + "»", "يُحذف من القائمة فقط؛ الحركات المسجّلة به تبقى كما هي.", "حذف", () -> {
                    try {
                        a.db.editSpecialType(x, true);
                        if (x.equals(form.specialType)) form.specialType = "";
                        renderSpecialTypes();
                    } catch (Exception e) {
                        a.error(e);
                    }
                });
                return true;
            });
            form.specialTypes.addView(p);
        }
        form.specialTypes.addView(a.pill("+ نوع جديد", false, v -> {
            final EditText e = a.field("مثل: عليها مشاكل، بانتظار الإيصال");
            new AlertDialog.Builder(a).setTitle("نوع حركة خاصة").setView(e).setPositiveButton("إضافة", (d, w) -> {
                try {
                    a.db.editSpecialType(e.getText().toString(), false);
                    form.specialType = e.getText().toString().trim();
                    renderSpecialTypes();
                    updateSummary();
                } catch (Exception ex) {
                    a.error(ex);
                }
            }).setNegativeButton("إلغاء", null).show();
        }));
    }

    /** 12.0: ينقل عناصر الصفحة من from إلى to (متتالية) إلى بطاقة مستديرة في مكانها. */
    private LinearLayout wrapCard(View from, View to, int fill, int stroke) {
        int i0 = a.body.indexOfChild(from), i1 = a.body.indexOfChild(to);
        if (i0 < 0 || i1 < i0) return null;
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(12));
        c.setBackground(a.round(fill, stroke, 18));
        List<View> vs = new ArrayList<>();
        for (int i = i0; i <= i1; i++) vs.add(a.body.getChildAt(i));
        for (View v : vs) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            a.body.removeView(v);
            c.addView(v, lp);
        }
        a.body.addView(c, i0, a.lp(-1, -2, 5));
        return c;
    }

    /** لون بطاقة الحركة حسب النوع: «لنا» أخضر و«علينا» أحمر (كقص العملات)، ومحايد قبل الاختيار. */
    private void tintTxCard() {
        if (f == null || f.txCard == null || f.type == null) return;
        int sel = f.type.selected;
        int fill = sel == 0 ? a.creditBg : sel == 1 ? a.debitBg : a.surface;
        int stroke = sel == 0 ? Ui.mix(a.creditBg, a.green, 0.45f) : sel == 1 ? Ui.mix(a.debitBg, a.red, 0.45f) : a.cardStroke;
        f.txCard.setBackground(a.round(fill, stroke, 18));
    }

    // ===================================================================================
    // الأجرة كنسبة من المبلغ الأصلي
    // ===================================================================================

    /** الأجرة مفعّلة وطريقتها «نسبة من المبلغ». */
    private boolean feeByRate() {
        return f != null && f.feeKind != null && f.feeMode != null && f.feeMode.selected == 1 && f.feeKind.selected == 1;
    }

    private int ratePer(Form form) { return form.ratePer != null && form.ratePer.selected == 1 ? Model.PER_MILLE : Model.PER_CENT; }

    /** الأجرة المحسوبة بالوحدة الصغرى لعملة المبلغ، أو null إن نقص شيء (النسبة أو المبلغ أو العملة). نفس الدالة للعرض وللتسجيل. */
    private Long rateFeeMinor(Form form) {
        if (form == null || form.cur == null || form.cur.none()) return null;
        java.math.BigDecimal r = Model.parseRate(form.rate.getText().toString());
        int per = ratePer(form);
        if (r == null || r.compareTo(java.math.BigDecimal.valueOf(per)) > 0) return null;
        try {
            long amt = Db.minor(form.amount.getText().toString(), a.db.decimals(form.cur.label()));
            return amt > 0 ? Model.feeFromRate(amt, r, per) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyFeeKind() { applyFeeKind(f); }

    private void applyFeeKind(Form form) {
        if (form == null || form.feeKind == null) return;
        boolean byRate = form.feeKind.selected == 1;
        form.rateBox.setVisibility(byRate ? View.VISIBLE : View.GONE);
        form.fee.setEnabled(!byRate);
        form.fee.setHint(byRate ? "الأجرة المحسوبة تظهر هنا" : "مبلغ الأجرة");
        form.feeCurLabel.setText(byRate ? "عملة الأجرة: عملة المبلغ نفسها" : "عملة الأجرة (تتبع عملة المبلغ تلقائيًا)");
        form.feeCurHolder.setVisibility(byRate ? View.GONE : View.VISIBLE);
        if (byRate) {
            if (form.feeMode != null && form.feeMode.selected == 1 && form.summary != null) form.rate.requestFocus();
            recalcFee();
        } else updateSummary();
    }

    /** يحسب الأجرة من النسبة ويعرض طريقة الحساب تحت الخانة، ويكتب الناتج في خانة الأجرة (غير قابلة للتعديل في هذا الوضع). */
    private void recalcFee() {
        final Form form = f;
        if (form == null || form.feeKind == null) return;
        if (form.feeKind.selected != 1) {
            updateSummary();
            return;
        }
        if (form.feeCur != null && form.cur != null && !form.cur.none() && form.feeCur.selected != form.cur.selected) form.feeCur.set(form.cur.selected, false);
        String rt = form.rate.getText().toString().trim();
        java.math.BigDecimal r = Model.parseRate(rt);
        int per = ratePer(form);
        String cur = form.cur == null ? null : form.cur.label();
        String amtText = form.amount.getText().toString().trim();
        String msg, value = "";
        int color = a.muted;
        if (rt.isEmpty()) msg = per == Model.PER_MILLE ? "اكتب النسبة بالألف، مثل 1.5" : "اكتب النسبة بالمئة، مثل 1";
        else if (r == null) {
            msg = "✗ النسبة غير صحيحة: اكتب رقمًا مثل 1 أو 1.5";
            color = a.red;
        } else if (r.compareTo(java.math.BigDecimal.valueOf(per)) > 0) {
            msg = "✗ النسبة أكبر من المبلغ نفسه";
            color = a.red;
        } else if (cur == null) msg = "اختر عملة المبلغ لتُحسب الأجرة";
        else if (amtText.isEmpty()) msg = "اكتب المبلغ لتُحسب الأجرة";
        else {
            int d = a.db.decimals(cur);
            long amt;
            try {
                amt = Db.minor(amtText, d);
            } catch (Exception e) {
                amt = -1;
            }
            if (amt <= 0) {
                msg = "✗ المبلغ غير صحيح";
                color = a.red;
            } else {
                long fee = Model.feeFromRate(amt, r, per);
                if (fee <= 0) {
                    msg = "✗ الأجرة أصغر من أصغر وحدة في " + cur;
                    color = a.red;
                } else {
                    value = Model.plain(fee, d);
                    msg = "الأجرة = \u2066" + Model.fmt(fee, d) + " " + cur + "\u2069  (" + Model.rateLabel(r, per) + " من \u2066" + Model.fmt(amt, d) + "\u2069"
                            + (Model.feeRounded(amt, r, per) ? "، مقرّبة" : "") + ")";
                    color = a.primaryInk;
                }
            }
        }
        form.rateResult.setText(msg);
        form.rateResult.setTextColor(color);
        if (!form.fee.getText().toString().equals(value)) form.fee.setText(value);
        updateSummary();
    }

    /** آخر النسب المستخدمة («1|100,1.5|1000») أزرارًا سريعة: ضغطة تملأ النسبة ونوعها. */
    private void fillRateChips(final Form form) {
        form.rateChips.removeAllViews();
        for (String item : a.db.setting("fee_rates_recent", "").split(",")) {
            String[] p = item.split("\\|");
            if (p.length != 2) continue;
            final java.math.BigDecimal r = Model.parseRate(p[0]);
            final int per = "1000".equals(p[1]) ? Model.PER_MILLE : Model.PER_CENT;
            if (r == null) continue;
            form.rateChips.addView(a.pill(Model.rateLabel(r, per), false, v -> {
                form.ratePer.set(per == Model.PER_MILLE ? 1 : 0, false);
                form.rate.setText(r.stripTrailingZeros().toPlainString());
                recalcFee();
            }));
        }
        form.rateChips.setVisibility(form.rateChips.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** يحفظ طريقة الأجرة، ونسبتها في أول قائمة آخر النسب (حتى 4). */
    private void rememberFee(boolean byRate, String rateText, int per) {
        a.db.setSettingQuiet("fee_kind_last", byRate ? "1" : "0");
        if (!byRate) return;
        java.math.BigDecimal r = Model.parseRate(rateText);
        if (r == null) return;
        String key = r.stripTrailingZeros().toPlainString() + "|" + per;
        List<String> l = new ArrayList<>();
        l.add(key);
        for (String item : a.db.setting("fee_rates_recent", "").split(",")) {
            if (!item.isEmpty() && !item.equals(key) && l.size() < 4) l.add(item);
        }
        a.db.setSettingQuiet("fee_rates_recent", Model.join(l, ","));
        a.db.setSettingQuiet("fee_per_last", "" + per);
    }

    private Ui.Seg curSeg(LinearLayout holder, List<String> codes, Ui.OnPick l) {
        holder.removeAllViews();
        if (codes.isEmpty()) {
            holder.addView(a.text("لا توجد عملات مفعّلة لهذا المكتب", 13, a.red, false));
            return null;
        }
        // لا عملة محددة مسبقًا: المحاسب يختار العملة في كل حركة (إلا إن كان للمكتب عملة واحدة)
        Ui.Seg s = a.new Seg(codes.toArray(new String[0]), null, codes.size() == 1 ? 0 : -1, true);
        s.listener = l;
        holder.addView(s.view, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private static void selectCur(Ui.Seg s, List<String> codes, String code) {
        if (s == null || code == null) return;
        int i = codes.indexOf(code);
        if (i >= 0) s.set(i, false);
    }

    /** 9.0: اسم المكتب المختار بلون مميز وإطار حوله، حتى لا تُسجَّل حركة على مكتب آخر سهوًا. */
    void markOffice(Button b, boolean chosen) {
        if (!chosen) return;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(a.fillFor(a.primarySoft));
        g.setCornerRadius(a.dp(14));
        g.setStroke(a.dp(2), a.primary);
        b.setBackground(a.ripple(g, 14));
        b.setTextColor(a.readable(a.primaryInk));
        b.setTextSize(18);
    }

    private void selectOffice(Model.Office o) {
        if (f == null) return;
        final Form form = f;
        form.office = o;
        form.officeBtn.setText((o.pinned ? "📌 " : "") + "المكتب: " + o.name);
        markOffice(form.officeBtn, true);
        String prevCur = form.cur == null ? null : form.cur.label();
        form.curs = a.db.officeCurrencies(o.id);
        form.cur = curSeg(form.curHolder, form.curs, i -> {
            if ((!form.feeCurTouched || feeByRate()) && form.feeCur != null) form.feeCur.set(i, false);
            if (form.cur != null) form.cur.flagMissing(false);
            metalLabels(form);
            if (feeByRate()) recalcFee();
            else updateSummary();
        });
        form.feeCur = curSeg(form.feeCurHolder, form.curs, i -> {
            form.feeCurTouched = true;
            if (form.feeCur != null) form.feeCur.flagMissing(false);
            metalLabels(form);
            updateSummary();
        });
        if (prevCur != null) {
            selectCur(form.cur, form.curs, prevCur);
            if (!form.feeCurTouched) selectCur(form.feeCur, form.curs, prevCur);
        } else if (form.cur != null && form.feeCur != null && !form.cur.none() && !form.feeCurTouched) {
            form.feeCur.set(form.cur.selected, false);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : a.db.balances(o.id).entrySet()) {
            if (sb.length() > 0) sb.append("   •   ");
            sb.append(e.getKey()).append(": ").append(Model.bal(e.getValue(), a.db.decimals(e.getKey())));
        }
        form.balance.setText(sb.length() == 0 ? "" : "الرصيد الحالي: " + sb);
        if (form.parseBox != null && form.parseBox.getVisibility() == View.VISIBLE) previewParse();
        if (feeByRate()) recalcFee();
        else updateSummary();
    }

    // ===================================================================================
    // استخراج المعلومات من رسالة واتساب
    // ===================================================================================

    private LinearLayout.LayoutParams toolLp(LinearLayout row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, a.dp(38));
        if (row.getChildCount() > 0) p.setMarginStart(a.dp(8));
        return p;
    }

    /** 12.3: «مسح النموذج»: بعد تأكيد إن كان فيه شيء مكتوب، ثم نموذج جديد فارغ. */
    private void clearForm() {
        Runnable go = () -> {
            f = null;
            a.db.deleteSetting("draft_tx");
            a.pendingImages.clear();
            showNew();
        };
        boolean any = f != null && ((f.desc != null && f.desc.getText().toString().trim().length() > 0)
                || (f.amount != null && f.amount.getText().toString().trim().length() > 0)
                || (f.person != null && f.person.getText().toString().trim().length() > 0) || !a.pendingImages.isEmpty());
        if (!any) {
            go.run();
            return;
        }
        new AlertDialog.Builder(a).setTitle("مسح النموذج؟").setMessage("يُمسح كل ما كتبته في هذا النموذج (الرسالة والحقول والصور) ويبدأ نموذج جديد فارغ.")
                .setPositiveButton("مسح", (d, w) -> go.run()).setNegativeButton("إلغاء", null).show();
    }

    /**
     * 12.3: «قص»: نص فيه عدة رسائل ملزوقة (في الخانة، أو في الحافظة إن كانت الخانة فارغة) يُقطع يدويًا عند حدود الرسائل.
     */
    private void cutFromForm() {
        if (f == null) return;
        String text = f.desc == null ? "" : f.desc.getText().toString().trim();
        if (text.isEmpty()) text = WaClip.read(a).text == null ? "" : WaClip.read(a).text.trim();
        if (WaCuts.lines(text).size() < 2) {
            new AlertDialog.Builder(a).setTitle("قص الرسائل")
                    .setMessage("«قص» لنص فيه عدة رسائل ملزوقة ببعض.\n\n1. انسخ الرسائل من واتساب (أو الصقها في خانة الرسالة).\n2. اضغط «قص».\n"
                            + "3. اضغط الخط المنقّط بين سطرين ليبدأ من هناك رسالة جديدة، ثم «متابعة».")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        a.beforeScreenChange();
        a.waMove.cutScreen(text);
    }

    private void pasteMessage() {
        if (f == null) return;
        WaClip.Read clip = WaClip.read(a);
        String text = clip.text;
        if (text == null || text.trim().isEmpty()) {
            new AlertDialog.Builder(a).setTitle("الحافظة فارغة")
                    .setMessage("انسخ رسالة الحوالة من واتساب أولًا: اضغط عليها مطولًا ثم زر «نسخ». لعدة رسائل حدّدها كلها ثم «نسخ».")
                    .setPositiveButton("الخطوات بالتفصيل", (d, w) -> a.homeScreen.waHelp()).setNegativeButton("حسنًا", null).show();
            return;
        }
        final List<WaText.Msg> msgs = WaClip.messages(text, clip.html);
        if (msgs.size() > 1 && editing == null && !f.queued) {
            final String all = text.trim();
            offerSplit(msgs, null, null);
            splitAll = all;
            return;
        }
        // 12.1: نص ملزوق بلا علامات: «قص» يدوي بدل حركة واحدة فيها عدة حوالات
        if (msgs.size() == 1 && editing == null && !f.queued && a.can(Auth.Perm.TX_WA) && WaClip.maybeGlued(msgs.get(0).body)) {
            final String one = msgs.get(0).body;
            new AlertDialog.Builder(a).setTitle("هل هذا النص أكثر من رسالة؟")
                    .setMessage("يبدو أن النص فيه أكثر من رسالة ملزوقة. اقطعه عند حدود الرسائل فتُسجَّل كل واحدة وحدها، أو أبقه هنا كما هو.")
                    .setPositiveButton("✂ قص الرسائل", (d, w) -> {
                        a.beforeScreenChange();
                        a.waMove.cutScreen(one);
                    })
                    .setNeutralButton("إبقاؤه هنا", (d, w) -> putText(one))
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        putText(msgs.size() == 1 ? msgs.get(0).body : text.trim());
    }

    /** 11.3: عرض واحد للصق عدة رسائل (من الزر أو من اللصق في الخانة نفسها). */
    private boolean splitOffered;
    private String splitAll;

    private void offerSplit(final List<WaText.Msg> msgs, final String pastedInField, final Runnable removePasted) {
        if (!a.can(Auth.Perm.TX_WA)) return;   // 12.0
        new AlertDialog.Builder(a).setTitle("في النص " + msgs.size() + " رسائل من واتساب")
                .setMessage("الأفضل أن تُسجَّل كل رسالة حركةً مستقلة: تظهر قائمة الرسائل، ثم نموذج لكل رسالة بالتتابع.")
                .setPositiveButton("تسجيل كل رسالة وحدها", (d, w) -> {
                    splitOffered = false;
                    if (removePasted != null) removePasted.run();
                    a.waQueue.overview(msgs, pasteOffice());
                })
                .setNeutralButton("إبقاء النص كله هنا", (d, w) -> {
                    splitOffered = false;
                    if (pastedInField == null && splitAll != null) putText(splitAll);
                })
                .setNegativeButton("إلغاء", (d, w) -> {
                    splitOffered = false;
                    if (removePasted != null) removePasted.run();
                })
                .setOnCancelListener(d -> splitOffered = false)
                .show();
    }

    private void putText(String text) {
        if (f == null) return;
        f.desc.setText(text);
        f.desc.removeCallbacks(f.parseLater);
        f.pasted = true;
        previewParse();
    }

    private List<MessageParser.Cur> parserCurrencies() {
        List<MessageParser.Cur> out = new ArrayList<>();
        if (f != null && f.office != null && !f.curs.isEmpty()) {
            for (String c : f.curs) out.add(new MessageParser.Cur(c, f.names.get(c), a.db.decimals(c)));
        } else {
            for (Model.Currency c : a.db.currencies(false)) out.add(new MessageParser.Cur(c.code, c.name, c.decimals));
        }
        return out;
    }

    /**
     * يستخرج المعلومات من البيان ويعرضها فقط (أخضر/أحمر). لا يكتب شيئًا في الحقول:
     * الكتابة تتم عند ضغط «إضافة المعلومات للحقول».
     */
    private void previewParse() {
        final Form form = f;
        if (form == null || form.parseBox == null || form.desc == null) return;
        String text = form.desc.getText().toString();
        if (!text.equals(form.parsedText)) form.filled = false;
        form.parsedText = text;
        if (text.trim().isEmpty()) {
            form.parsed = null;
            form.parseBox.removeAllViews();
            form.parseBox.setVisibility(View.GONE);
            return;
        }
        form.duplicate = duplicateOf(text);
        // 9.0: المجانية: استخراج المعلومات لـ 20 رسالة يوميًا، ثم الإدخال اليدوي العادي
        if (!a.db.waAllowed(text)) {
            form.parsed = null;
            renderLimit(form);
            return;
        }
        if (form.byAi && form.aiRaw != null && text.equals(form.aiText)) {
            form.parsed = forOffice(form.aiRaw);
            renderParse(form);
            return;
        }
        form.byAi = false;
        form.parsed = MessageParser.parse(text, parserCurrencies(), CurrencyWords.load(a.db));
        renderParse(form);
        // تلقائيًا (إن فعّله المدير): بعد لصق رسالة لم يستخرج المحرك المدمج كل معلوماتها
        if (form.pasted) {
            form.pasted = false;
            MessageParser.Result r = form.parsed;
            boolean incomplete = r.person.isEmpty() || r.amount.isEmpty() || r.currency == null;
            if (incomplete && AiSettings.auto(a.db) && !text.equals(form.aiAsked) && AiSettings.config(a.db).problem() == null) askAi(form);
        }
    }

    /** يرسل البيان إلى خدمة الذكاء الاصطناعي ويعرض فهمها في البطاقة نفسها (لا يكتب في الحقول). */
    private void askAi(final Form form) {
        final String text = form.desc.getText().toString();
        if (text.trim().isEmpty()) return;
        final AiExtract.Config cfg = AiSettings.config(a.db);
        String p = cfg.problem();
        if (p != null) {
            a.toast(p);
            return;
        }
        final List<MessageParser.Cur> all = new ArrayList<>();
        for (Model.Currency c : a.db.currencies(false)) all.add(new MessageParser.Cur(c.code, c.name, c.decimals));
        final Map<String, List<String>> al = CurrencyWords.load(a.db);
        form.aiAsked = text;
        a.busy("جارٍ القراءة الذكية السحابية…", pr -> AiExtract.call(cfg, text, all, al), res -> {
            if (f != form || !text.equals(form.desc.getText().toString())) return;
            form.aiRaw = (MessageParser.Result) res;
            form.aiText = text;
            form.byAi = true;
            form.filled = false;
            form.parsed = forOffice(form.aiRaw);
            renderParse(form);
        });
    }

    /** نتيجة الذكاء الاصطناعي بعملات المكتب المختار: عملة غير مفعّلة له تظهر تنبيهًا بدل اختيارها. */
    private MessageParser.Result forOffice(MessageParser.Result ai) {
        MessageParser.Result r = new MessageParser.Result();
        r.person = ai.person;
        r.amount = ai.amount;
        r.phone = ai.phone;
        r.city = ai.city;
        r.sender = ai.sender;
        r.fee = ai.fee;
        r.net = ai.net;
        List<String> ok = new ArrayList<>();
        for (MessageParser.Cur c : parserCurrencies()) ok.add(c.code);
        String code = ai.currency != null ? ai.currency : ai.currencyWord.toUpperCase(java.util.Locale.ROOT);
        if (ok.contains(code)) {
            r.currency = code;
            r.currencyWord = ai.currencyWord;
        } else {
            r.currency = null;
            r.currencyWord = ai.currency != null ? ai.currency : ai.currencyWord;
        }
        return r;
    }

    /** 10.13: ما استُخرج رقائق صغيرة في سطر واحد؛ وما يحتاج تدخلك سطر أحمر قصير. */
    private Ui.Flow okFlow;

    private void parseLine(LinearLayout box, boolean ok, String text) {
        if (ok) {
            if (okFlow == null || okFlow.getParent() != box) {
                okFlow = a.flow();
                box.addView(okFlow, a.lp(-1, -2, 2));
            }
            TextView c = a.chip("✓ " + text, a.green, a.creditBg);
            c.setTextSize(13);
            okFlow.addView(c);
            return;
        }
        TextView t = a.text("✗ " + text, 13, a.red, true);
        t.setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(2));
        box.addView(t, a.lp(-1, -2, 1));
    }

    private static String shownAmount(String plain) {
        try {
            java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0.##########", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
            return df.format(new java.math.BigDecimal(plain));
        } catch (Exception e) {
            return plain;
        }
    }

    private void renderParse(final Form form) {
        final MessageParser.Result r = form.parsed;
        LinearLayout box = form.parseBox;
        box.removeAllViews();
        if (r == null) {
            box.setVisibility(View.GONE);
            return;
        }
        okFlow = null;
        box.addView(a.text(form.byAi ? "☁️ قُرئ بالقراءة الذكية السحابية" : "استُخرج من الرسالة (بالمحرك المدمج، دون إنترنت)", 13, form.byAi ? a.accent : a.muted, true));
        long[] wu = a.db.waUsage();
        if (wu[1] != Entitlements.UNLIMITED) box.addView(a.text("النسخة المجانية: استُخدم " + wu[0] + " من " + wu[1] + " رسالة اليوم (تُحتسب الرسالة عند إضافتها للحقول)", 12, a.muted, false));
        final List<WaText.Msg> many = editing == null && !form.queued ? WaText.split(form.desc.getText().toString()) : new ArrayList<WaText.Msg>();
        if (many.size() > 1) {
            parseLine(box, false, "هذا النص فيه " + many.size() + " رسائل من واتساب — الأفضل تسجيل كل رسالة حركةً مستقلة");
            box.addView(a.smallButton("تسجيل كل رسالة وحدها (" + many.size() + ")", a.primary, v -> {
                form.desc.setText("");
                a.waQueue.overview(many, pasteOffice());
            }), a.lp(-1, 44, 2));
        }
        if (form.duplicate != null) parseLine(box, false, "⚠ هذه الرسالة مسجّلة سابقًا: " + duplicateText(form.duplicate) + " — تأكد قبل التسجيل حتى لا تُسجَّل مرتين");
        parseLine(box, !r.person.isEmpty(), r.person.isEmpty() ? "المستفيد: لم يُستخرج — اكتبه بيدك" : r.person);
        boolean metal = r.currency != null && Metals.isMetal(r.currency);   // 12.6: المعدن بوزنه
        parseLine(box, !r.amount.isEmpty(), r.amount.isEmpty() ? (metal ? "الوزن" : "المبلغ") + ": لم يُستخرج — اكتبه بيدك"
                : (metal ? "الوزن: " + shownAmount(r.amount) + " " + Metals.unitText(r.currency) : shownAmount(r.amount))
                + (r.amountText.isEmpty() || r.amountText.equals(r.amount) ? "" : " («" + r.amountText + "»)"));
        // 10.5: تفسير يحتاج مراجعة («50000 الف»، أو رقم ملتبس بعملة بثلاث منازل): يظهر صراحة قبل الإضافة للحقول
        if (!r.amountNote.isEmpty()) parseLine(box, false, "راجع المبلغ: " + r.amountNote);
        if (r.currency != null) {
            String nm = form.names.get(r.currency);
            parseLine(box, true, r.currency + (nm == null || nm.isEmpty() ? "" : " " + nm));
        } else if (!r.currencyWord.isEmpty()) {
            parseLine(box, false, "العملة «" + r.currencyWord + "» غير مفعّلة لهذا المكتب — اخترها بيدك أو فعّلها للمكتب");
        } else if (!r.unknownWord.isEmpty() && !form.byAi) {
            parseLine(box, false, "العملة: لم أتعرّف على «" + r.unknownWord + "»");
            final String w = r.unknownWord;
            box.addView(a.smallButton("اربط «" + w + "» بعملة ليفهمها البرنامج دائمًا", a.accent, v -> {
                List<String> codes = new ArrayList<>();
                for (Model.Currency c : a.db.currencies(false)) codes.add(c.code);
                CurrencyWords.linkDialog(a, w, codes, this::previewParse);
            }), a.lp(-1, 44, 2));
        } else {
            parseLine(box, false, "العملة: لم تُذكر في الرسالة — اخترها بيدك");
        }
        if (form.office == null) parseLine(box, false, "اختر المكتب من الأعلى");
        if (r.net) parseLine(box, true, "صافي (بدون خصم أجرة)");
        if (!r.phone.isEmpty()) parseLine(box, true, "\u2066" + r.phone + "\u2069");
        if (!r.city.isEmpty()) parseLine(box, true, r.city);
        if (!r.sender.isEmpty()) parseLine(box, true, "المرسل في نص الحوالة: " + r.sender);
        if (!r.rajeRate.isEmpty()) parseLine(box, true, "راجع (أجرة لنا): " + r.rajeRate + (r.rajePer == Model.PER_MILLE ? " بالألف" : "٪") + " من المبلغ");
        else if (!r.rajeAmount.isEmpty()) parseLine(box, true, "راجع (أجرة لنا): " + r.rajeAmount);
        else if (r.raje) parseLine(box, false, "ذُكر «راجع»" + (r.rajeHint.isEmpty() ? "" : " " + r.rajeHint) + " دون «بالمية/بالألف» أو عملة — حدّد الأجرة لنا بيدك من «مع أجرة»");
        if (!r.fee.isEmpty()) parseLine(box, true, "ذُكرت أجرة: " + r.fee + " — لا تُضاف تلقائيًا، أضفها من «مع أجرة» إن لزم");
        if (AiSettings.on(a.db)) {
            if (!form.byAi) {
                box.addView(a.smallButton(AiExtract.BUTTON, a.accent, v -> askAi(form)), a.lp(-1, 44, 4));
                box.addView(a.text(AiExtract.EXPLAIN, 12, a.muted, false));
            } else {
                box.addView(a.smallButton("↩ العودة لنتيجة المحرك المدمج", Color.rgb(120, 120, 120), v -> {
                    form.byAi = false;
                    form.aiText = "";
                    form.filled = false;
                    previewParse();
                }), a.lp(-1, 40, 4));
            }
        }
        if (r.any()) {
            Button fill = a.makeButton(form.filled ? "✓ أُضيفت — إعادة الإضافة" : "⬇ أضف للحقول", form.filled ? a.green : a.primary, v -> fillFromParse());
            fill.setTextSize(14);
            fill.setContentDescription(form.filled ? "أُضيفت للحقول — اضغط لإعادة الإضافة" : "إضافة المعلومات للحقول");
            box.addView(fill, a.lp(-1, 42, 4));
        } else {
            box.addView(a.text("لم أجد في الرسالة معلومات يمكن إضافتها؛ اكتب الحقول بيدك.", 13, a.red, true));
        }
        box.setVisibility(View.VISIBLE);
    }

    /** 9.0: بطاقة «انتهى استخراج اليوم» مكان المعلومات المستخرجة: النموذج يعمل بالإدخال اليدوي العادي. */
    private void renderLimit(final Form form) {
        LinearLayout box = form.parseBox;
        box.removeAllViews();
        long[] u = a.db.waUsage();
        box.addView(a.text("الإدخال اليدوي", 15, a.dark, true));
        box.addView(a.text(Entitlements.waLimitMessage(u[1]), 13, a.muted, false));
        box.addView(a.text("اكتب المستفيد والمبلغ والعملة في الحقول أدناه؛ البيان يُحفظ كما لصقته.", 13, a.ink, false));
        box.addView(a.smallButton("النسخة الكاملة", a.accent, v -> a.licenseScreen.show()), a.lp(-1, 40, 4));
        box.setVisibility(View.VISIBLE);
    }

    /** يكتب المعلومات المستخرجة في الحقول (فوق ما فيها) بعد أن يطلب المحاسب ذلك. */
    private void fillFromParse() {
        final Form form = f;
        if (form == null || form.parsed == null) return;
        if (!a.db.waUse(form.desc.getText().toString())) {
            form.parsed = null;
            renderLimit(form);
            return;
        }
        MessageParser.Result r = form.parsed;
        List<String> done = new ArrayList<>();
        if (!r.person.isEmpty()) {
            form.person.setText(r.person);
            done.add("المستفيد");
        }
        if (!r.amount.isEmpty()) {
            form.amount.setText(r.amount);
            done.add("المبلغ");
        }
        if (!r.city.isEmpty() && form.dest.getText().toString().trim().isEmpty()) {
            form.dest.setText(r.city);
            done.add("الوجهة");
        }
        if (r.currency != null && form.cur != null) {
            int i = form.curs.indexOf(r.currency);
            if (i >= 0) {
                form.cur.set(i, true);
                form.cur.flagMissing(false);
                done.add("العملة");
            }
        }
        if (!r.rajeRate.isEmpty() || !r.rajeAmount.isEmpty()) {
            // «راجع» = أجرة يدفعها المكتب لنا: «مع أجرة» + «الأجرة لنا» + النسبة أو المبلغ
            form.feeMode.set(1, true);
            form.feeSide.set(0, false);
            if (!r.rajeRate.isEmpty()) {
                form.feeKind.set(1, false);
                form.ratePer.set(r.rajePer == Model.PER_MILLE ? 1 : 0, false);
                form.rate.setText(r.rajeRate);
                applyFeeKind(form);
            } else {
                form.feeKind.set(0, false);
                applyFeeKind(form);
                if (form.feeCur != null && form.cur != null && !form.cur.none()) form.feeCur.set(form.cur.selected, false);
                form.fee.setText(r.rajeAmount);
            }
            done.add("الراجع (أجرة لنا)");
        }
        form.filled = true;
        renderParse(form);
        if (form.type.none()) form.type.flagMissing(true);
        updateSummary();
        if (done.isEmpty()) a.toast("لا توجد معلومات يمكن إضافتها" + (form.office == null ? "؛ اختر المكتب أولًا." : "."));
        else a.toast("أُضيف: " + Model.join(done, "، ") + ". الآن اختر «لنا» أو «علينا» وراجع الملخص.");
    }

    private void setWhen(long when) {
        if (f == null) return;
        f.when = when;
        boolean today = when == 0 || Model.day(when).equals(Model.day(Clock.now()));
        f.dateBtn.setText("التاريخ: " + (today && editing == null ? "اليوم " : "") + Model.day(when == 0 ? Clock.now() : when) + "   (اضغط للتغيير)");
        updateSummary();
    }

    private void pickDate() {
        final Calendar c = Model.cal();
        if (f.when > 0) c.setTimeInMillis(f.when);
        DatePickerDialog dp = new DatePickerDialog(a, (v, y, m, d) -> {
            Calendar now = Model.cal();
            Calendar x = Model.cal();
            x.set(y, m, d, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND));
            boolean isToday = Model.day(x.getTimeInMillis()).equals(Model.day(Clock.now()));
            setWhen(isToday && editing == null ? 0 : Math.min(x.getTimeInMillis(), Clock.now()));
            long lock = a.db.settingLong("lock_before", 0);
            if (lock > 0 && x.getTimeInMillis() < lock) a.toast("تنبيه: هذا التاريخ داخل فترة مقفلة ولن يُقبل.");
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMaxDate(Clock.now());
        dp.show();
    }

    // ===================================================================================
    // الملخص المباشر
    // ===================================================================================

    /** 10.9: يفتح «تفاصيل إضافية» أو يطويها، ويكتب في العنوان ما فيها مما مُلئ. */
    private void showMore(Form form, boolean open) {
        if (form == null || form.more == null) return;
        if (open != (form.more.getVisibility() == View.VISIBLE)) {
            form.more.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open && !Ui.NO_MOTION) {
                form.more.setAlpha(0f);
                form.more.setTranslationY(-a.dp(8));
                form.more.animate().alpha(1f).translationY(0).setDuration(420).start();
            }
        }
        List<String> used = new ArrayList<>();
        if (!form.notes.getText().toString().trim().isEmpty()) used.add("ملاحظات");
        if (!form.service.getText().toString().trim().isEmpty()) used.add("الخدمة");
        if (!form.dest.getText().toString().trim().isEmpty()) used.add("الوجهة");
        if (form.when != 0 && !Model.day(form.when).equals(Model.day(Clock.now()))) used.add("التاريخ");
        if (form.imgMode != null && form.imgMode.selected == 1) used.add("الصور");
        form.moreToggle.setText((open ? "▴  " : "▾  ") + "تفاصيل إضافية" + (used.isEmpty() ? ": ملاحظات، الخدمة، الوجهة، التاريخ، الصور" : " — " + Model.join(used, "، ")));
    }

    /**
     * 12.6: المعدن يُسجَّل بالوزن: «الوزن (غرام ذهب عيار 21)» بدل «المبلغ»، والأجرة يختار المحاسب كل مرة
     * بالوزن من نفس المعدن أو بعملة (من أزرار عملة الأجرة).
     */
    private void metalLabels(Form form) {
        if (form == null) return;
        String cur = form.cur == null ? null : form.cur.label();
        Metals.Metal m = Metals.of(cur);
        if (form.amountLabel != null) form.amountLabel.setText(m == null ? "المبلغ" : "الوزن (" + m.u().name + " — " + Metals.unitText(cur) + ")");
        if (form.amount != null) form.amount.setHint(m == null ? "0" : "0 " + m.u().shortName);
        String fc = form.feeCur == null ? null : form.feeCur.label();
        if (form.feeCurLabel != null) form.feeCurLabel.setText(m == null && !Metals.isMetal(fc) ? "عملة الأجرة (تتبع عملة المبلغ تلقائيًا)"
                : "الأجرة: بالوزن من نفس المعدن أو بعملة — اختر هنا");
        if (form.fee != null) form.fee.setHint(Metals.isMetal(fc) ? "وزن الأجرة (" + Metals.unitText(fc) + ")" : "مبلغ الأجرة");
    }

    private void updateSummary() {
        if (f == null || f.summary == null) return;
        tintTxCard();
        metalLabels(f);
        // ما مُلئ من التفاصيل الإضافية (مسودة، تعديل، رسالة) يبقى ظاهرًا
        if (f.more != null && f.more.getVisibility() != View.VISIBLE && (!f.notes.getText().toString().trim().isEmpty()
                || !f.service.getText().toString().trim().isEmpty() || !f.dest.getText().toString().trim().isEmpty()
                || (f.when != 0 && !Model.day(f.when).equals(Model.day(Clock.now()))) || (f.imgMode != null && f.imgMode.selected == 1))) showMore(f, true);
        String[] lines = summaryText().split("\n");
        List<String> missing = new ArrayList<>();
        LinearLayout box = f.summary;
        box.removeAllViews();
        TextView head = a.text(lines[0].replace("⚠ ", ""), 14, a.red, true);
        head.setPadding(a.dp(2), 0, a.dp(2), a.dp(4));
        box.addView(head);
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            boolean bad = l.startsWith("✗");
            if (bad) missing.add(l.substring(1).trim().replaceFirst(":.*$", ""));
            String body = bad ? l.substring(1).trim() : l;
            int c = body.indexOf(": ");
            String label = c > 0 ? body.substring(0, c) : body;
            String value = c > 0 ? body.substring(c + 2) : "";
            LinearLayout r = a.row();
            r.setPadding(0, a.dp(1), 0, a.dp(1));
            boolean wa = !bad && label.equals(WA_SENDER);
            if (wa) r.setBackground(a.round(a.goldSoft, a.goldSoft, 8));
            TextView lt = a.text((bad ? "✗ " : "") + label, 13, bad ? a.red : wa ? a.goldInk : a.muted, bad);
            lt.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
            r.addView(lt, new LinearLayout.LayoutParams(a.dp(92), -2));
            TextView vt = a.text(value, 14, bad ? a.red : wa ? a.goldInk : a.ink, true);
            vt.setPadding(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
            r.addView(vt, new LinearLayout.LayoutParams(0, -2, 1));
            box.addView(r);
        }
        if (f.footerLine != null) {
            if (!missing.isEmpty()) {
                f.footerLine.setText("ناقص: " + Model.join(missing, "، "));
                f.footerLine.setTextColor(a.red);
            } else {
                f.footerLine.setText("✓ " + shortLine());
                f.footerLine.setTextColor(a.readable(f.type.selected == 0 ? a.green : a.red));
            }
        }
    }

    /** سطر واحد لما سيُسجَّل: المكتب • لنا/علينا المبلغ العملة • المستفيد. */
    private String shortLine() {
        final Form x = f;
        String cur = x.cur == null ? "" : Model.nz(x.cur.label());
        String amt;
        try {
            int d = a.db.decimals(cur);
            amt = Model.fmt(Db.minor(x.amount.getText().toString(), d), d);
        } catch (Exception e) {
            amt = x.amount.getText().toString().trim();
        }
        String p = x.person.getText().toString().trim();
        return (x.office == null ? "" : x.office.name + " • ") + (x.type.selected == 0 ? "لنا " : "علينا ") + "\u2066" + amt + " " + cur + "\u2069"
                + (p.isEmpty() ? "" : " • " + p) + (x.feeMode.selected == 1 ? " • مع أجرة" : "");
    }

    /** ما سيُسجَّل، سطرًا لكل معلومة، مع ✗ أمام الناقص. يُعرض فوق زر التسجيل وفي نافذة التأكيد. */
    private static final String WA_SENDER = "مرسل الرسالة";

    private String summaryText() {
        final Form x = f;
        List<String> l = new ArrayList<>();
        l.add(editing == null ? "⚠ راجع قبل التسجيل:" : "⚠ راجع قبل حفظ التعديل:");
        if (!x.waSender.isEmpty()) l.add(WA_SENDER + ": " + x.waSender);
        l.add(x.office == null ? "✗ المكتب: لم يُختر" : "المكتب: " + x.office.name);
        l.add(x.type.none() ? "✗ النوع: لم تختر «لنا» أو «علينا»"
                : x.type.selected == 0 ? "النوع: لنا — المكتب مدين لنا" : "النوع: علينا — نحن مدينون للمكتب");
        String cur = x.cur == null ? null : x.cur.label();
        String amt = x.amount.getText().toString().trim();
        String curName = cur == null ? null : x.names.get(cur);
        String curText = cur == null ? "" : Metals.isMetal(cur) ? " " + Metals.unitText(cur) : " " + cur + (curName == null || curName.isEmpty() ? "" : " (" + curName + ")");
        String qw = cur == null ? "المبلغ" : Metals.qtyWord(cur);   // 12.6: «الوزن» للمعدن
        if (amt.isEmpty()) l.add("✗ " + qw + ": لم يُكتب" + (cur == null ? "" : " —" + curText));
        else {
            String shown;
            try {
                int d = cur == null ? 10 : a.db.decimals(cur);
                shown = cur == null ? shownAmount(new java.math.BigDecimal(MessageParser.digits(amt).replace(",", "")).toPlainString())
                        : Model.fmt(Db.minor(amt, d), d);
            } catch (Exception e) {
                shown = null;
            }
            if (shown == null) l.add("✗ " + qw + " غير صحيح: اكتب أرقامًا فقط (مثال 1500 أو 1500.50)");
            else l.add(qw + ": " + shown + curText);
        }
        if (x.office != null && x.cur == null) l.add("✗ العملة: لا توجد عملات مفعّلة لهذا المكتب");
        else if (cur == null) l.add("✗ العملة: لم تُختر");
        String p = x.person.getText().toString().trim();
        l.add("المستفيد: " + (p.isEmpty() ? "—" : p));
        if (x.feeMode.selected == 1 && x.feeKind != null && x.feeKind.selected == 1) {
            String side = x.feeSide.selected == 0 ? "لنا" : "علينا";
            java.math.BigDecimal r = Model.parseRate(x.rate.getText().toString());
            Long fm = rateFeeMinor(x);
            if (r == null) l.add("✗ الأجرة (" + side + "): اكتب النسبة");
            else if (fm == null || fm <= 0) l.add("✗ الأجرة (" + side + " " + Model.rateLabel(r, ratePer(x)) + "): تُحسب بعد كتابة المبلغ واختيار العملة");
            else {
                int d = a.db.decimals(cur);
                l.add("الأجرة: " + side + " " + Metals.show(Model.fmt(fm, d), cur) + " (" + Model.rateLabel(r, ratePer(x)) + " من " + (Metals.isMetal(cur) ? "الوزن" : "المبلغ") + ")");
            }
        } else if (x.feeMode.selected == 1) {
            String fc = x.feeCur == null ? null : x.feeCur.label();
            String fe = x.fee.getText().toString().trim();
            String side = x.feeSide.selected == 0 ? "لنا" : "علينا";
            if (fc == null) l.add("✗ الأجرة: اختر عملة الأجرة");
            else if (fe.isEmpty()) l.add("✗ الأجرة (" + side + "): اكتب المبلغ");
            else {
                try {
                    l.add("الأجرة: " + side + " " + Metals.show(Model.fmt(Db.minor(fe, a.db.decimals(fc)), a.db.decimals(fc)), fc));
                } catch (Exception e) {
                    l.add("✗ مبلغ الأجرة غير صحيح");
                }
            }
        } else l.add("الأجرة: بدون");
        long when = x.when == 0 ? Clock.now() : x.when;
        boolean today = Model.day(when).equals(Model.day(Clock.now()));
        l.add("التاريخ: " + (today ? "اليوم " : "") + Model.day(when));
        int imgs = a.pendingImages.size() + existingAtt.size() - removedAtt.size();
        if (imgs > 0) l.add("الصور: " + imgs);
        return Model.join(l, "\n");
    }

    // ===================================================================================
    // الصور
    // ===================================================================================

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        a.startForResult(i, MainActivity.PICK_IMAGES);
    }

    private void takePhoto() {
        File file = new File(a.sharedDir(), "camera_" + Clock.now() + ".jpg");
        a.cameraFile = file.getName();
        Uri u = EvidenceProvider.uriFor(a, file);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, u);
        i.setClipData(ClipData.newRawUri(file.getName(), u));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startForResult(i, MainActivity.TAKE_PHOTO);
    }

    @SuppressWarnings("unchecked")
    void onImageResult(int r, int c, Intent data) throws Exception {
        if (c != android.app.Activity.RESULT_OK) {
            if (r == MainActivity.TAKE_PHOTO && a.cameraFile != null) new File(a.sharedDir(), a.cameraFile).delete();
            return;
        }
        if (r == MainActivity.PICK_IMAGES) {
            if (data == null) return;
            final List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            a.busy("جارٍ تجهيز الصور…", p -> {
                List<byte[]> out = new ArrayList<>();
                for (Uri u : uris) {
                    try (InputStream in = a.getContentResolver().openInputStream(u)) {
                        if (in == null) continue;
                        byte[] raw = Db.readAll(in);
                        if (!ImageUtil.isImage(raw)) continue;
                        out.add(ImageUtil.shrink(raw));
                    }
                }
                return out;
            }, res -> {
                List<byte[]> l = (List<byte[]>) res;
                if (l.size() < uris.size()) a.toast("تم تجاهل " + (uris.size() - l.size()) + " ملف ليس صورة صالحة");
                a.pendingImages.addAll(l);
                imagesChanged();
            });
        } else {
            if (a.cameraFile == null) throw new Exception("تعذر العثور على الصورة الملتقطة. أعد المحاولة.");
            final File file = new File(a.sharedDir(), a.cameraFile);
            a.cameraFile = null;
            a.busy("جارٍ تجهيز الصورة…", p -> {
                if (!file.exists() || file.length() == 0) throw new Exception("تطبيق الكاميرا لم يحفظ الصورة. أعد المحاولة.");
                byte[] raw = Db.readAll(file);
                file.delete();
                return ImageUtil.shrink(raw);
            }, res -> {
                a.pendingImages.add((byte[]) res);
                imagesChanged();
            });
        }
    }

    private void imagesChanged() {
        if (f == null) {
            a.toast("تمت إضافة الصورة. افتح «تسجيل حركة» للمتابعة.");
            return;
        }
        if (f.imgMode.selected != 1) f.imgMode.set(1, true);
        refreshThumbs();
        updateSummary();
    }

    private void refreshThumbs() {
        if (f == null) return;
        f.thumbs.removeAllViews();
        for (final Long id : existingAtt) {
            if (removedAtt.contains(id)) continue;
            byte[] raw = a.db.attachment(id);
            addThumb(ImageUtil.decodeSampled(raw, 240), () -> {
                removedAtt.add(id);
                refreshThumbs();
                updateSummary();
            });
        }
        for (int i = 0; i < a.pendingImages.size(); i++) {
            final int idx = i;
            addThumb(ImageUtil.decodeSampled(a.pendingImages.get(i), 240), () -> {
                if (idx < a.pendingImages.size()) a.pendingImages.remove(idx);
                refreshThumbs();
                updateSummary();
            });
        }
        if (f.thumbs.getChildCount() == 0) f.thumbs.addView(a.text("لا توجد صور بعد", 13, a.muted, false));
    }

    private void addThumb(Bitmap bm, final Runnable remove) {
        FrameLayout box = new FrameLayout(a);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (bm != null) iv.setImageBitmap(bm);
        else iv.setBackgroundColor(Color.LTGRAY);
        box.addView(iv, new FrameLayout.LayoutParams(a.dp(84), a.dp(84)));
        TextView x = a.text("✕", 15, Color.WHITE, true);
        x.setGravity(Gravity.CENTER);
        x.setBackground(a.round(Color.argb(200, 170, 40, 40), Color.TRANSPARENT, 12));
        FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(a.dp(28), a.dp(28));
        xp.gravity = Gravity.TOP | Gravity.START;
        box.addView(x, xp);
        x.setOnClickListener(v -> remove.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(a.dp(84), a.dp(84));
        p.setMargins(a.dp(4), a.dp(6), a.dp(4), a.dp(6));
        f.thumbs.addView(box, p);
    }

    // ===================================================================================
    // الحفظ
    // ===================================================================================

    private Db.TxInput collect() throws Exception {
        if (f.office == null) throw new Exception("اختر المكتب");
        if (f.type.none()) {
            f.type.flagMissing(true);
            throw new Exception("اختر نوع الحركة: «لنا» أو «علينا». لا يُختار النوع تلقائيًا حتى لا تُسجَّل حركة بالاتجاه الخطأ.");
        }
        if (f.cur == null) throw new Exception("هذا المكتب ليس له عملات. افتح «إضافة / تعديل مكتب» وفعّل عملة واحدة على الأقل.");
        if (f.cur.none()) {
            f.cur.flagMissing(true);
            throw new Exception("اختر عملة الحركة. لا تُحدَّد العملة تلقائيًا حتى لا تُسجَّل حركة بعملة خاطئة.");
        }
        Db.TxInput in = new Db.TxInput();
        in.office = f.office.id;
        in.type = f.type.selected == 0 ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        in.currency = f.cur.label();
        if (f.amount.getText().toString().trim().isEmpty()) throw new Exception("اكتب المبلغ");
        in.amount = Db.minor(f.amount.getText().toString(), a.db.decimals(in.currency));
        if (in.amount <= 0) throw new Exception("المبلغ يجب أن يكون أكبر من صفر");
        in.person = f.person.getText().toString().trim();
        in.description = f.desc.getText().toString().trim();
        in.notes = f.notes.getText().toString().trim();
        in.service = f.service.getText().toString().trim();
        in.dest = f.dest.getText().toString().trim();
        in.when = f.when == 0 ? Clock.now() : f.when;
        if (f.feeMode.selected == 1 && f.feeKind != null && f.feeKind.selected == 1) {
            // نسبة من المبلغ: تُحسب هنا من المبلغ المسجَّل نفسه (لا من النص المعروض) وبعملته
            java.math.BigDecimal r = Model.parseRate(f.rate.getText().toString());
            int per = ratePer(f);
            if (r == null) throw new Exception("اكتب نسبة الأجرة (مثل 1 أو 1.5) أو اختر «مبلغ محدد» أو «بدون أجرة»");
            if (r.compareTo(java.math.BigDecimal.valueOf(per)) > 0) throw new Exception("نسبة الأجرة أكبر من المبلغ نفسه");
            in.feeType = f.feeSide.selected == 0 ? Model.FEE_US : Model.FEE_THEM;
            in.feeCurrency = in.currency;
            in.fee = Model.feeFromRate(in.amount, r, per);
            if (in.fee <= 0) throw new Exception("الأجرة المحسوبة أصغر من أصغر وحدة في " + in.currency + ". اكتبها كمبلغ محدد أو اختر «بدون أجرة».");
        } else if (f.feeMode.selected == 1) {
            if (f.feeCur == null || f.feeCur.none()) {
                if (f.feeCur != null) f.feeCur.flagMissing(true);
                throw new Exception("اختر عملة الأجرة");
            }
            in.feeType = f.feeSide.selected == 0 ? Model.FEE_US : Model.FEE_THEM;
            in.feeCurrency = f.feeCur.label();
            if (f.fee.getText().toString().trim().isEmpty()) throw new Exception("اكتب مبلغ الأجرة أو اختر «بدون أجرة»");
            in.fee = Db.minor(f.fee.getText().toString(), a.db.decimals(in.feeCurrency));
            if (in.fee <= 0) throw new Exception("مبلغ الأجرة يجب أن يكون أكبر من صفر");
        } else in.feeType = Model.FEE_NONE;
        // 12.4: الحركة الخاصة
        if (f.specialOn) {
            if (f.specialType.isEmpty()) throw new Exception("اختر نوع الحركة الخاصة (ناقصة أجور، تحتاج مراجعة…) أو أطفئ «حركة خاصة»");
            if (f.specialBal == null || f.specialBal.none()) {
                if (f.specialBal != null) f.specialBal.flagMissing(true);
                throw new Exception("اختر: تُحسب الحركة الخاصة في الرصيد أو لا تُحسب");
            }
            in.special = f.specialType;
            in.specialBal = f.specialBal.selected == 0;
        }
        return in;
    }

    /** 10.5: يمنع الحفظ المزدوج (ضغطتان سريعتان). */
    private boolean saving;

    /**
     * 10.5 (§5): قبل حفظ رسالة من واتساب: جلسة جديدة تؤكد المكتب أولًا، وتغيير المكتب المثبّت يمر بالتحذير المطلوب.
     * ثم الحفظ العادي بتأكيده.
     */
    private void save() {
        if (f == null || f.office == null || !f.external || editing != null) {
            saveChecked();
            return;
        }
        final Model.Office chosen = f.office;
        WaPin.Need need = WaPin.before(a.db, chosen.id);
        if (need == WaPin.Need.NOTHING) {
            saveChecked();
            return;
        }
        if (need == WaPin.Need.CONFIRM_SESSION) {
            new AlertDialog.Builder(a).setTitle("تأكيد المكتب: «" + chosen.name + "»").setMessage(WaPin.sessionQuestion(chosen.name))
                    .setPositiveButton("نعم، ثبّته", (d, w) -> {
                        WaPin.pin(a.db, chosen.id);
                        a.waQueue.repin(chosen.id);
                        saveChecked();
                    }).setNegativeButton("سأختار مكتبًا آخر", null).show();
            return;
        }
        final Model.Office was = WaPin.current(a.db);
        new AlertDialog.Builder(a).setTitle("تغيير المكتب").setMessage(WaPin.changeWarning(was == null ? "" : was.name, chosen.name))
                .setPositiveButton("تغيير إلى «" + chosen.name + "»", (d, w) -> {
                    WaPin.pin(a.db, chosen.id);
                    a.waQueue.repin(chosen.id);
                    saveChecked();
                })
                .setNegativeButton("إبقاء «" + (was == null ? "" : was.name) + "»", (d, w) -> {
                    if (was != null) selectOffice(was);
                    updateSummary();
                }).show();
    }

    private void saveChecked() {
        final Db.TxInput in;
        try {
            in = collect();
        } catch (Exception e) {
            a.error(e);
            return;
        }
        final Model.Office office = f.office;
        final List<byte[]> images = new ArrayList<>(a.pendingImages);
        final Set<Long> removed = new HashSet<>(removedAtt);
        final Model.Tx old = editing;
        final boolean approve = approving && old != null;
        final boolean external = f.external, queued = f.queued;
        final boolean withFee = f.feeMode.selected == 1, byRate = feeByRate();
        final String rateText = f.rate == null ? "" : f.rate.getText().toString();
        final int ratePer = ratePer(f);
        final String holdUuid = old == null ? fromHold : null;
        final Runnable back = afterEdit;
        final boolean conv = converting;
        final Model.Tx dup = old == null && holdUuid == null ? duplicateOf(in.description) : null;
        final List<String> warns = old == null ? sanity(in) : new ArrayList<String>();
        final String warnText = warns.isEmpty() ? "" : "⚠ " + Model.join(warns, "\n⚠ ") + "\n\n";
        if (saving) return;
        Runnable doSave = () -> {
            if (saving) return;
            saving = true;
            a.busy(old == null ? "جارٍ تسجيل الحركة…" : "جارٍ حفظ التعديل…", p -> {
            try {
                if (old == null) return a.db.addTx(in, images, holdUuid);
                if (approve) {
                    a.db.approveEdited(old.id, in, images, removed);
                    return old.id;
                }
                return a.db.editTx(old.id, in, images, removed, "");
            } finally {
                saving = false;
            }
        }, res -> {
            a.pendingImages.clear();
            f = null;
            recent = null;
            if (old == null && !external) a.db.deleteSetting("draft_tx");
            a.db.setSettingQuiet("last_office", "" + office.id);
            if (external) WaPin.touch(a.db);
            if (withFee) rememberFee(byRate, rateText, ratePer);
            OfficeHub.touch(a.db, office.id);
            final String what = (Model.TYPE_DEBIT.equals(in.type) ? "لنا " : "علينا ") + "\u2066" + Model.fmt(in.amount, a.db.decimals(in.currency)) + " " + in.currency + "\u2069 — " + office.name;
            Model.Tx savedTx = res instanceof Long ? a.db.tx((Long) res) : null;
            final boolean pendingSaved = savedTx != null && savedTx.isPending();
            // 12.4: لا «تراجع» سريع للحركة المحوّلة من أمانة (فُكّت الأمانة معها) ولا للخاصة غير المحسوبة
            final boolean undoable = old == null && res instanceof Long && a.can(Auth.Perm.TX_CANCEL) && !pendingSaved && holdUuid == null
                    && (savedTx == null || !savedTx.isUnposted());
            fromHold = null;
            converting = false;
            if (pendingSaved) a.toast("أُرسلت الحركة لاعتماد المحاسب: لا أثر لها في الأرصدة حتى تُعتمد.");
            else if (holdUuid != null) a.toast("سُجّلت الحركة وفُكّت الأمانة ✓");
            else if (conv) a.toast("حُوّلت إلى حركة عادية ✓ (تنزل بالكشف والمطابقة)");
            else if (!Model.nz(in.special).isEmpty()) a.toast("سُجّلت حركة خاصة «" + in.special + "» " + (in.specialBal ? "(تُحسب في الرصيد)" : "(لا تُحسب في الرصيد)")
                    + ": تجدها في «الأمانات والخاصة» بالمكتب.");
            else if (approve) a.toast("صُحّحت الحركة واعتُمدت ✓");
            else if (!undoable) a.toast(old == null ? "تم تسجيل الحركة" : "تم حفظ التعديل");
            if (old == null) AutoBackup.runIfDueAsync(a);
            if (holdUuid != null && back != null) back.run();
            else if (old == null && queued) a.waQueue.savedCurrent(office.id);
            else if (old == null && external) a.showHome();
            else if (old == null) showNew();
            else if (afterEdit != null) afterEdit.run();
            else a.showHome();
            if (undoable) {
                final long newId = (Long) res;
                a.snack("✓ سُجّلت: " + what, "تراجع", () -> undo(newId, queued));
            }
            List<String> alerts = a.officesScreen.limitAlerts(a.db.office(office.id));
            if (!alerts.isEmpty()) {
                new AlertDialog.Builder(a).setTitle("تنبيه: تجاوز حد المكتب").setMessage(Model.join(alerts, "\n")).setPositiveButton("حسنًا", null).show();
            } else if (old == null) {
                long txLimit = a.db.settingLong("reminder_tx", 20);
                if (txLimit > 0 && a.txSinceBackup() == txLimit) {
                    new AlertDialog.Builder(a).setTitle("تذكير: النسخة الاحتياطية").setMessage("سجّلت " + txLimit + " حركة منذ آخر نسخة احتياطية.")
                            .setPositiveButton("إنشاء نسخة الآن", (d, w) -> a.backupScreen.backupDialog()).setNegativeButton("لاحقًا", null).show();
                }
            }
        });
        };
        // 10.5 (§11): التأكيد بالأرصدة من طبقة البيانات (المكتب بالأحمر، الرصيد الحالي وبعد الحركة أو بعد اعتمادها)، يُحدَّث وهو مفتوح
        final Long replacing = old == null ? null : old.id;
        java.util.function.Supplier<TxConfirm.View> view = () -> TxConfirm.build(a.db, in, replacing);
        if (dup != null) {
            // الرسالة نفسها سُجّلت من قبل: لا تُسجَّل مرتين إلا بقرار صريح
            a.confirmTx("⚠ هذه الرسالة مسجّلة سابقًا", "يوجد حركة بالبيان نفسه: " + duplicateText(dup) + ". هل هي حوالة جديدة فعلًا؟\n" + warnText, view, "نعم، سجّلها", doSave);
        } else if (!warns.isEmpty()) {
            // 8.0 (ت2): تنبيه قبل الحفظ حتى لو كانت نافذة التأكيد متوقفة
            a.confirmTx("راجع قبل التسجيل", warnText, view, "تسجيل رغم ذلك", doSave);
        } else if ("1".equals(a.db.setting("confirm_tx", "1")) || old != null) {
            a.confirmTx(old == null ? "تأكيد تسجيل الحركة" : approve ? "تأكيد التصحيح والاعتماد" : "تأكيد التعديل", "", view,
                    old == null ? "تسجيل" : approve ? "اعتماد" : "حفظ", doSave);
        } else doSave.run();
    }

    /** 8.0 (ت2): مبلغ غير معتاد، تكرار محتمل خلال 10 دقائق، تجاوز حد المكتب. لا يمنع الحفظ أبدًا. */
    private List<String> sanity(Db.TxInput in) {
        try {
            Sanity.Input s = new Sanity.Input();
            s.amount = in.amount;
            s.when = in.when;
            s.debit = Model.TYPE_DEBIT.equals(in.type);
            s.currency = in.currency;
            s.dec = a.db.decimals(in.currency);
            s.person = in.person;
            Long bal = a.db.balances(in.office).get(in.currency);
            s.balanceBefore = bal == null ? 0 : bal;
            Db.Limit lim = a.db.officeLimits(in.office).get(in.currency);
            if (lim != null) {
                s.receivableLimit = lim.receivable;
                s.payableLimit = lim.payable;
            }
            Db.TxFilter f = new Db.TxFilter();
            f.office = in.office;
            f.limit = 60;
            s.history = a.db.txLog(f);
            return Sanity.check(s);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** تراجع فوري بعد التسجيل: يُلغى القيد بقيد عكسي (لا يُحذف شيء)، ويبقى أثره في سجل العمليات. */
    private void undo(long txId, boolean queued) {
        try {
            Model.Tx t = a.db.tx(txId);
            if (t == null || !t.isActive()) return;
            a.db.cancelTx(txId, "تراجع فوري بعد التسجيل");
            recent = null;
            if (queued) a.waQueue.undone();
            a.toast("تم التراجع: أُلغيت الحركة بقيد عكسي وتبقى ظاهرة في السجل كملغاة.");
        } catch (Exception e) {
            a.error(e);
        }
    }

    /** حركة جديدة من صفحة المكتب: المكتب محدد مسبقًا. */
    void showForOffice(final Model.Office o) {
        if (!a.require(Auth.Perm.TX_ADD)) return;
        if (!o.active) {
            a.toast("هذا المكتب موقوف. فعّله أولًا من صفحته.");
            return;
        }
        String draft = a.db.secretSetting("draft_tx");
        long draftOffice = -1;
        if (!draft.isEmpty()) {
            try {
                draftOffice = new JSONObject(draft).optLong("office", -1);
            } catch (Exception ignored) {
                // تجاهل
            }
        }
        if (draft.isEmpty() || draftOffice == o.id) {
            showNew();
            if (f != null && (f.office == null || f.office.id != o.id)) selectOffice(o);
            chosenOffice = o;
            return;
        }
        new AlertDialog.Builder(a).setTitle("لديك حركة لم تُحفظ (مسودة)")
                .setMessage("توجد بيانات حركة لم تُسجَّل بعد لمكتب آخر. هل تريد متابعتها، أم البدء بحركة جديدة لـ «" + o.name + "» وحذف المسودة؟")
                .setPositiveButton("حركة جديدة لهذا المكتب", (d, w) -> {
                    a.db.deleteSetting("draft_tx");
                    showNew();
                    if (f != null) selectOffice(o);
                    chosenOffice = o;
                })
                .setNegativeButton("متابعة المسودة", (d, w) -> showNew()).show();
    }

    private String duplicateText(Model.Tx t) {
        Model.Office o = a.db.office(t.officeId);
        return Model.day(t.createdAt) + (o == null ? "" : "، " + o.name) + "، " + (Model.TYPE_DEBIT.equals(t.type) ? "لنا " : "علينا ")
                + Model.fmt(t.amount, a.db.decimals(t.currency)) + " " + t.currency + (t.person.isEmpty() ? "" : "، " + t.person);
    }

    // ===================================================================================
    // المسودة
    // ===================================================================================

    void saveDraft() {
        try {
            if (f == null || editing != null || f.external || a.db == null) return;
            boolean empty = f.amount.getText().toString().trim().isEmpty() && f.person.getText().toString().trim().isEmpty()
                    && f.desc.getText().toString().trim().isEmpty() && f.notes.getText().toString().trim().isEmpty()
                    && f.fee.getText().toString().trim().isEmpty();
            if (empty) {
                a.db.deleteSetting("draft_tx");
                return;
            }
            JSONObject o = new JSONObject();
            if (f.office != null) o.put("office", f.office.id);
            o.put("type", f.type.selected);
            o.put("amount", f.amount.getText().toString());
            if (f.cur != null) o.put("currency", f.cur.label());
            o.put("person", f.person.getText().toString());
            o.put("desc", f.desc.getText().toString());
            o.put("notes", f.notes.getText().toString());
            o.put("service", f.service.getText().toString());
            o.put("dest", f.dest.getText().toString());
            o.put("feeMode", f.feeMode.selected);
            o.put("feeSide", f.feeSide.selected);
            o.put("fee", f.fee.getText().toString());
            if (f.feeCur != null) o.put("feeCur", f.feeCur.label());
            o.put("feeCurTouched", f.feeCurTouched);
            o.put("feeKind", f.feeKind.selected);
            o.put("rate", f.rate.getText().toString());
            o.put("ratePer", f.ratePer.selected);
            o.put("when", f.when);
            a.db.setSecretSetting("draft_tx", o.toString());
        } catch (Throwable ignored) {
            // المسودة ليست حرجة
        }
    }
}
