import com.smart.hawala.Model;
import com.smart.hawala.OwnerReports;
import com.smart.hawala.Report;
import com.smart.hawala.ReportBuilder;
import com.smart.hawala.XlsxWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 8.1: منشئ التقارير. أرقام اصطناعية محسوبة يدويًا، ثم (اختياريًا) ملف جداول حقيقي:
 * «الأثر على الرصيد» لكل مكتب وعملة = رصيده في دفتر القيود ناقص أرصدته السابقة.
 * وسيط 1 (اختياري): مجلد مخرجات. وسيط 2 (اختياري): ملف \u200e*.tables.json.
 */
public class ReportBuilderTest {
    static int fails, passes;
    static final long DAY = 86400000L;
    static long ids = 1;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static Model.Tx tx(long office, boolean us, long amount, String cur, long at, String desc) {
        Model.Tx t = new Model.Tx();
        t.id = ids++;
        t.uuid = "u" + t.id;
        t.officeId = office;
        t.type = us ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
        t.amount = amount;
        t.currency = cur;
        t.feeType = Model.FEE_NONE;
        t.feeCurrency = cur;
        t.createdAt = at;
        t.status = "ACTIVE";
        t.person = "";
        t.description = desc;
        t.notes = "";
        t.userName = "المدير";
        return t;
    }

    public static void main(String[] a) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        long now = 1791936000000L;   // 2026-10-14
        ReportBuilder.Data d = new ReportBuilder.Data();
        d.now = now;
        d.decimals.put("USD", 2);
        d.decimals.put("EUR", 2);
        d.names.put(1L, "النور");
        d.names.put(2L, "الأمل");
        // النور: 3 حوالات لنا بالدولار (واحدة بأجرة 1% لنا)، حوالة علينا، وحركة «راجع» مستقلة
        Model.Tx a1 = tx(1, true, 1000000, "USD", now - 40 * DAY, "حوالة");
        a1.feeType = Model.FEE_US;
        a1.fee = 10000;
        a1.person = "سامر خليل";
        Model.Tx a2 = tx(1, true, 200000, "USD", now - 20 * DAY, "حوالة");
        Model.Tx a3 = tx(1, false, 500000, "USD", now - 10 * DAY, "تسليم");
        a3.service = "اعتماد";
        a3.dest = "حلب";
        Model.Tx a4 = tx(1, true, 38500, "EUR", now - 5 * DAY, "راجع154 الف");
        Model.Tx a5 = tx(2, false, 300000, "EUR", now - 3 * DAY, "حوالة");
        a5.feeType = Model.FEE_THEM;
        a5.fee = 1500;
        a5.feeCurrency = "EUR";
        Model.Tx gone = tx(1, true, 999900, "USD", now - 15 * DAY, "خطأ");
        gone.status = "CANCELLED";
        Model.Tx old = tx(1, true, 70000, "USD", now - 100 * DAY, "قديمة");
        d.txs.addAll(Arrays.asList(old, a1, gone, a2, a3, a4, a5));
        d.openings.add(new OwnerReports.Opening(1, "USD", 50000, now - 200 * DAY));

        System.out.println("== الخلاصة والقاعدة المحاسبية");
        ReportBuilder.Spec s = new ReportBuilder.Spec();
        s.from = now - 60 * DAY;
        ReportBuilder.Result r = ReportBuilder.compute(s, d);
        ReportBuilder.Agg u = r.totals.get("USD"), e = r.totals.get("EUR");
        check(r.rows.size() == 5, "5 حركات نشطة في 60 يومًا (الملغاة والقديمة خارجها): " + r.rows.size());
        check(u.us == 1200000 && u.them == 500000 && u.net() == 700000 && u.feeUs == 10000 && u.impact() == 710000,
                "الدولار: لنا 12,000 علينا 5,000 الصافي 7,000 + أجرة 1% (100) = أثر 7,100");
        check(e.us == 0 && e.feeUs == 38500 && e.them == 300000 && e.feeThem == 1500 && e.impact() == 38500 - 300000 - 1500,
                "اليورو: «راجع» 385 في الأجور لا في الحوالات، وحوالة علينا 3,000 بأجرة علينا 15");

        System.out.println("== التصفية");
        ReportBuilder.Spec f = new ReportBuilder.Spec();
        f.from = s.from;
        f.direction = ReportBuilder.DIR_THEM;
        check(ReportBuilder.compute(f, d).rows.size() == 2, "علينا فقط: حركتان");
        f = new ReportBuilder.Spec();
        f.kind = ReportBuilder.K_RAJE;
        check(ReportBuilder.compute(f, d).rows.size() == 1, "الراجع فقط: حركة واحدة");
        f = new ReportBuilder.Spec();
        f.kind = ReportBuilder.K_WITH_FEE;
        check(ReportBuilder.compute(f, d).rows.size() == 2, "حوالات بأجرة: 2");
        f = new ReportBuilder.Spec();
        f.text = "سامر";
        check(ReportBuilder.compute(f, d).rows.size() == 1, "بحث بالمستفيد «سامر»");
        f = new ReportBuilder.Spec();
        f.minAmount = new BigDecimal("2000");
        f.maxAmount = new BigDecimal("5000");
        check(ReportBuilder.compute(f, d).rows.size() == 3, "المبلغ بين 2,000 و5,000: 3 (2,000 و5,000 و3,000)");
        f = new ReportBuilder.Spec();
        f.status = ReportBuilder.S_CANCELLED;
        check(ReportBuilder.compute(f, d).rows.size() == 1, "الملغاة فقط: 1");
        f = new ReportBuilder.Spec();
        f.offices.add(2L);
        check(ReportBuilder.compute(f, d).rows.size() == 1, "مكتب واحد");
        f = new ReportBuilder.Spec();
        f.service = "اعتماد";
        f.dest = "حلب";
        check(ReportBuilder.compute(f, d).rows.size() == 1, "الخدمة والوجهة");

        System.out.println("== التجميع والمقارنة والجدول المتقاطع");
        ReportBuilder.Spec g = new ReportBuilder.Spec();
        g.from = now - 30 * DAY;
        g.to = now;
        g.group = ReportBuilder.G_OFFICE;
        g.compare = true;
        g.group2 = ReportBuilder.G_KIND;
        g.measure = ReportBuilder.M_COUNT;
        ReportBuilder.Result rg = ReportBuilder.compute(g, d);
        check(rg.groups.size() == 2 && rg.groups.get("1").get("USD").count == 2, "بالمكتب: النور 2 حركتان بالدولار في 30 يومًا");
        check(rg.previous.get("1").get("USD").count == 1 && rg.previous.get("1").get("USD").us == 1000000, "الفترة السابقة (30 يومًا قبلها): حوالة 10,000");
        check("+100%".equals(ReportBuilder.change(2, 1)) && "جديد".equals(ReportBuilder.change(3, 0)) && "−50%".equals(ReportBuilder.change(1, 2)), "نسب التغيّر");
        long pivotTotal = 0;
        for (Map<String, Map<String, ReportBuilder.Agg>> row : rg.pivot.values())
            for (Map<String, ReportBuilder.Agg> c : row.values()) for (ReportBuilder.Agg x : c.values()) pivotTotal += x.count;
        check(pivotTotal == rg.rows.size(), "مجموع الجدول المتقاطع = عدد الحركات");
        ReportBuilder.Spec m = new ReportBuilder.Spec();
        m.group = ReportBuilder.G_MONTH;
        ReportBuilder.Result rm = ReportBuilder.compute(m, d);
        List<String> keys = new ArrayList<>(rm.groups.keySet());
        check(keys.size() >= 3 && keys.get(0).compareTo(keys.get(keys.size() - 1)) < 0, "بالشهر: مرتبة زمنيًا " + keys);

        System.out.println("== الكشف التفصيلي برصيد متراكم");
        ReportBuilder.Spec k = new ReportBuilder.Spec();
        k.group = ReportBuilder.G_NONE;
        k.offices.add(1L);
        k.currencies.add("USD");
        k.from = now - 60 * DAY;
        Report rep = ReportBuilder.build(k, d, "مكتب الاختبار");
        Report.Section det = rep.sections.get(rep.sections.size() - 1);
        Object last = det.rows.get(det.rows.size() - 1).cells[det.cols.size() - 1];
        // قبل الفترة: رصيد سابق 500 + حوالة قديمة 700 = 1,200؛ ثم +10,000 +100 أجرة +2,000 −5,000 = 8,300
        check(last instanceof Report.Bal && ((Report.Bal) last).v == 830000, "الرصيد المتراكم في آخر سطر 8,300 (" + last + ")");
        check(det.rows.get(0).cells[6].equals("الرصيد قبل الفترة") && ((Report.Bal) det.rows.get(0).cells[det.cols.size() - 1]).v == 120000, "يبدأ برصيد ما قبل الفترة 1,200");

        System.out.println("== القوالب والتصدير");
        g.title = "مقارنة شهرية";
        g.text = "حوالة";
        g.minAmount = new BigDecimal("12.5");
        ReportBuilder.Spec back = ReportBuilder.Spec.fromJson(new JSONObject(g.toJson().toString()));
        check(back.toJson().toString().equals(g.toJson().toString()), "القالب يُحفظ ويُقرأ كما هو");
        Report full = ReportBuilder.build(g, d, "");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        XlsxWriter.write(full, bo, null, TimeZone.getTimeZone("UTC"));
        check(bo.size() > 3000, "Excel بكل الأقسام (" + full.sections.size() + " أقسام، " + bo.size() + " بايت)");
        String csv = ReportBuilder.csv(full);
        check(csv.startsWith("﻿") && csv.contains("الخلاصة"), "CSV بترميز يفهمه Excel");
        check(ReportBuilder.csv(single("=HYPERLINK(1)")).contains("\"'=HYPERLINK(1)\""), "CSV لا ينفّذ معادلات من البيانات");
        if (a.length > 0) Files.write(Paths.get(a[0], "custom-report.xlsx"), bo.toByteArray());

        if (a.length > 1 && Files.exists(Paths.get(a[1]))) real(Paths.get(a[1]));
        System.out.println(fails == 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
        if (fails > 0) System.exit(1);
    }

    static Report single(String text) {
        Report r = new Report();
        Report.Section s = r.section("x", "x");
        s.col("a", 1, Report.TEXT);
        s.row(Report.NORMAL, text);
        return r;
    }

    /** ملف حقيقي: الأثر على الرصيد لكل مكتب = دفتر القيود − الأرصدة السابقة. */
    static void real(Path p) throws Exception {
        System.out.println("== بيانات حقيقية: " + p.getFileName());
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Damascus"));
        OwnerReports.Input in = OwnerReportsTest.load(p);
        JSONObject j = OwnerReportsTest.lastFile;
        ReportBuilder.Data d = new ReportBuilder.Data();
        d.txs.addAll(in.txs);
        d.openings.addAll(in.openings);
        d.names.putAll(in.names);
        d.decimals.putAll(in.decimals);
        d.now = in.now;
        ReportBuilder.Spec s = new ReportBuilder.Spec();
        s.group = ReportBuilder.G_OFFICE;
        long t0 = System.currentTimeMillis();
        ReportBuilder.Result r = ReportBuilder.compute(s, d);
        Report rep = ReportBuilder.build(s, d, "");
        long ms = System.currentTimeMillis() - t0;
        check(ms < 5000, "تقرير بالمكتب على " + d.txs.size() + " حركة في " + ms + " ms (" + rep.dataRows() + " سطرًا)");
        Map<String, Long> ledger = new HashMap<>();
        JSONArray jl = j.getJSONArray("journal_lines");
        for (int i = 0; i < jl.length(); i++) {
            JSONObject l = jl.getJSONObject(i);
            if (!"OFFICE".equals(l.optString("account"))) continue;
            ledger.merge(l.getLong("office_id") + "|" + l.getString("currency"), l.optLong("debit_minor") - l.optLong("credit_minor"), Long::sum);
        }
        for (OwnerReports.Opening o : d.openings) ledger.merge(o.office + "|" + o.currency, -o.signed, Long::sum);
        ledger.values().removeIf(v -> v == 0);
        Map<String, Long> impact = new HashMap<>();
        for (Map.Entry<String, Map<String, ReportBuilder.Agg>> e : r.groups.entrySet())
            for (Map.Entry<String, ReportBuilder.Agg> c : e.getValue().entrySet()) if (c.getValue().impact() != 0) impact.put(e.getKey() + "|" + c.getKey(), c.getValue().impact());
        check(impact.equals(ledger), "الأثر على الرصيد لكل مكتب وعملة = دفتر القيود (" + ledger.size() + " رصيدًا)");
        ReportBuilder.Spec m = new ReportBuilder.Spec();
        m.group = ReportBuilder.G_MONTH;
        m.group2 = ReportBuilder.G_CURRENCY;
        m.measure = ReportBuilder.M_COUNT;
        m.from = in.now - 365 * DAY;
        ReportBuilder.Result rm = ReportBuilder.compute(m, d);
        long n = 0;
        for (Map<String, ReportBuilder.Agg> c : rm.groups.values()) for (ReportBuilder.Agg x : c.values()) n += x.count;
        check(n == rm.rows.size(), "بالشهر × العملة لآخر 12 شهرًا: " + rm.groups.size() + " شهرًا، " + n + " عملية");
    }
}
