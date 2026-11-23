#!/system/bin/sh
# Read-only boot inventory. This script intentionally makes no system changes.
MODDIR=/data/adb/modules/pmhb
OUT=/data/local/tmp/pico_xr_compat
mkdir -p "$OUT"
chmod 0770 "$OUT"

{
  echo "=== pico_xr_compat post-fs-data $(date) ==="
  echo "module=$MODDIR"
  for p in ro.product.device ro.product.vendor.device ro.product.model \
           ro.build.version.release ro.build.version.sdk \
           ro.build.fingerprint ro.board.platform ro.soc.model \
           ro.zygote ro.product.cpu.abilist; do
    echo "$p=$(getprop "$p")"
  done
  echo "--- PICO identity from system.prop ---"
  for p in ro.build.display.id ro.pico.tag ro.pico.ota.version \
           ro.pvr.internal.version persist.sys.pico.runtimesupercore \
           persist.pvr.mrc_enable; do
    echo "$p=$(getprop "$p")"
  done
  echo "--- device identity (expected to stay Horizon OS; spoofed per app) ---"
  for p in ro.product.brand ro.product.manufacturer ro.product.name; do
    echo "$p=$(getprop "$p")"
  done
  echo "selinux=$(getenforce 2>/dev/null)"
  echo "=== end ==="
} > "$OUT/boot.txt" 2>&1
chmod 0660 "$OUT/boot.txt"
