#!/system/bin/sh
# pico-xr-compat: status / probe / two-lane routing inspection.
#
# Two lanes, and this script can only *read* both:
#   Quest lane : /odm/etc/openxr/1/active_runtime.*.json   (Horizon OS, untouched)
#   PICO lane  : /system/etc/openxr/pico/active_runtime.*.json (added by this module)
#
# Nothing here writes to /odm, /system/etc/openxr/1, /vendor or any other
# system file. The PICO lane is selected per application process by the
# LSPosed helper (XR_RUNTIME_JSON), never globally.
set -u

MODDIR=${0%/*}
MODDIR=${MODDIR%/tools}
OUT=/data/local/tmp/pico_xr_compat
RUNTIME_DIR="$MODDIR/runtime"
PICO_MANIFEST_64=/system/etc/openxr/pico/active_runtime.aarch64.json
PICO_MANIFEST_32=/system/etc/openxr/pico/active_runtime.armv7a-vfp.json
QUEST_MANIFEST=/odm/etc/openxr/1/active_runtime.aarch64.json
mkdir -p "$OUT"

usage() {
  cat <<'EOF'
pico-xr-compat <command>

  status            device + both lanes + bridge/backend state (read-only)
  lanes             show which app is routed where, and the manifests
  probe             run the read-only native probe now
  report            print /data/local/tmp/pico_xr_compat/probe.txt
  set-backend PATH  set persist.pico.xr.compat.backend for the facade
  help              this text

There is deliberately no "make PICO the system runtime" command: the PICO lane
is per-app (LSPosed scope) and Horizon OS's own runtime is never replaced.
EOF
}

need_root() {
  [ "$(id -u)" = "0" ] || { echo "root required" >&2; exit 1; }
}

show_manifest() {
  f=$1
  if [ -f "$f" ]; then
    echo "  $f"
    sed 's/^/    /' "$f"
  else
    echo "  $f  (absent)"
  fi
}

cmd_status() {
  echo "device      : $(getprop ro.product.device) / $(getprop ro.product.model)"
  echo "build       : $(getprop ro.build.version.release) sdk=$(getprop ro.build.version.sdk)"
  echo "fingerprint : $(getprop ro.build.fingerprint)"
  echo "selinux     : $(getenforce 2>/dev/null)"
  echo
  echo "module      : $MODDIR"
  ls -l "$MODDIR/system/lib64/libpico_meta_bridge.so" \
        "$MODDIR/system/lib/libpico_meta_bridge.so" 2>/dev/null
  echo
  echo "Quest lane (never modified by this module):"
  show_manifest "$QUEST_MANIFEST"
  echo
  echo "PICO lane (added by this module, per-app only):"
  show_manifest "$PICO_MANIFEST_64"
  show_manifest "$PICO_MANIFEST_32"
  echo
  echo "backend property: $(getprop persist.pico.xr.compat.backend)"
  echo "default backend : /system/lib64/libmeta_xrloader.so"
  if [ -e /system/lib64/libmeta_xrloader.so ]; then
    echo "  backend present"
  else
    echo "  backend MISSING (facade not runnable yet)"
  fi
  echo
  echo "last probe      : $OUT/probe.txt"
}

cmd_lanes() {
  echo "== Quest lane =="
  echo "selected by: Horizon OS for every app that is not routed"
  show_manifest "$QUEST_MANIFEST"
  echo
  echo "== PICO lane =="
  echo "selected by: LSPosed scope -> libpico_xr_inject.so sets XR_RUNTIME_JSON"
  echo "only in that process; the app must already ship PxrApi or use OpenXR"
  show_manifest "$PICO_MANIFEST_64"
  echo
  echo "== router state =="
  if [ -f /system/lib64/libpico_xr_inject.so ]; then
    echo "  router lib present: /system/lib64/libpico_xr_inject.so"
  else
    echo "  router lib MISSING (module not mounted?)"
  fi
  echo "  LSPosed scope decides the apps; check the LSPosed log for:"
  echo "    PicoXrInject: route check pkg=... pkgMatch=... pxrApi=..."
  echo "    PicoXrLsp   : route result=1 (PICO lane)"
}

cmd_probe() {
  need_root
  case "$(getprop ro.product.cpu.abi)" in
    arm64*) SRC="$MODDIR/bin/pico_xr_probe64"; P="$OUT/pico_xr_probe64" ;;
    *)      SRC="$MODDIR/bin/pico_xr_probe32"; P="$OUT/pico_xr_probe32" ;;
  esac
  # Magisk may install the payload as 0644; run a chmod'ed copy.
  cp "$SRC" "$P"
  chmod 0755 "$P"
  "$P"
  echo "probe written to $OUT/probe.txt"
}

cmd_report() {
  if [ -f "$OUT/probe.txt" ]; then
    cat "$OUT/probe.txt"
  else
    echo "no probe report"
  fi
}

cmd_set_backend() {
  need_root
  [ $# -ge 1 ] || { echo "usage: set-backend /system/lib64/..." >&2; exit 2; }
  if command -v resetprop >/dev/null 2>&1; then
    resetprop -n persist.pico.xr.compat.backend "$1"
    echo "set persist.pico.xr.compat.backend=$1"
  else
    echo "resetprop not found; write the property with your Magisk build" >&2
    exit 1
  fi
}

[ $# -ge 1 ] || { usage; exit 1; }
cmd=$1
shift
case "$cmd" in
  status) cmd_status ;;
  lanes) cmd_lanes ;;
  probe) cmd_probe ;;
  report) cmd_report ;;
  set-backend) cmd_set_backend "$@" ;;
  help|-h|--help) usage ;;
  *) usage; exit 2 ;;
esac
