package android.graphics;
public class DashPathEffect extends PathEffect {
    final float[] intervals; final float phase;
    public DashPathEffect(float[] intervals, float phase) {
        float[] v = intervals.clone();
        for (int i = 0; i < v.length; i++) if (v[i] <= 0) v[i] = 0.01f;
        this.intervals = v; this.phase = phase;
    }
}
