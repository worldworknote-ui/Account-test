package android.app;
public abstract class Service extends android.content.Context {
    public Service() { super(); }
    public void onCreate() {}
    public void onDestroy() {}
    public int onStartCommand(android.content.Intent i, int flags, int id) { return 2; }
    public android.os.IBinder onBind(android.content.Intent i) { return null; }
    public final void stopSelf() {}
    public final void startForeground(int id, Notification n) {}
    public final void stopForeground(boolean remove) {}
}
