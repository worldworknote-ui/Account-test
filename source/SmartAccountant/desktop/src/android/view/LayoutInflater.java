package android.view;
public class LayoutInflater {
    private final android.content.Context c;
    private LayoutInflater(android.content.Context c) { this.c = c; }
    public static LayoutInflater from(android.content.Context c) { return new LayoutInflater(c); }
    public View inflate(int res, ViewGroup root) { return inflate(res, root, root != null); }
    public View inflate(int res, ViewGroup root, boolean attach) {
        android.widget.TextView t = new android.widget.TextView(c);
        if (attach && root != null) root.addView(t);
        return t;
    }
}
