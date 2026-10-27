"""Builds the feed fixtures the desktop tests parse. Shapes follow the live feeds as checked on
2026-10-27 (GBFS 2.3 station_status fields, adsb.lol v2 point, Open-Meteo, MBTA V3) and
the GTFS-realtime protobuf is written with Google's own bindings, so the Java reader is tested
against the reference encoder."""
import io, json, os, random, sys, zipfile, datetime
from google.transit import gtfs_realtime_pb2 as rt

out = sys.argv[1]
NOW = int(datetime.datetime(2026, 10, 27, 18, 5, tzinfo=datetime.timezone.utc).timestamp())  # 2:05 PM in Boston
random.seed(7)

# ---- GBFS ----
WESTGATE = dict(station_id="f8350cc2-0de8-11e7-991c-3863bb43a7d0", name="MIT Vassar St", short_name="M32042",
                lat=42.355601, lon=-71.103945, capacity=25, region_id="10")
PACIFIC = dict(station_id="f835141c-0de8-11e7-991c-3863bb43a7d0", name="MIT Pacific St at Purrington St",
               short_name="M32016", lat=42.35957, lon=-71.10129, capacity=19, region_id="10")
STATA = dict(station_id="f8349de4-0de8-11e7-991c-3863bb43a7d0", name="MIT Stata Center at Vassar St / Main St",
             short_name="M32005", lat=42.3621312344991, lon=-71.09115600585936, capacity=35, region_id="10")
AMHERST = dict(station_id="f83493c0-0de8-11e7-991c-3863bb43a7d0", name="MIT at Mass Ave / Amherst St",
               short_name="M32006", lat=42.3583056, lon=-71.0932723, capacity=31, region_id="10")
stations = [WESTGATE, PACIFIC, STATA, AMHERST]
for i in range(560):
    stations.append(dict(station_id="%08x-dead-beef-0000-%012x" % (i, i), name="Somewhere St at Elsewhere Ave %d" % i,
                         short_name="X%05d" % i, lat=42.2 + random.random() * 0.3, lon=-71.25 + random.random() * 0.3,
                         capacity=random.randint(11, 39), rental_uris={"android": "https://bluebikes.com/app?station_id=%d" % i}))
random.shuffle(stations)
json.dump({"data": {"stations": stations}, "last_updated": NOW - 30, "ttl": 60, "version": "2.3"},
          open(os.path.join(out, "gbfs_info.json"), "w"), separators=(",", ":"))

def status(s, bikes, ebikes, docks, with_ebike_field=True, renting=1):
    d = {"station_id": s["station_id"], "num_bikes_available": bikes, "num_bikes_disabled": 1,
         "num_docks_available": docks, "num_docks_disabled": 0, "is_installed": 1, "is_renting": renting,
         "is_returning": 1, "last_reported": NOW - 40,
         "vehicle_types_available": [{"vehicle_type_id": "1", "count": bikes - ebikes}, {"vehicle_type_id": "2", "count": ebikes}]}
    if with_ebike_field:
        d["num_ebikes_available"] = ebikes
        d["num_scooters_available"] = 0
    return d

st = []
for s in stations:
    if s is WESTGATE: st.append(status(s, 6, 0, 18))
    elif s is PACIFIC: st.append(status(s, 16, 1, 1, with_ebike_field=False))
    elif s is STATA: st.append(status(s, 9, 2, 25, renting=1))
    elif s is AMHERST: st.append(status(s, 4, 3, 26, renting=0))
    else:
        b = random.randint(0, s["capacity"]); e = random.randint(0, b)
        st.append(status(s, b, e, s["capacity"] - b))
json.dump({"data": {"stations": st}, "last_updated": NOW - 20, "ttl": 60, "version": "2.3"},
          open(os.path.join(out, "gbfs_status.json"), "w"), separators=(",", ":"))
# The same feed a few polls later: an e-bike has been docked at Westgate.
for d in st:
    if d["station_id"] == WESTGATE["station_id"]:
        d.update(num_bikes_available=7, num_ebikes_available=1, num_docks_available=17,
                 vehicle_types_available=[{"vehicle_type_id": "1", "count": 6}, {"vehicle_type_id": "2", "count": 1}])
json.dump({"data": {"stations": st}, "last_updated": NOW + 10, "ttl": 60, "version": "2.3"},
          open(os.path.join(out, "gbfs_status_found.json"), "w"), separators=(",", ":"))

# ---- MIT Passio GTFS static + realtime ----
z = io.BytesIO()
with zipfile.ZipFile(z, "w") as zf:
    zf.writestr("routes.txt", "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\r\n"
                "63220,mit,,Tech Shuttle,3,A31F34\r\n56642,mit,,Tech Shuttle2,3,8A8B8C\r\n71674,mit,,Tech Shuttle2 copy,3,8A8B8C\r\n"
                "56140,mit,,Saferide Campus,3,000000\r\n55555,mit,,\"Saferide Cambridge West\",3,000000\r\n61000,mit,,Boston Daytime,3,FFFFFF\r\n")
    zf.writestr("trips.txt", "﻿route_id,service_id,trip_id,trip_headsign\n63220,wk,T-1,Kendall\n63220,wk,T-2,Kendall\n"
                "56642,wk,T2-1,\"Tech, NW\"\n71674,wk,T2C-1,NW\n56140,ev,SR-1,Campus\n55555,ev,SRW-1,West\n61000,wk,BD-1,Boston\n")
    zf.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n180113,Grad Junction West,42.3546,-71.1102\n"
                "3813,W98 @ Vassar St,42.3555,-71.1038\n179825,Grad Junction East,42.3548,-71.1094\n")
open(os.path.join(out, "mit_gtfs.zip"), "wb").write(z.getvalue())

m = rt.FeedMessage()
m.header.gtfs_realtime_version = "2.0"
m.header.timestamp = NOW
def trip(eid, trip_id, route_id, stops, deleted=False):
    e = m.entity.add(); e.id = eid
    if deleted: e.is_deleted = True
    tu = e.trip_update
    tu.trip.trip_id = trip_id
    if route_id: tu.trip.route_id = route_id
    tu.vehicle.id = "bus-" + eid
    for stop, secs, kind in stops:
        u = tu.stop_time_update.add(); u.stop_id = stop
        ev = u.arrival if kind == "a" else u.departure
        ev.time = NOW + secs
        if kind == "a": ev.delay = 30
trip("1", "T-1", "63220", [("179825", 60, "a"), ("180113", 4 * 60 + 10, "a"), ("3813", 9 * 60, "a")])
trip("2", "T-2", None, [("180113", 16 * 60 - 20, "a")])                      # route via trips.txt
trip("3", "T2-1", "56642", [("180113", 9 * 60, "d")])                        # departure only
trip("4", "T2C-1", "71674", [("180113", 24 * 60, "a"), ("180113", -90, "a")])  # one past arrival
trip("5", "SR-1", "56140", [("3813", 7 * 60, "a"), ("180113", 3 * 60, "a")])   # SafeRide counts only at W98
trip("6", "SRW-1", None, [("3813", 22 * 60, "a")])                           # SafeRide by name
trip("7", "BD-1", "61000", [("180113", 2 * 60, "a")])                        # not one of ours
trip("8", "T-1", "63220", [("180113", 60, "a")], deleted=True)                # deleted entity
v = m.entity.add(); v.id = "veh-1"; v.vehicle.trip.trip_id = "T-1"; v.vehicle.position.latitude = 42.35; v.vehicle.position.longitude = -71.1
open(os.path.join(out, "mit_trips.pb"), "wb").write(m.SerializeToString())

# ---- MBTA V3 (JSON:API), route 85 at the Westgate stops ----
def iso(sec):
    t = datetime.datetime.fromtimestamp(NOW + sec, tz=datetime.timezone(datetime.timedelta(hours=-4)))
    return t.isoformat(timespec='seconds')
def pred(pid, sec, direction, stop, trip, rel=None, dep=True):
    return {"type": "prediction", "id": pid,
            "attributes": {"arrival_time": iso(sec), "departure_time": iso(sec) if dep else None, "direction_id": direction,
                           "schedule_relationship": rel, "status": None, "stop_sequence": 13 if direction == 0 else 21,
                           "arrival_uncertainty": None, "departure_uncertainty": None, "update_type": None, "revenue": "REVENUE"},
            "relationships": {"route": {"data": {"id": "85", "type": "route"}}, "stop": {"data": {"id": stop, "type": "stop"}},
                              "trip": {"data": {"id": trip, "type": "trip"}}, "vehicle": {"data": {"id": "y0888", "type": "vehicle"}}}}
mbta = {"data": [
    pred("p1", 60, 0, "21772", "t1", rel="SKIPPED"),          # skipped trip: ignored
    pred("p2", 130, 1, "22173", "t2"),                        # 2 min to Ruggles
    pred("p3", 400, 0, "21772", "t3"),                        # 7 min to Assembly
    pred("p4", 750, 1, "22173", "t4", dep=False),             # arrival only: 13 min
    pred("p5", 1380, 0, "21772", "t5"),                       # 23 min to Assembly
    pred("p6", 300, 1, "21772", "t6"),                        # wrong direction for this stop: ignored
    pred("p7", -120, 0, "21772", "t7"),                       # already gone: ignored
], "jsonapi": {"version": "1.0"}}
json.dump(mbta, open(os.path.join(out, "mbta_predictions.json"), "w"))
sched = {"data": [], "jsonapi": {"version": "1.0"}}
for k, sec in enumerate([-3600, -600, 900, 2700, 4500]):
    sched["data"].append({"type": "schedule", "id": "s0-%d" % k, "attributes": {"arrival_time": iso(sec), "departure_time": iso(sec),
        "direction_id": 0, "stop_headsign": None}, "relationships": {"stop": {"data": {"id": "21772", "type": "stop"}},
        "route": {"data": {"id": "85", "type": "route"}}}})
    sched["data"].append({"type": "schedule", "id": "s1-%d" % k, "attributes": {"arrival_time": iso(sec + 300), "departure_time": iso(sec + 300),
        "direction_id": 1, "stop_headsign": None}, "relationships": {"stop": {"data": {"id": "22173", "type": "stop"}},
        "route": {"data": {"id": "85", "type": "route"}}}})
json.dump(sched, open(os.path.join(out, "mbta_schedules.json"), "w"))

# ---- adsb.lol v2 point ----
ac = {"ac": [
    {"hex": "a793fd", "type": "adsb_icao", "r": "N588DT", "t": "A21N", "alt_baro": "ground", "gs": 0.0, "lat": 42.373375, "lon": -71.028141, "dst": 3.68, "dir": 71.6},
    {"hex": "acf906", "type": "adsb_icao", "flight": "JBU1387 ", "r": "N935JB", "t": "A321", "alt_baro": 12400, "alt_geom": 12650, "gs": 289.4, "track": 246.3, "baro_rate": -1088, "squawk": "3341", "category": "A3", "lat": 42.3612, "lon": -71.0702, "dst": 1.8, "dir": 70.1},
    {"hex": "a1b2c3", "type": "adsb_icao", "flight": "DAL2214 ", "r": "N812DN", "t": "A321", "alt_baro": 31000, "gs": 455.0, "track": 52.0, "baro_rate": 0, "lat": 42.52, "lon": -71.31, "dst": 13.2, "dir": 300.0},
    {"hex": "c0ffee", "type": "adsb_icao", "flight": "N12345  ", "r": "N12345", "t": "C172", "alt_baro": 2100, "gs": 96.0, "track": 180.0, "geom_rate": 320, "lat": 42.33, "lon": -71.12, "dst": 1.5, "dir": 200.0},
    {"hex": "abc123", "type": "mlat", "r": "N1", "t": "B738", "alt_baro": 8000, "gs": 250.0, "lat": 42.30, "lon": -71.0, "dst": 6.0},
], "msg": "No error", "now": (NOW) * 1000, "total": 5, "ctime": NOW * 1000, "ptime": 0}
json.dump(ac, open(os.path.join(out, "adsb.json"), "w"))
json.dump({"flight": "JBU1387", "route": "KBOS-KSFO", "updatetime": NOW - 86400}, open(os.path.join(out, "hexdb_route.json"), "w"))
json.dump({"airport": "Logan International Airport", "country_code": "US", "iata": "BOS", "icao": "KBOS", "latitude": 42.3643, "longitude": -71.005, "region_name": "Massachusetts"}, open(os.path.join(out, "hexdb_kbos.json"), "w"))
json.dump({"airport": "San Francisco International Airport", "country_code": "US", "iata": "SFO", "icao": "KSFO", "latitude": 37.62, "longitude": -122.37, "region_name": "California"}, open(os.path.join(out, "hexdb_ksfo.json"), "w"))

# ---- Open-Meteo (Celsius) ----
base = datetime.datetime(2026, 10, 27, 14, 0)
hours = [(base + datetime.timedelta(hours=h)).strftime("%Y-%m-%dT%H:%M") for h in range(7)]
w = {"latitude": 42.37, "longitude": -71.11, "timezone": "America/New_York", "utc_offset_seconds": -14400,
     "current_units": {"temperature_2m": "°C"},
     "current": {"time": "2026-10-27T14:00", "interval": 900, "temperature_2m": 11.4, "apparent_temperature": 9.2, "is_day": 1,
                 "weather_code": 2, "relative_humidity_2m": 58, "wind_speed_10m": 14.3, "wind_direction_10m": 250},
     "hourly": {"time": hours, "temperature_2m": [11.4, 11.9, 11.2, 10.1, 8.7, 7.6, 6.9], "weather_code": [2, 2, 3, 3, 61, 61, 3], "precipitation_probability": [5, 10, 20, 45, 70, 65, 25],
                "is_day": [1, 1, 1, 1, 0, 0, 0]},
     "daily": {"time": ["2026-10-27", "2026-10-28"], "temperature_2m_max": [13.1, 12.0], "temperature_2m_min": [5.2, 4.4]}}
json.dump(w, open(os.path.join(out, "weather.json"), "w"), ensure_ascii=False)
print("NOW", NOW)
