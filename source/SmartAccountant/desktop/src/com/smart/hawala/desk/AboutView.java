package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import javax.swing.*;

/** 9.0: «حول التطبيق»: معلومات عن التطبيق، ومن نحن، واتصل بنا (النصوص في AboutText المشترك مع الهاتف). */
final class AboutView extends View {
    AboutView(App app) {
        super(app, "حول التطبيق");
    }

    @Override void refresh() {
        clear();
        int w = textWidth();
        subtitle.setText(AboutText.COMPANY + " — الإصدار " + BuildInfo.VERSION + " " + Ui.forOs());
        Ui.Card us = Ui.card();
        us.add(Ui.heading("من نحن"));
        for (String p : AboutText.ABOUT_US) us.add(Ui.para(p, 14, Ui.ink, w));
        add(us);
        space(12);
        Ui.Card c = Ui.card();
        c.add(Ui.heading("اتصل بنا"));
        c.add(Ui.label(Ui.ltr(AboutText.PHONE), 22, Ui.primaryInk, true));
        c.add(Ui.label("واتساب أو اتصال، للدعم الفني وطلب النسخة الكاملة.", 13, Ui.muted, false));
        c.add(Ui.gap(8));
        c.add(Ui.flow(Ui.primary("مراسلة على واتساب", e -> open("https://wa.me/" + AboutText.PHONE_DIGITS)),
                Ui.ghost("نسخ الرقم", e -> {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(AboutText.PHONE), null);
                    Ui.toast("نُسخ الرقم");
                })));
        add(c);
        space(12);
        Ui.Card f = Ui.card();
        f.add(Ui.heading("معلومات عن التطبيق"));
        f.add(Ui.para(AboutText.INTRO, 14, Ui.ink, w));
        f.add(Ui.gap(8));
        for (String[] x : AboutText.FEATURES) {
            f.add(Ui.label(x[0], 14.5f, Ui.ink, true));
            f.add(Ui.para(x[1], 13.5f, Ui.muted, w));
            f.add(Ui.gap(6));
        }
        // 10.12: المنصات الأخرى (الهاتف أيضًا)، وطلبها بالتواصل
        f.add(Ui.gap(4));
        f.add(Ui.label("متوفر على الهاتف والويندوز والماك", 14.5f, Ui.primaryInk, true));
        f.add(Ui.para("للمحاسب الذكي تطبيق للهاتف (أندرويد)، ونسخة للويندوز 10 و11، وللماك بشريحة Apple أو معالج Intel، "
                + "تتزامن كلها ببيانات واحدة. لطلب نسخة لجهاز آخر تواصل معنا.", 13.5f, Ui.muted, w));
        f.add(Ui.flow(Ui.primary("اطلبها على واتساب", ev -> open("https://wa.me/" + AboutText.PHONE_DIGITS))));
        f.add(Ui.gap(8));
        Entitlements e = License.current;
        f.add(Ui.para(e.pro ? "النسخة الكاملة: كل الميزات مفتوحة." : AboutText.FREE_LINE + "\n" + AboutText.BUY_LINE, 14, Ui.goldInk, w));
        add(f);
        done();
    }

    void open(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            Ui.info(this, "اتصل بنا", "الرقم: " + AboutText.PHONE);
        }
    }
}
