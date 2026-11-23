package android.graphics;

import java.awt.geom.AffineTransform;

public class Matrix {
    public enum ScaleToFit { FILL, START, CENTER, END }
    final AffineTransform t = new AffineTransform();
    public Matrix() {}
    public Matrix(Matrix m) { if (m != null) t.setTransform(m.t); }
    public void reset() { t.setToIdentity(); }
    public boolean isIdentity() { return t.isIdentity(); }
    public void set(Matrix m) { if (m == null) reset(); else t.setTransform(m.t); }
    public void setTranslate(float dx, float dy) { t.setToTranslation(dx, dy); }
    public void setScale(float sx, float sy) { t.setToScale(sx, sy); }
    public void setScale(float sx, float sy, float px, float py) { t.setToIdentity(); t.translate(px, py); t.scale(sx, sy); t.translate(-px, -py); }
    public void setRotate(float d) { t.setToRotation(Math.toRadians(d)); }
    public void setRotate(float d, float px, float py) { t.setToRotation(Math.toRadians(d), px, py); }
    public boolean preTranslate(float dx, float dy) { t.translate(dx, dy); return true; }
    public boolean preScale(float sx, float sy) { t.scale(sx, sy); return true; }
    public boolean preScale(float sx, float sy, float px, float py) { t.translate(px, py); t.scale(sx, sy); t.translate(-px, -py); return true; }
    public boolean preRotate(float d) { t.rotate(Math.toRadians(d)); return true; }
    public boolean preRotate(float d, float px, float py) { t.rotate(Math.toRadians(d), px, py); return true; }
    public boolean postTranslate(float dx, float dy) { t.preConcatenate(AffineTransform.getTranslateInstance(dx, dy)); return true; }
    public boolean postScale(float sx, float sy) { t.preConcatenate(AffineTransform.getScaleInstance(sx, sy)); return true; }
    public boolean postScale(float sx, float sy, float px, float py) {
        AffineTransform a = new AffineTransform(); a.translate(px, py); a.scale(sx, sy); a.translate(-px, -py);
        t.preConcatenate(a); return true;
    }
    public boolean postRotate(float d) { t.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(d))); return true; }
    public boolean postRotate(float d, float px, float py) { t.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(d), px, py)); return true; }
    public boolean postConcat(Matrix m) { t.preConcatenate(m.t); return true; }
    public boolean preConcat(Matrix m) { t.concatenate(m.t); return true; }
    public boolean invert(Matrix out) {
        try { out.t.setTransform(t.createInverse()); return true; } catch (Exception e) { return false; }
    }
    public void mapPoints(float[] pts) {
        t.transform(pts, 0, pts, 0, pts.length / 2);
    }
    public boolean mapRect(RectF r) {
        java.awt.geom.Rectangle2D b = t.createTransformedShape(r.awt()).getBounds2D();
        r.set((float) b.getMinX(), (float) b.getMinY(), (float) b.getMaxX(), (float) b.getMaxY());
        return true;
    }
    public boolean setRectToRect(RectF src, RectF dst, ScaleToFit fit) {
        float sx = dst.width() / src.width(), sy = dst.height() / src.height();
        t.setToIdentity();
        if (fit == ScaleToFit.FILL) {
            t.translate(dst.left, dst.top); t.scale(sx, sy); t.translate(-src.left, -src.top); return true;
        }
        float s = Math.min(sx, sy);
        float w = src.width() * s, h = src.height() * s;
        float dx = dst.left, dy = dst.top;
        if (fit == ScaleToFit.CENTER) { dx += (dst.width() - w) / 2; dy += (dst.height() - h) / 2; }
        else if (fit == ScaleToFit.END) { dx += dst.width() - w; dy += dst.height() - h; }
        t.translate(dx, dy); t.scale(s, s); t.translate(-src.left, -src.top);
        return true;
    }
    public void getValues(float[] v) {
        v[0] = (float) t.getScaleX(); v[1] = (float) t.getShearX(); v[2] = (float) t.getTranslateX();
        v[3] = (float) t.getShearY(); v[4] = (float) t.getScaleY(); v[5] = (float) t.getTranslateY();
        v[6] = 0; v[7] = 0; v[8] = 1;
    }
    public AffineTransform awt() { return t; }
}
