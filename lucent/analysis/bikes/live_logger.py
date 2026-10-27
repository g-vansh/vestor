"""Logs GBFS station_status for MIT-area docks once a minute, to live/status.csv."""
import csv, json, time, urllib.request, os, sys
URL = "https://gbfs.lyft.com/gbfs/2.3/bos/en/station_status.json"
INFO = "https://gbfs.lyft.com/gbfs/2.3/bos/en/station_information.json"
WANT = {"M32042","M32047","M32041","M32006","M32005","M32070","M32071","M32061","M32076","M32101","M32040","M32030"}
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent":"lucent-research"}), timeout=20) as r:
        return json.load(r)
info = get(INFO)["data"]["stations"]
ids = {s["station_id"]: s["short_name"] for s in info if s.get("short_name") in WANT}
path = "live/status.csv"
new = not os.path.exists(path)
f = open(path, "a", newline="")
w = csv.writer(f)
if new: w.writerow(["polled","short","last_reported","ebikes","bikes","docks","disabled","renting"])
last = {}
while True:
    try:
        d = get(URL); now = int(time.time())
        for s in d["data"]["stations"]:
            sh = ids.get(s["station_id"])
            if not sh: continue
            row = [now, sh, s.get("last_reported"), s.get("num_ebikes_available"), s.get("num_bikes_available"),
                   s.get("num_docks_available"), s.get("num_bikes_disabled"), s.get("is_renting")]
            w.writerow(row)
        f.flush()
    except Exception as e:
        print("err", e, file=sys.stderr)
    time.sleep(60 - time.time() % 60)
