package android.app;

public class Notification {
    public static final int PRIORITY_DEFAULT = 0, PRIORITY_HIGH = 1, PRIORITY_LOW = -1, VISIBILITY_PRIVATE = 0, VISIBILITY_PUBLIC = 1, VISIBILITY_SECRET = -1,
            DEFAULT_ALL = -1, DEFAULT_SOUND = 1, FLAG_AUTO_CANCEL = 16;
    public static final String CATEGORY_REMINDER = "reminder", CATEGORY_STATUS = "status", CATEGORY_MESSAGE = "msg", CATEGORY_EVENT = "event",
            CATEGORY_ERROR = "err", CATEGORY_PROGRESS = "progress", CATEGORY_SYSTEM = "sys", EXTRA_TITLE = "android.title", EXTRA_TEXT = "android.text";
    public CharSequence title, text;
    public PendingIntent contentIntent;
    public int flags;
    public final android.os.Bundle extras = new android.os.Bundle();

    public static class Style { CharSequence big; }
    public static class BigTextStyle extends Style {
        public BigTextStyle bigText(CharSequence t) { big = t; return this; }
        public BigTextStyle setBigContentTitle(CharSequence t) { return this; }
        public BigTextStyle setSummaryText(CharSequence t) { return this; }
    }
    public static class InboxStyle extends Style {
        private final StringBuilder b = new StringBuilder();
        public InboxStyle addLine(CharSequence l) { if (b.length() > 0) b.append('\n'); b.append(l); big = b.toString(); return this; }
        public InboxStyle setBigContentTitle(CharSequence t) { return this; }
        public InboxStyle setSummaryText(CharSequence t) { return this; }
    }
    public static class Action { public Action(int icon, CharSequence t, PendingIntent p) {} public static class Builder { public Builder(int icon, CharSequence t, PendingIntent p) {} public Action build() { return new Action(0, null, null); } } }

    public static class Builder {
        private final Notification n = new Notification();
        private Style style;
        public Builder(android.content.Context c) {}
        public Builder(android.content.Context c, String channel) {}
        public Builder setContentTitle(CharSequence t) { n.title = t; return this; }
        public Builder setContentText(CharSequence t) { n.text = t; return this; }
        public Builder setSubText(CharSequence t) { return this; }
        public Builder setSmallIcon(int r) { return this; }
        public Builder setSmallIcon(android.graphics.drawable.Icon i) { return this; }
        public Builder setLargeIcon(android.graphics.Bitmap b) { return this; }
        public Builder setContentIntent(PendingIntent p) { n.contentIntent = p; return this; }
        public Builder setDeleteIntent(PendingIntent p) { return this; }
        public Builder setAutoCancel(boolean a) { if (a) n.flags |= FLAG_AUTO_CANCEL; return this; }
        public Builder setStyle(Style s) { style = s; return this; }
        public Builder setPriority(int p) { return this; }
        public Builder setCategory(String c) { return this; }
        public Builder setColor(int c) { return this; }
        public Builder setOnlyAlertOnce(boolean o) { return this; }
        public Builder setOngoing(boolean o) { return this; }
        public Builder setShowWhen(boolean s) { return this; }
        public Builder setWhen(long w) { return this; }
        public Builder setVisibility(int v) { return this; }
        public Builder setGroup(String g) { return this; }
        public Builder setGroupSummary(boolean s) { return this; }
        public Builder setNumber(int n) { return this; }
        public Builder setDefaults(int d) { return this; }
        public Builder setTicker(CharSequence t) { return this; }
        public Builder setTimeoutAfter(long t) { return this; }
        public Builder setProgress(int max, int p, boolean ind) { return this; }
        public Builder addAction(int icon, CharSequence t, PendingIntent p) { return this; }
        public Builder addAction(Action a) { return this; }
        public Builder setChannelId(String id) { return this; }
        public Builder setBadgeIconType(int t) { return this; }
        public Builder setLocalOnly(boolean l) { return this; }
        public Notification build() {
            if (style != null && style.big != null) n.text = style.big;
            n.extras.putCharSequence(EXTRA_TITLE, n.title);
            n.extras.putCharSequence(EXTRA_TEXT, n.text);
            return n;
        }
    }
}
