package com.smart.hawala;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * يرسم أي تقرير ({@link Report}) كصفحات A4: PDF، أو صور PNG صفحة صفحة، أو صورة معاينة للصفحة الأولى.
 * رأس بالشعار واسم مكتبك، ملخص مفصل لكل عملة، جداول بالتفاف النص العربي وتكرار رأس الجدول،
 * صور الوصولات تحت حركاتها، خانات التوقيع، وترقيم الصفحات.
 */
public final class ReportPdf {

    public static final class Opts {
        public ExportStyle style = new ExportStyle();
        public int accent = Color.rgb(11, 107, 83);
        public Bitmap logo;
        public String developer = "";
        /** لجلب الصور عند الرسم (null = بدون صور). */
        public Db db;
    }

    private abstract static class Block {
        float h;
        Block repeatHeader;
        boolean keepWithNext;

        abstract void draw(Canvas c, float top);
    }

    private final Report r;
    private final Opts o;
    private final float W, H, M = 28f, FOOTER = 24f, RUNNING = 22f, PAD = 3.5f, CW;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bold = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint white = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint muted = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint heading = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int light, shade, total;

    private ReportPdf(Report r, Opts o) {
        this.r = r;
        this.o = o;
        boolean land = o.style.landscape;
        W = land ? 842f : 595f;
        H = land ? 595f : 842f;
        CW = W - 2 * M;
        float s = o.style.fontScale();
        text.setTextSize(8.5f * s);
        text.setColor(Color.rgb(35, 35, 35));
        bold.setTextSize(8.8f * s);
        bold.setColor(Color.rgb(25, 25, 25));
        bold.setTypeface(Typeface.DEFAULT_BOLD);
        white.setTextSize(8.8f * s);
        white.setColor(Color.WHITE);
        white.setTypeface(Typeface.DEFAULT_BOLD);
        muted.setTextSize(8.2f * s);
        muted.setColor(Color.rgb(140, 140, 140));
        small.setTextSize(7.4f * s);
        small.setColor(Color.rgb(110, 110, 110));
        title.setTextSize(15f * s);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setColor(o.accent);
        heading.setTextSize(10.5f * s);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setColor(darker(o.accent));
        line.setColor(Color.rgb(212, 212, 212));
        line.setStrokeWidth(0.6f);
        light = blend(o.accent, 0.13f);
        shade = Color.rgb(248, 247, 243);
        total = blend(o.accent, 0.22f);
    }

    static int blend(int c, float a) {
        return Color.rgb((int) (255 - (255 - Color.red(c)) * a), (int) (255 - (255 - Color.green(c)) * a), (int) (255 - (255 - Color.blue(c)) * a));
    }

    static int darker(int c) {
        return Color.rgb((int) (Color.red(c) * 0.8f), (int) (Color.green(c) * 0.8f), (int) (Color.blue(c) * 0.8f));
    }

    private static StaticLayout layout(String s, TextPaint p, float width, Layout.Alignment align) {
        String t = s == null ? "" : s;
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, Math.max(8, (int) width))
                .setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.06f)
                .build();
    }

    private static void drawLayout(Canvas c, StaticLayout l, float x, float y) {
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
    }

    // ---------------------------------------------------------------------------- الكتل

    private Block header() {
        final float logoSize = o.style.logo && o.logo != null ? 54f : 0f;
        final float gap = logoSize > 0 ? 10f : 0f;
        final StaticLayout t = layout(r.title, title, CW - logoSize - gap, Layout.Alignment.ALIGN_NORMAL);
        final StaticLayout lines = layout(Model.join(r.headerLines, "\n"), small, CW - logoSize - gap, Layout.Alignment.ALIGN_NORMAL);
        Block b = new Block() {
            @Override void draw(Canvas c, float top) {
                if (logoSize > 0) {
                    RectF dst = new RectF(W - M - logoSize, top, W - M, top + logoSize);
                    c.drawBitmap(o.logo, new Rect(0, 0, o.logo.getWidth(), o.logo.getHeight()), dst, null);
                }
                drawLayout(c, t, M, top);
                drawLayout(c, lines, M, top + t.getHeight() + 4);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(o.accent);
                p.setStrokeWidth(1.6f);
                c.drawLine(M, top + h - 5, W - M, top + h - 5, p);
            }
        };
        b.h = Math.max(logoSize, t.getHeight() + 4 + lines.getHeight()) + 12;
        return b;
    }

    private Block band(String s, final int accent) {
        TextPaint hp = heading;
        if (accent != 0) {
            hp = new TextPaint(heading);
            hp.setColor(darker(accent));
        }
        final StaticLayout l = layout(s, hp, CW - 2 * PAD, Layout.Alignment.ALIGN_NORMAL);
        Block b = new Block() {
            @Override void draw(Canvas c, float top) {
                fill.setColor(accent != 0 ? blend(accent, 0.13f) : light);
                c.drawRect(M, top + 8, W - M, top + h, fill);
                fill.setColor(accent != 0 ? accent : o.accent);
                c.drawRect(W - M - 3, top + 8, W - M, top + h, fill);
                drawLayout(c, l, M + PAD, top + 8 + PAD);
            }
        };
        b.h = l.getHeight() + 8 + 2 * PAD;
        b.keepWithNext = true;
        return b;
    }

    private Block kv(final Report.Kv kv) {
        final float boxW = Math.min(CW, 330f);
        final float labelW = boxW * 0.58f;
        final StaticLayout lab = layout(kv.label, kv.emphasis ? bold : text, labelW - 2 * PAD, Layout.Alignment.ALIGN_NORMAL);
        final StaticLayout val = layout(Report.text(kv.value), kv.emphasis ? bold : text, boxW - labelW - 2 * PAD, Layout.Alignment.ALIGN_CENTER);
        Block b = new Block() {
            @Override void draw(Canvas c, float top) {
                float right = W - M, left = right - boxW;
                fill.setColor(kv.emphasis ? total : Color.rgb(246, 246, 246));
                c.drawRect(right - labelW, top, right, top + h, fill);
                if (kv.emphasis) {
                    fill.setColor(total);
                    c.drawRect(left, top, right - labelW, top + h, fill);
                }
                drawLayout(c, lab, right - labelW + PAD, top + PAD);
                drawLayout(c, val, left + PAD, top + PAD);
                c.drawLine(left, top + h, right, top + h, line);
                c.drawLine(left, top, left, top + h, line);
                c.drawLine(right, top, right, top + h, line);
                c.drawLine(right - labelW, top, right - labelW, top + h, line);
            }
        };
        b.h = Math.max(lab.getHeight(), val.getHeight()) + 2 * PAD;
        return b;
    }

    private Block note(String s) {
        final StaticLayout l = layout(s, muted, CW, Layout.Alignment.ALIGN_NORMAL);
        Block b = new Block() {
            @Override void draw(Canvas c, float top) { drawLayout(c, l, M, top + 4); }
        };
        b.h = l.getHeight() + 8;
        return b;
    }

    private float[] widths(List<Report.Col> cols) {
        float sum = 0;
        for (Report.Col c : cols) sum += c.weight;
        float[] w = new float[cols.size()];
        for (int i = 0; i < w.length; i++) w[i] = CW * cols.get(i).weight / sum;
        return w;
    }

    /** صف جدول. الأعمدة تُرسم من اليمين لليسار (العمود الأول في أقصى اليمين). */
    private Block row(final List<Report.Col> cols, final float[] w, Object[] cells, final int style, final boolean isHeader, final int accent, final int tint) {
        final StaticLayout[] ls = new StaticLayout[cols.size()];
        float hh = 0;
        // أرقام الصف بلون عملته (أغمق قليلًا للوضوح)
        TextPaint tintText = null;
        if (tint != 0 && !isHeader && style != Report.MUTED) {
            tintText = new TextPaint(style == Report.BOLD || style == Report.TOTAL ? bold : text);
            tintText.setColor(darker(tint));
            tintText.setTypeface(Typeface.DEFAULT_BOLD);
        }
        for (int i = 0; i < cols.size(); i++) {
            Object v = i < cells.length ? cells[i] : "";
            String s = isHeader ? cols.get(i).title : Report.text(v);
            boolean num = v instanceof Report.Money || v instanceof Report.Bal;
            TextPaint p = isHeader ? white : (tintText != null && num ? tintText : (style == Report.MUTED ? muted : (style == Report.BOLD || style == Report.TOTAL ? bold : text)));
            Layout.Alignment al = isHeader || cols.get(i).type != Report.TEXT ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
            ls[i] = layout(s, p, w[i] - 2 * PAD, al);
            hh = Math.max(hh, ls[i].getHeight());
        }
        Block b = new Block() {
            @Override void draw(Canvas c, float top) {
                int base = accent != 0 ? accent : o.accent;
                int bg = isHeader ? base : (style == Report.TOTAL ? (accent != 0 || tint != 0 ? blend(tint != 0 ? tint : accent, 0.22f) : total)
                        : tint != 0 ? blend(tint, style == Report.SHADED ? 0.15f : 0.08f) : (style == Report.SHADED ? shade : Color.WHITE));
                if (bg != Color.WHITE) {
                    fill.setColor(bg);
                    c.drawRect(M, top, W - M, top + h, fill);
                }
                if (tint != 0 && !isHeader) {
                    // شريط بلون العملة في بداية الصف
                    fill.setColor(tint);
                    c.drawRect(W - M - 3, top, W - M, top + h, fill);
                }
                float x = W - M;
                for (int i = 0; i < ls.length; i++) {
                    float left = x - w[i];
                    drawLayout(c, ls[i], left + PAD, top + PAD);
                    c.drawLine(left, top, left, top + h, line);
                    x = left;
                }
                c.drawLine(W - M, top, W - M, top + h, line);
                c.drawLine(M, top + h, W - M, top + h, line);
                if (isHeader) c.drawLine(M, top, W - M, top, line);
            }
        };
        b.h = hh + 2 * PAD;
        return b;
    }

    private Block image(final long id, float maxH) {
        if (o.db == null) return null;
        byte[] raw = o.db.attachment(id);
        int[] sz = ImageUtil.size(raw);
        if (sz == null) return note("(صورة رقم " + id + " غير قابلة للعرض)");
        float maxW = Math.min(o.style.imageWidth(), CW - 40);
        float scale = Math.min(maxW / sz[0], Math.min(maxH, 420f) / sz[1]);
        final float iw = sz[0] * scale, ih = sz[1] * scale;
        Block b = new Block() {
            @Override void draw(Canvas c, float top) {
                Bitmap bm = ImageUtil.decodeSampled(o.db.attachment(id), (int) Math.max(600, iw * 2.5f));
                if (bm == null) return;
                RectF dst = new RectF(W - M - 20 - iw, top + 4, W - M - 20, top + 4 + ih);
                c.drawBitmap(bm, new Rect(0, 0, bm.getWidth(), bm.getHeight()), dst, null);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(Color.rgb(200, 200, 200));
                p.setStrokeWidth(0.6f);
                c.drawRect(dst, p);
                bm.recycle();
            }
        };
        b.h = ih + 10;
        return b;
    }

    private Block signature() {
        final StaticLayout a = layout("توقيع المحاسب: ..............................", text, CW / 2 - 10, Layout.Alignment.ALIGN_NORMAL);
        final StaticLayout bb = layout("توقيع المكتب: ..............................", text, CW / 2 - 10, Layout.Alignment.ALIGN_NORMAL);
        Block b = new Block() {
            @Override void draw(Canvas c, float top) {
                drawLayout(c, a, W / 2 + 5, top + 26);
                drawLayout(c, bb, M, top + 26);
            }
        };
        b.h = 26 + Math.max(a.getHeight(), bb.getHeight()) + 10;
        return b;
    }

    private List<Block> build() {
        List<Block> out = new ArrayList<>();
        out.add(header());
        float maxImg = H - 2 * M - FOOTER - RUNNING - 60;
        for (Report.Section s : r.sections) {
            if (s.heading != null && !s.heading.isEmpty()) out.add(band(s.heading, s.accent));
            for (int i = 0; i < s.summary.size(); i++) {
                Block k = kv(s.summary.get(i));
                if (i == 0) {
                    Block spacer = new Block() {
                        @Override void draw(Canvas c, float top) {}
                    };
                    spacer.h = 6;
                    spacer.keepWithNext = true;
                    out.add(spacer);
                }
                k.keepWithNext = i < s.summary.size() - 1;
                out.add(k);
            }
            if (s.note != null && !s.note.isEmpty()) out.add(note(s.note));
            if (!s.cols.isEmpty() && !s.rows.isEmpty()) {
                float[] w = widths(s.cols);
                Block spacer = new Block() {
                    @Override void draw(Canvas c, float top) {}
                };
                spacer.h = 6;
                spacer.keepWithNext = true;
                out.add(spacer);
                Block head = row(s.cols, w, new Object[0], Report.NORMAL, true, s.accent, 0);
                head.keepWithNext = true;
                out.add(head);
                for (Report.Row rw : s.rows) {
                    Block b = row(s.cols, w, rw.cells, rw.style, false, s.accent, rw.tint);
                    b.repeatHeader = head;
                    out.add(b);
                    if (rw.images != null && o.db != null && o.style != null) {
                        for (Long id : rw.images) {
                            Block im = image(id, maxImg);
                            if (im != null) {
                                im.repeatHeader = head;
                                out.add(im);
                            }
                        }
                    }
                }
            }
        }
        if (r.signature) out.add(signature());
        return out;
    }

    private List<List<Block>> paginate(List<Block> blocks) {
        List<List<Block>> pages = new ArrayList<>();
        List<Block> page = new ArrayList<>();
        float avail = H - 2 * M - FOOTER;
        float used = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            float need = b.h;
            int j = i;
            while (blocks.get(j).keepWithNext && j + 1 < blocks.size() && need < avail * 0.5f) {
                j++;
                need += blocks.get(j).h;
            }
            if (used + need > avail && !page.isEmpty()) {
                pages.add(page);
                page = new ArrayList<>();
                avail = H - 2 * M - FOOTER - RUNNING;
                used = 0;
                if (b.repeatHeader != null) {
                    page.add(b.repeatHeader);
                    used += b.repeatHeader.h;
                }
            }
            page.add(b);
            used += b.h;
        }
        if (!page.isEmpty()) pages.add(page);
        return pages;
    }

    private void drawPage(Canvas c, List<Block> blocks, int index, int count) {
        c.drawColor(Color.WHITE);
        float y = M;
        if (index > 0) {
            StaticLayout run = layout(r.title, small, CW, Layout.Alignment.ALIGN_NORMAL);
            drawLayout(c, run, M, y);
            c.drawLine(M, y + RUNNING - 6, W - M, y + RUNNING - 6, line);
            y += RUNNING;
        }
        for (Block b : blocks) {
            b.draw(c, y);
            y += b.h;
        }
        List<String> foot = new ArrayList<>();
        foot.add("صفحة " + (index + 1) + " من " + count);
        if (r.footer != null && !r.footer.trim().isEmpty()) foot.add(r.footer.trim());
        if (o.developer != null && !o.developer.isEmpty()) foot.add(o.developer);
        // 12.6: علامة المجانية بخط صغير أحمر تحت التذييل في الهامش السفلي لكل صفحة (غير مزعجة، ولا تغطي الأرقام)
        if (r.watermark != null && !r.watermark.isEmpty()) {
            TextPaint red = new TextPaint(small);
            red.setColor(Color.rgb(198, 40, 40));
            red.setTextSize(small.getTextSize() * 0.92f);
            drawLayout(c, layout(r.watermark, red, CW, Layout.Alignment.ALIGN_CENTER), M, H - M + 2);
        }
        c.drawLine(M, H - M - FOOTER + 4, W - M, H - M - FOOTER + 4, line);
        drawLayout(c, layout(Model.join(foot, "   •   "), small, CW, Layout.Alignment.ALIGN_CENTER), M, H - M - FOOTER + 8);
    }

    // ---------------------------------------------------------------------------- الواجهات العامة

    public static File pdf(Report r, Opts o, File out) throws IOException {
        ReportPdf ex = new ReportPdf(r, o);
        List<List<Block>> pages = ex.paginate(ex.build());
        PdfDocument doc = new PdfDocument();
        try {
            for (int i = 0; i < pages.size(); i++) {
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) ex.W, (int) ex.H, i + 1).create());
                ex.drawPage(page.getCanvas(), pages.get(i), i, pages.size());
                doc.finishPage(page);
            }
            try (OutputStream os = new FileOutputStream(out)) {
                doc.writeTo(os);
            }
        } finally {
            doc.close();
        }
        return out;
    }

    public static List<File> png(Report r, Opts o, File dir, String base) throws IOException {
        ReportPdf ex = new ReportPdf(r, o);
        List<List<Block>> pages = ex.paginate(ex.build());
        List<File> files = new ArrayList<>();
        float scale = 2f;
        for (int i = 0; i < pages.size(); i++) {
            Bitmap bm = Bitmap.createBitmap((int) (ex.W * scale), (int) (ex.H * scale), Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(bm);
                c.scale(scale, scale);
                ex.drawPage(c, pages.get(i), i, pages.size());
                File f = new File(dir, base + (pages.size() > 1 ? "-" + (i + 1) : "") + ".png");
                try (OutputStream os = new FileOutputStream(f)) {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                files.add(f);
            } finally {
                bm.recycle();
            }
        }
        return files;
    }

    /** صورة الصفحة الأولى للمعاينة في شاشة خيارات التصميم. */
    public static Bitmap preview(Report r, Opts o, int widthPx) {
        ReportPdf ex = new ReportPdf(r, o);
        List<List<Block>> pages = ex.paginate(ex.build());
        float scale = widthPx / ex.W;
        Bitmap bm = Bitmap.createBitmap(widthPx, (int) (ex.H * scale), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(scale, scale);
        ex.drawPage(c, pages.isEmpty() ? new ArrayList<Block>() : pages.get(0), 0, Math.max(1, pages.size()));
        return bm;
    }
}
