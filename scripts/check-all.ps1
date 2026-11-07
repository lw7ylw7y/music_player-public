<#
.SYNOPSIS
  Syncs this clone with its origin (the WSL repository), runs `checkAll` and records the results in .check-results\ (git-ignored).

.DESCRIPTION
  Run from a terminal in Android Studio, with the emulator already started:
      powershell -ExecutionPolicy Bypass -File scripts\check-all.ps1
  Needs nothing else: it finds Java and the Android SDK itself.

  What it runs is decided by what changed since each part last passed (.check-results\green.txt): documents and
  scripts need no tests; the JVM tests and lint run when code changed; the emulator tests run only for the package
  (playback, ui, library, text, playlist, widget) whose code or tests changed; a change to shared code (core, resources, manifest,
  build files) runs everything. A story commit is not special: it runs what its changes affect, like any other. -Full
  runs everything, and a full run is expected before a release.

  The emulator tests are skipped unless you add -Emulator (or -Full): since 2026-11-02 the emulators keep getting stuck.
  Without them the script runs the JVM tests, the app's unit tests (manifest and source rules among them) and lint, and
  compiles the emulator test sources, so a mistake in them still shows. Skipped emulator packages are not recorded as passed.

  After a run in which only emulator tests failed (failed.txt), the next run repeats just those test classes when nothing
  but their files changed meanwhile; if they pass, the scopes of the failed run count as passed.

  The emulator tests run on an emulator. If only a phone is connected, the script stops; add -Phone (or
  -Device <serial>) to run them on the phone on purpose. They install a test app, create and delete their own
  files, and uninstall Music Player afterwards, which removes its data.

  Sync rules (nothing is lost; everything it moves aside can be recovered):
    - Uncommitted changes to tracked files are stashed ("check-all auto-stash ..."). A difference in line endings
      only (Windows CRLF against the repository's LF) is not a change and is simply discarded. Untracked files
      (for example the gradle-daemon-jvm.properties that Gradle creates) are left alone.
    - If origin/<branch> is ahead (the same as git pull), the branch fast-forwards.
    - If history was rewritten (an amended commit or a forced push), the old HEAD is kept as branch backup/check-all-<time>,
      then the branch is reset to origin/<branch>.
    - If this clone is ahead of origin (unpushed commits), it runs on what it has.

  Results: .check-results\latest.md (summary, failing tests, compile errors), latest.log (full Gradle output)
  and latest-xml\ (JUnit XML of every test run).
#>
[CmdletBinding()]
param(
    # Run the emulator tests on this device (the serial shown by `adb devices`) instead of the emulator.
    [string]$Device = '',
    # Allow the emulator tests to run on a connected phone when no emulator is running.
    [switch]$Phone,
    # Run everything, whatever has or has not changed.
    [switch]$Full,
    # Also run the emulator tests. They are skipped by default since 2026-11-02, because the emulators keep getting stuck.
    [switch]$Emulator
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$out = Join-Path $root '.check-results'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$log = Join-Path $out 'latest.log'
$summary = Join-Path $out 'latest.md'
$xmlOut = Join-Path $out 'latest-xml'
$started = Get-Date
$notes = New-Object System.Collections.Generic.List[string]
$ranJvm = $true
$ranEmulator = $true
$script:emulatorSkipped = $false
# For a failed run: the emulator test classes that failed, and the scopes that count as passed once they pass (failed.txt).
$script:failedClasses = New-Object System.Collections.Generic.HashSet[string]
$script:greenIfPassed = @()
$scopeLines = New-Object System.Collections.Generic.List[string]
Remove-Item -Recurse -Force $xmlOut -ErrorAction SilentlyContinue
"" | Out-File -FilePath $log -Encoding utf8

# Runs git with its output in the log. Git writes warnings to stderr, which Windows PowerShell 5 would show as a
# red "NativeCommandError"; turning every line into plain text avoids that. $LASTEXITCODE still holds git's result.
function Invoke-Git {
    & git @args 2>&1 | ForEach-Object { $_.ToString() } | ForEach-Object { Log $_ } | Out-Host
}

# Appends a line to the log as UTF-8 (Tee-Object in Windows PowerShell 5 writes UTF-16, which Linux tools cannot read)
# and passes it on to the console.
function Log($line) {
    $line | Out-File -FilePath $log -Append -Encoding utf8
    $line
}

# A step banner with the time, so it is always clear what the script is doing.
function Step($text) {
    Log ("`n[{0}] == {1} ==" -f (Get-Date -Format 'HH:mm:ss'), $text) | Out-Host
}

function Finish($status, $exitCode) {
    "# checkAll: $status (summary is being written)" | Out-File -FilePath $summary -Encoding utf8
    if ($status -like 'NOT RUN*' -or $status -like 'UP TO DATE*') {
        # Nothing ran, so there are no results to show; the result files on disk belong to an earlier run.
        $short = New-Object System.Collections.Generic.List[string]
        $short.Add("# checkAll: $status")
        $short.Add("")
        $short.Add("- Finished: $(Get-Date -Format 's')")
        $short.Add("- Commit: $((git rev-parse --short HEAD) 2>$null) $((git log -1 --format=%s) 2>$null)")
        foreach ($n in $notes) { $short.Add("- Note: $n") }
        foreach ($l in $scopeLines) { $short.Add("- $l") }
        $short.Add("")
        $short.Add("No tests were run. Reason (from the log):")
        Get-Content $log -Encoding UTF8 | Select-Object -Last 6 | ForEach-Object { $short.Add("    $_") }
        $short | Out-File -FilePath $summary -Encoding utf8
        Write-Host ""
        Write-Host "checkAll: $status. Summary: $summary"
        exit $exitCode
    }
    $failures = New-Object System.Collections.Generic.List[string]
    # One entry per test run area: its counts and one line per test.
    $areas = [ordered]@{
        'core (JVM)'              = 'core\build\test-results'
        'app unit tests (JVM)'    = 'app\build\test-results'
        'emulator tests'          = 'app\build\outputs\androidTest-results'
    }
    $tally = New-Object System.Collections.Generic.List[string]
    $listing = New-Object System.Collections.Generic.List[string]
    New-Item -ItemType Directory -Force -Path $xmlOut | Out-Null
    foreach ($area in $areas.Keys) {
        $wasRun = if ($area -eq 'emulator tests') { $script:ranEmulator } else { $script:ranJvm }
        if (-not $wasRun) {
            $tally.Add($(if ($area -eq 'emulator tests' -and $script:emulatorSkipped) { "- ${area}: skipped (the emulators keep getting stuck; add -Emulator to run them). Their sources were compiled." } else { "- ${area}: not run (nothing it covers changed since it last passed)" }))
            continue
        }
        $p = Join-Path $root $areas[$area]
        $n = 0; $bad = 0; $skip = 0
        $rows = New-Object System.Collections.Generic.List[string]
        if (Test-Path $p) {
            Get-ChildItem -Path $p -Recurse -Filter *.xml | ForEach-Object {
                Copy-Item $_.FullName (Join-Path $xmlOut (($area -replace '\W+', '_') + '__' + $_.Name)) -Force
                try {
                    [xml]$x = Get-Content $_.FullName -Raw -Encoding UTF8
                    foreach ($tc in $x.SelectNodes('//testcase')) {
                        $n++
                        $class = ($tc.classname -split '\.')[-1]
                        $fail = $tc.SelectSingleNode('failure')
                        if (-not $fail) { $fail = $tc.SelectSingleNode('error') }
                        if ($fail) {
                            $bad++
                            if ($area -eq 'emulator tests') { [void]$script:failedClasses.Add($tc.classname) }
                            $rows.Add("FAIL  $class.$($tc.name)")
                            $msg = ($fail.InnerText -split "`n" | Select-Object -First 6) -join "`n    "
                            $failures.Add("- [$area] $class.$($tc.name)`n    $msg")
                        } elseif ($tc.SelectSingleNode('skipped')) {
                            $skip++
                            $rows.Add("skip  $class.$($tc.name)")
                        } else {
                            $rows.Add("ok    $class.$($tc.name)")
                        }
                    }
                } catch { $notes.Add("Could not read $($_.Name)") }
            }
        }
        $tally.Add("- ${area}: $n tests, $($n - $bad - $skip) passed, $bad failed, $skip skipped")
        if ($rows.Count) { $listing.Add("### $area"); $rows | Sort-Object | ForEach-Object { $listing.Add("    $_") }; $listing.Add("") }
    }
    $lines = Get-Content $log -Encoding UTF8
    # Kotlin compile errors start with "e: "; javac errors contain lower-case "error:". Test failures are not compile errors.
    $compile = @($lines | Where-Object { $_ -cmatch '^e: ' -or $_ -cmatch '\berror: ' } | Select-Object -Unique -First 40)
    $failedTasks = @($lines | Where-Object { $_ -match '^> Task .* FAILED' -or $_ -match '^FAILURE:' -or $_ -match 'Execution failed for task' })
    $lintTxt = Join-Path $root 'app\build\reports\lint-results-debug.txt'
    $lintIssues = @()
    $lintSummary = ''
    if (Test-Path $lintTxt) {
        $lintIssues = @(Get-Content $lintTxt -Encoding UTF8 | Where-Object { $_ -match ': (Warning|Error|Fatal): ' } | ForEach-Object { ($_ -replace '^.*\\app\\', 'app\') -replace '(.{200}).*', '$1' })
        $lintSummary = (Get-Content $lintTxt -Encoding UTF8 | Where-Object { $_ -match '^\d+ errors?, \d+ warnings?' } | Select-Object -Last 1)
    }
    $head = (git rev-parse --short HEAD) 2>$null
    $subject = (git log -1 --format=%s) 2>$null
    $elapsed = (Get-Date) - $started
    $seconds = [int]$elapsed.TotalSeconds
    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# checkAll: $status")
    $md.Add("")
    $md.Add("- Finished: $(Get-Date -Format 's') (took $seconds s)")
    $md.Add("- Commit: $head $subject")
    $md.Add("- Gradle exit code: $exitCode")
    foreach ($n in $notes) { $md.Add("- Note: $n") }
    foreach ($l in $scopeLines) { $md.Add("- $l") }
    $md.Add("")
    $md.Add("## Tests (from the latest run of each task)")
    $tally | ForEach-Object { $md.Add($_) }
    $md.Add("")
    if ($failedTasks.Count) { $md.Add("## Failed tasks"); $failedTasks | ForEach-Object { $md.Add("    $_") }; $md.Add("") }
    if ($compile.Count) { $md.Add("## Compile errors"); $compile | ForEach-Object { $md.Add("    $_") }; $md.Add("") }
    if ($failures.Count) { $md.Add("## Failing tests"); $failures | ForEach-Object { $md.Add($_) }; $md.Add("") }
    $md.Add("## Lint")
    if (-not $script:ranJvm) { $md.Add("- not run (no code changed since it last passed)") }
    elseif ($lintSummary) { $md.Add("- $lintSummary") } elseif (-not (Test-Path $lintTxt)) { $md.Add("- no lint report found") } else { $md.Add("- no problems reported") }
    if ($script:ranJvm) { $lintIssues | ForEach-Object { $md.Add("    $_") } }
    $md.Add("")
    $md.Add("## Every test")
    $listing | ForEach-Object { $md.Add($_) }
    $md.Add("## Last 40 lines of the Gradle output")
    $lines | Select-Object -Last 40 | ForEach-Object { $md.Add("    $_") }
    $md | Out-File -FilePath $summary -Encoding utf8
    # A run in which only emulator tests failed is remembered, so that the next run can repeat just those test classes when
    # only their files changed (see "retry" below). Any other outcome clears it.
    $failedFile = Join-Path $out 'failed.txt'
    $onlyEmulator = ($failures.Count -gt 0) -and ($script:failedClasses.Count -gt 0) -and ($script:greenIfPassed.Count -gt 0) -and ($compile.Count -eq 0) `
        -and (@($failures | Where-Object { $_ -notmatch '^- \[emulator tests\]' }).Count -eq 0) `
        -and (@($failedTasks | Where-Object { $_ -match 'Task :' -and $_ -notmatch 'connectedDebugAndroidTest' }).Count -eq 0)
    if ($script:emulatorSkipped -or -not $script:ranEmulator) {
        # The emulator tests did not run, so what is saved about their last failure stays as it is.
    } elseif ($status -eq 'FAILED' -and $onlyEmulator) {
        @("commit=$((git rev-parse HEAD).Trim())", "green=$($script:greenIfPassed -join ',')", "classes=$(@($script:failedClasses) -join ',')") |
            Out-File -FilePath $failedFile -Encoding ascii
    } else {
        Remove-Item $failedFile -ErrorAction SilentlyContinue
    }
    Write-Host ""
    Write-Host "checkAll: $status. Summary: $summary"
    $tally | ForEach-Object { Write-Host $_ }
    exit $exitCode
}

# ---- 1. Sync with origin -------------------------------------------------------------------------
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Step 'Step 1 of 4: sync with origin'
Invoke-Git fetch origin
if ($LASTEXITCODE -ne 0) { $notes.Add('git fetch failed (is the WSL distribution running?); ran on the local files'); }
else {
    $branch = (git rev-parse --abbrev-ref HEAD).Trim()
    $remoteRef = "origin/$branch"
    git rev-parse --verify --quiet $remoteRef *> $null
    if ($LASTEXITCODE -ne 0) { $notes.Add("No $remoteRef; ran on the local files") }
    else {
        git diff --ignore-cr-at-eol --quiet 2>$null
        if ($LASTEXITCODE -ne 0) {
            Invoke-Git stash push -m "check-all auto-stash $stamp"
            $notes.Add("Uncommitted changes were stashed as 'check-all auto-stash $stamp' (git stash list)")
        } elseif (git status --porcelain --untracked-files=no) {
            Invoke-Git reset --hard HEAD
            $notes.Add('Discarded differences in line endings only')
        }
        $local = (git rev-parse HEAD).Trim()
        $remote = (git rev-parse $remoteRef).Trim()
        if ($local -eq $remote) { $notes.Add("Already at $remoteRef") }
        else {
            git merge-base --is-ancestor $local $remote
            if ($LASTEXITCODE -eq 0) {
                Invoke-Git merge --ff-only $remoteRef
                $notes.Add("Fast-forwarded to $remoteRef")
            } else {
                git merge-base --is-ancestor $remote $local
                if ($LASTEXITCODE -eq 0) { $notes.Add("This clone is ahead of $remoteRef (unpushed commits); ran on them") }
                else {
                    $backup = "backup/check-all-$stamp"
                    Invoke-Git branch $backup $local
                    Invoke-Git reset --hard $remoteRef
                    $notes.Add("History on origin was rewritten (a commit was amended). Old HEAD kept as branch $backup; reset to $remoteRef")
                }
            }
        }
    }
}

# ---- 2. Java and the Android SDK -------------------------------------------------------------------
Step 'Step 2 of 4: find Java and the Android SDK'
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    foreach ($c in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}
if (-not $env:JAVA_HOME) { Log "JAVA_HOME is not set and Android Studio's bundled Java was not found." | Out-Host; Finish 'NOT RUN (no Java)' 2 }
$sdk = $env:ANDROID_HOME
$props = Join-Path $root 'local.properties'
if (-not $sdk -and (Test-Path $props)) {
    $line = Get-Content $props | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

# ---- 3. Decide what needs checking ------------------------------------------------------------------
Step 'Step 3 of 4: decide what needs checking'
$packageNames = @('playback', 'ui', 'library', 'text', 'playlist', 'widget')
$greenFile = Join-Path $out 'green.txt'

function Get-Green {
    $h = @{}
    if (Test-Path $greenFile) {
        Get-Content $greenFile | ForEach-Object { if ($_ -match '^(\S+)=(\S+)$') { $h[$Matches[1]] = $Matches[2] } }
    }
    return $h
}

function Set-Green($names, $sha) {
    $h = Get-Green
    foreach ($n in $names) { $h[$n] = $sha }
    $h.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" } | Out-File -FilePath $greenFile -Encoding ascii
}

# The files that differ from the commit $sha, committed or not; $null when that commit is unknown here (the history
# was rewritten), in which case everything counts as changed.
function Get-Changed($sha) {
    git cat-file -e "$sha^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    $files = @(git diff --name-only $sha 2>$null) + @(git ls-files --others --exclude-standard 2>$null)
    return @($files | Where-Object { $_ })
}

# What a changed file can break: 'jvm' (JVM tests and lint), an emulator test package, or 'ALL' (shared code: the whole
# app is checked). Documents, scripts and the like break no test.
function Get-Impact($file) {
    $f = $file -replace '\\', '/'
    if ($f -match '^(docs/|scripts/|cli/|README\.md$|CLAUDE\.md$|\.gitignore$|\.gitattributes$)') { return @() }
    if ($f -match '^(core|app)/src/test/') { return @('jvm') }
    # Code in `core` is checked where it is used; the shared parts (logging, errors, test fixtures, build files) are used by all.
    if ($f -match '^core/src/main/kotlin/app/songplayer/core/playlist/') { return @('jvm', 'playlist') }
    if ($f -match '^core/src/main/kotlin/app/songplayer/core/((resume|playback|openfile|sleep)/|Clock\.kt$)') { return @('jvm', 'playback') }
    if ($f -match '^core/src/main/kotlin/app/songplayer/core/widget/') { return @('jvm', 'widget') }
    if ($f -match '^core/src/main/kotlin/app/songplayer/core/library/') { return @('jvm', 'library', 'playback', 'playlist') }
    if ($f -match '^core/src/main/kotlin/app/songplayer/core/text/') { return @('jvm', 'text', 'library') }
    if ($f -match '^core/') { return @('ALL') }
    if ($f -match '^app/src/main/kotlin/app/songplayer/playback/') { return @('jvm', 'playback') }
    if ($f -match '^app/src/main/kotlin/app/songplayer/ui/') { return @('jvm', 'ui') }
    if ($f -match '^app/src/main/kotlin/app/songplayer/library/') { return @('jvm', 'library', 'playback') }
    if ($f -match '^app/src/main/kotlin/app/songplayer/text/') { return @('jvm', 'text', 'library') }
    if ($f -match '^app/src/main/kotlin/app/songplayer/playlist/') { return @('jvm', 'playlist') }
    if ($f -match '^app/src/main/kotlin/app/songplayer/widget/') { return @('jvm', 'widget') }
    if ($f -match '^app/src/androidTest/kotlin/app/songplayer/(playback|ui|library|text|playlist|widget)/') { return @('jvm', $Matches[1]) }
    # The words of the screens are checked by the source rules (jvm) and the screen tests (ui); the playlist database's exported schema by its own tests.
    if ($f -eq 'app/src/main/res/values/strings.xml') { return @('jvm', 'ui') }
    if ($f -match '^app/schemas/') { return @('jvm', 'library', 'playlist') }
    return @('ALL')
}

$head = (git rev-parse HEAD).Trim()
$subject = (git log -1 --format=%s)
$green = Get-Green
$everything = $false
$due = New-Object System.Collections.Generic.HashSet[string]
$onlyClasses = @()
$greenNames = @()

# Retry: the last run failed only in some emulator test classes, and since then nothing changed except the files of those
# classes (and documents or scripts). The rest of the suite passed on the same code, so only those classes are run again; if they
# pass, the scopes of the failed run count as passed. Anything else that changed takes the normal path below. -Full never retries.
$retry = $null
$failedFile = Join-Path $out 'failed.txt'
if (-not $Full -and ($Emulator) -and (Test-Path $failedFile)) {
    $kv = @{}
    Get-Content $failedFile | ForEach-Object { if ($_ -match '^(\w+)=(.*)$') { $kv[$Matches[1]] = $Matches[2] } }
    if ($kv['commit'] -and $kv['classes'] -and $kv['green']) {
        $changedSince = Get-Changed $kv['commit']
        if ($null -ne $changedSince) {
            $classes = @($kv['classes'] -split ',' | Where-Object { $_ })
            $ownFiles = @($classes | ForEach-Object { 'app/src/androidTest/kotlin/' + ($_ -replace '\.', '/') + '.kt' })
            $otherChanges = New-Object System.Collections.Generic.List[string]
            foreach ($file in $changedSince) {
                $name = $file -replace '\\', '/'
                if ((Get-Impact $name).Count -gt 0 -and ($ownFiles -notcontains $name)) { $otherChanges.Add($name) }
            }
            if ($otherChanges.Count -eq 0) { $retry = @{ classes = $classes; green = @($kv['green'] -split ',' | Where-Object { $_ }) } }
        }
    }
}
if ($retry) {
    $onlyClasses = $retry.classes
    $greenNames = $retry.green
    $scopeLines.Add("Scope: only the emulator test class(es) that failed last time ($(($onlyClasses | ForEach-Object { ($_ -split '\.')[-1] }) -join ', ')). Only their files changed since; the rest of the suite passed on the same code. If they pass, the earlier scopes count as passed.")
} elseif ($Full) {
    $everything = $true
    $scopeLines.Add('Scope: everything (-Full)')
} else {
    foreach ($scope in (@('jvm') + $packageNames)) {
        $base = $green[$scope]
        $changed = if ($base) { Get-Changed $base } else { $null }
        if ($null -eq $changed) {
            [void]$due.Add($scope)
            $scopeLines.Add("${scope}: checked (no passing run recorded for the current history)")
            continue
        }
        $hits = @($changed | Where-Object { $i = Get-Impact $_; ($i -contains 'ALL') -or ($i -contains $scope) })
        if (@($changed | Where-Object { (Get-Impact $_) -contains 'ALL' }).Count -gt 0) { $everything = $true }
        if ($hits.Count -gt 0) {
            [void]$due.Add($scope)
            $scopeLines.Add("${scope}: checked ($($hits.Count) changed file(s) since it last passed, for example $($hits[0]))")
        } else {
            $scopeLines.Add("${scope}: skipped (nothing it covers changed since it last passed)")
        }
    }
    # Nothing has passed before (the first run, or the history was rewritten): the whole app is checked, MainActivityTest too.
    if ($due.Count -eq (1 + $packageNames.Count)) { $everything = $true }
    if ($everything) { $scopeLines.Add('Scope: everything (shared code, resources, the manifest or build files changed, or nothing has passed yet)') }
}
if ($retry) {
    $runJvm = $false
    $runEmu = $true
    $packages = @()
} elseif ($everything) {
    $runJvm = $true
    $runEmu = $true
    $packages = @()
} else {
    $runJvm = $due.Contains('jvm')
    $packages = @($packageNames | Where-Object { $due.Contains($_) })
    $runEmu = $packages.Count -gt 0
}
# The emulator tests are skipped unless asked for (-Emulator, or -Full). Then the JVM tests and lint always run when code changed,
# together with a compile of the emulator test sources; nothing is recorded as passed for the emulator packages.
$emulatorSkipped = $runEmu -and -not ($Emulator -or $Full)
if ($emulatorSkipped) {
    $runEmu = $false
    $runJvm = $true
    $script:emulatorSkipped = $true
    $scopeLines.Add('Emulator tests: skipped (decision of 2026-11-02: the emulators keep getting stuck). Add -Emulator to run them. The emulator test sources are still compiled.')
}
$ranJvm = $runJvm
$ranEmulator = $runEmu
if (-not $retry) {
    $greenNames = if ($emulatorSkipped) { @('jvm') } elseif ($everything) { @('jvm') + $packageNames } else { @($(if ($runJvm) { 'jvm' })) + $packages | Where-Object { $_ } }
}
$script:greenIfPassed = $greenNames
if (-not $runJvm -and -not $runEmu) {
    Log 'Nothing to check: only documents or scripts changed since every part last passed.' | Out-Host
    Finish 'UP TO DATE' 0
}
Log ("Running: " + ($(if ($runJvm) { 'JVM tests and lint' } else { 'no JVM tests' })) + '; ' + ($(if ($everything) { 'all emulator tests' } elseif ($retry) { 'only the emulator test class(es) that failed last time' } elseif ($runEmu) { 'emulator tests for ' + ($packages -join ', ') } elseif ($emulatorSkipped) { 'emulator tests skipped (-Emulator runs them)' } else { 'no emulator tests' }))) | Out-Host

if ($runEmu) {
# ---- 3b. An emulator or phone must be running --------------------------------------------------------
$devices = @()
if (Test-Path $adb) {
    & $adb start-server 2>&1 | Out-Null
    # A freshly started adb server needs a moment to list a device that is already running.
    for ($i = 0; $i -lt 30 -and $devices.Count -eq 0; $i++) {
        $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' })
        if ($devices.Count -eq 0) { Start-Sleep -Seconds 2 }
    }
} else {
    Log "adb was not found at $adb (set ANDROID_HOME or sdk.dir in local.properties)." | Out-Host
}
if ($devices.Count -eq 0) {
    "No emulator or phone is connected (adb devices is empty). Start the virtual device and run again." | ForEach-Object { Log $_ } | Out-Host
    Finish 'NOT RUN (no emulator)' 3
}
# The emulator tests install a test app, grant permissions, create and delete their own files, and uninstall the
# app afterwards, taking its data with it. So they run on an emulator unless a device is named on purpose.
$serials = @($devices | ForEach-Object { ($_ -split '\s+')[0] })
$emulators = @($serials | Where-Object { $_ -like 'emulator-*' })
if ($Device) {
    if ($serials -notcontains $Device) {
        Log "The device '$Device' is not connected. Connected: $($serials -join ', ')" | Out-Host
        Finish 'NOT RUN (device not connected)' 3
    }
    $target = $Device
} elseif ($emulators.Count -gt 0) {
    $target = $emulators[0]
} elseif ($Phone -and $serials.Count -eq 1) {
    $target = $serials[0]
} else {
    Log ("No emulator is running; connected: $($serials -join ', '). Start the virtual device and run again. " +
        "To test on the connected phone on purpose, run the script with -Phone (or -Device <serial>): the tests then " +
        "install a test app on it and remove Music Player, with its data, afterwards.") | Out-Host
    Finish 'NOT RUN (no emulator)' 3
}
# Gradle and adb both honour this variable, so every command below goes to this one device.
$env:ANDROID_SERIAL = $target
$notes.Add("Tests run on: $target (devices connected: $($serials.Count))")

# A device that is listed may still be booting; installing on it then hangs (ShellCommandUnresponsiveException).
$ready = $false
for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
    $boot = (& $adb shell getprop sys.boot_completed 2>$null) -join ''
    $pm = (& $adb shell pm path android 2>$null) -join ''
    if ($boot.Trim() -eq '1' -and $pm -match 'package:') { $ready = $true } else { Start-Sleep -Seconds 2 }
}
if (-not $ready) {
    Log "The emulator is listed but not ready (still booting, or frozen). Wait for the home screen, or cold-boot it in Device Manager, then run again." | Out-Host
    Finish 'NOT RUN (emulator not ready)' 4
}

}

# ---- 4. Run what was chosen -------------------------------------------------------------------------
Step 'Step 4 of 4: gradlew (the emulator tests, when chosen, are the slowest part)'
$tasks = @()
if ($runJvm) { $tasks += ':core:test', ':app:testDebugUnitTest', ':app:lintDebug' }
if ($runEmu) { $tasks += ':app:connectedDebugAndroidTest' }
if ($emulatorSkipped) { $tasks += ':app:compileDebugAndroidTestKotlin' }
$gradleArgs = @($tasks) + @('--continue', '--console=plain')
if ($retry) {
    # AndroidJUnitRunner runs only these classes.
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.class=" + ($onlyClasses -join ',')
} elseif ($runEmu -and -not $everything) {
    # AndroidJUnitRunner runs only the tests of these packages.
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.package=" + (($packages | ForEach-Object { "app.songplayer.$_" }) -join ',')
}
# Gradle runs as a child process writing to files, so this script can keep printing while it works: new Gradle lines
# as they appear, and every 20 seconds a heartbeat with the current task and, during the emulator tests, the test
# that the emulator reports as started.
$gradleOut = Join-Path $out 'gradle-stdout.tmp'
$gradleErr = Join-Path $out 'gradle-stderr.tmp'
$gradle = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $gradleArgs `
    -WorkingDirectory $root -NoNewWindow -PassThru -RedirectStandardOutput $gradleOut -RedirectStandardError $gradleErr
$null = $gradle.Handle # without this, ExitCode can come back empty in Windows PowerShell 5
$printed = 0
$lastTask = 'starting Gradle'
$lastBeat = Get-Date
$clock = [Diagnostics.Stopwatch]::StartNew()
function Show-NewGradleLines {
    if (-not (Test-Path $gradleOut)) { return }
    $all = @(Get-Content $gradleOut -Encoding UTF8 -ErrorAction SilentlyContinue)
    for ($i = $script:printed; $i -lt $all.Count; $i++) {
        Log $all[$i] | Out-Host
        if ($all[$i] -match '^> Task (\S+)') { $script:lastTask = $Matches[1] }
    }
    $script:printed = $all.Count
}
while (-not $gradle.HasExited) {
    Start-Sleep -Seconds 2
    Show-NewGradleLines
    if (((Get-Date) - $lastBeat).TotalSeconds -ge 20) {
        $lastBeat = Get-Date
        $detail = ''
        if ($lastTask -like '*connected*AndroidTest*') {
            $lastStarted = (& $adb logcat -d -s TestRunner:I -t 400 2>$null | Select-String 'started:' | Select-Object -Last 1)
            if ($lastStarted) { $detail = '; emulator test: ' + ($lastStarted.ToString() -replace '^.*started:\s*', '') }
            else { $detail = '; installing the app on the emulator or starting the tests' }
        }
        Log ('[{0}] still running ({1}:{2:00} elapsed), task: {3}{4}' -f (Get-Date -Format 'HH:mm:ss'), [int][math]::Floor($clock.Elapsed.TotalMinutes), $clock.Elapsed.Seconds, $lastTask, $detail) | Out-Host
    }
}
$gradle.WaitForExit()
Show-NewGradleLines
if (Test-Path $gradleErr) { Get-Content $gradleErr | ForEach-Object { Log $_ | Out-Host } }
Remove-Item $gradleOut, $gradleErr -ErrorAction SilentlyContinue
$code = $gradle.ExitCode
if ($null -eq $code) { $code = if ((Get-Content $log -Encoding UTF8) -match '^BUILD SUCCESSFUL') { 0 } else { 1 } }
if ($code -eq 0) {
    # What passed is remembered, so the next run checks only what changes after this commit.
    Set-Green $greenNames $head
    Finish 'PASSED' 0
} else {
    Finish 'FAILED' $code
}
