package android.view;
public interface WindowManager extends ViewManager {
    Display getDefaultDisplay();
    class LayoutParams extends ViewGroup.LayoutParams {
        public static final int FLAG_SECURE = 0x2000, FLAG_KEEP_SCREEN_ON = 0x80, FLAG_DIM_BEHIND = 2, FLAG_NOT_FOCUSABLE = 8, FLAG_NOT_TOUCHABLE = 16,
                FLAG_LAYOUT_NO_LIMITS = 0x200, FLAG_FULLSCREEN = 0x400, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS = 0x80000000, FLAG_TRANSLUCENT_STATUS = 0x4000000,
                FLAG_TRANSLUCENT_NAVIGATION = 0x8000000, FLAG_SHOW_WHEN_LOCKED = 0x80000, FLAG_ALT_FOCUSABLE_IM = 0x20000;
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x10, SOFT_INPUT_ADJUST_PAN = 0x20, SOFT_INPUT_ADJUST_NOTHING = 0x30, SOFT_INPUT_STATE_VISIBLE = 4,
                SOFT_INPUT_STATE_ALWAYS_VISIBLE = 5, SOFT_INPUT_STATE_HIDDEN = 2, SOFT_INPUT_STATE_ALWAYS_HIDDEN = 3, SOFT_INPUT_STATE_UNSPECIFIED = 0;
        public static final int TYPE_APPLICATION = 2, TYPE_APPLICATION_PANEL = 1000;
        public int flags, softInputMode, gravity, x, y, type, windowAnimations;
        public float dimAmount = 0.6f, alpha = 1f;
        public LayoutParams() { super(-1, -1); }
        public LayoutParams(int w, int h) { super(w, h); }
    }
}
