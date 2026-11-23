package android.text;

import java.util.ArrayList;
import java.util.List;

/** تخزين التنسيقات (الألوان والعريض...) مع مواضعها. */
final class SpanStore {
    static final class S { Object what; int start, end, flags; S(Object w, int s, int e, int f) { what = w; start = s; end = e; flags = f; } }
    final List<S> list = new ArrayList<>();

    void set(Object w, int s, int e, int f) {
        for (S x : list) if (x.what == w) { x.start = s; x.end = e; x.flags = f; return; }
        list.add(new S(w, s, e, f));
    }
    void remove(Object w) { list.removeIf(x -> x.what == w); }
    S find(Object w) { for (S x : list) if (x.what == w) return x; return null; }

    @SuppressWarnings("unchecked")
    <T> T[] get(int s, int e, Class<T> type) {
        List<T> r = new ArrayList<>();
        for (S x : list) {
            if (type != null && !type.isInstance(x.what)) continue;
            if (x.start <= e && x.end >= s && !(x.end == s && x.start < s && s != e) || (s == e && x.start <= s && x.end >= s)) r.add((T) x.what);
        }
        T[] a = (T[]) java.lang.reflect.Array.newInstance(type == null ? Object.class : type, r.size());
        return r.toArray(a);
    }
    int next(int start, int limit, Class<?> type) {
        int n = limit;
        for (S x : list) {
            if (type != null && !type.isInstance(x.what)) continue;
            if (x.start > start && x.start < n) n = x.start;
            if (x.end > start && x.end < n) n = x.end;
        }
        return n;
    }
    /** تعديل المواضع بعد استبدال [s,e) بنص طوله len. */
    void replaced(int s, int e, int len) {
        int d = len - (e - s);
        for (java.util.Iterator<S> it = list.iterator(); it.hasNext(); ) {
            S x = it.next();
            if (x.start >= e) x.start += d; else if (x.start > s) x.start = s + Math.min(len, x.start - s);
            if (x.end >= e) x.end += d; else if (x.end > s) x.end = s + Math.min(len, x.end - s);
            if (x.end < x.start) it.remove();
        }
    }
    SpanStore copy(int s, int e) {
        SpanStore c = new SpanStore();
        for (S x : list) {
            int a = Math.max(s, x.start), b = Math.min(e, x.end);
            if (a < b || (a == b && x.start == x.end && a >= s && a <= e)) c.list.add(new S(x.what, a - s, b - s, x.flags));
        }
        return c;
    }
}
