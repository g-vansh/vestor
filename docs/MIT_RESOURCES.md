# MIT BUILD RESOURCES — fabricate the wall on campus (free/cheap)

For the owner (g-vansh), an **MIT grad student**, building the Vestor LED wall frame
(see `INVENTORY.md` §8). Researched 2026-06-21 (3 web-research agents). **Bottom
line: fabrication and 3D printing are FREE for grads; materials are free-to-cheap via
MIT give-away channels + a $500 grant. The frame can cost close to nothing.**

> ⚠️ Access rules / fees / perks change each year — treat the specifics below as
> "verify when you go," not gospel. Emails + URLs included so you can confirm.

---

## 1. WHERE TO BUILD (machine shops & makerspaces, grad access)

| Space | Bldg/Room | Cost (grad) | Tools relevant to the frame | How to get in |
|---|---|---|---|---|
| **MIT Hobby Shop** ⭐ | **N51-120** | **Free** for students (fee reportedly dropped for AY26-27) | **Waterjet, MIG+TIG welding, metal lathes/mills, bandsaws** (cut extrusion/steel to length), drill presses, FDM + resin 3D printers | Email **hayami@mit.edu** → Calira invite → **1-hr in-person orientation** (Wed 1-2 / Fri 3-4, first 6 wks of term). [studentlife.mit.edu/hobby-shop](https://studentlife.mit.edu/campus-communities/hobby-shop/) |
| **MITERS** ⭐ | **N52-115** | **Free, no fee, no application** | Bridgeport + CNC mill, lathe, h/v bandsaws, drill press, **MIG welder**, 3D printers, full EE bench | Show up during open hours with a project; a keyholder onboards you. 24/7 keyholder status after ~1-2 semesters. [miters.mit.edu/membership](https://miters.mit.edu/membership.html) |
| **Project Manus / MIT MAKE — Metropolis** | **6C-006B** | Free w/ training | FDM hub: Prusa MK3S/MK4 (~250×210×210); laser cutters; drill press, bandsaw, hand tools | Free **M1T** (ex-MakerLodge) orientation → tool trainings → free credentials. [make.mit.edu/metropolis](https://make.mit.edu/metropolis) |
| **Project Manus — The Deep** | **37-072** | Free w/ training | SLA/resin (Form 3 ~145×145×185), 3D printers, prototyping | Same M1T path. [project-manus.mit.edu](https://project-manus.mit.edu/thedeep-metropolis.html) |
| **Edgerton 6C Student Shop** | 6C | Free w/ training | Mills/lathes; 1st-floor fab has CNC + **waterjet** | **12-hr training** (build a steel flashlight); contact **Mark Belanger, mdbelang@mit.edu**. [edgerton.mit.edu student-shops](https://edgerton.mit.edu/for-MIT-students/student-shops) |
| Pappalardo (MechE) | Bldg 3 basement | Course-2 gated | Lathes, CNC, **big waterjet**, laser | Mostly via 2.00x classes/UROP — harder for non-MechE. |

### ⭐ HOBBY SHOP — the plan for THIS build (scouted 2026-06-29; owner getting access)
N51-120 · 265 Mass Ave · (617) 253-4343 · Director **Hayami Arakawa hayami@mit.edu**,
Assoc. instructor **Charlotte Reiter creiter@mit.edu**. **FREE for students.**
- **Cut the 2 steel rails to ~1.6 m:** **Baileigh 14" cold saw** (best — clean square cuts
  on flat bar/angle); backups: DoAll 24" metal bandsaw, Omax waterjet. **Confirm max stock
  length with staff** (1.6 m is long).
- **Wall-anchor holes in steel:** Clausing drill press. (Also Bridgeport/Haas mills,
  MIG+TIG welders if needed.)
- **3D printers — CONFIRMED 2026-07-02 (Charlotte Reiter email). The Stratasys uPrint is
  GONE.** Now **3 printers: Bambu P2S** (FDM, 256³ mm), **Bambu H2S** (FDM, 340×320×340 mm,
  65 °C chamber / 350 °C hotend → PLA/PETG/ABS/ASA/PC/CF), **Formlabs Form 3L** (resin SLA,
  335×200×300). **PLA is FREE** (only filament stocked now; more soon). **Resin = $0.25/mL**
  (assorted). → Full fabrication plan (parts, printer/material per part, PLA-creep strategy)
  in **`docs/FABRICATION.md`**. Also Epilog **120 W laser** (40×28") + Laguna 4×8 CNC router
  for acrylic/ply jigs.
- **Steel stock = BRING YOUR OWN** (no metal store). Source order: **`reuse@mit.edu`** (free
  scrap + Unistrut) → **MIT Central Machine Shop** ([web.mit.edu/cmshop](https://web.mit.edu/cmshop/services.html), stocks/pulls steel/alu) → OnlineMetals off-campus. Ask Hayami for offcuts.
- **⚠️ Orientation REQUIRED before any tool use** — Wed 1–2 / Fri 3–4, **first 6 wks of term
  only** (tours Mon 12–12:30). **Email hayami@mit.edu now** for a Calira slot; ask (a) max
  cold-saw stock length, (b) steel/Unistrut offcuts availability.
- Hours: Mon 10–8, Tue–Fri 10–7, Sat 10–4, Sun closed. **Cash/TechCash only.** Ethos =
  finished projects; staff give 1-on-1 help.

#### 🧰 Full equipment (access GRANTED 2026-07-02) — what each buys THIS build
The Hobby Shop is a full metal **and** wood shop. Relevant machines, grouped by build use:
- **Precision metal cutting:** **Omax Micro Jet waterjet** (cut aluminum/steel plate to any
  shape — brackets, cleats, **steel magnet strips**), **Baileigh 14″ cold saw** + **DoAll 24″
  bandsaw** (cut rail/extrusion to length), **Epilog 120 W laser** (acrylic diffusers/bezels,
  thin jigs/templates), **sheet-metal tools** (form the steel magnet strips).
- **Precision machining:** **Bridgeport mills ×2**, **Prototrak DPM2 mill**, **Haas Mini Mill 2**,
  **Menig micro mill**, **Prototrak/Trak lathes** → machine custom aluminum rails/brackets with
  exact hole patterns (means we **don't have to buy 80/20** — we can mill our own rails), turned parts.
- **Welding:** **Miller MIG + TIG** → a welded steel/aluminum frame if ever wanted (likely overkill).
- **Big CNC (wood/plastic sheets):** **Laguna Swift 4×8 CNC router** (8 ft!) + **ShopBot Buddy**
  → cut wood cassettes/frames/tongues from full sheets with exact hole grids + connector windows.
- **Shaper Origin** (handheld precision CNC) → cut the tongue-cleat, mortises, brackets — even
  in-place work.
- **Flat-stock prep:** **Martin 20″ jointer + 24″ planer + SawStop table saws ×2 + Oliver 30″
  bandsaw** → mill our OWN dead-flat/straight hardwood (mitigates the ply-warp concern), **Laguna
  LMB 200 mortiser** (precise mortise-and-tenon for the tongues), **Finishing Room** (SEAL wood
  against humidity → dimensional stability), **glue-up table** (laminate cleats / torsion boxes).
- **Formtech 989 vacuum former** → thermoform plastic covers/diffusers if desired.
- **Printers:** Bambu **P2S** + **H2S**, Formlabs **Form 3L** (the auto-generated access list still
  shows a Stratasys uPrint, but **Charlotte's email says it's GONE — treat as gone**).
- Not build-relevant (but available): leatherworking, tufting guns, sewing, **softserve machine**.

**Upshot:** we can build the mount to a high standard entirely in-house in **either metal or wood**,
and make every precise interface part (tongues, steel strips, brackets) on the waterjet/mills/CNC.
This removes the "buy 80/20" dependency and de-risks flatness/precision. Hours: Mon 10–8, Tue–Fri
10–7, Sat 10–4. Orientation required first (email hayami@mit.edu).

**⚠️ None stock 80/20 T-slot extrusion** — buy the raw extrusion/steel (or source free,
§2) **or machine our own rails from aluminum bar/plate on the mills/waterjet** (now viable).

**Best plan:** **Hobby Shop** for the metal cutting/welding/waterjet (one orientation,
everything in one room) **+ MITERS** for free 24/7 iterative drilling + electronics +
printing. Use **Metropolis/The Deep** for extra 3D printing & laser-cut brackets.

---

## 2. MATERIALS — free first, then cheap

### Free
- **`reuse@mit.edu` + `reuse-ask@mit.edu`** ⭐ — MIT give-away list; lab/shop cleanouts
  dump steel, extrusion, fasteners, electronics. **Post a request now:** "grad student
  looking for 80/20 extrusion / steel sheet / M3 T-nuts." High odds for steel/scrap/
  fasteners; real-but-moderate for 80/20. Sub: [mailman.mit.edu/.../reuse](https://mailman.mit.edu/mailman/listinfo/reuse) + [.../reuse-ask](https://mailman.mit.edu/mailman/listinfo/reuse-ask). [web.mit.edu/reuse](https://web.mit.edu/reuse/)
- **Edgerton Student Project Lab (4-409)** — free-use plywood, metal rods, machine
  screws/nuts/wood screws. [edgerton.mit.edu/.../student-project-lab-4-409](https://edgerton.mit.edu/for-MIT-students/student-shops/student-project-lab-4-409)
- **Makerspace scrap bins** — MITERS scrap piles (ask members in person).
- **Rheaply / Equipment Exchange (WW15)** — surplus routed through departments; ask
  your lab/dept admin to grab extrusion or steel shelving for you. [vpf.mit.edu/acquire-surplus](https://vpf.mit.edu/acquire-surplus-equipmentfurniture)

### Cheap
- **MIT Swapfest (Flea at MIT)** — 3rd Sunday monthly, **Apr–Oct**, Albany St Garage,
  9am-2pm, ~$6 cash, haggle. Metal, extrusion, test gear, hardware. [w1mx.mit.edu/flea](https://w1mx.mit.edu/flea-at-mit/)
- **MIT Furniture Exchange (FX), WW15-182** — Tue/Thu 10-4 + 1st Sat; cheap shelving to
  cannibalize for steel. [fx.mit.edu](https://fx.mit.edu/)
- **Off-campus:** Home Depot (Cambridge/Somerville) — Superstrut/Unistrut, plywood, lag
  screws, **TOGGLER SNAPTOGGLE** toggle bolts. Boston Building Resources & Habitat
  ReStore — cheap reuse lumber/steel/hardware. New 80/20 via [8020.net distributor lookup](https://8020.net/distributorlookup) (Air Inc. = regional distributor).

---

## 3. FUNDING (apply as a grad student)

- **Project Manus "Design-to-Making" mini-grant — up to $500** ⭐ for supplies/materials;
  best fit for a personal hardware build. + **$100 MakerBucks** after free MakerLodge/M1T
  training (spend on stock + machine time). [make.mit.edu](https://make.mit.edu/) · [project-manus.mit.edu/students](https://project-manus.mit.edu/students)
- **MIT Sandbox — $1K–$25K** seed; open to grad students but **venture-oriented** — only
  worth it if framed as a product/startup prototype. [mit-sandbox.smapply.io](https://mit-sandbox.smapply.io/)
- **Departmental discretionary funds** — ask advisor/admin; a faculty cost-object can buy
  MIT surplus for free.

---

## 4. 3D PRINTING — print the interface parts, buy the structural metal

**Where (grad-accessible, free):** Metropolis (FDM, free white **ABS** + MakerBucks for
PLA/PETG), The Deep (SLA/resin), MITERS (free), MakerWorkshop (lists FDM as free).
**Libraries do NOT 3D print.** The old paid Project Manus print service ended Dec 2023 —
it's all self-serve after training now.

**Material:** **ABS (free at Metropolis) or PETG/ASA** for anything load-bearing or near
the warm PSUs. **Avoid PLA** (creeps under load, softens with heat). Reserve SLA/resin
for cosmetic detail only (too brittle for brackets).

**Why this split:** structural metal (the extrusion carrying ~15 kg over 5 m) must be
bought/machined — stiffness/load is a job polymer can't do over that span. The
*interface* parts (brackets, clips, trays) are geometry-matching, not load-bearing, and
you need ~60 identical ones → printing wins.

**What to print:**
| Part | ~Size / qty | Why print | Material |
|---|---|---|---|
| **Panel-corner T-slot brackets** ⭐ | ~40×40×15 mm, 60+ | biggest win — M3 clearance into panel corners, hammer-nut onto 80/20; 60+ to buy | ABS/PETG |
| **Depth spacers / M3 standoffs** | 5-15 mm | set uniform panel standoff for ribbon clearance | ABS/PETG |
| **HUB75 ribbon + 5V cable raceways/clips** | snap into T-slot | corral cable along 5.1 m | PETG |
| **Pi 4 + bonnet tray** | ~90×60 mm | bolts to T-slot, clean HUB75 routing | ABS/PETG |
| **LRS-350-5 PSU brackets** | ~50×30 mm ×2 | clamp PSU flange to extrusion (heat) | **ASA/PETG** |
| **End caps** | 20×20 mm | cap 80/20 ends; pennies vs $1-2 ea | any |
| **Magnet holders** (magnet-mount only) | for 10-12 mm discs | vs Adafruit ~$0.63 ea × 60+ | ABS/PETG |

**Reusable designs:** Printables [1055338](https://www.printables.com/model/1055338),
[1294572](https://www.printables.com/model/1294572-brackets-for-joining-hub75-led-panels),
[1259766](https://www.printables.com/model/1259766) (HUB75 joiners); [463278](https://www.printables.com/model/463278),
[10750](https://www.printables.com/model/10750-corner-brackets-for-2020-extrusion) (2020 brackets);
Thingiverse [thing:2815656](https://www.thingiverse.com/thing:2815656).

---

## 5. RECOMMENDED MOVE ORDER
1. **Subscribe** to `reuse` + `reuse-ask` and **post a request now** (longest lead time).
2. Do the **free M1T/MakerLodge training** → bank $100 MakerBucks + makerspace access.
3. **Email Hobby Shop** (hayami@mit.edu) for the orientation — your one-stop metal shop.
4. **Apply** for the $500 Design-to-Making mini-grant.
5. Hit the next **Swapfest** + **FX** for cheap fill-ins.
6. **3D print** brackets/clips/PSU mounts in ABS/PETG at Metropolis or MITERS.
