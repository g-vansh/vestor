"""E-bike departures and arrivals at the docks we model, one row per event, local wall time."""
import pandas as pd
S = {'M32042':'vassar','M32047':'erie','M32041':'pacific','M32006':'mass_amherst','M32005':'stata','M32070':'carleton','M32071':'hayward'}
t = pd.read_parquet('work/trips_all.parquet', columns=['rideable_type','started_at','ended_at','start_station_id','end_station_id','member_casual'])
t = t[t.started_at >= '2024-03-01']
ev = []
for code, name in S.items():
    d = t[t.start_station_id == code]
    ev.append(pd.DataFrame({'station': name, 'kind': 'dep', 'type': d.rideable_type.values, 't': d.started_at.values,
                            'other': d.end_station_id.values, 'member': d.member_casual.values}))
    a = t[t.end_station_id == code]
    ev.append(pd.DataFrame({'station': name, 'kind': 'arr', 'type': a.rideable_type.values, 't': a.ended_at.values,
                            'other': a.start_station_id.values, 'member': a.member_casual.values}))
ev = pd.concat(ev, ignore_index=True).sort_values(['station','t']).reset_index(drop=True)
ev.to_parquet('work/events.parquet', index=False)
print(ev.groupby(['station','kind','type']).size().unstack())
# Same-station round trips and very short rides tell us about failed unlocks and re-docks.
e = t[(t.rideable_type=='electric_bike') & (t.start_station_id=='M32042')]
dur = (e.ended_at - e.started_at).dt.total_seconds()
print('vassar e-bike departures', len(e), 'returned to same dock within 2 min:', ((e.end_station_id=='M32042') & (dur<120)).sum())
