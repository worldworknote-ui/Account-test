package android.graphics;

import java.awt.geom.Point2D;

public class RadialGradient extends Shader {
    final float cx, cy, r;
    final int[] colors;
    final float[] pos;
    final TileMode tile;
    public RadialGradient(float cx, float cy, float r, int c0, int c1, TileMode t) { this(cx, cy, r, new int[]{c0, c1}, null, t); }
    public RadialGradient(float cx, float cy, float r, int[] colors, float[] pos, TileMode t) {
        this.cx = cx; this.cy = cy; this.r = Math.max(0.01f, r); this.colors = colors; this.pos = pos; this.tile = t;
    }
    @Override public java.awt.Paint awt(int alpha) {
        if (colors.length == 1) return Shader.colors(colors, alpha)[0];
        java.awt.geom.AffineTransform at = local == null ? new java.awt.geom.AffineTransform() : local.t;
        Point2D c = new Point2D.Float(cx, cy);
        return new java.awt.RadialGradientPaint(c, r, c, fractions(colors.length, pos), Shader.colors(colors, alpha), cycle(tile),
                java.awt.MultipleGradientPaint.ColorSpaceType.SRGB, at);
    }
}
