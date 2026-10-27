#!/usr/bin/env bash
# Builds dist/lucent.zip (the APK, the installer, the guide, the screenshots and the source). The
# signing key is zipped separately, outside dist/, only if LUCENT_KEY_ZIP names where.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
[[ -f build/lucent.apk ]] || { echo "build first: ./build.sh" >&2; exit 1; }
rm -rf dist && mkdir -p dist/lucent/screenshots
cp build/lucent.apk install.sh README.md NOTICE dist/lucent/
if [[ -d ${SHOTS:-} ]]; then cp "$SHOTS"/*.png "$SHOTS"/*.jpg dist/lucent/screenshots/ 2>/dev/null || true; fi
# The source, from a fixed list of what belongs in it, so a key or a copy of one lying in this
# folder can never ride along.
src=(AndroidManifest.xml NOTICE README.md build.sh install.sh package.sh .gitignore)
while IFS= read -r f; do src+=("$f"); done < <(find src res assets preview scripts web -type f 2>/dev/null | sort)
zip -q -X dist/lucent/lucent-source.zip "${src[@]}"
if unzip -Z1 dist/lucent/lucent-source.zip | grep -iE 'keystore|\.jks$|\.p12$|\.pass$|signing-key' >&2; then
  echo "error: key material found in the source zip, stopping" >&2
  rm -rf dist
  exit 1
fi
(cd dist && zip -q -r -X lucent.zip lucent)
printf 'dist/lucent.zip  %s bytes\n' "$(wc -c < dist/lucent.zip | tr -d ' ')"
# The key travels on its own, never in dist/ beside the zip meant for sharing, and only when asked
# for: LUCENT_KEY_ZIP=/somewhere/private/signing-key-keep-private.zip ./package.sh
if [[ -n ${LUCENT_KEY_ZIP:-} ]]; then
  KEYSTORE=${LUCENT_KEYSTORE:-$HOME/.lucent/release.keystore}
  case $(cd "$(dirname "$LUCENT_KEY_ZIP")" && pwd) in "$PWD"/dist*) echo "error: put the key zip outside dist/" >&2; exit 1 ;; esac
  files=("$KEYSTORE")
  [[ -f $KEYSTORE.pass ]] && files+=("$KEYSTORE.pass")
  (umask 077; rm -f "$LUCENT_KEY_ZIP"; zip -q -X -j "$LUCENT_KEY_ZIP" "${files[@]}")
  printf '%s  %s bytes (keep private)\n' "$LUCENT_KEY_ZIP" "$(wc -c < "$LUCENT_KEY_ZIP" | tr -d ' ')"
fi
