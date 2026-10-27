"""Fetches the community status log (AdhamKhouly/bluebikes-logger) and keeps the Cambridge docks we model."""
import urllib.request, datetime, io, os
import pandas as pd
info = pd.read_json("https://gbfs.lyft.com/gbfs/2.3/bos/en/station_information.json")["data"]["stations"]
info = pd.DataFrame(info)
keep = info[info.short_name.str.startswith("M3")]
ids = dict(zip(keep.station_id, keep.short_name))
os.makedirs("work/status", exist_ok=True)
d0 = datetime.date(2026, 9, 17)
for i in range(0, 41):
    day = (d0 + datetime.timedelta(i)).isoformat()
    out = f"work/status/{day}.parquet"
    if os.path.exists(out): continue
    u = f"https://raw.githubusercontent.com/AdhamKhouly/bluebikes-logger/main/data/station_status_{day}.csv"
    try:
        raw = urllib.request.urlopen(u, timeout=300).read()
    except Exception as e:
        print(day, "missing", e, flush=True); continue
    df = pd.read_csv(io.BytesIO(raw), dtype=str)
    df = df[df.station_id.isin(ids)]
    df["short"] = df.station_id.map(ids)
    df.to_parquet(out, index=False)
    print(day, len(df), df.fetched_utc.nunique(), "snapshots", flush=True)
