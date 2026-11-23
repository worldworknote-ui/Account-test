package android.content;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import java.util.ArrayList;

public class Intent implements Parcelable, Cloneable {
    public static final String ACTION_MAIN = "android.intent.action.MAIN", ACTION_VIEW = "android.intent.action.VIEW", ACTION_SEND = "android.intent.action.SEND",
            ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE", ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT",
            ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT", ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE",
            ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT", ACTION_DIAL = "android.intent.action.DIAL", ACTION_CHOOSER = "android.intent.action.CHOOSER",
            ACTION_SENDTO = "android.intent.action.SENDTO", ACTION_PICK = "android.intent.action.PICK", ACTION_EDIT = "android.intent.action.EDIT",
            ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED", ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS",
            ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";
    public static final String CATEGORY_OPENABLE = "android.intent.category.OPENABLE", CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER",
            CATEGORY_DEFAULT = "android.intent.category.DEFAULT", CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT", EXTRA_STREAM = "android.intent.extra.STREAM", EXTRA_SUBJECT = "android.intent.extra.SUBJECT",
            EXTRA_TITLE = "android.intent.extra.TITLE", EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE", EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES",
            EXTRA_INTENT = "android.intent.extra.INTENT", EXTRA_EMAIL = "android.intent.extra.EMAIL", EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT",
            EXTRA_LOCAL_ONLY = "android.intent.extra.LOCAL_ONLY", EXTRA_CHOOSER_TARGETS = "android.intent.extra.CHOOSER_TARGETS", EXTRA_INITIAL_INTENTS = "android.intent.extra.INITIAL_INTENTS";
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 1, FLAG_GRANT_WRITE_URI_PERMISSION = 2, FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x40,
            FLAG_GRANT_PREFIX_URI_PERMISSION = 0x80, FLAG_ACTIVITY_NEW_TASK = 0x10000000, FLAG_ACTIVITY_CLEAR_TOP = 0x4000000, FLAG_ACTIVITY_CLEAR_TASK = 0x8000,
            FLAG_ACTIVITY_SINGLE_TOP = 0x20000000, FLAG_ACTIVITY_NO_HISTORY = 0x40000000, FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS = 0x800000, FLAG_ACTIVITY_MULTIPLE_TASK = 0x8000000,
            FLAG_ACTIVITY_NEW_DOCUMENT = 0x80000, FLAG_RECEIVER_FOREGROUND = 0x10000000, FLAG_IMMUTABLE = 0x4000000, FLAG_UPDATE_CURRENT = 0x8000000;

    private String action, type;
    private Uri data;
    private final Bundle extras = new Bundle();
    private ComponentName component;
    private ClipData clip;
    private int flags;
    private final java.util.Set<String> categories = new java.util.HashSet<>();
    private String pkg;

    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent(String action, Uri data) { this.action = action; this.data = data; }
    public Intent(Context c, Class<?> cls) { component = new ComponentName(c, cls); }
    public Intent(Intent o) {
        action = o.action; type = o.type; data = o.data; extras.putAll(o.extras); component = o.component; clip = o.clip; flags = o.flags; categories.addAll(o.categories);
    }

    public static Intent makeRestartActivityTask(ComponentName c) { Intent i = new Intent(ACTION_MAIN); i.setComponent(c); return i; }
    public static Intent makeMainActivity(ComponentName c) { return makeRestartActivityTask(c); }
    public static Intent createChooser(Intent target, CharSequence title) {
        Intent i = new Intent(ACTION_CHOOSER);
        i.putExtra(EXTRA_INTENT, target);
        i.putExtra(EXTRA_TITLE, title);
        return i;
    }

    public String getAction() { return action; }
    public Intent setAction(String a) { action = a; return this; }
    public Uri getData() { return data; }
    public Intent setData(Uri u) { data = u; return this; }
    public String getDataString() { return data == null ? null : data.toString(); }
    public String getType() { return type; }
    public Intent setType(String t) { type = t; return this; }
    public Intent setDataAndType(Uri u, String t) { data = u; type = t; return this; }
    public Intent setPackage(String p) { pkg = p; return this; }
    public String getPackage() { return pkg; }
    public Intent setComponent(ComponentName c) { component = c; return this; }
    public ComponentName getComponent() { return component; }
    public Intent setClass(Context c, Class<?> k) { component = new ComponentName(c, k); return this; }
    public Intent setClassName(String p, String c) { component = new ComponentName(p, c); return this; }
    public Intent addCategory(String c) { categories.add(c); return this; }
    public boolean hasCategory(String c) { return categories.contains(c); }
    public java.util.Set<String> getCategories() { return categories; }
    public Intent addFlags(int f) { flags |= f; return this; }
    public Intent setFlags(int f) { flags = f; return this; }
    public int getFlags() { return flags; }
    public ClipData getClipData() { return clip; }
    public void setClipData(ClipData c) { clip = c; }
    public Bundle getExtras() { return extras.isEmpty() ? null : new Bundle(extras); }
    public boolean hasExtra(String k) { return extras.containsKey(k); }
    public Intent putExtras(Bundle b) { extras.putAll(b); return this; }
    public Intent putExtra(String k, String v) { extras.putString(k, v); return this; }
    public Intent putExtra(String k, CharSequence v) { extras.putCharSequence(k, v); return this; }
    public Intent putExtra(String k, int v) { extras.putInt(k, v); return this; }
    public Intent putExtra(String k, long v) { extras.putLong(k, v); return this; }
    public Intent putExtra(String k, boolean v) { extras.putBoolean(k, v); return this; }
    public Intent putExtra(String k, String[] v) { extras.putStringArray(k, v); return this; }
    public Intent putExtra(String k, Parcelable v) { extras.putParcelable(k, v); return this; }
    public Intent putExtra(String k, Parcelable[] v) { extras.putParcelableArray(k, v); return this; }
    public Intent putExtra(String k, java.io.Serializable v) { extras.putSerializable(k, v); return this; }
    public Intent putExtra(String k, Bundle v) { extras.putBundle(k, v); return this; }
    public Intent putExtra(String k, byte[] v) { extras.putByteArray(k, v); return this; }
    public Intent putParcelableArrayListExtra(String k, ArrayList<? extends Parcelable> v) { extras.putParcelableArrayList(k, v); return this; }
    public Intent putStringArrayListExtra(String k, ArrayList<String> v) { extras.putStringArrayList(k, v); return this; }
    public void removeExtra(String k) { extras.remove(k); }
    public String getStringExtra(String k) { return extras.getString(k); }
    public CharSequence getCharSequenceExtra(String k) { Object o = extras.get(k); return o instanceof CharSequence ? (CharSequence) o : null; }
    public int getIntExtra(String k, int d) { return extras.getInt(k, d); }
    public long getLongExtra(String k, long d) { return extras.getLong(k, d); }
    public boolean getBooleanExtra(String k, boolean d) { return extras.getBoolean(k, d); }
    public ArrayList<CharSequence> getCharSequenceArrayListExtra(String k) { return extras.getCharSequenceArrayList(k); }
    public Intent putCharSequenceArrayListExtra(String k, ArrayList<CharSequence> v) { extras.putCharSequenceArrayList(k, v); return this; }
    public String[] getStringArrayExtra(String k) { return extras.getStringArray(k); }
    public ArrayList<String> getStringArrayListExtra(String k) { return extras.getStringArrayList(k); }
    public byte[] getByteArrayExtra(String k) { return extras.getByteArray(k); }
    public Bundle getBundleExtra(String k) { return extras.getBundle(k); }
    public java.io.Serializable getSerializableExtra(String k) { return extras.getSerializable(k); }
    @SuppressWarnings("unchecked") public <T extends Parcelable> T getParcelableExtra(String k) { return (T) extras.get(k); }
    @SuppressWarnings("unchecked") public <T> T getParcelableExtra(String k, Class<T> c) { return (T) extras.get(k); }
    @SuppressWarnings("unchecked") public <T extends Parcelable> ArrayList<T> getParcelableArrayListExtra(String k) { return (ArrayList<T>) extras.get(k); }
    @SuppressWarnings("unchecked") public <T> ArrayList<T> getParcelableArrayListExtra(String k, Class<T> c) { return (ArrayList<T>) extras.get(k); }
    public Intent resolveActivity(android.content.pm.PackageManager pm) { return this; }
    @Override public Intent clone() { return new Intent(this); }
    @Override public String toString() { return "Intent{" + action + " " + data + "}"; }
}
