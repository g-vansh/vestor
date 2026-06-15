#!/usr/bin/env bash
# [PI] Install the systemd unit. Idempotent. Deliberately does NOT enable or start
# the service: the FIRST time the matrix drives the panel must be a SUPERVISED Phase 0
# test (hard stop #5). The enable/start commands are printed for you to run while you
# are watching the panel.
set -euo pipefail
sudo cp "$HOME/vestor/services/vestor.service" /etc/systemd/system/vestor.service
sudo systemctl daemon-reload
echo ">>> vestor.service installed (NOT enabled, NOT started)."
echo ">>> Phase 0 supervised live test — run these WHILE watching the panel:"
echo ">>>   sudo systemctl start vestor       # start the matrix once"
echo ">>>   journalctl -u vestor -f           # follow logs"
echo ">>> Once output looks correct, persist across reboots:"
echo ">>>   sudo systemctl enable vestor"
