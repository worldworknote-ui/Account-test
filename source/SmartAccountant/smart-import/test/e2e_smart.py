#!/usr/bin/env python3
"""«الاستيراد الذكي» في متصفح حقيقي (Chromium): اختيار الملفات، الفهم، التعديل، التحقق، إنشاء ملف ‎.sba وتقرير الأرصدة.
الاستخدام: python3 e2e_smart.py <smart-import.html> <مجلد العينات من smart.test.js> <مجلد الإخراج>"""
import os
import sys
from playwright.sync_api import sync_playwright

HTML, SAMPLES, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
fails = 0


def check(name, ok, extra=None):
    global fails
    print(("  PASS " if ok else "  FAIL ") + name + ("" if ok or extra is None else " — " + str(extra)[:600]))
    if not ok:
        fails += 1


def wait_ready(page):
    page.wait_for_selector("#fileInfo .msg.ok", timeout=60000)
    page.wait_for_function("!document.getElementById('busy')", timeout=60000)
    page.wait_for_timeout(300)


def recalc(page):
    page.wait_for_timeout(350)
    page.wait_for_function("!document.getElementById('busy')", timeout=60000)
    page.wait_for_timeout(200)


with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1100, "height": 900}, accept_downloads=True, locale="ar")
    page = ctx.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)

    # ------------------------------------------------------------- S1: سجل حركات + نسخة مفلترة + معادلات
    print("== S1 journal")
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "S1-journal.xlsx"))
    wait_ready(page)
    und = page.inner_text("#understand")
    check("journal and filtered copy understood", "سجل حركات" in und and "نسخة مفلترة" in und and "كشف النور" in und, und[:400])
    check("names list shown", "قائمة أسماء الحسابات" in und and "5 اسمًا" in und, und)
    att = page.inner_text("#attention")
    check("attention lists missing direction / currency / account / future", all(x in att for x in ["بلا اتجاه", "بلا عملة", "بلا اسم حساب", "تاريخ في المستقبل"]), att[:500])
    rv = page.inner_text("#review")
    check("all accuracy checks pass", "✗" not in rv.split("مكتب\n")[0] and "طابقت كلها" in rv and "يطابق رصيده بعد النقل" in rv, rv[:800])
    check("balance مكتب النور USD 1,850.50 shown", "1,850.50" in rv, rv[:1500])
    page.screenshot(path=os.path.join(OUT, "S1-full.png"), full_page=True)
    # البحث في الأرصدة
    page.fill("#q", "النور")
    page.wait_for_timeout(150)
    check("search filters the balances table (7 rows incl. 2 listed offices at 0)", "2 من 7" in page.inner_text("#qn"), page.inner_text("#qn"))
    page.fill("#q", "")
    # إعادة تسمية توحّد مكتبين
    page.click('details[data-k="names"] > summary')
    inp = page.locator('input[data-rename="الامين"]')
    inp.fill("الأمين")
    inp.dispatch_event("change")
    recalc(page)
    rv2 = page.inner_text("#review")
    check("renaming «الامين» to «الأمين» merges them (USD 100 + 200 = علينا 300.00)", "300.00" in rv2 and "4\nمكتب" in rv2, rv2[:900])
    # الإنشاء
    page.fill("#pw1", "smart-1234")
    page.fill("#pw2", "smart-1234")
    check("make disabled before confirmation", page.is_disabled("#make"))
    page.check("#confirmed")
    with page.expect_download(timeout=60000) as dl:
        page.click("#make")
    d = dl.value
    d.save_as(os.path.join(OUT, "S1-ui.sba"))
    check("download is .sba", d.suggested_filename.endswith(".sba") and "استيراد-ذكي" in d.suggested_filename, d.suggested_filename)
    page.wait_for_selector("#makeMsg .msg.ok", timeout=60000)
    with page.expect_download() as d2:
        page.click("#csv")
    d2.value.save_as(os.path.join(OUT, "S1-report.csv"))
    csv = open(os.path.join(OUT, "S1-report.csv"), encoding="utf-8-sig").read()
    check("report has balances and excluded rows", "مكتب النور" in csv and "بلا اتجاه" in csv and "بلا تاريخ صالح" in csv, csv[:300])

    # ------------------------------------------------------------- S2: ورقة لكل مكتب بعمود رصيد
    print("== S2 ledgers")
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "S2-ledgers.xlsx"))
    wait_ready(page)
    und = page.inner_text("#understand")
    check("account from sheet name, Sheet3 needs an account", "مكتب النور" in und and "حركات بلا اسم حساب" in und, und[:600])
    rv = page.inner_text("#review")
    check("running balance verified row by row", "طابقت رصيدنا التراكمي سطرًا بسطر" in rv and "رصيد سابق مستنتج" in rv, rv[:900])
    check("«in your file» column all matching", rv.count("مطابق") >= 2 and "غير مطابق" not in rv, rv[:1500])
    page.select_option('select[data-accfrom="t2"]', "sheet")
    recalc(page)
    rv = page.inner_text("#review")
    check("choosing «sheet name» imports Sheet3 (+100 −40 +15 = لنا 75.00)", "Sheet3" in rv and "75.00" in rv, rv[:1500])
    page.screenshot(path=os.path.join(OUT, "S2-full.png"), full_page=True)

    # ------------------------------------------------------------- S3: إشارة المبلغ
    print("== S3 signed")
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "S3-signed.xlsx"))
    wait_ready(page)
    rv = page.inner_text("#review")
    check("positive = لنا by default (الخال USD لنا 450.00)", "450.00" in rv)
    page.click('input[name="pos|t0"][value="C"] >> xpath=..')
    recalc(page)
    row = page.locator("#balTable tr", has_text="الخال").filter(has_text="USD").inner_text()
    check("switching the sign makes it علينا 450.00", "علينا" in row and "450.00" in row, row)

    # ------------------------------------------------------------- ملفان معًا: ملف لكل مكتب بعمود أجرة
    print("== two files with fee column")
    page.goto("file://" + HTML)
    page.set_input_files("#file", [os.path.join(SAMPLES, "الربيع.xlsx"), os.path.join(SAMPLES, "السلام.xlsx")])
    wait_ready(page)
    vals = page.inner_text("#values")
    check("fee direction learnt from the balance column", "عرفتُ اتجاهها من عمود الرصيد" in vals, vals[:600])
    rv = page.inner_text("#review")
    check("balances with fees (الربيع لنا 715.00, السلام علينا 197.00)", "715.00" in rv and "197.00" in rv, rv[:1200])
    page.screenshot(path=os.path.join(OUT, "fees-full.png"), full_page=True)

    # ------------------------------------------------------------- ملخص بمعادلات
    print("== S5 summary")
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "S5-summary.xlsx"))
    wait_ready(page)
    und = page.inner_text("#understand")
    check("summaries are for checking only", und.count("ملخص أرصدة محسوب") == 2, und[:800])
    rv = page.inner_text("#review")
    check("summary tables match the import", rv.count("تطابق أرصدة المكاتب بعد النقل") == 2, rv[:900])

    # ------------------------------------------------------------- CSV
    print("== S6 csv")
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "S6-journal.csv"))
    wait_ready(page)
    rv = page.inner_text("#review")
    check("CSV balances (الشام لنا 955.50، حلب علينا 800.00)", "955.50" in rv and "800.00" in rv, rv[:900])

    # الهاتف بعرض صغير
    page.set_viewport_size({"width": 400, "height": 860})
    page.goto("file://" + HTML)
    page.set_input_files("#file", os.path.join(SAMPLES, "S2-ledgers.xlsx"))
    wait_ready(page)
    sw = page.evaluate("document.documentElement.scrollWidth")
    check("no horizontal page scroll at phone width", sw <= 400, sw)
    page.screenshot(path=os.path.join(OUT, "mobile.png"), full_page=True)

    check("no page errors", not errors, errors)
    b.close()

print("ALL SMART IMPORT E2E TESTS PASSED" if fails == 0 else str(fails) + " FAILED")
sys.exit(0 if fails == 0 else 1)
