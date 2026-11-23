// اختبارات خادم المزامنة (10.11): node sync-server/test/server.test.js — محليًا على 127.0.0.1 بمجلد مؤقت.
"use strict";
var path = require("path"), fs = require("fs"), os = require("os"), http = require("http");

var dir = fs.mkdtempSync(path.join(os.tmpdir(), "sa-sync-test-"));
process.env.DATA_DIR = dir;
process.env.RATE_PER_MIN = "5";
process.env.MAX_VAULT_MB = "1";
process.env.MAX_TOTAL_MB = "3";
var S = require(path.join(__dirname, "..", "server.js"));

var fails = 0, passes = 0;
function check(c, m) {
  if (c) passes++; else fails++;
  console.log((c ? "  PASS " : "  FAIL ") + m);
}

function req(port, method, p, headers, body) {
  return new Promise(function (res, rej) {
    var r = http.request({ host: "127.0.0.1", port: port, method: method, path: p, headers: headers || {} }, function (rs) {
      var c = [];
      rs.on("data", function (d) { c.push(d); });
      rs.on("end", function () { res({ code: rs.statusCode, body: Buffer.concat(c).toString() }); });
    });
    r.on("error", rej);
    if (body) r.write(body);
    r.end();
  });
}

function listen(srv) { return new Promise(function (ok) { srv.listen(0, "127.0.0.1", function () { ok(srv.address().port); }); }); }
function auth(t) { return { Authorization: "Bearer " + t }; }

(async function () {
  var A = "A".repeat(64), B = "B".repeat(64);

  console.log("== حد الطلبات");
  var srv = S.makeServer(), port = await listen(srv);
  var spoofed = [];
  for (var i = 0; i < 8; i++) spoofed.push((await req(port, "GET", "/v1/health", { "X-Forwarded-For": "10.0.0." + i })).code);
  check(spoofed.indexOf(429) >= 0, "X-Forwarded-For يكتبه العميل لا يتجاوز حد الطلبات (بلا TRUST_PROXY): " + spoofed.join(","));
  srv.close();

  process.env.RATE_PER_MIN = "100000";
  delete require.cache[require.resolve(path.join(__dirname, "..", "server.js"))];
  S = require(path.join(__dirname, "..", "server.js"));
  srv = S.makeServer();
  port = await listen(srv);

  console.log("== الكتابة والقراءة");
  var V = "deadbeefdeadbeef";
  check((await req(port, "PUT", "/v1/vault/" + V + "/file/p-1.sab", auth(A), Buffer.from("x"))).code === 200, "أول كتابة تنشئ الخزنة");
  check((await req(port, "GET", "/v1/vault/" + V + "/file/p-1.sab", auth(A))).body === "x", "القراءة بالرمز");
  check((await req(port, "GET", "/v1/vault/" + V + "/file/p-1.sab", auth(B))).code === 401, "رمز آخر يُرفض");

  console.log("== الأسماء التي تبدأ بنقطة (ملف الرمز والملفات المؤقتة)");
  check((await req(port, "PUT", "/v1/vault/" + V + "/file/.token", auth(A), Buffer.from("y"))).code === 400, "لا كتابة فوق .token");
  check((await req(port, "DELETE", "/v1/vault/" + V + "/file/.token", auth(A))).code === 400, "لا حذف لـ .token");
  check((await req(port, "GET", "/v1/vault/" + V + "/file/.token", auth(A))).code === 400, "لا قراءة لـ .token");
  var dd = (await req(port, "GET", "/v1/vault/" + V + "/file/..", auth(A))).code;
  check(dd === 400 || dd === 404, "الاسم «..» لا يصل إلى نظام الملفات (" + dd + ")");
  check((await req(port, "PUT", "/v1/vault/" + V + "/file/p-2.sab", auth(B), Buffer.from("z"))).code === 401, "الخزنة ما زالت لصاحبها");

  console.log("== الحدود");
  var big = Buffer.alloc(2 * 1024 * 1024, 1);
  check((await req(port, "PUT", "/v1/vault/cafebabecafebabe/file/p-1.sab", auth(A), big)).code === 507, "أول ملف في خزنة جديدة أكبر من حد الخزنة يُرفض");
  var ok = 0;
  for (var k = 0; k < 5; k++) {
    var r = await req(port, "PUT", "/v1/vault/" + ("0123456789abcd" + k + k) + "/file/p-1.sab", auth(A), Buffer.alloc(900 * 1024, 1));
    if (r.code === 200) ok++;
  }
  check(ok === 3, "حد كل الخزنات معًا (3MB): قُبلت " + ok + " خزنات بحجم 900KB من 5");
  srv.close();

  console.log("\n" + (fails === 0 ? "ALL " + passes + " PASSED" : fails + " FAILED"));
  process.exit(fails === 0 ? 0 : 1);
})().catch(function (e) { console.error(e); process.exit(1); });
