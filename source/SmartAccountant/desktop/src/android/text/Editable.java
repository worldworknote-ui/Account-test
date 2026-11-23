package android.text;
public interface Editable extends CharSequence, GetChars, Spannable, Appendable {
    Editable replace(int st, int en, CharSequence s, int start, int end);
    Editable replace(int st, int en, CharSequence s);
    Editable insert(int where, CharSequence s);
    Editable delete(int st, int en);
    Editable append(CharSequence s);
    Editable append(CharSequence s, int start, int end);
    Editable append(char c);
    void clear();
    void clearSpans();
    void setFilters(InputFilter[] f);
    InputFilter[] getFilters();
    class Factory { private static final Factory I = new Factory(); public static Factory getInstance() { return I; } public Editable newEditable(CharSequence s) { return new SpannableStringBuilder(s); } }
}
