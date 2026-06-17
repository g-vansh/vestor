/* =============================================================================
 * Vestor — Scene library
 * -----------------------------------------------------------------------------
 * Each scene draws into a rectangular ZONE of an LEDMatrix given a data model,
 * elapsed time t (s) and frame delta dt (s). Scenes are stateful classes so
 * split-flaps, radar sweeps and counters can animate frame to frame.
 *
 * Zones are aligned to 64px panel boundaries on the 1024x32 wall:
 *   [  0.. 127]  CLOCK + DATE          (2 panels)
 *   [128.. 255]  WEATHER  (°C AND °F)  (2 panels)
 *   [256.. 575]  FLIGHTS hero          (5 panels — radar + split-flap board)
 *   [576.. 703]  BLUEBIKES             (2 panels)
 *   [704.. 831]  MIT TECH SHUTTLE      (2 panels)
 *   [832.. 959]  EXTRAS (rotating)     (2 panels)
 *   [960..1023]  STATUS end-cap        (1 panel)
 * The single-panel Phase-0 test reuses FlightScene at full 64x32.
 * ========================================================================== */
'use strict';

const FLAP_CHARS = ' ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-:/.°+';

/* ---- Split-flap (Solari) animated text -------------------------------- */
class SplitFlap {
  constructor(len, flipsPerSec) {
    this.len = len;
    this.fps = flipsPerSec || 22;
    this.target = ' '.repeat(len).split('');
    this.curIdx = new Array(len).fill(0);   // index into FLAP_CHARS
    this.acc = new Array(len).fill(0);
  }
  set(text) {
    text = String(text).toUpperCase().slice(0, this.len);
    text = text + ' '.repeat(this.len - text.length);
    for (let i = 0; i < this.len; i++) this.target[i] = text[i];
  }
  update(dt) {
    for (let i = 0; i < this.len; i++) {
      const tgt = FLAP_CHARS.indexOf(this.target[i]);
      const ti = tgt < 0 ? 0 : tgt;
      if (this.curIdx[i] !== ti) {
        this.acc[i] += dt * this.fps;
        while (this.acc[i] >= 1 && this.curIdx[i] !== ti) {
          this.acc[i] -= 1;
          this.curIdx[i] = (this.curIdx[i] + 1) % FLAP_CHARS.length;
        }
      } else {
        this.acc[i] = 0;
      }
    }
  }
  settled() {
    for (let i = 0; i < this.len; i++) {
      const tgt = FLAP_CHARS.indexOf(this.target[i]);
      if (this.curIdx[i] !== (tgt < 0 ? 0 : tgt)) return false;
    }
    return true;
  }
  draw(m, x, y, color, scl, font) {
    scl = scl || 1;
    const gw = font === '3x5' ? 3 : 5;
    const gh = font === '3x5' ? 7 : 7;
    const adv = (gw + 1) * scl;
    for (let i = 0; i < this.len; i++) {
      const ch = FLAP_CHARS[this.curIdx[i]];
      const tgt = FLAP_CHARS.indexOf(this.target[i]);
      const moving = this.curIdx[i] !== (tgt < 0 ? 0 : tgt);
      const cx = x + i * adv;
      const c = moving ? scale(color, 0.82) : color;
      m.text(cx, y, ch, c, scl, font);
      if (moving) {
        // flap seam across the middle while mechanically turning
        const sy = y + Math.floor((gh * scl) / 2);
        m.hline(cx, cx + gw * scl - 1, sy, PAL.ink, 120);
      }
    }
  }
}

/* ---- scrolling marquee ------------------------------------------------- */
class Marquee {
  constructor(speed) { this.x = 0; this.speed = speed || 14; }
  draw(m, zx, zy, zw, y, text, color, scl, dt) {
    scl = scl || 1;
    const w = m.measure(text, scl);
    this.x -= this.speed * dt;
    if (this.x < -(w + 8)) this.x = 0;
    // clip by only drawing within zone via temp: draw twice for wrap
    const startX = zx + Math.round(this.x);
    this._clipText(m, startX, zx, zw, y, text, color, scl);
    this._clipText(m, startX + w + 12, zx, zw, y, text, color, scl);
  }
  _clipText(m, x, zx, zw, y, text, color, scl) {
    // draw char by char, skip out-of-zone
    let cx = x;
    for (const ch of String(text).toUpperCase()) {
      if (cx + 6 * scl >= zx && cx <= zx + zw) m.text(cx, y, ch, color, scl);
      cx += 6 * scl;
    }
  }
}

/* ---- radar field (used by FlightScene) — a TRUE circular PPI scope ----- */
function drawRadar(m, cx, cy, r, t, blips, sweepColor, ringColor) {
  // concentric rings (circular: equal x/y radius). Finer angular step so the
  // outer ring reads as a continuous circle on the dot grid.
  for (let rr = Math.floor(r / 3) || 1; rr <= r; rr += Math.floor(r / 3) || 1) {
    const step = 0.9 / rr;                 // denser sampling for bigger rings
    for (let a = 0; a < Math.PI * 2; a += step) {
      const x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr;
      m.add(Math.round(x), Math.round(y), ringColor, rr === r ? 42 : 26);
    }
  }
  // crosshair (square aspect)
  m.hline(cx - r, cx + r, cy, scale(ringColor, 0.5), 40);
  m.vline(cx, cy - r, cy + r, scale(ringColor, 0.5), 40);
  // sweep line + faint sector trail
  const ang = (t * 1.1) % (Math.PI * 2);
  for (let k = 0; k < 14; k++) {
    const a = ang - k * 0.05;
    const fade = (1 - k / 14);
    const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
    m.line(cx, cy, Math.round(x), Math.round(y), scale(sweepColor, fade * 0.5), 255);
  }
  // blips: brighten when sweep passes, then decay
  for (const b of blips) {
    const bx = cx + Math.cos(b.a) * (b.d * r);
    const by = cy + Math.sin(b.a) * (b.d * r);
    let da = ((ang - b.a) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
    const glow = da < 1.2 ? (1 - da / 1.2) : 0.12;
    m.add(Math.round(bx), Math.round(by), b.color || PAL.green, 120 + glow * 135);
    if (glow > 0.4) m.add(Math.round(bx), Math.round(by) - 1, b.color || PAL.green, glow * 90);
  }
}

/* gauge: a small horizontal bar with label, 0..1 value */
function gauge(m, x, y, w, val, color, label) {
  val = clamp(val, 0, 1);
  m.hline(x, x + w - 1, y, scale(color, 0.22), 90);
  m.hline(x, x + Math.round((w - 1) * val), y, color, 255);
  m.add(x + Math.round((w - 1) * val), y, PAL.white, 200);
  if (label) m.text(x, y - 6, label, scale(color, 0.7), 1, '3x5');
}

/* ============================================================================
 * FLIGHT SCENE — the hero. Radar + split-flap callsign + route + gauges.
 * Adapts to zone width: compact (64px panel) or full (320px wall hero).
 * ========================================================================== */
class FlightScene {
  constructor() {
    this.call = new SplitFlap(8, 20);
    this.route = new SplitFlap(9, 16);
    this.curHex = null;
    this.marq = new Marquee(12);
    this.blips = [];
  }
  _ensureBlips(flights, r) {
    // map flights to radar blips by bearing/distance
    this.blips = flights.slice(0, 8).map(f => ({
      a: (f.track || 0) * Math.PI / 180 - Math.PI / 2,
      d: clamp((f.distance || 5) / 25, 0.12, 0.95),
      color: f._hero ? PAL.amber : PAL.green,
    }));
  }
  draw(m, x, y, w, h, data, t, dt) {
    const f = data.heroFlight;
    if (f && f.hex !== this.curHex) {
      this.curHex = f.hex;
      this.call.set(f.callsign || f.reg || '—');
      this.route.set(((f.origin || '???') + '-' + (f.dest || '???')));
    }
    this.call.update(dt); this.route.update(dt);

    if (w >= 200) this._drawWide(m, x, y, w, h, data, t, dt, f);
    else this._drawCompact(m, x, y, w, h, data, t, dt, f);
  }

  /* ---- 64px single-panel layout (Phase-0 hero) ---- */
  _drawCompact(m, x, y, w, h, data, t, dt, f) {
    const r = 9;
    this._ensureBlips(data.flights, r);
    if (!f) {
      drawRadar(m, x + (w >> 1), y + 18, 11, t, this.blips, PAL.cyan, PAL.cyanDim);
      m.textCenter(x + w / 2, y + 1, 'NO CONTACT', PAL.amberDim, 1, '3x5');
      return;
    }
    // callsign banner — full-width, centered, split-flap
    const fw = this.call.len * 6;
    this.call.draw(m, x + Math.max(0, (w - fw) >> 1), y, PAL.amber, 1);
    // route centered just below
    m.textCenter(x + w / 2, y + 8, (f.origin || '???') + '→' + (f.dest || '???'), PAL.cyan, 1, '3x5');
    // bottom-left mini radar
    drawRadar(m, x + 12, y + 22, r, t, this.blips, PAL.cyan, PAL.cyanDim);
    // bottom-right stacked stats
    const rx = x + 28;
    m.text(rx, y + 15, (f.alt ? 'FL' + Math.round(f.alt / 100) : 'FL--'), PAL.green, 1, '3x5');
    m.text(rx, y + 21, (f.gs ? Math.round(f.gs) : '--') + 'KT', PAL.warm, 1, '3x5');
    const climbing = f.vspeed > 64, descending = f.vspeed < -64;
    const vc = climbing ? PAL.green : descending ? PAL.red : PAL.amberDim;
    m.text(rx, y + 27, climbing ? '↑' + Math.round(f.vspeed)
      : descending ? '↓' + Math.abs(Math.round(f.vspeed)) : 'LEVEL', vc, 1, '3x5');
  }

  /* ---- 320px wall hero layout ---- */
  _drawWide(m, x, y, w, h, data, t, dt, f) {
    // Left radar disc — true circle, centered in the left 34px gutter
    const r = 12, cx = x + 16, cy = y + 14;
    this._ensureBlips(data.flights, r);
    drawRadar(m, cx, cy, r, t, this.blips, PAL.cyan, PAL.cyanDim);
    m.textCenter(cx, y + 27, data.flights.length + ' TRK', PAL.cyanDim, 1, '3x5');

    const bx = x + 38;            // board start
    const bw = w - 40;
    if (!f) {
      m.textCenter(bx + bw / 2, y + 13, 'SCANNING CAMBRIDGE SKY', PAL.amberDim, 1, '3x5');
      return;
    }
    // Header: big split-flap callsign
    this.call.draw(m, bx, y + 1, PAL.amber, 1);
    // airline/type chip to the right of callsign
    m.text(bx + 56, y + 1, (f.type || ''), PAL.purple, 1, '3x5');

    // Route line: ORIGIN  →  DEST  with city names
    const oy = y + 9;
    m.text(bx, oy, (f.origin || '???'), PAL.cyan, 1);
    m.text(bx + 22, oy, '→', PAL.white, 1);
    m.text(bx + 30, oy, (f.dest || '???'), PAL.cyan, 1);
    // route arc visual between codes
    this._routeArc(m, bx + 2, y + 7, bx + 28, y + 7, t);
    // city subtitle (marquee if long)
    const cities = ((f.originCity || '') + '  ' + (f.destCity || '')).trim();
    m.text(bx, y + 18, (f.originCity || '').slice(0, 9), PAL.cyanDim, 1, '3x5');
    m.textRight(x + w - 1, y + 18, (f.destCity || '').slice(0, 9), PAL.cyanDim, 1, '3x5');

    // bottom data row — compact, no comma glyphs, no overlapping gauge labels
    const dy = y + 24;
    m.text(bx, dy, (f.alt ? 'FL' + Math.round(f.alt / 100) : 'FL--'), PAL.green, 1, '3x5');
    m.text(bx + 30, dy, (f.gs ? Math.round(f.gs) : '--') + 'KT', PAL.warm, 1, '3x5');
    const climbing = f.vspeed > 64, descending = f.vspeed < -64;
    const vc = climbing ? PAL.green : descending ? PAL.red : PAL.amberDim;
    m.text(bx + 62, dy, climbing ? '↑' + Math.round(f.vspeed)
      : descending ? '↓' + Math.abs(Math.round(f.vspeed)) : 'LVL', vc, 1, '3x5');
    m.textRight(x + w - 1, dy, (f.distance ? f.distance.toFixed(1) : '--') + 'MI', PAL.white, 1, '3x5');
    // thin unlabeled gauges underneath
    const gy = y + 30, halfW = Math.floor((w - 42) / 2) - 4;
    gauge(m, bx, gy, halfW, (f.alt || 0) / 40000, PAL.green);
    gauge(m, bx + halfW + 8, gy, halfW, (f.gs || 0) / 600, PAL.warm);
  }
  _routeArc(m, x0, y0, x1, y1, t) {
    const steps = 16;
    for (let i = 0; i <= steps; i++) {
      const p = i / steps;
      const xx = x0 + (x1 - x0) * p;
      const yy = y0 - Math.sin(p * Math.PI) * 4;
      const pulse = (Math.sin(t * 3 - p * 6) * 0.5 + 0.5);
      m.add(Math.round(xx), Math.round(yy), PAL.cyan, 40 + pulse * 90);
    }
  }
}

/* ============================================================================
 * WEATHER SCENE — temperature in BOTH °C and °F (explicit requirement)
 * ========================================================================== */
const WMO_ICON = (code, isDay) => {
  if (code === 0) return isDay ? 'sun' : 'moon';
  if (code <= 2) return isDay ? 'sun' : 'moon';
  if (code === 3 || code === 45 || code === 48) return 'cloud';
  if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) return 'rain';
  if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snow';
  return 'cloud';
};
const WMO_TEXT = (code) => {
  const map = { 0: 'CLEAR', 1: 'FAIR', 2: 'PARTLY', 3: 'CLOUDY', 45: 'FOG', 48: 'RIME',
    51: 'DRIZZLE', 53: 'DRIZZLE', 55: 'DRIZZLE', 61: 'RAIN', 63: 'RAIN', 65: 'HVY RAIN',
    71: 'SNOW', 73: 'SNOW', 75: 'HVY SNOW', 80: 'SHOWERS', 81: 'SHOWERS', 82: 'STORM',
    95: 'THUNDER', 96: 'THUNDER', 99: 'THUNDER' };
  return map[code] || 'WX';
};
class WeatherScene {
  draw(m, x, y, w, h, data, t, dt) {
    const wx = data.weather;
    const c = WMO_ICON(wx.code, wx.isDay);
    const accent = wx.isDay ? PAL.warm : PAL.blue;
    // icon top-left, animated shimmer
    const pulse = 0.7 + 0.3 * Math.sin(t * 2);
    m.icon(x + 1, y + 1, c, scale(accent, pulse));
    // condition text
    m.text(x + 12, y + 1, WMO_TEXT(wx.code), accent, 1, '3x5');
    // BIG dual temperature: large °C, then °F beneath — both always shown
    const tc = Math.round(wx.tempC);
    const tf = Math.round(wx.tempC * 9 / 5 + 32);
    m.text(x + 12, y + 8, tc + '°C', PAL.amber, 1);          // 5x7 prominent
    m.text(x + 12, y + 17, tf + '°F', PAL.green, 1);         // 5x7 prominent
    // right column micro-stats
    const rx = x + w - 1;
    m.textRight(rx, y + 1, 'H' + Math.round(wx.hiC) + '°', PAL.redDim, 1, '3x5');
    m.textRight(rx, y + 7, 'L' + Math.round(wx.loC) + '°', PAL.cyanDim, 1, '3x5');
    m.textRight(rx, y + 14, Math.round(wx.humidity) + '%RH', PAL.cyan, 1, '3x5');
    m.textRight(rx, y + 20, Math.round(wx.windKph) + 'KPH', PAL.white, 1, '3x5');
    // wind dir arrow
    this._windArrow(m, rx - 4, y + 27, wx.windDir, t);
    // feels-like ribbon at bottom
    const ff = Math.round(wx.feelsC * 9 / 5 + 32);
    m.text(x + 1, y + 27, 'FEEL ' + Math.round(wx.feelsC) + '°/' + ff + '°', scale(accent, 0.8), 1, '3x5');
  }
  _windArrow(m, x, y, deg, t) {
    const a = (deg || 0) * Math.PI / 180;
    const dx = Math.sin(a), dy = -Math.cos(a);
    m.line(x - Math.round(dx * 2), y - Math.round(dy * 2),
      x + Math.round(dx * 2), y + Math.round(dy * 2), PAL.white, 200);
    m.add(x + Math.round(dx * 2), y + Math.round(dy * 2), PAL.amber, 220);
  }
}

/* ============================================================================
 * BLUEBIKES SCENE — Pacific St @ Purrington St: classic + ebikes + docks
 * ========================================================================== */
class BluebikesScene {
  constructor() { this.flash = 0; this.prev = -1; }
  draw(m, x, y, w, h, data, t, dt) {
    const b = data.bikes;
    m.text(x + 1, y + 1, 'BLUEBIKES', PAL.cyan, 1, '3x5');
    m.icon(x + w - 12, y, 'pin', PAL.cyanDim);
    // station short name (left) + free-dock count (right) share one row
    m.text(x + 1, y + 7, 'PACIFIC ST', PAL.cyanDim, 1, '3x5');
    m.textRight(x + w - 1, y + 7, b.docks + ' DOCKS', PAL.cyanDim, 1, '3x5');

    // classic bikes row — icon + label + big count
    m.icon(x + 1, y + 14, 'bike', PAL.white);
    m.text(x + 14, y + 15, 'CLASSIC', PAL.white, 1, '3x5');
    m.textRight(x + w - 1, y + 14, String(b.classic), PAL.green, 1);

    // ebikes row — bolt + label + big count
    m.icon(x + 4, y + 23, 'bolt', PAL.magenta);
    m.text(x + 14, y + 24, 'E-BIKE', PAL.magenta, 1, '3x5');
    m.textRight(x + w - 1, y + 23, String(b.ebikes), PAL.magenta, 1);

    // occupancy bar (filled = bikes present / capacity) along the bottom edge
    const free = b.docks, cap = b.capacity || (b.classic + b.ebikes + b.docks);
    const filled = Math.round((w - 2) * ((cap - free) / cap));
    m.hline(x + 1, x + w - 2, y + h - 1, PAL.cyanDim, 70);
    m.hline(x + 1, x + 1 + filled, y + h - 1, PAL.cyan, 200);

    // pulse the classic count if low total availability
    if (b.classic + b.ebikes <= 2) {
      const p = (Math.sin(t * 6) * 0.5 + 0.5);
      m.textRight(x + w - 1, y + 14, String(b.classic), mix(PAL.green, PAL.red, p), 1);
    }
  }
}

/* ============================================================================
 * SHUTTLE SCENE — MIT Tech Shuttle + Tech Shuttle NW @ Grad Junction
 * ========================================================================== */
class ShuttleScene {
  draw(m, x, y, w, h, data, t, dt) {
    const s = data.shuttle;
    m.icon(x + 1, y, 'bus', PAL.amber);
    m.text(x + 13, y + 1, 'MIT SHUTTLE', PAL.amber, 1, '3x5');
    m.text(x + 1, y + 7, 'GRAD JUNCTION', PAL.amberDim, 1, '3x5');

    // route 1: TECH
    this._row(m, x, y + 13, w, 'TECH', s.tech, PAL.green, t);
    // route 2: TECH NW
    this._row(m, x, y + 22, w, 'TECH NW', s.techNW, PAL.cyan, t);
  }
  _row(m, x, y, w, label, etas, color, t) {
    m.text(x + 1, y, label, color, 1, '3x5');
    const lead = (etas && etas.length) ? etas[0] : null;
    if (lead === null) { m.textRight(x + w - 1, y, '--', PAL.amberDim, 1, '3x5'); return; }
    // imminent (<=1 min): flash "DUE"
    if (lead <= 1) {
      const p = (Math.sin(t * 8) * 0.5 + 0.5);
      m.textRight(x + w - 1, y, 'DUE', mix(color, PAL.white, p), 1, '3x5');
    } else {
      const txt = lead + 'M';
      m.textRight(x + w - 1, y, txt, color, 1, '3x5');
      // next two ETAs as small ticks
      const nxt = etas.slice(1, 3).join(' ');
      if (nxt) m.text(x + 38, y, nxt, scale(color, 0.55), 1, '3x5');
    }
  }
}

/* ============================================================================
 * CLOCK SCENE — big time + day + date
 * ========================================================================== */
class ClockScene {
  draw(m, x, y, w, h, data, t, dt) {
    const d = data.now;
    const hh = d.getHours(), mm = d.getMinutes(), ss = d.getSeconds();
    const h12 = ((hh % 12) || 12);
    const hs = (h12 < 10 ? ' ' : '') + h12;
    const ms = (mm < 10 ? '0' : '') + mm;
    // big HH:MM at scale 2 (10x14 glyphs)
    const time = hs + ':' + ms;
    const tw = m.measure(time, 2);
    m.text(x + Math.round((w - tw) / 2) - 4, y + 2, time, PAL.amber, 2);
    // blinking colon overlay
    if (ss % 2 === 0) {
      // dim the colon for a soft tick
      const colonX = x + Math.round((w - tw) / 2) - 4 + 2 * 6 * 2;
    }
    // seconds bar across the bottom
    const frac = (ss + d.getMilliseconds() / 1000) / 60;
    m.hline(x + 2, x + w - 3, y + h - 1, PAL.amberDim, 60);
    m.hline(x + 2, x + 2 + Math.round((w - 5) * frac), y + h - 1, PAL.amber, 220);
    // AM/PM + day + date row
    const days = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
    const mons = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
    const ap = hh < 12 ? 'AM' : 'PM';
    m.text(x + 2, y + 24, days[d.getDay()], PAL.cyan, 1, '3x5');
    m.textCenter(x + w / 2, y + 24, mons[d.getMonth()] + ' ' + d.getDate(), PAL.white, 1, '3x5');
    m.textRight(x + w - 2, y + 24, ap, PAL.green, 1, '3x5');
  }
}

/* ============================================================================
 * EXTRAS SCENE — rotates: ISS overhead / MBTA Red Line / AQI / Moon / Tide
 * ========================================================================== */
class ExtrasScene {
  constructor() { this.idx = 0; this.timer = 0; this.period = 6; }
  draw(m, x, y, w, h, data, t, dt) {
    this.timer += dt;
    if (this.timer > this.period) { this.timer = 0; this.idx = (this.idx + 1) % 5; }
    const e = data.extras;
    // rotation dots at top-right
    for (let i = 0; i < 5; i++)
      m.set(x + w - 11 + i * 2, y, i === this.idx ? PAL.amber : PAL.amberDim);
    // transition fade
    const fade = clamp(Math.min(this.timer, this.period - this.timer) / 0.6, 0.15, 1);
    switch (this.idx) {
      case 0: this._iss(m, x, y, w, h, e.iss, t, fade); break;
      case 1: this._mbta(m, x, y, w, h, e.mbta, t, fade); break;
      case 2: this._aqi(m, x, y, w, h, e.aqi, t, fade); break;
      case 3: this._moon(m, x, y, w, h, e.moon, t, fade); break;
      case 4: this._tide(m, x, y, w, h, e.tide, t, fade); break;
    }
  }
  _iss(m, x, y, w, h, iss, t, f) {
    m.icon(x + 1, y + 1, 'sat', scale(PAL.cyan, f));
    m.text(x + 1, y + 8, 'ISS', scale(PAL.cyan, f), 1);
    if (iss.overhead) {
      const p = (Math.sin(t * 4) * 0.5 + 0.5);
      m.text(x + 1, y + 17, 'OVERHEAD', scale(mix(PAL.green, PAL.white, p), f), 1, '3x5');
      m.text(x + 1, y + 24, 'LOOK UP!', scale(PAL.green, f), 1, '3x5');
    } else {
      m.text(x + 1, y + 17, 'PASS IN', scale(PAL.cyanDim, f), 1, '3x5');
      m.text(x + 1, y + 24, iss.minutesAway + ' MIN', scale(PAL.cyan, f), 1, '3x5');
    }
    // little orbit dot tracing the zone
    const ox = x + w / 2 + Math.cos(t) * (w / 3);
    const oy = y + h / 2 + Math.sin(t * 1.3) * 8;
    m.add(Math.round(ox), Math.round(oy), scale(PAL.white, f), 220);
  }
  _mbta(m, x, y, w, h, mb, t, f) {
    m.fillRect(x + 1, y + 1, 5, 5, scale(PAL.red, f));   // red line bullet
    m.text(x + 8, y + 1, 'RED LN', scale(PAL.red, f), 1, '3x5');
    m.text(x + 1, y + 8, 'KENDALL/MIT', scale(PAL.redDim, f), 1, '3x5');
    m.text(x + 1, y + 15, mb.dest0 || 'ALEWIFE', scale(PAL.white, f), 1, '3x5');
    m.textRight(x + w - 1, y + 15, (mb.eta0 != null ? mb.eta0 + 'M' : '--'), scale(PAL.green, f), 1, '3x5');
    m.text(x + 1, y + 23, mb.dest1 || 'ASHMONT', scale(PAL.white, f), 1, '3x5');
    m.textRight(x + w - 1, y + 23, (mb.eta1 != null ? mb.eta1 + 'M' : '--'), scale(PAL.green, f), 1, '3x5');
  }
  _aqi(m, x, y, w, h, aqi, t, f) {
    const col = aqi.value <= 50 ? PAL.green : aqi.value <= 100 ? PAL.warm
      : aqi.value <= 150 ? PAL.amber : PAL.red;
    m.text(x + 1, y + 1, 'AIR QUALITY', scale(PAL.cyanDim, f), 1, '3x5');
    m.text(x + 1, y + 8, String(aqi.value), scale(col, f), 2);
    m.text(x + 1, y + 24, aqi.label, scale(col, f), 1, '3x5');
    // bar
    m.hline(x + 1, x + w - 2, y + 22, PAL.amberDim, 60);
    m.hline(x + 1, x + 1 + Math.round((w - 3) * clamp(aqi.value / 200, 0, 1)), y + 22, col, 220);
  }
  _moon(m, x, y, w, h, moon, t, f) {
    m.text(x + 1, y + 1, 'MOON', scale(PAL.white, f), 1, '3x5');
    // draw a disc lit per illumination fraction
    const cx = x + 12, cy = y + 17, r = 8;
    for (let yy = -r; yy <= r; yy++) for (let xx = -r; xx <= r; xx++) {
      if (xx * xx + yy * yy <= r * r) {
        const lit = (xx / r + 1) / 2 <= moon.illum;  // simple terminator
        m.set(cx + xx, cy + yy, lit ? scale(PAL.warm, f) : scale(PAL.amberDim, f * 0.4));
      }
    }
    m.text(x + 24, y + 12, moon.phase, scale(PAL.white, f), 1, '3x5');
    m.text(x + 24, y + 20, Math.round(moon.illum * 100) + '%', scale(PAL.warm, f), 1, '3x5');
  }
  _tide(m, x, y, w, h, tide, t, f) {
    m.text(x + 1, y + 1, 'CHARLES TIDE', scale(PAL.blue, f), 1, '3x5');
    const rising = tide.state === 'RISING';
    m.text(x + 1, y + 9, tide.state, scale(rising ? PAL.green : PAL.cyan, f), 1, '3x5');
    m.text(x + 1, y + 16, (rising ? '↑' : '↓') + ' ' + tide.next, scale(PAL.white, f), 1, '3x5');
    // animated wave
    for (let i = 0; i < w - 2; i++) {
      const yy = y + 27 + Math.round(Math.sin(i * 0.4 + t * 2) * 2);
      m.add(x + 1 + i, yy, scale(PAL.blue, f), 180);
    }
  }
}

/* ============================================================================
 * STATUS END-CAP — vertical "live" marker + scene heartbeat + wordmark
 * ========================================================================== */
class StatusEndcap {
  draw(m, x, y, w, h, data, t, dt) {
    // pulsing LIVE dot
    const p = (Math.sin(t * 3) * 0.5 + 0.5);
    m.fillCircle(x + 5, y + 4, 2, mix(PAL.redDim, PAL.red, p));
    m.text(x + 10, y + 2, 'LIVE', PAL.red, 1, '3x5');
    // vertical VESTOR ticks
    const word = 'VESTOR';
    for (let i = 0; i < word.length; i++)
      m.text(x + 2, y + 9 + i * 4, word[i], i % 2 ? PAL.amberDim : PAL.amber, 1, '3x5');
    // data freshness sparkline
    for (let i = 0; i < 8; i++) {
      const v = (Math.sin(t * 2 + i) * 0.5 + 0.5);
      const bh = 1 + Math.round(v * 5);
      m.vline(x + 30 + i * 3, y + 28 - bh, y + 28, mix(PAL.cyanDim, PAL.cyan, v));
    }
  }
}

/* ---- expose ---- */
window.SCENES = {
  SplitFlap, Marquee, drawRadar, gauge,
  FlightScene, WeatherScene, BluebikesScene, ShuttleScene,
  ClockScene, ExtrasScene, StatusEndcap,
  WMO_ICON, WMO_TEXT,
};
