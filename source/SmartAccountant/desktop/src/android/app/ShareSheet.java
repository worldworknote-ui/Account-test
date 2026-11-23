package android.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 11.2: قائمة المشاركة على الماك والويندوز (بدل قائمة أندرويد): واتساب (التطبيق أو ويب)، تلغرام، البريد،
 * النسخ للصق، الحفظ، الفتح، الإظهار في المجلد. يمكن تذكّر اختيار ثابت لكل نوع (نص أو ملفات) ويُغيَّر من «المظهر».
 * <p>
 * تطبيقات المراسلة على الكمبيوتر لا تستقبل ملفًا من برنامج آخر مباشرة: يُنسخ الملف (والصورة نفسها) إلى الحافظة،
 * ويُفتح التطبيق والنص جاهز في خانة الكتابة، فيكفي اختيار المحادثة ثم اللصق.
 */
public final class ShareSheet {
    private ShareSheet() {}

    static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    public static final String PREFS = "desk_share";

    public static final String SYSTEM = "system";
    public static final String WA_APP = "wa_app", WA_WEB = "wa_web", TG = "tg", MAIL = "mail", COPY = "copy", SAVE = "save", OPEN = "open", REVEAL = "reveal";
    /** 11.6: واتساب بسحب الملف: يُظهر الملف في مجلده ويُفتح واتساب، فتسحبه إلى المحادثة. */
    public static final String WA_DRAG = "wa_drag";
    /** الاختيارات التي يمكن جعلها ثابتة (الأولى = يسأل كل مرة). */
    public static final String[] FIXED = {"", SYSTEM, WA_APP, WA_WEB, TG};
    public static final String[] FIXED_LABELS = {"يسأل كل مرة", "مشاركة النظام", "واتساب", "واتساب ويب", "تلغرام"};

    static String label(String id, boolean files) {
        switch (id) {
            case SYSTEM: return (MAC ? "قائمة مشاركة الماك" : "نافذة مشاركة ويندوز") + ": واتساب وغيره مباشرة";
            case WA_APP: return files ? "واتساب: نسخ الملف ثم لصقه في المحادثة (" + (MAC ? "⌘V" : "Ctrl+V") + ")" : "واتساب (التطبيق)";
            case WA_DRAG: return "واتساب: إظهار الملف في المجلد لسحبه إلى المحادثة";
            case WA_WEB: return "واتساب ويب (المتصفح)";
            case TG: return "تلغرام";
            case MAIL: return files && MAC ? "البريد (Mail) مع المرفق" : "البريد";
            case COPY: return files ? "نسخ للصق في أي برنامج" : "نسخ النص";
            case SAVE: return files ? "حفظ نسخة في…" : "حفظ كملف نصي…";
            case OPEN: return "فتح الملف";
            case REVEAL: return "إظهار في المجلد";
            default: return id;
        }
    }

    /** 12.0: الاسم القصير تحت الأيقونة. */
    static String shortLabel(String id, boolean files) {
        switch (id) {
            case SYSTEM: return MAC ? "مشاركة الماك" : "مشاركة ويندوز";
            case WA_APP:
            case WA_DRAG: return "واتساب";
            case WA_WEB: return "واتساب ويب";
            case TG: return "تلغرام";
            case MAIL: return "البريد";
            case COPY: return "نسخ النص";
            case SAVE: return files ? "حفظ نسخة" : "حفظ كملف";
            case OPEN: return "فتح الملف";
            case REVEAL: return "في المجلد";
            default: return id;
        }
    }

    /** 12.0: سطر صغير يشرح ما يحدث. */
    static String hint(String id, boolean files) {
        switch (id) {
            case SYSTEM: return "واتساب وغيره مباشرة";
            case WA_APP: return "يفتح التطبيق والنص جاهز";
            case WA_DRAG: return "يُظهر الملف لتسحبه إلى المحادثة";
            case WA_WEB: return files ? "في المتصفح: الصق الملف" : "في المتصفح";
            case TG: return files ? "الصق الملف في المحادثة" : "يفتح التطبيق والنص جاهز";
            case MAIL: return files ? "رسالة مع المرفق" : "رسالة جديدة";
            case COPY: return "للصق في أي مكان";
            case SAVE: return "في مكان تختاره";
            case OPEN: return "بالبرنامج المعتاد";
            case REVEAL: return MAC ? "يُظهره في Finder" : "يُظهره في المستكشف";
            default: return "";
        }
    }

    public static void share(Context ctx, List<File> files, CharSequence text, String subject) {
        boolean hasFiles = !files.isEmpty();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        // القائمة تظهر دائمًا (للنص والملفات). 12.0: شبكة بأيقونات. للملفات: كل الخيارات ما عدا «نسخ الملف ولصقه في
        // تطبيق واتساب» (لا يعمل مع PDF): السحب من المجلد، وواتساب ويب وتلغرام (لصق)، ومشاركة النظام، والبريد والحفظ والفتح.
        List<String> ids = new ArrayList<>();
        if (hasFiles) ids.add(WA_DRAG);
        else ids.add(WA_APP);
        ids.add(WA_WEB);
        ids.add(TG);
        if (SystemShare.supported()) ids.add(SYSTEM);
        ids.add(MAIL);
        if (!hasFiles) ids.add(COPY);
        ids.add(SAVE);
        if (hasFiles) {
            if (files.size() == 1) ids.add(OPEN);
            ids.add(REVEAL);
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(10 * d), Math.round(2 * d), Math.round(10 * d), 0);
        final AlertDialog[] dlg = {null};
        String last = p.getString("last_" + (hasFiles ? "files" : "text"), "");
        if (!last.isEmpty() && ids.remove(last)) ids.add(0, last);
        String what = hasFiles ? (files.size() == 1 ? files.get(0).getName() : files.size() + " ملفات") : "نص";
        TextView sub = new TextView(ctx);
        sub.setText(what);
        sub.setTextSize(12);
        sub.setTextColor(0xFF7A7F87);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        sub.setPadding(Math.round(6 * d), 0, Math.round(6 * d), Math.round(8 * d));
        box.addView(sub);
        LinearLayout rowL = null;
        int col = 0;
        final int cols = ids.size() > 6 ? 4 : 3;
        for (final String id : ids) {
            if (col % cols == 0) {
                rowL = new LinearLayout(ctx);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(rowL, new LinearLayout.LayoutParams(-1, -2));
            }
            col++;
            LinearLayout tile = new LinearLayout(ctx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(Math.round(6 * d), Math.round(10 * d), Math.round(6 * d), Math.round(8 * d));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(16 * d);
            bg.setColor(id.equals(last) ? 0x1425D366 : 0x08000000);
            bg.setStroke(Math.max(1, Math.round(d)), id.equals(last) ? 0x6625D366 : 0x14000000);
            tile.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), bg, null));
            tile.setContentDescription(label(id, hasFiles));
            ShareIcon ic = new ShareIcon(ctx, id);
            tile.addView(ic, new LinearLayout.LayoutParams(Math.round(46 * d), Math.round(46 * d)));
            TextView t = new TextView(ctx);
            t.setText(shortLabel(id, hasFiles));
            t.setTextSize(14);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Math.round(6 * d), 0, 0);
            tile.addView(t, new LinearLayout.LayoutParams(-1, -2));
            TextView h = new TextView(ctx);
            h.setText(hint(id, hasFiles) + (id.equals(last) ? "\n· الأخير ·" : ""));
            h.setTextSize(11);
            h.setTextColor(0xFF7A7F87);
            h.setGravity(Gravity.CENTER);
            h.setMaxLines(3);
            tile.addView(h, new LinearLayout.LayoutParams(-1, -2));
            tile.setOnClickListener(v -> {
                p.edit().putString("last_" + (hasFiles ? "files" : "text"), id).apply();
                if (dlg[0] != null) dlg[0].dismiss();
                run(ctx, id, files, text, subject);
            });
            tile.setMinimumHeight(Math.round(118 * d));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.setMargins(Math.round(4 * d), Math.round(4 * d), Math.round(4 * d), Math.round(4 * d));
            rowL.addView(tile, tp);
        }
        // صف أخير ناقص: فراغات بالعرض نفسه كي لا تتمدد البطاقات
        while (col % cols != 0) {
            col++;
            rowL.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(ctx).setTitle("مشاركة").setView(sv).setNegativeButton("إلغاء", null).show();
    }

    static void run(Context ctx, String id, List<File> files, CharSequence text, String subject) {
        String t = text == null ? "" : text.toString();
        boolean hasFiles = !files.isEmpty();
        try {
            switch (id) {
                case SYSTEM: {
                    int[] c = center();
                    // 11.5: الملف وحده إلى قائمة المشاركة (مع نص بجانبه كان واتساب في الماك يفتح نافذة كتابة فارغة)،
                    // والنص يُنسخ للصق بعد الملف
                    final boolean split = hasFiles && !t.isEmpty() && MAC;
                    if (split) copyText(t);
                    SystemShare.share(files, split ? "" : t, c[0], c[1], ok -> {
                        if (ok && split) Toast.makeText(ctx, "أُرسل الملف إلى قائمة المشاركة، والنص منسوخ: الصقه في المحادثة بعد الملف (⌘V)", Toast.LENGTH_LONG).show();
                        if (!ok) {
                            Toast.makeText(ctx, "تعذّر فتح مشاركة النظام على هذا الجهاز: انتقلنا إلى «نسخ ثم لصق» في واتساب", Toast.LENGTH_LONG).show();
                            run(ctx, WA_APP, files, text, subject);
                        }
                    });
                    return;
                }
                case WA_APP:
                case WA_WEB:
                case TG: {
                    boolean web = id.equals(WA_WEB);
                    boolean opened;
                    if (id.equals(TG)) opened = openApp("tg://msg?text=" + Uri.encode(t), "Telegram");
                    else if (!web) opened = openApp("whatsapp://send?text=" + Uri.encode(t), "WhatsApp");
                    else opened = openWebOnce(t);
                    if (!opened && !web) {
                        // التطبيق غير مثبّت: النسخة على الويب (مرة واحدة في الجلسة)
                        if (id.equals(TG)) Platform.browseQuiet("https://t.me/share/url?url=" + Uri.encode(t.isEmpty() ? " " : t));
                        else openWebOnce(t);
                        Toast.makeText(ctx, (id.equals(TG) ? "تطبيق تلغرام" : "تطبيق واتساب") + " غير موجود على هذا الكمبيوتر: فُتحت النسخة على الويب", Toast.LENGTH_LONG).show();
                    }
                    // الملفات (وصورة مع نصها) تُلصق على خطوات: لا يمكن أن تحمل الحافظة الصورة والنص معًا للصق واحد
                    if (hasFiles || web) pasteSteps(ctx, files, t, id.equals(TG) ? "تلغرام" : web ? "واتساب ويب" : "واتساب");
                    return;
                }
                case WA_DRAG: {
                    Platform.reveal(files.get(0));
                    if (!openApp("whatsapp://", "WhatsApp")) openWebOnce("");
                    if (!t.trim().isEmpty()) copyText(t);
                    new AlertDialog.Builder(ctx).setTitle("الإرسال إلى واتساب")
                            .setMessage("1) فُتح المجلد والملف محدد فيه، وفُتح واتساب.\n2) افتح المحادثة، ثم اسحب الملف من المجلد وأفلته فيها."
                                    + (t.trim().isEmpty() ? "" : "\n3) النص منسوخ: الصقه بعد الملف (" + (MAC ? "⌘V" : "Ctrl+V") + ")."))
                            .setPositiveButton("تم", null).show();
                    return;
                }
                case MAIL: {
                    if (hasFiles && MAC) {
                        List<String> cmd = new ArrayList<>();
                        cmd.add("open");
                        cmd.add("-a");
                        cmd.add("Mail");
                        for (File f : files) cmd.add(f.getAbsolutePath());
                        new ProcessBuilder(cmd).start();
                        if (!t.isEmpty()) {
                            ((android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE)).setText(t);
                            Toast.makeText(ctx, "فُتحت رسالة بالمرفق، والنص منسوخ للصق", Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    Desktop.getDesktop().mail(new java.net.URI("mailto:?subject=" + Uri.encode(subject == null ? "" : subject) + "&body=" + Uri.encode(t)));
                    if (hasFiles) {
                        Platform.reveal(files.get(0));
                        Toast.makeText(ctx, "أرفق الملف من المجلد الذي فُتح", Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                case COPY:
                    if (hasFiles && !t.trim().isEmpty()) {
                        pasteSteps(ctx, files, t, "البرنامج الذي تريده");
                        return;
                    }
                    if (hasFiles) copyFiles(files);
                    else ((android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE)).setText(t);
                    Toast.makeText(ctx, "نُسخ — الصقه حيث تريد", Toast.LENGTH_SHORT).show();
                    return;
                case SAVE: {
                    if (!hasFiles) {
                        File out = Platform.saveDialog((subject == null || subject.isEmpty() ? "نص" : subject.replaceAll("[\\\\/:*?\"<>|]", "_")) + ".txt");
                        if (out != null) {
                            java.nio.file.Files.write(out.toPath(), t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            Toast.makeText(ctx, "حُفظ: " + out.getName(), Toast.LENGTH_SHORT).show();
                        }
                    } else if (files.size() == 1) {
                        File out = Platform.saveDialog(files.get(0).getName());
                        if (out != null) {
                            java.nio.file.Files.copy(files.get(0).toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            Toast.makeText(ctx, "حُفظ: " + out.getName(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        File dir = Platform.dirDialog();
                        if (dir != null) {
                            for (File f : files) java.nio.file.Files.copy(f.toPath(), new File(dir, f.getName()).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            Toast.makeText(ctx, "حُفظت " + files.size() + " ملفات", Toast.LENGTH_SHORT).show();
                        }
                    }
                    return;
                }
                case OPEN:
                    Desktop.getDesktop().open(files.get(0));
                    return;
                case REVEAL:
                    Platform.reveal(files.get(0));
                    return;
                default:
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "تعذر: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** وسط نافذة البرنامج على الشاشة (لموضع قائمة المشاركة). */
    static int[] center() {
        java.awt.Frame f = Platform.frame;
        if (f == null || !f.isShowing()) {
            java.awt.Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
            return new int[]{d.width / 2, d.height / 2};
        }
        java.awt.Point p = f.getLocationOnScreen();
        return new int[]{p.x + f.getWidth() / 2, p.y + f.getHeight() / 2};
    }

    /**
     * 11.3: يفتح التطبيق نفسه برابطه (whatsapp:// أو tg://). ويندوز: عبر معالج الروابط في النظام (كان Java يرفض هذه الروابط
     * فينتقل إلى المتصفح). ماك: open، وإن لم يقبل الرابط يُفتح التطبيق باسمه. false = غير مثبّت.
     */
    static boolean openApp(String url, String appName) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                // إن لم يوجد التطبيق يعرض ويندوز نفسه «اختر تطبيقًا» (لا يُفتح المتصفح بدلًا منه)
                return true;
            }
            if (MAC) {
                Process p = new ProcessBuilder("open", url).start();
                if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) return true;
                Process p2 = new ProcessBuilder("open", "-a", appName).start();
                return p2.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p2.exitValue() == 0;
            }
            Desktop.getDesktop().browse(new java.net.URI(url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean webOpened;

    /** 11.3: واتساب ويب يُفتح مرة واحدة في الجلسة (كان كل إرسال يفتح نافذة جديدة)؛ بعدها النص يُنسخ للصق في النافذة المفتوحة. */
    static boolean openWebOnce(String text) {
        if (webOpened) return true;
        webOpened = true;
        return Platform.browseQuiet("https://web.whatsapp.com/" + (text == null || text.isEmpty() ? "" : "send?text=" + Uri.encode(text)));
    }

    /**
     * 11.3: خطوات اللصق: الصورة أو الملف أولًا (منسوخ الآن)، ثم زر «نسخ النص» للصقه بعدها، و«فتح واتساب» إن لزم.
     * الحافظة لا تحمل الصورة والنص للصق واحد، فكان ينتقل أحدهما فقط.
     */
    static void pasteSteps(Context ctx, List<File> files, String text, String app) {
        boolean hasFiles = !files.isEmpty();
        boolean hasText = text != null && !text.trim().isEmpty();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (hasFiles) copyFiles(files);
        else if (hasText) cm.setText(text);
        String paste = MAC ? "⌘V" : "Ctrl+V";
        StringBuilder m = new StringBuilder();
        if (hasFiles) {
            m.append("1) ").append(files.size() == 1 ? (isImage(files.get(0)) ? "الصورة" : "الملف") : files.size() + " ملفات")
                    .append(" منسوخ الآن: افتح المحادثة في ").append(app).append(" واضغط ").append(paste).append(".");
            if (hasText) m.append("\n2) ثم اضغط «نسخ النص» هنا، والصقه في المحادثة نفسها.");
        } else {
            m.append("النص منسوخ: الصقه في المحادثة في ").append(app).append(" (").append(paste).append(").");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx).setTitle("الإرسال إلى " + app).setMessage(m.toString());
        if (hasFiles && hasText) b.setPositiveButton("نسخ النص", null);
        if (hasFiles) b.setNeutralButton(files.size() == 1 && isImage(files.get(0)) ? "نسخ الصورة مرة أخرى" : "نسخ الملف مرة أخرى", null);
        b.setNegativeButton("تم", null);
        AlertDialog d = b.show();
        if (hasFiles && hasText) d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            cm.setText(text);
            Toast.makeText(ctx, "نُسخ النص: الصقه الآن (" + paste + ")", Toast.LENGTH_SHORT).show();
        });
        if (hasFiles) d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            copyFiles(files);
            Toast.makeText(ctx, "نُسخ: الصقه الآن (" + paste + ")", Toast.LENGTH_SHORT).show();
        });
    }

    static boolean isImage(File f) { return f.getName().toLowerCase().matches(".*\\.(png|jpe?g)$"); }

    /** يفتح رابطًا؛ false إن لم يوجد برنامج له (تطبيق غير مثبّت). */
    static boolean browse(String url) {
        try {
            if (MAC && !url.startsWith("http")) {
                // open يعيد رمز خطأ إن لم يوجد تطبيق للرابط
                Process pr = new ProcessBuilder("open", url).start();
                return pr.waitFor() == 0;
            }
            Desktop.getDesktop().browse(new java.net.URI(url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void copyText(String t) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(t), null);
        } catch (Exception ignored) {
            // الحافظة مشغولة: يبقى زر «نسخ النص»
        }
    }

    /**
     * 11.6: على الماك تُنسخ الملفات بالطريقة التي يستعملها Finder (روابط ملفات في حافظة النظام)، فيقبلها واتساب
     * عند اللصق (كان PDF لا يُلصق أبدًا والصورة أحيانًا: صيغة جافا للحافظة لا يفهمها واتساب). false إن تعذّر.
     */
    static boolean macCopyFiles(List<File> files) {
        if (!MAC) return false;
        try {
            StringBuilder js = new StringBuilder("ObjC.import('AppKit');var pb=$.NSPasteboard.generalPasteboard;pb.clearContents;var a=$.NSMutableArray.array;");
            for (File f : files) js.append("a.addObject($.NSURL.fileURLWithPath(").append(jsString(f.getAbsolutePath())).append("));");
            js.append("pb.writeObjects(a) ? 'ok' : 'no';");
            Process p = new ProcessBuilder("osascript", "-l", "JavaScript", "-e", js.toString()).redirectErrorStream(true).start();
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 && out.endsWith("ok");
        } catch (Exception e) {
            return false;
        }
    }

    static String jsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** ينسخ الملفات إلى الحافظة (وللصورة الواحدة: الصورة نفسها أيضًا) للصق في واتساب وتلغرام والبريد. */
    static void copyFiles(List<File> files) {
        if (macCopyFiles(files)) return;
        final List<File> fl = new ArrayList<>(files);
        java.awt.Image img = null;
        if (fl.size() == 1 && fl.get(0).getName().toLowerCase().matches(".*\\.(png|jpe?g)$")) {
            try { img = javax.imageio.ImageIO.read(fl.get(0)); } catch (Exception ignored) { /* الملف وحده */ }
        }
        final java.awt.Image im = img;
        Transferable tr = new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() {
                return im != null ? new DataFlavor[]{DataFlavor.imageFlavor, DataFlavor.javaFileListFlavor} : new DataFlavor[]{DataFlavor.javaFileListFlavor};
            }
            @Override public boolean isDataFlavorSupported(DataFlavor f) {
                for (DataFlavor x : getTransferDataFlavors()) if (x.equals(f)) return true;
                return false;
            }
            @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                if (DataFlavor.imageFlavor.equals(f) && im != null) return im;
                if (DataFlavor.javaFileListFlavor.equals(f)) return fl;
                throw new UnsupportedFlavorException(f);
            }
        };
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(tr, null);
    }
}
