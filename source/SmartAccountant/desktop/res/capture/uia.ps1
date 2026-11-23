# 12.1 المحاسب الذكي — «إطار الالتقاط» على ويندوز: يقرأ نصوص النافذة التي تحت الإطار بميزة «إمكانية الوصول»
# المدمجة في ويندوز (UI Automation)، كما تفعل قارئات الشاشة. يقرأ فقط: لا يضغط ولا يكتب في أي برنامج، ولا يتصل بأي شبكة.
# المخرجات سطر لكل عنصر: النوع، x، y، العرض، الارتفاع، النص بـBase64.
#   F نافذة الإطار نفسها (لمعايرة الإحداثيات)، W النافذة التي تحته، R فقاعة/صف، T نص.
# 12.2: الإطار صار شريطًا وحوافّ منفصلة (حتى يمر الماوس إلى واتساب)، فيُعطى داخله بإحداثيات جافا (-Ix…) مع مكان الشريط
#   بإحداثيات جافا (-Jx…) ليُحوَّل إلى بكسل الشاشة بمعايرة الشريط نفسه. -List 1: كل النوافذ الظاهرة (W) من الأعلى للأسفل.
param([int]$SelfPid = 0, [int]$Whole = 0, [int]$List = 0,
      [double]$Ix = 0, [double]$Iy = 0, [double]$Iw = 0, [double]$Ih = 0,
      [double]$Jx = 0, [double]$Jy = 0, [double]$Jw = 0, [double]$Jh = 0)
$ErrorActionPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$A = [System.Windows.Automation.AutomationElement]
$CT = [System.Windows.Automation.ControlType]
$TS = [System.Windows.Automation.TreeScope]
$ALL = [System.Windows.Automation.Condition]::TrueCondition

function B64([string]$s) {
    if ($null -eq $s) { $s = '' }
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s))
}
function Emit([string]$k, $r, [string]$t) {
    if ($null -eq $r -or $r.IsEmpty) { return }
    "{0}`t{1}`t{2}`t{3}`t{4}`t{5}" -f $k, [int]$r.X, [int]$r.Y, [int]$r.Width, [int]$r.Height, (B64 $t)
}

$root = $A::RootElement
$wins = $root.FindAll($TS::Children, $ALL)
$self = $null
foreach ($w in $wins) {
    try {
        $c = $w.Current
        if ($c.ProcessId -eq $SelfPid -and $c.Name -eq 'SA-CAPTURE') { $self = $w; break }
    } catch {}
}
if ($null -ne $self) { Emit 'F' $self.Current.BoundingRectangle '' }
$fr = $null
if ($null -ne $self) {
    $sr = $self.Current.BoundingRectangle
    if ($Iw -gt 0 -and $Jw -gt 0 -and $Jh -gt 0) {
        $sx = $sr.Width / $Jw; $sy = $sr.Height / $Jh
        $fr = New-Object System.Windows.Rect(($sr.X + ($Ix - $Jx) * $sx), ($sr.Y + ($Iy - $Jy) * $sy), ($Iw * $sx), ($Ih * $sy))
    } else { $fr = $sr }
}
if ($List -eq 1) {
    foreach ($w in $wins) {
        try {
            $c = $w.Current
            if ($c.ProcessId -eq $SelfPid -or $c.IsOffscreen) { continue }
            $r = $c.BoundingRectangle
            if ($r.IsEmpty -or $r.Width -lt 80 -or $r.Height -lt 80) { continue }
            Emit 'W' $r $c.Name
        } catch {}
    }
    exit 0
}

# النافذة المقصودة: واتساب بالاسم، أو أعلى نافذة (غير نافذتنا) تحت وسط الإطار
$target = $null
if ($Whole -eq 1 -or $null -eq $fr) {
    foreach ($w in $wins) {
        try {
            $c = $w.Current
            if ($c.ProcessId -eq $SelfPid) { continue }
            if ($c.Name -match 'WhatsApp|واتساب') { $target = $w; break }
        } catch {}
    }
} else {
    $cx = $fr.X + $fr.Width / 2
    $cy = $fr.Y + $fr.Height / 2
    foreach ($w in $wins) {
        try {
            $c = $w.Current
            if ($c.ProcessId -eq $SelfPid -or $c.IsOffscreen) { continue }
            $r = $c.BoundingRectangle
            if ($r.IsEmpty -or $r.Width -lt 50 -or $r.Height -lt 50) { continue }
            if ($cx -ge $r.X -and $cx -le ($r.X + $r.Width) -and $cy -ge $r.Y -and $cy -le ($r.Y + $r.Height)) { $target = $w; break }
        } catch {}
    }
}
if ($null -eq $target) { exit 0 }
$tc = $target.Current
$wr = $tc.BoundingRectangle
Emit 'W' $wr $tc.Name
if ($Whole -eq 1) { exit 0 }

$or = [System.Windows.Automation.OrCondition]::new([System.Windows.Automation.Condition[]]@(
    [System.Windows.Automation.PropertyCondition]::new($A::ControlTypeProperty, $CT::ListItem),
    [System.Windows.Automation.PropertyCondition]::new($A::ControlTypeProperty, $CT::DataItem),
    [System.Windows.Automation.PropertyCondition]::new($A::ControlTypeProperty, $CT::Text)))

function Collect() {
    $cr = New-Object System.Windows.Automation.CacheRequest
    $cr.Add($A::NameProperty)
    $cr.Add($A::ControlTypeProperty)
    $cr.Add($A::BoundingRectangleProperty)
    $cr.TreeScope = $TS::Element
    $act = $cr.Activate()
    try { return $target.FindAll($TS::Descendants, $or) } finally { $act.Dispose() }
}

$els = Collect
# المتصفحات وواتساب ديسكتوب تبني شجرة إمكانية الوصول عند أول طلب: نعيد مرة إن جاءت شبه فارغة
if ($null -eq $els -or $els.Count -lt 3) { Start-Sleep -Milliseconds 800; $els = Collect }
if ($null -eq $els) { exit 0 }

$top = $wr.Y + [Math]::Max(60, $wr.Height / 7)
$n = 0
foreach ($e in $els) {
    if ($n -ge 4000) { break }
    try {
        $r = $e.Cached.BoundingRectangle
        if ($r.IsEmpty) { continue }
        $mx = $r.X + $r.Width / 2
        $my = $r.Y + $r.Height / 2
        $inside = $false
        if ($null -ne $fr) { $inside = ($mx -ge $fr.X -and $mx -le ($fr.X + $fr.Width) -and $my -ge $fr.Y -and $my -le ($fr.Y + $fr.Height)) }
        $head = ($r.Y -le $top)
        if (-not ($inside -or $head)) { continue }
        $ct = $e.Cached.ControlType
        $k = 'T'
        if ($ct -eq $CT::ListItem -or $ct -eq $CT::DataItem) { $k = 'R' }
        Emit $k $r $e.Cached.Name
        $n++
    } catch {}
}
