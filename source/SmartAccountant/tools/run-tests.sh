#!/bin/bash
# اختبارات الحسابات بلا هاتف ولا Gradle: bash tools/run-tests.sh [مجلد بيانات النقل]
# يحتاج JDK 11+ فقط؛ org.json (ملكية عامة، github.com/stleary/JSON-java) مرفق في tools/lib.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
J=$ROOT/app/src/main/java/com/smart/hawala
OUT=${OUT:-$ROOT/app/build/pure-tests}
JSON_CP=${JSON_CP:-$ROOT/tools/lib/org.json.jar}
[ -e "$JSON_CP" ] || { echo "ضع org.json في $JSON_CP أو حدّد JSON_CP (https://repo1.maven.org/maven2/org/json/json/20240303/)"; exit 2; }
SRCS=""
for f in Clock Amounts Auth BackupFormat ExportStyle Ledger MessageParser NumberWords AiExtract Model Pin Reconcile Report Reports SecureStreams \
         XlsxWriter WaText FieldCipher MergePlan LicenseToken Entitlements QuotaState BackupPolicy BuildInfo OwnerReports ReportBuilder AuditChain Sanity DayClose \
         CashBox WaQuota OfficeOrder AboutText ClientBook Hlc SyncCrypto NavPrefs WaWords WaCuts Metals; do
  [ -f "$J/$f.java" ] && SRCS="$SRCS $J/$f.java"
done
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes" "$OUT/out"
javac -nowarn -encoding UTF-8 -cp "$JSON_CP" -d "$OUT/classes" $SRCS "$ROOT"/tests/*.java || { echo "تعذر بناء الاختبارات"; exit 1; }
DATA=${1:-$ROOT/tests/data}
if [ -d "$DATA" ]; then java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT/classes:$JSON_CP" RunAll "$OUT/out" "$DATA"; else java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT/classes:$JSON_CP" RunAll "$OUT/out"; fi
