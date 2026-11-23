package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * إعدادات «القراءة الذكية السحابية المدفوعة الخاصة» (12.7؛ كانت «الفهم بالذكاء الاصطناعي») — اختيارية ومطفأة افتراضيًا.
 * أي خدمة ذكاء اصطناعي حالية أو مستقبلية: Claude بصيغته، وغيرها بصيغة OpenAI (OpenAI وGemini قالبان جاهزان، و«مخصّص» لأي خدمة).
 * كل الإعدادات خاصة بهذا الهاتف ولا تُنقل مع النسخة الاحتياطية؛ المفتاح محفوظ مشفّرًا بمفتاح الهاتف.
 */
final class AiSettings {
    private AiSettings() {}

    /** 8.0: ميزة في النسخة الكاملة؛ المحرك المدمج لفهم الرسائل مجاني دائمًا. */
    static boolean on(Db db) { return License.current.ai() && "1".equals(db.setting("ai_on", "0")); }

    static boolean auto(Db db) { return on(db) && "1".equals(db.setting("ai_auto", "0")); }

    static AiExtract.Config config(Db db) { return AiPrefs.config(db); }

    static String providerName(AiExtract.Config c) { return AiExtract.PROVIDER_NAMES[AiExtract.providerIndex(c.provider)]; }

    static void screen(final MainActivity a, final Runnable back) {
        if (!a.require(Auth.Perm.SETTINGS)) return;
        a.here(() -> screen(a, back));
        a.tabbedScreen(AiExtract.NAME, back, "more");
        if (!License.current.ai()) {
            a.notice(R.drawable.ic_sparkle, AiExtract.NAME + " من ميزات النسخة المدفوعة. المحرك المدمج في التطبيق يفهم معظم رسائل واتساب وحده دون إنترنت، وهو مجاني ويعمل دائمًا.",
                    false, "الترخيص", v -> a.licenseScreen.show());
        }
        final AiExtract.Config cur = config(a.db);
        a.hint(AiExtract.EXPLAIN + " اختيارية ومطفأة افتراضيًا. عند تفعيلها يظهر زر «" + AiExtract.BUTTON + "» في بطاقة المعلومات المستخرجة، "
                + "فتُقرأ الرسالة بأي لغة وأي صيغة للمبلغ والاسم. النتيجة تظهر في البطاقة نفسها ولا تُكتب في الحقول إلا بزر «إضافة المعلومات للحقول».");
        LinearLayout info = a.card();
        info.setBackground(a.round(Color.rgb(255, 248, 230), Color.rgb(232, 205, 140), 12));
        info.addView(a.text("ما الذي يُرسل؟", 15, a.dark, true));
        info.addView(a.text("نص رسالة الحوالة فقط ورموز عملات المكتب، إلى الخدمة التي تختارها وبمفتاحك أنت. لا تُرسل أرصدة ولا بيانات مكاتب ولا حركات.", 13, a.dark, false));
        info.addView(a.text("التكلفة", 15, a.dark, true));
        info.addView(a.text("تدفعها للخدمة التي تختارها مباشرة، حسب أسعارها.", 13, a.dark, false));
        info.addView(a.text("الخدمات", 15, a.dark, true));
        info.addView(a.text("تعمل مع أي خدمة ذكاء اصطناعي حالية أو مستقبلية: Claude، أو OpenAI، أو Gemini، أو «مخصّص» لأي خدمة تقبل صيغة OpenAI (عنوانها ومفتاحها واسم نموذجها). "
                + "إن لم تتوفر خدمة في بلدك فاختر غيرها.", 13, a.dark, false));

        final CheckBox on = new CheckBox(a);
        on.setText("تفعيل زر «" + AiExtract.BUTTON + "»");
        on.setChecked(on(a.db));
        a.body.addView(on);
        final CheckBox auto = new CheckBox(a);
        auto.setText("تشغيله تلقائيًا بعد لصق رسالة لم يستخرج المحرك المدمج كل معلوماتها");
        auto.setChecked("1".equals(a.db.setting("ai_auto", "0")));
        a.body.addView(auto);

        a.add(a.label("الخدمة"));
        final Ui.Seg prov = a.new Seg(AiExtract.PROVIDER_NAMES, null, AiExtract.providerIndex(cur.provider), false);
        a.body.addView(prov.view, a.lp(-1, -2, 2));

        a.add(a.label("مفتاح الخدمة (API key)"));
        final EditText key = a.field(cur.key.isEmpty() ? "الصق المفتاح هنا" : "مفتاح محفوظ ✓ — الصق مفتاحًا جديدًا لتغييره");
        Ui.secret(key, false);
        a.body.addView(key, a.lp(-1, 52, 4));

        final TextView modelLabel = a.add(a.label("النموذج"));
        final EditText model = a.field("");
        model.setText(a.db.setting("ai_model", ""));
        a.body.addView(model, a.lp(-1, 52, 4));
        final TextView urlLabel = a.add(a.label("عنوان الخدمة"));
        final EditText url = a.field("https://api.openai.com/v1");
        url.setText(a.db.setting("ai_url", ""));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        a.body.addView(url, a.lp(-1, 52, 4));
        final TextView provHint = a.hint("");
        final Runnable refresh = () -> {
            String p = AiExtract.PROVIDERS[Math.max(0, prov.selected)];
            boolean claude = AiExtract.CLAUDE.equals(p);
            model.setHint(claude ? AiExtract.CLAUDE_MODEL + " (افتراضي)" : "اسم النموذج كما في موقع الخدمة");
            boolean custom = AiExtract.CUSTOM.equals(p);
            urlLabel.setVisibility(claude ? View.GONE : View.VISIBLE);
            url.setVisibility(claude ? View.GONE : View.VISIBLE);
            url.setHint(AiExtract.GEMINI.equals(p) ? AiExtract.GEMINI_URL + " (افتراضي)" : custom ? "https://…/v1 (مطلوب)" : AiExtract.OPENAI_URL + " (افتراضي)");
            provHint.setText(claude
                    ? "مفتاح Claude: أنشئ حسابًا في platform.claude.com واشحن رصيدًا، ثم أنشئ مفتاح API (يبدأ بـ sk-ant-)."
                    : AiExtract.GEMINI.equals(p) ? "مفتاح Gemini من Google AI Studio، واسم النموذج كما في موقعها. العنوان الافتراضي جاهز."
                    : custom ? "أي خدمة حالية أو مستقبلية تقبل صيغة OpenAI (chat/completions): اكتب عنوانها ومفتاحها واسم النموذج كما في موقعها."
                    : "مفتاح OpenAI من موقعها، واسم النموذج كما في موقعها. العنوان الافتراضي جاهز.");
        };
        prov.listener = i -> refresh.run();
        refresh.run();

        a.button("حفظ الإعدادات", a.primary, v -> {
            save(a, on.isChecked(), auto.isChecked(), AiExtract.PROVIDERS[Math.max(0, prov.selected)], key.getText().toString(), model.getText().toString(), url.getText().toString());
            a.toast(on.isChecked() ? (config(a.db).problem() == null ? "حُفظت. الزر مفعّل في «تسجيل حركة»." : config(a.db).problem()) : "حُفظت. الميزة متوقفة.");
            screen(a, back);
        });
        a.button("اختبار الاتصال", a.accent, v -> {
            save(a, on.isChecked(), auto.isChecked(), AiExtract.PROVIDERS[Math.max(0, prov.selected)], key.getText().toString(), model.getText().toString(), url.getText().toString());
            test(a);
        });
        if (!cur.key.isEmpty()) {
            a.button("حذف المفتاح من هذا الهاتف", a.red, v -> new AlertDialog.Builder(a).setTitle("حذف المفتاح")
                    .setMessage("ستتوقف «" + AiExtract.NAME + "» حتى تضيف مفتاحًا جديدًا.")
                    .setPositiveButton("حذف", (d, w) -> {
                        a.db.deleteSetting("ai_key");
                        a.db.setSetting("ai_on", "0");
                        screen(a, back);
                    }).setNegativeButton("إلغاء", null).show());
        }
        a.hint("المفتاح والإعدادات خاصة بهذا الهاتف ومحفوظة مشفّرة، ولا تُنقل مع النسخة الاحتياطية.");
    }

    private static void save(MainActivity a, boolean on, boolean auto, String provider, String key, String model, String url) {
        a.db.setSetting("ai_on", on ? "1" : "0");
        a.db.setSetting("ai_auto", auto ? "1" : "0");
        a.db.setSetting("ai_provider", provider);
        String k = key.trim();
        if (!k.isEmpty()) a.db.setSettingQuiet("ai_key", Crypto.enc(k));   // لا يُكتب المفتاح في سجل العمليات
        a.db.setSetting("ai_model", model.trim());
        a.db.setSetting("ai_url", url.trim());
    }

    private static void test(final MainActivity a) {
        final AiExtract.Config c = config(a.db);
        String p = c.problem();
        if (p != null) {
            a.toast(p);
            return;
        }
        final List<MessageParser.Cur> curs = new ArrayList<>();
        for (Model.Currency x : a.db.currencies(false)) curs.add(new MessageParser.Cur(x.code, x.name));
        final String sample = "need five hundred dollars to Mr John Smith in Hama, tel 0933 111 222";
        a.busy("جارٍ اختبار الاتصال بـ " + providerName(c) + "…", pr -> AiExtract.call(c, sample, curs, CurrencyWords.load(a.db)), res -> {
            MessageParser.Result r = (MessageParser.Result) res;
            String msg = "الرسالة التجريبية:\n" + sample + "\n\nفهمها كالتالي:\nالمستفيد: " + (r.person.isEmpty() ? "—" : r.person)
                    + "\nالمبلغ: " + (r.amount.isEmpty() ? "—" : r.amount) + "\nالعملة: " + (r.currency != null ? r.currency : r.currencyWord.isEmpty() ? "—" : r.currencyWord)
                    + "\nالمدينة: " + (r.city.isEmpty() ? "—" : r.city) + "\nالهاتف: " + (r.phone.isEmpty() ? "—" : r.phone);
            new AlertDialog.Builder(a).setTitle("الاتصال يعمل ✓").setMessage(msg).setPositiveButton("حسنًا", null).show();
        });
    }
}
