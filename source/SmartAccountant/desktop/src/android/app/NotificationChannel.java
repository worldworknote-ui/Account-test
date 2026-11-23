package android.app;
public final class NotificationChannel {
    final String id; CharSequence name; int importance; String desc;
    public NotificationChannel(String id, CharSequence name, int importance) { this.id = id; this.name = name; this.importance = importance; }
    public void setDescription(String d) { desc = d; }
    public void enableVibration(boolean v) {}
    public void enableLights(boolean l) {}
    public void setShowBadge(boolean s) {}
    public void setSound(android.net.Uri u, Object a) {}
    public void setLockscreenVisibility(int v) {}
    public void setVibrationPattern(long[] p) {}
    public String getId() { return id; }
    public int getImportance() { return importance; }
}
