#!/usr/bin/env bash
# Installs Vestor on a Fire TV and gives it the HOME button. Run from your computer:
#
#   adb connect <fire-tv-ip>:5555      # accept "Allow USB debugging?" on the TV
#   ./install.sh                       # install, take over HOME, open Vestor
#   ./install.sh --undo                # give HOME back to Amazon (Vestor stays installed)
#   ./install.sh --remove              # give HOME back and uninstall Vestor
#   ./install.sh --adb-off             # turn ADB Debugging off once you are done (safer)
#   ./install.sh --claude-key FILE     # put the Claude API key from FILE on the TV (Ask Vestor)
#   VESTOR_CLAUDE_KEY=FILE ./install.sh   # the same, as part of an install or update
#
# Nothing here touches /system, roots the device or disables Amazon apps. Everything it
# changes is listed under "What it changes" in README.md and reversed by --undo.
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PKG=tv.vestor.home
GUARD=$PKG/$PKG.Guard
LISTENER=$PKG/$PKG.Listener
LABEL="Vestor home button"
# Lucent was this app's earlier name (tv.lucent.home). Its HOME service never stays on beside this
# one, and once this one has HOME the old app is switched off, never removed (see README).
OLD_PKG=tv.lucent.home
OLD_GUARDS=("$OLD_PKG/$OLD_PKG.Guard" "$OLD_PKG/.Guard")
OTHER_GUARDS=(
  tv.dumbtv.home/tv.dumbtv.home.Guard
  com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService
)
ADB=${ADB:-adb}
APK=${APK:-vestoros.apk}
[[ -f $APK ]] || APK=build/vestoros.apk

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

# Vestor's signing key as Android reports it (dumpsys package). A copy signed with anything else
# did not come from this key and is never given the HOME button. If you sign your own build with
# a new key, set VESTOR_SIG to the value build.sh prints.
SIG=${VESTOR_SIG:-8e4a2ae1}       # certificate SHA-256 d6ee2421...772752
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
  --claude-key) mode=claudekey; VESTOR_CLAUDE_KEY=${2:-} ;;
  --claude-key-forget) mode=claudeforget ;;
  *) echo "usage: $0 [--undo | --remove | --adb-off | --claude-key FILE | --claude-key-forget]" >&2; exit 2 ;;
esac

# The Claude API key, read from a file you keep private, goes over ADB straight into Vestor's
# private storage: "content write" streams it from standard input, so it is on no command line,
# in no shell history and in no broadcast record. On the TV only ADB's shell may hand it over
# (KeyDrop checks the caller), and only to a Vestor signed with Vestor's key. Phones never see it.
claude_key() {
  local f=$1 k out
  [[ -f $f ]] || { echo "error: no key file at $f" >&2; return 1; }
  k=$(LC_ALL=C tr -d ' \r\n\t' < "$f")
  # macOS reads a repeat count over 255 as a broken pattern and matches nothing, so 255 is the cap.
  [[ $k =~ ^sk-ant-[A-Za-z0-9_-]{20,255}$ ]] || { echo "error: $f does not hold a Claude API key (it starts with sk-ant-)" >&2; return 1; }
  local sig
  sig=$(installed_sig)
  [[ $sig == "$SIG" ]] || { echo "error: the Vestor on this TV is not signed with Vestor's key, so it does not get the Claude key." >&2; return 1; }
  printf '%s' "$k" | adb_ shell content write --uri content://$PKG.key/claude >/dev/null 2>&1 || true
  sleep 1
  out=$(sh_ content call --uri content://$PKG.key --method status 2>&1 || true)
  if [[ $out == *"result=set"* ]]; then
    echo "Claude key saved on the TV (it ends in ${k: -4}). Ask is ready: the sparkle at the top of the home screen."
  else
    echo "error: the TV did not take the key. Is Vestor installed and open once? $(plain "$out")" >&2
    return 1
  fi
}

command -v "$ADB" >/dev/null || { echo "error: adb not found. Install Android platform-tools (brew install android-platform-tools)." >&2; exit 1; }
[[ $(adb_ get-state 2>/dev/null || true) == device ]] || {
  echo "error: no Fire TV connected. Run: adb connect <fire-tv-ip>:5555 and accept the prompt on the TV." >&2; exit 1; }

# ADB is the one way in from the network, so it should stay off unless you are installing,
# updating or undoing. This flips the same switch as Developer Options and ends the session.
if [[ $mode == claudekey ]]; then
  [[ -n ${VESTOR_CLAUDE_KEY:-} ]] || { echo "usage: $0 --claude-key FILE" >&2; exit 2; }
  claude_key "$VESTOR_CLAUDE_KEY"
  exit $?
fi
if [[ $mode == claudeforget ]]; then
  out=$(sh_ content call --uri content://$PKG.key --method forget 2>&1 || true)
  if [[ $out == *"result=forgotten"* ]]; then echo "The Claude key is gone from the TV."; else echo "error: the TV did not answer: $(plain "$out")" >&2; exit 1; fi
  exit 0
fi

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
  echo "error: Vestor needs Fire OS 7 or 8 (Android 9+). This device reports API $sdk." >&2
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
    echo "error: the TV's list of accessibility services looks unusual, so the HOME button was left as it is: $(printf '%q' "$orig")" >&2
    exit 1
  fi
  cur=$orig
  for g in "$GUARD" "$PKG/.Guard" "${OLD_GUARDS[@]}" "${OTHER_GUARDS[@]}"; do
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
# set before (or "none") is kept in vestor_min_futurity and put back by --undo.
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
  saved=$(sh_ settings get global vestor_min_futurity)
  [[ $saved =~ ^([0-9]+|none)$ ]] || saved=
  if [[ $1 == on ]]; then
    # Remember what was there before the first install, even a 0 another launcher set, or
    # "none". A reinstall finds the record and keeps it.
    if [[ -z $saved ]]; then sh_ settings put global vestor_min_futurity "${old:-none}" >/dev/null; fi
    sh_ settings put global alarm_manager_constants "${rest:+$rest,}min_futurity=0" >/dev/null
    if [[ $sdk -ge 31 ]]; then sh_ device_config put alarm_manager min_futurity 0 >/dev/null 2>&1 || true; fi
    return
  fi
  if [[ $saved =~ ^[0-9]+$ ]]; then rest=${rest:+$rest,}min_futurity=$saved; fi
  if [[ -n $rest ]]; then sh_ settings put global alarm_manager_constants "$rest" >/dev/null
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
  if [[ -n $saved ]]; then sh_ settings delete global vestor_min_futurity >/dev/null; fi
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

# Records an install made under Lucent, this app's earlier name, move to Vestor's names once, so
# --undo still puts back what was there before the very first install.
carry_record() {
  local old new
  old=$(sh_ settings get global "$1")
  [[ $old == null || -z $old ]] && return 0
  new=$(sh_ settings get global "$2")
  if [[ $new == null || -z $new ]] && [[ $old =~ $3 ]]; then sh_ settings put global "$2" "$old" >/dev/null; fi
  sh_ settings delete global "$1" >/dev/null 2>&1 || true
}
carry_record lucent_min_futurity vestor_min_futurity '^([0-9]+|none)$'
carry_record lucent_prev_guards vestor_prev_guards "$COMPONENTS"

# Whether a path exists on the TV, without trusting how adb passes the exit status along.
on_tv() { [[ $(sh_ sh -c 'test -e "$1" && echo yes' x "$1" 2>/dev/null) == yes ]]; }

if [[ $mode != install ]]; then
  sh_ am force-stop $PKG >/dev/null 2>&1 || true
  record=$(sh_ settings get global vestor_prev_guards)
  # Only launchers this script knows are switched back on, whatever the record says.
  restore=
  for g in "${OTHER_GUARDS[@]}"; do
    if [[ ":$record:" == *":$g:"* ]]; then restore=${restore:+$restore:}$g; fi
  done
  set_guard "$restore"
  if [[ $record != null && -n $record ]]; then sh_ settings delete global vestor_prev_guards >/dev/null 2>&1 || true; fi
  if [[ -n $restore ]]; then
    echo "turned the other launcher's home-button service back on: ${restore//:/, }"
  fi
  set_futurity off
  sh_ dumpsys deviceidle whitelist -$PKG >/dev/null 2>&1 || true
  # The grants this script gave: re-enabling its own HOME service after updates, seeing what is
  # playing, and offering its own updates. Without them nothing can switch HOME back on behind
  # your back, and Vestor can no longer start an update.
  sh_ pm revoke $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  sh_ cmd notification disallow_listener "$LISTENER" >/dev/null 2>&1 || true
  sh_ appops set $PKG REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true
  sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
  if [[ $mode == remove ]]; then
    adb_ uninstall $PKG >/dev/null 2>&1 || true
    echo "HOME is Amazon's again and Vestor is uninstalled. Nothing else was changed."
  else
    echo "HOME is Amazon's again. Vestor is still installed: run ./install.sh to switch back, or --remove."
  fi
  exit 0
fi

[[ -f $APK ]] || { echo "error: $APK not found next to this script" >&2; exit 1; }
# With Android's apksigner on this computer, the APK's full certificate fingerprint is checked
# before anything is installed (the TV-side check below only sees a short hash).
CERT=d6ee242165e4281248b0d1493e9f436143243d97614419dd73f3fe28a3772752
if [[ -z ${VESTOR_SIG:-} ]] && command -v apksigner >/dev/null 2>&1; then
  certs=$(apksigner verify --print-certs "$APK" 2>/dev/null || true)
  if [[ $certs != *"SHA-256 digest: $CERT"* ]]; then
    echo "error: $APK is not signed with Vestor's key, so nothing was installed. Check where it came from." >&2
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
# else replacing Vestor. A copy on the TV signed with another key is replaced only if you agree.
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
  replace_old "The Vestor on this TV is signed with a key this one does not know ($was)."
fi
# The APK's own fingerprint, to compare with the one published beside vestoros.zip if you like.
apk_sum=$( { shasum -a 256 "$APK" 2>/dev/null || sha256sum "$APK" 2>/dev/null; } | cut -d' ' -f1)
echo "installing $APK${apk_sum:+ (SHA-256 $apk_sum)}"
out=$(install_apk)
if [[ $out == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
  replace_old "The Vestor on this TV is signed with a different key than this one."
  out=$(install_apk)
fi
case $out in
  *Success*) ;;
  *) echo "error: adb install failed: ${out:-no output}" >&2; exit 1 ;;
esac
now=$(installed_sig)
if [[ -n $now && $now != "$SIG" ]]; then
  echo "error: the app just installed is not signed with Vestor's key ($now), so it does not get the" >&2
  echo "  HOME button. Check where this vestoros.apk came from. Remove it with: adb uninstall $PKG" >&2
  exit 1
fi

# Lucent, this app's earlier name, is stopped now so the two never run at once (it would hold the
# control center's port), and switched off further down once Vestor has HOME. The wallpapers,
# banners and lucent.conf you pushed for it come along; files already here are kept.
old_on=
pk=$(sh_ pm list packages -e "$OLD_PKG" 2>/dev/null || true)
if grep -qx "package:$OLD_PKG" <<<"$pk"; then
  old_on=1
  sh_ am force-stop $OLD_PKG >/dev/null 2>&1 || true
fi
om=/sdcard/Android/media/$OLD_PKG
nm=/sdcard/Android/media/$PKG
if on_tv "$om"; then
  sh_ mkdir -p "$nm" >/dev/null 2>&1 || true
  for d in wallpapers banners; do
    if on_tv "$om/$d" && ! on_tv "$nm/$d"; then
      sh_ cp -r "$om/$d" "$nm/$d" >/dev/null 2>&1 && echo "copied your $d from Lucent"
    fi
  done
  if on_tv "$om/lucent.conf" && ! on_tv "$nm/vestor.conf"; then
    sh_ cp "$om/lucent.conf" "$nm/vestor.conf" >/dev/null 2>&1 || true
  fi
fi

# What is there now, so a failed install can put back exactly this, including the records an
# earlier install left for --undo.
had_fut=$(sh_ settings get global vestor_min_futurity)
[[ $had_fut =~ ^([0-9]+|none)$ ]] || had_fut=
had_prev=$(sh_ settings get global vestor_prev_guards)
{ [[ $had_prev != null ]] && [[ $had_prev =~ $COMPONENTS ]]; } || had_prev=
before_services=$(sh_ settings get secure enabled_accessibility_services)
[[ $before_services == null ]] && before_services=
if ! [[ $before_services =~ $COMPONENTS ]]; then
  echo "error: the TV's list of accessibility services changed into something unusual during the install, so HOME was" >&2
  echo "  left alone. Vestor is installed as a normal app. Run ./install.sh again to finish." >&2
  exit 1
fi
before_enabled=$(sh_ settings get secure accessibility_enabled)
[[ $before_enabled =~ ^[01]$ ]] || before_enabled=0
before_constants=$(constants)

# Another launcher's HOME service is switched off (two would fight over HOME) and remembered,
# so --undo can switch it back on.
prev=()
for g in "${OTHER_GUARDS[@]}"; do
  if [[ ":$before_services:" == *":$g:"* ]]; then
    echo "note: another launcher's home-button service (${g%%/*}) was on; Vestor pauses it so they do not fight over HOME. --undo turns it back on."
    prev+=("$g")
  fi
done
if [[ ${#prev[@]} -gt 0 ]]; then
  # Add to any record an earlier install made, so every paused service comes back on --undo.
  record=$had_prev
  for g in "${prev[@]}"; do
    if [[ ":$record:" != *":$g:"* ]]; then record=${record:+$record:}$g; fi
  done
  sh_ settings put global vestor_prev_guards "$record" >/dev/null
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
  echo "error: Fire OS did not start Vestor's home-button service." >&2
  log=$(sh_ logcat -d -b all 2>/dev/null || true)
  if grep -F A11yAuthorizer <<<"$log" | grep -F "$PKG" >/dev/null; then
    echo "  This build only runs accessibility services Amazon trusts, so HOME cannot be taken over here." >&2
  fi
  echo "  Undoing the HOME changes. Vestor stays installed as a normal app (open it from Your Apps)." >&2
  put_secure enabled_accessibility_services "$before_services" || true
  [[ -n $before_services ]] || put_secure accessibility_enabled "$before_enabled" || true
  if [[ -n $before_constants ]]; then sh_ settings put global alarm_manager_constants "$before_constants" >/dev/null
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
  # Records from an earlier install stay for --undo. Only what this run wrote is taken back.
  if [[ -n $had_fut ]]; then sh_ settings put global vestor_min_futurity "$had_fut" >/dev/null
  else sh_ settings delete global vestor_min_futurity >/dev/null 2>&1 || true; fi
  if [[ -n $had_prev ]]; then sh_ settings put global vestor_prev_guards "$had_prev" >/dev/null
  else sh_ settings delete global vestor_prev_guards >/dev/null 2>&1 || true; fi
  if [[ -z $had_fut ]]; then sh_ dumpsys deviceidle whitelist -$PKG >/dev/null 2>&1 || true; fi
  exit 1
fi

# One-time grants. The first lets Vestor put its own HOME service back after a Fire OS update or
# a restart switches it off (Boot.java, nothing else). The second lets it see which app is
# playing for Now Playing and the remote's play key; it reads no notifications. The third is
# "Install unknown apps" for Vestor, so it can hand its own updates to Fire OS's installer
# (Updater.java); each update still asks you to confirm, and Android installs only one signed
# with Vestor's key. --undo takes all three back.
if sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; then
  echo "granted: Vestor can switch its HOME service back on after a Fire OS update"
else
  echo "note: this Fire OS refused the self-repair grant. After an update, run ./install.sh again if HOME goes back to Amazon."
fi
if sh_ cmd notification allow_listener "$LISTENER" >/dev/null 2>&1; then
  echo "granted: Now Playing (Vestor sees which app plays music, not your notifications)"
else
  echo "note: Now Playing was not granted, so the music card stays hidden. Everything else works."
fi
if sh_ appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1; then
  echo "granted: updates from the TV (Vestor offers new versions, you confirm each with Install)"
else
  echo "note: to update from the TV, allow it once: Settings, My Fire TV, Developer Options, Install Unknown Apps, Vestor."
fi

if [[ -n ${VESTOR_CLAUDE_KEY:-} ]]; then claude_key "$VESTOR_CLAUDE_KEY" || true; fi

# Vestor has HOME, so Lucent is switched off: its two grants are taken back and it is disabled,
# not removed. Its notes and photos stay in it. adb shell pm enable tv.lucent.home undoes this.
if [[ -n $old_on ]]; then
  sh_ pm revoke $OLD_PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  sh_ cmd notification disallow_listener "$OLD_PKG/$OLD_PKG.Listener" >/dev/null 2>&1 || true
  sh_ dumpsys deviceidle whitelist -$OLD_PKG >/dev/null 2>&1 || true
  if sh_ pm disable-user --user 0 $OLD_PKG >/dev/null 2>&1; then
    echo "switched off Lucent (this app's old name). Remove it for good with: adb uninstall $OLD_PKG"
  fi
fi

sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
sh_ am start -n $PKG/.Home >/dev/null 2>&1 || true
# Starting the activity also starts the control center: its address is on the TV under the phone icon.
ip=$(sh_ ip -4 -o addr show wlan0 2>/dev/null | sed -n 's/.* inet \([0-9.]*\)\/.*/\1/p' | head -1)
[[ -n $ip ]] || ip=$(sh_ ip -4 -o addr show eth0 2>/dev/null | sed -n 's/.* inet \([0-9.]*\)\/.*/\1/p' | head -1)
echo "done: press HOME on the remote. Vestor should appear in under half a second."
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
