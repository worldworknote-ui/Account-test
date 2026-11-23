package android.widget;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** دائرة انتظار تدور (أو شريط تقدم أفقي). */
public class ProgressBar extends View {
    private final boolean horizontal;
    private int max = 100, progress, secondary;
    private boolean indeterminate;
    private float angle;
    private long start = System.currentTimeMillis();
    private android.content.res.ColorStateList tint;

    public ProgressBar(Context c) { this(c, null, 0); }
    public ProgressBar(Context c, android.util.AttributeSet a) { this(c, a, 0); }
    public ProgressBar(Context c, android.util.AttributeSet a, int style) {
        super(c);
        horizontal = style == android.R.attr.progressBarStyleHorizontal || style == android.R.style.Widget_ProgressBar_Horizontal;
        indeterminate = !horizontal;
    }
    public ProgressBar(Context c, android.util.AttributeSet a, int style, int res) { this(c, a, res != 0 ? res : style); }
    public void setMax(int m) { max = Math.max(1, m); invalidate(); }
    public int getMax() { return max; }
    public void setMin(int m) {}
    public void setProgress(int p) { progress = Math.max(0, Math.min(max, p)); invalidate(); }
    public void setProgress(int p, boolean animate) { setProgress(p); }
    public int getProgress() { return progress; }
    public void setSecondaryProgress(int p) { secondary = p; invalidate(); }
    public void setIndeterminate(boolean i) { indeterminate = i; invalidate(); }
    public boolean isIndeterminate() { return indeterminate; }
    public void incrementProgressBy(int d) { setProgress(progress + d); }
    public void setProgressTintList(android.content.res.ColorStateList t) { tint = t; invalidate(); }
    public void setIndeterminateTintList(android.content.res.ColorStateList t) { tint = t; invalidate(); }
    public void setProgressDrawable(android.graphics.drawable.Drawable d) {}
    public void setIndeterminateDrawable(android.graphics.drawable.Drawable d) {}
    private int color() {
        if (tint != null) return tint.getDefaultColor();
        return getContext() instanceof android.app.Activity ? ((android.app.Activity) getContext()).themeAccent() : 0xFF0B6B53;
    }
    @Override protected void onMeasure(int ws, int hs) {
        float d = getResources().getDisplayMetrics().density;
        if (horizontal) setMeasuredDimension(resolveSize(Math.round(200 * d), ws), resolveSize(Math.round(16 * d), hs));
        else setMeasuredDimension(resolveSize(Math.round(48 * d), ws), resolveSize(Math.round(48 * d), hs));
    }
    @Override protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int col = color();
        if (horizontal) {
            float h = 4 * d, y = getHeight() / 2f - h / 2;
            p.setColor((col & 0xFFFFFF) | 0x40000000);
            c.drawRect(getPaddingLeft(), y, getWidth() - getPaddingRight(), y + h, p);
            p.setColor(col);
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            if (indeterminate) {
                float t = ((System.currentTimeMillis() - start) % 1500) / 1500f;
                float x0 = getPaddingLeft() + w * (t * 1.4f - 0.4f);
                c.save();
                c.clipRect(getPaddingLeft(), y, getWidth() - getPaddingRight(), y + h);
                c.drawRect(x0, y, x0 + w * 0.4f, y + h, p);
                c.restore();
                postInvalidateDelayed(16);
            } else {
                float pw = w * progress / max;
                if (isRtl()) c.drawRect(getWidth() - getPaddingRight() - pw, y, getWidth() - getPaddingRight(), y + h, p);
                else c.drawRect(getPaddingLeft(), y, getPaddingLeft() + pw, y + h, p);
            }
            return;
        }
        float size = Math.min(getWidth(), getHeight()) - 8 * d;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = size / 2f - 2 * d;
        long t = System.currentTimeMillis() - start;
        float rot = (t % 1333) / 1333f * 360f;
        float sweep = 30 + 240 * (float) (0.5 - 0.5 * Math.cos((t % 1333) / 1333.0 * 2 * Math.PI));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(col);
        c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), rot * 2 - 90, sweep, false, p);
        if (isShown()) postInvalidateDelayed(16);
    }
}
