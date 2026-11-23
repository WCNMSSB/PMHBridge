# Install the PMHBridge LSPosed module the supported way.
#
# Rule (docs/WORKING-RULES.md): never restart lspd. LSPosed injects at process
# fork, so installing the APK and restarting the scoped apps is enough; killing
# lspd causes uid-cache loss, missed injection and system-server injection
# failures.
#
# What this does:
#   1. adb install -r the module APK
#   2. rewrite modules.apk_path in modules_config.db (adb install changes the
#      /data/app path, and LSPosed silently skips modules with a stale path)
#   3. force-stop the scoped apps so their next start picks the new code up
param(
    [string]$Apk = "artifacts\pmhbridge\dist\pmh-bridge-module.apk",
    [string]$Pkg = "com.pmh.bridge.lsp",
    [string[]]$Scoped = @(
        "com.oculus.vrshell",
        "com.picovr.picostreamassistant",
        "com.picovr.vrusercenter",
        "com.picovr.store",
        "com.pvr.swift"
    )
)
$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = "C:\Users\77456\Desktop\platform-tools-r36.0.2-windows\adb.exe"
$pyExe = "C:\Users\77456\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if (-not (Test-Path $pyExe)) { $pyExe = "python" }

$apkPath = if ([IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path (Split-Path (Split-Path $Root -Parent) -Parent) $Apk }
Write-Host "== install $apkPath"
& $adb install -r $apkPath 2>&1 | Select-Object -Last 1

$cur = ((& $adb shell "pm path $Pkg") -replace 'package:', '').Trim()
Write-Host "== installed at $cur"
if (-not $cur) { throw "package not installed" }

$tmp = Join-Path (Split-Path (Split-Path $Root -Parent) -Parent) "artifacts\pmhbridge\build\module-install"
New-Item -ItemType Directory -Force $tmp | Out-Null
Get-ChildItem $tmp -Filter "*.db*" -ErrorAction SilentlyContinue | Remove-Item -Force

& $adb shell su -c "dd if=/data/adb/lspd/config/modules_config.db of=/sdcard/pmh_mod.db bs=64k 2>/dev/null; chmod 666 /sdcard/pmh_mod.db" | Out-Null
& $adb pull /sdcard/pmh_mod.db "$tmp\mod.db" 2>&1 | Out-Null
& $adb pull /sdcard/pmh_mod.db-wal "$tmp\mod.db-wal" 2>&1 | Out-Null

$patch = Join-Path $tmp "patch_path.py"
@"
import os, sqlite3, sys
db, pkg, apk = sys.argv[1], sys.argv[2], sys.argv[3]
con = sqlite3.connect(db)
cur = con.cursor()
cur.execute("update modules set apk_path=? where module_pkg_name=?", (apk, pkg))
if cur.rowcount == 0:
    cur.execute("insert into modules(module_pkg_name, apk_path) values(?,?)", (pkg, apk))
con.commit()
cur.execute("pragma wal_checkpoint(TRUNCATE)")
con.close()
out = db + ".final"
if os.path.exists(out):
    os.remove(out)
con = sqlite3.connect(db)
con.execute("vacuum into '%s'" % out)
con.close()
print("module path updated")
"@ | Set-Content -Encoding UTF8 $patch
& $pyExe $patch "$tmp\mod.db" $Pkg $cur
if (-not (Test-Path "$tmp\mod.db.final")) { throw "db merge failed" }

& $adb push "$tmp\mod.db.final" /data/local/tmp/pmh_mod.db 2>&1 | Out-Null
& $adb shell su -c "cp /data/local/tmp/pmh_mod.db /data/adb/lspd/config/modules_config.db && echo copied"
& $adb shell su -c "rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm; echo cleaned"

Write-Host "== restarting scoped apps (no lspd restart)"
foreach ($p in $Scoped) {
    & $adb shell "am force-stop $p" | Out-Null
    Write-Host "   stopped $p"
}
Write-Host "== done"
