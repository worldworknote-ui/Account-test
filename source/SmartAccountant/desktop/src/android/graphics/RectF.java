package android.graphics;

public class RectF {
    public float left, top, right, bottom;
    public RectF() {}
    public RectF(float l, float t, float r, float b) { set(l, t, r, b); }
    public RectF(RectF o) { set(o); }
    public RectF(Rect o) { set(o.left, o.top, o.right, o.bottom); }
    public void set(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
    public void set(RectF o) { set(o.left, o.top, o.right, o.bottom); }
    public void set(Rect o) { set(o.left, o.top, o.right, o.bottom); }
    public void setEmpty() { left = top = right = bottom = 0; }
    public final float width() { return right - left; }
    public final float height() { return bottom - top; }
    public final float centerX() { return (left + right) * 0.5f; }
    public final float centerY() { return (top + bottom) * 0.5f; }
    public final boolean isEmpty() { return left >= right || top >= bottom; }
    public boolean contains(float x, float y) { return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom; }
    public void offset(float dx, float dy) { left += dx; right += dx; top += dy; bottom += dy; }
    public void offsetTo(float x, float y) { right += x - left; bottom += y - top; left = x; top = y; }
    public void inset(float dx, float dy) { left += dx; top += dy; right -= dx; bottom -= dy; }
    public void union(float x, float y) { left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y); }
    public void round(Rect r) { r.set(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom)); }
    public boolean intersect(float l, float t, float r, float b) {
        if (left < r && l < right && top < b && t < bottom) {
            if (left < l) left = l;
            if (top < t) top = t;
            if (right > r) right = r;
            if (bottom > b) bottom = b;
            return true;
        }
        return false;
    }
    java.awt.geom.Rectangle2D awt() { return new java.awt.geom.Rectangle2D.Float(left, top, right - left, bottom - top); }
    @Override public String toString() { return "RectF(" + left + ", " + top + ", " + right + ", " + bottom + ")"; }
}
