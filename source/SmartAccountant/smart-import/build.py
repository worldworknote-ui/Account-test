#!/usr/bin/env python3
"""يجمع «الاستيراد الذكي» في ملف HTML واحد يعمل بدون إنترنت: app/src/main/assets/smart-import.html
يستخدم core.js ومكتبة SheetJS من أداة النقل كما هما (للقراءة فقط)."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MT = os.path.join(HERE, "..", "migration-tool")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "app", "src", "main", "assets", "smart-import.html")


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def safe_js(s):
    return s.replace("</script", "<\\/script").replace("<!--", "<\\!--")


html = read(os.path.join(HERE, "template.html"))
html = html.replace("{{STYLE}}", read(os.path.join(HERE, "style.css")))
html = html.replace("{{XLSX}}", safe_js(read(os.path.join(MT, "vendor", "xlsx.full.min.js"))))
html = html.replace("{{CORE}}", safe_js(read(os.path.join(MT, "core.js"))))
html = html.replace("{{ENGINE}}", safe_js(read(os.path.join(HERE, "engine.js"))))
html = html.replace("{{UI}}", safe_js(read(os.path.join(HERE, "ui.js"))))
os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(html)
print("wrote", os.path.abspath(OUT), len(html.encode("utf-8")), "bytes")
