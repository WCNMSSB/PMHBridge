# Build the PMHBridge helper app.
#
# Plain javac + aapt2 + d8 chain (no Gradle): the UI currently uses framework
# views, so nothing external is required. When MIUIX is available the sources move
# to a Gradle/Compose module and this script is retired.
param(
    [string]$Out = "dist\pmh-bridge-helper.apk"
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$App = Join-Path $Root "helper\app"
$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "K:\PICOXRRUNTIME2HORIZONOS\tools\android-sdk" }
$Bt = Join-Path $Sdk "build-tools\35.0.0"
if (-not (Test-Path (Join-Path $Bt "d8.bat"))) { $Bt = Join-Path $Sdk "build-tools\34.0.0" }
$Platform = Join-Path $Sdk "platforms\android-34\android.jar"
$Ks = "K:\PICOXRRUNTIME2HORIZONOS\testapp\debug.keystore"
$Build = Join-Path $Root "build\helper"
$Cls = Join-Path $Build "classes"
$Dex = Join-Path $Build "dex"

foreach ($p in @($Platform, $Ks, (Join-Path $App "AndroidManifest.xml"))) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}
Remove-Item -Recurse -Force $Build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Cls, $Dex | Out-Null

$javaFiles = Get-ChildItem (Join-Path $App "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 8 -nowarn -g:none -encoding UTF-8 -cp $Platform -d $Cls $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classes = Get-ChildItem $Cls -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $Bt "d8.bat") --lib $Platform --min-api 29 --output $Dex $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$unsigned = Join-Path $Build "unsigned.apk"
& (Join-Path $Bt "aapt2.exe") link -o $unsigned -I $Platform `
    --manifest (Join-Path $App "AndroidManifest.xml")
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $Dex "classes.dex"), "classes.dex") | Out-Null
$zip.Dispose()

$aligned = Join-Path $Build "aligned.apk"
& (Join-Path $Bt "zipalign.exe") -f 4 $unsigned $aligned
$distFull = Join-Path $Root $Out
New-Item -ItemType Directory -Force -Path (Split-Path $distFull) | Out-Null
& (Join-Path $Bt "apksigner.bat") sign --ks $Ks --ks-pass pass:android `
    --key-pass pass:android --ks-key-alias androiddebugkey --out $distFull $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "built $distFull ($((Get-Item $distFull).Length) bytes)"
