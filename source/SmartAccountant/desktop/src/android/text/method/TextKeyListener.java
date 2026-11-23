package android.text.method;
public class TextKeyListener implements KeyListener {
    public enum Capitalize { NONE, SENTENCES, WORDS, CHARACTERS }
    public static TextKeyListener getInstance() { return new TextKeyListener(); }
    public static TextKeyListener getInstance(boolean auto, Capitalize c) { return new TextKeyListener(); }
    @Override public int getInputType() { return android.text.InputType.TYPE_CLASS_TEXT; }
}
