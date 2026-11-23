package android.animation;

public class PropertyValuesHolder implements Cloneable {
    String name;
    android.util.Property property;
    float[] floats;
    int[] ints;
    Object[] objects;
    @SuppressWarnings("rawtypes") TypeEvaluator evaluator;
    Object value;
    Float singleF; Integer singleI;

    PropertyValuesHolder(String n) { name = n; }
    public static PropertyValuesHolder ofFloat(String n, float... v) { PropertyValuesHolder h = new PropertyValuesHolder(n); h.floats = v; return h; }
    public static PropertyValuesHolder ofFloat(android.util.Property<?, Float> p, float... v) { PropertyValuesHolder h = ofFloat(p.getName(), v); h.property = p; return h; }
    public static PropertyValuesHolder ofInt(String n, int... v) { PropertyValuesHolder h = new PropertyValuesHolder(n); h.ints = v; return h; }
    public static PropertyValuesHolder ofObject(String n, TypeEvaluator ev, Object... v) { PropertyValuesHolder h = new PropertyValuesHolder(n); h.objects = v; h.evaluator = ev; return h; }
    public void setFloatValues(float... v) { floats = v; ints = null; objects = null; }
    public void setIntValues(int... v) { ints = v; floats = null; objects = null; }
    public String getPropertyName() { return name; }
    public void setPropertyName(String n) { name = n; }

    @SuppressWarnings("unchecked")
    void calc(float f) {
        if (evaluator != null) {
            Object[] vs = objects != null ? objects : ints != null ? box(ints) : floats != null ? boxF(floats) : null;
            if (vs == null || vs.length == 0) return;
            if (vs.length == 1) { value = vs[0]; return; }
            float seg = f * (vs.length - 1);
            int i = Math.max(0, Math.min(vs.length - 2, (int) Math.floor(seg)));
            value = evaluator.evaluate(seg - i, vs[i], vs[i + 1]);
            return;
        }
        if (floats != null) value = interp(floats, f);
        else if (ints != null) {
            float[] fl = new float[ints.length];
            for (int i = 0; i < fl.length; i++) fl[i] = ints[i];
            value = Math.round(interp(fl, f));
        } else if (objects != null && objects.length > 0) value = objects[Math.min(objects.length - 1, Math.round(f * (objects.length - 1)))];
    }
    private static float interp(float[] v, float f) {
        if (v.length == 1) return v[0];
        float seg = f * (v.length - 1);
        int i = Math.max(0, Math.min(v.length - 2, (int) Math.floor(seg)));
        float t = seg - i;
        return v[i] + (v[i + 1] - v[i]) * t;
    }
    private static Object[] box(int[] a) { Object[] r = new Object[a.length]; for (int i = 0; i < a.length; i++) r[i] = a[i]; return r; }
    private static Object[] boxF(float[] a) { Object[] r = new Object[a.length]; for (int i = 0; i < a.length; i++) r[i] = a[i]; return r; }
    @Override public PropertyValuesHolder clone() { try { return (PropertyValuesHolder) super.clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(e); } }
}
