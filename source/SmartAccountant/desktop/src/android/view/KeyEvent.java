package android.view;
public class KeyEvent extends InputEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1;
    public static final int KEYCODE_BACK = 4, KEYCODE_ENTER = 66, KEYCODE_DEL = 67, KEYCODE_TAB = 61, KEYCODE_ESCAPE = 111, KEYCODE_SEARCH = 84,
            KEYCODE_DPAD_UP = 19, KEYCODE_DPAD_DOWN = 20, KEYCODE_DPAD_LEFT = 21, KEYCODE_DPAD_RIGHT = 22, KEYCODE_MENU = 82, KEYCODE_NUMPAD_ENTER = 160,
            KEYCODE_F = 34, KEYCODE_N = 42, KEYCODE_S = 47, KEYCODE_V = 50, KEYCODE_PAGE_UP = 92, KEYCODE_PAGE_DOWN = 93, KEYCODE_MOVE_HOME = 122, KEYCODE_MOVE_END = 123;
    public static final int META_CTRL_ON = 0x1000, META_SHIFT_ON = 1, META_ALT_ON = 2, META_META_ON = 0x10000;
    private final int action, code, meta;
    public KeyEvent(int action, int code) { this(action, code, 0); }
    public KeyEvent(int action, int code, int meta) { this.action = action; this.code = code; this.meta = meta; }
    public final int getAction() { return action; }
    public final int getKeyCode() { return code; }
    public final int getMetaState() { return meta; }
    public final boolean isCtrlPressed() { return (meta & META_CTRL_ON) != 0; }
    public final boolean isShiftPressed() { return (meta & META_SHIFT_ON) != 0; }
    public final boolean isMetaPressed() { return (meta & META_META_ON) != 0; }
    public final int getRepeatCount() { return 0; }
    public final long getEventTime() { return System.currentTimeMillis(); }
    public int getUnicodeChar() { return 0; }
}
