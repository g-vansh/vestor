#!/usr/bin/env bash
# Builds build/lucent.apk from the bare Android tools: no Gradle, no libraries.
#
#   FRAMEWORK_JAR  an android.jar with framework resources, for aapt2 (API >= 24)
#   ANDROID_JAR    an android.jar to compile against (API 30, Fire OS 8); defaults to FRAMEWORK_JAR
#   BUILD_TOOLS    a folder with aapt2, zipalign and apksigner (and dx or d8)
#
# The code avoids lambdas so the old dx dexer works as well as d8.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
unset JAVA_TOOL_OPTIONS

VERSION_CODE=${VERSION_CODE:-2}
VERSION_NAME=${VERSION_NAME:-1.1}
MIN_SDK=28
TARGET_SDK=28

BT=${BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/debian}
# An API 30 android.jar serves for both when it carries resources.arsc (it needs API 24+ for the
# network security config).
FRAMEWORK_JAR=${FRAMEWORK_JAR:-${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-30/android.jar}}
ANDROID_JAR=${ANDROID_JAR:-$FRAMEWORK_JAR}
for f in "$BT/aapt2" "$BT/zipalign" "$FRAMEWORK_JAR" "$ANDROID_JAR"; do
  [[ -e $f ]] || { echo "error: missing $f (set BUILD_TOOLS / FRAMEWORK_JAR / ANDROID_JAR)" >&2; exit 1; }
done
APKSIGNER=$BT/apksigner
[[ -x $APKSIGNER ]] || APKSIGNER=$(command -v apksigner)

# The signing key and its password live in two files beside this script. Keep both private:
# an update installs only if it is signed by the same key.
KEYSTORE=${LUCENT_KEYSTORE:-$PWD/release.keystore}
if [[ -z ${LUCENT_KEYPASS:-} && -f $KEYSTORE.pass ]]; then LUCENT_KEYPASS=$(cat "$KEYSTORE.pass"); fi
if [[ -z ${LUCENT_KEYPASS:-} ]]; then
  LUCENT_KEYPASS=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf '%s' "$LUCENT_KEYPASS" > "$KEYSTORE.pass")
fi
# Handed to the tools through the environment, never on a command line where other programs on
# the computer could read it from the process list.
export LUCENT_KEYPASS
if [[ ! -f $KEYSTORE ]]; then
  (umask 077; keytool -genkeypair -keystore "$KEYSTORE" -alias lucent -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass:env LUCENT_KEYPASS -keypass:env LUCENT_KEYPASS -dname "CN=Lucent home" >/dev/null)
  echo "created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/lucent.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build

"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$FRAMEWORK_JAR" --manifest AndroidManifest.xml "$WORK/res.zip" -A assets \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" -o "$WORK/app.apk"

find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$ANDROID_JAR" -d "$WORK/classes" @"$WORK/sources.txt"

if [[ -x $BT/d8 ]]; then
  find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
  "$BT/d8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$WORK" @"$WORK/classes.txt"
else
  "$BT/dx" --dex --min-sdk-version="$MIN_SDK" --output="$WORK/classes.dex" "$WORK/classes"
fi
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
# v2 and v3 signatures. Fire OS 8 checks them over the whole file, so a changed byte anywhere
# in the APK makes it refuse to install.
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass env:LUCENT_KEYPASS --key-pass env:LUCENT_KEYPASS \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out build/lucent.apk "$WORK/aligned.apk"
"$APKSIGNER" verify build/lucent.apk
rm -f build/lucent.apk.idsig
printf 'built build/lucent.apk  %s bytes\n' "$(wc -c < build/lucent.apk | tr -d ' ')"
# The signature as Android reports it (Java's Arrays.hashCode of the certificate), which
# install.sh checks before it hands over the HOME button. Only a new key changes it.
sig=1
for b in $(keytool -exportcert -keystore "$KEYSTORE" -alias lucent -storepass:env LUCENT_KEYPASS 2>/dev/null | od -An -v -td1); do
  sig=$(( (31 * sig + b) & 0xFFFFFFFF ))
done
sig=$(printf '%x' "$sig")
if ! grep -q "LUCENT_SIG:-$sig}" install.sh; then
  echo "note: this key's signature is $sig. Run the installer as LUCENT_SIG=$sig ./install.sh"
fi
