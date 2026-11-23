package com.smart.hawala;

import android.app.Dialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34}, application = TestApp.class, qualifiers = "ar-w400dp-h860dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ShotTest {
    static File dir = new File(System.getProperty("shots.dir", "/tmp/shots"));
    static List<String> errors = new ArrayList();

    static void idle() {
        for (int i = 0; i < 40; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    static void shot(MainActivity a, String name) {
        idle();
        try {
            int w = a.getWindow().getDecorView().getWidth();
            if (w <= 0) {
                w = 1200;
            }
            int ws = View.MeasureSpec.makeMeasureSpec(w, 1073741824);
            int us = View.MeasureSpec.makeMeasureSpec(0, 0);
            a.bar.measure(ws, View.MeasureSpec.makeMeasureSpec(a.bar.getHeight() > 0 ? a.bar.getHeight() : a.dp(58), 1073741824));
            a.body.measure(ws, us);
            int bh = a.body.getMeasuredHeight();
            int barH = a.bar.getMeasuredHeight();
            int fh = 0;
            int th = 0;
            if (a.footerBox.getVisibility() == 0) {
                a.footerBox.measure(ws, us);
                fh = a.footerBox.getMeasuredHeight();
            }
            if (a.tabs != null) {
                int tabH = a.tabs.getLayoutParams() != null && a.tabs.getLayoutParams().height > 0 ? a.tabs.getLayoutParams().height : a.dp(Ui.TAB_FRAME_DP);
                a.tabs.measure(ws, View.MeasureSpec.makeMeasureSpec(tabH, 1073741824));
                th = a.tabs.getMeasuredHeight();
            }
            Bitmap out = Bitmap.createBitmap(w, Math.min(barH + bh + fh + th, 12000), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(a.bg);
            a.bar.layout(0, 0, w, barH);
            a.bar.draw(c);
            c.save();
            c.translate(0.0f, barH);
            a.body.layout(0, 0, w, bh);
            a.body.draw(c);
            c.restore();
            if (fh > 0) {
                c.save();
                c.translate(0.0f, barH + bh);
                a.footerBox.layout(0, 0, w, fh);
                a.footerBox.draw(c);
                c.restore();
            }
            if (th > 0) {
                c.save();
                c.translate(0.0f, barH + bh + fh);
                a.tabs.layout(0, 0, w, th);
                a.tabs.draw(c);
                c.restore();
            }
            dir.mkdirs();
            FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"));
            try {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
                o.close();
                System.out.println("SHOT " + name + " " + w + "x" + out.getHeight());
            } catch (Throwable th2) {
                try {
                    o.close();
                } catch (Throwable th3) {
                    th2.addSuppressed(th3);
                }
                throw th2;
            }
        } catch (Throwable t) {
            errors.add(name + ": " + t);
            t.printStackTrace();
        }
    }

    static void shotDialog(MainActivity a, String name) {
        idle();
        try {
            Dialog d = ShadowDialog.getLatestDialog();
            View v = d.getWindow().getDecorView();
            v.measure(View.MeasureSpec.makeMeasureSpec(1000, 1073741824), View.MeasureSpec.makeMeasureSpec(0, 0));
            int h = Math.min(v.getMeasuredHeight(), 6000);
            v.layout(0, 0, 1000, h);
            Bitmap out = Bitmap.createBitmap(1000, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(-1);
            v.draw(c);
            dir.mkdirs();
            FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"));
            try {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
                o.close();
                System.out.println("SHOT " + name + " 1000x" + h);
                d.dismiss();
            } catch (Throwable th) {
                try {
                    o.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (Throwable t) {
            errors.add(name + ": " + t);
            t.printStackTrace();
        }
    }

    // 10.6: أدوات للقائمة نصف الدائرية والشاشتين الجديدتين
    static View find(View v, java.util.function.Predicate<View> p) {
        if (v == null) return null;
        if (p.test(v)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = find(g.getChildAt(i), p);
                if (r != null) return r;
            }
        }
        return null;
    }

    static View byDesc(View root, String prefix) {
        return find(root, v -> v.getContentDescription() != null && v.getContentDescription().toString().startsWith(prefix));
    }

    static android.widget.Button buttonText(View root, String text) {
        return (android.widget.Button) find(root, v -> v instanceof android.widget.Button && text.equals(((android.widget.Button) v).getText().toString()));
    }

    static EditText byHint(View root, String hint) {
        return (EditText) find(root, v -> v instanceof EditText && ((EditText) v).getHint() != null && ((EditText) v).getHint().toString().startsWith(hint));
    }

    static void later(long ms) {
        for (long t = 0; t < ms; t += 50) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
            }
        }
        idle();
    }

    /** لقطة للنافذة كما تظهر (مع الطبقات فوقها كقائمة «تسجيل»). */
    static void shotWindow(MainActivity a, String name) {
        idle();
        try {
            View f = a.frame;
            int w = f.getWidth() > 0 ? f.getWidth() : 1200, h = f.getHeight() > 0 ? f.getHeight() : 2580;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(a.bg);
            f.draw(c);
            dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            System.out.println("SHOT " + name + " " + w + "x" + h);
        } catch (Throwable t) {
            errors.add(name + ": " + t);
            t.printStackTrace();
        }
    }

    static void confirmLatest() {
        idle();
        android.app.AlertDialog d = (android.app.AlertDialog) ShadowDialog.getLatestDialog();
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        later(600);
    }

    static Db.TxInput tx(long office, boolean us, long amount, String cur, String person, String desc, long when) {
        Db.TxInput t = new Db.TxInput();
        t.office = office;
        t.type = us ? "مدين / لنا" : "دائن / علينا";
        t.amount = amount;
        t.currency = cur;
        t.person = person;
        t.description = desc;
        t.when = when;
        return t;
    }

    @Test
    public void shots() throws Exception {
        MainActivity a = (MainActivity) Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        Db db = a.db;
        long now = System.currentTimeMillis();
        List<String> ue = Arrays.asList("USD", "EUR");
        List<String> ut = Arrays.asList("USD", "TRY");
        List<String> us = Arrays.asList("USD", "SYP");
        long nour = db.addOffice("النور", "", "0933111222", "حلب", "", "", (Long) null, (Long) null, ue, new HashMap(), Arrays.asList(new Db.Opening("USD", "لنا", 250000L)));
        long amal = db.addOffice("الأمل", "", "", "إسطنبول", "", "", (Long) null, (Long) null, ut, new HashMap(), new ArrayList());
        long balsam = db.addOffice("البلسم", "", "", "دمشق", "", "", (Long) null, (Long) null, us, new HashMap(), new ArrayList());
        long rayyan = db.addOffice("الريان", "", "", "إدلب", "", "", (Long) null, (Long) null, ue, new HashMap(), new ArrayList());
        long salam = db.addOffice("السلام", "", "", "", "", "", (Long) null, (Long) null, ue, new HashMap(), new ArrayList());
        db.setPinned(amal, true);
        db.addTx(tx(nour, true, 1500000L, "USD", "محمد علي", "تسليم محمد علي 15000 دولار راجع 1%", now - (3 * 86400000)), (List) null);
        db.addTx(tx(nour, false, 420000L, "EUR", "سامر", "", now - (2 * 86400000)), (List) null);
        db.addTx(tx(amal, false, 2600000L, "TRY", "أحمد", "", (now - 86400000) - 3600000), (List) null);
        db.addTx(tx(amal, true, 98000L, "USD", "خالد", "", now - (2 * 86400000)), (List) null);
        db.addTx(tx(balsam, true, 50000000L, "SYP", "رنا", "", now - (5 * 86400000)), (List) null);
        db.addTx(tx(rayyan, false, 830000L, "USD", "يوسف", "", now - (4 * 86400000)), (List) null);
        db.addTx(tx(salam, true, 12000L, "EUR", "ليلى", "", now - (6 * 86400000)), (List) null);
        db.setManualRate("EUR", new BigDecimal("1.08"));
        db.setManualRate("TRY", new BigDecimal("0.029"));
        db.setManualRate("SYP", new BigDecimal("0.0000769"));
        db.setSetting("office_name", "مكتب الوفاء للحوالات");
        Map<String, Long> op = new HashMap<>();
        op.put("USD", 1250000L);
        op.put("EUR", 300000L);
        op.put("TRY", 5000000L);
        db.cashSetup("الصندوق الرئيسي", Arrays.asList("USD", "EUR", "TRY"), op, false, true);
        CashBox.Input o1 = new CashBox.Input();
        o1.kind = "OUT";
        o1.currency = "USD";
        o1.amount = 10000L;
        o1.officeId = Long.valueOf(balsam);
        o1.party = "سمير الحلبي";
        o1.fee = 100L;
        o1.feeSide = "US";
        o1.feeInCash = false;
        o1.when = now - 3600000;
        db.addCashMove(o1);
        CashBox.Input i1 = new CashBox.Input();
        i1.kind = "IN";
        i1.currency = "EUR";
        i1.amount = 50000L;
        i1.officeId = Long.valueOf(nour);
        i1.party = "فادي";
        i1.when = now - 7200000;
        db.addCashMove(i1);
        CashBox.Input e1 = new CashBox.Input();
        e1.kind = "EXPENSE";
        e1.currency = "USD";
        e1.amount = 40000L;
        e1.category = "إيجار المحل";
        e1.description = "إيجار شهر 10";
        e1.when = now - 86400000;
        db.addCashMove(e1);
        CashBox.Input e2 = new CashBox.Input();
        e2.kind = "EXPENSE";
        e2.currency = "USD";
        e2.amount = 25000L;
        e2.category = "رواتب وأجور العمال";
        e2.party = "عامل المحل";
        e2.when = now - (2 * 86400000);
        db.addCashMove(e2);
        CashBox.Input g1 = new CashBox.Input();
        g1.kind = "IN";
        g1.currency = "USD";
        g1.amount = 30000L;
        g1.category = "إيداع من صاحب المكتب";
        g1.fee = 0L;
        g1.when = now - (3 * 86400000);
        db.addCashMove(g1);
        CashBox.Input x1 = new CashBox.Input();
        x1.kind = "EXCHANGE";
        x1.currency = "USD";
        x1.toCurrency = "EUR";
        x1.amount = 100000L;
        x1.toAmount = 92000L;
        x1.rate = "0.92";
        x1.when = now - 18000000;
        db.addCashMove(x1);
        CashBox.Input b1 = new CashBox.Input();
        b1.kind = "EXCHANGE";
        b1.side = "BUY";
        b1.currency = "EUR";
        b1.toCurrency = "USD";
        b1.amount = 50000L;
        b1.toAmount = 54000L;
        b1.rate = "1.08";
        b1.party = "زبون";
        b1.when = now - 14400000;
        db.addCashMove(b1);
        Map<String, Long> op2 = new HashMap<>();
        op2.put("USD", 500000L);
        long box2 = db.addCashBox("خزنة البيت", Arrays.asList("USD", "EUR"), op2);
        CashBox.Input t1 = new CashBox.Input();
        t1.kind = "TRANSFER";
        t1.currency = "USD";
        t1.amount = 200000L;
        t1.toBox = Long.valueOf(box2);
        t1.description = "نقل لآخر النهار";
        t1.when = now - 10800000;
        db.addCashMove(t1);
        ClientBook.Client k1 = new ClientBook.Client();
        k1.name = "أبو خالد";
        k1.phone = "0944123456";
        long kid = db.saveClient(k1);
        ClientBook.Client k2 = new ClientBook.Client();
        k2.name = "سمير الأحمد";
        long kid2 = db.saveClient(k2);
        ClientBook.Client k3 = new ClientBook.Client();
        k3.name = "أم محمد";
        long kid3 = db.saveClient(k3);
        db.addClientEntry(kid, "USD", 5000L, 1, "رصيد سابق", now - (6 * 86400000));
        CashBox.Input c1 = new CashBox.Input();
        c1.kind = "IN";
        c1.currency = "EUR";
        c1.amount = 20000L;
        c1.clientId = Long.valueOf(kid);
        c1.clientCurrency = "EUR";
        c1.clientAmount = 20000L;
        c1.description = "أمانة";
        c1.when = now - (5 * 86400000);
        db.addCashMove(c1);
        CashBox.Input c2 = new CashBox.Input();
        c2.kind = "OUT";
        c2.currency = "USD";
        c2.amount = 10800L;
        c2.clientId = Long.valueOf(kid);
        c2.clientCurrency = "EUR";
        c2.clientAmount = 10000L;
        c2.description = "أخذ دولارًا مكان اليورو";
        c2.when = now - (2 * 86400000);
        db.addCashMove(c2);
        CashBox.Input c3 = new CashBox.Input();
        c3.kind = "IN";
        c3.currency = "USD";
        c3.amount = 150000L;
        c3.clientId = Long.valueOf(kid2);
        c3.clientCurrency = "USD";
        c3.clientAmount = 150000L;
        c3.when = now - 86400000;
        db.addCashMove(c3);
        db.addClientEntry(kid3, "TRY", 750000L, 1, "دين", now - (3 * 86400000));
        Map<String, Long> cb = db.cashBalances(1L);
        Map<String, Long> cnt0 = new LinkedHashMap<>();
        cnt0.put("TRY", Long.valueOf(cb.get("TRY").longValue() - 5000));
        db.cashCount(1L, cnt0, "جرد الأمس", (List) null);
        idle();
        a.showHome();
        shot(a, "01-home");
        a.officeHub.showList();
        shot(a, "02-offices-alpha");
        db.setSettingQuiet("office_sort", "1");
        a.officeHub.showList();
        shot(a, "03-offices-biggest");
        a.officeHub.showOffice(db.office(balsam));
        shot(a, "04-office-page");
        Field it = OfficeHub.class.getDeclaredField("innerTab");
        it.setAccessible(true);
        it.set(a.officeHub, 2);
        a.officeHub.showOffice(db.office(balsam));
        shot(a, "05-office-info");
        it.set(a.officeHub, 0);
        a.cashScreen.show();
        shot(a, "06-cash-home");
        db.setSettingQuiet("hide_amounts", "1");
        a.cashScreen.show();
        shot(a, "06b-cash-home-hidden");
        a.officeHub.showList();
        shot(a, "06c-offices-hidden");
        db.setSettingQuiet("hide_amounts", "0");
        a.cashScreen.form("OUT", db.office(balsam));
        shot(a, "07-cash-out-office");
        a.cashScreen.form("OUT", (Model.Office) null, db.client(kid));
        shot(a, "08-cash-out-client");
        a.cashScreen.trade("SELL");
        shot(a, "09-cash-sell");
        a.cashScreen.trade("BUY");
        shot(a, "09b-cash-buy");
        CashBox.Input ps = new CashBox.Input();
        ps.kind = "EXCHANGE";
        ps.side = "SELL";
        ps.currency = "USD";
        ps.toCurrency = "EUR";
        ps.amount = 100000L;
        ps.rate = "0.93";
        ps.toAmount = 93000L;
        ps.party = "زبون";
        ps.when = now;
        CashScreen cashScreen = a.cashScreen;
        CashScreen cashScreen2 = a.cashScreen;
        Objects.requireNonNull(cashScreen2);
        cashScreen.preview(ps, cashScreen2::home);
        shotDialog(a, "10-preview-sell");
        CashBox.Input pc = new CashBox.Input();
        pc.kind = "OUT";
        pc.currency = "USD";
        pc.amount = 10800L;
        pc.clientId = Long.valueOf(kid);
        pc.clientCurrency = "EUR";
        pc.clientAmount = 10000L;
        pc.when = now;
        CashScreen cashScreen3 = a.cashScreen;
        CashScreen cashScreen4 = a.cashScreen;
        Objects.requireNonNull(cashScreen4);
        cashScreen3.preview(pc, cashScreen4::home);
        shotDialog(a, "10b-preview-client");
        a.cashScreen.count();
        shot(a, "11-cash-count");
        Map<String, Long> cb2 = db.cashBalances(1L);
        Map<String, Long> cnt = new LinkedHashMap<>();
        cnt.put("USD", Long.valueOf(cb2.get("USD").longValue() - 10000));
        cnt.put("EUR", Long.valueOf(cb2.get("EUR").longValue() + 9200));
        cnt.put("TRY", cb2.get("TRY"));
        Method cr0 = CashScreen.class.getDeclaredMethod("countReview", CashBox.Config.class, Map.class, String.class);
        cr0.setAccessible(true);
        cr0.invoke(a.cashScreen, db.cashConfig(), cnt, "جرد المساء");
        shot(a, "11b-count-review");
        db.cashCount(1L, cnt, "جرد المساء", CashBox.analyze(Arrays.asList(new CashBox.CountLine("USD", cb2.get("USD").longValue(), cnt.get("USD").longValue()), new CashBox.CountLine("EUR", cb2.get("EUR").longValue(), cnt.get("EUR").longValue())), db.usdRates(), db.decimalsMap()));
        a.cashScreen.countLog();
        shot(a, "11c-count-log");
        a.cashScreen.setup(false);
        shot(a, "12-cash-settings");
        a.cashScreen.transfer();
        shot(a, "12b-transfer");
        a.cashScreen.clients.list();
        shot(a, "12c-clients");
        a.cashScreen.clients.page(kid);
        shot(a, "12d-client-page");
        a.cashScreen.clients.statement(db.client(kid), 0L, 0L);
        idle();
        shot(a, "12e-client-statement");
        CashScreen cashScreen5 = a.cashScreen;
        CashScreen cashScreen6 = a.cashScreen;
        Objects.requireNonNull(cashScreen6);
        cashScreen5.reportFor(0L, 0L, cashScreen6::show);
        idle();
        shot(a, "13-cash-report");
        String salt = Pin.newSalt();
        db.setSettingQuiet("cash_pin_salt", salt);
        db.setSettingQuiet("cash_pin_hash", Pin.hash("1234", salt));
        db.setSettingQuiet("cash_lock", "pin");
        a.showHome();
        shot(a, "13b-home-locked-cash");
        a.cashScreen.show();
        shot(a, "13c-cash-locked");
        db.setSettingQuiet("cash_lock", "0");
        db.addCashBox("صندوق الفرع", Arrays.asList("USD", "EUR"), new HashMap(), false, false);
        a.cashScreen.setup(false);
        shot(a, "13d-box-settings");
        Method bm = CashScreen.class.getDeclaredMethod("boxMenu", CashBox.Config.class);
        bm.setAccessible(true);
        bm.invoke(a.cashScreen, db.cashBoxes().get(db.cashBoxes().size() - 1));
        shotDialog(a, "13e-box-menu");
        a.cashScreen.boxForm((Runnable) null);
        shot(a, "13f-box-new");
        Entitlements before = License.current;
        License.current = Entitlements.free("");
        a.syncScreen.show();
        shot(a, "13g-sync-free");
        Map<String, String> gf = new HashMap<>();
        gf.put("ft", "all");
        gf.put("lid", "TEST-FULL");
        gf.put("uu", "29991231");
        License.current = Entitlements.of(new LicenseToken.Grant(gf), 20261021);
        a.syncScreen.show();
        shot(a, "13h-sync-intro");
        File sd = new File(System.getProperty("java.io.tmpdir"), "sa-sync-shot-" + System.nanoTime());
        sd.mkdirs();
        SyncEngine eng = new SyncEngine(db, new FileSyncFolder(sd));
        eng.create("Sync-Pass-2026".toCharArray(), "هاتف المكتب");
        eng.sync();
        a.syncScreen.show();
        shot(a, "13i-sync-status");
        eng.leave();
        License.current = before;
        a.reportsScreen.show();
        shot(a, "14-reports");
        // 10.16: الأرصدة بجدول مرتب، وقالب المطابقة بخياراته الجديدة
        a.balancesScreen.show();
        shot(a, "14b-balances");
        a.reconScreen.templateScreen(null);
        shot(a, "14c-recon-template");
        a.reportsScreen.show();
        ReportBuilder.Spec s = ((ReportBuilder.Preset) ReportBuilder.presets().get(0)).spec;
        ReportsScreen reportsScreen = a.reportsScreen;
        ReportsScreen reportsScreen2 = a.reportsScreen;
        Objects.requireNonNull(reportsScreen2);
        reportsScreen.run(s, reportsScreen2::show);
        idle();
        shot(a, "15-report-result");
        a.reportsScreen.builder((ReportBuilder.Spec) null);
        shot(a, "16-builder");
        a.moreScreen.show();
        shot(a, "17-more");
        MoreScreen moreScreen = a.moreScreen;
        Objects.requireNonNull(moreScreen);
        About.aboutUs(a, moreScreen::show);
        shot(a, "18-about");
        MoreScreen moreScreen2 = a.moreScreen;
        Objects.requireNonNull(moreScreen2);
        About.contact(a, moreScreen2::show);
        shot(a, "19-contact");
        MoreScreen moreScreen3 = a.moreScreen;
        Objects.requireNonNull(moreScreen3);
        About.appInfo(a, moreScreen3::show);
        shot(a, "20-appinfo");
        a.licenseScreen.show();
        shot(a, "21-license");
        a.notifScreen.show();
        shot(a, "22-notif");
        Reliability.screen(a);
        shot(a, "23-reliability");
        a.txScreen.showForOffice(db.office(nour));
        idle();
        shot(a, "24-tx-office");
        WaQuota q = new WaQuota();
        for (int i = 0; i < 20; i++) {
            q.use("k" + i, 20L, Db.today());
        }
        db.setSettingQuiet("wa_quota", q.serialize());
        db.setSettingQuiet("wa_quota_mac", License.mac(q.serialize()));
        a.txScreen.showNew();
        idle();
        Field ff = TxScreen.class.getDeclaredField("f");
        ff.setAccessible(true);
        Object form = ff.get(a.txScreen);
        Field fd = form.getClass().getDeclaredField("desc");
        fd.setAccessible(true);
        ((EditText) fd.get(form)).setText("تسليم\nمحمد علي حسن\n1500 دولار\n0933974123");
        Method pp = TxScreen.class.getDeclaredMethod("previewParse", new Class[0]);
        pp.setAccessible(true);
        pp.invoke(a.txScreen, new Object[0]);
        shot(a, "25-tx-wa-limit");
        a.designScreen.show();
        shot(a, "26-design");
        SQLiteDatabase w = db.getWritableDatabase();
        for (int k = 1; k <= 7; k++) {
            w.execSQL("INSERT INTO offices(name,active,pinned) VALUES(?,1,0)", new Object[]{Crypto.enc("مكتب إضافي " + k)});
        }
        Cursor cu = w.rawQuery("SELECT id FROM offices", null);
        while (cu.moveToNext()) {
            try {
                w.execSQL("INSERT OR IGNORE INTO office_currencies(office_id,code) VALUES(?,?)", new Object[]{Long.valueOf(cu.getLong(0)), "USD"});
            } catch (Throwable th) {
                if (cu != null) {
                    try {
                        cu.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        }
        if (cu != null) {
            cu.close();
        }
        BackupManager.Created cr = BackupManager.createLocal(a, db, "pass-12-office".toCharArray(), "9.0", m -> {
        });
        BackupManager.Prepared prep = BackupManager.prepareRestore(a, cr.file, "pass-12-office".toCharArray(), m2 -> {
        });
        Method pk = BackupScreen.class.getDeclaredMethod("pickOffices", File.class, Boolean.TYPE, BackupManager.Prepared.class, Long.TYPE, Runnable.class);
        pk.setAccessible(true);
        pk.invoke(a.backupScreen, cr.file, false, prep, 9L, null);
        shot(a, "27-restore-pick");
        License.current = Entitlements.of(new LicenseToken.Grant(gf), 20261021);
        File sd2 = new File(System.getProperty("java.io.tmpdir"), "sa-sync-shot2-" + System.nanoTime());
        sd2.mkdirs();
        SyncEngine eng2 = new SyncEngine(db, new FileSyncFolder(sd2));
        eng2.create("Sync-Pass-2026".toCharArray(), "هاتف المحل");
        long adm = db.addUser("أبو سامي", "ADMIN", (String) null, "123456");
        SmartApp.currentUser = db.user(adm);
        long acc = db.addUser("سامر", "CUSTOM", "VIEW,TX_ADD,TX_EDIT,CASH,EXPORT", "1234");
        db.addUser("رنا", "CUSTOM", "VIEW", "4321");
        eng2.sync();
        a.usersScreen.showManager();
        shot(a, "28-users");
        Method ud = UsersScreen.class.getDeclaredMethod("userDialog", Model.User.class, Boolean.TYPE);
        ud.setAccessible(true);
        ud.invoke(a.usersScreen, db.user(acc), false);
        shotDialog(a, "29-user-perms");
        a.syncScreen.show();
        shot(a, "30-sync-status");
        // 10.14: حساب Google ظاهر وتغييره، والحساب القديم، وتنبيه «نُقلت» على جهاز آخر، وأزرار الأمان الجديدة
        db.setSettingQuiet("sync_transport", "drive");
        db.setSettingQuiet("drive_account", "office.sync@gmail.com");
        db.setSettingQuiet("drive_account_old", "old.office@gmail.com");
        SyncStore.setMeta(db.getWritableDatabase(), "moved_to", "نُقلت المزامنة إلى «Google Drive: office.sync@gmail.com» (بواسطة «أبو سامي»).");
        SyncStore.setMeta(db.getWritableDatabase(), "moved_target", "Google Drive: office.sync@gmail.com");
        a.syncScreen.show();
        shot(a, "30b-sync-account");
        SyncStore.setMeta(db.getWritableDatabase(), "moved_to", null);
        SyncStore.setMeta(db.getWritableDatabase(), "moved_target", null);
        db.setSettingQuiet("sync_transport", "folder");
        BackupRecovery.create(db);
        a.backupScreen.show();
        shot(a, "30c-backup-recovery");
        BackupRecovery.disable(db);
        SmartApp.currentUser = null;
        a.usersScreen.showLogin();
        shot(a, "31-login-synced");
        SmartApp.currentUser = db.user(adm);
        Db.currentUserName = "أبو سامي";
        a.moreScreen.show();
        shot(a, "32-more");
        a.tabsScreen.show();
        shot(a, "33-tabs");
        long d1 = db.addTx(tx(nour, true, 45600L, "USD", "أبو عمر", "", now - 3600000), (List) null);
        long d2 = db.addTx(tx(nour, true, 45600L, "USD", "أبو عمر", "", now - 1800000), (List) null);
        db.getWritableDatabase().execSQL("INSERT INTO sync_conflicts(created_at,kind,tbl,rid,detail,dev) VALUES(?,?,?,?,?,?)", new Object[]{Long.valueOf(now), "DUPLICATE", "tx", Math.min(d1, d2) + "|" + Math.max(d1, d2), "", "x"});
        a.syncScreen.showDuplicates();
        shot(a, "34-duplicates");
        db.setSetting("approval_on", "1");
        long emp = db.addUser("موظف الصندوق", "CUSTOM", "VIEW,TX_ADD,REVIEW", "2468");
        SmartApp.currentUser = db.user(emp);
        Db.currentUserName = "موظف الصندوق";
        long pt = db.addTx(tx(amal, false, 99900L, "USD", "سامر خطأ", "", now - 600000), (List) null);
        SmartApp.currentUser = db.user(adm);
        Db.currentUserName = "أبو سامي";
        a.logScreen.showPending();
        shot(a, "35-pending");
        a.txScreen.showApproveEdit(db.tx(pt), (Runnable) null);
        idle();
        shot(a, "36-approve-edit");
        a.showHome();
        shot(a, "37-home-tabs");
        // 10.15: الشريط الزجاجي بتجويف (الافتراضي) وقائمة «+» وإعداداته، والجزيرة باقية خيارًا
        java.util.List<String> tk = a.tabKeys();
        a.plusPressed((NotchBar) a.tabs, tk.indexOf("tx"), tk.indexOf("home"));
        idle();
        shotWindow(a, "37b-plus-menu");
        if (!a.closeFan()) errors.add("plus: زر «+» لم يفتح القائمة السريعة");
        idle();
        a.tabsScreen.show();
        shot(a, "37c-bar-settings");
        NavPrefs np = new NavPrefs();
        np.barFloat = true;
        np.barColor = NavPrefs.BAR_COLORS[1];
        np.circleColor = NavPrefs.CIRCLE_COLORS[1];
        np.labels = false;
        np.fanStyle = 2;
        a.saveNav(np);
        a.officeHub.showList();
        shot(a, "37d-bar-float");
        db.setSettingQuiet("tab_style", "island");
        a.showHome();
        shot(a, "37e-bar-island");
        db.deleteSetting("tab_style");
        db.deleteSetting("nav_prefs");
        new SyncEngine(db, new FileSyncFolder(sd2)).leave();
        db.setSettingQuiet("sync_transport", "drive");
        a.syncScreen.show();
        shot(a, "38-sync-methods");
        a.appLock.settings();
        shot(a, "39-applock");
        Method ud2 = UsersScreen.class.getDeclaredMethod("userDialog", Model.User.class, Boolean.TYPE);
        ud2.setAccessible(true);
        ud2.invoke(a.usersScreen, db.user(emp), false);
        shotDialog(a, "40-user-perms");
        a.syncScreen.help("folder", (Runnable) null);
        shot(a, "41-sync-help");
        long cashier = db.addUser("أمين الصندوق", "CUSTOM", "TX_ADD,REVIEW", "1122");
        SmartApp.currentUser = db.user(cashier);
        Db.currentUserName = "أمين الصندوق";
        db.addTx(tx(amal, true, 12500L, "USD", "زبون", "", now - 300000), (List) null);
        a.logScreen.show(false);
        shot(a, "42-mine");
        a.moreScreen.show();
        shot(a, "43-more-limited");
        SmartApp.currentUser = db.user(adm);
        Db.currentUserName = "أبو سامي";
        // 10.5: الشاشات الجديدة
        db.setWaMark(balsam, "سلم 500 دولار لأحمد الحلبي — مكتب البلسم", "10:31");
        a.officeHub.showOffice(db.office(balsam));
        it.set(a.officeHub, 2);
        a.officeHub.showOffice(db.office(balsam));
        shot(a, "50-office-marker");
        it.set(a.officeHub, 0);
        a.fxScreen.show(db.office(balsam));
        shot(a, "51-fx");
        a.fxScreen.transfer(db.office(balsam));
        shot(a, "52-transfer");
        final Db.TxInput ci = tx(balsam, true, 250000L, "USD", "أحمد", "", now - 1000);
        a.confirmTx("تأكيد تسجيل الحركة", "", () -> TxConfirm.build(db, ci, null), "تسجيل", () -> {});
        shotDialog(a, "53-confirm");
        Db.TransferInput tri = new Db.TransferInput();
        tri.from = balsam;
        tri.to = amal;
        tri.currency = "USD";
        tri.amount = 100000L;
        tri.when = now - 1000;
        final java.util.List<Db.TxInput> tl = java.util.Arrays.asList(db.transferLegs(tri));
        a.confirmTx("تأكيد التحويل بين مكتبين", "", () -> TxConfirm.build(db, tl, null, null), "تسجيل التحويل", () -> {});
        shotDialog(a, "54-confirm-transfer");
        long ver = db.addUser("محقق", "CUSTOM", "VIEW,VERIFY", "3344");
        db.setUserOffices(ver, new java.util.HashSet<>(java.util.Arrays.asList(balsam, amal)));
        SmartApp.currentUser = db.user(ver);
        Db.currentUserName = "محقق";
        a.mineScreen.show();
        shot(a, "55-mine-verify");
        SmartApp.currentUser = db.user(adm);
        Db.currentUserName = "أبو سامي";
        WaPin.clear(db);
        a.txScreen.showShared(new WaText.Msg("أبو أحمد", "10:30", "سلم 500 دولار لأحمد"));
        shot(a, "56-wa-unpinned");
        WaPin.pin(db, balsam);
        a.txScreen.showShared(new WaText.Msg("أبو أحمد", "10:32", "سلم 700 دولار لسامي"));
        shot(a, "57-wa-pinned");
        // 10.7: زر «تسجيل» يفتح «تسجيل حركة» مباشرة، ونوع الحركة فوق المكتب (عادية، قص عملات، بين مكتبين)
        a.officeHub.showList();
        idle();
        // 10.15: في الشريط الزجاجي «+» يفتح القائمة السريعة، و«حركة عادية» منها تفتح «تسجيل حركة»
        java.util.List<String> tk2 = a.tabKeys();
        a.plusPressed((NotchBar) a.tabs, tk2.indexOf("tx"), tk2.indexOf("offices"));
        idle();
        if (!a.closeFan()) errors.add("tab: زر «+» لم يفتح القائمة السريعة");
        idle();
        a.fanAction("TX");
        idle();
        if (!"تسجيل حركة".equals(a.screenTitle.getText().toString()) || byDesc(a.body, "نوع الحركة") == null) errors.add("tx: لا أزرار نوع الحركة " + a.screenTitle.getText());
        shot(a, "58-tx-kinds");
        buttonText(byDesc(a.body, "نوع الحركة"), "قص عملات").performClick();
        idle();
        if (byDesc(a.body, "المبلغ المباع") == null || byDesc(a.body, "نوع الحركة") == null) errors.add("kinds→fx: لم تُفتح صفحة القص");
        shot(a, "59-fx-from-kinds");
        buttonText(byDesc(a.body, "نوع الحركة"), "بين مكتبين").performClick();
        idle();
        if (buttonText(a.body, "اختر المكتب المنفّذ") == null) errors.add("kinds→xfer: لم تُفتح صفحة الحركة بين مكتبين");
        shot(a, "61-transfer-from-kinds");
        buttonText(byDesc(a.body, "نوع الحركة"), "حركة عادية").performClick();
        idle();
        if (byDesc(a.body, "نوع الحركة") == null || buttonText(a.body, "اختر المكتب المنفّذ") != null) errors.add("kinds→tx: لم تعد صفحة الحركة العادية");
        // قص عملات: اختيار العملتين بأزرار، والمبلغ المقابل محسوب، والملخص، ثم الحفظ عبر نافذة التأكيد
        int nBefore = db.recentTx(1000).size();
        a.fxScreen.show(db.office(nour));
        idle();
        buttonText(a.body, "USD").performClick();
        ((EditText) byDesc(a.body, "المبلغ المباع")).setText("1000");
        ((EditText) byDesc(a.body, "السعر")).setText("0.92");
        idle();
        String counter = ((EditText) byDesc(a.body, "المبلغ المقابل")).getText().toString();
        if (!"920".equals(counter)) errors.add("fx: المقابل " + counter);
        shot(a, "60-fx-filled");
        buttonText(a.footerBox, "تسجيل القص").performClick();
        confirmLatest();
        if (db.recentTx(1000).size() != nBefore + 2) errors.add("fx: لم تُسجَّل الساقان " + (db.recentTx(1000).size() - nBefore));
        // حركة بين مكتبين: طالب الحركة من صفحته، والمنفّذ من قائمة المكاتب
        nBefore = db.recentTx(1000).size();
        long balsamUsd = db.balances(balsam).getOrDefault("USD", 0L), amalUsd = db.balances(amal).getOrDefault("USD", 0L);
        a.fxScreen.transfer(db.office(balsam));
        idle();
        buttonText(a.body, "اختر المكتب المنفّذ").performClick();
        idle();
        android.widget.ListView lv = (android.widget.ListView) find(ShadowDialog.getLatestDialog().getWindow().getDecorView(), v -> v instanceof android.widget.ListView);
        int at = -1;
        for (int i = 0; i < lv.getAdapter().getCount(); i++) if (String.valueOf(lv.getAdapter().getItem(i)).contains("الأمل")) at = i;
        lv.performItemClick(null, at, at);
        idle();
        byHint(a.body, "0").setText("1000");
        idle();
        shot(a, "62-transfer-filled");
        buttonText(a.footerBox, "تسجيل الحركة").performClick();
        confirmLatest();
        if (db.recentTx(1000).size() != nBefore + 2) errors.add("xfer: لم تُسجَّل الساقان " + (db.recentTx(1000).size() - nBefore));
        // 10.7: طالب الحركة «لنا» عليه (يزيد ما لنا) والمنفّذ «علينا» له
        if (db.balances(balsam).getOrDefault("USD", 0L) != balsamUsd + 100000L || db.balances(amal).getOrDefault("USD", 0L) != amalUsd - 100000L)
            errors.add("xfer: الاتجاه خاطئ " + db.balances(balsam) + " " + db.balances(amal));
        // 10.8: قص العملات مقاصة: بيع أكثر من الرصيد يقلبه إلى «علينا» بعد تنبيه وتأكيد
        long amalTry = db.balances(amal).getOrDefault("TRY", 0L), amalUsd2 = db.balances(amal).getOrDefault("USD", 0L);
        a.fxScreen.show(db.office(amal));
        idle();
        buttonText(a.body, "TRY").performClick();
        ((EditText) byDesc(a.body, "المبلغ المباع")).setText("30000");
        ((EditText) byDesc(a.body, "السعر")).setText("0.03");
        idle();
        if (find(a.body, v -> v instanceof android.widget.TextView && ((android.widget.TextView) v).getText().toString().startsWith("⚠ تنبيه")) == null) errors.add("fx: لا تنبيه تجاوز الرصيد");
        shot(a, "63-fx-overdraft");
        buttonText(a.footerBox, "تسجيل القص").performClick();
        idle();
        ((android.app.AlertDialog) ShadowDialog.getLatestDialog()).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        confirmLatest();
        if (db.balances(amal).getOrDefault("TRY", 0L) != amalTry - 3000000L || db.balances(amal).getOrDefault("USD", 0L) != amalUsd2 + 90000L)
            errors.add("fx مقاصة: " + db.balances(amal));
        // 10.8: فرز المكاتب بين المحاسبين
        a.teamScreen.show();
        idle();
        if (!"فرز المكاتب بين المحاسبين".equals(a.screenTitle.getText().toString())) errors.add("team: " + a.screenTitle.getText());
        shot(a, "64-team");
        // 10.9: الوضع الليلي العصري لأهم الشاشات
        db.setSettingQuiet("theme_mode", "dark");
        a.night = true;
        a.appliedTheme = a.themeSig();
        a.applyColors();
        a.showHome();
        shot(a, "70-dark-home");
        a.txScreen.showNew();
        shot(a, "71-dark-tx");
        a.fxScreen.show(db.office(nour));
        shot(a, "72-dark-fx");
        a.officeHub.showList();
        shot(a, "73-dark-offices");
        db.setSettingQuiet("theme_mode", "light");
        a.night = false;
        a.appliedTheme = a.themeSig();
        a.applyColors();
        a.showHome();
        shot(a, "74-light-home");
        a.txScreen.showNew();
        shot(a, "75-light-tx");
        // 10.12: أكورديون الشريط العلوي، والاهتزاز في المظهر، و«عن البرنامج»، والتقارير الجديدة
        db.setSettingQuiet("theme_mode", "light");
        a.night = false;
        a.appliedTheme = a.themeSig();
        a.applyColors();
        a.showHome();
        idle();
        View fold = byDesc(a.bar, "أدوات الشاشة");
        if (fold == null) errors.add("header: لا أيقونة طي");
        else {
            shot(a, "76-header-closed");
            fold.performClick();
            idle();
            shot(a, "77-header-open");
        }
        a.designScreen.show();
        if (buttonText(a.body, "إيقاف") == null) errors.add("design: لا خيار للاهتزاز");
        shot(a, "78-design-haptics");
        About.appInfo(a, a::showHome);
        shot(a, "79-appinfo");
        a.reportsScreen.show();
        shot(a, "80-reports");
        a.reportsScreen.teamReport(0, 0);
        idle();
        if (!"التسجيل والتحقق بين المحاسبين".equals(a.screenTitle.getText().toString())) errors.add("team report: " + a.screenTitle.getText());
        shot(a, "81-team-report");
        // 10.13: «مكاتبي» بصفحاتها وحالاتها، والشريط «دائرة تتمدد»، وخيارات المظهر
        a.mineScreen.show();
        idle();
        if (!"مكاتبي".equals(a.screenTitle.getText().toString())) errors.add("mine: " + a.screenTitle.getText());
        shot(a, "84-mine");
        if (!a.mineScreen.pages().isEmpty()) {
            for (String[] pg : a.mineScreen.pages()) {
                db.setSettingQuiet("mine_page", pg[0]);
                a.mineScreen.show();
                idle();
                shot(a, "84-mine-" + pg[0]);
            }
        }
        db.setSettingQuiet("tab_style", "bubble");
        Ui.bubbleOpen = false;
        a.showHome();
        idle();
        shot(a, "85-bubble-closed");
        View bub = byDesc(a.tabs, "القائمة");
        if (bub == null) errors.add("bubble: لا دائرة");
        else {
            bub.performClick();
            idle();
            shot(a, "86-bubble-open");
        }
        db.setSettingQuiet("tab_style", "island");
        Ui.bubbleOpen = false;
        db.setSettingQuiet("btn_frames", "off");
        a.applyColors();
        a.designScreen.show();
        shot(a, "87-design-noframes");
        db.setSettingQuiet("btn_frames", "on");
        a.applyColors();
        // 10.13: التابلت بالعرض — النصفان متقابلان
        org.robolectric.RuntimeEnvironment.setQualifiers("+w1000dp-h640dp-land");
        idle();
        if (a.wide()) {
            a.txScreen.showNew();
            shot(a, "88-tablet-tx");
            a.fxScreen.show(db.office(nour));
            shot(a, "89-tablet-fx");
            a.showHome();
            shot(a, "90-tablet-home");
        } else System.out.println("TABLET qualifiers not applied to activity: " + a.getResources().getConfiguration().screenWidthDp);
        System.out.println("ERRORS " + errors);
        if (!errors.isEmpty()) {
            throw new AssertionError(errors.toString());
        }
    }
}
