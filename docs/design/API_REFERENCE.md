# VESTOR — Live Data API Reference

Every data source the wall uses. **All public; no scraping.** Geo anchor =
**540 Memorial Drive, Cambridge MA → 42.354, -71.107**. Endpoints marked
**CORS-OK** work directly from the browser simulator; the rest are fetched by the
Pi's Python clients (server-side). Verified status as of 2026-06-15 noted inline.

---

## 1. Flights — ADS-B  *(no key · verified 200)*
Primary live aircraft feed within range of the anchor.

- **Aircraft in range (Pi/Python):**
  `https://api.adsb.lol/v2/point/42.354/-71.107/60` (lat, lon, radius nm)
- **Aircraft in range (browser):**
  `https://api.airplanes.live/v2/point/42.354/-71.107/60`
  > ⚠️ **CORS split (verified 2026-06-15):** `adsb.lol` and `adsb.fi` **omit
  > `Access-Control-Allow-Origin`**, so the browser sim gets *"Failed to fetch"*.
  > `airplanes.live` **sends CORS headers** and returns the **identical `ac`
  > schema** (all three are readsb mirrors of the same community feed). The sim
  > therefore reads `airplanes.live`; the Pi's Python client reads `adsb.lol`
  > (no CORS server-side). Only the base URL differs.
- **Fields:** `hex`, `flight` (callsign), `r` (registration), `t` (ICAO type),
  `alt_baro` (ft, **or the string `"ground"`** — coerce defensively!), `gs`
  (ground speed kt), `track` (deg; `dir` as fallback), `baro_rate` (vertical
  fpm; `geom_rate` fallback), `lat`, `lon`, `squawk`, `category`.
- **Route enrichment (callsign → airports):** `https://hexdb.io/api/v1/route/icao/{callsign}`
  → `{"route":"KLGB-KSFO"}` (ICAO pair). CORS-OK; ~50 % of callsigns have data
  (others 404 — treat as "no route", cache the miss).
- **Airport → IATA + city:** `https://hexdb.io/api/v1/airport/icao/{icao}` →
  `{"iata":"BOS","airport":"Logan International Airport","region_name":"Massachusetts"}`.
- **Aircraft → type + operator:** `https://hexdb.io/api/v1/aircraft/{hex}` →
  `{"Type":"767 224ER","RegisteredOwners":"Omni Air International","OperatorFlagCode":"OAE"}`.
- **Distance:** computed client-side via haversine from the anchor (miles).
- **Caching:** enrichment is rate-limited — cache route/airport/aircraft lookups
  with a TTL (Python client: 1 h; browser: per-session) so 60 s polling does not
  hammer hexdb.

Scene fields consumed: `callsign, reg, type, origin, dest, originCity, destCity,
alt, gs, track, vspeed, distance, hex`. Implemented in `tools/clients/flights.py`
(Pi) and `sim/data.js` `_liveFlights()` (browser).

---

## 2. Weather — Open-Meteo  *(no key · CORS-OK · verified 200)*
Free, no key, returns one unit set per call; we render both °C and °F by
converting (`F = C×9/5+32`).

```
https://api.open-meteo.com/v1/forecast
  ?latitude=42.354&longitude=-71.107
  &current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,
           precipitation,weather_code,wind_speed_10m,wind_direction_10m
  &daily=sunrise,sunset,temperature_2m_max,temperature_2m_min
  &timezone=America/New_York
```
- **`current.weather_code`** is a **WMO code** → icon/label map (in
  `sim/scenes.js` `WMO_ICON` / `WMO_TEXT`):
  `0` clear · `1–2` mostly clear/partly · `3` overcast · `45/48` fog ·
  `51–67` drizzle/rain · `71–77` snow · `80–82` showers · `95–99` thunder.
- Wind speed default km/h; add `&wind_speed_unit=mph` if preferred.
- **Air Quality** (separate host, same style, CORS-OK):
  `https://air-quality-api.open-meteo.com/v1/air-quality?latitude=42.354&longitude=-71.107&current=us_aqi,pm2_5`

---

## 3. Bluebikes — GBFS 2.3 (Lyft)  *(no key · CORS-OK · verified 200)*
General Bikeshare Feed Spec. The brief's station is **MIT Pacific St at
Purrington St** (nearest to 540 Memorial Dr).

- **Discovery:** `https://gbfs.lyft.com/gbfs/2.3/bos/en/gbfs.json`
- **Live status:** `https://gbfs.lyft.com/gbfs/2.3/bos/en/station_status.json`
- **Static info:** `https://gbfs.lyft.com/gbfs/2.3/bos/en/station_information.json`
- **Station id:** `f835141c-0de8-11e7-991c-3863bb43a7d0`
  (lat 42.35957, lon -71.10129, capacity 19).
- **Fields:** `num_bikes_available`, `num_ebikes_available`,
  `num_docks_available`, `vehicle_types_available[]`. TTL 60 s.
- **Classic vs e-bike:** `classic = num_bikes_available − num_ebikes_available`;
  `ebikes = num_ebikes_available`. *(Verified live sample: 17 bikes / 0 ebikes /
  0 docks — the exact split is what the Bluebikes zone shows.)*

---

## 4. MIT Tech Shuttle — Passio GTFS-realtime  *(no key · server-side · verified 2026-06-15)*
MIT's shuttles run on **Passio GO** (agency `mit`). The per-stop ETA REST
endpoint is disabled, so use **GTFS-realtime TripUpdates** (protobuf).

- **TripUpdates:** `https://passio3.com/mit/passioTransit/gtfs/realtime/tripUpdates`
- **VehiclePositions:** `https://passio3.com/mit/passioTransit/gtfs/realtime/vehiclePositions`
- **Static GTFS:** `https://passio3.com/mit/passioTransit/gtfs/google_transit.zip`
  (route/stop names, schedules — 9 files, ~50 KB).
- **Routes serving Grad Junction West** (verified against the live static GTFS):
  - `63220` **"Tech Shuttle"** → line A (`tech`)
  - `56642` **"Tech Shuttle2"** + `71674` **"Tech Shuttle2 copy"** → line B
    (`tech_nw`; the two ids are the same line / second vehicle)
  > ⚠️ **Correction:** earlier research listed *Tech Shuttle NW = `63319`* — that
  > route **does not exist** in the current feed. There is **no separately-named
  > "NW" route**; the closest match is the **"Tech Shuttle 2"** line above, which
  > is what the `tech_nw` field now carries.
- **Stop:** Grad Junction West = **`180113`** ✓ (Grad Junction East = `179825`).
- **How to read arrivals:** parse the protobuf `FeedMessage`; for each `entity`
  with a `trip_update`, resolve the route (`trip.route_id` **or**, when absent,
  `trip.trip_id` → static `trips.txt`), keep the Tech routes, find the
  `stop_time_update` whose `stop_id == 180113`, read `arrival.time` (epoch),
  subtract `now` → minutes. Decode with `gtfs-realtime-bindings`
  (`pip install gtfs-realtime-bindings protobuf`).
- **Notes:** RT shares the static `stop_id` namespace, so `180113` matches
  directly. The Tech lines are **daytime services** — overnight the TripUpdates
  feed is an empty 15-byte header (only Saferide runs), so `fetch()` correctly
  returns empty lists (display shows "—"). The browser sim keeps **synthetic**
  shuttle data (decoding protobuf in-browser would break the zero-dependency
  design); the Pi's `tools/clients/mit_shuttle.py` reads the real feed.

Scene fields consumed: `shuttle.tech[]`, `shuttle.techNW[]` (minutes, ascending).

---

## 5. MBTA Red Line — MBTA v3  *(free key recommended)*
Kendall/MIT is the closest T stop.

- `https://api-v3.mbta.com/predictions?filter[stop]=place-knncl&filter[route]=Red&sort=arrival_time`
- Stop id **`place-knncl`** (Kendall/MIT). Branch by `direction_id`
  (0 = southbound Ashmont/Braintree, 1 = northbound Alewife).
- Get a free key at `https://api-v3.mbta.com/` to raise rate limits
  (`?api_key=…` or `x-api-key` header). Works keyless at low volume.

---

## 6. ISS overhead — wheretheiss.at  *(no key · CORS-OK)*
- `https://api.wheretheiss.at/v1/satellites/25544` → `latitude`, `longitude`,
  `altitude`, `velocity`, `visibility`.
- "Overhead" = sub-satellite point within ~5° of the anchor; otherwise estimate
  minutes-to-pass from ground-track. Drives the "LOOK UP!" card.

---

## 7. Charles River / Boston tide — NOAA CO-OPS  *(no key)*
- `https://api.tidesandcurrents.noaa.gov/api/prod/datagetter?station=8443970&product=predictions&interval=hilo&datum=MLLW&units=english&time_zone=lst_ldt&format=json&date=today`
- Station **8443970** (Boston). `interval=hilo` gives next high/low; derive
  rising/falling state + next event time.

---

## Source summary

| Domain | Provider | Key? | CORS | Used by | Status |
|---|---|---|---|---|---|
| Flights (sim) | airplanes.live + hexdb.io | no | **yes** | sim | ✅ live |
| Flights (Pi) | adsb.lol + hexdb.io | no | no | Pi (server) | ✅ live |
| Weather | Open-Meteo | no | **yes** | sim + Pi | ✅ live |
| Air Quality | Open-Meteo AQ | no | **yes** | sim + Pi | ready |
| Bluebikes | GBFS 2.3 (Lyft) | no | **yes** | sim + Pi | ✅ live |
| MIT Shuttle | Passio GTFS-rt | no | no (protobuf) | Pi (server) | ✅ live¹ |
| MBTA Red | MBTA v3 | optional | yes | Pi | ready |
| ISS | wheretheiss.at | no | **yes** | sim + Pi | ready |
| Tide | NOAA CO-OPS | no | yes | Pi | ready |

¹ Verified end-to-end; arrival lists empty overnight (Tech is daytime-only).

The simulator's **LIVE** toggle uses the CORS-OK rows directly (weather, bikes,
**airplanes.live** flights); the Python clients in `tools/clients/` cover every
row server-side. Run `python3 -m tools.clients.test_live` for a live smoke test.
