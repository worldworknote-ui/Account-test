package com.smart.hawala;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/**
 * أسرار الماك (مفتاح البيانات، مفتاح الجهاز للترخيص، مفتاح بصمة العدّادات) في «سلسلة المفاتيح» (Keychain)
 * عبر أداة النظام /usr/bin/security، فلا تُخزَّن بجانب قاعدة البيانات. على الويندوز: ملفات محمية بـ DPAPI لحساب المستخدم.
 * غير ذلك (للاختبارات): ملفات صلاحيتها للمالك فقط.
 */
public final class SecretStore {
    private SecretStore() {}

    static final String SERVICE = "com.smart.hawala.mac";
    private static volatile File fallbackDir;

    static boolean keychain() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") && new File("/usr/bin/security").exists()
                && !("file".equals(System.getProperty("sa.secrets")) && android.content.Context.testMode());
    }

    /** للاختبارات وغير الماك: مجلد الأسرار. */
    public static void fallback(File dir) { fallbackDir = dir; }

    private static File dir() {
        File d = fallbackDir != null ? fallbackDir : new File(android.content.Context.defaultHome(), "keys");
        if (!d.exists()) d.mkdirs();
        try {
            Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("rwx------"));
        } catch (Exception ignored) {
            // نظام بلا صلاحيات POSIX
        }
        return d;
    }

    /** null إن لم يوجد. يرمي إن تعذر الوصول إلى سلسلة المفاتيح نفسها (لا يُعامل كغياب). */
    public static synchronized String get(String name) throws Exception {
        if (keychain()) {
            Res r = run("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", name, "-w");
            if (r.code == 0) return r.out.trim();
            if (r.code == 44 || r.err.contains("could not be found")) return null;
            throw new IllegalStateException("تعذر قراءة سلسلة المفاتيح (" + r.code + "): " + r.err.trim());
        }
        String cached = winCache.get(name);
        if (cached != null) return cached;
        File f = file(name);
        if (!f.exists()) return null;
        String v = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        if (v.startsWith(DPAPI)) {
            v = dpapi(false, v.substring(DPAPI.length()));
            winCache.put(name, v);
        }
        return v;
    }

    /**
     * 11.3: اسم ملف آمن لكل نظام. أسماء مثل «drive_refresh:بريد@…» فيها «:» الممنوعة في أسماء ملفات ويندوز
     * (فكان حفظ دخول Google يفشل هناك ويطلب البرنامج الدخول من جديد). كل حرف خارج [A-Za-z0-9._-] يُكتب %XX؛
     * الأسماء القديمة بلا هذه الأحرف تبقى كما هي، وملف قديم بالاسم الخام (ماك/لينكس) يُقرأ ويُنقل.
     */
    static String fileName(String name) {
        StringBuilder b = new StringBuilder();
        for (byte x : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (x & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') b.append(c);
            else b.append('%').append(String.format("%02X", x & 0xff));
        }
        return b.toString();
    }

    static File file(String name) {
        File d = dir();
        File f = new File(d, fileName(name));
        if (!f.exists() && !fileName(name).equals(name) && !windows()) {
            File legacy = new File(d, name);
            if (legacy.isFile()) return legacy;
        }
        return f;
    }

    /** على الويندوز: تُحمى الأسرار بـ DPAPI (مربوطة بحساب مستخدم ويندوز) فلا يفيد نسخ الملفات إلى جهاز أو حساب آخر. */
    static final String DPAPI = "dpapi:";
    private static final java.util.Map<String, String> winCache = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") && !("file".equals(System.getProperty("sa.secrets")) && android.content.Context.testMode());
    }

    private static String dpapi(boolean protect, String input) throws Exception {
        String ps = "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Security;"
                + "$i=[Console]::In.ReadToEnd().Trim();$s=[Security.Cryptography.DataProtectionScope]::CurrentUser;"
                + "$e=[Text.Encoding]::UTF8.GetBytes('SmartAccountant');"
                + (protect
                ? "$o=[Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($i),$e,$s);[Console]::Out.Write([Convert]::ToBase64String($o))"
                : "$o=[Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($i),$e,$s);[Console]::Out.Write([Convert]::ToBase64String($o))");
        String enc = java.util.Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
        String root = System.getenv("SystemRoot");
        File exe = new File(root == null ? "C:\\Windows" : root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        Process p = new ProcessBuilder(exe.exists() ? exe.getAbsolutePath() : "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc).start();
        try (java.io.OutputStream o = p.getOutputStream()) {
            o.write(input.getBytes(StandardCharsets.UTF_8));
        }
        final String[] err = {""};
        Thread t = new Thread(() -> {
            try { err[0] = read(p.getErrorStream()); } catch (Exception ignored) { /* لا شيء */ }
        });
        t.start();
        String out = read(p.getInputStream()).trim();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("انتهت مهلة حماية المفاتيح في ويندوز");
        }
        t.join(2000);
        if (p.exitValue() != 0 || out.isEmpty()) throw new IllegalStateException("تعذرت حماية المفاتيح في ويندوز (DPAPI): " + err[0].trim());
        if (protect) return out;
        return new String(java.util.Base64.getDecoder().decode(out), StandardCharsets.UTF_8);
    }

    public static synchronized void put(String name, String value) throws Exception {
        if (keychain()) {
            Res r = run("/usr/bin/security", "add-generic-password", "-U", "-s", SERVICE, "-a", name, "-l", "المحاسب الذكي — " + name, "-w", value);
            if (r.code != 0) throw new IllegalStateException("تعذر الحفظ في سلسلة المفاتيح (" + r.code + "): " + r.err.trim());
            String back = get(name);
            if (!value.equals(back)) throw new IllegalStateException("سلسلة المفاتيح لم تحفظ القيمة كما هي");
            return;
        }
        File d = dir();
        String fn = fileName(name);
        File tmp = new File(d, fn + ".tmp");
        String stored = value;
        if (windows()) {
            try {
                String p = DPAPI + dpapi(true, value);
                if (value.equals(dpapi(false, p.substring(DPAPI.length())))) stored = p;
            } catch (Exception e) {
                // ويندوز منع PowerShell (سياسة مؤسسة/مضاد فيروسات): يبقى الملف في مجلد المستخدم الخاص بلا DPAPI بدل أن يتوقف البرنامج
                System.err.println("DPAPI غير متاح: " + e);
            }
        }
        Files.write(tmp.toPath(), stored.getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // نظام بلا صلاحيات POSIX
        }
        Files.move(tmp.toPath(), new File(d, fn).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // ملف قديم بالاسم الخام (ماك/لينكس قبل 11.3) لم يعد مطلوبًا
        if (!fn.equals(name) && !windows()) new File(d, name).delete();
        if (windows()) winCache.put(name, value); else winCache.remove(name);
    }

    public static synchronized void delete(String name) throws Exception {
        if (keychain()) {
            run("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", name);
            return;
        }
        winCache.remove(name);
        new File(dir(), fileName(name)).delete();
        if (!fileName(name).equals(name) && !windows()) new File(dir(), name).delete();
    }

    static final class Res {
        int code;
        String out = "", err = "";
    }

    static Res run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        Res r = new Res();
        r.out = read(p.getInputStream());
        r.err = read(p.getErrorStream());
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("انتهت مهلة سلسلة المفاتيح");
        }
        r.code = p.exitValue();
        return r;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }
}
