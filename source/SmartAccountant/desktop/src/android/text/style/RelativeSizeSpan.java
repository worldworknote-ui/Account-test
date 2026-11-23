package android.text.style;
public class RelativeSizeSpan extends MetricAffectingSpan { final float p; public RelativeSizeSpan(float p) { this.p = p; } public float getSizeChange() { return p; } }
