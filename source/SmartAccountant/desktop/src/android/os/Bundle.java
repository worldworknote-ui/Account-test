package android.os;

import java.util.*;

public class Bundle implements Parcelable {
    protected final Map<String, Object> m = new LinkedHashMap<>();
    public Bundle() {}
    public Bundle(Bundle o) { if (o != null) m.putAll(o.m); }
    public boolean containsKey(String k) { return m.containsKey(k); }
    public Set<String> keySet() { return m.keySet(); }
    public Object get(String k) { return m.get(k); }
    public void remove(String k) { m.remove(k); }
    public boolean isEmpty() { return m.isEmpty(); }
    public int size() { return m.size(); }
    public void clear() { m.clear(); }
    public void putAll(Bundle b) { m.putAll(b.m); }
    public void putString(String k, String v) { m.put(k, v); }
    public String getString(String k) { Object o = m.get(k); return o == null ? null : o.toString(); }
    public String getString(String k, String d) { String s = getString(k); return s == null ? d : s; }
    public void putInt(String k, int v) { m.put(k, v); }
    public int getInt(String k) { return getInt(k, 0); }
    public int getInt(String k, int d) { Object o = m.get(k); return o instanceof Number ? ((Number) o).intValue() : d; }
    public void putLong(String k, long v) { m.put(k, v); }
    public long getLong(String k) { return getLong(k, 0); }
    public long getLong(String k, long d) { Object o = m.get(k); return o instanceof Number ? ((Number) o).longValue() : d; }
    public void putBoolean(String k, boolean v) { m.put(k, v); }
    public boolean getBoolean(String k) { return getBoolean(k, false); }
    public boolean getBoolean(String k, boolean d) { Object o = m.get(k); return o instanceof Boolean ? (Boolean) o : d; }
    public void putDouble(String k, double v) { m.put(k, v); }
    public double getDouble(String k) { Object o = m.get(k); return o instanceof Number ? ((Number) o).doubleValue() : 0; }
    public void putFloat(String k, float v) { m.put(k, v); }
    public float getFloat(String k) { Object o = m.get(k); return o instanceof Number ? ((Number) o).floatValue() : 0; }
    public void putStringArray(String k, String[] v) { m.put(k, v); }
    public String[] getStringArray(String k) { return (String[]) m.get(k); }
    public void putLongArray(String k, long[] v) { m.put(k, v); }
    public long[] getLongArray(String k) { return (long[]) m.get(k); }
    public void putIntArray(String k, int[] v) { m.put(k, v); }
    public int[] getIntArray(String k) { return (int[]) m.get(k); }
    public void putStringArrayList(String k, ArrayList<String> v) { m.put(k, v); }
    @SuppressWarnings("unchecked") public ArrayList<String> getStringArrayList(String k) { return (ArrayList<String>) m.get(k); }
    public void putSerializable(String k, java.io.Serializable v) { m.put(k, v); }
    public java.io.Serializable getSerializable(String k) { return (java.io.Serializable) m.get(k); }
    public void putParcelable(String k, Parcelable v) { m.put(k, v); }
    @SuppressWarnings("unchecked") public <T extends Parcelable> T getParcelable(String k) { return (T) m.get(k); }
    public void putParcelableArrayList(String k, ArrayList<? extends Parcelable> v) { m.put(k, v); }
    @SuppressWarnings("unchecked") public <T extends Parcelable> ArrayList<T> getParcelableArrayList(String k) { return (ArrayList<T>) m.get(k); }
    public void putParcelableArray(String k, Parcelable[] v) { m.put(k, v); }
    public Parcelable[] getParcelableArray(String k) { return (Parcelable[]) m.get(k); }
    public void putCharSequenceArrayList(String k, ArrayList<CharSequence> v) { m.put(k, v); }
    @SuppressWarnings("unchecked") public ArrayList<CharSequence> getCharSequenceArrayList(String k) { return (ArrayList<CharSequence>) m.get(k); }
    public void putBundle(String k, Bundle b) { m.put(k, b); }
    public Bundle getBundle(String k) { return (Bundle) m.get(k); }
    public void putCharSequence(String k, CharSequence v) { m.put(k, v); }
    public CharSequence getCharSequence(String k) { return (CharSequence) m.get(k); }
    public void putByteArray(String k, byte[] v) { m.put(k, v); }
    public byte[] getByteArray(String k) { return (byte[]) m.get(k); }
}
