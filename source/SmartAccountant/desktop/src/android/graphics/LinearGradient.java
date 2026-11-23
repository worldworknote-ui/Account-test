package android.graphics;

import java.awt.geom.Point2D;

public class LinearGradient extends Shader {
    final float x0, y0, x1, y1;
    final int[] colors;
    final float[] pos;
    final TileMode tile;
    public LinearGradient(float x0, float y0, float x1, float y1, int c0, int c1, TileMode t) { this(x0, y0, x1, y1, new int[]{c0, c1}, null, t); }
    public LinearGradient(float x0, float y0, float x1, float y1, int[] colors, float[] pos, TileMode t) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.colors = colors; this.pos = pos; this.tile = t;
    }
    @Override public java.awt.Paint awt(int alpha) {
        if (colors.length == 1) return Shader.colors(colors, alpha)[0];
        float ax = x0, ay = y0, bx = x1, by = y1;
        if (ax == bx && ay == by) bx += 0.01f;
        Point2D a = new Point2D.Float(ax, ay), b = new Point2D.Float(bx, by);
        java.awt.geom.AffineTransform at = local == null ? new java.awt.geom.AffineTransform() : local.t;
        return new java.awt.LinearGradientPaint(a, b, fractions(colors.length, pos), Shader.colors(colors, alpha), cycle(tile),
                java.awt.MultipleGradientPaint.ColorSpaceType.SRGB, at);
    }
}
