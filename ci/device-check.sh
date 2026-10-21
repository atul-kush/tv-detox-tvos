#!/bin/sh
# Installs this build on a real TV the way a user would and checks the tvOS-style home screen.
# Unlike smoke-test.sh it never runs undo.sh, so it's safe on the TV you watch.
#   sh ci/device-check.sh <TV_IP or adb serial> ["app1,app2,..."]   (default: the apps in apps.txt)
set -e
cd "$(dirname "$0")/.."
TARGET="$1"
APPS="${2:-$(grep -v '^[[:space:]]*#' apps.txt | tr -d ' \t\r' | grep -v '^$' | paste -sd, -)}"
[ -n "$TARGET" ] || { echo "Usage: sh ci/device-check.sh <TV_IP> [\"app1,app2,...\"]" >&2; exit 1; }
case "$TARGET" in *.*.*.*:*) TV=$TARGET ;; *.*.*.*) TV=$TARGET:5555 ;; *) TV=$TARGET ;; esac

fail() { echo "FAIL: $*" >&2; exit 1; }
tv() { adb -s "$TV" shell "$@" </dev/null | tr -d '\r'; }
key() { tv input keyevent "$1"; sleep 1; }
ui() { tv "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml"; }
wait_home() { for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do tv dumpsys window | grep -m1 mCurrentFocus | grep -q com.ansub.tvhome && return 0; sleep 1; done; return 1; }

echo "== install"
YES=1 sh install.sh "$TARGET" "$APPS"
tv pm grant com.ansub.tvhome android.permission.ACCESS_FINE_LOCATION || echo "   (location not granted: Wi-Fi shows \"Connected\" instead of its name)"
home=$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)
[ "$home" = "com.ansub.tvhome/.MainActivity" ] || fail "default home is $home"

echo "== home screen with the time in the pill"
key KEYCODE_WAKEUP
key KEYCODE_HOME
wait_home || fail "home screen not in front"
sleep 3
ui | grep -qE 'text="[0-9]{1,2}:[0-9]{2}"' || fail "no time in the pill"

echo "== memory after 2 minutes on Home"
sleep 120
pss=$(tv dumpsys meminfo com.ansub.tvhome | sed -n 's/.*TOTAL PSS: *\([0-9]*\).*/\1/p' | head -1)
echo "   TOTAL PSS ${pss} KB"
[ -n "$pss" ] && [ "$pss" -le 30000 ] || fail "TOTAL PSS ${pss} KB is over 30000"

echo "== Control Center"
key KEYCODE_HOME
key KEYCODE_DPAD_UP
key KEYCODE_DPAD_CENTER
x=$(ui)
for want in Settings Inputs Wallpaper Wi-Fi Bluetooth "About this picture"; do
  echo "$x" | grep -q "$want" || fail "Control Center has no \"$want\""
done
key KEYCODE_BACK
ui | grep -q 'text="Wallpaper"' && fail "Back didn't close Control Center"

echo "== boot fix still in place"
tv dumpsys package com.ansub.tvhome | grep -q "com.ansub.tvhome/.BootReceiver" || fail "BootReceiver not registered"
tv appops get com.ansub.tvhome SYSTEM_ALERT_WINDOW | grep -q allow || fail "SYSTEM_ALERT_WINDOW not allowed"
tv appops get com.ansub.tvhome GET_USAGE_STATS | grep -q allow || fail "GET_USAGE_STATS not allowed"

echo "PASS"
