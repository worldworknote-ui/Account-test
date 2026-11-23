package android.text;
public class TextPaint extends android.graphics.Paint {
    public int linkColor, bgColor;
    public float density = 1f;
    public int[] drawableState;
    public TextPaint() { super(); }
    public TextPaint(int flags) { super(flags); }
    public TextPaint(android.graphics.Paint p) { super(p); }
}
