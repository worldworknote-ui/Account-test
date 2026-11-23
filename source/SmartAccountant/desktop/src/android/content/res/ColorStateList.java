package android.content.res;

public class ColorStateList {
    private final int[][] states;
    private final int[] colors;
    public ColorStateList(int[][] states, int[] colors) { this.states = states; this.colors = colors; }
    public static ColorStateList valueOf(int c) { return new ColorStateList(new int[][]{{}}, new int[]{c}); }
    public int getDefaultColor() { return colors.length == 0 ? 0 : colors[colors.length - 1]; }
    public boolean isStateful() { return states.length > 1 || (states.length == 1 && states[0].length > 0); }
    public int getColorForState(int[] st, int def) {
        if (st == null) st = new int[0];
        outer:
        for (int i = 0; i < states.length; i++) {
            for (int s : states[i]) {
                boolean has = false;
                for (int x : st) if (x == Math.abs(s)) { has = true; break; }
                if (s > 0 && !has) continue outer;
                if (s < 0 && has) continue outer;
            }
            return colors[i];
        }
        return def;
    }
    public ColorStateList withAlpha(int a) {
        int[] c = colors.clone();
        for (int i = 0; i < c.length; i++) c[i] = (a << 24) | (c[i] & 0xFFFFFF);
        return new ColorStateList(states, c);
    }
}
