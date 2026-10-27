#!/usr/bin/env bash
# Builds dist/lucent.zip (the APK, the installer, the guide, the screenshots and the source) and,
# beside it, dist/signing-key-keep-private.zip with the signing key alone.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
[[ -f build/lucent.apk ]] || { echo "build first: ./build.sh" >&2; exit 1; }
rm -rf dist && mkdir -p dist/lucent/screenshots
cp build/lucent.apk install.sh README.md NOTICE dist/lucent/
if [[ -d ${SHOTS:-} ]]; then cp "$SHOTS"/*.png "$SHOTS"/*.jpg dist/lucent/screenshots/ 2>/dev/null || true; fi
# The source, from a fixed list of what belongs in it, so a key or a copy of one lying in this
# folder can never ride along.
src=(AndroidManifest.xml NOTICE README.md build.sh install.sh package.sh .gitignore)
while IFS= read -r f; do src+=("$f"); done < <(find src res assets preview scripts -type f 2>/dev/null | sort)
zip -q -X dist/lucent/lucent-source.zip "${src[@]}"
if unzip -Z1 dist/lucent/lucent-source.zip | grep -iE 'keystore|\.jks$|\.p12$|\.pass$|signing-key' >&2; then
  echo "error: key material found in the source zip, stopping" >&2
  rm -rf dist
  exit 1
fi
(cd dist && zip -q -r -X lucent.zip lucent)
# The key on its own, outside lucent.zip, so that zip can be shared and the key never travels
# with it. Updates must be signed with this key: keep it private and safe.
(umask 077; zip -q -X -j dist/signing-key-keep-private.zip "${LUCENT_KEYSTORE:-release.keystore}" \
  "${LUCENT_KEYSTORE:-release.keystore}.pass")
printf 'dist/lucent.zip  %s bytes\n' "$(wc -c < dist/lucent.zip | tr -d ' ')"
printf 'dist/signing-key-keep-private.zip  %s bytes (keep private)\n' "$(wc -c < dist/signing-key-keep-private.zip | tr -d ' ')"
