package com.smart.hawala;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.util.List;

/** صورة «مطابقة رصيد» أنيقة للإرسال: الترحيب، العنوان، المكتب والتاريخ، ثم سطر لكل عملة بلونه، والتذييل. */
final class BalanceCard {
    private BalanceCard() {}

    private static final int W = 1080;
    private static final int OUT = 36;      // هامش الصورة حول البطاقة
    private static final int PAD = 44;      // هامش داخل البطاقة
    private static final int ROW_H = 118;
    private static final int GREEN = Color.rgb(27, 120, 70), RED = Color.rgb(173, 46, 54), GRAY = Color.rgb(120, 120, 120), INK = Color.rgb(40, 40, 40);

    private static TextPaint paint(float size, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    private static StaticLayout wrap(String s, TextPaint p, int width) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, width).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(6f, 1f).build();
    }

    private static int blend(int c, float a) {
        return Color.rgb((int) (255 - (255 - Color.red(c)) * a), (int) (255 - (255 - Color.green(c)) * a), (int) (255 - (255 - Color.blue(c)) * a));
    }

    /** 12.6: علامة النسخة المجانية على صورة المطابقة. */
    static final String FREE_MARK = "نسخة مجانية";

    static Bitmap render(Reconcile.Template t, String office, String issuer, long at, List<Reconcile.Row> rows, int accent, Bitmap logo) {
        final int cardL = OUT, cardR = W - OUT, inner = cardR - cardL - 2 * PAD;
        final float cx = W / 2f;

        TextPaint greetP = paint(34, GRAY, false);
        TextPaint titleP = paint(62, Ui.onColor(accent), true);
        TextPaint issuerTopP = paint(30, Ui.onColor(accent), false);
        TextPaint officeP = paint(44, INK, true);
        TextPaint dateP = paint(32, GRAY, false);
        TextPaint nameP = paint(40, INK, true);
        TextPaint amountP = paint(46, INK, true);
        TextPaint wordP = paint(32, Color.WHITE, true);
        TextPaint footP = paint(32, GRAY, false);

        String greeting = Model.nz(t.greeting).trim();
        String orn = Model.nz(t.ornament).trim();
        String title = (orn.isEmpty() ? "" : orn + "  ") + t.title.trim() + (orn.isEmpty() ? "" : "  " + orn);
        String to = (Model.nz(t.salutation).trim() + " " + Model.nz(office).trim() + " " + Model.nz(t.honorific).trim()).trim();
        String footer = Model.nz(t.footer).trim();
        String sign = t.showIssuer ? Model.nz(issuer).trim() : "";

        StaticLayout greetL = greeting.isEmpty() ? null : wrap(greeting, greetP, inner);
        StaticLayout toL = wrap(to, officeP, inner);
        StaticLayout footL = footer.isEmpty() ? null : wrap(footer, footP, inner);

        // ---- القياس
        int y = OUT + PAD;
        int greetTop = y;
        if (greetL != null) y += greetL.getHeight() + 28;
        int bandTop = y, bandH = sign.isEmpty() ? 150 : 186;
        y += bandH + 36;
        int toTop = y;
        y += toL.getHeight() + 8;
        int dateBase = y + 34;
        y += 34 + 40;
        int rowsTop = y;
        int nRows = Math.max(1, rows.size());
        y += nRows * (ROW_H + 18) + 22;
        int footTop = y;
        if (footL != null) y += footL.getHeight() + 20;
        // 12.6: النسخة المجانية: «نسخة مجانية» بخط صغير أحمر أسفل البطاقة (غير مزعج)
        final boolean free = License.current.watermark();
        int freeBase = y + 26;
        if (free) y += 38;
        y += PAD;
        int H = y + OUT;

        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(blend(accent, 0.10f));
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(cardL, OUT, cardR, H - OUT), 36, 36, fill);

        // ---- الترحيب
        if (greetL != null) {
            c.save();
            c.translate(cardL + PAD, greetTop);
            greetL.draw(c);
            c.restore();
        }

        // ---- شريط العنوان
        fill.setColor(accent);
        RectF band = new RectF(cardL + PAD, bandTop, cardR - PAD, bandTop + bandH);
        c.drawRoundRect(band, 28, 28, fill);
        titleP.setTextAlign(Paint.Align.CENTER);
        float maxTitle = band.width() - (logo != null ? 2 * 150 : 60);
        while (titleP.measureText(title) > maxTitle && titleP.getTextSize() > 34) titleP.setTextSize(titleP.getTextSize() - 2);
        while (issuerTopP.measureText(sign) > maxTitle && issuerTopP.getTextSize() > 22) issuerTopP.setTextSize(issuerTopP.getTextSize() - 1);
        float titleBase = bandTop + (sign.isEmpty() ? bandH / 2f + 22 : 84);
        c.drawText(title, cx, titleBase, titleP);
        if (!sign.isEmpty()) {
            issuerTopP.setTextAlign(Paint.Align.CENTER);
            c.drawText(sign, cx, bandTop + 140, issuerTopP);
        }
        if (logo != null) {
            float s = 104, lx = band.right - 26 - s, ly = band.centerY() - s / 2;
            fill.setColor(Color.WHITE);
            c.drawCircle(lx + s / 2, ly + s / 2, s / 2 + 6, fill);
            c.drawBitmap(logo, null, new RectF(lx + 8, ly + 8, lx + s - 8, ly + s - 8), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        }

        // ---- المكتب والتاريخ
        c.save();
        c.translate(cardL + PAD, toTop);
        toL.draw(c);
        c.restore();
        dateP.setTextAlign(Paint.Align.CENTER);
        c.drawText("التاريخ: " + Model.date(at), cx, dateBase, dateP);

        // ---- الأرصدة
        float right = cardR - PAD, left = cardL + PAD;
        float ry = rowsTop;
        if (rows.isEmpty()) {
            fill.setColor(blend(GREEN, 0.10f));
            c.drawRoundRect(new RectF(left, ry, right, ry + ROW_H), 24, 24, fill);
            nameP.setTextAlign(Paint.Align.CENTER);
            c.drawText("لا توجد أرصدة قائمة؛ الحساب مسدَّد بالكامل ✅", cx, ry + ROW_H / 2f + 14, nameP);
        }
        for (Reconcile.Row r : rows) {
            int col = t.color(r.sign());
            fill.setColor(blend(col, 0.09f));
            RectF box = new RectF(left, ry, right, ry + ROW_H);
            c.drawRoundRect(box, 24, 24, fill);
            fill.setColor(col);
            c.drawRoundRect(new RectF(right - 12, ry + 18, right, ry + ROW_H - 18), 6, 6, fill);
            float mid = ry + ROW_H / 2f;
            // الكلمة في شارة ملوّنة على اليسار، والمبلغ بجانبها
            String w = Reconcile.word(t, r);
            float ww = wordP.measureText(w) + 44;
            amountP.setTextSize(46);
            float aw = amountP.measureText(t.amount(r));
            // 10.16: «لنا/لكم» قبل الرقم (على يمينه في القراءة العربية) أو بعده (على يساره)
            boolean before = t.wordBefore();
            float amountX = before ? left + 26 : left + 26 + ww + 22;
            float pillL = before ? left + 26 + aw + 22 : left + 26;
            RectF pill = new RectF(pillL, mid - 30, pillL + ww, mid + 30);
            float amountEnd = Math.max(pill.right, amountX + aw);
            String label = r.label(t.showCode);
            float avail = right - 34 - amountEnd - 28;
            nameP.setTextSize(40);
            while (nameP.measureText(label) > avail && nameP.getTextSize() > 26) nameP.setTextSize(nameP.getTextSize() - 2);
            nameP.setTextAlign(Paint.Align.RIGHT);
            c.drawText(label, right - 34, mid + 14, nameP);
            c.drawRoundRect(pill, 30, 30, fill);
            wordP.setTextAlign(Paint.Align.CENTER);
            c.drawText(w, pill.centerX(), mid + 11, wordP);
            amountP.setColor(col);
            amountP.setTextAlign(Paint.Align.LEFT);
            c.drawText(t.amount(r), amountX, mid + 16, amountP);
            ry += ROW_H + 18;
        }

        // ---- التذييل
        if (footL != null) {
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(Color.rgb(225, 222, 214));
            line.setStrokeWidth(2);
            c.drawLine(cx - 160, footTop - 12, cx + 160, footTop - 12, line);
            c.save();
            c.translate(cardL + PAD, footTop + 8);
            footL.draw(c);
            c.restore();
        }
        if (free) {
            TextPaint fp = paint(22, Color.rgb(198, 40, 40), false);
            fp.setTextAlign(Paint.Align.CENTER);
            c.drawText(FREE_MARK, cx, freeBase, fp);
        }
        return bm;
    }
}
