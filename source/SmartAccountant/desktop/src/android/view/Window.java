package android.view;

import android.graphics.drawable.Drawable;

/** نافذة الشاشة أو الحوار: الجذر (decor) وألوان الأشرطة والأعلام. */
public class Window {
    public static final int FEATURE_NO_TITLE = 1, FEATURE_ACTION_BAR = 8;
    public interface Callback {}
    private final View decor;
    private final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
    private int status, nav;
    public Runnable onChange;
    public Window(View decor) { this.decor = decor; }
    public View getDecorView() { return decor; }
    public View peekDecorView() { return decor; }
    public void setStatusBarColor(int c) { status = c; if (onChange != null) onChange.run(); }
    public int getStatusBarColor() { return status; }
    public void setNavigationBarColor(int c) { nav = c; if (onChange != null) onChange.run(); }
    public int getNavigationBarColor() { return nav; }
    public void setNavigationBarDividerColor(int c) {}
    public void setNavigationBarContrastEnforced(boolean b) {}
    public void setStatusBarContrastEnforced(boolean b) {}
    public void setDecorFitsSystemWindows(boolean b) {}
    public void setSoftInputMode(int m) { attrs.softInputMode = m; }
    public void addFlags(int f) { attrs.flags |= f; }
    public void clearFlags(int f) { attrs.flags &= ~f; }
    public void setFlags(int f, int mask) { attrs.flags = (attrs.flags & ~mask) | (f & mask); }
    public WindowManager.LayoutParams getAttributes() { return attrs; }
    public void setAttributes(WindowManager.LayoutParams a) { attrs.flags = a.flags; attrs.dimAmount = a.dimAmount; attrs.gravity = a.gravity; attrs.width = a.width; attrs.height = a.height; if (onChange != null) onChange.run(); }
    public void setLayout(int w, int h) { attrs.width = w; attrs.height = h; if (onChange != null) onChange.run(); }
    public void setGravity(int g) { attrs.gravity = g; if (onChange != null) onChange.run(); }
    public void setDimAmount(float a) { attrs.dimAmount = a; if (onChange != null) onChange.run(); }
    public void setBackgroundDrawable(Drawable d) {}
    public void setBackgroundDrawableResource(int r) {}
    public void setWindowAnimations(int r) {}
    public void setElevation(float e) {}
    public boolean requestFeature(int f) { return true; }
    public View getCurrentFocus() { ViewRootImpl r = decor.root(); return r == null ? null : r.getFocused(); }
    public WindowInsetsController getInsetsController() { return new WindowInsetsController(); }
    public void setHideOverlayWindows(boolean h) {}
    public <T extends View> T findViewById(int id) { return decor.findViewById(id); }
    public static class WindowInsetsController {
        public static final int APPEARANCE_LIGHT_STATUS_BARS = 8, APPEARANCE_LIGHT_NAVIGATION_BARS = 16, BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;
        public void setSystemBarsAppearance(int a, int m) {}
        public void hide(int t) {}
        public void show(int t) {}
        public void setSystemBarsBehavior(int b) {}
    }
}
