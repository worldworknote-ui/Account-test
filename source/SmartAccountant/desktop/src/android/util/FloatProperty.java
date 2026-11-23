package android.util;
public abstract class FloatProperty<T> extends Property<T, Float> {
    public FloatProperty(String name) { super(Float.class, name); }
    public abstract void setValue(T o, float v);
    @Override public void set(T o, Float v) { setValue(o, v); }
}
