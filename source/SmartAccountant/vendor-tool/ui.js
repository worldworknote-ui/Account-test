/* أداة البائع — الواجهة. كل نص قادم من العميل (الطلب، الإيصال) يُعرض نصًا لا HTML. */
(function () {
  "use strict";
  var VT = window.VT;
  var $ = function (id) { return document.getElementById(id); };
  var LS_LEDGER = "sa8v.ledger", LS_KEY = "sa8v.key", LS_STATUS = "sa8v.status", LS_TPL = "sa8v.templates";

  var S = { signer: null, ledger: load(LS_LEDGER, VT.newLedger()), status: load(LS_STATUS, null), sale: null, move: { lid: "", receipt: null, review: null } };

  function load(k, def) {
    try {
      var v = localStorage.getItem(k);
      return v ? JSON.parse(v) : def;
    } catch (e) {
      return def;
    }
  }

  function save(k, v) {
    try {
      localStorage.setItem(k, typeof v === "string" ? v : JSON.stringify(v));
      return true;
    } catch (e) {
      return false;
    }
  }

  function saveLedger() {
    if (!save(LS_LEDGER, S.ledger)) alert("تعذر حفظ السجل في المتصفح. نزّل نسخة احتياطية منه الآن.");
  }

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; });
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function msg(box, kind, text) {
    var d = el("div", "msg " + kind, text);
    box.appendChild(d);
    return d;
  }

  function download(name, text, type) {
    var a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: type || "application/json" }));
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () {
      URL.revokeObjectURL(a.href);
      a.remove();
    }, 1000);
  }

  function copy(text, btn) {
    var done = function () {
      if (!btn) return;
      var t = btn.textContent;
      btn.textContent = "✓ نُسخ";
      setTimeout(function () { btn.textContent = t; }, 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fallback);
    else fallback();
    function fallback() {
      var ta = el("textarea");
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand("copy");
        done();
      } catch (e) {
        alert("انسخ النص يدويًا");
      }
      ta.remove();
    }
  }

  /** نص لاتيني/أرقام داخل جملة عربية: يُعزل اتجاهه فلا ينقلب «2026-0153». */
  function iso(s) { return "\u2066" + String(s == null ? "" : s) + "\u2069"; }
  function dateInput(yyyymmdd) { var s = String(yyyymmdd); return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8); }
  function fromDateInput(v) { return Number(String(v).replace(/-/g, "")); }
  function fmtDate(t) { var d = new Date(t); return d.getFullYear() + "/" + String(d.getMonth() + 1).padStart(2, "0") + "/" + String(d.getDate()).padStart(2, "0"); }
  function fmtUu(u) { var s = String(u); return s.substring(0, 4) + "/" + s.substring(4, 6) + "/" + s.substring(6); }

  // ------------------------------------------------------------------ التبويبات
  document.querySelectorAll(".tabs button").forEach(function (b) {
    b.addEventListener("click", function () {
      document.querySelectorAll(".tabs button").forEach(function (x) { x.classList.toggle("on", x === b); });
      document.querySelectorAll(".tab").forEach(function (t) { t.classList.toggle("hidden", t.id !== "tab-" + b.dataset.tab); });
      if (b.dataset.tab === "ledger") renderLedger();
      if (b.dataset.tab === "move") renderMoveList();
    });
  });

  // ------------------------------------------------------------------ المفتاح
  function keyState() {
    var pill = $("keyPill"), box = $("keyState");
    box.innerHTML = "";
    if (S.signer && S.signer.kid) {
      pill.textContent = "مفتاح " + S.signer.kid + " جاهز";
      pill.className = "pill on";
      msg(box, "ok", "المفتاح محمّل: " + S.signer.kid + (S.signer.kid === "k2" ? " (الاحتياطي)" : "") + ". يطابق المفتاح العام المضمّن في التطبيق.");
    } else {
      pill.textContent = "لا يوجد مفتاح";
      pill.className = "pill off";
      msg(box, "warn", "لم يُحمَّل مفتاح بعد: يمكنك فحص الطلبات، ولا يمكن إصدار رموز.");
    }
    var sealed = load(LS_KEY, null);
    if (sealed) msg(box, "info", "يوجد مفتاح محفوظ مشفّرًا في هذا المتصفح. افتحه بعبارة المرور.");
    updateIssue();
  }

  $("keyFile").addEventListener("change", function (ev) {
    var f = ev.target.files[0];
    ev.target.value = "";
    if (!f) return;
    f.text().then(VT.importPrivatePem).then(function (k) {
      if (!k.kid) {
        $("keyMsg").innerHTML = "";
        msg($("keyMsg"), "err", "هذا المفتاح ليس من مفاتيح التطبيق (k1 أو k2): الرموز الموقّعة به لن تعمل.");
        return;
      }
      S.signer = k;
      keyState();
      $("keyMsg").innerHTML = "";
      msg($("keyMsg"), "ok", "حُمّل المفتاح " + k.kid + ". للحفظ في هذا المتصفح اكتب عبارة مرور واضغط «حفظ مشفّرًا».");
    }).catch(function (e) {
      $("keyMsg").innerHTML = "";
      msg($("keyMsg"), "err", "تعذر قراءة المفتاح: " + (e.message || e));
    });
  });

  $("keyForget").addEventListener("click", function () {
    S.signer = null;
    keyState();
  });

  $("keySeal").addEventListener("click", function () {
    var box = $("keyMsg");
    box.innerHTML = "";
    if (!S.signer) return msg(box, "err", "حمّل ملف المفتاح أولًا.");
    if ($("kp1").value !== $("kp2").value) return msg(box, "err", "العبارتان غير متطابقتين.");
    VT.sealKey(S.signer.pkcs8, $("kp1").value).then(function (sealed) {
      save(LS_KEY, sealed);
      $("kp1").value = $("kp2").value = "";
      msg(box, "ok", "حُفظ المفتاح مشفّرًا. في المرة القادمة افتحه بعبارة المرور. احتفظ بملف ‎.pem الأصلي في مكان آمن أيضًا.");
      keyState();
    }).catch(function (e) { msg(box, "err", e.message); });
  });

  $("keyOpen").addEventListener("click", function () {
    var box = $("keyMsg"), sealed = localStorage.getItem(LS_KEY);
    box.innerHTML = "";
    if (!sealed) return msg(box, "err", "لا يوجد مفتاح محفوظ في هذا المتصفح.");
    VT.openKey(sealed, $("kp1").value).then(function (k) {
      S.signer = k;
      $("kp1").value = $("kp2").value = "";
      keyState();
      msg(box, "ok", "فُتح المفتاح " + k.kid + ".");
    }).catch(function (e) { msg(box, "err", e.message); });
  });

  $("keyDrop").addEventListener("click", function () {
    if (!confirm("حذف المفتاح المحفوظ من هذا المتصفح؟ (ملف ‎.pem الأصلي لا يتأثر)")) return;
    localStorage.removeItem(LS_KEY);
    keyState();
  });

  // ------------------------------------------------------------------ حالة الإلغاء من Google
  function statusState() {
    var box = $("statusState");
    box.innerHTML = "";
    if (!S.status) msg(box, "info", "لم يُحمَّل ملف حالة الإلغاء (اختياري).");
    else msg(box, "ok", "ملف حالة الإلغاء محمّل: " + Object.keys(S.status.entries || {}).length + " شهادة ملغاة.");
  }

  $("statusFile").addEventListener("change", function (ev) {
    var f = ev.target.files[0];
    ev.target.value = "";
    if (!f) return;
    f.text().then(function (t) {
      var j = JSON.parse(t);
      if (!j.entries) throw new Error("الملف لا يحوي entries");
      S.status = { entries: j.entries };
      save(LS_STATUS, S.status);
      statusState();
    }).catch(function (e) { alert("ملف غير صالح: " + e.message); });
  });

  // ------------------------------------------------------------------ عرض نتيجة الفحص
  function showReview(box, rv) {
    box.innerHTML = "";
    if (rv.req) {
      var r = rv.req, d = el("div", "facts");
      d.textContent = "رقم الطلب: " + iso(r.order) + " • " + (r.platform === "mac" ? "الماك" : "الهاتف") + ": " + iso(r.model || "—") + " • البرنامج " + iso((r.ver || "") + " (" + (r.build || "") + ")");
      box.appendChild(d);
    }
    rv.errors.forEach(function (t) { msg(box, "err", "✗ " + t); });
    rv.warnings.forEach(function (t) { msg(box, "warn", "⚠ " + t); });
    rv.info.forEach(function (t) { msg(box, "ok", "✓ " + t); });
    if (!rv.errors.length && rv.req) msg(box, "info", rv.warnings.length ? "يمكن الإصدار بعد قراءة التحذيرات." : "الطلب سليم ومصدَّق.");
  }

  function attestLabel(rv) {
    if (rv.mac) return "ماك (سلسلة المفاتيح)";
    if (!rv.attest) return "بلا تصديق";
    if (!rv.attest.rootTrusted) return "تصديق غير معتمد";
    return VT.LEVELS[rv.level] || "مصدَّق";
  }

  // ------------------------------------------------------------------ تفعيل جديد
  $("sUu").value = dateInput(VT.defaultUu(Date.now()));

  // ------------------------------------------------------------------ 12.6: محتوى الرخصة (قوالب قابلة للتعديل وحدود بالأرقام)
  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  S.templates = load(LS_TPL, null) || clone(VT.DEFAULT_TEMPLATES);
  function saveTemplates() { if (!save(LS_TPL, S.templates)) alert("تعذر حفظ القوالب في المتصفح."); }

  /** لوحة البنود: قالب، كل حد بخانة رقم (فارغ = بلا حد)، الميزات، النسخ، والاشتراك. */
  function contentPanel(box, onChange) {
    box.innerHTML = "";
    var top = el("div", "row");
    var sel = el("select", "search");
    var bSave = el("button", "btn sec", "حفظ كقالب"), bDel = el("button", "btn sec", "حذف القالب"), bReset = el("button", "btn sec", "القوالب الافتراضية");
    top.appendChild(sel);
    top.appendChild(bSave);
    top.appendChild(bDel);
    top.appendChild(bReset);
    box.appendChild(top);
    var grid = el("div", "grid"), rows = {};
    VT.LIMITS.forEach(function (L) {
      var w = el("div", "f");
      var lab = el("label", "check");
      var cb = el("input");
      cb.type = "checkbox";
      lab.appendChild(cb);
      lab.appendChild(document.createTextNode(" " + L.label));
      var num = el("input", "ltr");
      num.type = "number";
      num.min = "1";
      num.placeholder = "فارغ = بلا حد";
      var note = el("small", null, "غير محدد ← حد المجانية (" + L.free + ")");
      w.appendChild(lab);
      w.appendChild(num);
      w.appendChild(note);
      grid.appendChild(w);
      rows[L.k] = { cb: cb, num: num };
      cb.addEventListener("change", function () { num.disabled = !cb.checked; changed(); });
      num.addEventListener("input", changed);
    });
    box.appendChild(grid);
    var g2 = el("div", "grid"), sws = {};
    VT.SWITCHES.forEach(function (w) {
      var lab = el("label", "check");
      var cb = el("input");
      cb.type = "checkbox";
      lab.appendChild(cb);
      lab.appendChild(document.createTextNode(" " + w.label));
      g2.appendChild(lab);
      sws[w.k] = cb;
      cb.addEventListener("change", changed);
    });
    box.appendChild(g2);
    var g3 = el("div", "grid");
    function numField(label, ph) {
      var l = el("label", "f", label);
      var i = el("input", "ltr");
      i.type = "number";
      i.min = "1";
      i.placeholder = ph;
      l.appendChild(i);
      g3.appendChild(l);
      i.addEventListener("input", changed);
      return i;
    }
    var bh = numField("النسخ التلقائي كل كم ساعة (1–24)", "فارغ = الافتراضي"), bn = numField("نسخة بعد كل كم حركة", "فارغ = الافتراضي");
    // 12.7: تأكيد الوقت من الإنترنت (بلا خادم) وعدد أجهزة المكتب
    var tc = numField("تأكيد الوقت من الإنترنت كل كم يوم", "فارغ = لا يلزم"), tg = numField("المهلة بعدها قبل حدود المجانية (أيام)", "فارغ = بلا مهلة"),
      md = numField("عدد أجهزة المكتب (لنفس هوية المكتب)", "فارغ = بلا حد");
    var exL = el("label", "check"), exCb = el("input");
    exCb.type = "checkbox";
    exL.appendChild(exCb);
    exL.appendChild(document.createTextNode(" اشتراك بتاريخ انتهاء (بعده يعمل بالمجانية دون قفل البيانات)"));
    var exD = el("input");
    exD.type = "date";
    var exW = el("div", "f");
    exW.appendChild(exL);
    exW.appendChild(exD);
    g3.appendChild(exW);
    exCb.addEventListener("change", function () { exD.disabled = !exCb.checked; changed(); });
    exD.addEventListener("input", changed);
    box.appendChild(g3);
    var sum = el("div", "facts");
    box.appendChild(sum);

    function fillSel(pick) {
      sel.innerHTML = "";
      S.templates.forEach(function (t, i) {
        var o = el("option", null, t.name);
        o.value = String(i);
        sel.appendChild(o);
      });
      var o2 = el("option", null, "— مخصص —");
      o2.value = "-1";
      sel.appendChild(o2);
      sel.value = String(pick == null ? 0 : pick);
    }
    function set(t) {
      VT.LIMITS.forEach(function (L) {
        var v = (t.lim || {})[L.k] || { on: false, n: "" };
        rows[L.k].cb.checked = !!v.on;
        rows[L.k].num.value = v.n == null ? "" : v.n;
        rows[L.k].num.disabled = !v.on;
      });
      VT.SWITCHES.forEach(function (w) { sws[w.k].checked = !!(t.sw || {})[w.k]; });
      bh.value = t.bh || "";
      bn.value = t.bn || "";
      tc.value = t.tc || "";
      tg.value = t.tg || "";
      md.value = t.md || "";
      exCb.checked = !!t.ex;
      exD.disabled = !t.ex;
      if (t.ex) exD.value = dateInput(t.ex);
      changed(true);
    }
    function get() {
      var t = { name: sel.value === "-1" ? "مخصصة" : (S.templates[Number(sel.value)] || {}).name || "مخصصة", lim: {}, sw: {} };
      VT.LIMITS.forEach(function (L) { t.lim[L.k] = { on: rows[L.k].cb.checked, n: rows[L.k].num.value.trim() }; });
      VT.SWITCHES.forEach(function (w) { t.sw[w.k] = sws[w.k].checked; });
      t.bh = bh.value.trim();
      t.bn = bn.value.trim();
      t.tc = tc.value.trim();
      t.tg = tg.value.trim();
      t.md = md.value.trim();
      if (exCb.checked) {
        if (!exD.value) throw new Error("اختر تاريخ انتهاء الاشتراك");
        t.ex = fromDateInput(exD.value);
      }
      return t;
    }
    function changed(quiet) {
      sum.textContent = "";
      try {
        sum.textContent = "الرخصة: " + VT.describe(VT.fieldsOf(get())).join(" • ");
      } catch (e) {
        sum.textContent = "⚠ " + e.message;
      }
      if (!quiet && onChange) onChange();
    }
    sel.addEventListener("change", function () { if (sel.value !== "-1") set(S.templates[Number(sel.value)]); });
    bSave.addEventListener("click", function () {
      var name = prompt("اسم القالب (يظهر للزبون اسمًا للإصدار):", sel.value === "-1" ? "" : (S.templates[Number(sel.value)] || {}).name || "");
      if (!name || !name.trim()) return;
      var t;
      try {
        t = get();
      } catch (e) {
        return alert(e.message);
      }
      t.name = name.trim();
      delete t.ex;   // تاريخ الانتهاء يخص كل زبون، لا القالب
      var i = S.templates.findIndex(function (x) { return x.name === t.name; });
      if (i >= 0) S.templates[i] = t;
      else S.templates.push(t);
      saveTemplates();
      fillSel(i >= 0 ? i : S.templates.length - 1);
      changed(true);
    });
    bDel.addEventListener("click", function () {
      var i = Number(sel.value);
      if (i < 0 || !S.templates[i]) return;
      if (!confirm("حذف القالب «" + S.templates[i].name + "»؟ الرخص الصادرة لا تتأثر.")) return;
      S.templates.splice(i, 1);
      saveTemplates();
      fillSel(0);
      if (S.templates[0]) set(S.templates[0]);
    });
    bReset.addEventListener("click", function () {
      if (!confirm("استعادة القوالب الثلاثة الافتراضية؟ قوالبك المحفوظة الأخرى تبقى.")) return;
      VT.DEFAULT_TEMPLATES.forEach(function (d) {
        var i = S.templates.findIndex(function (x) { return x.name === d.name; });
        if (i >= 0) S.templates[i] = clone(d);
        else S.templates.push(clone(d));
      });
      saveTemplates();
      fillSel(0);
      set(S.templates[0]);
    });
    fillSel(0);
    if (S.templates[0]) set(S.templates[0]);
    return {
      get: get, set: function (t, custom) {
        if (custom) sel.value = "-1";
        set(t);
      }
    };
  }

  /** بنود رخصة صادرة ← قالب في اللوحة (للتعديل). */
  function templateOf(l) {
    var ft = "," + (l.ft || "") + ",", all = ft.indexOf(",all,") >= 0, lim = l.lim || {};
    var t = { name: l.ed || "مخصصة", lim: {}, sw: {}, bh: lim.bh || "", bn: lim.bn || "", ex: lim.ex || "", tc: lim.tc || "", tg: lim.tg || "", md: lim.md || "" };
    VT.LIMITS.forEach(function (L) {
      var n = lim[L.key], flagOn = !!L.flag && (all || ft.indexOf("," + L.flag + ",") >= 0), on;
      if (!L.explicitFree) on = flagOn || n > 0;
      else if (!L.flag) on = n !== L.free;
      else on = flagOn || (n !== L.free);   // بلا علم ولا رقم = رخصة 12.5 وأقدم: مفتوحة
      t.lim[L.k] = { on: !!on, n: on && n ? n : "" };
    });
    VT.SWITCHES.forEach(function (w) { t.sw[w.k] = all || ft.indexOf("," + w.k + ",") >= 0; });
    return t;
  }

  var salePanel = contentPanel($("sContent"), function () { $("issueOut").innerHTML = ""; });

  $("reqCheck").addEventListener("click", function () {
    var box = $("reqOut");
    box.innerHTML = "";
    msg(box, "info", "جارٍ الفحص…");
    VT.reviewRequest($("reqText").value, { ledger: S.ledger, status: S.status }).then(function (rv) {
      S.sale = rv;
      showReview(box, rv);
      var ok = !!rv.req;
      $("saleForm").classList.toggle("off", !ok);
      $("sLid").value = VT.nextLid(S.ledger, Date.now());
      $("overrideBox").classList.toggle("hidden", !rv.errors.length);
      $("sOverride").checked = false;
      $("issueOut").innerHTML = "";
      updateIssue();
    });
  });
  $("sOverride").addEventListener("change", updateIssue);

  function updateIssue() {
    if ($("aIssue")) updateAmend();
    var rv = S.sale;
    $("issue").disabled = !(S.signer && S.signer.kid && rv && rv.req && (!rv.errors.length || $("sOverride").checked));
    var mv = S.move.review;
    $("mIssue").disabled = !(S.signer && S.signer.kid && mv && mv.req && S.move.lid && moveAllowed().ok && (!mv.errors.length || $("mOverride").checked));
  }

  function features() { return VT.fieldsOf(salePanel.get()); }

  /** 12.6: حقول الرمز من سجل رخصة (للنقل: تنتقل الحدود كلها). */
  function withLimits(base, lim) {
    var o = {};
    for (var k in base) o[k] = base[k];
    lim = lim || {};
    VT.NUM_KEYS.forEach(function (k) { if (lim[k]) o[k] = lim[k]; });
    if (lim.ex) o.ex = lim.ex;
    return o;
  }

  /** 11.5: ملف الطلب من العميل: يُقرأ نصه في خانة الطلب ثم يُفحص. */
  function reqFile(inputId, textId, checkId) {
    var inp = $(inputId);
    if (!inp) return;
    inp.addEventListener("change", function () {
      var f = inp.files && inp.files[0];
      if (!f) return;
      f.text().then(function (t) {
        $(textId).value = t;
        inp.value = "";
        $(checkId).click();
      });
    });
  }
  reqFile("reqFile", "reqText", "reqCheck");
  reqFile("mReqFile", "mReqText", "mReqCheck");

  function download(name, blob) {
    var a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  // ------------------------------------------------------------------ 12.6: تعديل رخصة
  S.amend = null;
  var amendPanel = contentPanel($("aContent"), function () { $("aIssueOut").innerHTML = ""; });
  function renderAmendList() {
    var box = $("aList"), q = $("aFind").value.trim().toLowerCase();
    box.innerHTML = "";
    var list = S.ledger.licenses.filter(function (l) { return l.status === "active"; })
      .filter(function (l) { return !q || (l.lid + " " + l.customer + " " + l.order + " " + l.phone).toLowerCase().indexOf(q) >= 0; });
    if (!list.length) return msg(box, "info", S.ledger.licenses.length ? "لا رخصة فعّالة مطابقة." : "السجل فارغ.");
    list.slice(-50).reverse().forEach(function (l) {
      var b = el("button", "pick" + (S.amend === l.lid ? " on" : ""));
      b.textContent = l.lid + " — " + (l.customer || "بلا اسم") + " — " + (l.ed || "") + (l.amendedAt ? " (معدّلة)" : "");
      b.addEventListener("click", function () {
        S.amend = l.lid;
        $("aIssueOut").innerHTML = "";
        $("aForm").classList.remove("off");
        $("aUu").value = dateInput(l.uu);
        $("aNote").value = "";
        var info = $("aInfo");
        info.innerHTML = "";
        msg(info, "info", "الرخصة " + l.lid + " • العميل: " + (l.customer || "—") + " • منذ " + fmtDate(l.issuedAt) + " • الحالية: "
          + VT.describe(withLimits({ ft: l.ft }, l.lim)).join(" • "));
        amendPanel.set(templateOf(l), true);
        renderAmendList();
        updateAmend();
      });
      box.appendChild(b);
    });
  }
  function updateAmend() { $("aIssue").disabled = !(S.signer && S.signer.kid && S.amend); }
  $("aFind").addEventListener("input", renderAmendList);
  document.querySelectorAll('[data-tab="amend"]').forEach(function (b) { b.addEventListener("click", function () { renderAmendList(); updateAmend(); }); });
  $("aIssue").addEventListener("click", function () {
    var box = $("aIssueOut"), l = VT.findLicense(S.ledger, S.amend);
    box.innerHTML = "";
    if (!l) return msg(box, "err", "اختر الرخصة أولًا.");
    var f;
    try {
      f = VT.fieldsOf(amendPanel.get());
    } catch (e) {
      return msg(box, "err", e.message);
    }
    var uu = fromDateInput($("aUu").value);
    VT.issueToken(S.signer, withLimits({ lid: l.lid, ed: f.ed, ft: f.ft, uu: uu, dk: l.dk, of: l.of || "" }, f)).then(function (token) {
      var lic = VT.amend(S.ledger, l.lid, withLimits({ token: token, ft: f.ft, ed: f.ed, uu: uu, note: $("aNote").value.trim() }, f), Date.now());
      saveLedger();
      tokenBox(box, token, lic, true);
      renderAmendList();
    }).catch(function (e) { msg(box, "err", e.message); });
  });

  function tokenBox(box, token, lic, amended) {
    box.innerHTML = "";
    msg(box, "ok", "صدر رمز الترخيص " + iso(lic.lid) + " وحُفظ في السجل.");
    var t = el("textarea", "ltr token");
    t.readOnly = true;
    t.rows = 4;
    t.value = token;
    box.appendChild(t);
    var text = "رمز ترخيص «المحاسب الذكي» (" + lic.lid + "):\n\n" + token + "\n\n" + (amended
      ? "في التطبيق: المزيد ← الترخيص ← «تحديث الرخصة»: الصق الرمز ثم اضغط «تحديث الرخصة»."
      : "في التطبيق: المزيد ← الترخيص ← الصق الرمز في «رمز الترخيص» ثم اضغط «تفعيل».") + "\nالتحديثات مشمولة حتى " + fmtUu(lic.uu) + "."
      + "\nالبنود: " + VT.describe(withLimits({ ft: lic.ft }, lic.lim)).join("، ") + ".";
    var row = el("div", "row");
    var b1 = el("button", "btn", "نسخ الرمز");
    b1.addEventListener("click", function () { copy(token, b1); });
    var b2 = el("button", "btn sec", "نسخ رسالة للعميل");
    b2.addEventListener("click", function () { copy(text, b2); });
    var b3 = el("button", "btn sec", "نسخة احتياطية من السجل");
    b3.addEventListener("click", backupLedger);
    row.appendChild(b1);
    row.appendChild(b2);
    row.appendChild(b3);
    box.appendChild(row);
    // 11.5: ملف المفتاح (رسالة واحدة لا تنقسم) ورمز QR يمسحه العميل بكاميرا هاتفه فيفتح التطبيق ويفعّل
    var row2 = el("div", "row");
    var b4 = el("button", "btn", "تنزيل ملف المفتاح (.sakey)");
    b4.addEventListener("click", function () {
      download("مفتاح-" + lic.lid + ".sakey", new Blob(["SMARTACC-LICENSE\n" + token + "\n"], { type: "text/plain" }));
    });
    row2.appendChild(b4);
    var link = "smartacc://activate?k=" + token, cv = null;
    try {
      cv = QR.toCanvas(QR.encode(link), 5);
    } catch (e) {
      cv = null;
    }
    if (cv) {
      var b5 = el("button", "btn sec", "تنزيل صورة QR");
      b5.addEventListener("click", function () {
        cv.toBlob(function (bl) { download("QR-" + lic.lid + ".png", bl); });
      });
      row2.appendChild(b5);
    }
    box.appendChild(row2);
    if (cv) {
      cv.style.maxWidth = "260px";
      cv.style.display = "block";
      cv.style.margin = "10px auto";
      box.appendChild(cv);
      box.appendChild(el("p", "lead", "رمز QR: يمسحه العميل بكاميرا هاتفه (من شاشة أخرى) فيفتح التطبيق ويُفعّل مباشرة. ملف المفتاح: يرسله في واتساب كمرفق، ويفتحه العميل من «الترخيص ← التفعيل من ملف»."));
    }
  }

  $("issue").addEventListener("click", function () {
    var box = $("issueOut"), rv = S.sale;
    box.innerHTML = "";
    var f;
    try {
      f = features();
    } catch (e) {
      return msg(box, "err", e.message);
    }
    var uu = fromDateInput($("sUu").value);
    var lid = VT.nextLid(S.ledger, Date.now());
    // 12.7: الرخصة لهوية مكتب الطلب، وبحد أجهزة المكتب
    var seat = VT.seatCheck(S.ledger, rv.req.of, f.md);
    if (!seat.ok) return msg(box, "err", seat.reason);
    var fields = withLimits({ lid: lid, ed: f.ed, ft: f.ft, uu: uu, dk: rv.req.dk, of: rv.req.of || "" }, f);
    VT.issueToken(S.signer, fields).then(function (token) {
      var lic = VT.record(S.ledger, withLimits({
        lid: lid, order: rv.req.order, customer: $("sCustomer").value.trim(), phone: $("sPhone").value.trim(), price: $("sPrice").value.trim(),
        note: $("sNote").value.trim(), uu: uu, ft: f.ft, ed: f.ed, dk: rv.req.dk, pub: rv.req.pub, model: rv.req.model, attest: attestLabel(rv), token: token,
        of: rv.req.of || ""
      }, f), Date.now());
      if (seat.n) msg(box, "info", "هذا الجهاز رقم " + seat.n + (f.md ? " من " + f.md : "") + " لهذا المكتب.");
      saveLedger();
      tokenBox(box, token, lic);
      $("issue").disabled = true;
      $("sLid").value = VT.nextLid(S.ledger, Date.now());
    }).catch(function (e) { msg(box, "err", e.message); });
  });

  // ------------------------------------------------------------------ نقل رخصة
  function renderMoveList() {
    var box = $("mList"), q = $("mFind").value.trim().toLowerCase();
    box.innerHTML = "";
    var list = S.ledger.licenses.filter(function (l) { return l.status === "active" || l.status === "released"; })
      .filter(function (l) { return !q || (l.lid + " " + l.customer + " " + l.order + " " + l.phone).toLowerCase().indexOf(q) >= 0; });
    if (!list.length) return msg(box, "info", S.ledger.licenses.length ? "لا رخصة مطابقة." : "السجل فارغ.");
    list.slice(-50).reverse().forEach(function (l) {
      var b = el("button", "pick" + (S.move.lid === l.lid ? " on" : ""));
      b.textContent = l.lid + " — " + (l.customer || "بلا اسم") + " — " + (l.model || "") + (l.status === "released" ? " (محرَّرة)" : "");
      b.addEventListener("click", function () {
        S.move = { lid: l.lid, receipt: null, review: null };
        $("relText").value = "";
        $("mReqText").value = "";
        $("relOut").innerHTML = $("mReqOut").innerHTML = $("mIssueOut").innerHTML = "";
        renderMoveList();
        moveInfo();
      });
      box.appendChild(b);
    });
  }
  $("mFind").addEventListener("input", renderMoveList);

  function moveAllowed() {
    if (!S.move.lid) return { ok: false, reason: "اختر رخصة" };
    return VT.canTransfer(S.ledger, S.move.lid, Date.now(), !!S.move.receipt);
  }

  function moveInfo() {
    var box = $("mInfo"), l = VT.findLicense(S.ledger, S.move.lid);
    box.innerHTML = "";
    $("mOld").classList.toggle("off", !l);
    $("mNew").classList.toggle("off", !l);
    if (!l) return;
    var n = VT.transfersInYear(S.ledger, l.base, Date.now());
    msg(box, "info", "الرخصة " + l.lid + " • العميل: " + (l.customer || "—") + " • منذ " + fmtDate(l.issuedAt) + " • التحديثات حتى " + fmtUu(l.uu)
      + " • النقلات هذه السنة: " + n + " من 2" + (l.lostAt ? " • سُجّل الفقد في " + fmtDate(l.lostAt) : ""));
    var a = moveAllowed();
    msg(box, a.ok ? "ok" : "warn", a.ok ? "النقل مسموح الآن برقم " + a.nextLid + "." : a.reason);
    updateIssue();
  }

  $("relCheck").addEventListener("click", function () {
    var box = $("relOut"), l = VT.findLicense(S.ledger, S.move.lid);
    box.innerHTML = "";
    if (!l) return msg(box, "err", "اختر الرخصة أولًا.");
    VT.verifyRelease($("relText").value, l.pub).then(function (r) {
      if (!r.ok) return msg(box, "err", r.error);
      if (r.lid !== l.lid) return msg(box, "err", "الإيصال لرخصة أخرى: " + r.lid);
      S.move.receipt = r;
      l.status = "released";
      l.releasedAt = r.at;
      saveLedger();
      msg(box, "ok", "إيصال صحيح: حُرّرت الرخصة من الهاتف القديم في " + fmtDate(r.at) + ".");
      moveInfo();
    });
  });

  $("lost").addEventListener("click", function () {
    var l = VT.findLicense(S.ledger, S.move.lid);
    if (!l) return;
    if (l.lostAt && !confirm("سُجّل الفقد من قبل في " + fmtDate(l.lostAt) + ". إعادة التسجيل تبدأ مهلة 7 أيام من جديد. متابعة؟")) return;
    if (!l.lostAt && !confirm("تسجيل الهاتف مفقودًا؟ يُسمح بنقل الرخصة بعد 7 أيام، ويُضاف رقمها القديم إلى قائمة الإلغاء عند النقل.")) return;
    l.lostAt = Date.now();
    saveLedger();
    moveInfo();
  });

  $("mReqCheck").addEventListener("click", function () {
    var box = $("mReqOut");
    box.innerHTML = "";
    VT.reviewRequest($("mReqText").value, { ledger: S.ledger, status: S.status }).then(function (rv) {
      S.move.review = rv;
      showReview(box, rv);
      $("mOverrideBox").classList.toggle("hidden", !rv.errors.length);
      $("mOverride").checked = false;
      updateIssue();
    });
  });
  $("mOverride").addEventListener("change", updateIssue);

  $("mIssue").addEventListener("click", function () {
    var box = $("mIssueOut"), l = VT.findLicense(S.ledger, S.move.lid), rv = S.move.review, a = moveAllowed();
    box.innerHTML = "";
    if (!a.ok) return msg(box, "err", a.reason);
    VT.issueToken(S.signer, withLimits({ lid: a.nextLid, ed: l.ed, ft: l.ft, uu: l.uu, dk: rv.req.dk, of: rv.req.of || l.of || "" }, l.lim)).then(function (token) {
      var lic = VT.record(S.ledger, withLimits({
        lid: a.nextLid, order: rv.req.order, customer: l.customer, phone: l.phone, price: "", note: "نقل من " + l.lid + (S.move.receipt ? " (بإيصال)" : " (هاتف مفقود)"),
        uu: l.uu, ft: l.ft, ed: l.ed, dk: rv.req.dk, pub: rv.req.pub, model: rv.req.model, attest: attestLabel(rv), token: token,
        of: rv.req.of || l.of || ""
      }, l.lim), Date.now());
      VT.markMoved(S.ledger, l.lid, a.nextLid);
      saveLedger();
      tokenBox(box, token, lic);
      S.move = { lid: "", receipt: null, review: null };
      $("mIssue").disabled = true;
      $("mInfo").innerHTML = "";
      renderMoveList();
      if (S.ledger.revoked.length) msg(box, "info", "أُضيف " + l.lid + " إلى قائمة الإلغاء. إن كان لديك خادم ترخيص فارفع إليه «سجل الخادم» من تبويب السجل.");
    }).catch(function (e) { msg(box, "err", e.message); });
  });

  // ------------------------------------------------------------------ السجل
  var STATUS = { active: "فعّالة", released: "محرَّرة", moved: "منقولة", revoked: "ملغاة" };

  function renderLedger() {
    var L = S.ledger, q = $("lFind").value.trim().toLowerCase();
    var st = $("lStats");
    st.innerHTML = "";
    var sales = L.licenses.filter(function (l) { return l.lid === l.base; }).length;
    var active = L.licenses.filter(function (l) { return l.status === "active"; }).length;
    [[sales, "بيع"], [active, "رخصة فعّالة"], [L.licenses.length - sales, "نقل"], [L.revoked.length, "في قائمة الإلغاء"]].forEach(function (x) {
      var d = el("div", "stat");
      d.appendChild(el("b", null, String(x[0])));
      d.appendChild(el("span", null, x[1]));
      st.appendChild(d);
    });
    var t = $("lTable");
    t.innerHTML = "<tr><th>رقم الرخصة</th><th>العميل</th><th>الطلب</th><th>الإصدار</th><th>التحديثات حتى</th><th>الحالة</th><th>الهاتف</th><th>التصديق</th><th></th></tr>";
    L.licenses.slice().reverse().filter(function (l) {
      return !q || (l.lid + " " + l.customer + " " + l.order + " " + l.phone + " " + l.model).toLowerCase().indexOf(q) >= 0;
    }).forEach(function (l) {
      var tr = document.createElement("tr");
      tr.innerHTML = "<td class='ltr'>" + esc(l.lid) + "</td><td>" + esc(l.customer) + "</td><td class='ltr'>" + esc(l.order) + "</td><td>" + esc(fmtDate(l.issuedAt))
        + "</td><td>" + esc(fmtUu(l.uu)) + "</td><td><span class='chip " + (l.status === "active" ? "ok" : "bad") + "'>" + esc(STATUS[l.status] || l.status)
        + "</span></td><td>" + esc(l.model) + "</td><td>" + esc(l.attest) + "</td><td></td>";
      var b = el("button", "btn sec mini", "الرمز");
      b.addEventListener("click", function () { copy(l.token || "", b); });
      tr.lastChild.appendChild(b);
      t.appendChild(tr);
    });
    if (!L.licenses.length) {
      var tr = document.createElement("tr");
      tr.innerHTML = "<td colspan='9'>لا مبيعات بعد.</td>";
      t.appendChild(tr);
    }
  }
  $("lFind").addEventListener("input", renderLedger);

  function stamp() { var d = new Date(); return d.getFullYear() + String(d.getMonth() + 1).padStart(2, "0") + String(d.getDate()).padStart(2, "0"); }

  function backupLedger() { download("سجل-تراخيص-المحاسب-الذكي-" + stamp() + ".json", JSON.stringify(S.ledger, null, 1)); }

  $("lCsv").addEventListener("click", function () { download("مبيعات-المحاسب-الذكي-" + stamp() + ".csv", VT.ledgerCsv(S.ledger), "text/csv;charset=utf-8"); });
  $("lBackup").addEventListener("click", backupLedger);
  $("lServer").addEventListener("click", function () { download("licenses.json", VT.serverExport(S.ledger)); });
  $("lRevoked").addEventListener("click", function () { download("revoked.json", VT.revocationJson(S.ledger)); });
  $("lImport").addEventListener("change", function (ev) {
    var f = ev.target.files[0];
    ev.target.value = "";
    if (!f) return;
    f.text().then(function (t) {
      var j = JSON.parse(t);
      if (!j || j.v !== 1 || !Array.isArray(j.licenses)) throw new Error("ليس ملف سجل من هذه الأداة");
      var have = {};
      S.ledger.licenses.forEach(function (l) { have[l.lid] = 1; });
      var added = 0;
      j.licenses.forEach(function (l) {
        if (!have[l.lid]) {
          S.ledger.licenses.push(l);
          added++;
        }
      });
      (j.revoked || []).forEach(function (r) { if (S.ledger.revoked.indexOf(r) < 0) S.ledger.revoked.push(r); });
      S.ledger.next = Math.max(S.ledger.next, j.next || 1);
      saveLedger();
      renderLedger();
      alert("أُضيفت " + added + " رخصة من النسخة (الموجودة لم تتغير).");
    }).catch(function (e) { alert("تعذر الاستيراد: " + e.message); });
  });

  keyState();
  statusState();
})();
