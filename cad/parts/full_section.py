#!/usr/bin/env python3
"""
full_section.py — the ENTIRE build in one vertical cross-section (ceiling → panel
bottom). Shows the ceiling-gap electronics (gap hanger + backer strip + PSU + valance)
AND the mount (cleat + steel rails + panel), which sit at ALTERNATING stations along
the same top groove. The single picture of the whole finalized design.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, Polygon
import mount_params as P
from util import IMG
import top_cleat as TC, gap_hanger as GH

WALL=(.66,.52,.35); PIECE=(.55,.41,.26); PANEL=(.13,.13,.17); TAN=(.92,.80,.52)
RAIL=(.62,.64,.68); PSU=(.90,.70,.34); VAL=(.86,.86,.89); STRIP=(.74,.78,.84); ORNG="#e5844a"

fig, ax = plt.subplots(figsize=(8.6, 13))
# ---- context: wall, ceiling, piece, attach band ----
ax.plot([-26,120],[P.CEIL_Z,P.CEIL_Z],color="#888",lw=3); ax.text(60,P.CEIL_Z+5,"CEILING",fontsize=10,color="#777",fontweight="bold")
ax.add_patch(Rectangle((-26,-175),26,P.CEIL_Z+180,facecolor=WALL,ec="k",lw=.4))
ax.add_patch(Rectangle((P.PIECE_BACK_Y,P.PIECE_BOT_Z),P.PIECE_THICK,-P.PIECE_BOT_Z,facecolor=PIECE,ec="k",lw=.6))
ax.add_patch(Rectangle((0,P.ATTACH_BOT_Z),P.PIECE_BACK_Y,P.ATTACH_TOP_Z-P.ATTACH_BOT_Z,facecolor=PIECE,ec="k",lw=.4))
ax.text(24,-112,"wooden\npiece",ha="center",va="center",fontsize=7,color="w")

# ---- ELECTRONICS zone (above, in the ceiling gap) ----
gh=[(GH.TONGUE_Y0,-GH.TONGUE_DEEP),(GH.TONGUE_Y0,GH.SADDLE_TOP),(P.FACE_Y,GH.SADDLE_TOP),(P.FACE_Y,0),
    (P.STRIP_FRONT_Y,0),(P.STRIP_FRONT_Y,GH.ARM_Z0),(P.VALANCE_FRONT_Y,GH.ARM_Z0),
    (P.VALANCE_FRONT_Y,P.HANGER_TOP_Z),(P.STRIP_BACK_Y,P.HANGER_TOP_Z),(P.STRIP_BACK_Y,GH.SADDLE_TOP),
    (GH.TONGUE_Y1,GH.SADDLE_TOP),(GH.TONGUE_Y1,-GH.TONGUE_DEEP)]
ax.add_patch(Polygon(gh,closed=True,facecolor=TAN,ec="k",lw=1.1,zorder=5))
ax.add_patch(Rectangle((P.STRIP_FRONT_Y,35),4,140,facecolor=STRIP,ec="k",lw=.6,zorder=6))          # backer strip
ax.add_patch(Rectangle((P.STRIP_FRONT_Y+4,50),30,105,facecolor=PSU,ec="k",lw=.7,zorder=6))         # PSU (edge)
ax.add_patch(Rectangle((P.VALANCE_BACK_Y,0),10,P.VALANCE_TOP_Z,facecolor=VAL,ec="k",lw=.7,alpha=.9,zorder=7))  # valance
ax.annotate("gap hanger → top groove\n(zero new wall holes)",(7,60),(78,70),fontsize=8,arrowprops=dict(arrowstyle="->"))
ax.annotate("backer strip",(P.STRIP_FRONT_Y,150),(78,150),fontsize=8,arrowprops=dict(arrowstyle="->"))
ax.annotate("PSU / fuse block / Pi\n(vent both ends)",(50,110),(78,110),fontsize=8,arrowprops=dict(arrowstyle="->"))
ax.annotate("valance — flush + vented top",(P.VALANCE_BACK_Y+5,195),(60,213),fontsize=8,arrowprops=dict(arrowstyle="->"))
ax.text(-40,110,"CEILING-GAP\nELECTRONICS",ha="center",va="center",rotation=90,fontsize=10,fontweight="bold",color="#a8571f")

# ---- MOUNT zone (below): cleat spine + rails + panel ----
spine=[(TC.SPINE_BACK,TC.SPINE_BOT),(P.FACE_Y,TC.SPINE_BOT),(P.FACE_Y,0),(TC.SPINE_BACK,0)]
ax.add_patch(Polygon(spine,closed=True,facecolor=TAN,ec="k",lw=1.1,zorder=4))
z0,z1=P.bar_z(P.TOP_BAR_CZ); ax.add_patch(Rectangle((P.FACE_Y,z0),P.RAIL_THK,P.BAR_H,facecolor=RAIL,ec="k",lw=.6,zorder=6))
ax.add_patch(Rectangle((P.FACE_Y,P.LEDGE_Z),P.RAIL_THK,30,facecolor=RAIL,ec="k",lw=.6,zorder=6))
ax.add_patch(Rectangle((P.FACE_Y,P.LEDGE_Z-P.LEDGE_THK),P.LEDGE_FRONT_Y-P.FACE_Y,P.LEDGE_THK,facecolor=RAIL,ec="k",lw=.6,zorder=6))
ax.add_patch(Rectangle((P.PANEL_BACK_Y,P.PANEL_BOT_Z),P.PANEL_D,P.PANEL_H,facecolor=PANEL,ec="k",lw=.7,zorder=6))
for z in (P.M3_TOP_Z,P.M3_BOT_Z): ax.plot([P.RAIL_FRONT_Y,P.PANEL_BACK_Y],[z,z],color=ORNG,lw=3,zorder=8)
ax.annotate("cleat spine carries BOTH rails",(P.FACE_Y,-70),(78,-55),fontsize=8,arrowprops=dict(arrowstyle="->"))
ax.annotate("steel rails + magnet screws",(P.PANEL_BACK_Y,P.M3_TOP_Z),(78,-25),fontsize=8,arrowprops=dict(arrowstyle="->"))
ax.annotate("panel rests on the angle ledge",(P.LEDGE_FRONT_Y-3,P.LEDGE_Z),(70,-172),fontsize=8,arrowprops=dict(arrowstyle="->"))
ax.text(-40,-90,"MOUNT",ha="center",va="center",rotation=90,fontsize=10,fontweight="bold",color="#2a6a8a")
ax.text(93,-120,"panel",ha="center",fontsize=8,color="#333")

ax.text(50,P.CEIL_Z-14,"cleats (mount) & gap-hangers (electronics) alternate along the one top groove",
        ha="center",fontsize=8,style="italic",color="#555")
ax.set_xlim(-52,120); ax.set_ylim(-180,P.CEIL_Z+16); ax.set_aspect("equal"); ax.axis("off")
ax.set_title("VESTOR — the whole build, one cross-section (ceiling gap → mount → panel)",fontsize=13,fontweight="bold")
OUT=os.path.join(IMG,"full_section.png"); fig.savefig(OUT,dpi=125,bbox_inches="tight",facecolor="white"); print("wrote full_section.png")
