# VestorOS setup night: handoff for the local Claude Code session

You are a Claude Code session on the owner's Mac. The laptop stands in front of the living room
TV with its camera facing the screen. The owner (GitHub `g-vansh`) wants you to install, test and
debug VestorOS on the Fire TV yourself. Ask the owner only for things a person has to do: a press
on the remote where ADB cannot reach yet, scanning a code with their phone, pairing AirPods,
plugging in a cable. Batch those asks, say exactly which button, and keep going on everything else
while a step waits.

Work through the steps in order. After every action, look: a screenshot of the Fire TV, a camera
photo, or a command whose output you check against the expected value written here. Write down
what you find as you go. Section 12 says how to report it.

The cloud session that wrote VestorOS cannot reach this TV. You are the hands now.

---

## 0. What you are working with (facts, do not re-derive)

| Thing | Fact |
|---|---|
| Stick | Fire TV Stick 4K, 2nd gen, model `AFTKM`, Fire OS 8.1.8.5 (Android 11, API 30) |
| TV | Samsung TU7000 (2020, Tizen 5.5), the Fire TV on one of its two HDMI ports |
| Sound | VIZIO SV510X-0806 soundbar on the Samsung's eARC |
| App | VestorOS, package `tv.vestor.home`, a home screen for the Fire TV |
| Old app | Lucent, package `tv.lucent.home`, the same app under its old name. install.sh switches it off (never removes it) |
| Source | `https://github.com/g-vansh/vestor`, branch `lucent`, folder `vestoros/` (public) |
| Updates | branch `ota`, folder `vestoros/`: `update.json` plus the current and previous APK |
| Guide | `vestoros/README.md` (sections "Updates from the TV", "Video calls: WhatsApp on the TV", "What it changes, exactly", "Security", "Undo") |
| Control center | the TV serves it on port 8686 (HTTP, home network or Tailscale only) |
| Signing key | certificate SHA-256 `d6ee242165e4281248b0d1493e9f436143243d97614419dd73f3fe28a3772752`, Android's short signature `8e4a2ae1` |

**Versions for tonight**

| APK | Version | Code | SHA-256 | Where |
|---|---|---|---|---|
| `vestoros-6.apk` | 2.1.1 | 6 | `48c5db3ce04dde76a1d633fcd383bd66b99d98ccca5b411c552c8e3f5b71561a` | `ota` branch. **Install this one over ADB** |
| `vestoros-7.apk` | 2.1.2 | 7 | `d08cbd4a71fbd73a43c9145fdef9f9e8dd144414bb6e12f3ab81015173ca7b04` | `ota` branch. **The TV installs this one by itself** (the update test) |

2.1.2 differs from 2.1.1 only in its version and its "what changed" line, so the update test tests
the update path and nothing else. Both log every update step and every call audio decision to
logcat under the tag `Vestor`.

**Secrets the owner has:** `signing-key-keep-private.zip`, sent in the Claude app chat. It holds
`release.keystore`, `release.keystore.pass` and `claude-key.txt` (the Claude API key for Ask
Vestor). `vestoros.zip` from the same chat holds 2.1 (code 5), a fallback if GitHub is unreachable.

---

## 1. Ground rules

1. **Secrets.** Never print, paste, commit or upload the Claude key, the keystore or its password.
   Hand them to scripts as file paths only. They live in `~/.vestor/` (folder mode 700, files 600).
   Never `cat` them. install.sh shows the key's last four characters, which is fine.
2. **Reversible only.** `./install.sh --undo` gives HOME back to Amazon at any time. Never factory
   reset, never uninstall Lucent or Vestor, never `pm clear` an app, and never change a Fire TV
   setting a step here does not name, without the owner's yes.
3. **ADB** stays on while you work. At the end, ask the owner, then `./install.sh --adb-off`.
4. **Owner's standing rules:** research before guessing, everything reversible, secure by default,
   ask few questions, no semicolons or em dashes in anything the owner reads, no other companies'
   logos in screenshots you share, and never place a trade in any brokerage (if a brokerage tool is
   connected to this session, do not touch it).
5. **Code changes** follow Appendix B. Anything new on the TV needs a new version code.
6. **The log.** At the end, a dated entry in `docs/BUILD_LOG.md` on branch `lucent` (the template
   is at the bottom of that file), committed and pushed. Results only, never secret values.

---

## 2. Set up the Mac (about 5 minutes)

```bash
brew install android-platform-tools imagesnap
git clone --branch lucent https://github.com/g-vansh/vestor.git ~/vestor 2>/dev/null || git -C ~/vestor pull --ff-only
cd ~/vestor/vestoros && git log --oneline -1
mkdir -p ~/vestor-tv/shots
```

If the owner already has a checkout, use it, after `git switch lucent && git pull --ff-only`.

**The key zip.** Ask the owner to download `signing-key-keep-private.zip` from the Claude app chat
to this Mac and tell you where it is. Then:

```bash
mkdir -p ~/.vestor && chmod 700 ~/.vestor
unzip -o "<path>/signing-key-keep-private.zip" -d ~/.vestor
chmod 600 ~/.vestor/*
ls -l ~/.vestor        # release.keystore  release.keystore.pass  claude-key.txt, all -rw-------
```

**The camera.** Point the laptop so the whole TV is in frame, then:

```bash
scripts/tv.sh cam test     # prints the photo's path. Open it (Read) and look
```

The first time, macOS asks to let the terminal app (Terminal, iTerm, VS Code or Claude, whichever
runs this session) use the camera. If there is no photo or it is black, ask the owner to allow it
in System Settings, Privacy & Security, Camera, then restart this session's app.

---

## 3. Eyes and hands: `scripts/tv.sh`

| Command | Use |
|---|---|
| `scripts/tv.sh shot [label]` | The Fire TV's own picture, pixel exact. Use after every step. Video under DRM shows black. It cannot see the Samsung's own menus |
| `scripts/tv.sh cam [label]` | A camera photo: the Samsung's own screens and prompts, the TV's input and power, anything before ADB works |
| `scripts/tv.sh key UP LEFT:6 OK` | Remote keys: `UP DOWN LEFT RIGHT OK BACK HOME MENU PLAY REW FF WAKE SLEEP`, `NAME:count`, or any `KEYCODE_` name |
| `scripts/tv.sh text "hello"` | Type into the focused field (letters, digits and `. , : / @ + -`) |
| `scripts/tv.sh ui` | Buttons on Fire OS's own screens (installer, permission prompts, settings) and which one has focus. Vestor draws its screens itself, so use `shot` for those |
| `scripts/tv.sh front` | Which app and window are in front |
| `scripts/tv.sh log` | Follow Vestor's log live (run it in the background) |
| `scripts/tv.sh logdump` | The last Vestor lines and any crash, once |

Set `export ADB_SERIAL=<tv-ip>:5555` once connected, so every command reaches the TV even if
another device appears.

**Vestor's screens, for navigation:** the home screen is a clock, today and an info bar along the
bottom. **Up** reaches the top row: Ask Vestor, Video Calls, Fire TV Home, Mirror a Screen, Phone
Control, Sound, Settings (left to right, focus starts on Settings). **Down** brings up the apps.
**Menu** opens Settings from anywhere on the home screen. Always confirm focus with a screenshot
before pressing OK.

---

## 4. Connect ADB (needs the owner for one or two presses)

1. **The TV's address.** Ask the owner, or take them to Settings, My Fire TV, About, Network and
   read it with the camera. To search instead: `ipconfig getifaddr en0` gives the Mac's address,
   then `brew install nmap && nmap -p 5555 --open <first three parts>.0/24`.
2. **ADB on?** Try `adb connect <ip>:5555`.
   - `connection refused`: ADB is off. The owner, with the remote: Settings, My Fire TV, About,
     press OK on "Fire TV Stick 4K" seven times, Back, Developer Options, ADB Debugging, On.
     Then connect again.
   - The TV shows "Allow USB debugging?" (check with `cam`): the owner ticks "Always allow from
     this computer" and presses OK. `adb devices` must then say `<ip>:5555 device`.
   - `unauthorized`: the prompt is still waiting on the TV. `failed to connect` or a timeout: the
     Mac and the TV are on different networks, or the Wi-Fi keeps devices from reaching each other
     (common on building and university networks). Ask the owner which network each is on.
     Section 11 (Tailscale) gets around it.
3. **Facts check and a snapshot of before:**

```bash
export ADB_SERIAL=<ip>:5555
adb shell getprop ro.product.model          # AFTKM
adb shell getprop ro.build.version.name     # Fire OS 8.x
adb shell getprop ro.build.version.sdk      # 30
adb shell dumpsys webviewupdate | grep -iE "current webview|fallback"   # WhatsApp needs Chromium 100 or newer
adb shell pm list packages | grep -E "vestor|lucent|dumbtv|projengmenu|tailscale"
{ date; adb shell settings get secure enabled_accessibility_services
  adb shell dumpsys package tv.vestor.home | grep -E "versionCode|versionName"
  adb shell dumpsys package tv.lucent.home | grep -E "versionCode|versionName"; } > ~/vestor-tv/before.txt 2>&1
scripts/tv.sh shot before
```

---

## 5. Install 2.1.1 over ADB

```bash
cd ~/vestor/vestoros
curl -fL -o ~/vestor-tv/vestoros-6.apk https://raw.githubusercontent.com/g-vansh/vestor/ota/vestoros/vestoros-6.apk
shasum -a 256 ~/vestor-tv/vestoros-6.apk   # must equal 48c5db3ce04dde76a1d633fcd383bd66b99d98ccca5b411c552c8e3f5b71561a
APK=$HOME/vestor-tv/vestoros-6.apk VESTOR_CLAUDE_KEY=$HOME/.vestor/claude-key.txt ./install.sh
```

Stop if the checksum differs. If the download answers 404, the branch has moved on: use the
`vestoros.zip` from the chat (2.1, code 5, `APK=<unzipped>/vestoros/vestoros.apk`). The update test
in section 6 then goes from 5 to 7, which works the same way.

**Expected output, in this order:** the device line, `installing ... (SHA-256 48c5db3c...)`, three
`granted:` lines (HOME service self-repair, Now Playing, updates from the TV), `Claude key saved
on the TV (it ends in ...)`, `switched off Lucent` (only if Lucent was on), `done: press HOME`, the
phone control address, and a reminder to turn ADB off. Run this way, it leaves ADB on, which you
want for now. A `note:` line instead of a `granted:` line is not fatal: copy it into your notes.
If the Claude key line says `error`, open Vestor once (`key HOME`) and run
`./install.sh --claude-key ~/.vestor/claude-key.txt`.

**Check:**

```bash
adb shell dumpsys package tv.vestor.home | grep -E "versionCode|versionName"   # versionCode=6 ... versionName=2.1.1
adb shell appops get tv.vestor.home REQUEST_INSTALL_PACKAGES                    # REQUEST_INSTALL_PACKAGES: allow
adb shell settings get secure enabled_accessibility_services                    # includes tv.vestor.home/tv.vestor.home.Guard
adb shell dumpsys package tv.vestor.home | grep -E "WRITE_SECURE_SETTINGS|BLUETOOTH|REQUEST_INSTALL_PACKAGES" | head
scripts/tv.sh key HOME && sleep 2 && scripts/tv.sh shot home
```

The screenshot should be Vestor's home screen. Amazon's home for a moment before Vestor is
normal. If HOME stays on Amazon's home, see Appendix A.

---

## 6. The update test: the TV updates itself from 2.1.1 to 2.1.2

Vestor looks for an update three minutes after it starts and every six hours after, so it should
find 2.1.2 on its own. Start `scripts/tv.sh log` in the background now.

1. **Expected log, in order:**
   - `update: checking`
   - `update: installed code 6, offer code 7`
   - `update: downloading`
   - `update: ready 2.1.2`

   On the TV: a notice "VestorOS 2.1.2 is ready, Menu, then Updates, installs it".
2. **To skip the wait,** or once it is ready: `scripts/tv.sh key MENU`, then `DOWN` until the
   "Updates" row has focus. It is second to last, above "Vestor 2.1.1, Remote Help". Take a
   screenshot every few presses. Its text tells the state: "Check Now" or "Up to Date, Check
   Now", "Checking", "Downloading", "VestorOS 2.1.2 Is Ready, Install". Press `OK`: on a check
   row it checks (at most once a minute), and on the ready row it installs.
3. **Installing:** a notice "Installing VestorOS 2.1.2" shows the release's line, then Fire OS's
   own confirmation appears. Run `scripts/tv.sh ui` to find the Install button and the focused
   button. Move focus with `LEFT`/`RIGHT`/`DOWN` until Install is focused, check with `ui` again,
   then `OK`. Or ask the owner to press Install with the remote. The log shows
   `update: installer status -1` (the confirmation) before it.
4. **Expected:** Vestor closes, Fire OS installs, and Vestor comes back to the front by itself
   within a few seconds.
5. **Check:**

```bash
adb shell dumpsys package tv.vestor.home | grep -E "versionCode|versionName"   # 7, 2.1.2
scripts/tv.sh front                                                              # tv.vestor.home/.Home
adb shell appops get tv.vestor.home REQUEST_INSTALL_PACKAGES                    # still allow
adb shell settings get secure enabled_accessibility_services                    # still has the Guard
scripts/tv.sh shot after-update
```

Then open Settings again: the Updates row should say "Up to Date, Check Now". Settings the owner
had chosen, paired phones and the Claude key must all still be there.

**If it goes wrong** (the log line tells you which):

| Log or screen | Meaning and fix |
|---|---|
| `update: no update.json` | The TV could not reach GitHub, or Vestor's pacing held the request. `adb shell ping -c1 raw.githubusercontent.com`, wait 60 s, Check Now again |
| `download failed or its size or SHA-256 did not match` | Retry once. Right after a publish, GitHub may serve the old update.json for up to five minutes |
| `refused, not signed by this app's key` | The installed Vestor and the update have different keys. Compare `adb shell dumpsys package tv.vestor.home \| grep -A1 signatures` with `8e4a2ae1` |
| Row says "Allow Installs for Vestor" | `adb shell appops set tv.vestor.home REQUEST_INSTALL_PACKAGES allow` |
| `installer status 3` | Cancel was pressed. Choose Updates again |
| `installer status 1 ...` or 4 to 7 | Fire OS refused. The rest of the line is its reason. Copy it into your notes |
| `the confirmation is not a system screen` | Vestor did not open the confirmation because the installer did not look like Fire OS's own. Copy the logged intent into your notes |
| Vestor does not come back after installing | Press HOME (the Guard takes it to Vestor). Note it. `adb logcat -d \| grep -iE "PACKAGE_REPLACED\|tv.vestor"` |

Last resort, which also proves the APK itself is fine: download `vestoros-7.apk` from the `ota`
branch, check its SHA-256 (`d08cbd4a...`), then `adb install -r ~/vestor-tv/vestoros-7.apk`.

---

## 7. Ask Vestor and the phone control center (quick)

- **Ask Vestor:** top row, Ask Vestor, OK. Ask "what's the weather" with the remote's keyboard
  or `tv.sh text`. An answer should appear within seconds. The Claude key went on in section 5.
- **Phone control:** top row, Phone Control, OK shows a code. The owner scans it with the iPhone
  camera, then presses OK on the TV when the three digits match. On the phone, Settings, About
  should show the update state ("Up to date."), and Settings, Video calls has the voice setting.

---

## 8. Video Calls (WhatsApp Web on the Fire TV)

1. **Open:** from Vestor's home, `scripts/tv.sh key UP LEFT:6 RIGHT`, then `shot`: focus should
   be on Video Calls (the camera icon, second on the top row). Then `key OK`.
2. **Expected:** WhatsApp Web's sign-in code, full screen, and a hint line at the bottom. A page
   saying "use a computer" or "WhatsApp works with Google Chrome 100+" means the Fire TV's web
   engine is too old or the page changed. Copy the WebView version from section 4 into your notes.
3. **Sign in:** the owner opens WhatsApp on the phone, then Settings, Linked devices, Link a device,
   and scans the TV.
4. **Try the remote:** the arrows move a pointer (faster while held), OK clicks, pushing past the
   top or bottom edge scrolls, and Rewind and Fast Forward scroll a page. Open a chat, click the message box
   (the Fire TV keyboard should open), type with `tv.sh text`. Back closes WhatsApp's panels,
   Back twice leaves. Note anything that feels wrong: a pointer too slow or fast, clicks that
   miss, text the keyboard will not send.
5. **Menu inside Video Calls** has: Check the AirPods and microphones, the voice setting, Check
   for a webcam on the Fire TV, Check for a webcam on the Samsung, Reload WhatsApp, Sign out,
   Leave.

---

## 9. AirPods (tonight's big open question: does Fire OS offer their microphone?)

Bluetooth headphones play sound over the stereo profile (A2DP) and offer their microphone only
over the hands-free profile (HFP). Whether Fire OS on this stick offers the hands-free profile
decides whether the AirPods can be the call's microphone. Vestor handles both answers. Tonight
you find out which one this TV has.

1. **Pair:** the owner puts the AirPods in pairing mode. In the case with the lid open, they hold
   the button on the back until the light flashes white. On AirPods 4, they double-tap the front
   of the case instead. On the TV: Settings, Controllers & Bluetooth Devices, Other Bluetooth
   Devices, Add Bluetooth Devices, then the AirPods. Vestor's own Settings, Bluetooth Headphones
   opens the same screen.
2. **The answer, without a call:**

```bash
adb shell dumpsys media.audio_policy | grep -oE "AUDIO_DEVICE_(IN|OUT)_BLUETOOTH_[A-Z0-9_]+" | sort -u
adb shell dumpsys bluetooth_manager | grep -iE "HeadsetService|Headset|A2dpService|A2dp" | head -20
```

   - `AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET` in the list: Fire OS offers the AirPods'
     microphone. Calls can use them both ways, at phone-call sound quality.
   - Only `..._OUT_BLUETOOTH_A2DP...`: listening only. Call sound plays in the AirPods, and the
     voice has to come from a webcam's microphone.

3. **The same answer in Vestor:** Video Calls, Menu, Check the AirPods and microphones. Take a
   screenshot, and check `scripts/tv.sh logdump | grep "call audio: check"` (it prints
   `headsetMic=… offCall=… stereo=… usbMic=… airpods=…`).
4. Write the answer down for the owner and the log. It is the main thing tonight settles.

---

## 10. A test call, then the webcam

A call needs a second WhatsApp account (someone else's phone) and a microphone on the TV: the
AirPods if section 9 said so, or a webcam with a microphone on the Fire TV. With neither, skip the
call and say so.

1. Ask the owner who can take a test call. From WhatsApp on the TV: open the chat, then the phone
   icon (voice) or the camera icon (video).
2. Fire OS asks once whether VestorOS may record audio (and use the camera). Choose Allow, using
   `ui` then `OK`, or the owner does it.
3. **During the call:**

```bash
scripts/tv.sh logdump | grep "call audio"
adb shell dumpsys audio | grep -iE "mode|sco|communication|speakerphone|force" | head -20
```

   | Case | Expected log | The owner hears |
   |---|---|---|
   | AirPods with their microphone | `mic asked, headsetMic=true ... route=both`, `headset call link up, call mode on`, `call microphone open, route=both, mode=3, sco=true` | the caller in the AirPods, and the caller hears them |
   | AirPods for listening, webcam mic | `route=ears`, maybe `call mode off, so the call plays in stereo headphones` | the caller in the AirPods, and the caller hears the webcam mic |
   | No AirPods | `route=tv` | the call on the soundbar |

4. **Hang up:** the log shows `call audio: call over, audio put back`, and `adb shell dumpsys
   audio | grep -i "mode"` shows normal mode again.
5. **The voice setting:** Video Calls, Menu, "Talk through the webcam, keep AirPods for
   listening", then a second call: `route=ears`. Switch it back afterwards if the owner prefers
   the AirPods' microphone.
6. **If sound or voice is missing:** save `logdump` and the `dumpsys audio` lines from during the
   call, write down what the owner heard and what the caller heard, and try once with the voice
   setting on the webcam.

**The webcam** (if the owner has one):

- **On the Samsung's USB port:** Video Calls, Menu, Check for a webcam on the Samsung. With the
  Samsung remote connected in Vestor, the Samsung's browser opens by itself (take a camera
  photo). Otherwise the Fire TV shows an address for the owner to open in the Samsung's Internet
  app. The answer comes back to the Fire TV within a minute (`shot`). Expect "no camera", since
  Samsung added webcam support only to its 2021 and later TVs.
- **On the Fire TV:** this needs a micro-USB OTG cable that also takes the stick's power. Then
  Menu, Check for a webcam on the Fire TV, plus:

```bash
adb shell dumpsys media.camera | grep -iE "Number of camera devices|Camera [0-9]+ information" | head
adb shell dumpsys usb | grep -iE "manufacturer|product|class" | head -20
```

---

## 11. Tailscale on the Fire TV (optional)

**Recommendation for the owner:** not needed tonight while the laptop's `adb connect` works over
home Wi-Fi. Worth adding in any of three cases:

- The TV's Wi-Fi is a network the owner does not run, such as the building's or MIT's.
- Devices on the Wi-Fi cannot reach each other.
- The owner wants phone control and ADB debugging from away from home.

In the first case, VestorOS's README already says to set Phone Control Works On to Tailscale Only.
Tailscale cannot connect the cloud session to the TV. It only links the owner's own devices.

Costs:

- One more always-on service on a small stick.
- Updates by hand, because Tailscale is not in Amazon's Appstore and its sideloaded APK does not
  update itself.
- One more way into ADB while ADB is on, though only from the owner's own devices.

If the owner says yes:

```bash
v=$(curl -fsS https://pkgs.tailscale.com/stable/ | grep -oE 'tailscale-android-universal-[0-9.]+\.apk' | head -1)
curl -fL -o ~/vestor-tv/$v "https://pkgs.tailscale.com/stable/$v"
adb install -r ~/vestor-tv/$v
adb shell monkey -p com.tailscale.ipn -c android.intent.category.LEANBACK_LAUNCHER 1
```

On the TV, OK on the VPN connection request (`ui`), then Get Started, then Log in. A code appears:
the owner scans it with their phone and signs in. If the tailnet requires device approval, they
approve it in the Tailscale admin page. Check:

```bash
adb shell ip -4 addr show tun0                  # a 100.x.y.z address
adb shell dumpsys package com.tailscale.ipn | grep versionName
```

From the Mac, with Tailscale on it too, `adb connect 100.x.y.z:5555` should work. In Vestor's
Settings, Phone Control Works On: keep "Home Wi-Fi and Tailscale", or "Tailscale Only" on a shared
network. Then test a restart: `adb reboot`, wait 90 seconds, reconnect over home Wi-Fi, and check
`tun0` again. If Tailscale did not come back by itself, tell the owner: after a power cut it needs
opening once.

---

## 12. Wrap up

1. Leave the TV on Vestor's home screen (`key HOME`, `shot`).
2. Ask the owner whether to turn ADB off now (`./install.sh --adb-off`). The README recommends
   off. They can turn it on again in Developer Options whenever a session like this one is needed.
3. Add the BUILD_LOG entry (`docs/BUILD_LOG.md`, branch `lucent`): installed versions, the
   update test result, the AirPods answer, call results, webcam results, Tailscale if done, and
   anything left open. Commit and push. Stage only that file, so a key file can never ride along.
4. Tell the owner, in a few lines: what works, what does not and why, and what they might still
   do (an OTG cable, a test call with someone else).

---

## Appendix A: Troubleshooting

| Symptom | Check and fix |
|---|---|
| HOME shows Amazon's home | `adb shell settings get secure enabled_accessibility_services` should include the Guard. Vestor's Settings, Vestor Opens on HOME should be Yes. Run `./install.sh` again |
| Vestor closes or crashes | `scripts/tv.sh logdump`, `adb logcat -d -b crash`. After three crashes in three minutes, HOME goes to Amazon's home by itself for a while: that is Vestor's safety net working |
| Screenshot is black | Video under DRM or the screensaver: `key WAKE HOME` |
| adb `offline` or `unauthorized` | `adb kill-server && adb connect <ip>:5555`, then the TV's prompt again |
| The camera shows the wrong picture | The Samsung is on another input. The owner switches inputs, or `key HOME` wakes CEC |
| WhatsApp shows "use a computer" | Reload from Video Calls' Menu. Note the WebView version. Fire OS updates WebView itself |
| No sound in the AirPods during a call | `dumpsys audio` mode and forced use, `logdump`. Try the voice setting on the webcam |
| A system prompt will not take OK | `ui` to see focus. Some prompts start on Cancel. Or ask the owner |

---

## Appendix B: Changing code and shipping it

Only if something is broken and the fix is clear. Tell the owner first.

**Tools (once):**

```bash
brew install --cask temurin@21 android-commandlinetools
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
sdkmanager --sdk_root="$ANDROID_HOME" "build-tools;35.0.0" "platforms;android-30"
export BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0" FRAMEWORK_JAR="$ANDROID_HOME/platforms/android-30/android.jar"
export VESTOR_KEYSTORE="$HOME/.vestor/release.keystore"   # its password comes from release.keystore.pass beside it
```

**Rules of the code:** Java 8 with no lambdas, no Gradle, no libraries, targetSdk and minSdk 28.
Comments and user-facing text follow the house style (plain words, no semicolons, no em dashes).
Match the surrounding code.

**Desk tests (no TV needed):**

```bash
cd ~/vestor/vestoros
out=$(mktemp -d)
{ grep -L '^import android' src/tv/vestor/home/*.java | grep -v '/Actions.java'; ls preview/tv/vestor/home/*.java preview/tests/*.java preview/tools/*.java; } > "$out.txt"
javac --release 8 -Xlint:-options -encoding UTF-8 -d "$out" @"$out.txt"
for t in CallingTest UpdateTest SecurityTest ClaudeTest Feeds; do printf '%s: ' $t; java -Djava.awt.headless=true -cp "$out" tv.vestor.home.$t | tail -1; done
```

**Build:** `./build.sh` writes `build/vestoros.apk`, signed. It refuses to make a new key.

**Onto this TV now:** `adb install -r build/vestoros.apk`. The same version code reinstalls fine
with `-r`. Settings and data stay.

**To every TV as an update:**
1. Raise `CODE` (and `VERSION`) in `src/tv/vestor/home/BuildInfo.java`. Codes 6 and 7 are taken,
   so the next one is 8, and a code is never reused.
2. Write the change in `res/values/strings.xml`, `update_notes`.
3. Commit and push to `lucent`.
4. Run `./publish.sh`. `--dry-run` first is wise. It checks the signer, the version and that no
   secret is inside, then replaces the `ota` branch (only that branch).

The TV finds the update within six hours, or at once with Settings, Updates, Check Now.

---

## Appendix C: Undo

- `./install.sh --undo`: HOME goes back to Amazon's home, and the three grants are taken back.
  Vestor stays installed.
- `./install.sh --remove`: the same, and Vestor is uninstalled with its notes and photos. Ask
  first.
- Lucent back on: `adb shell pm enable tv.lucent.home`.
- An older Vestor: `adb install -r -d ~/vestor-tv/vestoros-6.apk`.
- Tailscale off: `adb uninstall com.tailscale.ipn`.
