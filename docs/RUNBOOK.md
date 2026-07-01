# RUNBOOK
Detailed, copy-paste expansion of AGENTS.md §7. Execute top to bottom; log to BUILD_LOG.md.

1. **[MAC] Repo** — `gh` auth, clone its-a-plane-python → rename remote `upstream` → `gh repo create g-vansh/vestor --source=. --remote=origin --push`. Write scaffold. Commit.
2. **[MAC] Flash** — `scripts/flash_sd.sh /dev/diskN` (confirm device via `diskutil list`!). Bake hostname/SSH/WiFi/locale via `custom.toml`. Eject, boot Pi, `ssh pi@vestor.local`.
2.5 **[MAC] Reach the Pi on an isolated Wi-Fi (MIT PSK)** — `.local`/mDNS is blocked by client isolation, so bake in Tailscale: put a Reusable+Pre-approved key in `scripts/vestor-tailscale.auth` (gitignored), run `scripts/install_tailscale_firstboot.sh /Volumes/bootfs` AFTER the wizard flash + BEFORE eject. Boot the Pi, watch for `vestor` at login.tailscale.com/admin/machines, install Tailscale on the Mac, then `ssh pi@vestor` (Tailscale SSH/MagicDNS) instead of `ssh pi@vestor.local`.
3. **[PI] Base+driver** — `scripts/setup_pi.sh` (apt build toolchain incl. cmake+ninja; clone hzeller upstream source; blacklist sound; audio=off; isolcpus=3 with backups, cmdline stays ONE line). Driver is compiled later by pip. Reboot to apply tweaks.
4. **[PI] App** — `scripts/install_app.sh` (venv; build the `rgbmatrix` binding from the matrix **repo root** via scikit-build-core/CMake — modern upstream has NO `bindings/python/setup.py`; it fetches Pillow libImaging headers onto `CPATH` so `shims/pillow.c` compiles, no runtime Pillow dep; installs deps with `rgbmatrix` filtered out; `setcap cap_sys_nice`; scaffolds `.env`). `display/__init__.py` already set to regular/panel_type=""(FM6124D is standard, no init)/parallel=1/disable_hardware_pulsing=False; `config.py` is committed (location/zone). Verify `import rgbmatrix` OK.
5. **[PI] Service** — `scripts/install_service.sh` installs + daemon-reloads the unit but does NOT enable/start it (hard stop #5). Enable/start happen in the supervised Phase 0 test.
6. **[EITHER] Dry validation** — everything above works without panels (service error-loops harmlessly).
7. **[PI] Phase 0 test** — wire 1 panel (data→Port 1, power→PSU). Run hzeller `demo -D0` (already built at `~/rpi-rgb-led-matrix/examples-api-use/demo`) with `--led-gpio-mapping=regular --led-rows=32 --led-cols=64 --led-chain=1 --led-parallel=1 --led-slowdown-gpio=4 --led-show-refresh` (NO `--led-panel-type` — FM6124D is standard; add `=FM6126A`/`=FM6127` only if black). Fix rgb-sequence + row-addr per the tuning ladder. Then run `vestor-tracker.py`.
8. **[PI] Full wall** — 2×8 **center-feed** (`parallel=2 chain=8`, Ports 1&2; left half 180° + SW-flip → 512×64 canvas), retune slowdown, pwm_bits 7–9, 2× LRS-350-5 + fuse blocks. See INVENTORY §5/§9.

---

## ✅ Phase 0 — CONFIRMED & OPERATING (2026-07-01)

**First light** (one panel) — the exact working demo command:
```
sudo ~/rpi-rgb-led-matrix/examples-api-use/demo \
  --led-cols=64 --led-rows=32 --led-chain=1 --led-parallel=1 \
  --led-gpio-mapping=regular --led-slowdown-gpio=4 --led-brightness=50 -D0
```
Config confirmed: **no `--led-panel-type`** (FM6124HJ standard), `regular` mapping,
slowdown 4, default RGB (colors correct). ⚠️ **Gotcha:** a panel on the WRONG bonnet
port shows a stuck "top-half white" block that mimics a driver fault — the demo drives
lane 0 = **Port 1**. Match ribbon port ↔ parallel lane.

**Remote access:** Pi is on Tailscale (MIT 5 GHz). From the Mac: **`ssh root@vestor`**
(or `ssh root@100.91.127.127`) — Tailscale identity auth, no password. (`pi` sudo needs a
password; `iw` isn't installed — use `nmcli` / `/proc/net/wireless`.)

**Run the "one-screen" flight-tracker app:**
```
sudo systemctl start vestor        # start (restart/stop/status likewise)
journalctl -u vestor -f            # live logs
```
Service = `vestor.service` + a `User=root` drop-in (`/etc/systemd/system/vestor.service.d/
override.conf`), **enabled** (starts on boot), `Restart=always`. Shows live Logan flights
+ weather + clock.

**⚠️ TEMP Port-3 shim — REVERT before the full-wall build:** the test panel was on
**Port 3**, so `display/__init__.py` was patched to `parallel=3` + a **+64-row draw
offset** (lane 2). Backup: `display/__init__.py.port1bak`. **Revert:** `cp display/__init__.py.port1bak
display/__init__.py` (or move the ribbon to Port 1 and set `parallel=1`). The real wall
uses 2×8 center-feed (`parallel=2`, Ports 1&2).
