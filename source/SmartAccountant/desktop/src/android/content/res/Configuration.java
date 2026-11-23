package android.content.res;
public class Configuration {
    public static final int UI_MODE_NIGHT_MASK = 0x30, UI_MODE_NIGHT_NO = 0x10, UI_MODE_NIGHT_YES = 0x20, UI_MODE_NIGHT_UNDEFINED = 0;
    public static final int ORIENTATION_PORTRAIT = 1, ORIENTATION_LANDSCAPE = 2;
    public static final int SCREENLAYOUT_SIZE_MASK = 0x0f, SCREENLAYOUT_SIZE_LARGE = 3, SCREENLAYOUT_SIZE_XLARGE = 4, SCREENLAYOUT_SIZE_NORMAL = 2;
    public int screenWidthDp = 480, screenHeightDp = 900, smallestScreenWidthDp = 480, uiMode = UI_MODE_NIGHT_NO | 1, orientation = ORIENTATION_PORTRAIT,
            densityDpi = 160, screenLayout = SCREENLAYOUT_SIZE_NORMAL;
    public float fontScale = 1f;
    public java.util.Locale locale = new java.util.Locale("ar");
    public int getLayoutDirection() { return 1; }
    public android.os.LocaleList getLocales() { return new android.os.LocaleList(locale); }
}
