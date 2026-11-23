package com.smart.hawala;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * 12.2: «إطار الالتقاط»: الرسالة كلها رقم واحد من شكل الفقاعة في الصورة (واتساب فاتح وداكن، تلغرام بخلفية متدرجة،
 * شاشة Retina)، وتجميع أسطر القراءة فيها، وبصمة القراءة التلقائية. صور مصطنعة ونصوص مصطنعة.
 */
public class Capture122Test {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    /** خلفية فيها زخارف (كخلفية واتساب)، ثم فقاعات بنص (خطوط داكنة قصيرة تشبه الكلمات). */
    static BufferedImage chat(int w, int h, Color wall, Color doodle, boolean gradient, int k) {
        BufferedImage im = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.scale(k, k);
        if (gradient) g.setPaint(new GradientPaint(0, 0, wall, w, h, wall.darker()));
        else g.setColor(wall);
        g.fillRect(0, 0, w, h);
        Random rnd = new Random(7);
        g.setColor(doodle);
        g.setStroke(new BasicStroke(1.3f));
        for (int i = 0; i < 260; i++) {
            int x = rnd.nextInt(w), y = rnd.nextInt(h);
            if (rnd.nextBoolean()) g.drawOval(x, y, 8 + rnd.nextInt(14), 8 + rnd.nextInt(14));
            else g.drawLine(x, y, x + rnd.nextInt(20) - 10, y + rnd.nextInt(20) - 10);
        }
        g.dispose();
        return im;
    }

    static void bubble(BufferedImage im, int k, int x, int y, int w, int h, Color fill, Color ink, int lines) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(k, k);
        g.setColor(fill);
        g.fillRoundRect(x, y, w, h, 14, 14);
        g.setColor(ink);
        Random rnd = new Random(x * 31 + y);
        for (int l = 0; l < lines; l++) {
            int cx = x + 10, ly = y + 10 + l * 18;
            while (cx < x + w - 40) {
                int ww = 8 + rnd.nextInt(26);
                g.fillRect(cx, ly, Math.min(ww, x + w - 12 - cx), 9);
                cx += ww + 5;
            }
        }
        // الوقت في الزاوية السفلية
        g.fillRect(x + w - 40, y + h - 14, 28, 7);
        g.dispose();
    }

    static int[] px(BufferedImage im) { return im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()); }

    static boolean near(BubbleShapes.Box b, int x, int y, int w, int h, int tol) {
        return Math.abs(b.x - x) <= tol && Math.abs(b.y - y) <= tol && Math.abs(b.w - w) <= tol * 2 && Math.abs(b.h - h) <= tol * 2;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== واتساب فاتح: خلفية بزخارف، فقاعة واردة بيضاء وصادرة خضراء وفاصل يوم");
        Color wall = new Color(0xEF, 0xEA, 0xE2), doodle = new Color(0xDD, 0xD6, 0xCC);
        BufferedImage im = chat(500, 420, wall, doodle, false, 1);
        bubble(im, 1, 200, 8, 90, 22, Color.WHITE, Color.WHITE, 0);
        bubble(im, 1, 20, 50, 260, 80, Color.WHITE, new Color(0x11, 0x1B, 0x21), 3);
        bubble(im, 1, 210, 150, 270, 46, new Color(0xD9, 0xFD, 0xD3), new Color(0x11, 0x1B, 0x21), 1);
        bubble(im, 1, 20, 220, 300, 120, Color.WHITE, new Color(0x11, 0x1B, 0x21), 5);
        // اقتباس رد داخل الفقاعة الأخيرة (لون أغمق)
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(0xF0, 0xF0, 0xF0));
        g.fillRoundRect(28, 228, 284, 34, 8, 8);
        g.dispose();
        List<BubbleShapes.Box> l = BubbleShapes.find(px(im), im.getWidth(), im.getHeight(), 1);
        System.out.println("    " + l);
        check(l.size() == 4, "أربعة أشكال: الفاصل وثلاث فقاعات (" + l.size() + ")");
        boolean a = false, b = false, c = false;
        for (BubbleShapes.Box x : l) {
            a |= near(x, 20, 50, 260, 80, 3);
            b |= near(x, 210, 150, 270, 46, 3);
            c |= near(x, 20, 220, 300, 120, 3);
        }
        check(a && b && c, "حدود الفقاعات الثلاث مضبوطة، والاقتباس داخل الأخيرة لا يصير فقاعة");

        System.out.println("== شاشة Retina (ضعف البكسلات)");
        BufferedImage r2 = chat(500, 300, wall, doodle, false, 2);
        bubble(r2, 2, 20, 40, 260, 80, Color.WHITE, Color.DARK_GRAY, 3);
        bubble(r2, 2, 220, 150, 260, 46, new Color(0xD9, 0xFD, 0xD3), Color.DARK_GRAY, 1);
        List<BubbleShapes.Box> l2 = BubbleShapes.find(px(r2), r2.getWidth(), r2.getHeight(), 2);
        check(l2.size() == 2 && near(l2.get(0), 40, 80, 520, 160, 6), "فقاعتان بإحداثيات الصورة الكبيرة (" + l2 + ")");

        System.out.println("== واتساب داكن");
        BufferedImage dk = chat(500, 300, new Color(0x0B, 0x14, 0x1A), new Color(0x16, 0x20, 0x26), false, 1);
        bubble(dk, 1, 20, 40, 260, 62, new Color(0x20, 0x2C, 0x33), new Color(0xE9, 0xED, 0xEF), 2);
        bubble(dk, 1, 220, 140, 260, 46, new Color(0x00, 0x5C, 0x4B), new Color(0xE9, 0xED, 0xEF), 1);
        List<BubbleShapes.Box> l3 = BubbleShapes.find(px(dk), dk.getWidth(), dk.getHeight(), 1);
        check(l3.size() == 2, "فقاعتان في الوضع الداكن (" + l3 + ")");

        System.out.println("== تلغرام: خلفية خضراء متدرجة وفقاعة ملف بأيقونة دائرية");
        BufferedImage tg = chat(560, 360, new Color(0xB8, 0xC9, 0x8F), new Color(0xA8, 0xBA, 0x80), true, 1);
        bubble(tg, 1, 10, 30, 420, 80, Color.WHITE, new Color(0x22, 0x22, 0x22), 2);
        Graphics2D g2 = tg.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(0x4F, 0xAE, 0x4E));
        g2.fillOval(20, 40, 56, 56);
        g2.dispose();
        bubble(tg, 1, 380, 140, 160, 40, new Color(0xEF, 0xFE, 0xDD), new Color(0x22, 0x22, 0x22), 0);
        bubble(tg, 1, 10, 200, 430, 80, Color.WHITE, new Color(0x22, 0x22, 0x22), 2);
        List<BubbleShapes.Box> l4 = BubbleShapes.find(px(tg), tg.getWidth(), tg.getHeight(), 1);
        check(l4.size() == 3, "ثلاث فقاعات، والأيقونة الخضراء داخل الأولى لا تصير فقاعة (" + l4 + ")");

        System.out.println("== أسطر القراءة داخل الأشكال ← رسالة واحدة لكل فقاعة");
        // كما في صورة المستخدم: اسم الملف، والحجم، والوقت، كل منها سطر قراءة منفصل؛ وفاصل «Today»
        StringBuilder sb = new StringBuilder();
        sb.append("W\t0\t0\t600\t400\t").append(b64("Telegram")).append('\n');
        sb.append("B\t10\t30\t420\t80\t\n");
        sb.append("B\t380\t140\t160\t40\t\n");
        sb.append("B\t10\t200\t430\t80\t\n");
        sb.append("B\t230\t160\t80\t22\t\n");
        sb.append("L\t90\t42\t300\t20\t").append(b64("SmartAccountant...ppleSilicon (1).zip")).append('\n');
        sb.append("L\t90\t66\t200\t18\t").append(b64("29.1 MB - Show in Finder")).append('\n');
        sb.append("L\t370\t90\t50\t14\t").append(b64("12:07")).append('\n');
        sb.append("L\t480\t150\t50\t16\t").append(b64("0 12:10")).append('\n');
        sb.append("L\t240\t163\t60\t16\t").append(b64("Today")).append('\n');
        sb.append("L\t30\t212\t300\t20\t").append(b64("ابو عمر 10250.")).append('\n');
        sb.append("L\t30\t236\t200\t20\t").append(b64("استلمت")).append('\n');
        sb.append("L\t380\t262\t50\t14\t").append(b64("22:16")).append('\n');
        // كلمة خارج كل فقاعة (فوق الخلفية) لا تُرقّم
        sb.append("L\t470\t320\t60\t16\t").append(b64("ف")).append('\n');
        Bubbles.Result res = Bubbles.build(Bubbles.parse(sb.toString()), new int[]{0, 0, 600, 400});
        for (Bubbles.Bubble x : res.bubbles) System.out.println("    " + x.number + ": " + x.text.replace('\n', '|') + " [" + x.time + "]");
        check(res.bubbles.size() == 3, "ثلاث رسائل فقط: لا رقم لكل كلمة، ولا للفاصل «Today» ولا لنص خارج الفقاعات (" + res.bubbles.size() + ")");
        if (res.bubbles.size() == 3) {
            check(res.bubbles.get(0).text.equals("SmartAccountant...ppleSilicon (1).zip") && res.bubbles.get(0).time.equals("12:07"),
                    "الأولى: اسم الملف رسالة واحدة ووقتها 12:07، وسطر «29.1 MB - Show in Finder» حُذف (12.3)");
            check(res.bubbles.get(1).text.equals("0") && res.bubbles.get(1).time.equals("12:10"), "الثانية: نص قصير ووقته على السطر نفسه");
            check(res.bubbles.get(2).text.equals("ابو عمر 10250.\nاستلمت") && res.bubbles.get(2).time.equals("22:16"), "الثالثة: سطران عربيان ووقتها");
            check(res.bubbles.get(0).number == 1 && res.bubbles.get(2).number == 3, "الترقيم من الأعلى للأسفل");
        }

        System.out.println("== بلا أشكال: أسطر على ارتفاع واحد تبقى رسالة واحدة");
        String noShape = "L\t30\t40\t200\t18\t" + b64("حوالة 500 دولار") + "\n" + "L\t380\t41\t50\t16\t" + b64("12:07") + "\n";
        Bubbles.Result ns = Bubbles.build(Bubbles.parse(noShape), new int[]{0, 0, 600, 400});
        check(ns.bubbles.size() == 1 && ns.bubbles.get(0).time.equals("12:07"), "النص والوقت جنبه رسالة واحدة (" + ns.bubbles.size() + ")");

        System.out.println("== بصمة القراءة التلقائية");
        Rectangle in = new Rectangle(0, 0, 500, 420);
        int[] s1 = DeskCapture.signature(im, in, new ArrayList<>());
        int[] s2 = DeskCapture.signature(im, in, new ArrayList<>());
        check(!DeskCapture.changed(s1, s2), "الصورة نفسها: لا تغيير");
        BufferedImage caret = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_RGB);
        caret.getGraphics().drawImage(im, 0, 0, null);
        caret.getGraphics().fillRect(300, 380, 2, 14);
        check(!DeskCapture.changed(s1, DeskCapture.signature(caret, in, new ArrayList<>())), "وميض مؤشر الكتابة لا يُعدّ تغييرًا");
        BufferedImage scrolled = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_RGB);
        scrolled.getGraphics().drawImage(im, 0, -60, null);
        check(DeskCapture.changed(s1, DeskCapture.signature(scrolled, in, new ArrayList<>())), "التمرير تغيير حقيقي");
        BufferedImage badge = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_RGB);
        badge.getGraphics().drawImage(im, 0, 0, null);
        Graphics bg = badge.getGraphics();
        bg.setColor(new Color(0x1F, 0x8A, 0x70));
        for (int i = 0; i < 6; i++) bg.fillRect(20 + i * 70, 40, 28, 28);
        List<Rectangle> mask = new ArrayList<>();
        for (int i = 0; i < 6; i++) mask.add(new Rectangle(20 + i * 70, 40, 28, 28));
        check(!DeskCapture.changed(DeskCapture.signature(im, in, mask), DeskCapture.signature(badge, in, mask)), "أرقامنا فوق الفقاعات لا تُعدّ تغييرًا");

        System.out.println("== 12.3: واتساب ماك فاتح: فقاعات لونها قريب جدًا من الخلفية، وفواصل «Fri 6 Nov» و«33 unread messages»");
        Color w3 = new Color(0xF4, 0xF1, 0xEC), d3 = new Color(0xE6, 0xE2, 0xDB), b3 = new Color(0xE9, 0xE5, 0xDE);
        BufferedImage mac = chat(520, 520, w3, d3, false, 2);
        bubble(mac, 2, 190, 10, 120, 24, Color.WHITE, Color.WHITE, 0);
        bubble(mac, 2, 20, 50, 330, 120, b3, new Color(0x11, 0x1B, 0x21), 5);
        bubble(mac, 2, 170, 190, 180, 24, Color.WHITE, Color.WHITE, 0);
        bubble(mac, 2, 20, 230, 300, 66, b3, new Color(0x11, 0x1B, 0x21), 2);
        bubble(mac, 2, 20, 320, 300, 44, b3, new Color(0x11, 0x1B, 0x21), 1);
        List<BubbleShapes.Box> l5 = BubbleShapes.find(px(mac), mac.getWidth(), mac.getHeight(), 2);
        System.out.println("    " + l5);
        check(l5.size() == 5, "الفقاعات الثلاث بلونها القريب من الخلفية والفاصلان كلها أشكال (" + l5.size() + ")");
        StringBuilder m3 = new StringBuilder();
        m3.append("B\t190\t10\t120\t24\t\n").append("L\t205\t14\t90\t16\t").append(b64("Fri 6 Nov")).append('\n');
        m3.append("B\t20\t50\t330\t120\t\n");
        m3.append("L\t40\t56\t260\t16\t").append(b64("~Abo Nour   +40 765 450 476")).append('\n');
        m3.append("L\t60\t78\t270\t18\t").append(b64("مطابقة رصيد")).append('\n');
        m3.append("L\t60\t100\t270\t18\t").append(b64("السلام عليكم ورحمة الله وبركاته")).append('\n');
        m3.append("L\t60\t122\t270\t18\t").append(b64("الرصيد 5000 دولار لكم")).append('\n');
        m3.append("L\t300\t150\t40\t14\t").append(b64("23:30")).append('\n');
        m3.append("B\t170\t190\t180\t24\t\n").append("L\t180\t194\t160\t16\t").append(b64("33 unread messages")).append('\n');
        m3.append("B\t20\t230\t300\t66\t\n").append("L\t30\t236\t260\t16\t").append(b64("~Abo Nour +40 765 450 476")).append('\n');
        m3.append("L\t30\t258\t200\t16\t").append(b64("This message was deleted")).append('\n').append("L\t270\t276\t40\t14\t").append(b64("23:30")).append('\n');
        // سطر عربي خارج كل الأشكال (فقاعة لم يُعرف شكلها): يبقى رسالة ولا يضيع
        m3.append("L\t380\t400\t120\t18\t").append(b64("استلمت 300 يورو")).append('\n').append("L\t460\t420\t40\t14\t").append(b64("23:41")).append('\n');
        m3.append("L\t30\t480\t400\t16\t").append(b64("Message history sent by a group member. Learn more")).append('\n');
        Bubbles.Result r3 = Bubbles.build(Bubbles.parse(m3.toString()), new int[]{0, 0, 520, 520});
        for (Bubbles.Bubble x : r3.bubbles) System.out.println("    " + x.number + ": [" + x.sender + "] " + x.text.replace('\n', '|') + " [" + x.time + "]");
        check(r3.bubbles.size() == 2, "رسالتان فقط: لا رقم لفاصل اليوم ولا «unread» ولا «deleted» ولا «Learn more» (" + r3.bubbles.size() + ")");
        if (r3.bubbles.size() == 2) {
            check(r3.bubbles.get(0).sender.equals("Abo Nour") && r3.bubbles.get(0).text.startsWith("مطابقة رصيد") && r3.bubbles.get(0).time.equals("23:30"),
                    "سطر «~Abo Nour +40…» صار المرسل، والنص العربي كامل ووقته");
            check(r3.bubbles.get(1).text.equals("استلمت 300 يورو") && r3.bubbles.get(1).time.equals("23:41"), "الرسالة العربية خارج الأشكال لم تضع");
        }

        System.out.println("== 12.5: إطار ضيق: الفقاعة تملأ عرضه (تلمس اليمين واليسار)، وفقاعة صورة");
        BufferedImage nar = chat(320, 420, w3, d3, false, 2);
        bubble(nar, 2, 0, 20, 320, 150, b3, new Color(0x11, 0x1B, 0x21), 7);
        Graphics2D gm = nar.createGraphics();
        gm.scale(2, 2);
        gm.setColor(b3);
        gm.fillRoundRect(20, 200, 240, 190, 14, 14);
        java.util.Random rr = new java.util.Random(3);
        for (int yy = 205; yy < 383; yy += 3) for (int xx = 25; xx < 253; xx += 3) {
            gm.setColor(new Color(rr.nextInt(256), rr.nextInt(256), rr.nextInt(256)));
            gm.fillRect(xx, yy, 3, 3);
        }
        gm.dispose();
        List<BubbleShapes.Box> l6 = BubbleShapes.find(px(nar), nar.getWidth(), nar.getHeight(), 2);
        System.out.println("    " + l6 + " media=" + (l6.size() > 1 ? l6.get(1).media : "?"));
        boolean wide = false, med = false;
        for (BubbleShapes.Box x : l6) {
            if (!x.media && x.w >= 600 && x.h >= 280) wide = true;
            if (x.media && x.h >= 360) med = true;
        }
        check(wide, "الفقاعة العريضة في الإطار الضيق تُعرف");
        check(med, "فقاعة الصورة تُعرف «صورة»");
        String m6 = "B\t0\t20\t320\t150\t\nL\t20\t30\t280\t16\t" + b64("السلام عليكم") + "\nL\t20\t60\t280\t16\t" + b64("دولار 7,868 دائن علينا")
                + "\nM\t20\t200\t240\t190\t\nL\t40\t360\t150\t16\t" + b64("تعليق على الصورة") + "\n";
        Bubbles.Result r6 = Bubbles.build(Bubbles.parse(m6), new int[]{0, 0, 320, 420});
        check(r6.bubbles.size() == 1 && r6.bubbles.get(0).text.contains("7,868"), "رسالة واحدة كاملة، ورسالة الصورة لا تُرقَّم (" + r6.bubbles.size() + ")");

        System.out.println("== 12.3: قاموس تنبيهات البرنامج");
        String[][] sysYes = {{"Fri 6 Nov"}, {"6 Nov 2026"}, {"Nov 6, 2026"}, {"الجمعة 6 نوفمبر"}, {"Today"}, {"أمس"}, {"33 unread messages"}, {"12 رسالة غير مقروءة"},
                {"This message was deleted"}, {"تم حذف هذه الرسالة"}, {"Message history sent by a group member. Learn more"},
                {"Missed voice call"}, {"مكالمة صوتية فائتة"}, {"Ahmad joined using this group's invite link"}, {"Omar added Khaled"}, {"أحمد غادر"},
                {"+40 765 450 476 joined"}, {"Photo"}, {"صورة"}};
        for (String[] t : sysYes) check(WaWords.systemText(t[0]), "تنبيه: «" + t[0] + "»");
        String[] sysNo = {"added 500 dollar to Ahmad", "حوالة 300 دولار لأحمد", "Omar added 500", "استلمت", "نوفمبر 300 دولار لأبو علي", "left 200 euro with Sami"};
        for (String t : sysNo) check(!WaWords.systemText(t), "رسالة لا تنبيه: «" + t + "»");
        String[] sp = WaWords.split("+90 545 965 72 40 ~ زهرة حلب\nForwarded\nابو عمر 10250.\nاستلمت\n29.1 MB - Show in Finder");
        check(sp[0].equals("زهرة حلب") && sp[1].equals("ابو عمر 10250.\nاستلمت"), "المرسل من «الرقم ~ الاسم»، وحُذف Forwarded وحجم الملف: [" + sp[0] + "] " + sp[1].replace('\n', '|'));
        String[] one = WaWords.split("~ Abo Nour +40 765 450 476");
        check(one[1].contains("Abo Nour"), "سطر وحيد لا يُعدّ مرسلًا (قد يكون الرسالة نفسها)");

        System.out.println("== 12.3: التحية لا تصير اسم المستفيد");
        java.util.List<MessageParser.Cur> curs = new java.util.ArrayList<>();
        curs.add(new MessageParser.Cur("USD", "دولار"));
        MessageParser.Result pr = MessageParser.parse("السلام عليكم ورحمة الله وبركاته\nيعطيكم العافية تحية طيبة السادة الكرام\nتسليم 500 دولار لأبو خالد", curs, new java.util.LinkedHashMap<>());
        check("500".equals(pr.amount) && pr.person.contains("خالد") && !pr.person.contains("العافية") && !pr.person.contains("السادة"),
                "المبلغ 500 والمستفيد «" + pr.person + "»");

        System.out.println();
        System.out.println("Capture122Test: " + passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }
}
