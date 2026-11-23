#!/usr/bin/env python3
"""يجمع أداة النقل في ملف HTML واحد يعمل بدون إنترنت: app/src/main/assets/migration-tool.html"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "app", "src", "main", "assets", "migration-tool.html")


def read(p):
    with open(os.path.join(HERE, p), encoding="utf-8") as f:
        return f.read()


def safe_js(s):
    # لا يجوز أن يظهر «</script» داخل السكربت المضمّن، و«<!--» قد يربك المحلل
    return s.replace("</script", "<\\/script").replace("<!--", "<\\!--")


html = read("template.html")
html = html.replace("{{STYLE}}", read("style.css"))
html = html.replace("{{XLSX}}", safe_js(read("vendor/xlsx.full.min.js")))
html = html.replace("{{CORE}}", safe_js(read("core.js")))
html = html.replace("{{UI}}", safe_js(read("ui.js")))
os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(html)
print("wrote", os.path.abspath(OUT), len(html.encode("utf-8")), "bytes")
