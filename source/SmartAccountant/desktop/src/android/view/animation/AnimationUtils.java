package android.view.animation;
public class AnimationUtils {
    public static long currentAnimationTimeMillis() { return android.os.SystemClock.uptimeMillis(); }
    public static Animation loadAnimation(android.content.Context c, int id) { return new AlphaAnimation(0f, 1f); }
    public static Interpolator loadInterpolator(android.content.Context c, int id) { return new AccelerateDecelerateInterpolator(); }
}
