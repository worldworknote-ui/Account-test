package android.view.animation;
public class AccelerateDecelerateInterpolator extends BaseInterpolator {
    private final float f;
    public AccelerateDecelerateInterpolator() { this(1.0f); }
    public AccelerateDecelerateInterpolator(float f) { this.f = f; }
    public AccelerateDecelerateInterpolator(double f) { this((float) f); }
    public AccelerateDecelerateInterpolator(android.content.Context c, android.util.AttributeSet a) { this(1.0f); }
    private static float b(float t) { return t * t * 8.0f; }
    @Override public float getInterpolation(float t) { return (float) (Math.cos((t + 1) * Math.PI) / 2.0f) + 0.5f; }
}
