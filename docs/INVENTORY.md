# INVENTORY — Vestor 16-panel wall

Inventory & gap analysis. Compiled 2026-06-21, **revised 2026-06-21** against the
owner's actual on-hand list. Sources: `HARDWARE.md`, `AGENTS.md`, `BUILD_LOG.md`
(2026-06-14 power research), Adafruit product pages (6358 / 1466), Mean Well
LRS-350-5 datasheet/listings.

> **Headline:** you have the **brains and the panels but none of the panel
> power**. The 2× LRS-350-5 supplies were specced but never bought — so right now
> you can't light even **one** panel. The Triple Bonnet has no power path; the
> panel's 5V must come from a dedicated supply. **Buy ≥1 LRS-350-5 + an AC cord to
> unblock Phase 0; 2 supplies + a distribution harness + a frame for the full wall.**

---

## 1. WHAT I HAVE  ✅

| Item | Spec | Status |
|---|---|---|
| Raspberry Pi 4 Model B | brain; runs the hzeller driver | ✅ on hand |
| Adafruit **Triple** RGB Matrix Bonnet | PID 6358, "active3", 3 chains, **no onboard power** | ✅ on hand |
| **45 W USB-C power supply** | powers the **Pi** (and the bonnet's logic via the Pi header) | ✅ **fine** — see note below |
| microSD card | flashed with the Vestor image | ✅ on hand |
| P5 HUB75 panels ×16 | 64×32, FM6124D, 1/16 scan; each ships w/ ribbon + 1 power cable per 2 panels + magnet screws | 📦 **arriving tomorrow** |

**45 W USB-C note:** safe and more than enough. A spec-compliant USB-C PD charger
outputs **5 V by default** and won't go above 5 V unless the device negotiates up
— which the Pi 4 never does. The Pi 4 only draws ~15 W, so the extra headroom is
harmless. Just make sure it's a reputable/compliant unit (the official Raspberry
Pi 45 W is ideal).

---

## 2. WHAT I NEED

### 🔴 BUY NOW — unblocks Phase 0 *and* required for the wall anyway
| Need | Spec | Qty | ~Cost | Why |
|---|---|---|---|---|
| **Mean Well LRS-350-5** PSU | 5 V / **60 A** / 300 W | **2** (1 lights Phase 0; 2 for the full wall) | ~$33 ea | The only thing that can power the panels. Stocked at DigiKey/Mouser/Arrow/Jameco/Amazon. |
| **AC mains cord** for the PSU | fork/ring-lug or IEC tail → 120 V; fused+switched is nicer | **2** (1 per PSU) | ~$8 ea | LRS-350-5 has bare L/N/⏚ screw terminals — needs a mains cord. |

> With **one** LRS-350-5 + **one** AC cord you can do **Phase 0** the day the panels
> land: wire PSU → the panel's power cable, panel ribbon → bonnet **Port 1**, Pi on
> its 45 W USB-C, and run the single-panel flight test.

### 🟠 BUY BEFORE THE FULL WALL — power-distribution harness
The Triple Bonnet has no power connector; Adafruit's guidance is to feed panels
directly from the supplies via **"two power distribution bus bars,"** injecting 5 V
in parallel at every panel/pair (never daisy-chain panel power through HUB75).

| Need | Spec | Notes |
|---|---|---|
| **Bus bars** ×2 (one 5 V + GND pair per PSU) | screw-down distribution blocks, ≥60 A | Split the wall: **PSU1 → 8 panels, PSU2 → 8 panels** (don't force two LRS to current-share one rail). |
| **Trunk wire** | **pure-copper AWG 6** (red + black) PSU→bus bar; AWG 8/10 taps bus→panels | **Buy copper, not the included CCA pigtails** (see §7.1). Inject 5 V at **≥2 points per 8-panel half (4 total)**; center each PSU behind its half. |
| **Crimp lugs / ferrules** + crimp tool | fork lugs (PSU), ring/spade (bus bars) | **Crimp, never solder** — included CCA won't tin. Use CCA only for the final short bus→panel hop. |
| **Common-ground bond** | AWG 14 | Tie PSU1 ⏚ ↔ PSU2 ⏚ ↔ Pi/bonnet ⏚ — **mandatory** for clean HUB75 signals (signal reference, *not* a 5 V-rail parallel). |
| *(recommended)* inline fuse / DC breaker per 5 V output | **~40 A per PSU** | Each PSU half draws ~32 A @ BRIGHTNESS 50; fault protection for a wall-mounted 600 W appliance. |
| *(NOT needed)* 3rd LRS-350-5 | — | Only for *unrestricted all-white*. The `BRIGHTNESS=50` cap keeps worst case ~64 A < 120 A. Skip it. |

### 🟡 BUY BEFORE THE FULL WALL — mounting / structure
| Need | Notes |
|---|---|
| Rigid frame/backing | **Layout LOCKED: one continuous 16-wide row ≈ 5120 × 160 mm** (16.8 ft). Panels mount via magnets + 4 corner standoffs onto a flat steel/rigid substrate — needs a stiff backbone (aluminium extrusion / unistrut / t-slot) to resist sag over that span. Mount **Pi + bonnet at the center-back**; **PSU1 behind the left half, PSU2 behind the right half**. |
| ~~HUB75 ribbon extensions~~ | **Not needed** — the center-mount 2×8 topology (§5) keeps every ribbon short (bonnet→center panel, then panel-to-panel). The included short data cables suffice. |

---

## 3. WHAT TO RETURN

### ✅ Adafruit 5V **4A** supply — PID **1466** ("P1466") — *already returning, correct call*
It's the companion for the **single** Matrix Bonnet (3211), not the Triple. On the
6358 there's **no barrel jack to plug it into**, its logic comes from the **Pi
header**, and at **4 A** it's ~6 % of one LRS-350-5 (one P5 panel alone can pull
~8 A all-white). It also can't power the Pi 4 (USB-C, not barrel). Redundant on
connector, role, and capacity. Refund ≈ $14.95.

> *Optional:* if you want to flash-test a single panel **before** the LRS-350-5
> arrives, the P1466 + a $2 "barrel-jack → screw-terminal" adapter can limp one
> panel at low brightness. But real Phase-0 tuning should use the LRS-350-5 (a
> sagging 4 A supply causes flicker/color shift that masquerades as driver-config
> bugs). If you don't want the hassle, just return it.

---

## 4. STATUS SUMMARY

| Phase | Can I do it with what I'll have tomorrow? |
|---|---|
| **Phase 0** (light 1 panel) | ❌ **Blocked** — need **1× LRS-350-5 + 1× AC cord**. Everything else (Pi, bonnet, SD, 45 W, panel, ribbon) is ready. |
| **Phase 1** (full 16-panel wall) | ❌ Need **2× LRS-350-5 + AC cords + 2 bus bars + trunk wire + lugs + ground bond + a frame**. |

---

## 5. LAYOUT — LOCKED: one long 16-wide row, fed from the center  (2026-06-21)

**Decision: one continuous 1024×32 row (5120 × 160 mm), Pi+bonnet center-mounted,
2 chains of 8 daisy-chaining outward** (`--led-parallel=2 --led-chain=8`).

**Why not 3 chains of 6+5+5?** HUB75 ribbons must stay **< 50 cm** (raw parallel
TTL @ ~15–20 MHz, no differential signaling). All three bonnet ports sit at one
physical point; splitting 6+5+5 from one end forces **1.9 m / 3.5 m jump cables**
to the starts of chains 2 & 3 → corruption + flicker. Center-feeding two 8-panel
chains keeps **every** ribbon short.

| Item | 2×8 center-feed (chosen) | 3×6 from end | 1×16 single chain |
|---|---|---|---|
| Long jump cables | **none** ✅ | 1.9 m + 3.5 m ❌ | none ✅ |
| Wasted panel slots | **0** (16=2×8) ✅ | 2 (needs 18) | 0 ✅ |
| Refresh on Pi 4 | hundreds of Hz ✅ | highest | marginal/flicker ⚠️ |
| Assembly wrinkle | left 8 mounted **180°** + SW flip | rotation/long cable | none (simplest) |

**Bring-up plan:** validate first-light with the dead-simple `parallel=1 chain=16`
(no rotation), then switch to the 2×8 center-feed for the real install.

**Software:** we own `display/__init__.py`, so the hardware canvas is **512×64**
(chain 0 = left half, chain 1 = right half); compose the 1024×32 render into it
with the **left half rotated 180°** (snake). Replaces the earlier 6+5+5
`pixel_mapper_config` TODO in `BUILD_LOG`.

**Confirm on the bench:** which HUB75-D connector is IN vs OUT (sets the daisy
direction and which half needs the 180° flip).

---

## 6. PANEL-SPEC COMPATIBILITY — verified 2026-06-21
Against the actual MUEN P5 listing + module-back photos (chip marked `FM6124(Z)D`).

| Spec | Verdict | Note |
|---|---|---|
| 64×32, 320×160 mm, P5 | ✅ | 16 wide = 1024 px ribbon (matches sim/design). |
| 1/16 scan, **A–D** address (no E) | ✅ | `row_addr_type=0`. No E line is correct for 64×32. |
| HUB75-D pinout (R1 G1 / B1 GND / R2 G2 / B2 GND / A B / C D / CLK LAT / OE GND) | ✅ | Standard HUB75 — plug-compatible with the bonnet. |
| Driver **FM6124(Z)D** | ✅ start standard | FM6124 ≈ ICN2037/2038S, **no init**. `panel_type` empty. Fallback `FM6126A`→`FM6127` only if black. |
| Chip "Epstar" | ✅ | LED *emitter* die brand, not the driver. Nothing to set. |
| 2 HUB75 connectors / module | ✅ | IN + OUT loop-through (daisy). |
| 5 V, SMD2121, "1-for-2" power cable | ✅ | 8 power cables / 16 panels, fed from LRS-350-5. |

**Likely bring-up tweaks (not blockers):** (1) **R/B swap** is common on FM6124 →
set `--led-rgb-sequence` (`RBG`/`BGR`); (2) raise `--led-slowdown-gpio` 4→5 if
flicker. The scary `row-addr-type=3` / forced-`FM6126A` GitHub cases are all
**128×64 1/32-scan "ABC"** panels — **not** this 64×32 1/16-scan A–D panel.

---

## 7. WHAT THE ALIEXPRESS REVIEWS CHANGE — sourcing & QC, NOT compatibility  (2026-06-21)

Reviewed the listing's reviews + seller back-photos (NovaStar receiving-card demo
rig, power-wiring guide, customer module-back shots). **Verdict: nothing here
changes panel compatibility (§6) or the locked 2×8 center-feed topology (§5).**
The complaints are the well-known failure modes of cheap HUB75 panels — wire
quality, shade variation, and unit-to-unit defects. Three hardening actions:

### 7.1 The included power pigtails are probably CCA — buy copper, inject at ≥2 points/half
Reviewers report **copper-clad-aluminum (CCA)** power leads: ~**1.55–1.6× copper
resistance** and **won't take solder** (aluminum-oxide skin). Voltage-drop math
(5 V rail, far panel must stay ≥4.8 V → **≤0.2 V budget**):

| Wire | Current | Length | Drop | Verdict |
|---|---|---|---|---|
| AWG10 Cu | 32 A | 2.5 m | **0.52 V** | ❌ far panels redshift |
| AWG10 Cu | 64 A | 2.5 m | 1.05 V | ❌❌ |
| AWG8 Cu | 32 A | 0.64 m | **0.085 V** | ✅ |

**Starvation looks exactly like a driver bug** — blue dies sag first, so an
under-fed panel tints **red**. Don't chase it in software. Fix is geometry + copper:
- **Center each PSU behind its own half** (PSU1 ≈ behind panel 4, PSU2 ≈ behind panel 12) so the longest run is ~4 panels, not 8.
- **Inject 5 V at ≥2 points per 8-panel half = 4 injection points total** (parallel taps off the bus bar, never daisy-chain panel power through HUB75).
- **Keep the two PSU 5 V positives ISOLATED** — don't parallel two LRS-350-5 onto one rail (they won't current-share; one hogs, the other coasts). Split is already PSU1→left 8, PSU2→right 8.
- **Common-ground bond still mandatory** (PSU1 ⏚ ↔ PSU2 ⏚ ↔ Pi/bonnet ⏚) — that's a *signal-reference* tie, not a power-rail parallel.
- **~40 A fuse per PSU 5 V output.** **Crimp, never solder, the CCA** (won't tin). Use included CCA pigtails only for the final short bus→panel hop; trunks = pure-copper **AWG6**, taps **AWG8/10**.

### 7.2 Panel-to-panel shade variation is real — and hzeller can't fully fix it
"Different shades between panels" reviews are accurate. **Every hzeller color/brightness
knob is GLOBAL** — `--led-brightness`, `--led-pwm-bits`, `--led-pwm-lsb-nanoseconds`,
`--led-pwm-dither-bits`, `--led-rgb-sequence`, CIE-1931 (on by default). There is
**no per-panel / per-region LUT, no gamma or color-temp flag, and no way to ingest
a NovaStar `.cal` file**. True per-panel correction = hand-patching `MapColors()`
with per-coordinate R/G/B multipliers (hzeller issues #222, #193) — a DIY fork, last
resort. Mitigations, in priority order:
1. **Insist on one batch / one production lot** (biggest lever).
2. **Run lower brightness** (BRIGHTNESS≈50 already planned — also fixes §7.1 sag).
3. **Favor dark/sparse content** — our departure-board aesthetic (black bg, text/logos) hides field uniformity far better than full-screen color would.
4. **Warm up** before judging; LED tint drifts in the first minutes.
> Accept it won't match a NovaStar-controlled wall. At low brightness on black, fine.

### 7.3 Incoming-QC protocol — test all 16 BEFORE mounting (do tomorrow)
DOA units, dead ICs, dead rows, and stuck-color data faults all show up in reviews.
Test each panel **individually** on Port 1 with the hzeller `demo`:

```
sudo ./demo --led-cols=64 --led-rows=32 --led-chain=1 --led-parallel=1 \
            --led-gpio-mapping=regular --led-slowdown-gpio=4 \
            --led-brightness=80 -D <mode>
```

| Mode | Shows | Fault signature |
|---|---|---|
| `-D3` | DOA / first-light | whole panel black = DOA (try `--led-panel-type=FM6126A` before condemning) |
| `-D4` | per-channel color cycle | missing R/G/B = bad channel / RGB-seq |
| `-D5` | grayscale ramp | **16-px-wide dead column = dead FM6124 IC**; horizontal line = dead row; uneven = shade |
| `-D11` | brightness pulse | flicker/lag on one IC = weak driver |
| `-D0` | rotating square | tearing/wrong position = addressing/`row_addr_type` |

- **Log panels 1–16** (pass / which fault). **Keep 2–3 as spares** (~10–15%).
- **RMA defects with video** inside the AliExpress Buyer-Protection window — push for **partial refund without return** (shipping a panel back rarely worth it).
- Verify included counts on unboxing: **8 power cables** (1-for-2) + **~15 data ribbons** (15 inter-panel links for 16 panels) + magnet screws.
