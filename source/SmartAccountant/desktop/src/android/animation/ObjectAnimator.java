package android.animation;

import java.lang.reflect.Method;

/** يحرّك خاصية في كائن بالاسم (setScaleX...) أو بخاصية Property. قيمة واحدة = من القيمة الحالية إليها. */
public final class ObjectAnimator extends ValueAnimator {
    private Object target;

    public static ObjectAnimator ofFloat(Object t, String prop, float... v) {
        ObjectAnimator a = new ObjectAnimator(); a.target = t; a.values = new PropertyValuesHolder[]{PropertyValuesHolder.ofFloat(prop, v)}; return a;
    }
    public static <T> ObjectAnimator ofFloat(T t, android.util.Property<T, Float> p, float... v) {
        ObjectAnimator a = new ObjectAnimator(); a.target = t; a.values = new PropertyValuesHolder[]{PropertyValuesHolder.ofFloat(p, v)}; return a;
    }
    public static ObjectAnimator ofInt(Object t, String prop, int... v) {
        ObjectAnimator a = new ObjectAnimator(); a.target = t; a.values = new PropertyValuesHolder[]{PropertyValuesHolder.ofInt(prop, v)}; return a;
    }
    public static ObjectAnimator ofArgb(Object t, String prop, int... v) {
        ObjectAnimator a = ofInt(t, prop, v); a.setEvaluator(ArgbEvaluator.getInstance()); return a;
    }
    public static ObjectAnimator ofObject(Object t, String prop, TypeEvaluator ev, Object... v) {
        ObjectAnimator a = new ObjectAnimator(); a.target = t; a.values = new PropertyValuesHolder[]{PropertyValuesHolder.ofObject(prop, ev, v)}; return a;
    }
    public static ObjectAnimator ofPropertyValuesHolder(Object t, PropertyValuesHolder... h) {
        ObjectAnimator a = new ObjectAnimator(); a.target = t; a.values = h; return a;
    }
    @Override public void setTarget(Object t) { target = t; }
    public Object getTarget() { return target; }
    public void setPropertyName(String n) { if (values != null && values.length > 0) values[0].name = n; }
    public String getPropertyName() { return values == null || values.length == 0 ? null : values[0].name; }
    @Override public ObjectAnimator setDuration(long d) { super.setDuration(d); return this; }
    public void setAutoCancel(boolean c) {}

    @Override void prepare() {
        if (target == null || values == null) return;
        for (PropertyValuesHolder h : values) {
            if (h.singleF == null && h.floats != null && h.floats.length == 1) h.singleF = h.floats[0];
            if (h.singleI == null && h.ints != null && h.ints.length == 1) h.singleI = h.ints[0];
            float cur = h.property != null ? ((Number) h.property.get(target)).floatValue() : getFloat(target, h.name);
            if (h.singleF != null) h.floats = new float[]{cur, h.singleF};
            else if (h.singleI != null) h.ints = new int[]{(int) cur, h.singleI};
        }
    }
    @Override void animateValue(float f) {
        super.animateValue(f);
        if (target == null || values == null) return;
        for (PropertyValuesHolder h : values) {
            if (h.value == null) continue;
            if (h.property != null) { h.property.set(target, h.value); continue; }
            set(target, h.name, h.value);
        }
    }

    private static String cap(String n) { return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1); }

    public static float getFloat(Object t, String n) {
        try {
            Method m = t.getClass().getMethod("get" + cap(n));
            Object v = m.invoke(t);
            return v instanceof Number ? ((Number) v).floatValue() : 0f;
        } catch (Exception e) {
            return 0f;
        }
    }
    public static void setFloat(Object t, String n, float v) { set(t, n, v); }
    static void set(Object t, String n, Object v) {
        String m = "set" + cap(n);
        try {
            for (Method x : t.getClass().getMethods()) {
                if (!x.getName().equals(m) || x.getParameterCount() != 1) continue;
                Class<?> p = x.getParameterTypes()[0];
                if (p == float.class && v instanceof Number) { x.invoke(t, ((Number) v).floatValue()); return; }
                if (p == int.class && v instanceof Number) { x.invoke(t, ((Number) v).intValue()); return; }
                if (!p.isPrimitive() && p.isInstance(v)) { x.invoke(t, v); return; }
            }
        } catch (Exception ignored) { }
    }
    @Override public ObjectAnimator clone() { return (ObjectAnimator) super.clone(); }
}
