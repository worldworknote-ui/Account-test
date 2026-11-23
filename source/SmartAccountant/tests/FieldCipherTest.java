import com.smart.hawala.*;
import java.util.Base64;
import javax.crypto.SecretKey;
/** 7.2: تشفير الحقول بمفتاح البيانات (السريع) والتمييز عن الصيغة القديمة. */
public class FieldCipherTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  public static void main(String[] a) throws Exception {
    SecretKey k = FieldCipher.newKey();
    String[] samples = {"", "سامر علي", "حوالة 500 دولار\nالشام", "1410100092 الخال", "😀 emoji", new String(new char[5000]).replace('\0', 'x')};
    for (String s : samples) {
      String e = FieldCipher.encrypt(k, s);
      byte[] raw = Base64.getDecoder().decode(e);
      check(FieldCipher.isV2(raw) && FieldCipher.isV2(e) && FieldCipher.decrypt(k, raw).equals(s), "round trip (" + Math.min(s.length(), 20) + " chars)");
    }
    String e1 = FieldCipher.encrypt(k, "نفس النص"), e2 = FieldCipher.encrypt(k, "نفس النص");
    check(!e1.equals(e2), "random IV: same text encrypts differently");
    // الصيغة القديمة تبدأ بطول IV = 12 ولا تُعامل كجديدة
    byte[] legacy = new byte[1 + 12 + 30];
    legacy[0] = 12;
    check(!FieldCipher.isV2(legacy), "legacy layout (first byte 12) is not v2");
    check(!FieldCipher.isV2("نص عادي") && !FieldCipher.isV2(""), "plain text / empty is not v2");
    // العبث بالنص المشفّر يُكتشف
    byte[] raw = Base64.getDecoder().decode(FieldCipher.encrypt(k, "500 USD"));
    raw[raw.length - 1] ^= 1;
    boolean caught = false;
    try { FieldCipher.decrypt(k, raw); } catch (Exception ex) { caught = true; }
    check(caught, "tampering detected (GCM tag)");
    // مفتاح آخر لا يفك
    caught = false;
    try { FieldCipher.decrypt(FieldCipher.newKey(), Base64.getDecoder().decode(e1)); } catch (Exception ex) { caught = true; }
    check(caught, "another key cannot decrypt");
    // المفتاح المحفوظ (32 بايت) يعاد بناؤه
    SecretKey k2 = FieldCipher.key(k.getEncoded());
    check(FieldCipher.decrypt(k2, Base64.getDecoder().decode(e1)).equals("نفس النص"), "key rebuilt from saved bytes");
    // السرعة: 20,000 تشفير وفك (كشف 5000 حركة × 4 حقول)
    long t0 = System.nanoTime();
    for (int i = 0; i < 20000; i++) FieldCipher.decrypt(k, Base64.getDecoder().decode(FieldCipher.encrypt(k, "حوالة رقم " + i)));
    long ms = (System.nanoTime() - t0) / 1000000;
    check(ms < 5000, "20,000 encrypt+decrypt in " + ms + " ms");
    System.out.println(fails == 0 ? "ALL FIELD CIPHER TESTS PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
