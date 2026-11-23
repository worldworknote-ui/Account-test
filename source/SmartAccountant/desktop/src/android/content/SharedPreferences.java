package android.content;
public interface SharedPreferences {
    interface Editor {
        Editor putString(String k, String v); Editor putInt(String k, int v); Editor putLong(String k, long v); Editor putBoolean(String k, boolean v);
        Editor putFloat(String k, float v); Editor putStringSet(String k, java.util.Set<String> v); Editor remove(String k); Editor clear(); boolean commit(); void apply();
    }
    interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences p, String k); }
    String getString(String k, String d); int getInt(String k, int d); long getLong(String k, long d); boolean getBoolean(String k, boolean d); float getFloat(String k, float d);
    java.util.Set<String> getStringSet(String k, java.util.Set<String> d); boolean contains(String k); java.util.Map<String, ?> getAll(); Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l); void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
}
