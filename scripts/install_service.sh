#!/usr/bin/env bash
# [PI] Install + enable the systemd service. Idempotent.
set -euo pipefail
sudo cp "$HOME/vestor/services/vestor.service" /etc/systemd/system/vestor.service
sudo systemctl daemon-reload
sudo systemctl enable --now vestor.service
systemctl --no-pager status vestor.service || true
echo ">>> Follow logs:  journalctl -u vestor -f"
