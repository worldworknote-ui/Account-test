package com.smart.hawala;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONObject;

/**
 * خطة «إضافة ملف النقل إلى البيانات الحالية» بدل استبدالها. منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر.
 * تقرأ جداول ملف أداة النقل، وتطابق مكاتبه بمكاتب التطبيق بالاسم، وتحوّل المبالغ إلى منازل عملات التطبيق،
 * وتحسب لكل مكتب وعملة: الرصيد الحالي، وما سيُضاف (من قيود الملف نفسها)، والرصيد بعد الإضافة.
 * لا تكتب شيئًا: الكتابة في Db.mergeMigration التي تتحقق بعدها أن كل رصيد تغيّر بالمقدار المتوقع تمامًا.
 */
public final class MergePlan {

    /** حالة التطبيق الحالية اللازمة للتخطيط. */
    public static final class Existing {
        public final Map<Long, String> offices = new LinkedHashMap<>();
        public final Map<String, Integer> decimals = new HashMap<>();
        /** «رقم المكتب|العملة» ← الرصيد (موجب = لنا). */
        public final Map<String, Long> balances = new HashMap<>();
        public final Set<String> uuids = new HashSet<>();
        public final Set<String> mergedFiles = new HashSet<>();
        public long lockBefore;
    }

    public static final class Office {
        public long fileId;
        public String name = "", phone = "", notes = "";
        /** مكتب التطبيق الذي يُضاف إليه، أو null = مكتب جديد. */
        public Long targetId;
        public String targetName = "";
        /** مطابقة بعد توحيد الهمزات والتاء المربوطة والمسافات، لا بالكتابة نفسها. */
        public boolean approximate;
        public final Set<String> codes = new java.util.TreeSet<>();
    }

    public static final class NewCurrency {
        public String code, name;
        public int decimals;
    }

    public static final class Opening {
        public long fileOffice, when;
        public String code;
        /** موجب = لنا، بمنازل عملة التطبيق. */
        public long signed;
    }

    public static final class Tx {
        public String uuid, type, person, currency, feeType, feeCurrency, description, notes, userName;
        public long fileOffice, amount, fee, when, fileId;
    }

    /** سطر في المعاينة: مكتب وعملة. */
    public static final class Row {
        public long fileOffice;
        public String office, code;
        public boolean isNew, approximate;
        public int decimals;
        public long current, added, after, txCount;
    }

    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final Map<Long, Office> offices = new LinkedHashMap<>();
    public final List<NewCurrency> newCurrencies = new ArrayList<>();
    public final List<Opening> openings = new ArrayList<>();
    public final List<Tx> txs = new ArrayList<>();
    public final List<Row> rows = new ArrayList<>();
    /** «مكتب الملف|العملة» ← صافي قيود الملف لحساب المكتب (مدين − دائن) بمنازل التطبيق. */
    public final Map<String, Long> expected = new TreeMap<>();
    public String fingerprint = "";
    public int newOffices, matchedOffices;

    public boolean ok() { return errors.isEmpty(); }

    /** 9.0: مكاتب جديدة استُبعدت باختيار المستخدم (حد النسخة المجانية). */
    public int droppedOffices;

    /**
     * 10.16: «اعتماد رصيد الملف» بدل جمعه: للمكتب الموجود يُلغى رصيده الحالي بقيد تسوية ثم تُضاف أرصدة الملف وحركاته،
     * فيصبح رصيده = رصيد الملف تمامًا (لا رصيد قديم + رصيد جديد). لا يُحذف شيء من الدفتر؛ العملات غير الواردة في الملف لا تُمس.
     */
    public boolean adopt;

    public void setAdopt(boolean on) {
        adopt = on;
        for (Row r : rows) r.after = on && !r.isNew ? r.added : r.current + r.added;
    }

    /** كم سطرًا (مكتب موجود + عملة) عليه رصيد حالي سيتأثر بخيار الاعتماد. */
    public int adoptable() {
        int n = 0;
        for (Row r : rows) if (!r.isNew && r.current != 0) n++;
        return n;
    }

    private MergePlan() {}

    /** المكاتب الجديدة في الملف (التي ستأخذ مكانًا في التطبيق). */
    public List<Office> newOfficeList() {
        List<Office> l = new ArrayList<>();
        for (Office o : offices.values()) if (o.targetId == null) l.add(o);
        return l;
    }

    /**
     * 9.0: النسخة المجانية: يُبقي من المكاتب الجديدة ما اختاره المستخدم فقط، مع حركاتها وأرصدتها السابقة كاملة.
     * المكاتب الموجودة في التطبيق (تُضاف إليها الأرصدة) لا تأخذ مكانًا فتبقى كلها. المتوقع بعد الإضافة يُحسب للباقي فقط.
     */
    public void keepNew(Set<Long> keep) {
        final Set<Long> drop = new HashSet<>();
        for (Office o : offices.values()) if (o.targetId == null && !keep.contains(o.fileId)) drop.add(o.fileId);
        if (drop.isEmpty()) return;
        offices.keySet().removeAll(drop);
        txs.removeIf(x -> drop.contains(x.fileOffice));
        openings.removeIf(o -> drop.contains(o.fileOffice));
        expected.keySet().removeIf(k -> drop.contains(Long.parseLong(k.split("\\|", 2)[0])));
        rows.removeIf(r -> drop.contains(r.fileOffice));
        newOffices -= drop.size();
        droppedOffices += drop.size();
        if (txs.isEmpty() && openings.isEmpty()) errors.add("لم يبقَ في الاختيار أرصدة ولا حركات.");
    }

    // ===================================================================================
    // توحيد الأسماء
    // ===================================================================================

    static String exactKey(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.trim().toCharArray()) {
            if (ch == 'ـ' || (ch >= 'ً' && ch <= 'ْ') || ch == '\u200E' || ch == '\u200F') continue;
            b.append(Character.isWhitespace(ch) ? ' ' : Character.toLowerCase(ch));
        }
        return b.toString().replaceAll(" +", " ").trim();
    }

    static String looseKey(String s) {
        return exactKey(s).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي').replace(" ", "");
    }

    // ===================================================================================
    // تحويل المنازل العشرية
    // ===================================================================================

    /** يحوّل مبلغًا من منازل الملف إلى منازل التطبيق؛ null إن لم يكن ممكنًا دون تقريب. */
    static Long convert(long minor, int from, int to) {
        if (from == to) return minor;
        BigInteger v = BigInteger.valueOf(minor);
        if (to > from) {
            BigInteger r = v.multiply(BigInteger.TEN.pow(to - from));
            return r.bitLength() < 63 ? r.longValue() : null;
        }
        BigInteger[] qr = v.divideAndRemainder(BigInteger.TEN.pow(from - to));
        return qr[1].signum() == 0 ? qr[0].longValue() : null;
    }

    // ===================================================================================
    // البناء
    // ===================================================================================

    public static MergePlan build(Map<String, List<JSONObject>> tables, JSONObject manifest, Existing ex) {
        MergePlan p = new MergePlan();
        if (manifest == null || !"migration".equals(manifest.optString("source"))) {
            p.errors.add("هذا الملف ليس من أداة النقل؛ الإضافة متاحة لملفات أداة النقل فقط. النسخ الاحتياطية العادية تُستعاد استعادة كاملة.");
            return p;
        }
        List<JSONObject> txRows = list(tables, "tx");
        List<JSONObject> entries = list(tables, "journal_entries");
        List<JSONObject> lines = list(tables, "journal_lines");
        if (!list(tables, "reconciliations").isEmpty() || tables.containsKey("users") && !list(tables, "users").isEmpty()) {
            p.errors.add("الملف فيه مطابقات أو مستخدمون؛ لا يُضاف إلى البيانات الحالية.");
        }
        for (JSONObject e : entries) {
            String k = e.optString("kind");
            if (!"OPENING".equals(k) && !"PRINCIPAL".equals(k) && !"FEE".equals(k)) {
                p.errors.add("الملف فيه قيود من نوع «" + k + "» (إلغاء أو تعديل)؛ لا يُضاف إلى البيانات الحالية.");
                break;
            }
        }
        for (JSONObject t : txRows) {
            if (!"ACTIVE".equals(t.optString("status")) || !t.isNull("replaces_id") && t.has("replaces_id")) {
                p.errors.add("الملف فيه حركات ملغاة أو معدّلة؛ لا يُضاف إلى البيانات الحالية.");
                break;
            }
        }
        if (!p.errors.isEmpty()) return p;

        // بصمة الملف: تمنع إضافته مرتين
        JSONObject counts = manifest.optJSONObject("counts");
        p.fingerprint = manifest.optLong("createdAt") + "|" + (counts == null ? "" : counts.optLong("offices") + "|" + counts.optLong("tx"))
                + "|" + (txRows.isEmpty() ? "" : txRows.get(0).optString("uuid"));
        if (ex.mergedFiles.contains(p.fingerprint)) {
            p.errors.add("أُضيف هذا الملف إلى بياناتك سابقًا. إضافته مرة ثانية تضاعف الأرصدة، لذلك لا تُسمح.");
            return p;
        }
        int dupUuids = 0;
        for (JSONObject t : txRows) if (ex.uuids.contains(t.optString("uuid"))) dupUuids++;
        if (dupUuids > 0) {
            p.errors.add(dupUuids + " حركة من هذا الملف موجودة في بياناتك (يبدو أنه أُضيف سابقًا). لم يُضف شيء.");
            return p;
        }

        // العملات ومنازلها
        Map<String, Integer> fileDec = new HashMap<>();
        Map<String, String> fileName = new HashMap<>();
        for (JSONObject c : list(tables, "currencies")) {
            fileDec.put(c.optString("code"), c.optInt("decimals", 2));
            fileName.put(c.optString("code"), c.optString("name", c.optString("code")));
        }
        Set<String> used = new java.util.TreeSet<>();
        for (JSONObject l : lines) if ("OFFICE".equals(l.optString("account"))) used.add(l.optString("currency"));
        for (JSONObject t : txRows) used.add(t.optString("currency"));
        Map<String, int[]> dec = new HashMap<>();
        for (String code : used) {
            Integer fd = fileDec.get(code);
            if (fd == null) {
                p.errors.add("العملة " + code + " مستخدمة في الملف وغير معرّفة فيه.");
                continue;
            }
            Integer ad = ex.decimals.get(code);
            if (ad == null) {
                NewCurrency n = new NewCurrency();
                n.code = code;
                n.name = fileName.get(code);
                n.decimals = fd;
                p.newCurrencies.add(n);
                ad = fd;
            } else if (ad.intValue() != fd.intValue()) {
                p.warnings.add("منازل " + code + " في التطبيق " + ad + " وفي الملف " + fd + "؛ حُوّلت المبالغ دون تقريب.");
            }
            dec.put(code, new int[]{fd, ad});
        }
        if (!p.errors.isEmpty()) return p;

        // المكاتب: مطابقة بالاسم (بالكتابة نفسها أولًا، ثم بعد توحيد الهمزات والتاء والمسافات إن كان التطابق وحيدًا)
        Map<String, List<Long>> byExact = new HashMap<>(), byLoose = new HashMap<>();
        for (Map.Entry<Long, String> e : ex.offices.entrySet()) {
            put(byExact, exactKey(e.getValue()), e.getKey());
            put(byLoose, looseKey(e.getValue()), e.getKey());
        }
        for (JSONObject o : list(tables, "offices")) {
            Office of = new Office();
            of.fileId = o.optLong("id");
            of.name = o.optString("name").trim();
            of.phone = o.optString("phone", "");
            of.notes = o.optString("notes", "");
            if (of.name.isEmpty()) {
                p.errors.add("في الملف مكتب بلا اسم.");
                continue;
            }
            List<Long> m = byExact.get(exactKey(of.name));
            if (m == null || m.size() != 1) {
                List<Long> l2 = byLoose.get(looseKey(of.name));
                if ((m == null || m.isEmpty()) && l2 != null && l2.size() == 1) {
                    m = l2;
                    of.approximate = true;
                }
            }
            if (m != null && m.size() > 1) {
                p.errors.add("في التطبيق أكثر من مكتب باسم «" + of.name + "»؛ غيّر اسم أحدهما أولًا حتى يُعرف أين تُضاف الأرصدة.");
                continue;
            }
            if (m != null && m.size() == 1) {
                of.targetId = m.get(0);
                of.targetName = ex.offices.get(of.targetId);
                p.matchedOffices++;
            } else p.newOffices++;
            p.offices.put(of.fileId, of);
        }
        if (!p.errors.isEmpty()) return p;
        // مكتبان في الملف يُضافان إلى المكتب نفسه في التطبيق: مسموح، ويُجمعان
        Map<Long, Long> entryWhen = new HashMap<>();
        Map<Long, String> entryKind = new HashMap<>();
        for (JSONObject e : entries) {
            entryWhen.put(e.optLong("id"), e.optLong("created_at"));
            entryKind.put(e.optLong("id"), e.optString("kind"));
        }

        // المتوقع: صافي قيود حساب المكتب في الملف (هو نفسه ما ستضيفه القيود الجديدة)
        for (JSONObject l : lines) {
            if (!"OFFICE".equals(l.optString("account")) || l.isNull("office_id")) continue;
            long fo = l.optLong("office_id");
            String code = l.optString("currency");
            if (!p.offices.containsKey(fo)) {
                p.errors.add("قيد في الملف لمكتب غير موجود فيه.");
                return p;
            }
            int[] d = dec.get(code);
            Long dv = convert(l.optLong("debit_minor"), d[0], d[1]), cv = convert(l.optLong("credit_minor"), d[0], d[1]);
            if (dv == null || cv == null) {
                p.errors.add("مبلغ بعملة " + code + " فيه منازل أكثر مما في التطبيق (" + d[1] + ")؛ غيّر منازل العملة في التطبيق أو في الأداة.");
                return p;
            }
            String k = fo + "|" + code;
            Long prev = p.expected.get(k);
            p.expected.put(k, (prev == null ? 0 : prev) + dv - cv);
            p.offices.get(fo).codes.add(code);
        }

        // الأرصدة السابقة
        Map<String, Long> fromParts = new HashMap<>();
        for (JSONObject ob : list(tables, "opening_balances")) {
            Opening o = new Opening();
            o.fileOffice = ob.optLong("office_id");
            o.code = ob.optString("currency");
            int[] d = dec.get(o.code);
            Long amt = d == null ? null : convert(ob.optLong("amount_minor"), d[0], d[1]);
            if (amt == null || amt <= 0 || !p.offices.containsKey(o.fileOffice)) {
                p.errors.add("رصيد سابق غير صالح في الملف.");
                return p;
            }
            String type = ob.optString("type");
            if (!"لنا".equals(type) && !"علينا".equals(type)) {
                p.errors.add("نوع رصيد سابق غير معروف: " + type);
                return p;
            }
            o.signed = "لنا".equals(type) ? amt : -amt;
            Long w = entryWhen.get(ob.optLong("entry_id"));
            if (w == null || !"OPENING".equals(entryKind.get(ob.optLong("entry_id")))) {
                p.errors.add("رصيد سابق في الملف بلا قيد افتتاحي.");
                return p;
            }
            o.when = w;
            p.openings.add(o);
            add(fromParts, o.fileOffice + "|" + o.code, o.signed);
            p.offices.get(o.fileOffice).codes.add(o.code);
        }

        // الحركات
        for (JSONObject t : txRows) {
            Tx x = new Tx();
            x.fileId = t.optLong("id");
            x.uuid = t.optString("uuid");
            x.fileOffice = t.optLong("office_id");
            x.type = t.optString("type");
            x.person = t.optString("person", "");
            x.currency = t.optString("currency");
            x.feeType = t.optString("fee_type", Model.FEE_NONE);
            x.feeCurrency = t.optString("fee_currency", x.currency);
            x.description = t.optString("description", "");
            x.notes = t.optString("notes", "");
            x.userName = t.optString("user_name", "");
            x.when = t.optLong("created_at");
            if (x.uuid.isEmpty() || !p.offices.containsKey(x.fileOffice)) {
                p.errors.add("حركة غير صالحة في الملف (رقم " + x.fileId + ").");
                return p;
            }
            if (!Model.TYPE_DEBIT.equals(x.type) && !Model.TYPE_CREDIT.equals(x.type)) {
                p.errors.add("نوع حركة غير معروف في الملف: " + x.type);
                return p;
            }
            if (!Model.FEE_NONE.equals(x.feeType) && !Model.FEE_US.equals(x.feeType) && !Model.FEE_THEM.equals(x.feeType)) {
                p.errors.add("نوع أجرة غير معروف في الملف: " + x.feeType);
                return p;
            }
            int[] d = dec.get(x.currency);
            Long amt = d == null ? null : convert(t.optLong("amount_minor"), d[0], d[1]);
            if (amt == null || amt <= 0) {
                p.errors.add("مبلغ حركة غير صالح في الملف (رقم " + x.fileId + ").");
                return p;
            }
            x.amount = amt;
            add(fromParts, x.fileOffice + "|" + x.currency, Model.TYPE_DEBIT.equals(x.type) ? amt : -amt);
            if (!Model.FEE_NONE.equals(x.feeType)) {
                int[] fd = dec.get(x.feeCurrency);
                Long fee = fd == null ? null : convert(t.optLong("fee_minor"), fd[0], fd[1]);
                if (fee == null || fee <= 0) {
                    p.errors.add("أجرة غير صالحة في الملف (حركة " + x.fileId + ").");
                    return p;
                }
                x.fee = fee;
                add(fromParts, x.fileOffice + "|" + x.feeCurrency, Model.FEE_US.equals(x.feeType) ? fee : -fee);
                p.offices.get(x.fileOffice).codes.add(x.feeCurrency);
            } else x.fee = 0;
            if (ex.lockBefore > 0 && x.when < ex.lockBefore) {
                p.errors.add("في الملف حركات قبل تاريخ الإقفال المحاسبي في التطبيق؛ فك الإقفال أولًا أو صدّر فترة بعده.");
                return p;
            }
            p.txs.add(x);
            p.offices.get(x.fileOffice).codes.add(x.currency);
        }
        for (Opening o : p.openings) {
            if (ex.lockBefore > 0 && o.when < ex.lockBefore) {
                p.errors.add("في الملف أرصدة سابقة بتاريخ قبل الإقفال المحاسبي في التطبيق؛ فك الإقفال أولًا.");
                return p;
            }
        }
        // تحقق داخلي: ما ستكتبه الحركات والأرصدة يساوي قيود الملف نفسها
        Set<String> keys = new HashSet<>(p.expected.keySet());
        keys.addAll(fromParts.keySet());
        for (String k : keys) {
            long a = p.expected.containsKey(k) ? p.expected.get(k) : 0, b = fromParts.containsKey(k) ? fromParts.get(k) : 0;
            if (a != b) {
                p.errors.add("الملف غير متسق: قيود المكتب لا تساوي حركاته وأرصدته (" + k + ").");
                return p;
            }
        }

        // المعاينة
        Map<String, Integer> txCount = new HashMap<>();
        for (Tx x : p.txs) {
            String k = x.fileOffice + "|" + x.currency;
            txCount.put(k, (txCount.containsKey(k) ? txCount.get(k) : 0) + 1);
        }
        for (Office of : p.offices.values()) {
            for (String code : of.codes) {
                Row r = new Row();
                r.fileOffice = of.fileId;
                r.office = of.targetId != null ? of.targetName : of.name;
                r.isNew = of.targetId == null;
                r.approximate = of.approximate;
                r.code = code;
                r.decimals = dec.get(code)[1];
                Long cur = of.targetId == null ? null : ex.balances.get(of.targetId + "|" + code);
                r.current = cur == null ? 0 : cur;
                Long add = p.expected.get(of.fileId + "|" + code);
                r.added = add == null ? 0 : add;
                r.after = r.current + r.added;
                Integer n = txCount.get(of.fileId + "|" + code);
                r.txCount = n == null ? 0 : n;
                p.rows.add(r);
            }
        }
        if (p.txs.isEmpty() && p.openings.isEmpty()) p.errors.add("الملف لا يحتوي أرصدة ولا حركات.");
        return p;
    }

    private static List<JSONObject> list(Map<String, List<JSONObject>> t, String name) {
        List<JSONObject> l = t.get(name);
        return l == null ? new ArrayList<JSONObject>() : l;
    }

    private static void put(Map<String, List<Long>> m, String k, long v) {
        List<Long> l = m.get(k);
        if (l == null) {
            l = new ArrayList<>();
            m.put(k, l);
        }
        l.add(v);
    }

    private static void add(Map<String, Long> m, String k, long v) {
        Long p = m.get(k);
        m.put(k, (p == null ? 0 : p) + v);
    }
}
