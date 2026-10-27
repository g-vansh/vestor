#!/usr/bin/env bash
# Installs Lucent on a Fire TV and gives it the HOME button. Run from your computer:
#
#   adb connect <fire-tv-ip>:5555      # accept "Allow USB debugging?" on the TV
#   ./install.sh                       # install, take over HOME, open Lucent
#   ./install.sh --undo                # give HOME back to Amazon (Lucent stays installed)
#   ./install.sh --remove              # give HOME back and uninstall Lucent
#   ./install.sh --adb-off             # turn ADB Debugging off once you are done (safer)
#
# Nothing here touches /system, roots the device or disables Amazon apps. Everything it
# changes is listed under "What it changes" in README.md and reversed by --undo.
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PKG=tv.lucent.home
GUARD=$PKG/$PKG.Guard
LISTENER=$PKG/$PKG.Listener
LABEL="Lucent home button"
OTHER_GUARDS=(
  tv.dumbtv.home/tv.dumbtv.home.Guard
  com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService
)
ADB=${ADB:-adb}
APK=${APK:-lucent.apk}
[[ -f $APK ]] || APK=build/lucent.apk

adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
# adb shell joins its arguments and the TV's shell parses the line again, so every argument is
# single-quoted here: nothing read from the TV can turn into a command.
# The quote characters are held in variables: inside ${a//...}, bash 3.2 (macOS) and bash 5 read
# escaped quotes differently, and this form means the same thing to both.
sh_() {
  local cmd= a q="'" bs='\'
  for a in "$@"; do cmd+=" '${a//$q/$q$bs$q$q}'"; done
  adb_ shell "$cmd" </dev/null | tr -d '\r'
}
# Text from the TV shown on your terminal: plain characters only, so it cannot send escape codes.
plain() { printf '%s' "$1" | LC_ALL=C tr -cd '[:alnum:] ._,:()/+-' | cut -c1-120; }

# Lucent's signing key as Android reports it (dumpsys package). A copy signed with anything else
# did not come from this key and is never given the HOME button. If you sign your own build with
# a new key, set LUCENT_SIG to the value build.sh prints.
SIG=${LUCENT_SIG:-364d93c7}       # certificate SHA-256 9ba0dd17...cad7f
SIG_RETIRED=82789a35              # the first build's key, retired: it travelled inside that zip
installed_sig() {
  local d
  d=$(sh_ dumpsys package $PKG 2>/dev/null) || return 0
  [[ $d == *"Package [$PKG]"* && $d =~ signatures:\[([0-9a-f]+)\] ]] && printf '%s' "${BASH_REMATCH[1]}"
  return 0
}

mode=install
case ${1:-} in
  "") ;;
  --undo) mode=undo ;;
  --remove) mode=remove ;;
  --adb-off) mode=adboff ;;
  *) echo "usage: $0 [--undo | --remove | --adb-off]" >&2; exit 2 ;;
esac

command -v "$ADB" >/dev/null || { echo "error: adb not found. Install Android platform-tools (brew install android-platform-tools)." >&2; exit 1; }
[[ $(adb_ get-state 2>/dev/null || true) == device ]] || {
  echo "error: no Fire TV connected. Run: adb connect <fire-tv-ip>:5555 and accept the prompt on the TV." >&2; exit 1; }

# ADB is the one way in from the network, so it should stay off unless you are installing,
# updating or undoing. This flips the same switch as Developer Options and ends the session.
if [[ $mode == adboff ]]; then
  sh_ settings put global adb_enabled 0 >/dev/null 2>&1 || true
  if [[ -n ${ADB_SERIAL:-} ]]; then "$ADB" disconnect "$ADB_SERIAL" >/dev/null 2>&1 || true
  else "$ADB" disconnect >/dev/null 2>&1 || true; fi
  echo "ADB Debugging is off. To update or undo later, turn it on again in Settings › My Fire TV › Developer Options."
  exit 0
fi

model=$(sh_ getprop ro.product.model)
sdk=$(sh_ getprop ro.build.version.sdk)
fireos=$(sh_ getprop ro.build.version.name)
echo "device: $(plain "$model"), Android API $(plain "$sdk"), $(plain "${fireos:-Fire OS}")"
case $sdk in ''|*[!0-9]*) sdk=0 ;; esac
if [[ $mode == install && $sdk -lt 28 ]]; then
  echo "error: Lucent needs Fire OS 7 or 8 (Android 9+). This device reports API $sdk." >&2
  exit 1
fi

# Fire OS 6 silently drops some `settings put` writes; the content provider still takes them.
put_secure() {
  local got
  sh_ settings put secure "$1" "${2:-}" >/dev/null
  got=$(sh_ settings get secure "$1")
  [[ $got == "$2" || ( -z $2 && $got == null ) ]] && return 0
  sh_ content insert --uri content://settings/secure --bind "name:s:$1" --bind "value:s:$2" >/dev/null
}

# A colon-separated list of components, as Android stores enabled accessibility services.
COMPONENTS='^[A-Za-z0-9._$/:]*$'

# Replaces any home-button guard (ours or another launcher's) with $1, keeping every other
# accessibility service the TV has, such as VoiceView, switched on throughout.
set_guard() {
  local cur g
  # orig stays global on purpose: the EXIT trap below reads it after an interruption.
  orig=$(sh_ settings get secure enabled_accessibility_services)
  [[ $orig == null ]] && orig=
  if ! [[ $orig =~ $COMPONENTS ]]; then
    echo "error: the TV's list of accessibility services looks unusual, so nothing was changed: $(printf '%q' "$orig")" >&2
    exit 1
  fi
  cur=$orig
  for g in "$GUARD" "$PKG/.Guard" "${OTHER_GUARDS[@]}"; do
    cur=$(printf '%s' "$cur" | tr ':' '\n' | { grep -vxF -e "$g" -e '' || true; } | paste -sd: -)
  done
  # The list without any home-button service first, so Android lets go of ours and binds it
  # afresh (it may be a new version). If this is cut short, the trap puts the original back.
  trap 'put_secure enabled_accessibility_services "$orig" || true' EXIT
  trap 'exit 130' INT TERM
  put_secure enabled_accessibility_services "$cur"
  sleep 1
  if [[ -n ${1:-} ]]; then
    cur=${cur:+$cur:}$1
    put_secure enabled_accessibility_services "$cur"
  fi
  if [[ -n $cur ]]; then put_secure accessibility_enabled 1; else put_secure accessibility_enabled 0; fi
  trap - EXIT INT TERM
}

# Android delays alarms by 5 s unless min_futurity is 0; the HOME switch needs them at once.
# Android 11 (Fire OS 8) reads it from settings global alarm_manager_constants. A value someone
# set before (or "none") is kept in lucent_min_futurity and put back by --undo.
# Values read back from the TV are only ever plain key=value lists; anything else stops the script
# before it writes, rather than being passed along.
SETTING='^[A-Za-z0-9_.=,:-]*$'
constants() {
  local v
  v=$(sh_ settings get global alarm_manager_constants)
  [[ $v == null ]] && v=
  if ! [[ $v =~ $SETTING ]]; then
    echo "error: the TV's alarm_manager_constants looks unusual, so nothing was changed: $(printf '%q' "$v")" >&2
    exit 1
  fi
  printf '%s' "$v"
}
set_futurity() {
  local cur rest old saved
  cur=$(constants)
  old=$(printf '%s' "$cur" | tr ',' '\n' | sed -n 's/^min_futurity=//p' | head -1)
  rest=$(printf '%s' "$cur" | tr ',' '\n' | { grep -v '^min_futurity=' || true; } | { grep -v '^$' || true; } | paste -sd, -)
  saved=$(sh_ settings get global lucent_min_futurity)
  [[ $saved =~ ^([0-9]+|none)$ ]] || saved=
  if [[ $1 == on ]]; then
    # Remember what was there before the first install, even a 0 another launcher set, or
    # "none". A reinstall finds the record and keeps it.
    if [[ -z $saved ]]; then sh_ settings put global lucent_min_futurity "${old:-none}" >/dev/null; fi
    sh_ settings put global alarm_manager_constants "${rest:+$rest,}min_futurity=0" >/dev/null
    if [[ $sdk -ge 31 ]]; then sh_ device_config put alarm_manager min_futurity 0 >/dev/null 2>&1 || true; fi
    return
  fi
  if [[ $saved =~ ^[0-9]+$ ]]; then rest=${rest:+$rest,}min_futurity=$saved; fi
  if [[ -n $rest ]]; then sh_ settings put global alarm_manager_constants "$rest" >/dev/null
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
  if [[ -n $saved ]]; then sh_ settings delete global lucent_min_futurity >/dev/null; fi
  if [[ $sdk -ge 31 ]]; then sh_ device_config delete alarm_manager min_futurity >/dev/null 2>&1 || true; fi
  return 0
}

# The bound-services dump names a service by its label (or, right after an install, by its
# class). Enabled and binding lists name components, so this cannot match an unbound service.
guard_bound() {
  # Read the whole dump first: grep -q exits at the first match, and under pipefail the writer's
  # SIGPIPE would turn that match into a failure.
  local dump
  dump=$(sh_ dumpsys accessibility) || return 1
  grep -qE "label=($LABEL|$PKG\.Guard)," <<<"$dump"
}

if [[ $mode != install ]]; then
  sh_ am force-stop $PKG >/dev/null 2>&1 || true
  record=$(sh_ settings get global lucent_prev_guards)
  # Only launchers this script knows are switched back on, whatever the record says.
  restore=
  for g in "${OTHER_GUARDS[@]}"; do
    if [[ ":$record:" == *":$g:"* ]]; then restore=${restore:+$restore:}$g; fi
  done
  set_guard "$restore"
  if [[ $record != null && -n $record ]]; then sh_ settings delete global lucent_prev_guards >/dev/null 2>&1 || true; fi
  if [[ -n $restore ]]; then
    echo "turned the other launcher's home-button service back on: ${restore//:/, }"
  fi
  set_futurity off
  sh_ dumpsys deviceidle whitelist -$PKG >/dev/null 2>&1 || true
  # The two grants this script gave: re-enabling its own HOME service after updates, and seeing
  # what is playing. Without them nothing can switch HOME back on behind your back.
  sh_ pm revoke $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  sh_ cmd notification disallow_listener "$LISTENER" >/dev/null 2>&1 || true
  sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
  if [[ $mode == remove ]]; then
    adb_ uninstall $PKG >/dev/null 2>&1 || true
    echo "HOME is Amazon's again and Lucent is uninstalled. Nothing else was changed."
  else
    echo "HOME is Amazon's again. Lucent is still installed: run ./install.sh to switch back, or --remove."
  fi
  exit 0
fi

[[ -f $APK ]] || { echo "error: $APK not found next to this script" >&2; exit 1; }
# With Android's apksigner on this computer, the APK's full certificate fingerprint is checked
# before anything is installed (the TV-side check below only sees a short hash).
CERT=9ba0dd174f8348d9acabf9f71e4ff128b0bb03821871adc56dada9a1aaacbd7f
if [[ -z ${LUCENT_SIG:-} ]] && command -v apksigner >/dev/null 2>&1; then
  certs=$(apksigner verify --print-certs "$APK" 2>/dev/null || true)
  if [[ $certs != *"SHA-256 digest: $CERT"* ]]; then
    echo "error: $APK is not signed with Lucent's key, so nothing was installed. Check where it came from." >&2
    exit 1
  fi
fi
# Checked before anything is changed: set_guard refuses such a list, and nothing else may have
# been touched by then.
current=$(sh_ settings get secure enabled_accessibility_services)
if [[ $current != null ]] && ! [[ $current =~ $COMPONENTS ]]; then
  echo "error: the TV's list of accessibility services looks unusual, so nothing was changed: $(printf '%q' "$current")" >&2
  exit 1
fi
# The same goes for the alarm timings this script rewrites: checked before the APK goes in.
checked=$(constants)
install_apk() { adb_ install -r "$APK" 2>&1 | grep -m1 -E 'Success|Failure|failed' || true; }

# Android lets an update in only if it carries the same signature, which is what stops anyone
# else replacing Lucent. A copy on the TV signed with another key is replaced only if you agree.
replace_old() {
  echo "$1"
  echo "Replacing it resets your dock order and hidden apps."
  local ans=
  if [[ -t 0 ]]; then read -r -p "Remove it and install this one? [y/N] " ans || ans=; fi
  if [[ $ans != [yY]* ]]; then
    echo "Nothing was changed. To replace it later: adb uninstall $PKG, then ./install.sh" >&2
    exit 1
  fi
  adb_ uninstall $PKG >/dev/null 2>&1 || true
}
was=$(installed_sig)
if [[ -n $was && $was != "$SIG" ]]; then
  if [[ $was == "$SIG_RETIRED" ]]; then
    replace_old "The Lucent on this TV is the first build, signed with a key that is now retired."
  else
    replace_old "The Lucent on this TV is signed with a key this one does not know ($was)."
  fi
fi
echo "installing $APK"
out=$(install_apk)
if [[ $out == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
  replace_old "The Lucent on this TV is signed with a different key than this one."
  out=$(install_apk)
fi
case $out in
  *Success*) ;;
  *) echo "error: adb install failed: ${out:-no output}" >&2; exit 1 ;;
esac
now=$(installed_sig)
if [[ -n $now && $now != "$SIG" ]]; then
  echo "error: the app just installed is not signed with Lucent's key ($now), so it does not get the" >&2
  echo "  HOME button. Check where this lucent.apk came from. Remove it with: adb uninstall $PKG" >&2
  exit 1
fi

# What is there now, so a failed install can put back exactly this, including the records an
# earlier install left for --undo.
had_fut=$(sh_ settings get global lucent_min_futurity)
[[ $had_fut =~ ^([0-9]+|none)$ ]] || had_fut=
had_prev=$(sh_ settings get global lucent_prev_guards)
{ [[ $had_prev != null ]] && [[ $had_prev =~ $COMPONENTS ]]; } || had_prev=
before_services=$(sh_ settings get secure enabled_accessibility_services)
[[ $before_services == null ]] && before_services=
before_enabled=$(sh_ settings get secure accessibility_enabled)
[[ $before_enabled =~ ^[01]$ ]] || before_enabled=0
before_constants=$(constants)

# Another launcher's HOME service is switched off (two would fight over HOME) and remembered,
# so --undo can switch it back on.
prev=()
for g in "${OTHER_GUARDS[@]}"; do
  if [[ ":$before_services:" == *":$g:"* ]]; then
    echo "note: another launcher's home-button service (${g%%/*}) was on; Lucent pauses it so they do not fight over HOME. --undo turns it back on."
    prev+=("$g")
  fi
done
if [[ ${#prev[@]} -gt 0 ]]; then
  # Add to any record an earlier install made, so every paused service comes back on --undo.
  record=$had_prev
  for g in "${prev[@]}"; do
    if [[ ":$record:" != *":$g:"* ]]; then record=${record:+$record:}$g; fi
  done
  sh_ settings put global lucent_prev_guards "$record" >/dev/null
fi

# Keeps the guard's alarm from being deferred while the stick idles.
sh_ dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
set_futurity on
set_guard "$GUARD"

bound=0
for _ in $(seq 1 15); do
  if guard_bound; then bound=1; break; fi
  sleep 1
done
if [[ $bound == 0 ]]; then
  echo "error: Fire OS did not start Lucent's home-button service." >&2
  log=$(sh_ logcat -d -b all 2>/dev/null || true)
  if grep -F A11yAuthorizer <<<"$log" | grep -F "$PKG" >/dev/null; then
    echo "  This build only runs accessibility services Amazon trusts, so HOME cannot be taken over here." >&2
  fi
  echo "  Undoing the HOME changes. Lucent stays installed as a normal app (open it from Your Apps)." >&2
  put_secure enabled_accessibility_services "$before_services" || true
  [[ -n $before_services ]] || put_secure accessibility_enabled "$before_enabled" || true
  if [[ -n $before_constants ]]; then sh_ settings put global alarm_manager_constants "$before_constants" >/dev/null
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
  # Records from an earlier install stay for --undo. Only what this run wrote is taken back.
  if [[ -n $had_fut ]]; then sh_ settings put global lucent_min_futurity "$had_fut" >/dev/null
  else sh_ settings delete global lucent_min_futurity >/dev/null 2>&1 || true; fi
  if [[ -n $had_prev ]]; then sh_ settings put global lucent_prev_guards "$had_prev" >/dev/null
  else sh_ settings delete global lucent_prev_guards >/dev/null 2>&1 || true; fi
  if [[ -z $had_fut ]]; then sh_ dumpsys deviceidle whitelist -$PKG >/dev/null 2>&1 || true; fi
  exit 1
fi

# Two one-time grants Fire OS has no screen for. The first lets Lucent put its own HOME service
# back after a Fire OS update or a restart switches it off (Boot.java, nothing else). The second
# lets it see which app is playing for Now Playing and the remote's play key; it reads no
# notifications. --undo takes both back.
if sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; then
  echo "granted: Lucent can switch its HOME service back on after a Fire OS update"
else
  echo "note: this Fire OS refused the self-repair grant. After an update, run ./install.sh again if HOME goes back to Amazon."
fi
if sh_ cmd notification allow_listener "$LISTENER" >/dev/null 2>&1; then
  echo "granted: Now Playing (Lucent sees which app plays music, not your notifications)"
else
  echo "note: Now Playing was not granted, so the music card stays hidden. Everything else works."
fi

sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
sh_ am start -n $PKG/.Home >/dev/null 2>&1 || true
# Starting the activity also starts the control center: its address is on the TV under the phone icon.
ip=$(sh_ ip -4 -o addr show wlan0 2>/dev/null | sed -n 's/.* inet \([0-9.]*\)\/.*/\1/p' | head -1)
[[ -n $ip ]] || ip=$(sh_ ip -4 -o addr show eth0 2>/dev/null | sed -n 's/.* inet \([0-9.]*\)\/.*/\1/p' | head -1)
echo "done: press HOME on the remote. Lucent should appear in under half a second."
if [[ -n $ip ]]; then
  echo "phone control: on the TV choose the phone icon (top right) and scan the code, or open http://$ip:8686 on your home Wi-Fi"
fi
echo "undo any time: ./install.sh --undo"
# ADB is the one way in from the network: once HOME works, it should be off.
ans=n
if [[ -t 0 ]]; then
  read -r -p "Once HOME works, turn ADB Debugging off? It stays off until you turn it on in Developer Options. [Y/n] " ans || ans=n
  [[ -z $ans ]] && ans=y
fi
if [[ $ans == [yY]* ]]; then
  sh_ settings put global adb_enabled 0 >/dev/null 2>&1 || true
  if [[ -n ${ADB_SERIAL:-} ]]; then "$ADB" disconnect "$ADB_SERIAL" >/dev/null 2>&1 || true
  else "$ADB" disconnect >/dev/null 2>&1 || true; fi
  echo "ADB Debugging is off. Turn it on in Settings › My Fire TV › Developer Options to update or undo."
else
  echo "for safety, turn ADB off once you are done: ./install.sh --adb-off"
fi
