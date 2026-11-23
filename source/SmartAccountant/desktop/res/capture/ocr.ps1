# 12.1 المحاسب الذكي — قراءة صورة ما تحت الإطار بقارئ الصور المدمج في ويندوز (Windows.Media.Ocr)، حين لا تعطي
# النافذة نصها لميزة إمكانية الوصول. يعمل على الجهاز نفسه بلا إنترنت. العربية تحتاج أن تكون حزمة اللغة العربية مثبتة في ويندوز،
# وإلا تُقرأ الأرقام والحروف اللاتينية بلغة المستخدم.
# المخرجات: سطر لكل سطر نص: L، x، y، العرض، الارتفاع (بكسل الصورة)، النص بـBase64.
param([string]$Path)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]

$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$t) {
    $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op))
    $null = $task.Wait(-1)
    return $task.Result
}
function B64([string]$s) { return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)) }

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$dec = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bmp = Await ($dec.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

$engine = $null
$ar = [Windows.Globalization.Language]::new('ar')
if ([Windows.Media.Ocr.OcrEngine]::IsLanguageSupported($ar)) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($ar) }
if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
if ($null -eq $engine) { exit 0 }
$res = Await ($engine.RecognizeAsync($bmp)) ([Windows.Media.Ocr.OcrResult])
foreach ($line in $res.Lines) {
    $x1 = [double]::MaxValue; $y1 = [double]::MaxValue; $x2 = 0; $y2 = 0
    foreach ($w in $line.Words) {
        $b = $w.BoundingRect
        if ($b.X -lt $x1) { $x1 = $b.X }
        if ($b.Y -lt $y1) { $y1 = $b.Y }
        if (($b.X + $b.Width) -gt $x2) { $x2 = $b.X + $b.Width }
        if (($b.Y + $b.Height) -gt $y2) { $y2 = $b.Y + $b.Height }
    }
    if ($x2 -le 0) { continue }
    "L`t{0}`t{1}`t{2}`t{3}`t{4}" -f [int]$x1, [int]$y1, [int]($x2 - $x1), [int]($y2 - $y1), (B64 $line.Text)
}
