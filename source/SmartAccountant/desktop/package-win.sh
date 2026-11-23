#!/bin/bash
# يبني نسخة ويندوز x64 من «المحاسب الذكي» (مجلد جاهز مضغوط zip) على هذا الجهاز (لينكس):
#   RT_WIN=<بيئة جافا مصغّرة للويندوز من jlink بوحدات Temurin ويندوز>  JPKG_WIN=<jmods/jdk.jpackage.jmod من JDK ويندوز>
#   SQLITE_FULL=<sqlite-jdbc الكامل (فيه مكتبة ويندوز)>  bash package-win.sh [مجلد الإخراج]
# بناء RT_WIN (إصدار jlink يطابق إصدار الوحدات تمامًا):
#   jlink --module-path <jdk-win>/jmods --add-modules java.base,java.desktop,java.sql,jdk.crypto.ec,jdk.unsupported,jdk.crypto.mscapi \
#         --strip-java-debug-attributes --no-header-files --no-man-pages --compress=zip-0 --output rt-win
# المشغّل هو مشغّل jpackage الأصلي لويندوز (بلا نافذة أوامر) بتوقيع Adoptium كما هو دون تعديل؛ الأيقونة في الاختصارات (ملف .ico).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
P="$(dirname "$HERE")"
OUT="${1:-$HERE/dist}"
VERSION=$(grep -o 'VERSION = "[^"]*"' "$P/app/src/main/java/com/smart/hawala/BuildInfo.java" | cut -d'"' -f2)
[ -x "$RT_WIN/bin/java.exe" ] || { echo "حدّد RT_WIN"; exit 1; }
[ -f "$JPKG_WIN" ] || { echo "حدّد JPKG_WIN"; exit 1; }
[ -f "$SQLITE_FULL" ] || { echo "حدّد SQLITE_FULL"; exit 1; }
# 12.6: مكتبة القاعدة المشفّرة (io.github.willena:sqlite-jdbc، SQLite3MultipleCiphers) لا xerial العادية
unzip -l "$SQLITE_FULL" | grep -q 'org/sqlite/mc/SQLiteMCSqlCipherConfig.class' || { echo "SQLITE_FULL ليست مكتبة التشفير (io.github.willena:sqlite-jdbc)"; exit 1; }
[ -f "$R8_JAR" ] || { echo "حدّد R8_JAR (cmdline-tools/lib/r8.jar من أدوات أندرويد) لتعمية النسخة"; exit 1; }

# 12.6: نسخة التوزيع معمّاة دائمًا (الخريطة في desktop/build/obf-map.txt، لا تدخل الحزمة)
OBFUSCATE=1 bash "$HERE/build.sh"
mkdir -p "$OUT"
ICONS="$OUT/icons"; rm -rf "$ICONS"
java -cp "$HERE/build/smart-accountant.jar" com.smart.hawala.desk.IconGen "$ICONS"

STAGE="$OUT/stage-win"
NAME="SmartAccountant-$VERSION-Windows"
R="$STAGE/$NAME"
rm -rf "$STAGE"; mkdir -p "$R/app"
python3 - "$JPKG_WIN" "$R/SmartAccountant.exe" <<'EOF'
import sys, zipfile, io
d = open(sys.argv[1], 'rb').read()[4:]
z = zipfile.ZipFile(io.BytesIO(d))
open(sys.argv[2], 'wb').write(z.read('classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe'))
EOF
file "$R/SmartAccountant.exe" | grep -q 'PE32+ executable (GUI) x86-64' || { echo "المشغّل ليس ويندوز x64 بواجهة رسومية"; exit 1; }

cp "$HERE/win/SmartAccountant.cfg" "$R/app/"
J="$R/app/smart-accountant.jar"
cp "$HERE/build/smart-accountant.jar" "$J"
zip -qd "$J" "org/sqlite/native/Linux/*" "org/sqlite/native/Mac/*" >/dev/null || true
TMPN="$OUT/native-win"; rm -rf "$TMPN"; mkdir -p "$TMPN"
( cd "$TMPN" && unzip -qo "$SQLITE_FULL" 'org/sqlite/native/Windows/x86_64/*' && zip -qr "$J" org )
rm -rf "$TMPN"
unzip -l "$J" | grep -q 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll' || { echo "مكتبة sqlite لويندوز مفقودة"; exit 1; }
unzip -l "$J" | grep -q 'org/sqlite/native/\(Linux\|Mac\)/' && { echo "بقيت مكتبات غير ويندوز"; exit 1; }

cp -a "$RT_WIN" "$R/runtime"

python3 - "$ICONS" "$R/SmartAccountant.ico" <<'EOF'
import sys, os, struct
sizes = [16, 24, 32, 48, 64, 128, 256]
imgs = [open(os.path.join(sys.argv[1], "icon_%d.png" % s), "rb").read() for s in sizes]
head = struct.pack("<HHH", 0, 1, len(sizes))
off = 6 + 16 * len(sizes)
ents = b""
for s, d in zip(sizes, imgs):
    ents += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(d), off)
    off += len(d)
open(sys.argv[2], "wb").write(head + ents + b"".join(imgs))
EOF

# سكربتات PowerShell بترميز UTF-8 مع BOM (يلزم Windows PowerShell 5.1 لقراءة العربية)، وملفات cmd بنص لاتيني فقط ونهايات CRLF
bom() { printf '\xef\xbb\xbf' > "$2"; sed 's/$/\r/' "$1" >> "$2"; }
bom "$HERE/win/install.ps1" "$R/install.ps1"
bom "$HERE/win/uninstall.ps1" "$R/uninstall.ps1"
bom "$HERE/win/اقرأني-ويندوز.txt" "$R/اقرأني-ويندوز.txt"
printf '@echo off\r\nchcp 65001 >nul\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%%~dp0install.ps1"\r\n' > "$R/تثبيت.cmd"
printf '@echo off\r\nchcp 65001 >nul\r\ncopy /y "%%~dp0uninstall.ps1" "%%TEMP%%\\sa-uninstall.ps1" >nul\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%%TEMP%%\\sa-uninstall.ps1"\r\n' > "$R/إلغاء-التثبيت.cmd"

ZIP="$OUT/$NAME.zip"
rm -f "$ZIP"
# zipfile في بايثون يضع علامة UTF-8 للأسماء العربية فيفكّها مستكشف ويندوز صحيحة
python3 - "$STAGE" "$ZIP" <<'EOF'
import sys, os, zipfile
base, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(base):
        dirs.sort()
        for f in sorted(files):
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, base))
EOF
echo "PACKAGE OK: $ZIP ($(du -h "$ZIP" | cut -f1))"
