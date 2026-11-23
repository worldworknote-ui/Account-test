package android.content;

import java.util.ArrayList;
import java.util.List;

public class ClipData {
    public static class Item {
        final CharSequence text; final android.net.Uri uri; final Intent intent; String html;
        public Item(CharSequence t) { text = t; uri = null; intent = null; }
        /** 12.1: نسخة HTML من الحافظة (واتساب ديسكتوب والمتصفح). */
        public Item(CharSequence t, String htmlText) { text = t; uri = null; intent = null; html = htmlText; }
        public String getHtmlText() { return html; }
        public Item(android.net.Uri u) { text = null; uri = u; intent = null; }
        public Item(Intent i) { text = null; uri = null; intent = i; }
        public Item(CharSequence t, Intent i, android.net.Uri u) { text = t; intent = i; uri = u; }
        public CharSequence getText() { return text; }
        public android.net.Uri getUri() { return uri; }
        public Intent getIntent() { return intent; }
        public CharSequence coerceToText(Context c) { return text != null ? text : uri != null ? uri.toString() : ""; }
    }
    private final ClipDescription desc;
    private final List<Item> items = new ArrayList<>();
    public ClipData(CharSequence label, String[] mime, Item item) { desc = new ClipDescription(label, mime); items.add(item); }
    public ClipData(ClipDescription d, Item item) { desc = d; items.add(item); }
    public static ClipData newPlainText(CharSequence label, CharSequence text) { return new ClipData(label, new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}, new Item(text)); }
    public static ClipData newRawUri(CharSequence label, android.net.Uri u) { return new ClipData(label, new String[]{"text/uri-list"}, new Item(u)); }
    public static ClipData newUri(ContentResolver r, CharSequence label, android.net.Uri u) { return newRawUri(label, u); }
    public static ClipData newHtmlText(CharSequence label, CharSequence text, String html) { return new ClipData(label, new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN, ClipDescription.MIMETYPE_TEXT_HTML}, new Item(text, html)); }
    public void addItem(Item i) { items.add(i); }
    public int getItemCount() { return items.size(); }
    public Item getItemAt(int i) { return items.get(i); }
    public ClipDescription getDescription() { return desc; }
}
