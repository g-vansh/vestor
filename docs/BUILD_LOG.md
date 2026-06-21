# BUILD LOG
Append a dated entry after each meaningful step: what you did, what worked, what changed.
Never record secret values — only that a secret was set.

---

## 2026-06-21 — Panel-spec compatibility check + single-row data-routing decision

**Context:** owner shared the actual MUEN P5 listing + module-back photos (driver
marked `FM6124(Z)D`, connector `HUB75-D`) and locked the physical layout to **one
long 16-wide row**. Deep-researched whether every spec is compatible end-to-end.

**Compatibility — all PASS (4 web-research passes, primary sources):**
- 64×32 / 1/16 scan / **A–D address (no E)** / HUB75-D pinout (R1 G1 / B1 GND /
  R2 G2 / B2 GND / A B / C D / CLK LAT / OE GND) = the standard HUB75 the Adafruit
  bonnet drives. Plug-compatible.
- **FM6124(Z)D ≈ ICN2037/ICN2038S** — basic constant-current driver, **no init**.
  Confirms `panel_type=""`; `FM6126A`→`FM6127` stays a black-screen-only fallback.
  The alarming `--led-row-addr-type=3` / forced-`FM6126A` GitHub threads are all
  **128×64 1/32-scan "ABC"** panels — NOT our 64×32 1/16 A–D panel.
- Likely live tweaks: **R/B swap** (`--led-rgb-sequence RBG/BGR`, common on FM6124),
  slowdown 4→5 if flicker. "Epstar" = LED emitter die brand, not the driver.
- Power re-confirmed: 16 × ~4 A design ≈ 64 A @ BRIGHTNESS=50 on 2× LRS-350-5
  (120 A). All-white-full = 128 A > 120 A → keep brightness capped (already do).

**Decision — single-row data routing → 2 chains of 8, fed from CENTER.**
HUB75 ribbons must stay <50 cm; a 16-panel row is ~5 m and all 3 bonnet ports are
co-located, so the old **6+5+5** plan would need **1.9 m + 3.5 m** jump cables to
the starts of chains 2 & 3 → corruption/flicker. **New plan:** Pi+bonnet
center-mounted, `--led-parallel=2 --led-chain=8`; chain 0 → left (panels 8→1),
chain 1 → right (panels 9→16). Every ribbon short (included cables suffice, no
extensions). 16 = 2×8 exactly (no wasted slots vs 3×6's 18-slot canvas). Refresh
stays in the hundreds of Hz on a Pi 4. **Cost:** left 8 panels mount **180°** and
that half is flipped in software (`display/__init__.py`, hardware canvas 512×64) —
the classic snake. Bring up first-light with the trivial `parallel=1 chain=16`,
then move to 2×8.

- Changed: `docs/HARDWARE.md` wall topology line (6+5+5 → 2×8 center-feed);
  `docs/INVENTORY.md` §5 (layout LOCKED) + §6 (panel-spec compat table) + §2
  (ribbon extensions struck — not needed). Supersedes the 6+5+5
  `pixel_mapper_config` TODO from the 2026-06-14 geometry note.
- Next: Phase 0 still gated on ≥1 LRS-350-5 + AC cord (see INVENTORY §2).

## 2026-06-21 — AliExpress review/QC pass (3 research agents)

**Context:** owner shared the listing's reviews + seller back-photos. Question:
"anything that needs to change?" Ran 3 parallel research agents (power/voltage-drop,
hzeller color uniformity, incoming-QC). **Bottom line: compatibility (§6) and the
2×8 center-feed topology (§5) are UNCHANGED.** Reviews are sourcing/QC issues, not
spec mismatches. Three hardening actions added → `docs/INVENTORY.md` §7:

1. **CCA wire / voltage drop (§7.1).** Included pigtails are likely copper-clad
   aluminum (~1.55–1.6× Cu resistance, won't solder). AWG10 Cu @32 A/2.5 m = 0.52 V
   drop → far panels redshift (blue sags first; *looks* like a driver bug). Fix is
   geometry+copper: center each PSU behind its half, inject 5 V at ≥2 points/half
   (4 total), keep the two PSU positives ISOLATED, ~40 A fuse each, crimp-not-solder,
   AWG6 Cu trunks. Updated §2 harness rows accordingly.
2. **Shade variation (§7.2).** All hzeller knobs are GLOBAL — no per-panel LUT, no
   NovaStar `.cal` ingestion. Per-panel = DIY `MapColors()` patch (issues #222/#193).
   Mitigate: same batch, low brightness, dark/sparse content (our aesthetic), warm-up.
3. **Incoming QC (§7.3).** Test all 16 individually with `demo` -D3/-D4/-D5/-D11/-D0
   before mounting; dead FM6124 = 16-px dead column, dead row = horizontal line.
   Log 1–16, keep 2–3 spares, RMA with video for partial refund. If dark/garbled,
   try `--led-panel-type=FM6126A` before condemning.

- Changed: `docs/INVENTORY.md` (+§7, updated §2 trunk/fuse rows). No code touched.

## 2026-06-21 — Software audit vs. locked plan (post-topology / post-QC)

**Context:** owner asked "anything on software that needs to change?" after the
topology + review/QC research. Audited the live code against the locked decisions.

**Found + FIXED (`display/__init__.py`):** the code still hard-coded the **rejected
6+5+5 wall plan** in its comments — told a future session to set `parallel=3,
chain=6` and to "build a mapper for the uneven 6+5+5 chains." That contradicts the
LOCKED 2×8 center-feed decision and would cause the wrong wiring/config. Rewrote the
comments to the 2×8 center-feed plan: `parallel=2, chain=8`, compose 1024×32 →
512×64 canvas with the LEFT half rotated 180° **in our code, not pixel_mapper_config**.
Also lowered the `except`-fallback `BRIGHTNESS` default **100 → 50** (+ slowdown
1→4, HAT_PWM True→False) so a missing config can't over-draw the 120 A PSU budget.

**Confirmed already-correct (no change):** `config.py` `BRIGHTNESS=50`,
`GPIO_SLOWDOWN=4`, `panel_type=""`, `row_address_type=0` all match the verified
panel spec + power budget.

**Big note written down:** the on-Pi app is STILL the upstream single-panel (64×32)
flight tracker. The full multi-zone 1024×32 design (weather + planes + Bluebikes +
shuttle, dual °C/°F) lives **only in `sim/`** — porting it (the 512×64 snake
composition layer + new data sources) is the real Phase-1 software build. Captured
as the ⭐ prerequisite at the top of `ROADMAP.md`. Also added TROUBLESHOOTING rows
for voltage-drop redshift, shade variation, and dead-IC/row signatures.

- Changed: `display/__init__.py` (comments + brightness fallback), `docs/ROADMAP.md`
  (wall-composition layer), `docs/TROUBLESHOOTING.md` (+3 rows). No behavior change
  to the running single-panel app.

## 2026-06-21 — Mounting & frame research (3 agents)

**Context:** owner asked where the "magnets/screws" claim comes from, whether the
wall free-stands, and how to buy/build a frame. Ran 3 research agents (panel mount
hardware, DIY frame/sag, free-standing physics + commercial options). New
`docs/INVENTORY.md` §8.

- **Provenance:** "magnet screws" traces to the **MUEN listing's "Free Parts" image**
  (owner-supplied), not a datasheet. Generic P5 modules usually ship 4 M3 magnet
  screws but it's vendor-dependent (Adafruit-brand ships none since 2020). Panel
  backs have **M3-threaded corner holes** regardless → screw-mount always works.
  Flagged "confirm on unboxing."
- **Free-stand: NO.** 32:1 aspect ratio, ~6.3" base → tips/bows/twists. Wall-mount
  (above the window, as planned) is the right fit.
- **Buy vs build: BUILD.** No off-the-shelf 1-tall × 16-wide frame; cabinets are
  640×480 / 960×960 (too tall), custom 1-row = OEM, truss stands ≈ $2000. DIY ≈ $200.
- **Physics:** `sag=5WL³/384EI`; unsupported 5.1 m sags badly (4040 ≈ 32 mm) but
  **intermediate support every ~3–4 ft makes any profile flat**. **Magnets need a
  FERROUS surface** (won't stick to alu/wood) → either screw panels via M3 corners
  (recommended) or add a steel sheet.
- **Recommended:** 80/20 1530 backbone, panels bolted via T-nuts, lag-screwed to
  studs (~$210). **Renter variant:** plywood/2040 backbone on French cleats +
  TOGGLER SNAPTOGGLE toggle bolts (~$190, ~6 fillable holes). Depth budget ~30–35 mm;
  total weight ~12–15 kg; build/carry in 2–3 sections (floppy until mounted).
- Changed: `docs/INVENTORY.md` (+§8 mounting/frame, §2 🟡 row + §7.3 caveat). Docs only.

## 2026-06-21 — Vetted Hanson "hanpaneldistro" fused power-distribution board

**Context:** owner found the Hanson Electronics "Panel Power Distribution" board
(wiredwatts.com, $14.50) and asked if it's useful/compatible. Vetted via research
agent. **Verdict: YES — buy 4.** It's the recommended concrete part for the §7.1
injection points.

- **What it is:** passive **5 V-in → 4× fused (7 A) 5 V-out** PCB, bolts to a panel
  back (144 mm M3 holes), 30 A board rating. = bus-bar + fuses + mount in one.
- **Compatible:** fully — touches only power rails, never HUB75 data, so identical
  behavior on Pi 4 + Triple Bonnet + hzeller. Falcon/xLights heritage irrelevant.
- **Derate:** the "8 panels" claim is a low-current **P10** figure. Our P5 pulls
  ~4 A (bright) to ~8 A (white) → **1 panel per 7 A fuse = 4 panels/board = 4 boards**
  for 16 panels (2 per PSU = the 4 injection points). Keep BRIGHTNESS≤50.
- **Better than 1× 40 A PSU fuse:** per-panel fusing isolates a shorted panel; panel-
  back mount shortens high-current runs (helps §7.1 voltage drop).
- **Doesn't replace:** PSUs, AC cords, copper trunk PSU→board (~16 A → AWG10+), or the
  common-ground bond. Nothing for data.
- **Sourcing:** US-stocked (Alpharetta GA, ~2–4 days). "Origin: Australia" = mfg only.
- **Bench-verify before buying 4:** (1) measure panel rear M3 hole spacing ≈144 mm;
  (2) clamp-meter one all-white panel vs 7 A fuse; (3) board-vs-frame-bracket M3 hole
  contention. US alts: Falcon3DParts, QuinLED Dig-Octa, CZH-Labs, or bus bars + ATO.
- Changed: `docs/INVENTORY.md` §7.1 (+distro recommendation) + §2 🟠 row. Docs only.

## 2026-06-21 — MIT on-campus build resources (3 agents)

**Context:** owner is an MIT grad student; asked whether they can build/source/3D-print
the frame on campus cheap/free. Ran 3 research agents (makerspaces+access, materials+
funding, 3D printing+printable parts). New `docs/MIT_RESOURCES.md`.

- **Build (free for grads):** **Hobby Shop (N51-120)** = one-stop metal shop (waterjet,
  MIG/TIG, lathes/mills, bandsaws, 3D printers; free, 1-hr orientation via hayami@mit.edu).
  **MITERS (N52-115)** = free, 24/7, no paperwork (mill/lathe/MIG/3D). **Project Manus
  Metropolis 6C-006B / The Deep 37-072** = FDM/SLA via free M1T training. None stock
  80/20 — bring your own metal.
- **Materials (free→cheap):** **#1 = `reuse@mit.edu` + `reuse-ask@mit.edu`** (post a
  request now). Swapfest (3rd Sun Apr-Oct), Edgerton Project Lab 4-409 (free ply/screws),
  FX shelving, MITERS scrap. Off-campus: Home Depot/ReStore.
- **Funding:** Project Manus **Design-to-Making mini-grant up to $500** (best fit) +
  $100 MakerBucks after training. Sandbox ($1K-25K) is venture-oriented.
- **3D print** the interface parts (corner T-slot brackets ×60, cable raceways, Pi/bonnet
  tray, PSU brackets, end caps) in **ABS/PETG/ASA — not PLA**; buy the structural metal.
  Free FDM at Metropolis/MITERS. Reusable designs on Printables.
- Caveat noted in-doc: access rules/fees change yearly — verify on arrival.
- Changed: new `docs/MIT_RESOURCES.md`; `docs/INVENTORY.md` §8 cross-link. Docs only.

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

## 2026-06-14 — Pre-hardware validation (panels ~2 weeks out)

**Goal:** while the 16 P5 panels ship, (a) deeply confirm the whole hardware chain is
compatible and (b) run every software test possible WITHOUT panels, to maximize Phase 0
confidence. No live LED test (hard stop #5 still in force).

**Panels being bought (owner, confirmed from listing photos):** 16× MUEN P5 indoor,
320×160 mm, **64×32**, **1/16 scan**, HUB75, SMD2121, 5V, driver chip **FM6124D**.
Driven by the Adafruit **Triple** RGB Matrix Bonnet (PID **6358**, "active3"); powered by
**2× Mean Well LRS-350-5** (5V/60A each).

### Research (3 web-research passes, primary sources) — findings & what changed
- **Triple Bonnet 6358 → `hardware_mapping="regular"` (NOT adafruit-hat), `parallel=3`.**
  Adafruit's own guide: the active3 pinout = library `regular` mapping + `--led-parallel=3`
  (there is no mapping literally named "active3"). The `adafruit-hat`/`-pwm` mappings only
  support 1 parallel chain. **Confirms our committed config.** The Address-E line is
  irrelevant for 64×32 1/16-scan panels (A–D only); the bonnet's on-board E switch matters
  only for 64×64. `disable_hardware_pulsing=False` is correct (the no-hardware-pulse flag is
  debug-only and hurts stability; the right fix for the PWM/audio conflict is blacklisting
  `snd_bcm2835`, already done).
- **FM6124D needs NO init sequence → `panel_type` should start EMPTY.** FM6124 is a standard
  constant-current driver (like MBI5124/ICN2038S); only **FM6126A**/**FM6127** need a
  power-on init string. The `demo` binary's own `--help` lists only `FM6126A`/`FM6127` as
  supported panel-types — FM6124's absence is deliberate. Forcing `FM6126A` onto an FM6124
  sends register writes it interprets as pixel data → garbage, which masks a working panel.
  **CHANGE:** `display/__init__.py` `panel_type` `"FM6126A"` → `""`, with a comment demoting
  `FM6126A`→`FM6127` to fallback #1 (set only if the panel stays black, then remove again).
- **Power: 2× LRS-350-5 = 120 A / 600 W is comfortable for real content, NOT for all-white.**
  LRS-350-5 is 5V/60A/**300W** (derated; "350" is the series, not the 5V wattage). Per 64×32
  P5 panel: ~8A all-white max, ~4A Adafruit design figure, ~2A typical. 16 panels: all-white
  **128A/640W = 107% (over)**; design **64A/320W = 53%**; typical **32A/160W = 27%**. Verdict:
  fine for flight-tracker content, but cap brightness so a stray full-white frame can't exceed
  capacity — our `BRIGHTNESS=50` already ~halves worst case (→ ~64A, well within 120A). For
  unrestricted full white, add a 3rd LRS-350-5. **Injection:** parallel-inject 5V at every
  panel/pair (never daisy-chain power through HUB75), bus-bar topology, AWG 10–12 trunks /
  AWG 14–16 pigtails, keep the far panel ≥4.8V, common-ground all PSUs + the Pi/HAT.

### Tests run on the Pi (network/CPU only — no LED hardware)
- **Live flight-data fetch (FlightRadar24 / `utilities.overhead.Overhead`)** for the Cambridge
  ZONE_HOME: **PASS** — returned a live flight (e.g. `DAL888` A321 MCO→BOS @650ft). Benign
  noise: FR24 sets `Content-Encoding: gzip` on already-decompressed JSON; the API layer logs a
  warning and falls back to raw bytes (parses fine). Data items are **dicts**
  (`plane/origin/destination/vertical_speed/altitude/callsign`), which is what the scenes read.
- **End-to-end headless render via RGBMatrixEmulator (`raw` adapter):** **PASS.** Added a
  committed test harness `tools/emulator_capture.py` + a `tools/rgbmatrix_emulator_shim/`
  package that makes `import rgbmatrix` resolve to the emulator (inserted at `sys.path[0]`, no
  app source edits). Ran the REAL `display.Display()` and captured 12 PNG frames. Visual
  inspection confirmed: **clock** (`23:46`), **day/date** (`Sunday 14-6-2026`), and a full
  **live-flight render** (`DCA ▶ BOS`, `AAL3207`, `1/2`, `Airbus`) — i.e. clock/day/date/weather/
  overhead/journey/plane-details scenes, fonts, colours, and scene transitions all render
  correctly. (Emulator + its Pillow dep installed in the venv for testing only; not a runtime
  dependency of the app.)

### Bug found & fixed via the render test
- **`scenes/weather.py: grab_current_temperature` crashed the whole app** when a temperature
  fetch fails AND `TEMPERATURE_UNITS="imperial"`: it caught `WeatherError`, left `current_temp`
  = `None`, then ran `None * (9.0/5.0)` → `TypeError`. That `TypeError` is not a `WeatherError`,
  so it escaped the provider loop's `except WeatherError` and killed the app. This path triggers
  whenever the (optional) weather provider is unreachable — i.e. it would also crash on real
  hardware. **FIX:** guard the conversion (`if units == "imperial" and current_temp is not
  None:`) so failure returns `None` — the contract the caller already handles (`if
  self.current_temperature:`). Re-ran the render test: all 12 frames clean, exit 0.

### Geometry math (validated)
- **Phase 0:** 1×64×32 P5 = 2048 px; 64×5mm × 32×5mm = **320×160 mm** — matches the datasheet
  (P5 pitch internally consistent).
- **Phase 1 wall (6+5+5):** hzeller requires a **uniform** `chain_length`, so canvas =
  `chain_length=6 × parallel=3` = **384×96** logical px. 16 real panels = 32 768 lit px; the
  remainder **4096 px = exactly 2 panel-slots** are unused/dark (the missing 6th panel on the
  two 5-panel chains). Physical rows: 1920 / 1600 / 1600 mm wide × 480 mm tall. **Phase-1 TODO:**
  a `pixel_mapper_config` (e.g. `Rotate`/`Remap`) to fold the layout into the intended physical
  shape — exact mapper depends on the final wall arrangement (not yet locked); leave the two
  tail slots unrendered or remap them out.

### Phase 0 supervised test plan (ready; DO NOT run until owner present + panel wired)
hzeller `demo` binary already built at `~/rpi-rgb-led-matrix/examples-api-use/demo` (ARM64,
verified via `--help`; not yet run on hardware). With ONE panel (data→Port 1, power→PSU):
1. **Driver-level smoke test** (sudo; drops privs after init):
   `sudo ~/rpi-rgb-led-matrix/examples-api-use/demo -D0 --led-gpio-mapping=regular
   --led-rows=32 --led-cols=64 --led-chain=1 --led-parallel=1 --led-slowdown-gpio=4
   --led-show-refresh`  (NO `--led-panel-type` first; FM6124D is standard).
2. **Tuning ladder if output is wrong:** black → add `--led-panel-type=FM6126A` (then `FM6127`),
   then REMOVE again · wrong colours → cycle `--led-rgb-sequence` RBG/GRB/GBR/BRG/BGR ·
   split/doubled rows → `--led-multiplexing=1..3` · scrambled rows → `--led-row-addr-type=1/2` ·
   flicker → raise `--led-slowdown-gpio` to 5. Confirm refresh >100 Hz (>300 Hz if filmed).
3. **Then the app:** `sudo systemctl start vestor` + `journalctl -u vestor -f` while watching;
   port the winning rgb-sequence/row-addr/slowdown into `display/__init__.py`; only once correct
   `sudo systemctl enable vestor` for boot persistence.

- Did: 3 research passes; reconciled `panel_type`→`""`; live FR24 fetch test; headless
  end-to-end emulator render (new `tools/` harness+shim); found+fixed the weather `None`
  crash; validated geometry; verified the prebuilt `demo` binary; wrote the Phase 0 plan.
- Verified: FR24 returns live Cambridge flights; 12 emulator frames render clock/date/flight
  correctly; weather fix → clean exit 0; `demo --help` confirms FM6124 needs no panel-type.
- Changed from brief: discovered FM6124 (not FM6126A) is our chip → `panel_type` emptied;
  found a real upstream weather crash on the no-API-key path and fixed it.
- Next: **supervised Phase 0** once panels arrive — run the demo command above WHILE watching
  the single panel, tune, then port settings into the app and enable the service.

## 2026-06-15 — Design system + faithful simulator + live data clients
Big autonomous build answering the "design it mind-blowingly, simulate it, save the
docs" request. Two display targets: the **64×32** Phase-0 flights-only panel and the
full **1024×32** (16-panel, 201.6") wall as one ultra-wide ribbon.

- Did:
  - **`sim/`** — a zero-dependency browser simulator (`file://`, classic `<script>`,
    no build step) that renders the real HUB75 phosphor optically: linear framebuffer
    → gamma 2.2 LUT → round-dot mask (destination-in) → 2-pass additive bloom →
    scanline/seam/vignette. `led.js` (LEDMatrix + LEDRenderer, two authored bitmap
    fonts), `scenes.js` (split-flap Solari board, radar sweep, flight hero, weather
    **°C + °F**, Bluebikes, shuttle, clock, extras, status endcap), `data.js`
    (synthetic model + live overlay), `app.js` (3 wall modes: dashboard / flight
    takeover / marquee + the single panel). "Departure Board Noir" aesthetic
    (sodium-amber on control-black; Major Mono / Saira / IBM Plex Mono / Silkscreen).
  - **`docs/design/`** — DESIGN.md (full visual system), API_REFERENCE.md (every
    source with verified endpoints/ids/CORS), RESEARCH.md (projects surveyed + 32px
    patterns), README.md index.
  - **`tools/clients/`** — stdlib Python clients shaped to mirror the sim DataModel:
    `weather.py` (Open-Meteo, both units), `bluebikes.py` (GBFS, classic/e-bike
    split), `mit_shuttle.py` (Passio GTFS-rt + static-GTFS route fallback),
    `flights.py` (adsb.lol + hexdb enrichment, haversine, TTL cache), `_http.py`,
    `test_live.py` harness.
- Verified (all live, 2026-06-15):
  - `test_live.py` → **ALL PASS**: weather (22°C/72°F), bikes (18 classic/0 e-bike),
    shuttle (empty — Tech is daytime-only, correct), flights (hero on Logan approach).
  - Browser sim renders all 7 zones, both panel sizes, 3 modes — **no console
    errors**; LIVE toggle pulls real weather + bikes + **airplanes.live** flights
    (hero seen live: TAP216L BOS→LIS, JBU560 KIN→JFK, with hexdb route/city/operator
    enrichment). Synthetic shuttle math validated with an injected protobuf
    (`tech=[3,12]`, `tech_nw=[7]`).
- Changed from brief / corrected earlier research:
  - **Shuttle "Tech NW = 63319" was wrong** — that route doesn't exist. Grad Junction
    West (`180113`, confirmed) is served by `63220` "Tech Shuttle" and
    `56642`/`71674` "Tech Shuttle 2" (the 2nd is what `tech_nw` now carries).
  - **ADS-B CORS split:** adsb.lol/adsb.fi omit CORS → browser blocked; **airplanes.live**
    sends CORS with the identical schema, so the sim reads it while the Pi reads
    adsb.lol. Documented in API_REFERENCE.md.
- Next: wire the Python clients into the real `display/` render path (Phase-1) and
  fold the 384×96 electrical canvas → 1024×32 logical via `pixel_mapper_config`.

## 2026-06-16 — Simulator visual QA pass + label polish
Full "does everything fit and look good" review of the wall, driven by a crisp
debug-readout technique: read `wallM.buf` (linear-light Float32) directly, apply
gamma 2.2 per pixel, and blit each logical LED at integer scale into an injected
overlay canvas — legibility verification decoupled from the optical bloom/dot-mask
layer. Top-level `<script>` consts (`wallM`, `data`, `mode`, `WMO_TEXT`) are
reachable by name from the page eval context, which makes this possible.

- Verified (all fit, no overflow/collision):
  - Dashboard ribbon — all 7 zones: clock (`11:47` + date), weather (icon · °C · °F ·
    H/L · %RH · wind · feels-like), **flight hero** (radar sweep + "N TRK", split-flap
    callsign, type, route, city pair, FL/kt/vspeed, distance), bikes (classic vs
    e-bike split + docks), shuttle (TECH / TECH NW soonest-big + next-ups dim),
    extras (moon phase + %), status endcap (vertical VESTOR, ● LIVE, heartbeat
    sparkline).
  - Flight **takeover** mode — giant Solari callsign + arriving/route across the full
    1024px ribbon, fits.
  - **°C and °F both rendered** in the weather zone (the explicit brief requirement),
    confirmed at scale 8.
- Changed (polish):
  - Weather condition label ladder cleaned: WMO code 1 `MFAIR` → **`FAIR`** (read like
    a render glitch; now CLEAR→FAIR→PARTLY→CLOUDY reads as a clarity gradient).
    `sim/scenes.js` `WMO_TEXT`. (Python `weather.py` keeps the verbose "MOSTLY FAIR" —
    it isn't pixel-constrained.)
  - Wind unit `KH` → **`KPH`** (`sim/scenes.js`); one extra 3×5 glyph, still clears the
    left-column temps in the 128px zone.
  - Added `?v=2` cache-busting to the four local `<script>` tags in `sim/index.html`
    so reloads (dev + Pi kiosk) reliably pick up edited JS instead of heuristically
    caching it (server sends only `Last-Modified`, no `Cache-Control`).
- Next: same Phase-1 wiring as the 06-15 entry (Python clients → real `display/`
  render path; 384×96 electrical → 1024×32 logical via `pixel_mapper_config`).

## 2026-06-16 — Layout-bug fixes: circular radar, takeover hero spacing, bikes overlap
User-reported visual defects, all root-caused with the crisp debug-readout technique
(read `wallM.buf` linear → gamma → blit each LED at integer scale into an overlay
canvas) and fixed by arithmetic, then re-verified at high zoom.

- **Radar was an ellipse, not a circle.** `drawRadar` (`sim/scenes.js`) multiplied every
  vertical coordinate by `0.62` (a zone-fit squash) — on a square-pixel canvas that
  reads as a flattened oval. Fix: equal x/y radius (true circle); instead *shrink* the
  radius and re-center it higher so the full circle fits a 32px-tall zone. Denser
  angular sampling (`step = 0.9 / rr`) so the outer ring reads continuous; outer ring
  brightened. Call sites updated: flight-hero wide (`r=12, cx=x+16, cy=y+14`), takeover
  (`drawRadar(wallM, 24, 14, 12)`); dropped the "RADAR" word and centered "N TRK" under
  the disc so the taller circle doesn't collide with labels.
- **Takeover hero: yellow callsign overlapped/clipped by the lines below it.** Root cause
  = font-baseline metrics: 5×7 glyphs draw downward from `y`, so route at `y+26` spanned
  rows 26–32 and clipped (zone is rows 0–31), and the type/route lines crowded the
  scale-2 callsign. Fix (`sim/app.js drawTakeover`): callsign `y=0` (rows 0–13), type +
  ARRIVING/DEPARTING `y=16`, route `y=23` (rows 23–29). Mid-stats column similarly
  un-clipped: ALT label `y1`/value `y8`, SPD label `y17`/value `y24`; climb & distance
  aligned to those value rows. Verified `AAL519 / B738 DEPARTING / BOS-DCA` and
  `ALT 18,220 FT / SPD 306 KT / +CLIMB / 13.9` all clear with no row-31 clip.
- **Bikes: "10 DOCKS" overlapped "E-BIKE".** A 2-digit dock count widened the bottom-left
  `N DOCKS` label until it ran horizontally and vertically into the e-bike row. Fixed by
  a layout-flow change (`sim/scenes.js BluebikesScene`), not a nudge: the free-dock count
  now shares the station-name row, right-aligned (`textRight(x+w-1, y+7, …)`); classic
  row at `y+14`, e-bike row at `y+23`, occupancy bar on the bottom edge. Stress-tested at
  worst case (classic 18 / e-bike 14 / 12 docks) — every count 2-digit, zero collision.
- Also: corrected a stale source-card doc (`sim/app.js buildSources`) — MIT shuttle
  routes are `56642/71674` (Tech / Tech NW), not the old `63319`. Bumped script
  cache-bust `?v=2` → `?v=3` so reloads pick up the new JS.
- **API liveness (answering the user's question):** all four Python clients live-tested
  PASS. In the browser sim, SIM mode is synthetic; the LIVE toggle pulls real Open-Meteo
  weather + Bluebikes GBFS + airplanes.live flights (CORS-OK). The shuttle stays
  synthetic *in-browser only* (zero-dep JS can't decode GTFS-realtime protobuf); on the
  Pi the Python `mit_shuttle.py` client decodes it for real. **Tech Shuttle confirmed
  live**: route 63220 present, real arrivals `tech=[1,13,15]` min at Grad Junction West
  (180113); the NW line was simply not running that late evening (correctly shows `--`).
- Next: wire the Python clients into the real `display/` render path (Phase-1).

## 2026-06-18 — Multi-agency transit: + MIT SafeRide + BU "Hyatt" shuttle (live tracking)
User asked to add **MIT SafeRide** and the **BU shuttle from the Hyatt to BU**, using the
live-tracking features. Deep-researched both, found they need *two different* integrations,
and built both the Pi (Python) and the in-browser (sim) live paths.

- **Research — SafeRide is the *same* MIT Passio feed, not a new system.** Every MIT shuttle
  (Tech, Tech 2, SafeRide, Boston Daytime, Grocery) shares ONE GTFS-realtime TripUpdates
  feed (`passio3.com/mit/passioTransit/gtfs/realtime/tripUpdates`). Discovered the SafeRide
  trips live on `route_id 56140` "Saferide Campus" serving **`stop_id 3813` = "W98 @ Vassar
  St"** — at **~43 m this is the closest stop of all to the wall**. SafeRide is an
  **evening/overnight service (~6 pm–3 am)**, so by day the feed has no SafeRide trips and
  the field is correctly empty (the inverse of the daytime-only Tech lines).
- **Research — the BU "Hyatt" shuttle runs on TransLoc, not Passio.** Boston University's
  fleet ("the BUS") is on **TransLoc**; the named **"Hyatt"** route (`RouteID 5`) boards at
  **Amesbury St @ Vassar St** (`StopId 21`) — *right by the wall, beside the Hyatt Regency* —
  and crosses the Charles to BU's GSU. The TransLoc3 JSONPRelay backend
  (`bu.transloc.com/Services/JSONPRelay.svc`, public map key `8882812681`) returns plain
  **JSON with `Access-Control-Allow-Origin: *`** → the **simulator can fetch it live in the
  browser** (unlike the protobuf MIT feed). Endpoints: `GetRoutesForMapWithScheduleWithEncodedLine`,
  `GetStopArrivalTimes?routeIDs=5`, `GetMapVehiclePoints?routeIDs=5`. Live ETAs come from
  `Times[].Seconds` where `EstimateTime` is non-null (tracked vehicle vs schedule-only).
- **Did (Pi clients):**
  - `tools/clients/mit_shuttle.py` — extended to classify each trip into `tech`/`tech_nw`/
    `saferide` by **route name** (`route_long_name` starts "Saferide") rather than hard-coded
    ids, pull SafeRide from stop `3813`, and expose `shuttle.saferide[]`. `discover()` now
    reports `saferide_routes_live` + `w98_vassar_present`.
  - `tools/clients/bu_shuttle.py` — **new** TransLoc client: `fetch()` → `BuShuttleArrivals`
    (`hyatt[]` minutes ascending, `live_vehicles`), `.NET /Date()/` parser, `discover()`.
  - `tools/clients/test_live.py` — added `bu_hyatt` to the smoke test + EMPTY checks +
    summary lines for SafeRide and BU.
- **Did (simulator):**
  - `sim/led.js` — added `scarlet`/`scarletDim` to PAL (BU brand colour).
  - `sim/data.js` — `shuttle` now carries `saferide[]`; new `buShuttle{hyatt[],vehicles,…}`;
    synth ticker drives all four lines; **new `_liveBuHyatt()`** does the real CORS fetch to
    bu.transloc.com (same fields as the Python client) and is wired into `fetchLive()`.
  - `sim/scenes.js` — `ShuttleScene` redesigned from a 2-line MIT board into a **4-line
    multi-agency departures board**: header "SHUTTLES" + live dot, then TECH (green) / NW
    (cyan) / SAFE (purple) / BU (scarlet), each lead-bright + next-up-dim, "DUE" flash ≤1 min,
    "—" when idle.
  - `sim/app.js` — zone renamed "SHUTTLES" (src "Passio + TransLoc"); marquee now lists
    SafeRide + BU; added a **BU HYATT SHUTTLE** source card and corrected the MIT card.
  - `sim/index.html` — hero lede now says "MIT & BU shuttles"; cache-bust `?v=3` → `?v=5`.
- **Verified:** `python3 -m tools.clients.test_live` → **5/5 PASS**; BU live path proven on an
  active route (returns real `Seconds`→minutes ETAs; route 5 itself idle on a summer
  afternoon → correctly empty, same pattern as SafeRide-by-day / Tech-overnight). In the sim,
  reloaded at `?v=5` (no console errors), and re-ran the crisp debug-readout on the shuttle
  zone (x=704, w=128, scale 4–5): all four rows render cleanly — `TECH 16·4M / NW 24·9M /
  SAFE DUE / BU 18·3M` — no label↔ETA overlap and no row-31 clip (BU row at y+26 → rows
  26–30). Marquee segment renders "TECH … NW … SAFERIDE … BU HYATT …" with no exception.
- **Changed from brief:** SafeRide turned out to need *no* new integration (same Passio feed)
  — the work was discovery + name-based classification. BU needed a genuinely new vendor
  (TransLoc), but its CORS-open JSON means the *simulator* shows it **live**, while MIT
  shuttles stay synthetic in-browser (protobuf) and live on the Pi.
- **Next:** wire all Python clients (incl. `bu_shuttle.py`) into the real `display/` render
  path for Phase-1; SafeRide will populate the board after dusk on the Pi.

## 2026-06-18 — Carrier-led flight redesign + airline branding (sim)

**Prompt:** referenced `AxisNimble/TheFlightWall_OSS` — *"look how clean their airplane
tracking is… we have so much space to play around with… this also shows airline logos we
should do that too."* Goal: make the flight display dramatically cleaner/more spacious and
add airline branding.

- **Why the reference reads clean (diagnosis):** it identifies a flight by **brand colour +
  emblem + human "From: City"** in a single framed card — one flight, spacious, branded. Ours
  led with a cryptic callsign + cluttered radar/gauges. Fix = lead with the *carrier*, show
  *cities* not just codes, and spend the 1024 px on breathing room.
- **New `sim/airlines.js`:** `AIRLINE_DB` of ~27 carriers keyed by **ICAO operator code**
  (first 3 callsign chars → works for SIM *and* LIVE). LED-boosted brand colour + accent +
  `mark`. **Procedural** marks (shape math, no bitmaps/PNGs/CORS) so one emblem renders crisp
  at h=26 (takeover) and h=7 (panel): swept tail-fin (default, livery-tinted) + bespoke Delta
  widget / United globe / Southwest heart / Lufthansa ring / Air Canada roundel. Generic
  sodium-amber fin fallback for unknown operators. API `airlineFor` / `drawAirlineMark` /
  `markWidth`.
- **`sim/scenes.js` `FlightScene` rebuilt** — both `_drawCompact` (64 px Phase-0) and
  `_drawWide` (320 px dashboard hero) now open with the emblem + brand-coloured wordmark, show
  `ORIG CITY → DEST CITY`, then telemetry; wide adds a carrier-tinted altitude gauge.
- **`sim/app.js` `drawTakeover` rebuilt** as a **3-panel cinematic boarding pass**:
  IDENTITY (big emblem + 2× brand wordmark + callsign·type·reg) · JOURNEY (split-flap IATA
  codes + full city names that clack over on hero rotation, plane crossing the route on a
  `distance`/`_arriving`-derived track with a jet-wash trail) · STATUS (2× `FL###`/`###KT`,
  vertical alt gauge, V/S, "IN RANGE" traffic mini-board, corner radar scope).
- **`sim/led.js`:** added a `·` (middot) glyph to **both** FONT3x5 and FONT5x7 — the takeover
  separator was rendering as `?` (unknown-glyph fallback).
- **Verified (preview emulator, crisp gamma-corrected pixel readout @ scale 3–8):**
  takeover panel-by-panel across **two carriers** — *Southwest* (heart + gold "SOUTHWEST" +
  `SWA2299·B737·N8642E`, BWI/BALTIMORE→BOS/BOSTON, gold plane mid-route) and *United* (globe +
  blue "UNITED", SFO/SAN FRAN→BOS/BOSTON, blue plane, `ARRIVING · 12.0 MI`). Also confirmed
  the 320 px dashboard hero and 64 px Phase-0 panel render carrier-led and clean. No overlap,
  no row-31 clip; fixed one tight spacing on the IN-RANGE rows (`21 + i*6`). Cache-bust
  `?v=5` → `?v=8`.
- **Changed from brief:** chose **procedural pixel marks + brand colours baked into JS** over
  fetching AirHex/airline PNGs — at 7–26 px tall, real logos turn to anti-alias mush and the
  fetches are CORS-blocked; the *brand colour* is the strongest identifier on an LED wall
  anyway. Bespoke geometry only for the few truly iconic marks.
- **Next:** mirror `airlines.py` brand table + the carrier-led layout into the Pi's Python
  `display/` render path for Phase-1; consider per-carrier accent on the dashboard zone seams.

### 2026-06-18 — REAL airline logos (procedural marks rejected, replaced)
- **Why:** owner compared the procedural marks against the actual theflightwall.com product
  (photos: Southwest gradient heart, United globe, Air France wing-stripe — crisp, full-colour,
  full panel height) and called them "nowhere close." Direct instruction: *find where the real
  crisp logos come from and use them; 32 px / full height is fine.* The prior call (procedural
  because "real logos are CORS-blocked mush") was wrong on both counts — corrected here.
- **Source found → `pics.avs.io`** (TravelPayouts/Aviasales CDN): free, **CORS-enabled**,
  transparent-background PNG wordmark logos keyed by **IATA** code, any size. Verified 200s +
  `access-control-allow-origin: *`. (AirHex 403s w/o paid key; TheFlightWall's own CDN serves
  only display *names* — their logos live in proprietary firmware. Both ruled out.)
- **`tools/fetch_logos.py` (new):** ICAO→IATA table → downloads 64 carriers → crops to opaque
  bbox → scales to a 64-px master → `sim/logos/<IATA>.png` + `manifest.json` (~1 MB total).
  5 codes 404 (UPS/Republic/PSA/Piedmont/ABX) → handled by wordmark fallback. Re-runnable;
  wall is then **fully offline** (no per-frame net, no CORS taint, Pi-reusable via Pillow).
- **`sim/airlines.js` rewritten:** dropped all procedural mark geometry. New pipeline loads a
  logo PNG → offscreen-canvas downscale to exact LED height → `getImageData` → alpha-composite
  over black into the framebuffer (`m.set` premultiplies). Read-back **cached per `iata@height`**
  so the 60 fps takeover rasterises each carrier once. Brand accent colour = hand-tuned for ~30
  KBOS majors, else **sampled from the logo's own saturated pixels**. No-logo/unknown →
  brand **wordmark** in brand colour (never a muddy mark). New API: `airlineFor`, `drawLogoH`,
  `fitLogoBox`, `logoReady`, `logoAspect`.
- **Call sites updated:** `app.js drawTakeover` PANEL 1 now fits the real logo big into rows
  3–23 (wordmark fallback) + callsign·type·reg beneath; `scenes.js _drawWide` opens with the
  logo at h=9; `_drawCompact` (64 px) fits the logo across the panel top. Cache-bust `?v=8`→`?v=9`.
- **Verified (preview emulator, gamma-corrected pixel readout):** Southwest takeover (blue
  "Southwest" + gradient heart, crisp, full height) across all 3 panels; United on the 320 px
  dashboard hero **and** the 64 px Phase-0 panel (wordmark + globe + `SFO→BOS` + `FL185`/radar);
  18-carrier sweep rendered 17/18 real logos crisply (1 a load-timing miss, not a missing file);
  wordmark fallback confirmed for Republic (YX, no file) + unknown operators. Buffer-sampled to
  confirm logo + radar pixels lit in every layout.
- **Changed from brief:** reversed the earlier procedural-mark decision entirely. Updated
  `DESIGN.md` §5.1a and `RESEARCH.md` "Airline logos" section to document the real-logo approach
  and retract the "logos turn to mush / CORS-blocked" claim.
- **Next:** port the same logo pipeline to the Pi's Python `display/` path (PIL: load
  `sim/logos/*.png` → resize → blit with gamma) for Phase-1; the manifest + ICAO→IATA map are
  already shared-ready.

## (template)
### YYYY-MM-DD — <step>
- Did:
- Verified:
- Changed from brief:
- Next:
