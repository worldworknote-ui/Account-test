package android.text.method;
public class ScrollingMovementMethod implements MovementMethod {
    private static final ScrollingMovementMethod I = new ScrollingMovementMethod();
    public static MovementMethod getInstance() { return I; }
}
