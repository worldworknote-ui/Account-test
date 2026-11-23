package android.view;
public interface ViewParent {
    void requestLayout();
    boolean isLayoutRequested();
    ViewParent getParent();
    void requestDisallowInterceptTouchEvent(boolean d);
    default void invalidateChild(View child, android.graphics.Rect r) {}
    default void bringChildToFront(View child) {}
    default void focusableViewAvailable(View v) {}
    default void requestChildFocus(View child, View focused) {}
    default void clearChildFocus(View child) {}
    default boolean requestChildRectangleOnScreen(View child, android.graphics.Rect r, boolean immediate) { return false; }
    default int getLayoutDirection() { return View.LAYOUT_DIRECTION_RTL; }
}
