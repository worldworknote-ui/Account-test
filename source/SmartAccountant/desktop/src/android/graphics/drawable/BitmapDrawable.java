package android.graphics.drawable;

import android.graphics.*;

public class BitmapDrawable extends Drawable {
    private final Bitmap bm;
    private int alpha = 255;
    public BitmapDrawable(Bitmap b) { bm = b; }
    public BitmapDrawable(android.content.res.Resources r, Bitmap b) { bm = b; }
    public Bitmap getBitmap() { return bm; }
    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public int getIntrinsicWidth() { return bm == null ? -1 : bm.getWidth(); }
    @Override public int getIntrinsicHeight() { return bm == null ? -1 : bm.getHeight(); }
    @Override public void draw(Canvas c) {
        if (bm == null) return;
        Paint p = new Paint();
        p.setAlpha(alpha);
        c.drawBitmap(bm, null, new RectF(getBounds()), p);
    }
}
