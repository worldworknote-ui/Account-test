package android.graphics;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** طمس صندوقي ثلاثي التمرير (قريب من الطمس الغاوسي) للظلال والتوهج. */
public final class Blur {
    private Blur() {}

    public static void box(BufferedImage im, int r) {
        if (r < 1) return;
        int w = im.getWidth(), h = im.getHeight();
        int[] px = ((DataBufferInt) im.getRaster().getDataBuffer()).getData();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            blurH(px, tmp, w, h, r);
            blurV(tmp, px, w, h, r);
        }
    }

    private static void blurH(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + clamp(i, w)];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = (int) ((sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div));
                int a = in[row + clamp(x - r, w)], b = in[row + clamp(x + r + 1, w)];
                sa += (b >>> 24) - (a >>> 24); sr += ((b >> 16) & 255) - ((a >> 16) & 255);
                sg += ((b >> 8) & 255) - ((a >> 8) & 255); sb += (b & 255) - (a & 255);
            }
        }
    }

    private static void blurV(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[clamp(i, h) * w + x];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = (int) ((sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div));
                int a = in[clamp(y - r, h) * w + x], b = in[clamp(y + r + 1, h) * w + x];
                sa += (b >>> 24) - (a >>> 24); sr += ((b >> 16) & 255) - ((a >> 16) & 255);
                sg += ((b >> 8) & 255) - ((a >> 8) & 255); sb += (b & 255) - (a & 255);
            }
        }
    }

    private static int clamp(int v, int n) { return v < 0 ? 0 : v >= n ? n - 1 : v; }
}
