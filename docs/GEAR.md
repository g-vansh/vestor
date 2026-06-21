# GEAR & TECH SCOUT — things that make the Vestor wall easier

Ranked findings from a 5-domain ecosystem scout (5 research agents, 2026-06-21):
power, HUB75 data/cabling, mounting/frame, **front-face/diffusion**, and
reliability/remote-ops. Each item: ~price + why it helps **us** specifically.
Honest **SKIP** flags included — this is not a shopping spree.

> Build context: 16× P5 HUB75 (64×32, 320×160 mm, FM6124D) · Pi 4 + Triple Bonnet
> (hzeller) · one 16-wide center-fed row (parallel=2, chain=8) · 2× LRS-350-5 (5V/60A)
> · wall-mounted above a window, always-on · custom Python departure board.

---

## ⭐ TOP PICKS — highest life-easier-per-dollar

| # | Item | ~$ | Why it changes our build |
|---|---|---|---|
| 1 | **Ferrule kit + ratchet crimper (AWG6–10)** | ~$45 | **Solves the CCA-won't-solder problem (§7.1)** — gas-tight crimp, no solder, won't loosen. |
| 2 | **UNI-T UT210E DC clamp meter** | ~$35 | Reads **DC amps** (cheap meters don't) → verify per-leg load balance + per-panel draw vs 7A fuse before sealing to the wall. |
| 3 | **Smoke-Gray #2064 tinted acrylic front face** | ~$15–25/ft² | Dark **contrast tint** (not white diffuser): deepens blacks, kills daylight wash, **hides panel shade variation (§7.2)**, zero text blur. |
| 4 | **Smart plug (Kasa KP125 / Shelly Plug)** | ~$15–22 | **Remote power-cycle both PSUs over Tailscale** — can't reach the wall once mounted. |
| 5 | **systemd hardware watchdog + overlayfs** | **FREE** | Auto-reboot a hung Pi; brownout-proof the SD card. ~90% of always-on failure modes for $0. |
| 6 | **Hanson PDist1 ×2 (PSU-side fused distro)** | ~$15 ea | Bolts onto the LRS-350-5 terminals (4×15A+1×10A fused) — the clean injection split *at the PSU*. |

---

## 1. POWER & ELECTRICAL

**Must-haves**
- **Hanson PDist1 fused distro (~$15 ea, buy 2)** — [wiredwatts.com/hanpdist1](https://www.wiredwatts.com/hanpdist1). Bolts directly onto each LRS-350-5's screw terminals → 4×15A + 1×10A fused outputs. Pairs with the panel-side hanpaneldistro (§7.1): PDist1 splits *at* the PSU, hanpaneldistro fuses *at* the panels.
- **UNI-T UT210E clamp meter (~$35)** — [Amazon](https://www.amazon.com/UT210E-Mini-Clamp-Meter-Current/dp/B08HPWQ89G). True **DC-amp** clamp; the tool that validates the power plan.
- **Ferrule kit + ratchet crimper, 6–10 AWG (~$45)** — [Aven crimper](https://aventools.com/products/crimping-tool-for-wire-ferrules-6-to-10-awg) + [FerrulesDirect FD1006SQ](https://www.ferrulesdirect.com/products/fd1006sq). **The CCA fix.**
- **Inline DC watt meter, Powerwerx bare-wire (~$40)** — [powerwerx](https://powerwerx.com/watt-meter-analyzer-inline-dc-bare-wire). Permanent per-PSU A/W readout (use on individual ~32A legs; 45A SKU fine there).
- **PSU output trim to ~5.1–5.2 V** — FREE (trim pot). The **LRS-350-5 has no remote-sense**, so this is how you compensate §7.1 voltage drop. Target far-panel ≥4.8V; don't exceed ~5.3V.

**Nice-to-have**
- **Ameritron ICP-120 soft-start / inrush limiter (~$90, 120V)** — [DX Engineering](https://www.dxengineering.com/parts/ame-icp-120). Tames cap-charge surge from 2×300W on one outlet; prevents nuisance breaker trips. **Buy the US/120V unit — NOT the 230V German ESB-230-16.**
- **Common-ground bond wire + ferrules (~$5)** — tie the two PSU 0V/negatives at one point (NOT the positives). Robust HUB75 signal reference.
- **Anderson Powerpole + TRIcrimp (~$50)** — makes panels/PSUs disconnectable without unscrewing terminals once wall-mounted. Optional serviceability.

**SKIP:** Hanson Power8 (pixel-prop scale), 250A marine bus bars, SB50/200A analyzers (EV-scale), NTC-only inrush mods (lose effect on always-on), remote-sense wiring (LRS has none).

---

## 2. HUB75 DATA & CABLING

> **Honest top-line:** the included panel ribbons already cover **14 of 16** inter-panel
> links. You only need to make/buy **2 short cables** (bonnet → first panel of each
> chain). Gains here are signal integrity + strain relief, not exotic cable.

**Must-haves**
- **Keadic 50-pc IDC kit + crimp tool (~$16)** — [Amazon](https://www.amazon.com/Keadic-Sockets-Assortment-Connector-Rainbow/dp/B09WMWPQY3). Crimp **exact-length, keyed 2×8** cables for the 2 bonnet feeds (stay <50 cm). Highest-value buy.
- **Clip-on ferrite cores (12-pk, ~$8)** — [Amazon](https://www.amazon.com/20pcs-Clip-Ferrite-Suppressor-Diameter/dp/B0CSXZWGD6). Snap near connectors → kill high-MHz EMI ("garbage pixels") over the 5 m metal frame.
- **Shrouded/keyed 2×8 IDC sockets (10-pk, ~$8)** — [Amazon](https://www.amazon.com/Pc-Accessories-Connectors-10-PACK-16-Pin/dp/B015MMVQ4E). Prevent the #1 HUB75 mistake: plugging the ribbon in backwards.
- **Adafruit 30 cm keyed 2×8 IDC cable ($1.95)** — [adafruit 4170](https://www.adafruit.com/product/4170). Drop-in correct-length feed cable if you'd rather not crimp.
- **Adhesive cable-tie mounts + zip ties (~$10)** — anchor each ribbon ~2 cm from the IDC so nothing tugs a connector loose.

**SKIP:** Electrodragon active-3 board (= what the bonnet already is); NovaStar/ColorLight receiver cards + shielded differential ribbon (forces you off Pi+bonnet+hzeller); extra level-shifter boards (bonnet already buffers; fix garbage via `gpio-slowdown 4–5` + short cables + ferrites). **No HUB75 "tester" needed — the Pi running a single-panel pattern IS the tester.**

---

## 3. MOUNTING, FRAME & ALIGNMENT

> **Decide steel vs aluminum early:** magnets need a **ferrous** surface. 80/20 aluminum
> is non-magnetic → either screw panels via M3 corners, or face the rail with a thin
> steel strip. (See §8 of INVENTORY for the full frame buildout.)

**Must-haves**
- **3D-printed HUB75 joiner brackets (FREE — print at MIT)** — [Printables 1294572](https://www.printables.com/model/1294572-brackets-for-joining-hub75-led-panels), [578204 (4-panel)](https://www.printables.com/model/578204-hub75-5mm-pitch-4-panel-bracket). Clamp adjacent panel edges + alignment-pin holes → **the coplanar/gap-free fix**, and can carry diffuser standoffs.
- **80/20 backbone** — **1010** (1"×1", ~$8–12/ft, [TNUTZ EX-1010](https://www.tnutz.com/product/ex-1010/)) is fine **with supports every 3–4 ft** (cheaper/lighter); step up to **1530** ([§8](INVENTORY.md)) for fewer supports / longer unsupported bays.
- **80/20 splice plates + hammer/T-nuts + gussets** — [8020 fastening](https://8020.net/fasteningmethods.html). Join 8 ft bars into the 16.8 ft span; gussets at the 3–4 ft support points.
- **Aluminum French cleat (renter-friendly)** — [SignBracketStore heavy-duty](https://www.signbracketstore.com/heavy-duty-french-cleat/). Whole wall lifts off in one motion; 2–3 anchors into studs above the window.
- **P5 magnet-screw spares (12-pk ~$1.20)** — [Wally's Lights](https://wallyslights.com/products/p5-panel-magnet-screw) / [wiredwatts](https://www.wiredwatts.com/panel-magnet). Only if mounting magnetically to a ferrous rail.

**Nice-to-have**
- **80/20 drop-in cable clips** ([8020](https://8020.net/finishingyourframe/cablewirepowermanagement.html)) + **D-Line adhesive raceway (~$15–40)** ([Amazon](https://www.amazon.com/D-Line-Raceway-Self-Adhesive-Electrical-Organizer/dp/B07C41H4B6)) — hide power/data runs, renter-safe.
- **80/20 leveling feet/shims + end caps** — keep the long row true; finish exposed slot ends.
- **WiredWatts "Build-a-Matrix" steel mounting strips** — [link](https://wiredwatts.com/build-a-matrix-kit). Off-the-shelf ferrous backing if you don't want to fabricate your own.

**SKIP:** die-cast rental cabinets (640×640/960×960, $100s, touring-grade), patent locking-rail/spring-pin systems (the free brackets achieve coplanarity).

---

## 4. FRONT FACE / DIFFUSION / FINISHING  (the pro-look layer)

> **Key finding:** for a **text-heavy P5 board in daylight**, the lever is a **dark
> contrast tint, NOT a white diffuser.** White diffusers smear 5 mm pixels → text mush.
> A smoke-gray/black tint deepens blacks, cuts daylight washout, AND masks panel shade
> variation — exactly our three problems — with **zero** sharpness loss (it's transparent).

**Recommended starting spec:** **1/8" (3 mm) Smoke-Gray #2064 cast acrylic, matte front
face, 5–10 mm air gap off the LEDs, in 3–4 seamed sections on magnetic standoffs with a
slim black aluminum bezel.** Buy **one 2'×4' test sheet (~$60–90) first** to dial in tint
density against brightness before the full 16.8 ft run.

- **#1 Smoke-Gray 2064 acrylic** (~25–35% transmission, ~$15–25/ft²) — [Canal Plastics](https://www.canalplastic.com/products/2064-gray-smoke-acrylic-sheet), [eStreet](https://www.estreetplastics.com/Grey-Smoke-Plexiglass-2064-s/80.htm). **Start here.**
- **#2 TAP black LED acrylic** (~3–15% transmission, ~$20–35/ft²) — [TAP](https://www.tapplastics.com/product/plastics/cut_to_size_plastic/black_led_sheet/668) / [ACRYLITE LED](https://www.acrylite.co/products/brands/acrylite-led). Deeper blacks, but dims output — test brightness headroom (we're already capped at 50).
- **#3 Matte/anti-glare front surface** — [A&C Plastics light-diffusing](https://www.acplasticsinc.com/categories/light-diffusing-acrylic). Glossy mirrors the window; get matte face out.
- **Mounting:** acrylic max ~96" → 3–4 sheets seamed **on panel boundaries**; magnetic standoffs ([MBS](https://mbs-standoffs.com/)) keep it removable for panel service.

**SKIP / verdict:** white/opal **light-diffusion at P5 — generally skip** (blurs text; only works with an air gap that blooms 5 mm pixels). [pixelmatix thread](https://community.pixelmatix.com/t/diffuser-sheet-ideas/408).

---

## 5. RELIABILITY & REMOTE OPS  (always-on, hard to reach)

> **Hard constraint:** the Triple Bonnet **occupies the full 40-pin GPIO header** → no
> GPIO-stacking UPS HATs or fan SHIMs. Everything routes to **AC-side + USB-side**.

**Must-haves**
- **Remote power-cycle — Shelly Plug US (~$22, preferred) or Kasa KP125 (~$15)** — [Shelly](https://us.shelly.com/products/shelly-plug-us-gen4-white) (pure-local REST, cloud-free over Tailscale) / [Kasa](https://www.amazon.com/Kasa-Smart-Monitoring-Compact-Certified/dp/B0BYGRLRS1) ([python-kasa](https://github.com/python-kasa/python-kasa)). Switch the AC feeding **both PSUs** → one command reboots the wall.
- **systemd hardware watchdog (BCM2835) — FREE** — set `RuntimeWatchdogSec=14s` in `/etc/systemd/system.conf` + `WatchdogSec=` on `vestor.service`. Auto-reboots a hung Pi / restarts a wedged service. **Highest-leverage "dark wall" fix.** [guide](https://bends.se/?page=notebook%2Fsbc%2Fraspberry-pi%2Fhw-watchdog).
- **Read-only root filesystem (overlayfs) — FREE** — `raspi-config` → Performance → Overlay FS. Makes the SD **power-loss/brownout tolerant** (writes go to RAM). Pair with remote logging. [guide](https://www.dzombak.com/blog/2024/03/running-a-raspberry-pi-with-a-read-only-root-filesystem/).
- **Pi cooling — Official Pi 4 Case Fan + Heatsink (~$7.25)** — [Adafruit 4794](https://www.adafruit.com/product/4794). USB/5V powered (no GPIO needed); clears the throttle point under sustained hzeller load.

**Nice-to-have**
- **Quiet Noctua fan (NF-A8 ~$15) for PSU/enclosure** — moves air across both warm LRS-350-5 + Pi; extends cap life. Keep Mean Well vent slots clear; metal (non-combustible) enclosure.
- **Remote logging — systemd-journal-remote over Tailscale — FREE** — debug a crash without SSHing into a wedged Pi (needed once rootfs is read-only).

**SKIP:** Pi UPS HATs (Geekworm X728 / PiSugar — GPIO conflict + overlayfs makes them redundant), 52Pi ICE Tower (may foul the bonnet ribbon; $7 fan suffices), USB watchdog dongles (BCM2835 is built-in/free).

---

## TIERED SHOPPING SUMMARY

- **Free wins (do regardless):** systemd watchdog, overlayfs, remote logging, PSU output trim, 3D-printed joiner brackets (print at MIT).
- **~$120 reliability+QC bundle:** ferrule crimper+kit ($45), DC clamp meter ($35), smart plug ($20), Pi fan ($7), ferrites ($8).
- **~$60–90 aesthetics test:** one 2'×4' Smoke-Gray 2064 sheet to prototype the front face.
- **Power refinement:** 2× PDist1 (~$30), inline watt meter (~$40), optional inrush limiter (~$90).
- **Defer until panels are in hand / bench-verified:** front-face full run, frame profile choice (1010 vs 1530), magnet vs screw mount.
