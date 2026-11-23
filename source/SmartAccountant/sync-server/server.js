#!/usr/bin/env node
/*
 * خادم المزامنة الخاص لـ«المحاسب الذكي» 10.4 — اختياري. Node 18+ بلا أي مكتبات خارجية.
 *
 * الخادم «صندوق بريد» لملفات مشفّرة فقط: يحفظ ملفات كل خزنة كما تصله (أسماء معتمة ومحتوى AES-256-GCM)
 * ولا يملك أي مفتاح، فلا يرى اسمًا ولا مبلغًا. الكتابة في خزنة لمن يملك رمزًا مشتقًا من مفتاحها:
 * أول كتابة تسجّل بصمة الرمز (SHA-256)، وكل ما بعدها يجب أن يطابقها.
 *
 *   GET  /v1/health                         الحالة (ويخبر إن كان الخادم يطلب «رمز الخادم»)
 *   GET  /v1/vault/<id>/list?prefix=p-      أسماء الملفات (بالرمز)
 *   GET  /v1/vault/<id>/file/<name>         تنزيل ملف (رأس الخزنة بلا رمز: فيه الملح والمفتاح المغلّف فقط)
 *   PUT  /v1/vault/<id>/file/<name>         رفع ملف (بالرمز)
 *   DELETE /v1/vault/<id>/file/<name>       حذف ملف (بالرمز) — 10.4: تنظيف الحزم القديمة تلقائيًا
 *
 * الإعداد (متغيرات بيئة):
 *   PORT=8090            المنفذ (خلف HTTPS: Caddy أو nginx)
 *   DATA_DIR=./vaults    مكان الملفات
 *   SERVER_KEY=...       اختياري (مستحسن): «رمز الخادم» يُكتب في التطبيق؛ بدونه يُرفض إنشاء خزنة جديدة أو قراءتها
 *   MAX_FILE_MB=64       أكبر ملف (اللقطة الكاملة قد تكون عدة ميغابايت)
 *   MAX_VAULT_MB=2048    أكبر حجم لخزنة واحدة
 *   RATE_PER_MIN=1200    أكثر عدد طلبات في الدقيقة لكل عنوان IP
 *   MAX_TOTAL_MB=20480   أكبر حجم لكل الخزنات معًا (10.11)
 *   TRUST_PROXY=1        خلف Caddy/nginx فقط: عنوان العميل من X-Forwarded-For (آخر عنوان أضافه الوكيل)؛
 *                        بدونه يُستعمل عنوان الاتصال نفسه، فلا يتجاوز أحد حد الطلبات بترويسة مزوّرة (10.11)
 */
"use strict";
var http = require("http"), fs = require("fs"), path = require("path"), crypto = require("crypto");

var VERSION = "10.11";
var DATA = process.env.DATA_DIR || path.join(__dirname, "vaults");
var SERVER_KEY = process.env.SERVER_KEY || "";
var MAX_FILE = Number(process.env.MAX_FILE_MB || 64) * 1024 * 1024;
var MAX_VAULT = Number(process.env.MAX_VAULT_MB || 2048) * 1024 * 1024;
var RATE = Number(process.env.RATE_PER_MIN || 1200);
var MAX_TOTAL = Number(process.env.MAX_TOTAL_MB || 20480) * 1024 * 1024;
var TRUST_PROXY = process.env.TRUST_PROXY === "1";
var HEADER = "smart-accountant-sync-vault.json";
var NAME_RE = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$/, VAULT_RE = /^[0-9a-f]{8,64}$/;

function send(rs, code, obj, type) {
  var body = Buffer.isBuffer(obj) ? obj : Buffer.from(JSON.stringify(obj), "utf8");
  rs.writeHead(code, {
    "Content-Type": type || "application/json; charset=utf-8",
    "Content-Length": body.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  rs.end(body);
}

function eqConst(a, b) {
  var x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

function sha(s) { return crypto.createHash("sha256").update(String(s)).digest("hex"); }

function vaultDir(id) { return path.join(DATA, id); }

function tokenOk(id, rq) {
  var auth = String(rq.headers["authorization"] || "");
  if (auth.indexOf("Bearer ") !== 0) return false;
  var t = auth.substring(7).trim();
  try {
    var saved = fs.readFileSync(path.join(vaultDir(id), ".token"), "utf8").trim();
    return eqConst(saved, sha(t));
  } catch (e) {
    return false;
  }
}

function vaultExists(id) { return fs.existsSync(path.join(vaultDir(id), ".token")); }

function vaultSize(id) {
  var n = 0;
  try {
    fs.readdirSync(vaultDir(id)).forEach(function (f) { n += fs.statSync(path.join(vaultDir(id), f)).size; });
  } catch (e) { /* فارغة */ }
  return n;
}

/** 10.11: حجم كل الخزنات (يُحسب عند الحاجة، مع ذاكرة قصيرة حتى لا يُمسح القرص في كل طلب). */
var totalCache = { at: 0, n: 0 };
function totalSize() {
  if (Date.now() - totalCache.at < 60000) return totalCache.n;
  var n = 0;
  try {
    fs.readdirSync(DATA).forEach(function (v) { if (VAULT_RE.test(v)) n += vaultSize(v); });
  } catch (e) { /* فارغ */ }
  totalCache = { at: Date.now(), n: n };
  return n;
}

/** 10.11 (AUDIT-009): عنوان العميل لحد الطلبات: عنوان الاتصال، أو آخر عنوان أضافه الوكيل الموثوق. */
function clientIp(rq) {
  if (TRUST_PROXY) {
    var xs = String(rq.headers["x-forwarded-for"] || "").split(",").map(function (x) { return x.trim(); }).filter(Boolean);
    if (xs.length) return xs[xs.length - 1];
  }
  return rq.socket.remoteAddress || "";
}

function readBody(rq, limit, cb) {
  var chunks = [], size = 0, done = false;
  rq.on("data", function (c) {
    if (done) return;
    size += c.length;
    if (size > limit) {
      done = true;
      cb(new Error("too large"));
      rq.destroy();
      return;
    }
    chunks.push(c);
  });
  rq.on("end", function () {
    if (!done) {
      done = true;
      cb(null, Buffer.concat(chunks));
    }
  });
  rq.on("error", function (e) {
    if (!done) {
      done = true;
      cb(e);
    }
  });
}

function handle(rq, rs) {
  var u = new URL(rq.url, "http://x");
  var p = u.pathname;
  var keyOk = !SERVER_KEY || eqConst(String(rq.headers["x-server-key"] || ""), SERVER_KEY);
  if (p === "/v1/health" && rq.method === "GET") {
    return send(rs, 200, { service: "smart-accountant-sync", version: VERSION, keyRequired: !!SERVER_KEY, keyOk: keyOk, time: Date.now() });
  }
  var m = /^\/v1\/vault\/([^/]+)\/(list|file\/([^/]+))$/.exec(p);
  if (!m) return send(rs, 404, { error: "غير موجود" });
  var id = m[1], name = m[3] ? decodeURIComponent(m[3]) : null;
  if (!VAULT_RE.test(id)) return send(rs, 400, { error: "رمز خزنة غير صالح" });
  if (name !== null && !NAME_RE.test(name)) return send(rs, 400, { error: "اسم ملف غير صالح" });
  if (!keyOk) return send(rs, 403, { error: "رمز الخادم غير صحيح" });
  var dir = vaultDir(id);

  if (m[2] === "list" && rq.method === "GET") {
    if (!vaultExists(id)) return send(rs, 404, { error: "لا توجد خزنة بهذا الرمز" });
    if (!tokenOk(id, rq)) return send(rs, 401, { error: "رمز الخزنة غير صحيح" });
    var prefix = u.searchParams.get("prefix") || "";
    var files = [];
    fs.readdirSync(dir).forEach(function (f) {
      if (f.charAt(0) === "." || f.indexOf(prefix) !== 0) return;
      var st = fs.statSync(path.join(dir, f));
      files.push({ n: f, s: st.size, m: st.mtimeMs });
    });
    return send(rs, 200, { files: files });
  }

  if (name !== null && rq.method === "GET") {
    if (!vaultExists(id)) return send(rs, 404, { error: "لا توجد خزنة بهذا الرمز" });
    // رأس الخزنة يُقرأ بلا رمز (يحتاجه الجهاز المنضم ليفتح المفتاح بكلمة السر)؛ لا يحمل أي بيانات
    if (name !== HEADER && !tokenOk(id, rq)) return send(rs, 401, { error: "رمز الخزنة غير صحيح" });
    var f = path.join(dir, name);
    if (!fs.existsSync(f)) return send(rs, 404, { error: "لا يوجد" });
    return send(rs, 200, fs.readFileSync(f), "application/octet-stream");
  }

  if (name !== null && rq.method === "PUT") {
    var auth = String(rq.headers["authorization"] || "");
    if (auth.indexOf("Bearer ") !== 0 || auth.length < 40) return send(rs, 401, { error: "رمز الخزنة مطلوب" });
    var fresh = !vaultExists(id);
    if (!fresh && !tokenOk(id, rq)) return send(rs, 401, { error: "رمز الخزنة غير صحيح" });
    return readBody(rq, MAX_FILE, function (err, body) {
      if (err) return send(rs, 413, { error: "الملف أكبر من " + (MAX_FILE / 1048576) + " ميغابايت" });
      // 10.11 (AUDIT-009): الحد يشمل أول كتابة في خزنة جديدة، ومعه حد لكل الخزنات معًا
      if ((fresh ? 0 : vaultSize(id)) + body.length > MAX_VAULT) return send(rs, 507, { error: "امتلأت مساحة الخزنة على الخادم" });
      if (totalSize() + body.length > MAX_TOTAL) return send(rs, 507, { error: "امتلأت مساحة الخادم" });
      totalCache.n += body.length;
      try {
        fs.mkdirSync(dir, { recursive: true, mode: 448 });
        if (fresh) {
          // أول كتابة تسجّل بصمة الرمز (الخزنة الجديدة لصاحبها)
          fs.writeFileSync(path.join(dir, ".token"), sha(auth.substring(7).trim()), { mode: 384, flag: "wx" });
        }
        var tmp = path.join(dir, ".tmp-" + crypto.randomBytes(8).toString("hex"));
        fs.writeFileSync(tmp, body, { mode: 384 });
        var fd = fs.openSync(tmp, "r");
        fs.fsyncSync(fd);
        fs.closeSync(fd);
        fs.renameSync(tmp, path.join(dir, name));
        return send(rs, 200, { ok: true, size: body.length });
      } catch (e) {
        if (e && e.code === "EEXIST") return send(rs, 409, { error: "الخزنة أُنشئت للتو من جهاز آخر؛ أعد المحاولة" });
        return send(rs, 500, { error: "تعذر الحفظ على الخادم" });
      }
    });
  }
  if (name !== null && rq.method === "DELETE") {
    if (!vaultExists(id)) return send(rs, 404, { error: "لا توجد خزنة بهذا الرمز" });
    if (!tokenOk(id, rq)) return send(rs, 401, { error: "رمز الخزنة غير صحيح" });
    if (name === HEADER) return send(rs, 403, { error: "لا يُحذف رأس الخزنة" });
    try {
      fs.unlinkSync(path.join(dir, name));
    } catch (e) {
      if (e && e.code === "ENOENT") return send(rs, 404, { error: "لا يوجد" });
      return send(rs, 500, { error: "تعذر الحذف" });
    }
    return send(rs, 200, { ok: true });
  }
  return send(rs, 405, { error: "طريقة غير مسموحة" });
}

function makeServer() {
  var hits = new Map();
  return http.createServer(function (rq, rs) {
    var ip = clientIp(rq);
    var now = Date.now(), h = hits.get(ip) || { n: 0, t: now };
    if (now - h.t > 60000) h = { n: 0, t: now };
    h.n++;
    hits.set(ip, h);
    if (hits.size > 100000) hits.clear();
    if (h.n > RATE) return send(rs, 429, { error: "طلبات كثيرة؛ أعد المحاولة بعد دقيقة" });
    try {
      handle(rq, rs);
    } catch (e) {
      send(rs, 500, { error: "خطأ في الخادم" });
    }
  });
}

if (require.main === module) {
  // 10.11 (AUDIT-009): القيمة النموذجية في ملف الخدمة ليست سرًا
  if (/change-me/i.test(SERVER_KEY)) {
    console.error("SERVER_KEY ما زال القيمة النموذجية من ملف الخدمة: ضع سرًا عشوائيًا طويلًا ثم أعد التشغيل.");
    process.exit(1);
  }
  if (SERVER_KEY && SERVER_KEY.length < 16) console.warn("تنبيه: SERVER_KEY قصير؛ يُنصح بسر عشوائي من 32 محرفًا على الأقل.");
  fs.mkdirSync(DATA, { recursive: true, mode: 448 });
  var port = Number(process.env.PORT || 8090);
  makeServer().listen(port, process.env.HOST || "127.0.0.1", function () {
    console.log("smart-accountant-sync " + VERSION + " on " + port + " data=" + DATA + (SERVER_KEY ? " (server key required)" : " (open: set SERVER_KEY!)"));
  });
}

module.exports = { makeServer: makeServer };
