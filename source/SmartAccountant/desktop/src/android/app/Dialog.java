package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.FrameLayout;

/** نافذة حوار فوق الشاشة: خلفية معتمة، والمحتوى في الوسط، والرجوع (Esc) أو اللمس خارجها يلغيها إن سُمح. */
public class Dialog implements DialogInterface {
    protected final Context ctx;
    protected final FrameLayout scrim;
    protected final FrameLayout content;
    private final Window window;
    private ViewRootImpl root;
    private boolean showing, cancelable = true, outside = true;
    private OnDismissListener onDismiss;
    private OnCancelListener onCancel;
    private OnShowListener onShow;
    private OnKeyListener onKey;
    private boolean created;

    public Dialog(Context c) { this(c, 0); }
    public Dialog(Context c, int theme) {
        ctx = c;
        scrim = new FrameLayout(c);
        content = new FrameLayout(c);
        scrim.addView(content, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        window = new Window(scrim);
        window.onChange = this::applyWindow;
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> { if (cancelable && outside) cancel(); });
        content.setClickable(true);
    }

    public Context getContext() { return ctx; }
    public Window getWindow() { return window; }
    protected void onCreate(android.os.Bundle b) {}
    protected void onStart() {}
    protected void onStop() {}
    public void create() { if (!created) { created = true; onCreate(null); } }
    public void setContentView(View v) { content.removeAllViews(); content.addView(v, new FrameLayout.LayoutParams(-2, -2)); }
    public void setContentView(View v, ViewGroup.LayoutParams p) { content.removeAllViews(); content.addView(v, p); }
    public <T extends View> T findViewById(int id) { return content.findViewById(id); }
    public void setTitle(CharSequence t) {}
    public void setTitle(int res) {}
    public void setCancelable(boolean c) { cancelable = c; }
    public void setCanceledOnTouchOutside(boolean c) { if (c) cancelable = true; outside = c; }
    public boolean isCancelable() { return cancelable; }
    public boolean isShowing() { return showing; }
    public void setOnDismissListener(OnDismissListener l) { onDismiss = l; }
    public void setOnCancelListener(OnCancelListener l) { onCancel = l; }
    public void setOnShowListener(OnShowListener l) { onShow = l; }
    public void setOnKeyListener(OnKeyListener l) { onKey = l; }
    public void setOwnerActivity(Activity a) {}
    public Activity getOwnerActivity() { return ctx instanceof Activity ? (Activity) ctx : null; }
    public View getCurrentFocus() { return root == null ? null : root.getFocused(); }
    public void onBackPressed() { if (cancelable) cancel(); }
    void backPressed() {
        if (onKey != null && onKey.onKey(this, KeyEvent.KEYCODE_BACK, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))) return;
        onBackPressed();
    }

    private void applyWindow() {
        WindowManager.LayoutParams a = window.getAttributes();
        int dim = Math.round(255 * Math.max(0, Math.min(1, a.dimAmount)));
        if ((a.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0 && a.dimAmount != 0.6f) dim = 0;
        scrim.setBackground(new ColorDrawable(dim << 24));
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) content.getLayoutParams();
        if (a.width != -1 && a.width != 0 || a.gravity != 0) {
            if (a.gravity != 0) p.gravity = a.gravity;
            content.setLayoutParams(p);
        }
    }

    public void show() {
        if (showing) return;
        create();
        showing = true;
        scrim.setBackground(new ColorDrawable(0x99000000));
        root = WindowStack.add(scrim, ViewRootImpl.LAYER_DIALOG);
        root.onBack = this::backPressed;
        if (ctx instanceof Activity) ((Activity) ctx).dialogs.add(this);
        onStart();
        if (!android.animation.Choreo.instant) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(150).start();
            content.setScaleX(0.94f);
            content.setScaleY(0.94f);
            content.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        if (onShow != null) {
            final OnShowListener l = onShow;
            AttachInfo.MAIN.post(() -> { if (showing) l.onShow(this); });
        }
    }
    public void hide() { if (root != null) root.pane.setVisible(false); }
    @Override public void dismiss() {
        if (!showing) return;
        showing = false;
        if (ctx instanceof Activity) ((Activity) ctx).dialogs.remove(this);
        final ViewRootImpl r = root;
        root = null;
        WindowStack.remove(r);
        onStop();
        if (onDismiss != null) onDismiss.onDismiss(this);
    }
    @Override public void cancel() {
        if (!showing) return;
        if (onCancel != null) onCancel.onCancel(this);
        dismiss();
    }
}
