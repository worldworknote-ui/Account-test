import com.smart.hawala.*;
import java.util.*;
public class SecurityTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  public static void main(String[] a) {
    System.out.println("1) recovery code");
    String code = Pin.newRecoveryCode();
    check(code.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"), "format XXXX-XXXX-XXXX-XXXX: " + code);
    check(!code.matches(".*[01OIL].*"), "no ambiguous characters");
    Set<String> seen = new HashSet<>(); for (int i = 0; i < 2000; i++) seen.add(Pin.newRecoveryCode());
    check(seen.size() == 2000, "2000 codes all different");
    String salt = Pin.newSalt(); String h = Pin.hash(Pin.normalizeRecovery(code), salt);
    check(Pin.verify(Pin.normalizeRecovery(code.toLowerCase().replace("-", " ")), salt, h), "lowercase with spaces still verifies");
    String wrong = code.substring(0, 18) + (code.charAt(18) == 'A' ? 'B' : 'A');
    check(!Pin.verify(Pin.normalizeRecovery(wrong), salt, h), "one wrong character rejected");
    check(Pin.normalizeRecovery("").isEmpty() && Pin.normalizeRecovery(null).isEmpty(), "empty input normalizes to empty");
    System.out.println("2) admin PIN rules");
    check(!Pin.validAdmin("1234") && !Pin.validAdmin("12345") && Pin.validAdmin("123456") && Pin.validAdmin("12345678") && !Pin.validAdmin("123456789"), "admin PIN must be 6-8 digits");
    check(Pin.valid("1234") && !Pin.valid("123"), "staff PIN 4-8 digits");
    System.out.println("3) lockout backoff");
    check(Pin.lockoutMillis(4) == 0, "no wait before 5 failures");
    check(Pin.lockoutMillis(5) == 30000, "5th failure: 30 s");
    check(Pin.lockoutMillis(6) == 60000 && Pin.lockoutMillis(7) == 120000, "doubles: 1 min, 2 min");
    check(Pin.lockoutMillis(50) == 3600000, "capped at 1 hour");
    long total = 0; for (int f = 1; f <= 30; f++) total += Pin.lockoutMillis(f);
    check(total > 20L * 3600000, "30 guesses take over 20 hours: " + total / 3600000 + " h");
    System.out.println("4) roles");
    check(Auth.can(Auth.ADMIN, Auth.Perm.DELETE) && !Auth.can(Auth.ACCOUNTANT, Auth.Perm.DELETE) && !Auth.can(Auth.EMPLOYEE, Auth.Perm.DELETE) && !Auth.can(Auth.VIEWER, Auth.Perm.DELETE), "only admin can hard-delete");
    check(!Auth.can(Auth.ACCOUNTANT, Auth.Perm.USERS) && !Auth.can(Auth.ACCOUNTANT, Auth.Perm.RESTORE), "accountant cannot manage users or restore");
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
  }
}
