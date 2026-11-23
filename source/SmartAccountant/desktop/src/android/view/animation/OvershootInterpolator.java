package android.view.animation;
public class OvershootInterpolator extends BaseInterpolator {
    private final float f;
    public OvershootInterpolator() { this(2.0f); }
    public OvershootInterpolator(float f) { this.f = f; }
    public OvershootInterpolator(double f) { this((float) f); }
    public OvershootInterpolator(android.content.Context c, android.util.AttributeSet a) { this(2.0f); }
    private static float b(float t) { return t * t * 8.0f; }
    @Override public float getInterpolation(float t) { t -= 1.0f; return t * t * ((f + 1) * t + f) + 1.0f; }
}
