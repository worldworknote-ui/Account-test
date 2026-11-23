package android.text;
public interface Spanned extends CharSequence {
    int SPAN_EXCLUSIVE_EXCLUSIVE = 33, SPAN_INCLUSIVE_EXCLUSIVE = 17, SPAN_EXCLUSIVE_INCLUSIVE = 34, SPAN_INCLUSIVE_INCLUSIVE = 18, SPAN_MARK_MARK = 17, SPAN_POINT_POINT = 34;
    <T> T[] getSpans(int start, int end, Class<T> type);
    int getSpanStart(Object span);
    int getSpanEnd(Object span);
    int getSpanFlags(Object span);
    int nextSpanTransition(int start, int limit, Class type);
}
