#!/usr/bin/env bash
# Builds dist/vestoros.zip (the APK, the installer, the guide, the screenshots and the source). The
# signing key is zipped separately, outside dist/, only if VESTOR_KEY_ZIP names where.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
[[ -f build/vestoros.apk ]] || { echo "build first: ./build.sh" >&2; exit 1; }
rm -rf dist && mkdir -p dist/vestoros/screenshots
cp build/vestoros.apk install.sh README.md NOTICE dist/vestoros/
if [[ -d ${SHOTS:-} ]]; then cp "$SHOTS"/*.png "$SHOTS"/*.jpg dist/vestoros/screenshots/ 2>/dev/null || true; fi
# The source, from a fixed list of what belongs in it, so a key or a copy of one lying in this
# folder can never ride along.
src=(AndroidManifest.xml NOTICE README.md SETUP_HANDOFF.md build.sh install.sh package.sh publish.sh .gitignore)
while IFS= read -r f; do src+=("$f"); done < <(find src res assets preview scripts web analysis -type f 2>/dev/null | sort)
zip -q -X dist/vestoros/vestoros-source.zip "${src[@]}"
if unzip -Z1 dist/vestoros/vestoros-source.zip | grep -iE 'keystore|\.jks$|\.p12$|\.pass$|signing-key' >&2; then
  echo "error: key material found in the source zip, stopping" >&2
  rm -rf dist
  exit 1
fi
(cd dist && zip -q -r -X vestoros.zip vestoros)
printf 'dist/vestoros.zip  %s bytes\n' "$(wc -c < dist/vestoros.zip | tr -d ' ')"
# The key travels on its own, never in dist/ beside the zip meant for sharing, and only when asked
# for: VESTOR_KEY_ZIP=/somewhere/private/signing-key-keep-private.zip ./package.sh
if [[ -n ${VESTOR_KEY_ZIP:-} ]]; then
  KEYSTORE=${VESTOR_KEYSTORE:-$HOME/.vestor/release.keystore}
  case $(cd "$(dirname "$VESTOR_KEY_ZIP")" && pwd) in "$PWD"/dist*) echo "error: put the key zip outside dist/" >&2; exit 1 ;; esac
  files=("$KEYSTORE")
  [[ -f $KEYSTORE.pass ]] && files+=("$KEYSTORE.pass")
  (umask 077; rm -f "$VESTOR_KEY_ZIP"; zip -q -X -j "$VESTOR_KEY_ZIP" "${files[@]}")
  printf '%s  %s bytes (keep private)\n' "$VESTOR_KEY_ZIP" "$(wc -c < "$VESTOR_KEY_ZIP" | tr -d ' ')"
fi
