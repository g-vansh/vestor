# OPEN QUESTIONS
Anything ambiguous or needing the owner. Everything else was built. Updated 2026-07-01
(Phase 0 / first light complete).

## Resolved this session (owner provided / decided)
1. **WiFi** — SSID + password provided by the owner and baked ONLY into the
   gitignored `scripts/custom.toml` (plaintext, `password_encrypted = false`). Never
   committed; lives only in the SD image.
2. **pi user password** — provided by the owner; baked into `scripts/custom.toml`
   under `[user]` (plaintext). Never committed.
3. **Project code name / hostname = `vestor`.** The owner set the code name to
   "vestor"; the whole repo is standardized to it — hostname `vestor`
   (`ssh pi@vestor.local`), on-Pi path `/home/pi/vestor`, `services/vestor.service`.
4. **Entry point = `vestor-tracker.py`** (renamed from upstream `flight-tracker.py`);
   `services/vestor.service` `ExecStart` points at it.

## ✅ RESOLVED — Pi reachability (2026-07-01)
8. **Tailscale — DONE.** Auth key baked in, the Pi enrolled, and it's reachable:
   **root Tailscale SSH works from the Mac** (`ssh root@vestor` / `100.91.127.127`,
   identity auth, no password). *Gotcha fixed 2026-07-01:* WiFi wouldn't re-provision
   because `cmdline.txt` hardcoded the cloud-init instance-id (`ds=nocloud;i=…`),
   overriding `meta-data` — so an added network never applied. Fixed by changing `i=`
   in `cmdline.txt` (see BUILD_LOG). Pi runs on **MIT 5 GHz** (client isolation is moot
   for outbound Tailscale); `Vestor` phone hotspot configured as a backup network.

## Decisions I made autonomously (flagged so the owner can override)
- **Tailscale chosen over MIT SECURE / Ethernet for reachability.** MIT SECURE
  (802.1X) was rejected (Kerberos credential exposed on a wall appliance, brittle,
  uncertain it even fixes peer reachability); no Ethernet cable on hand; personal
  APs/routers are barred on MITnet. Tailscale is outbound-only, policy-compliant, and
  durable for a wall-mounted display. To undo: `sudo tailscale down && sudo apt remove
  tailscale` on the Pi and delete the node in the console.
- **GitHub repo renamed `g-vansh/flightwall` → `g-vansh/vestor`** to match the code
  name. GitHub auto-redirects the old URL. To revert: rename back in repo Settings
  and `git remote set-url origin`.
- **On-Pi directory `/home/pi/vestor`** (matches repo + code name). The clone target
  in `install_app.sh` and all service paths use it. Safe because the Pi is not yet
  provisioned — nothing to migrate.

## Not blocking (can be done anytime later)
5. **API keys** (`OPENWEATHER_API_KEY`, `MBTA_API_KEY`) are blank placeholders in the
   Pi's `.env`. The app runs fine without them. Add the free keys later, then
   `sudo systemctl restart vestor`. NWS weather + Bluebikes need no key.

## Wall mount — profile captured (owner-confirmed 2026-07-02)
9. **RESOLVED — how to hang the row.** The top of the wall has a **full-width
   (~512 cm) structural WOODEN rail** with a continuous **open-top pocket
   (1.4 cm × 5 cm)** and a **3.4 cm-proud lip**. Plan: **top cleat/tongue (≤1.4 cm)
   dropped into the pocket** carries the whole row; lip gives standoff; PSUs mount
   separately. Full spec: `docs/design/WALL_PROFILE.md`. (Supersedes the panel
   "mounting method TBD" / magnet question — the plastic rear frames don't need
   magnets if the assembly hangs from a cleat.)
10. **TO CONFIRM ON-SITE (before building the cleat):**
    - Depth behind the 3.4 cm lip for the **center Pi + Triple Bonnet stack**
      (~4–5 cm deep) — may exceed 3.4 cm → recess it or mount the Pi/bonnet just
      below the row.
    - **Panel weight** (≈0.5 kg each?) → total hung load (~10–13 kg est.).
    - Pocket is **uniform/continuous** across the full 512 cm.
    - Wall material behind (studs?) for a **bottom restraint** fixing.
    - Desired **height** of the row (pocket puts the top ~22.5 cm below the ceiling).

## ✅ CONFIRMED on hardware at first light (2026-07-01)
6. **RGB order / row addressing / slowdown — CONFIRMED, no tweaks needed.**
   `rgb_sequence=RGB` (colors correct, no swap), `row_address_type=0`, `gpio_slowdown=4`
   all worked on the first real run. Matches `display/__init__.py` / `config.py` as-is.
7. **FM6126A init — NOT needed (confirmed).** Panel lit fully with **no** `--led-panel-type`;
   adding `FM6126A` made zero difference. FM6124HJ = standard driver, exactly as
   determined. *(The actual first-light gotcha was physical — ribbon on the wrong bonnet
   port shows a stuck half-lit block that mimics a driver fault; see BUILD_LOG 2026-07-01.)*
