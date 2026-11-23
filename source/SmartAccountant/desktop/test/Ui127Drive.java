package com.smart.hawala;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Method;
import javax.swing.SwingUtilities;
import static com.smart.hawala.PhoneDrive.*;

/**
 * 12.7 على النافذة الحقيقية (شاشة افتراضية، بيانات الاختبار D.sba): تفعيل نظام المستخدمين ← رسالة واضحة ← شاشة الدخول واسم
 * المدير مكتوب فيها؛ ولصق عدة رسائل واتساب في «حركة جديدة» من داخل مكتب يثبّت المكتب نفسه لا آخر مكتب سُجّل له.
 * وسائط: مجلد الصور، ملف .sba، ملف expect.json (كلمة مرور الاختبار).
 */
public final class Ui127Drive {
    static void W(int ms) throws Exception { PhoneDrive.wait(ms); }

    static MainActivity act() { return com.smart.hawala.desk.PhoneWindow.activityForTests(); }

    public static void main(String[] a) throws Exception {
        out = new File(a[0]);
        out.mkdirs();
        File home = java.nio.file.Files.createTempDirectory("sa-u127").toFile();
        System.setProperty("sa.home", home.getAbsolutePath());
        System.setProperty("sa.secrets", "file");
        SecretStore.fallback(new File(home, "keys"));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { fails++; e.printStackTrace(System.out); });
        Context ctx = new Context(home);
        Crypto.init(ctx);
        Db db = new Db(ctx);
        SmartApp.db = db;
        SmartApp.ctx = ctx;
        License.load(ctx, db);
        String pw = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(a[2]).toPath()), "UTF-8")).getJSONObject("D").getString("password");
        BackupManager.Prepared p = BackupManager.prepareRestore(ctx, new File(a[1]), pw.toCharArray(), m -> { });
        BackupManager.applyRestore(ctx, db, p, m -> { });
        java.util.Map<String, String> gf = new java.util.HashMap<>();
        gf.put("ft", "all");
        gf.put("lid", "TEST-FULL");
        gf.put("uu", "29991231");
        java.lang.reflect.Constructor<LicenseToken.Grant> gc = LicenseToken.Grant.class.getDeclaredConstructor(java.util.Map.class);
        gc.setAccessible(true);
        License.current = Entitlements.of(gc.newInstance(gf), BuildInfo.BUILD_DATE);
        SwingUtilities.invokeAndWait(() -> {
            com.smart.hawala.desk.Main.lafForTests();
            com.smart.hawala.desk.PhoneWindow.open(ctx, db);
        });
        robot = new java.awt.Robot();
        robot.setAutoDelay(30);
        W(1500);
        if (findQuiet("لاحقًا")) clickText("لاحقًا");
        W(400);

        System.out.println("== تفعيل نظام المستخدمين: مسار واضح بلا تعليق");
        SwingUtilities.invokeAndWait(() -> act().usersScreen.showManager());
        W(800);
        clickText("تفعيل: إنشاء حساب المدير");
        W(800);
        clickHint("اسم المستخدم");
        paste("مدير المكتب");
        W(200);
        clickHint("رمز المدير (6 إلى 8 أرقام)");
        type("135790");
        clickHint("أعد كتابة الرمز");
        type("135790");
        clickText("حفظ");
        W(1200);
        expect(findQuiet("رمز استرداد المدير"), "يظهر رمز الاسترداد أولًا");
        clickText("كتبت الرمز وحفظته");
        W(300);
        clickText("تم");
        W(1000);
        shot("users-enabled-message");
        expect(findQuiet("تم تفعيل نظام المستخدمين ✓") && findQuiet("الدخول باسمي"), "ثم رسالة واضحة: «ادخل الآن باسمك»");
        clickText("الدخول باسمي");
        W(1000);
        shot("login-prefilled");
        final String[] nameVal = {null};
        SwingUtilities.invokeAndWait(() -> {
            android.view.View v = PhoneDrive.findClass(android.app.WindowStack.roots().get(0).getView(), "EditText");
            if (v instanceof android.widget.EditText) nameVal[0] = ((android.widget.EditText) v).getText().toString();
        });
        expect(findQuiet("تسجيل الدخول") && "مدير المكتب".equals(nameVal[0]), "شاشة الدخول واسم المدير مكتوب فيها («" + nameVal[0] + "»)");
        clickHint("الرمز السري");
        type("135790");
        clickText("دخول");
        W(1500);
        expect(!findQuiet("تسجيل الدخول") && SmartApp.currentUser != null, "الدخول يعمل والتطبيق يستجيب");
        clickTab(1, 6);
        W(800);
        expect(findQuiet("الأمل"), "التنقل يعمل بعد الدخول (المكاتب)");

        System.out.println("== لصق عدة رسائل من «حركة جديدة» داخل مكتب: المكتب نفسه");
        Model.Office amal = null, other = null;
        for (Model.Office o : db.offices()) {
            if (o.name.equals("الأمل")) amal = o;
            else if (o.active && other == null) other = o;
        }
        db.setSettingQuiet("last_office", "" + other.id);
        WaPin.pin(db, other.id);   // آخر مكتب مثبّت لرسائل واتساب: غير الذي سندخل منه
        final Model.Office target = amal;
        SwingUtilities.invokeAndWait(() -> act().txScreen.showForOffice(target));
        W(1000);
        String msgs = "[18/11, 19:36] OA: 500 دولار لأحمد علي\n[18/11, 19:37] OA: 300 يورو لخالد\n[18/11, 19:38] OA: 200 دولار لسامر";
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(msgs), null);
        SwingUtilities.invokeAndWait(() -> {
            try {
                Method m = TxScreen.class.getDeclaredMethod("pasteMessage");
                m.setAccessible(true);
                m.invoke(act().txScreen);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        W(900);
        clickText("تسجيل كل رسالة وحدها");
        W(1000);
        shot("paste-office");
        expect(findQuiet("📌 المكتب: " + amal.name), "قائمة الرسائل مثبّتة على «" + amal.name + "» (المكتب الذي دخلنا منه)");
        expect(!findQuiet("📌 المكتب: " + other.name), "لا على آخر مكتب مثبّت «" + other.name + "»");

        System.out.println("== شاشة تحميل الانضمام (تُرسم، وتعرض الخطأ وزرّيه، والإلغاء يعود)");
        SwingUtilities.invokeAndWait(() -> act().syncScreen.joinScreen());
        W(2500);
        shot("join-screen");
        expect(findQuiet("الانضمام إلى المزامنة") && findQuiet("يمكنك ترك التطبيق أثناء التحميل"), "شاشة التحميل بشريط تقدم وشرح");
        expect(findQuiet("تعذّر إكمال التحميل") && findQuiet("إعادة المحاولة") && findQuiet("إلغاء الانضمام"), "بلا انضمام جارٍ: رسالة واضحة وزرّا إعادة المحاولة والإلغاء");
        clickText("إلغاء الانضمام");
        W(600);
        click("إلغاء الانضمام", true);   // زر نافذة التأكيد (العنوان بالنص نفسه أعلاها)
        W(1200);
        expect(!findQuiet("الانضمام إلى المزامنة"), "الإلغاء يعود للتطبيق");

        System.out.println(fails == 0 ? "UI127 OK" : "UI127 FAILED " + fails + " " + log);
        System.exit(fails == 0 ? 0 : 1);
    }
}
