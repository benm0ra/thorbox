# Clamshell Beats: screenshot tour on a real AYN Thor (both screens).
# Run it with thor-shots.bat (double-click) with the Thor plugged in and USB debugging allowed.
#
# It starts the app in a SCRATCH session (your projects are saved first and never touched), loads the
# demo break, fills every track with a pattern, presses play, then walks through every view, deck,
# settings page and a few themes, saving BOTH screens each time to the thor-shots folder next to this
# script. At the end it closes the app; the next launch reopens your project as usual.
# It first installs the newest dev build (same as thor-dev.bat) so the shots show the current app,
# and writes the installed version to thor-shots\version.txt. Old shots are cleared first.
# Needs a debug / dev build (release builds ignore the tour's launch extras).

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here 'thor-shots'
New-Item -ItemType Directory -Force -Path $out | Out-Null
Get-ChildItem -Path $out -Filter *.png -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
$pkg = 'com.clamshellbeats.app'
$act = "$pkg/com.thorbox.app.MainActivity"

$cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
$adb = $null
if ($cmd) { $src = $cmd.Source; $t = (Get-Item $src).Target; if ($t) { $src = @($t)[0] }; $adb = Join-Path (Split-Path $src) 'adb.exe' }
if (-not $adb -or -not (Test-Path $adb)) { $a = Get-Command adb -ErrorAction SilentlyContinue; if ($a) { $adb = $a.Source } }
if (-not $adb) { Write-Host 'adb not found (install scrcpy with: winget install --exact Genymobile.scrcpy)'; Read-Host 'Press Enter'; exit 1 }

Write-Host 'Waiting for the Thor...'
& $adb wait-for-device

# ---- newest dev build first, so the tour never photographs an old app ----
$marker = Join-Path $here '.thor-dev-last-install'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $base = 'https://github.com/benm0ra/thorbox/releases/download/dev'
    $buildDir = Join-Path $here 'thor-builds'
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    $infoFile = Join-Path $buildDir 'build.txt'
    Invoke-WebRequest -UseBasicParsing -Uri "$base/build.txt" -OutFile $infoFile -TimeoutSec 20
    $sha = (@(Get-Content $infoFile | Where-Object { $_.Trim() }))[0].Trim()
    $stamp = "dev|$sha"
    $last = if (Test-Path $marker) { (Get-Content $marker -Raw).Trim() } else { '' }
    if ($last -ne $stamp) {
        $apk = Join-Path $buildDir "ClamshellBeats-dev-$($sha.Substring(0, 7)).apk"
        if (-not (Test-Path $apk)) {
            Write-Host "Downloading dev build $($sha.Substring(0, 7)) ..."
            Invoke-WebRequest -UseBasicParsing -Uri "$base/ClamshellBeats-dev-debug.apk" -OutFile "$apk.part" -TimeoutSec 300
            Move-Item -Force "$apk.part" $apk
        }
        Write-Host "Installing dev build $($sha.Substring(0, 7)) ..."
        $r = & $adb install -r -d $apk 2>&1 | ForEach-Object { "$_" }
        $r | ForEach-Object { Write-Host "  $_" }
        if ($r -match 'Success') { Set-Content -Path $marker -Value $stamp }
        else { Write-Host 'Install failed: run thor-dev.bat once (it can handle a differently signed copy), then this again.' }
    } else { Write-Host "Newest dev build $($sha.Substring(0, 7)) is already installed." }
} catch { Write-Host "Couldn't check GitHub for a newer build ($($_.Exception.Message)); using what's installed." }
$ver = (& $adb shell dumpsys package $pkg | Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
Set-Content -Path (Join-Path $out 'version.txt') -Value $ver
Write-Host "App on the Thor: $ver"

# Physical display ids (screencap -d wants these, not the logical 0/1).
$ids = @(& $adb shell dumpsys SurfaceFlinger --display-id | ForEach-Object { if ($_ -match '^Display (\d+)') { $Matches[1] } })
Write-Host "Displays: $($ids -join ', ')"

$n = 0
function Shot([string]$label, [int]$wait = 2) {
    Start-Sleep -Seconds $wait
    $script:n++
    $i = 0
    foreach ($id in $ids) {
        $f = Join-Path $out ('{0:D2}-{1}-d{2}.png' -f $script:n, $label, $i)
        cmd /c "`"$adb`" exec-out screencap -p -d $id > `"$f`""
        $i++
    }
    Write-Host "  $label"
}
function Go([string[]]$extras) { & $adb shell am start -n $act @extras | Out-Null }

Write-Host 'Starting a scratch session...'
& $adb shell am force-stop $pkg
& $adb shell am start -W -n $act --ez scratch true | Out-Null
Start-Sleep -Seconds 5
Go @('--ez', 'demo', 'true', '--es', 'view', 'SAMPLER', '--es', 'deck', 'PADS')
Start-Sleep -Seconds 6
Go @('--ez', 'dice', 'true', '--ez', 'play', 'true')
Shot 'sampler-pads' 4

Go @('--es', 'view', 'SYNTH', '--es', 'deck', 'KEYS');  Shot 'synth-keys' 3
Go @('--es', 'view', 'DRUM', '--es', 'deck', 'DRUMS');  Shot 'drums-bigdog' 3
Go @('--es', 'view', 'SEQ', '--es', 'deck', 'STEP');    Shot 'steps' 3
Go @('--es', 'view', 'MIX', '--es', 'deck', 'FX');      Shot 'mixer-fx' 3
Go @('--es', 'view', 'VIZ');                            Shot 'visualizer' 3
Go @('--es', 'view', 'SYNTH', '--ez', 'meters', 'true'); Shot 'meters' 3
Go @('--ez', 'meters', 'false')
foreach ($t in 0..4) { Go @('--ei', 'settings', "$t"); Shot "settings-$t" 2 }
Go @('--ei', 'settings', '-1', '--ez', 'mic', 'true');  Shot 'recorder' 2
Go @('--ez', 'mic', 'false')
# DUCK: keyed off the kick, the sampler + synth dipping, playing (a few frames: the rat pumps)
Go @('--es', 'view', 'DUCK', '--ez', 'duckdemo', 'true', '--ez', 'play', 'true'); Shot 'duck-1' 2
Shot 'duck-2' 0
Go @('--es', 'duckpreset', 'HEAVY_DILLA_PUMP'); Shot 'duck-dilla' 2
# every theme on the screens that draw the most: synth (cat | waveform), drums, mixer
foreach ($th in 'genesis', 'studio', 'bodega', 'cyberpunk', 'vintage', 'pastel', 'terminal', 'grayscale') {
    Go @('--es', 'theme', $th, '--es', 'view', 'SYNTH', '--es', 'deck', 'KEYS'); Shot "theme-$th-synth" 3
    Go @('--es', 'view', 'DRUM', '--es', 'deck', 'DRUMS'); Shot "theme-$th-drums" 2
    Go @('--es', 'view', 'MIX', '--es', 'deck', 'FX');     Shot "theme-$th-mixer" 2
    Go @('--es', 'view', 'DUCK');                            Shot "theme-$th-duck" 2
}
Go @('--es', 'theme', 'genesis')

# ---- the buttons: NAV cursor, a grabbed knob, a pad grid, the cheat sheet, the FX deck ----
function Key([string]$k) { & $adb shell input keyevent "KEYCODE_$k"; Start-Sleep -Milliseconds 400 }
Go @('--es', 'view', 'SYNTH', '--es', 'deck', 'KEYS', '--ez', 'nav', 'true'); Start-Sleep -Seconds 2
Key 'DPAD_RIGHT'; Key 'DPAD_DOWN'; Key 'DPAD_DOWN'; Shot 'nav-cursor' 1
Key 'BUTTON_A'; Key 'DPAD_RIGHT'; Shot 'nav-grab' 1
Key 'BUTTON_B'
Go @('--es', 'view', 'SAMPLER', '--es', 'deck', 'PADS', '--ez', 'nav', 'true'); Start-Sleep -Seconds 2
Key 'BUTTON_R1'; Shot 'nav-bottom' 1                     # the cursor jumps to the bottom screen (deck tabs)
Key 'DPAD_DOWN'; Key 'BUTTON_A'; Key 'DPAD_RIGHT'; Shot 'nav-pad-grid' 1   # into the pads, one to the right
Key 'BUTTON_B'; Key 'BUTTON_B'; Key 'BUTTON_B'
Go @('--ez', 'nav', 'false', '--ez', 'controlmap', 'true'); Shot 'cheat-sheet' 3
Go @('--ez', 'controlmap', 'false', '--es', 'view', 'DRUM', '--es', 'deck', 'FX'); Start-Sleep -Seconds 1
Key 'DPAD_RIGHT'; Key 'DPAD_DOWN'; Shot 'fx-by-buttons' 1

Write-Host 'Done. Closing the app (your project reopens next launch).'
& $adb shell am force-stop $pkg
Write-Host "Screenshots: $out"
Start-Sleep -Seconds 4
