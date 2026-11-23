#!/usr/bin/env python3
"""يولّد com/smart/hawala/R.java للكمبيوتر من مجلد res في تطبيق الهاتف (المعرّفات نفسها بالأسماء)."""
import os, re, sys, xml.etree.ElementTree as ET
res, out = sys.argv[1], sys.argv[2]
groups = {}
def add(kind, name):
    groups.setdefault(kind, [])
    if name not in groups[kind]: groups[kind].append(name)
for d in sorted(os.listdir(res)):
    full = os.path.join(res, d)
    if not os.path.isdir(full): continue
    kind = d.split('-')[0]
    if kind == 'values':
        for f in sorted(os.listdir(full)):
            t = ET.parse(os.path.join(full, f)).getroot()
            for e in t:
                n = e.get('name')
                if not n: continue
                tag = e.tag
                if tag == 'item' and e.get('type'): add(e.get('type'), n)
                elif tag in ('string', 'color', 'style', 'dimen', 'bool', 'integer'): add(tag, n.replace('.', '_'))
        continue
    for f in sorted(os.listdir(full)):
        add(kind, os.path.splitext(f)[0])
# المعرّفات المطلوبة في الكود ولا ملف لها
add('mipmap', 'ic_launcher')
strings = {}
sf = os.path.join(res, 'values', 'strings.xml')
if os.path.exists(sf):
    for e in ET.parse(sf).getroot():
        if e.tag == 'string': strings[e.get('name')] = ''.join(e.itertext())
base = {'drawable': 0x7f010000, 'font': 0x7f020000, 'id': 0x7f030000, 'style': 0x7f040000, 'string': 0x7f050000, 'mipmap': 0x7f060000,
        'color': 0x7f070000, 'xml': 0x7f080000, 'layout': 0x7f090000, 'dimen': 0x7f0a0000, 'raw': 0x7f0b0000, 'bool': 0x7f0c0000, 'integer': 0x7f0d0000}
L = ['package com.smart.hawala;', '', '/** مولَّد من res (desktop/gen-r.py) — لا يُعدَّل باليد. */', 'public final class R {']
for kind in sorted(groups):
    b = base.get(kind, 0x7f0f0000)
    L.append('    public static final class %s {' % kind)
    for i, n in enumerate(groups[kind]):
        L.append('        public static final int %s = 0x%08x;' % (n, b + i))
    L.append('    }')
L.append('    public static final String[][] STRINGS = {')
for k, v in strings.items():
    L.append('        {"%s", "%s"},' % (k, v.replace('\\', '\\\\').replace('"', '\\"')))
L.append('    };')
L.append('}')
os.makedirs(os.path.dirname(out), exist_ok=True)
open(out, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
