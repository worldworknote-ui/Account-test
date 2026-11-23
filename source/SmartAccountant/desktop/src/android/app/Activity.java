package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * شاشة أندرويد على الكمبيوتر: نافذتها الجذرية في عمود الهاتف، ودورة الحياة (onCreate/onResume/...)، والسمة،
 * وفتح الملفات والمشاركة عبر Platform، وإعادة الإنشاء (recreate) كما في أندرويد.
 */
public class Activity extends Context {
    public static final int RESULT_OK = -1, RESULT_CANCELED = 0, RESULT_FIRST_USER = 1;
    private static final List<Activity> live = new ArrayList<>();

    private final FrameLayout decor;
    private final Window window;
    private ViewRootImpl root;
    private Intent intent = new Intent(Intent.ACTION_MAIN);
    private boolean finishing, destroyed, resumed;
    private boolean night;
    private int themeRes;
    final List<Dialog> dialogs = new ArrayList<>();
    /** تستدعيه نافذة الكمبيوتر حين تتغير ألوان الأشرطة (لون ما حول العمود). */
    public static Runnable onBarsChanged;

    public Activity() {
        super();
        decor = new FrameLayout(this);
        window = new Window(decor);
        window.onChange = () -> { if (onBarsChanged != null) onBarsChanged.run(); };
    }

    // ------------------------------------------------------------------ التشغيل
    /** يشغّل شاشة جديدة من هذا الصنف (أول شاشة في البرنامج). */
    public static <T extends Activity> T launch(Class<T> cls, Bundle saved) {
        try {
            T a = cls.getDeclaredConstructor().newInstance();
            ((Activity) a).attachWindow();
            a.onCreate(saved);
            a.onPostCreate(saved);
            a.onStart();
            a.onResume();
            ((Activity) a).resumed = true;
            a.onPostResume();
            return a;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private void attachWindow() {
        live.add(this);
        root = WindowStack.add(decor, ViewRootImpl.LAYER_APP);
        root.onBack = this::backKey;
        root.keyHook = this::shortcutKey;
    }
    private void backKey() {
        for (int i = dialogs.size() - 1; i >= 0; i--) { Dialog d = dialogs.get(i); if (d.isShowing()) { d.backPressed(); return; } }
        onBackPressed();
    }
    /** اختصارات لوحة المفاتيح (تُعرّف في الشاشة المشتقة). */
    protected boolean shortcutKey(java.awt.event.KeyEvent e) { return false; }
    public static List<Activity> live() { return new ArrayList<>(live); }
    public ViewRootImpl rootImpl() { return root; }

    // ------------------------------------------------------------------ دورة الحياة
    protected void onCreate(Bundle b) {}
    protected void onPostCreate(Bundle b) {}
    protected void onStart() {}
    protected void onRestart() {}
    protected void onResume() {}
    protected void onPostResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    protected void onSaveInstanceState(Bundle b) {}
    protected void onRestoreInstanceState(Bundle b) {}
    protected void onNewIntent(Intent i) {}
    protected void onActivityResult(int code, int result, Intent data) {}
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {}
    public void onConfigurationChanged(Configuration c) {}
    public void onWindowFocusChanged(boolean has) {}
    public void onUserInteraction() {}
    public void onTrimMemory(int level) {}
    public void onLowMemory() {}
    public boolean onKeyDown(int code, KeyEvent e) { return false; }
    public boolean onKeyUp(int code, KeyEvent e) { return false; }
    public boolean dispatchKeyEvent(KeyEvent e) { return false; }
    public boolean dispatchTouchEvent(MotionEvent e) { return false; }

    /** تفعيل/إيقاف الشاشة مع النافذة (تصغير وإظهار). */
    public void pauseFromWindow() { if (resumed && !destroyed) { resumed = false; onPause(); onStop(); } }
    public void resumeFromWindow() { if (!resumed && !destroyed) { onRestart(); onStart(); onResume(); resumed = true; onPostResume(); } }
    /** إغلاق البرنامج. */
    public void destroyFromWindow() {
        if (destroyed) return;
        if (resumed) { onPause(); onStop(); resumed = false; }
        destroyed = true;
        onDestroy();
        live.remove(this);
    }

    /** 11.5: النافذة أمام المستخدم الآن (ليست مصغّرة ولا خلف برنامج آخر). */
    public boolean hasWindowFocus() {
        java.awt.Frame f = Platform.frame;
        return f != null && f.isActive() && (f.getExtendedState() & java.awt.Frame.ICONIFIED) == 0;
    }

    public void onBackPressed() { finish(); }
    public void finish() {
        // على الكمبيوتر: الشاشة الرئيسية لا تُغلق بالرجوع، بل تُصغَّر النافذة كما يعود الهاتف للشاشة الرئيسية
        if (Platform.onFinish != null) Platform.onFinish.run();
    }
    public void finishAffinity() { finish(); }
    public void finishAndRemoveTask() { finish(); }
    public boolean moveTaskToBack(boolean nonRoot) { if (Platform.onFinish != null) Platform.onFinish.run(); return true; }
    public boolean isFinishing() { return finishing; }
    public boolean isDestroyed() { return destroyed; }
    public boolean isChangingConfigurations() { return false; }
    public boolean isTaskRoot() { return true; }

    /** إعادة إنشاء الشاشة (بعد تغيير السمة أو الخط) كما في أندرويد: كائن جديد بالحالة المحفوظة. */
    public void recreate() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            Bundle saved = new Bundle();
            onSaveInstanceState(saved);
            for (Dialog d : new ArrayList<>(dialogs)) d.dismiss();
            if (resumed) { onPause(); onStop(); resumed = false; }
            finishing = true;
            destroyed = true;
            onDestroy();
            live.remove(this);
            WindowStack.remove(root);
            Activity fresh = launch(getClass(), saved);
            fresh.intent = intent;
            if (Platform.onRecreate != null) Platform.onRecreate.accept(fresh);
        });
    }

    static void configChanged() {
        for (Activity a : new ArrayList<>(live)) if (!a.destroyed) a.onConfigurationChanged(a.getResources().getConfiguration());
    }

    // ------------------------------------------------------------------ النافذة والمحتوى
    public Window getWindow() { return window; }
    public void setContentView(View v) {
        decor.removeAllViews();
        decor.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }
    public void setContentView(View v, ViewGroup.LayoutParams p) { setContentView(v); }
    public void addContentView(View v, ViewGroup.LayoutParams p) { decor.addView(v, p); }
    public <T extends View> T findViewById(int id) { return decor.findViewById(id); }
    public View getCurrentFocus() { return root == null ? null : root.getFocused(); }
    public WindowManager getWindowManager() { return (WindowManager) getSystemService(WINDOW_SERVICE); }
    public LayoutInflater getLayoutInflater() { return LayoutInflater.from(this); }
    public void setRequestedOrientation(int o) {}
    public void setTitle(CharSequence t) {}
    public void setTaskDescription(Object d) {}
    public void runOnUiThread(Runnable r) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run();
        else javax.swing.SwingUtilities.invokeLater(r);
    }
    public final Application getApplication() { return Application.INSTANCE; }
    public void overridePendingTransition(int a, int b) {}
    public void reportFullyDrawn() {}
    public void setShowWhenLocked(boolean s) {}
    public void setTurnScreenOn(boolean t) {}
    public void invalidateOptionsMenu() {}
    public void setFinishOnTouchOutside(boolean f) {}
    public ComponentCaller getCaller() { return null; }
    public static class ComponentCaller {}

    // ------------------------------------------------------------------ السمة
    @Override public void setTheme(int res) {
        themeRes = res;
        String[] n = names(res);
        night = n != null && n[1].contains("Dark");
    }
    private static String[] names(int id) {
        try { return new String[]{"style", RES.getResourceEntryName(id)}; } catch (Exception e) { return null; }
    }
    public boolean themeNight() { return night; }
    public int themeAccent() { return night ? 0xFF5CCBA3 : 0xFF0B6B53; }
    public int themeTextColor() { return night ? 0xFFFFFFFF : 0xDE000000; }
    public int themeSecondaryText() { return night ? 0xB3FFFFFF : 0x8A000000; }
    public int themeHintColor() { return night ? 0x80FFFFFF : 0x61000000; }
    public int themeDialogBg() { return night ? 0xFF424242 : 0xFFFFFFFF; }

    // ------------------------------------------------------------------ النوايا
    public Intent getIntent() { return intent; }
    public void setIntent(Intent i) { intent = i; }
    /** نية جديدة من خارج الشاشة (اختصار أو مشاركة). */
    public void deliverNewIntent(Intent i) { onNewIntent(i); }
    public void startActivityForResult(Intent i, int code) { Platform.handle(this, i, code, this::deliverResult); }
    public void startActivityForResult(Intent i, int code, Bundle b) { startActivityForResult(i, code); }
    @Override public void startActivity(Intent i) { Platform.handle(this, i, -1, null); }
    void deliverResult(int code, int result, Intent data) { onActivityResult(code, result, data); }
    public void requestPermissions(String[] perms, int code) {
        int[] r = new int[perms.length];
        javax.swing.SwingUtilities.invokeLater(() -> onRequestPermissionsResult(code, perms, r));
    }
    public boolean shouldShowRequestPermissionRationale(String p) { return false; }
    public void setResult(int r) {}
    public void setResult(int r, Intent d) {}
    public android.content.ComponentName getComponentName() { return new android.content.ComponentName(this, getClass()); }
    public String getLocalClassName() { return getClass().getSimpleName(); }
}
