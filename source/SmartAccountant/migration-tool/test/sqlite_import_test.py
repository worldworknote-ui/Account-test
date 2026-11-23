#!/usr/bin/env python3
"""يستورد جداول ملفات أداة النقل (بعد فكها بالكود Java) في SQLite بنفس مخطط التطبيق (DDL من Db.java)،
ثم يشغّل استعلامات الأرصدة نفسها التي يستخدمها التطبيق ويقارنها بالقيم المتوقعة."""
import json
import re
import sqlite3
import sys

DB_JAVA = sys.argv[1]
DIR = sys.argv[2]
src = open(DB_JAVA, encoding="utf-8").read()
ddl_block = src[src.index("private static final String[] DDL"):src.index("};", src.index("private static final String[] DDL"))]
ddl = re.findall(r'"(CREATE TABLE[^"]+)"', ddl_block)
assert len(ddl) >= 14, len(ddl)

# الاستعلامات كما هي في Db.java
q_office_balances = re.search(r'"(SELECT office_id,currency,SUM\(debit_minor-credit_minor\) FROM journal_lines[^"]+)"', src).group(1)
q_totals = ('SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM journal_lines l'
            ' JOIN journal_entries e ON e.id=l.entry_id LEFT JOIN tx t ON t.id=e.tx_id'
            " WHERE l.account='OFFICE' AND l.office_id IS NOT NULL AND (e.tx_id IS NULL OR t.status='ACTIVE')"
            ' GROUP BY l.office_id,l.currency')
assert "l.account='OFFICE'" in src and "(e.tx_id IS NULL OR t.status='ACTIVE')" in src

expect = json.load(open(DIR + "/expect.json", encoding="utf-8"))
fails = 0


def check(name, ok, extra=None):
    global fails
    print(("  PASS " if ok else "  FAIL ") + name + ("" if ok or extra is None else " — " + str(extra)))
    if not ok:
        fails += 1


ORDER = ["currencies", "offices", "office_currencies", "tx", "journal_entries", "journal_lines",
         "opening_balances", "exchange_rates", "settings", "audit_logs", "users", "reconciliations", "reconciliation_lines"]
for name in sorted(expect.keys()):
    print("== " + name)
    tables = json.load(open(DIR + "/" + name + ".tables.json", encoding="utf-8"))
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for d in ddl:
        db.execute(d)
    for t in ORDER:
        cols = {r[1].lower() for r in db.execute("PRAGMA table_info(" + t + ")")}
        for row in tables.get(t, []):
            keys = [k for k in row if k.lower() in cols]
            db.execute("INSERT INTO " + t + "(" + ",".join(keys) + ") VALUES(" + ",".join("?" * len(keys)) + ")", [row[k] for k in keys])
    names = dict(db.execute("SELECT id,name FROM offices"))
    bal = {names[o] + "|" + c: v for o, c, v in db.execute(q_office_balances)}
    tot = {names[o] + "|" + c: d - cr for o, c, d, cr in db.execute(q_totals)}
    for office, cur, v in expect[name]["balances"]:
        k = office + "|" + cur
        check("officeBalances " + k, bal.get(k) == v, bal.get(k))
        check("officeTotals " + k, tot.get(k) == v, tot.get(k))
    unbalanced = db.execute("SELECT entry_id,currency,SUM(debit_minor-credit_minor) s FROM journal_lines GROUP BY entry_id,currency HAVING s<>0").fetchall()
    check("all entries balanced in SQLite", not unbalanced, unbalanced)
    fk = db.execute("PRAGMA foreign_key_check").fetchall()
    check("foreign keys consistent", not fk, fk)
    opening = db.execute("SELECT COUNT(*) FROM opening_balances ob JOIN journal_entries e ON e.id=ob.entry_id WHERE e.kind='OPENING'").fetchone()[0]
    check("opening rows linked to OPENING entries", opening == expect[name]["openings"], opening)
print("ALL SQLITE IMPORT TESTS PASSED" if fails == 0 else str(fails) + " FAILED")
sys.exit(1 if fails else 0)
