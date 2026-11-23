package android.widget;

import android.content.Context;
import android.graphics.Rect;
import android.view.*;

/** تمرير أفقي (الرقائق والعملات): السحب بالفأرة وعجلة لوحة اللمس الأفقية أو Shift+العجلة. يبدأ من اليمين في الواجهة العربية. */
public class HorizontalScrollView extends FrameLayout {
    private float lastX, startX;
    private boolean dragging, fill, placed;
    private android.animation.ValueAnimator smooth;

    public HorizontalScrollView(Context c) { super(c); }
    public HorizontalScrollView(Context c, android.util.AttributeSet a) { this(c); }
    public void setFillViewport(boolean f) { fill = f; requestLayout(); }
    public void setSmoothScrollingEnabled(boolean e) {}
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new FrameLayout.LayoutParams(-2, -1); }

    @Override protected void onMeasure(int ws, int hs) {
        if (getChildCount() == 0) { super.onMeasure(ws, hs); return; }
        View c = getChildAt(0);
        MarginLayoutParams p = margins(c);
        int chs = getChildMeasureSpec(hs, getPaddingTop() + getPaddingBottom() + p.topMargin + p.bottomMargin, p.height);
        c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), chs);
        int w = resolveSize(Math.max(getSuggestedMinimumWidth(), c.getMeasuredWidth() + getPaddingLeft() + getPaddingRight() + p.leftMargin + p.rightMargin), ws);
        int h = resolveSize(Math.max(getSuggestedMinimumHeight(), c.getMeasuredHeight() + getPaddingTop() + getPaddingBottom() + p.topMargin + p.bottomMargin), hs);
        setMeasuredDimension(w, h);
        int avail = w - getPaddingLeft() - getPaddingRight() - p.leftMargin - p.rightMargin;
        if (fill && c.getMeasuredWidth() < avail) c.measure(MeasureSpec.makeMeasureSpec(avail, MeasureSpec.EXACTLY), chs);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) return;
        View c = getChildAt(0);
        MarginLayoutParams p = margins(c);
        int x = getPaddingLeft() + p.leftMargin, y = getPaddingTop() + p.topMargin;
        int prevMax = placed ? maxScroll() : 0;
        int prevX = getScrollX();
        c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
        if (!placed) {
            placed = true;
            if (isRtl()) scrollTo(maxScroll(), 0);
        } else if (isRtl() && prevX >= prevMax) {
            scrollTo(maxScroll(), 0);
        } else scrollTo(prevX, 0);
    }
    public int maxScroll() {
        if (getChildCount() == 0) return 0;
        View c = getChildAt(0);
        MarginLayoutParams p = margins(c);
        return Math.max(0, c.getWidth() + p.leftMargin + p.rightMargin + getPaddingLeft() + getPaddingRight() - getWidth());
    }
    @Override public void scrollTo(int x, int y) { super.scrollTo(Math.max(0, Math.min(x, maxScroll())), 0); }
    public void smoothScrollTo(int x, int y) {
        if (smooth != null) smooth.cancel();
        int from = getScrollX(), to = Math.max(0, Math.min(x, maxScroll()));
        if (!isAttachedToWindow() || android.animation.Choreo.instant) { scrollTo(to, 0); return; }
        smooth = android.animation.ValueAnimator.ofInt(from, to);
        smooth.setDuration(250);
        smooth.addUpdateListener(a -> scrollTo((int) a.getAnimatedValue(), 0));
        smooth.start();
    }
    public void smoothScrollBy(int dx, int dy) { smoothScrollTo(getScrollX() + dx, 0); }
    public boolean fullScroll(int dir) { smoothScrollTo(dir == FOCUS_LEFT ? 0 : maxScroll(), 0); return true; }
    @Override public boolean canScrollHorizontally(int dir) { return dir < 0 ? getScrollX() > 0 : getScrollX() < maxScroll(); }
    @Override public boolean wheel(float dx, float dy) {
        if (dx == 0 || maxScroll() == 0) return false;
        scrollBy(Math.round(dx), 0);
        return true;
    }
    @Override public boolean requestChildRectangleOnScreen(View child, Rect r, boolean immediate) {
        if (r.left < getScrollX()) scrollTo(r.left, 0);
        else if (r.right > getScrollX() + getWidth()) scrollTo(r.right - getWidth(), 0);
        return true;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = startX = e.getX(); dragging = false; return false;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getX() - startX) > ViewConfiguration.TOUCH_SLOP && maxScroll() > 0) {
                    dragging = true;
                    lastX = e.getX();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                return dragging;
            default: dragging = false; return false;
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = startX = e.getX(); return maxScroll() > 0;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getX() - startX) > ViewConfiguration.TOUCH_SLOP) dragging = true;
                if (dragging) { scrollBy(Math.round(lastX - e.getX()), 0); lastX = e.getX(); }
                return true;
            default: dragging = false; return true;
        }
    }
}
