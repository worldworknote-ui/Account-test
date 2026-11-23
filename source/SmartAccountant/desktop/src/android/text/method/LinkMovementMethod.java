package android.text.method;
public class LinkMovementMethod extends ScrollingMovementMethod {
    private static final LinkMovementMethod I = new LinkMovementMethod();
    public static MovementMethod getInstance() { return I; }
}
