package com.smart.hawala;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 9.0: «من نحن» و«اتصل بنا» و«معلومات عن التطبيق» (النصوص في AboutText المشترك). */
final class About {
    private About() {}

    static final String COMPANY = AboutText.COMPANY;
    static final String PHONE_SHOWN = AboutText.PHONE_SHOWN;

    private static void logoCard(MainActivity a, String title, String sub) {
        LinearLayout c = a.card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(a);
        iv.setImageResource(R.mipmap.ic_launcher);
        c.addView(iv, new LinearLayout.LayoutParams(a.dp(72), a.dp(72)));
        TextView t = a.text(title, 19, a.primaryInk, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null) {
            TextView s = a.text(sub, 13, a.muted, false);
            s.setGravity(Gravity.CENTER);
            c.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    static void aboutUs(final MainActivity a, Runnable back) {
        a.here(() -> aboutUs(a, back));
        a.tabbedScreen("من نحن", back, "more");
        logoCard(a, COMPANY, "حلول ذكية تسهّل عمل المشاريع");
        LinearLayout c = a.card();
        for (String p : AboutText.ABOUT_US) {
            TextView t = a.text(p, 15, a.ink, false);
            t.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
            c.addView(t);
        }
        a.button("اتصل بنا", a.primary, v -> contact(a, () -> aboutUs(a, back)));
    }

    static void contact(final MainActivity a, Runnable back) {
        a.here(() -> contact(a, back));
        a.tabbedScreen("اتصل بنا", back, "more");
        logoCard(a, COMPANY, "يسعدنا سماع ملاحظاتك واقتراحاتك");
        LinearLayout c = a.card();
        c.addView(a.text("رقم التواصل", 13, a.muted, false));
        TextView n = a.text(PHONE_SHOWN, 24, a.primaryInk, true);
        n.setTextIsSelectable(true);
        c.addView(n);
        c.addView(a.text("واتساب أو اتصال، للدعم الفني وطلب النسخة الكاملة.", 13, a.muted, false));
        a.button("مراسلة على واتساب", a.primary, v -> open(a, "https://wa.me/" + AboutText.PHONE_DIGITS));
        a.button("اتصال", a.accent, v -> open(a, "tel:" + AboutText.PHONE));
        a.button("نسخ الرقم", android.graphics.Color.rgb(130, 130, 130), v -> {
            try {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("phone", AboutText.PHONE));
                a.toast("نُسخ الرقم " + AboutText.PHONE);
            } catch (Exception e) {
                a.error(e);
            }
        });
    }

    static void appInfo(final MainActivity a, Runnable back) {
        a.here(() -> appInfo(a, back));
        a.tabbedScreen("معلومات عن التطبيق", back, "more");
        logoCard(a, a.db.setting("app_title", "المحاسب الذكي لمكاتب الحوالات"), "الإصدار " + a.versionName());
        LinearLayout in = a.card();
        TextView it = a.text(AboutText.INTRO, 15, a.ink, false);
        it.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
        in.addView(it);
        for (String[] f : AboutText.FEATURES) {
            LinearLayout c = a.card();
            c.addView(a.text(f[0], 16, a.primaryInk, true));
            c.addView(a.text(f[1], 14, a.ink, false));
        }
        // 10.12: المنصات الأخرى، وطلبها بالتواصل
        LinearLayout pc = a.card();
        pc.addView(a.text(AboutText.PLATFORMS_TITLE, 16, a.primaryInk, true));
        pc.addView(a.text(AboutText.PLATFORMS_LINE, 14, a.ink, false));
        LinearLayout pr = a.row();
        pr.addView(a.smallButton("اطلبها على واتساب", a.primary, v -> open(a, "https://wa.me/" + AboutText.PHONE_DIGITS + "?text="
                + Uri.encode("أرغب بنسخة المحاسب الذكي للكمبيوتر (ويندوز / ماك)"))), a.weight(44, 1.2f, 2));
        pr.addView(a.smallButton("اتصل بنا", a.accent, v -> open(a, "tel:" + AboutText.PHONE)), a.weight(44, 0.8f, 2));
        pc.addView(pr, a.lp(-1, -2, 4));
        Entitlements e = License.current;
        LinearLayout lc = a.card();
        lc.addView(a.text(e.pro ? e.edition() : AboutText.FREE_TITLE, 16, a.goldInk, true));
        lc.addView(a.text(e.pro ? "كل الميزات مفتوحة." : AboutText.FREE_LINE + "\n" + AboutText.BUY_LINE, 14, a.ink, false));
        a.hint("من " + COMPANY + " • الإصدار " + a.versionName());
    }

    private static void open(MainActivity a, String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            if (uri.startsWith("tel:")) i = new Intent(Intent.ACTION_DIAL, Uri.parse(uri));
            a.startActivity(i);
        } catch (Exception e) {
            a.toast("تعذر الفتح على هذا الهاتف. الرقم: " + AboutText.PHONE);
        }
    }
}
