package com.smart.hawala;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * المستخدمون والصلاحيات. بدون مستخدمين مسجّلين يعمل التطبيق بصلاحيات كاملة كما كان.
 *
 * <p>10.1: المدير له كل شيء؛ وكل مستخدم آخر يختار له المدير صلاحياته واحدة واحدة (لا قوالب ثابتة). الحسابات القديمة
 * (محاسب، موظف، مشاهد) تتحول تلقائيًا إلى الصلاحيات نفسها التي كانت لها، ويستطيع المدير تعديلها.
 */
public final class Auth {
    private Auth() {}

    public static final String ADMIN = "ADMIN", ACCOUNTANT = "ACCOUNTANT", EMPLOYEE = "EMPLOYEE", VIEWER = "VIEWER";
    /** 10.1: مستخدم بصلاحيات يختارها المدير. */
    public static final String CUSTOM = "CUSTOM";
    public static final String[] ROLES = {ADMIN, ACCOUNTANT, EMPLOYEE, VIEWER};

    public enum Perm { TX_ADD, TX_EDIT, TX_CANCEL, VIEW, EXPORT, OFFICES, CURRENCIES, SETTINGS, BACKUP, RESTORE, USERS, DESIGN, DELETE, APPROVE, CASH, SYNC, VERIFY, DEVICES, NOTIFS, AUDIT,
        // 12.0: صلاحيات تفصيلية حسب أقسام التطبيق
        FX, XFER, TX_WA, BACKDATE, STATEMENT, RECON, HOLDS, CLIENTS, REPORTS, OWNER, RATES, LICENSE,
        // 12.4: الحركات الخاصة (ناقصة أجور، تحتاج مراجعة…): تسجيلها وتعديلها وتحويلها لحركات عادية
        SPECIAL,
        // 12.6: العملات والمعادن والأسعار مفصّلة: المعادن، وتحديث الأسعار من الإنترنت (منفصلة عن التعديل اليدوي)
        METALS, RATES_ONLINE,
        // 12.6: الصندوق مفصّلًا: «الصندوق» = الدخول والاستلام والتسليم والتحويل؛ وهذه فوقه
        CASH_EXPENSE, CASH_FX, CASH_COUNT }

    /** 10.3: ليست صلاحية بل قيد: كل حركات هذا المستخدم تنتظر الاعتماد قبل أن تدخل الأرصدة (أيًا كان مبلغها). */
    public static final String REVIEW = "REVIEW";

    /**
     * 12.0: الصلاحيات حسب أقسام التطبيق، يختار المدير كل واحدة لكل مستخدم. القسم الأول يُفتح افتراضيًا، والبقية بسهم.
     * علامة {@link #DETAILED} في قائمة المستخدم تعني أنها اختيرت تفصيليًا (12.0)؛ القوائم الأقدم بلا علامة تُشتق منها
     * الصلاحيات الجديدة من القديمة (التسجيل ← القص وبين مكتبين وواتساب والتاريخ السابق والأمانات… إلخ)، فلا يفقد أحد ما كان له.
     */
    public static final Object[][] SECTIONS = {
            {"الحركات", new Perm[]{Perm.VIEW, Perm.TX_ADD, Perm.FX, Perm.XFER, Perm.TX_WA, Perm.BACKDATE, Perm.SPECIAL, Perm.TX_EDIT, Perm.TX_CANCEL, Perm.APPROVE, Perm.VERIFY}},
            {"المكاتب", new Perm[]{Perm.STATEMENT, Perm.RECON, Perm.HOLDS, Perm.OFFICES, Perm.DELETE}},
            {"الصندوق والعملاء", new Perm[]{Perm.CASH, Perm.CASH_EXPENSE, Perm.CASH_FX, Perm.CASH_COUNT, Perm.CLIENTS}},
            {"التقارير والتصدير", new Perm[]{Perm.REPORTS, Perm.OWNER, Perm.EXPORT, Perm.DESIGN}},
            {"العملات والمعادن والأسعار", new Perm[]{Perm.CURRENCIES, Perm.METALS, Perm.RATES, Perm.RATES_ONLINE}},
            {"المزامنة", new Perm[]{Perm.SYNC, Perm.DEVICES}},
            {"السجلات والتنبيهات", new Perm[]{Perm.NOTIFS, Perm.AUDIT}},
            {"الإعدادات والإدارة", new Perm[]{Perm.SETTINGS, Perm.LICENSE, Perm.BACKUP, Perm.RESTORE, Perm.USERS}}};

    /** 12.0: علامة «اختيرت تفصيليًا» داخل قائمة صلاحيات المستخدم. */
    public static final String DETAILED = "P12";

    /** 12.6: علامة «القائمة تعرف صلاحيات 12.6» (المعادن والأسعار أونلاين). قائمة 12.0–12.5 بدونها تُشتق منها كما كانت تعمل. */
    public static final String DETAILED126 = "P126";

    /** علامات داخل القائمة ليست صلاحيات. */
    public static boolean marker(String p) { return REVIEW.equals(p) || DETAILED.equals(p) || DETAILED126.equals(p); }

    /** 10.3: الصلاحيات في مجموعات للعرض: [العنوان، الصلاحيات]. */
    public static final Object[][] GROUPS = {
            {"الحركات", new Perm[]{Perm.VIEW, Perm.TX_ADD, Perm.TX_EDIT, Perm.TX_CANCEL, Perm.APPROVE, Perm.VERIFY}},
            {"الصندوق والعملاء", new Perm[]{Perm.CASH}},
            {"المكاتب والعملات", new Perm[]{Perm.OFFICES, Perm.DELETE, Perm.CURRENCIES}},
            {"التقارير والتصدير", new Perm[]{Perm.EXPORT, Perm.DESIGN}},
            {"السجلات", new Perm[]{Perm.NOTIFS, Perm.AUDIT, Perm.DEVICES}},
            {"الإدارة", new Perm[]{Perm.SETTINGS, Perm.BACKUP, Perm.RESTORE, Perm.SYNC, Perm.USERS}}};

    /** هل تنتظر حركات هذا المستخدم اعتمادًا؟ (المدير أبدًا) */
    public static boolean needsReview(Model.User u) { return u != null && !isAdmin(u) && perms(u).contains(REVIEW); }

    /** ترتيب العرض في شاشة الصلاحيات، مع الشرح. */
    public static final Perm[] ORDER = {Perm.VIEW, Perm.TX_ADD, Perm.FX, Perm.XFER, Perm.TX_WA, Perm.BACKDATE, Perm.SPECIAL, Perm.TX_EDIT, Perm.TX_CANCEL, Perm.APPROVE, Perm.VERIFY,
            Perm.STATEMENT, Perm.RECON, Perm.HOLDS, Perm.OFFICES, Perm.DELETE, Perm.CASH, Perm.CASH_EXPENSE, Perm.CASH_FX, Perm.CASH_COUNT, Perm.CLIENTS, Perm.REPORTS, Perm.OWNER, Perm.EXPORT, Perm.DESIGN,
            Perm.CURRENCIES, Perm.METALS, Perm.RATES, Perm.RATES_ONLINE, Perm.SYNC, Perm.DEVICES, Perm.NOTIFS, Perm.AUDIT, Perm.SETTINGS, Perm.LICENSE, Perm.BACKUP, Perm.RESTORE, Perm.USERS};

    public static String title(Perm p) {
        switch (p) {
            case VIEW: return "عرض كل الحركات والأرصدة والتقارير";
            case TX_ADD: return "تسجيل حركات جديدة";
            case TX_EDIT: return "تعديل الحركات";
            case TX_CANCEL: return "إلغاء الحركات";
            case APPROVE: return "اعتماد الحركات المعلّقة";
            case CASH: return "الصندوق: الاستلام والتسليم والتحويل بين الصناديق";
            case CASH_EXPENSE: return "مصاريف الصندوق (رواتب، إيجار…)";
            case CASH_FX: return "بيع وشراء العملات بالصندوق";
            case CASH_COUNT: return "جرد الصندوق وتصحيح الفرق";
            case OFFICES: return "إضافة المكاتب وتعديلها";
            case DELETE: return "حذف المكاتب ودمجها";
            case CURRENCIES: return "إضافة العملات وتعديلها وتفعيلها";
            case METALS: return "المعادن الثمينة: إضافة العيارات وتعديلها";
            case EXPORT: return "التصدير والطباعة والمشاركة";
            case DESIGN: return "تصميم الكشوف والتقارير";
            case SETTINGS: return "الإعدادات (ومنها إعدادات الصندوق والترخيص)";
            case BACKUP: return "النسخ الاحتياطي";
            case RESTORE: return "الاستعادة من نسخة احتياطية";
            case SYNC: return "إدارة المزامنة (الإنشاء والإيقاف وكلمة السر)";
            case USERS: return "إدارة المستخدمين وصلاحياتهم";
            case VERIFY: return "التحقق من الحركات («تم التحقق»)";
            case DEVICES: return "رؤية أجهزة المزامنة وسجلاتها";
            case NOTIFS: return "سجل الإشعارات وتنبيهات المزامنة";
            case FX: return "قص العملات";
            case XFER: return "الحركات بين مكتبين";
            case TX_WA: return "التسجيل من رسائل واتساب (رسالة أو عدة رسائل)";
            case BACKDATE: return "تسجيل حركة بتاريخ سابق";
            case STATEMENT: return "كشف حساب المكتب";
            case RECON: return "مطابقة الرصيد (الإرسال والتأكيد)";
            case HOLDS: return "الأمانات والمبالغ المجمّدة";
            case SPECIAL: return "الحركات الخاصة (ناقصة أجور، تحتاج مراجعة…) وتحويلها لعادية";
            case CLIENTS: return "حسابات العملاء";
            case REPORTS: return "مركز التقارير والتقرير اليومي";
            case OWNER: return "تقارير صاحب المكتب";
            case RATES: return "تعديل الأسعار يدويًا (العملات والمعادن)";
            case RATES_ONLINE: return "تحديث الأسعار من الإنترنت وتغيير مصدرها";
            case LICENSE: return "الترخيص والتفعيل";
            case AUDIT: return "سجل العمليات (من فعل ماذا ومتى)";
            default: return p.name();
        }
    }

    /** شرح قصير يظهر تحت الصلاحية. */
    public static String hint(Perm p) {
        switch (p) {
            case VIEW: return "بدونها يرى حركاته التي سجّلها هو فقط";
            case TX_ADD: return "حوالات المكاتب وحركاتها";
            case APPROVE: return "يعتمد أو يصحح أو يرفض حركات غيره المعلّقة";
            case CASH: return "يفتح الصندوق ويستلم ويسلّم نقدًا (لحساب مكتب أو عميل أو بدونه). المصاريف والبيع والشراء والجرد صلاحيات منفصلة تحتها";
            case CASH_COUNT: return "الجرد يسجّل العجز أو الزيادة حركة تصحيح";
            case DELETE: return "عملية خطرة: امنحها بحذر";
            case RESTORE: return "تستبدل كل البيانات: للمدير غالبًا";
            case USERS: return "يدير حسابات الموظفين ويمنح من صلاحياته هو فقط؛ حساب المدير ورمزه للمدير وحده";
            case VERIFY: return "يؤكد أن حركة غيره تطابق رسالتها؛ لا قيد له ولا أثر في الأرصدة، وتحققه من حركته هو لا يُحتسب";
            case BACKDATE: return "بدونها تُسجَّل الحركة بتاريخ اليوم فقط";
            case OWNER: return "النمو والتركّز وعمر الديون والأجور: أرقام حساسة";
            case CLIENTS: return "دفتر العملاء الأفراد وحركاتهم";
            case NOTIFS: return "تنبيهات حساسة (تغيّر الصلاحيات، الأجهزة، التعارضات): بدونها لا يرى السجل ولا قسم «للمراجعة»";
            case AUDIT: return "كل ما فعله كل مستخدم: للمدير غالبًا";
            case CURRENCIES: return "رمز العملة واسمها ومنازلها، والتفعيل والتعطيل";
            case METALS: return "ذهب وفضة… بعياراتها ووحداتها وهل تدخل الميزان بالدولار";
            case RATES: return "السعر اليدوي يتقدم على الأونلاين ويغيّر التقويم بالدولار فقط، لا المبالغ المسجلة";
            case RATES_ONLINE: return "جلب الأسعار الآن ومصدرها (عنوان الخادم)";
            case DEVICES: return "قائمة الأجهزة وآخر ظهورها وسجل كل جهاز وأخطاء المزامنة. بدونها يرى حالة المزامنة فقط. (صلاحية «إدارة المزامنة» تشملها)";
            default: return "";
        }
    }

    public static String label(String role) {
        switch (role == null ? "" : role) {
            case ADMIN: return "مدير (كل الصلاحيات)";
            case ACCOUNTANT: return "محاسب (حركات وتعديل وإلغاء واعتماد ومكاتب وتصدير)";
            case EMPLOYEE: return "موظف (تسجيل حركات وحركات الصندوق، وعرض)";
            case VIEWER: return "مشاهد (عرض فقط)";
            case CUSTOM: return "صلاحيات مخصّصة";
            default: return role;
        }
    }

    public static String shortLabel(String role) {
        switch (role == null ? "" : role) {
            case ADMIN: return "مدير";
            case ACCOUNTANT: return "محاسب";
            case EMPLOYEE: return "موظف";
            case VIEWER: return "مشاهد";
            default: return "مستخدم";
        }
    }

    /** وصف المستخدم: «مدير» أو عدد صلاحياته. */
    public static String describe(Model.User u) {
        if (u == null) return "";
        if (isAdmin(u)) return "مدير — كل الصلاحيات";
        Set<String> p = perms(u);
        if (p.isEmpty()) return "بلا صلاحيات";
        List<String> names = new ArrayList<>();
        for (Perm x : ORDER) if (p.contains(x.name())) names.add(shortTitle(x));
        if (p.contains(REVIEW)) names.add("حركاته تحتاج اعتماد");
        return String.join("، ", names);
    }

    static String shortTitle(Perm p) {
        switch (p) {
            case VIEW: return "عرض";
            case TX_ADD: return "تسجيل";
            case TX_EDIT: return "تعديل";
            case TX_CANCEL: return "إلغاء";
            case APPROVE: return "اعتماد";
            case CASH: return "صندوق";
            case CASH_EXPENSE: return "مصاريف";
            case CASH_FX: return "بيع وشراء";
            case CASH_COUNT: return "جرد";
            case OFFICES: return "مكاتب";
            case DELETE: return "حذف مكاتب";
            case CURRENCIES: return "عملات";
            case METALS: return "معادن";
            case RATES_ONLINE: return "أسعار أونلاين";
            case EXPORT: return "تصدير";
            case DESIGN: return "تصميم";
            case SETTINGS: return "إعدادات";
            case BACKUP: return "نسخ";
            case RESTORE: return "استعادة";
            case SYNC: return "مزامنة";
            case USERS: return "مستخدمون";
            case VERIFY: return "تحقق";
            case DEVICES: return "أجهزة المزامنة";
            case NOTIFS: return "سجل الإشعارات";
            case AUDIT: return "سجل العمليات";
            case FX: return "قص";
            case XFER: return "بين مكتبين";
            case TX_WA: return "واتساب";
            case BACKDATE: return "تاريخ سابق";
            case STATEMENT: return "كشف";
            case RECON: return "مطابقة";
            case HOLDS: return "أمانات";
            case SPECIAL: return "خاصة";
            case CLIENTS: return "عملاء";
            case REPORTS: return "تقارير";
            case OWNER: return "تقارير المالك";
            case RATES: return "أسعار يدوية";
            case LICENSE: return "ترخيص";
            default: return p.name();
        }
    }

    public static boolean isAdmin(Model.User u) { return u != null && ADMIN.equals(u.role); }

    /** صلاحيات المستخدم: المحفوظة له، أو (لحساب قديم بلا قائمة) صلاحيات دوره القديم. */
    public static Set<String> perms(Model.User u) {
        return expand(u.perms != null ? parse(u.perms) : parse(legacyPerms(u.role)));
    }

    /**
     * 12.0: قائمة بلا علامة {@link #DETAILED} (من إصدار أقدم أو من جهاز لم يُحدَّث): تُشتق الصلاحيات التفصيلية الجديدة
     * من القديمة كما كانت تعمل، فلا يفقد مستخدم شيئًا. ومع العلامة تُؤخذ كما اختارها المدير.
     */
    public static Set<String> expand(Set<String> s) {
        if (s.contains(DETAILED)) {
            if (s.contains(DETAILED126)) return s;
            // 12.0–12.5: «العملات» كانت تشمل المعادن، و«أسعار الصرف» كانت تشمل التحديث أونلاين
            Set<String> o = new LinkedHashSet<>(s);
            if (s.contains("CURRENCIES")) o.add(Perm.METALS.name());
            if (s.contains("RATES")) o.add(Perm.RATES_ONLINE.name());
            if (s.contains("CASH")) for (Perm p : new Perm[]{Perm.CASH_EXPENSE, Perm.CASH_FX, Perm.CASH_COUNT}) o.add(p.name());
            return o;
        }
        Set<String> o = new LinkedHashSet<>(s);
        if (s.contains("TX_ADD")) for (Perm p : new Perm[]{Perm.XFER, Perm.TX_WA, Perm.BACKDATE, Perm.HOLDS, Perm.SPECIAL}) o.add(p.name());
        if (s.contains("TX_ADD") && s.contains("TX_EDIT")) o.add(Perm.FX.name());   // كان القص يحتاج التسجيل والتعديل
        if (s.contains("VIEW")) for (Perm p : new Perm[]{Perm.STATEMENT, Perm.RECON, Perm.REPORTS, Perm.OWNER}) o.add(p.name());
        if (s.contains("CASH")) for (Perm p : new Perm[]{Perm.CLIENTS, Perm.CASH_EXPENSE, Perm.CASH_FX, Perm.CASH_COUNT}) o.add(p.name());
        if (s.contains("CURRENCIES")) for (Perm p : new Perm[]{Perm.RATES, Perm.METALS, Perm.RATES_ONLINE}) o.add(p.name());
        if (s.contains("SETTINGS")) o.add(Perm.LICENSE.name());
        return o;
    }

    public static Set<String> parse(String csv) {
        Set<String> s = new LinkedHashSet<>();
        if (csv != null) for (String x : csv.split(",")) if (!x.trim().isEmpty()) s.add(x.trim());
        return s;
    }

    public static String csv(Set<String> s) {
        List<String> out = new ArrayList<>();
        for (Perm p : ORDER) if (s.contains(p.name())) out.add(p.name());
        if (s.contains(REVIEW)) out.add(REVIEW);
        if (s.contains(DETAILED)) out.add(DETAILED);
        if (s.contains(DETAILED126)) out.add(DETAILED126);
        return String.join(",", out);
    }

    /** صلاحيات الدور القديم (لترحيل الحسابات الموجودة). */
    public static String legacyPerms(String role) {
        Set<String> s = new LinkedHashSet<>();
        for (Perm p : Perm.values()) if (can(role, p)) s.add(p.name());
        return csv(s);
    }

    /** 10.1: الفحص الفعلي للمستخدم. */
    public static boolean can(Model.User u, Perm p) {
        if (u == null) return false;
        if (isAdmin(u)) return true;
        return perms(u).contains(p.name());
    }

    /** الأدوار القديمة (للترحيل والاختبارات). role == null يعني وضع المستخدم الواحد (كل الصلاحيات). */
    public static boolean can(String role, Perm p) {
        if (role == null || ADMIN.equals(role)) return true;
        switch (role) {
            case ACCOUNTANT:
                return p == Perm.TX_ADD || p == Perm.TX_EDIT || p == Perm.TX_CANCEL || p == Perm.VIEW || p == Perm.EXPORT || p == Perm.OFFICES || p == Perm.APPROVE || p == Perm.CASH;
            case EMPLOYEE:
                return p == Perm.TX_ADD || p == Perm.VIEW || p == Perm.CASH;
            case VIEWER:
                return p == Perm.VIEW;
            default:
                return false;
        }
    }

    /** للاختبار والواجهة: كل الصلاحيات ما عدا المستخدمين. */
    public static List<Perm> all() { return Arrays.asList(ORDER); }
}
