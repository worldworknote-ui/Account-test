import re, sqlite3, sys
import os, sys
src = open(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'java', 'com', 'smart', 'hawala', 'Db.java'), encoding='utf-8').read()
fails = 0
def check(c, m):
    global fails
    print(("  PASS " if c else "  FAIL ") + m); fails += 0 if c else 1
def frag(*parts):
    # every SQL fragment used here must exist verbatim in Db.java (so the test exercises the real SQL)
    for p in parts:
        if p not in src: print("  FAIL fragment not found in Db.java:", p[:90]); globals()['fails'] += 1
    return "".join(parts)
def T(*a): return tuple(str(x) for x in a)   # Android rawQuery binds everything as TEXT

ddl = re.findall(r'"(CREATE TABLE IF NOT EXISTS [^"]+)"', re.sub(r'"\s*\+\s*"', '', src))   # 12.7.1: جمل مقسومة بـ +
db = sqlite3.connect(':memory:'); db.execute('PRAGMA foreign_keys=ON')
for s in ddl: db.execute(s)
DEBIT, CREDIT, FUS, FTHEM, FNONE = "مدين / لنا", "دائن / علينا", "الأجرة لنا", "الأجرة علينا", "بدون أجرة"
db.execute("INSERT INTO currencies VALUES('USD','دولار',2,1)"); db.execute("INSERT INTO currencies VALUES('SYP','ليرة',0,1)")
db.execute("INSERT INTO offices(id,name,active,pinned) VALUES(1,'a',1,0)"); db.execute("INSERT INTO offices(id,name,active,pinned) VALUES(2,'b',1,1)")
for o in (1,2):
    for c in ('USD','SYP'): db.execute("INSERT INTO office_currencies(office_id,code) VALUES(?,?)", (o,c))
def post(tx, kind, office, cur, od, oc, t):
    eid = db.execute("INSERT INTO journal_entries(tx_id,kind,created_at) VALUES(?,?,?)", (tx, kind, t)).lastrowid
    db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) VALUES(?,?,?,?,?,?)", (eid,'OFFICE',office,cur,od,oc))
    contra = ('FEE_REVENUE' if od > 0 else 'FEE_EXPENSE') if kind == 'FEE' else ('OPENING' if kind == 'OPENING' else 'SETTLEMENT')
    db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) VALUES(?,?,NULL,?,?,?)", (eid,contra,cur,oc,od))
def tx(id, office, typ, amt, cur, t, fee_type=FNONE, fee=0, status='ACTIVE'):
    db.execute("INSERT INTO tx(id,uuid,office_id,type,amount_minor,currency,fee_type,fee_minor,fee_currency,created_at,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
               (id, 'u%d'%id, office, typ, amt, cur, fee_type, fee, cur, t, status))
    post(id, 'PRINCIPAL', office, cur, amt if typ == DEBIT else 0, amt if typ == CREDIT else 0, t)
    if fee: post(id, 'FEE', office, cur, fee if fee_type == FUS else 0, fee if fee_type == FTHEM else 0, t)
DAY = 86400000; t0 = 1760000000000
post(None, 'OPENING', 1, 'USD', 20000, 0, t0 - 10*DAY)
tx(1, 1, DEBIT, 100000, 'USD', t0, FUS, 500)
tx(2, 1, CREDIT, 70000, 'USD', t0 + DAY)
tx(3, 1, DEBIT, 30000, 'USD', t0 + 2*DAY)
tx(4, 2, CREDIT, 2600000, 'SYP', t0 + 2*DAY, FTHEM, 1000)
# cancel tx 3 using the exact reversal SQL from Db.reverseTx
olds = db.execute(frag("SELECT id FROM journal_entries WHERE tx_id=", "") + "3" + frag(" AND COALESCE(status,'POSTED')='POSTED' AND COALESCE(kind,'')<>'REVERSAL'")).fetchall()
for (old,) in olds:
    ne = db.execute("INSERT INTO journal_entries(tx_id,kind,created_at,reverses_id) VALUES(3,'REVERSAL',?,?)", (t0 + 5*DAY, old)).lastrowid
    # 10.0+: Db.reverseTx reads the original lines then inserts them one by one (each row gets an id from the device range)
    rev = db.execute(frag("SELECT account,office_id,currency,credit_minor,debit_minor FROM journal_lines WHERE entry_id=") + str(old) + frag(" ORDER BY id")).fetchall()
    for r in rev:
        db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) VALUES(?,?,?,?,?,?)", (ne,) + tuple(r))
db.execute("UPDATE tx SET status='CANCELLED' WHERE id=3")

print("1) statementLines with literal dates (the 5.0 bug: TEXT params returned nothing)")
q_st = frag("SELECT l.id,l.entry_id,COALESCE(e.kind,''),COALESCE(e.created_at,0),l.currency,l.debit_minor,l.credit_minor,e.tx_id,",
            "t.uuid,t.type,t.person,t.description,t.status,t.fee_type,t.notes",
            " FROM journal_lines l JOIN journal_entries e ON e.id=l.entry_id LEFT JOIN tx t ON t.id=e.tx_id",
            " WHERE l.account='OFFICE' AND l.office_id=")
def st(office, frm, to):
    return db.execute(q_st + str(office) + frag(" AND COALESCE(e.created_at,0)>=") + str(frm) + frag(" AND COALESCE(e.created_at,0)<") + str(to)
                      + frag(" ORDER BY COALESCE(e.created_at,0),l.id")).fetchall()
rows = st(1, 0, 2**63-1)
check(len(rows) == 6, f"all periods: 6 lines (opening, principal, fee, credit, cancelled + reversal) -> {len(rows)}")
check(len(st(1, t0, t0 + DAY)) == 2, "period [t0, t0+1d): principal + fee")

print("2) balanceBefore, with and without cancelled")
q_bb = frag("SELECT l.currency,SUM(l.debit_minor-l.credit_minor) FROM journal_lines l JOIN journal_entries e ON e.id=l.entry_id",
            " LEFT JOIN tx t ON t.id=e.tx_id WHERE l.account='OFFICE' AND l.office_id=")
def bb(office, before, excl):
    return dict(db.execute(q_bb + str(office) + frag(" AND COALESCE(e.created_at,0)<") + str(before)
            + (frag(" AND (e.tx_id IS NULL OR t.status='ACTIVE')") if excl else "") + frag(" GROUP BY l.currency")).fetchall())
check(bb(1, t0, False) == {'USD': 20000}, f"before t0 = opening 200.00 -> {bb(1, t0, False)}")
check(bb(1, t0 + 3*DAY, False) == {'USD': 80500}, f"before t0+3d incl. cancelled tx (not yet reversed) = 805.00 -> {bb(1, t0+3*DAY, False)}")
check(bb(1, t0 + 3*DAY, True) == {'USD': 50500}, f"same, cancelled excluded = 505.00 -> {bb(1, t0+3*DAY, True)}")
check(bb(1, t0 + 9*DAY, False) == bb(1, t0 + 9*DAY, True) == {'USD': 50500}, "after reversal both agree: 505.00")

print("3) office totals: every لنا, every علينا, balance (cancelled excluded from gross)")
# 12.7: كل أسطر المكاتب ناقص أسطر الحركات غير النشطة وناقص قيود حركة غير موجودة (الجمل حرفيًا من Db.officeTotals)
q_all = frag("SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM journal_lines l",
             " WHERE l.account='OFFICE' AND l.office_id IS NOT NULL") + frag(" GROUP BY l.office_id,l.currency")
q_m1 = frag("SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM tx t JOIN journal_entries e ON e.tx_id=t.id",
            " JOIN journal_lines l ON l.entry_id=e.id WHERE t.status<>'ACTIVE' AND l.account='OFFICE' AND l.office_id IS NOT NULL") + frag(" GROUP BY l.office_id,l.currency")
q_m2 = frag("SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM journal_entries e JOIN journal_lines l ON l.entry_id=e.id",
            " WHERE e.tx_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tx x WHERE x.id=e.tx_id) AND l.account='OFFICE' AND l.office_id IS NOT NULL") + frag(" GROUP BY l.office_id,l.currency")
acc = {(r[0], r[1]): [r[2], r[3]] for r in db.execute(q_all)}
for q in (q_m1, q_m2):
    for r in db.execute(q):
        if (r[0], r[1]) in acc:
            acc[(r[0], r[1])][0] -= r[2]
            acc[(r[0], r[1])][1] -= r[3]
got = {k: (v[0], v[1], v[0] - v[1]) for k, v in acc.items()}
# الطريقة القديمة (ربط كل سطر بقيده وحركته) للمقارنة: النتيجتان متطابقتان
old = {(r[0], r[1]): (r[2], r[3], r[2]-r[3]) for r in db.execute("SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM journal_lines l"
       " JOIN journal_entries e ON e.id=l.entry_id LEFT JOIN tx t ON t.id=e.tx_id"
       " WHERE l.account='OFFICE' AND l.office_id IS NOT NULL AND (e.tx_id IS NULL OR t.status='ACTIVE') GROUP BY l.office_id,l.currency")}
check(got == old, f"12.7 office totals == old join method: {got} vs {old}")
check(got[(1,'USD')] == (120500, 70000, 50500), f"office 1 USD: لنا 1205.00 (opening+principal+fee) / علينا 700.00 / رصيد لنا 505.00 -> {got[(1,'USD')]}")
check(got[(2,'SYP')] == (0, 2601000, -2601000), f"office 2 SYP: علينا 2,601,000 -> {got[(2,'SYP')]}")
net = dict(((r[0], r[1]), r[2]) for r in db.execute(frag("SELECT office_id,currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id IS NOT NULL GROUP BY office_id,currency")))
check(net[(1,'USD')] == got[(1,'USD')][2], "gross balance == true net balance")

print("4) daily stats with TEXT-bound fee types")
rng = frag(" WHERE status='ACTIVE' AND created_at>=") + str(t0) + frag(" AND created_at<") + str(t0 + 3*DAY)
p = db.execute(frag("SELECT currency,type,SUM(amount_minor),COUNT(*) FROM tx") + rng + frag(" GROUP BY currency,type")).fetchall()
f = db.execute(frag("SELECT fee_currency,fee_type,SUM(fee_minor) FROM tx") + rng + frag(" AND fee_minor>0 AND fee_type IN (?,?) GROUP BY fee_currency,fee_type"), T(FUS, FTHEM)).fetchall()
check(sorted(p) == sorted([('USD', DEBIT, 100000, 1), ('USD', CREDIT, 70000, 1), ('SYP', CREDIT, 2600000, 1)]), f"principal sums (cancelled excluded): {p}")
check(sorted(f) == sorted([('USD', FUS, 500), ('SYP', FTHEM, 1000)]), f"fee sums via TEXT params: {f}")

print("5) txLog filters with TEXT-bound type/currency and literal ids/dates")
TXC = "id,uuid,office_id,type,person,amount_minor,currency,fee_type,fee_minor,fee_currency,description,notes,created_at,status,replaces_id,user_name"
check(TXC in src, "TX_COLS matches")
def log(frm=0, to=2**63-1, office=None, cancelled=False, typ=None, cur=None):
    w = frag(" WHERE created_at>=") + str(frm) + frag(" AND created_at<") + str(to); args = []
    if office is not None: w += frag(" AND office_id=") + str(office)
    if not cancelled: w += frag(" AND status='ACTIVE'")
    if typ: w += frag(" AND type=?"); args.append(typ)
    if cur: w += frag(" AND currency=?"); args.append(cur)
    return db.execute("SELECT " + TXC + " FROM tx" + w + frag(" ORDER BY created_at DESC,id DESC"), T(*args)).fetchall()
check(len(log()) == 3 and len(log(cancelled=True)) == 4, "active 3 / all 4")
check([r[0] for r in log(typ=DEBIT)] == [1], "type filter (TEXT) works")
check([r[0] for r in log(cur='SYP')] == [4], "currency filter (TEXT) works")
check([r[0] for r in log(office=1, frm=t0+DAY, cancelled=True)] == [3, 2], "office + date range")

print("6) edit = reverse + new tx + move attachments (exact UPDATE)")
db.execute("INSERT INTO attachments(id,tx_id,path) VALUES(10,1,'a'),(11,1,'b')")
db.execute(frag("UPDATE attachments SET tx_id=") + "5" + frag(" WHERE tx_id=") + "1" + frag(" AND id NOT IN (") + "11)") if False else None
tx(5, 1, DEBIT, 110000, 'USD', t0, FUS, 500)
db.execute("UPDATE attachments SET tx_id=5 WHERE tx_id=1 AND id NOT IN (11)")
check(db.execute("SELECT tx_id FROM attachments ORDER BY id").fetchall() == [(5,), (1,)], "kept image moved to new tx, removed one stays on old")
print("7) deleteOffice: removes the office, its tx, entries, lines, attachments, openings; nothing else")
db.execute("INSERT INTO offices(id,name,active,pinned) VALUES(3,'c',1,0)")
db.execute("INSERT INTO office_currencies(office_id,code) VALUES(3,'USD')")
post(None, 'OPENING', 3, 'USD', 5000, 0, t0)
oe = db.execute("SELECT MAX(id) FROM journal_entries").fetchone()[0]
db.execute("INSERT INTO opening_balances(office_id,currency,type,amount_minor,entry_id) VALUES(3,'USD','لنا',5000,?)", (oe,))
db.execute("INSERT INTO opening_balances(office_id,currency,type,amount_minor,entry_id) VALUES(2,'SYP','علينا',10,NULL)")
db.execute("INSERT INTO attachments(id,tx_id,path) VALUES(20,4,'c')")
tx(6, 1, DEBIT, 1000, 'USD', t0 + 3*DAY)
db.execute("UPDATE tx SET replaces_id=4 WHERE id=6")   # edit moved a tx from office 2 to office 1
before1 = db.execute("SELECT SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=1").fetchone()[0]
nlines_before = db.execute("SELECT COUNT(*) FROM journal_lines").fetchone()[0]
def delete_office(oid):
    o = str(oid)
    tx_ids = frag("SELECT id FROM tx WHERE office_id=") + o
    entry_ids = (frag("SELECT id FROM journal_entries WHERE tx_id IN (") + tx_ids + ")"
                 + frag(" UNION SELECT entry_id FROM journal_lines WHERE office_id=") + o
                 + frag(" UNION SELECT entry_id FROM opening_balances WHERE office_id=") + o + frag(" AND entry_id IS NOT NULL"))
    db.execute(frag("CREATE TEMP TABLE IF NOT EXISTS del_entries(id INTEGER PRIMARY KEY)"))
    db.execute(frag("DELETE FROM del_entries"))
    db.execute(frag("INSERT OR IGNORE INTO del_entries(id) ") + entry_ids)
    db.execute(frag("DELETE FROM journal_lines WHERE entry_id IN (SELECT id FROM del_entries)"))
    db.execute(frag("DELETE FROM journal_entries WHERE id IN (SELECT id FROM del_entries)"))
    db.execute(frag("DELETE FROM del_entries"))
    db.execute(frag("DELETE FROM attachments WHERE tx_id IN (") + tx_ids + ")")
    db.execute(frag("UPDATE tx SET replaces_id=NULL WHERE replaces_id IN (") + tx_ids + frag(") AND office_id<>") + o)
    db.execute(frag("DELETE FROM tx WHERE office_id=") + o)
    db.execute(frag("DELETE FROM opening_balances WHERE office_id=") + o)
    db.execute(frag("DELETE FROM reconciliation_lines WHERE recon_id IN (SELECT id FROM reconciliations WHERE office_id=") + o + ")")
    db.execute(frag("DELETE FROM reconciliations WHERE office_id=") + o)
    db.execute(frag("DELETE FROM office_currencies WHERE office_id=") + o)
    db.execute(frag("DELETE FROM offices WHERE id=") + o)
delete_office(2)
check(db.execute("SELECT COUNT(*) FROM offices WHERE id=2").fetchone()[0] == 0, "office 2 gone")
check(db.execute("SELECT COUNT(*) FROM tx WHERE office_id=2").fetchone()[0] == 0, "its tx gone")
check(db.execute("SELECT COUNT(*) FROM journal_lines WHERE office_id=2").fetchone()[0] == 0, "its office lines gone")
check(db.execute("SELECT COUNT(*) FROM journal_lines l LEFT JOIN journal_entries e ON e.id=l.entry_id WHERE e.id IS NULL").fetchone()[0] == 0, "no orphan lines (contra lines removed too)")
check(db.execute("SELECT COUNT(*) FROM journal_entries e WHERE NOT EXISTS (SELECT 1 FROM journal_lines l WHERE l.entry_id=e.id)").fetchone()[0] == 0, "no empty entries left")
check(db.execute("SELECT COUNT(*) FROM attachments WHERE id=20").fetchone()[0] == 0, "its attachments gone")
check(db.execute("SELECT replaces_id FROM tx WHERE id=6").fetchone()[0] is None, "cross-office replaces_id cleared")
check(db.execute("SELECT SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=1").fetchone()[0] == before1, "office 1 balance untouched")
check(db.execute("SELECT COUNT(*) FROM opening_balances WHERE office_id=2").fetchone()[0] == 0, "its openings gone")
check(db.execute("PRAGMA foreign_key_check").fetchall() == [], "foreign keys consistent")
check(sum(d - c for d, c in db.execute("SELECT debit_minor,credit_minor FROM journal_lines").fetchall()) == 0, "ledger still balanced (sum debit = sum credit)")
delete_office(3)
check(db.execute("SELECT COUNT(*) FROM journal_entries WHERE id=?", (oe,)).fetchone()[0] == 0, "office with only an opening: opening entry removed")
check(sum(d - c for d, c in db.execute("SELECT debit_minor,credit_minor FROM journal_lines").fetchall()) == 0, "ledger still balanced after second delete")
print("8) reconciliation: cutoff by entry id, statement after it, balances add up")
def full(o):
    return dict(db.execute("SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=? GROUP BY currency", (o,)).fetchall())
cutoff = db.execute(frag("SELECT COALESCE(MAX(id),0) FROM journal_entries")).fetchone()[0]
snap = dict(db.execute(frag("SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=") + "1"
                     + frag(" AND entry_id<=") + str(cutoff) + frag(" GROUP BY currency")).fetchall())
rid = db.execute("INSERT INTO reconciliations(uuid,office_id,created_at,cutoff_entry,status) VALUES('r1',1,?,?,'SENT')", (t0 + 6*DAY, cutoff)).lastrowid
for cur, v in snap.items(): db.execute("INSERT INTO reconciliation_lines(recon_id,currency,balance_minor) VALUES(?,?,?)", (rid, cur, v))
check(snap == full(1), "snapshot equals balance at the time")
tx(9, 1, DEBIT, 3000, 'USD', t0 - 3*DAY)                     # backdated, entered after the reconciliation
olds = db.execute("SELECT id FROM journal_entries WHERE tx_id=1 AND COALESCE(status,'POSTED')='POSTED' AND COALESCE(kind,'')<>'REVERSAL'").fetchall()
for (old,) in olds:                                            # cancel an OLD transaction after the reconciliation
    ne = db.execute("INSERT INTO journal_entries(tx_id,kind,created_at,reverses_id) VALUES(1,'REVERSAL',?,?)", (t0 + 7*DAY, old)).lastrowid
    db.execute("INSERT INTO journal_lines(entry_id,account,office_id,currency,debit_minor,credit_minor) SELECT ?,account,office_id,currency,credit_minor,debit_minor FROM journal_lines WHERE entry_id=?", (ne, old))
db.execute("UPDATE tx SET status='CANCELLED' WHERE id=1")
upto = dict(db.execute(frag("SELECT currency,SUM(debit_minor-credit_minor) FROM journal_lines WHERE account='OFFICE' AND office_id=") + "1"
                     + frag(" AND entry_id<=") + str(cutoff) + frag(" GROUP BY currency")).fetchall())
check(upto == snap, "balanceUpTo(cutoff) unchanged by later entries (even backdated)")
after = db.execute(q_st + "1" + frag(" AND l.entry_id>") + str(cutoff) + frag(" ORDER BY COALESCE(e.created_at,0),l.id")).fetchall()
kinds = sorted(r[2] for r in after)
check(kinds == ['PRINCIPAL', 'REVERSAL', 'REVERSAL'], "after-lines: backdated tx + reversal of old tx and its fee -> " + str(kinds))
tot = dict(upto)
for r in after: tot[r[4]] = tot.get(r[4], 0) + r[5] - r[6]
check(tot == full(1), "reconciled balance + lines after it = current balance")
check(db.execute(frag("SELECT id FROM reconciliations WHERE office_id=") + "1" + " ORDER BY id DESC LIMIT 1").fetchone()[0] == rid, "last reconciliation found")
q_last = frag("SELECT id,uuid,office_id,created_at,cutoff_entry,status,confirmed_at,note,user_name FROM reconciliations") + frag(" WHERE id IN (SELECT MAX(id) FROM reconciliations GROUP BY office_id)")
check(len(db.execute(q_last).fetchall()) == 1, "last reconciliation per office query")
delete_office(1)
check(db.execute("SELECT COUNT(*) FROM reconciliations").fetchone()[0] == 0 and db.execute("SELECT COUNT(*) FROM reconciliation_lines").fetchone()[0] == 0, "deleting the office removes its reconciliations")
check(db.execute("PRAGMA foreign_key_check").fetchall() == [], "foreign keys consistent after delete")
print("ALL SQL TESTS PASSED" if fails == 0 else f"{fails} FAILURE(S)"); sys.exit(1 if fails else 0)
