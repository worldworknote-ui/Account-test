package android.text.style;
public class AbsoluteSizeSpan extends MetricAffectingSpan {
    final int size; final boolean dip;
    public AbsoluteSizeSpan(int s) { this(s, false); }
    public AbsoluteSizeSpan(int s, boolean dip) { size = s; this.dip = dip; }
    public int getSize() { return size; } public boolean getDip() { return dip; }
}
