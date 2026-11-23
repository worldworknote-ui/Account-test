package android.view;
public class WindowInsets {
    public static final WindowInsets CONSUMED = new WindowInsets();
    public int getSystemWindowInsetTop() { return 0; }
    public int getSystemWindowInsetBottom() { return 0; }
    public int getSystemWindowInsetLeft() { return 0; }
    public int getSystemWindowInsetRight() { return 0; }
    public WindowInsets consumeSystemWindowInsets() { return this; }
    public android.graphics.Insets getInsets(int type) { return android.graphics.Insets.NONE; }
    public boolean isVisible(int type) { return false; }
    public static class Type { public static int ime() { return 8; } public static int systemBars() { return 7; } public static int navigationBars() { return 2; } public static int statusBars() { return 1; } }
}
