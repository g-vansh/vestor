/* =============================================================================
 * Vestor — Airline brand registry + procedural carrier marks
 * -----------------------------------------------------------------------------
 * Real airport gate boards identify a carrier by *brand colour + emblem* long
 * before you read any text. This module gives the flight scenes that vocabulary:
 *
 *   airlineFor(callsign)  → { code, name, color, accent, mark }   (ICAO-prefix
 *                            lookup; graceful generic fallback for unknowns)
 *   drawAirlineMark(m, x, y, h, entry, t)  → draws the carrier emblem in a box
 *                            of pixel-height `h`, top-left at (x, y); returns the
 *                            width drawn.
 *
 * Marks are drawn PROCEDURALLY (shape math, not bitmaps) so the very same emblem
 * renders crisply at h=26 on the full-wall takeover and h=7 on the single panel —
 * no anti-alias mush, no per-size assets. Most carriers use the swept tail-fin
 * tinted to their livery; the three most iconic get bespoke shapes (Delta widget,
 * United globe, Southwest heart). Colours are LED-boosted (bright + saturated) so
 * they glow believably through gamma 2.2 + bloom.
 *
 * ICAO 3-letter operator codes (the first 3 chars of an ADS-B callsign) key the
 * table, so this works identically for SIM and LIVE (airplanes.live) flights.
 * ========================================================================== */
'use strict';

/* code → brand. color = wordmark/primary; accent = secondary livery stripe. */
const AIRLINE_DB = {
  // ---- US majors -------------------------------------------------------
  AAL: { name: 'AMERICAN',   color: [230, 55, 70],  accent: [60, 110, 220], mark: 'tail'    },
  DAL: { name: 'DELTA',      color: [235, 60, 75],  accent: [150, 24, 44],  mark: 'widget'  },
  UAL: { name: 'UNITED',     color: [70, 130, 245], accent: [25, 55, 150],  mark: 'globe'   },
  SWA: { name: 'SOUTHWEST',  color: [255, 190, 40], accent: [235, 70, 60],  mark: 'heart'   },
  JBU: { name: 'JETBLUE',    color: [70, 150, 255], accent: [25, 50, 130],  mark: 'tail'    },
  ASA: { name: 'ALASKA',     color: [30, 160, 200], accent: [20, 40, 90],   mark: 'tail'    },
  NKS: { name: 'SPIRIT',     color: [255, 225, 0],  accent: [40, 40, 40],   mark: 'tail'    },
  FFT: { name: 'FRONTIER',   color: [45, 205, 95],  accent: [235, 240, 255],mark: 'tail'    },
  AAY: { name: 'ALLEGIANT',  color: [255, 180, 30], accent: [20, 50, 120],  mark: 'tail'    },
  HAL: { name: 'HAWAIIAN',   color: [255, 90, 160], accent: [120, 70, 200], mark: 'tail'    },
  // ---- regionals (operate for the majors) ------------------------------
  RPA: { name: 'REPUBLIC',   color: [80, 140, 235], accent: [25, 55, 150],  mark: 'tail'    },
  EDV: { name: 'ENDEAVOR',   color: [235, 60, 75],  accent: [150, 24, 44],  mark: 'widget'  },
  JZA: { name: 'AIR CANADA', color: [235, 55, 60],  accent: [235, 240, 255],mark: 'roundel' },
  SKW: { name: 'SKYWEST',    color: [120, 160, 235],accent: [25, 55, 150],  mark: 'tail'    },
  // ---- Canada / Latin --------------------------------------------------
  ACA: { name: 'AIR CANADA', color: [235, 55, 60],  accent: [235, 240, 255],mark: 'roundel' },
  WJA: { name: 'WESTJET',    color: [0, 160, 170],  accent: [20, 50, 110],  mark: 'tail'    },
  // ---- Europe / Gulf / intl --------------------------------------------
  BAW: { name: 'BRITISH A.', color: [70, 120, 235], accent: [225, 50, 70],  mark: 'tail'    },
  AFR: { name: 'AIR FRANCE', color: [80, 110, 235], accent: [225, 50, 70],  mark: 'tail'    },
  DLH: { name: 'LUFTHANSA',  color: [255, 200, 0],  accent: [20, 35, 95],   mark: 'ring'    },
  UAE: { name: 'EMIRATES',   color: [235, 55, 55],  accent: [255, 200, 40], mark: 'tail'    },
  KLM: { name: 'KLM',        color: [90, 170, 255], accent: [235, 240, 255],mark: 'tail'    },
  VIR: { name: 'VIRGIN',     color: [235, 45, 75],  accent: [120, 30, 60],  mark: 'tail'    },
  ICE: { name: 'ICELANDAIR', color: [70, 150, 235], accent: [235, 240, 255],mark: 'tail'    },
  TAP: { name: 'TAP',        color: [45, 205, 95],  accent: [235, 55, 60],  mark: 'tail'    },
  // ---- business / commuter (BOS-area) ----------------------------------
  EJA: { name: 'NETJETS',    color: [120, 160, 230],accent: [255, 200, 40], mark: 'tail'    },
  KAP: { name: 'CAPE AIR',   color: [235, 70, 70],  accent: [255, 200, 40], mark: 'tail'    },
  LXJ: { name: 'FLEXJET',    color: [200, 170, 110],accent: [40, 40, 40],   mark: 'tail'    },
};

/* generic fallback — sodium amber, generic fin, name = the operator code */
const _GENERIC = { color: [255, 176, 0], accent: [150, 100, 0], mark: 'tail' };

function airlineFor(callsign) {
  const m = String(callsign || '').toUpperCase().match(/^[A-Z]{3}/);
  const code = m ? m[0] : '';
  const e = AIRLINE_DB[code];
  if (e) return Object.assign({ code }, e);
  return Object.assign({ code, name: code || 'FLIGHT' }, _GENERIC);
}

/* ---- procedural marks (all take a box x,y,w,h and brand colours) --------- */

function _tail(m, x, y, w, h, col, acc) {
  // swept vertical stabiliser: vertical trailing edge, raked leading edge,
  // a livery cheatline across the mid, and a short fuselage stub at the base.
  const base = y + h - 1;
  const rX = 0.86;                                  // trailing edge (right)
  for (let yy = 0; yy < h; yy++) {
    const v = h > 1 ? yy / (h - 1) : 0;
    const xL = clamp(0.46 - 0.40 * v, 0, 1);        // leading edge rakes left
    const aL = Math.round(x + xL * w), aR = Math.round(x + rX * w);
    m.hline(aL, aR, y + yy, col);
    if (v >= 0.50 && v <= 0.72) m.hline(aL + 1, aR, y + yy, acc);  // cheatline
  }
  m.hline(x, x + Math.round(rX * w), base, col);    // fuselage stub
  if (h >= 12) m.hline(x, x + Math.round(rX * w), base - 1, scale(col, 0.6));
  return w;
}

function _widget(m, x, y, w, h, col, acc) {
  // Delta "widget": wide triangle, apex top, right half shaded (the 3-D fold).
  for (let yy = 0; yy < h; yy++) {
    const v = h > 1 ? yy / (h - 1) : 0;
    const hw = 0.46 * v;
    const L = Math.round(x + (0.5 - hw) * w);
    const C = Math.round(x + 0.5 * w);
    const R = Math.round(x + (0.5 + hw) * w);
    m.hline(L, C, y + yy, col);
    if (R > C) m.hline(C + 1, R, y + yy, acc);
  }
  return w;
}

function _globe(m, x, y, w, h, col, acc) {
  // United globe: ring + crosshair meridian/equator + two parallels.
  const cx = x + (w >> 1), cy = y + (h >> 1), r = Math.max(2, (Math.min(w, h) >> 1));
  m.circle(cx, cy, r, col);
  m.vline(cx, cy - r, cy + r, scale(col, 0.85));
  m.hline(cx - r, cx + r, cy, scale(col, 0.85));
  if (r >= 4) {
    const pr = Math.round(r * 0.84), po = Math.round(r * 0.5);
    m.hline(cx - pr, cx + pr, cy - po, scale(col, 0.6));
    m.hline(cx - pr, cx + pr, cy + po, scale(col, 0.6));
  }
  return w;
}

function _heart(m, x, y, w, h, col, acc) {
  // Southwest heart: two lobes over a V, with a warm accent band low.
  for (let yy = 0; yy < h; yy++) {
    const v = (yy + 0.5) / h;
    for (let xx = 0; xx < w; xx++) {
      const u = (xx + 0.5) / w;
      let inside;
      if (v < 0.5) {
        inside = Math.hypot(u - 0.30, v - 0.30) < 0.30 ||
                 Math.hypot(u - 0.70, v - 0.30) < 0.30;
      } else {
        inside = Math.abs(u - 0.5) < 0.46 * (1 - (v - 0.5) / 0.5);
      }
      if (inside) m.set(x + xx, y + yy, (v >= 0.58 && v < 0.74) ? acc : col);
    }
  }
  return w;
}

function _ring(m, x, y, w, h, col, acc) {
  // Lufthansa: yellow ring + a vertical crane hint.
  const cx = x + (w >> 1), cy = y + (h >> 1), r = Math.max(2, (Math.min(w, h) >> 1));
  m.circle(cx, cy, r, col);
  if (r >= 3) m.circle(cx, cy, r - 1, scale(col, 0.55));
  m.vline(cx, cy - Math.round(r * 0.45), cy + Math.round(r * 0.45), col);
  return w;
}

function _roundel(m, x, y, w, h, col, acc) {
  // Air Canada: filled disc with a lighter centre (rondelle).
  const cx = x + (w >> 1), cy = y + (h >> 1), r = Math.max(2, (Math.min(w, h) >> 1));
  m.fillCircle(cx, cy, r, col);
  m.fillCircle(cx, cy, Math.max(1, r - Math.max(1, r >> 1)), acc);
  m.set(cx, cy - r, col);
  return w;
}

function drawAirlineMark(m, x, y, h, e, t) {
  const col = e.color, acc = e.accent || scale(e.color, 0.5);
  const mk = e.mark || 'tail';
  const w = Math.round(h * (mk === 'widget' ? 1.15 : mk === 'tail' ? 0.95 : 1.0));
  switch (mk) {
    case 'widget':  return _widget(m, x, y, w, h, col, acc);
    case 'globe':   return _globe(m, x, y, w, h, col, acc);
    case 'heart':   return _heart(m, x, y, w, h, col, acc);
    case 'ring':    return _ring(m, x, y, w, h, col, acc);
    case 'roundel': return _roundel(m, x, y, w, h, col, acc);
    default:        return _tail(m, x, y, w, h, col, acc);
  }
}

function markWidth(h, e) {
  const mk = (e && e.mark) || 'tail';
  return Math.round(h * (mk === 'widget' ? 1.15 : mk === 'tail' ? 0.95 : 1.0));
}

/* expose */
window.AIRLINE_DB = AIRLINE_DB;
window.airlineFor = airlineFor;
window.drawAirlineMark = drawAirlineMark;
window.markWidth = markWidth;
