#!/usr/bin/env python3
"""
fetch_logos.py — Download real, full-colour airline logos for the Vestor wall.

WHY THIS EXISTS
---------------
The flight scenes lead with the operating carrier's *real* logo (the same wow
factor as theflightwall.com). Procedural pixel marks looked muddy; real
vector-derived wordmarks downscale beautifully and survive gamma 2.2 + bloom.

SOURCE
------
pics.avs.io (TravelPayouts / Aviasales CDN) serves free, CORS-enabled,
transparent-background PNG wordmark logos keyed by **IATA** code:

    https://pics.avs.io/<width>/<height>/<IATA>.png

It always returns the horizontal wordmark fitted to the requested width and
vertically centred on a transparent canvas — perfect for a 1024px-wide ribbon.

WHAT THIS DOES
--------------
For every carrier we care about (ICAO operator code -> IATA), download a
high-res logo, crop to the opaque bounding box, scale to a fixed pixel HEIGHT
(so all marks share a baseline), and save to ``sim/logos/<IATA>.png``. Also
writes ``sim/logos/manifest.json`` (IATA -> {w,h,icao[]}) so both the browser
sim and the on-Pi Python renderer can lay logos out without re-measuring.

Re-runnable and offline-friendly: once ``sim/logos/`` is populated the wall
needs no network for branding. Run again to refresh.

    python3 tools/fetch_logos.py
"""

import io
import json
import os
import sys
import time
import urllib.request

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.normpath(os.path.join(HERE, "..", "sim", "logos"))
STORE_HEIGHT = 64          # px tall stored source (crisp headroom; tiny files)
REQ_WIDTH = 640            # request a wide source so the logo is high-res
UA = {"User-Agent": "Mozilla/5.0 (vestor-led-wall logo prep)"}

# ICAO operator code (first 3 chars of an ADS-B callsign) -> IATA code.
# IATA is what pics.avs.io is keyed by; ICAO is what our flight feed gives us.
# Order roughly: US majors, US regionals/LCC, Canada, Europe/Gulf/intl, cargo.
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


def fetch_png(iata: str) -> Image.Image | None:
    url = f"https://pics.avs.io/{REQ_WIDTH}/{REQ_WIDTH}/{iata}.png"
    try:
        req = urllib.request.Request(url, headers=UA)
        data = urllib.request.urlopen(req, timeout=15).read()
        im = Image.open(io.BytesIO(data)).convert("RGBA")
        return im
    except Exception as e:  # noqa: BLE001
        print(f"  ! {iata}: fetch failed ({e})")
        return None


def process(iata: str) -> tuple[int, int] | None:
    im = fetch_png(iata)
    if im is None:
        return None
    bbox = im.getchannel("A").getbbox()
    if not bbox:
        print(f"  - {iata}: empty / no logo on file")
        return None
    im = im.crop(bbox)
    cw, ch = im.size
    if ch == 0:
        return None
    scale = STORE_HEIGHT / ch
    tw = max(1, round(cw * scale))
    im = im.resize((tw, STORE_HEIGHT), Image.LANCZOS)
    im.save(os.path.join(OUT_DIR, f"{iata}.png"), optimize=True)
    return (tw, STORE_HEIGHT)


def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)
    # invert map: IATA -> [icao,...] (several ICAO codes can share one IATA)
    iata_to_icao: dict[str, list[str]] = {}
    for icao, iata in ICAO_TO_IATA.items():
        iata_to_icao.setdefault(iata, []).append(icao)

    manifest: dict[str, dict] = {}
    ok = 0
    for iata in sorted(iata_to_icao):
        size = process(iata)
        if size:
            manifest[iata] = {"w": size[0], "h": size[1],
                              "icao": iata_to_icao[iata]}
            print(f"  + {iata:3s} {size[0]:3d}x{size[1]:2d}  <- {','.join(iata_to_icao[iata])}")
            ok += 1
        time.sleep(0.05)  # be polite to the CDN

    with open(os.path.join(OUT_DIR, "manifest.json"), "w") as f:
        json.dump({"icaoToIata": ICAO_TO_IATA, "logos": manifest}, f, indent=1)
    print(f"\n{ok}/{len(iata_to_icao)} logos saved to {OUT_DIR}")
    print(f"manifest.json written ({len(manifest)} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
