package android.os;
public final class Message {
    public int what, arg1, arg2; public Object obj; public Handler target; Bundle data;
    public static Message obtain() { return new Message(); }
    public void sendToTarget() { if (target != null) target.sendMessage(this); }
    public Bundle getData() { if (data == null) data = new Bundle(); return data; }
    public void setData(Bundle b) { data = b; }
}
