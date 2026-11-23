#!/system/bin/sh
# Expose Quest controller battery levels to normal apps.
#
# Horizon OS keeps one small file per controller under
# /data/vendor/misc/sensors/batteryinfo/, named by the controller id and holding
# the battery percentage as text, e.g.
#
#   /data/vendor/misc/sensors/batteryinfo/9e47f23a48644982  ->  "90"
#
# The directory is root/system only, so this Magisk service mirrors it into
# /data/local/tmp/pmh_battery.json (mode 644) every few seconds for apps and
# PMHBridge hooks to read.

SRC=/data/vendor/misc/sensors/batteryinfo
DIR=/data/local/tmp
JSON="$DIR/pmh_battery.json"
PIDFILE="$DIR/pmh_battery.pid"

# Magisk runs service.d scripts in one shell and waits for them, so a script
# that loops forever would block the next one. Re-exec ourselves detached and
# let the foreground copy return immediately. The pidfile keeps repeated boots
# or manual starts from stacking up duplicate mirrors.
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; then
    exit 0
fi
if [ -z "$PMH_BATTERY_DAEMON" ]; then
    PMH_BATTERY_DAEMON=1 nohup "$0" "$@" >/dev/null 2>&1 &
    exit 0
fi
echo $$ > "$PIDFILE"

[ -f "$JSON" ] || { echo '{}' > "$JSON"; chmod 644 "$JSON"; }

while true; do
    changed=0
    for f in "$SRC"/*; do
        [ -f "$f" ] || continue
        id=${f##*/}
        lvl=$(cat "$f" 2>/dev/null | tr -dc '0-9')
        [ -n "$lvl" ] || continue
        old=$(cat "$DIR/pmh_battery_$id" 2>/dev/null)
        if [ "$old" != "$lvl" ]; then
            echo "$lvl" > "$DIR/pmh_battery_$id"
            chmod 644 "$DIR/pmh_battery_$id"
            changed=1
        fi
    done

    if [ $changed -eq 1 ] || [ ! -s "$JSON" ]; then
        tmp="$JSON.$$"
        printf '{' > "$tmp"
        first=1
        for f in "$DIR"/pmh_battery_*; do
            [ -f "$f" ] || continue
            case "$f" in *.json|*.log) continue ;; esac
            id=${f##*/pmh_battery_}
            lvl=$(cat "$f" 2>/dev/null)
            [ -n "$lvl" ] || continue
            [ $first -eq 1 ] || printf ', ' >> "$tmp"
            printf '"%s": %s' "$id" "$lvl" >> "$tmp"
            first=0
        done
        printf '}\n' >> "$tmp"
        mv "$tmp" "$JSON"
        chmod 644 "$JSON"
    fi

    sleep 5
done
