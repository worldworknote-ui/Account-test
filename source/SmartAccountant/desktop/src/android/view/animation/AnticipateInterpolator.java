package android.view.animation;
public class AnticipateInterpolator extends BaseInterpolator {
    private final float f;
    public AnticipateInterpolator() { this(2.0f); }
    public AnticipateInterpolator(float f) { this.f = f; }
    public AnticipateInterpolator(double f) { this((float) f); }
    public AnticipateInterpolator(android.content.Context c, android.util.AttributeSet a) { this(2.0f); }
    private static float b(float t) { return t * t * 8.0f; }
    @Override public float getInterpolation(float t) { return t * t * ((f + 1) * t - f); }
}
