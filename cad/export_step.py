#!/usr/bin/env python3
"""export_step.py — export the box model as a colored STEP assembly for Fusion 360."""
import os
import cadquery as cq
import model as M

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)


def main():
    asm = cq.Assembly(name="vestor_wall")
    for name, b, c in M.parts():
        wp = (cq.Workplane("XY")
              .box(b[1] - b[0], b[3] - b[2], b[5] - b[4], centered=(False, False, False))
              .translate((b[0], b[2], b[4])))
        asm.add(wp, name=name, color=cq.Color(*c))
    path = os.path.join(OUT, "vestor_wall.step")
    asm.save(path)
    print("wrote", path)


if __name__ == "__main__":
    main()
