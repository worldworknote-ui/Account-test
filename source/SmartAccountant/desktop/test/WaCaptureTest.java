import com.smart.hawala.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 12.1: نقل رسائل واتساب من ويندوز وماك: HTML الحافظة، أسطر الوقت، القص اليدوي، إخفاء المحتوى في «فحص الحافظة»،
 * وتجميع الفقاعات واسم الغروب في «إطار الالتقاط». كل النصوص مصطنعة (لا بيانات حقيقية).
 */
public class WaCaptureTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    public static void main(String[] args) {
        System.out.println("== HTML واتساب ويب (data-pre-plain-text)");
        String html = "Version:0.9\r\nStartHTML:00000097\r\n<html><body><!--StartFragment-->"
                + "<div class=\"_amk4 message-in\"><div class=\"copyable-text\" data-pre-plain-text=\"[10:32, 17/11/2026] مكتب التجربة: \">"
                + "<span class=\"selectable-text copyable-text\"><span>تجربة 100 دولار لأحمد</span></span></div><span>10:32</span></div>"
                + "<div class=\"_amk4 message-in\"><div class=\"copyable-text\" data-pre-plain-text=\"[10:33, 17/11/2026] مكتب التجربة: \">"
                + "<span class=\"selectable-text copyable-text\"><span>استلام 50 يورو<br>من سامي &amp; شركاه</span></span></div><span>10:33 م</span></div>"
                + "<!--EndFragment--></body></html>";
        List<WaText.Msg> m = WaHtml.messages(html);
        check(m != null && m.size() == 2, "رسالتان من HTML (" + (m == null ? "null" : m.size()) + ")");
        if (m != null && m.size() == 2) {
            check(m.get(0).body.equals("تجربة 100 دولار لأحمد"), "نص الأولى: " + m.get(0).body);
            check(m.get(0).sender.equals("مكتب التجربة"), "المرسل من data-pre-plain-text");
            check(m.get(1).body.equals("استلام 50 يورو\nمن سامي & شركاه"), "السطر الثاني وفك & : " + m.get(1).body.replace('\n', '|'));
            check(m.get(1).time.contains("10:33"), "وقت الثانية");
        }
        String plain = "تجربة 100 دولار لأحمد\n10:32\nاستلام 50 يورو\nمن سامي & شركاه\n10:33 م";
        List<WaText.Msg> both = WaClip.messages(plain, html);
        check(both.size() == 2 && !both.get(0).sender.isEmpty(), "WaClip يفضّل HTML حين النص العادي بلا رؤوس");

        System.out.println("== HTML بأصناف الفقاعات فقط (بلا data-pre-plain-text)");
        String html2 = "<div class=\"message-out focusable-list-item\"><span dir=\"rtl\">تم 200 دولار</span><div><span>11:05 ص</span><span data-icon=\"msg-dblcheck\"></span></div></div>"
                + "<div class=\"message-in\"><span>واصل <img alt=\"👍\" src=\"x\"></span><span>11:06 ص</span></div>";
        List<WaText.Msg> m2 = WaHtml.messages(html2);
        check(m2 != null && m2.size() == 2, "فقاعتان بالأصناف");
        if (m2 != null && m2.size() == 2) {
            check(m2.get(0).body.equals("تم 200 دولار") && m2.get(0).time.startsWith("11:05"), "نص ووقت الأولى: " + m2.get(0).body + " / " + m2.get(0).time);
            check(m2.get(1).body.equals("واصل 👍"), "الإيموجي من alt: " + m2.get(1).body);
        }
        check(WaHtml.messages("<p>نص عادي من Word</p>") == null, "HTML بلا فقاعات ← null (يُستعمل النص العادي)");
        check(WaHtml.messages(null) == null && WaHtml.messages("") == null, "HTML فارغ ← null");

        System.out.println("== أسطر الوقت في النص العادي (ويندوز)");
        List<WaText.Msg> cut = WaClip.messages(plain, null);
        check(cut.size() == 2, "قطع تلقائي عند أسطر الوقت (" + cut.size() + ")");
        if (cut.size() == 2) {
            check(cut.get(0).body.equals("تجربة 100 دولار لأحمد") && cut.get(0).time.equals("10:32"), "الأولى بلا سطر الوقت");
            check(cut.get(1).time.equals("10:33 م"), "وقت الثانية: " + cut.get(1).time);
        }
        check(WaCuts.timeLine("١٠:٣٢ م") && WaCuts.timeLine("تم التعديل 9:05 PM") && WaCuts.timeLine("10:32 ✓✓"), "أشكال سطر الوقت (أرقام هندية، معدّلة، علامات القراءة)");
        check(!WaCuts.timeLine("الساعة 10:32 عند المكتب") && !WaCuts.timeLine("100 دولار"), "سطر فيه وقت ونص ليس سطر وقت");
        String one = "تجربة 100 دولار لأحمد";
        check(WaClip.messages(one, null).size() == 1, "رسالة واحدة تبقى واحدة");
        String android = "[17/11/26, 10:32 AM] أحمد: 100 دولار\n[17/11/26, 10:33 AM] أحمد: 50 يورو";
        check(WaClip.messages(android, "<b>x</b>").size() == 2, "رؤوس أندرويد ما زالت تعمل ولها الأولوية");

        System.out.println("== القص اليدوي");
        String glued = "حوالة 100 دولار لأحمد\nرقم 55\n\nحوالة 70 يورو لسامي\n\nتم";
        check(WaClip.maybeGlued(glued), "نص بأسطر فارغة يُعرض للقص");
        check(!WaClip.maybeGlued("100 دولار\nلأحمد"), "رسالة قصيرة لا تُعرض للقص");
        List<String> lines = WaCuts.lines(glued);
        TreeSet<Integer> sug = WaCuts.suggest(lines);
        check(sug.equals(new TreeSet<>(Arrays.asList(3, 5))), "مقترحات القص بعد الأسطر الفارغة: " + sug);
        List<WaText.Msg> parts = WaCuts.apply(lines, sug);
        check(parts.size() == 3 && parts.get(0).body.equals("حوالة 100 دولار لأحمد\nرقم 55") && parts.get(2).body.equals("تم"), "تطبيق القص: " + parts.size());
        check(WaCuts.apply(lines, new TreeSet<>()).size() == 1, "بلا قص ← رسالة واحدة");

        System.out.println("== فحص الحافظة (إخفاء المحتوى)");
        String mk = ClipMask.text("أحمد Ali 123 ٤٥ 👍 [10:32]");
        check(mk.equals("عععع xxx ### ## ☺ [##:##]"), "إخفاء النص: " + mk);
        String mh = ClipMask.html("<div class=\"copyable-text\" data-pre-plain-text=\"[10:32, 17/11/2026] أحمد: \" style=\"color:red\"><span>100 دولار</span></div>");
        check(mh.contains("class=\"copyable-text\"") && mh.contains("data-pre-plain-text=\"[##:##, ##/##/####] عععع: \"") && mh.contains("style=\"…\"")
                && mh.contains("<span>### ععععع</span>") && !mh.contains("أحمد") && !mh.contains("100"), "إخفاء HTML مع إبقاء البنية: " + mh);

        System.out.println("== إطار الالتقاط: الفقاعات من إمكانية الوصول");
        String out = "W\t0\t0\t1200\t800\t" + b64("WhatsApp") + "\n"
                + "H\t500\t20\t200\t30\t" + b64("مكتب الأمانة للحوالات") + "\n"
                + "R\t400\t100\t700\t600\t" + b64("قائمة الرسائل كلها") + "\n"
                + "R\t420\t120\t300\t60\t" + b64("اليوم") + "\n"
                + "R\t420\t200\t300\t60\t" + b64("تجربة 100 دولار لأحمد\n10:32") + "\n"
                + "R\t700\t280\t300\t60\t" + b64("استلام 50 يورو10:33 م") + "\n"
                + "R\t420\t900\t300\t60\t" + b64("خارج الإطار 5 دولار") + "\n";
        List<Bubbles.Node> nodes = Bubbles.parse(out);
        check(nodes.size() == 7, "قراءة سطور القارئ (" + nodes.size() + ")");
        Bubbles.Result r = Bubbles.build(nodes, new int[]{380, 80, 760, 640});
        check(r.bubbles.size() == 2, "فقاعتان مرقمتان داخل الإطار، بلا «اليوم» ولا القائمة الأم (" + r.bubbles.size() + ")");
        if (r.bubbles.size() == 2) {
            check(r.bubbles.get(0).number == 1 && r.bubbles.get(0).text.equals("تجربة 100 دولار لأحمد") && r.bubbles.get(0).time.equals("10:32"), "الفقاعة ① ونصها");
            check(r.bubbles.get(1).text.equals("استلام 50 يورو") && r.bubbles.get(1).time.equals("10:33 م"), "الوقت الملصق بآخر النص يُفصل: " + r.bubbles.get(1).text);
        }
        check(r.chat.equals("مكتب الأمانة للحوالات"), "اسم الغروب من الرأس: " + r.chat);
        check(r.window.equals("WhatsApp"), "عنوان النافذة");
        Bubbles.Result all = Bubbles.build(nodes, null);
        check(all.bubbles.size() == 3, "بلا إطار: كل فقاعات النافذة (" + all.bubbles.size() + ")");

        System.out.println("== إطار الالتقاط: أسطر قراءة الصورة (OCR)");
        String ocr = "W\t0\t0\t1000\t700\t" + b64("WhatsApp") + "\n"
                + "L\t300\t10\t200\t20\t" + b64("غروب مكتب النور") + "\n"
                + "L\t600\t100\t300\t20\t" + b64("حوالة 300 دولار") + "\n"
                + "L\t600\t124\t300\t20\t" + b64("للأخ سامر") + "\n"
                + "L\t850\t148\t50\t16\t" + b64("10:40") + "\n"
                + "L\t100\t200\t300\t20\t" + b64("تم الاستلام") + "\n"
                + "L\t600\t260\t300\t20\t" + b64("حوالة 40 يورو") + "\n";
        Bubbles.Result r2 = Bubbles.build(Bubbles.parse(ocr), new int[]{50, 80, 900, 400});
        check(r2.bubbles.size() == 3, "ثلاث فقاعات بالفجوات وتغيّر الجهة (" + r2.bubbles.size() + ")");
        if (r2.bubbles.size() == 3) {
            check(r2.bubbles.get(0).text.equals("حوالة 300 دولار\nللأخ سامر") && r2.bubbles.get(0).time.equals("10:40"), "الأولى سطران ووقتها: " + r2.bubbles.get(0).text.replace('\n', '|'));
            check(r2.bubbles.get(1).text.equals("تم الاستلام"), "الثانية (الجهة الأخرى)");
        }
        check(r2.chat.equals("غروب مكتب النور"), "اسم الغروب من أعلى النافذة: " + r2.chat);

        System.out.println("== اسم الغروب ← المكتب");
        List<String> offices = Arrays.asList("مكتب النور", "الأمانة", "شركة البركة للصرافة");
        check(Bubbles.matchOffice("غروب مكتب النور", offices) == 0, "احتواء الاسم");
        check(Bubbles.matchOffice("مكتب الأمانة للحوالات", offices) == 1, "بعد حذف «مكتب/للحوالات»");
        check(Bubbles.matchOffice("البركه", offices) == 2, "ة/ه موحّدة");
        check(Bubbles.matchOffice("عائلة", offices) == -1 && Bubbles.matchOffice("", offices) == -1, "لا تطابق ← بلا اقتراح");

        System.out.println();
        System.out.println("WaCaptureTest: " + passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }
}
