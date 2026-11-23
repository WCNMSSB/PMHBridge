# Install the merged PMHBridge Magisk module and retire the old split modules.
#
#   powershell -ExecutionPolicy Bypass -File tools\install_pmhb.ps1 [-Serial <adb serial>]
#
# The old modules are not deleted: a `disable` marker is written so Magisk skips
# them on the next boot and they can be re-enabled by deleting that file.
#
# Notes learned the hard way:
#   * `magisk --install-module` occasionally extracts only module.prop; the
#     script verifies the tree and falls back to a manual unzip.
#   * Magisk zip extraction does not always keep the executable bit, so every
#     service.d/post-fs-data.d script is chmod 755 afterwards.
param(
    [string]$Serial = "192.168.0.106:5555",
    [string]$Zip = "artifacts\pmhbridge\dist\pmhb-magisk.zip",
    [switch]$SkipReboot
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$adb = "C:\Users\77456\Desktop\platform-tools-r36.0.2-windows\adb.exe"
$zipPath = Join-Path $Root $Zip
if (-not (Test-Path $zipPath)) { throw "missing $zipPath" }

$OldModules = @(
    "pmh_openxr_layer",
    "pmh_bt_peripheral",
    "pmh_btperipheral",
    "pmh_xrstack",
    "pmh_inputd",
    "pico_xr_compat",
    "pico_xr_loader",
    "iobt-fix"
)

Write-Host "== push $(Split-Path $zipPath -Leaf)"
& $adb -s $Serial push $zipPath /data/local/tmp/pmhb-magisk.zip | Out-Null

Write-Host "== install module"
& $adb -s $Serial shell "su -c 'magisk --install-module /data/local/tmp/pmhb-magisk.zip'" | Select-Object -Last 3

Write-Host "== verify extraction (falls back to a manual unzip)"
$check = & $adb -s $Serial shell "su -c 'test -d /data/adb/modules/pmhb/system && echo ok || echo short'"
if ($check -notmatch "ok") {
    Write-Host "   token tree missing, unzipping manually"
    & $adb -s $Serial shell "su -c 'cd /data/adb/modules/pmhb && unzip -o -q /data/local/tmp/pmhb-magisk.zip && touch update'" | Out-Null
}

Write-Host "== fix script modes"
& $adb -s $Serial shell "su -c 'chmod 755 /data/adb/modules/pmhb/service.d/*.sh /data/adb/modules/pmhb/post-fs-data.d/*.sh /data/adb/modules/pmhb/*.sh /data/adb/modules/pmhb/bin/* 2>/dev/null; echo chmod done'" | Select-Object -Last 1

Write-Host "== disable old split modules (kept for rollback)"
$disable = ($OldModules | ForEach-Object { "touch /data/adb/modules/$_/disable 2>/dev/null" }) -join "; "
& $adb -s $Serial shell "su -c '$disable; echo disabled'" | Select-Object -Last 1

Write-Host "== merged module contents"
& $adb -s $Serial shell "su -c 'ls /data/adb/modules/pmhb; echo ---; ls -l /data/adb/modules/pmhb/service.d'" | Select-Object -First 14

if (-not $SkipReboot) {
    Write-Host "== reboot to apply"
    & $adb -s $Serial reboot
}
