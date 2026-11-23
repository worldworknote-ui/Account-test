/*
 * الاستيراد الذكي — يفهم أغلب ملفات Excel بلا إعداد: يكتشف الجداول (جداول Excel المعرّفة أو بالتخمين)،
 * ويعرف دور كل عمود من محتواه لا من اسمه فقط (تاريخ، عملة، مبلغ، اتجاه، حساب، بيان، رقم قيد، رصيد)،
 * ويصنّف الأوراق (سجل حركات، نسخة مفلترة مشتقة، أرصدة، قائمة حسابات، قوائم قيم)،
 * ثم يبني مكاتب وحركات بالقاعدة نفسها التي تحسب بها معادلات الملف (SUMIFS)، ويتحقق من مجاميع الملف نفسه.
 * يعتمد على Core (أداة النقل) للمبالغ والتواريخ والقيود وملف النسخة، دون تعديلها.
 */
"use strict";
var Smart = (function (C) {
  var text = C.text, clean = C.clean, normDigits = C.normDigits;

  // ===================================================================================
  // أدوات عامة
  // ===================================================================================

  function colName(i) { var s = ""; i++; while (i > 0) { var m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); } return s; }

  function colIndex(letters) {
    var n = 0;
    for (var i = 0; i < letters.length; i++) n = n * 26 + (letters.charCodeAt(i) - 64);
    return n - 1;
  }

  /** «B8:H5279» ← {s:{r,c}, e:{r,c}} (صفر أولًا). */
  function decodeRef(ref) {
    var m = String(ref || "").replace(/\$/g, "").match(/^([A-Z]+)(\d+)(?::([A-Z]+)(\d+))?$/i);
    if (!m) return null;
    var s = { r: +m[2] - 1, c: colIndex(m[1].toUpperCase()) };
    var e = m[3] ? { r: +m[4] - 1, c: colIndex(m[3].toUpperCase()) } : { r: s.r, c: s.c };
    return { s: s, e: e };
  }

  function unxml(s) {
    return String(s || "").replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
      .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(+d); })
      .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
  }

  function attr(tag, name) {
    var m = tag.match(new RegExp("\\s" + name.replace(":", "\\:") + '="([^"]*)"'));
    return m ? unxml(m[1]) : null;
  }

  function fileText(wb, path) {
    if (!wb || !wb.files) return null;
    var f = wb.files[path] || wb.files["/" + path];
    if (!f || !f.content) return null;
    try { return new TextDecoder("utf-8").decode(f.content instanceof Uint8Array ? f.content : new Uint8Array(f.content)); } catch (e) { return null; }
  }

  function resolvePath(dir, target) {
    if (target.charAt(0) === "/") return target.slice(1);
    var parts = (dir ? dir.split("/") : []);
    target.split("/").forEach(function (p) {
      if (p === "..") parts.pop();
      else if (p !== ".") parts.push(p);
    });
    return parts.join("/");
  }

  function rowLabel(row) {
    for (var i = 0; i < (row || []).length; i++) {
      var c = row[i];
      if (c && c.s !== undefined && clean(c.s)) return clean(c.s);
    }
    return "";
  }

  /** مفتاح مطابقة للنصوص (الحسابات والقيم): يتجاهل المسافات الزائدة وحالة الأحرف. */
  function key(s) { return C.officeKey(s); }

  /** مفتاح عناوين الأعمدة: بلا مسافات ولا رموز، مع توحيد الهمزات. */
  function hkey(s) { return C.headerKey(s).replace(/\s/g, ""); }

  // ===================================================================================
  // جداول Excel المعرّفة (ListObjects): أدق مصدر لحدود الجدول وعناوينه
  // ===================================================================================

  function excelTables(wb) {
    var out = [];
    var wbx = fileText(wb, "xl/workbook.xml"), rels = fileText(wb, "xl/_rels/workbook.xml.rels");
    if (!wbx || !rels) return out;
    var relMap = {};
    rels.replace(/<Relationship\b[^>]*>/g, function (t) { relMap[attr(t, "Id")] = attr(t, "Target"); return t; });
    wbx.replace(/<sheet\b[^>]*>/g, function (t) {
      var name = attr(t, "name"), rid = attr(t, "r:id");
      var target = rid && relMap[rid];
      if (!target) return t;
      var path = resolvePath("xl", target);
      var dir = path.slice(0, path.lastIndexOf("/")), file = path.slice(path.lastIndexOf("/") + 1);
      var srels = fileText(wb, dir + "/_rels/" + file + ".rels");
      if (!srels) return t;
      srels.replace(/<Relationship\b[^>]*>/g, function (r) {
        if (!/\/table$/.test(attr(r, "Type") || "")) return r;
        var tx = fileText(wb, resolvePath(dir, attr(r, "Target")));
        if (!tx) return r;
        var head = (tx.match(/<table\b[^>]*>/) || [""])[0];
        var cols = [];
        tx.replace(/<tableColumn\b[^>]*>/g, function (c) { cols.push(attr(c, "name") || ""); return c; });
        out.push({ name: attr(head, "displayName") || attr(head, "name") || "", sheet: name, ref: attr(head, "ref"),
          headerRows: attr(head, "headerRowCount") === "0" ? 0 : 1, totalsRows: +(attr(head, "totalsRowCount") || 0), columns: cols });
        return r;
      });
      return t;
    });
    return out;
  }

  // ===================================================================================
  // التعرف على القيم: الاتجاه والعملة والعناوين
  // ===================================================================================

  var D_WORDS = ["مدين", "لنا", "عليه", "منه", "debit", "dr", "debtor", "receivable"].map(function (w) { return C.looseKey(w); });
  var C_WORDS = ["دائن", "لكم", "علينا", "له", "لهم", "credit", "cr", "creditor", "payable"].map(function (w) { return C.looseKey(w); });

  /** «مدين(لنا)» ← D، «دائن(لكم)» ← C، «علينا» ← C (لا تُخدع بأن «لنا» داخل «علينا»). */
  function guessDir(t) {
    var s = clean(normDigits(t)).toLowerCase();
    if (!s || s.length > 40) return null;
    var toks = s.split(/[^a-z؀-ۿ]+/).filter(Boolean).map(function (w) { return C.looseKey(w); });
    if (!toks.length) return s === "+" ? "D" : s === "-" ? "C" : null;
    var d = 0, c = 0;
    toks.forEach(function (w) {
      if (C_WORDS.indexOf(w) >= 0) c++;
      else if (D_WORDS.indexOf(w) >= 0) d++;
    });
    if (d && !c) return "D";
    if (c && !d) return "C";
    return null;
  }

  function guessCur(t) {
    var s = clean(t);
    if (!s || s.length > 30) return null;
    return C.guessCurrency(s) || C.guessCurrency(s.toUpperCase());
  }

  var HINTS = {
    date: ["date", "التاريخ", "تاريخ", "تاريخ الحركه", "التاريخ والوقت", "datetime", "time", "الوقت", "يوم"],
    currency: ["currency", "العمله", "عمله", "cur", "ccy", "نوع العمله"],
    amount: ["amount", "ammount", "amout", "ammout", "المبلغ", "مبلغ", "القيمه", "قيمه", "value", "sum", "الكميه", "مقدار"],
    direction: ["in-out", "inout", "in out", "type", "النوع", "نوع", "نوع الحركه", "الاتجاه", "لنا علينا", "لنا/علينا", "مدين دائن", "dr/cr", "drcr", "حركه"],
    debit: ["debit", "dr", "مدين", "لنا", "عليه", "مدين لنا", "منه"],
    credit: ["credit", "cr", "دائن", "علينا", "له", "دائن علينا", "لكم"],
    account: ["account", "name of account", "name of accunt", "accunt", "acount", "الحساب", "اسم الحساب", "حساب", "المكتب", "اسم المكتب", "مكتب",
      "العميل", "الوكيل", "الطرف", "الجهه", "office", "agent", "client", "customer", "name", "الاسم", "اسم"],
    description: ["description", "البيان", "بيان", "الشرح", "التفاصيل", "الوصف", "details", "memo", "narration", "ملاحظه", "المستفيد"],
    ref: ["id", "id_no", "idno", "no", "رقم", "الرقم", "رقم القيد", "رقم الحركه", "رقم السند", "ref", "reference", "serial", "#", "م", "ت"],
    balance: ["balance", "الرصيد", "رصيد", "running balance", "الرصيد التراكمي"],
    fee: ["fee", "fees", "commission", "comm", "الاجره", "اجره", "الاجور", "اجور", "العموله", "عموله", "الكوميسيون", "عمولة التحويل"],
    phone: ["phone", "mobile", "tel", "telephone", "الهاتف", "هاتف", "رقم الهاتف", "الجوال", "جوال", "الموبايل", "موبايل", "واتساب", "whatsapp"]
  };
  var HINT_KEYS = {};
  Object.keys(HINTS).forEach(function (r) { HINT_KEYS[r] = HINTS[r].map(hkey); });

  function hint(role, header) {
    var k = hkey(header || "");
    if (!k) return 0;
    var hs = HINT_KEYS[role];
    if (hs.indexOf(k) >= 0) return 2;
    for (var i = 0; i < hs.length; i++) if (hs[i].length >= 4 && k.indexOf(hs[i]) >= 0) return 1;
    return 0;
  }

  // ===================================================================================
  // اكتشاف الجداول
  // ===================================================================================

  // نص يبدو رقمًا («1.250,50»، «(300)»، «٣٠٠») أو تاريخًا («15/06/2026»): ملفات CSV كلها نصوص
  var NUM_TEXT = /^[\s(+\-]*[0-9٠-٩۰-۹][0-9٠-٩۰-۹.,٫٬\s]*\)?-?\s*$/;
  var DATE_TEXT = /^\s*[0-9٠-٩۰-۹]{1,4}\s*[\/\-.]\s*[0-9٠-٩۰-۹]{1,2}\s*[\/\-.]\s*[0-9٠-٩۰-۹]{1,4}/;

  function cellKind(c) {
    if (!c) return "";
    if (c.date) return "d";
    if (c.n !== undefined) return "n";
    var t = text(c);
    if (!t) return "";
    if (DATE_TEXT.test(t)) return "d";
    if (NUM_TEXT.test(t)) return "n";
    return "s";
  }

  function rowSig(row) {
    var t = 0, n = 0, d = 0, any = 0, first = -1, last = -1;
    (row || []).forEach(function (c, i) {
      var k = cellKind(c);
      if (!k) return;
      any++;
      if (first < 0) first = i;
      last = i;
      if (k === "s") t++;
      else if (k === "n") n++;
      else d++;
    });
    return { t: t, n: n, d: d, any: any, first: first, last: last };
  }

  function headerScore(row) {
    var s = 0;
    (row || []).forEach(function (c) {
      if (!c || c.s === undefined) return;
      var h = text(c);
      Object.keys(HINTS).forEach(function (r) { if (hint(r, h) === 2) s += 2; });
    });
    return s;
  }

  /** جداول بلا تعريف Excel: صف عناوين (نصوص) تحته صفوف فيها أرقام أو تواريخ، حتى فراغ طويل. */
  function guessTables(rows) {
    var out = [];
    var n = rows.length;
    var r = 0;
    while (r < n) {
      while (r < n && rowSig(rows[r]).any === 0) r++;
      if (r >= n) break;
      var best = -1, bestScore = -1;
      for (var h = r; h < Math.min(n, r + 40); h++) {
        var sg = rowSig(rows[h]);
        if (sg.t < 2 || sg.t < sg.any * 0.6) continue;
        var data = 0, numeric = 0;
        for (var k = h + 1; k < Math.min(n, h + 12); k++) {
          var s2 = rowSig(rows[k]);
          if (!s2.any) continue;
          data++;
          if (s2.n + s2.d > 0) numeric++;
        }
        var strong = headerScore(rows[h]) >= 4;
        if (numeric < 1 || (numeric < 2 && !strong) || (data < 2 && !strong)) continue;
        var score = sg.t + 3 * headerScore(rows[h]) + numeric;
        if (score > bestScore) { bestScore = score; best = h; }
      }
      if (best < 0) { r += 40; continue; }
      var end = best + 1, blank = 0, lastData = best;
      while (end < n) {
        var s3 = rowSig(rows[end]);
        if (!s3.any) { blank++; if (blank >= 8) break; }
        else { blank = 0; lastData = end; }
        end++;
      }
      var hs = rowSig(rows[best]);
      var c1 = hs.last;
      for (var q = best + 1; q <= Math.min(lastData, best + 300); q++) c1 = Math.max(c1, rowSig(rows[q]).last);
      out.push({ header: best, first: best + 1, last: lastData, c0: Math.max(0, hs.first), c1: c1 });
      r = end;
    }
    return out;
  }

  /** قائمة نصية بعمود واحد (مثل أسماء الحسابات). */
  function guessList(rows) {
    var byCol = {};
    var total = 0;
    rows.forEach(function (row, r) {
      (row || []).forEach(function (c, i) {
        if (!c) return;
        var t = text(c);
        if (!t) return;
        total++;
        var b = byCol[i] = byCol[i] || { n: 0, texts: 0, rows: [], first: r, last: r };
        b.n++;
        if (c.s !== undefined && !/^\d+([.,]\d+)?$/.test(t)) b.texts++;
        b.rows.push(r);
        b.last = r;
      });
    });
    var best = null;
    Object.keys(byCol).forEach(function (i) {
      var b = byCol[i];
      if (b.n >= 5 && b.texts >= b.n * 0.8 && b.n >= total * 0.8 && (!best || b.n > best.n)) best = { c: +i, n: b.n, first: b.first, last: b.last };
    });
    return best;
  }

  // ===================================================================================
  // ملف تعريف كل عمود من محتواه
  // ===================================================================================

  /** عيّنة موزعة على الجدول (حتى 4000 صف) تكفي لمعرفة نوع العمود بسرعة، وأول 400 صف متتالية لكشف الأرقام المتسلسلة. */
  function profile(t, c) {
    var p = { c: c, n: 0, dates: 0, nums: 0, ints: 0, texts: 0, numText: 0, cur: 0, dir: 0, neg: 0, maxDec: 0, distinct: {}, nd: 0, seqHits: 0, seqPairs: 0, avgLen: 0, phones: 0 };
    var prev = null, lenSum = 0;
    var span = t.last - t.first + 1, step = Math.max(1, Math.floor(span / 4000));
    for (var q = t.first; q <= Math.min(t.last, t.first + 400); q++) {
      var cq = (t.rows[q] || [])[c];
      var sv = cq ? text(cq) : "";
      var iq = null;
      if (cq && cq.n !== undefined && Number.isInteger(cq.n)) iq = cq.n;
      else if (sv && /^\s*0*\d+\s*$/.test(normDigits(sv))) iq = +normDigits(sv);
      if (iq !== null && prev !== null) { p.seqPairs++; if (iq === prev + 1) p.seqHits++; }
      prev = iq;
    }
    p.visited = 0;
    for (var r = t.first; r <= t.last; r += step) {
      p.visited++;
      var cell = (t.rows[r] || [])[c];
      if (!cell) continue;
      var s = text(cell);
      if (!s) continue;
      p.n++;
      lenSum += s.length;
      var k = key(s);
      if (!(k in p.distinct)) { p.distinct[k] = 0; p.nd++; }
      p.distinct[k]++;
      if (cell.date) p.dates++;
      else if (cell.n !== undefined) {
        p.nums++;
        if (Number.isInteger(cell.n)) p.ints++;
        if (cell.n < 0) p.neg++;
        var dec = String(cell.n).split(".")[1];
        if (dec) p.maxDec = Math.max(p.maxDec, dec.replace(/0+$/, "").length);
      } else {
        p.texts++;
        var pa = C.parseAmount(s);
        if (pa.ok && !pa.hadText) {
          p.numText++;
          if (pa.neg) p.neg++;
          p.maxDec = Math.max(p.maxDec, pa.frac.length);
        }
        if (C.parseDate(s, "dmy") && /\d{1,4}\s*[\/\-.]\s*\d{1,2}\s*[\/\-.]\s*\d{1,4}/.test(normDigits(s))) p.dates++;
        if (guessCur(s)) p.cur++;
        if (guessDir(s)) p.dir++;
        if (/^(\+|00|0)\d[\d\s\-]{6,}$/.test(clean(normDigits(s)).replace(/[\u200e\u200f\u202a-\u202e]/g, ""))) p.phones++;
      }
    }
    p.avgLen = p.n ? lenSum / p.n : 0;
    p.ratio = function (x) { return p.n ? x / p.n : 0; };
    return p;
  }

  function topValues(p, n) {
    return Object.keys(p.distinct).sort(function (a, b) { return p.distinct[b] - p.distinct[a]; }).slice(0, n || 8);
  }

  // ===================================================================================
  // أدوار الأعمدة
  // ===================================================================================

  function inferRoles(t) {
    var cols = [];
    for (var c = t.c0; c <= t.c1; c++) {
      // الأعمدة الفارغة تبقى: عمود «علينا» فارغ في ورقة مكتب لم يُسلَّم بعد ما زال عمود «علينا»
      cols.push({ c: c, h: t.names[c - t.c0] || "", p: profile(t, c) });
    }
    var roles = {}, used = {};
    // عدد الصفوف التي فُحصت فعلًا (العيّنة)، لتُقارن به أعداد الأعمدة
    var rowsN = Math.max(1, cols.length ? cols[0].p.visited : t.last - t.first + 1);
    function take(role, col) { roles[role] = col.c; used[col.c] = true; }
    function best(filter, score) {
      var b = null, bs = -1e9;
      cols.forEach(function (col) {
        if (used[col.c] || !filter(col)) return;
        var s = score(col);
        if (s > bs) { bs = s; b = col; }
      });
      return b;
    }
    // التاريخ
    var d = best(function (x) { return x.p.ratio(x.p.dates) >= 0.6 || (hint("date", x.h) === 2 && x.p.ratio(x.p.dates) >= 0.3); },
      function (x) { return x.p.dates + hint("date", x.h) * rowsN; });
    if (d) take("date", d);
    // العملة
    var cu = best(function (x) { return x.p.ratio(x.p.cur) >= 0.6 && x.p.nd <= 40; }, function (x) { return x.p.cur + hint("currency", x.h) * rowsN; });
    if (cu) take("currency", cu);
    // الاتجاه
    var di = best(function (x) { return x.p.ratio(x.p.dir) >= 0.6 && x.p.nd <= 16; }, function (x) { return x.p.dir + hint("direction", x.h) * rowsN; });
    if (di) take("direction", di);
    // رقم القيد: أرقام متتالية أو نصوص أرقام مثل «001»
    var rf = best(function (x) {
      var seq = x.p.seqPairs ? x.p.seqHits / x.p.seqPairs : 0;
      return (seq >= 0.8 && x.p.seqPairs >= 3) || (hint("ref", x.h) === 2 && x.p.ratio(x.p.ints + x.p.numText) >= 0.8 && x.p.nd >= x.p.n * 0.9);
    }, function (x) { return x.p.seqHits + hint("ref", x.h) * rowsN; });
    if (rf) take("ref", rf);
    // الهاتف (لا يُخلط بالمبالغ): عنوان صريح أو أرقام تبدأ بـ 0 أو +
    var ph = best(function (x) { return x.p.n > 0 && (hint("phone", x.h) > 0 || x.p.ratio(x.p.phones) >= 0.8); },
      function (x) { return hint("phone", x.h) * rowsN + x.p.phones; });
    if (ph) take("phone", ph);
    // جدول عريض: عمود لكل عملة (المكتب | USD | EUR | TRY) — رصيد أو مبلغ موقّع في كل عمود
    if (roles.currency === undefined) {
      var wide = cols.filter(function (x) {
        return !used[x.c] && x.p.ratio(x.p.nums + x.p.numText) >= 0.6 && guessCur(x.h) && !hint("debit", x.h) && !hint("credit", x.h);
      });
      var codes = wide.map(function (x) { return guessCur(x.h); });
      var distinct = codes.filter(function (c, i) { return codes.indexOf(c) === i; });
      if (wide.length >= 2 && distinct.length === wide.length) {
        roles.wide = wide.map(function (x) { used[x.c] = true; return { c: x.c, code: guessCur(x.h), h: x.h }; });
      }
    }
    // الأعمدة الرقمية
    var numeric = cols.filter(function (x) { return !used[x.c] && x.p.ratio(x.p.nums + x.p.numText) >= 0.7; });
    var deb = null, cre = null;
    if (!roles.direction) {
      deb = best(function (x) { return (numeric.indexOf(x) >= 0 || (x.p.n === 0 && hint("debit", x.h) === 2)) && hint("debit", x.h) > 0; },
        function (x) { return hint("debit", x.h) * 10 + x.p.n; });
      if (deb) used[deb.c] = true;
      cre = best(function (x) { return (numeric.indexOf(x) >= 0 || (x.p.n === 0 && hint("credit", x.h) === 2)) && hint("credit", x.h) > 0; },
        function (x) { return hint("credit", x.h) * 10 + x.p.n; });
      if (deb) used[deb.c] = false;
      if (deb && cre && deb !== cre) { take("debit", deb); take("credit", cre); }
    }
    var bal = best(function (x) { return numeric.indexOf(x) >= 0 && hint("balance", x.h) > 0; }, function (x) { return hint("balance", x.h); });
    if (bal) take("balance", bal);
    // الأجرة/العمولة: عمود مستقل يدخل في الرصيد كأجرة الحركة (اتجاهها في الخطوة 3، ويُعرف من عمود الرصيد إن وُجد)
    var fe = best(function (x) { return (numeric.indexOf(x) >= 0 || (x.p.n === 0 && hint("fee", x.h) === 2)) && hint("fee", x.h) > 0; }, function (x) { return hint("fee", x.h) * 10 + x.p.n; });
    if (fe) take("fee", fe);
    if (roles.debit === undefined && !roles.wide) {
      var am = best(function (x) { return numeric.indexOf(x) >= 0; }, function (x) { return hint("amount", x.h) * rowsN * 2 + x.p.n - (x.p.maxDec > 4 ? rowsN : 0); });
      if (am) take("amount", am);
    }
    // جدول أرصدة (بلا تاريخ) عموده الرقمي الوحيد «الرصيد»: هو المبلغ
    if (roles.amount === undefined && roles.debit === undefined && !roles.wide && roles.balance !== undefined && roles.date === undefined) {
      roles.amount = roles.balance;
      delete roles.balance;
    }
    // الحساب: نص يتكرر كثيرًا (أسماء قليلة مقابل صفوف كثيرة)، والبيان: النص الأكثر تنوعًا
    var texts = cols.filter(function (x) { return !used[x.c] && x.p.ratio(x.p.texts + x.p.ints) >= 0.5 && x.p.nd >= 1; });
    // عنوان صريح («الحساب»، «المكتب»، «NAME OF ACCOUNT»)، أو في جدول كبير: نص تتكرر قيمه كثيرًا
    var acc = best(function (x) {
      if (texts.indexOf(x) < 0 || x.p.nd < 1 || x.p.n < rowsN * 0.3) return false;
      return hint("account", x.h) === 2 || (x.p.n >= 8 && x.p.nd <= Math.max(3, x.p.n * 0.25));
    }, function (x) { return hint("account", x.h) * rowsN + x.p.n - x.p.nd * 2; });
    // جدول أرصدة بلا تاريخ: سطر لكل مكتب، فالأسماء لا تتكرر
    var money = roles.amount !== undefined || roles.debit !== undefined || !!roles.wide;
    if (!acc && roles.date === undefined && money) {
      acc = best(function (x) { return texts.indexOf(x) >= 0 && x.p.n >= rowsN * 0.5 && x.p.nd >= x.p.n * 0.9; },
        function (x) { return hint("account", x.h) * rowsN + x.p.n - x.c * 0.001; });
    }
    if (acc) take("account", acc);
    var de = best(function (x) { return texts.indexOf(x) >= 0; }, function (x) { return hint("description", x.h) * rowsN + x.p.nd; });
    if (de) take("description", de);
    // باقي أعمدة الجدول (حتى النادرة التي لم تظهر في العيّنة) تُحفظ في ملاحظات الحركة
    roles.notes = [];
    for (var nc = t.c0; nc <= t.c1; nc++) if (!used[nc]) roles.notes.push(nc);
    return { roles: roles, cols: cols };
  }

  // ===================================================================================
  // تصنيف الجداول وتحليل الملف كله
  // ===================================================================================

  var GENERIC_SHEET = /^(sheet|ورقه|ورقة|data|البيانات|الحركات|المكاتب|الحسابات|الارصده|الأرصدة|table|page|صفحه|صفحة)\s*\d*$/i;

  function signature(t) { return t.names.map(function (h) { return hkey(h); }).join("|"); }

  function isHeaderRepeat(t, row) {
    var same = 0, total = 0;
    t.names.forEach(function (h, i) {
      if (!h) return;
      total++;
      if (hkey(text((row || [])[t.c0 + i])) === hkey(h)) same++;
    });
    return total >= 2 && same >= total * 0.8;
  }

  /**
   * books: [{name, wb, sheets:[{name, rows}]}] ← تحليل كامل: الجداول بأدوارها وأنواعها، والقيم التي تحتاج مطابقة.
   */
  function analyze(books) {
    var tables = [], lists = [], notes = [];
    books.forEach(function (bk, bi) {
      var xt = excelTables(bk.wb);
      bk.sheets.forEach(function (sh) {
        var rows = sh.rows || [];
        var label = (books.length > 1 ? bk.name + " / " : "") + sh.name;
        if (!rows.length) { notes.push({ sheet: label, kind: "empty" }); return; }
        var mine = xt.filter(function (x) { return x.sheet === sh.name; });
        var found = [];
        mine.forEach(function (x) {
          var rg = decodeRef(x.ref);
          if (!rg) return;
          found.push({ header: x.headerRows ? rg.s.r : -1, first: rg.s.r + x.headerRows, last: Math.min(rows.length - 1, rg.e.r - x.totalsRows),
            c0: rg.s.c, c1: rg.e.c, excel: x.name, names: x.columns });
        });
        if (!found.length) found = guessTables(rows);
        found.forEach(function (f) {
          var t = { id: "t" + tables.length, book: bi, bookName: bk.name, multiBook: books.length > 1, sheet: sh.name, label: label, rows: rows, header: f.header, first: f.first, last: f.last,
            c0: f.c0, c1: f.c1, excel: f.excel || "" };
          t.names = f.names || [];
          if (!f.names) for (var c = t.c0; c <= t.c1; c++) t.names.push(f.header >= 0 ? text((rows[f.header] || [])[c]) : "");
          for (var i = 0; i < t.names.length; i++) if (!t.names[i]) t.names[i] = "عمود " + colName(t.c0 + i);
          // آخر صف فيه بيانات فعلًا (الجداول المعرّفة قد تمتد على صفوف فارغة مُعدّة مسبقًا)
          var last = t.first - 1;
          for (var r = t.first; r <= t.last; r++) if (rowSig(rows[r]).any) last = r;
          t.last = last;
          var inf = inferRoles(t);
          t.roles = inf.roles;
          t.cols = inf.cols;
          classify(t);
          if (t.kind === "balances" && bk.wb && bk.wb.Sheets && bk.wb.Sheets[sh.name]) t.formulaShare = formulaShare(bk.wb.Sheets[sh.name], t);
          tables.push(t);
        });
        if (!found.length) {
          var l = guessList(rows);
          if (l) lists.push({ id: "l" + lists.length, sheet: sh.name, label: label, c: l.c, first: l.first, last: l.last, rows: rows, n: l.n });
          else notes.push({ sheet: label, kind: "unknown" });
        }
      });
      (bk.wb && bk.wb.SheetNames || []).forEach(function (n) {
        if (!bk.sheets.some(function (s) { return s.name === n; })) notes.push({ sheet: n, kind: "chart" });
      });
    });
    // قوائم القيم داخل جداول صغيرة (عملات واتجاهات للإدخال)
    lists.forEach(function (l) { l.kind = "accounts"; describeList(l); });
    markDerived(tables);
    // جدول أرصدة بمعادلات في ملف فيه سجل حركات = ملخص محسوب منه: للتحقق فقط
    var hasJournal = tables.some(function (t) { return t.kind === "journal"; });
    tables.forEach(function (t) {
      if (t.kind === "balances" && hasJournal && t.formulaShare >= 0.5) { t.kind = "summary"; t.use = false; t.summaryWhy = "formulas"; }
    });
    var a = { tables: tables, lists: lists, notes: notes };
    a.values = collectValues(a);
    return a;
  }

  function baseName(f) { return clean(String(f || "").replace(/\.[^.]+$/, "")); }

  /** «أبو سمير USD» ← {name: «أبو سمير», code: USD}؛ بلا عملة في الاسم ← {name: الاسم كما هو, code: null}. */
  function splitCur(nm) {
    var out = { name: nm, code: null };
    nm.split(/[\s\-_–—()]+/).some(function (w) {
      var g = w && guessCur(w);
      if (!g) return false;
      var rest = clean(nm.replace(w, " ").replace(/[\-_–—()]+/g, " "));
      if (!rest) return false;
      out = { name: rest, code: g };
      return true;
    });
    return out;
  }

  function classify(t) {
    var r = t.roles;
    var hasMoney = r.amount !== undefined || (r.debit !== undefined && r.credit !== undefined) || !!r.wide;
    var dataRows = t.last - t.first + 1;
    t.kind = "other";
    if (r.wide && r.date === undefined && r.account !== undefined) t.kind = "balances";
    else if (r.wide) t.kind = "other";
    else if (hasMoney && (r.direction !== undefined || r.debit !== undefined) && r.date !== undefined) t.kind = "journal";
    else if (hasMoney && r.date !== undefined) t.kind = "journal";
    else if (hasMoney && r.date === undefined && r.account !== undefined) t.kind = "balances";
    else if (dataRows > 0 && dataRows <= 40 && t.cols.some(function (x) { return x.p.ratio(x.p.cur) >= 0.6 || x.p.ratio(x.p.dir) >= 0.6; })) t.kind = "lookup";
    // حساب من اسم الورقة (دفتر بورقة لكل مكتب) أو من اسم الملف (ملف لكل مكتب)؛ عملة مكتوبة في الاسم تُفصل عنه
    t.fromSheet = splitCur(clean(t.sheet));
    t.fromFile = splitCur(baseName(t.bookName));
    t.accountFrom = r.account !== undefined ? "column" : null;
    t.sheetAccount = "";
    t.sheetCurrency = null;
    if (t.kind === "journal" && r.account === undefined) {
      var src = !GENERIC_SHEET.test(clean(t.sheet)) ? "sheet" : (t.multiBook && !GENERIC_SHEET.test(baseName(t.bookName)) ? "file" : null);
      if (src) {
        var nm = src === "sheet" ? t.fromSheet : t.fromFile;
        t.accountFrom = src;
        t.sheetAccount = nm.name;
        if (r.currency === undefined) t.sheetCurrency = nm.code;
      }
    }
    t.accountFromSheet = t.accountFrom === "sheet" || t.accountFrom === "file";
    if (t.kind === "journal" && !t.accountFrom) t.kind = "journal-noaccount";
    // الاتجاه بلا عمود: مبلغ موجب/سالب
    t.signed = (t.kind === "journal" || t.kind === "balances" || t.kind === "journal-noaccount") && r.direction === undefined && r.debit === undefined && !r.wide;
    t.use = t.kind === "journal" || t.kind === "balances";
    // سجل بلا اتجاه ومبالغه كلها موجبة: لا يُعرف أهي لنا أم علينا، فلا يُنقل تلقائيًا
    t.unsure = null;
    if (t.signed && t.kind === "journal") {
      var am = t.cols.filter(function (x) { return x.c === r.amount; })[0];
      if (am && am.p.neg === 0) { t.unsure = "allPositive"; t.use = false; }
    }
  }

  /** نسبة خلايا المبالغ المحسوبة بمعادلة في جدول. */
  function formulaShare(ws, t) {
    var r = t.roles, cols = [r.amount, r.debit, r.credit].filter(function (x) { return x !== undefined; });
    (r.wide || []).forEach(function (w) { cols.push(w.c); });
    var n = 0, f = 0;
    for (var row = t.first; row <= t.last; row++) {
      cols.forEach(function (c) {
        var cell = ws[colName(c) + (row + 1)];
        if (!cell || cell.v === undefined || cell.v === "") return;
        n++;
        if (cell.f) f++;
      });
    }
    return n ? f / n : 0;
  }

  /** بعد تعديل المستخدم لأدوار الأعمدة: تُعاد ملاحظات الأعمدة والتصنيف، وتبقى النسخة المشتقة مشتقة والملخص ملخصًا. */
  function reclassify(t) {
    var derived = t.kind === "derived", summary = t.kind === "summary";
    var used = {};
    Object.keys(t.roles).forEach(function (k) {
      if (k === "notes") return;
      if (k === "wide") (t.roles.wide || []).forEach(function (w) { used[w.c] = true; });
      else if (t.roles[k] !== undefined) used[t.roles[k]] = true;
    });
    t.roles.notes = [];
    for (var c = t.c0; c <= t.c1; c++) if (!used[c]) t.roles.notes.push(c);
    classify(t);
    if (derived) { t.kind = "derived"; t.use = false; }
    if (summary && t.kind === "balances") { t.kind = "summary"; t.use = false; }
  }

  function describeList(l) {
    var vals = [];
    for (var r = l.first; r <= l.last; r++) {
      var t = text((l.rows[r] || [])[l.c]);
      if (t) vals.push(t);
    }
    // أول قيمة غالبًا عنوان («names of accounts»)
    if (vals.length && /name|اسماء|أسماء|الحسابات|accounts|المكاتب/i.test(vals[0])) vals.shift();
    l.values = vals;
    l.use = true;
  }

  /** بصمة صف حركة: تاريخ + عملة + مبلغ + اتجاه + حساب. */
  function rowPrint(t, row) {
    var r = t.roles;
    function v(c) { if (c === undefined) return ""; var x = row[c]; if (!x) return ""; if (x.n !== undefined) return String(x.n); if (x.date) return JSON.stringify(x.date); return key(text(x)); }
    return [v(r.date), v(r.currency), v(r.amount), v(r.direction), v(r.debit), v(r.credit), v(r.account)].join("¦");
  }

  /** جدول أغلب صفوفه موجودة في جدول أكبر = نسخة مفلترة منه: لا يُنقل حتى لا تتكرر الحركات، ويُستخدم للتحقق. */
  function markDerived(tables) {
    var journals = tables.filter(function (t) { return t.kind === "journal"; });
    journals.forEach(function (t) {
      t.prints = {};
      t.count = 0;
      for (var r = t.first; r <= t.last; r++) {
        var row = t.rows[r];
        if (!rowSig(row).any) continue;
        var p = rowPrint(t, row);
        t.prints[p] = (t.prints[p] || 0) + 1;
        t.count++;
      }
    });
    journals.forEach(function (a) {
      journals.forEach(function (b) {
        if (a === b || a.count === 0 || a.count >= b.count || a.derivedFrom) return;
        if (signatureRoles(a) !== signatureRoles(b)) return;
        var hit = 0;
        Object.keys(a.prints).forEach(function (p) { if (b.prints[p]) hit += Math.min(a.prints[p], b.prints[p]); });
        if (hit >= a.count * 0.9) {
          a.derivedFrom = b.id;
          a.derivedHits = hit;
          a.kind = "derived";
          a.use = false;
        }
      });
    });
  }

  function signatureRoles(t) {
    return ["date", "currency", "amount", "direction", "debit", "credit", "account"].map(function (k) { return t.roles[k] !== undefined ? "1" : "0"; }).join("");
  }

  /** القيم التي تحتاج مطابقة: اتجاهات وعملات (مع عددها) من الجداول المستخدمة. */
  function collectValues(a) {
    var dirs = {}, curs = {};
    a.tables.forEach(function (t) {
      if (!t.use) return;
      (t.roles.wide || []).forEach(function (w) { add(curs, w.h, guessCur); });
      for (var r = t.first; r <= t.last; r++) {
        var row = t.rows[r] || [];
        if (isHeaderRepeat(t, row)) continue;
        if (t.roles.direction !== undefined) add(dirs, text(row[t.roles.direction]), guessDir);
        if (t.roles.currency !== undefined) add(curs, text(row[t.roles.currency]), guessCur);
      }
    });
    // قيم من قوائم الإدخال (عملات معرّفة لم تُستخدم بعد)
    a.tables.forEach(function (t) {
      if (t.kind !== "lookup") return;
      t.cols.forEach(function (x) { if (x.p.ratio(x.p.cur) >= 0.6) topValues(x.p, 40).forEach(function (k) { if (!curs[k]) curs[k] = { text: k, n: 0, guess: guessCur(k), listed: true }; }); });
    });
    function add(m, v, g) {
      if (!v) return;
      var k = key(v);
      if (!m[k]) m[k] = { text: clean(v), n: 0, guess: g(v) };
      m[k].n++;
    }
    return { dirs: dirs, curs: curs };
  }

  // ===================================================================================
  // البناء: صفوف ← مكاتب وحركات (بالقاعدة نفسها التي تحسب بها معادلات الملف)
  // ===================================================================================

  function newModel() {
    return { offices: [], officeIndex: {}, openings: [], txs: [], errors: [], warnings: [], skipped: [], checks: [], used: {}, rowsRead: 0, both: 0,
      issues: {}, direct: {}, filledDates: 0, emptyRows: 0, perTable: {}, keyTables: {}, balanceLogs: [], balanceChecks: [], impliedOpenings: [],
      lastBalance: {}, midOpenings: [], totalsSkipped: 0 };
  }

  var REASONS = {
    noAccount: "بلا اسم حساب", noCurrency: "بلا عملة", badCurrency: "عملة غير مطابَقة", noAmount: "بلا مبلغ", badAmount: "مبلغ غير مفهوم",
    zero: "مبلغ صفر", noDir: "بلا اتجاه (لنا/علينا)", badDir: "اتجاه غير مطابَق", excludedCur: "عملة مستبعدة", excludedDir: "اتجاه مستبعد",
    future: "تاريخ في المستقبل", decimals: "منازل عشرية أكثر من المسموح للعملة", filledDate: "بلا تاريخ صالح — أُخذ تاريخ الصف السابق",
    badFee: "أجرة غير مفهومة"
  };
  function flip(d) { return d === "D" ? "C" : "D"; }

  /** «رصيد سابق» و«رصيد منقول» وما يشبهها: رصيد يسبق الحركات. */
  var OPEN_RE = new RegExp(C.OPENING_RE.source + "|^(رصيد|الرصيد)\\s*(ال)?(منقول|مرحل|مدور)", "i");

  function issue(model, code, t, r, row, counted) {
    var g = model.issues[code] = model.issues[code] || { code: code, label: REASONS[code], counted: !!counted, rows: [] };
    if (g.rows.length < 3000) g.rows.push({ sheet: t.label, row: r + 1, cells: preview(t, row) });
    g.total = (g.total || 0) + 1;
  }

  function preview(t, row) {
    var o = {};
    ["date", "currency", "amount", "debit", "credit", "fee", "direction", "account", "description", "ref", "balance"].forEach(function (k) {
      var c = t.roles[k];
      if (c !== undefined) o[k] = text((row || [])[c]);
    });
    (t.roles.wide || []).forEach(function (w) { var v = text((row || [])[w.c]); if (v) o.amount = (o.amount ? o.amount + " • " : "") + w.h + ": " + v; });
    return o;
  }

  /** أول نص في الصف داخل أعمدة الجدول فقط. */
  function rowLabelIn(t, row) {
    for (var c = t.c0; c <= t.c1; c++) {
      var x = (row || [])[c];
      if (x && x.s !== undefined && clean(x.s)) return clean(x.s);
    }
    return "";
  }

  /** اسم المكتب في التطبيق بعد إعادة التسمية (دون إنشاء المكتب). */
  function officeKeyFor(cfg, name) {
    var nm = clean(name);
    return key(cfg.rename && cfg.rename[key(nm)] ? clean(cfg.rename[key(nm)]) : nm);
  }

  function officeOf(model, cfg, name, note) {
    var nm = clean(name);
    var shown = cfg.rename && cfg.rename[key(nm)] ? clean(cfg.rename[key(nm)]) : nm;
    var k = key(shown);
    var i = model.officeIndex[k];
    if (i === undefined) {
      i = model.offices.length;
      model.officeIndex[k] = i;
      model.offices.push({ name: shown, key: k, phone: "", variants: {}, tx: 0, openings: 0, notes: note || "" });
    }
    model.offices[i].variants[nm] = true;
    return i;
  }

  function addDirect(model, t, oi, code, signed) {
    var dk = model.offices[oi].key + "|" + code;
    model.direct[dk] = (model.direct[dk] || 0n) + signed;
    (model.keyTables[dk] = model.keyTables[dk] || {})[t.id] = true;
    model.used[code] = true;
  }

  function curDef(cfg, code) {
    var d = cfg.currencies && cfg.currencies[code];
    if (d) return d;
    var k = C.KNOWN[code];
    return k ? { name: k.name, dec: k.dec } : { name: code, dec: 2 };
  }

  /**
   * قيمة خلية «الرصيد» بالوحدة الصغرى وإشارتها كما في الملف. الرصيد المحسوب بمعادلة قد يحمل كسرًا عائمًا ضئيلًا
   * (70987.00000000001) فيُقرّب إلى منازل العملة فقط إن كان الفرق أقل من جزء من ألف من الوحدة الصغرى.
   * «لنا 500» / «علينا 500» تُقرأ بإشارتها؛ نص لا يُفهم اتجاهه ← null.
   */
  function balanceMinor(cell, dec) {
    if (!cell) return null;
    if (cell.n !== undefined && typeof cell.n === "number") {
      if (!isFinite(cell.n)) return null;
      var f = Math.pow(10, dec), x = cell.n * f, r = Math.round(x);
      if (Math.abs(x - r) > 1e-3 || Math.abs(r) > 9e15) return null;
      return BigInt(r);
    }
    var p = C.parseAmount(cell);
    if (!p.ok || p.empty) return null;
    var m = C.toMinor(p, dec);
    if (m === null) return null;
    var neg = p.neg;
    if (p.hadText) {
      var g = guessDir(clean(normDigits(text(cell))).replace(/[\d.,()\-+]/g, " "));
      if (!g) return null;
      if (g === "C") neg = !neg;
    }
    return neg ? -m : m;
  }

  /**
   * cfg: { now, dirMap{key→D|C|X}, curMap{key→code|__skip}, currencies{code→{name,dec}}, rename{}, use{tableId→bool},
   *        positiveIs{tableId→D|C}, balanceSign{tableId→D|C}, fixedCurrency{tableId→code}, defaultCurrency, listedAccounts (bool),
   *        inferOpening (bool, افتراضيًا نعم), openingDate }
   */
  function build(a, cfg, serialToDate) {
    var model = newModel();
    var used = a.tables.filter(function (t) { return cfg.use && t.id in cfg.use ? cfg.use[t.id] : t.use; });
    function rolesOf(t) { return Object.assign({}, t.roles, (cfg.roles && cfg.roles[t.id]) || {}); }
    used.forEach(function (t) { if (t.kind !== "balances" && t.kind !== "summary") buildJournal(model, cfg, t, rolesOf(t), serialToDate); });
    resolveBalances(model, cfg);
    // الأرصدة السابقة من جداول الأرصدة: قبل أول حركة بدقيقة (أو بالتاريخ الذي يحدده المستخدم)
    var first = null;
    model.txs.forEach(function (x) { if (first === null || x.when < first) first = x.when; });
    model.openings.forEach(function (x) { if (first === null || x.when < first) first = x.when; });
    var when = cfg.openingDate || (first !== null ? first - 60000 : cfg.now - 60000);
    used.forEach(function (t) { if (t.kind === "balances" || t.kind === "summary") buildBalances(model, cfg, t, rolesOf(t), when); });
    finalizeLast(model);
    // مكاتب من قائمة الأسماء بلا حركات
    if (cfg.listedAccounts !== false) {
      a.lists.forEach(function (l) {
        if (cfg.use && l.id in cfg.use && !cfg.use[l.id]) return;
        (l.values || []).forEach(function (v) {
          var i = officeOf(model, cfg, v, "");
          if (!model.offices[i].tx && !model.offices[i].openings) model.offices[i].emptyOk = true;
        });
      });
    }
    summaryChecks(a, model, cfg);
    model.offices.forEach(function (o) {
      var v = Object.keys(o.variants);
      if (v.length > 1) model.warnings.push("دُمجت كتابات مختلفة لاسم «" + o.name + "»: " + v.map(function (x) { return "«" + x + "»"; }).join(" / "));
    });
    // أسماء متشابهة لم تُدمج
    var groups = {};
    model.offices.forEach(function (o) { var k = C.looseKey(o.name); (groups[k] = groups[k] || []).push(o.name); });
    Object.keys(groups).forEach(function (k) { if (groups[k].length > 1) model.warnings.push("أسماء متشابهة قد تكون الحساب نفسه: " + groups[k].join(" / ") + " — وحّدها من «أسماء المكاتب» إن كانت حسابًا واحدًا."); });
    if (model.both) model.warnings.push(model.both + " صف فيه مبلغ «لنا» ومبلغ «علينا» معًا: سُجّل كل منهما حركة.");
    if (model.midOpenings.length) {
      model.warnings.push(model.midOpenings.length + " صف «رصيد سابق/منقول» جاء بعد حركات الحساب نفسه، ونُقل كما هو (كما تجمعه معادلات ملفك). تأكد أنه ليس تكرارًا لمجموع ما قبله: " +
        model.midOpenings.slice(0, 8).map(function (m) { return m.sheet + " صف " + m.row + " (" + m.office + ")"; }).join("، ") + (model.midOpenings.length > 8 ? "…" : ""));
    }
    return model;
  }

  function buildJournal(model, cfg, t, roles, serialToDate) {
    var stat = model.perTable[t.id] = { rows: 0, imported: 0, excluded: 0, empty: 0, filled: 0, totals: 0, balanceOnly: 0 };
    var order = C.guessDateOrder(sample(t, roles.date));
    var prevDate = null, firstDate = null;
    // أول تاريخ صالح (لصفوف بلا تاريخ في بداية الجدول)
    for (var r0 = t.first; r0 <= t.last && !firstDate && roles.date !== undefined; r0++) firstDate = validDate(t.rows[r0], roles.date, order, serialToDate);
    var sheetAccount = roles.account === undefined ? clean(t.sheetAccount || "") : null;
    var pos = (cfg.positiveIs && cfg.positiveIs[t.id]) || "D";
    var log = roles.balance !== undefined ? [] : null;
    var seen = {};
    function currencyOf(row) {
      if (roles.currency === undefined) return { code: (cfg.fixedCurrency && cfg.fixedCurrency[t.id]) || t.sheetCurrency || cfg.defaultCurrency || "USD" };
      var ct = text(row[roles.currency]);
      if (!ct) return { bad: "noCurrency" };
      var cd = (cfg.curMap && cfg.curMap[key(ct)]) || guessCur(ct);
      if (cd === "__skip") return { bad: "excludedCur" };
      if (!cd) return { bad: "badCurrency" };
      return { code: cd };
    }
    for (var r = t.first; r <= t.last; r++) {
      var row = t.rows[r] || [];
      if (!rowSig(row).any) continue;
      if (isHeaderRepeat(t, row)) continue;
      var acc = roles.account !== undefined ? text(row[roles.account]) : sheetAccount || "";
      var curT = roles.currency !== undefined ? text(row[roles.currency]) : "";
      var dirT = roles.direction !== undefined ? text(row[roles.direction]) : "";
      var desc = roles.description !== undefined ? text(row[roles.description]) : "";
      var label = rowLabelIn(t, row);
      var isOpening = OPEN_RE.test(desc) || OPEN_RE.test(label);
      var rowDate = roles.date !== undefined ? validDate(row, roles.date, order, serialToDate) : null;
      // صف مجموع بلا تاريخ («المجموع»، «الإجمالي»، «Total»): ليس حركة
      if (!rowDate && (C.TOTAL_RE.test(label) || C.TOTAL_RE.test(desc) || (roles.account !== undefined && C.TOTAL_RE.test(acc)))) {
        model.skipped.push({ sheet: t.label, row: r + 1, reason: "صف مجموع" });
        model.totalsSkipped++;
        stat.totals++;
        continue;
      }
      // المبالغ واتجاهاتها
      var parts = [], bad = null;
      if (roles.debit !== undefined && roles.credit !== undefined) {
        [["D", roles.debit], ["C", roles.credit]].forEach(function (x) {
          var p = C.parseAmount(row[x[1]]);
          if (p.empty || (p.ok && p.zero)) return;
          if (!p.ok) { bad = "badAmount"; return; }
          parts.push({ dir: p.neg ? (x[0] === "D" ? "C" : "D") : x[0], p: p });
        });
      } else if (roles.amount !== undefined) {
        var pa = C.parseAmount(row[roles.amount]);
        if (!pa.empty && !(pa.ok && pa.zero)) {
          if (!pa.ok) bad = "badAmount";
          else if (roles.direction !== undefined) {
            var dm = dirT ? (cfg.dirMap && cfg.dirMap[key(dirT)]) || guessDir(dirT) : null;
            if (!dirT) bad = "noDir";
            else if (dm === "X") bad = "excludedDir";
            else if (dm !== "D" && dm !== "C") bad = "badDir";
            // مبلغ سالب مع اتجاه: كما تجمعه معادلات Excel (يطرح)، أي عكس الاتجاه
            else parts.push({ dir: pa.neg ? (dm === "D" ? "C" : "D") : dm, p: pa });
          } else parts.push({ dir: pa.neg ? (pos === "D" ? "C" : "D") : pos, p: pa });
        } else if (pa.ok && pa.zero) bad = "zero";
      }
      // الأجرة: تتبع الحركة في التطبيق (قيد أجرة مستقل) وتدخل في الرصيد، إلا إن اختار المستخدم «لا تُحسب»
      var feeP = null, feeText = roles.fee !== undefined ? text(row[roles.fee]) : "";
      var feeDir = (cfg.feeDir && cfg.feeDir[t.id]) || "D";
      if (feeText && feeDir !== "X") {
        var fp = C.parseAmount(row[roles.fee]);
        if (!fp.ok) bad = bad && bad !== "zero" ? bad : "badFee";
        else if (!fp.zero) feeP = fp;
      }
      // صف أجرة فقط (بلا مبلغ أصل): الأجرة نفسها حركة باتجاهها
      if (feeP && !parts.length && (!bad || bad === "zero")) { parts.push({ dir: feeP.neg ? flip(feeDir) : feeDir, p: feeP }); feeP = null; bad = null; }
      var hasAny = parts.length > 0 || bad;
      if (!hasAny) {
        // صف رصيد فقط (رصيد منقول/سابق بلا مبلغ): للتحقق ولاستنتاج الرصيد السابق، لا يغيّر شيئًا وحده
        if (log && clean(acc) && text(row[roles.balance])) {
          var cb = currencyOf(row);
          var bm = cb.code ? balanceMinor(row[roles.balance], curDef(cfg, cb.code).dec) : null;
          if (bm !== null) {
            log.push({ r: r, acc: acc, code: cb.code, delta: 0n, bal: bm, when: rowDate ? C.epoch(rowDate, 12) : null, only: true });
            seen[key(acc) + "|" + cb.code] = true;
            stat.balanceOnly++;
            continue;
          }
        }
        // صف بلا مبلغ: فارغ (رقم قيد أو تاريخ أو بيان فقط) أو ناقص
        if ((roles.account !== undefined && acc) || curT || dirT) { issue(model, "noAmount", t, r, row); stat.excluded++; }
        else { model.emptyRows++; stat.empty++; }
        continue;
      }
      stat.rows++;
      model.rowsRead++;
      if (bad && bad !== "zero") { issue(model, bad, t, r, row); stat.excluded++; continue; }
      if (bad === "zero") { model.skipped.push({ sheet: t.label, row: r + 1, reason: REASONS.zero }); continue; }
      if (!clean(acc)) { issue(model, "noAccount", t, r, row); stat.excluded++; continue; }
      var cc = currencyOf(row);
      if (cc.bad) { issue(model, cc.bad, t, r, row); stat.excluded++; continue; }
      var code = cc.code;
      var def = curDef(cfg, code);
      var made = [];
      for (var k = 0; k < parts.length; k++) {
        var m = C.toMinor(parts[k].p, def.dec);
        if (m === null) { made = null; break; }
        made.push({ dir: parts[k].dir, minor: m });
      }
      var feeMinor = null;
      if (made && feeP) { feeMinor = C.toMinor(feeP, def.dec); if (feeMinor === null) made = null; }
      if (!made) { issue(model, "decimals", t, r, row); stat.excluded++; continue; }
      var feeD = feeP ? (feeP.neg ? flip(feeDir) : feeDir) : "D";
      // التاريخ
      var dt = rowDate;
      var filled = false;
      if (!dt) {
        dt = prevDate || firstDate;
        filled = true;
      }
      if (!dt) {
        dt = { y: new Date(cfg.now).getFullYear(), m: new Date(cfg.now).getMonth() + 1, d: new Date(cfg.now).getDate(), H: 12, M: 0, S: 0, hasTime: false };
      }
      prevDate = dt;
      var when = C.epoch(dt, 12);
      if (when > cfg.now + 10 * 60000) { issue(model, "future", t, r, row); stat.excluded++; continue; }
      if (filled) { issue(model, "filledDate", t, r, row, true); stat.filled++; model.filledDates++; }
      var oi = officeOf(model, cfg, acc, "");
      var gk = key(acc) + "|" + code;
      if (isOpening && seen[gk]) model.midOpenings.push({ sheet: t.label, row: r + 1, office: model.offices[oi].name });
      seen[gk] = true;
      var notes = [];
      if (roles.ref !== undefined && text(row[roles.ref])) notes.push("رقم القيد في الملف: " + text(row[roles.ref]));
      (roles.notes || []).forEach(function (c) { var v = text(row[c]); if (v) notes.push(t.names[c - t.c0] + ": " + v); });
      if (feeText && feeDir === "X") notes.push(t.names[roles.fee - t.c0] + " (لم تُحسب في الرصيد): " + feeText);
      notes.push("منقولة من: " + t.label + " — صف " + (r + 1) + (filled ? " (التاريخ من الصف السابق)" : ""));
      if (roles.phone !== undefined && !model.offices[oi].phone) model.offices[oi].phone = text(row[roles.phone]);
      if (made.length === 2) model.both++;
      var delta = 0n, feeLeft = feeMinor;
      made.forEach(function (x) {
        var signed = x.dir === "D" ? x.minor : -x.minor;
        var fee = 0n;
        if (feeLeft !== null) { fee = feeLeft; feeLeft = null; }
        var feeSigned = fee === 0n ? 0n : (feeD === "D" ? fee : -fee);
        if (isOpening) {
          signed += feeSigned;
          fee = 0n;
          feeSigned = 0n;
          if (signed !== 0n) {
            model.openings.push({ office: oi, code: code, minor: signed, when: when - 60000, src: { sheet: t.label, row: r + 1, tid: t.id } });
            model.offices[oi].openings++;
          }
        } else {
          model.txs.push({ office: oi, when: when, dir: x.dir, minor: x.minor, code: code, person: "", desc: desc, notes: notes.join(" • "),
            fee: fee, feeDir: feeD, src: { sheet: t.label, row: r + 1, tid: t.id }, seq: model.txs.length });
          model.offices[oi].tx++;
        }
        delta += signed + feeSigned;
        addDirect(model, t, oi, code, signed + feeSigned);
      });
      if (log) log.push({ r: r, acc: acc, code: code, delta: delta, bal: text(row[roles.balance]) ? balanceMinor(row[roles.balance], def.dec) : null, when: when });
      stat.imported++;
    }
    if (log) model.balanceLogs.push({ t: t, log: log });
  }

  /**
   * عمود «الرصيد» في سجل الحركات (دفتر لكل مكتب غالبًا):
   *  1) إشارته تُعرف من الملف نفسه: الفرق بين رصيدين متتاليين = مبلغ الحركة (موجب = لنا) أو عكسه (موجب = علينا).
   *  2) إن بدأ الحساب برصيد سابق غير مكتوب كحركة (رصيد أول صف ≠ مبلغه) يُضاف رصيدًا سابقًا مستنتجًا، فيطابق رصيدنا رصيد ملفك.
   *  3) يُتحقق من كل صف: رصيدنا التراكمي = رصيد الملف في الصف نفسه.
   */
  function resolveBalances(model, cfg) {
    var lastByKey = {};
    // الإشارة لكل جدول من فروق أرصدته المتتالية؛ والجدول بلا حركتين متتاليتين (ورقة مكتب بحركة واحدة) يأخذ إشارة باقي أوراق الملف
    var gPlus = 0, gMinus = 0;
    model.balanceLogs.forEach(function (bl) {
      var groups = {}, order = [];
      bl.log.forEach(function (e) {
        var g = key(e.acc) + "|" + e.code;
        if (!groups[g]) { groups[g] = []; order.push(g); }
        groups[g].push(e);
      });
      bl.groups = groups; bl.order = order;
      var p = evalSign(bl, 1n), m = evalSign(bl, -1n);
      // صفوف تفيد في الحكم: كل رصيد بعد أول رصيد في حسابه (الأول يطابق دائمًا لأنه يحدد الرصيد السابق)
      bl.inform = p.inform;
      bl.plus = p.ok; bl.minus = m.ok;
      bl.own = p.ok > m.ok && p.ok >= p.inform * 0.5 ? 1n : (m.ok > p.ok && m.ok >= m.inform * 0.5 ? -1n : 0n);
      if (bl.own === 1n) gPlus += p.ok - m.ok;
      if (bl.own === -1n) gMinus += m.ok - p.ok;
    });
    var global = gPlus > 0 && gMinus === 0 ? 1n : (gMinus > 0 && gPlus === 0 ? -1n : 0n);
    model.balanceLogs.forEach(function (bl) {
      var t = bl.t, groups = bl.groups, order = bl.order, plus = bl.plus, minus = bl.minus, pairs = bl.inform;
      var forced = cfg.balanceSign && cfg.balanceSign[t.id];
      var s = forced ? (forced === "D" ? 1n : -1n) : bl.own;
      var how = forced ? "forced" : "own";
      // إشارة باقي الأوراق: للتحقق فقط إن كان في الجدول ما يناقضها، ولا يُستنتج منها رصيد سابق
      var mayInfer = true;
      if (!forced && s === 0n && global !== 0n) { s = global; how = "book"; mayInfer = pairs === 0; }
      else if (!forced && s === 0n && pairs === 0) {
        // آخر احتمال: أول رصيد = أول مبلغ تمامًا (بلا رصيد سابق)
        order.some(function (g) {
          var cum = 0n;
          return groups[g].some(function (e) {
            cum += e.delta;
            if (e.bal === null || cum === 0n) return false;
            if (e.bal === cum) s = 1n; else if (e.bal === -cum) s = -1n;
            how = "first";
            return true;
          });
        });
      }
      var chk = { tid: t.id, table: t.label, column: t.names[t.roles.balance - t.c0] || "", sign: s === 1n ? "D" : s === -1n ? "C" : null, forced: !!forced, how: how,
        pairs: pairs, agree: Math.max(plus, minus), rows: 0, ok: 0, bad: [], badN: 0, implied: 0, groups: order.length };
      model.balanceChecks.push(chk);
      if (s === 0n) return;
      order.forEach(function (g) {
        var es = groups[g];
        var fb = -1;
        for (var i = 0; i < es.length; i++) if (es[i].bal !== null) { fb = i; break; }
        var start = 0n;
        if (fb >= 0) {
          var cum = 0n;
          for (var j = 0; j <= fb; j++) cum += es[j].delta;
          start = s * es[fb].bal - cum;
        }
        var oi = null;
        if (start !== 0n && cfg.inferOpening !== false && mayInfer) {
          oi = officeOf(model, cfg, es[0].acc, "");
          var fw = null;
          es.forEach(function (e) { if (fw === null && e.when !== null) fw = e.when; });
          var w = (fw !== null ? fw : cfg.now) - 120000;
          model.openings.push({ office: oi, code: es[0].code, minor: start, when: w, src: { sheet: t.label, row: es[0].r + 1, tid: t.id }, implied: true });
          model.offices[oi].openings++;
          addDirect(model, t, oi, es[0].code, start);
          model.impliedOpenings.push({ sheet: t.label, row: es[0].r + 1, office: model.offices[oi].name, code: es[0].code, minor: start });
          chk.implied++;
        } else start = 0n;
        var run = start;
        es.forEach(function (e) {
          run += e.delta;
          if (e.bal === null) return;
          chk.rows++;
          if (s * e.bal === run) chk.ok++;
          else {
            chk.badN++;
            if (chk.bad.length < 300) chk.bad.push({ row: e.r + 1, account: clean(e.acc), code: e.code, file: s * e.bal, ours: run });
          }
        });
        // الرصيد الأخير في الملف (إن كان آخر صف للحساب فيه رصيد) ← «الرصيد في ملفك» في المراجعة
        var last = es[es.length - 1];
        var ok = officeKeyFor(cfg, es[0].acc) + "|" + es[0].code;
        var lb = lastByKey[ok] = lastByKey[ok] || { sum: 0n, broken: false, tables: {} };
        if (last.bal === null) lb.broken = true;
        else lb.sum += s * last.bal;
        lb.tables[t.id] = true;
      });
    });
    model.lastByKey = lastByKey;
  }

  /** كم رصيدًا في الملف يطابق رصيدنا التراكمي لو كان الموجب = لنا (s=1) أو علينا (s=-1). */
  function evalSign(bl, s) {
    var ok = 0, inform = 0;
    bl.order.forEach(function (g) {
      var es = bl.groups[g], run = null;
      es.forEach(function (e) {
        if (run === null) {
          // أول رصيد في الحساب: يحدد ما قبله (الرصيد السابق)، فلا يُحكم به
          if (e.bal !== null) run = s * e.bal;
          return;
        }
        run += e.delta;
        if (e.bal === null) return;
        inform++;
        if (s * e.bal === run) ok++;
      });
    });
    return { ok: ok, inform: inform };
  }

  /** «الرصيد في ملفك» من عمود الرصيد: فقط إن كانت كل مبالغ المكتب بتلك العملة من جداول لها عمود رصيد. */
  function finalizeLast(model) {
    var lastByKey = model.lastByKey || {};
    Object.keys(lastByKey).forEach(function (k) {
      var lb = lastByKey[k];
      if (lb.broken) return;
      var all = Object.keys(model.keyTables[k] || {});
      if (all.every(function (id) { return lb.tables[id]; })) model.lastBalance[k] = { minor: lb.sum, src: "balance" };
    });
  }

  function buildBalances(model, cfg, t, roles, when) {
    var stat = model.perTable[t.id] = { rows: 0, imported: 0, excluded: 0, empty: 0, filled: 0, totals: 0, balanceOnly: 0 };
    var nets = balanceRows(model, cfg, t, roles, stat);
    nets.forEach(function (x) {
      var oi = officeOf(model, cfg, x.acc, "");
      if (x.phone && !model.offices[oi].phone) model.offices[oi].phone = x.phone;
      Object.keys(x.nets).forEach(function (code) {
        if (x.nets[code] === 0n) return;
        model.openings.push({ office: oi, code: code, minor: x.nets[code], when: when, src: { sheet: t.label, row: x.r + 1, tid: t.id } });
        model.offices[oi].openings++;
        addDirect(model, t, oi, code, x.nets[code]);
      });
    });
  }

  /** صفوف جدول أرصدة ← [{r, acc, nets{code→رصيد موقّع}}] (الموجب = لنا حسب اختيار positiveIs). */
  function balanceRows(model, cfg, t, roles, stat) {
    var pos = (cfg.positiveIs && cfg.positiveIs[t.id]) || "D";
    var out = [];
    for (var r = t.first; r <= t.last; r++) {
      var row = t.rows[r] || [];
      if (!rowSig(row).any || isHeaderRepeat(t, row)) continue;
      var acc = roles.account !== undefined ? text(row[roles.account]) : "";
      if (C.TOTAL_RE.test(acc) || (!clean(acc) && C.TOTAL_RE.test(rowLabelIn(t, row)))) {
        model.skipped.push({ sheet: t.label, row: r + 1, reason: "صف مجموع" });
        model.totalsSkipped++;
        stat.totals++;
        continue;
      }
      var parts = [], bad = null;
      if (roles.wide) {
        roles.wide.forEach(function (w) {
          var p = C.parseAmount(row[w.c]);
          if (p.empty || (p.ok && p.zero)) return;
          if (!p.ok) { bad = "badAmount"; return; }
          var wc = (cfg.curMap && cfg.curMap[key(w.h)]) || w.code;
          parts.push({ dir: p.neg ? (pos === "D" ? "C" : "D") : pos, p: p, code: wc });
        });
      } else if (roles.debit !== undefined && roles.credit !== undefined) {
        [["D", roles.debit], ["C", roles.credit]].forEach(function (x) {
          var p = C.parseAmount(row[x[1]]);
          if (p.empty || (p.ok && p.zero)) return;
          if (!p.ok) { bad = "badAmount"; return; }
          parts.push({ dir: p.neg ? (x[0] === "D" ? "C" : "D") : x[0], p: p });
        });
      } else if (roles.amount !== undefined) {
        var pa = C.parseAmount(row[roles.amount]);
        if (!pa.empty && !(pa.ok && pa.zero)) {
          if (!pa.ok) bad = "badAmount";
          else if (roles.direction !== undefined) {
            var dT = text(row[roles.direction]);
            var dm = dT ? (cfg.dirMap && cfg.dirMap[key(dT)]) || guessDir(dT) : null;
            if (dm === "X") bad = "excludedDir";
            else if (dm !== "D" && dm !== "C") bad = dT ? "badDir" : "noDir";
            else parts.push({ dir: pa.neg ? (dm === "D" ? "C" : "D") : dm, p: pa });
          } else parts.push({ dir: pa.neg ? (pos === "D" ? "C" : "D") : pos, p: pa });
        }
      }
      if (!parts.length && !bad) { stat.empty++; continue; }
      stat.rows++;
      model.rowsRead++;
      if (bad) { issue(model, bad, t, r, row); stat.excluded++; continue; }
      if (!clean(acc)) { issue(model, "noAccount", t, r, row); stat.excluded++; continue; }
      var rowCode = null;
      if (!roles.wide) {
        if (roles.currency !== undefined) {
          var curT = text(row[roles.currency]);
          if (!curT) { issue(model, "noCurrency", t, r, row); stat.excluded++; continue; }
          rowCode = (cfg.curMap && cfg.curMap[key(curT)]) || guessCur(curT);
          if (rowCode === "__skip") { issue(model, "excludedCur", t, r, row); stat.excluded++; continue; }
          if (!rowCode) { issue(model, "badCurrency", t, r, row); stat.excluded++; continue; }
        } else rowCode = (cfg.fixedCurrency && cfg.fixedCurrency[t.id]) || cfg.defaultCurrency || "USD";
      }
      var nets = {}, okDec = true;
      parts.forEach(function (x) {
        var code = x.code || rowCode;
        if (!code || code === "__skip") return;
        var m = C.toMinor(x.p, curDef(cfg, code).dec);
        if (m === null) { okDec = false; return; }
        nets[code] = (nets[code] || 0n) + (x.dir === "D" ? m : -m);
      });
      if (!okDec) { issue(model, "decimals", t, r, row); stat.excluded++; continue; }
      out.push({ r: r, acc: acc, nets: nets, phone: roles.phone !== undefined ? text(row[roles.phone]) : "" });
      stat.imported++;
    }
    return out;
  }

  /**
   * جدول أرصدة محسوب من الحركات (ملخص بمعادلات، أو قيم تطابق نتيجة الحركات): لا يُنقل حتى لا تتضاعف الأرصدة،
   * بل يُقارن كل رصيد فيه برصيد المكتب نفسه بعد النقل. الإشارة (الموجب = لنا أو علينا) تُعرف من المطابقة نفسها.
   */
  function compareSummary(a, model, cfg, t) {
    var sub = newModel();
    var rows = balanceRows(sub, Object.assign({}, cfg, { positiveIs: {} }), t, t.roles, { rows: 0, imported: 0, excluded: 0, empty: 0, filled: 0, totals: 0, balanceOnly: 0 });
    function score(s) {
      var ok = 0, n = 0, list = [];
      rows.forEach(function (x) {
        var k0 = officeKeyFor(cfg, x.acc);
        Object.keys(x.nets).forEach(function (code) {
          var file = s * x.nets[code];
          var ours = model.direct[k0 + "|" + code] || 0n;
          n++;
          if (file === ours) ok++;
          list.push({ row: x.r + 1, account: clean(x.acc), key: k0 + "|" + code, code: code, file: file, ours: ours, ok: file === ours, known: model.officeIndex[k0] !== undefined });
        });
      });
      return { ok: ok, n: n, list: list };
    }
    var forced = cfg.positiveIs && cfg.positiveIs[t.id];
    var p = score(1n), m = score(-1n);
    var best = forced ? (forced === "D" ? p : m) : (m.ok > p.ok ? m : p);
    return { tid: t.id, table: t.label, sign: forced ? forced : (best === p ? "D" : "C"), forced: !!forced, n: best.n, ok: best.ok, rows: best.list,
      nonzero: best.list.filter(function (x) { return x.file !== 0n; }).length };
  }

  function summaryChecks(a, model, cfg) {
    model.summaryChecks = [];
    a.tables.forEach(function (t) {
      if (t.kind !== "summary") return;
      if (cfg.use && cfg.use[t.id]) return;
      var res = compareSummary(a, model, cfg, t);
      model.summaryChecks.push(res);
      res.rows.forEach(function (x) { if (!model.lastBalance[x.key] && x.known) model.lastBalance[x.key] = { minor: x.file, src: "summary" }; });
    });
  }

  /**
   * اتجاه عمود الأجرة يُعرف من عمود الرصيد في الجدول نفسه: يُجرَّب «لنا» و«علينا» و«لا تُحسب»،
   * ويُعتمد الذي تطابق به أرصدة الملف سطرًا بسطر (90% فأكثر وأفضل من غيره). بلا عمود رصيد: «لنا» كأداة النقل، ويمكن تغييره.
   */
  function detectFeeDirs(a, cfg, serialToDate) {
    var out = {};
    a.tables.forEach(function (t) {
      if (!t.use || t.kind === "balances" || t.kind === "summary" || t.roles.fee === undefined || t.roles.balance === undefined) return;
      if (cfg.feeDir && cfg.feeDir[t.id]) return;
      var best = null;
      ["D", "C", "X"].forEach(function (h) {
        var c2 = Object.assign({}, cfg, { feeDir: Object.assign({}, cfg.feeDir || {}), inferOpening: true });
        c2.feeDir[t.id] = h;
        var sub = newModel();
        buildJournal(sub, c2, t, t.roles, serialToDate);
        resolveBalances(sub, c2);
        var ch = sub.balanceChecks[0];
        var score = ch && ch.rows && ch.how !== "book" ? ch.ok / ch.rows : 0;
        var implied = ch ? ch.implied : 0;
        // التساوي يُحسم بالأبسط: الاحتمال الذي لا يحتاج رصيدًا سابقًا مستنتجًا
        if (!best || score > best.score || (score === best.score && implied < best.implied)) best = { h: h, score: score, implied: implied, tie: false };
        else if (score === best.score && implied === best.implied) best.tie = true;
      });
      if (best && best.score >= 0.9 && !best.tie) {
        cfg.feeDir = cfg.feeDir || {};
        cfg.feeDir[t.id] = best.h;
        t.feeDetected = best.h;
        out[t.id] = best.h;
      }
    });
    return out;
  }

  /**
   * بعد التحليل: جدول أرصدة في ملف فيه سجل حركات — إن طابقت قيمه نتيجة الحركات (90% فأكثر) فهو ملخص لها لا أرصدة سابقة.
   * (جداول الأرصدة بمعادلات صُنّفت ملخصًا من قبل.)
   */
  function detectSummaries(a, cfg, serialToDate) {
    var journals = a.tables.filter(function (t) { return t.use && t.kind !== "balances" && t.kind !== "summary"; });
    var bal = a.tables.filter(function (t) { return t.kind === "balances" && t.use; });
    if (!journals.length || !bal.length) return [];
    var c2 = Object.assign({}, cfg, { use: {} });
    a.tables.forEach(function (t) { c2.use[t.id] = journals.indexOf(t) >= 0; });
    var m = build(a, Object.assign(c2, { listedAccounts: false }), serialToDate);
    var found = [];
    bal.forEach(function (t) {
      var res = compareSummary(a, m, cfg, t);
      var nz = res.rows.filter(function (x) { return x.file !== 0n; });
      var hit = nz.filter(function (x) { return x.ok; }).length;
      if (nz.length >= 3 && hit >= nz.length * 0.9) {
        t.kind = "summary";
        t.use = false;
        t.summaryWhy = "values";
        found.push(t.id);
      }
    });
    return found;
  }

  function validDate(row, c, order, serialToDate) {
    var cell = (row || [])[c];
    if (!cell) return null;
    var d = C.parseDate(cell, order, serialToDate);
    return d && d.y >= 1990 ? d : null;
  }

  function sample(t, c) {
    var out = [];
    if (c === undefined) return out;
    for (var r = t.first; r <= t.last && out.length < 500; r++) { var x = (t.rows[r] || [])[c]; if (x) out.push(x); }
    return out;
  }

  /** منازل عشرية مقترحة لكل عملة: الأكبر بين المعتاد وما في الملف (لا تقريب أبدًا). */
  function suggestDecimals(a, cfg) {
    var dec = {};
    a.tables.forEach(function (t) {
      if (!t.use) return;
      (t.roles.wide || []).forEach(function (w) {
        var code = (cfg.curMap && cfg.curMap[key(w.h)]) || w.code;
        if (!code || code === "__skip") return;
        for (var r = t.first; r <= t.last; r++) {
          var p = C.parseAmount((t.rows[r] || [])[w.c]);
          if (p.ok) dec[code] = Math.max(dec[code] || 0, p.frac.length);
        }
      });
      if (t.roles.currency === undefined) {
        var fc = (cfg.fixedCurrency && cfg.fixedCurrency[t.id]) || t.sheetCurrency || cfg.defaultCurrency || "USD";
        [t.roles.amount, t.roles.debit, t.roles.credit].forEach(function (c) {
          if (c === undefined) return;
          for (var r = t.first; r <= t.last; r++) {
            var p = C.parseAmount((t.rows[r] || [])[c]);
            if (p.ok) dec[fc] = Math.max(dec[fc] || 0, p.frac.length);
          }
        });
        return;
      }
      var am = [t.roles.amount, t.roles.debit, t.roles.credit].filter(function (x) { return x !== undefined; });
      for (var r = t.first; r <= t.last; r++) {
        var row = t.rows[r] || [];
        var curT = text(row[t.roles.currency]);
        if (!curT) continue;
        var code = (cfg.curMap && cfg.curMap[key(curT)]) || guessCur(curT);
        if (!code || code === "__skip") continue;
        am.forEach(function (c) {
          var p = C.parseAmount(row[c]);
          if (p.ok) dec[code] = Math.max(dec[code] || 0, p.frac.length);
        });
      }
    });
    return dec;
  }

  // ===================================================================================
  // التحقق من معادلات الملف نفسه: SUMIFS / SUMIF على جداوله، تُحسب من الصفوف وتقارَن بالقيم المحفوظة
  // ===================================================================================

  function splitArgs(s) {
    var out = [], depth = 0, q = false, cur = "";
    for (var i = 0; i < s.length; i++) {
      var ch = s.charAt(i);
      if (ch === '"') { q = !q; cur += ch; continue; }
      if (!q && ch === "(") depth++;
      if (!q && ch === ")") depth--;
      if (!q && depth === 0 && (ch === "," || ch === ";")) { out.push(cur.trim()); cur = ""; continue; }
      cur += ch;
    }
    out.push(cur.trim());
    return out;
  }

  /** «SUMIFS(…)-SUMIFS(…)» ليست استدعاءً واحدًا: يجب ألا يُغلق القوس الأول قبل النهاية. */
  function wholeCall(inner) {
    var depth = 0, q = false;
    for (var i = 0; i < inner.length; i++) {
      var ch = inner.charAt(i);
      if (ch === '"') q = !q;
      else if (!q && ch === "(") depth++;
      else if (!q && ch === ")") { depth--; if (depth < 0) return false; }
    }
    return depth === 0 && !q;
  }

  function resolveColumn(a, sheet, ref) {
    // مرجع منظّم: Table4[Ammount] أو Table4[[#Data],[Ammount]]
    var m = ref.match(/^([A-Za-z_؀-ۿ][\w.؀-ۿ]*)\[(?:\[#[^\]]+\],)?\[?([^\]]+)\]?\]$/);
    if (m) {
      var t = a.tables.filter(function (x) { return x.excel && x.excel.toLowerCase() === m[1].toLowerCase(); })[0];
      if (!t) return null;
      var name = m[2].replace(/^\[|\]$/g, "").replace(/'(.)/g, "$1");
      var i = t.names.map(function (n) { return n.toLowerCase(); }).indexOf(name.toLowerCase());
      return i < 0 ? null : { t: t, c: t.c0 + i };
    }
    // نطاق عادي: DATA!$C$4:$C$30652 أو C:C
    var mm = ref.replace(/\$/g, "").match(/^(?:'?([^'!]+)'?!)?([A-Z]+)(\d*):([A-Z]+)(\d*)$/i);
    if (!mm || mm[2].toUpperCase() !== mm[4].toUpperCase()) return null;
    var sh = mm[1] || sheet;
    var c = colIndex(mm[2].toUpperCase());
    var tt = a.tables.filter(function (x) { return x.sheet === sh && c >= x.c0 && c <= x.c1; })[0];
    return tt ? { t: tt, c: c } : null;
  }

  function criterion(raw, ws) {
    var s = raw.trim();
    var lit;
    if (/^".*"$/.test(s)) lit = s.slice(1, -1).replace(/""/g, '"');
    else if (/^-?\d+(\.\d+)?$/.test(s)) lit = s;
    else if (/^\$?[A-Z]+\$?\d+$/i.test(s) && ws) { var cell = ws[s.replace(/\$/g, "").toUpperCase()]; lit = cell ? String(cell.v) : ""; }
    else return null;
    var op = "=";
    var mo = lit.match(/^(<>|>=|<=|=|>|<)(.*)$/);
    if (mo) { op = mo[1]; lit = mo[2]; }
    if (op !== "=" && op !== "<>") return null;
    var rx = /[*?]/.test(lit) ? new RegExp("^" + lit.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".") + "$", "i") : null;
    var low = lit.toLowerCase();
    return function (v) {
      var x = v === null || v === undefined ? "" : String(v).toLowerCase();
      var eq = rx ? rx.test(x) : x === low;
      return op === "=" ? eq : !eq;
    };
  }

  function cellRaw(cell) {
    if (!cell) return "";
    if (cell.s !== undefined) return cell.s;
    if (cell.n !== undefined) return cell.n;
    return text(cell);
  }

  function formulaChecks(books, a) {
    var out = [];
    books.forEach(function (bk) {
      if (!bk.wb) return;
      bk.wb.SheetNames.forEach(function (sn) {
        var ws = bk.wb.Sheets[sn];
        if (!ws || !ws["!ref"]) return;
        Object.keys(ws).forEach(function (addr) {
          if (addr.charAt(0) === "!") return;
          var cell = ws[addr];
          if (!cell || !cell.f || typeof cell.v !== "number") return;
          var m = cell.f.match(/^\s*(SUMIFS|SUMIF)\((.*)\)\s*$/i);
          if (!m || !wholeCall(m[2])) return;
          var args = splitArgs(m[2]);
          var sum, conds = [];
          if (m[1].toUpperCase() === "SUMIFS") {
            if (args.length < 3 || args.length % 2 !== 1) return;
            sum = resolveColumn(a, sn, args[0]);
            for (var i = 1; i < args.length; i += 2) conds.push({ col: resolveColumn(a, sn, args[i]), f: criterion(args[i + 1], ws) });
          } else {
            if (args.length < 2) return;
            var rng = resolveColumn(a, sn, args[0]);
            sum = args[2] ? resolveColumn(a, sn, args[2]) : rng;
            conds.push({ col: rng, f: criterion(args[1], ws) });
          }
          if (!sum || conds.some(function (x) { return !x.col || !x.f || x.col.t !== sum.t; })) return;
          var t = sum.t, total = 0;
          for (var r = t.first; r <= t.last; r++) {
            var row = t.rows[r] || [];
            if (!conds.every(function (x) { return x.f(cellRaw(row[x.col.c])); })) continue;
            var p = C.parseAmount(row[sum.c]);
            if (p.ok && !p.empty) total += (p.neg ? -1 : 1) * Number(p.int + (p.frac ? "." + p.frac : ""));
          }
          out.push({ sheet: sn, cell: addr, formula: "=" + cell.f, cached: cell.v, computed: Math.round(total * 1e6) / 1e6, ok: Math.abs(total - cell.v) < 1e-6, table: t.label });
        });
      });
    });
    return out;
  }

  /**
   * تحقق إضافي: نسخة مفلترة لحساب واحد (مثل ورقة Detailed) ← رصيدها لكل عملة من صفوفها
   * يجب أن يساوي رصيد الحساب نفسه في النقل.
   */
  function derivedChecks(a, model, cfg, serialToDate) {
    var out = [];
    a.tables.forEach(function (t) {
      if (t.kind !== "derived" || t.roles.account === undefined || t.roles.currency === undefined) return;
      var accs = {};
      for (var r = t.first; r <= t.last; r++) { var v = text((t.rows[r] || [])[t.roles.account]); if (v) accs[key(v)] = clean(v); }
      var ks = Object.keys(accs);
      if (ks.length !== 1) return;
      var sub = newModel();
      var tt = Object.assign({}, t, { kind: "journal" });
      buildJournal(sub, Object.assign({}, cfg, { now: Number.MAX_SAFE_INTEGER }), tt, Object.assign({}, t.roles), serialToDate || null);
      var oi = model.officeIndex[key(cfg.rename && cfg.rename[ks[0]] ? cfg.rename[ks[0]] : accs[ks[0]])];
      if (oi === undefined) return;
      var o = model.offices[oi];
      Object.keys(sub.direct).forEach(function (k) {
        var code = k.split("|").pop();
        var mine = model.direct[o.key + "|" + code] || 0n;
        out.push({ sheet: t.label, account: o.name, code: code, file: sub.direct[k], ours: mine, ok: sub.direct[k] === mine, dec: curDef(cfg, code).dec });
      });
    });
    return out;
  }

  /** صفوف الورقة بأرقامها الحقيقية: الصف 0 = الصف 1 في Excel حتى لو بدأت الورقة من صف لاحق. */
  function sheetRows(ws, XLSX) {
    if (!ws || !ws["!ref"]) return [];
    var rg = XLSX.utils.decode_range(ws["!ref"]);
    var rows = C.sheetRows(ws, XLSX);
    var pad = [];
    for (var i = 0; i < rg.s.r; i++) pad.push(null);
    return pad.concat(rows);
  }

  return {
    sheetRows: sheetRows, excelTables: excelTables, decodeRef: decodeRef, guessDir: guessDir, guessCur: guessCur, guessTables: guessTables, inferRoles: inferRoles,
    analyze: analyze, build: build, suggestDecimals: suggestDecimals, formulaChecks: formulaChecks, derivedChecks: derivedChecks,
    detectSummaries: detectSummaries, detectFeeDirs: detectFeeDirs, reclassify: reclassify, collectValues: collectValues, balanceMinor: balanceMinor, OPEN_RE: OPEN_RE,
    REASONS: REASONS, colName: colName, key: key, topValues: topValues, curDef: curDef
  };
})(typeof Core !== "undefined" ? Core : require("../migration-tool/core.js"));
if (typeof module !== "undefined") module.exports = Smart;
