# INVENTORY — Vestor 16-panel wall

Inventory & gap analysis for the full **1024×32, 16-panel** wall (Phase 1).
Compiled 2026-06-21. Sources: `HARDWARE.md`, `AGENTS.md`, `BUILD_LOG.md`
(2026-06-14 power research), Adafruit product pages for 6358 / 1466.

> **Headline:** the **Adafruit 5V 4A supply (P1466) should be returned** — it has
> no role in a Triple-Bonnet + 2×LRS-350-5 build. Everything else specced is
> correct; the real remaining *purchases* are the **power-distribution harness**
> (bus bars + heavy wire + lugs), a **rigid mounting frame**, and a couple of
> small confirmations (Pi USB-C supply, 2nd AC cord).

---

## 1. WHAT I HAVE  (specced / ordered — confirm each on the bench)

| Item | Spec | Qty | Notes |
|---|---|---|---|
| Raspberry Pi 4 Model B | 4 GB (not Pi 5 — hzeller driver maturity) | 1 | Brain of the wall |
| Adafruit **Triple** RGB Matrix Bonnet | **PID 6358**, "active3", 3 chains, no onboard power, no soldering | 1 | `hardware_mapping=regular`, `parallel=3`. Logic powered from Pi header. |
| P5 HUB75 panels | MUEN P5, **64×32**, 320×160 mm, **1/16 scan**, SMD2121, 5V, **FM6124D** | **16** | Each ships with: 1 short HUB75 ribbon (panel→panel) + 1 power cable per **2** panels + magnet mounting screws. *In transit / verify on arrival.* |
| Mean Well **LRS-350-5** PSU | 5V / **60 A** / 300 W each (derated) | **2** | Wall budget = 120 A. 1 of these alone runs Phase 0. |
| microSDXC | 64 GB Verbatim (V10/U1) | 1 | Already flashed with the Vestor image. Make a backup image once tuned. |
| Fork-lug AC cord | Pre-terminated, plug-in | 1 | Mains → PSU. **You have 1; the wall has 2 PSUs → see §3.** |
| ~~Adafruit 5V 4A supply~~ | **PID 1466**, 5.5/2.1 mm barrel, $14.95 | 1 | **RETURN — see §4.** |

---

## 2. WHAT I NEED  (gaps to buy/source before the wall lights up)

### A. Power-distribution harness  *(the big one)*
The Triple Bonnet has **no power connector** — Adafruit's own guidance is to feed
panels directly from the supply via **"two power distribution bus bars."** You
inject 5V in parallel at every panel/pair; never daisy-chain panel power through
HUB75. (Per `BUILD_LOG` 2026-06-14 power research.)

| Need | Spec | Why |
|---|---|---|
| **Bus bars** ×2 (5V + GND) | screw-down power distribution blocks, ≥60 A | The parallel-injection backbone Adafruit recommends |
| **Trunk wire** | AWG **10–12** | PSU → bus bars (carries up to 60 A per supply) |
| **Pigtail wire** | AWG **14–16** | Bus bar → each panel pair (panels ship with a pigtail; you supply the injection runs + spares) |
| **Crimp lugs / ferrules** + crimp tool | fork lugs for PSU terminals, ring/spade for bus bars | Clean, low-resistance joints; keep the far panel **≥4.8 V** |
| **Common-ground bond wire** | AWG 14 | Tie PSU1 ⏚ ↔ PSU2 ⏚ ↔ Pi/bonnet ⏚ — **mandatory** for HUB75 signal integrity |
| **2nd AC cord** | fork-lug or IEC, fused/switched if possible | One per PSU (you have 1, need 2) — *confirm in §3* |
| *(optional)* inline fuse/breaker per 5V trunk | 60–80 A | Protects against a panel/wire fault |
| *(optional)* 3rd LRS-350-5 | only for **unrestricted all-white** | **NOT needed** with `BRIGHTNESS=50` cap (worst case ~64 A < 120 A). Skip it. |

### B. Mounting / structure
| Need | Notes |
|---|---|
| Rigid backing/frame | Panels = magnets + 4 corner standoffs, but need a flat rigid substrate. For a single 16-wide row that's **~5120 × 160 mm**; if stacked 6+5+5 it's wider/shorter. **Physical layout not yet locked** (see §5). Aluminium extrusion / unistrut / plywood + steel strip for the magnets. |
| Panel-to-panel alignment | Most P5 panels include link plates/screws; verify on arrival. |

### C. Pi-side power & data
| Need | Notes |
|---|---|
| **Official Pi 4 USB-C supply** (5V/3A, 15 W) | The Pi is powered **separately** from the panels and also feeds the bonnet's logic rail. **Confirm you have one** — not in the BOM. |
| HUB75 **ribbon extensions** | Only if the final layout is one long row: chains 2 & 3 start mid-row, far from the bonnet's ports. Decide after §5 is locked. |

---

## 3. CONFIRM (cheap to verify, avoids a stall)
- [ ] **2nd fork-lug AC cord** — `HARDWARE.md` lists "a fork-lug AC cord" (singular); the wall has **2 PSUs**.
- [ ] **Pi 4 USB-C power supply** on hand (5V/3A official, or a known-good 15 W+).
- [ ] **Panels arrived** and match spec on the silkscreen: FM6124D, 1/16 scan, 64×32; each came with ribbon + 1 power cable / 2 panels + mounting screws.
- [ ] **Final physical arrangement** locked (drives ribbon-extension + frame purchases — §5).

---

## 4. WHAT TO RETURN

### ✅ Adafruit **5V 4A switching power supply — PID 1466** ("P1466")
**Return it.** It is the recommended companion for the **single** RGB Matrix
Bonnet (PID 3211), which has a barrel jack and powers one panel + its own logic
from that one supply. This build uses the **Triple** Bonnet (6358), which:
1. has **no barrel jack / screw terminal** — nothing for it to plug into;
2. draws **logic power from the Pi's GPIO header**, so no small 5V brick is needed;
3. powers panels from the **LRS-350-5** supplies — 60 A each vs the P1466's 4 A
   (one P5 panel alone can pull ~8 A all-white, so 4 A wouldn't drive even one).

It also can't power the Pi 4 (wrong connector — Pi 4 = USB-C, not 5.5/2.1 mm).
→ Net: redundant on connector, role, and capacity. Refund ≈ $14.95.

### ⚠️ Conditional: a **single** Adafruit RGB Matrix Bonnet (PID 3211)
No evidence one was bought, but if an earlier order included the single bonnet
**and** the P1466 (a common starter pairing), the single bonnet is also
superseded by the Triple (6358) — return both.

---

## 5. OPEN — physical layout not yet locked
The renderer treats the wall as one **1024×32** ribbon (16 wide × 1 tall =
5120 × 160 mm). Electrically the bonnet drives **3 chains of 6+5+5**. Whether
those three chains are laid out as **one long row** (chains = segments 1–6 / 7–11
/ 12–16) or **three stacked rows** changes:
- **ribbon routing** (one row needs extensions to reach chains 2 & 3 from the bonnet),
- **frame dimensions**, and
- the `pixel_mapper_config` in `display/__init__.py` (`BUILD_LOG` flags this as TODO).

Lock this before buying frame + ribbons.
