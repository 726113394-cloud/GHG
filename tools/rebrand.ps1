# 一次性工程改名：Gost -> GHG（独立插件身份），只在本副本内生效。
# 保留包名 io.Sriptirc_wp_1258.gost，因此不需要改任何 import 路径。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$enc  = New-Object System.Text.UTF8Encoding($false, $true)

function Edit-Text([string]$path, [scriptblock]$fn) {
    $raw = [System.IO.File]::ReadAllBytes($path)
    $hasBom = $raw.Length -ge 3 -and $raw[0] -eq 0xEF -and $raw[1] -eq 0xBB -and $raw[2] -eq 0xBF
    $offset = if ($hasBom) { 3 } else { 0 }
    $text = $enc.GetString($raw, $offset, $raw.Length - $offset)
    $new  = & $fn $text
    if ($new -ne $text) {
        $bytes = $enc.GetBytes($new)
        if ($hasBom) { $bytes = [byte[]](0xEF,0xBB,0xBF) + $bytes }
        [System.IO.File]::WriteAllBytes($path, $bytes)
        Write-Output ("  edited: " + (Resolve-Path -Relative $path))
    }
}

Write-Output "== java sources =="
Get-ChildItem "$root\main" -Recurse -Filter *.java | ForEach-Object {
    Edit-Text $_.FullName {
        param($t)
        $t = [regex]::Replace($t, '\bGost\b', 'GHG')
        $t = $t -replace 'GostCommand', 'GhgCommand'
        $t = $t -replace 'GostAdminCommand', 'GhgAdminCommand'
        $t = $t -replace 'gost\.', 'ghg.'
        $t = $t -replace 'gost_', 'ghg_'
        $t = $t -replace '"gostadmin"', '"ghga"'
        $t = $t -replace '"gost"', '"ghg"'
        $t
    }
}

Write-Output "== rename files =="
$renames = @(
    @{ From = "$root\main\Gost.java";                                To = "$root\main\GHG.java" },
    @{ From = "$root\main\Gost\commands\GostCommand.java";           To = "$root\main\Gost\commands\GhgCommand.java" },
    @{ From = "$root\main\Gost\commands\GostAdminCommand.java";      To = "$root\main\Gost\commands\GhgAdminCommand.java" }
)
foreach ($r in $renames) {
    if (Test-Path $r.From) { Move-Item $r.From $r.To -Force; Write-Output ("  " + (Split-Path $r.From -Leaf) + " -> " + (Split-Path $r.To -Leaf)) }
}

Write-Output "== leftover check =="
$hits = Get-ChildItem "$root\main" -Recurse -Filter *.java | Select-String -Pattern '\bGost\b|GostCommand|gost\.|gost_'
if ($hits) { $hits | ForEach-Object { "  {0}:{1}: {2}" -f $_.Filename, $_.LineNumber, $_.Line.Trim() } } else { Write-Output "  clean" }
