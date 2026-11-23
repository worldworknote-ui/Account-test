package android.text;

public class StaticLayout extends Layout {
    public StaticLayout(CharSequence s, TextPaint p, int w, Alignment a, float mult, float add, boolean pad) {
        super(s, p, w, a, mult, add, false, Integer.MAX_VALUE, null);
    }
    private StaticLayout(Builder b) {
        super(b.text.subSequence(b.start, b.end), b.paint, b.width, b.align, b.mult, b.add, b.dir == TextDirectionHeuristics.RTL || b.dir == TextDirectionHeuristics.FIRSTSTRONG_RTL, b.maxLines, b.ell);
    }
    public static final class Builder {
        final CharSequence text; final int start, end; final TextPaint paint; final int width;
        Alignment align = Alignment.ALIGN_NORMAL; float mult = 1f, add = 0f; int maxLines = Integer.MAX_VALUE; TextUtils.TruncateAt ell;
        TextDirectionHeuristic dir = TextDirectionHeuristics.FIRSTSTRONG_LTR;
        private Builder(CharSequence t, int s, int e, TextPaint p, int w) { text = t; start = s; end = e; paint = p; width = w; }
        public static Builder obtain(CharSequence t, int s, int e, TextPaint p, int w) { return new Builder(t, s, e, p, w); }
        public Builder setAlignment(Alignment a) { align = a; return this; }
        public Builder setLineSpacing(float add, float mult) { this.add = add; this.mult = mult; return this; }
        public Builder setIncludePad(boolean b) { return this; }
        public Builder setMaxLines(int m) { maxLines = m; return this; }
        public Builder setEllipsize(TextUtils.TruncateAt e) { ell = e; return this; }
        public Builder setEllipsizedWidth(int w) { return this; }
        public Builder setTextDirection(TextDirectionHeuristic d) { dir = d; return this; }
        public Builder setBreakStrategy(int s) { return this; }
        public Builder setHyphenationFrequency(int f) { return this; }
        public Builder setJustificationMode(int m) { return this; }
        public Builder setUseLineSpacingFromFallbacks(boolean b) { return this; }
        public StaticLayout build() { return new StaticLayout(this); }
    }
}
