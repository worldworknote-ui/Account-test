package android.view;
public interface MenuItem {
    interface OnMenuItemClickListener { boolean onMenuItemClick(MenuItem i); }
    int getItemId();
    CharSequence getTitle();
    MenuItem setOnMenuItemClickListener(OnMenuItemClickListener l);
    MenuItem setIcon(int res);
    MenuItem setEnabled(boolean e);
    MenuItem setVisible(boolean v);
    MenuItem setCheckable(boolean c);
    MenuItem setChecked(boolean c);
    boolean isChecked();
}
