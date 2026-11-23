#!/bin/bash
# يبني SmartAccountant.app («المحاسب الذكي») للماك: ملف zip لكل معالج (Apple Silicon وIntel)، كل واحد ببيئة جافا مصغّرة مضمّنة:
#   RT_ARM=<بيئة arm64 من mac/make-runtime.sh>  RT_X64=<بيئة x64>
#   JPKG_ARM=<jdk.jpackage.jmod من JDK الماك arm64>  JPKG_X64=<jdk.jpackage.jmod من JDK الماك x64>
#   RCODESIGN=<أداة rcodesign من apple-codesign>   bash desktop/package-mac.sh <مجلد الإخراج>
# المشغّل ملف تنفيذي أصلي للماك (مشغّل jpackage من Temurin 21) وليس سكربتًا، والحزمة كلها موقّعة توقيعًا محليًا (ad-hoc):
# macOS الحديث (Sequoia وTahoe) يرفض فتح حزمة مشغّلها سكربت غير موقّع برسالة «لا يمكن فتح التطبيق».
# التخطيط مثل jpackage: Contents/MacOS/SmartAccountant + Contents/app/{SmartAccountant.cfg,smart-accountant.jar}
#                        + Contents/runtime/Contents/{Info.plist,Home/...}
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
P="$(dirname "$HERE")"
OUT="${1:-$HERE/dist}"
VERSION=$(grep -o 'VERSION = "[^"]*"' "$P/app/src/main/java/com/smart/hawala/BuildInfo.java" | cut -d'"' -f2)
BUILD=$(grep -o 'BUILD_DATE = [0-9]*' "$P/app/src/main/java/com/smart/hawala/BuildInfo.java" | grep -o '[0-9]*')
[ -x "$RT_ARM/bin/java" ] && [ -x "$RT_X64/bin/java" ] || { echo "حدّد RT_ARM وRT_X64 (انظر desktop/mac/make-runtime.sh)"; exit 1; }
[ -f "$JPKG_ARM" ] && [ -f "$JPKG_X64" ] || { echo "حدّد JPKG_ARM وJPKG_X64 (ملف jmods/jdk.jpackage.jmod من JDK الماك لكل معالج)"; exit 1; }
[ -x "$RCODESIGN" ] || { echo "حدّد RCODESIGN (https://github.com/indygreg/apple-platform-rs — apple-codesign)"; exit 1; }
[ -f "$R8_JAR" ] || { echo "حدّد R8_JAR (cmdline-tools/lib/r8.jar من أدوات أندرويد) لتعمية النسخة"; exit 1; }

# 12.6: نسخة التوزيع معمّاة دائمًا (الخريطة في desktop/build/obf-map.txt، لا تدخل الحزمة)
OBFUSCATE=1 bash "$HERE/build.sh"
mkdir -p "$OUT"
ICONS="$OUT/icons"; rm -rf "$ICONS"
java -cp "$HERE/build/smart-accountant.jar" com.smart.hawala.desk.IconGen "$ICONS"

# مشغّل jpackage الأصلي من ملف الوحدة (zip بعد ترويسة 4 بايت)
launcher() {
  python3 - "$1" "$2" <<'EOF'
import sys, zipfile, io
d = open(sys.argv[1], 'rb').read()[4:]
z = zipfile.ZipFile(io.BytesIO(d))
open(sys.argv[2], 'wb').write(z.read('classes/jdk/jpackage/internal/resources/jpackageapplauncher'))
EOF
  chmod 755 "$2"
}

for pair in "arm64:$RT_ARM:$JPKG_ARM:AppleSilicon" "x64:$RT_X64:$JPKG_X64:Intel"; do
  IFS=: read -r arch rt jpkg label <<< "$pair"
  STAGE="$OUT/stage-$arch"
  rm -rf "$STAGE"; mkdir -p "$STAGE"
  APP="$STAGE/SmartAccountant.app"
  C="$APP/Contents"
  mkdir -p "$C/MacOS" "$C/Resources/ar.lproj" "$C/Resources/en.lproj" "$C/app" "$C/runtime/Contents"
  sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" "$HERE/mac/Info.plist" > "$C/Info.plist"
  printf "APPLSACC" > "$C/PkgInfo"
  cp "$HERE/mac/InfoPlist.strings" "$C/Resources/ar.lproj/"
  cp "$HERE/mac/InfoPlist.strings" "$C/Resources/en.lproj/"
  launcher "$jpkg" "$C/MacOS/SmartAccountant"
  file "$C/MacOS/SmartAccountant" | grep -q "Mach-O 64-bit.*$( [ "$arch" = arm64 ] && echo arm64 || echo x86_64)" || { echo "المشغّل ليس لمعالج $arch"; exit 1; }
  cp "$HERE/mac/SmartAccountant.cfg" "$C/app/"
  cp "$HERE/build/smart-accountant.jar" "$C/app/"
  # المكتبة الأصلية لهذا المعالج فقط داخل البرنامج
  if [ "$arch" = "arm64" ]; then other="org/sqlite/native/Mac/x86_64/*"; else other="org/sqlite/native/Mac/aarch64/*"; fi
  zip -qd "$C/app/smart-accountant.jar" "org/sqlite/native/Linux/*" "$other" >/dev/null || true
  python3 "$HERE/mac/make_icns.py" "$ICONS" "$C/Resources/AppIcon.icns" >/dev/null
  cp "$HERE/mac/runtime-Info.plist" "$C/runtime/Contents/Info.plist"
  cp -a "$rt" "$C/runtime/Contents/Home"
  # الماك يشترط توقيعًا لكل كود arm64؛ التوقيع المحلي يجعل الحزمة سليمة ومتسقة (تبقى رسالة «مطوّر غير معروف» أول مرة فقط)
  # المشغّل وبيئة Adoptium موقّعة أصلًا بعلَم «hardened runtime»، فيُعطى المشغّل صلاحيات jpackage المعتادة لجافا
  # (JIT وتحميل مكتبات البيئة)، وإلا يتوقف الـ JVM على معالج Apple.
  "$RCODESIGN" sign -e "$HERE/mac/entitlements.plist" "$APP" >/dev/null 2>"$OUT/sign-$arch.log" || { cat "$OUT/sign-$arch.log"; exit 1; }
  INFO="$("$RCODESIGN" print-signature-info "$C/MacOS/SmartAccountant")"
  echo "$INFO" | grep -q 'ADHOC' && echo "$INFO" | grep -q 'allow-jit' && echo "$INFO" | grep -q 'disable-library-validation' \
    || { echo "توقيع المشغّل غير مكتمل"; echo "$INFO" | head -40; exit 1; }
  cp "$HERE/mac/اقرأني-الماك.txt" "$STAGE/"
  ZIP="$OUT/SmartAccountant-$VERSION-mac-$label.zip"
  rm -f "$ZIP"
  ( cd "$STAGE" && zip -qry -9 "$ZIP" . )
  echo "PACKAGE OK: $ZIP ($(du -h "$ZIP" | cut -f1))"
done
