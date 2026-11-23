package android.os;
public final class CancellationSignal {
    public interface OnCancelListener { void onCancel(); }
    private volatile boolean c; private OnCancelListener l;
    public void cancel() { c = true; if (l != null) l.onCancel(); }
    public boolean isCanceled() { return c; }
    public void setOnCancelListener(OnCancelListener l) { this.l = l; }
    public void throwIfCanceled() { if (c) throw new OperationCanceledException(); }
}
