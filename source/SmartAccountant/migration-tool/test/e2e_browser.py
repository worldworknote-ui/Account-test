#!/usr/bin/env python3
"""اختبار الأداة في متصفح حقيقي (Chromium): اختيار ملف، مراجعة، إنشاء ملف ‎.sba وتنزيله، وصور للشاشة."""
import json
import os
import sys
from playwright.sync_api import sync_playwright

HTML, SAMPLES, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
fails = 0


def check(name, ok, extra=None):
    global fails
    print(("  PASS " if ok else "  FAIL ") + name + ("" if ok or extra is None else " — " + str(extra)))
    if not ok:
        fails += 1


with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1100, "height": 900}, accept_downloads=True, locale="ar")
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)

    # ------------------------------------------------------------- A: أرصدة + حركات
    print("== sample A")
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "sample-A.xlsx"))
    page.wait_for_selector("#fileInfo.ok", timeout=15000)
    page.wait_for_timeout(800)
    check("both sections enabled", page.is_checked("#openOn") and page.is_checked("#txOn"))
    page.screenshot(path=os.path.join(OUT, "A-1-setup.png"), full_page=True)
    stats = page.inner_text("#review .stats")
    check("stats show 3 offices / 4 tx / 5 openings", "3\nمكتب" in stats and "4\nحركة" in stats and "5\nرصيد سابق" in stats, stats)
    check("no rejected rows", "0\nصف مرفوض" in stats, stats)
    review = page.inner_text("#review")
    check("balance كاش شام USD 1,305.25 shown", "1,305.25" in review, review[:400])
    check("SYP 1,250,000 shown", "1,250,000" in review)
    check("similar names warning", "متشابهة" in review)
    page.screenshot(path=os.path.join(OUT, "A-2-review.png"), full_page=True)
    # الإنشاء
    page.fill("#myOffice", "مكتب الاختبار")
    page.fill("#pw1", "pass-A-1234")
    page.fill("#pw2", "pass-A-1234")
    check("make disabled before confirmation", page.is_disabled("#make"))
    page.check("#confirmed")
    check("make enabled after confirmation", page.is_enabled("#make"))
    with page.expect_download(timeout=30000) as dl:
        page.click("#make")
    d = dl.value
    d.save_as(os.path.join(OUT, "A.sba"))
    check("download name .sba", d.suggested_filename.endswith(".sba"), d.suggested_filename)
    page.wait_for_selector("#makeMsg .msg.ok", timeout=30000)
    with page.expect_download() as dl2:
        page.click("#csv")
    dl2.value.save_as(os.path.join(OUT, "A-report.csv"))
    csv = open(os.path.join(OUT, "A-report.csv"), encoding="utf-8-sig").read()
    check("csv report has office rows", "كاش شام" in csv and "لنا 1,305.25" in csv, csv[:300])
    page.screenshot(path=os.path.join(OUT, "A-3-done.png"), full_page=True)
    # عكس الاتجاه يقلب الأرصدة
    page.check("#reverse")
    page.wait_for_timeout(400)
    row = page.inner_text("#review table tr:nth-child(2)")
    check("reverse toggle recomputes (SYP row flips to لنا)", "لنا" in row and "علينا" not in row, row)
    page.uncheck("#reverse")

    # ------------------------------------------------------------- B: دفتر Excel (ورقة لكل مكتب)
    print("== sample B (ledger)")
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "sample-B-ledger.xlsx"))
    page.wait_for_selector("#fileInfo.ok", timeout=15000)
    page.wait_for_timeout(800)
    check("only transactions enabled", page.is_checked("#txOn") and not page.is_checked("#openOn"))
    check("office from sheet name", page.is_checked("input[name=tofrom][value=sheet]"))
    review = page.inner_text("#review")
    check("asks for currency (fixed USD default) and balance column matches", "تطابق عمود الرصيد" in review, review[:600])
    page.screenshot(path=os.path.join(OUT, "B-1-review.png"), full_page=True)
    page.fill("#pw1", "كلمة سر عربية ١٢٣")
    page.fill("#pw2", "كلمة سر عربية ١٢٣")
    page.check("#confirmed")
    with page.expect_download(timeout=30000) as dl:
        page.click("#make")
    dl.value.save_as(os.path.join(OUT, "B.sba"))

    # ------------------------------------------------------------- D: كشوفات حساب متعددة
    print("== statements (many files)")
    sdir = os.path.join(SAMPLES, "statements")
    good = [os.path.join(sdir, f) for f in sorted(os.listdir(sdir))]
    bad = os.path.join(SAMPLES, "statements-bad-fajr.xlsx")
    page.goto("file://" + HTML)
    page.set_input_files("#file", good + [bad])
    page.wait_for_selector("#fileInfo.ok", timeout=20000)
    page.wait_for_timeout(800)
    info = page.inner_text("#fileInfo")
    check("5 files read as 5 statements", "5 ملف" in info and "5 كشف" in info, info)
    check("statements panel shown, single setup hidden", page.is_visible("#stmtPanel") and not page.is_visible("#singleSetup"))
    review = page.inner_text("#review")
    check("tampered statement reported and not imported", "لم يُنقل" in review and "لا يساوي" in review, review[:500])
    table = page.inner_text("#stmtTable")
    check("statement table shows per-file status", "رصيدًا مطابقًا" in table and "لا يساوي" in table, table[:400])
    page.screenshot(path=os.path.join(OUT, "D-1-with-bad.png"), full_page=True)
    page.set_input_files("#file", good)
    page.wait_for_timeout(1200)
    review = page.inner_text("#review")
    stats = page.inner_text("#review .stats")
    check("all 4 statements verified", "كل الكشوفات (4) صحيحة" in review, review[:300])
    check("3 offices, 8 tx, 2 openings", "3\nمكتب" in stats and "8\nحركة" in stats and "2\nرصيد سابق" in stats, stats)
    check("الخال merged across USD and EUR", review.count("الخال") >= 2 and "495.00" in review and "1,010.00" in review, review[:800])
    page.check("#mergeCom")
    page.wait_for_timeout(600)
    check("merge Com → 7 tx", "7\nحركة" in page.inner_text("#review .stats"), page.inner_text("#review .stats"))
    page.uncheck("#mergeCom")
    page.click("label:has(input[name=stwhat][value=bal])")
    page.wait_for_timeout(600)
    stats = page.inner_text("#review .stats")
    check("balances only → 0 tx, 4 openings", "0\nحركة" in stats and "4\nرصيد سابق" in stats, stats)
    page.click("label:has(input[name=stwhat][value=all])")
    page.wait_for_timeout(600)
    page.screenshot(path=os.path.join(OUT, "D-2-review.png"), full_page=True)
    page.fill("#pw1", "كشوفات-2026")
    page.fill("#pw2", "كشوفات-2026")
    page.check("#confirmed")
    with page.expect_download(timeout=30000) as dl:
        page.click("#make")
    dl.value.save_as(os.path.join(OUT, "D.sba"))
    page.wait_for_selector("#makeMsg .msg.ok", timeout=30000)
    # إضافة ملف لاحقًا
    page.set_input_files("#moreFiles", [bad])
    page.wait_for_timeout(1000)
    check("add more files appends (5 statements)", "5 كشف" in page.inner_text("#fileInfo"), page.inner_text("#fileInfo"))
    # عرض الهاتف للكشوفات
    mob0 = b.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2)
    mp0 = mob0.new_page()
    mp0.goto("file://" + HTML)
    mp0.set_input_files("#file", good)
    mp0.wait_for_selector("#fileInfo.ok", timeout=15000)
    mp0.wait_for_timeout(800)
    sw0 = mp0.evaluate("document.documentElement.scrollWidth")
    check("statements: no horizontal page scroll on phone", sw0 <= 391, sw0)
    mp0.screenshot(path=os.path.join(OUT, "D-phone.png"), full_page=True)
    mob0.close()

    # ------------------------------------------------------------- عرض الهاتف
    mob = b.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2)
    mp = mob.new_page()
    mp.goto("file://" + HTML)
    mp.set_input_files("#file", os.path.join(SAMPLES, "sample-A.xlsx"))
    mp.wait_for_selector("#fileInfo.ok", timeout=15000)
    mp.wait_for_timeout(600)
    sw = mp.evaluate("document.documentElement.scrollWidth")
    check("no horizontal page scroll on phone width", sw <= 391, sw)
    mp.screenshot(path=os.path.join(OUT, "M-phone.png"), full_page=False)
    check("no page errors", not errors, errors)
    b.close()

json.dump({
    "A": {"password": "pass-A-1234", "offices": 3, "tx": 4, "openings": 5, "entries": 10, "lines": 20,
          "balances": [["كاش شام", "USD", 130525], ["كاش شام", "SYP", -1250000], ["الأمين للصرافة", "USD", -20000],
                       ["الأمين للصرافة", "EUR", 5000], ["الامين للصرافه", "USD", 1000]]},
    "B": {"password": "كلمة سر عربية ١٢٣", "offices": 2, "tx": 4, "openings": 1, "entries": 5, "lines": 10,
          "balances": [["كاش شام", "USD", 90000], ["الأمين", "USD", -10000]]},
    "D": {"password": "كشوفات-2026", "offices": 3, "tx": 8, "openings": 2, "entries": 10, "lines": 20,
          "balances": [["الخال", "USD", 49500], ["الخال", "EUR", -10000], ["النور", "USD", 35000], ["الأمل", "TRY", -101000]]}
}, open(os.path.join(OUT, "expect.json"), "w", encoding="utf-8"), ensure_ascii=False)
print("ALL BROWSER TESTS PASSED" if fails == 0 else str(fails) + " FAILED")
sys.exit(1 if fails else 0)
