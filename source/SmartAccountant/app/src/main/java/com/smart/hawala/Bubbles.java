package com.smart.hawala;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 12.1: «إطار الالتقاط» على الكمبيوتر. قارئ الشاشة (إمكانية الوصول في النظام، أو قراءة الصورة) يعيد عناصر بمكانها ونصها،
 * وهنا نجمعها في فقاعات مرقمة ونخمّن اسم المحادثة (الغروب) من رأس النافذة.
 * صيغة سطر القارئ: «النوع⇥x⇥y⇥w⇥h⇥النص بـBase64». الأنواع: W النافذة (وعنوانها)، R فقاعة كاملة، T نص عنصر، L سطر من قراءة الصورة، H رأس المحادثة،
 * B (12.2) شكل فقاعة وجدناه في الصورة (BubbleShapes) بلا نص: تُجمع فيه أسطر القراءة التي يقع وسطها داخله.
 * منطق بحت بلا أندرويد ولا Swing حتى يُختبر.
 */
public final class Bubbles {
    private Bubbles() {}

    public static final class Node {
        public final char kind;
        public final int x, y, w, h;
        public final String text;

        public Node(char kind, int x, int y, int w, int h, String text) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.w = Math.max(0, w);
            this.h = Math.max(0, h);
            this.text = text == null ? "" : text;
        }

        int cx() { return x + w / 2; }

        int cy() { return y + h / 2; }

        boolean inside(int[] r) { return r == null || (cx() >= r[0] && cx() <= r[0] + r[2] && cy() >= r[1] && cy() <= r[1] + r[3]); }

        boolean contains(Node o) { return o.x >= x && o.y >= y && o.x + o.w <= x + w && o.y + o.h <= y + h; }
    }

    public static final class Bubble {
        public final int number;
        public final int x, y, w, h;
        public final String time, text;
        /** 12.3: اسم المرسل في الغروب إن ظهر في أول الفقاعة («~ Abo Nour +40…»)، وإلا فارغ. */
        public final String sender;

        Bubble(int number, int x, int y, int w, int h, String time, String text) { this(number, x, y, w, h, time, text, ""); }

        Bubble(int number, int x, int y, int w, int h, String time, String text, String sender) {
            this.number = number;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.time = time;
            this.text = text;
            this.sender = sender == null ? "" : sender;
        }

        public WaText.Msg msg() { return new WaText.Msg(sender, time, text); }
    }

    public static final class Result {
        public final List<Bubble> bubbles = new ArrayList<>();
        /** اسم المحادثة إن عُرف، وإلا فارغ. */
        public String chat = "";
        /** عنوان النافذة (اسم البرنامج). */
        public String window = "";
    }

    public static List<Node> parse(String out) {
        List<Node> l = new ArrayList<>();
        if (out == null) return l;
        for (String line : out.split("\n")) {
            // لا trim: النص الفارغ في آخر السطر (حقل أخير فارغ بعد ⇥) يجب أن يبقى حقلًا
            String[] p = line.replace("\r", "").replaceAll("^\\s+", "").split("\t", -1);
            if (p.length < 6 || p[0].length() != 1) continue;
            try {
                String t = p[5].isEmpty() ? "" : new String(Base64.getDecoder().decode(p[5]), StandardCharsets.UTF_8);
                l.add(new Node(p[0].charAt(0), (int) Double.parseDouble(p[1]), (int) Double.parseDouble(p[2]),
                        (int) Double.parseDouble(p[3]), (int) Double.parseDouble(p[4]), t));
            } catch (Exception ignored) {
                // سطر تالف
            }
        }
        return l;
    }

    /** ما لا يكون رسالة: فواصل الأيام، «رسائل غير مقروءة»، تنبيه التشفير، عناصر الواجهة. */
    private static final Pattern NOISE = Pattern.compile("(?iu)^\\s*(اليوم|أمس|امس|today|yesterday|"
            + "\\d+\\s*(رسالة|رسائل)\\s*غير\\s*مقروءة|\\d+\\s*unread\\s*messages?|"
            + "(الأحد|الاثنين|الإثنين|الثلاثاء|الأربعاء|الخميس|الجمعة|السبت|sunday|monday|tuesday|wednesday|thursday|friday|saturday)|"
            + "\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4}|"
            + ".*(مشفّرة تمامًا|مشفرة تماما|end-to-end encrypted).*|"
            + "اكتب رسالة|type a message|بحث|search|whatsapp|واتساب|الدردشات|chats|المجتمعات|communities|الحالة|status)\\s*$");

    static boolean noise(String t) {
        String s = MessageParser.digits(WaText.stripMarks(t)).trim();
        return s.isEmpty() || NOISE.matcher(s).matches() || WaCuts.timeLine(s) || WaWords.systemText(s);
    }

    /**
     * يبني الفقاعات داخل الإطار. frame = {x, y, w, h} بإحداثيات الشاشة، أو null للنافذة كلها.
     */
    public static Result build(List<Node> nodes, int[] frame) {
        Result r = new Result();
        Node win = null;
        for (Node n : nodes) if (n.kind == 'W') { win = n; break; }
        if (win != null) r.window = win.text.trim();
        List<Node> rows = new ArrayList<>(), lines = new ArrayList<>(), shapes = new ArrayList<>();
        for (Node n : nodes) {
            if (!n.inside(frame)) continue;
            if (n.kind == 'R') rows.add(n);
            else if (n.kind == 'B' || n.kind == 'M') shapes.add(n);
            else if ((n.kind == 'T' || n.kind == 'L') && !n.text.trim().isEmpty()) lines.add(n);
        }
        // فقاعة بلا اسم في إمكانية الوصول: نصها من عناصر النص التي بداخلها (من الأعلى للأسفل)
        for (int i = 0; i < rows.size(); i++) {
            Node n = rows.get(i);
            if (!n.text.trim().isEmpty()) continue;
            List<Node> in = new ArrayList<>();
            for (Node t : lines) if (n.contains(t)) in.add(t);
            in.sort(Comparator.comparingInt((Node t) -> t.y).thenComparingInt(t -> -t.x));
            StringBuilder sb = new StringBuilder();
            for (Node t : in) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(t.text.trim());
            }
            rows.set(i, new Node('R', n.x, n.y, n.w, n.h, sb.toString()));
        }
        rows.removeIf(n -> n.text.trim().isEmpty());
        List<Bubble> raw = new ArrayList<>();
        if (!rows.isEmpty()) {
            // الأكبر الذي يحتوي غيره يُترك إن كان نصه يجمع نصوص أبنائه: نأخذ أصغر الفقاعات (الفقاعة نفسها لا القائمة كلها)
            rows.sort(Comparator.comparingInt(n -> n.y));
            List<Node> keep = new ArrayList<>();
            for (Node n : rows) {
                boolean parent = false;
                for (Node o : rows) if (o != n && n.contains(o) && (n.w > o.w || n.h > o.h)) { parent = true; break; }
                if (!parent) keep.add(n);
            }
            for (Node n : keep) raw.add(bubble(n.x, n.y, n.w, n.h, n.text));
        } else if (!lines.isEmpty()) {
            List<Bubble> byShape = shapes.isEmpty() ? new ArrayList<>() : byShapes(shapes, lines);
            raw.addAll(byShape.isEmpty() ? cluster(lines) : byShape);
        }
        int k = 1;
        for (Bubble b : raw) {
            if (noise(b.text)) continue;
            r.bubbles.add(new Bubble(k++, b.x, b.y, b.w, b.h, b.time, b.text, b.sender));
        }
        r.chat = chatName(nodes, win, frame);
        return r;
    }

    private static Bubble bubble(int x, int y, int w, int h, String text) {
        String t = WaText.stripMarks(text).replace("\r\n", "\n").trim();
        String time = WaCuts.lastTime(t);
        // 12.3: سطر المرسل في أول فقاعة الغروب يصير «المرسل»، وتُحذف أسطر Forwarded وEdited وحجم الملف أينما كانت
        String[] sp = WaWords.split(t);
        // واتساب ويب يكتب في اسم الفقاعة أحيانًا الوقت ملصقًا بآخر النص: «… 100 دولار10:32 م»
        String body = WaCuts.dropMeta(sp[1]).trim();
        if (time.isEmpty()) {
            String[] tt = WaCuts.trailingTime(body);
            body = tt[0];
            time = tt[1];
        }
        return new Bubble(0, x, y, w, h, time, body, sp[0]);
    }

    /**
     * 12.2: الرسالة كلها رقم واحد: كل سطر قراءة يذهب إلى شكل الفقاعة الذي يقع وسطه داخله (الأصغر إن تداخلت).
     * 12.3: الأسطر خارج كل الأشكال تُجمَّع بالمسافات (ثم يُحذف ما هو تنبيه من البرنامج). الفقاعة بلا نص (صورة بلا تعليق) لا تُرقَّم.
     */
    static List<Bubble> byShapes(List<Node> shapes, List<Node> lines) {
        List<Node> rest = new ArrayList<>();
        List<List<Node>> in = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) in.add(new ArrayList<>());
        for (Node t : lines) {
            int best = -1;
            long area = Long.MAX_VALUE;
            for (int i = 0; i < shapes.size(); i++) {
                Node s = shapes.get(i);
                int cx = t.cx(), cy = t.cy();
                if (cx < s.x || cx > s.x + s.w || cy < s.y || cy > s.y + s.h) continue;
                long a = (long) s.w * s.h;
                if (a < area) { area = a; best = i; }
            }
            if (best >= 0) {
                // 12.5: أسطر داخل فقاعة صورة/ملصق (تعليق الصورة، أزرار التنزيل) لا تُنقل
                if (shapes.get(best).kind != 'M') in.get(best).add(t);
            } else rest.add(t);
        }
        List<Bubble> out = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) {
            List<Node> l = in.get(i);
            if (l.isEmpty()) continue;
            l.sort(Comparator.comparingInt((Node t) -> t.y).thenComparingInt(t -> -t.x));
            // أسطر على الارتفاع نفسه (اسم الملف والوقت مثلًا) تبقى على سطر واحد بترتيبها من اليمين
            StringBuilder sb = new StringBuilder();
            Node prev = null;
            for (Node t : l) {
                if (prev != null) sb.append(Math.abs(t.cy() - prev.cy()) < Math.max(4, Math.min(t.h, prev.h) / 2) ? " " : "\n");
                sb.append(t.text.trim());
                prev = t;
            }
            Node s = shapes.get(i);
            out.add(bubble(s.x, s.y, s.w, s.h, sb.toString()));
        }
        // 12.3: فقاعة لم يُعرف شكلها (لونها قريب جدًا من الخلفية مثلًا) لا تضيع: أسطرها خارج كل الأشكال تُجمَّع كما في 12.1
        if (!rest.isEmpty())
            for (Bubble b : cluster(rest)) if (letters(b.text) >= 3) out.add(b);
        out.sort(Comparator.comparingInt((Bubble b) -> b.y));
        return out;
    }

    /** عدد الحروف والأرقام (كسرة قراءة مثل «ف» خارج الفقاعات ليست رسالة). */
    static int letters(String t) {
        int n = 0;
        for (char c : t.toCharArray()) if (Character.isLetterOrDigit(c)) n++;
        return n;
    }

    /** أسطر قراءة الصورة إلى فقاعات: فجوة عمودية كبيرة، أو تغيّر الجهة (وارد/صادر)، أو سطر وقت، تبدأ فقاعة جديدة. */
    static List<Bubble> cluster(List<Node> lines) {
        List<Node> l = new ArrayList<>(lines);
        l.sort(Comparator.comparingInt((Node n) -> n.y).thenComparingInt(n -> -n.x));
        List<Bubble> out = new ArrayList<>();
        List<Node> cur = new ArrayList<>();
        int avgH = 0;
        for (Node n : l) avgH += n.h;
        avgH = l.isEmpty() ? 16 : Math.max(8, avgH / l.size());
        for (Node n : l) {
            if (!cur.isEmpty()) {
                Node last = cur.get(cur.size() - 1);
                int gap = n.y - (last.y + last.h);
                int bx = Integer.MAX_VALUE, bx2 = Integer.MIN_VALUE;
                for (Node c : cur) {
                    bx = Math.min(bx, c.x);
                    bx2 = Math.max(bx2, c.x + c.w);
                }
                int overlap = Math.min(bx2, n.x + n.w) - Math.max(bx, n.x);
                // 12.2: سطر على ارتفاع السطر السابق نفسه (الوقت بجانب النص) لا يبدأ فقاعة جديدة مهما بعُد أفقيًا
                boolean sameRow = Math.abs(n.cy() - last.cy()) < Math.max(4, Math.min(n.h, last.h) * 0.6);
                boolean side = !sameRow && overlap < Math.min(n.w, bx2 - bx) * 0.3;
                if ((!sameRow && gap > avgH * 0.75) || side || (!sameRow && WaCuts.timeLine(last.text))) {
                    out.add(fromLines(cur));
                    cur = new ArrayList<>();
                }
            }
            cur.add(n);
        }
        if (!cur.isEmpty()) out.add(fromLines(cur));
        return out;
    }

    private static Bubble fromLines(List<Node> c) {
        int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE;
        StringBuilder sb = new StringBuilder();
        for (Node n : c) {
            x = Math.min(x, n.x);
            y = Math.min(y, n.y);
            x2 = Math.max(x2, n.x + n.w);
            y2 = Math.max(y2, n.y + n.h);
            if (sb.length() > 0) sb.append('\n');
            sb.append(n.text.trim());
        }
        return bubble(x, y, x2 - x, y2 - y, sb.toString());
    }

    /** اسم المحادثة: عنصر رأس صريح (H)، أو أعلى نص في نافذة البرنامج فوق الإطار وقريب منه أفقيًا. */
    static String chatName(List<Node> nodes, Node win, int[] frame) {
        for (Node n : nodes) if (n.kind == 'H' && !n.text.trim().isEmpty() && !noise(n.text)) return firstLine(n.text);
        if (win == null) return "";
        int top = win.y, band = Math.max(60, win.h / 8);
        Node best = null;
        for (Node n : nodes) {
            if (n.kind != 'T' && n.kind != 'L') continue;
            if (n.y < top || n.y > top + band) continue;
            String t = n.text.trim();
            if (t.length() < 2 || t.length() > 60 || noise(t)) continue;
            if (frame != null && (n.cx() < frame[0] - 40 || n.cx() > frame[0] + frame[2] + 40)) continue;
            if (best == null || n.y < best.y) best = n;
        }
        return best == null ? "" : firstLine(best.text);
    }

    private static String firstLine(String s) {
        String t = WaText.stripMarks(s).trim();
        int i = t.indexOf('\n');
        return (i < 0 ? t : t.substring(0, i)).trim();
    }

    /** أقرب مكتب لاسم المحادثة: تطابق تام، ثم احتواء، ثم كلمات مشتركة. يعيد الفهرس أو -1. */
    public static int matchOffice(String chat, List<String> names) {
        String c = key(chat);
        if (c.isEmpty()) return -1;
        int best = -1;
        double score = 0;
        for (int i = 0; i < names.size(); i++) {
            String n = key(names.get(i));
            if (n.isEmpty()) continue;
            double s;
            if (n.equals(c)) s = 3;
            else if (c.contains(n) || n.contains(c)) s = 2 + Math.min(n.length(), c.length()) / (double) Math.max(n.length(), c.length());
            else s = words(c, n);
            if (s > score) {
                score = s;
                best = i;
            }
        }
        return score >= 0.6 ? best : -1;
    }

    private static String key(String s) {
        if (s == null) return "";
        String n = MessageParser.norm(WaText.stripMarks(s)).toLowerCase(Locale.ROOT);
        n = n.replaceAll("(?u)\\b(مكتب|شركه|شركة|صرافه|صرافة|للصرافه|للحوالات|حوالات|office|exchange)\\b", " ");
        StringBuilder b = new StringBuilder();
        for (char ch : n.toCharArray()) b.append(Character.isLetterOrDigit(ch) ? ch : ' ');
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    private static double words(String a, String b) {
        String[] x = a.split(" "), y = b.split(" ");
        int common = 0;
        for (String p : x) for (String q : y) if (p.length() > 1 && p.equals(q)) { common++; break; }
        return common / (double) Math.max(1, Math.min(x.length, y.length));
    }
}
