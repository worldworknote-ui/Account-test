/* اختبار الخادم المرجعي: node license-server/test/server.test.js */
"use strict";
var fs = require("fs"), path = require("path"), os = require("os"), crypto = require("crypto"), http = require("http");
var dir = fs.mkdtempSync(path.join(os.tmpdir(), "sa8srv-"));
process.env.DATA_DIR = dir;
var S = require("../server.js");
var fails = 0, passes = 0;
function check(c, m) { if (c) passes++; else fails++; console.log((c ? "  PASS " : "  FAIL ") + m); }
function b64url(b) { return Buffer.from(b).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }

function device() {
  var kp = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
  var spki = kp.publicKey.export({ type: "spki", format: "der" });
  return { priv: kp.privateKey, spki: spki, dk: b64url(crypto.createHash("sha256").update(spki).digest()) };
}

/** مطابق لـ License.checkOnline في التطبيق. */
function request(d, lid, at, n) {
  var body = "v=1;t=check;lid=" + lid + ";dk=" + d.dk + ";n=" + (n || b64url(crypto.randomBytes(16))) + ";at=" + (at || Date.now());
  var sig = crypto.sign("sha256", Buffer.from(body), { key: d.priv, dsaEncoding: "der" });
  return { body: body, sig: b64url(sig), pub: d.spki.toString("base64"), ver: "8.0" };
}

function post(port, p, obj) {
  return new Promise(function (ok) {
    var data = typeof obj === "string" ? obj : JSON.stringify(obj);
    var r = http.request({ host: "127.0.0.1", port: port, path: p, method: obj === undefined ? "GET" : "POST", agent: false, headers: { "Content-Type": "application/json" } }, function (res) {
      var c = [];
      res.on("data", function (x) { c.push(x); });
      res.on("end", function () { ok({ status: res.statusCode, json: JSON.parse(Buffer.concat(c).toString() || "{}") }); });
    });
    if (obj !== undefined) r.write(data);
    r.end();
  });
}

(async function () {
  var a = device(), b = device(), c = device();
  fs.writeFileSync(path.join(dir, "licenses.json"), JSON.stringify({
    v: 1, licenses: { "SA-2026-0001": { dk: a.dk, status: "moved" }, "SA-2026-0001-T1": { dk: b.dk, status: "active" } }, revoked: ["SA-2026-0001"]
  }));
  fs.writeFileSync(path.join(dir, "rates.json"), JSON.stringify({ rates: { USD: 1, SYP: 13100, TRY: 41.2 }, updatedAt: "2026-10-14T08:00:00Z" }));
  var srv = S.makeServer();
  await new Promise(function (ok) { srv.listen(0, "127.0.0.1", ok); });
  var port = srv.address().port;

  var r1 = await post(port, "/v1/check", request(b, "SA-2026-0001-T1"));
  check(r1.status === 200 && r1.json.message.indexOf("سليمة") >= 0 && r1.json.revoked.length === 0, "رخصة فعّالة: سليمة، والرد لا يكشف رخص الآخرين الملغاة (10.11)");
  var r2 = await post(port, "/v1/check", request(a, "SA-2026-0001"));
  check(r2.status === 200 && r2.json.message.indexOf("أُلغيت") >= 0 && r2.json.revoked.indexOf("SA-2026-0001") >= 0, "الهاتف القديم بعد النقل: ملغاة، ورخصته في قائمة الرد");
  var r3 = await post(port, "/v1/check", request(c, "SA-2026-0001-T1"));
  check(r3.status === 200 && r3.json.revoked.indexOf("SA-2026-0001-T1") >= 0, "رقم رخصة على هاتف غير مسجّل له: يُضاف لقائمة الإلغاء في الرد");
  var r4 = await post(port, "/v1/check", request(c, "SA-2099-0001"));
  check(r4.status === 200 && r4.json.message.indexOf("غير مسجّلة") >= 0, "رخصة غير مسجّلة بعد: لا يتعطل شيء");
  var bad = request(b, "SA-2026-0001-T1");
  bad.body = bad.body.replace("SA-2026-0001-T1", "SA-2026-0002");
  check((await post(port, "/v1/check", bad)).status === 401, "تعديل الطلب بعد التوقيع يُرفض");
  var other = request(b, "SA-2026-0001-T1");
  other.pub = c.spki.toString("base64");
  check((await post(port, "/v1/check", other)).status === 401, "توقيع بمفتاح غير المرسل يُرفض");
  var rep = request(b, "SA-2026-0001-T1", Date.now(), "fixed-nonce-1");
  check((await post(port, "/v1/check", rep)).status === 200 && (await post(port, "/v1/check", rep)).status === 409, "إعادة إرسال الطلب نفسه تُرفض");
  check((await post(port, "/v1/check", request(b, "SA-2026-0001-T1", Date.now() - 5 * 86400000))).status === 400, "طلب قديم (ساعة بعيدة) يُرفض");
  check((await post(port, "/v1/check", "{not json")).status === 400, "JSON فاسد");
  check((await post(port, "/v1/check", "x".repeat(9000))).status === 413, "طلب كبير يُرفض");
  var rr = await post(port, "/v1/rates");
  check(rr.status === 200 && rr.json.result === "success" && rr.json.rates.SYP === 13100 && rr.json.time_last_update_unix === 1791964800, "أسعار الصرف بصيغة التطبيق");
  check((await post(port, "/v1/health")).json.ok === true, "/v1/health");
  check((await post(port, "/nothing")).status === 404, "مسار مجهول");
  srv.close();
  console.log(fails === 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
  process.exit(fails ? 1 : 0);
})();
