package android.graphics.pdf;

import android.graphics.Canvas;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PdfDocument على الكمبيوتر: كل صفحة تُرسم بـ Canvas نفسه (Java2D) في سجل رسم متجه، ثم تُكتب ملف PDF حقيقيًا
 * (النصوص بخطوط مضمّنة) عبر PdfWriter.
 */
public class PdfDocument {
    public static class PageInfo {
        final int w, h, n;
        PageInfo(int w, int h, int n) { this.w = w; this.h = h; this.n = n; }
        public int getPageWidth() { return w; }
        public int getPageHeight() { return h; }
        public int getPageNumber() { return n; }
        public static class Builder {
            final int w, h, n;
            public Builder(int w, int h, int n) { this.w = w; this.h = h; this.n = n; }
            public Builder setContentRect(android.graphics.Rect r) { return this; }
            public PageInfo create() { return new PageInfo(w, h, n); }
        }
    }
    public class Page {
        final PageInfo info;
        final PdfWriter.PageCanvas pc;
        final Canvas canvas;
        Page(PageInfo i) { info = i; pc = writer.newPage(i.w, i.h); canvas = new Canvas(pc.graphics(), i.w, i.h); }
        public Canvas getCanvas() { return canvas; }
        public PageInfo getInfo() { return info; }
    }
    private final PdfWriter writer = new PdfWriter();
    private final List<PageInfo> pages = new ArrayList<>();
    public Page startPage(PageInfo i) { pages.add(i); return new Page(i); }
    public void finishPage(Page p) { p.pc.finish(); }
    public List<PageInfo> getPages() { return pages; }
    public void writeTo(OutputStream out) throws IOException { writer.write(out); }
    public void close() {}
}
