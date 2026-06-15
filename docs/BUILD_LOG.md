# BUILD LOG
Append a dated entry after each meaningful step: what you did, what worked, what changed.
Never record secret values — only that a secret was set.

---

## 2026-06-14 — Dry session plan (no hardware)

**Goal of this session:** every software/repo/config/script task achievable with NO LED
panels attached. STOP before any live panel hardware test (hard stop #5).

**Environment discovered (Mac, this session):**
- git 2.50.1, gh 2.83.2 **authenticated as `g-vansh`** (scopes: repo, workflow) → can create + push the repo autonomously.
- brew 5.1.11, python3 3.14.5, openssl 3.6.2.
- Installed this session: shellcheck 0.11.0, Raspberry Pi Imager (cask).
- **No SD card present** (only internal disk0/disk9 + disk images) → flashing is PENDING (hard stop #1, no card to risk).
- **Pi not reachable** (`flightwall.local` / `flightpi.local` both silent) → all Pi-side steps PENDING.
- Network to github.com OK.

**Plan (mirrors AGENTS.md §7 / master prompt execution plan):**
1. [done] Read AGENTS.md + scaffold + setup guide. Write this plan.
2. [done] Mac tooling: shellcheck + rpi-imager installed & verified.
3. Repo: cloned upstream `ColinWaddell/its-a-plane-python` → `flightwall`, renamed origin→`upstream`. Overlay scaffold. Create `g-vansh/flightwall`, push main.
4. Overlay scaffold files/scripts; verify tree.
5. GPL: keep upstream `LICENSE`; finalize `NOTICE.md` attribution.
6. Edit `display/__init__.py` → Triple Bonnet options (regular / FM6126A / parallel=1 test, 3 wall / HW pulsing on).
7. Write `config.py` (540 Memorial Drive geometry + Pi4 tuning, no secrets).
8. Secrets: gitignored `.env` + `config_secrets.py` placeholders; commit only `.example` templates; verify git status clean.
9. Flicker/quality tweaks in scripts; validate `bash -n` + shellcheck.
10. systemd service: venv python, not root, correct entry point.
11. SD flashing prep: `flash_sd.sh` + `custom.toml` (mark flashing PENDING — no card).
12. Pi-side steps → PENDING in OPEN_QUESTIONS.md with exact commands.
13. Final verification + report.

**Key decisions made this session (recorded, not stalled):**
- **Repo strategy:** clone-and-re-point (not fork). Keeps full upstream history + lets us `git fetch upstream` later. `origin` → `g-vansh/flightwall`, `upstream` → ColinWaddell.
- **Entry point:** upstream's real entry is `flight-tracker.py` (NOT `main.py`). The scaffold `services/flightwall.service` shipped `main.py` — **corrected to `flight-tracker.py`**. (Discrepancy with scaffold; the actual file on disk wins.)
- **Hostname:** master prompt says `flightwall` (`flightwall.local`); AGENTS.md + setup guide say `flightpi`. **Chose `flightwall`** (matches repo/project name, master prompt is the immediate instruction). Standardized scripts/docs/.env to `flightwall.local`. Owner can override — see OPEN_QUESTIONS.md.
- **Secrets in config.py:** `config.py` is committed and must stay secret-free, but `scenes/weather.py` does `from config import OPENWEATHER_API_KEY`. Resolved by reading keys from `os.environ` in `config.py` (`os.environ.get(...)`), populated by `.env` via the systemd `EnvironmentFile`. No gitignored-file import at module top (would crash the whole app if missing).
- **gpio_slowdown / brightness in display:** kept upstream's config-driven `options.gpio_slowdown = GPIO_SLOWDOWN` / `options.brightness = BRIGHTNESS` (config.py sets GPIO_SLOWDOWN=4, BRIGHTNESS=50) rather than hardcoding — resolves to the prompt's values while staying DRY.

**config.py symbol contract (from grepping the codebase):** required `WEATHER_LOCATION`,
`ZONE_HOME`, `LOCATION_HOME`, `BRIGHTNESS`, `GPIO_SLOWDOWN`, `HAT_PWM_ENABLED`; used-optional
`TEMPERATURE_UNITS`, `MIN_ALTITUDE`, `JOURNEY_CODE_SELECTED`, `JOURNEY_BLANK_FILLER`,
`OPENWEATHER_API_KEY`. (`RAINFALL_ENABLED`, `LOADING_LED_ENABLED/_GPIO_PIN` are guarded by
try/except in the code and intentionally left unset → disabled.)

---

## Log entries

### 2026-06-14 — Repo established
- Did: cloned upstream → `flightwall`; `git remote rename origin upstream`; installed shellcheck + rpi-imager; overlaid scaffold (AGENTS, README, NOTICE, .gitignore, .env.example, config_secrets.example, docs/, scripts/, services/).
- Verified: `git remote -v` shows `upstream` → ColinWaddell; `LICENSE` present (GPL-3.0, 35 KB); tree matches intended layout.
- Changed from brief: none structural. Scaffold `.gitignore` replaces upstream's (which ignored `config.py` — we commit `config.py`).
- Next: display edit, config.py, secrets, scripts, service, push.

### 2026-06-14 — Config, code edits, scripts, service (all dry)
- Did:
  - `display/__init__.py`: replaced single-bonnet options with Triple Bonnet active3
    (`hardware_mapping="regular"`, `panel_type="FM6126A"` added, `chain_length=1`/`parallel=1`
    for Phase 0 with wall values commented, `disable_hardware_pulsing=False`, `drop_privileges=True`).
  - `config.py`: 540 Memorial Drive geometry (ZONE_HOME/LOCATION_HOME), MIN_ALTITUDE=100,
    BRIGHTNESS=50, GPIO_SLOWDOWN=4, JOURNEY_CODE_SELECTED="BOS", weather Cambridge,MA,US;
    keys read from os.environ (no secrets committed).
  - Secrets: created gitignored `.env` + `config_secrets.py` (placeholders, chmod 600);
    committed only `.env.example` + `config_secrets.example.py`.
  - Scripts: rewrote `flash_sd.sh` (canonical Bookworm custom.toml, refuses internal disk,
    requires ERASE + filled toml); added `scripts/custom.toml.example`; `.gitignore` now
    blocks the filled `custom.toml`. `setup_pi.sh` already covers blacklist snd_bcm2835 +
    audio=off + isolcpus=3 (with .bak backups, single-line append).
  - `services/flightwall.service`: corrected entry point `main.py`→`flight-tracker.py`,
    added PYTHONUNBUFFERED; runs as `pi` (not root).
- Verified:
  - `python3 -m py_compile` clean on display/__init__.py + config.py; config symbol contract PASS.
  - display grep shows regular / FM6126A / parallel / drop_privileges.
  - `bash -n` + `shellcheck` clean on all 4 scripts.
  - `git check-ignore` confirms `.env` + `config_secrets.py` ignored; `git status` shows no secrets.
  - systemd unit structural check PASS (not root, flight-tracker.py present).
- Changed from brief: entry point `main.py`→`flight-tracker.py`; hostname `flightpi`→`flightwall` (repo-wide).
- Next: commit in logical groups, create `g-vansh/flightwall`, push main. Flashing + all Pi steps PENDING (no card, Pi offline).

### 2026-06-14 — Rebrand to "vestor" (owner code name)
- Did: owner set the project code name to **vestor**. Standardized the whole repo:
  - `git mv flight-tracker.py vestor-tracker.py`; `git mv services/flightwall.service
    services/vestor.service` (ExecStart + WorkingDirectory + EnvironmentFile →
    `/home/pi/vestor`, entry point `vestor-tracker.py`).
  - Hostname `flightwall` → `vestor` everywhere (`vestor.local`): scripts, AGENTS,
    RUNBOOK, OPEN_QUESTIONS, `.env`/`.env.example` PI_HOST, `custom.toml.example`.
  - On-Pi path `/home/pi/flightwall` → `/home/pi/vestor`; clone URL + repo refs
    `g-vansh/flightwall` → `g-vansh/vestor` (install_app.sh, install_service.sh, AGENTS).
  - Brand `FlightWall` → `Vestor` in README, NOTICE, config.py header, display comment.
  - Fixed stale `main.py` entry-point refs in AGENTS/RUNBOOK → `vestor-tracker.py`.
- Verified: `git grep -i flightwall` / `flight-tracker` → no tracked refs remain
  (except upstream artifact `assets/FlightTracker.service`, left untouched for
  attribution); `python3 -m py_compile` clean; `bash -n` clean on scripts.
- Owner inputs received this session: WiFi SSID/password, pi password = `vestor`
  (baked only into gitignored `scripts/custom.toml`, never committed).
- Changed from brief: code name flightwall → vestor (owner instruction); GitHub repo
  renamed to `g-vansh/vestor` (flagged in OPEN_QUESTIONS).
- Next: generate gitignored `custom.toml`, commit+push, flash SD (rpi-imager GUI —
  device confirmation needed), then owner powers on the Pi.

### 2026-06-14 — SD card flashed (rpi-imager GUI, screen-controlled)
- Did: flashed the microSD (in this Mac's built-in SDXC reader) with **Raspberry Pi
  Imager v2.0.8**, driven via screen control after the owner explicitly confirmed the
  target device AND authorized GUI automation ("yes i can confirm both").
  - Device = Raspberry Pi 4; OS = **Raspberry Pi OS Lite (64-bit)**; Storage =
    "Apple SDXC Reader Media" (verified earlier as 58.2 GB / `/Volumes/Untitled` —
    NOT the internal SSD; satisfies hard stop #1, 100% device certainty).
  - Applied settings via Imager's **built-in OS Customisation wizard** (not custom.toml):
    hostname `vestor`; locale Washington D.C. → tz America/New_York, keymap us;
    user `pi` / password `vestor`; Wi-Fi SSID `MIT` (password verified char-by-char via
    the reveal eye before writing); **SSH enabled, password authentication**; Raspberry
    Pi Connect left OFF (declined cloud sign-in — local SSH is sufficient).
  - Confirmed the ERASE warning named "Apple SDXC Reader Media" before clicking
    "I UNDERSTAND, ERASE AND WRITE". Imager v2.0.8 began the write without prompting for
    the macOS admin password.
- Changed from brief / deviations (recorded, not stalled):
  - **OS version: Bookworm → Trixie.** Imager no longer offers a Bookworm 64-bit Lite
    image; the current "Raspberry Pi OS Lite (64-bit)" is Debian **Trixie**-based. Chose
    Trixie Lite 64-bit. Implication: the hzeller `rgbmatrix` build + `gpio_slowdown=4`
    were tuned for Bookworm; Trixie ships a newer kernel/libgpiod, so re-confirm timing
    at the Phase 0 single-panel test.
  - **First-boot config method: Imager wizard, not `custom.toml`.** The settings were
    baked into the image by the wizard, so the gitignored `scripts/custom.toml` is now a
    redundant backup path rather than the active mechanism. No plaintext secrets land on
    the FAT boot partition this way.
- Verified: write initiated; "Writing in progress — do not disconnect the storage
  device" / "Unmounting drive…" observed. (Completion + eject pending.)
- Next: confirm "Write Successful", eject the card, owner inserts it in the Pi and powers
  on. Then SSH to `vestor.local` (pi/vestor). STOP before any live LED-panel test (#5).

### 2026-06-14 — Tailscale first-boot enrollment (reachability fix, coded)
- Context: the Pi booted fine but is **unreachable** over MIT's "MIT" SSID. That SSID
  is a Juniper Mist **per-user-PSK (BYOD/IoT)** network with **client isolation +
  blocked mDNS by design** — `vestor.local` never resolves, the Mac's ARP table shows
  only the gateway, and MIT publishes no DNS for the device. Mac SSH client itself
  verified healthy (OpenSSH 10.2, GitHub auth OK), so this is a network-fabric block,
  not an SSH problem. MIT SECURE (802.1X) rejected: storing a Kerberos master
  credential on a wall appliance is brittle + a credential-exposure risk, and likely
  wouldn't fix peer reachability anyway. Personal APs/routers are barred on MITnet.
- Decision: **Tailscale** overlay mesh. The Pi dials OUTBOUND (WireGuard / DERP
  relays) to the tailnet, sidestepping L2 isolation; opens no inbound ports; is not a
  personal AP → MITnet-policy-compliant. Keeps the Pi on the correct MIT PSK Wi-Fi.
  Doubles as the connectivity test (node appears in the Tailscale admin console).
- Did (all code, no hardware, no secrets committed):
  - `scripts/tailscale_bootstrap.sh` — **[PI] stage-2** oneshot. Idempotent +
    self-cleaning: installs Tailscale if missing, `tailscale up --hostname=vestor
    --ssh --accept-dns=false` using a key read from `/etc/vestor/tailscale.authkey`,
    reports the tailnet IP, then **shreds the key and `systemctl disable`s itself**.
  - `services/vestor-tailscale-bootstrap.service` — `Type=oneshot`,
    `After/Wants=network-online.target` (install + control-plane both need internet,
    which the early firstrun stage lacks). No `Restart=`; stays enabled so a failed
    run retries next boot, script self-disables on success.
  - `scripts/install_tailscale_firstboot.sh` — **[MAC]** stager. Run AFTER Imager's
    wizard flash, BEFORE eject. Copies the 3 payload files into `<bootfs>/vestor/`
    and **prepends a fully `|| true`-guarded block after firstrun.sh's shebang**
    (stage 1) that, on first boot, installs the script+unit+key into the rootfs and
    enables the oneshot. Self-guarded so it can NEVER abort the wizard's `set -e`
    Wi-Fi/SSH setup. Idempotent (marker-guarded). **Does NOT touch cmdline.txt**
    (hard stop #2 untouched).
  - `scripts/vestor-tailscale.auth.example` (committed) + gitignore for the real
    `vestor-tailscale.auth` / `*.authkey` (hard stop #3).
- Verified: `bash -n` + `shellcheck` clean on both scripts; `git check-ignore`
  confirms the real auth-key file is ignored; **dry-ran the stager against a synthetic
  bootfs/firstrun.sh** → block injected after shebang, result still valid bash, second
  run idempotent (1 marker), cmdline.txt line-count unchanged, key staged chmod 600.
- Two-stage rationale: Tailscale is *how* we'll get SSH, so it can't be installed
  *over* SSH — it must self-bootstrap from the card. Stage 1 (firstrun, no network)
  only drops a persistent unit; stage 2 (next boot, network up) does the networked
  install + enroll.
- Changed from brief: adds a VPN layer not in the original plan, forced by MIT client
  isolation. `ssh pi@vestor.local` (mDNS) is replaced by `ssh pi@vestor` over the
  tailnet (Tailscale SSH / MagicDNS).
- Next (owner-gated): owner creates a free Tailscale account + a **Reusable +
  Pre-approved** auth key → I re-flash (cached Trixie image, same settings) + run the
  stager + eject → owner powers on → `vestor` appears in the console → install
  Tailscale on the Mac → `ssh pi@vestor` → run setup/install scripts. STOP before any
  live LED-panel test (#5).

### 2026-06-14 — Tailscale armed on the existing card (no re-flash) + key received
- Owner provided the Tailscale auth key (Reusable). Stored ONLY in gitignored
  `scripts/vestor-tailscale.auth` (chmod 600); `git status` confirms untracked.
  Owner also installed + logged into Tailscale on the Mac.
- Decision: **skip the re-flash.** The card already boots and joins MIT Wi-Fi
  (proven), so instead of erasing a working install (and driving the Imager GUI
  again) I re-armed the Pi's own first-boot mechanism directly on the FAT `bootfs`
  partition. macOS can't write the ext4 rootfs, so a tiny on-Pi `firstrun.sh` is the
  bridge that copies the payload into the rootfs and enables the one-shot.
- Did (card in Mac's SD slot = `/dev/disk9`, boot partition `/Volumes/bootfs`;
  positively NOT the internal SSD = disk0/disk3, 500 GB APFS):
  - Backed up `cmdline.txt` → `cmdline.txt.bak` (hard stop #2).
  - Staged `bootfs/vestor/{tailscale_bootstrap.sh, vestor-tailscale-bootstrap.service,
    vestor-tailscale.auth}`.
  - Wrote `bootfs/firstrun.sh` (set +e, guaranteed `exit 0`; installs the stage-2
    one-shot + key into the rootfs, `systemctl enable`, shreds the FAT key copy, logs
    to `bootfs/vestor-firstboot.log`, then strips its own token + deletes itself).
  - Appended exactly one token to the single-line `cmdline.txt`:
    `systemd.run=/boot/firmware/firstrun.sh systemd.run_success_action=reboot
    systemd.unit=kernel-command-line.target`.
  - Captured the whole procedure as a reusable, shellcheck-clean script:
    `scripts/rearm_tailscale_existing_card.sh` (idempotent; verifies one line; restores
    backup if the edit ever produced >1 line).
- Verified: `firstrun.sh` passes `bash -n`; `cmdline.txt` 169→282 bytes, still ONE
  line, no trailing newline, `diff` shows ONLY the appended token, token present once;
  AppleDouble metadata cleaned (`dot_clean`); card ejected (`diskutil eject` →
  "disk9 ejected", device gone).
- Boot flow expected: power on → systemd runs firstrun (early, no net) → installs +
  enables `vestor-tailscale-bootstrap.service` → reboots → service waits for
  network-online → installs Tailscale + `tailscale up --hostname=vestor --ssh` →
  shreds key, self-disables. `vestor` then appears in the Tailscale console; reach via
  `ssh pi@vestor`. ETA a few minutes after power-on (Tailscale download dominates).
- Changed from brief: chose the no-reflash re-arm over the documented re-flash path
  (`install_tailscale_firstboot.sh`); both now exist in the repo.
- Next: owner powers on the Pi. STOP before any live LED-panel test (#5).

### 2026-06-14 — On-Pi provisioning over Tailscale (driver + app + service, NO panel)
- Owner confirmed "vestor is on tailscale now." Pi enrolled: `vestor` @ `100.91.127.127`
  (direct LAN path 10.31.134.31). SSH works over the tailnet via **Tailscale identity
  auth** — no Unix password needed (`ssh pi@100.91.127.127`, Trixie, Python 3.13.5).
- Did (all over SSH, as `pi`, never root; sudo via `echo vestor | sudo -S`):
  - git + clone `g-vansh/vestor` → `~/vestor`; clone hzeller upstream matrix →
    `~/rpi-rgb-led-matrix` (commit `41809e4`, 2026-06-07).
  - apt build toolchain: build-essential, python3-dev/venv, **cmake + ninja-build**,
    libcap2-bin, curl.
  - venv `~/vestor/env`; upgraded pip/wheel; Cython.
  - Built the `rgbmatrix` binding from the matrix **repo root** via scikit-build-core +
    CMake, then `pip install` of app deps with `rgbmatrix` filtered out. `import
    rgbmatrix` → **OK**.
  - `setcap cap_sys_nice=eip` on the resolved interpreter (`/usr/bin/python3.13`) for
    non-root RT scheduling (#4). Verified via `getcap`.
  - Scaffolded `.env` (chmod 600; weather/MBTA keys optional, default ""). Byte-compiled
    app sources; ran a **hardware-free import smoke test** (`import config; import
    display` — `BRIGHTNESS=50`, full scene/animator/rgbmatrix import graph resolves
    WITHOUT constructing `RGBMatrix`, which only happens in `Display.__init__`).
  - Flicker tweaks (root, with backups): `dtparam=audio=off` in config.txt; blacklist
    `snd_bcm2835`; appended `isolcpus=3` to cmdline.txt — verified still **ONE line**,
    no trailing newline (#2). Backups: `config.txt.vestorbak`, `cmdline.txt.vestorbak`.
  - Installed `vestor.service` + daemon-reload, then **left it `disabled` + `inactive`**
    — the panel must not start unsupervised (#5).
- **Key deviation (driver build):** modern hzeller upstream moved Python packaging to
  **scikit-build-core/CMake with a root `pyproject.toml`** — there is NO
  `bindings/python/setup.py` anymore, so the old `pip install bindings/python` fails.
  Correct path is `pip install <repo-root>`, which needs cmake + ninja. Its CMakeLists
  also compiles `shims/pillow.c` unconditionally, which `#include`s Pillow's private
  libImaging headers (`Imaging.h`, `ImPlatform.h`, `Mode.h`, `Arrow.h`,
  `ImagingUtils.h`) — NOT shipped in Pillow wheels. Fix: fetch those 5 headers (pinned
  Pillow 12.2.0) onto `CPATH` at build time only. The app never calls
  `SetImage(PILImage)` and the shim doesn't link libpillow, so this adds **no runtime
  Pillow dependency** (Pillow stays absent from requirements.txt).
- Reconciled the repo scripts to this proven process (shellcheck-clean):
  `setup_pi.sh` (drop the interactive Adafruit installer → apt cmake/ninja + clone
  upstream; newline-safe cmdline edit), `install_app.sh` (repo-root scikit-build-core
  build + CPATH Pillow headers + filtered requirements + correct ordering),
  `install_service.sh` (install only — no `enable --now`), and RUNBOOK steps 3–5.
- Verified: `rgbmatrix import OK`; app import graph OK; `getcap` shows cap_sys_nice;
  cmdline.txt = 1 line; `systemctl is-enabled vestor` = disabled, `is-active` = inactive.
- Changed from brief: adapted to upstream's scikit-build-core migration (cmake/ninja +
  Pillow-header workaround) — not anticipated in the brief, which assumed the classic
  `make` + `bindings/python` build.
- Next: **supervised Phase 0** — wire ONE panel (data→Port 1, power→PSU), `systemctl
  start vestor` (or run `vestor-tracker.py`) WHILE watching; tune rgb-sequence /
  row-address / gpio-slowdown on real hardware; only then `systemctl enable vestor`.

## (template)
### YYYY-MM-DD — <step>
- Did:
- Verified:
- Changed from brief:
- Next:
