package android.database;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * مؤشر نتائج كما في أندرويد، فوق نتائج مقروءة كاملة من JDBC. القيم تُقرأ بالتحويل نفسه الذي يفعله أندرويد:
 * getLong على نص رقمي يرجع الرقم، وgetString على رقم يرجع نصه، وnull يرجع 0 أو null.
 */
public class Cursor implements Closeable {
    public static final int FIELD_TYPE_NULL = 0, FIELD_TYPE_INTEGER = 1, FIELD_TYPE_FLOAT = 2, FIELD_TYPE_STRING = 3, FIELD_TYPE_BLOB = 4;

    private final String[] cols;
    private final List<Object[]> rows;
    private int pos = -1;
    private boolean closed;

    public Cursor(String[] cols, List<Object[]> rows) {
        this.cols = cols;
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    public android.os.Bundle getExtras() { return null; }
    public android.net.Uri getNotificationUri() { return null; }
    public void setNotificationUri(Object r, android.net.Uri u) {}
    public int getCount() { return rows.size(); }

    public int getPosition() { return pos; }

    public boolean moveToFirst() {
        pos = 0;
        return !rows.isEmpty();
    }

    public boolean moveToNext() {
        if (pos < rows.size()) pos++;
        return pos < rows.size();
    }

    public boolean moveToPosition(int p) {
        if (p < 0 || p >= rows.size()) {
            pos = p < 0 ? -1 : rows.size();
            return false;
        }
        pos = p;
        return true;
    }

    public boolean isAfterLast() { return pos >= rows.size(); }

    public int getColumnCount() { return cols.length; }

    public String getColumnName(int i) { return cols[i]; }

    public String[] getColumnNames() { return cols.clone(); }

    public int getColumnIndex(String name) {
        for (int i = 0; i < cols.length; i++) if (cols[i].equalsIgnoreCase(name)) return i;
        return -1;
    }

    public int getColumnIndexOrThrow(String name) {
        int i = getColumnIndex(name);
        if (i < 0) throw new IllegalArgumentException("column '" + name + "' does not exist");
        return i;
    }

    private Object v(int i) {
        if (pos < 0 || pos >= rows.size()) throw new IllegalStateException("Cursor index out of bounds: " + pos + " of " + rows.size());
        return rows.get(pos)[i];
    }

    public boolean isNull(int i) { return v(i) == null; }

    public int getType(int i) {
        Object o = v(i);
        if (o == null) return FIELD_TYPE_NULL;
        if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte || o instanceof Boolean) return FIELD_TYPE_INTEGER;
        if (o instanceof Double || o instanceof Float) return FIELD_TYPE_FLOAT;
        if (o instanceof byte[]) return FIELD_TYPE_BLOB;
        return FIELD_TYPE_STRING;
    }

    public String getString(int i) {
        Object o = v(i);
        if (o == null) return null;
        if (o instanceof byte[]) return new String((byte[]) o, java.nio.charset.StandardCharsets.UTF_8);
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return Long.toString((long) d) + ".0";
            return Double.toString(d);
        }
        return o.toString();
    }

    public long getLong(int i) {
        Object o = v(i);
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        return parseLong(o.toString());
    }

    /** كما يفعل SQLite: يقرأ أطول بادئة رقمية (نص غير رقمي = 0). */
    static long parseLong(String s) {
        s = s.trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(s);
            } catch (NumberFormatException e2) {
                int n = 0;
                if (n < s.length() && (s.charAt(n) == '-' || s.charAt(n) == '+')) n++;
                while (n < s.length() && Character.isDigit(s.charAt(n))) n++;
                try {
                    return n == 0 ? 0 : Long.parseLong(s.substring(0, n));
                } catch (NumberFormatException e3) {
                    return 0;
                }
            }
        }
    }

    public int getInt(int i) { return (int) getLong(i); }

    public short getShort(int i) { return (short) getLong(i); }

    public double getDouble(int i) {
        Object o = v(i);
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public float getFloat(int i) { return (float) getDouble(i); }

    public byte[] getBlob(int i) {
        Object o = v(i);
        if (o == null) return null;
        if (o instanceof byte[]) return (byte[]) o;
        return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean isClosed() { return closed; }

    @Override public void close() { closed = true; }
}
