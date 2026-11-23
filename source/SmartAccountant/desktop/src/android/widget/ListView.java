package android.widget;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.drawable.*;
import android.view.*;

/** قائمة قابلة للتمرير تبني سطرًا لكل عنصر من المحوّل (القوائم في التطبيق صغيرة). */
public class ListView extends AdapterView<ListAdapter> {
    public static final int CHOICE_MODE_NONE = 0, CHOICE_MODE_SINGLE = 1, CHOICE_MODE_MULTIPLE = 2;
    private ListAdapter adapter;
    private final ScrollView scroll;
    private final LinearLayout box;
    private final DataSetObserver obs = new DataSetObserver() { @Override public void onChanged() { rebuild(); } @Override public void onInvalidated() { rebuild(); } };
    private Drawable divider;
    private int dividerH = 1;
    private int choice, checkedPos = -1;

    public ListView(Context c) {
        super(c);
        scroll = new ScrollView(c);
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box, new FrameLayout.LayoutParams(-1, -2));
        super.addView(scroll, -1, new ViewGroup.MarginLayoutParams(-1, -1));
        boolean night = c instanceof android.app.Activity && ((android.app.Activity) c).themeNight();
        divider = new ColorDrawable(night ? 0x1FFFFFFF : 0x1F000000);
    }
    public ListView(Context c, android.util.AttributeSet a) { this(c); }

    @Override public ListAdapter getAdapter() { return adapter; }
    @Override public void setAdapter(ListAdapter a) {
        if (adapter != null) adapter.unregisterDataSetObserver(obs);
        adapter = a;
        if (a != null) a.registerDataSetObserver(obs);
        rebuild();
    }
    public void setDivider(Drawable d) { divider = d; rebuild(); }
    public void setDividerHeight(int h) { dividerH = h; rebuild(); }
    public void setChoiceMode(int m) { choice = m; }
    public void setItemChecked(int pos, boolean v) { checkedPos = v ? pos : -1; rebuild(); }
    public int getCheckedItemPosition() { return checkedPos; }
    public void setSelector(Drawable d) {}
    public void setSelector(int r) {}
    public void setFastScrollEnabled(boolean e) {}
    public void setTextFilterEnabled(boolean e) {}
    public void addHeaderView(View v) { box.addView(v, 0); }
    public void addFooterView(View v) { box.addView(v); }
    public void setStackFromBottom(boolean s) {}
    public void setTranscriptMode(int m) {}
    public void smoothScrollToPosition(int p) { setSelection(p); }
    @Override public View getSelectedView() { return null; }
    @Override public void setSelection(int pos) {
        if (pos < 0 || pos >= box.getChildCount()) return;
        post(() -> { View r = box.getChildAt(pos); if (r != null) scroll.scrollTo(0, r.getTop()); });
    }

    private void rebuild() {
        box.removeAllViews();
        if (adapter == null) return;
        float d = getResources().getDisplayMetrics().density;
        int n = adapter.getCount();
        for (int i = 0; i < n; i++) {
            final int pos = i;
            View row = adapter.getView(i, null, this);
            if (row.getBackground() == null) {
                boolean night = getContext() instanceof android.app.Activity && ((android.app.Activity) getContext()).themeNight();
                row.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(night ? 0x33FFFFFF : 0x1F000000), null, new ColorDrawable(0xFFFFFFFF)));
            }
            row.setClickable(true);
            row.setOnClickListener(v -> { if (choice == CHOICE_MODE_SINGLE) checkedPos = pos; performItemClick(v, pos, adapter.getItemId(pos)); });
            if (itemLong != null) row.setOnLongClickListener(v -> itemLong.onItemLongClick(this, v, pos, adapter.getItemId(pos)));
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (i < n - 1 && divider != null && dividerH > 0) {
                View dv = new View(getContext());
                dv.setBackground(divider);
                box.addView(dv, new LinearLayout.LayoutParams(-1, dividerH));
            }
        }
    }
    @Override public void setOnItemLongClickListener(OnItemLongClickListener l) { super.setOnItemLongClickListener(l); rebuild(); }

    @Override protected void onMeasure(int ws, int hs) {
        scroll.measure(ws, MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) : hs);
        int h = MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hs) : scroll.getMeasuredHeight();
        if (MeasureSpec.getMode(hs) == MeasureSpec.AT_MOST) h = Math.min(h, MeasureSpec.getSize(hs));
        setMeasuredDimension(resolveSize(scroll.getMeasuredWidth(), ws), h);
        scroll.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { scroll.layout(0, 0, r - l, b - t); }
}
