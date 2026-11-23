package android.view;

public final class MotionEvent extends InputEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, ACTION_MOVE = 2, ACTION_CANCEL = 3, ACTION_OUTSIDE = 4, ACTION_POINTER_DOWN = 5,
            ACTION_POINTER_UP = 6, ACTION_HOVER_MOVE = 7, ACTION_SCROLL = 8, ACTION_HOVER_ENTER = 9, ACTION_HOVER_EXIT = 10, ACTION_MASK = 0xff,
            ACTION_POINTER_INDEX_MASK = 0xff00, ACTION_POINTER_INDEX_SHIFT = 8, ACTION_BUTTON_PRESS = 11, ACTION_BUTTON_RELEASE = 12;
    public static final int AXIS_X = 0, AXIS_Y = 1, AXIS_VSCROLL = 9, AXIS_HSCROLL = 10;
    public static final int BUTTON_PRIMARY = 1, BUTTON_SECONDARY = 2, BUTTON_TERTIARY = 4;
    public static final int TOOL_TYPE_FINGER = 1, TOOL_TYPE_MOUSE = 3;

    private int action;
    private float x, y, rawX, rawY;
    private final long downTime, eventTime;
    private int buttons = BUTTON_PRIMARY;
    float vscroll, hscroll;

    private MotionEvent(long down, long time, int action, float x, float y) {
        downTime = down; eventTime = time; this.action = action; this.x = x; this.y = y; rawX = x; rawY = y;
    }
    public static MotionEvent obtain(long down, long time, int action, float x, float y, int meta) { return new MotionEvent(down, time, action, x, y); }
    public static MotionEvent obtain(MotionEvent o) {
        MotionEvent e = new MotionEvent(o.downTime, o.eventTime, o.action, o.x, o.y);
        e.rawX = o.rawX; e.rawY = o.rawY; e.buttons = o.buttons; e.vscroll = o.vscroll; e.hscroll = o.hscroll;
        return e;
    }
    public static MotionEvent raw(long down, long time, int action, float x, float y, float rx, float ry) {
        MotionEvent e = new MotionEvent(down, time, action, x, y);
        e.rawX = rx; e.rawY = ry;
        return e;
    }
    public final int getAction() { return action; }
    public final int getActionMasked() { return action & ACTION_MASK; }
    public final int getActionIndex() { return 0; }
    public final void setAction(int a) { action = a; }
    public final float getX() { return x; }
    public final float getY() { return y; }
    public final float getX(int i) { return x; }
    public final float getY(int i) { return y; }
    public final float getRawX() { return rawX; }
    public final float getRawY() { return rawY; }
    public final void setLocation(float nx, float ny) { x = nx; y = ny; }
    public final void offsetLocation(float dx, float dy) { x += dx; y += dy; }
    public final long getDownTime() { return downTime; }
    public final long getEventTime() { return eventTime; }
    public final int getPointerCount() { return 1; }
    public final int getPointerId(int i) { return 0; }
    public final int findPointerIndex(int id) { return id == 0 ? 0 : -1; }
    public final int getButtonState() { return buttons; }
    public final void setButtons(int b) { buttons = b; }
    public final boolean isButtonPressed(int b) { return (buttons & b) != 0; }
    public final int getToolType(int i) { return TOOL_TYPE_MOUSE; }
    public final float getPressure() { return 1f; }
    public final float getAxisValue(int axis) { return axis == AXIS_VSCROLL ? vscroll : axis == AXIS_HSCROLL ? hscroll : axis == AXIS_X ? x : y; }
    public final int getHistorySize() { return 0; }
    public final int getSource() { return 0x2002; }
    public final void recycle() {}
    @Override public String toString() { return "MotionEvent{" + action + " " + x + "," + y + "}"; }
}
