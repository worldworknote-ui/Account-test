package android.graphics;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;

public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1, FILTER_BITMAP_FLAG = 2, DITHER_FLAG = 4, UNDERLINE_TEXT_FLAG = 8, STRIKE_THRU_TEXT_FLAG = 16,
            FAKE_BOLD_TEXT_FLAG = 32, LINEAR_TEXT_FLAG = 64, SUBPIXEL_TEXT_FLAG = 128;

    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    public enum Align { LEFT, CENTER, RIGHT }

    public static class FontMetrics { public float top, ascent, descent, bottom, leading; }
    public static class FontMetricsInt { public int top, ascent, descent, bottom, leading; }

    int color = Color.BLACK;
    int flags;
    Style style = Style.FILL;
    Cap cap = Cap.BUTT;
    Join join = Join.MITER;
    float strokeWidth = 0, miter = 4;
    float textSize = 12;
    Typeface typeface;
    Align align = Align.LEFT;
    Shader shader;
    MaskFilter maskFilter;
    ColorFilter colorFilter;
    Xfermode xfermode;
    float shadowR, shadowDx, shadowDy;
    int shadowColor;
    float letterSpacing, textScaleX = 1f;
    PathEffect pathEffect;

    public Paint() {}
    public Paint(int flags) { this.flags = flags; }
    public Paint(Paint o) { set(o); }

    public void set(Paint o) {
        color = o.color; flags = o.flags; style = o.style; cap = o.cap; join = o.join; strokeWidth = o.strokeWidth; miter = o.miter;
        textSize = o.textSize; typeface = o.typeface; align = o.align; shader = o.shader; maskFilter = o.maskFilter; colorFilter = o.colorFilter;
        xfermode = o.xfermode; shadowR = o.shadowR; shadowDx = o.shadowDx; shadowDy = o.shadowDy; shadowColor = o.shadowColor;
        letterSpacing = o.letterSpacing; textScaleX = o.textScaleX; pathEffect = o.pathEffect;
    }
    public void reset() { set(new Paint()); }

    public int getFlags() { return flags; }
    public void setFlags(int f) { flags = f; }
    public void setAntiAlias(boolean a) { flags = a ? flags | ANTI_ALIAS_FLAG : flags & ~ANTI_ALIAS_FLAG; }
    public boolean isAntiAlias() { return (flags & ANTI_ALIAS_FLAG) != 0; }
    public void setDither(boolean d) {}
    public void setFilterBitmap(boolean f) {}
    public void setFakeBoldText(boolean b) { flags = b ? flags | FAKE_BOLD_TEXT_FLAG : flags & ~FAKE_BOLD_TEXT_FLAG; }
    public void setStrikeThruText(boolean b) { flags = b ? flags | STRIKE_THRU_TEXT_FLAG : flags & ~STRIKE_THRU_TEXT_FLAG; }
    public void setUnderlineText(boolean b) { flags = b ? flags | UNDERLINE_TEXT_FLAG : flags & ~UNDERLINE_TEXT_FLAG; }
    public int getColor() { return color; }
    public void setColor(int c) { color = c; }
    public void setARGB(int a, int r, int g, int b) { color = Color.argb(a, r, g, b); }
    public int getAlpha() { return Color.alpha(color); }
    public void setAlpha(int a) { color = (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF); }
    public Style getStyle() { return style; }
    public void setStyle(Style s) { style = s; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeWidth(float w) { strokeWidth = w; }
    public void setStrokeCap(Cap c) { cap = c; }
    public Cap getStrokeCap() { return cap; }
    public void setStrokeJoin(Join j) { join = j; }
    public Join getStrokeJoin() { return join; }
    public void setStrokeMiter(float m) { miter = m; }
    public float getTextSize() { return textSize; }
    public void setTextSize(float s) { textSize = s; }
    public Typeface getTypeface() { return typeface; }
    public Typeface setTypeface(Typeface t) { typeface = t; return t; }
    public Align getTextAlign() { return align; }
    public void setTextAlign(Align a) { align = a; }
    public Shader getShader() { return shader; }
    public Shader setShader(Shader s) { shader = s; return s; }
    public MaskFilter getMaskFilter() { return maskFilter; }
    public MaskFilter setMaskFilter(MaskFilter m) { maskFilter = m; return m; }
    public ColorFilter getColorFilter() { return colorFilter; }
    public ColorFilter setColorFilter(ColorFilter c) { colorFilter = c; return c; }
    public Xfermode setXfermode(Xfermode x) { xfermode = x; return x; }
    public PathEffect setPathEffect(PathEffect e) { pathEffect = e; return e; }
    public void setShadowLayer(float r, float dx, float dy, int c) { shadowR = r; shadowDx = dx; shadowDy = dy; shadowColor = c; }
    public void clearShadowLayer() { shadowR = 0; }
    public void setLetterSpacing(float s) { letterSpacing = s; }
    public float getLetterSpacing() { return letterSpacing; }
    public void setTextScaleX(float s) { textScaleX = s; }
    public void setElegantTextHeight(boolean b) {}
    public void setTextLocale(java.util.Locale l) {}

    // ------------------------------------------------------------------ النص
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    public Font font() {
        Typeface t = typeface == null ? Typeface.DEFAULT : typeface;
        boolean bold = t.isBold() || (flags & FAKE_BOLD_TEXT_FLAG) != 0;
        return Typeface.base(t.familyName(), bold).deriveFont(textSize);
    }

    /** سطر واحد جاهز للرسم بالخطوط البديلة والاتجاه الصحيح؛ null لنص فارغ. */
    public TextLayout layout(String s) {
        if (s == null || s.isEmpty()) return null;
        Typeface t = typeface == null ? Typeface.DEFAULT : typeface;
        boolean bold = t.isBold() || (flags & FAKE_BOLD_TEXT_FLAG) != 0;
        Font base = Typeface.base(t.familyName(), bold);
        AttributedString as = new AttributedString(s);
        FontKit.apply(as, s, 0, s.length(), base, textSize, bold);
        as.addAttribute(TextAttribute.RUN_DIRECTION, firstStrongRtl(s) ? TextAttribute.RUN_DIRECTION_RTL : TextAttribute.RUN_DIRECTION_LTR);
        if (letterSpacing != 0) as.addAttribute(TextAttribute.TRACKING, letterSpacing);
        if ((flags & STRIKE_THRU_TEXT_FLAG) != 0) as.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        if ((flags & UNDERLINE_TEXT_FLAG) != 0) as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        return new TextLayout(as.getIterator(), FRC);
    }

    public static boolean firstStrongRtl(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
        }
        return false;
    }

    public float measureText(String s) {
        TextLayout l = layout(s);
        return l == null ? 0 : l.getAdvance();
    }
    public float measureText(CharSequence s, int a, int b) { return measureText(s.toString().substring(a, b)); }
    public float measureText(char[] c, int i, int n) { return measureText(new String(c, i, n)); }
    public float measureText(String s, int a, int b) { return measureText(s.substring(a, b)); }

    public void getTextBounds(String s, int a, int b, Rect out) {
        TextLayout l = layout(s.substring(a, b));
        if (l == null) { out.set(0, 0, 0, 0); return; }
        java.awt.geom.Rectangle2D r = l.getBounds();
        out.set((int) Math.floor(r.getMinX()), (int) Math.floor(r.getMinY()), (int) Math.ceil(r.getMaxX()), (int) Math.ceil(r.getMaxY()));
    }
    public void getTextBounds(CharSequence s, int a, int b, Rect out) { getTextBounds(s.toString(), a, b, out); }
    public void getTextBounds(char[] c, int i, int n, Rect out) { getTextBounds(new String(c, i, n), 0, n, out); }

    public int breakText(String s, boolean forward, float maxW, float[] measured) {
        int n = 0;
        float w = 0;
        while (n < s.length()) {
            float nw = measureText(s.substring(0, n + 1));
            if (nw > maxW) break;
            w = nw;
            n++;
        }
        if (measured != null && measured.length > 0) measured[0] = w;
        return n;
    }

    public float ascent() { return -font().getLineMetrics("أAg", FRC).getAscent(); }
    public float descent() { return font().getLineMetrics("أAg", FRC).getDescent(); }
    public float getFontSpacing() { java.awt.font.LineMetrics m = font().getLineMetrics("أAg", FRC); return m.getAscent() + m.getDescent() + m.getLeading(); }

    public FontMetrics getFontMetrics() {
        FontMetrics f = new FontMetrics();
        getFontMetrics(f);
        return f;
    }
    public float getFontMetrics(FontMetrics f) {
        java.awt.font.LineMetrics m = font().getLineMetrics("أAg", FRC);
        f.ascent = -m.getAscent();
        f.descent = m.getDescent();
        f.top = f.ascent * 1.08f;
        f.bottom = f.descent * 1.08f;
        f.leading = m.getLeading();
        return f.descent - f.ascent + f.leading;
    }
    public FontMetricsInt getFontMetricsInt() {
        FontMetrics f = getFontMetrics();
        FontMetricsInt i = new FontMetricsInt();
        i.ascent = (int) Math.floor(f.ascent); i.descent = (int) Math.ceil(f.descent); i.top = (int) Math.floor(f.top); i.bottom = (int) Math.ceil(f.bottom); i.leading = (int) f.leading;
        return i;
    }

    // ------------------------------------------------------------------ للرسم
    /** لون/تدرج Java2D مع الشفافية الإضافية. */
    java.awt.Paint awtPaint(float extraAlpha) {
        int a = Math.round(Color.alpha(color) * extraAlpha);
        if (shader != null) return shader.awt(Math.round(255 * extraAlpha * (Color.alpha(color) / 255f)));
        int c = colorFilter != null && colorFilter.tint() != 0 ? colorFilter.tint() : color;
        if (colorFilter != null && colorFilter.tint() != 0) a = Math.round(Color.alpha(color) * extraAlpha * Color.alpha(c) / 255f);
        return new java.awt.Color(Color.red(c), Color.green(c), Color.blue(c), Math.max(0, Math.min(255, a)));
    }

    java.awt.Stroke awtStroke() {
        int c = cap == Cap.ROUND ? java.awt.BasicStroke.CAP_ROUND : cap == Cap.SQUARE ? java.awt.BasicStroke.CAP_SQUARE : java.awt.BasicStroke.CAP_BUTT;
        int j = join == Join.ROUND ? java.awt.BasicStroke.JOIN_ROUND : join == Join.BEVEL ? java.awt.BasicStroke.JOIN_BEVEL : java.awt.BasicStroke.JOIN_MITER;
        float w = strokeWidth <= 0 ? 1f : strokeWidth;
        if (pathEffect instanceof DashPathEffect) {
            DashPathEffect d = (DashPathEffect) pathEffect;
            return new java.awt.BasicStroke(w, c, j, Math.max(1f, miter), d.intervals, d.phase);
        }
        return new java.awt.BasicStroke(w, c, j, Math.max(1f, miter));
    }
}
