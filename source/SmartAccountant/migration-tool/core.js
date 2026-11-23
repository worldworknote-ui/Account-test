/*
 * أداة النقل إلى «المحاسب الذكي» — المنطق (بدون واجهة).
 * تقرأ صفوف Excel/CSV، تحوّلها إلى مكاتب وأرصدة افتتاحية وحركات بقيد مزدوج مطابق تمامًا لما يكتبه التطبيق،
 * ثم تكتب ملف نسخة احتياطية بصيغة SMARTBK5 (ZIP داخل غلاف AES-256-GCM مقسّم إلى أجزاء).
 * المبالغ كلها أعداد صحيحة بالوحدة الصغرى (BigInt) حتى لا يدخل أي خطأ تقريب في الحسابات.
 */
"use strict";
var Core = (function () {
  // ===================================================================================
  // نصوص
  // ===================================================================================
  var AR_DIGITS = { "٠": "0", "١": "1", "٢": "2", "٣": "3", "٤": "4", "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9",
    "۰": "0", "۱": "1", "۲": "2", "۳": "3", "۴": "4", "۵": "5", "۶": "6", "۷": "7", "۸": "8", "۹": "9", "٫": ".", "٬": ",", "−": "-", "–": "-" };

  function normDigits(s) {
    return String(s).replace(/[٠-٩۰-۹٫٬−–]/g, function (c) { return AR_DIGITS[c]; });
  }

  function clean(v) {
    if (v === null || v === undefined) return "";
    return String(v).replace(/[‎‏‪-‮⁦-⁩ ]/g, " ").replace(/\s+/g, " ").trim();
  }

  /** مفتاح مطابقة أسماء المكاتب: يتجاهل المسافات الزائدة والتشكيل والتطويل وحالة الأحرف. */
  function officeKey(name) {
    return clean(name).replace(/[ً-ْـ]/g, "").toLowerCase();
  }

  /** مفتاح أوسع لاكتشاف الأسماء المتشابهة (أ/إ/آ/ا، ة/ه، ى/ي). لا يُدمج به إلا بموافقة المستخدم. */
  function looseKey(name) {
    return officeKey(name).replace(/[أإآٱ]/g, "ا").replace(/ة/g, "ه").replace(/ى/g, "ي").replace(/ؤ/g, "و").replace(/ئ/g, "ي").replace(/\s/g, "");
  }

  function headerKey(h) {
    return looseKey(normDigits(h)).replace(/[:：.\-_/()\[\]]/g, "");
  }

  // ===================================================================================
  // المبالغ
  // ===================================================================================

  /**
   * يقرأ مبلغًا من خلية: رقم Excel أو نص مثل «1,250.50» أو «(300)» أو «٥٠٠» أو «500-».
   * يرجع {empty:true} أو {ok:true, neg, int, frac, hadText} أو {ok:false, reason}.
   */
  function parseAmount(v) {
    if (v === null || v === undefined) return { empty: true };
    if (typeof v === "object" && v.n !== undefined) v = v.n;
    else if (typeof v === "object" && v.s !== undefined) v = v.s;
    else if (typeof v === "object" && v.date) return { ok: false, reason: "تاريخ في خانة مبلغ" };
    if (typeof v === "number") {
      if (!isFinite(v)) return { ok: false, reason: "رقم غير صالح" };
      if (Math.abs(v) >= 1e15) return { ok: false, reason: "رقم كبير جدًا" };
      if (v === 0) return { ok: true, zero: true, neg: false, int: "0", frac: "" };
      var neg0 = v < 0;
      var s0 = Math.abs(v).toFixed(10).replace(/0+$/, "").replace(/\.$/, "");
      var p0 = s0.split(".");
      return { ok: true, neg: neg0, int: p0[0].replace(/^0+(?=\d)/, ""), frac: p0[1] || "", zero: /^0*$/.test(p0[0] + (p0[1] || "")) };
    }
    var s = clean(normDigits(v));
    if (s === "" || s === "-" || s === "—") return { empty: true };
    var neg = false;
    if (/^\(.*\)$/.test(s)) { neg = true; s = s.slice(1, -1).trim(); }
    if (/^-/.test(s)) { neg = !neg; s = s.slice(1).trim(); } else if (/-$/.test(s)) { neg = !neg; s = s.slice(0, -1).trim(); }
    var hadText = /[^\d.,\s]/.test(s);
    var nums = s.replace(/[^\d.,]/g, " ").trim().split(/\s+/).filter(function (x) { return /\d/.test(x); });
    if (nums.length === 0) return { ok: false, reason: "لا يوجد رقم" };
    if (nums.length > 1) return { ok: false, reason: "أكثر من رقم في الخلية" };
    var t = nums[0];
    var lastDot = t.lastIndexOf("."), lastComma = t.lastIndexOf(",");
    var intPart, fracPart;
    if (lastDot >= 0 && lastComma >= 0) {
      var dec = lastDot > lastComma ? "." : ",";
      var other = dec === "." ? "," : ".";
      var i = t.lastIndexOf(dec);
      intPart = t.slice(0, i).split(other).join("");
      fracPart = t.slice(i + 1);
      if (intPart.indexOf(dec) >= 0 || /[.,]/.test(fracPart)) return { ok: false, reason: "صيغة رقم غير مفهومة" };
    } else if (lastComma >= 0) {
      var parts = t.split(",");
      if (parts.length > 2 || (parts.length === 2 && parts[1].length === 3 && parts[0].length >= 1)) {
        // فاصلة آلاف: 1,250 أو 1,250,000
        if (!parts.slice(1).every(function (x) { return x.length === 3; })) return { ok: false, reason: "صيغة رقم غير مفهومة" };
        intPart = parts.join("");
        fracPart = "";
      } else {
        intPart = parts[0];
        fracPart = parts[1] || "";
      }
    } else if (lastDot >= 0) {
      var dp = t.split(".");
      if (dp.length > 2) {
        if (!dp.slice(1).every(function (x) { return x.length === 3; })) return { ok: false, reason: "صيغة رقم غير مفهومة" };
        intPart = dp.join("");
        fracPart = "";
      } else {
        intPart = dp[0];
        fracPart = dp[1] || "";
      }
    } else {
      intPart = t;
      fracPart = "";
    }
    if (!/^\d*$/.test(intPart) || !/^\d*$/.test(fracPart) || (intPart + fracPart) === "") return { ok: false, reason: "صيغة رقم غير مفهومة" };
    fracPart = fracPart.replace(/0+$/, "");
    intPart = intPart.replace(/^0+(?=\d)/, "") || "0";
    return { ok: true, neg: neg, int: intPart, frac: fracPart, hadText: hadText, zero: /^0*$/.test(intPart + fracPart) };
  }

  /** مبلغ بالوحدة الصغرى (BigInt موجب). يرفض المبلغ إن كانت منازله العشرية أكثر من المسموح للعملة. */
  function toMinor(p, dec) {
    if (p.frac.length > dec) return null;
    var f = p.frac;
    while (f.length < dec) f += "0";
    return BigInt(p.int + f);
  }

  function fmtMinor(m, dec) {
    var neg = m < 0n;
    var s = (neg ? -m : m).toString();
    while (s.length <= dec) s = "0" + s;
    var ip = dec > 0 ? s.slice(0, s.length - dec) : s;
    var fp = dec > 0 ? s.slice(s.length - dec) : "";
    ip = ip.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    return (neg ? "-" : "") + ip + (fp ? "." + fp : "");
  }

  // ===================================================================================
  // التواريخ
  // ===================================================================================

  function validDate(y, m, d, H, M, S) {
    if (!(y >= 1980 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= 31 && H >= 0 && H < 24 && M >= 0 && M < 60 && S >= 0 && S < 60)) return null;
    var dt = new Date(y, m - 1, d, H, M, S);
    if (dt.getFullYear() !== y || dt.getMonth() !== m - 1 || dt.getDate() !== d) return null;
    return { y: y, m: m, d: d, H: H, M: M, S: S, hasTime: H !== 0 || M !== 0 || S !== 0 };
  }

  /** order: dmy | mdy | ymd. يرجع {y,m,d,H,M,S,hasTime} أو null. */
  function parseDate(v, order, serialToDate) {
    if (v === null || v === undefined) return null;
    if (typeof v === "object" && v.date) {
      var x = v.date;
      return validDate(x.y, x.m, x.d, x.H || 0, x.M || 0, Math.floor(x.S || 0));
    }
    if (typeof v === "object" && v.n !== undefined) v = v.n;
    else if (typeof v === "object" && v.s !== undefined) v = v.s;
    if (typeof v === "number") {
      if (v > 20000 && v < 80000 && serialToDate) {
        var d0 = serialToDate(v);
        return d0 ? validDate(d0.y, d0.m, d0.d, d0.H || 0, d0.M || 0, Math.floor(d0.S || 0)) : null;
      }
      return null;
    }
    var s = clean(normDigits(v)).replace(/[،,]/g, " ").replace(/\s+/g, " ");
    var tm = { H: 0, M: 0, S: 0 };
    var tmatch = s.match(/(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm|ص|م|a\.m\.|p\.m\.)?/i);
    if (tmatch) {
      tm.H = +tmatch[1];
      tm.M = +tmatch[2];
      tm.S = tmatch[3] ? +tmatch[3] : 0;
      var ap = (tmatch[4] || "").toLowerCase();
      if ((ap === "pm" || ap === "م" || ap === "p.m.") && tm.H < 12) tm.H += 12;
      if ((ap === "am" || ap === "ص" || ap === "a.m.") && tm.H === 12) tm.H = 0;
      s = s.replace(tmatch[0], " ").trim();
    }
    var m = s.match(/(\d{1,4})\s*[\/\-.]\s*(\d{1,2})\s*[\/\-.]\s*(\d{1,4})/);
    if (!m) {
      if (/^\d{8}$/.test(s)) m = [s, s.slice(0, 4), s.slice(4, 6), s.slice(6, 8)];
      else return null;
    }
    var a = +m[1], b = +m[2], c = +m[3], y, mo, d;
    if (m[1].length === 4) { y = a; mo = b; d = c; }
    else if (order === "mdy") { mo = a; d = b; y = c; }
    else if (order === "ymd") { y = a; mo = b; d = c; }
    else { d = a; mo = b; y = c; }
    if (y < 100) y += 2000;
    return validDate(y, mo, d, tm.H, tm.M, tm.S);
  }

  /** يقترح ترتيب اليوم والشهر من نصوص التواريخ نفسها. */
  function guessDateOrder(values) {
    var dmy = 0, mdy = 0;
    values.forEach(function (v) {
      if (typeof v !== "string" && !(v && v.s !== undefined)) return;
      var s = normDigits(typeof v === "string" ? v : v.s);
      var m = s.match(/^\s*(\d{1,2})\s*[\/\-.]\s*(\d{1,2})\s*[\/\-.]\s*(\d{2,4})/);
      if (!m) return;
      if (+m[1] > 12) dmy++;
      if (+m[2] > 12) mdy++;
    });
    return mdy > dmy ? "mdy" : "dmy";
  }

  function epoch(dt, defaultHour) {
    var H = dt.hasTime ? dt.H : defaultHour, M = dt.hasTime ? dt.M : 0, S = dt.hasTime ? dt.S : 0;
    return new Date(dt.y, dt.m - 1, dt.d, H, M, S).getTime();
  }

  function fmtDate(t) {
    var d = new Date(t);
    function p(n) { return (n < 10 ? "0" : "") + n; }
    return d.getFullYear() + "/" + p(d.getMonth() + 1) + "/" + p(d.getDate());
  }

  // ===================================================================================
  // قراءة أوراق Excel إلى صفوف
  // ===================================================================================

  /** يحوّل ورقة SheetJS إلى مصفوفة صفوف من خلايا: {s} نص، {n} رقم، {date} تاريخ، أو null. */
  function sheetRows(ws, XLSX) {
    if (!ws || !ws["!ref"]) return [];
    var rg = XLSX.utils.decode_range(ws["!ref"]);
    var out = [];
    var maxRows = Math.min(rg.e.r, rg.s.r + 200000);
    for (var r = rg.s.r; r <= maxRows; r++) {
      var row = [];
      for (var c = 0; c <= rg.e.c; c++) {
        var cell = ws[XLSX.utils.encode_cell({ r: r, c: c })];
        row.push(cellValue(cell, XLSX));
      }
      out.push(row);
    }
    return out;
  }

  function cellValue(cell, XLSX) {
    if (!cell || cell.v === undefined || cell.v === null) return null;
    switch (cell.t) {
      case "s": case "str": return clean(cell.v) === "" ? null : { s: String(cell.v) };
      case "n":
        if (cell.z && XLSX.SSF.is_date(cell.z)) {
          var p = XLSX.SSF.parse_date_code(cell.v);
          return p ? { date: { y: p.y, m: p.m, d: p.d, H: p.H, M: p.M, S: p.S } } : { n: cell.v };
        }
        return { n: cell.v };
      case "d":
        var dt = cell.v instanceof Date ? cell.v : new Date(cell.v);
        return { date: { y: dt.getFullYear(), m: dt.getMonth() + 1, d: dt.getDate(), H: dt.getHours(), M: dt.getMinutes(), S: dt.getSeconds() } };
      case "b": return { s: cell.v ? "TRUE" : "FALSE" };
      default: return null;
    }
  }

  function text(cell) {
    if (!cell) return "";
    if (cell.s !== undefined) return clean(cell.s);
    if (cell.n !== undefined) {
      var n = cell.n;
      return Number.isInteger(n) ? String(n) : String(+n.toFixed(10));
    }
    if (cell.date) {
      var x = cell.date;
      return x.y + "/" + (x.m < 10 ? "0" : "") + x.m + "/" + (x.d < 10 ? "0" : "") + x.d;
    }
    return "";
  }

  function isBlankRow(row) { return !row || row.every(function (c) { return c === null || text(c) === ""; }); }

  // ===================================================================================
  // التعرف على الأعمدة والعملات والاتجاه
  // ===================================================================================

  var SYN = {
    office: ["المكتب", "اسم المكتب", "مكتب", "الحساب", "اسم الحساب", "حساب", "العميل", "اسم العميل", "الوكيل", "الجهه", "الطرف", "account", "office", "agent", "customer", "client", "accountname", "officename"],
    date: ["التاريخ", "تاريخ", "تاريخ الحركه", "تاريخ العمليه", "تاريخ السند", "date", "datetime", "transactiondate"],
    amount: ["المبلغ", "مبلغ", "القيمه", "قيمه", "amount", "value", "sum"],
    debit: ["مدين", "لنا", "عليه", "مدين لنا", "debit", "dr", "debitamount"],
    credit: ["دائن", "علينا", "له", "دائن علينا", "credit", "cr", "creditamount"],
    type: ["النوع", "نوع", "نوع الحركه", "نوع العمليه", "الاتجاه", "type", "direction", "drcr"],
    currency: ["العمله", "عمله", "نوع العمله", "currency", "cur", "ccy", "curr"],
    person: ["المستفيد", "المستلم", "اسم المستفيد", "اسم المستلم", "الشخص", "المرسل اليه", "beneficiary", "receiver", "recipient", "person", "payee"],
    description: ["البيان", "بيان", "الشرح", "التفاصيل", "الوصف", "description", "details", "memo", "narration", "particulars"],
    notes: ["ملاحظات", "ملاحظه", "notes", "note", "remarks", "comment"],
    ref: ["المرجع", "مرجع", "رقم الحواله", "رقم السند", "رقم العمليه", "رقم القيد", "الرقم", "ref", "reference", "no", "number", "voucher"],
    fee: ["الاجره", "اجره", "العموله", "عموله", "fee", "fees", "commission"],
    balance: ["الرصيد", "رصيد", "balance", "runningbalance"],
    phone: ["الهاتف", "هاتف", "الجوال", "جوال", "موبايل", "رقم الهاتف", "phone", "mobile", "tel"]
  };
  var SYN_KEYS = {};
  Object.keys(SYN).forEach(function (k) { SYN_KEYS[k] = SYN[k].map(headerKey); });

  /** يقترح صف العناوين: أول صف (من أول 25) فيه أكثر الأسماء المعروفة. */
  function guessHeaderRow(rows) {
    var best = 0, bestScore = -1;
    for (var r = 0; r < Math.min(rows.length, 25); r++) {
      var row = rows[r] || [];
      var score = 0, texts = 0;
      row.forEach(function (c) {
        var t = text(c);
        if (!t || (c && c.n !== undefined)) return;
        texts++;
        var k = headerKey(t);
        Object.keys(SYN_KEYS).forEach(function (f) { if (SYN_KEYS[f].indexOf(k) >= 0) score += 3; });
      });
      score += Math.min(texts, 6) * 0.2;
      if (texts >= 2 && score > bestScore) { bestScore = score; best = r; }
    }
    return best;
  }

  /** يقترح عمودًا لكل حقل من عناوين الأعمدة. */
  function guessColumns(headers) {
    var keys = headers.map(function (h) { return headerKey(text(h)); });
    var used = {};
    var res = {};
    var order = ["debit", "credit", "date", "currency", "office", "amount", "type", "person", "description", "notes", "ref", "fee", "balance", "phone"];
    order.forEach(function (f) {
      var idx = -1;
      for (var i = 0; i < keys.length && idx < 0; i++) if (!used[i] && keys[i] && SYN_KEYS[f].indexOf(keys[i]) >= 0) idx = i;
      for (var j = 0; j < keys.length && idx < 0; j++) {
        if (used[j] || !keys[j]) continue;
        if (SYN_KEYS[f].some(function (s) { return s.length >= 3 && keys[j].indexOf(s) >= 0; })) idx = j;
      }
      if (idx >= 0) { res[f] = idx; used[idx] = true; }
    });
    return res;
  }

  var KNOWN = {
    USD: { name: "دولار أمريكي", dec: 2, words: ["usd", "$", "دولار", "دولار امريكي", "الدولار", "dollar", "us dollar", "usd$", "دولار أمريكي"] },
    EUR: { name: "يورو", dec: 2, words: ["eur", "€", "يورو", "اليورو", "euro"] },
    TRY: { name: "ليرة تركية", dec: 2, words: ["try", "tl", "₺", "ليره تركيه", "ليرة تركية", "تركي", "تركيه", "lira", "turkish lira"] },
    SYP: { name: "ليرة سورية", dec: 0, words: ["syp", "ل.س", "ل س", "ليره سوريه", "ليرة سورية", "سوري", "سوريه", "ليره", "ليرة", "syrian pound"] },
    USDT: { name: "تيثر (USDT)", dec: 2, words: ["usdt", "تيثر", "يوز", "tether"] },
    SAR: { name: "ريال سعودي", dec: 2, words: ["sar", "ريال سعودي", "ريال", "سعودي", "riyal"] },
    AED: { name: "درهم إماراتي", dec: 2, words: ["aed", "درهم", "درهم اماراتي", "اماراتي", "dirham"] },
    GBP: { name: "جنيه إسترليني", dec: 2, words: ["gbp", "£", "استرليني", "جنيه استرليني", "pound"] },
    EGP: { name: "جنيه مصري", dec: 2, words: ["egp", "جنيه مصري", "مصري", "جنيه"] },
    IQD: { name: "دينار عراقي", dec: 0, words: ["iqd", "دينار عراقي", "عراقي"] },
    JOD: { name: "دينار أردني", dec: 3, words: ["jod", "دينار اردني", "اردني"] },
    KWD: { name: "دينار كويتي", dec: 3, words: ["kwd", "دينار كويتي", "كويتي"] },
    LBP: { name: "ليرة لبنانية", dec: 0, words: ["lbp", "ليره لبنانيه", "لبناني", "لبنانيه"] },
    QAR: { name: "ريال قطري", dec: 2, words: ["qar", "ريال قطري", "قطري"] },
    RON: { name: "ليو روماني", dec: 2, words: ["ron", "ليو", "روماني", "lei", "leu"] },
    CHF: { name: "فرنك سويسري", dec: 2, words: ["chf", "فرنك", "فرنك سويسري"] },
    SEK: { name: "كرونة سويدية", dec: 2, words: ["sek", "كرون", "كرونه سويديه"] },
    CAD: { name: "دولار كندي", dec: 2, words: ["cad", "دولار كندي", "كندي"] },
    AUD: { name: "دولار أسترالي", dec: 2, words: ["aud", "دولار استرالي", "استرالي"] },
    RUB: { name: "روبل روسي", dec: 2, words: ["rub", "روبل", "₽"] }
  };
  var DEFAULT_CODES = ["USD", "EUR", "TRY", "SYP"];

  function guessCurrency(t) {
    var k = looseKey(normDigits(t)).replace(/\s/g, "");
    if (!k) return null;
    var up = clean(t).toUpperCase();
    if (KNOWN[up]) return up;
    var codes = Object.keys(KNOWN);
    for (var i = 0; i < codes.length; i++) {
      var ws = KNOWN[codes[i]].words;
      for (var j = 0; j < ws.length; j++) if (looseKey(ws[j]).replace(/\s/g, "") === k) return codes[i];
    }
    return null;
  }

  var DIR_D = ["لنا", "مدين", "عليه", "debit", "dr", "d", "+", "مدين لنا"].map(looseKey);
  var DIR_C = ["علينا", "دائن", "له", "credit", "cr", "c", "-", "دائن علينا"].map(looseKey);

  function guessDirection(t) {
    var k = looseKey(t);
    if (!k) return null;
    if (DIR_D.indexOf(k) >= 0) return "D";
    if (DIR_C.indexOf(k) >= 0) return "C";
    if (/(^|\s)مدين/.test(clean(t))) return "D";
    if (/(^|\s)دائن/.test(clean(t))) return "C";
    return null;
  }

  var OPENING_RE = /^(رصيد\s*(سابق|افتتاحي|مدور|مرحل|اول\s*المده|أول\s*المدة|قبل)|الرصيد\s*(السابق|الافتتاحي|المدور|المرحل)|opening\s*balance|balance\s*(b\/?f|brought\s*forward)|b\/f)/i;
  var TOTAL_RE = /^(المجموع|مجموع|الإجمالي|الاجمالي|إجمالي|اجمالي|المجاميع|total|totals|grand\s*total|sum)(?=$|[\s:：\-–—(])/i;

  function rowLabel(row) {
    for (var i = 0; i < row.length; i++) {
      var c = row[i];
      if (c && c.s !== undefined && clean(c.s)) return clean(c.s);
    }
    return "";
  }

  // ===================================================================================
  // البناء: من الصفوف إلى مكاتب وأرصدة وحركات
  // ===================================================================================

  function newModel() {
    return { offices: [], officeIndex: {}, openings: [], txs: [], errors: [], warnings: [], skipped: [], checks: [], used: {}, rowsRead: 0, both: 0 };
  }

  function displayName(cfg, name) {
    var r = cfg.rename && cfg.rename[officeKey(name)];
    return r && clean(r) ? clean(r) : clean(name);
  }

  function keyOf(cfg, name) {
    var n = displayName(cfg, name);
    return cfg.mergeSimilar ? looseKey(n) : officeKey(n);
  }

  function officeFor(model, name, phone, cfg) {
    var orig = clean(name);
    name = displayName(cfg, name);
    var key = keyOf(cfg, orig);
    var i = model.officeIndex[key];
    if (i === undefined) {
      i = model.offices.length;
      model.officeIndex[key] = i;
      model.offices.push({ name: clean(name), key: key, phone: clean(phone || ""), variants: {}, tx: 0, openings: 0 });
    }
    var o = model.offices[i];
    o.variants[orig] = true;
    if (!o.phone && phone) o.phone = clean(phone);
    return i;
  }

  function err(model, src, msg) { model.errors.push({ sheet: src.sheet, row: src.row, msg: msg }); }

  function skip(model, src, reason) { model.skipped.push({ sheet: src.sheet, row: src.row, reason: reason }); }

  function curDef(cfg, code) {
    var d = cfg.currencies && cfg.currencies[code];
    if (d) return d;
    var k = KNOWN[code];
    return k ? { name: k.name, dec: k.dec } : null;
  }

  /** يحدد عملة الصف: من عمود (عبر جدول المطابقة) أو عملة ثابتة. */
  function rowCurrency(cfg, sec, row, model, src) {
    if (sec.currencyFrom === "fixed") {
      if (!sec.fixedCurrency) { err(model, src, "لم تُحدَّد العملة الثابتة لهذا القسم"); return null; }
      return sec.fixedCurrency;
    }
    var t = text(row[sec.cols.currency]);
    if (!t) { err(model, src, "خانة العملة فارغة"); return null; }
    var code = cfg.currencyMap[officeKey(t)];
    if (code === "__skip") return "__skip";
    if (!code) { err(model, src, "العملة «" + t + "» غير مطابَقة مع رمز عملة"); return null; }
    return code;
  }

  function amountMinor(cfg, model, src, p, code, what) {
    var def = curDef(cfg, code);
    if (!def) { err(model, src, "العملة " + code + " غير معرّفة"); return null; }
    var m = toMinor(p, def.dec);
    if (m === null) {
      err(model, src, what + " فيه منازل عشرية أكثر من المسموح لعملة " + code + " (" + def.dec + "). غيّر عدد المنازل للعملة أو صحّح الخلية.");
      return null;
    }
    if (m > 9000000000000000n) { err(model, src, what + " كبير جدًا"); return null; }
    return m;
  }

  function flip(dir, cfg) { return cfg.reverse ? (dir === "D" ? "C" : "D") : dir; }

  /** قسم الأرصدة الافتتاحية: صف لكل مكتب (وعملة). */
  function buildOpenings(model, cfg, sec, sheets) {
    sheets.forEach(function (sh) {
      var rows = sh.rows;
      for (var r = sec.headerRow + 1; r < rows.length; r++) {
        var row = rows[r];
        var src = { sheet: sh.name, row: r + 1 };
        if (isBlankRow(row)) continue;
        model.rowsRead++;
        var name = text(row[sec.cols.office]);
        var label = rowLabel(row);
        if (TOTAL_RE.test(name) || (!name && TOTAL_RE.test(label))) { skip(model, src, "صف مجموع"); continue; }
        var values = [];
        if (sec.layout === "wide") {
          (sec.wide || []).forEach(function (w) {
            if (w.code && w.code !== "__skip") values.push({ code: w.code, v: row[w.col], signed: true });
          });
        } else {
          var code = rowCurrency(cfg, sec, row, model, src);
          if (code === null) continue;
          if (code === "__skip") { skip(model, src, "عملة مستبعدة"); continue; }
          if (sec.layout === "split") {
            values.push({ code: code, v: row[sec.cols.debit], dir: "D" });
            values.push({ code: code, v: row[sec.cols.credit], dir: "C" });
          } else values.push({ code: code, v: row[sec.cols.amount], signed: true });
        }
        var any = false, bad = false, net = {};
        values.forEach(function (x) {
          var p = parseAmount(x.v);
          if (p.empty) return;
          if (!p.ok) { err(model, src, "مبلغ غير مفهوم: «" + text(x.v) + "» — " + p.reason); bad = true; return; }
          if (p.zero) return;
          if (!x.signed && p.neg) { err(model, src, "قيمة سالبة في عمود " + (x.dir === "D" ? "لنا/مدين" : "علينا/دائن") + ": «" + text(x.v) + "»"); bad = true; return; }
          var m = amountMinor(cfg, model, src, p, x.code, "المبلغ");
          if (m === null) { bad = true; return; }
          var pos = sec.positiveIs || "D";
          var dir = x.signed ? (p.neg ? (pos === "D" ? "C" : "D") : pos) : x.dir;
          dir = flip(dir, cfg);
          net[x.code] = (net[x.code] || 0n) + (dir === "D" ? m : -m);
          any = true;
        });
        if (bad) continue;
        if (!name) {
          if (any) err(model, src, "يوجد رصيد بلا اسم مكتب");
          else skip(model, src, "صف بلا مكتب ولا رصيد");
          continue;
        }
        var oi = officeFor(model, name, sec.cols.phone !== undefined ? text(row[sec.cols.phone]) : "", cfg);
        if (!any) { skip(model, src, "مكتب بلا رصيد (أُضيف المكتب فقط)"); model.offices[oi].emptyOk = true; continue; }
        Object.keys(net).forEach(function (code) {
          if (net[code] === 0n) return;
          model.openings.push({ office: oi, code: code, minor: net[code], when: sec.date || null, src: src });
          model.offices[oi].openings++;
          model.used[code] = true;
        });
      }
    });
  }

  /** قسم الحركات: صف لكل حركة، من ورقة واحدة أو من أوراق متعددة (كل ورقة مكتب). */
  function buildTxs(model, cfg, sec, sheets, serialToDate) {
    sheets.forEach(function (sh) {
      var rows = sh.rows;
      var prevDate = null, prevOffice = "";
      var firstDate = null;
      var pendingOpen = [];
      for (var r = sec.headerRow + 1; r < rows.length; r++) {
        var row = rows[r];
        var src = { sheet: sh.name, row: r + 1 };
        if (isBlankRow(row)) continue;
        model.rowsRead++;
        var label = rowLabel(row);
        var officeName = sec.officeFrom === "sheet" ? sh.name : text(row[sec.cols.office]);
        var dateRaw = text(row[sec.cols.date]);
        // صف المجموع: عنوانه «المجموع/الإجمالي» ولا تاريخ فيه (الحركة الحقيقية لها تاريخ دائمًا)
        if (!dateRaw && (TOTAL_RE.test(label) || (sec.officeFrom !== "sheet" && TOTAL_RE.test(officeName)))) { skip(model, src, "صف مجموع"); continue; }
        var isOpening = OPENING_RE.test(label) || (sec.cols.description !== undefined && OPENING_RE.test(text(row[sec.cols.description])));
        if (!officeName && sec.fillOffice && prevOffice) officeName = prevOffice;
        // المبالغ
        var parts = [];
        var bad = false;
        var code = null;
        var needCur = function () {
          if (code !== null) return code;
          code = rowCurrency(cfg, sec, row, model, src);
          return code;
        };
        if (sec.layout === "split") {
          [["D", sec.cols.debit], ["C", sec.cols.credit]].forEach(function (x) {
            if (x[1] === undefined) return;
            var p = parseAmount(row[x[1]]);
            if (p.empty || (p.ok && p.zero)) return;
            if (!p.ok) { err(model, src, "مبلغ غير مفهوم: «" + text(row[x[1]]) + "» — " + p.reason); bad = true; return; }
            if (p.neg) { err(model, src, "قيمة سالبة في عمود " + (x[0] === "D" ? "لنا/مدين" : "علينا/دائن") + ": «" + text(row[x[1]]) + "»"); bad = true; return; }
            parts.push({ dir: x[0], p: p });
          });
        } else {
          var pa = parseAmount(row[sec.cols.amount]);
          if (!pa.empty && !(pa.ok && pa.zero)) {
            if (!pa.ok) { err(model, src, "مبلغ غير مفهوم: «" + text(row[sec.cols.amount]) + "» — " + pa.reason); bad = true; }
            else if (sec.layout === "typed") {
              var tv = text(row[sec.cols.type]);
              var dm = cfg.typeMap[officeKey(tv)];
              if (dm === "X") { skip(model, src, "نوع مستبعد: " + tv); continue; }
              if (dm !== "D" && dm !== "C") { err(model, src, tv ? "نوع الحركة «" + tv + "» غير مطابَق (لنا أو علينا)" : "خانة نوع الحركة فارغة"); bad = true; }
              else if (pa.neg) { err(model, src, "مبلغ سالب مع عمود نوع: «" + text(row[sec.cols.amount]) + "»"); bad = true; }
              else parts.push({ dir: dm, p: pa });
            } else {
              var pos = sec.positiveIs || "D";
              parts.push({ dir: pa.neg ? (pos === "D" ? "C" : "D") : pos, p: pa });
            }
          }
        }
        // عمود الرصيد (للتحقق فقط)
        var balCell = sec.cols.balance !== undefined ? row[sec.cols.balance] : null;
        if (bad) continue;
        if (parts.length === 0) {
          if (isOpening && balCell) {
            // «رصيد سابق» مكتوب في عمود الرصيد فقط
            var pb = parseAmount(balCell);
            if (pb.ok && !pb.zero) parts.push({ dir: pb.neg ? (sec.balancePositiveIs === "D" ? "C" : "D") : (sec.balancePositiveIs || "D"), p: pb, fromBalance: true });
          }
          if (parts.length === 0) {
            if (balCell && officeName) recordBalance(model, cfg, sec, officeName, row, balCell, src, needCur);
            skip(model, src, isOpening ? "رصيد سابق صفر" : "صف بلا مبلغ");
            continue;
          }
        }
        if (!officeName) { err(model, src, "يوجد مبلغ بلا اسم مكتب"); continue; }
        prevOffice = officeName;
        var cur = needCur();
        if (cur === null) continue;
        if (cur === "__skip") { skip(model, src, "عملة مستبعدة"); continue; }
        // التاريخ
        var dt = parseDate(row[sec.cols.date], sec.dateOrder, serialToDate);
        if (!dt) {
          var raw = dateRaw;
          var hasInfo = (sec.cols.person !== undefined && text(row[sec.cols.person])) || (sec.descCols || []).some(function (c) { return text(row[c]); });
          if (!raw && sec.fillDate && prevDate && hasInfo) dt = prevDate;
          else if (!raw && isOpening) dt = null;
          else { err(model, src, raw ? "تاريخ غير مفهوم: «" + raw + "»" : "خانة التاريخ فارغة"); continue; }
        }
        if (dt) {
          prevDate = dt;
          if (!firstDate) firstDate = dt;
        }
        var when = dt ? epoch(dt, 12) : null;
        if (when !== null && when > cfg.now + 10 * 60000) { err(model, src, "تاريخ في المستقبل: " + fmtDate(when)); continue; }
        var oi = officeFor(model, officeName, sec.cols.phone !== undefined ? text(row[sec.cols.phone]) : "", cfg);
        // الأجرة
        var feeMinor = 0n;
        if (sec.cols.fee !== undefined && !isOpening) {
          var pf = parseAmount(row[sec.cols.fee]);
          if (!pf.empty && !(pf.ok && pf.zero)) {
            if (!pf.ok || pf.neg) { err(model, src, "أجرة غير مفهومة: «" + text(row[sec.cols.fee]) + "»"); continue; }
            feeMinor = amountMinor(cfg, model, src, pf, cur, "مبلغ الأجرة");
            if (feeMinor === null) continue;
          }
        }
        var person = sec.cols.person !== undefined ? text(row[sec.cols.person]) : "";
        var desc = (sec.descCols || []).map(function (c) { return text(row[c]); }).filter(Boolean).join(" — ");
        var notes = [];
        if (sec.cols.notes !== undefined && text(row[sec.cols.notes])) notes.push(text(row[sec.cols.notes]));
        if (sec.cols.ref !== undefined && text(row[sec.cols.ref])) notes.push("مرجع: " + text(row[sec.cols.ref]));
        notes.push("منقولة من: " + sh.name + " — صف " + (r + 1));
        if (parts.length === 2) model.both++;
        var ok = true;
        var made = [];
        parts.forEach(function (x, k) {
          var m = amountMinor(cfg, model, src, x.p, cur, "المبلغ");
          if (m === null) { ok = false; return; }
          made.push({ dir: flip(x.dir, cfg), minor: m });
        });
        if (!ok) continue;
        if (isOpening) {
          made.forEach(function (x) {
            pendingOpen.push({ office: oi, code: cur, minor: x.dir === "D" ? x.minor : -x.minor, when: when, src: src });
          });
        } else {
          made.forEach(function (x, k) {
            model.txs.push({
              office: oi, when: when, dir: x.dir, minor: x.minor, code: cur, person: person, desc: desc,
              notes: notes.join(" • "), fee: k === 0 ? feeMinor : 0n, feeDir: flip(sec.feeDir || "D", cfg), src: src, seq: model.txs.length
            });
            model.offices[oi].tx++;
          });
        }
        model.used[cur] = true;
        if (balCell) recordBalance(model, cfg, sec, officeName, row, balCell, src, function () { return cur; });
      }
      // أرصدة «سابق» داخل الورقة: تاريخها تاريخ صفها، أو قبل أول حركة في الورقة بدقيقة
      pendingOpen.forEach(function (o) {
        var when = o.when !== null ? o.when : (firstDate ? epoch(firstDate, 12) - 60000 : cfg.openingDate);
        model.openings.push({ office: o.office, code: o.code, minor: o.minor, when: when, src: o.src, inline: true });
        model.offices[o.office].openings++;
      });
    });
  }

  function recordBalance(model, cfg, sec, officeName, row, balCell, src, curFn) {
    var p = parseAmount(balCell);
    if (!p.ok || p.empty) return;
    var cur = curFn();
    if (!cur || cur === "__skip") return;
    var def = curDef(cfg, cur);
    if (!def) return;
    var m = toMinor(p, def.dec);
    if (m === null) {
      var f = p.frac.slice(0, def.dec);
      m = toMinor({ int: p.int, frac: f }, def.dec);
    }
    var pos = sec.balancePositiveIs || "D";
    var signed = (p.neg ? (pos === "D" ? -m : m) : (pos === "D" ? m : -m));
    if (cfg.reverse) signed = -signed;
    var key = keyOf(cfg, officeName) + "|" + cur;
    model.lastBalance = model.lastBalance || {};
    model.lastBalance[key] = { minor: signed, src: src };
  }

  /** يجمع كل الأقسام ثم يدقق: مكاتب متشابهة، أرصدة متوقعة، أعداد. */
  function build(cfg, input, serialToDate) {
    var model = newModel();
    if (cfg.openings && cfg.openings.enabled) buildOpenings(model, cfg, cfg.openings, input.openingSheets || []);
    if (cfg.txs && cfg.txs.enabled) buildTxs(model, cfg, cfg.txs, input.txSheets || [], serialToDate);
    // تاريخ الأرصدة الافتتاحية: التاريخ المختار، وإلا قبل أول حركة بيوم
    var minTx = null;
    model.txs.forEach(function (t) { if (minTx === null || t.when < minTx) minTx = t.when; });
    model.openings.forEach(function (o) {
      if (o.when === undefined || o.when === null) {
        o.when = cfg.openingDate ? cfg.openingDate : (minTx !== null ? minTx - 86400000 : cfg.now);
      }
    });
    // أسماء متشابهة لم تُدمج
    if (!cfg.mergeSimilar) {
      var groups = {};
      model.offices.forEach(function (o) {
        var k = looseKey(o.name);
        (groups[k] = groups[k] || []).push(o.name);
      });
      Object.keys(groups).forEach(function (k) {
        if (groups[k].length > 1) model.warnings.push("أسماء مكاتب متشابهة قد تكون المكتب نفسه: " + groups[k].join(" / ") + " — فعّل «دمج الأسماء المتشابهة» إن كانت مكتبًا واحدًا.");
      });
    }
    model.offices.forEach(function (o) {
      var v = Object.keys(o.variants);
      if (v.length > 1) model.warnings.push("دُمجت كتابات مختلفة لاسم المكتب «" + o.name + "»: " + v.join(" / "));
    });
    if (model.both > 0) model.warnings.push(model.both + " صف فيه مبلغ «لنا» ومبلغ «علينا» معًا: سُجّل كل منها حركة مستقلة.");
    return model;
  }

  // ===================================================================================
  // كشوفات الحساب: ملف (أو أكثر) لكل مكتب وعملة، كما تصدّرها برامج الحوالات
  // «اسم الحساب» و«العملة» أعلى الكشف، ثم «Pre Balance» وصفوف الحركات بعمود رصيد، ثم «اجمالي الرصيد».
  // الدقة: كل صف يُتحقق منه بعمود الرصيد، والنهاية بسطر الإجمالي؛ الكشف الذي لا يطابق يُرفض كاملًا.
  // ===================================================================================

  var PRE_RE = /^(pre\s*-?\s*balance|previous\s*balance|opening\s*balance|b\/f|c\/f|(carried|brought)\s*forward|رصيد\s*منقول|الرصيد\s*المنقول|منقول|رصيد\s*سابق|الرصيد\s*السابق|رصيد\s*ما\s*قبل(\s*الفتر[هة])?|رصيد\s*(اول|أول)\s*المد[هة]|رصيد\s*مدور|الرصيد\s*المدور|رصيد\s*مرحل|الرصيد\s*المرحل)$/i;
  var FOOT_RE = /^((ا|إ)جمالي\s*الرصيد|الرصيد\s*(النهائي|الختامي|الحالي)|رصيد\s*نهاي[هة]\s*الفتر[هة]|closing\s*balance|total\s*balance|balance\s*c\/?f)$/i;
  var META_SKIP_RE = /^(تاريخ\s*(البدايه|البداية|النهايه|النهاية)|من\s*تاريخ|(الى|إلى)\s*تاريخ|من|(الى|إلى)|الفتر[هة]|كشف\s*حساب.*|page\s*\d+.*|صفح[هة]\s*\d+.*|:)$/i;

  /** أعمدة صف عناوين الكشف: التاريخ ومدين ودائن والرصيد إلزامية، والبيان اختياري. */
  function stmtHeader(row) {
    if (!row) return null;
    var texts = 0;
    row.forEach(function (c) { if (c && c.s !== undefined) texts++; });
    if (texts < 4) return null;
    var g = guessColumns(row);
    if (g.date === undefined || g.debit === undefined || g.credit === undefined || g.balance === undefined) return null;
    // جدول عام (فيه عمود مكتب أو عملة) ليس كشف حساب واحد
    if (g.office !== undefined || g.currency !== undefined) return null;
    // كل الخلايا المعروفة عناوين نصية لا أرقام
    var ok = [g.date, g.debit, g.credit, g.balance].every(function (i) { return row[i] && row[i].s !== undefined; });
    return ok ? { date: g.date, debit: g.debit, credit: g.credit, balance: g.balance, description: g.description } : null;
  }

  /** «1410100092 الخال» أو «الخال 1410100092» أو «الخال - 1410100092». */
  function splitAccount(t) {
    var s = clean(normDigits(t));
    var m = s.match(/^(\d{3,})\s*[-–:]?\s*(\S.*)$/);
    if (m) return { code: m[1], name: clean(m[2]) };
    m = s.match(/^(.*\S)\s*[-–:]?\s*(\d{3,})$/);
    if (m && /[^\d\s]/.test(m[1])) return { code: m[2], name: clean(m[1]) };
    return { code: "", name: s };
  }

  /** معلومات الحساب والعملة والفترة من الأسطر التي فوق صف العناوين. */
  function stmtMeta(lines) {
    var meta = { account: null, curText: "", from: null, to: null, title: [] };
    var texts = [];
    lines.forEach(function (row) {
      var cells = row.filter(function (c) { return c && text(c); });
      var label = cells.map(text).join(" ");
      var isFrom = /تاريخ\s*(البدايه|البداية)|من\s*تاريخ|^من\b/.test(label), isTo = /تاريخ\s*(النهايه|النهاية)|(الى|إلى)\s*تاريخ/.test(label);
      cells.forEach(function (c) {
        var t = text(c);
        var dt = parseDate(c, "ymd", null);
        if (dt && /\d{1,4}\s*[\/\-.]\s*\d{1,2}\s*[\/\-.]\s*\d{1,4}/.test(normDigits(t))) {
          if (isFrom && !meta.from) meta.from = dt;
          else if (isTo && !meta.to) meta.to = dt;
          return;
        }
        if (META_SKIP_RE.test(clean(t))) return;
        texts.push(t);
      });
    });
    // العملة: نص يُعرف كعملة؛ الحساب: نص فيه رمز رقمي واسم، وإلا أول نص ليس عملة
    var curIdx = -1;
    for (var i = 0; i < texts.length; i++) if (guessCurrency(texts[i])) { curIdx = i; break; }
    var accIdx = -1;
    for (var j = 0; j < texts.length; j++) {
      if (j === curIdx) continue;
      var a = splitAccount(texts[j]);
      if (a.code && a.name) { accIdx = j; break; }
    }
    if (accIdx < 0) for (var k = 0; k < texts.length; k++) if (k !== curIdx) { accIdx = k; break; }
    if (accIdx >= 0) meta.account = splitAccount(texts[accIdx]);
    if (curIdx >= 0) meta.curText = clean(texts[curIdx]);
    else {
      // عملة غير معروفة (ذهب، عملة محلية…): أول نص بعد الحساب يُعرض للمطابقة
      for (var n = accIdx + 1; n < texts.length; n++) if (n !== accIdx) { meta.curText = clean(texts[n]); break; }
    }
    meta.title = texts.filter(function (x, idx) { return idx !== accIdx && idx !== curIdx; });
    return meta;
  }

  function footerOf(row) {
    var cells = row.filter(function (c) { return c && text(c); });
    if (!cells.some(function (c) { return FOOT_RE.test(clean(text(c)).replace(/\s*:\s*$/, "")); })) return null;
    var amount = null, side = null;
    cells.forEach(function (c) {
      var t = clean(text(c));
      if (FOOT_RE.test(t) || t === ":" || /نوع\s*الرصيد/.test(t)) return;
      var p = parseAmount(c);
      if (p.ok && !p.hadText && amount === null) { amount = p; return; }
      var k = looseKey(t);
      if (/دائن|علينا|credit|\bcr\b/i.test(t) || k === looseKey("له")) side = side || "C";
      else if (/مدين|لنا|debit|\bdr\b/i.test(t) || k === looseKey("عليه")) side = side || "D";
      else if (/متوازن|متعادل|صفر|balanced/i.test(t)) side = side || "0";
    });
    return amount ? { p: amount, side: side } : null;
  }

  /**
   * يقرأ ملفًا (كل أوراقه بالترتيب) ويعيد الكشوفات التي فيه. صفحات التقرير التي تكرر صف العناوين تُدمج،
   * ويبدأ كشف جديد بعد سطر الإجمالي أو عند ظهور حساب آخر قبل صف عناوين.
   */
  /** هل في الملف كشوفات حساب (صف عناوين كشف وفوقه اسم حساب)؟ */
  function isStatementFile(blocks) {
    return blocks.some(function (b) { return b.account && b.account.name; });
  }

  function parseStatements(fileName, sheets) {
    var blocks = [];
    var cur = null, pending = [], since = [];
    function close() { cur = null; pending = []; since = []; }
    sheets.forEach(function (sh) {
      (sh.rows || []).forEach(function (row, r) {
        if (isBlankRow(row)) return;
        var src = { sheet: fileName + (sheets.length > 1 ? " / " + sh.name : ""), row: r + 1 };
        var hc = stmtHeader(row);
        if (hc) {
          var fresh = !cur || cur.footer;
          if (!fresh && since.length) {
            // أسطر نصية بين آخر حركة وهذا العنوان: إن كان فيها حساب آخر فهو كشف جديد
            var m0 = stmtMeta(since);
            if (m0.account && m0.account.name && (m0.account.code ? m0.account.code !== cur.account.code : m0.account.name !== cur.account.name)) {
              pending = since;
              fresh = true;
            }
          }
          if (fresh) {
            var meta = stmtMeta(pending);
            cur = { file: fileName, src: src, cols: hc, account: meta.account || { code: "", name: "" }, curText: meta.curText, from: meta.from, to: meta.to,
              pre: null, rows: [], footer: null, skipped: [], headerRow: src.row };
            blocks.push(cur);
          } else cur.cols = hc;
          pending = [];
          since = [];
          return;
        }
        if (!cur || cur.footer) { pending.push(row); return; }
        var f = footerOf(row);
        if (f) { cur.footer = { p: f.p, side: f.side, src: src }; return; }
        var c = cur.cols;
        var desc = c.description !== undefined ? text(row[c.description]) : "";
        var item = { src: src, desc: desc, date: row[c.date], d: parseAmount(row[c.debit]), c: parseAmount(row[c.credit]), b: parseAmount(row[c.balance]) };
        if (PRE_RE.test(desc) || (!desc && OPENING_RE.test(rowLabel(row)))) {
          if (cur.pre || cur.rows.length) {
            // «رصيد منقول/مرحل» في رأس صفحة تالية: للتحقق من الرصيد فقط، ومجاميعه ليست حركات
            item.d = { empty: true };
            item.c = { empty: true };
            item.carry = true;
            cur.rows.push(item);
            return;
          }
          cur.pre = item;
          return;
        }
        if (item.d.empty && item.c.empty && item.b.empty) {
          // سطر نصي بلا مبالغ (عنوان حساب لكشف تالٍ، أو ملاحظة): لا أثر له على الرصيد
          since.push(row);
          cur.skipped.push({ src: src, reason: "سطر بلا مبالغ" });
          return;
        }
        since = [];
        cur.rows.push(item);
      });
    });
    return blocks;
  }

  /** يحوّل مبلغًا مقروءًا إلى وحدة صغرى بإشارته، أو يرمي سببًا مفهومًا. */
  function signedMinor(p, dec, what) {
    if (p.empty) return 0n;
    if (!p.ok) throw new Error(what + " غير مفهوم (" + p.reason + ")");
    var m = toMinor(p, dec);
    if (m === null) throw new Error(what + " فيه منازل عشرية أكثر من المسموح للعملة (" + dec + ")");
    return p.neg ? -m : m;
  }

  /**
   * يتحقق من كشف واحد ويحوّله إلى رصيد سابق وحركات. الإشارة الداخلية: موجب = «علينا» (دائن − مدين)،
   * ثم تُقلب عند الإخراج لأن التطبيق يحسب «لنا» موجبًا (مدين − دائن).
   * يُجرَّب تفسيرا عمود الرصيد (موجبه علينا أو لنا)، ويُقبل فقط التفسير الذي يطابق كل الصفوف والإجمالي.
   */
  function checkStatement(st, dec, fallbackPositive) {
    var rows = st.rows.map(function (x) {
      var d = signedMinor(x.d, dec, "مبلغ «مدين/لنا»"), c = signedMinor(x.c, dec, "مبلغ «دائن/علينا»");
      if (d < 0n || c < 0n) throw new Error("قيمة سالبة في عمود مدين أو دائن (صف " + x.src.row + ")");
      return { x: x, d: d, c: c, b: x.b.empty ? null : signedMinor(x.b, dec, "الرصيد") };
    });
    var preB = st.pre ? signedMinor(st.pre.b, dec, "الرصيد السابق") : 0n;
    var cum = null;
    if (st.pre && !st.pre.d.empty && !st.pre.c.empty) {
      var pd = signedMinor(st.pre.d, dec, "مجموع مدين السابق"), pc = signedMinor(st.pre.c, dec, "مجموع دائن السابق");
      if (pd !== 0n || pc !== 0n) cum = pc - pd;
    }
    var foot = null;
    if (st.footer) foot = { m: signedMinor(st.footer.p, dec, "اجمالي الرصيد"), side: st.footer.side };
    function attempt(mode) {
      var s = mode === "C" ? preB : -preB;
      var res = { mode: mode, s0: s, bad: null, checked: 0 };
      if (cum !== null && cum !== s) { res.bad = { src: st.pre.src, shown: preB, want: cum, what: "الرصيد السابق لا يساوي (دائن − مدين) في سطره" }; return res; }
      for (var i = 0; i < rows.length; i++) {
        s += rows[i].c - rows[i].d;
        if (rows[i].b !== null) {
          var want = mode === "C" ? s : -s;
          if (rows[i].b !== want) { res.bad = { src: rows[i].x.src, shown: rows[i].b, want: want, what: "الرصيد المكتوب لا يساوي الرصيد المحسوب" }; return res; }
          res.checked++;
        }
      }
      if (foot) {
        var abs = foot.m < 0n ? -foot.m : foot.m;
        var sAbs = s < 0n ? -s : s;
        var sideOk = foot.side === null || foot.side === "0" ? true : (foot.side === "C" ? s >= 0n : s <= 0n);
        var signedFoot = foot.m < 0n && foot.side === null ? (mode === "C" ? foot.m : -foot.m) : null;
        var okFoot = signedFoot !== null ? signedFoot === s : (abs === sAbs && (s === 0n || sideOk));
        if (!okFoot) { res.bad = { src: st.footer.src, shown: foot.m, want: s, what: "اجمالي الرصيد في آخر الكشف لا يساوي الرصيد المحسوب" }; return res; }
      }
      res.final = s;
      return res;
    }
    var A = attempt("C"), B = attempt("D");
    var pick = null, ambiguous = false;
    if (!A.bad && !B.bad) {
      // التفسيران متطابقان: لا حركات تغيّر الرصيد ولا مجاميع ولا نوع رصيد في الإجمالي
      if (A.final === B.final) pick = A;
      else { pick = fallbackPositive === "D" ? B : A; ambiguous = true; }
    } else if (!A.bad) pick = A;
    else if (!B.bad) pick = B;
    if (!pick) {
      var first = A.bad && B.bad ? ((A.bad.src.row || 0) >= (B.bad.src.row || 0) ? A.bad : B.bad) : (A.bad || B.bad);
      return { ok: false, bad: first, rows: rows };
    }
    return { ok: true, mode: pick.mode, s0: pick.s0, final: pick.final, checked: pick.checked, ambiguous: ambiguous, rows: rows, footer: !!foot };
  }

  /**
   * يبني النموذج (مكاتب، أرصدة سابقة، حركات) من كشوفات مقروءة.
   * cfg.statements: { balancesOnly, mergeCom, positiveIs ("C" افتراضيًا: الرصيد الموجب في الكشف يعني «علينا») }.
   */
  function buildStatements(cfg, statements, serialToDate) {
    var model = newModel();
    var opt = cfg.statements || {};
    model.files = [];
    var seen = {};
    statements.forEach(function (st) {
      var info = { file: st.file, src: st.src, account: st.account, curText: st.curText, code: null, rows: st.rows.length, status: "", ok: false };
      model.files.push(info);
      var label = st.file + (st.account && st.account.name ? " — " + (st.account.code ? st.account.code + " " : "") + st.account.name : "");
      if (!st.account || !st.account.name) { info.status = "لم يُعرف اسم الحساب أعلى الكشف"; err(model, st.src, info.status); return; }
      var code = st.curText ? (cfg.currencyMap && cfg.currencyMap[officeKey(st.curText)]) || guessCurrency(st.curText) : (opt.fixedCurrency || null);
      if (code === "__skip") { info.status = "عملة مستبعدة"; skip(model, st.src, "كشف بعملة مستبعدة: " + st.curText); return; }
      if (!code) { info.status = "العملة «" + (st.curText || "غير مكتوبة") + "» غير مطابَقة"; err(model, st.src, label + ": " + info.status); return; }
      info.code = code;
      var def = curDef(cfg, code);
      if (!def) { info.status = "العملة " + code + " غير معرّفة"; err(model, st.src, info.status); return; }
      var accKey = st.account.code ? "#" + st.account.code : keyOf(cfg, st.account.name);
      var dupKey = accKey + "|" + code;
      if (seen[dupKey]) {
        info.status = "مكرر: الحساب والعملة نفسيهما في «" + seen[dupKey] + "»";
        err(model, st.src, label + ": كشف مكرر للحساب والعملة نفسيهما (الأول في «" + seen[dupKey] + "»). احذف أحدهما، أو صدّر كشفًا واحدًا يغطي الفترة كلها.");
        return;
      }
      var chk;
      try { chk = checkStatement(st, def.dec, opt.positiveIs || "C"); } catch (e) { info.status = e.message; err(model, st.src, label + ": " + e.message); return; }
      if (!chk.ok) {
        var b = chk.bad;
        info.status = b.what + " (صف " + b.src.row + ")";
        err(model, b.src, label + ": " + b.what + " — في الملف " + fmtMinor(b.shown, def.dec) + " والمحسوب " + fmtMinor(b.want < 0n ? -b.want : b.want, def.dec) +
          ". لم يُنقل هذا الكشف؛ تأكد أنه كامل ولم يُعدَّل يدويًا.");
        return;
      }
      // التواريخ قبل أي إضافة: كشف فيه تاريخ غير مفهوم أو في المستقبل يُرفض كاملًا
      var order = guessDateOrder(chk.rows.map(function (r) { return r.x.date; }).concat(st.pre ? [st.pre.date] : []).filter(Boolean));
      var rowsAt = chk.rows.map(function (r) { return parseDate(r.x.date, order, serialToDate); });
      var badDate = null, future = null;
      chk.rows.forEach(function (r, k) {
        if (r.x.carry) return;
        if (!rowsAt[k]) { if (!badDate) badDate = r.x; return; }
        if (!future && epoch(rowsAt[k], 12) > cfg.now + 10 * 60000) future = r.x;
      });
      if (badDate || future) {
        var bx = badDate || future;
        info.status = (badDate ? "تاريخ غير مفهوم" : "تاريخ في المستقبل") + " (صف " + bx.src.row + ")";
        err(model, bx.src, label + ": " + (badDate ? "تاريخ غير مفهوم «" + text(bx.date) + "»" : "تاريخ في المستقبل «" + text(bx.date) + "»") + "؛ لم يُنقل هذا الكشف.");
        return;
      }
      seen[dupKey] = st.file;
      info.ok = true;
      info.checked = chk.checked;
      info.footer = chk.footer;
      info.opening = -chk.s0;
      info.final = -chk.final;
      info.status = "✓";
      if (chk.ambiguous) model.warnings.push(label + ": لا حركات ولا مجاميع تبيّن اتجاه الرصيد؛ اعتُبر الرصيد الموجب «" + (opt.positiveIs === "D" ? "لنا" : "علينا") + "». راجعه.");
      // المكتب: الرمز يجمع عملات الحساب نفسه من كل الملفات
      var name = st.account.name;
      var i = model.officeIndex[accKey];
      if (i === undefined) {
        i = model.offices.length;
        model.officeIndex[accKey] = i;
        model.offices.push({ name: displayName(cfg, name), key: accKey, phone: "", variants: {}, tx: 0, openings: 0,
          notes: st.account.code ? "رمز الحساب في البرنامج السابق: " + st.account.code : "" });
      }
      var o = model.offices[i];
      o.variants[clean(name)] = true;
      model.used[code] = true;
      var files = model.lastBalance = model.lastBalance || {};
      files[accKey + "|" + code] = { minor: -chk.final, src: st.footer ? st.footer.src : st.src };
      var preDt = st.pre ? parseDate(st.pre.date, order, serialToDate) : null;
      var firstAt = rowsAt.filter(Boolean)[0] || null;
      var startDt = preDt || st.from || firstAt;
      var openWhen = startDt ? new Date(startDt.y, startDt.m - 1, startDt.d, 0, 0, 0).getTime() - 60000 : (cfg.openingDate || cfg.now);
      if (opt.balancesOnly) {
        // الأرصدة النهائية فقط: رصيد واحد بتاريخ نهاية الكشف
        var endDt = st.to || rowsAt.filter(Boolean).slice(-1)[0] || preDt;
        var endWhen = endDt ? new Date(endDt.y, endDt.m - 1, endDt.d, 23, 0, 0).getTime() : cfg.now;
        if (endWhen > cfg.now) endWhen = cfg.now;
        if (chk.final !== 0n) {
          model.openings.push({ office: i, code: code, minor: -chk.final, when: endWhen, src: st.src });
          o.openings++;
        } else o.emptyOk = true;
        return;
      }
      if (chk.s0 !== 0n) {
        model.openings.push({ office: i, code: code, minor: -chk.s0, when: openWhen, src: st.pre ? st.pre.src : st.src, inline: true });
        o.openings++;
      }
      var last = null;
      chk.rows.forEach(function (r, k) {
        if (r.x.carry) { skip(model, r.x.src, "رصيد منقول (للتحقق فقط)"); return; }
        var when = epoch(rowsAt[k], 12);
        var parts = [];
        if (r.d > 0n) parts.push(["D", r.d]);
        if (r.c > 0n) parts.push(["C", r.c]);
        if (!parts.length) { skip(model, r.x.src, "صف بلا مبلغ"); return; }
        if (parts.length === 2) model.both++;
        // «Com فلان» بعد حركة «فلان» في اليوم نفسه: أجرتها (اختياري)
        var cm = opt.mergeCom && parts.length === 1 ? r.x.desc.match(/^\s*com\.?\s+(.+)$/i) : null;
        if (cm && last && last.when === when && !last.fee && clean(last.desc) === clean(cm[1])) {
          last.fee = parts[0][1];
          last.feeDir = parts[0][0];
          last.notes += " • الأجرة من صف " + r.x.src.row;
          return;
        }
        parts.forEach(function (p) {
          var t = { office: i, when: when, dir: p[0], minor: p[1], code: code, person: "", desc: r.x.desc,
            notes: "منقولة من: " + r.x.src.sheet + " — صف " + r.x.src.row, fee: 0n, feeDir: "D", src: r.x.src, seq: model.txs.length };
          model.txs.push(t);
          o.tx++;
          last = parts.length === 1 ? t : null;
        });
        model.rowsRead++;
      });
    });
    model.offices.forEach(function (o) {
      var v = Object.keys(o.variants);
      if (v.length > 1) model.warnings.push("الحساب «" + o.name + "» مكتوب بأكثر من اسم في الكشوفات: " + v.join(" / ") + " (جُمع برمز الحساب).");
    });
    if (model.both > 0) model.warnings.push(model.both + " صف فيه مبلغ «لنا» ومبلغ «علينا» معًا: سُجّل كل منها حركة مستقلة.");
    return model;
  }

  // ===================================================================================
  // جداول النسخة الاحتياطية (مطابقة لما يكتبه التطبيق في Db.addOffice و Db.addTx)
  // ===================================================================================

  var TYPE_DEBIT = "مدين / لنا", TYPE_CREDIT = "دائن / علينا";
  var FEE_NONE = "بدون أجرة", FEE_US = "الأجرة لنا", FEE_THEM = "الأجرة علينا";
  var TABLE_ORDER = ["currencies", "offices", "office_currencies", "tx", "journal_entries", "journal_lines",
    "opening_balances", "exchange_rates", "settings", "audit_logs", "reconciliations", "reconciliation_lines"];

  function num(b) {
    var n = Number(b);
    if (!Number.isSafeInteger(n)) throw new Error("مبلغ أكبر من الحد المسموح");
    return n;
  }

  function uuid(rand) {
    var b = rand(16);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    var h = Array.prototype.map.call(b, function (x) { return (x < 16 ? "0" : "") + x.toString(16); }).join("");
    return h.slice(0, 8) + "-" + h.slice(8, 12) + "-" + h.slice(12, 16) + "-" + h.slice(16, 20) + "-" + h.slice(20);
  }

  function tables(model, cfg, rand) {
    var T = {};
    TABLE_ORDER.forEach(function (t) { T[t] = []; });
    // العملات: الافتراضية + المستخدمة
    var codes = DEFAULT_CODES.slice();
    Object.keys(model.used).forEach(function (c) { if (codes.indexOf(c) < 0) codes.push(c); });
    codes.forEach(function (c) {
      var d = curDef(cfg, c) || { name: c, dec: 2 };
      T.currencies.push({ code: c, name: d.name || c, decimals: d.dec, active: 1 });
    });
    // المكاتب وعملاتها
    var officeCur = model.offices.map(function () { return {}; });
    model.openings.forEach(function (o) { officeCur[o.office][o.code] = true; });
    model.txs.forEach(function (t) { officeCur[t.office][t.code] = true; });
    model.offices.forEach(function (o, i) {
      T.offices.push({ id: i + 1, name: o.name, short_name: "", phone: o.phone || "", location: "", contact: "", notes: o.notes || "",
        active: 1, usd_limit_minor: null, usd_owe_limit_minor: null, pinned: 0 });
      var cs = Object.keys(officeCur[i]).sort();
      if (cs.length === 0) cs = [cfg.defaultCurrency || "USD"];
      cs.forEach(function (c) { T.office_currencies.push({ office_id: i + 1, code: c, limit_minor: null, limit_owe_minor: null }); });
    });
    var entryId = 0, lineId = 0;
    function post(txId, kind, office, cur, debit, credit, when) {
      // مثل Db.postEntry: سطر المكتب + سطر الحساب المقابل بقيمة معاكسة
      if (debit < 0n || credit < 0n || debit === credit) throw new Error("قيد غير صالح");
      var eid = ++entryId;
      T.journal_entries.push({ id: eid, tx_id: txId, kind: kind, created_at: when, reverses_id: null, status: "POSTED" });
      T.journal_lines.push({ id: ++lineId, entry_id: eid, account: "OFFICE", office_id: office, currency: cur, debit_minor: num(debit), credit_minor: num(credit) });
      var contra = kind === "FEE" ? (debit > 0n ? "FEE_REVENUE" : "FEE_EXPENSE") : (kind === "OPENING" ? "OPENING" : "SETTLEMENT");
      T.journal_lines.push({ id: ++lineId, entry_id: eid, account: contra, office_id: null, currency: cur, debit_minor: num(credit), credit_minor: num(debit) });
      return eid;
    }
    // الأرصدة الافتتاحية أولًا (بترتيب التاريخ)
    var opens = model.openings.slice().sort(function (a, b) { return a.when - b.when; });
    var obId = 0;
    opens.forEach(function (o) {
      var us = o.minor > 0n;
      var amt = us ? o.minor : -o.minor;
      var eid = post(null, "OPENING", o.office + 1, o.code, us ? amt : 0n, us ? 0n : amt, o.when);
      T.opening_balances.push({ id: ++obId, office_id: o.office + 1, currency: o.code, type: us ? "لنا" : "علينا", amount_minor: num(amt), entry_id: eid });
    });
    // الحركات بترتيب التاريخ ثم ترتيبها في الملف
    var txs = model.txs.slice().sort(function (a, b) { return a.when - b.when || a.seq - b.seq; });
    var txId = 0;
    var user = cfg.userName || "نقل من البرنامج السابق";
    txs.forEach(function (t) {
      var id = ++txId;
      var hasFee = t.fee > 0n;
      var feeType = hasFee ? (t.feeDir === "D" ? FEE_US : FEE_THEM) : FEE_NONE;
      T.tx.push({ id: id, uuid: uuid(rand), office_id: t.office + 1, type: t.dir === "D" ? TYPE_DEBIT : TYPE_CREDIT, person: t.person || "",
        amount_minor: num(t.minor), currency: t.code, fee_type: feeType, fee_minor: hasFee ? num(t.fee) : 0, fee_currency: t.code,
        description: t.desc || "", notes: t.notes || "", created_at: t.when, user_name: user, status: "ACTIVE", replaces_id: null });
      post(id, "PRINCIPAL", t.office + 1, t.code, t.dir === "D" ? t.minor : 0n, t.dir === "C" ? t.minor : 0n, t.when);
      if (hasFee) post(id, "FEE", t.office + 1, t.code, t.feeDir === "D" ? t.fee : 0n, t.feeDir === "C" ? t.fee : 0n, t.when);
    });
    if (cfg.officeName) T.settings.push({ k: "office_name", v: cfg.officeName });
    T.settings.push({ k: "migrated_at", v: String(cfg.now) });
    T.audit_logs.push({ id: 1, action: "MIGRATION", entity: "import", entity_id: 0, user_name: user,
      reason: "نقل من " + (cfg.sourceName || "ملف Excel") + ": " + T.offices.length + " مكتب، " + T.tx.length + " حركة، " + T.opening_balances.length + " رصيد سابق",
      created_at: cfg.now });
    return T;
  }

  /** الأرصدة من أسطر القيود تمامًا كما يحسبها التطبيق: مجموع (مدين − دائن) لحساب المكتب. */
  function balancesFromTables(T) {
    var b = {};
    T.journal_lines.forEach(function (l) {
      if (l.account !== "OFFICE" || l.office_id === null) return;
      var k = l.office_id + "|" + l.currency;
      b[k] = (b[k] || 0n) + BigInt(l.debit_minor) - BigInt(l.credit_minor);
    });
    return b;
  }

  /** فحص مستقل لتوازن كل قيد (كما في BackupFormat.validate). */
  function checkBalanced(T) {
    var sums = {};
    T.journal_lines.forEach(function (l) {
      var k = l.entry_id + "|" + l.currency;
      sums[k] = (sums[k] || 0n) + BigInt(l.debit_minor) - BigInt(l.credit_minor);
    });
    return Object.keys(sums).filter(function (k) { return sums[k] !== 0n; });
  }

  /** تقرير الأرصدة لكل مكتب وعملة + مقارنة بعمود الرصيد إن وُجد. */
  function report(model, T, cfg) {
    var decOf = {};
    T.currencies.forEach(function (c) { decOf[c.code] = c.decimals; });
    var kind = {};
    T.journal_entries.forEach(function (e) { kind[e.id] = e.kind; });
    var agg = {};
    T.journal_lines.forEach(function (l) {
      if (l.account !== "OFFICE" || l.office_id === null) return;
      var k = l.office_id + "|" + l.currency;
      var a = agg[k] = agg[k] || { opening: 0n, debit: 0n, credit: 0n };
      var d = BigInt(l.debit_minor), c = BigInt(l.credit_minor);
      if (kind[l.entry_id] === "OPENING") a.opening += d - c;
      else { a.debit += d; a.credit += c; }
    });
    var rows = [];
    var totals = {};
    model.offices.forEach(function (o, i) {
      var cs = [];
      T.office_currencies.forEach(function (oc) { if (oc.office_id === i + 1) cs.push(oc.code); });
      cs.sort().forEach(function (c) {
        var a = agg[(i + 1) + "|" + c] || { opening: 0n, debit: 0n, credit: 0n };
        var v = a.opening + a.debit - a.credit;
        var exp = model.lastBalance ? model.lastBalance[o.key + "|" + c] : null;
        rows.push({ office: o.name, code: c, dec: decOf[c], opening: a.opening, debit: a.debit, credit: a.credit, balance: v,
          expected: exp ? exp.minor : null, match: exp ? exp.minor === v : null, txCount: o.tx });
        var t = totals[c] = totals[c] || { code: c, dec: decOf[c], receivable: 0n, payable: 0n, nr: 0, np: 0 };
        if (v > 0n) { t.receivable += v; t.nr++; } else if (v < 0n) { t.payable += -v; t.np++; }
      });
    });
    return { rows: rows, totals: Object.keys(totals).sort().map(function (k) { return totals[k]; }) };
  }

  // ===================================================================================
  // ZIP بدون ضغط (يقرؤه ZipInputStream في التطبيق)
  // ===================================================================================

  var CRC_TABLE = (function () {
    var t = new Uint32Array(256);
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(b) {
    var c = 0xffffffff;
    for (var i = 0; i < b.length; i++) c = CRC_TABLE[(c ^ b[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }

  function utf8(s) { return new TextEncoder().encode(s); }

  function zipStored(files, when) {
    var d = new Date(when);
    var dosTime = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
    var dosDate = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
    var parts = [], central = [], offset = 0;
    files.forEach(function (f) {
      var name = utf8(f.name), data = f.bytes, crc = crc32(data);
      var h = new DataView(new ArrayBuffer(30));
      h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true); h.setUint16(8, 0, true);
      h.setUint16(10, dosTime, true); h.setUint16(12, dosDate, true); h.setUint32(14, crc, true);
      h.setUint32(18, data.length, true); h.setUint32(22, data.length, true); h.setUint16(26, name.length, true); h.setUint16(28, 0, true);
      parts.push(new Uint8Array(h.buffer), name, data);
      var c = new DataView(new ArrayBuffer(46));
      c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true); c.setUint16(10, 0, true);
      c.setUint16(12, dosTime, true); c.setUint16(14, dosDate, true); c.setUint32(16, crc, true); c.setUint32(20, data.length, true);
      c.setUint32(24, data.length, true); c.setUint16(28, name.length, true); c.setUint16(30, 0, true); c.setUint16(32, 0, true);
      c.setUint16(34, 0, true); c.setUint16(36, 0, true); c.setUint32(38, 0, true); c.setUint32(42, offset, true);
      central.push(new Uint8Array(c.buffer), name);
      offset += 30 + name.length + data.length;
    });
    var cdSize = central.reduce(function (s, x) { return s + x.length; }, 0);
    var e = new DataView(new ArrayBuffer(22));
    e.setUint32(0, 0x06054b50, true); e.setUint16(4, 0, true); e.setUint16(6, 0, true); e.setUint16(8, files.length, true);
    e.setUint16(10, files.length, true); e.setUint32(12, cdSize, true); e.setUint32(16, offset, true); e.setUint16(20, 0, true);
    return concat(parts.concat(central, [new Uint8Array(e.buffer)]));
  }

  function concat(arrs) {
    var n = arrs.reduce(function (s, a) { return s + a.length; }, 0);
    var out = new Uint8Array(n), p = 0;
    arrs.forEach(function (a) { out.set(a, p); p += a.length; });
    return out;
  }

  /** محتوى النسخة: manifest + الجداول + فهرس الصور (فارغ) + summary في النهاية. */
  function backupZip(T, cfg) {
    var files = [];
    var counts = { offices: T.offices.length, tx: T.tx.length, attachments: 0, currencies: T.currencies.length };
    var manifest = { format: 5, app: "smart-accountant-hawala", appName: "المحاسب الذكي لمكاتب الحوالات",
      appVersion: "أداة النقل 1.1", schema: 6, createdAt: cfg.now, counts: counts, source: "migration" };
    files.push({ name: "manifest.json", bytes: utf8(JSON.stringify(manifest, null, 1)) });
    var rows = {};
    TABLE_ORDER.forEach(function (t) {
      var lines = T[t].map(function (r) { return JSON.stringify(r) + "\n"; }).join("");
      files.push({ name: "tables/" + t + ".ndjson", bytes: utf8(lines) });
      rows[t] = T[t].length;
    });
    files.push({ name: "attachments/index.ndjson", bytes: new Uint8Array(0) });
    var summary = { rows: rows, attachments: 0, skippedAttachments: 0,
      warnings: ["ملف نقل من: " + (cfg.sourceName || "ملف Excel") + " — راجع أرصدة المكاتب بعد الاستعادة وقارنها بالبرنامج السابق"],
      completedAt: cfg.now };
    files.push({ name: "summary.json", bytes: utf8(JSON.stringify(summary, null, 1)) });
    return zipStored(files, cfg.now);
  }

  // ===================================================================================
  // التشفير: SMARTBK5 (مطابق لـ SecureStreams.EncryptingOutputStream)
  // ===================================================================================

  function be32(v) { return new Uint8Array([(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255]); }

  function iv(prefix, counter) {
    var out = new Uint8Array(12);
    out.set(prefix, 0);
    out.set(be32(counter >>> 0), 8);
    return out;
  }

  async function encrypt(plain, password, opts, subtle, rand) {
    opts = opts || {};
    var iterations = opts.iterations || 310000;
    var chunk = opts.chunk || 262144;
    var salt = opts.salt || rand(16);
    var prefix = opts.prefix || rand(8);
    var header = concat([utf8("SMARTBK5"), be32(1), be32(iterations), be32(16), salt, be32(8), prefix, be32(chunk)]);
    var base = await subtle.importKey("raw", utf8(password), "PBKDF2", false, ["deriveKey"]);
    var key = await subtle.deriveKey({ name: "PBKDF2", hash: "SHA-256", salt: salt, iterations: iterations }, base,
      { name: "AES-GCM", length: 256 }, false, ["encrypt"]);
    var out = [header];
    var v = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv: iv(prefix, 0xffffffff), additionalData: concat([header, utf8("VERIFY")]), tagLength: 128 },
      key, new Uint8Array(16)));
    out.push(be32(v.length), v);
    var count = Math.max(1, Math.ceil(plain.length / chunk));
    for (var i = 0; i < count; i++) {
      var last = i === count - 1 ? 1 : 0;
      var part = plain.subarray(i * chunk, Math.min(plain.length, (i + 1) * chunk));
      var aad = new Uint8Array(header.length + 9);
      aad.set(header, 0);
      // رقم الجزء 8 بايت (long) ثم علامة «الأخير»
      aad.set(be32(0), header.length);
      aad.set(be32(i), header.length + 4);
      aad[header.length + 8] = last;
      var ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv: iv(prefix, i), additionalData: aad, tagLength: 128 }, key, part));
      out.push(new Uint8Array([last]), be32(ct.length), ct);
    }
    return concat(out);
  }

  async function makeBackup(T, cfg, password, subtle, rand, opts) {
    var zip = backupZip(T, cfg);
    return encrypt(zip, password, opts, subtle, rand);
  }

  // ===================================================================================
  // تقرير CSV (يفتح في Excel بالعربية)
  // ===================================================================================

  function csvCell(s) {
    s = String(s === null || s === undefined ? "" : s);
    return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  function balWord(m, dec) {
    if (m === 0n) return "0";
    return (m > 0n ? "لنا " : "علينا ") + fmtMinor(m > 0n ? m : -m, dec);
  }

  function reportCsv(rep, model) {
    var lines = [];
    lines.push(["المكتب", "العملة", "الرصيد السابق", "حركات لنا (مدين)", "حركات علينا (دائن)", "الرصيد النهائي", "الرصيد في الملف", "المطابقة"].map(csvCell).join(","));
    rep.rows.forEach(function (r) {
      lines.push([r.office, r.code, balWord(r.opening, r.dec), fmtMinor(r.debit, r.dec), fmtMinor(r.credit, r.dec), balWord(r.balance, r.dec),
        r.expected === null ? "" : balWord(r.expected, r.dec), r.match === null ? "" : (r.match ? "مطابق" : "غير مطابق")].map(csvCell).join(","));
    });
    lines.push("");
    lines.push(["العملة", "مجموع ما لنا", "عدد المكاتب", "مجموع ما علينا", "عدد المكاتب", "الصافي"].map(csvCell).join(","));
    rep.totals.forEach(function (t) {
      lines.push([t.code, fmtMinor(t.receivable, t.dec), t.nr, fmtMinor(t.payable, t.dec), t.np, balWord(t.receivable - t.payable, t.dec)].map(csvCell).join(","));
    });
    if (model.errors.length || model.skipped.length) {
      lines.push("");
      lines.push(["الورقة", "الصف", "الحالة", "السبب"].map(csvCell).join(","));
      model.errors.forEach(function (e) { lines.push([e.sheet, e.row, "خطأ", e.msg].map(csvCell).join(",")); });
      model.skipped.forEach(function (e) { lines.push([e.sheet, e.row, "تخطٍّ", e.reason].map(csvCell).join(",")); });
    }
    return "﻿" + lines.join("\r\n");
  }

  return {
    normDigits: normDigits, clean: clean, officeKey: officeKey, looseKey: looseKey, headerKey: headerKey,
    parseAmount: parseAmount, toMinor: toMinor, fmtMinor: fmtMinor, balWord: balWord,
    parseDate: parseDate, guessDateOrder: guessDateOrder, epoch: epoch, fmtDate: fmtDate,
    sheetRows: sheetRows, text: text, isBlankRow: isBlankRow,
    guessHeaderRow: guessHeaderRow, guessColumns: guessColumns, guessCurrency: guessCurrency, guessDirection: guessDirection,
    KNOWN: KNOWN, DEFAULT_CODES: DEFAULT_CODES, OPENING_RE: OPENING_RE, TOTAL_RE: TOTAL_RE,
    build: build, tables: tables, stmtHeader: stmtHeader, splitAccount: splitAccount, parseStatements: parseStatements, isStatementFile: isStatementFile, checkStatement: checkStatement,
    buildStatements: buildStatements, PRE_RE: PRE_RE, FOOT_RE: FOOT_RE, keyOf: keyOf, displayName: displayName, balancesFromTables: balancesFromTables, checkBalanced: checkBalanced, report: report,
    crc32: crc32, zipStored: zipStored, backupZip: backupZip, encrypt: encrypt, makeBackup: makeBackup, reportCsv: reportCsv
  };
})();
if (typeof module !== "undefined") module.exports = Core;
