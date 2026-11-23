# Iteratively bundle every native library the PICO XR runtime asks for.
#
# Each round:
#   1. push the current APK, install it, start the runtime service
#   2. read logcat for `dlopen failed: library "X" not found`
#   3. find X in the 5.15.4 partition images, extract it
#   4. repackage + resign + install again
#
# Stops when no dlopen failures are left, or when a library cannot be found in
# any firmware image.
param(
    [int]$MaxRounds = 6,
    [string]$StartApk = "dist\b9-signed.apk"
)
$ErrorActionPreference = "Continue"
$Root = "K:\PICOXRRUNTIME2HORIZONOS"
$Cg = Join-Path $Root "PMHB\PMHBridge"
$Dist = Join-Path $Root "artifacts\pmhbridge\dist"
$Work = Join-Path $Root "build\pxrdeps"
$Er = Join-Path $Root "tools\erofs\bin\fs.erofs.exe"
$Bt = Join-Path $Root "tools\android-sdk\build-tools\35.0.0"
$Ks = Join-Path $Root "testapp\debug.keystore"
$Adb = "C:\Users\77456\Desktop\platform-tools-r36.0.2-windows\adb.exe"
$Py = "C:\Users\77456\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
$Images = @("system", "system_ext", "product", "vendor")
$LibDirs = @("/system/lib64", "/lib64", "/vendor/lib64", "/app/lib64", "/system_ext/lib64", "/product/lib64")

New-Item -ItemType Directory -Force $Work | Out-Null

function Find-Lib([string]$name) {
    foreach ($img in $Images) {
        $p = Join-Path $Root "images\p4u-5.15.4\$img.img"
        if (-not (Test-Path $p)) { continue }
        foreach ($d in $LibDirs) {
            $dest = Join-Path $Work $name
            if (Test-Path $dest) { return $dest }
            & $Er $p export "$d/$name" $dest 2>$null | Out-Null
            if (Test-Path $dest) { return $dest }
        }
    }
    return $null
}

$apk = Join-Path $Cg $StartApk
for ($round = 1; $round -le $MaxRounds; ++$round) {
    Write-Host "== round $round : $apk"
    & $Adb shell "am force-stop com.pico.xr.openxr_runtime" | Out-Null
    & $Adb install -r $apk 2>&1 | Select-Object -Last 1
    & $Adb logcat -c | Out-Null
    & $Adb shell "am start-foreground-service -n com.pico.xr.openxr_runtime/com.pico.xr.openxr_runtime.PicoOpenXRRuntimeService" | Out-Null
    Start-Sleep -Seconds 8

    $log = (& $Adb logcat -d 2>&1) -join "`n"
    $missing = [regex]::Matches($log, 'dlopen failed: library "([^"]+)" not found') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    if (-not $missing) {
        Write-Host "== no missing libraries left"
        break
    }
    Write-Host "== missing: $($missing -join ', ')"

    $added = @()
    foreach ($name in $missing) {
        $found = Find-Lib $name
        if ($found) { $added += $found; Write-Host "   packaged $name" }
        else { Write-Host "   NOT FOUND in firmware: $name" }
    }
    if ($added.Count -eq 0) { break }

    $next = Join-Path $Dist ("runtime-deps-r$round.apk")
    @"
import zipfile, os, sys
src, out = sys.argv[1], sys.argv[2]
libs = [l for l in sys.argv[3:] if os.path.exists(l)]
z = zipfile.ZipFile(src)
existing = set(z.namelist())
added = 0
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
    for info in z.infolist():
        if info.is_dir():
            continue
        dst.writestr(info, z.read(info.filename))
    for lib in libs:
        base = os.path.basename(lib)
        # Android only extracts entries whose file name starts with "lib"; VNDK
        # libs like android.hardware.*-ndk.so are shipped renamed so the shim can
        # preload them by their real soname.
        if not base.startswith("lib"):
            stem = base[:-3] if base.endswith(".so") else base
            stem = "".join(c if c.isalnum() else "_" for c in stem)
            base = "libpmhvndk_" + stem + ".so"
        name = 'lib/arm64-v8a/' + base
        if name in existing:
            continue
        dst.writestr(name, open(lib, 'rb').read())
        added += 1
z.close()
print('packaged', added, 'libs ->', os.path.getsize(out))
"@ | Set-Content -Encoding UTF8 (Join-Path $Work "addlibs.py")
    & $Py (Join-Path $Work "addlibs.py") $apk $next @added

    $aligned = Join-Path $Work "aligned.apk"
    & (Join-Path $Bt "zipalign.exe") -f 4 $next $aligned
    $signed = Join-Path $Dist ("runtime-deps-r$round-signed.apk")
    & (Join-Path $Bt "apksigner.bat") sign --ks $Ks --ks-pass pass:android --key-pass pass:android `
        --ks-key-alias androiddebugkey --out $signed $aligned 2>&1 | Select-Object -Last 1
    $apk = $signed
}

Write-Host "== final apk: $apk"
