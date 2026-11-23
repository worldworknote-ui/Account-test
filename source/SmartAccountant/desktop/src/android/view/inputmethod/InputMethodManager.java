package android.view.inputmethod;
public class InputMethodManager {
    public static final int SHOW_IMPLICIT = 1, SHOW_FORCED = 2, HIDE_NOT_ALWAYS = 2, HIDE_IMPLICIT_ONLY = 1;
    public boolean showSoftInput(android.view.View v, int flags) { if (v != null) v.requestFocus(); return true; }
    public boolean hideSoftInputFromWindow(android.os.IBinder t, int flags) { return true; }
    public void toggleSoftInput(int a, int b) {}
    public boolean isActive() { return true; }
    public boolean isActive(android.view.View v) { return v != null && v.isFocused(); }
    public void restartInput(android.view.View v) {}
}
