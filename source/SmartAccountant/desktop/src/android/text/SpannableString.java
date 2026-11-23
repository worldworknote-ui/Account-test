package android.text;

public class SpannableString implements Spannable {
    private final String text;
    final SpanStore spans;
    public SpannableString(CharSequence s) {
        text = s.toString();
        spans = s instanceof SpannableString ? ((SpannableString) s).spans.copy(0, text.length())
                : s instanceof SpannableStringBuilder ? ((SpannableStringBuilder) s).spans.copy(0, text.length()) : new SpanStore();
    }
    public static SpannableString valueOf(CharSequence s) { return s instanceof SpannableString ? (SpannableString) s : new SpannableString(s); }
    @Override public void setSpan(Object w, int s, int e, int f) { spans.set(w, s, e, f); }
    @Override public void removeSpan(Object w) { spans.remove(w); }
    @Override public <T> T[] getSpans(int s, int e, Class<T> t) { return spans.get(s, e, t); }
    @Override public int getSpanStart(Object w) { SpanStore.S x = spans.find(w); return x == null ? -1 : x.start; }
    @Override public int getSpanEnd(Object w) { SpanStore.S x = spans.find(w); return x == null ? -1 : x.end; }
    @Override public int getSpanFlags(Object w) { SpanStore.S x = spans.find(w); return x == null ? 0 : x.flags; }
    @Override public int nextSpanTransition(int s, int l, Class t) { return spans.next(s, l, t); }
    @Override public int length() { return text.length(); }
    @Override public char charAt(int i) { return text.charAt(i); }
    @Override public CharSequence subSequence(int s, int e) { SpannableString r = new SpannableString(text.substring(s, e)); r.spans.list.addAll(spans.copy(s, e).list); return r; }
    @Override public String toString() { return text; }
    @Override public boolean equals(Object o) { return o instanceof CharSequence && text.equals(o.toString()); }
    @Override public int hashCode() { return text.hashCode(); }
}
