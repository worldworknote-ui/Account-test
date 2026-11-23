// اختبار «الاستيراد الذكي» على ملفات نموذجية بأشكال مختلفة، بأرصدة محسوبة يدويًا.
// node smart.test.js <مجلد الإخراج>  ← يكتب ملفات ‎.sba و expect.json ليفحصها MigrationTest (Java) بكود التطبيق نفسه.
"use strict";
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const { webcrypto } = require("crypto");
const XLSX = require("../../migration-tool/vendor/xlsx.full.min.js");
const C = require("../../migration-tool/core.js");
const X = require("../engine.js");

const OUT = process.argv[2] || path.join(__dirname, "out");
const SAMPLES = path.join(OUT, "samples");
fs.mkdirSync(SAMPLES, { recursive: true });
execFileSync("node", [path.join(__dirname, "make-samples.js"), SAMPLES]);
execFileSync("node", [path.join(__dirname, "..", "..", "migration-tool", "test", "make-samples.js"), SAMPLES]);

let fails = 0;
function check(name, ok, extra) {
  console.log((ok ? "  PASS " : "  FAIL ") + name + (ok || extra === undefined ? "" : " — " + JSON.stringify(extra, (k, v) => (typeof v === "bigint" ? v.toString() : v))));
  if (!ok) fails++;
}
const NOW = new Date(2026, 9, 13, 20, 0).getTime();
const rand = (n) => webcrypto.getRandomValues(new Uint8Array(n));
const serial = (v) => { const p = XLSX.SSF.parse_date_code(v); return p ? { y: p.y, m: p.m, d: p.d, H: p.H, M: p.M, S: p.S } : null; };

/** يقرأ الملفات كما تقرؤها الواجهة تمامًا. */
function load(names) {
  return names.map((n) => {
    const buf = fs.readFileSync(path.join(SAMPLES, n));
    let wb;
    if (/\.(csv|txt|tsv)$/i.test(n)) {
      const txt = new TextDecoder("utf-8").decode(buf).replace(/^﻿/, "");
      const first = txt.split("\n")[0];
      const semi = (first.match(/;/g) || []).length > (first.match(/,/g) || []).length;
      wb = XLSX.read(txt, { type: "string", raw: true, FS: semi ? ";" : undefined });
    } else wb = XLSX.read(buf, { type: "buffer", cellNF: true, cellDates: false, cellFormula: true, bookFiles: true });
    return { name: n, wb, sheets: wb.SheetNames.map((s) => ({ name: s, rows: X.sheetRows(wb.Sheets[s], XLSX) })) };
  });
}

/** نفس خطوات الواجهة: تحليل، إعداد افتراضي، اكتشاف الملخصات واتجاه الأجرة، مطابقة القيم وتعريف العملات. */
function prepare(books) {
  const a = X.analyze(books);
  const cfg = { now: NOW, dirMap: {}, curMap: {}, currencies: {}, rename: {}, positiveIs: {}, balanceSign: {}, fixedCurrency: {}, defaultCurrency: "USD",
    listedAccounts: true, inferOpening: true, openingDate: null };
  a.tables.forEach((t) => { if (t.sheetCurrency) cfg.fixedCurrency[t.id] = t.sheetCurrency; });
  const fchecks = X.formulaChecks(books, a);
  X.detectSummaries(a, cfg, serial);
  X.detectFeeDirs(a, cfg, serial);
  values(a, cfg);
  return { a, cfg, fchecks };
}
function values(a, cfg) {
  a.values = X.collectValues(a);
  Object.keys(a.values.dirs).forEach((k) => { if (!(k in cfg.dirMap)) cfg.dirMap[k] = a.values.dirs[k].guess || ""; });
  Object.keys(a.values.curs).forEach((k) => { if (!(k in cfg.curMap)) cfg.curMap[k] = a.values.curs[k].guess || ""; });
  const need = X.suggestDecimals(a, cfg);
  const codes = new Set();
  Object.keys(a.values.curs).forEach((k) => { const c = cfg.curMap[k]; if (c && c !== "__skip") codes.add(c); });
  a.tables.forEach((t) => { if (t.use && t.roles.currency === undefined && !t.roles.wide) codes.add(cfg.fixedCurrency[t.id] || t.sheetCurrency || cfg.defaultCurrency); });
  codes.forEach((c) => {
    const k = C.KNOWN[c];
    if (!cfg.currencies[c]) cfg.currencies[c] = { name: k ? k.name : c, dec: Math.max(k ? k.dec : 2, need[c] || 0) };
  });
}

function run(p) {
  const model = X.build(p.a, p.cfg, serial);
  const tc = { now: NOW, currencies: p.cfg.currencies, defaultCurrency: "USD", userName: "استيراد ذكي", sourceName: "اختبار" };
  const T = C.tables(model, tc, rand);
  const rep = C.report(model, T, tc);
  const fromT = C.balancesFromTables(T);
  let internal = 0;
  model.offices.forEach((o, i) => T.office_currencies.forEach((oc) => {
    if (oc.office_id === i + 1 && (fromT[(i + 1) + "|" + oc.code] || 0n) !== (model.direct[o.key + "|" + oc.code] || 0n)) internal++;
  }));
  const bal = {};
  rep.rows.forEach((r) => { if (r.balance !== 0n) bal[r.office + "|" + r.code] = r.balance; });
  return { model, T, rep, tc, bal, internal, unbalanced: C.checkBalanced(T).length, derived: X.derivedChecks(p.a, model, p.cfg, serial) };
}

/** الأرصدة غير الصفرية يجب أن تساوي المتوقع تمامًا: لا زيادة ولا نقص. */
function expectBalances(label, r, exp) {
  const want = {};
  exp.forEach(([o, c, v]) => { if (v !== 0) want[o + "|" + c] = BigInt(v); });
  const keys = new Set(Object.keys(want).concat(Object.keys(r.bal)));
  const bad = [];
  keys.forEach((k) => { if (want[k] !== r.bal[k]) bad.push(k + ": want " + want[k] + " got " + r.bal[k]); });
  check(label + ": balances exactly as computed by hand (" + Object.keys(want).length + ")", bad.length === 0, bad);
  check(label + ": every entry balanced, ledger = rows", r.unbalanced === 0 && r.internal === 0, [r.unbalanced, r.internal]);
}
const kind = (p, label) => (p.a.tables.find((t) => t.label === label) || {}).kind;
const roleName = (t, role) => (t.roles[role] === undefined ? null : t.names[t.roles[role] - t.c0]);
const issues = (r) => Object.fromEntries(Object.values(r.model.issues).map((g) => [g.code, g.total]));

const files = [];

// ------------------------------------------------------------------------------------------------ S1
console.log("== S1: journal with direction column, filtered copy, names list, formulas");
{
  const p = prepare(load(["S1-journal.xlsx"]));
  const tj = p.a.tables.find((t) => t.label === "الحركات");
  check("journal detected on «الحركات»", tj && tj.kind === "journal" && tj.use, tj && tj.kind);
  check("roles from content", tj && roleName(tj, "date") === "التاريخ" && roleName(tj, "currency") === "العملة" && roleName(tj, "amount") === "المبلغ" &&
    roleName(tj, "direction") === "النوع" && roleName(tj, "account") === "اسم الحساب", tj && tj.roles);
  check("«كشف النور» is a filtered copy (not imported)", kind(p, "كشف النور") === "derived");
  check("names list found (5 names)", p.a.lists.length === 1 && p.a.lists[0].values.length === 5, p.a.lists.map((l) => l.values));
  check("currency «دولار» mapped to USD", p.cfg.curMap[X.key("دولار")] === "USD");
  check("«لكم» mapped to credit", p.cfg.dirMap[X.key("لكم")] === "C");
  const r = run(p);
  expectBalances("S1", r, [["مكتب النور", "USD", 185050], ["مكتب النور", "TRY", -500000], ["الأمين", "EUR", 25050], ["الأمين", "USD", -10000], ["الامين", "USD", -20000]]);
  check("7 transactions, 5 offices (2 from the names list only)", r.T.tx.length === 7 && r.T.offices.length === 5, [r.T.tx.length, r.T.offices.length]);
  const is = issues(r);
  check("issues: no direction, no currency, no account, future date (excluded); missing date filled", is.noDir === 1 && is.noCurrency === 1 && is.noAccount === 1 && is.future === 1 && is.filledDate === 1, is);
  check("repeated header row ignored, one empty row", r.model.emptyRows === 1, r.model.emptyRows);
  check("formula checks: 2 SUMIFS recomputed and equal", p.fchecks.length === 2 && p.fchecks.every((x) => x.ok), p.fchecks);
  check("filtered copy matches after import (USD + TRY)", r.derived.length === 2 && r.derived.every((x) => x.ok), r.derived);
  check("similar names warning (الأمين / الامين)", r.model.warnings.some((w) => w.indexOf("متشابهة") >= 0), r.model.warnings);
  const tx = r.T.tx.find((x) => x.amount_minor === 125050);
  check("text amount «1,250.50» read exactly; notes keep file row", tx && /صف 12/.test(tx.notes) && /رقم القيد في الملف: 10/.test(tx.notes), tx && tx.notes);
  files.push(["S1", r, "s1-pass", [["مكتب النور", "USD", 185050], ["مكتب النور", "TRY", -500000], ["الأمين", "EUR", 25050], ["الأمين", "USD", -10000], ["الامين", "USD", -20000]]]);
  // تغيير المطابقة: استبعاد «لكم» يُخرج صفه ويُظهره في الصفوف المستبعدة
  p.cfg.dirMap[X.key("لكم")] = "X";
  const r2 = run(p);
  check("excluding a direction value removes exactly its rows", !r2.bal["الامين|USD"] && issues(r2).excludedDir === 1, issues(r2));
}

// ------------------------------------------------------------------------------------------------ S2
console.log("== S2: a sheet per office with running balance");
{
  const p = prepare(load(["S2-ledgers.xlsx"]));
  const tn = p.a.tables.find((t) => t.label === "مكتب النور USD"), ta = p.a.tables.find((t) => t.label === "الأمين"), t3 = p.a.tables.find((t) => t.label === "Sheet3");
  check("account and currency from sheet name «مكتب النور USD»", tn && tn.sheetAccount === "مكتب النور" && tn.sheetCurrency === "USD", tn && [tn.sheetAccount, tn.sheetCurrency]);
  check("debit/credit/balance columns", tn && roleName(tn, "debit") === "لنا" && roleName(tn, "credit") === "علينا" && roleName(tn, "balance") === "الرصيد", tn && tn.roles);
  check("«الأمين»: مدين/دائن", ta && roleName(ta, "debit") === "مدين" && roleName(ta, "credit") === "دائن", ta && ta.roles);
  check("generic «Sheet3» without account is not imported", t3 && t3.kind === "journal-noaccount" && !t3.use, t3 && t3.kind);
  const r = run(p);
  expectBalances("S2", r, [["مكتب النور", "USD", 122550], ["الأمين", "USD", -160000]]);
  const bn = r.model.balanceChecks.find((b) => b.table === "مكتب النور USD"), ba = r.model.balanceChecks.find((b) => b.table === "الأمين");
  check("running balance: positive = لنا detected, 4 rows equal", bn && bn.sign === "D" && bn.ok === 4 && bn.badN === 0, bn);
  check("running balance: positive = علينا detected on «الأمين», 3 rows equal", ba && ba.sign === "C" && ba.ok === 3 && ba.badN === 0, ba);
  check("carried balance 500 added as an opening", r.model.impliedOpenings.length === 1 && r.model.impliedOpenings[0].minor === 50000n, r.model.impliedOpenings);
  check("totals row skipped", r.model.totalsSkipped === 1, r.model.totalsSkipped);
  check("«in your file» column matches for both", r.rep.rows.filter((x) => x.match === true).length === 2 && !r.rep.rows.some((x) => x.match === false), r.rep.rows.map((x) => [x.office, x.match]));
  files.push(["S2", r, "s2-pass", [["مكتب النور", "USD", 122550], ["الأمين", "USD", -160000]]]);
  // خطأ في الملف: رصيد مكتوب خطأ يجب أن يظهر في الصف نفسه
  const rows = tn.rows;
  const saved = rows[3][4];
  rows[3][4] = { n: 1300 };
  const rb = run(p);
  const bb = rb.model.balanceChecks.find((b) => b.table === "مكتب النور USD");
  check("a wrong balance cell is reported at its row", bb && bb.badN >= 1 && bb.bad[0].row === 4, bb && bb.bad);
  rows[3][4] = saved;
  // اختيار اسم حساب لورقة عامة
  t3.sheetAccount = "الصندوق"; t3.accountFrom = "custom"; t3.kind = "journal"; t3.use = true;
  const r3 = run(p);
  check("generic sheet imported once an account name is chosen (signed: +100 −40 +15)", r3.bal["الصندوق|USD"] === 7500n, r3.bal);
}

// ------------------------------------------------------------------------------------------------ S3
console.log("== S3: signed amounts");
{
  const p = prepare(load(["S3-signed.xlsx"]));
  const t = p.a.tables[0];
  check("signed journal (no direction column)", t.kind === "journal" && t.signed, [t.kind, t.signed]);
  const r = run(p);
  expectBalances("S3", r, [["الخال", "USD", 45000], ["النور", "EUR", 100000], ["الخال", "TRY", 300000]]);
  files.push(["S3", r, "s3-pass", [["الخال", "USD", 45000], ["النور", "EUR", 100000], ["الخال", "TRY", 300000]]]);
  p.cfg.positiveIs[t.id] = "C";
  expectBalances("S3 positive = علينا", run(p), [["الخال", "USD", -45000], ["النور", "EUR", -100000], ["الخال", "TRY", -300000]]);
}

// ------------------------------------------------------------------------------------------------ S4
console.log("== S4: wide balances table (a column per currency)");
{
  const p = prepare(load(["S4-balances.xlsx"]));
  const t = p.a.tables[0];
  check("balances table with currency columns USD/EUR/TRY", t.kind === "balances" && t.roles.wide && t.roles.wide.map((w) => w.code).join() === "USD,EUR,TRY", [t.kind, t.roles.wide]);
  check("phone column recognised", roleName(t, "phone") === "الهاتف", t.roles);
  const r = run(p);
  expectBalances("S4", r, [["النور", "USD", 150000], ["النور", "EUR", -20000], ["الخال", "USD", -300050], ["الخال", "TRY", 4500000], ["الأمل", "EUR", 9999]]);
  check("5 opening balances, totals row skipped", r.T.opening_balances.length === 5 && r.model.totalsSkipped === 1, [r.T.opening_balances.length, r.model.totalsSkipped]);
  check("phones kept on offices", r.T.offices.find((o) => o.name === "النور").phone === "0933111222" && r.T.offices.find((o) => o.name === "الأمل").phone === "+963944555666");
  files.push(["S4", r, "s4-pass", [["النور", "USD", 150000], ["النور", "EUR", -20000], ["الخال", "USD", -300050], ["الخال", "TRY", 4500000], ["الأمل", "EUR", 9999]]]);
}

// ------------------------------------------------------------------------------------------------ S5
console.log("== S5: journal + summaries (formulas, and pasted values with reversed sign)");
{
  const p = prepare(load(["S5-summary.xlsx"]));
  check("Summary (formulas) recognised as a summary, not imported", kind(p, "Summary") === "summary" && !p.a.tables.find((t) => t.label === "Summary").use);
  check("Values (no formulas) recognised as a summary by matching the journal", kind(p, "Values") === "summary");
  const r = run(p);
  expectBalances("S5", r, [["Delta", "USD", 6500], ["Gamma", "USD", -40000], ["Omega", "USD", 7500]]);
  const sc = r.model.summaryChecks;
  check("summary checks: 3/3 (positive = لنا) and 3/3 (positive = علينا)", sc.length === 2 && sc.every((s) => s.ok === 3 && s.n === 3) && sc.map((s) => s.sign).sort().join() === "C,D", sc.map((s) => [s.table, s.sign, s.ok, s.n]));
  check("SUMIFS check cell recomputed (compound formulas skipped)", p.fchecks.length === 1 && p.fchecks[0].ok && p.fchecks[0].computed === 300.25, p.fchecks);
  check("report shows «in your file» = match for all", r.rep.rows.filter((x) => x.match === true).length === 3, r.rep.rows.map((x) => [x.office, x.expected, x.match]));
  files.push(["S5", r, "s5-pass", [["Delta", "USD", 6500], ["Gamma", "USD", -40000], ["Omega", "USD", 7500]]]);
}

// ------------------------------------------------------------------------------------------------ S6
console.log("== S6: CSV (semicolons, European numbers, text dates)");
{
  const p = prepare(load(["S6-journal.csv"]));
  const t = p.a.tables[0];
  check("CSV journal with لنا/علينا columns", t && t.kind === "journal" && roleName(t, "debit") === "لنا" && roleName(t, "credit") === "علينا", t && [t.kind, t.roles]);
  const r = run(p);
  expectBalances("S6", r, [["مكتب الشام", "USD", 95550], ["مكتب حلب", "EUR", -80000]]);
  const d0 = new Date(r.T.tx[0].created_at);
  check("text date 15/06/2026 read as 15 June", d0.getDate() === 15 && d0.getMonth() === 5 && d0.getFullYear() === 2026, d0.toISOString());
  files.push(["S6", r, "s6-pass", [["مكتب الشام", "USD", 95550], ["مكتب حلب", "EUR", -80000]]]);
}

// ------------------------------------------------------------------------------------------------ S7
console.log("== S7: a file per office (generic sheet names) with fee + balance columns");
{
  const p = prepare(load(["الربيع.xlsx", "السلام.xlsx"]));
  const t = p.a.tables[0];
  check("account from file name", p.a.tables.map((x) => x.sheetAccount).join() === "الربيع,السلام" && p.a.tables.every((x) => x.kind === "journal" && x.accountFrom === "file"), p.a.tables.map((x) => [x.kind, x.sheetAccount]));
  check("fee column found and its direction (لنا) learnt from the balance column", roleName(t, "fee") === "الأجرة" && t.feeDetected === "D" && p.a.tables[1].feeDetected === "D", [t.roles, t.feeDetected]);
  const r = run(p);
  expectBalances("S7", r, [["الربيع", "USD", 71500], ["السلام", "USD", -19700]]);
  check("fees recorded on the transactions (FEE entries)", r.T.tx.filter((x) => x.fee_minor > 0).length === 3 && r.T.journal_entries.filter((e) => e.kind === "FEE").length === 3);
  check("running balances all equal", r.model.balanceChecks.every((b) => b.badN === 0 && b.ok === b.rows && b.rows > 0), r.model.balanceChecks);
  files.push(["S7", r, "s7-pass", [["الربيع", "USD", 71500], ["السلام", "USD", -19700]]]);
  p.cfg.feeDir[t.id] = "X";
  const rx = run(p);
  const bx = rx.model.balanceChecks[0];
  check("fee «not counted»: balance without fee; the balance column (sign of the other file) shows the mismatch, no opening is invented",
    rx.bal["الربيع|USD"] === 70000n && bx.how === "book" && bx.badN === 3 && bx.implied === 0, [rx.bal, bx]);
}

// ------------------------------------------------------------------------------------------------ أداة النقل: نفس النتيجة على ملفاتها
console.log("== migration-tool samples A and B give the same balances as the migration tool");
{
  const pA = prepare(load(["sample-A.xlsx"]));
  const rA = run(pA);
  const expA = [["كاش شام", "USD", 130525], ["كاش شام", "SYP", -1250000], ["الأمين للصرافة", "USD", -20000], ["الأمين للصرافة", "EUR", 5000], ["الامين للصرافه", "USD", 1000]];
  expectBalances("sample-A", rA, expA);
  check("sample-A: phone from balances table", rA.T.offices.find((o) => o.name === "كاش شام").phone === "0933123456");
  files.push(["MA", rA, "pass-A-1234", expA]);
  const pB = prepare(load(["sample-B-ledger.xlsx"]));
  const rB = run(pB);
  expectBalances("sample-B", rB, [["كاش شام", "USD", 90000], ["الأمين", "USD", -10000]]);
  const one = rB.model.balanceChecks.find((b) => b.table === "الأمين");
  check("single-row sheet takes the sign of the other sheets and matches", one && one.how === "book" && one.ok === 1, one);
  files.push(["MB", rB, "pass-B", [["كاش شام", "USD", 90000], ["الأمين", "USD", -10000]]]);
}

// ------------------------------------------------------------------------------------------------ ملفات ‎.sba
(async () => {
  console.log("== backup files for the app (MigrationTest)");
  const expect = {};
  for (const [name, r, pw, bal] of files) {
    const bytes = await C.makeBackup(r.T, r.tc, pw, webcrypto.subtle, rand);
    fs.writeFileSync(path.join(OUT, name + ".sba"), bytes);
    expect[name] = { password: pw, offices: r.T.offices.length, tx: r.T.tx.length, openings: r.T.opening_balances.length,
      entries: r.T.journal_entries.length, lines: r.T.journal_lines.length, balances: bal };
    check(name + ".sba written", Buffer.from(bytes.slice(0, 8)).toString() === "SMARTBK5");
  }
  fs.writeFileSync(path.join(OUT, "expect.json"), JSON.stringify(expect, null, 1));
  console.log(fails === 0 ? "ALL SMART IMPORT TESTS PASSED" : fails + " FAILED");
  process.exit(fails === 0 ? 0 : 1);
})();
