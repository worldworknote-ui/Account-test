import com.smart.hawala.*;
import java.util.*;
/** 6.2: رسائل بلغات متعددة ومبالغ مكتوبة بالحروف. */
public class MultiLangTest {
  static int fails = 0, n = 0;
  static List<MessageParser.Cur> CURS = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي"), new MessageParser.Cur("EUR", "يورو"),
      new MessageParser.Cur("TRY", "ليرة تركية"), new MessageParser.Cur("SYP", "ليرة سورية"), new MessageParser.Cur("RON", "ليو روماني"),
      new MessageParser.Cur("GBP", "جنيه استرليني"));
  /** عملات المكتب في لقطة الشاشة: بلا RON. */
  static List<MessageParser.Cur> SHOT = Arrays.asList(new MessageParser.Cur("EUR", "يورو"), new MessageParser.Cur("SILVER", "فضة"),
      new MessageParser.Cur("TRY", "ليرة تركية"), new MessageParser.Cur("USD", "دولار امريكي"));
  static MessageParser.Result t(String title, String msg, String person, String amount, String cur, String city) { return t(title, msg, person, amount, cur, city, CURS); }
  static MessageParser.Result t(String title, String msg, String person, String amount, String cur, String city, List<MessageParser.Cur> curs) {
    n++;
    MessageParser.Result r = MessageParser.parse(msg, curs);
    List<String> bad = new ArrayList<>();
    if (person != null && !person.equals(r.person)) bad.add("person=[" + r.person + "] want [" + person + "]");
    if (amount != null && !amount.equals(r.amount)) bad.add("amount=[" + r.amount + "] want [" + amount + "]");
    if (!Objects.equals(cur, r.currency)) bad.add("currency=[" + r.currency + "] want [" + cur + "]");
    if (city != null && !city.equals(r.city)) bad.add("city=[" + r.city + "] want [" + city + "]");
    System.out.println((bad.isEmpty() ? "  PASS " : "  FAIL ") + title + (bad.isEmpty() ? "" : "  -> " + bad));
    if (!bad.isEmpty()) fails++;
    return r;
  }
  static void check(boolean c, String m) { System.out.println((c ? "  PASS" : "  FAIL") + m); if (!c) fails++; }
  public static void main(String[] a) {
    MessageParser.Result s = t("01) the screenshot: RON not enabled", "need five hundred ron to syria for mr tony", "Tony", "500", null, "Syria", SHOT);
    check(s.currencyWord.equals("ron"), "   warns «ron» is not enabled (got [" + s.currencyWord + "])");
    t("02) same with RON enabled", "need five hundred ron to syria for mr tony", "Tony", "500", "RON", "Syria");
    t("03) digits + ron", "need 500 ron to syria for mr tony", "Tony", "500", "RON", "Syria");
    MessageParser.Result tr = t("04) Turkish için + bin", "Merhaba, Ahmet Yılmaz için 2 bin dolar teslim edin. Tel: 0532 123 45 67 Gaziantep", "Ahmet Yılmaz", "2000", "USD", "Gaziantep");
    check(tr.phone.equals("0532 123 45 67"), "   phone (got [" + tr.phone + "])");
    t("05) Turkish words + dative", "Lütfen beş yüz euro Mehmet Kaya'ya teslim edin", "Mehmet Kaya", "500", "EUR", null);
    t("06) Turkish buçuk milyon", "iki buçuk milyon lira\nAli Demir", "Ali Demir", "2500000", "TRY", null);
    t("07) Turkish joined beşyüz", "beşyüz dolar\nAli Demir", "Ali Demir", "500", "USD", null);
    t("08) Turkish on bin", "on bin dolar\nAli Demir", "Ali Demir", "10000", "USD", null);
    t("09) Turkish Bey'e", "Ahmet Bey'e 300 euro", "Ahmet", "300", "EUR", null);
    t("10) Turkish labels", "Isim: Fatma Kaya\nTutar: 750 TL", "Fatma Kaya", "750", "TRY", null);
    t("11) Romanian", "Bună ziua, vă rog să predați cinci sute de euro domnului Ion Popescu din Alep", "Ion Popescu", "500", "EUR", "Alep");
    t("12) Romanian mii lei pentru", "două mii lei pentru Andrei Ionescu", "Andrei Ionescu", "2000", "RON", null);
    t("13) English words + Mr.", "Please pay two thousand five hundred dollars to Mr. John Smith, London", "John Smith", "2500", "USD", "London");
    t("14) English a hundred and fifty", "a hundred and fifty euros for Lina", "Lina", "150", "EUR", null);
    t("15) French", "Bonjour, merci de remettre deux mille euros à Monsieur Karim Haddad, Paris", "Karim Haddad", "2000", "EUR", "Paris");
    t("16) French quatre-vingt", "quatre-vingt-dix euros pour Sami", "Sami", "90", "EUR", null);
    t("17) German joined number", "Bitte fünfhundertzwanzig Euro an Herrn Ali Hassan auszahlen", "Ali Hassan", "520", "EUR", null);
    t("18) Spanish", "Por favor entregar dos mil quinientos dólares al señor Omar Khaled", "Omar Khaled", "2500", "USD", null);
    t("19) Spanish ciento cincuenta", "ciento cincuenta euros para Laura Gomez", "Laura Gomez", "150", "EUR", null);
    t("20) Russian labels + words", "Получатель: Ахмед Али\nСумма: пятьсот долларов", "Ахмед Али", "500", "USD", null);
    t("21) Russian digits + тысячи", "3 тысячи долларов\nОмар Халед", "Омар Халед", "3000", "USD", null);
    t("22) Arabic خمسمية + للسيد", "يرجى تسليم خمسمية دولار للسيد احمد علي", "احمد علي", "500", "USD", null);
    t("23) Arabic الفين ونص", "الفين ونص يورو\nتسليم محمد خالد", "محمد خالد", "2500", "EUR", null);
    t("24) Arabic مية وخمسين الف", "مية وخمسين الف سوري\nسامر علي", "سامر علي", "150000", "SYP", null);
    t("25) Arabic مليون ونص", "مليون ونص ليرة تركية\nسامر علي", "سامر علي", "1500000", "TRY", null);
    t("26) Arabic تلتمية", "تلتمية دولار\nسامر علي", "سامر علي", "300", "USD", null);
    t("27) 'bin' inside a name is not 1000", "Ahmad bin Ali\n500 USD", "Ahmad bin Ali", "500", "USD", null);
    t("28) 'on' is not 10 in English", "send on monday 300 euro to Sami Nasser", "Sami Nasser", "300", "EUR", null);
    t("29) Salam is a name", "Mr Salam Hamdan\n1000 USD", "Salam Hamdan", "1000", "USD", null);
    t("30) words without currency", "need five hundred\nAli Demir", "Ali Demir", "500", null, null);
    t("31) English sender and receiver", "from Khaled Omar to Rami Saleh 700 GBP", "Rami Saleh", "700", "GBP", null);
    MessageParser.Result sr = MessageParser.parse("from Khaled Omar to Rami Saleh 700 GBP", CURS);
    check(sr.sender.equals("Khaled Omar"), "   sender = Khaled Omar (got [" + sr.sender + "])");
    t("32) syrian pounds beat pounds", "250000 syrian pounds\nMaher Daoud", "Maher Daoud", "250000", "SYP", null);
    t("34) comma ends the name", "Hi bro, give 750$ to Yusuf, he will come at 5 pm", "Yusuf", "750", "USD", null);
    t("35) Arabic لأخوي + بحلب", "مرحبا اخي كيفك، بدي ابعت خمسمية دولار لأخوي محمد بحلب", "محمد", "500", "USD", "حلب");
    t("36) Romanian către", "Va rog sa platiti 1.000 lei catre Mihai Stan", "Mihai Stan", "1000", "RON", null);
    t("37) Turkish alıcı + Halep'e", "Merhaba abi, Halep'e 1500 dolar, alıcı Mustafa Öztürk 0555 444 33 22", "Mustafa Öztürk", "1500", "USD", "Halep");
    t("38) English phone + city", "please send 1,200 euros to Ms. Sara Ali in Homs, phone +963 944 111 222", "Sara Ali", "1200", "EUR", "Homs");
    t("39) attached لـ after currency", "تسليم 300 يورو لسامر علي", "سامر علي", "300", "EUR", null);
    t("40) attached لـ after words amount", "ابعت خمسمية دولار لأحمد", "أحمد", "500", "USD", null);
    t("41) لنا is not a name", "500 دولار لنا\nسامر علي", "سامر علي", "500", "USD", null);
    t("33) Arabic still fine: user example", "السلام عليكم ورحمة الله وبركاته \nيرجى تسليم \nحمدي احمد محمد \nحماة \n+963 933 974 \n500 دولار صافي \nزكاتكم", "حمدي احمد محمد", "500", "USD", "حماة");
    check(MessageParser.parse("500 USD net\nAli Demir", CURS).net, "   «net» = صافي");
    check(MessageParser.parse("Ali Demir\n500 USD\ncommission 10 USD", CURS).fee.equals("10"), "   English fee line");
    // aliases with a user currency named like a built-in one
    check(MessageParser.aliasProblem("فضة", "SILVER", new LinkedHashMap<String, List<String>>(), SHOT) == null, "   «فضة» may be linked to SILVER when XAG is not defined");
    // compact with Latin greetings
    String c = MessageParser.compact("Hello,\nplease deliver 500 USD\nto Mr John Smith\nLondon\nThanks", "John Smith");
    check(c.equals("500 USD • London"), "   compact English -> [" + c + "]");
    String c2 = MessageParser.compact("Merhaba\nAhmet Yılmaz için 2 bin dolar\nTeşekkürler", "Ahmet Yılmaz");
    check(c2.equals("2 bin dolar"), "   compact Turkish drops the name and için -> [" + c2 + "]");
    System.out.println(fails == 0 ? "ALL PASS (" + n + " messages)" : fails + " FAILED of " + n);
    System.exit(fails == 0 ? 0 : 1);
  }
}
