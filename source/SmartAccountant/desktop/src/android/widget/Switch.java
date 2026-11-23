package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;

/** مفتاح Material: مسار ومقبض ينزلق. */
public class Switch extends CompoundButton {
    private float pos;
    private ColorStateList thumbTint, trackTint;
    private CharSequence textOn, textOff;
    private boolean showText;
    public Switch(Context c) { super(c); }
    public Switch(Context c, android.util.AttributeSet a) { this(c); }
    @Override protected int buttonWidth() { return Math.round(52 * d); }
    public void setThumbTintList(ColorStateList t) { thumbTint = t; invalidate(); }
    public void setTrackTintList(ColorStateList t) { trackTint = t; invalidate(); }
    public void setThumbDrawable(android.graphics.drawable.Drawable dr) {}
    public void setTrackDrawable(android.graphics.drawable.Drawable dr) {}
    public void setTextOn(CharSequence t) { textOn = t; }
    public void setTextOff(CharSequence t) { textOff = t; }
    public void setShowText(boolean s) { showText = s; }
    public void setSwitchMinWidth(int w) {}
    public void setSwitchPadding(int p) {}
    @Override public void setChecked(boolean c) {
        boolean was = isChecked();
        super.setChecked(c);
        if (was != c && isAttachedToWindow() && !android.animation.Choreo.instant) {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(pos, c ? 1f : 0f);
            a.setDuration(160);
            a.addUpdateListener(x -> { pos = (float) x.getAnimatedValue(); invalidate(); });
            a.start();
        } else pos = c ? 1f : 0f;
    }
    @Override protected void drawButton(Canvas c, int x, int w) {
        float cy = getHeight() / 2f;
        float tw = 34 * d, th = 14 * d;
        float cx = x + w / 2f;
        boolean on = isChecked();
        float p = isAttachedToWindow() ? pos : (on ? 1f : 0f);
        if (!isAttachedToWindow()) pos = p;
        boolean night = getContext() instanceof android.app.Activity && ((android.app.Activity) getContext()).themeNight();
        int acc = accent();
        int[] st = getDrawableState();
        int track = trackTint != null ? trackTint.getColorForState(st, trackTint.getDefaultColor()) : on ? ((acc & 0xFFFFFF) | 0x80000000) : (night ? 0x4DFFFFFF : 0x61000000);
        int thumb = thumbTint != null ? thumbTint.getColorForState(st, thumbTint.getDefaultColor()) : on ? acc : (night ? 0xFFBDBDBD : 0xFFF1F1F1);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setColor(track);
        float a = isEnabled() ? 1f : 0.5f;
        pp.setAlpha(Math.round(Color.alpha(track) * a));
        // في الواجهة العربية «مفعّل» إلى اليسار كما في أندرويد
        boolean rtl = isRtl();
        c.drawRoundRect(new RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2), th / 2, th / 2, pp);
        float r = 10 * d;
        float from = rtl ? cx + tw / 2 - r + 2 * d : cx - tw / 2 + r - 2 * d, to = rtl ? cx - tw / 2 + r - 2 * d : cx + tw / 2 - r + 2 * d;
        float tx = from + (to - from) * p;
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x33000000);
        c.drawCircle(tx, cy + d, r, sh);
        pp.setColor(thumb);
        pp.setAlpha(Math.round(Color.alpha(thumb) * a));
        c.drawCircle(tx, cy, r, pp);
        if (isPressed() || isHovered()) {
            Paint h = new Paint(Paint.ANTI_ALIAS_FLAG);
            h.setColor((acc & 0xFFFFFF) | 0x22000000);
            c.drawCircle(tx, cy, 20 * d, h);
        }
    }
}
