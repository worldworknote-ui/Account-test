package android.widget;

import android.content.Context;
import android.graphics.*;

public class RadioButton extends CompoundButton {
    public RadioButton(Context c) { super(c); }
    @Override protected int buttonWidth() { return Math.round(32 * d); }
    @Override public void toggle() { if (!isChecked()) setChecked(true); }
    @Override protected void drawButton(Canvas c, int x, int w) {
        float cx = x + w / 2f, cy = getHeight() / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor(isChecked() ? accent() : offColor());
        c.drawCircle(cx, cy, 9 * d, p);
        if (isChecked()) { p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, 5 * d, p); }
    }
}
