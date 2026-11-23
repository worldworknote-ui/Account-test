package android.graphics;

import java.awt.image.BufferedImage;
import java.io.OutputStream;

public final class Bitmap {
    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, HARDWARE, RGBA_F16 }
    public enum CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    public BufferedImage img;
    private boolean recycled;
    private int density = 160;

    public Bitmap(BufferedImage img) { this.img = img; }

    public static Bitmap createBitmap(int w, int h, Config c) {
        return new Bitmap(new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB));
    }
    public static Bitmap createBitmap(Bitmap src) { return createBitmap(src, 0, 0, src.getWidth(), src.getHeight()); }
    public static Bitmap createBitmap(Bitmap src, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src.img, -x, -y, null);
        g.dispose();
        return new Bitmap(out);
    }
    public static Bitmap createBitmap(Bitmap src, int x, int y, int w, int h, Matrix m, boolean filter) {
        if (m == null || m.isIdentity()) return createBitmap(src, x, y, w, h);
        RectF r = new RectF(0, 0, w, h);
        Matrix mm = new Matrix(m);
        mm.mapRect(r);
        int ow = Math.max(1, Math.round(r.width())), oh = Math.max(1, Math.round(r.height()));
        BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
        at.translate(-r.left, -r.top);
        at.concatenate(m.t);
        at.translate(-x, -y);
        g.drawImage(src.img, at, null);
        g.dispose();
        return new Bitmap(out);
    }
    public static Bitmap createScaledBitmap(Bitmap src, int w, int h, boolean filter) {
        w = Math.max(1, w); h = Math.max(1, h);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src.img, 0, 0, w, h, null);
        g.dispose();
        return new Bitmap(out);
    }

    public int getWidth() { return img.getWidth(); }
    public int getHeight() { return img.getHeight(); }
    public int getByteCount() { return getWidth() * getHeight() * 4; }
    public int getAllocationByteCount() { return getByteCount(); }
    public Config getConfig() { return Config.ARGB_8888; }
    public boolean hasAlpha() { return img.getColorModel().hasAlpha(); }
    public void setHasAlpha(boolean b) {}
    public boolean isRecycled() { return recycled; }
    public void recycle() { recycled = true; }
    public boolean isMutable() { return true; }
    public Bitmap copy(Config c, boolean mutable) { return createBitmap(this); }
    public void setDensity(int d) { density = d; }
    public int getDensity() { return density; }
    public int getPixel(int x, int y) { return img.getRGB(x, y); }
    public void setPixel(int x, int y, int c) { img.setRGB(x, y, c); }
    public void getPixels(int[] px, int off, int stride, int x, int y, int w, int h) { img.getRGB(x, y, w, h, px, off, stride); }
    public void setPixels(int[] px, int off, int stride, int x, int y, int w, int h) { img.setRGB(x, y, w, h, px, off, stride); }
    public void eraseColor(int c) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new java.awt.Color(c, true));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
    }

    public boolean compress(CompressFormat f, int quality, OutputStream out) {
        try {
            if (f == CompressFormat.JPEG || f == CompressFormat.WEBP_LOSSY || (f == CompressFormat.WEBP && quality < 100)) {
                BufferedImage rgb = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = rgb.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();
                javax.imageio.ImageWriter w = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
                javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(Math.max(0.05f, Math.min(1f, quality / 100f)));
                try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(out)) {
                    w.setOutput(ios);
                    w.write(null, new javax.imageio.IIOImage(rgb, null, null), p);
                }
                w.dispose();
                return true;
            }
            return javax.imageio.ImageIO.write(img, "png", out);
        } catch (Exception e) {
            return false;
        }
    }
}
