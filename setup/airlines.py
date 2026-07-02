"""
airlines.py — callsign -> airline identity for the single-panel flight card.

FLOW (mirrors the web sim's sim/airlines.js, kept in sync):
    ADS-B callsign  ->  ICAO operator (first 3 chars)  ->  IATA  ->  logo PNG
                                                       ->  brand colour + name

The flight feed (FlightRadar24) gives us a callsign like "UAL123"; the first
three letters are the ICAO operator code ("UAL"). IATA is what the logo assets
(sim/logos/<IATA>.png) are keyed by, so we translate ICAO->IATA here.
`AIRLINE_DB` holds curated, LED-boosted livery colours + short wordmark names
for the carriers we expect most at KBOS; everyone else falls back to the colour
sampled from their own logo, or sodium amber.
"""

# ICAO operator code (first 3 chars of a callsign) -> IATA code.
# Mirror of sim/airlines.js ICAO_TO_IATA and tools/fetch_logos.py — keep in sync.
ICAO_TO_IATA = {
    # US majors
    "AAL": "AA", "DAL": "DL", "UAL": "UA", "SWA": "WN", "JBU": "B6",
    "ASA": "AS", "NKS": "NK", "FFT": "F9", "AAY": "G4", "HAL": "HA",
    "SCX": "SY", "MXY": "MX", "VXP": "XP",
    # US regionals (operate for the majors)
    "RPA": "YX", "EDV": "9E", "SKW": "OO", "ENY": "MQ", "PDT": "PT",
    "ASH": "YV", "GJS": "G7", "AWI": "ZW", "JIA": "OH",
    # Canada
    "ACA": "AC", "JZA": "QK", "WJA": "WS", "POE": "PD", "TSC": "TS",
    # Europe / UK / Ireland
    "BAW": "BA", "AFR": "AF", "DLH": "LH", "KLM": "KL", "VIR": "VS",
    "ICE": "FI", "TAP": "TP", "EIN": "EI", "SWR": "LX", "IBE": "IB",
    "AUA": "OS", "SAS": "SK", "FIN": "AY", "VLG": "VY", "EZY": "U2",
    "ITY": "AZ", "BEL": "SN", "TAY": "3V", "NAX": "DY", "VOE": "V7",
    # Gulf / Asia / Latin / Oceania
    "UAE": "EK", "QTR": "QR", "ETD": "EY", "THY": "TK", "CPA": "CX",
    "JAL": "JL", "ANA": "NH", "SIA": "SQ", "KAL": "KE", "AAR": "OZ",
    "QFA": "QF", "AMX": "AM", "AVA": "AV", "LAN": "LA", "CMP": "CM",
    "TAM": "JJ", "ELY": "LY",
    # Business / commuter (BOS-area)
    "KAP": "9K",
    # Cargo (common at BOS)
    "FDX": "FX", "UPS": "5X", "GTI": "5Y", "ABX": "GB",
}

# Curated short wordmark names + LED-boosted livery colours (mirror of sim).
# color is the primary accent; used for the brand rule + fallback text.
AIRLINE_DB = {
    "AAL": {"name": "AMERICAN",   "color": (230, 55, 70)},
    "DAL": {"name": "DELTA",      "color": (235, 60, 75)},
    "UAL": {"name": "UNITED",     "color": (70, 130, 245)},
    "SWA": {"name": "SOUTHWEST",  "color": (255, 190, 40)},
    "JBU": {"name": "JETBLUE",    "color": (70, 150, 255)},
    "ASA": {"name": "ALASKA",     "color": (30, 160, 200)},
    "NKS": {"name": "SPIRIT",     "color": (255, 225, 0)},
    "FFT": {"name": "FRONTIER",   "color": (45, 205, 95)},
    "AAY": {"name": "ALLEGIANT",  "color": (255, 180, 30)},
    "HAL": {"name": "HAWAIIAN",   "color": (255, 90, 160)},
    "RPA": {"name": "REPUBLIC",   "color": (80, 140, 235)},
    "EDV": {"name": "ENDEAVOR",   "color": (235, 60, 75)},
    "SKW": {"name": "SKYWEST",    "color": (120, 160, 235)},
    "ENY": {"name": "ENVOY",      "color": (230, 55, 70)},
    "ASH": {"name": "MESA",       "color": (120, 160, 235)},
    "ACA": {"name": "AIR CANADA", "color": (235, 55, 60)},
    "JZA": {"name": "AIR CANADA", "color": (235, 55, 60)},
    "WJA": {"name": "WESTJET",    "color": (0, 160, 170)},
    "BAW": {"name": "BRITISH A.", "color": (70, 120, 235)},
    "AFR": {"name": "AIR FRANCE", "color": (80, 110, 235)},
    "DLH": {"name": "LUFTHANSA",  "color": (255, 200, 0)},
    "UAE": {"name": "EMIRATES",   "color": (235, 55, 55)},
    "KLM": {"name": "KLM",        "color": (90, 170, 255)},
    "VIR": {"name": "VIRGIN",     "color": (235, 45, 75)},
    "ICE": {"name": "ICELANDAIR", "color": (70, 150, 235)},
    "TAP": {"name": "TAP",        "color": (45, 205, 95)},
    "EIN": {"name": "AER LINGUS", "color": (45, 205, 120)},
    "SWR": {"name": "SWISS",      "color": (235, 55, 55)},
    "THY": {"name": "TURKISH",    "color": (235, 55, 55)},
    "QTR": {"name": "QATAR",      "color": (140, 30, 70)},
    "FIN": {"name": "FINNAIR",    "color": (70, 130, 245)},
    "SCX": {"name": "SUN CTRY",   "color": (70, 130, 245)},
    "KAP": {"name": "CAPE AIR",   "color": (235, 70, 70)},
    "FDX": {"name": "FEDEX",      "color": (120, 90, 200)},
    "UPS": {"name": "UPS",        "color": (170, 110, 40)},
    # Common bizjet operators near KBOS (no logo file — shown as name + icon)
    "EJA": {"name": "NETJETS",    "color": (120, 160, 230)},
    "LXJ": {"name": "FLEXJET",    "color": (200, 170, 110)},
}

GENERIC_BRAND = (255, 176, 0)   # sodium amber (#FFB000) — Solari departure-board


def icao_for_callsign(callsign):
    """First three letters of a callsign = ICAO operator code (e.g. UAL)."""
    if not callsign:
        return ""
    return "".join(callsign[:3]).upper()


def iata_for_callsign(callsign):
    """Callsign -> IATA code (for the logo pack), or '' if unknown."""
    return ICAO_TO_IATA.get(icao_for_callsign(callsign), "")


def brand_for_callsign(callsign, sampled=None):
    """Best brand colour for a callsign.

    Prefers the curated LED-boosted livery colour; else the colour sampled from
    the logo (passed in by the caller); else sodium amber.
    """
    entry = AIRLINE_DB.get(icao_for_callsign(callsign))
    if entry:
        return entry["color"]
    if sampled:
        return sampled
    return GENERIC_BRAND


def name_for_callsign(callsign):
    """Curated short wordmark name, or the ICAO code as a last resort."""
    icao = icao_for_callsign(callsign)
    entry = AIRLINE_DB.get(icao)
    if entry:
        return entry["name"]
    return icao


# --- FlightRadar24 altitude colour ramp -------------------------------------
# A real aviation colour language borrowed from FR24's own trail colouring, so
# the altitude field encodes height the way flight-trackers already do.
def altitude_colour(altitude_ft):
    """(r,g,b) for an altitude in feet, following FR24's low->high ramp."""
    a = altitude_ft or 0
    if a < 700:
        return (255, 255, 255)     # white — very low / on approach
    if a < 2000:
        return (255, 230, 60)      # yellow
    if a < 4000:
        return (60, 220, 90)       # green
    if a < 10000:
        return (110, 200, 255)     # light blue
    if a < 20000:
        return (90, 140, 255)      # blue
    if a < 33000:
        return (150, 120, 255)     # indigo
    return (215, 130, 255)         # violet — high cruise
