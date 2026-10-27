#!/usr/bin/env bash
# Builds build/vestoros.apk from the bare Android tools: no Gradle, no libraries.
#
#   FRAMEWORK_JAR  an android.jar with framework resources, for aapt2 (API >= 24)
#   ANDROID_JAR    an android.jar to compile against (API 30, Fire OS 8); defaults to FRAMEWORK_JAR
#   BUILD_TOOLS    a folder with aapt2, zipalign and apksigner (and dx or d8)
#
# The code avoids lambdas so the old dx dexer works as well as d8.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
unset JAVA_TOOL_OPTIONS

# The version comes from BuildInfo.java, so the app and the APK can never disagree.
VERSION_CODE=$(sed -n 's/.*static final int CODE = \([0-9][0-9]*\);.*/\1/p' src/tv/vestor/home/BuildInfo.java)
VERSION_NAME=$(sed -n 's/.*static final String VERSION = "\([0-9][0-9A-Za-z.]*\)";.*/\1/p' src/tv/vestor/home/BuildInfo.java)
[[ -n $VERSION_CODE && -n $VERSION_NAME ]] || { echo "error: no version in BuildInfo.java" >&2; exit 1; }
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

# The signing key lives outside this folder (default ~/.vestor/release.keystore), so it can never
# be zipped or committed with the source. Its password comes from VESTOR_KEYPASS, else the macOS
# Keychain (service "vestor-keystore"), else release.keystore.pass beside the key. An update
# installs only if it is signed by the same key: keep both private.
KEYSTORE=${VESTOR_KEYSTORE:-$HOME/.vestor/release.keystore}
case $KEYSTORE in "$PWD"/*) echo "warning: the signing key is inside the project folder. Keep it outside, for example in ~/.vestor/" >&2 ;; esac
pass=${VESTOR_KEYPASS:-}
if [[ -z $pass ]] && command -v security >/dev/null 2>&1; then
  pass=$(security find-generic-password -s vestor-keystore -w 2>/dev/null || true)
fi
if [[ -z $pass && -f $KEYSTORE.pass ]]; then pass=$(cat "$KEYSTORE.pass"); fi
if [[ ! -f $KEYSTORE ]]; then
  # A new key means the TV refuses updates over the old app, so it is never made by accident.
  if [[ ${VESTOR_NEW_KEY:-} != 1 ]]; then
    echo "error: no signing key at $KEYSTORE. Unzip signing-key-keep-private.zip there, or set VESTOR_KEYSTORE." >&2
    echo "  (VESTOR_NEW_KEY=1 makes a new key, but then the TV needs the old app removed first.)" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$KEYSTORE")"
  chmod 700 "$(dirname "$KEYSTORE")" 2>/dev/null || true
  [[ -n $pass ]] || pass=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
  (umask 077; printf '%s' "$pass" > "$KEYSTORE.pass")
  (umask 077; VESTOR_KEYPASS=$pass keytool -genkeypair -keystore "$KEYSTORE" -alias vestor -keyalg RSA -keysize 4096 \
    -validity 10000 -storepass:env VESTOR_KEYPASS -keypass:env VESTOR_KEYPASS -dname "CN=Vestor home" >/dev/null)
  echo "created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
fi
[[ -n $pass ]] || { echo "error: no password for $KEYSTORE (set VESTOR_KEYPASS or put it in $KEYSTORE.pass)" >&2; exit 1; }
# The password reaches only keytool and apksigner, through their environment: never a command
# line (which other programs can read from the process list), and never javac, d8 or aapt2.
unset VESTOR_KEYPASS

WORK=$(mktemp -d "${TMPDIR:-/tmp}/vestor.XXXXXX")
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
VESTOR_KEYPASS=$pass "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass env:VESTOR_KEYPASS --key-pass env:VESTOR_KEYPASS \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out build/vestoros.apk "$WORK/aligned.apk"
"$APKSIGNER" verify build/vestoros.apk
rm -f build/vestoros.apk.idsig
printf 'built build/vestoros.apk  %s bytes\n' "$(wc -c < build/vestoros.apk | tr -d ' ')"
# The signature as Android reports it (Java's Arrays.hashCode of the certificate), which
# install.sh checks before it hands over the HOME button. Only a new key changes it.
sig=1
for b in $(VESTOR_KEYPASS=$pass keytool -exportcert -keystore "$KEYSTORE" -alias vestor -storepass:env VESTOR_KEYPASS 2>/dev/null | od -An -v -td1); do
  sig=$(( (31 * sig + b) & 0xFFFFFFFF ))
done
sig=$(printf '%x' "$sig")
if ! grep -q "VESTOR_SIG:-$sig}" install.sh; then
  echo "note: this key's signature is $sig. Run the installer as VESTOR_SIG=$sig ./install.sh"
fi
