"""
aircraft.py — classify an ICAO aircraft type code into a category and provide a
pixel-art icon, for flights that have no airline logo (GA, helicopters, private
jets, regional). Turns a blank/text-only card into a recognisable silhouette.

category(code) -> "heli" | "bizjet" | "prop" | "airliner"
icon(category)  -> list of (x, y) pixels within the icon's bounding box + (w, h)
"""

# ---- classification (common types; anything unlisted -> airliner) ----------
_HELI = {
    "EC45", "EC30", "EC35", "EC20", "EC55", "EC25", "EC75", "H145", "H135", "H125",
    "AS50", "AS55", "AS65", "AS32", "AS3B", "R22", "R44", "R66", "B06", "B407",
    "B429", "B412", "B430", "B47G", "S76", "S92", "S70", "A109", "A119", "A139",
    "A169", "H500", "H60", "EH10", "MD52", "GAZL", "EXPL", "B505",
}
_BIZJET = {
    "C25A", "C25B", "C25C", "C500", "C501", "C510", "C525", "C526", "C550", "C551",
    "C560", "C56X", "C650", "C680", "C68A", "C700", "C750", "GLF2", "GLF3", "GLF4",
    "GLF5", "GLF6", "GALX", "G150", "G280", "GLEX", "GL5T", "GL7T", "LJ31", "LJ35",
    "LJ40", "LJ45", "LJ55", "LJ60", "LJ70", "LJ75", "CL30", "CL35", "CL60", "CL64",
    "E50P", "E55P", "E545", "E550", "F900", "FA10", "FA20", "FA50", "FA7X", "FA8X",
    "F2TH", "FA5X", "H25B", "HA4T", "HDJT", "BE40", "PC24", "PRM1", "EA50",
}
_PROP = {
    "C402", "C404", "C208", "C210", "C172", "C182", "C206", "C207", "C310", "C340",
    "C414", "C421", "BE20", "BE9L", "BE99", "BE58", "BE55", "BE36", "BE35", "BE33",
    "PC12", "PC6", "TBM7", "TBM8", "TBM9", "TBM4", "DHC2", "DHC3", "DHC6", "PA31",
    "PA32", "PA34", "PA44", "PA46", "P46T", "P28A", "P28B", "M20P", "M20T", "SR20",
    "SR22", "S22T", "DA40", "DA42", "DA62", "PAY1", "PAY2", "PAY3", "PAY4", "AC90",
}


def category(code):
    c = (code or "").upper().strip()
    if not c:
        return "airliner"
    if c in _HELI:
        return "heli"
    if c in _BIZJET:
        return "bizjet"
    if c in _PROP:
        return "prop"
    return "airliner"


# ---- icons (side view, nose right). '#'/'o'/'@' = lit, '.'/' ' = empty ------
# The main rotor row (heli) and prop column can be animated by the caller.
_ART = {
    "heli": """
....#################.....
..........###.............
.......#########.........
......###########........
......##.o##.o##.#########
......###########.......##
.......#########.....####.
........#.....#..........
.......###...###.........
""",
    "bizjet": """
.......................###
.......................#..
....##################.#..
...####################o..
....###@@@@@##########o....
.........#####.............
........#####..............
""",
    "prop": """
.............#............
....##########............
...###########.......#....
..############.....o###....
...###########.......#....
....##########............
.............#............
""",
    "airliner": """
...#......................
...##.....................
...###....................
...########################
...#####@@@@@@@#########o..
....######...######.......
.....ooo......ooo.........
""",
}


def _parse(art):
    rows = [r for r in art.split("\n") if r]
    w = max(len(r) for r in rows)
    px = []
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch not in (".", " "):
                px.append((x, y))
    return px, w, len(rows)


_ICONS = {k: _parse(v) for k, v in _ART.items()}


def icon_for(code):
    """(pixels[(x,y)], w, h, category) for an aircraft type code."""
    cat = category(code)
    px, w, h = _ICONS[cat]
    return px, w, h, cat
