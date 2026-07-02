# VESTOR — ELECTRICAL / POWER WIRING (validated)

The wiring plan for the 16-panel wall, verified by two cited research passes (power
distribution + fusing; LRS-350-5 datasheet + placement + grounding). Diagram source of
truth: the labeled wiring diagram in the design session. Companion: `INVENTORY.md` §9
(BOM), `HARDWARE.md`. **Safety-critical — read before wiring.**

## Architecture (one line)
**Split the two PSUs (one per 8-panel half) → each feeds 2 fuse blocks in a STAR with
10 AWG copper → every panel gets its own 5 A fuse → bond both PSU negatives + the Pi
ground at one point, keep the positives isolated → trim each PSU to ~5.15 V.**
Data is independent: a single HUB75 chain of 16 from the corner.

## The decisions, with the numbers

### 1. Fuses — YES (per panel, 16 total). Value depends on the pigtail gauge.
- The LRS-350-5's own protection (OCP at **110–140 % → ~66–84 A**, hiccup auto-recover;
  **no 150 % peak on the 5 V model**) protects the *supply*, not your wiring. A soft short
  (pinched/chafed pigtail, backed-out terminal) draws **10–25 A** through a ~4 A pigtail and
  the 60 A PSU **never trips** → the wire becomes a cartridge heater. **The fuse protects the
  WIRE.** Genuine fire-safety item for a 24/7 wall.
- **The load:** each pigtail carries only its OWN panel (power is injected per-panel, not
  chained). A 64×32 P5 draws **~3.5–4 A at full white / 100 % brightness** (1/16 scan → 384
  sub-LEDs lit at once × ~10 mA; ~2.4 A measured on comparable panels), and only **~1–2 A at
  brightness ≤50**. So the fuse must sit **just above ~4 A** and **below the pigtail's ampacity**.
- **Value = 7.5 A (the fuses on hand).** The pigtail is marked **"RV … 450/750 V"** — RV is the
  Chinese spec for **solid-copper-core** flexible PVC wire, and it's already sized to carry the
  panel's own ~4 A, so it's ≥0.5 mm² copper (~7–10 A ampacity). **7.5 A > the 4 A load and ≤ the
  wire's ampacity → it protects the wire.** (5 A only if the pigtail turns out to be a thin
  0.3 mm² — glance at the mm² printed on it to be 100 % sure; ≥0.5 mm² → 7.5 A.)
- **Per-panel, not per-group.** A group fuse must be ≥16 A (4 panels) so it never blows for
  one panel's thin-pigtail short. 24 slots / 4 blocks → **16 fuses, one per panel**, 8 spare.

### 2. Trunks — **10 AWG copper**, star topology
Budget <3 % of 5 V (<0.15 V). Per-branch (4 panels ≈ 16 A) over ~2 m round-trip:

| Gauge | drop @16 A/2 m | % | verdict |
|---|---|---|---|
| **10 AWG** | 0.10 V | 2.1 % | ✅ use this |
| 12 AWG | 0.17 V | 3.3 % | marginal |
| 14 AWG | 0.27 V | 5.3 % | ✗ |

- **Star:** each fuse block gets its own 10 AWG feed straight off the PSU terminals (not
  daisy-chained). 2 branches × 4 panels per half → 4 trunks total.
- **Owner's trunk = 10 AWG CCA** (copper-clad aluminum, 25 ft spool). CCA carries like ~12 AWG
  copper, so drop is a bit higher — **acceptable here** given split PSUs, short runs, ~5.2 V trim,
  and the dark brightness-≤50 content (real ~4–8 A/trunk → <0.15 V; only a pathological all-white
  16-panel frame would droop, which this board never shows). Keep each block **close to its PSU**.
- Your **yellow 10–12 AWG ring terminals** land the trunk on the PSU/​block studs (ring > fork on
  the high-current lugs; ≤2 wires per screw, ~8 kgf-cm). CCA → crimp + anti-oxidant + re-torque.

### 3. Placement — **SPLIT** (one PSU per half), each on its own AC outlet
- Split makes every panel ≤2.5 m from its PSU (vs ≤5 m). At 10 AWG, split holds ≥4.48 V even
  at a full-white 32 A worst case; **both-at-left on 10 AWG collapses to ~3.95 V (broken)** and
  would need 8 AWG + trim to barely reach ~4.3 V. Split = less copper, better voltage.
- **Separate AC outlets / circuits.** Steady draw is tiny (~1–2 A AC each at real load), but
  **cold-start inrush is ~60 A per unit** (2025 datasheet; older rev said 45 A — check your
  label). Two at once ≈ 120 A can pop a cheap switch → give each its own switched outlet, or
  stagger power-on. Left PSU + Pi in the corner, right PSU at the far end — outlets fall out
  naturally. Fan is thermostatic (on ≥50 °C) → usually silent at your <15 % load; keep 10–15 cm
  vent clearance. **Tie each PSU's FG to mains earth** (separate from the DC ground below).

### 4. Grounding — bond V−, isolate V+
- **Bond both PSU negatives (V−) together AND to the Pi/Bonnet GND at ONE star point.** HUB75
  data is ground-referenced across all 16 panels — "where goes data, so goes V−." A missing/
  divergent V− return gives flicker and "interesting random lighting."
- **NEVER connect the two V+ together.** Two 5 V supplies are never exactly equal → tying V+
  back-feeds circulating current into the lower unit (unequal load, OVP/hiccup trips). Each PSU
  owns only its half's V+.

### 5. CCA pigtail handling (the #2 ignition source after an unfused short)
- **Crimp, never solder** CCA (flux eats the cladding → cold joint). **Don't tin** the fork ends
  (solder creep loosens screw terminals). **Anti-oxidant paste** (Noalox-type) on the aluminum,
  and **re-torque the terminals after a few days** of thermal cycling. Keep pigtails as short as
  they ship (~55 cm); land them into crimped copper trunk — don't extend the CCA.

## Are the pigtails long enough? Yes — because the blocks are distributed
~55 cm reaches ±1.7 panels, so **one fuse block per 4-panel group** (blocks A/B/C/D at panels
1–4, 5–8, 9–12, 13–16) puts every panel within reach of its block. Distributing the 4 blocks is
what makes the pigtails sufficient *and* keeps the 10 AWG runs short. (Putting all blocks in the
corner would strand the far pigtails 5 m away.)

## Shopping delta (vs what's on hand) — almost nothing
- **Anti-oxidant paste** (Noalox-type) + crimp ferrules for the **CCA trunk** terminations.
- (Maybe) a 2nd trunk spool if the 4 runs exceed ~7.6 m — probably not; and confirm enough ring/fork terminals.
- **NOT needed:** 5 A fuses (the 7.5 A work), a negative bus bar (block has one), copper trunk (10 AWG CCA is fine here).
- **On hand & correct:** 4× 6-way fuse blocks (built-in +/− bus, 16/24 slots used), 10 AWG CCA trunk,
  yellow 10–12 AWG rings, copper RV pigtails, 2× LRS-350-5, 100 cable ties + adhesive mounts.

## Confirmed on the owner's parts (2026-07-02, from photos)
- **Fuse blocks (×4): 6-way, enclosed, blown-fuse LEDs, with BOTH a fused +bus and a −ground bus.**
  Terminal map: **bottom center stud = + IN** (PSU +5 V trunk), **top center stud = − IN** (PSU ground),
  **6 side screws = fused + OUT** (one per panel), **6 top-bar screws = − ground OUT** (one per panel).
  → no separate negative bus bar needed; each block = a complete +fused/−bus unit for ≤4 panels.
- **Trunk cable = 10 AWG CCA** (TYUMEN, 25 ft) → acceptable (see §2).
- **Pigtail = "RV … 450/750 V" = solid-copper flexible wire** → **7.5 A fuses on hand are the right value.**
- **100 cable ties + adhesive mounts** → dress the HUB75 ribbons + power leads along the rails so nothing
  sags or **chafes** (chafing is the fault the fuses guard against — tidy routing is itself a safety measure).

## Only thing left to eyeball
- The **mm² printed on the pigtail** (≥0.5 mm² → 7.5 A good; a rare 0.3 mm² → drop to 5 A). Optionally
  clamp-meter one panel at full white to log the real per-panel max (~2.4–4 A expected).

## Sources
Mean Well LRS-350 datasheet + Enclosed-Type install manual; hzeller rpi-rgb-led-matrix `wiring.md`
(4.5 V floor, ≤50 mV target, star, common ground); AusChristmasLighting (fuse-the-wire, 5 A pixel
wire, "where goes data, so goes V−", power injection); electricaltechnology.org AWG tables; VoltPlan
fuse sizing; Romtronic/MWS/IAEI on CCA ampacity. (Full URLs in the research transcripts.)
