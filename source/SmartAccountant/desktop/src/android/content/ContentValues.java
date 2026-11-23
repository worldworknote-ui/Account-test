package android.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** مثل ContentValues في أندرويد: أعمدة وقيم بترتيب الإدخال. */
public final class ContentValues {
    private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

    public void put(String k, String v) { values.put(k, v); }

    public void put(String k, Long v) { values.put(k, v); }

    public void put(String k, Integer v) { values.put(k, v == null ? null : v.longValue()); }

    public void put(String k, Double v) { values.put(k, v); }

    public void put(String k, Float v) { values.put(k, v == null ? null : v.doubleValue()); }

    public void put(String k, Boolean v) { values.put(k, v == null ? null : (v ? 1L : 0L)); }

    public void put(String k, byte[] v) { values.put(k, v); }

    public void putNull(String k) { values.put(k, null); }

    public Object get(String k) { return values.get(k); }

    public String getAsString(String k) {
        Object v = values.get(k);
        return v == null ? null : v.toString();
    }

    public Long getAsLong(String k) {
        Object v = values.get(k);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean containsKey(String k) { return values.containsKey(k); }

    public void remove(String k) { values.remove(k); }

    public int size() { return values.size(); }

    public Set<String> keySet() { return values.keySet(); }

    public Set<Map.Entry<String, Object>> valueSet() { return values.entrySet(); }

    public void clear() { values.clear(); }
}
