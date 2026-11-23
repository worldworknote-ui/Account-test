package com.smart.hawala;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * 12.1: قراءة الحافظة بكل صيغها (نص عادي + HTML) وتحويلها إلى رسائل.
 * الترتيب: رؤوس واتساب في النص العادي (أندرويد وماك) ← فقاعات HTML (ويندوز والمتصفح) ← أسطر الوقت ← رسالة واحدة.
 */
public final class WaClip {
    private WaClip() {}

    public static final class Read {
        public final String text, html;
        public final List<String> mimes;

        public Read(String text, String html, List<String> mimes) {
            this.text = text == null ? "" : text;
            this.html = html;
            this.mimes = mimes == null ? new ArrayList<>() : mimes;
        }

        public boolean empty() { return text.trim().isEmpty() && (html == null || html.trim().isEmpty()); }
    }

    public static Read read(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return new Read("", null, null);
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return new Read("", null, null);
            List<String> mimes = new ArrayList<>();
            ClipDescription desc = d.getDescription();
            if (desc != null) for (int i = 0; i < desc.getMimeTypeCount(); i++) mimes.add(desc.getMimeType(i));
            ClipData.Item it = d.getItemAt(0);
            CharSequence cs = it.coerceToText(c);
            String html = null;
            try {
                html = it.getHtmlText();
            } catch (Throwable ignored) {
                html = null;
            }
            return new Read(cs == null ? "" : cs.toString(), html, mimes);
        } catch (Exception e) {
            return new Read("", null, null);
        }
    }

    /** الرسائل من الحافظة. */
    public static List<WaText.Msg> messages(String text, String html) {
        List<WaText.Msg> plain = WaText.split(text == null ? "" : text);
        if (plain.size() > 1) return plain;
        List<WaText.Msg> h = WaHtml.messages(html);
        if (h != null && h.size() > 1) return h;
        List<WaText.Msg> cut = WaCuts.auto(text == null ? "" : text);
        if (cut.size() > 1) return cut;
        if (plain.isEmpty() && h != null) return h;
        return plain;
    }

    /** نص رسالة واحدة قد يكون فيه أكثر من رسالة ملزوقة (أسطر فارغة أو أكثر من مبلغ): نعرض «القص» اليدوي. */
    public static boolean maybeGlued(String text) {
        List<String> l = WaCuts.lines(text);
        if (l.size() < 3) return false;
        return !WaCuts.suggest(l).isEmpty();
    }
}
