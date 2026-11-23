package android.graphics;
public class BitmapShader extends Shader {
    final Bitmap b;
    public BitmapShader(Bitmap b, TileMode x, TileMode y) { this.b = b; }
    @Override public java.awt.Paint awt(int alpha) {
        java.awt.geom.Rectangle2D r = new java.awt.geom.Rectangle2D.Float(0, 0, b.getWidth(), b.getHeight());
        return new java.awt.TexturePaint(b.img, r);
    }
}
