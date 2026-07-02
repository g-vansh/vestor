# BUILD LOG
Append a dated entry after each meaningful step: what you did, what worked, what changed.
Never record secret values — only that a secret was set.

---

## 2026-07-02 — Manufacturing strategy researched + design revised → `MANUFACTURING_PLAN.md`
Owner asked to deep-research the EASIEST/least-labor way to actually build the mount
(print vs CNC vs waterjet vs off-the-shelf) and make the plan airtight. Ran 4 parallel
web-research agents: (1) LED-wall mounting best practice, (2) off-the-shelf hardware,
(3) makerspace process-labor, (4) adversarial engineering validation.
- **Method verdict:** printing the custom parts + saw/drill the rails is least-*human*-labor
  by a wide margin (quantity is nearly free on a printer; waterjet needs 50 mm plate + tab-
  grinding, CNC needs ~3 setups/part, laser can't do it, custom extrusion die $750+). Print
  cleats on-side (support-free + shear along layers). Hobby Shop confirmed waterjet/mills/saws,
  no laser/router.
- **Validation caught 3 real problems** (skeleton was fine — wood 20-100× margin, PLA 4-5×
  under creep): 🔴 coplanarity (roughly-straight bar + 96 screws won't give invisible P5 seams;
  the Caudell jumbotron that used our exact magnet-on-bars scheme cable-tied panels on),
  🟠 magnet shear-only is marginal (friction ~15% of pull; needs a load-bearing ledge),
  🟠 5 m thermal growth (~1-2.4 mm) into a butt-tight row.
- **Design revised (CAD updated, collide-check clean):** (a) cleat → **tall C-bracket carrying
  BOTH rails** on one rigid spine = coplanarity datum you level once (jackscrew + slotted holes);
  (b) rails → **steel** (flat bar top + **angle** bottom whose leg is a continuous rest ledge →
  weight off the magnets, deletes 16 rest-shoes); (c) center-anchor + slotted rail holes (thermal);
  (d) bare 2-3 mm low-carbon steel for magnet grip. **Magnets KEPT** (owner owns/tested them +
  tool-free service; engineering says fine once weight's on the ledge & rail is flat) — bolt-in-
  sections documented as the fallback. Part count ~43 → ~25. Full cited writeup: `docs/design/
  MANUFACTURING_PLAN.md`; `MOUNT_PARTS.md` banner-flagged as superseded.
- Next: owner review; then print 1 cleat + 1 foot as fit coupons against the real grooves.

## 2026-07-02 — Mount hardware designed (all 6 parts) → `cad/parts/`
Built the whole mount as real CadQuery solids on ONE shared parameter set
(`cad/parts/mount_params.py`) so parts can't drift: **(1)** top cleat, **(2)**
anti-swing foot (base + slide-up tab), **(3)** panel rest shoe, **(4)** two alu bars
+ steel strip (cut list), **(5)** corner Pi/Bonnet enclosure, **(6)** PSU cradle.
Each exports STL + STEP + a fit-check PNG; `assembly.py` unions them all and renders
the full cross-section + a 3D section. Manifest: **`docs/design/MOUNT_PARTS.md`**.
Design calls: two independently wall-referenced rails (no ladder stiles); coplanarity
via the adjustable magnet screws (bars only need to be roughly straight); panels
self-space in X by butting from the corner; bottom capture is a SEPARATE slide-up
motion (kinematic constraint — the bottom groove opens up, a rigid drop-in tab would
hit the solid attach band). **Verified:** an automated boolean `collide_check()` — no
bracket shares volume with a panel or the piece. It CAUGHT a real 2 mm upright/piece
interference → fixed (upright now bears flush on the piece front, sharing the forward
load). Dropped from the earlier brief: the machined continuous T-slot rail (magnet
adjust makes it unnecessary) + the seam-alignment key (butting self-registers). Next:
owner review, then print PLA test coupons (one cleat + one foot) to check the
tongue/tab fit against the real grooves before batch-printing.

## 2026-07-02 — Corner + magnets + length confirmed → feed-topology reconsidered
Owner updates: usable wall length **201.5 in (~5118 mm)** ≈ 16×320=5120 mm (**exact fit, zero spare**).
The **left end is a room corner and the grooved piece turns it** → host the **Pi/bonnet (+ maybe a PSU)
in the corner** (hung or on the 3.4 cm ledge) — **resolves the depth-behind-panels question** (electronics
off to the side; connectors barely protrude). **Magnets tested → viable → GO MAGNETIC** (steel strips on
the aluminum rails + on-hand magnet screws, panels snap on/self-align + alignment pins). **Corner forces a
feed change:** center-fed 2×8 would need a ~2.5 m bonnet ribbon (HUB75 wants <~50 cm, research-backed) →
**propose a single chain of 16 from the corner** (`--led-chain=16 --led-parallel=1`, pwm_bits≈8–9, ~100 Hz —
fine for this static board; also removes the snake/180° flip). **Supersedes LOCKED 2×8, pending owner OK.**
Power: keep PSU2 near the right half (short 5 V runs) or a heavy bus if all-in-corner. Updated WALL_PROFILE,
MOUNT_DESIGN, HARDWARE.

## 2026-07-02 — Mount design, clean-slate (hang from the wooden pocket)
Owner asked to disregard the old §8 mount plan and design fresh given the confirmed wall pocket +
full Hobby Shop (wood + metal + print). Research: a wood French cleat holds 50–100+ kg and is
limited by its anchoring — but our pocket IS solid structural wood spanning the full width, and the
assembly is only ~10 kg → **~5–10× margin, hang straight from the pocket** (no stud lags/toggles).
The hard part is coplanarity over 5 m, not weight. New design in **`docs/design/MOUNT_DESIGN.md`**:
**wood ½″ Baltic-birch tongue-cleat in the pocket (hang) + two continuous aluminum T-slot rails
(flatness, top+bottom at the 144 mm M3 rows) + 3D-printed adjustable clips (per-panel micro-adjust)
+ bottom spacer (anti-swing, no new holes) + Pi/bonnet below the row + PSUs separate/vented.** Every
sustained-load link is metal or structural wood; prints only locate (no PLA creep). Honest
alternative documented: all-Baltic-birch modular cassettes (4×4 panels, CNC'd) — cheaper/in-house,
marginally less stable. Gating measurement: clear depth wall→lip-front + pocket-to-panel height
(decides Pi behind vs below). Marked `INVENTORY.md` §8 SUPERSEDED; pointed `FABRICATION.md` at it.

## 2026-07-02 — 3D-printing plan (MIT Hobby Shop: Bambu P2S/H2S + Form 3L)
Owner joined the MIT Hobby Shop. Confirmed (Charlotte Reiter) 3 printers (Stratasys gone):
**Bambu P2S** (256³), **Bambu H2S** (340×320×340, 65 °C chamber/350 °C hotend → PLA/PETG/ABS/ASA/PC/CF),
**Formlabs Form 3L** (resin). **PLA free** (only filament now, more soon); **resin $0.25/mL**. Wrote
**`docs/FABRICATION.md`** — the printed BOM per part, printer/material assignment, and the load path
integrating the wooden pocket (`WALL_PROFILE.md`) + the 80/20 spine (`INVENTORY.md` §8). **Key finding:**
PLA is the worst common filament for **creep** (sags under sustained load) → the ~12–15 kg hang goes
through METAL; printed parts locate/interface; reprint load-bearing parts (cleat hooks, PSU cradles)
in PETG/ASA on the H2S when stocked. Resin reserved for small precision/cosmetic (~$10–30 total).
Parametric CadQuery/build123d workflow proposed (`cad/`) for Claude/Codex-generated parts. Updated
`MIT_RESOURCES.md` with the confirmed lineup. Next: measure depth behind the 34 mm lip + panel weight,
then generate CAD for the cleat hook + panel bracket first.

## 2026-07-02 — Wall mount profile captured (full-width wooden cleat rail)
Owner sketched + confirmed the top-of-wall cross-section (photo `docs/design/wall_top_profile.jpg`).
It's a **full-width (~512 cm / ~201 in) structural WOODEN rail**: a 2 cm lip standing **3.4 cm
proud** of the wall, with a continuous **open-top pocket (1.4 cm wide × 5 cm deep)** behind it
(open from the top, closed at the bottom; piece anchored to the wall at its bottom). Ceiling → top
of piece = 22.5 cm; piece 14 cm tall. It's load-bearing ("holds a lot of weight"). This is a
**built-in full-width cleat rail** → the plan to **hang the entire 16-panel row (512 cm × 16 cm,
~8 kg) from a top cleat/tongue (≤1.4 cm) dropped into the pocket**, with the lip's 3.4 cm giving
standoff for cabling; PSUs mount separately. Full geometry, panel-row sizing, load path,
constraints, and on-site checks in **`docs/design/WALL_PROFILE.md`**; summarized in `HARDWARE.md`
(resolves panel "mounting method TBD") and `OPEN_QUESTIONS.md`. Took 3 sketch-reading passes to get
the geometry right (was NOT a stepped ledge, and the pocket is open at the TOP not the bottom).

## 2026-07-02 — Plane-swoop scene transitions + fuller center jet
A big airliner now swoops left→right on every scene change (flight↔flight, flight↔clock):
NEW screen reveals to the LEFT of the plane, OLD stays frozen to the RIGHT, with a bright
sweep line at the seam + a contrail (commit 14f435f, deployed + verified: 68/68 logos, 0
restarts, ~54% CPU steady, 51°C). Design doc image: `docs/design/swoop_preview.png`.
- **How the wipe works on a single-buffer renderer:** the display draws into ONE persistent
  canvas and each scene clears+redraws only its own zone per frame. `utilities/transition.py`
  installs a **clip window `[0,edge)`** that every primitive obeys during a wipe, so scenes
  only repaint the revealed left side and the OLD frame is left frozen on the right — no second
  framebuffer, no pixel readback. `edge` sweeps 0→WIDTH under the plane. Steady-state hot path
  is byte-identical (clip is `None`). Text can't be pixel-clipped in the C `DrawText`, so a
  whole-glyph **prefix** is drawn using the real binding's `CharacterWidth` (a straddling glyph
  sits under the wide plane body anyway).
- `install()` also **subsumes the Port-3 lane-offset shim** (the old ad-hoc `graphics.DrawText`
  `+64` monkeypatch is gone). `TransitionMixin` provides `set_pixel`/`draw_square` (offset+clip),
  overrides `reset_scene` to launch a swoop instead of a hard cut, and adds the `swoop` keyframe
  (alphabetically after all scene draws, before `sync`). Toggle: `config.TRANSITIONS_ENABLED`.
- **Two display variants:** repo `display/__init__.py` = clean (lane 0, parallel 1). The Pi runs
  a Port-3 variant (lane 64, parallel 3, `panel_type=FM6126A`) with the SAME transition wiring —
  deployed by hand (NOT rsynced), previous copy backed up on-Pi as `display/__init__.py.preswoop`.
- `journey.py`: center track plane is now a fuller mini-jet (2px fuselage + tail fin + swept
  wing), not a 1px stick. Offline validation: `tools/preview_transition.py` (swoop filmstrip).

## 2026-07-01 — Route accuracy + operator logos (NetJets/PAL/Envoy) + real plane sprite
Five field-reported fixes (commit f6e2904), deployed + verified on-Pi (68/68 logos, 0 restarts):
- **`BOS→???` for ENY4047 (real: BOS-DCA):** FR24's lightweight feed returned the origin
  but an empty destination that instant, and we cached that partial route for the full 30-min
  TTL — so it stuck. Now: partial FR24 routes are completed from adsbdb **only when adsbdb
  corroborates the endpoint FR24 already knows** (adsbdb is often a stale *different* leg —
  it had ENY4047 as PHX-SLC), and partial routes cache for just **25 s** (`INCOMPLETE_TTL`)
  so the next FR24 scan (refreshed every 20 s) fills the gap. Self-heals in ~25 s vs. never.
- **NetJets logo:** `EJA→1I`; wordmark from kiwi (served on white → white-keyed to alpha).
- **PAL Airlines (PVL7668) showed "AIRLINER":** `PVL→PB` + `AIRLINE_DB["PVL"]="PAL"`. Note
  **adsbdb mislabels ICAO PVL as an Italian operator "Professione Volare"** — our AIRLINE_DB
  overrides the name; PVL=PAL Airlines confirmed (IATA PB, callsign PROVINCIAL, airhex/wiki).
- **Envoy shown as "American Eagle":** `ENY` mapped to `MQ` (= American Eagle). Now `ENY→ENY`,
  keying Envoy's **own** navy "envoy" wordmark (Wikimedia SVG, rasterised via the MediaWiki
  thumbnailer). MQ.png (American Eagle) is now orphaned/unused but left on disk.
- **Center-panel plane** was a 7×5 blob → **14×5 side-view jet** (tail fin + swept wing +
  brightened nose tip) in `scenes/journey.py`.
- `tools/fetch_logos.py` gains a `SPECIAL_LOGOS` pipeline (non-avs sources + optional white-key)
  so NetJets/Envoy are reproducible; `PVL→PB` added to the avs map. Re-run reproduces all 66.

## 2026-07-01 — Single-panel flight-tracker app RUNNING LIVE (as a service)

Found the "one-screen" app already installed on the Pi at `/home/pi/vestor` (venv,
`rgbmatrix` + `FlightRadarAPI` import OK, `.env` present). Ran it and it's now **live and
persistent**: enabled `vestor.service` with a `User=root` drop-in (the hzeller lib must
start as root for GPIO, then drops privileges). `Restart=always`, starts on boot. Pulls
live Logan/Boston flights (found + detailed a real flight) + weather + clock.

**Port-3 shim (TEMP):** the test panel is on bonnet **Port 3** (lane 2), but the app
hardcodes `parallel=1` (Port 1). Rather than move the ribbon, patched the Pi's
`display/__init__.py`: `parallel=1→3` + a +64-row draw offset on `graphics.DrawText/
DrawLine` and `SetPixel` (app uses no image blits, so 3 primitives cover it). Backup at
`display/__init__.py.port1bak`. **REVERT before the full-wall build** (which uses 2×8
center-feed, `parallel=2`, Ports 1&2). Manage: `systemctl {start,stop,restart,status}
vestor`; `journalctl -u vestor -f`.

- Docs: RUNBOOK (+Phase-0-confirmed/operate section, fixed stale 6+5+5 → 2×8);
  OPEN_QUESTIONS + INVENTORY headline updated post-first-light. On-Pi code is a static
  copy (not a git repo) and slightly behind the repo's comment edits — functionally same.


## 2026-07-01 — ✅ FIRST LIGHT — Phase 0 COMPLETE

One panel fully lit and animating (demo -D4 pulsing color, all 32 rows, correct
colors), driven over Tailscale SSH from the Mac. **Every link validated together:**
CnGear FM6124HJ panel + Triple Bonnet + riser + LRS-350-5 (5 V, correct polarity) +
hzeller driver on the Pi 4.

**Confirmed working config (matches the locked plan exactly):**
`--led-rows=32 --led-cols=64 --led-gpio-mapping=regular --led-slowdown-gpio=4
--led-brightness=50` · **no `--led-panel-type`** (FM6124HJ = standard, no init — FM6126A
made no difference) · **default RGB sequence** (colors correct, no swap).

**The one gotcha (not config — physical):** the demo drives chain/lane 0 = bonnet Port 1;
the ribbon was on **Port 3**. A panel on the wrong port gets no data and shows a stuck
half-lit ("top-half white") block that looks like a driver fault but isn't. Drove it with
`--led-parallel=3` (lane 2 = Port 3) → full panel lit. Lesson: "only half lights / static
white" first suspect = wrong bonnet port or IN/OUT connector, not the panel-type.

**Next:** QC the remaining 15 panels on the rig (per-panel demo -D3/-D4/-D5), log 1-16,
keep spares; then full-wall build (mount + power distribution).


## 2026-07-01 — Pi ONLINE + bonnet working; WiFi root cause found (cmdline instance-id lock)

Long bring-up session. Bare Pi connected once, then nothing would connect (bonnet or
bare) for hours. **Root cause found by inspecting the SD boot partition:** `cmdline.txt`
had `ds=nocloud;i=rpi-imager-1781482529670` — the cloud-init instance-id **hardcoded in
the kernel command line**, which **overrides `meta-data`**. So the earlier "bump the
instance-id in meta-data" never took effect → cloud-init never re-rendered `network-config`
→ the added `Vestor` network was **never applied**; the Pi only ever knew `MIT`.

**Fix:** changed `i=` in `cmdline.txt` (+ matched `meta-data`) to a new value
(`vestor-fix-20260701`). Next boot, cloud-init re-provisioned → **`Vestor` + `MIT` both
configured**. Also added a `user-data` runcmd that writes `/boot/firmware/vestor-netdiag.txt`
(offline instrumentation) — validated + used.

**Confirmed working (root Tailscale SSH from the Mac):**
- Online via **MIT on 5 GHz** (ch 149), signal **-52 dBm/80% bare, -48 dBm/85% with the
  bonnet** → **the bonnet does NOT shadow the WiFi antenna**; all prior bonnet-WiFi
  failures were the config bug, not RF. `Vestor` hotspot kept as configured backup.
- Power clean: 42-44 C, `throttled=0x0` (official Pi brick + **riser header** for solid
  GPIO contact — the riser was required to clear the Pi 4 PoE pins; a marginal USB-C
  supply had caused brown-out before).
- **hzeller `demo` present** at `~/rpi-rgb-led-matrix/examples-api-use/demo` → first light
  ready once a panel is powered.
- Tailscale SSH works as `root` (identity auth, no password); `pi` sudo needs a password;
  `iw` not installed (use `nmcli` / `/proc/net/wireless`); nmcli can't modify the
  netplan-managed connections (powersave toggle deferred).

**Recommendations captured:** operational wall should use **MIT 5 GHz (or wired MITnet)**,
not a 2.4 GHz phone hotspot — 5 GHz also dodges the matrix's future 2.4 GHz RFI. Resolves
the OPEN_QUESTIONS Tailscale/reachability item.

**Next:** power one panel (LRS-350-5 + AC cord, 115 V, red->+V/black->-V) → run the demo
over SSH → first light.


## 2026-06-29 — PSU sizing confirmed: keep 2× LRS-350-5 (not downsizing)

Owner asked whether the lower-than-budgeted draw means a smaller/cheaper supply
would suffice (and whether to cancel the order). Worked the math:
- Per 64×32 P5 panel: **~4 A** full-white-full-bright conservative max (Adafruit); 2.4 A
  measured (WiredWatts). 16 panels = **64 A (max) / 38 A (measured)**; per-PSU (8) =
  32 A / 19 A. **Real use (BRIGHTNESS≤50 + dark content) ≈ 5–16 A total** (~10–20 %/PSU).
- **One PSU? No** — 64 A max > a single 60 A unit, and central feed reintroduces the
  voltage-drop the split avoids. **Keep two.**
- **2× LRS-350-5 (60 A): correctly sized** — 53 % at conservative peak, ~10–20 % typical.
- **Alternative considered: 2× LRS-200-5 (40 A, FANLESS).** Would suffice (32 A peak =
  80 %); main upside is fanless (silent/fewer failure modes) for a living-space wall.
- **DECISION: keep the LRS-350-5s.** Overspecced = cooler/longer life; the fan is
  thermostatic and won't run at ~10–20 % load (so fanless benefit is moot in practice);
  ~$15 savings not worth the cancel/reorder. Switch to 200s only if guaranteed-fanless
  is a personal priority — it's a preference, not a fix.
- No doc/BOM change (order stands). Recorded in `INVENTORY.md` §9.5.

## 2026-06-29 — Layout VALIDATED (research agents) → PSUs split + config notes

Validation research (data-integrity / power-injection+voltage-drop / grounding+thermal
sub-agents) against real long-row HUB75 builds. Layout sound; one change + notes
(`INVENTORY.md` §9.5):

- ✅ **2×8 center-feed confirmed** — 8/chain is the sweet spot; ghosting/tearing starts
  at 12+/chain (hzeller README + issue #1370). Center-feed halves worst-case run.
- ✅ **Per-panel power + 4 fuse blocks confirmed best practice** (HUB75 can't pass power;
  AusChristmasLighting consensus: fuse +only, common −, never tie the 2 V+ together).
- ✅ **Real draw lower than assumed:** 1.7–2.4 A/panel full white (WiredWatts measured),
  not 4 A → 7.5 A fuses + budget very comfortable.
- 🔧 **CHANGE: split the PSUs (one behind each half), do NOT center-stack.** (a) voltage
  drop ~4× less (far panel ~4.95 V; hzeller targets <50 mV); (b) **LRS-350-5 has a
  built-in fan** (verified — not convection) → don't block airflow, ~10–15 cm clearance;
  (c) inrush ~20 A cold-start each @115 V → separate outlets vs a 15 A breaker. **Double
  Stack Mount Kit now unused** (keep as spare).
- ⚡ **Grounding:** star-point bond PSU1 V− ↔ PSU2 V− ↔ Pi GND (data ground reference);
  never parallel V+.
- 📝 **Pi config notes** banked: pwm-bits=7, lsb-ns≈100→300, dither=1, isolcpus a core,
  **audio off + blacklist snd_bcm2835** (mandatory), slowdown 2–4. ~400–500 Hz.
- 📝 Magnet hold over 5 m is anecdotal → bench-test a few panels on the rail first.
- Changed: `INVENTORY.md` §9.5 (PSU split + grounding + config + draw). Diagram's center
  box → Pi+bonnet only; PSUs move behind each half.

## 2026-06-29 — Physical layout LOCKED (power pigtail = 55 cm)

Owner measured the panel power pigtail = **55 cm** (white 4-pin plug → 2 blue forks).
This sets the power distribution. Worked the full back-of-wall layout (`INVENTORY.md`
§9.5; diagram shown to owner):
- 55 cm reach ÷ 320 mm pitch ≈ **±1.7 panels → one fuse block serves ~4 panels.**
- **→ 4× 6-way fuse blocks** (not 2× 12-way), one per 4-panel group (1–4/5–8/9–12/13–16),
  fed by 10 AWG trunk from the center PSUs. Each panel's 55 cm pigtail reaches its block;
  no extension leads needed.
- **PSUs center-stacked** (uses the Double Stack Mount Kit) in a center "brain box" with
  the Pi + Triple Bonnet + AC entry — single service point. Data 2×8 center-feed
  unchanged; magnetic mount unchanged.
- **No redesign** — only the fuse-block count/spacing changed (2→4, +~$18). BOM total
  still-to-order ~$115–145.
- Launched a background agent to validate injection spacing / voltage drop / grounding /
  cable mgmt against real long-row HUB75 builds; fold in refinements.
- Changed: `INVENTORY.md` §9.2 (4× 6-way blocks) + §9.5 (layout resolved).

## 2026-06-29 — PSUs + magnets ordered; mounting revised to MAGNETIC

Owner placed Wired Watts order #225740 ($90.60): **2× LRS-350-5** ($29.50 ea — the
Phase-0/wall power blocker is now resolved, up to 10 biz days), a **Double Stack Mount
Kit** ($4.50, stacks the 2 PSUs), and **100× Panel Magnets** ($10, screw-in feet for
the M3 holes — "just in case").

- **⚠️ GAP: no AC cords ordered.** LRS-350-5 has bare L/N/⏚ terminals → needs **2× AC
  mains cords** (~$8 ea). Added to the still-to-order list. Set PSU input switch to 115 V.
- **Mounting revised → MAGNETIC** (`INVENTORY.md` §9.1/9.2). 100 magnets = 6×16 + spares,
  so screw a magnet into each panel's 6 holes and snap onto **2 ferrous rails** (Unistrut
  / steel flat bar) at the magnet-row heights. Tool-free service, no bracket printing.
  The earlier "reject magnets" was about a full steel *sheet*; 2 narrow rails avoid that.
  3D-bracket+extrusion kept as the fallback.
- **New open item (§9.5):** measure the **panel power-pigtail length** — it sets whether
  one fuse block per half reaches all 8 panels or we split/extend. Also the Stack Kit
  implies central PSU placement → plan 5 V runs to reach both ends, or mount split.
- Still to order (~$95–125): 2 AC cords, 2× Nilight fuse blocks, 7.5 A fuses, 10/14 AWG
  wire, 2 ferrous rails (free at MIT possible), wall anchors, optional clamp meter.
- Changed: `INVENTORY.md` §1 (ordered items + AC-cord gap), §9.1/9.2/9.3/9.4 (+§9.5).

## 2026-06-29 — Panels arrived: specs verified off the boards + framed-panel discovery

**Context:** the 16 panels landed. Owner photographed the backs (IMG_3780-82, HEIC →
converted + zoomed). Read exact specs directly off the PCBs.

**Verified on-board (supersedes listing-image assumptions):**
- Marking **`P5(2121)64×32-16S-JHT2.0`** → P5 pitch, SMD2121 LEDs, 64×32, **1/16 scan**.
- Pinout table on the board: **R1 G1 / B1 GND / R2 G2 / B2 GND / A B / C D / CLK LAT /
  OE GND** — standard HUB75, **A–D only (no E)**. Connector labeled **HUB75-D**, 16-pin 2×8.
- Driver **FM6124HJ** (chip UB2). `HJ` = package/grade variant of the standard FM6124;
  no init needed. (Listing image had said `FM6124ZD` — same family, no impact.)
- Power: **4-pin keyed plug** labeled `POWER` (NOT screw terminals).
- Brand **CnGear**; 220 µF/10 V decoupling caps.
- **Electrical verdict: 100% compatible, zero config changes** to the locked plan.

**Key new finding — FRAMED panels, not bare PCBs:**
- Each panel has an **integrated black plastic rear frame** (X-braced, brass corner
  screws). **No magnets included** (owner confirmed). This invalidates the prior
  "6 M3 magnet holes on a flat PCB back" mounting assumption and may affect whether the
  Hanson distro bolts flat. **§8 mounting + distro fit now PENDING owner detail.**

**Asked owner for (can't get from photos):** confirm 16 individual panels; panel
W×H×**depth** (incl. frame); straight-down photo of the frame's rear mounting
provisions; whether frames join/align panel-to-panel; full accessory inventory (power
cable type + count, data ribbon count + **length**, screws/clips, confirm zero magnets).

- Changed: `docs/INVENTORY.md` §6 (verified table + mounting-changed flag),
  `docs/HARDWARE.md` (FM6124HJ/CnGear/JHT2.0/framed). Docs only.

**Update (same day, later) — 3-AGENT OPTIMIZATION PASS → §9 final no-solder build:**
Owner asked to minimize parts/cost/hassle (no soldering) and check for prebuilt
options. Ran 3 research agents (power, mounting, prebuilt+control) across web + Reddit
+ xLights/Falcon forums. Outcome (full detail → `INVENTORY.md` §9):
- **Control = $0:** keep Pi 4 + Triple Bonnet + Python. No prebuilt 1024×32 wall worth
  buying (custom = several× panel cost, NovaStar-locked). NovaStar/ESP32 = downgrades.
  Keep 2×8 center-feed; cap `--led-pwm-bits 7–8`. **Rejected the agent's "3-lane/6+5+5"
  idea** — a 3rd chain needs a >50 cm jump in a 1-D row (the constraint that locked 2×8).
- **Power = ~$52, no-solder:** drop the 4 Hanson distros → **one 8-way ATC automotive
  fuse block (w/ negative bus) per PSU**; panel forks land directly (+5 V fused, GND on
  bus). Per-panel fusing kept (60 A rail + shorted 4 A panel = fire). Bare wire under
  screws → no crimp tool. **Corrected agent error:** PSU→block trunk carries full ~32 A
  (splits only *after* fuses) → **10 AWG, not 14 AWG**.
- **Mounting = ~$48 + free MIT parts:** 2020 extrusion + 3D-printed brackets (free at
  MIT). Magnets+steel **rejected** for a long thin row (flat 16.8 ft steel = heavy/
  pricey/not-flat). ~30 lb assembled → French cleat + few toggle bolts, no studs.
- **Net:** earlier ~$230–330 BOM → **~$100** (≈$155 if buying extrusion). Cut: Hanson
  distros, copper AWG6, crimp tool, bus bars, magnets/steel, data+power cables, 3rd PSU.
- Changed: `INVENTORY.md` §2 (fuse-block table), §7.1 (Hanson superseded), **+§9**.

**Update (same day) — mounting + cabling RESOLVED from frame/cable photos (IMG_3783-85):**
- Owner confirmed **16 individual panels**, each its own CnGear frame; **320 × 158 ×
  ~15 mm**; panels **do NOT interlock**.
- **Mounting interface:** frame has **6 brass M3-threaded holes** (4 corners + 2 mid-
  long-edge, 3×2 grid, ~144 mm vertical pitch) = the magnet-mount points. **Zero
  magnets** shipped → holes free for screws/brackets.
- **RECOMMENDED:** 80/20 backbone + **3D-printed brackets** (free at MIT) into the 6
  holes, locating panels coplanar; no magnets/steel needed; serviceable. Alt: buy ~64
  magnet feet + 2 steel strips on the rail. → `INVENTORY.md` §8.0.
- **Cabling fully included:** 16 short 16-pin IDC data ribbons (`UL2651 28AWG`) + 16
  power pigtails (white 4-pin plug → **blue fork** PSU end). **Buy neither.** Forks land
  on distro/bus-bar screw terminals → distro mounts on the **backbone** (144 mm-on-panel
  fit moot). → §6, §7.1, §7.3.
- Net buy-list shrinkage: no data cables, no panel power cables, no magnets (if bracket
  route). Real blocker still just 1× LRS-350-5 + AC cord.

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

## 2026-06-21 — Ecosystem gear scout (5 agents) → docs/GEAR.md

**Context:** owner asked to scour wiredwatts.com + adjacent vendor/community sites for
any tech that makes the build easier. Ran 5 domain agents (power, HUB75 cabling,
mounting, front-face/diffusion, reliability/remote-ops). New `docs/GEAR.md` (ranked,
with honest SKIP flags). Highlights folded into existing docs:

- **CCA fix:** ferrule kit + ratchet crimper (AWG6–10, ~$45) = gas-tight no-solder
  connection. **DC clamp meter** (UNI-T UT210E, ~$35) to verify the power plan
  (cheap meters read AC only). → noted in INVENTORY §7.1.
- **PSU-side distro:** Hanson **PDist1** (~$15 ×2) bolts onto LRS-350-5 terminals
  (4×15A+1×10A fused). **PSU has no remote-sense → trim output to ~5.1–5.2 V** to
  pre-compensate run drop. → INVENTORY §7.1.
- **Front face = dark TINT, not white diffuser** (white smears 5 mm text). Smoke-Gray
  #2064 acrylic deepens blacks + hides shade variation (§7.2). → ROADMAP finishing.
- **Reliability (all FREE, work with Tailscale/systemd):** systemd hardware watchdog,
  overlayfs read-only root, remote logging; **smart plug** (Shelly/Kasa) to remote-
  power-cycle the PSUs. → ROADMAP "always-on hardening". **Hard constraint logged:**
  Triple Bonnet eats the full 40-pin header → no GPIO UPS HATs / fan SHIMs.
- **Honest skips:** included ribbons cover 14/16 data links (only make 2 feed cables);
  Electrodragon board = the bonnet's active-3 (no gain); die-cast cabinets, 230V inrush
  units, Pi UPS HATs — overkill/wrong-spec.
- Changed: new `docs/GEAR.md`; INVENTORY §7.1 (PDist1 + PSU trim + clamp meter);
  ROADMAP (+hardening +finishing). Docs only.

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

### 2026-07-01 — Legendary single-panel flight card (on-Pi, logos + split-flap)
- **Did:** built the airline-branded 64×32 flight scene on the real Pi — the
  hardware sibling of the sim's logo takeover. New/rewritten:
  `scenes/airlinelogo.py` (logo band + livery rule), `scenes/journey.py` (route
  with **split-flap flip-in** + brand-colour chase marker, relocated to rows
  13–25), `scenes/flightdetails.py` (telemetry: warm-white callsign + right
  field **rotating** altitude/type/index, off-blue), `scenes/planedetails.py`
  (now a headless ~7 s dwell/cycle controller). `setup/airlines.py`
  (ICAO→IATA + brand + names + **FR24 altitude ramp**), `setup/logos.py`
  (stdlib pack loader). `utilities/overhead.py` +`aircraft_code`.
  `setup/frames.py` → **20 fps**. `display/__init__.py` wires `AirlineLogoScene`.
- **Logo pipeline (this delivers the last BUILD_LOG "Next"):** reuse the sim's
  64 wordmark PNGs + manifest, but bake them **offline** with
  `tools/bake_logos.py` → `assets/airline_logos.pkl` (12px tall, alpha flattened
  on black, **no gamma** — the driver auto-applies CIE1931; packed 5 bytes/px,
  ~75 KB). On the Pi we render with **`SetPixel` (no runtime Pillow)**, so the
  code rides the existing Port-3 `+64` shim and stays panel-lane-agnostic.
  *(Chose pre-baked pixels over PIL-at-runtime: dependency-free + shim-friendly.)*
- **Verified:** `tools/preview_card.py` — a pixel-accurate offline harness that
  mocks `rgbmatrix` with a real BDF renderer — confirmed the full composition
  (United/jetBlue/British Airways, split-flap settling, FR24 altitude colours,
  climb/descent triangles, BA Speedmarque revealed by the marquee); 0 stray red
  in non-BA route bands. On hardware: `import display` OK in the venv, pack
  loads, **service active, no tracebacks**, 35% CPU (driver baseline, not
  pegged), 52 °C, `throttled=0x0`, pulling live Logan flights at 20 fps. The
  FR24 `Content-Encoding=gzip` log lines are a benign library warning (JSON
  still decodes).
- **Changed from brief:** the Pi path renders logos via pre-baked `SetPixel`
  pixels rather than the sim's live-canvas/PIL blit — same assets + ICAO→IATA
  map, but zero runtime image deps and compatible with the temporary shim.
  Dropped the upstream blue callsign (worst LED text colour per research).
- **Next:** (1) revert the Port-3 shim + `parallel` when the centre-fed wall is
  wired; (2) optional polish — a pulsing brand "LIVE" dot, scene wipe
  transitions; (3) port the same card into the wall's multi-panel layout.
- **Design doc:** [design/FLIGHT_CARD_PI.md](design/FLIGHT_CARD_PI.md)
  (+ `design/flight_card_preview.png`).

### 2026-07-01 — Fix: American logo + switch logos to runtime Pillow
- **Did:** the American logo showed as faint gray "American Airlines" text — its
  livery is a **dark-navy wordmark**, the worst case on a black HUB75 panel
  (blue = dimmest subpixel; the driver's CIE1931 curve dims mids further). Added
  an **LED legibility lift** in `setup/logos.py` (raise HSV *value* `v**0.62`
  floored at 0.50, keep hue) so dark liveries read and their colourful marks pop
  (AA eagle, BA Speedmarque); already-bright logos barely move.
- **Also (owner directive — "install/use whatever packages you need, don't
  reinvent the wheel"):** replaced the offline pre-baked pixel-pack
  (`tools/bake_logos.py` → `assets/airline_logos.pkl`, both removed) with
  **runtime rendering via Pillow** straight from `sim/logos/*.png`, cached per
  carrier. Pillow is now a **declared dep** (`requirements.txt`) and is used on
  the Pi as intended (venv already had 12.2). One code path, no custom byte
  format, `sim/logos/` is the single source of truth. Shipped `sim/logos/` (64
  PNGs, 1 MB) to the Pi.
- **Verified:** offline preview (American now bright + recognisable, United/
  Delta unaffected); on the Pi `get_logo("AA")` → 239-px lifted logo; service
  active, no tracebacks, ~41% CPU (per-carrier render cached), 52 °C.
- **Changed from brief:** dropped the "no runtime Pillow dep" goal entirely — it
  was needless wheel-reinvention. To tune a carrier now: edit `LIFT_*` in
  `setup/logos.py` + restart (no bake step). Updated
  [design/FLIGHT_CARD_PI.md](design/FLIGHT_CARD_PI.md) §2.

### 2026-07-01 — Fix: logos showed as name-text (drop_privileges vs file reads)
- **Symptom:** panel showed airline **names as plain text** (the AirlineLogoScene
  text fallback), not logos — even though the new code was deployed and
  `get_logo("AA")` worked in a manual `ssh root` venv test.
- **Root cause:** `display/__init__.py` sets `options.drop_privileges = True`.
  `RGBMatrix()` maps the PWM hardware as root, then **drops to the `daemon`
  user**. `daemon` cannot read the logo PNGs because **`/home/pi` is 0700**
  (`drwx------ pi`) — so `daemon` can't even traverse into it. Fonts render fine
  because `setup/fonts.py` `LoadFont` runs at **import** (pre-construction, still
  root); my logos loaded **lazily at render time** (post-drop) → `Image.open`
  → PermissionError → `_render` returns None → text fallback. The manual test
  never constructed the matrix, so it never dropped privileges — masking the bug.
- **Fix:** `setup/logos.py preload_all()` renders + caches all 64 logos, called
  from `Display.__init__` **before** `RGBMatrix()` (i.e. while still root).
  Post-drop rendering hits the warm module-level cache and never touches disk.
  Logs `"[logos] preloaded 64/64 airline logos"` at startup for observability.
- **Rule learned (important):** with `drop_privileges=True`, **every file the app
  reads must be read before the matrix is constructed** — load assets at import
  or in `__init__` pre-`RGBMatrix()`, never lazily during rendering. (Backup on
  Pi: `display/__init__.py.prepreload`.)
- **Verified:** restart → `preloaded 64/64`, service active, no errors, still
  drops to `daemon` (cache warm).

### 2026-07-01 — Live data: FR24 → airplanes.live + adsbdb (free, no key, 10s poll)
- **Why:** FR24's unofficial feed throttles/blocks under continuous polling (a
  45-mile Boston box returned 0 flights after our polling tripped it). It's also
  ToS-fragile for a 24/7 wall. Researched the free community ADS-B landscape and
  chose **airplanes.live** (positions + aircraft type, `/v2/point/{lat}/{lon}/
  {radius}`, ~1 req/s, no key) + **adsbdb.com** (callsign→route, cached, no key)
  — the same route enricher upstream its-a-plane uses. (Rejected OpenSky: 4000/
  day cap + OAuth2 + no route; AeroDataBox: ~600 units/mo free ≠ 24/7.)
- **Did:** new `utilities/overhead.py` self-polls on a background daemon thread
  every `POLL_SECONDS` (config, default **10s**) and emits the identical
  per-flight dict, so every scene AND `display/__init__.py` are unchanged (no
  Pi-shim re-patch). Config adds `SEARCH_RADIUS_NM=10`, `POLL_SECONDS=10`,
  `MAX_FLIGHTS=5`. FR24 impl preserved as `utilities/overhead_fr24.py`.
- **CA gotcha (same family as the logo bug):** `requests` verifies with certifi's
  bundle under `/home/pi` (0700) — unreadable by the dropped `daemon` user — so
  point `verify` at the OS trust store `/etc/ssl/certs/ca-certificates.crt`.
  Tested by replicating the service's `import-as-root → setuid(daemon) → HTTPS`
  sequence: airplanes.live + adsbdb both 200 as uid 1.
- **Verified:** real flights over Cambridge with routes (JFK→BOS on approach,
  Republic IND→PHL, a NetJets Citation TVC→BED), types (A21N/E75L/C68A), vspeed;
  service healthy, `preloaded 64/64` logos, no fetch errors across poll cycles,
  `DEMO_MODE=False`.
- **Legit, not evasion:** declined FR24 identity-rotation (ToS-violating, fragile
  on a fixed-IP appliance); switched to a source that's free + unthrottled by
  design. Ultimate path remains a local RTL-SDR + dump1090 receiver.

### 2026-07-01 — Fix: logos invisible (SetPixel can't be monkeypatched) + static logos + box
- **Symptom (from panel photos):** route codes + telemetry rendered, but the
  logo band was empty — no logo AND no fallback text — even for carriers with
  logos (United LAX→JFK, Delta ATL→CDG).
- **Root cause:** the Port-3 shim added its +64 lane offset to `SetPixel` by
  monkeypatching `FrameCanvas.SetPixel` — but that's an **immutable method on the
  compiled C-extension type** (`TypeError: cannot set 'SetPixel' attribute of
  immutable type`), swallowed by the shim's `try/except`. So every SetPixel-drawn
  element (the whole logo, chase marker, vs triangle, loading dot) was written to
  lane 0 (**Port 1 = no panel**) and vanished, while `DrawText`/`DrawLine`
  (module-level functions, genuinely patched) rendered on Port 3. Verified the
  TypeError directly on-Pi.
- **Fix:** stop monkeypatching. Added `Display.set_pixel(x,y,r,g,b)` that adds
  `self._lane_offset_y` (**0** in-repo, **64** on the Pi shim) — mirroring how
  `draw_square` wraps `DrawLine`. All scenes now call `self.set_pixel`, never
  `canvas.SetPixel`. Lesson: **never rely on monkeypatching methods of the
  rgbmatrix C-extension types.**
- **Also (owner requests):** logos now **static** — `setup/logos.py` fits each
  logo WHOLE within 64×12 (no marquee); `airlinelogo.py` centres it H+V.
  `MAX_ALTITUDE` back to **10000** (visible-overhead only). Bounding box: added
  `USE_ZONE_BOX` — `overhead.py` filters airplanes.live results to `ZONE_HOME`
  (the same box FR24 used; point+radius stays the query, radius must cover it).
- **Verified:** offline preview (all logos static + whole), on-Pi import OK,
  service stable (`set_pixel` resolves or the every-frame logo draw would
  AttributeError-loop), `preloaded 64/64`, box+10000ft returned Cape Air
  LEB→BOS at 225 ft with a 24×12 static logo.

### 2026-07-01 — 3-panel departure board (airline | route | telemetry)
- **Did:** owner chained 2 more panels left of the original. **Calibrated** the
  geometry with a labelled test pattern (`scratchpad/calibrate3.py`): canvas col
  0..63 = physical LEFT, 64..127 = middle, 128..191 = right; L/R + v correct →
  **no mirror/flip**, canvas X = physical X. Rebuilt the card as a 192×32
  three-zone board: LEFT = big static airline logo + name + livery rule
  (`airlinelogo.py`); MIDDLE = big stacked ORIGIN/DEST codes, split-flap
  flip-in, plane sprite on a dotted track (`journey.py`); RIGHT = callsign +
  big FR24-coloured altitude + climb/descent marker + type + N/M + vertical
  altitude gauge (`flightdetails.py`). `config CHAIN_LENGTH=3` drives
  `display.chain_length` + `setup/screen` zone offsets (ZONE_AIRLINE/ROUTE/
  TELEM = 0/64/128); `setup/logos` fits to a larger 62×22 box. Scenes draw
  canvas-absolute per zone; the Pi's Port-3 `+64` row shim is unchanged.
- **Verified:** offline preview (`tools/preview_card.py` now 192×32) + live —
  service active, restarts 0, preloaded 64/64, ~54% CPU (3× draw load, fine),
  50 °C, no throttle. JetBlue JFK→BUR and United IAD→BOS rendered with full
  logo+route+telemetry.
- **Connectivity fix (earlier):** a deploy `rsync` hung on a WiFi blip (no
  keepalive) → zombie session saturated the Pi's tailscaled SSH → banner
  timeouts; a power-cycle cleared it. Added a `Host vestor` block to
  `~/.ssh/config` (ConnectTimeout + ServerAliveInterval) so a blip fails fast
  instead of hanging. tailscale ping/TCP were always healthy — it was never WiFi.
- **Next:** 4th panel → extend zones; enhance the idle (no-flight) state to
  span all panels (currently the clock draws only on the left panel).

### 2026-07-01 — Accurate CURRENT routes: FR24-primary + adsbdb fallback
- **Why:** free route DBs (adsbdb/hexdb) key on flight number and return STALE
  scheduled routes — a flight number can fly different routes in one day. Tested
  live: for DAL2816/RPA5679/AAL909 adsbdb was wrong; **FR24 returned the correct
  current route every time** (it reads the filed flight plan). FR24 also gives
  origin/dest on the *basic* get_flights object (no per-flight detail calls).
- **Did:** `overhead.py` route enrichment is now **FR24-first, adsbdb-fallback**.
  When any overhead callsign has no cached route, do ONE FR24 `get_flights` over
  the zone → `{callsign: (origin,dest)}`; routes are cached in-memory with a
  **30-min TTL** (`ROUTE_TTL`, pruned each grab, never persisted) — long enough
  to survive one overhead pass, short enough that a reused callsign (a different
  leg later) re-fetches. Callsigns FR24 doesn't cover fall back to adsbdb + the
  BOS plausibility fix. Positions still come from airplanes.live every 10s.
- **Won't re-throttle:** routes are static, so it's ~0 FR24 calls in steady
  state and one per new arrival; plus a hard **`FR24_MIN_INTERVAL=20s`** rate-cap
  and graceful `{}`-on-error fallback. (The original throttle was polling FR24's
  feed + 5 detail calls every 30s forever.)
- **Verified:** JBU251 now shows **BOS→MCO** (was `BOS → ?`); service active,
  restarts 0, no FR24 errors, ~59% CPU, 53 °C.

### 2026-07-01 — Design tiers 1–4: idle screen, richer cards, polish, prioritization
- **Tier 1 — idle screen** (`scenes/idle.py`, replaces the old single-panel
  clock/date/day/weather): when no aircraft is overhead, all 3 panels show a big
  blinking clock (middle), day/date/year (left), and weather (right), with a
  radar sweep across the board. Weather from **Open-Meteo** (`setup/weather.py`,
  free/no-key, 10-min refresh) in **both °C and °F**. `setup/fonts.py` +`huge`
  (10x20). `display` wires `IdleScene` + `weather.start()`.
- **Tier 2 — richer telemetry** (`scenes/flightdetails.py`): ARR/DEP status tag
  (green down / amber up, from BOS origin/dest + climb/descent), ground speed,
  and an **emergency-squawk alert** (7500/7600/7700 → flashing red HIJACK/NORDO/
  EMERG). `overhead.py` now enriches flights with ground_speed/heading/squawk/
  registration/distance/bearing.
- **Tier 3 — polish**: **dynamic icons** — helicopter rotor + prop SPIN
  (`setup/aircraft.py` marks `*` animated px; `airlinelogo.py` sweeps a bright
  spot); pulsing LIVE dot. (Split-flap already transitions each flight; a
  full-board plane flyover was left out — packed layout, no free strip.)
- **Tier 4 — smart prioritization** (`overhead.py`): sort overhead flights by
  interest (commercial + arriving-low) then nearest, so the board leads with the
  best flight.
- **Verified:** idle + flight previews; live on the Pi (weather 28C/83F, ARR/DEP
  tags, 7700 alert, service healthy, ~64% CPU, 52 °C).

## (template)
### YYYY-MM-DD — <step>
- Did:
- Verified:
- Changed from brief:
- Next:
