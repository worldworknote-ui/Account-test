package android.view;

public class Gravity {
    public static final int NO_GRAVITY = 0, AXIS_SPECIFIED = 1, AXIS_PULL_BEFORE = 2, AXIS_PULL_AFTER = 4, AXIS_CLIP = 8, AXIS_X_SHIFT = 0, AXIS_Y_SHIFT = 4;
    public static final int TOP = 0x30, BOTTOM = 0x50, LEFT = 0x03, RIGHT = 0x05, CENTER_VERTICAL = 0x10, CENTER_HORIZONTAL = 0x01,
            FILL_VERTICAL = 0x70, FILL_HORIZONTAL = 0x07, CENTER = 0x11, FILL = 0x77, CLIP_VERTICAL = 0x80, CLIP_HORIZONTAL = 0x08,
            RELATIVE_LAYOUT_DIRECTION = 0x00800000, START = RELATIVE_LAYOUT_DIRECTION | LEFT, END = RELATIVE_LAYOUT_DIRECTION | RIGHT,
            HORIZONTAL_GRAVITY_MASK = 0x07, VERTICAL_GRAVITY_MASK = 0x70, RELATIVE_HORIZONTAL_GRAVITY_MASK = START | END, DISPLAY_CLIP_VERTICAL = 0x10000000,
            DISPLAY_CLIP_HORIZONTAL = 0x01000000;

    /** يحوّل START/END إلى يسار/يمين حسب الاتجاه. */
    public static int getAbsoluteGravity(int g, int dir) {
        int r = g;
        if ((r & RELATIVE_LAYOUT_DIRECTION) > 0) {
            boolean rtl = dir == View.LAYOUT_DIRECTION_RTL;
            if ((r & START) == START) {
                r &= ~START;
                r |= rtl ? RIGHT : LEFT;
            } else if ((r & END) == END) {
                r &= ~END;
                r |= rtl ? LEFT : RIGHT;
            }
            r &= ~RELATIVE_LAYOUT_DIRECTION;
        }
        return r;
    }

    public static boolean isHorizontal(int g) { return g > 0 && (g & RELATIVE_HORIZONTAL_GRAVITY_MASK) != 0; }
    public static boolean isVertical(int g) { return g > 0 && (g & VERTICAL_GRAVITY_MASK) != 0; }

    public static void apply(int g, int w, int h, android.graphics.Rect container, android.graphics.Rect out, int dir) {
        apply(getAbsoluteGravity(g, dir), w, h, container, out);
    }

    public static void apply(int g, int w, int h, android.graphics.Rect c, android.graphics.Rect o) {
        int hg = g & HORIZONTAL_GRAVITY_MASK, vg = g & VERTICAL_GRAVITY_MASK;
        if (hg == RIGHT) { o.right = c.right; o.left = o.right - w; }
        else if (hg == CENTER_HORIZONTAL) { o.left = c.left + (c.width() - w) / 2; o.right = o.left + w; }
        else if (hg == FILL_HORIZONTAL) { o.left = c.left; o.right = c.right; }
        else { o.left = c.left; o.right = o.left + w; }
        if (vg == BOTTOM) { o.bottom = c.bottom; o.top = o.bottom - h; }
        else if (vg == CENTER_VERTICAL) { o.top = c.top + (c.height() - h) / 2; o.bottom = o.top + h; }
        else if (vg == FILL_VERTICAL) { o.top = c.top; o.bottom = c.bottom; }
        else { o.top = c.top; o.bottom = o.top + h; }
    }
}
