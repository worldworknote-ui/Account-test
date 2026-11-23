package android.widget;
public interface Adapter {
    int IGNORE_ITEM_VIEW_TYPE = -1, NO_SELECTION = Integer.MIN_VALUE;
    void registerDataSetObserver(android.database.DataSetObserver o);
    void unregisterDataSetObserver(android.database.DataSetObserver o);
    int getCount();
    Object getItem(int pos);
    long getItemId(int pos);
    boolean hasStableIds();
    android.view.View getView(int pos, android.view.View convert, android.view.ViewGroup parent);
    default int getItemViewType(int pos) { return 0; }
    default int getViewTypeCount() { return 1; }
    boolean isEmpty();
}
