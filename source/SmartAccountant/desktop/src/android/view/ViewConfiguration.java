package android.view;
public class ViewConfiguration {
    public static final int TOUCH_SLOP = 8;
    public static ViewConfiguration get(android.content.Context c) { return new ViewConfiguration(); }
    public static int getLongPressTimeout() { return 500; }
    public static int getTapTimeout() { return 100; }
    public static int getDoubleTapTimeout() { return 300; }
    public static int getPressedStateDuration() { return 64; }
    public int getScaledTouchSlop() { return TOUCH_SLOP; }
    public int getScaledPagingTouchSlop() { return TOUCH_SLOP * 2; }
    public int getScaledMinimumFlingVelocity() { return 50; }
    public int getScaledMaximumFlingVelocity() { return 8000; }
    public static int getScrollBarFadeDuration() { return 250; }
}
