# Restore every PMHBridge system-side piece on the Quest.
#
# Why this exists: Horizon OS wipes Magisk modules that were mid-install when the
# system_server crash-looped, and the root itself (Singularity) is not always
# granted at boot. This script waits for root, re-installs both modules and
# verifies the result, so a bad boot can be recovered with one command.
param(
    [string]$Serial = "2G0YC5ZG4L008S"
)
$ErrorActionPreference = "Continue"
$adb = "C:\Users\77456\Desktop\platform-tools-r36.0.2-windows\adb.exe"
$Here = (Resolve-Path $PSScriptRoot).Path
$Root = (Resolve-Path (Join-Path $Here "..")).Path

function Assert-Root([int]$Attempts = 6) {
    for ($i = 1; $i -le $Attempts; $i++) {
        $id = & $adb -s $Serial shell "su -c id" 2>&1
        if ($id -match "uid=0") {
            Write-Host "root ready (attempt $i)"
            return $true
        }
        Write-Host "waiting for root, attempt $i"
        Start-Sleep -Seconds 20
    }
    return $false
}

if (-not (Assert-Root)) {
    Write-Host "No root: open Singularity in the headset and press the start button, then rerun."
    exit 1
}

foreach ($zip in @("pmh-openxr-layer.zip", "pmh-btperipheral.zip")) {
    $path = Join-Path (Split-Path (Split-Path $Root -Parent) -Parent) "artifacts\pmhbridge\dist\$zip"
    if (-not (Test-Path $path)) {
        Write-Host "missing $path"
        continue
    }
    Write-Host "== installing $zip"
    & $adb -s $Serial push $path "/data/local/tmp/$zip" 2>&1 | Out-Null
    & $adb -s $Serial shell "su -c 'magisk --install-module /data/local/tmp/$zip'" 2>&1 | Select-Object -Last 2
}

Write-Host "== rebooting so the modules mount"
& $adb -s $Serial reboot 2>&1 | Out-Null
Start-Sleep -Seconds 170
& $adb connect "192.168.0.106:5555" 2>&1 | Out-Null

Write-Host "== verifying"
& $adb -s $Serial shell "ls -l /system/lib64/libpmh_openxr_layer.so /system/etc/openxr/1/api_layers/implicit.d/pmh_tracker.json 2>&1"
& $adb -s $Serial shell "pm path com.pvr.btperipheral"
& $adb -s $Serial shell "service list | head -1"
