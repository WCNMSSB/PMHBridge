# Extract a PICO OS firmware (OTA zip or payload.bin) into partition images and
# the few framework pieces PMHBridge needs.
#
# Usage:
#   powershell -File pipeline/extract_firmware.ps1 -Source <ota.zip|payload.bin> -Out images/p4u-5.15.4
#
# Output layout:
#   <Out>/system.img ...            partition images
#   <Out>/framework/framework.jar   PICO's patched AOSP framework (dexes inside)
#   <Out>/framework/pico-framework-extension-api.jar
#   <Out>/lib64/librms*.so          PICO resource-manager natives
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Out,
    [string[]]$Partitions = @("system", "system_ext", "product", "vendor", "odm", "boot", "vendor_boot")
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$Tools = Join-Path $Root "tools"
$Erofs = Join-Path $Tools "erofs\bin\fs.erofs.exe"

New-Item -ItemType Directory -Force -Path $Out, (Join-Path $Out "framework"), (Join-Path $Out "lib64") | Out-Null

$payload = $Source
if ($Source -like "*.zip") {
    $payload = Join-Path $Out "payload.bin"
    if (-not (Test-Path $payload)) {
        Write-Host "== extracting payload.bin from $Source"
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Source))
        $entry = $zip.Entries | Where-Object { $_.FullName -eq "payload.bin" } | Select-Object -First 1
        if (-not $entry) { throw "payload.bin not found in $Source" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $payload, $true)
        $zip.Dispose()
    }
}

Write-Host "== extracting partitions into $Out"
& python (Join-Path $Tools "payload_extract.py") --no-verify $payload $Out @Partitions
if ($LASTEXITCODE -ne 0) { throw "payload_extract failed" }

$systemImg = Join-Path $Out "system.img"
if (Test-Path $systemImg) {
    Write-Host "== exporting framework pieces"
    $wanted = @(
        "/system/framework/framework.jar",
        "/system/framework/pico-framework-extension-api.jar"
    )
    foreach ($path in $wanted) {
        $dest = Join-Path (Join-Path $Out "framework") (Split-Path $path -Leaf)
        & $Erofs $systemImg export $path $dest 2>$null | Out-Null
        if (Test-Path $dest) { Write-Host "   $path -> $dest" }
    }
    foreach ($lib in @("librms_jni.so", "librmsclient.so", "librmscore.so")) {
        $dest = Join-Path (Join-Path $Out "lib64") $lib
        & $Erofs $systemImg export "/system/lib64/$lib" $dest 2>$null | Out-Null
        if (Test-Path $dest) { Write-Host "   /system/lib64/$lib" }
    }
}

Write-Host "== done: $Out"
