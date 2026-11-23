package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import javax.swing.*;

/**
 * الترخيص على الماك: المجاني (9 مكاتب و700 حركة جديدة لكل مكتب، بلا مدة) أو الكامل برمز من البائع مربوط بهذا الجهاز.
 * الخطوات كالهاتف: رقم الطلب ← «إنشاء طلب التفعيل» ← يُرسل للبائع ← يصل رمز ← «تفعيل». ولا شيء هنا يحجز البيانات.
 */
final class LicenseView extends View {
    LicenseView(App app) {
        super(app, "الترخيص");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        Entitlements e = License.current;
        Ui.Card s = Ui.tinted(e.pro ? Ui.creditBg : Ui.goldSoft, null);
        s.add(Ui.label(e.pro ? "✓ النسخة الكاملة" + (e.lid.isEmpty() ? "" : " — " + e.lid) : AboutText.FREE_TITLE, 18, e.pro ? Ui.green : Ui.goldInk, true));
        if (!e.pro) {
            s.add(Ui.para(AboutText.FREE_LINE, 14.5f, Ui.ink, w));
            s.add(Ui.para(AboutText.BUY_LINE, 14.5f, Ui.goldInk, w));
        }
        if (e.pro) {
            s.add(Ui.label(e.edition(), 14, Ui.ink, false));
            if (!e.updatesUntil().isEmpty()) s.add(Ui.label("التحديثات حتى: " + e.updatesUntil(), 13.5f, Ui.muted, false));
        } else {
            QuotaState q = db.quotaNow();
            long[] wa = db.waUsage();
            s.add(Ui.label("المكاتب: " + q.offices + " من " + e.maxOffices() + " (حذف مكتب يعيد مكانه)   •   الحركات: " + e.maxTxPerOffice() + " حركة جديدة لكل مكتب"
                    + "   •   رسائل واتساب اليوم: " + wa[0] + " من " + wa[1], 14, Ui.ink, false));
            Map<Long, String> names = db.officeNames();
            StringBuilder b = new StringBuilder();
            for (Map.Entry<Long, String> n : names.entrySet()) {
                long used = q.txOf(n.getKey());
                if (used == 0) continue;
                b.append(n.getValue()).append(": ").append(used).append(" من ").append(e.maxTxPerOffice()).append("   ");
            }
            if (b.length() > 0) s.add(Ui.para(b.toString(), 12.5f, Ui.muted, w));
            s.add(Ui.gap(4));
            s.add(Ui.para("عند الحد تتوقف الإضافة فقط: كل البيانات تبقى متاحة للعرض والتقارير والتصدير والنسخ الاحتياطي والاستعادة. "
                    + "النسخة الكاملة: مكاتب وحركات بلا حد، مستخدمون بأدوار، تصدير بلا علامة «نسخة مجانية»، نسخ تلقائي حتى كل 6 ساعات، والقراءة الذكية السحابية المدفوعة الخاصة (ذكاء اصطناعي عبر الإنترنت).", 13.5f, Ui.ink, w));
        }
        if (e.note != null && !e.note.isEmpty()) s.add(Ui.para("! " + e.note, 13.5f, Ui.red, w));
        add(s);
        space(12);

        Ui.Card req = Ui.card();
        req.add(Ui.heading(e.pro ? "نقل الرخصة إلى جهاز آخر" : "التفعيل"));
        if (!e.pro) {
            req.add(Ui.para("1) خذ «رقم الطلب» من البائع.  2) اكتبه هنا واضغط «إنشاء طلب التفعيل» وأرسل النص للبائع (واتساب أو بريد).  3) الصق الرمز الذي يصلك واضغط «تفعيل».\n"
                    + "الرخصة مربوطة بهذا الكمبيوتر؛ رخصة الهاتف لا تعمل هنا والعكس (كل جهاز يُفعَّل بطلبه).", 13.5f, Ui.ink, w));
            Ui.Field order = Ui.field("رقم الطلب من البائع");
            order.setMaximumSize(new Dimension(320, 40));
            req.add(Ui.row(order, Ui.primary("إنشاء طلب التفعيل", ev -> {
                try {
                    String t = License.requestText(app.ctx, db, order.val());
                    showText("طلب التفعيل", "أرسل هذا النص كاملًا للبائع:", t);
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            })));
            req.add(Ui.gap(10));
            JTextArea token = Ui.area(3);
            JScrollPane ts = Ui.areaBox(token);
            ts.setPreferredSize(new Dimension(600, 80));
            ts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
            req.add(Ui.labeled("رمز الترخيص من البائع", ts));
            req.add(Ui.gap(6));
            req.add(Ui.flow(Ui.primary("تفعيل", ev -> {
                try {
                    Entitlements r = License.activate(app.ctx, db, token.getText().trim());
                    app.updateFoot();
                    Ui.info(this, "تم التفعيل ✓", "النسخة الكاملة مفعّلة: " + r.edition());
                    refresh();
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            })));
        } else {
            req.add(Ui.para("لنقل الرخصة: اضغط «إلغاء التفعيل على هذا الجهاز» فتأخذ «إيصال تحرير» موقّعًا، أرسله للبائع مع طلب من الجهاز الجديد. "
                    + "بعد الإلغاء يعود هذا الجهاز للنسخة المجانية وتبقى بياناته كما هي.", 13.5f, Ui.ink, w));
            req.add(Ui.flow(Ui.danger("إلغاء التفعيل على هذا الجهاز…", ev -> {
                if (!Ui.confirm(this, "إلغاء التفعيل", "يعود هذا الجهاز للنسخة المجانية فورًا وتأخذ إيصالًا للبائع. متابعة؟", "إلغاء التفعيل")) return;
                try {
                    String rel = License.release(app.ctx, db);
                    app.updateFoot();
                    showText("إيصال التحرير", "أرسل هذا الإيصال للبائع لنقل الرخصة:", rel);
                    refresh();
                } catch (Exception ex) {
                    Ui.error(this, ex);
                }
            })));
        }
        add(req);
        space(12);

        Ui.Card srv = Ui.card();
        srv.add(Ui.heading("خادم الترخيص (اختياري)"));
        srv.add(Ui.para("إن أنشأت خادم الترخيص (license-server في المشروع) اكتب عنوانه هنا: يسأله البرنامج كل 7 أيام عن الرخص الملغاة. فشل الاتصال لا يوقف شيئًا.", 13, Ui.muted, w));
        Ui.Field url = Ui.field("https://…");
        url.setText(db.setting("license_server", ""));
        srv.add(Ui.row(url, Ui.ghost("حفظ", ev -> {
            String u = url.val();
            if (!u.isEmpty() && !u.startsWith("https://") && !u.startsWith("http://")) {
                Ui.toast("العنوان يبدأ بـ https://");
                return;
            }
            db.setSettingQuiet("license_server", u);
            Ui.toast("حُفظ");
        }), Ui.ghost("فحص الآن", ev -> Ui.busy(this, "جارٍ الاتصال…", p -> License.checkOnline(app.ctx, db), m -> {
            Ui.info(this, "خادم الترخيص", m);
            refresh();
        }))));
        // 12.7: خادم الترخيص مخفي (الكود باقٍ)؛ بدلًا منه هوية المكتب وتأكيد الوقت
        if (com.smart.hawala.LicenseScreenFlags.SHOW_SERVER) add(srv);
        Ui.Card idc = Ui.card();
        idc.add(Ui.heading("هوية المكتب والوقت"));
        long lt = com.smart.hawala.LicenseScreenFlags.lastTrusted(db);
        idc.add(Ui.para("هوية بيانات هذا المكتب: " + com.smart.hawala.LicenseScreenFlags.officeUid(db)
                + "\nالرخصة تعمل على بيانات مكتبها فقط. على جهاز ينضم للمزامنة: انضم أولًا ثم اطلب التفعيل."
                + "\nآخر تأكيد للوقت من الإنترنت: " + (lt <= 0 ? "لم يُؤكَّد بعد" : com.smart.hawala.Model.date(lt))
                + (com.smart.hawala.LicenseScreenFlags.note().isEmpty() ? "" : "\n⚠ " + com.smart.hawala.LicenseScreenFlags.note()), 13, Ui.muted, w));
        idc.add(Ui.ghost("تأكيد الوقت الآن", ev -> Ui.busy(this, "جارٍ تأكيد الوقت…", p -> com.smart.hawala.LicenseScreenFlags.fetch(app.ctx, db), m -> {
            Ui.info(this, "الوقت", (Boolean) m ? "تأكّد الوقت ✓" : "تعذّر الاتصال الآن. لا يتوقف شيء؛ يُعاد تلقائيًا.");
            refresh();
        })));
        add(idc);
        done();
    }

    void showText(String title, String intro, String text) {
        JPanel c = Ui.column();
        c.add(Ui.para(intro, 13.5f, Ui.ink, 480));
        c.add(Ui.gap(6));
        JTextArea a = new JTextArea(text, 8, 40);
        a.setLineWrap(true);
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        JScrollPane sp = new JScrollPane(a);
        sp.setPreferredSize(new Dimension(520, 180));
        c.add(sp);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        c.add(Ui.label("نُسخ النص إلى الحافظة.", 12.5f, Ui.green, true));
        Ui.form(this, title, Ui.rtl(c), "تم");
    }
}
