# VESTOR — FULL BUILD WALKTHROUGH (16-panel wall)

The master checklist from parts to a lit, running wall. Each stage points to the deep
doc for detail. Frame/mount = `design/MANUFACTURING_PLAN.md` + `design/MOUNT_PARTS.md`
+ `design/PRINTING.md`; wall geometry = `design/SETUP_SPEC.md`; electrical = `INVENTORY.md`
§9 + `HARDWARE.md`; operate = `RUNBOOK.md`. Phase 0 (one panel, first light) is already ✅.

Order matters: **fabricate → QC → hang the frame flat → panels → power → software.**

---

## STAGE 0 · Measure + gather (do before printing the batch)
- [ ] **Measure the real grooves** at ~5 points along the 5.1 m: top-groove width/depth,
      bottom-groove width/depth, and that they're uniform + unbroken (no outlets/nails).
      Compare to the CAD (top 14×50, bottom 18×57). Tell me any delta — I retune clearances.
- [ ] **Measure a real panel's M3 holes** (the two rows' Z + the 3 columns' X pitch) off one
      panel. The rail drill positions key off this — send me the numbers if they differ from
      the estimate (rows at −8/−152 mm, ~144 mm apart).
- [ ] **Gather stock/hardware:** free PLA (H2S); steel flat bar + angle (rails); M4 bolts +
      **52 M4 heat-set inserts** + M4 jack/set screws; M2.5 for the Pi; the **100 magnet feet**;
      2× LRS-350-5 + 2 AC cords; 2× 8-way ATC fuse blocks + 7.5 A fuses + 10/14 AWG wire + forks;
      Pi 4 + Triple Bonnet; the 16 panels + their ribbons/pigtails. (Buy-list status: `INVENTORY.md`.)

## STAGE 1 · Print the mount parts (H2S, PLA — chamber door OPEN for PLA)
- [ ] **Coupons FIRST:** print `00_coupon_tongue.stl` + `02_foot_tab.stl` → test in the real
      grooves (want a slight ~0.5–1 mm wiggle). If off, I tweak `mount_params.py` + you reprint.
- [ ] **Batch:** 13× `01_top_cleat`, 7× `02_foot_base`, 7× `02_foot_tab`, 1× `05_corner_enclosure`,
      4× `06_psu_cradle`. Keep the exported orientation (cleat on its side). Settings: `PRINTING.md`
      (4–5 walls, 15–20 % infill, supports only on the enclosure). ~0.9 kg total, ~3 H2S plates.
- [ ] **Heat-set inserts:** press 52× M4 into the cleats (4 each) + M2.5 into the enclosure with a
      soldering iron. (Optional creep-proof upgrade later: reprint the 13 cleats in PETG/ASA on the H2S.)

## STAGE 2 · Fabricate the two steel rails
- [ ] **Cut** (bandsaw/cold saw): TOP = low-carbon steel flat bar ~26×5 mm, 2×2559 mm. BOTTOM =
      steel angle ~30×24×4 mm, 2×2559 mm (leg = magnet target, other leg = panel rest ledge).
- [ ] **Drill** M4 at each of the 13 stations × 2 per rail, positioned to your measured M3 columns.
      **Slot** every hole except the center station in X (lets the 5 m rail expand ~1.2 mm/20 °C).
- [ ] Splice each rail's two halves at the center station (a cleat bridges the joint). **Deburr.**
      Keep the **magnet faces BARE** (no paint/zinc). Optional: a thin wax on the back/edges vs rust.

## STAGE 3 · QC all 16 panels (before they go on the wall)
- [ ] Run each panel on the Pi (`demo -D3/-D4/-D5`) one at a time. Dead IC = a 16-px column out;
      dead row = a horizontal line. **Log 1–16, keep 2–3 as spares**, RMA any dud with video. (`INVENTORY.md` §7.3)

## STAGE 4 · Prep panels + electronics (bench work)
- [ ] Screw **6 magnet feet into every panel's M3 holes** (96 total). Don't over-drive (rear parts).
- [ ] Build the **corner brain:** Pi 4 + Triple Bonnet stacked on the GPIO, mounted in `05_corner_enclosure`.
- [ ] Seat the **2 PSUs** in their cradles; pre-wire each PSU → its 8-way fuse block (10 AWG trunk,
      common-negative bus, 7.5 A fuses). Set the PSU input switch to **115 V**. (`INVENTORY.md` §9)

## STAGE 5 · Hang the frame — THE PRECISION STAGE (get this flat)
- [ ] **Drop the 13 cleats** into the TOP groove, ~427 mm apart; saddles rest on the piece; snug the
      Z set-screws. Zero wall holes.
- [ ] **Laser-level the cleat faces:** run a laser line (or taut string) across all 13 front faces and
      **jackscrew/shim each cleat until every face is in one vertical plane, ≤0.3 mm.** ← do this now,
      before any rail or panel. This one step is what makes the seams disappear.
- [ ] **Bolt on the two steel rails:** anchor the **center** station first, then work outward with the
      slotted holes snug-but-sliding. Splice the halves at center. Verify both rails are flat, parallel
      (144 mm apart), and their magnet faces are coplanar (same Y).
- [ ] **Wedge the anti-swing tabs** up into the BOTTOM groove (between cleats) and pinch the set-screws.

## STAGE 6 · Hang the panels
- [ ] Start at the **corner**. Panel 0: rest its bottom edge on the angle ledge, butt it to the corner,
      let the magnets snap to both rails. Panel 1: **butt tight to panel 0** (they self-space), rest,
      snap. Repeat across all 16 (the +2 mm slack lands at the far end).
- [ ] **Chain the HUB75 ribbons: single chain of 16 from the corner** (panel 0→1→…→15). All ribbons stay short.
- [ ] **Coplanarity trim:** the flat rail does most of it — set each panel's 6 magnet screws to a uniform
      depth with a gauge, then nudge any proud corner. Sight down the face under raking light.

## STAGE 7 · Power + data → first light of the full wall  (full spec: `ELECTRICAL.md`)
- [ ] **Split the PSUs — one per half**, each on its **own AC outlet** (inrush ~60 A). Mount with 10–15 cm
      vent clearance; tie each **FG to earth**. Trim each to **~5.15 V** (measured at the farthest panel).
- [ ] Distribute the **4 fuse blocks** (one per 4-panel group). Feed each block its own **10 AWG** copper
      run straight from the PSU (**star**). Land each panel's pigtail on **its own 5 A fuse** (16 total —
      **NOT the 7.5 A**, too high for the thin CCA pigtails). Crimp CCA + anti-oxidant paste; re-torque.
- [ ] **Ground:** bond both PSU **V−** + the Pi GND at ONE point (a negative bus bar). **Never tie the two V+.**
- [ ] Connect the Bonnet's HUB75 output → panel 0; power up the Pi. **Full-wall first light.**

## STAGE 8 · Software
- [ ] **Config the single chain of 16:** `--led-chain=16 --led-parallel=1`, `pwm_bits=9` (~111 Hz),
      `BRIGHTNESS ≤ 50`, `panel_type=""` (FM6124HJ), regular mapping. **Remove the old snake / 180° left-half
      flip** in `display/__init__.py`, and revert the Phase-0 Port-3 shim. (`ROADMAP.md`, `RUNBOOK.md`)
- [ ] **Port the multi-zone app** (weather °C/°F + flights + Bluebikes + MIT shuttle) onto the 1024×32 wall —
      this is the real Phase-1 software build (design lives in `sim/` + the design docs). Tune + verify health.

## STAGE 9 · Finish + harden (optional)
- [ ] Cable management; optional smoke-gray acrylic front tint (deepens blacks, hides shade variation);
      always-on hardening (systemd watchdog, remote power-cycle plug). (`GEAR.md`, `ROADMAP.md`)

---

### The 3 things that most decide success
1. **STAGE 5 laser-leveling** — flat frame first = invisible seams. Everything downstream rides on it.
2. **STAGE 0 measurements** — the grooves + real M3 positions are the only unknowns; measure before you cut/batch.
3. **STAGE 3 QC** — find dead panels on the bench, never after they're mounted and wired.
