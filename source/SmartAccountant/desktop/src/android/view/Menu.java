package android.view;
public interface Menu {
    int NONE = 0, FIRST = 1;
    MenuItem add(CharSequence t);
    MenuItem add(int group, int id, int order, CharSequence t);
    void clear();
    int size();
    MenuItem getItem(int i);
}
