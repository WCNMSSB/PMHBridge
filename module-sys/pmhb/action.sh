#!/system/bin/sh
# Magisk module Action: run the probe and publish a world-readable report.
# This is the no-adb path: Magisk app -> module card -> Action.
MODDIR=${0%/*}
OUT=/data/local/tmp/pico_xr_compat
DEST=/sdcard/Download/pico_xr_probe.txt

mkdir -p "$OUT"
chmod 0770 "$OUT"

if [ "$(getprop ro.product.device)" != "eureka" ]; then
  echo "not a Quest 3 (eureka); refusing"
  exit 1
fi

case "$(getprop ro.product.cpu.abi)" in
  arm64*) SRC="$MODDIR/bin/pico_xr_probe64"; PROBE="$OUT/pico_xr_probe64" ;;
  *)      SRC="$MODDIR/bin/pico_xr_probe32"; PROBE="$OUT/pico_xr_probe32" ;;
esac

# Magisk may install the payload as 0644; run a chmod'ed copy.
cp "$SRC" "$PROBE" || { echo "copy failed: $SRC"; exit 1; }
chmod 0755 "$PROBE" || { echo "chmod failed"; exit 1; }
"$PROBE" > "$OUT/probe.log" 2>&1
RC=$?

if [ -f "$OUT/probe.txt" ]; then
  cp "$OUT/probe.txt" "$DEST"
  chmod 0666 "$DEST"
  echo "probe report: $DEST"
else
  echo "probe did not produce $OUT/probe.txt (rc=$RC)"
  cat "$OUT/probe.log" 2>/dev/null
fi
exit "$RC"
