package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.graphics.*;

/** أثر اللمس: المحتوى، وفوقه عند الضغط دائرة تتسع بلون الأثر داخل شكل القناع (وتظليل خفيف عند مرور الفأرة). */
public class RippleDrawable extends LayerDrawable {
    public static final int RADIUS_AUTO = -1;
    private ColorStateList color;
    private final Drawable content, mask;
    private boolean pressed, hovered, focused;
    private long pressAt, releaseAt;
    private float hx = -1, hy = -1;
    private int maxRadius = RADIUS_AUTO;

    public RippleDrawable(ColorStateList color, Drawable content, Drawable mask) {
        super(content == null ? new Drawable[0] : new Drawable[]{content});
        this.color = color;
        this.content = content;
        this.mask = mask;
        if (mask != null) mask.setCallback(this);
    }
    public void setColor(ColorStateList c) { color = c; invalidateSelf(); }
    public void setRadius(int r) { maxRadius = r; }
    @Override public void setHotspot(float x, float y) { hx = x; hy = y; }
    @Override protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        if (mask != null) mask.setBounds(b);
    }
    @Override public boolean isStateful() { return true; }
    @Override public boolean setState(int[] s) {
        boolean ch = super.setState(s);
        boolean p = has(s, android.R.attr.state_pressed), h = has(s, android.R.attr.state_hovered), f = has(s, android.R.attr.state_focused);
        if (p != pressed) {
            pressed = p;
            if (p) pressAt = System.currentTimeMillis();
            else releaseAt = System.currentTimeMillis();
            ch = true;
        }
        if (h != hovered || f != focused) { hovered = h; focused = f; ch = true; }
        return ch;
    }
    @Override public void jumpToCurrentState() { releaseAt = 0; }
    @Override public java.awt.Shape outline() {
        if (content != null) return content.outline();
        return null;
    }

    @Override public void draw(Canvas c) {
        super.draw(c);
        long now = System.currentTimeMillis();
        int rc = color == null ? 0x22000000 : color.getDefaultColor();
        float level = 0;
        boolean anim = false;
        float grow = 1f;
        if (pressed) {
            float t = Math.min(1f, (now - pressAt) / 220f);
            level = 1f;
            grow = 0.35f + 0.65f * (1 - (1 - t) * (1 - t));
            anim = t < 1f;
        } else if (releaseAt > 0 && now - releaseAt < 260) {
            float t = (now - releaseAt) / 260f;
            level = 1f - t;
            anim = true;
        }
        if (hovered && level < 0.45f) { level = Math.max(level, 0.45f); grow = 1f; }
        if (level <= 0.01f) return;
        Rect b = getBounds();
        if (b.isEmpty()) return;
        java.awt.Graphics2D g = c.graphics();
        java.awt.Shape old = g.getClip();
        java.awt.Shape clip = mask != null ? mask.outline() : content != null ? content.outline() : null;
        if (clip == null && mask == null && content == null) {
            // أثر بلا حدود: دائرة حول المركز
            float r = Math.max(b.width(), b.height()) * 0.6f;
            clip = new java.awt.geom.Ellipse2D.Float(b.exactCenterX() - r, b.exactCenterY() - r, 2 * r, 2 * r);
        }
        if (clip == null) clip = new java.awt.geom.Rectangle2D.Float(b.left, b.top, b.width(), b.height());
        g.clip(clip);
        int a = Math.round(Color.alpha(rc) * level * c.alpha * 1.6f);
        g.setPaint(new java.awt.Color(Color.red(rc), Color.green(rc), Color.blue(rc), Math.min(255, a)));
        float cx = hx >= 0 ? hx : b.exactCenterX(), cy = hy >= 0 ? hy : b.exactCenterY();
        float far = (float) Math.hypot(Math.max(cx - b.left, b.right - cx), Math.max(cy - b.top, b.bottom - cy));
        if (maxRadius > 0) far = maxRadius;
        float r = far * grow;
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        g.setClip(old);
        if (anim) invalidateSelf();
    }
}
