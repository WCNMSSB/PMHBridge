# Build the PMHBridge LSPosed module (javac -> d8 -> aapt2 -> sign).
#
# Keeps the same lightweight chain the old project used so it works without Gradle.
param(
    [string]$Out = "dist\pmh-bridge-module.apk"
)
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
# Build outputs (and the shipped probe assets) live outside the source tree.
$Repo = (Resolve-Path (Join-Path $Root "..\..")).Path
$Art = Join-Path $Repo "artifacts\pmhbridge"
$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "K:\PICOXRRUNTIME2HORIZONOS\tools\android-sdk" }
$Bt = Join-Path $Sdk "build-tools\35.0.0"
if (-not (Test-Path (Join-Path $Bt "d8.bat"))) { $Bt = Join-Path $Sdk "build-tools\34.0.0" }
$Platform = Join-Path $Sdk "platforms\android-34\android.jar"
$Xposed = if ($env:XPosed_API) { $env:XPosed_API } else { "K:\PICOXRRUNTIME2HORIZONOS\tools\xposed\api-82.jar" }
$Ks = "K:\PICOXRRUNTIME2HORIZONOS\testapp\debug.keystore"
$Src = Join-Path $Root "module\src"
$Res = Join-Path $Root "module\res"
$Assets = Join-Path $Art "module\assets"
$Manifest = Join-Path $Root "module\AndroidManifest.xml"
$Build = Join-Path $Art "build\module"
$Cls = Join-Path $Build "classes"
$Dex = Join-Path $Build "dex"

foreach ($p in @($Platform, $Xposed, $Ks, $Manifest)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}
Remove-Item -Recurse -Force $Build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Cls, $Dex | Out-Null

# Native helper: OpenXR loader probe used inside the host app.
$Ndk = if ($env:ANDROID_NDK_ROOT) { $env:ANDROID_NDK_ROOT } else { "K:\PICOXRRUNTIME2HORIZONOS\tools\ndk\r27c\android-ndk-r27c" }
$Clang = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"
$LibDir = Join-Path $Build "lib\arm64-v8a"
New-Item -ItemType Directory -Force -Path $LibDir | Out-Null
& $Clang --target=aarch64-linux-android29 -O2 -shared -fPIC `
    -I (Join-Path $Sdk "platforms\android-34\include") `
    -o (Join-Path $LibDir "libxrprobe.so") (Join-Path $Root "module\jni\xrprobe.c") -llog -ldl
if ($LASTEXITCODE -ne 0) { throw "native build failed" }

# Ship the probe as an asset as well: the host app extracts it into its own
# files dir, which avoids every root/SELinux issue with pushing into app data.
$AssetDir = Join-Path $Art "module\assets"
New-Item -ItemType Directory -Force -Path $AssetDir | Out-Null
Copy-Item (Join-Path $LibDir "libxrprobe.so") (Join-Path $AssetDir "libxrprobe_v5.so") -Force

$javaFiles = Get-ChildItem $Src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 8 -nowarn -encoding UTF-8 -cp "$Platform;$Xposed" -d $Cls $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# d8 chokes on long argument lists, so pack the classes into one jar first.
$jar = Join-Path $Build "classes.jar"
if (Test-Path $jar) { Remove-Item $jar -Force }
& jar cf $jar -C $Cls .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& (Join-Path $Bt "d8.bat") --lib $Platform --classpath $Xposed --min-api 29 --output $Dex $jar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$resZip = Join-Path $Build "res.zip"
& (Join-Path $Bt "aapt2.exe") compile --dir $Res -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

$unsigned = Join-Path $Build "unsigned.apk"
& (Join-Path $Bt "aapt2.exe") link -o $unsigned -I $Platform --manifest $Manifest `
    -A $Assets --auto-add-overlay $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $Dex "classes.dex"), "classes.dex") | Out-Null
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $LibDir "libxrprobe.so"), "lib/arm64-v8a/libxrprobe.so", [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
$zip.Dispose()

$aligned = Join-Path $Build "aligned.apk"
& (Join-Path $Bt "zipalign.exe") -f -p 4 $unsigned $aligned
$distFull = if ([IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $Art $Out }
New-Item -ItemType Directory -Force -Path (Split-Path $distFull) | Out-Null
& (Join-Path $Bt "apksigner.bat") sign --ks $Ks --ks-pass pass:android `
    --key-pass pass:android --ks-key-alias androiddebugkey --out $distFull $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "built $distFull ($((Get-Item $distFull).Length) bytes)"
