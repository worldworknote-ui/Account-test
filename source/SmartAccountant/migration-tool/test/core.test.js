// اختبار منطق أداة النقل على بيانات نموذجية بقيم متوقعة محسوبة يدويًا.
// يكتب ملفات .sba وملف توقعات JSON يتحقق منها اختبار Java (MigrationTest) واختبار SQLite.
"use strict";
const fs = require("fs");
const path = require("path");
const XLSX = require("../vendor/xlsx.full.min.js");
const Core = require("../core.js");
const { webcrypto } = require("crypto");

const OUT = process.argv[2] || path.join(__dirname, "out");
fs.mkdirSync(OUT, { recursive: true });
let fails = 0;
function check(name, cond, extra) {
  if (cond) console.log("  PASS " + name);
  else { fails++; console.log("  FAIL " + name + (extra !== undefined ? " — " + JSON.stringify(extra, (k, v) => typeof v === "bigint" ? v.toString() : v) : "")); }
}
const rand = (n) => webcrypto.getRandomValues(new Uint8Array(n));
const serial = (v) => { const p = XLSX.SSF.parse_date_code(v); return p ? { y: p.y, m: p.m, d: p.d, H: p.H, M: p.M, S: p.S } : null; };

function readBook(wb) {
  const out = {};
  wb.SheetNames.forEach((n) => { out[n] = Core.sheetRows(wb.Sheets[n], XLSX); });
  return out;
}
function roundTrip(wb) {
  // نكتب الملف كـ xlsx ثم نقرؤه كما تفعل الأداة في المتصفح
  const buf = XLSX.write(wb, { type: "array", bookType: "xlsx" });
  return XLSX.read(new Uint8Array(buf), { type: "array", cellNF: true, cellDates: false });
}

// ---------------------------------------------------------------------------------- وحدات
console.log("== helpers");
check("amount 1,250.50", JSON.stringify(Core.parseAmount("1,250.50")).includes('"int":"1250","frac":"5"'));
check("amount (300) negative", Core.parseAmount("(300)").neg === true);
check("amount arabic digits ١٬٢٥٠٫٧٥", Core.toMinor(Core.parseAmount("١٬٢٥٠٫٧٥"), 2) === 125075n);
check("amount trailing minus 500-", Core.parseAmount("500-").neg === true);
check("amount float noise", Core.toMinor(Core.parseAmount(0.1 + 0.2), 2) === 30n);
check("amount too many decimals rejected for SYP", Core.toMinor(Core.parseAmount("10.5"), 0) === null);
check("amount 1,000,000", Core.toMinor(Core.parseAmount("1,000,000"), 0) === 1000000n);
check("amount two numbers rejected", Core.parseAmount("100 200").ok === false);
check("date dmy", JSON.stringify(Core.parseDate("03/05/2024", "dmy")).includes('"y":2024,"m":5,"d":3'));
check("date mdy", JSON.stringify(Core.parseDate("05/03/2024", "mdy")).includes('"y":2024,"m":5,"d":3'));
check("date iso with time", Core.parseDate("2024-05-03 10:15", "dmy").H === 10);
check("date invalid 31/02", Core.parseDate("31/02/2024", "dmy") === null);
check("date pm arabic", Core.parseDate("2024/05/03 02:30 م", "dmy").H === 14);
check("fmtMinor", Core.fmtMinor(125075n, 2) === "1,250.75" && Core.fmtMinor(-5n, 2) === "-0.05" && Core.fmtMinor(1250000n, 0) === "1,250,000");
check("total row regex", Core.TOTAL_RE.test("المجموع") && Core.TOTAL_RE.test("الإجمالي:") && !Core.TOTAL_RE.test("مجموعة"));
check("opening regex", Core.OPENING_RE.test("رصيد سابق") && Core.OPENING_RE.test("الرصيد المدور"));
check("currency guesses", Core.guessCurrency("دولار") === "USD" && Core.guessCurrency("ل.س") === "SYP" && Core.guessCurrency("يوز") === "USDT" && Core.guessCurrency("xyz") === null);
check("direction له/عليه", Core.guessDirection("له") === "C" && Core.guessDirection("عليه") === "D" && Core.guessDirection("مدين") === "D");

// ---------------------------------------------------------------------------------- السيناريو أ
console.log("== scenario A: balances sheet (wide) + transactions export");
const wbA = XLSX.utils.book_new();
const balAoa = [
  ["كشف أرصدة المكاتب"],
  [],
  ["المكتب", "الهاتف", "USD", "SYP", "EUR"],
  ["كاش شام", "0933123456", 1000.5, -250000, null],
  ["الأمين للصرافة", null, -300, null, 50],
  ["الامين للصرافه", null, 10, null, null],
  ["المجموع", null, 710.5, -250000, 50]
];
XLSX.utils.book_append_sheet(wbA, XLSX.utils.aoa_to_sheet(balAoa), "الأرصدة");
const txAoa = [
  ["التاريخ", "الحساب", "البيان", "مدين", "دائن", "العملة", "الأجرة", "الرصيد"],
  ["2024/05/01", "كاش شام", "حوالة لأحمد", 500, null, "دولار", 5, null],
  ["02/05/2024", "كاشْ  شام", "تسليم", null, "200.25", "USD", null, null],
  ["2024-05-03 10:15", "الأمين للصرافة", "قبض", "100", null, "$", null, null],
  ["03/05/2024", "كاش شام", "ليرات", null, "1,000,000", "ل.س", null, null],
  [null, null, null, null, null, null, null, null],
  [null, "المجموع", null, 600, "1,000,200.25", null, null, null]
];
XLSX.utils.book_append_sheet(wbA, XLSX.utils.aoa_to_sheet(txAoa), "الحركات");
const bookA = readBook(roundTrip(wbA));
const hdrBal = Core.guessHeaderRow(bookA["الأرصدة"]);
check("header row guessed for balances", hdrBal === 2, hdrBal);
const hdrTx = Core.guessHeaderRow(bookA["الحركات"]);
check("header row guessed for tx", hdrTx === 0, hdrTx);
const colsTx = Core.guessColumns(bookA["الحركات"][0]);
check("columns guessed", colsTx.date === 0 && colsTx.office === 1 && colsTx.description === 2 && colsTx.debit === 3 && colsTx.credit === 4 && colsTx.currency === 5 && colsTx.fee === 6 && colsTx.balance === 7, colsTx);

const now = new Date(2024, 9, 1, 9, 0, 0).getTime();
const currencyMap = {};
["دولار", "USD", "$", "ل.س"].forEach((t) => { currencyMap[Core.officeKey(t)] = Core.guessCurrency(t); });
const cfgA = {
  now, reverse: false, mergeSimilar: false, currencyMap, typeMap: {}, currencies: {}, sourceName: "test-A.xlsx",
  openings: { enabled: true, headerRow: hdrBal, layout: "wide", positiveIs: "D", cols: { office: 0, phone: 1 },
    wide: [{ col: 2, code: "USD" }, { col: 3, code: "SYP" }, { col: 4, code: "EUR" }], date: new Date(2024, 3, 30, 12).getTime() },
  txs: { enabled: true, headerRow: hdrTx, officeFrom: "column", layout: "split", currencyFrom: "column", dateOrder: "dmy", feeDir: "D",
    cols: colsTx, descCols: [colsTx.description], fillDate: false, balancePositiveIs: "D" }
};
const inputA = { openingSheets: [{ name: "الأرصدة", rows: bookA["الأرصدة"] }], txSheets: [{ name: "الحركات", rows: bookA["الحركات"] }] };
const mA = Core.build(cfgA, inputA, serial);
check("A no errors", mA.errors.length === 0, mA.errors);
check("A offices = 3 (no merge)", mA.offices.length === 3, mA.offices.map((o) => o.name));
check("A similar-name warning", mA.warnings.some((w) => w.includes("متشابهة")));
check("A whitespace variant merged", mA.warnings.some((w) => w.includes("دُمجت")));
check("A openings = 5", mA.openings.length === 5, mA.openings.length);
check("A tx = 4", mA.txs.length === 4, mA.txs.length);
check("A total rows skipped", mA.skipped.filter((x) => x.reason === "صف مجموع").length === 2, mA.skipped);
const TA = Core.tables(mA, cfgA, rand);
check("A all entries balanced", Core.checkBalanced(TA).length === 0);
const repA = Core.report(mA, TA, cfgA);
const byA = {};
repA.rows.forEach((r) => { byA[r.office + "|" + r.code] = r.balance; });
check("A كاش شام USD = +1305.25", byA["كاش شام|USD"] === 130525n, byA);
check("A كاش شام SYP = -1,250,000", byA["كاش شام|SYP"] === -1250000n);
check("A الأمين USD = -200", byA["الأمين للصرافة|USD"] === -20000n);
check("A الأمين EUR = +50", byA["الأمين للصرافة|EUR"] === 5000n);
check("A الامين للصرافه USD = +10 (separate office)", byA["الامين للصرافه|USD"] === 1000n, Object.keys(byA));
const usdA = repA.totals.find((t) => t.code === "USD");
check("A USD totals receivable 1315.25 / payable 200", usdA.receivable === 131525n && usdA.payable === 20000n && usdA.nr === 2 && usdA.np === 1, usdA);
check("A fee entry FEE_REVENUE", TA.journal_lines.some((l) => l.account === "FEE_REVENUE" && l.debit_minor === 0 && l.credit_minor === 500));
check("A tx type text", TA.tx[0].type === "مدين / لنا" && TA.tx[0].fee_type === "الأجرة لنا" && TA.tx[0].fee_minor === 500);
check("A tx sorted by date", TA.tx.every((t, i) => i === 0 || TA.tx[i - 1].created_at <= t.created_at));
check("A opening rows typed", TA.opening_balances.every((o) => o.type === "لنا" || o.type === "علينا") && TA.opening_balances.every((o) => o.amount_minor > 0));
check("A office phone kept", TA.offices[0].phone === "0933123456");
check("A currencies include defaults", ["USD", "EUR", "TRY", "SYP"].every((c) => TA.currencies.some((x) => x.code === c)));

// صف بمبلغ غير مفهوم يجب أن يمنع البناء
const txBad = txAoa.slice(0, 5).concat([["2024/05/04", "كاش شام", "خطأ", "abc", null, "USD", null, null]]);
const wbBad = XLSX.utils.book_new();
XLSX.utils.book_append_sheet(wbBad, XLSX.utils.aoa_to_sheet(txBad), "الحركات");
const bookBad = readBook(roundTrip(wbBad));
const mBad = Core.build(Object.assign({}, cfgA, { openings: { enabled: false } }), { txSheets: [{ name: "الحركات", rows: bookBad["الحركات"] }] }, serial);
check("bad amount is a blocking error", mBad.errors.length === 1 && mBad.errors[0].row === 6, mBad.errors);
// عملة غير مطابقة
const mBad2 = Core.build(Object.assign({}, cfgA, { openings: { enabled: false }, currencyMap: {} }), { txSheets: [{ name: "الحركات", rows: bookA["الحركات"] }] }, serial);
check("unmapped currency is an error", mBad2.errors.length === 4 && mBad2.txs.length === 0, mBad2.errors.length);
// عكس الاتجاه
const mRev = Core.build(Object.assign({}, cfgA, { reverse: true }), inputA, serial);
const TRev = Core.tables(mRev, cfgA, rand);
const repRev = Core.report(mRev, TRev, cfgA);
check("reverse flips every balance", repRev.rows.every((r) => r.balance === -byA[r.office + "|" + r.code]));
// دمج الأسماء المتشابهة
const mMerge = Core.build(Object.assign({}, cfgA, { mergeSimilar: true }), inputA, serial);
check("merge similar → 2 offices", mMerge.offices.length === 2, mMerge.offices.map((o) => o.name));

// ---------------------------------------------------------------------------------- السيناريو ب
console.log("== scenario B: Excel ledger, one sheet per office, opening row + running balance");
const wbB = XLSX.utils.book_new();
XLSX.utils.book_append_sheet(wbB, XLSX.utils.aoa_to_sheet([
  ["التاريخ", "البيان", "له", "عليه", "الرصيد"],
  [null, "رصيد سابق", null, 1000, 1000],
  ["2024/06/01", "حوالة", null, 250, 1250],
  [null, "تتمة", 50, null, 1200],
  ["2024/06/02", "استلام", 300, null, 900],
  [null, "المجموع", 350, 1250, 900]
]), "كاش شام");
XLSX.utils.book_append_sheet(wbB, XLSX.utils.aoa_to_sheet([
  ["التاريخ", "البيان", "له", "عليه", "الرصيد"],
  ["2024/06/03", "قبض", 100, null, -100]
]), "الأمين");
const bookB = readBook(roundTrip(wbB));
const colsB = Core.guessColumns(bookB["كاش شام"][0]);
check("B columns له=credit عليه=debit", colsB.credit === 2 && colsB.debit === 3 && colsB.balance === 4 && colsB.description === 1, colsB);
const cfgB = {
  now, reverse: false, mergeSimilar: false, currencyMap: {}, typeMap: {}, currencies: {}, sourceName: "ledger.xlsx",
  openings: { enabled: false },
  txs: { enabled: true, headerRow: 0, officeFrom: "sheet", layout: "split", currencyFrom: "fixed", fixedCurrency: "USD", dateOrder: "dmy",
    feeDir: "D", cols: colsB, descCols: [1], fillDate: true, balancePositiveIs: "D" }
};
const mB = Core.build(cfgB, { txSheets: ["كاش شام", "الأمين"].map((n) => ({ name: n, rows: bookB[n] })) }, serial);
check("B no errors", mB.errors.length === 0, mB.errors);
check("B one inline opening", mB.openings.length === 1 && mB.openings[0].minor === 100000n);
check("B opening dated before first tx", mB.openings[0].when < new Date(2024, 5, 1, 12).getTime());
check("B 4 transactions", mB.txs.length === 4, mB.txs.length);
check("B filled date used for تتمة", mB.txs.some((t) => t.desc === "تتمة" && t.when === new Date(2024, 5, 1, 12).getTime()));
const TB = Core.tables(mB, cfgB, rand);
const repB = Core.report(mB, TB, cfgB);
const rB = {};
repB.rows.forEach((r) => { rB[r.office] = r; });
check("B كاش شام = +900 and matches balance column", rB["كاش شام"].balance === 90000n && rB["كاش شام"].match === true, rB["كاش شام"]);
check("B الأمين = -100 and matches", rB["الأمين"].balance === -10000n && rB["الأمين"].match === true, rB["الأمين"]);
check("B balanced", Core.checkBalanced(TB).length === 0);
// تعمّد قلب إشارة عمود الرصيد يجب أن يظهر عدم مطابقة
const mB2 = Core.build(Object.assign({}, cfgB, { txs: Object.assign({}, cfgB.txs, { balancePositiveIs: "C" }) }), { txSheets: ["كاش شام"].map((n) => ({ name: n, rows: bookB[n] })) }, serial);
const rep2 = Core.report(mB2, Core.tables(mB2, cfgB, rand), cfgB);
check("B wrong balance sign → mismatch shown", rep2.rows[0].match === false);

// ---------------------------------------------------------------------------------- CSV بترميز عربي
console.log("== scenario C: CSV with type column");
const csv = "التاريخ,المكتب,النوع,المبلغ,العملة,المستفيد\n2024/07/01,النور,لنا,150,USD,سامر\n2024/07/02,النور,علينا,40,USD,\n2024/07/02,النور,تحويل داخلي,10,USD,\n";
const wbC = XLSX.read(csv, { type: "string", raw: true });
const rowsC = Core.sheetRows(wbC.Sheets[wbC.SheetNames[0]], XLSX);
const colsC = Core.guessColumns(rowsC[0]);
check("C columns", colsC.type === 2 && colsC.amount === 3 && colsC.person === 5, colsC);
const typeMap = {};
typeMap[Core.officeKey("لنا")] = "D";
typeMap[Core.officeKey("علينا")] = "C";
typeMap[Core.officeKey("تحويل داخلي")] = "X";
const cfgC = { now, currencyMap: { usd: "USD" }, typeMap, currencies: {}, openings: { enabled: false },
  txs: { enabled: true, headerRow: 0, officeFrom: "column", layout: "typed", currencyFrom: "column", dateOrder: "dmy", cols: colsC, descCols: [] } };
const mC = Core.build(cfgC, { txSheets: [{ name: "csv", rows: rowsC }] }, serial);
check("C no errors", mC.errors.length === 0, mC.errors);
check("C 2 tx + 1 excluded", mC.txs.length === 2 && mC.skipped.some((x) => x.reason.indexOf("مستبعد") >= 0));
const TC = Core.tables(mC, cfgC, rand);
check("C balance +110", Core.report(mC, TC, cfgC).rows[0].balance === 11000n);
check("C person kept", TC.tx[0].person === "سامر");

// ---------------------------------------------------------------------------------- كشوفات حساب متعددة
console.log("== scenario D: many statement files (one account + currency each)");
const SS = require("./stmt-samples.js");
function stmtFrom(name, wb) {
  const rt = roundTrip(wb);
  const book = readBook(rt);
  return Core.parseStatements(name, rt.SheetNames.map((n) => ({ name: n, rows: book[n] })));
}
let stD = [];
Object.keys(SS.SPECS).forEach((f) => { stD = stD.concat(stmtFrom(f, SS.statementBook(XLSX, SS.SPECS[f]))); });
check("D 4 statements read", stD.length === 4, stD.length);
const khal = stD[0];
check("D account split into code + name", khal.account.code === "1410100092" && khal.account.name === "الخال", khal.account);
check("D currency text read", khal.curText === "دولار امريكي");
check("D period read", khal.from && khal.from.m === 8 && khal.to && khal.to.d === 13);
check("D repeated page headers skipped", khal.rows.length === 3, khal.rows.length);
check("D Pre Balance kept apart", khal.pre && Core.text({ s: khal.pre.desc }) === "Pre Balance");
check("D footer read with side", khal.footer && khal.footer.side === "D");
check("D two-sheet statement read as one", stD[3].rows.length === 3 && stD[3].footer && stD[3].footer.side === "C", stD[3].rows.length);
const cfgD = { now: new Date(2026, 9, 14, 9, 0, 0).getTime(), currencyMap: {}, currencies: {}, rename: {}, statements: {} };
const mD = Core.buildStatements(cfgD, stD, serial);
check("D no errors", mD.errors.length === 0, mD.errors);
check("D 3 offices (two currencies of الخال merged by code)", mD.offices.length === 3, mD.offices.map((o) => o.name));
check("D office note keeps old account code", mD.offices[0].notes.indexOf("1410100092") >= 0);
check("D 8 movements + 2 openings", mD.txs.length === 8 && mD.openings.length === 2, [mD.txs.length, mD.openings.length]);
check("D opening dated just before the period", mD.openings[0].when === new Date(2026, 7, 1, 0, 0, 0).getTime() - 60000);
check("D every balance cell checked", mD.files.every((f) => f.ok) && mD.files[0].checked === 3, mD.files);
check("D balance-column direction detected per file (النور: مدين − دائن)", mD.files[2].ok && mD.files[2].final === 35000n, mD.files[2]);
const TD = Core.tables(mD, cfgD, rand);
const repD = Core.report(mD, TD, cfgD);
const rD = {};
repD.rows.forEach((r) => { rD[r.office + "|" + r.code] = r; });
check("D الخال USD = لنا 495 and matches footer", rD["الخال|USD"].balance === 49500n && rD["الخال|USD"].match === true, rD["الخال|USD"]);
check("D الخال EUR = علينا 100", rD["الخال|EUR"].balance === -10000n && rD["الخال|EUR"].match === true);
check("D النور USD = لنا 350", rD["النور|USD"].balance === 35000n && rD["النور|USD"].match === true);
check("D الأمل TRY = علينا 1,010.00", rD["الأمل|TRY"].balance === -101000n && rD["الأمل|TRY"].match === true);
check("D balanced", Core.checkBalanced(TD).length === 0);
// دمج سطور العمولة: الأرصدة نفسها
const mDc = Core.buildStatements(Object.assign({}, cfgD, { statements: { mergeCom: true } }), stD, serial);
const TDc = Core.tables(mDc, cfgD, rand);
const fee = TDc.tx.filter((t) => t.fee_minor > 0);
check("D Com row merged as fee (5.00, علينا)", fee.length === 1 && fee[0].fee_minor === 500 && fee[0].fee_type === "الأجرة علينا" && TDc.tx.length === TD.tx.length - 1, fee);
const bal1 = Core.balancesFromTables(TD), bal2 = Core.balancesFromTables(TDc);
check("D same balances with Com merged", Object.keys(bal1).every((k) => bal1[k] === bal2[k]) && Object.keys(bal1).length === Object.keys(bal2).length);
// الأرصدة فقط
const mDb = Core.buildStatements(Object.assign({}, cfgD, { statements: { balancesOnly: true } }), stD, serial);
const TDb = Core.tables(mDb, cfgD, rand);
const bal3 = Core.balancesFromTables(TDb);
check("D balances-only: no tx, same final balances", TDb.tx.length === 0 && Object.keys(bal1).every((k) => bal1[k] === bal3[k]), bal3);
check("D balances-only dated at period end", mDb.openings.every((o) => o.when === new Date(2026, 9, 13, 23, 0, 0).getTime()));
// كشف معدّل، ومكرر، وعملة غير معروفة
const bad = stmtFrom("fajr.xlsx", SS.statementBook(XLSX, SS.TAMPERED));
const dup = stmtFrom("khal-usd-copy.xlsx", SS.statementBook(XLSX, SS.SPECS["khal-usd.xlsx"]));
const gold = stmtFrom("gold.xlsx", SS.statementBook(XLSX, Object.assign({}, SS.SPECS["khal-eur.xlsx"], { currency: "ذهب", account: "1410100096 الذهبي" })));
const mE = Core.buildStatements(cfgD, stD.concat(bad, dup, gold), serial);
check("D tampered statement rejected whole with row number", mE.errors.some((e) => e.sheet === "fajr.xlsx" && e.row === 11 && e.msg.indexOf("لا يساوي") >= 0) && !mE.offices.some((o) => o.name === "الفجر"), mE.errors);
check("D duplicate account+currency rejected", mE.errors.some((e) => e.sheet === "khal-usd-copy.xlsx" && e.msg.indexOf("مكرر") >= 0), mE.errors);
check("D unknown currency asks for mapping", mE.errors.some((e) => e.msg.indexOf("«ذهب»") >= 0));
check("D good files unaffected by bad ones", Core.report(mE, Core.tables(mE, cfgD, rand), cfgD).rows.filter((r) => r.match === true).length === 4);
const cfgG = Object.assign({}, cfgD, { currencyMap: { [Core.officeKey("ذهب")]: "GOLD" }, currencies: { GOLD: { name: "ذهب", dec: 3 } } });
const mG = Core.buildStatements(cfgG, gold, serial);
check("D mapped unknown currency (GOLD, 3 decimals)", mG.errors.length === 0 && Core.report(mG, Core.tables(mG, cfgG, rand), cfgG).rows[0].balance === -100000n, mG.errors);
// تكرار «رصيد منقول» في رأس صفحة: للتحقق فقط
const carryBook = SS.statementBook(XLSX, SS.SPECS["khal-eur.xlsx"]);
const carryRows = readBook(roundTrip(carryBook))["Page 1"];
const hi = carryRows.findIndex((r) => r && r[4] && r[4].s === "حوالة");
carryRows.splice(hi + 1, 0, carryRows[hi].map((c, i) => (i === 4 ? { s: "رصيد منقول" } : i === 14 ? { s: "9,999.00" } : i === 20 ? { s: "1.00" } : c)));
const carry = Core.parseStatements("carry.xlsx", [{ name: "Page 1", rows: carryRows }]);
const mCarry = Core.buildStatements(cfgD, carry, serial);
check("D carried-forward line only checks the balance", mCarry.errors.length === 0 && mCarry.txs.length === 1 && mCarry.files[0].checked === 2, [mCarry.errors, mCarry.txs.length]);

// ---------------------------------------------------------------------------------- الملفات المشفرة
(async () => {
  console.log("== backup files");
  const files = [
    ["A", TA, cfgA, "pass-A-1234", { usd: [["كاش شام", "USD", 130525], ["كاش شام", "SYP", -1250000], ["الأمين للصرافة", "USD", -20000], ["الأمين للصرافة", "EUR", 5000], ["الامين للصرافه", "USD", 1000]] }],
    ["B", TB, cfgB, "كلمة سر عربية ١٢٣", { usd: [["كاش شام", "USD", 90000], ["الأمين", "USD", -10000]] }],
    ["C", TC, cfgC, "x", { usd: [["النور", "USD", 11000]] }],
    ["D", TD, cfgD, "كشوفات-2026", { usd: [["الخال", "USD", 49500], ["الخال", "EUR", -10000], ["النور", "USD", 35000], ["الأمل", "TRY", -101000]] }]
  ];
  const expect = {};
  for (const [name, T, cfg, pw, exp] of files) {
    const bytes = await Core.makeBackup(T, cfg, pw, webcrypto.subtle, rand, name === "C" ? { chunk: 1024 } : undefined);
    fs.writeFileSync(path.join(OUT, name + ".sba"), bytes);
    expect[name] = { password: pw, offices: T.offices.length, tx: T.tx.length, openings: T.opening_balances.length,
      entries: T.journal_entries.length, lines: T.journal_lines.length, balances: exp.usd };
    check(name + ".sba written (" + bytes.length + " bytes)", bytes.length > 100 && Buffer.from(bytes.slice(0, 8)).toString() === "SMARTBK5");
  }
  fs.writeFileSync(path.join(OUT, "expect.json"), JSON.stringify(expect, null, 1));
  console.log(fails === 0 ? "ALL MIGRATION CORE TESTS PASSED" : fails + " FAILED");
  process.exit(fails === 0 ? 0 : 1);
})();
