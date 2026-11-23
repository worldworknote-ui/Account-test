package android.graphics.drawable;

import android.graphics.*;
import android.view.Gravity;

public class ClipDrawable extends Drawable implements Drawable.Callback {
    public static final int HORIZONTAL = 1, VERTICAL = 2;
    private final Drawable d;
    private final int gravity, orientation;
    public ClipDrawable(Drawable d, int gravity, int orientation) {
        this.d = d; this.gravity = gravity; this.orientation = orientation;
        if (d != null) d.setCallback(this);
    }
    public Drawable getDrawable() { return d; }
    @Override protected void onBoundsChange(Rect b) { if (d != null) d.setBounds(b); }
    @Override public boolean setState(int[] s) { super.setState(s); return d != null && d.setState(s); }
    @Override public void draw(Canvas c) {
        if (d == null || getLevel() == 0) return;
        Rect b = getBounds();
        float f = getLevel() / 10000f;
        int w = b.width(), h = b.height();
        Rect r = new Rect(b);
        if ((orientation & HORIZONTAL) != 0) {
            int cw = Math.round(w * f);
            int hg = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (hg == Gravity.RIGHT) r.left = b.right - cw;
            else if (hg == Gravity.CENTER_HORIZONTAL) { r.left = b.left + (w - cw) / 2; r.right = r.left + cw; }
            else r.right = b.left + cw;
        }
        if ((orientation & VERTICAL) != 0) {
            int ch = Math.round(h * f);
            int vg = gravity & Gravity.VERTICAL_GRAVITY_MASK;
            if (vg == Gravity.TOP) r.bottom = b.top + ch;
            else r.top = b.bottom - ch;
        }
        c.save();
        c.clipRect(r);
        d.draw(c);
        c.restore();
    }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {}
    @Override public void unscheduleDrawable(Drawable who, Runnable what) {}
}
