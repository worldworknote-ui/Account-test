#!/usr/bin/env node
/*
 * خادم الترخيص المرجعي لـ«المحاسب الذكي» 8 — اختياري. Node 18+ بلا أي مكتبات خارجية.
 *
 *   POST /v1/check   فحص دوري من التطبيق (كل 7 أيام): الطلب موقّع بمفتاح الهاتف؛ الرد قائمة الرخص الملغاة.
 *   GET  /v1/rates   أسعار الصرف التي تحددها أنت (data/rates.json) بصيغة {"rates": {"SYP": 13000}}.
 *   GET  /v1/health  للتأكد أن الخادم يعمل.
 *
 * البيانات: data/licenses.json (نزّله من أداة البائع ← سجل المبيعات ← «سجل الخادم»)، وdata/rates.json (اختياري).
 * يعمل خلف HTTPS (Caddy أو nginx) لأن التطبيق لا يقبل إلا https. التشغيل: PORT=8080 node server.js
 * الخادم لا يستطيع إصدار رخص (لا يحمل المفتاح الخاص) ولا يرى أي بيانات حسابات: رقم الرخصة وبصمة الهاتف فقط.
 */
"use strict";
var http = require("http"), fs = require("fs"), path = require("path"), crypto = require("crypto");

var DATA = process.env.DATA_DIR || path.join(__dirname, "data");
var MAX_BODY = 8 * 1024, SKEW = 2 * 86400000;
// 10.11 (AUDIT-012): خلف Caddy/nginx فقط: عنوان العميل آخر ما أضافه الوكيل؛ بدونه عنوان الاتصال (لا ترويسة يكتبها العميل)
var TRUST_PROXY = process.env.TRUST_PROXY === "1";

function clientIp(rq) {
  if (TRUST_PROXY) {
    var xs = String(rq.headers["x-forwarded-for"] || "").split(",").map(function (x) { return x.trim(); }).filter(Boolean);
    if (xs.length) return xs[xs.length - 1];
  }
  return rq.socket.remoteAddress || "";
}

function readJson(name, def) {
  try {
    return JSON.parse(fs.readFileSync(path.join(DATA, name), "utf8"));
  } catch (e) {
    return def;
  }
}

function b64url(buf) { return Buffer.from(buf).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }

function fields(s) {
  var m = {};
  String(s).split(";").forEach(function (kv) {
    var i = kv.indexOf("=");
    if (i > 0) {
      if (Object.prototype.hasOwnProperty.call(m, kv.substring(0, i))) throw new Error("dup");
      m[kv.substring(0, i)] = kv.substring(i + 1);
    }
  });
  return m;
}

/** يتحقق من طلب الفحص كما يرسله License.checkOnline في التطبيق. يرجع {status, json}. */
function check(req, now, nonces) {
  var lic = readJson("licenses.json", { licenses: {}, revoked: [] });
  var revoked = lic.revoked || [];
  if (!req || typeof req.body !== "string" || typeof req.sig !== "string" || typeof req.pub !== "string") return { status: 400, json: { message: "طلب ناقص" } };
  var pubDer = Buffer.from(req.pub, "base64"), f;
  try {
    var key = crypto.createPublicKey({ key: pubDer, format: "der", type: "spki" });
    var ok = crypto.verify("sha256", Buffer.from(req.body, "utf8"), { key: key, dsaEncoding: "der" }, Buffer.from(req.sig.replace(/-/g, "+").replace(/_/g, "/"), "base64"));
    if (!ok) return { status: 401, json: { message: "توقيع غير صالح" } };
    f = fields(req.body);
  } catch (e) {
    return { status: 400, json: { message: "طلب غير مقروء" } };
  }
  if (f.v !== "1" || f.t !== "check" || !f.lid || !f.n) return { status: 400, json: { message: "صيغة غير معروفة" } };
  var dk = b64url(crypto.createHash("sha256").update(pubDer).digest());
  if (f.dk !== dk) return { status: 401, json: { message: "بصمة الهاتف لا تطابق مفتاحه" } };
  var at = Number(f.at);
  if (!(Math.abs(now - at) <= SKEW)) return { status: 400, json: { message: "ساعة الهاتف بعيدة عن الوقت الصحيح؛ صحّحها ثم أعد الفحص" } };
  if (nonces.has(f.n)) return { status: 409, json: { message: "طلب مكرر" } };
  nonces.add(f.n);
  if (nonces.size > 50000) nonces.clear();
  var entry = (lic.licenses || {})[f.lid];
  var msg, mine = false;
  if (!entry) msg = "الرخصة " + f.lid + " غير مسجّلة في الخادم بعد. لا يتعطل شيء.";
  else if (entry.dk !== dk) {
    msg = "الرخصة " + f.lid + " مسجّلة لهاتف آخر.";
    mine = true;
  } else if (revoked.indexOf(f.lid) >= 0 || entry.status === "moved" || entry.status === "revoked") {
    msg = "أُلغيت هذه الرخصة (نُقلت أو أُبلغ عن فقد الهاتف). تواصل مع البائع.";
    mine = true;
  } else msg = "الرخصة " + f.lid + " سليمة ✓";
  // 10.11 (AUDIT-012): الرد لا يكشف قائمة الرخص الملغاة كلها لأي طالب (يكفي توقيعه بمفتاح يولّده بنفسه)؛
  // التطبيق يقارن رخصته وحدها بالقائمة، فيكفيه أن يعرف إن كانت رخصته ملغاة
  return { status: 200, json: { revoked: mine ? [f.lid] : [], message: msg, at: now } };
}

function makeServer() {
  var nonces = new Set(), hits = new Map();
  return http.createServer(function (rq, rs) {
    var ip = clientIp(rq);
    var now = Date.now(), h = hits.get(ip) || { n: 0, t: now };
    if (now - h.t > 60000) h = { n: 0, t: now };
    h.n++;
    hits.set(ip, h);
    if (hits.size > 100000) hits.clear();
    function send(status, obj) {
      var body = JSON.stringify(obj);
      rs.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
      rs.end(body);
    }
    if (h.n > 60) return send(429, { message: "طلبات كثيرة؛ أعد المحاولة بعد دقيقة" });
    var url = rq.url.split("?")[0];
    if (rq.method === "GET" && url === "/v1/health") return send(200, { ok: true, at: now });
    if (rq.method === "GET" && url === "/v1/rates") {
      var r = readJson("rates.json", null);
      if (!r || !r.rates) return send(404, { result: "error", message: "لا توجد أسعار" });
      return send(200, { result: "success", rates: r.rates, time_last_update_unix: Math.floor((r.updatedAt ? Date.parse(r.updatedAt) : fs.statSync(path.join(DATA, "rates.json")).mtimeMs) / 1000) });
    }
    if (rq.method === "POST" && url === "/v1/check") {
      var chunks = [], size = 0;
      var refused = false;
      rq.on("data", function (c) {
        size += c.length;
        if (size > MAX_BODY) {
          if (!refused) {
            refused = true;
            rs.setHeader("Connection", "close");
            send(413, { message: "طلب كبير" });
          }
        } else chunks.push(c);
      });
      rq.on("end", function () {
        if (size > MAX_BODY) return;
        var j;
        try {
          j = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        } catch (e) {
          return send(400, { message: "JSON غير صالح" });
        }
        var res = check(j, Date.now(), nonces);
        send(res.status, res.json);
      });
      return;
    }
    send(404, { message: "غير موجود" });
  });
}

if (require.main === module) {
  var port = Number(process.env.PORT || 8080);
  makeServer().listen(port, process.env.HOST || "127.0.0.1", function () { console.log("license server on " + port + " (data: " + DATA + ")"); });
}
module.exports = { makeServer: makeServer, check: check };
