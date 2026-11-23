package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.graphics.*;
import java.awt.Shape;
import java.awt.geom.*;

/** الشكل الأساسي لكل الخلفيات: مستطيل/بيضاوي بزوايا مستديرة، تعبئة أو تدرج، وحدّ (متقطع أحيانًا). */
public class GradientDrawable extends Drawable {
    public static final int RECTANGLE = 0, OVAL = 1, LINE = 2, RING = 3;
    public static final int LINEAR_GRADIENT = 0, RADIAL_GRADIENT = 1, SWEEP_GRADIENT = 2;

    public enum Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR }

    private int shape = RECTANGLE;
    private int gradientType = LINEAR_GRADIENT;
    private Orientation orientation = Orientation.TOP_BOTTOM;
    private int[] colors;
    private float[] offsets;
    private ColorStateList solid;
    private float radius;
    private float[] radii;
    private int strokeWidth = 0;
    private ColorStateList strokeColor;
    private float dashW, dashGap;
    private int alpha = 255;
    private int w = -1, h = -1;
    private float gradRadius = 0.5f, cx = 0.5f, cy = 0.5f;
    private boolean useLevel;

    public GradientDrawable() {}
    public GradientDrawable(Orientation o, int[] colors) { orientation = o; this.colors = colors; }

    public void setShape(int s) { shape = s; invalidateSelf(); }
    public int getShape() { return shape; }
    public void setGradientType(int t) { gradientType = t; invalidateSelf(); }
    public int getGradientType() { return gradientType; }
    public void setOrientation(Orientation o) { orientation = o; invalidateSelf(); }
    public Orientation getOrientation() { return orientation; }
    public void setColors(int[] c) { colors = c; solid = null; invalidateSelf(); }
    public void setColors(int[] c, float[] off) { colors = c; offsets = off; solid = null; invalidateSelf(); }
    public int[] getColors() { return colors; }
    public void setColor(int c) { solid = ColorStateList.valueOf(c); colors = null; invalidateSelf(); }
    public void setColor(ColorStateList c) { solid = c; colors = null; invalidateSelf(); }
    public ColorStateList getColor() { return solid; }
    public void setCornerRadius(float r) { radius = r; radii = null; invalidateSelf(); }
    public float getCornerRadius() { return radius; }
    public void setCornerRadii(float[] r) { radii = r; invalidateSelf(); }
    public float[] getCornerRadii() { return radii; }
    public void setStroke(int width, int color) { setStroke(width, ColorStateList.valueOf(color), 0, 0); }
    public void setStroke(int width, ColorStateList color) { setStroke(width, color, 0, 0); }
    public void setStroke(int width, int color, float dw, float dg) { setStroke(width, ColorStateList.valueOf(color), dw, dg); }
    public void setStroke(int width, ColorStateList color, float dw, float dg) {
        strokeWidth = width; strokeColor = color; dashW = dw; dashGap = dg; invalidateSelf();
    }
    public void setSize(int w, int h) { this.w = w; this.h = h; }
    public void setGradientRadius(float r) { gradRadius = r; gradientType = RADIAL_GRADIENT; }
    public float getGradientRadius() { return gradRadius; }
    public void setGradientCenter(float x, float y) { cx = x; cy = y; }
    public void setUseLevel(boolean u) { useLevel = u; }
    public void setDither(boolean d) {}
    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public int getIntrinsicWidth() { return w; }
    @Override public int getIntrinsicHeight() { return h; }
    @Override public boolean isStateful() { return (solid != null && solid.isStateful()) || (strokeColor != null && strokeColor.isStateful()); }
    @Override protected boolean onStateChange(int[] s) { return isStateful(); }
    @Override public Drawable mutate() { return this; }
    @Override public ConstantState getConstantState() {
        final GradientDrawable src = this;
        return new ConstantState() {
            @Override public Drawable newDrawable() {
                GradientDrawable g = new GradientDrawable(src.orientation, src.colors == null ? null : src.colors.clone());
                g.shape = src.shape; g.gradientType = src.gradientType; g.offsets = src.offsets; g.solid = src.solid; g.radius = src.radius;
                g.radii = src.radii == null ? null : src.radii.clone(); g.strokeWidth = src.strokeWidth; g.strokeColor = src.strokeColor;
                g.dashW = src.dashW; g.dashGap = src.dashGap; g.alpha = src.alpha; g.w = src.w; g.h = src.h; g.gradRadius = src.gradRadius;
                g.cx = src.cx; g.cy = src.cy;
                return g;
            }
        };
    }

    /** شكل الرسم داخل المستطيل r. */
    private Shape shapeIn(float l, float t, float r, float b) {
        float ww = r - l, hh = b - t;
        if (shape == OVAL) return new Ellipse2D.Float(l, t, ww, hh);
        if (shape == LINE) return new Line2D.Float(l, t + hh / 2, r, t + hh / 2);
        if (radii != null) return Path.roundRect(l, t, r, b, radii);
        if (radius > 0) {
            float rr = Math.min(radius, Math.min(ww, hh) / 2f);
            return new RoundRectangle2D.Float(l, t, ww, hh, 2 * rr, 2 * rr);
        }
        return new Rectangle2D.Float(l, t, ww, hh);
    }

    @Override public Shape outline() {
        Rect b = getBounds();
        return shapeIn(b.left, b.top, b.right, b.bottom);
    }

    @Override public void draw(Canvas c) {
        Rect bb = getBounds();
        if (bb.isEmpty()) return;
        float half = strokeWidth / 2f;
        java.awt.Graphics2D g = c.graphics();
        float a = c.alpha * alpha / 255f;
        int[] st = getState();
        if (shape != LINE) {
            // التعبئة داخل نصف عرض الحد (كما في أندرويد)
            Shape fill = shapeIn(bb.left + half, bb.top + half, bb.right - half, bb.bottom - half);
            java.awt.Paint p = null;
            if (colors != null && colors.length > 0) {
                p = gradient(bb, a);
            } else if (solid != null) {
                int col = solid.getColorForState(st, solid.getDefaultColor());
                if (hasTint()) col = tintColor();
                int al = Math.round(Color.alpha(col) * a);
                if (al > 0) p = new java.awt.Color(Color.red(col), Color.green(col), Color.blue(col), al);
            } else if (hasTint()) {
                int col = tintColor();
                p = new java.awt.Color(Color.red(col), Color.green(col), Color.blue(col), Math.round(Color.alpha(col) * a));
            }
            if (p != null) {
                g.setPaint(p);
                g.fill(fill);
            }
        }
        if (strokeWidth > 0 && strokeColor != null) {
            int col = strokeColor.getColorForState(st, strokeColor.getDefaultColor());
            int al = Math.round(Color.alpha(col) * a);
            if (al > 0) {
                Shape s = shapeIn(bb.left + half, bb.top + half, bb.right - half, bb.bottom - half);
                java.awt.BasicStroke bs = dashW > 0
                        ? new java.awt.BasicStroke(strokeWidth, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND, 4f, new float[]{dashW, Math.max(0.01f, dashGap)}, 0)
                        : new java.awt.BasicStroke(strokeWidth, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND);
                g.setPaint(new java.awt.Color(Color.red(col), Color.green(col), Color.blue(col), al));
                g.fill(bs.createStrokedShape(s));
            }
        }
    }

    private java.awt.Paint gradient(Rect b, float a) {
        int al = Math.round(255 * a);
        if (colors.length == 1) {
            int c0 = colors[0];
            return new java.awt.Color(Color.red(c0), Color.green(c0), Color.blue(c0), Color.alpha(c0) * al / 255);
        }
        if (gradientType == RADIAL_GRADIENT) {
            float rr = gradRadius <= 1f ? gradRadius * Math.min(b.width(), b.height()) : gradRadius;
            return new RadialGradient(b.left + cx * b.width(), b.top + cy * b.height(), Math.max(1, rr), colors, offsets, Shader.TileMode.CLAMP).awt(al);
        }
        float x0, y0, x1, y1;
        float l = b.left, t = b.top, r = b.right, bo = b.bottom;
        switch (orientation) {
            case TOP_BOTTOM: x0 = l; y0 = t; x1 = l; y1 = bo; break;
            case TR_BL: x0 = r; y0 = t; x1 = l; y1 = bo; break;
            case RIGHT_LEFT: x0 = r; y0 = t; x1 = l; y1 = t; break;
            case BR_TL: x0 = r; y0 = bo; x1 = l; y1 = t; break;
            case BOTTOM_TOP: x0 = l; y0 = bo; x1 = l; y1 = t; break;
            case BL_TR: x0 = l; y0 = bo; x1 = r; y1 = t; break;
            case LEFT_RIGHT: x0 = l; y0 = t; x1 = r; y1 = t; break;
            default: x0 = l; y0 = t; x1 = r; y1 = bo; break;
        }
        return new LinearGradient(x0, y0, x1, y1, colors, offsets, Shader.TileMode.CLAMP).awt(al);
    }
}
