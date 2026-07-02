# VESTOR — BUILD PACKET (print & carry)

One self-contained sheet for the Hobby Shop + install. Everything here is decided; the
deep detail lives in `MANUFACTURING_PLAN.md`, `ELECTRICAL.md`, `HARDENING.md`,
`design/BACK_OF_HOUSE.md`, `design/PRINTING.md`. **Do Phase 0 (measure) before cutting steel.**

**What you're building:** a 16× P5 64×32 LED wall, one row 5120 mm × 160 mm, hung from the
wall's built-in top groove (zero new wall holes). Center-fed 2×8 data; split 2× LRS-350-5 power;
all electronics + wiring hidden in the ~22.5 cm ceiling gap behind a vented valance.

---

## ▢ PHASE 0 — MEASURE FIRST (at the wall, before you cut steel)
- ▢ **Top groove:** width + depth at ~5 points along the 5.1 m (expected 14 × 50 mm). Note the min width.
- ▢ **Bottom groove:** width + depth (expected 18 × 57 mm).
- ▢ **One panel's M3 holes:** the two rows' vertical spacing (expected 144 mm) + the 3 columns' X spacing + edge offsets.
- ▢ **Confirm** grooves are uniform + unbroken (no outlets/nails); note the AC outlet at the right end.
- → Send me the groove widths + M3 positions; I tune the tongue/tab clearances and the rail drill layout, then you cut.

---

## ▢ SHOPPING / BRING LIST

**On hand — just bring:**
▢ 16 panels + ribbons ▢ 2× LRS-350-5 + 2 AC cords ▢ 4× 6-way fuse blocks ▢ 7.5 A fuses (16+)
▢ 10 AWG CCA red/black trunk (25 ft) ▢ copper RV pigtails ▢ yellow ring terminals ▢ 100 magnet feet
▢ Pi 4 + Triple Bonnet + 96 W USB-C charger ▢ 100 cable ties + (adhesive mounts — light dressing only)

**Steel (rails):**
▢ Mild-steel **flat bar 25 × 5 mm**, 2 × ~2.6 m (top rail) — Hobby Shop stock or metal supplier, **bare**
▢ Mild-steel **angle 25 × 25 × 3 mm**, 2 × ~2.6 m (bottom rail = magnet target + panel rest ledge), **bare**

**Fasteners / hardware:**
▢ **M4 brass heat-set inserts** (100-pack) ▢ M4 bolts 10–16 mm (~60) ▢ M4 grub/set screws 8–12 mm (~50)
▢ M2.5 screws + 4 inserts (Pi) ▢ **M3.5 Belleville or Nord-Lock washers** + flat washers (PSU studs)
▢ M4 screws for the cable clips → strip

**Electrical hardening (the reliability pass):**
▢ **Bootlace ferrules** + ferrule crimper ▢ **dielectric/contact grease** ▢ bag of **clip-on ferrite cores**
▢ **bulk low-ESR caps** ~2200–4700 µF ≥16 V (×2–4, for the center feeds) ▢ **16 AWG wire** (V− bond ~5.5 m + Bonnet-GND ~1 m)
▢ **Ethernet cable/run to the Pi** ▢ **Noalox anti-oxidant** *(only if keeping the CCA trunk — else swap the 7 m to copper)*
▢ latching JST-VH housings *(optional, for the pigtail plugs)*

**Cabling / valance / strip:**
▢ **backer strip** — aluminium angle 40×40 or 1×3 pine, ~5.1 m ▢ **valance** — MDF/1× pine board ~5.1 m × ~0.25 m, paintable
▢ spiral-wrap (DC loom) + cable sleeving (HUB75 spine)

**Spares (buy the panels NOW with the order):**
▢ **1–2 same-batch P5 64×32 panels** ← must be same batch. Later: spare PSU, 80 mm fan, ribbons, Bonnet, Pi + cloned SD.

**Tools:** ▢ torque driver (in·lb / kgf·cm) ▢ crimper ▢ soldering iron (inserts) ▢ clamp meter ▢ laser level or string + feeler gauge ▢ IR thermometer

---

## ▢ PRINT LIST (Bambu H2S, PLA, chamber door OPEN) — pre-oriented STLs in `cad/print_stl/`
**Coupons FIRST** (check the real groove fit before batching):
▢ `00_coupon_tongue` ×1 ▢ `02_foot_tab` ×1  → test in grooves; if off, I tweak params + you reprint.

**Then the batch (~1 kg PLA total):**
▢ `01_top_cleat` ×13 ▢ `02_foot_base` ×7 + `02_foot_tab` ×7 ▢ `05_center_enclosure` ×1
▢ `06_psu_cradle` ×4 ▢ `07_gap_hanger` ×6 ▢ `08_cable_clip` ×40

**Settings:** 0.2 mm layer · **4–5 walls · 15–20 % gyroid infill** · supports **off** (except `05` = light) ·
**brim** on cleat/tab/hanger · **keep the exported orientation** (cleat + hanger print on-side — do NOT auto-rotate).
Optional later: reprint the 13 cleats in **PETG/ASA** for zero creep worry.

---

## ▢ CUT & DRILL LIST
**Steel (bandsaw/cold saw + drill press):**
▢ Top rail: 25×5 flat bar → **2 × 2559 mm** (splice at the center station)
▢ Bottom rail: 25×25×3 angle → **2 × 2559 mm**
▢ Drill **M4 clearance holes at 13 stations × 2 per rail** (X per your measured M3 columns); **slot all but the center station** (thermal). Deburr; keep magnet faces bare.

**10 AWG trunk (cut with service-loop slack included):**
▢ PSU-L → block A ~**1.1 m** ▢ PSU-L → block B ~**2.4 m** ▢ PSU-R → block D ~**1.1 m** ▢ PSU-R → block C ~**2.4 m**
▢ 16 AWG V− bond ~**5.5 m** + Bonnet-GND ~**1 m** (separate wire, not the 10 AWG)

**Wood/strip (saw):** ▢ backer strip ~5.1 m ▢ valance ~5.1 m (cut a **top vent reveal + low intake slot** = chimney)

---

## ▢ HOBBY-SHOP SESSION (order of operations)
1. ▢ **Start the prints first** (long pole, unattended) — coupons, then the batch across ~3 H2S plates.
2. ▢ **Saw** the 4 steel rail pieces + backer strip + valance to length.
3. ▢ **Drill + slot** the rail bolt holes (to your measured M3 columns).
4. ▢ **Set heat-set inserts** (soldering iron) into the 13 cleats + the enclosure.
5. ▢ **Crimp** ring terminals (trunk) + ferrules (stranded ends); **stick nothing structural with adhesive.**

---

## ▢ INSTALL SEQUENCE (on-site) — full detail in `BUILD_WALKTHROUGH.md`
1. ▢ **QC all 16 panels** on the Pi (`demo`) before mounting; log 1–16, set spares aside.
2. ▢ Bolt cleats + feet to the rails; **splice rails at center; slot all but the center bolt.**
3. ▢ **Drop the 13 cleats into the top groove**; **laser-line + shim their faces flat to ≤0.3 mm** ← the precision hour, before any panels.
4. ▢ Bolt on the two steel rails (anchor center, slotted ends); wedge the anti-swing feet into the bottom groove.
5. ▢ Hang the **gap hangers** + backer strip in the ceiling gap; mount PSUs at the ends (vents clear), Pi+Bonnet at center, 4 fuse blocks distributed.
6. ▢ Screw magnet feet into all 6 M3 holes of each panel; **hang panels from center outward**, resting on the ledge, butting neighbors; magnets snap on.
7. ▢ **Data:** Bonnet → center panels 8 & 9, chain 8 each way; **retain every connector housing + first-anchor strain relief + service loops.**
8. ▢ **Power:** star trunks → fuse blocks → per-panel 7.5 A → pigtails; **bond both V− + Pi GND at one node, never V+**; decoupling caps at the feeds; trim ~5.2 V under load.
9. ▢ **Ethernet to the Pi**; dress DC + ribbon in separate looms; close the vented valance.
10. ▢ Power on → config below → tune.

---

## ▢ QUICK-REFERENCE CARD
- **Fuse:** 7.5 A per panel (16). **Brightness:** ≤ 50.
- **Data config:** `--led-chain=8 --led-parallel=2 --led-pixel-mapper="U-mapper;Rotate:180" --led-pwm-bits=11 --led-slowdown-gpio=2` · `dtparam=audio=off` · `isolcpus=3` · disable WiFi, use Ethernet.
- **Ground:** bond both PSU V− + both chain returns + Pi/Bonnet GND at ONE node. **Never tie the two V+.**
- **Torque:** LRS-350 M3.5 studs **8–10 kgf·cm** (~7–8.5 in·lb). **Re-torque at ~30 days, then annually.**
- **PSU trim:** ~5.1–5.2 V measured at the farthest panel under load (stay ≤ 5.5 V).
- **Do-not-forget:** service loops in every cable · screw-down (not adhesive) anchors · rolled ribbon folds (no crease) · same-batch spare panels bought with the order.
