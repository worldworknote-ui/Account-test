package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import java.util.List;

public abstract class CompoundButton extends Button implements Checkable {
    public interface OnCheckedChangeListener { void onCheckedChanged(CompoundButton b, boolean checked); }
    private boolean checked, broadcasting;
    private OnCheckedChangeListener listener;
    protected ColorStateList tint;
    protected final float d;

    public CompoundButton(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setBackground(null);
        setElevation(0);
        setStateListAnimator(null);
        setAllCaps(false);
        setTypeface(Typeface.DEFAULT);
        setTextSize(14);
        setMinimumWidth(0);
        setMinWidth(0);
        setMinimumHeight(Math.round(48 * d));
        setMinHeight(Math.round(48 * d));
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setPadding(0, 0, 0, 0);
    }
    @Override public boolean isChecked() { return checked; }
    @Override public void setChecked(boolean c) {
        if (checked == c) return;
        checked = c;
        refreshDrawableState();
        invalidate();
        if (broadcasting) return;
        broadcasting = true;
        if (listener != null) listener.onCheckedChanged(this, c);
        broadcasting = false;
    }
    @Override public void toggle() { setChecked(!checked); }
    @Override public boolean performClick() { toggle(); return super.performClick() || true; }
    public void setOnCheckedChangeListener(OnCheckedChangeListener l) { listener = l; }
    public void setButtonTintList(ColorStateList t) { tint = t; invalidate(); }
    public ColorStateList getButtonTintList() { return tint; }
    public void setButtonDrawable(Drawable dr) {}
    public void setButtonDrawable(int res) {}
    public Drawable getButtonDrawable() { return null; }
    @Override protected void addStates(List<Integer> s) { if (checked) s.add(android.R.attr.state_checked); s.add(android.R.attr.state_checkable); }

    /** عرض الزر الرسومي في طرف النص. */
    protected abstract int buttonWidth();
    @Override protected void onMeasure(int ws, int hs) {
        int pl = getPaddingLeft(), pr = getPaddingRight();
        setPaddingQuiet(pl + (isRtl() ? 0 : buttonWidth()), pr + (isRtl() ? buttonWidth() : 0));
        super.onMeasure(ws, hs);
        setPaddingQuiet(pl, pr);
    }
    private void setPaddingQuiet(int l, int r) { mPaddingLeft = l; mPaddingRight = r; }
    protected int accent() {
        if (tint != null) return tint.getColorForState(getDrawableState(), tint.getDefaultColor());
        return getContext() instanceof android.app.Activity ? ((android.app.Activity) getContext()).themeAccent() : 0xFF0B6B53;
    }
    protected int offColor() {
        if (tint != null && !tint.isStateful()) return tint.getDefaultColor();
        return getContext() instanceof android.app.Activity && ((android.app.Activity) getContext()).themeNight() ? 0xB3FFFFFF : 0x8A000000;
    }
    @Override protected void onDraw(Canvas c) {
        int w = buttonWidth();
        int x = isRtl() ? getWidth() - getPaddingRight() - w : getPaddingLeft();
        drawButton(c, x, w);
        int pl = getPaddingLeft(), pr = getPaddingRight();
        setPaddingQuiet(pl + (isRtl() ? 0 : w), pr + (isRtl() ? w : 0));
        super.onDraw(c);
        setPaddingQuiet(pl, pr);
    }
    protected abstract void drawButton(Canvas c, int x, int w);
}
