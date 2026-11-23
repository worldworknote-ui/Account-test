package com.smart.hawala;

import android.app.Activity;
import android.app.WindowStack;
import android.content.Context;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * 11.0: يشغّل شاشات الهاتف نفسها (MainActivity) على الكمبيوتر بلا شاشة، ويفتح كل شاشة ويحفظ صورتها، ويفشل إن رمت شاشة استثناءً.
 * وسائط: مجلد الصور، ملف .sba للتجربة، كلمة مروره، [العرض] [night].
 */
public final class PhoneShot {
    static int fails;
    static final List<String> errors = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        out.mkdirs();
        final int W = a.length > 3 ? Integer.parseInt(a[3]) : 480, H = 960;
        final boolean night = a.length > 4 && a[4].equals("night");
        File home = java.nio.file.Files.createTempDirectory("sa-phone").toFile();
        System.setProperty("sa.home", home.getAbsolutePath());
        System.setProperty("sa.secrets", "file");
        System.setProperty("sa.noanim", "true");
        android.animation.Choreo.instant = true;
        SecretStore.fallback(new File(home, "keys"));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            fails++;
            StringWriter sw = new StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            errors.add(sw.toString());
            e.printStackTrace();
        });
        Context ctx = new Context(home);
        Crypto.init(ctx);
        Db db = new Db(ctx);
        SmartApp.db = db;
        SmartApp.ctx = ctx;
        License.load(ctx, db);
        if (a.length > 2 && !a[1].isEmpty()) {
            BackupManager.Prepared p = BackupManager.prepareRestore(ctx, new File(a[1]), a[2].toCharArray(), m -> {});
            BackupManager.applyRestore(ctx, db, p, m -> {});
        }
        if (night) db.setSetting("theme_mode", "dark");
        // 11.3: تجربة الثيمات الجاهزة (sa.preset=رقم الثيم) على شاشات مختارة (sa.only=اسم,اسم)
        String pre = System.getProperty("sa.preset", "");
        if (!pre.isEmpty()) {
            String[] pz = DesignScreen.PRESETS[Integer.parseInt(pre)];
            db.setSetting("primary_color", pz[1]);
            db.setSetting("accent_color", pz[2]);
            db.setSetting("header_color", pz[3]);
            db.setSetting("bg_color", pz[4]);
            db.setSetting("night_bg", pz[5]);
        }
        db.setSetting("office_name", "مكتب التجربة للصرافة");
        final MainActivity[] act = {null};
        SwingUtilities.invokeAndWait(() -> {
            JLayeredPane h = WindowStack.host();
            h.setSize(W, H);
            act[0] = Activity.launch(MainActivity.class, null);
        });
        settle();
        final MainActivity m = act[0];
        Map<String, Runnable> screens = new LinkedHashMap<>();
        screens.put("01-home", m::showHome);
        screens.put("02-offices", m.officeHub::showList);
        screens.put("03-office", () -> m.officeHub.showOffice(db.offices().get(0)));
        screens.put("04-tx-new", m.txScreen::showNew);
        screens.put("05-fx", () -> m.fxScreen.show(null));
        screens.put("06-between", () -> m.fxScreen.transfer(null, null));
        screens.put("07-log", () -> m.logScreen.show(false));
        screens.put("08-log-search", () -> m.logScreen.show(true));
        screens.put("09-statement", () -> m.statementScreen.show(db.offices().get(0)));
        screens.put("10-balances", m.balancesScreen::show);
        screens.put("11-daily", () -> m.dailyScreen.show(Clock.now()));
        screens.put("12-cash", m.cashScreen::show);
        screens.put("13-reports", m.reportsScreen::show);
        screens.put("14-more", m.moreScreen::show);
        screens.put("15-design", m.designScreen::show);
        screens.put("16-sync", m.syncScreen::show);
        screens.put("17-backup", m.backupScreen::show);
        screens.put("18-users", m.usersScreen::showManager);
        screens.put("19-mine", m.mineScreen::show);
        screens.put("20-team", m.teamScreen::show);
        screens.put("21-clients", m.cashScreen.clients::list);
        screens.put("22-notif", m.notifScreen::show);
        screens.put("23-license", m.licenseScreen::show);
        screens.put("24-security", m.settingsScreen::showSecurity);
        screens.put("25-audit", m.settingsScreen::showAudit);
        screens.put("26-health", m.settingsScreen::showHealth);
        screens.put("27-tabs", m.tabsScreen::show);
        screens.put("28-owner", m.ownerScreen::show);
        screens.put("29-currencies", m.officesScreen::showCurrencies);
        screens.put("30-pending", m.logScreen::showPending);
        screens.put("31-recon", () -> m.reconScreen.showOffice(db.offices().get(0)));
        screens.put("32-picker", () -> m.officePicker(o -> {}));
        String only = System.getProperty("only");
        String onlyShots = System.getProperty("sa.only", "");
        if (!onlyShots.isEmpty()) screens.keySet().retainAll(java.util.Arrays.asList(onlyShots.split(",")));
        for (Map.Entry<String, Runnable> e : screens.entrySet()) {
            if (only != null && !e.getKey().contains(only)) continue;
            int before = fails;
            SwingUtilities.invokeAndWait(() -> {
                try {
                    for (android.view.ViewRootImpl r : WindowStack.roots()) if (r.layer() >= android.view.ViewRootImpl.LAYER_DIALOG) WindowStack.remove(r);
                    e.getValue().run();
                } catch (Throwable t) {
                    fails++;
                    t.printStackTrace();
                    errors.add(e.getKey() + ": " + t);
                }
            });
            settle();
            shot(new File(out, e.getKey() + ".png"), W, H);
            System.out.println((fails > before ? "FAIL " : "ok   ") + e.getKey());
        }
        // 11.3: الأمانات والمجمّد في المطابقة (نص وصورة) وفي الكشف
        SwingUtilities.invokeAndWait(() -> {
            try {
                Model.Office o = null;
                for (Model.Office x : db.offices()) if (o == null && !db.balances(x.id).isEmpty()) o = x;
                String cur = db.balances(o.id).keySet().iterator().next();
                db.addHold(o.id, cur, Db.HOLD_TRUST, Db.SIDE_OURS, 50000, "تجربة أمانة");
                java.util.List<String> lines = m.reconScreen.holdLines(o, null);
                String txt = Reconcile.text(ReconScreen.withHolds(m.reconScreen.template(), lines), o.name, "مكتب", Clock.now(),
                        Reconcile.rows(db.balances(o.id), db.currencyNames(), db.decimalsMap(), null, false));
                boolean inText = txt.contains("الأمانات والمبالغ المجمّدة") && txt.contains("أمانة لنا عند المكتب");
                Report r = new Report();
                m.exportUi.addHolds(r, o, null);
                boolean inStatement = !r.sections.isEmpty() && r.sections.get(0).rows.size() == 1;
                String plain = Reconcile.text(ReconScreen.withHolds(m.reconScreen.template(), null), o.name, "مكتب", Clock.now(),
                        Reconcile.rows(db.balances(o.id), db.currencyNames(), db.decimalsMap(), null, false));
                boolean offByDefault = !plain.contains("الأمانات");
                System.out.println((inText ? "ok   " : "FAIL ") + "holds in reconciliation text");
                System.out.println((inStatement ? "ok   " : "FAIL ") + "holds section in statement");
                System.out.println((offByDefault ? "ok   " : "FAIL ") + "holds hidden when not chosen");
                if (!inText || !inStatement || !offByDefault) fails++;
            } catch (Exception ex) {
                fails++;
                ex.printStackTrace();
            }
        });
        System.out.println(fails == 0 ? "ALL SCREENS OK" : "FAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            SwingUtilities.invokeAndWait(() -> { for (android.view.ViewRootImpl r : WindowStack.roots()) r.traversal(); });
            Thread.sleep(40);
        }
    }

    static void shot(File f, int w, int h) throws Exception {
        final BufferedImage[] im = {null};
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage b = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.scale(2, 2);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            WindowStack.host().printAll(g);
            g.dispose();
            im[0] = b;
        });
        ImageIO.write(im[0], "png", f);
    }

    static final class StringWriter extends java.io.StringWriter {}
}
