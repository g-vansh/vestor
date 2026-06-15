#!/usr/bin/env bash
# [PI] Idempotent: clone/pull the app, create the venv, build the rgbmatrix binding
# from the matrix source, install app deps, setcap, and scaffold .env. Re-runnable.
#
# Build notes (current hzeller upstream):
#  * The Python module builds from the matrix REPO ROOT via scikit-build-core + CMake.
#    There is NO bindings/python/setup.py — `pip install <repo-root>` is correct.
#  * That CMakeLists unconditionally compiles shims/pillow.c, which #includes Pillow's
#    private libImaging headers (Imaging.h, ImPlatform.h, Mode.h, Arrow.h,
#    ImagingUtils.h). Those are NOT shipped in Pillow wheels, so we fetch them and put
#    them on CPATH for the build only. The app never calls SetImage(PILImage) and the
#    shim doesn't link libpillow, so this adds NO runtime Pillow dependency (Pillow is
#    intentionally absent from requirements.txt).
#  * rgbmatrix==0.0.1 in requirements.txt IS this locally-built binding, not a PyPI
#    package — it is filtered out of the requirements install below.
set -euo pipefail
APP="$HOME/vestor"
MATRIX="$HOME/rpi-rgb-led-matrix"
PILLOW_HEADERS_REF="${PILLOW_HEADERS_REF:-12.2.0}"   # override if upstream layout changes

if [ ! -d "$APP/.git" ]; then
  git clone https://github.com/g-vansh/vestor.git "$APP"
else
  git -C "$APP" pull --ff-only || true
fi
cd "$APP"

[ -d "$MATRIX/.git" ] || { echo "ERROR: $MATRIX missing — run scripts/setup_pi.sh first." >&2; exit 1; }

# venv + build frontend
[ -d env ] || python3 -m venv env
./env/bin/pip install --upgrade pip wheel
./env/bin/pip install Cython

# Fetch Pillow libImaging headers needed ONLY to compile shims/pillow.c (see notes).
HDR_DIR="$(mktemp -d)"
base="https://raw.githubusercontent.com/python-pillow/Pillow/${PILLOW_HEADERS_REF}/src/libImaging"
for f in Imaging.h ImPlatform.h Mode.h Arrow.h ImagingUtils.h; do
  curl -fsSL "$base/$f" -o "$HDR_DIR/$f"
done

# Build + install the binding from the matrix repo root (scikit-build-core/CMake).
CPATH="$HDR_DIR${CPATH:+:$CPATH}" ./env/bin/pip install -v "$MATRIX"
rm -rf "$HDR_DIR"

# App deps (rgbmatrix filtered — built from source above).
grep -v '^rgbmatrix' requirements.txt > /tmp/req_no_matrix.txt
./env/bin/pip install -r /tmp/req_no_matrix.txt

# Verify the binding imports. This does NOT construct RGBMatrix → no hardware access.
./env/bin/python -c "import rgbmatrix; from rgbmatrix import RGBMatrix, RGBMatrixOptions; print('rgbmatrix import OK')"

# Grant RT scheduling without root (cap_sys_nice). The venv interpreter is a symlink
# to the system python, so this applies to /usr/bin/python3.x system-wide — acceptable
# on a single-purpose appliance, and it satisfies "never run the matrix as root".
PYBIN="$(readlink -f env/bin/python3)"
sudo setcap 'cap_sys_nice=eip' "$PYBIN"
sudo getcap "$PYBIN"

# .env scaffold (gitignored). Weather/MBTA keys are optional (config.py defaults "").
[ -f .env ] || cp .env.example .env
chmod 600 .env
echo ">>> App installed. Edit $APP/.env for optional API keys (kept out of git)."
echo ">>> CONFIRM display/__init__.py uses: regular / FM6126A / parallel=1 / disable_hardware_pulsing=False"
echo ">>> Next: scripts/install_service.sh   (installs the unit; does NOT start the panel)."
