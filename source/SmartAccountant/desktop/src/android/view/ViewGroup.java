package android.view;

import android.content.Context;
import android.graphics.*;
import java.util.ArrayList;
import java.util.List;

public abstract class ViewGroup extends View implements ViewParent, ViewManager {
    public static final int FOCUS_BEFORE_DESCENDANTS = 0x20000, FOCUS_AFTER_DESCENDANTS = 0x40000, FOCUS_BLOCK_DESCENDANTS = 0x60000;
    public static final int CLIP_TO_PADDING_MASK = 0x22;
    public static final int LAYOUT_MODE_CLIP_BOUNDS = 0, LAYOUT_MODE_OPTICAL_BOUNDS = 1;

    public interface OnHierarchyChangeListener { void onChildViewAdded(View p, View c); void onChildViewRemoved(View p, View c); }

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1, FILL_PARENT = -1, WRAP_CONTENT = -2;
        public int width, height;
        public android.view.animation.LayoutAnimationController.AnimationParameters layoutAnimationParameters;
        public LayoutParams(int w, int h) { width = w; height = h; }
        public LayoutParams(LayoutParams o) { width = o.width; height = o.height; }
        public LayoutParams(Context c, android.util.AttributeSet a) { this(-2, -2); }
        public void resolveLayoutDirection(int dir) {}
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin, topMargin, rightMargin, bottomMargin;
        int startMargin = Integer.MIN_VALUE, endMargin = Integer.MIN_VALUE;
        public MarginLayoutParams(int w, int h) { super(w, h); }
        public MarginLayoutParams(LayoutParams o) {
            super(o);
            if (o instanceof MarginLayoutParams) {
                MarginLayoutParams m = (MarginLayoutParams) o;
                leftMargin = m.leftMargin; topMargin = m.topMargin; rightMargin = m.rightMargin; bottomMargin = m.bottomMargin;
                startMargin = m.startMargin; endMargin = m.endMargin;
            }
        }
        public MarginLayoutParams(MarginLayoutParams o) { this((LayoutParams) o); }
        public MarginLayoutParams(Context c, android.util.AttributeSet a) { super(c, a); }
        public void setMargins(int l, int t, int r, int b) { leftMargin = l; topMargin = t; rightMargin = r; bottomMargin = b; startMargin = endMargin = Integer.MIN_VALUE; }
        public void setMarginStart(int s) { startMargin = s; }
        public void setMarginEnd(int e) { endMargin = e; }
        public int getMarginStart() { return startMargin != Integer.MIN_VALUE ? startMargin : leftMargin; }
        public int getMarginEnd() { return endMargin != Integer.MIN_VALUE ? endMargin : rightMargin; }
        public boolean isMarginRelative() { return startMargin != Integer.MIN_VALUE || endMargin != Integer.MIN_VALUE; }
        @Override public void resolveLayoutDirection(int dir) {
            boolean rtl = dir == LAYOUT_DIRECTION_RTL;
            if (startMargin != Integer.MIN_VALUE) { if (rtl) rightMargin = startMargin; else leftMargin = startMargin; }
            if (endMargin != Integer.MIN_VALUE) { if (rtl) leftMargin = endMargin; else rightMargin = endMargin; }
        }
        public int getLayoutDirection() { return LAYOUT_DIRECTION_INHERIT; }
        public void setLayoutDirection(int d) {}
    }

    final List<View> children = new ArrayList<>();
    private boolean clipChildren = true, clipToPadding = true;
    private boolean disallowIntercept;
    private View touchTarget;
    private boolean selfTouch;
    private OnHierarchyChangeListener hierarchy;
    private int descendantFocus = FOCUS_BEFORE_DESCENDANTS;
    private android.animation.LayoutTransition transition;
    private boolean addStatesFromChildren;

    public ViewGroup(Context c) { super(c); }
    public ViewGroup(Context c, android.util.AttributeSet a) { super(c); }

    // ------------------------------------------------------------------ الأبناء
    public int getChildCount() { return children.size(); }
    public View getChildAt(int i) { return i < 0 || i >= children.size() ? null : children.get(i); }
    public int indexOfChild(View v) { return children.indexOf(v); }

    public void addView(View v) { addView(v, -1); }
    public void addView(View v, int index) {
        LayoutParams p = v.getLayoutParams();
        if (p == null) p = generateDefaultLayoutParams();
        addView(v, index, p);
    }
    public void addView(View v, int w, int h) { LayoutParams p = generateDefaultLayoutParams(); p.width = w; p.height = h; addView(v, -1, p); }
    @Override public void addView(View v, LayoutParams p) { addView(v, -1, p); }
    public void addView(View v, int index, LayoutParams p) {
        if (v.parent != null) throw new IllegalStateException("The specified child already has a parent. You must call removeView() on the child's parent first.");
        if (p == null) p = generateDefaultLayoutParams();
        if (!checkLayoutParams(p)) p = generateLayoutParams(p);
        v.setParamsQuiet(p);
        if (index < 0 || index > children.size()) children.add(v);
        else children.add(index, v);
        v.parent = this;
        v.requestLayout();
        requestLayout();
        invalidate();
        if (attachInfo != null) v.dispatchAttachedToWindowTree(attachInfo);
        onViewAdded(v);
        if (hierarchy != null) hierarchy.onChildViewAdded(this, v);
        if (transition != null) transition.added(this, v);
    }
    protected boolean addViewInLayout(View v, int index, LayoutParams p) { addView(v, index, p); return true; }
    @Override public void removeView(View v) {
        int i = children.indexOf(v);
        if (i >= 0) removeViewAt(i);
    }
    public void removeViewInLayout(View v) { removeView(v); }
    public void removeViewAt(int i) {
        View v = children.remove(i);
        detach(v);
    }
    public void removeViews(int start, int count) { for (int k = 0; k < count && start < children.size(); k++) removeViewAt(start); }
    public void removeViewsInLayout(int start, int count) { removeViews(start, count); }
    public void removeAllViews() {
        if (children.isEmpty()) return;
        List<View> old = new ArrayList<>(children);
        children.clear();
        for (View v : old) detach(v);
    }
    public void removeAllViewsInLayout() { removeAllViews(); }
    private void detach(View v) {
        if (touchTarget == v) touchTarget = null;
        if (v.attachInfo != null) v.dispatchDetachedFromWindowTree();
        v.parent = null;
        onViewRemoved(v);
        if (hierarchy != null) hierarchy.onChildViewRemoved(this, v);
        requestLayout();
        invalidate();
    }
    public void onViewAdded(View v) {}
    public void onViewRemoved(View v) {}
    public void setOnHierarchyChangeListener(OnHierarchyChangeListener l) { hierarchy = l; }
    @Override public void bringChildToFront(View v) {
        if (children.remove(v)) { children.add(v); requestLayout(); invalidate(); }
    }
    @Override public void updateViewLayout(View v, LayoutParams p) { v.setLayoutParams(p); }
    void onSetLayoutParams(View v, LayoutParams p) {
        if (!checkLayoutParams(p)) v.setParamsQuiet(generateLayoutParams(p));
    }
    public void detachViewFromParent(View v) { children.remove(v); }
    public void attachViewToParent(View v, int i, LayoutParams p) { addView(v, i, p); }

    protected LayoutParams generateDefaultLayoutParams() { return new MarginLayoutParams(-2, -2); }
    protected LayoutParams generateLayoutParams(LayoutParams p) { return p instanceof MarginLayoutParams ? p : new MarginLayoutParams(p); }
    public LayoutParams generateLayoutParams(android.util.AttributeSet a) { return generateDefaultLayoutParams(); }
    protected boolean checkLayoutParams(LayoutParams p) { return p != null; }

    @Override void dispatchAttachedToWindowTree(AttachInfo ai) {
        dispatchAttachedToWindow(ai);
        for (View c : new ArrayList<>(children)) c.dispatchAttachedToWindowTree(ai);
    }
    @Override void dispatchDetachedFromWindowTree() {
        for (View c : new ArrayList<>(children)) c.dispatchDetachedFromWindowTree();
        dispatchDetachedFromWindow();
    }

    @Override protected View findViewTraversal(int id) {
        if (getId() == id) return this;
        for (View c : children) { View r = c.findViewTraversal(id); if (r != null) return r; }
        return null;
    }
    @Override protected View findWithTag(Object t) {
        if (t != null && t.equals(getTag())) return this;
        for (View c : children) { View r = c.findWithTag(t); if (r != null) return r; }
        return null;
    }
    @Override public View findFocus() {
        if (isFocused()) return this;
        for (View c : children) { View f = c.findFocus(); if (f != null) return f; }
        return null;
    }
    @Override public boolean hasFocus() { return findFocus() != null; }
    @Override public boolean requestFocus(int dir, Rect prev) {
        if (descendantFocus == FOCUS_BLOCK_DESCENDANTS) return super.requestFocus(dir, prev);
        if (descendantFocus == FOCUS_BEFORE_DESCENDANTS && isFocusable() && super.requestFocus(dir, prev)) return true;
        for (View c : children) if (c.getVisibility() == VISIBLE && c.requestFocus(dir, prev)) return true;
        return descendantFocus == FOCUS_AFTER_DESCENDANTS && super.requestFocus(dir, prev);
    }
    public void setDescendantFocusability(int f) { descendantFocus = f; }
    public int getDescendantFocusability() { return descendantFocus; }
    public View getFocusedChild() { for (View c : children) if (c.findFocus() != null) return c; return null; }
    @Override public void clearFocus() { View f = findFocus(); if (f != null && f != this) f.clearFocus(); else super.clearFocus(); }

    @Override protected void dispatchSetPressed(boolean p) {
        for (View c : children) if (c.duplicatesParentState()) c.setPressed(p);
    }
    void childDrawableStateChanged(View child) { if (addStatesFromChildren) refreshDrawableState(); }
    public void setAddStatesFromChildren(boolean a) { addStatesFromChildren = a; }
    @Override public void setEnabled(boolean e) { super.setEnabled(e); }

    // ------------------------------------------------------------------ القياس
    public static int getChildMeasureSpec(int spec, int padding, int childDim) {
        int mode = MeasureSpec.getMode(spec), size = Math.max(0, MeasureSpec.getSize(spec) - padding);
        int rs = 0, rm = 0;
        switch (mode) {
            case MeasureSpec.EXACTLY:
                if (childDim >= 0) { rs = childDim; rm = MeasureSpec.EXACTLY; }
                else if (childDim == LayoutParams.MATCH_PARENT) { rs = size; rm = MeasureSpec.EXACTLY; }
                else { rs = size; rm = MeasureSpec.AT_MOST; }
                break;
            case MeasureSpec.AT_MOST:
                if (childDim >= 0) { rs = childDim; rm = MeasureSpec.EXACTLY; }
                else { rs = size; rm = MeasureSpec.AT_MOST; }
                break;
            default:
                if (childDim >= 0) { rs = childDim; rm = MeasureSpec.EXACTLY; }
                else { rs = size; rm = MeasureSpec.UNSPECIFIED; }
                break;
        }
        return MeasureSpec.makeMeasureSpec(rs, rm);
    }
    protected void measureChildren(int ws, int hs) { for (View c : children) if (c.getVisibility() != GONE) measureChild(c, ws, hs); }
    protected void measureChild(View c, int ws, int hs) {
        LayoutParams p = c.getLayoutParams();
        c.measure(getChildMeasureSpec(ws, getPaddingLeft() + getPaddingRight(), p.width), getChildMeasureSpec(hs, getPaddingTop() + getPaddingBottom(), p.height));
    }
    protected void measureChildWithMargins(View c, int ws, int usedW, int hs, int usedH) {
        MarginLayoutParams p = margins(c);
        c.measure(getChildMeasureSpec(ws, getPaddingLeft() + getPaddingRight() + p.leftMargin + p.rightMargin + usedW, p.width),
                getChildMeasureSpec(hs, getPaddingTop() + getPaddingBottom() + p.topMargin + p.bottomMargin + usedH, p.height));
    }
    /** هوامش الابن بعد حل البداية/النهاية حسب الاتجاه. */
    protected MarginLayoutParams margins(View c) {
        LayoutParams p = c.getLayoutParams();
        MarginLayoutParams m = p instanceof MarginLayoutParams ? (MarginLayoutParams) p : new MarginLayoutParams(p);
        m.resolveLayoutDirection(getLayoutDirection());
        return m;
    }

    @Override public void requestLayout() { super.requestLayout(); }
    @Override public void requestDisallowInterceptTouchEvent(boolean d) {
        disallowIntercept = d;
        if (parent != null) parent.requestDisallowInterceptTouchEvent(d);
    }
    @Override public boolean requestChildRectangleOnScreen(View child, Rect r, boolean immediate) { return false; }

    // ------------------------------------------------------------------ القص والرسم
    public void setClipChildren(boolean c) { clipChildren = c; invalidate(); }
    public boolean getClipChildren() { return clipChildren; }
    public void setClipToPadding(boolean c) { clipToPadding = c; invalidate(); }
    public boolean getClipToPadding() { return clipToPadding; }
    public void setLayoutTransition(android.animation.LayoutTransition t) { transition = t; }
    public android.animation.LayoutTransition getLayoutTransition() { return transition; }
    public void setLayoutAnimation(android.view.animation.LayoutAnimationController c) {}
    public void setMotionEventSplittingEnabled(boolean s) {}
    public void setChildrenDrawingOrderEnabled(boolean e) {}
    public void setTouchscreenBlocksFocus(boolean b) {}
    public void setTransitionGroup(boolean g) {}
    public void setLayoutMode(int m) {}
    public boolean shouldDelayChildPressedState() { return false; }
    public void startViewTransition(View v) {}
    public void endViewTransition(View v) {}
    public void suppressLayout(boolean s) {}

    /** ترتيب الرسم: حسب الارتفاع (Z) ثم الترتيب. */
    List<View> drawOrder() {
        boolean z = false;
        for (View c : children) if (c.getZ() != 0) { z = true; break; }
        if (!z) return children;
        List<View> l = new ArrayList<>(children);
        l.sort((a, b) -> Float.compare(a.getZ(), b.getZ()));
        return l;
    }

    @Override protected void dispatchDraw(Canvas c) {
        boolean clip = clipToPadding && (getPaddingLeft() | getPaddingTop() | getPaddingRight() | getPaddingBottom()) != 0;
        if (clip) {
            c.save();
            c.clipRect(mScrollX + getPaddingLeft(), mScrollY + getPaddingTop(), mScrollX + getWidth() - getPaddingRight(), mScrollY + getHeight() - getPaddingBottom());
        }
        java.awt.Rectangle vis = c.graphics().getClipBounds();
        for (View ch : new ArrayList<>(drawOrder())) {
            if (ch.getVisibility() != VISIBLE) continue;
            if (vis != null && ch.hasIdentityTransform() && ch.getZ() <= 0 && clipChildren) {
                float l = ch.getLeft() + ch.getTranslationX(), t = ch.getTop() + ch.getTranslationY();
                if (l > vis.x + vis.width || t > vis.y + vis.height || l + ch.getWidth() < vis.x || t + ch.getHeight() < vis.y) continue;
            }
            drawChild(c, ch, 0);
        }
        if (clip) c.restore();
    }

    protected boolean drawChild(Canvas c, View ch, long time) {
        float a = ch.getAlpha();
        if (a <= 0.003f) return false;
        c.save();
        c.translate(ch.getLeft() + ch.getTranslationX(), ch.getTop() + ch.getTranslationY());
        if (!ch.hasIdentityTransform()) {
            float pvx = ch.getPivotX(), pvy = ch.getPivotY();
            c.translate(pvx, pvy);
            if (ch.getRotation() != 0) c.rotate(ch.getRotation());
            c.scale(ch.getScaleX(), ch.getScaleY());
            c.translate(-pvx, -pvy);
        }
        float z = ch.getZ();
        if (z > 0.5f) Shadows.draw(c, ch, z, a);
        c.alpha *= a;
        if (clipChildren) c.clipRect(0, 0, ch.getWidth(), ch.getHeight());
        ch.draw(c);
        c.restore();
        return false;
    }

    // ------------------------------------------------------------------ اللمس
    public boolean onInterceptTouchEvent(MotionEvent e) { return false; }

    /** الابن تحت النقطة (إحداثيات العنصر بعد التمرير)، ويضع إحداثيات الابن في p. */
    View childAt(float x, float y, float[] p) {
        List<View> order = drawOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            View c = order.get(i);
            if (c.getVisibility() != VISIBLE || c.getAlpha() <= 0.003f) continue;
            float[] q = {x + mScrollX, y + mScrollY};
            c.parentToLocal(q);
            if (q[0] >= 0 && q[1] >= 0 && q[0] < c.getWidth() && q[1] < c.getHeight()) {
                p[0] = q[0]; p[1] = q[1];
                return c;
            }
        }
        return null;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            touchTarget = null;
            selfTouch = false;
            disallowIntercept = false;
        }
        if (selfTouch) return super.dispatchTouchEvent(e);
        boolean intercepted = false;
        if (act == MotionEvent.ACTION_DOWN || touchTarget != null) {
            intercepted = !disallowIntercept && onInterceptTouchEvent(e);
        }
        if (intercepted) {
            if (touchTarget != null) {
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                deliver(touchTarget, cancel);
                touchTarget = null;
            }
            selfTouch = true;
            boolean r = super.dispatchTouchEvent(e);
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) selfTouch = false;
            return r;
        }
        if (act == MotionEvent.ACTION_DOWN) {
            List<View> order = drawOrder();
            for (int i = order.size() - 1; i >= 0; i--) {
                View c = order.get(i);
                if (c.getVisibility() != VISIBLE || c.getAlpha() <= 0.003f) continue;
                float[] q = {e.getX() + mScrollX, e.getY() + mScrollY};
                c.parentToLocal(q);
                if (q[0] < 0 || q[1] < 0 || q[0] >= c.getWidth() || q[1] >= c.getHeight()) continue;
                MotionEvent ce = MotionEvent.obtain(e);
                ce.setLocation(q[0], q[1]);
                if (c.dispatchTouchEvent(ce)) {
                    touchTarget = c;
                    return true;
                }
            }
            selfTouch = true;
            boolean r = super.dispatchTouchEvent(e);
            if (!r) selfTouch = false;
            return r;
        }
        if (touchTarget == null) return false;
        View t = touchTarget;
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) touchTarget = null;
        return deliver(t, e);
    }

    private boolean deliver(View c, MotionEvent e) {
        float[] q = {e.getX() + mScrollX, e.getY() + mScrollY};
        c.parentToLocal(q);
        MotionEvent ce = MotionEvent.obtain(e);
        ce.setLocation(q[0], q[1]);
        return c.dispatchTouchEvent(ce);
    }

    /** للتحويم: أعمق عنصر تحت المؤشر. */
    public View deepestAt(float x, float y) {
        float[] p = new float[2];
        View c = childAt(x, y, p);
        if (c == null) return this;
        if (c instanceof ViewGroup) return ((ViewGroup) c).deepestAt(p[0], p[1]);
        return c;
    }

    @Override public void dispatchSetSelected(boolean s) { for (View c : children) c.setSelected(s); }
    public void dispatchSetActivated(boolean a) { for (View c : children) c.setActivated(a); }
    public void focusableViewAvailable(View v) {}
}
