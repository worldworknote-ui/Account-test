package android.app;

import android.view.*;
import android.view.TextOverlay;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

/**
 * كومة نوافذ أندرويد داخل عمود «الهاتف» في نافذة الكمبيوتر: الشاشة، ثم الحوارات والقوائم والإشعارات فوقها.
 * عرض العمود من الإعدادات (480 / 600 / بعرض النافذة) وتكبير الواجهة كذلك.
 */
public final class WindowStack {
    private WindowStack() {}

    private static JLayeredPane host;
    private static final List<ViewRootImpl> roots = new ArrayList<>();

    /** لوحة الطبقات (عمود الهاتف). */
    public static synchronized JLayeredPane host() {
        if (host == null) {
            host = new JLayeredPane() {
                @Override public void doLayout() { relayout(); }
                @Override public void setBounds(int x, int y, int w, int h) { super.setBounds(x, y, w, h); relayout(); }
            };
            host.setOpaque(false);
            host.setLayout(null);
        }
        return host;
    }

    public static ViewRootImpl add(View v, int layer) {
        ViewRootImpl r = new ViewRootImpl(v, layer);
        JLayeredPane h = host();
        h.add(r.pane, Integer.valueOf(layer));
        h.moveToFront(r.pane);
        r.pane.setBounds(0, 0, h.getWidth(), h.getHeight());
        roots.add(r);
        r.requestLayout();
        h.repaint();
        // نافذة الحوار تأخذ لوحة المفاتيح (كما في أندرويد)، والإشعار العابر لا يأخذها
        if (layer >= ViewRootImpl.LAYER_DIALOG && layer < ViewRootImpl.LAYER_TOAST) {
            SwingUtilities.invokeLater(() -> { if (roots.contains(r) && TextOverlay.active(r) == null) r.pane.requestFocusInWindow(); });
        }
        return r;
    }

    public static void remove(ViewRootImpl r) {
        if (r == null || !roots.remove(r)) return;
        r.detach();
        JLayeredPane h = host();
        h.remove(r.pane);
        h.repaint();
        // التركيز يعود للنافذة التي تحتها
        ViewRootImpl top = top();
        if (top != null) SwingUtilities.invokeLater(() -> {
            View f = top.getFocused();
            if (f != null && f.onCheckIsTextEditor()) top.setFocused(f);
            else top.pane.requestFocusInWindow();
        });
    }

    public static List<ViewRootImpl> roots() { return new ArrayList<>(roots); }

    /** النافذة العليا التي تستقبل اللمس. */
    public static ViewRootImpl top() {
        ViewRootImpl best = null;
        for (ViewRootImpl r : roots) if (r.layer() < ViewRootImpl.LAYER_TOAST && (best == null || r.layer() >= best.layer())) best = r;
        return best;
    }

    /** 11.0: تغيّر المقياس العام: تخطيط جديد لكل النوافذ (والحجم المنطقي يتغيّر). */
    public static void rescale(float s) {
        if (s == android.view.ViewRootImpl.scale()) return;
        android.view.ViewRootImpl.setScale(s);
        relayout();
    }

    static void relayout() {
        if (host == null) return;
        int pw = host.getWidth(), ph = host.getHeight();
        float sc = android.view.ViewRootImpl.scale();
        int w = Math.round(pw / sc), h = Math.round(ph / sc);
        android.content.res.Resources res = android.content.Context.RES;
        float d = res.getDisplayMetrics().density;
        res.getDisplayMetrics().widthPixels = w;
        res.getDisplayMetrics().heightPixels = h;
        int wdp = Math.round(w / d), hdp = Math.round(h / d);
        boolean changed = res.getConfiguration().screenWidthDp != wdp || res.getConfiguration().screenHeightDp != hdp;
        res.getConfiguration().screenWidthDp = wdp;
        res.getConfiguration().screenHeightDp = hdp;
        res.getConfiguration().smallestScreenWidthDp = Math.min(wdp, hdp);
        res.getConfiguration().orientation = w > h ? android.content.res.Configuration.ORIENTATION_LANDSCAPE : android.content.res.Configuration.ORIENTATION_PORTRAIT;
        for (ViewRootImpl r : roots) {
            r.pane.setBounds(0, 0, pw, ph);
            r.requestLayout();
        }
        if (changed) Activity.configChanged();
    }
}
