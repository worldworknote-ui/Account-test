import com.smart.hawala.*;
import java.nio.charset.StandardCharsets;

/** 10.0: تشفير المزامنة والساعة المنطقية (بلا قاعدة بيانات). */
public class SyncCryptoTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    public static void main(String[] a) throws Exception {
        byte[] vk = SyncCrypto.random(32);
        SyncCrypto.Keys k = new SyncCrypto.Keys("v123", vk);
        byte[] msg = "{\"ops\":[1,2,3],\"name\":\"النور\"}".getBytes(StandardCharsets.UTF_8);
        String name = k.fileName("p-", "dev:1");
        byte[] sealed = k.sealFile("p", name, msg);
        check(sealed.length % 4096 == 0, "الحجم مضاعف لـ 4KB: " + sealed.length);
        check(new String(k.openFile("p", name, sealed), StandardCharsets.UTF_8).contains("النور"), "يُفك بالمفتاح نفسه");
        boolean tamper = false;
        byte[] bad = sealed.clone();
        bad[100] ^= 1;
        try {
            k.openFile("p", name, bad);
        } catch (Exception e) {
            tamper = true;
        }
        check(tamper, "تغيير بايت واحد يُرفض");
        boolean moved = false;
        try {
            k.openFile("p", k.fileName("p-", "dev:2"), sealed);
        } catch (Exception e) {
            moved = true;
        }
        check(moved, "نقل الملف إلى اسم آخر (إعادة ترتيب) يُرفض");
        boolean other = false;
        try {
            new SyncCrypto.Keys("v123", SyncCrypto.random(32)).openFile("p", name, sealed);
        } catch (Exception e) {
            other = true;
        }
        check(other, "مفتاح خزنة آخر لا يفك");
        check(!java.util.Arrays.equals(k.sealFile("p", name, msg), sealed), "كل تشفير برقم عشوائي جديد (لا يتكرر النص المشفّر)");
        check(name.matches("p-[0-9a-f]{32}\\.sab") && name.equals(k.fileName("p-", "dev:1")), "اسم معتم ثابت");
        byte[] kek = SyncCrypto.pbkdf2("كلمة سر".toCharArray(), SyncCrypto.random(16), 1000);
        String w = SyncCrypto.wrap(kek, vk, "v123");
        check(java.util.Arrays.equals(SyncCrypto.unwrap(kek, w, "v123"), vk), "تغليف مفتاح الخزنة وفكه");
        String rc = SyncCrypto.newRecoveryCode();
        check(rc.matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){5}"), "رمز الاسترداد: " + rc);
        check(SyncCrypto.normalizeRecovery(rc.toLowerCase().replace("-", " ")).equals(SyncCrypto.normalizeRecovery(rc)), "رمز الاسترداد بلا حساسية للحالة والفواصل");
        Hlc h = new Hlc("aa");
        String t1 = h.now(1000), t2 = h.now(1000), t3 = h.now(900);
        check(t1.compareTo(t2) < 0 && t2.compareTo(t3) < 0, "الساعة لا تتراجع حتى لو رجع وقت الجهاز");
        h.observe(Hlc.format(5000, 3, "bb"), 1000);
        check(h.now(1000).compareTo(Hlc.format(5000, 3, "bb")) > 0, "تتقدم فوق ساعة جهاز آخر");
        System.out.println("\n" + passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }
}
