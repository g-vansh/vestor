# RUNBOOK
Detailed, copy-paste expansion of AGENTS.md §7. Execute top to bottom; log to BUILD_LOG.md.

1. **[MAC] Repo** — `gh` auth, clone its-a-plane-python → rename remote `upstream` → `gh repo create g-vansh/vestor --source=. --remote=origin --push`. Write scaffold. Commit.
2. **[MAC] Flash** — `scripts/flash_sd.sh /dev/diskN` (confirm device via `diskutil list`!). Bake hostname/SSH/WiFi/locale via `custom.toml`. Eject, boot Pi, `ssh pi@vestor.local`.
2.5 **[MAC] Reach the Pi on an isolated Wi-Fi (MIT PSK)** — `.local`/mDNS is blocked by client isolation, so bake in Tailscale: put a Reusable+Pre-approved key in `scripts/vestor-tailscale.auth` (gitignored), run `scripts/install_tailscale_firstboot.sh /Volumes/bootfs` AFTER the wizard flash + BEFORE eject. Boot the Pi, watch for `vestor` at login.tailscale.com/admin/machines, install Tailscale on the Mac, then `ssh pi@vestor` (Tailscale SSH/MagicDNS) instead of `ssh pi@vestor.local`.
3. **[PI] Base+driver** — `scripts/setup_pi.sh` (Adafruit installer: Bonnet + QUALITY; blacklist sound; audio=off; isolcpus=3 with backups). Reboot.
4. **[PI] App** — `scripts/install_app.sh` (venv, deps, matrix binding, setcap). Edit `display/__init__.py` → regular/FM6126A/parallel/disable_hardware_pulsing=False. Fill `config.py` + `.env`. Commit.
5. **[PI] Service** — `scripts/install_service.sh`. Verify `systemctl status` + `journalctl`.
6. **[EITHER] Dry validation** — everything above works without panels (service error-loops harmlessly).
7. **[PI] Phase 0 test** — wire 1 panel (data→Port 1, power→PSU). Run hzeller `demo` with parallel=1/chain=1/FM6126A/slowdown=4/show-refresh. Fix rgb-sequence + row-addr. Run `vestor-tracker.py`.
8. **[PI] Full wall** — chain_length=6, parallel=3, pixel-mapper for 6+5+5, retune slowdown, pwm_bits 7–9, 2 PSUs + bus bars.
