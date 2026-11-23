import android.content.Context;
import com.smart.hawala.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * نواة الماك: Db نفسه (من ملفات الهاتف) فوق sqlite-jdbc.
 * 1) نسخ أداة النقل الاصطناعية A–D تُستعاد وأرصدتها كما في expect.json، وهوية الأرصدة سليمة.
 * 2) عمليات كاملة على الماك (مكاتب، حركات بأجور ونسب، تعديل، إلغاء، تبديل عملة، دمج، رصيد سابق) ثم فحص الصحة.
 * 3) نسخة من الماك ← استعادة في جهاز آخر (مجلد آخر بمفتاح آخر) ← الجداول والأرصدة متطابقة سطرًا بسطر.
 * 4) (اختياري) ملف حقيقي \u200e.sba: الأرصدة الـ94 = المتوقعة، وهوية الأرصدة على 25,163 حركة.
 * وسائط: tests/data [ملف_حقيقي.sba كلمة_المرور ملف_المتوقع.json]
 */
public class DeskCoreTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static File tmp(String n) throws Exception {
        File d = Files.createTempDirectory("sa-desk-" + n).toFile();
        d.deleteOnExit();
        return d;
    }

    /** جهاز جديد: مجلد بيانات + مفاتيح خاصة به. */
    static Object[] device(String n) throws Exception {
        File home = tmp(n);
        SecretStore.fallback(new File(home, "keys"));
        Context ctx = new Context(home);
        resetCrypto();
        Crypto.init(ctx);
        Db db = new Db(ctx);
        SmartApp.db = db;
        SmartApp.ctx = ctx;
        SmartApp.currentUser = null;
        License.load(ctx, db);
        return new Object[]{ctx, db};
    }

    /** Crypto يحفظ المفتاح في حقل ثابت؛ لمحاكاة جهاز آخر نصفّره. */
    static void resetCrypto() throws Exception {
        java.lang.reflect.Field f = Crypto.class.getDeclaredField("dek");
        f.setAccessible(true);
        f.set(null, null);
        Crypto.clearCache();
    }

    static Map<String, Long> namedBalances(Db db) {
        Map<String, Long> m = new TreeMap<>();
        Map<Long, String> names = db.officeNames();
        for (Map.Entry<Long, Map<String, Long>> e : db.officeBalances().entrySet())
            for (Map.Entry<String, Long> b : e.getValue().entrySet()) if (b.getValue() != 0) m.merge(names.get(e.getKey()) + "|" + b.getKey(), b.getValue(), Long::sum);
        return m;
    }

    static boolean healthy(Db db, String label) {
        boolean all = true;
        for (Db.Check c : db.healthCheck(true)) {
            if (!c.ok) {
                all = false;
                System.out.println("      ✗ " + c.title + ": " + c.detail);
            }
        }
        check(all, label + ": فحص الصحة الكامل (سلامة الملف، الروابط، توازن القيود، رصيد كل مكتب = حركاته، تسلسل السجل)");
        return all;
    }

    static void restore(Context ctx, Db db, File f, String pw) throws Exception {
        BackupManager.Prepared p = BackupManager.prepareRestore(ctx, f, pw.toCharArray(), m -> {});
        BackupManager.applyRestore(ctx, db, p, m -> {});
    }

    /** رخصة كاملة للاختبار (Grant بلا توقيع: لا تمر بالتحقق، تُبنى مباشرة كما يبنيها التحقق بعد نجاحه). */
    static LicenseToken.Grant fullGrant() throws Exception {
        Map<String, String> f = new HashMap<>();
        f.put("ft", "all");
        f.put("lid", "TEST-FULL");
        f.put("uu", "29991231");
        java.lang.reflect.Constructor<LicenseToken.Grant> c = LicenseToken.Grant.class.getDeclaredConstructor(Map.class);
        c.setAccessible(true);
        return c.newInstance(f);
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        File data = new File(a.length > 0 ? a[0] : "tests/data");
        JSONObject expect = new JSONObject(new String(Files.readAllBytes(new File(data, "expect.json").toPath()), StandardCharsets.UTF_8));

        System.out.println("== 1) نسخ أداة النقل تُستعاد على الماك");
        for (String k : new String[]{"A", "B", "C", "D"}) {
            Object[] dv = device("m" + k);
            Context ctx = (Context) dv[0];
            Db db = (Db) dv[1];
            JSONObject e = expect.getJSONObject(k);
            restore(ctx, db, new File(data, k + ".sba"), e.getString("password"));
            Map<String, Long> want = new TreeMap<>();
            JSONArray bs = e.getJSONArray("balances");
            for (int i = 0; i < bs.length(); i++) {
                JSONArray b = bs.getJSONArray(i);
                if (b.getLong(2) != 0) want.merge(b.getString(0) + "|" + b.getString(1), b.getLong(2), Long::sum);
            }
            Map<String, Long> got = namedBalances(db);
            check(got.equals(want), k + ": " + db.offices().size() + " مكاتب و" + db.txCount() + " حركة، الأرصدة كما في الملف " + (got.equals(want) ? "" : got + " ≠ " + want));
            healthy(db, k);
            db.close();
        }

        System.out.println("== 2) عمليات كاملة على الماك");
        Object[] dv = device("ops");
        Context ctx = (Context) dv[0];
        Db db = (Db) dv[1];
        List<String> usdEur = Arrays.asList("USD", "EUR");
        long nour = db.addOffice("النور", "", "0933", "حلب", "", "", null, null, usdEur, new HashMap<>(), Arrays.asList(new Db.Opening("USD", "لنا", 100000)));
        long amal = db.addOffice("الأمل", "", "", "", "", "", null, null, usdEur, new HashMap<>(), new ArrayList<>());
        long amal2 = db.addOffice("مكتب الأمل", "", "", "", "", "", null, null, usdEur, new HashMap<>(), Arrays.asList(new Db.Opening("EUR", "علينا", 50000)));
        long now = System.currentTimeMillis();
        Db.TxInput t1 = new Db.TxInput();
        t1.office = nour;
        t1.type = Model.TYPE_DEBIT;
        t1.amount = 1000000;
        t1.currency = "USD";
        t1.feeType = Model.FEE_US;
        t1.fee = Model.feeFromRate(t1.amount, new java.math.BigDecimal("1"), Model.PER_CENT);   // «راجع 1%»
        t1.feeCurrency = "USD";
        t1.person = "سامر خليل";
        t1.description = "حوالة";
        t1.when = now - 3600000;
        long id1 = db.addTx(t1, null);
        Db.TxInput t2 = new Db.TxInput();
        t2.office = nour;
        t2.type = Model.TYPE_CREDIT;
        t2.amount = 250000;
        t2.currency = "EUR";
        t2.feeType = Model.FEE_THEM;
        t2.fee = 1500;
        t2.feeCurrency = "EUR";
        t2.when = now - 1800000;
        long id2 = db.addTx(t2, Collections.singletonList("صورة وصل".getBytes(StandardCharsets.UTF_8)));
        Db.TxInput t3 = new Db.TxInput();
        t3.office = amal;
        t3.type = Model.TYPE_DEBIT;
        t3.amount = 77700;
        t3.currency = "USD";
        t3.when = now - 900000;
        long id3 = db.addTx(t3, null);
        check(db.balances(nour).get("USD") == 100000 + 1000000 + 10000 && db.balances(nour).get("EUR") == -250000 - 1500,
                "النور: رصيد سابق 1,000 + حوالة 10,000 لنا + راجع 1% (100) = 11,100 USD؛ و-2,515 EUR");
        Db.TxInput t3b = new Db.TxInput();
        t3b.office = amal;
        t3b.type = Model.TYPE_DEBIT;
        t3b.amount = 88800;
        t3b.currency = "USD";
        t3b.when = t3.when;
        long id3b = db.editTx(id3, t3b, null, new HashSet<>(), "تصحيح المبلغ");
        check(db.balances(amal).get("USD") == 88800, "تعديل حركة: قيد عكسي + حركة مصححة → 888");
        db.cancelTx(id1, "إلغاء تجربة");
        check(db.balances(nour).get("USD") == 100000, "إلغاء: يعود الرصيد إلى الرصيد السابق 1,000");
        String[] fx = db.addFx(amal, "USD", 100000, "EUR", 92000, "0.92", "تبديل", now - 600000);
        check(fx != null && db.balances(amal).get("USD") == 188800 && db.balances(amal).get("EUR") == -92000, "تبديل عملة: ساقان مرتبطتان");
        Map<String, Long> before = new HashMap<>(db.balances(amal));
        for (Map.Entry<String, Long> x : db.balances(amal2).entrySet()) before.merge(x.getKey(), x.getValue(), Long::sum);
        db.mergeOffices(amal2, amal);
        Map<String, Long> after = new HashMap<>(db.balances(amal));
        after.values().removeIf(v -> v == 0);
        before.values().removeIf(v -> v == 0);
        check(after.equals(before) && db.office(amal2) == null, "دمج «مكتب الأمل» في «الأمل»: الرصيد = مجموع الرصيدين " + after);
        check(Crypto.healthy(ctx), "التشفير بمفتاح سلسلة المفاتيح سليم");
        check("سامر خليل".equals(db.tx(id1).person), "الحقول المشفّرة تُقرأ (المستفيد)");
        healthy(db, "بعد العمليات");
        Map<String, Long> opsBalances = namedBalances(db);
        long opsTx = db.txCount();

        System.out.println("== 3) نسخة من الماك تُستعاد في جهاز آخر");
        BackupManager.Created cr = BackupManager.createLocal(ctx, db, "Mac-pass-2026".toCharArray(), "8.1-mac", m -> {});
        check(cr.file.exists() && cr.size > 0, "نسخة مشفّرة ومتحقق منها: " + cr.file.getName() + " (" + cr.size + " بايت)");
        File out = new File(tmp("deliver"), "منقولة.sba");
        BackupManager.Delivery dl = BackupManager.copyToFile(cr.file, out);
        check(dl.verified, "النسخ إلى المكان المختار مع إعادة القراءة ومطابقة البصمة");
        Map<String, Long> counts = db.counts();
        db.close();
        Object[] dv2 = device("other");
        Context ctx2 = (Context) dv2[0];
        Db db2 = (Db) dv2[1];
        restore(ctx2, db2, out, "Mac-pass-2026");
        check(namedBalances(db2).equals(opsBalances) && db2.txCount() == opsTx, "الجهاز الآخر: الأرصدة والحركات نفسها " + namedBalances(db2));
        check(db2.counts().equals(counts), "عدد صفوف كل جدول مطابق " + db2.counts());
        check("سامر خليل".equals(db2.tx(id1).person), "الحقول تُفك بمفتاح الجهاز الجديد (أُعيد تشفيرها عند الاستعادة)");
        Model.Tx back2 = db2.tx(id2);
        check(back2 != null && db2.attachmentCounts(null).getOrDefault(id2, 0) == 1, "الصورة المرفقة انتقلت");
        healthy(db2, "بعد الاستعادة");
        // 10.13: الاستعادة مع تفضيلات المظهر أو بدونها
        String lookBackup = db2.setting("primary_color", "∅");
        db2.setSettingQuiet("primary_color", "#123456");
        BackupManager.Prepared pk = BackupManager.prepareRestore(ctx2, out, "Mac-pass-2026".toCharArray(), m -> {});
        pk.keepLook = true;
        BackupManager.applyRestore(ctx2, db2, pk, m -> {});
        check("#123456".equals(db2.setting("primary_color", "")) && namedBalances(db2).equals(opsBalances), "استعادة «بدون التفضيلات»: يبقى المظهر الحالي والبيانات من النسخة");
        BackupManager.Prepared pw2 = BackupManager.prepareRestore(ctx2, out, "Mac-pass-2026".toCharArray(), m -> {});
        BackupManager.applyRestore(ctx2, db2, pw2, m -> {});
        check(lookBackup.equals(db2.setting("primary_color", "∅")) && !db2.restoreKeepLook, "استعادة «مع التفضيلات»: يعود مظهر النسخة");
        // 10.14: رمز استرداد النسخ: نسخة بكلمة مرور منسية تُستعاد بالرمز
        String bcode = BackupRecovery.create(db2);
        BackupManager.Created rcB = BackupManager.createLocal(ctx2, db2, "كلمة-نُسيت-2026".toCharArray(), "10.14", m -> {});
        BackupManager.Prepared byCode = BackupManager.prepareRestore(ctx2, rcB.file, bcode.toLowerCase().toCharArray(), m -> {});
        check(byCode.parsed != null && byCode.report.offices > 0, "نسخة كُتبت بعد تفعيل رمز الاسترداد تُفتح بالرمز وحده (" + byCode.report.offices + " مكتب)");
        BackupManager.clearStaging(ctx2);
        BackupManager.Prepared byPw = BackupManager.prepareRestore(ctx2, rcB.file, "كلمة-نُسيت-2026".toCharArray(), m -> {});
        check(byPw.parsed != null, "وتُفتح بكلمة مرورها أيضًا");
        BackupManager.clearStaging(ctx2);
        boolean rcRefused = false;
        try {
            BackupManager.prepareRestore(ctx2, rcB.file, "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF".toCharArray(), m -> {});
        } catch (Exception ex) {
            rcRefused = true;
        }
        check(rcRefused, "ورمز آخر يُرفض");
        check(!db2.globalSetting("backup_rc_key", "").isEmpty() && !Db.personal("backup_rc_key"), "مفتاح الرمز محفوظ على الجهاز (لا الرمز نفسه)");
        BackupRecovery.disable(db2);
        BackupManager.Created plain = BackupManager.createLocal(ctx2, db2, "كلمة-عادية-2026".toCharArray(), "10.14", m -> {});
        boolean oldOnly = false;
        try {
            BackupManager.prepareRestore(ctx2, plain.file, bcode.toCharArray(), m -> {});
        } catch (Exception ex) {
            oldOnly = true;
        }
        check(oldOnly, "بعد إيقاف الرمز: النسخ الجديدة بكلمة المرور وحدها");
        BackupManager.clearStaging(ctx2);
        boolean wrong = false;
        try {
            BackupManager.prepareRestore(ctx2, out, "wrong-pass".toCharArray(), m -> {});
        } catch (BackupManager.BackupException e) {
            wrong = e.getCause() instanceof SecureStreams.WrongPasswordException;
        }
        check(wrong, "كلمة مرور خاطئة تُرفض بوضوح");
        db2.close();

        if (a.length > 2) {
            System.out.println("== 4) ملف حقيقي على الماك");
            Object[] dv3 = device("real");
            Context c3 = (Context) dv3[0];
            Db d3 = (Db) dv3[1];
            // 9.0: المجانية لا تستعيد نسخة فيها أكثر من 9 مكاتب كلها (يختار المستخدم 9)؛ الملف الحقيقي يُستعاد كاملًا بالنسخة الكاملة
            boolean refused = false;
            try {
                restore(c3, d3, new File(a[1]), a[2]);
            } catch (BackupManager.BackupException e) {
                refused = e.getCause() instanceof Entitlements.LimitReached;
            }
            check(refused && d3.officeCount() == 0, "المجانية: استعادة الملف الحقيقي كله تُرفض (أكثر من 9 مكاتب) ولا يتغير شيء");
            License.current = Entitlements.of(fullGrant(), BuildInfo.BUILD_DATE);
            long t0 = System.currentTimeMillis();
            restore(c3, d3, new File(a[1]), a[2]);
            long ms = System.currentTimeMillis() - t0;
            JSONObject ex = new JSONObject(new String(Files.readAllBytes(new File(a[3]).toPath()), StandardCharsets.UTF_8)).getJSONObject("REAL");
            Map<String, Long> want = new TreeMap<>();
            JSONArray bs = ex.getJSONArray("balances");
            for (int i = 0; i < bs.length(); i++) {
                JSONArray b = bs.getJSONArray(i);
                if (b.getLong(2) != 0) want.merge(b.getString(0) + "|" + b.getString(1), b.getLong(2), Long::sum);
            }
            Map<String, Long> got = namedBalances(d3);
            check(d3.txCount() == ex.getLong("tx") && got.equals(want), "استعادة " + d3.txCount() + " حركة في " + ms + " ms، والأرصدة غير الصفرية (" + got.size() + ") كما هي");
            healthy(d3, "الملف الحقيقي");
            long t1x = System.currentTimeMillis();
            BackupManager.Created c = BackupManager.createLocal(c3, d3, "test-1234".toCharArray(), "9.0-mac", m -> {});
            check(c.size > 0, "نسخة من الملف الحقيقي على الماك في " + (System.currentTimeMillis() - t1x) + " ms");
            d3.close();
            Object[] dv4 = device("real2");
            Db d4 = (Db) dv4[1];
            License.current = Entitlements.of(fullGrant(), BuildInfo.BUILD_DATE);
            restore((Context) dv4[0], d4, c.file, "test-1234");
            check(namedBalances(d4).equals(want), "ونسخة الماك نفسها تُستعاد بالأرصدة نفسها");
            d4.close();
            // 9.0: المجانية تختار 9 مكاتب (الأكثر حركات) من الملف الحقيقي: أرصدتها كما في الملف تمامًا، والقيود متوازنة
            Object[] dv5 = device("real9");
            Db d5 = (Db) dv5[1];
            BackupManager.Prepared pr = BackupManager.prepareRestore((Context) dv5[0], new File(a[1]), a[2].toCharArray(), m -> {});
            List<BackupFormat.OfficeInfo> infos = BackupFormat.officeSummaries(pr.parsed);
            infos.sort((x, y) -> Long.compare(y.txCount, x.txCount));
            Set<Long> keep = new HashSet<>();
            Set<String> keepNames = new HashSet<>();
            long keepTx = 0;
            for (int i = 0; i < 9; i++) {
                keep.add(infos.get(i).id);
                keepNames.add(infos.get(i).name);
                keepTx += infos.get(i).txCount;
            }
            pr.keep = keep;
            BackupManager.applyRestore((Context) dv5[0], d5, pr, m -> {});
            Map<String, Long> want9 = new TreeMap<>();
            for (Map.Entry<String, Long> e : want.entrySet()) if (keepNames.contains(e.getKey().split("\\|")[0])) want9.put(e.getKey(), e.getValue());
            check(d5.officeCount() == 9 && d5.txCount() == keepTx && namedBalances(d5).equals(want9),
                    "المجانية: استعادة 9 مكاتب مختارة من الملف الحقيقي (" + keepTx + " حركة) بأرصدتها كما في الملف (" + want9.size() + " رصيدًا)");
            healthy(d5, "الاستعادة الانتقائية من الملف الحقيقي");
            d5.close();
        }
        // 11.5: استخراج رمز الترخيص من ملف المفتاح ورابط QR ورسائل واتساب مقسّمة، وضغط طلب التفعيل
        System.out.println("== 11.5) رمز الترخيص وطلب التفعيل");
        String tk = "dj0xO2tpZD1rMTtsaWQ9U0EtMjAyNi0wMDAxO2RrPUFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaMDEyMzQ1Njc4OWFiY2Q.MEUCIQDx9dAyiOI8tq8eBaEbS0g9n1bBkOVv7lj8C2dOq6Js4QIgUqFzVBwV1gO8jWQ4Tt3fYIxm2_R5_Pyk0L7rRxLd-Ys";
        check(tk.equals(LicenseToken.extract("SMARTACC-LICENSE\n" + tk + "\n")), "من ملف المفتاح");
        check(tk.equals(LicenseToken.extract("smartacc://activate?k=" + tk)), "من رابط QR");
        String split = "[14/11, 18:14] البائع: رمز ترخيص «المحاسب الذكي» (SA-2026-0001):\n[14/11, 18:14] البائع: " + tk.substring(0, 90)
                + "\n[14/11, 18:15] البائع: " + tk.substring(90) + "\n[14/11, 18:15] البائع: في التطبيق: المزيد ← الترخيص";
        check(tk.equals(LicenseToken.extract(split)), "من رسالتي واتساب منسوختين معًا (البادئات تُحذف والجزءان يُجمعان)");
        check(tk.equals(LicenseToken.extract("  " + tk.substring(0, 50) + "\n " + tk.substring(50) + " ")), "الرمز بأسطر ومسافات");
        String js = "{\"t\":\"sa-activate\",\"v\":1,\"order\":\"2026-0001\",\"chain\":[\"" + "QUJD".repeat(600) + "\"]}";
        String z = LicenseToken.packRequest(js);
        java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
        inf.setInput(java.util.Base64.getUrlDecoder().decode(z.substring(9)));
        byte[] back = new byte[js.length() + 64];
        int nb = inf.inflate(back);
        check(z.startsWith("SA8-REQZ:") && js.equals(new String(back, 0, nb, java.nio.charset.StandardCharsets.UTF_8)), "طلب التفعيل المضغوط يُفك كما هو");
        if (System.getProperty("sa.reqz.out") != null) java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("sa.reqz.out")), z.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println(fails == 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
        if (fails > 0) System.exit(1);
    }
}
