package android.widget;

import android.graphics.*;
import android.text.*;
import android.text.style.*;
import java.awt.Font;
import java.awt.font.*;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * تخطيط النص كما في StaticLayout في أندرويد: فقرات وأسطر ملفوفة بعرض محدد، اتجاه كل فقرة من أول حرف قوي، خط بديل
 * للرموز، الألوان والعريض والحجم من التنسيقات، وقطع بـ«…» عند تجاوز عدد الأسطر.
 */
public final class TextLines {
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    /** طريقة تحديد الاتجاه: 0 أول حرف قوي، 1 أي حرف عربي، 2 يسار دائمًا، 3 يمين دائمًا. */
    public static final int DIR_FIRST = 0, DIR_ANY_RTL = 1, DIR_LTR = 2, DIR_RTL = 3;

    static final class Line {
        TextLayout tl;
        int start, end;
        boolean rtl, ellipsized;
        float adv, vis, ascent, descent, top, height;
    }

    final List<Line> lines = new ArrayList<>();
    float height, maxWidth;
    float lineAscent, lineDescent;

    public int count() { return lines.size(); }
    public float height() { return height; }
    public float maxWidth() { return maxWidth; }
    public float lineWidth(int i) { return lines.get(i).vis; }
    public float lineTop(int i) { return lines.get(i).top; }
    public float lineHeight(int i) { return lines.get(i).height; }
    public float lineAscent(int i) { return lines.get(i).ascent; }
    public int lineStart(int i) { return lines.get(i).start; }
    public int lineEnd(int i) { return lines.get(i).end; }
    public boolean lineRtl(int i) { return lines.get(i).rtl; }
    public boolean ellipsized(int i) { return lines.get(i).ellipsized; }
    /** أول خط أساس (لمحاذاة الأسطر في الصف). */
    public float firstBaseline() { return lines.isEmpty() ? lineAscent : lines.get(0).top + lines.get(0).ascent; }

    public static TextLines build(CharSequence text, Paint p, int width, float mult, float add, boolean rtlDefault, int maxLines, TextUtils.TruncateAt ell) {
        Spec s = new Spec();
        s.paint = p; s.width = width; s.mult = mult; s.add = add; s.dirMode = rtlDefault ? DIR_FIRST : DIR_FIRST; s.rtlFallback = rtlDefault;
        s.maxLines = maxLines; s.ell = ell; s.includePad = true;
        return build(text, s);
    }

    public static final class Spec {
        public Paint paint;
        public int width = -1;
        public float mult = 1f, add = 0f;
        public int dirMode = DIR_FIRST;
        public boolean rtlFallback = true;
        public int maxLines = Integer.MAX_VALUE;
        public TextUtils.TruncateAt ell;
        public boolean singleLine, includePad = true, allCaps;
        public float density = 1f;
    }

    public static TextLines build(CharSequence text, Spec sp) {
        TextLines r = new TextLines();
        Paint p = sp.paint;
        Typeface tf = p.getTypeface() == null ? Typeface.DEFAULT : p.getTypeface();
        boolean bold = tf.isBold() || (p.getFlags() & Paint.FAKE_BOLD_TEXT_FLAG) != 0;
        Font base = Typeface.base(tf.familyName(), bold), baseBold = Typeface.base(tf.familyName(), true), baseReg = Typeface.base(tf.familyName(), false);
        float size = p.getTextSize();
        Font sized = base.deriveFont(size);
        LineMetrics lm = sized.getLineMetrics("أAg", FRC);
        float asc = lm.getAscent(), desc = lm.getDescent();
        java.awt.geom.Rectangle2D mb = sized.getMaxCharBounds(FRC);
        float top = Math.max(asc, (float) -mb.getY()), bottom = Math.max(desc, (float) mb.getMaxY());
        // حدود معقولة: بعض الخطوط ترجع حدودًا مبالغًا فيها
        top = Math.min(top, asc * 1.35f);
        bottom = Math.min(bottom, desc * 1.6f + size * 0.05f);
        r.lineAscent = asc;
        r.lineDescent = desc;
        String all = text == null ? "" : text.toString();
        if (sp.allCaps) all = all.toUpperCase();
        all = all.replace('\t', ' ');
        if (sp.singleLine) all = all.replace('\n', ' ');
        Spanned spans = text instanceof Spanned ? (Spanned) text : null;
        int maxLines = sp.maxLines <= 0 ? Integer.MAX_VALUE : sp.maxLines;
        float width = sp.width > 0 ? sp.width : Float.MAX_VALUE;
        boolean wrap = sp.width > 0 && !sp.singleLine;
        float y = 0;
        int pos = 0;
        boolean stop = false;
        String[] paras = all.split("\n", -1);
        for (int pi = 0; pi < paras.length && !stop; pi++) {
            String para = paras[pi];
            int pStart = pos;
            pos += para.length() + 1;
            boolean rtl = dir(para, sp);
            if (para.isEmpty()) {
                Line l = new Line();
                l.start = pStart; l.end = pStart; l.rtl = rtl; l.ascent = asc; l.descent = desc;
                r.lines.add(l);
                if (r.lines.size() >= maxLines && pi < paras.length - 1) { stop = true; }
                continue;
            }
            AttributedString as = attributed(para, pStart, spans, p, base, baseBold, baseReg, size, bold, rtl, sp.density);
            if (!wrap) {
                TextLayout t = new TextLayout(as.getIterator(), FRC);
                Line l = line(t, pStart, pStart + para.length(), rtl, asc, desc);
                if (l.vis > width + 1.5f && sp.ell != null && sp.ell != TextUtils.TruncateAt.MARQUEE) {
                    l = ellipsize(para, pStart, 0, spans, p, base, baseBold, baseReg, size, bold, rtl, width, asc, desc, sp.density);
                }
                r.lines.add(l);
                if (r.lines.size() >= maxLines && pi < paras.length - 1) { mark(r, para, pStart, spans, p, base, baseBold, baseReg, size, bold, rtl, width, asc, desc, sp, true); stop = true; }
                continue;
            }
            AttributedCharacterIterator it = as.getIterator();
            LineBreakMeasurer m = new LineBreakMeasurer(it, FRC);
            int end = it.getEndIndex();
            while (m.getPosition() < end) {
                int st = m.getPosition();
                TextLayout t = m.nextLayout(width + 1.5f);
                if (t == null) break;
                int en = m.getPosition();
                if (r.lines.size() + 1 >= maxLines && (en < end || pi < paras.length - 1)) {
                    // آخر سطر مسموح والنص لم ينته: «…» إن طُلب، وإلا يُقطع
                    Line l;
                    if (sp.ell != null && sp.ell != TextUtils.TruncateAt.MARQUEE) l = ellipsize(para, pStart, st, spans, p, base, baseBold, baseReg, size, bold, rtl, width, asc, desc, sp.density);
                    else l = line(t, pStart + st, pStart + en, rtl, asc, desc);
                    r.lines.add(l);
                    stop = true;
                    break;
                }
                r.lines.add(line(t, pStart + st, pStart + en, rtl, asc, desc));
            }
        }
        // الارتفاعات
        int n = r.lines.size();
        for (int i = 0; i < n; i++) {
            Line l = r.lines.get(i);
            float a = l.ascent, d = l.descent;
            if (sp.includePad && i == 0) a = Math.max(a, top * (l.ascent / asc));
            if (sp.includePad && i == n - 1) d = Math.max(d, bottom * (l.descent / desc));
            float h = a + d;
            if (i < n - 1) h = h * sp.mult + sp.add;
            l.top = y;
            l.ascent = a;
            l.height = h;
            y += h;
            r.maxWidth = Math.max(r.maxWidth, l.vis);
        }
        r.height = y;
        return r;
    }

    private static void mark(TextLines r, String para, int pStart, Spanned spans, Paint p, Font base, Font bb, Font br, float size, boolean bold, boolean rtl, float width, float asc, float desc, Spec sp, boolean more) {
        if (sp.ell == null || sp.ell == TextUtils.TruncateAt.MARQUEE) return;
        r.lines.set(r.lines.size() - 1, ellipsize(para + " ", pStart, 0, spans, p, base, bb, br, size, bold, rtl, Math.min(width, r.lines.get(r.lines.size() - 1).adv), asc, desc, sp.density));
    }

    private static boolean dir(String s, Spec sp) {
        switch (sp.dirMode) {
            case DIR_LTR: return false;
            case DIR_RTL: return true;
            case DIR_ANY_RTL:
                for (int i = 0; i < s.length(); i++) { byte d = Character.getDirectionality(s.charAt(i)); if (d == 1 || d == 2) return true; }
                return sp.rtlFallback && !hasStrongLtr(s) ? sp.rtlFallback : false;
            default:
                for (int i = 0; i < s.length(); i++) {
                    byte d = Character.getDirectionality(s.charAt(i));
                    if (d == 1 || d == 2) return true;
                    if (d == 0) return false;
                }
                return sp.rtlFallback;
        }
    }
    private static boolean hasStrongLtr(String s) { for (int i = 0; i < s.length(); i++) if (Character.getDirectionality(s.charAt(i)) == 0) return true; return false; }

    private static Line line(TextLayout t, int s, int e, boolean rtl, float asc, float desc) {
        Line l = new Line();
        l.tl = t; l.start = s; l.end = e; l.rtl = rtl;
        l.adv = t.getAdvance();
        l.vis = t.getVisibleAdvance();
        l.ascent = Math.max(asc, t.getAscent());
        l.descent = Math.max(desc, t.getDescent());
        return l;
    }

    private static Line ellipsize(String para, int pStart, int from, Spanned spans, Paint p, Font base, Font bb, Font br, float size, boolean bold, boolean rtl, float width, float asc, float desc, float density) {
        String rest = para.substring(from).replace('\n', ' ');
        int lo = 0, hi = rest.length();
        TextLayout best = null;
        // بحث ثنائي على أطول بداية تتسع مع «…»
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String s = rest.substring(0, mid).replaceAll("\\s+$", "") + "…";
            TextLayout t = new TextLayout(attributed(s, -1, null, p, base, bb, br, size, bold, rtl, density).getIterator(), FRC);
            if (t.getAdvance() <= width) { lo = mid; best = t; } else hi = mid - 1;
        }
        if (best == null) best = new TextLayout(attributed("…", -1, null, p, base, bb, br, size, bold, rtl, density).getIterator(), FRC);
        else {
            // إعادة البناء بالتنسيقات الأصلية
            String s = rest.substring(0, lo).replaceAll("\\s+$", "");
            AttributedString as = attributedWithTail(s, pStart + from, spans, p, base, bb, br, size, bold, rtl, density);
            best = new TextLayout(as.getIterator(), FRC);
        }
        Line l = line(best, pStart + from, pStart + para.length(), rtl, asc, desc);
        l.ellipsized = true;
        return l;
    }

    private static AttributedString attributedWithTail(String s, int off, Spanned spans, Paint p, Font base, Font bb, Font br, float size, boolean bold, boolean rtl, float density) {
        String full = s + "…";
        AttributedString as = attributed(full, -1, null, p, base, bb, br, size, bold, rtl, density);
        return as;
    }

    /** النص مع الخطوط (والبدائل) والاتجاه والتنسيقات. off = موضع الفقرة في النص الأصلي (-1 بلا تنسيقات). */
    static AttributedString attributed(String s0, int off, Spanned spans, Paint p, Font base, Font bb, Font br, float size, boolean bold, boolean rtl, float density) {
        // علامات الاتجاه الخفية (LRI/RLI/PDI...) تربك تقسيم الأسطر في Java: تُحذف ويُطبَّق أثرها بمستويات اتجاه صريحة
        int n0 = s0.length();
        int[] map = new int[n0 + 1];
        StringBuilder sb = new StringBuilder(n0);
        java.util.List<int[]> emb = new java.util.ArrayList<>();
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
        for (int i = 0; i < n0; i++) {
            map[i] = sb.length();
            char c = s0.charAt(i);
            if (c == '\u2066' || c == '\u202A' || c == '\u202D') { open.push(new int[]{sb.length(), 2}); continue; }
            if (c == '\u2067' || c == '\u202B' || c == '\u202E') { open.push(new int[]{sb.length(), 1}); continue; }
            if (c == '\u2068') { open.push(new int[]{sb.length(), 0}); continue; }
            if (c == '\u2069' || c == '\u202C') { if (!open.isEmpty()) { int[] o = open.pop(); if (o[1] > 0 && sb.length() > o[0]) emb.add(new int[]{o[0], sb.length(), o[1]}); } continue; }
            if (c == '\u200E' || c == '\u200F' || c == '\u061C') continue;
            sb.append(c);
        }
        map[n0] = sb.length();
        while (!open.isEmpty()) { int[] o = open.pop(); if (o[1] > 0 && sb.length() > o[0]) emb.add(new int[]{o[0], sb.length(), o[1]}); }
        String s = sb.length() == 0 ? " " : sb.toString();
        AttributedString as = new AttributedString(s);
        FontKit.apply(as, s, 0, s.length(), base, size, bold);
        as.addAttribute(TextAttribute.RUN_DIRECTION, rtl ? TextAttribute.RUN_DIRECTION_RTL : TextAttribute.RUN_DIRECTION_LTR);
        for (int[] e : emb) as.addAttribute(TextAttribute.BIDI_EMBEDDING, e[2] == 2 ? (rtl ? 2 : 2) : (rtl ? 3 : 1), e[0], e[1]);
        if ((p.getFlags() & Paint.STRIKE_THRU_TEXT_FLAG) != 0) as.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        if ((p.getFlags() & Paint.UNDERLINE_TEXT_FLAG) != 0) as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        if (p.getLetterSpacing() != 0) as.addAttribute(TextAttribute.TRACKING, p.getLetterSpacing());
        if (spans != null && off >= 0) applySpans(as, s, off, spans, p, base, bb, br, size, bold, density, map, n0);
        return as;
    }

    private static void applySpans(AttributedString as, String s, int off, Spanned spans, Paint p, Font base, Font bb, Font br, float size, boolean bold, float density, int[] map, int n0) {
        int len = n0;
        for (Object o : spans.getSpans(off, off + len, Object.class)) {
            int a = map[Math.max(0, spans.getSpanStart(o) - off)], b = map[Math.min(len, spans.getSpanEnd(o) - off)];
            if (a >= b) continue;
            if (o instanceof ForegroundColorSpan) as.addAttribute(TextAttribute.FOREGROUND, new java.awt.Color(((ForegroundColorSpan) o).getForegroundColor(), true), a, b);
            else if (o instanceof BackgroundColorSpan) as.addAttribute(TextAttribute.BACKGROUND, new java.awt.Color(((BackgroundColorSpan) o).getBackgroundColor(), true), a, b);
            else if (o instanceof UnderlineSpan || o instanceof URLSpan) as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, a, b);
            else if (o instanceof StrikethroughSpan) as.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, a, b);
        }
        for (Object o : spans.getSpans(off, off + len, MetricAffectingSpan.class)) {
            int a = map[Math.max(0, spans.getSpanStart(o) - off)], b = map[Math.min(len, spans.getSpanEnd(o) - off)];
            if (a >= b) continue;
            float sz = size;
            boolean bo = bold;
            if (o instanceof RelativeSizeSpan) sz = size * ((RelativeSizeSpan) o).getSizeChange();
            else if (o instanceof AbsoluteSizeSpan) sz = ((AbsoluteSizeSpan) o).getSize() * (((AbsoluteSizeSpan) o).getDip() ? density : 1f);
            else if (o instanceof StyleSpan) bo = (((StyleSpan) o).getStyle() & Typeface.BOLD) != 0 || bold;
            FontKit.apply(as, s, a, b, bo ? bb : br, sz, bo);
        }
    }

    /**
     * يرسم الأسطر داخل عرض w بدءًا من (x,y). mode: 0 طبيعي حسب اتجاه الفقرة، 1 وسط، 2 معاكس، 3 يسار، 4 يمين.
     */
    public void draw(Canvas c, float x, float y, float w, int mode, int color) {
        java.awt.Graphics2D g = c.graphics();
        int a = Math.round(Color.alpha(color) * c.alpha);
        if (a <= 0) return;
        g.setPaint(new java.awt.Color(Color.red(color), Color.green(color), Color.blue(color), a));
        java.awt.Composite old = null;
        if (c.alpha < 0.999f) {
            old = g.getComposite();
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, c.alpha))));
            g.setPaint(new java.awt.Color(Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color)));
        }
        for (Line l : lines) {
            if (l.tl == null) continue;
            float lx = x + lineLeft(l, w, mode);
            l.tl.draw(g, lx, y + l.top + l.ascent);
        }
        if (old != null) g.setComposite(old);
    }

    public float lineLeft(int i, float w, int mode) { return lineLeft(lines.get(i), w, mode); }

    private static float lineLeft(Line l, float w, int mode) {
        // المنطقة المرئية: للسطر العربي في الطرف الأيمن من عرضه الكلي
        float visOff = l.rtl ? l.adv - l.vis : 0;
        boolean right;
        switch (mode) {
            case 1: return (w - l.vis) / 2f - visOff;
            case 2: right = !l.rtl; break;
            case 3: right = false; break;
            case 4: right = true; break;
            default: right = l.rtl; break;
        }
        return right ? w - l.vis - visOff : -visOff;
    }
}
