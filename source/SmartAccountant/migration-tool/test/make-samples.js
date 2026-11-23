// ينشئ ملفات Excel نموذجية لاختبار الأداة في المتصفح
const XLSX = require("../vendor/xlsx.full.min.js");
const out = process.argv[2];
const fs = require("fs");
const save = (wb, f) => fs.writeFileSync(out + "/" + f, Buffer.from(XLSX.write(wb, { type: "array", bookType: "xlsx" })));
const wbA = XLSX.utils.book_new();
XLSX.utils.book_append_sheet(wbA, XLSX.utils.aoa_to_sheet([
  ["كشف أرصدة المكاتب"], [],
  ["المكتب", "الهاتف", "USD", "SYP", "EUR"],
  ["كاش شام", "0933123456", 1000.5, -250000, null],
  ["الأمين للصرافة", null, -300, null, 50],
  ["الامين للصرافه", null, 10, null, null],
  ["المجموع", null, 710.5, -250000, 50]
]), "الأرصدة");
XLSX.utils.book_append_sheet(wbA, XLSX.utils.aoa_to_sheet([
  ["التاريخ", "الحساب", "البيان", "مدين", "دائن", "العملة", "الأجرة", "الرصيد"],
  ["2024/05/01", "كاش شام", "حوالة لأحمد", 500, null, "دولار", 5, null],
  ["02/05/2024", "كاشْ  شام", "تسليم", null, "200.25", "USD", null, null],
  ["2024-05-03 10:15", "الأمين للصرافة", "قبض", "100", null, "$", null, null],
  ["03/05/2024", "كاش شام", "ليرات", null, "1,000,000", "ل.س", null, null],
  [null, null, null, null, null, null, null, null],
  [null, "المجموع", null, 600, "1,000,200.25", null, null, null]
]), "الحركات");
save(wbA, "sample-A.xlsx");
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
save(wbB, "sample-B-ledger.xlsx");
console.log("ok");
// كشوفات حساب: ملف لكل حساب وعملة، وكشف معدّل يدويًا
const SS = require("./stmt-samples.js");
fs.mkdirSync(out + "/statements", { recursive: true });
Object.keys(SS.SPECS).forEach((f) => save(SS.statementBook(XLSX, SS.SPECS[f]), "statements/" + f));
save(SS.statementBook(XLSX, SS.TAMPERED), "statements-bad-fajr.xlsx");
console.log("statements ok");
