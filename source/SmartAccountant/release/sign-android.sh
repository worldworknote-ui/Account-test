#!/bin/bash
# 10.11: توقيع النسخة النهائية للهاتف بمفتاح 8.0 على جهاز صاحب المفتاح (المفتاح لا يغادر جهازه ولا يوضع في المستودع).
#   bash release/sign-android.sh <app-release-unsigned.apk> [ملف الإخراج.apk]
# يحتاج: keystore.properties ومجلد signing في مجلد المشروع (كما في keystore.properties.example)، وapksigner من Android SDK build-tools
# (أو حدّد APKSIGNER=<المسار>). يتحقق بعد التوقيع أن بصمة الشهادة = BuildInfo.SIGNING_CERT_SHA256، وإلا يحذف الناتج:
# نسخة بمفتاح آخر لا تُثبَّت فوق النسخة الحالية ولا تعمل عليها الرخص.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
P="$(dirname "$HERE")"
IN="${1:?حدد ملف app-release-unsigned.apk}"
VERSION=$(grep -o 'VERSION = "[^"]*"' "$P/app/src/main/java/com/smart/hawala/BuildInfo.java" | cut -d'"' -f2)
OUT="${2:-$(dirname "$IN")/SmartAccountant-$VERSION.apk}"
WANT=$(grep -o 'SIGNING_CERT_SHA256 = "[^"]*"' "$P/app/src/main/java/com/smart/hawala/BuildInfo.java" | cut -d'"' -f2 | tr 'A-F' 'a-f')
PROPS="$P/keystore.properties"
[ -f "$PROPS" ] || { echo "لا يوجد $PROPS (انظر keystore.properties.example)"; exit 1; }
prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2-; }
KS="$P/$(prop storeFile)"
[ -f "$KS" ] || { echo "ملف المفتاح غير موجود: $KS"; exit 1; }
if [ -z "${APKSIGNER:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
fi
[ -x "${APKSIGNER:-}" ] || { echo "حدّد APKSIGNER (من Android SDK build-tools)"; exit 1; }

# كلمات المرور تُمرَّر بمتغيرات بيئة لا في سطر الأوامر
export SA_KS_PASS="$(prop storePassword)" SA_KEY_PASS="$(prop keyPassword)"
"$APKSIGNER" sign --ks "$KS" --ks-key-alias "$(prop keyAlias)" --ks-pass env:SA_KS_PASS --key-pass env:SA_KEY_PASS --out "$OUT" "$IN"
unset SA_KS_PASS SA_KEY_PASS

GOT=$("$APKSIGNER" verify --print-certs "$OUT" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}' | tr 'A-F' 'a-f')
if [ "$GOT" != "$WANT" ]; then
  rm -f "$OUT"
  echo "بصمة الشهادة $GOT لا تطابق مفتاح 8.0 ($WANT): حُذف الناتج."
  exit 1
fi
"$APKSIGNER" verify --verbose "$OUT" | grep -E "Verified|v[0-9] scheme" || true
sha256sum "$OUT"
echo "SIGNED OK: $OUT"
