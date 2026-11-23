package android.view.animation;
public class DecelerateInterpolator extends BaseInterpolator {
    private final float f;
    public DecelerateInterpolator() { this(1.0f); }
    public DecelerateInterpolator(float f) { this.f = f; }
    public DecelerateInterpolator(double f) { this((float) f); }
    public DecelerateInterpolator(android.content.Context c, android.util.AttributeSet a) { this(1.0f); }
    private static float b(float t) { return t * t * 8.0f; }
    @Override public float getInterpolation(float t) { return f == 1f ? 1f - (1f - t) * (1f - t) : (float) (1.0 - Math.pow(1.0 - t, 2 * f)); }
}
