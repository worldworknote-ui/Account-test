package android.view.animation;
public class CycleInterpolator extends BaseInterpolator {
    private final float f;
    public CycleInterpolator() { this(1.0f); }
    public CycleInterpolator(float f) { this.f = f; }
    public CycleInterpolator(double f) { this((float) f); }
    public CycleInterpolator(android.content.Context c, android.util.AttributeSet a) { this(1.0f); }
    private static float b(float t) { return t * t * 8.0f; }
    @Override public float getInterpolation(float t) { return (float) Math.sin(2 * f * Math.PI * t); }
}
