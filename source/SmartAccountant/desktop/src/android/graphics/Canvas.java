package android.graphics;

import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/** لوحة الرسم فوق Graphics2D. */
public class Canvas {
    public static final int ALL_SAVE_FLAG = 0x1F;
    public enum VertexMode { TRIANGLES }
    public enum EdgeType { BW, AA }

    protected Graphics2D g;
    private int width, height;
    private Bitmap bitmap;
    /** شفافية إضافية (شفافية العنصر وطبقاته). */
    public float alpha = 1f;

    private static final class State {
        AffineTransform t; Shape clip; float alpha;
        State(AffineTransform t, Shape clip, float alpha) { this.t = t; this.clip = clip; this.alpha = alpha; }
    }
    private final ArrayDeque<State> stack = new ArrayDeque<>();

    public Canvas() {}
    public Canvas(Bitmap b) { setBitmap(b); }
    public Canvas(Graphics2D g, int w, int h) { attach(g, w, h); }

    public void attach(Graphics2D g, int w, int h) {
        this.g = g;
        width = w;
        height = h;
        hints(g);
        stack.clear();
        alpha = 1f;
    }

    public static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        if (b != null) attach(b.img.createGraphics(), b.getWidth(), b.getHeight());
    }

    public Graphics2D graphics() { return g; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isHardwareAccelerated() { return false; }
    public void setDensity(int d) {}
    public int getDensity() { return 160; }

    // ------------------------------------------------------------------ الحفظ والتحويل
    public int save() {
        stack.push(new State(g.getTransform(), g.getClip(), alpha));
        return stack.size();
    }
    public int save(int flags) { return save(); }
    public int saveLayerAlpha(RectF b, int a) { int n = save(); alpha *= a / 255f; return n; }
    public int saveLayerAlpha(float l, float t, float r, float b, int a) { int n = save(); alpha *= a / 255f; return n; }
    public int saveLayerAlpha(RectF b, int a, int flags) { return saveLayerAlpha(b, a); }
    public int saveLayer(RectF b, Paint p) { int n = save(); if (p != null) alpha *= p.getAlpha() / 255f; return n; }
    public int saveLayer(float l, float t, float r, float b, Paint p) { return saveLayer(null, p); }
    public int saveLayer(RectF b, Paint p, int flags) { return saveLayer(b, p); }
    public void restore() {
        State s = stack.poll();
        if (s == null) return;
        g.setTransform(s.t);
        g.setClip(s.clip);
        alpha = s.alpha;
    }
    public int getSaveCount() { return stack.size() + 1; }
    public void restoreToCount(int n) { while (stack.size() + 1 > n && !stack.isEmpty()) restore(); }

    public void translate(float dx, float dy) { g.translate(dx, dy); }
    public void scale(float sx, float sy) { g.scale(sx, sy); }
    public void scale(float sx, float sy, float px, float py) { g.translate(px, py); g.scale(sx, sy); g.translate(-px, -py); }
    public void rotate(float d) { g.rotate(Math.toRadians(d)); }
    public void rotate(float d, float px, float py) { g.rotate(Math.toRadians(d), px, py); }
    public void skew(float sx, float sy) { g.shear(sx, sy); }
    public void concat(Matrix m) { if (m != null) g.transform(m.t); }
    public void setMatrix(Matrix m) { g.setTransform(m == null ? new AffineTransform() : m.t); }

    public boolean clipRect(float l, float t, float r, float b) { g.clip(new Rectangle2D.Float(l, t, Math.max(0, r - l), Math.max(0, b - t))); return true; }
    public boolean clipRect(int l, int t, int r, int b) { return clipRect((float) l, (float) t, (float) r, (float) b); }
    public boolean clipRect(RectF r) { return clipRect(r.left, r.top, r.right, r.bottom); }
    public boolean clipRect(Rect r) { return clipRect(r.left, r.top, r.right, r.bottom); }
    public boolean clipPath(Path p) { g.clip(p.p); return true; }
    public boolean clipOutRect(float l, float t, float r, float b) {
        Area a = new Area(g.getClip() == null ? new Rectangle(-100000, -100000, 200000, 200000) : g.getClip());
        a.subtract(new Area(new Rectangle2D.Float(l, t, r - l, b - t)));
        g.setClip(a);
        return true;
    }
    public boolean clipOutPath(Path p) {
        Area a = new Area(g.getClip() == null ? new Rectangle(-100000, -100000, 200000, 200000) : g.getClip());
        a.subtract(new Area(p.p));
        g.setClip(a);
        return true;
    }
    public boolean getClipBounds(Rect r) {
        Rectangle b = g.getClipBounds();
        if (b == null) { r.set(0, 0, width, height); return true; }
        r.set(b.x, b.y, b.x + b.width, b.y + b.height);
        return !b.isEmpty();
    }
    public Rect getClipBounds() { Rect r = new Rect(); getClipBounds(r); return r; }
    public boolean quickReject(float l, float t, float r, float b, EdgeType e) {
        Rectangle c = g.getClipBounds();
        return c != null && !c.intersects(l, t, r - l, b - t);
    }

    // ------------------------------------------------------------------ الرسم
    public void drawColor(int c) { fillAll(c); }
    public void drawColor(int c, PorterDuff.Mode m) {
        if (m == PorterDuff.Mode.CLEAR) {
            Composite o = g.getComposite();
            g.setComposite(AlphaComposite.Clear);
            g.fill(clipOrAll());
            g.setComposite(o);
        } else fillAll(c);
    }
    public void drawRGB(int r, int gg, int b) { fillAll(Color.rgb(r, gg, b)); }
    public void drawARGB(int a, int r, int gg, int b) { fillAll(Color.argb(a, r, gg, b)); }
    public void drawPaint(Paint p) { shape(clipOrAll(), p); }

    private Shape clipOrAll() {
        Shape c = g.getClip();
        if (c != null) return c;
        try { return g.getTransform().createInverse().createTransformedShape(new Rectangle(0, 0, Math.max(width, 4000), Math.max(height, 4000))); }
        catch (Exception e) { return new Rectangle(0, 0, 4000, 4000); }
    }
    private void fillAll(int c) {
        g.setPaint(new java.awt.Color(Color.red(c), Color.green(c), Color.blue(c), Math.round(Color.alpha(c) * alpha)));
        g.fill(clipOrAll());
    }

    public void drawRect(float l, float t, float r, float b, Paint p) { shape(new Rectangle2D.Float(l, t, r - l, b - t), p); }
    public void drawRect(RectF r, Paint p) { drawRect(r.left, r.top, r.right, r.bottom, p); }
    public void drawRect(Rect r, Paint p) { drawRect(r.left, r.top, r.right, r.bottom, p); }
    public void drawRoundRect(RectF r, float rx, float ry, Paint p) { drawRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, p); }
    public void drawRoundRect(float l, float t, float r, float b, float rx, float ry, Paint p) {
        shape(new RoundRectangle2D.Float(l, t, r - l, b - t, Math.min(2 * rx, r - l), Math.min(2 * ry, b - t)), p);
    }
    public void drawCircle(float cx, float cy, float rad, Paint p) { shape(new Ellipse2D.Float(cx - rad, cy - rad, 2 * rad, 2 * rad), p); }
    public void drawOval(RectF r, Paint p) { shape(new Ellipse2D.Float(r.left, r.top, r.width(), r.height()), p); }
    public void drawOval(float l, float t, float r, float b, Paint p) { shape(new Ellipse2D.Float(l, t, r - l, b - t), p); }
    public void drawArc(RectF o, float start, float sweep, boolean useCenter, Paint p) {
        drawArc(o.left, o.top, o.right, o.bottom, start, sweep, useCenter, p);
    }
    public void drawArc(float l, float t, float r, float b, float start, float sweep, boolean useCenter, Paint p) {
        shape(new Arc2D.Float(l, t, r - l, b - t, -start, -sweep, useCenter ? Arc2D.PIE : Arc2D.OPEN), p);
    }
    public void drawPath(Path path, Paint p) { shape(path.p, p); }
    public void drawLine(float x0, float y0, float x1, float y1, Paint p) {
        Paint q = p;
        if (p.style == Paint.Style.FILL) { q = new Paint(p); q.style = Paint.Style.STROKE; }
        shape(new Line2D.Float(x0, y0, x1, y1), q);
    }
    public void drawLines(float[] pts, Paint p) {
        for (int i = 0; i + 3 < pts.length; i += 4) drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], p);
    }
    public void drawPoint(float x, float y, Paint p) {
        float r = Math.max(0.5f, p.strokeWidth / 2);
        Paint q = new Paint(p);
        q.style = Paint.Style.FILL;
        if (p.cap == Paint.Cap.ROUND) shape(new Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r), q);
        else shape(new Rectangle2D.Float(x - r, y - r, 2 * r, 2 * r), q);
    }

    /** يرسم شكلًا بالفرشاة (تعبئة/خط، تدرج، ضبابية، ظل). */
    public void shape(Shape s, Paint p) {
        if (p.xfermode instanceof PorterDuffXfermode && ((PorterDuffXfermode) p.xfermode).mode == PorterDuff.Mode.CLEAR) {
            Composite o = g.getComposite();
            g.setComposite(AlphaComposite.Clear);
            g.fill(s);
            g.setComposite(o);
            return;
        }
        Shape fillShape = null, strokeShape = null;
        if (p.style == Paint.Style.FILL || p.style == Paint.Style.FILL_AND_STROKE) fillShape = s;
        if (p.style == Paint.Style.STROKE || p.style == Paint.Style.FILL_AND_STROKE) strokeShape = p.awtStroke().createStrokedShape(s);
        if (p.shadowR > 0 && Color.alpha(p.shadowColor) > 0) {
            Area a = new Area();
            if (fillShape != null) a.add(new Area(fillShape));
            if (strokeShape != null) a.add(new Area(strokeShape));
            Shape sh = AffineTransform.getTranslateInstance(p.shadowDx, p.shadowDy).createTransformedShape(a);
            int sc = p.shadowColor;
            blurred(sh, new java.awt.Color(Color.red(sc), Color.green(sc), Color.blue(sc), Math.round(Color.alpha(sc) * alpha)), p.shadowR);
        }
        java.awt.Paint paint = p.awtPaint(alpha);
        float blur = p.maskFilter instanceof BlurMaskFilter ? ((BlurMaskFilter) p.maskFilter).radius : 0;
        for (Shape x : new Shape[]{fillShape, strokeShape}) {
            if (x == null) continue;
            if (blur > 0.5f) blurred(x, paint, blur);
            else {
                g.setPaint(paint);
                g.fill(x);
            }
        }
    }

    /** ضبابية حقيقية: يُرسم الشكل في صورة صغيرة ثم تُطمس وتُلصق. */
    public void blurred(Shape s, java.awt.Paint paint, float radius) {
        AffineTransform t = g.getTransform();
        Shape dev = t.createTransformedShape(s);
        double sc = Math.sqrt(Math.abs(t.getDeterminant()));
        int r = (int) Math.ceil(radius * sc);
        Rectangle b = dev.getBounds();
        Rectangle clip = g.getClip() == null ? null : t.createTransformedShape(g.getClip()).getBounds();
        Rectangle area = new Rectangle(b.x - 2 * r, b.y - 2 * r, b.width + 4 * r, b.height + 4 * r);
        if (clip != null) area = area.intersection(new Rectangle(clip.x - 2 * r, clip.y - 2 * r, clip.width + 4 * r, clip.height + 4 * r));
        if (area.width <= 0 || area.height <= 0 || (long) area.width * area.height > 6_000_000L) return;
        // نرسم بدقة أقل للضبابية الكبيرة (أسرع، والنتيجة ناعمة أصلًا)
        int down = r > 12 ? 2 : 1;
        int w = Math.max(1, area.width / down), h = Math.max(1, area.height / down);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = im.createGraphics();
        ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ig.scale(1.0 / down, 1.0 / down);
        ig.translate(-area.x, -area.y);
        ig.transform(t);
        ig.setPaint(paint);
        ig.fill(s);
        ig.dispose();
        Blur.box(im, Math.max(1, (int) Math.round(radius * sc / down / 1.6)));
        AffineTransform old = g.getTransform();
        g.setTransform(new AffineTransform());
        // الإبقاء على المقياس الأصلي للجهاز (الشاشات عالية الدقة)
        AffineTransform dt = deviceBase();
        g.setTransform(dt);
        g.drawImage(im, area.x, area.y, area.width, area.height, null);
        g.setTransform(old);
    }

    /** التحويل الأساسي للجهاز (مقياس شاشة Retina) دون تحويلات الرسم. */
    private AffineTransform deviceBase() { return new AffineTransform(); }

    // ------------------------------------------------------------------ النص
    public void drawText(String s, float x, float y, Paint p) {
        if (s == null || s.isEmpty()) return;
        TextLayout l = p.layout(s);
        if (l == null) return;
        float adv = l.getAdvance();
        if (p.align == Paint.Align.CENTER) x -= adv / 2f;
        else if (p.align == Paint.Align.RIGHT) x -= adv;
        Shape outline = null;
        if (p.style != Paint.Style.FILL || p.maskFilter != null || p.shadowR > 0) {
            outline = l.getOutline(AffineTransform.getTranslateInstance(x, y));
            shape(outline, p);
            return;
        }
        g.setPaint(p.awtPaint(alpha));
        l.draw(g, x, y);
    }
    public void drawText(CharSequence s, int a, int b, float x, float y, Paint p) { drawText(s.toString().substring(a, b), x, y, p); }
    public void drawText(String s, int a, int b, float x, float y, Paint p) { drawText(s.substring(a, b), x, y, p); }
    public void drawText(char[] c, int i, int n, float x, float y, Paint p) { drawText(new String(c, i, n), x, y, p); }
    public void drawTextOnPath(String s, Path path, float h, float v, Paint p) {}

    // ------------------------------------------------------------------ الصور
    public void drawBitmap(Bitmap b, float x, float y, Paint p) {
        if (b == null) return;
        Composite o = withAlpha(p);
        g.drawImage(b.img, Math.round(x), Math.round(y), null);
        g.setComposite(o);
    }
    public void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {
        if (b == null) return;
        Rect s = src == null ? new Rect(0, 0, b.getWidth(), b.getHeight()) : src;
        Composite o = withAlpha(p);
        AffineTransform old = g.getTransform();
        g.translate(dst.left, dst.top);
        g.scale(dst.width() / Math.max(1, s.width()), dst.height() / Math.max(1, s.height()));
        g.drawImage(b.img, 0, 0, s.width(), s.height(), s.left, s.top, s.right, s.bottom, null);
        g.setTransform(old);
        g.setComposite(o);
    }
    public void drawBitmap(Bitmap b, Rect src, Rect dst, Paint p) { drawBitmap(b, src, new RectF(dst), p); }
    public void drawBitmap(Bitmap b, Matrix m, Paint p) {
        if (b == null) return;
        Composite o = withAlpha(p);
        g.drawImage(b.img, m == null ? new AffineTransform() : m.t, null);
        g.setComposite(o);
    }

    private Composite withAlpha(Paint p) {
        Composite o = g.getComposite();
        float a = alpha * (p == null ? 1f : p.getAlpha() / 255f);
        if (a < 0.999f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, a))));
        return o;
    }

    /** رسم صورة Java2D مباشرة (للعناصر الداخلية). */
    public void drawImage(java.awt.Image im, int x, int y, int w, int h) {
        Composite o = g.getComposite();
        if (alpha < 0.999f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, alpha)));
        g.drawImage(im, x, y, w, h, null);
        g.setComposite(o);
    }

    public void release() { if (bitmap != null && g != null) g.dispose(); }
}
