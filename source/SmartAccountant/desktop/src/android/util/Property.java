package android.util;
public abstract class Property<T, V> {
    private final String name; private final Class<V> type;
    public Property(Class<V> type, String name) { this.type = type; this.name = name; }
    public abstract V get(T o);
    public void set(T o, V v) {}
    public String getName() { return name; }
    public Class<V> getType() { return type; }
}
