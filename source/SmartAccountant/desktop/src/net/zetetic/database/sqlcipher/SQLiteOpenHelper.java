package net.zetetic.database.sqlcipher;

import android.database.sqlite.SQLiteException;

import android.content.Context;

/**
 * كما في أندرويد: يفتح القاعدة مرة واحدة، ثم onConfigure ← (onCreate | onUpgrade | onDowngrade) داخل معاملة ← onOpen.
 */
public abstract class SQLiteOpenHelper {
    private final Context ctx;
    private final String name;
    private final int version;
    private SQLiteDatabase db;

    private final String password;

    protected SQLiteOpenHelper(Context ctx, String name, Object factory, int version) {
        this(ctx, name, "", factory, version, 0, null, null, false);
    }

    /** 12.6: كمكتبة SQLCipher لأندرويد: كلمة السر (فارغة = ملف عادي). */
    protected SQLiteOpenHelper(Context ctx, String name, String password, Object factory, int version, int minimumSupportedVersion,
                               Object errorHandler, Object hook, boolean wal) {
        this.ctx = ctx;
        this.name = name;
        this.version = version;
        this.password = password == null ? "" : password;
    }

    public String getDatabaseName() { return name; }

    public synchronized SQLiteDatabase getWritableDatabase() {
        if (db != null && db.isOpen()) return db;
        SQLiteDatabase d = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).getAbsolutePath(), password);
        try {
            onConfigure(d);
            int v = d.getVersion();
            if (v != version) {
                d.beginTransaction();
                try {
                    if (v == 0) onCreate(d);
                    else if (v < version) onUpgrade(d, v, version);
                    else onDowngrade(d, v, version);
                    d.setVersion(version);
                    d.setTransactionSuccessful();
                } finally {
                    d.endTransaction();
                }
            }
            onOpen(d);
        } catch (RuntimeException e) {
            d.close();
            throw e;
        }
        db = d;
        return d;
    }

    public SQLiteDatabase getReadableDatabase() { return getWritableDatabase(); }

    public synchronized void close() {
        if (db != null) db.close();
        db = null;
    }

    public void onConfigure(SQLiteDatabase d) {}

    public abstract void onCreate(SQLiteDatabase d);

    public abstract void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion);

    public void onDowngrade(SQLiteDatabase d, int oldVersion, int newVersion) {
        throw new SQLiteException("Can't downgrade database from version " + oldVersion + " to " + newVersion);
    }

    public void onOpen(SQLiteDatabase d) {}
}
