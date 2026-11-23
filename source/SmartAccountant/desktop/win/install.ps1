# تثبيت «المحاسب الذكي» لمستخدم ويندوز الحالي (بلا صلاحيات مدير):
# ينسخ البرنامج إلى %LOCALAPPDATA%\Programs\SmartAccountant وينشئ اختصارًا على سطح المكتب وفي قائمة ابدأ.
# البيانات لا تُمس أبدًا: هي في %APPDATA%\SmartAccountant، فالتحديث بالطريقة نفسها آمن.
$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $MyInvocation.MyCommand.Path
$dst = Join-Path $env:LOCALAPPDATA 'Programs\SmartAccountant'
$name = 'المحاسب الذكي'
try {
    $running = Get-Process -Name 'SmartAccountant' -ErrorAction SilentlyContinue
    if ($running) {
        Write-Host 'Smart Accountant is running. Close it, then press Enter...'
        [void](Read-Host)
        Get-Process -Name 'SmartAccountant' -ErrorAction SilentlyContinue | ForEach-Object { $_.CloseMainWindow() | Out-Null }
        Start-Sleep -Seconds 2
    }
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    foreach ($d in 'app', 'runtime') {
        $t = Join-Path $dst $d
        if (Test-Path $t) { Remove-Item -Recurse -Force $t }
        Copy-Item -Recurse -Force (Join-Path $src $d) $t
    }
    foreach ($f in 'SmartAccountant.exe', 'SmartAccountant.ico', 'uninstall.ps1', 'إلغاء-التثبيت.cmd') {
        Copy-Item -Force (Join-Path $src $f) (Join-Path $dst $f)
    }
    $exe = Join-Path $dst 'SmartAccountant.exe'
    $ico = Join-Path $dst 'SmartAccountant.ico'
    $sh = New-Object -ComObject WScript.Shell
    $menu = Join-Path ([Environment]::GetFolderPath('Programs')) ($name + '.lnk')
    $desk = Join-Path ([Environment]::GetFolderPath('Desktop')) ($name + '.lnk')
    # 11.0: WScript.Shell لا يحفظ اختصارًا اسمه عربي (يصير «?????» فيفشل الحفظ):
    # يُحفظ باسم لاتيني مؤقت ثم يُنقل إلى الاسم العربي (النقل يدعم العربية)، وفشل اختصار لا يوقف التثبيت.
    $made = 0
    foreach ($p in $menu, $desk) {
        try {
            $tmp = Join-Path $env:TEMP ('SmartAccountant-' + [guid]::NewGuid().ToString('N') + '.lnk')
            $l = $sh.CreateShortcut($tmp)
            $l.TargetPath = $exe
            $l.WorkingDirectory = $dst
            $l.IconLocation = $ico + ',0'
            $l.Description = 'Smart Accountant'
            $l.Save()
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $p) | Out-Null
            Move-Item -Force -LiteralPath $tmp -Destination $p
            $made++
        } catch {
            Write-Host ('Shortcut skipped: ' + $_.Exception.Message)
        }
    }
    Write-Host ''
    Write-Host ('Installed: ' + $dst)
    if ($made -gt 0) { Write-Host 'Shortcut created on the Desktop and in the Start menu.' }
    else { Write-Host ('No shortcut. Run: ' + $exe) }
    Start-Process -FilePath $exe -WorkingDirectory $dst
} catch {
    Write-Host ('Install failed: ' + $_.Exception.Message)
    Write-Host 'You can run SmartAccountant.exe directly from this folder.'
    [void](Read-Host 'Press Enter to close')
    exit 1
}
