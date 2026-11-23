package android.text;

public class SpannableStringBuilder implements Editable {
    final StringBuilder sb = new StringBuilder();
    final SpanStore spans = new SpanStore();
    private InputFilter[] filters = new InputFilter[0];
    /** للربط بخانة كتابة: يُستدعى بعد كل تعديل. */
    public Runnable onChange;

    public SpannableStringBuilder() {}
    public SpannableStringBuilder(CharSequence s) { this(s, 0, s == null ? 0 : s.length()); }
    public SpannableStringBuilder(CharSequence s, int a, int b) {
        if (s == null) return;
        sb.append(s, a, b);
        if (s instanceof SpannableStringBuilder) spans.list.addAll(((SpannableStringBuilder) s).spans.copy(a, b).list);
        else if (s instanceof SpannableString) spans.list.addAll(((SpannableString) s).spans.copy(a, b).list);
    }
    public static SpannableStringBuilder valueOf(CharSequence s) { return s instanceof SpannableStringBuilder ? (SpannableStringBuilder) s : new SpannableStringBuilder(s); }

    @Override public SpannableStringBuilder replace(int st, int en, CharSequence s, int start, int end) {
        CharSequence src = s.subSequence(start, end);
        for (InputFilter f : filters) {
            CharSequence r = f.filter(src, 0, src.length(), this, st, en);
            if (r != null) src = r;
        }
        sb.replace(st, en, src.toString());
        spans.replaced(st, en, src.length());
        if (src instanceof Spanned) {
            Spanned sp = (Spanned) src;
            for (Object o : sp.getSpans(0, src.length(), Object.class)) spans.set(o, st + sp.getSpanStart(o), st + sp.getSpanEnd(o), sp.getSpanFlags(o));
        }
        if (onChange != null) onChange.run();
        return this;
    }
    @Override public SpannableStringBuilder replace(int st, int en, CharSequence s) { return replace(st, en, s, 0, s.length()); }
    @Override public SpannableStringBuilder insert(int where, CharSequence s) { return replace(where, where, s); }
    public SpannableStringBuilder insert(int where, CharSequence s, int a, int b) { return replace(where, where, s, a, b); }
    @Override public SpannableStringBuilder delete(int st, int en) { return replace(st, en, ""); }
    @Override public SpannableStringBuilder append(CharSequence s) { return replace(sb.length(), sb.length(), s == null ? "null" : s); }
    @Override public SpannableStringBuilder append(CharSequence s, int a, int b) { return replace(sb.length(), sb.length(), s, a, b); }
    @Override public SpannableStringBuilder append(char c) { return append(String.valueOf(c)); }
    public SpannableStringBuilder append(CharSequence s, Object what, int flags) {
        int st = sb.length();
        append(s);
        setSpan(what, st, sb.length(), flags);
        return this;
    }
    @Override public void clear() { replace(0, sb.length(), ""); }
    @Override public void clearSpans() { spans.list.clear(); }
    @Override public void setFilters(InputFilter[] f) { filters = f == null ? new InputFilter[0] : f; }
    @Override public InputFilter[] getFilters() { return filters; }
    @Override public void setSpan(Object w, int s, int e, int f) { spans.set(w, s, e, f); if (onChange != null && !(w instanceof NoCopySpan)) { } }
    @Override public void removeSpan(Object w) { spans.remove(w); }
    @Override public <T> T[] getSpans(int s, int e, Class<T> t) { return spans.get(s, e, t); }
    @Override public int getSpanStart(Object w) { SpanStore.S x = spans.find(w); return x == null ? -1 : x.start; }
    @Override public int getSpanEnd(Object w) { SpanStore.S x = spans.find(w); return x == null ? -1 : x.end; }
    @Override public int getSpanFlags(Object w) { SpanStore.S x = spans.find(w); return x == null ? 0 : x.flags; }
    @Override public int nextSpanTransition(int s, int l, Class t) { return spans.next(s, l, t); }
    @Override public void getChars(int s, int e, char[] d, int off) { sb.getChars(s, e, d, off); }
    @Override public int length() { return sb.length(); }
    @Override public char charAt(int i) { return sb.charAt(i); }
    @Override public CharSequence subSequence(int s, int e) { return new SpannableStringBuilder(this, s, e); }
    @Override public String toString() { return sb.toString(); }
    /** نسخة بلا ربط (للحفظ). */
    public void setRaw(String s) { sb.setLength(0); sb.append(s); spans.list.clear(); }
}
