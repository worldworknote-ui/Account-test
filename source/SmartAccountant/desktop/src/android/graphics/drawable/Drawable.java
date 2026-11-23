package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.graphics.*;

public abstract class Drawable {
    public interface Callback {
        void invalidateDrawable(Drawable who);
        void scheduleDrawable(Drawable who, Runnable what, long when);
        void unscheduleDrawable(Drawable who, Runnable what);
    }

    private final Rect bounds = new Rect();
    private int[] state = new int[0];
    private int level;
    private java.lang.ref.WeakReference<Callback> cb;
    protected ColorStateList tint;
    protected ColorFilter colorFilter;
    protected boolean visible = true;

    public abstract void draw(Canvas c);
    public void setAlpha(int a) {}
    public int getAlpha() { return 255; }
    public void setColorFilter(ColorFilter f) { colorFilter = f; invalidateSelf(); }
    public void setColorFilter(int color, PorterDuff.Mode m) { setColorFilter(new PorterDuffColorFilter(color, m)); }
    public ColorFilter getColorFilter() { return colorFilter; }
    public void clearColorFilter() { setColorFilter(null); }
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    public void setTint(int c) { setTintList(ColorStateList.valueOf(c)); }
    public void setTintList(ColorStateList t) { tint = t; invalidateSelf(); }
    public void setTintMode(PorterDuff.Mode m) {}
    /** لون التلوين الحالي (0 = بلا تلوين). */
    protected int tintColor() {
        if (colorFilter != null && colorFilter.tint() != 0) return colorFilter.tint();
        return tint == null ? 0 : tint.getColorForState(state, tint.getDefaultColor()) | 0;
    }
    protected boolean hasTint() { return (colorFilter != null && colorFilter.tint() != 0) || tint != null; }

    public final void setBounds(int l, int t, int r, int b) {
        if (bounds.left != l || bounds.top != t || bounds.right != r || bounds.bottom != b) {
            bounds.set(l, t, r, b);
            onBoundsChange(bounds);
        }
    }
    public final void setBounds(Rect r) { setBounds(r.left, r.top, r.right, r.bottom); }
    public final Rect getBounds() { return bounds; }
    public final Rect copyBounds() { return new Rect(bounds); }
    public final void copyBounds(Rect r) { r.set(bounds); }
    protected void onBoundsChange(Rect b) {}

    public boolean setState(int[] s) {
        if (s == null) s = new int[0];
        if (java.util.Arrays.equals(state, s)) return false;
        state = s.clone();
        return onStateChange(state);
    }
    public int[] getState() { return state; }
    protected boolean onStateChange(int[] s) { return false; }
    public boolean isStateful() { return false; }
    public void jumpToCurrentState() {}

    public final boolean setLevel(int l) {
        if (level == l) return false;
        level = l;
        boolean r = onLevelChange(l);
        invalidateSelf();
        return r;
    }
    public final int getLevel() { return level; }
    protected boolean onLevelChange(int l) { return true; }

    public boolean setVisible(boolean v, boolean restart) { boolean c = visible != v; visible = v; return c; }
    public final boolean isVisible() { return visible; }

    public int getIntrinsicWidth() { return -1; }
    public int getIntrinsicHeight() { return -1; }
    public int getMinimumWidth() { return Math.max(0, getIntrinsicWidth()); }
    public int getMinimumHeight() { return Math.max(0, getIntrinsicHeight()); }
    public boolean getPadding(Rect p) { p.set(0, 0, 0, 0); return false; }
    public Drawable mutate() { return this; }
    public ConstantState getConstantState() { return null; }
    public void setDither(boolean d) {}
    public void setFilterBitmap(boolean f) {}
    public void setAutoMirrored(boolean m) {}
    public void setHotspot(float x, float y) {}
    public void setHotspotBounds(int l, int t, int r, int b) {}
    public int getLayoutDirection() { return 0; }
    public boolean setLayoutDirection(int d) { return false; }

    /** الشكل الخارجي لظل الارتفاع (null = المستطيل). */
    public java.awt.Shape outline() { return null; }

    public final void setCallback(Callback c) { cb = c == null ? null : new java.lang.ref.WeakReference<>(c); }
    public Callback getCallback() { return cb == null ? null : cb.get(); }
    public void invalidateSelf() { Callback c = getCallback(); if (c != null) c.invalidateDrawable(this); }
    public void scheduleSelf(Runnable r, long when) { Callback c = getCallback(); if (c != null) c.scheduleDrawable(this, r, when); }
    public void unscheduleSelf(Runnable r) { Callback c = getCallback(); if (c != null) c.unscheduleDrawable(this, r); }

    public static abstract class ConstantState {
        public abstract Drawable newDrawable();
        public Drawable newDrawable(android.content.res.Resources r) { return newDrawable(); }
        public int getChangingConfigurations() { return 0; }
    }

    static boolean has(int[] state, int s) {
        for (int x : state) if (x == s) return true;
        return false;
    }
}
