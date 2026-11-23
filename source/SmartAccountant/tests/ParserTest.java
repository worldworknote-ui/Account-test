import com.smart.hawala.*;
import java.util.*;
public class ParserTest {
  static int fails = 0, n = 0;
  static List<MessageParser.Cur> CURS = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي"), new MessageParser.Cur("SYP", "ليرة سورية ( جديد )"),
      new MessageParser.Cur("SYO", "ليرة سورية قديم"), new MessageParser.Cur("EUR", "يورو اوربي"), new MessageParser.Cur("TRY", "ليرة تركية"));
  static MessageParser.Result t(String title, String msg, String person, String amount, String cur) { return t(title, msg, person, amount, cur, CURS, null, null); }
  static MessageParser.Result t(String title, String msg, String person, String amount, String cur, List<MessageParser.Cur> curs, String phone, String city) {
    n++;
    MessageParser.Result r = MessageParser.parse(msg, curs);
    List<String> bad = new ArrayList<>();
    if (person != null && !person.equals(r.person)) bad.add("person=[" + r.person + "] want [" + person + "]");
    if (amount != null && !amount.equals(r.amount)) bad.add("amount=[" + r.amount + "] want [" + amount + "]");
    if (!Objects.equals(cur, r.currency)) bad.add("currency=[" + r.currency + "] want [" + cur + "]");
    if (phone != null && !phone.equals(r.phone)) bad.add("phone=[" + r.phone + "] want [" + phone + "]");
    if (city != null && !city.equals(r.city)) bad.add("city=[" + r.city + "] want [" + city + "]");
    System.out.println((bad.isEmpty() ? "  PASS " : "  FAIL ") + title + (bad.isEmpty() ? "" : "  -> " + bad));
    if (!bad.isEmpty()) fails++;
    return r;
  }
  public static void main(String[] a) {
    MessageParser.Result r = t("1) the user's example", "السلام عليكم ورحمة الله وبركاته \nيرجى تسليم \nحمدي احمد محمد \nحماة \n+963 933 974 \n500 دولار صافي \nزكاتكم",
        "حمدي احمد محمد", "500", "USD", CURS, "+963 933 974", "حماة");
    check(r.net, "   صافي detected");
    t("2) amount first, name last", "500$\nحماة\n0933 974 123\nحمدي احمد محمد", "حمدي احمد محمد", "500", "USD", CURS, "0933 974 123", "حماة");
    t("3) all inline", "يرجى تسليم حمدي احمد محمد مبلغ 500 دولار في حماة", "حمدي احمد محمد", "500", "USD");
    t("4) labelled fields", "الاسم: سامر خليل\nالمبلغ: 2,000 يورو\nالهاتف: 00963933111222\nالمدينة: حلب", "سامر خليل", "2000", "EUR", CURS, "00963933111222", "حلب");
    t("5) Arabic digits", "تسليم\nمحمد علي حسن\n١٥٠٠ دولار\n٠٩٣٣٩٧٤١٢٣", "محمد علي حسن", "1500", "USD", CURS, "0933974123", null);
    t("6) million, new Syrian pound", "يرجى تسليم 5 مليون سوري جديد\nللسيد أحمد يوسف\nدمشق", "أحمد يوسف", "5000000", "SYP");
    t("7) old Syrian pound by name", "تسليم 750 الف ليرة سورية قديم\nخالد العلي محمد", "خالد العلي محمد", "750000", "SYO");
    t("8) tatweel, greeting, 'حوالة باسم'", "الســـلام عليكـم\nحوالة باسم: خالد عمر\n300 تركي\nاسطنبول", "خالد عمر", "300", "TRY", CURS, null, "اسطنبول");
    MessageParser.Result s = t("9) sender and receiver", "المرسل: أبو محمد\nالمستلم: ياسر حسن\n$750", "ياسر حسن", "750", "USD");
    check(s.sender.equals("أبو محمد"), "   sender = أبو محمد");
    MessageParser.Result f = t("10) fee line is not the amount", "يرجى تسليم 1000 دولار\nالاجرة 10 دولار\nمحمود سعيد الأحمد", "محمود سعيد الأحمد", "1000", "USD");
    check(f.fee.equals("10"), "   fee = 10");
    t("11) no currency word", "تسليم\nعلي محمود\n2500", "علي محمود", "2500", null);
    List<MessageParser.Cur> office = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي"), new MessageParser.Cur("SYP", "ليرة سورية"));
    MessageParser.Result d = t("12) currency not enabled for the office", "يرجى تسليم\nفاطمة الزهراء علي\n200 درهم", "فاطمة الزهراء علي", "200", null, office, null, null);
    check(d.currencyWord.equals("درهم"), "   remembers the word «درهم» to warn");
    t("13) thousands with dot", "1.500 دولار\nتسليم الى سعيد ناصر", null, "1500", "USD");
    t("14) decimals", "تسليم 1,250.50 يورو\nرامي فادي حداد", "رامي فادي حداد", "1250.5", "EUR");
    t("15) phone and amount on one line", "حمدي احمد محمد\n0933974123 - 500 دولار", "حمدي احمد محمد", "500", "USD");
    t("16) two-word city is not a name", "دير الزور\nيرجى تسليم\nمحمود حسين\n300 دولار", "محمود حسين", "300", "USD", CURS, null, "دير الزور");
    t("17) blessing line is not a name", "الله يسلمكم\nعمر فاروق\n100 دولار", "عمر فاروق", "100", "USD");
    t("18) عبد الله stays a name", "تسليم\nعبد الله محمد\n600$", "عبد الله محمد", "600", "USD");
    t("19) euro symbol before number", "€ 250\nلينا سمير", "لينا سمير", "250", "EUR");
    t("20) 'الف' after number, currency elsewhere", "المبلغ 25 الف\nالعملة دولار\nالسيد مازن عيسى", "مازن عيسى", "25000", "USD");
    t("21) name then phone same line", "ياسين عمر 0944123456\nتسليم 400 دولار", "ياسين عمر", "400", "USD");
    // ---- 10.5: «الف» بعد رقم مكتوب كاملًا (اصطلاح المكاتب)، والأرقام العربية والفواصل، والعملات ذات المنازل الثلاث
    t("10.5-a) 50 ألف = 50,000", "تسليم 50 ألف دولار\nسامر علي", "سامر علي", "50000", "USD");
    MessageParser.Result q1 = t("10.5-b) 50000 الف = 50,000 لا 50 مليونًا", "تسليم 50000 الف دولار\nسامر علي", "سامر علي", "50000", "USD");
    check(q1.amountNote.contains("توضيح") && q1.amountText.equals("50000 الف"), "   note explains «الف» is not a multiplier [" + q1.amountNote + "]");
    t("10.5-c) 50000 ألف بالهمزة", "50000 ألف ليرة تركية\nأحمد سعيد", "أحمد سعيد", "50000", "TRY");
    t("10.5-d) خمسين ألف بالحروف", "يرجى تسليم خمسين ألف دولار\nمراد حسين", "مراد حسين", "50000", "USD");
    t("10.5-e) أرقام عربية ٥٠ الف", "تسليم ٥٠ الف دولار\nعلي حسن", "علي حسن", "50000", "USD");
    t("10.5-f) أرقام عربية ٥٠٠٠٠ الف", "تسليم ٥٠٠٠٠ الف دولار\nعلي حسن", "علي حسن", "50000", "USD");
    t("10.5-g) فواصل 50,000 الف", "تسليم 50,000 الف دولار\nعلي حسن", "علي حسن", "50000", "USD");
    MessageParser.Result q2 = t("10.5-h) 1500 الف ملتبس: يُفهم 1,500 ويُطلب التأكيد", "تسليم 1500 الف ليرة تركية\nعلي حسن", "علي حسن", "1500", "TRY");
    check(q2.amountNote.contains("1,500,000"), "   note offers the other reading [" + q2.amountNote + "]");
    t("10.5-i) 750 الف ما زالت 750,000", "تسليم 750 الف ليرة تركية\nعلي حسن", "علي حسن", "750000", "TRY");
    t("10.5-j) 2.5 مليون", "تسليم 2.5 مليون ليرة تركية\nعلي حسن", "علي حسن", "2500000", "TRY");
    t("10.5-k) مبالغ سليمة لا تتغير: 1,500 دولار", "تسليم 1,500 دولار\nعلي حسن", "علي حسن", "1500", "USD");
    t("10.5-l) مبالغ سليمة: 12.5 دولار", "تسليم 12.5 دولار\nعلي حسن", "علي حسن", "12.5", "USD");
    List<MessageParser.Cur> kw = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي", 2), new MessageParser.Cur("KWD", "دينار كويتي", 3));
    MessageParser.Result q3 = t("10.5-m) 1.250 دينار كويتي ملتبس: لا يُخمَّن", "تسليم 1.250 دينار كويتي\nعلي حسن", "علي حسن", "", "KWD", kw, null, null);
    check(q3.amountNote.contains("ملتبس"), "   ambiguous 3-decimal amount explained [" + q3.amountNote + "]");
    t("10.5-n) 1250 دينار كويتي واضح", "تسليم 1250 دينار كويتي\nعلي حسن", "علي حسن", "1250", "KWD", kw, null, null);
    t("10.5-o) 1.250 دولار بمنزلتين = 1250 كما كان", "تسليم 1.250 دولار\nعلي حسن", "علي حسن", "1250", "USD", kw, null, null);
    t("24) 'لـ' before the name", "مرحبا اخي\nبدنا نسلم 300 دولار\nلـ أحمد الخطيب\nحمص", "أحمد الخطيب", "300", "USD", CURS, null, "حمص");
    t("25) English message", "Please deliver\nAhmad Ali\n200 USD", "Ahmad Ali", "200", "USD");
    t("26) two amounts: first with its currency wins", "يرجى تسليم 200 دولار\nما يعادل 3,000,000 ليرة سورية\nحسن رضا", "حسن رضا", "200", "USD");
    t("27) labelled with Arabic digits", "المبلغ: ٣٠٠٠ دولار أمريكي\nاسم المستلم: نور الهدى", "نور الهدى", "3000", "USD");
    t("28) labelled phone with spaces", "رقم الهاتف: 0933 123 456\nتسليم 150 يورو\nسمير سليمان", "سمير سليمان", "150", "EUR", CURS, "0933 123 456", null);
    t("29) name in one word after delivery line is ignored, labelled wins", "يرجى التسليم\nالسيد: عماد\n90 دولار", "عماد", "90", "USD");
    t("30) WhatsApp bold stars", "*يرجى تسليم*\n*حمدي احمد محمد*\n*500 دولار*", "حمدي احمد محمد", "500", "USD");
    // ---- 6.1: كلمات المستخدم للعملات، USDT، الكلمة المجهولة
    List<MessageParser.Cur> withT = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي"), new MessageParser.Cur("USDT", "دولار تيثر"), new MessageParser.Cur("EUR", "يورو"));
    MessageParser.Result u = t("31) unknown slang word is reported", "يرجى تسليم\nحمدي احمد محمد\n500 يوز", "حمدي احمد محمد", "500", null, withT, null, null);
    check(u.unknownWord.equals("يوز"), "   unknownWord = يوز (got [" + u.unknownWord + "])");
    Map<String, List<String>> al = MessageParser.aliasesFrom("USDT=يوز|يوزات");
    MessageParser.Result u2 = MessageParser.parse("يرجى تسليم\nحمدي احمد محمد\n500 يوز", withT, al);
    check("USDT".equals(u2.currency) && u2.unknownWord.isEmpty(), "   with alias يوز -> USDT (got " + u2.currency + ")");
    check("USD".equals(MessageParser.parse("تسليم 500 دولار\nسامر علي", withT, al).currency), "   «دولار» stays USD even when USDT is named «دولار تيثر»");
    check("USDT".equals(MessageParser.parse("تسليم 500 usdt\nسامر علي", withT, al).currency), "   usdt built-in");
    check("USDT".equals(MessageParser.parse("تسليم 1000 تيثر\nسامر علي", withT, null).currency), "   تيثر built-in");
    MessageParser.Result u3 = MessageParser.parse("تسليم 500 يوز\nسامر علي", CURS, al);
    check(u3.currency == null && u3.currencyWord.equals("يوز"), "   alias of a currency not enabled for the office -> warn with the word");
    check(MessageParser.parse("تسليم 500 دولار\nسامر علي", CURS).unknownWord.isEmpty(), "   no unknownWord when currency known");
    check(MessageParser.parse("يرجى تسليم 2500 صافي\nسامر علي", CURS).unknownWord.isEmpty(), "   «صافي» is not an unknown currency");
    check(MessageParser.parse("تسليم 25 الف يوز\nسامر علي", withT, null).unknownWord.equals("يوز"), "   unknown word after «الف»");
    check(MessageParser.aliasesTo(al).equals("USDT=يوز|يوزات"), "   aliases round-trip");
    List<MessageParser.Cur> allc = new ArrayList<>(withT);
    check(MessageParser.aliasProblem("يوز", "USDT", new LinkedHashMap<String, List<String>>(), allc) == null, "   «يوز» accepted for USDT");
    check(MessageParser.aliasProblem("يوز", "EUR", al, allc) != null, "   «يوز» refused for EUR (already USDT)");
    check(MessageParser.aliasProblem("دولار", "USDT", al, allc) != null, "   «دولار» refused (built-in USD)");
    check(MessageParser.aliasProblem("من", "USDT", al, allc) != null, "   «من» refused (too common)");
    check(MessageParser.aliasProblem("12", "USDT", al, allc) != null, "   digits refused");
    check(MessageParser.aliasProblem("يورو", "USDT", al, allc) != null, "   another currency's name refused");
    // ---- 6.1: البيان المختصر
    String c1 = MessageParser.compact("السلام عليكم ورحمة الله وبركاته \nيرجى تسليم \nحمدي احمد محمد \nحماة \n+963 933 974 \n500 دولار صافي \nزكاتكم", "حمدي احمد محمد");
    check(c1.equals("حماة • +963 933 974 • 500 دولار صافي"), "   compact multi-line -> [" + c1 + "]");
    String c2 = MessageParser.compact("السلام عليكم ورحمة الله وبركاته يرجى تسليم حمدي احمد محمد حماة +963 933 974 500 دولار صافي زكاتكم", "حمدي احمد محمد");
    check(c2.equals("حماة +963 933 974 500 دولار صافي"), "   compact one line -> [" + c2 + "]");
    String c3 = MessageParser.compact("السلام عليكم\nيرجى تسليم\nحمدي احمد محمد\n500 دولار", null);
    check(c3.equals("حمدي احمد محمد • 500 دولار"), "   name kept when not shown elsewhere -> [" + c3 + "]");
    check(MessageParser.compact("تسليم نقدي باليد", "سامر").equals("تسليم نقدي باليد"), "   short manual text unchanged");
    String c4 = MessageParser.compact("الاسم: سامر خليل\nالمبلغ: 2,000.50 يورو\nالهاتف: 0933 111 222\nعاجل 🌹🌹", "سامر خليل");
    check(c4.equals("المبلغ: 2,000.50 يورو • الهاتف: 0933 111 222 • عاجل"), "   labels, decimals and «عاجل» kept, emoji dropped -> [" + c4 + "]");
    check(MessageParser.compact("السلام عليكم\nشكرا", "x").isEmpty(), "   only pleasantries -> empty");
    StringBuilder longMsg = new StringBuilder("يرجى تسليم\n");
    for (int i = 0; i < 40; i++) longMsg.append("كلمة").append(i).append(" ");
    String c5 = MessageParser.compact(longMsg.toString(), null);
    check(c5.length() <= MessageParser.COMPACT_MAX + 1 && c5.endsWith("…"), "   long text cut with … (" + c5.length() + ")");
    t("22) empty message", "", "", "", null);
    t("23) only greeting", "السلام عليكم ورحمة الله", "", "", null);
    // «راجع» بلغة المكاتب = أجرة يدفعها المكتب لنا
    MessageParser.Result k1 = t("R1) راجع 1%", "حوالة 10000 دولار\nسامر خليل\nراجع 1%", "سامر خليل", "10000", "USD");
    check(k1.raje && "1".equals(k1.rajeRate) && k1.rajePer == 100, "   راجع 1 بالمئة لنا");
    MessageParser.Result k2 = t("R2) راجع ١ بالميه في نفس السطر", "تسليم 5000 يورو راجع ١ بالميه\nخالد عمر", "خالد عمر", "5000", "EUR");
    check("1".equals(k2.rajeRate) && k2.rajePer == 100, "   ١ بالميه = 1%");
    MessageParser.Result k3 = t("R3) راجع نص بالمية", "5000 يورو راجع نص بالمية\nمحمد علي", "محمد علي", "5000", "EUR");
    check("0.5".equals(k3.rajeRate), "   نص بالمية = 0.5%");
    MessageParser.Result k4 = t("R4) راجع 5 بالالف", "20 الف دولار\nراجع 5 بالالف\nياسر حسن", "ياسر حسن", "20000", "USD");
    check("5".equals(k4.rajeRate) && k4.rajePer == 1000, "   5 بالألف");
    MessageParser.Result k5 = t("R5) راجع مبلغ محدد", "2000 دولار\nراجع 20 دولار\nعلي محمود", "علي محمود", "2000", "USD");
    check("20".equals(k5.rajeAmount) && k5.rajeRate.isEmpty(), "   راجع 20 دولار = أجرة 20");
    MessageParser.Result k6 = t("R6) رقم بلا وحدة لا يُخمَّن ولا يصير المبلغ", "1500$\nراجع 2\nسعيد ناصر", "سعيد ناصر", "1500", "USD");
    check(k6.raje && k6.rajeRate.isEmpty() && k6.rajeAmount.isEmpty() && "2".equals(k6.rajeHint), "   راجع 2: يُعرض للمحاسب ليحدده");
    MessageParser.Result k7 = t("R7) «راجعني» ليست راجع", "حوالة 3000 يورو\nراجعني بكرة\nخالد عمر", null, "3000", "EUR");
    check(!k7.raje, "   راجعني ≠ راجع");
    System.out.println(fails == 0 ? "ALL PASS (" + n + " messages)" : fails + " FAILED of " + n);
    System.exit(fails == 0 ? 0 : 1);
  }
  static void check(boolean c, String m) { System.out.println((c ? "  PASS" : "  FAIL") + m); if (!c) fails++; }
}
