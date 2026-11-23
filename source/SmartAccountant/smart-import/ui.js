/* الاستيراد الذكي — الواجهة. الفهم والبناء في engine.js، والقيود وملف النسخة في core.js (من أداة النقل، دون تعديل). */
"use strict";
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var C = Core, X = Smart;
  var S = { names: [], books: [], a: null, cfg: null, fchecks: [], result: null, need: {}, accFrom: {}, busy: false, q: "" };

  var ROLE_ORDER = ["date", "account", "currency", "amount", "direction", "debit", "credit", "fee", "description", "ref", "balance", "phone"];
  var ROLE_LABEL = { date: "التاريخ", account: "الحساب / المكتب", currency: "العملة", amount: "المبلغ", direction: "الاتجاه (لنا/علينا)", debit: "لنا (مدين)",
    credit: "علينا (دائن)", fee: "الأجرة / العمولة", description: "البيان", ref: "رقم القيد", balance: "الرصيد", phone: "الهاتف" };
  var KIND = {
    journal: ["سجل حركات", ""], derived: ["نسخة مفلترة — للتحقق فقط", "derived"], balances: ["جدول أرصدة", ""],
    summary: ["ملخص أرصدة محسوب — للتحقق فقط", "derived"], lookup: ["قوائم إدخال", "list"], "journal-noaccount": ["حركات بلا اسم حساب", "off"], other: ["لم أجد فيه مبالغ", "off"]
  };
  var WHY = {
    noAccount: "لا يوجد اسم حساب، فلا يُعرف لأي مكتب تعود. معادلات ملفك التي تجمع حسب الحساب لا تحسبها أيضًا.",
    noDir: "لا يوجد «لنا/علينا»، فلا يُعرف هل تزيد الرصيد أم تنقصه. معادلات ملفك (SUMIFS بشرط الاتجاه) لا تحسبها أيضًا.",
    noCurrency: "بلا عملة: لا يُعرف رصيدُ أي عملة تغيّر. معادلات ملفك (بشرط العملة) لا تحسبها أيضًا.",
    noAmount: "فيها حساب أو عملة أو اتجاه لكن بلا مبلغ، فلا تغيّر أي رصيد.",
    badCurrency: "عملة لم تُطابَق بعد — طابقها في الخطوة 3 لتُنقل.",
    badDir: "اتجاه لم يُطابَق بعد — طابقه في الخطوة 3 لتُنقل.",
    excludedCur: "استبعدتَ هذه العملة في الخطوة 3.",
    excludedDir: "استبعدتَ هذا الاتجاه في الخطوة 3.",
    badAmount: "خلية المبلغ ليست رقمًا مفهومًا (أو فيها أكثر من رقم) — صحّحها في الملف ثم اختره مجددًا.",
    decimals: "في المبلغ منازل عشرية أكثر مما حددته للعملة — زِد المنازل في الخطوة 3 (لا يُقرَّب أي مبلغ).",
    future: "تاريخها بعد اليوم — صحّح التاريخ في الملف ثم اختره مجددًا.",
    filledDate: "نُقلت وتدخل في الرصيد كما في ملفك، لكن خانة التاريخ فارغة أو غير صالحة فأُخذ تاريخ الصف الذي قبلها.",
    badFee: "خلية الأجرة ليست رقمًا مفهومًا — صحّحها في الملف، أو اختر «لا تُحسب» للأجرة في الخطوة 3."
  };

  function esc(s) {
    return String(s === null || s === undefined ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; });
  }
  function n(x) { return Number(x).toLocaleString("en-US"); }
  function stat(v, label, err) { return '<div class="stat' + (err ? " err" : "") + '"><b>' + v + "</b><span>" + label + "</span></div>"; }
  function decOf(code) { return X.curDef(S.cfg, code).dec; }
  function bw(m, dec) { return m === 0n ? "0" : (m > 0n ? "لنا " : "علينا ") + C.fmtMinor(m > 0n ? m : -m, dec); }
  function balCell(m, dec) {
    if (m === 0n) return '<span class="hint">0</span>';
    return '<span class="chip ' + (m > 0n ? "us" : "them") + '">' + (m > 0n ? "لنا" : "علينا") + '</span> <span class="ltr ' + (m > 0n ? "us" : "them") + '">' + C.fmtMinor(m > 0n ? m : -m, dec) + "</span>";
  }
  function codeOptions(sel, extra) {
    var codes = Object.keys(C.KNOWN);
    Object.keys(S.cfg.currencies).forEach(function (c) { if (codes.indexOf(c) < 0) codes.push(c); });
    return (extra || "") + codes.map(function (c) {
      var d = S.cfg.currencies[c] || C.KNOWN[c];
      return '<option value="' + c + '"' + (sel === c ? " selected" : "") + ">" + c + " — " + esc(d ? d.name : c) + "</option>";
    }).join("") + '<option value="__new">رمز آخر…</option>';
  }
  function seg(name, opts, val) {
    return '<div class="seg">' + opts.map(function (o) {
      return '<label><input type="radio" name="' + name + '" value="' + o[0] + '"' + (o[0] === val ? " checked" : "") + ">" + esc(o[1]) + "</label>";
    }).join("") + "</div>";
  }
  function colLabel(t, c) { return X.colName(c) + " — " + (t.names[c - t.c0] || ""); }
  function tableById(id) { return S.a.tables.filter(function (t) { return t.id === id; })[0]; }
  function serialToDate(v) {
    var p = XLSX.SSF.parse_date_code(v);
    return p ? { y: p.y, m: p.m, d: p.d, H: p.H, M: p.M, S: p.S } : null;
  }
  function rand(k) { return crypto.getRandomValues(new Uint8Array(k)); }
  function wait(ms) { return new Promise(function (res) { setTimeout(res, ms || 30); }); }

  // ===================================================================================
  // 1. قراءة الملفات
  // ===================================================================================

  function decodeText(buf) {
    try { return new TextDecoder("utf-8", { fatal: true }).decode(buf); } catch (e) { return new TextDecoder("windows-1256").decode(buf); }
  }

  function readBook(file) {
    return file.arrayBuffer().then(function (ab) {
      var buf = new Uint8Array(ab);
      var lower = file.name.toLowerCase();
      var wb;
      try {
        if (/\.(csv|txt|tsv)$/.test(lower)) {
          var txt = decodeText(buf).replace(/^﻿/, "");
          var first = txt.split("\n")[0];
          var semi = (first.match(/;/g) || []).length > (first.match(/,/g) || []).length;
          var tab = lower.endsWith(".tsv") || (first.match(/\t/g) || []).length > (first.match(/,/g) || []).length;
          wb = XLSX.read(txt, { type: "string", raw: true, FS: tab ? "\t" : (semi ? ";" : undefined) });
        } else {
          wb = XLSX.read(buf, { type: "array", cellNF: true, cellDates: false, cellFormula: true, bookFiles: true });
        }
      } catch (e) {
        var msg = String(e && e.message || e);
        if (/password|encrypt/i.test(msg)) throw new Error("«" + file.name + "» محمي بكلمة سر. افتحه في Excel واحفظه بدون كلمة سر ثم اختره مجددًا.");
        throw new Error("تعذر فتح «" + file.name + "»: " + msg);
      }
      return { name: file.name, wb: wb, sheets: wb.SheetNames.map(function (nm) { return { name: nm, rows: X.sheetRows(wb.Sheets[nm], XLSX) }; }) };
    });
  }

  function readFiles(list) {
    var files = Array.prototype.slice.call(list || []);
    if (!files.length) return;
    var info = $("fileInfo");
    info.className = "";
    info.innerHTML = '<div class="msg info" id="readMsg">جارٍ قراءة ' + (files.length > 1 ? files.length + " ملفات" : "«" + esc(files[0].name) + "»") +
      "… الملف الكبير يحتاج بضع ثوانٍ.</div><div class=\"progress\"><i></i></div>";
    reset();
    var books;
    Promise.all(files.map(readBook)).then(function (b) {
      books = b;
      $("readMsg").textContent = "أفهم الأوراق والجداول ودور كل عمود…";
      return wait();
    }).then(function () {
      S.names = files.map(function (f) { return f.name; });
      S.books = books;
      S.a = X.analyze(books);
      initCfg();
      $("readMsg").textContent = "أعيد حساب معادلات ملفك للتحقق…";
      return wait();
    }).then(function () {
      S.fchecks = X.formulaChecks(S.books, S.a);
      X.detectSummaries(S.a, S.cfg, serialToDate);
      X.detectFeeDirs(S.a, S.cfg, serialToDate);
      refreshValues();
      $("readMsg").textContent = "أبني المكاتب والحركات…";
      return wait();
    }).then(function () {
      run();
      renderAll(true);
    }).catch(function (e) {
      info.innerHTML = '<div class="msg err">' + esc(e.message || e) + "</div>";
    });
  }

  function reset() {
    S.a = null; S.result = null; S.accFrom = {}; S.q = "";
    ["understand", "values", "attention", "review", "makeMsg"].forEach(function (id) { $(id).innerHTML = ""; });
    ["step2", "step3", "step4", "step5", "step6"].forEach(function (id) { $(id).classList.add("off"); $(id).classList.remove("done"); });
    $("confirmed").checked = false;
    updateMake();
  }

  function initCfg() {
    S.cfg = { now: Date.now(), dirMap: {}, curMap: {}, currencies: {}, rename: {}, positiveIs: {}, balanceSign: {}, fixedCurrency: {},
      defaultCurrency: "USD", listedAccounts: true, inferOpening: true, openingDate: null, fixedCurrencyUser: {} };
    S.a.tables.forEach(function (t) { if (t.sheetCurrency) S.cfg.fixedCurrency[t.id] = t.sheetCurrency; });
  }

  /** بعد أي تغيير في الجداول المستخدمة أو أدوارها: القيم التي تحتاج مطابقة، وتعريف كل عملة مستخدمة. */
  function refreshValues() {
    S.a.values = X.collectValues(S.a);
    var v = S.a.values;
    Object.keys(v.dirs).forEach(function (k) { if (!(k in S.cfg.dirMap)) S.cfg.dirMap[k] = v.dirs[k].guess || ""; });
    Object.keys(v.curs).forEach(function (k) { if (!(k in S.cfg.curMap)) S.cfg.curMap[k] = v.curs[k].guess || ""; });
    syncCurrencies();
  }

  function usedCodes() {
    var codes = {};
    Object.keys(S.a.values.curs).forEach(function (k) { var c = S.cfg.curMap[k]; if (c && c !== "__skip") codes[c] = true; });
    S.a.tables.forEach(function (t) {
      if (!t.use) return;
      if (t.roles.currency === undefined && !t.roles.wide) codes[S.cfg.fixedCurrency[t.id] || t.sheetCurrency || S.cfg.defaultCurrency] = true;
    });
    return Object.keys(codes);
  }

  /** المنازل العشرية لكل عملة = الأكبر بين المعتاد لها وما في الملف، حتى لا يُقرَّب أي مبلغ. */
  function syncCurrencies() {
    S.need = X.suggestDecimals(S.a, S.cfg);
    usedCodes().forEach(function (c) {
      var d = S.cfg.currencies[c];
      var k = C.KNOWN[c];
      if (!d) S.cfg.currencies[c] = { name: k ? k.name : c, dec: Math.max(k ? k.dec : 2, S.need[c] || 0) };
      else if (!d.userSet && (S.need[c] || 0) > d.dec) d.dec = S.need[c];
    });
  }

  // ===================================================================================
  // الحساب
  // ===================================================================================

  function tablesCfg() {
    return { now: S.cfg.now, currencies: S.cfg.currencies, defaultCurrency: S.cfg.defaultCurrency, userName: "استيراد ذكي",
      sourceName: "الاستيراد الذكي — " + S.names.join("، ") };
  }

  function run() {
    S.cfg.now = Date.now();
    try {
      var model = X.build(S.a, S.cfg, serialToDate);
      var tc = tablesCfg();
      var T = C.tables(model, tc, rand);
      var rep = C.report(model, T, tc);
      var unb = C.checkBalanced(T);
      if (unb.length) throw new Error("قيد غير متوازن — لا يجب أن يحدث، أرسل الملف للمطوّر");
      // فحص داخلي: رصيد كل مكتب من قيود التطبيق = مجموع صفوف الملف مباشرة
      var fromT = C.balancesFromTables(T), internal = { n: 0, bad: [] };
      model.offices.forEach(function (o, i) {
        T.office_currencies.forEach(function (oc) {
          if (oc.office_id !== i + 1) return;
          internal.n++;
          var a = fromT[(i + 1) + "|" + oc.code] || 0n, b = model.direct[o.key + "|" + oc.code] || 0n;
          if (a !== b) internal.bad.push(o.name + " " + oc.code);
        });
      });
      var perTable = {};
      model.txs.forEach(function (x) {
        var p = perTable[x.src.tid] = perTable[x.src.tid] || { n: 0, offices: {}, codes: {}, min: null, max: null };
        p.n++; p.offices[x.office] = true; p.codes[x.code] = true;
        if (p.min === null || x.when < p.min) p.min = x.when;
        if (p.max === null || x.when > p.max) p.max = x.when;
      });
      model.openings.forEach(function (x) {
        var p = perTable[x.src.tid] = perTable[x.src.tid] || { n: 0, offices: {}, codes: {}, min: null, max: null };
        p.ob = (p.ob || 0) + 1; p.offices[x.office] = true; p.codes[x.code] = true;
      });
      S.result = { model: model, T: T, rep: rep, internal: internal, perTable: perTable, derived: X.derivedChecks(S.a, model, S.cfg, serialToDate) };
    } catch (e) {
      S.result = { error: e.message || String(e) };
    }
  }

  var timer = null;
  function later(full) {
    clearTimeout(timer);
    var box = $("review");
    if (S.result && !S.result.error && !$("busy")) box.insertAdjacentHTML("afterbegin", '<div class="msg info" id="busy">جارٍ إعادة الحساب…</div>');
    timer = setTimeout(function () { run(); renderAll(full); }, 200);
  }

  // ===================================================================================
  // العرض
  // ===================================================================================

  function renderAll(full) {
    var open = {};
    document.querySelectorAll("details[data-k]").forEach(function (d) { open[d.dataset.k] = d.open; });
    renderInfo();
    renderUnderstand();
    if (full !== false) renderValues();
    renderAttention();
    renderReview();
    document.querySelectorAll("details[data-k]").forEach(function (d) { if (d.dataset.k in open) d.open = open[d.dataset.k]; });
    ["step2", "step3", "step4", "step5", "step6"].forEach(function (id) { $(id).classList.toggle("off", !S.a); });
    updateMake();
  }

  function renderInfo() {
    var rows = 0, sheets = 0;
    S.books.forEach(function (b) { b.sheets.forEach(function (s) { sheets++; rows += s.rows.length; }); });
    $("fileInfo").innerHTML = '<div class="msg ok">✓ قرأتُ ' + S.names.map(function (x) { return "«" + esc(x) + "»"; }).join("، ") + ": " + sheets + " ورقة. " +
      '<label class="btn sec" style="display:inline-flex;align-items:center;min-height:34px;padding:4px 12px;margin-inline-start:8px;font-size:13px">اختيار ملف آخر<input type="file" id="file2" multiple accept=".xlsx,.xlsm,.xlsb,.xls,.csv,.txt,.tsv,.ods" hidden></label></div>';
  }

  function fmtDay(ms) { return ms === null || ms === undefined ? "" : C.fmtDate(ms).split(" ")[0]; }

  function renderUnderstand() {
    var a = S.a, r = S.result || {}, pt = r.perTable || {};
    var html = "";
    var usable = a.tables.filter(function (t) { return t.use; });
    if (!usable.length) {
      html += '<div class="msg err"><b>لم أجد في الملف سجل حركات ولا جدول أرصدة يمكن نقله تلقائيًا.</b> افتح «تعديل الأعمدة» في الجدول المناسب أدناه وحدّد أعمدة الحساب والمبلغ (والتاريخ للحركات).</div>';
    }
    var jUsed = usable.filter(function (t) { return t.kind !== "balances"; }), bUsed = usable.filter(function (t) { return t.kind === "balances"; });
    if (jUsed.length && bUsed.length) {
      html += '<div class="msg warn"><b>انتبه:</b> ستُنقل حركات من ' + jUsed.map(function (t) { return "«" + esc(t.label) + "»"; }).join("، ") + " وأرصدة سابقة من " +
        bUsed.map(function (t) { return "«" + esc(t.label) + "»"; }).join("، ") + ". إن كان جدول الأرصدة نتيجةً لهذه الحركات (لا رصيدًا قبلها) فأوقف نقله، وإلا تضاعفت الأرصدة.</div>";
    }
    a.tables.forEach(function (t) { html += tableCard(t, pt[t.id], r.model && r.model.perTable[t.id]); });
    a.lists.forEach(function (l) {
      var empty = 0;
      if (r.model) l.values.forEach(function (v) { var i = r.model.officeIndex[X.key(S.cfg.rename[X.key(v)] || v)]; if (i !== undefined && r.model.offices[i].emptyOk) empty++; });
      html += '<div class="tcard"><div class="top"><b>' + esc(l.label) + '</b><span class="badge list">قائمة أسماء الحسابات</span></div>' +
        '<div class="facts">' + n(l.values.length) + " اسمًا" + (empty ? "، منها " + n(empty) + " بلا أي حركة أو رصيد" : "") + "</div>" +
        '<label class="check"><input type="checkbox" data-listed="1"' + (S.cfg.listedAccounts ? " checked" : "") + "> أنشئ في التطبيق كل المكاتب التي في القائمة، حتى التي ليس لها حركات</label></div>";
    });
    var notes = a.notes.filter(function (x) { return x.kind !== "empty"; });
    if (notes.length || a.tables.some(function (t) { return t.kind === "lookup"; })) {
      html += '<p class="hint">أوراق لا يُنقل منها شيء: ' + notes.map(function (x) { return "«" + esc(x.sheet) + "»" + (x.kind === "chart" ? " (رسم بياني)" : " (لا جدول بيانات فيها — نموذج إدخال أو رسم أو نص)"); })
        .concat(a.tables.filter(function (t) { return t.kind === "lookup"; }).map(function (t) { return "«" + esc(t.label) + "» (قوائم قيم للإدخال)"; })).join("، ") + ".</p>";
    }
    $("understand").innerHTML = html;
  }

  function tableCard(t, p, st) {
    if (t.kind === "lookup") return "";
    var k = KIND[t.kind] || KIND.other;
    var r = t.roles;
    var h = '<div class="tcard' + (t.use ? "" : " off") + '"><div class="top"><b>' + esc(t.label) + '</b><span class="badge ' + k[1] + '">' + k[0] + "</span>" +
      '<span class="hint">' + (t.excel ? "جدول Excel «" + esc(t.excel) + "» · " : "") + "الصفوف " + (t.first + 1) + "–" + (t.last + 1) + "</span></div>";
    // ما فيه
    var facts = [];
    if (t.use && p) {
      if (p.n) facts.push(n(p.n) + " حركة");
      if (p.ob) facts.push(n(p.ob) + " رصيد سابق");
      facts.push(n(Object.keys(p.offices).length) + " حسابًا");
      facts.push("العملات: " + Object.keys(p.codes).sort().join("، "));
      if (p.min) facts.push("من " + fmtDay(p.min) + " إلى " + fmtDay(p.max));
    } else if (t.use && st) facts.push("لا شيء يُنقل منه بعد");
    if (st && st.excluded) facts.push(n(st.excluded) + " صفًا لا تُنقل (الخطوة 4)");
    if (facts.length) h += '<div class="facts">' + facts.join(" · ") + "</div>";
    // الأدوار
    h += '<div class="roles">';
    ROLE_ORDER.forEach(function (role) {
      if (r[role] !== undefined) h += '<span class="role">' + ROLE_LABEL[role] + ": <b>" + esc(t.names[r[role] - t.c0]) + "</b></span>";
    });
    if (r.wide) h += '<span class="role">أعمدة العملات: <b>' + r.wide.map(function (w) { return esc(w.h); }).join("، ") + "</b></span>";
    var isJ = t.kind === "journal" || t.kind === "journal-noaccount" || t.kind === "derived";
    if ((isJ || t.kind === "balances" || t.kind === "summary") && r.account === undefined) {
      h += t.sheetAccount ? '<span class="role">الحساب: <b>' + esc(t.sheetAccount) + "</b> (من اسم " + (t.accountFrom === "file" ? "الملف" : t.accountFrom === "custom" ? "كتبتَه" : "الورقة") + ")</span>"
        : '<span class="role miss">لا يوجد عمود حساب</span>';
    }
    if ((isJ || t.kind === "balances") && r.currency === undefined && !r.wide) {
      h += '<span class="role">العملة: <b>' + esc(S.cfg.fixedCurrency[t.id] || t.sheetCurrency || S.cfg.defaultCurrency) + "</b> لكل الجدول</span>";
    }
    if (t.signed && (isJ || t.kind === "balances")) h += '<span class="role">الاتجاه: <b>من إشارة المبلغ</b> (الخطوة 3)</span>';
    if (r.notes && r.notes.length && t.kind !== "other") h += '<span class="role">في ملاحظات الحركة: <b>' + r.notes.map(function (c) { return esc(t.names[c - t.c0]); }).join("، ") + "</b></span>";
    h += "</div>";
    // شرح
    if (t.kind === "derived") {
      var src = tableById(t.derivedFrom);
      var accs = {};
      if (r.account !== undefined) for (var q = t.first; q <= t.last; q++) { var v = C.text((t.rows[q] || [])[r.account]); if (v) accs[X.key(v)] = C.clean(v); }
      var al = Object.keys(accs);
      h += '<p class="hint">' + n(t.derivedHits) + " من صفوفه موجودة كما هي في «" + esc(src ? src.label : "") + "»" + (al.length === 1 ? " (لحساب واحد: «" + esc(accs[al[0]]) + "»)" : "") +
        "، فهو نسخة مفلترة منه. لا يُنقل حتى لا تتكرر الحركات، ويُستخدم للتحقق في الخطوة 5.</p>";
    }
    if (t.unsure === "allPositive") {
      h += '<p class="hint">كل المبالغ موجبة ولا يوجد عمود اتجاه (لنا/علينا)، فلم أنقله تلقائيًا. إن كانت كلها في اتجاه واحد ففعّل النقل وحدّد معنى المبلغ الموجب في الخطوة 3، أو حدّد عمود الاتجاه من «تعديل الأعمدة».</p>';
    }
    if (t.kind === "summary") {
      h += '<p class="hint">' + (t.summaryWhy === "formulas" ? "أرصدته محسوبة بمعادلات من الحركات" : "قيمه تطابق نتيجة الحركات") +
        "، فهو ملخص لها لا أرصدة قبلها. لا يُنقل حتى لا تتضاعف الأرصدة، ويُقارن كل رصيد فيه برصيد المكتب بعد النقل في الخطوة 5.</p>";
    }
    if (t.kind === "journal-noaccount" || ((isJ || t.kind === "balances") && r.account === undefined && t.kind !== "derived")) {
      var af = S.accFrom[t.id] || { from: t.accountFrom || "", name: t.accountFrom === "custom" ? t.sheetAccount : "" };
      h += '<div class="grid" style="margin:6px 0"><label class="f">اسم الحساب لكل صفوف هذا الجدول<select data-accfrom="' + t.id + '">' +
        '<option value="">— اختر —</option><option value="sheet"' + (af.from === "sheet" ? " selected" : "") + ">اسم الورقة: " + esc(t.fromSheet.name) + "</option>" +
        '<option value="file"' + (af.from === "file" ? " selected" : "") + ">اسم الملف: " + esc(t.fromFile.name) + "</option>" +
        '<option value="custom"' + (af.from === "custom" ? " selected" : "") + ">اسم أكتبه…</option></select></label>" +
        (af.from === "custom" ? '<label class="f">الاسم<input type="text" data-accname="' + t.id + '" value="' + esc(af.name || "") + '"></label>' : "") + "</div>";
    }
    if ((isJ || t.kind === "balances") && r.currency === undefined && !r.wide && t.kind !== "derived") {
      h += '<div class="grid" style="margin:6px 0"><label class="f">عملة هذا الجدول <small>لا يوجد فيه عمود عملة</small><select data-fixcur="' + t.id + '">' +
        codeOptions(S.cfg.fixedCurrency[t.id] || t.sheetCurrency || S.cfg.defaultCurrency) + "</select></label></div>";
    }
    // الاستخدام
    if (t.kind !== "other") {
      var useLabel = t.kind === "derived" ? "انقله أيضًا (غير منصوح: حركاته موجودة في الجدول الأصلي فتتكرر)"
        : t.kind === "summary" ? "انقله كأرصدة سابقة (غير منصوح: يضاعف الأرصدة إن كان نتيجة الحركات)"
        : t.kind === "journal-noaccount" ? "انقل هذا الجدول (اختر اسم الحساب أولًا)" : "انقل هذا الجدول";
      h += '<label class="check"><input type="checkbox" data-use="' + t.id + '"' + (t.use ? " checked" : "") + (t.kind === "journal-noaccount" ? " disabled" : "") + "> " + useLabel + "</label>";
    }
    // تعديل الأعمدة
    h += '<details class="box" data-k="roles-' + t.id + '"><summary>تعديل الأعمدة<span class="hint">إن أخطأتُ في فهم عمود</span></summary><div><div class="grid">';
    ROLE_ORDER.forEach(function (role) {
      h += '<label class="f">' + ROLE_LABEL[role] + '<select data-role="' + t.id + "|" + role + '"><option value="">— لا يوجد —</option>';
      for (var c = t.c0; c <= t.c1; c++) h += '<option value="' + c + '"' + (r[role] === c ? " selected" : "") + ">" + esc(colLabel(t, c)) + "</option>";
      h += "</select></label>";
    });
    h += '</div><p class="hint">يكفي للحركات: الحساب، والمبلغ مع الاتجاه (أو عمودا لنا/علينا، أو مبلغ موقّع)، والتاريخ. الأعمدة التي لا دور لها تُحفظ في ملاحظات كل حركة.</p></div></details>';
    return h + "</div>";
  }

  function renderValues() {
    var a = S.a, v = a.values, html = "";
    var dk = Object.keys(v.dirs);
    if (dk.length) {
      html += '<h3>الاتجاه (لنا / علينا)</h3><div class="mapgrid"><span class="h">القيمة في ملفك</span><span class="h">صفوف</span><span class="h">معناها في التطبيق</span>';
      dk.sort(function (x, y) { return v.dirs[y].n - v.dirs[x].n; }).forEach(function (k) {
        var d = v.dirs[k], cur = S.cfg.dirMap[k] || "";
        html += "<b>" + esc(d.text) + '</b><span class="hint">' + n(d.n) + '</span><select data-dirmap="' + esc(k) + '">' +
          [["", "— اختر —"], ["D", "لنا — مدين: يزيد ما لنا عنده"], ["C", "علينا — دائن: يزيد ما علينا له"], ["X", "استبعاد هذه الصفوف"]].map(function (o) {
            return '<option value="' + o[0] + '"' + (cur === o[0] ? " selected" : "") + ">" + o[1] + "</option>";
          }).join("") + "</select>";
      });
      html += '</div><p class="hint">طابقتُها من الكلمات نفسها (مدين/لنا ← لنا، دائن/لكم/علينا ← علينا)، وهي القاعدة التي تجمع بها معادلات ملفك. المبلغ السالب يعكس الاتجاه، كما في Excel.</p>';
    }
    var ck = Object.keys(v.curs);
    if (ck.length) {
      html += '<h3>العملات</h3><div class="mapgrid"><span class="h">القيمة في ملفك</span><span class="h">صفوف</span><span class="h">العملة في التطبيق</span>';
      ck.sort(function (x, y) { return v.curs[y].n - v.curs[x].n; }).forEach(function (k) {
        var c = v.curs[k];
        html += "<b class=\"ltr\">" + esc(c.text) + '</b><span class="hint">' + (c.listed && !c.n ? "قائمة" : n(c.n)) + '</span><select data-curmap="' + esc(k) + '">' +
          codeOptions(S.cfg.curMap[k] || "", '<option value="">— اختر —</option><option value="__skip"' + (S.cfg.curMap[k] === "__skip" ? " selected" : "") + ">استبعاد هذه العملة</option>") + "</select>";
      });
      html += "</div>";
    }
    var codes = usedCodes();
    if (codes.length) {
      html += '<h3>تعريف العملات</h3><div class="curdefs"><span class="hint">الرمز</span><span class="hint">الاسم في التطبيق</span><span class="hint">المنازل العشرية</span>';
      codes.sort().forEach(function (c) {
        var d = S.cfg.currencies[c];
        html += '<b class="ltr">' + esc(c) + '</b><input type="text" data-curname="' + c + '" value="' + esc(d.name) + '"><select data-curdec="' + c + '">' +
          [0, 1, 2, 3, 4, 5, 6].map(function (x) { return "<option" + (d.dec === x ? " selected" : "") + ">" + x + "</option>"; }).join("") + "</select>";
      });
      html += '</div><p class="hint">المنازل = الأكبر بين المعتاد للعملة وما في ملفك' +
        codes.filter(function (c) { return S.need[c]; }).map(function (c) { return " (" + c + ": ملفك يحتاج " + S.need[c] + ")"; }).join("") +
        '، فلا يُقرَّب أي مبلغ. إن كانت العملة في التطبيق بمنازل أقل يرفض التطبيق الإضافة بدل أن يقرّب.</p>';
    }
    // إشارة المبالغ الموقّعة
    a.tables.forEach(function (t) {
      if (!t.use || !t.signed) return;
      html += "<h3>إشارة المبالغ في «" + esc(t.label) + "»</h3><p class=\"hint\">لا يوجد عمود اتجاه: المبلغ الموجب يعني…</p>" +
        seg("pos|" + t.id, [["D", "لنا — مدين (عليه لنا)"], ["C", "علينا — دائن (له علينا)"]], S.cfg.positiveIs[t.id] || "D");
    });
    // الأجرة
    a.tables.forEach(function (t) {
      if (!t.use || t.roles.fee === undefined || t.kind === "balances" || t.kind === "summary") return;
      html += "<h3>الأجرة في «" + esc(t.label) + "» (عمود «" + esc(t.names[t.roles.fee - t.c0]) + "»)</h3><p class=\"hint\">تُسجَّل مع الحركة نفسها كأجرة (قيد مستقل كما في التطبيق)" +
        (t.feeDetected ? "؛ عرفتُ اتجاهها من عمود الرصيد في ملفك" : "") + ".</p>" +
        seg("fee|" + t.id, [["D", "لنا — يدفعها المكتب لنا"], ["C", "علينا — ندفعها للمكتب"], ["X", "لا تُحسب في الرصيد (تُحفظ في الملاحظات)"]], (S.cfg.feeDir && S.cfg.feeDir[t.id]) || "D");
    });
    // إشارة عمود الرصيد
    var bc = (S.result && S.result.model && S.result.model.balanceChecks) || [];
    a.tables.forEach(function (t) {
      if (!t.use || t.roles.balance === undefined) return;
      var ch = bc.filter(function (x) { return x.tid === t.id; })[0];
      var auto = ch && ch.sign && !ch.forced ? (ch.sign === "D" ? " (عرفتُ من الملف: الموجب = لنا)" : " (عرفتُ من الملف: الموجب = علينا)") : "";
      html += "<h3>عمود الرصيد في «" + esc(t.label) + "»</h3><p class=\"hint\">يُستخدم للتحقق سطرًا بسطر، ولاستنتاج الرصيد السابق إن بدأ الحساب برصيد غير مكتوب كحركة" + esc(auto) + ".</p>" +
        seg("bsign|" + t.id, [["", "تلقائي"], ["D", "الموجب = لنا"], ["C", "الموجب = علينا"]], S.cfg.balanceSign[t.id] || "");
    });
    if (a.tables.some(function (t) { return t.use && t.roles.balance !== undefined; })) {
      html += '<label class="check"><input type="checkbox" data-infer="1"' + (S.cfg.inferOpening ? " checked" : "") +
        "> أضف «رصيدًا سابقًا» لكل حساب يبدأ في ملفك برصيد غير مكتوب كحركة (حتى يطابق رصيدُه رصيدَ ملفك)</label>";
    }
    if (a.tables.some(function (t) { return t.use && t.kind === "balances"; })) {
      html += '<h3>تاريخ الأرصدة السابقة</h3><div class="grid"><label class="f">التاريخ <small>فارغ = قبل أول حركة بدقيقة</small><input type="date" data-odate="1" value="' +
        esc(S.cfg.openingDateStr || "") + '"></label></div>';
    }
    $("values").innerHTML = html || '<p class="hint">لا توجد قيم تحتاج مطابقة.</p>';
  }

  function renderAttention() {
    var box = $("attention"), r = S.result;
    if (!r || r.error) { box.innerHTML = ""; return; }
    var m = r.model, html = "";
    var groups = Object.keys(m.issues).map(function (k) { return m.issues[k]; });
    var excluded = groups.filter(function (g) { return !g.counted; }).reduce(function (s, g) { return s + g.total; }, 0);
    var counted = groups.filter(function (g) { return g.counted; }).reduce(function (s, g) { return s + g.total; }, 0);
    var imported = Object.keys(m.perTable).reduce(function (s, k) { return s + m.perTable[k].imported; }, 0);
    html += '<div class="stats">' + stat(n(imported), "صف نُقل") + stat(n(excluded), "صف لم يُنقل", false) + stat(n(counted), "نُقل بتاريخ الصف السابق") +
      stat(n(m.emptyRows), "صف فارغ") + "</div>";
    if (!groups.length) html += '<div class="msg ok">✓ كل صفوف ملفك فيها ما يلزم للنقل.</div>';
    groups.sort(function (x, y) { return (x.counted - y.counted) || (y.total - x.total); }).forEach(function (g) {
      html += '<details class="box" data-k="iss-' + g.code + '"><summary><span>' + esc(g.label) + " — " + n(g.total) + " صف</span>" +
        (g.counted ? '<span class="chip ok">نُقلت</span>' : '<span class="chip bad">لم تُنقل</span>') + "</summary><div><p class=\"hint\">" + esc(WHY[g.code] || "") + "</p>" +
        issueTable(g) + "</div></details>";
    });
    var zero = m.skipped.filter(function (s) { return s.reason === Smart.REASONS.zero; }).length;
    var info = [];
    if (m.emptyRows) info.push(n(m.emptyRows) + " صفًا فارغًا (رقم قيد أو تاريخ أو بيان فقط، بلا مبلغ ولا حساب) — جداول Excel تُعدّ صفوفًا فارغة مسبقًا؛ لا شيء فيها يُنقل.");
    if (zero) info.push(n(zero) + " صفًا مبلغه صفر — لا يغيّر أي رصيد.");
    if (m.totalsSkipped) info.push(n(m.totalsSkipped) + " صف مجموع («المجموع»/«الإجمالي» بلا تاريخ) — ليس حركة فلم يُنقل.");
    if (info.length) html += '<div class="msg info"><ul style="margin:0">' + info.map(function (x) { return "<li>" + esc(x) + "</li>"; }).join("") + "</ul></div>";
    box.innerHTML = html;
  }

  function issueTable(g) {
    var cols = [["date", "التاريخ"], ["account", "الحساب"], ["currency", "العملة"], ["amount", "المبلغ"], ["debit", "لنا"], ["credit", "علينا"], ["fee", "الأجرة"], ["direction", "الاتجاه"], ["description", "البيان"], ["balance", "الرصيد"]];
    var has = {};
    g.rows.forEach(function (x) { cols.forEach(function (c) { if (c[0] in x.cells) has[c[0]] = true; }); });
    cols = cols.filter(function (c) { return has[c[0]]; });
    var shown = g.rows.slice(0, 300);
    var h = '<div class="tablebox" style="max-height:320px"><table><tr><th>الورقة</th><th>الصف</th>' + cols.map(function (c) { return "<th>" + c[1] + "</th>"; }).join("") + "</tr>";
    shown.forEach(function (x) {
      h += "<tr><td>" + esc(x.sheet) + "</td><td>" + x.row + "</td>" + cols.map(function (c) {
        var v = x.cells[c[0]];
        return "<td" + (c[0] === "amount" || c[0] === "debit" || c[0] === "credit" ? ' class="num"' : "") + ">" + (v ? esc(String(v).slice(0, 60)) : '<span class="err-t">—</span>') + "</td>";
      }).join("") + "</tr>";
    });
    h += "</table></div>";
    if (g.total > shown.length) h += '<p class="hint">و' + n(g.total - shown.length) + " صفًا غيرها — كلها في تقرير الأرصدة (Excel) من الخطوة 6.</p>";
    return h;
  }

  function renderReview() {
    var box = $("review"), r = S.result;
    if (!r) { box.innerHTML = ""; return; }
    if (r.error) { box.innerHTML = '<div class="msg err">تعذر الحساب: ' + esc(r.error) + "</div>"; return; }
    var m = r.model, T = r.T, rep = r.rep, html = "";
    // التحقق
    var li = [];
    function add(ok, txt) { li.push('<li class="' + (ok ? "ok" : "bad") + '">' + (ok ? "✓ " : "✗ ") + txt + "</li>"); }
    add(!r.internal.bad.length, r.internal.bad.length ? "رصيد " + r.internal.bad.length + " مكتب/عملة من القيود لا يساوي مجموع صفوف الملف: " + esc(r.internal.bad.slice(0, 5).join("، "))
      : "رصيد كل مكتب وعملة (" + n(r.internal.n) + ") من قيود التطبيق يساوي مجموع صفوف ملفك مباشرة، وكل قيد متوازن (مدين = دائن).");
    var fc = S.fchecks, fbad = fc.filter(function (x) { return !x.ok; });
    if (fc.length) {
      add(!fbad.length, fbad.length ? fbad.length + " من " + fc.length + " معادلة في ملفك لا تطابق قيمتها المحفوظة صفوفَه الحالية (ربما لم يُعد Excel حسابها بعد آخر تعديل — افتح الملف في Excel واحفظه ثم أعد اختياره)."
        : "أعدتُ حساب معادلات SUMIFS/SUMIF في ملفك (" + fc.length + ") من صفوفه، فطابقت كلها القيم المحفوظة في الملف — أي أن قراءة الصفوف مطابقة لـ Excel.");
    }
    var dbad = r.derived.filter(function (x) { return !x.ok; });
    if (r.derived.length) {
      var byAcc = {};
      r.derived.forEach(function (x) { (byAcc[x.sheet + "|" + x.account] = byAcc[x.sheet + "|" + x.account] || []).push(x); });
      Object.keys(byAcc).forEach(function (k) {
        var xs = byAcc[k], bad = xs.filter(function (x) { return !x.ok; });
        add(!bad.length, "ورقة «" + esc(xs[0].sheet) + "» (لحساب «" + esc(xs[0].account) + "»): " + xs.map(function (x) {
          return x.code + " " + esc(bw(BigInt(x.file), x.dec)) + (x.ok ? "" : " ≠ بعد النقل " + esc(bw(BigInt(x.ours), x.dec)));
        }).join("، ") + (bad.length ? "" : " — يطابق رصيده بعد النقل."));
      });
    }
    (m.balanceChecks || []).forEach(function (b) {
      if (!b.sign && !b.pairs) {
        li.push('<li class="bad">ⓘ عمود «' + esc(b.column) + "» في «" + esc(b.table) + "»: لا توجد حركتان متتاليتان لمعرفة إشارته، فلم يُستخدم للتحقق. حدّد إشارته في الخطوة 3 إن أردت.</li>");
        return;
      }
      if (!b.sign) {
        li.push('<li class="bad">ⓘ عمود «' + esc(b.column) + "» في «" + esc(b.table) + "» لا يتبع رصيد كل حساب على حدة (" + n(b.agree) + " من " + n(b.pairs) +
          " فرقًا فقط يساوي مبلغ الحركة)، فلم يُستخدم للتحقق ولا لاستنتاج رصيد سابق. حدّد إشارته في الخطوة 3 إن كان رصيدًا فعلًا.</li>");
        return;
      }
      add(!b.badN, "عمود «" + esc(b.column) + "» في «" + esc(b.table) + "» (الموجب = " + (b.sign === "D" ? "لنا" : "علينا") +
        (b.how === "book" ? "، كباقي أوراق الملف" : b.how === "first" ? "، من أول صف" : "") + "): " +
        (b.badN ? n(b.badN) + " من " + n(b.rows) + " رصيدًا لا يطابق رصيدنا التراكمي (التفاصيل أدناه)." : "كل الأرصدة (" + n(b.rows) + ") طابقت رصيدنا التراكمي سطرًا بسطر.") +
        (b.implied ? " أُضيف رصيد سابق مستنتج لـ " + n(b.implied) + " حساب." : ""));
    });
    (m.summaryChecks || []).forEach(function (s) {
      var bad = s.rows.filter(function (x) { return !x.ok; });
      add(!bad.length, "جدول «" + esc(s.table) + "» (ملخص، الموجب = " + (s.sign === "D" ? "لنا" : "علينا") + "): " +
        (bad.length ? n(bad.length) + " من " + n(s.n) + " رصيدًا لا يطابق رصيد المكتب بعد النقل (التفاصيل أدناه)." : "كل أرصدته (" + n(s.n) + ") تطابق أرصدة المكاتب بعد النقل."));
    });
    var allOk = li.every(function (x) { return x.indexOf('class="ok"') >= 0; });
    html += '<div class="msg ' + (allOk ? "ok" : "warn") + '"><b>التحقق من الدقة</b><ul class="checks">' + li.join("") + "</ul></div>";
    // تفاصيل عدم المطابقة
    (m.balanceChecks || []).forEach(function (b) {
      if (!b.bad.length) return;
      html += '<details class="box" data-k="bbad-' + b.tid + '"><summary>صفوف لا يطابق فيها عمود الرصيد — ' + esc(b.table) + " (" + n(b.badN) + ')</summary><div><div class="tablebox" style="max-height:300px"><table><tr><th>الصف</th><th>الحساب</th><th>العملة</th><th>في ملفك</th><th>رصيدنا</th><th class="num">الفرق</th></tr>' +
        b.bad.map(function (x) { var d = decOf(x.code); return "<tr><td>" + x.row + "</td><td>" + esc(x.account) + '</td><td class="ltr">' + x.code + "</td><td>" + balCell(x.file, d) + "</td><td>" + balCell(x.ours, d) + '</td><td class="num">' + C.fmtMinor(x.ours - x.file, d) + "</td></tr>"; }).join("") +
        '</table></div><p class="hint">أول صف لا يطابق غالبًا هو موضع الخطأ: مبلغ أو اتجاه مكتوب خطأ، أو رصيد مكتوب يدويًا.</p></div></details>';
    });
    (m.summaryChecks || []).forEach(function (s) {
      var bad = s.rows.filter(function (x) { return !x.ok; });
      if (!bad.length) return;
      html += '<details class="box" data-k="sbad-' + s.tid + '"><summary>أرصدة «' + esc(s.table) + "» التي لا تطابق (" + n(bad.length) + ')</summary><div><div class="tablebox" style="max-height:300px"><table><tr><th>الصف</th><th>الحساب</th><th>العملة</th><th>في ملفك</th><th>بعد النقل</th></tr>' +
        bad.map(function (x) { var d = decOf(x.code); return "<tr><td>" + x.row + "</td><td>" + esc(x.account) + (x.known ? "" : ' <span class="chip bad">ليس في الحركات</span>') + '</td><td class="ltr">' + x.code + "</td><td>" + balCell(x.file, d) + "</td><td>" + balCell(x.ours, d) + "</td></tr>"; }).join("") +
        "</table></div></div></details>";
    });
    if (m.impliedOpenings.length) {
      html += '<details class="box" data-k="implied"><summary>أرصدة سابقة مستنتجة من عمود الرصيد (' + n(m.impliedOpenings.length) + ')</summary><div><p class="hint">حسابات بدأت في ملفك برصيد غير مكتوب كحركة: رصيد أول صف ≠ مبلغه. أُضيف الفرق «رصيدًا سابقًا» قبل أول حركة.</p><div class="tablebox" style="max-height:260px"><table><tr><th>الحساب</th><th>العملة</th><th>الرصيد السابق</th><th>من</th></tr>' +
        m.impliedOpenings.map(function (x) { return "<tr><td>" + esc(x.office) + '</td><td class="ltr">' + x.code + "</td><td>" + balCell(x.minor, decOf(x.code)) + "</td><td>" + esc(x.sheet) + " صف " + x.row + "</td></tr>"; }).join("") +
        "</table></div></div></details>";
    }
    html += '<div class="stats">' + stat(n(T.offices.length), "مكتب") + stat(n(T.tx.length), "حركة") + stat(n(T.opening_balances.length), "رصيد سابق") +
      stat(n(rep.rows.filter(function (x) { return x.balance !== 0n; }).length), "رصيدًا غير صفري") + "</div>";
    if (m.warnings.length) html += '<div class="msg warn"><b>ملاحظات:</b><ul>' + m.warnings.map(function (w) { return "<li>" + esc(w) + "</li>"; }).join("") + "</ul></div>";
    // الأرصدة
    var hasExp = rep.rows.some(function (x) { return x.expected !== null; });
    html += '<h3>أرصدة المكاتب بعد النقل</h3><div class="row" style="margin-bottom:8px"><input type="text" id="q" class="search" placeholder="ابحث باسم المكتب…" value="' + esc(S.q) + '">' +
      '<span class="hint" id="qn"></span></div><div class="tablebox"><table id="balTable"><tr><th>المكتب</th><th>العملة</th><th class="num">حركات</th><th class="num">رصيد سابق</th><th class="num">مجموع لنا</th><th class="num">مجموع علينا</th><th>الرصيد النهائي</th>' +
      (hasExp ? "<th>في ملفك</th><th></th>" : "") + "</tr>";
    var txByKey = {};
    m.txs.forEach(function (x) { var k = x.office + "|" + x.code; txByKey[k] = (txByKey[k] || 0) + 1; });
    var oIndex = {};
    m.offices.forEach(function (o, i) { oIndex[o.name] = i; });
    rep.rows.forEach(function (x) {
      html += '<tr data-o="' + esc(C.looseKey(x.office)) + '"><td>' + esc(x.office) + '</td><td class="ltr">' + x.code + '</td><td class="num">' + n(txByKey[oIndex[x.office] + "|" + x.code] || 0) +
        '</td><td class="num">' + bw(x.opening, x.dec) + '</td><td class="num">' + C.fmtMinor(x.debit, x.dec) + '</td><td class="num">' + C.fmtMinor(x.credit, x.dec) + "</td><td>" + balCell(x.balance, x.dec) + "</td>" +
        (hasExp ? "<td>" + (x.expected === null ? "" : balCell(x.expected, x.dec)) + "</td><td>" + (x.match === null ? "" : x.match ? '<span class="chip ok">مطابق</span>' : '<span class="chip bad">غير مطابق</span>') + "</td>" : "") + "</tr>";
    });
    html += "</table></div>";
    html += "<h3>الإجمالي لكل عملة</h3><div class=\"tablebox\"><table><tr><th>العملة</th><th class=\"num\">مجموع ما لنا</th><th>مكاتب</th><th class=\"num\">مجموع ما علينا</th><th>مكاتب</th><th>الصافي</th></tr>";
    rep.totals.forEach(function (t) {
      html += '<tr><td class="ltr">' + t.code + '</td><td class="num us">' + C.fmtMinor(t.receivable, t.dec) + "</td><td>" + t.nr + '</td><td class="num them">' + C.fmtMinor(t.payable, t.dec) +
        "</td><td>" + t.np + "</td><td>" + balCell(t.receivable - t.payable, t.dec) + "</td></tr>";
    });
    html += "</table></div>";
    // أسماء المكاتب
    html += '<details class="box" data-k="names"><summary>أسماء المكاتب في التطبيق (' + n(m.offices.length) + ')<span class="hint">تعديل أو توحيد</span></summary><div><p class="hint">عدّل الاسم إن أردت؛ إعطاء حسابين الاسم نفسه يجمعهما في مكتب واحد. المكتب الموجود في التطبيق بالاسم نفسه تُضاف إليه الحركات عند «إضافة إلى بياناتي».</p>' +
      '<div class="tablebox" style="max-height:340px"><table><tr><th>الاسم في الملف</th><th>الاسم في التطبيق</th><th class="num">حركات</th><th class="num">أرصدة سابقة</th></tr>';
    m.offices.forEach(function (o) {
      Object.keys(o.variants).forEach(function (v, i) {
        var k = X.key(v);
        html += '<tr data-o="' + esc(C.looseKey(o.name)) + '"><td>' + esc(v) + '</td><td><input type="text" data-rename="' + esc(k) + '" value="' + esc(S.cfg.rename[k] || o.name) + '"></td><td class="num">' +
          (i === 0 ? n(o.tx) : "") + '</td><td class="num">' + (i === 0 ? n(o.openings) : "") + "</td></tr>";
      });
    });
    html += "</table></div></div></details>";
    // أول الحركات
    if (T.tx.length) {
      var names = {}, dec = {};
      T.offices.forEach(function (o) { names[o.id] = o.name; });
      T.currencies.forEach(function (c) { dec[c.code] = c.decimals; });
      html += '<details class="box" data-k="sample"><summary>أول الحركات كما ستظهر في التطبيق</summary><div><div class="tablebox" style="max-height:300px"><table><tr><th>التاريخ</th><th>المكتب</th><th>النوع</th><th class="num">المبلغ</th><th>البيان</th><th>ملاحظات</th></tr>';
      T.tx.slice(0, 30).forEach(function (t) {
        var us = t.type.indexOf("لنا") >= 0;
        html += "<tr><td>" + C.fmtDate(t.created_at) + "</td><td>" + esc(names[t.office_id]) + '</td><td><span class="chip ' + (us ? "us" : "them") + '">' + (us ? "لنا" : "علينا") + '</span></td><td class="num">' +
          C.fmtMinor(BigInt(t.amount_minor), dec[t.currency]) + " " + t.currency + "</td><td>" + esc(String(t.description).slice(0, 50)) + '</td><td class="hint">' + esc(String(t.notes).slice(0, 80)) + "</td></tr>";
      });
      html += "</table></div></div></details>";
    }
    box.innerHTML = html;
    filter();
  }

  function filter() {
    var q = C.looseKey(S.q || "");
    var shown = 0, total = 0;
    document.querySelectorAll("#review tr[data-o]").forEach(function (tr) {
      var hit = !q || tr.dataset.o.indexOf(q) >= 0;
      tr.style.display = hit ? "" : "none";
      if (tr.closest("#balTable")) { total++; if (hit) shown++; }
    });
    var qn = $("qn");
    if (qn) qn.textContent = q ? shown + " من " + total : "";
  }

  // ===================================================================================
  // الإنشاء
  // ===================================================================================

  function updateMake() {
    var r = S.result;
    var ok = !!r && !r.error && (r.T.tx.length + r.T.opening_balances.length) > 0;
    var pw1 = $("pw1").value, pw2 = $("pw2").value;
    $("make").disabled = !(ok && $("confirmed").checked && pw1.length >= 6 && pw1 === pw2);
    $("csv").disabled = !ok;
    var mm = $("makeMsg");
    if (!ok) { if (mm.dataset.done !== "1") mm.innerHTML = ""; }
    else if (pw1 && pw2 && pw1 !== pw2) mm.innerHTML = '<div class="msg err">كلمتا السر غير متطابقتين.</div>';
    else if (pw1 && pw1.length < 6) mm.innerHTML = '<div class="msg warn">كلمة السر قصيرة: 6 أحرف على الأقل.</div>';
    else if (mm.dataset.done !== "1") mm.innerHTML = "";
  }

  function download(bytes, name, type) {
    var blob = new Blob([bytes], { type: type || "application/octet-stream" });
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1500);
  }

  function stamp() {
    var d = new Date();
    function p(x) { return (x < 10 ? "0" : "") + x; }
    return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + "-" + p(d.getHours()) + p(d.getMinutes());
  }

  function make() {
    var r = S.result;
    if (!r || r.error) return;
    var mm = $("makeMsg");
    $("make").disabled = true;
    mm.dataset.done = "";
    mm.innerHTML = '<div class="msg info">جارٍ إنشاء الملف وتشفيره… (قد يستغرق بضع ثوانٍ)</div>';
    S.cfg.now = Date.now();
    var tc = tablesCfg();
    var T = C.tables(r.model, tc, rand);
    if (C.checkBalanced(T).length) { mm.innerHTML = '<div class="msg err">قيد غير متوازن — لم يُنشأ الملف.</div>'; return; }
    C.makeBackup(T, tc, $("pw1").value, crypto.subtle, rand).then(function (bytes) {
      var name = "استيراد-ذكي-" + stamp() + ".sba";
      download(bytes, name);
      mm.dataset.done = "1";
      mm.innerHTML = '<div class="msg ok"><b>✓ تم إنشاء «' + esc(name) + "»</b> (" + n(Math.ceil(bytes.length / 1024)) + " ك.ب) — " + n(T.offices.length) + " مكتب، " + n(T.tx.length) + " حركة، " +
        n(T.opening_balances.length) + " رصيد سابق.<ul><li>انقله إلى الهاتف (واتساب لنفسك، أو البريد، أو كابل).</li>" +
        "<li>في التطبيق: المزيد ← مساعد النقل من برنامج آخر ← «3. استورد الملف هنا»، واكتب كلمة السر، ثم اختر «إضافة إلى بياناتي» حتى لا يُحذف شيء من بياناتك الحالية.</li>" +
        "<li>قارن أرصدة المكاتب في التطبيق بتقرير الأرصدة (زر «تنزيل تقرير الأرصدة»).</li><li>لا تنسَ كلمة السر: لا يمكن فتح الملف بدونها.</li></ul></div>";
      updateMake();
    }).catch(function (e) {
      mm.innerHTML = '<div class="msg err">تعذر إنشاء الملف: ' + esc(e.message || e) + "</div>";
      updateMake();
    });
  }

  function csv() {
    var r = S.result;
    if (!r || r.error) return;
    var m = r.model, errors = [], skipped = m.skipped.slice();
    Object.keys(m.issues).forEach(function (k) {
      var g = m.issues[k];
      g.rows.forEach(function (x) { (g.counted ? skipped : errors).push(g.counted ? { sheet: x.sheet, row: x.row, reason: g.label } : { sheet: x.sheet, row: x.row, msg: g.label }); });
    });
    download(C.reportCsv(r.rep, { errors: errors, skipped: skipped }), "تقرير-الأرصدة-" + stamp() + ".csv", "text/csv;charset=utf-8");
  }

  // ===================================================================================
  // الأحداث
  // ===================================================================================

  function newCode() {
    var c = (prompt("رمز العملة بالإنجليزية (مثل GOLD أو USDT):") || "").trim().toUpperCase();
    if (!/^[A-Z0-9]{2,10}$/.test(c)) { if (c) alert("الرمز يجب أن يكون من 2 إلى 10 أحرف إنجليزية أو أرقام."); return null; }
    var name = (prompt("اسم العملة بالعربية:", c) || c).trim();
    S.cfg.currencies[c] = { name: name || c, dec: 2, userSet: false };
    return c;
  }

  function setAccountFrom(t, from, name) {
    S.accFrom[t.id] = { from: from, name: name || "" };
    applyAccountFrom(t);
  }

  function applyAccountFrom(t) {
    var af = S.accFrom[t.id];
    if (!af || t.roles.account !== undefined) return;
    var src = af.from === "sheet" ? t.fromSheet : af.from === "file" ? t.fromFile : null;
    var nm = src ? src.name : af.from === "custom" ? C.clean(af.name) : "";
    t.accountFrom = af.from || null;
    t.sheetAccount = nm;
    // عملة مكتوبة في الاسم المختار (إن لم يحدد المستخدم عملة الجدول بنفسه)
    t.sheetCurrency = src && t.roles.currency === undefined ? src.code : null;
    if (t.sheetCurrency && !S.cfg.fixedCurrencyUser[t.id]) S.cfg.fixedCurrency[t.id] = t.sheetCurrency;
    t.accountFromSheet = !!nm;
    if (nm && t.kind === "journal-noaccount") { t.kind = "journal"; t.use = true; }
    if (!nm && t.kind === "journal") { t.kind = "journal-noaccount"; t.use = false; }
  }

  function changed(full) {
    refreshValues();
    later(full);
  }

  document.addEventListener("change", function (e) {
    var t = e.target, d = t.dataset;
    if ((t.id === "file" || t.id === "file2") && t.files.length) { var fl = Array.prototype.slice.call(t.files); t.value = ""; readFiles(fl); return; }
    if (t.id === "confirmed") { updateMake(); return; }
    if (!S.a) return;
    var val = t.value;
    if (t.tagName === "SELECT" && val === "__new") {
      var c = newCode();
      if (!c) { renderValues(); renderUnderstand(); return; }
      val = c;
    }
    if (d.use) { var tu = tableById(d.use); tu.use = t.checked; changed(true); return; }
    if (d.listed) { S.cfg.listedAccounts = t.checked; later(false); return; }
    if (d.role) {
      var p = d.role.split("|"), tr = tableById(p[0]), role = p[1];
      var col = val === "" ? undefined : +val;
      if (col !== undefined) ROLE_ORDER.forEach(function (k) { if (k !== role && tr.roles[k] === col) delete tr.roles[k]; });
      if (col !== undefined && tr.roles.wide) tr.roles.wide = tr.roles.wide.filter(function (w) { return w.c !== col; });
      if (tr.roles.wide && !tr.roles.wide.length) delete tr.roles.wide;
      if (col === undefined) delete tr.roles[role]; else tr.roles[role] = col;
      // الاتجاه أو المبلغ يلغي عمودي لنا/علينا والعكس
      if ((role === "debit" || role === "credit") && col !== undefined) delete tr.roles.direction;
      X.reclassify(tr);
      applyAccountFrom(tr);
      changed(true);
      return;
    }
    if (d.accfrom) { setAccountFrom(tableById(d.accfrom), val, (S.accFrom[d.accfrom] || {}).name); changed(true); return; }
    if (d.accname) { setAccountFrom(tableById(d.accname), "custom", val); changed(true); return; }
    if (d.fixcur) { S.cfg.fixedCurrency[d.fixcur] = val; S.cfg.fixedCurrencyUser[d.fixcur] = true; changed(true); return; }
    if (d.dirmap !== undefined) { S.cfg.dirMap[d.dirmap] = val; later(false); return; }
    if (d.curmap !== undefined) { S.cfg.curMap[d.curmap] = val; changed(true); return; }
    if (d.curname) { S.cfg.currencies[d.curname].name = val; later(false); return; }
    if (d.curdec) { S.cfg.currencies[d.curdec].dec = +val; S.cfg.currencies[d.curdec].userSet = true; later(false); return; }
    if (d.rename !== undefined) { S.cfg.rename[d.rename] = val.trim(); later(false); return; }
    if (d.infer) { S.cfg.inferOpening = t.checked; later(false); return; }
    if (d.odate) {
      S.cfg.openingDateStr = val;
      if (val) { var q = val.split("-"); S.cfg.openingDate = new Date(+q[0], +q[1] - 1, +q[2], 12, 0, 0).getTime(); } else S.cfg.openingDate = null;
      later(false);
      return;
    }
    if (t.type === "radio") {
      var pr = t.name.split("|");
      if (pr[0] === "pos") { S.cfg.positiveIs[pr[1]] = val; later(false); return; }
      if (pr[0] === "fee") { S.cfg.feeDir = S.cfg.feeDir || {}; S.cfg.feeDir[pr[1]] = val; later(false); return; }
      if (pr[0] === "bsign") { if (val) S.cfg.balanceSign[pr[1]] = val; else delete S.cfg.balanceSign[pr[1]]; later(false); return; }
    }
  });
  document.addEventListener("input", function (e) {
    if (e.target.id === "q") { S.q = e.target.value; filter(); }
  });
  ["pw1", "pw2"].forEach(function (id) { $(id).addEventListener("input", updateMake); });
  $("make").addEventListener("click", make);
  $("csv").addEventListener("click", csv);
  var drop = $("drop");
  ["dragenter", "dragover"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add("over"); }); });
  ["dragleave", "drop"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove("over"); }); });
  drop.addEventListener("drop", function (e) { if (e.dataTransfer.files.length) readFiles(e.dataTransfer.files); });
  if (!window.crypto || !crypto.subtle || typeof BigInt === "undefined") {
    $("fileInfo").className = "msg err";
    $("fileInfo").textContent = "هذا المتصفح قديم ولا يدعم التشفير المطلوب. افتح الأداة بأحدث نسخة من Chrome أو Edge أو Firefox.";
  }
})();
