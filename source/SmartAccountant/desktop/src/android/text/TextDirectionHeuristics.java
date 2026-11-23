package android.text;
public class TextDirectionHeuristics {
    public static final TextDirectionHeuristic LTR = (s, a, n) -> false, RTL = (s, a, n) -> true,
            FIRSTSTRONG_LTR = (s, a, n) -> android.graphics.Paint.firstStrongRtl(s.subSequence(a, a + n)),
            FIRSTSTRONG_RTL = (s, a, n) -> { CharSequence x = s.subSequence(a, a + n); for (int i = 0; i < x.length(); i++) { byte d = Character.getDirectionality(x.charAt(i)); if (d == 0) return false; if (d == 1 || d == 2) return true; } return true; },
            ANYRTL_LTR = (s, a, n) -> { for (int i = a; i < a + n; i++) { byte d = Character.getDirectionality(s.charAt(i)); if (d == 1 || d == 2) return true; } return false; },
            LOCALE = (s, a, n) -> true;
}
