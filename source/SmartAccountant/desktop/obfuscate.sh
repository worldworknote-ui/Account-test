#!/bin/bash
# 12.6: تعمية أصناف البرنامج بـ R8 (من أدوات أندرويد: cmdline-tools/lib/r8.jar) — قواعدها في obfuscate.pro.
#   R8_JAR=<r8.jar>  bash desktop/obfuscate.sh <مجلد الأصناف> <مجلد الإخراج> <ملف الخريطة> [خريطة سابقة تُطبَّق] [قواعد إضافية]
# الخريطة (mapping) تبقى خارج الحزم دائمًا: تُحفظ مع الإصدار لقراءة سجلات الانهيار فقط.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
IN="$1"; OUTD="$2"; MAP="$3"; APPLY="$4"; EXTRA="$5"
[ -f "$R8_JAR" ] || { echo "حدّد R8_JAR"; exit 1; }
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
T=$(mktemp -d)
( cd "$IN" && jar cf "$T/in.jar" $(find . -name '*.class' | sed 's|^\./||') )
CONF="$T/rules.pro"
cp "$HERE/obfuscate.pro" "$CONF"
[ -n "$APPLY" ] && echo "-applymapping $APPLY" >> "$CONF"
[ -n "$EXTRA" ] && cat "$EXTRA" >> "$CONF"
java -cp "$R8_JAR" com.android.tools.r8.R8 --classfile --release --lib "$JH" \
  --classpath "$HERE/lib/sqlite-jdbc.jar" --classpath "$HERE/lib/org.json.jar" --classpath "$HERE/lib/flatlaf.jar" --classpath "$HERE/lib/zxing-core.jar" \
  --pg-conf "$CONF" --pg-map-output "$MAP" --output "$T/out.jar" "$T/in.jar" 2>&1 | grep -v "JAVA_TOOL\|^Info in\|does not match anything\|^  \*;\|^}\|<init>(...);\|^Proguard configuration" || true
[ -f "$T/out.jar" ] || { echo "فشلت التعمية"; exit 1; }
rm -rf "$OUTD"; mkdir -p "$OUTD"
( cd "$OUTD" && unzip -qo "$T/out.jar" && rm -rf META-INF )
# الموارد غير الأصناف كما هي
( cd "$IN" && find . -type f ! -name '*.class' -exec cp --parents {} "$OUTD/" \; )
rm -rf "$T"
echo "OBFUSCATE OK: $(grep -c ' -> ' "$MAP") اسمًا في الخريطة"
