package com.smart.hawala;

/**
 * ما يحق لهذه النسخة: المجانية أو المرخّصة. منطق بحت يُبنى عند الإقلاع من رمز الترخيص الموقّع.
 * قيم متعددة بدل قيمة منطقية واحدة isPro، فلا يفتح تعديل موضع واحد كل شيء.
 *
 * القاعدة الثابتة: لا شيء يحجز بيانات المستخدم. العرض والبحث والكشوف والتقارير والنسخ اليدوي والاستعادة
 * وتصحيح الحركات القائمة مجانية دائمًا، ويتوقف عند السقف ما يضيف فقط.
 */
public final class Entitlements {
    /**
     * سقوف المجاني (12.6): 15 مكتبًا (حذف مكتب يعيد مكانه فارغًا)، و1500 حركة جديدة لكل مكتب (التصحيحات لا تُحتسب)،
     * واستخراج معلومات رسائل واتساب لـ 20 رسالة يوميًا (ومعها الالتقاط والتجميع والقص) ثم الإدخال اليدوي العادي،
     * وصندوق واحد، و25 عميلًا، ومستخدم واحد (المالك). (9.0–12.5: 9 مكاتب و700 حركة.)
     */
    public static final int FREE_OFFICES = 15;
    public static final int FREE_TX_PER_OFFICE = 1500;
    public static final int FREE_WA_PER_DAY = 20;
    public static final int FREE_CASH_BOXES = 1;
    public static final int FREE_CLIENTS = 25;

    /**
     * 12.6: حقول الرمز الرقمية (يكتبها البائع لكل زبون كما يشاء، 0 أو غيابها = بلا حد إن كانت الميزة مفعّلة):
     * mo المكاتب، mt الحركات لكل مكتب، mu المستخدمون، mb الصناديق، mc العملاء، mw رسائل واتساب يوميًا،
     * bh أقل ساعات بين النسخ التلقائي، bn نسخة بعد كل كم حركة، ex آخر يوم للاشتراك (yyyymmdd، اختياري).
     */
    public static final String K_OFFICES = "mo", K_TX = "mt", K_USERS = "mu", K_BOXES = "mb", K_CLIENTS = "mc", K_WA = "mw",
            K_BACKUP_HOURS = "bh", K_BACKUP_TX = "bn", K_EXPIRES = "ex";
    public static final long UNLIMITED = Long.MAX_VALUE;

    /** الميزات المدفوعة كما تُكتب في الرمز (ft). */
    public static final String F_OFFICES = "offices", F_TX = "tx", F_USERS = "users", F_AI = "ai", F_EXPORT = "export", F_BACKUP = "backup";
    /** 10.0: المزامنة بين الأجهزة والمنصات. */
    public static final String F_SYNC = "sync";
    /** 12.6: الصناديق المتعددة والعملاء (مفتوحة لكل رخصة مدفوعة ما لم يحدد البائع رقمًا). */
    public static final String F_BOXES = "boxes", F_CLIENTS = "clients";

    public final boolean pro;
    public final String lid;
    /** سبب العمل بالمجاني رغم وجود رمز (انتهت التحديثات، رمز لهاتف آخر…)، أو فارغ. */
    public final String note;
    private final LicenseToken.Grant grant;

    private Entitlements(boolean pro, String lid, String note, LicenseToken.Grant grant) {
        this.pro = pro;
        this.lid = lid == null ? "" : lid;
        this.note = note == null ? "" : note;
        this.grant = grant;
    }

    public static Entitlements free(String note) { return new Entitlements(false, "", note, null); }

    /** من رمز تحقق منه LicenseToken.verify. البناء خارج فترة التحديثات يعمل بالمجاني ويقول لماذا. */
    public static Entitlements of(LicenseToken.Grant g, int buildDate) { return of(g, buildDate, 0); }

    /**
     * 12.6: ومعه اليوم (yyyymmdd، لا يرجع للوراء: أكبر يوم رآه التطبيق) لاشتراك بتاريخ انتهاء. بعد الانتهاء يعمل بالمجاني
     * ولا يُقفل أي شيء من البيانات؛ تتوقف الإضافة عند سقوف المجاني فقط.
     */
    public static Entitlements of(LicenseToken.Grant g, int buildDate, long today) {
        if (g == null) return free("");
        long ex = g.num(K_EXPIRES);
        if (ex > 0 && today > ex) {
            return free("انتهى اشتراك رخصتك (" + g.lid() + ") في " + fmtDate(ex) + ". جدّده من البائع؛ بياناتك كلها متاحة، والإضافة عند حدود المجاني فقط.");
        }
        if (!g.coversBuild(buildDate)) {
            return free("رخصتك (" + g.lid() + ") تشمل التحديثات حتى " + fmtDate(g.num("uu")) + "، وهذا الإصدار أحدث منها. "
                    + "ارجع إلى آخر إصدار مشمول أو جدّد التحديثات؛ بياناتك كلها متاحة.");
        }
        return new Entitlements(true, g.lid(), "", g);
    }

    private boolean feature(String f) { return pro && grant != null && grant.has(f); }

    /**
     * 12.6: الحد من الرمز: رقم يكتبه البائع (أكبر من صفر) هو الحد؛ وإلا بلا حد إن كانت الميزة مفعّلة (أو الرخصة مدفوعة للحدود
     * التي كانت مفتوحة للجميع قبل 12.6: الصناديق والعملاء)؛ وإلا حد المجاني.
     */
    private long limit(String key, String flag, boolean openWhenPro, long free) {
        if (!pro || grant == null) return free;
        long n = grant.num(key);
        if (n > 0) return n;
        if ((flag != null && grant.has(flag)) || openWhenPro) return UNLIMITED;
        return free;
    }

    public long maxOffices() { return limit(K_OFFICES, F_OFFICES, false, FREE_OFFICES); }

    public long maxTxPerOffice() { return limit(K_TX, F_TX, false, FREE_TX_PER_OFFICE); }

    /** المجاني: المالك وحده (مدير واحد يحمي التطبيق برمزه)؛ المدفوع: مستخدمون بأدوار (بعدد يحدده البائع أو بلا حد). */
    public long maxUsers() { return limit(K_USERS, F_USERS, false, 1); }

    /** 12.6: الصناديق (المجاني صندوق واحد). */
    public long maxCashBoxes() { return limit(K_BOXES, F_BOXES, true, FREE_CASH_BOXES); }

    /** 12.6: حسابات العملاء (المجاني 25). */
    public long maxClients() { return limit(K_CLIENTS, F_CLIENTS, true, FREE_CLIENTS); }

    /** 12.6: آخر يوم للاشتراك أو فارغ (دائمة). */
    public String expires() { return grant == null || grant.num(K_EXPIRES) <= 0 ? "" : fmtDate(grant.num(K_EXPIRES)); }

    public boolean ai() { return feature(F_AI); }

    /** 10.0: المزامنة بين الأجهزة (ميزة مدفوعة، يفعّلها المستخدم أو يتركها). */
    public boolean sync() { return feature(F_SYNC); }

    /** رسائل واتساب التي تُستخرج معلوماتها في اليوم (المجاني 20، الكاملة بلا حد). */
    public long waPerDay() { return limit(K_WA, null, true, FREE_WA_PER_DAY); }

    /** المجاني يصدّر كل شيء مع سطر «نسخة مجانية» ظاهر؛ المدفوع بلا علامة وببصمة رقم الرخصة في الملف. */
    public boolean watermark() { return !feature(F_EXPORT); }

    /** النسخ التلقائي: المجاني حتى مرة يوميًا، والمدفوع كل 6 ساعات أو بعد كل 25 حركة. */
    public int autoBackupMinHours() {
        long n = pro && grant != null ? grant.num(K_BACKUP_HOURS) : 0;
        if (n > 0) return (int) Math.max(1, Math.min(24, n));
        return feature(F_BACKUP) ? 6 : 24;
    }

    public int autoBackupEveryTx() {
        long n = pro && grant != null ? grant.num(K_BACKUP_TX) : 0;
        if (n > 0) return (int) Math.max(5, Math.min(1000, n));
        return feature(F_BACKUP) ? 25 : 0;
    }

    public String updatesUntil() { return grant == null ? "" : fmtDate(grant.num("uu")); }

    public String edition() {
        if (!pro) return "النسخة المجانية";
        String ed = grant.get("ed");
        return ed.isEmpty() || "pro".equals(ed) ? "النسخة الكاملة" : ed.replace('_', ' ');   // 12.6: اسم القالب من أداة البائع
    }

    static String fmtDate(long yyyymmdd) {
        if (yyyymmdd <= 0) return "—";
        String s = String.valueOf(yyyymmdd);
        return s.length() == 8 ? s.substring(0, 4) + "/" + s.substring(4, 6) + "/" + s.substring(6) : s;
    }

    /** رفض بسبب حدود النسخة المجانية (تعرض الواجهة معه زر «الترخيص»). لا يمس أي بيانات قائمة. */
    public static final class LimitReached extends IllegalStateException {
        public LimitReached(String m) { super(m); }
    }

    /** نص الرفض عند السقف: يقول ما يتوقف وما يبقى متاحًا. */
    public static String officeLimitMessage(long max) {
        return (max == FREE_OFFICES ? "النسخة المجانية تتسع لـ " : "رخصتك تتسع لـ ") + max + " مكتبًا. احذف مكتبًا لا تحتاجه فيعود مكانه فارغًا، أو فعّل الرخصة من «المزيد ← الترخيص» لإضافة المزيد. "
                + "كل بياناتك تبقى متاحة: العرض والكشوف والتقارير والنسخ الاحتياطي والتصحيح.";
    }

    public static String boxLimitMessage(long max) {
        return (max == FREE_CASH_BOXES ? "النسخة المجانية فيها صندوق واحد. " : "رخصتك تتسع لـ " + max + " صناديق. ")
                + "لإضافة صناديق فعّل الرخصة أو وسّعها من «المزيد ← الترخيص». الصناديق الموجودة وحركاتها تبقى كما هي.";
    }

    public static String clientLimitMessage(long max) {
        return "بلغت " + max + " عميلًا" + (max == FREE_CLIENTS ? "، وهو حد النسخة المجانية" : "، وهو حد رخصتك") + ". أرشف عميلًا لا تحتاجه، "
                + "أو فعّل الرخصة من «المزيد ← الترخيص». العملاء الحاليون وحركاتهم تبقى متاحة.";
    }

    public static String userLimitMessage(long max) {
        return max <= 1 ? "النسخة المجانية تتسع لمستخدم واحد (المالك برمز مدير). تعدد المستخدمين بأدوار في النسخة الكاملة: «المزيد ← الترخيص». المستخدمون الحاليون يبقون كما هم."
                : "رخصتك تتسع لـ " + max + " مستخدمين نشطين. لإضافة مستخدم اطلب من البائع رمزًا بعدد أكبر، أو أوقف حسابًا لا تحتاجه. المستخدمون الحاليون يبقون كما هم.";
    }

    public static String waLimitMessage(long max) {
        return "استخدمت استخراج معلومات " + max + " رسالة واتساب اليوم، وهو حد " + (max == FREE_WA_PER_DAY ? "النسخة المجانية" : "رخصتك") + ". أكمل اليوم بالإدخال اليدوي العادي "
                + "(يعود الاستخراج غدًا)، أو فعّل النسخة الكاملة من «المزيد ← الترخيص» لاستخراج بلا حد.";
    }

    public static String txLimitMessage(String office, long max) {
        return "بلغ مكتب «" + office + "» " + max + " حركة، وهو حد " + (max == FREE_TX_PER_OFFICE ? "النسخة المجانية" : "رخصتك") + ". فعّل الرخصة من «المزيد ← الترخيص» للمتابعة عليه. "
                + "تصحيح الحركات القائمة وكل المكاتب الأخرى تحت السقف ما زالت تعمل.";
    }
}
