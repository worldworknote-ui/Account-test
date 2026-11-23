package android.graphics.drawable;

import android.graphics.*;
import java.awt.geom.*;
import java.util.*;
import org.w3c.dom.*;

/** أيقونات أندرويد الخطية (res/drawable/*.xml) تُقرأ وتُرسم مباشرة: المسارات نفسها والألوان والتلوين. */
public class VectorDrawable extends Drawable {
    static final class P {
        Path2D.Float path; int fill, stroke; float sw, fillAlpha = 1, strokeAlpha = 1; int cap, join; AffineTransform t; boolean evenOdd;
        /** 12.3: تدرج لوني (aapt:attr) بدل اللون الواحد، كما في شعار التطبيق. */
        Grad fillGrad, strokeGrad;
    }
    /** 12.3: تدرج <gradient> خطي أو دائري بإحداثيات الأيقونة. */
    static final class Grad {
        boolean radial;
        float x1, y1, x2, y2, cx, cy, r;
        float[] stops;
        int[] colors;

        java.awt.Paint paint(float a) {
            java.awt.Color[] cs = new java.awt.Color[colors.length];
            for (int i = 0; i < colors.length; i++)
                cs[i] = new java.awt.Color(Color.red(colors[i]), Color.green(colors[i]), Color.blue(colors[i]), Math.max(0, Math.min(255, Math.round(Color.alpha(colors[i]) * a))));
            if (radial) return new java.awt.RadialGradientPaint(cx, cy, Math.max(0.01f, r), stops, cs);
            if (x1 == x2 && y1 == y2) return cs[0];
            return new java.awt.LinearGradientPaint(x1, y1, x2, y2, stops, cs);
        }
    }

    private static Grad grad(Element attr) {
        NodeList gl = attr.getElementsByTagName("gradient");
        if (gl.getLength() == 0) return null;
        Element g = (Element) gl.item(0);
        Grad d = new Grad();
        d.radial = "radial".equals(g.getAttribute("android:type"));
        d.x1 = num(g.getAttribute("android:startX"), 0);
        d.y1 = num(g.getAttribute("android:startY"), 0);
        d.x2 = num(g.getAttribute("android:endX"), 0);
        d.y2 = num(g.getAttribute("android:endY"), 0);
        d.cx = num(g.getAttribute("android:centerX"), 0);
        d.cy = num(g.getAttribute("android:centerY"), 0);
        d.r = num(g.getAttribute("android:gradientRadius"), 1);
        List<float[]> st = new ArrayList<>();
        List<Integer> cl = new ArrayList<>();
        NodeList items = g.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element it = (Element) items.item(i);
            st.add(new float[]{num(it.getAttribute("android:offset"), i / (float) Math.max(1, items.getLength() - 1))});
            cl.add(color(it.getAttribute("android:color")));
        }
        if (cl.isEmpty()) {
            if (g.hasAttribute("android:startColor")) { st.add(new float[]{0}); cl.add(color(g.getAttribute("android:startColor"))); }
            if (g.hasAttribute("android:centerColor")) { st.add(new float[]{0.5f}); cl.add(color(g.getAttribute("android:centerColor"))); }
            if (g.hasAttribute("android:endColor")) { st.add(new float[]{1}); cl.add(color(g.getAttribute("android:endColor"))); }
        }
        if (cl.isEmpty()) return null;
        if (cl.size() == 1) { st.add(new float[]{1}); cl.add(cl.get(0)); }
        d.stops = new float[st.size()];
        d.colors = new int[cl.size()];
        float last = -1;
        for (int i = 0; i < st.size(); i++) {
            // الإيقافات يجب أن تزيد زيادة صارمة في جافا
            float o = Math.max(0, Math.min(1, st.get(i)[0]));
            if (o <= last) o = Math.min(1, last + 0.0001f);
            d.stops[i] = o;
            last = o;
            d.colors[i] = cl.get(i);
        }
        for (int i = 1; i < d.stops.length; i++) if (d.stops[i] <= d.stops[i - 1]) return null;
        return d;
    }

    private final List<P> paths = new ArrayList<>();
    private float vw = 24, vh = 24;
    private int iw, ih;
    private int alpha = 255;
    private int tintAttr; // تلوين من الملف نفسه
    private final String name;

    private VectorDrawable(String name) { this.name = name; }

    public static float density = 1f;
    private static final Map<String, VectorDrawable> cache = new HashMap<>();

    /** يقرأ الأيقونة من الموارد (أو null). */
    public static VectorDrawable load(String name) {
        VectorDrawable proto;
        synchronized (cache) {
            proto = cache.get(name);
            if (proto == null) {
                proto = parse(name);
                if (proto == null) return null;
                cache.put(name, proto);
            }
        }
        VectorDrawable v = new VectorDrawable(name);
        v.paths.addAll(proto.paths);
        v.vw = proto.vw; v.vh = proto.vh; v.iw = proto.iw; v.ih = proto.ih; v.tintAttr = proto.tintAttr;
        return v;
    }

    private static VectorDrawable parse(String name) {
        try (java.io.InputStream in = VectorDrawable.class.getResourceAsStream("/res/drawable/" + name + ".xml")) {
            if (in == null) return null;
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            Document d = f.newDocumentBuilder().parse(in);
            Element root = d.getDocumentElement();
            VectorDrawable v = new VectorDrawable(name);
            if (!"vector".equals(root.getTagName())) return null;
            v.vw = num(root.getAttribute("android:viewportWidth"), 24);
            v.vh = num(root.getAttribute("android:viewportHeight"), 24);
            v.iw = Math.round(num(root.getAttribute("android:width"), 24));
            v.ih = Math.round(num(root.getAttribute("android:height"), 24));
            String tint = root.getAttribute("android:tint");
            if (tint.startsWith("#")) v.tintAttr = color(tint);
            walk(v, root, new AffineTransform());
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static void walk(VectorDrawable v, Element e, AffineTransform at) {
        NodeList ch = e.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            if (!(ch.item(i) instanceof Element)) continue;
            Element c = (Element) ch.item(i);
            if ("group".equals(c.getTagName())) {
                AffineTransform t = new AffineTransform(at);
                float px = num(c.getAttribute("android:pivotX"), 0), py = num(c.getAttribute("android:pivotY"), 0);
                float tx = num(c.getAttribute("android:translateX"), 0), ty = num(c.getAttribute("android:translateY"), 0);
                float sx = num(c.getAttribute("android:scaleX"), 1), sy = num(c.getAttribute("android:scaleY"), 1);
                float rot = num(c.getAttribute("android:rotation"), 0);
                t.translate(tx + px, ty + py);
                t.rotate(Math.toRadians(rot));
                t.scale(sx, sy);
                t.translate(-px, -py);
                walk(v, c, t);
            } else if ("path".equals(c.getTagName())) {
                P p = new P();
                p.path = SvgPath.parse(c.getAttribute("android:pathData"));
                p.fill = c.hasAttribute("android:fillColor") ? color(c.getAttribute("android:fillColor")) : 0;
                p.stroke = c.hasAttribute("android:strokeColor") ? color(c.getAttribute("android:strokeColor")) : 0;
                p.sw = num(c.getAttribute("android:strokeWidth"), 0);
                p.fillAlpha = num(c.getAttribute("android:fillAlpha"), 1);
                p.strokeAlpha = num(c.getAttribute("android:strokeAlpha"), 1);
                String cap = c.getAttribute("android:strokeLineCap"), join = c.getAttribute("android:strokeLineJoin");
                p.cap = "round".equals(cap) ? java.awt.BasicStroke.CAP_ROUND : "square".equals(cap) ? java.awt.BasicStroke.CAP_SQUARE : java.awt.BasicStroke.CAP_BUTT;
                p.join = "round".equals(join) ? java.awt.BasicStroke.JOIN_ROUND : "bevel".equals(join) ? java.awt.BasicStroke.JOIN_BEVEL : java.awt.BasicStroke.JOIN_MITER;
                p.evenOdd = "evenOdd".equals(c.getAttribute("android:fillType"));
                NodeList kids = c.getChildNodes();
                for (int k = 0; k < kids.getLength(); k++) {
                    if (!(kids.item(k) instanceof Element)) continue;
                    Element ka = (Element) kids.item(k);
                    if (!"aapt:attr".equals(ka.getTagName())) continue;
                    String an = ka.getAttribute("name");
                    if ("android:fillColor".equals(an)) p.fillGrad = grad(ka);
                    else if ("android:strokeColor".equals(an)) p.strokeGrad = grad(ka);
                }
                if (p.evenOdd) p.path.setWindingRule(Path2D.WIND_EVEN_ODD);
                p.t = at;
                v.paths.add(p);
            }
        }
    }

    static float num(String s, float d) {
        if (s == null || s.isEmpty()) return d;
        s = s.replace("dp", "").replace("dip", "").replace("px", "").trim();
        try { return Float.parseFloat(s); } catch (Exception e) { return d; }
    }

    static int color(String s) {
        s = s.trim();
        if (!s.startsWith("#")) return 0xFF000000;
        String h = s.substring(1);
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        if (h.length() == 4) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2) + h.charAt(3) + h.charAt(3);
        long v = Long.parseLong(h, 16);
        return h.length() == 6 ? (int) (v | 0xFF000000L) : (int) v;
    }

    public String name() { return name; }
    @Override public int getIntrinsicWidth() { return Math.round(iw * density); }
    @Override public int getIntrinsicHeight() { return Math.round(ih * density); }
    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public Drawable mutate() { return this; }
    @Override public ConstantState getConstantState() {
        return new ConstantState() { @Override public Drawable newDrawable() { return load(name); } };
    }

    @Override public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        java.awt.Graphics2D g = c.graphics();
        AffineTransform old = g.getTransform();
        g.translate(b.left, b.top);
        g.scale(b.width() / vw, b.height() / vh);
        float a = c.alpha * alpha / 255f;
        int tc = hasTint() ? tintColor() : tintAttr;
        for (P p : paths) {
            AffineTransform keep = g.getTransform();
            if (p.t != null && !p.t.isIdentity()) g.transform(p.t);
            if (p.fillGrad != null && tc == 0) {
                g.setPaint(p.fillGrad.paint(a * p.fillAlpha));
                g.fill(p.path);
            } else if (Color.alpha(p.fill) > 0 || p.fillGrad != null) {
                g.setPaint(col(p.fillGrad != null ? p.fillGrad.colors[0] : p.fill, tc, a * p.fillAlpha));
                g.fill(p.path);
            }
            if (p.strokeGrad != null && tc == 0 && p.sw > 0) {
                g.setPaint(p.strokeGrad.paint(a * p.strokeAlpha));
                g.fill(new java.awt.BasicStroke(p.sw, p.cap, p.join, 4f).createStrokedShape(p.path));
            } else if (Color.alpha(p.stroke) > 0 && p.sw > 0) {
                g.setPaint(col(p.stroke, tc, a * p.strokeAlpha));
                g.fill(new java.awt.BasicStroke(p.sw, p.cap, p.join, 4f).createStrokedShape(p.path));
            }
            g.setTransform(keep);
        }
        g.setTransform(old);
    }

    /** التلوين بنمط SRC_IN: لون التلوين بشفافية المسار. */
    private static java.awt.Color col(int c, int tint, float a) {
        int base = tint != 0 ? tint : c;
        int al = Math.round(Color.alpha(c) * (tint != 0 ? Color.alpha(tint) / 255f : 1f) * a);
        return new java.awt.Color(Color.red(base), Color.green(base), Color.blue(base), Math.max(0, Math.min(255, al)));
    }
}
