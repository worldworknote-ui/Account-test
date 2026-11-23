import android.content.Context;
import android.database.Cursor;
import com.smart.hawala.*;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 12.7 على Db الحقيقي وبيانات اصطناعية (لا بيانات حقيقية ولا اتصال بخدمة خارجية؛ الوقت من خادم وهمي محلي):
 * لا تنتظر الشاشة أعمال الخلفية (الفحص، اللقطة، الانضمام)، الانضمام على مرحلتين والاستئناف، ملخص المكاتب بالطريقة الجديدة
 * يطابق القديمة، البحث بالنص المحلي يطابق البحث القديم، الفحص اليومي المتتابع لسجل العمليات، ربط الرخصة بالمكتب وسجل
 * المحاولات المختوم، تأكيد الوقت ومهلته، الحركات لكل مستخدم، ومزوّدات القراءة الذكية السحابية.
 * الاستعمال: java Release127Test [مجلد tests/data لكتابة طلب تفعيل نموذجي لأداة البائع]
 */
public class Release127Test {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Db.TxInput tx(long office, boolean us, long amount, String cur, String person, String desc) {
        Db.TxInput t = new Db.TxInput();
        t.office = office;
        t.type = us ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        t.amount = amount;
        t.currency = cur;
        t.person = person;
        t.description = desc;
        t.when = Clock.now() - 90L * 86_400_000L + (long) (Math.abs(desc.hashCode()) % (80L * 86_400_000L));
        return t;
    }

    static LicenseToken.Grant grant(Map<String, String> f) throws Exception {
        java.lang.reflect.Constructor<LicenseToken.Grant> c = LicenseToken.Grant.class.getDeclaredConstructor(Map.class);
        c.setAccessible(true);
        return c.newInstance(f);
    }

    static Object call(Class<?> k, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = k.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    /** أطول انتظار لقراءة بسيطة من «الشاشة» أثناء عمل في الخلفية. */
    static long[] probe(Db d, Runnable work) throws Exception {
        final long[] max = {0};
        final boolean[] stop = {false};
        Thread p = new Thread(() -> {
            while (!stop[0]) {
                long t = System.nanoTime();
                d.setting("office_name", "");
                d.decimals("USD");
                long w = (System.nanoTime() - t) / 1_000_000;
                if (w > max[0]) max[0] = w;
                try {
                    Thread.sleep(15);
                } catch (Exception e) {
                    return;
                }
            }
        });
        p.start();
        long t0 = System.currentTimeMillis();
        work.run();
        long total = System.currentTimeMillis() - t0;
        stop[0] = true;
        p.join();
        return new long[]{total, max[0]};
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("sa.secrets", "file");
        File data = a.length > 0 ? new File(a[0]) : null;

        System.out.println("== 1) الشاشة لا تنتظر أعمال الخلفية");
        Object[] dv = DeskCoreTest.device("r127a");
        Context ctx = (Context) dv[0];
        final Db db = (Db) dv[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        List<Long> offs = new ArrayList<>();
        for (int i = 1; i <= 12; i++) offs.add(db.addOffice("مكتب " + i, "", "", "", "", "", null, null, Arrays.asList("USD", "EUR", "TRY"), new HashMap<>(), new ArrayList<>()));
        Random r = new Random(11);
        String[] names = {"أحمد الخطيب", "محمد العلي", "خالد يوسف", "Sami Haddad", "ليلى قاسم"};
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            Db.TxInput t = tx(offs.get(r.nextInt(offs.size())), r.nextBoolean(), 1000 + r.nextInt(900000), new String[]{"USD", "EUR", "TRY"}[r.nextInt(3)],
                    names[r.nextInt(names.length)] + " " + r.nextInt(300), "حوالة رقم " + i + " إلى حلب");
            ids.add(db.addTx(t, null));
        }
        for (int i = 0; i < 300; i++) db.cancelTx(ids.get(i * 7), "اختبار");
        long[] h = probe(db, db::runHealthAuto);
        check(h[1] < 300, "فحص السلامة في الخلفية (" + h[0] + " ms) والشاشة انتظرت " + h[1] + " ms فقط");
        long hits0 = net.zetetic.database.sqlcipher.SQLiteDatabase.READER_HITS.get();
        File shared = Files.createTempDirectory("r127-shared").toFile();
        long[] cr = probe(db, () -> {
            try {
                new SyncEngine(db, new FileSyncFolder(shared)).create("كلمة-سر-طويلة-127".toCharArray(), "A");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        check(cr[1] < 300, "إنشاء الخزنة واللقطة الكاملة (" + cr[0] + " ms) والشاشة انتظرت " + cr[1] + " ms فقط");
        check(net.zetetic.database.sqlcipher.SQLiteDatabase.READER_HITS.get() > hits0, "قراءات الشاشة خدمها اتصال القراءة الثاني أثناء عمل الخلفية");
        File cache = ctx.getCacheDir();
        String[] left = cache.list((x, n) -> n.startsWith("read-"));
        check(left == null || left.length == 0, "نسخة الملف المؤقتة للّقطة حُذفت بعد البناء");

        System.out.println("== 2) الانضمام على مرحلتين، والاستئناف بعد انقطاع");
        Db B = new Db(new Context(Files.createTempDirectory("r127b").toFile()));
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        boolean wrong = false;
        try {
            new SyncEngine(B, new FileSyncFolder(shared)).joinPrepare("كلمة-خاطئة-تماما".toCharArray(), false, "B");
        } catch (IllegalArgumentException e) {
            wrong = e.getMessage().contains("غير صحيحة");
        }
        check(wrong && !SyncEngine.joinPending(B) && !SyncEngine.enabled(B), "كلمة سر خاطئة: لا انضمام معلّق ولا تغيير");
        new SyncEngine(B, new FileSyncFolder(shared)).joinPrepare("كلمة-سر-طويلة-127".toCharArray(), false, "هاتف B");
        check(SyncEngine.joinPending(B) && !SyncEngine.enabled(B) && "هاتف B".equals(SyncEngine.pendingName(B)), "بعد كلمة السر: الجهاز منضم ويُنتظر التحميل (والبيانات لم تُستبدل بعد)");
        String pend = new String(Files.readAllBytes(new File(B.evidenceDir().getParentFile(), "sync-join-pending.json").toPath()), StandardCharsets.UTF_8);
        check(!pend.contains("كلمة-سر-طويلة-127"), "ملف الانضمام المعلّق لا يحوي كلمة السر (مفتاح الخزنة مشفّر بمفتاح الجهاز)");
        // انقطاع: كائن جديد (كأن التطبيق أُغلق وأُعيد فتحه) يكمل التحميل
        final List<Integer> pcts = new ArrayList<>();
        new SyncEngine(B, new FileSyncFolder(shared)).joinFinish((pc, m) -> pcts.add(pc));
        check(SyncEngine.enabled(B) && !SyncEngine.joinPending(B), "الاستئناف أكمل الانضمام وحذف الملف المعلّق");
        boolean rising = !pcts.isEmpty() && pcts.get(pcts.size() - 1) == 100;
        for (int i = 1; i < pcts.size(); i++) if (pcts.get(i) < pcts.get(i - 1)) rising = false;
        check(rising, "شريط التقدم يصعد حتى 100٪ (" + pcts + ")");
        check(db.officeBalances().equals(B.officeBalances()), "أرصدة الجهاز المنضم تطابق الأصل");
        new SyncEngine(B, new FileSyncFolder(shared)).joinFinish(null);
        check(SyncEngine.enabled(B), "إعادة الاستئناف بعد الاكتمال لا تفعل شيئًا");

        System.out.println("== 3) ملخص المكاتب بالطريقة السريعة يطابق القديمة");
        Map<String, long[]> old = new HashMap<>();
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT l.office_id,l.currency,SUM(l.debit_minor),SUM(l.credit_minor) FROM journal_lines l"
                + " JOIN journal_entries e ON e.id=l.entry_id LEFT JOIN tx t ON t.id=e.tx_id"
                + " WHERE l.account='OFFICE' AND l.office_id IS NOT NULL AND (e.tx_id IS NULL OR t.status='ACTIVE') GROUP BY l.office_id,l.currency", null)) {
            while (c.moveToNext()) old.put(c.getLong(0) + "/" + c.getString(1), new long[]{c.getLong(2), c.getLong(3)});
        }
        boolean same = true;
        int compared = 0;
        for (Model.OfficeTotals ot : db.officeTotals(null)) {
            for (Map.Entry<String, long[]> e : ot.perCurrency.entrySet()) {
                long[] o = old.get(ot.officeId + "/" + e.getKey());
                long[] n = e.getValue();
                if (o == null) {
                    if (n[0] != 0 || n[1] != 0) same = false;
                } else {
                    compared++;
                    if (o[0] != n[0] || o[1] != n[1] || n[2] != o[0] - o[1]) same = false;
                }
            }
        }
        check(same && compared >= 30, "كل مكتب وعملة: لنا وعلينا والرصيد كما كانت (" + compared + " خانة، مع 300 حركة ملغاة)");
        Model.OfficeTotals one = db.officeTotals(offs.get(3)).get(0);
        boolean sameOne = true;
        for (Map.Entry<String, long[]> e : one.perCurrency.entrySet()) {
            long[] o = old.get(offs.get(3) + "/" + e.getKey());
            if (o != null && (o[0] != e.getValue()[0] || o[1] != e.getValue()[1])) sameOne = false;
        }
        check(sameOne, "ملخص مكتب واحد يطابق أيضًا");
        boolean idx = false;
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name IN ('idx_lines_bal','idx_tx_status','idx_audit_action')", null)) {
            int n = 0;
            while (c.moveToNext()) n++;
            idx = n == 3;
        }
        check(idx, "الفهارس الجديدة موجودة (بالترحيل نفسه، بلا تغيير في البيانات)");

        System.out.println("== 4) البحث بالنص المحلي يطابق البحث القديم");
        String[] terms = {"خالد", "sami", "حوالة رقم 123", "حلب", "1,2", "eur", "مكتب 7", "غير موجود أبدًا"};
        Map<Long, String> on = db.officeNames();
        boolean allSame = true;
        for (String term : terms) {
            String t = term.toLowerCase(Locale.ROOT);
            Db.TxFilter f = new Db.TxFilter();
            f.includeCancelled = true;
            Set<Long> want = new TreeSet<>(), got = new TreeSet<>();
            for (Model.Tx x : db.txLog(f)) {
                int d = db.decimals(x.currency);
                String hay = (Model.nz(on.get(x.officeId)) + " " + x.person + " " + x.description + " " + x.notes + " " + x.uuid + " " + Model.nz(x.userName) + " " + Model.nz(x.dest) + " " + x.currency
                        + " " + Model.plain(x.amount, d) + " " + Model.fmt(x.amount, d)).toLowerCase(Locale.ROOT);
                if (hay.contains(t)) want.add(x.id);
            }
            for (Model.Tx x : db.txSearch(f, t)) got.add(x.id);
            if (!got.containsAll(want)) {
                allSame = false;
                System.out.println("      ناقص لـ «" + term + "»: " + want.size() + " مقابل " + got.size());
            }
        }
        check(allSame, "لكل كلمة (اسم، بيان، مبلغ، عملة، اسم مكتب): كل ما كان يجده البحث القديم موجود في المرشّحين");
        long sid = ids.get(5000);
        db.getWritableDatabase().execSQL("UPDATE tx SET person=? WHERE id=?", new Object[]{Crypto.enc("اسم-جديد-تمامًا"), sid});
        Db.TxFilter fa = new Db.TxFilter();
        fa.includeCancelled = true;
        boolean found = false;
        for (Model.Tx x : db.txSearch(fa, "اسم-جديد-تمامًا")) if (x.id == sid) found = true;
        check(found, "تعديل اسم المستفيد (ولو من المزامنة) يحدّث نص البحث تلقائيًا");
        boolean inBackup = false;
        for (String tn : BackupFormat.TABLES) if (tn.equals("tx_search")) inBackup = true;
        check(!inBackup, "نص البحث محلي: لا يدخل النسخة الاحتياطية ولا المزامنة");
        long t0 = System.currentTimeMillis();
        db.txSearch(fa, "محمد");
        long ms = System.currentTimeMillis() - t0;
        check(ms < 1500, "البحث بعد اكتمال النص المحلي: " + ms + " ms");

        System.out.println("== 5) فحص سجل العمليات اليومي من آخر نقطة سليمة");
        AuditChain.Result full1 = db.verifyAudit();
        AuditChain.Result inc1 = db.verifyAuditSince();
        check(full1.intact() && inc1.intact(), "الفحص الكامل والمتتابع سليمان");
        db.audit("TEST_AFTER", "test", 1, "سطر بعد النقطة");
        AuditChain.Result inc2 = db.verifyAuditSince();
        check(inc2.intact() && inc2.ok >= 1 && inc2.ok < 50, "الفحص المتتابع قرأ الأسطر الجديدة فقط (" + inc2.ok + ")");
        long midId;
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT id FROM audit_logs WHERE row_hash IS NOT NULL ORDER BY id LIMIT 1 OFFSET 20", null)) {
            c.moveToFirst();
            midId = c.getLong(0);
        }
        db.getWritableDatabase().execSQL("UPDATE audit_logs SET reason=? WHERE id=?", new Object[]{Crypto.enc("عُدّل"), midId});
        check(!db.verifyAudit().intact(), "تعديل سطر قديم: يكشفه الفحص الكامل (من «الفحص»)");
        db.getWritableDatabase().execSQL("DELETE FROM settings WHERE k='sync_audit_ok'");
        check(!db.verifyAuditSince().intact(), "وبلا نقطة سابقة يفحص المتتابع السجل كله فيكشفه أيضًا");

        System.out.println("== 6) ربط الرخصة بالمكتب، وسجل المحاولات المختوم");
        License.requestText(ctx, db, "ORDER-127-FIRST");   // مفتاح الجهاز يُنشأ مع أول طلب (كما في الواقع: لا رمز بلا طلب)
        String mine = LicenseGuard.officeUid(db);
        check(mine.matches("OF-[0-9A-F]{18}") && mine.equals(LicenseGuard.officeUid(db)), "هوية المكتب تُنشأ مرة واحدة وتبقى");
        check(mine.equals(B.setting("office_uid", "")), "الهوية تصل للجهاز المنضم (من اللقطة)");
        Map<String, String> gf = new HashMap<>();
        gf.put("ft", "all");
        gf.put("lid", "SA-TEST-127");
        gf.put("uu", "29991231");
        gf.put("of", mine);
        Entitlements e1 = LicenseGuard.decide(ctx, db, grant(gf));
        check(e1.pro, "رخصة لهذا المكتب: تعمل");
        gf.put("of", "OF-AAAAAAAAAAAAAAAAAA");
        Entitlements e2 = LicenseGuard.decide(ctx, db, grant(gf));
        check(!e2.pro && e2.note.contains("مكتب آخر"), "رخصة مكتب آخر على هذه البيانات: حدود المجاني فورًا، برسالة واضحة");
        File mf = new File(ctx.getFilesDir(), "license-misuse.json");
        JSONObject mis = new JSONObject(new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8));
        check(mis.getJSONArray("log").length() == 1 && !mis.optString("sig").isEmpty(), "المحاولة سُجّلت مختومة بمفتاح الجهاز");
        LicenseGuard.decide(ctx, db, grant(gf));
        check(new JSONObject(new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8)).getJSONArray("log").length() == 1, "المحاولة نفسها في اليوم نفسه تُسجَّل مرة واحدة");
        String reqText = License.requestText(ctx, db, "ORDER-127");
        String body = reqText.startsWith("SA8-REQZ:") ? new String(inflate(java.util.Base64.getUrlDecoder().decode(reqText.substring(9))), StandardCharsets.UTF_8)
                : new String(java.util.Base64.getUrlDecoder().decode(reqText.substring(8)), StandardCharsets.UTF_8);
        JSONObject rq = new JSONObject(body);
        check(mine.equals(rq.optString("of")) && rq.optJSONArray("mis") != null && rq.getJSONArray("mis").length() == 1 && !rq.optString("misSig").isEmpty(),
                "طلب التفعيل يحمل هوية المكتب وسجل المحاولات وختمه");
        java.security.Signature sv = java.security.Signature.getInstance("SHA256withECDSA");
        sv.initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(rq.getString("pub")))));
        String mb = (String) call(LicenseGuard.class, "misuseBody", new Class<?>[]{JSONArray.class}, rq.getJSONArray("mis"));
        sv.update(mb.getBytes(StandardCharsets.UTF_8));
        check(sv.verify(java.util.Base64.getUrlDecoder().decode(rq.getString("misSig"))), "الختم يتحقق بالمفتاح العام في الطلب (كما تفعل أداة البائع)");
        if (data != null) {
            Files.write(new File(data, "request-127.txt").toPath(), reqText.getBytes(StandardCharsets.UTF_8));
            System.out.println("      كُتب tests/data/request-127.txt (طلب اصطناعي لاختبار أداة البائع)");
        }
        gf.remove("of");
        check(LicenseGuard.decide(ctx, db, grant(gf)).pro, "رخصة قديمة بلا هوية مكتب: تعمل كما كانت");
        // خزنة أقدم من 12.7: الجهاز المنضم لا يُنشئ هوية بنفسه؛ الرئيسي يُنشئها في المزامنة فتصل للكل
        // كأنها قبل 12.7: لا هوية على الجهازين (حذف لا يُزامَن)
        for (Db x : new Db[]{db, B}) {
            x.getWritableDatabase().execSQL("UPDATE sync_flag SET cap=0 WHERE id=1");
            x.getWritableDatabase().execSQL("DELETE FROM settings WHERE k='office_uid'");
            x.getWritableDatabase().execSQL("UPDATE sync_flag SET cap=1 WHERE id=1");
        }
        check(LicenseGuard.officeUid(B).isEmpty(), "الجهاز المنضم لا يُنشئ هوية مكتب بنفسه");
        Map<String, String> gB = new HashMap<>(gf);
        gB.put("of", "OF-0123456789ABCDEF01");
        Entitlements eb = LicenseGuard.decide(ctx, B, grant(gB));
        check(!eb.pro && eb.note.contains("لم تصل"), "وقبل وصولها: رسالة «اضغط مزامنة الآن» بلا تسجيل محاولة غش");
        new SyncEngine(db, new FileSyncFolder(shared)).sync();
        String uA = LicenseGuard.currentUid(db);
        new SyncEngine(B, new FileSyncFolder(shared)).sync();
        System.out.println("      A=" + uA + " B=" + LicenseGuard.currentUid(B));
        check(!uA.isEmpty() && uA.equals(LicenseGuard.currentUid(B)), "الرئيسي أنشأ الهوية في المزامنة ووصلت للمنضم");

        System.out.println("== 7) تأكيد الوقت من الإنترنت (خادم وهمي محلي)");
        final long[] fake = {Clock.now() + 3 * 86_400_000L};
        // خادم وهمي محلي بسيط (رأس Date كما نريده بالضبط)
        final java.net.ServerSocket ss = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        Thread srv = new Thread(() -> {
            while (!ss.isClosed()) {
                try (java.net.Socket so = ss.accept()) {
                    java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(so.getInputStream(), StandardCharsets.US_ASCII));
                    String ln;
                    while ((ln = rd.readLine()) != null && !ln.isEmpty()) { /* الرأس */ }
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
                    f.setTimeZone(TimeZone.getTimeZone("GMT"));
                    so.getOutputStream().write(("HTTP/1.1 200 OK\r\nDate: " + f.format(new Date(fake[0])) + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    so.getOutputStream().flush();
                } catch (Exception e) {
                    // إغلاق
                }
            }
        });
        srv.setDaemon(true);
        srv.start();
        String base = "http://127.0.0.1:" + ss.getLocalPort() + "/";
        setStatic(LicenseGuard.class, "testSources", new String[]{"http://127.0.0.1:1/", base});
        Map<String, String> tf = new HashMap<>(gf);
        tf.put("tc", "14");
        tf.put("tg", "14");
        db.getWritableDatabase().execSQL("DELETE FROM settings WHERE k IN ('license_time','license_time_try')");
        Entitlements t1 = LicenseGuard.decide(ctx, db, grant(tf));
        check(!t1.pro && LicenseGuard.timeState == 2, "رخصة تطلب تأكيد الوقت ولم يُؤكَّد أبدًا: حدود المجاني حتى أول اتصال");
        long got = LicenseGuard.fetchTime(db);
        check(Math.abs(got - fake[0]) < 2000, "الوقت جاء من المصدر الذي يعمل (الأول معطّل؛ يكفي مصدر واحد)");
        check(LicenseGuard.decide(ctx, db, grant(tf)).pro && LicenseGuard.timeState == 0, "بعد التأكيد: الرخصة تعمل");
        check(LicenseGuard.trustedNow(db) >= fake[0] - 1000, "الوقت الموثوق أكبر من ساعة الجهاز المتأخرة (تأخير الساعة لا يفيد)");
        long y = Long.parseLong(new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(fake[0])));
        check(db.licenseDay() >= y, "اليوم الموثوق صار حدًّا أدنى لا ترجع عنه الرخصة");
        long now = Clock.now();
        db.setSettingQuiet("license_time", (now - 20 * 86_400_000L) + "|" + (android.os.SystemClock.elapsedRealtime() + 999_999_999L) + "|x");
        Entitlements t3 = LicenseGuard.decide(ctx, db, grant(tf));
        check(t3.pro && LicenseGuard.timeState == 1 && LicenseGuard.timeNote.contains("بقي"), "مضى 20 يومًا (أكثر من 14): تنبيه ومهلة، والرخصة ما زالت تعمل");
        db.setSettingQuiet("license_time", (now - 30 * 86_400_000L) + "|" + (android.os.SystemClock.elapsedRealtime() + 999_999_999L) + "|x");
        Entitlements t4 = LicenseGuard.decide(ctx, db, grant(tf));
        check(!t4.pro && LicenseGuard.timeState == 2, "مضى 30 يومًا (أكثر من 14 + 14): حدود المجاني دون مسّ البيانات");
        fake[0] = now - 40 * 86_400_000L;
        db.setSettingQuiet("license_time", now + "|" + android.os.SystemClock.elapsedRealtime() + "|x");
        LicenseGuard.fetchTime(db);
        check(LicenseGuard.lastTrusted(db) >= now - 5000, "مصدر يعطي وقتًا أقدم من آخر وقت موثوق يُرفض");
        Map<String, String> nf = new HashMap<>(gf);
        check(LicenseGuard.decide(ctx, db, grant(nf)).pro && !LicenseGuard.timeDue(db, grant(nf)), "رخصة بلا مدة تأكيد: لا اتصال ولا قيد");
        ss.close();
        setStatic(LicenseGuard.class, "testSources", null);

        System.out.println("== 8) الحركات لكل مستخدم، ومزوّدات القراءة الذكية السحابية");
        Map<String, Long> cnt = db.txCountsByUser(0);
        long sum = 0;
        for (long v : cnt.values()) sum += v;
        check(sum == 6000, "مجموع حركات المستخدمين = كل الحركات (" + sum + ")");
        AiExtract.Config c1 = AiPrefs.config(db);
        check(c1.claude() && c1.endpoint().equals(AiExtract.CLAUDE_URL), "الافتراضي Claude كما كان");
        db.setSetting("ai_provider", AiExtract.GEMINI);
        AiExtract.Config c2 = AiPrefs.config(db);
        check(!c2.claude() && c2.endpoint().equals(AiExtract.GEMINI_URL + "/chat/completions"), "قالب Gemini بعنوانه الجاهز (صيغة OpenAI)");
        db.setSetting("ai_provider", AiExtract.CUSTOM);
        db.setSettingQuiet("ai_key", Crypto.enc("k"));
        db.setSetting("ai_model", "any-model");
        AiExtract.Config c3 = AiPrefs.config(db);
        check(c3.problem() != null && c3.problem().contains("عنوان"), "«مخصّص» بلا عنوان: يُطلب العنوان");
        db.setSetting("ai_url", "https://ai.example.com/v1/");
        check(AiPrefs.config(db).problem() == null && AiPrefs.config(db).endpoint().equals("https://ai.example.com/v1/chat/completions"), "«مخصّص» بعنوان أي خدمة متوافقة");
        db.setSetting("ai_provider", AiExtract.OPENAI);
        db.setSetting("ai_url", "");
        check(AiPrefs.config(db).endpoint().equals(AiExtract.OPENAI_URL + "/chat/completions"), "قالب OpenAI");
        db.setSetting("ai_provider", "openai-old-or-unknown");
        check(AiPrefs.config(db).claude(), "قيمة غير معروفة: تعود لـ Claude بأمان");

        System.out.println(fails == 0 ? "نجحت اختبارات 12.7 كلها (" + passes + ")" : "فشل " + fails + " من " + (passes + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void setStatic(Class<?> k, String f, Object v) throws Exception {
        java.lang.reflect.Field x = k.getDeclaredField(f);
        x.setAccessible(true);
        x.set(null, v);
    }

    static byte[] inflate(byte[] z) throws Exception {
        java.util.zip.Inflater in = new java.util.zip.Inflater(true);
        in.setInput(z);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        while (!in.finished()) {
            int n = in.inflate(b);
            if (n == 0 && (in.needsInput() || in.needsDictionary())) break;
            o.write(b, 0, n);
        }
        return o.toByteArray();
    }
}
