#!/bin/bash
# يبني بيئة جافا مصغّرة للماك (≈24 ميغابايت بعد ضغط zip) من وحدات Temurin، تكفي البرنامج وحده:
#   JLINK=<jdk-21.0.8 على هذا الجهاز>/bin/jlink  JMODS=<jdk-21.0.8 للماك>/Contents/Home/jmods  bash make-runtime.sh <مجلد الإخراج>
# يجب أن يكون إصدار jlink مطابقًا تمامًا لإصدار وحدات الماك (مثلًا 21.0.8+9 للاثنين). تبقى توقيعات Adoptium على الملفات التنفيذية كما هي
# (لا يُستعمل --strip-debug لأنه يعدّل الملفات التنفيذية فتسقط توقيعاتها). الوحدات بلا ضغط داخلي لأن ضغط ملف zip الخارجي لها أفضل.
set -e
OUT="$1"
rm -rf "$OUT"
"$JLINK" --module-path "$JMODS" \
  --add-modules java.base,java.desktop,java.sql,jdk.crypto.ec,jdk.unsupported \
  --exclude-files='**/libjsound.dylib,**/libsplashscreen.dylib,**/bin/keytool' \
  --strip-java-debug-attributes --no-header-files --no-man-pages --compress=zip-0 --output "$OUT"
echo "RUNTIME OK: $OUT ($(du -sh "$OUT" | cut -f1))"
