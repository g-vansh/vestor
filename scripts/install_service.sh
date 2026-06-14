#!/usr/bin/env bash
# [PI] Install + enable the systemd service. Idempotent.
set -euo pipefail
sudo cp "$HOME/flightwall/services/flightwall.service" /etc/systemd/system/flightwall.service
sudo systemctl daemon-reload
sudo systemctl enable --now flightwall.service
systemctl --no-pager status flightwall.service || true
echo ">>> Follow logs:  journalctl -u flightwall -f"
