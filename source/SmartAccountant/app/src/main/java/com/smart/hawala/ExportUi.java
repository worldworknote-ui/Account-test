package com.smart.hawala;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * نافذة التصدير الموحّدة لكل التقارير (كشف مكتب، أرصدة كل المكاتب، التقرير اليومي، سجل الحركات/نتائج البحث):
 * اختيار الصيغة (PDF / صور / Excel)، النطاق (المحدد فقط أو كامل الفترة)، تضمين الصور، ثم المشاركة
 * (واتساب أو غيره من قائمة المشاركة) أو الحفظ في الهاتف. وفيها شاشة «خيارات تصميم الكشوفات» مع معاينة حيّة.
 */
final class ExportUi {
    static final int PDF = 0, PNG = 1, XLSX = 2;
    static final int SHARE = 0, SAVE = 2;
    static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MainActivity a;
    private File pendingSave;

    ExportUi(MainActivity a) { this.a = a; }

    interface Build { List<Report> build(boolean images, ExportStyle st) throws Exception; }

    static final int SCOPE_PERIOD = 0, SCOPE_SELECTED = 1, SCOPE_SINCE = 2;

    ExportStyle style() { return ExportStyle.from(a.db.settings(ExportStyle.KEYS)); }

    private int lastFormat() {
        long f = a.db.settingLong("export_format", PDF);
        return f < 0 || f > 2 ? PDF : (int) f;
    }

    // ===================================================================================
    // نوافذ التصدير لكل تقرير
    // ===================================================================================

    void statementDialog(final Model.Office o, final StatementScreen.Period p, final Set<Long> selected) {
        statementDialog(o, p, selected, -1, null);
    }

    /**
     * تصدير كشف المكتب. النطاق: الفترة، أو الحركات المحددة، أو ما سُجّل بعد مطابقة (آخر مطابقة مؤكدة إن وُجدت).
     * العملات: كلها أو بعضها، في ملف واحد أو ملف لكل عملة.
     */
    void statementDialog(final Model.Office o, final StatementScreen.Period p, final Set<Long> selected, final int wantScope, final Model.Recon given) {
        final boolean hasSel = selected != null && !selected.isEmpty();
        Model.Recon b = given;
        if (b == null) {
            b = a.db.lastRecon(o.id, true);
            if (b == null) b = a.db.lastRecon(o.id, false);
        }
        final Model.Recon base = b;
        if (wantScope == SCOPE_SINCE && base == null) {
            a.toast("لا توجد مطابقة لهذا المكتب بعد. أنشئ «مطابقة رصيد» أولًا.");
            return;
        }
        final List<String> labels = new ArrayList<>();
        final List<Integer> kinds = new ArrayList<>();
        labels.add(p.hasStart() || p.to != Long.MAX_VALUE ? "الفترة: " + p.label : "كل الفترات");
        kinds.add(SCOPE_PERIOD);
        if (hasSel) {
            labels.add("المحددة فقط (" + selected.size() + ")");
            kinds.add(SCOPE_SELECTED);
        }
        if (base != null) {
            labels.add("بعد مطابقة " + Model.day(base.createdAt) + (Model.RECON_CONFIRMED.equals(base.status) ? " ✓" : ""));
            kinds.add(SCOPE_SINCE);
        }
        int init = kinds.indexOf(wantScope);
        if (init < 0) init = hasSel ? kinds.indexOf(SCOPE_SELECTED) : 0;

        List<View> extras = new ArrayList<>();
        final Ui.Seg scope;
        if (labels.size() > 1) {
            extras.add(a.label("النطاق"));
            scope = a.new Seg(labels.toArray(new String[0]), null, init, true);
            extras.add(scope.view);
            if (base != null) {
                extras.add(a.text("«بعد مطابقة»: يبدأ برصيد المطابقة (" + Model.date(base.createdAt) + " — " + base.statusLabel()
                        + ") ويضم كل ما سُجّل بعدها حتى الآن.", 12, a.muted, false));
            }
        } else scope = null;

        final Set<String> codeSet = new java.util.TreeSet<>(a.db.officeCurrencies(o.id));
        for (Map.Entry<String, Long> e : a.db.balances(o.id).entrySet()) if (e.getValue() != 0) codeSet.add(e.getKey());
        final List<String> codes = new ArrayList<>(codeSet);
        final Map<String, CheckBox> curChecks = new java.util.LinkedHashMap<>();
        final CheckBox separate = new CheckBox(a);
        if (codes.size() > 1) {
            extras.add(a.label("العملات"));
            // 12.5: يلتف بدل التمرير الأفقي حتى لا تُقصّ العملات
            Ui.Flow cr = a.flow();
            View hs = cr;
            Map<String, String> names = a.db.currencyNames();
            for (String c : codes) {
                CheckBox ck = new CheckBox(a);
                ck.setText(c);
                ck.setChecked(true);
                String nm = names.get(c);
                if (nm != null) ck.setContentDescription(nm);
                cr.addView(ck);
                curChecks.put(c, ck);
            }
            extras.add(hs);
            separate.setText("كل عملة في ملف منفصل");
            separate.setChecked("1".equals(a.db.setting("export_separate", "0")));
            extras.add(separate);
        }
        // 11.3: الأمانات والمبالغ المجمّدة في الكشف (اختيارية، ويُحفظ آخر اختيار)
        final CheckBox holdsCk = new CheckBox(a);
        final boolean hasHolds = !a.db.holds(o.id, true).isEmpty();
        if (hasHolds) {
            holdsCk.setText("إظهار الأمانات والمبالغ المجمّدة (قسم مستقل، لا تدخل في الرصيد)");
            holdsCk.setChecked("1".equals(a.db.setting("st_holds", "0")));
            extras.add(holdsCk);
        }
        String info = "المكتب: " + o.name;
        open("تصدير كشف المكتب", info, extras, true, "كشف حساب — " + o.name,
                () -> {
                    a.statementScreen.show(o, p, false);
                    statementDialog(o, p, selected, wantScope, given);
                },
                (images, st) -> {
                    int kind = scope == null ? SCOPE_PERIOD : kinds.get(Math.max(0, scope.selected));
                    Set<String> chosen = new java.util.LinkedHashSet<>();
                    if (curChecks.isEmpty()) chosen.addAll(codes);
                    else for (Map.Entry<String, CheckBox> e : curChecks.entrySet()) if (e.getValue().isChecked()) chosen.add(e.getKey());
                    if (chosen.isEmpty()) throw new Exception("اختر عملة واحدة على الأقل.");
                    a.db.setSettingQuiet("export_separate", separate.isChecked() ? "1" : "0");
                    ExportStyle use = st;
                    if (kind != SCOPE_PERIOD) {
                        // المحدد يظهر كما هو حتى لو ملغى؛ و«بعد المطابقة» يصفّي الملغاة بنفسه حتى لا يختل رصيد المطابقة
                        use = ExportStyle.from(st.toMap());
                        use.hideCancelled = false;
                    }
                    Set<Long> sel = kind == SCOPE_SELECTED ? selected : null;
                    Model.Recon since = kind == SCOPE_SINCE ? base : null;
                    List<Report> out = new ArrayList<>();
                    if (separate.isChecked() && chosen.size() > 1) {
                        for (String c : chosen) {
                            Reports.StatementInput in = a.statementScreen.input(o, p, sel, images, st, since, Collections.singleton(c));
                            out.add(Reports.statement(in, use));
                        }
                    } else {
                        Set<String> only = chosen.size() == codes.size() ? null : chosen;
                        out.add(Reports.statement(a.statementScreen.input(o, p, sel, images, st, since, only), use));
                    }
                    if (hasHolds) {
                        a.db.setSettingQuiet("st_holds", holdsCk.isChecked() ? "1" : "0");
                        if (holdsCk.isChecked()) for (Report r : out) addHolds(r, o, chosen);
                    }
                    return out;
                });
    }

    /** 11.3: قسم «الأمانات والمبالغ المجمّدة» في الكشف (المفتوحة الآن، للعملات المختارة). */
    void addHolds(Report r, Model.Office o, Set<String> codes) {
        List<Db.Hold> hs = new ArrayList<>();
        for (Db.Hold h : a.db.holds(o.id, true)) if (codes == null || codes.contains(h.currency)) hs.add(h);
        if (hs.isEmpty()) return;
        Map<String, String> names = a.db.currencyNames();
        Report.Section s = r.section("الأمانات والمبالغ المجمّدة", "الأمانات");
        s.note = "الأمانة المنفصلة لا تدخل في الرصيد. المجمّد جزء من الرصيد معلَّم كمجمّد. القائمة: المفتوحة حتى الآن.";
        s.col("البيان", 2f, Report.TEXT).col("العملة", 1.2f, Report.TEXT).col("المبلغ", 1.3f, Report.TEXT).col("منذ", 1.2f, Report.TEXT).col("ملاحظة", 2f, Report.TEXT);
        for (Db.Hold h : hs) {
            String nm = names.get(h.currency);
            s.row(Report.NORMAL, h.label(), nm == null ? h.currency : nm, Model.fmt(h.amount, a.db.decimals(h.currency)), Model.day(h.at), Model.nz(h.note));
        }
    }

    void balancesDialog(final boolean includeZero) {
        List<View> extras = new ArrayList<>();
        final CheckBox zero = new CheckBox(a);
        zero.setText("تضمين المكاتب ذات الرصيد الصفري");
        zero.setChecked(includeZero);
        extras.add(zero);
        open("تصدير أرصدة كل المكاتب", "لكل مكتب ولكل عملة: كل ما لنا، كل ما علينا، ثم الرصيد، مع الإجمالي لكل عملة.", extras, false,
                "أرصدة كل المكاتب",
                () -> {
                    a.balancesScreen.show();
                    balancesDialog(includeZero);
                },
                (images, st) -> Collections.singletonList(a.balancesScreen.report(zero.isChecked())));
    }

    void dailyDialog(final long from, final long to) {
        open("تصدير التقرير اليومي", "اليوم: " + Model.day(from), new ArrayList<View>(), true, "التقرير اليومي " + Model.day(from),
                () -> {
                    a.dailyScreen.show(from);
                    dailyDialog(from, to);
                },
                (images, st) -> {
                    Db.TxFilter f = new Db.TxFilter();
                    f.from = from;
                    f.to = to;
                    f.includeCancelled = true;
                    List<Model.Tx> txs = a.db.txLog(f);
                    return Collections.singletonList(Reports.daily(Model.day(from), a.db.dailyStats(from, to), txs, a.db.officeNames(), a.db.decimalsMap(),
                            a.db.setting("office_name", ""), Clock.now(), images ? attachments(txs) : null));
                });
    }

    void listDialog(final String title, final List<String> filters, final List<Model.Tx> txs) {
        open("تصدير: " + title, "عدد الحركات: " + txs.size() + (filters.isEmpty() ? "" : "\n" + Model.join(filters, " • ")),
                new ArrayList<View>(), true, title,
                () -> {
                    a.logScreen.show("نتائج البحث".equals(title));
                    listDialog(title, filters, txs);
                },
                (images, st) -> Collections.singletonList(Reports.txList(title, filters, txs, a.db.officeNames(), a.db.decimalsMap(), a.db.setting("office_name", ""),
                        Clock.now(), images ? attachments(txs) : null)));
    }

    /** 8.0: تصدير تقرير جاهز (تقارير صاحب المكتب). */
    void reportDialog(final String title, final Runnable reopen, final Build build) {
        open("تصدير: " + title, null, new ArrayList<View>(), false, title, reopen, build);
    }

    private Map<Long, List<Long>> attachments(List<Model.Tx> txs) {
        Map<Long, Integer> counts = a.db.attachmentCounts(null);
        Map<Long, List<Long>> m = new HashMap<>();
        for (Model.Tx t : txs) {
            Integer n = counts.get(t.id);
            if (n != null && n > 0) m.put(t.id, a.db.attachmentIds(t.id));
        }
        return m;
    }

    // ===================================================================================
    // النافذة العامة
    // ===================================================================================

    private void open(String title, String info, List<View> extras, boolean imagesPossible, final String subject,
                      final Runnable reopen, final Build build) {
        if (!a.require(Auth.Perm.EXPORT)) return;
        ScrollView sv = new ScrollView(a);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        l.setPadding(a.dp(16), a.dp(6), a.dp(16), a.dp(6));
        sv.addView(l);
        if (info != null && !info.isEmpty()) l.addView(a.text(info, 14, a.dark, false));
        l.addView(a.label("صيغة الملف"));
        final Ui.Seg fmt = a.new Seg(new String[]{"PDF", "صور PNG", "Excel"}, null, lastFormat(), false);
        l.addView(fmt.view);
        for (View v : extras) l.addView(v);
        final CheckBox img = new CheckBox(a);
        img.setText("تضمين صور الحركات (الإيصالات) داخل الكشف");
        final TextView imgNote = a.text("", 12, a.muted, false);
        if (imagesPossible) {
            img.setChecked("1".equals(a.db.setting("export_images", "0")));
            l.addView(img);
            l.addView(imgNote);
        }
        final Runnable sync = () -> {
            boolean x = fmt.selected == XLSX;
            img.setEnabled(!x);
            imgNote.setText(x ? "ملف Excel لا يحمل الصور؛ اختر PDF أو صور لتضمينها." : "");
        };
        fmt.listener = i -> sync.run();
        sync.run();

        final AlertDialog[] dlg = new AlertDialog[1];
        final Ui.OnPick go = how -> {
            final int format = fmt.selected;
            final boolean images = imagesPossible && format != XLSX && img.isChecked();
            a.db.setSettingQuiet("export_format", "" + format);
            if (imagesPossible && format != XLSX) a.db.setSettingQuiet("export_images", img.isChecked() ? "1" : "0");
            if (dlg[0] != null) dlg[0].dismiss();
            run(build, format, images, how, subject);
        };
        l.addView(a.makeButton("مشاركة / إرسال (واتساب، بريد…)", a.primary, v -> go.picked(SHARE)), a.lp(-1, 50, 4));
        l.addView(a.makeButton("حفظ في الهاتف", a.accent, v -> go.picked(SAVE)), a.lp(-1, 50, 4));
        if (a.can(Auth.Perm.DESIGN)) {
            l.addView(a.makeButton("خيارات تصميم الكشوفات…", Color.rgb(120, 120, 120), v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                showStyle(reopen);
            }), a.lp(-1, 46, 4));
        }
        dlg[0] = new AlertDialog.Builder(a).setTitle(title).setView(sv).setNegativeButton("إغلاق", null).create();
        dlg[0].show();
    }

    private ReportPdf.Opts opts(ExportStyle st, boolean images) {
        ReportPdf.Opts o = new ReportPdf.Opts();
        o.style = st;
        o.accent = Ui.parseColor(st.color, a.primary);
        o.logo = st.logo ? a.homeLogo() : null;
        o.developer = a.db.setting("developer", "");
        o.db = images ? a.db : null;
        return o;
    }

    private static String stamp() { return Model.sdf(new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)).format(new Date()); }

    private void run(final Build build, final int format, final boolean images, final int how, final String subject) {
        final ExportStyle st = style();
        final ReportPdf.Opts o = opts(st, images);
        final File dir = a.sharedDir();
        a.busy("جارٍ تجهيز الملف…", p -> {
            List<Report> reports = build.build(images, st);
            List<File> files = new ArrayList<>();
            java.util.Set<String> used = new java.util.HashSet<>();
            String stamp = stamp();
            int n = 0;
            for (Report r : reports) {
                if (r.dataRows() == 0) continue;
                Reports.colorize(r, st);
                Entitlements ent = License.current;
                r.stamp(ent.watermark() || License.highRisk(), ent.lid);
                n++;
                String base = Ui.safeName(r.fileBase) + "-" + stamp;
                if (!used.add(base)) base = base + "-" + n;
                if (reports.size() > 1) p.update("جارٍ تجهيز الملف " + n + " من " + reports.size() + "…");
                if (format == PDF) {
                    if (reports.size() == 1) p.update("جارٍ رسم صفحات PDF…");
                    files.add(ReportPdf.pdf(r, o, new File(dir, base + ".pdf")));
                } else if (format == PNG) {
                    if (reports.size() == 1) p.update("جارٍ رسم الصور…");
                    files.addAll(ReportPdf.png(r, o, dir, base));
                } else {
                    if (reports.size() == 1) p.update("جارٍ كتابة ملف Excel…");
                    File f = new File(dir, base + ".xlsx");
                    try (OutputStream os = new FileOutputStream(f)) {
                        XlsxWriter.write(r, os, Ui.argb(o.accent), Model.zone());
                    }
                    files.add(f);
                }
            }
            if (files.isEmpty()) throw new Exception("لا توجد بيانات للتصدير في هذا النطاق.");
            for (File f : files) if (!f.exists() || f.length() == 0) throw new Exception("تعذر إنشاء الملف. أعد المحاولة.");
            return files;
        }, res -> {
            @SuppressWarnings("unchecked") List<File> files = (List<File>) res;
            String mime = format == PDF ? "application/pdf" : format == PNG ? "image/png" : XLSX_MIME;
            a.db.audit("EXPORT", format == PDF ? "pdf" : format == PNG ? "png" : "xlsx", 0, subject);
            if (how == SAVE) save(files, mime);
            else a.shareFiles(files, mime, subject);
        });
    }

    // ===================================================================================
    // الحفظ في الهاتف
    // ===================================================================================

    private void save(List<File> files, String mime) {
        if (files.size() > 1) {
            new AlertDialog.Builder(a).setTitle("عدة ملفات")
                    .setMessage("التصدير أنتج " + files.size() + " ملفات. لحفظها كلها اختر «مشاركة» ثم «حفظ» أو Google Drive، أو صدّر في ملف واحد.")
                    .setPositiveButton("مشاركة", (d, w) -> a.shareFiles(files, mime, "كشف")).setNegativeButton("إلغاء", null).show();
            return;
        }
        pendingSave = files.get(0);
        a.db.setSettingQuiet("pending_export_file", pendingSave.getAbsolutePath());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, pendingSave.getName());
        a.startForResult(i, MainActivity.SAVE_EXPORT);
    }

    void onSaveResult(int c, Intent data) {
        String path = a.db.setting("pending_export_file", "");
        final File src = pendingSave != null ? pendingSave : path.isEmpty() ? null : new File(path);
        pendingSave = null;
        a.db.deleteSetting("pending_export_file");
        if (c != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (src == null || !src.exists()) {
            a.error(new Exception("انتهت صلاحية الملف المؤقت. أعد التصدير."));
            return;
        }
        final Uri target = data.getData();
        a.busy("جارٍ الحفظ…", p -> {
            try (OutputStream os = a.getContentResolver().openOutputStream(target, "w")) {
                if (os == null) throw new Exception("تعذر الكتابة في المكان المختار.");
                os.write(Db.readAll(src));
            }
            return BackupManager.displayName(a, target);
        }, res -> a.toast("تم الحفظ: " + res));
    }

    // ===================================================================================
    // خيارات تصميم الكشوفات المصدّرة
    // ===================================================================================

    void showStyle(final Runnable back) {
        if (!a.require(Auth.Perm.DESIGN)) return;
        final ExportStyle st = style();
        a.here(() -> showStyle(back));
        a.tabbedScreen("تصميم الكشوفات المصدّرة", back == null ? a.moreScreen::show : back, "more");
        a.hint("تنطبق على PDF والصور، وأعمدة Excel. المعاينة أدناه لكشف تجريبي.");

        final ImageView preview = new ImageView(a);
        preview.setAdjustViewBounds(true);
        preview.setBackground(a.round(Color.WHITE, Color.rgb(210, 205, 194), 8));
        final int pw = Math.min(a.getResources().getDisplayMetrics().widthPixels - a.dp(24), 1100);
        final Runnable[] refresh = new Runnable[1];

        a.title("العنوان والترويسة");
        final EditText title = a.input("عنوان الكشف (مثل: كشف حساب)");
        title.setText(st.title);
        final EditText info = a.input("سطر تحت اسم مكتبك: العنوان، الهاتف… (اختياري)");
        info.setSingleLine(false);
        info.setMinLines(2);
        info.setLayoutParams(a.lp(-1, -2, 4));
        info.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
        info.setText(st.issuerInfo);
        final CheckBox logo = check("إظهار صورة/شعار المكتب في رأس الكشف", st.logo, refresh);

        a.title("اللون");
        final LinearLayout colorRow = a.row();
        a.body.addView(colorRow, a.lp(-1, -2, 4));
        final Runnable drawColor = new Runnable() {
            @Override public void run() {
                colorRow.removeAllViews();
                int c = Ui.parseColor(st.color, a.primary);
                colorRow.addView(a.swatch(c, 36), new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));
                colorRow.addView(a.text(st.color.isEmpty() ? "نفس لون التطبيق" : st.color, 14, a.dark, false), new LinearLayout.LayoutParams(0, -2, 1));
                colorRow.addView(a.smallButton("تغيير اللون", a.accent, v -> a.designScreen.paletteDialog("لون الكشوفات", DesignScreen.PALETTE, true, st.color, hex -> {
                    st.color = hex;
                    run();
                    refresh[0].run();
                })), new LinearLayout.LayoutParams(-2, a.dp(42)));
            }
        };
        drawColor.run();

        a.title("ألوان العملات");
        a.hint("كل عملة بلون مختلف: رأس جدولها، وخلفية خفيفة لصفوفها، وأرقامها بلونها، حتى لا تختلط العملات على القارئ.");
        final CheckBox curColors = check("لون مختلف لكل عملة", st.curColors, refresh);
        final LinearLayout curBox = new LinearLayout(a);
        curBox.setOrientation(LinearLayout.VERTICAL);
        a.body.addView(curBox);
        final Runnable drawCurs = new Runnable() {
            @Override public void run() {
                curBox.removeAllViews();
                curBox.setVisibility(curColors.isChecked() ? View.VISIBLE : View.GONE);
                Ui.Flow fl = a.flow();
                for (final Model.Currency c : a.db.currencies(false)) {
                    LinearLayout chip = a.row();
                    chip.setPadding(a.dp(10), a.dp(6), a.dp(8), a.dp(6));
                    chip.setBackground(a.ripple(a.round(a.surface, a.line, 50), 50));
                    chip.addView(a.text(c.code, 14, a.ink, true));
                    chip.addView(a.swatch(st.colorFor(c.code), 24), new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
                    chip.setOnClickListener(v -> a.designScreen.paletteDialog("لون " + c.code + " — " + c.name, DesignScreen.PALETTE, true, st.colorHexFor(c.code), hex -> {
                        Map<String, String> m = st.colorMap();
                        if (hex.isEmpty()) m.remove(c.code);
                        else m.put(c.code, hex);
                        st.curColorMap = ExportStyle.joinMap(m);
                        run();
                        refresh[0].run();
                    }));
                    fl.addView(chip);
                }
                curBox.addView(fl, a.lp(-1, -2, 4));
                curBox.addView(a.text("اضغط على عملة لتغيير لونها. «الافتراضي» في منتقي اللون يعيد لونها الأصلي.", 12, a.muted, false));
            }
        };
        drawCurs.run();
        curColors.setOnCheckedChangeListener((b, x) -> {
            drawCurs.run();
            if (refresh[0] != null) refresh[0].run();
        });

        a.title("الأعمدة والمحتوى");
        final CheckBox person = check("اسم الشخص (المرسل/المستلم)", st.showPerson, refresh);
        final CheckBox desc = check("البيان", st.showDesc, refresh);
        final CheckBox ref = check("رقم المرجع", st.showRef, refresh);
        final CheckBox notes = check("الملاحظات", st.showNotes, refresh);
        final CheckBox running = check("عمود الرصيد التراكمي", st.showRunning, refresh);
        final CheckBox summary = check("مربع الملخص (الرصيد السابق، لنا، علينا، الأجور، الرصيد)", st.showSummary, refresh);
        final CheckBox hideCancelled = check("إخفاء الحركات الملغاة من الكشف", st.hideCancelled, refresh);
        final CheckBox newest = check("الأحدث أولًا (بدل الأقدم أولًا)", st.newestFirst, refresh);

        a.title("الصفحة");
        a.add(a.label("حجم الخط"));
        final Ui.Seg font = a.seg(new String[]{"صغير", "عادي", "كبير"}, "small".equals(st.font) ? 0 : "large".equals(st.font) ? 2 : 1, i -> refresh[0].run());
        a.add(a.label("اتجاه الصفحة"));
        final Ui.Seg orient = a.seg(new String[]{"طولي", "عرضي"}, st.landscape ? 1 : 0, i -> refresh[0].run());
        a.add(a.label("حجم الصور داخل الكشف"));
        final Ui.Seg img = a.seg(new String[]{"صغير", "متوسط", "كبير"}, "small".equals(st.imageSize) ? 0 : "large".equals(st.imageSize) ? 2 : 1, i -> refresh[0].run());
        final CheckBox sign = check("خانات توقيع في نهاية الكشف", st.signature, refresh);
        final EditText footer = a.input("نص أسفل كل صفحة (اختياري)");
        footer.setText(st.footer);

        a.title("المعاينة");
        a.body.addView(preview, a.lp(-1, -2, 4));
        a.button("تحديث المعاينة", Color.rgb(120, 120, 120), v -> refresh[0].run());

        refresh[0] = () -> {
            st.title = title.getText().toString().trim().isEmpty() ? "كشف حساب" : title.getText().toString().trim();
            st.issuerInfo = info.getText().toString().trim();
            st.logo = logo.isChecked();
            st.showPerson = person.isChecked();
            st.showDesc = desc.isChecked();
            st.showRef = ref.isChecked();
            st.showNotes = notes.isChecked();
            st.showRunning = running.isChecked();
            st.showSummary = summary.isChecked();
            st.hideCancelled = hideCancelled.isChecked();
            st.newestFirst = newest.isChecked();
            st.font = font.selected == 0 ? "small" : font.selected == 2 ? "large" : "normal";
            st.landscape = orient.selected == 1;
            st.imageSize = img.selected == 0 ? "small" : img.selected == 2 ? "large" : "medium";
            st.signature = sign.isChecked();
            st.footer = footer.getText().toString().trim();
            st.curColors = curColors.isChecked();
            try {
                Bitmap bm = ReportPdf.preview(Reports.sample(st, a.db.setting("office_name", "")), opts(st, false), pw);
                preview.setImageBitmap(bm);
            } catch (Throwable t) {
                preview.setImageDrawable(null);
            }
        };
        refresh[0].run();

        a.button("حفظ خيارات التصميم", a.primary, v -> {
            refresh[0].run();
            for (Map.Entry<String, String> e : st.toMap().entrySet()) a.db.setSettingQuiet(e.getKey(), e.getValue());
            a.db.audit("SETTING", "settings", 0, "export_style");
            a.toast("تم حفظ تصميم الكشوفات");
            if (back != null) back.run();
            else a.settingsScreen.show();
        });
        a.button("استعادة التصميم الافتراضي", Color.rgb(140, 140, 140), v -> a.confirm("التصميم الافتراضي", "ستعود كل خيارات الكشوفات إلى الوضع الافتراضي.", "استعادة", () -> {
            for (Map.Entry<String, String> e : new ExportStyle().toMap().entrySet()) a.db.setSettingQuiet(e.getKey(), e.getValue());
            showStyle(back);
        }));
    }

    private CheckBox check(String label, boolean value, final Runnable[] refresh) {
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setTextSize(15);
        c.setChecked(value);
        c.setOnCheckedChangeListener((b, x) -> {
            if (refresh[0] != null) refresh[0].run();
        });
        a.body.addView(c);
        return c;
    }
}
