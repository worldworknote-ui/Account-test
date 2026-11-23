#!/usr/bin/env python3
"""أداة البائع في متصفح حقيقي (Chromium): المفتاح، الفحص، الإصدار، السجل، النقل بإيصال، وحماية العرض من النصوص الضارة.
الاستخدام: python3 e2e_vendor.py <أداة-البائع.html> <requests.json من make-requests.js> <مجلد مفاتيح البائع> <مجلد الإخراج>"""
import json
import os
import sys
from playwright.sync_api import sync_playwright

HTML, REQS, KEYS, OUT = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
os.makedirs(OUT, exist_ok=True)
R = json.load(open(REQS, encoding="utf-8"))
fails = 0


def check(name, ok, extra=None):
    global fails
    print(("  PASS " if ok else "  FAIL ") + name + ("" if ok or extra is None else " — " + str(extra)[:400]))
    if not ok:
        fails += 1


def text(page, sel):
    return page.inner_text(sel)


with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1100, "height": 900}, accept_downloads=True, locale="ar")
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
    requests = []
    page.on("request", lambda r: requests.append(r.url) if not r.url.startswith(("file:", "data:", "blob:")) else None)
    page.goto("file://" + os.path.abspath(HTML))
    page.wait_for_timeout(300)
    check("تفتح بلا أخطاء", not errors, errors)
    check("بلا مفتاح في البداية", "لا يوجد مفتاح" in text(page, "#keyPill"))

    # ---------------------------------------------------------------- المفتاح
    page.click("[data-tab=keys]")
    page.set_input_files("#keyFile", os.path.join(KEYS, "vendor-k1-private.pem"))
    page.wait_for_selector("#keyPill.on", timeout=10000)
    check("تحميل المفتاح k1", "k1" in text(page, "#keyPill"))
    page.fill("#kp1", "عبارة مرور طويلة للاختبار")
    page.fill("#kp2", "عبارة مرور طويلة للاختبار")
    page.click("#keySeal")
    page.wait_for_selector("#keyMsg .msg.ok", timeout=20000)
    stored = page.evaluate("localStorage.getItem('sa8v.key')")
    pem = open(os.path.join(KEYS, "vendor-k1-private.pem")).read()
    body = "".join(pem.strip().splitlines()[1:-1])
    check("المفتاح محفوظ مشفّرًا (لا يظهر نصه)", stored and body[20:60] not in stored)
    page.reload()
    page.wait_for_timeout(300)
    check("بعد إعادة الفتح: لا مفتاح حتى تُكتب العبارة", "لا يوجد مفتاح" in text(page, "#keyPill"))
    page.click("[data-tab=keys]")
    page.fill("#kp1", "خطأ خطأ خطأ خطأ")
    page.click("#keyOpen")
    page.wait_for_selector("#keyMsg .msg.err", timeout=20000)
    check("عبارة خاطئة تُرفض", "غير صحيحة" in text(page, "#keyMsg"))
    page.fill("#kp1", "عبارة مرور طويلة للاختبار")
    page.click("#keyOpen")
    page.wait_for_selector("#keyPill.on", timeout=20000)
    check("فتح المفتاح المحفوظ", "k1" in text(page, "#keyPill"))

    # ---------------------------------------------------------------- تفعيل جديد
    page.click("[data-tab=sale]")
    check("زر الإصدار معطّل قبل الفحص", page.is_disabled("#issue"))
    page.fill("#reqText", R["good"])
    page.click("#reqCheck")
    page.wait_for_selector("#reqOut .facts", timeout=10000)
    out = text(page, "#reqOut")
    check("الطلب يُقرأ من وسط الرسالة", "2026-0153" in out and "Samsung" in out, out)
    check("تحذير: بلا سلسلة تصديق", "سلسلة تصديق" in out, out)
    check("زر الإصدار مفعّل", not page.is_enabled("#issue") is False)
    check("رقم الرخصة التالي", page.input_value("#sLid") == R["lid"], page.input_value("#sLid"))
    page.fill("#sCustomer", "مكتب الأمل للصرافة")
    page.fill("#sPhone", "+90 555 000 0000")
    page.fill("#sPrice", "150 USD")
    page.click("#issue")
    page.wait_for_selector("#issueOut textarea.token", timeout=10000)
    token = page.input_value("#issueOut textarea.token")
    check("صدر الرمز", token.count(".") == 1 and len(token) > 100, token[:80])
    fields = page.evaluate("async ([t, pub]) => await VT.verifyToken(t, VT.b64ToBytes(pub))", [token, R["pub1"]])
    check("الرمز يتحقق بالمفاتيح المضمّنة في التطبيق", fields and fields.get("lid") == R["lid"] and fields.get("ft") == "all" and fields.get("kid") == "k1", fields)
    with open(os.path.join(OUT, "e2e-token.json"), "w", encoding="utf-8") as f:
        json.dump({"devicePub": R["pub1"], "token": token, "lid": R["lid"]}, f)
    check("زر الإصدار يتعطل بعد الإصدار (لا تكرار)", page.is_disabled("#issue"))

    # طلب بسلسلة حقيقية لتطبيق آخر
    page.fill("#reqText", R["other"])
    page.click("#reqCheck")
    page.wait_for_selector("#reqOut .msg.err", timeout=20000)
    out = text(page, "#reqOut")
    check("سلسلة Google لتطبيق آخر: أخطاء واضحة", "التحدي" in out and "تطبيق آخر" in out, out)
    check("الإصدار معطّل مع الأخطاء", page.is_disabled("#issue"))
    page.check("#sOverride")
    check("التجاوز الصريح يفعّل الزر", page.is_enabled("#issue"))
    page.uncheck("#sOverride")

    # نص ضار في الطلب يُعرض نصًا
    page.fill("#reqText", R["xss"])
    page.click("#reqCheck")
    page.wait_for_selector("#reqOut .facts", timeout=10000)
    check("نص HTML في الطلب لا يُنفَّذ", page.evaluate("!window.__xss && !document.querySelector('#reqOut img')") and "<img" in text(page, "#reqOut .facts"))

    # ---------------------------------------------------------------- السجل
    page.click("[data-tab=ledger]")
    page.wait_for_timeout(200)
    t = text(page, "#lTable")
    check("السجل يعرض البيع", R["lid"] in t and "مكتب الأمل" in t, t)
    with page.expect_download() as d:
        page.click("#lCsv")
    csv = open(d.value.path(), encoding="utf-8-sig").read()
    check("تصدير CSV", R["lid"] in csv and "مكتب الأمل للصرافة" in csv)
    with page.expect_download() as d:
        page.click("#lBackup")
    bk = json.load(open(d.value.path(), encoding="utf-8"))
    check("نسخة السجل الاحتياطية", bk["licenses"][0]["lid"] == R["lid"] and bk["licenses"][0]["pub"] == R["pub1"])

    # ---------------------------------------------------------------- النقل بإيصال
    page.click("[data-tab=move]")
    page.wait_for_selector("#mList .pick", timeout=5000)
    page.click("#mList .pick")
    page.wait_for_selector("#mInfo .msg", timeout=5000)
    check("بلا إيصال: النقل غير مسموح بعد", "مفقود" in text(page, "#mInfo"), text(page, "#mInfo"))
    page.fill("#relText", R["receipt"])
    page.click("#relCheck")
    page.wait_for_selector("#relOut .msg", timeout=10000)
    check("إيصال التحرير يتحقق", "إيصال صحيح" in text(page, "#relOut"), text(page, "#relOut"))
    check("النقل مسموح برقم -T1", R["lid"] + "-T1" in text(page, "#mInfo"), text(page, "#mInfo"))
    page.fill("#mReqText", R["second"])
    page.click("#mReqCheck")
    page.wait_for_selector("#mReqOut .facts", timeout=10000)
    page.click("#mIssue")
    page.wait_for_selector("#mIssueOut textarea.token", timeout=10000)
    tok2 = page.input_value("#mIssueOut textarea.token")
    f2 = page.evaluate("async ([t, pub]) => await VT.verifyToken(t, VT.b64ToBytes(pub))", [tok2, R["pub2"]])
    check("رخصة الهاتف الجديد", f2 and f2.get("lid") == R["lid"] + "-T1", f2)
    led = page.evaluate("JSON.parse(localStorage.getItem('sa8v.ledger'))")
    old = [l for l in led["licenses"] if l["lid"] == R["lid"]][0]
    check("القديمة «منقولة» ومضافة لقائمة الإلغاء", old["status"] == "moved" and R["lid"] in led["revoked"], old["status"])
    check("رقم البيع التالي لم يتغير بالنقل", led["next"] == 2, led["next"])

    # ---------------------------------------------------------------- الهاتف
    m = ctx.new_page()
    m.set_viewport_size({"width": 390, "height": 840})
    m.goto("file://" + os.path.abspath(HTML))
    m.wait_for_timeout(300)
    for tab in ["sale", "move", "ledger", "keys"]:
        m.click("[data-tab=%s]" % tab)
        m.wait_for_timeout(100)
        w = m.evaluate("document.scrollingElement.scrollWidth - window.innerWidth")
        check("عرض الهاتف 390px بلا تمرير أفقي (" + tab + ")", w <= 0, w)
    page.screenshot(path=os.path.join(OUT, "vendor-desktop.png"), full_page=True)
    m.click("[data-tab=sale]")
    m.screenshot(path=os.path.join(OUT, "vendor-mobile.png"), full_page=True)
    check("لا اتصال بالشبكة", not requests, requests)
    check("بلا أخطاء في الصفحة", not errors, errors)
    b.close()

print("ALL PASSED" if fails == 0 else "%d FAILED" % fails)
sys.exit(1 if fails else 0)
