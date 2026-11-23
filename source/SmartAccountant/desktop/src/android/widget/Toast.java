package android.widget;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.*;

/** الإشعار العابر أسفل الشاشة. */
public class Toast {
    public static final int LENGTH_SHORT = 0, LENGTH_LONG = 1;
    private final Context ctx;
    private CharSequence text;
    private int duration;
    private View custom;
    private int gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, yOff = -1;
    private ViewRootImpl root;
    private static Toast current;

    public Toast(Context c) { ctx = c; }
    public static Toast makeText(Context c, CharSequence t, int d) { Toast x = new Toast(c); x.text = t; x.duration = d; return x; }
    public static Toast makeText(Context c, int res, int d) { return makeText(c, c.getResources().getText(res), d); }
    public void setText(CharSequence t) { text = t; }
    public void setText(int res) { text = ctx.getResources().getText(res); }
    public void setDuration(int d) { duration = d; }
    public int getDuration() { return duration; }
    public void setView(View v) { custom = v; }
    public View getView() { return custom; }
    public void setGravity(int g, int x, int y) { gravity = g; yOff = y; }
    public void cancel() {
        if (root != null) android.app.WindowStack.remove(root);
        root = null;
    }

    public void show() {
        javax.swing.SwingUtilities.invokeLater(this::showNow);
    }
    private void showNow() {
        if (current != null) current.cancel();
        current = this;
        float d = ctx.getResources().getDisplayMetrics().density;
        FrameLayout host = new FrameLayout(ctx);
        View content = custom;
        if (content == null) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextSize(14);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setMaxWidth(Math.round(340 * d));
            t.setPadding(Math.round(16 * d), Math.round(10 * d), Math.round(16 * d), Math.round(10 * d));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xE6333333);
            g.setCornerRadius(22 * d);
            t.setBackground(g);
            t.setElevation(6 * d);
            content = t;
        }
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, gravity);
        p.bottomMargin = yOff >= 0 ? yOff : Math.round(64 * d);
        p.topMargin = (gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP ? Math.max(0, yOff) : 0;
        p.leftMargin = p.rightMargin = Math.round(24 * d);
        host.addView(content, p);
        root = android.app.WindowStack.add(host, ViewRootImpl.LAYER_TOAST);
        root.setTouchable(false);
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(180).start();
        final View fc = content;
        final ViewRootImpl r = root;
        AttachInfo.MAIN.postDelayed(() -> fc.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            android.app.WindowStack.remove(r);
            if (current == this) current = null;
        }).start(), duration == LENGTH_LONG ? 3500 : 2000);
    }
}
