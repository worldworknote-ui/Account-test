/* يولّد طلبات تفعيل وإيصال تحرير لاختبار الواجهة: node make-requests.js <مجلد> */
"use strict";
var fs = require("fs"), path = require("path"), nc = require("crypto");
var VT = require("../core.js");
var OUT = process.argv[2];
fs.mkdirSync(OUT, { recursive: true });

function dev() {
  var kp = nc.generateKeyPairSync("ec", { namedCurve: "P-256" });
  return { priv: kp.privateKey, spki: new Uint8Array(kp.publicKey.export({ type: "spki", format: "der" })) };
}

async function req(d, o) {
  var j = Object.assign({ t: "sa-activate", v: 1, order: "2026-0153", pkg: VT.PKG, ver: "8.0", build: 20261014, dk: await VT.deviceFingerprint(d.spki), pub: VT.bytesToB64(d.spki), chain: [], model: "Samsung A15 / Android 15", at: Date.now() }, o || {});
  return "SA8-REQ:" + VT.b64url(VT.utf8(JSON.stringify(j)));
}

(async function () {
  var d1 = dev(), d2 = dev();
  var chain = VT.pemBlocks(fs.readFileSync(path.join(__dirname, "vectors", "tegu_sdk36_SB_EC_2026_ROOT.pem"), "utf8"), "CERTIFICATE");
  var leaf = VT.parseCert(chain[0]);
  var year = new Date().getFullYear();
  var lid = "SA-" + year + "-0001";
  var dk1 = await VT.deviceFingerprint(d1.spki);
  var body = "v=1;t=release;lid=" + lid + ";dk=" + dk1 + ";n=e2e;at=" + Date.now();
  var sig = nc.sign("sha256", Buffer.from(body, "utf8"), { key: d1.priv, dsaEncoding: "der" });
  var out = {
    good: "السلام عليكم، هذا طلب التفعيل:\n" + (await req(d1)).replace(/(.{70})/g, "$1\n") + "\nشكرًا لكم",
    xss: await req(dev(), { order: "2026-0999", model: "<img src=x onerror=window.__xss=1>" }),
    other: await req({ spki: leaf.spki }, { order: "2026-0200", chain: chain.map(VT.bytesToB64), model: "Pixel (تطبيق آخر)" }),
    second: await req(d2, { order: "2026-0154", model: "Xiaomi Redmi Note 13" }),
    receipt: "إيصال التحرير:\nSA8-REL:" + VT.b64url(VT.utf8(body)) + "." + VT.b64url(new Uint8Array(sig)),
    pub1: VT.bytesToB64(d1.spki), pub2: VT.bytesToB64(d2.spki), lid: lid
  };
  fs.writeFileSync(path.join(OUT, "requests.json"), JSON.stringify(out, null, 1));
  console.log("wrote", path.join(OUT, "requests.json"));
})();
