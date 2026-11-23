package com.smart.hawala;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * يستخرج من رسالة حوالة (من واتساب) اسم المستفيد والمبلغ والعملة والهاتف والمدينة، أيًّا كان ترتيب الأسطر.
 * منطق بحت بلا أندرويد حتى يُختبر على الكمبيوتر. لا يغيّر النص الأصلي؛ يقترح القيم فقط.
 */
public final class MessageParser {
    private MessageParser() {}

    /** عملة كما هي معرّفة في البرنامج. */
    public static final class Cur {
        public final String code, name;
        /** 10.5: منازل العملة (−1 = غير معروفة): عملة بثلاث منازل لا يُخمَّن فيها «1.250». */
        public final int decimals;

        public Cur(String code, String name) { this(code, name, -1); }

        public Cur(String code, String name, int decimals) {
            this.code = code;
            this.name = name == null ? "" : name;
            this.decimals = decimals;
        }
    }

    public static final class Result {
        public String person = "";
        /** المبلغ كرقم عادي مثل 500 أو 1250.5 */
        public String amount = "";
        /** رمز العملة من عملات البرنامج، أو null إن لم تُعرف. */
        public String currency;
        /** الكلمة التي دلّت على العملة في الرسالة (للتنبيه إن لم تكن مفعّلة). */
        public String currencyWord = "";
        public String phone = "";
        public String city = "";
        public String sender = "";
        public String fee = "";
        /**
         * «راجع» بلغة المكاتب: أجرة يدفعها المكتب لنا. «راجع 1%» أو «راجع ١ بالمية» = 1 بالمئة من المبلغ لنا،
         * «راجع 5 بالألف» = 5 بالألف، «راجع 50 دولار» = أجرة محددة 50. فارغ = لم يُذكر راجع بنسبة أو مبلغ واضح.
         */
        public String rajeRate = "";
        /** 100 (بالمئة) أو 1000 (بالألف) مع rajeRate. */
        public int rajePer = 100;
        /** مبلغ راجع محدد (بعملة المبلغ). */
        public String rajeAmount = "";
        /** ذُكرت كلمة «راجع» (حتى بلا رقم واضح). */
        public boolean raje;
        /** رقم بعد «راجع» بلا وحدة (لا يُخمَّن: نسبة أم مبلغ؟). */
        public String rajeHint = "";
        public boolean net;
        /** كلمة بعد المبلغ لم يعرفها البرنامج (قد تكون اسم عملة عامي مثل «يوز»)، ليربطها المستخدم بعملة. */
        public String unknownWord = "";
        /** 10.5: نص المبلغ كما ورد في الرسالة (مثل «50000 الف»)، وتنبيه حين يحتاج تفسيره مراجعة المحاسب. */
        public String amountText = "", amountNote = "";

        public boolean any() { return !person.isEmpty() || !amount.isEmpty() || currency != null; }
    }

    // ===================================================================================
    // التطبيع
    // ===================================================================================

    /** الأرقام العربية والفارسية إلى 0-9، وفواصلها إلى , و . */
    static String digits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '٠' && ch <= '٩') sb.append((char) ('0' + ch - '٠'));
            else if (ch >= '۰' && ch <= '۹') sb.append((char) ('0' + ch - '۰'));
            else if (ch == '٫') sb.append('.');
            else if (ch == '٬') sb.append(',');
            else sb.append(ch);
        }
        return sb.toString();
    }

    /** يحذف التطويل والتشكيل ويجمع المسافات، مع إبقاء الحروف كما كُتبت (للعرض). */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : digits(s).toCharArray()) {
            if (ch == 'ـ') continue;                         // تطويل
            if (ch >= 'ً' && ch <= 'ٟ') continue;       // تشكيل
            if (ch == 'ٰ' || ch == '\u200F' || ch == '\u200E' || ch == '​' || ch == '\u202A' || ch == '\u202B' || ch == '\u202C') continue;
            if (ch == '*' || ch == '~' || ch == '_') continue;     // تنسيق واتساب (عريض/مشطوب/مائل)
            if (ch == '\u00a0' || ch == '\t') ch = ' ';
            sb.append(ch);
        }
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    /**
     * للتطابق: كالتنظيف + توحيد الألف والتاء المربوطة والياء، وحروف صغيرة بلا علامات:
     * Yılmaz ← yilmaz، Ștefan ← stefan، Müller ← muller، Señor ← senor، Ёлка ← елка.
     */
    static String norm(String s) {
        String c = clean(s).replace('İ', 'i').toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(c.length());
        for (char ch : c.toCharArray()) {
            if (ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ') ch = 'ا';
            else if (ch == 'ة') ch = 'ه';
            else if (ch == 'ى' || ch == 'ی') ch = 'ي';    // ی الفارسية
            else if (ch == 'ک') ch = 'ك';
            else if (ch == 'ё') ch = 'е';
            else if (ch >= '̀' && ch <= 'ͯ') continue;   // علامات مركّبة (مثل نقطة i التركية)
            else if ((ch >= 'À' && ch <= 'ɏ') || (ch >= 'Ḁ' && ch <= 'ỿ')) {
                sb.append(foldLatin(ch));
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String foldLatin(char ch) {
        switch (ch) {
            case 'ı': return "i";
            case 'ß': return "ss";
            case 'æ': return "ae";
            case 'œ': return "oe";
            case 'ø': return "o";
            case 'đ': return "d";
            case 'ł': return "l";
            case 'þ': return "th";
            default:
                String d = java.text.Normalizer.normalize(String.valueOf(ch), java.text.Normalizer.Form.NFD);
                StringBuilder o = new StringBuilder();
                for (char x : d.toCharArray()) if (Character.getType(x) != Character.NON_SPACING_MARK) o.append(x);
                return o.length() == 0 ? String.valueOf(ch) : o.toString();
        }
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.split("[\\s،,:;؛\\-–—/\\\\|*()\\[\\]\"'«»!؟?.]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static boolean hasDigit(String s) {
        for (char ch : s.toCharArray()) if (ch >= '0' && ch <= '9') return true;
        return false;
    }

    /** هل تحتوي العبارة (مطبّعة) على الكلمة/العبارة كاملة؟ */
    static boolean hasPhrase(String normLine, String phrase) {
        String hay = " " + String.join(" ", words(normLine)) + " ";
        String nee = " " + String.join(" ", words(phrase)) + " ";
        return hay.contains(nee);
    }

    private static boolean startsWithPhrase(List<String> w, List<String> p) {
        if (p.isEmpty() || w.size() < p.size()) return false;
        for (int i = 0; i < p.size(); i++) if (!w.get(i).equals(p.get(i))) return false;
        return true;
    }

    // ===================================================================================
    // القوائم
    // ===================================================================================

    private static Set<String> set(String... a) {
        Set<String> s = new HashSet<>();
        for (String x : a) s.add(norm(x));
        return s;
    }

    private static List<String> list(String... a) {
        List<String> s = new ArrayList<>();
        for (String x : a) s.add(norm(x));
        return s;
    }

    static final List<String> GREETINGS = list("السلام عليكم", "سلام عليكم", "السلام عليكم ورحمة الله", "مرحبا", "مرحباً", "صباح الخير", "مساء الخير",
            "اهلا", "أهلا وسهلا", "تحية طيبة", "هلا", "يا هلا", "صباح النور", "مساء النور", "كيفكم", "كيف الحال",
            "hi", "hello", "hey", "good morning", "good evening", "good afternoon", "dear", "salam", "salaam", "salam alaikum", "salamu alaikum",
            "assalamu alaikum", "as salamu alaikum", "selam", "selamlar", "selamun aleykum", "selamünaleyküm", "merhaba", "merhabalar", "günaydın",
            "iyi günler", "iyi akşamlar", "salut", "bună", "bună ziua", "bună seara", "bonjour", "bonsoir", "hallo", "guten tag", "guten morgen",
            "hola", "buenos días", "buenas tardes", "привет", "здравствуйте", "добрый день");
    static final List<String> BLESSINGS = list("شكرا", "شكراً", "مشكور", "مشكورين", "تسلم", "تسلمو", "تسلموا", "يعطيك العافية", "يعطيكم العافية",
            "الله يعطيك", "بارك الله", "جزاكم الله", "جزاك الله", "تحياتي", "مع التحية", "مع الشكر", "زكاتكم", "زكاة", "صدقة", "تقبل الله",
            "الله يحفظكم", "الله يوفقكم", "ودمتم", "ان شاء الله", "إن شاء الله", "الحمد لله", "عاجل", "ضروري", "مستعجل", "بأسرع وقت",
            "وفقكم الله", "الله يجزيكم", "عافاكم الله", "دمتم بخير", "يرحم والديكم", "الله يرضى عليكم", "الله يسلمكم", "الله يسلمك",
            "يسلمو", "يسلموا", "يسلمو ايديكم", "تسلم ايدك", "الله معكم", "بالتوفيق", "اللهم صل", "صلى الله",
            "thanks", "thank you", "thanks a lot", "many thanks", "thx", "regards", "best regards", "kind regards", "god bless", "inshallah",
            "teşekkürler", "teşekkür ederim", "sağol", "sağolun", "sağ olun", "kolay gelsin", "allah razı olsun", "inşallah",
            "mulțumesc", "multumesc", "mersi", "merci", "merci beaucoup", "cordialement", "danke", "danke schön", "vielen dank", "gracias",
            "muchas gracias", "спасибо", "большое спасибо");
    static final List<String> DELIVERY = list("يرجى تسليم", "الرجاء تسليم", "رجاء تسليم", "نرجو تسليم", "يرجى التسليم", "تسليم", "التسليم",
            "سلمو", "سلموا", "سلم", "يسلم", "تسليمها", "تسليمه", "تسليم الى", "تسليم إلى", "حوالة", "حواله", "حوالة الى", "دفع", "صرف",
            "deliver", "delivery", "please deliver", "pay", "pay out", "payout", "payment", "give", "hand over", "handover", "send", "transfer",
            "remit", "need", "needs", "teslim", "teslim et", "teslim edin", "teslim edilsin", "teslimat", "öde", "ödeyin", "ödeme", "gönder",
            "gönderin", "havale", "predați", "predati", "predă", "predare", "plătiți", "plata", "plată", "dați", "trimiteți", "trimite",
            "livrer", "remettre", "payer", "envoyer", "auszahlen", "auszahlung", "übergeben", "zahlen", "senden", "entregar", "pagar", "enviar",
            "передать", "передайте", "выдать", "выдайте", "выплатить", "отправить", "перевод");
    static final List<String> PERSON_LABELS = list("اسم المستلم", "اسم المستفيد", "اسم المستلمة", "المستلم", "المستفيد", "المستلمة", "المستفيدة",
            "الاسم", "الإسم", "اسم", "للسيد", "السيد", "للسيدة", "السيدة", "للأخ", "الأخ", "للاخت", "الاخت", "للآنسة", "الآنسة",
            "باسم", "بإسم", "حوالة باسم", "حواله باسم", "تسليم باسم", "المرسل اليه", "المرسل إليه", "المحول اليه", "المحول إليه", "لصالح",
            "name", "full name", "receiver", "receiver name", "recipient", "recipient name", "beneficiary", "beneficiary name", "isim", "ad soyad",
            "adı soyadı", "alıcı", "alıcı adı", "nume", "nume complet", "beneficiar", "destinatar", "nom", "bénéficiaire", "destinataire",
            "empfänger", "nombre", "beneficiario", "destinatario", "имя", "фио", "получатель");
    /** ألقاب قبل الاسم في أي موضع من السطر: «for Mr Tony»، «domnului Ion Popescu»، «للسيد أحمد». */
    static final List<String> HONORIFICS = list("mr", "mrs", "ms", "miss", "mister", "sir", "madam", "bay", "bayan", "sayın", "sn", "domnul",
            "domnului", "dl", "dlui", "dna", "doamna", "doamnei", "monsieur", "madame", "mme", "mlle", "herr", "herrn", "frau", "señor", "señora",
            "sr", "sra", "господин", "госпожа", "السيد", "للسيد", "السيدة", "للسيدة", "الاخ", "للاخ", "الأخ", "للأخ", "الاخت", "للاخت", "الحاج",
            "للحاج", "الحاجة", "للحاجة", "الاستاذ", "للاستاذ", "الأستاذ", "للأستاذ", "الدكتور", "للدكتور", "الشيخ", "للشيخ", "الست", "للست", "لاخوي", "لأخوي", "لاخي", "لأخي", "لاختي",
            "لأختي", "لابني", "لبنتي", "لعمي", "لخالي", "لابن", "لبنت");
    /** حروف جر قبل الاسم: «to Ahmad Ali»، «pentru Ion»، «لـ أحمد». الاسم بعدها يُقبل فقط إن لم يكن مدينة أو عملة أو رقمًا. */
    static final List<String> PREP_LABELS = list("to", "for", "pentru", "către", "catre", "lui", "pour", "para", "für", "для", "ل", "الى", "إلى", "لصالح");
    /** ألقاب أو حروف تأتي بعد الاسم (التركية): «Ahmet Yılmaz için»، «Ahmet Bey'e». */
    static final Set<String> POST_LABELS = set("için", "adına", "bey", "beye", "beyefendi", "hanım", "hanıma", "hanimefendi", "efendi");
    static final List<String> SENDER_LABELS = list("المرسل", "اسم المرسل", "من طرف", "من قبل", "المحول", "اسم المحول", "المرسلة",
            "sender", "sender name", "from", "gönderen", "gönderici", "expeditor", "de la", "absender", "von", "remitente", "отправитель", "от");
    static final List<String> PHONE_LABELS = list("رقم الهاتف", "رقم الجوال", "رقم الموبايل", "الهاتف", "هاتف", "الجوال", "جوال", "موبايل",
            "تلفون", "التلفون", "واتس", "واتساب", "للتواصل", "تواصل", "رقم", "الرقم", "tel", "phone", "mobile", "mob", "gsm", "whatsapp", "telefon",
            "cep", "numara", "no", "nr", "număr", "téléphone", "portable", "handy", "nummer", "teléfono", "móvil", "телефон", "номер");
    static final List<String> AMOUNT_LABELS = list("المبلغ", "مبلغ", "القيمة", "قيمة", "بقيمة", "بمبلغ", "amount", "sum", "total", "value", "tutar",
            "miktar", "meblağ", "suma", "valoare", "montant", "somme", "betrag", "summe", "cantidad", "importe", "monto", "сумма");
    static final List<String> CITY_LABELS = list("المدينة", "مدينة", "العنوان", "عنوان", "مكان التسليم", "المكان", "المحافظة", "المنطقة", "منطقة", "التسليم في",
            "city", "address", "location", "place", "şehir", "adres", "oraș", "localitate", "adresa", "ville", "adresse", "stadt", "ort", "ciudad",
            "dirección", "город", "адрес");
    /** حروف قبل مدينة في وسط السطر: «to Syria»، «in Hama»، «din Alep»، «في حلب». */
    static final Set<String> CITY_PREPS = set("to", "in", "at", "în", "la", "din", "nach", "à", "en", "в", "الى", "إلى", "في");
    /** «from Damascus»، «من حلب»: مدينة المرسل لا مدينة التسليم. */
    static final Set<String> FROM_WORDS = set("من", "from", "von", "от", "gönderen", "de la");
    /** «من عند أبو أحمد»، «من حساب خالد علي»: كلمة بين «من» والاسم. */
    static final Set<String> FROM_FILLER = set("عند", "حساب", "جهة", "طرف", "قبل");
    /** «من اجل…»، «من خلال…»، «من بعد…»: ليست مرسلًا. */
    static final Set<String> FROM_BLOCK = set("اجل", "أجل", "خلال", "بعد", "دون", "غير", "اول", "أول", "جديد", "زمان", "شان", "شأن", "فترة", "مدة", "هنا", "هناك");
    static final List<String> FEE_WORDS = list("اجرة", "الاجرة", "أجرة", "الأجرة", "اجور", "الاجور", "أجور", "عمولة", "العمولة", "كوميسيون", "اجرته", "اجرتها",
            "fee", "fees", "commission", "charge", "charges", "komisyon", "ücret", "masraf", "comision", "comisionul", "taxa", "frais", "gebühr",
            "gebühren", "provision", "comisión", "tarifa", "комиссия");
    static final List<String> NET_WORDS = list("صافي", "صافيه", "صافية", "net", "neto", "netto", "nett");
    static final Set<String> NOT_NAME = set("يرجى", "الرجاء", "رجاء", "نرجو", "من", "فضلك", "حوالة", "حواله", "تحويل", "ارسال", "استلام", "وصل",
            "تم", "مكتب", "المكتب", "صرافة", "شركة", "مبلغ", "المبلغ", "دولار", "يورو", "ليرة", "صافي", "فقط", "لا", "غير", "كاش", "نقدا",
            "نقدي", "تسليم", "التسليم", "الى", "إلى", "في", "عن", "مع", "او", "أو", "اليوم", "بكرا", "غدا", "الآن", "هاتف", "رقم",
            "سلام", "السلام", "عليكم", "مرحبا", "شكرا", "اجرة", "الاجرة", "عمولة", "راجع", "الراجع", "بالميه", "بالمية", "بالالف", "المرسل", "المستلم", "الاسم", "المدينة", "واتس", "يد", "باليد",
            "حي", "شارع", "قرب", "جانب", "بجانب", "مقابل", "منطقة", "ساحة", "دوار", "سوق", "بناء", "طابق", "جامع", "مسجد", "مدرسة", "الحي",
            "please", "pls", "plz", "deliver", "delivery", "to", "mr", "mrs", "the", "send", "from", "amount", "name", "phone", "hi", "hello",
            "thanks", "thank", "you", "pay", "cash", "transfer", "receiver", "sender", "city", "dear", "bro", "usd", "eur", "dollar", "dollars",
            "need", "needs", "want", "wants", "urgent", "asap", "today", "tomorrow", "now", "money", "a", "an", "of", "in", "at", "on", "by",
            "with", "and", "or", "is", "are", "it", "this", "that", "my", "your", "our", "his", "her", "office", "sent", "give", "paid", "total",
            "sum", "fee", "net", "only", "just", "also", "same", "day", "ok", "okay", "yes", "no", "for", "via", "per", "brother", "sister",
            "ve", "ile", "için", "lütfen", "acil", "bugün", "yarın", "nakit", "para", "gönder", "teslim", "edin", "et", "kişi", "bey", "hanım",
            "efendi", "abi", "kardeş", "lira", "dolar", "avro", "şu", "bu", "ona", "da", "de", "ki", "mi",
            "și", "si", "cu", "la", "în", "pe", "pentru", "azi", "mâine", "numerar", "bani", "birou", "vă", "va", "rog", "să", "sa", "din", "lei",
            "le", "les", "du", "des", "pour", "avec", "aujourd", "hui", "demain", "merci", "svp", "und", "der", "die", "das", "mit", "für", "heute",
            "morgen", "bitte", "an", "y", "el", "los", "las", "con", "por", "hoy", "mañana", "favor", "и", "в", "на", "с", "для", "сегодня",
            "завтра", "пожалуйста", "he", "she", "they", "we", "i", "me", "him", "them", "will", "would", "can", "could", "should", "must",
            "come", "comes", "coming", "go", "goes", "get", "gets", "take", "takes", "pick", "receive", "receives", "collect", "call", "here",
            "there", "when", "where", "what", "who", "how", "sms", "msg", "message", "pm", "am", "tonight", "week", "friend", "family",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "gelecek", "alacak", "kardeşim", "كيفك", "كيفكم",
            "بدي", "ابعت", "بعت", "ابعث", "بدنا");

    /** مدن ومناطق شائعة (سوريا وما حولها وأوروبا) بالعربية واللاتينية لتمييز سطر المدينة عن الاسم. */
    static final Set<String> CITIES = set("دمشق", "الشام", "ريف دمشق", "حلب", "حمص", "حماة", "حماه", "اللاذقية", "طرطوس", "ادلب", "إدلب", "الرقة",
            "دير الزور", "ديرالزور", "الدير", "الحسكة", "القامشلي", "قامشلي", "درعا", "السويداء", "القنيطرة", "جبلة", "بانياس", "منبج", "عفرين",
            "اعزاز", "إعزاز", "الباب", "جرابلس", "سلمية", "السلمية", "مصياف", "محردة", "السقيلبية", "صوران", "حلفايا", "كفرزيتا", "مورك", "دوما",
            "جرمانا", "صحنايا", "التل", "النبك", "يبرود", "قطنا", "الكسوة", "الزبداني", "داريا", "المعضمية", "حرستا", "عربين", "زملكا", "قدسيا",
            "خان شيخون", "معرة النعمان", "سراقب", "اريحا", "أريحا", "جسر الشغور", "سرمدا", "الدانا", "الأتارب", "الاتارب", "سلقين", "حارم",
            "البوكمال", "الميادين", "تدمر", "الرستن", "تلبيسة", "القصير", "تلكلخ", "الحولة", "صافيتا", "الدريكيش", "القرداحة", "الحفة", "نوى",
            "ازرع", "إزرع", "الصنمين", "بصرى", "طفس", "جاسم", "انخل", "شهبا", "صلخد", "راس العين", "رأس العين", "تل ابيض", "تل أبيض", "عين العرب",
            "كوباني", "المالكية", "عامودا", "الدرباسية", "السفيرة", "الأتارب", "عندان", "حريتان", "تل رفعت", "مارع", "الراعي", "سرجيلا",
            "عمان", "عمّان", "الزرقاء", "اربد", "إربد", "بيروت", "طرابلس", "صيدا", "زحلة", "اسطنبول", "إسطنبول", "استنبول", "غازي عنتاب", "عنتاب",
            "مرسين", "انطاكية", "أنطاكية", "الريحانية", "اورفا", "أورفا", "كلس", "هاتاي", "انقرة", "أنقرة", "بورصة", "اربيل", "أربيل", "دهوك",
            "السليمانية", "بغداد", "الموصل", "البصرة", "دبي", "الشارقة", "ابوظبي", "أبوظبي", "عجمان", "الرياض", "جدة", "الدمام", "مكة", "المدينة المنورة",
            "الكويت", "الدوحة", "مسقط", "المنامة", "القاهرة", "الاسكندرية", "الإسكندرية", "برلين", "هامبورغ", "ميونخ", "فيينا", "ستوكهولم", "امستردام",
            "أمستردام", "باريس", "لندن", "سوريا", "سورية", "تركيا", "رومانيا", "المانيا", "ألمانيا", "لبنان", "الاردن", "الأردن", "العراق", "مصر",
            "بوخارست", "السويد", "هولندا", "النمسا", "فرنسا",
            "syria", "suriye", "siria", "syrie", "syrien", "sirya", "turkey", "turkiye", "türkiye", "turcia", "turquie", "türkei", "romania",
            "românia", "romanya", "roumanie", "rumänien", "germany", "almanya", "germania", "allemagne", "deutschland", "lebanon", "lübnan",
            "liban", "libanon", "ürdün", "iordania", "jordanie", "iraq", "irak", "egypt", "mısır", "egipt", "egypte", "uae", "emirates",
            "sweden", "isveç", "suedia", "netherlands", "holland", "hollanda", "austria", "avusturya", "france", "fransa", "franta",
            "damascus", "damasc", "damas", "damaskus", "aleppo", "halep", "alep", "homs", "humus", "hama", "idlib", "idlip", "latakia", "lattakia",
            "lazkiye", "lattaquié", "tartus", "tartous", "raqqa", "rakka", "deir ezzor", "deir ez zor", "deir al zor", "hasakah", "haseke", "hasaka",
            "qamishli", "kamışlı", "daraa", "deraa", "dera", "suwayda", "sweida", "afrin", "azaz", "manbij", "jarablus", "kobani", "istanbul",
            "ankara", "gaziantep", "antep", "mersin", "hatay", "antakya", "reyhanlı", "urfa", "şanlıurfa", "kilis", "izmir", "bursa", "konya",
            "adana", "kahramanmaraş", "bucharest", "bucurești", "bukreş", "cluj", "cluj napoca", "timișoara", "iași", "constanța", "brașov",
            "craiova", "galați", "oradea", "chișinău", "berlin", "hamburg", "munich", "münchen", "cologne", "köln", "frankfurt", "dortmund",
            "vienna", "wien", "stockholm", "amsterdam", "paris", "london", "brussels", "bruxelles", "beirut", "beyrut", "amman", "erbil",
            "baghdad", "mosul", "dubai", "sharjah", "riyadh", "jeddah", "doha", "kuwait", "cairo", "kahire", "athens", "budapest");

    /** مرادفات العملات حسب رمزها: {الكلمة، الوزن}. الوزن الأعلى = دلالة أوضح. */
    private static final Map<String, String[]> SYN = new LinkedHashMap<>();

    static {
        SYN.put("USD", new String[]{"دولار", "دولارات", "الدولار", "بالدولار", "دولار امريكي", "دولار أمريكي", "usd", "us$", "dollar", "dollars", "$", "اخضر", "أخضر",
                "dolar", "dolari", "dolarul", "dolares", "dólares", "doları", "dollaro", "dollari", "us dollar", "us dollars", "american dollar",
                "amerikan doları", "dolar american", "доллар", "доллара", "долларов", "доллары", "бакс", "баксов"});
        SYN.put("EUR", new String[]{"يورو", "اليورو", "باليورو", "eur", "euro", "€", "euros", "avro", "евро"});
        SYN.put("TRY", new String[]{"تركي", "التركي", "بالتركي", "ليرة تركية", "ليرات تركية", "try", "tl", "₺", "lira", "liras", "lirası", "türk lirası",
                "turkish lira", "lira turcească", "lire turcești", "livre turque", "türkische lira", "lira turca", "турецкая лира", "турецких лир"});
        SYN.put("SYP", new String[]{"سوري", "السوري", "بالسوري", "ليرة سورية", "ليرات سورية", "ليرة سوري", "ل.س", "ل س", "syp", "syrian pound",
                "syrian pounds", "syrian lira", "syrian", "suriye lirası", "lira siriană", "lire siriene", "livre syrienne", "сирийский фунт"});
        SYN.put("SAR", new String[]{"ريال سعودي", "سعودي", "ريال", "sar", "riyal", "riyals", "saudi riyal", "suudi riyali"});
        SYN.put("AED", new String[]{"درهم", "دراهم", "درهم اماراتي", "اماراتي", "aed", "dirham", "dirhams", "dirhem"});
        SYN.put("JOD", new String[]{"دينار اردني", "اردني", "jod", "jordanian dinar", "ürdün dinarı"});
        SYN.put("IQD", new String[]{"دينار عراقي", "عراقي", "iqd", "iraqi dinar", "irak dinarı"});
        SYN.put("KWD", new String[]{"دينار كويتي", "كويتي", "kwd", "kuwaiti dinar"});
        SYN.put("EGP", new String[]{"جنيه مصري", "مصري", "جنيه", "egp", "egyptian pound", "egyptian pounds", "mısır lirası"});
        SYN.put("GBP", new String[]{"استرليني", "جنيه استرليني", "باوند", "gbp", "£", "pound", "pounds", "sterling", "pound sterling", "sterlin",
                "ingiliz sterlini", "liră sterlină", "lire sterline", "livre sterling", "pfund", "libra esterlina", "фунт", "фунтов"});
        SYN.put("QAR", new String[]{"ريال قطري", "قطري", "qar", "qatari riyal"});
        SYN.put("LBP", new String[]{"ليرة لبنانية", "لبناني", "lbp", "lebanese pound", "lebanese lira", "lübnan lirası"});
        SYN.put("USDT", new String[]{"usdt", "تيثر", "تيذر", "التيثر", "tether", "يو اس دي تي", "₮"});
        SYN.put("RON", new String[]{"ron", "lei", "leu", "lei românești", "lei romanesti", "romanian lei", "romanian leu", "rumänische lei", "лей",
                "ليو روماني", "لي روماني", "ليرة رومانية"});
        SYN.put("CHF", new String[]{"chf", "swiss franc", "swiss francs", "franc elvețian", "franci elvețieni", "isviçre frangı", "franken",
                "franc suisse", "francs suisses", "فرنك سويسري"});
        SYN.put("SEK", new String[]{"sek", "kronor", "krona", "swedish krona", "isveç kronu", "coroane suedeze", "كرون سويدي"});
        SYN.put("PLN", new String[]{"pln", "zloty", "zlotys", "złoty", "zł", "zlot", "زلوتي"});
        SYN.put("RUB", new String[]{"rub", "ruble", "rubles", "rouble", "roubles", "ruble rusești", "rus rublesi", "рубль", "рублей", "рубля",
                "روبل"});
        SYN.put("CAD", new String[]{"cad", "canadian dollar", "canadian dollars", "دولار كندي"});
        SYN.put("AUD", new String[]{"aud", "australian dollar", "australian dollars", "دولار استرالي"});
        SYN.put("XAU", new String[]{"xau", "gold", "altın", "aur", "ذهب", "غرام ذهب", "غرامات ذهب"});
        SYN.put("XAG", new String[]{"xag", "silver", "gümüş", "argint", "فضة", "فضه"});
    }

    private static final Set<String> SYMBOLS = new HashSet<>(Arrays.asList("$", "€", "₺", "£", "₮"));
    private static final Set<String> GENERIC_NAME_WORDS = set("ليرة", "ليره", "عملة", "دينار", "ريال", "جديد", "قديم", "جديدة", "قديمة");

    // ===================================================================================
    // العملات
    // ===================================================================================

    private static final class Kw {
        final String word;
        final int weight;

        Kw(String w, int weight) {
            this.word = w;
            this.weight = weight;
        }
    }

    /** الكلمة (مطبّعة) ← رمز العملة التي يعرفها البرنامج بها. */
    private static final Map<String, String> SYN_OWNER = new LinkedHashMap<>();

    static {
        for (Map.Entry<String, String[]> e : SYN.entrySet()) {
            for (String s : e.getValue()) {
                String n = String.join(" ", words(norm(s)));
                if (SYMBOLS.contains(s)) n = s;
                if (!n.isEmpty() && !SYN_OWNER.containsKey(n)) SYN_OWNER.put(n, e.getKey());
            }
        }
    }

    private static Map<String, List<Kw>> keywords(List<Cur> curs, Map<String, List<String>> aliases) {
        Map<String, List<Kw>> m = new LinkedHashMap<>();
        Set<String> codes = new HashSet<>();
        for (Cur c : curs) codes.add(c.code.toUpperCase(Locale.ROOT));
        // كلمة في اسم عملة واحدة فقط (مثل «جديد») تميّزها عن غيرها، فوزنها أعلى
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (Cur c : curs) {
            for (String w : new HashSet<>(words(norm(c.name)))) freq.put(w, freq.containsKey(w) ? freq.get(w) + 1 : 1);
        }
        for (Cur c : curs) {
            List<Kw> k = new ArrayList<>();
            String code = c.code.toUpperCase(Locale.ROOT);
            k.add(new Kw(norm(code), 3));
            // كلمات أضافها المستخدم لهذه العملة (مثل «يوز» = USDT): أوضح دلالة
            List<String> mine = aliases == null ? null : aliases.get(c.code);
            if (mine != null) {
                for (String s : mine) {
                    String n = SYMBOLS.contains(s.trim()) ? s.trim() : String.join(" ", words(norm(s)));
                    if (!n.isEmpty()) k.add(new Kw(n, 4));
                }
            }
            String[] syn = SYN.get(code);
            if (syn != null) {
                for (String s : syn) {
                    String n = norm(s);
                    int w = SYMBOLS.contains(s) ? 3 : words(n).size() > 1 ? 3 : 2;
                    k.add(new Kw(n, w));
                }
            }
            String nn = norm(c.name);
            if (!nn.isEmpty()) {
                k.add(new Kw(String.join(" ", words(nn)), 4));
                for (String w : words(nn)) {
                    if (w.length() < 3) continue;
                    boolean distinct = freq.containsKey(w) && freq.get(w) == 1 && curs.size() > 1;
                    String owner = SYN_OWNER.get(w);
                    // «دولار» في اسم «دولار تيثر» لا يجعلها تنافس الدولار الأمريكي على كلمة «دولار»
                    boolean othersWord = owner != null && !owner.equals(code) && codes.contains(owner);
                    k.add(new Kw(w, othersWord ? 1 : distinct ? 3 : GENERIC_NAME_WORDS.contains(w) ? 1 : 2));
                }
            }
            if (nn.contains("ليره") || "SYP".equals(code) || "TRY".equals(code) || "LBP".equals(code)) k.add(new Kw("ليره", 1));
            m.put(c.code, k);
        }
        return m;
    }

    /** أفضل عملة يدل عليها النص، وكلمتها؛ null إن لم توجد. */
    static String[] currencyIn(String normText, Map<String, List<Kw>> kws) {
        List<String> w = words(normText);
        String best = null, bestWord = "";
        int bestScore = 0;
        for (Map.Entry<String, List<Kw>> e : kws.entrySet()) {
            int score = 0;
            String word = "";
            Set<String> seen = new HashSet<>();
            for (Kw k : e.getValue()) {
                if (!seen.add(k.word)) continue;
                boolean hit;
                if (SYMBOLS.contains(k.word)) hit = normText.contains(k.word);
                else if (words(k.word).size() > 1) hit = hasPhrase(normText, k.word);
                else hit = w.contains(k.word) || prefixHit(w, k.word);
                if (hit) {
                    score += k.weight;
                    if (word.isEmpty() || k.weight >= 3) word = k.word;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
                bestWord = word;
            }
        }
        return best == null ? null : new String[]{best, bestWord};
    }

    /** «سوري» تطابق «سوريه» و«جديد» تطابق «جديده»: سابقة من 4 أحرف فأكثر. */
    private static boolean prefixHit(List<String> words, String kw) {
        if (kw.length() < 4) return false;
        for (String w : words) {
            if (w.length() < 4) continue;
            if ((w.startsWith(kw) && w.length() - kw.length() <= 1) || (kw.startsWith(w) && kw.length() - w.length() <= 1)) return true;
        }
        return false;
    }

    /** هل في النص أي كلمة عملة معروفة (حتى لو لم تكن مفعّلة في البرنامج)؟ */
    static String anyCurrencyWord(String normText) { return anyCurrencyWord(normText, null); }

    /** مثلها، مع كلمات المستخدم لكل العملات (حتى غير المفعّلة للمكتب). */
    static String anyCurrencyWord(String normText, Map<String, List<String>> aliases) {
        List<String> w = words(normText);
        if (aliases != null) {
            for (List<String> l : aliases.values()) {
                for (String s : l) {
                    String n = String.join(" ", words(norm(s)));
                    if (n.isEmpty()) continue;
                    if (SYMBOLS.contains(s.trim()) ? normText.contains(s.trim()) : words(n).size() > 1 ? hasPhrase(normText, n) : w.contains(n)) return s.trim();
                }
            }
        }
        for (Map.Entry<String, String[]> e : SYN.entrySet()) {
            for (String s : e.getValue()) {
                String n = norm(s);
                if (SYMBOLS.contains(s) ? normText.contains(n) : words(n).size() > 1 ? hasPhrase(normText, n) : w.contains(n)) return s;
            }
        }
        return null;
    }

    // ===================================================================================
    // الأرقام والهاتف
    // ===================================================================================

    private static final Pattern NUM = Pattern.compile("\\+?\\d[\\d.,]*");
    /** رقم هاتف: مجموعات أرقام بينها مسافة واحدة أو شرطة واحدة (لا « - » التي تفصل بين رقمين مختلفين). */
    private static final Pattern PHONE = Pattern.compile("(?:\\+|00)?\\d+(?:[ \\-]\\d+)+|\\+?\\d{7,}");

    /** يقرأ رقمًا كما يُكتب في الرسائل: 1,500 / 1.500 / 1,250.50 / 1.250,50 / 2.5 */
    /** للاختبار: قراءة الرقم كما في الرسائل. */
    public static BigDecimal numberForTest(String tok) { return number(tok); }

    static BigDecimal number(String tok) {
        String s = tok.replaceAll("[.,]+$", "");
        if (s.startsWith("+") || s.isEmpty()) return null;
        try {
            // القراءة نفسها التي يستعملها نموذج الإدخال (Amounts)، فلا يخرج النص الواحد بقيمتين
            String n = Amounts.normalize(s, 2);
            return n.isEmpty() ? null : new BigDecimal(n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String plain(BigDecimal v) {
        return new java.text.DecimalFormat("#,##0.##########", java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    private static int digitCount(String s) {
        int n = 0;
        for (char ch : s.toCharArray()) if (ch >= '0' && ch <= '9') n++;
        return n;
    }

    /** يبدو رقم هاتف: يبدأ بـ + أو 00 أو 0، أو طويل جدًا ليكون مبلغًا. */
    static boolean looksPhone(String raw, boolean labeled, boolean lineHasCurrency) {
        String t = raw.trim();
        int d = digitCount(t);
        if (d < 7) return false;
        if (labeled) return true;
        if (t.startsWith("+") || t.startsWith("00")) return true;
        if (t.startsWith("0") && d >= 8) return true;
        if (t.matches(".*\\d[ \\-]\\d.*") && !lineHasCurrency) return true;  // أرقام مجمّعة بمسافات
        return d >= 10 && !lineHasCurrency;
    }

    private static final Map<String, BigDecimal> DIGIT_MULT = new LinkedHashMap<>();

    static {
        for (String w : new String[]{"الف", "الاف", "ألف", "آلاف", "الفا", "k", "thousand", "thousands", "grand", "bin", "mie", "mii", "mille", "tausend",
                "тыс", "тысяч", "тысячи", "тысяча"}) DIGIT_MULT.put(norm(w), new BigDecimal(1000));
        for (String w : new String[]{"مليون", "ملايين", "مليونا", "m", "mn", "million", "millions", "milyon", "milion", "milioane", "millón", "millones",
                "millionen", "млн", "миллион", "миллиона", "миллионов"}) DIGIT_MULT.put(norm(w), new BigDecimal(1000000));
        for (String w : new String[]{"مليار", "billion", "milyar", "miliard", "miliarde", "milliard", "milliarde", "млрд", "миллиард"})
            DIGIT_MULT.put(norm(w), new BigDecimal(1000000000));
    }

    /** «5 الف»، «5 bin»، «2 mii»، «3 тысячи»، «1.5 million»: الكلمة بعد الرقم تضربه. */
    private static BigDecimal multiplier(List<String> after) {
        if (after.isEmpty()) return BigDecimal.ONE;
        BigDecimal m = DIGIT_MULT.get(after.get(0));
        return m == null ? BigDecimal.ONE : m;
    }

    // ===================================================================================
    // التحليل
    // ===================================================================================

    private static final class Line {
        final String orig, norm;
        final List<String> ow, nw;
        /** brk[i]: بعد الكلمة i فاصلة أو نقطة ونحوها، فالاسم لا يمتد بعدها («to Yusuf, he will come»). */
        final boolean[] brk;
        boolean used;

        Line(String raw) {
            orig = clean(raw);
            norm = norm(raw);
            ow = words(orig);
            nw = words(norm);
            brk = new boolean[ow.size()];
            Matcher m = TOKEN.matcher(orig);
            int i = 0, lastEnd = -1;
            while (m.find() && i < brk.length) {
                if (i > 0 && BREAK.matcher(orig.substring(lastEnd, m.start())).find()) brk[i - 1] = true;
                lastEnd = m.end();
                i++;
            }
        }
    }

    private static final Pattern BREAK = Pattern.compile("[,،.;؛!?؟:()]");

    /** يعيد ما بعد التسمية (مثل «الاسم:») بالكلمات الأصلية، أو null إن لم يبدأ السطر بها. */
    private static String afterLabel(Line l, List<String> labels) {
        int n = labelLen(l, labels);
        return n == 0 ? null : String.join(" ", l.ow.subList(n, l.ow.size()));
    }

    /** طول أطول تسمية يبدأ بها السطر (بالكلمات)، أو 0. */
    private static int labelLen(Line l, List<String> labels) {
        int bestLen = 0;
        for (String lab : labels) {
            List<String> lw = words(lab);
            if (lw.size() > bestLen && startsWithPhrase(l.nw, lw) && l.ow.size() >= lw.size()) bestLen = lw.size();
        }
        return bestLen;
    }

    private static boolean containsAny(String normLine, List<String> phrases) {
        for (String p : phrases) if (hasPhrase(normLine, p)) return true;
        return false;
    }

    private static boolean isCity(String normText) {
        String t = String.join(" ", words(normText));
        if (CITIES.contains(t)) return true;
        List<String> w = words(t);
        if (w.size() <= 4 && !w.isEmpty()) {
            if (CITIES.contains(w.get(0))) return true;
            if (w.size() >= 2 && CITIES.contains(w.get(0) + " " + w.get(1))) return true;
        }
        return false;
    }

    /** هل يصلح النص اسمًا لشخص؟ (كلمتان إلى ست، حروف فقط، بلا كلمات وظيفية) */
    private static boolean nameLike(List<String> nw) {
        if (nw.size() < 2 || nw.size() > 6) return false;
        for (String w : nw) {
            if (hasDigit(w)) return false;
            if (!isLetters(w)) return false;
            if (NOT_NAME.contains(w)) return false;
            if (NumberWords.isStrong(w)) return false;
        }
        return true;
    }

    /** \u062D\u0631\u0648\u0641 \u0641\u0642\u0637 \u0628\u0623\u064A \u0644\u063A\u0629 (\u0639\u0631\u0628\u064A\u0629\u060C \u0644\u0627\u062A\u064A\u0646\u064A\u0629 \u0628\u0639\u0644\u0627\u0645\u0627\u062A\u0647\u0627\u060C \u0633\u064A\u0631\u064A\u0644\u064A\u0629\u2026)\u060C \u0628\u0644\u0627 \u0623\u0631\u0642\u0627\u0645 \u0648\u0644\u0627 \u0631\u0645\u0648\u0632. */
    static boolean isLetters(String w) {
        if (w.isEmpty()) return false;
        for (char ch : w.toCharArray()) if (!Character.isLetter(ch)) return false;
        return true;
    }

    private static String cleanName(String s) {
        String t = s.replaceAll("^[\\s:،,\\-–—]+|[\\s:،,.\\-–—]+$", "").trim();
        t = t.replaceAll("^(ل|الى|إلى|إلي|الي)\\s+", "").trim();   // «لـ أحمد» ← «أحمد»
        return t.replaceAll(" {2,}", " ");
    }

    // ===================================================================================
    // «راجع»: أجرة يدفعها المكتب لنا
    // ===================================================================================

    /** بعد «راجع»: الرقم (أو نص/ربع) ثم بالمئة أو بالألف أو عملة. يعمل على النص بعد التنظيف (أرقام 0-9). */
    static final Pattern RAJE = Pattern.compile("(?:^|[^\\p{L}])(?:ال)?راجع(?:ه|ة|نا|لنا)?(?![\\p{L}])\\s*[:=]?\\s*"
            + "(\\d+(?:[.,]\\d+)?|نصف|نص|ربع)?\\s*"
            + "(%|٪|‰|(?:ب|في\\s*)ال?(?:مي[هةت]|مئ[هةت]|مائ[هةت]|ميئ[هة])|(?:ب|في\\s*)ال?[اأ]?(?:لف|لاف))?(?![\\p{L}])");
    static final Pattern PER_MILLE_W = Pattern.compile("‰|لف$|لاف$");

    /**
     * يقرأ «راجع» من سطر واحد. يرجع [النسبة، 100|1000] أو [المبلغ، 0] أو null. يعرّف المطابقة في rajeSpan
     * لتُستبعد أرقامها من المبلغ الرئيسي.
     */
    static String blanks(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(' ');
        return b.toString();
    }

    static Object[] raje(String line, int[] span, Map<String, List<Kw>> kws, Map<String, List<String>> al) {
        Matcher m = RAJE.matcher(line);
        if (!m.find()) return null;
        span[0] = m.start();
        span[1] = m.end();
        String num = m.group(1), unit = m.group(2);
        if (num == null) return new Object[]{null, -1};
        BigDecimal v;
        switch (num) {
            case "نص": case "نصف": v = new BigDecimal("0.5"); break;
            case "ربع": v = new BigDecimal("0.25"); break;
            default: v = number(num.replace(',', '.'));
        }
        if (v == null || v.signum() <= 0) return new Object[]{null, -1};
        if (unit != null) return new Object[]{v, PER_MILLE_W.matcher(unit).find() ? 1000 : 100};
        // رقم بعده عملة («راجع 50 دولار») = أجرة محددة
        String rest = line.substring(m.end());
        String rn = norm(rest);
        List<String> w = words(rn);
        String first = w.isEmpty() ? "" : w.get(0);
        String sym = rest.trim().isEmpty() ? "" : rest.trim().substring(0, 1);
        if (!first.isEmpty() && (currencyIn(first, kws) != null || anyCurrencyWord(first, al) != null) || SYMBOLS.contains(sym)) {
            span[1] = Math.min(line.length(), m.end() + rest.indexOf(rest.trim()) + (SYMBOLS.contains(sym) ? 1 : 0));
            return new Object[]{v, 0};
        }
        // رقم وحده («راجع 5»): لا يُخمَّن (قد يكون نسبة أو مبلغًا أو حجم الحوالة)؛ يظهر للمحاسب ليحدده
        return new Object[]{v, -1};
    }

    public static Result parse(String text, List<Cur> currencies) { return parse(text, currencies, null); }

    /** aliases: كلمات أضافها المستخدم لكل رمز عملة (مثل USDT ← «يوز»)، أو null. */
    public static Result parse(String text, List<Cur> currencies, Map<String, List<String>> aliases) {
        Result r = new Result();
        if (text == null || text.trim().isEmpty()) return r;
        final Map<String, List<String>> al = aliases;
        Map<String, List<Kw>> kws = keywords(currencies, al);
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            Line l = new Line(raw);
            if (!l.orig.isEmpty()) lines.add(l);
        }
        String allNorm = norm(text);
        r.net = containsAny(allNorm, NET_WORDS);

        // ---------------------------------------------------------------- الهاتف
        for (Line l : lines) {
            String lab = afterLabel(l, PHONE_LABELS);
            boolean labeled = lab != null && hasDigit(lab);
            boolean lineCur = currencyIn(l.norm, kws) != null || anyCurrencyWord(l.norm, al) != null;
            Matcher m = PHONE.matcher(l.orig);
            while (m.find()) {
                String p = m.group().trim();
                if (looksPhone(p, labeled, lineCur)) {
                    if (r.phone.isEmpty()) r.phone = p;
                    if (l.orig.replace(p, "").replaceAll("[\\s:،,\\-]", "").isEmpty() || labeled) l.used = true;
                }
            }
        }

        // ---------------------------------------------------------------- «راجع» (أجرة لنا)
        Map<Line, int[]> rajeSpans = new HashMap<>();
        for (Line l : lines) {
            int[] sp = new int[2];
            Object[] rj = raje(l.orig, sp, kws, al);
            if (rj == null) continue;
            r.raje = true;
            rajeSpans.put(l, sp);
            if (rj[0] == null || !r.rajeRate.isEmpty() || !r.rajeAmount.isEmpty()) continue;
            BigDecimal v = (BigDecimal) rj[0];
            int per = (Integer) rj[1];
            if (per == 100 || per == 1000) {
                if (v.compareTo(BigDecimal.valueOf(per)) < 0) {
                    r.rajeRate = v.stripTrailingZeros().toPlainString();
                    r.rajePer = per;
                }
            } else if (per == 0) r.rajeAmount = v.stripTrailingZeros().toPlainString();
            if (per == -1 && r.rajeRate.isEmpty()) r.rajeHint = v.stripTrailingZeros().toPlainString();
        }

        // ---------------------------------------------------------------- المبلغ والأجرة (أرقام أو حروف)
        String amountLineCur = null, amountWord = "", afterO = "", afterN = "", amountTok = null;
        int amountScore = -1;
        for (Line l : lines) {
            boolean fee = containsAny(l.norm, FEE_WORDS);
            String lab = afterLabel(l, AMOUNT_LABELS);
            String[] cur = currencyIn(l.norm, kws);
            String other = cur == null ? anyCurrencyWord(l.norm, al) : null;
            boolean lineCur = cur != null || other != null;
            boolean delivery = containsAny(l.norm, DELIVERY);
            String scan = l.orig;
            int[] rs = rajeSpans.get(l);
            if (rs != null) scan = scan.substring(0, rs[0]) + blanks(rs[1] - rs[0]) + scan.substring(rs[1]);
            if (!r.phone.isEmpty()) scan = scan.replace(r.phone, " ");
            Matcher m = NUM.matcher(scan);
            while (m.find()) {
                String tok = m.group();
                if (looksPhone(tok, false, lineCur)) continue;
                BigDecimal v = number(tok);
                if (v == null || v.signum() <= 0) continue;
                String rest = scan.substring(m.end());
                BigDecimal mul = multiplier(words(norm(rest)));
                // 10.5: «50 الف» = 50,000، أما «50000 الف» فالرقم مكتوب كاملًا و«الف» توضيح له (اصطلاح المكاتب)، لا ضرب بألف
                String note = "";
                String shown = tok + (mul.compareTo(BigDecimal.ONE) > 0 ? " " + words(clean(rest)).get(0) : "");
                if (mul.compareTo(BigDecimal.ONE) > 0 && v.compareTo(mul) >= 0) {
                    boolean exact = v.remainder(mul).signum() == 0;
                    note = "«" + shown + "» فُهمت " + plain(v) + (exact ? " (كلمة «" + words(clean(rest)).get(0) + "» توضيح للمبلغ المكتوب كاملًا لا ضرب)."
                            : ". إن كان المقصود " + plain(v.multiply(mul)) + " فصحّح المبلغ.");
                    mul = BigDecimal.ONE;
                }
                v = v.multiply(mul);
                String sym = rest.trim().isEmpty() ? "" : rest.trim().substring(0, 1);
                String before = scan.substring(0, m.start()).trim();
                String symBefore = before.isEmpty() ? "" : before.substring(before.length() - 1);
                boolean symbol = SYMBOLS.contains(sym) || SYMBOLS.contains(symBefore);
                int score = 0;
                if (lab != null) score += 6;
                if (lineCur || symbol) score += 5;
                if (mul.compareTo(BigDecimal.ONE) > 0) score += 2;
                if (delivery) score += 1;
                if (l.nw.size() <= 3) score += 1;
                if (fee) {
                    if (r.fee.isEmpty()) r.fee = v.stripTrailingZeros().toPlainString();
                    continue;
                }
                if (score > amountScore) {
                    amountScore = score;
                    r.amount = v.stripTrailingZeros().toPlainString();
                    r.amountText = shown;
                    r.amountNote = note;
                    amountTok = mul.compareTo(BigDecimal.ONE) > 0 ? null : tok;
                    amountLineCur = cur == null ? null : cur[0];
                    amountWord = cur != null ? cur[1] : other != null ? other : symbol ? (SYMBOLS.contains(sym) ? sym : symBefore) : "";
                    // الكلمة التالية للمبلغ (بعد «الف» ونحوها): قد تكون اسم عملة عاميًّا لا يعرفه البرنامج
                    List<String> ro = words(clean(rest)), rn = words(norm(rest));
                    int skip = mul.compareTo(BigDecimal.ONE) > 0 ? 1 : 0;
                    if (rn.size() > skip && FILLER.contains(rn.get(skip))) skip++;
                    afterO = ro.size() > skip && rn.size() > skip ? ro.get(skip) : "";
                    afterN = rn.size() > skip ? rn.get(skip) : "";
                    l.used = true;
                }
            }
            // المبالغ المكتوبة بالحروف: «خمسمية دولار»، «five hundred»، «beş yüz»، «cinci sute de lei»…
            for (NumberWords.Match w : NumberWords.find(l.nw)) {
                if (w.start > 0 && hasDigit(l.nw.get(w.start - 1))) continue;   // «5 bin»: مضاعِف للرقم، عولج أعلاه
                int k = w.end;
                if (k < l.nw.size() && FILLER.contains(l.nw.get(k))) k++;
                String next = k < l.nw.size() ? String.join(" ", l.nw.subList(k, Math.min(k + 3, l.nw.size()))) : "";
                String first = k < l.nw.size() ? l.nw.get(k) : "";
                String prev = w.start > 0 ? l.nw.get(w.start - 1) : "";
                String[] adj = !first.isEmpty() ? currencyIn(first, kws) : null;
                if (adj == null && !next.isEmpty()) adj = currencyIn(next, kws);
                if (adj == null && !prev.isEmpty()) adj = currencyIn(prev, kws);
                String adjOther = adj != null ? null : !first.isEmpty() && anyCurrencyWord(first, al) != null ? anyCurrencyWord(first, al)
                        : !prev.isEmpty() && anyCurrencyWord(prev, al) != null ? anyCurrencyWord(prev, al) : null;
                boolean nearCur = adj != null || adjOther != null || SYMBOLS.contains(first) || SYMBOLS.contains(prev);
                if (w.weak && !nearCur && lab == null) continue;
                int score = -1;
                if (lab != null) score += 6;
                if (lineCur || nearCur) score += 5;
                if (w.multiplier) score += 2;
                if (delivery) score += 1;
                if (l.nw.size() <= 3 + w.words) score += 1;
                if (fee) {
                    if (r.fee.isEmpty()) r.fee = w.value.toPlainString();
                    continue;
                }
                if (score > amountScore) {
                    amountScore = score;
                    r.amount = w.value.toPlainString();
                    r.amountText = String.join(" ", l.ow.subList(w.start, Math.min(w.end, l.ow.size())));
                    r.amountNote = "";
                    amountTok = null;
                    String[] c = adj != null ? adj : cur;
                    amountLineCur = c == null ? null : c[0];
                    amountWord = c != null ? c[1] : adjOther != null ? adjOther : other != null ? other : "";
                    afterO = k < l.ow.size() ? l.ow.get(k) : "";
                    afterN = first;
                    l.used = true;
                }
            }
        }
        if (amountLineCur != null) {
            r.currency = amountLineCur;
        } else {
            String[] cur = currencyIn(allNorm, kws);
            if (cur != null) {
                r.currency = cur[0];
                amountWord = cur[1];
            } else if (amountWord.isEmpty()) {
                String w = anyCurrencyWord(allNorm, al);
                if (w != null) amountWord = w;
            }
            if (!amountWord.isEmpty() && r.currency == null && SYMBOLS.contains(amountWord)) {
                String[] c2 = currencyIn(amountWord, kws);
                if (c2 != null) r.currency = c2[0];
            }
        }
        r.currencyWord = amountWord;
        // 10.5 (AUDIT-031): بعملة بثلاث منازل (دينار كويتي، أردني…) «1.250» قد تعني ألفًا ومئتين وخمسين أو دينارًا وربعًا:
        // لا يُخمَّن الرقم؛ يُترك المبلغ للمحاسب مع التفسيرين
        if (amountTok != null && r.currency != null) {
            int dec = -1;
            for (Cur c : currencies) if (c.code.equals(r.currency)) dec = c.decimals;
            if (dec == 3) {
                try {
                    Amounts.normalize(amountTok.replaceAll("[.,]+$", ""), 3);
                } catch (NumberFormatException amb) {
                    r.amountNote = "«" + amountTok + "» ملتبس بعملة " + r.currency + " (ثلاث منازل): قد يكون " + r.amount + " أو " + amountTok.replace(',', '.')
                            + ". اكتب المبلغ بيدك.";
                    r.amount = "";
                }
            }
        }

        // ---------------------------------------------------------------- المرسل والمدينة
        for (Line l : lines) {
            int sl = labelLen(l, SENDER_LABELS);
            String s = sl > labelLen(l, PERSON_LABELS) ? afterLabel(l, SENDER_LABELS) : null;
            if (s != null && !s.trim().isEmpty() && r.sender.isEmpty()) {
                // «from Khaled Omar to Rami Saleh 700 GBP»: المرسل حتى نهاية اسمه فقط
                int e = nameEnd(l.nw, l.brk, sl, kws, al);
                boolean more = e < l.nw.size() && e > sl;
                r.sender = more ? titleCase(String.join(" ", l.ow.subList(sl, e))) : cleanName(s);
                if (!more) l.used = true;
            }
            String c = afterLabel(l, CITY_LABELS);
            if (c != null && !c.trim().isEmpty() && r.city.isEmpty() && !hasDigit(c)) {
                r.city = cleanName(c);
                l.used = true;
            }
        }
        for (Line l : lines) {
            if (l.used || hasDigit(l.orig)) continue;
            if (isCity(l.norm)) {
                if (r.city.isEmpty()) r.city = cleanName(l.orig);
                l.used = true;
            }
        }
        // في وسط السطر: «from Ahmad» للمرسل، و«to Syria»/«in Hama»/«din Alep»/«في حلب» للمدينة
        for (Line l : lines) {
            for (int k = 0; k < l.nw.size(); k++) {
                if (r.sender.isEmpty() && k > 0) {
                    int sl = phraseAt(l.nw, k, SENDER_LABELS);
                    if (sl > 0) {
                        int e = nameEnd(l.nw, l.brk, k + sl, kws, al);
                        if (e > k + sl) r.sender = titleCase(String.join(" ", l.ow.subList(k + sl, e)));
                    }
                }
                // «لكم 5000 دولار من عبد العزيز»: «من» العربية وحدها شائعة جدًا («من فضلك»، «من حلب»)،
                // فنقبلها للمرسل فقط إن تبعها اسم من كلمتين على الأقل ليس مدينة.
                if (r.sender.isEmpty() && "من".equals(l.nw.get(k))) {
                    int st = k + 1;
                    if (st < l.nw.size() && FROM_FILLER.contains(l.nw.get(st))) st++;
                    if (st < l.nw.size() && !FROM_BLOCK.contains(l.nw.get(st)) && cityAt(l.nw, st) == 0) {
                        int e = nameEnd(l.nw, l.brk, st, kws, al);
                        if (e - st >= 2) {
                            r.sender = titleCase(String.join(" ", l.ow.subList(st, e)));
                            if (k == 0 && e == l.nw.size()) l.used = true;
                        }
                    }
                }
                if (r.city.isEmpty() && CITY_PREPS.contains(l.nw.get(k)) && k + 1 < l.nw.size()) {
                    int n = cityAt(l.nw, k + 1);
                    if (n > 0) r.city = cityText(l.ow, l.nw, k + 1, n);
                }
            }
        }

        // ---------------------------------------------------------------- اسم المستفيد
        String best = "";
        int bestScore = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            // 1) بعد تسمية صريحة في أول السطر: «الاسم: …»، «المستلم: …»، «للسيد …»، «Receiver: …»
            boolean senderLine = labelLen(l, SENDER_LABELS) >= labelLen(l, PERSON_LABELS) && labelLen(l, SENDER_LABELS) > 0;
            String lab = senderLine ? null : afterLabel(l, PERSON_LABELS);
            if (lab != null) {
                String cand = cleanName(lab.replaceAll("[\\d+$€₺£₮].*$", ""));
                List<String> cw = words(norm(cand));
                if (cw.size() >= 1 && cw.size() <= 6 && nameWord(cw.get(0), kws, al)) {
                    int sc = 10 + (cw.size() >= 2 ? 1 : 0);
                    if (sc > bestScore) {
                        bestScore = sc;
                        best = titleCase(cand);
                    }
                    continue;
                }
            }
            // 1ب) في أي موضع من السطر: لقب («Mr Tony»، «domnului Ion»، «للسيد أحمد»)، أو تسمية، أو حرف جر («to Ahmad Ali»، «لـ أحمد»)
            for (int k = 0; k < l.nw.size(); k++) {
                int hl = phraseAt(l.nw, k, HONORIFICS);
                int pl = hl == 0 ? phraseAt(l.nw, k, PREP_LABELS) : 0;
                int ll = hl == 0 && pl == 0 && k > 0 ? phraseAt(l.nw, k, PERSON_LABELS) : 0;
                int len = Math.max(hl, Math.max(pl, ll));
                if (len == 0) continue;
                int st = k + len;
                if (pl > 0 && phraseAt(l.nw, st, HONORIFICS) > 0) continue;   // «for Mr Tony»: اللقب يتولّاه
                int e = nameEnd(l.nw, l.brk, st, kws, al);
                int n = e - st;
                if (n == 0) continue;
                int sc = hl > 0 || ll > 0 ? 9 + (n >= 2 ? 1 : 0) : 6 + (n >= 2 ? 1 : 0);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = titleCase(String.join(" ", l.ow.subList(st, e)));
                }
            }
            // 1ب2) لام الجر ملتصقة بالاسم بعد المبلغ أو العملة أو كلمة التسليم: «300 يورو لسامر علي»، «خمسمية لأحمد»
            for (int k = 1; k < l.nw.size(); k++) {
                String w = l.nw.get(k);
                if (w.length() < 4 || w.charAt(0) != 'ل' || w.startsWith("لل") || NAME_STOP.contains(w)) continue;
                String prev = l.nw.get(k - 1);
                boolean ctx = hasDigit(prev) || SYMBOLS.contains(prev) || currencyIn(prev, kws) != null || anyCurrencyWord(prev, al) != null
                        || NumberWords.isStrong(prev) || DELIVERY.contains(prev);
                String rem = w.substring(1);
                if (!ctx || !nameWord(rem, kws, al)) continue;
                int e = l.brk[k] ? k + 1 : nameEnd(l.nw, l.brk, k + 1, kws, al);
                if (e - k > 4) e = k + 4;
                int sc = 6 + (e - k >= 2 ? 1 : 0);
                if (sc > bestScore) {
                    bestScore = sc;
                    String first = l.ow.get(k).substring(1);
                    best = titleCase((first + " " + String.join(" ", l.ow.subList(k + 1, e))).trim());
                }
            }
            // 1ج) بعد الاسم (التركية): «Ahmet Yılmaz için»، «Ahmet Bey'e»، «Mehmet Kaya'ya»
            for (int k = 1; k < l.nw.size(); k++) {
                if (!POST_LABELS.contains(l.nw.get(k))) continue;
                int s0 = nameStart(l.nw, l.brk, k, kws, al);
                if (k - s0 == 0) continue;
                boolean honor = !l.nw.get(k).startsWith("icin") && !l.nw.get(k).startsWith("adina");
                int sc = (honor ? 9 : 7) + (k - s0 >= 2 ? 1 : 0);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = titleCase(String.join(" ", l.ow.subList(s0, k)));
                }
            }
            Matcher dat = TR_DATIVE.matcher(l.orig);
            while (dat.find()) {
                List<String> dn = words(norm(dat.group(1))), dor = words(dat.group(1));
                if (dn.size() != dor.size()) continue;
                int e = dn.size();
                while (e > 0 && POST_LABELS.contains(dn.get(e - 1))) e--;
                int s0 = nameStart(dn, null, e, kws, al);
                if (e - s0 == 0) continue;
                int sc = 8 + (e - s0 >= 2 ? 1 : 0);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = titleCase(String.join(" ", dor.subList(s0, e)));
                }
            }
            // 2) في سطر التسليم نفسه: «يرجى تسليم حمدي احمد محمد مبلغ 500»
            for (String d : DELIVERY) {
                List<String> dw = words(d);
                int at = indexOf(l.nw, dw);
                if (at < 0) continue;
                List<String> restN = l.nw.subList(at + dw.size(), l.nw.size());
                List<String> restO = l.ow.subList(Math.min(at + dw.size(), l.ow.size()), l.ow.size());
                int stop = 0;
                while (stop < restN.size() && nameWord(restN.get(stop), kws, al)) stop++;
                if (stop >= 2 && stop <= 6) {
                    int sc = 7;
                    if (sc > bestScore) {
                        bestScore = sc;
                        best = titleCase(String.join(" ", restO.subList(0, Math.min(stop, restO.size()))));
                    }
                }
            }
            // 3) سطر مستقل يشبه الاسم (بعد حذف رقم الهاتف منه إن وُجد)
            if (l.used || senderLine) continue;
            String candO = r.phone.isEmpty() ? l.orig : clean(l.orig.replace(r.phone, " "));
            String candN = norm(candO);
            List<String> candW = words(candN);
            if (containsAny(candN, GREETINGS) || containsAny(candN, BLESSINGS) || isCity(candN)) continue;
            if (!nameLike(candW)) continue;
            if (currencyIn(candN, kws) != null || anyCurrencyWord(candN, al) != null) continue;
            int sc = 3;
            if (i > 0 && isDeliveryOnly(lines.get(i - 1))) sc += 3;
            if (candW.size() >= 3 && candW.size() <= 4) sc += 1;
            int god = candW.indexOf("الله");
            if (god >= 0 && (god == 0 || !candW.get(god - 1).equals("عبد"))) sc -= 2;
            if (sc > bestScore) {
                bestScore = sc;
                best = titleCase(candO);
            }
        }
        r.person = cleanName(best);

        // ---------------------------------------------------------------- مدينة مذكورة في أي مكان
        if (r.city.isEmpty()) {
            List<String> pn = words(norm(r.person)), sn = words(norm(r.sender));
            for (Line l : lines) {
                for (int k = 0; k < l.nw.size() && r.city.isEmpty(); k++) {
                    if (pn.contains(l.nw.get(k)) || sn.contains(l.nw.get(k))) continue;
                    int n = cityAt(l.nw, k);
                    if (n > 0 && !(k > 0 && FROM_WORDS.contains(l.nw.get(k - 1)))) r.city = cityText(l.ow, l.nw, k, n);
                }
            }
        }

        // ---------------------------------------------------------------- كلمة عملة مجهولة
        if (r.currency == null && r.currencyWord.isEmpty() && !r.amount.isEmpty() && unknownCandidate(afterN)
                && !words(norm(r.person)).contains(afterN) && !words(norm(r.city)).contains(afterN)) {
            r.unknownWord = afterO;
        }
        // 12.5: المعادن الثمينة: الحساب بعياره، والوزن بوحدة الحساب
        Metals.apply(r, text, currencies);
        if (Metals.isMetal(r.currency) || (r.currency == null && Metals.baseIn(text) != null)) {
            r.unknownWord = "";
            // وحدة الوزن («غرام»، «كيلو») ليست اسم مستفيد
            if (Metals.weightIn(r.person) != null || Metals.baseIn(r.person) != null) r.person = "";
        }
        return r;
    }

    /** كلمات بين الرقم والعملة لا معنى لها هنا: «cinci sute de lei»، «five hundred of…». */
    private static final Set<String> FILLER = set("de", "of", "d");

    /** الاسم التركي قبل لاحقة الجر بعد الفاصلة العليا: «Mehmet Kaya'ya»، «Ahmet Bey'e». */
    private static final Pattern TR_DATIVE = Pattern.compile("([\\p{L}]+(?:[ ]+[\\p{L}]+){0,4})['’`](?:ya|ye|a|e|na|ne)(?![\\p{L}])");

    /** كلمات لا تكون جزءًا من اسم شخص: وظيفية، تحية، تسليم، تسميات، ألقاب… («عبد» و«الله» مستثناتان لأسماء مثل «عبد الله»). */
    private static final Set<String> NAME_STOP = new HashSet<>();

    static {
        NAME_STOP.addAll(NOT_NAME);
        for (List<String> l : Arrays.asList(GREETINGS, BLESSINGS, DELIVERY, HONORIFICS, PREP_LABELS, SENDER_LABELS, PHONE_LABELS, AMOUNT_LABELS,
                CITY_LABELS, FEE_WORDS, NET_WORDS, PERSON_LABELS)) {
            for (String p : l) if (words(p).size() == 1) NAME_STOP.add(p);
        }
        NAME_STOP.addAll(POST_LABELS);
        // كلمات تحية هي أيضًا أسماء شائعة: «Salam Hamdan»، «سلام»
        for (String w : new String[]{"الله", "عبد", "salam", "salaam", "selam", "سلام"}) NAME_STOP.remove(norm(w));
    }

    /** هل تصلح الكلمة (مطبّعة) جزءًا من اسم شخص؟ */
    private static boolean nameWord(String w, Map<String, List<Kw>> kws, Map<String, List<String>> al) {
        if (w.length() < 2) return false;
        if (!isLetters(w) || NAME_STOP.contains(w) || CITIES.contains(w) || prefixedCity(w) || NumberWords.isStrong(w)) return false;
        return currencyIn(w, kws) == null && anyCurrencyWord(w, al) == null;
    }

    /** نهاية الاسم ابتداءً من st (حتى 5 كلمات). */
    private static int nameEnd(List<String> nw, boolean[] brk, int st, Map<String, List<Kw>> kws, Map<String, List<String>> al) {
        int e = st;
        while (e < nw.size() && e - st < 5 && nameWord(nw.get(e), kws, al)) {
            if (e > st && cityAt(nw, e) > 0) break;   // «Ion Popescu Deir Ezzor»: المدينة ليست من الاسم
            e++;
            if (brk != null && brk[e - 1]) break;
        }
        return e;
    }

    /** بداية الاسم الذي ينتهي قبل end (حتى 4 كلمات للخلف). */
    private static int nameStart(List<String> nw, boolean[] brk, int end, Map<String, List<Kw>> kws, Map<String, List<String>> al) {
        int s0 = end;
        while (s0 > 0 && end - s0 < 4 && nameWord(nw.get(s0 - 1), kws, al)) {
            if (brk != null && s0 < end && brk[s0 - 1]) break;
            s0--;
        }
        return s0;
    }

    /** طول أطول عبارة من القائمة تبدأ عند k، أو 0. */
    private static int phraseAt(List<String> nw, int k, List<String> phrases) {
        int best = 0;
        for (String p : phrases) {
            List<String> pw = words(p);
            if (pw.size() > best && k + pw.size() <= nw.size() && startsWithPhrase(nw.subList(k, nw.size()), pw)) best = pw.size();
        }
        return best;
    }

    /** مدينة من كلمة أو كلمتين أو ثلاث تبدأ عند k: عدد كلماتها، أو 0. */
    private static int cityAt(List<String> nw, int k) {
        for (int n = Math.min(3, nw.size() - k); n >= 1; n--) {
            if (CITIES.contains(String.join(" ", nw.subList(k, k + n)))) return n;
        }
        return prefixedCity(nw.get(k)) ? 1 : 0;
    }

    /** «بحلب»، «لدمشق»، «وحمص»: مدينة ملتصق بها حرف جر أو عطف. */
    private static boolean prefixedCity(String w) {
        return w.length() > 3 && "بلوف".indexOf(w.charAt(0)) >= 0 && CITIES.contains(w.substring(1));
    }

    /** نص المدينة للعرض بلا الحرف الملتصق. */
    private static String cityText(List<String> ow, List<String> nw, int k, int n) {
        if (n == 1 && !CITIES.contains(nw.get(k)) && prefixedCity(nw.get(k))) return ow.get(k).substring(1);
        return titleCase(String.join(" ", ow.subList(k, k + n)));
    }

    /** الأسماء اللاتينية المكتوبة بحروف صغيرة تُكتب بحرف كبير أولًا: «mr tony» ← «Tony». العربية كما هي. */
    static String titleCase(String s) {
        for (char ch : s.toCharArray()) if (Character.isUpperCase(ch)) return s;   // «Ahmad bin Ali» كما كُتب
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            if (w.isEmpty()) continue;
            boolean lower = true, latin = false;
            for (char ch : w.toCharArray()) {
                if (Character.isUpperCase(ch)) lower = false;
                Character.UnicodeScript sc = Character.UnicodeScript.of(ch);
                if (sc == Character.UnicodeScript.LATIN || sc == Character.UnicodeScript.CYRILLIC) latin = true;
            }
            sb.append(lower && latin ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : w);
        }
        return sb.toString();
    }

    private static final Set<String> NOT_CURRENCY = set("صافي", "صافيه", "صافية", "فقط", "لاغير", "الف", "الاف", "ألف", "آلاف", "مليون", "ملايين",
            "مليار", "نقدا", "نقداً", "كاش", "مبلغ", "المبلغ", "قيمة", "بقيمة", "بمبلغ", "يدا", "بيد", "كامل", "كاملة", "كاملين", "تماما", "زكاتكم",
            "زكاة", "صدقة", "شكرا", "مشكورين", "لو", "سمحت", "فضلك", "بس", "اليوم", "بكرا", "غدا", "عاجل", "ضروري", "مستعجل", "للمستلم", "عند", "عنده",
            "عندكم", "منكم", "لكم", "لنا", "علينا", "عليكم", "بدون", "مع", "الاجرة", "اجرة", "أجرة", "عمولة", "راجع", "الراجع", "خصم", "هي", "هو", "على", "عالحساب");

    // 12.3: قاموس التحية والمجاملة المشترك (WaWords): لا يُستخرج اسمًا للمستفيد ولا كلمة عملة مجهولة
    static {
        for (String w : new String[]{"غرام", "غرامات", "جرام", "كيلو", "اونصة", "أونصة", "مثقال", "تولة", "عيار", "قيراط", "ذهب", "فضة", "فضه", "كسر"}) {
            NOT_NAME.add(norm(w));
            NOT_CURRENCY.add(norm(w));
        }
        for (String w : WaWords.COURTESY) {
            String n = norm(w);
            NOT_NAME.add(n);
            NOT_CURRENCY.add(n);
        }
    }

    /** هل تصلح الكلمة (مطبّعة) أن تكون اسم عملة عاميًّا مجهولًا؟ */
    private static boolean unknownCandidate(String n) {
        if (n == null || n.length() < 2 || n.length() > 15 || !isLetters(n)) return false;
        if (NOT_NAME.contains(n) || NOT_CURRENCY.contains(n) || CITIES.contains(n)) return false;
        for (String p : FEE_WORDS) if (p.equals(n)) return false;
        for (String p : DELIVERY) if (p.equals(n)) return false;
        for (String p : PERSON_LABELS) if (p.equals(n)) return false;
        for (String p : GREETINGS) if (words(p).contains(n)) return false;
        for (String p : BLESSINGS) if (words(p).contains(n)) return false;
        return true;
    }

    private static int indexOf(List<String> hay, List<String> nee) {
        if (nee.isEmpty()) return -1;
        for (int i = 0; i + nee.size() <= hay.size(); i++) {
            boolean ok = true;
            for (int j = 0; j < nee.size(); j++) {
                if (!hay.get(i + j).equals(nee.get(j))) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    /** سطر فيه كلمة تسليم/حوالة فقط (مع «يرجى» ونحوها) ولا اسم بعده. */
    private static boolean isDeliveryOnly(Line l) {
        if (hasDigit(l.orig)) return false;
        for (String w : l.nw) if (!NOT_NAME.contains(w) && !DELIVERY.contains(w)) return false;
        return containsAny(l.norm, DELIVERY);
    }

    // ===================================================================================
    // البيان المختصر للعرض (الكشوفات وقوائم الحركات)
    // ===================================================================================

    /** أقصى طول للبيان المختصر قبل «…». */
    public static final int COMPACT_MAX = 150;

    private static final Pattern TOKEN = Pattern.compile("[^\\s،,:;؛\\-–—/\\\\|*()\\[\\]\"'«»!؟?.]+");
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}\\x{20E3}]");
    private static final Pattern EDGE = Pattern.compile("^[\\s:،,;؛\\-–—.]+|[\\s:،,;؛\\-–—]+$");

    private static List<List<String>> phrases(List<String> base, String... extra) {
        List<List<String>> out = new ArrayList<>();
        for (String s : base) out.add(words(s));
        for (String s : extra) out.add(words(norm(s)));
        // الأطول أولًا حتى تُحذف «السلام عليكم ورحمة الله» كاملة لا «السلام عليكم» وحدها
        java.util.Collections.sort(out, (x, y) -> y.size() - x.size());
        return out;
    }

    private static final List<List<String>> OPENERS = phrases(GREETINGS, "ورحمة الله وبركاته", "ورحمة الله", "وبركاته", "اخي", "اخوي", "أخي",
            "الغالي", "يا غالي", "شباب", "يا شباب", "اهلين", "مرحبتين", "الاخوة الكرام", "الأخوة الكرام", "اخوتي", "hi", "hello", "dear");
    private static final List<List<String>> CLOSERS;
    private static final List<List<String>> REQUEST = phrases(list("يرجى تسليم", "الرجاء تسليم", "رجاء تسليم", "نرجو تسليم", "يرجى التسليم",
            "الرجاء التسليم", "تسليم", "التسليم", "تسليم الى", "تسليم إلى", "سلمو", "سلموا", "يرجى", "الرجاء", "نرجو", "رجاء", "ارجو",
            "من فضلك", "لو سمحت", "بدنا نسلم", "please deliver", "please", "pls", "plz", "need", "lütfen", "vă rog", "va rog", "bitte",
            "por favor", "s'il vous plaît", "svp", "пожалуйста"));

    static {
        // «عاجل» و«زكاة» و«صدقة» معلومات مفيدة فلا تُحذف من البيان المختصر
        Set<String> keep = set("عاجل", "ضروري", "مستعجل", "بأسرع وقت", "زكاة", "صدقة");
        List<String> b = new ArrayList<>();
        for (String s : BLESSINGS) if (!keep.contains(s)) b.add(s);
        CLOSERS = phrases(b, "جزاكم الله خيرا", "وجزاكم الله خيرا", "الله يعطيك العافية", "الله يعطيكم العافية", "يعطيكم العافيه", "شكرا لكم",
                "شكرا جزيلا", "مع خالص الشكر", "ودمتم بخير", "وشكرا", "ولكم الشكر", "thanks", "thank you");
    }

    private static final List<List<String>> PREPS_P = phrases(PREP_LABELS);
    private static final List<List<String>> HONOR_P = phrases(HONORIFICS);
    private static final List<List<String>> POST_P = phrases(new ArrayList<>(POST_LABELS));

    private static final class Tok {
        final int s, e;
        final String n;

        Tok(int s, int e, String n) {
            this.s = s;
            this.e = e;
            this.n = n;
        }
    }

    private static List<Tok> toks(String clean) {
        List<Tok> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(clean);
        while (m.find()) out.add(new Tok(m.start(), m.end(), norm(m.group())));
        return out;
    }

    private static boolean matchAt(List<Tok> t, int i, List<String> p) {
        if (p.isEmpty() || i < 0 || i + p.size() > t.size()) return false;
        for (int k = 0; k < p.size(); k++) if (!t.get(i + k).n.equals(p.get(k))) return false;
        return true;
    }

    /** عدد الرموز من i التي تُحذف لأنها عبارات من القائمة (متتالية). */
    private static int stripFront(List<Tok> t, int i, int end, List<List<String>> ps) {
        boolean again = true;
        while (again && i < end) {
            again = false;
            for (List<String> p : ps) {
                if (i + p.size() <= end && matchAt(t, i, p)) {
                    i += p.size();
                    again = true;
                    break;
                }
            }
        }
        return i;
    }

    private static int stripBack(List<Tok> t, int start, int j, List<List<String>> ps) {
        boolean again = true;
        while (again && j > start) {
            again = false;
            for (List<String> p : ps) {
                if (j - p.size() >= start && matchAt(t, j - p.size(), p)) {
                    j -= p.size();
                    again = true;
                    break;
                }
            }
        }
        return j;
    }

    /**
     * يختصر رسالة واتساب الملصقة في البيان لعرضها في الكشوفات والقوائم: يحذف التحية والدعاء و«يرجى تسليم»
     * واسم المستفيد إن كان يُعرض بجانبها (person)، ويجمع الباقي في سطر واحد. النص المحفوظ لا يتغيّر أبدًا.
     * person = null أو فارغ: لا يُحذف الاسم.
     */
    public static String compact(String desc, String person) {
        if (desc == null) return "";
        String raw = EMOJI.matcher(desc).replaceAll(" ");
        List<String> lines = new ArrayList<>();
        for (String s : raw.split("\\r?\\n")) {
            String c = clean(s);
            if (!c.isEmpty()) lines.add(c);
        }
        if (lines.isEmpty()) return "";
        String first = norm(lines.get(0));
        boolean pasted = lines.size() >= 2 || containsAny(first, GREETINGS) || raw.length() >= 60;
        if (!pasted) return limit(lines.get(0));
        List<String> pw = person == null ? new ArrayList<String>() : words(norm(person));
        List<List<String>> who = new ArrayList<>();
        if (!pw.isEmpty()) {
            who.add(pw);
            for (String lab : PERSON_LABELS) {
                List<String> p = new ArrayList<>(words(lab));
                p.addAll(pw);
                who.add(p);
            }
            List<String> li = new ArrayList<>();
            li.add("ل");
            li.addAll(pw);
            who.add(li);
            java.util.Collections.sort(who, (x, y) -> y.size() - x.size());
        }
        List<String> keep = new ArrayList<>();
        for (String c : lines) {
            List<Tok> t = toks(c);
            if (t.isEmpty()) continue;
            int i = stripFront(t, 0, t.size(), OPENERS);
            int j = stripBack(t, i, t.size(), CLOSERS);
            int i2 = stripFront(t, i, j, REQUEST);
            if (!who.isEmpty()) {
                // «to Mr John Smith» / «للسيد أحمد»: حرف الجر واللقب يُحذفان مع الاسم المعروض بجانب البيان
                int i3 = stripFront(t, stripFront(t, i2, j, PREPS_P), j, HONOR_P);
                int i4 = stripFront(t, i3, j, who);
                if (i4 > i3) i2 = i4;
                else i2 = stripFront(t, i2, j, who);
                i2 = stripFront(t, i2, j, POST_P);   // «Ahmet Yılmaz için 2 bin dolar» ← «2 bin dolar»
            }
            // السطر لم يبقَ منه شيء غير «يرجى تسليم» أو التحية أو الاسم
            if (i2 >= j) continue;
            // لا نحذف «تسليم» وحدها إن لم يتبعها الاسم: «تسليم 500 دولار» ← «500 دولار» (التسليم معروف ضمنًا)
            int from = i2 == 0 ? 0 : t.get(i2).s;
            int to = j == t.size() ? c.length() : t.get(j - 1).e;
            String part = EDGE.matcher(c.substring(from, to)).replaceAll("").trim();
            if (!part.isEmpty()) keep.add(part);
        }
        if (keep.isEmpty()) return "";
        return limit(Model0.join(keep, " • "));
    }

    private static String limit(String s) {
        String t = s.replaceAll("\\s{2,}", " ").trim();
        if (t.length() <= COMPACT_MAX) return t;
        int cut = t.lastIndexOf(' ', COMPACT_MAX - 1);
        if (cut < COMPACT_MAX / 2) cut = COMPACT_MAX - 1;
        return t.substring(0, cut).trim() + "…";
    }

    /** دمج بسيط بلا اعتماد على Model (حتى يبقى المحلّل مستقلًا للاختبار). */
    private static final class Model0 {
        static String join(List<String> l, String sep) {
            StringBuilder sb = new StringBuilder();
            for (String s : l) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(s);
            }
            return sb.toString();
        }
    }

    // ===================================================================================
    // كلمات العملات التي يضيفها المستخدم: تُحفظ في الإعدادات بصيغة «USDT=يوز|يوزات» سطرًا لكل عملة
    // ===================================================================================

    public static Map<String, List<String>> aliasesFrom(String stored) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        if (stored == null) return m;
        for (String line : stored.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String code = line.substring(0, eq).trim();
            List<String> l = new ArrayList<>();
            for (String w : line.substring(eq + 1).split("\\|")) {
                String x = w.trim();
                if (!x.isEmpty() && !l.contains(x)) l.add(x);
            }
            if (!code.isEmpty() && !l.isEmpty()) m.put(code, l);
        }
        return m;
    }

    public static String aliasesTo(Map<String, List<String>> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : m.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('=').append(Model0.join(e.getValue(), "|"));
        }
        return sb.toString();
    }

    /** الكلمات التي يعرفها البرنامج مسبقًا لعملة (للعرض في شاشة كلمات العملات). */
    public static List<String> builtinWords(String code) {
        String[] s = SYN.get(code);
        return s == null ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s));
    }

    private static final Set<String> TOO_COMMON = set("من", "الى", "إلى", "في", "على", "عن", "مع", "او", "أو", "و", "يا", "لا", "ما", "هذا", "هذه",
            "يرجى", "تسليم", "التسليم", "حوالة", "حواله", "مبلغ", "المبلغ", "صافي", "فقط", "الف", "مليون", "اجرة", "الاجرة", "السلام", "عليكم", "الله",
            "شكرا", "اسم", "الاسم", "المستلم", "المستفيد", "رقم", "هاتف", "لنا", "لكم", "علينا", "عليكم");

    /**
     * سبب رفض إضافة كلمة لعملة، أو null إن كانت مقبولة.
     * تُرفض الكلمة إن كانت عامة جدًا، أو يعرفها البرنامج لعملة أخرى، أو أضيفت لعملة أخرى.
     */
    public static String aliasProblem(String word, String code, Map<String, List<String>> aliases, List<Cur> currencies) {
        String w = word == null ? "" : word.trim();
        if (w.isEmpty()) return "اكتب الكلمة.";
        if (w.contains("|") || w.contains("=") || w.contains("\n")) return "الكلمة لا يجوز أن تحتوي | أو =";
        if (hasDigit(digits(w))) return "الكلمة لا تحتوي أرقامًا.";
        if (w.length() > 30) return "الكلمة طويلة جدًا (30 حرفًا على الأكثر).";
        String n = SYMBOLS.contains(w) ? w : String.join(" ", words(norm(w)));
        if (n.isEmpty()) return "اكتب الكلمة بالحروف.";
        if (words(n).size() > 3) return "اكتب كلمة أو عبارة قصيرة (3 كلمات على الأكثر).";
        if (n.length() < 2) return "الكلمة قصيرة جدًا.";
        if (TOO_COMMON.contains(n) || (words(n).size() == 1 && NOT_NAME.contains(n) && !SYN_OWNER.containsKey(n)))
            return "«" + w + "» كلمة عامة تظهر في رسائل كثيرة، ولا تصلح اسمًا لعملة.";
        String owner = SYN_OWNER.get(n);
        if (owner != null && owner.equals(code)) return "البرنامج يعرف «" + w + "» لهذه العملة مسبقًا، لا حاجة لإضافتها.";
        boolean ownerDefined = false;
        if (currencies != null) for (Cur c : currencies) if (c.code.equals(owner)) ownerDefined = true;
        // «فضة» تخص XAG، لكن إن لم تكن XAG معرّفة فيمكن ربطها بعملة المستخدم «SILVER»
        if (owner != null && ownerDefined) return "«" + w + "» يعرفها البرنامج لعملة " + owner + "، فلا يمكن ربطها بعملة أخرى.";
        if (currencies != null) {
            for (Cur c : currencies) {
                if (c.code.equals(code)) continue;
                if (norm(c.code).equals(n) || String.join(" ", words(norm(c.name))).equals(n))
                    return "«" + w + "» هي اسم عملة " + c.code + " أو رمزها.";
            }
        }
        if (aliases != null) {
            for (Map.Entry<String, List<String>> e : aliases.entrySet()) {
                for (String x : e.getValue()) {
                    String xn = SYMBOLS.contains(x) ? x : String.join(" ", words(norm(x)));
                    if (!xn.equals(n)) continue;
                    return e.getKey().equals(code) ? "«" + w + "» مضافة لهذه العملة مسبقًا." : "«" + w + "» مضافة مسبقًا لعملة " + e.getKey() + ". احذفها من هناك أولًا.";
                }
            }
        }
        return null;
    }
}
