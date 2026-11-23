#!/usr/bin/env python3
"""يجمع أداة البائع في ملف HTML واحد يعمل بدون إنترنت.
الأداة للبائع وحده: لا توضع داخل التطبيق ولا تُرسل للعملاء (لا تحوي مفاتيح خاصة، لكنها أداة إصدار الرخص).
الاستعمال: python3 vendor-tool/build.py [مسار الملف الناتج]"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "dist", "أداة-البائع-المحاسب-الذكي.html")


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def safe_js(s):
    return s.replace("</script", "<\\/script").replace("<!--", "<\\!--")


html = read(os.path.join(HERE, "template.html"))
html = html.replace("{{STYLE}}", read(os.path.join(HERE, "..", "smart-import", "style.css")) + "\n" + read(os.path.join(HERE, "style.css")))
html = html.replace("{{CORE}}", safe_js(read(os.path.join(HERE, "core.js")) + "\n" + read(os.path.join(HERE, "qr.js"))))
html = html.replace("{{UI}}", safe_js(read(os.path.join(HERE, "ui.js"))))
os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(html)
print("wrote", os.path.abspath(OUT), len(html.encode("utf-8")), "bytes")
