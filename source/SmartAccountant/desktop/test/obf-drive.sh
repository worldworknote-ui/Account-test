#!/bin/bash
# 12.6: يشغّل اختبارات الواجهة الآلية (PhoneShot وPhoneDrive وLookDrive) على النسخة المعمّاة نفسها التي تُشحن:
# الاختبارات تُعمّى مع البرنامج بالخريطة نفسها (-applymapping) فتُنادي الأسماء الجديدة، ويُتأكد أن أسماء أصناف البرنامج
# مطابقة لنسخة الشحن. R8_JAR=<r8.jar> bash desktop/test/obf-drive.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
D="$(dirname "$HERE")"
P="$(dirname "$D")"
LIBS="$D/lib/sqlite-jdbc.jar:$D/lib/org.json.jar:$D/lib/flatlaf.jar:$D/lib/zxing-core.jar"
W=$(mktemp -d /tmp/sa-obf-XXXXXX)
bash "$D/build.sh" >/dev/null
cp -r "$D/build/classes" "$W/plain"
mkdir -p "$W/tests"
javac -nowarn -encoding UTF-8 -cp "$W/plain:$LIBS" -d "$W/tests" "$HERE/PhoneShot.java" "$HERE/PhoneDrive.java" "$HERE/LookDrive.java" 2>&1 | grep -v "JAVA_TOOL\|^Note" || true
bash "$D/obfuscate.sh" "$W/plain" "$W/ship" "$W/map.txt"
cp -r "$W/plain" "$W/both" && cp -r "$W/tests/"* "$W/both/"
cat > "$W/keep-tests.pro" <<'R'
-keep class com.smart.hawala.PhoneShot** { *; }
-keep class com.smart.hawala.PhoneDrive** { *; }
-keep class com.smart.hawala.desk.LookDrive** { *; }
R
bash "$D/obfuscate.sh" "$W/both" "$W/drive" "$W/map2.txt" "$W/map.txt" "$W/keep-tests.pro"
a=$(cd "$W/ship" && find . -name '*.class' | sort | md5sum); b=$(cd "$W/drive" && find . -name '*.class' ! -path '*PhoneShot*' ! -path '*PhoneDrive*' ! -path '*LookDrive*' | sort | md5sum)
[ "$a" = "$b" ] || { echo "أسماء أصناف نسخة الاختبار تختلف عن نسخة الشحن"; exit 1; }
echo "ok   أسماء الأصناف المعمّاة مطابقة لنسخة الشحن"
grep -q "com.smart.hawala.Entitlements -> com.smart.hawala.Entitlements" "$W/map.txt" && { echo "منطق الترخيص لم يُعمَّ"; exit 1; }
echo "ok   منطق الترخيص والحدود معمّى ($(grep -E '^com.smart.hawala.(Entitlements|License|LicenseToken|QuotaState|WaQuota) ->' "$W/map.txt" | tr '\n' ' '))"
PW=$(python3 -c "import json;print(json.load(open('$P/tests/data/expect.json'))['D']['password'])")
UCP="$W/drive:$LIBS"
java -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.PhoneShot "$W/ui" "$P/tests/data/D.sba" "$PW" > "$W/shot.log" 2>&1 || { tail -30 "$W/shot.log"; echo "FAIL PhoneShot (معمّاة)"; exit 1; }
echo "ok   PhoneShot على النسخة المعمّاة"
xvfb-run -a -s '-screen 0 1440x1000x24' java -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.PhoneDrive "$W/drive-out" "$P/tests/data/D.sba" "$PW" > "$W/drive.log" 2>&1 || { grep -E "FAIL|Exception" "$W/drive.log" | head -30; echo "FAIL PhoneDrive (معمّاة)"; exit 1; }
grep -c "^ok" "$W/drive.log" | sed 's/^/ok   PhoneDrive على النسخة المعمّاة: /'
LH=$(mktemp -d /tmp/sa-look-XXXXXX)
xvfb-run -a -s '-screen 0 1600x1100x24' java -Dsa.home="$LH/home" -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.desk.LookDrive "$W/look" > "$W/look.log" 2>&1 || { tail -20 "$W/look.log"; echo "FAIL LookDrive (معمّاة)"; exit 1; }
echo "ok   LookDrive على النسخة المعمّاة"
rm -rf "$LH" "$W"
echo "OBF DRIVE OK"
