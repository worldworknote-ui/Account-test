package android.widget;

import android.content.Context;
import android.view.*;

public abstract class AdapterView<T extends Adapter> extends ViewGroup {
    public static final int INVALID_POSITION = -1, ITEM_VIEW_TYPE_IGNORE = -1;
    public static final long INVALID_ROW_ID = Long.MIN_VALUE;
    public interface OnItemClickListener { void onItemClick(AdapterView<?> p, View v, int pos, long id); }
    public interface OnItemLongClickListener { boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id); }
    public interface OnItemSelectedListener { void onItemSelected(AdapterView<?> p, View v, int pos, long id); void onNothingSelected(AdapterView<?> p); }

    protected OnItemClickListener itemClick;
    protected OnItemLongClickListener itemLong;
    protected OnItemSelectedListener itemSelected;

    public AdapterView(Context c) { super(c); }
    public abstract T getAdapter();
    public abstract void setAdapter(T a);
    public abstract View getSelectedView();
    public abstract void setSelection(int pos);
    public void setOnItemClickListener(OnItemClickListener l) { itemClick = l; }
    public final OnItemClickListener getOnItemClickListener() { return itemClick; }
    public void setOnItemLongClickListener(OnItemLongClickListener l) { itemLong = l; }
    public void setOnItemSelectedListener(OnItemSelectedListener l) { itemSelected = l; }
    public final OnItemSelectedListener getOnItemSelectedListener() { return itemSelected; }
    public int getSelectedItemPosition() { return INVALID_POSITION; }
    public Object getSelectedItem() { int p = getSelectedItemPosition(); T a = getAdapter(); return a == null || p < 0 || p >= a.getCount() ? null : a.getItem(p); }
    public long getSelectedItemId() { return getSelectedItemPosition(); }
    public int getCount() { T a = getAdapter(); return a == null ? 0 : a.getCount(); }
    public Object getItemAtPosition(int p) { T a = getAdapter(); return a == null || p < 0 ? null : a.getItem(p); }
    public long getItemIdAtPosition(int p) { T a = getAdapter(); return a == null || p < 0 ? INVALID_ROW_ID : a.getItemId(p); }
    public boolean performItemClick(View v, int pos, long id) { if (itemClick != null) { itemClick.onItemClick(this, v, pos, id); return true; } return false; }
    public void setEmptyView(View v) {}
    public int getFirstVisiblePosition() { return 0; }
    public int getLastVisiblePosition() { return getCount() - 1; }
    public int getPositionForView(View v) { return INVALID_POSITION; }
}
