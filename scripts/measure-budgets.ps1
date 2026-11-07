<#
.SYNOPSIS
  Measures the performance budgets of Technical Design 12 on the phone (story F1) and writes the numbers to
  .check-results\budgets.md. It only reads: it starts the app, scrolls nothing by itself and changes no setting except
  resetting the phone's frame and battery counters (which Android refills).

.DESCRIPTION
  Connect the phone with adb (USB, or wireless debugging as in the README) and install the final build with the 2,000-song
  library (and later the 5,000-song one). You are asked to do a few things by hand, because only a person can scroll the Library
  or play for 30 minutes. Steps, each printed as it runs:
    1. cold start: force-stops the app and times `am start -W` three times (the phone's own measure of the start; the audio
       part of the 5-second budget is the "Copy diagnostics" timing lines, step 5);
    2. scrolling: you scroll Songs by hand for about 20 seconds; the script reads the share of slow frames;
    3. memory: after you have played music for 30 minutes, the script reads the app's memory;
    4. battery: it resets the battery counters before your 2-hour screen-off run and reads them after;
    5. a reminder to paste Settings > Copy diagnostics (the timing lines) to the report.

    powershell -ExecutionPolicy Bypass -File scripts\measure-budgets.ps1             (-Device <serial> if more than one device)
    powershell -ExecutionPolicy Bypass -File scripts\measure-budgets.ps1 -Step Battery
#>
[CmdletBinding()]
param(
    [string]$Package = 'app.songplayer',
    [string]$Device = '',
    [ValidateSet('All', 'ColdStart', 'Scrolling', 'Memory', 'Battery')]
    [string]$Step = 'All'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = $env:ANDROID_HOME
$props = Join-Path $root 'local.properties'
if (-not $sdk -and (Test-Path $props)) {
    $line = Get-Content $props | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb was not found at $adb (set ANDROID_HOME or sdk.dir in local.properties)." }

$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($Device) {
    if ($devices -notcontains $Device) { throw "The device '$Device' is not connected. Connected: $($devices -join ', ')" }
} elseif ($devices.Count -eq 1) {
    $Device = $devices[0]
} else {
    throw "Connect exactly one device, or name one with -Device. Connected: $($devices -join ', ')"
}
$env:ANDROID_SERIAL = $Device

function Adb {
    $ErrorActionPreference = 'Continue'
    & $adb @args 2>&1 | ForEach-Object { $_.ToString() }
}

$out = Join-Path $root '.check-results'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$report = Join-Path $out 'budgets.md'
function Note([string]$text) { Add-Content -Path $report -Value $text -Encoding UTF8; Write-Host $text }

$model = (Adb shell getprop ro.product.model) -join ''
$version = (Adb shell dumpsys package $Package | Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
Note ""
Note "## $(Get-Date -Format 'yyyy-MM-dd HH:mm') on $model ($Device), $version"

if ($Step -in 'All', 'ColdStart') {
    Note "### Cold start (budget: 5 s to audio; this is to the first screen)"
    for ($i = 1; $i -le 3; $i++) {
        Adb shell am force-stop $Package | Out-Null
        Start-Sleep -Seconds 2
        $r = (Adb shell am start -W -n "$Package/.MainActivity") -join "`n"
        $total = ($r | Select-String 'TotalTime:\s*(\d+)').Matches.Groups[1].Value
        Note "- run ${i}: TotalTime $total ms"
    }
}

if ($Step -in 'All', 'Scrolling') {
    Note "### Scrolling (budget: under 5 % slow frames)"
    Adb shell dumpsys gfxinfo $Package reset | Out-Null
    Read-Host "Scroll the Songs list up and down for about 20 seconds with your finger, then press Enter"
    $g = (Adb shell dumpsys gfxinfo $Package) -join "`n"
    foreach ($key in 'Total frames rendered', 'Janky frames', '50th percentile', '90th percentile', '95th percentile', '99th percentile') {
        $m = ($g -split "`n") | Where-Object { $_ -match [regex]::Escape($key) } | Select-Object -First 1
        if ($m) { Note "- $($m.Trim())" }
    }
}

if ($Step -in 'All', 'Memory') {
    Note "### Memory (budget: 150 MB during playback)"
    Read-Host "Play music for 30 minutes (screen on or off), then press Enter"
    $m = (Adb shell dumpsys meminfo $Package) -join "`n"
    $total = ($m -split "`n") | Where-Object { $_ -match 'TOTAL PSS' -or $_ -match '^\s*TOTAL\s' } | Select-Object -First 2
    foreach ($t in $total) { Note "- $($t.Trim())" }
}

if ($Step -in 'All', 'Battery') {
    Note "### Battery (budget: under 5 % per hour with the screen off) and the 2-hour run"
    $level0 = ((Adb shell dumpsys battery) | Select-String 'level:\s*(\d+)').Matches.Groups[1].Value
    Adb shell dumpsys batterystats --reset | Out-Null
    Note "- start: battery $level0 %, counters reset"
    Read-Host "Start music, connect the Bluetooth headset, turn the screen off, wait 2 hours with no unexpected stop, turn the screen on, then press Enter"
    $level1 = ((Adb shell dumpsys battery) | Select-String 'level:\s*(\d+)').Matches.Groups[1].Value
    $uid = ((Adb shell dumpsys package $Package) | Select-String 'userId=(\d+)').Matches.Groups[1].Value
    Note "- end: battery $level1 %, a drop of $([int]$level0 - [int]$level1) points in the run"
    $stats = (Adb shell dumpsys batterystats $Package) -join "`n"
    $est = ($stats -split "`n") | Where-Object { $_ -match 'Estimated power use' -or $_ -match "UID u0a\d+:|Uid u0a\d+:" } | Select-Object -First 4
    foreach ($e in $est) { Note "- $($e.Trim())" }
    Note "- uid $uid; tell Claude whether the music stopped by itself at any time"
}

Note ""
Note "Also paste Settings > Copy diagnostics after the run (it holds the timing lines for tap to sound and queue build)."
Write-Host "`nWritten to $report"
