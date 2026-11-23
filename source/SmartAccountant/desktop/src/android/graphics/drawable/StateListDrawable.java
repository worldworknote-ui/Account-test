package android.graphics.drawable;

import android.graphics.*;

public class StateListDrawable extends Drawable implements Drawable.Callback {
    private final java.util.List<int[]> specs = new java.util.ArrayList<>();
    private final java.util.List<Drawable> ds = new java.util.ArrayList<>();
    private Drawable cur;
    private int alpha = 255;

    public void addState(int[] spec, Drawable d) {
        specs.add(spec);
        ds.add(d);
        if (d != null) { d.setCallback(this); d.setBounds(getBounds()); }
        pick();
    }
    private boolean matches(int[] spec, int[] st) {
        for (int s : spec) {
            if (s > 0 && !has(st, s)) return false;
            if (s < 0 && has(st, -s)) return false;
        }
        return true;
    }
    private boolean pick() {
        Drawable n = null;
        for (int i = 0; i < specs.size(); i++) if (matches(specs.get(i), getState())) { n = ds.get(i); break; }
        if (n == cur) return false;
        cur = n;
        if (cur != null) { cur.setBounds(getBounds()); cur.setState(getState()); }
        return true;
    }
    public Drawable getCurrent() { return cur; }
    public int getStateCount() { return specs.size(); }
    public Drawable getStateDrawable(int i) { return ds.get(i); }
    @Override protected boolean onStateChange(int[] s) {
        boolean ch = pick();
        if (cur != null) ch |= cur.setState(s);
        return ch;
    }
    @Override public boolean isStateful() { return true; }
    @Override protected void onBoundsChange(Rect b) { for (Drawable d : ds) if (d != null) d.setBounds(b); }
    @Override public void draw(Canvas c) {
        if (cur == null) return;
        float o = c.alpha;
        c.alpha = o * alpha / 255f;
        cur.draw(c);
        c.alpha = o;
    }
    @Override public void setAlpha(int a) { alpha = a; }
    @Override public int getAlpha() { return alpha; }
    @Override public void setTintList(android.content.res.ColorStateList t) { for (Drawable d : ds) if (d != null) d.setTintList(t); }
    @Override public int getIntrinsicWidth() { return cur == null ? -1 : cur.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return cur == null ? -1 : cur.getIntrinsicHeight(); }
    @Override public java.awt.Shape outline() { return cur == null ? null : cur.outline(); }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
}
