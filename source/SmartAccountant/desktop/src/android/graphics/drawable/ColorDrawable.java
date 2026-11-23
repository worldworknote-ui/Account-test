package android.graphics.drawable;

import android.graphics.*;

public class ColorDrawable extends Drawable {
    private int color;
    private int alpha = 255;
    public ColorDrawable() {}
    public ColorDrawable(int c) { color = c; }
    public int getColor() { return color; }
    public void setColor(int c) { color = c; invalidateSelf(); }
    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void draw(Canvas c) {
        int col = hasTint() ? tintColor() : color;
        int a = Color.alpha(col) * alpha / 255;
        if (a == 0) return;
        Paint p = new Paint();
        p.setColor((a << 24) | (col & 0xFFFFFF));
        c.drawRect(getBounds(), p);
    }
}
