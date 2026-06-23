#!/usr/bin/env bash
# Build, install, and smoke-test vMessenger on a connected emulator/device via adb.
# Requires: ANDROID_HOME (or ~/Library/Android/sdk), running emulator/device.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="ir.vmessenger.android"
ACTIVITY="ir.vmessenger.android/ir.vmessenger.MainActivity"
ADB_BIN="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found. Set ANDROID_HOME or install Android SDK platform-tools." >&2
  exit 1
fi

DEVICE="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [[ -z "$DEVICE" ]]; then
  echo "No Android device/emulator connected." >&2
  exit 1
fi

adb_cmd() { "$ADB_BIN" -s "$DEVICE" "$@"; }

log() { printf '\n==> %s\n' "$*"; }

dump_ui() {
  adb_cmd shell uiautomator dump /sdcard/vm-ui.xml >/dev/null 2>&1
  adb_cmd shell cat /sdcard/vm-ui.xml
}

scroll_down() {
  adb_cmd shell input swipe 540 1800 540 800 300
  sleep 0.5
}

tap_bottom_tab() {
  local label="$1"
  local x
  case "$label" in
    "تنظیمات") x=127 ;;
    "موقعیت") x=402 ;;
    "مخاطبین") x=677 ;;
    "گفتگوها") x=952 ;;
    *) echo "Unknown tab: $label" >&2; return 1 ;;
  esac
  adb_cmd shell input tap "$x" 2169
  sleep 0.8
}

settings_scroll_to_bottom() {
  local i
  for i in 1 2 3 4; do
    scroll_down
  done
}

find_text_center() {
  local text="$1"
  local clickable_only="${2:-false}"
  local xml
  adb_cmd shell uiautomator dump /sdcard/vm-ui.xml >/dev/null 2>&1
  xml="$(adb_cmd shell cat /sdcard/vm-ui.xml)"
  VM_UI_XML="$xml" VM_UI_NEEDLE="$text" VM_UI_CLICKABLE_ONLY="$clickable_only" python3 <<'PY'
import os, re, sys
needle = os.environ["VM_UI_NEEDLE"]
clickable_only = os.environ["VM_UI_CLICKABLE_ONLY"] == "true"
xml = os.environ["VM_UI_XML"]
pattern = re.compile(
    r'text="([^"]*)"[^>]*clickable="(true|false)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)
matches = []
for m in pattern.finditer(xml):
    if needle not in m.group(1):
        continue
    if clickable_only and m.group(2) != "true":
        continue
    x1, y1, x2, y2 = map(int, m.groups()[2:])
    matches.append(((x1 + x2) // 2, (y1 + y2) // 2))
if not matches:
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if needle in m.group(1):
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            matches.append(((x1 + x2) // 2, (y1 + y2) // 2))
if not matches:
    sys.exit(1)
print(f"{matches[0][0]} {matches[0][1]}")
PY
}

tap_text() {
  local text="$1"
  local attempt cx cy
  for attempt in 1 2 3 4 5; do
    if coords="$(find_text_center "$text" true 2>/dev/null || find_text_center "$text" false 2>/dev/null)"; then
      read -r cx cy <<<"$coords"
      adb_cmd shell input tap "$cx" "$cy"
      return 0
    fi
    scroll_down
  done
  echo "Could not find UI element with text: $text" >&2
  return 1
}

wait_for_text() {
  local text="$1"
  local tries="${2:-30}"
  local i
  for ((i = 0; i < tries; i++)); do
    if adb_cmd shell uiautomator dump /sdcard/vm-ui.xml >/dev/null 2>&1 \
      && adb_cmd shell cat /sdcard/vm-ui.xml | grep -Fq "$text"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for text: $text" >&2
  return 1
}

log "Device: $DEVICE"
log "Build (assembleDebug)"
cd "$ROOT"
./gradlew assembleDebug -q

log "Install debug APK"
./gradlew installDebug -q

log "Clear app data for fresh identity flow"
adb_cmd shell pm clear "$PKG" >/dev/null

log "Launch app"
adb_cmd logcat -c
adb_cmd shell am start -W -n "$ACTIVITY"
sleep 2

log "Create identity"
wait_for_text "ساخت هویت من"
tap_text "ساخت هویت من"
wait_for_text "ادامه" 60
tap_text "ادامه"

log "Navigate main tabs"
for tab in "گفتگوها" "مخاطبین" "موقعیت" "تنظیمات"; do
  tap_bottom_tab "$tab"
done

log "Settings → About"
settings_scroll_to_bottom
tap_text "درباره"
wait_for_text "vMessenger"
adb_cmd shell input keyevent KEYCODE_BACK
sleep 1

log "Settings → Debug"
tap_bottom_tab "تنظیمات"
settings_scroll_to_bottom
tap_text "اشکال‌زدایی"
wait_for_text "پیوستن و انتشار endpoint"
adb_cmd shell input keyevent KEYCODE_BACK
sleep 1

log "Settings → Identity"
tap_bottom_tab "تنظیمات"
settings_scroll_to_bottom
tap_text "هویت من"
sleep 1
adb_cmd shell input keyevent KEYCODE_BACK
sleep 1

log "Contacts → add by hash"
tap_bottom_tab "مخاطبین"
wait_for_text "هنوز مخاطبی ندارید"
# Extended FAB text is not always exposed to accessibility; tap known RTL FAB bounds.
adb_cmd shell input tap 247 1822
wait_for_text "افزودن با شناسه کاربری"
adb_cmd shell input keyevent KEYCODE_BACK
sleep 1
adb_cmd shell input keyevent KEYCODE_BACK
sleep 1

log "Location tab"
tap_bottom_tab "موقعیت"
wait_for_text "شروع اشتراک‌گذاری"
sleep 1

log "Check logcat for fatal errors"
if adb_cmd logcat -d | grep -E "FATAL EXCEPTION|UnsatisfiedLinkError" | grep -q "$PKG"; then
  echo "FAIL: crash detected in logcat" >&2
  adb_cmd logcat -d | grep -A20 "FATAL EXCEPTION" | tail -25
  exit 1
fi

log "Smoke test passed"
