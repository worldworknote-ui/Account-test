package android.graphics.drawable;

import android.graphics.*;

public class InsetDrawable extends Drawable implements Drawable.Callback {
    private final Drawable d;
    private final int l, t, r, b;
    public InsetDrawable(Drawable d, int inset) { this(d, inset, inset, inset, inset); }
    public InsetDrawable(Drawable d, int l, int t, int r, int b) { this.d = d; this.l = l; this.t = t; this.r = r; this.b = b; if (d != null) d.setCallback(this); }
    public Drawable getDrawable() { return d; }
    @Override protected void onBoundsChange(Rect bb) { if (d != null) d.setBounds(bb.left + l, bb.top + t, bb.right - r, bb.bottom - b); }
    @Override public boolean setState(int[] s) { super.setState(s); return d != null && d.setState(s); }
    @Override public void draw(Canvas c) { if (d != null) d.draw(c); }
    @Override public boolean getPadding(Rect p) { p.set(l, t, r, b); return true; }
    @Override public int getIntrinsicWidth() { return d == null || d.getIntrinsicWidth() < 0 ? -1 : d.getIntrinsicWidth() + l + r; }
    @Override public int getIntrinsicHeight() { return d == null || d.getIntrinsicHeight() < 0 ? -1 : d.getIntrinsicHeight() + t + b; }
    @Override public java.awt.Shape outline() { return d == null ? null : d.outline(); }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {}
    @Override public void unscheduleDrawable(Drawable who, Runnable what) {}
}
