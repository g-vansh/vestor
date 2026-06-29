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
| **45 W USB-C power supply** | powers the **Pi** (and the bonnet's logic via the Pi header) | ✅ **fine** |
| microSD card | flashed with the Vestor image | ✅ on hand |
| **CnGear P5 HUB75 panels ×16** | 64×32, FM6124HJ, 1/16 scan; framed; each w/ data ribbon + fork-terminated power pigtail | ✅ **on hand (arrived 2026-06-29)** |
| **Mean Well LRS-350-5 ×2** | 5 V / 60 A / 300 W | 🛒 **ORDERED 2026-06-29** (Wired Watts #225740, $29.50 ea; up to 10 biz days) |
| **Double Stack Mount Kit** (Mean Well) | bracket to stack the 2 PSUs | 🛒 ordered (same order, $4.50) |
| **Panel Magnets ×100** | $0.10 ea screw-in magnet feet for the M3 holes | 🛒 ordered (same order, $10) — enables magnetic mount (96 = 6×16) |

> ⚠️ **GAP in that order: NO AC power cords.** The LRS-350-5 has bare L/N/⏚ screw
> terminals — it needs a **2× AC mains cord** (3-conductor, US plug → bare/fork ends,
> ~$8 ea) to power on. Order 2. Also: set each PSU's input-voltage switch to **115 V**.

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

### 🟠 BUY BEFORE THE FULL WALL — power distribution (SIMPLIFIED, no-solder — see §9)
**Revised 2026-06-29 (3-agent optimization pass).** The panels each ship a
**fork-terminated power pigtail**, so distribution collapses to **one automotive
fuse block per PSU** — no bus bars, no copper trunks, no crimping, no soldering.

| Need | Spec | Notes |
|---|---|---|
| **8-way ATC fuse block** w/ negative bus | ~20 A/circuit, ~75 A/block, ~$16 | **1 per PSU** (PSU1→8 panels, PSU2→8). Panel **+5 V forks land on the fused studs, GND forks on the common negative bus** — the block's negative bus *is* your ground bus. One fuse per panel. |
| **ATC blade fuses** | **7.5 A** (10 A if running bright) | one per panel + spares; panel ≈4 A @ BRIGHTNESS 50. |
| **10 AWG wire**, red + black | short PSU→block jumpers | Carries the **full ~32 A per PSU** (current only splits *after* the fuses → NOT 14 AWG). Land bare stranded under the screws — no lugs needed. |
| **Common-ground bond** | 14 AWG | One jumper **PSU1 V− ↔ PSU2 V−**; the bonnet already ties Pi GND to panel GND via the ribbon. Bare wire under screws. |
| *(NOT needed)* Hanson distro / bus bars / crimp tool / ferrules / copper AWG6 / 3rd PSU | — | All cut. Fork terminals + screw blocks do it all, solder-free. Keep `BRIGHTNESS≤50` so each PSU sits ~32 A < 60 A. |

### 🟡 BUY BEFORE THE FULL WALL — mounting / structure
| Need | Notes |
|---|---|
| Rigid frame/backing | **Layout LOCKED: one continuous 16-wide row ≈ 5120 × 160 mm** (16.8 ft). **WON'T free-stand — wall-mount above the window** (full buildout + parts list + cost in **§8**). TL;DR: **80/20 1530 backbone** ≈ $210, panels **screwed** via M3 corner holes (magnets need a ferrous sheet — see §8.2), supported every 3–4 ft so it stays flat. Mount **Pi + bonnet at center-back**; **PSU1 behind the left half, PSU2 behind the right half**. |
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

## 6. PANEL-SPEC COMPATIBILITY — ✅ VERIFIED FROM PHYSICAL PANELS 2026-06-29
The 16 panels arrived. Specs read directly off the boards (photos IMG_3780-82).
**Brand: CnGear. Board rev: JHT2.0. Marking: `P5(2121)64×32-16S-JHT2.0`.**

| Spec | Verdict | Note (verified on-board) |
|---|---|---|
| 64×32, P5, **SMD2121** | ✅ | silkscreen `P5(2121)64×32`. 16 wide = 1024 px (matches sim/design). |
| 1/16 scan (`16S`), **A–D** address (no E) | ✅ | `row_addr_type=0`. Pinout table shows only A B C D — confirms standard 1/16. |
| HUB75-D pinout (R1 G1 / B1 GND / R2 G2 / B2 GND / A B / C D / CLK LAT / OE GND) | ✅ | Read off the board's own pinout table. Standard HUB75 — plug-compatible with the bonnet. |
| Driver **FM6124HJ** | ✅ start standard | `HJ` is a package/grade variant of the standard FM6124 (≈ICN2037/2038S), **no init**. `panel_type` empty. Fallback `FM6126A`→`FM6127` only if black. (Listing image said `FM6124ZD` — same family, no impact.) |
| **HUB75-D connector**, 16-pin 2×8 | ✅ | IN + OUT per module (daisy). |
| 5 V power — **4-pin keyed plug** (`POWER`), NOT screw terminals | ✅ | Panel power cable plugs into this; PSU-side end TBD (confirm on unboxing). |

**Likely bring-up tweaks (not blockers):** (1) **R/B swap** is common on FM6124 →
set `--led-rgb-sequence` (`RBG`/`BGR`); (2) raise `--led-slowdown-gpio` 4→5 if
flicker. The scary `row-addr-type=3` / forced-`FM6126A` GitHub cases are all
**128×64 1/32-scan "ABC"** panels — **not** this 64×32 1/16-scan A–D panel.

> ✅ **MOUNTING RESOLVED (2026-06-29) — framed panels with 6 brass M3 holes.** Each
> CnGear panel has an **integrated plastic rear frame** with **6 M3-threaded brass
> holes** (4 corners + 2 mid-edge, ~144 mm vertical pitch) = the magnet-mount points.
> **No magnets shipped**, but the holes take screws/brackets. Depth ~15 mm; panels
> don't interlock. Full mounting plan + recommendation in **§8.0**. Power/data cabling
> fully included (§7.3) — the distro now mounts on the **backbone**, not a panel, so the
> 144 mm-on-panel fit is **moot**.

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

> ⚠️ **SUPERSEDED by §9 (2026-06-29):** the Hanson-distro plan below is replaced by
> **one 8-way ATC fuse block per PSU** (cheaper, fewer items, no-solder, panel forks
> land directly). The voltage-drop *principles* here still hold; the *part* changed.

**~~Recommended part for the injection points: Hanson "hanpaneldistro" fused distro
boards~~** (wiredwatts.com, ~$14.50, **US-stocked Alpharetta GA**, vetted 2026-06-21).
Each is a passive **5 V-in → 4× fused (7 A) 5 V-out** PCB that bolts to a panel back
(144 mm M3 holes). It IS a bus-bar + fuses + mount in one part — controller-agnostic
(power only; never touches HUB75), so it works identically with the Pi + Triple
Bonnet + hzeller. **Better than one 40 A PSU fuse:** per-panel fusing isolates a
shorted panel to its own 7 A fuse; panel-back mount shortens the high-current runs.
- **Derate for P5: 1 panel per 7 A fuse → 4 panels/board → buy 4 boards** (2 per PSU
  = the 4 injection points above). The listing's "up to 8 panels" is a **P10**
  (low-current) figure; our P5 pulls ~4 A (bright) to ~8 A (all-white), so a single
  white panel would pop a 7 A fuse and 8×4 A > the 30 A board. **Keep BRIGHTNESS≤50.**
- **Still need:** 2× LRS-350-5, AC cords, **copper trunk PSU→board input** (~16 A/board
  → AWG10+ Cu), and the common-ground bond. The board only does the *last leg* +
  fusing; it is **not** a PSU and does nothing for data.
- **Land** the panel pigtail's fork lug or bare wire on the screw terminals (no adapter).
- **✅ Update (2026-06-29):** each panel ships its own **fork-terminated power pigtail**,
  so **mount the distro on the BACKBONE, not a panel** — the 144 mm-on-panel fit concern
  is **moot**. Just land the blue forks (4 per board). Still worth a clamp-meter check of
  one all-white panel vs the 7 A fuse before buying 4. Bus bars + inline fuses also fine.
- **US alternatives if it doesn't fit:** Falcon3DParts distro, QuinLED Dig-Octa
  powerboard (12–16 fused outs), CZH-Labs panel-mount fuse blocks (~$15), or plain
  copper bus bars + inline ATO fuse holders (cheapest, most flexible injection points).
- **Split at the PSU too:** the Hanson **PDist1** (~$15) bolts onto the LRS-350-5 screw
  terminals (4×15A+1×10A fused) — clean injection split *at the supply*, feeding the
  panel-side boards. See `docs/GEAR.md` §1.
- **🔧 No remote-sense on the LRS-350-5 → trim its output to ~5.1–5.2 V** (front trim
  pot) to pre-compensate the run drop; target far-panel ≥4.8 V, don't exceed ~5.3 V.
  **Verify with a DC clamp meter** (most cheap meters read AC only — get a UNI-T UT210E).

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
- ✅ **CONFIRMED on unboxing (2026-06-29):** **16 data ribbons** (short 16-pin 2×8 keyed IDC, `UL2651 28AWG`) + **16 power pigtails** (1-per-panel: white 4-pin plug → **blue fork/spade** terminal at the PSU end) + **ZERO magnets**. Data + panel-power cabling is fully covered by the box — buy neither. The forks land directly on distro/bus-bar screw terminals.

---

## 8. MOUNTING & FRAME — how the wall physically hangs  (researched 2026-06-21)

> 🏗️ **Build it at MIT for free/cheap:** the owner is an MIT grad student — see
> **`docs/MIT_RESOURCES.md`** for where to cut/weld/3D-print on campus (Hobby Shop,
> MITERS, Project Manus), free materials (`reuse@mit.edu`, Swapfest), and a $500
> Design-to-Making grant. Print the corner brackets/clips, buy only the metal.

### 8.0 ✅ MOUNTING INTERFACE CONFIRMED (2026-06-29, from the real panels)
The CnGear panels have an **integrated plastic rear frame** with **6 brass M3-threaded
holes** = **4 corners + 2 mid-long-edge** (a 3-col × 2-row grid; vertical pair ≈144 mm).
**These 6 holes ARE the magnet-mount points** — the (missing) magnet feet screw into
them. With no magnets, the holes are free for screws/brackets. Panel = 320 × 158 ×
**~15 mm** deep; panels **do NOT interlock** (each mounts independently → the backbone
must set alignment/coplanarity).

**RECOMMENDED MOUNT (best for a 16-wide row): 80/20 backbone + 3D-printed brackets**
(free at MIT) that screw into the 6 brass holes and bolt into the T-slot, *locating*
each panel so the row stays coplanar + evenly gapped. No magnets, no steel backing,
and serviceable (unbolt a panel's brackets to pull it). Print designs: Printables
1294572 / 578204 (HUB75 joiners), adapt for the 144 mm hole pitch.

**Native alternative (tool-free swaps):** buy ~64 magnet feet (~$8–40, screw into the
6 holes) + face the backbone with **two steel strips** at the top-/bottom-row hole
heights; panels snap on, butt edges to align. More serviceable, but +magnets +ferrous
strip and alignment isn't positively located.

> **Provenance note (resolved):** the earlier "magnets + screws" claim came from the
> listing's "Free Parts" image. **Reality: zero magnets shipped**, but the frame's 6
> M3 brass holes mean screw/bracket mounting works regardless.

### 8.1 It will NOT free-stand — wall-mount it
A rigid strip **5120 × 160 mm (16.8 ft × 6.3 in)** has a ~**32:1 aspect ratio** and a
paper-thin base. It **tips sideways**, **bows/sags** under its own ~7 kg, and
**twists** (near-zero torsional stiffness). Free-standing would need a heavy wide
base + A-frame supports every 2–3 ft + a truss spine — far more than just hanging
it. **Wall-mounting (above the window, as planned) is dramatically the right fit:**
the wall is the spine, the anti-tip, and the anti-sag, for free.

### 8.2 No off-the-shelf frame fits — build it (~$200)
Commercial LED cabinets are **640×480** (4×3) or **960×960** — *too tall*, waste
rows. A 1-row cabinet = OEM/custom; truss ground-support stands ≈ **$2000** (wrong
proportions). So we build a backbone.

**Governing physics** (`sag = 5WL³/384EI`): an *unsupported* 5.1 m span sags badly
(metric 4040 ≈ 32 mm in the middle). **The decisive lever is intermediate support,
not beam size** — anchor the rail every **~3–4 ft** and *any* reasonable profile
goes dead flat (<0.2 mm).

**CRITICAL — magnets need FERROUS metal.** They will **NOT** stick to aluminum
extrusion, wood, or MDF. Two paths:
- **(A) Screw-mount (simplest):** bolt panels' **M3 corner holes** to the frame with
  T-nuts — **skip magnets entirely.** Recommended for a permanent install.
- **(B) Magnet-mount:** add a **steel sheet** (e.g., 26-ga galvanized on plywood) as
  the substrate, then panels snap on.

| Build | What | Anchoring | ~Cost |
|---|---|---|---|
| **Recommended (stud-mount)** | continuous **80/20 1530** alu extrusion backbone; panels bolt via T-nuts (path A) | lag screws into wall studs every 16–32" (≤1.7 m bays → dead flat) | **~$210** |
| **Renter-friendly** | lightweight backbone (2040 alu or ½" plywood); 26-ga steel sheet if using magnets (path B) | **French cleats + TOGGLER SNAPTOGGLE** toggle bolts (~238 lb each in ½" drywall; wall ≈ 15 kg = 10× margin); ~6 spackle-fillable holes, no stud-hunting | **~$190** |

### 8.3 Budget for
- **Depth:** ~**30–35 mm** behind the LED face (panel ~15 mm + magnet studs 16.6 mm +
  IDC/power connectors). Leave frame clearance.
- **Weight:** ~0.45 kg/panel → **~7 kg panels** + ~1.5 kg PSUs + frame ≈ **12–15 kg**
  total. Trivial for studs; fine for the toggle-bolt variant's 10× margin.
- **Handling:** the strip is **floppy out-of-plane until mounted** — build & carry in
  **2–3 sections**, join on the wall. Laser-level the ledger/rail before final tighten.

### 8.4 Shopping (frame) — buy before the full wall
| Item | Spec | Why |
|---|---|---|
| **80/20 1530** profile (or 2040 for renter variant) | ~17 ft, cut to ~5.1 m + splice plates | the backbone |
| **T-nuts + M3 bolts** | ~80 pc (4 corners × 16 panels + frame) | screw-mount panels (path A) |
| **Lag screws ⅜"×3"** *(stud)* **or TOGGLER SNAPTOGGLE** *(renter)* | ~10 anchors | wall attachment |
| *(magnet path only)* **26-ga steel sheet** | laminate to ½" ply | ferrous substrate |
| **Leveling shims / end brackets** | — | flat & level over 5 m |

**Sources:** Adafruit DIY LED Video Wall (frame), 80/20 deflection calculator
(1530 Ix=1.80 / 1545 Ix=5.69 in⁴), Unistrut P1000 load tables, TOGGLER SNAPTOGGLE
rating, Adafruit #2277 (453 g, 318×158×15 mm, M3) / #4631 mini magnet feet.

---

## 9. ⭐ FINAL OPTIMIZED BUILD — fewest parts, cheapest, NO-SOLDER  (2026-06-29)
Result of a 3-agent optimization pass (power / mounting / prebuilt+control) against
the owner's priorities: **fewest items, cheap, no soldering, buy-don't-build.**
Supersedes the per-part recommendations above where they conflict.

### 9.1 Verdicts
- **Control = $0.** Keep the **Pi 4 + Triple Bonnet** + existing Python. No prebuilt
  1024×32 wall worth buying (custom signage = several× the panel cost, locks you to
  NovaStar video). NovaStar/ESP32 are downgrades. Keep **2×8 center-feed**
  (`parallel=2 chain=8`); cap `--led-pwm-bits 7–8` for refresh headroom. *(The "use
  3 lanes / 6+5+5" idea is rejected — a 3rd chain needs a >50 cm jump cable in a
  single 1-D row; see §5.)*
- **Power = ~$52, no-solder.** **One 8-way ATC automotive fuse block (w/ negative
  bus) per PSU.** Panel **fork terminals land directly** (+5 V on fused studs, GND on
  the common bus). Replaces the 4× Hanson distro + bus bars + crimping. Per-panel
  fusing kept (a 60 A rail will feed a fire into a shorted 4 A panel — fusing is
  consensus-mandatory). Bare stranded wire clamps under screws → no crimp tool.
- **Mounting = MAGNETIC (revised 2026-06-29 — owner bought 100 panel magnets).** Screw
  a magnet into each panel's 6 M3 holes (96 used) → panels **snap onto 2 horizontal
  ferrous rails** at the top-row and bottom-row magnet heights. **Tool-free panel
  removal** (great for QC/service) and **no brackets to design/print.** The earlier
  "reject magnets" call was about a full flat steel *sheet* (heavy/not-flat) — **2
  narrow rails** (Unistrut steel strut, or steel flat bar/angle) avoid that: the rail
  is spine + magnetic surface + wall mount in one. Wall is only ~30 lb assembled →
  anchor the rails every ~2–3 ft (toggle bolts, no studs needed). *Fallback if you skip
  steel: 2020 aluminum extrusion + 3D-printed brackets bolted to the 6 holes (free at
  MIT), keep the magnets as spares.*

### 9.2 ORDER STATUS

**🛒 ORDERED — Wired Watts #225740 (2026-06-29):** 2× LRS-350-5 · Double Stack Mount Kit
(now spare) · 100× Panel Magnets.
**🛒 ORDERED — power consumables (2026-06-29):** 2× AC mains cord · **4× 6-way fuse block**
· 7.5 A blade fuses · 10 AWG red+black wire (offcut → 14 AWG ground bond) · cable ties +
self-adhesive tie mounts.
**✅ ON HAND:** Pi 4 · Triple Bonnet · 45 W USB-C · microSD · 16 panels w/ data ribbons +
fork-terminated power pigtails.

**STILL TO BUY:**
| Item | Qty | ~$ | Source |
|---|---|---|---|
| **2 ferrous rails** (steel — light flat bar/angle **or** Unistrut, ~17 ft ea; magnets need STEEL, not aluminum) | 2 | **FREE** at MIT, else ~$40–70 | `reuse@mit.edu` / Hobby Shop → else Home Depot |
| **Wall anchors / toggle bolts + rail brackets** (or French cleat) | ~8 | $20 | hardware store |
| **3D-printed alignment aids** (end-stops + registration combs — see §9.7) | ~6–10 | **FREE** | print at MIT |
| *(recommended)* **ring terminals**: insulated **YELLOW (10–12 AWG)**, assorted stud holes **#8 (M4) + #10 (M5)** — **~8 needed** (4× +input #10, 4× V− #8); **crimper FREE at MIT** | 1 kit | $8 | Amazon "yellow ring terminal assortment 10-12 AWG #8 #10" |
| *(recommended)* **DC clamp meter** UT210E (QC current checks) | 1 | $35 | Amazon |

**Total still-to-buy ≈ $20 essential** (rails + prints free at MIT; only wall anchors)
**+ ~$50 recommended** (crimper + meter). If buying steel rails too, add ~$40–70.
*(Power layout LOCKED — pigtail = 55 cm → 4 fuse blocks, PSUs SPLIT one per half. See §9.5.)*

### 9.3 CUT / already covered (do NOT buy)
Hanson distro boards · copper AWG6 trunks · ferrule/crimp tool · fork-lug kit · bus
bars · 3D brackets+extrusion (magnetic route instead) · steel *sheet* · data cables ·
panel power cables · 3rd PSU · NovaStar/ESP32.

### 9.4 No-solder assembly order (screwdriver only)
1. **Power:** fuse block by each PSU → 10 AWG jumpers PSU V+→block input, V−→bus →
   land the panel forks → 14 AWG bond PSU1 V− ↔ PSU2 V−.
   - *Trunk → block STUDS: use a **ring terminal** (gas-tight, set-and-forget for 16 A/24-7).
     ~8 needed (4× +input, 4× V−); crimper free at MIT. **Alt = bare-wire J-hook** under the
     nut — $0/no tool, but stranded wire loosens over thermal cycling → resistance/heat at
     16 A; needs a tight twist + periodic re-tighten. PSU-end is screw-clamp = bare wire OK.*
2. **Frame:** mount 2 ferrous rails to the wall (level, every ~2–3 ft) at the top/bottom
   magnet heights → screw a magnet into each panel's 6 M3 holes → snap panels on,
   butting edges.
3. **Data:** daisy the included ribbons panel→panel, 2 chains of 8, bonnet at center.
4. **Power on** with the PSU switch set to **115 V**.

### 9.5 ✅ PHYSICAL LAYOUT — RESOLVED (pigtail measured = 55 cm, 2026-06-29)
Power pigtail = **55 cm** (white 4-pin plug → 2 blue fork terminals, +5 V / GND). Panels
do NOT pass power through — each needs its own feed. At 320 mm panel pitch with the tap
near panel-center, **55 cm reaches ~±1.7 panels → one fuse block serves ~4 panels.**

**LOCKED layout (back of wall) — validated 2026-06-29 against real long-row builds:**
- **Center:** Pi + Triple Bonnet only (data origin).
- **Data:** 2 chains of 8 from center — chain 0 → panels 8→1 (left), chain 1 → 9→16
  (right). ✅ Validated: 8/chain is the sweet spot (ghosting/tearing starts at **12+/
  chain**); center-feed halves the worst-case run.
- **Power:** **4× 6-way fuse blocks**, one centered on each 4-panel group (1–4 / 5–8 /
  9–12 / 13–16). Each panel's 55 cm pigtail reaches its group's block. ✅ Validated:
  per-panel feeding is best practice; fuse the **positive only**, common all negatives.
- **🔧 PSUs SPLIT — one behind each half (NOT stacked at center).** PSU1 ≈ behind panel
  4–5 → blocks 1–2; PSU2 ≈ behind panel 12–13 → blocks 3–4; short 10 AWG trunk each.
  **3 reasons:** (a) **voltage drop** — splitting halves trunk current+length → ~4× less
  drop (far panel ~4.95 V); (b) **thermal** — the LRS-350-5 has a **built-in fan**, don't
  stack tightly/block airflow (Mean Well wants ~10–15 cm clearance); (c) **inrush** —
  ~20 A cold-start each @115 V → put on **separate outlets** so a 15 A breaker won't trip.
  *(The Double Stack Mount Kit goes unused — keep as a spare.)*
- **⚡ Grounding (critical):** bond **PSU1 V− ↔ PSU2 V− ↔ Pi GND** at one star point — the
  PSU2 panels need a defined ground reference for the Pi's data lines or you get garbage
  pixels. **NEVER** tie the two V+ rails together.
- **Mounting:** magnets → 2 horizontal ferrous rails (top-/bottom-hole heights). *(Magnet
  hold over a 5 m vertical row is anecdotal — bench-test a few panels on the rail before
  committing all 16.)*
- **Real draw is light:** ~4 A/panel full-white-full-bright conservative max (2.4 A
  measured); 16 panels = 64 A max / ~5–16 A in real capped/dark use → 7.5 A fuses +
  budget very comfortable. **PSU sizing confirmed: keep 2× LRS-350-5** (53 % at peak,
  ~10–20 % typical). One 60 A unit is too tight (64 A max) + worsens distribution; 2×
  fanless LRS-200-5 (40 A) would also work (80 % peak) — a fanless *preference*, not a
  fix. See BUILD_LOG.
- **Software tuning (Pi):** `--led-pwm-bits=7`, `--led-pwm-lsb-nanoseconds≈100` (→300 if
  bright text ghosts), `--led-pwm-dither-bits=1`, dedicate a core (`isolcpus`), and
  **disable onboard audio** (`dtparam=audio=off` + blacklist `snd_bcm2835`, mandatory —
  it steals the LED timing). 400–500 Hz achievable; keep `--led-slowdown-gpio` 2–4.
- **No redesign:** topology, control, magnetic mount unchanged; vs the prior note only the
  PSU placement moved (center-stack → split). *(Still confirm a data ribbon reaches
  bonnet→panel 8 and bonnet→panel 9 — adjacent to center, so it should.)*

### 9.7 MOUNTING TECHNIQUE — LIGHT HYBRID (steel rails + magnets + a few prints)
Mounting = two problems, each solved by a different material → use both:
- **HOLD = magnets** (tool-free, already owned); **LOCATE = 3D-printed parts** (positive registration).
- Pure-magnetic doesn't locate (drift); pure-bracket (bolt to aluminum extrusion) locates
  but = 96 screws + no tool-free service. Hybrid keeps the best of each.

**Recommended build:**
1. **Backbone — 2 steel rails** at the top-row & bottom-row magnet heights (~144 mm apart).
   Steel (ferrous) so magnets stick. **Light ~1/8" flat bar** (cheap/light, anchor every
   2–3 ft) or Unistrut (pre-slotted, heavier). *Thicker steel = stronger magnet grip —
   test a magnet on a sample.* **Level both rails coplanar = the whole wall's flatness.**
2. **Attach — magnetic.** Magnet into each panel's 6 M3 holes (96 of 100) → snap on.
   Tool-free QC/service. Shear hold ≈ 6× the 0.45 kg panel weight (secure on a vertical wall).
3. **Locate — 3D-printed (free at MIT, PETG/ABS):**
   - **End-stops** (2–4) at rail ends → row can't slide sideways (the key anti-drift part).
   - **Registration combs/spacers** *(optional, ~every 4 panels)* → even gaps, stop drift.
- **Why best here:** uses the magnets, uses free printing, no bolting 16 panels, **tool-free
  panel swaps** (big for QC + dead-panel replacement). Printed end-stops fix magnetic
  mounting's one weakness (lateral drift) for ~free. *(Fallback: pure 3D-bracket-to-2020-
  extrusion if you skip steel — but loses tool-free service.)*

### 9.6 TOOLS (build is solder-free — borrow most free at MIT)
**Essential:** screwdriver set (Phillips+flat; M3 magnet screws), wire stripper/cutter
(10–22 AWG), small wrench/nut driver ~7–8 mm (fuse-block M4/M5 stud nuts), **power drill
+ bits** (wall anchors + driving 96 magnet screws), **level — 4 ft or laser** ⭐ (the most
important tool — 2 rails dead level/coplanar over 5 m), tape measure + pencil. Hacksaw
*only if* cutting rails at home — better: cut free at the **MIT Hobby Shop**.
**Recommended:** wire crimper + **#10 ring terminals** (~$15) for the 10 AWG trunk → fuse-
block M5 stud (proper 16 A/24-7 connection; bare-hook works but ring lug is safer); DC
clamp meter (UT210E ~$35); basic multimeter.
**NOT needed:** soldering iron (nothing is soldered), heavy lug crimpers.
**At the apartment (must have on-hand for wall-mount):** drill, level, tape measure,
screwdriver, pencil. Everything else can be done/borrowed at MITERS/Hobby Shop.
