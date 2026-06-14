# AGENTS.md — FlightWall Project

> **You are a Claude Code agent with full autonomy on this project.** Read this entire file before doing anything. It is the source of truth for the FlightWall build: a Raspberry Pi–driven RGB LED matrix that shows aircraft passing over Boston/Dorchester as seen from the owner's window, and will grow into a modular multi-scene dashboard (flights → MBTA → Bluebikes → weather → animations).
>
> The owner (GitHub `g-vansh`) has explicitly delegated this to you. **You may install software, edit configs, commit to `main`, SSH into the Pi, and persist secrets without asking.** The only things you must do carefully are listed in **§3 Autonomy Contract**. Default to acting, not asking.

---

## 0. Quick Start (what to do first)

1. Read this whole file, then read `docs/RUNBOOK.md` and `docs/HARDWARE.md` (you will create these — see §7).
2. Confirm your environment: are you on the Mac, or SSH'd into the Pi? Many steps are Mac-side (repo, flashing), many are Pi-side (install, hardware). Each step below is tagged **[MAC]**, **[PI]**, or **[EITHER]**.
3. Establish the repo (§4), then the secrets scaffold (§5), then walk the Runbook (§6) top to bottom.
4. Keep a running log at `docs/BUILD_LOG.md` — append a dated entry after each meaningful step (what you did, what worked, what you changed). This is how the owner follows along.
5. When you hit something genuinely ambiguous or destructive beyond §3's allowances, write the question into `docs/OPEN_QUESTIONS.md` and keep going on everything else.

---

## 1. Project Overview & Goals

**Vision.** A long, thin LED matrix mounted above the living-room window at 540 Memorial Drive, Cambridge MA. It primarily shows real-time details of aircraft visible from the window (callsign, route, aircraft type, altitude) — Logan (BOS) traffic over Dorchester/South Boston. When no plane is overhead it shows weather and, later, MBTA arrivals, Bluebikes availability, and ambient animations.

**Phased plan — this is the spine of the project:**

| Phase | Goal | Hardware | Status gate to advance |
|-------|------|----------|------------------------|
| **0** | Software fully installed + **ONE panel** lights up with live flights | 1 of 16 panels, Triple Bonnet, 1× 5V/60A PSU | One panel renders a flight + correct colors + ≥80 Hz refresh |
| **1** | **Full 16-panel wall** (3 chains of 6+5+5) | all 16 panels, 2× PSU, bus bars | Wall renders cleanly edge-to-edge, stable refresh |
| **2** | **Modular dashboard**: flights + weather + MBTA + Bluebikes + animations as rotating scenes | same | Scenes rotate; each is an independent, testable module |

**Design principle that governs every decision:** *flights work first, but the architecture must make adding a new scene (MBTA, Bluebikes, weather, animation) a clean drop-in* — a new module under `scenes/`, a new data client under `data/`, and a line in the scene scheduler. Do not hard-wire flight logic so deeply that scenes can't be added later. See §6 (architecture).

---

## 2. Hardware Inventory (ground truth — never re-derive)

- **Compute:** Raspberry Pi 4 Model B, 4GB. (Deliberately NOT a Pi 5 — the mature hzeller driver targets Pi 0–4; Pi 5's RP1 needs the alpha PioMatter path. Do not "upgrade" to Pi 5.)
- **Driver board:** Adafruit Triple RGB Matrix Bonnet, **product 6358** — "active3" pinout, 3 parallel HUB75 chains, **no onboard power management**, fully assembled (no soldering). Ports: 1, 2, 3. For 3 physically-adjacent panels, port 2 = center panel.
- **Panels:** 16× P5 HUB75, **64×32 px**, 320×160mm, **1/16 scan**, **FM6124D driver chip** (FM6126A family). Wall = 3 chains of **6 + 5 + 5**. Each panel ships with a data ribbon and a "1-for-2" power pigtail.
- **Power:** Mean Well **LRS-350-5** (5V/60A) supplies — **2 for the full wall**, 1 for Phase 0. AC connected via a pre-terminated fork-lug cord (plug-in). **Panels are powered directly from the PSU; the bonnet's logic is powered from the Pi's 5V GPIO rail** — you do NOT separately wire 5V to the bonnet for a single-panel test.
- **Storage:** 64GB Verbatim microSDXC (V10/U1/Class10, no A1/A2 — fine, slightly slower random IO; once configured, image a backup).
- **Flashing host:** the Mac, via its built-in SDXC slot + Raspberry Pi Imager.

**The three settings that matter most and are non-obvious (memorize these):**
1. `--led-gpio-mapping=regular` (NOT `adafruit-hat`/`adafruit-hat-pwm` — those are for the single bonnet/HAT). The Triple Bonnet is "active3" = `regular`.
2. `--led-parallel=3` for the wall (`1` for the single-panel test); `--led-chain=6` for the wall (`1` for the test).
3. `--led-panel-type=FM6126A` (the FM6124 chips need this init sequence or they show nothing/garbage).

---

## 3. Autonomy Contract

**You may do freely, without asking:**
- Create/clone/modify the repo, commit and push to `main`, create branches/PRs if you prefer.
- Install packages on the Mac (Homebrew, `gh`, etc.) and on the Pi (`apt`, `pip` in venvs).
- SSH into the Pi and run any setup, edit application configs, install and enable the systemd service.
- Create and populate secrets files (`.env`, `config_secrets.py`) on the Mac and Pi, persistently.
- Run the hzeller demos and the flight tracker; tune flags.

**Do carefully — back up first, verify target, and log it:**
- **Editing `/boot/firmware/cmdline.txt`** (for `isolcpus=3`): a malformed `cmdline.txt` can prevent boot. **Always `cp` it to `cmdline.txt.bak` first**, make a *minimal* single-line edit (it MUST stay one line, space-separated), and `cat` it back to verify before reboot. If the Pi fails to boot after, the fix is to re-mount the SD on the Mac and restore the `.bak`.
- **Editing `/boot/firmware/config.txt`** (`dtparam=audio=off`): back up first; this is lower risk than cmdline.txt.
- **Flashing the SD card [MAC]:** this **erases a physical disk**. Run `diskutil list`, identify the SD card by size (≈64GB) and name, and **triple-check the `/dev/diskN` target**. Never target the internal disk. Use `diskutil` to confirm before any `dd`/`rpi-imager` write. This is the single most dangerous step — get the device id right.
- **Anything that could brick the Pi or wipe data** beyond the above: back up, then proceed, and note it in `BUILD_LOG.md`.

**Never:**
- Commit secrets (API keys, WiFi password, password hashes) to git. They live only in gitignored files. (See §5.)
- Run the flight tracker as `root` long-term — use the `setcap` + `drop_privileges=True` path (§6).

When in doubt: prefer a reversible action, log it, and continue. Append blockers to `docs/OPEN_QUESTIONS.md` rather than stalling the whole build.

---

## 4. Git / GitHub Workflow

**Approach: clone upstream, re-point to a new `g-vansh` repo, keep upstream as a remote.** This preserves its-a-plane-python's history and lets you pull future upstream fixes, while making `g-vansh/flightwall` the source of truth.

**[MAC] Commands:**
```bash
# Prereqs
brew install gh git || true
gh auth status || gh auth login        # device flow; owner approves once

# Clone upstream, rename remotes
git clone https://github.com/ColinWaddell/its-a-plane-python.git flightwall
cd flightwall
git remote rename origin upstream       # keep upstream for future pulls

# Create the new repo under g-vansh and point origin at it
gh repo create g-vansh/flightwall --public --source=. --remote=origin --push
# (use --private instead of --public if the owner later prefers; GPL note below)

git branch -M main
git push -u origin main
```

**Branching:** committing directly to `main` is fine and preferred by the owner. Use clear conventional-ish commit messages: `feat:`, `fix:`, `chore:`, `docs:`, e.g. `feat(display): switch Triple Bonnet mapping to regular/parallel=3`. Group logical changes per commit so `BUILD_LOG.md` and `git log` tell the same story.

**Future upstream syncs:** `git fetch upstream && git merge upstream/main` (resolve conflicts in `display/` and `config.py`, which you will have modified).

**License obligation (GPL-3.0 — important, state plainly, not legal advice):** its-a-plane-python is **GPL-3.0**. Because this repo is derived from it, the repo **must remain GPL-3.0**: keep the existing `LICENSE` file, keep ColinWaddell's copyright notices, and document your modifications. Add a `NOTICE.md` crediting `ColinWaddell/its-a-plane-python` (GPL-3.0) as the upstream base and listing major changes (Triple Bonnet support, FM6126A panels, new scenes). If the repo is **public**, all GPL terms apply to anyone you distribute to; keeping it public and GPL-3.0 is the simplest compliant choice. Do **not** relicense or strip notices.

---

## 5. Secrets Management

**Goal:** the owner never re-enters keys; secrets never hit GitHub.

**Pattern:**
- `config_secrets.example.py` and `.env.example` — **committed**, with placeholder values and comments.
- `config_secrets.py` and `.env` — **gitignored**, real values, created by you on both Mac and Pi.
- `.gitignore` must contain: `.env`, `config_secrets.py`, `*.local`, and any image/backup files.

**What goes where:**
- **WiFi credentials** are NOT in `.env` — they're baked into the SD image at flash time via the Imager / `custom.toml` (§6b). They never need to live in the repo.
- **API keys** (`OPENWEATHER_API_KEY`, `MBTA_API_KEY`) go in `.env` on the Pi (and `.env` on the Mac for any local testing). Bluebikes (GBFS) and NWS need **no key**.
- **Pi connection details** (hostname `flightwall.local`, user `pi`) can live in a gitignored `.env` on the Mac for your SSH convenience.

**How the app reads them:** load `.env` at startup (e.g. `python-dotenv`) or `source` it in the systemd unit via `EnvironmentFile=`. `config.py` (committed, no secrets) imports from environment / `config_secrets.py` (gitignored). Keep the split clean: **structure in `config.py`, secrets in `.env`/`config_secrets.py`.**

**Persisting so the owner never re-enters:** store the populated `.env` on the Pi at `/home/pi/flightwall/.env` (root-readable, mode `600`) and keep a copy on the Mac at the repo root (also gitignored). Once written, they persist across reboots and redeploys. Record in `BUILD_LOG.md` *that* keys were set (never the values).

**Honest tradeoff:** these are plaintext-on-disk secrets on machines the owner controls — appropriate for a hobby device, not for shared/production systems. Mode `600` + gitignore is the right bar here. Do not over-engineer (no vault needed).

---

## 6. Repository Architecture

Start from its-a-plane-python's layout and evolve it toward modular scenes. Upstream already uses an **`Animator` keyframe pattern**: the display is composed from scene classes, each contributing `@Animator.KeyFrame.add(n)` methods that draw every n frames. Extend that, don't fight it.

**Target structure** (create missing dirs; keep upstream files working):
```
flightwall/
├── AGENTS.md                  # this file
├── LICENSE                    # GPL-3.0 (keep upstream)
├── NOTICE.md                  # attribution + change log
├── README.md                  # human overview (rewrite for this project)
├── .gitignore
├── .env.example               # committed template
├── .env                       # gitignored (real keys, Pi + Mac)
├── config.py                  # committed: geometry, ZONE_HOME, scene config (NO secrets)
├── config_secrets.example.py  # committed template
├── config_secrets.py          # gitignored
├── main.py / flight-tracker.py# entry point (upstream)
├── display/                   # matrix init + Animator (EDIT __init__.py — see below)
├── scenes/                    # one module per scene
│   ├── flight.py              # existing flight scenes (refactor from upstream)
│   ├── weather.py             # idle weather scene (upstream has temp; formalize)
│   ├── mbta.py                # FUTURE — Red Line predictions (place-cntsq/place-knncl)
│   ├── bluebikes.py           # FUTURE — GBFS station status
│   └── animations.py          # FUTURE — ambient idle animations
├── data/                      # data-source clients (no rendering)
│   ├── flights.py             # FlightRadarAPI wrapper (upstream overhead.py)
│   ├── weather_nws.py         # FUTURE — api.weather.gov
│   ├── mbta.py                # FUTURE — api-v3.mbta.com
│   └── bluebikes.py           # FUTURE — gbfs.bluebikes.com
├── scheduler.py               # FUTURE — chooses scene: plane overhead → flight; else rotate
├── scripts/                   # idempotent setup scripts (see §7)
│   ├── flash_sd.sh            # [MAC] flash + headless config
│   ├── setup_pi.sh            # [PI] OS deps + matrix driver + tweaks
│   ├── install_app.sh         # [PI] venv + its-a-plane + config edits
│   └── install_service.sh     # [PI] systemd unit
├── services/
│   └── flightwall.service     # systemd unit template
└── docs/
    ├── HARDWARE.md  RUNBOOK.md  BUILD_LOG.md  OPEN_QUESTIONS.md  TROUBLESHOOTING.md  ROADMAP.md
```

**The scene scheduler (Phase 2 abstraction, design it now even if minimal):** a single loop asks the active data sources what to show. Priority: *if a flight is overhead → flight scene*; *else rotate through enabled idle scenes (weather → MBTA → Bluebikes → animation)* on a timer. Each scene exposes a uniform interface (`has_content()`, `draw(canvas)`); each data client exposes `fetch()` with its own cache/refresh interval. Keep flight behavior identical to upstream for Phase 0; the scheduler just wraps it.

---

## 7. The Autonomous Runbook

Write each script into `scripts/`, make them **idempotent and re-runnable**, commit them, then execute. Tag every action. Append to `BUILD_LOG.md` as you go. The detailed, copy-paste version of all of this also lives in `docs/RUNBOOK.md` (create it from this section).

### 7a. [MAC] Repo + tooling
- Install `gh`, authenticate, clone+re-point (§4), write `.gitignore`, `.env.example`, `config_secrets.example.py`, `NOTICE.md`, rewrite `README.md`. Commit.

### 7b. [MAC] Flash the SD card (headless, scripted)
The one step touching physical media. Two routes — prefer the scriptable one, fall back to GUI.

- **Scriptable:** `scripts/flash_sd.sh` — uses `rpi-imager` CLI if available (`brew install --cask raspberry-pi-imager`; the binary supports a `--cli <image> <device>` mode), OR flashes the base **Raspberry Pi OS Lite (64-bit, Bookworm)** image then mounts the boot partition and writes a **`custom.toml`** (Bookworm's first-boot config) to preconfigure: hostname `flightwall`, user `pi` + password hash, **WiFi (SSID/PSK)**, **enable SSH**, locale `America/New_York`, WiFi country `US`. Generate the password hash with `openssl passwd -6`. **Before writing: `diskutil list`, confirm the ≈64GB SD device, and pass it explicitly** (see §3 guardrail). After write, `diskutil eject`.
- **GUI fallback:** if CLI flashing is unreliable, instruct the owner once through Raspberry Pi Imager's OS-customization (the only likely manual touch in the whole build) and document it in `BUILD_LOG.md`.

Insert SD into Pi, power on, wait, then from the Mac: `ssh pi@flightwall.local`.

### 7c. [PI] OS base + matrix driver — `scripts/setup_pi.sh`
```bash
sudo apt update && sudo apt full-upgrade -y
# matrix driver via Adafruit installer (select Adafruit RGB Matrix Bonnet + QUALITY)
curl https://raw.githubusercontent.com/adafruit/Raspberry-Pi-Installer-Scripts/main/rgb-matrix.sh > /tmp/rgb-matrix.sh
sudo bash /tmp/rgb-matrix.sh
# flicker/quality tweaks
echo "blacklist snd_bcm2835" | sudo tee /etc/modprobe.d/blacklist-rgb-matrix.conf
sudo update-initramfs -u
# config.txt: ensure dtparam=audio=off  (back up first)
sudo cp /boot/firmware/config.txt /boot/firmware/config.txt.bak
# cmdline.txt: append isolcpus=3 to the SINGLE line (back up first, verify, ONE line)
sudo cp /boot/firmware/cmdline.txt /boot/firmware/cmdline.txt.bak
# ... append " isolcpus=3" to the existing line, then `cat` to verify, then reboot
```
The installer is interactive; if running non-interactively, drive it carefully or perform the manual clone+make path and replicate its choices (Adafruit-Bonnet mapping, quality = disable sound). Verify with the demo (needs a panel — §7g).

### 7d. [PI] App install + the critical edits — `scripts/install_app.sh`
```bash
cd /home/pi && git clone https://github.com/g-vansh/flightwall.git || (cd flightwall && git pull)
cd /home/pi/flightwall
python3 -m venv env && source env/bin/activate
pip install -r requirements.txt
# install the matrix python binding INTO this venv:
( cd /home/pi/rpi-rgb-led-matrix/bindings/python && pip install . )
python3 --version    # note X.Y for setcap below
sudo setcap 'cap_sys_nice=eip' "$(readlink -f env/bin/python3)"
```
**Then the non-negotiable edits to `display/__init__.py`** (upstream hardcodes single-bonnet values). Set:
```python
options.hardware_mapping     = "regular"     # active-3 Triple Bonnet (NOT adafruit-hat)
options.rows                 = 32
options.cols                 = 64
options.chain_length         = 1             # 6 for full wall
options.parallel             = 1             # 3 for full wall
options.row_address_type     = 0             # 1/16 scan ABCD; confirm on hardware
options.panel_type           = "FM6126A"     # FM6124 family init — ADD if absent
options.multiplexing         = 0
options.pwm_bits             = 11            # drop to 7–9 for the full wall
options.pwm_lsb_nanoseconds  = 130
options.led_rgb_sequence     = "RGB"         # change to RBG/BGR if colors swap
options.gpio_slowdown        = 4             # Pi 4; try 5 if garbage
options.disable_hardware_pulsing = False     # active-3 supports HW PWM natively
options.drop_privileges      = True
```
Populate `config.py` for this location: `ZONE_HOME = {tl_y:42.400, tl_x:-71.120, br_y:42.280, br_x:-70.980}`, `LOCATION_HOME=[42.354,-71.107,0.005]`, `MIN_ALTITUDE=100`, `BRIGHTNESS=50`, `JOURNEY_CODE_SELECTED="BOS"`, weather location `Cambridge,MA,US`. Keep keys in `.env`. Commit all of this.

### 7e. [PI] systemd service — `scripts/install_service.sh`
Install `services/flightwall.service` to `/etc/systemd/system/`, `EnvironmentFile=/home/pi/flightwall/.env`, `WorkingDirectory=/home/pi/flightwall`, `ExecStart=/home/pi/flightwall/env/bin/python3 main.py`, `Restart=always`, run as `pi`. Then `daemon-reload`, `enable --now`, check `systemctl status` and `journalctl -u flightwall -f`. It will restart-loop harmlessly until a panel is attached — that's expected pre-hardware.

### 7f. [EITHER] Everything testable WITHOUT panels (do all of this now)
Flash, boot, SSH, all installs, all config edits, the service install, secrets, repo — **all dry-runnable**. The service running (even error-looping for lack of a panel) confirms the software path. Mark hardware-gated steps clearly.

### 7g. [PI] Phase 0 hardware test (when 1 panel + PSU are wired)
Panel data → Bonnet **Port 1**; panel power → PSU 5V terminals (its own pigtail); PSU AC via fork cord; Pi on its own USB-C. Then:
```bash
cd ~/rpi-rgb-led-matrix/examples-api-use
sudo ./demo --led-rows=32 --led-cols=64 --led-gpio-mapping=regular \
  --led-parallel=1 --led-chain=1 --led-panel-type=FM6126A \
  --led-row-addr-type=0 --led-slowdown-gpio=4 --led-show-refresh -D0
```
- No output → check `--led-panel-type=FM6126A` and that the PSU is on.
- Wrong colors → try `--led-rgb-sequence=RBG` (or BGR/GRB); record the winner into `display/__init__.py`.
- Scrambled rows → try `--led-row-addr-type=3` then `5`.
- Note the Hz (want ≥80–100). Then run the app (`main.py`) with `chain_length=1, parallel=1`.

### 7h. [PI] Scale to the full wall (Phase 1)
Set `chain_length=6`, `parallel=3`; wire 3 chains (6+5+5), Port 2 = center if relevant; add a pixel-mapper for the uneven chains; re-tune `slowdown-gpio` (4→5 if garbage); drop `pwm_bits` to 7–9 if refresh < ~100 Hz. Two PSUs + bus bars, inject power along the run.

---

## 8. Testing & Validation

- **After flash/boot:** `ssh pi@flightwall.local` succeeds; `hostnamectl` shows `flightwall`; internet via `ping -c1 github.com`.
- **After driver install:** `~/rpi-rgb-led-matrix/examples-api-use/demo` exists; runs (with panel) and prints `Hardware gpio mapping: regular`.
- **After app install:** `env/bin/python3 -c "import rgbmatrix"` succeeds inside the venv; `pip check` clean.
- **After config edits:** `grep` `display/__init__.py` shows `regular`, `FM6126A`, `disable_hardware_pulsing = False`.
- **After service install:** `systemctl is-enabled flightwall` = enabled; `journalctl -u flightwall` shows the loop (and, pre-panel, the expected "no matrix" error — acceptable).
- **Hardware test:** demo lights the panel, colors correct, refresh ≥80 Hz, app renders a flight when one is overhead.
- **Idempotency:** every `scripts/*.sh` can be re-run without harm — assert this by running twice.

---

## 9. Troubleshooting Playbook (also write to `docs/TROUBLESHOOTING.md`)

| Symptom | Cause | Fix |
|---|---|---|
| No output at all | FM6124 not initialized | `--led-panel-type=FM6126A` |
| Dim / reddish / odd | Power | The Pi can't power panels — confirm the 5V/60A PSU is on and wired |
| Garbage / random pixels | Pi 4 too fast | Raise `--led-slowdown-gpio` 4 → 5 |
| Colors swapped | RGB order | `--led-rgb-sequence` RBG/BGR/GRB until correct |
| Rows scrambled / interleaved | Wrong addressing | `--led-row-addr-type` 0 → 3 → 5 |
| Persistent flicker | Sound not disabled / HW pulse off | Confirm `snd_bcm2835` blacklisted + `dtparam=audio=off`; `disable_hardware_pulsing=False` |
| Pi won't boot after cmdline edit | Malformed `cmdline.txt` | Re-mount SD on Mac, restore `cmdline.txt.bak` |
| No flights ever | FlightRadarAPI upstream change | `pip install FlightRadarAPI -U`; or `git fetch upstream && merge` |
| Service won't start | Path/env/caps | Check `EnvironmentFile`, venv path, `setcap`, `journalctl -u flightwall` |
| Can't SSH | WiFi/SSH not baked in | Re-flash with correct `custom.toml`; verify SSID/country=US |

## 10. Roadmap / Future Scenes (also write to `docs/ROADMAP.md`)

- **Weather (no key option):** NWS `api.weather.gov/points/42.354,-71.107` → `properties.forecast` (needs a `User-Agent` header). OpenWeatherMap free key also supported by upstream.
- **MBTA:** `api-v3.mbta.com` (free key; 20 req/min anon, 1000 with key). `/predictions?filter[stop]=place-cntsq&filter[route]=Red` (also `place-knncl`). Reference: `dufus2506/MBTA-bus-train-stop-prediction-sign`, `TrevorSayre/led-matrix-mbta-signage`.
- **Bluebikes:** GBFS, no key — `gbfs.bluebikes.com/gbfs/gbfs.json` → `station_information.json` + `station_status.json`; pick stations nearest 42.354,−71.107 (One Memorial Drive + MIT-area Memorial Dr docks).
- **Local ADS-B (reliability upgrade):** RTL-SDR + `dump1090` → `localhost` `aircraft.json`; consume via `exxamalte/python-flightradar-client` `Dump1090AircraftsFeed`. Removes cloud rate-limit/breakage risk.
- **Multi-scene dashboard alternative:** `ChuckBuilds/LEDMatrix` natively supports the Triple Bonnet (`hardware_mapping=regular`, `parallel=3`, `gpio_slowdown=4`) with weather/clock/calendar/sports/stocks plugins. Decide later: extend its-a-plane with our own scenes, or migrate the dashboard role to ChuckBuilds and keep its-a-plane as the flight scene. **Never run two matrix processes against the hardware at once** — they share the GPIO driver and will conflict.

---

## 11. Definition of Done (Phase 0)
- New `g-vansh/flightwall` repo exists, builds from a clean clone, GPL-compliant, secrets gitignored.
- Pi flashed, headless, reachable at `flightwall.local`; driver + app + service installed; configs correct (`regular`/`FM6126A`/`parallel=1`).
- One panel lights up, colors correct, refresh ≥80 Hz, a real flight renders.
- `BUILD_LOG.md` tells the full story; `OPEN_QUESTIONS.md` holds anything you couldn't resolve.
- Owner has to do **nothing** except wire the panel and watch it work.
