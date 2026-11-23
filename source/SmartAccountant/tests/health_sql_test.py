#!/usr/bin/env python3
"""8.0: يشغّل استعلامات الفحص التلقائي والترقية وحدود المجاني كما هي في Db.java على SQLite.
- هوية الأرصدة (BALANCE_IDENTITY_SQL): صفر فروق على بيانات سليمة، وفروق عند قيد ناقص أو إلغاء بلا عكس.
- أرضية عدّادات المجاني: الحركات الأصلية لكل مكتب (التصحيحات لا تُحتسب، والملغاة تُحتسب).
- ترقية المخطط (ت12): قاعدة بأعمدة الإصدار الأول تُرقّى بأوامر COLS نفسها دون فقد بيانات، وكل أمر ALTER صالح.
الاستعمال: python3 tests/health_sql_test.py [ملفات ‎*.tables.json ...]  (افتراضيًا tests/data)"""
import glob
import json
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "java", "com", "smart", "hawala")
db_java = open(os.path.join(SRC, "Db.java"), encoding="utf-8").read()
model = open(os.path.join(SRC, "Model.java"), encoding="utf-8").read()
fails = 0


def check(c, m):
    global fails
    print(("  PASS " if c else "  FAIL ") + m)
    fails += 0 if c else 1


def const(name):
    return re.search(r'%s = "([^"]+)"' % name, model).group(1)


DEBIT, CREDIT, FUS, FTHEM, FNONE = const("TYPE_DEBIT"), const("TYPE_CREDIT"), const("FEE_US"), const("FEE_THEM"), const("FEE_NONE")


def java_concat(name):
    """قيمة ثابت نصي مبني بـ + في Db.java."""
    i = db_java.index("static final String %s =" % name)
    body = db_java[i:db_java.index(";", i)]
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', body))


IDENTITY = java_concat("BALANCE_IDENTITY_SQL")
ddl_block = db_java[db_java.index("private static final String[] DDL"):db_java.index("};", db_java.index("private static final String[] DDL"))]
DDL = re.findall(r'"(CREATE TABLE[^"]+)"', re.sub(r'"\s*\+\s*"', '', ddl_block))   # 12.7.1: جمل مقسومة على أسطر بـ +
cols_block = db_java[db_java.index("private static final String[][] COLS"):db_java.index("};", db_java.index("private static final String[][] COLS"))]
COLS = re.findall(r'\{"(\w+)", "(\w+)", "([^"]+)"\}', cols_block)
check(len(DDL) >= 15 and len(COLS) >= 60, "قُرئ المخطط من Db.java: %d جدول و%d عمود مضاف" % (len(DDL), len(COLS)))
check("prev_hash" in [c[1] for c in COLS] and any("audit_archive" in d for d in DDL), "أعمدة سلسلة التدقيق وجدول الأرشيف في المخطط")


def fresh():
    db = sqlite3.connect(":memory:")
    for s in DDL:
        db.execute(s)
    for t, c, ty in COLS:
        have = [r[1] for r in db.execute("PRAGMA table_info(%s)" % t)]
        if c not in have:
            db.execute("ALTER TABLE %s ADD COLUMN %s %s" % (t, c, ty))
    return db


def load(db, tables):
    order = ["currencies", "offices", "office_currencies", "tx", "journal_entries", "journal_lines", "opening_balances", "exchange_rates",
             "settings", "audit_logs", "reconciliations", "reconciliation_lines"]
    for t in order:
        rows = tables.get(t) or []
        if not rows:
            continue
        have = set(r[1] for r in db.execute("PRAGMA table_info(%s)" % t))
        for r in rows:
            ks = [k for k in r if k in have]
            db.execute("INSERT INTO %s(%s) VALUES(%s)" % (t, ",".join(ks), ",".join("?" * len(ks))), [r[k] for k in ks])


def identity(db):
    return db.execute(IDENTITY, (DEBIT, FUS, FTHEM)).fetchall()


files = sys.argv[1:] or sorted(glob.glob(os.path.join(HERE, "data", "*.tables.json")))
print("== هوية الأرصدة على ملفات النقل (%d)" % len(files))
for f in files:
    tables = json.load(open(f, encoding="utf-8"))
    db = fresh()
    load(db, tables)
    n_tx = db.execute("SELECT COUNT(*) FROM tx").fetchone()[0]
    bad = identity(db)
    check(not bad, "%s: %d حركة، صفر فروق %s" % (os.path.basename(f), n_tx, bad[:3]))

print("== هوية الأرصدة تكشف الخلل")
db = fresh()
db.execute("INSERT INTO currencies(code,name,decimals,active) VALUES('USD','دولار',2,1)")   # 12.5: أعمدة المعادن بقيمها الافتراضية
db.execute("INSERT INTO offices(id,name,active,pinned) VALUES(1,'أ',1,0)")


def post(tx, kind, office, cur, od, oc, t=1):
    eid = db.execute("INSERT INTO journal_entries(tx_id,kind,created_at) VALUES(?,?,?)", (tx, kind, t)).lastrowid
    db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) VALUES(?,?,?,?,?,?)", (eid, "OFFICE", office, cur, od, oc))
    contra = ("FEE_REVENUE" if od > 0 else "FEE_EXPENSE") if kind == "FEE" else ("OPENING" if kind == "OPENING" else "SETTLEMENT")
    db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) VALUES(?,?,NULL,?,?,?)", (eid, contra, cur, oc, od))
    return eid


def tx(i, typ, amt, fee_type=FNONE, fee=0, replaces=None):
    db.execute("INSERT INTO tx(id,uuid,office_id,type,amount_minor,currency,fee_type,fee_minor,fee_currency,created_at,status,replaces_id) VALUES(?,?,1,?,?,'USD',?,?,'USD',1,'ACTIVE',?)",
               (i, "u%d" % i, typ, amt, fee_type, fee, replaces))
    post(i, "PRINCIPAL", 1, "USD", amt if typ == DEBIT else 0, amt if typ == CREDIT else 0)
    if fee:
        post(i, "FEE", 1, "USD", fee if fee_type == FUS else 0, fee if fee_type == FTHEM else 0)


def cancel(i):
    for (old,) in db.execute("SELECT id FROM journal_entries WHERE tx_id=? AND COALESCE(kind,'')<>'REVERSAL'", (i,)).fetchall():
        ne = db.execute("INSERT INTO journal_entries(tx_id,kind,created_at,reverses_id) VALUES(?,'REVERSAL',2,?)", (i, old)).lastrowid
        db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) SELECT ?,account,office_id,currency,credit_minor,debit_minor FROM journal_lines WHERE entry_id=?", (ne, old))
    db.execute("UPDATE tx SET status='CANCELLED' WHERE id=?", (i,))


eo = post(None, "OPENING", 1, "USD", 50000, 0)
db.execute("INSERT INTO opening_balances(office_id,currency,type,amount_minor,entry_id) VALUES(1,'USD','لنا',50000,?)", (eo,))
tx(1, DEBIT, 100000, FUS, 500)
tx(2, CREDIT, 70000, FTHEM, 300)
tx(3, DEBIT, 30000)
cancel(3)
tx(4, CREDIT, 25000, replaces=2)   # تصحيح الحركة 2
cancel(2)
check(not identity(db), "رصيد سابق + حركات بأجور + إلغاء + تصحيح: صفر فروق")
db.commit()
db.execute("DELETE FROM journal_lines WHERE id=(SELECT MAX(id) FROM journal_lines WHERE account='OFFICE')")
check(len(identity(db)) == 1, "قيد ناقص يظهر فرقًا")
db.rollback()
check(not identity(db), "بعد التراجع: صفر فروق")
db.execute("UPDATE tx SET status='CANCELLED' WHERE id=1")
check(len(identity(db)) >= 1, "حركة معلَّمة ملغاة بلا قيد عكسي تظهر فرقًا")
db.rollback()

print("== دمج مكتبين (MERGE_SQL كما في Db.java)")
mb = db_java[db_java.index("static final String[] MERGE_SQL"):db_java.index("};", db_java.index("static final String[] MERGE_SQL"))]
MERGE = re.findall(r'"((?:[^"\\]|\\.)*)"', mb)
check(len(MERGE) >= 12 and MERGE[-1].startswith("DELETE FROM offices") and any("cash_moves" in q for q in MERGE) and any("office_holds" in q for q in MERGE),
      "أوامر الدمج مقروءة (%d) ومعها ربط حركات الصندوق (9.0) والأمانات (12.4)" % len(MERGE))


def bal(d, office):
    return dict(d.execute("SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=? GROUP BY currency", (office,)).fetchall())


for f in files:
    tables = json.load(open(f, encoding="utf-8"))
    md = fresh()
    md.execute("PRAGMA foreign_keys=ON")
    load(md, tables)
    names = dict(md.execute("SELECT name,id FROM offices").fetchall())
    pair = os.environ.get("MERGE_PAIR", "").split("|")   # اختياري: «اسم المصدر|اسم الهدف» من ملفك
    if len(pair) == 2 and pair[0] in names and pair[1] in names:
        a, b = names[pair[0]], names[pair[1]]
    else:
        busy = [r[0] for r in md.execute("SELECT office_id FROM tx GROUP BY office_id ORDER BY COUNT(*) DESC LIMIT 2")]
        if len(busy) < 2:
            continue
        a, b = busy
    want = bal(md, b)
    for k, v in bal(md, a).items():
        want[k] = want.get(k, 0) + v
    n_tx = md.execute("SELECT COUNT(*) FROM tx").fetchone()[0]
    for q in MERGE:
        md.execute(q.replace("{from}", str(a)).replace("{into}", str(b)))
    got = bal(md, b)
    check(got == {k: v for k, v in want.items() if v != 0 or k in got} and not identity(md) and md.execute("SELECT COUNT(*) FROM tx").fetchone()[0] == n_tx
          and md.execute("SELECT COUNT(*) FROM offices WHERE id=?", (a,)).fetchone()[0] == 0 and not md.execute("PRAGMA foreign_key_check").fetchall(),
          "%s: دمج %s في %s — الرصيد = مجموع الرصيدين، والحركات كلها باقية، وهوية الأرصدة سليمة %s" % (os.path.basename(f), a, b, got))

print("== أرضية عدّادات المجاني")
q_live = "SELECT office_id,COUNT(*) FROM tx WHERE replaces_id IS NULL"
check(q_live in db_java and " GROUP BY office_id" in db_java, "الاستعلام موجود كما هو في Db.java")
live = dict(db.execute(q_live + " AND office_id=1 GROUP BY office_id").fetchall())
check(live.get(1) == 3, "الحركات 1 و2 و3 أصلية (الملغاة تُحتسب) والتصحيح 4 لا يُحتسب: %s" % live)
# 9.0: حذف المكتب يعيد مكانه: عدّاد المكاتب = الموجودة فعلًا دائمًا (لا أرضية من سجل التدقيق)
check("q.offices = 0;" in db_java and "SELECT COUNT(*) FROM offices" in db_java, "9.0: عدّاد المكاتب = المكاتب الموجودة فعلًا (الحذف يعيد المكان)")

print("== أرشيف سجل العمليات عند الاستعادة")
arch = re.search(r'"(INSERT INTO audit_archive\([^"]+)"\s*\+\s*"(SELECT id,action[^"]+)"', db_java)
check(arch is not None, "أمر الأرشفة موجود في Db.java")
db.execute("INSERT INTO audit_logs(action,entity,entity_id,user_name,reason,created_at,prev_hash,row_hash) VALUES('CREATE_TRANSACTION','tx',1,'x','y',5,'','h1')")
db.execute(arch.group(1) + arch.group(2) + "77 FROM audit_logs")
check(db.execute("SELECT orig_id,row_hash,archived_at FROM audit_archive").fetchall() == [(1, "h1", 77)], "الأسطر تُنقل كما هي إلى الأرشيف")

print("== ترقية المخطط (ت12)")
for t, c, ty in COLS:
    bad = re.search(r"NOT NULL(?!.*DEFAULT)|PRIMARY|UNIQUE", ty)
    if bad:
        check(False, "عمود يُضاف بـ ALTER لا يقبل: %s.%s %s" % (t, c, ty))
check(True, "كل أعمدة COLS صالحة لـ ALTER TABLE ADD COLUMN")
# قاعدة الإصدار الأول: الأعمدة الأساسية فقط
v1 = sqlite3.connect(":memory:")
v1.execute("CREATE TABLE currencies(code TEXT PRIMARY KEY,name TEXT,decimals INTEGER)")
v1.execute("CREATE TABLE offices(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)")
v1.execute("CREATE TABLE office_currencies(office_id INTEGER,code TEXT,limit_minor INTEGER,PRIMARY KEY(office_id,code))")
v1.execute("CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT,office_id INTEGER NOT NULL,type TEXT NOT NULL,amount_minor INTEGER NOT NULL,currency TEXT NOT NULL,created_at INTEGER NOT NULL,status TEXT)")
v1.execute("CREATE TABLE journal_entries(id INTEGER PRIMARY KEY AUTOINCREMENT,tx_id INTEGER)")
v1.execute("CREATE TABLE journal_lines(id INTEGER PRIMARY KEY AUTOINCREMENT,entry_id INTEGER NOT NULL,account TEXT NOT NULL,office_id INTEGER,currency TEXT NOT NULL,debit_minor INTEGER NOT NULL,credit_minor INTEGER NOT NULL)")
v1.execute("CREATE TABLE settings(k TEXT PRIMARY KEY,v TEXT)")
v1.execute("CREATE TABLE audit_logs(id INTEGER PRIMARY KEY AUTOINCREMENT)")
v1.execute("INSERT INTO currencies VALUES('USD','دولار',2)")
v1.execute("INSERT INTO offices(id,name) VALUES(1,'الأمل')")
v1.execute("INSERT INTO office_currencies VALUES(1,'USD',500000)")
v1.execute("INSERT INTO tx VALUES(1,1,?,150000,'USD',1700000000000,'ACTIVE')", (DEBIT,))
v1.execute("INSERT INTO journal_entries VALUES(1,1)")
v1.execute("INSERT INTO journal_lines VALUES(1,1,'OFFICE',1,'USD',150000,0)")
v1.execute("INSERT INTO journal_lines VALUES(2,1,'SETTLEMENT',NULL,'USD',0,150000)")
v1.execute("INSERT INTO audit_logs(id) VALUES(1)")
# ensureSchema كما في Db.java: إنشاء الناقص، ثم الأعمدة الناقصة، ثم ملء uuid
for s in DDL:
    v1.execute(s)
filled = False
for t, c, ty in COLS:
    have = [r[1].lower() for r in v1.execute("PRAGMA table_info(%s)" % t)]
    if c not in have:
        v1.execute("ALTER TABLE %s ADD COLUMN %s %s" % (t, c, ty))
        if t == "tx" and c == "uuid":
            v1.execute("UPDATE tx SET uuid=lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid=''")
            filled = True
check("UPDATE tx SET uuid=lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid=''" in db_java and filled, "مراجع الحركات القديمة تُملأ عند إضافة uuid")
v1.execute("UPDATE office_currencies SET limit_owe_minor=limit_minor WHERE limit_owe_minor IS NULL AND limit_minor IS NOT NULL")
for t in ["tx", "audit_logs", "office_currencies", "offices"]:
    want = set(c for tt, c, ty in COLS if tt == t)
    have = set(r[1] for r in v1.execute("PRAGMA table_info(%s)" % t))
    check(want <= have, "الجدول %s فيه كل أعمدة 8.0 بعد الترقية" % t)
r = v1.execute("SELECT amount_minor,currency,status,length(uuid),fee_type,fee_minor FROM tx").fetchone()
check(r[0] == 150000 and r[1] == "USD" and r[2] == "ACTIVE" and r[3] == 32 and r[4] == FNONE and r[5] == 0, "الحركة القديمة سليمة بعد الترقية: %s" % (r,))
check(v1.execute("SELECT limit_minor,limit_owe_minor FROM office_currencies").fetchone() == (500000, 500000), "حد «علينا» يُنسخ من الحد القديم (ترقية 5)")
check(v1.execute("SELECT SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE'").fetchone()[0] == 150000, "الرصيد لم يتغير")
check(v1.execute("SELECT active,pinned FROM offices").fetchone() == (1, 0), "قيم افتراضية للأعمدة الجديدة")
check(not v1.execute(IDENTITY, (DEBIT, FUS, FTHEM)).fetchall(), "هوية الأرصدة سليمة على القاعدة المرقّاة")

print("ALL PASSED" if fails == 0 else "%d FAILED" % fails)
sys.exit(1 if fails else 0)
