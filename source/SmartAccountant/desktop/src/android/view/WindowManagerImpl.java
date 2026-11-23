package android.view;
public class WindowManagerImpl implements WindowManager {
    @Override public Display getDefaultDisplay() { return new Display(); }
    @Override public void addView(View v, ViewGroup.LayoutParams p) {}
    @Override public void updateViewLayout(View v, ViewGroup.LayoutParams p) {}
    @Override public void removeView(View v) {}
}
