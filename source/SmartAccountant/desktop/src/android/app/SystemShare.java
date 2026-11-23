package android.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 11.3 (تجريبي): قائمة المشاركة الخاصة بالنظام، فيصل الملف إلى واتساب (أو غيره) مباشرة دون لصق:
 * <ul>
 * <li>ويندوز 10/11: نافذة «مشاركة» (DataTransferManager) عبر PowerShell المدمج في النظام.</li>
 * <li>ماك: قائمة المشاركة (NSSharingServicePicker) عبر osascript المدمج في النظام.</li>
 * </ul>
 * تعمل في عملية منفصلة، فإن فشلت لا يتأثر البرنامج، ويُبلَّغ المتصل لينتقل إلى «نسخ ثم لصق». لا يُنزَّل شيء ولا يُثبَّت.
 */
public final class SystemShare {
    private SystemShare() {}

    static final String OS = System.getProperty("os.name", "").toLowerCase();
    static final boolean MAC = OS.contains("mac"), WIN = OS.contains("win");

    public static boolean supported() { return MAC || WIN; }

    /**
     * يفتح قائمة المشاركة. result: true = ظهرت القائمة، false = تعذّرت (يُستدعى على خيط الواجهة).
     * x,y: موضع القائمة على الشاشة (وسط نافذة البرنامج).
     */
    public static void share(List<File> files, String text, int x, int y, Consumer<Boolean> result) {
        if (!supported()) {
            post(result, false);
            return;
        }
        String[] cmd = MAC ? mac(files, text, x, y) : win(files, text, x, y);
        Thread t = new Thread(() -> {
            boolean shown = false;
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                long until = System.currentTimeMillis() + 15000;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("SA_SHOWN")) {
                            shown = true;
                            post(result, true);
                            break;
                        }
                        if (line.contains("SA_FAIL") || System.currentTimeMillis() > until) break;
                    }
                }
                if (!shown) {
                    p.destroy();
                    post(result, false);
                }
            } catch (Exception e) {
                if (!shown) post(result, false);
            }
        }, "system-share");
        t.setDaemon(true);
        t.start();
    }

    static void post(Consumer<Boolean> c, boolean v) { javax.swing.SwingUtilities.invokeLater(() -> c.accept(v)); }

    // ------------------------------------------------------------------ ماك
    static String[] mac(List<File> files, String text, int x, int y) {
        String js = "ObjC.import('AppKit');"
                + "var done=false,keep=[];"
                + "ObjC.registerSubclass({name:'SAShareDelegate',protocols:['NSSharingServicePickerDelegate','NSSharingServiceDelegate'],methods:{"
                + "'sharingServicePicker:didChooseSharingService:':{types:['void',['id','id']],implementation:function(p,s){if(s.isNil()){done=true;}else{var d=$.SAShareDelegate.alloc.init;keep.push(d);s.delegate=d;}}},"
                + "'sharingService:didShareItems:':{types:['void',['id','id']],implementation:function(s,i){done=true;}},"
                + "'sharingService:didFailToShareItems:error:':{types:['void',['id','id','id']],implementation:function(s,i,e){done=true;}}"
                + "}});"
                + "function run(argv){"
                + "var app=$.NSApplication.sharedApplication;app.setActivationPolicy(1);"
                + "var x=parseInt(argv[0]),y=parseInt(argv[1]),text=argv[2];"
                + "var scr=$.NSScreen.mainScreen.frame;"
                + "var w=$.NSWindow.alloc.initWithContentRectStyleMaskBackingDefer($.NSMakeRect(x,scr.size.height-y,2,2),0,2,false);"
                + "w.setLevel(3);w.makeKeyAndOrderFront(null);app.activateIgnoringOtherApps(true);"
                + "var items=[];for(var k=3;k<argv.length;k++){items.push($.NSURL.fileURLWithPath(argv[k]));}"
                + "if(text.length>0){items.push($(text));}"
                + "var picker=$.NSSharingServicePicker.alloc.initWithItems($(items));"
                + "var pd=$.SAShareDelegate.alloc.init;keep.push(pd);picker.delegate=pd;"
                + "picker.showRelativeToRectOfViewPreferredEdge(w.contentView.bounds,w.contentView,1);"
                + "console.log('SA_SHOWN');"
                + "var n=0;while(!done&&n<6000){var ev=app.nextEventMatchingMaskUntilDateInModeDequeue(-1,$.NSDate.dateWithTimeIntervalSinceNow(0.1),$.NSDefaultRunLoopMode,true);"
                + "if(!ev.isNil()){app.sendEvent(ev);}n++;}"
                + "return 'SA_END';}";
        List<String> c = new ArrayList<>();
        c.add("/usr/bin/osascript");
        c.add("-l");
        c.add("JavaScript");
        c.add("-e");
        c.add(js);
        c.add(String.valueOf(x));
        c.add(String.valueOf(y));
        c.add(text == null ? "" : text);
        for (File f : files) c.add(f.getAbsolutePath());
        return c.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ ويندوز
    static String psq(String s) { return "'" + (s == null ? "" : s).replace("'", "''") + "'"; }

    static String[] win(List<File> files, String text, int x, int y) {
        StringBuilder paths = new StringBuilder("@(");
        for (int i = 0; i < files.size(); i++) paths.append(i == 0 ? "" : ",").append(psq(files.get(i).getAbsolutePath()));
        paths.append(")");
        String cls = "Windows.ApplicationModel.DataTransfer.DataTransferManager";
        String ps = "$ErrorActionPreference='Stop';"
                + "try{"
                + "Add-Type -AssemblyName System.Windows.Forms;Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                + "$null=[Windows.ApplicationModel.DataTransfer.DataTransferManager,Windows.ApplicationModel.DataTransfer,ContentType=WindowsRuntime];"
                + "$null=[Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime];"
                + "Add-Type -TypeDefinition @'\n"
                + "using System; using System.Runtime.InteropServices;\n"
                + "[ComImport, Guid(\"3A3DCD6C-3EAB-43DC-BCDE-45671CE800C8\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
                + "public interface ISaDtmInterop { IntPtr GetForWindow(IntPtr hwnd, [In] ref Guid riid); void ShowShareUIForWindow(IntPtr hwnd); }\n"
                + "public static class SaShare {\n"
                + "  [DllImport(\"combase.dll\")] static extern int WindowsCreateString([MarshalAs(UnmanagedType.LPWStr)] string s, int len, out IntPtr h);\n"
                + "  [DllImport(\"combase.dll\")] static extern int RoGetActivationFactory(IntPtr cls, [In] ref Guid iid, out IntPtr f);\n"
                + "  public static ISaDtmInterop Interop() { IntPtr h; WindowsCreateString(\"" + cls + "\", " + cls.length() + ", out h);\n"
                + "    Guid g = typeof(ISaDtmInterop).GUID; IntPtr f; Marshal.ThrowExceptionForHR(RoGetActivationFactory(h, ref g, out f));\n"
                + "    return (ISaDtmInterop)Marshal.GetObjectForIUnknown(f); }\n"
                + "  public static object ForWindow(ISaDtmInterop i, IntPtr hwnd) { Guid g = new Guid(\"A5CAEE9B-8708-49D1-8D36-67D25A8DA00C\"); return Marshal.GetObjectForIUnknown(i.GetForWindow(hwnd, ref g)); }\n"
                + "}\n"
                + "'@;"
                + "$m=([System.WindowsRuntimeSystemExtensions].GetMethods()|?{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
                + "function Aw($o,$t){$k=$m.MakeGenericMethod($t).Invoke($null,@($o));$k.Wait(-1)|Out-Null;$k.Result};"
                + "$paths=" + paths + ";$text=" + psq(text) + ";"
                + "$items=New-Object 'System.Collections.Generic.List[Windows.Storage.IStorageItem]';"
                + "foreach($p in $paths){$items.Add((Aw ([Windows.Storage.StorageFile]::GetFileFromPathAsync($p)) ([Windows.Storage.StorageFile])))};"
                + "$f=New-Object System.Windows.Forms.Form;$f.ShowInTaskbar=$false;$f.FormBorderStyle='None';$f.Opacity=0.01;"
                + "$f.StartPosition='Manual';$f.Location=New-Object System.Drawing.Point(" + x + "," + y + ");$f.Size=New-Object System.Drawing.Size(2,2);$f.TopMost=$true;"
                + "$f.Show();[System.Windows.Forms.Application]::DoEvents();"
                + "$i=[SaShare]::Interop();$dtm=[SaShare]::ForWindow($i,$f.Handle);"
                + "$script:chosen=$null;"
                + "$dtm.add_DataRequested({param($s,$e);$r=$e.Request;$r.Data.Properties.Title='المحاسب الذكي';"
                + "if($text.Length -gt 0){$r.Data.SetText($text)};if($items.Count -gt 0){$r.Data.SetStorageItems($items)}});"
                + "$dtm.add_TargetApplicationChosen({param($s,$e);$script:chosen=[DateTime]::Now});"
                + "$i.ShowShareUIForWindow($f.Handle);Write-Output 'SA_SHOWN';"
                + "$sw=[Diagnostics.Stopwatch]::StartNew();"
                + "while($sw.Elapsed.TotalSeconds -lt 300){[System.Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 40;"
                + "if($script:chosen -ne $null -and ([DateTime]::Now-$script:chosen).TotalSeconds -gt 90){break}};"
                + "$f.Close()"
                + "}catch{Write-Output ('SA_FAIL '+$_.Exception.Message)}";
        String enc = java.util.Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
        String root = System.getenv("SystemRoot");
        File exe = new File(root == null ? "C:\\Windows" : root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        return new String[]{exe.exists() ? exe.getAbsolutePath() : "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-STA", "-WindowStyle", "Hidden", "-EncodedCommand", enc};
    }
}
