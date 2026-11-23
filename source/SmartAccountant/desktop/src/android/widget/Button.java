package android.widget;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;

public class Button extends TextView {
    public Button(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        setGravity(Gravity.CENTER);
        setTextSize(14);
        setAllCaps(true);
        setMinimumHeight(Math.round(48 * d));
        setMinimumWidth(Math.round(88 * d));
        setMinHeight(Math.round(48 * d));
        setMinWidth(Math.round(88 * d));
        setPadding(Math.round(16 * d), Math.round(10 * d), Math.round(16 * d), Math.round(10 * d));
        setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        GradientDrawable g = new GradientDrawable();
        boolean night = c instanceof android.app.Activity && ((android.app.Activity) c).themeNight();
        g.setColor(night ? 0xFF5A595B : 0xFFD6D7D7);
        g.setCornerRadius(2 * d);
        InsetDrawable in = new InsetDrawable(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), g, null), Math.round(4 * d), Math.round(6 * d), Math.round(4 * d), Math.round(6 * d));
        setBackground(in);
        setElevation(2 * d);
        android.animation.StateListAnimator s = new android.animation.StateListAnimator();
        setStateListAnimator(s);
    }
    public Button(Context c, android.util.AttributeSet a) { this(c); }
    public Button(Context c, android.util.AttributeSet a, int s) { this(c); }
}
