package android.view.animation;
public class LinearInterpolator extends BaseInterpolator {
    private final float f;
    public LinearInterpolator() { this(1.0f); }
    public LinearInterpolator(float f) { this.f = f; }
    public LinearInterpolator(double f) { this((float) f); }
    public LinearInterpolator(android.content.Context c, android.util.AttributeSet a) { this(1.0f); }
    private static float b(float t) { return t * t * 8.0f; }
    @Override public float getInterpolation(float t) { return t; }
}
