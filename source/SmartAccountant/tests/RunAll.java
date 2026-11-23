import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * يشغّل كل اختبارات *Test كلًّا في عملية مستقلة (بعضها ينهي نفسه بـ System.exit) ويفشل إن فشل واحد.
 * RunAll &lt;مجلد المخرجات&gt; [مجلد البيانات: ملفات \u200e.sba وexpect.json وnode-tokens.json]
 * الاختبارات التي تحتاج بيانات النقل تُتخطى إن لم يوجد مجلد البيانات (وتُذكر).
 */
public class RunAll {
    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "build/pure-tests/out");
        File data = a.length > 1 ? new File(a[1]) : null;
        File dir = new File(RunAll.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String[] names = dir.list((d, n) -> n.endsWith("Test.class") && !n.contains("$"));
        if (names == null || names.length == 0) throw new IllegalStateException("لا اختبارات في " + dir);
        Arrays.sort(names);
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String cp = System.getProperty("java.class.path");
        List<String> failed = new ArrayList<>();
        int ran = 0;
        for (String n : names) {
            String t = n.substring(0, n.length() - 6);
            List<String> cmd = new ArrayList<>(Arrays.asList(java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", cp, t));
            if (t.equals("MigrationTest") || t.equals("MergePlanTest")) {
                if (data == null || !data.isDirectory()) {
                    System.out.println("[" + t + "] تُخطّي: يحتاج مجلد بيانات النقل (tests/data)");
                    continue;
                }
                cmd.add(data.getAbsolutePath());
            } else if (t.equals("LicenseTest")) {
                if (data != null && data.isDirectory()) cmd.add(data.getAbsolutePath());
            } else {
                File o = new File(out, t);
                o.mkdirs();
                cmd.add(o.getAbsolutePath());
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] buf = p.getInputStream().readAllBytes();
            boolean done = p.waitFor(10, TimeUnit.MINUTES);
            String text = new String(buf, StandardCharsets.UTF_8);
            boolean ok = done && p.exitValue() == 0 && !text.contains("  FAIL ") && !text.contains("FAILED");
            ran++;
            String[] lines = text.trim().split("\n");
            System.out.println("[" + t + "] " + (ok ? "✓ " : "✗ ") + lines[lines.length - 1]);
            if (!ok) {
                failed.add(t);
                for (String l : lines) if (l.contains("FAIL") || l.contains("Exception")) System.out.println("    " + l);
            }
        }
        System.out.println(failed.isEmpty() ? "كل الاختبارات نجحت (" + ran + ")" : "فشل: " + failed);
        if (!failed.isEmpty()) System.exit(1);
    }
}
