# 12.6: تعمية نسخة الكمبيوتر (ويندوز وماك) بـ R8: أسماء أصناف البرنامج ودواله تُستبدل بحروف بلا معنى، فيصعب البحث عن
# منطق الترخيص والحدود وتعديله. لا حذف ولا تحسين (-dontshrink -dontoptimize): السلوك هو نفسه تمامًا.
-dontshrink
-dontoptimize
-dontwarn **
-ignorewarnings
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,MethodParameters
-renamesourcefileattribute SA

# طبقة توافق أندرويد ومكتبة القاعدة: تُستعمل أسماؤها بالانعكاس وتُطابق أسماء أندرويد
-keep class android.** { *; }
-keep interface android.** { *; }
-keep class net.zetetic.** { *; }
-keep class org.** { *; }
-keep class com.formdev.** { *; }
-keep class com.google.** { *; }

# الموارد (R) تُقرأ بالانعكاس، والتعدادات (valueOf)، ونقطة البدء
-keep class com.smart.hawala.R { *; }
-keep class com.smart.hawala.R$* { *; }
-keepclassmembers enum * { *; }
-keep class com.smart.hawala.desk.Main { public static void main(java.lang.String[]); }

# أصناف تُنشأ بالاسم (الشاشات والمهام الخلفية)
-keep class * extends android.app.Activity { <init>(...); }
-keep class * extends android.app.Service { <init>(...); }
-keep class * extends android.app.job.JobService { <init>(...); }
-keep class * extends android.content.BroadcastReceiver { <init>(...); }
-keep class * extends android.app.Application { <init>(...); }
-keep class * extends android.view.View { <init>(...); }

# واجهة الكمبيوتر وما يحتاجه اختبار الواجهة الآلي (يُشغَّل على النسخة المعمّاة نفسها)
-keep class com.smart.hawala.desk.** { *; }
-keep class com.smart.hawala.MainActivity { *; }
-keep class com.smart.hawala.SmartApp { *; }
-keep class com.smart.hawala.UsersScreen { *; }
-keep class com.smart.hawala.Model$User { *; }
