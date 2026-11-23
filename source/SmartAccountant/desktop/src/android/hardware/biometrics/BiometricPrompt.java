package android.hardware.biometrics;

/** 11.2: على الكمبيوتر = تحقق النظام (Touch ID أو كلمة سر الماك، أو Windows Hello)؛ إن لم يوجد قفل: «لا قفل» (14). */
public class BiometricPrompt {
    public static final int BIOMETRIC_ERROR_USER_CANCELED = 10, BIOMETRIC_ERROR_CANCELED = 5, BIOMETRIC_ERROR_HW_NOT_PRESENT = 12, BIOMETRIC_ERROR_NO_BIOMETRICS = 11,
            BIOMETRIC_ERROR_HW_UNAVAILABLE = 1, BIOMETRIC_ERROR_LOCKOUT = 7, BIOMETRIC_ERROR_LOCKOUT_PERMANENT = 9, BIOMETRIC_ERROR_TIMEOUT = 3,
            BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL = 14, AUTHENTICATION_RESULT_TYPE_BIOMETRIC = 2;
    public static class AuthenticationResult { public int getAuthenticationType() { return 1; } public CryptoObject getCryptoObject() { return null; } }
    public static class CryptoObject { public CryptoObject(javax.crypto.Cipher c) {} public CryptoObject(java.security.Signature s) {} }
    public abstract static class AuthenticationCallback {
        public void onAuthenticationError(int code, CharSequence msg) {}
        public void onAuthenticationSucceeded(AuthenticationResult r) {}
        public void onAuthenticationFailed() {}
        public void onAuthenticationHelp(int code, CharSequence msg) {}
    }
    private final String title;
    private BiometricPrompt(String title) { this.title = title; }
    /** 11.5: تحقق واحد في كل مرة (كان كل ضغط على «فتح التطبيق» يفتح نافذة تحقق جديدة خلف البرنامج). */
    private static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    public void authenticate(android.os.CancellationSignal s, java.util.concurrent.Executor e, AuthenticationCallback cb) {
        if (!running.compareAndSet(false, true)) {
            e.execute(() -> cb.onAuthenticationError(BIOMETRIC_ERROR_HW_UNAVAILABLE, "نافذة التحقق مفتوحة الآن: ابحث عنها في شريط المهام، أو انتظر قليلًا ثم اضغط «فتح التطبيق»."));
            return;
        }
        Thread t = new Thread(() -> {
            int r;
            try {
                r = SystemAuth.verify(title == null ? "" : title);
            } finally {
                running.set(false);
            }
            if (s != null && s.isCanceled()) return;
            if (r == 0) e.execute(() -> cb.onAuthenticationSucceeded(new AuthenticationResult()));
            else if (r == 2) e.execute(() -> cb.onAuthenticationError(BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL, "لا يوجد قفل للجهاز"));
            else e.execute(() -> cb.onAuthenticationError(BIOMETRIC_ERROR_USER_CANCELED, "أُلغي التحقق"));
        }, "system-auth");
        t.setDaemon(true);
        t.start();
    }
    public void authenticate(CryptoObject c, android.os.CancellationSignal s, java.util.concurrent.Executor e, AuthenticationCallback cb) { authenticate(s, e, cb); }
    public static class Builder {
        private String title;
        public Builder(android.content.Context c) {}
        public Builder setTitle(CharSequence t) { title = t == null ? null : t.toString(); return this; }
        public Builder setSubtitle(CharSequence t) { return this; }
        public Builder setDescription(CharSequence t) { return this; }
        public Builder setNegativeButton(CharSequence t, java.util.concurrent.Executor e, android.content.DialogInterface.OnClickListener l) { return this; }
        public Builder setAllowedAuthenticators(int a) { return this; }
        public Builder setDeviceCredentialAllowed(boolean b) { return this; }
        public Builder setConfirmationRequired(boolean b) { return this; }
        public BiometricPrompt build() { return new BiometricPrompt(title); }
    }
}
