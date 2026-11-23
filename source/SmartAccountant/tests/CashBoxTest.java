import com.smart.hawala.*;
import java.util.*;

/**
 * 9.0: منطق صندوق المكتب وعدّاد واتساب وترتيب المكاتب (بلا قاعدة بيانات).
 * القاعدة: رصيد الصندوق = مجموع أثر الحركات المعدودة، وخلاصة أي فترة: آخرها = أولها + الداخل − الخارج.
 */
public class CashBoxTest {
    static int fails, passes;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static long T0 = 1_700_000_000_000L;

    static CashBox.Move mv(long id, String kind, String cur, long amount, int dir, long t) {
        CashBox.Move m = new CashBox.Move();
        m.id = id;
        m.kind = kind;
        m.currency = cur;
        m.amount = amount;
        m.dir = dir;
        m.createdAt = t;
        m.uuid = "u" + id;
        return m;
    }

    public static void main(String[] a) {
        System.out.println("== الأثر والأرصدة");
        List<CashBox.Move> ms = new ArrayList<>();
        ms.add(mv(1, CashBox.OPENING, "USD", 100000, 1, T0));
        CashBox.Move in = mv(2, CashBox.IN, "USD", 50000, 1, T0 + 1000);
        in.fee = 1000;
        in.feeSide = CashBox.FEE_US;
        in.feeInCash = true;
        ms.add(in);
        CashBox.Move out = mv(3, CashBox.OUT, "USD", 20000, -1, T0 + 2000);
        out.fee = 500;
        out.feeSide = CashBox.FEE_THEM;
        out.feeInCash = true;
        ms.add(out);
        CashBox.Move ex = mv(4, CashBox.EXPENSE, "USD", 3000, -1, T0 + 3000);
        ex.category = "إيجار المحل";
        ms.add(ex);
        CashBox.Move linked = mv(5, CashBox.OUT, "USD", 7000, -1, T0 + 4000);
        linked.officeId = 9L;
        linked.txId = 99L;
        linked.txStatus = "ACTIVE";
        linked.fee = 70;
        linked.feeSide = CashBox.FEE_US;
        linked.feeInCash = false;
        ms.add(linked);
        CashBox.Move pend = mv(6, CashBox.IN, "USD", 99999, 1, T0 + 5000);
        pend.txId = 100L;
        pend.officeId = 9L;
        pend.txStatus = Model.STATUS_PENDING;
        ms.add(pend);
        CashBox.Move canc = mv(7, CashBox.IN, "USD", 88888, 1, T0 + 6000);
        canc.status = CashBox.CANCELLED;
        ms.add(canc);
        CashBox.Move exOut = mv(8, CashBox.EXCHANGE, "USD", 10000, -1, T0 + 7000);
        CashBox.Move exIn = mv(9, CashBox.EXCHANGE, "EUR", 9200, 1, T0 + 7000);
        exOut.pairUuid = exIn.pairUuid = "p";
        ms.add(exOut);
        ms.add(exIn);
        Map<String, Long> b = CashBox.balances(ms, Arrays.asList("USD", "EUR", "TRY"));
        long usd = 100000 + 50000 + 1000 - 20000 - 500 - 3000 - 7000 - 10000;
        check(b.get("USD") == usd && b.get("EUR") == 9200 && b.get("TRY") == 0, "الأرصدة: USD " + b.get("USD") + " = " + usd + "، EUR 92، TRY صفر");
        check(linked.effect() == -7000, "أجرة الحركة المرتبطة على حساب المكتب لا تمس النقد");
        check(pend.effect() == 0 && pend.pending() && "بانتظار الاعتماد".equals(pend.stateText()), "حركة مكتب بانتظار الاعتماد لا تُحسب في الصندوق");
        check(canc.effect() == 0 && "ملغاة".equals(canc.stateText()), "الملغاة لا تُحسب");
        CashBox.Move rej = mv(10, CashBox.OUT, "USD", 5, -1, T0);
        rej.txId = 1L;
        rej.txStatus = Model.STATUS_REJECTED;
        check(rej.effect() == 0 && "مرفوضة".equals(rej.stateText()), "المرفوضة لا تُحسب");

        System.out.println("== الخلاصة لفترة");
        Map<String, CashBox.Summary> all = CashBox.summaries(ms, 0, 0, null);
        CashBox.Summary su = all.get("USD");
        check(su.closing == usd && su.computedClosing() == usd, "الخلاصة الكاملة = الرصيد");
        check(su.openingsInPeriod == 100000 && su.in == 50000 && su.out == 27000 && su.feesIn == 1000 && su.feesOut == 500 && su.expenses == 3000
                && su.exchangeOut == 10000 && su.officeOut == 7000, "تفصيل الداخل والخارج والأجور والمصاريف والصرف");
        check(su.byCategory.get("إيجار المحل") == 3000, "المصاريف حسب البند");
        Map<String, CashBox.Summary> mid = CashBox.summaries(ms, T0 + 2500, T0 + 4500, null);
        CashBox.Summary m2 = mid.get("USD");
        long before = 100000 + 50000 + 1000 - 20000 - 500;
        check(m2.opening == before && m2.closing == before - 3000 - 7000 && m2.count == 2, "فترة وسطى: أولها " + m2.opening + " وآخرها " + m2.closing);
        Map<String, CashBox.Summary> onlyEur = CashBox.summaries(ms, 0, 0, new HashSet<>(Collections.singletonList("EUR")));
        check(onlyEur.size() == 1 && onlyEur.get("EUR").exchangeIn == 9200, "تصفية بالعملة");
        // تحقق عشوائي: لكل نقطة قطع، أول الفترة التالية = آخر السابقة
        Random r = new Random(7);
        List<CashBox.Move> rnd = new ArrayList<>();
        long t = T0;
        for (int i = 0; i < 400; i++) {
            t += 1 + r.nextInt(100000);
            String[] kinds = {CashBox.IN, CashBox.OUT, CashBox.EXPENSE, CashBox.ADJUST};
            String k = kinds[r.nextInt(kinds.length)];
            CashBox.Move x = mv(100 + i, k, r.nextBoolean() ? "USD" : "SYP", 1 + r.nextInt(100000), k.equals(CashBox.IN) ? 1 : k.equals(CashBox.ADJUST) ? (r.nextBoolean() ? 1 : -1) : -1, t);
            if (r.nextInt(5) == 0) {
                x.fee = r.nextInt(500) + 1;
                x.feeSide = r.nextBoolean() ? CashBox.FEE_US : CashBox.FEE_THEM;
                x.feeInCash = r.nextBoolean();
            }
            if (r.nextInt(9) == 0) x.status = CashBox.CANCELLED;
            if (k.equals(CashBox.EXPENSE)) x.category = "بند " + r.nextInt(4);
            rnd.add(x);
        }
        boolean chain = true;
        long cut1 = T0 + (t - T0) / 3, cut2 = T0 + 2 * (t - T0) / 3;
        Map<String, CashBox.Summary> p1 = CashBox.summaries(rnd, 0, cut1, null), p2 = CashBox.summaries(rnd, cut1, cut2, null), p3 = CashBox.summaries(rnd, cut2, 0, null);
        Map<String, Long> fin = CashBox.balances(rnd, null);
        for (String c : fin.keySet()) {
            if (p1.get(c).closing != p2.get(c).opening || p2.get(c).closing != p3.get(c).opening || p3.get(c).closing != fin.get(c)) chain = false;
            long cats = 0;
            for (CashBox.Summary s : new CashBox.Summary[]{p1.get(c), p2.get(c), p3.get(c)}) {
                long sum = 0;
                for (long v : s.byCategory.values()) sum += v;
                if (sum != s.expenses) chain = false;
                cats += sum;
            }
        }
        check(chain, "400 حركة عشوائية في 3 فترات: آخر كل فترة = أول التالية، وآخر الأخيرة = الرصيد، والبنود = المصاريف");

        System.out.println("== التحقق من الإدخال");
        CashBox.Config cfg = new CashBox.Config();
        cfg.on = true;
        cfg.currencies.addAll(Arrays.asList("USD", "EUR"));
        CashBox.Input i1 = new CashBox.Input();
        i1.kind = CashBox.IN;
        i1.currency = "USD";
        i1.amount = 100;
        i1.when = T0;
        i1.category = "أخرى";
        check(CashBox.validate(i1, cfg, T0) == null, "استلام عام صالح");
        i1.currency = "TRY";
        check(CashBox.validate(i1, cfg, T0) != null, "عملة خارج الصندوق مرفوضة");
        i1.currency = "USD";
        i1.when = T0 + 3600000;
        check(CashBox.validate(i1, cfg, T0) != null, "تاريخ في المستقبل مرفوض");
        i1.when = T0;
        cfg.strict = true;
        check(CashBox.validate(i1, cfg, T0).contains("الربط الإلزامي"), "الربط الإلزامي يرفض الاستلام بلا مكتب");
        i1.officeId = 5L;
        check(CashBox.validate(i1, cfg, T0) == null, "ويقبله لحساب مكتب");
        CashBox.Input e1 = new CashBox.Input();
        e1.kind = CashBox.EXPENSE;
        e1.currency = "EUR";
        e1.amount = 5;
        e1.when = T0;
        e1.category = "كهرباء وماء";
        check(CashBox.validate(e1, cfg, T0) == null, "المصروف مسموح مع الربط الإلزامي حين يُسمح بالداخلية");
        cfg.allowInternal = false;
        check(CashBox.validate(e1, cfg, T0) != null, "ومرفوض حين لا يُسمح");
        e1.officeId = 3L;
        cfg.allowInternal = true;
        check(CashBox.validate(e1, cfg, T0) != null, "المصروف لا يُربط بمكتب");
        CashBox.Input x1 = new CashBox.Input();
        x1.kind = CashBox.EXCHANGE;
        x1.currency = "USD";
        x1.toCurrency = "USD";
        x1.amount = 10;
        x1.toAmount = 9;
        x1.when = T0;
        check(CashBox.validate(x1, cfg, T0) != null, "الصرف لعملة نفسها مرفوض");
        x1.toCurrency = "EUR";
        check(CashBox.validate(x1, cfg, T0) == null, "الصرف بين عملتين صالح");
        CashBox.Input f1 = new CashBox.Input();
        f1.kind = CashBox.OUT;
        f1.currency = "USD";
        f1.amount = 10;
        f1.when = T0;
        f1.officeId = 2L;
        f1.fee = 3;
        check(CashBox.validate(f1, cfg, T0) != null, "أجور بلا جهة (لنا/علينا) مرفوضة");
        f1.feeSide = CashBox.FEE_THEM;
        check(CashBox.validate(f1, cfg, T0) == null, "أجور علينا صالحة");
        CashBox.Config off = new CashBox.Config();
        check(CashBox.validate(f1, off, T0).contains("غير مُعَد"), "الصندوق غير المعَد يرفض");

        System.out.println("== الإعدادات والبنود");
        Map<String, String> st = new HashMap<>();
        st.put("cash_on", "1");
        st.put("cash_currencies", "USD,EUR,USD");
        st.put("cash_cats_exp", "حراسة,إيجار المحل");
        st.put("cash_allow_internal", "0");
        CashBox.Config c2 = CashBox.Config.from(st);
        check(c2.on && c2.currencies.equals(Arrays.asList("USD", "EUR")) && !c2.allowInternal && c2.name.equals(CashBox.DEFAULT_NAME), "قراءة الإعدادات بلا تكرار");
        check(c2.expenseCats.indexOf("حراسة") == c2.expenseCats.size() - 2 && c2.expenseCats.get(c2.expenseCats.size() - 1).equals("أخرى")
                && Collections.frequency(c2.expenseCats, "إيجار المحل") == 1, "البند الجديد قبل «أخرى» ولا يتكرر الموجود");

        System.out.println("== التقرير");
        CashBox.ReportSpec sp = new CashBox.ReportSpec();
        Map<String, Integer> dec = new HashMap<>();
        dec.put("USD", 2);
        dec.put("EUR", 2);
        Map<Long, String> names = new HashMap<>();
        names.put(9L, "النور");
        Report rep = CashBox.report(ms, sp, dec, names, "الصندوق", "مكتب");
        boolean hasSum = false, hasExp = false, hasOff = false, hasStmt = false;
        for (Report.Section s : rep.sections) {
            if (s.heading.equals("خلاصة الصندوق")) hasSum = true;
            if (s.heading.equals("المصاريف حسب البند")) hasExp = true;
            if (s.heading.equals("الصندوق مع المكاتب")) hasOff = s.rows.size() == 1 && Report.text(s.rows.get(0).cells[0]).equals("النور");
            if (s.heading.startsWith("كشف الصندوق — USD")) {
                Report.Row last = s.rows.get(s.rows.size() - 1);
                hasStmt = ((Report.Money) last.cells[6]).v == usd;
            }
        }
        check(hasSum && hasExp && hasOff && hasStmt, "أقسام التقرير: الخلاصة، المصاريف، مع المكاتب (النور فقط: المعلّقة لا تُحسب)، والكشف ينتهي بالرصيد");
        String csv = ReportBuilder.csv(rep);
        check(csv.contains("خلاصة الصندوق") && csv.contains("إيجار المحل"), "التقرير يُصدَّر CSV");

        System.out.println("== عدّاد واتساب اليومي");
        WaQuota q = new WaQuota();
        boolean ok = true;
        for (int k = 0; k < 20; k++) ok &= q.use(WaQuota.key("رسالة " + k), 20, "20261020");
        check(ok && !q.allows(WaQuota.key("رسالة 99"), 20, "20261020") && !q.use(WaQuota.key("رسالة 99"), 20, "20261020"), "20 رسالة ثم الإدخال اليدوي");
        check(q.use(WaQuota.key("  رسالة   5 "), 20, "20261020") && q.used("20261020") == 20, "الرسالة نفسها (ولو بمسافات مختلفة) لا تُحتسب مرتين");
        check(q.allows(WaQuota.key("رسالة 99"), 20, "20261021") && q.used("20261021") == 0, "يوم جديد: يبدأ العد من جديد");
        WaQuota q2 = WaQuota.parse(q.serialize());
        check(q2.day.equals(q.day) && q2.seen.equals(q.seen), "الحفظ والقراءة");
        check(q.allows("x", Entitlements.UNLIMITED, "20261020"), "الكاملة: بلا حد");

        System.out.println("== ترتيب المكاتب");
        List<Model.Office> offs = new ArrayList<>();
        String[] nm = {"ياسمين", "الأمل", "باب الحارة", "تيسير"};
        for (int k = 0; k < nm.length; k++) {
            Model.Office o = new Model.Office();
            o.id = k + 1;
            o.name = nm[k];
            o.active = true;
            offs.add(o);
        }
        offs.get(2).pinned = true;
        OfficeOrder.Inputs oi = new OfficeOrder.Inputs();
        oi.usdNet.put(1L, new java.math.BigDecimal("-5000"));
        oi.usdNet.put(2L, new java.math.BigDecimal("300"));
        oi.usdNet.put(4L, new java.math.BigDecimal("1200"));
        oi.usage.put(2L, new long[]{40, 100});
        oi.usage.put(4L, new long[]{40, 300});
        oi.usage.put(1L, new long[]{2, 900});
        oi.opened.put(4L, 50L);
        oi.opened.put(1L, 90L);
        OfficeOrder.sort(offs, OfficeOrder.BIGGEST, oi);
        check(ids(offs).equals("3,1,4,2"), "الأكبر رصيدًا (بالقيمة المطلقة للميزان) والمثبّت أولًا: " + ids(offs));
        OfficeOrder.sort(offs, OfficeOrder.SMALLEST, oi);
        check(ids(offs).equals("3,2,4,1"), "الأصغر رصيدًا: " + ids(offs));
        OfficeOrder.sort(offs, OfficeOrder.MOST_USED, oi);
        check(ids(offs).equals("3,4,2,1"), "الأكثر استخدامًا (آخر 90 يومًا ثم الكل): " + ids(offs));
        OfficeOrder.sort(offs, OfficeOrder.LAST_OPENED, oi);
        check(ids(offs).equals("3,1,4,2"), "آخر ما فتحته: " + ids(offs));
        OfficeOrder.sort(offs, OfficeOrder.ALPHA, oi);
        check(offs.get(1).name.equals("الأمل") && offs.get(0).pinned, "أبجديًا بعد المثبّت");
        String op = OfficeOrder.touchOpened("1:5,2:6", 2, 99);
        check(op.equals("2:99,1:5") && OfficeOrder.parseOpened(op).get(2L) == 99, "سجل آخر فتح: الأحدث أولًا بلا تكرار");

        // 9.0.1: البيع والشراء، والجرد، والصناديق، والعملاء
        Map<String, Integer> decs = new java.util.HashMap<>();
        decs.put("USD", 2);
        decs.put("EUR", 2);
        decs.put("SYP", 0);
        check(CashBox.counterAmount(100000, 2, "0.92", 2) == 92000, "المقابل: 1,000 × 0.92 = 920.00");
        check(CashBox.counterAmount(10000, 2, "13,500", 0) == 1350000, "المقابل بعملة بلا كسور: 100 × 13,500 = 1,350,000 SYP");
        check(CashBox.counterAmount(10000, 2, "abc", 2) == null && CashBox.counterAmount(10000, 2, "0", 2) == null, "سعر غير صالح أو صفر يُرفض");
        Map<String, java.math.BigDecimal> rt = new java.util.HashMap<>();
        rt.put("EUR", new java.math.BigDecimal("1.08"));
        rt.put("SYP", new java.math.BigDecimal("0.0000769230769"));
        check(CashBox.crossRate("USD", "EUR", rt).toPlainString().equals("0.925926") && CashBox.crossRate("USD", "SYP", rt).toPlainString().equals("13000"),
                "السعر المقترح: 1 USD = 0.925926 EUR و13000 SYP: " + CashBox.crossRate("USD", "SYP", rt).toPlainString());
        CashBox.Input sl = new CashBox.Input();
        sl.kind = CashBox.EXCHANGE;
        sl.side = CashBox.SELL;
        sl.currency = "USD";
        sl.amount = 10000;
        sl.toCurrency = "EUR";
        sl.toAmount = 9200;
        check(CashBox.changes(sl).get("USD") == -10000 && CashBox.changes(sl).get("EUR") == 9200, "أثر البيع: USD −100 وEUR +92");
        sl.side = CashBox.BUY;
        check(CashBox.changes(sl).get("USD") == 10000 && CashBox.changes(sl).get("EUR") == -9200 && sl.outgoing()[0].equals("EUR"), "أثر الشراء: USD +100 وEUR −92 (الخارج EUR)");
        CashBox.Config cf = new CashBox.Config();
        cf.on = true;
        cf.currencies.addAll(java.util.Arrays.asList("USD", "EUR"));
        sl.rate = "0.92";
        sl.when = T0;
        check(CashBox.validate(sl, cf, T0 + 1) == null, "الشراء صالح");
        sl.side = "X";
        check(CashBox.validate(sl, cf, T0 + 1) != null, "جهة غير معروفة تُرفض");
        List<CashBox.CountLine> cl = new ArrayList<>();
        cl.add(new CashBox.CountLine("USD", 100000, 90000));   // عجز 100
        cl.add(new CashBox.CountLine("EUR", 50000, 59200));    // زيادة 92 ≈ 99.36$
        cl.add(new CashBox.CountLine("SYP", 0, 0));
        List<CashBox.Suggestion> sug = CashBox.analyze(cl, rt, decs);
        check(sug.size() == 1 && sug.get(0).soldCur.equals("USD") && sug.get(0).gotCur.equals("EUR") && sug.get(0).rate.toPlainString().equals("0.92"),
                "زيادة EUR تعادل عجز USD = بيع 100 USD بسعر 0.92");
        check(CashBox.netUsd(cl, rt, decs, null).compareTo(new java.math.BigDecimal("-0.64")) == 0, "صافي الجرد بالدولار: −0.64 " + CashBox.netUsd(cl, rt, decs, null));
        cl.set(1, new CashBox.CountLine("EUR", 50000, 55000));
        check(CashBox.analyze(cl, rt, decs).isEmpty(), "زيادة 50 EUR (54$) لا تعادل عجز 100$: لا اقتراح");
        List<CashBox.CountLine> rj = CashBox.linesFrom(CashBox.linesJson(cl));
        check(rj.size() == 3 && rj.get(1).counted == 55000 && rj.get(0).diff() == -10000, "سطور الجرد تُحفظ وتُقرأ كما هي");
        Map<String, String> stb = new java.util.HashMap<>();
        stb.put("cash_on", "1");
        stb.put("cash_currencies", "USD,EUR");
        stb.put("cash_boxes", "[{\"id\":2,\"name\":\"الفرع\",\"cur\":\"USD\",\"active\":true},{\"id\":3,\"name\":\"قديم\",\"cur\":\"EUR\",\"active\":false}]");
        List<CashBox.Config> bx = CashBox.Config.boxes(stb);
        check(bx.size() == 3 && bx.get(1).name.equals("الفرع") && bx.get(1).currencies.equals(java.util.Arrays.asList("USD")) && !bx.get(2).active,
                "الصناديق: الرئيسي + الفرع + المؤرشف");
        check(CashBox.Config.boxes(CashBox.Config.boxesJson(bx).isEmpty() ? stb : stb).size() == 3 && CashBox.Config.boxesJson(bx).contains("الفرع"), "حفظ قائمة الصناديق");
        List<ClientBook.Move> km = new ArrayList<>();
        ClientBook.Move k1 = new ClientBook.Move();
        k1.currency = "EUR";
        k1.amount = 10000;
        k1.dir = -1;
        km.add(k1);
        ClientBook.Move k2 = new ClientBook.Move();
        k2.currency = "EUR";
        k2.amount = 10000;
        k2.dir = 1;
        k2.kind = ClientBook.CASH;
        k2.cashCurrency = "USD";
        k2.cashAmount = 10800;
        k2.cashDir = -1;
        k2.rate = ClientBook.rateOf(10000, 2, 10800, 2);
        km.add(k2);
        check(ClientBook.balances(km).isEmpty() && "1.08".equals(k2.rate), "العميل: له 100 EUR فأخذ 108 USD = حسابه صفر، والسعر 1.08");
        check(ClientBook.text(k2, decs).contains("108") && ClientBook.text(k2, decs).contains("(1 EUR = 1.08 USD)"), "نص الكشف: " + ClientBook.text(k2, decs));
        k2.status = CashBox.CANCELLED;
        check(ClientBook.balances(km).get("EUR") == -10000, "إلغاء التسليم يعيد له 100 EUR (علينا)");
        Map<String, Long> kbal = new java.util.HashMap<>();
        kbal.put("EUR", -10000L);
        check(ClientBook.validateSwap(kbal, "EUR", 20000, "USD", 1) == null ? false : true, "التبديل أكثر من الرصيد يُرفض");

        System.out.println("\n" + passes + " نجح، " + fails + " فشل");
        if (fails > 0) System.exit(1);
    }

    static String ids(List<Model.Office> l) {
        StringBuilder b = new StringBuilder();
        for (Model.Office o : l) b.append(b.length() > 0 ? "," : "").append(o.id);
        return b.toString();
    }
}
