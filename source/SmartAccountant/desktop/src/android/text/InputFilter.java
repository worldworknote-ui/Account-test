package android.text;
public interface InputFilter {
    CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend);
    class LengthFilter implements InputFilter {
        private final int max;
        public LengthFilter(int max) { this.max = max; }
        public int getMax() { return max; }
        @Override public CharSequence filter(CharSequence s, int a, int b, Spanned d, int ds, int de) {
            int keep = max - (d.length() - (de - ds));
            if (keep <= 0) return "";
            if (keep >= b - a) return null;
            return s.subSequence(a, a + keep);
        }
    }
    class AllCaps implements InputFilter {
        @Override public CharSequence filter(CharSequence s, int a, int b, Spanned d, int ds, int de) { return s.subSequence(a, b).toString().toUpperCase(); }
    }
}
