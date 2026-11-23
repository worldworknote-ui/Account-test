package android.graphics.pdf;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/** كاتب PDF: كل صفحة تُرسم بدقة عالية (2.5×) وتُضغط بلا فقد — كما في PDF نسخة الكمبيوتر السابقة. */
final class PdfWriter {
    static final float SCALE = 2.5f;
    private final List<PageCanvas> pages = new ArrayList<>();

    final class PageCanvas {
        final int w, h;
        final BufferedImage img;
        final Graphics2D g;
        PageCanvas(int w, int h) {
            this.w = w;
            this.h = h;
            img = new BufferedImage(Math.round(w * SCALE), Math.round(h * SCALE), BufferedImage.TYPE_INT_RGB);
            g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.scale(SCALE, SCALE);
        }
        Graphics2D graphics() { return g; }
        void finish() { g.dispose(); }
    }

    PageCanvas newPage(int w, int h) { PageCanvas p = new PageCanvas(w, h); pages.add(p); return p; }

    void write(OutputStream os) throws IOException {
        Out o = new Out(os);
        for (PageCanvas p : pages) o.page(p.img, p.w, p.h);
        o.finish();
    }

    private static final class Out {
        final OutputStream out;
        long pos;
        final List<Long> offsets = new ArrayList<>();
        final List<Integer> pageIds = new ArrayList<>();
        int next = 3;
        Out(OutputStream out) throws IOException {
            this.out = out;
            offsets.add(0L); offsets.add(0L); offsets.add(0L);
            write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        void write(byte[] b) throws IOException { out.write(b); pos += b.length; }
        void w(String s) throws IOException { write(s.getBytes(StandardCharsets.ISO_8859_1)); }
        int obj() { int id = next++; while (offsets.size() <= id) offsets.add(0L); return id; }
        void begin(int id) throws IOException { offsets.set(id, pos); w(id + " 0 obj\n"); }
        void page(BufferedImage im, float pw, float ph) throws IOException {
            int iw = im.getWidth(), ih = im.getHeight();
            byte[] raw = new byte[iw * ih * 3];
            int k = 0;
            int[] row = new int[iw];
            for (int y = 0; y < ih; y++) {
                im.getRGB(0, y, iw, 1, row, 0, iw);
                for (int x = 0; x < iw; x++) { int c = row[x]; raw[k++] = (byte) (c >> 16); raw[k++] = (byte) (c >> 8); raw[k++] = (byte) c; }
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
        void finish() throws IOException {
            offsets.set(2, pos);
            StringBuilder kids = new StringBuilder();
            for (int id : pageIds) kids.append(id).append(" 0 R ");
            w("2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + pageIds.size() + " >>\nendobj\n");
            offsets.set(1, pos);
            w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            long xref = pos;
            w("xref\n0 " + next + "\n0000000000 65535 f \n");
            for (int i = 1; i < next; i++) w(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets.get(i)));
            w("trailer\n<< /Size " + next + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            out.flush();
        }
    }
}
