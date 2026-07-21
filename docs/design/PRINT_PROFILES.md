# VESTOR — PRINT PROFILES ("Vestor Structural" + "Vestor Light")

Evidence-based slicer settings, from a research pass over CNC Kitchen's tensile/hook tests
and peer-reviewed FDM studies. **Supersedes the "4–5 walls / 15–20 % infill" guidance in
`PRINTING.md`.** Optimization target: filament + machine-hours are FREE, so we spend both to
buy strength. Plate/schedule: `PRINT_PLAN.md`.

## What actually matters (ranked by measured strength-per-effort)
1. **Orientation — worth ~2×** (PLA hook: 73 kg with load *along* layers vs 40 kg across).
   **Already correct** in our STLs — never let Studio auto-orient the cleat or hanger.
2. **Total wall cross-section.** 2 → 4 wall loops took a test hook from **20 kg → 33 kg (+65 %)**.
   Wall *width* matters as much as count: 2 loops at 200 % width (39 kg) **beat** 4 loops at 100 % (33 kg).
3. **Solid vs sparse.** Infill **20 %→80 % is statistically flat** (noise-level, non-monotonic).
   Only going fully **100 %** does anything (+37 % modulus over 60 %).
4. **Don't print cold or wet.** 190 °C loses **50 %** of layer adhesion vs 230 °C. Wet filament = steam voids.
5. **Layer height ≤ 50 % of nozzle.** 0.3 mm on a 0.4 nozzle **halves** interlayer strength.

**Placebo / counterproductive — don't bother:** infill % between 20–80 · very slow printing
(30 vs 500 mm/s was "minimal") · **annealing** (zero layer-adhesion gain, up to **10 % dimensional
distortion** — wrong trade for precision brackets) · 0.28 mm layers "for strength" · a bigger nozzle ·
**the H2S heated chamber with PLA** (65 °C chamber > PLA's 60 °C Tg → curling + heat-creep jams).

---

## ▶ Ready-made profile files (just load these)
`cad/print_profiles/Vestor_Structural_H2S.3mf` and `..._P2S.3mf` — built from the shop's own
Medium profile with every value below already applied.

**To load:** Bambu Studio → **File → Import → Import Configs…** → pick the `.3mf`. That imports the
*settings only* (no model). Then import the STLs from `cad/print_stl/` as usual.
*(If you open the `.3mf` as a project instead, it will also bring the shop's placeholder model —
just delete it and import ours.)*

⚠️ **After importing, always check the plate:** parts must be **lying down**, not standing.
Use **Arrange (`A`)** to shuffle in XY — never **Auto orient**, which re-picks the resting face.
If a part is upright: select it → **Place on face** → choose the big flat profile face.

## ▶ "VESTOR STRUCTURAL" — for `01_top_cleat`, `07_gap_hanger`, `02_foot_base`, `02_foot_tab`
Start from the shop's **H2S Medium** profile, then change:

**Quality**
- Layer height **0.16 mm** · first layer **0.20 mm**
- Line width — outer wall **0.42** · inner wall **0.55** · sparse infill **0.55** · internal solid **0.50**
  *(0.55 ≈ 140 % of nozzle, near the measured layer-adhesion peak)*
- Precise wall **ON** · seam position: put it on a **non-loaded face**

**Strength**
- **Wall loops: 8** ← the big one. Our parts have thin 6–12 mm sections, so 8 loops makes them
  **effectively solid** and infill becomes irrelevant. Check the preview: no sparse infill left in
  the critical section.
- Top shell **6** · bottom shell **6**
- Sparse infill **100 %** (if any sparse region survives, use **grid**, not gyroid — grid tested
  strongest in-plane: 21.9 vs 13.1 MPa for adaptive cubic)
- Infill/wall overlap **25 %** · wall order **Inner/Outer/Inner**

**Material / temp**
- Nozzle **230 °C** (first layer 235) · bed 55–60 °C
- **Chamber heating OFF** (keep internal < ~40 °C; crack the door if it climbs)
- Dry the filament **45–55 °C for 6–8 h** if a dryer is available

**Cooling**
- Part cooling **40 %** (min 30 / max 50) · **0 % for the first 3 layers** · min layer time ~5 s

**Speed** *(speed is nearly a myth for strength — this is free insurance)*
- Outer wall **80** · inner wall **150** · sparse infill **200** · solid infill **150** mm/s
- **Max volumetric speed 12 mm³/s** (well under PLA's ~21) so the melt is fully saturated at 230 °C

**Other:** supports **OFF** · brim **ON** · **do NOT anneal**

> **Time cost:** this roughly **doubles** print time vs the shop Medium profile (near-solid parts).
> That's the right trade when machine time is free — see `PRINT_PLAN.md` for the adjusted schedule.
> **If you need it faster:** 6 wall loops + 50 % grid infill gets ~85 % of the strength at ~65 % of the time.

## ▶ "VESTOR LIGHT" — for `08_cable_clip`, `06_psu_cradle`, `05_center_enclosure`
These carry no meaningful load. **Use the shop's Medium profile as-is** (0.20 mm, 2 walls, 15 %),
with one change: **supports OFF** — except `05_center_enclosure`, which wants **light supports**.

---

## Material verdict: PLA is genuinely the right choice (with one thing to verify)
PLA **beats** PETG and ASA on the three properties that matter here — hook strength (73 vs 55 vs 57 kg),
bending modulus (3300 vs 1900 vs 2300 MPa), and layer-adhesion retention (55 % vs 46 % vs 29 %).
It loses only on impact (irrelevant — static load) and heat.

**Creep is not our failure mode.** 24 kg ÷ 13 cleats ≈ **1.85 kg ≈ 18 N each** → roughly **0.1–0.2 MPa**
in the load-bearing section, i.e. **under 2 % of PLA's ~60 MPa UTS**. The creep studies that show PLA
failing operate at **50–90 % of UTS**. At ~35 °C below its Tg, PLA is firmly glassy here.

### ⚠ The one assumption to check on-site
Our "18–27 °C indoor" figure is the **room** — not the air **behind the panels**, where the LEDs, and
especially the PSUs, add heat. Once the wall runs, **IR-gun the actual temperature at the bracket
locations** (this also validates the ceiling-gap chimney from `../HARDENING.md`):
- **< 40 °C** → PLA, comfortably. Proceed as built.
- **40–50 °C** → switch the **cleats** to PETG (accept ~25 % lower strength, compensate with wall thickness).
- **> 50 °C** → ASA, and re-check orientation (its layer adhesion is poor).

## Validate it, don't trust it
Print **one extra cleat and pull it to destruction.** Service load is ~18 N per cleat; you want
**≥ 10× that**. Free to do, and it turns the whole analysis into a measured fact.
