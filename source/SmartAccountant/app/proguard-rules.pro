# 8.0: قواعد R8 للنسخة النهائية.
# التطبيق لا يستعمل الانعكاس (reflection) ولا مكتبات خارجية؛ مكونات AndroidManifest تُبقى تلقائيًا.
# أرقام الأسطر تبقى حتى يُفهم سجل الانهيار مع ملف mapping.txt (احفظه مع كل إصدار).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SA

# 12.6: SQLCipher (تشفير القاعدة): مكتبة أصلية (JNI) تُستدعى أسماؤها من الشيفرة الأصلية
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
