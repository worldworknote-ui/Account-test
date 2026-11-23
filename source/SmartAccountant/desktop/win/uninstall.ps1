# إزالة البرنامج والاختصارات فقط. البيانات (%APPDATA%\SmartAccountant) تبقى كما هي عمدًا:
# احذفها يدويًا فقط بعد أخذ نسخة احتياطية إن لم تعد تحتاجها.
$ErrorActionPreference = 'Continue'
$dst = Join-Path $env:LOCALAPPDATA 'Programs\SmartAccountant'
$name = 'المحاسب الذكي'
Get-Process -Name 'SmartAccountant' -ErrorAction SilentlyContinue | ForEach-Object { $_.CloseMainWindow() | Out-Null }
Start-Sleep -Seconds 1
Remove-Item -Force -LiteralPath (Join-Path ([Environment]::GetFolderPath('Programs')) ($name + '.lnk')) -ErrorAction SilentlyContinue
Remove-Item -Force -LiteralPath (Join-Path ([Environment]::GetFolderPath('Desktop')) ($name + '.lnk')) -ErrorAction SilentlyContinue
Set-Location $env:TEMP
Remove-Item -Recurse -Force $dst -ErrorAction SilentlyContinue
Write-Host 'Removed. Your data is still in:'
Write-Host (Join-Path $env:APPDATA 'SmartAccountant')
[void](Read-Host 'Press Enter to close')
