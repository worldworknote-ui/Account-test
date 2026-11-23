package android.text.method;
public class DigitsKeyListener implements KeyListener {
    final String accepted; final boolean decimal;
    private DigitsKeyListener(String a, boolean d) { accepted = a; decimal = d; }
    public static DigitsKeyListener getInstance(String accepted) { return new DigitsKeyListener(accepted, accepted.contains(".")); }
    public static DigitsKeyListener getInstance(boolean sign, boolean decimal) { return new DigitsKeyListener(null, decimal); }
    public static DigitsKeyListener getInstance(java.util.Locale l, boolean sign, boolean decimal) { return new DigitsKeyListener(null, decimal); }
    @Override public int getInputType() { return android.text.InputType.TYPE_CLASS_NUMBER | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0); }
}
