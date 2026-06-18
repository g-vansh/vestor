/* =============================================================================
 * Vestor — Airline brand registry + REAL full-colour logo rendering
 * -----------------------------------------------------------------------------
 * Real gate boards (and theflightwall.com) identify a carrier by its actual
 * logo, in full colour, at panel height. Procedural pixel marks looked muddy;
 * real vector-derived wordmarks downscale beautifully and survive gamma 2.2 +
 * additive bloom. So this module renders the genuine carrier logo.
 *
 *   SOURCE   pics.avs.io (TravelPayouts/Aviasales CDN) — free, CORS-enabled,
 *            transparent-background PNG wordmarks keyed by IATA code. Pre-fetched
 *            and pre-sized into sim/logos/<IATA>.png by tools/fetch_logos.py, so
 *            the wall is fully offline (no per-frame network, no CORS taint).
 *
 *   FLOW     ADS-B callsign → ICAO operator (first 3 chars) → IATA → logo PNG.
 *            The PNG is downscaled into an offscreen canvas at the exact LED
 *            target height, read back with getImageData, and alpha-composited
 *            into the matrix framebuffer over the black substrate.
 *
 * Public API
 *   airlineFor(callsign)                 → { code, iata, name, color, accent }
 *   logoReady(iata) / logoAspect(iata)
 *   drawLogoH(m, iata, x, y, h, align)   → width drawn (0 if not loaded yet)
 *   fitLogoBox(m, iata, x, y, maxW, maxH, align, valign) → [w, h] drawn
 *
 * `color`/`accent` are LED-boosted livery tints used for non-logo accents
 * (route line, plane icon, altitude gauge). For curated majors they come from
 * AIRLINE_DB; for everyone else they are sampled from the logo's own pixels.
 * ========================================================================== */
'use strict';

/* ICAO operator code (first 3 chars of an ADS-B callsign) -> IATA code.
 * IATA is what the logo CDN is keyed by; ICAO is what the flight feed gives us.
 * Mirror of tools/fetch_logos.py ICAO_TO_IATA (keep in sync). */
const ICAO_TO_IATA = {
  // US majors
  AAL: 'AA', DAL: 'DL', UAL: 'UA', SWA: 'WN', JBU: 'B6',
  ASA: 'AS', NKS: 'NK', FFT: 'F9', AAY: 'G4', HAL: 'HA',
  SCX: 'SY', MXY: 'MX', VXP: 'XP',
  // US regionals (operate for the majors)
  RPA: 'YX', EDV: '9E', SKW: 'OO', ENY: 'MQ', PDT: 'PT',
  ASH: 'YV', GJS: 'G7', AWI: 'ZW', JIA: 'OH',
  // Canada
  ACA: 'AC', JZA: 'QK', WJA: 'WS', POE: 'PD', TSC: 'TS',
  // Europe / UK / Ireland
  BAW: 'BA', AFR: 'AF', DLH: 'LH', KLM: 'KL', VIR: 'VS',
  ICE: 'FI', TAP: 'TP', EIN: 'EI', SWR: 'LX', IBE: 'IB',
  AUA: 'OS', SAS: 'SK', FIN: 'AY', VLG: 'VY', EZY: 'U2',
  ITY: 'AZ', BEL: 'SN', TAY: '3V', NAX: 'DY', VOE: 'V7',
  // Gulf / Asia / Latin / Oceania
  UAE: 'EK', QTR: 'QR', ETD: 'EY', THY: 'TK', CPA: 'CX',
  JAL: 'JL', ANA: 'NH', SIA: 'SQ', KAL: 'KE', AAR: 'OZ',
  QFA: 'QF', AMX: 'AM', AVA: 'AV', LAN: 'LA', CMP: 'CM',
  TAM: 'JJ', ELY: 'LY',
  // Business / commuter (BOS-area)
  KAP: '9K',
  // Cargo (common at BOS)
  FDX: 'FX', UPS: '5X', GTI: '5Y', ABX: 'GB',
};

/* Curated brand names + LED-boosted livery colours for the carriers we expect
 * most at KBOS. `name` is the wordmark fallback when a logo PNG is missing;
 * `color`/`accent` tint the non-logo accents. Everyone else gets a colour
 * sampled from their own logo. */
const AIRLINE_DB = {
  AAL: { name: 'AMERICAN',   color: [230, 55, 70],  accent: [60, 110, 220] },
  DAL: { name: 'DELTA',      color: [235, 60, 75],  accent: [150, 24, 44]  },
  UAL: { name: 'UNITED',     color: [70, 130, 245], accent: [25, 55, 150]  },
  SWA: { name: 'SOUTHWEST',  color: [255, 190, 40], accent: [235, 70, 60]  },
  JBU: { name: 'JETBLUE',    color: [70, 150, 255], accent: [25, 50, 130]  },
  ASA: { name: 'ALASKA',     color: [30, 160, 200], accent: [20, 40, 90]   },
  NKS: { name: 'SPIRIT',     color: [255, 225, 0],  accent: [40, 40, 40]   },
  FFT: { name: 'FRONTIER',   color: [45, 205, 95],  accent: [235, 240, 255]},
  AAY: { name: 'ALLEGIANT',  color: [255, 180, 30], accent: [20, 50, 120]  },
  HAL: { name: 'HAWAIIAN',   color: [255, 90, 160], accent: [120, 70, 200] },
  RPA: { name: 'REPUBLIC',   color: [80, 140, 235], accent: [25, 55, 150]  },
  EDV: { name: 'ENDEAVOR',   color: [235, 60, 75],  accent: [150, 24, 44]  },
  SKW: { name: 'SKYWEST',    color: [120, 160, 235],accent: [25, 55, 150]  },
  ACA: { name: 'AIR CANADA', color: [235, 55, 60],  accent: [235, 240, 255]},
  JZA: { name: 'AIR CANADA', color: [235, 55, 60],  accent: [235, 240, 255]},
  WJA: { name: 'WESTJET',    color: [0, 160, 170],  accent: [20, 50, 110]  },
  BAW: { name: 'BRITISH A.', color: [70, 120, 235], accent: [225, 50, 70]  },
  AFR: { name: 'AIR FRANCE', color: [80, 110, 235], accent: [225, 50, 70]  },
  DLH: { name: 'LUFTHANSA',  color: [255, 200, 0],  accent: [20, 35, 95]   },
  UAE: { name: 'EMIRATES',   color: [235, 55, 55],  accent: [255, 200, 40] },
  KLM: { name: 'KLM',        color: [90, 170, 255], accent: [235, 240, 255]},
  VIR: { name: 'VIRGIN',     color: [235, 45, 75],  accent: [120, 30, 60]  },
  ICE: { name: 'ICELANDAIR', color: [70, 150, 235], accent: [235, 240, 255]},
  TAP: { name: 'TAP',        color: [45, 205, 95],  accent: [235, 55, 60]  },
  EIN: { name: 'AER LINGUS', color: [45, 205, 120], accent: [235, 240, 255]},
  SWR: { name: 'SWISS',      color: [235, 55, 55],  accent: [235, 240, 255]},
  THY: { name: 'TURKISH',    color: [235, 55, 55],  accent: [235, 240, 255]},
  QTR: { name: 'QATAR',      color: [140, 30, 70],  accent: [235, 240, 255]},
  FIN: { name: 'FINNAIR',    color: [70, 130, 245], accent: [235, 240, 255]},
  SY:  { name: 'SUN CTRY',   color: [70, 130, 245], accent: [235, 55, 60]  },
  EJA: { name: 'NETJETS',    color: [120, 160, 230],accent: [255, 200, 40] },
  KAP: { name: 'CAPE AIR',   color: [235, 70, 70],  accent: [255, 200, 40] },
  LXJ: { name: 'FLEXJET',    color: [200, 170, 110],accent: [40, 40, 40]   },
};

const _GENERIC_COLOR = [255, 176, 0];           // sodium amber fallback
const LOGO_BASE = 'logos/';

/* ---- logo asset cache + offscreen rasteriser ----------------------------- */
const _img = {};        // iata -> HTMLImageElement
const _blit = {};       // 'iata@h' -> { w, h, data:Uint8ClampedArray }
const _brand = {};      // iata -> [r,g,b] sampled livery colour
const _tmp = [0, 0, 0]; // reused colour triple (avoid per-pixel GC)
let _scratch = null;

function _ensureImg(iata) {
  if (!iata) return null;
  let im = _img[iata];
  if (im) return im;
  im = new Image();
  im.decoding = 'async';
  im.src = LOGO_BASE + iata + '.png';
  _img[iata] = im;
  return im;
}

function logoReady(iata) {
  const im = _img[iata];
  return !!(im && im.complete && im.naturalWidth);
}
function logoAspect(iata) {
  const im = _img[iata];
  return (im && im.naturalWidth) ? im.naturalWidth / im.naturalHeight : null;
}

/* Downscale a logo to an exact LED height once; cache the read-back pixels so
 * subsequent frames are a cheap copy. Also samples the brand colour. */
function _raster(iata, h) {
  h = Math.max(1, Math.round(h));
  const key = iata + '@' + h;
  const cached = _blit[key];
  if (cached) return cached;
  const im = _img[iata];
  if (!im || !im.complete || !im.naturalWidth) return null;
  const ar = im.naturalWidth / im.naturalHeight;
  const w = Math.max(1, Math.round(h * ar));
  if (!_scratch) _scratch = document.createElement('canvas');
  _scratch.width = w; _scratch.height = h;
  const ctx = _scratch.getContext('2d', { willReadFrequently: true });
  ctx.clearRect(0, 0, w, h);
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(im, 0, 0, w, h);
  const data = ctx.getImageData(0, 0, w, h).data;
  const rec = { w, h, data };
  _blit[key] = rec;
  if (!_brand[iata]) _brand[iata] = _sampleBrand(data);
  return rec;
}

/* Weighted average of the logo's saturated, bright pixels → its livery hue,
 * then normalised so the dominant channel glows (LED-boosted). */
function _sampleBrand(data) {
  let r = 0, g = 0, b = 0, wt = 0;
  for (let i = 0; i < data.length; i += 4) {
    const a = data[i + 3];
    if (a < 50) continue;
    const R = data[i], G = data[i + 1], B = data[i + 2];
    const mx = Math.max(R, G, B), mn = Math.min(R, G, B);
    const sat = mx - mn;                       // grey outlines contribute little
    const w = (a / 255) * (sat + 12) * (mx / 255 + 0.1);
    r += R * w; g += G * w; b += B * w; wt += w;
  }
  if (wt <= 0) return _GENERIC_COLOR.slice();
  r /= wt; g /= wt; b /= wt;
  const mx = Math.max(r, g, b, 1), k = 225 / mx; // boost dominant channel
  return [Math.min(255, r * k), Math.min(255, g * k), Math.min(255, b * k)];
}

/* Draw a logo at an exact pixel height. align: 'left'|'right'|'center'. */
function drawLogoH(m, iata, x, y, h, align) {
  const rec = _raster(iata, h);
  if (!rec) return 0;
  const w = rec.w, d = rec.data;
  let ox = x;
  if (align === 'right') ox = x - w;
  else if (align === 'center') ox = Math.round(x - w / 2);
  for (let yy = 0; yy < rec.h; yy++) {
    const row = yy * w * 4;
    const py = y + yy;
    for (let xx = 0; xx < w; xx++) {
      const i = row + xx * 4;
      const a = d[i + 3];
      if (a === 0) continue;
      _tmp[0] = d[i]; _tmp[1] = d[i + 1]; _tmp[2] = d[i + 2];
      m.set(ox + xx, py, _tmp, a / 255);
    }
  }
  return w;
}

/* Fit a logo inside a box, preserving aspect. Returns [w,h] actually drawn.
 * valign: 'top'|'center'|'bottom'. */
function fitLogoBox(m, iata, x, y, maxW, maxH, align, valign) {
  const ar = logoAspect(iata);
  if (!ar) return [0, 0];
  let h = maxH, w = Math.round(h * ar);
  if (w > maxW) { w = maxW; h = Math.max(1, Math.round(w / ar)); }
  let oy = y;
  if (valign === 'center') oy = Math.round(y + (maxH - h) / 2);
  else if (valign === 'bottom') oy = y + (maxH - h);
  const drawn = drawLogoH(m, iata, x, oy, h, align);
  return [drawn, h];
}

/* Resolve a callsign to brand identity + accent colours. Kicks off the logo
 * load so it's ready by the next frame. */
function airlineFor(callsign) {
  const mm = String(callsign || '').toUpperCase().match(/^[A-Z]{3}/);
  const icao = mm ? mm[0] : '';
  const iata = ICAO_TO_IATA[icao] || null;
  if (iata) _ensureImg(iata);
  const db = AIRLINE_DB[icao];
  const color = db ? db.color
    : (iata && _brand[iata]) ? _brand[iata] : _GENERIC_COLOR;
  const accent = db ? (db.accent || scale(color, 0.5)) : scale(color, 0.5);
  const name = db ? db.name : (icao || 'FLIGHT');
  return { code: icao, iata, name, color, accent };
}

/* expose */
window.ICAO_TO_IATA = ICAO_TO_IATA;
window.AIRLINE_DB = AIRLINE_DB;
window.airlineFor = airlineFor;
window.logoReady = logoReady;
window.logoAspect = logoAspect;
window.drawLogoH = drawLogoH;
window.fitLogoBox = fitLogoBox;
