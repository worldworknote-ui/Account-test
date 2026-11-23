package android.text.style;
public class StyleSpan extends MetricAffectingSpan { final int style; public StyleSpan(int s) { style = s; } public int getStyle() { return style; } }
