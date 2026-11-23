package android.util;
public class TypedValue {
    public static final int COMPLEX_UNIT_PX = 0, COMPLEX_UNIT_DIP = 1, COMPLEX_UNIT_SP = 2, COMPLEX_UNIT_PT = 3;
    public int data, resourceId, type;
    public static float applyDimension(int unit, float v, DisplayMetrics m) {
        switch (unit) {
            case COMPLEX_UNIT_DIP: return v * m.density;
            case COMPLEX_UNIT_SP: return v * m.scaledDensity;
            case COMPLEX_UNIT_PT: return v * m.xdpi / 72f;
            default: return v;
        }
    }
}
