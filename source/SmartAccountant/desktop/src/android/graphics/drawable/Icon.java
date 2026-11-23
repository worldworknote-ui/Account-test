package android.graphics.drawable;
public class Icon {
    final int res;
    private Icon(int res) { this.res = res; }
    public static Icon createWithResource(android.content.Context c, int res) { return new Icon(res); }
    public static Icon createWithBitmap(android.graphics.Bitmap b) { return new Icon(0); }
}
