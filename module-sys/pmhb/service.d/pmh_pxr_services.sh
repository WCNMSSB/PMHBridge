#!/system/bin/sh
# Publish the PICO binder service names that PICO apps probe for.
#
# Off by default: the stubs answer with success but carry no data, and PICO
# Connect's session handshake gives up on the empty replies (the PC transport
# then resets every few seconds). Enable by creating the flag file, only when a
# real implementation or a targeted experiment needs the names to exist.

FLAG=/data/adb/pmh_pxr_services.enable
[ -f "$FLAG" ] || exit 0

DEX=/system/etc/pmh_pxr_services.dex
[ -f "$DEX" ] || exit 0

# do not start twice
if pgrep -f PublishProbe >/dev/null 2>&1; then
    exit 0
fi

CLASSPATH=$DEX app_process /system/bin PublishProbe &
