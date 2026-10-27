"""Downloads Bluebikes monthly trip files and keeps the trips touching Cambridge docks (short names M3...)."""
import io, os, sys, zipfile, urllib.request
import pandas as pd
BASE = "https://s3.amazonaws.com/hubway-data/"
months = [f"{y}{m:02d}" for y in range(2023, 2027) for m in range(1, 13) if f"{y}{m:02d}" <= "202609"]
os.makedirs("work/trips", exist_ok=True)
KEEP = ["ride_id","rideable_type","started_at","ended_at","start_station_name","start_station_id","end_station_name",
        "end_station_id","member_casual"]
def fetch(name):
    with urllib.request.urlopen(BASE + name, timeout=300) as r:
        return r.read()
for ym in months:
    out = f"work/trips/{ym}.parquet"
    if os.path.exists(out): continue
    for name in (f"{ym}-bluebikes-tripdata.zip", f"{ym}-bluebikes-tripdata.csv.zip", f"{ym}-bluebikes-tripdata.csv"):
        try:
            raw = fetch(name); break
        except Exception as e:
            raw = None
    if raw is None:
        print(ym, "missing", flush=True); continue
    if raw[:2] == b"PK":
        z = zipfile.ZipFile(io.BytesIO(raw))
        csvn = [n for n in z.namelist() if n.endswith(".csv") and not n.startswith("__MACOSX")][0]
        fh = z.open(csvn)
    else:
        fh = io.BytesIO(raw)
    df = pd.read_csv(fh, dtype=str, low_memory=False)
    cols = set(df.columns)
    if "rideable_type" not in cols:
        # the pre-April 2023 layout: no bike type, legacy numeric ids
        df = df.rename(columns={"starttime":"started_at","stoptime":"ended_at","start station name":"start_station_name",
                                "start station id":"start_station_id","end station name":"end_station_name",
                                "end station id":"end_station_id","usertype":"member_casual"})
        df["rideable_type"] = "unknown"; df["ride_id"] = ""
    df = df[[c for c in KEEP if c in df.columns]]
    m = df["start_station_id"].fillna("").str.startswith("M3") | df["end_station_id"].fillna("").str.startswith("M3")
    names = df["start_station_name"].fillna("") + "|" + df["end_station_name"].fillna("")
    m = m | names.str.contains("MIT|Sidney|Cambridge|Kendall|Central|Harvard", regex=True)
    df = df[m]
    df.to_parquet(out, index=False)
    print(ym, len(df), "kept, types:", df["rideable_type"].value_counts().to_dict(), flush=True)
