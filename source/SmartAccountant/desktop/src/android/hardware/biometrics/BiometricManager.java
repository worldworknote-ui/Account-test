package android.hardware.biometrics;
public class BiometricManager {
    public static final int BIOMETRIC_SUCCESS = 0, BIOMETRIC_ERROR_NONE_ENROLLED = 11, BIOMETRIC_ERROR_NO_HARDWARE = 12, BIOMETRIC_ERROR_HW_UNAVAILABLE = 1;
    public static class Authenticators { public static final int BIOMETRIC_STRONG = 15, BIOMETRIC_WEAK = 255, DEVICE_CREDENTIAL = 32768; }
    // 11.2: Touch ID/كلمة سر الماك، أو Windows Hello
    public int canAuthenticate() { return SystemAuth.available() ? BIOMETRIC_SUCCESS : BIOMETRIC_ERROR_NONE_ENROLLED; }
    public int canAuthenticate(int a) { return canAuthenticate(); }
}
