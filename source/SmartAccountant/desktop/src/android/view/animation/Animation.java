package android.view.animation;

/** حركات العرض القديمة: تُنفَّذ بخصائص العنصر (شفافية، إزاحة، تكبير). */
public abstract class Animation {
    public static final int INFINITE = -1, RESTART = 1, REVERSE = 2, ABSOLUTE = 0, RELATIVE_TO_SELF = 1, RELATIVE_TO_PARENT = 2;
    public interface AnimationListener { void onAnimationStart(Animation a); void onAnimationEnd(Animation a); void onAnimationRepeat(Animation a); }
    protected long duration = 300, offset;
    protected Interpolator interp = new AccelerateDecelerateInterpolator();
    protected AnimationListener listener;
    protected int repeat;
    public void setDuration(long d) { duration = d; }
    public long getDuration() { return duration; }
    public void setStartOffset(long o) { offset = o; }
    public void setInterpolator(Interpolator i) { interp = i; }
    public void setAnimationListener(AnimationListener l) { listener = l; }
    public void setFillAfter(boolean f) {}
    public void setFillBefore(boolean f) {}
    public void setRepeatCount(int c) { repeat = c; }
    public void setRepeatMode(int m) {}
    public void cancel() {}
    public void reset() {}
    public abstract void start(android.view.View v);
}
