package android.graphics;
public class SweepGradient extends Shader {
    final int[] colors;
    public SweepGradient(float cx, float cy, int[] colors, float[] pos) { this.colors = colors; }
    public SweepGradient(float cx, float cy, int c0, int c1) { this.colors = new int[]{c0, c1}; }
    @Override public java.awt.Paint awt(int alpha) { return Shader.colors(colors, alpha)[0]; }
}
