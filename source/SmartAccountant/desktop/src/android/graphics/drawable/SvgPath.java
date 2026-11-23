package android.graphics.drawable;

import java.awt.geom.Path2D;

/** قارئ pathData كامل (M L H V C S Q T A Z بالحروف الكبيرة والصغيرة). */
public final class SvgPath {
    private SvgPath() {}

    public static Path2D.Float parse(String d) {
        Path2D.Float p = new Path2D.Float();
        if (d == null) return p;
        int[] pos = {0};
        float x = 0, y = 0, sx = 0, sy = 0, cx = 0, cy = 0;
        char cmd = 0, prev = 0;
        int n = d.length();
        while (true) {
            skip(d, pos);
            if (pos[0] >= n) break;
            char ch = d.charAt(pos[0]);
            if (Character.isLetter(ch)) { cmd = ch; pos[0]++; }
            else if (cmd == 0) break;
            boolean rel = Character.isLowerCase(cmd);
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float a = num(d, pos), b = num(d, pos);
                    if (rel) { a += x; b += y; }
                    p.moveTo(a, b); x = sx = a; y = sy = b;
                    cmd = rel ? 'l' : 'L';
                    break;
                }
                case 'L': { float a = num(d, pos), b = num(d, pos); if (rel) { a += x; b += y; } p.lineTo(a, b); x = a; y = b; break; }
                case 'H': { float a = num(d, pos); if (rel) a += x; p.lineTo(a, y); x = a; break; }
                case 'V': { float b = num(d, pos); if (rel) b += y; p.lineTo(x, b); y = b; break; }
                case 'C': {
                    float x1 = num(d, pos), y1 = num(d, pos), x2 = num(d, pos), y2 = num(d, pos), a = num(d, pos), b = num(d, pos);
                    if (rel) { x1 += x; y1 += y; x2 += x; y2 += y; a += x; b += y; }
                    p.curveTo(x1, y1, x2, y2, a, b); cx = x2; cy = y2; x = a; y = b;
                    break;
                }
                case 'S': {
                    float x2 = num(d, pos), y2 = num(d, pos), a = num(d, pos), b = num(d, pos);
                    if (rel) { x2 += x; y2 += y; a += x; b += y; }
                    float x1 = x, y1 = y;
                    char pu = Character.toUpperCase(prev);
                    if (pu == 'C' || pu == 'S') { x1 = 2 * x - cx; y1 = 2 * y - cy; }
                    p.curveTo(x1, y1, x2, y2, a, b); cx = x2; cy = y2; x = a; y = b;
                    break;
                }
                case 'Q': {
                    float x1 = num(d, pos), y1 = num(d, pos), a = num(d, pos), b = num(d, pos);
                    if (rel) { x1 += x; y1 += y; a += x; b += y; }
                    p.quadTo(x1, y1, a, b); cx = x1; cy = y1; x = a; y = b;
                    break;
                }
                case 'T': {
                    float a = num(d, pos), b = num(d, pos);
                    if (rel) { a += x; b += y; }
                    float x1 = x, y1 = y;
                    char pu = Character.toUpperCase(prev);
                    if (pu == 'Q' || pu == 'T') { x1 = 2 * x - cx; y1 = 2 * y - cy; }
                    p.quadTo(x1, y1, a, b); cx = x1; cy = y1; x = a; y = b;
                    break;
                }
                case 'A': {
                    float rx = num(d, pos), ry = num(d, pos), rot = num(d, pos);
                    boolean large = flag(d, pos), sweep = flag(d, pos);
                    float a = num(d, pos), b = num(d, pos);
                    if (rel) { a += x; b += y; }
                    arc(p, x, y, a, b, rx, ry, rot, large, sweep);
                    x = a; y = b;
                    break;
                }
                case 'Z': p.closePath(); x = sx; y = sy; break;
                default: pos[0]++; break;
            }
            prev = cmd;
            if (Character.toUpperCase(cmd) == 'Z') { cmd = 0; prev = 'Z'; skip(d, pos); if (pos[0] < n && !Character.isLetter(d.charAt(pos[0]))) cmd = 'L'; }
        }
        return p;
    }

    private static void skip(String d, int[] pos) {
        while (pos[0] < d.length()) {
            char c = d.charAt(pos[0]);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') pos[0]++;
            else break;
        }
    }

    private static boolean flag(String d, int[] pos) {
        skip(d, pos);
        char c = d.charAt(pos[0]);
        pos[0]++;
        return c == '1';
    }

    private static float num(String d, int[] pos) {
        skip(d, pos);
        int s = pos[0], i = s, n = d.length();
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false, exp = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' && !dot && !exp) { dot = true; i++; }
            else if ((c == 'e' || c == 'E') && !exp) { exp = true; i++; if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++; }
            else break;
        }
        pos[0] = i;
        if (i == s) return 0;
        return Float.parseFloat(d.substring(s, i));
    }

    /** قوس SVG إلى منحنيات بيزييه. */
    private static void arc(Path2D.Float p, float x0, float y0, float x, float y, float rx, float ry, float angle, boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) { p.lineTo(x, y); return; }
        double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
        double a = Math.toRadians(angle % 360.0);
        double cos = Math.cos(a), sin = Math.sin(a);
        double x1 = cos * dx2 + sin * dy2, y1 = -sin * dx2 + cos * dy2;
        rx = Math.abs(rx); ry = Math.abs(ry);
        double Prx = rx * rx, Pry = ry * ry, Px1 = x1 * x1, Py1 = y1 * y1;
        double check = Px1 / Prx + Py1 / Pry;
        if (check > 1) { rx = (float) (Math.sqrt(check) * rx); ry = (float) (Math.sqrt(check) * ry); Prx = rx * rx; Pry = ry * ry; }
        double sign = (large == sweep) ? -1 : 1;
        double sq = ((Prx * Pry) - (Prx * Py1) - (Pry * Px1)) / ((Prx * Py1) + (Pry * Px1));
        sq = sq < 0 ? 0 : sq;
        double coef = sign * Math.sqrt(sq);
        double cx1 = coef * ((rx * y1) / ry), cy1 = coef * -((ry * x1) / rx);
        double sx2 = (x0 + x) / 2.0, sy2 = (y0 + y) / 2.0;
        double cx = sx2 + (cos * cx1 - sin * cy1), cy = sy2 + (sin * cx1 + cos * cy1);
        double ux = (x1 - cx1) / rx, uy = (y1 - cy1) / ry, vx = (-x1 - cx1) / rx, vy = (-y1 - cy1) / ry;
        double start = Math.atan2(uy, ux);
        double extent = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && extent > 0) extent -= 2 * Math.PI;
        else if (sweep && extent < 0) extent += 2 * Math.PI;
        int segs = (int) Math.ceil(Math.abs(extent) / (Math.PI / 2));
        double delta = extent / segs;
        double t = 4.0 / 3.0 * Math.tan(delta / 4);
        double th = start;
        for (int i = 0; i < segs; i++) {
            double c1 = Math.cos(th), s1 = Math.sin(th), th2 = th + delta, c2 = Math.cos(th2), s2 = Math.sin(th2);
            double ex1 = c1 - t * s1, ey1 = s1 + t * c1, ex2 = c2 + t * s2, ey2 = s2 - t * c2;
            p.curveTo((float) (cx + rx * ex1 * cos - ry * ey1 * sin), (float) (cy + rx * ex1 * sin + ry * ey1 * cos),
                    (float) (cx + rx * ex2 * cos - ry * ey2 * sin), (float) (cy + rx * ex2 * sin + ry * ey2 * cos),
                    (float) (cx + rx * c2 * cos - ry * s2 * sin), (float) (cy + rx * c2 * sin + ry * s2 * cos));
            th = th2;
        }
    }
}
