package android.view;

/** ما تحتاجه الخانة الحقيقية من خانة الكتابة (EditText). */
public interface TextHost {
    String hostText();
    /** نص كتبه المستخدم في الخانة الحقيقية. */
    void userText(String s, int selStart, int selEnd);
    void userSelection(int s, int e);
    int selStart();
    int selEnd();
    java.awt.Font hostFont();
    int hostColor();
    int hostHighlight();
    boolean singleLine();
    boolean password();
    /** 0 نص، 1 أرقام، 2 أرقام عشرية، 3 هاتف. */
    int numeric();
    int maxLength();
    /** محاذاة Swing (LEFT/RIGHT/CENTER). */
    int align();
    boolean rtlText();
    /** Enter في خانة بسطر واحد: true إن استُهلك. */
    boolean enterPressed();
    boolean editable();
    /** الخانة الحقيقية ظاهرة الآن (فلا يُرسم النص مرتين). */
    void liveChanged(boolean live);
}
