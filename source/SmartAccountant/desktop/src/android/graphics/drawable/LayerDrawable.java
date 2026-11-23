package android.graphics.drawable;

import android.graphics.*;

public class LayerDrawable extends Drawable implements Drawable.Callback {
    static final class Layer { Drawable d; int l, t, r, b; int id = -1; int gravity; int w = -1, h = -1; }
    final java.util.List<Layer> layers = new java.util.ArrayList<>();
    private int alpha = 255;

    public LayerDrawable(Drawable[] ds) {
        for (Drawable d : ds) addLayer(d);
    }
    public int addLayer(Drawable d) {
        Layer l = new Layer();
        l.d = d;
        if (d != null) d.setCallback(this);
        layers.add(l);
        return layers.size() - 1;
    }
    public int getNumberOfLayers() { return layers.size(); }
    public Drawable getDrawable(int i) { return layers.get(i).d; }
    public void setDrawable(int i, Drawable d) { layers.get(i).d = d; if (d != null) d.setCallback(this); onBoundsChange(getBounds()); invalidateSelf(); }
    public void setId(int i, int id) { layers.get(i).id = id; }
    public Drawable findDrawableByLayerId(int id) { for (Layer l : layers) if (l.id == id) return l.d; return null; }
    public boolean setDrawableByLayerId(int id, Drawable d) { for (Layer l : layers) if (l.id == id) { l.d = d; onBoundsChange(getBounds()); return true; } return false; }
    public void setLayerInset(int i, int l, int t, int r, int b) {
        Layer x = layers.get(i); x.l = l; x.t = t; x.r = r; x.b = b; onBoundsChange(getBounds());
    }
    public void setLayerInsetRelative(int i, int s, int t, int e, int b) { setLayerInset(i, e, t, s, b); }
    public void setLayerGravity(int i, int g) { layers.get(i).gravity = g; onBoundsChange(getBounds()); }
    public void setLayerSize(int i, int w, int h) { Layer x = layers.get(i); x.w = w; x.h = h; onBoundsChange(getBounds()); }
    public void setLayerInsetTop(int i, int t) { layers.get(i).t = t; onBoundsChange(getBounds()); }
    public void setLayerInsetBottom(int i, int b) { layers.get(i).b = b; onBoundsChange(getBounds()); }
    public void setLayerInsetLeft(int i, int l) { layers.get(i).l = l; onBoundsChange(getBounds()); }
    public void setLayerInsetRight(int i, int r) { layers.get(i).r = r; onBoundsChange(getBounds()); }

    @Override protected void onBoundsChange(Rect b) {
        for (Layer l : layers) {
            if (l.d == null) continue;
            int L = b.left + l.l, T = b.top + l.t, R = b.right - l.r, B = b.bottom - l.b;
            if (l.w >= 0 || l.h >= 0) {
                int w = l.w >= 0 ? l.w : R - L, h = l.h >= 0 ? l.h : B - T;
                int g = l.gravity;
                int x = (g & android.view.Gravity.HORIZONTAL_GRAVITY_MASK) == android.view.Gravity.RIGHT ? R - w
                        : (g & android.view.Gravity.HORIZONTAL_GRAVITY_MASK) == android.view.Gravity.CENTER_HORIZONTAL ? (L + R - w) / 2 : L;
                int y = (g & android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.BOTTOM ? B - h
                        : (g & android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.CENTER_VERTICAL ? (T + B - h) / 2 : T;
                l.d.setBounds(x, y, x + w, y + h);
            } else l.d.setBounds(L, T, R, B);
        }
    }
    @Override public void draw(Canvas c) {
        float old = c.alpha;
        c.alpha = old * alpha / 255f;
        for (Layer l : layers) if (l.d != null) l.d.draw(c);
        c.alpha = old;
    }
    @Override public boolean setState(int[] s) {
        boolean ch = super.setState(s);
        for (Layer l : layers) if (l.d != null) ch |= l.d.setState(s);
        return ch;
    }
    @Override public boolean isStateful() { for (Layer l : layers) if (l.d != null && l.d.isStateful()) return true; return false; }
    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setTintList(android.content.res.ColorStateList t) { for (Layer l : layers) if (l.d != null) l.d.setTintList(t); }
    @Override public int getIntrinsicWidth() { int w = -1; for (Layer l : layers) if (l.d != null) w = Math.max(w, l.d.getIntrinsicWidth() + l.l + l.r); return w; }
    @Override public int getIntrinsicHeight() { int h = -1; for (Layer l : layers) if (l.d != null) h = Math.max(h, l.d.getIntrinsicHeight() + l.t + l.b); return h; }
    @Override public java.awt.Shape outline() { return layers.isEmpty() || layers.get(0).d == null ? null : layers.get(0).d.outline(); }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
}
