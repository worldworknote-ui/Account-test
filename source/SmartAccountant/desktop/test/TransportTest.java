import com.smart.hawala.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 10.2: نواقل المزامنة الجديدة على Db الحقيقي:
 * 1) خادم المزامنة الخاص (sync-server/server.js الحقيقي على Node): إنشاء، انضمام برابط، كتابة في الاتجاهين، رفض الرمز الخاطئ ورمز الخادم.
 * 2) Google Drive مباشرة على خادم يحاكي واجهة Drive v3 (القائمة، التنزيل، الرفع بطلب واحد والمستأنف، الحذف، رمز منتهي 401).
 * 3) نقل خزنة قائمة من المجلد المشترك إلى الخادم ثم إلى Drive دون فقد شيء.
 */
public class TransportTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    // ---------------------------------------------------------------- محاكي Google Drive
    static final class FakeDrive {
        final Map<String, Object[]> files = new LinkedHashMap<>(); // id -> {name, bytes, modified}
        final Map<String, Object[]> sessions = new HashMap<>();
        int seq, tokenGen = 1, expireAfter = -1, calls, uploads, resumables;
        HttpServer srv;

        String valid() { return "tok-" + tokenGen; }

        int start() throws Exception {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/", this::handle);
            srv.start();
            return srv.getAddress().getPort();
        }

        static byte[] body(HttpExchange x) throws java.io.IOException {
            try (InputStream in = x.getRequestBody()) {
                return in.readAllBytes();
            }
        }

        static void send(HttpExchange x, int code, byte[] b, String type) throws java.io.IOException {
            x.getResponseHeaders().set("Content-Type", type);
            x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
            x.close();
        }

        static void json(HttpExchange x, int code, Object o) throws java.io.IOException { send(x, code, o.toString().getBytes(StandardCharsets.UTF_8), "application/json"); }

        synchronized void handle(HttpExchange x) throws java.io.IOException {
            calls++;
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (expireAfter == 0) {
                tokenGen++;
                expireAfter = -1;
            } else if (expireAfter > 0) expireAfter--;
            if (auth == null || !auth.equals("Bearer " + valid())) {
                json(x, 401, new JSONObject().put("error", new JSONObject().put("code", 401).put("message", "Invalid Credentials")));
                return;
            }
            String path = x.getRequestURI().getPath(), q = x.getRequestURI().getRawQuery() == null ? "" : x.getRequestURI().getRawQuery();
            String m = x.getRequestMethod();
            if (m.equals("GET") && path.equals("/drive/v3/files")) {
                if (!q.contains("spaces=appDataFolder")) {
                    json(x, 403, new JSONObject().put("error", new JSONObject().put("message", "space")));
                    return;
                }
                JSONArray a = new JSONArray();
                for (Map.Entry<String, Object[]> e : files.entrySet())
                    a.put(new JSONObject().put("id", e.getKey()).put("name", e.getValue()[0]).put("modifiedTime", e.getValue()[2]));
                json(x, 200, new JSONObject().put("files", a));
                return;
            }
            if (m.equals("GET") && path.startsWith("/drive/v3/files/") && q.contains("alt=media")) {
                Object[] f = files.get(path.substring("/drive/v3/files/".length()));
                if (f == null) json(x, 404, new JSONObject().put("error", new JSONObject().put("message", "nf")));
                else send(x, 200, (byte[]) f[1], "application/octet-stream");
                return;
            }
            if (m.equals("DELETE") && path.startsWith("/drive/v3/files/")) {
                files.remove(path.substring("/drive/v3/files/".length()));
                send(x, 204, new byte[0], "text/plain");
                return;
            }
            if (m.equals("POST") && path.equals("/upload/drive/v3/files") && q.contains("uploadType=multipart")) {
                uploads++;
                String ct = x.getRequestHeaders().getFirst("Content-Type");
                String boundary = ct.substring(ct.indexOf("boundary=") + 9);
                byte[] b = body(x);
                String all = new String(b, StandardCharsets.ISO_8859_1);
                String sep = "--" + boundary;
                int p1 = all.indexOf(sep), h1 = all.indexOf("\r\n\r\n", p1) + 4, p2 = all.indexOf("\r\n" + sep, h1);
                JSONObject meta = new JSONObject(new String(Arrays.copyOfRange(b, h1, p2), StandardCharsets.UTF_8));
                int h2 = all.indexOf("\r\n\r\n", p2 + 2) + 4, p3 = all.lastIndexOf("\r\n" + sep + "--");
                byte[] data = Arrays.copyOfRange(b, h2, p3);
                if (!meta.getJSONArray("parents").getString(0).equals("appDataFolder")) throw new IllegalStateException("parent");
                String id = "f" + (++seq);
                files.put(id, new Object[]{meta.getString("name"), data, String.format("2026-10-21T10:%02d:%02d.%03dZ", seq / 60 % 60, seq % 60, seq % 1000)});
                json(x, 200, new JSONObject().put("id", id).put("name", meta.getString("name")).put("modifiedTime", files.get(id)[2]));
                return;
            }
            if (m.equals("POST") && path.equals("/upload/drive/v3/files") && q.contains("uploadType=resumable")) {
                resumables++;
                JSONObject meta = new JSONObject(new String(body(x), StandardCharsets.UTF_8));
                String sid = "s" + (++seq);
                sessions.put(sid, new Object[]{meta.getString("name")});
                x.getResponseHeaders().set("Location", "http://127.0.0.1:" + srv.getAddress().getPort() + "/upload/session/" + sid);
                send(x, 200, new byte[0], "text/plain");
                return;
            }
            if (m.equals("PUT") && path.startsWith("/upload/session/")) {
                Object[] s = sessions.remove(path.substring("/upload/session/".length()));
                byte[] data = body(x);
                String id = "f" + (++seq);
                files.put(id, new Object[]{s[0], data, String.format("2026-10-21T11:%02d:%02d.%03dZ", seq / 60 % 60, seq % 60, seq % 1000)});
                json(x, 200, new JSONObject().put("id", id).put("name", s[0]).put("modifiedTime", files.get(id)[2]));
                return;
            }
            json(x, 400, new JSONObject().put("error", new JSONObject().put("message", "unsupported " + m + " " + path)));
        }
    }

    static String bal(Db db) { return SyncDbTest.bal(db); }

    static void syncAll(SyncEngine... es) throws Exception {
        for (int r = 0; r < 3; r++) for (SyncEngine e : es) e.sync();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("sa.secrets", "file");
        File serverJs = new File(args.length > 0 ? args[0] : "sync-server/server.js");
        Object[] first = DeskCoreTest.device("trA");
        Db A = (Db) first[1];
        License.current = Entitlements.of(DeskCoreTest.fullGrant(), BuildInfo.BUILD_DATE);
        List<String> ue = Arrays.asList("USD", "EUR");
        long nour = A.addOffice("النور", "", "", "", "", "", null, null, ue, new HashMap<>(), Arrays.asList(new Db.Opening("USD", "لنا", 50000)));
        A.addTx(SyncDbTest.tx(nour, true, 100000, "USD", "سامر"), Arrays.asList(new byte[]{1, 2, 3}));

        System.out.println("== 1) خادم المزامنة الخاص (Node الحقيقي)");
        File data = Files.createTempDirectory("sa-sync-srv").toFile();
        int port = 18000 + new Random().nextInt(2000);
        ProcessBuilder pb = new ProcessBuilder("node", serverJs.getAbsolutePath());
        pb.environment().put("PORT", "" + port);
        pb.environment().put("DATA_DIR", data.getAbsolutePath());
        pb.environment().put("SERVER_KEY", "server-key-123");
        pb.environment().put("RATE_PER_MIN", "100000");
        pb.redirectErrorStream(true);
        Process node = pb.start();
        try {
            String base = "http://127.0.0.1:" + port;
            for (int i = 0; i < 50; i++) {
                try {
                    new HttpSyncFolder(base, null, "server-key-123").ping();
                    break;
                } catch (Exception e) {
                    Thread.sleep(100);
                }
            }
            check(new HttpSyncFolder(base, null, "server-key-123").ping().contains("يعمل"), "الخادم يعمل ويقبل رمز الخادم");
            boolean wrongKey = false;
            try {
                new HttpSyncFolder(base, null, "wrong").ping();
            } catch (Exception e) {
                wrongKey = e.getMessage().contains("غير صحيح");
            }
            check(wrongKey, "رمز الخادم الخاطئ يُكتشف عند الفحص");
            HttpSyncFolder fa = new HttpSyncFolder(base, null, "server-key-123");
            SyncEngine eA = new SyncEngine(A, fa);
            eA.create("كلمة-سر-الخادم".toCharArray(), "المحل");
            String vid = SyncEngine.status(A).vaultId;
            String link = HttpSyncFolder.link(base, vid);
            String[] pv = HttpSyncFolder.parseLink(link);
            check(pv[0].equals(base) && pv[1].equals(vid), "رابط الانضمام يحمل العنوان ورمز الخزنة: " + link);
            boolean opaque = true;
            for (File f : new File(data, vid).listFiles()) {
                if (f.getName().startsWith(".") || f.getName().equals(SyncEngine.HEADER)) continue;
                if (new String(Files.readAllBytes(f.toPath()), "ISO-8859-1").contains("USD")) opaque = false;
            }
            check(opaque && new File(data, vid + "/.token").isFile(), "الخادم يحفظ ملفات مشفّرة وبصمة الرمز فقط");
            Db B = SyncDbTest.dev("trB");
            SyncEngine eB = new SyncEngine(B, new HttpSyncFolder(pv[0], pv[1], "server-key-123"));
            eB.join("كلمة-سر-الخادم".toCharArray(), false, "البيت");
            check(bal(A).equals(bal(B)), "الانضمام عبر الخادم بالأرصدة نفسها");
            long t1 = B.addTx(SyncDbTest.tx(nour, false, 7000, "USD", "من البيت"), null);
            A.addTx(SyncDbTest.tx(nour, true, 3000, "USD", "من المحل"), null);
            long t0 = System.currentTimeMillis();
            syncAll(eB, eA);
            check(bal(A).equals(bal(B)) && A.tx(t1) != null, "الكتابة في الاتجاهين عبر الخادم (" + (System.currentTimeMillis() - t0) + " ms لـ 6 جولات)");
            // 10.4: التنظيف على الخادم (DELETE)
            for (int i = 0; i < 160; i++) {
                A.addTx(SyncDbTest.tx(nour, i % 2 == 0, 10 + i, "USD", "ح" + i), null);
                eA.sync();
            }
            syncAll(eB, eA);
            int onServer = new File(data, vid).list((d, n) -> n.startsWith("p-")).length;
            String gcs = eA.clean(true);
            int afterGc = new File(data, vid).list((d, n) -> n.startsWith("p-")).length;
            check(afterGc < onServer - 100 && bal(A).equals(bal(B)), "التنظيف على الخادم حذف الحزم القديمة (" + onServer + " ← " + afterGc + "): " + gcs);
            // دخيل يعرف رمز الخزنة ورمز الخادم لكن لا يعرف كلمة السر: لا يقرأ ولا يكتب
            HttpSyncFolder evil = new HttpSyncFolder(base, vid, "server-key-123");
            boolean denied = false;
            try {
                evil.list("p-");
            } catch (Exception e) {
                denied = true;
            }
            boolean headerOpen = evil.read(SyncEngine.HEADER) != null;
            check(denied && headerOpen, "بلا مفتاح الخزنة: لا قائمة ولا ملفات (الرأس وحده مقروء، وفيه المفتاح مغلّفًا فقط)");
            HttpSyncFolder noKey = new HttpSyncFolder(base, vid, "");
            boolean nk = false;
            try {
                noKey.read(SyncEngine.HEADER);
            } catch (Exception e) {
                nk = e.getMessage().contains("403");
            }
            check(nk, "بلا رمز الخادم يُرفض كل شيء");

            System.out.println("== 2) نقل خزنة قائمة: المجلد المشترك ← الخادم");
            File shared = Files.createTempDirectory("sa-tr-folder").toFile();
            Db X = SyncDbTest.dev("trX");
            long xo = X.addOffice("مكتب س", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
            X.addTx(SyncDbTest.tx(xo, true, 1234, "USD", "أ"), null);
            SyncEngine eX = new SyncEngine(X, new FileSyncFolder(shared));
            eX.create("كلمة-سر-المجلد".toCharArray(), "س");
            Db Y = SyncDbTest.dev("trY");
            SyncEngine eY = new SyncEngine(Y, new FileSyncFolder(shared));
            eY.join("كلمة-سر-المجلد".toCharArray(), false, "ص");
            Y.addTx(SyncDbTest.tx(xo, false, 77, "USD", "ب"), null);
            eY.sync();
            // Y ينقل أولًا (فيه حزمة لم تصل X بعد)، ثم X
            String xv = SyncEngine.status(X).vaultId;
            int ny = new SyncEngine(Y, new FileSyncFolder(shared)).moveTo(new HttpSyncFolder(base, xv, "server-key-123"));
            int nx = new SyncEngine(X, new FileSyncFolder(shared)).moveTo(new HttpSyncFolder(base, xv, "server-key-123"));
            SyncEngine sX = new SyncEngine(X, new HttpSyncFolder(base, xv, "server-key-123")), sY = new SyncEngine(Y, new HttpSyncFolder(base, xv, "server-key-123"));
            syncAll(sX, sY);
            check(ny > 0 && nx == 0 && bal(X).equals(bal(Y)) && SyncEngine.status(X).lastError.isEmpty(), "نُقلت الخزنة (" + ny + " ملفًا) وأكمل كل جهاز من حيث كان: " + bal(X));

            System.out.println("== 2ب) 10.3: مكان مزامنة فارغ (نُقل المجلد دون ملفاته): الإصلاح");
            File empty = Files.createTempDirectory("sa-tr-empty").toFile();
            X.addTx(SyncDbTest.tx(xo, true, 500, "USD", "بعد النقل"), null);
            SyncEngine xe = new SyncEngine(X, new FileSyncFolder(empty));
            boolean missing = false;
            try {
                xe.sync();
            } catch (Exception e) {
                missing = SyncEngine.headerMissing(X);
            }
            check(missing, "الجهاز يكتشف أن المكان الجديد بلا ملفات (ولا يكتب فيه شيئًا من تلقاء نفسه)");
            String rc0 = xe.repair(null);
            Y.addTx(SyncDbTest.tx(xo, false, 33, "USD", "من ص"), null);
            new SyncEngine(Y, new HttpSyncFolder(base, xv, "server-key-123")).moveTo(new FileSyncFolder(empty));
            SyncEngine ye = new SyncEngine(Y, new FileSyncFolder(empty));
            syncAll(xe, ye);
            check(rc0.isEmpty() && !SyncEngine.headerMissing(X) && bal(X).equals(bal(Y)), "الإصلاح من النسخة المحلية للرأس، ثم الجهاز الآخر انتقل للمكان نفسه وتطابقا: " + bal(X));
            File empty2 = Files.createTempDirectory("sa-tr-empty2").toFile();
            X.getWritableDatabase().execSQL("DELETE FROM sync_meta WHERE k='header_copy'");
            SyncEngine xe2 = new SyncEngine(X, new FileSyncFolder(empty2));
            String rc1 = xe2.repair("كلمة-سر-جديدة".toCharArray());
            Db Z = SyncDbTest.dev("trZ");
            new SyncEngine(Z, new FileSyncFolder(empty2)).join("كلمة-سر-جديدة".toCharArray(), false, "ع");
            check(!rc1.isEmpty() && bal(Z).equals(bal(X)), "بلا نسخة محلية: إصلاح بكلمة سر جديدة ورمز استرداد جديد، وجهاز جديد ينضم بها");

            System.out.println("== 3) Google Drive مباشرة (محاكي واجهة Drive v3)");
            FakeDrive fd = new FakeDrive();
            int dport = fd.start();
            java.lang.reflect.Field api = DriveSyncFolder.class.getDeclaredField("api");
            api.setAccessible(true);
            api.set(null, "http://127.0.0.1:" + dport);
            final int[] refreshes = {0};
            DriveSyncFolder.Tokens toks = refresh -> {
                if (refresh) refreshes[0]++;
                return fd.valid();
            };
            Db D1 = SyncDbTest.dev("trD1");
            long d1o = D1.addOffice("مكتب د", "", "", "", "", "", null, null, ue, new HashMap<>(), new ArrayList<>());
            byte[] big = new byte[5 * 1024 * 1024];
            new Random(7).nextBytes(big);
            for (int i = 0; i < 30; i++) D1.addTx(SyncDbTest.tx(d1o, i % 2 == 0, 1000 + i, "USD", "حركة " + i), i == 0 ? Arrays.asList(big) : null);
            SyncEngine e1 = new SyncEngine(D1, new DriveSyncFolder(toks));
            e1.create("كلمة-سر-درايف".toCharArray(), "هاتف 1");
            check(fd.resumables >= 1, "اللقطة الكبيرة (صورة 5MB) رُفعت بالرفع المستأنف");
            Db D2 = SyncDbTest.dev("trD2");
            SyncEngine e2 = new SyncEngine(D2, new DriveSyncFolder(toks));
            e2.join("كلمة-سر-درايف".toCharArray(), false, "هاتف 2");
            check(bal(D1).equals(bal(D2)), "الانضمام عبر Drive بالأرصدة نفسها");
            D2.addTx(SyncDbTest.tx(d1o, true, 999, "USD", "من هاتف 2"), null);
            fd.expireAfter = 2;
            syncAll(new SyncEngine(D2, new DriveSyncFolder(toks)), new SyncEngine(D1, new DriveSyncFolder(toks)));
            check(bal(D1).equals(bal(D2)) && refreshes[0] > 0, "رمز منتهي (401) يُجدَّد تلقائيًا وتكمل المزامنة (" + refreshes[0] + " تجديد)");
            Set<String> names = new HashSet<>();
            int dupMembers = 0;
            for (Object[] f : fd.files.values()) if (!names.add((String) f[0])) dupMembers++;
            check(dupMembers == 0, "استبدال ملف العضو لا يترك نسخًا مكررة في Drive");
            // 10.4: الصورة الكبيرة ملف مستقل، والتنظيف في Drive
            int bigImages = 0;
            for (Object[] f : fd.files.values()) if (((String) f[0]).startsWith("a-") && ((byte[]) f[1]).length > 5_000_000) bigImages++;
            check(bigImages == 1, "الصورة (5MB) ملف واحد مستقل في Drive، لا داخل اللقطة");
            SyncEngine g1 = new SyncEngine(D1, new DriveSyncFolder(toks)), g2 = new SyncEngine(D2, new DriveSyncFolder(toks));
            for (int i = 0; i < 155; i++) {
                D1.addTx(SyncDbTest.tx(d1o, i % 2 == 0, 5 + i, "USD", "د" + i), null);
                g1.sync();
            }
            syncAll(g2, g1);
            int pBefore = 0, pAfter = 0;
            for (Object[] f : fd.files.values()) if (((String) f[0]).startsWith("p-")) pBefore++;
            g1.clean(true);
            for (Object[] f : fd.files.values()) if (((String) f[0]).startsWith("p-")) pAfter++;
            check(pAfter < pBefore - 100 && bal(D1).equals(bal(D2)), "التنظيف في Drive حذف الحزم القديمة (" + pBefore + " ← " + pAfter + ")");
            fd.srv.stop(0);
        } finally {
            node.destroy();
        }
        System.out.println("\n" + passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }
}
