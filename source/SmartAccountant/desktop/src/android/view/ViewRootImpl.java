package android.view;

import android.graphics.Canvas;
import java.awt.*;
import java.awt.event.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import javax.swing.*;

/**
 * نافذة أندرويد واحدة (الشاشة، أو نافذة حوار، أو إشعار) فوق مكوّن Swing: تقيس العناصر وترسمها وتوصل إليها الفأرة
 * ولوحة المفاتيح. خانة الكتابة المركّزة فقط تصير خانة Swing حقيقية فوق مكانها، والباقي يُرسم كما في الهاتف.
 */
public final class ViewRootImpl implements ViewParent {
    /** نوع النافذة: الطبقة في الكومة. */
    public static final int LAYER_APP = 0, LAYER_DIALOG = 100, LAYER_POPUP = 200, LAYER_TOAST = 300;

    final View view;
    final AttachInfo ai = new AttachInfo(this);
    public final Pane pane = new Pane();
    final int layer;
    boolean inLayout;
    private boolean layoutWanted = true, traversalScheduled;
    View focused;
    private View hovered, pressedTarget;
    private boolean touchable = true;
    /** لنوافذ الحوار: لمسة خارج المحتوى. */
    public Runnable onOutsideTouch;
    public Runnable onBack;
    private boolean firstFocusDone;
    /** يُستدعى عند الضغط على مفتاح (اختصارات)؛ يعيد true إن استُهلك. */
    public java.util.function.Predicate<KeyEvent> keyHook;

    /** 11.0: مقياس الرسم العام (تكبير النافذة كلها دون إعادة بناء الشاشات). العناصر تُقاس بوحدات منطقية (dp عند كثافة 1). */
    private static float scale = 1f;
    public static float scale() { return scale; }
    public static void setScale(float s) { scale = Math.max(0.5f, Math.min(4f, s)); }
    static float lx(MouseEvent e) { return e.getX() / scale; }
    static float ly(MouseEvent e) { return e.getY() / scale; }

    public ViewRootImpl(View v, int layer) {
        this.view = v;
        this.layer = layer;
        v.parent = this;
        pane.setOpaque(false);
        pane.setLayout(null);
        pane.setFocusable(true);
        pane.setFocusTraversalKeysEnabled(false);
        Input in = new Input();
        pane.addMouseListener(in);
        pane.addMouseMotionListener(in);
        pane.addMouseWheelListener(in);
        pane.addKeyListener(in);
        v.dispatchAttachedToWindowTree(ai);
        requestLayout();
    }

    public View getView() { return view; }
    public int layer() { return layer; }
    public void setTouchable(boolean t) { touchable = t; }
    public int width() { return Math.round(pane.getWidth() / scale); }
    public int height() { return Math.round(pane.getHeight() / scale); }
    Point screenOrigin() {
        try { Point p = pane.getLocationOnScreen(); return p; } catch (Exception e) { return new Point(0, 0); }
    }

    /** إزالة النافذة (تفصل العناصر). */
    public void detach() {
        if (focused != null) setFocused(null);
        TextOverlay.hide(this);
        if (view.attachInfo != null) view.dispatchDetachedFromWindowTree();
    }

    // ------------------------------------------------------------------ ViewParent
    @Override public void requestLayout() {
        layoutWanted = true;
        if (!traversalScheduled) {
            traversalScheduled = true;
            SwingUtilities.invokeLater(this::traversal);
        }
        pane.repaint();
    }
    @Override public boolean isLayoutRequested() { return layoutWanted; }
    @Override public ViewParent getParent() { return null; }
    @Override public void requestDisallowInterceptTouchEvent(boolean d) {}
    public void invalidate() { pane.repaint(); }
    void visibilityChanged() { overlaysDirty(); }
    void overlaysDirty() { pane.repaint(); }
    void tooltipChanged(View v) {}

    /** القياس والتخطيط ثم مستمعو التخطيط (حتى 4 مرات إن طلبوا تخطيطًا جديدًا). */
    public void traversal() {
        traversalScheduled = false;
        int w = width(), h = height();
        if (w <= 0 || h <= 0) return;
        for (int pass = 0; pass < 4 && layoutWanted; pass++) {
            layoutWanted = false;
            inLayout = true;
            try {
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, w, h);
            } finally {
                inLayout = false;
            }
            ai.observer.dispatchOnGlobalLayout();
        }
        if (!firstFocusDone && view.getWidth() > 0) {
            firstFocusDone = true;
            if (focused == null) focusFirst(view);
        }
        TextOverlay.sync(this);
        pane.repaint();
    }

    /** أول خانة كتابة في النافذة تأخذ التركيز (كما في أندرويد). */
    private boolean focusFirst(View v) {
        if (v.getVisibility() != View.VISIBLE) return false;
        if (v.isFocusableInTouchMode() && v.isEnabled() && v.onCheckIsTextEditor()) { setFocused(v); return true; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (g.getDescendantFocusability() == ViewGroup.FOCUS_BLOCK_DESCENDANTS) return false;
            if (g.isFocusableInTouchMode() && g.getDescendantFocusability() == ViewGroup.FOCUS_BEFORE_DESCENDANTS) return true;
            for (int i = 0; i < g.getChildCount(); i++) if (focusFirst(g.getChildAt(i))) return true;
        }
        return false;
    }

    /** نقل التركيز إلى عنصر (أو لا شيء). */
    public void setFocused(View v) {
        if (v == focused) {
            if (v != null && v.onCheckIsTextEditor()) TextOverlay.show(this, v, -1, -1);
            return;
        }
        View old = focused;
        focused = v;
        if (old != null) old.setFocusedInternal(false);
        if (v != null) v.setFocusedInternal(true);
        ai.observer.dispatchFocus(old, v);
        if (v != null && v.onCheckIsTextEditor()) TextOverlay.show(this, v, -1, -1);
        else {
            TextOverlay.hide(this);
            if (pane.isShowing()) pane.requestFocusInWindow();
        }
        pane.repaint();
    }
    public View getFocused() { return focused; }

    boolean focusOnClick(View v) { return false; }

    /** التالي/السابق بين خانات الكتابة (Tab). */
    public void moveFocus(boolean forward) {
        java.util.List<View> all = new java.util.ArrayList<>();
        collect(view, all);
        if (all.isEmpty()) return;
        int i = all.indexOf(focused);
        int n = forward ? (i + 1) % all.size() : (i <= 0 ? all.size() - 1 : i - 1);
        View next = all.get(n);
        setFocused(next);
        next.requestRectangleOnScreen(new android.graphics.Rect(0, 0, next.getWidth(), next.getHeight()));
    }
    private void collect(View v, java.util.List<View> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v.onCheckIsTextEditor() && v.isEnabled() && v.isFocusable()) out.add(v);
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out); }
    }

    // ------------------------------------------------------------------ الرسم
    public final class Pane extends JComponent {
        @Override protected void paintComponent(Graphics g0) {
            if (layoutWanted) traversal();
            if (ai.observer.dispatchOnPreDraw()) { /* يُكمل الرسم على أي حال */ }
            Graphics2D g = (Graphics2D) g0.create();
            try {
                if (scale != 1f) g.scale(scale, scale);
                Canvas c = new Canvas(g, width(), height());
                view.draw(c);
            } finally {
                g.dispose();
            }
            ai.observer.dispatchDraw();
            TextOverlay.sync(ViewRootImpl.this);
        }
        @Override public boolean contains(int x, int y) {
            if (!touchable) return false;
            return super.contains(x, y);
        }
        public ViewRootImpl root() { return ViewRootImpl.this; }
    }

    /** يرسم النافذة في صورة (للتجارب واللقطات). */
    public java.awt.image.BufferedImage snapshot(int w, int h, float scale) {
        pane.setSize(w, h);
        traversal();
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(Math.round(w * scale), Math.round(h * scale), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.scale(scale, scale);
        view.draw(new Canvas(g, w, h));
        g.dispose();
        return im;
    }

    // ------------------------------------------------------------------ الإدخال
    private long downTime;
    private boolean down;

    private final class Input extends MouseAdapter implements KeyListener {
        @Override public void mousePressed(MouseEvent e) {
            // 11.5: زر «رجوع» الجانبي في الفأرة
            if (e.getButton() == 4 && swipeBack != null && android.app.WindowStack.roots().size() == 1) { swipeBack.run(); return; }
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            if (!pane.hasFocus() && TextOverlay.active(ViewRootImpl.this) == null) pane.requestFocusInWindow();
            downTime = android.os.SystemClock.uptimeMillis();
            down = true;
            View before = focused;
            boolean r = view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, e));
            if (!r && onOutsideTouch != null) onOutsideTouch.run();
        }
        @Override public void mouseDragged(MouseEvent e) {
            if (!down) return;
            view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, e));
            hover(e);
        }
        @Override public void mouseReleased(MouseEvent e) {
            if (!down) return;
            down = false;
            final MotionEvent up = ev(MotionEvent.ACTION_UP, e);
            View tapped = hoveredEditor(e);
            view.dispatchTouchEvent(up);
            // نقرة على خانة كتابة: التركيز ثم وضع المؤشر عند النقرة
            if (tapped != null && tapped.isEnabled()) {
                float[] p = local(tapped, lx(e), ly(e));
                if (focused != tapped) setFocused(tapped);
                TextOverlay.show(ViewRootImpl.this, tapped, p[0], p[1]);
            } else if (focused != null && focused.onCheckIsTextEditor()) {
                // نقرة خارج الخانة: يبقى التركيز كما في أندرويد
            }
        }
        @Override public void mouseMoved(MouseEvent e) { hover(e); }
        @Override public void mouseExited(MouseEvent e) { setHover(null); }
        @Override public void mouseWheelMoved(MouseWheelEvent e) {
            View v = deepest(lx(e), ly(e));
            double amt = e.getPreciseWheelRotation();
            boolean horiz = e.isShiftDown();
            float d = (float) (amt * (Math.abs(amt) < 1 ? 40 : 48));
            while (v != null) {
                if (v.wheel(horiz ? d : 0, horiz ? 0 : d)) return;
                v = v.parent instanceof View ? (View) v.parent : null;
            }
            // 11.5: سحب أفقي بإصبعين (لوحة اللمس) لم يستهلكه شيء = رجوع للصفحة السابقة
            if (horiz) swipe(d);
        }
        @Override public void keyPressed(KeyEvent e) { key(e); }
        @Override public void keyReleased(KeyEvent e) {}
        @Override public void keyTyped(KeyEvent e) {}
    }

    /** 11.5: ما يفعله السحب الأفقي وزر «رجوع» في الفأرة (الرجوع للصفحة السابقة). */
    public static Runnable swipeBack;
    private static float swipeSum;
    private static long swipeAt, swipeDone;

    static void swipe(float d) {
        long now = System.currentTimeMillis();
        if (now - swipeAt > 350) swipeSum = 0;
        swipeAt = now;
        swipeSum += d;
        if (Math.abs(swipeSum) > 260 && now - swipeDone > 900 && swipeBack != null && android.app.WindowStack.top() != null && android.app.WindowStack.roots().size() == 1) {
            swipeDone = now;
            swipeSum = 0;
            swipeBack.run();
        }
    }

    /** مفتاح من النافذة أو من خانة الكتابة؛ true إن استُهلك. */
    public boolean key(KeyEvent e) {
        if (keyHook != null && keyHook.test(e)) { e.consume(); return true; }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            if (onBack != null) onBack.run();
            e.consume();
            return true;
        }
        if (e.getKeyCode() == KeyEvent.VK_TAB) {
            moveFocus(!e.isShiftDown());
            e.consume();
            return true;
        }
        return false;
    }

    private MotionEvent ev(int action, MouseEvent e) {
        MotionEvent m = MotionEvent.raw(downTime, android.os.SystemClock.uptimeMillis(), action, lx(e), ly(e),
                (e.getXOnScreen() - e.getX()) + lx(e), (e.getYOnScreen() - e.getY()) + ly(e));
        return m;
    }

    View deepest(float x, float y) {
        if (view instanceof ViewGroup) return ((ViewGroup) view).deepestAt(x, y);
        return view;
    }

    private View hoveredEditor(MouseEvent e) {
        View v = deepest(lx(e), ly(e));
        while (v != null) {
            if (v.onCheckIsTextEditor()) return v;
            if (v.isClickable()) return null;
            v = v.parent instanceof View ? (View) v.parent : null;
        }
        return null;
    }

    static float[] local(View v, float x, float y) {
        float[] o = {0, 0};
        v.mapToRoot(o);
        return new float[]{x - o[0], y - o[1]};
    }

    private void hover(MouseEvent e) {
        View v = deepest(lx(e), ly(e));
        View target = null;
        boolean text = false;
        while (v != null) {
            if (v.onCheckIsTextEditor() && v.isEnabled()) { text = true; break; }
            if ((v.isClickable() || v.isLongClickable()) && v.isEnabled()) { target = v; break; }
            v = v.parent instanceof View ? (View) v.parent : null;
        }
        setHover(target);
        pane.setCursor(text ? Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR) : target != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        View tip = deepest(lx(e), ly(e));
        String t = null;
        while (tip != null && t == null) {
            if (tip.getTooltipText() != null) t = tip.getTooltipText().toString();
            tip = tip.parent instanceof View ? (View) tip.parent : null;
        }
        pane.setToolTipText(t);
    }

    private void setHover(View v) {
        if (hovered == v) return;
        if (hovered != null) hovered.setHovered(false);
        hovered = v;
        if (v != null) v.setHovered(true);
    }
}
