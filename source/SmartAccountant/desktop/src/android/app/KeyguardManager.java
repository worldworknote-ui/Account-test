package android.app;
public class KeyguardManager {
    // 11.2: قفل الكمبيوتر (Touch ID/كلمة سر الماك، أو Windows Hello)
    public boolean isKeyguardSecure() { return android.hardware.biometrics.SystemAuth.available(); }
    public boolean isDeviceSecure() { return android.hardware.biometrics.SystemAuth.available(); }
    public boolean isKeyguardLocked() { return false; }
    public boolean isDeviceLocked() { return false; }
    public android.content.Intent createConfirmDeviceCredentialIntent(CharSequence t, CharSequence d) { return null; }
}
