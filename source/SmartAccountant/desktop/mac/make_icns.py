#!/usr/bin/env python3
"""يبني AppIcon.icns من صور PNG (بلا أدوات الماك): make_icns.py <مجلد الصور> <الملف الناتج>"""
import struct, sys, os
d, out = sys.argv[1], sys.argv[2]
# النوع ← المقاس بالبكسل (صيغ PNG المدعومة منذ macOS 10.7)
types = [(b"icp4", 16), (b"icp5", 32), (b"icp6", 64), (b"ic07", 128), (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024),
         (b"ic11", 32), (b"ic12", 64), (b"ic13", 256), (b"ic14", 512)]
chunks = []
for t, s in types:
    data = open(os.path.join(d, "icon_%d.png" % s), "rb").read()
    chunks.append(t + struct.pack(">I", len(data) + 8) + data)
body = b"".join(chunks)
open(out, "wb").write(b"icns" + struct.pack(">I", len(body) + 8) + body)
print("icns", len(body) + 8)
