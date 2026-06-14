# OPEN QUESTIONS
Anything ambiguous or needing the owner. Everything else was built. Updated 2026-06-14.

## Needs the owner before the SD card can be flashed
1. **WiFi SSID + password.** Not supplied this session. `scripts/custom.toml.example`
   has placeholders (`OWNER_WIFI_SSID` / `OWNER_WIFI_PASSWORD`). The plaintext WiFi
   password is required (Bookworm's encrypted-WiFi path is unreliable); it goes ONLY
   into the SD image, never into git.
2. **pi user password.** Placeholder `CHANGEME_pi_password` in the example. Pick one
   (or use an SSH key via the commented `authorized_keys` line). For an encrypted
   user password: `openssl passwd -5 'thepassword'` → paste the `$5$...` hash and set
   `password_encrypted = true` under `[user]` only.

## Decisions I made autonomously (flagged so the owner can override)
3. **Hostname = `flightwall`** (so `ssh pi@flightwall.local`). The master prompt asked
   for `flightwall`; AGENTS.md / the setup guide said `flightpi`. I standardized the
   whole repo (scripts, docs, `.env`) to `flightwall`. To revert: change `hostname` in
   `scripts/custom.toml(.example)` and `PI_HOST` in `.env`, and re-run the hostname
   swap. See BUILD_LOG for rationale.
4. **Entry point = `flight-tracker.py`.** The scaffold service shipped `main.py`, but
   upstream's real entry file is `flight-tracker.py`. Corrected in
   `services/flightwall.service`. No owner action needed — just noting it.

## Not blocking (can be done anytime later)
5. **API keys** (`OPENWEATHER_API_KEY`, `MBTA_API_KEY`) are blank placeholders in the
   Pi's `.env`. The app runs fine without them. Add the free keys later, then
   `sudo systemctl restart flightwall`. NWS weather + Bluebikes need no key.

## To confirm only on real hardware (Phase 0 — do NOT do this session)
6. **RGB order / row addressing / slowdown.** `led_rgb_sequence=RGB`,
   `row_address_type=0`, `gpio_slowdown=4` are correct starting points but the FM6124
   panel family often needs a second pass. Knobs to try live: rgb_sequence RBG/BGR/GRB,
   row_addr_type 3 or 5, slowdown 5. Record the winners back into `display/__init__.py`.
7. **FM6126A + active3 replug gotcha (Phase 1).** The FM6126A init initializes all 3
   chains in one `--led-parallel=3` run; if you re-plug panels into different chains,
   power-cycle and re-init rather than re-sending init under a different topology
   (hzeller issue #947).
