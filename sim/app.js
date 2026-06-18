/* =============================================================================
 * Vestor — Simulator wiring
 * Builds the 1024x32 wall + 64x32 panel, runs the render loop, lays out the
 * three wall modes (DASHBOARD / FLIGHT TAKEOVER / MARQUEE), and binds the
 * console. Pure DOM + canvas; no framework.
 * ========================================================================== */
'use strict';

/* ---- wall + panel matrices/renderers ---------------------------------- */
const WALL_W = 1024, WALL_H = 32, PANEL_W = 64, PANEL_H = 32;
const wallM = new LEDMatrix(WALL_W, WALL_H);
const wallR = new LEDRenderer(document.getElementById('wall'), wallM,
  { pitch: 5, dot: 0.82, bloom: 0.5, scan: 0.12, seam: true, vignette: 0.16 });
const panelM = new LEDMatrix(PANEL_W, PANEL_H);
const panelR = new LEDRenderer(document.getElementById('panel'), panelM,
  { pitch: 14, dot: 0.78, bloom: 0.6, scan: 0.10, seam: true, vignette: 0.28 });

const data = new DataModel();

/* ---- scene instances --------------------------------------------------- */
const S = window.SCENES;
const sc = {
  clock: new S.ClockScene(),
  weather: new S.WeatherScene(),
  flight: new S.FlightScene(),
  bikes: new S.BluebikesScene(),
  shuttle: new S.ShuttleScene(),
  extras: new S.ExtrasScene(),
  status: new S.StatusEndcap(),
};
const panelFlight = new S.FlightScene();        // dedicated for single panel
/* takeover departure-board split-flaps — IATA codes + full city names flip
 * dramatically each time the hero flight rotates (the gate-board "clack"). */
const tkOrigCode = new S.SplitFlap(3, 20);
const tkDestCode = new S.SplitFlap(3, 20);
const tkOrigCity = new S.SplitFlap(11, 16);
const tkDestCity = new S.SplitFlap(11, 16);
const marquee = new S.Marquee(26);
let takeoverHex = null;

/* ---- wall zone map (all boundaries land on 64px panel seams) ----------- */
const ZONES = [
  { id: 'clock',   x: 0,    w: 128, name: 'CLOCK · DATE',    src: 'system',    color: '#ffb000', desc: 'Local time, day & date with a sweeping seconds bar.' },
  { id: 'weather', x: 128,  w: 128, name: 'WEATHER °C/°F',   src: 'Open-Meteo',color: '#5a96ff', desc: 'Condition, dual-unit temp, hi/lo, humidity, wind vector.' },
  { id: 'flight',  x: 256,  w: 320, name: 'FLIGHTS · RADAR', src: 'ADS-B',     color: '#00e5ff', desc: 'Live radar sweep, split-flap callsign, route arc, alt/spd gauges.' },
  { id: 'bikes',   x: 576,  w: 128, name: 'BLUEBIKES',       src: 'GBFS',      color: '#00e5ff', desc: 'Pacific St: classic vs e-bikes vs free docks.' },
  { id: 'shuttle', x: 704,  w: 128, name: 'SHUTTLES',         src: 'Passio + TransLoc', color: '#ffb000', desc: 'MIT Tech · Tech NW · SafeRide + BU Hyatt→GSU, live arrivals.' },
  { id: 'extras',  x: 832,  w: 128, name: 'EXTRAS',          src: 'mixed',     color: '#aa78ff', desc: 'Rotates: ISS pass · MBTA Red · AQI · Moon · Charles tide.' },
  { id: 'status',  x: 960,  w: 64,  name: 'STATUS',          src: 'system',    color: '#ff3c32', desc: 'Live heartbeat, wordmark, data-freshness sparkline.' },
];

/* ---- state ------------------------------------------------------------- */
let mode = 'dashboard';
let autoPan = true, panX = 0, fitMode = 'actual';

/* ============================ render loop =============================== */
let last = performance.now(), fpsAcc = 0, fpsN = 0, fpsShown = 0;
function frame(now) {
  let dt = (now - last) / 1000; last = now;
  if (dt > 0.1) dt = 0.1;                 // clamp after tab-switch
  const t = now / 1000;
  data.tick(dt);

  /* ----- WALL ----- */
  wallM.clear(PAL.ink);
  if (mode === 'dashboard') drawDashboard(t, dt);
  else if (mode === 'takeover') drawTakeover(t, dt);
  else drawMarquee(t, dt);
  wallR.render();

  /* ----- PANEL (always flights-only Phase-0 view) ----- */
  panelM.clear(PAL.ink);
  panelFlight.draw(panelM, 0, 0, PANEL_W, PANEL_H, data, t, dt);
  panelR.render();

  /* ----- auto-pan the wall viewport like a ticker ----- */
  if (autoPan && fitMode === 'actual') {
    const vp = document.getElementById('wall-viewport');
    const max = vp.scrollWidth - vp.clientWidth;
    if (max > 0) {
      panX += dt * 60;
      // triangle wave: pan right to the end, then back, like a transit ticker
      vp.scrollLeft = ((panX / max) % 2 < 1) ? (panX % max) : (max - (panX % max));
    }
  }

  /* ----- HUD pills ----- */
  fpsAcc += dt; fpsN++;
  if (fpsAcc >= 0.5) { fpsShown = Math.round(fpsN / fpsAcc); fpsAcc = 0; fpsN = 0;
    document.getElementById('pill-fps').textContent = fpsShown + ' FPS'; }
  const d = data.now;
  document.getElementById('pill-clock').textContent =
    [d.getHours(), d.getMinutes(), d.getSeconds()].map(n => String(n).padStart(2, '0')).join(':');

  requestAnimationFrame(frame);
}

/* ============================ DASHBOARD ================================= */
function drawDashboard(t, dt) {
  sc.clock.draw(wallM, 0, 0, 128, 32, data, t, dt);
  sc.weather.draw(wallM, 128, 0, 128, 32, data, t, dt);
  sc.flight.draw(wallM, 256, 0, 320, 32, data, t, dt);
  sc.bikes.draw(wallM, 576, 0, 128, 32, data, t, dt);
  sc.shuttle.draw(wallM, 704, 0, 128, 32, data, t, dt);
  sc.extras.draw(wallM, 832, 0, 128, 32, data, t, dt);
  sc.status.draw(wallM, 960, 0, 64, 32, data, t, dt);
}

/* ============================ FLIGHT TAKEOVER ========================== */
/* The whole 1024px ribbon becomes one cinematic boarding pass, read L→R:
 *   IDENTITY (who)  ·  JOURNEY (where)  ·  STATUS (how)
 * Big airline emblem + brand wordmark, a split-flap origin→dest with a plane
 * crossing the route, then telemetry + radar + nearby traffic on the right.   */
function drawTakeover(t, dt) {
  const f = data.heroFlight;
  const rjust = (s, n) => { s = String(s).slice(0, n); return ' '.repeat(n - s.length) + s; };

  /* ----- graceful "no contact" state ----- */
  if (!f) {
    S.drawRadar(wallM, 512, 15, 13, t, data.flights.slice(0, 8).map(x => ({
      a: (x.track || 0) * Math.PI / 180 - Math.PI / 2,
      d: clamp((x.distance || 5) / 25, 0.12, 0.95), color: PAL.green,
    })), PAL.cyan, PAL.cyanDim);
    wallM.textCenter(512, 26, 'SCANNING THE CAMBRIDGE SKY', PAL.amberDim, 1, '3x5');
    return;
  }

  const A = airlineFor(f.callsign);
  // hero changed → reload the split-flaps so the whole board "clacks" over
  if (f.hex !== takeoverHex) {
    takeoverHex = f.hex;
    tkOrigCode.set(f.origin || '???'); tkDestCode.set(f.dest || '???');
    tkOrigCity.set(f.originCity || f.origin || '');
    tkDestCity.set(rjust(f.destCity || f.dest || '', 11));   // right-justified field
  }
  tkOrigCode.update(dt); tkDestCode.update(dt);
  tkOrigCity.update(dt); tkDestCity.update(dt);

  /* ============ PANEL 1 · CARRIER IDENTITY (x 10…330) ============ */
  // REAL full-colour carrier logo, fit big into rows 3–23 (the wow moment).
  const [lw] = A.iata ? fitLogoBox(wallM, A.iata, 12, 3, 300, 20, 'left', 'center')
                      : [0, 0];
  if (lw === 0) wallM.text(12, 6, A.name, A.color, 2);     // wordmark fallback
  // sub-line: callsign (white) · type (brand-tinted) · registration (dim)
  let sx = wallM.text(12, 25, f.callsign || f.reg || '', PAL.white, 1, '5x7');
  sx = wallM.text(sx + 6, 26, f.type || '', scale(A.color, 0.9), 1, '3x5');
  if (f.reg && f.callsign) wallM.text(sx + 6, 26, f.reg, PAL.cyanDim, 1, '3x5');

  wallM.vline(338, 3, 28, PAL.cyanDim, 55);                // divider

  /* ============ PANEL 2 · THE JOURNEY (x 356…752) ============ */
  const jx0 = 356, jx1 = 752, jcx = (jx0 + jx1) / 2;
  const dir = f._arriving ? 'ARRIVING' : 'DEPARTING';
  const dirCol = f._arriving ? PAL.green : PAL.warm;
  wallM.textCenter(jcx, 2, dir + '  ·  ' + (f.distance ? f.distance.toFixed(1) : '--') + ' MI',
    dirCol, 1, '3x5');

  // big IATA codes flanking (split-flap, scale 2)
  tkOrigCode.draw(wallM, jx0, 9, PAL.cyan, 2);            // rows 9–22
  tkDestCode.draw(wallM, jx1 - 34, 9, PAL.cyan, 2);
  // route line with travelling plane between the codes
  const rlx0 = jx0 + 42, rlx1 = jx1 - 42, rly = 15;
  wallM.hline(rlx0, rlx1, rly, scale(PAL.cyan, 0.32), 100);
  wallM.fillCircle(rlx0, rly, 1, PAL.cyan);
  wallM.fillCircle(rlx1, rly, 1, scale(PAL.cyan, 0.55));
  const frac = clamp((f.distance || 0) / 25, 0, 1);
  const prog = f._arriving ? (1 - frac) : frac;
  const px = Math.round(rlx0 + (rlx1 - rlx0) * prog);
  for (let k = 1; k <= 6; k++)                            // jet-wash trail behind nose
    wallM.add(px - 3 - k * 3, rly, A.color, 90 * (1 - k / 7));
  wallM.icon(px - 3, rly - 3, 'plane', A.color);         // 7×7, nose toward dest
  // full city names beneath (split-flap), left- and right-justified
  tkOrigCity.draw(wallM, jx0, 25, scale(PAL.cyan, 0.85), 1, '3x5');
  tkDestCity.draw(wallM, jx1 - 43, 25, scale(PAL.cyan, 0.85), 1, '3x5');

  wallM.vline(760, 3, 28, PAL.cyanDim, 55);                // divider

  /* ============ PANEL 3 · STATUS (x 768…1023) ============ */
  // vertical altitude gauge, tinted to the carrier
  const af = clamp((f.alt || 0) / 42000, 0, 1);
  wallM.vline(768, 3, 29, scale(PAL.green, 0.18), 90);
  wallM.vline(768, 29 - Math.round(af * 26), 29, scale(A.color, 0.95), 255);
  // big telemetry: flight level over ground speed
  wallM.text(776, 3, 'FL' + (f.alt ? Math.round(f.alt / 100) : '--'), PAL.green, 2);  // rows 3–16
  wallM.text(776, 17, (f.gs ? Math.round(f.gs) : '--') + 'KT', PAL.warm, 2);          // rows 17–30
  // vertical trend + nearby traffic column
  const climbing = f.vspeed > 64, descending = f.vspeed < -64;
  const vc = climbing ? PAL.green : descending ? PAL.red : PAL.amberDim;
  wallM.text(858, 2, 'V/S', PAL.cyanDim, 1, '3x5');
  wallM.text(858, 8, climbing ? '↑' + Math.round(f.vspeed)
    : descending ? '↓' + Math.abs(Math.round(f.vspeed)) : 'LEVEL', vc, 1, '3x5');
  wallM.text(858, 16, 'IN RANGE', PAL.cyanDim, 1, '3x5');
  data.flights.filter(x => x !== f).slice(0, 2).forEach((x, i) => {
    const yy = 21 + i * 6;
    wallM.text(858, yy, (x.callsign || x.reg || '').slice(0, 7), scale(airlineFor(x.callsign).color, 0.95), 1, '3x5');
    wallM.textRight(980, yy, (x.alt ? 'FL' + Math.round(x.alt / 100) : '--'), PAL.green, 1, '3x5');
  });
  // radar scope in the far-right corner
  S.drawRadar(wallM, 1000, 13, 12, t, data.flights.slice(0, 8).map(x => ({
    a: (x.track || 0) * Math.PI / 180 - Math.PI / 2,
    d: clamp((x.distance || 5) / 25, 0.12, 0.95),
    color: x._hero ? A.color : PAL.green,
  })), PAL.cyan, PAL.cyanDim);
  wallM.textCenter(1000, 28, data.flights.length + ' TRK', PAL.cyanDim, 1, '3x5');
}

/* ============================ MARQUEE ================================== */
function drawMarquee(t, dt) {
  // static left block
  wallM.fillRect(0, 0, 60, 32, scale(PAL.ink, 1));
  const p = (Math.sin(t * 3) * 0.5 + 0.5);
  wallM.fillCircle(8, 8, 2, mix(PAL.redDim, PAL.red, p));
  wallM.text(14, 6, 'LIVE', PAL.red, 1, '3x5');
  wallM.text(6, 16, 'VESTOR', PAL.amber, 1, '3x5');
  wallM.vline(62, 2, 29, PAL.amberDim, 80);

  const wx = data.weather, b = data.bikes, s = data.shuttle, f = data.heroFlight;
  const bu = data.buShuttle || { hyatt: [] };
  const tc = Math.round(wx.tempC), tf = Math.round(wx.tempC * 9 / 5 + 32);
  const eta = a => (a && a[0] != null ? a[0] + 'M' : '--');
  const txt =
    tc + '°C / ' + tf + '°F ' + S.WMO_TEXT(wx.code) + '    ' +
    (f ? (f.callsign + ' ' + f.origin + '>' + f.dest + ' FL' + Math.round(f.alt / 100)) : 'NO TRAFFIC') + '    ' +
    'BIKES ' + b.classic + ' CLASSIC +' + b.ebikes + 'E   ' +
    'TECH ' + eta(s.tech) + '  NW ' + eta(s.techNW) + '  SAFERIDE ' + eta(s.saferide) +
    '  BU HYATT ' + eta(bu.hyatt) + '    ' +
    'ISS ' + (data.extras.iss.overhead ? 'OVERHEAD' : data.extras.iss.minutesAway + 'M') + '    ' +
    'RED LN ' + data.extras.mbta.eta0 + 'M     +++     ';
  marquee.draw(wallM, 66, 0, WALL_W - 66, 12, txt, PAL.amber, 2, dt);
}

/* ============================ controls ================================= */
function bindSeg(id, attr, fn) {
  const seg = document.getElementById(id);
  seg.querySelectorAll('button').forEach(btn => btn.addEventListener('click', () => {
    seg.querySelectorAll('button').forEach(b => b.classList.remove('on'));
    btn.classList.add('on'); fn(btn.getAttribute(attr));
  }));
}
bindSeg('seg-mode', 'data-mode', v => {
  mode = v; document.getElementById('pill-mode').textContent = v.toUpperCase();
});
bindSeg('seg-src', 'data-src', async v => {
  const pill = document.getElementById('pill-src');
  if (v === 'live') {
    pill.textContent = 'LIVE…'; pill.classList.add('on-live');
    await data.fetchLive();
    const anyLive = data.weather._live || data.bikes._live || data._flightsLive;
    pill.textContent = anyLive ? 'LIVE' : 'LIVE (SIM FALLBACK)';
    // keep refreshing live data every 60s
    if (!window._liveTimer) window._liveTimer = setInterval(() => data.fetchLive(), 60000);
  } else {
    pill.textContent = 'SIM'; pill.classList.remove('on-live');
    if (window._liveTimer) { clearInterval(window._liveTimer); window._liveTimer = null; }
    data.weather._live = false; data.bikes._live = false;
    data._flightsLive = false; data._shuttleLive = false;
    data._seed();   // restore synthetic flight pool
  }
});
document.getElementById('sl-bri').addEventListener('input', e => {
  const v = +e.target.value; document.getElementById('lbl-bri').textContent = v;
  wallR.brightness = v / 100 * 1.3; panelR.brightness = v / 100 * 1.3;
});
bindSeg('seg-fit', 'data-fit', v => {
  fitMode = v;
  const c = document.getElementById('wall');
  if (v === 'fit') c.classList.add('fit'); else c.classList.remove('fit');
});
bindSeg('seg-pan', 'data-pan', v => { autoPan = (v === 'on'); });

/* ============================ chrome build ============================= */
function buildRuler() {
  const r = document.getElementById('ruler');
  // ticks every 64px (panel) → label inches every 4 panels
  const inchesPerPanel = 201.6 / 16;
  for (let i = 0; i <= 16; i++) {
    const tick = document.createElement('div');
    tick.className = 'tick' + (i % 4 === 0 ? ' major' : '');
    tick.style.left = (i / 16 * 100) + '%';
    if (i % 4 === 0) {
      const s = document.createElement('span');
      s.textContent = Math.round(i * inchesPerPanel) + '"';
      tick.appendChild(s);
    }
    r.appendChild(tick);
  }
}
function buildSeamguide() {
  const g = document.getElementById('seamguide');
  for (let i = 0; i < 16; i++) {
    const seg = document.createElement('div');
    seg.style.flex = '1';
    seg.style.borderRight = i < 15 ? '1px solid #2a3344' : 'none';
    seg.style.fontFamily = 'Silkscreen';
    seg.style.fontSize = '6px';
    seg.style.color = '#465064';
    seg.style.textAlign = 'center';
    seg.style.lineHeight = '10px';
    seg.textContent = 'P' + (i + 1);
    g.appendChild(seg);
  }
}
function buildLegend() {
  const L = document.getElementById('legend');
  for (const z of ZONES) {
    const el = document.createElement('div');
    el.className = 'z'; el.style.borderTopColor = z.color;
    el.innerHTML = `<div class="zh">${z.name}</div>
      <div class="zr">px ${z.x}–${z.x + z.w - 1} · ${z.w / 64}p · ${z.src}</div>
      <div class="zd">${z.desc}</div>`;
    L.appendChild(el);
  }
}
function buildSources() {
  const SRC = [
    { t: 'ADS-B FLIGHTS', s: 'adsb.lol · hexdb.io · adsbdb', d: 'Live aircraft within 25 nm of 540 Memorial Dr; enriched with route + type. No key.', e: 'api.adsb.lol/v2/point/42.354/-71.107/25' },
    { t: 'WEATHER °C/°F', s: 'Open-Meteo', d: 'Current temp, apparent, humidity, wind, daily hi/lo, WMO code. Free, no key, CORS-OK.', e: 'api.open-meteo.com/v1/forecast' },
    { t: 'BLUEBIKES', s: 'GBFS 2.3 (Lyft)', d: 'Pacific St @ Purrington — classic vs e-bike counts, free docks. 60s TTL.', e: 'gbfs.lyft.com/gbfs/2.3/bos/en/station_status.json' },
    { t: 'MIT SHUTTLE', s: 'Passio GTFS-realtime', d: 'Tech + Tech NW at Grad Junction (180113); SafeRide Campus at W98 @ Vassar (3813), one shared protobuf feed.', e: 'passio3.com/mit/passioTransit/gtfs/realtime/tripUpdates' },
    { t: 'BU HYATT SHUTTLE', s: 'TransLoc3 (CORS-OK)', d: 'BU "Hyatt" route 5: Amesbury @ Vassar (stop 21, by the wall) → BU GSU. Live vehicle ETAs, fetched in-browser.', e: 'bu.transloc.com/Services/JSONPRelay.svc/GetStopArrivalTimes' },
    { t: 'MBTA RED LINE', s: 'MBTA v3 API', d: 'Predicted arrivals at Kendall/MIT (place-knncl) toward Alewife / Ashmont.', e: 'api-v3.mbta.com/predictions?stop=place-knncl' },
    { t: 'ISS OVERHEAD', s: 'wheretheiss.at', d: 'Sub-satellite point + pass timing relative to Cambridge for "look up!" alerts.', e: 'api.wheretheiss.at/v1/satellites/25544' },
    { t: 'AIR QUALITY', s: 'Open-Meteo AQ', d: 'US AQI / PM2.5 for the Cambridge grid cell, color-coded GOOD→HAZARD.', e: 'air-quality-api.open-meteo.com/v1/air-quality' },
    { t: 'CHARLES TIDE', s: 'NOAA CO-OPS', d: 'Boston station 8443970 rising/falling state + next high/low time.', e: 'api.tidesandcurrents.noaa.gov' },
  ];
  const g = document.getElementById('src-grid');
  for (const x of SRC) {
    const el = document.createElement('div'); el.className = 'src';
    el.innerHTML = `<div class="st">${x.t}</div><div class="ss">${x.s}</div>
      <div class="sd">${x.d}</div><code class="se">${x.e}</code>`;
    g.appendChild(el);
  }
}

buildRuler(); buildSeamguide(); buildLegend(); buildSources();
requestAnimationFrame(frame);
