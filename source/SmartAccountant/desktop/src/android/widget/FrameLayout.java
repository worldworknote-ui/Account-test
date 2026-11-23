package android.widget;

import android.content.Context;
import android.view.*;
import java.util.ArrayList;
import java.util.List;

public class FrameLayout extends ViewGroup {
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public static final int UNSPECIFIED_GRAVITY = -1;
        public int gravity = UNSPECIFIED_GRAVITY;
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, int g) { super(w, h); gravity = g; }
        public LayoutParams(ViewGroup.LayoutParams p) {
            super(p);
            if (p instanceof LayoutParams) gravity = ((LayoutParams) p).gravity;
            else if (p instanceof LinearLayout.LayoutParams) gravity = ((LinearLayout.LayoutParams) p).gravity;
        }
        public LayoutParams(ViewGroup.MarginLayoutParams p) { this((ViewGroup.LayoutParams) p); }
        public LayoutParams(LayoutParams p) { this((ViewGroup.LayoutParams) p); }
        public LayoutParams(Context c, android.util.AttributeSet a) { super(c, a); }
    }

    private boolean measureAll;
    private final List<View> matchKids = new ArrayList<>();
    private int foregroundGravity;

    public FrameLayout(Context c) { super(c); }
    public FrameLayout(Context c, android.util.AttributeSet a) { super(c); }
    public FrameLayout(Context c, android.util.AttributeSet a, int s) { super(c); }

    public void setMeasureAllChildren(boolean m) { measureAll = m; }
    public void setForegroundGravity(int g) { foregroundGravity = g; }
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-1, -1); }
    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) { return new LayoutParams(p); }
    @Override public LayoutParams generateLayoutParams(android.util.AttributeSet a) { return new LayoutParams(getContext(), a); }
    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LayoutParams; }

    @Override protected void onMeasure(int ws, int hs) {
        int count = getChildCount();
        boolean measureMatch = MeasureSpec.getMode(ws) != MeasureSpec.EXACTLY || MeasureSpec.getMode(hs) != MeasureSpec.EXACTLY;
        matchKids.clear();
        int maxW = 0, maxH = 0, state = 0;
        for (int i = 0; i < count; i++) {
            View c = getChildAt(i);
            if (!measureAll && c.getVisibility() == GONE) continue;
            measureChildWithMargins(c, ws, 0, hs, 0);
            MarginLayoutParams p = margins(c);
            maxW = Math.max(maxW, c.getMeasuredWidth() + p.leftMargin + p.rightMargin);
            maxH = Math.max(maxH, c.getMeasuredHeight() + p.topMargin + p.bottomMargin);
            state = combineMeasuredStates(state, c.getMeasuredState());
            if (measureMatch && (p.width == LayoutParams.MATCH_PARENT || p.height == LayoutParams.MATCH_PARENT)) matchKids.add(c);
        }
        maxW += getPaddingLeft() + getPaddingRight();
        maxH += getPaddingTop() + getPaddingBottom();
        maxH = Math.max(maxH, getSuggestedMinimumHeight());
        maxW = Math.max(maxW, getSuggestedMinimumWidth());
        if (getForeground() != null) { maxH = Math.max(maxH, getForeground().getMinimumHeight()); maxW = Math.max(maxW, getForeground().getMinimumWidth()); }
        setMeasuredDimension(resolveSizeAndState(maxW, ws, state), resolveSizeAndState(maxH, hs, state << 16));
        if (matchKids.size() > 1) {
            for (View c : matchKids) {
                MarginLayoutParams p = margins(c);
                int cws = p.width == LayoutParams.MATCH_PARENT
                        ? MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight() - p.leftMargin - p.rightMargin), MeasureSpec.EXACTLY)
                        : getChildMeasureSpec(ws, getPaddingLeft() + getPaddingRight() + p.leftMargin + p.rightMargin, p.width);
                int chs = p.height == LayoutParams.MATCH_PARENT
                        ? MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom() - p.topMargin - p.bottomMargin), MeasureSpec.EXACTLY)
                        : getChildMeasureSpec(hs, getPaddingTop() + getPaddingBottom() + p.topMargin + p.bottomMargin, p.height);
                c.measure(cws, chs);
            }
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int pl = getPaddingLeft(), pr = r - l - getPaddingRight(), pt = getPaddingTop(), pb = b - t - getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            ViewGroup.LayoutParams raw = c.getLayoutParams();
            MarginLayoutParams p = margins(c);
            int w = c.getMeasuredWidth(), h = c.getMeasuredHeight();
            int g = raw instanceof LayoutParams ? ((LayoutParams) raw).gravity : -1;
            if (g == -1) g = Gravity.TOP | Gravity.START;
            int abs = Gravity.getAbsoluteGravity(g, getLayoutDirection());
            int x, y;
            switch (abs & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.CENTER_HORIZONTAL: x = pl + (pr - pl - w) / 2 + p.leftMargin - p.rightMargin; break;
                case Gravity.RIGHT: x = pr - w - p.rightMargin; break;
                default: x = pl + p.leftMargin; break;
            }
            switch (g & Gravity.VERTICAL_GRAVITY_MASK) {
                case Gravity.CENTER_VERTICAL: y = pt + (pb - pt - h) / 2 + p.topMargin - p.bottomMargin; break;
                case Gravity.BOTTOM: y = pb - h - p.bottomMargin; break;
                default: y = pt + p.topMargin; break;
            }
            c.layout(x, y, x + w, y + h);
        }
    }
}
