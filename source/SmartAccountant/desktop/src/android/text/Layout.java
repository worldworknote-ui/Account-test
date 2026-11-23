package android.text;

/** تخطيط نص متعدد الأسطر (يُستعمل في رسم البطاقات والتقارير). */
public class Layout {
    public enum Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }
    public static final int BREAK_STRATEGY_SIMPLE = 0, BREAK_STRATEGY_HIGH_QUALITY = 1, BREAK_STRATEGY_BALANCED = 2,
            HYPHENATION_FREQUENCY_NONE = 0, HYPHENATION_FREQUENCY_NORMAL = 1, JUSTIFICATION_MODE_NONE = 0, JUSTIFICATION_MODE_INTER_WORD = 1,
            DIR_LEFT_TO_RIGHT = 1, DIR_RIGHT_TO_LEFT = -1;

    protected final android.widget.TextLines lines;
    protected final CharSequence text;
    protected final TextPaint paint;
    protected final int width;
    protected final Alignment align;

    protected Layout(CharSequence text, TextPaint p, int width, Alignment a, float mult, float add, boolean rtl, int maxLines, TextUtils.TruncateAt ell) {
        this.text = text;
        this.paint = p;
        this.width = width;
        this.align = a;
        this.lines = android.widget.TextLines.build(text, p, width, mult, add, rtl, maxLines, ell);
    }
    public int getLineCount() { return lines.count(); }
    public int getHeight() { return Math.round(lines.height()); }
    public int getWidth() { return width; }
    public float getLineWidth(int i) { return lines.lineWidth(i); }
    public float getLineMax(int i) { return lines.lineWidth(i); }
    public int getLineTop(int i) { return Math.round(lines.lineTop(i)); }
    public int getLineBottom(int i) { return Math.round(lines.lineTop(i) + lines.lineHeight(i)); }
    public int getLineBaseline(int i) { return Math.round(lines.lineTop(i) + lines.lineAscent(i)); }
    public int getLineAscent(int i) { return -Math.round(lines.lineAscent(i)); }
    public int getLineDescent(int i) { return Math.round(lines.lineHeight(i) - lines.lineAscent(i)); }
    public int getLineStart(int i) { return lines.lineStart(i); }
    public int getLineEnd(int i) { return lines.lineEnd(i); }
    public float getLineLeft(int i) { return lines.lineLeft(i, width, align == Alignment.ALIGN_CENTER ? 1 : align == Alignment.ALIGN_OPPOSITE ? 2 : 0); }
    public float getLineRight(int i) { return getLineLeft(i) + getLineWidth(i); }
    public int getEllipsisCount(int i) { return lines.ellipsized(i) ? 1 : 0; }
    public int getParagraphDirection(int i) { return lines.lineRtl(i) ? DIR_RIGHT_TO_LEFT : DIR_LEFT_TO_RIGHT; }
    public int getLineForVertical(int y) { for (int i = 0; i < lines.count(); i++) if (y < lines.lineTop(i) + lines.lineHeight(i)) return i; return Math.max(0, lines.count() - 1); }
    public int getLineForOffset(int off) { for (int i = 0; i < lines.count(); i++) if (off < lines.lineEnd(i)) return i; return Math.max(0, lines.count() - 1); }
    public CharSequence getText() { return text; }
    public TextPaint getPaint() { return paint; }
    public Alignment getAlignment() { return align; }
    public void draw(android.graphics.Canvas c) {
        int mode = align == Alignment.ALIGN_CENTER ? 1 : align == Alignment.ALIGN_OPPOSITE ? 2 : 0;
        lines.draw(c, 0, 0, width, mode, paint.getColor());
    }
    public static float getDesiredWidth(CharSequence s, TextPaint p) {
        float m = 0;
        for (String line : s.toString().split("\n", -1)) m = Math.max(m, p.measureText(line));
        return m;
    }
}
