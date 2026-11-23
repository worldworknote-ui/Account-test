package com.smart.hawala;

import android.app.WindowStack;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.widget.TextView;
import android.widget.EditText;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * 11.0: تجربة النافذة الحقيقية بالفأرة ولوحة المفاتيح (على شاشة افتراضية): تنقّل، كتابة، تسجيل حركة، نوافذ حوار، قوائم.
 * وسائط: مجلد الصور، ملف .sba، كلمة مروره.
 */
public final class PhoneDrive {
    static Robot robot;
    static File out;
    static int n, fails;
    static final List<String> log = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        out = new File(a[0]);
        out.mkdirs();
        File home = java.nio.file.Files.createTempDirectory("sa-drive").toFile();
        System.setProperty("sa.home", home.getAbsolutePath());
        System.setProperty("sa.secrets", "file");
        System.setProperty("sa.auth", "fail");   // 11.5: «تحقق النظام» متاح ويفشل (للقفل في آخر التجربة)
        SecretStore.fallback(new File(home, "keys"));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { fails++; e.printStackTrace(); });
        Context ctx = new Context(home);
        Crypto.init(ctx);
        Db db = new Db(ctx);
        SmartApp.db = db;
        SmartApp.ctx = ctx;
        License.load(ctx, db);
        BackupManager.Prepared p = BackupManager.prepareRestore(ctx, new File(a[1]), a[2].toCharArray(), m -> {});
        BackupManager.applyRestore(ctx, db, p, m -> {});
        db.setSetting("office_name", "مكتب التجربة");
        SwingUtilities.invokeAndWait(() -> {
            com.smart.hawala.desk.Main.lafForTests();
            com.smart.hawala.desk.PhoneWindow.open(ctx, db);
        });
        robot = new Robot();
        robot.setAutoDelay(30);
        wait(1500);
        shot("home");
        clickText("لاحقًا");
        wait(600);
        shot("home-clear");
        // 1) التبويب «المكاتب» من الشريط السفلي
        clickTab(1, 6);
        wait(900);
        shot("offices");
        // 2) البحث في المكاتب بالكتابة
        clickHint("ابحث باسم المكتب");
        type("al");
        wait(300);
        key(KeyEvent.VK_A, true);
        paste("الأ");
        wait(500);
        shot("offices-search");
        // 3) فتح مكتب
        clickText("الأمل");
        wait(900);
        shot("office");
        // 12.4: تبويب «أمانات وخاصة»: الحركات الخاصة ثم الأمانات
        performClickOn("أمانات وخاصة");
        wait(600);
        expect(findQuiet("الحركات الخاصة") && findQuiet("الأمانات والمبالغ المجمّدة"), "12.4 office tab: specials + holds");
        shot("office-specials");
        performClickOn("الحركات");
        wait(500);
        // 4) حركة جديدة لهذا المكتب
        clickText("حركة جديدة");
        wait(1000);
        shot("tx-form");
        clickText("لنا (مدين)");
        clickHint("0");
        type("250");
        wait(300);
        shot("tx-filled");
        clickText("تسجيل الحركة");
        wait(900);
        shot("tx-confirm");
        clickText("تسجيل");
        wait(1200);
        shot("tx-saved");
        // 5) القائمة المنسدلة والتقويم في السجل
        key(KeyEvent.VK_F, true);
        wait(900);
        shot("search");
        // 6) نافذة اختيار المكتب في «كشف حساب» من الرئيسية
        key(KeyEvent.VK_H, true);
        wait(900);
        clickText("كشف مكتب");
        wait(900);
        shot("picker");
        key(KeyEvent.VK_ESCAPE, false);
        wait(500);
        // 7) المزيد ← المظهر ← الوضع الليلي
        clickTab(4, 6);
        wait(900);
        shot("more");

        // 11.2: «العين» في خانة السر تُظهر ما كُتب وتخفيه
        final EditText[] pe = {null};
        SwingUtilities.invokeAndWait(() -> {
            MainActivity act = com.smart.hawala.desk.PhoneWindow.activityForTests();
            EditText e = new EditText(act);
            Ui.secret(e, false);
            e.setHint("سر التجربة");
            new android.app.AlertDialog.Builder(act).setTitle("تجربة العين").setView(e).setPositiveButton("تم", null).show();
            pe[0] = e;
        });
        wait(900);
        clickHint("سر التجربة");
        wait(300);
        paste("abc123");
        wait(500);
        expect(fieldShowing(javax.swing.JPasswordField.class) != null, "password field hides text");
        final Point[] eye = {null};
        SwingUtilities.invokeAndWait(() -> {
            EditText e = pe[0];
            float d = e.getResources().getDisplayMetrics().density;
            eye[0] = phys(e, e.getPaddingLeft() + 11 * d, e.getHeight() / 2f);
        });
        press(eye[0]);
        wait(700);
        shot("eye-shown");
        javax.swing.text.JTextComponent vis = fieldShowing(javax.swing.JTextField.class);
        expect(vis != null && !(vis instanceof javax.swing.JPasswordField) && "abc123".equals(vis.getText()), "eye shows the typed text");
        expect(!(pe[0].getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod), "eye switched transformation");
        press(eye[0]);
        wait(700);
        expect(fieldShowing(javax.swing.JPasswordField.class) != null && "abc123".equals(new String(fieldShowing(javax.swing.JPasswordField.class).getPassword())), "eye hides again, text kept");
        key(KeyEvent.VK_ESCAPE, false);
        wait(500);

        // 11.2: مشاركة صورة مطابقة (content://…files/…): قائمة المشاركة ثم «نسخ للصق»
        final File[] img = {null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                MainActivity act = com.smart.hawala.desk.PhoneWindow.activityForTests();
                File dir = new File(act.getCacheDir(), "shared");
                dir.mkdirs();
                File f = new File(dir, "مطابقة-تجربة.png");
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
                javax.imageio.ImageIO.write(bi, "png", f);
                img[0] = f;
                act.shareFiles(java.util.Collections.singletonList(f), "image/png", "مطابقة");
                img[0] = f;
            } catch (Exception ex) {
                fails++;
                log.add("share setup: " + ex);
            }
        });
        wait(900);
        shot("share-sheet");
        // (لا يُضغط واتساب في التجربة: لا اتصال بالإنترنت)
        // 11.6: للملفات ما يعمل فقط: سحب الملف من المجلد (ومشاركة النظام على الماك والويندوز)، بلا «نسخ ولصق» ولا تلغرام
        // 12.0: شبكة بأيقونات: للملف سحب من المجلد، وواتساب ويب، وتلغرام، والحفظ… ولا «نسخ ولصق في تطبيق واتساب»
        expect(findQuiet("يُظهر الملف لتسحبه إلى المحادثة") && findQuiet("واتساب ويب") && findQuiet("تلغرام") && findQuiet("حفظ نسخة") && findQuiet("في المجلد")
                && !findQuiet("يفتح التطبيق والنص جاهز") && !findQuiet("نسخ النص"), "file share grid: drag, web, telegram, save; no app copy/paste");
        expect(!findQuiet("اجعل التطبيق"), "no 'always use' option");
        clickText("إلغاء");
        wait(500);
        // النص: القائمة تظهر دائمًا (حتى مع اختيار ثابت قديم محفوظ)
        SwingUtilities.invokeAndWait(() -> {
            com.smart.hawala.desk.PhoneWindow.activityForTests().getSharedPreferences(android.app.ShareSheet.PREFS, 0).edit().putString("text", "wa_app").apply();
            android.app.ShareSheet.share(com.smart.hawala.desk.PhoneWindow.activityForTests(), new ArrayList<>(), "نص المطابقة", "مطابقة");
        });
        wait(800);
        shot("share-text");
        expect(findQuiet("واتساب") && findQuiet("تلغرام") && findQuiet("نسخ النص") && findQuiet("يفتح التطبيق والنص جاهز"), "text share grid always shown with WhatsApp/Telegram/copy");
        clickText("نسخ النص");
        wait(500);
        java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        boolean txt = false;
        try { txt = "نص المطابقة".equals(String.valueOf(cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor))); } catch (Exception ex) { /* لا نص */ }
        expect(txt, "text copied");

        // 11.3: نوافذ المظهر الجديدة (حفظ نمط، ومنتقي اللون بخانة الرمز) تُفتح دون خطأ «already has a parent»
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().designScreen.show());
        wait(1200);
        int before = fails;
        clickText("حفظ الشكل الحالي كنمط…");
        wait(800);
        expect(findQuiet("حفظ الشكل الحالي") && fails == before, "save style dialog opens");
        clickText("حفظ");
        wait(1000);
        expect(findQuiet("نمطي 1"), "style saved and listed");
        final int fb = fails;
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().designScreen
                .paletteDialog("اللون الأساسي", DesignScreen.PALETTE, false, "#0B6B53", h -> { }));
        wait(900);
        expect(fails == fb, "color picker: no layout error");
        expect(findQuiet("اختيار هذا اللون"), "color picker opens with hex field");
        key(KeyEvent.VK_ESCAPE, false);
        wait(500);
        key(KeyEvent.VK_H, true);
        wait(800);

        // 11.3: نسخ عدة رسائل من واتساب ديسكتوب: Ctrl+V في الرئيسية، ولصقها في خانة الرسالة
        String two = "[14/11, 18:14] ابو النور: استلمنا 500 دولار من سامر\n[14/11, 18:15] ابو النور: سلمنا 300 يورو لأحمد\n";
        clickTab(0, 6);
        wait(800);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(two), null);
        robot.mouseMove(20, 500);
        key(KeyEvent.VK_V, true);
        wait(1000);
        shot("paste-two-home");
        expect(findQuiet("رسائل من واتساب"), "Ctrl+V on home splits two messages");
        key(KeyEvent.VK_N, true);
        wait(1000);
        clickHint("الصق رسالة");
        wait(300);
        paste(two);
        wait(900);
        shot("paste-two-field");
        expect(findQuiet("تسجيل كل رسالة وحدها"), "pasting two messages into the field offers splitting");
        clickText("تسجيل كل رسالة وحدها");
        wait(900);
        expect(findQuiet("رسائل من واتساب"), "split list opened from field paste");
        key(KeyEvent.VK_H, true);
        wait(800);

        // 12.1: نقل الرسائل من واتساب ويندوز والمتصفح (HTML الحافظة)، والقص اليدوي، ووضع التجميع، وإطار الالتقاط، وفحص الحافظة
        waMove12_1(db);

        // 11.2: انتظار الدخول إلى Google يُلغى ولا يعلّق البرنامج (دخول وهمي، بلا اتصال)
        final boolean[] called = {false};
        SwingUtilities.invokeAndWait(() -> DriveAuth.waitLogin(com.smart.hawala.desk.PhoneWindow.activityForTests(), () -> {
            Thread.sleep(20000);
            return "x@example.com";
        }, r -> called[0] = true, e -> called[0] = true));
        wait(900);
        shot("google-wait");
        expect(findQuiet("الدخول إلى Google"), "google wait dialog shown with cancel");
        clickText("إلغاء");
        wait(700);
        expect(!findQuiet("الدخول إلى Google"), "google wait cancelled at once");
        key(KeyEvent.VK_H, true);
        wait(800);
        expect(findQuiet("صافي الأرصدة") || findQuiet("ابحث"), "app responsive after cancel");

        // 11.5: أكثر من 6 عناصر في الشريط السفلي على الكمبيوتر
        final int[] mx = {0};
        SwingUtilities.invokeAndWait(() -> mx[0] = com.smart.hawala.desk.PhoneWindow.activityForTests().maxTabs());
        db.setSettingQuiet("tabs", "home,offices,tx,log,reports,balances,daily,more");
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().showHome());
        wait(1200);
        shot("tabs-eight");
        final int[] nt = {0};
        SwingUtilities.invokeAndWait(() -> nt[0] = com.smart.hawala.desk.PhoneWindow.activityForTests().tabKeys().size());
        expect(mx[0] > 6 && nt[0] == Math.min(8, mx[0]), "bottom bar allows more than 6 on desktop (max " + mx[0] + ", shown " + nt[0] + ")");
        db.setSettingQuiet("tabs", "");
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().showHome());
        wait(800);

        // 11.5: نافذة اختيار المكتب: البحث يعمل، والقسمان يُطويان ويُحفظ وضعهما
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().officePicker(o -> { }));
        wait(900);
        shot("picker-new");
        expect(findQuiet("كل المكاتب ("), "picker shows all-offices header");
        clickHint("ابحث باسم المكتب");
        paste("امل");
        wait(700);
        shot("picker-search");
        expect(findQuiet("نتائج البحث: 1") && findQuiet("الأمل"), "picker search finds office ignoring hamza");
        key(KeyEvent.VK_ESCAPE, false);
        wait(500);

        // 11.5: البحث المتقدم من مركز التقارير: اختيار المكتب من القائمة ثم «تطبيق الفلاتر» يصفّي فعلًا
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().logScreen.show(true));
        wait(1500);
        final Point[] sp = {null};
        SwingUtilities.invokeAndWait(() -> { for (ViewRootImpl r : WindowStack.roots()) { View v = findClass(r.getView(), "Spinner"); if (v != null && sp[0] == null) sp[0] = phys(v, v.getWidth() / 2f, v.getHeight() / 2f); } });
        press(sp[0]);
        wait(800);
        shot("filter-office-list");
        click("الأمل", true);
        wait(600);
        clickText("تطبيق الفلاتر");
        wait(1800);
        shot("filter-office-applied");
        expect(findQuiet("المكتب: الأمل"), "office filter applied in advanced search");
        key(KeyEvent.VK_H, true);
        wait(800);

        // 11.6: قراءة رمز QR الترخيص من صورة PNG (ZXing) على الكمبيوتر
        {
            String link = "smartacc://activate?k=" + "dj0xO2tpZD1rMTtsaWQ9U0EtMjAyNi0wMDAx".repeat(4) + ".MEUCIQDx9dAyiOI8tq8eBaEbS0g9n1bBkOVv7lj8C2dOq6Js4QIgUqFzVBwV1gO8jWQ4Tt3fYIxm2";
            com.google.zxing.common.BitMatrix bmx = new com.google.zxing.qrcode.QRCodeWriter().encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600);
            java.awt.image.BufferedImage qi = new java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) qi.setRGB(x, y, bmx.get(x, y) ? 0 : 0xFFFFFF);
            java.io.ByteArrayOutputStream qo = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(qi, "png", qo);
            android.graphics.Bitmap qb = android.graphics.BitmapFactory.decodeStream(new java.io.ByteArrayInputStream(qo.toByteArray()));
            String got = QrRead.decode(qb);
            expect(link.equals(got), "QR image decodes on desktop");
            expect(LicenseToken.extract(got).indexOf('.') > 0 && !LicenseToken.extract(got).contains("smartacc"), "token extracted from QR link");
        }
        // 11.6: سطر الميزان في المطابقة المرسلة
        {
            final String[] sl = {null};
            java.util.Map<String, Long> bm = new java.util.LinkedHashMap<>();
            bm.put("USD", 150000L);
            bm.put("EUR", 0L);
            SwingUtilities.invokeAndWait(() -> sl[0] = com.smart.hawala.desk.PhoneWindow.activityForTests().reconScreen.scaleLine(bm, null));
            expect(sl[0] != null && sl[0].contains("الميزان") && sl[0].contains("1,500"), "recon scale line: " + sl[0]);
        }

        // 11.6: نافذة عريضة: الصفحة تملأ النافذة، والزر لا يتمدد بعرضها كله ولا يكبر فوق الحجم الطبيعي
        {
            final java.awt.Dimension keep = com.smart.hawala.desk.PhoneWindow.frame().getSize();
            SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.frame().setSize(1400, 850));
            wait(900);
            SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().licenseScreen.show());
            wait(1200);
            shot("wide-window");
            final int[] wv = {0, 0, 0};
            SwingUtilities.invokeAndWait(() -> {
                for (ViewRootImpl r : WindowStack.roots()) {
                    List<View> hits = new ArrayList<>();
                    collect(r.getView(), "التفعيل من ملف المفتاح (.sakey)", false, hits);
                    if (!hits.isEmpty()) { wv[0] = hits.get(0).getWidth(); wv[1] = r.getView().getWidth(); wv[2] = hits.get(0).getHeight(); }
                }
            });
            expect(wv[1] > 0 && wv[0] < wv[1] * 0.7 && wv[0] > 200, "wide window: page fills, button capped (" + wv[0] + " of " + wv[1] + ", h " + wv[2] + ")");
            SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.frame().setSize(keep));
            wait(700);
            key(KeyEvent.VK_H, true);
            wait(600);
        }

        // 11.6: نافذة المستخدم: الصلاحيات «أساسي» و«متقدم»، وقوالب محفوظة
        {
            java.lang.reflect.Method ud = UsersScreen.class.getDeclaredMethod("userDialog", Model.User.class, boolean.class);
            ud.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> { try { ud.invoke(com.smart.hawala.desk.PhoneWindow.activityForTests().usersScreen, null, false); } catch (Exception e) { throw new RuntimeException(e); } });
            wait(900);
            shot("user-perms");
            // 12.0: أقسام التطبيق، الأول مفتوح والبقية مطوية بسهم، ومع كل قسم عدد المختار
            expect(findQuiet("الحركات") && findQuiet("قص العملات") && findQuiet("المزامنة") && !findQuiet("رؤية أجهزة المزامنة وسجلاتها") && findQuiet("حفظ كقالب…"),
                    "user dialog: sections, first open, others collapsed, presets");
            SwingUtilities.invokeAndWait(() -> {   // فتح قسم «المزامنة» (تحت)
                for (ViewRootImpl r : WindowStack.roots()) {
                    View v = withDesc(r.getView(), "المزامنة");
                    if (v != null) v.performClick();
                }
            });
            wait(500);
            shot("user-perms-adv");
            expect(findQuiet("رؤية أجهزة المزامنة وسجلاتها"), "opening a section shows its detailed permissions");
            clickText("حفظ كقالب…");
            wait(600);
            clickHint("اسم القالب، مثل: محاسب فرع");
            paste("محاسب فرع");
            shot("preset-name");
            final Point[] sv = {null};   // «حفظ» نافذة القالب (الأعلى على الشاشة؛ «حفظ» نافذة المستخدم أسفلها)
            SwingUtilities.invokeAndWait(() -> {
                int best = Integer.MAX_VALUE;
                for (ViewRootImpl r : WindowStack.roots()) {
                    List<View> hits = new ArrayList<>();
                    collect(r.getView(), "حفظ", false, hits);
                    for (View v : hits) {
                        if (!"حفظ".contentEquals(((TextView) v).getText())) continue;
                        int[] l = new int[2];
                        v.getLocationOnScreen(l);
                        if (l[1] < best) { best = l[1]; sv[0] = phys(v, v.getWidth() / 2f, v.getHeight() / 2f); }
                    }
                }
            });
            press(sv[0]);
            wait(600);
            shot("preset-saved");
            expect(com.smart.hawala.desk.PhoneWindow.activityForTests().usersScreen.presets().containsKey("محاسب فرع"), "permission preset saved by name");
            key(KeyEvent.VK_ESCAPE, false);
            wait(500);
            key(KeyEvent.VK_H, true);
            wait(600);
        }

        // 11.5: الرجوع خطوة خطوة: المزيد ← المظهر ← سهم الرجوع يعيد «المزيد» (لا الرئيسية)، ثم الرئيسية
        clickTab(4, 6);
        wait(900);
        final String[] t0 = {null};
        SwingUtilities.invokeAndWait(() -> t0[0] = com.smart.hawala.desk.PhoneWindow.activityForTests().screenTitle.getText().toString());
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().designScreen.show());
        wait(1000);
        final Point[] bk = {null};
        SwingUtilities.invokeAndWait(() -> { for (ViewRootImpl r : WindowStack.roots()) { View v = withDesc(r.getView(), "رجوع"); if (v != null) bk[0] = phys(v, v.getWidth() / 2f, v.getHeight() / 2f); } });
        expect(bk[0] != null, "inner page has a back arrow");
        press(bk[0]);
        wait(1000);
        final String[] t1 = {null};
        SwingUtilities.invokeAndWait(() -> t1[0] = com.smart.hawala.desk.PhoneWindow.activityForTests().screenTitle.getText().toString());
        shot("back-step");
        expect(t0[0] != null && t0[0].equals(t1[0]), "back arrow returns to the previous page (" + t0[0] + " / " + t1[0] + ")");
        key(KeyEvent.VK_H, true);
        wait(800);

        // 11.5: قفل التطبيق بعد ترك النافذة مصغّرة: شاشة القفل تظهر بأزرارها (لا شاشة فارغة)، وتحقق النظام إن فشل لا يتركها فارغة
        // (والمشاركة السابقة في هذه التجربة لا تُعفي أول تصغير حقيقي من القفل)
        db.setSettingQuiet("app_lock", "bio");
        db.setSettingQuiet("biometric", "1");
        db.setSettingQuiet("lock_timeout", "0");
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().pauseFromWindow());
        wait(1500);
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().resumeFromWindow());
        wait(2500);
        shot("lock-after-idle");
        expect(findQuiet("التطبيق مقفل"), "app locked after idle");
        expect(findQuiet("فتح التطبيق"), "lock screen shows unlock button");
        final boolean[] arrow = {false};
        SwingUtilities.invokeAndWait(() -> { for (ViewRootImpl r : WindowStack.roots()) if (hasDesc(r.getView(), "رجوع")) arrow[0] = true; });
        expect(!arrow[0], "no back arrow on the lock screen");
        key(KeyEvent.VK_ESCAPE, false);
        wait(500);
        expect(findQuiet("فتح التطبيق"), "Esc does not leave the lock screen");
        System.setProperty("sa.auth", "yes");
        clickText("فتح التطبيق");
        wait(1500);
        shot("lock-opened");
        expect(!findQuiet("التطبيق مقفل"), "unlock works after retry");
        db.setSettingQuiet("app_lock", "off");
        db.setSettingQuiet("biometric", "0");
        System.out.println(fails == 0 ? "DRIVE OK" : "DRIVE FAILURES: " + fails);
        for (String l : log) System.out.println(l);
        System.exit(fails == 0 ? 0 : 1);
    }

    /** محتوى حافظة بنص عادي ونسخة HTML (كما يضعهما واتساب ديسكتوب على ويندوز والمتصفح). */
    static void setHtmlClip(final String text, final String html) {
        final java.awt.datatransfer.DataFlavor hf;
        try {
            hf = new java.awt.datatransfer.DataFlavor("text/html;class=java.lang.String");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.awt.datatransfer.Transferable t = new java.awt.datatransfer.Transferable() {
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.stringFlavor, hf}; }
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) { return f.equals(java.awt.datatransfer.DataFlavor.stringFlavor) || f.getMimeType().startsWith("text/html"); }
            public Object getTransferData(java.awt.datatransfer.DataFlavor f) { return f.equals(java.awt.datatransfer.DataFlavor.stringFlavor) ? text : html; }
        };
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
    }

    static void setClip(String s) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(s), null); }

    /** 12.4: يضغط أول أب قابل للضغط لنص (حتى لو كان تحت حافة الشاشة). */
    static void performClickOn(final String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (ViewRootImpl rt : WindowStack.roots()) {
                List<View> hits = new ArrayList<>();
                collect(rt.getView(), text, false, hits);
                for (View v : hits) {
                    if (!((TextView) v).getText().toString().trim().equals(text)) continue;
                    View p = v;
                    while (p != null && !p.isClickable()) p = p.getParent() instanceof View ? (View) p.getParent() : null;
                    if (p != null) {
                        p.performClick();
                        return;
                    }
                }
            }
        });
    }

    static String formText() throws Exception {
        final String[] r = {null};
        SwingUtilities.invokeAndWait(() -> {
            for (ViewRootImpl rt : WindowStack.roots()) {
                List<View> hits = new ArrayList<>();
                collectHint(rt.getView(), "الصق رسالة", hits);
                for (View v : hits) r[0] = ((TextView) v).getText().toString();
            }
        });
        return r[0];
    }

    static void collectHint(View v, String hint, List<View> hits) {
        if (!v.isShown()) return;
        if (v instanceof TextView) {
            CharSequence h = ((TextView) v).getHint();
            if (h != null && h.toString().contains(hint)) hits.add(v);
        }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collectHint(((ViewGroup) v).getChildAt(i), hint, hits);
    }

    static void waMove12_1(Db db) throws Exception {
        // أ) HTML من واتساب ديسكتوب: النص العادي ملزوق بلا رؤوس، فتُقسم الفقاعات من HTML
        String plain = "تجربة 100 دولار لأحمد\n10:32\nاستلام 50 يورو من سامي\n10:33";
        String html = "<div class=\"message-in\"><div class=\"copyable-text\" data-pre-plain-text=\"[10:32, 17/11/2026] مكتب التجربة: \"><span>تجربة 100 دولار لأحمد</span></div><span>10:32</span></div>"
                + "<div class=\"message-in\"><div class=\"copyable-text\" data-pre-plain-text=\"[10:33, 17/11/2026] مكتب التجربة: \"><span>استلام 50 يورو من سامي</span></div><span>10:33</span></div>";
        key(KeyEvent.VK_H, true);
        wait(700);
        setHtmlClip(plain, html);
        robot.mouseMove(20, 500);
        key(KeyEvent.VK_V, true);
        wait(1000);
        shot("wa-html-two");
        expect(findQuiet("رسائل من واتساب") && findQuiet("2 رسائل"), "12.1 HTML clipboard splits two WhatsApp-desktop bubbles");
        key(KeyEvent.VK_H, true);
        wait(700);
        // ب) نص ملزوق بأسطر فارغة: «قص الرسائل»
        setClip("حوالة 100 دولار لأحمد\nرقم 55\n\nحوالة 70 يورو لسامي\n\nحوالة 20 دولار لعلي");
        key(KeyEvent.VK_V, true);
        wait(900);
        expect(findQuiet("هل هذا النص أكثر من رسالة؟"), "12.1 glued text offers manual cut");
        clickText("✂ قص الرسائل");
        wait(900);
        shot("wa-cut");
        expect(findQuiet("قص الرسائل") && findQuiet("متابعة (3 رسائل)"), "12.1 cut screen pre-marks 3 messages");
        click("✂ ─────── رسالة جديدة ───────", true);
        wait(400);
        expect(findQuiet("متابعة (2 رسائل)"), "12.1 tapping a cut removes it");
        clickText("متابعة (2 رسائل)");
        wait(900);
        expect(findQuiet("رسائل من واتساب") && findQuiet("2 رسائل"), "12.1 cut result opens the batch list");
        key(KeyEvent.VK_H, true);
        wait(700);
        // ج) وضع التجميع: كل نسخة رسالة
        clickText("وضع التجميع");
        wait(900);
        setClip("حوالة 300 دولار للأخ سامر");
        wait(2200);
        setClip("حوالة 40 يورو لأبو علي");
        wait(2200);
        shot("wa-collect");
        expect(findQuiet("جُمعت 2 رسائل"), "12.1 collect mode gathered two copies");
        // 12.2: نافذة التجميع الصغيرة فوق كل النوافذ، فيها 1 و2 وزر «نقل (2)»
        final DeskCollect dc = DeskCollect.last;
        final boolean[] cw = {false};
        SwingUtilities.invokeAndWait(() -> cw[0] = dc != null && dc.frame() != null && dc.frame().isShowing() && dc.rows() == 2
                && dc.moveButton().getText().equals("نقل (2)") && dc.frame().isAlwaysOnTop());
        expect(cw[0], "12.2 small collect window lists 1 and 2 with «نقل (2)»");
        if (dc != null && dc.frame() != null) {
            BufferedImage ci = robot.createScreenCapture(dc.frame().getBounds());
            ImageIO.write(ci, "png", new File(out, String.format("%02d-%s.png", ++n, "collect-window")));
        }
        clickText("نقل");
        wait(900);
        expect(findQuiet("رسائل من واتساب") && findQuiet("2 رسائل"), "12.1 collected messages open as a batch");
        SwingUtilities.invokeAndWait(() -> cw[0] = dc.frame() == null);
        expect(cw[0], "12.2 collect window closes after «نقل»");
        key(KeyEvent.VK_H, true);
        wait(700);
        // 12.3: الرئيسية: لا «نافذة واتساب»، و«قص الرسائل» ظاهر ويفتح شاشة القص للنص المنسوخ
        expect(!findQuiet("نافذة واتساب") && findQuiet("التقاط من الشاشة") && findQuiet("قص الرسائل"), "12.3 home: capture + collect + cut, no «نافذة واتساب»");
        setClip("حوالة 100 دولار لسامر\nحوالة 200 يورو لأبو علي\nحوالة 50 دولار لخالد");
        wait(400);
        clickText("قص الرسائل");
        wait(900);
        shot("cut-screen");
        expect(findQuiet("متابعة (3 رسائل)"), "12.3 «قص الرسائل» opens the cut screen with 3 suggested messages (same opener «حوالة»)");
        key(KeyEvent.VK_H, true);
        wait(700);
        // 12.2: «🧺 تجميع» في نموذج التسجيل بجانب «التقاط»: رسالة واحدة ← خانة النموذج المفتوح مباشرة
        key(KeyEvent.VK_N, true);
        wait(1000);
        expect(findQuiet("تجميع") && findQuiet("قص") && findQuiet("مسح") && findQuiet("لصق"), "12.2/12.3 form toolbar: paste, capture, collect, cut, clear");
        shot("form-toolbar");
        // 12.4: «حركة خاصة» في نموذج التسجيل: الأنواع وحسابها في الرصيد
        expect(findQuiet("حركة خاصة"), "12.4 special section in the form");
        performClickOn("حركة خاصة");
        wait(500);
        expect(findQuiet("ناقصة أجور") && findQuiet("تحتاج مراجعة") && findQuiet("لا تُحسب في الرصيد") && findQuiet("+ نوع جديد"), "12.4 special types and balance choice");
        shot("special-form");
        performClickOn("حركة خاصة");
        wait(400);
        clickText("تجميع");
        wait(900);
        setClip("حوالة 75 دولار لأم خالد");
        wait(2200);
        SwingUtilities.invokeAndWait(() -> cw[0] = dc.frame() != null && dc.rows() == 1);
        expect(cw[0], "12.2 form collect shows the copied message as 1");
        SwingUtilities.invokeAndWait(() -> dc.moveButton().doClick());
        wait(1000);
        String ft = formText();
        expect(ft != null && ft.contains("حوالة 75 دولار لأم خالد"), "12.2 «نقل» of one message fills the open form: " + ft);
        // نافذة التجميع أُغلقت: نضغط على البرنامج حتى تعود لوحة المفاتيح إليه (Xvfb بلا مدير نوافذ لا ينقلها وحده)
        clickText("رسالة واتساب");
        wait(300);
        key(KeyEvent.VK_H, true);
        wait(700);
        // د) مراقبة الحافظة: بطاقة «في الحافظة N رسائل من واتساب» في الرئيسية
        setClip("[14/11, 18:14] ابو النور: استلمنا 500 دولار\n[14/11, 18:15] ابو النور: سلمنا 300 يورو");
        wait(2200);
        shot("wa-clipcard");
        expect(findQuiet("في الحافظة 2 رسائل من واتساب"), "12.1 clipboard watcher shows the move card");
        // هـ) إطار الالتقاط (قارئ وهمي على لينكس): أرقام الفقاعات، والضغط ينقل النص إلى خانة النموذج المفتوح
        File fake = File.createTempFile("sa-fake-", ".txt");
        java.util.Base64.Encoder b = java.util.Base64.getEncoder();
        String nodes = "W\t0\t0\t400\t500\t" + b.encodeToString("WhatsApp".getBytes("UTF-8")) + "\n"
                + "T\t120\t4\t200\t24\t" + b.encodeToString("مكتب الأمل للحوالات".getBytes("UTF-8")) + "\n"
                + "R\t40\t60\t300\t50\t" + b.encodeToString("حوالة 250 دولار لخالد\n11:05".getBytes("UTF-8")) + "\n"
                + "R\t40\t140\t300\t50\t" + b.encodeToString("حوالة 90 يورو لمحمود\n11:06".getBytes("UTF-8")) + "\n";
        java.nio.file.Files.write(fake.toPath(), nodes.getBytes("UTF-8"));
        System.setProperty("sa.capture.fake", fake.getAbsolutePath());
        key(KeyEvent.VK_N, true);
        wait(1000);
        expect(findQuiet("التقاط"), "12.1 capture button in the form on desktop");
        clickText("التقاط");
        wait(2200);
        final DeskCapture.Win[] w = {null};
        SwingUtilities.invokeAndWait(() -> w[0] = DeskCapture.current());
        expect(w[0] != null && w[0].isShowing(), "12.1 capture frame opened");
        final javax.swing.JButton[] badge = {null, null};
        if (w[0] != null) {
            SwingUtilities.invokeAndWait(() -> {
                for (javax.swing.JButton c : w[0].badges()) {
                    if ("badge-1".equals(c.getName())) badge[0] = c;
                    if ("badge-2".equals(c.getName())) badge[1] = c;
                }
            });
            expect(badge[0] != null && badge[1] != null, "12.1 two numbered badges on the bubbles");
            // 12.2: شرح الإطار يظهر أول مرة فقط
            final boolean[] intro = {false};
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window x : java.awt.Window.getWindows())
                    if (x instanceof java.awt.Dialog && x.isShowing() && "كيف أستعمل إطار الالتقاط؟".equals(((java.awt.Dialog) x).getTitle())) {
                        intro[0] = true;
                        x.dispose();
                    }
            });
            expect(intro[0], "12.2 first-time guide for the capture frame");
            // 12.2: داخل الإطار لا تغطيه أي نافذة من البرنامج (عدا الأرقام)، فيمر الماوس والتمرير إلى واتساب
            final boolean[] free = {true};
            SwingUtilities.invokeAndWait(() -> {
                Rectangle in = w[0].inner();
                Point c = new Point(in.x + in.width / 2, in.y + in.height - 20);
                for (java.awt.Window x : java.awt.Window.getWindows())
                    if (x.isShowing() && x != com.smart.hawala.desk.PhoneWindow.frame() && x.getBounds().contains(c)) free[0] = false;
            });
            expect(free[0], "12.2 the frame's inside is not covered by any of our windows (mouse/scroll pass through)");
            expect(w[0].auto.getText().contains("قراءة تلقائية: تعمل"), "12.2 auto-read toggle on the bar: " + w[0].auto.getText());
            BufferedImage im = robot.createScreenCapture(new Rectangle(w[0].area.x, w[0].area.y - DeskCapture.Win.BAR, w[0].area.width, w[0].area.height + DeskCapture.Win.BAR));
            ImageIO.write(im, "png", new File(out, String.format("%02d-%s.png", ++n, "capture-frame")));
            expect(w[0].status.getText().contains("مكتب الأمل للحوالات"), "12.1 chat name read from the window header: " + w[0].status.getText());
            if (badge[0] != null) SwingUtilities.invokeAndWait(() -> badge[0].doClick());
            wait(900);
            String t = formText();
            expect(t != null && t.contains("حوالة 250 دولار لخالد") && !t.contains("11:05"), "12.1 badge click puts the bubble text into the form: " + t);
            // عدة فقاعات ← دفعة
            SwingUtilities.invokeAndWait(() -> w[0].multi.doClick());
            if (badge[0] != null) SwingUtilities.invokeAndWait(() -> {
                for (javax.swing.JButton c : w[0].badges()) c.doClick();
            });
            wait(300);
            SwingUtilities.invokeAndWait(() -> w[0].send.doClick());
            wait(1200);
            shot("capture-batch");
            expect(findQuiet("رسائل من واتساب") && findQuiet("2 رسائل"), "12.1 multi-select sends a batch");
            expect(findQuiet("مقترح من اسم المحادثة") && findQuiet("المكتب: الأمل"), "12.1 office suggested from the chat name");
            // 12.5: «مربع نسخ»: كل ما تحت المربع رسالة واحدة
            SwingUtilities.invokeAndWait(() -> w[0].boxBtn.doClick());
            wait(400);
            final boolean[] bm = {false};
            SwingUtilities.invokeAndWait(() -> bm[0] = w[0].boxMode && w[0].copyBtn.isVisible() && !w[0].auto.isVisible() && w[0].badges().isEmpty()
                    && w[0].area.width <= 400);
            expect(bm[0], "12.5 box mode: small box with a copy button");
            BufferedImage bi = robot.createScreenCapture(new Rectangle(w[0].area.x, w[0].area.y - DeskCapture.Win.BAR, w[0].area.width, w[0].area.height + DeskCapture.Win.BAR));
            ImageIO.write(bi, "png", new File(out, String.format("%02d-%s.png", ++n, "capture-box")));
            // القارئ الوهمي يعطي أسطرًا بمكانها في الإطار الكبير: نكبّر المربع فوقها
            SwingUtilities.invokeAndWait(() -> w[0].setArea(new Rectangle(w[0].area.x, w[0].area.y, 460, 600)));
            SwingUtilities.invokeAndWait(() -> w[0].copyBtn.doClick());
            wait(1800);
            shot("capture-box-after");
            String ft5 = formText();
            expect(findQuiet("رسائل من واتساب") || (ft5 != null && ft5.contains("حوالة 250 دولار لخالد")), "12.5 «نسخ ما في المربع» moves the text to the app: "
                    + w[0].status.getText() + " | " + ft5);
            SwingUtilities.invokeAndWait(() -> w[0].dispose());
            clickText("رسالة واتساب");
            wait(300);
            key(KeyEvent.VK_H, true);
            wait(800);
        }
        System.clearProperty("sa.capture.fake");
        fake.delete();
        key(KeyEvent.VK_H, true);
        wait(700);
        // و) فحص الحافظة: بلا محتوى
        setClip("أحمد 100");
        SwingUtilities.invokeAndWait(() -> com.smart.hawala.desk.PhoneWindow.activityForTests().waMove.inspect());
        wait(900);
        shot("clip-inspect");
        expect(findQuiet("فحص الحافظة") && findQuiet("عععع ###") && !findQuiet("أحمد 100"), "12.1 clipboard inspector masks the content");
        clickText("إغلاق");
        wait(500);
        key(KeyEvent.VK_H, true);
        wait(700);
    }

    static boolean findQuiet(String text) throws Exception {
        int f = fails;
        int sz = log.size();
        boolean ok = find(text, false, false) != null;
        fails = f;
        while (log.size() > sz) log.remove(log.size() - 1);
        return ok;
    }

    static void expect(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) { fails++; log.add("FAIL: " + what); }
    }

    /** الخانة الحقيقية الظاهرة الآن من هذا النوع (أو null). */
    static <T extends javax.swing.text.JTextComponent> T fieldShowing(Class<T> k) throws Exception {
        final Object[] r = {null};
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Component c : all(com.smart.hawala.desk.PhoneWindow.frame().getLayeredPane()))
                if (k.isInstance(c) && c.isShowing() && (k != javax.swing.JTextField.class || !(c instanceof javax.swing.JPasswordField))) r[0] = c;
        });
        return k.cast(r[0]);
    }

    static List<java.awt.Component> all(java.awt.Component c) {
        List<java.awt.Component> o = new ArrayList<>();
        o.add(c);
        if (c instanceof java.awt.Container) for (java.awt.Component x : ((java.awt.Container) c).getComponents()) o.addAll(all(x));
        return o;
    }

    static void wait(int ms) throws Exception {
        Thread.sleep(ms);
        SwingUtilities.invokeAndWait(() -> { });
    }

    static void shot(String name) throws Exception {
        Rectangle r = com.smart.hawala.desk.PhoneWindow.frameBounds();
        BufferedImage im = robot.createScreenCapture(r);
        ImageIO.write(im, "png", new File(out, String.format("%02d-%s.png", ++n, name)));
        System.out.println("shot " + name);
    }

    /** يبحث عن نص ظاهر في النوافذ (الأعلى أولًا)، ويرجع مركزه على الشاشة. */
    static Point find(String text, boolean bottom, boolean hint) throws Exception {
        final Point[] res = {null};
        SwingUtilities.invokeAndWait(() -> {
            List<ViewRootImpl> roots = WindowStack.roots();
            roots.sort((x, y) -> Integer.compare(y.layer(), x.layer()));
            for (ViewRootImpl r : roots) {
                List<View> hits = new ArrayList<>();
                collect(r.getView(), text, hint, hits);
                if (hits.isEmpty()) continue;
                List<View> exact = new ArrayList<>();
                for (View v : hits) {
                    CharSequence t = hint ? ((TextView) v).getHint() : ((TextView) v).getText();
                    if (t.toString().trim().equals(text)) exact.add(v);
                }
                if (!exact.isEmpty()) hits = exact;
                View best = hits.get(0);
                for (View v : hits) {
                    int[] l = new int[2], m = new int[2];
                    v.getLocationOnScreen(l);
                    best.getLocationOnScreen(m);
                    if (bottom ? l[1] > m[1] : l[1] < m[1]) best = v;
                }
                res[0] = phys(best, best.getWidth() / 2f, best.getHeight() / 2f);
                return;
            }
        });
        if (res[0] == null) { fails++; log.add("NOT FOUND: " + text); }
        return res[0];
    }

    static void collect(View v, String text, boolean hint, List<View> hits) {
        if (!v.isShown()) return;
        if (v instanceof TextView) {
            CharSequence t = hint ? ((TextView) v).getHint() : ((TextView) v).getText();
            if (t != null && t.toString().contains(text) && v.getWidth() > 0) hits.add(v);
        }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), text, hint, hits);
    }

    static void click(String text, boolean bottom) throws Exception { press(find(text, bottom, false)); }
    static void clickText(String text) throws Exception { press(find(text, false, false)); }
    static void clickHint(String text) throws Exception { press(find(text, false, true)); }

    /** يضغط تبويبًا في الشريط السفلي الزجاجي (يُرسم بلا نصوص عناصر): i من اليمين. */
    static void clickTab(int i, int count) throws Exception {
        final Point[] r = {null};
        SwingUtilities.invokeAndWait(() -> {
            for (ViewRootImpl root : WindowStack.roots()) {
                View bar = findClass(root.getView(), "NotchBar");
                if (bar == null) continue;
                float d = bar.getResources().getDisplayMetrics().density;
                float cell = (bar.getWidth() - 24 * d) / count;
                r[0] = phys(bar, bar.getWidth() - 12 * d - (i + 0.5f) * cell, bar.getHeight() - 30 * d);
            }
        });
        if (r[0] == null) { fails++; log.add("NO TAB BAR"); return; }
        press(r[0]);
    }

    /** 11.0: نقطة داخل عنصر بإحداثيات الشاشة الحقيقية (مع مقياس الرسم العام للنافذة). */
    static Point phys(View v, float x, float y) {
        float[] o = {x, y};
        v.mapToRoot(o);
        ViewRootImpl root = null;
        for (ViewRootImpl r : WindowStack.roots()) if (v.getRootView() == r.getView()) root = r;
        if (root == null) return null;
        Point s = root.pane.getLocationOnScreen();
        float sc = ViewRootImpl.scale();
        return new Point(s.x + Math.round(o[0] * sc), s.y + Math.round(o[1] * sc));
    }

    static View withDesc(View v, String d) {
        if (!v.isShown()) return null;
        if (v.getContentDescription() != null && d.contentEquals(v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int k = 0; k < ((ViewGroup) v).getChildCount(); k++) { View f = withDesc(((ViewGroup) v).getChildAt(k), d); if (f != null) return f; }
        return null;
    }

    static boolean hasDesc(View v, String d) {
        if (!v.isShown()) return false;
        if (v.getContentDescription() != null && d.contentEquals(v.getContentDescription())) return true;
        if (v instanceof ViewGroup) for (int k = 0; k < ((ViewGroup) v).getChildCount(); k++) if (hasDesc(((ViewGroup) v).getChildAt(k), d)) return true;
        return false;
    }

    static View findClass(View v, String name) {
        if (v.getClass().getSimpleName().equals(name)) return v;
        if (v instanceof ViewGroup) for (int k = 0; k < ((ViewGroup) v).getChildCount(); k++) { View f = findClass(((ViewGroup) v).getChildAt(k), name); if (f != null) return f; }
        return null;
    }

    static void press(Point p) throws Exception {
        if (p == null) return;
        robot.mouseMove(p.x, p.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        wait(250);
    }

    static void type(String s) throws Exception {
        for (char c : s.toCharArray()) {
            int k = KeyEvent.getExtendedKeyCodeForChar(c);
            robot.keyPress(k);
            robot.keyRelease(k);
        }
        wait(200);
    }

    static void paste(String s) throws Exception {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(s), null);
        key(KeyEvent.VK_V, true);
    }

    static void key(int k, boolean menu) throws Exception {
        if (menu) robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(k);
        robot.keyRelease(k);
        if (menu) robot.keyRelease(KeyEvent.VK_CONTROL);
        wait(200);
    }
}
