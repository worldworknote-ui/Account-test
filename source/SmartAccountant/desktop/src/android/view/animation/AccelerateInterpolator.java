package android.view.animation;
public class AccelerateInterpolator extends BaseInterpolator {
    private final float f;
    public AccelerateInterpolator() { this(1.0f); }
    public AccelerateInterpolator(float f) { this.f = f; }
    public AccelerateInterpolator(double f) { this((float) f); }
    public AccelerateInterpolator(android.content.Context c, android.util.AttributeSet a) { this(1.0f); }
    private static float b(float t) { return t * t * 8.0f; }
    @Override public float getInterpolation(float t) { return f == 1f ? t * t : (float) Math.pow(t, 2 * f); }
}
