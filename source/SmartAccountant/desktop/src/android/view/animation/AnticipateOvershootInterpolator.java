package android.view.animation;
public class AnticipateOvershootInterpolator extends BaseInterpolator {
    private final float t;
    public AnticipateOvershootInterpolator() { this(2.0f); }
    public AnticipateOvershootInterpolator(float tension) { t = tension * 1.5f; }
    private static float a(float x, float s) { return x * x * ((s + 1) * x - s); }
    private static float o(float x, float s) { return x * x * ((s + 1) * x + s); }
    @Override public float getInterpolation(float x) { return x < 0.5f ? 0.5f * a(x * 2.0f, t) : 0.5f * (o(x * 2.0f - 2.0f, t) + 2.0f); }
}
