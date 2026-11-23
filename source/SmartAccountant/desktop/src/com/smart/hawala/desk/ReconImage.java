package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 10.16: صورة مطابقة الرصيد على الكمبيوتر (كانت على الهاتف وحده): بطاقة بعرض 1080 بالقالب نفسه (التحية، العنوان، المكتب،
 * سطر لكل عملة بلون «لنا/لكم» وموضع الكلمة المختارين، التذييل، اسم مكتبك). تُنسخ إلى الحافظة لتُلصق في واتساب، أو تُحفظ PNG.
 */
final class ReconImage {
    private ReconImage() {}

    static final int W = 1080, PAD = 54, ROW_H = 104;

    private static Font f(String s, float size, boolean bold) {
        Font app = Ui.font(size, bold);
        // رموز لا يحويها خط البرنامج (🌹 🔍): خط النظام المنطقي يعرضها
        return s != null && app.canDisplayUpTo(s) >= 0 ? new Font(Font.DIALOG, bold ? Font.BOLD : Font.PLAIN, Math.round(size)) : app;
    }

    /** يرسم نصًّا عربيًّا محاذًى (-1 يسار، 0 وسط، 1 يمين) عند x ويرجع عرضه. */
    private static float text(Graphics2D g, String s, float size, boolean bold, Color c, float x, float y, int align) {
        if (s == null || s.isEmpty()) return 0;
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout tl = new TextLayout(s, f(s, size, bold), frc);
        float w = tl.getAdvance();
        g.setColor(c);
        tl.draw(g, align < 0 ? x : align == 0 ? x - w / 2 : x - w, y);
        return w;
    }

    private static float width(Graphics2D g, String s, float size, boolean bold) {
        return s == null || s.isEmpty() ? 0 : new TextLayout(s, f(s, size, bold), g.getFontRenderContext()).getAdvance();
    }

    private static Color tint(Color c, float a) {
        return new Color(Math.round(255 + (c.getRed() - 255) * a), Math.round(255 + (c.getGreen() - 255) * a), Math.round(255 + (c.getBlue() - 255) * a));
    }

    static BufferedImage render(Reconcile.Template t, String office, String issuer, long at, List<Reconcile.Row> rows, Color accent) {
        String greeting = Model.nz(t.greeting).trim(), footer = Model.nz(t.footer).trim(), orn = Model.nz(t.ornament).trim();
        boolean hasIssuer = t.showIssuer && issuer != null && !issuer.trim().isEmpty();
        int h = PAD + (greeting.isEmpty() ? 0 : 64) + 150 + (t.showOffice ? 70 : 0) + (t.showDate ? 50 : 0) + 30
                + Math.max(1, rows.size()) * (ROW_H + (t.gap ? 22 : 12)) + (footer.isEmpty() ? 0 : 80) + PAD + 20;
        BufferedImage im = new BufferedImage(W, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(tint(accent, 0.10f));
        g.fillRect(0, 0, W, h);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(24, 24, W - 48, h - 48, 44, 44));
        Color gray = new Color(120, 120, 120), ink = new Color(40, 40, 40);
        float left = PAD, right = W - PAD, y = PAD + 30;
        if (!greeting.isEmpty()) {
            text(g, greeting, 30, false, gray, W / 2f, y + 10, 0);
            y += 64;
        }
        // شريط العنوان
        g.setColor(accent);
        g.fill(new RoundRectangle2D.Float(left, y, right - left, 126, 28, 28));
        String title = (orn.isEmpty() ? "" : orn + "  ") + t.title.trim() + (orn.isEmpty() ? "" : "  " + orn);
        text(g, title, 44, true, Color.WHITE, W / 2f, y + (hasIssuer ? 58 : 78), 0);
        if (hasIssuer) text(g, issuer.trim(), 26, false, new Color(255, 255, 255, 225), W / 2f, y + 102, 0);
        y += 150;
        if (t.showOffice) {
            String who = (t.salutation.trim().isEmpty() ? "" : t.salutation.trim() + " ") + Model.nz(office) + (t.honorific.trim().isEmpty() ? "" : " " + t.honorific.trim());
            text(g, who, 38, true, ink, W / 2f, y + 38, 0);
            y += 70;
        }
        if (t.showDate) {
            String lbl = t.dateLabel.replaceAll("[^\\p{L}\\p{N}\\p{P}\\s]", "").trim();
            text(g, (lbl.isEmpty() ? "" : lbl + " ") + Model.date(at), 28, false, gray, W / 2f, y + 26, 0);
            y += 50;
        }
        y += 30;
        if (rows.isEmpty()) text(g, t.emptyText.replaceAll("[^\\p{L}\\p{N}\\p{P}\\s]", "").trim(), 34, true, ink, W / 2f, y + 60, 0);
        boolean before = t.wordBefore();
        for (Reconcile.Row r : rows) {
            Color col = new Color(t.color(r.sign()) & 0xFFFFFF);
            g.setColor(tint(col, 0.09f));
            g.fill(new RoundRectangle2D.Float(left, y, right - left, ROW_H, 24, 24));
            g.setColor(col);
            g.fill(new RoundRectangle2D.Float(right - 12, y + 18, 12, ROW_H - 36, 6, 6));
            float mid = y + ROW_H / 2f;
            String word = Reconcile.word(t, r), amount = t.amount(r);
            float ww = width(g, word, 28, true) + 44, aw = width(g, amount, 46, true);
            float amountX = before ? left + 26 : left + 26 + ww + 22, pillL = before ? left + 26 + aw + 22 : left + 26;
            g.setColor(col);
            g.fill(new RoundRectangle2D.Float(pillL, mid - 30, ww, 60, 60, 60));
            text(g, word, 28, true, Color.WHITE, pillL + ww / 2, mid + 10, 0);
            text(g, amount, 46, true, col, amountX, mid + 16, -1);
            String label = r.label(t.showCode);
            float avail = right - 34 - Math.max(pillL + ww, amountX + aw) - 28, size = 40;
            while (size > 24 && width(g, label, size, false) > avail) size -= 2;
            text(g, label, size, t.boldName, ink, right - 34, mid + 14, 1);
            y += ROW_H + (t.gap ? 22 : 12);
        }
        if (!footer.isEmpty()) {
            y += 14;
            float size = 30;
            while (size > 20 && width(g, footer, size, false) > right - left) size -= 2;
            text(g, footer, size, false, gray, W / 2f, y + 34, 0);
        }
        g.dispose();
        return im;
    }

    /** ينسخ الصورة إلى الحافظة (تُلصق مباشرة في واتساب للكمبيوتر أو واتساب ويب). */
    static void copy(final BufferedImage im) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }

            @Override public boolean isDataFlavorSupported(DataFlavor fl) { return DataFlavor.imageFlavor.equals(fl); }

            @Override public Object getTransferData(DataFlavor fl) { return im; }
        }, null);
    }
}
