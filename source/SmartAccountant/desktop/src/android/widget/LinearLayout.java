package android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.*;

/** LinearLayout كما في أندرويد: الاتجاه، الأوزان، المحاذاة، الهوامش، خط الأساس، والاتجاه من اليمين. */
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0, VERTICAL = 1;
    public static final int SHOW_DIVIDER_NONE = 0, SHOW_DIVIDER_BEGINNING = 1, SHOW_DIVIDER_MIDDLE = 2, SHOW_DIVIDER_END = 4;

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public float weight;
        public int gravity = -1;
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, float weight) { super(w, h); this.weight = weight; }
        public LayoutParams(ViewGroup.LayoutParams p) {
            super(p);
            if (p instanceof LayoutParams) { weight = ((LayoutParams) p).weight; gravity = ((LayoutParams) p).gravity; }
        }
        public LayoutParams(ViewGroup.MarginLayoutParams p) { this((ViewGroup.LayoutParams) p); }
        public LayoutParams(LayoutParams p) { this((ViewGroup.LayoutParams) p); }
        public LayoutParams(Context c, android.util.AttributeSet a) { super(c, a); }
    }

    private int orientation = HORIZONTAL;
    private int gravity = Gravity.START | Gravity.TOP;
    private boolean baselineAligned = true;
    private float weightSum = -1;
    private int totalLength;
    private Drawable divider;
    private int showDividers, dividerPadding;
    private boolean useLargestChild;
    private int maxAscent, maxDescent;

    public LinearLayout(Context c) { super(c); }
    public LinearLayout(Context c, android.util.AttributeSet a) { super(c); }
    public LinearLayout(Context c, android.util.AttributeSet a, int s) { super(c); }

    public void setOrientation(int o) { if (orientation != o) { orientation = o; requestLayout(); } }
    public int getOrientation() { return orientation; }
    public void setGravity(int g) {
        if ((g & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) g |= Gravity.START;
        if ((g & Gravity.VERTICAL_GRAVITY_MASK) == 0) g |= Gravity.TOP;
        if (gravity != g) { gravity = g; requestLayout(); }
    }
    public int getGravity() { return gravity; }
    public void setHorizontalGravity(int h) { setGravity((gravity & ~Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK & ~Gravity.HORIZONTAL_GRAVITY_MASK) | h); }
    public void setVerticalGravity(int v) { setGravity((gravity & ~Gravity.VERTICAL_GRAVITY_MASK) | v); }
    public void setBaselineAligned(boolean b) { baselineAligned = b; requestLayout(); }
    public boolean isBaselineAligned() { return baselineAligned; }
    public void setWeightSum(float s) { weightSum = s; requestLayout(); }
    public float getWeightSum() { return weightSum; }
    public void setMeasureWithLargestChildEnabled(boolean e) { useLargestChild = e; requestLayout(); }
    public void setDividerDrawable(Drawable d) { divider = d; requestLayout(); invalidate(); }
    public Drawable getDividerDrawable() { return divider; }
    public void setShowDividers(int s) { showDividers = s; requestLayout(); invalidate(); }
    public int getShowDividers() { return showDividers; }
    public void setDividerPadding(int p) { dividerPadding = p; invalidate(); }

    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return orientation == HORIZONTAL ? new LayoutParams(-2, -2) : new LayoutParams(-1, -2);
    }
    @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) { return new LayoutParams(p); }
    @Override public LayoutParams generateLayoutParams(android.util.AttributeSet a) { return new LayoutParams(getContext(), a); }
    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LayoutParams; }

    private LayoutParams lp(View c) {
        LayoutParams p = (LayoutParams) c.getLayoutParams();
        p.resolveLayoutDirection(getLayoutDirection());
        return p;
    }
    private boolean hasDividerBefore(int i) {
        if (divider == null) return false;
        if (i == 0) return (showDividers & SHOW_DIVIDER_BEGINNING) != 0;
        if (i == getChildCount()) return (showDividers & SHOW_DIVIDER_END) != 0;
        if ((showDividers & SHOW_DIVIDER_MIDDLE) != 0) {
            for (int k = i - 1; k >= 0; k--) if (getChildAt(k).getVisibility() != GONE) return true;
        }
        return false;
    }
    private int divH() { return divider == null ? 0 : Math.max(0, divider.getIntrinsicHeight()); }
    private int divW() { return divider == null ? 0 : Math.max(0, divider.getIntrinsicWidth()); }

    @Override protected void onMeasure(int ws, int hs) {
        if (orientation == VERTICAL) measureVertical(ws, hs); else measureHorizontal(ws, hs);
    }

    // ------------------------------------------------------------------ عمودي
    private void measureVertical(int ws, int hs) {
        totalLength = 0;
        int maxW = 0, state = 0;
        float total = 0;
        int count = getChildCount();
        int wm = MeasureSpec.getMode(ws), hm = MeasureSpec.getMode(hs);
        boolean matchW = false, allMatch = true;
        int weightedMaxW = 0, altMaxW = 0;
        boolean skipped = false;
        int largest = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            if (hasDividerBefore(i)) totalLength += divH();
            LayoutParams p = lp(c);
            total += p.weight;
            boolean useExcess = p.height == 0 && p.weight > 0;
            if (hm == MeasureSpec.EXACTLY && useExcess) {
                totalLength = Math.max(totalLength, totalLength + p.topMargin + p.bottomMargin);
                skipped = true;
            } else {
                if (useExcess) p.height = LayoutParams.WRAP_CONTENT;
                measureChildWithMargins(c, ws, 0, hs, total == 0 ? totalLength : 0);
                if (useExcess) p.height = 0;
                int ch = c.getMeasuredHeight();
                totalLength = Math.max(totalLength, totalLength + ch + p.topMargin + p.bottomMargin);
                if (useLargestChild) largest = Math.max(ch, largest);
            }
            boolean matchThis = false;
            if (wm != MeasureSpec.EXACTLY && p.width == LayoutParams.MATCH_PARENT) { matchW = true; matchThis = true; }
            int margin = p.leftMargin + p.rightMargin;
            int mw = c.getMeasuredWidth() + margin;
            maxW = Math.max(maxW, mw);
            state = combineMeasuredStates(state, c.getMeasuredState());
            allMatch = allMatch && p.width == LayoutParams.MATCH_PARENT;
            if (p.weight > 0) weightedMaxW = Math.max(weightedMaxW, matchThis ? margin : mw);
            else altMaxW = Math.max(altMaxW, matchThis ? margin : mw);
        }
        if (totalLength > 0 && hasDividerBefore(count)) totalLength += divH();
        totalLength += getPaddingTop() + getPaddingBottom();
        int hSize = Math.max(totalLength, getSuggestedMinimumHeight());
        int hss = resolveSizeAndState(hSize, hs, 0);
        hSize = hss & MEASURED_SIZE_MASK;
        int remaining = hSize - totalLength + (skipped ? 0 : 0);
        if (skipped || (remaining != 0 && total > 0)) {
            float ws2 = weightSum > 0 ? weightSum : total;
            totalLength = 0;
            remaining = hSize - (computeFixed(true) + getPaddingTop() + getPaddingBottom());
            for (int i = 0; i < count; i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                if (hasDividerBefore(i)) totalLength += divH();
                LayoutParams p = lp(c);
                float cw = p.weight;
                if (cw > 0) {
                    int share = (int) (cw * remaining / ws2);
                    remaining -= share;
                    ws2 -= cw;
                    int childH;
                    if (p.height == 0 && hm == MeasureSpec.EXACTLY) childH = share;
                    else childH = c.getMeasuredHeight() + share;
                    childH = Math.max(0, childH);
                    int cws = getChildMeasureSpec(ws, getPaddingLeft() + getPaddingRight() + p.leftMargin + p.rightMargin, p.width);
                    c.measure(cws, MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY));
                    state = combineMeasuredStates(state, c.getMeasuredState() & (MEASURED_STATE_MASK >> 16));
                }
                int margin = p.leftMargin + p.rightMargin;
                int mw = c.getMeasuredWidth() + margin;
                maxW = Math.max(maxW, mw);
                boolean matchThis = wm != MeasureSpec.EXACTLY && p.width == LayoutParams.MATCH_PARENT;
                altMaxW = Math.max(altMaxW, matchThis ? margin : mw);
                totalLength = Math.max(totalLength, totalLength + c.getMeasuredHeight() + p.topMargin + p.bottomMargin);
            }
            totalLength += getPaddingTop() + getPaddingBottom();
        } else {
            altMaxW = Math.max(altMaxW, weightedMaxW);
        }
        if (!allMatch && wm != MeasureSpec.EXACTLY) maxW = altMaxW;
        maxW += getPaddingLeft() + getPaddingRight();
        maxW = Math.max(maxW, getSuggestedMinimumWidth());
        setMeasuredDimension(resolveSizeAndState(maxW, ws, state), hss);
        if (matchW) forceUniformWidth(count, hs);
    }

    /** مجموع ما يشغله غير الموزون (لإعادة توزيع المساحة الباقية). */
    private int computeFixed(boolean vertical) {
        int sum = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            if (hasDividerBefore(i)) sum += vertical ? divH() : divW();
            LayoutParams p = lp(c);
            if (vertical) {
                sum += p.topMargin + p.bottomMargin;
                if (!(p.weight > 0 && p.height == 0)) sum += p.weight > 0 ? c.getMeasuredHeight() : c.getMeasuredHeight();
            } else {
                sum += p.leftMargin + p.rightMargin;
                if (!(p.weight > 0 && p.width == 0)) sum += c.getMeasuredWidth();
            }
        }
        if (hasDividerBefore(getChildCount())) sum += vertical ? divH() : divW();
        return sum;
    }

    private void forceUniformWidth(int count, int hs) {
        int uniform = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        for (int i = 0; i < count; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            LayoutParams p = lp(c);
            if (p.width == LayoutParams.MATCH_PARENT) {
                int oldH = p.height;
                p.height = c.getMeasuredHeight();
                measureChildWithMargins(c, uniform, 0, hs, 0);
                p.height = oldH;
            }
        }
    }

    // ------------------------------------------------------------------ أفقي
    private void measureHorizontal(int ws, int hs) {
        totalLength = 0;
        int maxH = 0, state = 0;
        float total = 0;
        int count = getChildCount();
        int wm = MeasureSpec.getMode(ws), hm = MeasureSpec.getMode(hs);
        boolean matchH = false, allMatch = true;
        int weightedMaxH = 0, altMaxH = 0;
        maxAscent = -1;
        maxDescent = -1;
        boolean base = baselineAligned;
        boolean skipped = false;
        for (int i = 0; i < count; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            if (hasDividerBefore(i)) totalLength += divW();
            LayoutParams p = lp(c);
            total += p.weight;
            boolean useExcess = p.width == 0 && p.weight > 0;
            if (wm == MeasureSpec.EXACTLY && useExcess) {
                totalLength += p.leftMargin + p.rightMargin;
                skipped = true;
                if (base) {
                    int fs = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                    c.measure(fs, getChildMeasureSpec(hs, getPaddingTop() + getPaddingBottom() + p.topMargin + p.bottomMargin, p.height));
                }
            } else {
                if (useExcess) p.width = LayoutParams.WRAP_CONTENT;
                measureChildWithMargins(c, ws, total == 0 ? totalLength : 0, hs, 0);
                if (useExcess) p.width = 0;
                totalLength += c.getMeasuredWidth() + p.leftMargin + p.rightMargin;
            }
            boolean matchThis = false;
            if (hm != MeasureSpec.EXACTLY && p.height == LayoutParams.MATCH_PARENT) { matchH = true; matchThis = true; }
            int margin = p.topMargin + p.bottomMargin;
            int ch = c.getMeasuredHeight() + margin;
            state = combineMeasuredStates(state, c.getMeasuredState());
            if (base) {
                int bl = c.getBaseline();
                if (bl != -1) {
                    int g = (p.gravity < 0 ? gravity : p.gravity) & Gravity.VERTICAL_GRAVITY_MASK;
                    if (g == Gravity.TOP || g == Gravity.BOTTOM || g == 0) {
                        maxAscent = Math.max(maxAscent, bl);
                        maxDescent = Math.max(maxDescent, ch - margin - bl);
                    }
                }
            }
            maxH = Math.max(maxH, ch);
            allMatch = allMatch && p.height == LayoutParams.MATCH_PARENT;
            if (p.weight > 0) weightedMaxH = Math.max(weightedMaxH, matchThis ? margin : ch);
            else altMaxH = Math.max(altMaxH, matchThis ? margin : ch);
        }
        if (totalLength > 0 && hasDividerBefore(count)) totalLength += divW();
        if (maxAscent != -1) maxH = Math.max(maxH, maxAscent + maxDescent);
        totalLength += getPaddingLeft() + getPaddingRight();
        int wSize = Math.max(totalLength, getSuggestedMinimumWidth());
        int wss = resolveSizeAndState(wSize, ws, 0);
        wSize = wss & MEASURED_SIZE_MASK;
        int remaining = wSize - totalLength;
        if (skipped || (remaining != 0 && total > 0)) {
            float ws2 = weightSum > 0 ? weightSum : total;
            remaining = wSize - (computeFixed(false) + getPaddingLeft() + getPaddingRight());
            maxAscent = maxDescent = -1;
            maxH = -1;
            totalLength = 0;
            for (int i = 0; i < count; i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                if (hasDividerBefore(i)) totalLength += divW();
                LayoutParams p = lp(c);
                float cw = p.weight;
                if (cw > 0) {
                    int share = (int) (cw * remaining / ws2);
                    remaining -= share;
                    ws2 -= cw;
                    int childW;
                    if (p.width == 0 && wm == MeasureSpec.EXACTLY) childW = share;
                    else childW = c.getMeasuredWidth() + share;
                    childW = Math.max(0, childW);
                    int chs = getChildMeasureSpec(hs, getPaddingTop() + getPaddingBottom() + p.topMargin + p.bottomMargin, p.height);
                    c.measure(MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY), chs);
                    state = combineMeasuredStates(state, c.getMeasuredState() & MEASURED_STATE_MASK);
                }
                totalLength += c.getMeasuredWidth() + p.leftMargin + p.rightMargin;
                boolean matchThis = hm != MeasureSpec.EXACTLY && p.height == LayoutParams.MATCH_PARENT;
                int margin = p.topMargin + p.bottomMargin;
                int ch = c.getMeasuredHeight() + margin;
                maxH = Math.max(maxH, ch);
                altMaxH = Math.max(altMaxH, matchThis ? margin : ch);
                allMatch = allMatch && p.height == LayoutParams.MATCH_PARENT;
                if (base) {
                    int bl = c.getBaseline();
                    if (bl != -1) {
                        int g = (p.gravity < 0 ? gravity : p.gravity) & Gravity.VERTICAL_GRAVITY_MASK;
                        if (g == Gravity.TOP || g == Gravity.BOTTOM || g == 0) {
                            maxAscent = Math.max(maxAscent, bl);
                            maxDescent = Math.max(maxDescent, ch - margin - bl);
                        }
                    }
                }
            }
            totalLength += getPaddingLeft() + getPaddingRight();
            if (maxAscent != -1) maxH = Math.max(maxH, maxAscent + maxDescent);
        } else {
            altMaxH = Math.max(altMaxH, weightedMaxH);
        }
        if (!allMatch && hm != MeasureSpec.EXACTLY) maxH = altMaxH;
        if (maxAscent != -1 && !allMatch && hm != MeasureSpec.EXACTLY) maxH = Math.max(maxH, maxAscent + maxDescent);
        maxH += getPaddingTop() + getPaddingBottom();
        maxH = Math.max(maxH, getSuggestedMinimumHeight());
        setMeasuredDimension(wss | (state & MEASURED_STATE_MASK), resolveSizeAndState(maxH, hs, state << 16));
        if (matchH) forceUniformHeight(count, ws);
    }

    private void forceUniformHeight(int count, int ws) {
        int uniform = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        for (int i = 0; i < count; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            LayoutParams p = lp(c);
            if (p.height == LayoutParams.MATCH_PARENT) {
                int oldW = p.width;
                p.width = c.getMeasuredWidth();
                measureChildWithMargins(c, ws, 0, uniform, 0);
                p.width = oldW;
            }
        }
    }

    @Override public int getBaseline() {
        if (orientation != VERTICAL || getChildCount() == 0) {
            if (maxAscent > 0) return getPaddingTop() + maxAscent;
            return -1;
        }
        View c = getChildAt(0);
        if (c == null || c.getVisibility() == GONE) return -1;
        int b = c.getBaseline();
        return b == -1 ? -1 : c.getTop() + b;
    }

    // ------------------------------------------------------------------ التخطيط
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (orientation == VERTICAL) layoutVertical(l, t, r, b); else layoutHorizontal(l, t, r, b);
    }

    private void layoutVertical(int l, int t, int r, int b) {
        int pl = getPaddingLeft();
        int width = r - l, childRight = width - getPaddingRight(), space = childRight - pl;
        int count = getChildCount();
        int major = gravity & Gravity.VERTICAL_GRAVITY_MASK, minor = gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
        int top;
        switch (major) {
            case Gravity.BOTTOM: top = getPaddingTop() + b - t - totalLength; break;
            case Gravity.CENTER_VERTICAL: top = getPaddingTop() + (b - t - totalLength) / 2; break;
            default: top = getPaddingTop(); break;
        }
        for (int i = 0; i < count; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            LayoutParams p = lp(c);
            int g = p.gravity < 0 ? minor : p.gravity;
            int abs = Gravity.getAbsoluteGravity(g, getLayoutDirection());
            int x;
            switch (abs & Gravity.HORIZONTAL_GRAVITY_MASK) {
                case Gravity.CENTER_HORIZONTAL: x = pl + (space - cw) / 2 + p.leftMargin - p.rightMargin; break;
                case Gravity.RIGHT: x = childRight - cw - p.rightMargin; break;
                default: x = pl + p.leftMargin; break;
            }
            if (hasDividerBefore(i)) top += divH();
            top += p.topMargin;
            c.layout(x, top, x + cw, top + ch);
            top += ch + p.bottomMargin;
        }
    }

    private void layoutHorizontal(int l, int t, int r, int b) {
        boolean rtl = isRtl();
        int pt = getPaddingTop();
        int height = b - t, childBottom = height - getPaddingBottom(), space = childBottom - pt;
        int count = getChildCount();
        int major = Gravity.getAbsoluteGravity(gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK, getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
        int minor = gravity & Gravity.VERTICAL_GRAVITY_MASK;
        int left;
        switch (major) {
            case Gravity.RIGHT: left = getPaddingLeft() + r - l - totalLength; break;
            case Gravity.CENTER_HORIZONTAL: left = getPaddingLeft() + (r - l - totalLength) / 2; break;
            default: left = getPaddingLeft(); break;
        }
        int start = 0, dir = 1;
        if (rtl) { start = count - 1; dir = -1; }
        for (int k = 0; k < count; k++) {
            int i = start + dir * k;
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            LayoutParams p = lp(c);
            int bl = -1;
            if (baselineAligned && p.height != LayoutParams.MATCH_PARENT) bl = c.getBaseline();
            int g = p.gravity < 0 ? minor : p.gravity;
            int y;
            switch (g & Gravity.VERTICAL_GRAVITY_MASK) {
                case Gravity.CENTER_VERTICAL: y = pt + (space - ch) / 2 + p.topMargin - p.bottomMargin; break;
                case Gravity.BOTTOM:
                    y = childBottom - ch - p.bottomMargin;
                    if (bl != -1 && maxDescent > 0) y -= maxDescent - (ch - bl);
                    break;
                default:
                    y = pt + p.topMargin;
                    if (bl != -1 && maxAscent > 0) y += maxAscent - bl;
                    break;
            }
            if (hasDividerBefore(i)) left += divW();
            left += p.leftMargin;
            c.layout(left, y, left + cw, y + ch);
            left += cw + p.rightMargin;
        }
    }

    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (divider == null || showDividers == 0) return;
        for (int i = 0; i <= getChildCount(); i++) {
            if (!hasDividerBefore(i)) continue;
            View ch = i < getChildCount() ? getChildAt(i) : null;
            if (ch != null && ch.getVisibility() == GONE) continue;
            if (orientation == VERTICAL) {
                int top = ch != null ? ch.getTop() - lp(ch).topMargin - divH() : getHeight() - getPaddingBottom() - divH();
                divider.setBounds(getPaddingLeft() + dividerPadding, top, getWidth() - getPaddingRight() - dividerPadding, top + divH());
            } else {
                int left = ch != null ? (isRtl() ? ch.getRight() + lp(ch).rightMargin : ch.getLeft() - lp(ch).leftMargin - divW()) : getPaddingLeft();
                divider.setBounds(left, getPaddingTop() + dividerPadding, left + divW(), getHeight() - getPaddingBottom() - dividerPadding);
            }
            divider.draw(c);
        }
    }
}
