/*
 * اختبارات أداة البائع: node vendor-tool/test/core.test.js [مجلد المخرجات] [مجلد مفاتيح البائع الحقيقية — اختياري]
 * - سلاسل تصديق حقيقية من هواتف (github.com/android/keyattestation، رخصة Apache-2.0) تُقرأ وتتحقق حتى جذر Google.
 * - رموز تُصدرها الأداة تُكتب في node-tokens.json ليتحقق منها اختبار Java (LicenseTest) بالشيفرة نفسها التي في التطبيق.
 */
"use strict";
var fs = require("fs"), path = require("path"), nc = require("crypto");
var VT = require("../core.js");
var OUT = process.argv[2] || path.join(__dirname, "out");
var KEYS = process.argv[3] || "";
fs.mkdirSync(OUT, { recursive: true });

var fails = 0, passes = 0;
function check(c, m) {
  if (c) passes++;
  else fails++;
  console.log((c ? "  PASS " : "  FAIL ") + m);
}

var LEVEL = { SOFTWARE: 0, TRUSTED_ENVIRONMENT: 1, STRONG_BOX: 2 };
var VEC = path.join(__dirname, "vectors");

function chainOf(name) {
  return VT.pemBlocks(fs.readFileSync(path.join(VEC, name + ".pem"), "utf8"), "CERTIFICATE").map(function (b) { return VT.bytesToB64(b); });
}

function nodeKeyPair() {
  var kp = nc.generateKeyPairSync("ec", { namedCurve: "P-256" });
  return { priv: kp.privateKey, spki: new Uint8Array(kp.publicKey.export({ type: "spki", format: "der" })), pkcs8: new Uint8Array(kp.privateKey.export({ type: "pkcs8", format: "der" })) };
}

async function main() {
  console.log("== سلاسل تصديق حقيقية");
  var names = fs.readdirSync(VEC).filter(function (f) { return f.endsWith(".pem"); }).map(function (f) { return f.slice(0, -4); });
  for (var i = 0; i < names.length; i++) {
    var n = names[i], exp = JSON.parse(fs.readFileSync(path.join(VEC, n + ".json"), "utf8").replace(/^\s*\/\/.*$/mg, ""));
    var chain = chainOf(n);
    var r = await VT.verifyChain(chain);
    var hw = exp.attestationSecurityLevel !== "SOFTWARE";
    check(r.sigOk, n + ": كل توقيعات السلسلة (" + chain.length + " شهادات) صحيحة");
    check(r.rootTrusted === hw, n + ": " + (hw ? "تنتهي بجذر Google: " + r.rootName : "تصديق برمجي لا ينتهي بجذر Google العتادي"));
    var kd = r.kd;
    check(kd && kd.securityLevel === LEVEL[exp.attestationSecurityLevel], n + ": مستوى الأمان " + exp.attestationSecurityLevel);
    check(kd && VT.bytesToB64(kd.challenge) === exp.attestationChallenge, n + ": التحدي يُقرأ كما هو");
    var app = exp.softwareEnforced.attestationApplicationId || (exp.hardwareEnforced || {}).attestationApplicationId;
    if (app) {
      check(kd.appId && kd.appId.packages.map(function (p) { return p.name; }).join(",") === app.packages.map(function (p) { return p.name; }).join(","), n + ": اسم الحزمة");
      var sigs = app.signatures.map(function (s) { return VT.hex(VT.b64ToBytes(s)).toUpperCase(); });
      check(kd.appId.signatures.join(",") === sigs.join(","), n + ": بصمة شهادة التطبيق");
    }
    var rot = exp.hardwareEnforced && exp.hardwareEnforced.rootOfTrust;
    if (rot) check(kd.rootOfTrust && kd.rootOfTrust.deviceLocked === rot.deviceLocked && kd.rootOfTrust.verifiedBootState === rot.verifiedBootState, n + ": حالة الإقلاع " + rot.verifiedBootState);
  }

  console.log("== سلاسل معدّلة");
  var good = chainOf("tegu_sdk36_SB_EC_2026_ROOT");
  var leaf = VT.b64ToBytes(good[0]);
  var c0 = VT.parseCert(leaf);
  var bad = new Uint8Array(leaf);
  bad[c0.tbs.byteOffset - leaf.byteOffset + 40] ^= 1;
  var r1 = await VT.verifyChain([VT.bytesToB64(bad)].concat(good.slice(1)));
  check(!r1.sigOk && !r1.ok, "تغيير بايت واحد في شهادة المفتاح يُكشف");
  var r2 = await VT.verifyChain([good[0]].concat(good.slice(2)));
  check(!r2.sigOk, "حذف شهادة وسيطة يُكشف");
  var r3 = await VT.verifyChain(good.slice(1));
  check(!r3.kd && !r3.ok, "سلسلة بلا شهادة المفتاح: لا وصف تصديق");
  var r4 = await VT.verifyChain(good.slice(0, good.length - 1));
  check(r4.sigOk === false || !r4.rootTrusted, "سلسلة بلا جذر Google لا تُعدّ موثوقة");
  var r5 = await VT.verifyChain(good, { status: { entries: (function () { var e = {}; e[VT.parseCert(VT.b64ToBytes(good[1])).serial] = { status: "REVOKED", reason: "KEY_COMPROMISE" }; return e; })() } });
  check(r5.revoked.length === 1 && !r5.ok, "شهادة ملغاة في قائمة Google تُرفض");

  console.log("== مراجعة طلب التفعيل");
  // طلب بسلسلة حقيقية لكن لتطبيق آخر وتحدٍّ آخر: يجب أن يُرفض
  var other = await VT.reviewRequest("SA8-REQ:" + VT.b64url(VT.utf8(JSON.stringify({
    t: "sa-activate", v: 1, order: "2026-0001", pkg: VT.PKG, ver: "8.0", build: 20261014,
    dk: await VT.deviceFingerprint(c0.spki), pub: VT.bytesToB64(c0.spki), chain: good, model: "Pixel"
  }))));
  check(other.errors.some(function (e) { return e.indexOf("التحدي") >= 0; }), "تحدٍّ لا يطابق رقم الطلب يُرفض");
  check(other.errors.some(function (e) { return e.indexOf("تطبيق آخر") >= 0; }), "مفتاح أنشأه تطبيق آخر يُرفض");
  check(other.errors.some(function (e) { return e.indexOf("ليس موقّعًا بشهادتك") >= 0; }), "تطبيق غير موقّع بشهادة البائع يُرفض");
  check(other.info.some(function (e) { return e.indexOf("Google") >= 0; }) && other.level === 2, "والسلسلة نفسها موثوقة (StrongBox)");

  var dev = nodeKeyPair();
  var dk = await VT.deviceFingerprint(dev.spki);
  function req(o) {
    var j = Object.assign({ t: "sa-activate", v: 1, order: "2026-0153", pkg: VT.PKG, ver: "8.0", build: 20261014, dk: dk, pub: VT.bytesToB64(dev.spki), chain: [], model: "Samsung A15 / Android 15" }, o || {});
    return "SA8-REQ:" + VT.b64url(VT.utf8(JSON.stringify(j)));
  }
  var plain = await VT.reviewRequest("رسالة من العميل:\n" + req().replace(/(.{60})/g, "$1\n") + "\nشكرًا");
  check(plain.errors.length === 0 && plain.warnings.length === 2 && plain.req.order === "2026-0153"
    && plain.warnings.some(function (w) { return w.indexOf("أقدم من 12.7") >= 0; }), "طلب بلا سلسلة (محاكٍ/هاتف قديم): تحذيرات فقط (وبلا هوية مكتب: من إصدار أقدم)، ويُقرأ من وسط رسالة");
  // 11.5: الطلب المضغوط SA8-REQZ: (كما يصنعه التطبيق بـ Deflater) يُقرأ كالعادي ومن وسط رسالة مقسّمة، وأقصر بوضوح
  var fullJson = JSON.stringify({ t: "sa-activate", v: 1, order: "2026-0001", pkg: VT.PKG, ver: "8.0", build: 20261014,
    dk: await VT.deviceFingerprint(c0.spki), pub: VT.bytesToB64(c0.spki), chain: good, model: "Pixel" });
  var zreq = "SA8-REQZ:" + VT.b64url(new Uint8Array(require("zlib").deflateRawSync(Buffer.from(fullJson, "utf8"), { level: 9 })));
  var oreq = "SA8-REQ:" + VT.b64url(VT.utf8(fullJson));
  var zr = await VT.reviewRequest("طلب:\n" + zreq.replace(/(.{80})/g, "$1\n") + "\nشكرًا");
  check(zr.req && zr.req.order === "2026-0001" && zr.req.chain.length === good.length, "الطلب المضغوط يُقرأ من وسط رسالة مقسّمة");
  check(zreq.length < oreq.length * 0.8, "المضغوط أقصر: " + zreq.length + " بدل " + oreq.length + " حرفًا");
  check((await VT.reviewRequest("SA8-REQZ:AAAA")).errors.length > 0, "مضغوط فاسد يُرفض دون رمي");
  check((await VT.reviewRequest(req({ dk: dk.replace(/^./, dk[0] === "A" ? "B" : "A") }))).errors.length > 0, "بصمة معدّلة تُرفض");
  check((await VT.reviewRequest(req({ pkg: "com.smart.hawala.v7" }))).errors.length > 0, "حزمة أخرى تُرفض");
  check((await VT.reviewRequest("SA8-REQ:@@@")).errors.length > 0, "طلب فاسد لا يرمي");
  check((await VT.reviewRequest("مرحبا")).errors.length > 0, "نص ليس طلبًا");

  console.log("== إصدار الرمز (مفتاح اختبار)");
  var vendor = nodeKeyPair();
  var signer = await VT.importPkcs8(vendor.pkcs8);
  check(signer.kid === null, "مفتاح الاختبار ليس من مفاتيح التطبيق (kid=null)");
  var refused = false;
  try {
    await VT.issueToken(signer, { lid: "SA-2026-0001", uu: 20271014, dk: dk });
  } catch (e) {
    refused = true;
  }
  check(refused, "لا إصدار بمفتاح ليس من مفاتيح التطبيق");
  signer.kid = "t9";
  var trusted = { t9: signer.spki };
  var tok = await VT.issueToken(signer, { lid: "SA-2026-0001", uu: 20271014, dk: dk });
  var f = await VT.verifyToken(tok, dev.spki, trusted);
  check(f && f.lid === "SA-2026-0001" && f.ft === "all" && f.ed === "pro" && f.uu === "20271014" && f.kid === "t9", "رمز صحيح يتحقق بحقوله");
  check(!(await VT.verifyToken(tok, nodeKeyPair().spki, trusted)), "الرمز لا يعمل لهاتف آخر");
  var parts = tok.split(".");
  var forged = VT.b64url(VT.utf8(VT.fromUtf8(VT.b64ToBytes(parts[0])).replace("uu=20271014", "uu=20991231"))) + "." + parts[1];
  check(!(await VT.verifyToken(forged, dev.spki, trusted)), "تمديد التاريخ في رمز موقّع يُرفض");
  var bads = [["lid", "SA;ft=all"], ["lid", "a b"], ["uu", "2027-10-14"], ["dk", "short"], ["ed", "x=y"]];
  for (var bi = 0; bi < bads.length; bi++) {
    var fx = { lid: "SA-2026-0002", uu: 20271014, dk: dk };
    fx[bads[bi][0]] = bads[bi][1];
    var threw = false;
    try {
      await VT.issueToken(signer, fx);
    } catch (e) {
      threw = true;
    }
    check(threw, "حقل غير صالح يُرفض قبل التوقيع: " + bads[bi][0] + "=" + bads[bi][1]);
  }
  var der = VT.b64ToBytes(parts[1]);
  check(der[0] === 0x30 && VT.rawToDer(VT.derToRaw(der, 32)).join() === der.join(), "التوقيع بترميز DER ويتحول ذهابًا وإيابًا");

  // رموز لاختبار Java: التطبيق يجب أن يقبل ما تصدره الأداة ويرفض المعدّل
  var dev2 = nodeKeyPair();
  var raw64 = VT.derToRaw(der, 32);
  var list = [
    { name: "رمز كامل", token: tok, ok: true },
    { name: "رمز جزئي mo و mt", token: await VT.issueToken(signer, { lid: "SA-2026-0003", ed: "small", ft: "offices,export", uu: 20270101, dk: dk, mo: 20, mt: 2000 }), ok: true },
    { name: "رمز بأسطر جديدة من واتساب", token: tok.replace(/(.{30})/g, "$1\n"), ok: true },
    // 12.6: رمز مخصص بالقالب الأساسي وحدود رقمية وتاريخ انتهاء
    { name: "رمز 12.6 بحدود مخصصة", token: await VT.issueToken(signer, Object.assign({ lid: "SA-2026-0005", uu: 20270101, dk: dk, ex: 20271231 }, VT.fieldsOf(VT.DEFAULT_TEMPLATES[0]))), ok: true },
    { name: "رمز لهاتف آخر", token: await VT.issueToken(signer, { lid: "SA-2026-0004", uu: 20271014, dk: await VT.deviceFingerprint(dev2.spki) }), ok: false },
    { name: "تاريخ ممدد بعد التوقيع", token: forged, ok: false },
    { name: "توقيع خام بلا DER", token: parts[0] + "." + VT.b64url(raw64), ok: false }
  ];
  fs.writeFileSync(path.join(OUT, "node-tokens.json"), JSON.stringify({ kid: "t9", vendorPub: signer.spki, devicePub: VT.bytesToB64(dev.spki), tokens: list }));
  check(true, "كُتب node-tokens.json (" + list.length + " رموز) لاختبار Java");

  console.log("== 12.6: القوالب والحدود الرقمية وتعديل الرخصة");
  var fb = VT.fieldsOf(VT.DEFAULT_TEMPLATES[0]);
  check(fb.mo === 30 && fb.mu === 2 && fb.mb === 1 && fb.mc === 100 && !fb.mt && !fb.mw, "القالب الأساسي: 30 مكتبًا، مستخدمان، صندوق، 100 عميل");
  check(fb.ft.split(",").indexOf("ai") < 0 && fb.ft.split(",").indexOf("users") >= 0, "الأساسي بلا ذكاء اصطناعي ومع المستخدمين");
  var fOff = VT.fieldsOf({ name: "صغيرة جدا", lim: { offices: { on: true, n: 20 }, boxes: { on: false }, clients: { on: false }, wa: { on: false } }, sw: { export: true } });
  check(fOff.mb === 1 && fOff.mc === 25 && fOff.mw === 20 && fOff.ed === "صغيرة_جدا", "البند غير المحدد = حد المجانية مكتوبًا صراحة، والاسم بلا مسافات");
  var p126 = VT.payloadOf(Object.assign({ lid: "SA-2026-0009", uu: 20270101, dk: dk, ex: 20271231 }, fb), "k1");
  check(/;mo=30/.test(p126) && /;mu=2/.test(p126) && /;ex=20271231/.test(p126), "الحدود وتاريخ الانتهاء في الرمز");
  var badLim = false;
  try { VT.fieldsOf({ name: "x", lim: { users: { on: true, n: "-3" } }, sw: {} }); } catch (e) { badLim = true; }
  check(badLim, "حد سالب مرفوض");
  var LA = VT.newLedger();
  VT.record(LA, Object.assign({ lid: "SA-2026-0001", dk: dk, uu: 20271014, token: "t1" }, fb), Date.UTC(2026, 9, 1));
  check(VT.findLicense(LA, "SA-2026-0001").lim.mu === 2, "السجل يحفظ الحدود");
  var am = VT.amend(LA, "SA-2026-0001", Object.assign({ token: "t2", uu: 20271014, note: "مستخدم ثالث" }, fb, { mu: 3 }), Date.UTC(2026, 10, 1));
  check(am.token === "t2" && am.lim.mu === 3 && am.history.length === 1 && am.history[0].token === "t1", "تعديل الرخصة: رمز جديد بنفس الرقم والقديم في السجل");

  console.log("== حفظ المفتاح مشفّرًا");
  var sealed = await VT.sealKey(vendor.pkcs8, "عبارة مرور طويلة 2026");
  check(sealed.indexOf(VT.bytesToB64(vendor.pkcs8).substring(10, 40)) < 0, "المفتاح المحفوظ مشفّر");
  var opened = await VT.openKey(sealed, "عبارة مرور طويلة 2026");
  check(opened.spki === signer.spki, "يُفتح بعبارة المرور الصحيحة");
  var wrong = false;
  try {
    await VT.openKey(sealed, "عبارة خاطئة 1234");
  } catch (e) {
    wrong = /غير صحيحة/.test(e.message);
  }
  check(wrong, "عبارة خاطئة تُرفض برسالة واضحة");
  var shortPass = false;
  try {
    await VT.sealKey(vendor.pkcs8, "123");
  } catch (e) {
    shortPass = true;
  }
  check(shortPass, "عبارة قصيرة مرفوضة");

  console.log("== إيصال التحرير (توقيع Java/Android بصيغة DER)");
  var body = "v=1;t=release;lid=SA-2026-0001;dk=" + dk + ";n=abc;at=1791900000000";
  var sig = nc.sign("sha256", Buffer.from(body, "utf8"), { key: dev.priv, dsaEncoding: "der" });
  var rel = "SA8-REL:" + VT.b64url(VT.utf8(body)) + "." + VT.b64url(new Uint8Array(sig));
  var vr = await VT.verifyRelease("أرسل لك الإيصال: " + rel, VT.bytesToB64(dev.spki));
  check(vr.ok && vr.lid === "SA-2026-0001" && vr.dk === dk, "إيصال صحيح من الهاتف المسجّل");
  check(!(await VT.verifyRelease(rel, VT.bytesToB64(dev2.spki))).ok, "الإيصال لا يُقبل بمفتاح هاتف آخر");
  var relBad = "SA8-REL:" + VT.b64url(VT.utf8(body.replace("SA-2026-0001", "SA-2026-0009"))) + "." + VT.b64url(new Uint8Array(sig));
  check(!(await VT.verifyRelease(relBad, VT.bytesToB64(dev.spki))).ok, "تغيير رقم الرخصة في الإيصال يُكشف");
  check(!(await VT.verifyRelease("SA8-REQ:xyz", VT.bytesToB64(dev.spki))).ok, "نص ليس إيصالًا");

  console.log("== السجل وقواعد النقل");
  var L = VT.newLedger(), now = Date.UTC(2026, 9, 14, 9);
  check(VT.nextLid(L, now) === "SA-2026-0001", "أول رقم رخصة");
  VT.record(L, { lid: "SA-2026-0001", order: "2026-0153", customer: "=HYPERLINK(\"x\")", dk: dk, pub: VT.bytesToB64(dev.spki), uu: 20271014, token: tok }, now);
  check(VT.nextLid(L, now) === "SA-2026-0002" && L.licenses.length === 1, "الرقم التالي يزيد بعد البيع");
  var again = await VT.reviewRequest(req(), { ledger: L });
  check(again.warnings.some(function (w) { return w.indexOf("لديه رخصة") >= 0; }) && again.warnings.some(function (w) { return w.indexOf("استُعمل") >= 0; }), "تنبيه: الهاتف لديه رخصة ورقم الطلب مستعمل");
  var t1 = VT.canTransfer(L, "SA-2026-0001", now, false);
  check(!t1.ok && t1.reason.indexOf("مفقود") >= 0, "بلا إيصال ولا تسجيل فقد: لا نقل");
  L.licenses[0].lostAt = now;
  var t2 = VT.canTransfer(L, "SA-2026-0001", now + 3 * VT.DAY, false);
  check(!t2.ok && t2.reason.indexOf("4") >= 0, "هاتف مفقود: الانتظار 7 أيام (بقي 4)");
  var t3 = VT.canTransfer(L, "SA-2026-0001", now + 7 * VT.DAY + 1, false);
  check(t3.ok && t3.nextLid === "SA-2026-0001-T1", "بعد 7 أيام: النقل برقم SA-2026-0001-T1");
  var t4 = VT.canTransfer(L, "SA-2026-0001", now, true);
  check(t4.ok, "بإيصال تحرير: النقل فورًا");
  VT.record(L, { lid: t3.nextLid, dk: "x", uu: 20271014 }, now + 8 * VT.DAY);
  VT.markMoved(L, "SA-2026-0001", t3.nextLid);
  check(L.revoked.indexOf("SA-2026-0001") >= 0 && VT.findLicense(L, "SA-2026-0001").status === "moved", "الرخصة القديمة منقولة ومضافة لقائمة الإلغاء");
  check(VT.nextLid(L, now) === "SA-2026-0002", "النقل لا يستهلك رقم بيع جديدًا");
  var t5 = VT.canTransfer(L, "SA-2026-0001-T1", now + 9 * VT.DAY, true);
  check(t5.ok && t5.nextLid === "SA-2026-0001-T2", "النقل الثاني في السنة مسموح");
  VT.record(L, { lid: t5.nextLid, dk: "y", uu: 20271014 }, now + 9 * VT.DAY);
  VT.markMoved(L, "SA-2026-0001-T1", t5.nextLid);
  var t6 = VT.canTransfer(L, "SA-2026-0001-T2", now + 10 * VT.DAY, true);
  check(!t6.ok && t6.reason.indexOf("مرتين") >= 0, "النقل الثالث في السنة مرفوض");
  check(VT.canTransfer(L, "SA-2026-0001-T2", now + 380 * VT.DAY, true).ok, "بعد سنة يُسمح من جديد");
  var csv = VT.ledgerCsv(L);
  check(csv.indexOf("'=HYPERLINK") >= 0 && csv.charCodeAt(0) === 0xfeff, "CSV بترميز UTF-8 ومحمي من حقن المعادلات");
  check(JSON.parse(VT.revocationJson(L)).revoked.length === 2, "قائمة الإلغاء للخادم");
  check(JSON.parse(VT.serverExport(L)).licenses["SA-2026-0001-T2"].dk === "y", "سجل الخادم");
  check(VT.defaultUu(Date.UTC(2026, 9, 14, 12)) === 20271014, "التحديثات سنة من اليوم افتراضيًا");

  console.log("== طلب تفعيل من نسخة الماك (8.1)");
  var macReq = fs.readFileSync(path.join(__dirname, "..", "..", "tests", "data", "mac-request.txt"), "utf8");
  var mrv = await VT.reviewRequest(macReq, {});
  check(mrv.mac === true && mrv.errors.length === 0 && mrv.req.platform === "mac", "طلب الماك يُقبل (توقيع إثبات الحيازة صحيح) " + JSON.stringify(mrv.errors));
  check(mrv.warnings.some(function (w) { return w.indexOf("سلسلة مفاتيح") >= 0; }), "ويُنبَّه البائع أنه بلا تصديق عتادي");
  var mj = JSON.parse(VT.fromUtf8 ? VT.fromUtf8(VT.b64ToBytes(macReq.trim().slice(8))) : Buffer.from(macReq.trim().slice(8), "base64url").toString());
  mj.order = "ORDER-OTHER";
  var mbad = await VT.reviewRequest("SA8-REQ:" + Buffer.from(JSON.stringify(mj)).toString("base64url"), {});
  check(mbad.errors.length > 0, "طلب ماك معدَّل (رقم طلب آخر) يُرفض");
  mj = JSON.parse(Buffer.from(macReq.trim().slice(8), "base64url").toString());
  mj.pkg = "com.other.app";
  check((await VT.reviewRequest("SA8-REQ:" + Buffer.from(JSON.stringify(mj)).toString("base64url"), {})).errors.length > 0, "برنامج آخر يُرفض");

  console.log("== 12.7: هوية المكتب، وعدد أجهزة المكتب، وسجل محاولات الاستخدام لمكتب آخر");
  var OF = "OF-0123456789ABCDEF01";
  var ofReq = JSON.parse(Buffer.from(req().replace(/^SA8-REQ:/, "").trim(), "base64url").toString());
  ofReq.of = OF;
  var log = [{ at: 1763600000000, lid: "SA-2026-0007", of: "OF-AAAAAAAAAAAAAAAAAA", saw: OF }];
  var sigDer = nc.sign("sha256", Buffer.from(VT.misuseBody(log), "utf8"), dev.priv);
  ofReq.mis = log;
  ofReq.misSig = Buffer.from(sigDer).toString("base64url");
  var L127 = VT.newLedger();
  var orv = await VT.reviewRequest("SA8-REQ:" + Buffer.from(JSON.stringify(ofReq)).toString("base64url"), { ledger: L127 });
  check(orv.office && orv.office.of === OF && orv.office.active === 0, "هوية المكتب تُقرأ من الطلب");
  check(orv.misuse && orv.misuse.signed === true && orv.warnings.some(function (w) { return w.indexOf("محاولة غش محتملة") >= 0 && w.indexOf("سليم") >= 0; }),
    "سجل المحاولات المختوم بمفتاح الجهاز يتحقق ويظهر تنبيهًا واضحًا");
  check(orv.errors.length === 0, "التنبيه لا يمنع الإصدار (البائع يقرر)");
  var tamper = JSON.parse(JSON.stringify(ofReq));
  tamper.mis[0].saw = "OF-BBBBBBBBBBBBBBBBBB";
  var trv = await VT.reviewRequest("SA8-REQ:" + Buffer.from(JSON.stringify(tamper)).toString("base64url"), {});
  check(trv.misuse && trv.misuse.signed === false && trv.warnings.some(function (w) { return w.indexOf("لا يتحقق") >= 0; }), "سجل معدَّل: الختم لا يتحقق ويُقال ذلك");
  var pl = VT.payloadOf({ lid: "SA-2026-0100", uu: 20271014, dk: dk, of: OF, tc: 14, tg: 14, md: 4 }, "k1");
  check(pl.indexOf(";of=" + OF) > 0 && pl.indexOf(";tc=14") > 0 && pl.indexOf(";tg=14") > 0 && pl.indexOf(";md=4") > 0, "الرمز يحمل هوية المكتب ومدة تأكيد الوقت ومهلته وعدد الأجهزة");
  var badOf = false;
  try { VT.payloadOf({ lid: "SA-2026-0100", uu: 20271014, dk: dk, of: "OF-x;y" }, "k1"); } catch (e) { badOf = true; }
  check(badOf, "هوية مكتب غير صالحة تُرفض");
  for (var si = 0; si < 4; si++) L127.licenses.push({ lid: "SA-2026-01" + si, of: OF, status: "active" });
  check(!VT.seatCheck(L127, OF, 4).ok && VT.seatCheck(L127, OF, 5).ok && VT.seatCheck(L127, OF, 5).n === 5, "حد أجهزة المكتب: الخامس يُرفض بحد 4 ويُقبل بحد 5");
  L127.licenses[0].status = "moved";
  check(VT.seatCheck(L127, OF, 4).ok, "الرخصة المنقولة لا تُحسب جهازًا للمكتب");
  var tf = VT.fieldsOf(VT.DEFAULT_TEMPLATES[1]);
  check(tf.tc === 14 && tf.tg === 14, "القوالب الافتراضية: تأكيد الوقت كل 14 يومًا ومهلة 14");
  check(VT.describe(tf).some(function (x) { return x.indexOf("تأكيد الوقت من الإنترنت كل 14") >= 0; }), "وصف الرخصة يذكر تأكيد الوقت");
  var appVec = path.join(__dirname, "..", "..", "tests", "data", "request-127.txt");
  if (fs.existsSync(appVec)) {
    var arv = await VT.reviewRequest(fs.readFileSync(appVec, "utf8"), {});
    check(arv.errors.length === 0 && arv.office && /^OF-[0-9A-F]{18}$/.test(arv.office.of) && arv.misuse && arv.misuse.signed === true,
      "طلب حقيقي من التطبيق (12.7): هوية المكتب وسجل المحاولات المختوم يتحققان " + JSON.stringify(arv.errors));
  }

  if (KEYS && fs.existsSync(path.join(KEYS, "vendor-k1-private.pem"))) {
    console.log("== مفاتيح البائع الحقيقية (خارج المشروع)");
    for (var k of ["k1", "k2"]) {
      var real = await VT.importPrivatePem(fs.readFileSync(path.join(KEYS, "vendor-" + k + "-private.pem"), "utf8"));
      check(real.kid === k, k + ": المفتاح الخاص يطابق المفتاح العام المضمّن في التطبيق");
      var rt = await VT.issueToken(real, { lid: "SA-TEST-" + k, uu: 20271014, dk: dk });
      check(!!(await VT.verifyToken(rt, dev.spki)), k + ": رمز موقّع به يتحقق بالمفاتيح المضمّنة");
      fs.writeFileSync(path.join(OUT, "real-" + k + ".json"), JSON.stringify({ devicePub: VT.bytesToB64(dev.spki), token: rt }));
    }
  }

  console.log(fails === 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
  process.exit(fails ? 1 : 0);
}

main().catch(function (e) {
  console.error(e);
  process.exit(1);
});
