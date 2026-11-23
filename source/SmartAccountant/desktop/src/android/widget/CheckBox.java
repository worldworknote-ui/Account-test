package android.widget;

import android.content.Context;
import android.graphics.*;

public class CheckBox extends CompoundButton {
    public CheckBox(Context c) { super(c); }
    public CheckBox(Context c, android.util.AttributeSet a) { this(c); }
    @Override protected int buttonWidth() { return Math.round(32 * d); }
    @Override protected void drawButton(Canvas c, int x, int w) {
        float s = 18 * d, cx = x + w / 2f, cy = getHeight() / 2f;
        RectF r = new RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float a = isEnabled() ? 1f : 0.38f;
        if (isChecked()) {
            p.setColor(accent());
            p.setAlpha(Math.round(255 * a));
            c.drawRoundRect(r, 2 * d, 2 * d, p);
            Path m = new Path();
            m.moveTo(cx - s * 0.28f, cy + s * 0.02f);
            m.lineTo(cx - s * 0.08f, cy + s * 0.22f);
            m.lineTo(cx + s * 0.30f, cy - s * 0.20f);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * d);
            p.setStrokeCap(Paint.Cap.SQUARE);
            c.drawPath(m, p);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(offColor());
            p.setAlpha(Math.round(Color.alpha(offColor()) * a));
            r.inset(d, d);
            c.drawRoundRect(r, 2 * d, 2 * d, p);
        }
        if (isPressed() || isHovered()) {
            Paint h = new Paint(Paint.ANTI_ALIAS_FLAG);
            h.setColor((accent() & 0xFFFFFF) | 0x22000000);
            c.drawCircle(cx, cy, 18 * d, h);
        }
    }
}
