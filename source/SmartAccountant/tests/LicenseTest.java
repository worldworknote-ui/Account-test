import com.smart.hawala.BackupPolicy;
import com.smart.hawala.Entitlements;
import com.smart.hawala.LicenseToken;
import com.smart.hawala.QuotaState;
import com.smart.hawala.Report;
import com.smart.hawala.XlsxWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 8.0: الترخيص وحدود المجاني. رمز موقّع ECDSA P-256 مربوط ببصمة مفتاح الهاتف؛ ما يحق للنسخة؛ عدّادات لا تنقص؛
 * توقيت النسخ التلقائي؛ علامة «نسخة مجانية» وبصمة الرخصة في الملفات المصدّرة.
 * وسيط اختياري: مجلد فيه node-tokens.json من اختبارات أداة البائع (توقيع WebCrypto) للتحقق عبر التطبيقين.
 */
public class LicenseTest {
    static int fails = 0, passes = 0;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static final Base64.Encoder U = Base64.getUrlEncoder().withoutPadding();
    static KeyPair vendor, vendor2, device, device2;
    static Map<String, String> trusted = new HashMap<>();

    static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String token(String payload, KeyPair k) throws Exception {
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k.getPrivate());
        s.update(p);
        return U.encodeToString(p) + "." + U.encodeToString(s.sign());
    }

    static String dk(KeyPair d) throws Exception { return LicenseToken.deviceFingerprint(d.getPublic().getEncoded()); }

    static String base(KeyPair d) throws Exception {
        return "v=1;lid=SA-2026-0001;ed=pro;ft=all;uu=20271231;dk=" + dk(d) + ";kid=t1";
    }

    static LicenseToken.Grant verify(String t, KeyPair d) { return LicenseToken.verify(t, d == null ? null : d.getPublic().getEncoded(), trusted); }

    public static void main(String[] args) throws Exception {
        vendor = ec();
        vendor2 = ec();
        device = ec();
        device2 = ec();
        trusted.put("t1", Base64.getEncoder().encodeToString(vendor.getPublic().getEncoded()));
        trusted.put("t2", Base64.getEncoder().encodeToString(vendor2.getPublic().getEncoded()));

        System.out.println("== بصمة مفتاح الهاتف");
        byte[] pub = device.getPublic().getEncoded();
        String want = U.encodeToString(MessageDigest.getInstance("SHA-256").digest(pub));
        check(want.equals(dk(device)), "dk = Base64url(SHA-256(X.509)) بلا حشو");
        check(dk(device).length() == 43 && !dk(device).contains("="), "طول البصمة 43 حرفًا");

        System.out.println("== رمز صحيح");
        String good = token(base(device), vendor);
        LicenseToken.Grant g = verify(good, device);
        check(g != null, "رمز موقّع لهذا الهاتف يُقبل");
        check(g != null && "SA-2026-0001".equals(g.lid()), "رقم الرخصة يُقرأ");
        check(g != null && g.has("offices") && g.has("ai") && g.has("anything"), "ft=all يشمل كل الميزات");
        check(g != null && g.coversBuild(20261014) && g.coversBuild(20271231) && !g.coversBuild(20280101), "فترة التحديثات uu تشمل البناء حتى تاريخها فقط");
        check(verify(" " + good.substring(0, 20) + "\n" + good.substring(20, 50) + "\r\n " + good.substring(50) + "\n", device) != null,
                "مسافات وأسطر جديدة من النسخ واللصق تُتجاهل");
        check(verify(token(base(device).replace("kid=t1", "kid=t2"), vendor2), device) != null, "المفتاح الاحتياطي k2 (t2) مقبول");

        System.out.println("== رموز مرفوضة");
        check(verify(good, device2) == null, "الرمز نفسه على هاتف آخر يُرفض (dk)");
        String forged = U.encodeToString(base(device).replace("ft=all", "ft=all,x").getBytes(StandardCharsets.UTF_8)) + good.substring(good.indexOf('.'));
        check(verify(forged, device) == null, "تعديل حقل بعد التوقيع يُرفض");
        check(verify(token(base(device), ec()), device) == null, "توقيع بمفتاح غير موثوق يُرفض");
        check(verify(token(base(device), vendor2), device) == null, "توقيع k2 مع kid=k1 يُرفض");
        check(verify(token(base(device).replace("kid=t1", "kid=zz"), vendor), device) == null, "kid مجهول يُرفض");
        check(verify(token(base(device).replace(";kid=t1", ""), vendor), device) == null, "بلا kid يُرفض");
        check(verify(token(base(device).replace("v=1", "v=2"), vendor), device) == null, "إصدار صيغة غير معروف v=2 يُرفض");
        check(verify(token(base(device).replace("lid=SA-2026-0001", "lid="), vendor), device) == null, "بلا رقم رخصة يُرفض");
        check(verify(token(base(device) + ";ft=none", vendor), device) == null, "حقل مكرر يُرفض");
        check(verify(token(base(device).replace("dk=" + dk(device), "dk=" + dk(device2)), vendor), device) == null, "رمز صحيح لهاتف آخر لا يعمل هنا");
        check(verify(good.substring(0, good.length() - 4), device) == null, "رمز مبتور يُرفض");
        check(verify(good.replace(".", ""), device) == null, "بلا نقطة يُرفض");
        check(verify(good + ".x", device) == null, "نقطتان يُرفض");
        check(verify(good.substring(0, good.indexOf('.') + 1), device) == null, "توقيع فارغ يُرفض");
        check(verify("@@@." + good.substring(good.indexOf('.') + 1), device) == null, "Base64 فاسد يُرفض");
        check(verify(null, device) == null && verify(good, null) == null, "null لا يرمي");
        check(LicenseToken.verify(good, pub) == null, "مفتاح اختبار ليس من المفاتيح المضمّنة في التطبيق");
        // التوقيع الخام (r||s) كما يعطيه WebCrypto لا يُقبل: أداة البائع يجب أن تحوّله إلى DER
        byte[] der = Base64.getUrlDecoder().decode(good.substring(good.indexOf('.') + 1));
        byte[] raw = derToRaw(der);
        check(raw.length == 64 && verify(good.substring(0, good.indexOf('.') + 1) + U.encodeToString(raw), device) == null, "توقيع خام 64 بايت (P1363) يُرفض: الصيغة DER");
        check(verify(good.substring(0, good.indexOf('.') + 1) + U.encodeToString(rawToDer(raw)), device) != null, "تحويل الخام إلى DER (كما في أداة البائع) يُقبل");
        check(LicenseToken.peek(good).get("lid").equals("SA-2026-0001") && LicenseToken.peek("garbage").isEmpty(), "peek للعرض فقط ولا يرمي");

        System.out.println("== المفاتيح المضمّنة");
        check(LicenseToken.TRUSTED.size() == 2 && LicenseToken.TRUSTED.containsKey("k1") && LicenseToken.TRUSTED.containsKey("k2"), "k1 و k2 مضمّنان");
        boolean immutable;
        try {
            LicenseToken.TRUSTED.put("evil", "x");
            immutable = false;
        } catch (UnsupportedOperationException e) {
            immutable = true;
        }
        check(immutable, "قائمة المفاتيح الموثوقة لا تُعدَّل أثناء التشغيل");
        for (String k : LicenseToken.TRUSTED.values()) {
            java.security.PublicKey pk = java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(k)));
            check(pk.getAlgorithm().equals("EC") && ((java.security.interfaces.ECPublicKey) pk).getParams().getCurve().getField().getFieldSize() == 256, "مفتاح مضمّن صالح P-256");
        }

        System.out.println("== ما يحق للنسخة");
        Entitlements free = Entitlements.free("");
        check(!free.pro && free.maxOffices() == 15 && free.maxTxPerOffice() == 1500, "المجانية (12.6): 15 مكتبًا و1500 حركة لكل مكتب");
        check(free.maxCashBoxes() == 1 && free.maxClients() == 25, "المجانية (12.6): صندوق واحد و25 عميلًا");
        check(free.waPerDay() == 20 && Entitlements.of(g, 20261014).waPerDay() == Entitlements.UNLIMITED, "المجانية: استخراج 20 رسالة واتساب يوميًا، والكاملة بلا حد");
        check(free.maxUsers() == 1 && !free.ai() && free.watermark(), "المجانية: مستخدم واحد، بلا ذكاء اصطناعي، بعلامة على التصدير");
        check(free.autoBackupMinHours() == 24 && free.autoBackupEveryTx() == 0, "المجانية: نسخ تلقائي يومي على الأكثر");
        Entitlements pro = Entitlements.of(g, 20261014);
        check(pro.pro && pro.maxOffices() == Entitlements.UNLIMITED && pro.maxTxPerOffice() == Entitlements.UNLIMITED, "الكاملة: بلا حدود");
        check(pro.maxUsers() == Entitlements.UNLIMITED && pro.ai() && !pro.watermark(), "الكاملة: مستخدمون، ذكاء اصطناعي، بلا علامة");
        check(pro.autoBackupMinHours() == 6 && pro.autoBackupEveryTx() == 25, "الكاملة: كل 6 ساعات أو 25 حركة");
        check("2027/12/31".equals(pro.updatesUntil()) && "SA-2026-0001".equals(pro.lid), "تاريخ التحديثات ورقم الرخصة للعرض");
        Entitlements late = Entitlements.of(g, 20280115);
        check(!late.pro && late.maxOffices() == 15 && late.note.contains("2027/12/31"), "بناء بعد فترة التحديثات: مجانية مع سبب واضح");
        LicenseToken.Grant part = verify(token(base(device).replace("ft=all", "ft=offices,export").replace("ed=pro", "ed=مكتب صغير") + ";mo=20", vendor), device);
        Entitlements pe = Entitlements.of(part, 20261014);
        check(pe.pro && pe.maxOffices() == 20 && pe.maxTxPerOffice() == 1500, "ميزات جزئية: mo=20 مكتبًا والحركات بحد المجاني");
        check(pe.maxCashBoxes() == Entitlements.UNLIMITED && pe.maxClients() == Entitlements.UNLIMITED && pe.waPerDay() == Entitlements.UNLIMITED,
                "رخصة قديمة بلا حدود الصناديق والعملاء: مفتوحة كما كانت (لا ينقص على أحد)");
        check(!pe.ai() && !pe.watermark() && pe.maxUsers() == 1 && "مكتب صغير".equals(pe.edition()), "ميزات جزئية: export بلا علامة، ولا ai ولا users");
        check(Entitlements.of(null, 20261014).maxOffices() == 15, "بلا رمز = مجانية");
        // 12.6: رخصة مخصصة لكل زبون بالأرقام، واشتراك بتاريخ انتهاء
        LicenseToken.Grant cu = verify(token(base(device).replace("ft=all", "ft=offices,tx,users,boxes,clients,export,backup").replace("ed=pro", "ed=أساسية")
                + ";mo=30;mu=3;mb=2;mc=100;mw=50;bh=3;bn=10;ex=20271231", vendor), device);
        Entitlements ce = Entitlements.of(cu, 20261014, 20270601);
        check(ce.pro && ce.maxOffices() == 30 && ce.maxTxPerOffice() == Entitlements.UNLIMITED && ce.maxUsers() == 3, "مخصصة: 30 مكتبًا، حركات بلا حد، 3 مستخدمين");
        check(ce.maxCashBoxes() == 2 && ce.maxClients() == 100 && ce.waPerDay() == 50, "مخصصة: صندوقان و100 عميل و50 رسالة يوميًا");
        check(ce.autoBackupMinHours() == 3 && ce.autoBackupEveryTx() == 10 && !ce.ai() && "2027/12/31".equals(ce.expires()), "مخصصة: نسخ كل 3 ساعات أو 10 حركات، والاشتراك حتى 2027/12/31");
        Entitlements expired = Entitlements.of(cu, 20261014, 20280101);
        check(!expired.pro && expired.maxOffices() == 15 && expired.note.contains("انتهى"), "بعد انتهاء الاشتراك: المجانية مع سبب واضح");
        check(Entitlements.of(cu, 20261014, 20271231).pro, "يوم الانتهاء نفسه ما زال ساريًا");
        check(Entitlements.userLimitMessage(3).contains("3") && Entitlements.boxLimitMessage(1).contains("صندوق واحد"), "رسائل الحدود تذكر الرقم");

        System.out.println("== عدّادات المجاني");
        QuotaState q = QuotaState.parse("o=3;t5=12;t9=499");
        check(q.offices == 3 && q.txOf(5) == 12 && q.txOf(9) == 499 && q.txOf(1) == 0, "قراءة العدّادات");
        check(QuotaState.parse(q.serialize()).serialize().equals(q.serialize()) && q.serialize().equals("o=3;t5=12;t9=499"), "الكتابة ثابتة الترتيب");
        check(QuotaState.parse("o=x;t5=-4;tq=3;;=;t7=2").serialize().equals("o=0;t7=2"), "أجزاء فاسدة أو سالبة تُتجاهل");
        check(QuotaState.parse(null).offices == 0 && QuotaState.parse("").tx.isEmpty(), "فارغ = صفر");
        Map<Long, Long> live = new HashMap<>();
        live.put(5L, 10L);
        live.put(7L, 40L);
        q.raiseTo(2, live);
        check(q.offices == 3 && q.txOf(5) == 12 && q.txOf(7) == 40, "الأرضية ترفع ولا تُنقص");
        q.raiseTo(6, new HashMap<>());
        check(q.offices == 6, "أرضية أكبر ترفع عدد المكاتب");
        q.officeDeleted(7);
        check(q.offices == 5 && q.txOf(7) == 0, "9.0: حذف مكتب له حركات يعيد مكانه فارغًا (ويُحذف عدّاد حركاته)");
        q.officeDeleted(11);
        check(q.offices == 4, "حذف مكتب بلا حركات يعيد مكانه");
        q.officesMerged(5, 9);
        check(q.txOf(9) == 511 && q.txOf(5) == 0, "الدمج يجمع حركات المكتبين في الهدف");
        QuotaState b = new QuotaState();
        b.offices = 14;
        check(b.officeRefusal(free) == null, "المكتب الخامس عشر مسموح");
        b.offices = 15;
        String no = b.officeRefusal(free);
        check(no != null && no.contains("15") && no.contains("الكشوف"), "المكتب السادس عشر مرفوض برسالة تذكر ما يبقى متاحًا");
        check(b.officeRefusal(pro) == null, "الكاملة: لا رفض");
        b.tx.put(4L, 1499L);
        check(b.txRefusal(free, 4, "الأمل") == null, "الحركة 1500 مسموحة");
        b.tx.put(4L, 1500L);
        String nt = b.txRefusal(free, 4, "الأمل");
        check(nt != null && nt.contains("الأمل") && nt.contains("1500") && nt.contains("تصحيح"), "الحركة 1501 مرفوضة باسم المكتب");
        check(b.txRefusal(free, 3, "آخر") == null, "مكتب آخر تحت السقف يبقى يعمل");
        check(b.txRefusal(pro, 4, "الأمل") == null, "الكاملة: لا رفض");
        check(QuotaState.remaining(480, 500) == 20 && QuotaState.remaining(700, 500) == 0 && QuotaState.remaining(5, Entitlements.UNLIMITED) == -1, "المتبقي للعرض");
        // محاكاة: تسجيل 500 حركة ثم الإلغاء لا يعيد شيئًا (العدّاد لا ينقص) والتصحيح لا يُحتسب
        QuotaState sim = new QuotaState();
        for (int i = 0; i < 1500; i++) {
            check1(sim.txRefusal(free, 1, "س") == null);
            sim.tx.put(1L, sim.txOf(1) + 1);
        }
        Map<Long, Long> afterCancel = new HashMap<>();
        afterCancel.put(1L, 1500L);   // الأرضية تحتسب الملغاة
        sim.raiseTo(1, afterCancel);
        check(okAll && sim.txRefusal(free, 1, "س") != null, "1500 حركة تُسجَّل ثم تتوقف الإضافة على المكتب");

        System.out.println("== توقيت النسخ التلقائي");
        check(BackupPolicy.intervalHours(6, 1, 24) == 24, "المجانية: إعداد 6 ساعات يُرفع إلى 24");
        check(BackupPolicy.intervalHours(6, 1, 6) == 6, "الكاملة: 6 ساعات");
        check(BackupPolicy.intervalHours(0, 3, 24) == 72 && BackupPolicy.intervalHours(0, 0, 24) == 24, "الإعداد القديم بالأيام");
        check(BackupPolicy.everyTx(-1, 0) == 0 && BackupPolicy.everyTx(25, 0) == 0, "المجانية: لا نسخ بعدد الحركات");
        check(BackupPolicy.everyTx(-1, 25) == 25 && BackupPolicy.everyTx(0, 25) == 0 && BackupPolicy.everyTx(10, 25) == 25 && BackupPolicy.everyTx(50, 25) == 50,
                "الكاملة: الافتراضي 25، و0 يوقفه، وما دون 25 يُرفع");
        long h = 3600000L, now = 1_800_000_000_000L;
        check(BackupPolicy.due(now, 0, 24, 0, 0), "لا نسخة سابقة: يحين");
        check(!BackupPolicy.due(now, now - 20 * h, 24, 0, 0) && BackupPolicy.due(now, now - 23 * h - 40 * 60000L, 24, 0, 0), "يوميًا مع هامش نصف ساعة");
        check(!BackupPolicy.due(now, now - 2 * h, 24, 24, 25) && BackupPolicy.due(now, now - 2 * h, 24, 25, 25), "بعد 25 حركة يحين قبل الموعد");
        check(!BackupPolicy.due(now, now - 5 * 60000L, 24, 100, 25), "لا نسختان خلال 10 دقائق");
        check(BackupPolicy.due(now, now + 5 * h, 24, 0, 0), "ساعة رجعت إلى الوراء: يُنسخ");

        System.out.println("== علامة النسخة المجانية في الملفات");
        Report r = new Report();
        r.title = "كشف تجريبي";
        r.section("USD", "USD").col("التاريخ", 1, Report.TEXT).col("المبلغ", 1, Report.MONEY).row(Report.NORMAL, "2026/10/14", new Report.Money(150000, 2));
        r.stamp(true, "SA-2026-0001");
        check(Report.FREE_MARK.equals(r.watermark) && r.licenseId.isEmpty(), "المجانية: علامة بلا رقم رخصة");
        Map<String, String> xf = xlsx(r);
        check(xf.get("xl/worksheets/sheet1.xml").contains(Report.FREE_MARK), "سطر «نسخة مجانية» ظاهر في الورقة");
        check(xf.get("xl/worksheets/sheet1.xml").contains("<oddFooter>"), "وفي تذييل الطباعة");
        check(xf.get("docProps/core.xml").contains("<dc:description>") && !xf.get("docProps/core.xml").contains("keywords"), "وصف الملف يذكر النسخة المجانية");
        check(xf.get("xl/worksheets/sheet1.xml").contains("<v>1500.00</v>"), "المبلغ نفسه لم يتغير");
        Report r2 = new Report();
        r2.title = "كشف";
        r2.section("USD", "USD").col("المبلغ", 1, Report.MONEY).row(Report.NORMAL, new Report.Money(150000, 2));
        r2.stamp(false, "SA-2026-0001");
        Map<String, String> xp = xlsx(r2);
        check(!xp.get("xl/worksheets/sheet1.xml").contains(Report.FREE_MARK) && !xp.get("xl/worksheets/sheet1.xml").contains("oddFooter"), "الكاملة: بلا علامة");
        check(xp.get("docProps/core.xml").contains("<cp:keywords>SA-SA-2026-0001</cp:keywords>"), "الكاملة: رقم الرخصة في خصائص الملف");
        check(wellFormed(xf) && wellFormed(xp), "XML سليم في الحالتين");

        if (args.length > 0) {
            nodeTokens(Paths.get(args[0], "node-tokens.json"));
            for (String k : new String[]{"k1", "k2"}) {
                Path rp = Paths.get(args[0], "real-" + k + ".json");
                if (!Files.exists(rp)) continue;
                // رمز وقّعته أداة البائع بالمفتاح الحقيقي (الملف خارج المشروع): يقبله التحقق المضمّن في التطبيق كما هو
                String j = new String(Files.readAllBytes(rp), StandardCharsets.UTF_8);
                byte[] dev = Base64.getDecoder().decode(field(j, "devicePub"));
                LicenseToken.Grant rg = LicenseToken.verify(field(j, "token"), dev);
                check(rg != null && ("SA-TEST-" + k).equals(rg.lid()), "مفتاح البائع الحقيقي " + k + ": الرمز يُقبل بالمفاتيح المضمّنة");
            }
        }

        System.out.println(fails == 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
        if (fails > 0) System.exit(1);
    }

    static boolean okAll = true;

    static void check1(boolean c) { okAll &= c; }

    /** رموز وقّعتها أداة البائع (WebCrypto في Node) بمفتاح اختبار: يجب أن يقبلها التطبيق كما هي. */
    static void nodeTokens(Path p) throws Exception {
        if (!Files.exists(p)) {
            System.out.println("  (لا يوجد " + p + ")");
            return;
        }
        System.out.println("== رموز من أداة البائع (Node WebCrypto)");
        String j = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Map<String, String> tr = new HashMap<>();
        tr.put(field(j, "kid"), field(j, "vendorPub"));
        byte[] dev = Base64.getDecoder().decode(field(j, "devicePub"));
        Matcher m = Pattern.compile("\\{\"name\":\"([^\"]*)\",\"token\":\"([^\"]*)\",\"ok\":(true|false)\\}").matcher(j);
        int n = 0;
        while (m.find()) {
            n++;
            boolean got = LicenseToken.verify(m.group(2).replace("\\n", "\n"), dev, tr) != null;   // \n في JSON = سطر جديد حقيقي
            check(got == Boolean.parseBoolean(m.group(3)), "Node: " + m.group(1) + " → " + (got ? "مقبول" : "مرفوض"));
        }
        check(n >= 3, "قُرئت " + n + " رموز من أداة البائع");
    }

    static String field(String j, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":\"([^\"]*)\"").matcher(j);
        return m.find() ? m.group(1) : "";
    }

    static Map<String, String> xlsx(Report r) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        XlsxWriter.write(r, bo, null, TimeZone.getTimeZone("UTC"));
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bo.toByteArray()))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    static boolean wellFormed(Map<String, String> files) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (e.getKey().endsWith(".xml") || e.getKey().endsWith(".rels")) f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            return true;
        } catch (Exception e) {
            System.out.println("    " + e);
            return false;
        }
    }

    // ---- DER <-> خام (r||s) كما تفعل أداة البائع ----
    static byte[] derToRaw(byte[] der) {
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7f;
        byte[] out = new byte[64];
        for (int part = 0; part < 2; part++) {
            int len = der[i + 1];
            int start = i + 2;
            int skip = len > 32 ? len - 32 : 0;
            System.arraycopy(der, start + skip, out, part * 32 + (32 - (len - skip)), len - skip);
            i = start + len;
        }
        return out;
    }

    static byte[] rawToDer(byte[] raw) {
        byte[][] parts = new byte[2][];
        for (int p = 0; p < 2; p++) {
            int s = p * 32;
            while (s < p * 32 + 31 && raw[s] == 0) s++;
            int len = p * 32 + 32 - s;
            boolean pad = (raw[s] & 0x80) != 0;
            byte[] v = new byte[len + (pad ? 1 : 0)];
            System.arraycopy(raw, s, v, pad ? 1 : 0, len);
            parts[p] = v;
        }
        int total = 2 + parts[0].length + 2 + parts[1].length;
        byte[] der = new byte[2 + total];
        der[0] = 0x30;
        der[1] = (byte) total;
        int i = 2;
        for (byte[] v : parts) {
            der[i++] = 0x02;
            der[i++] = (byte) v.length;
            System.arraycopy(v, 0, der, i, v.length);
            i += v.length;
        }
        return der;
    }
}
