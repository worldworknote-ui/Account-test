package android.database;

import java.util.ArrayList;
import java.util.List;

public class MatrixCursor extends Cursor {
    private final List<Object[]> rows;
    public MatrixCursor(String[] cols) { this(cols, new ArrayList<>()); }
    public MatrixCursor(String[] cols, int initial) { this(cols, new ArrayList<>()); }
    private MatrixCursor(String[] cols, List<Object[]> rows) { super(cols, rows); this.rows = rows; }
    public void addRow(Object[] r) { rows.add(r); }
    public void addRow(Iterable<?> r) { List<Object> l = new ArrayList<>(); for (Object o : r) l.add(o); rows.add(l.toArray()); }
    public RowBuilder newRow() { Object[] r = new Object[getColumnCount()]; rows.add(r); return new RowBuilder(r); }
    public class RowBuilder {
        private final Object[] r; private int i;
        RowBuilder(Object[] r) { this.r = r; }
        public RowBuilder add(Object o) { r[i++] = o; return this; }
        public RowBuilder add(String col, Object o) { int k = getColumnIndex(col); if (k >= 0) r[k] = o; return this; }
    }
}
