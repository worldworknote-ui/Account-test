package com.smart.hawala.desk;

import com.smart.hawala.*;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;
import javax.swing.*;

/**
 * تصدير أي تقرير: PDF، Excel، CSV، صور PNG، أو الطباعة. خيارات التصميم نفسها المحفوظة في الإعدادات (st_*) كالهاتف.
 * في النسخة المجانية يُضاف سطر «نسخة مجانية» كما في الهاتف.
 */
final class Export {
    private Export() {}

    static final String[] FORMATS = {"PDF", "Excel (.xlsx)", "CSV (للجداول الأخرى)", "صور PNG", "طباعة…"};

    static ExportStyle style(Db db) {
        return ExportStyle.from(db.settings(ExportStyle.KEYS));
    }

    static Pdf.Opts opts(Db db, ExportStyle st) {
        Pdf.Opts o = new Pdf.Opts();
        o.style = st;
        o.db = db;
        o.developer = db.setting("developer", "برمجة: Ai creative solutions");
        Color c = st.color == null || st.color.trim().isEmpty() ? Ui.primary : Ui.parse(st.color, Ui.primary);
        o.accent = c;
        return o;
    }

    /** يعرض خيارات التصدير ثم يبني التقرير في الخلفية ويحفظه. */
    static void dialog(Component parent, App app, String what, Supplier<Report> build) {
        // 10.11 (AUDIT-003): كان الشرط يسمح بالتصدير لمن يملك «العرض» دون «التصدير»؛ كالهاتف: التصدير يحتاج صلاحيته
        if (!app.require(Auth.Perm.EXPORT)) return;
        Db db = app.db;
        ExportStyle st = style(db);
        JPanel c = Ui.column();
        c.add(Ui.label("الصيغة", 13, Ui.muted, true));
        JComboBox<String> fmt = Ui.combo(FORMATS);
        fmt.setSelectedIndex((int) Math.min(FORMATS.length - 1, db.settingLong("last_export_format", 0)));
        c.add(fmt);
        c.add(Ui.gap(10));
        JCheckBox land = Ui.check("عرض الصفحة بالعرض (أفقي) — للجداول العريضة", st.landscape);
        JComboBox<String> size = Ui.combo(new String[]{"خط صغير", "خط عادي", "خط كبير"});
        size.setSelectedIndex("small".equals(st.font) ? 0 : "large".equals(st.font) ? 2 : 1);
        JCheckBox logo = Ui.check("الشعار في رأس الصفحة", st.logo);
        JCheckBox sign = Ui.check("خانات توقيع في آخر التقرير", st.signature);
        JCheckBox colors = Ui.check("لون لكل عملة", st.curColors);
        // 10.16: خيارات تصميم الكشف التي كانت على الهاتف وحده
        final Ui.Field ttl = Ui.field("كشف حساب");
        ttl.setText(st.title);
        final String[][] COLS = {{"لون البرنامج", ""}, {"أخضر", "#1B7846"}, {"أزرق", "#1F5FBF"}, {"كحلي", "#1F3A5F"}, {"بنفسجي", "#7B3FB5"}, {"بني", "#7A4B2A"}, {"رمادي داكن", "#3A3A3A"}, {"أحمر", "#AD2E36"}};
        String[] cn = new String[COLS.length];
        int csel = 0;
        for (int i = 0; i < COLS.length; i++) {
            cn[i] = COLS[i][0];
            if (!COLS[i][1].isEmpty() && COLS[i][1].equalsIgnoreCase(Model.nz(st.color).trim())) csel = i;
        }
        final JComboBox<String> color = Ui.combo(cn);
        color.setSelectedIndex(csel);
        final String customColor = csel == 0 && !Model.nz(st.color).trim().isEmpty() ? st.color : null;   // لون اختير من الهاتف وليس في القائمة: يبقى
        final JComboBox<String> img = Ui.combo(new String[]{"صور صغيرة", "صور متوسطة", "صور كبيرة"});
        img.setSelectedIndex("small".equals(st.imageSize) ? 0 : "large".equals(st.imageSize) ? 2 : 1);
        final JCheckBox running = Ui.check("عمود الرصيد التراكمي", st.showRunning), ref = Ui.check("رقم المرجع", st.showRef),
                summary = Ui.check("الملخص في أعلى الكشف", st.showSummary);
        JPanel og = new JPanel(new GridLayout(0, 2, 12, 2));
        og.setOpaque(false);
        og.setAlignmentX(Component.RIGHT_ALIGNMENT);
        for (JCheckBox x : new JCheckBox[]{land, logo, sign, colors, running, ref, summary}) og.add(x);
        c.add(og);
        c.add(Ui.gap(6));
        JPanel fg = new JPanel(new GridLayout(0, 2, 12, 6));
        fg.setOpaque(false);
        fg.setAlignmentX(Component.RIGHT_ALIGNMENT);
        fg.add(Ui.labeled("عنوان الكشف", ttl));
        fg.add(Ui.labeled("لون الكشف", color));
        fg.add(Ui.labeled("حجم الخط (PDF والصور والطباعة)", size));
        fg.add(Ui.labeled("حجم الصور المرفقة داخل الكشف", img));
        c.add(fg);
        c.add(Ui.gap(8));
        final Runnable read = () -> {
            st.landscape = land.isSelected();
            st.font = size.getSelectedIndex() == 0 ? "small" : size.getSelectedIndex() == 2 ? "large" : "normal";
            st.logo = logo.isSelected();
            st.signature = sign.isSelected();
            st.curColors = colors.isSelected();
            st.title = ttl.val().isEmpty() ? "كشف حساب" : ttl.val();
            st.color = color.getSelectedIndex() == 0 ? (customColor == null ? "" : customColor) : COLS[color.getSelectedIndex()][1];
            st.imageSize = img.getSelectedIndex() == 0 ? "small" : img.getSelectedIndex() == 2 ? "large" : "medium";
            st.showRunning = running.isSelected();
            st.showRef = ref.isSelected();
            st.showSummary = summary.isSelected();
        };
        c.add(Ui.flow(Ui.ghost("معاينة قبل التصدير", e -> {
            read.run();
            // الخيارات تُحفظ أولًا لأن الكشف يُبنى منها
            for (Map.Entry<String, String> x : st.toMap().entrySet()) db.setSettingQuiet(x.getKey(), x.getValue());
            Ui.busy(parent, "جارٍ تجهيز المعاينة…", p -> Pdf.preview(prepare(build, st), opts(db, st), 760), im -> {
                JLabel pic = new JLabel(new ImageIcon(im));
                JScrollPane sp = new JScrollPane(pic);
                sp.setPreferredSize(new Dimension(Math.min(800, im.getWidth() + 24), 620));
                sp.getVerticalScrollBar().setUnitIncrement(24);
                JOptionPane.showMessageDialog(Ui.win(parent), sp, "معاينة: " + what + " (الصفحة الأولى)", JOptionPane.PLAIN_MESSAGE);
            });
        })));
        c.add(Ui.gap(4));
        c.add(Ui.para(License.current.watermark() ? "النسخة المجانية: يُضاف سطر «" + Report.FREE_MARK + "» أسفل الصفحات وفي خصائص Excel." : "", 12, Ui.muted, 380));
        c.setPreferredSize(new Dimension(560, c.getPreferredSize().height));
        if (!Ui.form(parent, "تصدير: " + what, Ui.rtl(c), "تصدير")) return;
        read.run();
        for (Map.Entry<String, String> e : st.toMap().entrySet()) db.setSettingQuiet(e.getKey(), e.getValue());
        int f = fmt.getSelectedIndex();
        db.setSettingQuiet("last_export_format", "" + f);
        run(parent, app, what, build, st, f);
    }

    static void run(Component parent, App app, String what, Supplier<Report> build, ExportStyle st, int f) {
        Db db = app.db;
        if (f == 4) {
            Ui.busy(parent, "جارٍ تجهيز التقرير…", p -> prepare(build, st), r -> {
                try {
                    Pdf.print(r, opts(db, st));
                } catch (Exception e) {
                    Ui.error(parent, e);
                }
            });
            return;
        }
        String ext = f == 0 ? ".pdf" : f == 1 ? ".xlsx" : f == 2 ? ".csv" : ".png";
        File target = save(parent, db, what, ext);
        if (target == null) return;
        Ui.busy(parent, "جارٍ التصدير…", p -> {
            Report r = prepare(build, st);
            if (r.dataRows() == 0 && r.sections.isEmpty()) throw new IllegalStateException("لا توجد بيانات للتصدير بهذه الاختيارات.");
            switch (f) {
                case 0:
                    Pdf.pdf(r, opts(db, st), target);
                    return target;
                case 1: {
                    String accent = Ui.hex(opts(db, st).accent).substring(1);
                    try (OutputStream o = new FileOutputStream(target)) {
                        XlsxWriter.write(r, o, "FF" + accent, Model.zone());
                    }
                    return target;
                }
                case 2:
                    try (OutputStream o = new FileOutputStream(target)) {
                        o.write(ReportBuilder.csv(r).getBytes(StandardCharsets.UTF_8));
                    }
                    return target;
                default: {
                    String base = target.getName().replaceAll("\\.png$", "");
                    List<File> files = Pdf.png(r, opts(db, st), target.getParentFile(), base);
                    return files.get(0);
                }
            }
        }, file -> {
            db.audit("EXPORT", "report", 0, what + " → " + file.getName());
            int k = Ui.choose(parent, "تم التصدير", "حُفظ: " + file.getName() + "\nفي: " + file.getParent(), new String[]{"فتح الملف", "إظهار في Finder", "حسنًا"});
            try {
                if (k == 0) Desktop.getDesktop().open(file);
                else if (k == 1) reveal(file);
            } catch (Exception e) {
                Ui.error(parent, e);
            }
        });
    }

    static Report prepare(Supplier<Report> build, ExportStyle st) {
        Report r = build.get();
        Entitlements e = License.current;
        r.stamp(e.watermark(), e.lid);
        r.signature = st.signature || r.signature;
        if (st.footer != null && !st.footer.trim().isEmpty()) r.footer = st.footer.trim();
        Reports.colorize(r, st);
        return r;
    }

    static void reveal(File f) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) new ProcessBuilder("open", "-R", f.getAbsolutePath()).start();
        else if (System.getProperty("os.name", "").toLowerCase().contains("win")) new ProcessBuilder("explorer.exe", "/select," + f.getAbsolutePath()).start();
        else Desktop.getDesktop().open(f.getParentFile());
    }

    /** نافذة حفظ الماك الأصلية، مع تذكّر آخر مجلد. */
    static File save(Component parent, Db db, String name, String ext) {
        FileDialog fd = new FileDialog(frame(parent), "حفظ", FileDialog.SAVE);
        String dir = db.setting("last_export_dir", "");
        if (dir.isEmpty() || !new File(dir).isDirectory()) dir = new File(System.getProperty("user.home"), "Documents").getAbsolutePath();
        fd.setDirectory(dir);
        fd.setFile(safe(name) + "-" + Model.day(Clock.now()).replace('/', '-') + ext);
        fd.setVisible(true);
        if (fd.getFile() == null) return null;
        String fn = fd.getFile();
        if (!fn.toLowerCase().endsWith(ext)) fn += ext;
        db.setSettingQuiet("last_export_dir", fd.getDirectory());
        return new File(fd.getDirectory(), fn);
    }

    static File open(Component parent, Db db, String title, String... exts) {
        FileDialog fd = new FileDialog(frame(parent), title, FileDialog.LOAD);
        String dir = db.setting("last_export_dir", "");
        if (!dir.isEmpty() && new File(dir).isDirectory()) fd.setDirectory(dir);
        if (exts.length > 0) fd.setFilenameFilter((d, n) -> {
            for (String e : exts) if (n.toLowerCase().endsWith(e)) return true;
            return false;
        });
        fd.setVisible(true);
        if (fd.getFile() == null) return null;
        return new File(fd.getDirectory(), fd.getFile());
    }

    static Frame frame(Component c) {
        Window w = Ui.win(c);
        while (w != null && !(w instanceof Frame)) w = w.getOwner();
        return w instanceof Frame ? (Frame) w : App.frame;
    }

    static String safe(String s) {
        return (s == null ? "تقرير" : s).replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("\\s+", "-");
    }
}
