#!/system/bin/sh
# Run the read-only probe after boot. No service is started or replaced.
MODDIR=/data/adb/modules/pmhb
OUT=/data/local/tmp/pico_xr_compat
mkdir -p "$OUT"
chmod 0770 "$OUT"

# This payload is only meaningful on Quest 3 (eureka). Do not run on other
# devices that happen to have Magisk installed.
[ "$(getprop ro.product.device)" = "eureka" ] || exit 0

i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 120 ]; do
  sleep 5
  i=$((i + 1))
done
sleep 5

ABI=$(getprop ro.product.cpu.abi)
case "$ABI" in
  arm64*) SRC="$MODDIR/bin/pico_xr_probe64"; PROBE="$OUT/pico_xr_probe64" ;;
  *)      SRC="$MODDIR/bin/pico_xr_probe32"; PROBE="$OUT/pico_xr_probe32" ;;
esac

# Magisk may install files as 0644. Copy to a writable executable location
# before running, so the probe does not depend on the module mount's mode.
cp "$SRC" "$PROBE"
chmod 0755 "$PROBE"
"$PROBE" > "$OUT/probe.log" 2>&1
chmod 0660 "$OUT/probe.log" "$OUT/probe.txt" 2>/dev/null
if [ -f "$OUT/probe.txt" ]; then
  cp "$OUT/probe.txt" /sdcard/Download/pico_xr_probe.txt 2>/dev/null
  chmod 0666 /sdcard/Download/pico_xr_probe.txt 2>/dev/null
fi
command -v log >/dev/null 2>&1 && log -t PicoXrCompat "probe complete: $OUT/probe.txt"
