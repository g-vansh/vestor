#!/usr/bin/env bash
# [MAC] Flash Raspberry Pi OS Lite (64-bit, Bookworm) headless, with hostname +
# SSH + WiFi baked in via Bookworm's custom.toml.
#
# DANGER: this ERASES a physical disk (hard stop #1). The script refuses the
# internal disk and any non-external device, and still requires you to type ERASE.
# If you are not 100% sure which /dev/diskN is the SD card, STOP and re-run
# `diskutil list` — do not guess.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- 1) Identify the SD card FIRST. Do NOT guess. ----
#   diskutil list           # find the ~64GB EXTERNAL/removable disk, e.g. /dev/disk4
DISK="${1:?Usage: flash_sd.sh /dev/diskN  (run 'diskutil list' first; pick the ~64GB EXTERNAL SD)}"

echo ">>> Target: $DISK"
diskutil info "$DISK" | grep -E 'Device / Media Name|Disk Size|Internal|Removable|Protocol' || true

# Safety: refuse the internal disk outright.
if diskutil info "$DISK" | grep -qE 'Internal:[[:space:]]+Yes'; then
  echo "REFUSING: $DISK reports Internal: Yes. That is not the SD card. Aborting." >&2
  exit 1
fi
case "$DISK" in
  /dev/disk0|/dev/disk0s*|/dev/disk9|/dev/disk9s*)
    echo "REFUSING: $DISK is an internal physical disk on this Mac. Aborting." >&2
    exit 1 ;;
esac

read -rp "Type ERASE to confirm $DISK is the SD card and NOT your Mac disk: " ok
[ "$ok" = "ERASE" ] || { echo "Aborted."; exit 1; }

# ---- 2) Prepare the headless config (custom.toml). ----
# Copy scripts/custom.toml.example -> scripts/custom.toml (gitignored), then fill in
# the WiFi SSID/password + a pi password. This script will NOT proceed with the
# placeholder values still in place.
TOML="$HERE/custom.toml"
if [ ! -f "$TOML" ]; then
  cp "$HERE/custom.toml.example" "$TOML"
  echo ">>> Created $TOML from the example. Edit it (WiFi SSID/password + pi password), then re-run." >&2
  exit 2
fi
if grep -qE 'OWNER_WIFI_SSID|OWNER_WIFI_PASSWORD|CHANGEME_pi_password' "$TOML"; then
  echo ">>> $TOML still has placeholder values. Fill in WiFi + pi password, then re-run." >&2
  exit 2
fi

# ---- 3) Flash the base image, then drop custom.toml into the boot partition. ----
# Preferred: Raspberry Pi Imager GUI (select 'Raspberry Pi OS Lite (64-bit)' + your SD),
# OR the CLI:  rpi-imager --cli <image.img.xz> "$DISK"
# After the write completes, the FAT boot partition mounts at /Volumes/bootfs:
#   cp "$TOML" /Volumes/bootfs/custom.toml
#   diskutil eject "$DISK"
RPI_IMAGER="/Applications/Raspberry Pi Imager.app/Contents/MacOS/rpi-imager"
echo ">>> rpi-imager: ${RPI_IMAGER}"
echo ">>> Flash 'Raspberry Pi OS Lite (64-bit)' to $DISK (GUI or: rpi-imager --cli <img> $DISK),"
echo ">>>   then:  cp \"$TOML\" /Volumes/bootfs/custom.toml && diskutil eject $DISK"
echo ">>> Insert the SD into the Pi, power on, wait ~60-90s, then:  ssh pi@vestor.local"
