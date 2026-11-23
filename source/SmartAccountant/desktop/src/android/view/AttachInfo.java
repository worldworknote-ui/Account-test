package android.view;

import android.os.Handler;

/** ما يشترك فيه كل عنصر في نافذة واحدة. */
public final class AttachInfo {
    public static final Handler MAIN = new Handler(android.os.Looper.getMainLooper());
    /** الواجهة عربية: الاتجاه الافتراضي من اليمين. */
    public static volatile boolean localeRtl = true;
    final ViewRootImpl root;
    final Handler handler = MAIN;
    final ViewTreeObserver observer = new ViewTreeObserver();
    final android.os.IBinder token = new android.os.Binder();
    View pendingFocus;
    AttachInfo(ViewRootImpl r) { root = r; }
}
