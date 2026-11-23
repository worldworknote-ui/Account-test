package com.smart.hawala;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34}, application = TestApp.class)
public class SyncAndroidTest {

    /* JADX INFO: loaded from: SyncAndroidTest$Dev.class */
    static final class Dev extends ContextWrapper {
        final File dir;

        Dev(Context base, File dir) {
            super(base);
            this.dir = dir;
            dir.mkdirs();
        }

        @Override // android.content.ContextWrapper, android.content.Context
        public File getDatabasePath(String name) {
            return new File(this.dir, name);
        }
    }

    static Map<String, String> book(Db db) {
        Map<String, String> m = new TreeMap<>();
        for (Model.Office o : db.offices()) {
            m.put(o.name, db.balances(o.id).toString());
        }
        return m;
    }

    static long office(Db db, String name) {
        for (Model.Office o : db.offices()) {
            if (o.name.equals(name)) {
                return o.id;
            }
        }
        return -1L;
    }

    @Test
    public void twoPhones() throws Exception {
        Map<String, String> gf = new HashMap<>();
        gf.put("ft", "all");
        gf.put("lid", "TEST");
        gf.put("uu", "29991231");
        License.current = Entitlements.of(new LicenseToken.Grant(gf), 20261021);
        Context app = RuntimeEnvironment.getApplication();
        File root = new File(System.getProperty("java.io.tmpdir"), "sa-sync-android-" + System.nanoTime());
        FileSyncFolder folder = new FileSyncFolder(new File(root, "shared"));
        new File(root, "shared").mkdirs();
        Db a = new Db(new Dev(app, new File(root, "A")));
        a.getWritableDatabase();
        long nour = a.addOffice("النور", "", "", "", "", "", (Long) null, (Long) null, Arrays.asList("USD", "EUR"), new HashMap(), new ArrayList());
        a.addTx(ShotTest.tx(nour, true, 150000L, "USD", "محمد", "", System.currentTimeMillis()), (List) null);
        SyncEngine ea = new SyncEngine(a, folder);
        ea.create("Sync-Pass-2026".toCharArray(), "A");
        Db b = new Db(new Dev(app, new File(root, "B")));
        b.getWritableDatabase();
        SyncEngine eb = new SyncEngine(b, folder);
        eb.join("Sync-Pass-2026".toCharArray(), false, "B");
        Assert.assertEquals(book(a), book(b));
        long amal = a.addOffice("الأمل", "", "", "", "", "", (Long) null, (Long) null, Arrays.asList("USD"), new HashMap(), new ArrayList());
        a.addTx(ShotTest.tx(amal, false, 70000L, "USD", "سامر", "", System.currentTimeMillis()), (List) null);
        a.addTx(ShotTest.tx(nour, false, 20000L, "USD", "رنا", "", System.currentTimeMillis()), (List) null);
        SyncEngine.Result ra = ea.sync();
        SyncEngine.Result rb = eb.sync();
        System.out.println("A: " + ra.summary() + " | B: " + rb.summary() + " errors=" + rb.errors);
        Assert.assertTrue("B received", rb.received > 0);
        Assert.assertTrue("B has new office", office(b, "الأمل") > 0);
        Assert.assertEquals(book(a), book(b));
        b.addTx(ShotTest.tx(office(b, "الأمل"), true, 5000L, "USD", "خالد", "", System.currentTimeMillis()), (List) null);
        eb.sync();
        SyncEngine.Result ra2 = ea.sync();
        System.out.println("A2: " + ra2.summary() + " errors=" + ra2.errors);
        Assert.assertEquals(book(a), book(b));
        System.out.println("BOOK " + book(a));
        // 10.4: صورة الحركة تصل ملفًا مستقلًا بالبايتات نفسها
        byte[] img = new byte[120000];
        new java.util.Random(3).nextBytes(img);
        long ti = a.addTx(ShotTest.tx(nour, true, 3000L, "USD", "مع صورة", "", System.currentTimeMillis()), Arrays.asList(img));
        ea.sync();
        eb.sync();
        Assert.assertArrayEquals(img, b.attachment(b.attachmentIds(ti).get(0)));
        Assert.assertEquals(1, eb.usage().images);
        long admin = a.addUser("المدير", "ADMIN", (String) null, "123456");
        SmartApp.currentUser = a.user(admin);
        long samer = a.addUser("سامر", "CUSTOM", "VIEW,TX_ADD", "1234");
        SmartApp.currentUser = null;
        ea.sync();
        eb.sync();
        Assert.assertTrue("users on B", b.usersEnabled() && b.users(true).size() == 2);
        Assert.assertTrue("pin works on B", b.verifyPin(samer, "1234"));
        SmartApp.currentUser = b.user(samer);
        Assert.assertTrue(b.allowed(Auth.Perm.TX_ADD) && !b.allowed(Auth.Perm.DELETE));
        SmartApp.currentUser = null;
        System.out.println("PEERS " + SyncEngine.status(b).peers + " LOG " + SyncEngine.history(b).size());
        Assert.assertEquals("", SyncEngine.status(a).lastError);
        Assert.assertEquals("", SyncEngine.status(b).lastError);
    }
}
