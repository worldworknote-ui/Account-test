package com.smart.hawala;

import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * كلمات العملات التي يضيفها المستخدم (مثل «يوز» = USDT) ليفهمها استخراج المعلومات من رسائل واتساب.
 * تُحفظ في الإعدادات (cur_aliases) فتنتقل مع النسخة الاحتياطية.
 */
final class CurrencyWords {
    private CurrencyWords() {}

    static final String KEY = "cur_aliases";

    static Map<String, List<String>> load(Db db) { return MessageParser.aliasesFrom(db.setting(KEY, "")); }

    private static List<MessageParser.Cur> allCurrencies(Db db) {
        List<MessageParser.Cur> out = new ArrayList<>();
        for (Model.Currency c : db.currencies(true)) out.add(new MessageParser.Cur(c.code, c.name, c.decimals));
        return out;
    }

    /** يضيف الكلمة للعملة، أو يعيد سبب الرفض. */
    static String add(Db db, String code, String word) {
        Map<String, List<String>> m = load(db);
        String problem = MessageParser.aliasProblem(word, code, m, allCurrencies(db));
        if (problem != null) return problem;
        List<String> l = m.get(code);
        if (l == null) {
            l = new ArrayList<>();
            m.put(code, l);
        }
        l.add(word.trim());
        db.setSetting(KEY, MessageParser.aliasesTo(m));
        return null;
    }

    static void remove(Db db, String code, String word) {
        Map<String, List<String>> m = load(db);
        List<String> l = m.get(code);
        if (l == null) return;
        l.remove(word);
        if (l.isEmpty()) m.remove(code);
        db.setSetting(KEY, MessageParser.aliasesTo(m));
    }

    /** من بطاقة المعلومات المستخرجة: الكلمة معروفة، والمستخدم يختار عملتها. */
    static void linkDialog(final MainActivity a, final String word, final List<String> codes, final Runnable after) {
        if (codes.isEmpty()) {
            a.toast("لا توجد عملات. أضف العملة أولًا من شاشة العملات.");
            return;
        }
        Map<String, String> names = a.db.currencyNames();
        String[] items = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++) {
            String n = names.get(codes.get(i));
            items[i] = codes.get(i) + (n == null || n.isEmpty() ? "" : " — " + n);
        }
        new AlertDialog.Builder(a).setTitle("«" + word + "» تعني أي عملة؟")
                .setItems(items, (d, i) -> {
                    String err = add(a.db, codes.get(i), word);
                    if (err != null) {
                        new AlertDialog.Builder(a).setTitle("لم تُضف الكلمة").setMessage(err).setPositiveButton("حسنًا", null).show();
                        return;
                    }
                    a.toast("من الآن تُفهم «" + word + "» على أنها " + codes.get(i) + ". يمكنك تعديل الكلمات من الإعدادات ← كلمات العملات.");
                    if (after != null) after.run();
                })
                .setNegativeButton("إلغاء", null).show();
    }

    /** إضافة كلمة لعملة معروفة: النافذة تبقى مفتوحة إن رُفضت الكلمة حتى يصححها المستخدم. */
    static void wordDialog(final MainActivity a, final String code, final Runnable after) {
        final EditText e = a.field("مثال: يوز");
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("كلمة جديدة لعملة " + code)
                .setMessage("اكتب الكلمة كما تأتي في رسائل المكاتب.")
                .setView(e).setPositiveButton("إضافة", null).setNegativeButton("إلغاء", null).create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String err = add(a.db, code, e.getText().toString());
            if (err != null) {
                e.setError(err);
                return;
            }
            dlg.dismiss();
            if (after != null) after.run();
        }));
        dlg.show();
    }

    /** شاشة الإعدادات: لكل عملة كلماتها المعروفة مسبقًا وكلمات المستخدم مع حذف وإضافة. */
    static void screen(final MainActivity a, final Runnable back) {
        a.here(() -> screen(a, back));
        a.tabbedScreen("كلمات العملات", back, "more");
        a.hint("أضف الكلمات التي تكتبها المكاتب للعملة في رسائل واتساب، مثل «يوز» لعملة USDT. "
                + "عند لصق الرسالة في البيان تُفهم الكلمة وتُختار عملتها. الكلمات تُحفظ مع النسخة الاحتياطية.");
        if (!"1".equals(a.db.setting("wa_parse", "1"))) a.hint("تنبيه: ميزة استخراج المعلومات من رسائل واتساب متوقفة حاليًا من الإعدادات.");
        final Runnable again = () -> screen(a, back);
        Map<String, List<String>> mine = load(a.db);
        List<Model.Currency> curs = a.db.currencies(false);
        if (curs.isEmpty()) a.hint("لا توجد عملات مفعّلة.");
        for (final Model.Currency c : curs) {
            LinearLayout card = a.card();
            card.addView(a.text(c.code + (c.name == null || c.name.isEmpty() ? "" : " — " + c.name), 16, a.dark, true));
            List<String> known = MessageParser.builtinWords(c.code);
            if (!known.isEmpty()) card.addView(a.text("يعرفها البرنامج: " + Model.join(known, "، "), 12, a.muted, false));
            List<String> l = mine.get(c.code);
            if (l == null || l.isEmpty()) {
                card.addView(a.text("لا توجد كلمات مضافة.", 13, a.muted, false));
            } else {
                for (final String w : l) {
                    LinearLayout r = a.row();
                    TextView t = a.text("«" + w + "»", 16, a.green, true);
                    r.addView(t, a.weight(-2, 1, 2));
                    Button del = a.smallButton("حذف", a.red, v -> new AlertDialog.Builder(a).setTitle("حذف الكلمة")
                            .setMessage("لن تُفهم «" + w + "» على أنها " + c.code + " بعد الآن.")
                            .setPositiveButton("حذف", (d, i) -> {
                                remove(a.db, c.code, w);
                                again.run();
                            }).setNegativeButton("إلغاء", null).show());
                    r.addView(del, a.weight(40, 0.45f, 2));
                    card.addView(r);
                }
            }
            card.addView(a.makeButton("+ إضافة كلمة لـ " + c.code, a.accent, v -> wordDialog(a, c.code, again)), a.lp(-1, 44, 3));
        }
        a.hint("العملة غير موجودة في القائمة؟ أضفها أو فعّلها أولًا من شاشة العملات (مثل USDT).");
    }
}
