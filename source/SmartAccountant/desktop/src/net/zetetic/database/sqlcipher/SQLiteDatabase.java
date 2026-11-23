package net.zetetic.database.sqlcipher;

import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteException;

import android.content.ContentValues;
import android.database.Cursor;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLiteDatabase بسلوك أندرويد فوق اتصال JDBC واحد (sqlite-jdbc):
 * <ul>
 *   <li>المعاملات المتداخلة: الداخلية بلا setTransactionSuccessful تُفشل المعاملة كلها (كأندرويد).</li>
 *   <li>المعاملة مملوكة لخيطها: الخيوط الأخرى تنتظر حتى تنتهي (قفل قابل لإعادة الدخول)، فلا تختلط عملياتها بها.</li>
 *   <li>وسائط rawQuery نصوص (كأندرويد تمامًا)، والقيم في ContentValues بأنواعها.</li>
 * </ul>
 */
public final class SQLiteDatabase implements Closeable {
    public static final int CONFLICT_NONE = 0, CONFLICT_ROLLBACK = 1, CONFLICT_ABORT = 2, CONFLICT_FAIL = 3, CONFLICT_IGNORE = 4, CONFLICT_REPLACE = 5;
    private static final String[] CONFLICT = {"", " OR ROLLBACK", " OR ABORT", " OR FAIL", " OR IGNORE", " OR REPLACE"};

    private final Connection c;
    private final String path;
    private final ReentrantLock lock = new ReentrantLock(true);
    private int depth;
    private boolean failed;
    private final List<Boolean> marks = new ArrayList<>();
    private boolean readOnly;
    /** 12.7: سر الملف لفتح اتصال القراءة الثاني. */
    private final String password;
    /** 12.7: اتصال قراءة ثانٍ (وضع WAL): القراءة من خيط آخر لا تنتظر معاملة كتابة طويلة في الخلفية (اللقطة، الانضمام، الفحص). */
    private Connection reader;
    private final ReentrantLock readerLock = new ReentrantLock(true);
    /** 12.7: قواعد ملحقة (ATTACH) أو جداول مؤقتة على الاتصال الرئيسي: القراءة تبقى عليه. */
    private volatile int attached;
    /** 12.7: خيط عمل في الخلفية (الفحص): قراءاته من اتصال القراءة دائمًا فلا يحجز الاتصال الرئيسي عن الشاشة. */
    private static final ThreadLocal<Boolean> PREFER = new ThreadLocal<>();

    public static void preferReader(boolean on) {
        if (on) PREFER.set(Boolean.TRUE);
        else PREFER.remove();
    }

    /** للاختبار: عدد القراءات التي خدمها اتصال القراءة. */
    public static final java.util.concurrent.atomic.AtomicLong READER_HITS = new java.util.concurrent.atomic.AtomicLong();

    private SQLiteDatabase(String path, Connection c, String password) {
        this.path = path;
        this.c = c;
        this.password = password == null ? "" : password;
    }

    public static SQLiteDatabase openOrCreateDatabase(String path) { return openOrCreateDatabase(path, ""); }

    /**
     * 12.6: كاسم مكتبة أندرويد (net.zetetic SQLCipher): بكلمة سر غير فارغة يُفتح الملف مشفّرًا بصيغة SQLCipher 4
     * عبر SQLite3MultipleCiphers (مكتبة io.github.willena:sqlite-jdbc)، وبلا كلمة سر يُفتح ملفًا عاديًا.
     */
    public static SQLiteDatabase openOrCreateDatabase(String path, String password) {
        try {
            return new SQLiteDatabase(path, connect(path, password), password);
        } catch (Exception e) {
            throw new SQLiteException("تعذر فتح قاعدة البيانات: " + e.getMessage(), e);
        }
    }

    public static final int OPEN_READWRITE = 0, OPEN_READONLY = 1;

    /** 12.7: كتوقيع المكتبة على أندرويد (يُستخدم لفتح نسخة ملف للقراءة). */
    public static SQLiteDatabase openDatabase(String path, String password, Object factory, int flags, Object errorHandler, Object hook) {
        SQLiteDatabase d = openOrCreateDatabase(path, password);
        d.readOnly = (flags & OPEN_READONLY) != 0;
        return d;
    }

    private static Connection connect(String path, String password) throws Exception {
        Class.forName("org.sqlite.JDBC");
        org.sqlite.SQLiteConfig cfg;
        if (password != null && !password.isEmpty()) {
            org.sqlite.mc.SQLiteMCSqlCipherConfig mc = org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults();
            mc.withKey(password);
            cfg = mc.build();
        } else cfg = new org.sqlite.SQLiteConfig();
        cfg.setBusyTimeout(15000);
        Connection c = cfg.createConnection("jdbc:sqlite:" + path);
        c.setAutoCommit(true);
        return c;
    }

    /** 12.7: هل تُخدم هذه القراءة من اتصال القراءة؟ استعلام SELECT من خيط لا يملك المعاملة، والاتصال الرئيسي مشغول بخيط آخر. */
    private boolean useReader(String sql) {
        if (attached > 0 || lock.isHeldByCurrentThread()) return false;
        String t = sql.trim();
        if (t.length() < 6) return false;
        String h = t.substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        if (!h.startsWith("SELECT") && !h.startsWith("WITH")) return false;
        String low = t.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("temp.") || low.contains("del_entries")) return false;
        return true;
    }

    private Cursor readerQuery(String sql, String[] args) {
        readerLock.lock();
        try {
            if (reader == null || reader.isClosed()) {
                reader = connect(path, password);
                try (Statement s = reader.createStatement()) {
                    s.execute("PRAGMA query_only=1");
                    s.execute("PRAGMA cache_size=-16384");
                }
            }
            try (PreparedStatement p = reader.prepareStatement(sql)) {
                if (args != null) for (int i = 0; i < args.length; i++) {
                    if (args[i] == null) p.setNull(i + 1, java.sql.Types.NULL);
                    else p.setString(i + 1, args[i]);
                }
                boolean has = p.execute();
                READER_HITS.incrementAndGet();
                if (!has) return new Cursor(new String[0], new ArrayList<>());
                try (ResultSet rs = p.getResultSet()) {
                    return read(rs);
                }
            }
        } catch (SQLException e) {
            throw wrap(e, sql);
        } catch (Exception e) {
            throw new SQLiteException(e.getMessage(), e);
        } finally {
            readerLock.unlock();
        }
    }

    public String getPath() { return path; }

    public boolean isOpen() {
        try {
            return !c.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean isReadOnly() { return readOnly; }

    @Override public void close() {
        lock.lock();
        try {
            readerLock.lock();
            try {
                if (reader != null) reader.close();
            } catch (SQLException ignored) {
                // مغلق أصلًا
            } finally {
                reader = null;
                readerLock.unlock();
            }
            c.close();
        } catch (SQLException ignored) {
            // مغلق أصلًا
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- الإعداد

    public void setForeignKeyConstraintsEnabled(boolean on) { execSQL("PRAGMA foreign_keys=" + (on ? "ON" : "OFF")); }

    public boolean enableWriteAheadLogging() {
        try (Cursor x = rawQuery("PRAGMA journal_mode=WAL", null)) {
            return x.moveToFirst() && "wal".equalsIgnoreCase(x.getString(0));
        }
    }

    public int getVersion() {
        try (Cursor x = rawQuery("PRAGMA user_version", null)) {
            return x.moveToFirst() ? x.getInt(0) : 0;
        }
    }

    public void setVersion(int v) { execSQL("PRAGMA user_version=" + v); }

    // ---------------------------------------------------------------- المعاملات

    public void beginTransaction() {
        lock.lock();
        boolean ok = false;
        try {
            if (depth == 0) {
                failed = false;
                marks.clear();
                try (Statement s = c.createStatement()) {
                    s.execute("BEGIN IMMEDIATE");
                }
            }
            depth++;
            marks.add(false);
            ok = true;
        } catch (SQLException e) {
            throw new SQLiteException(e.getMessage(), e);
        } finally {
            if (!ok) lock.unlock();
        }
    }

    public void beginTransactionNonExclusive() { beginTransaction(); }

    public void setTransactionSuccessful() {
        if (depth == 0 || !lock.isHeldByCurrentThread()) throw new IllegalStateException("no transaction pending");
        marks.set(depth - 1, true);
    }

    public boolean inTransaction() { return depth > 0 && lock.isHeldByCurrentThread(); }

    public void endTransaction() {
        if (depth == 0 || !lock.isHeldByCurrentThread()) throw new IllegalStateException("no transaction pending");
        try {
            if (!marks.remove(depth - 1)) failed = true;
            depth--;
            if (depth == 0) {
                try (Statement s = c.createStatement()) {
                    s.execute(failed ? "ROLLBACK" : "COMMIT");
                } catch (SQLException e) {
                    try (Statement s = c.createStatement()) {
                        s.execute("ROLLBACK");
                    } catch (SQLException ignored) {
                        // انتهت أصلًا
                    }
                    throw new SQLiteException(e.getMessage(), e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- التنفيذ

    private void track(String sql) {
        String t = sql.trim().toUpperCase(java.util.Locale.ROOT);
        if (t.startsWith("ATTACH") || t.startsWith("CREATE TEMP")) attached++;
        else if (t.startsWith("DETACH") && attached > 0) attached--;
    }

    public void execSQL(String sql) {
        lock.lock();
        track(sql);
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw wrap(e, sql);
        } finally {
            lock.unlock();
        }
    }

    public void execSQL(String sql, Object[] args) {
        lock.lock();
        track(sql);
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bindObjects(p, args);
            p.execute();
        } catch (SQLException e) {
            throw wrap(e, sql);
        } finally {
            lock.unlock();
        }
    }

    /** الوسائط نصوص كما في أندرويد (SQLite يحوّلها حسب نوع العمود عند المقارنة). */
    public Cursor rawQuery(String sql, String[] args) {
        if (useReader(sql)) {
            if (Boolean.TRUE.equals(PREFER.get()) || !lock.tryLock()) return readerQuery(sql, args);
        } else lock.lock();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            if (args != null) for (int i = 0; i < args.length; i++) {
                if (args[i] == null) p.setNull(i + 1, java.sql.Types.NULL);
                else p.setString(i + 1, args[i]);
            }
            boolean has = p.execute();
            if (!has) return new Cursor(new String[0], new ArrayList<>());
            try (ResultSet rs = p.getResultSet()) {
                return read(rs);
            }
        } catch (SQLException e) {
            throw wrap(e, sql);
        } finally {
            lock.unlock();
        }
    }

    private static Cursor read(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] cols = new String[n];
        for (int i = 0; i < n; i++) cols[i] = md.getColumnLabel(i + 1);
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] r = new Object[n];
            for (int i = 0; i < n; i++) {
                Object o = rs.getObject(i + 1);
                if (o instanceof Integer || o instanceof Short || o instanceof Byte) o = ((Number) o).longValue();
                else if (o instanceof Float) o = ((Float) o).doubleValue();
                r[i] = o;
            }
            rows.add(r);
        }
        return new Cursor(cols, rows);
    }

    public long insertOrThrow(String table, String nullColumnHack, ContentValues v) {
        return insertWithOnConflictThrow(table, nullColumnHack, v, CONFLICT_NONE);
    }

    public long insert(String table, String nullColumnHack, ContentValues v) {
        try {
            return insertOrThrow(table, nullColumnHack, v);
        } catch (SQLiteException e) {
            return -1;
        }
    }

    public long insertWithOnConflict(String table, String nullColumnHack, ContentValues v, int conflict) {
        try {
            return insertWithOnConflictThrow(table, nullColumnHack, v, conflict);
        } catch (SQLiteException e) {
            return -1;
        }
    }

    public long replace(String table, String nullColumnHack, ContentValues v) { return insertWithOnConflict(table, nullColumnHack, v, CONFLICT_REPLACE); }

    private long insertWithOnConflictThrow(String table, String nullColumnHack, ContentValues v, int conflict) {
        StringBuilder sql = new StringBuilder("INSERT").append(CONFLICT[conflict]).append(" INTO ").append(table).append('(');
        List<Object> vals = new ArrayList<>();
        if (v == null || v.size() == 0) {
            sql.append(nullColumnHack).append(") VALUES (NULL)");
        } else {
            int i = 0;
            for (Map.Entry<String, Object> e : v.valueSet()) {
                if (i++ > 0) sql.append(',');
                sql.append(e.getKey());
                vals.add(e.getValue());
            }
            sql.append(") VALUES (");
            for (int k = 0; k < vals.size(); k++) sql.append(k > 0 ? ",?" : "?");
            sql.append(')');
        }
        lock.lock();
        try (PreparedStatement p = c.prepareStatement(sql.toString())) {
            bindObjects(p, vals.toArray());
            p.executeUpdate();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw wrap(e, sql.toString());
        } finally {
            lock.unlock();
        }
    }

    public int update(String table, ContentValues v, String where, String[] args) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        List<Object> vals = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Object> e : v.valueSet()) {
            if (i++ > 0) sql.append(',');
            sql.append(e.getKey()).append("=?");
            vals.add(e.getValue());
        }
        if (where != null && !where.isEmpty()) sql.append(" WHERE ").append(where);
        if (args != null) for (String a : args) vals.add(new TextArg(a));
        return change(sql.toString(), vals.toArray());
    }

    public int delete(String table, String where, String[] args) {
        String sql = "DELETE FROM " + table + (where != null && !where.isEmpty() ? " WHERE " + where : "");
        List<Object> vals = new ArrayList<>();
        if (args != null) for (String a : args) vals.add(new TextArg(a));
        return change(sql, vals.toArray());
    }

    private int change(String sql, Object[] vals) {
        lock.lock();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bindObjects(p, vals);
            return p.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e, sql);
        } finally {
            lock.unlock();
        }
    }

    /** وسيط شرط WHERE: نص كما في أندرويد. */
    private static final class TextArg {
        final String s;

        TextArg(String s) { this.s = s; }
    }

    private static void bindObjects(PreparedStatement p, Object[] args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            Object o = args[i];
            int k = i + 1;
            if (o == null) p.setNull(k, java.sql.Types.NULL);
            else if (o instanceof TextArg) {
                if (((TextArg) o).s == null) p.setNull(k, java.sql.Types.NULL);
                else p.setString(k, ((TextArg) o).s);
            } else if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte) p.setLong(k, ((Number) o).longValue());
            else if (o instanceof Boolean) p.setLong(k, (Boolean) o ? 1 : 0);
            else if (o instanceof Double || o instanceof Float) p.setDouble(k, ((Number) o).doubleValue());
            else if (o instanceof byte[]) p.setBytes(k, (byte[]) o);
            else p.setString(k, o.toString());
        }
    }

    private static SQLiteException wrap(SQLException e, String sql) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("CONSTRAINT")) return new SQLiteConstraintException(m);
        return new SQLiteException(m + " (in " + (sql.length() > 120 ? sql.substring(0, 120) + "…" : sql) + ")", e);
    }
}
