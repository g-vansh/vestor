#!/usr/bin/env bash
# Publishes this build as an over-the-air update. Every Vestor TV checks within six hours, says
# "VestorOS N is ready", and installs when you choose Updates and confirm with Install.
#
#   ./publish.sh              build (signed), check, and publish to the "ota" branch
#   ./publish.sh --dry-run    everything except the push; the files are left in build/ota
#   ./publish.sh --skip-build publish build/vestoros.apk as it is
#
# Before publishing: raise CODE (and VERSION) in src/tv/vestor/home/BuildInfo.java, and say what
# changed in res/values/strings.xml, "update_notes" (the TV shows it).
#
# The signing key is the same one build.sh uses (VESTOR_KEYSTORE, default ~/.vestor/release.keystore).
# It never leaves this computer, and it is what keeps updates safe: Fire OS installs an update only
# when it is signed by the key that signed the app already installed.
#
# With VESTOR_NTFY_TOPIC set to the Alexa topic shown in the control center, the TVs are also
# nudged to look in a few minutes instead of within six hours.
#
# The "ota" branch holds only update.json, this version's APK and the one before it (for going
# back). It is replaced on every publish, so it never grows.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
unset JAVA_TOOL_OPTIONS

dry=0 build=1
for a in "$@"; do
  case $a in
    --dry-run) dry=1 ;;
    --skip-build) build=0 ;;
    *) echo "usage: $0 [--dry-run] [--skip-build]" >&2; exit 2 ;;
  esac
done
REMOTE=${VESTOR_REMOTE:-origin}
BRANCH=ota
PKG=tv.vestor.home
BT=${BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/debian}
APKSIGNER=$BT/apksigner
[[ -x $APKSIGNER ]] || APKSIGNER=$(command -v apksigner || true)
AAPT2=$BT/aapt2
[[ -x $AAPT2 ]] || AAPT2=$(command -v aapt2 || true)
die() { echo "error: $*" >&2; exit 1; }
[[ -n $APKSIGNER && -n $AAPT2 ]] || die "needs apksigner and aapt2 (set BUILD_TOOLS)"

code=$(sed -n 's/.*static final int CODE = \([0-9][0-9]*\);.*/\1/p' src/tv/vestor/home/BuildInfo.java)
name=$(sed -n 's/.*static final String VERSION = "\([0-9][0-9A-Za-z.]*\)";.*/\1/p' src/tv/vestor/home/BuildInfo.java)
[[ -n $code && -n $name ]] || die "no version in BuildInfo.java"
# The key's fingerprint, from install.sh, so there is one place that names it.
CERT=$(sed -n 's/^CERT=\([0-9a-f]\{64\}\)$/\1/p' install.sh)
[[ ${#CERT} == 64 ]] || die "no CERT= line in install.sh"

# What is published now. The branch itself, not the raw file, which GitHub caches for minutes.
published=0 old=
if git fetch -q "$REMOTE" "refs/heads/$BRANCH" 2>/dev/null; then
  old=$(git rev-parse FETCH_HEAD)
  published=$(git show "$old:vestoros/update.json" 2>/dev/null | sed -n 's/.*"versionCode": *\([0-9][0-9]*\).*/\1/p')
  published=${published:-0}
fi
(( code > published )) || die "VestorOS $code is already published (or older). Raise CODE in BuildInfo.java first."

if (( build )); then ./build.sh; fi
apk=build/vestoros.apk
[[ -f $apk ]] || die "$apk not found"

# 1. Signed by Vestor's key, and only by it.
certs=$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null) || die "$apk does not verify"
[[ $certs == *"SHA-256 digest: $CERT"* ]] || die "$apk is not signed with Vestor's key"
[[ $(grep -c "certificate SHA-256 digest" <<< "$certs") == 1 ]] || die "$apk has more than one signer"
# 2. The package and version the TV will check.
badge=$("$AAPT2" dump badging "$apk" 2>/dev/null) || die "aapt2 cannot read $apk"
badge=${badge%%$'\n'*}
[[ $badge == *"name='$PKG'"* && $badge == *"versionCode='$code'"* && $badge == *"versionName='$name'"* ]] ||
  die "$apk is not $PKG $name ($code): $badge"
# 3. Nothing secret inside: no Claude key, no keystore, no password file.
if unzip -l "$apk" | grep -qiE '\.(keystore|jks|p12|pass)$|claude\.key'; then die "$apk carries a key file"; fi
if unzip -p "$apk" | LC_ALL=C grep -aqE 'sk-ant-[A-Za-z0-9_-]{20}'; then die "$apk carries an Anthropic key"; fi

size=$(wc -c < "$apk" | tr -d ' ')
if command -v sha256sum >/dev/null 2>&1; then sha=$(sha256sum "$apk" | cut -d' ' -f1); else sha=$(shasum -a 256 "$apk" | cut -d' ' -f1); fi
(( size > 0 && size < 64 * 1024 * 1024 )) || die "$apk is $size bytes"

out=$PWD/build/ota
rm -rf "$out"
mkdir -p "$out/vestoros"
# The version before stays beside the new one, for going back (README, "Going back a version").
if [[ -n $old && $published != 0 ]]; then
  git show "$old:vestoros/vestoros-$published.apk" > "$out/vestoros/vestoros-$published.apk" 2>/dev/null ||
    rm -f "$out/vestoros/vestoros-$published.apk"
fi
cp "$apk" "$out/vestoros/vestoros-$code.apk"
cat > "$out/vestoros/update.json" <<EOF
{
  "package": "$PKG",
  "versionCode": $code,
  "versionName": "$name",
  "apk": "vestoros-$code.apk",
  "size": $size,
  "sha256": "$sha"
}
EOF
cat > "$out/README.md" <<'EOF'
# VestorOS updates

This branch is written by `vestoros/publish.sh` on the `lucent` branch and replaced on every
publish. Vestor TVs read `vestoros/update.json` and download the APK it names. They install it
only after checking its size, SHA-256, package, version and signing key, and only when you
confirm on the TV. The APK before the current one is kept for going back a version.

The source of these builds is the `lucent` branch of this repository (`vestoros/`), under the
same GPL-3.0 license as the rest of it.
EOF

if (( dry )); then
  echo "dry run: VestorOS $name ($code), $size bytes, sha256 $sha"
  echo "files in $out, nothing pushed"
  exit 0
fi

# One commit holding just these files, built without touching this checkout or its index.
gitdir=$(git rev-parse --absolute-git-dir)
idx=$(mktemp "${TMPDIR:-/tmp}/vestor-ota-index.XXXXXX")
rm -f "$idx"
trap 'rm -f "$idx"' EXIT
(cd "$out" && GIT_INDEX_FILE=$idx git --git-dir="$gitdir" --work-tree=. add -A .)
tree=$(GIT_INDEX_FILE=$idx git --git-dir="$gitdir" write-tree)
commit=$(git commit-tree "$tree" -m "VestorOS $name ($code)")
if [[ -n $old ]]; then
  git push --force-with-lease="refs/heads/$BRANCH:$old" "$REMOTE" "$commit:refs/heads/$BRANCH"
else
  git push "$REMOTE" "$commit:refs/heads/$BRANCH"
fi
echo "published VestorOS $name ($code): TVs see it within six hours, or now with Updates, Check Now"

if [[ -n ${VESTOR_NTFY_TOPIC:-} ]]; then
  [[ $VESTOR_NTFY_TOPIC =~ ^[A-Za-z0-9_-]{8,64}$ ]] || die "VESTOR_NTFY_TOPIC does not look like a topic"
  if curl -fsS -m 20 -d update "https://ntfy.sh/$VESTOR_NTFY_TOPIC" >/dev/null; then
    echo "nudged: the TVs look again in about six minutes"
  fi
fi
