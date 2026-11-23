package com.smart.hawala;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 8.0 (ت5): بصمة اليوم (منطق بحت). ملخص قانوني لحركات يوم واحد (كل الحالات، مرتبة برقمها) يُوقَّع بـ HMAC بسرّ المكتب،
 * فيُطبع رمز قصير على التقرير اليومي المصدَّر. من يعدّل رقمًا في الملف بعد خروجه من الهاتف لا يستطيع إنتاج رمز صحيح،
 * والتطبيق يعيد الحساب ويقارن. إلغاء حركة أو تعديلها لاحقًا يغيّر بصمة يومها (وهذا مقصود: يدل على تغيير بعد الإقفال).
 */
public final class DayClose {
    private DayClose() {}

    private static final String B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** النص القانوني: سطر لكل حركة، والحقول مسبوقة بأطوالها. */
    public static String canonical(String day, List<Model.Tx> txs) {
        List<Model.Tx> l = new ArrayList<>(txs);
        Collections.sort(l, (x, y) -> Long.compare(x.id, y.id));
        StringBuilder b = new StringBuilder("SA-DAY-1|").append(day).append('|').append(l.size());
        for (Model.Tx t : l) {
            b.append('\n');
            for (String s : new String[]{t.uuid, t.type, Long.toString(t.amount), t.currency, t.feeType, Long.toString(t.fee), t.feeCurrency,
                    t.status, Long.toString(t.createdAt)}) {
                String v = s == null ? "" : s;
                b.append(v.length()).append(':').append(v).append('|');
            }
        }
        return b.toString();
    }

    /** رمز من 12 حرفًا (60 بتًا) بصيغة XXXX-XXXX-XXXX، بلا حروف ملتبسة. */
    public static String code(byte[] key, String canonical) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = m.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xff);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                if (i > 0 && i % 4 == 0) s.append('-');
                s.append(B32.charAt((int) ((v >>> (59 - 5 * i)) & 31)));
            }
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** يوحّد ما يكتبه المستخدم: أحرف كبيرة، بلا مسافات أو شرطات (الأبجدية بلا 0 و1 وO وI أصلًا). */
    public static String normalize(String typed) {
        if (typed == null) return "";
        StringBuilder s = new StringBuilder();
        for (char ch : typed.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (ch >= 0x0660 && ch <= 0x0669) ch = (char) ('0' + ch - 0x0660);
            if (B32.indexOf(ch) >= 0) s.append(ch);
        }
        String r = s.toString();
        return r.length() == 12 ? r.substring(0, 4) + "-" + r.substring(4, 8) + "-" + r.substring(8) : r;
    }
}
