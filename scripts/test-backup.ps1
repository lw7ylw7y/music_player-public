<#
.SYNOPSIS
  Tests that the playlists survive a backup, an uninstall and a reinstall (story E4, R-17), on a phone connected with adb.

.DESCRIPTION
  Uses Google's documented method (developer.android.com/identity/data/testingbackup): the backup goes to the phone's
  LOCAL transport, not to Google's servers, so it can be repeated at once; it tests the backup rules (only playlists.db) and the
  restore. It does not test Google's cloud backup, which needs a Play install or a phone setup and runs about once a day.

  Before you run it: make a playlist with a few songs in Music Player on the phone, and keep the phone connected (USB, or
  wireless debugging as in the README). The script then:
    1. backs up Music Player to the local transport,
    2. copies the installed app to a temporary folder,
    3. UNINSTALLS Music Player, which deletes its data on the phone (the backup is what brings the playlists back),
    4. installs it again from the copy, which makes Android restore the backup,
    5. puts the phone's backup transport back to Google's.
  Then open Music Player: the playlists should be back, with their names and order, and the Library fills again after a scan.

    powershell -ExecutionPolicy Bypass -File scripts\test-backup.ps1            (-Device <serial> if more than one device)
#>
[CmdletBinding()]
param(
    [string]$Package = 'app.songplayer',
    [string]$Device = ''
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
Write-Host "Device: $Device. Package: $Package"
Write-Host "This UNINSTALLS Music Player on that device, deleting its data; only the backed-up playlists come back." -ForegroundColor Yellow

# adb writes some progress lines to stderr; with the strict setting above PowerShell would stop on them.
function Adb {
    $ErrorActionPreference = 'Continue'
    & $adb @args 2>&1 | ForEach-Object { $_.ToString() }
}

Write-Host "1. Backing up to the local transport"
Adb shell bmgr enable true | Out-Null
$selected = (Adb shell bmgr transport com.android.localtransport/.LocalTransport) -join "`n"
if ($selected -notmatch 'Selected transport') { throw "Could not select the local backup transport: $selected" }
Adb shell "settings put secure backup_local_transport_parameters 'is_encrypted=true'" | Out-Null
try {
    $result = (Adb shell bmgr backupnow $Package) -join "`n"
    if ($result -notmatch [regex]::Escape("Package $Package with result: Success")) { throw "The backup failed:`n$result" }
    Write-Host "   backed up"

    Write-Host "2. Copying the installed app"
    $temp = Join-Path ([IO.Path]::GetTempPath()) "song-player-backup-test"
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    $apks = @()
    foreach ($line in (Adb shell pm path $Package)) {
        if ($line -match '^package:(.+)$') {
            $target = Join-Path $temp ("part" + $apks.Count + ".apk")
            Adb pull $Matches[1].Trim() $target | Out-Null
            $apks += $target
        }
    }
    if ($apks.Count -eq 0) { throw "Music Player is not installed on $Device." }

    Write-Host "3. Uninstalling"
    Adb shell pm uninstall --user 0 $Package | Out-Null

    Write-Host "4. Installing again (this restores the backup)"
    $install = (Adb install-multiple -t --user 0 @apks) -join "`n"
    if ($install -notmatch 'Success') { throw "The install failed:`n$install" }
} finally {
    Adb shell bmgr transport com.google.android.gms/.backup.BackupTransportService | Out-Null
    Write-Host "5. Backup transport set back to Google's"
}
Write-Host ""
Write-Host "Done. Open Music Player on the phone. Playlists should show the playlists you made, with the same names and order." -ForegroundColor Green
Write-Host "If they do not, say so (and say what the phone showed); that decides whether V1 ships without R-17."
