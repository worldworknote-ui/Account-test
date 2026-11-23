import com.smart.hawala.*;
import java.util.*;
public class ReconTest {
  static int fails = 0;
  static void check(boolean c, String m) { System.out.println((c ? "  PASS " : "  FAIL ") + m); if (!c) fails++; }
  static Model.Line line(String kind, String cur, long t, long d, long c, long tx, String status) {
    Model.Line l = new Model.Line(); l.kind = kind; l.currency = cur; l.createdAt = t; l.debit = d; l.credit = c; l.txId = tx; l.status = status;
    l.person = "شخص " + tx; l.description = "بيان"; l.txUuid = "uuid-" + tx; l.txType = d > 0 ? Model.TYPE_DEBIT : Model.TYPE_CREDIT; l.feeType = Model.FEE_NONE; return l; }
  public static void main(String[] a) throws Exception {
    System.out.println("1) balance message text");
    Map<String, Long> bal = new TreeMap<>(); bal.put("SYP", 5945000L); bal.put("USD", -781900L); bal.put("EUR", 875000L); bal.put("TRY", 0L);
    Map<String, String> names = new HashMap<>(); names.put("SYP", "ليرة سورية ( جديد )"); names.put("USD", "دولار امريكي"); names.put("EUR", "يورو اوربي");
    Map<String, Integer> dec = new HashMap<>(); dec.put("SYP", 2); dec.put("USD", 2); dec.put("EUR", 2); dec.put("TRY", 2);
    Reconcile.Template t = new Reconcile.Template();
    List<Reconcile.Row> rows = Reconcile.rows(bal, names, dec, null, false);
    check(rows.size() == 3, "zero balance hidden by default: " + rows.size());
    String txt = Reconcile.text(t, "كاش شام", "مكتب أبو عمر", 1760090449000L, rows);
    System.out.println("------\n" + txt + "\n------");
    check(txt.startsWith("السلام عليكم ورحمة الله وبركاته"), "greeting first");
    check(txt.contains("*مطابقة رصيد*"), "bold title");
    check(txt.contains("السادة: *كاش شام* المحترمين"), "office salutation");
    // 10.16: الافتراضي: الرقم قبل اسم العملة، بلا أصفار عشرية، وسطر فارغ بين كل عملة
    check(txt.contains("🟢 *59,450* ليرة سورية ( جديد ) لنا"), "10.16 الافتراضي: المبلغ قبل اسم العملة وبلا أصفار عشرية");
    check(txt.contains("🔴 *7,819* دولار امريكي لكم"), "USD 7,819 لكم (السالب = لكم)");
    check(txt.contains("لنا\n\n🔴") || txt.contains("لنا\n\n🟢"), "سطر فارغ بين كل عملة وأخرى");
    check(!txt.contains(".00"), "لا أصفار عشرية في الرسالة");
    Reconcile.Template old = new Reconcile.Template(); old.lineFormat = Reconcile.LINE_NAME_FIRST; old.trimZeros = false; old.gap = false;
    String ot = Reconcile.text(old, "كاش شام", "مكتب أبو عمر", 1760090449000L, rows);
    check(ot.contains("🟢 ليرة سورية ( جديد ): *59,450.00* لنا\n🔴 دولار امريكي: *7,819.00* لكم") || ot.contains("🟢 يورو اوربي: *8,750.00* لنا\n"), "الشكل القديم ما زال متاحًا كما كان");
    check(ot.contains("دولار امريكي: *7,819.00* لكم") && ot.contains("يورو اوربي: *8,750.00* لنا"), "الشكل القديم: الاسم ثم المبلغ بالأصفار");
    check(Reconcile.trimZeros("12.50").equals("12.5") && Reconcile.trimZeros("1,000.00").equals("1,000") && Reconcile.trimZeros("0.00").equals("0")
        && Reconcile.trimZeros("100").equals("100") && Reconcile.trimZeros("1,200").equals("1,200") && Reconcile.trimZeros("0.05").equals("0.05"), "حذف الأصفار لا يمس الكسور الحقيقية ولا الأعداد الصحيحة");
    List<Reconcile.Row> frac = new ArrayList<>(); frac.add(new Reconcile.Row("USD", "دولار", 123450L, 2)); frac.add(new Reconcile.Row("JOD", "دينار", -1500L, 3));
    String ft = Reconcile.text(new Reconcile.Template(), "م", "", 0, frac);
    check(ft.contains("*1,234.5* دولار لنا") && ft.contains("*1.5* دينار لكم"), "كسر حقيقي يبقى: 1,234.5 و1.5");
    Reconcile.Template cu = new Reconcile.Template(); cu.lineFormat = "{كلمة}: {مبلغ} {رمز} — {عملة}"; cu.markPlus = ""; cu.bold = false;
    check(Reconcile.line(cu, frac.get(0)).equals("لنا: 1,234.5 USD — دولار"), "شكل حر يكتبه المستخدم: " + Reconcile.line(cu, frac.get(0)));
    cu.lineFormat = "{علامة} {عملة}\\n{مبلغ} {كلمة}"; cu.markPlus = "";
    check(Reconcile.line(cu, frac.get(0)).equals("دولار\n1,234.5 لنا"), "سطران لكل عملة، والعلامة المحذوفة لا تترك فراغًا");
    cu.lineFormat = "{عملة} {كلمة}";
    check(Reconcile.line(cu, frac.get(0)).contains("1,234.5"), "شكل بلا خانة المبلغ يُرفض ويعود الافتراضي (لا رسالة بلا أرقام)");
    Map<String, String> bad = new HashMap<>(); bad.put("rc_line", "بلا مبلغ");
    check(Reconcile.Template.from(bad).lineFormat.equals(Reconcile.LINE_AMOUNT_FIRST), "قالب محفوظ بلا خانة المبلغ يُصحَّح عند القراءة");
    Reconcile.Template hid = new Reconcile.Template(); hid.showDate = false; hid.showOffice = false; hid.showSeparator = false; hid.markMinus = "⬇️"; hid.zeroWord = "متعادل";
    String ht = Reconcile.text(hid, "كاش شام", "", 1760090449000L, rows);
    check(!ht.contains("التاريخ") && !ht.contains("كاش شام") && !ht.contains(Reconcile.LINE) && ht.contains("⬇️ *7,819*"), "إخفاء التاريخ والمكتب والخط، وعلامة مخصصة");
    hid.separator = "••••"; hid.showSeparator = true; hid.dateLabel = "بتاريخ"; hid.showDate = true;
    ht = Reconcile.text(hid, "كاش شام", "", 1760090449000L, rows);
    check(ht.contains("••••") && ht.contains("بتاريخ " + Model.date(1760090449000L)), "خط فاصل ونص تاريخ مخصصان");
    Reconcile.Template rt = Reconcile.Template.from(hid.toMap());
    check(rt.separator.equals("••••") && !rt.showOffice && rt.markMinus.equals("⬇️") && rt.zeroWord.equals("متعادل") && rt.trimZeros && rt.gap, "الخيارات الجديدة تُحفظ وتُقرأ");
    // موضع «لنا/لكم» من الرقم، واللون
    check(Reconcile.wordPos(Reconcile.LINE_AMOUNT_FIRST) == 2 && Reconcile.wordPos(Reconcile.LINE_WORD_FIRST) == 0 && Reconcile.wordPos(Reconcile.LINE_NAME_FIRST) == 1, "اكتشاف موضع الكلمة في الأشكال الجاهزة");
    String wb = Reconcile.withWordPos(Reconcile.LINE_AMOUNT_FIRST, 0), wa = Reconcile.withWordPos(Reconcile.LINE_AMOUNT_FIRST, 1), we = Reconcile.withWordPos(wb, 2);
    check(wb.equals("{علامة} {كلمة} {مبلغ} {عملة}") && wa.equals("{علامة} {مبلغ} {كلمة} {عملة}") && we.equals(Reconcile.LINE_AMOUNT_FIRST), "نقل «لنا/لكم» قبل الرقم وبعده وآخر السطر: " + wb + " | " + wa);
    Reconcile.Template wp = new Reconcile.Template(); wp.lineFormat = wb; wp.bold = false;
    check(Reconcile.line(wp, frac.get(0)).equals("🟢 لنا 1,234.5 دولار") && wp.wordBefore(), "الكلمة قبل الرقم في النص: " + Reconcile.line(wp, frac.get(0)));
    wp.lineFormat = wa;
    check(Reconcile.line(wp, frac.get(1)).equals("🔴 1.5 لكم دينار") && !wp.wordBefore(), "الكلمة بعد الرقم مباشرة: " + Reconcile.line(wp, frac.get(1)));
    check(Reconcile.withWordPos("{عملة} {مبلغ}", 0).equals("{عملة} {كلمة} {مبلغ}"), "شكل بلا كلمة: تُضاف في الموضع المطلوب");
    Reconcile.Template col = new Reconcile.Template();
    check(col.color(1) == 0xFF1B7846 && col.color(-1) == 0xFFAD2E36, "اللونان الافتراضيان أخضر وأحمر");
    col.colorPlus = "#1F5FBF"; col.colorMinus = "خطأ";
    check(col.color(1) == 0xFF1F5FBF && col.color(-1) == 0xFFAD2E36, "لون مختار يُطبَّق، ولون غير صالح يعود للافتراضي");
    col.colored = false;
    check(col.color(1) == col.color(-1), "بلا تلوين: لون واحد");
    Reconcile.Template cr = Reconcile.Template.from(col.toMap());
    check(cr.colorPlus.equals("#1F5FBF") && !cr.colored, "اللون وخيار التلوين يُحفظان");
    Reconcile.Template legacy = Reconcile.Template.from(new HashMap<String, String>() {{ put("rc_title", "كشف"); put("rc_bold", "0"); }});
    check(legacy.title.equals("كشف") && !legacy.bold && legacy.gap && legacy.trimZeros && legacy.lineFormat.equals(Reconcile.LINE_AMOUNT_FIRST), "قالب محفوظ من إصدار أقدم: نصوصه تبقى والجديد بقيمه الافتراضية");
    check(txt.contains("يرجى المطابقة على الأرصدة مع المحاسب بشكل دائم 🔍"), "footer");
    check(txt.trim().endsWith("مكتب أبو عمر"), "issuer last");
    check(!txt.contains("TRY"), "zero currency not listed");
    Set<String> only = new HashSet<>(Arrays.asList("USD", "TRY"));
    List<Reconcile.Row> r2 = Reconcile.rows(bal, names, dec, only, true);
    check(r2.size() == 2 && r2.get(0).code.equals("TRY") && r2.get(0).sign() == 0, "selected currencies incl. zero when asked");
    t.bold = false; t.showIssuer = false; t.greeting = ""; t.ornament = "";
    String plain = Reconcile.text(t, "كاش شام", "مكتب", 1760090449000L, rows);
    check(!plain.contains("*") && plain.startsWith("مطابقة رصيد") && !plain.contains("مكتب\n") && !plain.trim().endsWith("مكتب"), "plain variant: no bold, no greeting, no issuer");
    String empty = Reconcile.text(new Reconcile.Template(), "x", "", 0, new ArrayList<Reconcile.Row>());
    check(empty.contains("مسدَّد بالكامل"), "no balances -> settled line");
    Reconcile.Template t2 = Reconcile.Template.from(new Reconcile.Template().toMap());
    check(t2.plusWord.equals("لنا") && t2.minusWord.equals("لكم") && t2.bold, "template round-trips via settings map");
    Map<String, String> m = new HashMap<>(); m.put("rc_plus", " "); m.put("rc_title", "");
    Reconcile.Template t3 = Reconcile.Template.from(m);
    check(t3.plusWord.equals("لنا") && t3.title.equals("مطابقة رصيد"), "blank words fall back to defaults");

    System.out.println("2) statement after reconciliation (opening = reconciled balance)");
    Reports.StatementInput in = new Reports.StatementInput();
    in.officeName = "كاش شام"; in.decimals = dec; in.currencyNames = names; in.periodHasStart = true; in.periodLabel = "بعد مطابقة";
    in.opening.put("USD", 100000L); in.opening.put("SYP", 500000L);
    in.lines.add(line("PRINCIPAL", "USD", 2000, 20000, 0, 7, "ACTIVE"));
    in.lines.add(line("REVERSAL", "USD", 3000, 0, 5000, 3, "CANCELLED"));   // إلغاء حركة قديمة
    in.lines.add(line("PRINCIPAL", "SYP", 2500, 0, 100000, 8, "ACTIVE"));
    in.fullBalance.put("USD", 115000L); in.fullBalance.put("SYP", 400000L);
    in.openingLabel = "رصيد المطابقة بتاريخ X"; in.openingKv = "رصيد آخر مطابقة"; in.openingCol = "رصيد المطابقة";
    ExportStyle st = new ExportStyle(); st.hideCancelled = false;
    Report r = Reports.statement(in, st);
    Report.Section usd = null, syp = null, sum = null;
    for (Report.Section s : r.sections) { if ("USD".equals(s.sheetName)) usd = s; if ("SYP".equals(s.sheetName)) syp = s; if ("ملخص".equals(s.sheetName)) sum = s; }
    check(usd != null && syp != null, "both currencies present");
    check(usd.rows.get(0).cells[1].equals("رصيد المطابقة بتاريخ X"), "opening row labelled with reconciliation");
    Object closing = usd.rows.get(usd.rows.size() - 1).cells[4];
    check(closing.toString().contains("1,150"), "USD closing = 1000 + 200 - 50 = 1,150.00 -> " + closing);
    check(sum.cols.get(1).title.equals("رصيد المطابقة"), "summary column renamed");
    System.out.println("3) one currency only");
    in.onlyCurrencies = Collections.singleton("USD");
    Report r1 = Reports.statement(in, st);
    boolean hasSyp = false; for (Report.Section s : r1.sections) if ("SYP".equals(s.sheetName)) hasSyp = true;
    check(!hasSyp, "SYP excluded");
    check(r1.fileBase.endsWith("-USD"), "file name carries currency: " + r1.fileBase);
    check(r1.sections.get(0).rows.size() == 1, "summary has one row");
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
  }
}
