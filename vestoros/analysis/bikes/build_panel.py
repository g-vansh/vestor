"""
15-minute panel per dock: e-bike arrivals, all e-bike departures, and departures while an e-bike was
certainly there, with that exposure time.

Stock is never published in the trip files, but a lower bound is: every e-bike that arrived and has
not been matched by a later departure is still docked (Lyft's van can only add more). While that bound
is at least one, a rider who wants an e-bike can take one, so departures in that time measure demand
without the stock-out censoring that raw counts carry. The bound is reset to zero at 3:00 each night,
when Lyft's crews may take bikes away for charging.
"""
import numpy as np, pandas as pd
ev = pd.read_parquet('work/events.parquet')
ev = ev[ev.type == 'electric_bike'].sort_values(['station', 't'])
SLOT = 15
rows = []
for st, g in ev.groupby('station'):
    t = g.t.values.astype('datetime64[s]').astype(np.int64)
    kind = (g.kind.values == 'arr')
    start = (t.min() // 86400) * 86400
    end = (t.max() // 86400 + 1) * 86400
    nslots = int((end - start) // (SLOT * 60))
    expo = np.zeros(nslots); dep_av = np.zeros(nslots); dep_all = np.zeros(nslots); arr = np.zeros(nslots)
    L = 0; last = start
    reset = start + 3 * 3600
    def add_expo(a, b):
        # spread [a, b) across slots
        while a < b:
            k = int((a - start) // (SLOT * 60))
            se = start + (k + 1) * SLOT * 60
            e = min(b, se)
            expo[k] += (e - a) / 60.0
            a = e
    for ti, isarr in zip(t, kind):
        while ti >= reset:
            if L >= 1: add_expo(last, reset)
            last = reset; L = 0; reset += 86400
        if L >= 1: add_expo(last, ti)
        last = ti
        k = int((ti - start) // (SLOT * 60))
        if isarr:
            arr[k] += 1; L += 1
        else:
            dep_all[k] += 1
            if L >= 1:
                dep_av[k] += 1; L -= 1
    idx = pd.to_datetime(start + np.arange(nslots) * SLOT * 60, unit='s')
    rows.append(pd.DataFrame({'station': st, 'slot_start': idx, 'arr': arr, 'dep_all': dep_all, 'dep_av': dep_av, 'expo_av': expo}))
p = pd.concat(rows, ignore_index=True)
p['date'] = p.slot_start.dt.normalize()
p['tod'] = p.slot_start.dt.hour * 4 + p.slot_start.dt.minute // 15
p.to_parquet('work/panel.parquet', index=False)
for st in ('vassar', 'erie'):
    q = p[(p.station == st) & (p.slot_start >= '2025-09-01')]
    m = q[(q.slot_start.dt.dayofweek < 5)].groupby('tod')[['arr', 'dep_all', 'dep_av', 'expo_av']].sum()
    days = q[q.slot_start.dt.dayofweek < 5].date.nunique()
    m['dep_rate_per_hr_when_avail'] = m.dep_av / (m.expo_av / 60)
    m['arr_per_hr'] = m.arr / days * 4
    m['dep_all_per_hr'] = m.dep_all / days * 4
    m['avail_share'] = m.expo_av / (days * 15)
    print('==', st, 'weekdays since Sept 2025, days', days)
    print(m.loc[24:44, ['arr_per_hr', 'dep_all_per_hr', 'dep_rate_per_hr_when_avail', 'avail_share']].round(2).to_string())
