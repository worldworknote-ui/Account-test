package com.smart.hawala;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * سجل العمليات المتسلسل (منطق بحت): بصمة كل سطر تشمل بصمة السطر الذي قبله، فحذف سطر أو تعديله أو تغيير ترتيبه
 * يكسر السلسلة من موضعه ويظهر في «فحص سجل العمليات». البصمة على النصوص الأصلية (لا المشفّرة)
 * فتبقى صحيحة بعد النسخ الاحتياطي والاستعادة على هاتف آخر.
 * الأسطر الأقدم من 8.0 بلا بصمة: تُعدّ «قبل التسلسل» ولا تُحسب خللًا.
 */
public final class AuditChain {
    private AuditChain() {}

    public static final class Row {
        public long id, entityId, createdAt;
        public String action, entity, user, reason, prev, hash;
    }

    /** SHA-256 لحقول مسبوقة بأطوالها (لا لبس بين «أ|ب» و«أ» + «|ب»). */
    public static String hash(String prev, String action, String entity, long entityId, String user, String reason, long createdAt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder b = new StringBuilder("SA-AUDIT-1");
            for (String s : new String[]{prev, action, entity, Long.toString(entityId), user, reason, Long.toString(createdAt)}) {
                String v = s == null ? "" : s;
                b.append('|').append(v.length()).append(':').append(v);
            }
            return BackupFormat.hex(md.digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(Row r) { return hash(r.prev, r.action, r.entity, r.entityId, r.user, r.reason, r.createdAt); }

    public static final class Result {
        /** أسطر قبل بدء التسلسل (من إصدارات أقدم). */
        public int legacy;
        /** أسطر متسلسلة سليمة. */
        public int ok;
        /** أول سطر انكسرت عنده السلسلة، أو -1. */
        public long brokenAt = -1;
        public String problem = "";

        public boolean intact() { return brokenAt < 0 && problem.isEmpty(); }
    }

    /**
     * يتحقق من الأسطر بترتيب رقمها. head = آخر بصمة يحفظها الهاتف (فارغ = غير معروف، مثلًا بعد نقل البيانات):
     * إن اختلفت عن بصمة آخر سطر فقد حُذفت أسطر من آخر السجل.
     */
    public static Result verify(List<Row> rows, String head) { return verify(rows, head, null); }

    /**
     * 12.7: يكمل التحقق من نقطة تحقّق سابقة (startPrev = بصمة آخر سطر فُحص وسَلِم): الفحص اليومي يقرأ الأسطر الجديدة فقط.
     * null = من أول السجل.
     */
    public static Result verify(List<Row> rows, String head, String startPrev) {
        Result res = new Result();
        String last = startPrev;
        boolean started = startPrev != null;
        for (Row r : rows) {
            if (r.hash == null || r.hash.isEmpty()) {
                if (!started) {
                    res.legacy++;
                    continue;
                }
                res.brokenAt = r.id;
                res.problem = "سطر بلا بصمة بعد بدء التسلسل (أُضيف من خارج التطبيق)";
                return res;
            }
            // أول سطر متسلسل يبدأ من فراغ؛ وكل سطر بعده يحمل بصمة الذي قبله
            String expectPrev = started ? last : "";
            if (!expectPrev.equals(r.prev == null ? "" : r.prev)) {
                res.brokenAt = r.id;
                res.problem = started ? "سطر محذوف أو مُعاد ترتيبه قبل هذا السطر" : "أسطر محذوفة من بداية السجل المتسلسل";
                return res;
            }
            if (!hash(r).equals(r.hash)) {
                res.brokenAt = r.id;
                res.problem = "محتوى السطر عُدّل بعد كتابته";
                return res;
            }
            started = true;
            last = r.hash;
            res.ok++;
        }
        if (head != null && !head.isEmpty() && last != null && !head.equals(last)) {
            res.problem = "آخر السجل لا يطابق ما حفظه الهاتف: حُذفت أسطر من نهايته أو استُبدل السجل";
        }
        return res;
    }
}
