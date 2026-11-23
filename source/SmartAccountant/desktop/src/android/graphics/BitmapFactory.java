package android.graphics;

import java.awt.image.BufferedImage;
import java.io.*;

public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds, inMutable, inScaled = true;
        public int inSampleSize = 1, outWidth, outHeight, inDensity, inTargetDensity;
        public String outMimeType;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    public static Bitmap decodeByteArray(byte[] b, int off, int len) { return decodeByteArray(b, off, len, null); }
    public static Bitmap decodeByteArray(byte[] b, int off, int len, Options o) {
        return decodeStream(new ByteArrayInputStream(b, off, len), null, o);
    }
    public static Bitmap decodeFile(String path) { return decodeFile(path, null); }
    public static Bitmap decodeFile(String path, Options o) {
        try (InputStream in = new FileInputStream(path)) { return decodeStream(in, null, o); } catch (IOException e) { return null; }
    }
    public static Bitmap decodeStream(InputStream in) { return decodeStream(in, null, null); }
    public static Bitmap decodeStream(InputStream in, Rect pad, Options o) {
        try {
            byte[] data = in.readAllBytes();
            BufferedImage im = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
            if (im == null) return null;
            if (o != null) {
                o.outWidth = im.getWidth();
                o.outHeight = im.getHeight();
                o.outMimeType = data.length > 3 && (data[0] & 0xFF) == 0xFF ? "image/jpeg" : "image/png";
                if (o.inJustDecodeBounds) return null;
                if (o.inSampleSize > 1) {
                    Bitmap b = new Bitmap(argb(im));
                    return Bitmap.createScaledBitmap(b, Math.max(1, im.getWidth() / o.inSampleSize), Math.max(1, im.getHeight() / o.inSampleSize), true);
                }
            }
            return new Bitmap(argb(im));
        } catch (Exception e) {
            return null;
        }
    }
    public static Bitmap decodeFileDescriptor(FileDescriptor fd, Rect pad, Options o) {
        try (InputStream in = new FileInputStream(fd)) { return decodeStream(in, pad, o); } catch (IOException e) { return null; }
    }
    public static Bitmap decodeResource(android.content.res.Resources r, int id) {
        android.graphics.drawable.Drawable d = r.getDrawable(id, null);
        if (d == null) return null;
        int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    static BufferedImage argb(BufferedImage im) {
        if (im.getType() == BufferedImage.TYPE_INT_ARGB) return im;
        BufferedImage o = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = o.createGraphics();
        g.drawImage(im, 0, 0, null);
        g.dispose();
        return o;
    }
}
