package com.smart.hawala.desk;

import com.smart.hawala.Db;
import com.smart.hawala.ExportStyle;
import com.smart.hawala.Model;
import com.smart.hawala.Report;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;

/**
 * يرسم أي تقرير (Report) كصفحات A4 بالتصميم نفسه الذي في الهاتف (ReportPdf): رأس واسم المكتب، ملخص لكل عملة،
 * جداول بالتفاف النص العربي وتكرار رأس الجدول، الصور تحت حركاتها، خانات التوقيع، وترقيم الصفحات.
 * المخرجات: PDF (صفحات بدقة عالية)، أو صور PNG، أو الطباعة عبر نظام الماك (ومنها «حفظ PDF» بنص قابل للتحديد).
 */
final class Pdf {
    static final class Opts {
        ExportStyle style = new ExportStyle();
        Color accent = Ui.primary;
        String developer = "";
        Db db;
    }

    private abstract static class Block {
        float h;
        Block repeatHeader;
        boolean keepWithNext;

        abstract void draw(Graphics2D g, float top);
    }

    static final int RIGHT = 0, CENTER = 1;

    /** نص ملتف من اليمين لليسار. */
    static final class TB {
        final List<TextLayout> lines = new ArrayList<>();
        final List<Float> gaps = new ArrayList<>();
        final float width;
        final int align;
        final Color color;
        float h;

        TB(String s, Font f, Color color, float width, int align) {
            this.width = Math.max(8, width);
            this.align = align;
            this.color = color;
            FontRenderContext frc = new FontRenderContext(null, true, true);
            String t = clean(s == null ? "" : s);
            for (String p : t.split("\n", -1)) {
                if (p.isEmpty()) {
                    lines.add(null);
                    float lh = f.getSize2D() * 1.2f;
                    gaps.add(lh);
                    h += lh;
                    continue;
                }
                AttributedString as = new AttributedString(p);
                as.addAttribute(TextAttribute.FONT, f);
                as.addAttribute(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL);
                LineBreakMeasurer m = new LineBreakMeasurer(as.getIterator(), frc);
                while (m.getPosition() < p.length()) {
                    TextLayout l = m.nextLayout(this.width);
                    if (l == null) break;
                    lines.add(l);
                    float lh = (l.getAscent() + l.getDescent() + l.getLeading()) * 1.06f;
                    gaps.add(lh);
                    h += lh;
                }
            }
        }

        void draw(Graphics2D g, float x, float y) {
            g.setColor(color);
            float yy = y;
            for (int i = 0; i < lines.size(); i++) {
                TextLayout l = lines.get(i);
                if (l != null) {
                    float adv = l.getAdvance();
                    float lx = align == CENTER ? x + (width - adv) / 2 : x + width - adv;
                    l.draw(g, lx, yy + l.getAscent());
                }
                yy += gaps.get(i);
            }
        }
    }

    /** علامات العزل الاتجاهي (\u2066…\u2069) تتحول إلى تضمين يفهمه محرك الرسم في كل إصدارات جافا. */
    static String clean(String s) {
        return s.replace('\u2066', '\u202A').replace('\u2069', '\u202C').replace('\u2067', '\u202B').replace('\u2068', '\u202A');
    }

    private final Report r;
    private final Opts o;
    final float W, H, M = 28f, FOOTER = 24f, RUNNING = 22f, PAD = 3.5f, CW;
    private final Font text, bold, small, title, heading, muted;
    private final Color light, shade, total, lineC = new Color(212, 212, 212), ink = new Color(35, 35, 35), mutedC = new Color(140, 140, 140);

    Pdf(Report r, Opts o) {
        this.r = r;
        this.o = o;
        boolean land = o.style.landscape;
        W = land ? 842f : 595f;
        H = land ? 595f : 842f;
        CW = W - 2 * M;
        float s = o.style.fontScale();
        text = Ui.font(8.5f * s, false);
        bold = Ui.font(8.8f * s, true);
        small = Ui.font(7.4f * s, false);
        muted = Ui.font(8.2f * s, false);
        title = Ui.font(15f * s, true);
        heading = Ui.font(10.5f * s, true);
        light = blend(o.accent, 0.13f);
        shade = new Color(248, 247, 243);
        total = blend(o.accent, 0.22f);
    }

    static Color blend(Color c, float a) {
        return new Color((int) (255 - (255 - c.getRed()) * a), (int) (255 - (255 - c.getGreen()) * a), (int) (255 - (255 - c.getBlue()) * a));
    }

    static Color darker(Color c) { return new Color((int) (c.getRed() * 0.8f), (int) (c.getGreen() * 0.8f), (int) (c.getBlue() * 0.8f)); }

    static Color argb(int c) { return c == 0 ? null : new Color(c, false); }

    private void hline(Graphics2D g, float x1, float y, float x2) {
        g.setColor(lineC);
        g.setStroke(new BasicStroke(0.6f));
        g.draw(new Line2D.Float(x1, y, x2, y));
    }

    private void vline(Graphics2D g, float x, float y1, float y2) {
        g.setColor(lineC);
        g.setStroke(new BasicStroke(0.6f));
        g.draw(new Line2D.Float(x, y1, x, y2));
    }

    // ---------------------------------------------------------------------------- الكتل

    private Block header() {
        final BufferedImage logo = o.style.logo ? Main.icon(160) : null;
        final float logoSize = logo != null ? 50f : 0f;
        final float gap = logoSize > 0 ? 10f : 0f;
        final TB t = new TB(r.title, title, o.accent, CW - logoSize - gap, RIGHT);
        final TB lines = new TB(Model.join(r.headerLines, "\n"), small, new Color(110, 110, 110), CW - logoSize - gap, RIGHT);
        Block b = new Block() {
            @Override void draw(Graphics2D g, float top) {
                if (logo != null) g.drawImage(logo, (int) (W - M - logoSize), (int) top, (int) logoSize, (int) logoSize, null);
                t.draw(g, M, top);
                lines.draw(g, M, top + t.h + 4);
                g.setColor(o.accent);
                g.setStroke(new BasicStroke(1.6f));
                g.draw(new Line2D.Float(M, top + h - 5, W - M, top + h - 5));
            }
        };
        b.h = Math.max(logoSize, t.h + 4 + lines.h) + 12;
        return b;
    }

    private Block band(String s, final int accentInt) {
        final Color accent = argb(accentInt);
        final TB l = new TB(s, heading, accent != null ? darker(accent) : darker(o.accent), CW - 2 * PAD - 4, RIGHT);
        Block b = new Block() {
            @Override void draw(Graphics2D g, float top) {
                g.setColor(accent != null ? blend(accent, 0.13f) : light);
                g.fill(new Rectangle2D.Float(M, top + 8, CW, h - 8));
                g.setColor(accent != null ? accent : o.accent);
                g.fill(new Rectangle2D.Float(W - M - 3, top + 8, 3, h - 8));
                l.draw(g, M + PAD, top + 8 + PAD);
            }
        };
        b.h = l.h + 8 + 2 * PAD;
        b.keepWithNext = true;
        return b;
    }

    private Block kv(final Report.Kv kv) {
        final float boxW = Math.min(CW, 360f);
        final float labelW = boxW * 0.52f;
        final TB lab = new TB(kv.label, kv.emphasis ? bold : text, ink, labelW - 2 * PAD, RIGHT);
        final TB val = new TB(Report.text(kv.value), kv.emphasis ? bold : text, ink, boxW - labelW - 2 * PAD, CENTER);
        Block b = new Block() {
            @Override void draw(Graphics2D g, float top) {
                float right = W - M, left = right - boxW;
                g.setColor(kv.emphasis ? total : new Color(246, 246, 246));
                g.fill(new Rectangle2D.Float(right - labelW, top, labelW, h));
                if (kv.emphasis) {
                    g.setColor(total);
                    g.fill(new Rectangle2D.Float(left, top, boxW - labelW, h));
                }
                lab.draw(g, right - labelW + PAD, top + PAD);
                val.draw(g, left + PAD, top + PAD);
                hline(g, left, top + h, right);
                vline(g, left, top, top + h);
                vline(g, right, top, top + h);
                vline(g, right - labelW, top, top + h);
            }
        };
        b.h = Math.max(lab.h, val.h) + 2 * PAD;
        return b;
    }

    private Block note(String s) {
        final TB l = new TB(s, muted, mutedC, CW, RIGHT);
        Block b = new Block() {
            @Override void draw(Graphics2D g, float top) { l.draw(g, M, top + 4); }
        };
        b.h = l.h + 8;
        return b;
    }

    private float[] widths(List<Report.Col> cols) {
        float sum = 0;
        for (Report.Col c : cols) sum += c.weight;
        float[] w = new float[cols.size()];
        for (int i = 0; i < w.length; i++) w[i] = CW * cols.get(i).weight / sum;
        return w;
    }

    private Block row(final List<Report.Col> cols, final float[] w, Object[] cells, final int style, final boolean isHeader, final int accentInt, final int tintInt) {
        final Color accent = argb(accentInt), tint = argb(tintInt);
        final TB[] ls = new TB[cols.size()];
        float hh = 0;
        for (int i = 0; i < cols.size(); i++) {
            Object v = i < cells.length ? cells[i] : "";
            String s = isHeader ? cols.get(i).title : Report.text(v);
            boolean num = v instanceof Report.Money || v instanceof Report.Bal;
            Font f = isHeader ? bold : (style == Report.BOLD || style == Report.TOTAL || (tint != null && num)) ? bold : style == Report.MUTED ? muted : text;
            Color c = isHeader ? Color.WHITE : style == Report.MUTED ? mutedC : (tint != null && num) ? darker(tint) : ink;
            int al = isHeader || cols.get(i).type != Report.TEXT ? CENTER : RIGHT;
            ls[i] = new TB(s, f, c, w[i] - 2 * PAD, al);
            hh = Math.max(hh, ls[i].h);
        }
        Block b = new Block() {
            @Override void draw(Graphics2D g, float top) {
                Color base = accent != null ? accent : o.accent;
                Color bg = isHeader ? base : (style == Report.TOTAL ? (accent != null || tint != null ? blend(tint != null ? tint : accent, 0.22f) : total)
                        : tint != null ? blend(tint, style == Report.SHADED ? 0.15f : 0.08f) : (style == Report.SHADED ? shade : Color.WHITE));
                if (!Color.WHITE.equals(bg)) {
                    g.setColor(bg);
                    g.fill(new Rectangle2D.Float(M, top, CW, h));
                }
                if (tint != null && !isHeader) {
                    g.setColor(tint);
                    g.fill(new Rectangle2D.Float(W - M - 3, top, 3, h));
                }
                float x = W - M;
                for (int i = 0; i < ls.length; i++) {
                    float left = x - w[i];
                    ls[i].draw(g, left + PAD, top + PAD);
                    vline(g, left, top, top + h);
                    x = left;
                }
                vline(g, W - M, top, top + h);
                hline(g, M, top + h, W - M);
                if (isHeader) hline(g, M, top, W - M);
            }
        };
        b.h = hh + 2 * PAD;
        return b;
    }

    private Block image(final long id, float maxH) {
        if (o.db == null) return null;
        BufferedImage im;
        try {
            byte[] raw = o.db.attachment(id);
            im = raw == null ? null : ImageIO.read(new java.io.ByteArrayInputStream(raw));
        } catch (Exception e) {
            im = null;
        }
        if (im == null) return note("(صورة رقم " + id + " غير قابلة للعرض)");
        float maxW = Math.min(o.style.imageWidth(), CW - 40);
        float scale = Math.min(maxW / im.getWidth(), Math.min(maxH, 420f) / im.getHeight());
        final float iw = im.getWidth() * scale, ih = im.getHeight() * scale;
        final BufferedImage img = im;
        Block b = new Block() {
            @Override void draw(Graphics2D g, float top) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img, (int) (W - M - 20 - iw), (int) (top + 4), (int) iw, (int) ih, null);
                g.setColor(new Color(200, 200, 200));
                g.setStroke(new BasicStroke(0.6f));
                g.draw(new Rectangle2D.Float(W - M - 20 - iw, top + 4, iw, ih));
            }
        };
        b.h = ih + 10;
        return b;
    }

    private Block signature() {
        final TB a = new TB("توقيع المحاسب: ..............................", text, ink, CW / 2 - 10, RIGHT);
        final TB bb = new TB("توقيع المكتب: ..............................", text, ink, CW / 2 - 10, RIGHT);
        Block b = new Block() {
            @Override void draw(Graphics2D g, float top) {
                a.draw(g, W / 2 + 5, top + 26);
                bb.draw(g, M, top + 26);
            }
        };
        b.h = 26 + Math.max(a.h, bb.h) + 10;
        return b;
    }

    private Block spacer(float h) {
        Block s = new Block() {
            @Override void draw(Graphics2D g, float top) {}
        };
        s.h = h;
        s.keepWithNext = true;
        return s;
    }

    private List<Block> build() {
        List<Block> out = new ArrayList<>();
        out.add(header());
        float maxImg = H - 2 * M - FOOTER - RUNNING - 60;
        for (Report.Section s : r.sections) {
            if (s.heading != null && !s.heading.isEmpty()) out.add(band(s.heading, s.accent));
            for (int i = 0; i < s.summary.size(); i++) {
                if (i == 0) out.add(spacer(6));
                Block k = kv(s.summary.get(i));
                k.keepWithNext = i < s.summary.size() - 1;
                out.add(k);
            }
            if (s.note != null && !s.note.isEmpty()) out.add(note(s.note));
            if (!s.cols.isEmpty() && !s.rows.isEmpty()) {
                float[] w = widths(s.cols);
                out.add(spacer(6));
                Block head = row(s.cols, w, new Object[0], Report.NORMAL, true, s.accent, 0);
                head.keepWithNext = true;
                out.add(head);
                for (Report.Row rw : s.rows) {
                    Block b = row(s.cols, w, rw.cells, rw.style, false, s.accent, rw.tint);
                    b.repeatHeader = head;
                    out.add(b);
                    if (rw.images != null && o.db != null) {
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

    List<List<Block>> pages() { return paginate(build()); }

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

    void drawPage(Graphics2D g, List<Block> blocks, int index, int count) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Float(0, 0, W, H));
        float y = M;
        if (index > 0) {
            new TB(r.title, small, new Color(110, 110, 110), CW, RIGHT).draw(g, M, y);
            hline(g, M, y + RUNNING - 6, W - M);
            y += RUNNING;
        }
        for (Block b : blocks) {
            b.draw(g, y);
            y += b.h;
        }
        List<String> foot = new ArrayList<>();
        foot.add("صفحة " + (index + 1) + " من " + count);
        if (r.watermark != null && !r.watermark.isEmpty()) foot.add(r.watermark);
        if (r.footer != null && !r.footer.trim().isEmpty()) foot.add(r.footer.trim());
        if (o.developer != null && !o.developer.isEmpty()) foot.add(o.developer);
        hline(g, M, H - M - FOOTER + 4, W - M);
        new TB(Model.join(foot, "   •   "), small, new Color(110, 110, 110), CW, CENTER).draw(g, M, H - M - FOOTER + 8);
    }

    BufferedImage render(List<Block> page, int index, int count, float scale) {
        BufferedImage im = new BufferedImage((int) (W * scale), (int) (H * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.scale(scale, scale);
        drawPage(g, page, index, count);
        g.dispose();
        return im;
    }

    // ---------------------------------------------------------------------------- المخرجات

    /** PDF من صور الصفحات (2.2×) مضغوطة بلا فقد؛ يفتح في أي قارئ ويُطبع كما هو. */
    static File pdf(Report r, Opts o, File out) throws IOException {
        Pdf ex = new Pdf(r, o);
        List<List<Block>> pages = ex.pages();
        float scale = 2.2f;
        try (OutputStream os = new FileOutputStream(out)) {
            PdfWriter w = new PdfWriter(os);
            for (int i = 0; i < pages.size(); i++) w.page(ex.render(pages.get(i), i, pages.size(), scale), ex.W, ex.H);
            w.finish(r.title);
        }
        return out;
    }

    static List<File> png(Report r, Opts o, File dir, String base) throws IOException {
        Pdf ex = new Pdf(r, o);
        List<List<Block>> pages = ex.pages();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            File f = new File(dir, base + (pages.size() > 1 ? "-" + (i + 1) : "") + ".png");
            ImageIO.write(ex.render(pages.get(i), i, pages.size(), 2f), "png", f);
            files.add(f);
        }
        return files;
    }

    static BufferedImage preview(Report r, Opts o, int widthPx) {
        Pdf ex = new Pdf(r, o);
        List<List<Block>> pages = ex.pages();
        return ex.render(pages.isEmpty() ? new ArrayList<>() : pages.get(0), 0, Math.max(1, pages.size()), widthPx / ex.W);
    }

    /** الطباعة عبر نافذة الماك (ومنها زر PDF بنص قابل للتحديد). */
    static boolean print(Report r, Opts o) throws Exception {
        final Pdf ex = new Pdf(r, o);
        final List<List<Block>> pages = ex.pages();
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName(r.title);
        PageFormat pf = job.defaultPage();
        Paper p = new Paper();
        p.setSize(595, 842);
        p.setImageableArea(0, 0, 595, 842);
        pf.setPaper(p);
        pf.setOrientation(o.style.landscape ? PageFormat.LANDSCAPE : PageFormat.PORTRAIT);
        job.setPrintable((g, f, i) -> {
            if (i >= pages.size()) return Printable.NO_SUCH_PAGE;
            Graphics2D g2 = (Graphics2D) g;
            g2.translate(f.getImageableX(), f.getImageableY());
            double s = Math.min(f.getImageableWidth() / ex.W, f.getImageableHeight() / ex.H);
            g2.scale(s, s);
            ex.drawPage(g2, pages.get(i), i, pages.size());
            return Printable.PAGE_EXISTS;
        }, pf);
        if (!job.printDialog()) return false;
        job.print();
        return true;
    }

    /** كاتب PDF بسيط: صفحة لكل صورة RGB مضغوطة Flate. */
    static final class PdfWriter {
        final OutputStream out;
        long pos;
        final List<Long> offsets = new ArrayList<>();
        final List<Integer> pageIds = new ArrayList<>();
        int next = 3;   // 1 = الكتالوج، 2 = شجرة الصفحات

        PdfWriter(OutputStream out) throws IOException {
            this.out = out;
            offsets.add(0L);
            offsets.add(0L);
            offsets.add(0L);
            write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        void write(byte[] b) throws IOException {
            out.write(b);
            pos += b.length;
        }

        void w(String s) throws IOException { write(s.getBytes(StandardCharsets.ISO_8859_1)); }

        int obj() {
            int id = next++;
            while (offsets.size() <= id) offsets.add(0L);
            return id;
        }

        void begin(int id) throws IOException {
            offsets.set(id, pos);
            w(id + " 0 obj\n");
        }

        void page(BufferedImage im, float pw, float ph) throws IOException {
            int iw = im.getWidth(), ih = im.getHeight();
            byte[] raw = new byte[iw * ih * 3];
            int k = 0;
            int[] row = new int[iw];
            for (int y = 0; y < ih; y++) {
                im.getRGB(0, y, iw, 1, row, 0, iw);
                for (int x = 0; x < iw; x++) {
                    int c = row[x];
                    raw[k++] = (byte) (c >> 16);
                    raw[k++] = (byte) (c >> 8);
                    raw[k++] = (byte) c;
                }
            }
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 8);
            byte[] buf = new byte[65536];
            while (!d.finished()) z.write(buf, 0, d.deflate(buf));
            d.end();
            byte[] data = z.toByteArray();
            int img = obj(), content = obj(), page = obj();
            begin(img);
            w("<< /Type /XObject /Subtype /Image /Width " + iw + " /Height " + ih + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length " + data.length + " >>\nstream\n");
            write(data);
            w("\nendstream\nendobj\n");
            String cs = "q " + f(pw) + " 0 0 " + f(ph) + " 0 0 cm /Im0 Do Q\n";
            begin(content);
            w("<< /Length " + cs.length() + " >>\nstream\n" + cs + "endstream\nendobj\n");
            begin(page);
            w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + f(pw) + " " + f(ph) + "] /Resources << /XObject << /Im0 " + img + " 0 R >> >> /Contents " + content + " 0 R >>\nendobj\n");
            pageIds.add(page);
        }

        static String f(float v) { return String.format(java.util.Locale.US, "%.2f", v); }

        void finish(String title) throws IOException {
            offsets.set(2, pos);
            StringBuilder kids = new StringBuilder();
            for (int id : pageIds) kids.append(id).append(" 0 R ");
            w("2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + pageIds.size() + " >>\nendobj\n");
            int info = obj();
            begin(info);
            w("<< /Producer (Smart Accountant for Mac) /Title <" + utf16(title) + "> >>\nendobj\n");
            offsets.set(1, pos);
            w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            long xref = pos;
            w("xref\n0 " + next + "\n0000000000 65535 f \n");
            for (int i = 1; i < next; i++) w(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets.get(i)));
            w("trailer\n<< /Size " + next + " /Root 1 0 R /Info " + info + " 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            out.flush();
        }

        static String utf16(String s) {
            StringBuilder b = new StringBuilder("FEFF");
            for (char c : (s == null ? "" : s).toCharArray()) b.append(String.format(java.util.Locale.ROOT, "%04X", (int) c));
            return b.toString();
        }
    }
}
