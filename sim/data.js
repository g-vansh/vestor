/* =============================================================================
 * Vestor — Simulation data model
 * -----------------------------------------------------------------------------
 * Holds the live-ish state the scenes render. Two modes:
 *   SIM  — high-fidelity synthetic data that animates believably (default;
 *          works offline, deterministic-ish, great for the showcase).
 *   LIVE — opportunistically overlays REAL data from CORS-friendly public APIs
 *          (Open-Meteo weather in °C, Bluebikes GBFS). Falls back to SIM on any
 *          failure so the wall never goes dark. The Pi's Python clients fetch
 *          the same shapes from the same endpoints (+ Passio GTFS-rt, ADS-B).
 *
 * Geo anchor: 540 Memorial Drive, Cambridge MA  → 42.354, -71.107
 * Bluebikes station: "MIT Pacific St at Purrington St"
 *   id f835141c-0de8-11e7-991c-3863bb43a7d0  (cap 19)
 * ========================================================================== */
'use strict';

const LAT = 42.354, LON = -71.107;

function rnd(a, b) { return a + Math.random() * (b - a); }
function pick(arr) { return arr[(Math.random() * arr.length) | 0]; }

/* Realistic BOS-area flight pool (callsign, type, origin/dest + cities). */
const FLIGHT_POOL = [
  { callsign: 'JBU1124', reg: 'N2044J', type: 'A320', origin: 'BOS', dest: 'MCO', originCity: 'BOSTON', destCity: 'ORLANDO' },
  { callsign: 'AAL519', reg: 'N9026A', type: 'B738', origin: 'BOS', dest: 'DCA', originCity: 'BOSTON', destCity: 'WASHINGTON' },
  { callsign: 'DAL1387', reg: 'N3743H', type: 'A321', origin: 'BOS', dest: 'ATL', originCity: 'BOSTON', destCity: 'ATLANTA' },
  { callsign: 'UAL624', reg: 'N77296', type: 'B752', origin: 'SFO', dest: 'BOS', originCity: 'SAN FRAN', destCity: 'BOSTON' },
  { callsign: 'JBU616', reg: 'N304JB', type: 'A20N', origin: 'SJU', dest: 'BOS', originCity: 'SAN JUAN', destCity: 'BOSTON' },
  { callsign: 'SWA2299', reg: 'N8642E', type: 'B737', origin: 'BWI', dest: 'BOS', originCity: 'BALTIMORE', destCity: 'BOSTON' },
  { callsign: 'BAW238', reg: 'GZBKA', type: 'B789', origin: 'LHR', dest: 'BOS', originCity: 'LONDON', destCity: 'BOSTON' },
  { callsign: 'AFR334', reg: 'FHRBG', type: 'A359', origin: 'CDG', dest: 'BOS', originCity: 'PARIS', destCity: 'BOSTON' },
  { callsign: 'ACA7621', reg: 'CGFEW', type: 'E75L', origin: 'YUL', dest: 'BOS', originCity: 'MONTREAL', destCity: 'BOSTON' },
  { callsign: 'JBU1287', reg: 'N967JT', type: 'A321', origin: 'BOS', dest: 'LAX', originCity: 'BOSTON', destCity: 'LOS ANGELES' },
  { callsign: 'DAL967', reg: 'N825DN', type: 'A332', origin: 'BOS', dest: 'LAX', originCity: 'BOSTON', destCity: 'LOS ANGELES' },
  { callsign: 'FFT1543', reg: 'N705FR', type: 'A20N', origin: 'BOS', dest: 'DEN', originCity: 'BOSTON', destCity: 'DENVER' },
  { callsign: 'KAP4', reg: 'N4221X', type: 'C402', origin: 'BOS', dest: 'HYA', originCity: 'BOSTON', destCity: 'HYANNIS' },
  { callsign: 'EJA529', reg: 'N529QS', type: 'C68A', origin: 'BED', dest: 'TEB', originCity: 'BEDFORD', destCity: 'TETERBORO' },
];

class DataModel {
  constructor() {
    this.now = new Date();
    this.flights = [];
    this.heroFlight = null;
    this._heroTimer = 0;
    this._spawnTimer = 0;
    this._seed();
    this.weather = {
      tempC: 21.5, code: 1, isDay: true, hiC: 24, loC: 14,
      humidity: 58, windKph: 14, windDir: 250, feelsC: 21, _live: false,
    };
    this.bikes = { classic: 12, ebikes: 3, docks: 4, capacity: 19, name: 'PACIFIC ST', _live: false };
    // MIT Passio shuttles at the wall: Tech + Tech2 (Grad Junction West),
    // SafeRide (evening) at W98 @ Vassar St. BU "Hyatt" (TransLoc) departs
    // Amesbury St @ Vassar and crosses the Charles to BU's GSU.
    this.shuttle = { tech: [4, 16, 28], techNW: [9, 24], saferide: [7, 22], stop: 'GRAD JUNCTION', _live: false };
    this.buShuttle = { hyatt: [3, 18], stop: 'AMESBURY @ VASSAR', dest: 'BU · GSU', vehicles: 1, _live: false };
    this.extras = {
      iss: { overhead: false, minutesAway: 38, lat: 12, lon: -40 },
      mbta: { dest0: 'ALEWIFE', eta0: 3, dest1: 'ASHMONT', eta1: 7 },
      aqi: { value: 32, label: 'GOOD' },
      moon: { phase: 'WAX GIB', illum: 0.78 },
      tide: { state: 'RISING', next: '4:12P' },
    };
    this._wxTimer = 0; this._bikeTimer = 0; this._shTimer = 0; this._exTimer = 0;
  }

  _seed() {
    const n = 6 + ((Math.random() * 4) | 0);
    const pool = FLIGHT_POOL.slice().sort(() => Math.random() - 0.5).slice(0, n);
    this.flights = pool.map(f => this._instance(f));
    this._pickHero();
  }
  _instance(base) {
    const arriving = base.dest === 'BOS';
    return Object.assign({}, base, {
      hex: Math.random().toString(16).slice(2, 8),
      typeDesc: base.type,
      alt: arriving ? rnd(4000, 18000) : rnd(8000, 37000),
      gs: rnd(230, 520),
      track: rnd(0, 360),
      vspeed: arriving ? rnd(-1400, -200) : rnd(200, 1800),
      distance: rnd(2, 24),
      _arriving: arriving,
      _phase: rnd(0, Math.PI * 2),
    });
  }
  _pickHero() {
    if (!this.flights.length) { this.heroFlight = null; return; }
    // hero = nearest flight, flagged for amber radar blip
    this.flights.forEach(f => f._hero = false);
    const sorted = this.flights.slice().sort((a, b) => a.distance - b.distance);
    this.heroFlight = sorted[0];
    this.heroFlight._hero = true;
  }

  tick(dt) {
    this.now = new Date();

    /* flights: advance positions (SIM only — live positions come from adsb.lol
       on the 60s refresh and must not be teleported by the synthetic model) */
    if (this._flightsLive) {
      // keep radar phase + a touch of track rotation alive between live polls
      for (const f of this.flights) { f._phase += dt * 0.3; }
      this._heroTimer += dt;
      if (this._heroTimer > 10) { this._heroTimer = 0; this._rotateHero(); }
    } else {
      for (const f of this.flights) {
        f._phase += dt * 0.3;
        f.distance += (f._arriving ? -1 : 1) * (f.gs / 3600) * dt * 0.6;
        f.alt += (f.vspeed / 60) * dt;
        f.track = (f.track + dt * 2) % 360;
        f.gs += Math.sin(f._phase) * 4 * dt;
        if (f._arriving && f.distance < 1.5) { f.distance = rnd(20, 26); f.alt = rnd(12000, 20000); }
        if (!f._arriving && f.distance > 25) { f.distance = rnd(1.5, 3); f.alt = rnd(6000, 11000); }
        f.alt = clamp(f.alt, 1500, 39000);
      }
      this._heroTimer += dt;
      if (this._heroTimer > 8) { this._heroTimer = 0; this._rotateHero(); }
      this._spawnTimer += dt;
      if (this._spawnTimer > 14) {
        this._spawnTimer = 0;
        if (this.flights.length < 9 && Math.random() < 0.6) {
          this.flights.push(this._instance(pick(FLIGHT_POOL)));
        } else if (this.flights.length > 5 && Math.random() < 0.4) {
          const i = (Math.random() * this.flights.length) | 0;
          if (!this.flights[i]._hero) this.flights.splice(i, 1);
        }
      }
    }

    /* weather: gentle drift (SIM) */
    if (!this.weather._live) {
      this._wxTimer += dt;
      if (this._wxTimer > 4) {
        this._wxTimer = 0;
        this.weather.tempC += rnd(-0.3, 0.3);
        this.weather.humidity = clamp(this.weather.humidity + rnd(-2, 2), 30, 95);
        this.weather.windKph = clamp(this.weather.windKph + rnd(-1.5, 1.5), 2, 38);
        this.weather.windDir = (this.weather.windDir + rnd(-8, 8) + 360) % 360;
        this.weather.feelsC = this.weather.tempC - this.weather.windKph * 0.05;
      }
      const h = this.now.getHours();
      this.weather.isDay = h >= 5 && h < 20;
    }

    /* bikes: occasional check-in/out (SIM) */
    if (!this.bikes._live) {
      this._bikeTimer += dt;
      if (this._bikeTimer > 5) {
        this._bikeTimer = 0;
        const b = this.bikes;
        if (Math.random() < 0.5 && b.classic + b.ebikes > 0) {
          if (Math.random() < 0.25 && b.ebikes > 0) b.ebikes--; else if (b.classic > 0) b.classic--;
          b.docks++;
        } else if (b.docks > 0) {
          if (Math.random() < 0.25) b.ebikes++; else b.classic++;
          b.docks--;
        }
        b.classic = clamp(b.classic, 0, b.capacity);
        b.ebikes = clamp(b.ebikes, 0, b.capacity);
        b.docks = clamp(b.capacity - b.classic - b.ebikes, 0, b.capacity);
      }
    }

    /* shuttle: count down ETAs, recycle (SIM) */
    this._shTimer += dt;
    if (this._shTimer > 1) {
      this._shTimer = 0;
      const tick = (arr, gapMin, gapMax) => {
        if (arr.length && Math.random() < 0.5) {
          arr[0] = Math.max(0, arr[0] - 1);
          if (arr[0] === 0) {
            arr.shift();
            arr.push(arr.length ? arr[arr.length - 1] + ((Math.random() * (gapMax - gapMin) + gapMin) | 0) : gapMin);
          }
        }
      };
      if (!this.shuttle._live) {
        tick(this.shuttle.tech, 8, 18);
        tick(this.shuttle.techNW, 10, 22);
        tick(this.shuttle.saferide, 9, 20);
      }
      if (!this.buShuttle._live) tick(this.buShuttle.hyatt, 12, 24);   // ~15-20 min headway
    }

    /* extras: ISS pass, MBTA, etc. (SIM) */
    this._exTimer += dt;
    if (this._exTimer > 2) {
      this._exTimer = 0;
      const e = this.extras;
      e.iss.minutesAway = Math.max(0, e.iss.minutesAway - 1);
      if (e.iss.minutesAway === 0) { e.iss.overhead = true; }
      if (e.iss.overhead && Math.random() < 0.2) { e.iss.overhead = false; e.iss.minutesAway = 88; }
      e.mbta.eta0 = Math.max(0, e.mbta.eta0 - (Math.random() < 0.5 ? 1 : 0));
      if (e.mbta.eta0 === 0) e.mbta.eta0 = (Math.random() * 6 + 3) | 0;
      e.mbta.eta1 = Math.max(e.mbta.eta0 + 1, e.mbta.eta1 - (Math.random() < 0.4 ? 1 : 0));
    }
  }
  _rotateHero() {
    if (this.flights.length < 2) return this._pickHero();
    this.flights.forEach(f => f._hero = false);
    // weight toward closer + more interesting (international/arriving)
    const idx = (Math.random() * Math.min(this.flights.length, 5)) | 0;
    const sorted = this.flights.slice().sort((a, b) => a.distance - b.distance);
    this.heroFlight = sorted[idx];
    this.heroFlight._hero = true;
  }

  /* ---- optional LIVE overlays (best-effort, CORS-friendly) ------------- */
  async fetchLive() {
    await Promise.allSettled([
      this._liveWeather(), this._liveBikes(),
      this._liveFlights(), this._liveShuttle(), this._liveBuHyatt(),
    ]);
  }
  async _liveWeather() {
    try {
      const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + LAT + '&longitude=' + LON +
        '&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m,wind_direction_10m' +
        '&daily=temperature_2m_max,temperature_2m_min&timezone=America/New_York';
      const r = await fetch(url);
      const j = await r.json();
      const c = j.current;
      this.weather = {
        tempC: c.temperature_2m, code: c.weather_code, isDay: !!c.is_day,
        hiC: j.daily.temperature_2m_max[0], loC: j.daily.temperature_2m_min[0],
        humidity: c.relative_humidity_2m, windKph: c.wind_speed_10m,
        windDir: c.wind_direction_10m, feelsC: c.apparent_temperature, _live: true,
      };
    } catch (e) { /* keep SIM */ }
  }
  async _liveBikes() {
    try {
      const r = await fetch('https://gbfs.lyft.com/gbfs/2.3/bos/en/station_status.json');
      const j = await r.json();
      const id = 'f835141c-0de8-11e7-991c-3863bb43a7d0';
      const s = j.data.stations.find(x => x.station_id === id);
      if (s) {
        const e = s.num_ebikes_available != null ? s.num_ebikes_available :
          (s.vehicle_types_available ? (s.vehicle_types_available.find(v => v.vehicle_type_id !== '1') || {}).count || 0 : 0);
        this.bikes = {
          classic: Math.max(0, s.num_bikes_available - e), ebikes: e,
          docks: s.num_docks_available, capacity: s.num_bikes_available + s.num_docks_available,
          name: 'PACIFIC ST', _live: true,
        };
      }
    } catch (e) { /* keep SIM */ }
  }

  /* ---- live flights: airplanes.live point search + hexdb enrichment ----
     airplanes.live is the CORS-friendly readsb mirror (adsb.lol, used by the
     Python/Pi client, omits CORS headers so the browser blocks it). Identical
     'ac' schema, so only the base URL differs between sim and Pi. */
  async _liveFlights() {
    try {
      const r = await fetch('https://api.airplanes.live/v2/point/' + LAT + '/' + LON + '/60');
      const j = await r.json();
      const ac = (j.ac || j.aircraft || []).filter(a => a.flight && a.flight.trim());
      if (!ac.length) return;
      const mapped = ac.map(a => {
        const ground = a.alt_baro === 'ground';
        const lat = a.lat != null ? a.lat : LAT, lon = a.lon != null ? a.lon : LON;
        return {
          callsign: a.flight.trim(), reg: a.r || '', type: a.t || '', typeDesc: a.t || '',
          hex: a.hex || '', origin: '', dest: '', originCity: '', destCity: '',
          alt: ground ? 0 : (+a.alt_baro || 0), gs: +a.gs || 0,
          track: a.track != null ? +a.track : (+a.dir || 0),
          vspeed: +(a.baro_rate != null ? a.baro_rate : a.geom_rate) || 0,
          distance: haversineMi(LAT, LON, lat, lon),
          _arriving: false, _phase: Math.random() * Math.PI * 2, _ground: ground,
        };
      }).filter(f => !f._ground).sort((a, b) => a.distance - b.distance).slice(0, 9);
      if (!mapped.length) return;
      // enrich the nearest few with route + airport cities (cached)
      await Promise.allSettled(mapped.slice(0, 6).map(f => this._enrichFlight(f)));
      this.flights = mapped;
      this._pickHero();
      this._flightsLive = true;
    } catch (e) { /* keep SIM */ }
  }
  async _enrichFlight(f) {
    this._enrichCache = this._enrichCache || { rt: {}, ap: {} };
    const C = this._enrichCache;
    try {
      if (C.rt[f.callsign] === undefined) {
        const rr = await fetch('https://hexdb.io/api/v1/route/icao/' + f.callsign);
        C.rt[f.callsign] = rr.ok ? ((await rr.json()).route || null) : null;
      }
      const route = C.rt[f.callsign];
      if (route && route.indexOf('-') > -1) {
        const [o, d] = route.split('-');
        const ao = await this._airport(o.trim()), ad = await this._airport(d.trim());
        if (ao) { f.origin = ao.iata || o.trim(); f.originCity = ao.city; }
        if (ad) { f.dest = ad.iata || d.trim(); f.destCity = ad.city; }
      }
    } catch (e) { /* leave route blank */ }
  }
  async _airport(icao) {
    const C = (this._enrichCache = this._enrichCache || { rt: {}, ap: {} }).ap;
    if (C[icao] !== undefined) return C[icao];
    try {
      const r = await fetch('https://hexdb.io/api/v1/airport/icao/' + icao);
      if (!r.ok) { C[icao] = null; return null; }
      const j = await r.json();
      const city = (j.airport || '').replace(/ (International|Regional|Municipal)? ?(Airport|Intl)$/i, '').trim()
        || j.region_name || j.iata || icao;
      C[icao] = { iata: j.iata || icao, city: city.toUpperCase() };
      return C[icao];
    } catch (e) { C[icao] = null; return null; }
  }

  /* ---- live shuttle: Passio GTFS-realtime TripUpdates at Grad Junction West ---- */
  async _liveShuttle() {
    try {
      // GTFS-rt is protobuf; the browser sim reads the JSON-friendly subset via
      // the same host's vehicle ETA is unavailable, so we keep SIM unless the
      // protobuf decoder is present. (Python client handles the real protobuf.)
      if (!window.GtfsRt) return;   // optional decoder hook; absent -> keep SIM
      const arr = await window.GtfsRt.shuttleArrivals();
      if (arr) { this.shuttle = Object.assign({ stop: 'GRAD JUNCTION' }, arr); this._shuttleLive = true; }
    } catch (e) { /* keep SIM */ }
  }

  /* ---- live BU "Hyatt" shuttle via TransLoc (CORS-OK JSON relay) --------
     Unlike the MIT Passio feed (protobuf, no browser decoder), bu.transloc.com
     sends Access-Control-Allow-Origin:* and plain JSON, so the browser sim can
     show REAL BU arrivals. Route 5 = "Hyatt"; stop 21 = Amesbury St @ Vassar. */
  async _liveBuHyatt() {
    try {
      const base = 'https://bu.transloc.com/Services/JSONPRelay.svc';
      const key = '8882812681', ROUTE = 5, STOP = 21;
      const [veh, rows] = await Promise.all([
        fetch(base + '/GetMapVehiclePoints?APIKey=' + key + '&routeIDs=' + ROUTE).then(r => r.json()).catch(() => []),
        fetch(base + '/GetStopArrivalTimes?APIKey=' + key + '&routeIDs=' + ROUTE).then(r => r.json()),
      ]);
      const vehicles = (veh || []).filter(v => v.RouteID === ROUTE).length;
      const mins = [];
      for (const row of (rows || [])) {
        if (row.StopId !== STOP) continue;
        for (const tm of (row.Times || [])) {
          if (!tm.EstimateTime || tm.IsDeparted) continue;     // real-time only
          const m = Math.round((tm.Seconds != null ? tm.Seconds : 0) / 60);
          if (m >= 0) mins.push(m);
        }
      }
      mins.sort((a, b) => a - b);
      this.buShuttle = {
        hyatt: [...new Set(mins)], stop: 'AMESBURY @ VASSAR', dest: 'BU · GSU',
        vehicles, _live: true,
      };
      this._buHyattLive = true;
    } catch (e) { /* keep SIM (e.g. offline) */ }
  }
}

function haversineMi(lat1, lon1, lat2, lon2) {
  const R = 3958.7613, p1 = lat1 * Math.PI / 180, p2 = lat2 * Math.PI / 180;
  const dp = (lat2 - lat1) * Math.PI / 180, dl = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return Math.round(2 * R * Math.asin(Math.min(1, Math.sqrt(a))) * 10) / 10;
}

window.DataModel = DataModel;
