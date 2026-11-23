import com.smart.hawala.Model;
import com.smart.hawala.OwnerReports;
import com.smart.hawala.Report;
import com.smart.hawala.XlsxWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 8.0: تقارير صاحب المكتب. بيانات اصطناعية بأرقام معروفة، ثم (اختياريًا) ملف حقيقي \u200e*.tables.json تُقارن أرقامه بالدراسة.
 * وسيط 1: مجلد مخرجات (يُكتب فيه ملف Excel للتقارير). وسيط 2 (اختياري): ملف جداول حقيقي.
 */
public class OwnerReportsTest {
    static int fails = 0, passes = 0;
    static JSONObject lastFile;

    static void check(boolean c, String m) {
        if (c) passes++;
        else fails++;
        System.out.println((c ? "  PASS " : "  FAIL ") + m);
    }

    static final long DAY = 86400000L;
    static long ids = 1;

    static Model.Tx tx(long office, boolean us, long amount, String cur, long at) {
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
        t.description = "";
        t.notes = "";
        t.userName = "";
        return t;
    }

    public static void main(String[] a) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        long now = 1791936000000L;   // 2026-10-14 00:00 UTC
        OwnerReports.Input in = new OwnerReports.Input();
        in.now = now;
        in.decimals.put("USD", 2);
        in.decimals.put("EUR", 2);
        in.names.put(1L, "الأمل");
        in.names.put(2L, "النور");
        in.names.put(3L, "الريان");
        in.names.put(4L, "الريان التجاري");
        in.names.put(5L, "12");

        System.out.println("== عمر الأرصدة (الأقدم يُسدَّد أولًا)");
        // الأمل: لنا 1000 قبل 200 يوم، 500 قبل 100 يوم، 300 قبل 10 أيام، وسدّد 800 قبل 5 أيام → الرصيد 1000
        in.txs.add(tx(1, true, 100000, "USD", now - 200 * DAY));
        in.txs.add(tx(1, true, 50000, "USD", now - 100 * DAY));
        in.txs.add(tx(1, true, 30000, "USD", now - 10 * DAY));
        in.txs.add(tx(1, false, 80000, "USD", now - 5 * DAY));
        List<OwnerReports.Aging> ag = OwnerReports.aging(in, true);
        OwnerReports.Aging g = ag.get(0);
        check(g.balance == 100000 && g.buckets[0] == 30000 && g.buckets[2] == 50000 && g.buckets[3] == 20000 && g.buckets[1] == 0,
                "رصيد 1000: 300 حديثة، 500 من 100 يوم، و200 من 200 يوم " + java.util.Arrays.toString(g.buckets));
        check(g.over90() == 70000 && g.oldestDays == 200, "أقدم من 90 يومًا: 700");
        Model.Tx cancelled = tx(1, true, 999900, "USD", now - 400 * DAY);
        cancelled.status = "CANCELLED";
        in.txs.add(cancelled);
        check(OwnerReports.aging(in, true).get(0).balance == 100000, "الحركة الملغاة خارج الحساب");
        in.openings.add(new OwnerReports.Opening(2, "EUR", -200000, now - 300 * DAY));
        in.txs.add(tx(2, false, 50000, "EUR", now - 20 * DAY));
        List<OwnerReports.Aging> pay = OwnerReports.aging(in, false);
        check(pay.size() == 1 && pay.get(0).balance == 250000 && pay.get(0).buckets[3] == 200000 && pay.get(0).buckets[0] == 50000, "ما علينا: الرصيد السابق يُعمَّر بتاريخه");

        System.out.println("== الحسابات الخاملة");
        in.txs.add(tx(5, true, 12300, "USD", now - 538 * DAY));
        List<OwnerReports.Dormant> dm = OwnerReports.dormant(in, 90);
        check(dm.size() == 1 && dm.get(0).office == 5 && dm.get(0).days == 538, "الحساب «12» بلا حركة منذ 538 يومًا، والنور تحرّك باليورو قبل 20 يومًا فليس متوقفًا");
        Model.Tx eurOnly = tx(1, true, 7700, "EUR", now - 400 * DAY);
        in.txs.add(eurOnly);
        List<OwnerReports.Dormant> dm2 = OwnerReports.dormant(in, 90);
        boolean amal = false;
        for (OwnerReports.Dormant d : dm2) amal |= d.office == 1 && d.balances.containsKey("EUR") && !d.balances.containsKey("USD");
        check(amal, "رصيد يورو متوقف على مكتب نشط بالدولار يُحتسب وحده");
        in.txs.remove(eurOnly);

        System.out.println("== الاتجاهات");
        for (int i = 0; i < 30; i++) in.txs.add(tx(3, false, 1000, "EUR", now - (100 + i) * DAY));   // قبل: 30
        for (int i = 0; i < 10; i++) in.txs.add(tx(3, false, 1000, "EUR", now - (10 + i) * DAY));    // بعد: 10
        for (int i = 0; i < 5; i++) in.txs.add(tx(4, true, 1000, "EUR", now - (2 + i) * DAY));       // جديد
        Map<Long, String> kinds = new HashMap<>();
        for (OwnerReports.Trend t : OwnerReports.trends(in, 90)) kinds.put(t.office, t.kind);
        check("declining".equals(kinds.get(3L)) && "new".equals(kinds.get(4L)), "الريان متراجع (30 → 10)، الريان التجاري جديد " + kinds);

        System.out.println("== الأجور وحجم الحوالة والتركّز والجودة");
        Model.Tx f1 = tx(1, true, 5000, "USD", now - 30 * DAY);
        f1.description = "أجور حوالة";
        Model.Tx f2 = tx(2, false, 9000, "USD", now - 40 * DAY);
        f2.description = "اجور10 الاف";
        Model.Tx f3 = tx(1, true, 200000, "USD", now - 50 * DAY);
        f3.feeType = Model.FEE_THEM;
        f3.fee = 1500;
        f3.feeCurrency = "USD";
        in.txs.add(f1);
        in.txs.add(f2);
        in.txs.add(f3);
        OwnerReports.Fees fe = OwnerReports.fees(in, now - 365 * DAY);
        long[] usd = fe.perCurrency.get("USD");
        check(usd[0] == 5000 && usd[1] == 10500 && fe.fromDescription == 2, "قبضت 50 ودفعت 105 (خانة الأجرة + بيان «أجور») " + java.util.Arrays.toString(usd));
        Map<String, OwnerReports.Size> sz = OwnerReports.sizes(in, now - 365 * DAY);
        check(sz.get("USD").max == 200000 && sz.get("USD").count == 5, "حجم الحوالة دون حركات الأجور: " + sz.get("USD").count);
        Model.Tx notFee = tx(1, false, 2800000, "USD", now - 30 * DAY);
        notFee.description = "عمان مع أجور";
        Model.Tx typo = tx(1, false, 3700, "USD", now - 30 * DAY);
        typo.description = "اجر3700";
        Model.Tx name = tx(1, false, 100, "USD", now - 30 * DAY);
        name.description = "اجرت الشقة";
        check(!OwnerReports.feeRow(notFee) && OwnerReports.feeRow(typo) && !OwnerReports.feeRow(name), "«عمان مع أجور» حوالة لا أجرة، و«اجر3700» أجرة");
        System.out.println("== «راجع» = أجرة (لا إرجاع)");
        Model.Tx r1 = tx(2, true, 38500, "EUR", now - 15 * DAY);
        r1.description = "راجع154 الف";
        Model.Tx r2 = tx(2, false, 6000, "EUR", now - 16 * DAY);
        r2.description = "راجع 40 الف";
        Model.Tx r3 = tx(2, true, 6600, "EUR", now - 17 * DAY);
        r3.description = "راجع33";                       // المذكور بالآلاف: 66 ÷ 33,000 = 0.2%
        Model.Tx r4 = tx(2, true, 34000, "USD", now - 18 * DAY);
        r4.description = "راجع35 الف+ 50125";             // 85,125
        Model.Tx back = tx(2, true, 70000, "EUR", now - 19 * DAY);
        back.description = "حوالة مرتجع";
        Model.Tx call = tx(2, true, 5000, "EUR", now - 19 * DAY);
        call.description = "راجعني بخصوص الحوالة";
        check(OwnerReports.feeRow(r1) && OwnerReports.rajeRow(r1) && !OwnerReports.returnRow(r1), "«راجع154 الف» أجرة لا إرجاع");
        check(OwnerReports.returnRow(back) && !OwnerReports.feeRow(back), "«مرتجع» إرجاع لا أجرة");
        check(!OwnerReports.rajeRow(call) && !OwnerReports.feeRow(call), "«راجعني» ليست راجع");
        check(OwnerReports.rajeBase("راجع154 الف").compareTo(new java.math.BigDecimal(154000)) == 0
                && OwnerReports.rajeBase("راجع35 الف+ 50125").compareTo(new java.math.BigDecimal(85125)) == 0
                && OwnerReports.rajeBase("راجع ٥٠٠٠").compareTo(new java.math.BigDecimal(5000)) == 0 && OwnerReports.rajeBase("راجع بوخارست") == null, "المبلغ المذكور بعد «راجع»");
        check(OwnerReports.rajeRate(r1, 2).compareTo(new java.math.BigDecimal("0.25")) == 0 && OwnerReports.rajeRate(r3, 2).compareTo(new java.math.BigDecimal("0.2")) == 0,
                "النسبة الفعلية: 385 ÷ 154,000 = 0.25% و66 ÷ 33 ألفًا = 0.2%");
        in.txs.add(r1);
        in.txs.add(r2);
        in.txs.add(r3);
        in.txs.add(r4);
        OwnerReports.Fees fr = OwnerReports.fees(in, now - 365 * DAY);
        long[] ke = fr.raje.get("2|EUR");
        check(fr.fromRaje == 4 && ke[0] == 45100 && ke[1] == 6000 && ke[2] == 3, "الراجع مع «النور» باليورو: قبضت 451 ودفعت 60 في 3 حركات");
        check(fr.perCurrency.get("EUR")[0] == 45100 && fr.perCurrency.get("USD")[0] == 5000 + 34000, "الراجع داخل ربح الأجور لكل عملة");
        in.txs.remove(r1);
        in.txs.remove(r2);
        in.txs.remove(r3);
        in.txs.remove(r4);
        OwnerReports.Quality q = OwnerReports.quality(in);
        check(q.similarNames.size() == 1 && q.similarNames.get(0)[0].equals("الريان") && q.numericNames == 1, "أسماء متشابهة («الريان»/«الريان التجاري») واسم رقمي «12»");
        OwnerReports.Concentration c = OwnerReports.concentration(in, 0);
        check(c.ranked.get(0)[0] == 3 && c.total == in.txs.size() - 1, "ترتيب المكاتب بعدد العمليات النشطة");
        check(OwnerReports.norm("أُجورٌ  الإمارات").equals("اجور الامارات"), "توحيد الكتابة للمقارنة");

        Report r = OwnerReports.build(in, "مكتب الاختبار", false);
        check(r.sections.size() >= 12 && r.dataRows() > 10, "التقرير الكامل: " + r.sections.size() + " قسمًا");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        XlsxWriter.write(r, bo, null, TimeZone.getTimeZone("UTC"));
        check(bo.size() > 3000, "يُكتب Excel بكل الأقسام (" + bo.size() + " بايت)");
        if (a.length > 0) Files.write(Paths.get(a[0], "owner-reports.xlsx"), bo.toByteArray());

        if (a.length > 1 && Files.exists(Paths.get(a[1]))) real(Paths.get(a[1]), a[0]);
        System.out.println(fails == 0 ? "ALL " + passes + " PASSED" : fails + " FAILED of " + (passes + fails));
        if (fails > 0) System.exit(1);
    }

    /**
     * ملف جداول حقيقي \u200e*.tables.json (يبقى خارج المشروع): تُبنى كل التقارير عليه ويُتحقق من اتساقها الداخلي
     * (أعمار الديون مجموعها = الرصيد، وما لنا وما علينا = أرصدة المكاتب)، ويُكتب Excel.
     */
    static OwnerReports.Input load(Path p) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Damascus"));   // تواريخ الملف بتوقيت المكتب
        JSONObject j = new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        OwnerReports.Input in = new OwnerReports.Input();
        in.now = 1791838800000L;   // 2026-10-13 00:00 بتوقيت دمشق
        JSONArray cs = j.getJSONArray("currencies");
        for (int i = 0; i < cs.length(); i++) in.decimals.put(cs.getJSONObject(i).getString("code"), cs.getJSONObject(i).getInt("decimals"));
        JSONArray os = j.getJSONArray("offices");
        for (int i = 0; i < os.length(); i++) in.names.put(os.getJSONObject(i).getLong("id"), os.getJSONObject(i).getString("name"));
        JSONArray ts = j.getJSONArray("tx");
        for (int i = 0; i < ts.length(); i++) {
            JSONObject o = ts.getJSONObject(i);
            Model.Tx t = new Model.Tx();
            t.id = o.getLong("id");
            t.uuid = o.optString("uuid");
            t.officeId = o.getLong("office_id");
            t.type = o.getString("type");
            t.amount = o.getLong("amount_minor");
            t.currency = o.getString("currency");
            t.feeType = o.optString("fee_type", Model.FEE_NONE);
            t.fee = o.optLong("fee_minor", 0);
            t.feeCurrency = o.optString("fee_currency", t.currency);
            t.description = o.optString("description", "");
            t.person = o.optString("person", "");
            t.notes = "";
            t.createdAt = o.getLong("created_at");
            t.status = o.optString("status", "ACTIVE");
            t.replacesId = o.isNull("replaces_id") ? null : o.getLong("replaces_id");
            in.txs.add(t);
        }
        JSONArray ob = j.optJSONArray("opening_balances");
        for (int i = 0; ob != null && i < ob.length(); i++) {
            JSONObject o = ob.getJSONObject(i);
            long v = o.getLong("amount_minor");
            in.openings.add(new OwnerReports.Opening(o.getLong("office_id"), o.getString("currency"), "لنا".equals(o.getString("type")) ? v : -v, 0));
        }
        lastFile = j;
        return in;
    }

    static void real(Path p, String out) throws Exception {
        System.out.println("== بيانات حقيقية: " + p.getFileName());
        OwnerReports.Input in = load(p);
        long t0 = System.currentTimeMillis();
        Report r = OwnerReports.build(in, "", true);
        long ms = System.currentTimeMillis() - t0;
        for (String h : OwnerReports.headlines(in)) System.out.println("    " + h);
        check(ms < 5000, "كل التقارير على " + in.txs.size() + " حركة في " + ms + " ms");
        boolean sums = true;
        for (boolean us : new boolean[]{true, false})
            for (OwnerReports.Aging x : OwnerReports.aging(in, us)) {
                long s = 0;
                for (long b : x.buckets) s += b;
                sums &= s == x.balance && x.balance > 0;
            }
        check(sums, "أعمار الديون: مجموع الفئات = الرصيد لكل مكتب وعملة");
        // ما لنا وما علينا في التقرير = رصيد حساب المكتب في دفتر القيود نفسه (journal_lines) لكل مكتب وعملة
        Map<String, Long> ledger = new HashMap<>();
        JSONArray jl = lastFile.getJSONArray("journal_lines");
        for (int i = 0; i < jl.length(); i++) {
            JSONObject l = jl.getJSONObject(i);
            if (!"OFFICE".equals(l.optString("account"))) continue;
            ledger.merge(l.getLong("office_id") + "|" + l.getString("currency"), l.optLong("debit_minor") - l.optLong("credit_minor"), Long::sum);
        }
        ledger.values().removeIf(v -> v == 0);
        Map<String, Long> fromAging = new HashMap<>();
        for (OwnerReports.Aging x : OwnerReports.aging(in, true)) fromAging.put(x.office + "|" + x.currency, x.balance);
        for (OwnerReports.Aging x : OwnerReports.aging(in, false)) fromAging.put(x.office + "|" + x.currency, -x.balance);
        check(ledger.equals(fromAging), "أرصدة التقرير = أرصدة المكاتب في دفتر القيود (عدد الأرصدة " + ledger.size() + ")");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        XlsxWriter.write(r, bo, null, TimeZone.getTimeZone("UTC"));
        Files.write(Paths.get(out, "owner-reports-real.xlsx"), bo.toByteArray());
    }
}
