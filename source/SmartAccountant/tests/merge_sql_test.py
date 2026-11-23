#!/usr/bin/env python3
"""7.2: يطبّق خطة «الإضافة إلى البيانات الحالية» (من MergePlanTest) في SQLite بمخطط التطبيق وبجمل SQL المأخوذة حرفيًا
من Db.mergeMigration، ثم يتحقق بالاستعلامات نفسها التي يحسب بها التطبيق الأرصدة."""
import json, re, sqlite3, sys
DB_JAVA, DIR = sys.argv[1], sys.argv[2]
src = open(DB_JAVA, encoding="utf-8").read()
merge_src = src[src.index("public int mergeMigration("):src.index("// الاستعادة", src.index("public int mergeMigration("))]
fails = 0
def check(name, ok, extra=None):
    global fails
    print(("  PASS " if ok else "  FAIL ") + name + ("" if ok or extra is None else " — " + str(extra)))
    if not ok: fails += 1
def frag(s, where=None):
    w = where or src
    if s not in w:
        check("SQL fragment present in Db.java: " + s[:70], False)
    return s
ddl_block = src[src.index("private static final String[] DDL"):src.index("};", src.index("private static final String[] DDL"))]
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for d in re.findall(r'"(CREATE TABLE[^"]+)"', re.sub(r'"\s*\+\s*"', '', ddl_block)): db.execute(d)   # 12.7.1: جمل مقسومة بـ +
DEBIT, CREDIT, FUS, FTHEM, FNONE = "مدين / لنا", "دائن / علينا", "الأجرة لنا", "الأجرة علينا", "بدون أجرة"
for c, n, d in (("USD", "دولار", 2), ("EUR", "يورو", 2), ("TRY", "ليرة تركية", 2), ("SYP", "ليرة سورية", 0)):
    db.execute("INSERT INTO currencies(code,name,decimals,active) VALUES(?,?,?,1)", (c, n, d))
def post(tx, kind, office, cur, od, oc, t):
    # مثل Db.postEntry
    eid = db.execute("INSERT INTO journal_entries(tx_id,kind,created_at) VALUES(?,?,?)", (tx, kind, t)).lastrowid
    db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) VALUES(?,?,?,?,?,?)", (eid, "OFFICE", office, cur, od, oc))
    contra = ("FEE_REVENUE" if od > 0 else "FEE_EXPENSE") if kind == "FEE" else ("OPENING" if kind == "OPENING" else "SETTLEMENT")
    db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) VALUES(?,?,NULL,?,?,?)", (eid, contra, cur, oc, od))
    return eid
plan = json.load(open(DIR + "/merge-plan.json", encoding="utf-8"))
# البيانات الحالية (كما في MergePlanTest)
for oid, name in plan["existingOffices"].items():
    db.execute("INSERT INTO offices(id,name,active,pinned) VALUES(?,?,1,0)", (int(oid), name))
for k, v in plan["existingBalances"].items():
    o, c = k.split("|")
    db.execute("INSERT OR IGNORE INTO office_currencies(office_id,code) VALUES(?,?)", (int(o), c))
    post(None, "OPENING", int(o), c, v if v > 0 else 0, -v if v < 0 else 0, 1700000000000)
q_bal = frag("SELECT COALESCE(SUM(debit_minor-credit_minor),0) FROM journal_lines WHERE account='OFFICE' AND office_id=? AND currency=?")
ins_oc = frag("INSERT OR IGNORE INTO office_currencies(office_id,code) VALUES(?,?)", merge_src)
act = frag("UPDATE currencies SET active=1 WHERE code=?", merge_src)
unbal = frag("SELECT entry_id,currency,SUM(debit_minor-credit_minor) s FROM journal_lines GROUP BY entry_id,currency HAVING s<>0 LIMIT 1", merge_src)
# التطبيق بالترتيب نفسه في Db.mergeMigration
target = {}
for o in plan["offices"]:
    if o["target"] is None:
        tid = db.execute("INSERT INTO offices(name,active,pinned) VALUES(?,1,0)", (o["name"],)).lastrowid
    else:
        tid = o["target"]
    target[o["fileId"]] = tid
    for c in o["codes"]:
        db.execute(ins_oc, (tid, c))
        db.execute(act, (c,))
want = {}
for k, v in plan["expected"].items():
    fo, c = k.split("|")
    key = (target[int(fo)], c)
    if key not in want: want[key] = db.execute(q_bal, (str(key[0]), c)).fetchone()[0]
    want[key] += v
for op in plan["openings"]:
    oid = target[op["office"]]; amt = abs(op["signed"])
    eid = post(None, "OPENING", oid, op["code"], amt if op["signed"] > 0 else 0, amt if op["signed"] < 0 else 0, op["when"])
    db.execute("INSERT INTO opening_balances(office_id,currency,type,amount_minor,entry_id) VALUES(?,?,?,?,?)", (oid, op["code"], "لنا" if op["signed"] > 0 else "علينا", amt, eid))
for x in plan["txs"]:
    oid = target[x["office"]]; fee = x["feeType"] != FNONE
    tid = db.execute("INSERT INTO tx(uuid,office_id,type,person,amount_minor,currency,fee_type,fee_minor,fee_currency,description,notes,created_at,user_name) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     (x["uuid"], oid, x["type"], "", x["amount"], x["currency"], x["feeType"], x["fee"] if fee else 0, x["feeCurrency"] if fee else x["currency"], "", "", x["when"], "")).lastrowid
    post(tid, "PRINCIPAL", oid, x["currency"], x["amount"] if x["type"] == DEBIT else 0, x["amount"] if x["type"] == CREDIT else 0, x["when"])
    if fee: post(tid, "FEE", oid, x["feeCurrency"], x["fee"] if x["feeType"] == FUS else 0, x["fee"] if x["feeType"] == FTHEM else 0, x["when"])
for (oid, c), v in want.items():
    got = db.execute(q_bal, (str(oid), c)).fetchone()[0]
    check("verification balance office %s %s = %s" % (oid, c, v), got == v, got)
check("no unbalanced entry", db.execute(unbal).fetchone() is None)
# أرصدة التطبيق باستعلامه نفسه تساوي «بعد الإضافة» في المعاينة
q_office_balances = re.search(r'"(SELECT office_id,currency,SUM\(debit_minor-credit_minor\) FROM journal_lines[^"]+)"', src).group(1)
names = dict(db.execute("SELECT id,name FROM offices"))
bal = {(names[o], c): v for o, c, v in db.execute(q_office_balances)}
for r in plan["rows"]:
    check("officeBalances %s %s = preview after %s" % (r["office"], r["code"], r["after"]), bal.get((r["office"], r["code"])) == r["after"], bal.get((r["office"], r["code"])))
check("untouched office keeps its balance (مكتب آخر USD −777.00)", bal.get(("مكتب آخر", "USD")) == -77700, bal.get(("مكتب آخر", "USD")))
check("existing office not duplicated", db.execute("SELECT COUNT(*) FROM offices WHERE name='الخال'").fetchone()[0] == 1)
check("one new office (النور)", db.execute("SELECT COUNT(*) FROM offices").fetchone()[0] == 4)
check("tx uuids unique (re-import would fail)", db.execute("SELECT COUNT(*),COUNT(DISTINCT uuid) FROM tx").fetchone()[0] == len(plan["txs"]))
check("foreign keys consistent", not db.execute("PRAGMA foreign_key_check").fetchall())
print("ALL MERGE SQL TESTS PASSED" if fails == 0 else str(fails) + " FAILED")
sys.exit(1 if fails else 0)
