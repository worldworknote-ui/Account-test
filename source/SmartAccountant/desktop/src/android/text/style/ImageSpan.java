package android.text.style;
public class ImageSpan extends CharacterStyle {
    final android.graphics.drawable.Drawable d;
    public ImageSpan(android.graphics.drawable.Drawable d) { this.d = d; }
    public ImageSpan(android.graphics.drawable.Drawable d, int align) { this.d = d; }
    public ImageSpan(android.content.Context c, int res) { this.d = c.getDrawable(res); }
    public android.graphics.drawable.Drawable getDrawable() { return d; }
}
