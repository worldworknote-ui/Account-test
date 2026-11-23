package android.graphics;

import java.awt.geom.*;

/** مسار أندرويد فوق Path2D. الزوايا بالدرجات مع عقارب الساعة (y للأسفل) كما في أندرويد. */
public class Path {
    public enum Direction { CW, CCW }
    public enum FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }
    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }

    Path2D.Float p = new Path2D.Float();
    private float lx, ly;
    private boolean started;

    public Path() {}
    public Path(Path src) { set(src); }

    public void reset() { p = new Path2D.Float(p.getWindingRule()); started = false; lx = ly = 0; }
    public void rewind() { reset(); }
    public void set(Path src) { p = (Path2D.Float) src.p.clone(); lx = src.lx; ly = src.ly; started = src.started; }
    public boolean isEmpty() { return p.getCurrentPoint() == null; }

    public void setFillType(FillType f) {
        p.setWindingRule(f == FillType.EVEN_ODD || f == FillType.INVERSE_EVEN_ODD ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
    }
    public FillType getFillType() { return p.getWindingRule() == Path2D.WIND_EVEN_ODD ? FillType.EVEN_ODD : FillType.WINDING; }

    private void ensure() { if (p.getCurrentPoint() == null) { p.moveTo(0, 0); } }

    public void moveTo(float x, float y) { p.moveTo(x, y); lx = x; ly = y; started = true; }
    public void rMoveTo(float dx, float dy) { Point2D c = p.getCurrentPoint(); moveTo((c == null ? 0 : (float) c.getX()) + dx, (c == null ? 0 : (float) c.getY()) + dy); }
    public void lineTo(float x, float y) { ensure(); p.lineTo(x, y); }
    public void rLineTo(float dx, float dy) { ensure(); Point2D c = p.getCurrentPoint(); lineTo((float) c.getX() + dx, (float) c.getY() + dy); }
    public void quadTo(float x1, float y1, float x2, float y2) { ensure(); p.quadTo(x1, y1, x2, y2); }
    public void rQuadTo(float x1, float y1, float x2, float y2) {
        ensure(); Point2D c = p.getCurrentPoint(); float x = (float) c.getX(), y = (float) c.getY();
        p.quadTo(x + x1, y + y1, x + x2, y + y2);
    }
    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { ensure(); p.curveTo(x1, y1, x2, y2, x3, y3); }
    public void rCubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        ensure(); Point2D c = p.getCurrentPoint(); float x = (float) c.getX(), y = (float) c.getY();
        p.curveTo(x + x1, y + y1, x + x2, y + y2, x + x3, y + y3);
    }
    public void close() { if (p.getCurrentPoint() != null) p.closePath(); }

    public void arcTo(RectF o, float start, float sweep, boolean forceMoveTo) { arcTo(o.left, o.top, o.right, o.bottom, start, sweep, forceMoveTo); }
    public void arcTo(RectF o, float start, float sweep) { arcTo(o, start, sweep, false); }
    public void arcTo(float l, float t, float r, float b, float start, float sweep, boolean forceMoveTo) {
        Arc2D a = new Arc2D.Float(l, t, r - l, b - t, -start, -sweep, Arc2D.OPEN);
        p.append(a, !forceMoveTo && p.getCurrentPoint() != null);
    }
    public void addArc(RectF o, float start, float sweep) {
        p.append(new Arc2D.Float(o.left, o.top, o.width(), o.height(), -start, -sweep, Arc2D.OPEN), false);
    }
    public void addArc(float l, float t, float r, float b, float start, float sweep) { addArc(new RectF(l, t, r, b), start, sweep); }
    public void addRect(RectF r, Direction d) { addRect(r.left, r.top, r.right, r.bottom, d); }
    public void addRect(float l, float t, float r, float b, Direction d) {
        p.moveTo(l, t);
        if (d == Direction.CCW) { p.lineTo(l, b); p.lineTo(r, b); p.lineTo(r, t); }
        else { p.lineTo(r, t); p.lineTo(r, b); p.lineTo(l, b); }
        p.closePath();
    }
    public void addCircle(float x, float y, float rad, Direction d) { p.append(new Ellipse2D.Float(x - rad, y - rad, 2 * rad, 2 * rad), false); }
    public void addOval(RectF r, Direction d) { p.append(new Ellipse2D.Float(r.left, r.top, r.width(), r.height()), false); }
    public void addOval(float l, float t, float r, float b, Direction d) { addOval(new RectF(l, t, r, b), d); }
    public void addRoundRect(RectF r, float rx, float ry, Direction d) { addRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, d); }
    public void addRoundRect(float l, float t, float r, float b, float rx, float ry, Direction d) {
        p.append(new RoundRectangle2D.Float(l, t, r - l, b - t, Math.min(2 * rx, r - l), Math.min(2 * ry, b - t)), false);
    }
    public void addRoundRect(RectF r, float[] radii, Direction d) { addRoundRect(r.left, r.top, r.right, r.bottom, radii, d); }
    public void addRoundRect(float l, float t, float r, float b, float[] radii, Direction d) {
        p.append(roundRect(l, t, r, b, radii), false);
    }
    public void addPath(Path o) { p.append(o.p, false); }
    public void addPath(Path o, float dx, float dy) { p.append(AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(o.p), false); }
    public void addPath(Path o, Matrix m) { p.append(m.t.createTransformedShape(o.p), false); }
    public void offset(float dx, float dy) { p.transform(AffineTransform.getTranslateInstance(dx, dy)); }
    public void transform(Matrix m) { p.transform(m.t); }
    public void transform(Matrix m, Path dst) { dst.set(this); dst.p.transform(m.t); }
    public void computeBounds(RectF out, boolean exact) {
        Rectangle2D b = p.getBounds2D();
        out.set((float) b.getMinX(), (float) b.getMinY(), (float) b.getMaxX(), (float) b.getMaxY());
    }
    public boolean op(Path path, Op op) { return op(this, path, op); }
    public boolean op(Path a, Path b, Op op) {
        Area x = new Area(a.p), y = new Area(b.p);
        switch (op) {
            case DIFFERENCE: x.subtract(y); break;
            case INTERSECT: x.intersect(y); break;
            case UNION: x.add(y); break;
            case XOR: x.exclusiveOr(y); break;
            case REVERSE_DIFFERENCE: y.subtract(x); x = y; break;
        }
        p = new Path2D.Float(x);
        return true;
    }

    /** مستطيل بزوايا مختلفة: radii = [tlx,tly, trx,try, brx,bry, blx,bly]. */
    public static Path2D.Float roundRect(float l, float t, float r, float b, float[] radii) {
        float w = r - l, h = b - t;
        float[] q = new float[8];
        for (int i = 0; i < 8; i++) q[i] = radii == null || i >= radii.length ? 0 : Math.max(0, radii[i]);
        // تصغير متناسب إن تجاوزت الأنصاف الضلع (كما في أندرويد)
        float s = 1f;
        s = Math.min(s, fit(w, q[0] + q[2]));
        s = Math.min(s, fit(w, q[6] + q[4]));
        s = Math.min(s, fit(h, q[1] + q[7]));
        s = Math.min(s, fit(h, q[3] + q[5]));
        for (int i = 0; i < 8; i++) q[i] *= s;
        final float k = 0.5522847f;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(l + q[0], t);
        p.lineTo(r - q[2], t);
        if (q[2] > 0 || q[3] > 0) p.curveTo(r - q[2] + q[2] * k, t, r, t + q[3] - q[3] * k, r, t + q[3]);
        p.lineTo(r, b - q[5]);
        if (q[4] > 0 || q[5] > 0) p.curveTo(r, b - q[5] + q[5] * k, r - q[4] + q[4] * k, b, r - q[4], b);
        p.lineTo(l + q[6], b);
        if (q[6] > 0 || q[7] > 0) p.curveTo(l + q[6] - q[6] * k, b, l, b - q[7] + q[7] * k, l, b - q[7]);
        p.lineTo(l, t + q[1]);
        if (q[0] > 0 || q[1] > 0) p.curveTo(l, t + q[1] - q[1] * k, l + q[0] - q[0] * k, t, l + q[0], t);
        p.closePath();
        return p;
    }

    private static float fit(float side, float sum) { return sum > side && sum > 0 ? side / sum : 1f; }

    public java.awt.Shape awt() { return p; }
}
