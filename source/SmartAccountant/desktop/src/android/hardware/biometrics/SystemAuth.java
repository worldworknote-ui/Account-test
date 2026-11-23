package android.hardware.biometrics;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 11.2: تحقق النظام على الكمبيوتر بدل البصمة في الهاتف:
 * <ul>
 * <li>ماك: Touch ID أو كلمة سر الماك (LocalAuthentication بسياسة «مالك الجهاز») عبر osascript المدمج في النظام.</li>
 * <li>ويندوز: Windows Hello (الوجه أو البصمة أو رمز PIN) عبر PowerShell المدمج في النظام.</li>
 * </ul>
 * لا شيء يُنزَّل ولا يُثبَّت، ولا يخرج أي شيء من الجهاز. التوفّر يُفحص مرة واحدة في الخلفية ويُحفظ.
 */
public final class SystemAuth {
    private SystemAuth() {}

    static final String OS = System.getProperty("os.name", "").toLowerCase();
    static final boolean MAC = OS.contains("mac"), WIN = OS.contains("win");
    /** null = لم يُفحص بعد. */
    private static volatile Boolean available;

    /** للتجارب: يمكن فرض النتيجة (sa.auth=yes|no|fail). */
    static String forced() { return android.content.Context.testMode() ? System.getProperty("sa.auth", "") : ""; }

    /** يبدأ الفحص في الخلفية (عند تشغيل البرنامج) لئلا ينتظر المستخدم لاحقًا. */
    public static void warmUp() {
        if (available != null) return;
        Thread t = new Thread(SystemAuth::available, "system-auth-check");
        t.setDaemon(true);
        t.start();
    }

    /** هل في الجهاز قفل يمكن التحقق به (Touch ID/كلمة سر الماك، أو Windows Hello)؟ */
    public static boolean available() {
        Boolean a = available;
        if (a != null) return a;
        boolean r;
        String f = forced();
        if (!f.isEmpty()) r = !"no".equals(f);
        else if (MAC) r = "yes".equals(run(macScript("check", ""), 20));
        else if (WIN) r = "Available".equals(run(winScript("check", ""), 25));
        else r = false;
        available = r;
        return r;
    }

    /** اسم طريقة التحقق للعرض. */
    public static String methodName() {
        if (MAC) return "Touch ID أو كلمة سر الماك";
        if (WIN) return "Windows Hello (الوجه أو البصمة أو رمز PIN)";
        return "قفل النظام";
    }

    /** نتيجة التحقق: 0 نجح، 1 أُلغي/فشل، 2 غير متاح. يعمل خارج خيط الواجهة. */
    public static int verify(String reason) {
        String f = forced();
        if (!f.isEmpty()) return "yes".equals(f) ? 0 : "no".equals(f) ? 2 : 1;
        if (!available()) return 2;
        String out;
        if (MAC) out = run(macScript("verify", reason), 120);
        else if (WIN) out = run(winScript("verify", reason), 120);
        else return 2;
        if (MAC) return "ok".equals(out) ? 0 : 1;
        return "Verified".equals(out) ? 0 : "DeviceNotPresent".equals(out) || "NotConfiguredForUser".equals(out) || "DisabledByPolicy".equals(out) ? 2 : 1;
    }

    // ------------------------------------------------------------------ ماك
    static String[] macScript(String mode, String reason) {
        String js = "ObjC.import('LocalAuthentication');ObjC.import('Foundation');"
                + "function run(argv){var c=$.LAContext.alloc.init;"
                + "if(argv[0]=='check'){return c.canEvaluatePolicyError(2,null)?'yes':'no';}"
                + "var done=false,ok=false;"
                + "c.evaluatePolicyLocalizedReasonReply(2,argv[1],function(s,e){ok=s;done=true;});"
                + "var n=0;while(!done&&n<1200){$.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.1));n++;}"
                + "return ok?'ok':'fail';}";
        return new String[]{"/usr/bin/osascript", "-l", "JavaScript", "-e", js, mode, reason.isEmpty() ? "التحقق من هويتك" : reason};
    }

    // ------------------------------------------------------------------ ويندوز
    static String[] winScript(String mode, String reason) {
        String r = (reason.isEmpty() ? "التحقق من هويتك" : reason).replace("'", "''");
        String ps = "$ErrorActionPreference='Stop';"
                + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                + "$m=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
                + "function Aw($o,$t){$k=$m.MakeGenericMethod($t).Invoke($null,@($o));$k.Wait(-1)|Out-Null;$k.Result};"
                + "[Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime]|Out-Null;"
                + ("check".equals(mode)
                ? "Write-Output (Aw ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability]))"
                : "Write-Output (Aw ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync('" + r + "')) ([Windows.Security.Credentials.UI.UserConsentVerificationResult]))");
        String enc = java.util.Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
        return new String[]{"powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", enc};
    }

    /** يشغّل أمرًا من أدوات النظام ويعيد آخر سطر من مخرجاته (أو "" عند أي خطأ). */
    static String run(String[] cmd, int seconds) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            Thread rd = new Thread(() -> {
                try (InputStream in = p.getInputStream()) { in.transferTo(bo); } catch (Exception ignored) { /* انتهى */ }
            });
            rd.setDaemon(true);
            rd.start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            rd.join(2000);
            String s = bo.toString(StandardCharsets.UTF_8).trim();
            int nl = s.lastIndexOf('\n');
            return (nl >= 0 ? s.substring(nl + 1) : s).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
