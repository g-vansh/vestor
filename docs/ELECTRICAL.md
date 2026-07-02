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
- **Value:** **5 A is the safe default** — at ≤50 brightness it runs at 20–40 %, never
  nuisance-blows, and stays under a thin-CCA pigtail's ~5–10 A rating. **7.5 A (on hand) is
  fine IF the pigtail is ~18 AWG _copper_** (~10 A ampacity) — then it clears the load with
  more margin. **Confirm before choosing:** (a) clamp-meter one panel at full white/full
  bright for the real max, (b) read the pigtail's gauge (thin CCA → 5 A; ≥18 AWG copper → 7.5 A OK).
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
- **5 A ATC blade fuses** (×16 + spares) — unless a clamp-meter reading + pigtail gauge confirm the
  pigtail is ~18 AWG copper, in which case the **7.5 A already on hand** are fine.
- **10 AWG red/black copper** for the 4 trunk runs (confirm your existing red/black isn't thinner).
- **Anti-oxidant paste + crimp ferrules** for the CCA forks.
- **One negative bus bar** to bond the two PSU V− + Pi GND at a single point (unless a fuse block
  has an integrated ground bus — **check your block type**).
- **On hand & correct:** 4× 6-way fuse blocks (16/24 slots used), yellow 10–12 AWG ring terminals,
  the 2× LRS-350-5, the panel pigtails.

## Confirmed on the owner's parts (2026-07-02)
- Fuse blocks are **enclosed with a built-in negative bus** → no separate bus bar needed; touch-safe.
- Red/black trunk cable is **10 AWG** → correct for the trunks.

## Still to measure (decides the fuse value)
1. **One panel's full-white/full-bright current** with a clamp meter → the real per-panel max (~2.4–4 A expected).
2. **The pigtail's own gauge** (not the trunk): thin CCA → 5 A fuses; ~18 AWG copper → the 7.5 A on hand are fine.

## Sources
Mean Well LRS-350 datasheet + Enclosed-Type install manual; hzeller rpi-rgb-led-matrix `wiring.md`
(4.5 V floor, ≤50 mV target, star, common ground); AusChristmasLighting (fuse-the-wire, 5 A pixel
wire, "where goes data, so goes V−", power injection); electricaltechnology.org AWG tables; VoltPlan
fuse sizing; Romtronic/MWS/IAEI on CCA ampacity. (Full URLs in the research transcripts.)
