/* أداة النقل — الواجهة. المنطق المحاسبي كله في core.js */
"use strict";
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var C = Core;
  var S = {
    fileName: "", sheets: [], rows: {},
    open: { enabled: false, sheet: "", headerRow: 0, cols: {}, layout: "single", currencyFrom: "column", fixedCurrency: "USD", positiveIs: "D", wide: [], date: "" },
    tx: { enabled: false, sheets: [], officeFrom: "column", headerRow: 0, cols: {}, descCols: [], layout: "split", currencyFrom: "column", fixedCurrency: "USD",
      positiveIs: "D", dateOrder: "dmy", fillDate: true, feeDir: "D", balancePositiveIs: "D" },
    currencyMap: {}, currencyDefs: {}, typeMap: {}, rename: {}, mergeSimilar: false, reverse: false,
    result: null,
    // كشوفات الحساب (ملف لكل مكتب وعملة)
    mode: "single", stmts: [], stmtFiles: [], otherFiles: [],
    st: { balancesOnly: false, mergeCom: false, positiveIs: "C" }
  };
  var ACCEPT = ".xlsx,.xlsm,.xls,.csv,.txt,.tsv,.ods,.xml,.htm,.html";

  function esc(s) {
    return String(s === null || s === undefined ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; });
  }
  function el(html) { var t = document.createElement("template"); t.innerHTML = html.trim(); return t.content.firstChild; }
  function colName(i) { var s = ""; i++; while (i > 0) { var m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); } return s; }
  function headers(rows, hr) { return (rows[hr] || []).map(function (c, i) { var t = C.text(c); return { i: i, label: colName(i) + (t ? " — " + t : "") }; }); }
  function width(rows, hr) { var w = 0; for (var r = hr; r < Math.min(rows.length, hr + 60); r++) w = Math.max(w, (rows[r] || []).length); return w; }
  function colOptions(rows, hr, sel, optional) {
    var h = headers(rows, hr);
    var w = width(rows, hr);
    for (var i = h.length; i < w; i++) h.push({ i: i, label: colName(i) });
    var o = optional ? '<option value="">— لا يوجد —</option>' : '<option value="">— اختر —</option>';
    return o + h.map(function (x) { return '<option value="' + x.i + '"' + (sel === x.i ? " selected" : "") + ">" + esc(x.label) + "</option>"; }).join("");
  }
  function codeOptions(sel, extra) {
    var codes = Object.keys(C.KNOWN);
    Object.keys(S.currencyDefs).forEach(function (c) { if (codes.indexOf(c) < 0) codes.push(c); });
    var o = (extra || "") + codes.map(function (c) {
      var d = S.currencyDefs[c] || C.KNOWN[c];
      return '<option value="' + c + '"' + (sel === c ? " selected" : "") + ">" + c + " — " + esc(d ? d.name : c) + "</option>";
    }).join("");
    return o + '<option value="__new">رمز آخر…</option>';
  }
  function seg(name, opts, val) {
    return '<div class="seg">' + opts.map(function (o) {
      return '<label><input type="radio" name="' + name + '" value="' + o[0] + '"' + (o[0] === val ? " checked" : "") + ">" + esc(o[1]) + "</label>";
    }).join("") + "</div>";
  }
  function num(v) { return v === "" || v === null || v === undefined ? undefined : +v; }

  // ===================================================================================
  // 1. قراءة الملف
  // ===================================================================================

  function decodeText(buf) {
    try { return new TextDecoder("utf-8", { fatal: true }).decode(buf); } catch (e) { return new TextDecoder("windows-1256").decode(buf); }
  }

  /** يقرأ ملفًا إلى أوراق وصفوف. */
  function readBook(file) {
    return file.arrayBuffer().then(function (ab) {
      var buf = new Uint8Array(ab);
      var wb;
      var lower = file.name.toLowerCase();
      if (/\.(csv|txt|tsv)$/.test(lower)) {
        var text = decodeText(buf).replace(/^\uFEFF/, "");
        var semi = (text.split("\n")[0].match(/;/g) || []).length > (text.split("\n")[0].match(/,/g) || []).length;
        wb = XLSX.read(text, { type: "string", raw: true, FS: lower.endsWith(".tsv") ? "\t" : (semi ? ";" : undefined) });
      } else {
        wb = XLSX.read(buf, { type: "array", cellNF: true, cellDates: false });
      }
      return { name: file.name, sheets: wb.SheetNames.map(function (n) { return { name: n, rows: C.sheetRows(wb.Sheets[n], XLSX) }; }) };
    });
  }

  /**
   * ملف واحد بجدول عام ← الإعداد اليدوي (الخطوة 2). ملف أو أكثر فيه كشوفات حساب ← وضع الكشوفات:
   * كل كشف يُقرأ ويُتحقق منه وحده، ثم تُجمع عملات الحساب الواحد في مكتب واحد.
   * append: إضافة ملفات إلى الكشوفات الحالية (ملف بالاسم نفسه يُستبدل).
   */
  function readFiles(list, append) {
    var files = Array.prototype.slice.call(list || []);
    if (!files.length) return;
    var info = $("fileInfo");
    info.className = "msg info";
    info.textContent = files.length > 1 ? "جارٍ قراءة " + files.length + " ملف…" : "جارٍ قراءة الملف…";
    Promise.all(files.map(function (f) {
      return readBook(f).catch(function (e) { return { name: f.name, error: e && e.message ? e.message : String(e) }; });
    })).then(function (books) {
      var found = [], plain = [], failed = [];
      books.forEach(function (b) {
        if (b.error) { failed.push(b); return; }
        var blocks = C.parseStatements(b.name, b.sheets);
        if (C.isStatementFile(blocks)) found.push({ name: b.name, sheets: b.sheets.length, blocks: blocks });
        else plain.push(b);
      });
      if (!found.length && !append && books.length === 1 && plain.length === 1) { loadSingle(plain[0]); return; }
      if (!found.length && !(append && S.mode === "statements")) {
        info.className = "msg err";
        info.innerHTML = "لم نتعرف على كشوفات حساب في الملفات المختارة." + (failed.length ? " تعذرت قراءة: " + failed.map(function (f) { return esc(f.name); }).join("، ") + "." : "") +
          " لنقل جدول عام (أرصدة أو حركات) اختر ملفًا واحدًا فقط.";
        return;
      }
      loadStatements(found, plain.map(function (b) { return b.name; }).concat(failed.map(function (b) { return b.name + " (تعذرت قراءته)"; })), append);
    });
  }

  function loadSingle(book) {
    S.mode = "single";
    S.fileName = book.name;
    S.sheets = book.sheets.map(function (x) { return x.name; });
    S.rows = {};
    book.sheets.forEach(function (x) { S.rows[x.name] = x.rows; });
    afterLoad();
  }

  function loadStatements(found, others, append) {
    var keep = append && S.mode === "statements" ? S.stmtFiles.filter(function (f) { return !found.some(function (n) { return n.name === f.name; }); }) : [];
    S.mode = "statements";
    S.stmtFiles = keep.concat(found);
    S.stmts = [];
    S.stmtFiles.forEach(function (f) { S.stmts = S.stmts.concat(f.blocks); });
    S.otherFiles = (append ? S.otherFiles : []).concat(others || []);
    S.fileName = S.stmtFiles.length === 1 ? S.stmtFiles[0].name : S.stmtFiles.length + " كشف حساب";
    S.result = null;
    var info = $("fileInfo");
    info.className = "msg ok";
    info.innerHTML = "✓ قُرئ " + S.stmtFiles.length + " ملف فيها <b>" + S.stmts.length + " كشف حساب</b>." +
      (S.otherFiles.length ? '<br><span class="err-t">لم يُتعرف على هذه الملفات ككشوفات ولن تُنقل: ' + S.otherFiles.map(esc).join("، ") + "</span>" : "");
    $("step1").classList.add("done");
    ["step2", "step3", "step4", "step5"].forEach(function (x) { $(x).classList.remove("off"); });
    renderAll();
  }

  function dataRows(n) { return (S.rows[n] || []).filter(function (r) { return !C.isBlankRow(r); }).length; }

  function afterLoad() {
    $("stmtPanel").classList.add("hidden");
    $("singleSetup").classList.remove("hidden");
    var info = S.sheets.map(function (n) { return "«" + esc(n) + "»: " + dataRows(n) + " صف"; }).join(" • ");
    $("fileInfo").className = "msg ok";
    $("fileInfo").innerHTML = "✓ قُرئ الملف <b>" + esc(S.fileName) + "</b> — " + S.sheets.length + " ورقة: " + info;
    $("step1").classList.add("done");
    ["step2", "step3", "step4", "step5"].forEach(function (s) { $(s).classList.remove("off"); });
    // اقتراحات أولية
    var biggest = S.sheets.slice().sort(function (a, b) { return dataRows(b) - dataRows(a); })[0];
    var withDates = S.sheets.filter(function (n) {
      var hr = C.guessHeaderRow(S.rows[n]);
      var g = C.guessColumns(S.rows[n][hr] || []);
      return g.date !== undefined;
    });
    S.tx.sheets = withDates.length ? withDates.slice(0, withDates.length === S.sheets.length ? S.sheets.length : 1) : [biggest];
    S.tx.officeFrom = "column";
    var noDate = S.sheets.filter(function (n) { return withDates.indexOf(n) < 0; });
    S.open.sheet = noDate[0] || biggest;
    S.tx.enabled = withDates.length > 0;
    S.open.enabled = !S.tx.enabled || noDate.length > 0;
    $("openOn").checked = S.open.enabled;
    $("txOn").checked = S.tx.enabled;
    guessOpen(true);
    guessTx(true);
    renderAll();
  }

  // ===================================================================================
  // 2. الأقسام
  // ===================================================================================

  function guessOpen(reset) {
    var rows = S.rows[S.open.sheet] || [];
    if (reset) S.open.headerRow = C.guessHeaderRow(rows);
    var g = C.guessColumns(rows[S.open.headerRow] || []);
    var o = S.open;
    o.cols = { office: g.office !== undefined ? g.office : 0, phone: g.phone, currency: g.currency, amount: g.balance !== undefined ? g.balance : g.amount, debit: g.debit, credit: g.credit };
    // أعمدة بأسماء عملات = «عمود لكل عملة»
    var wide = [];
    (rows[o.headerRow] || []).forEach(function (c, i) {
      var code = C.guessCurrency(C.text(c));
      if (code && i !== o.cols.office) wide.push({ col: i, code: code });
    });
    o.wide = wide;
    if (wide.length >= 1 && g.currency === undefined) o.layout = "wide";
    else if (g.debit !== undefined && g.credit !== undefined) o.layout = "split";
    else o.layout = "single";
    o.currencyFrom = g.currency !== undefined ? "column" : "fixed";
  }

  function guessTx(reset) {
    var first = S.tx.sheets[0];
    var rows = S.rows[first] || [];
    if (reset) S.tx.headerRow = C.guessHeaderRow(rows);
    var g = C.guessColumns(rows[S.tx.headerRow] || []);
    var t = S.tx;
    t.cols = g;
    t.descCols = g.description !== undefined ? [g.description] : [];
    if (g.debit !== undefined && g.credit !== undefined) t.layout = "split";
    else if (g.type !== undefined) t.layout = "typed";
    else t.layout = "signed";
    t.currencyFrom = g.currency !== undefined ? "column" : "fixed";
    if (t.officeFrom !== "sheet" && g.office === undefined && S.tx.sheets.length > 1) t.officeFrom = "sheet";
    var dates = [];
    S.tx.sheets.forEach(function (n) { (S.rows[n] || []).slice(t.headerRow + 1, t.headerRow + 400).forEach(function (r) { if (g.date !== undefined && r[g.date]) dates.push(r[g.date]); }); });
    t.dateOrder = C.guessDateOrder(dates);
  }

  function preview(rows, hr, hi) {
    var w = Math.min(width(rows, hr), 14);
    var h = "<tr><th>#</th>";
    for (var c = 0; c < w; c++) h += "<th>" + colName(c) + "</th>";
    h += "</tr>";
    var start = Math.max(0, hr - 2), end = Math.min(rows.length, hr + 8);
    for (var r = start; r < end; r++) {
      var cls = r === hr ? "hdr" : r < hr ? "skipzone" : "";
      h += '<tr class="' + cls + '"><td>' + (r + 1) + "</td>";
      for (var k = 0; k < w; k++) {
        var cell = (rows[r] || [])[k];
        var t = C.text(cell);
        var isNum = cell && (cell.n !== undefined);
        h += "<td" + (isNum ? ' class="num"' : "") + (hi && hi.indexOf(k) >= 0 ? ' style="background:#FFF6DF"' : "") + ">" + esc(t.length > 40 ? t.slice(0, 40) + "…" : t) + "</td>";
      }
      h += "</tr>";
    }
    return '<div class="tablebox" style="max-height:260px"><table>' + h + "</table></div>";
  }

  function renderOpen() {
    var o = S.open, p = $("openPanel");
    p.classList.toggle("hidden", !o.enabled);
    if (!o.enabled || !S.sheets.length) return;
    var rows = S.rows[o.sheet] || [];
    var hr = o.headerRow;
    var html = '<div class="grid">' +
      '<label class="f">الورقة<select data-o="sheet">' + S.sheets.map(function (n) { return "<option" + (n === o.sheet ? " selected" : "") + ">" + esc(n) + "</option>"; }).join("") + "</select></label>" +
      '<label class="f">صف العناوين <small>رقم الصف الذي فيه أسماء الأعمدة</small><input type="number" min="1" data-o="headerRow" value="' + (hr + 1) + '"></label>' +
      '<label class="f">عمود اسم المكتب<select data-oc="office">' + colOptions(rows, hr, o.cols.office) + "</select></label>" +
      '<label class="f">عمود الهاتف <small>اختياري</small><select data-oc="phone">' + colOptions(rows, hr, o.cols.phone, true) + "</select></label>" +
      "</div>";
    html += "<h3>شكل الأرصدة في الورقة</h3>" + seg("olayout", [["single", "عمود رصيد واحد"], ["split", "عمودان: لنا / علينا"], ["wide", "عمود لكل عملة"]], o.layout);
    html += '<div class="grid" style="margin-top:10px">';
    if (o.layout === "single") html += '<label class="f">عمود الرصيد<select data-oc="amount">' + colOptions(rows, hr, o.cols.amount) + "</select></label>";
    if (o.layout === "split") {
      html += '<label class="f">عمود «لنا» <small>مدين / عليه</small><select data-oc="debit">' + colOptions(rows, hr, o.cols.debit) + "</select></label>";
      html += '<label class="f">عمود «علينا» <small>دائن / له</small><select data-oc="credit">' + colOptions(rows, hr, o.cols.credit) + "</select></label>";
    }
    if (o.layout !== "wide") {
      html += '<label class="f">العملة' + seg("ocurfrom", [["column", "من عمود"], ["fixed", "عملة واحدة"]], o.currencyFrom) + "</label>";
      html += o.currencyFrom === "column" ? '<label class="f">عمود العملة<select data-oc="currency">' + colOptions(rows, hr, o.cols.currency) + "</select></label>"
        : '<label class="f">العملة الثابتة<select data-o="fixedCurrency">' + codeOptions(o.fixedCurrency) + "</select></label>";
    }
    html += "</div>";
    if (o.layout === "wide") {
      html += '<p class="hint">حدّد عملة كل عمود رصيد، أو «تجاهل» للأعمدة الأخرى.</p><div class="grid">';
      var w = width(rows, hr);
      for (var c = 0; c < w; c++) {
        if (c === o.cols.office || c === o.cols.phone) continue;
        var cur = (o.wide.filter(function (x) { return x.col === c; })[0] || {}).code || "";
        html += '<label class="f">' + esc(colName(c) + " — " + C.text((rows[hr] || [])[c])) + '<select data-wide="' + c + '">' + codeOptions(cur, '<option value="">تجاهل هذا العمود</option>') + "</select></label>";
      }
      html += "</div>";
    }
    if (o.layout !== "split") html += "<h3>الرصيد الموجب في الملف يعني</h3>" + seg("opos", [["D", "لنا — المكتب مدين لنا"], ["C", "علينا — نحن مدينون للمكتب"]], o.positiveIs);
    html += '<div class="grid" style="margin-top:10px"><label class="f">تاريخ هذه الأرصدة <small>اتركه فارغًا: قبل أول حركة منقولة بيوم، أو اليوم</small><input type="date" data-o="date" value="' + esc(o.date) + '"></label></div>';
    html += "<h3>معاينة</h3>" + preview(rows, hr, [o.cols.office, o.cols.amount, o.cols.debit, o.cols.credit, o.cols.currency].concat(o.wide.map(function (x) { return x.col; })));
    p.innerHTML = html;
  }

  function renderTx() {
    var t = S.tx, p = $("txPanel");
    p.classList.toggle("hidden", !t.enabled);
    if (!t.enabled || !S.sheets.length) return;
    var rows = S.rows[t.sheets[0]] || [];
    var hr = t.headerRow;
    var html = "<h3>الأوراق</h3><div class=\"row\">" + S.sheets.map(function (n) {
      return '<label class="check" style="margin:0 0 0 14px"><input type="checkbox" data-sheet="' + esc(n) + '"' + (t.sheets.indexOf(n) >= 0 ? " checked" : "") + ">" + esc(n) + " <span class=\"hint\">(" + dataRows(n) + ")</span></label>";
    }).join("") + "</div>";
    if (t.sheets.length > 1) html += '<p class="hint">الأوراق المحددة يجب أن تكون بالأعمدة نفسها وبالترتيب نفسه.</p>';
    html += "<h3>اسم المكتب</h3>" + seg("tofrom", [["column", "من عمود في الجدول"], ["sheet", "اسم الورقة (كل ورقة مكتب)"]], t.officeFrom);
    html += '<div class="grid" style="margin-top:10px">' +
      '<label class="f">صف العناوين<input type="number" min="1" data-t="headerRow" value="' + (hr + 1) + '"></label>' +
      (t.officeFrom === "column" ? '<label class="f">عمود المكتب<select data-tc="office">' + colOptions(rows, hr, t.cols.office) + "</select></label>" : "") +
      '<label class="f">عمود التاريخ<select data-tc="date">' + colOptions(rows, hr, t.cols.date) + "</select></label>" +
      '<label class="f">ترتيب التاريخ<select data-t="dateOrder">' + [["dmy", "يوم/شهر/سنة"], ["mdy", "شهر/يوم/سنة"], ["ymd", "سنة/شهر/يوم"]].map(function (x) {
        return '<option value="' + x[0] + '"' + (t.dateOrder === x[0] ? " selected" : "") + ">" + x[1] + "</option>"; }).join("") + "</select></label>" +
      "</div>";
    html += '<label class="check"><input type="checkbox" data-tb="fillDate"' + (t.fillDate ? " checked" : "") + "> إذا كان التاريخ فارغًا في صف فيه بيان أو مستفيد، استخدم تاريخ الصف السابق (شائع في دفاتر Excel)</label>";
    html += "<h3>المبلغ واتجاهه</h3>" + seg("tlayout", [["split", "عمودان: لنا / علينا"], ["typed", "مبلغ + عمود نوع"], ["signed", "مبلغ موجب/سالب"]], t.layout);
    html += '<div class="grid" style="margin-top:10px">';
    if (t.layout === "split") {
      html += '<label class="f">عمود «لنا» <small>مدين / عليه — المكتب مدين لنا</small><select data-tc="debit">' + colOptions(rows, hr, t.cols.debit) + "</select></label>";
      html += '<label class="f">عمود «علينا» <small>دائن / له — نحن مدينون للمكتب</small><select data-tc="credit">' + colOptions(rows, hr, t.cols.credit) + "</select></label>";
    } else {
      html += '<label class="f">عمود المبلغ<select data-tc="amount">' + colOptions(rows, hr, t.cols.amount) + "</select></label>";
      if (t.layout === "typed") html += '<label class="f">عمود النوع <small>تطابق قيمه في الخطوة 3</small><select data-tc="type">' + colOptions(rows, hr, t.cols.type) + "</select></label>";
    }
    html += '<label class="f">العملة' + seg("tcurfrom", [["column", "من عمود"], ["fixed", "عملة واحدة"]], t.currencyFrom) + "</label>";
    html += t.currencyFrom === "column" ? '<label class="f">عمود العملة<select data-tc="currency">' + colOptions(rows, hr, t.cols.currency) + "</select></label>"
      : '<label class="f">العملة الثابتة<select data-t="fixedCurrency">' + codeOptions(t.fixedCurrency) + "</select></label>";
    html += "</div>";
    if (t.layout === "signed") html += "<h3>المبلغ الموجب يعني</h3>" + seg("tpos", [["D", "لنا — المكتب مدين لنا"], ["C", "علينا — نحن مدينون للمكتب"]], t.positiveIs);
    html += '<h3>أعمدة اختيارية</h3><div class="grid">' +
      '<label class="f">المستفيد<select data-tc="person">' + colOptions(rows, hr, t.cols.person, true) + "</select></label>" +
      '<label class="f">البيان<select data-desc="0">' + colOptions(rows, hr, t.descCols[0], true) + "</select></label>" +
      '<label class="f">بيان إضافي <small>يُضاف للبيان</small><select data-desc="1">' + colOptions(rows, hr, t.descCols[1], true) + "</select></label>" +
      '<label class="f">الملاحظات<select data-tc="notes">' + colOptions(rows, hr, t.cols.notes, true) + "</select></label>" +
      '<label class="f">المرجع / رقم السند<select data-tc="ref">' + colOptions(rows, hr, t.cols.ref, true) + "</select></label>" +
      '<label class="f">الأجرة<select data-tc="fee">' + colOptions(rows, hr, t.cols.fee, true) + "</select></label>" +
      (t.cols.fee !== undefined ? '<label class="f">الأجرة' + seg("tfeedir", [["D", "لنا"], ["C", "علينا"]], t.feeDir) + "</label>" : "") +
      '<label class="f">عمود الرصيد <small>للتحقق فقط: يقارن آخر رصيد في الملف بما سيحسبه التطبيق</small><select data-tc="balance">' + colOptions(rows, hr, t.cols.balance, true) + "</select></label>" +
      "</div>";
    if (t.cols.balance !== undefined) html += "<h3>الرصيد الموجب في عمود الرصيد يعني</h3>" + seg("tbalpos", [["D", "لنا"], ["C", "علينا"]], t.balancePositiveIs);
    html += "<h3>معاينة (" + esc(t.sheets[0] || "") + ")</h3>" + preview(rows, hr, [t.cols.office, t.cols.date, t.cols.debit, t.cols.credit, t.cols.amount, t.cols.type, t.cols.currency]);
    p.innerHTML = html;
  }

  // ===================================================================================
  // 2ب. الكشوفات
  // ===================================================================================

  function dmy(d) { return d ? d.y + "/" + (d.m < 10 ? "0" : "") + d.m + "/" + (d.d < 10 ? "0" : "") + d.d : ""; }

  function decOf(code) {
    var d = code && (S.currencyDefs[code] || C.KNOWN[code]);
    return d ? d.dec : 2;
  }

  function renderStmt() {
    var on = S.mode === "statements";
    $("stmtPanel").classList.toggle("hidden", !on);
    $("singleSetup").classList.toggle("hidden", on);
    if (!on) return;
    var html = '<p class="lead" style="margin-top:0">كل كشف = حساب واحد بعملة واحدة. كشوفات الحساب نفسه بعملات مختلفة تُجمع في مكتب واحد (برمز الحساب). ' +
      "كل سطر يُتحقق منه بعمود الرصيد، وآخر الكشف بسطر «اجمالي الرصيد»؛ الكشف الذي لا يطابق لا يُنقل.</p>";
    html += '<div class="row"><label class="btn sec" style="cursor:pointer">إضافة ملفات أخرى<input type="file" id="moreFiles" multiple accept="' + ACCEPT + '" hidden></label>' +
      '<span class="hint">ملف بالاسم نفسه يحلّ محل السابق.</span></div>';
    html += "<h3>ما الذي يُنقل؟</h3>" + seg("stwhat", [["all", "الرصيد السابق وكل حركات الكشوفات"], ["bal", "الأرصدة النهائية فقط"]], S.st.balancesOnly ? "bal" : "all");
    html += '<p class="hint">' + (S.st.balancesOnly
      ? "رصيد واحد لكل مكتب وعملة بتاريخ نهاية كشفه، وتبدأ الحركات الجديدة في التطبيق. يكفيك لهذا كشف لفترة قصيرة (يوم واحد مثلًا)."
      : "«Pre Balance» رصيدًا سابقًا بتاريخ بداية الكشف، ثم كل حركة كما هي في برنامجك.") + "</p>";
    if (!S.st.balancesOnly) html += '<label class="check"><input type="checkbox" id="mergeCom"' + (S.st.mergeCom ? " checked" : "") +
      "> دمج سطور العمولة «Com …» مع حركتها في اليوم نفسه كأجرة (الأرصدة لا تتغير). اتركه دون تحديد لتبقى الحركات سطرًا بسطر كما في برنامجك.</label>";
    html += '<h3>الكشوفات (' + S.stmts.length + ')</h3><div id="stmtTable"></div>';
    $("stmtPanel").innerHTML = html;
    renderStmtTable();
  }

  function renderStmtTable() {
    var box = $("stmtTable");
    if (!box) return;
    var res = S.result && S.result.model && S.result.model.files ? S.result.model.files : [];
    var h = '<div class="tablebox" style="max-height:440px"><table><tr><th>الملف</th><th>الحساب</th><th>العملة في الملف</th><th>الفترة</th><th class="num">سطور</th><th>الرصيد النهائي</th><th>التحقق</th></tr>';
    S.stmts.forEach(function (st, i) {
      var r = res[i];
      var acc = st.account ? (st.account.code ? '<span class="ltr hint">' + esc(st.account.code) + "</span> " : "") + esc(st.account.name) : "";
      h += "<tr><td>" + esc(st.file) + "</td><td>" + (acc || '<span class="them">غير معروف</span>') + "</td><td>" + esc(st.curText || "—") + (r && r.code ? ' <span class="ltr hint">' + r.code + "</span>" : "") +
        '</td><td class="ltr">' + esc(dmy(st.from) + (st.to ? " → " + dmy(st.to) : "")) + '</td><td class="num">' + st.rows.length + "</td><td>" +
        (r && r.ok ? balCell(r.final, decOf(r.code)) : "") + "</td><td>" +
        (!r ? "" : r.ok ? '<span class="chip ok">✓ ' + r.checked + " رصيدًا مطابقًا" + (st.footer ? " + الإجمالي" : "") + "</span>" : '<span class="chip bad">' + esc(r.status) + "</span>") + "</td></tr>";
    });
    box.innerHTML = h + "</table></div>";
  }

  // ===================================================================================
  // 3. المطابقة
  // ===================================================================================

  function collect(sec, sheets, col) {
    var m = {};
    if (col === undefined) return m;
    sheets.forEach(function (n) {
      (S.rows[n] || []).slice(sec.headerRow + 1).forEach(function (r) {
        var v = C.text(r[col]);
        if (!v) return;
        var k = C.officeKey(v);
        m[k] = m[k] || { text: v, n: 0 };
        m[k].n++;
      });
    });
    return m;
  }

  function ensureDef(code) {
    if (!code || code === "__skip" || S.currencyDefs[code]) return;
    var k = C.KNOWN[code];
    S.currencyDefs[code] = { name: k ? k.name : code, dec: k ? k.dec : 2 };
  }

  function renderMap() {
    var box = $("mapBox");
    var html = "";
    // العملات
    var vals = {};
    var stm = S.mode === "statements";
    if (stm) {
      S.stmts.forEach(function (st) {
        if (!st.curText) return;
        var k = C.officeKey(st.curText);
        vals[k] = vals[k] || { text: st.curText, n: 0 };
        vals[k].n++;
      });
    }
    if (!stm && S.open.enabled && S.open.layout !== "wide" && S.open.currencyFrom === "column") Object.assign(vals, collect(S.open, [S.open.sheet], S.open.cols.currency));
    if (!stm && S.tx.enabled && S.tx.currencyFrom === "column") {
      var tv = collect(S.tx, S.tx.sheets, S.tx.cols.currency);
      Object.keys(tv).forEach(function (k) { if (vals[k]) vals[k].n += tv[k].n; else vals[k] = tv[k]; });
    }
    var keys = Object.keys(vals);
    keys.forEach(function (k) { if (!(k in S.currencyMap)) S.currencyMap[k] = C.guessCurrency(vals[k].text) || ""; });
    if (keys.length) {
      if (keys.length > 60) html += '<div class="msg warn">عمود العملة فيه ' + keys.length + " قيمة مختلفة؛ تأكد أنك اخترت عمود العملة الصحيح.</div>";
      html += '<h3>العملات كما وردت في ' + (stm ? "الكشوفات" : "الملف") + '</h3><div class="mapgrid"><span class="h">في الملف</span><span class="h">' + (stm ? "كشوفات" : "عدد") + '</span><span class="h">العملة في التطبيق</span>';
      keys.slice(0, 120).forEach(function (k) {
        var v = S.currencyMap[k];
        html += "<span>" + esc(vals[k].text) + "</span><span>" + vals[k].n + '</span><select data-curmap="' + esc(k) + '"' + (!v ? ' style="border-color:#AD2E36"' : "") + ">" +
          codeOptions(v, '<option value=""' + (!v ? " selected" : "") + '>— اختر —</option><option value="__skip"' + (v === "__skip" ? " selected" : "") + ">" + (stm ? "استبعاد هذه الكشوفات" : "استبعاد هذه الصفوف") + "</option>") + "</select>";
      });
      html += "</div>";
    }
    // أنواع الحركات
    if (!stm && S.tx.enabled && S.tx.layout === "typed") {
      var tvals = collect(S.tx, S.tx.sheets, S.tx.cols.type);
      var tk = Object.keys(tvals);
      tk.forEach(function (k) { if (!(k in S.typeMap)) S.typeMap[k] = C.guessDirection(tvals[k].text) || ""; });
      html += '<h3>أنواع الحركات</h3><p class="hint">«لنا» = المكتب أصبح مدينًا لنا بهذا المبلغ. «علينا» = نحن أصبحنا مدينين للمكتب.</p><div class="mapgrid"><span class="h">في الملف</span><span class="h">عدد</span><span class="h">في التطبيق</span>';
      tk.slice(0, 80).forEach(function (k) {
        var v = S.typeMap[k];
        html += "<span>" + esc(tvals[k].text) + "</span><span>" + tvals[k].n + '</span><select data-typemap="' + esc(k) + '"' + (!v ? ' style="border-color:#AD2E36"' : "") + ">" +
          [["", "— اختر —"], ["D", "لنا (مدين)"], ["C", "علينا (دائن)"], ["X", "استبعاد هذه الصفوف"]].map(function (x) {
            return '<option value="' + x[0] + '"' + (v === x[0] ? " selected" : "") + ">" + x[1] + "</option>"; }).join("") + "</select>";
      });
      html += "</div>";
    }
    // تعريف العملات المستخدمة
    var used = {};
    Object.keys(S.currencyMap).forEach(function (k) { var c = S.currencyMap[k]; if (c && c !== "__skip") used[c] = true; });
    if (!stm && S.open.enabled && S.open.layout === "wide") S.open.wide.forEach(function (w) { if (w.code) used[w.code] = true; });
    if (!stm && S.open.enabled && S.open.layout !== "wide" && S.open.currencyFrom === "fixed") used[S.open.fixedCurrency] = true;
    if (!stm && S.tx.enabled && S.tx.currencyFrom === "fixed") used[S.tx.fixedCurrency] = true;
    Object.keys(used).forEach(ensureDef);
    var uc = Object.keys(used).sort();
    if (uc.length) {
      html += '<h3>العملات التي ستُنشأ في التطبيق</h3><p class="hint">عدد المنازل العشرية مهم: المبالغ بمنازل أكثر منه تُرفض بدل تقريبها بصمت.</p><div class="curdefs"><span class="h hint">الرمز</span><span class="h hint">الاسم</span><span class="h hint">منازل عشرية</span>';
      uc.forEach(function (c) {
        var d = S.currencyDefs[c];
        html += '<b class="ltr">' + esc(c) + '</b><input type="text" data-curname="' + c + '" value="' + esc(d.name) + '"><select data-curdec="' + c + '">' +
          [0, 1, 2, 3, 4, 5, 6].map(function (n) { return "<option" + (d.dec === n ? " selected" : "") + ">" + n + "</option>"; }).join("") + "</select>";
      });
      html += "</div>";
    }
    if (stm) html += '<h3>أسماء المكاتب</h3><p class="hint">اسم المكتب من سطر الحساب أعلى كل كشف (بدون رمزه؛ الرمز يُحفظ في ملاحظات المكتب). تظهر القائمة في الخطوة 4 ويمكن تعديلها هناك.</p>';
    else html += '<h3>أسماء المكاتب</h3><label class="check"><input type="checkbox" id="mergeSimilar"' + (S.mergeSimilar ? " checked" : "") +
      '> دمج الأسماء المتشابهة في الكتابة (أ/ا، ة/ه، ى/ي) كمكتب واحد</label><div id="officeNames" class="hint">تظهر قائمة المكاتب بعد التحليل في الخطوة 4، ويمكن تعديل أسمائها هناك.</div>';
    box.innerHTML = html || '<p class="hint">لا توجد قيم تحتاج مطابقة.</p>';
  }

  // ===================================================================================
  // 4. التحليل والمراجعة
  // ===================================================================================

  function config() {
    var o = S.open, t = S.tx;
    function cols(c) { var r = {}; Object.keys(c).forEach(function (k) { if (c[k] !== undefined && c[k] !== null && c[k] !== "" && !isNaN(c[k])) r[k] = +c[k]; }); return r; }
    var oDate = null;
    if (o.date) { var p = o.date.split("-"); oDate = new Date(+p[0], +p[1] - 1, +p[2], 12, 0, 0).getTime(); }
    return {
      now: Date.now(), reverse: S.reverse, mergeSimilar: S.mergeSimilar, currencyMap: S.currencyMap, typeMap: S.typeMap, currencies: S.currencyDefs,
      rename: S.rename, sourceName: S.fileName, officeName: $("myOffice").value.trim(),
      statements: { balancesOnly: S.st.balancesOnly, mergeCom: S.st.mergeCom && !S.st.balancesOnly, positiveIs: S.st.positiveIs },
      openings: { enabled: o.enabled, headerRow: o.headerRow, layout: o.layout === "single" ? "signed" : o.layout, positiveIs: o.positiveIs, cols: cols(o.cols),
        currencyFrom: o.currencyFrom, fixedCurrency: o.fixedCurrency, wide: o.wide.filter(function (w) { return w.code; }), date: oDate },
      txs: { enabled: t.enabled, headerRow: t.headerRow, officeFrom: t.officeFrom, layout: t.layout, currencyFrom: t.currencyFrom, fixedCurrency: t.fixedCurrency,
        positiveIs: t.positiveIs, dateOrder: t.dateOrder, fillDate: t.fillDate, feeDir: t.feeDir, balancePositiveIs: t.balancePositiveIs,
        cols: cols(t.cols), descCols: t.descCols.filter(function (c) { return c !== undefined && c !== null && !isNaN(c); }) }
    };
  }

  function missingSetup() {
    var m = [];
    if (S.mode === "statements") {
      if (!S.stmts.length) m.push("لا توجد كشوفات حساب. اختر الملفات في الخطوة 1.");
      return m;
    }
    if (!S.open.enabled && !S.tx.enabled) m.push("حدّد في الخطوة 2 ما تريد نقله: الأرصدة أو الحركات أو كلاهما.");
    if (S.open.enabled) {
      var oc = S.open.cols;
      if (oc.office === undefined) m.push("الأرصدة: اختر عمود اسم المكتب.");
      if (S.open.layout === "single" && oc.amount === undefined) m.push("الأرصدة: اختر عمود الرصيد.");
      if (S.open.layout === "split" && (oc.debit === undefined || oc.credit === undefined)) m.push("الأرصدة: اختر عمودي «لنا» و«علينا».");
      if (S.open.layout === "wide" && !S.open.wide.some(function (w) { return w.code; })) m.push("الأرصدة: حدّد عملة عمود واحد على الأقل.");
      if (S.open.layout !== "wide" && S.open.currencyFrom === "column" && oc.currency === undefined) m.push("الأرصدة: اختر عمود العملة أو «عملة واحدة».");
    }
    if (S.tx.enabled) {
      var tc = S.tx.cols;
      if (!S.tx.sheets.length) m.push("الحركات: حدّد ورقة واحدة على الأقل.");
      if (S.tx.officeFrom === "column" && tc.office === undefined) m.push("الحركات: اختر عمود المكتب.");
      if (tc.date === undefined) m.push("الحركات: اختر عمود التاريخ.");
      if (S.tx.layout === "split" && (tc.debit === undefined || tc.credit === undefined)) m.push("الحركات: اختر عمودي «لنا» و«علينا».");
      if (S.tx.layout !== "split" && tc.amount === undefined) m.push("الحركات: اختر عمود المبلغ.");
      if (S.tx.layout === "typed" && tc.type === undefined) m.push("الحركات: اختر عمود النوع.");
      if (S.tx.currencyFrom === "column" && tc.currency === undefined) m.push("الحركات: اختر عمود العملة أو «عملة واحدة».");
    }
    return m;
  }

  function serialToDate(v) {
    var p = XLSX.SSF.parse_date_code(v);
    return p ? { y: p.y, m: p.m, d: p.d, H: p.H, M: p.M, S: p.S } : null;
  }

  function rand(n) { return crypto.getRandomValues(new Uint8Array(n)); }

  function analyze() {
    var miss = missingSetup();
    var box = $("review");
    S.result = null;
    if (miss.length) {
      box.innerHTML = '<div class="msg warn"><b>أكمل الإعداد:</b><ul>' + miss.map(function (x) { return "<li>" + esc(x) + "</li>"; }).join("") + "</ul></div>";
      updateMake();
      return;
    }
    var cfg = config();
    var stm = S.mode === "statements";
    var input = stm ? null : {
      openingSheets: S.open.enabled ? [{ name: S.open.sheet, rows: S.rows[S.open.sheet] }] : [],
      txSheets: S.tx.enabled ? S.tx.sheets.map(function (n) { return { name: n, rows: S.rows[n] }; }) : []
    };
    var model, T, rep;
    try {
      model = stm ? C.buildStatements(cfg, S.stmts, serialToDate) : C.build(cfg, input, serialToDate);
      T = C.tables(model, cfg, rand);
      rep = C.report(model, T, cfg);
      var unbalanced = C.checkBalanced(T);
      if (unbalanced.length) throw new Error("قيد غير متوازن — لا يجب أن يحدث، أرسل الملف للمطوّر");
    } catch (e) {
      box.innerHTML = '<div class="msg err">تعذر التحليل: ' + esc(e.message || e) + "</div>";
      updateMake();
      return;
    }
    S.result = { model: model, T: T, rep: rep, cfg: cfg };
    var html = "";
    if (stm) {
      var okF = model.files.filter(function (f) { return f.ok; });
      var lines = okF.reduce(function (a, f) { return a + (f.checked || 0); }, 0);
      var badF = model.files.length - okF.length;
      html += badF ? '<div class="msg err"><b>✗ ' + badF + " كشف من " + model.files.length + " لم يُنقل.</b> أسبابها في القائمة أدناه وفي جدول الخطوة 2. صحّحها (أو احذف الملف) ثم اختر الملفات مجددًا.</div>"
        : '<div class="msg ok"><b>✓ كل الكشوفات (' + okF.length + ") صحيحة:</b> " + lines.toLocaleString("en-US") + " رصيدًا في الكشوفات طابق الحساب سطرًا بسطر" +
          (okF.filter(function (f) { return f.footer; }).length ? "، و«اجمالي الرصيد» طابق في " + okF.filter(function (f) { return f.footer; }).length + " كشف." : ".") + "</div>";
    }
    html += '<div class="stats">' +
      stat(T.offices.length, "مكتب") + stat(T.tx.length, "حركة") + stat(T.opening_balances.length, "رصيد سابق") +
      stat(model.rowsRead, "صف مقروء") + stat(model.skipped.length, "صف متخطى") + stat(model.errors.length, "صف مرفوض", model.errors.length > 0) + "</div>";
    if (model.errors.length) {
      html += '<div class="msg err"><b>صفوف مرفوضة (' + model.errors.length + "):</b> لن تُنقل حتى تُصحَّح في الملف أو في الإعداد. صحّحها ثم اختر الملف مجددًا.<ul>" +
        model.errors.slice(0, 200).map(function (e) { return "<li>" + esc(e.sheet) + " — صف " + e.row + ": " + esc(e.msg) + "</li>"; }).join("") + "</ul></div>";
    }
    if (model.warnings.length) html += '<div class="msg warn"><b>ملاحظات:</b><ul>' + model.warnings.map(function (w) { return "<li>" + esc(w) + "</li>"; }).join("") + "</ul></div>";
    if (model.skipped.length) {
      var reasons = {};
      model.skipped.forEach(function (s) { reasons[s.reason] = (reasons[s.reason] || 0) + 1; });
      html += '<div class="msg info"><b>صفوف تُخطّيت (لا تؤثر على الأرصدة):</b> ' + Object.keys(reasons).map(function (r) { return esc(r) + " (" + reasons[r] + ")"; }).join("، ") +
        ' <a href="#" id="showSkipped">عرضها</a><ul class="hidden" id="skippedList">' + model.skipped.slice(0, 300).map(function (s) { return "<li>" + esc(s.sheet) + " — صف " + s.row + ": " + esc(s.reason) + "</li>"; }).join("") + "</ul></div>";
    }
    var checks = rep.rows.filter(function (r) { return r.match !== null; });
    var bad = checks.filter(function (r) { return !r.match; });
    if (checks.length) {
      if (!stm) html += bad.length ? '<div class="msg err"><b>✗ ' + bad.length + " رصيد لا يطابق عمود الرصيد في ملفك.</b> راجع اتجاه «لنا/علينا» أو الصفوف المرفوضة.</div>"
        : '<div class="msg ok"><b>✓ كل الأرصدة (' + checks.length + ") تطابق عمود الرصيد في ملفك.</b></div>";
      else if (bad.length) html += '<div class="msg err"><b>✗ ' + bad.length + " رصيد بعد النقل لا يطابق الكشف.</b> أرسل الكشوفات للمطوّر.</div>";
    }
    if (!stm) html += '<div class="row" style="margin:6px 0 10px"><label class="check" style="margin:0"><input type="checkbox" id="reverse"' + (S.reverse ? " checked" : "") +
      '> عكس اتجاه «لنا/علينا» في كل البيانات</label><span class="hint">استخدمه إن ظهرت كل الأرصدة معكوسة عن برنامجك.</span></div>';
    // جدول الأرصدة
    html += "<h3>أرصدة المكاتب بعد النقل</h3><div class=\"tablebox\"><table><tr><th>المكتب</th><th>العملة</th><th class=\"num\">رصيد سابق</th><th class=\"num\">حركات لنا</th><th class=\"num\">حركات علينا</th><th>الرصيد النهائي</th>" +
      (checks.length ? "<th>في ملفك</th><th></th>" : "") + "</tr>";
    rep.rows.forEach(function (r) {
      html += "<tr><td>" + esc(r.office) + '</td><td class="ltr">' + r.code + '</td><td class="num">' + bw(r.opening, r.dec) + '</td><td class="num">' + C.fmtMinor(r.debit, r.dec) +
        '</td><td class="num">' + C.fmtMinor(r.credit, r.dec) + "</td><td>" + balCell(r.balance, r.dec) + "</td>" +
        (checks.length ? "<td>" + (r.expected === null ? "" : balCell(r.expected, r.dec)) + "</td><td>" + (r.match === null ? "" : r.match ? '<span class="chip ok">مطابق</span>' : '<span class="chip bad">غير مطابق</span>') + "</td>" : "") + "</tr>";
    });
    html += "</table></div>";
    html += "<h3>الإجمالي لكل عملة</h3><div class=\"tablebox\"><table><tr><th>العملة</th><th class=\"num\">مجموع ما لنا</th><th>مكاتب</th><th class=\"num\">مجموع ما علينا</th><th>مكاتب</th><th>الصافي</th></tr>";
    rep.totals.forEach(function (t) {
      html += '<tr><td class="ltr">' + t.code + '</td><td class="num us">' + C.fmtMinor(t.receivable, t.dec) + "</td><td>" + t.nr + '</td><td class="num them">' + C.fmtMinor(t.payable, t.dec) +
        "</td><td>" + t.np + "</td><td>" + balCell(t.receivable - t.payable, t.dec) + "</td></tr>";
    });
    html += "</table></div>";
    // أسماء المكاتب
    html += "<h3>أسماء المكاتب في التطبيق</h3><p class=\"hint\">عدّل الاسم إن أردت؛ إعطاء مكتبين الاسم نفسه يدمجهما.</p><div class=\"tablebox\" style=\"max-height:300px\"><table><tr><th>الاسم في الملف</th><th>الاسم في التطبيق</th><th>حركات</th><th>أرصدة سابقة</th></tr>";
    model.offices.forEach(function (o) {
      Object.keys(o.variants).forEach(function (v, i) {
        var k = C.officeKey(v);
        html += "<tr><td>" + esc(v) + '</td><td><input type="text" data-rename="' + esc(k) + '" value="' + esc(S.rename[k] || o.name) + '"></td><td>' + (i === 0 ? o.tx : "") + "</td><td>" + (i === 0 ? o.openings : "") + "</td></tr>";
      });
    });
    html += "</table></div>";
    // عينة الحركات
    if (T.tx.length) {
      var names = {};
      T.offices.forEach(function (o) { names[o.id] = o.name; });
      var dec = {};
      T.currencies.forEach(function (c) { dec[c.code] = c.decimals; });
      html += "<h3>أول الحركات كما ستظهر</h3><div class=\"tablebox\" style=\"max-height:300px\"><table><tr><th>التاريخ</th><th>المكتب</th><th>النوع</th><th class=\"num\">المبلغ</th><th>المستفيد</th><th>البيان</th><th>الأجرة</th></tr>";
      T.tx.slice(0, 25).forEach(function (t) {
        var us = t.type.indexOf("لنا") >= 0;
        html += "<tr><td>" + C.fmtDate(t.created_at) + "</td><td>" + esc(names[t.office_id]) + '</td><td><span class="chip ' + (us ? "us" : "them") + '">' + (us ? "لنا" : "علينا") + '</span></td><td class="num">' +
          C.fmtMinor(BigInt(t.amount_minor), dec[t.currency]) + " " + t.currency + "</td><td>" + esc(t.person) + "</td><td>" + esc(String(t.description).slice(0, 50)) + "</td><td>" +
          (t.fee_minor ? esc(t.fee_type) + " " + C.fmtMinor(BigInt(t.fee_minor), dec[t.currency]) : "") + "</td></tr>";
      });
      html += "</table></div>";
    }
    box.innerHTML = html;
    var sk = $("showSkipped");
    if (sk) sk.onclick = function (e) { e.preventDefault(); $("skippedList").classList.toggle("hidden"); };
    $("step4").classList.add("done");
    if (stm) renderStmtTable();
    updateMake();
  }

  function stat(n, label, err) { return '<div class="stat' + (err ? " err" : "") + '"><b>' + n + "</b><span>" + label + "</span></div>"; }
  function bw(m, dec) { return m === 0n ? "0" : (m > 0n ? "لنا " : "علينا ") + C.fmtMinor(m > 0n ? m : -m, dec); }
  function balCell(m, dec) {
    if (m === 0n) return '<span class="hint">0</span>';
    return '<span class="chip ' + (m > 0n ? "us" : "them") + '">' + (m > 0n ? "لنا" : "علينا") + '</span> <span class="ltr ' + (m > 0n ? "us" : "them") + '">' + C.fmtMinor(m > 0n ? m : -m, dec) + "</span>";
  }

  // ===================================================================================
  // 5. الإنشاء
  // ===================================================================================

  function updateMake() {
    var r = S.result;
    var ok = !!r && (r.T.tx.length + r.T.opening_balances.length + r.T.offices.length) > 0;
    var hasErr = ok && r.model.errors.length > 0;
    $("forceBox").classList.toggle("hidden", !hasErr);
    var pw1 = $("pw1").value, pw2 = $("pw2").value;
    var can = ok && $("confirmed").checked && pw1.length >= 6 && pw1 === pw2 && (!hasErr || $("force").checked);
    $("make").disabled = !can;
    $("csv").disabled = !ok;
    $("print").disabled = !ok;
    var m = $("makeMsg");
    if (!ok) m.innerHTML = "";
    else if (pw1 && pw2 && pw1 !== pw2) m.innerHTML = '<div class="msg err">كلمتا السر غير متطابقتين.</div>';
    else if (pw1 && pw1.length < 6) m.innerHTML = '<div class="msg warn">كلمة السر قصيرة: 6 أحرف على الأقل.</div>';
    else if (m.dataset.done !== "1") m.innerHTML = "";
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
    function p(n) { return (n < 10 ? "0" : "") + n; }
    return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + "-" + p(d.getHours()) + p(d.getMinutes());
  }

  function make() {
    var r = S.result;
    if (!r) return;
    var m = $("makeMsg");
    $("make").disabled = true;
    m.dataset.done = "";
    m.innerHTML = '<div class="msg info">جارٍ إنشاء الملف وتشفيره… (قد يستغرق بضع ثوانٍ)</div>';
    // اسم مكتبك قد يتغير بعد التحليل
    r.cfg.officeName = $("myOffice").value.trim();
    r.cfg.now = Date.now();
    var T = C.tables(r.model, r.cfg, rand);
    C.makeBackup(T, r.cfg, $("pw1").value, crypto.subtle, rand).then(function (bytes) {
      var name = "نقل-المحاسب-الذكي-" + stamp() + ".sba";
      download(bytes, name);
      m.dataset.done = "1";
      m.innerHTML = '<div class="msg ok"><b>✓ تم إنشاء الملف «' + esc(name) + "»</b> (" + Math.ceil(bytes.length / 1024) + " ك.ب) — " + T.offices.length + " مكتب، " + T.tx.length + " حركة، " +
        T.opening_balances.length + " رصيد سابق.<ul><li>انقله إلى الهاتف (واتساب لنفسك، أو البريد، أو كابل).</li><li>في التطبيق: المزيد ← مساعد النقل من برنامج آخر ← استورد الملف، واكتب كلمة السر، ثم اختر «إضافة إلى بياناتي» حتى لا يُحذف شيء من بياناتك الحالية.</li>" +
        "<li>بعد الاستيراد قارن أرصدة المكاتب في التطبيق بهذا التقرير (احفظه بزر «تنزيل تقرير المطابقة»).</li><li>لا تنسَ كلمة السر: لا يمكن فتح الملف بدونها.</li></ul></div>";
      updateMake();
    }).catch(function (e) {
      m.innerHTML = '<div class="msg err">تعذر إنشاء الملف: ' + esc(e.message || e) + "</div>";
      updateMake();
    });
  }

  // ===================================================================================
  // الأحداث
  // ===================================================================================

  var timer = null;
  function later() { clearTimeout(timer); timer = setTimeout(function () { renderMap(); analyze(); }, 250); }
  function renderAll() {
    if (S.mode === "statements") { renderStmt(); renderMap(); analyze(); return; }
    renderOpen(); renderTx(); renderMap(); analyze();
  }

  function newCode(sel) {
    var c = (prompt("رمز العملة بالإنجليزية (مثل GOLD أو SILVER أو USDT):") || "").trim().toUpperCase();
    if (!/^[A-Z0-9]{2,10}$/.test(c)) { if (c) alert("الرمز يجب أن يكون من 2 إلى 10 أحرف إنجليزية أو أرقام."); return null; }
    var name = (prompt("اسم العملة بالعربية:", c) || c).trim();
    S.currencyDefs[c] = { name: name || c, dec: 2 };
    return c;
  }

  document.addEventListener("change", function (e) {
    var t = e.target, d = t.dataset;
    if (t.id === "file" && t.files.length) { var fl = Array.prototype.slice.call(t.files); t.value = ""; readFiles(fl, false); return; }
    if (t.id === "moreFiles" && t.files.length) { var ml = Array.prototype.slice.call(t.files); t.value = ""; readFiles(ml, true); return; }
    if (t.id === "mergeCom") { S.st.mergeCom = t.checked; analyze(); return; }
    if (t.type === "radio" && t.name === "stwhat") { S.st.balancesOnly = t.value === "bal"; renderStmt(); analyze(); return; }
    if (t.id === "openOn") { S.open.enabled = t.checked; renderAll(); return; }
    if (t.id === "txOn") { S.tx.enabled = t.checked; renderAll(); return; }
    if (t.id === "mergeSimilar") { S.mergeSimilar = t.checked; later(); return; }
    if (t.id === "reverse") { S.reverse = t.checked; analyze(); return; }
    if (t.id === "confirmed" || t.id === "force") { updateMake(); return; }
    var val = t.value;
    if (t.tagName === "SELECT" && val === "__new") {
      var c = newCode(t);
      if (!c) { renderAll(); return; }
      val = c;
    }
    if (d.o) {
      if (d.o === "sheet") { S.open.sheet = val; guessOpen(true); }
      else if (d.o === "headerRow") { S.open.headerRow = Math.max(0, (+val || 1) - 1); guessOpen(false); }
      else S.open[d.o] = val;
      renderOpen(); later(); return;
    }
    if (d.oc) { S.open.cols[d.oc] = num(val); renderOpen(); later(); return; }
    if (d.wide !== undefined) {
      var col = +d.wide;
      S.open.wide = S.open.wide.filter(function (w) { return w.col !== col; });
      if (val) S.open.wide.push({ col: col, code: val });
      renderOpen(); later(); return;
    }
    if (d.t) {
      if (d.t === "headerRow") { S.tx.headerRow = Math.max(0, (+val || 1) - 1); guessTx(false); }
      else S.tx[d.t] = val;
      renderTx(); later(); return;
    }
    if (d.tb) { S.tx[d.tb] = t.checked; later(); return; }
    if (d.tc) { S.tx.cols[d.tc] = num(val); renderTx(); later(); return; }
    if (d.desc !== undefined) { S.tx.descCols[+d.desc] = num(val); later(); return; }
    if (d.sheet !== undefined) {
      var n = d.sheet;
      S.tx.sheets = S.sheets.filter(function (x) { return x === n ? t.checked : S.tx.sheets.indexOf(x) >= 0; });
      if (S.tx.sheets.length > 1 && S.tx.cols.office === undefined) S.tx.officeFrom = "sheet";
      if (S.tx.sheets.length === 1 && t.checked) guessTx(true);
      renderTx(); later(); return;
    }
    if (d.curmap !== undefined) { S.currencyMap[d.curmap] = val; renderMap(); analyze(); return; }
    if (d.typemap !== undefined) { S.typeMap[d.typemap] = val; analyze(); return; }
    if (d.curname) { S.currencyDefs[d.curname].name = val; later(); return; }
    if (d.curdec) { S.currencyDefs[d.curdec].dec = +val; later(); return; }
    if (d.rename !== undefined) { S.rename[d.rename] = val; later(); return; }
    if (t.type === "radio") {
      var map = { olayout: ["open", "layout"], ocurfrom: ["open", "currencyFrom"], opos: ["open", "positiveIs"], tofrom: ["tx", "officeFrom"], tlayout: ["tx", "layout"],
        tcurfrom: ["tx", "currencyFrom"], tpos: ["tx", "positiveIs"], tfeedir: ["tx", "feeDir"], tbalpos: ["tx", "balancePositiveIs"] };
      var m = map[t.name];
      if (m) { S[m[0]][m[1]] = val; if (m[0] === "open") renderOpen(); else renderTx(); later(); }
    }
  });
  ["pw1", "pw2"].forEach(function (id) { $(id).addEventListener("input", updateMake); });
  $("make").addEventListener("click", make);
  $("csv").addEventListener("click", function () {
    var r = S.result;
    if (!r) return;
    download(C.reportCsv(r.rep, r.model), "تقرير-المطابقة-" + stamp() + ".csv", "text/csv;charset=utf-8");
  });
  $("print").addEventListener("click", function () { window.print(); });
  var drop = $("drop");
  ["dragenter", "dragover"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add("over"); }); });
  ["dragleave", "drop"].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove("over"); }); });
  drop.addEventListener("drop", function (e) { if (e.dataTransfer.files.length) readFiles(e.dataTransfer.files, false); });
  if (!window.crypto || !crypto.subtle || typeof BigInt === "undefined") {
    $("fileInfo").className = "msg err";
    $("fileInfo").textContent = "هذا المتصفح قديم ولا يدعم التشفير المطلوب. افتح الأداة بأحدث نسخة من Chrome أو Edge أو Firefox.";
  }
})();
