# Clamshell Beats dev loop for the AYN Thor.
# Run it with thor-dev.bat (double-click). It:
#   1. waits for the Thor over USB,
#   2. downloads + installs the newest green build (the repo's rolling "dev" pre-release; falls back to a
#      thorbox-debug-apk*.zip or ClamshellBeats*.apk in this folder),
#   3. starts the app,
#   4. opens one scrcpy mirror window per Thor screen,
#   5. records the Thor's log into the thor-logs folder here until you close the window.
# Everything it does is written to thor-dev-log.txt next to it.

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $here 'thor-dev-log.txt'
$logDir = Join-Path $here 'thor-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logcat = Join-Path $logDir ('thor-logcat-{0:yyyyMMdd-HHmmss}.txt' -f (Get-Date))
$marker = Join-Path $here '.thor-dev-last-install'
$pkg = 'com.clamshellbeats.app'
$activity = "$pkg/com.thorbox.app.MainActivity"

function Log([string]$m) {
    $line = "[{0:HH:mm:ss}] {1}" -f (Get-Date), $m
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding UTF8
}
Set-Content -Path $log -Value "Clamshell Beats dev loop - $(Get-Date)" -Encoding UTF8

# ---- find scrcpy + adb (winget installs them side by side) ----
$cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
if (-not $cmd) {
    Log 'scrcpy is not installed. In a terminal run:  winget install --exact Genymobile.scrcpy  then open a new window and try again.'
    Read-Host 'Press Enter to close'; exit 1
}
$scrcpy = $cmd.Source
$target = (Get-Item $scrcpy).Target
if ($target) { $scrcpy = @($target)[0] }
$adb = Join-Path (Split-Path $scrcpy) 'adb.exe'
if (-not (Test-Path $adb)) {
    $a = Get-Command adb -ErrorAction SilentlyContinue
    if ($a) { $adb = $a.Source } else { Log 'adb.exe not found next to scrcpy.'; Read-Host 'Press Enter to close'; exit 1 }
}
Log "scrcpy: $scrcpy"

# ---- wait for the Thor ----
Log 'Waiting for the Thor (if it asks "Allow USB debugging?", tick Always allow and tap Allow)...'
& $adb start-server | Out-Null
& $adb wait-for-device
& $adb devices -l | ForEach-Object { if ($_) { Log $_ } }

# ---- crashes since the Thor booted (Android keeps them in a separate buffer, even if nobody was logging) ----
$crashFile = Join-Path $logDir ('thor-crashes-{0:yyyyMMdd-HHmmss}.txt' -f (Get-Date))
cmd /c "`"$adb`" logcat -b crash -d > `"$crashFile`" 2>&1"
$crashLines = @(Get-Content $crashFile | Where-Object { $_ -match 'clamshell|thorbox' }).Count
Log "Crash buffer saved to thor-logs\$(Split-Path $crashFile -Leaf) ($crashLines lines mention the app)."

# ---- get the newest build: the repo's rolling "dev" pre-release first, else a zip/apk in this folder ----
$repo = 'benm0ra/thorbox'
$buildDir = Join-Path $here 'thor-builds'
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
$apk = $null; $stamp = $null; $label = $null
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $base = "https://github.com/$repo/releases/download/dev"
    $infoFile = Join-Path $buildDir 'build.txt'       # to a file: PowerShell 5 hands back raw bytes for release assets
    Invoke-WebRequest -UseBasicParsing -Uri "$base/build.txt" -OutFile $infoFile -TimeoutSec 20
    $info = @(Get-Content $infoFile | Where-Object { $_.Trim() })
    $sha = $info[0].Trim()
    $stamp = "dev|$sha"
    $label = "dev build $($sha.Substring(0, 7)) ($($info[1].Trim()))"
    $apk = Join-Path $buildDir "ClamshellBeats-dev-$($sha.Substring(0, 7)).apk"
    if (-not (Test-Path $apk)) {
        Log "Downloading $label ..."
        Invoke-WebRequest -UseBasicParsing -Uri "$base/ClamshellBeats-dev-debug.apk" -OutFile "$apk.part" -TimeoutSec 300
        Move-Item -Force "$apk.part" $apk
    }
} catch {
    Log "Couldn't fetch the dev build from GitHub ($($_.Exception.Message)). Looking in this folder instead."
    $apk = $null
}
if (-not $apk) {
    $cand = Get-ChildItem -Path $here -File |
        Where-Object { $_.Name -match '^(thorbox-debug-apk|ClamshellBeats|clamshell).*\.(zip|apk)$' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($cand) {
        $stamp = "$($cand.Name)|$($cand.LastWriteTimeUtc.Ticks)"; $label = $cand.Name; $apk = $cand.FullName
        if ($cand.Extension -eq '.zip') {
            $dst = Join-Path $env:TEMP 'clamshell-apk'
            Remove-Item $dst -Recurse -Force -ErrorAction SilentlyContinue
            Expand-Archive -Path $cand.FullName -DestinationPath $dst -Force
            $apk = (Get-ChildItem $dst -Recurse -Filter *.apk | Select-Object -First 1).FullName
        }
    }
}

# ---- install it (skipped when that exact build is already on the Thor) ----
$last = if (Test-Path $marker) { (Get-Content $marker -Raw).Trim() } else { '' }
if (-not $apk) {
    Log 'No build found. Skipping install.'
} elseif ($last -eq $stamp) {
    Log "Already installed: $label. Skipping install."
} else {
    Log "Installing $label ..."
    $out = & $adb install -r -d $apk 2>&1 | ForEach-Object { "$_" }
    $out | ForEach-Object { Log $_ }
    if ($out -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
        Log 'This build is signed differently from the copy on the Thor.'
        $ans = Read-Host 'Uninstall the old copy and install this one? It DELETES the app''s saved projects and presets on the Thor. Type yes to continue'
        if ($ans -eq 'yes') {
            & $adb uninstall $pkg 2>&1 | ForEach-Object { Log "$_" }
            $out = & $adb install $apk 2>&1 | ForEach-Object { "$_" }
            $out | ForEach-Object { Log $_ }
        } else { Log 'Left the old copy installed.' }
    }
    if ($out -match 'Success') { Set-Content -Path $marker -Value $stamp }
}

# ---- start the app ----
& $adb logcat -c 2>&1 | Out-Null
& $adb shell am start -S -n $activity 2>&1 | ForEach-Object { Log "$_" }
Start-Sleep -Seconds 2

# ---- one mirror per screen ----
Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
$list = & $scrcpy --list-displays 2>&1 | ForEach-Object { "$_" }
$list | ForEach-Object { Log "  $_" }
$ids = @($list | Select-String -Pattern '--display-id=(\d+)' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
if ($ids.Count -eq 0) { Log 'Could not list the screens; mirroring the main screen only.'; $ids = @('0') }
foreach ($id in $ids) {
    Log "Opening mirror for display $id"
    $name = if ($id -eq '0') { 'Thor-TOP' } else { "Thor-BOTTOM-$id" }
    Start-Process -FilePath $scrcpy -ArgumentList @("--display-id=$id", "--window-title=$name", '--no-audio', '--stay-awake') -WindowStyle Minimized
    Start-Sleep -Milliseconds 800
}

# ---- record the log until this window is closed ----
Log "Recording the Thor's log to thor-logs\$(Split-Path $logcat -Leaf). Leave this window open while testing; close it to stop."
Start-Process -FilePath $adb -ArgumentList @('logcat', '-v', 'time', '*:I') -NoNewWindow -Wait -RedirectStandardOutput $logcat
