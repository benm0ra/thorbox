# Clamshell Beats: screenshot tour on a real AYN Thor (both screens).
# Run it with thor-shots.bat (double-click) with the Thor plugged in and USB debugging allowed.
#
# It starts the app in a SCRATCH session (your projects are saved first and never touched), loads the
# demo break, fills every track with a pattern, presses play, then walks through every view, deck,
# settings page and a few themes, saving BOTH screens each time to the thor-shots folder next to this
# script. At the end it closes the app; the next launch reopens your project as usual.
# Needs a debug / dev build (release builds ignore the tour's launch extras).

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here 'thor-shots'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$pkg = 'com.clamshellbeats.app'
$act = "$pkg/com.thorbox.app.MainActivity"

$cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
$adb = $null
if ($cmd) { $src = $cmd.Source; $t = (Get-Item $src).Target; if ($t) { $src = @($t)[0] }; $adb = Join-Path (Split-Path $src) 'adb.exe' }
if (-not $adb -or -not (Test-Path $adb)) { $a = Get-Command adb -ErrorAction SilentlyContinue; if ($a) { $adb = $a.Source } }
if (-not $adb) { Write-Host 'adb not found (install scrcpy with: winget install --exact Genymobile.scrcpy)'; Read-Host 'Press Enter'; exit 1 }

Write-Host 'Waiting for the Thor...'
& $adb wait-for-device

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
foreach ($th in 'cyberpunk', 'pastel', 'terminal') { Go @('--es', 'theme', $th, '--es', 'view', 'DRUM'); Shot "theme-$th" 3 }
Go @('--es', 'theme', 'genesis')

Write-Host 'Done. Closing the app (your project reopens next launch).'
& $adb shell am force-stop $pkg
Write-Host "Screenshots: $out"
Start-Sleep -Seconds 4
