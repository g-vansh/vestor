#!/usr/bin/env bash
# Eyes and hands for the Fire TV, for a session on the laptop in front of the TV.
#
#   scripts/tv.sh shot [label]     the Fire TV's own picture (exact pixels), prints the PNG path
#   scripts/tv.sh cam [label]      a photo from the laptop camera (the Samsung's own screens too)
#   scripts/tv.sh key UP LEFT:6 OK  remote keys, NAME or NAME:count, 0.4 s apart
#   scripts/tv.sh text "hello"     type into the focused field
#   scripts/tv.sh ui               the system screen's buttons and which one has focus
#   scripts/tv.sh front            which app and window are in front
#   scripts/tv.sh log              follow Vestor's log (Ctrl-C to stop)
#   scripts/tv.sh logdump          the last Vestor and crash lines, once
#
# Keys: UP DOWN LEFT RIGHT OK BACK HOME MENU PLAY REW FF WAKE SLEEP, or any KEYCODE_ name.
# Pick the TV with ADB_SERIAL (ip:5555) when more than one device is connected. Files go to
# $TV_DIR (default ~/vestor-tv/shots).
set -euo pipefail
D=${TV_DIR:-$HOME/vestor-tv}/shots
mkdir -p "$D"
adb_() { adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
stamp() { date +%H%M%S; }

code() {
  case $1 in
    UP) echo KEYCODE_DPAD_UP ;;
    DOWN) echo KEYCODE_DPAD_DOWN ;;
    LEFT) echo KEYCODE_DPAD_LEFT ;;
    RIGHT) echo KEYCODE_DPAD_RIGHT ;;
    OK) echo KEYCODE_DPAD_CENTER ;;
    BACK) echo KEYCODE_BACK ;;
    HOME) echo KEYCODE_HOME ;;
    MENU) echo KEYCODE_MENU ;;
    PLAY) echo KEYCODE_MEDIA_PLAY_PAUSE ;;
    REW) echo KEYCODE_MEDIA_REWIND ;;
    FF) echo KEYCODE_MEDIA_FAST_FORWARD ;;
    WAKE) echo KEYCODE_WAKEUP ;;
    SLEEP) echo KEYCODE_SLEEP ;;
    KEYCODE_*) echo "$1" ;;
    *) echo "unknown key $1" >&2; return 1 ;;
  esac
}

case ${1:-} in
  shot)
    f="$D/fire-$(stamp)${2:+-$2}.png"
    adb_ exec-out screencap -p > "$f"
    [[ -s $f ]] || { echo "error: empty screenshot (is adb connected?)" >&2; exit 1; }
    echo "$f" ;;
  cam)
    command -v imagesnap >/dev/null || { echo "error: brew install imagesnap" >&2; exit 1; }
    f="$D/cam-$(stamp)${2:+-$2}.jpg"
    # Two seconds for the camera to settle its exposure on a bright TV.
    imagesnap -q -w 2 "$f" >/dev/null 2>&1 || true
    [[ -s $f ]] || { echo "error: no photo. Allow the camera for this terminal app: System Settings, Privacy & Security, Camera" >&2; exit 1; }
    echo "$f" ;;
  key)
    shift
    for k in "$@"; do
      n=1 name=$k
      if [[ $k == *:* ]]; then name=${k%%:*}; n=${k##*:}; fi
      c=$(code "$name")
      for ((i = 0; i < n; i++)); do adb_ shell input keyevent "$c"; sleep 0.4; done
    done ;;
  text)
    shift
    # input text takes %s for a space and nothing the TV's shell would read as syntax.
    t=$(printf '%s' "$*" | sed 's/ /%s/g')
    [[ $t =~ ^[A-Za-z0-9%@._,:/+-]*$ ]] || { echo "error: plain letters, digits and . , : / @ + - only" >&2; exit 1; }
    adb_ shell input text "$t" ;;
  ui)
    adb_ shell uiautomator dump /sdcard/vestor-ui.xml >/dev/null
    adb_ exec-out cat /sdcard/vestor-ui.xml > "$D/ui.xml"
    adb_ shell rm -f /sdcard/vestor-ui.xml
    python3 - "$D/ui.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter('node'):
    a = n.attrib
    label = a.get('text') or a.get('content-desc') or ''
    if not (label or a.get('focused') == 'true' or a.get('clickable') == 'true'):
        continue
    flags = ' '.join(k for k in ('focused', 'clickable', 'checked') if a.get(k) == 'true')
    print(f"{a.get('class','').split('.')[-1]:<14} {label!r:<44} {a.get('resource-id',''):<48} {a.get('bounds','')} {flags}")
EOF
    ;;
  front)
    adb_ shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" ;;
  log)
    adb_ logcat -v time -s Vestor:V AndroidRuntime:E PackageInstaller:I ;;
  logdump)
    adb_ logcat -d -v time -s Vestor:V AndroidRuntime:E | tail -80
    adb_ logcat -d -b crash | tail -40 ;;
  *)
    sed -n '2,15p' "$0"; exit 2 ;;
esac
