#!/usr/bin/env bash
# [MAC] Bake the Tailscale first-boot enrollment onto a freshly-flashed SD card.
#
# Run this AFTER Raspberry Pi Imager has written the card WITH the OS-customisation
# wizard (hostname / user / Wi-Fi / SSH), but BEFORE you eject and boot the Pi. It
# does NOT modify cmdline.txt and does NOT change any OS or Wi-Fi setting. It only:
#   1. copies tailscale_bootstrap.sh + the systemd unit + your auth key into
#      <bootfs>/vestor/   (the FAT boot partition)
#   2. prepends a one-time, fully self-guarded install block to the wizard's
#      firstrun.sh that — on first boot — installs those into the rootfs and enables
#      the one-shot enrollment service (which then runs on the next, networked boot).
#
# SECRET HANDLING (hard stop #3): the reusable + pre-approved Tailscale auth key is a
# secret. Provide it via the gitignored file scripts/vestor-tailscale.auth OR the
# VESTOR_TS_AUTHKEY env var. It is NEVER committed; it is shredded from the Pi after
# enrollment succeeds, and from the FAT partition during first boot.
#
# Usage:
#   VESTOR_TS_AUTHKEY=tskey-auth-...  ./scripts/install_tailscale_firstboot.sh [/Volumes/bootfs]
#   # or put the key in scripts/vestor-tailscale.auth and just run:
#   ./scripts/install_tailscale_firstboot.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOOTFS="${1:-/Volumes/bootfs}"
MARKER_OPEN="# >>> vestor tailscale first-boot (managed) >>>"
MARKER_CLOSE="# <<< vestor tailscale first-boot (managed) <<<"

# ---- 1) Resolve the auth key (secret), never echo it. ----
KEY="${VESTOR_TS_AUTHKEY:-}"
if [ -z "$KEY" ] && [ -f "$HERE/vestor-tailscale.auth" ]; then
  KEY="$(tr -d ' \t\r\n' < "$HERE/vestor-tailscale.auth")"
fi
case "$KEY" in
  tskey-auth-*) : ;;  # looks valid
  "")  echo "ERROR: no auth key. Put it in $HERE/vestor-tailscale.auth (gitignored)" >&2
       echo "       or pass VESTOR_TS_AUTHKEY=tskey-auth-...  Generate one at:" >&2
       echo "       https://login.tailscale.com/admin/settings/keys (Reusable + Pre-approved)" >&2
       exit 2 ;;
  *)   echo "ERROR: '${KEY:0:12}...' is not a Tailscale auth key (expected tskey-auth-...)." >&2
       exit 2 ;;
esac

# ---- 2) Sanity-check the boot partition. ----
[ -d "$BOOTFS" ] || { echo "ERROR: boot partition not mounted at $BOOTFS." >&2
                      echo "       Re-flash with Imager, leave the card inserted, then re-run." >&2
                      exit 1; }
FIRSTRUN="$BOOTFS/firstrun.sh"
[ -f "$FIRSTRUN" ] || { echo "ERROR: $FIRSTRUN not found." >&2
                        echo "       The card must be flashed WITH the Imager OS-customisation wizard" >&2
                        echo "       (hostname / user / Wi-Fi / SSH) — that is what writes firstrun.sh." >&2
                        exit 1; }

# ---- 3) Stage the payload onto the FAT boot partition. ----
mkdir -p "$BOOTFS/vestor"
cp "$HERE/tailscale_bootstrap.sh"                       "$BOOTFS/vestor/tailscale_bootstrap.sh"
cp "$HERE/../services/vestor-tailscale-bootstrap.service" "$BOOTFS/vestor/vestor-tailscale-bootstrap.service"
printf '%s' "$KEY" > "$BOOTFS/vestor/vestor-tailscale.auth"
chmod 600 "$BOOTFS/vestor/vestor-tailscale.auth" 2>/dev/null || true  # FAT ignores mode; harmless

# ---- 4) Inject the stage-1 block into firstrun.sh (idempotent via marker). ----
if grep -qF "$MARKER_OPEN" "$FIRSTRUN"; then
  echo ">>> firstrun.sh already carries the vestor block; refreshed payload only."
else
  TMPBLK="$(mktemp)"
  cat > "$TMPBLK" <<EOS
$MARKER_OPEN
# Self-guarded: every line tolerates failure so this can NEVER abort the wizard's
# own (set -e) hostname/user/Wi-Fi/SSH setup that follows.
{
  if [ -d /boot/firmware/vestor ]; then VDIR=/boot/firmware/vestor; else VDIR=/boot/vestor; fi
  install -D -m 0755 "\$VDIR/tailscale_bootstrap.sh" /usr/local/sbin/tailscale_bootstrap.sh || true
  install -D -m 0644 "\$VDIR/vestor-tailscale-bootstrap.service" /etc/systemd/system/vestor-tailscale-bootstrap.service || true
  install -D -m 0600 "\$VDIR/vestor-tailscale.auth" /etc/vestor/tailscale.authkey || true
  systemctl enable vestor-tailscale-bootstrap.service || true
  shred -u "\$VDIR/vestor-tailscale.auth" 2>/dev/null || rm -f "\$VDIR/vestor-tailscale.auth" || true
} >>/var/log/vestor-firstboot.log 2>&1 || true
$MARKER_CLOSE
EOS

  TMPOUT="$(mktemp)"
  # Insert our block immediately AFTER the shebang line (deterministic placement;
  # ordering vs. the wizard's steps is irrelevant because our oneshot runs next boot).
  awk -v f="$TMPBLK" '
    NR==1 { print; while ((getline l < f) > 0) print l; close(f); next }
    { print }
  ' "$FIRSTRUN" > "$TMPOUT" && cat "$TMPOUT" > "$FIRSTRUN"
  rm -f "$TMPBLK" "$TMPOUT"
  echo ">>> injected vestor tailscale block into firstrun.sh (after shebang)"
fi

# ---- 5) Report; do NOT touch cmdline.txt. ----
echo ">>> staged onto $BOOTFS/vestor:"
ls -la "$BOOTFS/vestor"
echo ">>> cmdline.txt was NOT modified (hard stop #2 untouched)."
echo ">>> Next: eject the card, insert into the Pi, power on. Watch for 'vestor' at:"
echo ">>>   https://login.tailscale.com/admin/machines"
echo ">>> Then on the Mac (after installing Tailscale):  ssh pi@vestor"
