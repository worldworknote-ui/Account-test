package com.smart.hawala;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** أنواع البيانات المشتركة وأدوات التنسيق. منطق بحت بدون أندرويد حتى يُختبر على الكمبيوتر. */
public final class Model {
    private Model() {}

    public static final String STATUS_ACTIVE = "ACTIVE", STATUS_PENDING = "PENDING", STATUS_REJECTED = "REJECTED";
    /**
     * 12.4: «حركة خاصة» (ناقصة أجور، تحتاج مراجعة، بانتظار التنفيذ…): SPECIAL = لا تُحسب في الرصيد (لا قيود لها بعد)؛
     * CONVERTED = حُوّلت إلى حركة عادية (replaces_id في الجديدة)؛ VOIDED = أُلغيت قبل أن تُحسب (لا قيد لها ليُعكس).
     * الخاصة المحسوبة في الرصيد تبقى ACTIVE مع اسم نوعها في tx.special.
     */
    public static final String STATUS_SPECIAL = "SPECIAL", STATUS_CONVERTED = "CONVERTED", STATUS_VOIDED = "VOIDED";
    /** 10.5: علامة ساقي «التحويل بين مكتبين» في خانة fx_rate (التبديل يحمل فيها السعر). */
    public static final String TRANSFER_MARK = "XFER";
    public static final String TYPE_DEBIT = "مدين / لنا";
    public static final String TYPE_CREDIT = "دائن / علينا";
    public static final String FEE_NONE = "بدون أجرة";
    public static final String FEE_US = "الأجرة لنا";
    public static final String FEE_THEM = "الأجرة علينا";

    public static class Office {
        public long id;
        public String name, shortName, phone, location, contact, notes;
        public boolean active, pinned;
        public Long usdLimit, usdOweLimit;
    }

    public static class Currency {
        public String code, name;
        public int decimals;
        public boolean active;

        public Currency(String c, String n, int d, boolean a) {
            code = c;
            name = n;
            decimals = d;
            active = a;
        }

        @Override public String toString() { return code + " — " + name; }
    }

    public static class Tx {
        public long id, officeId, amount, fee, createdAt;
        public Long replacesId;
        public String uuid, type, person, currency, feeType, feeCurrency, description, notes, status, userName;
        /**
         * 10.17: userName هو الاسم كما يُعرض للمستخدم الحالي (قد يكون «الاسم الظاهر» الذي اختاره صاحبه)، وuserReal اسم
         * الدخول الحقيقي للمنطق (حركتي؟) والسجل. فارغ = لم يُحمَّل من القاعدة فيُستعمل userName.
         */
        public String userReal = "";

        public String realUser() { return userReal == null || userReal.isEmpty() ? nz(userName) : userReal; }
        /** 8.0: نوع الخدمة (اعتماد، سويفت، USDT…) والوجهة في خانتين ثابتتين لتقرير الخدمات والوجهات. */
        public String service = "", dest = "";
        /** 8.0: ساقا عملية تبديل عملة مرتبطتان برقم واحد، وسعر التبديل (وحدات عملة «علينا» لكل وحدة من عملة «لنا»). */
        public String pairUuid = "", fxRate = "";
        /** 10.5 (§7): رقم المستخدم الذي أدخلها (ثابت لا يتغير بتغيير الاسم)، ومن اعتمدها ومتى (null لحركات ما قبل 10.5 أو بلا مستخدمين). */
        public Long userId, approvedBy;
        public long approvedAt;
        /** 10.5: ساق من عملية مترابطة (تبديل عملة أو تحويل بين مكتبين): لا تُعدَّل وحدها، وتُلغى وتُعتمد مع شقيقتها. */
        public boolean isLinked() { return pairUuid != null && !pairUuid.isEmpty(); }
        /** 10.5: ساق تحويل بين مكتبين (العلامة في fx_rate، فلا يتغير مخطط قاعدة البيانات). */
        public boolean isTransfer() { return isLinked() && TRANSFER_MARK.equals(fxRate); }
        public boolean isFx() { return isLinked() && !TRANSFER_MARK.equals(fxRate); }
        public boolean isActive() { return "ACTIVE".equals(status); }
        /** 8.0 (ت4): مقترحة من موظف بانتظار اعتماد المحاسب: لا قيود لها ولا أثر في الأرصدة بعد. */
        public boolean isPending() { return STATUS_PENDING.equals(status); }
        public boolean isRejected() { return STATUS_REJECTED.equals(status); }
        public boolean hasFee() { return !FEE_NONE.equals(feeType) && fee > 0; }
        /** 12.4: نوع الحركة الخاصة («ناقصة أجور»…)، فارغ = حركة عادية. specialBal: تُحسب في الرصيد. */
        public String special = "";
        public boolean specialBal = true;
        public boolean isSpecial() { return special != null && !special.isEmpty() && (isActive() || isPending() || STATUS_SPECIAL.equals(status)); }
        /** خاصة لا تُحسب في الرصيد (لا قيود لها). */
        public boolean isUnposted() { return STATUS_SPECIAL.equals(status); }
    }

    /** سطر من دفتر القيود يخص حساب مكتب (أصل، أجرة، رصيد سابق، عكس). */
    public static class Line {
        public long lineId, entryId, createdAt, debit, credit;
        public Long txId;
        public String kind = "", currency, txUuid, txType, person, description, notes, status, feeType;
        /** 12.4: نوع الحركة الخاصة المحسوبة في الرصيد (تظهر في الكشف «معلّقة»)، أو فارغ. */
        public String special = "";

        public boolean cancelledTx() { return "CANCELLED".equals(status); }
    }

    /** مجاميع مكتب لكل عملة: كل «لنا»، كل «علينا»، والرصيد. */
    public static class OfficeTotals {
        public long officeId;
        public String name;
        public boolean pinned, active;
        /** العملة ← {مجموع لنا، مجموع علينا، الرصيد} */
        public final Map<String, long[]> perCurrency = new LinkedHashMap<>();
    }

    public static final String RECON_SENT = "SENT", RECON_CONFIRMED = "CONFIRMED", RECON_DISPUTED = "DISPUTED";

    /** مطابقة رصيد مع مكتب: لقطة الرصيد لكل عملة وقت إنشائها، وحالتها. */
    public static class Recon {
        public long id, officeId, createdAt, cutoffEntry, confirmedAt;
        /** 10.0: آخر قيد من كل نطاق أرقام (المزامنة)، JSON؛ فارغ = cutoffEntry وحده. */
        public String cutoffMarks = "";
        public String uuid = "", status = RECON_SENT, note = "", userName = "";
        /** 10.17: اسم الدخول الحقيقي لمن أنشأها (للمنطق)؛ userName للعرض. */
        public String userReal = "";
        /** 10.8: من أنشأها (رقمه) ومن تحقق منها ومتى (0 = لم يُتحقق منها). */
        public Long userId, checkedBy;
        public long checkedAt;
        public final Map<String, Long> balances = new java.util.TreeMap<>();

        public String statusLabel() {
            if (RECON_CONFIRMED.equals(status)) return "مطابِقة ✓";
            if (RECON_DISPUTED.equals(status)) return "غير مطابِقة ✗";
            return "بانتظار تأكيد المكتب";
        }
    }

    public static class User {
        public long id;
        public String name, role;
        /** 10.1: الصلاحيات (أسماء مفصولة بفواصل)؛ null لحساب قديم يُشتق من دوره. */
        public String perms;
        public boolean active;
        /** 10.8: حساب محذوف (يبقى لأسماء التاريخ فقط). */
        public boolean deleted;
        /** 10.17: «الاسم الظاهر» لبقية المحاسبين بدل اسم الدخول (فارغ = اسم الدخول). المدير يرى الاسم الحقيقي دائمًا. */
        public String nick = "";
    }

    // ===================================================================================
    // التنسيق
    // ===================================================================================

    public static String fmt(long minor, int dec) {
        StringBuilder p = new StringBuilder("#,##0");
        if (dec > 0) {
            p.append('.');
            for (int i = 0; i < dec; i++) p.append('#');
        }
        DecimalFormat f = new DecimalFormat(p.toString(), DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(BigDecimal.valueOf(minor, dec));
    }

    /** مبلغ بعدد منازل ثابت (59,450.00) لرسائل المطابقة. */
    public static String fmtFixed(long minor, int dec) {
        StringBuilder p = new StringBuilder("#,##0");
        if (dec > 0) {
            p.append('.');
            for (int i = 0; i < dec; i++) p.append('0');
        }
        DecimalFormat f = new DecimalFormat(p.toString(), DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(BigDecimal.valueOf(minor, dec));
    }

    /** رصيد بالصيغة المحاسبية: «لنا 1,000» أو «علينا 700» أو «0». */
    public static String bal(long v, int dec) {
        if (v > 0) return "لنا " + fmt(v, dec);
        if (v < 0) return "علينا " + fmt(-v, dec);
        return "0";
    }

    public static String plain(long minor, int dec) {
        return BigDecimal.valueOf(minor, dec).stripTrailingZeros().toPlainString();
    }

    // ===================================================================================
    // الأجرة كنسبة من المبلغ الأصلي: «1٪» أو «1.5‰»
    // ===================================================================================

    /** النسبة بالمئة (100) أو بالألف (1000). */
    public static final int PER_CENT = 100, PER_MILLE = 1000;

    /**
     * يقرأ النسبة كما يكتبها المحاسب: أرقام عربية أو إنجليزية، والفاصلة العشرية «.» أو «,» أو «٫»،
     * مع تجاهل المسافات وعلامات ٪ و% و‰. يعيد null إن لم تكن رقمًا موجبًا صالحًا.
     */
    public static BigDecimal parseRate(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char ch : text.trim().toCharArray()) {
            if (ch >= '\u0660' && ch <= '\u0669') sb.append((char) ('0' + (ch - '\u0660')));
            else if (ch >= '\u06F0' && ch <= '\u06F9') sb.append((char) ('0' + (ch - '\u06F0')));
            else if (ch == '\u066B' || ch == ',' || ch == '\u060C') sb.append('.');
            else if (ch == ' ' || ch == '\u00A0' || ch == '%' || ch == '\u066A' || ch == '\u2030') continue;
            else sb.append(ch);
        }
        String s = sb.toString();
        if (!s.matches("\\d{1,4}(\\.\\d{1,4})?|\\.\\d{1,4}")) return null;
        BigDecimal r = new BigDecimal(s);
        return r.signum() > 0 ? r : null;
    }

    /**
     * الأجرة بالوحدة الصغرى لعملة المبلغ نفسها: المبلغ × النسبة ÷ (100 أو 1000)، مقرّبة لأقرب وحدة صغرى (نصف للأعلى).
     * مثال: 5000.00 دولار × 1٪ = 50.00؛ 2000.00 دولار × 1.5‰ = 3.00.
     */
    public static long feeFromRate(long amountMinor, BigDecimal rate, int per) {
        if (amountMinor <= 0) throw new IllegalArgumentException("المبلغ يجب أن يكون أكبر من صفر");
        if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("النسبة يجب أن تكون أكبر من صفر");
        if (per != PER_CENT && per != PER_MILLE) throw new IllegalArgumentException("per");
        if (rate.compareTo(BigDecimal.valueOf(per)) > 0) throw new IllegalArgumentException("النسبة أكبر من المبلغ نفسه");
        return BigDecimal.valueOf(amountMinor).multiply(rate)
                .divide(BigDecimal.valueOf(per), 0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    /** هل الناتج مقرّب (أي أن المبلغ × النسبة لا يقسم على الوحدة الصغرى تمامًا)؟ */
    public static boolean feeRounded(long amountMinor, BigDecimal rate, int per) {
        return BigDecimal.valueOf(amountMinor).multiply(rate).remainder(BigDecimal.valueOf(per)).signum() != 0;
    }

    /** «1٪» أو «1.5‰» للعرض. */
    public static String rateLabel(BigDecimal rate, int per) {
        return rate.stripTrailingZeros().toPlainString() + (per == PER_MILLE ? "‰" : "٪");
    }

    // ---- 10.18: توقيت المكتب الموحّد: كل الأجهزة تعرض التواريخ وتحسب «اليوم» بالمنطقة التي يحددها المدير ----
    private static volatile java.util.TimeZone zone = java.util.TimeZone.getDefault();
    private static volatile String zoneId = "";

    /** منطقة المكتب، أو منطقة هذا الجهاز إن لم تُحدَّد. */
    public static java.util.TimeZone zone() { return zone; }

    /** معرّف منطقة المكتب المحدد ("" = منطقة الجهاز). */
    public static String zoneId() { return zoneId; }

    /** "" أو معرّف غير معروف = منطقة هذا الجهاز. */
    public static void setZone(String id) {
        String v = id == null ? "" : id.trim();
        boolean known = false;
        if (!v.isEmpty()) for (String x : java.util.TimeZone.getAvailableIDs()) if (x.equals(v)) known = true;
        zoneId = known ? v : "";
        zone = known ? java.util.TimeZone.getTimeZone(v) : java.util.TimeZone.getDefault();
    }

    /** 10.18: مناطق شائعة لمكاتب الحوالات (المعرّف، الاسم)؛ وأي معرّف IANA آخر مقبول بالكتابة. */
    public static final String[][] ZONES = {{"Asia/Damascus", "دمشق"}, {"Europe/Istanbul", "إسطنبول"}, {"Asia/Beirut", "بيروت"}, {"Asia/Amman", "عمّان"},
            {"Asia/Baghdad", "بغداد وأربيل"}, {"Asia/Riyadh", "الرياض"}, {"Asia/Kuwait", "الكويت"}, {"Asia/Qatar", "الدوحة"}, {"Asia/Dubai", "دبي"}, {"Africa/Cairo", "القاهرة"},
            {"Africa/Tripoli", "طرابلس"}, {"Africa/Khartoum", "الخرطوم"}, {"Europe/Berlin", "برلين"}, {"Europe/Amsterdam", "أمستردام"}, {"Europe/Stockholm", "ستوكهولم"},
            {"Europe/Vienna", "فيينا"}, {"Europe/Paris", "باريس"}, {"Europe/London", "لندن"}, {"Europe/Athens", "أثينا"}, {"America/New_York", "نيويورك"}, {"America/Toronto", "تورنتو"}};

    /** اسم المنطقة للعرض مع فرقها عن غرينتش الآن: «دمشق (Asia/Damascus، ‎+03:00)». */
    public static String zoneLabel(String id) {
        if (id == null || id.isEmpty()) return "توقيت هذا الجهاز (" + java.util.TimeZone.getDefault().getID() + "، " + zoneOffset(java.util.TimeZone.getDefault(), Clock.now()) + ")";
        String name = null;
        for (String[] z : ZONES) if (z[0].equals(id)) name = z[1];
        return (name == null ? id : name + " (" + id) + (name == null ? " (" : "، ") + zoneOffset(java.util.TimeZone.getTimeZone(id), Clock.now()) + ")";
    }

    /** تقويم بتوقيت المكتب، مضبوط على الوقت الموحّد الآن. */
    /** 12.0: بداية يوم الوقت t بتوقيت المكتب. */
    public static long dayStart(long t) {
        java.util.Calendar c = cal();
        c.setTimeInMillis(t);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static java.util.Calendar cal() {
        java.util.Calendar c = java.util.Calendar.getInstance(zone);
        c.setTimeInMillis(Clock.now());
        return c;
    }

    /** يضبط منسّق التاريخ على توقيت المكتب. */
    public static SimpleDateFormat sdf(SimpleDateFormat f) {
        f.setTimeZone(zone);
        return f;
    }

    /** وصف قصير للفرق عن غرينتش: «+03:00». */
    public static String zoneOffset(java.util.TimeZone z, long at) {
        int m = z.getOffset(at) / 60000;
        return (m < 0 ? "-" : "+") + String.format(Locale.US, "%02d:%02d", Math.abs(m) / 60, Math.abs(m) % 60);
    }

    public static String date(long t) {
        return t <= 0 ? "—" : sdf(new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)).format(new Date(t));
    }

    public static String day(long t) {
        return sdf(new SimpleDateFormat("yyyy/MM/dd", Locale.US)).format(new Date(t));
    }

    public static String shortRef(String uuid) {
        return uuid == null ? "" : (uuid.length() > 8 ? uuid.substring(0, 8) : uuid);
    }

    public static String nz(String s) { return s == null ? "" : s; }

    /**
     * 10.11 (AUDIT-013): عنوان على هذا الجهاز نفسه (أو محاكي أندرويد) بالمضيف كاملًا لا ببداية النص؛
     * «http://localhost.example.com» كان يُقبل بلا تشفير لأنه يبدأ بـ «http://localhost».
     */
    public static boolean localUrl(String url) {
        try {
            String h = new java.net.URI(nz(url).trim()).getHost();
            return h != null && (h.equals("127.0.0.1") || h.equals("localhost") || h.equals("10.0.2.2") || h.equals("[::1]"));
        } catch (Exception e) {
            return false;
        }
    }

    public static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    public static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return l;
    }

    // ===================================================================================
    // رقم واتساب
    // ===================================================================================

    /**
     * يحوّل رقم الهاتف المحفوظ إلى الصيغة الدولية التي يقبلها واتساب (أرقام فقط).
     * 00963... أو +963... تبقى كما هي؛ الرقم المحلي الذي يبدأ بصفر يُضاف له رمز الدولة.
     * يرجع null إذا لم يكن الرقم صالحًا.
     */
    public static String whatsappNumber(String phone, String countryCode) {
        if (phone == null) return null;
        StringBuilder d = new StringBuilder();
        String t = phone.trim();
        for (char ch : t.toCharArray()) {
            if (ch >= '0' && ch <= '9') d.append(ch);
            else if (ch >= 0x0660 && ch <= 0x0669) d.append((char) ('0' + ch - 0x0660));
            else if (ch >= 0x06F0 && ch <= 0x06F9) d.append((char) ('0' + ch - 0x06F0));
        }
        String n = d.toString();
        if (n.isEmpty()) return null;
        boolean intl = t.startsWith("+") || n.startsWith("00");
        if (n.startsWith("00")) n = n.substring(2);
        else if (!intl && n.startsWith("0")) {
            String cc = countryCode == null ? "" : countryCode.replaceAll("[^0-9]", "");
            if (cc.isEmpty()) return null;
            n = cc + n.substring(1);
        }
        return n.length() >= 8 && n.length() <= 15 ? n : null;
    }
}
