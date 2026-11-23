package com.smart.hawala;

import java.util.ArrayList;
import java.util.List;

/**
 * 12.2: يجد «الفقاعات» في صورة ما تحت إطار الالتقاط من شكلها، لا من أسطر النص: كل برامج المحادثة (واتساب، تلغرام…)
 * ترسم الرسالة مستطيلًا بلون واحد مختلف عن الخلفية، فنبحث عن مناطق بلون واحد حوافها كاملة وعرضها أقل من عرض الإطار.
 * هكذا تصير الرسالة كلها رقمًا واحدًا مهما كان فيها من أسطر وكلمات واسم ملف ووقت.
 * <p>
 * منطق بحت (مصفوفة بكسلات) بلا أندرويد ولا Swing حتى يُختبر.
 */
public final class BubbleShapes {
    private BubbleShapes() {}

    /** مستطيل بإحداثيات الصورة الأصلية. */
    public static final class Box {
        public final int x, y, w, h;
        /** 12.5: فقاعة صورة أو ملصق أو فيديو (حافة بلون الفقاعة وداخلها صورة): لا تُنقل ولا تُرقَّم. */
        public boolean media;

        Box(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(Box o) { return o.x >= x && o.y >= y && o.x + o.w <= x + w && o.y + o.h <= y + h; }

        @Override public String toString() { return x + "," + y + " " + w + "x" + h; }
    }

    /**
     * argb: بكسلات الصورة (صفًا صفًا)، w×h أبعادها، unit: حجم «البكسل المنطقي» في الصورة (2 على شاشات Retina وتكبير 200%).
     * يعيد الفقاعات من الأعلى للأسفل.
     */
    public static List<Box> find(int[] argb, int w, int h, double unit) {
        List<Box> out = new ArrayList<>();
        if (argb == null || w < 20 || h < 20 || argb.length < w * h) return out;
        // نصغّر الصورة لنحو بكسل منطقي واحد (أو أكثر للصور الكبيرة) — يكفي للألوان ويسرّع العمل
        int step = (int) Math.max(1, Math.round(unit));
        while (w / step > 900 || h / step > 1400) step++;
        int sw = w / step, sh = h / step;
        int[] px = new int[sw * sh];
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) px[y * sw + x] = argb[(y * step + step / 2) * w + x * step + step / 2] & 0xFFFFFF;

        // 12.3: «لبّ» المنطقة: بكسل جيرانه الثمانية بلونه نفسه. الملء يمر على اللبّ فقط، فلا تنساب الفقاعة إلى الخلفية عبر خطوط
        // الزخرفة الرفيعة الملاصقة لها حين يكون لونها قريبًا من لون الفقاعة (واتساب ماك الفاتح)
        boolean[] core = new boolean[sw * sh];
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) {
                int c = px[y * sw + x];
                boolean ok = true;
                for (int dy = -1; dy <= 1 && ok; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue;
                        if (!near(px[yy * sw + xx], c)) { ok = false; break; }
                    }
                core[y * sw + x] = ok;
            }
        int[] label = new int[sw * sh];
        int[] stack = new int[sw * sh];
        int next = 0;
        int minW = 26, minH = 14, maxW = (int) (sw * 0.93);
        List<int[]> cands = new ArrayList<>();
        for (int i = 0; i < px.length; i++) {
            if (label[i] != 0 || !core[i]) continue;
            int seed = px[i];
            int id = ++next;
            // ملء بلون البذرة (لا بلون الجار حتى لا ينساب الملء على التدرجات)
            int sp = 0, count = 0, x1 = sw, y1 = sh, x2 = -1, y2 = -1;
            stack[sp++] = i;
            label[i] = id;
            while (sp > 0) {
                int p = stack[--sp];
                count++;
                int x = p % sw, y = p / sw;
                if (x < x1) x1 = x;
                if (x > x2) x2 = x;
                if (y < y1) y1 = y;
                if (y > y2) y2 = y;
                if (x > 0) sp = push(px, core, label, stack, sp, p - 1, seed, id);
                if (x < sw - 1) sp = push(px, core, label, stack, sp, p + 1, seed, id);
                if (y > 0) sp = push(px, core, label, stack, sp, p - sw, seed, id);
                if (y < sh - 1) sp = push(px, core, label, stack, sp, p + sw, seed, id);
            }
            // 12.5: حدود المنطقة بلا «تسرّب» بكسلات متفرقة (زخرفة بلون قريب تلامس الفقاعة): صفوف وأعمدة كثافتها أقل من ثلث الأكثف تُقصّ
            if (DEBUG && count > 300) System.err.println("region seed=" + Integer.toHexString(seed) + " " + x1 + "," + y1 + "-" + x2 + "," + y2 + " count=" + count);
            if (x2 - x1 >= minW && y2 - y1 >= minH) {
                int[] rows = new int[y2 - y1 + 1], cols = new int[x2 - x1 + 1];
                for (int yy = y1; yy <= y2; yy++)
                    for (int xx = x1; xx <= x2; xx++)
                        if (label[yy * sw + xx] == id) {
                            rows[yy - y1]++;
                            cols[xx - x1]++;
                        }
                int mr = 0, mc = 0;
                for (int v : rows) mr = Math.max(mr, v);
                for (int v : cols) mc = Math.max(mc, v);
                int ny1 = y1, ny2 = y2, nx1 = x1, nx2 = x2;
                while (ny1 < ny2 && rows[ny1 - y1] * 3 < mr) ny1++;
                while (ny2 > ny1 && rows[ny2 - y1] * 3 < mr) ny2--;
                while (nx1 < nx2 && cols[nx1 - x1] * 3 < mc) nx1++;
                while (nx2 > nx1 && cols[nx2 - x1] * 3 < mc) nx2--;
                x1 = nx1;
                x2 = nx2;
                y1 = ny1;
                y2 = ny2;
            }
            int bw = x2 - x1 + 1, bh = y2 - y1 + 1;
            if (DEBUG && bw >= minW && bh >= minH) System.err.println("cand " + x1 + "," + y1 + " " + bw + "x" + bh + " count=" + count + " edge=" + edgeCover(label, sw, sh, id, x1, y1, x2, y2));
            if (bw < minW || bh < minH) continue;
            // مناطق الخلفية تمتد من أعلى الإطار إلى أسفله أو تلمس ثلاث حواف. 12.5: الفقاعة العريضة في إطار ضيق تلمس اليمين واليسار معًا
            // (كانت تُرفض فتضيع)، والمقصوصة بالتمرير تلمس الأعلى أو الأسفل
            boolean tl = x1 == 0, tt = y1 == 0, tr = x2 == sw - 1, tb = y2 == sh - 1;
            int touch = (tl ? 1 : 0) + (tt ? 1 : 0) + (tr ? 1 : 0) + (tb ? 1 : 0);
            if ((tt && tb) || touch >= 3 || (tl && tr && bh > sh * 0.8)) continue;
            // زاوية من الخلفية (تلمس حافتين متجاورتين) ليست فقاعة
            if (touch == 2 && !(tl && tr)) continue;
            if (edgeCover(label, sw, sh, id, x1, y1, x2, y2) < 0.7) continue;
            boolean media = count < bw * bh * 0.22;
            // صورة داخل فقاعة: إطار رفيع بلونها حول الصورة (كبيرة بما يكفي حتى لا تكون حرفًا أو أيقونة)
            if (media && (bh < 60 || bw < 60 || count < (bw + bh) * 2)) continue;
            cands.add(new int[]{x1, y1, bw, bh, id, media ? 1 : 0});
        }
        // فقاعة داخل فقاعة (اقتباس الرد، أيقونة الملف) تُترك: نأخذ الخارجية
        List<Box> boxes = new ArrayList<>();
        // اللبّ أصغر من الفقاعة ببكسل من كل جهة
        for (int[] c : cands) {
            int x0 = Math.max(0, c[0] - 1), y0 = Math.max(0, c[1] - 1);
            int x1 = Math.min(sw, c[0] + c[2] + 1), y1 = Math.min(sh, c[1] + c[3] + 1);
            Box bx = new Box(x0 * step, y0 * step, (x1 - x0) * step, (y1 - y0) * step);
            bx.media = c[5] == 1;
            boxes.add(bx);
        }
        for (Box b : boxes) {
            boolean inner = false;
            for (Box o : boxes) if (o != b && o.contains(b) && (o.w > b.w || o.h > b.h)) { inner = true; break; }
            if (!inner) out.add(b);
        }
        out.sort((p, q) -> p.y != q.y ? Integer.compare(p.y, q.y) : Integer.compare(p.x, q.x));
        return out;
    }

    private static int push(int[] px, boolean[] core, int[] label, int[] stack, int sp, int q, int seed, int id) {
        if (label[q] != 0 || !core[q] || !near(px[q], seed)) return sp;
        label[q] = id;
        stack[sp] = q;
        return sp + 1;
    }

    static final int TOL = 3;
    static boolean DEBUG = Boolean.getBoolean("sa.shapes.debug");

    static boolean near(int a, int b) {
        int dr = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255));
        int dg = Math.abs(((a >> 8) & 255) - ((b >> 8) & 255));
        int db = Math.abs((a & 255) - (b & 255));
        // 12.3: لون الفقاعة على الشاشة ثابت تمامًا، فالسماح صغير حتى لا تذوب فقاعة لونها قريب من الخلفية (واتساب ماك الفاتح)
        return dr <= TOL && dg <= TOL && db <= TOL;
    }

    /**
     * أقل نسبة بين الجهات الأربع (بعد إزاحة بكسلين للداخل، ومن دون الزوايا المستديرة) لما هو بلون المنطقة نفسها.
     * الفقاعة جهاتها الأربع مستقيمة بلونها (النص لا يلمس الحافة)، أما الخلفية بزخارفها وشرائط التدرج المائلة والنص والصور فلا.
     * الجهة الملاصقة لحافة الصورة (فقاعة قصّها التمرير) لا تُحسب.
     */
    private static double edgeCover(int[] label, int sw, int sh, int id, int x1, int y1, int x2, int y2) {
        int in = 2;
        int ax = x1 + in, ay = y1 + in, bx = x2 - in, by = y2 - in;
        if (bx - ax < 8 || by - ay < 4) return 0;
        int cut = Math.min(8, Math.min((bx - ax) / 4, (by - ay) / 3));
        double min = 1;
        int[] hit = new int[4], tot = new int[4];
        for (int x = ax + cut; x <= bx - cut; x++) {
            tot[0]++;
            tot[1]++;
            if (label[ay * sw + x] == id) hit[0]++;
            if (label[by * sw + x] == id) hit[1]++;
        }
        for (int y = ay + cut; y <= by - cut; y++) {
            tot[2]++;
            tot[3]++;
            if (label[y * sw + ax] == id) hit[2]++;
            if (label[y * sw + bx] == id) hit[3]++;
        }
        boolean[] border = {y1 == 0, y2 == sh - 1, x1 == 0, x2 == sw - 1};
        if (DEBUG) System.err.println("   sides top=" + hit[0] + "/" + tot[0] + " bottom=" + hit[1] + "/" + tot[1] + " left=" + hit[2] + "/" + tot[2] + " right=" + hit[3] + "/" + tot[3]);
        for (int i = 0; i < 4; i++) {
            if (border[i] || tot[i] == 0) continue;
            min = Math.min(min, hit[i] / (double) tot[i]);
        }
        return min;
    }
}
