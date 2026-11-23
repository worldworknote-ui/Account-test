package android.graphics;

public class Shader {
    public enum TileMode { CLAMP, REPEAT, MIRROR, DECAL }
    Matrix local;
    public void setLocalMatrix(Matrix m) { local = m == null ? null : new Matrix(m); }
    public boolean getLocalMatrix(Matrix m) { if (local == null) return false; m.set(local); return true; }
    /** ألوان Java2D لهذا الظل (alpha إضافي من الفرشاة). */
    public java.awt.Paint awt(int alpha) { return new java.awt.Color(0, 0, 0, 0); }

    static java.awt.MultipleGradientPaint.CycleMethod cycle(TileMode m) {
        if (m == TileMode.REPEAT) return java.awt.MultipleGradientPaint.CycleMethod.REPEAT;
        if (m == TileMode.MIRROR) return java.awt.MultipleGradientPaint.CycleMethod.REFLECT;
        return java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE;
    }

    /** يصحح المواضع (يجب أن تكون متزايدة تمامًا في Java2D). */
    static float[] fractions(int n, float[] pos) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = pos == null ? (n == 1 ? 0 : i / (float) (n - 1)) : Math.max(0, Math.min(1, pos[i]));
        for (int i = 1; i < n; i++) if (f[i] <= f[i - 1]) f[i] = Math.min(1f, f[i - 1] + 1e-5f);
        for (int i = n - 2; i >= 0; i--) if (f[i] >= f[i + 1]) f[i] = f[i + 1] - 1e-5f;
        return f;
    }

    static java.awt.Color[] colors(int[] c, int alpha) {
        java.awt.Color[] r = new java.awt.Color[c.length];
        for (int i = 0; i < c.length; i++) {
            int a = Color.alpha(c[i]) * alpha / 255;
            r[i] = new java.awt.Color(Color.red(c[i]), Color.green(c[i]), Color.blue(c[i]), a);
        }
        return r;
    }
}
