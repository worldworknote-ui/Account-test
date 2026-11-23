package com.smart.hawala;

/**
 * 10.5 (§5): المكتب المثبّت لرسائل واتساب — يختاره المحاسب صراحة، ولا يتغير تلقائيًا (لا بالمرسل ولا بآخر مكتب).
 * <ul>
 *   <li>«الجلسة»: من تثبيت المكتب حتى {@link #SESSION_MS} بلا تسجيل. جلسة جديدة تطلب تأكيد المكتب قبل أول حفظ.</li>
 *   <li>تغيير المكتب أثناء الجلسة يمر بتحذير صريح بالنص المطلوب، ثم تُسجَّل الرسائل التالية للمكتب الجديد.</li>
 *   <li>إعداد خاص بهذا الجهاز (لا يُزامن): لكل محاسب على جهازه مكتبه المثبّت.</li>
 * </ul>
 * مشترك بين الهاتف والكمبيوتر، فالقواعد واحدة.
 */
public final class WaPin {
    private WaPin() {}

    public static final String OFFICE = "wa_pin_office", AT = "wa_pin_at";
    /** مدة الجلسة منذ آخر تثبيت أو حفظ. */
    public static final long SESSION_MS = 8L * 3600 * 1000;

    /** المكتب المثبّت في الجلسة الحالية (نشط وموجود)، أو null: جلسة جديدة تحتاج تأكيدًا. */
    public static Model.Office current(Db db) {
        long id = db.settingLong(OFFICE, -1), at = db.settingLong(AT, 0);
        if (id <= 0 || Clock.now() - at > SESSION_MS) return null;
        Model.Office o = db.office(id);
        return o != null && o.active ? o : null;
    }

    /** يثبّت المكتب (قرار المحاسب) ويبدأ الجلسة أو يمددها. */
    public static void pin(Db db, long office) {
        db.setSettingQuiet(OFFICE, "" + office);
        db.setSettingQuiet(AT, "" + Clock.now());
    }

    /** بعد كل حفظ من رسالة: تمتد الجلسة. */
    public static void touch(Db db) {
        if (db.settingLong(OFFICE, -1) > 0) db.setSettingQuiet(AT, "" + Clock.now());
    }

    public static void clear(Db db) {
        db.deleteSetting(OFFICE);
        db.deleteSetting(AT);
    }

    /** نص التحذير عند تغيير المكتب أثناء الجلسة (كما طُلب حرفيًا). */
    public static String changeWarning(String oldName, String newName) {
        return "سيتم تغيير المكتب من «" + Model.nz(oldName) + "» إلى «" + Model.nz(newName) + "». الرسائل التالية ستُسجّل للمكتب الجديد";
    }

    /** نص تأكيد المكتب في جلسة جديدة. */
    public static String sessionQuestion(String name) {
        return "جلسة رسائل واتساب جديدة. ستُسجَّل هذه الرسالة والرسائل التالية للمكتب «" + Model.nz(name) + "» حتى تغيّره بنفسك. هل هذا هو المكتب الصحيح؟";
    }

    /** ما يلزم قبل حفظ رسالة واتساب لمكتب: لا شيء، أو تأكيد جلسة جديدة، أو تحذير تغيير. */
    public enum Need { NOTHING, CONFIRM_SESSION, CONFIRM_CHANGE }

    public static Need before(Db db, long office) {
        Model.Office p = current(db);
        if (p == null) return Need.CONFIRM_SESSION;
        return p.id == office ? Need.NOTHING : Need.CONFIRM_CHANGE;
    }
}
