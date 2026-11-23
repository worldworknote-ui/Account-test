package android.widget;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** تمرير عمودي: عجلة الفأرة ولوحة اللمس، والسحب بالفأرة، وشريط تمرير رفيع يظهر أثناء التمرير. */
public class ScrollView extends FrameLayout {
    private boolean fill;
    private float lastY, startY;
    private boolean dragging;
    private android.animation.ValueAnimator smooth;
    private long barShown;
    private boolean smoothEnabled = true;

    public ScrollView(Context c) { super(c); setVerticalScrollBarEnabled(true); }
    public ScrollView(Context c, android.util.AttributeSet a) { this(c); }

    public void setFillViewport(boolean f) { fill = f; requestLayout(); }
    public boolean isFillViewport() { return fill; }
    public void setSmoothScrollingEnabled(boolean e) { smoothEnabled = e; }
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new FrameLayout.LayoutParams(-1, -2); }

    @Override public void addView(View v, int index, ViewGroup.LayoutParams p) {
        if (getChildCount() > 0) throw new IllegalStateException("ScrollView can host only one direct child");
        super.addView(v, index, p);
    }

    @Override protected void onMeasure(int ws, int hs) {
        if (getChildCount() == 0) { super.onMeasure(ws, hs); return; }
        View c = getChildAt(0);
        MarginLayoutParams p = margins(c);
        int cws = getChildMeasureSpec(ws, getPaddingLeft() + getPaddingRight() + p.leftMargin + p.rightMargin, p.width);
        c.measure(cws, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int w = resolveSize(Math.max(getSuggestedMinimumWidth(), c.getMeasuredWidth() + getPaddingLeft() + getPaddingRight() + p.leftMargin + p.rightMargin), ws);
        int h = resolveSize(Math.max(getSuggestedMinimumHeight(), c.getMeasuredHeight() + getPaddingTop() + getPaddingBottom() + p.topMargin + p.bottomMargin), hs);
        setMeasuredDimension(w, h);
        if (fill && MeasureSpec.getMode(hs) != MeasureSpec.UNSPECIFIED) {
            int avail = h - getPaddingTop() - getPaddingBottom() - p.topMargin - p.bottomMargin;
            if (c.getMeasuredHeight() < avail) c.measure(cws, MeasureSpec.makeMeasureSpec(avail, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) return;
        View c = getChildAt(0);
        MarginLayoutParams p = margins(c);
        int x = getPaddingLeft() + p.leftMargin, y = getPaddingTop() + p.topMargin;
        c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
        clamp();
    }

    public int maxScroll() {
        if (getChildCount() == 0) return 0;
        View c = getChildAt(0);
        MarginLayoutParams p = margins(c);
        return Math.max(0, c.getHeight() + p.topMargin + p.bottomMargin + getPaddingTop() + getPaddingBottom() - getHeight());
    }
    private void clamp() { int m = maxScroll(); if (getScrollY() > m) scrollTo(getScrollX(), m); if (getScrollY() < 0) scrollTo(getScrollX(), 0); }
    @Override public void scrollTo(int x, int y) {
        int m = maxScroll();
        super.scrollTo(0, Math.max(0, Math.min(y, m)));
    }
    public void smoothScrollTo(int x, int y) {
        if (smooth != null) smooth.cancel();
        int from = getScrollY(), to = Math.max(0, Math.min(y, maxScroll()));
        if (!isAttachedToWindow() || android.animation.Choreo.instant || from == to) { scrollTo(0, to); return; }
        smooth = android.animation.ValueAnimator.ofInt(from, to);
        smooth.setDuration(250);
        smooth.setInterpolator(new android.view.animation.DecelerateInterpolator());
        smooth.addUpdateListener(a -> { scrollTo(0, (int) a.getAnimatedValue()); showBar(); });
        smooth.start();
    }
    public void smoothScrollBy(int dx, int dy) { smoothScrollTo(0, getScrollY() + dy); }
    public boolean fullScroll(int dir) { smoothScrollTo(0, dir == FOCUS_UP ? 0 : maxScroll()); return true; }
    public boolean pageScroll(int dir) { smoothScrollBy(0, dir == FOCUS_UP ? -getHeight() : getHeight()); return true; }
    public boolean arrowScroll(int dir) { return pageScroll(dir); }
    @Override public boolean canScrollVertically(int dir) { return dir < 0 ? getScrollY() > 0 : getScrollY() < maxScroll(); }
    @Override public boolean wheel(float dx, float dy) {
        if (dy == 0 || maxScroll() == 0) return false;
        if ((dy < 0 && getScrollY() == 0) || (dy > 0 && getScrollY() >= maxScroll())) return false;
        if (smooth != null) smooth.cancel();
        scrollBy(0, Math.round(dy));
        showBar();
        return true;
    }
    @Override public boolean requestChildRectangleOnScreen(View child, Rect r, boolean immediate) {
        int top = r.top, bottom = r.bottom, h = getHeight(), sy = getScrollY();
        int margin = Math.round(16 * getResources().getDisplayMetrics().density);
        if (top < sy) { scrollTo(0, top - margin); return true; }
        if (bottom > sy + h) { scrollTo(0, bottom - h + margin); return true; }
        return false;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = startY = e.getY();
                dragging = false;
                if (smooth != null) smooth.cancel();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getY() - startY) > ViewConfiguration.TOUCH_SLOP && maxScroll() > 0) {
                    dragging = true;
                    lastY = e.getY();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return dragging;
            default:
                dragging = false;
                return false;
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastY = startY = e.getY(); return maxScroll() > 0;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getY() - startY) > ViewConfiguration.TOUCH_SLOP) dragging = true;
                if (dragging) {
                    int dy = Math.round(lastY - e.getY());
                    scrollBy(0, dy);
                    // e.getY نسبي للعنصر الذي يتحرك محتواه، فنحفظ آخر نقطة
                    lastY = e.getY();
                    showBar();
                }
                return true;
            default:
                dragging = false;
                return true;
        }
    }

    private void showBar() {
        barShown = System.currentTimeMillis();
        invalidate();
        postDelayed(this::invalidate, 900);
        postDelayed(this::invalidate, 1250);
    }

    @Override public void onDrawForeground(Canvas c) {
        super.onDrawForeground(c);
        if (!isVerticalScrollBarEnabled()) return;
        int m = maxScroll();
        long age = System.currentTimeMillis() - barShown;
        if (m <= 0 || age > 1200) return;
        float a = age < 900 ? 1f : 1f - (age - 900) / 300f;
        float d = getResources().getDisplayMetrics().density;
        float h = getHeight(), total = h + m;
        float barH = Math.max(24 * d, h * h / total), y = (h - barH) * getScrollY() / m;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x66000000);
        p.setAlpha(Math.round(0x66 * a));
        float w = 3 * d;
        float x = isRtl() ? 2 * d : getWidth() - 2 * d - w;
        c.drawRoundRect(new RectF(x, y + 2 * d, x + w, y + barH - 2 * d), w / 2, w / 2, p);
    }
}
