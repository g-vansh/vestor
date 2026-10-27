# TROUBLESHOOTING
| Symptom | Cause | Fix |
|---|---|---|
| No output (black) | Panel needs init (rare for FM6124) | Try `--led-panel-type=FM6126A`, then `=FM6127`; remove again if no help |
| Dim/reddish | Power sag / voltage drop | Confirm 5V/60A PSU on + wired (Pi can't power panels). Far panels redshift if the 5V trunk is thin/CCA — inject at ≥2 points/half, use copper (INVENTORY §7.1). It's a power fault, NOT an rgb-sequence bug. |
| Panels different shades | Per-panel LED variance | hzeller is GLOBAL-only — can't per-panel calibrate. Mitigate: same batch, low brightness, dark content (INVENTORY §7.2). Last resort: `MapColors()` C++ fork. |
| Dead 16-px column / horizontal line | Dead FM6124 IC / dead row | Hardware defect — RMA the panel, swap a spare. Found via incoming-QC `demo -D5` (INVENTORY §7.3). |
| Garbage pixels | Pi 4 too fast | `--led-slowdown-gpio` 4→5 |
| Colors swapped | RGB order | `--led-rgb-sequence` RBG/BGR/GRB |
| Rows scrambled | Addressing | `--led-row-addr-type` 0→3→5 |
| Flicker | Sound on / HW pulse off | blacklist snd_bcm2835 + audio=off + disable_hardware_pulsing=False |
| Won't boot after cmdline edit | Malformed cmdline.txt | Restore cmdline.txt.bak on Mac |
| No flights | FlightRadarAPI change | `pip install FlightRadarAPI -U` or merge upstream |
| Same flights frozen on the board, journal says `holding last data` | Every position provider failed or is paused by the rate governor. The last flights stay up on purpose and `Overhead.stale` turns True after 2 min. | It clears by itself once a provider answers. Run `journalctl -u vestor -g overhead` to see which provider is paused and why (see the `rate stats` line). Check the Pi has internet. If the journal says `could not persist state`, delete `/tmp/vestor-ratelimit.json` because another user created it. |
| Journal says `airplanes.live failed (HTTP 403 (forbidden))` | airplanes.live now answers 403 to clients that have not registered with them. | Harmless. adsb.fi is the primary and airplanes.live is a last resort that is retried once an hour. To use it, email contact@airplanes.live with a short project description and the repo link. |
| Routes show `?` for a while, journal says `FR24 route lookup failed` | FR24 is throttling or blocking the unofficial feed (Cloudflare). | Expected and self-healing. FR24 waits 1 min, then 5 min, then 15 min and probes once per step. adsbdb plus the BOS check fill in routes meanwhile. If it never recovers, run `pip install -U FlightRadarAPI`. |
| Service dead | Path/env/caps | Check EnvironmentFile, venv path, setcap, journalctl |
| No SSH | WiFi/SSH not baked | Re-flash custom.toml; SSID + country=US |
