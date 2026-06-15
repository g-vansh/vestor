#!/usr/bin/env bash
# [PI] Idempotent OS prep: update, build toolchain, clone the matrix driver source,
# and apply flicker/quality tweaks. Re-runnable. The driver's Python module is BUILT
# later by install_app.sh — current hzeller upstream compiles it from the repo ROOT
# via scikit-build-core/CMake (there is NO bindings/python/setup.py anymore), so we
# only fetch the source here.
set -euo pipefail

sudo apt update && sudo apt full-upgrade -y

# Build toolchain for the rgbmatrix Python binding (scikit-build-core + CMake) plus
# setcap (libcap2-bin). cmake + ninja-build are REQUIRED by upstream's pyproject.toml.
sudo apt install -y git build-essential python3-dev python3-venv \
                    cmake ninja-build libcap2-bin curl

# Matrix driver source (official hzeller upstream). Just clone; do NOT `make` here —
# pip (scikit-build-core) rebuilds the C lib + Cython extension from the repo root.
if [ ! -d "$HOME/rpi-rgb-led-matrix/.git" ]; then
  git clone https://github.com/hzeller/rpi-rgb-led-matrix.git "$HOME/rpi-rgb-led-matrix"
else
  git -C "$HOME/rpi-rgb-led-matrix" pull --ff-only || true
fi

# ---- flicker / quality tweaks ----
# Onboard sound (snd_bcm2835) shares PWM hardware with the matrix → flicker. Kill it
# two ways: blacklist the module and disable it in firmware.
echo "blacklist snd_bcm2835" | sudo tee /etc/modprobe.d/blacklist-rgb-matrix.conf >/dev/null

# config.txt: dtparam=audio=off (back up first)
sudo cp -n /boot/firmware/config.txt /boot/firmware/config.txt.bak
sudo sed -i 's/^dtparam=audio=on/dtparam=audio=off/' /boot/firmware/config.txt
grep -q '^dtparam=audio=off' /boot/firmware/config.txt || \
  echo 'dtparam=audio=off' | sudo tee -a /boot/firmware/config.txt >/dev/null

# cmdline.txt: append isolcpus=3 to the SINGLE line so the matrix refresh thread
# (taskset -c 3) gets an uncontended core. HARD STOP: keep ONE line with NO trailing
# newline; back up first and restore the backup if the edit ever yields >1 line.
sudo cp -n /boot/firmware/cmdline.txt /boot/firmware/cmdline.txt.bak
if ! grep -qw 'isolcpus=3' /boot/firmware/cmdline.txt; then
  printf '%s isolcpus=3' "$(tr -d '\n' < /boot/firmware/cmdline.txt)" \
    | sudo tee /boot/firmware/cmdline.txt.new >/dev/null
  sudo mv /boot/firmware/cmdline.txt.new /boot/firmware/cmdline.txt
fi
LINES="$(awk 'END{print NR}' /boot/firmware/cmdline.txt)"
if [ "$LINES" != "1" ]; then
  echo "ERROR: cmdline.txt is $LINES lines — restoring backup. DO NOT BOOT." >&2
  sudo cp /boot/firmware/cmdline.txt.bak /boot/firmware/cmdline.txt
  exit 1
fi
echo ">>> cmdline.txt (ONE line):"; cat /boot/firmware/cmdline.txt; echo
echo ">>> OS prep done. Next: scripts/install_app.sh   (reboot later to apply tweaks)"
