package android.view.animation;
public class RotateAnimation extends Animation {
    final float from, to;
    public RotateAnimation(float from, float to) { this.from = from; this.to = to; }
    public RotateAnimation(float from, float to, int a, float b, int c, float d) { this(from, to); }
    public RotateAnimation(float from, float to, float px, float py) { this(from, to); }
    @Override public void start(android.view.View v) {
        v.setRotation(from);
        android.view.ViewPropertyAnimator a = v.animate().rotation(to).setDuration(duration).setInterpolator(interp);
        if (repeat == INFINITE) a.withEndAction(() -> start(v));
        a.start();
    }
}
