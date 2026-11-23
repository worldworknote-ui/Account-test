package android.text.style;
public class URLSpan extends ClickableSpan {
    final String url;
    public URLSpan(String u) { url = u; }
    public String getURL() { return url; }
    @Override public void onClick(android.view.View v) { try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); } catch (Exception ignored) { } }
}
