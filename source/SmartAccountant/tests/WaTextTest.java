import com.smart.hawala.*;
import java.util.*;
/** 7.0: تقسيم رسائل واتساب المشارَكة أو المنسوخة معًا. */
public class WaTextTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  public static void main(String[] a) {
    List<WaText.Msg> l = WaText.split("[13/10/26, 1:48 AM] Ahmad: يرجى تسليم\nحمدي احمد محمد\n500 دولار\n[13/10/26, 1:50 AM] Ahmad: تسليم 300 يورو لسامر");
    check(l.size() == 2, "Android copy: 2 messages (got " + l.size() + ")");
    check(l.get(0).sender.equals("Ahmad") && l.get(0).time.equals("13/10/26, 1:48 AM"), "sender and time");
    check(l.get(0).body.equals("يرجى تسليم\nحمدي احمد محمد\n500 دولار"), "multi-line body kept");
    check(l.get(1).body.equals("تسليم 300 يورو لسامر"), "second body");

    l = WaText.split("[١٣\u200f/١٠\u200f/٢٠٢٦ ١:٤٨ ص] أحمد: 500 دولار لحمدي\n[١٣\u200f/١٠\u200f/٢٠٢٦ ١:٥٠ ص] أحمد: 200 يورو");
    check(l.size() == 2 && l.get(0).sender.equals("أحمد") && l.get(1).body.equals("200 يورو"), "Arabic UI: Arabic digits, RLM marks, ص");

    l = WaText.split("[١٣\u200f/١٠\u200f/٢٠٢٦، ٩:١٥:٣٠ م] سامر: سلم 300 دولار\n[١٣\u200f/١٠\u200f/٢٠٢٦، ٩:١٦:٠١ م] سامر: و100 يورو");
    check(l.size() == 2 && l.get(0).sender.equals("سامر") && l.get(1).body.equals("و100 يورو"), "Arabic comma + seconds + م (iOS Arabic)");

    l = WaText.split("[13.10.26, 01:48:12] Ahmad: 500$ to Omar\n[13.10.26, 01:49:30] Ahmad: 250 EUR for Sami");
    check(l.size() == 2 && l.get(1).body.equals("250 EUR for Sami"), "iOS copy format");

    l = WaText.split("13/10/2026, 01:48 - Ahmad: 500$ to Omar\n13/10/2026, 01:49 - Messages and calls are end-to-end encrypted.\n13/10/2026, 01:50 - Sami: 200 EUR");
    check(l.size() == 2 && l.get(0).body.equals("500$ to Omar") && l.get(1).sender.equals("Sami"), "exported chat format, system line ignored");

    l = WaText.split("[13/10/26, 1:48 AM] Ahmad: <Media omitted>\n[13/10/26, 1:49 AM] Ahmad: 500 دولار لعمر\n[13/10/26, 1:50 AM] Ahmad: This message was deleted");
    check(l.size() == 1 && l.get(0).body.equals("500 دولار لعمر"), "media and deleted messages dropped");

    l = WaText.split("[13/10/26, 1:48 AM] Ahmad: 500 دولار لعمر <This message was edited>");
    check(l.size() == 1 && l.get(0).body.equals("500 دولار لعمر"), "edited marker removed");

    l = WaText.split("السلام عليكم\nيرجى تسليم\nحمدي احمد محمد\n500 دولار صافي");
    check(l.size() == 1 && l.get(0).sender.isEmpty() && l.get(0).body.startsWith("السلام عليكم") && l.get(0).body.endsWith("صافي"), "single message without header stays whole");

    l = WaText.split("[13/10/26, 1:48 AM] Abu Omar 🌹: 700 USD for Rami Saleh");
    check(l.size() == 1 && l.get(0).sender.equals("Abu Omar 🌹"), "sender with emoji");

    l = WaText.split("   ");
    check(l.isEmpty(), "blank text -> nothing");

    check(WaText.fingerprint("يرجى تسليم\nحمدي احمد\n500 دولار").equals(WaText.fingerprint("يُرجى  تسليم حمدي أحمد،  500 دولار")), "fingerprint ignores spacing, punctuation, diacritics, alef forms");
    check(!WaText.fingerprint("500 دولار لعمر").equals(WaText.fingerprint("600 دولار لعمر")), "fingerprint differs on amount");
    // the split bodies parse like any pasted message
    List<MessageParser.Cur> curs = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي"), new MessageParser.Cur("EUR", "يورو"));
    l = WaText.split("[13/10/26, 1:48 AM] Ahmad: يرجى تسليم\nحمدي احمد محمد\n500 دولار\n[13/10/26, 1:50 AM] Ahmad: تسليم 300 يورو لسامر علي");
    MessageParser.Result r0 = MessageParser.parse(l.get(0).body, curs), r1 = MessageParser.parse(l.get(1).body, curs);
    check(r0.person.equals("حمدي احمد محمد") && r0.amount.equals("500") && "USD".equals(r0.currency), "message 1 parses");
    check(r1.amount.equals("300") && "EUR".equals(r1.currency), "message 2 parses (person=[" + r1.person + "])");

    // 7.1: نسخ عدة رسائل من واتساب أندرويد في السنة الحالية: «[07/10, 20:39]» بلا سنة (مثال حقيقي من المستخدم)
    List<MessageParser.Cur> curs4 = Arrays.asList(new MessageParser.Cur("USD", "دولار امريكي"), new MessageParser.Cur("EUR", "يورو"),
        new MessageParser.Cur("TRY", "ليرة تركية"), new MessageParser.Cur("SYP", "ليرة سورية"));
    String real = "[07/10, 20:39] عمراوبار: يرجى دفع \n\n550 دولار صافي \n\nالشام \n\nمحمد راشد علي العبدالله\n\n+963 883 905\n"
        + "[10/10, 17:27] عمراوبار: يرجى دفع \n\nحسن حسن حسن\n\n\u200f+9947852028\u200f\n\n50 . دولار. صافي \n\nدمشق\n"
        + "[11/10, 20:13] Thana.Horani: لكم 5000\nدولار\nمن عبد العزيز\n"
        + "[12/10, 14:12] OA: السلام عليكم \nيرجى التسليم \n2000 دولار صافي\nمحمد بسام عبد الرحيم\n\n  مساكن برزة";
    l = WaText.split(real);
    check(l.size() == 4, "year-less headers [07/10, 20:39]: 4 messages (got " + l.size() + ")");
    if (l.size() == 4) {
      check(l.get(0).sender.equals("عمراوبار") && l.get(0).time.equals("07/10, 20:39"), "sender/time of message 1");
      check(l.get(2).sender.equals("Thana.Horani") && l.get(3).sender.equals("OA"), "senders of messages 3 and 4");
      check(!l.get(0).body.contains("[10/10") && l.get(0).body.endsWith("+963 883 905"), "message 1 body stops before next header");
      MessageParser.Result p1 = MessageParser.parse(l.get(0).body, curs4), p2 = MessageParser.parse(l.get(1).body, curs4),
          p3 = MessageParser.parse(l.get(2).body, curs4), p4 = MessageParser.parse(l.get(3).body, curs4);
      check(p1.amount.equals("550") && "USD".equals(p1.currency) && p1.person.equals("محمد راشد علي العبدالله") && p1.net, "msg1: 550 USD net, person [" + p1.person + "]");
      check(p1.city.equals("الشام"), "msg1: city [" + p1.city + "]");
      check(p2.amount.equals("50") && "USD".equals(p2.currency) && p2.person.equals("حسن حسن حسن") && p2.city.equals("دمشق"), "msg2: «50 . دولار.» -> 50 USD, person [" + p2.person + "]");
      check(p3.amount.equals("5000") && "USD".equals(p3.currency) && p3.sender.equals("عبد العزيز") && p3.person.isEmpty(), "msg3: 5000 USD from [" + p3.sender + "] person [" + p3.person + "]");
      check(p4.amount.equals("2000") && "USD".equals(p4.currency) && p4.person.equals("محمد بسام عبد الرحيم") && p4.net, "msg4: 2000 USD net, person [" + p4.person + "] (greeting skipped)");
    }
    l = WaText.split("[07/10, 8:39 PM] Ahmad: 500$ to Omar Ali\n[07/10, 8:40 PM] Ahmad: 200 EUR");
    check(l.size() == 2, "year-less header with AM/PM");
    l = WaText.split("[٠٧/١٠، ٢٠:٣٩] أحمد: 500 دولار\n[٠٧/١٠، ٢٠:٤٠] أحمد: 200 يورو");
    check(l.size() == 2 && l.get(1).body.equals("200 يورو"), "year-less header in Arabic digits and comma");
    l = WaText.split("تسليم 500 دولار لأحمد علي\nالموعد 07/10 الساعة 20:39");
    check(l.size() == 1, "date and time inside a single message do not split it");

    // «من» العربية للمرسل: فقط إن تبعها اسم من كلمتين ليس مدينة
    MessageParser.Result q = MessageParser.parse("لكم 5000 دولار من عبد العزيز", curs4);
    check(q.sender.equals("عبد العزيز") && q.amount.equals("5000"), "one line: sender [" + q.sender + "]");
    q = MessageParser.parse("من فضلك تسليم 500 دولار لأحمد علي", curs4);
    check(q.sender.isEmpty() && q.person.equals("أحمد علي"), "«من فضلك» is not a sender (person [" + q.person + "])");
    q = MessageParser.parse("500 دولار من حلب الى محمد خالد", curs4);
    check(q.sender.isEmpty() && q.person.equals("محمد خالد"), "«من حلب» is a city not a sender (sender [" + q.sender + "])");
    q = MessageParser.parse("تسليم 300 دولار لسامر علي من عند أبو أحمد الخطيب", curs4);
    check(q.sender.equals("أبو أحمد الخطيب") && q.person.equals("سامر علي"), "«من عند» + name: sender [" + q.sender + "] person [" + q.person + "]");
    q = MessageParser.parse("لكم 200 دولار من خالد", curs4);
    check(q.sender.isEmpty(), "single word after «من» is not taken (description keeps it)");

    System.out.println(fails == 0 ? "ALL WA TESTS PASSED" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
