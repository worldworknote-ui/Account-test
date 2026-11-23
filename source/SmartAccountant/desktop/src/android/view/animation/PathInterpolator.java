package android.view.animation;

/** منحنى بيزييه مكعب (مثل cubic-bezier في CSS). */
public class PathInterpolator extends BaseInterpolator {
    private final float x1, y1, x2, y2;
    public PathInterpolator(float cx, float cy) { this(cx * 2f / 3f, cy * 2f / 3f, 1f / 3f + cx * 2f / 3f, 1f / 3f + cy * 2f / 3f); }
    public PathInterpolator(float x1, float y1, float x2, float y2) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; }
    public PathInterpolator(android.graphics.Path p) { this(0.4f, 0f, 0.2f, 1f); }
    private static float bez(float t, float a, float b) { float u = 1 - t; return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t; }
    @Override public float getInterpolation(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        float lo = 0, hi = 1, t = x;
        for (int i = 0; i < 30; i++) {
            t = (lo + hi) / 2;
            float v = bez(t, x1, x2);
            if (Math.abs(v - x) < 1e-5f) break;
            if (v < x) lo = t; else hi = t;
        }
        return bez(t, y1, y2);
    }
}
