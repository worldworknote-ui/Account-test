package android.database;
public abstract class ContentObserver {
    public ContentObserver(android.os.Handler h) {}
    public void onChange(boolean self) {}
    public void onChange(boolean self, android.net.Uri u) { onChange(self); }
    public boolean deliverSelfNotifications() { return false; }
}
