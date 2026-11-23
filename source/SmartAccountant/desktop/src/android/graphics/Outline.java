package android.graphics;
public final class Outline {
    private java.awt.Shape s;
    private float alpha = 1f;
    public void setRect(int l, int t, int r, int b) { s = new java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t); }
    public void setRect(Rect r) { setRect(r.left, r.top, r.right, r.bottom); }
    public void setRoundRect(int l, int t, int r, int b, float rad) { s = new java.awt.geom.RoundRectangle2D.Float(l, t, r - l, b - t, 2 * rad, 2 * rad); }
    public void setRoundRect(Rect r, float rad) { setRoundRect(r.left, r.top, r.right, r.bottom, rad); }
    public void setOval(int l, int t, int r, int b) { s = new java.awt.geom.Ellipse2D.Float(l, t, r - l, b - t); }
    public void setOval(Rect r) { setOval(r.left, r.top, r.right, r.bottom); }
    public void setPath(Path p) { s = p.p; }
    public void setConvexPath(Path p) { s = p.p; }
    public void setEmpty() { s = null; }
    public void setAlpha(float a) { alpha = a; }
    public float getAlpha() { return alpha; }
    public boolean isEmpty() { return s == null; }
    public void setShape(java.awt.Shape sh) { s = sh; }
    public java.awt.Shape shape() { return s; }
}
