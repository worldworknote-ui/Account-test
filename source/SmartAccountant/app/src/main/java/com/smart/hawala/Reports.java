package com.smart.hawala;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * يبني محتوى التقارير من البيانات. لا يرسم ولا يكتب ملفات: الناتج نموذج {@link Report} يُحوَّل إلى PDF أو Excel.
 * منطق بحت بدون أندرويد، ومُختبر على الكمبيوتر.
 */
public final class Reports {
    private Reports() {}

    // ===================================================================================
    // كشف المكتب
    // ===================================================================================

    public static final class StatementInput {
        public String officeName = "";
        public String issuer = "";
        public String periodLabel = "كل الفترات";
        public boolean periodHasStart;
        public boolean selectedOnly;
        public long generatedAt = Clock.now();
        public Map<String, String> currencyNames = new HashMap<>();
        public Map<String, Integer> decimals = new HashMap<>();
        /** أسطر الفترة (ولأي تحديد: أسطر الحركات المحددة فقط). */
        public List<Model.Line> lines = new ArrayList<>();
        /** الرصيد قبل بداية الفترة لكل عملة. */
        public Map<String, Long> opening = new HashMap<>();
        /** الرصيد الحالي الكامل لكل عملة. */
        public Map<String, Long> fullBalance = new HashMap<>();
        /** رقم الحركة ← أرقام صورها (عند تضمين الصور). */
        public Map<Long, List<Long>> attachments;
        /** العملات المطلوبة فقط (null = كل العملات). */
        public Set<String> onlyCurrencies;
        /** عنوان سطر الرصيد الافتتاحي ومربع الملخص (مثل «رصيد آخر مطابقة»). */
        public String openingLabel = "الرصيد السابق (قبل بداية الفترة)";
        public String openingKv = "الرصيد في بداية الفترة";
        public String openingCol = "الرصيد السابق";
    }

    static int dec(Map<String, Integer> m, String code) {
        Integer d = m.get(code);
        return d == null ? 2 : d;
    }

    static String describe(Model.Line l, ExportStyle st) {
        List<String> parts = new ArrayList<>();
        String ref = st.showRef && l.txUuid != null ? "مرجع: " + Model.shortRef(l.txUuid) : "";
        String cancelled = l.cancelledTx() ? " (ملغاة)" : "";
        switch (l.kind) {
            case "OPENING":
                return "رصيد سابق مسجّل عند إضافة المكتب";
            case "FEE":
                parts.add("أجرة: " + Model.nz(l.feeType) + cancelled);
                if (st.showPerson && !Model.nz(l.person).isEmpty()) parts.add(l.person);
                parts.add(ref);
                return Model.join(parts, "\n");
            case "REVERSAL":
                String who = st.showPerson && !Model.nz(l.person).isEmpty() ? ": " + l.person : "";
                parts.add("قيد عكسي لإلغاء حركة" + who);
                parts.add(ref);
                return Model.join(parts, "\n");
            default:
                StringBuilder first = new StringBuilder();
                if (st.showPerson && !Model.nz(l.person).isEmpty()) first.append(l.person);
                if (first.length() == 0) first.append(Model.TYPE_DEBIT.equals(l.txType) ? "حركة لنا" : "حركة علينا");
                first.append(cancelled);
                // 12.4: حركة خاصة محسوبة في الرصيد
                if (!Model.nz(l.special).isEmpty() && !l.cancelledTx()) first.append(" — معلّقة: ").append(l.special);
                parts.add(first.toString());
                // رسائل واتساب الملصقة تُختصر في الكشف (بلا تحية ودعاء ولا تكرار للاسم)؛ النص الكامل يبقى في تفاصيل الحركة
                if (st.showDesc) parts.add(MessageParser.compact(l.description, st.showPerson ? l.person : null));
                if (st.showNotes && !Model.nz(l.notes).isEmpty()) parts.add("ملاحظة: " + l.notes);
                parts.add(ref);
                return Model.join(parts, "\n");
        }
    }

    public static Report statement(StatementInput in, ExportStyle st) {
        Report r = new Report();
        r.title = st.title + ": " + in.officeName;
        if (!in.issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + in.issuer.trim());
        for (String line : st.issuerInfo.split("\n")) if (!line.trim().isEmpty()) r.headerLines.add(line.trim());
        r.headerLines.add("الفترة: " + in.periodLabel + "   •   تاريخ الإصدار: " + Model.date(in.generatedAt));
        if (in.selectedOnly) r.headerLines.add("يحتوي هذا الكشف على الحركات المحددة فقط، والمجاميع لها وحدها.");
        r.footer = st.footer;
        r.signature = st.signature;
        r.fileBase = "كشف-" + in.officeName;
        if (in.onlyCurrencies != null && in.onlyCurrencies.size() == 1) r.fileBase += "-" + in.onlyCurrencies.iterator().next();

        boolean hasOpening = in.periodHasStart && !in.selectedOnly;
        Map<String, List<Model.Line>> byCur = new TreeMap<>();
        for (Model.Line l : in.lines) {
            if (st.hideCancelled && l.cancelledTx()) continue;
            List<Model.Line> list = byCur.get(l.currency);
            if (list == null) {
                list = new ArrayList<>();
                byCur.put(l.currency, list);
            }
            list.add(l);
        }
        Set<String> codes = new TreeSet<>(byCur.keySet());
        if (!in.selectedOnly) {
            for (Map.Entry<String, Long> e : in.fullBalance.entrySet()) if (e.getValue() != 0) codes.add(e.getKey());
            if (hasOpening) for (Map.Entry<String, Long> e : in.opening.entrySet()) if (e.getValue() != 0) codes.add(e.getKey());
        }
        if (in.onlyCurrencies != null) {
            codes.retainAll(in.onlyCurrencies);
            if (in.onlyCurrencies.size() == 1) r.headerLines.add("كشف بعملة واحدة: " + in.onlyCurrencies.iterator().next());
        }

        Report.Section summary = null;
        if (st.showSummary && !codes.isEmpty()) {
            summary = r.section("ملخص الأرصدة", "ملخص");
            summary.col("العملة", 1.4f, Report.TEXT);
            if (hasOpening) summary.col(in.openingCol, 1.5f, Report.BAL);
            summary.col("مجموع لنا", 1.3f, Report.MONEY).col("مجموع علينا", 1.3f, Report.MONEY);
            summary.col(in.selectedOnly ? "صافي المحدد" : "الرصيد الختامي", 1.5f, Report.BAL);
            if (hasOpening || in.selectedOnly) summary.col("الرصيد الحالي الكامل", 1.5f, Report.BAL);
        }

        for (String code : codes) {
            int dec = dec(in.decimals, code);
            List<Model.Line> lines = byCur.containsKey(code) ? byCur.get(code) : new ArrayList<Model.Line>();
            long opening = hasOpening && in.opening.containsKey(code) ? in.opening.get(code) : 0;
            long full = in.fullBalance.containsKey(code) ? in.fullBalance.get(code) : 0;
            long sumUs = 0, sumThem = 0, feeUs = 0, feeThem = 0;
            for (Model.Line l : lines) {
                sumUs += l.debit;
                sumThem += l.credit;
                if ("FEE".equals(l.kind)) {
                    feeUs += l.debit;
                    feeThem += l.credit;
                }
            }
            long closing = opening + sumUs - sumThem;

            if (summary != null) {
                List<Object> cells = new ArrayList<>();
                cells.add(code + (in.currencyNames.get(code) == null ? "" : " — " + in.currencyNames.get(code)));
                if (hasOpening) cells.add(new Report.Bal(opening, dec));
                cells.add(new Report.Money(sumUs, dec));
                cells.add(new Report.Money(sumThem, dec));
                cells.add(new Report.Bal(closing, dec));
                if (hasOpening || in.selectedOnly) cells.add(new Report.Bal(full, dec));
                summary.row(Report.NORMAL, cells.toArray());
            }

            String name = in.currencyNames.get(code);
            Report.Section s = r.section("العملة: " + code + (name == null ? "" : " — " + name), code);
            if (st.showSummary) {
                if (hasOpening) s.kv(in.openingKv, new Report.Bal(opening, dec), false);
                s.kv(in.selectedOnly ? "مجموع لنا (المحدد)" : "مجموع لنا خلال الفترة", new Report.Money(sumUs, dec), false);
                s.kv(in.selectedOnly ? "مجموع علينا (المحدد)" : "مجموع علينا خلال الفترة", new Report.Money(sumThem, dec), false);
                if (feeUs > 0) s.kv("منها أجور لنا", new Report.Money(feeUs, dec), false);
                if (feeThem > 0) s.kv("منها أجور علينا", new Report.Money(feeThem, dec), false);
                s.kv(in.selectedOnly ? "صافي البنود المحددة" : "الرصيد في نهاية الفترة", new Report.Bal(closing, dec), true);
                if (hasOpening || in.selectedOnly) s.kv("الرصيد الحالي الكامل للحساب", new Report.Bal(full, dec), false);
            }
            boolean running = st.showRunning && !in.selectedOnly;
            s.col("التاريخ", 1.15f, Report.DATE).col("البيان", 3.3f, Report.TEXT).col("لنا", 1.15f, Report.MONEY).col("علينا", 1.15f, Report.MONEY);
            if (running) s.col("الرصيد", 1.45f, Report.BAL);

            List<Report.Row> data = new ArrayList<>();
            long run = opening;
            boolean shade = false;
            for (Model.Line l : lines) {
                run += l.debit - l.credit;
                List<Object> cells = new ArrayList<>();
                cells.add(new Report.DateV(l.createdAt));
                cells.add(describe(l, st));
                cells.add(l.debit > 0 ? new Report.Money(l.debit, dec) : "");
                cells.add(l.credit > 0 ? new Report.Money(l.credit, dec) : "");
                if (running) cells.add(new Report.Bal(run, dec));
                Report.Row row = new Report.Row(l.cancelledTx() ? Report.MUTED : (shade ? Report.SHADED : Report.NORMAL), cells.toArray());
                if (in.attachments != null && "PRINCIPAL".equals(l.kind) && l.txId != null) {
                    List<Long> ids = in.attachments.get(l.txId);
                    if (ids != null && !ids.isEmpty()) row.images = ids;
                }
                data.add(row);
                shade = !shade;
            }
            Report.Row openRow = null;
            if (hasOpening) {
                List<Object> cells = new ArrayList<>();
                cells.add("");
                cells.add(in.openingLabel);
                cells.add(opening > 0 ? new Report.Money(opening, dec) : "");
                cells.add(opening < 0 ? new Report.Money(-opening, dec) : "");
                if (running) cells.add(new Report.Bal(opening, dec));
                openRow = new Report.Row(Report.SHADED, cells.toArray());
            }
            if (st.newestFirst) Collections.reverse(data);
            if (openRow != null && !st.newestFirst) s.rows.add(openRow);
            s.rows.addAll(data);
            if (openRow != null && st.newestFirst) s.rows.add(openRow);
            if (lines.isEmpty()) {
                List<Object> cells = new ArrayList<>();
                cells.add("");
                cells.add("لا توجد حركات بهذه العملة في الفترة");
                cells.add("");
                cells.add("");
                if (running) cells.add("");
                s.row(Report.MUTED, cells.toArray());
            }
            List<Object> tot = new ArrayList<>();
            tot.add("");
            tot.add(in.selectedOnly ? "مجموع المحدد" : "المجموع والرصيد الختامي");
            tot.add(new Report.Money(sumUs, dec));
            tot.add(new Report.Money(sumThem, dec));
            if (running) tot.add(new Report.Bal(closing, dec));
            s.row(Report.TOTAL, tot.toArray());
        }
        if (codes.isEmpty()) {
            Report.Section s = r.section("لا توجد بيانات", "كشف");
            s.note = in.selectedOnly ? "الحركات المحددة لا تحتوي على أسطر قيود." : "لا توجد حركات ولا أرصدة لهذا المكتب في هذه الفترة.";
        }
        return r;
    }

    // ===================================================================================
    // أرصدة كل المكاتب
    // ===================================================================================

    public static Report balances(List<Model.OfficeTotals> offices, Map<String, Integer> decimals, Map<String, String> names,
                                  boolean includeZero, String issuer, long generatedAt) {
        Report r = new Report();
        r.title = "أرصدة كل المكاتب";
        if (issuer != null && !issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + issuer.trim());
        r.headerLines.add("تاريخ الإصدار: " + Model.date(generatedAt));
        r.fileBase = "أرصدة-المكاتب";

        Map<Long, Map<String, Long>> net = new LinkedHashMap<>();
        for (Model.OfficeTotals o : offices) {
            Map<String, Long> m = new TreeMap<>();
            for (Map.Entry<String, long[]> e : o.perCurrency.entrySet()) m.put(e.getKey(), e.getValue()[2]);
            net.put(o.officeId, m);
        }
        Map<String, Ledger.CurrencyTotal> totals = Ledger.totals(net);
        Report.Section g = r.section("الإجمالي حسب العملة (بدون تقاصّ بين المكاتب)", "الإجمالي");
        g.col("العملة", 1.4f, Report.TEXT).col("مجموع ما لنا على المكاتب", 1.6f, Report.MONEY).col("مجموع ما علينا للمكاتب", 1.6f, Report.MONEY)
                .col("الصافي", 1.5f, Report.BAL).col("مكاتب لنا", 0.9f, Report.INT).col("مكاتب علينا", 0.9f, Report.INT);
        for (Ledger.CurrencyTotal t : totals.values()) {
            if (t.receivable == 0 && t.payable == 0) continue;
            int d = dec(decimals, t.code);
            g.row(Report.NORMAL, t.code + (names.get(t.code) == null ? "" : " — " + names.get(t.code)), new Report.Money(t.receivable, d),
                    new Report.Money(t.payable, d), new Report.Bal(t.net(), d), (long) t.officesReceivable, (long) t.officesPayable);
        }
        if (g.rows.isEmpty()) g.note = "لا توجد أرصدة.";

        Report.Section s = r.section("رصيد كل مكتب: كل ما لنا، كل ما علينا، ثم الرصيد", "المكاتب");
        s.col("المكتب", 2.2f, Report.TEXT).col("العملة", 0.9f, Report.TEXT).col("كل ما لنا", 1.4f, Report.MONEY)
                .col("كل ما علينا", 1.4f, Report.MONEY).col("الرصيد", 1.6f, Report.BAL);
        boolean shade = false;
        for (Model.OfficeTotals o : offices) {
            boolean first = true;
            for (Map.Entry<String, long[]> e : o.perCurrency.entrySet()) {
                long[] v = e.getValue();
                if (!includeZero && v[0] == 0 && v[1] == 0 && v[2] == 0) continue;
                int d = dec(decimals, e.getKey());
                s.row(shade ? Report.SHADED : Report.NORMAL, first ? o.name + (o.active ? "" : " (موقوف)") : "", e.getKey(),
                        new Report.Money(v[0], d), new Report.Money(v[1], d), new Report.Bal(v[2], d));
                first = false;
            }
            if (!first) shade = !shade;
        }
        if (s.rows.isEmpty()) s.note = "لا توجد مكاتب بأرصدة.";
        return r;
    }

    // ===================================================================================
    // التقرير اليومي
    // ===================================================================================

    /** stats: العملة ← {لنا، علينا، أجور لنا، أجور علينا، عدد الحركات} */
    public static Report daily(String dayLabel, Map<String, long[]> stats, List<Model.Tx> txs, Map<Long, String> officeNames,
                               Map<String, Integer> decimals, String issuer, long generatedAt) {
        return daily(dayLabel, stats, txs, officeNames, decimals, issuer, generatedAt, null);
    }

    public static Report daily(String dayLabel, Map<String, long[]> stats, List<Model.Tx> txs, Map<Long, String> officeNames,
                               Map<String, Integer> decimals, String issuer, long generatedAt, Map<Long, List<Long>> attachments) {
        Report r = new Report();
        r.title = "التقرير اليومي: " + dayLabel;
        if (issuer != null && !issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + issuer.trim());
        r.headerLines.add("تاريخ الإصدار: " + Model.date(generatedAt) + "   •   الحركات الملغاة غير محسوبة");
        r.fileBase = "تقرير-يومي-" + dayLabel.replace('/', '-');
        Report.Section s = r.section("المجاميع حسب العملة", "المجاميع");
        s.col("العملة", 1f, Report.TEXT).col("حركات لنا", 1.3f, Report.MONEY).col("حركات علينا", 1.3f, Report.MONEY)
                .col("أجور لنا", 1.1f, Report.MONEY).col("أجور علينا", 1.1f, Report.MONEY).col("صافي اليوم", 1.4f, Report.BAL).col("العدد", 0.7f, Report.INT);
        for (Map.Entry<String, long[]> e : new TreeMap<>(stats).entrySet()) {
            long[] v = e.getValue();
            int d = dec(decimals, e.getKey());
            s.row(Report.NORMAL, e.getKey(), new Report.Money(v[0], d), new Report.Money(v[1], d), new Report.Money(v[2], d),
                    new Report.Money(v[3], d), new Report.Bal(v[0] - v[1] + v[2] - v[3], d), v[4]);
        }
        if (s.rows.isEmpty()) s.note = "لا توجد حركات في هذا اليوم.";
        Report.Section l = txSection(r, "حركات اليوم", "الحركات", txs, officeNames, decimals, attachments);
        if (l.rows.isEmpty()) l.note = "لا توجد حركات.";
        return r;
    }

    // ===================================================================================
    // سجل الحركات / نتائج البحث
    // ===================================================================================

    public static Report txList(String title, List<String> filters, List<Model.Tx> txs, Map<Long, String> officeNames,
                                Map<String, Integer> decimals, String issuer, long generatedAt) {
        return txList(title, filters, txs, officeNames, decimals, issuer, generatedAt, null);
    }

    public static Report txList(String title, List<String> filters, List<Model.Tx> txs, Map<Long, String> officeNames,
                                Map<String, Integer> decimals, String issuer, long generatedAt, Map<Long, List<Long>> attachments) {
        Report r = new Report();
        r.title = title;
        if (issuer != null && !issuer.trim().isEmpty()) r.headerLines.add("صادر عن: " + issuer.trim());
        for (String f : filters) if (f != null && !f.isEmpty()) r.headerLines.add(f);
        r.headerLines.add("عدد الحركات: " + txs.size() + "   •   تاريخ الإصدار: " + Model.date(generatedAt));
        r.fileBase = "سجل-الحركات";
        Report.Section s = txSection(r, "الحركات", "الحركات", txs, officeNames, decimals, attachments);
        if (s.rows.isEmpty()) s.note = "لا توجد حركات مطابقة.";
        return r;
    }

    static Report.Section txSection(Report r, String heading, String sheet, List<Model.Tx> txs, Map<Long, String> officeNames,
                                    Map<String, Integer> decimals, Map<Long, List<Long>> attachments) {
        Report.Section s = r.section(heading, sheet);
        s.col("التاريخ", 1.15f, Report.DATE).col("المكتب", 1.5f, Report.TEXT).col("البيان", 2.6f, Report.TEXT)
                .col("لنا", 1.1f, Report.MONEY).col("علينا", 1.1f, Report.MONEY).col("العملة", 0.7f, Report.TEXT)
                .col("الأجرة", 1.3f, Report.TEXT).col("الحالة", 0.8f, Report.TEXT);
        boolean shade = false;
        for (Model.Tx t : txs) {
            int d = dec(decimals, t.currency);
            String desc = Model.join(Model.list(Model.nz(t.person), MessageParser.compact(t.description, t.person), "مرجع: " + Model.shortRef(t.uuid)), "\n");
            String fee = t.hasFee() ? (Model.FEE_US.equals(t.feeType) ? "لنا " : "علينا ") + Model.fmt(t.fee, dec(decimals, t.feeCurrency)) + " " + t.feeCurrency : "";
            boolean us = Model.TYPE_DEBIT.equals(t.type);
            String name = officeNames.get(t.officeId);
            Report.Row row = s.row(t.isActive() ? (shade ? Report.SHADED : Report.NORMAL) : Report.MUTED, new Report.DateV(t.createdAt), name == null ? "—" : name, desc,
                    us ? new Report.Money(t.amount, d) : "", us ? "" : new Report.Money(t.amount, d), t.currency, fee,
                    t.isActive() ? (t.replacesId != null ? "معدّلة" : "نشطة") : t.isPending() ? "بانتظار الاعتماد" : t.isRejected() ? "مرفوضة" : "ملغاة");
            if (attachments != null) {
                List<Long> ids = attachments.get(t.id);
                if (ids != null && !ids.isEmpty()) row.images = ids;
            }
            shade = !shade;
        }
        return s;
    }

    // ===================================================================================
    // ألوان العملات
    // ===================================================================================

    /**
     * يلوّن كل عملة بلونها: أقسام العملة الواحدة (رأس الجدول والعنوان)، والصفوف التي فيها عمود «العملة»
     * (خلفية خفيفة وأرقام بلون العملة). لا يغيّر أي رقم أو نص.
     */
    public static void colorize(Report r, ExportStyle st) {
        if (r == null || st == null || !st.curColors) return;
        for (Report.Section s : r.sections) {
            if (s.accent == 0 && s.heading != null && s.heading.startsWith("العملة: ") && isCode(s.sheetName)) s.accent = st.colorFor(s.sheetName);
            int ci = -1;
            for (int i = 0; i < s.cols.size(); i++) if ("العملة".equals(s.cols.get(i).title)) ci = i;
            if (ci < 0) continue;
            for (Report.Row row : s.rows) {
                if (row.style == Report.MUTED || ci >= row.cells.length) continue;
                String code = codeOf(Report.text(row.cells[ci]));
                if (code != null) row.tint = st.colorFor(code);
            }
        }
    }

    static String codeOf(String cell) {
        String t = cell == null ? "" : cell.trim();
        int i = t.indexOf(' ');
        if (i > 0) t = t.substring(0, i);
        return isCode(t) ? t : null;
    }

    static boolean isCode(String s) {
        return s != null && s.matches("[A-Z0-9]{2,10}");
    }

    // ===================================================================================
    // كشف تجريبي لمعاينة خيارات التصميم
    // ===================================================================================

    public static Report sample(ExportStyle st, String issuer) {
        StatementInput in = new StatementInput();
        in.officeName = "مكتب النور للصرافة";
        in.issuer = issuer == null || issuer.trim().isEmpty() ? "مكتبك" : issuer;
        in.periodLabel = "هذا الشهر";
        in.periodHasStart = true;
        in.currencyNames.put("USD", "دولار أمريكي");
        in.decimals.put("USD", 2);
        in.opening.put("USD", 150000L);
        in.fullBalance.put("USD", 230000L);
        in.currencyNames.put("EUR", "يورو");
        in.decimals.put("EUR", 2);
        in.opening.put("EUR", 50000L);
        in.fullBalance.put("EUR", 30000L);
        long t = Clock.now() - 5L * 86400000L;
        String[][] data = {{"PRINCIPAL", "D", "120000", "أحمد علي", "حوالة إلى حلب"}, {"FEE", "D", "1500", "", ""},
                {"PRINCIPAL", "C", "45000", "سامر خليل", "تسليم نقدي"}, {"PRINCIPAL", "D", "3500", "محمد", "تسوية"},
                {"PRINCIPAL", "C", "20000", "رامي", "تسليم يورو", "EUR"}};
        int i = 0;
        for (String[] x : data) {
            Model.Line l = new Model.Line();
            l.kind = x[0];
            l.currency = x.length > 5 ? x[5] : "USD";
            l.createdAt = t + (i++) * 86400000L;
            long v = Long.parseLong(x[2]);
            if ("D".equals(x[1])) l.debit = v;
            else l.credit = v;
            l.person = x[3];
            l.description = x[4];
            l.txType = "D".equals(x[1]) ? Model.TYPE_DEBIT : Model.TYPE_CREDIT;
            l.feeType = Model.FEE_US;
            l.txUuid = "a1b2c3d4-" + i;
            l.status = "ACTIVE";
            in.lines.add(l);
        }
        Report r = statement(in, st);
        colorize(r, st);
        return r;
    }
}
