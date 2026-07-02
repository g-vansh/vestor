# VESTOR — RELIABILITY HARDENING (permanent 24/7 wall)

Synthesis of six cited research passes (strain relief · IDC ribbons · grounding/​
decoupling · 24/7 failure modes · power terminals · EMI/RFI) into one prioritized,
buildable checklist. This is what makes a 16-panel HUB75 wall still work in 5–10 years.
Companion: `ELECTRICAL.md`, `design/BACK_OF_HOUSE.md`, `design/MOUNT_PARTS.md`.

## What actually fails first (years-scale, 24/7) — plan around this
1. **PSU cooling fan** — the only moving part, in a warm dusty gap. *But you load each
   LRS-350-5 to ~13 % (≤50 brightness), far under the 50–60 % derate knee → the fan
   essentially never spins.* Keep it that way + stock a spare 80 mm fan.
2. **PSU electrolytic caps** — wear-out item; life halves per +10 °C. Run cool + light (both true).
3. **Panel driver/​shift-register ICs** — cause whole-row/​half-panel/​stuck-color death; not
   repairable → **swap the panel** → keep same-batch spares.
4. **Solder joints + IDC ribbon/​connectors** — "a loose ribbon is the #1 cause of a dark section."
5. **Individual LEDs / lumen droop** — slowest mode; the ≤50 cap makes this a non-issue for years.
   → **The ≤50 brightness cap is your single best free move** (Arrhenius: cuts LED-junction,
   driver-IC, PSU, and cap stress all at once). Keep it; drop lower at night.

## DESIGN-IN-NOW (impossible/painful to retrofit behind a mounted wall)

### Connectors & cabling — the dominant long-term risk
- **Retain every one of the ~17 HUB75 connectors** — a zip-tie/clip/side-bead of hot glue
  capturing the *housing* (not the mating faces, so it stays serviceable). No latch + tin
  contacts + daily thermal cycling → fretting corrosion → flicker over years; gravity walks
  them off a vertical wall.
- **First anchor point 50–100 mm from every connector** (screw-down `cable_clip`, Part 8 —
  **NOT adhesive**, which peels in a warm gap and drops the load onto the connector).
- **Cut every cable with a 25–75 mm service loop** at each end — for service *and* to absorb
  daily thermal expansion. You cannot add slack to an exact-cut cable. (The ~15 % slack in the
  10 AWG cut list stays.)
- **Support each vertical drop's weight at the top** so no pigtail/​ribbon header ever bears cable weight.
- **Ribbons: keep short, turn corners with a rolled U-fold (never crease — ~40 % of cable
  failures are bend-radius), engage the IDC strain clip, don't zip-tie tight across the flat.**
- **Bootlace ferrules** on every stranded wire into a screw terminal; **crimp, never solder-only**
  (ABYC — solder melts at a hot joint + fatigue-cracks).
- **Latching JST-VH panel plugs** + strain relief on each pigtail.

### Electrical
- **CCA trunk decision (corrects "skip the paste"):** for a permanent wall, either **(a)** treat
  every CCA joint — **Noalox anti-oxidant + AL/CU-rated crimp lugs + the re-torque schedule** —
  **or (b)** swap the ~7 m trunk to real copper. Ampacity is fine for the 16 A branches; the risk
  is termination oxidation/​creep over years. Pick one; don't leave CCA bare-crimped.
- **Belleville (or Nord-Lock) + flat washer** under the PSU-stud/​busbar nuts (split lock-washers
  are proven useless) to hold preload against creep. LRS-350 studs = **M3.5 @ 8–10 kgf·cm**.
- **Single-point V− star + isolated V+** (already designed): both PSU negatives + both chain
  returns + Pi/​Bonnet GND to ONE node; never a second ground path; never tie the two V+.
- **Space for bulk decoupling caps** at the two center feed points (~2200–4700 µF low-ESR ≥16 V)
  — cuts scan-transient flicker/​sag; cheap, but leave the terminals reachable.
- **Per-branch positive fusing** (7.5 A, already designed); **stagger PSU turn-on** for cold-start inrush.
- **Leave every terminal + fuse block physically accessible** (the removable valance does this) for
  re-torque + IR scans.

### EMI — wire it out now
- **Run wired Ethernet as the primary link and disable/​strip WiFi** (`dtoverlay=disable-wifi`,
  remove bluez/​pi-bluetooth). A 16-panel HUB75 wall is a broadband RF emitter right next to the
  Pi; the Pi-4 2.4 GHz desense is Foundation-documented. 5 GHz is a fallback, **Ethernet is robust.**
- **Shortest ribbons; route the ~17 ribbons on a SEPARATE path from the 60 A DC bus** (cross at
  right angles, don't bundle) → less crosstalk/​ghosting + less coupling into the Pi.
- **Grounded metal backing behind the Pi/​Bonnet**; keep the Pi as far from the panel mass as wiring allows.

### Thermal / structural
- **Make the ceiling gap a chimney:** the valance's top vent reveal is the exhaust — **add a low
  intake** so warm air convects out (optionally a quiet low-RPM exhaust fan). Retrofitting airflow
  into a closed 5 m gap is the painful one. Panels don't need active cooling; the *trapped ambient* does.
- **PSUs at the ends, lying flat, both short-end vents clear, ≥10–15 cm air.**
- **Service access:** a single panel pulls off the front (magnets) and a PSU pulls out (removable
  valance) without dismounting the 5 m row — verify this holds in the final layout.

## ADD-LATER / TUNE AFTER POWER-ON (cheap, non-structural)
- **Clip-on ferrite cores** (common-mode, whole-ribbon) at the Bonnet end of each ribbon + on DC
  leads at the panel input — add/​adjust after observing behavior. Don't over-wind power leads.
- **Software:** `--led-slowdown-gpio=2`(+), `--led-pwm-lsb-nanoseconds` 150–300 (ghosting),
  `--led-pwm-bits`, `--led-limit-refresh`, `isolcpus=3`, `dtparam=audio=off`. Favor a rock-solid
  100–150 Hz over a jittery max.
- **Dielectric/​contact grease** in the HUB75 + JST plugs (anti-fretting; do at assembly if you can).
- **Bulk decoupling caps** installed at the feed points.
- **Blue (low-strength) Loctite** on threads *where the ring terminal, not the thread, carries current.*

## SPARES KIT (buy the panels NOW; rest as budget allows)
- **1–2 same-batch P5 64×32 panels** ← the one that must be bought with the order (batch-matching
  avoids driver init incompatibilities). The not-repairable, single-point-of-visible-failure item.
- 1 spare **LRS-350-5**, 1–2 spare **80 mm fans**, a few short **HUB75 ribbons**, 1 spare **Triple
  Bonnet**, 1 spare **Pi 4 + a cloned SD image** (SD corruption on 24/7 Pis is common).

## MAINTENANCE (low-effort, high-payoff)
- **~30 days after commissioning: re-torque every power terminal** (thermal-creep settle-in — the
  single most valuable free action). Then **annually.**
- **Every 3–6 months:** listen for PSU fan noise, blow out dust, re-seat any backed-out connectors.
- **Annually:** IR-scan the terminals/​fuse blocks under load (a phone-clip thermal cam) — a hot
  terminal is a loosening one; check PSU case temp + output voltage under load.
- **The tell:** end-of-chain pixels going pink/​dim = 5 V droop → add injection/​trim, not a panel swap.

## Small shopping additions from this pass
Noalox (if keeping CCA) · bootlace ferrules + crimper · Belleville/​Nord-Lock washers (M3.5) ·
latching JST-VH housings · dielectric grease · a bag of clip-on ferrite cores · bulk low-ESR caps ·
an Ethernet run to the Pi · the spares above. (Screw-down `cable_clip` prints free.)
