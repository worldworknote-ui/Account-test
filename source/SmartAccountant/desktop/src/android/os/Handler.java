package android.os;

import java.util.*;

/** ينفّذ الأعمال على خيط الواجهة (فورًا أو بعد مهلة)، مع إمكان إلغائها. */
public class Handler {
    public interface Callback { boolean handleMessage(Message m); }

    private static final class Task implements java.awt.event.ActionListener {
        final Runnable r; final Object token; final Handler h; javax.swing.Timer timer; boolean cancelled;
        Task(Handler h, Runnable r, Object token) { this.h = h; this.r = r; this.token = token; }
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { fire(); }
        void fire() {
            synchronized (h.tasks) { h.tasks.remove(this); }
            if (cancelled) return;
            try { r.run(); } catch (Throwable t) { Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t); }
        }
    }

    final List<Task> tasks = new ArrayList<>();
    private final Callback cb;

    public Handler() { this.cb = null; }
    public Handler(Looper l) { this.cb = null; }
    public Handler(Looper l, Callback cb) { this.cb = cb; }
    public Handler(Callback cb) { this.cb = cb; }

    public final Looper getLooper() { return Looper.getMainLooper(); }

    public final boolean post(Runnable r) { return postDelayed(r, 0); }
    public final boolean postAtFrontOfQueue(Runnable r) { return post(r); }
    public final boolean postDelayed(Runnable r, long ms) { return postDelayed(r, null, ms); }
    public final boolean postDelayed(Runnable r, Object token, long ms) {
        if (r == null) return false;
        final Task t = new Task(this, r, token);
        synchronized (tasks) { tasks.add(t); }
        if (ms <= 0) {
            javax.swing.SwingUtilities.invokeLater(t::fire);
        } else {
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (t.cancelled) return;
                t.timer = new javax.swing.Timer((int) Math.min(Integer.MAX_VALUE, ms), t);
                t.timer.setRepeats(false);
                t.timer.start();
            });
        }
        return true;
    }
    public final boolean postAtTime(Runnable r, long uptime) { return postDelayed(r, uptime - SystemClock.uptimeMillis()); }
    public final boolean postAtTime(Runnable r, Object token, long uptime) { return postDelayed(r, token, uptime - SystemClock.uptimeMillis()); }

    public final void removeCallbacks(Runnable r) {
        synchronized (tasks) {
            for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
                Task t = it.next();
                if (t.r == r) { t.cancelled = true; if (t.timer != null) t.timer.stop(); it.remove(); }
            }
        }
    }
    public final void removeCallbacks(Runnable r, Object token) { removeCallbacks(r); }
    public final void removeCallbacksAndMessages(Object token) {
        synchronized (tasks) {
            for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
                Task t = it.next();
                if (token == null || t.token == token) { t.cancelled = true; if (t.timer != null) t.timer.stop(); it.remove(); }
            }
        }
    }
    public final boolean hasCallbacks(Runnable r) { synchronized (tasks) { for (Task t : tasks) if (t.r == r) return true; } return false; }

    // ---- الرسائل (قليلة الاستعمال)
    public void handleMessage(Message m) {}
    public final boolean sendEmptyMessage(int what) { return sendEmptyMessageDelayed(what, 0); }
    public final boolean sendEmptyMessageDelayed(int what, long ms) {
        Message m = Message.obtain(); m.what = what; return sendMessageDelayed(m, ms);
    }
    public final boolean sendMessage(Message m) { return sendMessageDelayed(m, 0); }
    public final boolean sendMessageDelayed(Message m, long ms) {
        m.target = this;
        return postDelayed(() -> { if (cb == null || !cb.handleMessage(m)) handleMessage(m); }, m, ms);
    }
    public final Message obtainMessage(int what) { Message m = Message.obtain(); m.what = what; m.target = this; return m; }
    public final Message obtainMessage(int what, Object o) { Message m = obtainMessage(what); m.obj = o; return m; }
    public final void removeMessages(int what) {}
    public final boolean hasMessages(int what) { return false; }
}
