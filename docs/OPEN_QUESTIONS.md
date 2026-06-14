# OPEN QUESTIONS
Anything ambiguous or needing the owner. Everything else was built. Updated 2026-06-14.

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

## Decisions I made autonomously (flagged so the owner can override)
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

## To confirm only on real hardware (Phase 0 — do NOT do this session)
6. **RGB order / row addressing / slowdown.** `led_rgb_sequence=RGB`,
   `row_address_type=0`, `gpio_slowdown=4` are correct starting points but the FM6124
   panel family often needs a second pass. Knobs to try live: rgb_sequence RBG/BGR/GRB,
   row_addr_type 3 or 5, slowdown 5. Record the winners back into `display/__init__.py`.
7. **FM6126A + active3 replug gotcha (Phase 1).** The FM6126A init initializes all 3
   chains in one `--led-parallel=3` run; if you re-plug panels into different chains,
   power-cycle and re-init rather than re-sending init under a different topology
   (hzeller issue #947).
