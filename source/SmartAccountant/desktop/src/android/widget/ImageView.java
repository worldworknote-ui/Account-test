package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;

public class ImageView extends View {
    public enum ScaleType { MATRIX, FIT_XY, FIT_START, FIT_CENTER, FIT_END, CENTER, CENTER_CROP, CENTER_INSIDE }
    private Drawable d;
    private ScaleType scale = ScaleType.FIT_CENTER;
    private ColorStateList tint;
    private ColorFilter filter;
    private boolean adjust;
    private int maxW = Integer.MAX_VALUE, maxH = Integer.MAX_VALUE;
    private int alpha = 255;
    private Matrix matrix;

    public ImageView(Context c) { super(c); }
    public ImageView(Context c, android.util.AttributeSet a) { super(c); }

    public void setImageResource(int res) { setImageDrawable(res == 0 ? null : getContext().getDrawable(res)); }
    public void setImageDrawable(Drawable dr) {
        if (d == dr) return;
        if (d != null) d.setCallback(null);
        int ow = d == null ? -1 : d.getIntrinsicWidth(), oh = d == null ? -1 : d.getIntrinsicHeight();
        d = dr;
        if (dr != null) {
            dr.setCallback(this);
            if (tint != null) dr.setTintList(tint);
            if (filter != null) dr.setColorFilter(filter);
            dr.setState(getDrawableState());
        }
        if (dr == null || ow != dr.getIntrinsicWidth() || oh != dr.getIntrinsicHeight()) requestLayout();
        invalidate();
    }
    public void setImageBitmap(Bitmap b) { setImageDrawable(b == null ? null : new BitmapDrawable(getResources(), b)); }
    public void setImageIcon(Icon i) {}
    public void setImageURI(android.net.Uri u) {
        if (u == null) { setImageDrawable(null); return; }
        try (java.io.InputStream in = getContext().getContentResolver().openInputStream(u)) {
            setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception e) {
            setImageDrawable(null);
        }
    }
    public Drawable getDrawable() { return d; }
    public void setScaleType(ScaleType s) { scale = s; requestLayout(); invalidate(); }
    public ScaleType getScaleType() { return scale; }
    public void setImageTintList(ColorStateList t) { tint = t; if (d != null) d.setTintList(t); invalidate(); }
    public ColorStateList getImageTintList() { return tint; }
    public void setImageTintMode(PorterDuff.Mode m) {}
    public void setColorFilter(int c) { setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_ATOP)); }
    public void setColorFilter(int c, PorterDuff.Mode m) { setColorFilter(new PorterDuffColorFilter(c, m)); }
    public void setColorFilter(ColorFilter f) { filter = f; if (d != null) d.setColorFilter(f); invalidate(); }
    public void clearColorFilter() { setColorFilter(null); }
    public void setAdjustViewBounds(boolean a) { adjust = a; requestLayout(); }
    public void setMaxWidth(int w) { maxW = w; requestLayout(); }
    public void setMaxHeight(int h) { maxH = h; requestLayout(); }
    public void setImageAlpha(int a) { alpha = a; invalidate(); }
    public void setImageMatrix(Matrix m) { matrix = m; invalidate(); }
    public Matrix getImageMatrix() { return matrix == null ? new Matrix() : matrix; }
    public void setCropToPadding(boolean c) {}
    @Override protected boolean verifyDrawable(Drawable who) { return who == d || super.verifyDrawable(who); }
    @Override protected void drawableStateChanged() { super.drawableStateChanged(); if (d != null && d.setState(getDrawableState())) invalidate(); }

    @Override protected void onMeasure(int ws, int hs) {
        int iw = d == null ? 0 : Math.max(0, d.getIntrinsicWidth()), ih = d == null ? 0 : Math.max(0, d.getIntrinsicHeight());
        int pw = getPaddingLeft() + getPaddingRight(), ph = getPaddingTop() + getPaddingBottom();
        int w = Math.max(iw + pw, getSuggestedMinimumWidth()), h = Math.max(ih + ph, getSuggestedMinimumHeight());
        int wm = MeasureSpec.getMode(ws), hm = MeasureSpec.getMode(hs);
        if (adjust && iw > 0 && ih > 0) {
            float ratio = iw / (float) ih;
            if (wm == MeasureSpec.EXACTLY && hm != MeasureSpec.EXACTLY) {
                int ww = MeasureSpec.getSize(ws);
                int hh = Math.round((ww - pw) / ratio) + ph;
                setMeasuredDimension(ww, resolveSize(Math.min(hh, maxH), hs));
                return;
            }
            if (hm == MeasureSpec.EXACTLY && wm != MeasureSpec.EXACTLY) {
                int hh = MeasureSpec.getSize(hs);
                int ww = Math.round((hh - ph) * ratio) + pw;
                setMeasuredDimension(resolveSize(Math.min(ww, maxW), ws), hh);
                return;
            }
            if (wm == MeasureSpec.AT_MOST && w > MeasureSpec.getSize(ws)) {
                int ww = MeasureSpec.getSize(ws);
                setMeasuredDimension(ww, resolveSize(Math.round((ww - pw) / ratio) + ph, hs));
                return;
            }
        }
        setMeasuredDimension(resolveSize(Math.min(w, maxW), ws), resolveSize(Math.min(h, maxH), hs));
    }

    @Override protected void onDraw(Canvas c) {
        if (d == null) return;
        int pl = getPaddingLeft(), pt = getPaddingTop();
        int aw = getWidth() - pl - getPaddingRight(), ah = getHeight() - pt - getPaddingBottom();
        if (aw <= 0 || ah <= 0) return;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float old = c.alpha;
        c.alpha = old * alpha / 255f;
        c.save();
        c.clipRect(pl, pt, pl + aw, pt + ah);
        if (iw <= 0 || ih <= 0 || scale == ScaleType.FIT_XY) {
            d.setBounds(pl, pt, pl + aw, pt + ah);
        } else {
            float s;
            switch (scale) {
                case CENTER: s = 1f; break;
                case CENTER_CROP: s = Math.max(aw / (float) iw, ah / (float) ih); break;
                case CENTER_INSIDE: s = Math.min(1f, Math.min(aw / (float) iw, ah / (float) ih)); break;
                case MATRIX: s = 1f; break;
                default: s = Math.min(aw / (float) iw, ah / (float) ih); break;
            }
            int w = Math.round(iw * s), h = Math.round(ih * s);
            int x = pl + (aw - w) / 2, y = pt + (ah - h) / 2;
            if (scale == ScaleType.FIT_START || scale == ScaleType.MATRIX) { x = pl; y = pt; }
            if (scale == ScaleType.FIT_END) { x = pl + aw - w; y = pt + ah - h; }
            d.setBounds(x, y, x + w, y + h);
        }
        if (matrix != null && scale == ScaleType.MATRIX) c.concat(matrix);
        d.draw(c);
        c.restore();
        c.alpha = old;
    }
}
