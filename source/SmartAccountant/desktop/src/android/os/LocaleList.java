package android.os;
public class LocaleList {
    final java.util.Locale l;
    public LocaleList(java.util.Locale... l) { this.l = l.length == 0 ? java.util.Locale.getDefault() : l[0]; }
    public java.util.Locale get(int i) { return l; }
    public int size() { return 1; }
    public static LocaleList getDefault() { return new LocaleList(java.util.Locale.getDefault()); }
}
