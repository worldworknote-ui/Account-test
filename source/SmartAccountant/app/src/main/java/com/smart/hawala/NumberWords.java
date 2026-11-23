package com.smart.hawala;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * المبالغ المكتوبة بالحروف: العربية (الفصحى والشامية) والإنجليزية والتركية والرومانية والفرنسية والألمانية والإسبانية والروسية.
 * أمثلة: «خمسمية» ← 500، «ألفين ونص» ← 2500، «five hundred» ← 500، «beş yüz» و«beşyüz» ← 500، «cinci sute» ← 500،
 * «deux mille cinq cents» ← 2500، «fünfhundertzwanzig» ← 520، «dos mil quinientos» ← 2500، «пятьсот» ← 500.
 * منطق بحت بلا أندرويد. الكلمات تُقارن بعد MessageParser.norm (بلا علامات وبحروف صغيرة).
 */
public final class NumberWords {
    private NumberWords() {}

    static final int SMALL = 0, HUNDRED = 1, BIG = 2, HALF = 3, QUARTER = 4, ARTICLE = 5;

    /** كلمة رقمية: نوعها وقيمتها واللغات التي تنتمي إليها (حتى لا تُخلط لغتان في مبلغ واحد). */
    static final class W {
        final int kind;
        final long value;
        /** للمثنى العربي «ألفين» و«مليونين»: العدد المضمَّن (2). */
        final int count;
        final Set<String> langs;

        W(int kind, long value, int count, Set<String> langs) {
            this.kind = kind;
            this.value = value;
            this.count = count;
            this.langs = langs;
        }
    }

    private static final Map<String, W> DICT = new HashMap<>();
    private static final Map<String, Set<String>> CONNECTORS = new HashMap<>();
    /** كلمات رقمية تشبه كلمات عادية أو أسماء («bin» في Ahmad bin Ali، «on» الإنجليزية، «ست» بمعنى سيدة…): لا تكفي وحدها. */
    private static final Set<String> WEAK = new HashSet<>();

    private static void put(String lang, int kind, long value, int count, String... words) {
        for (String w : words) {
            String k = MessageParser.norm(w);
            W old = DICT.get(k);
            if (old != null && old.kind == kind && old.value == value && old.count == count) {
                old.langs.add(lang);
                continue;
            }
            if (old != null) continue;   // أول تعريف هو المعتمد
            DICT.put(k, new W(kind, value, count, new HashSet<>(Arrays.asList(lang))));
        }
    }

    private static void small(String lang, long v, String... w) { put(lang, SMALL, v, 0, w); }

    private static void conn(String lang, String... w) {
        for (String x : w) {
            String k = MessageParser.norm(x);
            Set<String> s = CONNECTORS.get(k);
            if (s == null) CONNECTORS.put(k, s = new HashSet<>());
            s.add(lang);
        }
    }

    static {
        // ---------------------------------------------------------------- العربية (فصحى وشامية)
        String ar = "ar";
        small(ar, 1, "واحد", "واحدة", "احد", "إحدى", "احدى");
        small(ar, 2, "اثنان", "اثنين", "اتنين", "تنين", "ثنتين", "اثنا", "اثني");
        small(ar, 3, "ثلاث", "ثلاثة", "تلات", "تلاتة", "تلاث");
        small(ar, 4, "أربع", "أربعة", "اربع", "اربعة");
        small(ar, 5, "خمس", "خمسة");
        small(ar, 6, "ست", "ستة");
        small(ar, 7, "سبع", "سبعة");
        small(ar, 8, "ثمان", "ثماني", "ثمانية", "تمن", "تمنة", "تمانة", "تمانية");
        small(ar, 9, "تسع", "تسعة");
        small(ar, 10, "عشر", "عشرة");
        small(ar, 11, "حدعش", "احدعش", "إحدعش");
        small(ar, 12, "اتنعش", "تنعش", "اثنعش");
        small(ar, 13, "تلتعش", "تلطعش", "ثلطعش");
        small(ar, 14, "اربعتعش", "اربطعش", "أربعتعش");
        small(ar, 15, "خمستعش", "خمسطعش");
        small(ar, 16, "ستعش", "سطعش", "ستطعش");
        small(ar, 17, "سبعتعش", "سبعطعش");
        small(ar, 18, "تمنتعش", "تمنطعش", "ثمنطعش");
        small(ar, 19, "تسعتعش", "تسعطعش");
        small(ar, 20, "عشرين", "عشرون");
        small(ar, 30, "ثلاثين", "ثلاثون", "تلاتين", "تلتين");
        small(ar, 40, "أربعين", "اربعين", "أربعون", "اربعون");
        small(ar, 50, "خمسين", "خمسون");
        small(ar, 60, "ستين", "ستون");
        small(ar, 70, "سبعين", "سبعون");
        small(ar, 80, "ثمانين", "ثمانون", "تمانين", "تمنين");
        small(ar, 90, "تسعين", "تسعون");
        small(ar, 200, "مئتين", "مئتان", "مائتين", "مائتان", "ميتين", "ميتان", "متين");
        String[][] stems = {{"ثلاث", "3"}, {"ثلث", "3"}, {"تلت", "3"}, {"تلات", "3"}, {"أربع", "4"}, {"اربع", "4"}, {"خمس", "5"}, {"ست", "6"},
                {"سبع", "7"}, {"ثمان", "8"}, {"ثمن", "8"}, {"تمن", "8"}, {"تمان", "8"}, {"تسع", "9"}};
        for (String[] s : stems) for (String h : new String[]{"مئة", "مائة", "مية", "ميه", "مئه"}) small(ar, Long.parseLong(s[1]) * 100, s[0] + h);
        put(ar, HUNDRED, 100, 0, "مئة", "مائة", "مية", "ميه", "مئه", "مائه");
        put(ar, BIG, 1000, 0, "ألف", "الف", "آلاف", "الاف", "ألوف", "الوف", "ألفا", "الفا");
        put(ar, BIG, 1000, 2, "ألفين", "الفين", "ألفان", "الفان");
        put(ar, BIG, 1000000, 0, "مليون", "ملايين", "مليونا");
        put(ar, BIG, 1000000, 2, "مليونين", "مليونان");
        put(ar, BIG, 1000000000, 0, "مليار", "مليارات");
        put(ar, BIG, 1000000000, 2, "مليارين", "ملياري");
        put(ar, HALF, 0, 0, "نص", "نصف");
        put(ar, QUARTER, 0, 0, "ربع");
        conn(ar, "و");

        // ---------------------------------------------------------------- English
        String en = "en";
        small(en, 1, "one");
        small(en, 2, "two");
        small(en, 3, "three");
        small(en, 4, "four");
        small(en, 5, "five");
        small(en, 6, "six");
        small(en, 7, "seven");
        small(en, 8, "eight");
        small(en, 9, "nine");
        small(en, 10, "ten");
        small(en, 11, "eleven");
        small(en, 12, "twelve");
        small(en, 13, "thirteen");
        small(en, 14, "fourteen");
        small(en, 15, "fifteen");
        small(en, 16, "sixteen");
        small(en, 17, "seventeen");
        small(en, 18, "eighteen");
        small(en, 19, "nineteen");
        small(en, 20, "twenty");
        small(en, 30, "thirty");
        small(en, 40, "forty", "fourty");
        small(en, 50, "fifty");
        small(en, 60, "sixty");
        small(en, 70, "seventy");
        small(en, 80, "eighty");
        small(en, 90, "ninety");
        put(en, HUNDRED, 100, 0, "hundred", "hundreds");
        put(en, BIG, 1000, 0, "thousand", "thousands", "grand");
        put(en, BIG, 1000000, 0, "million", "millions");
        put(en, BIG, 1000000000, 0, "billion", "billions");
        put(en, HALF, 0, 0, "half");
        put(en, ARTICLE, 1, 0, "a", "an");
        conn(en, "and");

        // ---------------------------------------------------------------- Türkçe
        String tr = "tr";
        small(tr, 1, "bir");
        small(tr, 2, "iki");
        small(tr, 3, "üç");
        small(tr, 4, "dört");
        small(tr, 5, "beş");
        small(tr, 6, "altı");
        small(tr, 7, "yedi");
        small(tr, 8, "sekiz");
        small(tr, 9, "dokuz");
        small(tr, 10, "on");
        small(tr, 20, "yirmi");
        small(tr, 30, "otuz");
        small(tr, 40, "kırk");
        small(tr, 50, "elli");
        small(tr, 60, "altmış");
        small(tr, 70, "yetmiş");
        small(tr, 80, "seksen");
        small(tr, 90, "doksan");
        put(tr, HUNDRED, 100, 0, "yüz");
        put(tr, BIG, 1000, 0, "bin");
        put(tr, BIG, 1000000, 0, "milyon");
        put(tr, BIG, 1000000000, 0, "milyar");
        put(tr, HALF, 0, 0, "buçuk");

        // ---------------------------------------------------------------- Română
        String ro = "ro";
        small(ro, 1, "unu", "una", "un");
        small(ro, 2, "doi", "două", "doua");
        small(ro, 3, "trei");
        small(ro, 4, "patru");
        small(ro, 5, "cinci");
        small(ro, 6, "șase", "şase");
        small(ro, 7, "șapte", "şapte");
        small(ro, 8, "opt");
        small(ro, 9, "nouă");
        small(ro, 10, "zece");
        small(ro, 11, "unsprezece");
        small(ro, 12, "doisprezece", "douăsprezece");
        small(ro, 13, "treisprezece");
        small(ro, 14, "paisprezece", "patrusprezece");
        small(ro, 15, "cincisprezece", "cinspe");
        small(ro, 16, "șaisprezece", "şaisprezece");
        small(ro, 17, "șaptesprezece", "şaptesprezece");
        small(ro, 18, "optsprezece");
        small(ro, 19, "nouăsprezece");
        small(ro, 20, "douăzeci");
        small(ro, 30, "treizeci");
        small(ro, 40, "patruzeci");
        small(ro, 50, "cincizeci");
        small(ro, 60, "șaizeci", "şaizeci");
        small(ro, 70, "șaptezeci", "şaptezeci");
        small(ro, 80, "optzeci");
        small(ro, 90, "nouăzeci");
        put(ro, HUNDRED, 100, 0, "sută", "sute");
        put(ro, BIG, 1000, 0, "mie", "mii");
        put(ro, BIG, 1000000, 0, "milion", "milioane");
        put(ro, BIG, 1000000000, 0, "miliard", "miliarde");
        put(ro, HALF, 0, 0, "jumătate");
        put(ro, ARTICLE, 1, 0, "o");
        conn(ro, "și", "şi", "de");

        // ---------------------------------------------------------------- Français
        String fr = "fr";
        small(fr, 1, "un", "une");
        small(fr, 2, "deux");
        small(fr, 3, "trois");
        small(fr, 4, "quatre");
        small(fr, 5, "cinq");
        small(fr, 6, "six");
        small(fr, 7, "sept");
        small(fr, 8, "huit");
        small(fr, 9, "neuf");
        small(fr, 10, "dix");
        small(fr, 11, "onze");
        small(fr, 12, "douze");
        small(fr, 13, "treize");
        small(fr, 14, "quatorze");
        small(fr, 15, "quinze");
        small(fr, 16, "seize");
        small(fr, 20, "vingt", "vingts");
        small(fr, 30, "trente");
        small(fr, 40, "quarante");
        small(fr, 50, "cinquante");
        small(fr, 60, "soixante");
        put(fr, HUNDRED, 100, 0, "cent", "cents");
        put(fr, BIG, 1000, 0, "mille");
        put(fr, BIG, 1000000, 0, "million", "millions");
        put(fr, BIG, 1000000000, 0, "milliard", "milliards");
        put(fr, HALF, 0, 0, "demi", "demie");
        conn(fr, "et");

        // ---------------------------------------------------------------- Deutsch
        String de = "de";
        small(de, 1, "eins", "ein", "eine");
        small(de, 2, "zwei");
        small(de, 3, "drei");
        small(de, 4, "vier");
        small(de, 5, "fünf");
        small(de, 6, "sechs");
        small(de, 7, "sieben");
        small(de, 8, "acht");
        small(de, 9, "neun");
        small(de, 10, "zehn");
        small(de, 11, "elf");
        small(de, 12, "zwölf");
        small(de, 13, "dreizehn");
        small(de, 14, "vierzehn");
        small(de, 15, "fünfzehn");
        small(de, 16, "sechzehn");
        small(de, 17, "siebzehn");
        small(de, 18, "achtzehn");
        small(de, 19, "neunzehn");
        small(de, 20, "zwanzig");
        small(de, 30, "dreißig", "dreissig");
        small(de, 40, "vierzig");
        small(de, 50, "fünfzig");
        small(de, 60, "sechzig");
        small(de, 70, "siebzig");
        small(de, 80, "achtzig");
        small(de, 90, "neunzig");
        put(de, HUNDRED, 100, 0, "hundert");
        put(de, BIG, 1000, 0, "tausend");
        put(de, BIG, 1000000, 0, "million", "millionen");
        put(de, BIG, 1000000000, 0, "milliarde", "milliarden");
        put(de, HALF, 0, 0, "halb", "einhalb");
        conn(de, "und");

        // ---------------------------------------------------------------- Español
        String es = "es";
        small(es, 1, "uno", "una", "un");
        small(es, 2, "dos");
        small(es, 3, "tres");
        small(es, 4, "cuatro");
        small(es, 5, "cinco");
        small(es, 6, "seis");
        small(es, 7, "siete");
        small(es, 8, "ocho");
        small(es, 9, "nueve");
        small(es, 10, "diez");
        small(es, 11, "once");
        small(es, 12, "doce");
        small(es, 13, "trece");
        small(es, 14, "catorce");
        small(es, 15, "quince");
        small(es, 16, "dieciséis");
        small(es, 17, "diecisiete");
        small(es, 18, "dieciocho");
        small(es, 19, "diecinueve");
        small(es, 20, "veinte");
        String[] veinti = {"veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"};
        for (int i = 0; i < veinti.length; i++) small(es, 21 + i, veinti[i]);
        small(es, 30, "treinta");
        small(es, 40, "cuarenta");
        small(es, 50, "cincuenta");
        small(es, 60, "sesenta");
        small(es, 70, "setenta");
        small(es, 80, "ochenta");
        small(es, 90, "noventa");
        small(es, 200, "doscientos", "doscientas");
        small(es, 300, "trescientos", "trescientas");
        small(es, 400, "cuatrocientos", "cuatrocientas");
        small(es, 500, "quinientos", "quinientas");
        small(es, 600, "seiscientos", "seiscientas");
        small(es, 700, "setecientos", "setecientas");
        small(es, 800, "ochocientos", "ochocientas");
        small(es, 900, "novecientos", "novecientas");
        put(es, HUNDRED, 100, 0, "cien", "ciento");
        put(es, BIG, 1000, 0, "mil");
        put(es, BIG, 1000000, 0, "millón", "millones");
        put(es, HALF, 0, 0, "medio");
        conn(es, "y");

        // ---------------------------------------------------------------- Русский
        String ru = "ru";
        small(ru, 1, "один", "одна", "одно");
        small(ru, 2, "два", "две");
        small(ru, 3, "три");
        small(ru, 4, "четыре");
        small(ru, 5, "пять");
        small(ru, 6, "шесть");
        small(ru, 7, "семь");
        small(ru, 8, "восемь");
        small(ru, 9, "девять");
        small(ru, 10, "десять");
        small(ru, 11, "одиннадцать");
        small(ru, 12, "двенадцать");
        small(ru, 13, "тринадцать");
        small(ru, 14, "четырнадцать");
        small(ru, 15, "пятнадцать");
        small(ru, 16, "шестнадцать");
        small(ru, 17, "семнадцать");
        small(ru, 18, "восемнадцать");
        small(ru, 19, "девятнадцать");
        small(ru, 20, "двадцать");
        small(ru, 30, "тридцать");
        small(ru, 40, "сорок");
        small(ru, 50, "пятьдесят");
        small(ru, 60, "шестьдесят");
        small(ru, 70, "семьдесят");
        small(ru, 80, "восемьдесят");
        small(ru, 90, "девяносто");
        small(ru, 100, "сто");
        small(ru, 200, "двести");
        small(ru, 300, "триста");
        small(ru, 400, "четыреста");
        small(ru, 500, "пятьсот");
        small(ru, 600, "шестьсот");
        small(ru, 700, "семьсот");
        small(ru, 800, "восемьсот");
        small(ru, 900, "девятьсот");
        put(ru, BIG, 1000, 0, "тысяча", "тысячи", "тысяч", "тыс");
        put(ru, BIG, 1000000, 0, "миллион", "миллиона", "миллионов", "млн");
        put(ru, BIG, 1000000000, 0, "миллиард", "миллиарда", "миллиардов", "млрд");
        put(ru, HALF, 0, 0, "половина");
        conn(ru, "и");

        for (String w : new String[]{"bin", "on", "ست", "سبع", "un", "une", "una", "uno", "o", "a", "an", "ein", "eine", "elf", "once", "cent", "cents",
                "sept", "neuf", "noua", "opt", "mil", "mie", "acht", "six", "demi", "medio", "half", "halb", "نص", "ربع", "احد", "اثنا", "اثني", "عشر",
                "doi", "trei", "dos", "tres", "seis", "uc", "iki", "bir", "yedi", "elli", "on", "grand", "dix", "douze", "seize", "vier", "drei"}) {
            WEAK.add(MessageParser.norm(w));
        }
    }

    /** الكلمة (مطبّعة) كلمة رقمية؟ تُفهم «وخمسين» و«بألف» بحذف الواو أو الباء العربية. */
    static W lookup(String w) {
        W x = DICT.get(w);
        if (x != null) return x;
        if (w.length() > 2 && (w.charAt(0) == 'و' || w.charAt(0) == 'ب')) {
            x = DICT.get(w.substring(1));
            if (x != null && x.langs.contains("ar")) return x;
            if (w.length() > 3 && w.charAt(0) == 'و' && w.charAt(1) == 'ب') {
                x = DICT.get(w.substring(2));
                if (x != null && x.langs.contains("ar")) return x;
            }
        }
        return null;
    }

    /** كلمة رقمية واضحة (ليست من الكلمات المشتبهة بأسماء أو كلمات عادية). */
    static boolean isStrong(String w) {
        W x = lookup(w);
        return x != null && x.kind != ARTICLE && !WEAK.contains(w) && !(w.length() > 1 && WEAK.contains(w.substring(1)) && x == DICT.get(w.substring(1)));
    }

    /** مبلغ بالحروف وُجد في قائمة كلمات: من start إلى end (غير شامل) بقيمته. */
    static final class Match {
        final int start, end;
        final BigDecimal value;
        /** فيه «مئة/ألف/مليون» أو ما يعادلها. */
        final boolean multiplier;
        /** كلمة واحدة فقط من الكلمات المشتبهة: لا تُقبل إلا بجانب عملة. */
        final boolean weak;
        final int words;

        Match(int start, int end, BigDecimal value, boolean multiplier, boolean weak, int words) {
            this.start = start;
            this.end = end;
            this.value = value;
            this.multiplier = multiplier;
            this.weak = weak;
            this.words = words;
        }
    }

    /** تفكيك كلمة ملتصقة مثل «beşyüzelli» أو «zweihundertfünfundzwanzig» إلى كلمات رقمية، أو null. */
    static List<String> split(String w) {
        if (w.length() < 5 || DICT.containsKey(w)) return null;
        for (char ch : w.toCharArray()) if (ch < 'a' || ch > 'z') return null;
        List<String> out = new ArrayList<>();
        return split(w, 0, out) && out.size() >= 2 ? out : null;
    }

    private static boolean split(String w, int at, List<String> out) {
        if (at == w.length()) return true;
        for (int end = w.length(); end > at + 1; end--) {
            String p = w.substring(at, end);
            boolean ok = (DICT.containsKey(p) && DICT.get(p).kind != ARTICLE) || p.equals("und");
            if (!ok) continue;
            out.add(p);
            if (split(w, end, out)) return true;
            out.remove(out.size() - 1);
        }
        return false;
    }

    /** كل المبالغ المكتوبة بالحروف في قائمة كلمات مطبّعة (كما يقسّمها المحلّل). */
    static List<Match> find(List<String> nw) {
        // توسيع الكلمات الملتصقة مع حفظ موقع كل جزء في القائمة الأصلية
        List<String> t = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < nw.size(); i++) {
            List<String> parts = lookup(nw.get(i)) == null ? split(nw.get(i)) : null;
            if (parts == null) {
                t.add(nw.get(i));
                at.add(i);
            } else {
                for (String p : parts) {
                    t.add(p);
                    at.add(i);
                }
            }
        }
        List<Match> out = new ArrayList<>();
        int i = 0;
        while (i < t.size()) {
            W first = lookup(t.get(i));
            if (first == null || !startsOk(t, i, first)) {
                i++;
                continue;
            }
            Set<String> langs = new HashSet<>(first.langs);
            List<W> seq = new ArrayList<>();
            int j = i;
            while (j < t.size()) {
                String w = t.get(j);
                W x = lookup(w);
                // «quatre vingt(s)» = 80
                if (x != null && w.equals("quatre") && j + 1 < t.size() && (t.get(j + 1).equals("vingt") || t.get(j + 1).equals("vingts")) && langs.contains("fr")) {
                    seq.add(new W(SMALL, 80, 0, new HashSet<>(Arrays.asList("fr"))));
                    langs.retainAll(Arrays.asList("fr"));
                    j += 2;
                    continue;
                }
                if (x == null) {
                    Set<String> c = CONNECTORS.get(w);
                    if (c == null && w.equals("und")) c = new HashSet<>(Arrays.asList("de"));
                    if (c != null && !seq.isEmpty() && j + 1 < t.size()) {
                        W nx = lookup(t.get(j + 1));
                        if (nx != null && overlaps(langs, c) && overlaps(langs, nx.langs)) {
                            j++;
                            continue;
                        }
                    }
                    break;
                }
                if (!overlaps(langs, x.langs)) break;
                if (x.kind == ARTICLE && !(j + 1 < t.size() && isMult(lookup(t.get(j + 1))))) break;
                if (x.kind == HALF || x.kind == QUARTER) {
                    if (seq.isEmpty()) break;
                }
                Set<String> keep = new HashSet<>(langs);
                keep.retainAll(x.langs);
                langs = keep;
                seq.add(x);
                j++;
            }
            if (seq.isEmpty()) {
                i++;
                continue;
            }
            BigDecimal v = value(seq);
            boolean mult = false;
            for (W x : seq) if (x.kind == HUNDRED || x.kind == BIG || x.value >= 100) mult = true;
            boolean weak = seq.size() == 1 && (WEAK.contains(t.get(i)) || seq.get(0).kind != SMALL || seq.get(0).value < 100);
            if (v.signum() > 0) out.add(new Match(at.get(i), at.get(j - 1) + 1, v, mult, weak, seq.size()));
            i = j;
        }
        return out;
    }

    private static boolean startsOk(List<String> t, int i, W x) {
        if (x.kind == HALF || x.kind == QUARTER) return false;
        if (x.kind == ARTICLE) return i + 1 < t.size() && isMult(lookup(t.get(i + 1)));
        return true;
    }

    private static boolean isMult(W x) { return x != null && (x.kind == HUNDRED || x.kind == BIG); }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        for (String x : b) if (a.contains(x)) return true;
        return false;
    }

    static BigDecimal value(List<W> seq) {
        BigDecimal total = BigDecimal.ZERO, current = BigDecimal.ZERO;
        BigDecimal lastMult = null;
        for (W x : seq) {
            switch (x.kind) {
                case SMALL:
                case ARTICLE:
                    current = current.add(BigDecimal.valueOf(x.value));
                    break;
                case HUNDRED:
                    current = (current.signum() == 0 ? BigDecimal.ONE : current).multiply(BigDecimal.valueOf(100));
                    lastMult = BigDecimal.valueOf(100);
                    break;
                case BIG:
                    BigDecimal n = current.signum() == 0 ? BigDecimal.valueOf(x.count > 0 ? x.count : 1) : current;
                    total = total.add(n.multiply(BigDecimal.valueOf(x.value)));
                    current = BigDecimal.ZERO;
                    lastMult = BigDecimal.valueOf(x.value);
                    break;
                case HALF:
                case QUARTER:
                    BigDecimal part = x.kind == HALF ? new BigDecimal("0.5") : new BigDecimal("0.25");
                    if (current.signum() == 0 && lastMult != null) total = total.add(lastMult.multiply(part));
                    else current = current.add(part);
                    break;
                default:
                    break;
            }
        }
        return total.add(current).stripTrailingZeros();
    }
}
