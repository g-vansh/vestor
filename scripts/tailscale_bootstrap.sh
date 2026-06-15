#!/usr/bin/env bash
# [PI] Stage-2 Tailscale enrollment. Installed to /usr/local/sbin by the first-boot
# hook, then run once by vestor-tailscale-bootstrap.service AFTER
# network-online.target — i.e. only once the Pi actually has internet (required to
# download the installer and reach the Tailscale control plane).
#
# WHY THIS EXISTS: the Pi lives on MIT's per-user-PSK Wi-Fi ("MIT" SSID), which
# isolates clients at L2 and blocks mDNS, so plain `ssh pi@vestor.local` can never
# reach it. Tailscale is an OUTBOUND WireGuard mesh: the Pi dials out to the control
# plane and peers via DERP relays, sidestepping the isolation. It opens no inbound
# ports and is not a personal access point, so it stays MITnet-policy-compliant.
#
# Idempotent and self-cleaning:
#   - installs Tailscale only if the binary is missing
#   - brings the node up as hostname 'vestor' with Tailscale SSH enabled
#   - on success, shreds the auth key and disables its own service so it never
#     runs again; on failure it stays enabled and retries on the next boot.
set -euo pipefail

LOG=/var/log/vestor-tailscale-bootstrap.log
exec > >(tee -a "$LOG") 2>&1
echo "=== vestor tailscale bootstrap $(date -Is 2>/dev/null || date) ==="

KEY_FILE="${VESTOR_TS_AUTHKEY_FILE:-/etc/vestor/tailscale.authkey}"
TS_HOSTNAME="vestor"
SERVICE="vestor-tailscale-bootstrap.service"

# 1) Install Tailscale if absent (needs network — guaranteed by the unit ordering).
if ! command -v tailscale >/dev/null 2>&1; then
  echo ">>> installing tailscale via official script"
  curl -fsSL https://tailscale.com/install.sh | sh
else
  echo ">>> tailscale already present: $(tailscale version 2>/dev/null | head -1)"
fi

# Make sure the daemon is running and will persist across reboots.
systemctl enable --now tailscaled

# 2) If we're already enrolled and Running, there is nothing to do (idempotent).
STATE="$(tailscale status --json 2>/dev/null \
         | sed -n 's/.*"BackendState"[ ]*:[ ]*"\([^"]*\)".*/\1/p' | head -1)"
if [ "$STATE" = "Running" ]; then
  echo ">>> tailscale already Running; skipping 'tailscale up'"
else
  if [ ! -s "$KEY_FILE" ]; then
    echo "!!! no auth key at $KEY_FILE and not already Running — cannot enroll." >&2
    echo "!!! leaving $SERVICE enabled to retry on next boot." >&2
    exit 1
  fi
  AUTHKEY="$(tr -d ' \t\r\n' < "$KEY_FILE")"
  echo ">>> tailscale up (hostname=$TS_HOSTNAME, Tailscale SSH on)"
  # --ssh        : reachable via `ssh pi@vestor` over the tailnet (identity-based)
  # --accept-dns=false : a server shouldn't let MagicDNS rewrite /etc/resolv.conf
  tailscale up \
    --authkey="$AUTHKEY" \
    --hostname="$TS_HOSTNAME" \
    --ssh \
    --accept-dns=false
fi

# 3) Report the assigned tailnet address for the log/journal.
TS_IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
echo ">>> tailscale IPv4: ${TS_IP:-<pending>}"

# 4) Success path: destroy the key and stop this one-shot from ever running again.
if [ -f "$KEY_FILE" ]; then
  shred -u "$KEY_FILE" 2>/dev/null || rm -f "$KEY_FILE"
  echo ">>> auth key removed from disk"
fi
systemctl disable "$SERVICE" 2>/dev/null || true
echo "=== done $(date -Is 2>/dev/null || date) ==="
