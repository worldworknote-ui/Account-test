package android.widget;

import android.database.DataSetObserver;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class BaseAdapter implements ListAdapter, SpinnerAdapter {
    private final CopyOnWriteArrayList<DataSetObserver> obs = new CopyOnWriteArrayList<>();
    @Override public void registerDataSetObserver(DataSetObserver o) { obs.add(o); }
    @Override public void unregisterDataSetObserver(DataSetObserver o) { obs.remove(o); }
    public void notifyDataSetChanged() { for (DataSetObserver o : obs) o.onChanged(); }
    public void notifyDataSetInvalidated() { for (DataSetObserver o : obs) o.onInvalidated(); }
    @Override public boolean hasStableIds() { return false; }
    @Override public boolean isEmpty() { return getCount() == 0; }
    @Override public long getItemId(int pos) { return pos; }
    @Override public android.view.View getDropDownView(int pos, android.view.View c, android.view.ViewGroup p) { return getView(pos, c, p); }
}
