package android.os;
public class Build {
    public static final String FINGERPRINT = "desktop/" + System.getProperty("os.name", "") + "/" + System.getProperty("os.arch", "");
    public static final String MODEL = System.getProperty("os.name", "Desktop"), MANUFACTURER = "Desktop", BRAND = "desktop", DEVICE = "desktop",
            PRODUCT = "desktop", HARDWARE = "desktop", BOARD = "desktop", ID = "desktop", TYPE = "user", TAGS = "release", HOST = "desktop", DISPLAY = "desktop";
    public static final String[] SUPPORTED_ABIS = {System.getProperty("os.arch", "x86_64")};
    public static class VERSION {
        public static final int SDK_INT = 35, PREVIEW_SDK_INT = 0;
        public static final String RELEASE = "15", CODENAME = "REL", INCREMENTAL = "desktop", SECURITY_PATCH = "2026-01-01";
    }
    public static class VERSION_CODES {
        public static final int M = 23, N = 24, N_MR1 = 25, O = 26, O_MR1 = 27, P = 28, Q = 29, R = 30, S = 31, S_V2 = 32, TIRAMISU = 33,
                UPSIDE_DOWN_CAKE = 34, VANILLA_ICE_CREAM = 35, LOLLIPOP = 21, KITKAT = 19;
    }
    public static String getSerial() { return "desktop"; }
}
