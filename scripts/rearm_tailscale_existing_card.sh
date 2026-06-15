#!/usr/bin/env bash
# [MAC] Re-arm Tailscale first-boot enrollment on an ALREADY-FLASHED card WITHOUT
# re-flashing it. Use this when the Pi has already booted once (so Imager's original
# firstrun.sh is gone) and you just want to add the Tailscale hook to the existing,
# working OS install (preserves the proven Wi-Fi / SSH / hostname config).
#
# It does what install_tailscale_firstboot.sh does for a fresh flash, but instead of
# injecting into an existing firstrun.sh it CREATES one and re-adds the one-time
# `systemd.run=` trigger to cmdline.txt. The trigger + firstrun.sh delete themselves
# on the next boot, so this runs exactly once.
#
# SECRET (hard stop #3): the reusable+pre-approved auth key comes from the gitignored
# scripts/vestor-tailscale.auth or $VESTOR_TS_AUTHKEY; it is never committed and the
# Pi shreds it after enrollment.
# cmdline.txt (hard stop #2): backed up to cmdline.txt.bak, edited in place, and the
# result is verified to remain a SINGLE line before exit.
#
# Usage:  ./scripts/rearm_tailscale_existing_card.sh [/Volumes/bootfs]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOOTFS="${1:-/Volumes/bootfs}"
TOKEN='systemd.run=/boot/firmware/firstrun.sh systemd.run_success_action=reboot systemd.unit=kernel-command-line.target'

# ---- auth key (secret) ----
KEY="${VESTOR_TS_AUTHKEY:-}"
if [ -z "$KEY" ] && [ -f "$HERE/vestor-tailscale.auth" ]; then
  KEY="$(tr -d ' \t\r\n' < "$HERE/vestor-tailscale.auth")"
fi
case "$KEY" in
  tskey-auth-*) : ;;
  "")  echo "ERROR: no auth key. Put it in $HERE/vestor-tailscale.auth or set VESTOR_TS_AUTHKEY." >&2; exit 2 ;;
  *)   echo "ERROR: '${KEY:0:12}...' is not a Tailscale auth key (expected tskey-auth-...)." >&2; exit 2 ;;
esac

# ---- boot partition sanity ----
[ -d "$BOOTFS" ]              || { echo "ERROR: boot partition not mounted at $BOOTFS." >&2; exit 1; }
[ -f "$BOOTFS/cmdline.txt" ]  || { echo "ERROR: $BOOTFS/cmdline.txt missing — is this a Pi boot partition?" >&2; exit 1; }

# ---- 1) stage payload onto the FAT boot partition ----
mkdir -p "$BOOTFS/vestor"
cp "$HERE/tailscale_bootstrap.sh"                        "$BOOTFS/vestor/tailscale_bootstrap.sh"
cp "$HERE/../services/vestor-tailscale-bootstrap.service" "$BOOTFS/vestor/vestor-tailscale-bootstrap.service"
printf '%s' "$KEY" > "$BOOTFS/vestor/vestor-tailscale.auth"
chmod 600 "$BOOTFS/vestor/vestor-tailscale.auth" 2>/dev/null || true  # FAT ignores mode

# ---- 2) write the one-shot firstrun.sh (no network needed; installs the stage-2 unit) ----
cat > "$BOOTFS/firstrun.sh" <<'EOF'
#!/bin/bash
# Vestor: re-armed first-boot hook to enroll the Pi in Tailscale. set +e and a
# guaranteed exit 0 are deliberate — with systemd.run_success_action=reboot the Pi
# only reboots into normal mode on success, so nothing here may abort the script.
set +e
{
  echo "=== vestor firstrun $(date -Is 2>/dev/null || date) ==="
  if [ -d /boot/firmware/vestor ]; then VDIR=/boot/firmware/vestor; else VDIR=/boot/vestor; fi
  echo "payload dir: $VDIR"
  install -D -m 0755 "$VDIR/tailscale_bootstrap.sh" /usr/local/sbin/tailscale_bootstrap.sh
  install -D -m 0644 "$VDIR/vestor-tailscale-bootstrap.service" /etc/systemd/system/vestor-tailscale-bootstrap.service
  install -D -m 0600 "$VDIR/vestor-tailscale.auth" /etc/vestor/tailscale.authkey
  systemctl enable vestor-tailscale-bootstrap.service
  echo "enabled vestor-tailscale-bootstrap.service; rc=$?"
  shred -u "$VDIR/vestor-tailscale.auth" 2>/dev/null || rm -f "$VDIR/vestor-tailscale.auth"
  echo "=== firstrun done $(date -Is 2>/dev/null || date) ==="
} >>/boot/firmware/vestor-firstboot.log 2>&1
# Run exactly once: strip our token from cmdline.txt and delete this script.
rm -f /boot/firmware/firstrun.sh
sed -i 's| systemd.run.*||g' /boot/firmware/cmdline.txt
exit 0
EOF
chmod +x "$BOOTFS/firstrun.sh"

# ---- 3) re-add the one-time trigger to cmdline.txt (idempotent; keep ONE line) ----
if grep -qF 'systemd.run=/boot/firmware/firstrun.sh' "$BOOTFS/cmdline.txt"; then
  echo ">>> cmdline.txt already has the trigger; left as-is."
else
  [ -f "$BOOTFS/cmdline.txt.bak" ] || cp "$BOOTFS/cmdline.txt" "$BOOTFS/cmdline.txt.bak"
  # Append the token to the single line WITHOUT adding a trailing newline.
  printf '%s %s' "$(tr -d '\n' < "$BOOTFS/cmdline.txt")" "$TOKEN" > "$BOOTFS/cmdline.txt.new"
  mv "$BOOTFS/cmdline.txt.new" "$BOOTFS/cmdline.txt"
fi

# ---- 4) verify cmdline.txt is still exactly one line ----
LINES="$(awk 'END{print NR}' "$BOOTFS/cmdline.txt")"
if [ "$LINES" != "1" ]; then
  echo "ERROR: cmdline.txt is $LINES lines — restoring backup. DO NOT BOOT." >&2
  cp "$BOOTFS/cmdline.txt.bak" "$BOOTFS/cmdline.txt"
  exit 1
fi

# ---- 5) tidy macOS metadata, report ----
command -v dot_clean >/dev/null && dot_clean "$BOOTFS" 2>/dev/null || true
echo ">>> armed. firstrun.sh + payload staged; cmdline.txt is one line with the trigger."
echo ">>> Eject:  diskutil eject \$(diskutil info \"$BOOTFS\" | awk -F: '/Part of Whole/{gsub(/ /,\"\",\$2);print \"/dev/\"\$2}')"
echo ">>> Then insert in the Pi, power on, and watch for 'vestor' at:"
echo ">>>   https://login.tailscale.com/admin/machines"
