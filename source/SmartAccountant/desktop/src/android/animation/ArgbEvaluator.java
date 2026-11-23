package android.animation;
public class ArgbEvaluator implements TypeEvaluator<Object> {
    private static final ArgbEvaluator I = new ArgbEvaluator();
    public static ArgbEvaluator getInstance() { return I; }
    @Override public Object evaluate(float f, Object s, Object e) {
        int a = (Integer) s, b = (Integer) e;
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * f) << 24 | Math.round(ar + (br - ar) * f) << 16 | Math.round(ag + (bg - ag) * f) << 8 | Math.round(ab + (bb - ab) * f);
    }
}
