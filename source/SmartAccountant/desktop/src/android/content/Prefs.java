package android.content;

import java.io.*;
import java.util.*;

/** تفضيلات بسيطة في ملف properties (بيانات الجهاز فقط). */
final class Prefs implements SharedPreferences {
    private static final Map<String, Prefs> all = new HashMap<>();
    private final File f;
    private final Properties p = new Properties();
    private Prefs(File f) {
        this.f = f;
        try (InputStream in = new FileInputStream(f)) { p.load(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)); } catch (IOException ignored) { }
    }
    static synchronized Prefs get(File f) { return all.computeIfAbsent(f.getAbsolutePath(), k -> new Prefs(f)); }
    private synchronized void save() {
        try (OutputStream o = new FileOutputStream(f)) { p.store(new OutputStreamWriter(o, java.nio.charset.StandardCharsets.UTF_8), null); } catch (IOException ignored) { }
    }
    @Override public synchronized String getString(String k, String d) { return p.getProperty(k, d); }
    @Override public synchronized int getInt(String k, int d) { try { return Integer.parseInt(p.getProperty(k)); } catch (Exception e) { return d; } }
    @Override public synchronized long getLong(String k, long d) { try { return Long.parseLong(p.getProperty(k)); } catch (Exception e) { return d; } }
    @Override public synchronized boolean getBoolean(String k, boolean d) { String v = p.getProperty(k); return v == null ? d : Boolean.parseBoolean(v); }
    @Override public synchronized float getFloat(String k, float d) { try { return Float.parseFloat(p.getProperty(k)); } catch (Exception e) { return d; } }
    @Override public synchronized Set<String> getStringSet(String k, Set<String> d) {
        String v = p.getProperty(k);
        if (v == null) return d;
        Set<String> s = new LinkedHashSet<>();
        for (String x : v.split("\u001F", -1)) if (!x.isEmpty()) s.add(x);
        return s;
    }
    @Override public synchronized boolean contains(String k) { return p.containsKey(k); }
    @Override public synchronized Map<String, ?> getAll() { Map<String, Object> m = new HashMap<>(); for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k)); return m; }
    @Override public Editor edit() {
        final Map<String, String> put = new LinkedHashMap<>();
        final Set<String> rm = new HashSet<>();
        final boolean[] clear = {false};
        return new Editor() {
            public Editor putString(String k, String v) { if (v == null) rm.add(k); else put.put(k, v); return this; }
            public Editor putInt(String k, int v) { put.put(k, String.valueOf(v)); return this; }
            public Editor putLong(String k, long v) { put.put(k, String.valueOf(v)); return this; }
            public Editor putBoolean(String k, boolean v) { put.put(k, String.valueOf(v)); return this; }
            public Editor putFloat(String k, float v) { put.put(k, String.valueOf(v)); return this; }
            public Editor putStringSet(String k, Set<String> v) { put.put(k, String.join("\u001F", v)); return this; }
            public Editor remove(String k) { rm.add(k); return this; }
            public Editor clear() { clear[0] = true; return this; }
            public boolean commit() {
                synchronized (Prefs.this) {
                    if (clear[0]) p.clear();
                    for (String k : rm) p.remove(k);
                    p.putAll(put);
                    save();
                }
                return true;
            }
            public void apply() { commit(); }
        };
    }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
}
