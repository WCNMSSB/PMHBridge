# Build the minimal OpenXR layer test APK (verifies PMHXRLayer gets loaded).
param(
    [string]$Sdk = "K:\PICOXRRUNTIME2HORIZONOS\tools\android-sdk",
    [string]$Ndk = "K:\PICOXRRUNTIME2HORIZONOS\tools\ndk\r27c\android-ndk-r27c"
)
$ErrorActionPreference = "Stop"
$App = $PSScriptRoot
$Build = Join-Path $App "build"
$Classes = Join-Path $App "classes"
$Dex = Join-Path $App "dex"
$Lib = Join-Path $App "lib\arm64-v8a"
$Out = Join-Path $Build "pmh-xrlayertest.apk"
New-Item -ItemType Directory -Force $Build, $Classes, $Dex, $Lib | Out-Null

$AndroidJar = Join-Path $Sdk "platforms\android-34\android.jar"
$aapt2 = Join-Path $Sdk "build-tools\34.0.0\aapt2.exe"
$d8 = Join-Path $Sdk "build-tools\34.0.0\d8.bat"
$zipalign = Join-Path $Sdk "build-tools\34.0.0\zipalign.exe"
$apksigner = Join-Path $Sdk "build-tools\34.0.0\apksigner.bat"
$clang = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"
$keystore = "K:\PICOXRRUNTIME2HORIZONOS\testapp\debug.keystore"

& $clang --target=aarch64-linux-android29 -O2 -shared -fPIC `
    -I (Join-Path $Sdk "platforms\android-34\include") `
    -o (Join-Path $Lib "libxrlayertest.so") (Join-Path $App "jni\xr_test.c") -llog -ldl
if ($LASTEXITCODE -ne 0) { throw "native build failed" }

& javac -source 8 -target 8 -classpath $AndroidJar -d $Classes `
    (Join-Path $App "src\com\pmh\xrlayertest\MainActivity.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $d8 --lib $AndroidJar --output $Dex (Join-Path $Classes "com\pmh\xrlayertest\MainActivity.class")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$base = Join-Path $Build "base.apk"
if (Test-Path $base) { Remove-Item $base -Force }
& $aapt2 link -o $base --manifest (Join-Path $App "AndroidManifest.xml") `
    -I $AndroidJar --min-sdk-version 29 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',compression=zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.write(sys.argv[3],'lib/arm64-v8a/libxrlayertest.so'); z.close()" `
    $base (Join-Path $Dex "classes.dex") (Join-Path $Lib "libxrlayertest.so")
if ($LASTEXITCODE -ne 0) { throw "zip add failed" }

$aligned = Join-Path $Build "aligned.apk"
if (Test-Path $aligned) { Remove-Item $aligned -Force }
& $zipalign -f 4 $base $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "built: $Out"
