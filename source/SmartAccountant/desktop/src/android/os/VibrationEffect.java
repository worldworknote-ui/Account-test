package android.os;
public class VibrationEffect {
    public static final int DEFAULT_AMPLITUDE = -1, EFFECT_CLICK = 0, EFFECT_TICK = 2, EFFECT_HEAVY_CLICK = 5;
    public static VibrationEffect createOneShot(long ms, int a) { return new VibrationEffect(); }
    public static VibrationEffect createPredefined(int e) { return new VibrationEffect(); }
}
