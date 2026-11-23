package android.widget;

import android.content.Context;
import android.view.*;
import java.util.*;

/** محوّل القوائم البسيطة: سطر نصي لكل عنصر بنمط سطور أندرويد. */
public class ArrayAdapter<T> extends BaseAdapter implements Filterable {
    private final Context ctx;
    private final List<T> items;
    private int res, dropRes;
    private boolean notify = true;

    public ArrayAdapter(Context c, int res) { this(c, res, new ArrayList<T>()); }
    public ArrayAdapter(Context c, int res, int textId) { this(c, res, new ArrayList<T>()); }
    public ArrayAdapter(Context c, int res, T[] items) { this(c, res, new ArrayList<>(Arrays.asList(items))); }
    public ArrayAdapter(Context c, int res, int textId, T[] items) { this(c, res, items); }
    public ArrayAdapter(Context c, int res, List<T> items) { ctx = c; this.res = res; dropRes = res; this.items = items; }
    public ArrayAdapter(Context c, int res, int textId, List<T> items) { this(c, res, items); }
    public static ArrayAdapter<CharSequence> createFromResource(Context c, int arr, int res) { return new ArrayAdapter<>(c, res); }

    public Context getContext() { return ctx; }
    public void add(T t) { items.add(t); if (notify) notifyDataSetChanged(); }
    @SafeVarargs public final void addAll(T... t) { items.addAll(Arrays.asList(t)); if (notify) notifyDataSetChanged(); }
    public void addAll(Collection<? extends T> t) { items.addAll(t); if (notify) notifyDataSetChanged(); }
    public void insert(T t, int i) { items.add(i, t); if (notify) notifyDataSetChanged(); }
    public void remove(T t) { items.remove(t); if (notify) notifyDataSetChanged(); }
    public void clear() { items.clear(); if (notify) notifyDataSetChanged(); }
    public void sort(Comparator<? super T> c) { items.sort(c); if (notify) notifyDataSetChanged(); }
    public void setNotifyOnChange(boolean n) { notify = n; }
    @Override public void notifyDataSetChanged() { super.notifyDataSetChanged(); notify = true; }
    @Override public int getCount() { return items.size(); }
    @Override public T getItem(int pos) { return items.get(pos); }
    public int getPosition(T t) { return items.indexOf(t); }
    @Override public long getItemId(int pos) { return pos; }
    public void setDropDownViewResource(int r) { dropRes = r; }
    @Override public Filter getFilter() { return new Filter() {}; }

    @Override public View getView(int pos, View convert, ViewGroup parent) { return row(pos, convert, res, false); }
    @Override public View getDropDownView(int pos, View convert, ViewGroup parent) { return row(pos, convert, dropRes, true); }

    private View row(int pos, View convert, int layout, boolean drop) {
        TextView t = convert instanceof TextView ? (TextView) convert : new TextView(ctx);
        Object o = getItem(pos);
        t.setText(o instanceof CharSequence ? (CharSequence) o : String.valueOf(o));
        float d = ctx.getResources().getDisplayMetrics().density;
        boolean listRow = layout == android.R.layout.simple_list_item_1 || layout == android.R.layout.simple_spinner_dropdown_item || drop;
        if (layout == android.R.layout.simple_spinner_item && !drop) {
            t.setTextSize(16);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(0, 0, 0, 0);
        } else if (listRow) {
            t.setTextSize(16);
            t.setPadding(Math.round(16 * d), Math.round(4 * d), Math.round(16 * d), Math.round(4 * d));
            t.setMinHeight(Math.round(48 * d));
            if (layout == android.R.layout.simple_spinner_dropdown_item && !drop) { t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        }
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return t;
    }
}
