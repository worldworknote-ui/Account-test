package android.content;

import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * حافظة النظام: النص العادي، و12.1: نسخة HTML (واتساب ديسكتوب والمتصفح يضعان كل فقاعة في عنصر مستقل)،
 * وتنبيه تغيّر الحافظة (يفحصها كل ثانية تقريبًا ما دام هناك مستمع؛ الماك وويندوز لا يرسلان تنبيهًا لـJava).
 */
public class ClipboardManager extends android.text.ClipboardManager {
    public interface OnPrimaryClipChangedListener { void onPrimaryClipChanged(); }

    private static final DataFlavor[] HTML = htmlFlavors();
    private static final List<OnPrimaryClipChangedListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static javax.swing.Timer poll;
    private static String lastSeen;
    /** ما نسخه البرنامج نفسه: لا يُعدّ تغيّرًا من الخارج. */
    private static String ownText;

    private static DataFlavor[] htmlFlavors() {
        List<DataFlavor> l = new ArrayList<>();
        try { l.add(new DataFlavor("text/html;class=java.lang.String;document=all")); } catch (Exception ignored) { }
        try { l.add(new DataFlavor("text/html;class=java.lang.String;document=fragment")); } catch (Exception ignored) { }
        try { l.add(new DataFlavor("text/html;class=java.lang.String")); } catch (Exception ignored) { }
        return l.toArray(new DataFlavor[0]);
    }

    private Clipboard cb() { return Toolkit.getDefaultToolkit().getSystemClipboard(); }

    public void setPrimaryClip(ClipData c) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < c.getItemCount(); i++) { if (i > 0) b.append('\n'); b.append(c.getItemAt(i).coerceToText(null)); }
        try {
            ownText = b.toString();
            lastSeen = ownText;
            cb().setContents(new StringSelection(b.toString()), null);
        } catch (Exception ignored) { }
    }

    public ClipData getPrimaryClip() {
        String s = text();
        if (s == null) return null;
        String h = html();
        return h == null ? ClipData.newPlainText("", s) : ClipData.newHtmlText("", s, h);
    }

    public ClipDescription getPrimaryClipDescription() {
        if (!hasPrimaryClip()) return null;
        List<String> m = new ArrayList<>();
        m.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
        if (html() != null) m.add(ClipDescription.MIMETYPE_TEXT_HTML);
        return new ClipDescription("", m.toArray(new String[0]));
    }

    public boolean hasPrimaryClip() { return text() != null; }
    public boolean hasText() { return hasPrimaryClip(); }
    public CharSequence getText() { return text(); }
    public void setText(CharSequence t) { setPrimaryClip(ClipData.newPlainText("", t)); }
    public void clearPrimaryClip() { try { cb().setContents(new StringSelection(""), null); } catch (Exception ignored) { } }

    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener l) {
        if (l == null || LISTENERS.contains(l)) return;
        LISTENERS.add(l);
        startPoll();
    }

    public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener l) {
        LISTENERS.remove(l);
        if (LISTENERS.isEmpty() && poll != null) { poll.stop(); poll = null; }
    }

    private void startPoll() {
        if (poll != null || java.awt.GraphicsEnvironment.isHeadless()) return;
        lastSeen = text();
        poll = new javax.swing.Timer(900, e -> {
            String now = text();
            if (now == null || now.equals(lastSeen)) return;
            lastSeen = now;
            if (now.equals(ownText)) return;
            for (OnPrimaryClipChangedListener x : LISTENERS) {
                try { x.onPrimaryClipChanged(); } catch (Throwable ignored) { }
            }
        });
        poll.start();
    }

    /** أنواع ما في الحافظة كما يسميها النظام (لـ«فحص الحافظة»). */
    public static List<String> flavors() {
        List<String> out = new ArrayList<>();
        try {
            for (DataFlavor f : Toolkit.getDefaultToolkit().getSystemClipboard().getAvailableDataFlavors()) {
                String s = f.getMimeType();
                int semi = s.indexOf(';');
                String base = semi < 0 ? s : s.substring(0, semi);
                if (!out.contains(base)) out.add(base);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private String text() {
        try {
            Transferable t = cb().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) return (String) t.getTransferData(DataFlavor.stringFlavor);
        } catch (Exception ignored) { }
        return null;
    }

    private String html() {
        try {
            Transferable t = cb().getContents(null);
            if (t == null) return null;
            for (DataFlavor f : HTML) {
                if (!t.isDataFlavorSupported(f)) continue;
                Object o = t.getTransferData(f);
                if (o instanceof String && !((String) o).trim().isEmpty()) return (String) o;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
