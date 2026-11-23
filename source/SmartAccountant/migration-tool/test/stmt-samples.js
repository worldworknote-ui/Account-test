// كشوفات حساب نموذجية بالشكل نفسه الذي يصدّره البرنامج القديم: ملف لكل حساب وعملة،
// أعمدة متباعدة (E, K, O, U, AA)، «Pre Balance»، صف عناوين يتكرر كل صفحة، وسطر «اجمالي الرصيد».
"use strict";
function fmt(n) {
  const neg = n < 0;
  const s = Math.abs(n).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return (neg ? "-" : "") + s;
}
function row(cells) {
  const r = new Array(30).fill(null);
  Object.keys(cells).forEach((k) => { r[+k] = cells[k]; });
  return r;
}
const HEAD = row({ 4: "البيان", 10: "الرصيد", 14: "دائن / علينا", 20: "مدين / لنا", 26: "تاريخ الحركة" });
/**
 * spec: { account, currency, from, to, pre: {bal, credit, debit} | null, rows: [[date, desc, credit, debit, bal]], foot: [amount, sideText] | null,
 *         pageEvery, sheets (عدد الأوراق) }
 */
function statementBook(XLSX, spec) {
  const top = [
    row({}),
    row({ 15: spec.from, 22: ":", 23: "تاريخ البداية" }),
    row({ 15: spec.to, 22: ":", 23: "تاريخ النهاية" }),
    row({}), row({}),
    row({ 3: spec.account }),
    row({ 5: spec.currency }),
    row({}),
    HEAD
  ];
  const body = [];
  if (spec.pre) body.push(row({ 4: "Pre Balance", 10: fmt(spec.pre.bal), 14: fmt(spec.pre.credit), 20: fmt(spec.pre.debit), 26: spec.from }));
  spec.rows.forEach((r, i) => {
    if (spec.pageEvery && i > 0 && i % spec.pageEvery === 0) body.push(HEAD);
    body.push(row({ 4: r[1], 10: fmt(r[4]), 14: fmt(r[2]), 20: fmt(r[3]), 26: r[0] }));
  });
  const end = [];
  if (spec.foot) end.push(row({ 1: "مبلغ بالحروف", 9: fmt(spec.foot[0]), 11: ":", 12: "اجمالي الرصيد", 19: spec.foot[1], 24: ":", 25: "نوع الرصيد" }));
  end.push(row({}), row({ 2: "شاكرين حسن تعاونكم" }), row({ 17: "مدير" }));
  const wb = XLSX.utils.book_new();
  const all = top.concat(body).concat(end);
  const n = spec.sheets || 1;
  const per = Math.ceil(all.length / n);
  for (let i = 0; i < n; i++) {
    const part = all.slice(i * per, (i + 1) * per);
    if (i > 0) part.unshift(HEAD);
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(part), "Page " + (i + 1));
  }
  return wb;
}
const SPECS = {
  // علينا 1000 سابقًا (مجاميعه دائن 5000 مدين 4000)، ثم +500 +5 −2000 = لنا 495
  "khal-usd.xlsx": { account: "1410100092 الخال", currency: "دولار امريكي", from: "2026/08/01", to: "2026/10/13",
    pre: { bal: 1000, credit: 5000, debit: 4000 }, pageEvery: 2,
    rows: [["2026/08/01", "سامر علي", 500, 0, 1500], ["2026/08/01", "Com سامر علي", 5, 0, 1505], ["2026/08/02", "الرقم السري: 588499", 0, 2000, -495]],
    foot: [495, "مدين / لنا"] },
  // الحساب نفسه باليورو: علينا 100
  "khal-eur.xlsx": { account: "1410100092 الخال", currency: "يورو", from: "2026/08/01", to: "2026/10/13", pre: null,
    rows: [["2026/09/05", "حوالة", 100, 0, 100]], foot: [100, "دائن /علينا"] },
  // عمود الرصيد هنا (مدين − دائن): لنا 300 ثم مدين 50 = لنا 350
  "noor-usd.xlsx": { account: "1410100093 النور", currency: "USD", from: "2026/08/01", to: "2026/10/13",
    pre: { bal: 300, credit: 500, debit: 800 }, rows: [["2026/08/10", "تسليم", 0, 50, 350]], foot: [350, "مدين / لنا"] },
  // كشف على ورقتين (صفحتان)، ليرة تركية: علينا 1,010.00
  "amal-try.xlsx": { account: "1410100094 الأمل", currency: "ليرة تركية", from: "2026/08/01", to: "2026/10/13", pre: null, sheets: 2,
    rows: [["2026/08/03", "أ", 1000.5, 0, 1000.5], ["2026/08/04", "ب", 0, 0.5, 1000], ["2026/08/05", "ج", 10, 0, 1010]], foot: [1010, "دائن /علينا"] }
};
// كشف معدّل يدويًا: الرصيد في الصف الثاني لا يطابق
const TAMPERED = { account: "1410100095 الفجر", currency: "دولار امريكي", from: "2026/08/01", to: "2026/10/13", pre: null,
  rows: [["2026/08/03", "أ", 100, 0, 100], ["2026/08/04", "ب", 50, 0, 140]], foot: [140, "دائن /علينا"] };
module.exports = { statementBook, SPECS, TAMPERED, fmt };
