package com.smart.hawala;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 12.3: قاموس كلمات برامج المحادثة بالعربية والإنجليزية (وبعض التركية والرومانية) لتمييز الرسالة الحقيقية عمّا حولها:
 * <ul>
 * <li>{@link #systemText}: نص كله تنبيه من البرنامج لا رسالة (فاصل يوم «Fri 6 Nov»، «33 unread messages»، «تم حذف هذه الرسالة»،
 * «Message history sent by a group member. Learn more»، مكالمة فائتة، انضمام وخروج من الغروب…).</li>
 * <li>{@link #metaLine}: سطر داخل الفقاعة ليس من نصها (Forwarded، Edited، حجم الملف و«Show in Finder»، «صورة»…).</li>
 * <li>{@link #sender}: سطر اسم المرسل في الغروب («~ Abo Nour +40 765 450 476»).</li>
 * <li>{@link #COURTESY}: كلمات التحية والمجاملة («يعطيكم العافية»، «السادة الكرام»، «good morning») فلا تُستخرج اسمًا ولا عملة.</li>
 * </ul>
 * منطق بحت بلا أندرويد حتى يُختبر، ومشترك بين الهاتف والكمبيوتر.
 */
public final class WaWords {
    private WaWords() {}

    // ===================================================================================
    // التواريخ وفواصل الأيام
    // ===================================================================================

    static final String DAYS = "الأحد|الاحد|الاثنين|الإثنين|الثلاثاء|الأربعاء|الاربعاء|الخميس|الجمعة|الجمعه|السبت|"
            + "sunday|monday|tuesday|wednesday|thursday|friday|saturday|sun|mon|tue|tues|wed|thu|thur|thurs|fri|sat|"
            + "pazar|pazartesi|salı|sali|çarşamba|carsamba|perşembe|persembe|cuma|cumartesi|"
            + "duminică|duminica|luni|marți|marti|miercuri|joi|vineri|sâmbătă|sambata";
    static final String MONTHS = "يناير|فبراير|مارس|أبريل|ابريل|إبريل|مايو|يونيو|يوليو|أغسطس|اغسطس|سبتمبر|أكتوبر|اكتوبر|نوفمبر|ديسمبر|"
            + "كانون الثاني|شباط|آذار|اذار|نيسان|أيار|ايار|حزيران|تموز|آب|اب|أيلول|ايلول|تشرين الأول|تشرين الاول|تشرين الثاني|كانون الأول|كانون الاول|"
            + "january|february|march|april|may|june|july|august|september|october|november|december|"
            + "jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec|"
            + "ocak|şubat|subat|mart|nisan|mayıs|mayis|haziran|temmuz|ağustos|agustos|eylül|eylul|ekim|kasım|kasim|aralık|aralik|"
            + "ianuarie|februarie|martie|aprilie|mai|iunie|iulie|septembrie|octombrie|noiembrie|decembrie";

    private static final Pattern DATE_PILL = Pattern.compile("(?iu)^\\s*(?:"
            + "اليوم|أمس|امس|البارحة|today|yesterday|bugün|bugun|dün|dun|azi|astăzi|astazi|ieri|"
            + "(?:" + DAYS + ")\\.?(?:\\s*[,،]?\\s*\\d{1,2}(?:\\s+(?:" + MONTHS + ")\\.?)?(?:\\s*[,،]?\\s*\\d{2,4})?)?|"
            + "\\d{1,2}\\s+(?:" + MONTHS + ")\\.?(?:\\s*[,،]?\\s*\\d{2,4})?|"
            + "(?:" + MONTHS + ")\\.?\\s+\\d{1,2}(?:\\s*[,،]?\\s*\\d{2,4})?|"
            + "\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4}"
            + ")\\s*$");

    // ===================================================================================
    // تنبيهات البرنامج (ليست رسائل)
    // ===================================================================================

    private static final List<Pattern> SYSTEM = new ArrayList<>();

    private static void sys(String re) { SYSTEM.add(Pattern.compile("(?iu)^\\s*(?:" + re + ")\\s*[.!]?\\s*$")); }

    static {
        sys("\\d+\\s*(?:رسالة|رسائل|رساله)\\s*غير\\s*مقروءة|(?:رسالة|رسائل)\\s*غير\\s*مقروءة\\s*\\(?\\d*\\)?|\\d+\\s*unread\\s*messages?|unread\\s*messages?|"
                + "\\d+\\s*okunmamış\\s*mesaj|\\d+\\s*mesaje\\s*necitite");
        sys(".*(?:مشفّرة تمامًا|مشفرة تماما|مشفّرة بين الطرفين|مشفرة بين الطرفين|end-to-end encrypted|uçtan uca şifreli|criptate integral).*");
        sys("this message was deleted|you deleted this message|message deleted|تم حذف هذه الرسالة|حُذفت هذه الرسالة|حذفت هذه الرسالة|"
                + "لقد حذفت هذه الرسالة|bu mesaj silindi|mesajul a fost șters");
        sys(".*message history sent by a group member.*|.*learn more|.*اعرف المزيد|.*معرفة المزيد|.*daha fazla bilgi");
        sys("waiting for this message.*|في انتظار هذه الرسالة.*|بانتظار هذه الرسالة.*");
        sys("(?:missed\\s+)?(?:voice|video)\\s+call.*|مكالمة\\s+(?:صوتية|فيديو)(?:\\s+فائتة)?.*|cevapsız\\s+(?:sesli|görüntülü)\\s+arama.*");
        sys(".*(?:security code (?:with .{1,40} )?changed|turned (?:on|off) disappearing messages|تغيّر رمز الأمان|تغير رمز الامان|"
                + "فعّل الرسائل المؤقتة|عطّل الرسائل المؤقتة|فعل الرسائل المؤقتة|عطل الرسائل المؤقتة).*");
        sys("اكتب رسالة|type a message|بحث|search|whatsapp|واتساب|telegram|تلغرام|تيليجرام|الدردشات|chats|المجتمعات|communities|الحالة|status|"
                + "online|متصل الآن|متصل|typing…?|typing\\.\\.\\.|يكتب…?|يكتب\\.\\.\\.|last seen.*|آخر ظهور.*|click here for group info|"
                + "اضغط هنا لمعلومات المجموعة|tap here for group info");
        sys("صورة|photo|image|فيديو|video|ملصق|sticker|gif|رسالة صوتية|voice message|مستند|document|ملف|file|موقع|location|جهة اتصال|contact|"
                + "استطلاع|poll|tap to download|click to download|انقر للتنزيل|اضغط للتنزيل");
    }

    /** نص الفقاعة كله تنبيه من البرنامج أو فاصل يوم (لا يُرقَّم ولا يُنقل). */
    public static boolean systemText(String t) {
        if (t == null) return true;
        String s = clean(t);
        if (s.isEmpty()) return true;
        // كل الأسطر تنبيهات (فاصل يوم فوق «رسائل غير مقروءة» مثلًا)
        for (String line : s.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (!systemLine(l)) return false;
        }
        return true;
    }

    /** انضمام وخروج وإضافة في الغروب: قصيرة، وبلا أرقام إلا رقم هاتف («+40 765… joined»)، حتى لا تُحذف رسالة فيها «added 500». */
    private static final Pattern MEMBERS = Pattern.compile("(?iu)^.{1,60}?\\s(?:joined using this group's invite link|joined|left|was added|were added|was removed|"
            + "added .{1,60}|removed .{1,60}|created group .{1,60}|changed the group.{0,60}|changed this group's.{0,60}|changed the subject.{0,60}|"
            + "changed their phone number.{0,60})$|"
            + "^.{1,60}?\\s(?:انضم|انضمت|غادر|غادرت|خرج|خرجت)(?:\\s.{0,40})?$|^.{1,60}?\\s(?:أضاف|اضاف|أزال|ازال)\\s.{1,60}$|"
            + "^.{0,60}(?:أنشأ|انشأ|أنشأت|انشأت)\\s(?:المجموعة|المجموعه|الغروب).{0,60}$|^.{0,60}(?:غيّر|غير|غيّرت|غيرت)\\s(?:اسم|صورة|وصف)\\s(?:المجموعة|المجموعه|الغروب).{0,60}$");
    private static final Pattern PHONES = Pattern.compile("\\+\\d[\\d \\-]{6,}");

    static boolean systemLine(String l) {
        String s = clean(l);
        if (s.isEmpty() || DATE_PILL.matcher(s).matches() || WaCuts.timeLine(s)) return true;
        for (Pattern p : SYSTEM) if (p.matcher(s).matches()) return true;
        if (s.length() <= 120 && !PHONES.matcher(s).replaceAll(" ").matches(".*\\d.*") && MEMBERS.matcher(s).matches()) return true;
        return false;
    }

    // ===================================================================================
    // أسطر داخل الفقاعة ليست من نصها
    // ===================================================================================

    private static final Pattern META = Pattern.compile("(?iu)^\\s*(?:"
            + "forwarded|forwarded many times|محوّلة|محولة|تمت إعادة توجيهها|تمت إعادة التوجيه|معاد توجيهها|أُعيد توجيهها|تمت إعادة توجيهها عدة مرات|"
            + "تمت إعادة توجيهها كثيرًا|iletildi|redirecționat|"
            + "edited|تم التعديل|معدّلة|معدلة|تم تعديلها|düzenlendi|editat|"
            + "show in finder|show in folder|open|فتح|عرض في المجلد|إظهار في Finder|save as…?|حفظ باسم…?|download|تنزيل|"
            + "\\d+(?:[.,]\\d+)?\\s*(?:kb|mb|gb|ك\\.?ب|م\\.?ب|كيلوبايت|ميغابايت|ميجابايت)(?:\\s*[-–•·]\\s*.*)?|"
            + "(?:pdf|docx?|xlsx?|pptx?|zip|rar|jpe?g|png|apk)\\s*[•·-]\\s*.*|\\d+\\s*(?:pages?|صفحات|صفحة)"
            + ")\\s*$");

    /** سطر داخل الفقاعة ليس من نص الرسالة (يُحذف). */
    public static boolean metaLine(String l) {
        String s = clean(l);
        return !s.isEmpty() && META.matcher(s).matches();
    }

    // ===================================================================================
    // اسم المرسل في الغروب
    // ===================================================================================

    private static final String PHONE = "\\+?\\d{1,4}(?:[ \\-]?\\d{2,4}){2,5}";
    private static final Pattern SENDER_NAME_PHONE = Pattern.compile("^\\s*~?\\s*([^\\d+~\\n]{1,40}?)\\s*~?\\s*(" + PHONE + ")\\s*$");
    private static final Pattern SENDER_PHONE_NAME = Pattern.compile("^\\s*(" + PHONE + ")\\s*~\\s*([^\\d+~\\n]{1,40}?)\\s*$");
    private static final Pattern SENDER_TILDE = Pattern.compile("^\\s*~\\s*([^\\d~\\n]{1,40}?)\\s*$");
    private static final Pattern PHONE_ONLY = Pattern.compile("^\\s*(" + PHONE + ")\\s*$");

    /**
     * أول سطر في فقاعة الغروب: اسم المرسل ورقمه («~ Abo Nour +40 765 450 476»، «+90 545 965 72 40 ~زهرة حلب»، «~ أبو عمر»).
     * يعيد {الاسم، الرقم} أو null إن لم يكن سطر مرسل.
     */
    public static String[] sender(String line) {
        if (line == null) return null;
        String s = MessageParser.digits(WaText.stripMarks(line)).trim();
        if (s.isEmpty() || s.length() > 70) return null;
        Matcher m = SENDER_NAME_PHONE.matcher(s);
        if (m.matches() && (s.contains("~") || s.contains("+"))) return new String[]{m.group(1).trim(), m.group(2).trim()};
        m = SENDER_PHONE_NAME.matcher(s);
        if (m.matches()) return new String[]{m.group(2).trim(), m.group(1).trim()};
        m = SENDER_TILDE.matcher(s);
        if (m.matches()) return new String[]{m.group(1).trim(), ""};
        m = PHONE_ONLY.matcher(s);
        if (m.matches() && s.startsWith("+")) return new String[]{"", m.group(1).trim()};
        return null;
    }

    /**
     * نص فقاعة مقروءة من الشاشة ← {المرسل، النص}: يُفصل سطر المرسل في أولها، وتُحذف أسطر «Forwarded» و«Edited» وحجم الملف أينما كانت،
     * وتنبيهات البرنامج الملتصقة بها.
     */
    public static String[] split(String text) {
        List<String> l = new ArrayList<>(Arrays.asList(WaText.stripMarks(text == null ? "" : text).replace("\r\n", "\n").split("\n", -1)));
        String who = "";
        while (!l.isEmpty() && l.get(0).trim().isEmpty()) l.remove(0);
        if (l.size() > 1) {
            String[] s = sender(l.get(0));
            if (s != null) {
                who = s[0].isEmpty() ? s[1] : s[0];
                l.remove(0);
            }
        }
        List<String> out = new ArrayList<>();
        for (String x : l) {
            if (metaLine(x)) continue;
            if (systemLine(x) && !WaCuts.timeLine(x) && DATE_PILL.matcher(clean(x)).matches()) continue;
            out.add(x);
        }
        while (!out.isEmpty() && out.get(0).trim().isEmpty()) out.remove(0);
        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) out.remove(out.size() - 1);
        return new String[]{who, String.join("\n", out)};
    }

    // ===================================================================================
    // التحية والمجاملة
    // ===================================================================================

    /** كلمات تحية ومجاملة (مطبّعة كما يطبّع المحلّل) لا تكون اسم مستفيد ولا اسم عملة. */
    public static final Set<String> COURTESY = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "السلام", "سلام", "عليكم", "ورحمة", "ورحمه", "وبركاته", "بركاته", "مرحبا", "مرحبتين", "اهلا", "أهلا", "اهلين", "هلا",
            "حياكم", "حياك", "يعطيكم", "يعطيك", "العافية", "العافيه", "تحية", "تحيه", "وبعد", "التحية",
            "صباح", "مساء", "الخير", "السادة", "الساده", "الكرام", "الاخوة", "الأخوة", "الاخوه", "اخوتي",
            "اخواني", "الأفاضل", "الافاضل", "المحترمين", "المحترم", "حضرة", "حضرات", "الاستاذ", "الأستاذ", "استاذ", "أستاذ", "جماعة", "جماعه",
            "شباب", "الشباب", "يسلمو", "يسلموا", "تسلم", "تسلمو", "مشكور", "مشكورين", "جزاكم", "فيكم", "ممنون",
            "ممنونين", "تكرم", "تكرموا", "لطفا", "لطفًا", "رجاء", "رجاءً", "مطابقة", "مطابقه", "رصيد", "الرصيد",
            "hello", "hi", "hey", "dear", "dears", "good", "morning", "evening", "afternoon", "night", "greetings", "regards", "best",
            "kind", "sir", "sirs", "madam", "gentlemen", "team", "friends", "brothers", "thanks", "thank", "you", "please", "kindly",
            "merhaba", "selam", "selamlar", "günaydın", "iyi", "akşamlar", "teşekkürler", "sağol", "bună", "buna", "ziua", "mulțumesc", "mersi")));

    // ===================================================================================
    // بدايات الرسائل (لاقتراح القص)
    // ===================================================================================

    /** كلمات تبدأ بها رسالة حوالة عادةً: سطر يبدأ بإحداها مثل أول سطر = رسالة جديدة مقترحة في شاشة القص. */
    public static final Set<String> OPENERS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "حوالة", "حواله", "حوالات", "تسليم", "سلم", "سلّم", "سلموا", "استلام", "استلم", "استلمت", "استلمنا", "مطلوب", "المطلوب", "تحويل", "حول", "حوّل",
            "ارسال", "إرسال", "ارسل", "أرسل", "دفع", "ادفع", "دفعة", "قبض", "سحب", "ايداع", "إيداع", "قيد", "مطابقة", "رصيد", "تسليم:", "حوالة:",
            "transfer", "send", "sent", "pay", "paid", "payment", "deliver", "received", "receive", "deposit", "withdraw", "havale", "gönder", "teslim",
            "transfer:", "plată", "trimite")));

    /** الكلمة الأولى في السطر (مطبّعة للمقارنة). */
    public static String firstWord(String line) {
        String s = clean(line).replaceAll("^[\\p{Punct}\\s•·\\-–—]+", "");
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    /** النص بلا علامات الاتجاه والمسافات الزائدة، والأرقام عربية غربية. */
    static String clean(String t) {
        return MessageParser.digits(WaText.stripMarks(t)).replace('\u00A0', ' ').replaceAll("[ \\t]+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
