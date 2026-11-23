package com.smart.hawala;

import java.math.BigDecimal;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 9.0: ترتيب قائمة المكاتب بما يختاره المستخدم (منطق بحت مشترك بين الهاتف والماك).
 * المثبّتة في الأعلى دائمًا (اختيار المستخدم نفسه)، ثم الترتيب المختار، ثم الأبجدي عند التساوي، والموقوفة في الأسفل.
 * «الرصيد» هنا هو ميزان المكتب: مجموع أرصدته بكل العملات مقوّمًا بالدولار (لنا − علينا)، ويُرتَّب بحجمه المطلق.
 */
public final class OfficeOrder {
    private OfficeOrder() {}

    public static final int ALPHA = 0, BIGGEST = 1, SMALLEST = 2, MOST_USED = 3, LAST_OPENED = 4;
    public static final String[] NAMES = {"أبجديًا", "الأكبر رصيدًا", "الأصغر رصيدًا", "الأكثر استخدامًا", "آخر ما فتحته"};

    /** ما يلزم للترتيب. أي خريطة ناقصة تُعامل كصفر. */
    public static final class Inputs {
        /** ميزان المكتب بالدولار (لنا − علينا). */
        public Map<Long, BigDecimal> usdNet = new HashMap<>();
        /** عدد حركات المكتب في آخر 90 يومًا، ثم مجموع حركاته كلها لفك التساوي. */
        public Map<Long, long[]> usage = new HashMap<>();
        /** آخر مرة فُتحت فيها صفحة المكتب أو سُجّلت عليه حركة. */
        public Map<Long, Long> opened = new HashMap<>();
    }

    public static int clamp(int mode) { return mode < 0 || mode >= NAMES.length ? ALPHA : mode; }

    public static void sort(List<Model.Office> list, int mode, final Inputs in) {
        final int m = clamp(mode);
        final Collator col = Collator.getInstance(new Locale("ar"));
        Collections.sort(list, new Comparator<Model.Office>() {
            @Override public int compare(Model.Office x, Model.Office y) {
                if (x.pinned != y.pinned) return x.pinned ? -1 : 1;
                if (x.active != y.active) return x.active ? -1 : 1;
                int c = 0;
                switch (m) {
                    case BIGGEST: c = size(in, y).compareTo(size(in, x)); break;
                    case SMALLEST: c = size(in, x).compareTo(size(in, y)); break;
                    case MOST_USED: {
                        long[] ux = use(in, x), uy = use(in, y);
                        c = Long.compare(uy[0], ux[0]);
                        if (c == 0) c = Long.compare(uy[1], ux[1]);
                        break;
                    }
                    case LAST_OPENED: c = Long.compare(opened(in, y), opened(in, x)); break;
                    default: break;
                }
                return c != 0 ? c : col.compare(Model.nz(x.name), Model.nz(y.name));
            }
        });
    }

    static BigDecimal size(Inputs in, Model.Office o) {
        BigDecimal v = in.usdNet.get(o.id);
        return v == null ? BigDecimal.ZERO : v.abs();
    }

    static long[] use(Inputs in, Model.Office o) {
        long[] v = in.usage.get(o.id);
        return v == null ? new long[2] : v;
    }

    static long opened(Inputs in, Model.Office o) {
        Long v = in.opened.get(o.id);
        return v == null ? 0 : v;
    }

    // ===================================================================================
    // سجل آخر فتح لكل مكتب: «id:وقت,id:وقت» (الأحدث أولًا، حتى 400 مكتب)
    // ===================================================================================

    public static Map<Long, Long> parseOpened(String s) {
        Map<Long, Long> m = new HashMap<>();
        if (s == null) return m;
        for (String p : s.split(",")) {
            int i = p.indexOf(':');
            if (i <= 0) continue;
            try {
                long id = Long.parseLong(p.substring(0, i).trim()), t = Long.parseLong(p.substring(i + 1).trim());
                if (!m.containsKey(id)) m.put(id, t);
            } catch (NumberFormatException ignored) {
                // جزء فاسد يُهمل
            }
        }
        return m;
    }

    public static String touchOpened(String s, long office, long when) {
        StringBuilder b = new StringBuilder().append(office).append(':').append(when);
        int n = 1;
        if (s != null) for (String p : s.split(",")) {
            int i = p.indexOf(':');
            if (i <= 0 || n >= 400) continue;
            if (p.substring(0, i).trim().equals(String.valueOf(office))) continue;
            b.append(',').append(p.trim());
            n++;
        }
        return b.toString();
    }
}
