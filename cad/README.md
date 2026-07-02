# cad/ — parametric CAD for the Vestor wall build

Parametric models (CadQuery) for a **virtual fit-check** of the wall + 16 panels +
mount, driven by the locked dimensions in `docs/design/WALL_PROFILE.md` +
`MOUNT_DESIGN.md`. Outputs a combined **STEP** (open in Fusion 360) + per-part STL
+ PyVista fit-check renders.

## Toolchain (macOS)
CadQuery/OCP need Python 3.12 (the system 3.14 is too new for the OCC wheels), in a
venv **outside** the repo:
```
brew install python@3.12
python3.12 -m venv <venv> && <venv>/bin/pip install cadquery build123d pyvista numpy trimesh
```
Current venv: `…/scratchpad/cadenv` (session-scoped; recreate with the above).

## Use
```
<venv>/bin/python cad/model.py     # build -> cad/out/*.stl + vestor_wall_assembly.step
<venv>/bin/python cad/render.py    # -> cad/out/img/{overview,corner,section}.png
```
`cad/out/` (STL/STEP/PNG) is gitignored — regenerate from source. All dimensions
are variables at the top of `model.py`; edit there and re-run.
