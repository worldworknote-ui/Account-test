#!/bin/bash
# يبني «المحاسب الذكي» للماك (والاختبارات على أي نظام فيه JDK 17+):
#   bash desktop/build.sh            ← desktop/build/smart-accountant.jar
# الحسابات وقاعدة البيانات والنسخ الاحتياطي والتقارير من ملفات الهاتف نفسها (app/src/main/java) دون نسخها،
# فلا يختلف رقم ولا صيغة نسخة بين الهاتف والماك.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
P="$(dirname "$HERE")"
SH="$P/app/src/main/java/com/smart/hawala"
OUT="$HERE/build"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"

# 11.0: كل ملفات تطبيق الهاتف (الواجهات نفسها) تُبنى فوق طبقة توافق أندرويد في desktop/src/android،
# عدا خمسة ملفات لها بدائل كمبيوتر في desktop/src/com/smart/hawala (النسخ الاحتياطي، المفاتيح، الترخيص، التطبيق، Google).
EXCL=" BackupManager Crypto License SmartApp DriveAuth DbCipher "
python3 "$HERE/gen-r.py" "$P/app/src/main/res" "$OUT/gen/com/smart/hawala/R.java"
SRCS="$OUT/gen/com/smart/hawala/R.java"
# 11.2: نسخة الكمبيوتر تقول «الكمبيوتر/الجهاز» بدل «الهاتف» (في نصوص البرنامج الثابتة فقط، على نسخة من الملفات)
rm -rf "$OUT/desk-src"
python3 "$HERE/desk-words.py" "$SH" "$OUT/desk-src"
for f in "$OUT/desk-src"/*.java; do n=$(basename "$f" .java); case "$EXCL" in *" $n "*) continue;; esac; SRCS="$SRCS $f"; done
DESK=$(find "$HERE/src" -name '*.java')
CP="$HERE/lib/sqlite-jdbc.jar:$HERE/lib/org.json.jar:$HERE/lib/flatlaf.jar:$HERE/lib/zxing-core.jar"
set +e
javac -nowarn -encoding UTF-8 --release 17 -Xmaxerrs 3000 -cp "$CP" -d "$OUT/classes" $SRCS $DESK > "$OUT/javac.log" 2>&1
RC=$?
set -e
grep -v "^Note:\|JAVA_TOOL" "$OUT/javac.log" || true
[ $RC -eq 0 ] || { echo "COMPILE FAILED"; exit 1; }
[ -n "$COMPILE_ONLY" ] && { echo "COMPILE OK"; exit 0; }
# 12.6: نسخة التوزيع معمّاة (OBFUSCATE=1 R8_JAR=…): أصناف بأسماء بلا معنى، والخريطة في build/obf-map.txt خارج الحزمة
if [ -n "$OBFUSCATE" ]; then
  bash "$HERE/obfuscate.sh" "$OUT/classes" "$OUT/obf-classes" "$OUT/obf-map.txt"
  rm -rf "$OUT/classes" && mv "$OUT/obf-classes" "$OUT/classes"
fi

# الموارد: الخطوط والأيقونات
mkdir -p "$OUT/classes/res"
cp "$P"/app/src/main/res/font/*.ttf "$OUT/classes/res/" 2>/dev/null || true
# 11.0: رسومات الهاتف المتجهة (res/drawable/*.xml) تُرسم كما هي على الكمبيوتر
mkdir -p "$OUT/classes/res/drawable" && cp "$P"/app/src/main/res/drawable/*.xml "$OUT/classes/res/drawable/"
[ -d "$HERE/res" ] && cp -r "$HERE"/res/* "$OUT/classes/res/"
# 10.16: أداتا النقل (صفحتا HTML تعملان في المتصفح بلا إنترنت) لمساعد النقل على الكمبيوتر، من ملفات الهاتف نفسها
cp "$P"/app/src/main/assets/migration-tool.html "$P"/app/src/main/assets/smart-import.html "$OUT/classes/res/" 2>/dev/null || true
# 10.2: معرّف عميل Google للكمبيوتر (اختياري): GOOGLE_OAUTH_JSON=ملف client_secret_….json المنزّل من Google Cloud
[ -n "$GOOGLE_OAUTH_JSON" ] && [ -f "$GOOGLE_OAUTH_JSON" ] && cp "$GOOGLE_OAUTH_JSON" "$OUT/classes/res/google-oauth.json"

# jar واحد يضم المكتبات (تشغيل بلا classpath) — 10.4: ومعها FlatLaf (Apache 2.0) للمظهر الحديث
rm -rf "$OUT/merge"; mkdir -p "$OUT/merge"
( cd "$OUT/merge" && unzip -qo "$HERE/lib/sqlite-jdbc.jar" && unzip -qo "$HERE/lib/org.json.jar" && unzip -qo "$HERE/lib/flatlaf.jar" -x 'module-info.class' && unzip -qo "$HERE/lib/zxing-core.jar" -x 'module-info.class' && rm -rf META-INF/*.SF META-INF/*.RSA META-INF/*.DSA META-INF/MANIFEST.MF )
cp -r "$OUT/classes/"* "$OUT/merge/"
printf "Manifest-Version: 1.0\nMain-Class: com.smart.hawala.desk.Main\n" > "$OUT/manifest.txt"
rm -f "$OUT/smart-accountant.jar"
( cd "$OUT/merge" && jar --create --file "$OUT/smart-accountant.jar" --manifest "$OUT/manifest.txt" . )
echo "BUILD OK: $OUT/smart-accountant.jar ($(du -h "$OUT/smart-accountant.jar" | cut -f1))"
