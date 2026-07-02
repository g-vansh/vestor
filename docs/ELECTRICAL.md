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

### 1. Fuses — YES, and use **5 A**, not the 7.5 A on hand (per panel, 16 total)
- The LRS-350-5's own protection (OCP at **110–140 % → ~66–84 A**, hiccup auto-recover;
  **no 150 % peak on the 5 V model**) protects the *supply*, not your wiring. A soft short
  (pinched/chafed pigtail, backed-out terminal) draws **10–25 A** through a ~4 A pigtail and
  the 60 A PSU **never trips** → the wire becomes a cartridge heater. **The fuse protects the
  WIRE.** Genuine fire-safety item for a 24/7 wall.
- **Value = 5 A.** The panel pigtails are thin **CCA** (copper-clad aluminum); a "18 AWG" CCA
  wire carries like ~20 AWG copper → real ampacity ~5–10 A. 5 A > the ~4 A full-white draw
  (no nuisance blows) and ≤ the pigtail's ampacity. **7.5 A is too high** — the wire cooks
  before it blows. → **Buy 5 A ATC blade fuses; set the 7.5 A aside.**
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
- Your **yellow 10–12 AWG ring terminals** are correct for the PSU M3.5 studs (ring > fork on
  the high-current V+/V− lugs; ≤2 wires per screw, ~8 kgf-cm torque). **Verify your red/black
  cable is actually 10 AWG** — thin cable is the one thing that breaks this.

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

## Shopping delta (vs what's on hand)
- **5 A ATC blade fuses** (×16 + spares) — the 7.5 A are the wrong value for these pigtails.
- **10 AWG red/black copper** for the 4 trunk runs (confirm your existing red/black isn't thinner).
- **Anti-oxidant paste + crimp ferrules** for the CCA forks.
- **One negative bus bar** to bond the two PSU V− + Pi GND at a single point (unless a fuse block
  has an integrated ground bus — **check your block type**).
- **On hand & correct:** 4× 6-way fuse blocks (16/24 slots used), yellow 10–12 AWG ring terminals,
  the 2× LRS-350-5, the panel pigtails.

## Two things to confirm on your actual parts
1. **Fuse-block type** — positive-only (need a separate negative bus bar) vs has a ground bus. (A photo answers it.)
2. **Red/black cable gauge** — must be 10 AWG for the trunks.

## Sources
Mean Well LRS-350 datasheet + Enclosed-Type install manual; hzeller rpi-rgb-led-matrix `wiring.md`
(4.5 V floor, ≤50 mV target, star, common ground); AusChristmasLighting (fuse-the-wire, 5 A pixel
wire, "where goes data, so goes V−", power injection); electricaltechnology.org AWG tables; VoltPlan
fuse sizing; Romtronic/MWS/IAEI on CCA ampacity. (Full URLs in the research transcripts.)
