#!/bin/bash
# اختبارات نسخة الماك على أي نظام فيه JDK 17+ (لينكس أو ماك):
#   bash desktop/test/run-tests.sh [ملف_حقيقي.sba كلمة_المرور expect.json]
# 1) Db نفسه فوق sqlite-jdbc: نسخ أداة النقل، عمليات كاملة، نسخة من «الماك» تُستعاد في «جهاز آخر»، وصندوق المكتب وحدود المجانية (9.0).
# 2) (اختياري) ملف حقيقي: الاستعادة والأرصدة وهوية الأرصدة ونسخة الماك منه.
# 3) الواجهة: فتح كل الشاشات على شاشة افتراضية (xvfb-run على لينكس) وحفظ صورها في desktop/build/ui.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
D="$(dirname "$HERE")"
P="$(dirname "$D")"
bash "$D/build.sh" >/dev/null
OUT="$D/build/test"
rm -rf "$OUT"; mkdir -p "$OUT"
CP="$D/build/classes:$D/lib/sqlite-jdbc.jar:$D/lib/org.json.jar:$D/lib/flatlaf.jar:$D/lib/zxing-core.jar"
javac -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" "$HERE/DeskCoreTest.java" "$HERE/CashDbTest.java" "$HERE/SyncDbTest.java" "$HERE/TransportTest.java" "$HERE/FixesTest.java" "$HERE/MigrationTest.java" "$HERE/AuditFixesTest.java" "$HERE/Fixes1016Test.java" "$HERE/Fixes1018Test.java" "$HERE/Special124Test.java" "$HERE/Metals125Test.java" "$HERE/Release126Test.java" "$HERE/Release127Test.java" "$HERE/WaCaptureTest.java" "$HERE/Capture122Test.java"
export LC_ALL=${LC_ALL:-C.UTF-8}
# 10.11: كل مجموعة تعمل حتى لو فشلت التي قبلها (كان set -e يوقف ما بعد أول فشل فلا يظهر)، والنتيجة النهائية فشل إن فشلت أي منها
set +e
FAILED=""
suite() {
    local name="$1"; shift
    "$@"
    [ $? -eq 0 ] || FAILED="$FAILED $name"
}
suite DeskCoreTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" DeskCoreTest "$P/tests/data" "$@"
# 9.0: صندوق المكتب، وحد المجانية (الحذف يعيد المكان، واختيار 9 مكاتب عند الاستعادة والإضافة)، وعدّاد واتساب، والترتيب
suite CashDbTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" CashDbTest "$P/tests/data"
# 10.5: إصلاحات المال والصلاحيات (AUDIT-017، 018، 023، 027، 028، 029، 003)
suite FixesTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" FixesTest
# 10.11: إصلاحات تقرير التدقيق (AUDIT-001، 002، 004، 010، 011، 013، 016)
suite AuditFixesTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" AuditFixesTest
# 10.5: ترحيل قاعدة بيانات أنشأها كود 10.4 نفسه (لا نسخة احتياطية) — الأرصدة كما هي والميزات الجديدة تعمل عليها
suite MigrationTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" MigrationTest "$P/tests/data/db-10.4"
# 10.16: إصلاحات تقرير المراجعة: السجل المشترك، تنبيهات المزامنة، التحقق الجماعي، الترحيل للمخطط 9، اعتماد رصيد الملف
suite Fixes1016Test java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" Fixes1016Test "$P/tests/data"
# 10.18: الساعة الموحّدة وتوقيت المكتب، الأمانات والتجميد، تعديل القص، «الرصيد بعدها»، التوزيع التلقائي، سجل الإشعارات، الترحيل للمخطط 11
suite Fixes1018Test java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" Fixes1018Test "$P/tests/data/db-10.4"
# 12.4: الحركات الخاصة، وتحويل الخاصة والأمانة والمجمّد لحركات عادية
suite Special124Test java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" Special124Test "$P/tests/data/db-10.4"
# 12.5: المعادن الثمينة بالوزن
suite Metals125Test java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" Metals125Test "$P/tests/data/db-10.4"
# 12.6: تشفير القاعدة كاملة وترحيل 10.4، الصلاحيات المفصلة، سقوف المجانية، الرخصة المخصصة، صرامة المعادن
suite Release126Test java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" Release126Test "$P/tests/data/db-10.4"
# 12.7: الشاشة لا تنتظر الخلفية، الانضمام على مرحلتين، ملخص المكاتب والبحث السريعان، الفحص المتتابع، ربط الرخصة بالمكتب وتأكيد الوقت
suite Release127Test java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" Release127Test "$P/tests/data"
# 12.7.1: فحوص SQL بالبايثون (جمل Db.java حرفيًا على SQLite)
if command -v python3 >/dev/null; then
  suite SqlHealthPy python3 -I "$P/tests/health_sql_test.py"
  suite SqlBindingPy python3 -I "$P/tests/sql_text_binding_test.py"
  suite SqlMergePy python3 -I "$P/tests/merge_sql_test.py" "$P/app/src/main/java/com/smart/hawala/Db.java" "$P/tests/data"
fi
# 12.1: نقل رسائل واتساب من ويندوز وماك: HTML الحافظة، أسطر الوقت، القص، إخفاء المحتوى، فقاعات إطار الالتقاط واسم الغروب
suite WaCaptureTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" WaCaptureTest
# 12.2: إطار الالتقاط: الرسالة كلها رقم واحد من شكل الفقاعة في الصورة، وبصمة القراءة التلقائية
suite Capture122Test java -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" com.smart.hawala.Capture122Test
# 10.0: المزامنة بين ثلاثة أجهزة تتشارك مجلدًا (الإلغاء والتعديل المتزامنان، التلاعب، الحزم الناقصة، الانضمام)
suite SyncDbTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" SyncDbTest
# 10.2: نواقل المزامنة: خادمك (Node) وGoogle Drive (محاكي) ونقل الخزنة بينها
if command -v node >/dev/null; then suite TransportTest java -Dstdout.encoding=UTF-8 -cp "$OUT:$CP" TransportTest "$P/sync-server/server.js"; fi
# 11.0: واجهات الهاتف نفسها على الكمبيوتر: 32 شاشة تُفتح وتُصوَّر، ثم تجربة بالفأرة ولوحة المفاتيح على شاشة افتراضية
javac -nowarn -encoding UTF-8 -cp "$D/build/classes:$D/lib/sqlite-jdbc.jar:$D/lib/org.json.jar:$D/lib/flatlaf.jar:$D/lib/zxing-core.jar" -d "$OUT" "$HERE/PhoneShot.java" "$HERE/PhoneDrive.java" "$HERE/LookDrive.java" "$HERE/Ui127Drive.java"
PW=$(python3 -c "import json;print(json.load(open('$P/tests/data/expect.json'))['D']['password'])")
UCP="$OUT:$D/build/classes:$D/lib/sqlite-jdbc.jar:$D/lib/org.json.jar:$D/lib/flatlaf.jar:$D/lib/zxing-core.jar"
suite PhoneShot java -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.PhoneShot "$D/build/ui" "$P/tests/data/D.sba" "$PW"
if [ "$(uname)" = "Linux" ] && command -v xvfb-run >/dev/null; then
    suite PhoneDrive xvfb-run -a -s '-screen 0 1440x1000x24' java -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.PhoneDrive "$D/build/drive" "$P/tests/data/D.sba" "$PW"
    # 12.7: تفعيل المستخدمين بمسار واضح، ولصق عدة رسائل من داخل مكتب، وشاشة تحميل الانضمام
    suite Ui127Drive xvfb-run -a -s '-screen 0 1440x1000x24' java -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.Ui127Drive "$D/build/u127" "$P/tests/data/D.sba" "$P/tests/data/expect.json"
    # 11.0: معالج الشكل، وسحب طرف النافذة، والتبديل إلى الشكل القديم والعودة (مجلد بيانات مؤقت فارغ)
    LH=$(mktemp -d /tmp/sa-look-XXXXXX)
    suite LookDrive xvfb-run -a -s '-screen 0 1600x1100x24' java -Dsa.home="$LH/home" -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.desk.LookDrive "$D/build/look"
    rm -rf "$LH"
elif [ "$(uname)" = "Darwin" ]; then
    suite PhoneDrive java -Dstdout.encoding=UTF-8 -cp "$UCP" com.smart.hawala.PhoneDrive "$D/build/drive" "$P/tests/data/D.sba" "$PW"
fi
# 12.6: اختبارات الواجهة نفسها على النسخة المعمّاة التي تُشحن (حين تتوفر R8_JAR وشاشة افتراضية)
if [ -f "$R8_JAR" ] && [ "$(uname)" = "Linux" ] && command -v xvfb-run >/dev/null; then suite ObfDrive bash "$HERE/obf-drive.sh"; fi
echo
if [ -n "$FAILED" ]; then echo "فشلت:$FAILED"; exit 1; fi
echo "نجحت كل مجموعات الكمبيوتر"
