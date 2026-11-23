/*
 * أداة البائع لـ«المحاسب الذكي» 8 — المنطق (بلا واجهة). يعمل في المتصفح بلا إنترنت، وفي Node 18+ للاختبار.
 *
 * ما تفعله:
 *  1. تقرأ «طلب التفعيل» الذي ينشئه التطبيق (SA8-REQ:…) وتفحص سلسلة تصديق مفتاح الهاتف:
 *     توقيعات السلسلة حتى جذر Google، ومطابقة المفتاح، والتحدي = رقم الطلب، ومستوى الأمان (TEE/StrongBox)،
 *     واسم الحزمة وبصمة شهادة توقيعها (نسخة معدّلة تُكشف)، وحالة محمّل الإقلاع.
 *  2. تُصدر «رمز الترخيص»: حقول مفتاح=قيمة موقّعة ECDSA P-256 (DER) بمفتاح البائع الخاص — نفس ما يتحقق منه التطبيق.
 *  3. تتحقق من «إيصال التحرير» (SA8-REL:…) الموقّع بمفتاح الهاتف القديم عند نقل الرخصة.
 *  4. سجل المبيعات وقواعد النقل (مرتان في السنة، و7 أيام انتظار إن فُقد الهاتف) وقائمة الإلغاء للخادم.
 * المفتاح الخاص لا يغادر هذا الجهاز: يُحمَّل من ملفه، أو يُحفظ في المتصفح مشفّرًا بعبارة مرور.
 */
(function (root) {
  "use strict";

  var nodeCrypto = null;
  if (typeof module === "object" && module.exports && typeof require === "function") nodeCrypto = require("crypto");
  var subtle = (root.crypto && root.crypto.subtle) || (nodeCrypto && nodeCrypto.webcrypto.subtle);
  var getRandom = function (n) {
    var a = new Uint8Array(n);
    (root.crypto || nodeCrypto.webcrypto).getRandomValues(a);
    return a;
  };

  var PKG = "com.smart.hawala.v8";
  var MAC_PKG = "com.smart.hawala.mac";
  /** بصمة SHA-256 لشهادة توقيع التطبيق 8 (BuildInfo.SIGNING_CERT_SHA256). */
  var APP_CERT_SHA256 = "FD24C2675B88217BECFD42828C39A27CF892393A1770494D969EEE6225BC8686";
  /** المفاتيح العامة المضمّنة في التطبيق (LicenseToken.TRUSTED). */
  var TRUSTED = {
    k1: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8zlFFLS9w6SXiVoxS62RhtEy2q32XDWj96nAFfvRleporJR5ugSQC4a1tjHVNTt7mFDSOPUcYLb+jYS6AR10hg==",
    k2: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEj9ACjhyMfDIK0u2pkPs517eT7AyTC+5TswYFs2mECyytnLgkRQV6TzTEs9Zy3icPNhKqCjjLZj9Ga9xAjghi6Q=="
  };
  /**
   * جذور تصديق المفاتيح من Google (المفتاح العام SubjectPublicKeyInfo). المصدر: https://android.googleapis.com/attestation/root
   * (نسخة منه في github.com/android/keyattestation/roots.json). المطابقة بالمفتاح لا بالشهادة، فالشهادة القديمة (2016)
   * والمجددة (2022) لجذر RSA نفسه تُقبلان معًا.
   */
  var GOOGLE_ROOTS = [
    { name: "Google Hardware Attestation Root (RSA)", spki: "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdSSxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggjnar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGqC4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQoVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+OJtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/EgsTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRiigHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+MRPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9EaDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5UmAGMCAwEAAQ==" },
    { name: "Google Key Attestation CA1 (EC P-384, 2025)", spki: "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEI9ojcU7fPlsFCjxy6IRqzgeOoK0b+YsV9FPQywiyw8EQRTkJ9u3qwfnI4DGoSLlBqClTXJfgfCcZvs60FikNMHnu4fkRzObfgDkU2KNXezT9/RQ+XvNslxPHrHCowhGr" }
  ];
  var KD_OID = "1.3.6.1.4.1.11129.2.1.17";

  // =================================================================================== بايتات ونصوص

  function utf8(s) { return new TextEncoder().encode(s); }
  function fromUtf8(b) { return new TextDecoder("utf-8", { fatal: false }).decode(b); }

  function b64ToBytes(s) {
    s = String(s || "").replace(/\s+/g, "").replace(/-/g, "+").replace(/_/g, "/");
    while (s.length % 4) s += "=";
    var bin = atob(s), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function bytesToB64(b) {
    var s = "";
    for (var i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
    return btoa(s);
  }

  function b64url(b) { return bytesToB64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }

  function hex(b) {
    var s = "";
    for (var i = 0; i < b.length; i++) s += (b[i] < 16 ? "0" : "") + b[i].toString(16);
    return s;
  }

  function eq(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  async function sha256(b) { return new Uint8Array(await subtle.digest("SHA-256", b)); }

  /** بصمة مفتاح الهاتف كما يكتبها التطبيق: Base64url(SHA-256(X.509)) بلا حشو. */
  async function deviceFingerprint(spki) { return b64url(await sha256(spki)); }

  function pemBlocks(text, label) {
    var re = new RegExp("-----BEGIN " + label + "-----([\\s\\S]*?)-----END " + label + "-----", "g"), out = [], m;
    while ((m = re.exec(text))) out.push(b64ToBytes(m[1]));
    return out;
  }

  // =================================================================================== DER

  function der(b, pos) {
    var start = pos;
    if (pos >= b.length) throw new Error("DER: نهاية غير متوقعة");
    var t = b[pos++];
    var cls = t >> 6, cons = (t & 0x20) !== 0, tag = t & 0x1f;
    if (tag === 0x1f) {
      tag = 0;
      var c, guard = 0;
      do {
        c = b[pos++];
        tag = tag * 128 + (c & 0x7f);
        if (++guard > 4) throw new Error("DER: رقم وسم طويل جدًا");
      } while (c & 0x80);
    }
    var len = b[pos++];
    if (len & 0x80) {
      var n = len & 0x7f;
      if (n === 0 || n > 4) throw new Error("DER: طول غير مدعوم");
      len = 0;
      for (var i = 0; i < n; i++) len = len * 256 + b[pos++];
    }
    if (pos + len > b.length) throw new Error("DER: بيانات مبتورة");
    return { cls: cls, cons: cons, tag: tag, start: start, hs: pos, end: pos + len, b: b };
  }

  function kids(n) {
    var out = [], p = n.hs;
    while (p < n.end) {
      var c = der(n.b, p);
      out.push(c);
      p = c.end;
    }
    return out;
  }

  function body(n) { return n.b.subarray(n.hs, n.end); }
  function whole(n) { return n.b.subarray(n.start, n.end); }

  function oid(n) {
    var b = body(n), parts = [], v = 0;
    for (var i = 0; i < b.length; i++) {
      v = v * 128 + (b[i] & 0x7f);
      if (!(b[i] & 0x80)) {
        if (!parts.length) {
          var a = v < 80 ? Math.floor(v / 40) : 2;
          parts.push(a, v - a * 40);
        } else parts.push(v);
        v = 0;
      }
    }
    return parts.join(".");
  }

  function int(n) {
    var b = body(n), v = 0;
    if (b.length > 6) throw new Error("DER: عدد كبير");
    for (var i = 0; i < b.length; i++) v = v * 256 + b[i];
    if (b.length && b[0] & 0x80) v -= Math.pow(2, 8 * b.length);
    return v;
  }

  // =================================================================================== X.509

  function parseCert(bytes) {
    var c = der(bytes, 0);
    if (c.end !== bytes.length) throw new Error("شهادة فيها بيانات زائدة");
    var k = kids(c);
    var tbs = k[0], tk = kids(tbs), i = 0;
    if (tk[0].cls === 2 && tk[0].tag === 0) i = 1;
    var serial = body(tk[i]);
    var spki = tk[i + 5];
    var exts = {}, extCount = {};
    for (var j = i + 6; j < tk.length; j++) {
      if (tk[j].cls !== 2 || tk[j].tag !== 3) continue;
      kids(kids(tk[j])[0]).forEach(function (e) {
        var ek = kids(e), id = oid(ek[0]);
        exts[id] = body(ek[ek.length - 1]);
        extCount[id] = (extCount[id] || 0) + 1;
      });
    }
    var alg = kids(kids(spki)[0]);
    var sigBits = body(k[2]);
    return {
      der: bytes,
      tbs: whole(tbs),
      sigAlg: oid(kids(k[1])[0]),
      sig: sigBits.subarray(1),
      spki: whole(spki),
      keyAlg: oid(alg[0]),
      curve: alg[1] && alg[1].tag === 6 ? oid(alg[1]) : null,
      exts: exts,
      extCount: extCount,
      serial: hex(serial).replace(/^0+(?=.)/, ""),
      subject: whole(tk[i + 4]),
      issuer: whole(tk[i + 2])
    };
  }

  var SIG = {
    "1.2.840.113549.1.1.11": ["RSA", "SHA-256"], "1.2.840.113549.1.1.12": ["RSA", "SHA-384"], "1.2.840.113549.1.1.13": ["RSA", "SHA-512"],
    "1.2.840.10045.4.3.2": ["EC", "SHA-256"], "1.2.840.10045.4.3.3": ["EC", "SHA-384"], "1.2.840.10045.4.3.4": ["EC", "SHA-512"]
  };
  var CURVES = { "1.2.840.10045.3.1.7": ["P-256", 32], "1.3.132.0.34": ["P-384", 48], "1.3.132.0.35": ["P-521", 66] };
  var RSA_OID = "1.2.840.113549.1.1.1", EC_OID = "1.2.840.10045.2.1";

  /** توقيع ECDSA بترميز DER ← r||s بطول ثابت (صيغة WebCrypto). */
  function derToRaw(sig, size) {
    var s = der(sig, 0), k = kids(s);
    if (k.length !== 2) throw new Error("توقيع ECDSA غير صالح");
    var out = new Uint8Array(size * 2);
    k.forEach(function (n, idx) {
      var v = body(n);
      while (v.length > size && v[0] === 0) v = v.subarray(1);
      if (v.length > size) throw new Error("توقيع ECDSA أطول من المنحنى");
      out.set(v, idx * size + (size - v.length));
    });
    return out;
  }

  /** r||s ← DER (التطبيق يتحقق بـ SHA256withECDSA في Java ويتوقع DER). */
  function rawToDer(raw) {
    var size = raw.length / 2, parts = [];
    for (var p = 0; p < 2; p++) {
      var v = raw.subarray(p * size, (p + 1) * size), s = 0;
      while (s < v.length - 1 && v[s] === 0) s++;
      v = v.subarray(s);
      if (v[0] & 0x80) {
        var w = new Uint8Array(v.length + 1);
        w.set(v, 1);
        v = w;
      }
      parts.push(v);
    }
    var len = 2 + parts[0].length + 2 + parts[1].length;
    var hdr = len < 128 ? [0x30, len] : [0x30, 0x81, len];
    var out = new Uint8Array(hdr.length + len), i = 0;
    hdr.forEach(function (x) { out[i++] = x; });
    parts.forEach(function (v) {
      out[i++] = 0x02;
      out[i++] = v.length;
      out.set(v, i);
      i += v.length;
    });
    return out;
  }

  async function verifySig(cert, issuer) {
    var a = SIG[cert.sigAlg];
    if (!a) throw new Error("خوارزمية توقيع غير مدعومة: " + cert.sigAlg);
    if (a[0] === "RSA") {
      if (issuer.keyAlg !== RSA_OID) return false;
      var rk = await subtle.importKey("spki", issuer.spki, { name: "RSASSA-PKCS1-v1_5", hash: a[1] }, false, ["verify"]);
      return subtle.verify("RSASSA-PKCS1-v1_5", rk, cert.sig, cert.tbs);
    }
    if (issuer.keyAlg !== EC_OID || !CURVES[issuer.curve]) return false;
    var cv = CURVES[issuer.curve];
    var ek = await subtle.importKey("spki", issuer.spki, { name: "ECDSA", namedCurve: cv[0] }, false, ["verify"]);
    var raw;
    try {
      raw = derToRaw(cert.sig, cv[1]);
    } catch (e) {
      return false;
    }
    return subtle.verify({ name: "ECDSA", hash: a[1] }, ek, raw, cert.tbs);
  }

  // =================================================================================== وصف المفتاح (Key Attestation)

  var LEVELS = ["برمجي (Software)", "عتاد آمن (TEE)", "StrongBox"];
  var BOOT = ["VERIFIED", "SELF_SIGNED", "UNVERIFIED", "FAILED"];

  function authList(n) {
    var m = {};
    kids(n).forEach(function (e) {
      if (e.cls === 2) m[e.tag] = kids(e)[0];
    });
    return m;
  }

  function parseAppId(node) {
    var inner = der(body(node), 0), k = kids(inner);
    return {
      packages: kids(k[0]).map(function (p) {
        var pk = kids(p);
        return { name: fromUtf8(body(pk[0])), version: pk[1] ? int(pk[1]) : 0 };
      }),
      signatures: kids(k[1]).map(function (x) { return hex(body(x)).toUpperCase(); })
    };
  }

  function parseRoot(node) {
    var k = kids(node);
    return {
      verifiedBootKey: b64url(body(k[0])),
      deviceLocked: body(k[1])[0] !== 0,
      verifiedBootState: BOOT[int(k[2])] || String(int(k[2]))
    };
  }

  function parseKeyDescription(bytes) {
    var k = kids(der(bytes, 0));
    var sw = authList(k[6]), hw = authList(k[7]);
    var app = sw[709] || hw[709];
    var rot = hw[704] || sw[704];
    return {
      attestationVersion: int(k[0]),
      securityLevel: int(k[1]),
      keymasterVersion: int(k[2]),
      keymasterSecurityLevel: int(k[3]),
      challenge: body(k[4]),
      appId: app ? parseAppId(app) : null,
      rootOfTrust: rot ? parseRoot(rot) : null,
      origin: hw[702] ? int(hw[702]) : (sw[702] ? int(sw[702]) : null)
    };
  }

  /**
   * يتحقق من سلسلة شهادات التصديق (الورقة أولًا). opts.status: ملف حالة الإلغاء من Google (اختياري)،
   * opts.extraRoots: مفاتيح جذور إضافية (Base64).
   */
  async function verifyChain(chainB64, opts) {
    opts = opts || {};
    var res = { ok: false, sigOk: true, rootTrusted: false, rootName: "", problems: [], kd: null, leafSpki: null, revoked: [] };
    if (!chainB64 || !chainB64.length) {
      res.problems.push("لا توجد سلسلة تصديق");
      return res;
    }
    var certs;
    try {
      certs = chainB64.map(function (c) { return parseCert(typeof c === "string" ? b64ToBytes(c) : c); });
    } catch (e) {
      res.sigOk = false;
      res.problems.push("شهادة غير مقروءة: " + e.message);
      return res;
    }
    res.leafSpki = certs[0].spki;
    for (var i = 0; i < certs.length; i++) {
      var issuer = certs[i + 1] || certs[i];
      var good = false;
      try {
        good = await verifySig(certs[i], issuer);
      } catch (e) {
        res.problems.push(e.message);
      }
      if (!good) {
        res.sigOk = false;
        res.problems.push("توقيع الشهادة " + (i + 1) + " من " + certs.length + " لا يتحقق");
      }
      if (i > 0 && certs[i].exts[KD_OID]) {
        res.sigOk = false;
        res.problems.push("شهادة وسيطة تحمل امتداد التصديق (سلسلة مركّبة من هاتف آخر)");
      }
    }
    var top = bytesToB64(certs[certs.length - 1].spki);
    GOOGLE_ROOTS.concat((opts.extraRoots || []).map(function (s) { return { name: "جذر مضاف", spki: s }; })).forEach(function (r) {
      if (r.spki === top) {
        res.rootTrusted = true;
        res.rootName = r.name;
      }
    });
    var st = opts.status && opts.status.entries;
    if (st) certs.forEach(function (c) {
      var e = st[c.serial] || st[c.serial.toLowerCase()];
      if (e && /REVOKED|SUSPENDED/i.test(e.status || "REVOKED")) res.revoked.push(c.serial + " (" + (e.reason || e.status || "") + ")");
    });
    if (!certs[0].exts[KD_OID]) res.problems.push("لا يوجد امتداد التصديق في شهادة المفتاح");
    else if (certs[0].extCount[KD_OID] > 1) {
      res.sigOk = false;
      res.problems.push("امتداد التصديق مكرر");
    } else {
      try {
        res.kd = parseKeyDescription(certs[0].exts[KD_OID]);
      } catch (e) {
        res.problems.push("امتداد التصديق غير مقروء: " + e.message);
      }
    }
    res.ok = res.sigOk && res.rootTrusted && !!res.kd && !res.revoked.length;
    return res;
  }

  // =================================================================================== طلب التفعيل

  /**
   * يستخرج النص بعد البادئة من رسالة كاملة (واتساب يضيف أسطرًا ونصوصًا قبله وبعده): يجرّب الأطول أولًا
   * ثم يحذف المقاطع الأخيرة المفصولة بمسافات حتى ينجح check.
   */
  function extract(text, prefix, chars, check) {
    var s = String(text || "");
    var at = s.indexOf(prefix);
    if (at < 0) return null;
    var m = new RegExp("^[" + chars + "\\s]+").exec(s.substring(at + prefix.length));
    if (!m) return null;
    var chunks = m[0].split(/\s+/).filter(Boolean);
    for (var n = chunks.length; n > 0; n--) {
      var cand = chunks.slice(0, n).join("");
      try {
        var v = check(cand);
        if (v) return v;
      } catch (e) {
        // نجرب أقصر
      }
    }
    return null;
  }

  /** 11.5: الطلب المضغوط (SA8-REQZ:): JSON مضغوط deflate-raw ثم Base64url — أقصر بنحو الثلث. */
  async function inflateRaw(bytes) {
    var ds = new DecompressionStream("deflate-raw");
    var stream = new Blob([bytes]).stream().pipeThrough(ds);
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }

  async function parseRequestAny(text) {
    var s = String(text || "");
    var at = s.indexOf("SA8-REQZ:");
    if (at < 0) return parseRequest(s);
    var m = /^[A-Za-z0-9_\-\s]+/.exec(s.substring(at + 9));
    var chunks = m ? m[0].split(/\s+/).filter(Boolean) : [];
    for (var n = chunks.length; n > 0; n--) {
      try {
        var o = JSON.parse(fromUtf8(await inflateRaw(b64ToBytes(chunks.slice(0, n).join("")))));
        if (o && typeof o === "object") {
          if (o.t !== "sa-activate") throw new Error("نوع الطلب غير معروف");
          return o;
        }
      } catch (e) {
        if (e && e.message === "نوع الطلب غير معروف") throw e;
        // نجرب أقصر
      }
    }
    throw new Error("الطلب مبتور أو غير مقروء: انسخه كاملًا من رسالة العميل، أو افتح ملف الطلب");
  }

  function parseRequest(text) {
    if (String(text || "").indexOf("SA8-REQ:") < 0) throw new Error("هذا ليس طلب تفعيل من «المحاسب الذكي» 8 (يبدأ بـ SA8-REQ:)");
    var j = extract(text, "SA8-REQ:", "A-Za-z0-9_\\-", function (c) {
      var o = JSON.parse(fromUtf8(b64ToBytes(c)));
      return o && typeof o === "object" ? o : null;
    });
    if (!j) throw new Error("الطلب مبتور أو غير مقروء: انسخه كاملًا من رسالة العميل");
    if (j.t !== "sa-activate") throw new Error("نوع الطلب غير معروف");
    return j;
  }

  /**
   * يفحص الطلب: errors تمنع الإصدار (إلا بتجاوز صريح)، warnings تُعرض للبائع، info للعلم.
   * ledger اختياري: لتنبيه «هذا الهاتف لديه رخصة» و«رقم الطلب استُعمل».
   */
  async function reviewRequest(text, opts) {
    opts = opts || {};
    var out = { req: null, errors: [], warnings: [], info: [], attest: null, level: -1, pub: null };
    var req;
    try {
      req = await parseRequestAny(text);
    } catch (e) {
      out.errors.push(e.message || "الطلب غير مقروء");
      return out;
    }
    out.req = req;
    var pub = b64ToBytes(req.pub || "");
    out.pub = pub;
    if (!pub.length) out.errors.push("الطلب بلا مفتاح عام");
    else if ((await deviceFingerprint(pub)) !== req.dk) out.errors.push("بصمة الهاتف في الطلب لا تطابق مفتاحه (الطلب معدَّل)");
    else {
      try {
        await subtle.importKey("spki", pub, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      } catch (e) {
        out.errors.push("مفتاح الهاتف ليس ECDSA P-256");
      }
    }
    if (req.platform === "mac") {
      // نسخة الماك: لا تصديق عتادي في الماك. المفتاح في «سلسلة المفاتيح» للمستخدم، والطلب يثبت حيازته بتوقيع رقم الطلب.
      out.mac = true;
      if (req.pkg !== MAC_PKG) out.errors.push("اسم البرنامج " + req.pkg + " وليس " + MAC_PKG + " (برنامج آخر أو نسخة معدّلة)");
      if (!req.order) out.errors.push("الطلب بلا رقم طلب");
      var want = "v=1;t=request;order=" + req.order + ";dk=" + req.dk;
      if (req.proof !== want || !req.proofSig) out.errors.push("الطلب بلا إثبات حيازة المفتاح أو الإثبات لا يطابق رقم الطلب");
      else if (pub.length) {
        try {
          var mk = await subtle.importKey("spki", pub, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
          var ok = await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, mk, derToRaw(b64ToBytes(req.proofSig), 32), utf8(req.proof));
          if (!ok) out.errors.push("توقيع إثبات الحيازة لا يتحقق (الطلب معدَّل)");
          else out.info.push("الطلب موقّع بمفتاح هذا الماك نفسه.");
        } catch (e) {
          out.errors.push("تعذر التحقق من توقيع الطلب");
        }
      }
      out.warnings.push("طلب من Mac: المفتاح محفوظ في «سلسلة مفاتيح» الماك (لا يوجد تصديق عتادي كما في الهاتف). يكفي لربط الرخصة بهذا الماك، لكنه أقل صرامة من الهاتف.");
      if (req.hw) out.info.push("معرّف الجهاز: " + String(req.hw).substring(0, 12) + "…");
    } else {
      if (req.pkg !== PKG) out.errors.push("اسم الحزمة " + req.pkg + " وليس " + PKG + " (تطبيق آخر أو نسخة معدّلة)");
      if (!req.order) out.errors.push("الطلب بلا رقم طلب");
    }
    if (req.platform === "mac") {
      // لا سلسلة
    } else if (!req.chain || !req.chain.length) {
      out.warnings.push("لا توجد سلسلة تصديق: لا يمكن التأكد أن المفتاح محفوظ في عتاد آمن (محاكٍ أو هاتف لا يدعم التصديق).");
    } else {
      var a = await verifyChain(req.chain, opts);
      out.attest = a;
      if (!a.sigOk) out.errors.push("سلسلة التصديق لا تتحقق: " + a.problems.join("؛ "));
      else if (!a.rootTrusted) out.warnings.push("السلسلة لا تنتهي بجذر Google: محاكٍ أو هاتف بتصديق غير معتمد.");
      else out.info.push("السلسلة موقّعة حتى " + a.rootName + ".");
      if (a.revoked.length) out.errors.push("شهادة في السلسلة ملغاة لدى Google: " + a.revoked.join("، "));
      if (a.leafSpki && pub.length && !eq(a.leafSpki, pub)) out.errors.push("المفتاح المصدَّق ليس مفتاح الطلب");
      var kd = a.kd;
      if (!kd) out.warnings.push("لا يمكن قراءة وصف المفتاح المصدَّق.");
      else {
        out.level = kd.securityLevel;
        var ch = fromUtf8(kd.challenge);
        if (ch !== "SA8|" + req.order) out.errors.push("التحدي في التصديق «" + ch + "» لا يطابق رقم الطلب (طلب قديم أو منسوخ من هاتف آخر).");
        if (kd.securityLevel === 0) out.warnings.push("المفتاح برمجي لا عتادي: يمكن نسخه من هاتف مروّت.");
        else out.info.push("مستوى الحماية: " + (LEVELS[kd.securityLevel] || kd.securityLevel) + ".");
        if (!kd.appId) out.warnings.push("التصديق بلا هوية التطبيق.");
        else {
          var names = kd.appId.packages.map(function (p) { return p.name; });
          if (names.indexOf(PKG) < 0) out.errors.push("المفتاح أنشأه تطبيق آخر: " + names.join("، "));
          if (kd.appId.signatures.indexOf(APP_CERT_SHA256) < 0) out.errors.push("التطبيق الذي أنشأ الطلب ليس موقّعًا بشهادتك (نسخة معدّلة أو معاد توقيعها).");
          else out.info.push("التطبيق أصلي (بصمة شهادة التوقيع مطابقة).");
        }
        if (kd.rootOfTrust && (!kd.rootOfTrust.deviceLocked || kd.rootOfTrust.verifiedBootState !== "VERIFIED")) {
          out.warnings.push("محمّل الإقلاع مفتوح أو النظام معدَّل (" + kd.rootOfTrust.verifiedBootState + "): الهاتف أقل أمانًا.");
        }
      }
    }
    // 12.7: هوية المكتب، وسجل محاولات استخدام الرخصة لمكتب آخر (مختوم بمفتاح الجهاز نفسه)
    if (req.of) {
      out.office = { of: req.of, active: opts.ledger ? officeSeats(opts.ledger, req.of) : 0 };
      out.info.push("هوية المكتب: " + req.of + " • أجهزة مرخّصة لهذا المكتب الآن: " + out.office.active + ".");
    } else out.warnings.push("الطلب من إصدار أقدم من 12.7: الرخصة لن تُربط بمكتب (تعمل على أي بيانات).");
    if (req.mis && req.mis.length) {
      var sigOk = false;
      try {
        var mk2 = await subtle.importKey("spki", pub, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
        sigOk = await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, mk2, derToRaw(b64ToBytes(req.misSig || ""), 32), utf8(misuseBody(req.mis)));
      } catch (e) {
        sigOk = false;
      }
      out.misuse = { list: req.mis, signed: sigOk };
      var days = req.mis.map(function (x) { return new Date(x.at).toISOString().substring(0, 10) + " (رخصة " + x.lid + " على مكتب " + x.saw + ")"; });
      out.warnings.push("⚠ محاولة غش محتملة: حاول هذا الجهاز استخدام رخصة على بيانات مكتب آخر " + req.mis.length + " مرة: " + days.join("، ") + "."
        + (sigOk ? " السجل مختوم بمفتاح الجهاز وسليم." : " تنبيه: ختم السجل لا يتحقق (عُدّل السجل)."));
    }
    var L = opts.ledger;
    if (L && req.dk) {
      L.licenses.forEach(function (l) {
        if (l.dk === req.dk && l.status === "active") out.warnings.push("هذا الهاتف لديه رخصة فعّالة: " + l.lid + ".");
        if (l.order === req.order && l.status !== "released") out.warnings.push("رقم الطلب " + req.order + " استُعمل في الرخصة " + l.lid + ".");
      });
    }
    return out;
  }

  // =================================================================================== المفاتيح والرمز

  async function importPrivatePem(pem) {
    var blocks = pemBlocks(pem, "PRIVATE KEY");
    if (!blocks.length) throw new Error("الملف ليس مفتاحًا خاصًا بصيغة PEM (BEGIN PRIVATE KEY)");
    return importPkcs8(blocks[0]);
  }

  async function importPkcs8(pkcs8) {
    var key = await subtle.importKey("pkcs8", pkcs8, { name: "ECDSA", namedCurve: "P-256" }, true, ["sign"]);
    var jwk = await subtle.exportKey("jwk", key);
    var pub = await subtle.importKey("jwk", { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y, ext: true }, { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"]);
    var spki = bytesToB64(new Uint8Array(await subtle.exportKey("spki", pub)));
    var kid = null;
    Object.keys(TRUSTED).forEach(function (k) { if (TRUSTED[k] === spki) kid = k; });
    return { key: key, pkcs8: new Uint8Array(pkcs8), spki: spki, kid: kid };
  }

  function clean(v, what) {
    v = String(v == null ? "" : v).trim();
    if (/[;=\s]/.test(v)) throw new Error(what + " لا يجوز أن يحوي «;» أو «=» أو مسافات");
    return v;
  }

  /** حقول الرمز كما يقرؤها التطبيق (LicenseToken + Entitlements). */
  function payloadOf(f, kid) {
    if (!/^\d{8}$/.test(String(f.uu))) throw new Error("تاريخ انتهاء التحديثات يجب أن يكون بصيغة yyyymmdd");
    if (!/^[A-Za-z0-9_-]{43}$/.test(f.dk || "")) throw new Error("بصمة الهاتف غير صالحة");
    var p = ["v=1", "lid=" + clean(f.lid, "رقم الرخصة"), "ed=" + clean(f.ed || "pro", "الإصدار"), "ft=" + clean(f.ft || "all", "الميزات"),
      "uu=" + f.uu, "dk=" + f.dk, "kid=" + clean(kid, "معرّف المفتاح")];
    if (!f.lid) throw new Error("رقم الرخصة مطلوب");
    // 12.6: كل حد رقمي يكتبه البائع لهذا الزبون (فارغ = لا يُكتب)
    NUM_KEYS.forEach(function (k) {
      if (f[k] === undefined || f[k] === null || String(f[k]).trim() === "") return;
      var n = parseInt(f[k], 10);
      if (!(n > 0) || n > 100000000) throw new Error("الحد «" + k + "» يجب أن يكون رقمًا أكبر من صفر");
      p.push(k + "=" + n);
    });
    if (f.ex) {
      if (!/^\d{8}$/.test(String(f.ex))) throw new Error("تاريخ انتهاء الاشتراك يجب أن يكون بصيغة yyyymmdd");
      p.push("ex=" + f.ex);
    }
    // 12.7: هوية المكتب (من طلب الجهاز): الرمز يعمل على بيانات هذا المكتب وحده
    if (f.of) {
      if (!/^OF-[0-9A-F]{18}$/.test(String(f.of))) throw new Error("هوية المكتب في الطلب غير صالحة");
      p.push("of=" + f.of);
    }
    return p.join(";");
  }

  /** 12.6: الحدود الرقمية في الرمز: المكاتب، الحركات، المستخدمون، الصناديق، العملاء، رسائل واتساب يوميًا، ساعات النسخ، النسخ كل كم حركة. */
  var NUM_KEYS = ["mo", "mt", "mu", "mb", "mc", "mw", "bh", "bn", "tc", "tg", "md"];

  /**
   * 12.6: بنود الرخصة كما يراها البائع. on=false ← حد المجانية؛ on=true ورقم ← الرقم؛ on=true بلا رقم ← بلا حد.
   * free: حد المجانية في التطبيق (يُكتب صراحة للبنود التي تكون مفتوحة لكل رخصة مدفوعة إن لم يُكتب رقم).
   */
  var LIMITS = [
    { k: "offices", key: "mo", flag: "offices", free: 15, explicitFree: false, label: "المكاتب" },
    { k: "tx", key: "mt", flag: "tx", free: 1500, explicitFree: false, label: "الحركات لكل مكتب" },
    { k: "users", key: "mu", flag: "users", free: 1, explicitFree: false, label: "المستخدمون" },
    { k: "boxes", key: "mb", flag: "boxes", free: 1, explicitFree: true, label: "الصناديق" },
    { k: "clients", key: "mc", flag: "clients", free: 25, explicitFree: true, label: "حسابات العملاء" },
    { k: "wa", key: "mw", flag: null, free: 20, explicitFree: true, label: "استخراج رسائل واتساب يوميًا (ومعه الالتقاط والتجميع والقص)" }
  ];
  var SWITCHES = [
    { k: "export", label: "تصدير بلا علامة «نسخة مجانية»" },
    { k: "backup", label: "نسخ تلقائي أكثر (افتراضيًا كل 6 ساعات أو 25 حركة)" },
    { k: "ai", label: "القراءة الذكية السحابية المدفوعة الخاصة (ذكاء اصطناعي عبر الإنترنت)" },
    { k: "sync", label: "المزامنة بين الأجهزة" }
  ];

  /** 12.6: ثلاثة قوالب افتراضية يعدّلها البائع أو يحفظ غيرها. */
  var DEFAULT_TEMPLATES = [
    { name: "أساسية", lim: { offices: { on: true, n: 30 }, tx: { on: true, n: "" }, users: { on: true, n: 2 }, boxes: { on: true, n: 1 }, clients: { on: true, n: 100 }, wa: { on: true, n: "" } },
      sw: { export: true, backup: true, ai: false, sync: false }, bh: "", bn: "", tc: 14, tg: 14, md: "" },
    { name: "احترافية", lim: { offices: { on: true, n: "" }, tx: { on: true, n: "" }, users: { on: true, n: 5 }, boxes: { on: true, n: 3 }, clients: { on: true, n: "" }, wa: { on: true, n: "" } },
      sw: { export: true, backup: true, ai: true, sync: true }, bh: "", bn: "", tc: 14, tg: 14, md: "" },
    { name: "كاملة", lim: { offices: { on: true, n: "" }, tx: { on: true, n: "" }, users: { on: true, n: "" }, boxes: { on: true, n: "" }, clients: { on: true, n: "" }, wa: { on: true, n: "" } },
      sw: { export: true, backup: true, ai: true, sync: true }, bh: "", bn: "", tc: 14, tg: 14, md: "" }
  ];

  /** 12.6: من القالب (أو بنود رخصة) إلى حقول الرمز: ft و ed والحدود. */
  function fieldsOf(t) {
    var ft = [], f = { ed: String(t.name || "custom").trim().replace(/[;=\s]+/g, "_") || "custom" };
    LIMITS.forEach(function (L) {
      var v = (t.lim || {})[L.k] || { on: false, n: "" };
      var n = String(v.n == null ? "" : v.n).trim();
      if (v.on) {
        if (L.flag) ft.push(L.flag);
        if (n) {
          if (!(parseInt(n, 10) > 0)) throw new Error("حد «" + L.label + "» يجب أن يكون رقمًا أكبر من صفر أو فارغًا (بلا حد)");
          f[L.key] = parseInt(n, 10);
        }
      } else if (L.explicitFree) f[L.key] = L.free;
    });
    SWITCHES.forEach(function (w) { if ((t.sw || {})[w.k]) ft.push(w.k); });
    if (!ft.length) throw new Error("اختر بندًا واحدًا على الأقل");
    if (t.bh) f.bh = parseInt(t.bh, 10);
    if (t.bn) f.bn = parseInt(t.bn, 10);
    // 12.7: تأكيد الوقت من الإنترنت كل tc يوم (ومهلة tg)، وعدد أجهزة المكتب md
    ["tc", "tg", "md"].forEach(function (k) {
      var v = String(t[k] == null ? "" : t[k]).trim();
      if (!v) return;
      if (!(parseInt(v, 10) > 0)) throw new Error("الحقل «" + k + "» يجب أن يكون رقمًا أكبر من صفر أو فارغًا");
      f[k] = parseInt(v, 10);
    });
    if (t.ex) f.ex = t.ex;
    f.ft = ft.join(",");
    return f;
  }

  /** 12.6: وصف مختصر لبنود رخصة للسجل والرسالة. */
  function describe(f) {
    var out = [];
    LIMITS.forEach(function (L) {
      var n = f[L.key];
      var on = L.flag ? ("," + (f.ft || "") + ",").indexOf("," + L.flag + ",") >= 0 || ("," + (f.ft || "") + ",").indexOf(",all,") >= 0 : !n || n !== L.free;
      if (n) out.push(L.label + ": " + n);
      else if (on) out.push(L.label + ": بلا حد");
    });
    SWITCHES.forEach(function (w) { if (("," + (f.ft || "") + ",").indexOf("," + w.k + ",") >= 0 || f.ft === "all") out.push(w.label); });
    if (f.ex) out.push("الاشتراك حتى " + String(f.ex).replace(/^(\d{4})(\d{2})(\d{2})$/, "$1/$2/$3"));
    if (f.tc) out.push("تأكيد الوقت من الإنترنت كل " + f.tc + " يوم" + (f.tg ? " (ومهلة " + f.tg + " يوم)" : ""));
    if (f.md) out.push("أجهزة المكتب: " + f.md);
    return out;
  }

  async function issueToken(signer, f) {
    if (!signer || !signer.kid) throw new Error("حمّل مفتاح البائع (k1 أو k2) أولًا");
    var payload = utf8(payloadOf(f, signer.kid));
    var raw = new Uint8Array(await subtle.sign({ name: "ECDSA", hash: "SHA-256" }, signer.key, payload));
    return b64url(payload) + "." + b64url(rawToDer(raw));
  }

  function parseFields(s) {
    var m = {};
    s.split(";").forEach(function (kv) {
      var i = kv.indexOf("=");
      if (i > 0) {
        if (Object.prototype.hasOwnProperty.call(m, kv.substring(0, i))) throw new Error("حقل مكرر");
        m[kv.substring(0, i)] = kv.substring(i + 1);
      }
    });
    return m;
  }

  /** تحقق مطابق لما يفعله التطبيق (للعرض والاختبار). يرجع الحقول أو null. */
  async function verifyToken(token, deviceSpki, trusted) {
    try {
      trusted = trusted || TRUSTED;
      var t = String(token).replace(/\s+/g, "");
      var dot = t.indexOf(".");
      if (dot < 1 || dot !== t.lastIndexOf(".") || dot === t.length - 1) return null;
      var payload = b64ToBytes(t.substring(0, dot)), sig = b64ToBytes(t.substring(dot + 1));
      var f = parseFields(fromUtf8(payload));
      if (f.v !== "1" || !trusted[f.kid] || !f.lid) return null;
      var pk = await subtle.importKey("spki", b64ToBytes(trusted[f.kid]), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      if (!(await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pk, derToRaw(sig, 32), payload))) return null;
      if (deviceSpki && f.dk !== (await deviceFingerprint(deviceSpki))) return null;
      return f;
    } catch (e) {
      return null;
    }
  }

  // =================================================================================== إيصال التحرير

  /** يتحقق من «SA8-REL:» بمفتاح الهاتف القديم المحفوظ في السجل. */
  async function verifyRelease(text, pubB64) {
    if (String(text || "").indexOf("SA8-REL:") < 0) return { ok: false, error: "هذا ليس إيصال تحرير (يبدأ بـ SA8-REL:)" };
    var t = extract(text, "SA8-REL:", "A-Za-z0-9_\\-.", function (c) {
      var d = c.indexOf(".");
      if (d < 1 || d !== c.lastIndexOf(".") || d === c.length - 1) return null;
      parseFields(fromUtf8(b64ToBytes(c.substring(0, d))));
      der(b64ToBytes(c.substring(d + 1)), 0);
      return c;
    });
    if (!t) return { ok: false, error: "الإيصال مبتور أو غير مقروء" };
    var dot = t.indexOf(".");
    try {
      var bodyB = b64ToBytes(t.substring(0, dot)), sig = b64ToBytes(t.substring(dot + 1));
      var f = parseFields(fromUtf8(bodyB));
      var pub = b64ToBytes(pubB64);
      var pk = await subtle.importKey("spki", pub, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      var good = await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pk, derToRaw(sig, 32), bodyB);
      if (!good) return { ok: false, error: "توقيع الإيصال لا يطابق مفتاح الهاتف المسجّل لهذه الرخصة" };
      if (f.v !== "1" || f.t !== "release") return { ok: false, error: "صيغة الإيصال غير معروفة" };
      if (f.dk !== (await deviceFingerprint(pub))) return { ok: false, error: "الإيصال من هاتف آخر" };
      return { ok: true, lid: f.lid, dk: f.dk, at: Number(f.at), nonce: f.n };
    } catch (e) {
      return { ok: false, error: "الإيصال غير مقروء" };
    }
  }

  // =================================================================================== حفظ المفتاح مشفّرًا

  async function passKey(pass, salt) {
    var base = await subtle.importKey("raw", utf8(pass), "PBKDF2", false, ["deriveKey"]);
    return subtle.deriveKey({ name: "PBKDF2", salt: salt, iterations: 310000, hash: "SHA-256" }, base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
  }

  async function sealKey(pkcs8, pass) {
    if (!pass || pass.length < 10) throw new Error("عبارة المرور 10 أحرف على الأقل");
    var salt = getRandom(16), iv = getRandom(12);
    var ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv: iv }, await passKey(pass, salt), pkcs8));
    return JSON.stringify({ v: 1, salt: bytesToB64(salt), iv: bytesToB64(iv), ct: bytesToB64(ct) });
  }

  async function openKey(sealed, pass) {
    var j = JSON.parse(sealed);
    try {
      var pt = await subtle.decrypt({ name: "AES-GCM", iv: b64ToBytes(j.iv) }, await passKey(pass, b64ToBytes(j.salt)), b64ToBytes(j.ct));
      return importPkcs8(new Uint8Array(pt));
    } catch (e) {
      throw new Error("عبارة المرور غير صحيحة");
    }
  }

  // =================================================================================== سجل المبيعات وقواعد النقل

  var DAY = 86400000;

  /** 12.7: نص ختم سجل المحاولات كما يكتبه التطبيق (LicenseGuard.misuseBody). */
  function misuseBody(log) {
    var b = "SA-MISUSE-1";
    (log || []).forEach(function (x) { if (x) b += "|" + Number(x.at || 0) + "," + (x.lid || "") + "," + (x.of || "") + "," + (x.saw || ""); });
    return b;
  }

  /** 12.7: عدد الرخص الفعّالة لهوية مكتب واحدة (أجهزة المكتب). */
  function officeSeats(L, of) {
    return L.licenses.filter(function (l) { return l.of === of && l.status === "active"; }).length;
  }

  /** 12.7: هل يُسمح بجهاز آخر لهذا المكتب؟ md = عدد الأجهزة المسموح (فارغ = بلا حد). */
  function seatCheck(L, of, md) {
    if (!of || !md) return { ok: true };
    var n = officeSeats(L, of);
    if (n >= md) return { ok: false, reason: "لهذا المكتب " + n + " أجهزة مرخّصة من " + md + ". زد «عدد أجهزة المكتب» أو انقل رخصة جهاز قديم." };
    return { ok: true, n: n + 1 };
  }

  function newLedger() { return { v: 1, next: 1, licenses: [], revoked: [] }; }

  function pad(n, w) { n = String(n); while (n.length < w) n = "0" + n; return n; }

  function nextLid(L, now) { return "SA-" + new Date(now).getFullYear() + "-" + pad(L.next, 4); }

  function ymd(d) { return d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate(); }

  /** التحديثات: سنة من اليوم افتراضيًا. */
  function defaultUu(now, months) {
    var d = new Date(now);
    d.setMonth(d.getMonth() + (months || 12));
    return ymd(d);
  }

  function findLicense(L, lid) {
    for (var i = 0; i < L.licenses.length; i++) if (L.licenses[i].lid === lid) return L.licenses[i];
    return null;
  }

  /** الرخصة الأصلية لرقم منقول (SA-2026-0007-T2 ← SA-2026-0007). */
  function baseLid(lid) { return String(lid).replace(/-T\d+$/, ""); }

  /** يسجّل بيعًا أو نقلًا بعد الإصدار. */
  function record(L, e, now) {
    var lic = {
      lid: e.lid, base: baseLid(e.lid), order: e.order || "", customer: e.customer || "", phone: e.phone || "", price: e.price || "", note: e.note || "",
      issuedAt: now, uu: e.uu, ft: e.ft || "all", ed: e.ed || "pro", dk: e.dk, pub: e.pub, model: e.model || "", attest: e.attest || "",
      token: e.token, status: "active", lostAt: 0, lim: limitsOf(e), tpl: e.tpl || null, history: [], of: e.of || ""
    };
    if (lic.lid === lic.base) L.next++;
    L.licenses.push(lic);
    return lic;
  }

  /** 12.6: الحدود الرقمية وتاريخ الانتهاء من حقول (لتُحفظ في السجل وتنتقل مع الرخصة عند النقل والتعديل). */
  function limitsOf(e) {
    var o = {};
    NUM_KEYS.forEach(function (k) { if (e[k]) o[k] = parseInt(e[k], 10); });
    if (e.ex) o.ex = String(e.ex);
    return o;
  }

  /**
   * 12.6: تعديل رخصة قائمة (مستخدم إضافي، مكاتب أكثر، تجديد…): رمز جديد لنفس الهاتف ونفس رقم الرخصة يحل محل القديم في التطبيق.
   * القديم يبقى في سجل الرخصة (history).
   */
  function amend(L, lid, e, now) {
    var lic = findLicense(L, lid);
    if (!lic) throw new Error("الرخصة غير موجودة في السجل");
    if (lic.status !== "active") throw new Error("الرخصة ليست فعّالة (" + lic.status + ")");
    lic.history = lic.history || [];
    lic.history.push({ at: now, token: lic.token, ft: lic.ft, ed: lic.ed, uu: lic.uu, lim: lic.lim || {}, note: e.note || "" });
    lic.token = e.token;
    lic.ft = e.ft;
    lic.ed = e.ed;
    lic.uu = e.uu;
    lic.lim = limitsOf(e);
    lic.tpl = e.tpl || lic.tpl || null;
    lic.amendedAt = now;
    return lic;
  }

  /** النقلات خلال 365 يومًا للرخصة الأصلية. */
  function transfersInYear(L, base, now) {
    return L.licenses.filter(function (l) { return l.base === base && l.lid !== base && now - l.issuedAt < 365 * DAY; }).length;
  }

  /**
   * هل يجوز النقل الآن؟ بإيصال تحرير صحيح: فورًا. بلا إيصال (هاتف مفقود): بعد 7 أيام من تسجيل الفقد.
   * والحد مرتان في السنة في الحالتين.
   */
  function canTransfer(L, lid, now, withReceipt) {
    var lic = findLicense(L, lid);
    if (!lic) return { ok: false, reason: "الرخصة غير موجودة في السجل" };
    if (lic.status !== "active" && lic.status !== "released") return { ok: false, reason: "الرخصة " + lid + " حالتها: " + lic.status };
    var n = transfersInYear(L, lic.base, now);
    if (n >= 2) return { ok: false, reason: "نُقلت هذه الرخصة مرتين خلال السنة الأخيرة (الحد مرتان)." };
    if (!withReceipt && lic.status !== "released") {
      if (!lic.lostAt) return { ok: false, reason: "سجّل «الهاتف مفقود» أولًا؛ النقل بعد 7 أيام." };
      var left = 7 * DAY - (now - lic.lostAt);
      if (left > 0) return { ok: false, reason: "النقل بلا إيصال متاح بعد " + Math.ceil(left / DAY) + " يوم." };
    }
    return { ok: true, nextLid: lic.base + "-T" + (L.licenses.filter(function (l) { return l.base === lic.base && l.lid !== lic.base; }).length + 1) };
  }

  /** بعد إصدار الرخصة المنقولة: القديمة «منقولة» وتُضاف إلى قائمة الإلغاء (للخادم إن وُجد). */
  function markMoved(L, oldLid, newLid) {
    var lic = findLicense(L, oldLid);
    if (lic) {
      lic.status = "moved";
      lic.movedTo = newLid;
    }
    if (L.revoked.indexOf(oldLid) < 0) L.revoked.push(oldLid);
  }

  function revocationJson(L) { return JSON.stringify({ revoked: L.revoked.slice().sort(), updatedAt: new Date().toISOString() }, null, 1); }

  function csvCell(v) {
    v = String(v == null ? "" : v);
    if (/^[=+\-@\t\r]/.test(v)) v = "'" + v;
    return /[",\n;]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
  }

  function ledgerCsv(L) {
    var rows = [["رقم الرخصة", "الأصلية", "رقم الطلب", "العميل", "الهاتف", "السعر", "تاريخ الإصدار", "التحديثات حتى", "الميزات", "الحالة", "الجهاز", "التصديق", "ملاحظة"]];
    L.licenses.forEach(function (l) {
      rows.push([l.lid, l.base, l.order, l.customer, l.phone, l.price, new Date(l.issuedAt).toISOString().substring(0, 10), l.uu, l.ft, l.status, l.model, l.attest, l.note]);
    });
    return "﻿" + rows.map(function (r) { return r.map(csvCell).join(","); }).join("\r\n");
  }

  /** سجل خادم الترخيص: ما يحتاجه للتحقق من فحص الهاتف (رقم الرخصة ← مفتاح الهاتف العام) والإلغاءات. */
  function serverExport(L) {
    var lic = {};
    L.licenses.forEach(function (l) { lic[l.lid] = { dk: l.dk, pub: l.pub, status: l.status, uu: l.uu }; });
    return JSON.stringify({ v: 1, licenses: lic, revoked: L.revoked.slice().sort() }, null, 1);
  }

  var api = {
    PKG: PKG, APP_CERT_SHA256: APP_CERT_SHA256, TRUSTED: TRUSTED, GOOGLE_ROOTS: GOOGLE_ROOTS, LEVELS: LEVELS,
    utf8: utf8, fromUtf8: fromUtf8, b64ToBytes: b64ToBytes, bytesToB64: bytesToB64, b64url: b64url, hex: hex, sha256: sha256,
    deviceFingerprint: deviceFingerprint, pemBlocks: pemBlocks, der: der, kids: kids, parseCert: parseCert, derToRaw: derToRaw, rawToDer: rawToDer,
    verifySig: verifySig, parseKeyDescription: parseKeyDescription, verifyChain: verifyChain, parseRequest: parseRequest, parseRequestAny: parseRequestAny, reviewRequest: reviewRequest,
    importPrivatePem: importPrivatePem, importPkcs8: importPkcs8, payloadOf: payloadOf, issueToken: issueToken, verifyToken: verifyToken,
    verifyRelease: verifyRelease, sealKey: sealKey, openKey: openKey,
    newLedger: newLedger, nextLid: nextLid, defaultUu: defaultUu, ymd: ymd, findLicense: findLicense, baseLid: baseLid, record: record,
    canTransfer: canTransfer, markMoved: markMoved, transfersInYear: transfersInYear, revocationJson: revocationJson, ledgerCsv: ledgerCsv,
    serverExport: serverExport, DAY: DAY,
    misuseBody: misuseBody, officeSeats: officeSeats, seatCheck: seatCheck,
    NUM_KEYS: NUM_KEYS, LIMITS: LIMITS, SWITCHES: SWITCHES, DEFAULT_TEMPLATES: DEFAULT_TEMPLATES, fieldsOf: fieldsOf, describe: describe, limitsOf: limitsOf, amend: amend
  };
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.VT = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
