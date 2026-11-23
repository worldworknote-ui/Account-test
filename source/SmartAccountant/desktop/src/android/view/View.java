package android.view;

import android.animation.StateListAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/**
 * العنصر الأساسي في واجهة أندرويد — نسخة الكمبيوتر: القياس والتخطيط والرسم واللمس (بالفأرة) كما في أندرويد،
 * فتعمل شاشات تطبيق الهاتف نفسها على Swing دون تعديل.
 */
public class View implements Drawable.Callback {
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
    public static final int NO_ID = -1;
    public static final int LAYOUT_DIRECTION_LTR = 0, LAYOUT_DIRECTION_RTL = 1, LAYOUT_DIRECTION_INHERIT = 2, LAYOUT_DIRECTION_LOCALE = 3;
    public static final int TEXT_DIRECTION_INHERIT = 0, TEXT_DIRECTION_FIRST_STRONG = 1, TEXT_DIRECTION_ANY_RTL = 2, TEXT_DIRECTION_LTR = 3,
            TEXT_DIRECTION_RTL = 4, TEXT_DIRECTION_LOCALE = 5, TEXT_DIRECTION_FIRST_STRONG_LTR = 6, TEXT_DIRECTION_FIRST_STRONG_RTL = 7;
    public static final int TEXT_ALIGNMENT_INHERIT = 0, TEXT_ALIGNMENT_GRAVITY = 1, TEXT_ALIGNMENT_TEXT_START = 2, TEXT_ALIGNMENT_TEXT_END = 3,
            TEXT_ALIGNMENT_CENTER = 4, TEXT_ALIGNMENT_VIEW_START = 5, TEXT_ALIGNMENT_VIEW_END = 6;
    public static final int LAYER_TYPE_NONE = 0, LAYER_TYPE_SOFTWARE = 1, LAYER_TYPE_HARDWARE = 2;
    public static final int IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0, IMPORTANT_FOR_ACCESSIBILITY_YES = 1, IMPORTANT_FOR_ACCESSIBILITY_NO = 2,
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4;
    public static final int ACCESSIBILITY_LIVE_REGION_NONE = 0, ACCESSIBILITY_LIVE_REGION_POLITE = 1, ACCESSIBILITY_LIVE_REGION_ASSERTIVE = 2;
    public static final int SYSTEM_UI_FLAG_VISIBLE = 0, SYSTEM_UI_FLAG_LIGHT_STATUS_BAR = 0x2000, SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR = 0x10,
            SYSTEM_UI_FLAG_LAYOUT_STABLE = 0x100, SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN = 0x400, SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2, SYSTEM_UI_FLAG_FULLSCREEN = 4,
            SYSTEM_UI_FLAG_IMMERSIVE_STICKY = 0x1000, SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION = 0x200;
    public static final int OVER_SCROLL_ALWAYS = 0, OVER_SCROLL_IF_CONTENT_SCROLLS = 1, OVER_SCROLL_NEVER = 2;
    public static final int SCROLLBARS_INSIDE_OVERLAY = 0, SCROLLBARS_OUTSIDE_OVERLAY = 0x02000000;
    public static final int FOCUS_DOWN = 130, FOCUS_UP = 33, FOCUS_FORWARD = 2, FOCUS_BACKWARD = 1, FOCUS_LEFT = 17, FOCUS_RIGHT = 66;
    public static final int FOCUSABLE = 1, NOT_FOCUSABLE = 0, FOCUSABLE_AUTO = 16;
    public static final int HAPTIC_FEEDBACK_ENABLED = 1;
    public static final int MEASURED_STATE_TOO_SMALL = 0x01000000, MEASURED_SIZE_MASK = 0x00ffffff, MEASURED_STATE_MASK = 0xff000000;
    public static final int DRAWING_CACHE_QUALITY_AUTO = 0, AUTOFILL_TYPE_NONE = 0, IMPORTANT_FOR_AUTOFILL_NO = 2, IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 8;
    public static final int[] EMPTY_STATE_SET = {};
    public static final int[] PRESSED_STATE_SET = {android.R.attr.state_pressed};
    public static final int[] ENABLED_STATE_SET = {android.R.attr.state_enabled};

    public static final android.util.Property<View, Float> ALPHA = prop("alpha"), TRANSLATION_X = prop("translationX"), TRANSLATION_Y = prop("translationY"),
            TRANSLATION_Z = prop("translationZ"), SCALE_X = prop("scaleX"), SCALE_Y = prop("scaleY"), ROTATION = prop("rotation"), X = prop("x"), Y = prop("y");

    private static android.util.Property<View, Float> prop(final String n) {
        return new android.util.Property<View, Float>(Float.class, n) {
            @Override public Float get(View v) { return android.animation.ObjectAnimator.getFloat(v, n); }
            @Override public void set(View v, Float f) { android.animation.ObjectAnimator.setFloat(v, n, f); }
        };
    }

    // ------------------------------------------------------------------ واجهات المستمعين
    public interface OnClickListener { void onClick(View v); }
    public interface OnLongClickListener { boolean onLongClick(View v); }
    public interface OnTouchListener { boolean onTouch(View v, MotionEvent e); }
    public interface OnFocusChangeListener { void onFocusChange(View v, boolean has); }
    public interface OnLayoutChangeListener { void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob); }
    public interface OnAttachStateChangeListener { void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v); }
    public interface OnKeyListener { boolean onKey(View v, int code, KeyEvent e); }
    public interface OnScrollChangeListener { void onScrollChange(View v, int x, int y, int ox, int oy); }
    public interface OnHoverListener { boolean onHover(View v, MotionEvent e); }
    public interface OnGenericMotionListener { boolean onGenericMotion(View v, MotionEvent e); }
    public interface OnSystemUiVisibilityChangeListener { void onSystemUiVisibilityChange(int v); }
    public interface OnCreateContextMenuListener { void onCreateContextMenu(ContextMenu m, View v, ContextMenu.ContextMenuInfo i); }
    public interface OnApplyWindowInsetsListener { WindowInsets onApplyWindowInsets(View v, WindowInsets i); }
    public interface OnDragListener { boolean onDrag(View v, DragEvent e); }

    /** مواصفات القياس كما في أندرويد. */
    public static class MeasureSpec {
        public static final int UNSPECIFIED = 0, EXACTLY = 0x40000000, AT_MOST = 0x80000000;
        private static final int MODE_MASK = 0xC0000000;
        public static int makeMeasureSpec(int size, int mode) { return (Math.max(0, size) & ~MODE_MASK) | (mode & MODE_MASK); }
        public static int getMode(int spec) { return spec & MODE_MASK; }
        public static int getSize(int spec) { return spec & ~MODE_MASK; }
        public static String toString(int s) { return (getMode(s) == EXACTLY ? "EXACTLY " : getMode(s) == AT_MOST ? "AT_MOST " : "UNSPECIFIED ") + getSize(s); }
    }

    // ------------------------------------------------------------------ الحالة
    protected final Context mContext;
    ViewParent parent;
    AttachInfo attachInfo;
    private int id = NO_ID;
    private Object tag;
    private android.util.SparseArray<Object> keyedTags;
    protected int mLeft, mTop, mRight, mBottom;
    private int measuredW, measuredH;
    private int oldWSpec = -1, oldHSpec = -1;
    boolean layoutRequested = true;
    protected int mPaddingLeft, mPaddingTop, mPaddingRight, mPaddingBottom;
    private int padStart = Integer.MIN_VALUE, padEnd = Integer.MIN_VALUE;
    private int minW, minH;
    private ViewGroup.LayoutParams params;
    private int visibility = VISIBLE;
    private boolean enabled = true, clickable, longClickable, focusable, focusableInTouch, pressed, selected, activated, hovered, focused;
    private Drawable background, foreground;
    private float alpha = 1f, tx, ty, tz, sx = 1f, sy = 1f, rot, rotX, rotY, px = Float.NaN, py = Float.NaN, elevation;
    private int layoutDir = LAYOUT_DIRECTION_INHERIT, textDir = TEXT_DIRECTION_INHERIT, textAlign = TEXT_ALIGNMENT_GRAVITY;
    private boolean clipToOutline;
    private ViewOutlineProvider outlineProvider = ViewOutlineProvider.BACKGROUND;
    OnClickListener onClick;
    OnLongClickListener onLongClick;
    OnTouchListener onTouch;
    private OnFocusChangeListener onFocus;
    private List<OnLayoutChangeListener> layoutListeners;
    private List<OnAttachStateChangeListener> attachListeners;
    private OnKeyListener onKey;
    private OnScrollChangeListener onScrollChange;
    private OnHoverListener onHover;
    private CharSequence description, tooltip;
    private StateListAnimator stateAnimator;
    protected int mScrollX, mScrollY;
    private ViewPropertyAnimator animator;
    private ViewTreeObserver floatingObserver;
    private boolean willNotDraw;
    private boolean hapticEnabled = true, soundEnabled = true;
    private boolean keepScreenOn;
    private Rect clipBounds;
    private boolean saveEnabled = true;
    private boolean verticalScrollBar = true, horizontalScrollBar = true;
    private boolean duplicateParentState;
    private int minimumHeightRes;

    public View(Context c) {
        mContext = c;
    }
    public View(Context c, android.util.AttributeSet a) { this(c); }
    public View(Context c, android.util.AttributeSet a, int s) { this(c); }

    public final Context getContext() { return mContext; }
    public android.content.res.Resources getResources() { return mContext == null ? android.content.res.Resources.getSystem() : mContext.getResources(); }

    // ------------------------------------------------------------------ الهوية والوسوم
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public Object getTag() { return tag; }
    public void setTag(Object t) { tag = t; }
    public Object getTag(int key) { return keyedTags == null ? null : keyedTags.get(key); }
    public void setTag(int key, Object t) { if (keyedTags == null) keyedTags = new android.util.SparseArray<>(); keyedTags.put(key, t); }
    public static int generateViewId() { return ++nextId; }
    private static int nextId = 0x00100000;

    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) { return (T) findViewTraversal(id); }
    @SuppressWarnings("unchecked")
    public final <T extends View> T requireViewById(int id) { T v = findViewById(id); if (v == null) throw new IllegalArgumentException(); return v; }
    protected View findViewTraversal(int id) { return this.id == id ? this : null; }
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewWithTag(Object t) { return (T) findWithTag(t); }
    protected View findWithTag(Object t) { return t != null && t.equals(tag) ? this : null; }

    // ------------------------------------------------------------------ الأب والنافذة
    public final ViewParent getParent() { return parent; }
    public View getRootView() { View v = this; while (v.parent instanceof View) v = (View) v.parent; return v; }
    public boolean isAttachedToWindow() { return attachInfo != null; }
    public android.os.IBinder getWindowToken() { return attachInfo == null ? null : attachInfo.token; }
    public android.os.IBinder getApplicationWindowToken() { return getWindowToken(); }
    public Handler getHandler() { return attachInfo == null ? null : attachInfo.handler; }
    public Display getDisplay() { return new Display(); }
    public WindowInsets getRootWindowInsets() { return new WindowInsets(); }
    public ViewRootImpl root() { return attachInfo == null ? null : attachInfo.root; }

    void dispatchAttachedToWindow(AttachInfo ai) {
        attachInfo = ai;
        if (floatingObserver != null) { ai.observer.merge(floatingObserver); floatingObserver = null; }
        onAttachedToWindow();
        if (attachListeners != null) for (OnAttachStateChangeListener l : new ArrayList<>(attachListeners)) l.onViewAttachedToWindow(this);
        refreshDrawableState();
        if (ai.pendingFocus == this) requestFocus();
    }
    void dispatchAttachedToWindowTree(AttachInfo ai) { dispatchAttachedToWindow(ai); }
    void dispatchDetachedFromWindowTree() { dispatchDetachedFromWindow(); }
    public void dispatchSetSelected(boolean s) {}
    boolean duplicatesParentState() { return duplicateParentState; }
    void dispatchDetachedFromWindow() {
        onDetachedFromWindow();
        if (attachListeners != null) for (OnAttachStateChangeListener l : new ArrayList<>(attachListeners)) l.onViewDetachedFromWindow(this);
        if (animator != null) animator.cancel();
        if (focused && attachInfo != null && attachInfo.root.focused == this) attachInfo.root.focused = null;
        focused = false;
        attachInfo = null;
    }
    protected void onAttachedToWindow() {}
    protected void onDetachedFromWindow() {}
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) { if (attachListeners == null) attachListeners = new ArrayList<>(); attachListeners.add(l); }
    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) { if (attachListeners != null) attachListeners.remove(l); }

    public ViewTreeObserver getViewTreeObserver() {
        if (attachInfo != null) return attachInfo.observer;
        if (floatingObserver == null) floatingObserver = new ViewTreeObserver();
        return floatingObserver;
    }

    // ------------------------------------------------------------------ تنفيذ لاحق
    public boolean post(Runnable r) {
        Handler h = attachInfo != null ? attachInfo.handler : AttachInfo.MAIN;
        return h.post(r);
    }
    public boolean postDelayed(Runnable r, long ms) {
        Handler h = attachInfo != null ? attachInfo.handler : AttachInfo.MAIN;
        return h.postDelayed(r, ms);
    }
    public void postOnAnimation(Runnable r) { postDelayed(r, 16); }
    public void postOnAnimationDelayed(Runnable r, long ms) { postDelayed(r, ms); }
    public boolean removeCallbacks(Runnable r) {
        if (attachInfo != null) attachInfo.handler.removeCallbacks(r);
        AttachInfo.MAIN.removeCallbacks(r);
        return true;
    }
    public void postInvalidate() { post(this::invalidate); }
    public void postInvalidateOnAnimation() { post(this::invalidate); }
    public void postInvalidateDelayed(long ms) { postDelayed(this::invalidate, ms); }

    // ------------------------------------------------------------------ الحشو
    public void setPadding(int l, int t, int r, int b) {
        padStart = Integer.MIN_VALUE; padEnd = Integer.MIN_VALUE;
        if (l != mPaddingLeft || t != mPaddingTop || r != mPaddingRight || b != mPaddingBottom) {
            mPaddingLeft = l; mPaddingTop = t; mPaddingRight = r; mPaddingBottom = b;
            requestLayout();
            invalidate();
        }
    }
    public void setPaddingRelative(int s, int t, int e, int b) {
        padStart = s; padEnd = e;
        mPaddingTop = t; mPaddingBottom = b;
        resolvePadding();
        requestLayout();
        invalidate();
    }
    void resolvePadding() {
        if (padStart == Integer.MIN_VALUE) return;
        boolean rtl = isRtl();
        mPaddingLeft = rtl ? padEnd : padStart;
        mPaddingRight = rtl ? padStart : padEnd;
    }
    public int getPaddingLeft() { resolvePadding(); return mPaddingLeft; }
    public int getPaddingRight() { resolvePadding(); return mPaddingRight; }
    public int getPaddingTop() { return mPaddingTop; }
    public int getPaddingBottom() { return mPaddingBottom; }
    public int getPaddingStart() { return isRtl() ? getPaddingRight() : getPaddingLeft(); }
    public int getPaddingEnd() { return isRtl() ? getPaddingLeft() : getPaddingRight(); }
    public boolean isPaddingRelative() { return padStart != Integer.MIN_VALUE; }

    // ------------------------------------------------------------------ المقاسات
    public void setMinimumWidth(int w) { minW = w; requestLayout(); }
    public void setMinimumHeight(int h) { minH = h; requestLayout(); }
    public int getMinimumWidth() { return minW; }
    public int getMinimumHeight() { return minH; }
    public final int getWidth() { return mRight - mLeft; }
    public final int getHeight() { return mBottom - mTop; }
    public final int getLeft() { return mLeft; }
    public final int getTop() { return mTop; }
    public final int getRight() { return mRight; }
    public final int getBottom() { return mBottom; }
    public final void setLeft(int l) { mLeft = l; invalidate(); }
    public final void setTop(int t) { mTop = t; invalidate(); }
    public final void setRight(int r) { mRight = r; invalidate(); }
    public final void setBottom(int b) { mBottom = b; invalidate(); }
    public void setLeftTopRightBottom(int l, int t, int r, int b) { setFrame(l, t, r, b); }
    public final int getMeasuredWidth() { return measuredW & MEASURED_SIZE_MASK; }
    public final int getMeasuredHeight() { return measuredH & MEASURED_SIZE_MASK; }
    public final int getMeasuredWidthAndState() { return measuredW; }
    public final int getMeasuredHeightAndState() { return measuredH; }
    public final int getMeasuredState() { return (measuredW & MEASURED_STATE_MASK) | ((measuredH >> 16) & (MEASURED_STATE_MASK >> 16)); }
    protected final void setMeasuredDimension(int w, int h) { measuredW = w; measuredH = h; }

    public ViewGroup.LayoutParams getLayoutParams() { return params; }
    public void setLayoutParams(ViewGroup.LayoutParams p) {
        if (p == null) throw new NullPointerException("Layout parameters cannot be null");
        params = p;
        if (parent instanceof ViewGroup) ((ViewGroup) parent).onSetLayoutParams(this, p);
        requestLayout();
    }
    void setParamsQuiet(ViewGroup.LayoutParams p) { params = p; }

    public final void measure(int ws, int hs) {
        boolean force = layoutRequested;
        if (force || ws != oldWSpec || hs != oldHSpec) {
            onMeasure(ws, hs);
            oldWSpec = ws;
            oldHSpec = hs;
            layoutNeeded = true;
        }
    }
    private boolean layoutNeeded = true;

    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), ws), getDefaultSize(getSuggestedMinimumHeight(), hs));
    }
    protected int getSuggestedMinimumWidth() { return background == null ? minW : Math.max(minW, background.getMinimumWidth()); }
    protected int getSuggestedMinimumHeight() { return background == null ? minH : Math.max(minH, background.getMinimumHeight()); }
    public static int getDefaultSize(int size, int spec) {
        int mode = MeasureSpec.getMode(spec), s = MeasureSpec.getSize(spec);
        return mode == MeasureSpec.UNSPECIFIED ? size : s;
    }
    public static int resolveSize(int size, int spec) { return resolveSizeAndState(size, spec, 0) & MEASURED_SIZE_MASK; }
    public static int resolveSizeAndState(int size, int spec, int childState) {
        int mode = MeasureSpec.getMode(spec), s = MeasureSpec.getSize(spec);
        int r;
        if (mode == MeasureSpec.AT_MOST) r = size > s ? s | MEASURED_STATE_TOO_SMALL : size;
        else if (mode == MeasureSpec.EXACTLY) r = s;
        else r = size;
        return r | (childState & MEASURED_STATE_MASK);
    }
    public static int combineMeasuredStates(int a, int b) { return a | b; }

    public void layout(int l, int t, int r, int b) {
        int ol = mLeft, ot = mTop, or = mRight, ob = mBottom;
        boolean changed = setFrame(l, t, r, b);
        if (changed || layoutRequested || layoutNeeded) {
            onLayout(changed, l, t, r, b);
            if (layoutListeners != null && (changed || ol != l || ot != t || or != r || ob != b)) {
                for (OnLayoutChangeListener x : new ArrayList<>(layoutListeners)) x.onLayoutChange(this, l, t, r, b, ol, ot, or, ob);
            }
        }
        layoutRequested = false;
        layoutNeeded = false;
    }
    protected boolean setFrame(int l, int t, int r, int b) {
        if (mLeft == l && mTop == t && mRight == r && mBottom == b) return false;
        int ow = getWidth(), oh = getHeight();
        mLeft = l; mTop = t; mRight = r; mBottom = b;
        if (ow != r - l || oh != b - t) onSizeChanged(r - l, b - t, ow, oh);
        if (background != null) background.setBounds(0, 0, r - l, b - t);
        invalidate();
        return true;
    }
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    protected void onSizeChanged(int w, int h, int ow, int oh) {}
    public void addOnLayoutChangeListener(OnLayoutChangeListener l) { if (layoutListeners == null) layoutListeners = new ArrayList<>(); layoutListeners.add(l); }
    public void removeOnLayoutChangeListener(OnLayoutChangeListener l) { if (layoutListeners != null) layoutListeners.remove(l); }

    public void requestLayout() {
        layoutRequested = true;
        oldWSpec = -1;
        if (parent != null && !parent.isLayoutRequested()) parent.requestLayout();
        else if (parent != null) parent.requestLayout();
    }
    public boolean isLayoutRequested() { return layoutRequested; }
    public void forceLayout() { layoutRequested = true; oldWSpec = -1; }
    public boolean isInLayout() { return attachInfo != null && attachInfo.root.inLayout; }
    public boolean isLaidOut() { return getWidth() > 0 || getHeight() > 0 || (attachInfo != null && !layoutRequested); }

    // ------------------------------------------------------------------ الرؤية والحالة
    public int getVisibility() { return visibility; }
    public void setVisibility(int v) {
        if (v == visibility) return;
        boolean gone = v == GONE || visibility == GONE;
        visibility = v;
        if (v != VISIBLE && focused) clearFocus();
        if (gone) requestLayout();
        invalidateParent();
        if (attachInfo != null) attachInfo.root.visibilityChanged();
    }
    public boolean isShown() {
        View v = this;
        while (v != null) {
            if (v.visibility != VISIBLE) return false;
            if (!(v.parent instanceof View)) return v.attachInfo != null;
            v = (View) v.parent;
        }
        return false;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { if (enabled != e) { enabled = e; refreshDrawableState(); invalidate(); } }
    public boolean isClickable() { return clickable; }
    public void setClickable(boolean c) { clickable = c; }
    public boolean isLongClickable() { return longClickable; }
    public void setLongClickable(boolean c) { longClickable = c; }
    public boolean isContextClickable() { return false; }
    public void setContextClickable(boolean c) {}
    public boolean isFocusable() { return focusable; }
    public void setFocusable(boolean f) { focusable = f; }
    public void setFocusable(int f) { focusable = f != 0; }
    public void setFocusableInTouchMode(boolean f) { focusableInTouch = f; if (f) focusable = true; }
    public boolean isFocusableInTouchMode() { return focusableInTouch; }
    public boolean isPressed() { return pressed; }
    public void setPressed(boolean p) {
        if (pressed == p) return;
        pressed = p;
        refreshDrawableState();
        dispatchSetPressed(p);
    }
    protected void dispatchSetPressed(boolean p) {}
    public boolean isSelected() { return selected; }
    public void setSelected(boolean s) { if (selected != s) { selected = s; refreshDrawableState(); invalidate(); } }
    public boolean isActivated() { return activated; }
    public void setActivated(boolean a) { if (activated != a) { activated = a; refreshDrawableState(); invalidate(); } }
    public boolean isHovered() { return hovered; }
    public void setHovered(boolean h) { if (hovered != h) { hovered = h; refreshDrawableState(); invalidate(); onHoverChanged(h); } }
    public void onHoverChanged(boolean h) {}
    public void setDuplicateParentStateEnabled(boolean d) { duplicateParentState = d; }

    public final int[] getDrawableState() { return onCreateDrawableState(0); }
    protected int[] onCreateDrawableState(int extra) {
        List<Integer> s = new ArrayList<>();
        if (enabled) s.add(android.R.attr.state_enabled);
        if (pressed) s.add(android.R.attr.state_pressed);
        if (focused) s.add(android.R.attr.state_focused);
        if (selected) s.add(android.R.attr.state_selected);
        if (activated) s.add(android.R.attr.state_activated);
        if (hovered) s.add(android.R.attr.state_hovered);
        addStates(s);
        int[] r = new int[s.size() + extra];
        for (int i = 0; i < s.size(); i++) r[i] = s.get(i);
        return r;
    }
    /** للعناصر المشتقة (checked مثلًا). */
    protected void addStates(List<Integer> s) {}
    public void refreshDrawableState() {
        drawableStateChanged();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).childDrawableStateChanged(this);
    }
    protected void drawableStateChanged() {
        int[] st = getDrawableState();
        boolean ch = false;
        if (background != null) ch |= background.setState(st);
        if (foreground != null) ch |= foreground.setState(st);
        if (stateAnimator != null) stateAnimator.setState(st);
        if (ch) invalidate();
    }
    public void jumpDrawablesToCurrentState() { if (background != null) background.jumpToCurrentState(); }
    public void drawableHotspotChanged(float x, float y) { if (background != null) background.setHotspot(x, y); }

    // ------------------------------------------------------------------ الخلفية
    public Drawable getBackground() { return background; }
    public void setBackground(Drawable d) {
        if (background == d) return;
        if (background != null) background.setCallback(null);
        background = d;
        if (d != null) {
            d.setCallback(this);
            d.setState(getDrawableState());
            d.setBounds(0, 0, getWidth(), getHeight());
            Rect p = new Rect();
            if (d.getPadding(p) && !(this instanceof android.widget.TextView && !(this instanceof android.widget.Button))) {
                // أندرويد يأخذ حشو الخلفية (InsetDrawable)
                setPadding(p.left, p.top, p.right, p.bottom);
            }
        }
        requestLayout();
        invalidate();
    }
    @Deprecated public void setBackgroundDrawable(Drawable d) { setBackground(d); }
    public void setBackgroundColor(int c) {
        if (background instanceof ColorDrawable) { ((ColorDrawable) background).setColor(c); invalidate(); }
        else setBackground(new ColorDrawable(c));
    }
    public void setBackgroundResource(int res) { setBackground(res == 0 ? null : mContext.getDrawable(res)); }
    public void setBackgroundTintList(android.content.res.ColorStateList t) { if (background != null) background.setTintList(t); invalidate(); }
    public void setBackgroundTintMode(PorterDuff.Mode m) {}
    public Drawable getForeground() { return foreground; }
    public void setForeground(Drawable d) {
        foreground = d;
        if (d != null) { d.setCallback(this); d.setState(getDrawableState()); }
        invalidate();
    }

    @Override public void invalidateDrawable(Drawable who) { invalidate(); }
    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { postDelayed(what, when - android.os.SystemClock.uptimeMillis()); }
    @Override public void unscheduleDrawable(Drawable who, Runnable what) { removeCallbacks(what); }
    protected boolean verifyDrawable(Drawable who) { return who == background || who == foreground; }

    // ------------------------------------------------------------------ التحويلات
    public float getAlpha() { return alpha; }
    public void setAlpha(float a) { if (alpha != a) { alpha = a; invalidateParent(); } }
    public float getTranslationX() { return tx; }
    public void setTranslationX(float v) { if (tx != v) { tx = v; invalidateParent(); moved(); } }
    public float getTranslationY() { return ty; }
    public void setTranslationY(float v) { if (ty != v) { ty = v; invalidateParent(); moved(); } }
    public float getTranslationZ() { return tz; }
    public void setTranslationZ(float v) { if (tz != v) { tz = v; invalidateParent(); } }
    public float getScaleX() { return sx; }
    public void setScaleX(float v) { if (sx != v) { sx = v; invalidateParent(); moved(); } }
    public float getScaleY() { return sy; }
    public void setScaleY(float v) { if (sy != v) { sy = v; invalidateParent(); moved(); } }
    public float getRotation() { return rot; }
    public void setRotation(float v) { if (rot != v) { rot = v; invalidateParent(); } }
    public float getRotationX() { return rotX; }
    public void setRotationX(float v) { rotX = v; invalidateParent(); }
    public float getRotationY() { return rotY; }
    public void setRotationY(float v) { rotY = v; invalidateParent(); }
    public float getPivotX() { return Float.isNaN(px) ? getWidth() / 2f : px; }
    public void setPivotX(float v) { px = v; invalidateParent(); }
    public float getPivotY() { return Float.isNaN(py) ? getHeight() / 2f : py; }
    public void setPivotY(float v) { py = v; invalidateParent(); }
    public void resetPivot() { px = py = Float.NaN; }
    public float getElevation() { return elevation; }
    public void setElevation(float e) { if (elevation != e) { elevation = e; invalidateParent(); } }
    public float getZ() { return elevation + tz; }
    public void setZ(float z) { setTranslationZ(z - elevation); }
    public float getX() { return mLeft + tx; }
    public void setX(float x) { setTranslationX(x - mLeft); }
    public float getY() { return mTop + ty; }
    public void setY(float y) { setTranslationY(y - mTop); }
    public void setCameraDistance(float d) {}
    public boolean hasIdentityTransform() { return sx == 1f && sy == 1f && rot == 0f; }
    public Matrix getMatrix() {
        Matrix m = new Matrix();
        m.postTranslate(-getPivotX(), -getPivotY());
        m.postScale(sx, sy);
        m.postRotate(rot);
        m.postTranslate(getPivotX() + tx, getPivotY() + ty);
        return m;
    }
    private void moved() { if (attachInfo != null) attachInfo.root.overlaysDirty(); }

    public ViewPropertyAnimator animate() {
        if (animator == null) animator = new ViewPropertyAnimator(this);
        return animator;
    }
    public void clearAnimation() { if (animator != null) animator.cancel(); }
    public void startAnimation(android.view.animation.Animation a) { if (a != null) a.start(this); }
    public android.view.animation.Animation getAnimation() { return null; }
    public void setAnimation(android.view.animation.Animation a) {}
    public StateListAnimator getStateListAnimator() { return stateAnimator; }
    public void setStateListAnimator(StateListAnimator s) {
        if (stateAnimator != null) stateAnimator.setTarget(null);
        stateAnimator = s;
        if (s != null) { s.setTarget(this); s.setState(getDrawableState()); }
    }
    public boolean hasOverlappingRendering() { return true; }

    // ------------------------------------------------------------------ الاتجاه
    public void setLayoutDirection(int d) { if (layoutDir != d) { layoutDir = d; padStart = padStart; requestLayout(); invalidate(); } }
    public int getRawLayoutDirection() { return layoutDir; }
    public int getLayoutDirection() {
        if (layoutDir == LAYOUT_DIRECTION_LTR || layoutDir == LAYOUT_DIRECTION_RTL) return layoutDir;
        if (layoutDir == LAYOUT_DIRECTION_LOCALE) return AttachInfo.localeRtl ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR;
        if (parent instanceof View) return ((View) parent).getLayoutDirection();
        return AttachInfo.localeRtl ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR;
    }
    public boolean isLayoutRtl() { return getLayoutDirection() == LAYOUT_DIRECTION_RTL; }
    protected boolean isRtl() { return getLayoutDirection() == LAYOUT_DIRECTION_RTL; }
    public void setTextDirection(int d) { textDir = d; requestLayout(); invalidate(); }
    public int getTextDirection() {
        if (textDir != TEXT_DIRECTION_INHERIT) return textDir;
        if (parent instanceof View) return ((View) parent).getTextDirection();
        return TEXT_DIRECTION_FIRST_STRONG;
    }
    public int getRawTextDirection() { return textDir; }
    public void setTextAlignment(int a) { textAlign = a; requestLayout(); invalidate(); }
    public int getTextAlignment() { return textAlign; }

    // ------------------------------------------------------------------ الشكل الخارجي
    public void setClipToOutline(boolean c) { clipToOutline = c; invalidate(); }
    public boolean getClipToOutline() { return clipToOutline; }
    public void setOutlineProvider(ViewOutlineProvider p) { outlineProvider = p; invalidate(); }
    public ViewOutlineProvider getOutlineProvider() { return outlineProvider; }
    public void invalidateOutline() { invalidate(); }
    public void setOutlineAmbientShadowColor(int c) {}
    public void setOutlineSpotShadowColor(int c) {}
    /** الشكل الخارجي بإحداثيات العنصر (للقص وللظل)، أو null. */
    public java.awt.Shape outlineShape() {
        if (outlineProvider == null) return null;
        if (outlineProvider == ViewOutlineProvider.BACKGROUND) {
            if (background == null) return null;
            java.awt.Shape s = background.outline();
            return s != null ? s : new java.awt.geom.Rectangle2D.Float(0, 0, getWidth(), getHeight());
        }
        Outline o = new Outline();
        outlineProvider.getOutline(this, o);
        return o.shape();
    }
    public void setClipBounds(Rect r) { clipBounds = r == null ? null : new Rect(r); invalidate(); }
    public Rect getClipBounds() { return clipBounds == null ? null : new Rect(clipBounds); }

    // ------------------------------------------------------------------ الرسم
    public void setWillNotDraw(boolean w) { willNotDraw = w; }
    public boolean willNotDraw() { return willNotDraw; }
    public void setLayerType(int type, Paint p) {}
    public int getLayerType() { return LAYER_TYPE_NONE; }
    public void setLayerPaint(Paint p) {}
    public void setDrawingCacheEnabled(boolean e) {}
    public void buildDrawingCache() {}
    public void destroyDrawingCache() {}

    public void invalidate() { if (attachInfo != null) attachInfo.root.invalidate(); }
    public void invalidate(Rect r) { invalidate(); }
    public void invalidate(int l, int t, int r, int b) { invalidate(); }
    protected void invalidateParent() { invalidate(); }

    /** يرسم العنصر كاملًا بإحداثياته (0,0 = أعلى يساره). */
    public void draw(Canvas c) {
        drawBackground(c);
        c.save();
        if (clipToOutline) {
            java.awt.Shape o = outlineShape();
            if (o != null) c.graphics().clip(o);
        }
        if (clipBounds != null) c.clipRect(clipBounds);
        if (mScrollX != 0 || mScrollY != 0) c.translate(-mScrollX, -mScrollY);
        onDraw(c);
        dispatchDraw(c);
        c.restore();
        onDrawForeground(c);
    }
    protected void drawBackground(Canvas c) {
        if (background == null) return;
        if (background.getBounds().width() != getWidth() || background.getBounds().height() != getHeight()) background.setBounds(0, 0, getWidth(), getHeight());
        background.draw(c);
    }
    protected void onDraw(Canvas c) {}
    protected void dispatchDraw(Canvas c) {}
    public void onDrawForeground(Canvas c) {
        if (foreground != null) {
            foreground.setBounds(0, 0, getWidth(), getHeight());
            foreground.draw(c);
        }
    }

    // ------------------------------------------------------------------ التمرير
    public final int getScrollX() { return mScrollX; }
    public final int getScrollY() { return mScrollY; }
    public void scrollTo(int x, int y) {
        if (x != mScrollX || y != mScrollY) {
            int ox = mScrollX, oy = mScrollY;
            mScrollX = x;
            mScrollY = y;
            onScrollChanged(x, y, ox, oy);
            if (onScrollChange != null) onScrollChange.onScrollChange(this, x, y, ox, oy);
            if (attachInfo != null) { attachInfo.observer.dispatchScrollChanged(); attachInfo.root.overlaysDirty(); }
            invalidate();
        }
    }
    public void scrollBy(int dx, int dy) { scrollTo(mScrollX + dx, mScrollY + dy); }
    public void setScrollX(int x) { scrollTo(x, mScrollY); }
    public void setScrollY(int y) { scrollTo(mScrollX, y); }
    protected void onScrollChanged(int l, int t, int ol, int ot) {}
    public void setOnScrollChangeListener(OnScrollChangeListener l) { onScrollChange = l; }
    /** عجلة الفأرة: true إن مرّر العنصر. */
    public boolean wheel(float dx, float dy) { return false; }
    public boolean canScrollVertically(int dir) { return false; }
    public boolean canScrollHorizontally(int dir) { return false; }
    public void setVerticalScrollBarEnabled(boolean e) { verticalScrollBar = e; }
    public void setHorizontalScrollBarEnabled(boolean e) { horizontalScrollBar = e; }
    public boolean isVerticalScrollBarEnabled() { return verticalScrollBar; }
    public boolean isHorizontalScrollBarEnabled() { return horizontalScrollBar; }
    public void setScrollBarStyle(int s) {}
    public void setScrollbarFadingEnabled(boolean f) {}
    public void setOverScrollMode(int m) {}
    public void setNestedScrollingEnabled(boolean e) {}
    public void setVerticalFadingEdgeEnabled(boolean e) {}
    public void setHorizontalFadingEdgeEnabled(boolean e) {}
    public void setFadingEdgeLength(int l) {}
    public boolean requestRectangleOnScreen(Rect r) { return requestRectangleOnScreen(r, false); }
    public boolean requestRectangleOnScreen(Rect r, boolean immediate) {
        // يمرر الآباء القابلة للتمرير حتى يظهر المستطيل
        View child = this;
        Rect rect = new Rect(r);
        ViewParent p = parent;
        while (p instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) p;
            rect.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
            g.requestChildRectangleOnScreen(child, rect, immediate);
            child = g;
            p = g.parent;
        }
        return true;
    }

    // ------------------------------------------------------------------ المواضع
    public void getLocationInWindow(int[] out) {
        float[] p = {0, 0};
        mapToRoot(p);
        out[0] = Math.round(p[0]);
        out[1] = Math.round(p[1]);
    }
    public void getLocationOnScreen(int[] out) {
        getLocationInWindow(out);
        if (attachInfo != null) {
            java.awt.Point s = attachInfo.root.screenOrigin();
            out[0] += s.x;
            out[1] += s.y;
        }
    }
    /** يحوّل نقطة من إحداثيات العنصر إلى إحداثيات الجذر (مع التحويلات والتمرير). */
    public void mapToRoot(float[] p) {
        View v = this;
        while (v != null) {
            v.mapToParent(p);
            v = v.parent instanceof View ? (View) v.parent : null;
        }
    }
    void mapToParent(float[] p) {
        if (!hasIdentityTransform()) {
            float pvx = getPivotX(), pvy = getPivotY();
            float x = p[0] - pvx, y = p[1] - pvy;
            x *= sx; y *= sy;
            if (rot != 0) {
                double a = Math.toRadians(rot);
                float nx = (float) (x * Math.cos(a) - y * Math.sin(a)), ny = (float) (x * Math.sin(a) + y * Math.cos(a));
                x = nx; y = ny;
            }
            p[0] = x + pvx; p[1] = y + pvy;
        }
        p[0] += mLeft + tx;
        p[1] += mTop + ty;
        if (parent instanceof View) {
            p[0] -= ((View) parent).mScrollX;
            p[1] -= ((View) parent).mScrollY;
        }
    }
    /** العكس: من إحداثيات الأب (بعد تمريره) إلى إحداثيات العنصر. */
    public boolean parentToLocal(float[] p) {
        p[0] -= mLeft + tx;
        p[1] -= mTop + ty;
        if (!hasIdentityTransform()) {
            float pvx = getPivotX(), pvy = getPivotY();
            float x = p[0] - pvx, y = p[1] - pvy;
            if (rot != 0) {
                double a = -Math.toRadians(rot);
                float nx = (float) (x * Math.cos(a) - y * Math.sin(a)), ny = (float) (x * Math.sin(a) + y * Math.cos(a));
                x = nx; y = ny;
            }
            if (sx != 0) x /= sx;
            if (sy != 0) y /= sy;
            p[0] = x + pvx; p[1] = y + pvy;
        }
        return true;
    }
    public boolean getGlobalVisibleRect(Rect r) {
        if (!isShown()) return false;
        int[] l = new int[2];
        getLocationInWindow(l);
        r.set(l[0], l[1], l[0] + getWidth(), l[1] + getHeight());
        if (attachInfo != null) return r.intersect(0, 0, attachInfo.root.width(), attachInfo.root.height());
        return !r.isEmpty();
    }
    public boolean getGlobalVisibleRect(Rect r, Point offset) { return getGlobalVisibleRect(r); }
    public boolean getLocalVisibleRect(Rect r) { boolean v = getGlobalVisibleRect(r); int[] l = new int[2]; getLocationInWindow(l); r.offset(-l[0], -l[1]); return v; }
    public void getHitRect(Rect r) { r.set(mLeft + (int) tx, mTop + (int) ty, mRight + (int) tx, mBottom + (int) ty); }
    public void getDrawingRect(Rect r) { r.set(mScrollX, mScrollY, mScrollX + getWidth(), mScrollY + getHeight()); }
    public void getWindowVisibleDisplayFrame(Rect r) { if (attachInfo != null) r.set(0, 0, attachInfo.root.width(), attachInfo.root.height()); }

    // ------------------------------------------------------------------ اللمس والضغط
    public void setOnClickListener(OnClickListener l) { if (!clickable) clickable = true; onClick = l; if (l == null) {} }
    public void setOnLongClickListener(OnLongClickListener l) { if (!longClickable) longClickable = true; onLongClick = l; }
    public void setOnTouchListener(OnTouchListener l) { onTouch = l; }
    public void setOnFocusChangeListener(OnFocusChangeListener l) { onFocus = l; }
    public OnFocusChangeListener getOnFocusChangeListener() { return onFocus; }
    public void setOnKeyListener(OnKeyListener l) { onKey = l; }
    public void setOnHoverListener(OnHoverListener l) { onHover = l; }
    public void setOnGenericMotionListener(OnGenericMotionListener l) {}
    public void setOnCreateContextMenuListener(OnCreateContextMenuListener l) {}
    public void setOnSystemUiVisibilityChangeListener(OnSystemUiVisibilityChangeListener l) {}
    public void setOnApplyWindowInsetsListener(OnApplyWindowInsetsListener l) {}
    public void setOnDragListener(OnDragListener l) {}
    public boolean hasOnClickListeners() { return onClick != null; }
    public boolean performClick() {
        if (onClick != null) {
            playSoundEffect(SoundEffectConstants.CLICK);
            onClick.onClick(this);
            return true;
        }
        return false;
    }
    public boolean callOnClick() { if (onClick != null) { onClick.onClick(this); return true; } return false; }
    public boolean performLongClick() { return onLongClick != null && onLongClick.onLongClick(this); }
    public boolean performLongClick(float x, float y) { return performLongClick(); }
    public boolean performContextClick() { return false; }
    public boolean showContextMenu() { return false; }
    public boolean performHapticFeedback(int c) { return true; }
    public boolean performHapticFeedback(int c, int f) { return true; }
    public void setHapticFeedbackEnabled(boolean e) { hapticEnabled = e; }
    public boolean isHapticFeedbackEnabled() { return hapticEnabled; }
    public void setSoundEffectsEnabled(boolean e) { soundEnabled = e; }
    public void playSoundEffect(int s) {}
    public boolean onKeyDown(int code, KeyEvent e) { return false; }
    public boolean onKeyUp(int code, KeyEvent e) { return false; }
    public boolean dispatchKeyEvent(KeyEvent e) { return onKey != null && onKey.onKey(this, e.getKeyCode(), e); }

    /** يُستدعى من الجذر أو من الأب. */
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (onTouch != null && enabled && onTouch.onTouch(this, e)) return true;
        return onTouchEvent(e);
    }
    boolean dispatchHover(MotionEvent e) {
        if (onHover != null && onHover.onHover(this, e)) return true;
        return onHoverEvent(e);
    }
    public boolean onHoverEvent(MotionEvent e) { return false; }
    public boolean onGenericMotionEvent(MotionEvent e) { return false; }
    public boolean dispatchGenericMotionEvent(MotionEvent e) { return onGenericMotionEvent(e); }

    private Runnable pendingLong;
    private boolean longFired;
    private float downX, downY;

    public boolean onTouchEvent(MotionEvent e) {
        boolean can = clickable || longClickable;
        if (!enabled) return can;
        if (!can) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                longFired = false;
                downX = e.getX(); downY = e.getY();
                drawableHotspotChanged(e.getX(), e.getY());
                setPressed(true);
                if (longClickable && onLongClick != null) {
                    pendingLong = () -> {
                        if (isPressed() && performLongClick()) {
                            longFired = true;
                            setPressed(false);
                        }
                    };
                    postDelayed(pendingLong, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                drawableHotspotChanged(e.getX(), e.getY());
                if (!pointInView(e.getX(), e.getY(), ViewConfiguration.TOUCH_SLOP)) {
                    cancelLong();
                    setPressed(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                boolean was = isPressed();
                cancelLong();
                setPressed(false);
                if (was && !longFired) {
                    if (attachInfo != null && attachInfo.root.focusOnClick(this)) { }
                    final MotionEvent copy = e;
                    performClickSafely();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelLong();
                setPressed(false);
                return true;
            default:
                return true;
        }
    }
    private void performClickSafely() { performClick(); }
    private void cancelLong() { if (pendingLong != null) { removeCallbacks(pendingLong); pendingLong = null; } }
    public boolean pointInView(float x, float y, float slop) { return x >= -slop && y >= -slop && x < getWidth() + slop && y < getHeight() + slop; }
    public void cancelLongPress() { cancelLong(); }

    // ------------------------------------------------------------------ التركيز
    public boolean isFocused() { return focused; }
    public boolean hasFocus() { return focused; }
    public boolean requestFocus() { return requestFocus(FOCUS_DOWN, null); }
    public boolean requestFocus(int dir) { return requestFocus(dir, null); }
    public boolean requestFocus(int dir, Rect prev) {
        if (!focusable || visibility != VISIBLE) return false;
        if (attachInfo == null) return false;
        attachInfo.root.setFocused(this);
        return true;
    }
    public final boolean requestFocusFromTouch() { return requestFocus(); }
    public void clearFocus() { if (focused && attachInfo != null) attachInfo.root.setFocused(null); else setFocusedInternal(false); }
    /** من الجذر. */
    public void setFocusedInternal(boolean f) {
        if (focused == f) return;
        focused = f;
        refreshDrawableState();
        onFocusChanged(f, FOCUS_DOWN, null);
        if (onFocus != null) onFocus.onFocusChange(this, f);
        invalidate();
    }
    protected void onFocusChanged(boolean gain, int dir, Rect prev) {}
    public View findFocus() { return focused ? this : null; }
    public View focusSearch(int dir) { return null; }
    public boolean onCheckIsTextEditor() { return false; }

    // ------------------------------------------------------------------ الوصول والوصف
    public void setContentDescription(CharSequence d) { description = d; if (attachInfo != null) attachInfo.root.tooltipChanged(this); }
    public CharSequence getContentDescription() { return description; }
    public void setTooltipText(CharSequence t) { tooltip = t; }
    public CharSequence getTooltipText() { return tooltip; }
    public void setImportantForAccessibility(int m) {}
    public int getImportantForAccessibility() { return 0; }
    public void setAccessibilityLiveRegion(int m) {}
    public void setAccessibilityDelegate(AccessibilityDelegate d) {}
    public void setAccessibilityHeading(boolean h) {}
    public void setAccessibilityPaneTitle(CharSequence t) {}
    public void setScreenReaderFocusable(boolean f) {}
    public void announceForAccessibility(CharSequence t) {}
    public void sendAccessibilityEvent(int t) {}
    public void setStateDescription(CharSequence s) {}
    public void setImportantForAutofill(int m) {}
    public void setAutofillHints(String... h) {}
    public void setLabelFor(int id) {}
    public void setNextFocusDownId(int id) {}
    public void setNextFocusForwardId(int id) {}
    public static class AccessibilityDelegate {}

    // ------------------------------------------------------------------ متفرقات
    public void setKeepScreenOn(boolean k) { keepScreenOn = k; }
    public boolean getKeepScreenOn() { return keepScreenOn; }
    public void setSystemUiVisibility(int v) {}
    public int getSystemUiVisibility() { return 0; }
    public int getWindowSystemUiVisibility() { return 0; }
    public void setFitsSystemWindows(boolean f) {}
    public WindowInsets onApplyWindowInsets(WindowInsets i) { return i; }
    public void requestApplyInsets() {}
    public void setSaveEnabled(boolean s) { saveEnabled = s; }
    public void setFilterTouchesWhenObscured(boolean f) {}
    public boolean isInEditMode() { return false; }
    public boolean isHardwareAccelerated() { return false; }
    public void setTransitionName(String n) {}
    public void setScrollContainer(boolean s) {}
    public WindowId getWindowId() { return null; }
    public int getWindowVisibility() { return attachInfo == null ? GONE : VISIBLE; }
    public boolean hasWindowFocus() { return attachInfo != null; }
    public void onWindowFocusChanged(boolean has) {}
    public boolean isDirty() { return true; }
    public boolean isOpaque() { return false; }
    public void bringToFront() { if (parent instanceof ViewGroup) ((ViewGroup) parent).bringChildToFront(this); }
    public boolean startDragAndDrop(android.content.ClipData d, DragShadowBuilder s, Object local, int flags) { return false; }
    public static class DragShadowBuilder { public DragShadowBuilder(View v) {} }
    public void setOnContextClickListener(Object l) {}
    public final void saveHierarchyState(android.util.SparseArray<android.os.Parcelable> c) {}
    public final void restoreHierarchyState(android.util.SparseArray<android.os.Parcelable> c) {}
    public void setPointerIcon(PointerIcon p) {}
    public void setTouchDelegate(TouchDelegate d) {}
    public void setDefaultFocusHighlightEnabled(boolean e) {}
    public void setRevealOnFocusHint(boolean r) {}
    public void setForceDarkAllowed(boolean f) {}
    public boolean isAccessibilityFocused() { return false; }
    public int getBaseline() { return -1; }


    @Override public String toString() { return getClass().getSimpleName() + "{" + mLeft + "," + mTop + " " + getWidth() + "x" + getHeight() + (description != null ? " '" + description + "'" : "") + "}"; }
}
