package android.util;
public class SparseArray<E> {
    private final java.util.TreeMap<Integer, E> m = new java.util.TreeMap<>();
    public E get(int k) { return m.get(k); }
    public E get(int k, E d) { return m.getOrDefault(k, d); }
    public void put(int k, E v) { m.put(k, v); }
    public void remove(int k) { m.remove(k); }
    public void delete(int k) { m.remove(k); }
    public int size() { return m.size(); }
    public void clear() { m.clear(); }
    public int keyAt(int i) { return new java.util.ArrayList<>(m.keySet()).get(i); }
    public E valueAt(int i) { return new java.util.ArrayList<>(m.values()).get(i); }
}
