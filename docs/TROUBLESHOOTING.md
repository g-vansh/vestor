# TROUBLESHOOTING
| Symptom | Cause | Fix |
|---|---|---|
| No output (black) | Panel needs init (rare for FM6124) | Try `--led-panel-type=FM6126A`, then `=FM6127`; remove again if no help |
| Dim/reddish | Power | Confirm 5V/60A PSU on + wired (Pi can't power panels) |
| Garbage pixels | Pi 4 too fast | `--led-slowdown-gpio` 4→5 |
| Colors swapped | RGB order | `--led-rgb-sequence` RBG/BGR/GRB |
| Rows scrambled | Addressing | `--led-row-addr-type` 0→3→5 |
| Flicker | Sound on / HW pulse off | blacklist snd_bcm2835 + audio=off + disable_hardware_pulsing=False |
| Won't boot after cmdline edit | Malformed cmdline.txt | Restore cmdline.txt.bak on Mac |
| No flights | FlightRadarAPI change | `pip install FlightRadarAPI -U` or merge upstream |
| Service dead | Path/env/caps | Check EnvironmentFile, venv path, setcap, journalctl |
| No SSH | WiFi/SSH not baked | Re-flash custom.toml; SSID + country=US |
