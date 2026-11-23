package android.view;

import android.graphics.Blur;
import android.graphics.Canvas;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** ظل الارتفاع (Material): ظل محيط خفيف + ظل منسدل للأسفل، من الشكل الخارجي للعنصر، مع ذاكرة للصور. */
final class Shadows {
    private Shadows() {}

    private static final Map<String, BufferedImage> cache = new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 160; }
    };

    static void draw(Canvas c, View v, float z, float alpha) {
        Shape s = v.outlineShape();
        if (s == null) return;
        Rectangle2D b = s.getBounds2D();
        if (b.getWidth() < 1 || b.getHeight() < 1) return;
        float blur = Math.min(40f, z * 0.9f + 1.5f);
        float dy = Math.min(30f, z * 0.45f);
        int pad = (int) Math.ceil(blur * 2) + 2;
        String key = shapeKey(s, b) + "|" + Math.round(blur * 2);
        BufferedImage im;
        synchronized (cache) { im = cache.get(key); }
        if (im == null) {
            int w = (int) Math.ceil(b.getWidth()) + 2 * pad, h = (int) Math.ceil(b.getHeight()) + 2 * pad;
            if ((long) w * h > 3_000_000L) return;
            im = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = im.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(pad - b.getX(), pad - b.getY());
            g.setColor(new Color(0, 0, 0, 255));
            g.fill(s);
            g.dispose();
            Blur.box(im, Math.max(1, Math.round(blur / 2f)));
            synchronized (cache) { cache.put(key, im); }
        }
        float a = alpha * c.alpha;
        Graphics2D g = c.graphics();
        Composite old = g.getComposite();
        // ظل منسدل (أقوى) + ظل محيط (أخف)
        float key1 = Math.min(0.30f, 0.12f + z * 0.006f) * a;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, key1))));
        g.drawImage(im, (int) Math.round(b.getX() - pad), (int) Math.round(b.getY() - pad + dy), null);
        float amb = Math.min(0.16f, 0.06f + z * 0.003f) * a;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, amb))));
        g.drawImage(im, (int) Math.round(b.getX() - pad), (int) Math.round(b.getY() - pad + dy * 0.2), null);
        g.setComposite(old);
    }

    private static String shapeKey(Shape s, Rectangle2D b) {
        String sz = Math.round(b.getWidth()) + "x" + Math.round(b.getHeight());
        if (s instanceof RoundRectangle2D) return "rr" + sz + ":" + Math.round(((RoundRectangle2D) s).getArcWidth());
        if (s instanceof Ellipse2D) return "e" + sz;
        if (s instanceof Rectangle2D) return "r" + sz;
        StringBuilder k = new StringBuilder("p" + sz + ":");
        PathIterator it = s.getPathIterator(null);
        float[] f = new float[6];
        int n = 0;
        while (!it.isDone() && n < 40) {
            int t = it.currentSegment(f);
            k.append(t).append(Math.round(f[0])).append(',').append(Math.round(f[1])).append(';');
            it.next();
            n++;
        }
        return k.toString();
    }
}
