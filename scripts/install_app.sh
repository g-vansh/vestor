#!/usr/bin/env bash
# [PI] Idempotent: clone/pull app, venv, deps, matrix binding, setcap. Re-runnable.
set -euo pipefail
APP="$HOME/flightwall"

if [ ! -d "$APP/.git" ]; then
  git clone https://github.com/g-vansh/flightwall.git "$APP"
else
  git -C "$APP" pull --ff-only || true
fi
cd "$APP"
[ -d env ] || python3 -m venv env
# shellcheck disable=SC1091
source env/bin/activate
pip install --upgrade pip
[ -f requirements.txt ] && pip install -r requirements.txt
( cd "$HOME/rpi-rgb-led-matrix/bindings/python" && pip install . )

# Grant RT scheduling without root
PYBIN="$(readlink -f env/bin/python3)"
sudo setcap 'cap_sys_nice=eip' "$PYBIN"

# .env scaffold (gitignored). Fill in keys; persists across reboots.
[ -f .env ] || cp .env.example .env
chmod 600 .env
echo ">>> Edit $APP/.env to add API keys (kept out of git)."
echo ">>> CONFIRM display/__init__.py uses: regular / FM6126A / parallel / disable_hardware_pulsing=False"
