package com.smart.hawala.desk;

import android.content.Context;
import com.smart.hawala.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.swing.*;

/**
 * تشغيل «المحاسب الذكي» على الماك: مجلد البيانات في ~/Library/Application Support/SmartAccountant،
 * ومفتاح التشفير في سلسلة المفاتيح، ثم قاعدة البيانات نفسها التي في الهاتف (Db) والترخيص، ثم النافذة.
 */
public final class Main {
    private Main() {}

    static File home;
    private static FileLock instanceLock;

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "المحاسب الذكي");
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameAqua");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        // 11.5: اتصال Google والخادم يمر بإعدادات الوكيل (Proxy/VPN) في النظام كما يفعل المتصفح؛ بدونها كان
        // الاتصال يُقطع أثناء المصافحة («Remote host terminated the handshake») في الشبكات التي تحتاج الوكيل.
        if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log(e));
        // الأرقام والتواريخ بالأرقام اللاتينية كما في تطبيق الهاتف (حتى لو كانت لغة الماك عربية)
        java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, java.util.Locale.US);
        // 10.4: سمة FlatLaf الحديثة من البداية (حتى نوافذ ما قبل فتح البيانات)، ثم بلون المكتب ووضعه بعد فتحها
        SwingUtilities.invokeLater(() -> {
            Ui.loadFonts();
            Ui.applyColors(null);
            Ui.setupLaf();
            start();
        });
    }

    /** للتجارب: الخطوط والمظهر كما عند التشغيل. */
    public static void lafForTests() {
        Ui.loadFonts();
        Ui.applyColors(null);
        Ui.setupLaf();
    }

    static void start() {
        Ui.loadFonts();
        Ui.applyColors(null);
        home = Context.defaultHome();
        if (!singleInstance()) {
            Ui.message(null, "البرنامج مفتوح", "«المحاسب الذكي» مفتوح أصلًا في نافذة أخرى على هذا الجهاز.", JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
            return;
        }
        Context ctx = new Context(home);
        SmartApp.ctx = ctx;
        Crypto.init(ctx);
        if (!Crypto.fast()) {
            if (!keyMissing(ctx)) {
                System.exit(0);
                return;
            }
        }
        Db db;
        // 12.7: أول تشفير لقاعدة قديمة: نافذة تقدم واضحة بدل نافذة لا تظهر (التشفير على هذا الخيط، والنافذة ترسم على خيط الواجهة)
        javax.swing.JDialog enc = null;
        if (Db.needsEncryption(ctx)) {
            final javax.swing.JDialog[] box = {null};
            try {
                SwingUtilities.invokeAndWait(() -> {
                    javax.swing.JDialog d = new javax.swing.JDialog((java.awt.Frame) null, "المحاسب الذكي", false);
                    javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.BorderLayout(0, 12));
                    p.setBorder(javax.swing.BorderFactory.createEmptyBorder(22, 26, 22, 26));
                    javax.swing.JLabel l = new javax.swing.JLabel("<html><div dir='rtl' style='text-align:right;width:330px'>جارٍ تشفير بيانات المحاسب لأول مرة…<br><br>"
                            + "يحدث مرة واحدة فقط وقد يستغرق دقيقة مع البيانات الكبيرة. بياناتك تبقى كما هي، وإن أُغلق البرنامج الآن يُعاد التشفير بأمان في الفتح التالي.</div></html>");
                    l.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
                    javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
                    bar.setIndeterminate(true);
                    p.add(l, java.awt.BorderLayout.CENTER);
                    p.add(bar, java.awt.BorderLayout.SOUTH);
                    d.setContentPane(p);
                    d.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
                    d.pack();
                    d.setLocationRelativeTo(null);
                    d.setVisible(true);
                    box[0] = d;
                });
            } catch (Exception ignored) {
                // بلا نافذة: يكمل التشفير
            }
            enc = box[0];
        }
        try {
            db = new Db(ctx);
            db.getWritableDatabase();
            if (enc != null) {
                final javax.swing.JDialog e = enc;
                SwingUtilities.invokeLater(e::dispose);
            }
        } catch (Throwable t) {
            log(t);
            Ui.message(null, "تعذر فتح البيانات", "تعذر فتح قاعدة البيانات:\n" + Ui.friendly(t) + "\n\nالمجلد: " + home.getAbsolutePath(), JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        SmartApp.db = db;
        License.load(ctx, db);
        Ui.init(db);
        // 10.16: قفل البرنامج بكلمة سر (بلا نظام مستخدمين): تُطلب قبل ظهور أي بيانات
        if (DeskLock.on(db) && !DeskLock.ask(null, db)) {
            System.exit(0);
            return;
        }
        // 11.0: شاشات الهاتف نفسها (طبقة أندرويد فوق Swing)؛ الشكل القديم خيار من الإعدادات أو معالج الشكل
        if ("classic".equals(db.setting("desk_ui", "phone"))) new App(ctx, db).show();
        else PhoneWindow.open(ctx, db);
        // أول تشغيل لهذا الإصدار على هذا الجهاز: معالج الشكل مع معاينة حية في نافذة البرنامج
        if (!"done".equals(db.setting("desk_wizard", "")) && !Boolean.getBoolean("sa.noWizard"))
            SwingUtilities.invokeLater(() -> LookWizard.open(true));
    }

    /** 11.0: التبديل بين شكل الهاتف (phone) والشكل القديم (classic) دون إغلاق البرنامج؛ البيانات تبقى مفتوحة. */
    public static void switchUi(String ui) {
        Db db = SmartApp.db;
        Context ctx = SmartApp.ctx;
        if (db == null || ctx == null) return;
        boolean classic = "classic".equals(ui);
        if (classic == (App.frame != null && !PhoneWindow.isOpen())) return;
        db.setSettingQuiet("desk_ui", classic ? "classic" : "phone");
        try {
            if (classic) {
                PhoneWindow.close();
                Ui.init(db);
                new App(ctx, db).show();
            } else {
                if (App.instance != null) App.instance.stop();
                if (App.frame != null) App.frame.dispose();
                App.frame = null;
                PhoneWindow.open(ctx, db);
            }
        } catch (Throwable t) {
            log(t);
            Ui.message(null, "تعذر تغيير الشكل", Ui.friendly(t), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** بعد التراجع إلى نسخة أمان: قاعدة جديدة، فتُبنى النافذة من جديد. */
    static void restart(App old, Db fresh) { restart(old, fresh, "عادت البيانات إلى نسخة الأمان"); }

    static void restart(App old, Db fresh, String msg) {
        SmartApp.db = fresh;
        SmartApp.currentUser = null;
        Db.currentUserName = "المستخدم المحلي";
        License.load(old.ctx, fresh);
        Ui.init(fresh);
        if (App.frame != null) App.frame.dispose();
        new App(old.ctx, fresh).show();
        Ui.toast(msg);
    }

    /** المفتاح مفقود والقاعدة موجودة: لا مفتاح جديد بصمت. الخيار: استعادة نسخة احتياطية بمفتاح جديد، أو الخروج. */
    static boolean keyMissing(Context ctx) {
        int r = Ui.choose(null, "مفتاح التشفير غير متاح",
                "بيانات البرنامج موجودة على هذا الجهاز لكن مفتاح تشفيرها غير موجود في «سلسلة المفاتيح» لهذا المستخدم.\n\n"
                        + Crypto.problem + "\n\nإن نقلت مجلد البيانات من ماك آخر يدويًا فالمفتاح بقي هناك: استعمل نسخة احتياطية (.sba) بدل نقل المجلد.\n\n"
                        + "يمكنك البدء بمفتاح جديد ثم استعادة آخر نسخة احتياطية (الحقول المشفرة الحالية لن تُقرأ، والنسخة تعيد كل شيء).",
                new String[]{"مفتاح جديد ثم أستعيد نسخة", "خروج"});
        if (r != 0) return false;
        Crypto.resetForRestore(ctx);
        return Crypto.fast();
    }

    static boolean singleInstance() {
        try {
            home.mkdirs();
            RandomAccessFile f = new RandomAccessFile(new File(home, ".lock"), "rw");
            instanceLock = f.getChannel().tryLock();
            return instanceLock != null;
        } catch (Exception e) {
            return true;
        }
    }

    /** سجل الأخطاء في مجلد البيانات (للدعم)، بلا بيانات الحسابات. */
    public static synchronized void log(Throwable t) {
        try {
            File d = new File(home == null ? Context.defaultHome() : home, "logs");
            d.mkdirs();
            File f = new File(d, "errors.txt");
            if (f.length() > 2_000_000) f.delete();
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write(("\n=== " + new Date() + " " + BuildInfo.VERSION + " mac\n" + sw).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // لا شيء
        }
    }

    /** أيقونة البرنامج (9.0، نفس أيقونة الهاتف): سهما تحويل ذهبيان حول دفتر حسابات على تدرّج من الأخضر إلى الأزرق الداكن. */
    public static Image appIcon(int size) { return icon(size); }

    static final String ARROW1 = "M27.31,44.29C29.79,37.48 34.78,31.87 41.25,28.62C47.73,25.37 55.20,24.71 62.15,26.79C69.09,28.87 74.97,33.52 78.60,39.80L82.72,37.42L78.62,49.66L68.58,45.58L72.71,43.20C69.95,38.43 65.47,34.89 60.19,33.31C54.92,31.73 49.23,32.22 44.31,34.70C39.38,37.17 35.59,41.43 33.70,46.61Z";
    static final String ARROW2 = "M80.69,63.71C78.21,70.52 73.22,76.13 66.75,79.38C60.27,82.63 52.80,83.29 45.85,81.21C38.91,79.13 33.03,74.48 29.40,68.20L25.28,70.58L29.38,58.34L39.42,62.42L35.29,64.80C38.05,69.57 42.53,73.11 47.81,74.69C53.08,76.27 58.77,75.78 63.69,73.30C68.62,70.83 72.41,66.57 74.30,61.39Z";
    static final String PAGE = "M45.5,38.5L62.5,38.5Q66.5,38.5 66.5,42.5L66.5,65.5Q66.5,69.5 62.5,69.5L45.5,69.5Q41.5,69.5 41.5,65.5L41.5,42.5Q41.5,38.5 45.5,38.5Z";
    static final String LINES = "M46.5,46.5h15v3.2h-15zM46.5,52.9h15v3.2h-15zM46.5,59.3h9.5v3.2h-9.5z";

    /** قارئ صغير لمسارات SVG (M L C Q H V Z بحروف كبيرة، وh v نسبيان) — يكفي لرسم الأيقونة بالمسارات نفسها التي في الهاتف. */
    static Path2D path(String d) {
        Path2D.Float p = new Path2D.Float();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[MLCQHVZmlcqhvz]|-?\\d*\\.?\\d+").matcher(d);
        java.util.List<String> t = new java.util.ArrayList<>();
        while (m.find()) t.add(m.group());
        float x = 0, y = 0;
        char cmd = 'M';
        int i = 0;
        while (i < t.size()) {
            String s = t.get(i);
            if (Character.isLetter(s.charAt(0))) {
                cmd = s.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.closePath();
                    continue;
                }
            }
            switch (cmd) {
                case 'M': x = f(t, i++); y = f(t, i++); p.moveTo(x, y); cmd = 'L'; break;
                case 'L': x = f(t, i++); y = f(t, i++); p.lineTo(x, y); break;
                case 'H': x = f(t, i++); p.lineTo(x, y); break;
                case 'V': y = f(t, i++); p.lineTo(x, y); break;
                case 'h': x += f(t, i++); p.lineTo(x, y); break;
                case 'v': y += f(t, i++); p.lineTo(x, y); break;
                case 'Q': {
                    float a = f(t, i++), b = f(t, i++);
                    x = f(t, i++);
                    y = f(t, i++);
                    p.quadTo(a, b, x, y);
                    break;
                }
                case 'C': {
                    float a = f(t, i++), b = f(t, i++), c = f(t, i++), e = f(t, i++);
                    x = f(t, i++);
                    y = f(t, i++);
                    p.curveTo(a, b, c, e, x, y);
                    break;
                }
                default: i++; break;
            }
        }
        return p;
    }

    private static float f(java.util.List<String> t, int i) { return Float.parseFloat(t.get(i)); }

    /** 10.13: «المحاسب الذكي» بخط كوفي (Noto Kufi Arabic عريض) مسارًا في مربع 108 — نفسه في أيقونة الهاتف. */
    static final String KUFI_TEXT = "M31.72,52.07Q31.32,52.07 31.09,51.82Q30.85,51.57 30.85,51.14Q30.85,50.71 31.08,50.46Q31.31,50.21 31.72,50.21Q32.12,50.21 32.35,50.46Q32.58,50.7 32.58,51.14Q32.58,51.57 32.34,51.82Q32.11,52.07 31.72,52.07ZM30.08,48.85Q28.89,48.85 28.19,48.16Q27.5,47.47 27.5,46.25Q27.5,45.91 27.55,45.56Q27.6,45.2 27.7,44.86Q27.8,44.53 27.91,44.27L29.62,44.27Q29.53,44.68 29.48,45.11Q29.43,45.54 29.43,45.96Q29.43,46.56 29.77,46.93Q30.1,47.3 30.65,47.3L34.85,47.3L34.85,42.44L36.78,42.44L36.78,47.3L38.47,47.3Q38.53,47.3 38.53,47.35L38.53,48.79Q38.53,48.85 38.47,48.85L30.08,48.85ZM47.6,48.85Q47.05,48.85 46.54,48.61Q46.04,48.36 45.57,47.88Q45.21,48.36 44.67,48.61Q44.13,48.85 43.41,48.85L38.34,48.85Q38.27,48.85 38.27,48.79L38.27,47.35Q38.27,47.3 38.34,47.3L39.95,47.3L39.95,43.45L41.88,43.45L41.88,47.3L42.98,47.3Q43.63,47.3 43.93,46.94Q44.23,46.59 44.23,45.8L44.23,43.45L46.16,43.45L46.16,45.95Q46.16,46.11 46.15,46.25Q46.14,46.39 46.11,46.53Q46.44,46.96 46.75,47.13Q47.06,47.3 47.57,47.3L48.37,47.3L48.37,42.44L50.3,42.44L50.3,48.85L47.6,48.85ZM56.24,47.3Q56.3,47.3 56.3,47.35L56.3,48.79Q56.3,48.85 56.24,48.85L52.69,48.85L52.69,39.01L54.62,39.01L54.62,47.3L56.24,47.3ZM56.11,48.85Q56.04,48.85 56.04,48.79L56.04,47.35Q56.04,47.3 56.11,47.3L61.34,47.3Q61.17,47.13 61.02,46.88Q60.87,46.62 60.76,46.26Q60.65,45.9 60.55,45.45Q60.46,44.99 60.33,44.7Q60.2,44.4 60.03,44.21Q59.85,44.02 59.6,43.95Q59.35,43.87 59,43.87Q58.54,43.87 58.14,43.98Q57.74,44.1 57.27,44.36L56.69,42.93Q57.26,42.62 57.93,42.47Q58.61,42.31 59.32,42.31Q59.73,42.31 60.06,42.35Q60.39,42.39 60.67,42.5Q61.13,42.65 61.47,42.95Q61.81,43.26 62.03,43.71Q62.26,44.16 62.36,44.81Q62.48,45.45 62.61,45.94Q62.75,46.43 62.92,46.77Q63.09,47.1 63.27,47.3L64.11,47.3Q64.17,47.3 64.17,47.35L64.17,48.79Q64.17,48.85 64.11,48.85L56.11,48.85ZM65.44,47.3Q65.28,46.9 65.18,46.45Q65.09,46 65.09,45.51Q65.09,44.95 65.28,44.3Q65.47,43.65 65.75,43.18Q66.38,42.82 67.08,42.63Q67.79,42.44 68.32,42.44Q68.76,42.44 69.16,42.54Q69.56,42.64 69.92,42.82Q70.65,43.19 71.05,43.89Q71.45,44.58 71.45,45.5Q71.45,45.86 71.39,46.19Q71.33,46.52 71.2,46.83Q71.44,47.09 71.74,47.19Q72.05,47.3 72.5,47.3L72.56,47.3Q72.63,47.3 72.63,47.35L72.63,48.79Q72.63,48.85 72.56,48.85L72.5,48.85Q71.81,48.85 71.31,48.66Q70.8,48.46 70.36,47.98Q69.87,48.41 69.23,48.63Q68.59,48.85 67.92,48.85L63.98,48.85Q63.92,48.85 63.92,48.79L63.92,47.35Q63.92,47.3 63.98,47.3L65.44,47.3ZM66.88,45.78Q66.88,46.11 66.96,46.49Q67.03,46.87 67.18,47.3L67.67,47.3Q68.19,47.3 68.6,47.06Q69.02,46.82 69.26,46.41Q69.51,45.99 69.51,45.5Q69.51,44.81 69.17,44.4Q68.84,44 68.28,44Q67.99,44 67.69,44.09Q67.38,44.18 67.08,44.36Q66.98,44.7 66.93,45.05Q66.88,45.41 66.88,45.78ZM72.43,48.85Q72.37,48.85 72.37,48.79L72.37,47.35Q72.37,47.3 72.43,47.3L74.05,47.3L74.05,39.01L75.98,39.01L75.98,48.85L72.43,48.85ZM78.57,39.01L80.5,39.01L80.5,48.85L78.57,48.85L78.57,39.01ZM41,68.99Q40.66,68.99 40.46,68.77Q40.26,68.55 40.26,68.19Q40.26,67.82 40.46,67.6Q40.66,67.38 41,67.38Q41.35,67.38 41.54,67.6Q41.74,67.81 41.74,68.19Q41.74,68.57 41.54,68.78Q41.34,68.99 41,68.99ZM42.9,68.99Q42.55,68.99 42.35,68.77Q42.15,68.55 42.15,68.19Q42.15,67.82 42.35,67.6Q42.55,67.38 42.9,67.38Q43.24,67.38 43.43,67.6Q43.63,67.82 43.63,68.19Q43.63,68.55 43.43,68.77Q43.23,68.99 42.9,68.99ZM40.52,66.45Q39.84,66.45 39.34,66.11Q38.84,65.77 38.56,65.2Q38.29,64.62 38.29,63.91Q38.29,63.37 38.38,62.83Q38.46,62.3 38.64,61.76L40.12,61.76Q39.98,62.22 39.93,62.64Q39.89,63.05 39.89,63.67Q39.89,64.04 40.07,64.38Q40.26,64.71 40.56,64.91Q40.87,65.11 41.21,65.11L43.14,65.11Q43.59,65.11 43.94,65.07Q44.29,65.04 44.49,64.91Q44.7,64.79 44.7,64.52Q44.7,64.33 44.51,64.19Q44.33,64.06 43.93,63.96Q43.31,63.8 42.94,63.57Q42.57,63.34 42.42,63.02Q42.27,62.7 42.27,62.25Q42.27,61.96 42.38,61.65Q42.48,61.34 42.69,61.03Q42.98,60.6 43.38,60.26Q43.79,59.91 44.23,59.71Q44.67,59.51 45.07,59.51Q45.24,59.51 45.41,59.54Q45.58,59.57 45.73,59.65Q46.1,59.83 46.44,60.2Q46.77,60.58 47.25,61.27Q47.5,61.64 47.71,61.87Q47.92,62.11 48.07,62.22Q48.23,62.33 48.4,62.39Q48.57,62.44 48.75,62.44L48.81,62.44Q48.86,62.44 48.86,62.49L48.86,63.72Q48.86,63.78 48.81,63.78L48.75,63.78Q48.35,63.78 48.03,63.7Q47.72,63.63 47.44,63.45Q47.16,63.28 46.86,62.96Q46.8,62.91 46.66,62.72Q46.51,62.52 46.29,62.21Q46.04,61.84 45.83,61.6Q45.62,61.35 45.47,61.23Q45.32,61.11 45.18,61.04Q45.03,60.98 44.9,60.98Q44.75,60.98 44.56,61.09Q44.37,61.19 44.2,61.36Q44.03,61.53 43.92,61.71Q43.81,61.88 43.81,62.02Q43.81,62.16 43.89,62.29Q43.97,62.42 44.15,62.53Q44.33,62.63 44.61,62.72Q44.97,62.83 45.23,62.95Q45.49,63.07 45.67,63.18Q46,63.39 46.17,63.7Q46.35,64.01 46.35,64.44Q46.35,65.41 45.55,65.93Q44.75,66.45 43.15,66.45L40.52,66.45ZM48.69,63.78Q48.64,63.78 48.64,63.72L48.64,62.49Q48.64,62.44 48.69,62.44L53.05,62.44L53.05,60.65Q53.05,59.98 52.59,59.62Q52.12,59.26 51.28,59.26L49.39,59.26L49.39,58.06Q49.57,57.66 49.8,57.32Q50.02,56.97 50.3,56.63Q50.92,55.9 51.74,55.33Q52.56,54.76 53.54,54.4L54.15,55.9Q53.67,56.04 53.21,56.26Q52.75,56.49 52.34,56.76Q51.93,57.03 51.62,57.33Q51.31,57.62 51.11,57.92L51.53,57.92Q53.13,57.92 53.92,58.55Q54.71,59.18 54.71,60.44L54.71,63.78L48.69,63.78ZM57.18,56.32Q56.83,56.32 56.63,56.1Q56.43,55.89 56.43,55.52Q56.43,55.14 56.63,54.93Q56.82,54.72 57.18,54.72Q57.52,54.72 57.72,54.93Q57.91,55.14 57.91,55.52Q57.91,55.89 57.71,56.1Q57.51,56.32 57.18,56.32ZM56.04,62.44L59.76,62.44Q59.65,61.93 59.51,61.51Q59.37,61.1 59.21,60.78Q58.87,60.15 58.36,59.69Q57.86,59.22 57.12,58.92L57.75,57.37Q58.73,57.75 59.45,58.43Q60.17,59.1 60.66,60.09Q61.15,61.08 61.43,62.44L62.89,62.44Q62.95,62.44 62.95,62.49L62.95,63.72Q62.95,63.78 62.89,63.78L56.04,63.78L56.04,62.44ZM62.78,63.78Q62.72,63.78 62.72,63.72L62.72,62.49Q62.72,62.44 62.78,62.44L64.17,62.44L64.17,55.32L65.83,55.32L65.83,63.78L62.78,63.78ZM68.06,55.32L69.71,55.32L69.71,63.78L68.06,63.78L68.06,55.32Z";

    /** 10.13: الأيقونة الجديدة: مربع بأطراف بنية، دائرة زيتية بحلقة ذهبية، و«المحاسب الذكي» بالكوفي بلون كريمي. */
    static BufferedImage icon(int size) {
        BufferedImage im = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float pad = size * 0.09f, w = size - 2 * pad;
        RoundRectangle2D bg = new RoundRectangle2D.Float(pad, pad, w, w, size * 0.42f, size * 0.42f);
        g.setPaint(new GradientPaint(pad, pad, new Color(139, 90, 43), size - pad, size - pad, new Color(90, 53, 23)));
        g.fill(bg);
        float cx = size / 2f, r = w * 0.43f;
        g.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Float(cx - r * 0.25f, cx - r * 0.35f), r * 1.6f, new float[]{0f, 1f},
                new Color[]{new Color(122, 139, 58), new Color(75, 90, 31)}));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cx - r, 2 * r, 2 * r));
        if (size >= 48) {
            float rr = r * 0.935f;
            g.setColor(new Color(201, 163, 90, 204));
            g.setStroke(new BasicStroke(Math.max(1f, size / 150f)));
            g.draw(new java.awt.geom.Ellipse2D.Float(cx - rr, cx - rr, 2 * rr, 2 * rr));
        }
        // النص في مربع 108 يُكبَّر ليملأ الدائرة
        // عرض النص في مربع 108 = 53؛ يُكبَّر ليشغل 78٪ من قطر الدائرة
        float tsc = 0.78f * 2f * r / 53f;
        AffineTransform at = new AffineTransform();
        at.translate(cx, cx);
        at.scale(tsc, tsc);
        at.translate(-54, -54);
        g.setColor(new Color(246, 234, 200));
        g.fill(at.createTransformedShape(path(KUFI_TEXT)));
        g.dispose();
        return im;
    }
}
