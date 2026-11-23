package android.app;

import java.awt.*;

/** إشعارات النظام (شريط الماك/ويندوز) مع إشعار داخل البرنامج. الضغط على الإشعار يفتح الشاشة المطلوبة. */
public class NotificationManager {
    public static final int IMPORTANCE_DEFAULT = 3, IMPORTANCE_HIGH = 4, IMPORTANCE_LOW = 2, IMPORTANCE_MIN = 1, IMPORTANCE_NONE = 0, IMPORTANCE_MAX = 5;
    private static TrayIcon tray;
    private static Notification last;
    /** يُستدعى عند الضغط على الإشعار: النية المرفقة. */
    public static java.util.function.Consumer<android.content.Intent> onOpen;

    public void createNotificationChannel(NotificationChannel c) {}
    public void createNotificationChannels(java.util.List<NotificationChannel> l) {}
    public void deleteNotificationChannel(String id) {}
    public NotificationChannel getNotificationChannel(String id) { return new NotificationChannel(id, id, IMPORTANCE_DEFAULT); }
    public boolean areNotificationsEnabled() { return true; }
    public void cancel(int id) {}
    public void cancel(String tag, int id) {}
    public void cancelAll() {}
    public void notify(String tag, int id, Notification n) { notify(id, n); }
    public void notify(int id, Notification n) {
        last = n;
        String title = n.title == null ? "المحاسب الذكي" : n.title.toString(), text = n.text == null ? "" : n.text.toString();
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return;
                if (tray == null) {
                    Image icon = Toolkit.getDefaultToolkit().createImage(new byte[0]);
                    if (Platform.frame != null && !Platform.frame.getIconImages().isEmpty()) icon = Platform.frame.getIconImages().get(0);
                    tray = new TrayIcon(icon, "المحاسب الذكي");
                    tray.setImageAutoSize(true);
                    tray.addActionListener(e -> open());
                    SystemTray.getSystemTray().add(tray);
                }
                tray.displayMessage(title, text, TrayIcon.MessageType.INFO);
            } catch (Throwable ignored) {
                // يكفي سجل الإشعارات داخل البرنامج
            }
        });
    }
    private static void open() {
        if (Platform.frame != null) { Platform.frame.setState(Frame.NORMAL); Platform.frame.toFront(); }
        if (last != null && last.contentIntent != null && onOpen != null) onOpen.accept(last.contentIntent.intent);
    }
    public android.service.notification.StatusBarNotification[] getActiveNotifications() { return new android.service.notification.StatusBarNotification[0]; }
}
