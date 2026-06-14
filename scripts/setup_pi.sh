#!/usr/bin/env bash
# [PI] Idempotent: OS update, matrix driver, flicker/quality tweaks. Re-runnable.
set -euo pipefail

sudo apt update && sudo apt full-upgrade -y

# hzeller driver via Adafruit installer (choose: Adafruit RGB Matrix Bonnet + QUALITY)
if [ ! -d "$HOME/rpi-rgb-led-matrix" ]; then
  curl -fsSL https://raw.githubusercontent.com/adafruit/Raspberry-Pi-Installer-Scripts/main/rgb-matrix.sh > /tmp/rgb-matrix.sh
  echo ">>> Running Adafruit installer. Select: Adafruit RGB Matrix Bonnet, then QUALITY."
  sudo bash /tmp/rgb-matrix.sh
fi

# Disable onboard sound (flicker) — blacklist + dtparam
echo "blacklist snd_bcm2835" | sudo tee /etc/modprobe.d/blacklist-rgb-matrix.conf >/dev/null
sudo update-initramfs -u || true

# config.txt: dtparam=audio=off (back up first)
sudo cp -n /boot/firmware/config.txt /boot/firmware/config.txt.bak
sudo sed -i 's/^dtparam=audio=on/dtparam=audio=off/' /boot/firmware/config.txt
grep -q '^dtparam=audio=off' /boot/firmware/config.txt || echo 'dtparam=audio=off' | sudo tee -a /boot/firmware/config.txt >/dev/null

# cmdline.txt: append isolcpus=3 to the SINGLE line (back up, verify, one line)
sudo cp -n /boot/firmware/cmdline.txt /boot/firmware/cmdline.txt.bak
if ! grep -q 'isolcpus=3' /boot/firmware/cmdline.txt; then
  sudo sed -i 's/$/ isolcpus=3/' /boot/firmware/cmdline.txt
fi
echo ">>> Verify cmdline.txt is ONE line:"; cat /boot/firmware/cmdline.txt
echo ">>> Reboot when ready:  sudo reboot"
