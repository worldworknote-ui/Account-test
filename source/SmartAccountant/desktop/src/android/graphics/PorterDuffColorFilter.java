package android.graphics;
public class PorterDuffColorFilter extends ColorFilter {
    final int color; final PorterDuff.Mode mode;
    public PorterDuffColorFilter(int color, PorterDuff.Mode mode) { this.color = color; this.mode = mode; }
    @Override public int tint() { return color; }
}
