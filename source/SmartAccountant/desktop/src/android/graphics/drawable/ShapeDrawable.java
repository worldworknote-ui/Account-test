package android.graphics.drawable;

import android.graphics.*;

public class ShapeDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public ShapeDrawable() {}
    public ShapeDrawable(Object shape) {}
    public Paint getPaint() { return paint; }
    @Override public void draw(Canvas c) { c.drawRect(new RectF(getBounds()), paint); }
}
